package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.AgentRuntimePort
import nebflow.core.delegate.DelegateRegistry
import nebflow.core.executor.ExecutorRegistry
import nebflow.core.node.NodeRunner
import nebflow.core.project.{ProjectRuntimeRegistry, TaskLedgerHistory, TaskLedgerStore}
import nebflow.shared.{NebflowLogger, PathUtil, SubAgentTask}

/**
 * DelegateTool —— Nebula 唯一的派发入口（unified-delegate 批 2026-10-03）。
 *
 * 大改后的形态（作者令）：**只填 `task` 与 `project`（选填）**——
 *   - 执行器 = 主 agent 动态选择（external-executors 批 #1225，S14 运行时半）：
 *     可选 `executor` 参数为唯一显式选择输入，缺省内置 `nebflow`；可用集 =
 *     [[ExecutorRegistry]] 检测面（GET /api/executors 快照）；不可用的显式
 *     选择 = 显式报错——不静默回落。
 *   - 工作目录 = `project` 挂载项目的 workspace；缺省 = 默认工作目录
 *     `<dataRoot>/general/`（首次派发现建）。
 *   - 台账 = 每次派发在 TaskLedger 落一条（task #N 随回执返回，地址面与
 *     任务列表共用这个号码）。
 *   - 地址 = `delegate:<id>`（[[DelegateRegistry]] 持久记录）——Mail 续聊、
 *     AgentControl 地址列、任务列表的唯一地址形态。旧 `kernel:<id>` 面
 *     由 MailTool 侧 tombstone（同一批）。
 *
 * 语义保留：后台一次性执行、结果以 `source="delegate"` 系统消息回投父会话、
 * BackoffSupervisor 拥有崩溃重启与 3600s 预算（DelegateBudget）、无并发上限
 * （2026-09-26 作者裁定，退休注释保留在 spawnBackground）。
 */
