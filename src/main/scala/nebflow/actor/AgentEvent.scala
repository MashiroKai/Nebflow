/* 从 protocol.scala 迁出(行为保持重构,2026-09-25)。 */
package nebflow.actor

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.ActorRef
import nebflow.shared.*

// ============================================================
// P2 — InteractionHub protocol
//
// 2026-09-13（permshield S1）：`PermissionPolicy` 与其每-root-会话桶**已删除**。
// 它是「会话级覆盖」的载体（`safetyMode` 档位），而档位自本批起只有应用级唯一
// 来源（`GlobalSafety.defaultMode`）；`allow`/`deny` 两个"模型预留"集合在全仓
// **从未被任何写入点写过**（恒空 ⇒ 判定恒等于 `ToolReversibility` 一条规则），
// 因此一并删除，不留无人消费的字段。
// ============================================================

/**
 * Interaction kind — what the user is being asked (P2).
 *
 * P0-1 / P-M1（2026-09-20，机制裁点 S3 = a）：新增第三种 kind `McpPermission`
 * —— MCP / ScriptTool 调用的审批卡。与既有两种的关系（spec §2.4 + roadmap §2.2 A10）：
 *   · `Permission`：内置工具审批卡（`type=askPermission`；payload 带完整 `input`）
 *   · `AskUser`：提问卡（唯一进 chat-input passthrough 的 kind）
 *   · `McpPermission`：**新 kind**（`type=mcpPermission`）——payload/answer 形状与
 *     `Permission` 分化（凭据 redact 摘要 + riskTier/declared/hostBanner），
 *     升级选项按 tier 门控；不新建机制，只加分支（零渲染层重构）。
 */
enum InteractionKind:
  case Permission, AskUser, McpPermission

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
   * P0-1（spec §2.4/§2.5）：mcpPermission 卡的答复面。比 `PermissionReply` 多带
   * 可选 `scope`（P0-2 会话放行）与 `upgradeMode`（递进放行，走既有
   * `PermissionUpgrade.parse` 语义，零改动）——故独立 reply 型别而非扩既有型别
   * （内置工具审批链逐字不动，A1-8 零回归）。
   */
  final case class McpPermissionReply(deferred: cats.effect.Deferred[IO, nebflow.shared.McpPermissionAnswer])
      extends InteractionReply

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

/**
 * Block 3 循环检测器 L1（supervision trio §D3）：同参同败超阈 / 轮预算超限的
 * turn 级终止。分类=Permanent（不重试不冻结）→ LlmFailed fatal 链（supervisor
 * notify / team 成员父 ExternalEvent(failed, retryable=true) / 持久化）。
 */
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

/**
 * 冻结原因（v2 冻结式错误恢复，§3.1）：统一「暂停在 dispatch 边界」的语义，
 * reason 决定恢复条件与前端两族视觉（schedule=sapphire 冷色 / 错误族=amber 暖色）。
 * 缺省 Schedule 保证旧调用点零改动（时间表冻结=特例）。
 */
enum FreezeReason:
  case Schedule // 时间表冻结（#337 黑名单），恢复条件=出冻结段
  case LlmTransient // LLM transient 错误预算耗尽（429/529 overload），恢复条件=退避到期+provider 恢复
  case Network // 连接重置/超时/未知瞬态，恢复条件=短退避到期
  case ProviderDown // 全候选 provider Down，恢复条件=HealthMonitor 探测恢复（P0 用 R1 轮询兜底）
  case RestartRecovery // 崩溃/进程重启后重建，恢复条件=条件检查通过后立即续跑
  case Loop // Block 3 循环检测器 L2（supervision trio §D3）：恢复条件=仅人工（用户输入唤醒 / AgentControl restart / cancel）——不自动续跑

  /**
   * #692（网络不可达退避等待修批，2026-10-21 作者令）：网络/供应商不可达的**指数
   * 退避等待**——网络级失败不进终态，5s→15s→45s→…封顶 5min 逐轮加长，直到恢复
   * 或作者取消。与 Network 的分界：Network = 短退避（10-30s）后自动重试 1-2 次
   * 的抖动形态；WaitingNetwork = 明确「等网络回来」的长等待形态（状态显式暴露
   * 「等待网络/供应商恢复」，面板可见）。不参与升级链（等待无父干预语义——作者
   * 面前的取消按钮就是出口）。
   */
  case WaitingNetwork

