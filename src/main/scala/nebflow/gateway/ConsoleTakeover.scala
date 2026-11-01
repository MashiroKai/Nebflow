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
 * == 身份与粒度 ==
 * 实例 = 门户会话（`GatewaySessionStore.SessionRecord.sessionId`，HttpOnly cookie）。
 * 同浏览器刷新/多标签共享 cookie jar ⇒ 同一会话 ⇒ 静默续位（**刷新不自杀**，结构
 * 保证：owner 按会话持久、无计时器、无竞态窗）。不同电脑/浏览器 = 各自独立会话。
 * 仅 session-rail 连接参与（`WebSocketRoutes` 侧捕获 `Credential.ViaSession`）；
 * Bearer rail（CLI/脚本）不注册不被通知不竞选——零触碰零漂移。
 *
 * == claim 规则（互斥权写入点只有两个） ==
 *   1. fresh claim：owner==None ⇒ 即位；owner==他会话 且 本会话 ∉ pastOwners ⇒
 *      接管（owner 翻位 + 向旧主全部活跃连接推 `takeover` 帧，尽力送达）。
 *   2. retake：被踢面用户点【重新接管】⇒ owner 翻回 + 向前主活跃连接推帧（反向踢）
 *      + 回执。两腿同核（[[ConsoleTakeover.claimAndReverseKick]] 单源）：
 *        · ws 腿：`takeoverRetake` 帧（WebSocketRoutes 收流管拦截）——回执经本连接
 *          出站队列（连接活时零跳）；
 *        · HTTP 腿（接管必达批 2026-11-01 作者令①）：`POST /api/takeover/retake`
 *          （[[ConsoleTakeoverRoutes]]，session-rail only）——回执同步随 HTTP 响应
 *          必达，不依赖本会话 ws 存活（死连接/僵尸/重连窗口上的帧腿结构性缺口，
 *          「重新接管不一定成功」的根因面）。
 * 旧主回连（owner==他会话 且 本会话 ∈ pastOwners）**不自动反抢**：回连是机器自动
 * 行为（休眠唤醒/抖动重连），不携带用户意图；若回连即反抢，则 B 操作中 A 的后台
 * 标签醒来即可隐身夺权——正是要防的误踢。回连方落被踢面（本连接收 `takeover`
 * 帧），一键 retake 可预期。
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
  val enabled: Boolean = ConsoleTakeover.defaultEnabled
):
  /** 拥有控制权的会话（全局唯一——控制台是每引擎一个的互斥资源）。 */
  private val ownerRef: Ref[IO, Option[String]] = Ref.unsafe(None)

  /** 会话 → 其活跃连接集（仅 session-rail 连接入账）。 */
  private val connsRef: Ref[IO, Map[String, Set[String]]] = Ref.unsafe(Map.empty)

  /**
   * 曾持有过控制权的会话账（插入序，帽 [[ConsoleTakeover.PastOwnerCap]] 淘汰最旧）：
   * 「旧主回连不反抢」的判据面——A 被 B 接管后重连，落被踢面而非隐身夺权。
   */
  private val pastOwnersRef: Ref[IO, Vector[(String, Long)]] = Ref.unsafe(Vector.empty)

  /**
   * 内容帧闸（2026-10-29 作者令四修 · 隐私面）：被顶出（superseded）会话的连接
   * 对 wsHub 广播/定向扇出一律断喂对话内容——只放行接管协议帧
   * （takeover/takeoverResult）。pong/心跳与本连接直回帧（perConnJsonSend）不经此闸。
   *
   * 裁决（每帧一次；owner 命中即放行、零解析开销——热路径只是一次 Ref 读：
   * 仅 superseded 会话付 type 解析代价，稀有面）：
   *   总闸关 / owner==本会话 / owner 未定（claim 前引导窗） ⇒ 放行；
   *   其余（owner==他会话 = superseded）⇒ 仅接管协议帧放行，其余内容帧断喂。
   *
   * 语义：retake 翻位后 owner==本会话 ⇒ 闸自动放开（后续帧照常送达）；前端在
   * takeoverResult ok 后 reload 全量重同步——闸窗期的内容缺口只经 reload 愈合，
   * 不做增量续传（单实例语义：同一时刻只有一个可信实例在查看控制台）。
   */
  def gateFrame(sessionId: String, text: String): IO[Boolean] =
    if !enabled then IO.pure(true)
    else
      ownerRef.get.flatMap {
        case None => IO.pure(true) // owner 未定（claim 前引导窗）⇒ 放行——首实例就位前不饿引导帧
        case Some(o) if o == sessionId => IO.pure(true)
        case Some(_) =>
          // superseded：只放行接管协议帧（重弹 superseded / retake 裁决回执经本连接）。
          IO.pure(
            io.circe.parser
              .parse(text)
              .flatMap(_.hcursor.downField("type").as[String])
              .toOption
              .exists(ConsoleTakeover.GatePassTypes)
          )
      }
  /** 控制权读数（spec / 未来观测面用）。 */
  def owner: IO[Option[String]] = ownerRef.get

  /** 会话的活跃连接数（spec 读数）。 */
  def connCount(sessionId: String): IO[Int] = connsRef.get.map(_.getOrElse(sessionId, Set.empty).size)

  /**
   * 连接入账 + claim 裁决（`WebSocketRoutes` 在 `wsHub.register` 后、build 前调用；
   * 推帧经该连接 outbound 队列缓冲，随 serverConfig 同批送达）。禁 total 调用。
   */
  def onConnect(sessionId: String, connId: String): IO[Unit] =
    if !enabled then IO.unit
    else
      IO.uncancelable { _ =>
        for
          _ <- addConn(sessionId, connId)
          cur <- ownerRef.get
          _ <- cur match
            case None =>
              // 首实例即位：无事件。
              claim(sessionId) *> logger.info(
                s"console-takeover: session ${mask(sessionId)} claimed console control (first instance)"
              )
            case Some(c) if c == sessionId =>
              // 刷新 / 同浏览器第二标签 / 掉线重连：静默续位，零帧（刷新不自杀）。
              IO.unit
            case Some(prevOwner) =>
              pastOwnersRef.get.flatMap { past =>
                if past.exists(_._1 == sessionId) then
                  // 旧主回连不反抢：owner 不动，向回连方本连接推 superseded 通知。
                  nowMs().flatMap(now => sendTo(connId, ConsoleTakeover.takeoverFrame(now))) *>
                    logger.info(
                      s"console-takeover: session ${mask(sessionId)} reconnected as non-owner — " +
                        s"superseded notice sent to its connection (owner ${mask(prevOwner)} untouched)"
                    )
                else
                  // 新实例接管：互斥权最后写入者胜；旧主活连接尽力送达（无活连接则
                  // 旧主下次重连按 pastOwners 规则落被踢面——最终一致）。
                  for
                    conns <- connsRef.get.map(_.getOrElse(prevOwner, Set.empty))
                    now <- nowMs()
                    _ <- claim(sessionId)
                    _ <- conns.toList.traverse_(cid => sendTo(cid, ConsoleTakeover.takeoverFrame(now)))
                    _ <- logger.info(
                      s"console-takeover: session ${mask(sessionId)} took over from " +
                        s"${mask(prevOwner)} — takeover pushed to ${conns.size} conn(s)"
                    )
                  yield ()
              }
        yield ()
      }

  /**
   * 连接出账（`onFinalize` 与 `wsHub.unregister` 同拍——关闭/EOF/异常/#960 判死回收
   * 四途同归）。只摘连接账：**掉线 ≠ 让位**，owner 不动、零出帧（僵尸回收不触发提示）。
   *
   * 接管必达批（2026-11-01 作者令①）修账：原实现 `m.collect { case (sid, ids) if
   * ids.contains(connId) => … }` 的 collect 只保留命中键——**任一会话断连都会把
   * 其他会话的连接账整键抹掉**（B 活着但账没了）⇒ 之后 fresh-claim/retake 的反向
   * 踢读 `connsRef(旧主)` 恒空集 ⇒ 旧端收不到 takeover 帧、页面毫无感知地继续看
   * ——「重新接管不一定生效」的账本面根因（A4c 回归钉）。修后只重写被触碰的键，
   * 其余键原样保留。
   */
  def onDisconnect(connId: String): IO[Unit] =
    if !enabled then IO.unit
    else
      connsRef.update { m =>
        val touched = m.collect { case (sid, ids) if ids.contains(connId) => sid -> (ids - connId) }
        touched.foldLeft(m) { case (acc, (sid, ids)) => if ids.isEmpty then acc - sid else acc.updated(sid, ids) }
      }

  /**
   * 【重新接管】（收流管拦截 `takeoverRetake` 帧后调用）：owner 翻回本连接的会话 +
   * 向前主活跃连接反向踢 + 回执。未注册连接（Bearer）⇒ ok:false。
   */
  def handleRetake(connId: String, reply: Json => IO[Unit]): IO[Unit] =
    if !enabled then IO.unit
    else
      sessionOf(connId).flatMap {
        case None =>
          logger.warn(s"console-takeover: retake from unregistered conn $connId refused") *>
            reply(ConsoleTakeover.resultFrame(ok = false))
        case Some(sid) =>
          IO.uncancelable { _ =>
            for
              cur <- ownerRef.get
              _ <- cur match
                case Some(c) if c == sid =>
                  // 已是 owner（竞态窗：模态未收时所有权已回）——幂等 ok，零帧。
                  IO.unit
                case _ =>
                  for
                    _ <- claimAndReverseKick(sid, cur)
                    _ <- logger.info(
                      s"console-takeover: session ${mask(sid)} retook console control (ws leg)" +
                        cur.fold("")(p => s" — reverse takeover pushed to ${mask(p)}")
                    )
                  yield ()
              // 统一回执（already-owner 竞态腿同为幂等 ok；ok:false 只在未注册腿发）。
              _ <- reply(ConsoleTakeover.resultFrame(ok = true))
            yield ()
          }
      }

  /**
   * 【重新接管】HTTP 腿（接管必达批 2026-11-01 作者令①）：会话身份直接来自
   * HttpOnly cookie（`ConsoleTakeoverRoutes`，session-rail only）——**不要求本
   * 会话在连接账上有活连接**。修的是 ws 腿的结构性缺口：
   *   · 被顶出页面的 ws 可能已死（后台标签心跳断喂/僵尸回收/重连窗口）——帧腿
   *     的 sendWs 静默入队死 socket、回执可被出站队列溢出拆连吞掉；
   *   · HTTP 响应同步返回 = 回执必达（无队列、无心跳竞态面）。
   * 所有权翻面 + 反向踢语义与 ws 腿逐字一致（同一 [[claimAndReverseKick]] 单源）；
   * 被踢端推送尽力而为 + 重连补推（pastOwners 规则）兜底，最终一致不变。
   */
  def retakeBySession(sessionId: String): IO[ConsoleTakeover.RetakeOutcome] =
    if !enabled then IO.pure(ConsoleTakeover.RetakeOutcome.Disabled)
    else
      IO.uncancelable { _ =>
        for
          cur <- ownerRef.get
          kicked <- cur match
            case Some(c) if c == sessionId =>
              // 已是 owner（竞态窗：模态未收时所有权已回）——幂等成功，零帧。
              IO.pure(0)
            case _ =>
              for
                kicked <- claimAndReverseKick(sessionId, cur)
                _ <- logger.info(
                  s"console-takeover: session ${mask(sessionId)} retook console control (http leg) — " +
                    s"reverse takeover pushed to ${kicked} conn(s)" +
                    cur.fold("")(p => s" of ${mask(p)}")
                )
              yield kicked
        yield ConsoleTakeover.RetakeOutcome.Ok(kicked)
      }

  // ── 内部 ────────────────────────────────────────────────────────────────

  private def addConn(sessionId: String, connId: String): IO[Unit] =
    connsRef.update(m => m.updated(sessionId, m.getOrElse(sessionId, Set.empty) + connId))

  /**
   * 翻位 + 反向踢共享核（ws 腿 / HTTP 腿单源，接管必达批抽出）：推送面在翻位前
   * 定死（旧主此刻的活跃连接集），claim 后对旧主全部活跃连接尽力送达 takeover 帧。
   * 返回送达连接数（无旧主/无活连接 = 0；旧主下次重连按 pastOwners 规则落被踢面
   * ——最终一致，A9 同款兜底）。
   */
  private def claimAndReverseKick(sessionId: String, prevOwner: Option[String]): IO[Int] =
    for
      targets <- prevOwner
        .traverse(p => connsRef.get.map(_.getOrElse(p, Set.empty).toList))
        .map(_.getOrElse(Nil))
      now <- nowMs()
      _ <- claim(sessionId)
      _ <- targets.traverse_(cid => sendTo(cid, ConsoleTakeover.takeoverFrame(now)))
    yield targets.size

  private def sessionOf(connId: String): IO[Option[String]] =
    connsRef.get.map(_.collectFirst { case (sid, ids) if ids.contains(connId) => sid })

  /** 记 owner + pastOwners 账（LRU 帽内淘汰最旧）。 */
  private def claim(sessionId: String): IO[Unit] =
    nowMs().flatMap { now =>
      ownerRef.set(Some(sessionId)) *>
        pastOwnersRef.update { v =>
          val pruned = v.filterNot(_._1 == sessionId) :+ ((sessionId, now))
          if pruned.length > ConsoleTakeover.PastOwnerCap then pruned.drop(pruned.length - ConsoleTakeover.PastOwnerCap)
          else pruned
        }
    }

  private def nowMs(): IO[Long] = IO.realTime.map(_.toMillis)

  private def mask(sessionId: String): String = sessionId.take(8)

