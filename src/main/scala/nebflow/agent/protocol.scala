package nebflow.agent

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.ActorRef
import nebflow.core.{AskItem, SystemReminder, ToolExecResult}
import nebflow.shared.*

sealed trait AgentCommand

/** Restart level for supervisor-triggered agent restart. */
enum RestartLevel:
  case Soft, Rollback, Prune, Full

object AgentCommand:

  case class UserInput(
    text: String,
    replyTo: Option[ActorRef[AgentEvent]] = None,
    clientMessageId: Option[String] = None,
    blocks: Option[List[ContentBlock]] = None,
    chatWidth: Int = 0,
    /**
     * Injection source marker (任务 P): Some(...) when this input was injected
     * by a tool (Mail/Delegate/SubTask/skill/ask) rather than typed by the
     * user. User WS inputs always carry clientMessageId and source=None.
     */
    source: Option[String] = None,
    /** Sender agent name for Mail-delivered messages (shown as attribution label). */
    sender: Option[String] = None,
    /** Team name of the sender for Mail-delivered messages (shown as attribution label). */
    senderTeam: Option[String] = None,
    /** Delivery mode marker: "queue" | "immediate" for Mail-injected inputs. */
    delivery: Option[String] = None,
    /** Structured event type (e.g. completion status) for the UI source label —
     *  carried through from ImmediateInput so flow results render
     *  'Flow · <name> · Completed/Failed' instead of a bare 'Flow'. */
    eventType: Option[String] = None
  ) extends AgentCommand

  case class ImmediateInput(
    text: String,
    blocks: Option[List[ContentBlock]] = None,
    /** Injection source marker (任务 P), e.g. "mail" for Mail delivery. */
    source: Option[String] = None,
    /** Structured event type (e.g. mail type, completion status) for the UI source label. */
    eventType: Option[String] = None,
    /** Sender agent name for Mail-delivered messages (shown as attribution label). */
    sender: Option[String] = None,
    /** Team name of the sender for Mail-delivered messages. */
    senderTeam: Option[String] = None,
    /** Delivery mode marker: "queue" | "immediate" for Mail delivery. */
    delivery: Option[String] = None
  ) extends AgentCommand


  case class Interrupt() extends AgentCommand

  case class AskUser(
    requestId: String,
    items: List[AskItem],
    replyTo: Option[ActorRef[List[String]]] = None
  ) extends AgentCommand

  case class LlmComplete(
    result: ConsumeResult,
    replyTo: Option[ActorRef[AgentEvent]],
    turnId: Long
  ) extends AgentCommand

  case class LlmFailed(
    error: Throwable,
    replyTo: Option[ActorRef[AgentEvent]],
    turnId: Long,
    /** Block 3：LoopGuard L1 终止路径的计数器快照（terminatedFps 随 state 存活，
      * 后续 turn 同 fp 复发 → 直接 L2 冻结）。该路径不投 ToolsComplete——
      * LlmFailed 本身是计数器写回的唯一载体。其余调用方默认 None。 */
    loopCounters: Option[nebflow.core.processor.LoopGuard.Counters] = None
  ) extends AgentCommand

  case class SetPermissionDeferred(deferred: cats.effect.Deferred[IO, Boolean]) extends AgentCommand

  case class ToolsComplete(
    results: List[(ToolCall, ToolExecResult)],
    originalText: String,
    replyTo: Option[ActorRef[AgentEvent]],
    compactedMessages: Option[List[Message]] = None,
    thinking: Option[String] = None,
    thinkingSignature: Option[String] = None,
    /** Block 3 循环检测器 L0（supervision trio §D3）：LoopGuard Warn 提醒——
      * 下一轮以 user system-reminder 消息注入，
      * 零成本给模型自纠机会。 */
    loopReminder: Option[String] = None,
    /** Block 3：本轮 evaluate 产出的计数器快照——pipeToolExecutions 的计数器在
      * 异步 IO 内计算，行为返回时不可见；经消息携带由 ToolsComplete handler 写回
      * state（S1/S2 跨轮、S3 跨 turn 持久化的载体）。None=非 loop-guard 路径。 */
    loopCounters: Option[nebflow.core.processor.LoopGuard.Counters] = None,
    /** Block 3 L2：Some(detail) 时 handler 完成消息组装/持久化/计数器写回后
      * 不续轮（不 pipeLlmCall），转而冻结（loopDetected 广播 + 父通知 +
      * enterFrozen(Loop)）。此前用独立 LoopFreezeDetected 消息实现——但
      * ToolsComplete 链式 dispatch 会递增 currentTurnId，后续消息按 stale
      * 丢弃，冻结永不落地（wiring 实证）。 */
    freezeAfter: Option[String] = None
  ) extends AgentCommand

  case class CompactionComplete(result: Either[String, List[Message]]) extends AgentCommand

  /**
   * F2 (2026-08-30, compact-injection-shield batch 2): sent to self at spawn
   * (before any external delivery) — load the persisted injection queues
   * (CompactionQueueStore) into the execution context. A crash mid-compaction
   * otherwise loses every ImmediateInput/ExternalEvent queued during the
   * window; replay restores them so the next turn injects them.
   */
  case object RecoverPersistedQueues extends AgentCommand

  case class TriggerCompaction(
    mode: String,
    replyDeferred: Option[cats.effect.Deferred[IO, Either[String, CompactionResult]]] = None,
    postCompactInstruction: Option[String] = None
  ) extends AgentCommand

  case class Retry(reason: String) extends AgentCommand

  case class AskQuestion(question: String, sessionId: String) extends AgentCommand

  case class SkillActivate(
    skillName: String,
    input: String,
    sessionId: String,
    skillContent: String,
    skillBaseDir: String
  ) extends AgentCommand

  /**
   * FreezeScheduler（30s 轮询）/ setWorkSchedule（配置热更）→ frozen agent：
   * 重评估冻结时间表（#337 黑名单语义）——已出冻结段则恢复挂起的 dispatch，
   * 仍在冻结段内则更新 resumeAt 留任。到达非 frozen behavior 时 no-op（幂等，无需去重）。
   */
  case object CheckFreezeGate extends AgentCommand

  case class UpdateContextWindow(window: Int) extends AgentCommand

  /** Frontend → agent: update safety mode for this session. */
  case class SetSafetyMode(mode: nebflow.core.SafetyMode) extends AgentCommand

  case class ResumeTurn(
    turnStartMessageCount: Int,
    turnIdx: Int
  ) extends AgentCommand

  case class Stop(reason: String) extends AgentCommand
  case object ClearReadTracker extends AgentCommand
  case object ResetSession extends AgentCommand

  case class UpdateGitBranch(branch: Option[String]) extends AgentCommand

  /**
   * Supervisor-triggered restart. Levels:
   *  - Soft: cancel current work, re-dispatch LLM call (same messages)
   *  - Rollback: truncate last tool call pair, inject error, re-dispatch
   *  - Prune: (future) context prune + restart
   *  - Full: (future) reset to empty, reload from persisted history
   */
  case class RestartAgent(level: RestartLevel) extends AgentCommand

  case class BackgroundTaskNotification(
    taskId: String,
    description: String,
    status: String,
    output: String,
    exitCode: Option[Int] = None
  ) extends AgentCommand:

    def toExternalEvent: ExternalEvent = ExternalEvent(
      source = "background-task",
      eventType = status,
      payload = status match
        case "completed" =>
          val exitInfo = exitCode.filter(_ != 0).map(c => s" (exit code $c)").getOrElse("")
          s"\"$description\"$exitInfo:\n$output"
        case "failed" => s"\"$description\":\n$output"
        case _ => s"\"$description\"",
      metadata = JsonObject(
        "taskId" -> taskId.asJson,
        "description" -> description.asJson,
        "status" -> status.asJson,
        "output" -> output.asJson,
        "exitCode" -> exitCode.asJson
      ),
      correlationId = Option(taskId).filter(_.nonEmpty)
    )
  end BackgroundTaskNotification

  case class ExternalEvent(
    source: String,
    eventType: String,
    payload: String,
    metadata: JsonObject = JsonObject.empty,
    correlationId: Option[String] = None
  ) extends AgentCommand

  case class SessionStarted(
    address: String,
    agentName: String,
    taskDescription: String
  ) extends AgentCommand

  case class SessionClosed(address: String) extends AgentCommand
  case class SessionUpdate(address: String, status: String) extends AgentCommand

  /** A queued mail has arrived — drain it (idle) or increment the count (processing). */
  case class MailQueued(
    item: nebflow.core.flow.MailQueueStore.MailQueueItem,
    fromSessionId: String
  ) extends AgentCommand

  /** v2 冻结式错误恢复升级链（§5.2）：FreezeScheduler.scan 发现 escalation 超时
    * 后发送——frozen behavior 中执行 escalate 动作（level+1，通知上一级/用户终态）。
    * 消息幂等：非 frozen/无 escalation 时 no-op。 */
  case object Escalate extends AgentCommand
end AgentCommand

/**
 * Display/routing kind of an agent (P1 统一注册表). Replaces the nodeSessionId
 * string-prefix conventions (team-/dag-/delegate-/ephemeral-) as the *identity*
 * discriminator; P3 drops the prefixes entirely and routes by this field.
 */
enum AgentKind:
  case Root, Team, Flow, Delegate, Ephemeral, Plan, SubTask

/**
 * Unified registry entry — one identity per agent (P1 统一注册表).
 *
 * sessionId is the single identity: execution key + persistence key + display key.
 * ref is the runtime reply target; kind is the display type; rootSessionId anchors
 * the permission-policy bucket (P2) and the inheritance chain; parentRef is kept
 * only for Mail semantics / upward diagnostics, NOT for interaction forwarding.
 */
