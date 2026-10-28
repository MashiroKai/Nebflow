package nebflow.neblink

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json, JsonObject}
import nebflow.agent.SubAgentTaskStore
import nebflow.core.device.{DeviceHmac, DeviceRegistry}
import nebflow.shared.{NebflowLogger, SubAgentTask}

/**
 * AgentSessionSync —— **后台子智能体会话的跨设备投影通道**（台账 #929，2026-10-26
 * 作者令：「多设备设计的核心就是透明，跑在哪个设备上，那我们的显示都应该都能看到。
 * 不应该说只显示Mac引擎自带的子智能体」）。
 *
 * 现状断点：delegate 会话原生跑在远端设备（如 KAI）时，其引擎自己的后台子智能体
 * 面板有行（SubAgentTaskStore 权威），但**对端引擎**（如 Mac）对这批会话零感知——
 * 可见性断在设备边界。本对象把「远端 delegate 会话的状态摘要」经既有配对通道投影
 * 过去，对端把远端行**并入后台子智能体注册表面板**（标设备角标）。
 *
 * 通道（**全复用既有设施，零新通道**，[[AgentIdentitySync]] 同族同构）：设备数据
 * 通道（presence `sendData` 优先 + relay `relayNotify` 兜底，DeviceDelegate
 * .sendToDevice / AgentIdentitySync 同款双腿）——一种载荷 `session-sync`（单会话
 * 全量摘要），两种触发：
 *
 *   · **推**（写后外推）：SubAgentTaskStore 记账写入（record/updateStatus/
 *     markRevived/remove→墓碑/cancel 族）成功后 fire-and-forget 外推到全部已配对
 *     设备（store 写钩单点，[[SubAgentTaskStore.setWriteHook]]）；
 *   · **对账推**（重连/发现即收敛）：peer 集合变化回调（boot 后隧道首联、对端
 *     重连、新配对都会触发）把本机全部任务行逐一外推——对端 LWW 收敛；离线对端
 *     在它自己下次上线的对账推里补齐（两腿对称，无定向重试、零新增定时器——
 *     设计零超时）。
 *
 * 摘要纪律（红线）：
 *   · **摘要级**：只投影 id/标题/状态/起止时间/重试计数/设备归属——prompt 全文、
 *     会话内容**永不上机**（单行 <2KB 闸，spec 钉）；标题截断 200 字符；
 *   · **验签**：载荷按配对 sharedKey 做 HMAC（DeviceHmac，ts ±90s 窗 + nonce），
 *     未配对/伪造/过期帧一律丢弃（可读 WARN，不含载荷体）；
 *   · **防回声靠结构**：对端收敛写入落在 [[RemoteAgentSessions]] 投影仓，不经
 *     SubAgentTaskStore ⇒ 写钩永不因远端帧触发，绝不回推；
 *   · **同会话本地行胜**：DeviceDelegate 形态下同一 spawn 在两侧各有任务行
 *     （host 侧 recordTask + 执行侧 recordTask），前端按 sessionId 去重、本地行
 *     优先（更富、可交互）——投影侧不做抑制，语义对齐发生在渲染层单点。
 *
 * 断联语义：对端离线时投影仓**保留最后态**（不消失、不假装死亡），前端标
 * 「设备离线」角标（在线性 = 收端按 peer 新鲜度现算，随 peer 变化重播帧）；
 * 重连后对端对账推补齐（LWW 收敛）。终态落账：收端投影仓持久化终态行
 * （completed/failed/cancelled + 墓碑 removed），快照帧只回在飞行 + 揭示窗内
 * 的终态行（与本地 registry「完成即注销」的快照口径同律）。
 */
