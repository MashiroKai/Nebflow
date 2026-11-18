package nebflow.gateway

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.{Branding, NebflowLogger}

/**
 * 控制台单实例 · 接管注册表（2026-10-27 批，设计稿
 * `reports/20261027_231456_console-single-instance/DESIGN.md`）。
 *
 * 目标（作者令）：同一时刻只有一个可信实例在查看/操作控制台——第二台电脑/浏览器
 * 进入时，第一台弹「控制台已在其他位置打开」（【退出】【重新接管】两按钮，重新
 * 接管即反向踢，互斥权最后写入者胜）。
 *
 * 数据可见性（2026-10-29 作者令四修修订，原批「数据透明照旧」作废）：被顶出
 * （superseded）会话的连接**断喂对话内容帧**——[[ConsoleTakeover.gateFrame]] 只放行
 * 接管协议帧（隐私面：被顶出的标签页不得继续看到最新对话）。#929 多设备透明对
 * **主动控制权**的独占语义不变。
 *
 * == 身份与粒度（误报根修批 2026-11-02；在带声明批 2026-11-03） ==
 * 实例 = **浏览器客户端**（`clientId`，`deviceIdentity.js` 的 localStorage 稳定 id），
 * 会话仅作退路。身份到达有**两条腿**：
 *   ① 握手 query：`/ws?clientId=`（直连脸；**不可靠**——门户/隧道腿与部分中转代理
 *      会重写请求目标，query 不保证送到引擎，`stand.log` 2026-11-03 10:07–10:30 连续
 *      7 条连接全部落会话退路即为实证）；
 *   ② 在带声明（本批新增）：ws open 后客户端立刻上行
 *      `{"type":"takeoverIdentify","clientId":"…"}`（[[ConsoleTakeover.identifyFrame]]，
 *      收流管拦截 → [[ConsoleTakeover.onIdentify]]）——**帧体走数据面**，任何把该连接
 *      的应用层帧原样送到引擎的交付路径都无法丢掉它 ⇒ 声明必达。两腿逐字同核
 *      （同一 holder 口径 + 同一 [[ConsoleTakeover.settle]]），声明后到 = 连接账与
 *      所有权**迁移**（[[ConsoleTakeover.migrateConn]]），不是第二实例。
 * 旧口径「实例 = 门户会话 `sessionId`」是把**易变的凭据**当身份，实测误报链（日志
 * stand.2026-11-02.0 12:29–12:53 连续 19 次 `took over from … pushed to 0 conn(s)`）：
 *   手机/桌面会话过期或被保险丝截断（`GatewaySessionStore` idle 表）⇒ ws.js
 *   [[remintHostSessionAndReload]] 静默重座 ⇒ `/api/portal/console-ticket` ⇒
 *   `/` 兑换**新 sessionId** ⇒ 同一台设备以**新会话**连回 ⇒ 旧口径视作「另一个
 *   实例」⇒ 向自己推 `takeover` 帧 ⇒ 「没人挤我，也显示被挤」。
 * 现口径：`holder = "c:" + clientId`（有 clientId）/ `holder = sessionId`（无，退路
 * ——老缓存页）。同一浏览器刷新/多标签/后台恢复/静默重座**恒同 holder** ⇒ 静默
 * 续位；浏览器会话被清空（localStorage 换 id）才换 holder。
 * 安全边界声明：`clientId` 是**UX 级身份键**，不是鉴权凭据（鉴权仍由 HttpOnly
 * 会话 cookie 决定）——伪造 clientId 最坏只是「不弹提示」，拿不到任何数据面。
 *
 * == 同端判据批（2026-11-07 作者报障「手机这一个端反复弹控制台已在其他位置打开」） ==
 * 报障根因（`stand.log` 2026-11-07 10:36–10:53 实证，见批文档）：`clientId` 是
 * **per-origin / per-浏览器进程** 的 `crypto.randomUUID()`（`deviceIdentity.js`
 * [[deviceClientId]] 存 localStorage），于是「同一台物理设备」在下面三种情形下
 * 会得到**新 clientId**，旧口径 `verdict` 把它当作「可证不同的第二个端」⇒ 弹窗：
 *   ① 每一次 Playwright/headless 自动化运行（临时 profile ⇒ 空 localStorage ⇒
 *      新 UUID）——当日 24 条 `origin=local-ticket ua=…HeadlessChrome` 控制台会话，
 *      每一条都顶掉作者手机端的控制权；
 *   ② 同一台手机走另一条入口（`link.mystand.space` 与直连 `:6138` 是**不同
 *      origin** ⇒ 不同 localStorage ⇒ 不同 clientId）；
 *   ③ 浏览器清理存储 / 换浏览器外壳。
 * 以及一个「没有可见活动也弹」的缺口：翻位推送不看被顶端**是否真的还在看**。
 *
 * 本批三条判据（[[ConsoleTakeover.verdict]] / [[ConsoleTakeover.settle]] 单源）：
 *   A. **同端判据**：实例身份除 clientId 外再带一枚**端指纹**
 *      ([[ConsoleTakeover.endprintOf]]，= 握手 UA 规范化后的短哈希)。双方指纹
 *      在场且相等 ⇒ `Verdict.SameEnd` ⇒ **静默让位/并账**：owner 翻到新键、
 *      零出帧、零弹窗（"同 ua + 同 device" 的重连必须静默 —— 作者令）。
 *   B. **可见活动闸**：向被顶端推 `takeover` 只推给**此刻真有可见活动**
 *      （存在活连接且 [[ConsoleTakeover.ActivityTtlMs]] 内有过入站帧）的实例；
 *      已死/僵尸/静默的旧端 ⇒ 静默让位（它下次回连按 pastOwners 走同一条同端
 *      判据，不会平白弹窗）。
 *   C. **自动化端不夺权**：声明了 `navigator.webdriver`（Playwright/Puppeteer）
 *      或 UA 带 `Headless` 的实例落**观察位**（spectator）：不翻 owner、不收帧、
 *      内容闸对它放行（测试要看得见内容），只有无人持有或它自己显式 retake 时
 *      才拿座位。开关 `CONSOLE_AUTOMATION_SPECTATOR=off` 回退旧行为。
 * 判据只增不减：指纹缺席（老缓存页/spec 构造面）⇒ 退回 clientId 口径，行为逐字
 * 同上一批（零漂移）。
 *
 * == claim 规则（互斥权写入点只有两个） ==
 *   1. fresh claim：owner==None ⇒ 即位；owner==他会话 且 本实例 ∉ pastOwners ⇒
 *      接管（owner 翻位 + 向旧主全部活跃连接推 `takeover` 帧，尽力送达）。
 *      但**只有当双方身份都可证且不同**才判冲突（见 [[ConsoleTakeover.verdict]]）：
 *      任一方 clientId 不明（老缓存页/升级窗）⇒ 宁可**不提示不踢**（假阴性可接受，
 *      假阳性不可接受——误报会让人以为账号被盗）。
 *   2. Canvas 写者席位另行独占（[[ConsoleTakeover.claimCanvas]]，2026-11-18 作者令）：
 *      **单写者、非抢占**——无写者才授予（单实例正常写），已有写者时第二个窗口
 *      的申请被拒（只读可看），零卡片零提示；拒写走真实「保存失败」文案。
 * 旧主回连（owner==他会话 且 本实例 ∈ pastOwners）**不自动反抢**：回连是机器自动
 * 行为（休眠唤醒/抖动重连），不携带用户意图；若回连即反抢，则 B 操作中 A 的后台
 * 标签醒来即可隐身夺权——正是要防的误踢。回连方落被踢面（本连接收 `takeover`
 * 帧），一键 retake 可预期。
 *
 * == 陈旧账清理（误报根修批第二条：不靠长 TTL 兜底） ==
 * 实例账在**每次连接入账时**做机会式清扫（[[ConsoleTakeover.sweepStale]]）：
 * 「零活连接 且 超过 `StaleTtl`（缺省 10 分钟）无入账」⇒ 整条实例摘账；若它是
 * 当前 owner，**owner 一并清空**（下次连接走 fresh claim = 零提示，而不是向一个
 * 已不存在的持有者「接管」）。对比：会话 store 的 idle 窗口是 7d、绝对窗口 30d
 * （`GatewaySessionStore.IdleTimeoutSeconds`）——用会话寿命兜底陈旧账，正是旧
 * 口径误报的温床。
 *
 * == 边界（与 #960 WsZombieGuard 的分工） ==
 * 掉线 ≠ 让位：`onDisconnect` 只摘连接账（判死回收/关闭/异常四途同归），owner
 * 永不因断线转移、注册表在断线路径上**零出帧**——僵尸回收不触发 takeover 提示，
 * 抖动误踢结构上不可表达。takeover 帧的唯一发射点 = fresh claim 翻位与显式 retake。
 *
 * == 总闸（灰度/回退） ==
 * env `CONSOLE_SINGLE_INSTANCE`（[[Branding.env]] 双前缀）：`off/0/false/no`
 * （大小写不敏感）= 关闭——`onConnect`/`onDisconnect`/`handleRetake` 全 no-op 且
 * 路由侧不拦截 retake 帧（未知帧走既有分派回退）＝现行为零漂移；缺省/其余值 = 启用。
 *
 * 帧（协议 3 型，shape 由 spec 钉）：
 *   服务端→旧实例 `{"type":"takeover","at":ms}`；客户端→服务端
 *   `{"type":"takeoverRetake"}`；服务端→接管者 `{"type":"takeoverResult","ok":b}`。
 */