case class AgentRecord(
  sessionId: String,
  ref: ActorRef[AgentCommand],
  kind: AgentKind,
  rootSessionId: String,
  parentRef: Option[ActorRef[AgentCommand]] = None,
  /**
   * P0 阶段 3（2026-08-18，设计 §4.4）：registry 注册时刻（task 生命周期起点）。
   * 默认 0 = 未设置（旧注册点）；TaskStuckWatcher 只依赖 lastActivityMs，
   * startedAt 供前端展示（二期 §4.6）。
   */
  startedAt: Long = 0L,
  /**
   * P0 阶段 3：当前 turn 状态快照——TaskStuckWatcher 判定 "Processing 且
   * 长时间无活动" 的 status 来源。由 AgentCore.pipeLlmCall（Processing）与
   * AgentActor.finishTurnCont 回 idle 分支（Idle）维护；run_in_background
   * 时 agent 回 Idle 为合法状态，永不判卡死（防误杀铁律）。
   */
  status: AgentStatus = AgentStatus.Idle,
  /**
   * P0 阶段 3 + 2026-09-10 卡死判据换轴（信号拆分）：最近一次 **agent 侧**
   * 活动时间戳——只允许由 agent 侧事件写入：LLM 调用开始 / 流 chunk / 工具
   * 批次完成（AgentCore.pipeLlmCall / pipeToolExecutions）、turn 终态与控制
   * 消息（AgentActor 各点）、AskUser/permission 进出（AgentCore.askUserPermission）、
   * 出生注册。**进程侧活性不得再写入本字段**（写入点见 `processActivityMs`）。
   * TaskStuckWatcher 判卡死的数据源之一。
   */
  lastActivityMs: Long = 0L,
  /**
   * 2026-09-10 卡死判据换轴（取证 `20260910_130621_flow-node-activity-signal-forensics.md`）：
   * **进程侧**活性时间戳——唯一写点 = `BashTool.startActivityBridge`（前台命令
   * 有进展：stdout 行数增长 / CPU ≥ CpuActiveThresholdNanos / sleep-like）与
   * `RemoteExecutor` 远程前台心跳（每 30s 无条件）。
   *
   * 语义 =「这个会话正在跑的子进程还有动静」，**不是**「agent 还在推进」——
   * 本次事故（n-5c69c793 会话被前台 `npx next dev` 占死 2h50m）里 dev server
   * 的 CPU 微动（实测 1.786 ms/s = 10ms 阈值的 5.4 倍）持续刷新 agent 侧戳，
   * 导致 TaskStuckWatcher / SessionKick / AgentControl 全部失明。
   *
   * **本字段不得再被 TaskStuckWatcher / SessionKick / 人可见 idle 读取**：
   * 它只能作为「子进程确实活着」的旁证，不构成「有进展」。
   */
  processActivityMs: Long = 0L,
  /**
   * 2026-09-10 卡死判据换轴：当前 turn 起点（该 turn 首次进入 Processing 的
   * 时刻）。由 AgentCore.touchRegistryActivity 在 status 由非 Processing
   * 转入 Processing 时置位（同 turn 内的多轮工具循环不重置）；仅诊断/展示用，
   * 不参与卡死判据（判据用 `currentToolStartedAt`）。
   */
  turnStartedAt: Long = 0L,
  /**
   * 2026-09-10 卡死判据换轴：当前 turn 内在飞的工具名。写入点 = 工具执行开始
   * （AgentCore.pipeToolExecutions 既有工具循环内，与 touchRegistryActivity 同
   * 一写入路径）；离开 Processing（Idle/WaitingForUser/Frozen/Error）或工具批次
   * 完成时清空。人可见性：AgentControl list 的 phase 列 / status 的 toolPhase 行。
   */
  currentToolName: Option[String] = None,
  /**
   * 2026-09-10 卡死判据换轴：当前工具调用的起始时刻（0 = 无在飞工具）。
   * **新卡死判据的主轴**：同一 turn 内单个工具调用持续超过
   * `Defaults.ToolPhaseStuckMs`（默认 10min）且 status 仍为 Processing →
   * 判卡死并走既有 L1→L4 分级恢复。判据**不引用任何进程 CPU**。
   */
  currentToolStartedAt: Long = 0L,
  /**
   * R6（取消静默死锁修复批 2026-09-10，作者裁定 R6 方案 2）：当前在飞工具**自己
   * 声明**的授权时长（ms）——工具开始时从入参 JSON 的 `timeout` 字段读出
   * （[[nebflow.shared.Defaults.declaredToolTimeoutMs]]，只读声明值、不读工具默认
   * 值）。0 = 未声明。
   *
   * 判据消费单点 = `TaskStuckWatcher.assess` 的 toolOverdue 轴：
   * `toolPhaseMs > min(ToolPhaseStuckMs, currentToolDeadlineMs + slack)` —— 判据
   * 尊重命令自己声明的合法时长（案例 1 的 `timeout=900000ms` 不再在 11.2 分钟被
   * 判死）。判据仍**不引用任何进程 CPU**。
   *
   * 生命周期与 currentToolStartedAt 同步（工具开始置位 / 工具批次完成与离开
   * Processing 清 0）。
   */
  currentToolDeadlineMs: Long = 0L,
  /**
   * AgentControl（2026-08-19 spec §3.1）：监听该 agent 终态的 adapter 引用——
   * ephemeral Delegate/SubTask = BackoffSupervisor；persistent Delegate =
   * persistentAdapter；Ephemeral/Flow/Root/Team = None（无取消通道，cancel 走
   * 降级 Stop 路径）。默认 None → 既有注册点零改动。
   */
  supervisorRef: Option[ActorRef[AgentEvent]] = None,
  /**
   * AgentControl：任务归属的父会话（SubAgentTaskStore 文件键）。Delegate/
   * SubTask 注册点已有值；list/restart 用它反查任务元数据，避免全目录扫描。
   */
  parentSessionId: String = "",
  /**
   * issue #31 (2026-08-20) Fix D — phantom barrier 可见化：该 agent 自身
   * outstandingSubagentResults 的最近快照（spawn 计数 / ExternalEvent 三分支
   * 时刷新）。诊断语义：idle 期 outstanding > 0 且无在飞任务 = barrier 被
   * phantom slot 占据（成员 hang/停止失败时发生，held 结果永不注入）。
   */
  outstandingSubagents: Int = 0,
  /**
   * issue #31 Fix D — 同上：该 agent pendingEvents（被 HOLD 的子代理结果
   * 队列）长度的最近快照。outstanding>0 时 pending>0 = 有结果被扣留等批。
   */
  pendingEventCount: Int = 0,
  /**
   * v2 冻结式错误恢复升级链（§5.2）：升级链状态快照（P0 内存态）——
   * enterErrorFrozen 第 3 次进入时设置、FreezeScheduler.scan 读它检查
   * escalateAt 到期（升级/跳级/到达用户）。与 AgentState.execution.escalation
   * 同步维护（registry 层供扫描器只读，actor 层为权威）。
   */
  escalation: Option[EscalationInfo] = None,
  /**
   * v2 冻结式错误恢复：当前冻结的 reason 字符串快照（"schedule"/"llm-transient"/
   * "network"/"provider-down"/"restart-recovery"）。WS parentRestart 用它区分
   * 时间表冻结（正常调度，不可父重启）与错误族冻结（可重启）。与
   * AgentState 的 lastErrorFreezeReason 同步维护；恢复/解除冻结时清 None。
   */
  frozenReason: Option[String] = None,
  /** Block 3 循环检测器观测镜像（supervision trio §D2-B）：当前 turn 的
    * 同参同败连续计数 / 非进展轮数——pipeToolExecutions 每轮随 touchRegistryActivity
    * 同步写入。AgentControl list 的 stuck? 列旁显示 loop×N（诊断「高活动零进展」）。 */
  loopStreak: Int = 0,
  loopRounds: Int = 0,
  /** Project 归属（2026-09-06 作者裁定：Sub-Agents 面板 Flow 徽标旁标注项目名）。
    * 仅 Project 域会话（node- 与 dispatcher- 前缀，NodeEngine/ProjectActor 注册点）有值；
    * Delegate/SubTask/Ephemeral/FlowDAG/Team 等 None（默认 = 既有注册点零改动）。
    * 恢复路径数据源：activeAgentEntryJson 输出 project 字段供前端刷新后渲染徽标；
    * 实时路径不经此字段（agentStart 帧由 routeSubagentWsSend 转发层注入）。 */
  project: Option[String] = None,
  /** 会话展示名（刷新恢复路径专用，20260907 节点名刷新持久化批）：Project 域注册点
    * （NodeEngine 节点 / ProjectActor 分发器）写 Flow Map 节点名 / "dispatcher/<project>"；
    * 其余域 None（默认 = 既有注册点零改动）。activeAgentEntryJson 恢复链消费：
    * meta.agentName → displayName → sessionId 三档。 */
  displayName: Option[String] = None
)

// ============================================================
// P2 — Permission policy (per root session) + InteractionHub protocol
// ============================================================

/**
 * One permission policy per Nebula root session (P2). All agents in a root
 * session's tree (Team/Flow/Delegate/Ephemeral/itself) *dynamically inherit*
 * the bucket — the decision point reads it at request time, never copies it.
 *
 * `allow`/`deny` are model-reserved tool-name sets (UI exposes them later);
 * while empty the behavior is identical to the old per-session safetyMode.
 */
case class PermissionPolicy(
  safetyMode: nebflow.core.SafetyMode = nebflow.core.SafetyMode.ConfirmEdits,
  allow: Set[String] = Set.empty,
  deny: Set[String] = Set.empty
)

