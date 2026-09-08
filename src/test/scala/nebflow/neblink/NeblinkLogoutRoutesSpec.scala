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
import scala.jdk.CollectionConverters.*

/**
 * POST /neblink/logout completeness tests (neblink client online-status fix, C1).
 *
 * Root cause being nailed: the desktop logout path cleared ONLY local state —
 * it never told the NebLink Server (no DELETE /api/device/logout, so the
 * server kept the device session until the 90s TTL purge) and never stopped
 * the relay tunnel. The route must now, in order:
 *   1. best-effort DELETE /api/device/logout on the LIVE discovery client
 *      (relayClientOpt may hold a stale instance after enrollment hot-swap);
 *   2. stop the relay tunnel;
 *   3. disconnect all P2P presence connections (anti ghost-resurrection);
 *   4-8. existing local cleanup (credential, config, client, user info, peers).
 *
 * And the hard requirement: logout must ALWAYS succeed locally — with the
 * server unreachable the route still returns 200 and completes local cleanup.
 *
 * Lives in package nebflow.neblink (not gateway) to reach the
 * private[neblink] tunnel test seam (isRunning).
 *
 * 时序注意：mock server 的 stop 必须挂在 IO guarantee 上，不能写在同步
 * finally 里（munit 的 IO 体返回后才执行）。
 */
