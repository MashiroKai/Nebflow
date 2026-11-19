package nebflow.agent

import cats.effect.IO
import io.circe.Json
import nebflow.core.AgentRuntimePort
import nebflow.core.delegate.DelegateRegistry

/**
 * DelegateWakeLeg —— 冻结 delegate 的**唤醒唯一腿**（冻结通知策略批 2026-11-05）。
 *
 * 作者令（10:36 逐字）：「我们引擎要做的就行检测恢复了能接着工作」＋ 对齐清单第 3 条
 * 「provider 故障**不属任务失败、不该扣重试预算**；建议改成带 `clientMessageId` 的
 * **UserInput 唤醒腿**（幂等、不占预算）」。
 *
 * 两条子腿（都不新建会话、都不碰 supervisor 重启预算）：
 *   - **live**（统一注册表里有活体 actor）：向**原 actor** 注入 `UserInput`，正文前缀 =
 *     [[nebflow.core.delegate.DelegateFreeze.wakeBanner]]（冻结点 + 原因 + 「已完成的不
 *     重做」），随后 [[nebflow.core.delegate.DelegateFreeze.onWoken]] 清冻结槽 /
 *     台账行翻 running / 合并队列重排。**绝不**发 `RestartAgent`——那是 supervisor
 *     重启腿（2 次/5min 预算），provider 故障不是任务失败，不该花预算；
 *   - **不 live**（监督者已自停或引擎重启过）：[[DelegateRevival.wakeFrozen]] 同 id
 *     复活（transcript checkpoint 续跑，**不新建会话**）。
 *
 * `clientMessageId` 幂等键：`frozen-wake:<delegateId>:<frozenSince>`——同一次冻结重复
 * 唤醒（面板连点 / 引擎自愈与人工唤醒撞车）由 actor 侧既有幂等闸去重，不产生两个回合。
 *
 * 调用方：面板 `delegateWake`（gateway）与引擎自动续跑（[[FreezeAlertSweep]]）共用本腿，
 * 杜绝「恢复路径两套」。
 */
