package nebflow.neblink

import cats.effect.IO
import io.circe.Json
import nebflow.core.NebflowLogger

/**
  * Enrollment persistence shared by every login path (device flow poll,
  * AC+PKCE callback, silent re-login): device.json + config update +
  * discovery hot-swap + re-discovery + profile info. Previously private in
  * RestApiRoutes; extracted so the silent re-login hook can run from any
  * client construction site (RestApiRoutes completes with an HTTP context,
  * GatewayMain's startup client does not).
  */
object NeblinkEnrollment:

  private val logger = NebflowLogger.forName("nebflow.neblink.enroll")

  /** Persist the EnrollResponse fields. `logtoRefresh` carries the provider
    * refresh token (AC+PKCE / silent re-login) into device.json; `logtoIdToken`
    * (RP-logout fix, 2026-09-06) the raw id_token for the end-session
    * `id_token_hint`; `reloginHook` is wired into the hot-swapped client so
    * IT can silent-relogin too. Returns the persisted device token.
    *
    * Isolation guard (2026-09-11): an instance running on a redirected data
    * root must not auto-register with the production network — see
    * [[EnrollGuard]]. The refusal is returned on the Left channel (so every
    * caller surfaces it) AND logged; `NEBFLOW_ALLOW_PROD_ENROLL=1` bypasses it.
    * Default data root: unchanged behaviour. */
  def persist(
    ms: NeblinkService,
    resolvedUrl: String,
    json: Json,
    logtoRefresh: Option[String],
    discovery: Option[NeblinkDiscovery],
    gatewayPort: Int,
    reloginHook: Option[IO[Option[String]]],
    logtoIdToken: Option[String] = None
  ): IO[Either[String, String]] =
    EnrollGuard.enrollRefusal(resolvedUrl) match
      case Some(reason) =>
        logger.warn(s"enrollment refused by the isolation guard: $reason").as(Left(reason))
      case None =>
        persistImpl(ms, resolvedUrl, json, logtoRefresh, discovery, gatewayPort, reloginHook, logtoIdToken)

  /** Pre-guard implementation — see [[persist]] for the entry point. */
  private def persistImpl(
    ms: NeblinkService,
    resolvedUrl: String,
    json: Json,
    logtoRefresh: Option[String],
    discovery: Option[NeblinkDiscovery],
    gatewayPort: Int,
    reloginHook: Option[IO[Option[String]]],
    logtoIdToken: Option[String]
  ): IO[Either[String, String]] =
    val deviceToken = json.hcursor.downField("deviceToken").as[String].toOption
    val networkId = json.hcursor.downField("networkId").as[String].toOption.getOrElse("")
    // Extract user info from neblink-server response (if available).
    val avatarUrl = json.hcursor.downField("avatarUrl").as[Option[String]].toOption.flatten
    // neblink-server serializes github_username as "githubUsername" (camelCase)
    val githubLogin = json.hcursor.downField("githubUsername").as[Option[String]].toOption.flatten
    deviceToken match
      case Some(tok) =>
        for
          identity <- ms.identity
          logtoBlock = logtoRefresh.map(rt =>
            LogtoRefresh(rt, System.currentTimeMillis(), logtoIdToken))
          cred = DeviceCredential(resolvedUrl, networkId, identity.deviceId, tok, logtoBlock)
          _ <- DeviceCredential.save(cred)
          newConfig = NeblinkServerConfig(
            url = resolvedUrl,
            networkId = networkId,
            secret = "",
            deviceToken = Some(tok)
          )
          _ <- ms.updateConfig(cfg => cfg.copy(enabled = true, neblinkServer = Some(newConfig)))
          // Hot-swap the client in the discovery service. The fresh client
          // carries the silent re-login hook (Logto refresh on 401) and the
          // device identity (API-level session self-heal, F2 of the
          // 2026-09-10 friend-search batch).
          // F1 (same batch): the discovery clientRef is the AUTHORITATIVE
          // live client — FriendService reads it per call (GatewayMain wiring)
          // and the relay/status consumers are re-pointed here via
          // setRelayClient so every component follows the hot-swap.
          _ <-
            discovery.fold(IO.unit) { d =>
              val fresh = new NeblinkClient(
                newConfig,
                gatewayPort,
                onDeviceTokenRejected = reloginHook,
                identity = Some(ms.identity)
              )
              d.setClient(Some(fresh)) *> IO(ms.setRelayClient(Some(fresh)))
            }
          // 2026-09-11 tunnel 生命周期：logout 会 stop() 隧道，且此前没有任何路径把
          // running 复位 ⇒ 「登出 → 再登录」后 relay 永久缺席到进程重启。装配
          // owner 仍是 GatewayMain（这里只发「确保在跑」信号：单飞 + 幂等）。
          _ <- ms.ensureRelayTunnel
          // Trigger immediate re-discovery.
          _ <- ms.sendSync(SyncCommand.PeerDiscovered)
          // Persist GitHub user info (avatar + login) from server response.
          _ <- ms.updateDeviceInfo(avatarUrl = avatarUrl, githubLogin = githubLogin)
        yield Right(tok)
      case None =>
        IO.pure(Left("Server did not return a device token"))
  end persistImpl

end NeblinkEnrollment
