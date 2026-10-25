package nebflow.core.delegate

import cats.effect.IO
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.actor.AgentCommand
import nebflow.shared.NebflowLogger

import java.util.concurrent.atomic.AtomicReference

/**
 * MergeRefusalCare —— 引擎原生自动合并**拒绝通知面**（合并静默搁浅修复批
 * 2026-10-23 作者令三腿之腿①；**同日作者设计裁定纠正：合并不自动化——delegate
 * 自己负责合并，平台只把「排队 + 通知」做自动化**，原「树转洁自动重跑合并」腿
 * 撤销，本面是纯通知面，零代跑语义）。
 *
 * 根因（2026-10-23 排查，三例实锤 #775 / c2b7661f / a65aba03）：DelegateAutoMerge
 * 遇基工作区脏等 fail-closed 拒并后，拒绝只落在终态尾注 + ChainStore 审计行里
 * ——无人被通知，工作就地搁浅：面板上就是「空等，没有人去通知」。本面把拒绝
 * 变成两条持久注入通知：
 *   - **主通知 → delegate 本人**（rec.id 会话）：持久注入队 append（correlationId
     * 幂等，复活/续聊轮 RecoverPersistedQueues 重放），带**可行动诊断**——哪条
     * 路径脏、什么原因、怎么清、重试是 delegate 自己的责任（清障后自行重试合
     * 并；平台不代跑）；
 *   - **知会一行 → 父会话**（rec.parentSessionId）：一行 FYI（重试责任在
     * delegate，父会话无需行动），root 行在时的升格路由同 MergeWindowCare 族。
 *   - 投递路由 = [[nebflow.core.tools.BgNotifyDelivery.deliverToRootOrInbox]]
 *     同族（目标活 = 直投；死 = 持久收件箱补投）；correlationId 按 reason 摘要
 *     幂等——同脏重评不重复轰炸持久队列，脏面变化（reason 变）= 新事件照发。
 *
 * 分层纪律：core 不 import agent —— 装配走注册器端口（BgInboxPort /
 * DelegateJudgePort 同款 M2 形态）；生产装配点 = SharedResources 构造行。
 * 未装配（无 SharedResources 的测试 harness）= 响亮 WARN，通知绝不静默蒸发。
 */
