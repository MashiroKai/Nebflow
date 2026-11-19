package nebflow.neblink.identity

import nebflow.neblink.core.*
import nebflow.neblink.relay.*
import nebflow.neblink.social.*

import nebflow.neblink.logto.*
import cats.effect.IO
import io.circe.Json
import nebflow.core.root.{AccountProfile, AccountRoot, ActiveAccount, RootLadder, RootLayer, RootLayout}
import nebflow.shared.{NebflowLogger, PathUtil}

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

  /**
   * 账号归属闸（logout-login-recovery 批 2026-11-08 重定形；前形 = 2bc597e62 的
   * 「非账号档一律 fail-closed」，那会把你登出后的平铺机器永远锁在登录门外）。
   *
   * 语义表（`incomingSub` 来自本次登录 id_token 的 `sub` 声明）：
   *  - 账号档 + 同账号 ⇒ 放行（同一账号的凭据落回自己的账号根）；
   *  - 账号档 + 异账号 ⇒ `account-switch-requires-logout`（跨账号 enroll 硬拒，不变）；
   *  - 平铺档 ⇒ **放行**。平铺根是兼容红线档（契约 §0 总则 1：无 layout 标记的根
   *    一切字节不变、dataRoot == machineRoot）。本批起平铺登录 = **只落凭据**
   *    （device.json + config.json，即阶梯前多年验证过的稳态），根层零写
   *    （[[writeActiveAccountPointer]] 的非账号档跳过腿与本闸同批同源）—— 无迁移、
   *    无指针、无半迁移态，也就**没有**「需要重启才生效」的欠账：登出 → 登录 →
   *    重启整个闭环在平铺稳态上成立。旧拒绝串 `account-root-restart-required` 随之
   *    退役（它承诺的重启路径在平铺根上根本不存在——重启后仍是平铺档）。
   *  - 未登录层 ⇒ `enroll-refused-no-account-root`（fail-closed 不变）：该层不铸设备
   *    身份（NeblinkModel 硬闸，契约 §6.2）且落在该层的凭据无法跨根携带 —— 与其让
   *    注册腿在身份铸造处炸成未分类错误，不如在闸上给稳定码 + 可行动文案。
   *  - 显式登录拿不到 `sub` ⇒ 分层：**账号档 / 未登录层**拒 `account-identity-missing`
   *    （无主凭据落进有主根 = 串号面，fail-closed 不变）；**平铺档放行** —— 配对码腿
   *    （门户 E6：`POST /api/neblink/enroll`，无 Logto 令牌面）正是这个形态，平铺根
   *    没有账号归属可串，凭据只落本机平铺根（指针腿对 None-sub 本就不写）。
   *    修前该分支不分层一刀切，把配对码入网连同登出重登一起锁死（2bc597e62 回归）。
   *  - 自动路径（`explicit = false`）无 `sub` ⇒ 放行（静默续登/设备码遗产路径，不变）。
   */
  private[nebflow] def accountCutoverRefusal(layer: RootLayer, incomingSub: Option[String], explicit: Boolean): Option[String] =
    incomingSub.flatMap { sub =>
      layer match
        case RootLayer.Account(_, owner, _) if owner == sub => None
        case RootLayer.Account(_, _, _) => Some("account-switch-requires-logout: this engine serves a different account")
        case RootLayer.Flat(_, _) => None
        case RootLayer.Unauthenticated(_, _) =>
          Some("enroll-refused-no-account-root: the unauthenticated layer has no account root; device registration is not available in this state")
    }.orElse {
      // 平铺档：无身份的显式入网（配对码腿，无 Logto 令牌面）放行 —— 平铺根没有账号
      // 归属可串，凭据只落本机平铺根。有主根（账号档 / 未登录层）fail-closed：
      // 无主凭据落进有主根 = 串号面。
      val ownedLayer = layer match
        case RootLayer.Flat(_, _) => false
        case _                    => true
      Option.when(explicit && incomingSub.isEmpty && ownedLayer)(
        "account-identity-missing: cannot safely establish account root"
      )
    }

  /**
   * Persist the EnrollResponse fields. `logtoRefresh` carries the provider
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
   * `false` and is gated exactly as before.
   */
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
    val incomingSub = logtoIdToken.flatMap(t =>
      LogtoAuthCode.decodeIdTokenClaims(t, Seq("sub")).get("sub").map(_.trim).filter(_.nonEmpty))
    val cutoverRefusal = accountCutoverRefusal(RootLadder.current, incomingSub, explicitUserAction)
    cutoverRefusal.orElse(EnrollGuard.enrollRefusal(resolvedUrl, explicitUserAction)) match
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

  /**
   * Pre-guard implementation — see [[persist]] for the entry point.
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
   *    [[liftParkOnProvenCredential]]）。显式路径逐字不变。
   */
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
          out <- NeblinkSingleFlight
            .serialize(
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
            // 🔴 凭据落盘失败（缺陷 A / 上游 §8.2 第 3 项）：收敛进 `Left` 通道，
            // **不**抛裸异常 —— 与隔离护栏拒绝同通道、同文案层。原文（路径 / 异常类名）
            // 已由 `DeviceCredentialStore` 写进 WARN（带分类码），`Left` 只承载三段式
            // 用户可见文案（§8.3），因此登录框再也不会出现「只有路径」的字面。
            .handleErrorWith {
              case e: CredentialDiagnostics.CredentialStoreError => IO.pure(Left(e.diagnostic.message))
              case other => IO.raiseError(other)
            }
        yield out
      case None =>
        // 服务端没发回凭据：同样走分类文案（老串 `Server did not return a device token`
        // 的语义由 `server-no-device-token` 这个稳定码承接，不再作为自由串流转）。
        IO.pure(
          Left(CredentialDiagnostics.diagnosticOf(CredentialFailure.ServerNoDeviceToken).message)
        )

    end match

  end persistImpl

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
      //
      // 🔴 缺陷 A（2026-09-18）：这一读**永不抛** —— 读不开/解码坏 ⇒
      // `DeviceCredentialStore` 先把坏件改名留档 + 打一条带分类码的 WARN，再按
      // 「无凭据」返回（自愈已定案：作者 2026-09-18 五条之①）。所以「换号时凭据件
      // 坏掉」不再是一条不可完成的登录：本次登录按空盘重建，写不进去才失败（那时
      // 由 `save` 的分类承载原因）。
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
      // 三层数据根批（b1-ladder；契约 design-api.md §4.1 (a)）：登录成功 ⇒ 机器层写
      // active-account 指针 + 账号根 + profile.json。平铺机器（阶梯判 flat）先
      // `RootLayout.migrate`（复制 + 逐条 sha256 记账，原件不动）再写指针；迁移流程失败 ⇒
      // `RootLayout.rollback`（旧根字节不变 + 指针清空，下次启动回到 flat）。
      // 凭据已落地后才走这一步（凭据落地在前、指针在后 —— 契约 §4.1 的次序）。
      _ <- writeActiveAccountPointer(logtoIdToken, storedBlock)
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
          autoLoginParked = IO(ms.kickParked),
          // 🔴 relay-honest 批（2026-10-20）A 修：凭据腿收口钩（与 GatewayMain boot
          // 客户端同款）。本客户端后续的每次会话铸造（心跳恢复 / API 自愈的
          // `/api/device/session` 成功）都无条件发 ensure 信号——隧道 stop() 之后
          // 不再依赖「恰好又走了一次完整 enroll」才复活。
          onSessionMinted = Some(ms.ensureRelayTunnel)
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
      //
      // 🔴 relay-honest 批（2026-10-20）A 修：下一行的 `ensureRelayTunnel` 是三条
      // 登录腿的**统一收口**（enrollment / silent-relogin 都经过本咽喉；凭据腿则由
      // `NeblinkClient.onSessionMinted` 钩子接到同一处 `NeblinkService.ensureRelayTunnel`）
      // —— 任何成功的设备登录/会话铸造之后幂等 ensure-start，各腿不再自行判断。
      _ <-
        if explicitUserAction then ms.relayTunnelOpt.fold(IO.unit)(_.resumeAfterUserLogin())
        else liftParkOnProvenCredential(ms, hotSwapped.map(_._2), identity)
      _ <- ms.ensureRelayTunnel
      // Trigger immediate re-discovery.
      _ <- ms.sendSync(SyncCommand.PeerDiscovered)
      // Persist GitHub user info (avatar + login) from server response.
      _ <- ms.updateDeviceInfo(avatarUrl = avatarUrl, githubLogin = githubLogin)
    yield Right(tok)

  // ── 三层数据根 · 登录腿建层（b1-ladder，契约 design-api.md §4.1 (a)）───────────
  //
  // 登录成功 ⇒ 机器层写 active-account 指针 + 账号根 + profile.json。**触发点 = 显式动作
  // （登录），绝不在 boot / RootLadder.resolve 里自动跑** —— 兼容红线：无 layout.json 的
  // 平铺根升级后第一次启动必须仍是 flat + 零写盘；启动序（Main）只负责把登录腿写下的
  // 迁移产出（layout.json + 指针）经阶梯解析成账号档。
  // 失败纪律（b1-ladder 派单）：迁移后置步骤失败 ⇒ `RootLayout.rollback`（按 manifest 校验
  // 后删副本 + `clearPointer` + 删 layout.json ⇒ 旧根字节不变 + 指针清空，下次启动回到 flat；
  // RootLayout.scala:185 的 clearPointer 回滚用法）。

  /**
   * 登录腿的根布局建立（纯阻塞编排，spec 直测面 —— `StartupLadderSpec`；IO 胶水见
   * [[writeActiveAccountPointer]]）。分岔（契约 §4.1 + LegacyRootMigrationSpec V4 次序）：
   *  - 平铺档（`RootLayer.Flat`，无 layout 标记的老根 / 悬档指针）：
   *    1. 账号根先就位（含 profile.json，**指针不写**）—— migrate 的 firstAccountRoot 由此
   *       认得目标 ⇒ 平铺内容**一次性归入该账号目录**（pendingClaim=false；若先迁移后建根，
   *       migrate 找不到任何账号 ⇒ 内容落 pending-claim 待认领区而账号根空着，与 V4
   *       「一次性归入首个账号目录」相悖）；
   *    2. `RootLayout.migrate`（复制 + 逐条 sha256 记账，原件不动）；
   *    3. 写指针（先迁移再写指针）；**迁移流程的任何后置失败 ⇒ `RootLayout.rollback` 后
   *       原样浮出异常**（绝不半迁移：要么整体就位、要么回到 flat）。
   *  - 其余档（账号档二次登录 / 未登录档登录 ⇒ 写指针晋升）：直接就位账号根 + 写指针。
   * 返回本次登录就位的账号根。`accountSub` 目录名经 `AccountRoot.dirNameOf` 映射，原文
   * 只留 `profile.json` / `active-account`。`writePointer` 步可注入（spec 直测「迁移成功后
   * 写指针失败 ⇒ 回滚」腿）；生产默认 = `AccountRoot.writePointer`。
   */
  def establishAccountRootAfterSignIn(
    machineRoot: os.Path,
    accountSub: String,
    email: String,
    switchedAt: String,
    writePointer: (os.Path, ActiveAccount) => Unit = AccountRoot.writePointer
  ): os.Path =
    val acct = ActiveAccount(accountSub, email, switchedAt)
    RootLadder.resolve(machineRoot) match
      case RootLayer.Flat(mr, _) =>
        // 平铺机器首次登录（契约 §4.1）：先建账号根（指针不写）→ 迁移（复制记账，原件不动）
        // → 写指针。
        establishAccountDirAndProfile(mr, acct)
        val report = RootLayout.migrate(mr)
        try writePointer(mr, acct)
        catch
          case e: Throwable =>
            // 迁移流程失败 ⇒ 回滚：按账校验后删副本 + clearPointer + 删 layout.json ⇒
            // 旧根字节不变（migrate 本就只复制不动源）、指针清空，下次进程起来回到 flat。
            RootLayout.rollback(report)
            throw e
        AccountRoot.accountRoot(mr, acct.accountSub)
      case _ =>
        // 账号档（二次登录 / 换号）/ 未登录档（首次登录晋升）：无平铺数据可迁，直接就位
        establishPointerAndProfile(machineRoot, acct, writePointer)

  /** 账号根就位 + profile（幂等：同 sub 的既有档案原样保留）。**不写 layout 与指针**。 */
  private def establishAccountDirAndProfile(machineRoot: os.Path, acct: ActiveAccount): os.Path =
    val dir = AccountRoot.ensureAccountDir(machineRoot, acct.accountSub)
    val existing = AccountRoot.readProfile(dir)
    if !existing.exists(_.accountSub == acct.accountSub) then
      AccountRoot.writeProfile(
        dir,
        AccountProfile(acct.accountSub, acct.email, acct.switchedAt, None)
      )
    dir

  /** 就位 + layout 标记 + 指针（账号档二次登录 / 未登录档晋升的完整步）。 */
  private def establishPointerAndProfile(
    machineRoot: os.Path,
    acct: ActiveAccount,
    writePointer: (os.Path, ActiveAccount) => Unit
  ): os.Path =
    val dir = establishAccountDirAndProfile(machineRoot, acct)
    AccountRoot.writeLayoutMarker(machineRoot)
    writePointer(machineRoot, acct)
    dir

  /**
   * 指针写的 IO 胶水（契约 §4.1 (a) 的生产入口）。账号身份来源（优先级）：
   *   1. 本次登录的 id_token 的 `sub` / `email` 声明（`LogtoAuthCode.decodeIdTokenClaims`，
   *      与 OwnerIdentity 同一解码单点）；
   *   2. 已存凭据里的 id_token（merge 后的 `logtoBlock`）。
   * 两者都拿不到 sub ⇒ 不写指针（没有任何可指的账号），打 INFO 留痕。
   * 拿到 sub 但进程**不是账号档**（平铺根 / 未登录层）⇒ 同样不写（logout-login-recovery
   * 批：平铺登录 = 只落凭据，根层零写；见下方实现内注）。指针落盘失败向调用方报告，
   * 不伪报登录成功。
   */
  private def writeActiveAccountPointer(
    incomingIdToken: Option[String],
    storedBlock: Option[LogtoRefresh]
  ): IO[Unit] =
    IO.blocking {
      val sub = incomingIdToken
        .flatMap(t => LogtoAuthCode.decodeIdTokenClaims(t, Seq("sub")).get("sub").map(_.trim).filter(_.nonEmpty))
        .orElse(
          storedBlock.flatMap(_.idToken).flatMap(t =>
            LogtoAuthCode.decodeIdTokenClaims(t, Seq("sub")).get("sub").map(_.trim).filter(_.nonEmpty)
          )
        )
      sub match
        case None =>
          logger.infoSync("[root] no account sub available — active-account pointer not written")
        case Some(accountSub) =>
          // 平铺兼容红线（logout-login-recovery 批 2026-11-08）：登录腿只在**账号档**进程上
          // 写根层（指针 / 布局 / 迁移）。平铺根（以及防御性的未登录层——正常到不了这里，
          // 账号归属闸已拒）只落凭据：device.json / config.json 在本步之前已写完，这里
          // **零根层写**。为什么不做 migrate+指针（尽管 [[establishAccountRootAfterSignIn]]
          // 的平铺分支已实现且 spec 覆盖）：进程内禁热切（契约 §0 总则 2）⇒ 迁移后本进程
          // 仍以平铺根为有效根继续写盘，制造「副本已建、原件仍在长」的半迁移态——这正是
          // 该函数平铺分支今天**停用**的原因。根层晋升（migrate + 指针 + 重启生效）由账号
          // 根切换批整体接线；本批的平铺稳态 = 登录前后字节不变、重启后照常服务。
          if RootLadder.current.name == RootLayer.AccountName then
            val email = incomingIdToken
              .flatMap(t => LogtoAuthCode.decodeIdTokenClaims(t, Seq("email")).get("email"))
              .getOrElse("")
            val now = java.time.format.DateTimeFormatter
              .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
              .withZone(java.time.ZoneId.of("UTC"))
              .format(java.time.Instant.now())
            // 机器层根与 Main 启动序同一解析链（PathUtil.machineRoot），不引入第二套 home 解析
            val dir = establishAccountRootAfterSignIn(PathUtil.machineRoot, accountSub, email, now)
            logger.infoSync(
              s"[root] active-account pointer -> '$accountSub' ($dir); takes effect on next process start"
            )
          else
            logger.infoSync(
              s"[root] root layer=${RootLadder.current.name}: credential-only login — no root-layer " +
                "writes (account root cutover is a separate batch)"
            )
    }

  /**
   * 停摆门的**证据式**解除（kaiauth 修法批 ①配套，2026-09-16）。
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
   * `discovery = None` 的装配面（含部分测试）。
   */
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