end FreezeReason

/**
 * 冻结的**呈现分类**（冻结态误报批 2026-11-05，作者报障逐字：「mail 给近路命中这会话
 * 让它恢复工作，它显示已冻结，但是我没有冻结它，而且也没有显示恢复按钮」）。
 *
 * `AgentStatus.Frozen` 是**一个**枚举值，却承载两类截然不同的处境：
 *   ① 系统在等 / 在退避，**马上会自己好**（时间表出窗、LLM/网络退避、供应商探测）；
 *   ② 引擎停在这里**只能由人决定**（循环检测 L2、delegate 错误冻结、已升级到用户）。
 * 两者都画成「已冻结」时，用户读到的是「有人替我做了一个决定、现在要我去解」——
 * 既不知道是谁冻的，也找不到恢复按钮（正是本轮报障的形态）。
 *
 * 因此把三条判定轴从状态枚举里分出来：谁停的 / 能不能自己好（autoRecoverable）/
 * 要不要人（needsHuman）。**单一判定点在本对象**：线包（`AgentStreamEvent.Frozen`
 * 的 frozen/agentFrozen 帧）、快照（`activeAgentEntryJson`）、工具面（`AgentControl
 * status`）与控制台徽标/按钮共用同一张表——禁止各面各写一份。
 */
enum FreezeKind:
  /** 系统等待（时间表工作时段）：到点自己恢复。 */
  case WaitingWindow

  /** 自动恢复中的退避/重试：到期自动续跑，无需人。 */
  case AutoRecovering

  /** 需要人决策：引擎不会自己恢复。 */
  case NeedsHuman

  /** 线值（WS 帧 / 快照字段；小写短横线，与既有 reason 词表同规格）。 */
  def wire: String = this match
    case FreezeKind.WaitingWindow => "waiting-window"
    case FreezeKind.AutoRecovering => "auto-recovering"
    case FreezeKind.NeedsHuman => "needs-human"

end FreezeKind

object FreezeKind:

  def fromWire(w: String): Option[FreezeKind] = Option(w).getOrElse("").trim match
    case "waiting-window" => Some(FreezeKind.WaitingWindow)
    case "auto-recovering" => Some(FreezeKind.AutoRecovering)
    case "needs-human" => Some(FreezeKind.NeedsHuman)
    case _ => None

/**
 * 一次冻结的呈现判定结果（纯数据；`FreezeDisplay.of` / `FreezeDisplay.ofWire` 是唯一
 * 构造点）。labelZh/labelEn = 面向用户的一句话（停在哪 + 在等什么，**不含**「已冻结」
 * 除非确实需要人）；hintZh/hintEn = 需要人时该做什么（不需要人 = 空串——不许给不存在
 * 的动作指路）。
 */
case class FreezeDisplay(
  kind: FreezeKind,
  reasonWire: String,
  needsHuman: Boolean,
  autoRecoverable: Boolean,
  labelZh: String,
  labelEn: String,
  hintZh: String,
  hintEn: String
)