object PermissionPolicy:
  val default: PermissionPolicy = PermissionPolicy()

/** Interaction kind — what the user is being asked (P2). */
enum InteractionKind:
  case Permission, AskUser

/**
 * Reply target for an interaction request (P2). Held by the InteractionHub —
 * NOT by the requesting agent — so it survives the requesting agent's turn
 * lifecycle (D3) and supports multiple concurrent requests (D4, multi-slot
 * queueing keyed by requestId).
 */
sealed trait InteractionReply

object InteractionReply:
  final case class PermissionReply(deferred: cats.effect.Deferred[IO, Boolean]) extends InteractionReply
  final case class AskUserReply(replyTo: Option[ActorRef[List[String]]]) extends InteractionReply

/**
 * Unified interaction request (P2). ForwardPermission/ForwardAskUser are gone:
 * every agent (root or sub-agent) sends this directly to the InteractionHub,
 * which renders the card/question in the Nebula (root) window and routes the
 * answer back by requestId.
 *
 * @param requestId     unique id used to route the answer (multi-slot queue)
 * @param kind          Permission | AskUser
 * @param payload       card/question JSON (type/summary/items/…); the hub
 *                      overrides sessionId=rootSessionId and adds requestId
 * @param reply         PermissionReply(deferred) | AskUserReply(replyTo)
 * @param rootSessionId permission-policy bucket + wsSend routing key
 * @param sourceAgent   originating agent name (UI attribution badge)
 * @param sourceSession originating agent session id (UI attribution badge)
 */
final case class InteractionRequest(
  requestId: String,
  kind: InteractionKind,
  payload: Json,
  reply: InteractionReply,
  rootSessionId: String,
  sourceAgent: String,
  sourceSession: String
)

/**
 * Unified interaction answer (P2). Frontend answers (permissionAnswer /
 * askUserAnswer) are translated by the gateway into this and sent to the hub.
 * When `requestId` is empty (old frontend, no requestId support) the hub falls
 * back to matching the oldest pending request for `rootSessionId`.
 *
 * payload: Permission → {"approved": Boolean}; AskUser → {"answers": [...]}
 */
final case class InteractionAnswered(
  requestId: String,
  rootSessionId: String,
  payload: Json
)

/**
 * Tool/compaction pipeline error — distinct from LLM failures.
 * Carried via LlmFailed but pattern-matched to show correct message to user.
 */
case class ToolPipelineError(message: String) extends RuntimeException(message)

/** Block 3 循环检测器 L1（supervision trio §D3）：同参同败超阈 / 轮预算超限的
  * turn 级终止。分类=Permanent（不重试不冻结）→ LlmFailed fatal 链（supervisor
  * notify / team 成员父 ExternalEvent(failed, retryable=true) / 持久化）。 */
final case class LoopDetectedError(message: String) extends RuntimeException(message)

sealed trait AgentEvent

object AgentEvent:
  case class Completed(sessionId: String, messages: List[Message] = Nil) extends AgentEvent
  case class Failed(sessionId: String, error: AgentError) extends AgentEvent
  /**
   * AgentControl 取消终态（spec §3.1）：sealed 穷尽——所有 adapter 必须处理。
   * BackoffSupervisor/persistentAdapter 走 notifyParentAndStop("cancelled")；
   * bridge 类（Ephemeral/FlowDag/Mail fork）完成 deferred Left。
   */
  case class Cancelled(sessionId: String, reason: String) extends AgentEvent

/** 冻结原因（v2 冻结式错误恢复，§3.1）：统一「暂停在 dispatch 边界」的语义，
  * reason 决定恢复条件与前端两族视觉（schedule=sapphire 冷色 / 错误族=amber 暖色）。
  * 缺省 Schedule 保证旧调用点零改动（时间表冻结=特例）。 */
enum FreezeReason:
  case Schedule        // 时间表冻结（#337 黑名单），恢复条件=出冻结段
  case LlmTransient    // LLM transient 错误预算耗尽（429/529 overload），恢复条件=退避到期+provider 恢复
  case Network         // 连接重置/超时/未知瞬态，恢复条件=短退避到期
  case ProviderDown    // 全候选 provider Down，恢复条件=HealthMonitor 探测恢复（P0 用 R1 轮询兜底）
  case RestartRecovery // 崩溃/进程重启后重建，恢复条件=条件检查通过后立即续跑
  case Loop            // Block 3 循环检测器 L2（supervision trio §D3）：恢复条件=仅人工（用户输入唤醒 / AgentControl restart / cancel）——不自动续跑

/** 升级链状态（v2 §5.2）：同 reason 连续冻结 ≥3 次进入——挂 AgentRecord（P0 内存态）。
  * level=当前升级层级（1 起）；escalateAt=本层等待父决策的截止时间；awaitedParentSessionId=等待的父会话。 */
case class EscalationInfo(
  level: Int,
  escalateAt: Long,
  awaitedParentSessionId: String
)

/** v2 升级链判定（§5.1 规则，纯函数——可单测）：升级只沿 parentSessionId 静态链
  * 向上、每级一次（通知按 level 去重）、无环（单父树）；父记录不存在 → 跳级；
  * 最终到达用户（root/无父）即终态，无再升级对象。 */
object Escalation:
  enum Target:
    /** 父存活：通知父（ExternalEvent 注入其上下文，排队不唤醒）。 */
    case Parent
    /** 父缺失：跳级（P0 无父链信息，视同到达用户终态，detail 标注）。 */
    case Grandparent
    /** 无父（root/standalone）：用户终态——WS errorEscalated，不设超时。 */
    case User

  def nextTarget(level: Int, hasParent: Boolean, parentAlive: Boolean): Target =
    if !hasParent then Target.User
    else if parentAlive then Target.Parent
    else Target.Grandparent
end Escalation

