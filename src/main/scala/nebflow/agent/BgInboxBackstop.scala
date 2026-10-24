package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import nebflow.actor.AgentCommand
import nebflow.core.tools.BgInboxPort

/**
 * D1 后台任务通知「持久补投」面（steward 批 2026-10-23）：[[BgInboxPort]] 的
 * agent 侧实现——把待补投事件 append 进目标会话的**持久注入队**
 * （`~/.nebflow/sessions/<sessionId>/injection-queues.json`，CompactionQueueStore）。
 * 会话复活 / 新回合 spawn 时既有 `RecoverPersistedQueues` 重放 ⇒ 补投到达。
 *
 * 语义与既有先例逐字同族（`BackoffSupervisor.f2DeliveryBackstop` /
 * `SubAgentStartupRecovery.notifyOnce`）：
 *   - correlationId 幂等（crash-between-steps 防 duplicate）；
 *   - 会话已删 = 跳过（不产生无人读的垃圾文件）；
 *   - fail-soft：存储面故障只 WARN，绝不拖垮调用链（投递兜底在终态三清之后跑）。
 */
object BgInboxBackstop:

  def face(resources: SharedResources): BgInboxPort.Face =
    new BgInboxPort.Face:
      def append(sessionId: String, event: AgentCommand.ExternalEvent): IO[Unit] =
        val sid = sessionId.trim
        if sid.isEmpty then IO.unit
        else
          CompactionQueueStore
            .load(sid)
            .flatMap { existing =>
              val already =
                existing.exists(_.events.exists(_.correlationId.contains(event.correlationId.getOrElse(""))))
              if already then IO.unit
              else
                resources.sessionStore.listSessions.flatMap { sessions =>
                  if !sessions.exists(_.id == sid) then IO.unit
                  else
                    val q = existing.getOrElse(CompactionQueueStore.PersistedQueues())
                    CompactionQueueStore.save(sid, q.copy(events = q.events :+ event))
                }
            }
            .handleErrorWith(e =>
              nebflow.shared.NebflowLogger
                .forName("nebflow.bg-notify")
                .warn(s"[bg-notify-inbox] append failed for session '$sid': ${e.getMessage}")
            )
        end if
      end append
  end face

end BgInboxBackstop
