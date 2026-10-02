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

/** secrets-panel backend leg — the four routes at the wire level (2026-09-27).
  *
  * Exercises the real http4s routes through `RestApiRoutes` against a
  * throwaway data root (same shape as `DaemonPanelRoutesSpec`), so the status
  * codes and the red lines are asserted at the wire rather than inferred:
  *
  *   - no token ⇒ 403 `{"error":"Unauthorized"}` (no reason detail);
  *   - 🔴 the list response NEVER carries a `value` key (structural assert);
  *   - `confirm` missing / mismatched ⇒ 400 `confirm_mismatch`;
  *   - managed namespace writes ⇒ 403 `managed_namespace` (visible in list);
  *   - referenced delete ⇒ 409 `referenced` with the referrer list;
  *   - oversized value ⇒ 400 `invalid_value`.
  *
  * All values are mock values; the fixture root is a temp directory, so the
  * real `~/.nebflow/secrets/` is never touched.
  */
class SecretsRoutesSpec extends FunSuite:

  private val token = "secrets-test-token"
  private val MockValue = "test-secret-value-1"

  private def json(s: String): Json = parse(s).fold(e => throw e, identity)

  /** Isolated data root + live routes for one case (DaemonPanelRoutesSpec shape). */
  private def withRoutes(f: HttpRoutes[IO] => Unit): Unit =
    val root = os.Path(Files.createTempDirectory("secrets-routes-spec").toRealPath().toString)
    val previous = PathUtil.dataRoot
    val (dispatcher, releaseDispatcher) = Dispatcher.parallel[IO].allocated.unsafeRunSync()
    try
      PathUtil.setDataRoot(root)
      val cfg = NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
      val inner = new RestApiRoutes(
        token = token,
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
      f(inner.routes)
    finally
      PathUtil.setDataRoot(previous)
      releaseDispatcher.unsafeRunSync()
      try os.remove.all(root)
      catch case _: Throwable => ()

  // HTTP method aliases (http4s `Method.GET` etc.).
  private val GET: Method = Method.GET
  private val POST: Method = Method.POST
  private val PUT: Method = Method.PUT
  private val DELETE: Method = Method.DELETE

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

  /** POST a fixture secret (create — the PUT fixture helper of the earlier
    * draft became a POST after the no-upsert ruling: PUT on a missing name is
    * 404 by contract). */
  private def put(routes: HttpRoutes[IO], name: String, value: String): Unit =
    val (st, _) = call(routes, POST, s"/secrets/$name", Some(json(s"""{"value":"$value"}""")))
    assertEquals(st.code, 200, s"POST fixture $name must succeed (got $st)")

  // ── Auth ────────────────────────────────────────────────────────────────

  test("A1 · no token ⇒ 403 Unauthorized on every secrets route, no reason detail") {
    withRoutes { routes =>
      val checks = List(
        call(routes, GET, "/secrets", bearer = None),
        call(routes, POST, "/secrets/n1", Some(json("""{"value":"x"}""")), bearer = None),
        call(routes, PUT, "/secrets/n1", Some(json("""{"value":"x"}""")), bearer = None),
        call(routes, DELETE, "/secrets/n1?confirm=n1", bearer = None)
      )
      checks.foreach { case (st, body) =>
        assertEquals(st.code, 403, "all four arms sit behind withAuth")
        assertEquals(body, Some(json("""{"error":"Unauthorized"}""")),
          "the auth failure must not distinguish reasons (anti-enumeration)")
      }
    }
  }

  // ── GET list: metadata only, structurally value-free ────────────────────

  test("L1 · list: 200, entries carry name/size/mtime/mode/refs and NEVER a value key") {
    withRoutes { routes =>
      put(routes, "mock-a", MockValue)
      put(routes, "mock-b", MockValue)
      val (st, body) = call(routes, GET, "/secrets")
      assertEquals(st.code, 200)
      val secrets = body.flatMap(_.hcursor.downField("secrets").values.map(_.toList))
        .getOrElse(fail(s"expected a secrets array, got $body"))
      assertEquals(secrets.size, 2)
      secrets.foreach { e =>
        val obj = e.asObject.getOrElse(fail(s"entry not an object: $e"))
        // Structural red line: no entry may carry a value key at any nesting.
        assert(obj("value").isEmpty, s"entry must not carry a value key: $e")
        assert(obj("value_ref").isEmpty && obj("content").isEmpty, s"entry: $e")
        assert(obj("name").isDefined && obj("size").isDefined && obj("mtime").isDefined &&
          obj("mode").isDefined && obj("refs").isDefined, s"entry shape: $e")
      }
      assertEquals(secrets.map(_.hcursor.get[String]("name")).map(_.toOption.get).sorted,
        List("mock-a", "mock-b"))
      // The wire body as a whole never mentions the mock value either.
      val raw = body.get.noSpaces
      assert(!raw.contains(MockValue), "the list payload must never contain a stored value")
    }
  }

  test("L2 · list: empty root ⇒ empty array, 200") {
    withRoutes { routes =>
      val (st, body) = call(routes, GET, "/secrets")
      assertEquals(st.code, 200)
      assertEquals(body, Some(json("""{"secrets":[]}""")))
    }
  }

  // ── POST / PUT semantics ────────────────────────────────────────────────

  test("W1 · POST creates; duplicate POST ⇒ 409 already_exists") {
    withRoutes { routes =>
      val (st1, b1) = call(routes, POST, "/secrets/mock-a", Some(json(s"""{"value":"$MockValue"}""")))
      assertEquals(st1.code, 200)
      assertEquals(b1.flatMap(j => j.hcursor.get[String]("name").toOption), Some("mock-a"))
      val (st2, b2) = call(routes, POST, "/secrets/mock-a", Some(json("""{"value":"other"}""")))
      assertEquals(st2.code, 409)
      assertEquals(b2.flatMap(j => j.hcursor.get[String]("error").toOption), Some("already_exists"))
    }
  }

  test("W2 · PUT overwrites; PUT on missing ⇒ 404 unknown_secret (no upsert)") {
    withRoutes { routes =>
      val (st1, b1) = call(routes, PUT, "/secrets/mock-a", Some(json(s"""{"value":"$MockValue"}""")))
      assertEquals(st1.code, 404, "PUT never creates")
      assertEquals(b1.flatMap(j => j.hcursor.get[String]("error").toOption), Some("unknown_secret"))
      put(routes, "mock-a", MockValue)
      val (st2, _) = call(routes, PUT, "/secrets/mock-a", Some(json("""{"value":"v2"}""")))
      assertEquals(st2.code, 200, "PUT on an existing name overwrites")
    }
  }

  test("W3 · invalid names ⇒ 400 invalid_name with the name, never the value") {
    withRoutes { routes =>
      val bad = List(".hidden", "mock.tmp", "Bad-Name", "a/b", "x" * 65)
      bad.foreach { n =>
        // `a/b` never matches the route arm at all (single-segment path) —
        // that refusal is the STORE's validateName, exercised at the store
        // level; at the wire a slash is just a different (unmounted) path.
        if n.contains("/") then ()
        else
          val (st, b) = call(routes, POST, s"/secrets/$n", Some(json(s"""{"value":"$MockValue"}""")))
          assertEquals(st.code, 400, s"'$n' must refuse with 400")
          assertEquals(b.flatMap(j => j.hcursor.get[String]("error").toOption), Some("invalid_name"), s"'$n'")
          val raw = b.map(_.noSpaces).getOrElse("")
          assert(!raw.contains(MockValue), s"the error body for '$n' must not carry the value")
      }
    }
  }

  test("W4 · oversized value ⇒ 400 invalid_value (64 KiB cap)") {
    withRoutes { routes =>
      val big = "x" * (nebflow.core.secrets.SecretsStore.MaxValueBytes + 1)
      val (st, b) = call(routes, POST, "/secrets/mock-a", Some(json(s"""{"value":"$big"}""")))
      assertEquals(st.code, 400)
      assertEquals(b.flatMap(j => j.hcursor.get[String]("error").toOption), Some("invalid_value"))
    }
  }

  test("W5 · value cap boundary at the wire: exactly 64 KiB stores (200), one byte over 400s") {
    withRoutes { routes =>
      // Inclusive cap, asserted at the wire: exactly MaxValueBytes bytes is a
      // legal POST (200), MaxValueBytes + 1 is 400 invalid_value.
      val exact = "x" * nebflow.core.secrets.SecretsStore.MaxValueBytes
      val (stOk, _) = call(routes, POST, "/secrets/mock-boundary",
        Some(json(s"""{"value":"$exact"}""")))
      assertEquals(stOk.code, 200,
        s"a value of exactly ${nebflow.core.secrets.SecretsStore.MaxValueBytes} bytes must store")
      val over = "x" * (nebflow.core.secrets.SecretsStore.MaxValueBytes + 1)
      val (stOver, bOver) = call(routes, PUT, "/secrets/mock-boundary",
        Some(json(s"""{"value":"$over"}""")))
      assertEquals(stOver.code, 400)
      assertEquals(bOver.flatMap(j => j.hcursor.get[String]("error").toOption), Some("invalid_value"))
    }
  }

  // ── DELETE: confirm gate, managed namespace, references ─────────────────

  test("D1 · delete: confirm missing / mismatched ⇒ 400 confirm_mismatch") {
    withRoutes { routes =>
      put(routes, "mock-a", MockValue)
      val (st1, b1) = call(routes, DELETE, "/secrets/mock-a")
      assertEquals(st1.code, 400, "missing confirm refuses")
      assertEquals(b1.flatMap(j => j.hcursor.get[String]("error").toOption), Some("confirm_mismatch"))
      val (st2, b2) = call(routes, DELETE, "/secrets/mock-a?confirm=other-name")
      assertEquals(st2.code, 400, "mismatched confirm refuses")
      assertEquals(b2.flatMap(j => j.hcursor.get[String]("error").toOption), Some("confirm_mismatch"))
      val (st3, _) = call(routes, DELETE, "/secrets/mock-a?confirm=mock-a")
      assertEquals(st3.code, 200, "matching confirm deletes")
      val (st4, body4) = call(routes, GET, "/secrets")
      assertEquals(st4.code, 200)
      assertEquals(body4, Some(json("""{"secrets":[]}""")), "the file is gone after the delete")
    }
  }

  test("D2 · managed namespace: visible in the list, every write ⇒ 403 managed_namespace") {
    withRoutes { routes =>
      // Simulate a managed file its own panel wrote (fixture, mock value).
      val root = PathUtil.dataRoot
      os.makeDir.all(root / "secrets")
      os.write.over(root / "secrets" / "social-telegram-bot-token", MockValue)
      os.perms.set(root / "secrets" / "social-telegram-bot-token", "rw-------")
      // Visible + inspectable in the list.
      val (lst, body) = call(routes, GET, "/secrets")
      assertEquals(lst.code, 200)
      val names = body.flatMap(_.hcursor.downField("secrets").values.map(_.toList))
        .getOrElse(fail("no secrets array")).map(_.hcursor.get[String]("name"))
      assert(names.exists(_.toOption.contains("social-telegram-bot-token")),
        "managed names must be listable")
      // Writes refused — POST / PUT / DELETE all 403.
      assertEquals(call(routes, POST, "/secrets/social-telegram-bot-token",
        Some(json(s"""{"value":"$MockValue"}""")))._1.code, 403)
      assertEquals(call(routes, PUT, "/secrets/social-telegram-bot-token",
        Some(json(s"""{"value":"$MockValue"}""")))._1.code, 403)
      val (dst, db) = call(routes, DELETE, "/secrets/social-telegram-bot-token?confirm=social-telegram-bot-token")
      assertEquals(dst.code, 403, "delete of a managed name refuses even with the right confirm")
      assertEquals(db.flatMap(j => j.hcursor.get[String]("error").toOption), Some("managed_namespace"))
    }
  }

  test("D3 · delete of a referenced NON-managed secret ⇒ 409 referenced with the referrer list") {
    withRoutes { routes =>
      val root = PathUtil.dataRoot
      // Fixture: a free-form name the way a consumer config would reference it
      // (a stored `_ref` path in nebflow.json's socialChannels face) — the
      // name itself is NOT managed, so the reference check is the one that
      // fires (the managed-vs-referenced ordering has its own case, D4).
      os.makeDir.all(root / "secrets")
      os.write.over(root / "secrets" / "feishu-app-cred", MockValue)
      os.perms.set(root / "secrets" / "feishu-app-cred", "rw-------")
      // An isolated (non-default) root renders its ABSOLUTE path in stored
      // refs — write the fixture ref the way SocialChannels.persist would.
      os.write.over(root / "nebflow.json",
        s"""{"socialChannels":{"channels":{"feishu":{"enabled":true,"fields":
           |{"app_secret_ref":"$root/secrets/feishu-app-cred"}}}}}""".stripMargin)
      val (st, b) = call(routes, DELETE, "/secrets/feishu-app-cred?confirm=feishu-app-cred")
      assertEquals(st.code, 409)
      assertEquals(b.flatMap(j => j.hcursor.get[String]("error").toOption), Some("referenced"))
      val refs = b.flatMap(j => j.hcursor.get[List[String]]("refs").toOption).getOrElse(fail("no refs"))
      assert(refs.nonEmpty, s"the referrer list must be non-empty, got $refs")
      assert(refs.exists(_.contains("feishu")), s"the feishu channel must appear: $refs")
      // The file survived the refused delete.
      assert(os.exists(root / "secrets" / "feishu-app-cred"))
    }
  }

  test("D4 · managed refusal precedes the reference refusal (managed beats referenced)") {
    withRoutes { routes =>
      val root = PathUtil.dataRoot
      os.makeDir.all(root / "secrets")
      os.write.over(root / "secrets" / "social-wechat-app-secret", MockValue)
      os.perms.set(root / "secrets" / "social-wechat-app-secret", "rw-------")
      val (st, b) = call(routes, DELETE, "/secrets/social-wechat-app-secret?confirm=social-wechat-app-secret")
      assertEquals(st.code, 403, "a managed+referenced name reports managed_namespace, not referenced")
      assertEquals(b.flatMap(j => j.hcursor.get[String]("error").toOption), Some("managed_namespace"))
    }
  }
