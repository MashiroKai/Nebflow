package nebflow.core.tools

import cats.effect.IO
import cats.effect.kernel.Deferred
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.delegate.DelegateRegistry
import nebflow.core.node.NodeRunner
import nebflow.shared.{NebflowLogger, SubAgentTask}

import scala.concurrent.duration.*

/**
 * BlockingSubagent —— 阻塞式只读侦察子会话的单一 spawn 点（builtin-merge 批
 * 2026-10-03）。
 *
 * Explore 同型（zcode/claude code 的 explore 子代理）：spawn 一个 `subagent`
 * 内置会话（只读工具面，机制固定 = `AgentCore.SubagentFixedTools`），**阻塞**
 * 到它完成，把最终文本作为工具结果返回。
 *
 * ==完成桥（wsSend 间谍）==
 * 子会话的流事件本来就要经 `wsSend` 上行（面板渲染面）——本点在路由包装外再包
 * 一层间谍：见到 `agentDone` / `agentEnd` 且归属本子会话（nodeSessionId 或
 * agentId 命中）即完成 Deferred。零新协议、零新 actor；超时（[[Timeout]]）
 * 是兜底而非主路径。
 *
 * ==叶子纪律==
 * subagent 是叶子：不注册 SupervisorAdapter（无后台续命），终态即回收；取消
 * 由 AgentControl 的 SubTask 通道（Stop fallback）覆盖。
 */
object BlockingSubagent:
  private val logger = NebflowLogger(getClass)

  /** 单次侦察硬超时（wall-clock；不含人机等待——本会话不等人）。 */
  val Timeout: FiniteDuration = 15.minutes

  /** 阻塞运行一次子会话。返回子会话最终文本。
    *
    * @param defName spawn 目标的内置定义名：`subagent`（只读侦察面，默认）或
    *   `nebflow`（执行面——WorkflowTool 的步骤用这套，步骤要写要执行）。 */
  def run(
      prompt: String,
      description: String,
      ctx: ToolContext,
      defName: String = nebflow.core.entity.BuiltinAgents.SubagentName
  ): IO[Either[ToolError, String]] =
    (ctx.actorSystem, ctx.sharedResources) match
      case (Some(system), Some(resources)) =>
        if ctx.depth >= DelegateTool.MaxDepth then
          IO.pure(Left(ToolError(s"Maximum sub-agent depth (${DelegateTool.MaxDepth}) reached.")))
        else
          val childId = s"subagent-${java.util.UUID.randomUUID().toString.take(8)}"
          val parentSid = ctx.sessionId
          val childDepth = ctx.depth + 1
          for
            done <- IO.deferred[Option[String]]
            childWsSend = spy(done, childId, DelegateTool.routeWsSendFor(childId, parentSid, ctx.wsSend))
            agentDef = nebflow.core.entity.BuiltinAgents.entry(defName).map(_.toAgentDef).get
            params = NodeRunner.SpawnParams(
              agentDef = agentDef,
              resources = resources,
              sessionId = childId,
              sessionName = description,
              depth = childDepth,
              parentRef = ctx.agentActorRef,
              wsSend = childWsSend,
              projectRoot = Some(ctx.projectRoot),
              rootSessionId = rootOf(ctx)
            )
            ref <- NodeRunner.spawnAgentActor(system, params)
            _ <- NodeRunner.registerAgent(
              resources,
              id = childId,
              ref = ref,
              kind = AgentKind.SubTask,
              rootSessionId = rootOf(ctx),
              parentRef = ctx.agentActorRef,
              startedAt = System.currentTimeMillis(),
              lastActivityMs = System.currentTimeMillis(),
              parentSessionId = parentSid.getOrElse("")
            )
            _ <- resources.subAgentTaskStore
              .recordTask(
                SubAgentTask(
                  taskId = childId,
                  parentSessionId = parentSid.getOrElse(""),
                  agentName = defName,
                  prompt = prompt,
                  description = description,
                  status = "running",
                  retryCount = 0,
                  spawnedAt = System.currentTimeMillis(),
                  completedAt = None,
                  lastError = None,
                  source = "subagent"
                )
              )
              .handleErrorWith(e => logger.warn(s"subAgentTaskStore.recordTask failed: ${e.getMessage}"))
            _ <- ref ! AgentCommand.UserInput(prompt, None)
            result <- done.get
              .timeout(Timeout)
              .map {
                case Some(text) if text.trim.nonEmpty => Right(text.trim)
                case _ => Left(ToolError(s"Subagent '$childId' ended without a final text."))
              }
              .handleErrorWith { e =>
                logger.warn(s"subagent $childId timeout/crash: ${e.getMessage}")
                (ref ! AgentCommand.Stop(s"subagent-timeout")).attempt.void.as(
                  Left(ToolError(s"Subagent did not finish within $Timeout — cancelled. Narrow the question and retry."))
                )
              }
          yield result
      case _ =>
        IO.pure(Left(ToolError("Subagent requires ActorSystem and SharedResources")))

  private def rootOf(ctx: ToolContext): String =
    ctx.rootSessionId.orElse(ctx.sessionId).getOrElse("")

  /** wsSend 间谍：agentDone/agentEnd 且 nodeSessionId 命中 ⇒ 完成桥。 */
  private[tools] def spy(
      done: Deferred[IO, Option[String]],
      childId: String,
      base: io.circe.Json => IO[Unit]
  ): io.circe.Json => IO[Unit] = json =>
    base(json) *>
      {
        val obj = json.asObject
        val t = obj.flatMap(_("type")).flatMap(_.asString)
        val nsid = obj.flatMap(_("nodeSessionId")).flatMap(_.asString).orElse(obj.flatMap(_("agentId")).flatMap(_.asString))
        if (t == Some("agentDone") || t == Some("agentEnd")) && (nsid.isEmpty || nsid.contains(childId)) then
          obj.flatMap(_("finalText")).flatMap(_.asString).match
            case Some(text) => done.complete(Some(text)).attempt.void
            case None       => done.complete(None).attempt.void
        else IO.unit
      }