enum AgentStreamEvent:
  case TextDelta(text: String)
  case ToolStart(label: String)
  case ToolEnd(label: String, summary: String, content: String, isError: Boolean, input: Option[JsonObject] = None)
  /** 工具执行期心跳（审计 20260903 子项①）：toolStart→toolEnd 之间每
    * Defaults.ToolHeartbeatSec 秒发一条，喂活前端 busy timer——前台长工具
    * 执行零事件段不再触发前端 630s 纯静默超时误杀。 */
  case ToolHeartbeat(label: String)
  case AgentStart(agentName: String, agentType: String, taskDescription: Option[String] = None)
  case AgentEnd(agentName: String)
  case Thinking
  case ToolCallDetected(name: String)
  case RetryStatus(message: String)

  case Done(
    model: Option[String] = None,
    contextWindow: Option[Int] = None,
    inputTokens: Option[Int] = None,
    compactThreshold: Option[Double] = None,
    outputTokens: Option[Int] = None
  )
  case UsageUpdate(
    inputTokens: Int,
    contextWindow: Int,
    compactThreshold: Double,
    outputTokens: Option[Int] = None,
    // #308: the model actually used in this LLM round (after fallback), so the
    // UI can live-refresh the "actual model" badge per round — None omits the key.
    model: Option[String] = None
  )
  case CompactStart(mode: String, inputTokens: Option[Int], threshold: Option[Int])
  case CompactComplete(before: Int, after: Int, reportPath: Option[String] = None)
  case CompactFailed(reason: String, attempt: Int, maxAttempts: Int)
  case BackgroundTaskUpdate(taskId: String, description: String, status: String)
  case ExternalEventReceived(source: String, eventType: String, correlationId: Option[String])
  case Interrupted

  /** 冻结调度：agent 在 dispatch 边界被冻结（处于冻结时段内，#337 黑名单语义）。resumeAtMillis 供前端展示。
    * v2（冻结式错误恢复）：reason 泛化——Schedule=时间表冻结（默认，缺省兼容旧前端）；
    * LlmTransient/Network/ProviderDown/RestartRecovery=错误恢复族（§3.1）；detail/retryCount/
    * escalation 为错误族附加信息（可选，缺省无）。 */
  case Frozen(
      resumeAtMillis: Option[Long],
      reason: FreezeReason = FreezeReason.Schedule,
      detail: Option[String] = None,
      retryCount: Int = 0,
      escalation: Option[EscalationInfo] = None
  )

  /** 冻结调度：恢复（出冻结段自动恢复 / 用户输入唤醒 / 交互豁免路径不会发出本事件）。
    * nextChangeAt = 恢复时刻的下一翻转点（工作态 = 下一冻结开始时刻，供前端
    * 展示「下一段 HH:mm 再冻结」；None = 无未来翻转点，如配置关闭）。 */
  case Resumed(nextChangeAt: Option[Long] = None)

  def toJson(agentId: String, isSubagent: Boolean = true, sessionId: Option[String] = None): Json =
    // For subagent events, inject nodeSessionId so the frontend can persist
    // messages to the correct flow agent session's ui.json.
    val withNodeSession: Json => Json =
      if isSubagent then
        sessionId match
          case Some(sid) =>
            json =>
              json.asObject match
                case Some(obj) => Json.fromJsonObject(obj.add("nodeSessionId", sid.asJson))
                case None => json
          case None => identity
      else identity
    withNodeSession(this match
      case TextDelta(text) =>
        if isSubagent then
          Json.obj("type" -> "agentTextDelta".asJson, "agentId" -> agentId.asJson, "delta" -> text.asJson)
        else Json.obj("type" -> "textDelta".asJson, "sessionId" -> sessionId.asJson, "delta" -> text.asJson)
      case ToolStart(label) =>
        if isSubagent then
          Json.obj("type" -> "agentToolStart".asJson, "agentId" -> agentId.asJson, "label" -> label.asJson)
        else Json.obj("type" -> "toolStart".asJson, "sessionId" -> sessionId.asJson, "label" -> label.asJson)
      case ToolHeartbeat(label) =>
        if isSubagent then
          Json.obj("type" -> "agentToolHeartbeat".asJson, "agentId" -> agentId.asJson, "label" -> label.asJson)
        else Json.obj("type" -> "toolHeartbeat".asJson, "sessionId" -> sessionId.asJson, "label" -> label.asJson)
      case ToolEnd(label, summary, content, isError, input) =>
        val base =
          if isSubagent then
            Json.obj(
              "type" -> "agentToolEnd".asJson,
              "agentId" -> agentId.asJson,
              "label" -> label.asJson,
              "summary" -> summary.asJson,
              "content" -> content.asJson,
              "isError" -> isError.asJson
            )
          else
            Json.obj(
              "type" -> "toolEnd".asJson,
              "sessionId" -> sessionId.asJson,
              "label" -> label.asJson,
              "summary" -> summary.asJson,
              "content" -> content.asJson,
              "isError" -> isError.asJson
            )
        input.fold(base)(i => base.deepMerge(Json.obj("input" -> Json.fromJsonObject(i))))
      case AgentStart(name, agentType, taskDescription) =>
        val base = Json.obj(
          "type" -> "agentStart".asJson,
          "agentId" -> agentId.asJson,
          "name" -> name.asJson,
          "agentType" -> agentType.asJson
        )
        taskDescription.fold(base)(desc => base.deepMerge(Json.obj("taskDescription" -> desc.asJson)))
      case AgentEnd(name) => Json.obj("type" -> "agentEnd".asJson, "agentId" -> agentId.asJson, "name" -> name.asJson)
      case Thinking =>
        if isSubagent then Json.obj("type" -> "agentThinking".asJson, "agentId" -> agentId.asJson)
        else Json.obj("type" -> "thinking".asJson, "sessionId" -> sessionId.asJson)
      case ToolCallDetected(name) =>
        if isSubagent then
          Json.obj("type" -> "agentToolCallDetected".asJson, "agentId" -> agentId.asJson, "name" -> name.asJson)
        else Json.obj("type" -> "toolCallDetected".asJson, "sessionId" -> sessionId.asJson, "name" -> name.asJson)
      case RetryStatus(message) =>
        if isSubagent then
          Json.obj("type" -> "agentRetryStatus".asJson, "agentId" -> agentId.asJson, "message" -> message.asJson)
        else Json.obj("type" -> "retryStatus".asJson, "sessionId" -> sessionId.asJson, "message" -> message.asJson)
      case Done(model, contextWindow, inputTokens, compactThreshold, outputTokens) =>
        val base =
          if isSubagent then Json.obj("type" -> "agentDone".asJson, "agentId" -> agentId.asJson)
          else Json.obj("type" -> "done".asJson, "sessionId" -> sessionId.asJson)
        val withModel = model.fold(base)(m => base.deepMerge(Json.obj("model" -> m.asJson)))
        val withCw = contextWindow.fold(withModel)(cw => withModel.deepMerge(Json.obj("contextWindow" -> cw.asJson)))
        val withIt = inputTokens.fold(withCw)(it => withCw.deepMerge(Json.obj("inputTokens" -> it.asJson)))
        val withOt = outputTokens.fold(withIt)(ot => withIt.deepMerge(Json.obj("outputTokens" -> ot.asJson)))
        compactThreshold.fold(withOt)(ct => withOt.deepMerge(Json.obj("compactThreshold" -> ct.asJson)))
      case UsageUpdate(inputTokens, contextWindow, compactThreshold, outputTokens, model) =>
        // #308: model (actual model of this round) is merged last, same style as
        // Done's withModel — absent when None so old payloads stay byte-stable.
        if isSubagent then
          Json.obj(
            "type" -> "usageUpdate".asJson,
            "sessionId" -> sessionId.asJson,
            "nodeSessionId" -> sessionId.asJson,
            "inputTokens" -> inputTokens.asJson,
            "contextWindow" -> contextWindow.asJson,
            "compactThreshold" -> compactThreshold.asJson
          ).deepMerge(outputTokens.fold(Json.obj())(ot => Json.obj("outputTokens" -> ot.asJson)))
            .deepMerge(model.fold(Json.obj())(m => Json.obj("model" -> m.asJson)))
        else
          Json.obj(
            "type" -> "usageUpdate".asJson,
            "sessionId" -> sessionId.asJson,
            "inputTokens" -> inputTokens.asJson,
            "contextWindow" -> contextWindow.asJson,
            "compactThreshold" -> compactThreshold.asJson
          ).deepMerge(outputTokens.fold(Json.obj())(ot => Json.obj("outputTokens" -> ot.asJson)))
            .deepMerge(model.fold(Json.obj())(m => Json.obj("model" -> m.asJson)))
      case CompactStart(mode, inputTokens, threshold) =>
        if isSubagent then
          Json.obj(
            "type" -> "agentCompactStart".asJson,
            "agentId" -> agentId.asJson,
            "mode" -> mode.asJson,
            "inputTokens" -> inputTokens.asJson,
            "threshold" -> threshold.asJson
          )
        else
          Json.obj(
            "type" -> "compactStart".asJson,
            "sessionId" -> sessionId.asJson,
            "mode" -> mode.asJson,
            "inputTokens" -> inputTokens.asJson,
            "threshold" -> threshold.asJson
          )
      case CompactComplete(before, after, reportPath) =>
        val base =
          if isSubagent then
            Json.obj(
              "type" -> "agentCompactComplete".asJson,
              "agentId" -> agentId.asJson,
              "before" -> before.asJson,
              "after" -> after.asJson
            )
          else
            Json.obj(
              "type" -> "compactComplete".asJson,
              "sessionId" -> sessionId.asJson,
              "before" -> before.asJson,
              "after" -> after.asJson
            )
        reportPath.fold(base)(p => base.deepMerge(Json.obj("reportPath" -> p.asJson)))
      case CompactFailed(reason, attempt, maxAttempts) =>
        if isSubagent then
          Json.obj(
            "type" -> "agentCompactFailed".asJson,
            "agentId" -> agentId.asJson,
            "reason" -> reason.asJson,
            "attempt" -> attempt.asJson,
            "maxAttempts" -> maxAttempts.asJson
          )
        else
          Json.obj(
            "type" -> "compactFailed".asJson,
            "sessionId" -> sessionId.asJson,
            "reason" -> reason.asJson,
            "attempt" -> attempt.asJson,
            "maxAttempts" -> maxAttempts.asJson
          )
      case BackgroundTaskUpdate(taskId, description, status) =>
        Json.obj(
          "type" -> "backgroundTaskUpdate".asJson,
          "taskId" -> taskId.asJson,
          "description" -> description.asJson,
          "status" -> status.asJson,
          "sessionId" -> sessionId.asJson
        )
      case ExternalEventReceived(source, eventType, correlationId) =>
        val base =
          Json.obj("type" -> "externalEventReceived".asJson, "source" -> source.asJson, "eventType" -> eventType.asJson)
        val withSession =
          if isSubagent then base.deepMerge(Json.obj("agentId" -> agentId.asJson))
          else base.deepMerge(Json.obj("sessionId" -> sessionId.asJson))
        correlationId.fold(withSession)(id => withSession.deepMerge(Json.obj("correlationId" -> id.asJson)))
      case Interrupted =>
        val base = Json.obj("type" -> "interrupted".asJson)
        if isSubagent then base.deepMerge(Json.obj("agentId" -> agentId.asJson))
        else base.deepMerge(Json.obj("sessionId" -> sessionId.asJson))
      case Frozen(resumeAtMillis, reason, detail, retryCount, escalation) =>
        val base =
          if isSubagent then Json.obj("type" -> "agentFrozen".asJson, "agentId" -> agentId.asJson)
          else Json.obj("type" -> "frozen".asJson, "sessionId" -> sessionId.asJson)
        // 冻结中显式布尔（现象 2 契约补全 2026-08-30）：前端输入栏禁用状态机
        // 直接读字段而非推断事件类型——事件语义与状态字段解耦，便于统一处理。
        val withFrozen = base.deepMerge(Json.obj("frozen" -> true.asJson))
        val withResume = resumeAtMillis.fold(withFrozen)(t => withFrozen.deepMerge(Json.obj("resumeAt" -> t.asJson)))
        // reason 缺省='schedule'（默认参数），旧前端/旧后端双向兼容；错误族前端按 reason 区分两族视觉
        val reasonStr = reason match
          case FreezeReason.Schedule        => "schedule"
          case FreezeReason.LlmTransient    => "llm-transient"
          case FreezeReason.Network         => "network"
          case FreezeReason.ProviderDown    => "provider-down"
          case FreezeReason.RestartRecovery => "restart-recovery"
          case FreezeReason.Loop            => "loop"
        val withReason = withResume.deepMerge(Json.obj("reason" -> reasonStr.asJson))
        val withDetail = detail.fold(withReason)(d => withReason.deepMerge(Json.obj("detail" -> d.asJson)))
        val withRetry = if retryCount > 0 then withDetail.deepMerge(Json.obj("retryCount" -> retryCount.asJson)) else withDetail
        escalation.fold(withRetry)(e =>
          withRetry.deepMerge(
            Json.obj(
              "escalation" -> Json.obj(
                "level" -> e.level.asJson,
                "escalateAt" -> e.escalateAt.asJson,
                "awaitedParentSessionId" -> e.awaitedParentSessionId.asJson
              )
            )
          )
        )
      case Resumed(nextChangeAt) =>
        val base =
          if isSubagent then Json.obj("type" -> "agentResumed".asJson, "agentId" -> agentId.asJson)
          else Json.obj("type" -> "resumed".asJson, "sessionId" -> sessionId.asJson)
        // 解冻显式布尔 + 下一翻转点（None 省略，旧载荷 byte-stable）
        val withFrozen = base.deepMerge(Json.obj("frozen" -> false.asJson))
        nextChangeAt.fold(withFrozen)(t => withFrozen.deepMerge(Json.obj("nextChangeAt" -> t.asJson)))
      )
  end toJson
