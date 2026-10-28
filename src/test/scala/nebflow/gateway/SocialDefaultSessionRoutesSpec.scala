package nebflow.gateway

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite
import nebflow.agent.SharedResources
import nebflow.llm.ModelCandidate
import nebflow.shared.{NebflowServiceConfig, PathUtil, ServiceLlmConfig}
import org.http4s.*
import org.http4s.server.websocket.WebSocketBuilder2
import org.typelevel.ci.CIString

import java.nio.file.Files

/** 绑定即通批 (weixin bind-through, 2026-10-11) — the per-channel
  * default-session REST face at the wire level, plus the feishu regression.
  *
  *   - the literal feishu arms (pre-existing) keep their exact shapes: PUT set
  *     → `{"ok":true,"sessionId":…}` / clear (null|blank) → sessionId null;
  *     GET → `{"sessionId":…}`; a non-JSON body → `400 invalid_field`;
  *   - the parameterized spelling PUT/GET
  *     `/social/channels/{channel}/default-session` serves the SAME contract
  *     for `weixin-ilink` (the card the scan-bind auto-wire writes), whitelist
  *     {feishu, weixin-ilink};
  *   - an off-whitelist channel → `400 unknown_channel` with the channel
  *     echoed (the social save face's existing unknown-card shape);
  *   - every arm sits behind withAuth (no token ⇒ the bare
  *     `{"error":"Unauthorized"}` 403);
  *   - the stored key is the channel-entry SIBLING `defaultSessionId` (never a
  *     card `field`) — the same position the feishu C4 batch pinned.
  *
  * Fixture root is a temp directory; the real `~/.nebflow` is never touched.
  */
