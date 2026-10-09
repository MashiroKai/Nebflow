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
import scala.jdk.CollectionConverters.*

/**
 * secrets-panel backend leg — the four routes at the wire level (2026-09-27).
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
    end try
  end withRoutes

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

  end call

  /**
   * POST a fixture secret (create — the PUT fixture helper of the earlier
   * draft became a POST after the no-upsert ruling: PUT on a missing name is
   * 404 by contract).
   */
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
        assertEquals(
          body,
          Some(json("""{"error":"Unauthorized"}""")),
          "the auth failure must not distinguish reasons (anti-enumeration)"
        )
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
      val secrets = body
        .flatMap(_.hcursor.downField("secrets").values.map(_.toList))
        .getOrElse(fail(s"expected a secrets array, got $body"))
      assertEquals(secrets.size, 2)
      secrets.foreach { e =>
        val obj = e.asObject.getOrElse(fail(s"entry not an object: $e"))
        // Structural red line: no entry may carry a value key at any nesting.
        assert(obj("value").isEmpty, s"entry must not carry a value key: $e")
        assert(obj("value_ref").isEmpty && obj("content").isEmpty, s"entry: $e")
        assert(
          obj("name").isDefined && obj("size").isDefined && obj("mtime").isDefined &&
            obj("mode").isDefined && obj("refs").isDefined,
          s"entry shape: $e"
        )
      }
      assertEquals(secrets.map(_.hcursor.get[String]("name")).map(_.toOption.get).sorted, List("mock-a", "mock-b"))
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
      val (stOk, _) = call(routes, POST, "/secrets/mock-boundary", Some(json(s"""{"value":"$exact"}""")))
      assertEquals(
        stOk.code,
        200,
        s"a value of exactly ${nebflow.core.secrets.SecretsStore.MaxValueBytes} bytes must store"
      )
      val over = "x" * (nebflow.core.secrets.SecretsStore.MaxValueBytes + 1)
      val (stOver, bOver) = call(routes, PUT, "/secrets/mock-boundary", Some(json(s"""{"value":"$over"}""")))
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
      val names = body
        .flatMap(_.hcursor.downField("secrets").values.map(_.toList))
        .getOrElse(fail("no secrets array"))
        .map(_.hcursor.get[String]("name"))
      assert(names.exists(_.toOption.contains("social-telegram-bot-token")), "managed names must be listable")
      // Writes refused — POST / PUT / DELETE all 403.
      assertEquals(
        call(routes, POST, "/secrets/social-telegram-bot-token", Some(json(s"""{"value":"$MockValue"}""")))._1.code,
        403
      )
      assertEquals(
        call(routes, PUT, "/secrets/social-telegram-bot-token", Some(json(s"""{"value":"$MockValue"}""")))._1.code,
        403
      )
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
      os.write.over(
        root / "nebflow.json",
        s"""{"socialChannels":{"channels":{"feishu":{"enabled":true,"fields":
           |{"app_secret_ref":"$root/secrets/feishu-app-cred"}}}}}""".stripMargin
      )
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

  // ── Consumption face (secrets-consumption batch, 2026-10-01) ────────────
  //
  // The C-case model at the wire: a consumer is told a LOCATION, and the two
  // non-value catalogue fields are editable. 🔴 No endpoint on this face
  // returns a stored value — asserted structurally below, not merely by
  // convention.

  private val PATCH: Method = Method.PATCH

  test("C1 · pointer: 200 with name/variableName/description/location, never a value") {
    withRoutes { routes =>
      put(routes, "vercel-token", MockValue)
      val (st, body) = call(routes, GET, "/secrets/vercel-token/pointer")
      assertEquals(st.code, 200)
      val b = body.getOrElse(fail("no body"))
      assertEquals(b.hcursor.get[String]("name").toOption, Some("vercel-token"))
      assertEquals(b.hcursor.get[String]("variableName").toOption, Some("VERCEL_TOKEN"))
      assertEquals(b.hcursor.get[String]("description").toOption, Some(""))
      val loc = b.hcursor.get[String]("location").toOption.getOrElse(fail("no location"))
      assert(loc.endsWith("/secrets/vercel-token"), s"the location is the query path: $loc")
      // 🔴 The structural red line: no value key, and the raw wire body never
      // mentions the stored value.
      assert(b.asObject.get("value").isEmpty && b.asObject.get("content").isEmpty, s"body: $b")
      assert(!b.noSpaces.contains(MockValue), "the pointer payload must never carry a value")
    }
  }

  test("C2 · pointer on a missing name ⇒ 404 unknown_secret") {
    withRoutes { routes =>
      val (st, b) = call(routes, GET, "/secrets/ghost/pointer")
      assertEquals(st.code, 404)
      assertEquals(b.flatMap(j => j.hcursor.get[String]("error").toOption), Some("unknown_secret"))
    }
  }

  test("C3 · catalog: 200, one entry per secret, value-free end to end") {
    withRoutes { routes =>
      put(routes, "mock-a", MockValue)
      put(routes, "mock-b", MockValue)
      val (st, body) = call(routes, GET, "/secrets/catalog")
      assertEquals(st.code, 200)
      val entries = body
        .flatMap(_.hcursor.downField("secrets").values.map(_.toList))
        .getOrElse(fail(s"expected a secrets array, got $body"))
      assertEquals(entries.size, 2)
      entries.foreach { e =>
        val obj = e.asObject.getOrElse(fail(s"entry not an object: $e"))
        assert(obj("value").isEmpty, s"entry must not carry a value key: $e")
        assert(
          obj("location").isDefined && obj("variableName").isDefined && obj("description").isDefined,
          s"entry shape: $e"
        )
      }
      assert(!body.get.noSpaces.contains(MockValue), "the catalog payload must never carry a value")
    }
  }

  test("C4 · PATCH metadata: whole-replace — set, then clear back to the derived default") {
    withRoutes { routes =>
      put(routes, "vercel-token", MockValue)
      val (st1, _) = call(
        routes,
        PATCH,
        "/secrets/vercel-token",
        Some(json("""{"variableName":"MY_DEPLOY_TOKEN","description":"deploys the docs site"}"""))
      )
      assertEquals(st1.code, 200)

      val (_, b1) = call(routes, GET, "/secrets/vercel-token/pointer")
      assertEquals(b1.flatMap(_.hcursor.get[String]("variableName").toOption), Some("MY_DEPLOY_TOKEN"))
      assertEquals(b1.flatMap(_.hcursor.get[String]("description").toOption), Some("deploys the docs site"))

      // A body without the keys CLEARS them (the replace contract), it does
      // not leave the previous values in place.
      val (st2, _) = call(routes, PATCH, "/secrets/vercel-token", Some(json("{}")))
      assertEquals(st2.code, 200)
      val (_, b2) = call(routes, GET, "/secrets/vercel-token/pointer")
      assertEquals(
        b2.flatMap(_.hcursor.get[String]("variableName").toOption),
        Some("VERCEL_TOKEN"),
        "clearing the override falls back to the derived default"
      )
      assertEquals(b2.flatMap(_.hcursor.get[String]("description").toOption), Some(""))
    }
  }

  test("C5 · PATCH metadata: an invalid variable name ⇒ 400 invalid_variable_name, never a value") {
    withRoutes { routes =>
      put(routes, "mock-a", MockValue)
      val (st, b) = call(routes, PATCH, "/secrets/mock-a", Some(json("""{"variableName":"not a name"}""")))
      assertEquals(st.code, 400)
      assertEquals(b.flatMap(j => j.hcursor.get[String]("error").toOption), Some("invalid_variable_name"))
      assert(!b.map(_.noSpaces).getOrElse("").contains(MockValue), "the error body carries no value")
      // A non-object body is refused by shape, before the store is consulted.
      val (st2, b2) = call(routes, PATCH, "/secrets/mock-a", Some(Json.fromString("nope")))
      assertEquals(st2.code, 400)
      assertEquals(b2.flatMap(j => j.hcursor.get[String]("error").toOption), Some("invalid_metadata"))
    }
  }

  test("C6 · PATCH metadata: a missing target ⇒ 404; metadata never mints a secret") {
    withRoutes { routes =>
      val (st, b) = call(routes, PATCH, "/secrets/ghost", Some(json("""{"description":"x"}""")))
      assertEquals(st.code, 404)
      assertEquals(b.flatMap(j => j.hcursor.get[String]("error").toOption), Some("unknown_secret"))
    }
  }

  test("C7 · the consumption endpoints sit behind the same auth gate as the management face") {
    withRoutes { routes =>
      val checks = List(
        call(routes, GET, "/secrets/catalog", bearer = None),
        call(routes, GET, "/secrets/n1/pointer", bearer = None),
        call(routes, PATCH, "/secrets/n1", Some(json("""{"description":"x"}""")), bearer = None)
      )
      checks.foreach { case (st, body) =>
        assertEquals(st.code, 403, "the consumption face is authenticated too")
        assertEquals(
          body,
          Some(json("""{"error":"Unauthorized"}""")),
          "the auth failure must not distinguish reasons (anti-enumeration)"
        )
      }
    }
  }

  test("C8 · the list answer gains the two catalogue fields WITHOUT losing its old shape") {
    withRoutes { routes =>
      put(routes, "mock-a", MockValue)
      val (_, _) = call(
        routes,
        PATCH,
        "/secrets/mock-a",
        Some(json("""{"variableName":"A_TOKEN","description":"a description"}"""))
      )
      val (st, body) = call(routes, GET, "/secrets")
      assertEquals(st.code, 200)
      val e = body.flatMap(_.hcursor.downField("secrets").values.map(_.toList)).getOrElse(fail("no array")).head
      // Old keys intact (the existing panel keeps working)…
      List("name", "size", "mtime", "mode", "refs").foreach { k =>
        assert(e.asObject.exists(_(k).isDefined), s"pre-existing key '$k' must survive the extension: $e")
      }
      // …plus the two new ones, and still no value key.
      assertEquals(e.hcursor.get[String]("variableName").toOption, Some("A_TOKEN"))
      assertEquals(e.hcursor.get[String]("description").toOption, Some("a description"))
      assert(e.asObject.exists(_("value").isEmpty), s"still no value key: $e")
      assert(!body.get.noSpaces.contains(MockValue), "no value in the extended payload either")
    }
  }

  // ── Single-secret value readback (secrets-readback batch, 2026-10-09) ───
  //
  // The panel editor opens with the STORED value prefilled (author ruling
  // 2026-10-09 07:03), so the face now carries exactly ONE value-returning
  // endpoint: GET /secrets/{name}/value. The red lines it must hold at the
  // wire: authenticated (same gate, indistinguishable 403); single-name
  // explicit read only; the response carries exactly {name, value}; the
  // route's log line names the secret and NEVER the value.

  test("V1 · readback: 200 with exactly {name, value}; the single read carries the stored value") {
    withRoutes { routes =>
      put(routes, "mock-a", MockValue)
      val (st, body) = call(routes, GET, "/secrets/mock-a/value")
      assertEquals(st.code, 200)
      val b = body.getOrElse(fail("no body"))
      assertEquals(b.hcursor.get[String]("name").toOption, Some("mock-a"))
      assertEquals(
        b.hcursor.get[String]("value").toOption,
        Some(MockValue),
        "the explicit single read DOES return the stored value"
      )
      // Exactly two keys — no refs / metadata sidecar / second channel.
      assertEquals(b.asObject.map(_.keys.toSet), Some(Set("name", "value")))
      // A managed name is READ-visible (the list shows it; only its lifecycle
      // writes belong to its own panel).
      val root = PathUtil.dataRoot
      os.makeDir.all(root / "secrets")
      os.write.over(root / "secrets" / "social-telegram-bot-token", MockValue)
      os.perms.set(root / "secrets" / "social-telegram-bot-token", "rw-------")
      val (stm, bm) = call(routes, GET, "/secrets/social-telegram-bot-token/value")
      assertEquals(stm.code, 200, "managed namespaces stay read-visible")
      assertEquals(bm.flatMap(_.hcursor.get[String]("value").toOption), Some(MockValue))
    }
  }

  test("V2 · readback: ONE trailing CRLF/LF stripped (consumption idiom), inner newlines kept") {
    withRoutes { routes =>
      val root = PathUtil.dataRoot
      os.makeDir.all(root / "secrets")
      os.write.over(root / "secrets" / "multiline", "line-one\nline-two\n")
      os.perms.set(root / "secrets" / "multiline", "rw-------")
      val (st, body) = call(routes, GET, "/secrets/multiline/value")
      assertEquals(st.code, 200)
      assertEquals(
        body.flatMap(_.hcursor.get[String]("value").toOption),
        Some("line-one\nline-two"),
        "one trailing LF stripped, inner newlines intact"
      )
      os.write.over(root / "secrets" / "crlf", "token-abc\r\n")
      os.perms.set(root / "secrets" / "crlf", "rw-------")
      val (st2, body2) = call(routes, GET, "/secrets/crlf/value")
      assertEquals(st2.code, 200)
      assertEquals(
        body2.flatMap(_.hcursor.get[String]("value").toOption),
        Some("token-abc"),
        "one trailing CRLF stripped"
      )
    }
  }

  test("V3 · readback refusals: missing ⇒ 404, invalid name ⇒ 400, oversized ⇒ 400 — bodies never carry a value") {
    withRoutes { routes =>
      val (st1, b1) = call(routes, GET, "/secrets/ghost/value")
      assertEquals(st1.code, 404)
      assertEquals(b1.flatMap(j => j.hcursor.get[String]("error").toOption), Some("unknown_secret"))
      val (st2, b2) = call(routes, GET, "/secrets/.hidden/value")
      assertEquals(st2.code, 400)
      assertEquals(b2.flatMap(j => j.hcursor.get[String]("error").toOption), Some("invalid_name"))
      // A foreign oversized file (the write face could never have produced it)
      // is refused rather than handed out unbounded.
      val root = PathUtil.dataRoot
      os.makeDir.all(root / "secrets")
      os.write.over(root / "secrets" / "toobig", "x" * (nebflow.core.secrets.SecretsStore.MaxValueBytes + 1))
      os.perms.set(root / "secrets" / "toobig", "rw-------")
      val (st3, b3) = call(routes, GET, "/secrets/toobig/value")
      assertEquals(st3.code, 400)
      assertEquals(b3.flatMap(j => j.hcursor.get[String]("error").toOption), Some("invalid_value"))
      List(b1, b2, b3).foreach { b =>
        assert(!b.map(_.noSpaces).getOrElse("").contains(MockValue), "no refusal body carries a value")
      }
    }
  }

  test("V4 · readback sits behind the same auth gate ⇒ 403 Unauthorized without a token") {
    withRoutes { routes =>
      put(routes, "mock-a", MockValue)
      val (st, body) = call(routes, GET, "/secrets/mock-a/value", bearer = None)
      assertEquals(st.code, 403, "the value face is authenticated too")
      assertEquals(
        body,
        Some(json("""{"error":"Unauthorized"}""")),
        "the auth failure must not distinguish reasons (anti-enumeration)"
      )
    }
  }

  test("V5 · 列表无值 / 单条读取有值 / 日志无值 — one run, three red lines") {
    withRoutes { routes =>
      // Capture this route's log lines (AnthropicEmptyEventWarnSpec appender shape).
      val lbLogger = org.slf4j.LoggerFactory
        .getLogger("nebflow.gateway.secrets")
        .asInstanceOf[ch.qos.logback.classic.Logger]
      val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
      appender.start()
      lbLogger.addAppender(appender)
      try
        put(routes, "mock-a", MockValue)
        // ① 列表无值 — the list AND catalog answers stay value-free.
        val (lst, listBody) = call(routes, GET, "/secrets")
        assertEquals(lst.code, 200)
        assert(!listBody.get.noSpaces.contains(MockValue), "① the list must not carry the value")
        val (cat, catBody) = call(routes, GET, "/secrets/catalog")
        assertEquals(cat.code, 200)
        assert(!catBody.get.noSpaces.contains(MockValue), "the catalog must not carry the value")
        // ② 单条读取有值 — the explicit single read DOES carry it.
        val (rd, rdBody) = call(routes, GET, "/secrets/mock-a/value")
        assertEquals(rd.code, 200)
        assertEquals(rdBody.flatMap(_.hcursor.get[String]("value").toOption), Some(MockValue))
        // ③ 日志无值 — the read is logged by NAME, never by value.
        val events = appender.list.asScala.toList
        assert(
          events.exists(_.getFormattedMessage.contains("mock-a")),
          s"the read is logged (which secret, not what it holds): ${events.map(_.getFormattedMessage)}"
        )
        events.foreach { e =>
          assert(
            !e.getFormattedMessage.contains(MockValue),
            s"🔴 a log line must never carry the value: ${e.getFormattedMessage}"
          )
        }
      finally
        lbLogger.detachAppender(appender)
        appender.stop()
      end try
    }
  }
end SecretsRoutesSpec
