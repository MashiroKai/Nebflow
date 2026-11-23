package nebflow.llm

import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.{FallbackExhaustedError, ModelScheduleRestrictedError}

/**
 * LlmFailureCard —— LLM 失败卡片的**非阻塞**运行时（卡片批 2026-11-21；聚合键改造 2026-11-21 第 3 轮）。
 *
 * 与 [[nebflow.core.model.ModelUnavailableCard]]（阻塞式 raiseAndAwait，等用户答复才
 * 放行）不同：本运行时**发帧即返回**，调用方（引擎重试 / 冻结）不等待用户。
 *
 * 聚合键 = **层级 + 模型**（作者第 3 轮裁定）：失败是模型层 / 层级级事件，不是代理级事件。
 * 同层级、同模型的多个代理失败只出**一张**卡，卡上列出受影响的代理。
 *   - 层级 `layer`：`root`（深度 0 的主代理）/ `team`（团队成员，无 replyTo、由父发消息驱动）/
 *     `subtask`（委派子任务）。
 *   - 模型 `modelRef`：链上首个候选（`providerId/model`）；无链信息时取代理自身的首选模型。
 *   - 失败 ⇒ `llmFailure` 帧（整组快照：层级 / 模型 / 受影响代理列表 / 最新摘要）。
 *   - 恢复：组内某代理下一次成功调用 ⇒ 从组内移除该代理；组仍有成员 ⇒ 再发一帧 `llmFailure`
 *     收窄代理列表；组清空 ⇒ `llmFailureRecovered` 帧收卡。
 *
 * 纯状态 + 帧构造，无 IO：WS 发送点（AgentProcessing）负责把帧送出去。
 */
object LlmFailureCard:

  /** 组内单个失败代理。 */
  final case class Member(agent: String, summary: String, failureClass: String, windowDesc: String = "")

  private final case class Group(layer: String, modelRef: String, members: Vector[Member])

  private val lock = new Object
  // 键 = (layer, modelRef)
  private var groups: Map[(String, String), Group] = Map.empty

  /** 层级判定（单点）：深度 0 = 根；无 replyTo 且有父 = 团队成员；其余 = 子任务。 */
  def layerOf(depth: Int, teamMember: Boolean): String =
    if depth <= 0 then "root" else if teamMember then "team" else "subtask"

  /** 模型判定（单点）：链穷尽时取链上首个候选；时段窗受限取受限清单首个；
    * 否则取代理首选；都没有给空串。 */
  def modelRefOf(error: Throwable, preferred: Option[String]): String = error match
    case f: FallbackExhaustedError =>
      f.attempts.headOption.map(a => s"${a.providerId}/${a.model}").orElse(preferred).getOrElse("")
    case s: ModelScheduleRestrictedError => s.restricted.headOption.getOrElse(preferred.getOrElse(""))
    case _ => preferred.getOrElse("")

  /** 时段窗受限卡的失败分类线值（前端 FAILURE_CLASS 同键；唯一真源在此）。 */
  val ScheduleWindowClass: String = "llm-schedule-window"

  /**
   * 失败分类线值（单点）：时段窗全受限 ⇒ [[ScheduleWindowClass]]（窗口受限·等待恢复型
    * ——前端据此渲染专用卡态：等窗口、不换模型）；其余 fallback 到调用方传入的分类。
    */
  def failureClassOf(error: Throwable, fallback: String): String = error match
    case _: ModelScheduleRestrictedError => ScheduleWindowClass
    case _ => fallback

  /** 时段窗受限的窗口描述串（帧 `windowDesc` 字段；非时段窗错误 = None）。 */
  def windowDescOf(error: Throwable): Option[String] = error match
    case s: ModelScheduleRestrictedError => Some(s.windowsDesc)
    case _ => None

  private def groupFrame(sessionId: String, g: Group): Json =
    val last = g.members.last
    Json.obj(
      "type" -> "llmFailure".asJson,
      "sessionId" -> sessionId.asJson,
      "layer" -> g.layer.asJson,
      "modelRef" -> g.modelRef.asJson,
      "agents" -> g.members.map(_.agent).asJson,
      "summary" -> last.summary.asJson,
      "failureClass" -> last.failureClass.asJson,
      "windowDesc" -> last.windowDesc.asJson,
      "waitable" -> (last.failureClass != ScheduleWindowClass).asJson,
      "switchable" -> (last.failureClass != ScheduleWindowClass).asJson
    )

  /** 单代理视角的失败帧（组快照；便于单测直接断言）。 */
  def failureFrame(sessionId: String, agentName: String, summary: String, failureClass: String,
                   layer: String = "root", modelRef: String = "", windowDesc: String = ""): Json =
    groupFrame(sessionId, Group(layer, modelRef, Vector(Member(agentName, summary, failureClass, windowDesc))))

  private def recoveredFrame(sessionId: String, layer: String, modelRef: String, agentName: String): Json =
    Json.obj(
      "type" -> "llmFailureRecovered".asJson,
      "sessionId" -> sessionId.asJson,
      "layer" -> layer.asJson,
      "modelRef" -> modelRef.asJson,
      "agentName" -> agentName.asJson
    )

  /** 登记一次 LLM 类失败并返回应发出的 `llmFailure` 组快照帧。 */
  def recordFailure(sessionId: String, agentName: String, summary: String, failureClass: String,
                    layer: String = "root", modelRef: String = "", windowDesc: String = ""): Json =
    lock.synchronized {
      val key = (layer, modelRef)
      val existing = groups.get(key).map(_.members.filterNot(_.agent == agentName)).getOrElse(Vector.empty)
      val g = Group(layer, modelRef, existing :+ Member(agentName, summary, failureClass, windowDesc))
      groups = groups.updated(key, g)
      groupFrame(sessionId, g)
    }

  /**
   * 同一代理成功调用后调用：若其在某组内 ⇒ 移出该组；组清空则返回 `llmFailureRecovered`
   * 收卡，组仍有成员则返回收窄后的 `llmFailure` 快照；不在任何组内 ⇒ None（不刷帧）。
   */
  def recordSuccess(sessionId: String, agentName: String): Option[Json] =
    lock.synchronized {
      groups.collectFirst { case (k, g) if g.members.exists(_.agent == agentName) => (k, g) }.map { case (k, g) =>
        val remaining = g.members.filterNot(_.agent == agentName)
        if remaining.isEmpty then
          groups = groups - k
          recoveredFrame(sessionId, g.layer, g.modelRef, agentName)
        else
          val ng = g.copy(members = remaining)
          groups = groups.updated(k, ng)
          groupFrame(sessionId, ng)
      }
    }

  /** 诊断用：当前处于失败态的代理名。 */
  def activeAgents: Set[String] =
    lock.synchronized(groups.values.flatMap(_.members.map(_.agent)).toSet)

end LlmFailureCard
