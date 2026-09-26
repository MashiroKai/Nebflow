package nebflow.neblink

import cats.effect.{IO, Ref}
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.{ActorRef, AgentCommand}
import nebflow.agent.SharedResources
import nebflow.core.tools.{MailTool, RelayExecAudit}
import nebflow.shared.{DeviceMail, NebflowLogger, Retry}

import scala.collection.immutable.Queue
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
 * 🔴 **事件号去重闸**（S1，2026-09-18 作者第二单缺陷 B）——重登后旧邮件被**逐条重投**
 * 的**主因修复**：服务端在隧道重连时按 at-least-once 重播「尚未脱账」的事件（本机生产
 * 日志实证：重连成功后 **4 ms** 跟入补投；好友面同期 131 次 `duplicate_eventId`、
 * 本腿 **0** 次）。好友腿有 `FriendService` 的 `guard.dedupe(eventId)` 把重放静默吸收，
 * 本腿此前**没有**这道闸 ⇒ 每重播一次就再注入一次（多一个蓝气泡、多跑一轮模型与工具）。
 * 现在：同一 `eventId` 二次送达 ⇒ **零注入** + 一行 INFO（`reason=duplicate_eventId`）
 * + 一行审计，**但仍然补发一次 ack**（不 ack ⇒ 服务端永不脱账、重放无限循环；与
 * `FriendService.onFriendEvent` 重复支同规则）。账本 = **进程内** `Ref`（🔴 本批
 * **不落盘**：落盘与存活期 = 作者待裁项 F2）；见 [[Claim]] / [[claimEvent]]。
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

  // ============================================================
  // 事件号去重闸（S1，2026-09-18）
  // ============================================================

  /**
   * 已处理事件号的容量上限（条数）：与同仓先例 `FriendMessagingGuard`
   * （`FriendService.scala:1532-1538`，`maxSeenEvents = 2048`）同量级、同形态
   * ——进程内 `Ref` + 按条数裁剪最旧，防无界增长。
   */
  val MaxClaimedEvents: Int = 2048

  /**
   * 认领结局（判重三态）：`Fresh` = 首见（已认领，须注入）；`InFlight` = 同一事件号
   * **正在**注入（并发同帧：跳过注入、**不**提前 ack —— 该事件尚未落地，由在飞的那次
   * 尝试决定 ack 或撤回）；`Done` = 已注入过（服务端重放：跳过注入 + **补发** ack）。
   */
  enum Claim:
    case Fresh, InFlight, Done

  /**
   * 去重账本（**进程内**；🔴 本批不落盘）。
   *
   * 语义 = 「本设备**已处理**该事件」（与 `injectWithRetry` 失败支「未处理 ⇒ 不回执」
   * 同一判词）——不是「已收到帧」：注入未落地的事件会被 [[releaseEvent]] 撤回，
   * 使服务端的重放仍能重试注入（禁把「没注入成功」的事件吞成「已处理」而回假 ack）。
   *
   * 🔴 存活期 = 本进程：重启即清空 ⇒ 重启后同一条旧事件仍会注入一次（「重登 ≠ 重启」；
   * 落盘与否 = 作者待裁项 F2，本批不实现）。
   */
  private final case class Ledger(inFlight: Set[String], done: Queue[String])

  private val ledger: Ref[IO, Ledger] = Ref.unsafe(Ledger(Set.empty, Queue.empty))

  /**
   * 事件号判重 + 认领（**单原子** `modify`：并发同帧不会同时判「首见」）。
   *
   * `Fresh` 时该事件号已记入在飞集（调用方注入落地后须 [[completeEvent]]、未落地须
   * [[releaseEvent]]；两条收口都在 [[injectWithDedup]]）。
   */
  private[nebflow] def claimEvent(eventId: String): IO[Claim] =
    ledger.modify { l =>
      if l.done.contains(eventId) then (l, Claim.Done)
      else if l.inFlight.contains(eventId) then (l, Claim.InFlight)
      else (l.copy(inFlight = l.inFlight + eventId), Claim.Fresh)
    }

  /** 注入落地 ⇒ 在飞 → 已处理（账本按 [[MaxClaimedEvents]] 裁剪最旧，同先例口径）。 */
  private[nebflow] def completeEvent(eventId: String): IO[Unit] =
    ledger.update { l =>
      val done = (l.done :+ eventId).drop(Math.max(0, l.done.size + 1 - MaxClaimedEvents))
      l.copy(inFlight = l.inFlight - eventId, done = done)
    }

  /**
   * 注入**未**落地 ⇒ 撤回认领：该事件按「未处理」记，服务端重放时本闸不拦、
   * 仍会重试注入（禁静默吞掉一封信）。
   */
  private[nebflow] def releaseEvent(eventId: String): IO[Unit] =
    ledger.update(l => l.copy(inFlight = l.inFlight - eventId))

  /** 账本只读探针（可观测/可测；`private[nebflow]` 与 `resetForTest` 同档）。 */
  private[nebflow] def isDone(eventId: String): IO[Boolean] =
    ledger.get.map(_.done.contains(eventId))

  /**
   * 装配缝载荷：本机资源（root 会话解析用）+ 前端广播口 + ack 发送面。
   *
   * `resources` 只被用于**读取** live root 会话（`agentRegistry` / `sessionStore`），
   * 不经任何写面。
   *
   * `ackSender`（契约 v2 ④）：本机处理完一封设备邮件后向服务端回 ack 的**唯一**出
   * 口（生产 = 既有 `NeblinkRelayTunnel.sendAckLive` 的 live 读取，与好友消息 ack
   * 同缝、**同一实现点**）。缺省 `None` = 未接线 ⇒ **不静默**：审计行写明
   * `ack-not-sent`。
   *
   * 🔴 F4/F5（2026-09-18 回执诚实性批）：返回值 = 可判别的
   * `NeblinkRelayTunnel.AckOutcome`（修前 `IO[Unit]` ⇒ 「没发出」与「已发出」
   * 在类型上不可分，本腿因此照打 `ack sent to the server` = 假陈述）。
   */
  final case class Wiring(
    resources: SharedResources,
    wsSend: Json => IO[Unit],
    ackSender: Option[String => IO[NeblinkRelayTunnel.AckOutcome]] = None
  )

  @volatile private var wiring: Option[Wiring] = None

  /** 生产装配点（`GatewayMain`）。幂等：重复调用以后一次为准（测试可重置）。 */
  def initialize(
    resources: SharedResources,
    wsSend: Json => IO[Unit],
    ackSender: Option[String => IO[NeblinkRelayTunnel.AckOutcome]] = None
  ): Unit =
    wiring = Some(Wiring(resources, wsSend, ackSender))

  /** 是否已接线（可观测/可测）。 */
  def isWired: Boolean = wiring.isDefined

  /** 测试用重置（装配缝 + 事件号账本）。 */
  private[nebflow] def resetForTest(): Unit =
    wiring = None
    // 账本同步清空（对象级单例，测试 beforeEach 用）——局部引 runtime，不引全局隐式。
    ledger.set(Ledger(Set.empty, Queue.empty)).unsafeRunSync()(using cats.effect.unsafe.implicits.global)

  // ============================================================
  // 收包入口
  // ============================================================

  /**
   * 设备邮件帧的收包入口（契约 **v2.1**：服务端经本设备**隧道**推设备**事件流**
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
   * 本方法对**非**设备邮件帧严格无副作用（普通 `friend_event` / 其它帧原样交回既有路径）。
   */
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
          ) *> injectWithDedup(incoming, eventId)
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

  /**
   * 事件号去重闸（S1，2026-09-18 第二单缺陷 B）——**注入之前**的第一步：
   *
   *   - 帧未带 `eventId` ⇒ 没有判重键（回执面也无从关联）⇒ 按现状注入，**零去重**
   *     （不假装判过）；
   *   - 已注入过（服务端 at-least-once 重放）⇒ **跳过注入**（零第二个蓝气泡、零重跑
   *     模型与工具）+ INFO（`reason=duplicate_eventId`）+ 审计行，**仍补发一次 ack**
   *     （不 ack ⇒ 服务端永不脱账）；照旧复用 [[sendReceipt]] 的出口与文案（零改动）；
   *   - 同一事件号**正在**注入（并发同帧）⇒ 同样跳过注入，但**不**补 ack：该事件尚未
   *     落地，由在飞的那次尝试决定结果（提前 ack 会把未处理事件报成已处理 = 与失败支
   *     的「不回执」判词冲突）；
   *   - 首见 ⇒ 认领 + 按现状注入；注入落地 ⇒ 记入账本（之后的重放走重复支）；
   *     注入**未**落地 ⇒ 撤回认领（重放仍会重试注入，禁把一封信吞成「已处理」）。
   *
   * 🔴 账本 = 进程内（见 [[Ledger]]，本批不落盘）。
   */
  private def injectWithDedup(incoming: DeviceMail.Incoming, eventId: Option[String]): IO[Unit] =
    eventId match
      case None => injectWithRetry(incoming, None).void
      case Some(id) =>
        claimEvent(id).flatMap {
          case Claim.Done =>
            logger.info(
              s"[device-mail] duplicate eventId=$id reason=duplicate_eventId " +
                "(at-least-once replay of an already-injected event — injection skipped: " +
                "no second bubble, no model/tool rerun; ack re-sent so the server can drop it)"
            ) *> audit(incoming, "duplicate-skipped", s"eventId=$id; reason=duplicate_eventId") *>
              sendReceipt(incoming, Some(id))
          case Claim.InFlight =>
            logger.info(
              s"[device-mail] concurrent duplicate eventId=$id reason=concurrent_duplicate_eventId " +
                "(the same event is being injected right now — injection skipped, no ack: " +
                "the in-flight attempt owns the receipt)"
            )
          case Claim.Fresh =>
            injectWithRetry(incoming, Some(id)).flatMap {
              case true => completeEvent(id)
              case false => releaseEvent(id)
            }
        }

  /**
   * 单次注入尝试：解析本机**唯一** live Nebula root 会话 → 投 `ImmediateInput`。
   * 解析不到 / 歧义 / 未接线 ⇒ `Left(可读原因)`（**显式失败**，不静默）。
   */
  private def injectOnce(incoming: DeviceMail.Incoming): IO[Either[String, String]] =
    wiring match
      case None =>
        IO.pure(Left("DeviceMailInbox is not wired (no SharedResources) — injection skipped"))
      case Some(w) =>
        // 复用 MailTool 的**唯一** root 解析单点（session meta agentName == "Nebula"
        // ∧ 排除发信者自身 ∧ 唯一；0 或 ≥2 命中一律判为解析不出，禁静默挑一条）。
        MailTool.resolveRoots(w.resources, senderSessionId = "", preferredRootSid = None).flatMap {
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

  /**
   * 重试注入（收口两条，**判词逐字不变**）：`true` = 注入落地（调用方把事件号记入
   * 去重账本）；`false` = 未落地（调用方撤回认领——重放仍可重试注入）。
   *
   * 🔴 失败支的「未持久处理 ⇒ 不回 ack」判词与文案本批**零改动**。
   */
  private def injectWithRetry(incoming: DeviceMail.Incoming, receiptEventId: Option[String]): IO[Boolean] =
    // Phase 3 去重:手写 attempt 递归 → shared Retry.retryWithBackoff(固定 2s × 总尝试
    // InjectAttempts 次;warn 文案与级别逐字保留,且只在「还将重试」时打;异常不在
    // 重试面——原实现 flatMap 直通抛出,Errored 判否保持该口径)。
    Retry
      .retryWithBackoff(injectOnce(incoming))(
        Retry.Policy(
          maxAttempts = InjectAttempts,
          initialDelay = InjectRetryDelay,
          isSuccess = (_.isRight),
          isRetryable = {
            case Retry.AttemptFailure.Errored(_) => false
            case _ => true
          },
          onAttempt = (n, raw) =>
            raw match
              case Right(Left(_)) if n < InjectAttempts =>
                logger.warn(
                  s"[device-mail] injection attempt $n/$InjectAttempts failed — retrying in ${InjectRetryDelay.toSeconds}s"
                )
              case _ => IO.unit
        )
      )
      .flatMap {
        case Right(sid) =>
          logger.info(
            s"[device-mail] injected into Nebula root session ${sid.take(8)} " +
              s"(from_device=${incoming.fromDevice}, source=${DeviceMail.SourceDeviceMail}, type=${DeviceMail.EventTypeInfo.toUpperCase})"
          ) *> audit(incoming, "injected", s"root=${sid.take(8)} attempts<=$InjectAttempts") *>
            sendReceipt(incoming, receiptEventId).as(true)
        case Left(reason) =>
          val detail = s"$reason (attempts=$InjectAttempts)"
          logger.warn(s"[device-mail] injection FAILED and is NOT silent: $detail") *>
            alert(incoming, detail) *>
            audit(incoming, "inject-failed", detail) *>
            // 未持久处理 ⇒ **不回 ack**（回执语义边界），只留可读行：
            logger
              .warn(
                s"[device-mail] ack NOT sent (injection failed, so the event is not processed): " +
                  s"eventId=${receiptEventId.getOrElse("<none>")}"
              )
              .as(false)
      }

  end injectWithRetry

  /**
   * 收件侧回执（契约 v2 ④；复用既有 ack 出口与帧形状，零新帧、零新字段）。
   *
   * 🔴 **F5（2026-09-18 回执诚实性批，作者裁示「回执诚实性修、单列小批」）——按
   * F3/F4 的可判别结局分支，**真发出才 `ack-sent`**：修前 `send(...)` 恒成功
   * （`sendAck` 无 socket 也返回成功、装配缝把「隧道不在册」吞成 `IO.unit`）⇒ 本处
   * 照打 `ack sent to the server` + 审计 `ack-sent`——**线上零帧却报已回执**
   * （缺陷 B 的放大因：服务端不脱账 ⇒ 重放不退）。现在：
   *   - `Sent`        ⇒ INFO `ack sent to the server` + 审计 `ack-sent`；
   *   - `NoLiveSocket`⇒ WARN `ack-not-sent reason=no_live_socket` + 审计
   *     `ack-not-sent`（**禁**假陈述）；
   *   - `SendFailed`  ⇒ WARN `ack send FAILED` + 审计 `ack-send-failed`。
   * 另两个既有**非静默**分支（未接线 / 帧未带 eventId）逐字不变。
   */
  private def sendReceipt(incoming: DeviceMail.Incoming, receiptEventId: Option[String]): IO[Unit] =
    (wiring.flatMap(_.ackSender), receiptEventId) match
      case (Some(send), Some(eventId)) =>
        send(eventId)
          .flatMap {
            case NeblinkRelayTunnel.AckOutcome.Sent =>
              logger.info(s"[device-mail] ack sent to the server: eventId=$eventId (injection persisted)") *>
                audit(incoming, "ack-sent", s"eventId=$eventId")
            case NeblinkRelayTunnel.AckOutcome.NoLiveSocket =>
              // 如实记账：注入**已落地**（本方法只在注入成功后调用），但回执**没有**
              // 上线 ⇒ 服务端不会脱账 ⇒ 重放会再来（S1 去重闸保证它无害）。
              logger.warn(
                s"[device-mail] ack-not-sent reason=no_live_socket: eventId=$eventId " +
                  "(no live relay socket / relay tunnel not registered — nothing reached the server: " +
                  "the event stays un-dropped and will be replayed; injection IS persisted, " +
                  "so the replay is absorbed by the event-id dedup gate)"
              ) *> audit(incoming, "ack-not-sent", s"eventId=$eventId; reason=no_live_socket")
            case NeblinkRelayTunnel.AckOutcome.SendFailed(reason) =>
              logger.warn(s"[device-mail] ack send FAILED for eventId=$eventId: $reason") *>
                audit(incoming, "ack-send-failed", s"eventId=$eventId; err=$reason")
          }
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

  /**
   * 审计行（④ 接收腿一条；`RelayExecAudit` 同族 = 设备通道审计的既有落面）。
   * `sourceDeviceId` = **远端**设备（本事件是它发来的），`targetDeviceId` = 本机。
   */
  private def audit(incoming: DeviceMail.Incoming, status: String, detail: String): IO[Unit] =
    val localIo: IO[String] = wiring match
      case None => IO.pure("unknown")
      case Some(w) =>
        w.resources.neblinkService match
          case None => IO.pure("unknown")
          case Some(ns) => ns.identity.map(_.deviceId).handleErrorWith(_ => IO.pure("unknown"))
    localIo
      .flatMap { target =>
        RelayExecAudit.record(
          sourceDeviceId = incoming.fromDeviceId,
          targetDeviceId = target,
          via = "relay",
          action = s"DeviceMail.inject.$status",
          command = s"from_device=${incoming.fromDevice}; chars=${incoming.text.length}; $detail",
          projectRoot = "",
          cwd = Option(System.getProperty("user.dir")).getOrElse("")
        )
      }
      .handleErrorWith(e => logger.warn(s"[device-mail] audit line dropped: ${e.getMessage}"))
  end audit

end DeviceMailInbox