object MergeRefusalCare:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.merge-refusal-care")

  /** 一条拒绝通知（DelegateAutoMerge.postSettle 构造；字段 = 可行动信息面）。 */
  final case class RefusalNotice(
      delegateId: String,
      parentSessionId: String,
      title: String,
      branch: String,
      target: String,
      baseWorkspace: String,
      reason: String,
      dirtyPaths: List[String]
  )

  /** delegate 主通知 correlationId 前缀（幂等键族；spec 判红锚）。 */
  val CorrelationPrefix: String = "auto-merge-refused"

  /** 父会话知会 correlationId 前缀（独立键族——父侧幂等不与 delegate 侧互扰）。 */
  val ParentCorrelationPrefix: String = "auto-merge-refused-parent"

  /**
   * delegate 主通知（可行动诊断全文）。correlationId 摘 reason（拒绝原因含脏路
   * 径清单）——同脏重评同键被注入队列幂等挡下；脏面变化 = 新键照发。
   * eventType=failed：与既有 failed 事件族同型（事件提醒机器统一处理）。
   */
  def event(n: RefusalNotice): AgentCommand.ExternalEvent =
    val cid = s"$CorrelationPrefix-${n.delegateId}-${Integer.toHexString(n.reason.hashCode)}"
    val dirtyList =
      if n.dirtyPaths.isEmpty then "<unreadable>"
      else n.dirtyPaths.mkString(", ")
    AgentCommand.ExternalEvent(
      source = "auto-merge",
      eventType = "failed",
      payload =
        s"""[auto-merge] your branch '$n.branch' was NOT merged into '${n.target}' — the engine's auto-merge refused (fail-closed, nothing staged).
           |Reason: ${n.reason}
           |Dirty paths: $dirtyList
           |Your move (the merge is YOUR responsibility — the platform does not run merges for you): commit or clean the dirty paths above in the base workspace ('${n.baseWorkspace}'; the platform's shared-area hygiene auto-restores tracked overwrites and auto-absorbs untracked generated artifacts under evidence/ and reports/ into the merge batch — tracked source modifications are never touched and stay fail-closed), then re-attempt the merge yourself on your next turn.
           |(Persistent injection — correlationId $cid; audit row: ChainStore '${DelegateAutoMerge.EntryKind}' action=refused.)""".stripMargin,
      metadata = JsonObject(
        "delegateId" -> n.delegateId.asJson,
        "description" -> n.title.asJson,
        "branch" -> n.branch.asJson,
        "target" -> n.target.asJson,
        "baseWorkspace" -> n.baseWorkspace.asJson,
        "dirtyPaths" -> n.dirtyPaths.asJson,
        "notifyTarget" -> "delegate".asJson,
        "escalatedBy" -> "auto-merge".asJson
      ),
      correlationId = Some(cid)
    )
  end event

  /** 父会话知会（一行 FYI——责任与行动都在 delegate 侧）。 */
  def parentEvent(n: RefusalNotice): AgentCommand.ExternalEvent =
    val cid = s"$ParentCorrelationPrefix-${n.delegateId}-${Integer.toHexString(n.reason.hashCode)}"
    AgentCommand.ExternalEvent(
      source = "auto-merge",
      eventType = "failed",
      payload =
        s"""[auto-merge] FYI: delegate "${n.title}" (${n.delegateId}) — its branch '${n.branch}' was NOT merged into '${n.target}' (${n.reason.trim}). The delegate itself has been notified to clear the obstacle and retry the merge on its own; no action needed from you unless it stays stuck.""".stripMargin,
      metadata = JsonObject(
        "delegateId" -> n.delegateId.asJson,
        "description" -> n.title.asJson,
        "branch" -> n.branch.asJson,
        "target" -> n.target.asJson,
        "notifyTarget" -> "parent".asJson,
        "escalatedBy" -> "auto-merge".asJson
      ),
      correlationId = Some(cid)
    )
  end parentEvent

  // ── 注册器端口（M2 形态：core 只见面，agent 侧装配实现） ────────────────

  /** [[nebflow.core.tools.BgInboxPort]] 同款注册器面（MergeRefusalCare.EscalationPort）。 */
  object EscalationPort:

    /**
     * 双目标投递（delegate 主通知 + 父会话知会一行）。实现面负责活性分腿
     * （活 = 直投 / 死 = 持久收件箱）与目标解析。
     */
    trait Face:
      def deliver(delegateId: String, parentSessionId: String, delegateEvent: AgentCommand.ExternalEvent, parentEvent: AgentCommand.ExternalEvent): IO[Unit]
    end Face

    private val face = new AtomicReference[Option[Face]](None)

    def install(f: Face): Unit = face.set(Some(f))

    def clear(): Unit = face.set(None)

    def installed: Boolean = face.get.isDefined

    /**
     * 通知投递（DelegateAutoMerge 的 refused 单点调用）。未装配 = 响亮 WARN
     * （不静默——本批要消灭的正是「无人通知」形态；生产 boot 必装）。
     */
    def deliver(
      delegateId: String,
      parentSessionId: String,
      delegateEvent: AgentCommand.ExternalEvent,
      parentEvent: AgentCommand.ExternalEvent
    ): IO[Unit] =
      face.get match
        case Some(f) =>
          f.deliver(delegateId, parentSessionId, delegateEvent, parentEvent).handleErrorWith(e =>
            logger.warn(s"[merge-refusal-care] notification delivery failed for $delegateId: ${e.getMessage}")
          )
        case None =>
          logger
            .warn(
              s"[merge-refusal-care] EscalationPort not installed — refusal notification for delegate '$delegateId' " +
                "dropped with this line (production boot installs this at SharedResources construction)"
            )
            .void
    end deliver
  end EscalationPort

  /** DelegateAutoMerge.postSettle 的单点入口（构造双事件 + 投递；fail-soft）。 */
  def escalate(n: RefusalNotice): IO[Unit] =
    EscalationPort.deliver(n.delegateId, n.parentSessionId, event(n), parentEvent(n))

end MergeRefusalCare
