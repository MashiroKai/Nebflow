package nebflow.core.tools

import cats.effect.IO
import cats.effect.kernel.Deferred
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.delegate.DelegateRegistry
import nebflow.core.node.NodeRunner
import nebflow.shared.{Defaults, NebflowLogger, SubAgentTask}

import scala.concurrent.duration.*

/**
 * BlockingSubagent —— 阻塞式只读侦察子会话的单一 spawn 点。
 *
 * Explore 同型（外部 CLI agent 的 explore 型子代理）：spawn 一个 `subagent`
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
 *
 * ==终态回收==
 * 「终态即回收」此前只是一句注释，没有任何实现：桥完成（或超时）后 registry
 * 条目（kind=SubTask）滞留 status=Idle、task store 恒 "running"、子 actor 永驻
 * idle——getActiveAgents 快照按「registry 有记录 = 任务在飞」的契约如实上报，
 * 子智能体面板随即渲染永不消失的「空闲」行（现场实证：
 * turn-complete 的 subagent-* 子会话挂面板 5 分钟+）。现补上 [[reap]]：镜像 BackoffSupervisor.notifyParentAndStop 的
 * 结算面（task store 终态化 → registry 移除 → ask 槽清理 → child Stop），减去
 * 父通知（父的结果 = 工具返回值本身，不重投）。delegate 编排模型零改动。
 */
