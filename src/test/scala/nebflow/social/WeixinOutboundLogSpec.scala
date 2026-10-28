package nebflow.social

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.bridge.BridgeContext
import nebflow.shared.SessionMeta

import java.net.{InetSocketAddress, ServerSocket}
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import scala.jdk.CollectionConverters.*

/**
 * 失败必打日志 spec (the send-failure observability batch, 2026-10-08): a
 * failed weixin OUTBOUND send is NEVER silent — the engine-side incident that
 * motivated this (2026-10-08 21:52–22:51) saw 9 replies die on the shim's 502
 * (`sendmessage ret=-2`) with ZERO engine log lines. The WARN now lives at the
 * single chokepoint [[WeixinOutboundAdapter.postLive]] (both seams — text and
 * media — and every bridge leg funnel through it), one line per failed send:
 * `channel=weixin-ilink to=<recipient> code=<status> reason=<stable vocabulary
 * + ret detail> digest=<first 50 chars / media path> attempts=1`. The success
 * path must stay SILENT (zero hot-path noise).
 *
 * Offline discipline (the WeixinOutboundSpec rule, plus the loopback exception
 * SttServiceProtocolSpec set): no external network — the side-car is a local
 * `com.sun.net.httpserver` stub on 127.0.0.1:0, the transport arm uses a dead
 * loopback port (bind-0, take the port, close — connection refused exercises
 * the same `handleErrorWith` containment arm a timeout does, without waiting
 * out the 40s budget), and the token is minted into a temp data root. The
 * assertions read a logback `ListAppender` attached to the adapter's own
 * logger (`nebflow.social.weixin-outbound` — precedent:
 * `WeixinIlinkBridgeSpec`'s withAppender, `SttServiceProtocolSpec`'s WARN
 * 实发钉子): a WARN that is declared but never fires fails these tests, which
 * is exactly the hole this batch closes.
 */
