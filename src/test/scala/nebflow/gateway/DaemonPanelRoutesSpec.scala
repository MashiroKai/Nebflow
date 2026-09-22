package nebflow.gateway

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite
import nebflow.agent.SharedResources
import nebflow.core.PathUtil
import nebflow.core.daemon.DaemonService
import nebflow.llm.{ModelCandidate, NebflowServiceConfig, ServiceLlmConfig}
import org.http4s.*
import org.http4s.server.websocket.WebSocketBuilder2
import org.typelevel.ci.CIString

import java.nio.file.Files

/** daemonpanel Phase A — the three config-panel endpoints (backend F2–F9).
  *
  * Exercises the real http4s routes through `RestApiRoutes`, against a
  * throwaway data root, so the status codes, the fail-closed degradation and
  * the masking discipline are asserted at the wire level rather than inferred
  * from the validator.
  *
  * Criteria covered here: F3 (400 unknown field), F6 (401), F7 (409 no config
  * panel), F8 (invalid declaration ⇒ 409, hidden whole), F9 (web denied /
  * allowed), plus F2 (masking) and the F4 (*** round-trip) at HTTP level.
  */
class DaemonPanelRoutesSpec extends FunSuite:

  private val token = "dp-test-token"

  private def json(s: String): Json = parse(s).fold(e => throw e, identity)

  /** Isolated data root + daemons.json fixture for the duration of one case. */
  private def withRoutes(
    daemonsJson: String,
    allowWeb: Boolean = false
  )(f: HttpRoutes[IO] => Unit): Unit =
    val root = os.Path(Files.createTempDirectory("dp-routes-spec").toRealPath().toString)
    val previous = PathUtil.dataRoot
    val (dispatcher, releaseDispatcher) = Dispatcher.parallel[IO].allocated.unsafeRunSync()
    try
      PathUtil.setDataRoot(root)
      os.write.over(root / "daemons.json", daemonsJson, createFolders = true)
      val cfg = NebflowServiceConfig(
        llm = ServiceLlmConfig(providers = Map.empty),
        daemonPanel = if allowWeb then Some(json("""{"allowWeb": true}""")) else None
      )
      // A REAL DaemonService on a temp marker file: the config-panel routes
      // must be exercised against the same service the daemon section uses.
      val svc = new DaemonService(dispatcher, root / "daemon-pids.json")
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
          daemonService = Some(svc)
        ),
        sessionStore = null,
        wsRoutes = null
      )
      // PRODUCTION MOUNT FORM (GatewayMain: `Router("/api" -> routes.routes <+>
      // presenceWsRoutes(wsb))`). The daemon arms live in the presenceWsRoutes
      // block, so calling `inner.routes` alone falls through spuriously
      // (PanelSchemeRoutesSpec / DeviceFaceHardeningRoutesSpec same finding).
      // The placeholder builder is only closed over — these arms never touch WS.
      val wsb = null.asInstanceOf[WebSocketBuilder2[IO]]
      f(inner.routes <+> inner.presenceWsRoutes(wsb))
    finally
      PathUtil.setDataRoot(previous)
      releaseDispatcher.unsafeRunSync()
      try os.remove.all(root)
      catch case _: Throwable => ()

  private def call(
    routes: HttpRoutes[IO],
    method: Method,
    path: String,
    body: Option[Json] = None,
    bearer: Option[String] = Some(token)
  ): (Status, Option[Json]) =
    val base = Request[IO](method, Uri.unsafeFromString(path))
    val authed = bearer.fold(base)(t => base.putHeaders(Header.Raw(CIString("Authorization"), s"Bearer $t")))
    val req = body match
      case None       => authed
      case Some(json) => authed.withEntity(json.noSpaces)
    routes(req).value.unsafeRunSync() match
      case None => fail(s"route fell through: $method $path")
      case Some(resp) =>
        val text = resp.body.compile.toVector.unsafeRunSync().map(_.toChar).mkString
        (resp.status, if text.isEmpty then None else parse(text).toOption)

  private val validPanel =
    """{"fields": [{"key": "host", "label": "Host", "type": "string"}, {"key": "password", "label": "PW", "type": "secret"}]}"""

  private def daemonWith(id: String, panel: Option[String]) =
    val panelPart = panel.map(p => s""", "configPanel": $p""").getOrElse("")
    s"""{"daemons": [{"id": "$id", "name": "$id", "command": ["true"]$panelPart},
       |{"id": "plain", "name": "plain", "command": ["true"]}]}""".stripMargin

  // ── F6 · authentication ───────────────────────────────────────────────

  test("F6 · the config-panel endpoints are withAuth: no token and a bad token are both rejected") {
    withRoutes(daemonWith("mail", Some(validPanel))) { routes =>
      assertEquals(call(routes, Method.GET, "/daemons/mail/config-panel", bearer = None)._1, Status.Forbidden)
      assertEquals(call(routes, Method.GET, "/daemons/mail/config-panel", bearer = Some("wrong"))._1, Status.Forbidden)
      assertEquals(call(routes, Method.PUT, "/daemons/mail/config-panel", Some(json("""{"values":{}}""")), None)._1, Status.Forbidden)
      assertEquals(call(routes, Method.GET, "/daemons/mail/config-panel/credentials", bearer = None)._1, Status.Forbidden)
    }
  }

  // ── F7 · 409 for a daemon without a panel (NOT 400) ────────────────────

  test("F7 · a daemon without configPanel answers 409 no config panel, not 400") {
    withRoutes(daemonWith("mail", None)) { routes =>
      val (status, body) = call(routes, Method.GET, "/daemons/plain/config-panel")
      assertEquals(status, Status.Conflict)
      assertEquals(body.flatMap(_.hcursor.get[String]("error").toOption), Some("no config panel"))
      // The write endpoint degrades identically.
      assertEquals(call(routes, Method.PUT, "/daemons/plain/config-panel", Some(json("""{"values":{}}""")))._1, Status.Conflict)
      assertEquals(call(routes, Method.GET, "/daemons/plain/config-panel/credentials")._1, Status.Conflict)
    }
  }

  test("F7 · an unknown daemon id is 404, distinct from the 409 no-panel case") {
    withRoutes(daemonWith("mail", None)) { routes =>
      val (status, body) = call(routes, Method.GET, "/daemons/nope/config-panel")
      assertEquals(status, Status.NotFound)
      assert(body.flatMap(_.hcursor.get[String]("error").toOption).exists(_.contains("not found")))
    }
  }

  // ── F8 · a bad declaration hides the entry whole ───────────────────────

  test("F8 · an out-of-whitelist type hides the panel entirely (409, no partial render)") {
    val bad = """{"fields": [{"key": "a", "type": "string"}, {"key": "evil", "type": "html"}]}"""
    withRoutes(daemonWith("mail", Some(bad))) { routes =>
      assertEquals(call(routes, Method.GET, "/daemons/mail/config-panel")._1, Status.Conflict)
    }
  }

  test("F8 · GET /daemons publishes hasConfigPanel only for a VALID declaration (F1/C7 pin)") {
    withRoutes(daemonWith("mail", Some(validPanel))) { routes =>
      val (status, body) = call(routes, Method.GET, "/daemons")
      assertEquals(status, Status.Ok)
      val entries = body.flatMap(_.hcursor.downField("daemons").as[List[Json]].toOption).getOrElse(Nil)
      val byId = entries.map(e => e.hcursor.get[String]("id").toOption.get -> e).toMap
      assertEquals(byId("mail").hcursor.get[Boolean]("hasConfigPanel").toOption, Some(true))
      // The panel-less daemon keeps the baseline key set: no new key at all.
      assertEquals(byId("plain").asObject.exists(_.contains("hasConfigPanel")), false)
      // Baseline keys still present on both.
      for id <- List("mail", "plain") do
        assert(byId(id).asObject.exists(_.contains("autoStart")), s"$id lost autoStart")
        assert(byId(id).asObject.exists(_.contains("restartOnExit")), s"$id lost restartOnExit")
    }
  }

  // ── F2 · masking at the wire ───────────────────────────────────────────

  test("F2 · the read view masks declared secrets as *** and never echoes plaintext") {
    withRoutes(daemonWith("mail", Some(validPanel))) { routes =>
      // Write a secret, then read it back.
      assertEquals(
        call(routes, Method.PUT, "/daemons/mail/config-panel", Some(json("""{"values":{"host":"imap.test","password":"plaintext-pw"}}""")))._1,
        Status.Ok
      )
      val (status, body) = call(routes, Method.GET, "/daemons/mail/config-panel")
      assertEquals(status, Status.Ok)
      val values = body.flatMap(_.hcursor.downField("values").focus).getOrElse(Json.Null)
      assertEquals(values.hcursor.get[String]("password").toOption, Some("***"))
      assertEquals(values.hcursor.get[String]("host").toOption, Some("imap.test"))
      // The response bytes must not carry the plaintext anywhere.
      assert(!body.exists(_.noSpaces.contains("plaintext-pw")), "plaintext leaked into the read response")
    }
  }

  // ── F3 · unknown field / invalid value ────────────────────────────────

  test("F3 · an undeclared key in the PUT body is 400 unknown field") {
    withRoutes(daemonWith("mail", Some(validPanel))) { routes =>
      val (status, body) =
        call(routes, Method.PUT, "/daemons/mail/config-panel", Some(json("""{"values":{"host":"a","backdoor":"x"}}""")))
      assertEquals(status, Status.BadRequest)
      assertEquals(body.flatMap(_.hcursor.get[String]("error").toOption), Some("unknown field"))
    }
  }

  test("F3 · a value violating its declared type is 400 invalid value") {
    // `port` is declared as a bounded number; a string is not acceptable.
    val numeric = """{"fields": [{"key": "port", "type": "number", "min": 1, "max": 100}]}"""
    withRoutes(daemonWith("mail", Some(numeric))) { routes =>
      val (status, body) = call(routes, Method.PUT, "/daemons/mail/config-panel", Some(json("""{"values":{"port":"abc"}}""")))
      assertEquals(status, Status.BadRequest)
      assertEquals(body.flatMap(_.hcursor.get[String]("error").toOption), Some("invalid value"))
      // Out-of-range is rejected the same way.
      assertEquals(call(routes, Method.PUT, "/daemons/mail/config-panel", Some(json("""{"values":{"port":500}}""")))._1, Status.BadRequest)
      // A legal value inside the range is accepted.
      assertEquals(call(routes, Method.PUT, "/daemons/mail/config-panel", Some(json("""{"values":{"port":50}}""")))._1, Status.Ok)
    }
  }

  // ── F4 · *** round-trip ───────────────────────────────────────────────

  test("F4 · writing back *** keeps the stored secret byte-identical") {
    withRoutes(daemonWith("mail", Some(validPanel))) { routes =>
      val put1 = call(routes, Method.PUT, "/daemons/mail/config-panel", Some(json("""{"values":{"host":"h","password":"keep"}}""")))
      assertEquals(put1._1, Status.Ok)
      val secretPath = PathUtil.dataRoot / "secrets" / "daemon-mail-password"
      val before = os.read(secretPath)
      // Echo back exactly what the read view returned.
      val read = call(routes, Method.GET, "/daemons/mail/config-panel")._2
      val masked = read.flatMap(_.hcursor.downField("values").focus).getOrElse(Json.Null)
      assertEquals(call(routes, Method.PUT, "/daemons/mail/config-panel", Some(Json.obj("values" -> masked)))._1, Status.Ok)
      assertEquals(os.read(secretPath), before, "*** round-trip rewrote the secret (F4)")
    }
  }

  // ── F5 · no plaintext in daemons.json ─────────────────────────────────

  test("F5 · the plaintext secret never reaches daemons.json (口径 α)") {
    withRoutes(daemonWith("mail", Some(validPanel))) { routes =>
      assertEquals(
        call(routes, Method.PUT, "/daemons/mail/config-panel", Some(json("""{"values":{"password":"top-secret-value"}}""")))._1,
        Status.Ok
      )
      val daemonsBytes = os.read(PathUtil.dataRoot / "daemons.json")
      assert(!daemonsBytes.contains("top-secret-value"), "plaintext leaked into daemons.json (F5)")
      assertEquals(os.read(PathUtil.dataRoot / "secrets" / "daemon-mail-password"), "top-secret-value")
    }
  }

  // ── F9 · the read-only credential probe (the E4-named second endpoint) ─

  test("F9 · the credential probe reports the three states and leaks no value") {
    withRoutes(daemonWith("mail", Some(validPanel))) { routes =>
      assertEquals(
        call(routes, Method.PUT, "/daemons/mail/config-panel", Some(json("""{"values":{"password":"probe-plaintext"}}""")))._1,
        Status.Ok
      )
      val (status, body) = call(routes, Method.GET, "/daemons/mail/config-panel/credentials")
      assertEquals(status, Status.Ok)
      assert(!body.exists(_.noSpaces.contains("probe-plaintext")), "the probe leaked a value")
      val creds = body.flatMap(_.hcursor.downField("credentials").as[List[Json]].toOption).getOrElse(Nil)
      assertEquals(creds.map(_.hcursor.get[String]("key").toOption), List(Some("password")))
      assertEquals(creds.map(_.hcursor.get[String]("state").toOption), List(Some("ok")))
    }
  }

  test("F9 · kind web is denied without the explicit switch and served with it") {
    val web = """{"kind": "web", "url": "http://localhost:3000/config"}"""
    withRoutes(daemonWith("mail", Some(web)), allowWeb = false) { routes =>
      assertEquals(call(routes, Method.GET, "/daemons/mail/config-panel")._1, Status.Conflict)
    }
    withRoutes(daemonWith("mail", Some(web)), allowWeb = true) { routes =>
      val (status, body) = call(routes, Method.GET, "/daemons/mail/config-panel")
      assertEquals(status, Status.Ok)
      assertEquals(body.flatMap(_.hcursor.get[String]("kind").toOption), Some("web"))
      // E1/F-6: the published sandbox token set never carries allow-same-origin.
      val tokens = body.flatMap(_.hcursor.downField("sandbox").as[List[String]].toOption).getOrElse(Nil)
      assertEquals(tokens, List("allow-scripts"))
      // E3: the panel URL carries zero credential parameters.
      val url = body.flatMap(_.hcursor.get[String]("panelUrl").toOption).getOrElse("")
      assert(!url.contains("token=") && !url.contains("ticket="), s"credential param in panelUrl: $url")
    }
  }

  // ── F1 · no panel ⇒ the baseline response is untouched ────────────────

  test("F1 · a daemon with no declaration contributes an unchanged key set") {
    withRoutes(daemonWith("mail", None)) { routes =>
      val (status, body) = call(routes, Method.GET, "/daemons")
      assertEquals(status, Status.Ok)
      val entries = body.flatMap(_.hcursor.downField("daemons").as[List[Json]].toOption).getOrElse(Nil)
      val keys = entries.map(_.asObject.map(_.keys.toSet).getOrElse(Set.empty))
      assert(keys.forall(!_.contains("hasConfigPanel")), s"unexpected panel flag: $keys")
      // Baseline fields are all still present on a config-less daemon.
      val expected = Set("id", "name", "status", "command", "autoStart", "restartOnExit")
      keys.foreach(k => assert(expected.subsetOf(k), s"missing baseline keys: ${expected.diff(k)}"))
    }
  }
