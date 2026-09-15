package nebflow.neblink

import cats.effect.IO
import io.circe.syntax.*
import io.circe.Json
import nebflow.actor.ActorRef
import nebflow.agent.{AgentCommand, SharedResources}
import nebflow.core.NebflowLogger
import nebflow.core.tools.{MailTool, RelayExecAudit}

import scala.concurrent.duration.*

/**
 * 跨设备 Nebula 邮件的**收件腿**（device-mail 批，2026-09-15）：对端设备经服务端设备
 * **事件流**（`friend_event` 信封内 `event.type == "agent_mail"`，契约 v2.1）送达本机的
 * 载荷在此落地，两件事同时发生（作者口径「邮件直接注入给该设备的 nebula，也是蓝色气泡」）：
 *
 *   ① **注入本机 Nebula 会话**（下一 turn 边界，头行
 *      `[DEVICE-MAIL · from <from_device>]`，类型 INFO）——走**既有会话注入 API**
 *      `AgentCommand.ImmediateInput`（引擎在下一个 turn 边界消费；idle 会话直接
 *      起一轮、busy 会话并入当前轮），来源定名 `deviceMail`（前端蓝气泡标签的数据源）；
 *   ② **蓝气泡**在该注入事件的**唯一发射点**自动产生（`AgentActor` 的 injected
 *      user 帧 `{type:"user", injected:true, source:"deviceMail", …}` + 落盘
 *      `UiMessage.User`）——本批**不复用**第二套渲染机制，零双泡：
 *      标签面 = `web/js/chat.js#injectedSourceLabel` 的 `deviceMail` 显式分支
 *      （i18n「来自 <from_device> 的 Nebula」双语），气泡样式 = 既有
 *      `.bubble.injected` 蓝气泡（含亮/暗双主题）。
 *
 * 🔴 **注入失败禁静默**（作者/root 口径）：重试 `InjectAttempts` 次（间隔
 * `InjectRetryDelay`），仍失败 ⇒ WARN + **事件告警帧**
 * `{"type":"deviceMailInjectFailed", …}`（前端通知条）+ 审计行 status=failed，
 * 三条读数齐备；**绝不**把「什么都没注入」报成成功。
 *
 * 装配缝（同 `RemoteExecutor.initialize` / `FriendMessageTool.initialize` 单例形态）：
 * `GatewayMain` 在 `SharedResources` 与 `wsHub` 就绪后调用 `initialize`。未接线
 * （如纯单测环境）⇒ 注入路径**显式失败**（不静默、不假装成功）。
 *
 * 降级面（老版本对端 / 未知流量）：非 `agent_mail` 载荷静默忽略（数据通道上还有
 * 其它流量）；**是** `agent_mail` 但缺字段/类型错/`to_nebula` 非真 ⇒ 忽略 + 一行
 * **可读 WARN**（`DeviceMail.parse` 的 fail-closed 判词），不崩、不误渲染。
 */