end AgentStreamEvent

case class AgentInfo(
  name: String,
  description: String,
  tools: List[String],
  displayName: Option[String] = None,
  avatar: Option[String] = None
)

enum AgentErrorType:
  case LlmFailed, ToolFailed, Timeout, Interrupted, DepthExceeded, Unknown

case class AgentError(
  agentId: String,
  agentName: String,
  depth: Int,
  errorType: AgentErrorType,
  message: String,
  cause: Option[AgentError] = None,
  /** Flow-node supervision P2 (2026-08-26): agent-turn-level retryability of
    * the failure (single source AgentActor.llmFailureRetryable). None =
    * legacy/unset (treated as not retryable by consumers). Lets the flow
    * executor distinguish "LLM stall, checkpoint-restart may heal it" from
    * hard failures without string-matching error messages. */
  retryable: Option[Boolean] = None
)

enum AgentStatus:
  case Idle
  case Processing
  case WaitingForUser
  /** 冻结调度：dispatch 边界被冻结时间表拦住（#337 黑名单语义），挂起等待出冻结段/用户唤醒。 */
  case Frozen
  case Error(msg: String)

case class CompactionResult(before: Int, after: Int)

/**
 * Compaction execution phase. Single-stage model (2026-08-31 redesign):
 *  - Compact: tools disabled, single text-only summary turn.
 * The former Save stage (two-stage model) was removed — compaction only
 * compresses; memory maintenance happens outside the compaction round
 * (event-time writes + periodic consolidation).
 */
enum CompactionPhase:
  case Compact

case class CompactionJob(
  subagentId: String,
  mode: String,
  replyDeferred: Option[cats.effect.Deferred[IO, Either[String, CompactionResult]]] = None,
  replyTo: Option[ActorRef[AgentEvent]] = None,
  resumeAfterCompact: Boolean = true,
  postCompactInstruction: Option[String] = None,
  phase: CompactionPhase = CompactionPhase.Compact // single-stage: always Compact
)

case class TurnContext(
  agentDef: AgentDef,
  /** 阶段 2 批 A（2026-09）systemPrefix 整层退役——恒空串；字段保留至阶段 3
    * 随 TurnContext 清理一并移除（PromptSections.assembleSystemPrompt 的
    * prefix 参数已同批删除，稳定首段 = agent system.md）。 */
  systemPrefix: String,
  projectRoot: Option[String],
  rulesMd: Option[String],
  /** §E.2: workspace-root AGENTS.md（每 turn 重读盘；仅 project 会话，gating 见
    * ContextRefresher.agentsMdEnabledFor）。默认 None 保构造点兼容。 */
  agentsMd: Option[String] = None,
  thinkingConfig: nebflow.llm.ThinkingConfig,
  branchChange: Option[SystemReminder] = None,
  currentBranch: Option[String] = None,
  skillCatalog: String = "",
  teamCatalog: String = "",
  flowCatalog: String = "",
  memoryBlock: String = ""
)

case class SessionContext(
  sessionId: Option[String] = None,
  sessionName: Option[String] = None,
  recentMessageIds: List[String] = Nil,
  wsSend: Json => IO[Unit] = _ => IO.unit,
  depth: Int = 0,
  readTracker: Option[nebflow.core.tools.ReadTracker] = None,
  fileHistory: Option[nebflow.core.tools.FileHistory] = None,
  contextWindow: Int = nebflow.shared.Defaults.ContextWindow,
  askMode: Option[String] = None,
  language: Option[String] = None,
  projectRoot: Option[String] = None,
  rulesMd: Option[String] = None,
  /** §E.2: workspace-root AGENTS.md spawn 快照（消费权威在 refreshTurn 每 turn
    * 重读盘的 TurnContext.agentsMd）。默认 None 保序列化/构造点兼容。 */
  agentsMd: Option[String] = None,
  folderId: Option[String] = None,
  chatWidth: Int = 0,
  gitBranch: Option[String] = None,
  safetyMode: String = "confirm-edits",
  /**
   * P2: permission-policy bucket + interaction routing anchor. Passed as a
   * constructor parameter at every spawn site (Root=itself, Team=parent root
   * session, Flow/Ephemeral=itself, Delegate=resolved caller root). Falls back
   * to sessionId so legacy spawn sites still get a sane bucket.
   */
  rootSessionId: String = "",
  pendingAskUser: Option[cats.effect.Deferred[IO, List[String]]] = None,
  pendingPermission: Option[cats.effect.Deferred[IO, Boolean]] = None,
  pendingAskUserReplyTo: Option[ActorRef[List[String]]] = None,
  /**
   * When true, agent must call Mail at least once before finishing.
   *  Used by flow agents to enforce structured result reporting.
   */
  expectsMail: Boolean = false,
  /** Total mail turns completed in this session. */
  mailTurnCount: Int = 0,
  /** 阶段 2a 沙箱会话开关（§A.6/H-5①）：true 时 AgentCore 从 projectRoot 派生
    * ToolContext.sandbox（root=worktree 或 workspace；分发器=project workspace）。
    * 置位点 = project 节点（NodeEngine）与分发器（ProjectActor）spawn，以及
    * Nebula 根会话（WebSocketRoutes，2026-09-05 裁定）；team/flow/Delegate 等双轨
    * 会话默认 false=旧行为（§A.7，双轨期不动旧体系）。
    * 边界（[沙箱拆围栏批 S1, 2026-09-10]）：本字段自本批起**只承载「围栏总闸」**
    * ——root 推导 + 闸门开关；「是否项目作用域会话」由 projectSession 独立承载
    * （AGENTS.md 注入判据），两信号不再互为代名词。 */
  sandboxEnabled: Boolean = false,
  /** 显式沙箱根（2026-09-05 21:05 作者裁定——worktree 节点继承项目沙箱）：
    * NodeEngine spawn 点传入项目工作区根，worktree 节点沙箱 root 收敛为工作区
    * 根而非 worktree 目录自身（主仓 .git/worktrees/<name>/ 元数据可直写，git
    * commit 走通）；节点 cwd / projectRoot 工具语义不动，只放宽写边界。None =
    * 沿用 projectRoot 推导（分发器/未接线节点旧行为逐字节不变）。推导权威在
    * SandboxPolicy.sessionRoot。 */
  sandboxRoot: Option[String] = None,
  /** 项目会话信号（沙箱拆围栏批 S1/R8 解耦，2026-09-10）：true = 本会话属项目
    * 作用域（project 节点 / 分发器）——项目级契约文件 AGENTS.md 的注入判据
    * （ContextRefresher.agentsMdEnabledFor）。
    *
    * 与 sandboxEnabled 分道的原因：拆围栏批将退役 sandboxEnabled 这一「围栏总闸」
    * 语义，而 AGENTS.md 注入必须继续生效——继续复用旧信号就会造成「拆围栏顺带
    * 关掉项目契约注入」的静默回归（design §0 结论 4 / R8 的 h2 禁止项）。判据按
    * 会话形态（spawn 置位）而非围栏开关置位，两者生命周期就此解耦。
    *
    * 置位点（call site 口径 3 处）：NodeEngine 节点 spawn ×2（普通节点 / loop
    * worker·verify）+ ProjectActor 分发器 spawn ×1；WebSocketRoutes 的 WS 根会话
    * （含 Nebula）保持 false——AGENTS.md 接收面 = project 分发器 + 节点会话不变。
    * 详见 design §4.4 S1 / §7 交下游纪律 1。 */
  projectSession: Boolean = false,
  /** Last experience extraction timestamp. */
  lastExperienceAt: Option[Long] = None,
  /**
   * True when this agent is a SubTask worker (spawned via SubTaskTool).
   * Workers are leaf agents: no Mail/SubTask/Delegate tools, no team
   * context injection (categoryPrefix/managerPrefix/memoryBlock/teamCatalog/
   * flowCatalog stripped), and a fixed Worker Block appended to the prompt.
   */
  isSubTaskWorker: Boolean = false,
  /**
   * #406: true when this agent is a one-shot FlowExecute node (spawned by
   * FlowDagExecutor.executeAgent with isFlowNode=true). Flow nodes are leaf
   * agents: FlowExecute/FlowTrigger/SubTask/Delegate are stripped to prevent
   * recursive flow-in-flow explosions — the same leaf rule SubTask workers
   * get. Differs from isSubTaskWorker in that Mail stays available for
   * team-category node agents (flow nodes may Mail the caller's team).
   */
  isFlowNode: Boolean = false,
  /**
   * 轨道二 #5：本节点的 userFacing 白名单声明（FlowNode.userFacing 原样透传）。
   * spawn 时从 DAG 定义读入，与 dedicatedAgents 开关解耦——开关在消费点
   * （buildAllowedToolSet 剥离 / PromptSections 条款变体）每 turn 热读，改动
   * nebflow.json 后下个 turn 生效，无需重启或 respawn。
   */
  userFacingNode: Boolean = false,
  /** Project 任务板身份（TaskBoard 批 2 接线，规格 §1d）：flowNodeId = project 节点
    * 会话的 NodeDef.id（NodeEngine spawn 点置位）；isDispatcher = 分发器会话标记
    * （ProjectActor spawn 点置位）。经 AgentCore 透传进 ToolContext——TaskBoard
    * 工具的引擎侧身份判定来源（不信客户端参数）。两字段皆空 = 非项目会话
    * （Nebula/team/flow 双轨/REST）→ 工具未挂载 + 工具内拒绝，双保险不可达。 */
  flowNodeId: Option[String] = None,
  isDispatcher: Boolean = false,
  /** 所属项目名（TaskBoard 批 2 身份链随路接通）：分发器/节点 spawn 注入 →
    * AgentCore 透传 ToolContext.projectName——该字段此前存在但生产代码从未赋值
    * （证据 §6-2），本批接通后 Node 系工具的 project 缺省解析（NodeTools.
    * resolveProject fallback 链）在节点会话内也生效。None = 非项目会话。 */
  projectName: Option[String] = None,
  /** 节点人类可读名（D6 批 F1 G9 路径 a：spawn 置位随路注入，spec §3.3）——
    * NodeEngine 置 node.name（loop worker/verify 同属该 loop 节点名）；
    * AskUser payload 的 nodeName 字段来源（badge「project · nodeName」归因）。
    * None = 非项目节点会话（Nebula/分发器/REPL——分发器由 isDispatcher 标注）。 */
  flowNodeName: Option[String] = None,
  /** 链级抽象 P2（20260910 process-doc-chain-attribution spec §9.2 项 2）：本节点
    * 所属链 id（NodeEngine 节点 spawn 时经 FlowMapStore.chainIdOf 判据单点取
    * spawn 时刻快照注入；分量成员数 ≥2 才带值）。经 AgentCore 透传
    * ToolContext.flowChainId——节点把 `chain:` 写进过程文档元数据头的值来源。
    * 分发器/非项目会话/孤立单节点分量 = None（分发器口径显式化见 ProjectActor
    * spawn 点）。快照语义见 ToolContext.flowChainId 注释。 */
  flowChainId: Option[String] = None,
  /**
   * D11 交互豁免（freeze-schedule spec v1.1）：用户在场等待的交互会话
   * 不参与冻结——冻结它们省下的 token 远低于浪费的用户等待时间。
   * 其余 spawn 点默认 false 零改动。ask 轮的豁免走
   * gate 内的 askMode.isDefined 检查，不经此字段。
   */
  freezeExempt: Boolean = false
)