object AgentSessionSync:

  private val logger = NebflowLogger.forName("nebflow.neblink.session-sync")

  /** 数据通道名（presence sendData / relay Notify 的 channel 维度）。 */
  val ChannelName: String = "agent-session"

  /** 载荷 kind（handleDataMessage 的 kind 判别维度）。 */
  val KindSync: String = "session-sync"

  /** 投影标题截断（单行 <2KB 闸的一部分；显示面本来就单行截断）。 */
  val MaxTitleChars: Int = 200

  /** 前端 UI 帧型（引擎 → 浏览器，wsHub.broadcast）。 */
  val FrameType: String = "remoteAgents"

  /**
   * 终态行在快照/UI 帧里的**揭示窗**（毫秒）：与本地「registry 完成即注销」的
   * 快照口径同律——实时终态帧到达后行翻终态、前端 2s 清理；刷新恢复时窗内终态行
   * 仍可见（跨设备延迟余量），窗外只留投影仓账面。非超时设计：窗只作用于「显示
   * 过滤」，不删任何账。
   */
  val TerminalRevealMs: Long = 30_000L

  /** 投影仓单设备行数帽（含终态；超出剪最旧终态、再剪最旧——账面有界）。 */
  val MaxRowsPerDevice: Int = 200

  // ── 载荷（构建/解析/验签全部纯函数，spec 直测）────────────────────────────

  /** 单会话摘要载荷（total 语义：endedAt None = 未终态）。 */
  final case class SessionPayload(
    sessionId: String,
    /** 批次标题/任务描述首行（截断 MaxTitleChars）——非 prompt 全文。 */
    title: String,
    /** 任务域源（delegate|subtask|subagent——前端 kind 徽标词的映射源）。 */
    source: String,
    /** SubAgentTask.status 原值（running/completed/failed/restarting/ready-to-merge/cancelled/removed）。 */
    status: String,
    startedAt: Long,
    endedAt: Option[Long],
    /** LWW 戳 = SubAgentTask.statusAt（当前状态的写入时刻）。 */
    updatedAt: Long,
    retryCount: Int,
    fromDeviceId: String,
    /** 发端设备显示名（收端角标直读，无需查对端表）。 */
    deviceName: String,
    tsSec: Long,
    nonce: String,
    sig: String
  )

  /** 签名规范串（签名与验签的唯一拼装点；字段值入串而非整段 JSON）。 */
  def canonicalOf(p: SessionPayload): String =
    s"$ChannelName|${p.fromDeviceId}|${p.tsSec}|${p.nonce}|${p.sessionId}|${p.title}|${p.source}|${p.status}|${p.startedAt}|${p.endedAt
        .getOrElse("")}|${p.updatedAt}|${p.retryCount}|${p.deviceName}"

  /** SubAgentTask → **未签名**摘要载荷（纯组装；标题截断单点）。 */
  def buildPayload(task: SubAgentTask, fromDeviceId: String, deviceName: String, nowMs: Long): SessionPayload =
    SessionPayload(
      sessionId = task.taskId,
      title = firstLine(task.description).take(MaxTitleChars),
      source = task.source,
      status = task.status,
      startedAt = task.spawnedAt,
      endedAt = task.completedAt,
      updatedAt = task.statusAt.getOrElse(task.spawnedAt),
      retryCount = task.retryCount,
      fromDeviceId = fromDeviceId,
      deviceName = deviceName,
      tsSec = nowMs / 1000,
      nonce = newNonce(),
      sig = ""
    )

  /** 显示名契约同款（bgAgents.bgFirstLine 的镜像）：只取首行，空白安全。 */
  private def firstLine(text: String): String =
    val s = Option(text).getOrElse("").trim
    if s.isEmpty then ""
    else
      val nl = s.indexOf('\n')
      (if nl == -1 then s else s.slice(0, nl)).trim

  /** 以「本机与 `peerDeviceId` 的配对 key」签署载荷（每目标一份，identity 同律）。 */
  def signedFor(p: SessionPayload, peerDeviceId: String): SessionPayload =
    p.copy(sig = DeviceHmac.sign(DeviceRegistry.secretForSync(peerDeviceId).getOrElse(""), canonicalOf(p)))

  private def newNonce(): String = java.util.UUID.randomUUID().toString.replace("-", "")

  /** 宽容解析（缺字段/类型错 ⇒ None——收端 fail-closed，同 DeviceMail.parse 纪律）。 */
  def parsePayload(json: Json): Option[SessionPayload] =
    val c = json.hcursor
    val kind = c.downField("kind").as[String].getOrElse("")
    if kind != KindSync then None
    else
      for
        sessionId <- c.downField("sessionId").as[String].toOption.filter(_.nonEmpty)
        title <- c.downField("title").as[String].toOption
        source <- c.downField("source").as[String].toOption
        status <- c.downField("status").as[String].toOption.filter(_.nonEmpty)
        startedAt <- c.downField("startedAt").as[Long].toOption
        endedAt = c.downField("endedAt").as[Option[Long]].toOption.flatten
        updatedAt <- c.downField("updatedAt").as[Long].toOption
        retryCount <- c.downField("retryCount").as[Int].toOption
        fromDeviceId <- c.downField("fromDeviceId").as[String].toOption
        deviceName = c.downField("deviceName").as[String].toOption.getOrElse("")
        tsSec <- c.downField("ts").as[Long].toOption
        nonce <- c.downField("nonce").as[String].toOption.filter(_.nonEmpty)
        sig <- c.downField("sig").as[String].toOption.filter(_.nonEmpty)
      yield SessionPayload(
        sessionId,
        title,
        source,
        status,
        startedAt,
        endedAt,
        updatedAt,
        retryCount,
        fromDeviceId,
        deviceName,
        tsSec,
        nonce,
        sig
      )

    end if

  end parsePayload

  /** 编码上机（kind 信封 + 全字段平铺）。 */
  def encodePayload(p: SessionPayload): Json =
    Json.fromJsonObject(
      JsonObject(
        "kind" -> KindSync.asJson,
        "sessionId" -> p.sessionId.asJson,
        "title" -> p.title.asJson,
        "source" -> p.source.asJson,
        "status" -> p.status.asJson,
        "startedAt" -> p.startedAt.asJson,
        "endedAt" -> p.endedAt.asJson,
        "updatedAt" -> p.updatedAt.asJson,
        "retryCount" -> p.retryCount.asJson,
        "fromDeviceId" -> p.fromDeviceId.asJson,
        "deviceName" -> p.deviceName.asJson,
        "ts" -> p.tsSec.asJson,
        "nonce" -> p.nonce.asJson,
        "sig" -> p.sig.asJson
      )
    )

  /** 验签（配对 key + ts 窗）。None = 通过；Some = 拒收原因（可读、不含载荷体）。 */
  def verifyPayload(p: SessionPayload, nowMs: Long): Option[String] =
    if !DeviceHmac.tsFresh(p.tsSec.toString, nowMs) then Some(s"stale ts (${p.tsSec})")
    else
      DeviceRegistry.secretForSync(p.fromDeviceId) match
        case None => Some(s"sender device '${p.fromDeviceId}' not paired")
        case Some("") => Some("empty shared key")
        case Some(key) =>
          if DeviceHmac.verify(key, canonicalOf(p), p.sig) then None
          else Some("signature mismatch")

  /** 单载荷字节读数（<2KB 闸的观测面；spec 断言用）。 */
  def payloadBytes(p: SessionPayload): Int = encodePayload(p).noSpaces.getBytes("UTF-8").length

  // ── 装配缝（AgentIdentitySync 同款单例形态；private[neblink] 供 spec 注入）──

  @volatile private[neblink] var sendFn: (String, Json) => IO[Boolean] = null
  @volatile private[neblink] var selfIdFn: IO[String] = IO.pure("")
  @volatile private[neblink] var deviceNameFn: IO[String] = IO.pure("")
  @volatile private[neblink] var allTasksFn: IO[List[SubAgentTask]] = IO.pure(Nil)

  /** 设备在线判读缝（收端 UI 帧的 deviceOnline 现算面；默认 ns 新鲜度谓词）。 */
  @volatile private[neblink] var onlineFn: String => IO[Boolean] = _ => IO.pure(true)
  @volatile private[neblink] var panelBroadcastFn: Json => IO[Unit] = null
  @volatile private[neblink] var store: RemoteAgentSessions = RemoteAgentSessions.unwired

  /**
   * 收发面装配（GatewayMain boot 单点调用，AgentIdentitySync.initialize 同缝同序）：
   * ① 投影仓载盘；② 数据通道 handler（收件面）；③ peer 变化回调（对账推 + 在线
   * 角标重播）；④ SubAgentTaskStore 写后外推钩。未接线（NebLink 未配置）⇒ WARN +
   * 跳过（推送/收发面全变 no-op，单机形态零行为——store 写钩也不装，记账面零触碰）。
   */
  def initialize(ns: Option[NeblinkService], taskStore: SubAgentTaskStore, panelBroadcast: Json => IO[Unit]): Unit =
    ns match
      case None =>
        logger.warnSync("AgentSessionSync.initialize skipped: NebLink is not configured (no device network)")
      case Some(svc) =>
        sendFn = (deviceId, payload) => sendTwoLeg(svc, deviceId, payload)
        selfIdFn = svc.identity.map(_.deviceId)
        deviceNameFn = svc.identity.map(_.deviceName)
        allTasksFn = taskStore.findAllTasks()
        onlineFn = deviceOnlineOf(svc)
        panelBroadcastFn = panelBroadcast
        store = RemoteAgentSessions.dataRootStore
        // 收件面：unsafeRunSync 注册（DeviceDelegate/AgentIdentitySync 同款纪律——
        // 本语境是 Unit，IO 返回值被丢弃即注册面静默缺失）。
        svc
          .addDataHandler(payload => handlePayload(payload))
          .unsafeRunSync()(using
            cats.effect.unsafe.implicits.global
          )
        // peer 变化（boot 后首联/对端重连/新配对/离线）⇒ 对账推 + 在线角标重播。
        // 两腿同拍（identity 同构）：推是幂等全量，收端 LWW 收敛。
        svc
          .addPeerChangeCallback(
            pushAllSessions().attempt.void *> broadcastRemoteRows()
          )
          .unsafeRunSync()(using cats.effect.unsafe.implicits.global)
        // 写后外推钩（本批唯一的 store 侵入面；未接线不装 = 记账面零触碰）。
        taskStore.setWriteHook(task => pushTask(task))
        // 投影仓载盘（boot 一次；载盘失败 = 空仓起步，可读 WARN 不楔死）。
        store.load().unsafeRunSync()(using cats.effect.unsafe.implicits.global)
        logger.infoSync(
          s"AgentSessionSync faces armed (channel: $ChannelName, kind: $KindSync, " +
            s"projected rows: ${store.rows().size})"
        )

  /** DeviceDelegate.sendToDevice 同款双腿：presence 优先（送达判真），relay 兜底。 */
  private def sendTwoLeg(svc: NeblinkService, deviceId: String, payload: Json): IO[Boolean] =
    svc.sendData(deviceId, ChannelName, payload).flatMap {
      case true => IO.pure(true)
      case false =>
        svc.relayClientOpt match
          case Some(client) =>
            client.relayNotify(deviceId, ChannelName, payload).flatMap {
              case Right(_) => IO.pure(true)
              case Left(err) =>
                logger.debug(s"session-sync relay fallback failed for $deviceId: $err").as(false)
            }
          case None => IO.pure(false)
    }

  /** peer 新鲜度谓词（收端在线角标的权威判据；未知设备 = 离线——不假装在线）。 */
  private def deviceOnlineOf(svc: NeblinkService): String => IO[Boolean] =
    (deviceId: String) =>
      for
        peers <- svc.peers
        intervalSec <- svc.neblinkConfig.map(_.syncIntervalSec)
      yield peers.find(_.deviceId == deviceId) match
        case Some(peer) => NeblinkService.isPeerOnline(peer, System.currentTimeMillis(), intervalSec)
        case None => false

  // ── 推送面（store 写钩 / peer 变化回调调用；未装配 = no-op）────────────────

  /**
   * 单会话摘要外推（fire-and-forget 语义由调用方（store 钩 .start）保证；失败只留
   * 元数据读数，绝不阻塞记账路径）。墓碑（status=removed）照推——对端退役行的
   * 显式证据。
   * 复验批注（batchA 2026-10-27）：未装配判读从**构建时**移进 **运行时**——原稿
   * `if sendFn == null then IO.unit else for…` 的分叉在 def 调用瞬间落定，而
   * sendFn 的调用发生在 IO 运行瞬间；任何「先构建、initialize 后运行」的调用序
   * （spec `IO.delay{…} *> pushTask(…)` 实证 NPE）都会踩空。现在每运行判一次并
   * 捕获本次的 fn（297 行读一次，不二次竞读）。
   */
  def pushTask(task: SubAgentTask): IO[Unit] =
    IO(Option(sendFn)).flatMap {
      case None => IO.unit
      case Some(fn) =>
        for
          selfId <- selfIdFn.attempt.map(_.getOrElse(""))
          deviceName <- deviceNameFn.attempt.map {
            case Right(n) if n.nonEmpty => n
            case _ => selfId // 设备名缺席时以 id 兜底（角标可读性底线）
          }
          unsigned = buildPayload(task, selfId, deviceName, System.currentTimeMillis())
          targets = DeviceRegistry.loadSync.map(_.deviceId).filterNot(_ == selfId)
          _ <- logger
            .info(
              s"session-sync push: session=${unsigned.sessionId} status=${unsigned.status} " +
                s"updatedAt=${unsigned.updatedAt} bytes=${payloadBytes(unsigned)} targets=${targets.size}"
            )
            .flatMap { _ =>
              // 每目标单独签署（配对 key 每对一份——见 signedFor）。
              targets.traverse_(t =>
                fn(t, encodePayload(signedFor(unsigned, t))).flatMap {
                  case true => IO.unit
                  case false => logger.debug(s"session-sync: device $t unreachable (offline?)")
                }
              )
            }
        yield ()
    }

  /**
   * 对账推：本机全部任务行逐一外推（peer 变化回调触发）。单飞闸防发现循环抖动下的
   * 帧暴（identity 同款：进行中 ⇒ 直接跳过，下一拍 peer 变化自然再推——对账推是
   * 幂等全量，跳过一拍无收敛风险）。
   */
  private val reconcileInFlight = new java.util.concurrent.atomic.AtomicBoolean(false)

  def pushAllSessions(): IO[Unit] =
    if sendFn == null then IO.unit
    else if !reconcileInFlight.compareAndSet(false, true) then
      logger.debug("session-sync reconcile: already in flight, skipping this round")
    else
      allTasksFn.attempt
        .flatMap {
          case Right(tasks) => IO.pure(tasks)
          case Left(e) =>
            logger.warn(s"session-sync reconcile: task enumeration failed: ${e.getMessage}").as(Nil)
        }
        .flatMap { tasks =>
          if tasks.isEmpty then IO.unit
          else
            logger
              .info(s"session-sync reconcile: pushing ${tasks.size} session row(s) on peer change")
              .productR(tasks.traverse_(pushTask))
        }
        .guarantee(IO(reconcileInFlight.set(false)))

  // ── 收件面（对端帧落地：验签 → LWW 收敛 → 变更即广播 UI 帧；零回推）─────────

  private[neblink] def handlePayload(json: Json): IO[Unit] =
    parsePayload(json) match
      case None => IO.unit // 非 session-sync / 结构坏 —— 数据通道上有别的流量，静默
      case Some(p) =>
        val nowMs = System.currentTimeMillis()
        verifyPayload(p, nowMs) match
          case Some(reason) =>
            logger.warn(s"session-sync frame REJECTED from=${p.fromDeviceId} session=${p.sessionId}: $reason")
          case None =>
            store.apply(p).flatMap { applied =>
              if !applied then
                logger
                  .debug(s"session-sync: stale frame skipped session=${p.sessionId} updatedAt=${p.updatedAt}")
                  .void
              else
                logger
                  .info(
                    s"session-sync applied: session=${p.sessionId} status=${p.status} " +
                      s"updatedAt=${p.updatedAt} from=${p.fromDeviceId}"
                  )
                  .productR(broadcastRemoteRows())
            }
        end match

  // ── UI 帧面（收端浏览器：remoteAgents 帧；投影仓 → 行集 + 在线角标现算）──────

  /**
   * 构建 `remoteAgents` UI 帧（投影仓空 ⇒ None——零行零帧，不造空噪帧）。
   * 在线性**发送时刻现算**（不落仓——新鲜度是传输面状态，不是账面状态）。
   * 复验批注（batchA 2026-10-27）：行集读数用 IO.defer 推迟到**运行时**——原稿
   * `val rows = store.rows()…` 在 IO 构建（def 调用）瞬间急切求值，任何「先构帧
   * 后播种」的组合序都会静默冻结空帧（spec 实证：frame=None 而 store 4 行）。
   */
  def remoteRowsFrame(nowMs: Long = System.currentTimeMillis()): IO[Option[Json]] =
    IO.defer {
      val rows = store.rows().filter(frameVisible(_, nowMs))
      if rows.isEmpty then IO.pure(None)
      else
        rows
          .traverse { r =>
            onlineFn(r.fromDeviceId).map(on => remoteRowJson(r, on))
          }
          .map(agents => Some(Json.obj("type" -> FrameType.asJson, "agents" -> agents.asJson)))
    }

  /** 投影仓行 → UI 帧行（快照显示过滤：在飞行 + 揭示窗内终态；墓碑不进帧）。 */
  private def frameVisible(r: RemoteAgentSessions.Row, nowMs: Long): Boolean =
    val inFlight = Set("running", "restarting", "ready-to-merge")
    if inFlight.contains(r.status) then true
    else if r.status == SubAgentTaskStore.StatusRemoved then false
    else r.endedAt.exists(end => nowMs - end <= TerminalRevealMs) // 终态揭示窗

  private def remoteRowJson(r: RemoteAgentSessions.Row, online: Boolean): Json =
    Json.obj(
      "sessionId" -> r.sessionId.asJson,
      "title" -> r.title.asJson,
      "source" -> r.source.asJson,
      "status" -> r.status.asJson,
      "startedAt" -> r.startedAt.asJson,
      "endedAt" -> r.endedAt.asJson,
      "updatedAt" -> r.updatedAt.asJson,
      "retryCount" -> r.retryCount.asJson,
      "deviceId" -> r.fromDeviceId.asJson,
      "deviceName" -> r.deviceName.asJson,
      "deviceOnline" -> online.asJson
    )

  /** 现算并广播一帧（行集空不广播；未装配 = no-op）。 */
  def broadcastRemoteRows(): IO[Unit] =
    Option(panelBroadcastFn) match
      case Some(broadcast) =>
        remoteRowsFrame().flatMap {
          case Some(frame) =>
            broadcast(frame).handleError(e =>
              logger.warnSync(s"session-sync: remoteAgents broadcast failed: ${e.getMessage}")
            )
          case None => IO.unit
        }
      case None => IO.unit

  /**
   * 快照回复的捎带面（`getActiveAgents` 处理器用）：把远端行走**本连接** wsSend
   * 直发（刷新恢复路径——与 activeAgents 快照同拍到达，前端零新请求型）。
   */
  def pushRemoteRowsTo(wsSend: Json => IO[Unit]): IO[Unit] =
    remoteRowsFrame().flatMap {
      case Some(frame) => wsSend(frame).handleError(_ => IO.unit)
      case None => IO.unit
    }