class SocialDefaultSessionRoutesSpec extends FunSuite:

  private val token = "default-session-routes-token"

  private def json(s: String): Json = parse(s).fold(e => throw e, identity)

  /** Isolated data root + live routes for one case (SecretsRoutesSpec shape). */
  private def withRoutes(f: HttpRoutes[IO] => Unit): Unit =
    val root = os.Path(Files.createTempDirectory("default-session-routes-spec").toRealPath().toString)
    val previous = PathUtil.dataRoot
    val (dispatcher, releaseDispatcher) = Dispatcher.parallel[IO].allocated.unsafeRunSync()
    try
      PathUtil.setDataRoot(root)
      val cfg = NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
      val inner = new RestApiRoutes(
        tokenRef = cats.effect.Ref.unsafe[IO, String](token),
        configRef = cats.effect.Ref.unsafe[IO, NebflowServiceConfig](cfg),
        sharedResources = SharedResources(
          llm = null,
          dispatcher = dispatcher,
          sessionStore = null,
          projectRoot = os.pwd,
          thinkingConfigRef = null,
          rateLimiter = null,
          fileChangeTracker = null,
          contextWindow = 100_000,
          agentLibrary = null,
          taskStore = null,
          historyArchiver = null,
          fileLockManager = null,
          sessionModelOverrides = cats.effect.Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
          providerRegistry = null,
          healthMonitor = null,
          actorSystem = null,
          voiceMutedRef = cats.effect.Ref.unsafe[IO, Boolean](false),
          daemonService = None
        ),
        sessionStore = null,
        wsRoutes = null
      )
      val wsb = null.asInstanceOf[WebSocketBuilder2[IO]]
      f(inner.routes <+> inner.presenceWsRoutes(wsb))
    finally
      PathUtil.setDataRoot(previous)
      releaseDispatcher.unsafeRunSync()
      try os.remove.all(root)
      catch case _: Throwable => ()

  // HTTP method aliases (http4s `Method.GET` etc.).
  private val GET: Method = Method.GET
  private val PUT: Method = Method.PUT

  private def call(
      routes: HttpRoutes[IO],
      method: Method,
      path: String,
      body: Option[Json] = None,
      bearer: Option[String] = Some(token)
  ): (Status, Option[Json]) =
    val base = Request[IO](method, Uri.unsafeFromString(path))
    val authed = bearer.fold(base)(t => base.putHeaders(Header.Raw(CIString("Authorization"), s"Bearer $t")))
    val req = body.fold(authed)(j => authed.withEntity(j.noSpaces))
    val maybeResp: Option[Response[IO]] = routes(req).value.unsafeRunSync()
    if maybeResp.isEmpty then fail(s"route fell through: $method $path")
    else
      val resp = maybeResp.get
      val text = resp.body.compile.toVector.unsafeRunSync().map(_.toChar).mkString
      (resp.status, if text.isEmpty then None else parse(text).toOption)

  /** The stored defaultSessionId as the frontend's raw config would read it:
    * the channel-entry sibling (NOT a member of `fields`). */
  private def storedSibling(root: os.Path, channel: String): Option[String] =
    nebflow.social.SocialChannels.readChannels(root).hcursor
      .downField("channels").downField(channel)
      .downField("defaultSessionId").as[String].toOption

  // ── auth ────────────────────────────────────────────────────────────────

  test("A1 · no token ⇒ 403 Unauthorized on every default-session arm, no reason detail") {
    withRoutes { routes =>
      val checks = List(
        call(routes, PUT, "/social/channels/feishu/default-session", Some(json("""{"sessionId":"s"}""")), bearer = None),
        call(routes, GET, "/social/channels/feishu/default-session", bearer = None),
        call(routes, PUT, "/social/channels/weixin-ilink/default-session", Some(json("""{"sessionId":"s"}""")), bearer = None),
        call(routes, GET, "/social/channels/weixin-ilink/default-session", bearer = None)
      )
      checks.foreach { case (st, body) =>
        assertEquals(st.code, 403, "all four arms sit behind withAuth")
        assertEquals(body, Some(json("""{"error":"Unauthorized"}""")))
      }
    }
  }

  // ── the literal feishu pair: byte-identical regression ──────────────────

  test("A2 · feishu literal arms: set / read / clear / invalid body — shapes unchanged") {
    withRoutes { routes =>
      // set
      val (st1, b1) = call(routes, PUT, "/social/channels/feishu/default-session",
        Some(json("""{"sessionId":"sess-feishu-1"}""")))
      assertEquals(st1.code, 200)
      assertEquals(b1, Some(json("""{"ok":true,"sessionId":"sess-feishu-1"}""")))
      // read
      val (st2, b2) = call(routes, GET, "/social/channels/feishu/default-session")
      assertEquals(st2.code, 200)
      assertEquals(b2, Some(json("""{"sessionId":"sess-feishu-1"}""")))
      // stored as the channel-entry sibling
      assertEquals(storedSibling(PathUtil.dataRoot, "feishu"), Some("sess-feishu-1"))
      // clear (null)
      val (st3, b3) = call(routes, PUT, "/social/channels/feishu/default-session",
        Some(json("""{"sessionId":null}""")))
      assertEquals(st3.code, 200)
      assertEquals(b3, Some(json("""{"ok":true,"sessionId":null}""")))
      val (st4, b4) = call(routes, GET, "/social/channels/feishu/default-session")
      assertEquals(b4, Some(json("""{"sessionId":null}""")))
      // invalid body
      val (st5, b5) = call(routes, PUT, "/social/channels/feishu/default-session", None)
      assertEquals(st5.code, 400)
      assertEquals(b5, Some(json("""{"error":"invalid_field","reason":"request body must be a JSON object"}""")))
    }
  }

  // ── the parameterized face: weixin-ilink ────────────────────────────────

  test("A3 · weixin-ilink via the parameterized arms: set / read / clear / blank clears / invalid body") {
    withRoutes { routes =>
      val put = "/social/channels/weixin-ilink/default-session"
      val get = "/social/channels/weixin-ilink/default-session"
      // set
      val (st1, b1) = call(routes, PUT, put, Some(json("""{"sessionId":"sess-wx-1"}""")))
      assertEquals(st1.code, 200)
      assertEquals(b1, Some(json("""{"ok":true,"sessionId":"sess-wx-1"}""")))
      // read
      val (st2, b2) = call(routes, GET, get)
      assertEquals(st2.code, 200)
      assertEquals(b2, Some(json("""{"sessionId":"sess-wx-1"}""")))
      // the sibling position — the same field the feishu card carries (C4)
      assertEquals(storedSibling(PathUtil.dataRoot, "weixin-ilink"), Some("sess-wx-1"))
      // blank sessionId clears (the literal pair's body rule, verbatim)
      val (st3, b3) = call(routes, PUT, put, Some(json("""{"sessionId":"   "}""")))
      assertEquals(st3.code, 200)
      assertEquals(b3, Some(json("""{"ok":true,"sessionId":null}""")))
      val (st4, _) = call(routes, GET, get)
      assertEquals(st4.code, 200)
      // non-JSON body
      val (st5, b5) = call(routes, PUT, put)
      assertEquals(st5.code, 400)
      assertEquals(b5, Some(json("""{"error":"invalid_field","reason":"request body must be a JSON object"}""")))
    }
  }

  // ── the whitelist: off-whitelist channel is refused ─────────────────────

  test("A4 · off-whitelist channel ⇒ 400 unknown_channel with the channel echoed, both methods") {
    withRoutes { routes =>
      val checks = List(
        call(routes, PUT, "/social/channels/telegram/default-session", Some(json("""{"sessionId":"s"}"""))),
        call(routes, GET, "/social/channels/telegram/default-session"),
        call(routes, PUT, "/social/channels/wechat/default-session", Some(json("""{"sessionId":"s"}"""))),
        call(routes, GET, "/social/channels/nonsense/default-session")
      )
      checks.foreach { case (st, body) =>
        assertEquals(st.code, 400, "off-whitelist channels are refused with the social-face shape")
        assert(clue(body).exists(_.hcursor.downField("error").as[String].toOption.contains("unknown_channel")))
      }
      // nothing was written for the refused channel
      assertEquals(storedSibling(PathUtil.dataRoot, "telegram"), None)
    }
  }

end SocialDefaultSessionRoutesSpec
