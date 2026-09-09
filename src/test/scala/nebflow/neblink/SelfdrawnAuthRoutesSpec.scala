package nebflow.neblink

import cats.effect.IO
import cats.effect.std.Dispatcher
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.agent.SharedResources
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.RestApiRoutes
import nebflow.llm.{ModelCandidate, NebflowServiceConfig, ServiceLlmConfig, ThinkingConfig}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*

/**
  * Selfdrawn (BFF) login routes — full-stack against a scripted mock that
  * plays BOTH the Logto provider (`/oidc/auth` 303 + interaction cookies,
  * Experience API, consent, token) and the neblink-server register endpoint.
  *
  * Nails the 2026-09-09 design §2.2 chain: start (303 intercepted server-
  * side, jar holds _interaction) → step proxies carry the jar cookie →
  * submit/consent orchestration finalizes through the SHARED token-exchange
  * + device-registration chain (refresh-token invariant: prompt=consent on
  * /oidc/auth) → /auth/social-callback runs verify→identification→submit
  * (+consent)→finalize with the state as CSRF token.
  *
  * 时序注意：mock server 的 stop 必须挂在 IO guarantee 上（munit 的 IO 体
  * 返回后才执行）。
  */
class SelfdrawnAuthRoutesSpec extends CatsEffectSuite:

  private val TestToken = "test-token-selfdrawn"

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-selfdrawn-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  // ── recorded traffic + script knobs ──────────────────────────────────────

  private case class Recorded(method: String, path: String, cookie: String, auth: String, body: String)

  private val capturedState = AtomicReference("")
  private val capturedRedirectUri = AtomicReference("")
  private val capturedChallenge = AtomicReference("")
  private val capturedPrompt = AtomicReference("")
  private val tokenRequestBody = AtomicReference("")
  private val registerAuth = AtomicReference("")
  private val socialVerifyBody = AtomicReference("")
  private val consentCalls = new ConcurrentLinkedQueue[String]()
  /** submit endpoint behavior: final (default) | consent | mfa */
  private val submitMode = AtomicReference("final")
  /** password endpoint behavior: ok (default) | bad-credentials */
  private val passwordMode = AtomicReference("ok")

  private def callbackRedirect(code: String): String =
    s"http://127.0.0.1:8080/auth/callback?code=$code&state=${capturedState.get()}"

  private def startMockServer: IO[(HttpServer, String, ConcurrentLinkedQueue[Recorded])] =
    IO.blocking {
      val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      val calls = new ConcurrentLinkedQueue[Recorded]()

      def record(ex: HttpExchange): String =
        val body = new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
        calls.add(Recorded(
          ex.getRequestMethod,
          ex.getRequestURI.getPath,
          Option(ex.getRequestHeaders.getFirst("Cookie")).getOrElse(""),
          Option(ex.getRequestHeaders.getFirst("Authorization")).getOrElse(""),
          body))
        body

      def send(ex: HttpExchange, status: Int, body: String, headers: (String, String)*): Unit =
        headers.foreach { case (k, v) => ex.getResponseHeaders.add(k, v) }
        if body.isEmpty then ex.sendResponseHeaders(status, -1)
        else
          val bytes = body.getBytes(StandardCharsets.UTF_8)
          ex.getResponseHeaders.add("Content-Type", "application/json")
          ex.sendResponseHeaders(status, bytes.length.toLong)
          ex.getResponseBody.write(bytes)
          ex.getResponseBody.close()

      // Logto: authorize → 303 + interaction cookies (design §1.3 probe).
      server.createContext("/oidc/auth", ex =>
        record(ex)
        val q = queryOf(ex)
        capturedState.set(q.getOrElse("state", ""))
        capturedRedirectUri.set(q.getOrElse("redirect_uri", ""))
        capturedChallenge.set(q.getOrElse("code_challenge", ""))
        capturedPrompt.set(q.getOrElse("prompt", ""))
        send(ex, 303, "",
          "Location" -> "https://auth.example/sign-in?app_id=x",
          "Set-Cookie" -> "_interaction=ixn-1; Path=/; HttpOnly; Max-Age=3600",
          "Set-Cookie" -> "_interaction.sig=sig-1; Path=/; HttpOnly; Max-Age=3600"))

      server.createContext("/oidc/token", ex =>
        tokenRequestBody.set(record(ex))
        send(ex, 200, """{"access_token":"at-1","refresh_token":"rt-1","token_type":"Bearer"}"""))

      server.createContext("/api/device/register", ex =>
        registerAuth.set(Option(ex.getRequestHeaders.getFirst("Authorization")).getOrElse(""))
        record(ex)
        send(ex, 200, """{"deviceToken":"dtok-1","networkId":"net-1","avatarUrl":"","githubUsername":""}"""))

      server.createContext("/api/experience", ex =>
        record(ex)
        send(ex, 204, ""))

      server.createContext("/api/experience/verification/password", ex =>
        record(ex)
        if passwordMode.get() == "bad-credentials" then
          send(ex, 422, """{"code":"session.invalid_credentials","message":"密码不正确"}""")
        else send(ex, 200, """{"verificationId":"vid-pw"}"""))

      server.createContext("/api/experience/verification/verification-code", ex =>
        record(ex)
        send(ex, 200, """{"verificationId":"vid-code"}"""))

      server.createContext("/api/experience/verification/totp/verify", ex =>
        record(ex)
        send(ex, 200, """{"verificationId":"vid-totp"}"""))

      server.createContext("/api/experience/verification/social/github/authorization-uri", ex =>
        record(ex)
        send(ex, 200, """{"redirectTo":"https://github.com/login/oauth/authorize?client_id=x"}"""))

      server.createContext("/api/experience/verification/social/github/verify", ex =>
        socialVerifyBody.set(record(ex))
        send(ex, 200, """{"verificationId":"vid-social"}"""))

      server.createContext("/api/experience/submit", ex =>
        record(ex)
        submitMode.get() match
          case "consent" => send(ex, 200, """{"redirectTo":"https://auth.example/consent"}""")
          case "mfa"     => send(ex, 422, """{"code":"session.mfa_required","message":"totp required"}""")
          case _         => send(ex, 200, Json.obj("redirectTo" -> callbackRedirect("code-1").asJson).noSpaces))

      server.createContext("/api/interaction/consent", ex =>
        record(ex)
        consentCalls.add(s"${ex.getRequestMethod} ${ex.getRequestURI.getPath}")
        ex.getRequestMethod.toUpperCase match
          case "GET"  => send(ex, 200, """{"scopes":["openid","offline_access"]}""")
          case _      => send(ex, 200, Json.obj("redirectTo" -> callbackRedirect("code-2").asJson).noSpaces))

      server.start()
      (server, s"http://127.0.0.1:${server.getAddress.getPort}", calls)
    }

  private def queryOf(ex: HttpExchange): Map[String, String] =
    Option(ex.getRequestURI.getRawQuery).getOrElse("")
      .split("&").toList.filter(_.nonEmpty)
      .map(kv => kv.split("=", 2) match
        case Array(k)    => k -> ""
        case Array(k, v) => java.net.URLDecoder.decode(k, "UTF-8") -> java.net.URLDecoder.decode(v, "UTF-8"))
      .toMap

  // ── stack under test ─────────────────────────────────────────────────────

  private def mkResources: SharedResources =
    SharedResources(
      llm = null,
      dispatcher = null,
      sessionStore = null,
      projectRoot = os.pwd,
      thinkingConfigRef = cats.effect.Ref.unsafe[IO, ThinkingConfig](ThinkingConfig()),
      rateLimiter = null,
      fileChangeTracker = null,
      contextWindow = 100_000,
      agentLibrary = null,
      taskStore = FileTaskStore,
      historyArchiver = HistoryArchiver.fileSystem(os.Path(tmpDir, os.pwd) / "archives"),
      fileLockManager = FileLockManager.create.unsafeRunSync(),
      sessionModelOverrides = cats.effect.Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = cats.effect.Ref.unsafe[IO, Boolean](false),
      friendService = None
    )

  private case class Stack(routes: RestApiRoutes, ms: NeblinkService)

  private def mkStack(serverUrl: String, dispatcher: Dispatcher[IO]): IO[Stack] =
    for
      ms <- NeblinkService.create(0, dispatcher)
      ps = new NeblinkPresenceService(ms, 0)(dispatcher)
      discovery = new NeblinkDiscovery(ms, 0, ps, None)
      routes = new RestApiRoutes(
        token = TestToken,
        configRef = cats.effect.Ref.unsafe[IO, NebflowServiceConfig](
          NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
        ),
        sharedResources = mkResources,
        sessionStore = null,
        wsRoutes = null,
        neblinkService = Some(ms),
        neblinkDiscovery = Some(discovery)
      )
      _ <- ms.updateConfig(cfg => cfg.copy(
        enabled = false,
        logto = Some(LogtoConfig(endpoint = serverUrl, clientId = "legacy", pkceClientId = Some("pkce-app"))),
        neblinkServer = Some(NeblinkServerConfig(url = serverUrl, networkId = "", secret = ""))
      ))
    yield Stack(routes, ms)

  private def postJson(path: String, body: Json, withAuth: Boolean = true): Request[IO] =
    val r = Request[IO](Method.POST, Uri.unsafeFromString(path)).withEntity(body)
    if withAuth then r.withHeaders(Headers("Authorization" -> s"Bearer $TestToken")) else r

  private def authedGet(path: String): Request[IO] =
    Request[IO](Method.GET, Uri.unsafeFromString(path))
      .withHeaders(Headers("Authorization" -> s"Bearer $TestToken"))

  private def runRoute(routes: RestApiRoutes, req: Request[IO]): IO[Response[IO]] =
    routes.routes(req).value.map(_.getOrElse(fail(s"route fell through: $req")))

  private def runAuthCallback(routes: RestApiRoutes, path: String): IO[Response[IO]] =
    routes.authCallbackRoutes(Request[IO](Method.GET, Uri.unsafeFromString(path)))
      .value.map(_.getOrElse(fail(s"auth callback fell through: $path")))

  private def bodyOf(resp: Response[IO]): IO[String] =
    resp.body.through(fs2.text.utf8.decode).compile.string

  private def statusOf(routes: RestApiRoutes): IO[String] =
    runRoute(routes, authedGet("/neblink/auth/state")).flatMap(_.as[Json])
      .map(_.hcursor.downField("status").as[String].toOption.getOrElse(""))

  /** Common preamble: open the interaction, verify a password, identify —
    * leaves the session one submit away from done. */
  private def signInThroughIdentification(routes: RestApiRoutes): IO[Unit] =
    for
      start <- runRoute(routes, postJson("/neblink/auth/selfdrawn/start", Json.obj()))
      _ <- IO(assertEquals(start.status, Status.Ok, s"start failed: $start"))
      pw <- runRoute(routes, postJson("/neblink/auth/selfdrawn/password",
        Json.obj("identifier" -> Json.obj("type" -> "username".asJson, "value" -> "alice".asJson), "password" -> "pw".asJson)))
      _ <- IO(assertEquals(pw.status, Status.Ok, s"password step failed: $pw"))
      ident <- runRoute(routes, postJson("/neblink/auth/selfdrawn/identification",
        Json.obj("verificationId" -> "vid-pw".asJson)))
      _ <- IO(assertEquals(ident.status, Status.Ok, s"identification failed: $ident"))
    yield ()

  // ── tests ────────────────────────────────────────────────────────────────

  test("password login happy path: start intercepts the 303 server-side, steps carry the jar, submit finalizes") {
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url, calls) =>
        mkStack(url, dispatcher).flatMap { st =>
          for
            _ <- signInThroughIdentification(st.routes)
            submit <- runRoute(st.routes, postJson("/neblink/auth/selfdrawn/submit", Json.obj()))
            submitBody <- submit.as[Json]
            state <- statusOf(st.routes)
            cred <- DeviceCredential.load
            list = calls.asScala.toList
          yield
            assertEquals(submit.status, Status.Ok, s"submit failed: $submitBody")
            assertEquals(submitBody.hcursor.downField("ok").as[Boolean].toOption, Some(true))
            // /oidc/auth: server-side open with the refresh-token invariant.
            val oidc = list.find(_.path == "/oidc/auth").getOrElse(fail("/oidc/auth never called"))
            assertEquals(oidc.method, "GET")
            assertEquals(capturedPrompt.get(), "consent", "prompt=consent is the refresh-token invariant")
            assertEquals(capturedRedirectUri.get(), "http://127.0.0.1:8080/auth/callback")
            assert(capturedState.get().nonEmpty, "state must be generated")
            assert(capturedChallenge.get().nonEmpty, "PKCE challenge must be generated")
            // Interaction event registered.
            assert(list.exists(r => r.method == "PUT" && r.path == "/api/experience" &&
              r.body.contains("SignIn")), s"interaction event not registered: ${list.map(_.path)}")
            // Step proxies carried the gateway-held jar.
            val pw = list.find(_.path == "/api/experience/verification/password").getOrElse(fail("password step missing"))
            assert(pw.cookie.contains("_interaction=ixn-1"), s"jar cookie missing on the step proxy: ${pw.cookie}")
            assert(pw.cookie.contains("_interaction.sig=sig-1"))
            // submit → shared chain: token exchange with the emitted code + a verifier.
            val tokenBody = tokenRequestBody.get()
            assert(tokenBody.contains("grant_type=authorization_code"), tokenBody)
            assert(tokenBody.contains("code=code-1"), tokenBody)
            assert(tokenBody.contains("code_verifier="), "PKCE verifier must reach the token endpoint")
            // Device registration with the exchanged access token.
            assertEquals(registerAuth.get(), "Bearer at-1")
            // Sticky state machine + persisted credential with the refresh token.
            assertEquals(state, "success")
            assert(cred.isDefined, "device credential must persist")
            assertEquals(cred.flatMap(_.logto).map(_.refreshToken), Some("rt-1"))
        }.guarantee(IO.blocking(server.stop(0)))
      }
    }
  }

  test("submit with a consent hop auto-resolves via GET info + POST approve, then finalizes") {
    submitMode.set("consent")
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url, _) =>
        mkStack(url, dispatcher).flatMap { st =>
          for
            _ <- signInThroughIdentification(st.routes)
            submit <- runRoute(st.routes, postJson("/neblink/auth/selfdrawn/submit", Json.obj()))
            state <- statusOf(st.routes)
            consent = consentCalls.asScala.toList
          yield
            assertEquals(submit.status, Status.Ok, s"consent submit failed: $submit")
            assertEquals(consent, List("GET /api/interaction/consent", "POST /api/interaction/consent"),
              s"consent dance expected, got $consent")
            assertEquals(tokenRequestBody.get().contains("code=code-2"), true,
              "finalize must use the POST-consent redirect code")
            assertEquals(state, "success")
        }.guarantee(IO.blocking(server.stop(0)))
      }
    }
  }

  test("mfa-required surfaces as pass-through 422; wizard drives TOTP then re-submits") {
    submitMode.set("mfa")
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url, _) =>
        mkStack(url, dispatcher).flatMap { st =>
          for
            _ <- signInThroughIdentification(st.routes)
            first <- runRoute(st.routes, postJson("/neblink/auth/selfdrawn/submit", Json.obj()))
            firstBody <- first.as[Json]
            // Wizard-driven TOTP step.
            totp <- runRoute(st.routes, postJson("/neblink/auth/selfdrawn/mfa/totp", Json.obj("code" -> "123456".asJson)))
            _ <- IO(assertEquals(totp.status, Status.Ok))
            // Session must have SURVIVED the mid-flow error.
            sessionAlive <- statusOf(st.routes)
            _ <- IO(assertEquals(sessionAlive, "pending", "session must survive mfa-required"))
            _ = submitMode.set("final")
            second <- runRoute(st.routes, postJson("/neblink/auth/selfdrawn/submit", Json.obj()))
            state <- statusOf(st.routes)
          yield
            assertEquals(first.status, Status.UnprocessableEntity)
            assertEquals(firstBody.hcursor.downField("code").as[String].toOption, Some("session.mfa_required"))
            assertEquals(firstBody.hcursor.downField("upstreamStatus").as[Int].toOption, Some(422))
            assertEquals(second.status, Status.Ok, s"re-submit failed: $second")
            assertEquals(state, "success")
        }.guarantee(IO.blocking(server.stop(0)))
      }
    }
  }

  test("upstream error bodies pass through with their status + code (invalid_credentials)") {
    passwordMode.set("bad-credentials")
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url, _) =>
        mkStack(url, dispatcher).flatMap { st =>
          for
            start <- runRoute(st.routes, postJson("/neblink/auth/selfdrawn/start", Json.obj()))
            _ <- IO(assertEquals(start.status, Status.Ok))
            pw <- runRoute(st.routes, postJson("/neblink/auth/selfdrawn/password",
              Json.obj("identifier" -> Json.obj("type" -> "username".asJson, "value" -> "alice".asJson), "password" -> "wrong".asJson)))
            pwBody <- pw.as[Json]
          yield
            // The wizard branches on `code`; the upstream 422 is preserved.
            assertEquals(pw.status, Status.UnprocessableEntity)
            assertEquals(pwBody.hcursor.downField("code").as[String].toOption, Some("session.invalid_credentials"))
            assertEquals(pwBody.hcursor.downField("upstreamStatus").as[Int].toOption, Some(422))
        }.guarantee(IO.blocking(server.stop(0)))
      }
    }
  }

  test("password step validates required fields before any upstream call") {
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url, _) =>
        mkStack(url, dispatcher).flatMap { st =>
          for
            _ <- runRoute(st.routes, postJson("/neblink/auth/selfdrawn/start", Json.obj()))
            bad <- runRoute(st.routes, postJson("/neblink/auth/selfdrawn/password", Json.obj()))
            badBody <- bad.as[Json]
            fresh <- statusOf(st.routes)
          yield
            assertEquals(bad.status, Status.BadRequest)
            assertEquals(badBody.hcursor.downField("code").as[String].toOption, Some("selfdrawn-bad-request"))
            assertEquals(fresh, "pending", "the session must survive a client-side 400")
        }.guarantee(IO.blocking(server.stop(0)))
      }
    }
  }

  test("social branch: social returns the IdP URL, social-callback runs the full chain to success") {
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url, calls) =>
        mkStack(url, dispatcher).flatMap { st =>
          for
            _ <- runRoute(st.routes, postJson("/neblink/auth/selfdrawn/start", Json.obj()))
            social <- runRoute(st.routes, postJson("/neblink/auth/selfdrawn/social", Json.obj("target" -> "github".asJson)))
            socialBody <- social.as[Json]
            idpUrl = socialBody.hcursor.downField("redirectTo").as[String].toOption.getOrElse(fail("redirectTo missing"))
            // IdP → Logto → 303 → the local route (query shape per §2.3 #2).
            cb <- runAuthCallback(st.routes, s"/social-callback?code=gh-c&state=${capturedState.get()}")
            cbBody <- bodyOf(cb)
            state <- statusOf(st.routes)
            verifyPayload = io.circe.parser.parse(socialVerifyBody.get()).toOption.getOrElse(Json.Null)
            list = calls.asScala.toList
          yield
            assertEquals(social.status, Status.Ok)
            assertEquals(idpUrl, "https://github.com/login/oauth/authorize?client_id=x", "IdP URL, NOT the auth domain")
            assert(idpUrl.startsWith("https://github.com"), s"browser must only ever see the IdP domain: $idpUrl")
            assertEquals(cb.status, Status.Ok, s"social callback failed: $cbBody")
            assert(cbBody.contains("登录成功"), "browser lands on the local success page")
            assertEquals(state, "success")
            // connectorData mapping (待锁 seam): every query param as a string field.
            assertEquals(verifyPayload.hcursor.downField("code").as[String].toOption, Some("gh-c"))
            assertEquals(verifyPayload.hcursor.downField("state").as[String].toOption, Some(capturedState.get()))
            // The chain finalized: token + register happened.
            assert(tokenRequestBody.get().contains("grant_type=authorization_code"), tokenRequestBody.get())
            assertEquals(registerAuth.get(), "Bearer at-1")
            // The authorization-uri call carried the LOCAL social-callback uri.
            val authUri = list.find(_.path == "/api/experience/verification/social/github/authorization-uri")
              .getOrElse(fail("authorization-uri never called"))
            assert(authUri.body.contains("social-callback"), authUri.body)
        }.guarantee(IO.blocking(server.stop(0)))
      }
    }
  }

  test("social-callback rejects a state mismatch (CSRF) and kills the session") {
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url, _) =>
        mkStack(url, dispatcher).flatMap { st =>
          for
            _ <- runRoute(st.routes, postJson("/neblink/auth/selfdrawn/start", Json.obj()))
            _ <- runRoute(st.routes, postJson("/neblink/auth/selfdrawn/social", Json.obj("target" -> "github".asJson)))
            cb <- runAuthCallback(st.routes, "/social-callback?code=gh-c&state=WRONG")
            cbBody <- bodyOf(cb)
            state <- statusOf(st.routes)
          yield
            assertEquals(cb.status, Status.BadRequest)
            assert(cbBody.contains("登录回调校验失败"), s"error page expected: $cbBody")
            assertEquals(state, "error")
        }.guarantee(IO.blocking(server.stop(0)))
      }
    }
  }

  test("step routes without a session answer selfdrawn-no-session; start needs the gateway token") {
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url, _) =>
        mkStack(url, dispatcher).flatMap { st =>
          for
            noSession <- runRoute(st.routes, postJson("/neblink/auth/selfdrawn/password",
              Json.obj("password" -> "x".asJson)))
            noSessionBody <- noSession.as[Json]
            noToken <- runRoute(st.routes, postJson("/neblink/auth/selfdrawn/start", Json.obj(), withAuth = false))
            noTokenBody <- noToken.as[Json]
          yield
            assertEquals(noSession.status, Status.BadRequest)
            assertEquals(noSessionBody.hcursor.downField("code").as[String].toOption, Some("selfdrawn-no-session"))
            assertEquals(noToken.status, Status.Forbidden)
            assertEquals(noTokenBody.hcursor.downField("error").as[String].toOption, Some("Unauthorized"))
        }.guarantee(IO.blocking(server.stop(0)))
      }
    }
  }

end SelfdrawnAuthRoutesSpec
