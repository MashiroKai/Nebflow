package nebflow.neblink

import cats.effect.IO
import cats.effect.std.Dispatcher
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.agent.SharedResources
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.RestApiRoutes
import nebflow.llm.{ModelCandidate, NebflowServiceConfig, ServiceLlmConfig, ThinkingConfig}
import nebflow.shared.PathUtil
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
          logoutCalls.add(
            Recorded(
              ex.getRequestMethod,
              ex.getRequestURI.getPath,
              Option(ex.getRequestHeaders.getFirst("Authorization")).getOrElse("")
            )
          )
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
      // 2026-09-11：隧道 URL 改为连接期 live 解析（构造参已移除）
      tunnel = new NeblinkRelayTunnel(ms, () => IO.pure(client.currentSessionToken))(dispatcher)
      _ = ms.setRelayClient(Some(client))
      _ = ms.setRelayTunnel(tunnel)
      // 装配 owner（GatewayMain）的等价登记：enrollment 只发 ensure 信号。
      // （setRelayTunnelStarter 返回 IO ⇒ 必须走生成器）
      _ <- ms.setRelayTunnelStarter(tunnel.ensure())
      _ = ms.setPresenceService(ps)
      discovery = new NeblinkDiscovery(ms, 0, ps, Some(client))
      _ <- ms.updateConfig(
        _.copy(
          enabled = true,
          neblinkServer = Some(NeblinkServerConfig(url = serverUrl, networkId = "n1", secret = "s"))
        )
      )
      _ <- DeviceCredential.save(DeviceCredential(serverUrl, "n1", "d1", "dev-tok"))
      // A peer that must not survive logout.
      _ <- ms.upsertPeer(PeerInfo("ghost", "GhostPC", "macos", "http://127.0.0.1:9"))
    yield Stack(ms, ps, tunnel, discovery, client, mkRoutes(ms, discovery))

  // ── tests ───────────────────────────────────────────────

  test("logout notifies server (DELETE /api/device/logout), stops tunnel, clears local state") {
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url, logoutCalls) =>
        mkStack(url, dispatcher)
          .flatMap { st =>
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
          }
          .guarantee(IO.blocking(server.stop(0)))
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

  // ── 2026-09-11 隧道生命周期：stop() 不再是终局 ──────────────────────────

  test("logout -> re-enroll -> ensure revives the relay tunnel (stop() is no longer terminal)") {
    // 缺陷：`stop()`（logout 第 2 步）把 running 置 false，全仓无任何复位路径
    // ⇒「登出 → 再登录」后 relay 永久缺席到进程重启（/neblink/status 恒
    // relayAvailable:false、RemoteExecutor 恒判 relay 不可用）。
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url, _) =>
        mkStack(url, dispatcher)
          .flatMap { st =>
            for
              _ <- st.client.login("d1", "dev", "macos", Nil)
              _ <- st.tunnel.connect()
              _ <- IO(assert(st.tunnel.isRunning, "connect() 后隧道必须在跑"))
              resp <- st.routes.routes(logoutRequest).value.map(_.getOrElse(fail("route fell through")))
              afterLogout <- IO(st.tunnel.isRunning)
              spawnsAfterLogout <- IO(st.tunnel.loopSpawnCount)
              // 重新 enroll（device-flow / AC+PKCE 的等价物）：persist 内部发 ensure 信号
              _ <- NeblinkEnrollment.persist(
                st.ms,
                resolvedUrl = url,
                json = parse("""{"deviceToken":"tok-re","networkId":"n1"}""").toOption.get,
                logtoRefresh = None,
                discovery = Some(st.discovery),
                gatewayPort = 0,
                reloginHook = None
              )
              revived <- IO(st.tunnel.isRunning)
              spawns <- IO(st.tunnel.loopSpawnCount)
            yield
              assertEquals(resp.status, Status.Ok)
              assertEquals(afterLogout, false, "logout 必须停隧道（既有语义不变）")
              assertEquals(
                revived,
                true,
                "重新 enroll（ensure）后隧道必须复活 —— 此前 running 无复位路径，只有进程重启能救"
              )
              assertEquals(spawns, spawnsAfterLogout + 1, "ensure 只能拉起一条连接链（不得双隧道）")
          }
          .guarantee(IO.blocking(server.stop(0)))
      }
    }
  }

  test("status reports real per-peer freshness (C3 — no hardcoded online)") {
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url, _) =>
        mkStack(url, dispatcher)
          .flatMap { st =>
            val now = System.currentTimeMillis()
            for
              _ <- st.ms.upsertPeer(PeerInfo("fresh", "FreshPC", "macos", "http://127.0.0.1:9", lastSeen = now))
              _ <- st.ms.upsertPeer(
                PeerInfo("stale", "StalePC", "windows", "http://127.0.0.1:9", lastSeen = now - 200_000)
              )
              resp <- st.routes
                .routes(
                  Request[IO](Method.GET, Uri.unsafeFromString("/neblink/status"))
                    .withHeaders(Headers("Authorization" -> s"Bearer $TestToken"))
                )
                .value
                .map(_.getOrElse(fail("route fell through")))
              body <- resp.as[Json]
            yield
              assertEquals(resp.status, Status.Ok)
              val peers = body.hcursor.downField("peers").values.getOrElse(fail("peers array missing")).toList
              def onlineOf(id: String): Option[Boolean] =
                peers
                  .find(_.hcursor.downField("deviceId").as[String].toOption.contains(id))
                  .flatMap(_.hcursor.downField("online").as[Boolean].toOption)
              assertEquals(onlineOf("fresh"), Some(true), "recently-seen peer must be online")
              assertEquals(onlineOf("stale"), Some(false), "peer unseen for 200s must be offline (was hardcoded true)")
            end for
          }
          .guarantee(IO.blocking(server.stop(0)))
      }
    }
  }

  test("device status push flips /neblink/status online flag both ways (C6)") {
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url, _) =>
        mkStack(url, dispatcher)
          .flatMap { st =>
            def statusOnline(id: String): IO[Option[Boolean]] =
              st.routes
                .routes(
                  Request[IO](Method.GET, Uri.unsafeFromString("/neblink/status"))
                    .withHeaders(Headers("Authorization" -> s"Bearer $TestToken"))
                )
                .value
                .flatMap(_.getOrElse(fail("route fell through")).as[Json])
                .map { body =>
                  body.hcursor
                    .downField("peers")
                    .values
                    .getOrElse(Nil)
                    .toList
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
          }
          .guarantee(IO.blocking(server.stop(0)))
      }
    }
  }

  // ── RP-initiated logout (end-session) ──────────────────────────────────

  /**
   * POST + Bearer + optional JSON body —— 2026-09-20 收尾批的契约：该路由已套
   * `withAuth`，出口从 302 改为 `200 {"endSessionUrl": …}`。
   * 🔴 夹具**必须**带 Authorization：旧注记「Deliberately NO Authorization header …
   * must not depend on checkAuth」**已作废** —— 无令牌现在只得到 403（门在前），
   * 且登出副作用零发生（这正是本批要钉的翻转）。
   */
  private def endSessionRequest(body: Json = Json.obj()): Request[IO] =
    Request[IO](Method.POST, Uri.unsafeFromString("/neblink/auth/end-session"))
      .withHeaders(Headers("Authorization" -> s"Bearer $TestToken"))
      .withEntity(body)

  /** 换号腿：`scenario=switch` / `ui_locales` 由 query 迁入 POST body（本批契约）。 */
  private def endSessionSwitchRequest: Request[IO] =
    endSessionRequest(Json.obj("scenario" -> "switch".asJson, "uiLocales" -> "en".asJson))

  /** 本批出口 = 200 + JSON：从 body 取 provider end-session URL 再解析其 query。 */
  private def endSessionUrlOf(resp: Response[IO]): IO[Uri] =
    resp.as[Json].map { body =>
      val url = body.hcursor
        .downField("endSessionUrl")
        .as[String]
        .toOption
        .getOrElse(fail(s"endSessionUrl missing in the 200 body: $body"))
      Uri.unsafeFromString(url)
    }

  test("end-session returns the provider end_session URL with the stored hint and tears local state down") {
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url, _) =>
        mkStack(url, dispatcher)
          .flatMap { st =>
            for
              // Configure the AC provider like a real PKCE install + seed a
              // credential carrying the id_token hint.
              _ <- st.ms.updateConfig(cfg =>
                cfg.copy(logto =
                  Some(
                    LogtoConfig(endpoint = "https://auth.example", clientId = "legacy", pkceClientId = Some("pkce-app"))
                  )
                )
              )
              _ <- DeviceCredential.save(
                DeviceCredential(
                  url,
                  "n1",
                  "d1",
                  "dev-tok",
                  logto = Some(LogtoRefresh("rt-1", 1L, Some("tok.hint.sig")))
                )
              )
              resp <- st.routes.routes(endSessionRequest()).value.map(_.getOrElse(fail("route fell through")))
              loc <- endSessionUrlOf(resp)
              peers <- st.ms.peers
              cred <- DeviceCredential.load
              cfg <- st.ms.neblinkConfig
              clientAfter <- st.discovery.currentClient
            yield
              // 本批出口：200 + JSON（302 退场 —— fetch 无法消费跨域 302），hint + 回跳 uri 仍在 URL 上。
              assertEquals(resp.status, Status.Ok)
              assertEquals(loc.path.renderString, "/oidc/session/end")
              val q = loc.query.pairs.collect { case (k, Some(v)) => k -> v }.toMap
              assertEquals(
                q.get("id_token_hint"),
                Some("tok.hint.sig"),
                "stored id_token must be replayed verbatim as the hint"
              )
              assertEquals(q.get("post_logout_redirect_uri"), Some("http://127.0.0.1:8080/auth/logged-out"))
              // Local teardown (same 8 steps as POST /neblink/logout) happened.
              assertEquals(cred, None, "credential (with the hint) removed")
              assertEquals(peers, Nil)
              assertEquals(clientAfter, None)
              assertEquals(cfg.enabled, false)
          }
          .guarantee(IO.blocking(server.stop(0)))
      }
    }
  }

  test("end-session without a stored id_token still logs out (no hint param, cookie-based)") {
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url, _) =>
        mkStack(url, dispatcher)
          .flatMap { st =>
            for
              _ <- st.ms.updateConfig(cfg =>
                cfg.copy(logto =
                  Some(
                    LogtoConfig(endpoint = "https://auth.example", clientId = "legacy", pkceClientId = Some("pkce-app"))
                  )
                )
              )
              // Pre-RP-logout credential shape: no idToken block at all.
              _ <- DeviceCredential.save(
                DeviceCredential(url, "n1", "d1", "dev-tok", logto = Some(LogtoRefresh("rt-1", 1L)))
              )
              resp <- st.routes.routes(endSessionRequest()).value.map(_.getOrElse(fail("route fell through")))
              loc <- endSessionUrlOf(resp)
            yield
              assertEquals(resp.status, Status.Ok)
              val q = loc.query.pairs.collect { case (k, Some(v)) => k -> v }.toMap
              assertEquals(q.contains("id_token_hint"), false, "never fabricate a hint — omit it entirely")
          }
          .guarantee(IO.blocking(server.stop(0)))
      }
    }
  }

  test("end-session answers logto-not-configured when no AC app is configured") {
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url, _) =>
        mkStack(url, dispatcher)
          .flatMap { st =>
            // end-session reads effectiveLogto (same resolution as auth/start:
            // explicit block wins verbatim, missing block → embeddedDefault).
            // This pins the only 404 arm: an EXPLICIT block without a
            // pkceClientId (the embedded default always carries one).
            for
              _ <- st.ms.updateConfig(cfg =>
                cfg.copy(logto =
                  Some(LogtoConfig(endpoint = "https://auth.example", clientId = "legacy", pkceClientId = None))
                )
              )
              resp <- st.routes.routes(endSessionRequest()).value.map(_.getOrElse(fail("route fell through")))
              body <- resp.as[Json]
            yield
              assertEquals(resp.status, Status.NotFound)
              assertEquals(body.hcursor.downField("error").as[String].toOption, Some("logto-not-configured"))
          }
          .guarantee(IO.blocking(server.stop(0)))
      }
    }
  }

  test("auth/start with forceLogin=true returns a prompt=login+consent authorize URL") {
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url, _) =>
        mkStack(url, dispatcher)
          .flatMap { st =>
            for
              _ <- st.ms.updateConfig(cfg =>
                cfg.copy(logto =
                  Some(
                    LogtoConfig(endpoint = "https://auth.example", clientId = "legacy", pkceClientId = Some("pkce-app"))
                  )
                )
              )
              req = Request[IO](Method.POST, Uri.unsafeFromString("/neblink/auth/start"))
                .withHeaders(Headers("Authorization" -> s"Bearer $TestToken"))
                .withEntity(Json.obj("forceLogin" -> true.asJson))
              resp <- st.routes.routes(req).value.map(_.getOrElse(fail("route fell through")))
              body <- resp.as[Json]
            yield
              assertEquals(resp.status, Status.Ok)
              val authorizeUrl =
                body.hcursor.downField("authorizeUrl").as[String].toOption.getOrElse(fail("authorizeUrl missing"))
              assert(
                authorizeUrl.contains("prompt=login+consent"),
                s"forceLogin must force the account form: $authorizeUrl"
              )
              assert(
                !authorizeUrl.contains("offline_access"),
                s"O5: no offline_access on the forceLogin authorize either: $authorizeUrl"
              )
          }
          .guarantee(IO.blocking(server.stop(0)))
      }
    }
  }

  test("auth/start without a body stays a plain prompt=consent login (empty-body tolerance)") {
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url, _) =>
        mkStack(url, dispatcher)
          .flatMap { st =>
            for
              _ <- st.ms.updateConfig(cfg =>
                cfg.copy(logto =
                  Some(
                    LogtoConfig(endpoint = "https://auth.example", clientId = "legacy", pkceClientId = Some("pkce-app"))
                  )
                )
              )
              // No .withEntity — the legacy call shape (empty body).
              req = Request[IO](Method.POST, Uri.unsafeFromString("/neblink/auth/start"))
                .withHeaders(Headers("Authorization" -> s"Bearer $TestToken"))
              resp <- st.routes.routes(req).value.map(_.getOrElse(fail("route fell through")))
              body <- resp.as[Json]
            yield
              assertEquals(resp.status, Status.Ok)
              val authorizeUrl =
                body.hcursor.downField("authorizeUrl").as[String].toOption.getOrElse(fail("authorizeUrl missing"))
              assert(
                authorizeUrl.contains("prompt=consent") && !authorizeUrl.contains("login"),
                s"plain login unchanged: $authorizeUrl"
              )
          }
          .guarantee(IO.blocking(server.stop(0)))
      }
    }
  }

  test("auth/start forwards uiLocales zh/en and whitelists everything else (BYUI handoff)") {
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url, _) =>
        mkStack(url, dispatcher)
          .flatMap { st =>
            for
              _ <- st.ms.updateConfig(cfg =>
                cfg.copy(logto =
                  Some(
                    LogtoConfig(endpoint = "https://auth.example", clientId = "legacy", pkceClientId = Some("pkce-app"))
                  )
                )
              )
              start = (ui: Option[String]) =>
                val base = Request[IO](Method.POST, Uri.unsafeFromString("/neblink/auth/start"))
                  .withHeaders(Headers("Authorization" -> s"Bearer $TestToken"))
                val req = ui.fold(base)(v => base.withEntity(Json.obj("uiLocales" -> v.asJson)))
                st.routes
                  .routes(req)
                  .value
                  .map(_.getOrElse(fail("route fell through")))
                  .flatMap(_.as[Json])
                  .map(body =>
                    body.hcursor.downField("authorizeUrl").as[String].toOption.getOrElse(fail("authorizeUrl missing"))
                  )
              zhUrl <- start(Some("zh"))
              enUrl <- start(Some("en"))
              legacyUrl <- start(None) // old callers: no uiLocales field at all
              junkUrl <- start(Some("de-DE")) // not whitelisted → param omitted
            yield
              assert(zhUrl.contains("ui_locales=zh"), s"zh handoff must reach the authorize URL: $zhUrl")
              assert(enUrl.contains("ui_locales=en"), s"en handoff must reach the authorize URL: $enUrl")
              assert(!legacyUrl.contains("ui_locales"), s"legacy callers keep the byte-identical URL: $legacyUrl")
              assert(!junkUrl.contains("ui_locales"), s"non-whitelisted values are dropped, not forwarded: $junkUrl")
              // Invariants that must survive on every variant.
              for u <- Seq(zhUrl, enUrl, legacyUrl, junkUrl) do
                // prompt=consent stays an invariant of every variant, but as
                // shipped UX only — O5 removed the refresh-token rationale.
                assert(u.contains("prompt=consent"), s"prompt=consent intact on every variant: $u")
                assert(!u.contains("offline_access"), s"O5: no variant may request offline_access: $u")
          }
          .guarantee(IO.blocking(server.stop(0)))
      }
    }
  }

  test("/auth/logged-out serves the RP-logout landing page") {
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url, _) =>
        mkStack(url, dispatcher)
          .flatMap { st =>
            for
              resp <- st.routes
                .authCallbackRoutes(
                  Request[IO](Method.GET, Uri.unsafeFromString("/logged-out"))
                )
                .value
                .map(_.getOrElse(fail("route fell through")))
              // Raw byte decode: CirceEntityCodec (imported above) would
              // otherwise route as[String] to the JSON decoder and choke on HTML.
              body <- resp.body.through(fs2.text.utf8.decode).compile.string
            yield
              assertEquals(resp.status, Status.Ok)
              assert(body.contains("已退出登录"), "landing page headline")
              assertEquals(resp.contentType.map(_.mediaType), Some(MediaType.text.html), "served as HTML")
          }
          .guarantee(IO.blocking(server.stop(0)))
      }
    }
  }

  // ── 缺陷 A（2026-09-18）：坏凭据态下登出必须能自救（判据 G5）──────────────
  //
  // 修前形态：end-session 的凭据读点在删点**之前**且异常裸冒泡 ⇒ 整条路由 500、本地
  // 拆除一步没跑（凭据没删、config 没关、client 没置空），续登标记 arm 过但永不被
  // consume ⇒ 用户**无法通过「退出账号」自救**（上游 S3 / §6.1「登出也救不了」）。
  //
  // 🔴 round 1 补正（判词 D2）：夹具从 POSIX `chmod 000` 换成**平台中立**形态
  // （凭据落点上放一个非空目录 ⇒ `os.read` 抛 IOException：POSIX `IsADirectoryException` /
  // Windows `AccessDeniedException`，与 `chmod 000` 走**同一条分类边**）⇒ 本文件现在
  // **零 `assume`**、任何平台都真跑。诚实申报本夹具的**两处不可避免的差别**：
  //   · 目录夹具无法同时携带 id_token hint ⇒ 「读不开 ⇒ 不编造 hint」这条由
  //     「可读且有 hint ⇒ hint 必现」（本文件另一用例）+「可读但无 id_token ⇒ 无 hint」两支合起来钉；
  //   · 读失败会触发**自愈改名**（装置层既有行为）⇒ 路径在拆除第④步之前就已空出，
  //     故第④步在这里是幂等空转；「第④步真删一个在场凭据」由本文件既有的登出用例
  //     （`assertEquals(cred, None, "device credential file removed")`）钉住。
  //   为补上这层信息损失，本用例新增「读失败**带分类码** WARN 实测」——否则「夹具坏了」
  //   与「路由正确分类了」两种情形在断言上不可区分。
  test("G5 坏凭据（读不开）态下 end-session 仍 200 + endSessionUrl 且本地拆除八步生效（非 500）") {
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url, _) =>
        mkStack(url, dispatcher)
          .flatMap { st =>
            val credPath = os.Path(tmpDir, os.pwd) / "neblink" / "device.json"
            for
              _ <- st.ms.updateConfig(cfg =>
                cfg.copy(logto =
                  Some(
                    LogtoConfig(endpoint = "https://auth.example", clientId = "legacy", pkceClientId = Some("pkce-app"))
                  )
                )
              )
              // 平台中立的「读不开」夹具：先清掉 mkStack 落盘的真凭据，再把落点占成非空目录
              // （非空 ⇒ 删也删不掉；读抛 IOException）。
              _ <- DeviceCredential.clear
              _ <- IO.blocking {
                os.makeDir.all(credPath)
                os.write.over(credPath / "occupied-by-a-directory.txt", "not a credential file")
              }
              respAndWarns <- LogdevTestSupport.withWarnsIO(
                st.routes.routes(endSessionRequest()).value.map(_.getOrElse(fail("route fell through")))
              )
              resp = respAndWarns._1
              warns = respAndWarns._2
              loc <- endSessionUrlOf(resp)
              peers <- st.ms.peers
              cred <- DeviceCredential.load
              cfg <- st.ms.neblinkConfig
              clientAfter <- st.discovery.currentClient
              // 拆除第④步的读数：落点必须不再持有坏件（读失败的自愈改名 / 第④步删除皆算）
              gone <- IO.blocking(!os.exists(credPath))
            yield
              assertEquals(resp.status, Status.Ok, "坏凭据下登出必须仍能拿到 provider URL（200），不是 500")
              val q = loc.query.pairs.collect { case (k, Some(v)) => k -> v }.toMap
              assertEquals(q.contains("id_token_hint"), false, "读失败 ⇒ 跳过 hint（读不到就不编）")
              assert(
                warns.exists(_.contains(CredentialFailure.CredentialUnreadable.code)),
                s"读失败必须留一条带分类码的 WARN（否则「夹具坏了」与「正确分类」不可区分）: $warns"
              )
              assertEquals(cred, None, "本地凭据不在了")
              assert(gone, "凭据落点必须不再持有坏件（坏件不挡拆除）")
              assertEquals(peers, Nil, "第八步清 peers 生效")
              assertEquals(clientAfter, None, "第六步置空 client 生效")
              assertEquals(cfg.enabled, false, "第五步关 enabled 生效")
            end for
          }
          .guarantee(IO.blocking(server.stop(0)))
      }
    }
  }

  // ── 缺陷 A 返工 round 1 / 判词 D1：换号续登失败页（switchNoticePage）的文案负控 ──
  //
  // 判 fail 的主因：`AuthRoutes.scala` 的 `renderLoggedOutLanding` 续登失败支把 `e.getMessage` 原样送进这一页
  // ⇒ 同一屏泄漏「文件系统路径 + 整条 authorize URL + PKCE state」（判词探针 P7 原文，
  // 判据正则命中 2）。修法 = 走同文件既有的分类通道（三段式进页面、原文只进 WARN）。
  // 本用例把**改后**读数钉在三支可达分支上：① 续登失败支（就是泄漏那一支）；
  // ② 「登录服务未配置」静态支；③ 重放支（同一 landing URL 二次访问）。
  // 不可达的三支（Expired / 服务未初始化）只传**字面量**文案，无插值点 —— 见报告登记。
  test("D1 换号续登失败页：可见串过三重判据（正则 / data-root / device.json）且三段式带稳定码") {
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url, _) =>
        mkStack(url, dispatcher)
          .flatMap { st =>
            val dataRoot = os.Path(tmpDir, os.pwd)
            val goodLogto =
              Some(LogtoConfig(endpoint = "https://auth.example", clientId = "legacy", pkceClientId = Some("pkce-app")))
            val badEndpointLogto = Some(
              LogtoConfig(
                endpoint = "C:\\Users\\kaiyu\\.nebflow\\bad endpoint",
                clientId = "legacy",
                pkceClientId = Some("pkce-app")
              )
            )
            val unconfiguredLogto =
              Some(LogtoConfig(endpoint = "https://auth.example", clientId = "legacy", pkceClientId = None))

            /** 一次 landing 访问的用户可见读数（`<p>` = 用户实际读到的那一行）。 */
            case class Land(status: Status, text: String)
            def landOnce: IO[Land] =
              st.routes.authCallbackRoutes.orNotFound
                .run(Request[IO](Method.GET, Uri.unsafeFromString("/logged-out")))
                .flatMap(r => r.body.through(fs2.text.utf8.decode).compile.string.map(b => Land(r.status, pTextOf(b))))
            for
              // ① 续登失败支：arm ⇒ 畸形 endpoint ⇒ landing 消费标记 ⇒ beginPkceLogin 抛
              _ <- st.ms.updateConfig(cfg => cfg.copy(enabled = true, logto = goodLogto))
              armed <- st.routes
                .routes(endSessionSwitchRequest)
                .value
                .map(_.getOrElse(fail("end-session route fell through")))
              _ <- st.ms.updateConfig(cfg => cfg.copy(logto = badEndpointLogto))
              land1 <- landOnce
              // ③ 重放支：标记已消费 ⇒ 同一 landing URL 二次访问（静态文案）
              land2 <- landOnce
              // ② 未配置支：重新 arm ⇒ 显式块无 pkceClientId ⇒ beginPkceLogin 返回 None
              _ <- st.routes
                .routes(endSessionSwitchRequest)
                .value
                .map(_.getOrElse(fail("end-session route fell through")))
              _ <- st.ms.updateConfig(cfg => cfg.copy(logto = unconfiguredLogto))
              land3 <- landOnce
            yield
              assertEquals(armed.status, Status.Ok, "arm 腿必须 200（arm 副作用照发生，出口已是 JSON 数据）")
              assertEquals(land1.status, Status.Ok)
              assertEquals(land2.status, Status.Ok)
              assertEquals(land3.status, Status.Ok)
              // 失败支：三段式（原因 + 动作 + 稳定诊断码），且**原文**（路径 / URL / state）不在页面上
              assert(land1.text.startsWith("登录失败："), s"续登失败页必须承载三段式: ${land1.text}")
              assert(land1.text.contains("下一步："), s"续登失败页缺动作句: ${land1.text}")
              assert(
                land1.text.contains(s"诊断码：${CredentialFailure.Unclassified.code}"),
                s"续登失败页必须带稳定诊断码: ${land1.text}"
              )
              assert(!land1.text.contains("Invalid URI"), s"裸异常原文不得进用户可见面: ${land1.text}")
              assert(
                !land1.text.contains("state=") && !land1.text.contains("code_challenge"),
                s"authorize URL / PKCE 参数不得进用户可见面: ${land1.text}"
              )
              List(
                "D1.续登失败支" -> land1.text,
                "D1.重放支" -> land2.text,
                "D1.未配置支" -> land3.text
              ).foreach { case (tag, text) =>
                assertEquals(LogdevTestSupport.violations(text, dataRoot), Nil, s"$tag 三条判据必须全过: $text")
              }
            end for
          }
          .guarantee(IO.blocking(server.stop(0)))
      }
    }
  }

  /** `<p>` 文本抽取（判词探针同形：只判用户实际读到的那一行）。 */
  private def pTextOf(html: String): String =
    val i = html.indexOf("<p>")
    val j = html.indexOf("</p>")
    if i >= 0 && j > i then html.substring(i + 3, j) else s"(NO <p> FOUND) ${html.take(300)}"

end NeblinkLogoutRoutesSpec