case class InteractionState(
  pendingAskUser: Option[cats.effect.Deferred[IO, List[String]]] = None,
  pendingPermission: Option[cats.effect.Deferred[IO, Boolean]] = None,
  pendingAskUserReplyTo: Option[ActorRef[List[String]]] = None
)

/** Tracks what was last dispatched (LLM call or tool execution) for Retry. */
case class LastDispatch(
  isToolExecution: Boolean,
  llmResult: Option[ConsumeResult] = None
)

case class ExecutionContext(
  messages: List[Message] = Nil,
  status: AgentStatus = AgentStatus.Idle,
  turnIdx: Int = 0,
  currentTurnId: Long = 0L,
  interaction: Option[InteractionState] = None,
  pendingEvents: List[AgentCommand.ExternalEvent] = Nil,
  /**
   * Sub-agent result barrier (2026-08-18, worker blocking semantics): number of
   * parallel sub-agent (Delegate/SubTask) results still awaited before their
   * batch is delivered to the agent. Incremented at ToolsComplete by the count
   * of Delegate/SubTask calls made that turn; decremented on each ExternalEvent
   * with source "subtask"/"delegate" (completed OR failed — failures still
   * count as results, so a crashed worker cannot stall the barrier forever).
   *
   * While > 0, subtask/delegate result events are HELD in [[pendingEvents]] —
   * the agent is NOT interrupted one result at a time. When the counter
   * reaches 0, ALL held results are injected together in a single turn.
   *
   * In-memory only (not persisted across crash recovery, same as
   * pendingEvents): a crash mid-batch degrades to per-result delivery.
   */
  outstandingSubagentResults: Int = 0,
  /**
   * #25 (nested delegation dead-letter): completion-notification debt. When a
   * turn ends while outstandingSubagentResults > 0, the replyTo that would
   * have received AgentEvent.Completed is PARKED here instead of being sent —
   * the actor lifecycle must not treat "turn ended" as "task completed" while
   * spawned sub-agents are still in flight (the supervisor adapter would stop
   * this actor and the grandchildren's results would dead-letter). When a
   * later turn ends with the barrier at 0, every parked ref receives the
   * Completed event — carrying the FINAL synthesized text — and the debt
   * clears. In-memory only (same lifecycle as pendingEvents): a crash
   * mid-flight degrades to the pre-#25 notification-less state.
   */
  owedCompletion: List[ActorRef[AgentEvent]] = Nil,
  pendingImmediateInputs: List[AgentCommand.ImmediateInput] = Nil,
  emptyResponseRetries: Int = 0,
  lastDispatch: Option[LastDispatch] = None,
  delegateCount: Int = 0,
  lastMaintenanceDelegateCount: Int = 0,
  // Snapshot of messages.size at turn start. Used to scope the expectsMail
  // check so only Mail calls within the current turn count. Persisted to
  // TurnStateStore for crash recovery — restored via ResumeTurn.
  turnStartMessageCount: Int = 0,
  // Per-turn flag: true once the agent called the Mail tool at any point during
  // the current turn (set in ToolsComplete, reset when a new turn starts). This
  // is an event-driven flag, NOT a message-index scan, so it survives
  // compaction (which rewrites/shortens `messages` and would invalidate any
  // index-based check). It captures the user's intent: "from user input → to
  // LLM finish/badge, did any tool call in that whole span include Mail?"
  mailUsedThisTurn: Boolean = false,
  // How many "you must call Mail" system-reminders have been injected this turn
  // without the agent subsequently calling Mail. Bounded by MaxMailReminders so
  // a flow agent that keeps producing text-without-Mail cannot loop forever —
  // after the cap it is allowed to finishTurn (emit agentDone, release busy).
  mailReminders: Int = 0,
  // Consecutive transient LLM failures auto-retried this turn (bounded by
  // AgentActor.LlmFailRetryMax). Reset on any successful LLM completion.
  llmFailRetries: Int = 0,
  // Per-turn LLM RETRY counter (2026-08-18 token incident, plan C): counts
  // ONLY failed-retry re-dispatches (incremented in the AgentActor LlmFailed
  // retry branch). Normal tool-loop calls do NOT count — tool-intensive agents
  // (read → edit → compile → ...) never trip it. Bounded by
  // Fallback.MaxTurnLlmCalls — exceeding it raises TurnBudgetExceeded
  // (Permanent) so the turn fails fast instead of amplifying token spend via
  // full-context re-dispatches. Monotonic within the turn; reset when the
  // turn ends (ExecutionContext.idle rebuilds the counter to 0).
  llmCallsThisTurn: Int = 0,
  // Pending queue mails (delivery=queue): counter only — actual items live on
  // disk (MailQueueStore). Drained one per turn at turn end, after immediate
  // inputs. Incremented by MailQueued in processing state, reset when drained.
  pendingMailQueueCount: Int = 0,
  // User inputs (UserInput/SkillActivate/AskQuestion) that arrived while the
  // agent was busy (processing). Drained one per turn boundary — the head is
  // re-sent to self so the idle handler processes it with full metadata.
  // Replaces the dead-end `pending` function parameter on the processing
  // behavior (messages entered but were never drained).
  pendingUserInputs: List[AgentCommand] = Nil,
  /**
   * P0 阶段 3（2026-08-18，设计 §4.4）：最近一次 turn 活动时间戳——状态层
   * 字段（registry 层权威源见 AgentRecord.lastActivityMs，TaskStuckWatcher
   * 读它；本字段供 AgentState 使用与二期前端展示）。touch 点见 touchActivity。
   */
  lastActivityMs: Long = 0L,
  /**
   * v2 冻结式错误恢复（§3.3）：同 reason 连续 ErrorFrozen 进入次数（跨恢复
   * 累计——resumed 续跑失败仍算连续，直至 turn 成功完成回 idle 清零）。
   * Schedule 时间表冻结不参与计数。≥3 触发升级链（不再直接 fatal）。
   * 内存态：ExecutionContext.idle 重建即清零（续跑成功=问题缓解）。
   */
  errorFreezeCount: Int = 0,
  /** v2：上次错误冻结的 reason——判定「同 reason 连续」；reason 变化重置计数。 */
  lastErrorFreezeReason: Option[FreezeReason] = None,
  /** v2 升级链状态（§5.2，P0 内存态）：进入升级链后挂起，父决策/超时升级时更新。 */
  escalation: Option[EscalationInfo] = None
)

/** P0 阶段 3：touch turn 活动戳（幂等——仅更新时间戳，不改变其他状态）。 */
extension (e: ExecutionContext)
  def touchActivity(now: Long = System.currentTimeMillis()): ExecutionContext =
    e.copy(lastActivityMs = now)

object ExecutionContext:

  def idle(messages: List[Message], turnIdx: Int = 0, currentTurnId: Long = 0L): ExecutionContext =
    ExecutionContext(
      messages = messages,
      status = AgentStatus.Idle,
      turnIdx = turnIdx,
      currentTurnId = currentTurnId,
      interaction = None,
      pendingEvents = Nil,
      pendingImmediateInputs = Nil,
      emptyResponseRetries = 0,
      turnStartMessageCount = 0,
      mailUsedThisTurn = false,
      mailReminders = 0
    )
end ExecutionContext

