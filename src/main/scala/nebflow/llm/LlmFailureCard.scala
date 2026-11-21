package nebflow.llm

import java.util.concurrent.ConcurrentHashMap

import io.circe.Json
import io.circe.syntax.*

/**
 * LlmFailureCard —— LLM 失败卡片的**非阻塞**运行时（卡片批 2026-11-21）。
 *
 * 与 [[nebflow.core.model.ModelUnavailableCard]]（阻塞式 raiseAndAwait，等用户答复才
 * 放行）不同：本运行时**发帧即返回**，调用方（引擎重试 / 冻结）不等待用户。
 *   - 失败（LLM 类，即 `AgentError.llmClass == true`）⇒ `llmFailure` 帧（卡片出现：错误摘要 +
 *     「等待恢复」+「换模型」）；同一 agent 的失败态登记进内存表。
 *   - 恢复（同一 agent 的下一次 LLM 调用成功）⇒ `llmFailureRecovered` 帧（卡片自动收起），
 *     表项移除。仅当表内确有该 agent 的失败态时才发，避免每次成功调用都刷帧。
 *
 * 纯状态 + 帧构造，无 IO：WS 发送点（AgentProcessing）负责把帧送出去。
 * 表以 agent 名为键（同一 agent 同一时刻至多一张卡，多次失败覆盖为最新摘要）。
 */
object LlmFailureCard:

  private val active = new ConcurrentHashMap[String, Json]()

  def failureFrame(sessionId: String, agentName: String, summary: String, failureClass: String): Json =
    Json.obj(
      "type" -> "llmFailure".asJson,
      "sessionId" -> sessionId.asJson,
      "agentName" -> agentName.asJson,
      "summary" -> summary.asJson,
      "failureClass" -> failureClass.asJson,
      "waitable" -> true.asJson,
      "switchable" -> true.asJson
    )

  def recoveredFrame(sessionId: String, agentName: String): Json =
    Json.obj(
      "type" -> "llmFailureRecovered".asJson,
      "sessionId" -> sessionId.asJson,
      "agentName" -> agentName.asJson
    )

  /** 登记一次 LLM 类失败并返回应发出的 `llmFailure` 帧。 */
  def recordFailure(sessionId: String, agentName: String, summary: String, failureClass: String): Json =
    val frame = failureFrame(sessionId, agentName, summary, failureClass)
    active.put(agentName, frame)
    frame

  /**
   * 同一 agent 成功调用后调用：若表内有其失败态 ⇒ 移除并返回 `llmFailureRecovered` 帧；
   * 否则 None（不刷帧）。
   */
  def recordSuccess(sessionId: String, agentName: String): Option[Json] =
    Option(active.remove(agentName)).map(_ => recoveredFrame(sessionId, agentName))

  /** 诊断用：当前处于失败态的 agent 名。 */
  def activeAgents: Set[String] =
    import scala.jdk.CollectionConverters.*
    active.keySet().asScala.toSet

end LlmFailureCard