object DelegateTool extends Tool:
  private val logger = NebflowLogger(getClass)

  /** 内置执行器 spawn 的 agent 定义名（builtin-merge 批：kernel+general ⇒ nebflow）。 */
  val KernelAgentName = nebflow.core.entity.BuiltinAgents.ExecutorName

  /** 默认工作目录名（`project` 缺省时的工作座位）。 */
  val DefaultWorkspaceName = "general"

  /** Sub-agent 深度上限（与 `AgentCore.MaxDepth` 同值；执行会话是叶子）。 */
  val MaxDepth: Int = 5

  val name = "Delegate"

  // 模型可见文案（executor 选择反转——S14 运行时半，external-executors 批
  // #1225 2026-10-06）。⚠ §16 待过目：本描述段为最小改写（终稿归 N3 ⑭），
  // 语义 = 「executor 由主 agent 动态选择；缺省 nebflow」（增补任务书 3.1）。
  val description =
    """Delegate a task to a background executor — the built-in Nebflow agent (default) or an external CLI agent installed on this machine. It runs in the background and its result is delivered back to your session as a system message when it finishes.

**Parameters:**
- `task` (required): self-contained brief — goal, constraints, absolute paths, exact commands/limits, and what "done" looks like. The executor starts with a clean context; the brief is its entire world.
- `project` (optional): name of a mounted project — the task then runs in that project's workspace. Omit it to run in the default general workspace.
- `executor` (optional): which executor runs the task. You pick it dynamically per task; omit it for the built-in default (`nebflow`). The available set (with detected external CLIs) is listed in your "Available executors" prompt section. An unavailable pick fails explicitly — there is no silent fallback.

**Result and continuation address:** the tool result is an ACK carrying the task number (`task #N`) and the continuation address `delegate:<id>`. To send the instance more input (supplements, corrections), use `Mail(address="delegate:<id>", message=...)` — a running instance gets it queued for its next continuation turn; a finished one is continued as a new turn. Do not start a second Delegate for the same work.

**Rules:**
- Do NOT duplicate the delegate's work — work on something else and let the result arrive as a system message.
- The ack is not the result.
- **Limits:** No hard concurrency limit — in-flight delegates run concurrently.
- **Safety**: NEVER send signals to or kill any sbt/java/nebflow process — you run inside a Nebflow instance; killing it kills you and the user's session. Process inspection with `ps` (read-only) is fine."""

  def inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "task" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Self-contained task brief: goal, constraints, absolute paths, exact commands/limits, what \"done\" looks like. The executor starts with a clean context and no conversation history.".asJson
        ),
        "project" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Optional mounted project name — the task runs in that project's workspace. Omit for the default general workspace.".asJson
        ),
        "executor" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Optional executor id — you pick dynamically per task (see the \"Available executors\" section). Omit for the built-in default \"nebflow\". An unavailable id fails explicitly; there is no silent fallback.".asJson
        )
      ),
      "required" -> io.circe.Json.arr("task".asJson)
    )
  )

  /** UI 标签 = 任务首行截断（schema 已无 description 参数）。 */
  private[tools] def labelOf(task: String): String =
    task.linesIterator.nextOption().getOrElse("delegate").trim match
      case "" => "delegate"
      case s  => if s.length > 60 then s.take(57) + "..." else s

  def summarize(input: JsonObject): String =
    s"Delegate(${labelOf(input("task").flatMap(_.asString).getOrElse(""))})"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 200 then result.take(197) + "..." else result

  private def routeWsSend(
    wsSend: Option[io.circe.Json => IO[Unit]],
    parentSessionId: Option[String],
    subagentId: String
  ): io.circe.Json => IO[Unit] =
    val base = wsSend.getOrElse((_: io.circe.Json) => IO.unit)
    parentSessionId match
      case Some(sid) =>
        json =>
          json.asObject match
            case Some(obj) =>
              val builder = obj.add("rootSessionId", sid.asJson).add("sessionId", sid.asJson)
              val finalObj =
                if obj.contains("nodeSessionId") then builder
                else builder.add("nodeSessionId", subagentId.asJson)
              base(Json.fromJsonObject(finalObj))
            case None => base(json)
      case None =>
        json =>
          json.asObject match
            case Some(obj) => base(Json.fromJsonObject(obj.add("nodeSessionId", subagentId.asJson)))
            case None => base(json)
    end match
  end routeWsSend

  /** 供 BlockingSubagent 复用的路由包装（nodeSessionId 戳 + 根会话索引）。 */
  private[tools] def routeWsSendFor(
      subagentId: String,
      parentSessionId: Option[String],
      wsSend: Option[io.circe.Json => IO[Unit]]
  ): io.circe.Json => IO[Unit] = routeWsSend(wsSend, parentSessionId, subagentId)

  /** 工作座位解析：挂载项目 workspace，或默认 `<dataRoot>/general/`（现建）。 */
  private[tools] def resolveWorkspace(project: Option[String]): IO[Either[ToolError, (String, Option[String])]] =
    project match
      case None =>
        IO.blocking {
          val ws = PathUtil.dataRoot / DefaultWorkspaceName
          os.makeDir.all(ws)
          Right((ws.toString, None))
        }.handleErrorWith(e => IO.pure(Left(ToolError(s"Delegate: default workspace unavailable: ${e.getMessage}"))))
      case Some(name) =>
        ProjectRuntimeRegistry.get(name).flatMap {
          case None =>
            IO.pure(Left(ToolError(s"Delegate: project '$name' is not mounted — check the exact name (mounted projects list: TaskInfo / project panel). (DELEGATE_PROJECT_UNMOUNTED)")))
          case Some(rt) =>
            val ws = os.Path(rt.project.workspace)
            IO.blocking(os.makeDir.all(ws)).map(_ => Right((ws.toString, Some(name))))
        }

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val task = input("task").flatMap(_.asString).getOrElse("")
    val project = input("project").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
    val description = labelOf(task)

    if task.trim.isEmpty then
      IO.pure(Left(ToolError("Missing required parameter: task. Give the executor a self-contained brief — goal, constraints, absolute paths, what \"done\" looks like.")))
    else if ctx.depth >= MaxDepth then
      IO.pure(Left(ToolError(s"Maximum sub-agent depth ($MaxDepth) reached. Cannot delegate further.")))
    else
      // 执行器选择反转（S14 运行时半，#1225）：显式 `executor` 参数是唯一选择
      // 输入，缺省 nebflow；nebflow.json executor 块止读（⑫）。fail-closed：
      // 不可用的显式选择 = 显式错误，零静默回落（DELEGATE_EXECUTOR_PENDING
      // 墓碑由本批真通路替换——R2/R11）。
      ExecutorRegistry.resolveRequested(input("executor").flatMap(_.asString)) match
        case Left(err) => IO.pure(Left(ToolError(err)))
        case Right(ExecutorRegistry.ResolvedSelection.Internal) =>
          internalSpawn(project, task, description, ctx)
        case Right(ExecutorRegistry.ResolvedSelection.External(defn, binPath)) =>
          externalSpawn(project, task, description, defn, binPath, ctx)
  end call

  /** 派发台账一条（fail-soft：失败不阻断派发，回执无号码）。 */
  private def createLedgerEntry(
      resolvedProject: Option[String],
      description: String
  ): IO[Option[String]] =
    IO.blocking {
      TaskLedgerStore
        .open()
        .createSyncReturningId(
          title = s"${resolvedProject.getOrElse(DefaultWorkspaceName)} — $description",
          actor = TaskLedgerHistory.Actors.Nebula
        )
    }.attempt.map {
      case Right(Right(id)) => Some(id)
      case _                => None
    }

  /** 调用者根会话解析（回投链与面板归桶锚点）。 */
  private def callerRootOf(ctx: ToolContext, resources: AgentRuntimePort): IO[String] =
    ctx.sessionId match
      case Some(sid) =>
        resources.agentRegistry.get.map(_.get(sid).map(_.rootSessionId).filter(_.nonEmpty).getOrElse(sid))
      case None => IO.pure("")

  /** 内置执行器腿（nebflow）：actor spawn ＋ BackoffSupervisor 监督（原路径）。 */
  private def internalSpawn(
      project: Option[String],
      task: String,
      description: String,
      ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    resolveWorkspace(project).flatMap {
      case Left(err) => IO.pure(Left(err))
      case Right((workspace, resolvedProject)) =>
        (ctx.actorSystem, ctx.sharedResources) match
          case (Some(system), Some(resources)) =>
            for
              rootSid <- callerRootOf(ctx, resources)
              taskId <- createLedgerEntry(resolvedProject, description)
              safetyMode <- resources.effectiveSafetyMode.map(nebflow.core.SafetyMode.toString)
              result <- spawnKernel(
                task = task,
                description = description,
                workspace = workspace,
                project = resolvedProject,
                taskId = taskId,
                system = system,
                resources = resources,
                parentDepth = ctx.depth,
                parentRef = ctx.agentActorRef,
                wsSend = ctx.wsSend,
                parentSessionId = ctx.sessionId,
                safetyMode = safetyMode,
                rootSessionId = rootSid
              )
            yield result
          case _ =>
            IO.pure(Left(ToolError("Delegate requires ActorSystem and SharedResources")))
    }

  /** 外部执行器腿（#1225 真适配器）：ExternalSessionRunner 一条通路——子进程、
    * 流帧、终态回投、续聊、取消全部在 runner；此处只做选座、台账与回执。 */
  private def externalSpawn(
      project: Option[String],
      task: String,
      description: String,
      defn: nebflow.core.executor.ExecutorRegistry.ExecutorDef,
      binPath: String,
      ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    resolveWorkspace(project).flatMap {
      case Left(err) => IO.pure(Left(err))
      case Right((workspace, resolvedProject)) =>
        ctx.sharedResources match
          case Some(resources) =>
            for
              rootSid <- callerRootOf(ctx, resources)
              taskId <- createLedgerEntry(resolvedProject, description)
              spawned <- nebflow.core.executor.ExternalSessionRunner.spawn(
                nebflow.core.executor.ExternalSessionRunner.SpawnSpec(
                  executorId = defn.id,
                  binPath = binPath,
                  task = task,
                  title = description,
                  workspace = workspace,
                  project = resolvedProject,
                  taskId = taskId,
                  parentSessionId = ctx.sessionId.getOrElse(""),
                  rootSessionId = rootSid,
                  parentRef = ctx.agentActorRef,
                  wsSend = ctx.wsSend,
                  resources = resources
                )
              )
            yield spawned
              .map { case (sessionId, ack) => ack + DelegateRegistry.continuationLine(sessionId) }
              .left.map(ToolError(_))
          case None =>
            IO.pure(Left(ToolError("Delegate requires SharedResources")))
    }

  private def spawnKernel(
    task: String,
    description: String,
    workspace: String,
    project: Option[String],
    taskId: Option[String],
    system: ActorSystem,
    resources: AgentRuntimePort,
    parentDepth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    wsSend: Option[io.circe.Json => IO[Unit]],
    parentSessionId: Option[String],
    safetyMode: String,
    rootSessionId: String
  ): IO[Either[ToolError, String]] =
    IO.blocking(nebflow.core.entity.BuiltinAgents.entry(KernelAgentName).map(_.toAgentDef).get).flatMap { agentDef =>
      spawnBackground(
        agentDef = agentDef,
        task = task,
        description = description,
        workspace = workspace,
        project = project,
        taskId = taskId,
        system = system,
        resources = resources,
        parentDepth = parentDepth,
        parentRef = parentRef,
        wsSend = wsSend,
        parentSessionId = parentSessionId,
        safetyMode = safetyMode,
        rootSessionId = rootSessionId
      ).map(_.map { case (subagentId, ack) => ack + DelegateRegistry.continuationLine(subagentId) })
    }

  /** Spawn one background executor instance. Returns `(subagentId, ack)` —
    * the id is the `delegate:<id>` continuation-address payload.
    * `private[tools]`: the Mail delegate leg's continue path reuses this
    * single spawn point (no parallel spawn path). */
  private[tools] def spawnBackground(
    agentDef: AgentDef,
    task: String,
    description: String,
    workspace: String,
    project: Option[String],
    taskId: Option[String],
    system: ActorSystem,
    resources: AgentRuntimePort,
    parentDepth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    wsSend: Option[io.circe.Json => IO[Unit]],
    parentSessionId: Option[String] = None,
    safetyMode: String = "confirm-edits",
    rootSessionId: String = ""
  ): IO[Either[ToolError, (String, String)]] =
    val childDepth = parentDepth + 1
    val agentName = agentDef.name
    val subagentId = s"delegate-$agentName-${java.util.UUID.randomUUID().toString.take(8)}"
    val childWsSend = routeWsSend(wsSend, parentSessionId, subagentId)
    val resolvedRoot = if rootSessionId.nonEmpty then rootSessionId else parentSessionId.getOrElse(subagentId)
    val params = NodeRunner.SpawnParams(
      agentDef = agentDef,
      resources = resources,
      sessionId = subagentId,
      sessionName = description,
      depth = childDepth,
      parentRef = parentRef,
      wsSend = childWsSend,
      // 工作座位 = 项目 workspace 或默认 general 目录（unified-delegate 批）；
      // 不再是临时目录——任务产物留在任务的工作目录里。
      projectRoot = Some(workspace),
      safetyMode = safetyMode,
      rootSessionId = resolvedRoot
    )
    val brief =
      s"""[workspace] $workspace (file tools need absolute paths; Bash cwd is not guaranteed)

$task"""
    val ack =
      s"""Delegate task started in the background${taskId.map(n => s" (task #$n)").getOrElse("")}.
Executor: ${agentName}; workspace: $workspace${project.map(p => s" (project '$p')").getOrElse("")}.
It runs in session $subagentId with no project context and no memory; the brief is its entire context.
You will be notified when it completes via a system message. Do NOT duplicate this task — wait for the result or work on something unrelated."""
    for
      subagentRef <- NodeRunner.spawnAgentActor(system, params)
      adapterRef <- NodeRunner.spawnSupervisedAdapter(
        system = system,
        params = params,
        childRef = subagentRef,
        childName = subagentId,
        description = description,
        agentName = agentName,
        subagentId = subagentId,
        parentSessionId = parentSessionId.getOrElse(""),
        initialPrompt = brief,
        source = "delegate",
        wsSend = Some(childWsSend)
      )
      _ = logger.info(
        s"Spawned supervised delegate session: $subagentId (depth=$childDepth, workspace=$workspace, project=${project.getOrElse("-")})"
      )
      _ <- NodeRunner.registerAgent(
        resources,
        id = subagentId,
        ref = subagentRef,
        kind = AgentKind.Delegate,
        rootSessionId = resolvedRoot,
        parentRef = parentRef,
        startedAt = System.currentTimeMillis(),
        lastActivityMs = System.currentTimeMillis(),
        supervisorRef = Some(adapterRef),
        parentSessionId = parentSessionId.getOrElse("")
      )
      _ <- IO.blocking(
        DelegateRegistry.put(
          DelegateRegistry.Record(
            id = subagentId,
            executor = ExecutorRegistry.DefaultId,
            cwd = workspace,
            project = project,
            taskId = taskId,
            parentSessionId = parentSessionId.getOrElse(""),
            title = description,
            createdAt = System.currentTimeMillis()
          )
        )
      ).handleErrorWith(e => logger.warn(s"DelegateRegistry.put failed: ${e.getMessage}").void)
      _ <- resources.subAgentTaskStore
        .recordTask(
          SubAgentTask(
            taskId = subagentId,
            parentSessionId = parentSessionId.getOrElse(""),
            agentName = agentName,
            prompt = task,
            description = description,
            status = "running",
            retryCount = 0,
            spawnedAt = System.currentTimeMillis(),
            completedAt = None,
            lastError = None,
            source = "delegate"
          )
        )
        .handleErrorWith(e => logger.warn(s"subAgentTaskStore.recordTask failed: ${e.getMessage}"))
      _ <- taskId match
        case Some(tid) =>
          IO.blocking(
            TaskLedgerStore.open().appendNoteSync(
              tid,
              s"Delegate dispatched → $subagentId (executor=${ExecutorRegistry.DefaultId}, workspace=$workspace)",
              from = TaskLedgerHistory.Origins.Nebula,
              actor = TaskLedgerHistory.Actors.Nebula
            )
          ).attempt.void
        case None => IO.unit
      _ <- subagentRef ! AgentCommand.UserInput(brief, Some(adapterRef))
    yield Right((subagentId, ack))

end DelegateTool