object FreezeDisplay:

  /**
   * 「自动恢复」承诺的最长时限（默认 24h）：`resumeAt` 超过它而 reason 仍是错误族，
   * 说明这条冻结事实上**不会自己好**（Loop 族 resumeAt = +1 年），此时按需人工呈现。
   * 判词诚实铁律：宁可说「不会自动恢复」，也不许把长停说成「马上重试」。
   */
  val AutoClaimHorizonMs: Long = 24L * 3600L * 1000L

  /** 需人介入时的统一动作说明（三个真实出口：唤醒续跑 / 断点重启 / 取消任务）。 */
  val ManualHintZh: String = "引擎不会自动续跑：唤醒它接着做、或让它从断点重启、或取消任务"

  val ManualHintEn: String =
    "the engine will NOT resume it on its own — wake it to continue, restart it from the checkpoint, or cancel the task"

  /**
   * **基础设施故障冻结**（provider 故障族）的动作说明（2026-11-05 10:36 作者令逐字：
   * 「不论是节点还是delegate，由于provider 故障，都不需要向上通知，冻结等待恢复就行。
   * 然后在面板显示，这个是需要用户去处理的，我们引擎要做的就行检测恢复了能接着工作。
   * 所以不需要向上汇报。因为provider 故障上级也基本死了，汇报给上级没用。」）：
   * 引擎**自己**探测供应商可用性并在恢复后续跑（同一会话、续在检查点、次数上限 +
   * 递增间隔），所以这一族**不再**说「引擎不会自动续跑」（那句话只对 `loop` 这类
   * 确定性死循环成立）；人也随时可以点「唤醒」立即续跑。
   */
  val InfraHintZh: String =
    "供应商恢复后引擎会自动续跑（同一会话、续在检查点，已完成的不重做，有次数上限与递增间隔）；" +
      "也可以现在就点「唤醒」让它接着做"

  val InfraHintEn: String =
    "the engine resumes it on its own once a provider is serving again (same session, from the checkpoint, " +
      "finished work is not redone, capped attempts with growing gaps) — or wake it now"

  /** provider 故障族（基础设施故障，非任务失败）的 delegate 冻结原因码。 */
  val InfraReasonCodes: Set[String] = Set("llm-failure", "cancelled-by-quota")

  /** 该 delegate 冻结原因码是否属 provider 故障族（基础设施故障，不是任务失败）。 */
  def isInfraReasonCode(code: String): Boolean = InfraReasonCodes.contains(Option(code).getOrElse("").trim)

  /**
   * `delegate-frozen:<reason>` 线值的中文短名。与
   * `nebflow.core.delegate.DelegateFreeze.reasonLabelZh` 同词（本件在 actor 层，
   * 分层门禁不许 actor 依赖 core.delegate ⇒ 这张 4 词表在此显式镜像；改词先改
   * core 侧正文，此处同步）。
   */
  private val DelegateReasonLabels: Map[String, String] = Map(
    "llm-failure" -> "模型层故障",
    "tool-failure" -> "工具层故障",
    "engine-fault" -> "引擎侧故障",
    "cancelled-by-quota" -> "配额耗尽取消"
  )

  private def auto(
    reasonWire: String,
    labelZh: String,
    labelEn: String,
    farFuture: Boolean
  ): FreezeDisplay =
    if farFuture then
      FreezeDisplay(
        kind = FreezeKind.NeedsHuman,
        reasonWire = reasonWire,
        needsHuman = true,
        autoRecoverable = false,
        labelZh = s"已暂停 · $labelZh（超过自动恢复时限，不会自己好）",
        labelEn = s"Paused — $labelEn (past the auto-recovery horizon)",
        hintZh = ManualHintZh,
        hintEn = ManualHintEn
      )
    else
      FreezeDisplay(
        kind = FreezeKind.AutoRecovering,
        reasonWire = reasonWire,
        needsHuman = false,
        autoRecoverable = true,
        labelZh = labelZh,
        labelEn = labelEn,
        hintZh = "",
        hintEn = ""
      )

  /**
   * 冻结原因 + 退避时刻 + 升级链 → 呈现判定（纯函数，单测判红锚 = `FreezeDisplaySpec`）。
   *
   * 覆盖规则（按优先级）：
   *   ① `escalation.escalateAt <= 0` = 已升级到用户终态（「用户是最终仲裁」）⇒ 需人，
   *      与错误族自动性无关：引擎已经停下等人。
   *   ② 错误族 `resumeAt` 超过 `AutoClaimHorizonMs` ⇒ 需人（不会自己好）。
   *   ③ 其余按 reason 表（下表即「状态判定表」的执行面）。
   */
  def of(
    reason: FreezeReason,
    resumeAt: Option[Long] = None,
    escalation: Option[EscalationInfo] = None,
    now: Long = System.currentTimeMillis()
  ): FreezeDisplay =
    val farFuture = resumeAt.exists(t => t - now > AutoClaimHorizonMs)
    val userArbiter = escalation.exists(_.escalateAt <= 0)
    val base = reason match
      case FreezeReason.Schedule =>
        FreezeDisplay(
          FreezeKind.WaitingWindow,
          "schedule",
          needsHuman = false,
          autoRecoverable = true,
          "等待工作时段",
          "Waiting for the work window",
          "",
          ""
        )
      case FreezeReason.LlmTransient =>
        auto("llm-transient", "自动重试中（模型层故障）", "Auto-retrying (LLM-layer failure)", farFuture)
      case FreezeReason.Network =>
        auto("network", "自动重试中（网络抖动）", "Auto-retrying (network blip)", farFuture)
      case FreezeReason.ProviderDown =>
        auto("provider-down", "自动重试中（供应商不可用）", "Auto-retrying (all providers down)", farFuture)
      case FreezeReason.RestartRecovery =>
        auto("restart-recovery", "断点续跑中", "Resuming from the checkpoint", farFuture)
      case FreezeReason.WaitingNetwork =>
        auto("waiting-network", "等待网络/供应商恢复", "Waiting for the network/provider to come back", farFuture)
      case FreezeReason.Loop =>
        FreezeDisplay(
          FreezeKind.NeedsHuman,
          "loop",
          needsHuman = true,
          autoRecoverable = false,
          "已暂停 · 循环检测（需你决定）",
          "Paused — loop guard tripped (needs your decision)",
          ManualHintZh,
          ManualHintEn
        )
    if userArbiter && base.kind != FreezeKind.NeedsHuman then
      base.copy(
        kind = FreezeKind.NeedsHuman,
        needsHuman = true,
        autoRecoverable = false,
        labelZh = s"${base.labelZh}（已升级到人，需你决定）",
        labelEn = s"${base.labelEn} (escalated to a human — needs your decision)",
        hintZh = ManualHintZh,
        hintEn = ManualHintEn
      )
    else base

  end of

  /**
   * 线值（`AgentRecord.frozenReason` / `DelegateRegistry.Record.frozenReason`）→ 呈现
   * 判定。快照/工具面只有线值、没有 `FreezeReason` 对象，是这条腿的唯一入口。
   * 未知/空线值**不承诺自动恢复**（冻结着却记不下原因 ⇒ 只能人去确认）。
   */
  def ofWire(wire: String, resumeAt: Option[Long] = None, now: Long = System.currentTimeMillis()): FreezeDisplay =
    val w = Option(wire).getOrElse("").trim
    if w.startsWith("delegate-frozen") then
      val code = w.stripPrefix("delegate-frozen").stripPrefix(":")
      val label = DelegateReasonLabels.getOrElse(code, if code.isEmpty then "原因未记录" else code)
      // provider 故障族（2026-11-05 10:36 作者令）：provider 故障 = **基础设施故障**，
      // 不是任务失败 ⇒ 不向上汇报、面板标「待你处理」、但引擎自己探测恢复后续跑。
      // 因此这一族 `autoRecoverable=true`（`kind` 保持 NeedsHuman 只为面板措辞与
      // 「唤醒」入口在场；耗 supervisor 重启预算的 RestartAgent 腿对它一律不提供——
      // 唤醒走带 clientMessageId 的 UserInput 幂等腿）。
      if isInfraReasonCode(code) then
        FreezeDisplay(
          FreezeKind.NeedsHuman,
          w,
          needsHuman = true,
          autoRecoverable = true,
          s"已冻结 · $label（待你处理 · 供应商恢复后自动续跑）",
          s"Frozen — $label (waiting for you · auto-resumes when a provider is back)",
          InfraHintZh,
          InfraHintEn
        )
      else
        FreezeDisplay(
          FreezeKind.NeedsHuman,
          w,
          needsHuman = true,
          autoRecoverable = false,
          s"已冻结 · $label（需唤醒）",
          s"Frozen — $label (needs a wake)",
          ManualHintZh,
          ManualHintEn
        )
    else
      w match
        case "schedule" => of(FreezeReason.Schedule, resumeAt, None, now)
        case "llm-transient" => of(FreezeReason.LlmTransient, resumeAt, None, now)
        case "network" => of(FreezeReason.Network, resumeAt, None, now)
        case "provider-down" => of(FreezeReason.ProviderDown, resumeAt, None, now)
        case "restart-recovery" => of(FreezeReason.RestartRecovery, resumeAt, None, now)
        case "waiting-network" => of(FreezeReason.WaitingNetwork, resumeAt, None, now)
        case "loop" => of(FreezeReason.Loop, resumeAt, None, now)
        case other =>
          FreezeDisplay(
            FreezeKind.NeedsHuman,
            if other.isEmpty then "unknown" else other,
            needsHuman = true,
            autoRecoverable = false,
            "已冻结 · 原因未记录（需人工确认）",
            "Frozen — reason not recorded (needs a human check)",
            ManualHintZh,
            ManualHintEn
          )

    end if

  end ofWire

  /** 该线值是否需要人给动作（delegateWake 等恢复入口的判据单点）。 */
  def needsHumanWire(wire: String, resumeAt: Option[Long] = None): Boolean =
    ofWire(wire, resumeAt).needsHuman

  /**
   * 状态迁移日志的 detail 正文（**格式单点**，单测判红锚）：一行说清「谁在什么条件
   * 下把会话置成了什么」，例如本次报障那条：
   * `running->frozen kind=needs-human needsHuman=true autoRecoverable=false reason=loop
   *  by=loop-guard resumeAt=in31536000000ms`
   * 由 `AgentFrozen.logFreezeTransition` 逐字写出（event=freeze-state），恢复腿同款
   * （`frozen->running … by=user-wake`）。
   */
  def transitionDetail(
    from: String,
    to: String,
    fd: FreezeDisplay,
    by: String,
    resumeAt: Option[Long],
    now: Long
  ): String =
    val eta = resumeAt.map(t => s"in${math.max(0L, t - now)}ms").getOrElse("-")
    s"$from->$to kind=${fd.kind.wire} needsHuman=${fd.needsHuman} autoRecoverable=${fd.autoRecoverable} " +
      s"reason=${fd.reasonWire} by=$by resumeAt=$eta"