final class ConsoleTakeover(
  /** 装配缝：向指定连接推帧（生产 = `wsHub.sendTo`；spec 注入假发送）。实现需自带容错（sendTo 对亡连接静默）。 */
  sendTo: (String, Json) => IO[Unit],
  logger: NebflowLogger,
  /** 总闸读数（进程级常量，boot 一次）：false = 全 no-op。路由侧读此值决定是否拦截 retake 帧。 */
  val enabled: Boolean = ConsoleTakeover.defaultEnabled,
  /** 实例账陈旧窗（零活连接 + 超此窗无入账 ⇒ 摘账；owner 随之清空）。 */
  staleTtlMs: Long = ConsoleTakeover.StaleTtlMs,
  /** 可见活动窗（同端判据批 B；spec 注入短窗以钉「静默让位」判据）。 */
  activityTtlMs: Long = ConsoleTakeover.ActivityTtlMs,
  /** 自动化端观察位开关（同端判据批 C；缺省读 env，spec 可显式开/关）。 */
  automationSpectator: Boolean = ConsoleTakeover.automationSpectator
):
  // Default domain combines console control and shared Canvas; subject to author review.
  // Both console ownership and Canvas fencing live in this single authoritative record.
  private val controlRef: Ref[IO, ConsoleTakeover.ControlState] = Ref.unsafe(ConsoleTakeover.ControlState())
  private val canvasLock = new java.util.concurrent.Semaphore(1)

  // Serialize claim/release with the entire authorized write, not just its check.
  private def canvasExclusive[A](fa: IO[A]): IO[A] =
    IO.uncancelable { poll =>
      IO.blocking(canvasLock.acquire()) *> poll(fa).guarantee(IO(canvasLock.release()))
    }

  // 单写者独占、**非抢占**（2026-11-18 作者令：多开只做检测，第二窗口只读）。
  // 授予条件 = 当前无写者，或写者就是本标签（刷新/重连的自我续位）；已有别的写者
  // ⇒ None（只读），绝不翻位。expectedEpoch 仍是严格 compare-and-set（并发同 epoch
  // 只有一个赢家）。旧口径「缺省抢占」随「取回控制权」状态机一并退役。
  def claimCanvas(principal: String, client: String, tab: String, expectedEpoch: Option[Long] = None): IO[Option[Long]] =
    val key = ConsoleTakeover.holderOf(principal, Some(client))
    canvasExclusive {
      controlRef.modify { state =>
        val mine = state.writer.exists(w => w.principal == principal && w.clientId == client && w.tabInstanceId == tab)
        if expectedEpoch.exists(_ != state.canvasEpoch) then (state, None)
        else if state.writer.isDefined && !mine then (state, None)
        else
          val next = Math.addExact(state.canvasEpoch, 1L)
          val writer = ConsoleTakeover.CanvasWriter(principal, client, tab, next)
          (state.copy(owner = Some(key), canvasEpoch = next, writer = Some(writer)), Some((next, state.owner)))
      }.flatMap {
        case None => IO.pure(None)
        case Some((epoch, previous)) =>
          (if previous.contains(key) then recordOwner(key)
           else reverseKickAfterClaim(key, previous).void).as(Some(epoch))
      }
    }

  def canvasRole(principal: String, client: String, tab: String): IO[Option[Long]] =
    controlRef.get.map(_.writer.filter(w => w.principal == principal && w.clientId == client && w.tabInstanceId == tab).map(_.epoch))

  def releaseCanvas(principal: String, client: String, tab: String, epoch: Long): IO[Boolean] =
    canvasExclusive(controlRef.modify { state =>
      if state.writer.contains(ConsoleTakeover.CanvasWriter(principal, client, tab, epoch)) then
        (state.copy(writer = None), true)
      else (state, false)
    })

  def withCanvasWriter[A](principal: String, client: String, tab: String, epoch: Long)(write: IO[A]): IO[Option[A]] =
    canvasExclusive(controlRef.get.flatMap { state =>
      if state.writer.contains(ConsoleTakeover.CanvasWriter(principal, client, tab, epoch)) then write.map(Some(_))
      else IO.pure(None)
    })

  /** holder → 实例账（活跃连接集 + 身份 + 最后入账时刻）。 */
  private val instancesRef: Ref[IO, Map[String, ConsoleTakeover.Instance]] = Ref.unsafe(Map.empty)

  /**
   * 曾持有过控制权的实例账（插入序，帽 [[ConsoleTakeover.PastOwnerCap]] 淘汰最旧）：
   * 「旧主回连不反抢」的判据面——A 被 B 接管后重连，落被踢面而非隐身夺权。
   */
  private val pastOwnersRef: Ref[IO, Vector[(String, Long)]] = Ref.unsafe(Vector.empty)

  /**
   * 内容帧闸（2026-10-29 作者令四修 · 隐私面；误报根修批放宽判据）：被顶出
   * （superseded）实例的连接对 wsHub 广播/定向扇出一律断喂对话内容——只放行接管
   * 协议帧（takeover/takeoverResult）。pong/心跳与本连接直回帧（perConnJsonSend）
   * 不经此闸。
   *
   * 裁决（每帧一次；owner 命中即放行、零解析开销——热路径只是一次 Ref 读）：
   *   总闸关 / holder==owner / owner 未定（claim 前引导窗） ⇒ 放行；
   *   **双方身份不可证（任一方无 clientId） ⇒ 放行**——身份不明时既不弹提示也不
   *   断喂（否则老缓存页会「无提示但全白」，比提示更糟）；
   *   双方身份可证且不同 ⇒ 仅接管协议帧放行，其余内容帧断喂。
   *
   * 语义：retake 翻位后 owner==本实例 ⇒ 闸自动放开（后续帧照常送达）；前端在
   * takeoverResult ok 后 reload 全量重同步——闸窗期的内容缺口只经 reload 愈合，
   * 不做增量续传（单实例语义：同一时刻只有一个可信实例在查看控制台）。
   */
  def gateFrame(holder: String, text: String): IO[Boolean] =
    if !enabled then IO.pure(true)
    else
      controlRef.get.map(_.owner).flatMap {
        case None => IO.pure(true) // owner 未定（claim 前引导窗）⇒ 放行——首实例就位前不饿引导帧
        case Some(o) if o == holder => IO.pure(true)
        case Some(o) =>
          instancesRef.get.map { m =>
            val mine = m.get(holder)
            val theirs = m.get(o)
            // 观察位/自动化端永不断喂（同端判据批 C：测试页要看得见内容，且它不参与
            // 互斥，断喂只会让自动化验证据失真）。
            val spectator = mine.exists(_.spectator) || mine.exists(_.automated) || theirs.exists(_.automated)
            // 同端判据（批 A）：同一物理端的两个标签/两条入口/换 clientId 的自我顶替
            // 不是「闯进来的人」，内容照喂——否则会退化成「不弹窗但全白」。
            val sameEnd =
              for
                a <- mine.flatMap(_.endprint)
                b <- theirs.flatMap(_.endprint)
              yield a == b
            val provenDifferent =
              for
                a <- mine.flatMap(_.clientId)
                b <- theirs.flatMap(_.clientId)
              yield a != b
            if spectator || sameEnd.contains(true) then true
            else
              provenDifferent match
                // 可证不同：superseded ⇒ 只放行接管协议帧。
                case Some(true) => ConsoleTakeover.isProtocolFrame(text)
                // 不可证（身份不明/账已被清扫）⇒ 放行：宁可少断喂，不可把同一个人误判成闯入者。
                case _ => true
          }
      }

  /** 控制权读数（spec / 未来观测面用）：当前 owner 的 holder 键。 */
  def owner: IO[Option[String]] = controlRef.get.map(_.owner)

  /** 会话的活跃连接数（spec 读数；同一浏览器多标签/多 holder 求和）。 */
  def connCount(sessionId: String): IO[Int] =
    instancesRef.get.map(_.values.filter(_.sessionId == sessionId).map(_.conns.size).sum)

  /**
   * 连接入账 + claim 裁决（`WebSocketRoutes` 在 `wsHub.register` 后、build 前调用；
   * 推帧经该连接 outbound 队列缓冲，随 serverConfig 同批送达）。禁 total 调用。
   *
   * `clientId` = 握手上的浏览器稳定身份（缺省 None = 老缓存页/无身份，落 session 退路）。
   */
  def onConnect(sessionId: String, connId: String, clientId: Option[String]): IO[Unit] =
    onConnect(sessionId, connId, ConsoleTakeover.ClientMeta(clientId = clientId))

  /** 握手腿入账（同端判据批：带端指纹/UA 类/来源 IP/自动化标记的完整身份）。 */
  def onConnect(sessionId: String, connId: String, meta: ConsoleTakeover.ClientMeta): IO[Unit] =
    if !enabled then IO.unit
    else
      val key = ConsoleTakeover.holderOf(sessionId, meta.clientId)
      IO.uncancelable { _ =>
        for
          now <- nowMs()
          _ <- sweepStale(now)
          _ <- addConn(key, sessionId, meta, connId, now)
          _ <- settle(key, connId)
        yield ()
      }

  /**
   * 在带身份声明（**握手 query 不可靠批 2026-11-03**）：浏览器在 ws open 后立刻上行
   * `{"type":"takeoverIdentify","clientId":"…"}`，本方法是它的落账核。
   *
   * 为什么必须有这条腿（根因实证）：实例身份自误报根修批（2026-11-02）起只认握手上的
   * `?clientId=`，而控制台的真实交付路径（门户/隧道腿、以及任何会重写请求目标的
   * 中转代理）**不保证把 query 送到引擎**——`stand.log` 2026-11-03 10:07–10:30 连续
   * 7 条连接全部以**会话 id**入账（`instance <sid> joined — no takeover (joining page
   * carries no client id …)`），其中一个还来自刚刚在 HTTP retake 体里带上 clientId 的
   * 同一浏览器 ⇒ `verdict` 恒 `Undecidable` ⇒ **永不提示、永不踢**（作者报障「手机端
   * 和电脑端同时在线，没有被挤掉」的直接成因；同时 `gateFrame` 也因身份不可证全放行，
   * 单实例语义整体失效）。故身份补一条**在带**通路：与 `?clientId=` 逐字同核
   * （[[ConsoleTakeover.holderOf]] + 同一 [[settle]]），差异只在时机——先按退路键
   * 入账，声明到达后[[migrateConn]]把连接账与所有权迁到 `c:<clientId>`，再重裁决。
   *
   * `clientId` 缺席（老缓存页/无 storage）⇒ 只回报当前 holder，零改动（静默空转）。
   * 返回值 = 本连接此后应使用的 holder 键（`WebSocketRoutes` 的 content-gate 读数）。
   */
  def onIdentify(sessionId: String, connId: String, clientId: Option[String]): IO[Option[String]] =
    onIdentifyDeclared(sessionId, connId, clientId.map(cid => ConsoleTakeover.ClientMeta(clientId = Some(cid))))

  /** 在带声明腿（带端指纹/自动化声明的完整身份形态；`None` = 声明缺席）。 */
  def onIdentifyDeclared(sessionId: String, connId: String, meta: Option[ConsoleTakeover.ClientMeta]): IO[Option[String]] =
    if !enabled then
      IO.pure(Some(ConsoleTakeover.holderOf(sessionId, meta.flatMap(_.clientId))))
    else
      meta.flatMap(_.clientId) match
        case None =>
          instancesRef.get.map { m =>
            Some(
              m.collectFirst { case (k, i) if i.conns.contains(connId) => k }
                .getOrElse(ConsoleTakeover.holderOf(sessionId, None))
            )
          }
        case Some(cid) =>
          val full = meta.getOrElse(ConsoleTakeover.ClientMeta(clientId = Some(cid)))
          val key = ConsoleTakeover.holderOf(sessionId, Some(cid))
          IO.uncancelable { _ =>
            def migrateNote(moved: Boolean): IO[Unit] =
              if moved then
                logWith(
                  s"console-takeover: ${label(key)} declared identity in-band — " +
                    s"instance account migrated (handshake carried no clientId)",
                  key
                )
              else IO.unit
            for
              now <- nowMs()
              moved <- migrateConn(connId, key, sessionId, full, now)
              cur <- controlRef.get.map(_.owner)
              _ <- if moved && !cur.contains(key) then settle(key, connId) else IO.unit
              _ <- migrateNote(moved)
            yield Some(key)
          }

  /**
   * 连接出账（`onFinalize` 与 `wsHub.unregister` 同拍——关闭/EOF/异常/#960 判死回收
   * 四途同归）。只摘连接账：**掉线 ≠ 让位**，owner 不动、零出帧（僵尸回收不触发提示）。
   *
   * 实例账条目保留（不因零连接立刻删键）：同 clientId 的重连必须还能认出「这就是
   * 我」——键被删了就会退化成一次 fresh claim。真正失效交给
   * [[ConsoleTakeover.sweepStale]]（零连接 + 超窗）。
   */
  def onDisconnect(connId: String): IO[Unit] =
    if !enabled then IO.unit
    else
      instancesRef.update { m =>
        m.map { case (k, inst) => k -> (if inst.conns.contains(connId) then inst.copy(conns = inst.conns - connId) else inst) }
      }

  // ── 内部 ────────────────────────────────────────────────────────────────

  /**
   * 入账后的 claim 裁决（握手腿 `onConnect` 与在带声明腿 `onIdentify` 单源）：
   *   无主 ⇒ 首实例即位（零帧）；
   *   本键即 owner ⇒ 静默续位（刷新/多标签/重连/静默重座，零帧）；
   *   **自动化端 + 人类活跃主** ⇒ 观察位（同端判据批 C：不夺权、零帧）；
   *   **同端指纹** ⇒ 静默让位（同端判据批 A：换 clientId / 换入口的一次自我顶替）；
   *   本键 ∈ pastOwners ⇒ 旧主回连，向本连接推 superseded 通知，owner 不动；
   *   否则按 [[ConsoleTakeover.verdict]]：可证不同 ⇒ 翻位 + 向旧主**仍有可见活动**的
   *   连接尽力送达（同端判据批 B：已死/静默的旧端静默让位，不弹）；
   *   不可证 ⇒ 既不提示也不踢（假阴性可接受，假阳性不可接受）。
   */
  private def settle(key: String, connId: String): IO[Unit] =
    for
      now <- nowMs()
      m <- instancesRef.get
      cur <- controlRef.get.map(_.owner)
      _ <- cur match
        case None =>
          // 首实例即位：无事件。
          claim(key) *> logWith(
            s"console-takeover: ${label(key)} claimed console control (first instance)",
            key
          )
        case Some(c) if c == key =>
          // 刷新 / 同浏览器第二标签 / 掉线重连 / 静默重座（同 clientId）：静默续位，零帧。
          IO.unit
        case Some(prevOwner) =>
          val me = m.get(key)
          val prev = m.get(prevOwner)
          val automatedMe = me.exists(_.automated)
          val prevHumanLive = prev.exists(p => !p.automated && ConsoleTakeover.isLive(p, now, activityTtlMs))
          pastOwnersRef.get.flatMap { past =>
            if automationSpectator && automatedMe && prevHumanLive then
              // 批 C：自动化端（Playwright/headless）不夺人类端的座位——落观察位。
              markSpectator(key) *> logWith(
                s"console-takeover: ${label(key)} joined as SPECTATOR (automated client never " +
                  s"displaces a live human console) — owner ${label(prevOwner)} untouched, zero frames",
                key,
                prevOwner
              )
            else if ConsoleTakeover.sameEnd(me.flatMap(_.endprint), prev.flatMap(_.endprint)) then
              // 批 A：同端让位——同一物理端换了 clientId/换了入口，不是第二个端。
              claim(key) *> logWith(
                s"console-takeover: ${label(key)} same-end handover from ${label(prevOwner)} " +
                  s"(identical end fingerprint — clientId churn or a second origin of ONE device); " +
                  s"silent, zero frames",
                key,
                prevOwner
              )
            else if past.exists(_._1 == key) then
              // 旧主回连不反抢：owner 不动，向回连方本连接推 superseded 通知。
              sendTo(connId, ConsoleTakeover.takeoverFrame(now)) *> logWith(
                s"console-takeover: ${label(key)} reconnected as non-owner — " +
                  s"superseded notice sent to its connection (owner ${label(prevOwner)} untouched)",
                key,
                prevOwner
              )
            else
              ConsoleTakeover.verdict(me.flatMap(_.clientId), prev.flatMap(_.clientId),
                me.flatMap(_.endprint), prev.flatMap(_.endprint)) match
                case ConsoleTakeover.Verdict.SameEnd =>
                  claim(key) *> logWith(
                    s"console-takeover: ${label(key)} same-end handover from ${label(prevOwner)} " +
                      s"(identical end fingerprint); silent, zero frames",
                    key,
                    prevOwner
                  )
                case ConsoleTakeover.Verdict.Conflict =>
                  // 批 B：只推给「此刻真有可见活动」的旧主连接；自动化/观察位不接弹窗帧
                  // （它们不看控制台，弹窗只会污染自动化验证据）。
                  val prevConns = prev.map(_.conns.toList.sorted).getOrElse(Nil)
                  val targets =
                    if prev.exists(p => !p.automated && ConsoleTakeover.isLive(p, now, activityTtlMs)) then prevConns
                    else Nil
                  for
                    n <- nowMs()
                    _ <- claim(key)
                    _ <- targets.traverse_(cid => sendTo(cid, ConsoleTakeover.takeoverFrame(n)))
                    _ <- logWith(
                      s"console-takeover: ${label(key)} took over from ${label(prevOwner)} — " +
                        s"takeover pushed to ${targets.size}/${prevConns.size} live conn(s)" +
                        (if targets.size < prevConns.size then " (idle/dead conns silently superseded)" else ""),
                      key,
                      prevOwner
                    )
                  yield ()
                case ConsoleTakeover.Verdict.Undecidable(why) =>
                  // 身份不可证 ⇒ 既不提示也不踢（假阴性可接受，假阳性不可接受）。
                  logWith(
                    s"console-takeover: ${label(key)} joined — no takeover ($why); " +
                      s"owner ${label(prevOwner)} untouched, zero frames",
                    key,
                    prevOwner
                  )
          }
    yield ()

  /**
   * 连接账迁移（在带声明腿）：把 `connId` 从它当前所在的实例账（退路键 = sessionId）
   * 挪到身份键 `key = c:<clientId>`，并把「所有权」「pastOwners 史」一并带过去
   * ——同一物理实例只是换了标签，**不是**新实例，故迁移本身零出帧（kick 与否由迁移
   * 之后的 [[settle]] 对**旧 owner** 裁决）。旧键的连接账摘空即删键（否则会留一条
   * 零连接的陈旧账，10 分钟后被 sweepStale 再记一行噪音）。
   *
   * 返回 true = 键确实变了（调用方据此跑一次重裁决）；false = 握手已带同一身份
   * （直连脸/老路径），零重复裁决。
   */
  private def migrateConn(connId: String, key: String, sessionId: String, meta: ConsoleTakeover.ClientMeta, now: Long): IO[Boolean] =
    instancesRef.get.flatMap { m =>
      val oldKey = m.collectFirst { case (k, i) if i.conns.contains(connId) => k }
      if oldKey.contains(key) then addConn(key, sessionId, meta, connId, now).as(false)
      else
        for
          _ <- instancesRef.update { m2 =>
            val pruned = oldKey.fold(m2) { old =>
              m2.get(old) match
                case None => m2
                case Some(inst) =>
                  val left = inst.copy(conns = inst.conns - connId)
                  if left.conns.isEmpty then m2 - old else m2.updated(old, left)
            }
            val prev = pruned.get(key).getOrElse(ConsoleTakeover.Instance(key, sessionId, None, Set.empty, now))
            pruned.updated(key, ConsoleTakeover.merged(prev, sessionId, meta, Set(connId), now))
          }
          // 所有权与历史随标签走：迁移是**同一次落地的换键**（在带声明腿），不是身份
          // 变化，故 owner 随迁、写者席位不动（写入由 epoch 栅栏判定，与 holder 键无关）。
          _ <- oldKey.traverse_(old => canvasExclusive(controlRef.update(s => if s.owner.contains(old) then s.copy(owner = Some(key)) else s)))
          _ <- oldKey.traverse_ { old =>
                 pastOwnersRef.update { v =>
                   v.map { case (k, t) => (if k == old then key else k, t) }
                     .foldLeft(Vector.empty[(String, Long)]) { (acc, e) =>
                       if acc.exists(_._1 == e._1) then acc else acc :+ e
                     }
                 }
               }
        yield true
    }

  private def addConn(key: String, sessionId: String, meta: ConsoleTakeover.ClientMeta, connId: String, now: Long): IO[Unit] =
    instancesRef.update { m =>
      val prev = m.get(key).getOrElse(ConsoleTakeover.Instance(key, sessionId, None, Set.empty, now))
      m.updated(key, ConsoleTakeover.merged(prev, sessionId, meta, Set(connId), now))
    }

  /**
   * 控制台可见活动戳（同端判据批 B）：入站帧（含心跳 ping）证明「这一端此刻真在看」。
   * 只更新本连接所属实例的 `lastActivity`；调用侧节流（最密 5s 一次），这里不做判断。
   */
  def noteActivity(connId: String): IO[Unit] =
    if !enabled then IO.unit
    else
      nowMs().flatMap(now =>
        instancesRef.update { m =>
          m.map { case (k, i) => if i.conns.contains(connId) then k -> i.copy(lastActivity = now) else k -> i }
        }
      )

  /** 观察位（同端判据批 C）：自动化端不得夺权，但内容闸对它放行。 */
  private def markSpectator(key: String): IO[Unit] =
    instancesRef.update { m =>
      m.get(key).fold(m)(i => m.updated(key, i.copy(spectator = true, automated = true)))
    }

  /** 显式 retake 解除观察位（用户/自动化端主动要座位时按普通实例对待）。 */
  private def clearSpectator(key: String): IO[Unit] =
    instancesRef.update { m =>
      m.get(key).fold(m)(i => if i.spectator then m.updated(key, i.copy(spectator = false)) else m)
    }

  /** 日志附加面（同端判据批③：补齐「谁顶了谁」的证据位——sid/ip/ua 类/端指纹/自动化）。 */
  private def logWith(msg: String, keys: String*): IO[Unit] =
    instancesRef.get.flatMap { m =>
      val suffix = keys.distinct
        .map(k => s"${ConsoleTakeover.maskHolder(k)}=${ConsoleTakeover.describe(m.get(k))}")
        .mkString(" ")
      logger.info(if suffix.isEmpty then msg else s"$msg [$suffix]")
    }

  /**
   * 机会式陈旧清扫（每次入账先跑一次，O(实例数)，无后台 fiber）：
   * 「零活连接 且 超 `staleTtl` 无入账」⇒ 摘账；若摘掉的正是 owner ⇒ owner 一并
   * 清空（下一个连接就是普通首实例即位——零提示）。有活连接的实例永不清扫。
   */
  private def sweepStale(now: Long): IO[Unit] =
    instancesRef
      .modify { m =>
        val (dead, alive) = m.partition { case (_, i) => i.conns.isEmpty && (now - i.lastSeen) > staleTtlMs }
        (alive, dead.keySet)
      }
      .flatMap { dead =>
        if dead.isEmpty then IO.unit
        else
          canvasExclusive(controlRef.update(s => if s.owner.exists(dead.contains) then s.copy(owner = None, writer = None) else s)) *>
            logger.info(
              s"console-takeover: swept ${dead.size} stale instance(s) " +
                s"[${dead.toList.sorted.map(label).mkString(", ")}] — owner cleared if it was one of them"
            )
      }

  /**
   * 翻位 + 反向踢共享核（ws 腿 / HTTP 腿单源，接管必达批抽出）：推送面在翻位前
   * 定死（旧主此刻的活跃连接集），claim 后对旧主全部活跃连接尽力送达 takeover 帧。
   * 返回送达连接数（无旧主/无活连接 = 0；旧主下次重连按 pastOwners 规则落被踢面
   * ——最终一致，A9 同款兜底）。
   */
  // Shared post-transition path: Canvas performs its state transition atomically under
  // the same lock, so it cannot call claim(key) again (that would clear its writer).
  private def reverseKickAfterClaim(key: String, prevOwner: Option[String]): IO[Int] =
    for
      now <- nowMs()
      prevInst <- prevOwner.flatTraverse(p => instancesRef.get.map(_.get(p)))
      _ <- clearSpectator(key)
      _ <- recordOwner(key)
      _ <- prevInst
        .filter(p => ConsoleTakeover.isLive(p, now, activityTtlMs) && !p.automated)
        .traverse_(p => p.conns.toList.sorted.traverse_(cid => sendTo(cid, ConsoleTakeover.takeoverFrame(now))))
    yield prevInst.map(_.conns.size).getOrElse(0)

  /** 记 owner + pastOwners 账（LRU 帽内淘汰最旧）。写者席位与 owner 标签解耦
   *  （2026-11-18）：控制台首实例即位不得清空画布席位，否则刚即位的窗口下一次
   *  写穿就带着过期 epoch 撞栅栏 ⇒ 单实例平白报「保存失败」（零误报优先）。 */
  private def claim(key: String): IO[Unit] =
    canvasExclusive(controlRef.update(s =>
      if s.owner.contains(key) then s else s.copy(owner = Some(key))
    )) *> recordOwner(key)

  private def recordOwner(key: String): IO[Unit] =
    nowMs().flatMap { now =>
        pastOwnersRef.update { v =>
          val pruned = v.filterNot(_._1 == key) :+ ((key, now))
          if pruned.length > ConsoleTakeover.PastOwnerCap then pruned.drop(pruned.length - ConsoleTakeover.PastOwnerCap)
          else pruned
        }
    }

  private def nowMs(): IO[Long] = IO.realTime.map(_.toMillis)

  /** 日志面脱敏：holder 键去掉 `c:`/`s:` 前缀后取前 8 位（与旧日志可读性同档）。 */
  private def label(holder: String): String = s"instance ${ConsoleTakeover.maskHolder(holder)}"