case class CompactionState(
  pendingJob: Option[CompactionJob] = None,
  compactionFailures: Int = 0,
  lastCompactionFailureAt: Long = 0L,
  latestUsage: Option[TokenUsage] = None,
  lastModel: Option[String] = None
)

case class AgentSessionInfo(
  address: String,
  agentName: String,
  taskDescription: String,
  status: String = "running",
  createdAt: Long = System.currentTimeMillis()
)

/**
 * Snapshot of the dynamic values injected into systemStable at its last build
 * (cache-optimization v2). systemStable is rebuilt only at lifecycle nodes
 * (new session / compaction complete / restart); mid-session changes to these
 * values are reported via change reminders instead of invalidating the cache.
 */
case class SystemStableSnapshot(
  devices: String = "",
  sessions: String = "",
  language: Option[String] = None,
  envInfo: String = "",
  /** Mounted-project list body at systemStable build time (cache v2 change
    * detection). Only populated for the root Nebula agent; "" for others. */
  mountedProjects: String = ""
)

case class AgentState(
  session: SessionContext,
  execution: ExecutionContext,
  compaction: CompactionState,
  agentSessions: List[AgentSessionInfo],
  /**
   * Last built systemStable string — reused on non-lifecycle turns.
   *
   * Restart invariant (2026-08-15 FlowTrigger outage audit): AgentState is
   * NEVER persisted — a JVM restart produces a fresh actor whose cache is
   * None, so the first restored turn always takes the isLifecycleRebuild
   * path and rebuilds systemStable from the CURRENT AgentDef (tools, flows,
   * skills sections). That is what makes "restart" a lifecycle node per the
   * cache-optimization design. If AgentState ever becomes persisted, the
   * restore path MUST clear cachedSystemStable (invalidateSystemStableCache)
   * or the restored session keeps advertising pre-restart tool sections.
   */
  cachedSystemStable: Option[String],
  /** Dynamic values at the time systemStable was last built (change detection). */
  stableSnapshot: Option[SystemStableSnapshot],
  /** Block 3 循环检测器计数器（supervision trio §D1）：顶层——S3 跨 turn 保留
    * （turn 边界只清 S1 与 R 连续重复计数，见 LoopGuard.evaluate 的 turnKey 判定）。 */
  loopCounters: nebflow.core.processor.LoopGuard.Counters,
  /** Block 3：逻辑 turn 纪元（每次真实 turn 开始 +1——UserInput/ExternalEvent
    * 唤醒/Mail 激活/冻结唤醒等 dispatch 起点；ToolsComplete 续轮/retry/压缩
    * 续跑不递增）。LoopGuard 的 turnKey 来源——currentTurnId 是每次 LLM
    * dispatch 都 +1 的序号（wiring 实证），不能当 turn 身份用。
    * （默认值只在 object AgentState.apply 提供——case class 字段带默认会与
    * 自定义 apply 的全默认参数形成重载冲突。） */
  loopTurnKey: Long
)

object AgentState:

  def apply(
    messages: List[Message] = Nil,
    status: AgentStatus = AgentStatus.Idle,
    depth: Int = 0,
    sessionId: Option[String] = None,
    sessionName: Option[String] = None,
    pendingCompaction: Option[CompactionJob] = None,
    compactionFailures: Int = 0,
    latestUsage: Option[TokenUsage] = None,
    pendingAskUser: Option[cats.effect.Deferred[IO, List[String]]] = None,
    pendingPermission: Option[cats.effect.Deferred[IO, Boolean]] = None,
    turnIdx: Int = 0,
    wsSend: Json => IO[Unit] = _ => IO.unit,
    readTracker: Option[nebflow.core.tools.ReadTracker] = None,
    fileHistory: Option[nebflow.core.tools.FileHistory] = None,
    recentMessageIds: List[String] = Nil,
    contextWindow: Int = nebflow.shared.Defaults.ContextWindow,
    projectRoot: Option[String] = None,
    rulesMd: Option[String] = None,
    agentsMd: Option[String] = None,
    folderId: Option[String] = None,
    safetyMode: String = "confirm-edits",
    gitBranch: Option[String] = None,
    expectsMail: Boolean = false,
    rootSessionId: String = "",
    isSubTaskWorker: Boolean = false,
    freezeExempt: Boolean = false,
    isFlowNode: Boolean = false,
    userFacingNode: Boolean = false,
    flowNodeId: Option[String] = None,
    isDispatcher: Boolean = false,
    projectName: Option[String] = None,
    flowNodeName: Option[String] = None,
    /** 链级抽象 P2（§9.2 项 2）：节点所属链 id 快照（None = 无链/非项目会话）。 */
    flowChainId: Option[String] = None,
    sandboxEnabled: Boolean = false,
    sandboxRoot: Option[String] = None,
    /** 项目会话信号（沙箱拆围栏批 S1/R8 解耦）：spawn 侧置位，见
      * SessionContext.projectSession。默认 false = 非项目会话（WS 根会话 /
      * team / flow / Delegate / SubTask 双轨面）语义与旧行为逐字节不变。 */
    projectSession: Boolean = false,
    loopTurnKey: Long = 0L
  ): AgentState =
    val interaction = (pendingAskUser, pendingPermission) match
      case (None, None) => None
      case _ => Some(InteractionState(pendingAskUser, pendingPermission))
    new AgentState(
      SessionContext(
        sessionId = sessionId,
        sessionName = sessionName,
        recentMessageIds = recentMessageIds,
        wsSend = wsSend,
        depth = depth,
        readTracker = readTracker,
        fileHistory = fileHistory,
        contextWindow = contextWindow,
        folderId = folderId,
        projectRoot = projectRoot,
        rulesMd = rulesMd,
        agentsMd = agentsMd,
        gitBranch = gitBranch,
        safetyMode = safetyMode,
        expectsMail = expectsMail,
        rootSessionId = rootSessionId,
        isSubTaskWorker = isSubTaskWorker,
        freezeExempt = freezeExempt,
        isFlowNode = isFlowNode,
        userFacingNode = userFacingNode,
        flowNodeId = flowNodeId,
        isDispatcher = isDispatcher,
        projectName = projectName,
        flowNodeName = flowNodeName,
        flowChainId = flowChainId,
        sandboxEnabled = sandboxEnabled,
        sandboxRoot = sandboxRoot,
        projectSession = projectSession
      ),
      ExecutionContext(messages, status, turnIdx, 0L, interaction),
      CompactionState(pendingCompaction, compactionFailures, 0L, latestUsage),
      Nil,
      None,
      None,
      nebflow.core.processor.LoopGuard.Counters.Empty,
      loopTurnKey
    )
  end apply
end AgentState

extension (s: AgentState)
  def withLoopCounters(c: nebflow.core.processor.LoopGuard.Counters): AgentState =
    s.copy(loopCounters = c)

  /** Block 3：turn 纪元 +1——真实 turn 开始的 dispatch 点调用
    *（UserInput/ExternalEvent 唤醒/Mail 激活/冻结唤醒/队列 drain）；
    * ToolsComplete 续轮、retry、save/compact 续跑不递增。 */
  def withNextLoopTurn: AgentState =
    s.copy(loopTurnKey = s.loopTurnKey + 1)