end FreezeDisplay

/**
 * 升级链状态（v2 §5.2）：同 reason 连续冻结 ≥3 次进入——挂 AgentRecord（P0 内存态）。
 * level=当前升级层级（1 起）；escalateAt=本层等待父决策的截止时间；awaitedParentSessionId=等待的父会话。
 */
case class EscalationInfo(
  level: Int,
  escalateAt: Long,
  awaitedParentSessionId: String
)

/**
 * v2 升级链判定（§5.1 规则，纯函数——可单测）：升级只沿 parentSessionId 静态链
 * 向上、每级一次（通知按 level 去重）、无环（单父树）；父记录不存在 → 跳级；
 * 最终到达用户（root/无父）即终态，无再升级对象。
 */
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

  /**
   * 工具执行期心跳（审计 20260903 子项①）：toolStart→toolEnd 之间每
   * Defaults.ToolHeartbeatSec 秒发一条，喂活前端 busy timer——前台长工具
   * 执行零事件段不再触发前端 630s 纯静默超时误杀。
   */
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
    outputTokens: Option[Int] = None,
    /**
     * The turn's final assistant text (blocking-subagent bridge batch
     * 2026-10-03): the producer for the `finalText` frame field that
     * BlockingSubagent's wsSend spy reads. Emitted only by the real
     * turn-finish path (AgentFinishTurn.returnToIdle); error/compaction Done
     * emitters leave it None so the bridge correctly reports "ended without a
     * final text". None = the JSON key is OMITTED — every pre-existing payload
     * stays byte-identical (same merge style as model / outputTokens).
     */
    finalText: Option[String] = None
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
  // 2026-09-15 作者令：压缩不再落 report ⇒ `reportPath` 字段随生成链删除
  // （此前由 AgentActor 从 archive.reportPath 填入，UI 据此显示「report: xxx.md」）。
  // 刀二（2026-10-28 无感压缩批·彻底零痕迹）：完成卡停渲染，唯一可见例外 =
  // 0.95 硬守卫 emergencyClean 真清上下文（安全阀）——`emergency` 区分发射点
  // （runEmergencyCompact = true，常规压缩轮 = false 缺省）；缺省 false ⇒ 既有
  // 调用点/载荷零漂移。
  case CompactComplete(before: Int, after: Int, emergency: Boolean = false)
  case CompactFailed(reason: String, attempt: Int, maxAttempts: Int)
  case BackgroundTaskUpdate(taskId: String, description: String, status: String)
  case ExternalEventReceived(source: String, eventType: String, correlationId: Option[String])
  case Interrupted

  /**
   * 冻结调度：agent 在 dispatch 边界被冻结（处于冻结时段内，#337 黑名单语义）。resumeAtMillis 供前端展示。
   * v2（冻结式错误恢复）：reason 泛化——Schedule=时间表冻结（默认，缺省兼容旧前端）；
   * LlmTransient/Network/ProviderDown/RestartRecovery=错误恢复族（§3.1）；detail/retryCount/
   * escalation 为错误族附加信息（可选，缺省无）。
   */
  case Frozen(
    resumeAtMillis: Option[Long],
    reason: FreezeReason = FreezeReason.Schedule,
    detail: Option[String] = None,
    retryCount: Int = 0,
    escalation: Option[EscalationInfo] = None
  )

  /**
   * 冻结调度：恢复（出冻结段自动恢复 / 用户输入唤醒 / 交互豁免路径不会发出本事件）。
   * nextChangeAt = 恢复时刻的下一翻转点（工作态 = 下一冻结开始时刻，供前端
   * 展示「下一段 HH:mm 再冻结」；None = 无未来翻转点，如配置关闭）。
   */
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
      case Done(model, contextWindow, inputTokens, compactThreshold, outputTokens, finalText) =>
        val base =
          if isSubagent then Json.obj("type" -> "agentDone".asJson, "agentId" -> agentId.asJson)
          else Json.obj("type" -> "done".asJson, "sessionId" -> sessionId.asJson)
        val withModel = model.fold(base)(m => base.deepMerge(Json.obj("model" -> m.asJson)))
        val withCw = contextWindow.fold(withModel)(cw => withModel.deepMerge(Json.obj("contextWindow" -> cw.asJson)))
        val withIt = inputTokens.fold(withCw)(it => withCw.deepMerge(Json.obj("inputTokens" -> it.asJson)))
        val withOt = outputTokens.fold(withIt)(ot => withIt.deepMerge(Json.obj("outputTokens" -> ot.asJson)))
        val withCt = compactThreshold.fold(withOt)(ct => withOt.deepMerge(Json.obj("compactThreshold" -> ct.asJson)))
        // finalText merged last, same omission style — absent when None so old
        // payloads stay byte-stable. This is the sole upstream producer of the
        // field BlockingSubagent's spy reads to complete the blocking bridge.
        finalText.fold(withCt)(ft => withCt.deepMerge(Json.obj("finalText" -> ft.asJson)))
      case UsageUpdate(inputTokens, contextWindow, compactThreshold, outputTokens, model) =>
        // #308: model (actual model of this round) is merged last, same style as
        // Done's withModel — absent when None so old payloads stay byte-stable.
        if isSubagent then
          Json
            .obj(
              "type" -> "usageUpdate".asJson,
              "sessionId" -> sessionId.asJson,
              "nodeSessionId" -> sessionId.asJson,
              "inputTokens" -> inputTokens.asJson,
              "contextWindow" -> contextWindow.asJson,
              "compactThreshold" -> compactThreshold.asJson
            )
            .deepMerge(outputTokens.fold(Json.obj())(ot => Json.obj("outputTokens" -> ot.asJson)))
            .deepMerge(model.fold(Json.obj())(m => Json.obj("model" -> m.asJson)))
        else
          Json
            .obj(
              "type" -> "usageUpdate".asJson,
              "sessionId" -> sessionId.asJson,
              "inputTokens" -> inputTokens.asJson,
              "contextWindow" -> contextWindow.asJson,
              "compactThreshold" -> compactThreshold.asJson
            )
            .deepMerge(outputTokens.fold(Json.obj())(ot => Json.obj("outputTokens" -> ot.asJson)))
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
      case CompactComplete(before, after, emergency) =>
        if isSubagent then
          Json.obj(
            "type" -> "agentCompactComplete".asJson,
            "agentId" -> agentId.asJson,
            "before" -> before.asJson,
            "after" -> after.asJson,
            "emergency" -> emergency.asJson
          )
        else
          Json.obj(
            "type" -> "compactComplete".asJson,
            "sessionId" -> sessionId.asJson,
            "before" -> before.asJson,
            "after" -> after.asJson,
            "emergency" -> emergency.asJson
          )
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
          case FreezeReason.Schedule => "schedule"
          case FreezeReason.LlmTransient => "llm-transient"
          case FreezeReason.Network => "network"
          case FreezeReason.ProviderDown => "provider-down"
          case FreezeReason.RestartRecovery => "restart-recovery"
          case FreezeReason.Loop => "loop"
          case FreezeReason.WaitingNetwork => "waiting-network" // #692 退避等待批
        val withReason = withResume.deepMerge(Json.obj("reason" -> reasonStr.asJson))
        val withDetail = detail.fold(withReason)(d => withReason.deepMerge(Json.obj("detail" -> d.asJson)))
        val withRetry =
          if retryCount > 0 then withDetail.deepMerge(Json.obj("retryCount" -> retryCount.asJson)) else withDetail
        val withEscalation = escalation.fold(withRetry)(e =>
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
        // 冻结态诚实呈现批（2026-11-05）：呈现分类随帧出——控制台据此决定「显示
        // 自动重试中（无按钮）」还是「需人工（给恢复按钮）」，不再从 reason 猜。
        // 三个字段并列：freezeKind（线值）/ needsHuman / autoRecoverable；判定单点 =
        // FreezeDisplay.of（同批件），此处只投影。
        val fd = FreezeDisplay.of(reason, resumeAtMillis, escalation)
        withEscalation.deepMerge(
          Json.obj(
            "freezeKind" -> fd.kind.wire.asJson,
            "needsHuman" -> fd.needsHuman.asJson,
            "autoRecoverable" -> fd.autoRecoverable.asJson
          )
        )
      case Resumed(nextChangeAt) =>
        val base =
          if isSubagent then Json.obj("type" -> "agentResumed".asJson, "agentId" -> agentId.asJson)
          else Json.obj("type" -> "resumed".asJson, "sessionId" -> sessionId.asJson)
        // 解冻显式布尔 + 下一翻转点（None 省略，旧载荷 byte-stable）
        val withFrozen = base.deepMerge(Json.obj("frozen" -> false.asJson))
        nextChangeAt.fold(withFrozen)(t => withFrozen.deepMerge(Json.obj("nextChangeAt" -> t.asJson))))
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
  /**
   * Flow-node supervision P2 (2026-08-26): agent-turn-level retryability of
   * the failure (single source AgentActor.llmFailureRetryable). None =
   * legacy/unset (treated as not retryable by consumers). Lets the flow
   * executor distinguish "LLM stall, checkpoint-restart may heal it" from
   * hard failures without string-matching error messages.
   */
  retryable: Option[Boolean] = None
)