object BlockingSubagent:
  private val logger = NebflowLogger(getClass)

  /** 单次侦察硬超时（wall-clock；不含人机等待——本会话不等人）。 */
  val Timeout: FiniteDuration = 15.minutes

  /**
   * 阻塞运行一次子会话。返回子会话最终文本。
   *
   * @param defName spawn 目标的内置定义名：`subagent`（只读侦察面，默认）或
   *   `nebflow`（执行面——WorkflowTool 的步骤用这套，步骤要写要执行）。
   */
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
              kind = nebflow.actor.AgentKind.SubTask,
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
            // sleep-poll 禁令批 C 段：步/子会话首条消息注入等待纪律铁律
            // （Defaults.NoSleepPollRule 单点——delegate brief / 节点脚注同源）。
            // 只读侦察面无 Bash，规则是纯提示成本（~60 token）；workflow 步
            // （defName=nebflow，有执行面）是本注入的主对象。包装单点 =
            // [[promptWithRule]]（spec 判红锚）。
            _ <- ref ! AgentCommand.UserInput(promptWithRule(prompt), None)
            result <- done.get
              .timeout(Timeout)
              .map {
                case Some(text) if text.trim.nonEmpty => Right(text.trim)
                case _ => Left(ToolError(s"Subagent '$childId' ended without a final text."))
              }
              .handleErrorWith { e =>
                logger.warn(s"subagent $childId timeout/crash: ${e.getMessage}")
                (ref ! AgentCommand.Stop(s"subagent-timeout")).attempt.void *>
                  // 超时腿子会话被 Stop 掐断，不再有自家 agentDone
                  // 帧——补一帧面板终态，活面板的行即时翻终态（成功腿子会话自家的
                  // agentDone 已随间谍上行，无需补发）。
                  childWsSend(
                    io.circe.Json.obj("type" -> "agentDone".asJson, "agentId" -> childId.asJson)
                  ).attempt.void.as(
                    Left(
                      ToolError(s"Subagent did not finish within $Timeout — cancelled. Narrow the question and retry.")
                    )
                  )
              }
            // 叶子终态回收——桥已定局（成功或超时），
            // 结算 registry / task store / 子 actor。滞留即「空闲」僵尸行 + 永久
            // running 记录 + actor 泄漏（根因取证见类注释）。
            _ <- reap(
              resources = resources,
              parentSessionId = parentSid.getOrElse(""),
              childId = childId,
              childRef = ref,
              completed = result.isRight
            )
          yield result
          end for
      case _ =>
        IO.pure(Left(ToolError("Subagent requires ActorSystem and SharedResources")))

  private def rootOf(ctx: ToolContext): String =
    ctx.rootSessionId.orElse(ctx.sessionId).getOrElse("")

  /**
   * 步/子会话首条消息铁律包装（sleep-poll 禁令批 C 段单点；spec 判红锚）——
   * 铁律前缀 + 空行 + 原始任务文本（子会话无历史，首条即全部上下文）。
   */
  private[tools] def promptWithRule(prompt: String): String =
    s"${Defaults.NoSleepPollRule}\n\n$prompt"

  /**
   * 叶子终态回收——镜像 [[BackoffSupervisor]]
   * `notifyParentAndStop` 的结算面，减去父通知（父的结果 = 工具返回值本身，
   * 已随桥交付，不重投）：
   *   ① task store 终态化（bridge 见到终态帧 = completed；超时兜底 = cancelled，
   *      与 AgentControl cancel 腿的 taskStatusOverride 同词表）；
   *   ② agentRegistry 移除——getActiveAgents 的契约「registry 有记录 = 任务
   *      在飞（完成即注销）」由此恢复（WebSocketRoutes.filterActiveAgents）；
   *   ③ InteractionHub ask 槽清理（僵尸审批卡防线，与监督者同款）；
   *   ④ child Stop——idle 永驻的子 actor 退场（AgentIdle Stop 分支：
   *      cancelTurn + 杀 shell + 生命周期钩子 + stopped）。
   * 全程 fail-soft：任何一腿失败只落日志，绝不改写工具结果。
   */
  private[tools] def reap(
    resources: nebflow.core.AgentRuntimePort,
    parentSessionId: String,
    childId: String,
    childRef: nebflow.actor.ActorRef[nebflow.actor.AgentCommand],
    completed: Boolean
  ): IO[Unit] =
    val status = if completed then "completed" else "cancelled"
    val taskUpdate = resources.subAgentTaskStore
      .updateStatus(parentSessionId, childId, status, completedAt = Some(System.currentTimeMillis()))
      .handleErrorWith(e => logger.warn(s"subagent $childId taskStore terminalize failed: ${e.getMessage}"))
    val registryRemove = resources.agentRegistry
      .update(_ - childId)
      .handleErrorWith(e => logger.warn(s"subagent $childId registry remove failed: ${e.getMessage}"))
    val askCleanup = resources.interactionHubRef.get
      .flatMap {
        case Some(hub) => (hub ! nebflow.actor.InteractionHubCommand.CleanupForSession(childId)).void
        case None => IO.unit
      }
      .handleErrorWith(e => logger.warn(s"subagent $childId ask-slot cleanup failed: ${e.getMessage}"))
    val stop = (childRef ! nebflow.actor.AgentCommand.Stop(s"subagent-terminal($status)"))
      .handleErrorWith(e => logger.warn(s"subagent $childId stop offer failed: ${e.getMessage}"))
    taskUpdate *> registryRemove *> askCleanup *> stop *>
      logger.info(s"subagent $childId reaped at terminal: status=$status, registry entry removed, actor stopped")

  end reap

  /** wsSend 间谍：agentDone/agentEnd 且 nodeSessionId 命中 ⇒ 完成桥。 */
  private[tools] def spy(
    done: Deferred[IO, Option[String]],
    childId: String,
    base: io.circe.Json => IO[Unit]
  ): io.circe.Json => IO[Unit] = json =>
    base(json) *> {
      val obj = json.asObject
      val t = obj.flatMap(_("type")).flatMap(_.asString)
      val nsid =
        obj.flatMap(_("nodeSessionId")).flatMap(_.asString).orElse(obj.flatMap(_("agentId")).flatMap(_.asString))
      if (t == Some("agentDone") || t == Some("agentEnd")) && (nsid.isEmpty || nsid.contains(childId)) then
        obj
          .flatMap(_("finalText"))
          .flatMap(_.asString)
          .match
            case Some(text) => done.complete(Some(text)).attempt.void
            case None => done.complete(None).attempt.void
      else IO.unit
    }
end BlockingSubagent