class WeixinOutboundLogSpec extends CatsEffectSuite:

  // ───────────────────────── harness ─────────────────────────

  private def tmpRoot(): os.Path = os.temp.dir(prefix = "nb-weixin-outbound-log-")

  private def withAppender[A](
    body: ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent] => A,
    loggerNames: List[String] = List("nebflow.social.weixin-outbound")
  ): A =
    val lbs = loggerNames.map(n =>
      org.slf4j.LoggerFactory.getLogger(n).asInstanceOf[ch.qos.logback.classic.Logger])
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    appender.start()
    lbs.foreach(_.addAppender(appender))
    try body(appender)
    finally lbs.foreach(_.detachAppender(appender))

  /** The formatted lines the appender captured (ANSI prefix included by
    * NebflowLogger — assertions match the clean kv tail after `" - "`). */
  private def lines(
    appender: ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
  ): List[String] =
    appender.list.asScala.toList.map(_.getFormattedMessage)

  /** The WARN-level lines captured AFTER the given snapshot index. Slice
    * FIRST, then filter: `before` is counted on the UNfiltered list, so a
    * mint/start INFO inside the snapshot must never eat a post-snapshot WARN
    * (the off-by-one the first draft had — WL2 proved it: the WARN fired and
    * was captured, the drop ate it). */
  private def newWarns(
    appender: ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent],
    before: Int
  ): List[String] =
    appender.list.asScala.toList.drop(before)
      .filter(_.getLevel == ch.qos.logback.classic.Level.WARN)
      .map(_.getFormattedMessage)

  /** A loopback stub answering EVERY request with the fixed status + body
    * (drain-first, the JDK handler rule). */
  private final class StubHandler(status: Int, responseBody: String) extends HttpHandler:
    def handle(exchange: HttpExchange): Unit =
      exchange.getRequestBody.readAllBytes() // drain
      val bytes = responseBody.getBytes(StandardCharsets.UTF_8)
      exchange.sendResponseHeaders(status, bytes.length)
      exchange.getResponseBody.write(bytes)
      exchange.close()

  private def withStub[A](status: Int, responseBody: String)(f: (HttpServer, Int) => A): A =
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/", new StubHandler(status, responseBody))
    server.setExecutor(Executors.newCachedThreadPool())
    server.start()
    try f(server, server.getAddress.getPort)
    finally server.stop(0)

  /** A dead loopback port: bind-0, take the number, close. Connecting to it
    * refuses — the same containment arm (`sidecar_unreachable`) a timeout
    * takes, offline and instant. */
  private def deadPort(): Int =
    val s = new ServerSocket(0)
    val p = s.getLocalPort
    s.close()
    p

  /** Mint the send token into the temp root (the real mint — the spec never
    * fakes the credential face), then point the stored card at the stub. */
  private def armLeg(root: os.Path, port: Int): IO[Unit] =
    for
      _ <- WeixinOutboundAdapter.ensureToken(root)
      _ <- IO(
        SocialChannels.save(
          root,
          WeixinIlinkBridgePlugin.Name,
          io.circe.parser.parse(s"""{"fields":{"sidecar_url":"http://127.0.0.1:$port"}}""").toOption.get
        )
      )
    yield ()

  private def meta(id: String, senderId: Option[String]): SessionMeta =
    SessionMeta(
      id = id,
      name = id,
      createdAt = 0L,
      updatedAt = 0L,
      hasUnread = false,
      bridges = senderId
        .map(s => Map(WeixinIlinkBridgePlugin.Name -> Json.obj("ilink_user_id" -> Json.fromString(s))))
        .getOrElse(Map.empty)
    )

  /** Minimal recording BridgeContext (the WeixinOutboundSpec shape). */
  private def recordingCtx(sessions: List[SessionMeta]) = new BridgeContext:
    def injectMessage(sessionId: String, content: String, senderId: Option[String],
        origin: Option[nebflow.bridge.BridgeOrigin] = None): IO[Unit] = IO.unit
    def interruptAgent(sessionId: String): IO[Unit] = IO.unit
    def sessionMeta(sessionId: String): IO[Option[SessionMeta]] = IO.none
    def listSessions: IO[List[SessionMeta]] = IO.pure(sessions)
    def updateBridgeConfig(sessionId: String, platform: String, config: Option[Json]): IO[Unit] = IO.unit

  /** The shim's own refusal shape (the incident's wire body): ret≠0 surfaces
    * verbatim inside the stable `delivery_failed:` vocabulary. */
  private val ret502Body =
    """{"error":"delivery_failed: sendmessage ret=-2 errmsg=prepare failed","client_id":"stub"}"""

  // ───────────────────────── 失败必打日志 ─────────────────────────

  test("WO-L1 shim 502 (iLink ret≠0) ⇒ ONE WARN with channel/to/code/reason/digest/attempts — the incident pinned") {
    val root = tmpRoot()
    withStub(502, ret502Body) { (_, port) =>
      armLeg(root, port).unsafeRunSync()
      withAppender { appender =>
        val before = lines(appender).length
        val r = WeixinOutboundAdapter.sendLive(root)("wx_alice", "你好，世界").unsafeRunSync()
        val fired = newWarns(appender, before)
        assertEquals(r.ok, false, "the send itself still fails contained (behaviour unchanged)")
        assertEquals(fired.length, 1, s"exactly ONE WARN per failed send, got $fired")
        val line = fired.headOption.getOrElse(fail(s"no WARN fired: $fired"))
        assert(line.contains("weixin outbound send failed"), s"the WARN stem is missing: $line")
        assert(line.contains("channel=weixin-ilink"), s"the channel is missing: $line")
        assert(line.contains("to=wx_alice"), s"the recipient is missing: $line")
        assert(line.contains("code=502"), s"the HTTP code is missing: $line")
        assert(line.contains("sendmessage ret=-2"), s"the iLink ret detail must ride reason verbatim: $line")
        assert(line.contains("digest=你好，世界"), s"the message digest is missing: $line")
        assert(line.contains("attempts=1"), s"the attempt count is missing: $line")
        assert(!line.toLowerCase.contains("bearer"), s"the credential must never appear: $line")
      }
    }
  }

  test("WO-L2 transport failure (dead loopback port = the timeout/exception arm) ⇒ sidecar_unreachable WARN, code=-1") {
    val root = tmpRoot()
    withAppender { appender =>
      armLeg(root, deadPort()).unsafeRunSync()
      val before = lines(appender).length
      val r = WeixinOutboundAdapter.sendLive(root)("wx_alice", "hello").unsafeRunSync()
      val fired = newWarns(appender, before)
      assertEquals(r.ok, false)
      assert(r.reason.startsWith("sidecar_unreachable"), r.reason)
      assertEquals(fired.length, 1, s"exactly ONE WARN, got $fired")
      val line = fired.headOption.getOrElse(fail("no WARN fired"))
      assert(line.contains("code=-1"), s"the transport class carries code=-1: $line")
      assert(line.contains("sidecar_unreachable"), s"the stable reason rides the line: $line")
      assert(line.contains("to=wx_alice") && line.contains("attempts=1"), s"full field set: $line")
    }
  }

  test("WO-L3 the closed leg (no token) is logged too — a reply dropped before the wire is still a dropped reply") {
    val root = tmpRoot() // NO token minted: the leg stays closed
    withAppender { appender =>
      val before = lines(appender).length
      val r = WeixinOutboundAdapter.sendLive(root)("wx_alice", "hello").unsafeRunSync()
      val fired = newWarns(appender, before)
      assertEquals(r.ok, false)
      assert(r.reason.startsWith("shim_token_unconfigured"), r.reason)
      assertEquals(fired.length, 1, s"the closed-leg drop must NOT be silent, got $fired")
      val line = fired.headOption.getOrElse(fail("no WARN fired"))
      assert(line.contains("shim_token_unconfigured") && line.contains("to=wx_alice"), line)
    }
  }

  test("WO-L4 a SUCCESSFUL send logs NOTHING (the hot path stays silent — zero noise rule)") {
    val root = tmpRoot()
    withStub(200, """{"ok":true,"delivered":true,"message_id":"421001"}""") { (_, port) =>
      // mint + save BEFORE the appender attaches: ensureToken's one-time mint
      // INFO must not masquerade as send-path noise
      armLeg(root, port).unsafeRunSync()
      withAppender { appender =>
        val before = lines(appender).length
        val r = WeixinOutboundAdapter.sendLive(root)("wx_alice", "你好，世界").unsafeRunSync()
        val fired = lines(appender).drop(before)
        assertEquals(r.ok, true, "the stub answers delivered — the send succeeds")
        assertEquals(r.messageId, Some("421001"))
        assertEquals(fired, Nil, s"the success path must be silent at EVERY level, got $fired")
      }
    }
  }

  test("WO-L5 the digest discipline: first 50 chars only, whitespace flattened, the tail never logged") {
    val root = tmpRoot()
    withStub(502, ret502Body) { (_, port) =>
      armLeg(root, port).unsafeRunSync()
      val text = ("a" * 30) + "\n\n" + ("b" * 40) // 71 chars flattened — must truncate at 50
      withAppender { appender =>
        val before = lines(appender).length
        WeixinOutboundAdapter.sendLive(root)("wx_alice", text).unsafeRunSync()
        val fired = newWarns(appender, before)
        val line = fired.headOption.getOrElse(fail(s"no WARN fired: $fired"))
        val expectedDigest = ("a" * 30) + " " + ("b" * 19) + "…"
        assert(line.contains(s"digest=$expectedDigest"), s"the digest must be the flattened 50-char head: $line")
        assert(!line.contains("b" * 20), s"the tail beyond 50 chars must never reach the log: $line")
        assert(!line.contains("\n"), s"the WARN must stay one line: $line")
      }
    }
  }

  test("WO-L6 the MEDIA seam logs the same WARN with the media path as digest (image_path leg)") {
    val root = tmpRoot()
    withStub(502, """{"error":"delivery_failed: cdn http_500"}""") { (_, port) =>
      armLeg(root, port).unsafeRunSync()
      withAppender { appender =>
        val before = lines(appender).length
        val r = WeixinOutboundAdapter.sendMediaLive(root)(
          "wx_alice", WeixinOutboundAdapter.WeixinMedia.image("/tmp/pop.png")).unsafeRunSync()
        val fired = newWarns(appender, before)
        assertEquals(r.ok, false)
        assertEquals(fired.length, 1, s"exactly ONE WARN for the media leg too, got $fired")
        val line = fired.headOption.getOrElse(fail("no WARN fired"))
        assert(line.contains("digest=/tmp/pop.png"), s"the media identifier is the path: $line")
        assert(line.contains("to=wx_alice") && line.contains("code=502") && line.contains("attempts=1"), line)
      }
    }
  }

  test("WO-L7 end to end: the bridge reply flush through the PRODUCTION seam logs the drop (9-silent-drops incident)") {
    val root = tmpRoot()
    withStub(502, ret502Body) { (_, port) =>
      armLeg(root, port).unsafeRunSync()
      // the PRODUCTION send factory (the constructor default) — no recorder
      val p = new WeixinIlinkBridgePlugin(root)
      // BOTH loggers: the adapter's canonical line lands on
      // `nebflow.social.weixin-outbound`, the bridge's per-leg context line on
      // `nebflow.social.weixin-ilink-bridge` — one appender hears both.
      withAppender({ appender =>
        val before = lines(appender).length
        (for
          _ <- p.start(recordingCtx(List(meta("s1", Some("wx_alice")))))
          _ <- p.onAgentEvent("s1", Json.obj("type" -> "textDelta".asJson, "delta" -> "这条回复会丢".asJson))
          _ <- p.onAgentEvent("s1", Json.obj("type" -> "done".asJson))
        yield ()).unsafeRunSync()
        val fired = newWarns(appender, before)
        val outboundLine = fired.find(_.contains("weixin outbound send failed"))
          .getOrElse(fail(s"the adapter's failure WARN must fire for a dropped reply, got $fired"))
        assert(outboundLine.contains("to=wx_alice") && outboundLine.contains("code=502")
          && outboundLine.contains("digest=这条回复会丢") && outboundLine.contains("attempts=1"),
          s"the canonical line carries the full field set: $outboundLine")
        // the bridge keeps its per-leg context line (which leg died) — the
        // failure is spoken twice, once per layer, never zero times
        assert(fired.exists(_.contains("weixin-ilink bridge: reply send failed")),
          s"the bridge's own context WARN still fires: $fired")
      }, loggerNames = List("nebflow.social.weixin-outbound", "nebflow.social.weixin-ilink-bridge"))
    }
  }

end WeixinOutboundLogSpec