end AgentSessionSync

/**
 * RemoteAgentSessions —— 远端会话投影仓（**收端账面**，#929）。
 *
 * 键 = (fromDeviceId, sessionId)；LWW 纪律与 identity-sync 同款：
 *   · 仓内无行 ⇒ 收（**首见即收**，戳旧也收——乱序到达的 spawn 行不得因先到的
 *     更新行而永久丢失）；
 *   · 仓内行的戳严格更新 ⇒ 收（覆盖）；等戳 ⇒ 收（幂等重放无害）；更旧 ⇒ 丢弃。
 * 持久面 = `<dataRoot>/neblink/remote-agent-sessions.json`（整表原子写，
 * AtomicJson 同族）；断联语义的承载者——对端离线时行保留最后态（不删不猜），
 * 重启后载盘恢复，角标由发送时刻新鲜度现算。终态/墓碑落账保留（有界：单设备
 * MaxRowsPerDevice 行，超出剪最旧终态、再剪最旧）。
 */
object RemoteAgentSessions:

  final case class Row(
    sessionId: String,
    title: String,
    source: String,
    status: String,
    startedAt: Long,
    endedAt: Option[Long],
    updatedAt: Long,
    retryCount: Int,
    fromDeviceId: String,
    deviceName: String
  )

  object Row:

    def of(p: AgentSessionSync.SessionPayload): Row =
      Row(
        p.sessionId,
        p.title,
        p.source,
        p.status,
        p.startedAt,
        p.endedAt,
        p.updatedAt,
        p.retryCount,
        p.fromDeviceId,
        p.deviceName
      )

  // ── codecs（手写宽容：缺键逐字段兜默认，禁整文件静默消失）──────────────────
  private given Encoder[Row] = Encoder.instance { r =>
    Json.obj(
      "sessionId" -> r.sessionId.asJson,
      "title" -> r.title.asJson,
      "source" -> r.source.asJson,
      "status" -> r.status.asJson,
      "startedAt" -> r.startedAt.asJson,
      "endedAt" -> r.endedAt.asJson,
      "updatedAt" -> r.updatedAt.asJson,
      "retryCount" -> r.retryCount.asJson,
      "fromDeviceId" -> r.fromDeviceId.asJson,
      "deviceName" -> r.deviceName.asJson
    )
  }

  private given Decoder[Row] = Decoder.instance { c =>
    for
      sessionId <- c.downField("sessionId").as[String]
      title = c.downField("title").as[Option[String]].toOption.flatten.getOrElse("")
      source = c.downField("source").as[Option[String]].toOption.flatten.getOrElse("")
      status <- c.downField("status").as[String]
      startedAt = c.downField("startedAt").as[Option[Long]].toOption.flatten.getOrElse(0L)
      endedAt = c.downField("endedAt").as[Option[Long]].toOption.flatten
      updatedAt <- c.downField("updatedAt").as[Long]
      retryCount = c.downField("retryCount").as[Option[Int]].toOption.flatten.getOrElse(0)
      fromDeviceId <- c.downField("fromDeviceId").as[String]
      deviceName = c.downField("deviceName").as[Option[String]].toOption.flatten.getOrElse("")
    yield Row(sessionId, title, source, status, startedAt, endedAt, updatedAt, retryCount, fromDeviceId, deviceName)
  }

  /** 未装配单例（单机形态零行为：apply 恒 false、rows 恒 Nil）。 */
  val unwired: RemoteAgentSessions = new RemoteAgentSessions(None)

  /** 数据根仓（boot 装配单点；持久面 = neblink/remote-agent-sessions.json）。 */
  def dataRootStore: RemoteAgentSessions =
    new RemoteAgentSessions(Some(nebflow.shared.PathUtil.dataRoot / "neblink" / "remote-agent-sessions.json"))

  /** spec 工厂（注入自定义持久路径；None = 纯内存）。 */
  def at(path: Option[os.Path]): RemoteAgentSessions = new RemoteAgentSessions(path)