object DelegateWakeLeg:

  /** 唤醒结果：`Right(mode)` = 已唤醒（`live` / `revived`），`Left(原因)` = 没唤成。 */
  type Result = Either[String, String]

  private val waking = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()

  private[agent] def exclusiveWake(id: String)(action: IO[Result]): IO[Result] =
    IO(waking.add(id)).bracket { acquired =>
      if acquired then action
      else IO.pure(Left("Recovery is already in progress (RECOVERY_IN_PROGRESS)"))
    } { acquired => IO { if acquired then waking.remove(id); () } }

  /** live 腿的注入源标记（前端气泡来源标签 / 审计行）。 */
  val LiveSource: String = "delegate-wake"

  /**
   * 唤醒（推荐入口）。
   *
   * @param via 唤醒者标记（`panel-wake` / `self-heal` / `mail`——审计行 `delegate-frozen-wake`）
   */
  def wake(
    resources: AgentRuntimePort,
    wsSend: Option[Json => IO[Unit]],
    delegateId: String,
    instruction: String,
    via: String
  ): IO[Result] =
    exclusiveWake(delegateId) {
      resources.executionProtection(delegateId).flatMap {
        case nebflow.shared.ExecutionProtection.Unprotected =>
          resources.sessionStore.loadMessagesForSession(delegateId).attempt.flatMap {
            case Left(_) => IO.pure(Left("Cannot read saved context (RECOVERY_UNVERIFIED)"))
            case Right(messages) if messages.isEmpty || nebflow.core.ResumeHandoff.hasUnresolvedToolCalls(messages) =>
              IO.pure(Left("Saved context or a tool outcome is unknown; review before retrying (RECOVERY_UNVERIFIED)"))
            case Right(messages) =>
              wakeVerified(resources, wsSend, delegateId, instruction, via, messages)
          }
        case _ => IO.pure(Left("Protected or unknown execution requires exact-ref recovery (RECOVERY_UNVERIFIED)"))
      }
    }

  private def wakeVerified(
    resources: AgentRuntimePort,
    wsSend: Option[Json => IO[Unit]],
    delegateId: String,
    instruction: String,
    via: String,
    transcript: List[nebflow.shared.Message]
  ): IO[Result] =
    IO.blocking(DelegateRegistry.find(delegateId)).flatMap {
      case None => IO.pure(Left(s"no delegate record for '$delegateId' (DELEGATE_NOT_FOUND)"))
      case Some(record) if !nebflow.core.delegate.DelegateFreeze.isFreezeWire(record.frozenReason.getOrElse("")) =>
        IO.pure(
          Left(
            s"'$delegateId' is not in the delegate-error frozen state " +
              s"(frozenReason=${record.frozenReason.getOrElse("none")})"
          )
        )
      case Some(record) if wakeExpired(record) =>
        IO.pure(
          Left(
            s"'$delegateId' has been frozen past the retention window (state-derived from frozenSince; the retired " +
              "archive flag used to express this) — re-delegate the work"
          )
        )
      case Some(record) =>
        val frozenSince = record.frozenSince.getOrElse(0L)
        val banner = nebflow.core.delegate.DelegateFreeze.wakeBanner(
          delegateId,
          record.frozenReason.getOrElse(""),
          record.frozenDetail.getOrElse("")
        )
        val body = if instruction.trim.isEmpty then banner else s"$banner\n\n$instruction"
        resources.agentRegistry.get.flatMap { reg =>
          val verified = reg.get(delegateId).fold(IO.pure(Option.empty[nebflow.actor.AgentRecord])) { record =>
            resources.actorSystem.isAlive(record.ref.path).map(alive => Option.when(alive)(record))
          }
          verified.flatMap { current =>
            current match
              case Some(live) if live.kind == nebflow.actor.AgentKind.Delegate =>
                // 无感恢复批 2026-11-07：唤醒载荷单点 = [[nebflow.core.ResumeHandoff]]。
                // 冻结可能发生在工具批次中途（断点处留下无 result 的 tool_use）⇒ 模型
                // 收到那次调用的 tool_result，接着往下走；无工具边界时才注入唤醒横幅。
                val handoff = nebflow.core.ResumeHandoff.plan(
                  transcript,
                  nebflow.core.ResumeHandoff.Cause.Freeze,
                  instruction = instruction,
                  legacyText = body
                )
                IO(nebflow.core.ResumeHandoff.logPlan(delegateId, handoff, nebflow.core.ResumeHandoff.Cause.Freeze)) *>
                  (live.ref ! nebflow.core.ResumeHandoff.userInput(
                    handoff,
                    replyTo = live.supervisorRef,
                    // 幂等键：同一次冻结只认一次唤醒（面板连点 / 引擎自愈撞车 ⇒ 单回合）。
                    clientMessageId = Some(s"frozen-wake:$delegateId:$frozenSince"),
                    sender = Some(via),
                    eventType = Some("info")
                  )) *>
                  nebflow.core.delegate.DelegateFreeze
                    .onWoken(resources, delegateId, via, wsSend)
                    .handleErrorWith(e =>
                      nebflow.shared.NebflowLogger
                        .forName("nebflow.agent.delegate-wake")
                        .warn(s"[delegate-wake] onWoken failed for $delegateId: ${e.getMessage}")
                    )
                    .as(Right("live"): Result)
              case _ =>
                DelegateRevival
                  .wakeFrozen(DelegateRevival.Deps(resources, resources.actorSystem, wsSend), delegateId, instruction)
                  .map(_.map(_ => "revived"))
          }
        }
    }

  /**
   * 冻结保留期是否已过（单点引用 [[nebflow.core.delegate.DelegateFreeze.retentionExpired]]：
   * 状态派生纯函数）。阶段② 起本闸不再读归档旗——「过保留期」由冻结龄现读复算。
   */
  private def wakeExpired(record: nebflow.core.delegate.DelegateRegistry.Record): Boolean =
    record.frozenSince.exists(ts => nebflow.core.delegate.DelegateFreeze.retentionExpired(ts))

  /** 是否属「不花重启预算」的唤醒对象（provider 故障族 —— 自愈与面板都只走本腿）。 */
  def isAutoResumable(frozenReason: String): Boolean =
    nebflow.actor.FreezeDisplay.isInfraReasonCode(
      Option(frozenReason).getOrElse("").stripPrefix(nebflow.core.delegate.DelegateFreeze.WirePrefix).stripPrefix(":")
    )

end DelegateWakeLeg