end ConsoleTakeover

object ConsoleTakeover:

  /** 【重新接管】HTTP 腿的裁决（`retakeBySession`）：Ok 带反向踢送达连接数；Disabled = 总闸关。 */
  sealed trait RetakeOutcome extends Product with Serializable
  object RetakeOutcome:
    final case class Ok(kickedConns: Int) extends RetakeOutcome
    case object Disabled extends RetakeOutcome

  /** pastOwners 账帽（淘汰最旧）：内存态、会话 ≤30d 寿命，64 富余。 */
  val PastOwnerCap: Int = 64

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

  /** 服务端→旧实例的接管帧（无 sessionId：前端 GLOBAL 路由，不抢 activeView）。 */
  def takeoverFrame(atMs: Long): Json = Json.obj("type" -> "takeover".asJson, "at" -> atMs.asJson)

  /** retake 回执帧。 */
  def resultFrame(ok: Boolean): Json = Json.obj("type" -> "takeoverResult".asJson, "ok" -> ok.asJson)

  /**
   * 内容帧闸放行集（[[ConsoleTakeover.gateFrame]]）：被顶出连接只喂接管协议帧。
   * 集中一处，禁各调用点自列（第二份判据 = 漂移源）。
   */
  private val GatePassTypes: Set[String] = Set("takeover", "takeoverResult")

  /** 收流管判别（廉价 parse + type 比对；非 JSON/异型 ⇒ false 走常规分派）。 */
  def isRetakeFrame(text: String): Boolean =
    io.circe.parser
      .parse(text)
      .flatMap(_.hcursor.downField("type").as[String])
      .toOption
      .contains("takeoverRetake")

end ConsoleTakeover