extension (s: AgentState)
  def messages: List[Message] = s.execution.messages
  def status: AgentStatus = s.execution.status
  def sessionId: Option[String] = s.session.sessionId
  def sessionName: Option[String] = s.session.sessionName
  def wsSend: Json => IO[Unit] = s.session.wsSend
  def depth: Int = s.session.depth
  def turnIdx: Int = s.execution.turnIdx
  def currentTurnId: Long = s.execution.currentTurnId
  def recentMessageIds: List[String] = s.session.recentMessageIds
  def pendingCompaction: Option[CompactionJob] = s.compaction.pendingJob
  def compactionFailures: Int = s.compaction.compactionFailures
  def lastCompactionFailureAt: Long = s.compaction.lastCompactionFailureAt
  def latestUsage: Option[TokenUsage] = s.compaction.latestUsage
  def lastModel: Option[String] = s.compaction.lastModel
  def emptyResponseRetries: Int = s.execution.emptyResponseRetries
  def mailUsedThisTurn: Boolean = s.execution.mailUsedThisTurn
  def mailReminders: Int = s.execution.mailReminders
  def pendingAskUser: Option[cats.effect.Deferred[IO, List[String]]] = s.execution.interaction.flatMap(_.pendingAskUser)

  def pendingPermission: Option[cats.effect.Deferred[IO, Boolean]] =
    s.execution.interaction.flatMap(_.pendingPermission)
  def readTracker: Option[nebflow.core.tools.ReadTracker] = s.session.readTracker
  def fileHistory: Option[nebflow.core.tools.FileHistory] = s.session.fileHistory
  def contextWindow: Int = s.session.contextWindow
  def askMode: Option[String] = s.session.askMode
  def language: Option[String] = s.session.language
  def projectRoot: Option[String] = s.session.projectRoot
  def sandboxEnabled: Boolean = s.session.sandboxEnabled
  def sandboxRoot: Option[String] = s.session.sandboxRoot
  /** 项目会话信号（沙箱拆围栏批 S1/R8 解耦）：AGENTS.md 注入判据的来源，见
    * SessionContext.projectSession。 */
  def projectSession: Boolean = s.session.projectSession
  def rulesMd: Option[String] = s.session.rulesMd
  def agentsMd: Option[String] = s.session.agentsMd
  def folderId: Option[String] = s.session.folderId
  def gitBranch: Option[String] = s.session.gitBranch
  def safetyMode: String = s.session.safetyMode
  def rootSessionId: String = s.session.rootSessionId
  def expectsMail: Boolean = s.session.expectsMail
  def isSubTaskWorker: Boolean = s.session.isSubTaskWorker
  def isFlowNode: Boolean = s.session.isFlowNode
  def userFacingNode: Boolean = s.session.userFacingNode
  def flowNodeId: Option[String] = s.session.flowNodeId
  def isDispatcher: Boolean = s.session.isDispatcher
  def projectName: Option[String] = s.session.projectName
  def flowNodeName: Option[String] = s.session.flowNodeName
  /** 链级抽象 P2（§9.2 项 2）：本节点所属链 id 快照（None = 无链/非项目会话）。 */
  def flowChainId: Option[String] = s.session.flowChainId

  def withSession(session: SessionContext): AgentState = s.copy(session = session)
  def withExecution(execution: ExecutionContext): AgentState = s.copy(execution = execution)
  def withCompaction(compaction: CompactionState): AgentState = s.copy(compaction = compaction)
  def withAgentSessions(sessions: List[AgentSessionInfo]): AgentState = s.copy(agentSessions = sessions)
  def withMessages(msgs: List[Message]): AgentState = s.copy(execution = s.execution.copy(messages = msgs))
  def withStatus(st: AgentStatus): AgentState = s.copy(execution = s.execution.copy(status = st))
  def withTurnIdx(idx: Int): AgentState = s.copy(execution = s.execution.copy(turnIdx = idx))

  def withTurnStart(count: Int): AgentState =
    s.copy(execution = s.execution.copy(turnStartMessageCount = count))
  def withCurrentTurnId(id: Long): AgentState = s.copy(execution = s.execution.copy(currentTurnId = id))

  def withMailUsedThisTurn(b: Boolean): AgentState =
    s.copy(execution = s.execution.copy(mailUsedThisTurn = b))

  def withMailReminders(n: Int): AgentState =
    s.copy(execution = s.execution.copy(mailReminders = n))

  def withInteraction(interaction: Option[InteractionState]): AgentState =
    s.copy(execution = s.execution.copy(interaction = interaction))

  def withLastDispatch(d: Option[LastDispatch]): AgentState =
    s.copy(execution = s.execution.copy(lastDispatch = d))

  def lastDispatch: Option[LastDispatch] = s.execution.lastDispatch

  def withPendingAskUser(d: Option[cats.effect.Deferred[IO, List[String]]]): AgentState =
    s.copy(execution =
      s.execution.copy(interaction =
        Some(s.execution.interaction.getOrElse(InteractionState()).copy(pendingAskUser = d))
      )
    )

  def withPendingPermission(d: Option[cats.effect.Deferred[IO, Boolean]]): AgentState =
    s.copy(execution =
      s.execution.copy(interaction =
        Some(s.execution.interaction.getOrElse(InteractionState()).copy(pendingPermission = d))
      )
    )
  def withRecentMessageIds(ids: List[String]): AgentState = s.copy(session = s.session.copy(recentMessageIds = ids))
  def withContextWindow(window: Int): AgentState = s.copy(session = s.session.copy(contextWindow = window))
  def withAskMode(mode: Option[String]): AgentState = s.copy(session = s.session.copy(askMode = mode))
  def withLanguage(lang: Option[String]): AgentState = s.copy(session = s.session.copy(language = lang))
  def mailTurnCount: Int = s.session.mailTurnCount
  def withMailTurnCount(count: Int): AgentState = s.copy(session = s.session.copy(mailTurnCount = count))
  def lastExperienceAt: Option[Long] = s.session.lastExperienceAt
  def withLastExperienceAt(ts: Long): AgentState = s.copy(session = s.session.copy(lastExperienceAt = Some(ts)))

  def withPendingCompaction(job: Option[CompactionJob]): AgentState =
    s.copy(compaction = s.compaction.copy(pendingJob = job))

  def withCompactionFailures(failures: Int): AgentState =
    s.copy(compaction = s.compaction.copy(compactionFailures = failures))

  def withLastCompactionFailureAt(ts: Long): AgentState =
    s.copy(compaction = s.compaction.copy(lastCompactionFailureAt = ts))

  def withEmptyResponseRetries(count: Int): AgentState =
    s.copy(execution = s.execution.copy(emptyResponseRetries = count))
  def llmFailRetries: Int = s.execution.llmFailRetries

  def withLlmFailRetries(count: Int): AgentState =
    s.copy(execution = s.execution.copy(llmFailRetries = count))

  def llmCallsThisTurn: Int = s.execution.llmCallsThisTurn

  def withLlmCallsThisTurn(count: Int): AgentState =
    s.copy(execution = s.execution.copy(llmCallsThisTurn = count))

  def errorFreezeCount: Int = s.execution.errorFreezeCount

  def lastErrorFreezeReason: Option[FreezeReason] = s.execution.lastErrorFreezeReason

  def escalation: Option[EscalationInfo] = s.execution.escalation

  /** v2 冻结式错误恢复：计数 + reason 记录（内存态，turn 完成回 idle 自动清零）。 */
  def withErrorFreezeCount(count: Int, reason: FreezeReason): AgentState =
    s.copy(execution = s.execution.copy(errorFreezeCount = count, lastErrorFreezeReason = Some(reason)))

  def withEscalation(esc: Option[EscalationInfo]): AgentState =
    s.copy(execution = s.execution.copy(escalation = esc))
  def withGitBranch(branch: Option[String]): AgentState = s.copy(session = s.session.copy(gitBranch = branch))

  def withSafetyMode(mode: String): AgentState =
    s.copy(session = s.session.copy(safetyMode = mode))

  // --- Cache v2: systemStable + dynamic snapshot (lifecycle-node updates) ---

  /** Store the rebuilt systemStable and the dynamic snapshot it was built from. */
  def withSystemStableCache(stable: String, snapshot: SystemStableSnapshot): AgentState =
    s.copy(cachedSystemStable = Some(stable), stableSnapshot = Some(snapshot))

  /** Mark the cache for rebuild (called at compaction complete / session reset). */
  def invalidateSystemStableCache: AgentState =
    s.copy(cachedSystemStable = None, stableSnapshot = None)

  def delegateCount: Int = s.execution.delegateCount
  def lastMaintenanceDelegateCount: Int = s.execution.lastMaintenanceDelegateCount

  def withDelegateCount(count: Int): AgentState =
    s.copy(execution = s.execution.copy(delegateCount = count))

  def withLastMaintenanceDelegateCount(count: Int): AgentState =
    s.copy(execution = s.execution.copy(lastMaintenanceDelegateCount = count))

  def outstandingSubagentResults: Int = s.execution.outstandingSubagentResults

  def withOutstandingSubagentResults(count: Int): AgentState =
    s.copy(execution = s.execution.copy(outstandingSubagentResults = count))

  /** #25: set the parked completion-notification debt. */
  def withOwedCompletion(targets: List[ActorRef[AgentEvent]]): AgentState =
    s.copy(execution = s.execution.copy(owedCompletion = targets))

  def owedCompletion: List[ActorRef[AgentEvent]] = s.execution.owedCompletion

  def withLatestUsage(usage: Option[TokenUsage]): AgentState =
    s.copy(compaction = s.compaction.copy(latestUsage = usage))
  def withLastModel(model: Option[String]): AgentState = s.copy(compaction = s.compaction.copy(lastModel = model))

  def updateContextWindowIfNeeded(reported: Option[Int]): AgentState = reported match
    case Some(cw) if cw != s.session.contextWindow => s.copy(session = s.session.copy(contextWindow = cw))
    case _ => s

  def resetToIdle(messages: List[Message], turnIdx: Int = s.execution.turnIdx): AgentState =
    s.copy(execution = ExecutionContext.idle(messages, turnIdx, s.execution.currentTurnId)
      // Sub-agent barrier: already-received results held for batch delivery are
      // still due to the agent — survive the reset (the workers keep running).
      .copy(pendingEvents = s.execution.pendingEvents,
            outstandingSubagentResults = s.execution.outstandingSubagentResults,
            // #25: a parked completion notification is still owed — the
            // supervisor/bridge is still waiting for the final answer.
            owedCompletion = s.execution.owedCompletion))

  def resetForInterrupt: AgentState = s.copy(
    execution = ExecutionContext.idle(s.execution.messages, s.execution.turnIdx, s.execution.currentTurnId)
      // Sub-agent barrier: held results survive an interrupt — they are still due.
      .copy(pendingEvents = s.execution.pendingEvents,
            outstandingSubagentResults = s.execution.outstandingSubagentResults,
            // #25: parked completion debt survives an interrupt/restart —
            // the waiting requester is still owed the final answer.
            owedCompletion = s.execution.owedCompletion,
            // #13: undelivered immediate inputs (queued Mail) survive
            // interrupt/restart — they are user-originated work; resetting
            // them away silently dropped tasks on every restartAgent.
            pendingImmediateInputs = s.execution.pendingImmediateInputs),
    compaction = s.compaction.copy(pendingJob = None)
  )
end extension

case class ConsumeResult(
  text: String,
  toolCalls: List[ToolCall],
  results: List[(ToolCall, ToolExecResult)],
  stopReason: Option[String],
  usage: Option[TokenUsage] = None,
  thinking: Option[String] = None,
  thinkingSignature: Option[String] = None,
  model: Option[String] = None,
  contextWindow: Option[Int] = None,
  /** 当轮 LLM 请求 id（审计 20260903 方案 B）：pipeLlmCall 生成并同时传给
    * LlmLogWriter（router JSONL 的 request_id）与本字段；工具执行轮经
    * pipeToolExecutions 流入 ToolContext.requestId，实现工具日志与 router
    * 日志精确对齐。Retry 重跑同一 cr 时 id 不变（同一 LLM 响应）。 */
  requestId: Option[String] = None
)
