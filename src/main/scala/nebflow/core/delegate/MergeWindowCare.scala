package nebflow.core.delegate

import cats.effect.IO
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.actor.AgentCommand
import nebflow.core.AgentRuntimePort
import nebflow.core.tools.BgNotifyDelivery
import nebflow.shared.NebflowLogger

/**
 * MergeWindowCare —— 合并窗的**台账状态事件化看护**（steward 批 D3-⑤，2026-10-23）。
 *
 * DelegateBudget wall-clock 预算退役（2026-10-09 作者裁定）后，delegate 治理的活性
 * 替代只建了半截：TaskStuckWatcher 无进展判据管**工作相**（Processing），停靠相
 * （waiting-merge / 合并窗）没有看护——合并轮的推进者=监督者终态回调，监督者
 * actor 若已死，轮永远等不到终态：单飞行窗被尸体持死，整队陪站（队头堵塞 ③ 的
 * 死亡形态）。本件补齐另一半：**判据 = 台账状态 × actor 活性信号（事件判据，零
 * 时间判据）**——
 *   - 合并轮在飞（reminderSentAt 有值）∧ 内部腿 ∧ 监督者 actor 已死 ⇒ 轮驱动：
 *     条目按「被拒不堵塞」语义留队待重试（`onTerminal(keepForRetry=true)`），窗
 * 释放轮转下一条，台账行 + 升级事件（root 活投 / 持久收件箱补投）。
 *   - 提醒未发（pre-injection）∧ delegate 会话已不在注册表（终态清理竞态孤儿）
 *     ⇒ 出队（该 delegate 永远不会再轮到）。
 *   - 通道腿（external / recovered）自管（CLI resume / boot sweep 自有 settle 面），
 *     不在本件覆盖面。
 *
 * 消费点 = `TaskStuckWatcher.scan` 尾部（搭既有扫描拍，零新增定时器；幂等——
 * 动作过的条目翻 `pendingRetry`/出队，重复扫描零重复动作）。
 */
object MergeWindowCare:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.merge-window-care")

  /** 单条看护结论（审计 / spec 断言面）。 */
  final case class Finding(delegateId: String, action: String, detail: String)

  /** 本拍看护动作词表。 */
  val ActionRoundDriverDead: String = "round-driver-dead"
  val ActionOrphanDequeue: String = "orphan-dequeue"

  /** 一拍看护。返回本拍的动作清单（无动作 = Nil）。 */
  def care(resources: AgentRuntimePort): IO[List[Finding]] =
    val entries = MergeQueue.entriesSnapshot
    if entries.isEmpty then IO.pure(Nil)
    else
      entries
        .traverse { entry =>
          // 通道腿自管（external/recovered 的注入走闭包通道，settle 面自有终态）。
          if entry.external.isDefined || entry.recovered.isDefined then IO.none
          else
            resources.actorSystem.isAlive(entry.supervisorRef.path).flatMap {
              case true => IO.none // 司机活着：合并轮在正常等待终态回调，零动作
              case false if entry.reminderSentAt.isDefined && !entry.pendingRetry =>
                // 合并轮司机已死（死亡事件，非超时）：轮永远等不到终态——按「被拒
                // 不堵塞」语义跳过本条（留队待重试），窗释放轮转下一条，升级上报。
                val detail =
                  s"merge round in flight but the supervisor driver of '${entry.delegateId}' is dead — " +
                    "entry kept pending retry, single-flight window released"
                logger.warn(s"[merge-window-care] $detail") *>
                  IO.blocking(MergeQueue.onTerminal(entry.delegateId, keepForRetry = true))
                    .handleErrorWith(e => logger.warn(s"[merge-window-care] queue advance failed: ${e.getMessage}")) *>
                  escalate(
                    resources,
                    entry.delegateId,
                    entry.parentSessionId,
                    entry.description,
                    ActionRoundDriverDead,
                    detail
                  )
                    .as(Some(Finding(entry.delegateId, ActionRoundDriverDead, detail)))
              case false if entry.reminderSentAt.isEmpty =>
                // 提醒未发的停靠条目，delegate 会话已不在注册表 = 该条永远不会再被
                // 注入（终态清理竞态孤儿）——出队收口（幂等）。
                resources.agentRegistry.get.map(_.contains(entry.delegateId)).flatMap {
                  case true => IO.none // 会话在册（等轮转的正常排队者），零动作
                  case false =>
                    val detail =
                      s"pre-injection queue entry for '${entry.delegateId}' has no live session (terminal cleanup race) — dequeuing the orphan"
                    logger.warn(s"[merge-window-care] $detail") *>
                      IO.blocking(MergeQueue.onTerminal(entry.delegateId))
                        .handleErrorWith(e =>
                          logger.warn(s"[merge-window-care] orphan dequeue failed: ${e.getMessage}")
                        )
                        .as(Some(Finding(entry.delegateId, ActionOrphanDequeue, detail)))
                }
              case false => IO.none // 待重试条目（已动作过）：幂等跳过
            }
        }
        .map(_.flatten)
    end if
  end care

  /**
   * 升级上报（D3-① 落点）：明确死亡事件且无人认领 ⇒ 投最终责任方——delegate
   * registry 行在 ⇒ 行上的 rootSessionId（顶层根，不猜）；无行 ⇒ 回落记录面父
   * 会话。root 活 = 直投；不活 = 持久收件箱（复活补投）。
   */
  private def escalate(
    resources: AgentRuntimePort,
    delegateId: String,
    parentSessionId: String,
    description: String,
    action: String,
    detail: String
  ): IO[Unit] =
    BgNotifyDelivery
      .resolveRootSessionId(Some(resources.agentRegistry), delegateId, parentSessionId)
      .flatMap { rootId =>
        if rootId.trim.isEmpty then
          logger.warn(
            s"[merge-window-care] no responsible session for '$delegateId' — escalation dropped with this line"
          )
          IO.unit
        else
          val event = AgentCommand.ExternalEvent(
            source = "merge-window-care",
            eventType = "failed",
            payload =
              s"""[merge-window-care] delegate "$description" ($delegateId): $detail. The merge queue moved on; the delivery was escalated to this session (the previous responsible party is not live).""",
            metadata = JsonObject(
              "delegateId" -> delegateId.asJson,
              "description" -> description.asJson,
              "action" -> action.asJson,
              "escalatedBy" -> "merge-window-care".asJson
            ),
            correlationId = Some(s"merge-window-care-$action-$delegateId")
          )
          BgNotifyDelivery
            .deliverToRootOrInbox(Some(resources.actorSystem), Some(resources.agentRegistry), rootId, event)
            .void
      }
      .handleErrorWith(e => logger.warn(s"[merge-window-care] escalation failed for $delegateId: ${e.getMessage}"))
  end escalate

end MergeWindowCare