end ConsoleTakeover

object ConsoleTakeover:

  final case class CanvasWriter(principal: String, clientId: String, tabInstanceId: String, epoch: Long)
  final case class ControlState(owner: Option[String] = None, canvasEpoch: Long = 0L, writer: Option[CanvasWriter] = None)

  /**
   * 实例账（holder → 活跃连接 + 身份 + 最后入账时刻 + 端指纹 + 可见活动戳）。
   *
   * 同端判据批（2026-11-07）新增字段全部带缺省值：既有 spec 的构造面逐字不变
   * （`Instance(holder, sessionId, clientId, conns, lastSeen)` 仍编译且语义同旧）。
   *   · `endprint` —— 端指纹（UA 规范化短哈希）：同端的第二判据（批 A）；
   *   · `uaClass` / `ip` —— 日志证据位（批③：谁顶了谁，一眼可判）；
   *   · `automated` —— 自动化端标记（webdriver/headless UA，批 C）；
   *   · `spectator` —— 观察位（自动化端不夺权，见 [[ConsoleTakeover.settle]]）；
   *   · `lastActivity` —— 最近一次入站帧时刻（可见活动闸，批 B）。
   */
  final case class Instance(
    holder: String,
    sessionId: String,
    clientId: Option[String],
    conns: Set[String],
    lastSeen: Long,
    endprint: Option[String] = None,
    uaClass: String = "-",
    ip: Option[String] = None,
    automated: Boolean = false,
    spectator: Boolean = false,
    lastActivity: Long = 0L
  )

  /**
   * 连接身份声明（握手腿/在带声明腿同一形态）：clientId + 端指纹素材 + 来源 IP +
   * 自动化声明。`automatedDeclared` 由客户端 `navigator.webdriver` 上报（批 C），
   * `ua` 侧另有兜底判据（headless UA）——两条任一命中即自动化端。
   */
  final case class ClientMeta(
    clientId: Option[String] = None,
    ua: Option[String] = None,
    ip: Option[String] = None,
    automatedDeclared: Boolean = false
  ):
    def endprint: Option[String] = ConsoleTakeover.endprintOf(ua)
    def uaClass: String = ConsoleTakeover.uaClass(ua)
    def automated: Boolean = automatedDeclared || ConsoleTakeover.uaLooksAutomated(ua)

  /** 实例账的字段合并核（入账/迁移单源）：身份以最近一次入账为准，连接集只增。 */
  def merged(prev: Instance, sessionId: String, meta: ClientMeta, added: Set[String], now: Long): Instance =
    prev.copy(
      sessionId = sessionId,
      clientId = meta.clientId.orElse(prev.clientId),
      endprint = meta.endprint.orElse(prev.endprint),
      uaClass = if meta.uaClass == "-" then prev.uaClass else meta.uaClass,
      ip = meta.ip.orElse(prev.ip),
      automated = prev.automated || meta.automated,
      conns = prev.conns ++ added,
      lastSeen = now,
      lastActivity = now
    )

  /** 「此刻真有可见活动」：存在活连接 且 `ttlMs` 内有过入站帧（同端判据批 B 的唯一判据）。 */
  def isLive(inst: Instance, nowMs: Long, ttlMs: Long): Boolean =
    inst.conns.nonEmpty && (nowMs - inst.lastActivity) <= ttlMs

  /** 可见活动窗（缺省 120s）：手机端心跳 30s 一拍 ⇒ 连续两拍丢失才算「没在看」。 */
  val ActivityTtlMs: Long = 120L * 1000L

  /** 端指纹：UA 规范化（trim + 折叠空白）后的 SHA-256 前 6 字节十六进制。 */
  def endprintOf(ua: Option[String]): Option[String] =
    ua.map(_.trim.replaceAll("\\s+", " ")).filter(_.nonEmpty).map { u =>
      val md = java.security.MessageDigest.getInstance("SHA-256")
      md.digest(u.getBytes(java.nio.charset.StandardCharsets.UTF_8)).take(6).map(b => f"${b & 0xff}%02x").mkString
    }

  /** UA 类（日志证据位；判不出 = `-`）。只做可读分类，不作身份键。 */
  def uaClass(ua: Option[String]): String =
    ua.getOrElse("") match
      case u if u.isEmpty => "-"
      case u if uaLooksAutomated(Some(u)) =>
        if u.contains("iPhone") || u.contains("Android") then "mobile-headless" else "headless-browser"
      case u if u.contains("iPhone") => "iphone"
      case u if u.contains("iPad") => "ipad"
      case u if u.contains("Android") => "android"
      case u if u.contains("Macintosh") => "mac"
      case u if u.contains("Windows") => "windows"
      case _ => "other"

  /** 自动化端判据（批 C 兜底腿）：UA 带 Headless / PhantomJS / 无 head 时仍可判。 */
  def uaLooksAutomated(ua: Option[String]): Boolean =
    ua.exists(u => u.contains("Headless") || u.contains("PhantomJS") || u.contains("Electron/"))

  /** 同端判据（批 A 单源）：两枚端指纹都在场且相等。任一缺席 ⇒ false（退回 clientId 口径）。 */
  def sameEnd(mine: Option[String], prev: Option[String]): Boolean =
    (mine, prev) match
      case (Some(a), Some(b)) => a == b
      case _ => false

  /** 日志证据位（批③：补最小埋点——sid/ip/ua 类/端指纹/自动化，永不含 token）。 */
  def describe(inst: Option[Instance]): String =
    inst match
      case None => "sid=? ip=? ua=? end=? auto=?"
      case Some(i) =>
        val sid = i.sessionId.take(8)
        s"sid=$sid ip=${i.ip.filter(_.nonEmpty).getOrElse("-")} ua=${i.uaClass} " +
          s"end=${i.endprint.getOrElse("-")} auto=${if i.automated then "yes" else "no"}" +
          (if i.spectator then " spectator=yes" else "")

  /**
   * 接管判定（单源）：
   *   SameEnd                      —— **同端指纹**（批 A）⇒ 静默让位/并账，零帧零弹窗；
   *   Conflict                     —— 两个可证不同的客户端（真并发抢占）；
   *   Undecidable(why)             —— 任一方 clientId 不明 ⇒ 不提示不踢（假阴性可接受）。
   */
  enum Verdict:
    case SameEnd
    case Conflict
    case Undecidable(why: String)

  def verdict(mine: Option[String], prev: Option[String],
              mineEnd: Option[String] = None, prevEnd: Option[String] = None): Verdict =
    if sameEnd(mineEnd, prevEnd) then Verdict.SameEnd
    else
      (mine, prev) match
        case (Some(a), Some(b)) if a != b => Verdict.Conflict
        case (Some(_), Some(_))           => Verdict.Undecidable("same client id (refresh / second tab / reseat)")
        case (None, _)                    => Verdict.Undecidable("joining page carries no client id (legacy cache)")
        case (_, None)                    => Verdict.Undecidable("incumbent carries no client id (legacy cache)")

  /** pastOwners 账帽（淘汰最旧）：内存态、会话 ≤30d 寿命，64 富余。 */
  val PastOwnerCap: Int = 64

  /** 实例账陈旧窗（缺省 10 分钟）：零活连接 + 超窗无入账 ⇒ 摘账（owner 随之清空）。 */
  val StaleTtlMs: Long = 10L * 60L * 1000L

  /** 总闸 env 名（裸后缀；`Branding.env` 补品牌前缀/NEBFLOW_ 回退）。 */
  val EnvName: String = "CONSOLE_SINGLE_INSTANCE"

  /** 自动化端观察位开关（同端判据批 C，裸后缀）：`off/0/false/no` = 回退旧行为（自动化端照常夺权）。 */
  val AutomationEnvName: String = "CONSOLE_AUTOMATION_SPECTATOR"

  /** 观察位开关读数（boot 一次）：缺省开——自动化验跑不该顶掉作者的控制台。 */
  lazy val automationSpectator: Boolean =
    Branding
      .env(AutomationEnvName)
      .map(v => !OffValues.contains(v.trim.toLowerCase))
      .getOrElse(true)

  /** 关闭值集合（大小写不敏感）；缺省与其余值 = 启用。 */
  private val OffValues: Set[String] = Set("off", "0", "false", "no")

  /** 总闸读数：boot 一次读 env（进程级常量）。缺省启用（本批即产品行为）。 */
  lazy val defaultEnabled: Boolean =
    Branding.env(EnvName).map(v => !OffValues.contains(v.trim.toLowerCase)).getOrElse(true)

  /** 路由侧缺省实例（`connGuard.disabled` 同款缝）：既有构造点零改动、行为零漂移。 */
  lazy val disabled: ConsoleTakeover =
    new ConsoleTakeover((_, _) => IO.unit, NebflowLogger.forName("nebflow.console-takeover"), enabled = false)

  /**
   * holder 键（实例身份）：有 `clientId` ⇒ `"c:<clientId>"`（同一浏览器的刷新/多标签/
   * 后台恢复/静默重座恒同键）；无 ⇒ 退路用 sessionId 原值（老缓存页与既有 spec
   * 的构造面，行为与 2026-10-27 批一致）。
   * `c:` 前缀避免与 sessionId 撞键（会话 id 是 base64url 随机串，理论可撞；
   * 撞了也只是把无关实例并成一个 holder，不能越权——鉴权不在本表）。
   */
  def holderOf(sessionId: String, clientId: Option[String]): String =
    clientId.map(c => "c:" + c).getOrElse(sessionId)

  /** 日志脱敏（前 8 位）。 */
  def maskHolder(holder: String): String =
    val bare = if holder.startsWith("c:") then holder.drop(2) else holder
    bare.take(8)

  /** 服务端→旧实例的接管帧（无 sessionId：前端 GLOBAL 路由，不抢 activeView）。 */
  def takeoverFrame(atMs: Long): Json = Json.obj("type" -> "takeover".asJson, "at" -> atMs.asJson)

  /** retake 回执帧。 */
  def resultFrame(ok: Boolean): Json = Json.obj("type" -> "takeoverResult".asJson, "ok" -> ok.asJson)

  /**
   * 内容帧闸放行集（[[ConsoleTakeover.gateFrame]]）：被顶出连接只喂接管协议帧。
   * 集中一处，禁各调用点自列（第二份判据 = 漂移源）。
   */
  private val GatePassTypes: Set[String] = Set("takeover", "takeoverResult")

  /** 帧是否属于接管协议（廉价 parse + type 比对；非 JSON/异型 ⇒ false）。 */
  def isProtocolFrame(text: String): Boolean =
    io.circe.parser
      .parse(text)
      .flatMap(_.hcursor.downField("type").as[String])
      .toOption
      .exists(GatePassTypes)

  /** 收流管判别（廉价 parse + type 比对；非 JSON/异型 ⇒ false 走常规分派）。 */
  def isRetakeFrame(text: String): Boolean =
    io.circe.parser
      .parse(text)
      .flatMap(_.hcursor.downField("type").as[String])
      .toOption
      .contains("takeoverRetake")

  /** 帧型常量（客户端 `ws.js` 与前端静态哨兵 `scripts/check-takeover-identify.mjs` 同源比较点）。 */
  val IdentifyFrameType: String = "takeoverIdentify"

  /** 声明体 clientId 上限（与握手上的 `?clientId=`、HTTP retake 体同档）。 */
  val ClientIdMax: Int = 128

  /** 收流管判别：在带身份声明帧（**握手 query 不可靠批 2026-11-03**）。 */
  def isIdentifyFrame(text: String): Boolean =
    io.circe.parser
      .parse(text)
      .flatMap(_.hcursor.downField("type").as[String])
      .toOption
      .contains(IdentifyFrameType)

  /**
   * 从声明帧里取 clientId（与 [[isIdentifyFrame]] 同一 parse 口径：trim 后非空且
   * 不超 [[ClientIdMax]]；异型/坏体 ⇒ None = 按缺席走退路，绝不因坏帧断连）。
   */
  def declaredClientId(text: String): Option[String] =
    io.circe.parser
      .parse(text)
      .toOption
      .flatMap(_.hcursor.downField("clientId").as[String].toOption)
      .map(_.trim)
      .filter(id => id.nonEmpty && id.length <= ClientIdMax)

  /** 在带身份声明帧（客户端 → 服务端；`ConsoleTakeover.onIdentify` 消费）。 */
  def identifyFrame(clientId: String): Json =
    Json.obj("type" -> IdentifyFrameType.asJson, "clientId" -> clientId.asJson)

  /**
   * 声明帧的自动化标记（同端判据批 C，客户端腿）：`{"automated":true}` = 本页由
   * `navigator.webdriver` 判定为自动化（Playwright/Puppeteer/Selenium）。坏体/缺席
   * ⇒ false（只有真声明才算，绝不按缺席推定）。
   */
  def declaredAutomated(text: String): Boolean =
    io.circe.parser
      .parse(text)
      .toOption
      .flatMap(_.hcursor.downField("automated").as[Boolean].toOption)
      .getOrElse(false)

end ConsoleTakeover
