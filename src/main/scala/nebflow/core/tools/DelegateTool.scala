package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.AgentRuntimePort
import nebflow.core.delegate.{ChainStore, DelegateRegistry, DelegateWorkspace}
import nebflow.core.executor.ExecutorRegistry
import nebflow.core.node.NodeRunner
import nebflow.core.project.*
import nebflow.shared.{NebflowLogger, PathUtil, PeerInfo, SubAgentTask}

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

  /**
   * brief 申报纪律行（提示词来源拆分批 2026-10-08 上提为公开常量——上提只为
   * 判据门可钉：`PromptFaceSplitSpec` 断言本行 = delegate_report 纪律且模型可见面
   * 零 node_report；文本内容 = 原 spawnWithPlugins 局部 `reportLine` 逐字，零改写。
   * S7/S8「node 同款提醒（防忘记调用）」的最小形态——delegate 常为单 turn 会话，
   * 提醒落在 brief 而非中途 ladder；未申报不阻断终态）。
   */
  val BriefReportLine: String =
    """[report] when your work reaches its end state, call delegate_report(category="finish"|"merge"|"blocked", detail=…) right before your final text — "merge" = your deliverable commits sit on your working branch and need the merge queue (you keep working until the merge reminder arrives); "finish" = done for good (pure read / no file changes, or the merge has landed); "blocked" = it cannot be completed. The report updates your status record; your final text remains the deliverable."""

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
      case s => if s.length > 60 then s.take(57) + "..." else s

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

  /**
   * 工作座位解析（v2.1 §A.3 路由语义，2026-10-09 实施批）：
   *
   *   - `Local`：本机座位——project 缺省（默认 general 目录）或 origin 缺省/self
   *     的挂载项目。**存量语义逐字节**（同一路径、同一 makeDir、同一错误）。
   *   - `RemoteRoute`：project 的 `origin.device` 绑在对端设备 ⇒ 本地 workspace
   *     解析**替换为路由判定**——派活投递给 origin 设备（DeviceExecutor 语义，
   *     [[nebflow.core.delegate.DeviceDelegate.dispatch]]）。模型面零新参数零新
   *     概念（project 名即派生事实，§A.2 理由 1-3）。
   *
   * 挂载项目仍走 [[ProjectRuntimeRegistry.get]]（现行单点）；**remote-origin 项目
   * 不在 host 挂载**（挂载面拒绝——G.12 路径错觉防护），故远端路由的解析顺序 =
   * 运行时注册表 miss ⇒ 项目注册表现读（ProjectStore.load）命中 remote-origin
   * 定义 ⇒ 路由。**未挂载的本机项目仍 = DELEGATE_PROJECT_UNMOUNTED 显式错误不变**
   * （验收 #3）。origin 设备离线/未知 ⇒ `DELEGATE_DEVICE_OFFLINE` 显式失败并列
   * 在线设备，**绝不静默落 host**（G.12）。
   */
  private[tools] sealed trait WorkspaceResolution
  private[tools] object WorkspaceResolution:
    /** 本机座位（现状形态）：workspace + 挂载项目名（缺省派发 = None）。 */
    final case class Local(workspace: String, project: Option[String]) extends WorkspaceResolution
    /** 远端路由：origin 设备 + 项目名 + origin 盘 workspace（对端本地直达零取件）。 */
    final case class RemoteRoute(peer: PeerInfo, deviceName: String, project: String, workspace: String)
        extends WorkspaceResolution
  end WorkspaceResolution

  private[tools] def resolveWorkspace(
    project: Option[String],
    ctx: ToolContext
  ): IO[Either[ToolError, WorkspaceResolution]] =
    project match
      case None =>
        IO.blocking {
          val ws = PathUtil.dataRoot / DefaultWorkspaceName
          os.makeDir.all(ws)
          Right(WorkspaceResolution.Local(ws.toString, None))
        }.handleErrorWith(e => IO.pure(Left(ToolError(s"Delegate: default workspace unavailable: ${e.getMessage}"))))
      case Some(name) =>
        def localOf(rt: ProjectRuntime): IO[Either[ToolError, WorkspaceResolution]] =
          val ws = os.Path(rt.project.workspace)
          IO.blocking(os.makeDir.all(ws)).map(_ => Right(WorkspaceResolution.Local(ws.toString, Some(name))))
        ProjectRuntimeRegistry.get(name).flatMap {
          case Some(rt) =>
            // 已挂载项目：origin 缺省/self = 现状；绑对端（手工改写 project.json 的
            // 形态）= 同样路由（防御性一致——挂载面对该形态本就拒绝，此处只读）。
            rt.project.origin match
              case None => localOf(rt)
              case Some(o) if ProjectOrigin.isSelfLiteral(o.device) => localOf(rt)
              case Some(o) => routeRemote(o.device, name, o.path, ctx)
            end match
          case None =>
            // 运行时注册表 miss：查注册表定义（remote-origin 项目不挂载的权威落点）。
            ProjectStore.load(name).flatMap {
              case None =>
                IO.pure(
                  Left(
                    ToolError(
                      s"Delegate: project '$name' is not mounted — check the exact name (mounted projects list: TaskInfo / project panel). (DELEGATE_PROJECT_UNMOUNTED)"
                    )
                  )
                )
              case Some(pd) =>
                pd.origin match
                  case Some(o) if !ProjectOrigin.isSelfLiteral(o.device) =>
                    // remote-origin 定义（未挂载是**预期形态**）→ 派生路由（非 UNMOUNTED）。
                    routeRemote(o.device, name, o.path, ctx)
                  case _ =>
                    // 本机定义但未挂载：现行显式错误**逐字不变**。
                    IO.pure(
                      Left(
                        ToolError(
                          s"Delegate: project '$name' is not mounted — check the exact name (mounted projects list: TaskInfo / project panel). (DELEGATE_PROJECT_UNMOUNTED)"
                        )
                      )
                    )
            }
        }
    end match
  end resolveWorkspace

  /**
   * 路由判定腿：origin 设备解析（精确 deviceId 优先 → 名册解析单点）。
   * 离线/未知 = `DELEGATE_DEVICE_OFFLINE` 显式失败（G.12：列在线设备，绝不静默落
   * host——静默回落会制造「以为在 D 的 workspace 跑、实际在 host 跑」的路径错觉）。
   * NebLink 未配置 = 同类显式失败（无设备网络 = 无路由标的）。
   */
  private def routeRemote(
    device: String,
    projectName: String,
    workspace: String,
    ctx: ToolContext
  ): IO[Either[ToolError, WorkspaceResolution]] =
    ctx.sharedResources.flatMap(_.neblinkService) match
      case None =>
        IO.pure(
          Left(
            ToolError(
              s"Delegate: project '$projectName' is bound to device '$device' but NebLink is not configured on this host — " +
                "no device network, no route. (DELEGATE_DEVICE_OFFLINE)"
            )
          )
        )
      case Some(neblink) =>
        neblink.peers.flatMap { peers =>
          val exact = peers.find(_.deviceId == device)
          (exact match
            case Some(p) => Right(p)
            case None => RemoteExecutor.resolvePeer(device, peers)
          ) match
            case Left(_) =>
              val online = peers.map(p => s"${p.deviceName}(${p.deviceId.take(8)})")
              IO.pure(
                Left(
                  ToolError(
                    s"project '$projectName' 的设备 '$device' 不在线（在线设备：${if online.isEmpty then "（无）" else online.mkString(", ")}）。" +
                      "任务不会落回本机执行——等该设备上线后重试。 (DELEGATE_DEVICE_OFFLINE)"
                  )
                )
              )
            case Right(peer) =>
              IO.pure(Right(WorkspaceResolution.RemoteRoute(peer, peer.deviceName, projectName, workspace)))
        }
    end match
  end routeRemote

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val task = input("task").flatMap(_.asString).getOrElse("")
    val project = input("project").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
    val description = labelOf(task)

    if task.trim.isEmpty then
      IO.pure(
        Left(
          ToolError(
            "Missing required parameter: task. Give the executor a self-contained brief — goal, constraints, absolute paths, what \"done\" looks like."
          )
        )
      )
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
          internalSpawn(project, task, description, ctx, input("executor").flatMap(_.asString))
        case Right(ExecutorRegistry.ResolvedSelection.External(defn, binPath)) =>
          externalSpawn(project, task, description, defn, binPath, ctx)
    end if
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
    }.attempt
      .map {
        case Right(Right(id)) => Some(id)
        case _ => None
      }

  /** 调用者根会话解析（回投链与面板归桶锚点）。 */
  private def callerRootOf(ctx: ToolContext, resources: AgentRuntimePort): IO[String] =
    ctx.sessionId match
      case Some(sid) =>
        resources.agentRegistry.get.map(_.get(sid).map(_.rootSessionId).filter(_.nonEmpty).getOrElse(sid))
      case None => IO.pure("")

  /** 内置执行器腿（nebflow）：actor spawn ＋ BackoffSupervisor 监督（原路径）；远端路由 = 设备投递。 */
  private def internalSpawn(
    project: Option[String],
    task: String,
    description: String,
    ctx: ToolContext,
    executorParam: Option[String] = None
  ): IO[Either[ToolError, String]] =
    resolveWorkspace(project, ctx).flatMap {
      case Left(err) => IO.pure(Left(err))
      case Right(WorkspaceResolution.Local(workspace, resolvedProject)) =>
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
                rootSessionId = rootSid,
                // 新鲜派发腿跑判定机（R6 worktree + S13 分支纪律）；Mail
                // continue 复活腿（不经此处）保持旧形态。
                judgeWorkspace = true
              )
            yield result
          case _ =>
            IO.pure(Left(ToolError("Delegate requires ActorSystem and SharedResources")))
      case Right(remote @ WorkspaceResolution.RemoteRoute(peer, deviceName, projectName, workspace)) =>
        // 显式 `executor` 选择与远端路由冲突 = fail-closed（执行器选择是 host 本地
        // 语义；对端用自己的执行器集原生执行——§A.3）。缺省（无 executor 参数）
        // 不进此分支（resolveRequested 已折叠为 Internal）。
        executorParam.map(_.trim).filter(_.nonEmpty) match
          case Some(_) =>
            IO.pure(
              Left(
                ToolError(
                  s"Delegate: explicit executor selection applies to host-local execution only — " +
                    s"project '$projectName' executes on device '$deviceName' with THAT device's own executors. " +
                    "Drop the executor parameter to dispatch to the device. (DELEGATE_EXECUTOR_REMOTE_CONFLICT)"
                )
              )
            )
          case None => dispatchRemote(remote, task, description, ctx)
    }
  end internalSpawn

  /**
   * 远端投递腿（v2.1 §A.3 变化二）：origin 设备 = 执行地。台账/任务面/回执与本地
   * 腿同构（task #N + delegate 地址面）；完成结果经 delegate-result 回投父会话
   * （`source="delegate(project:<名> @ <设备名>)"`）。spawnId 幂等 + HMAC 设备签名
   * （[[nebflow.core.delegate.DeviceDelegate]]）。
   */
  private def dispatchRemote(
    route: WorkspaceResolution.RemoteRoute,
    task: String,
    description: String,
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    ctx.sharedResources match
      case Some(resources) =>
        for
          rootSid <- callerRootOf(ctx, resources)
          taskId <- createLedgerEntry(Some(route.project), description)
          dispatchAck <- nebflow.core.delegate.DeviceDelegate.dispatch(
            resources = resources,
            peer = route.peer,
            projectName = route.project,
            workspace = route.workspace,
            task = task,
            title = description,
            parentSessionId = ctx.sessionId.getOrElse(""),
            rootSessionId = rootSid,
            taskId = taskId
          )
          result <- dispatchAck match
            case Left(err) =>
              taskId.foreach { tid =>
                nebflow.core.project.TaskLedgerStore
                  .open()
                  .appendNoteSync(
                    tid,
                    s"Device dispatch FAILED: $err",
                    from = nebflow.core.project.TaskLedgerHistory.Origins.Nebula,
                    actor = nebflow.core.project.TaskLedgerHistory.Actors.Nebula
                  )
              }
              IO.pure(Left(ToolError(s"Delegate: device dispatch failed — $err")))
            case Right(payload) =>
              val spawnId = payload.hcursor.downField("spawnId").as[String].getOrElse("")
              val ack =
                s"""Delegate task dispatched to device '${route.deviceName}' (project '${route.project}', task ${taskId
                    .map(n => s"#$n")
                    .getOrElse("n/a")}, spawnId $spawnId).
The session runs on that device with its own executors (workspace there: ${route.workspace}); its result will be delivered back to this session when it completes, and artifacts stay on the device (registered as pointers).
No queueing: if the device goes offline mid-run the result will NOT arrive — re-dispatch after it returns."""
              IO.pure(Right(ack))
        yield result
      case None => IO.pure(Left(ToolError("Delegate requires SharedResources")))
    end match
  end dispatchRemote

  /**
   * 外部执行器腿（#1225 真适配器）：ExternalSessionRunner 一条通路——子进程、
   * 流帧、终态回投、续聊、取消全部在 runner；此处只做选座、台账与回执。
   */
  private def externalSpawn(
    project: Option[String],
    task: String,
    description: String,
    defn: nebflow.core.executor.ExecutorRegistry.ExecutorDef,
    binPath: String,
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    resolveWorkspace(project, ctx).flatMap {
      case Left(err) => IO.pure(Left(err))
      // 外部执行器 + 远端路由 = 同 internalSpawn 的 fail-closed 判（执行器选择是
      // host 本地语义；对端用自己的执行器集）。
      case Right(_: WorkspaceResolution.RemoteRoute) =>
        IO.pure(
          Left(
            ToolError(
              "Delegate: explicit executor selection applies to host-local execution only — " +
                "the selected project executes on its origin device with THAT device's own executors. " +
                "Drop the executor parameter to dispatch to the device. (DELEGATE_EXECUTOR_REMOTE_CONFLICT)"
            )
          )
        )
      case Right(WorkspaceResolution.Local(workspace, resolvedProject)) =>
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
              .left
              .map(ToolError(_))
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
    rootSessionId: String,
    judgeWorkspace: Boolean = false
  ): IO[Either[ToolError, String]] =
    // 提示词来源拆分批（2026-10-08）：delegate spawn 走 **delegate 面解析路径**
    // `delegateEntry`（= delegate_report 纪律的系统提示词；节点面恒走
    // `EntityLoader.loadAgent` → `entry`，两来源机械可分）。判据门 =
    // `nebflow.core.entity.PromptFaceSplitSpec`。
    IO.blocking(nebflow.core.entity.BuiltinAgents.delegateEntry(KernelAgentName).map(_.toAgentDef).get).flatMap {
      agentDef =>
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
          rootSessionId = rootSessionId,
          judgeWorkspace = judgeWorkspace
        ).map(_.map { case (subagentId, ack) => ack + DelegateRegistry.continuationLine(subagentId) })
    }

  /**
   * Spawn one background executor instance. Returns `(subagentId, ack)` —
   * the id is the `delegate:<id>` continuation-address payload.
   * `private[nebflow]`: the Mail delegate leg's continue path reuses this
   * single spawn point (no parallel spawn path); 引擎加固批 2026-10-09（P2-5）
   * 起 boot sweep 的内部腿恢复通道（MergeQueueBootSweep.internalRevival）同点
   * 复用——仍无第二 spawn 路径，仅可见域从 tools 放宽到本工程。
   *
   * worktree-judge-fifo 批（#1224）：`judgeWorkspace = true` 仅新鲜派发腿
   * （`call`）传入——判定机跑 R6 worktree 判定 + S13 分支纪律（无论判否一律建
   * 分支 `delegate/<id>`，建权在判定机）。Mail continue 复活腿缺省 false =
   * 旧形态逐字（cwd 沿 record.cwd，不重判、不再建分支）。
   */
  private[nebflow] def spawnBackground(
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
    rootSessionId: String = "",
    judgeWorkspace: Boolean = false,
    /**
     * origin 标记（v2.1 阶段 1，2026-10-09）：**执行设备侧** delegate（受委托形态）
     * 随记录落 `delegated-by:<hostDeviceId>`（DelegateRegistry.Record.delegatedBy）。
     * 本地/受控 spawn 腿 = None（缺省，零变化）。唯一非缺省调用方 =
     * [[nebflow.core.delegate.DeviceDelegate.spawnLocalDelegate]]。
     */
    delegatedBy: Option[String] = None
  ): IO[Either[ToolError, (String, String)]] =
    val childDepth = parentDepth + 1
    val agentName = agentDef.name
    val subagentId = s"delegate-$agentName-${java.util.UUID.randomUUID().toString.take(8)}"
    val childWsSend = routeWsSend(wsSend, parentSessionId, subagentId)
    val resolvedRoot = if rootSessionId.nonEmpty then rootSessionId else parentSessionId.getOrElse(subagentId)
    // ── jev-plugins 批 #1226（内置执行器腿分配链 · 原文链条逐字）──────────
    // 启用插件 → CatalogEntry 目录 → JevGate? → JevAllocator.allocate（need_ 问题对）
    // ；未配 Jev → LlmAllocator（单次 JSON 选插件）；失败 → JevFallback fail-open。
    // 分配经 prepareNodePlugins 等价路径（PluginInjection）注入。三态语义：
    // 分配失败 = fail-open 空集（派发绝不阻塞）；解析/注入失败（选中包被封禁等）=
    // 显式拒 spawn（分配失败是节点级失败，不静默降级——节点路径同款口径）。
    // Mail 续聊复活腿同点复用（同是 spawn 触发点，机制同一）。
    PluginInjection.allocatePreparation(subject = subagentId, taskText = task).flatMap {
      case Left(err) =>
        IO.pure(Left(ToolError(s"Delegate: plugin allocation refused — $err (DELEGATE_PLUGINS_REFUSED)")))
      case Right((preparation, allocated)) =>
        val grantIo: IO[Either[String, nebflow.core.plugin.PluginMcpManager.Grant]] =
          if preparation.mcpPlugins.isEmpty then IO.pure(Right(nebflow.core.plugin.PluginMcpManager.Grant(Nil)))
          else resources.pluginMcp.acquire(subagentId, preparation.mcpPlugins)
        grantIo.flatMap {
          // pluginMcp.acquire 失败 → 本腿已启动者已被 manager 回滚停用；spawn 显式
          // 拒（零 running 残留——NodeStarter 同款口径）。
          case Left(err) => IO.pure(Left(ToolError(s"Delegate: plugin MCP startup failed — $err")))
          case Right(grant) =>
            val effectiveDef =
              if grant.serverIds.isEmpty && preparation.builtinTools.isEmpty then agentDef
              else agentDef.copy(pluginMcpServers = grant.serverIds, pluginTools = preparation.builtinTools)
            spawnWithPlugins(
              effectiveDef,
              task,
              description,
              workspace,
              project,
              taskId,
              system,
              resources,
              childDepth,
              parentRef,
              childWsSend,
              parentSessionId,
              safetyMode,
              resolvedRoot,
              judgeWorkspace,
              subagentId,
              preparation,
              allocated,
              delegatedBy
            )
        }
    }

  end spawnBackground

  /**
   * 分配后的 spawn 本体（原 spawnBackground for-推导逐字，两处注入：brief 尾部
   * `<injected-plugins>` 块 + agentDef 运行时授予；记账两行：DelegateRegistry
   * plugins 集 + ChainStore delegate-spawn 行 plugins/allocation 字段）。
   */
  private def spawnWithPlugins(
    agentDef: AgentDef,
    task: String,
    description: String,
    workspace: String,
    project: Option[String],
    taskId: Option[String],
    system: ActorSystem,
    resources: AgentRuntimePort,
    childDepth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    childWsSend: io.circe.Json => IO[Unit],
    parentSessionId: Option[String],
    safetyMode: String,
    resolvedRoot: String,
    judgeWorkspace: Boolean,
    subagentId: String,
    preparation: nebflow.core.project.NodeEngine.PluginPreparation,
    allocated: nebflow.core.project.PluginInjection.AllocatedSet,
    delegatedBy: Option[String] = None
  ): IO[Either[ToolError, (String, String)]] =
    val agentName = agentDef.name
    for
      // 判定机腿（R6/S13）：git 工作区才成腿——返回 None = 非 git（旧形态逐字）。
      binding <-
        if judgeWorkspace then
          DelegateWorkspace
            .prepare(workspace, task, subagentId)
            .handleErrorWith(e =>
              logger.warn(s"DelegateWorkspace.prepare failed (degraded to legacy spawn): ${e.getMessage}").as(None)
            )
        else IO.pure(None)
      // 判是腿的 delegate 工作座 = worktree；判否/无腿 = 原 workspace。
      effectiveWs = binding.map(_.cwd).getOrElse(workspace)
      params = NodeRunner.SpawnParams(
        agentDef = agentDef,
        resources = resources,
        sessionId = subagentId,
        sessionName = description,
        depth = childDepth,
        parentRef = parentRef,
        wsSend = childWsSend,
        // 工作座位 = 项目 workspace 或默认 general 目录（unified-delegate 批）；
        // 判是腿 = worktree（worktree-judge-fifo 批）。任务产物留在任务的工作目录里。
        projectRoot = Some(effectiveWs),
        safetyMode = safetyMode,
        rootSessionId = resolvedRoot
      )
      // brief：工作座行 + 判定机的 working-branch 申报行（🔴 均模型可见——
      // §16 待过目，最小成稿；无绑定 = 无行，旧 brief 逐字——无绑定 = 非 git
      // 或纯读腿（合法形态，非仅失败降级））+ delegate_report
      // 申报纪律行（[[BriefReportLine]]，来源拆分批上提为公开常量以便判据门钉住）。
      reportLine = BriefReportLine
      // jev-plugins 批 #1226：分配集注入 = prepareNodePlugins 等价路径（注入块
      // 追加在 brief 尾部——NodeStarter 首条消息同款位形；空分配 = 旧 brief 逐字）。
      brief =
        s"""[workspace] $effectiveWs (file tools need absolute paths; Bash cwd is not guaranteed)${binding
            .flatMap(_.briefLine)
            .map(l => s"\n$l")
            .getOrElse("")}
$reportLine

$task${if preparation.injectedBlock.isEmpty then "" else s"\n\n${preparation.injectedBlock}"}"""
      ack =
        s"""Delegate task started in the background${taskId.map(n => s" (task #$n)").getOrElse("")}.
Executor: ${agentName}; workspace: $effectiveWs${project.map(p => s" (project '$p')").getOrElse("")}${binding
            .map(b => s"; branch: ${b.branch}")
            .getOrElse("")}.
It runs in session $subagentId with no project context and no memory; the brief is its entire context.
You will be notified when it completes via a system message. Do NOT duplicate this task — wait for the result or work on something unrelated."""
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
        s"Spawned supervised delegate session: $subagentId (depth=$childDepth, workspace=$effectiveWs, project=${project.getOrElse("-")}${binding
            .map(b => s", branch=${b.branch}${b.worktree.map(w => s", worktree=$w").getOrElse("")}")
            .getOrElse("")}${if allocated.names.nonEmpty then s", plugins=${allocated.names.mkString(",")}" else ""})"
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
      _ <- IO
        .blocking(
          DelegateRegistry.put(
            DelegateRegistry.Record(
              id = subagentId,
              executor = ExecutorRegistry.DefaultId,
              cwd = effectiveWs,
              project = project,
              taskId = taskId,
              parentSessionId = parentSessionId.getOrElse(""),
              title = description,
              createdAt = System.currentTimeMillis(),
              branch = binding.map(_.branch),
              worktree = binding.flatMap(_.worktree),
              baseBranch = binding.flatMap(_.baseBranch),
              projectWorkspace = binding.map(_.baseWs),
              // 分配集记账（#1226 可查面）：分配动作发生即记（空集也是显式空）；
              // disabled/off（未发生）= None。
              plugins = if allocated.actionRan then Some(allocated.names) else None,
              // 引擎加固批 2026-10-09（P2-3 判据面）：判定机「会否改文件」裁决随
              // 记录持久化——终态只读座位诚实化的读数源。
              worktreeJudged = binding.flatMap(_.worktreeJudged),
              // origin 标记（v2.1 阶段 1）：受委托腿随记录落 delegated-by。
              delegatedBy = delegatedBy,
              // G1 编排模式初值（delegate-ui 批 2026-10-08）：内置腿 spawn 恒 single
              // （delegate 内首调 Subagent/Subtask/AgentFlow 后由记账点惰性升级——
              // 写点见 DelegateRegistry.upgradeOrchestrationMode）。Mail 续聊复活腿
              // 同点复用（本函数是唯一 spawn 记录点）⇒ 新会话同样拿到初值。
              orchestrationMode = Some(DelegateRegistry.ModeSingle)
            )
          )
        )
        .handleErrorWith(e => logger.warn(s"DelegateRegistry.put failed: ${e.getMessage}").void)
      // ⑤A 链存储：spawn 行（branch/worktree/cwd/基分支——N1 与 #1228 的数据源）；
      // #1226 增分配摘要字段（plugins 集 + 三态模式词——结构帧可查，零新建存储）。
      _ <- IO
        .blocking(
          ChainStore.appendSync(
            ChainStore.keyFor(taskId, subagentId),
            "delegate-spawn",
            DelegateWorkspace.spawnPayload(
              subagentId,
              ExecutorRegistry.DefaultId,
              project,
              binding,
              plugins = if allocated.actionRan then Some(allocated.names) else None,
              allocation = if allocated.actionRan then Some(allocated.mode) else None
            )
          )
        )
        .handleErrorWith(e => logger.warn(s"ChainStore delegate-spawn line failed: ${e.getMessage}").void)
      // N1 刀1（v3 §6/A3 宿主挂载腿）：spawn 成功即挂 delegate 作用域宿主——FlowMapStore
      // openScope（`<基工作区>/.nebflow/flow-maps/<会话id>/`）＋ NodeEngine 实例（自根：
      // rootSessionId = 会话 id，节点 out=Nebula 投 delegate 本人）。存储锚 = 基工作区
      // （`workspace` 参数——判是腿的 worktree 只作座位不作存储根，台账寿命长于 worktree）。
      // fail-soft：挂载失败只 WARN，绝不拖垮 delegate 本体（刀1 面 additive——工具面在刀2
      // 接入作用域；「查无作用域图」在刀2 有显式报错形态）。
      _ <-
        nebflow.core.project.DelegateFlowHosts
          .mount(
            delegateId = subagentId,
            project = project,
            baseWorkspace = workspace,
            engineCwd = effectiveWs,
            system = system,
            resources = resources,
            wsSend = Some(childWsSend)
          )
          .handleErrorWith(e =>
            logger
              .warn(s"delegate flow-host mount failed (delegate continues without a scope map): ${e.getMessage}")
              .as(None)
          )
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
            TaskLedgerStore
              .open()
              .appendNoteSync(
                tid,
                s"Delegate dispatched → $subagentId (executor=${ExecutorRegistry.DefaultId}, workspace=$effectiveWs${binding.map(b => s", branch=${b.branch}").getOrElse("")})",
                from = TaskLedgerHistory.Origins.Nebula,
                actor = TaskLedgerHistory.Actors.Nebula
              )
          ).attempt
            .void
        case None => IO.unit
      _ <- subagentRef ! AgentCommand.UserInput(brief, Some(adapterRef))
    yield Right((subagentId, ack))
    end for
  end spawnWithPlugins

end DelegateTool
