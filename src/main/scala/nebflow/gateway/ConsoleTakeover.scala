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
 * == 身份与粒度（误报根修批 2026-11-02） ==
 * 实例 = **浏览器客户端**（`clientId`，`deviceIdentity.js` 的 localStorage 稳定 id，
 * 随 `/ws?clientId=` 握手带上），会话仅作退路。旧口径「实例 = 门户会话
 * `sessionId`」是把**易变的凭据**当身份，实测误报链（日志 stand.2026-11-02.0
 * 12:29–12:53 连续 19 次 `took over from … pushed to 0 conn(s)`）：
 *   手机/桌面会话过期或被保险丝截断（`GatewaySessionStore` idle 表）⇒ ws.js
 *   [[remintHostSessionAndReload]] 静默重座 ⇒ `/api/portal/console-ticket` ⇒
 *   `/` 兑换**新 sessionId** ⇒ 同一台设备以**新会话**连回 ⇒ 旧口径视作「另一个
 *   实例」⇒ 向自己推 `takeover` 帧 ⇒ 「没人挤我，也显示被挤」。
 * 现口径：`holder = "c:" + clientId`（有 clientId）/ `holder = sessionId`（无，退路
 * ——老缓存页与 Bearer 面）。同一浏览器刷新/多标签/后台恢复/静默重座**恒同 holder**
 * ⇒ 静默续位；浏览器会话被清空（localStorage 换 id）才换 holder。
 * 安全边界声明：`clientId` 是**UX 级身份键**，不是鉴权凭据（鉴权仍由 HttpOnly
 * 会话 cookie 决定）——伪造 clientId 最坏只是「不弹提示」，拿不到任何数据面。
 *
 * == claim 规则（互斥权写入点只有两个） ==
 *   1. fresh claim：owner==None ⇒ 即位；owner==他会话 且 本实例 ∉ pastOwners ⇒
 *      接管（owner 翻位 + 向旧主全部活跃连接推 `takeover` 帧，尽力送达）。
 *      但**只有当双方身份都可证且不同**才判冲突（见 [[ConsoleTakeover.verdict]]）：
 *      任一方 clientId 不明（老缓存页/升级窗）⇒ 宁可**不提示不踢**（假阴性可接受，
 *      假阳性不可接受——误报会让人以为账号被盗）。
 *   2. retake：被踢面用户点【重新接管】⇒ owner 翻回 + 向前主活跃连接推帧（反向踢）
 *      + 回执。两腿同核（[[ConsoleTakeover.claimAndReverseKick]] 单源）：
 *        · ws 腿：`takeoverRetake` 帧（WebSocketRoutes 收流管拦截）——回执经本连接
 *          出站队列（连接活时零跳）；
 *        · HTTP 腿（接管必达批 2026-11-01 作者令①）：`POST /api/takeover/retake`
 *          （[[ConsoleTakeoverRoutes]]，session-rail only）——回执同步随 HTTP 响应
 *          必达，不依赖本会话 ws 存活（死连接/僵尸/重连窗口上的帧腿结构性缺口，
 *          「重新接管不一定成功」的根因面）。
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
  staleTtlMs: Long = ConsoleTakeover.StaleTtlMs
):
  /** 拥有控制权的实例（全局唯一——控制台是每引擎一个的互斥资源）；值是 holder 键。 */
  private val ownerRef: Ref[IO, Option[String]] = Ref.unsafe(None)

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
      ownerRef.get.flatMap {
        case None => IO.pure(true) // owner 未定（claim 前引导窗）⇒ 放行——首实例就位前不饿引导帧
        case Some(o) if o == holder => IO.pure(true)
        case Some(o) =>
          instancesRef.get.map { m =>
            val provenDifferent =
              for
                mine <- m.get(holder).flatMap(_.clientId)
                theirs <- m.get(o).flatMap(_.clientId)
              yield mine != theirs
            provenDifferent match
              // 可证不同：superseded ⇒ 只放行接管协议帧。
              case Some(true) => ConsoleTakeover.isProtocolFrame(text)
              // 不可证（身份不明/账已被清扫）⇒ 放行：宁可少断喂，不可把同一个人误判成闯入者。
              case _ => true
          }
      }

  /** 控制权读数（spec / 未来观测面用）：当前 owner 的 holder 键。 */
  def owner: IO[Option[String]] = ownerRef.get

  /** 会话的活跃连接数（spec 读数；同一浏览器多标签/多 holder 求和）。 */
  def connCount(sessionId: String): IO[Int] =
    instancesRef.get.map(_.values.filter(_.sessionId == sessionId).map(_.conns.size).sum)

  /**
   * 连接入账 + claim 裁决（`WebSocketRoutes` 在 `wsHub.register` 后、build 前调用；
   * 推帧经该连接 outbound 队列缓冲，随 serverConfig 同批送达）。禁 total 调用。
   *
   * `clientId` = 握手上的浏览器稳定身份（缺省 None = 老缓存页/无身份，落 session 退路）。
   */
  def onConnect(sessionId: String, connId: String, clientId: Option[String] = None): IO[Unit] =
    if !enabled then IO.unit
    else
      val key = ConsoleTakeover.holderOf(sessionId, clientId)
      IO.uncancelable { _ =>
        for
          now <- nowMs()
          _ <- sweepStale(now)
          _ <- addConn(key, sessionId, clientId, connId, now)
          cur <- ownerRef.get
          _ <- cur match
            case None =>
              // 首实例即位：无事件。
              claim(key) *> logger.info(
                s"console-takeover: ${label(key)} claimed console control (first instance)"
              )
            case Some(c) if c == key =>
              // 刷新 / 同浏览器第二标签 / 掉线重连 / 静默重座（同 clientId）：静默续位，零帧。
              IO.unit
            case Some(prevOwner) =>
              pastOwnersRef.get.flatMap { past =>
                if past.exists(_._1 == key) then
                  // 旧主回连不反抢：owner 不动，向回连方本连接推 superseded 通知。
                  nowMs().flatMap(now => sendTo(connId, ConsoleTakeover.takeoverFrame(now))) *>
                    logger.info(
                      s"console-takeover: ${label(key)} reconnected as non-owner — " +
                        s"superseded notice sent to its connection (owner ${label(prevOwner)} untouched)"
                    )
                else
                  instancesRef.get.flatMap { m =>
                    ConsoleTakeover.verdict(m.get(key).flatMap(_.clientId), m.get(prevOwner).flatMap(_.clientId)) match
                      case ConsoleTakeover.Verdict.Conflict =>
                        // 真并发两个不同控制端：互斥权最后写入者胜；旧主活连接尽力送达
                        // （无活连接则旧主下次重连按 pastOwners 规则落被踢面——最终一致）。
                        val targets = m.get(prevOwner).map(_.conns.toList).getOrElse(Nil)
                        for
                          n <- nowMs()
                          _ <- claim(key)
                          _ <- targets.traverse_(cid => sendTo(cid, ConsoleTakeover.takeoverFrame(n)))
                          _ <- logger.info(
                            s"console-takeover: ${label(key)} took over from " +
                              s"${label(prevOwner)} — takeover pushed to ${targets.size} conn(s)"
                          )
                        yield ()
                      case ConsoleTakeover.Verdict.Undecidable(why) =>
                        // 身份不可证 ⇒ 既不提示也不踢（假阴性可接受，假阳性不可接受）。
                        logger.info(
                          s"console-takeover: ${label(key)} joined — no takeover ($why); " +
                            s"owner ${label(prevOwner)} untouched, zero frames"
                        )
                  }
              }
        yield ()
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

  /**
   * 【重新接管】（收流管拦截 `takeoverRetake` 帧后调用）：owner 翻回本连接的实例 +
   * 向前主活跃连接反向踢 + 回执。未注册连接（Bearer）⇒ ok:false。
   */
  def handleRetake(connId: String, reply: Json => IO[Unit]): IO[Unit] =
    if !enabled then IO.unit
    else
      instancesRef.get.flatMap { m =>
        m.collectFirst { case (k, inst) if inst.conns.contains(connId) => k } match
          case None =>
            logger.warn(s"console-takeover: retake from unregistered conn $connId refused") *>
              reply(ConsoleTakeover.resultFrame(ok = false))
          case Some(key) =>
            IO.uncancelable { _ =>
              for
                cur <- ownerRef.get
                _ <- cur match
                  case Some(c) if c == key =>
                    // 已是 owner（竞态窗：模态未收时所有权已回）——幂等 ok，零帧。
                    IO.unit
                  case _ =>
                    for
                      _ <- claimAndReverseKick(key, cur)
                      _ <- logger.info(
                        s"console-takeover: ${label(key)} retook console control (ws leg)" +
                          cur.fold("")(p => s" — reverse takeover pushed to ${label(p)}")
                      )
                    yield ()
                // 统一回执（already-owner 竞态腿同为幂等 ok；ok:false 只在未注册腿发）。
                _ <- reply(ConsoleTakeover.resultFrame(ok = true))
              yield ()
            }
      }

  /**
   * 【重新接管】HTTP 腿（接管必达批 2026-11-01 作者令①）：实例身份来自 HttpOnly
   * cookie（会话）+ 请求声明的 clientId（`ConsoleTakeoverRoutes`，session-rail
   * only）——**不要求本会话在连接账上有活连接**。修的是 ws 腿的结构性缺口：
   *   · 被顶出页面的 ws 可能已死（后台标签心跳断喂/僵尸回收/重连窗口）——帧腿
   *     的 sendWs 静默入队死 socket、回执可被出站队列溢出拆连吞掉；
   *   · HTTP 响应同步返回 = 回执必达（无队列、无心跳竞态面）。
   * 所有权翻面 + 反向踢语义与 ws 腿逐字一致（同一 [[claimAndReverseKick]] 单源）；
   * 被踢端推送尽力而为 + 重连补推（pastOwners 规则）兜底，最终一致不变。
   * 误报根修批：holder 与本页 ws 入账同一个键（= 同 clientId）⇒ 翻位后 reload 的
   * 新连接认得出「这就是我」，不会再自我判冲突。
   */
  def retakeBySession(sessionId: String, clientId: Option[String] = None): IO[ConsoleTakeover.RetakeOutcome] =
    if !enabled then IO.pure(ConsoleTakeover.RetakeOutcome.Disabled)
    else
      val key = ConsoleTakeover.holderOf(sessionId, clientId)
      IO.uncancelable { _ =>
        for
          cur <- ownerRef.get
          kicked <- cur match
            case Some(c) if c == key =>
              // 已是 owner（竞态窗：模态未收时所有权已回）——幂等成功，零帧。
              IO.pure(0)
            case _ =>
              for
                kicked <- claimAndReverseKick(key, cur)
                _ <- logger.info(
                  s"console-takeover: ${label(key)} retook console control (http leg) — " +
                    s"reverse takeover pushed to $kicked conn(s)" +
                    cur.fold("")(p => s" of ${label(p)}")
                )
              yield kicked
        yield ConsoleTakeover.RetakeOutcome.Ok(kicked)
      }

  // ── 内部 ────────────────────────────────────────────────────────────────

  private def addConn(key: String, sessionId: String, clientId: Option[String], connId: String, now: Long): IO[Unit] =
    instancesRef.update { m =>
      val prev = m.get(key).getOrElse(ConsoleTakeover.Instance(key, sessionId, clientId, Set.empty, now))
      // 身份以最近一次入账为准（clientId 只在有值时覆盖：退路连接不清空已知身份）。
      m.updated(key, prev.copy(sessionId = sessionId, clientId = clientId.orElse(prev.clientId),
        conns = prev.conns + connId, lastSeen = now))
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
          ownerRef.update(o => if o.exists(dead.contains) then None else o) *>
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
  private def claimAndReverseKick(key: String, prevOwner: Option[String]): IO[Int] =
    for
      targets <- prevOwner
        .traverse(p => instancesRef.get.map(_.get(p).map(_.conns.toList).getOrElse(Nil)))
        .map(_.getOrElse(Nil))
      now <- nowMs()
      _ <- claim(key)
      _ <- targets.traverse_(cid => sendTo(cid, ConsoleTakeover.takeoverFrame(now)))
    yield targets.size

  /** 记 owner + pastOwners 账（LRU 帽内淘汰最旧）。 */
  private def claim(key: String): IO[Unit] =
    nowMs().flatMap { now =>
      ownerRef.set(Some(key)) *>
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

  /** 实例账（holder → 活跃连接 + 身份 + 最后入账时刻）。 */
  final case class Instance(
    holder: String,
    sessionId: String,
    clientId: Option[String],
    conns: Set[String],
    lastSeen: Long
  )

  /** 【重新接管】HTTP 腿的裁决（`retakeBySession`）：Ok 带反向踢送达连接数；Disabled = 总闸关。 */
  sealed trait RetakeOutcome extends Product with Serializable
  object RetakeOutcome:
    final case class Ok(kickedConns: Int) extends RetakeOutcome
    case object Disabled extends RetakeOutcome

  /**
   * 接管判定（误报根修批单源）：**只有双方身份都可证且不同**才算冲突。
   *   Conflict                     —— 两个可证不同的客户端（真并发抢占）；
   *   Undecidable(why)             —— 任一方 clientId 不明 ⇒ 不提示不踢（假阴性可接受）。
   */
  enum Verdict:
    case Conflict
    case Undecidable(why: String)

  def verdict(mine: Option[String], prev: Option[String]): Verdict =
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

end ConsoleTakeover
