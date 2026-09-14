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
    * `id_token_hint` AND for the switch-account identity hints. `reloginHook`
    * is wired into the hot-swapped client so IT can silent-relogin too.
    * Returns the persisted device token.
    *
    * The two Logto options are INDEPENDENT since the O5 companion fix
    * (2026-09-11): a post-O5 login carries an id_token and no refresh token, so
    * the device.json `logto` block is written when EITHER is present. Both are
    * MERGED with the stored credential (incoming wins, absent keeps stored) —
    * see the comment in [[persistImpl]] for why.
    *
    * Isolation guard (2026-09-11): an instance running on a redirected data
    * root must not auto-register with the production network — see
    * [[EnrollGuard]]. The refusal is returned on the Left channel (so every
    * caller surfaces it) AND logged; `NEBFLOW_ALLOW_PROD_ENROLL=1` bypasses it.
    * Default data root: unchanged behaviour.
    *
    * Explicit-user-action release (2026-09-14 作者裁定「案 C」):
    * `explicitUserAction = true` passes the guard AND lifts the relay tunnel's
    * post-kick park (「须用户显式再登录」), because that is the one act a kicked
    * instance is allowed to come back with. Only the PKCE loopback callback
    * sets it, and only on a matched, single-use state — every automatic path
    * (boot client, silent re-login, device-flow poll, tests) defaults to
    * `false` and is gated exactly as before. */
  def persist(
    ms: NeblinkService,
    resolvedUrl: String,
    json: Json,
    logtoRefresh: Option[String],
    discovery: Option[NeblinkDiscovery],
    gatewayPort: Int,
    reloginHook: Option[IO[Option[String]]],
    logtoIdToken: Option[String] = None,
    explicitUserAction: Boolean = false
  ): IO[Either[String, String]] =
    EnrollGuard.enrollRefusal(resolvedUrl, explicitUserAction) match
      case Some(reason) =>
        logger.warn(s"enrollment refused by the isolation guard: $reason").as(Left(reason))
      case None =>
        persistImpl(
          ms,
          resolvedUrl,
          json,
          logtoRefresh,
          discovery,
          gatewayPort,
          reloginHook,
          logtoIdToken,
          explicitUserAction
        )

  /** Pre-guard implementation — see [[persist]] for the entry point. */
  private def persistImpl(
    ms: NeblinkService,
    resolvedUrl: String,
    json: Json,
    logtoRefresh: Option[String],
    discovery: Option[NeblinkDiscovery],
    gatewayPort: Int,
    reloginHook: Option[IO[Option[String]]],
    logtoIdToken: Option[String],
    explicitUserAction: Boolean
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
          // O5 companion fix (2026-09-11) — the `logto` block is no longer
          // refresh-token-gated AND is no longer rebuilt blind:
          //  - a NEW login (post-O5) carries an id_token but NO refresh token
          //    (`LogtoAuthCode.parseTokenResponse`; authorize no longer asks
          //    for offline_access). The identity half — status `email` /
          //    `displayName` + the end-session `id_token_hint` — must still be
          //    persisted, so the block is written when EITHER half exists;
          //  - a login that carries NEITHER half (self-hosted device-flow poll,
          //    pairing-code path: `completeDeviceEnrollment` defaults) must not
          //    destroy what is already stored. `DeviceCredential.save` rewrites
          //    the whole file (`os.write.over`), so rebuilding the credential
          //    from the incoming options alone would wipe the stored identity
          //    (and the pre-O5 refresh fallback) on every such login.
          // Merge rule: an incoming value always wins; an absent incoming value
          // KEEPS the stored one. Scoped to the same `serverUrl` — a credential
          // belonging to another server is not this device's identity.
          stored <- DeviceCredential.load
          storedBlock = stored.filter(_.serverUrl == resolvedUrl).flatMap(_.logto)
          logtoBlock = LogtoRefresh.of(
            logtoRefresh.filter(_.nonEmpty).orElse(storedBlock.map(_.refreshToken)),
            logtoIdToken.filter(_.nonEmpty).orElse(storedBlock.flatMap(_.idToken))
          )
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
                identity = Some(ms.identity),
                // 2026-09-14（踢旧批 r2）：自动登录的停摆门。被服务端 kick 后本客户端
                // 不发任何登录/会话交换/重注册请求（唯一解除口 = 显式用户登录，
                // 见下面对 `resumeAfterUserLogin` 的调用）。
                autoLoginParked = IO(ms.kickParked)
              )
              d.setClient(Some(fresh)) *> IO(ms.setRelayClient(Some(fresh)))
            }
          // 2026-09-11 tunnel 生命周期：logout 会 stop() 隧道，且此前没有任何路径把
          // running 复位 ⇒ 「登出 → 再登录」后 relay 永久缺席到进程重启。装配
          // owner 仍是 GatewayMain（这里只发「确保在跑」信号：单飞 + 幂等）。
          // 2026-09-14（踢旧批 ②）：被服务端 `disconnect` 帧踢下线后，隧道进入停摆
          // 态（禁自动重连/重注册，防空转互踢）；**只有用户显式再登录**解除它——
          // 就在这里，因为这是唯一被证明由用户发起的入网路径。自动路径
          // （silent re-login / device-flow poll）persist 时不带该标记 ⇒ 不解停摆。
          _ <-
            if explicitUserAction then ms.relayTunnelOpt.fold(IO.unit)(_.resumeAfterUserLogin())
            else IO.unit
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
