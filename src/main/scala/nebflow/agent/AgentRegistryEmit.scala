/* 从 AgentCore.scala 迁出(行为保持重构,2026-09-25)。 */
package nebflow.agent

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.actor.AgentCommand.*
import nebflow.agent.PromptSections.*
import nebflow.core.*
import nebflow.core.compact.*
import nebflow.core.hooks.*
import nebflow.core.project.{NodeRoles, ProjectRuntimeRegistry}
import nebflow.core.tools.*
import nebflow.llm.{Fallback, TurnBudgetExceeded}
import nebflow.shared.given
import nebflow.shared.{NebflowLogger, *}

import scala.concurrent.duration.*

private[agent] trait AgentRegistryEmit:

  private[agent] val lifecycleLog = NebflowLogger.forName("nebflow.agent.lifecycle")

  private[agent] type ProcessingFn =
    (AgentDef, SharedResources, Int, Option[ActorRef[AgentCommand]], AgentState) => Behavior[AgentCommand]

  private def shortUuid(id: String): String =
    if id.startsWith("agent-") then id.drop(6).take(8)
    else if id == "-" || id == "system" then id
    else id.take(8)

  private[agent] def logAgentEvent(
    agentDef: AgentDef,
    depth: Int,
    sessionId: Option[String],
    sessionName: Option[String],
    event: String,
    detail: String = ""
  )(using ctx: ActorContext[AgentCommand]): Unit =
    val sid = shortUuid(sessionId.getOrElse("-"))
    val sname = sessionName.getOrElse("-")
    val who = if depth == 0 then s"${agentDef.name}-${shortUuid(ctx.self.path.name)}" else s"subagent-${agentDef.name}"
    val logCtx = lifecycleLog.ctxPrefix(who, s"$sname/$sid")
    lifecycleLog.infoSync(if detail.nonEmpty then s"$logCtx event=$event detail=$detail" else s"$logCtx event=$event")

  // processing 域迁移(2026-09-25):AgentProcessing 经 import AgentActor.* 调用,
  // protected 对包内非子类不可见 ⇒ 放宽为 private[agent](trait 仅包内可见,外延不变)。
  private[agent] def persistIfSession(resources: SharedResources, state: AgentState): IO[Unit] =
    state.sessionId match
      case Some(sid) => resources.sessionStore.saveMessagesForSession(sid, state.messages)
      case None => IO.unit

  private[agent] def emitStream(
    wsSend: io.circe.Json => IO[Unit],
    event: AgentStreamEvent,
    isSubagent: Boolean = true,
    sessionId: Option[String] = None
  )(using ctx: ActorContext[AgentCommand]): IO[Unit] =
    val eventName = event.getClass.getSimpleName
    val json = event.toJson(ctx.self.path.name, isSubagent, sessionId)
    ctx.forkTurn(
      wsSend(json).handleErrorWith(e =>
        NebflowLogger.forName("nebflow.agent").warn(s"emitStream($eventName) failed: ${e.getMessage}")
      )
    )

  /**
   * Direct wsSend for use inside Fiber context (pipeLlmCall, pipeToolExecutions).
   * Do NOT call from receive handlers — use emitStream instead (non-blocking forkTurn wrapper).
   */
  private[agent] def emitStreamIO(
    wsSend: io.circe.Json => IO[Unit],
    event: AgentStreamEvent,
    isSubagent: Boolean = true,
    sessionId: Option[String] = None
  )(using ctx: ActorContext[AgentCommand]): IO[Unit] =
    wsSend(event.toJson(ctx.self.path.name, isSubagent, sessionId))

  /**
   * P0 阶段 3（2026-08-18，设计 §4.4）：更新 agentRegistry 的活动快照。
   * TaskStuckWatcher 读 AgentRecord.status + lastActivityMs 判定卡死——
   * 本 helper 是这两个字段的主要写入点（注册点默认值除外）：
   *   - Processing：AgentCore.pipeLlmCall（LLM 调用开始 + 每个流 chunk）
   *   - Idle：AgentActor.finishTurnCont 回 idle 分支（turn 完成）
   *
   * 2026-09-10 卡死判据换轴（取证 20260910_130621_flow-node-activity-signal-forensics.md）：
   *   - **本 helper 是 lastActivityMs 的唯一 agent 侧写入路径**——进程侧活性改
   *     写 `processActivityMs`（BashTool 活动桥 / RemoteExecutor 心跳），不再经此。
   *   - `turnStart`：status 由非 Processing（且非 WaitingForUser 这个人机交互
   *     子态）转入 Processing 时置 `turnStartedAt = now`；同 turn 内的多轮
   *     LLM/工具循环不重置（否则「turn 起点」退化成「轮起点」）。
   *   - `toolStarting`：工具执行开始 → 记 `currentToolName/currentToolStartedAt`
   *     （同一 modify 路径，不另开写入路径）。
   *   - `clearToolPhase`：工具批次完成 → 清工具相位。
   *   - 离开 Processing 一律清工具相位（Idle/WaitingForUser/Frozen/Error 相位
   *     无意义，留着会让下一次 turn 继承过期相位）。
   * 幂等且仅在记录存在时更新（registry 无该 session 时 no-op——不创建幽灵条目）。
   */
  private[agent] def touchRegistryActivity(
    resources: SharedResources,
    sessionId: Option[String],
    status: AgentStatus,
    now: Long = System.currentTimeMillis(),
    turnStart: Boolean = false,
    toolStarting: Option[(String, Long)] = None,
    clearToolPhase: Boolean = false,
    /**
     * R6（取消静默死锁修复批 2026-09-10）：本工具自己声明的授权时长（ms）——与
     * `toolStarting` 同批传入（0 = 未声明）。末尾带默认值，既有位置构造/调用零破坏。
     */
    toolDeadlineMs: Long = 0L
  ): IO[Unit] =
    sessionId.fold(IO.unit) { sid =>
      resources.agentRegistry.modify { m =>
        m.get(sid) match
          case Some(rec) =>
            // turn 起点：仅当本会话此前不在 Processing（WaitingForUser 是同一
            // turn 内的人机交互子态，不算新 turn 起点）。
            val turnBegan =
              turnStart && rec.status != AgentStatus.Processing && rec.status != AgentStatus.WaitingForUser
            val withTurn = if turnBegan then rec.copy(turnStartedAt = now) else rec
            val withPhase = toolStarting match
              case Some((name, startedAt)) =>
                withTurn.copy(
                  currentToolName = Some(name),
                  currentToolStartedAt = startedAt,
                  currentToolDeadlineMs = toolDeadlineMs
                )
              case None =>
                if clearToolPhase then
                  withTurn.copy(currentToolName = None, currentToolStartedAt = 0L, currentToolDeadlineMs = 0L)
                else withTurn
            val phased =
              if status == AgentStatus.Processing then withPhase
              else withPhase.copy(currentToolName = None, currentToolStartedAt = 0L, currentToolDeadlineMs = 0L)
            (m.updated(sid, phased.copy(status = status, lastActivityMs = now)), ())
          case None => (m, ())
      }
    }

end AgentRegistryEmit