class NeblinkLogoutRoutesSpec extends CatsEffectSuite:

  private val TestToken = "test-token-logout"

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-logout-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  // ── mock neblink-server ─────────────────────────────────

  /** (method, path, authHeader) of every /api/device/logout call seen. */
  private case class Recorded(method: String, path: String, auth: String)

  private def startMockServer: IO[(HttpServer, String, ConcurrentLinkedQueue[Recorded])] =
    IO.blocking {
      val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      val logoutCalls = new ConcurrentLinkedQueue[Recorded]()

      def respond(ex: HttpExchange, status: Int, body: String): Unit =
        // Drain the request body first — JDK HttpServer keep-alive requires
        // the handler to consume it (leftover bytes corrupt the next request).
        ex.getRequestBody.transferTo(java.io.OutputStream.nullOutputStream())
        ex.getRequestBody.close()
        val bytes = body.getBytes(StandardCharsets.UTF_8)
        ex.getResponseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.length.toLong)
        val os = ex.getResponseBody
        os.write(bytes)
        os.close()

      server.createContext(
        "/api/device/login",
        ex => respond(ex, 200, """{"token":"tok-1","networkId":"n1","deviceId":"d1","peers":[]}""")
      )
      server.createContext(
        "/api/device/logout",
        ex =>
          logoutCalls.add(Recorded(ex.getRequestMethod, ex.getRequestURI.getPath,
            Option(ex.getRequestHeaders.getFirst("Authorization")).getOrElse("")))
          respond(ex, 200, """{"ok":true}""")
      )
      server.start()
      (server, s"http://127.0.0.1:${server.getAddress.getPort}", logoutCalls)
    }

  // ── routes under test ───────────────────────────────────

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

  private def mkRoutes(ms: NeblinkService, discovery: NeblinkDiscovery): RestApiRoutes =
    new RestApiRoutes(
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

  private def logoutRequest: Request[IO] =
    Request[IO](Method.POST, Uri.unsafeFromString("/neblink/logout"))
      .withHeaders(Headers("Authorization" -> s"Bearer $TestToken"))

  /** Full stack: service + presence + tunnel + discovery, wired like GatewayMain. */
  private case class Stack(
    ms: NeblinkService,
    ps: NeblinkPresenceService,
    tunnel: NeblinkRelayTunnel,
    discovery: NeblinkDiscovery,
    client: NeblinkClient,
    routes: RestApiRoutes
  )

  private def mkStack(serverUrl: String, dispatcher: Dispatcher[IO]): IO[Stack] =
    for
      ms <- NeblinkService.create(0, dispatcher)
      client = new NeblinkClient(NeblinkServerConfig(url = serverUrl, networkId = "n1", secret = "s"), 0)
      ps = new NeblinkPresenceService(ms, 0)(dispatcher)
      tunnel = new NeblinkRelayTunnel(ms, serverUrl, () => client.currentSessionToken)(dispatcher)
      _ = ms.setRelayClient(Some(client))
      _ = ms.setRelayTunnel(tunnel)
      _ = ms.setPresenceService(ps)
      discovery = new NeblinkDiscovery(ms, 0, ps, Some(client))
      _ <- ms.updateConfig(_.copy(enabled = true))
      _ <- DeviceCredential.save(DeviceCredential(serverUrl, "n1", "d1", "dev-tok"))
      // A peer that must not survive logout.
      _ <- ms.upsertPeer(PeerInfo("ghost", "GhostPC", "macos", "http://127.0.0.1:9"))
    yield Stack(ms, ps, tunnel, discovery, client, mkRoutes(ms, discovery))

  // ── tests ───────────────────────────────────────────────

  test("logout notifies server (DELETE /api/device/logout), stops tunnel, clears local state") {
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url, logoutCalls) =>
        mkStack(url, dispatcher).flatMap { st =>
          for
            _ <- st.client.login("d1", "dev", "macos", Nil)
            _ <- IO(assertEquals(st.client.currentSessionToken, Some("tok-1"), "login must set session token"))
            resp <- st.routes.routes(logoutRequest).value.map(_.getOrElse(fail("route fell through")))
            body <- resp.as[Json]
            calls = logoutCalls.asScala.toList
            peers <- st.ms.peers
            cfg <- st.ms.neblinkConfig
            cred <- DeviceCredential.load
            clientAfter <- st.discovery.currentClient
          yield
            assertEquals(resp.status, Status.Ok)
            assertEquals(body.hcursor.downField("ok").as[Boolean].toOption, Some(true))
            // C1-1: server notified on the live client
            assertEquals(calls.length, 1, s"exactly one logout call expected, got $calls")
            assertEquals(calls.head.method, "DELETE")
            assertEquals(calls.head.path, "/api/device/logout")
            assertEquals(calls.head.auth, "Bearer tok-1")
            // C1-2: relay tunnel stopped
            assertEquals(st.tunnel.isRunning, false, "relay tunnel must be stopped")
            assertEquals(st.tunnel.isAlive, false)
            // Local cleanup (pre-existing semantics, pinned here)
            assertEquals(st.client.currentSessionToken, None, "session token cleared")
            assertEquals(clientAfter, None, "discovery client hot-swapped to None")
            assertEquals(peers, Nil, "peers cleared")
            assertEquals(cred, None, "device credential file removed")
            assertEquals(cfg.enabled, false, "neblink disabled in config")
        }.guarantee(IO.blocking(server.stop(0)))
      }
    }
  }

  test("logout succeeds locally even when the server is unreachable") {
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url, _) =>
        mkStack(url, dispatcher).flatMap { st =>
          for
            _ <- st.client.login("d1", "dev", "macos", Nil)
            _ <- IO.blocking(server.stop(0)) // server goes away BEFORE logout
            resp <- st.routes.routes(logoutRequest).value.map(_.getOrElse(fail("route fell through")))
            body <- resp.as[Json]
            peers <- st.ms.peers
            cred <- DeviceCredential.load
          yield
            // Best-effort notify fails (connection refused) but logout completes.
            assertEquals(resp.status, Status.Ok)
            assertEquals(body.hcursor.downField("ok").as[Boolean].toOption, Some(true))
            assertEquals(st.client.currentSessionToken, None)
            assertEquals(st.tunnel.isRunning, false)
            assertEquals(peers, Nil)
            assertEquals(cred, None)
        }
      }
    }
  }

  test("status reports real per-peer freshness (C3 — no hardcoded online)") {
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url, _) =>
        mkStack(url, dispatcher).flatMap { st =>
          val now = System.currentTimeMillis()
          for
            _ <- st.ms.upsertPeer(PeerInfo("fresh", "FreshPC", "macos", "http://127.0.0.1:9", lastSeen = now))
            _ <- st.ms.upsertPeer(PeerInfo("stale", "StalePC", "windows", "http://127.0.0.1:9", lastSeen = now - 200_000))
            resp <- st.routes.routes(
              Request[IO](Method.GET, Uri.unsafeFromString("/neblink/status"))
                .withHeaders(Headers("Authorization" -> s"Bearer $TestToken"))
            ).value.map(_.getOrElse(fail("route fell through")))
            body <- resp.as[Json]
          yield
            assertEquals(resp.status, Status.Ok)
            val peers = body.hcursor.downField("peers").values.getOrElse(fail("peers array missing")).toList
            def onlineOf(id: String): Option[Boolean] =
              peers.find(_.hcursor.downField("deviceId").as[String].toOption.contains(id))
                .flatMap(_.hcursor.downField("online").as[Boolean].toOption)
            assertEquals(onlineOf("fresh"), Some(true), "recently-seen peer must be online")
            assertEquals(onlineOf("stale"), Some(false), "peer unseen for 200s must be offline (was hardcoded true)")
        }.guarantee(IO.blocking(server.stop(0)))
      }
    }
  }

  test("device status push flips /neblink/status online flag both ways (C6)") {
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url, _) =>
        mkStack(url, dispatcher).flatMap { st =>
          def statusOnline(id: String): IO[Option[Boolean]] =
            st.routes.routes(
              Request[IO](Method.GET, Uri.unsafeFromString("/neblink/status"))
                .withHeaders(Headers("Authorization" -> s"Bearer $TestToken"))
            ).value.flatMap(_.getOrElse(fail("route fell through")).as[Json]).map { body =>
              body.hcursor.downField("peers").values.getOrElse(Nil).toList
                .find(_.hcursor.downField("deviceId").as[String].toOption.contains(id))
                .flatMap(_.hcursor.downField("online").as[Boolean].toOption)
            }
          for
            // mkStack seeds peer "ghost" (freshly seen)
            before <- statusOnline("ghost")
            _ <- st.ms.applyServerPeerStatus("ghost", online = false)
            off <- statusOnline("ghost")
            _ <- st.ms.applyServerPeerStatus("ghost", online = true)
            on <- statusOnline("ghost")
          yield
            assertEquals(before, Some(true), "seeded peer starts online")
            assertEquals(off, Some(false), "offline push must flip status to offline")
            assertEquals(on, Some(true), "online push must flip status back")
        }.guarantee(IO.blocking(server.stop(0)))
      }
    }
  }

  // ── RP-initiated logout (end-session) ──────────────────────────────────

  private def endSessionRequest: Request[IO] =
    // Deliberately NO Authorization header: this is a browser navigation hop
    // (window.open) — the route must not depend on checkAuth.
    Request[IO](Method.GET, Uri.unsafeFromString("/neblink/auth/end-session"))

  test("end-session redirects to the provider end_session with the stored hint and tears local state down") {
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url, _) =>
        mkStack(url, dispatcher).flatMap { st =>
          for
            // Configure the AC provider like a real PKCE install + seed a
            // credential carrying the id_token hint.
            _ <- st.ms.updateConfig(cfg => cfg.copy(logto =
              Some(LogtoConfig(endpoint = "https://auth.example", clientId = "legacy", pkceClientId = Some("pkce-app")))))
            _ <- DeviceCredential.save(DeviceCredential(url, "n1", "d1", "dev-tok",
              logto = Some(LogtoRefresh("rt-1", 1L, Some("tok.hint.sig")))))
            resp <- st.routes.routes(endSessionRequest).value.map(_.getOrElse(fail("route fell through")))
            peers <- st.ms.peers
            cred <- DeviceCredential.load
            cfg <- st.ms.neblinkConfig
            clientAfter <- st.discovery.currentClient
          yield
            // 302 to the provider's end_session_endpoint, hint + return uri.
            assertEquals(resp.status, Status.Found)
            val loc = resp.headers.get[org.http4s.headers.Location].map(_.uri).getOrElse(fail("Location header missing"))
            assertEquals(loc.path.renderString, "/oidc/session/end")
            val q = loc.query.pairs.collect { case (k, Some(v)) => k -> v }.toMap
            assertEquals(q.get("id_token_hint"), Some("tok.hint.sig"), "stored id_token must be replayed verbatim as the hint")
            assertEquals(q.get("post_logout_redirect_uri"), Some("http://127.0.0.1:8080/auth/logged-out"))
            // Local teardown (same 8 steps as POST /neblink/logout) happened.
            assertEquals(cred, None, "credential (with the hint) removed")
            assertEquals(peers, Nil)
            assertEquals(clientAfter, None)
            assertEquals(cfg.enabled, false)
        }.guarantee(IO.blocking(server.stop(0)))
      }
    }
  }

  test("end-session without a stored id_token still logs out (no hint param, cookie-based)") {
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url, _) =>
        mkStack(url, dispatcher).flatMap { st =>
          for
            _ <- st.ms.updateConfig(cfg => cfg.copy(logto =
              Some(LogtoConfig(endpoint = "https://auth.example", clientId = "legacy", pkceClientId = Some("pkce-app")))))
            // Pre-RP-logout credential shape: no idToken block at all.
            _ <- DeviceCredential.save(DeviceCredential(url, "n1", "d1", "dev-tok",
              logto = Some(LogtoRefresh("rt-1", 1L))))
            resp <- st.routes.routes(endSessionRequest).value.map(_.getOrElse(fail("route fell through")))
          yield
            assertEquals(resp.status, Status.Found)
            val loc = resp.headers.get[org.http4s.headers.Location].map(_.uri).getOrElse(fail("Location header missing"))
            val q = loc.query.pairs.collect { case (k, Some(v)) => k -> v }.toMap
            assertEquals(q.contains("id_token_hint"), false, "never fabricate a hint — omit it entirely")
        }.guarantee(IO.blocking(server.stop(0)))
      }
    }
  }

  test("end-session answers logto-not-configured when no AC app is configured") {
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url, _) =>
        mkStack(url, dispatcher).flatMap { st =>
          // end-session reads effectiveLogto (same resolution as auth/start:
          // explicit block wins verbatim, missing block → embeddedDefault).
          // This pins the only 404 arm: an EXPLICIT block without a
          // pkceClientId (the embedded default always carries one).
          for
            _ <- st.ms.updateConfig(cfg => cfg.copy(logto = Some(LogtoConfig(endpoint = "https://auth.example", clientId = "legacy", pkceClientId = None))))
            resp <- st.routes.routes(endSessionRequest).value.map(_.getOrElse(fail("route fell through")))
            body <- resp.as[Json]
          yield
            assertEquals(resp.status, Status.NotFound)
            assertEquals(body.hcursor.downField("error").as[String].toOption, Some("logto-not-configured"))
        }.guarantee(IO.blocking(server.stop(0)))
      }
    }
  }

  test("auth/start with forceLogin=true returns a prompt=login+consent authorize URL") {
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url, _) =>
        mkStack(url, dispatcher).flatMap { st =>
          for
            _ <- st.ms.updateConfig(cfg => cfg.copy(logto =
              Some(LogtoConfig(endpoint = "https://auth.example", clientId = "legacy", pkceClientId = Some("pkce-app")))))
            req = Request[IO](Method.POST, Uri.unsafeFromString("/neblink/auth/start"))
              .withHeaders(Headers("Authorization" -> s"Bearer $TestToken"))
              .withEntity(Json.obj("forceLogin" -> true.asJson))
            resp <- st.routes.routes(req).value.map(_.getOrElse(fail("route fell through")))
            body <- resp.as[Json]
          yield
            assertEquals(resp.status, Status.Ok)
            val authorizeUrl = body.hcursor.downField("authorizeUrl").as[String].toOption.getOrElse(fail("authorizeUrl missing"))
            assert(authorizeUrl.contains("prompt=login+consent"), s"forceLogin must force the account form: $authorizeUrl")
            assert(authorizeUrl.contains("offline_access"), "refresh-token invariant must survive forceLogin")
        }.guarantee(IO.blocking(server.stop(0)))
      }
    }
  }

  test("auth/start without a body stays a plain prompt=consent login (empty-body tolerance)") {
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url, _) =>
        mkStack(url, dispatcher).flatMap { st =>
          for
            _ <- st.ms.updateConfig(cfg => cfg.copy(logto =
              Some(LogtoConfig(endpoint = "https://auth.example", clientId = "legacy", pkceClientId = Some("pkce-app")))))
            // No .withEntity — the legacy call shape (empty body).
            req = Request[IO](Method.POST, Uri.unsafeFromString("/neblink/auth/start"))
              .withHeaders(Headers("Authorization" -> s"Bearer $TestToken"))
            resp <- st.routes.routes(req).value.map(_.getOrElse(fail("route fell through")))
            body <- resp.as[Json]
          yield
            assertEquals(resp.status, Status.Ok)
            val authorizeUrl = body.hcursor.downField("authorizeUrl").as[String].toOption.getOrElse(fail("authorizeUrl missing"))
            assert(authorizeUrl.contains("prompt=consent") && !authorizeUrl.contains("login"), s"plain login unchanged: $authorizeUrl")
        }.guarantee(IO.blocking(server.stop(0)))
      }
    }
  }

  test("/auth/logged-out serves the RP-logout landing page") {
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url, _) =>
        mkStack(url, dispatcher).flatMap { st =>
          for
            resp <- st.routes.authCallbackRoutes(
              Request[IO](Method.GET, Uri.unsafeFromString("/logged-out"))
            ).value.map(_.getOrElse(fail("route fell through")))
            // Raw byte decode: CirceEntityCodec (imported above) would
            // otherwise route as[String] to the JSON decoder and choke on HTML.
            body <- resp.body.through(fs2.text.utf8.decode).compile.string
          yield
            assertEquals(resp.status, Status.Ok)
            assert(body.contains("已退出登录"), "landing page headline")
            assertEquals(resp.contentType.map(_.mediaType), Some(MediaType.text.html), "served as HTML")
        }.guarantee(IO.blocking(server.stop(0)))
      }
    }
  }

end NeblinkLogoutRoutesSpec
