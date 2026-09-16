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

  /** Pre-guard implementation — see [[persist]] for the entry point.
    *
    * kaiauth 修法批（2026-09-16 作者「治本」已批）在本咽喉上的三笔改动：
    *  - **③ enroll 单飞**：整个「凭据落地 + config 落地 + hot-swap」临界区按
    *    `(deviceId, networkId)` 串行化（[[NeblinkSingleFlight]]，**进程级** ⇒ hot-swap
    *    不重置它）。键与服务端 `enroll_device` 的 `INSERT OR REPLACE` 行维度
    *    （跨仓只读 `neblink-server/src/store.rs:2951-2969`）**逐字同源**；并发输家
    *    **复用**赢家的结果 ⇒ 并发 enroll 不再互相作废、「enroll 成功」与「发送值有效」
    *    不再分叉。
    *  - **② 单源化**：`deviceToken` **不再**落 `neblink/device.json`（见
    *    [[DeviceCredential]] 的 DEPRECATED 注记）；**唯一权威写面 = 出站点所用的那份
    *    `config.json`**（下方 `ms.updateConfig(newConfig)` 一笔，出站点逐行不变）。
    *  - **①配套**：自动路径（`explicitUserAction = false`）下，若本进程已停摆，用新铸
    *    凭据做**一次**证明性交换；**成功才**解除停摆，失败保持（见
    *    [[liftParkOnProvenCredential]]）。显式路径逐字不变。 */
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
          out <- NeblinkSingleFlight.serialize(
            NeblinkSingleFlight.key("enroll", identity.deviceId, networkId)
          ) {
            persistCredential(
              ms,
              resolvedUrl,
              networkId,
              tok,
              avatarUrl,
              githubLogin,
              logtoRefresh,
              logtoIdToken,
              discovery,
              gatewayPort,
              reloginHook,
              explicitUserAction,
              identity
            )
          }
        yield out
      case None =>
        IO.pure(Left("Server did not return a device token"))

  /** 单飞临界区本体（`(deviceId, networkId)` 已被 [[persistImpl]] 的闸包住）。 */
  private def persistCredential(
    ms: NeblinkService,
    resolvedUrl: String,
    networkId: String,
    tok: String,
    avatarUrl: Option[String],
    githubLogin: Option[String],
    logtoRefresh: Option[String],
    logtoIdToken: Option[String],
    discovery: Option[NeblinkDiscovery],
    gatewayPort: Int,
    reloginHook: Option[IO[Option[String]]],
    explicitUserAction: Boolean,
    identity: DeviceIdentity
  ): IO[Either[String, String]] =
    for
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
      // 🔴 单源化（②）：`tok` 仍作为**入参**传入（出站点那一份 `config.json` 用它），
      // 但 `DeviceCredential` 的编码面**已停写** `deviceToken`（见其 DEPRECATED
      // 注记）⇒ 本文件（`neblink/device.json`）只留 `logto` 块 + 身份面字段。
      // 零删除纪律：字段既不删、旧文件也照旧可解码（读侧只忽略）。
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
      hotSwapped <- IO(discovery.map { d =>
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
        d -> fresh
      })
      _ <- hotSwapped.fold(IO.unit) { case (d, fresh) =>
        d.setClient(Some(fresh)) *> IO(ms.setRelayClient(Some(fresh)))
      }
      // 2026-09-11 tunnel 生命周期：logout 会 stop() 隧道，且此前没有任何路径把
      // running 复位 ⇒ 「登出 → 再登录」后 relay 永久缺席到进程重启。装配
      // owner 仍是 GatewayMain（这里只发「确保在跑」信号：单飞 + 幂等）。
      // 2026-09-14（踢旧批 ②）：被服务端 `disconnect` 帧踢下线后，隧道进入停摆
      // 态（禁自动重连/重注册，防空转互踢）；**只有用户显式再登录**解除它——
      // 就在这里，因为这是唯一被证明由用户发起的入网路径。自动路径
      // （silent re-login / device-flow poll）persist 时不带该标记 ⇒ 不解停摆。
      //
      // 🔴 2026-09-16（kaiauth 修法批 ①配套）：`else` 支不再是空操作 —— 自动路径
      // 改为**证据式**解除（先证明后解锁，失败保持）。显式路径那一支**逐字不变**
      // （案 C 语义：显式登录是无条件解除口）。
      _ <- if explicitUserAction then ms.relayTunnelOpt.fold(IO.unit)(_.resumeAfterUserLogin())
           else liftParkOnProvenCredential(ms, hotSwapped.map(_._2), identity)
      _ <- ms.ensureRelayTunnel
      // Trigger immediate re-discovery.
      _ <- ms.sendSync(SyncCommand.PeerDiscovered)
      // Persist GitHub user info (avatar + login) from server response.
      _ <- ms.updateDeviceInfo(avatarUrl = avatarUrl, githubLogin = githubLogin)
    yield Right(tok)

  /** 停摆门的**证据式**解除（kaiauth 修法批 ①配套，2026-09-16）。
    *
    * 判据（作者给的口径「新凭据已铸成且经一次成功交换证明有效」，两条**都**要）：
    *  - 已铸成 = 本节前面刚写完整份新凭据（`config.json` 的 deviceToken 是**本次**
    *    的 `tok`，出站点那一份 = 唯一权威来源）；
    *  - 已证明 = 用新 client 对新凭据做**一次**会话交换且成功
    *    （[[NeblinkClient.proveSessionExchange]]）。
    *
    * 🔴 **失败绝不解锁**（防风暴的回归钉）：证明失败 ⇒ 打 WARN、停摆门**保持**、
    * 零重试、零额外 enroll。未停摆 ⇒ 直接返回（**零额外请求**：无门可解时本步
    * 在 wire 面上完全不可观测）。
    *
    * 边界（诚实登记）：本步需要**已 hot-swap 的新 client**（`discovery` 为 None 的
    * 装配面没有新 client ⇒ 无凭据可证 ⇒ 保守保持停摆并 WARN）。生产装配
    * （`GatewayMain` / `RestApiRoutes`）都传 `discovery`，故该边界只影响
    * `discovery = None` 的装配面（含部分测试）。 */
  private def liftParkOnProvenCredential(
    ms: NeblinkService,
    fresh: Option[NeblinkClient],
    identity: DeviceIdentity
  ): IO[Unit] =
    IO(ms.kickParked).flatMap {
      case false => IO.unit
      case true =>
        fresh match
          case None =>
            logger.warn(
              "kick park kept: the enrollment landed with no hot-swapped client, so the fresh " +
                "credential could not be proven by a session exchange"
            )
          case Some(client) =>
            client
              .proveSessionExchange(identity.deviceId, identity.deviceName, identity.platform)
              .flatMap {
                case true =>
                  logger.info(
                    "fresh device credential proven by a successful session exchange — lifting the kick park"
                  ) *> ms.liftKickParkAfterProvenCredential
                case false =>
                  logger.warn(
                    "kick park kept: the fresh device credential was minted but its session " +
                      "exchange did NOT succeed (no positive evidence ⇒ no unlock)"
                  )
              }
    }

end NeblinkEnrollment