object DeviceMailInbox:

  private val logger = NebflowLogger.forName("nebflow.neblink.devicemail")

  /** 注入重试次数（含首次尝试）。 */
  val InjectAttempts: Int = 3

  /** 注入重试间隔。 */
  val InjectRetryDelay: FiniteDuration = 2.seconds

  /** 前端告警帧类型（注入失败禁静默的告警面；`main.js` 消费为通知条）。 */
  val InjectFailedFrameType: String = "deviceMailInjectFailed"

  /** 装配缝载荷：本机资源（root 会话解析用）+ 前端广播口 + ack 发送面。
    *
    * `resources` 只被用于**读取** live root 会话（`agentRegistry` / `sessionStore`），
    * 不经任何写面。
    *
    * `ackSender`（契约 v2 ④）：本机处理完一封设备邮件后向服务端回 ack 的**唯一**出
    * 口（生产 = 既有 `NeblinkRelayTunnel.sendAck` 的 live 读取，与好友消息 ack 同缝、
    * 同帧形状）。缺省 `None` = 未接线 ⇒ **不静默**：审计行写明 `ack-not-sent`。 */
  final case class Wiring(
      resources: SharedResources,
      wsSend: Json => IO[Unit],
      ackSender: Option[String => IO[Unit]] = None
  )

  @volatile private var wiring: Option[Wiring] = None

  /** 生产装配点（`GatewayMain`）。幂等：重复调用以后一次为准（测试可重置）。 */
  def initialize(
      resources: SharedResources,
      wsSend: Json => IO[Unit],
      ackSender: Option[String => IO[Unit]] = None
  ): Unit =
    wiring = Some(Wiring(resources, wsSend, ackSender))

  /** 是否已接线（可观测/可测）。 */
  def isWired: Boolean = wiring.isDefined

  /** 测试用重置。 */
  private[nebflow] def resetForTest(): Unit = wiring = None

  // ============================================================
  // 收包入口
  // ============================================================

  /** 设备邮件帧的收包入口（契约 **v2.1**：服务端经本设备**隧道**推设备**事件流**
    * 信封）。
    *
    * 入场形态（逐字，服务端投递实证）：
    * `{"type":"friend_event","eventId":"message-<id>","event":{"payload":{<五键载荷>},"type":"agent_mail"}}`
    * ⇒ 挂载点 = `NeblinkRelayTunnel` 的 `friend_event` 分支（`DeviceMail.isAgentMailEnvelope`
    * 判定后独占该帧）。
    *
    * 🔴 **单一入场**（v2.1 ①）：本方法**只**接受事件流信封。
    *   - 契约 v1 曾按 P2P 设备数据通道（`kind` 面）设计——该面**已弃**，实现与断言零残留；
    *   - 契约 v2 曾在隧道顶层按 `type == "agent_mail"` 直收——该分支**已删**（否则同一条
    *     腿会挂成两个入口）；顶层裸 `agent_mail` 帧现在按**非本批形态**记一行可读 WARN 后
    *     忽略（零注入、零 ack）。
    *
    * 本方法对**非**设备邮件帧严格无副作用（普通 `friend_event` / 其它帧原样交回既有路径）。 */
  def handle(frame: Json): IO[Unit] =
    if DeviceMail.isAgentMailEnvelope(frame) then
      DeviceMail.parseEnvelope(frame) match
        case Left(reason) =>
          // 降级面：可读日志 + 忽略（绝不误渲染、绝不崩）。acceptor 侧同一帧还带
          // 不回执责任：畸形帧**不回 ack**（回执语义 = 「本设备已持久处理」，畸形态
          // 未处理）。
          logger.warn(
            s"[device-mail] ignored malformed '${DeviceMail.TypeAgentMail}' event: $reason " +
              "(fail-closed — nothing injected, no bubble rendered, no ack sent)"
          )
        case Right((incoming, eventId)) =>
          logger.info(
            s"[device-mail] received ${DeviceMail.TypeAgentMail} (event stream envelope) " +
              s"from_device=${incoming.fromDevice} from_device_id=${incoming.fromDeviceId} " +
              s"chars=${incoming.text.length} eventId=${eventId.getOrElse("<none>")}"
          ) *> injectWithRetry(incoming, eventId)
    else if legacyTopLevelAgentMail(frame) then
      // v2.1 单一入场的**显式负控读数**：顶层裸 `agent_mail` 帧不再是入口。
      logger.warn(
        s"[device-mail] ignored top-level '${DeviceMail.TypeAgentMail}' frame: the v2.1 entry is " +
          s"the '${DeviceMail.TypeFriendEvent}' event-stream envelope (single entry — nothing injected)"
      )
    else IO.unit

  /** 旧形态（v2 顶层直收）判别——**只为**留可读 WARN，不构成第二个入口（零注入）。 */
  private def legacyTopLevelAgentMail(frame: Json): Boolean =
    frame.hcursor.get[String](DeviceMail.KeyType).toOption.contains(DeviceMail.TypeAgentMail)

  // ============================================================
  // 注入（既有会话注入 API；重试 + 告警）
  // ============================================================

  /** 单次注入尝试：解析本机**唯一** live Nebula root 会话 → 投 `ImmediateInput`。
    * 解析不到 / 歧义 / 未接线 ⇒ `Left(可读原因)`（**显式失败**，不静默）。 */
  private def injectOnce(incoming: DeviceMail.Incoming): IO[Either[String, String]] =
    wiring match
      case None =>
        IO.pure(Left("DeviceMailInbox is not wired (no SharedResources) — injection skipped"))
      case Some(w) =>
        // 复用 MailTool 的**唯一** root 解析单点（session meta agentName == "Nebula"
        // ∧ 排除发信者自身 ∧ 唯一；0 或 ≥2 命中一律判为解析不出，禁静默挑一条）。
        MailTool.resolveNebulaRoots(w.resources, senderSessionId = "", preferredRootSid = None).flatMap {
          case List((sid, ref)) =>
            // 🔴 `ref ! msg` **本身是 IO**（tell = 把消息投进信箱的副作用）——必须被
            // 执行；`map` 里丢弃它就是「零投递的静默成功」（DeviceMailSpec 的注入
            // 读数正是钉这一条）。
            (ref ! AgentCommand.ImmediateInput(
              text = DeviceMail.injectedText(incoming.fromDevice, incoming.text),
              source = Some(DeviceMail.SourceDeviceMail),
              eventType = Some(DeviceMail.EventTypeInfo),
              sender = Some(incoming.fromDevice),
              fromUser = false
            )).as(Right(sid): Either[String, String])
          case Nil =>
            IO.pure(Left("no live Nebula root session (NEBULA_ROOT_UNRESOLVED) — nothing injected"))
          case many =>
            IO.pure(Left(s"ambiguous: ${many.size} live Nebula root sessions — nothing injected"))
        }

  private def injectWithRetry(incoming: DeviceMail.Incoming, receiptEventId: Option[String]): IO[Unit] =
    def attempt(n: Int): IO[Either[String, String]] =
      injectOnce(incoming).flatMap {
        case ok @ Right(_) => IO.pure(ok)
        case left @ Left(_) =>
          if n < InjectAttempts then
            logger.warn(
              s"[device-mail] injection attempt $n/$InjectAttempts failed — retrying in ${InjectRetryDelay.toSeconds}s"
            ) *> IO.sleep(InjectRetryDelay) *> attempt(n + 1)
          else IO.pure(left)
      }
    attempt(1).flatMap {
      case Right(sid) =>
        logger.info(
          s"[device-mail] injected into Nebula root session ${sid.take(8)} " +
            s"(from_device=${incoming.fromDevice}, source=${DeviceMail.SourceDeviceMail}, type=${DeviceMail.EventTypeInfo.toUpperCase})"
        ) *> audit(incoming, "injected", s"root=${sid.take(8)} attempts<=$InjectAttempts") *>
          sendReceipt(incoming, receiptEventId)
      case Left(reason) =>
        val detail = s"$reason (attempts=$InjectAttempts)"
        logger.warn(s"[device-mail] injection FAILED and is NOT silent: $detail") *>
          alert(incoming, detail) *>
          audit(incoming, "inject-failed", detail) *>
          // 未持久处理 ⇒ **不回 ack**（回执语义边界），只留可读行：
          logger.warn(
            s"[device-mail] ack NOT sent (injection failed, so the event is not processed): " +
              s"eventId=${receiptEventId.getOrElse("<none>")}"
          )
    }

  /** 收件侧回执（契约 v2 ④；复用既有 ack 出口与帧形状，零新帧、零新字段）。
    *  两个**非静默**分支：未接线 / 帧未带 eventId ⇒ INFO/WARN + 审计行 `ack-not-sent`。 */
  private def sendReceipt(incoming: DeviceMail.Incoming, receiptEventId: Option[String]): IO[Unit] =
    (wiring.flatMap(_.ackSender), receiptEventId) match
      case (Some(send), Some(eventId)) =>
        send(eventId)
          .flatMap(_ =>
            logger.info(s"[device-mail] ack sent to the server: eventId=$eventId (injection persisted)") *>
              audit(incoming, "ack-sent", s"eventId=$eventId")
          )
          .handleErrorWith(e =>
            logger.warn(s"[device-mail] ack send FAILED for eventId=$eventId: ${e.getMessage}") *>
              audit(incoming, "ack-send-failed", s"eventId=$eventId; err=${e.getMessage}")
          )
      case (None, Some(eventId)) =>
        logger.warn(
          s"[device-mail] ack NOT sent (ack sender not wired): eventId=$eventId — the server leg will see no receipt"
        ) *> audit(incoming, "ack-not-sent", s"eventId=$eventId; reason=ack sender not wired")
      case (_, None) =>
        logger.info(
          "[device-mail] injection persisted but the frame carries no eventId ⇒ no ack (receipt uncorrelated)"
        ) *> audit(incoming, "ack-not-sent", "reason=frame carries no eventId")

  /** 事件告警帧（前端通知条；`wsSend` 缺席 ⇒ 只余 WARN + 审计两条读数）。 */
  private def alert(incoming: DeviceMail.Incoming, reason: String): IO[Unit] =
    wiring match
      case None => IO.unit
      case Some(w) =>
        w.wsSend(
          Json.obj(
            "type" -> InjectFailedFrameType.asJson,
            "fromDevice" -> incoming.fromDevice.asJson,
            "fromDeviceId" -> incoming.fromDeviceId.asJson,
            "attempts" -> InjectAttempts.asJson,
            "reason" -> reason.asJson,
            "timestamp" -> System.currentTimeMillis().asJson
          )
        ).handleErrorWith(e => logger.warn(s"[device-mail] alert frame dropped: ${e.getMessage}"))

  /** 审计行（④ 接收腿一条；`RelayExecAudit` 同族 = 设备通道审计的既有落面）。
    * `sourceDeviceId` = **远端**设备（本事件是它发来的），`targetDeviceId` = 本机。 */
  private def audit(incoming: DeviceMail.Incoming, status: String, detail: String): IO[Unit] =
    val localIo: IO[String] = wiring match
      case None => IO.pure("unknown")
      case Some(w) =>
        w.resources.neblinkService match
          case None    => IO.pure("unknown")
          case Some(ns) => ns.identity.map(_.deviceId).handleErrorWith(_ => IO.pure("unknown"))
    localIo.flatMap { target =>
      RelayExecAudit.record(
        sourceDeviceId = incoming.fromDeviceId,
        targetDeviceId = target,
        via = "relay",
        action = s"DeviceMail.inject.$status",
        command = s"from_device=${incoming.fromDevice}; chars=${incoming.text.length}; $detail",
        projectRoot = "",
        cwd = Option(System.getProperty("user.dir")).getOrElse("")
      )
    }.handleErrorWith(e => logger.warn(s"[device-mail] audit line dropped: ${e.getMessage}"))

end DeviceMailInbox
