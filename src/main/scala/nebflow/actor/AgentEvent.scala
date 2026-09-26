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
  final case class McpPermissionReply(deferred: cats.effect.Deferred[IO, nebflow.core.McpPermissionAnswer])
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
  // 2026-09-15 作者令：压缩不再落 report ⇒ `reportPath` 字段随生成链删除
  // （此前由 AgentActor 从 archive.reportPath 填入，UI 据此显示「report: xxx.md」）。
  case CompactComplete(before: Int, after: Int)
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
      case CompactComplete(before, after) =>
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
        val withReason = withResume.deepMerge(Json.obj("reason" -> reasonStr.asJson))
        val withDetail = detail.fold(withReason)(d => withReason.deepMerge(Json.obj("detail" -> d.asJson)))
        val withRetry =
          if retryCount > 0 then withDetail.deepMerge(Json.obj("retryCount" -> retryCount.asJson)) else withDetail
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