end RemoteAgentSessions

/**
 * 投影仓实例（见伴生对象纪律注）。线程模型：单 ReentrantLock 串行化读-改-写
 * （SubAgentTaskStore 同款纪律）。
 */
final class RemoteAgentSessions private (path: Option[os.Path]):
  import RemoteAgentSessions.*

  private val logger = NebflowLogger.forName("nebflow.neblink.session-store")
  private val lock = new java.util.concurrent.locks.ReentrantLock()
  private var loaded = false
  private var rowsMap: Map[(String, String), Row] = Map.empty

  private def withLock[A](body: => A): IO[A] = IO.blocking {
    lock.lock()
    try body
    finally lock.unlock()
  }

  /**
   * 载盘（幂等；文件缺席/坏 = 空仓起步——fail-soft，绝不楔死）。
   * 复验批注（batchA 2026-10-27）：载盘体抽成 [[loadLocked]]（纯同步、须持锁），
   * 本 IO 面只是 withLock 外皮——原先 apply 锁内经 ensureLoaded 嵌套起第二个
   * runtime（load().unsafeRunSync() inside IO.blocking body）在套件/单线程池形态
   * 下互等死锁（spec 31s 超时实证），生产形态同属潜在饥饿点。
   */
  def load(): IO[Unit] = withLock(loadLocked())

  /**
   * 载盘同步体（**须持锁调用**——withLock 可重入，apply/ensureLocked 直接复用，
   * 绝不在锁内再起 runtime）。
   */
  private def loadLocked(): Unit =
    loaded = true
    path.foreach { p =>
      if os.exists(p) then
        io.circe.parser.decode[List[Row]](os.read(p)) match
          case Right(rows) => rowsMap = rows.map(r => (r.fromDeviceId, r.sessionId) -> r).toMap
          case Left(err) => logger.warnSync(s"remote-agent-sessions decode failed (${err.getMessage}) — starting empty")
    }

  def rows(): List[Row] = rowsMap.values.toList.sortBy(r => (r.fromDeviceId, r.updatedAt))

  /**
   * LWW 收敛（返回是否改变仓——空转帧不广播的判据）。
   * 首见即收（乱序守卫）；有行按戳裁定（旧 ⇒ 丢，等/新 ⇒ 覆盖）。
   */
  def apply(p: AgentSessionSync.SessionPayload): IO[Boolean] = withLock {
    ensureLoaded()
    val key = (p.fromDeviceId, p.sessionId)
    rowsMap.get(key) match
      case Some(existing) if existing.updatedAt > p.updatedAt => false
      case _ =>
        rowsMap = rowsMap + (key -> Row.of(p))
        pruneLocked(p.fromDeviceId)
        persistLocked()
        true
  }

  /** 剪最旧终态、再剪最旧（单设备行数帽；账面有界）。 */
  private def pruneLocked(deviceId: String): Unit =
    val mine = rowsMap.values.toList.filter(_.fromDeviceId == deviceId)
    val over = mine.size - AgentSessionSync.MaxRowsPerDevice
    if over > 0 then
      val terminal = Set("completed", "failed", "cancelled", SubAgentTaskStore.StatusRemoved)
      val ordered = mine.sortBy(r => (if terminal.contains(r.status) then 0 else 1, r.updatedAt))
      ordered.take(over).foreach(r => rowsMap = rowsMap - ((r.fromDeviceId, r.sessionId)))

  private def ensureLoaded(): Unit =
    if !loaded then loadLocked()

  private def persistLocked(): Unit = path.foreach { p =>
    try
      os.makeDir.all(p / os.up)
      nebflow.core.AtomicJson
        .writeSync(p, rowsMap.values.toList.sortBy(r => (r.fromDeviceId, r.updatedAt)).asJson.noSpaces)
    catch case e: Exception => logger.warnSync(s"remote-agent-sessions persist failed: ${e.getMessage}")
  }
end RemoteAgentSessions
