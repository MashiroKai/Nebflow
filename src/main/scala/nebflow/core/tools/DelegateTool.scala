package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.NebflowLogger
import nebflow.core.node.NodeRunner
import nebflow.neblink.PeerInfo

/**
 * DelegateTool — Nebula 专属的一次性执行入口（极简内核形态，2026-09-11 恢复批）。
 *
 * 语义：把「不属任何项目 ∧ 需实际执行动作 ∧ 单次」的任务交给一个**内置极简内核**
 * 子会话（`delegate-kernel-<8hex>`）执行，结果异步回根会话。
 *
 * 与旧实现的两处根本差别：
 *   1. **目标不再是「指名某个 standalone agent」**——`agent` 参数已删，目标恒为
 *      内置 `kernel` def（三处常量：`AgentCore.KernelFixedTools` /
 *      `fixedToolsFor("kernel")` / `ConvergedAgentNames`）。自克隆禁令（#28）由此
 *      从「三道运行时拒绝」升级为**结构不可表达**（无目标参数）。
 *   2. **persistent 模式退役**——`lifecycle` / `taskDescription` 参数与其
 *      `spawnPersistent` / `persistentAdapter` 实现一并删除（招牌能力 Mail 追问
 *      已无活体调用链）。剩下**唯一的** background 形态。
 *
 * 工具面（内核）：Read/Write/Edit/Glob/Grep/Bash（六件，均可带 `device=` 远端）
 * + AskUserQuestion，共七件；机制固定零配置（`KernelFixedTools` 单点来源）。
 *
 * 并发（R9，作者裁定 U4=D1）：每根会话 ≤ 4 个 Delegate 会话**在飞**——等待答复
 * 的内核同样占额度（`WaitingForUser` 也计入）。超限拒绝（自描述错误 + 在飞清单），
 * 不新造排队机制。
 *
 * 预算（R11 第 4 层 / U1=C-a）：每个内核会话 3600s wall-clock 预算，**不含人机
 * 等待期**；超时走既有 cancel 链（`reason=timeout`）。实现见
 * [[nebflow.agent.DelegateBudget]]（在 `BackoffSupervisor` 侧按 source=="delegate"
 * 装配——本文件不参与计时）。
 *
 * 工作根（R12-a / Q4）：spawn 时为内核建一个**会话级临时目录**（用完即弃），写进
 * 任务简报首行，并作为会话 `projectRoot` 传入——**绝不静默作用在网关安装目录或
 * Bash 的 `user.dir`**。调用方可在任务文本里点名绝对工作目录（以任务为准）。
 *
 * 设备（`device=`）：仅作**简报预绑定提示**（写进简报头部一行），不新增路由机制
 * ——真正的远端路由由内核自己在工具调用上带 `device=` 完成。但**无 NebLink 的
 * 实例上必须 fail-fast**（否则 `device=` 被静默忽略、命令在本机执行——这是既有
 * 静默降级坑，见 `AgentCore.executeToolInner` 的 `RemoteExecutor.current` 前置
 * 条件）。
 */
object DelegateTool extends Tool:
  private val logger = NebflowLogger(getClass)

  /** 内核 agent 定义名（作者裁定 U6=沿用 kernel）：会话 id `delegate-kernel-<8hex>`。 */
  val KernelAgentName = "kernel"

  /** 每根会话的 Delegate 并发上限（R9，作者裁定 U4=D1）。依据是事故记录而非理论：
    * 2026-08-xx 12:39 现场 7 个并发 Delegate 撞 API 限流后各自进入 retry/fallback
    * 循环（`TaskStuckWatcher.scala` 头注释在案）。 */
  val MaxConcurrentPerRoot: Int = 4

  /** Sub-agent 深度上限（与 `AgentCore.MaxDepth` 同值；内核是叶子，恒 depth=1）。 */
  val MaxDepth: Int = 5

  val name = "Delegate"

  val description =
    """Delegate a one-shot task to the minimal kernel sub-agent — a throwaway session with Read/Write/Edit/Glob/Grep/Bash (each accepts device= for another machine) plus AskUserQuestion. It has no project context, no memory and no history: the task text must be fully self-contained.

**When to use — all three must hold:**
- The target does NOT live in a mounted project workspace, needs no project AGENTS.md / review / merge chain, and will not write a git repo (those go to Task(project=...)).
- It is a single action ending in one text result (no artifact to review or archive, no multi-step plan).
- You cannot do it yourself: you have no Bash/Write/Edit.

**When NOT to use:** anything project-owned, anything whose output is a work unit needing review, or anything you can finish yourself — use Task(project=...) or do it directly.

**Parameters:**
- `task` (required): self-contained brief. Include the target device (if any), ABSOLUTE paths, the exact command/limits, and what "done" looks like. State explicit non-goals when the risk matters (e.g. "do not delete anything").
- `description` (required): short UI label (session name, sub-agent panel row, ask-card attribution).
- `device` (optional): target device name from the # Devices section. It only pre-binds the brief header — the kernel still passes device= on each tool call itself. If the device is unknown, or NebLink is not configured on this instance, the call FAILS FAST (it is never silently executed locally).

**Path semantics (hard facts, measured):**
- The file tools require ABSOLUTE paths — relative paths are rejected (no sandbox root in this session). `~` is not expanded.
- Bash's initial working directory is NOT guaranteed (it follows the gateway process, not the session). Use absolute paths or `cd` explicitly.
- Glob/Grep without an explicit root search from the gateway process cwd — pass an absolute root.
- Each kernel session gets a throwaway work root (temp directory), written on the first line of its brief. If the task names its own absolute directory, the task wins.
- Remote (device=) paths are paths on THAT machine and must be absolute.

**Limits:** at most 4 kernel sessions in flight per root session (sessions waiting for your answer count too); the 5th is rejected with the in-flight list. Each kernel has a 3600s wall-clock budget that EXCLUDES user-wait time.

**Rules:**
- Do NOT duplicate the kernel's work — work on something else and let the result arrive as a system message.
- The ack is not the result: the kernel reports back later via a `source="delegate"` message.
- **Safety**: NEVER send signals to or kill any sbt/java/nebflow process — you run inside a Nebflow instance; killing it kills you and the user's session. Process inspection with `ps` (read-only) is fine."""

  def inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "task" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Self-contained task brief for the kernel. Include the target device (if any), absolute paths, the exact commands/limits and what \"done\" looks like — the kernel starts with a clean context and no project knowledge.".asJson
        ),
        "description" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Short label for the task (shown in UI: session name, sub-agent panel, ask-card attribution).".asJson
        ),
        "device" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Optional target device name (from the # Devices section). Pre-binds the brief header only — the kernel routes each call itself via device=. Unknown device / no NebLink ⇒ fail-fast error (never a silent local run).".asJson
        )
      ),
      "required" -> io.circe.Json.arr("task".asJson, "description".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val desc = input("description").flatMap(_.asString).getOrElse("")
    val device = input("device").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
    val target = device.map(d => s"@ $d").getOrElse("")
    s"Delegate($desc $target)".trim

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 200 then result.take(197) + "..." else result

  /**
   * Wraps wsSend so that the parent session's sessionId is injected into every
   * event JSON. This lets the frontend route sub-agent events to the correct
   * window (primary or secondary) without modifying the sub-agent's internal
   * state — the sub-agent still sees sessionId=None for session-busy / error
   * logic, but its streaming events carry the parent's sessionId for display.
   */
  private def routeWsSend(
    wsSend: Option[io.circe.Json => IO[Unit]],
    parentSessionId: Option[String],
    subagentId: String
  ): io.circe.Json => IO[Unit] =
    val base = wsSend.getOrElse((_: io.circe.Json) => IO.unit)
    parentSessionId match
      case Some(sid) =>
        json =>
          // Carry the original parent sessionId as rootSessionId so nested
          // delegates (child → grandchild) still index against the top-level
          // main session on the frontend. routeWsSend wrappers compose such that
          // the wrapper created first (closest to the root session) executes
          // last, so overwriting here always yields the true root session id.
          // JsonObject.add is O(1) per field, replacing the O(n) deepMerge chain.
          json.asObject match
            case Some(obj) =>
              val builder = obj.add("rootSessionId", sid.asJson).add("sessionId", sid.asJson)
              // Preserve an existing nodeSessionId: for nested delegates, toJson
              // already stamped the grandchild's own nodeSessionId — overwriting it
              // would route grandchild events into the child's popup.
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

  /**
   * 目标解析：内置 `kernel` def（零参数面——调用方无法指定别的 agent）。
   * 定义缺失给自描述错误（含期望路径与种子来源），不静默失败。
   */
  private def resolveKernelDef(ctx: ToolContext): IO[Either[ToolError, AgentDef]] =
    ctx.agentLibrary match
      case Some(lib) =>
        lib.get(KernelAgentName).map {
          case Some(defn) => Right(defn)
          case None =>
            Left(
              ToolError(
                s"Kernel agent definition '$KernelAgentName' not found — Delegate cannot start. Expected " +
                  s"~/.nebflow/agents/$KernelAgentName/{agent.json,system.md} (seeded from " +
                  s"src/main/resources/seed/agents/$KernelAgentName/). Restore the definition and retry."
              )
            )
        }
      case None =>
        IO.pure(Left(ToolError("No agent library available — Delegate cannot resolve the kernel definition.")))
  end resolveKernelDef

  /** 设备预检（fail-fast，§5.2 静默降级坑）：无 NebLink / 设备不存在时**拒绝**
    * spawn——否则 `device=` 会被静默忽略、命令在本机执行（用户以为在远端）。
    * 与 `RemoteExecutor.execute` 同款语义：先查 peer 名册，未命中触发一次
    * discovery scan 再判（避免名册瞬时过期造成假阴性）。 */
  private def devicePrecheck(device: Option[String]): IO[Either[ToolError, Unit]] =
    device match
      case None => IO.pure(Right(()))
      case Some(target) =>
        RemoteExecutor.current match
          case None =>
            IO.pure(
              Left(
                ToolError(
                  s"""device="$target" was requested, but NebLink is NOT initialized on this instance — the kernel's device= parameter would be silently ignored and the command would run LOCALLY. Refusing to delegate. Fix: configure and sign in to NebLink, or omit the device parameter to run locally on purpose."""
                )
              )
            )
          case Some(re) =>
            re.neblinkServiceOpt match
              case None =>
                IO.pure(
                  Left(
                    ToolError(
                      s"""device="$target" was requested, but no NebLink service is wired on this instance — refusing to delegate (the command would run locally instead of on "$target")."""
                    )
                  )
                )
              case Some(svc) =>
                def hit(peers: List[PeerInfo]): Boolean = peers.exists(peerMatches(_, target))
                def unavailable(peers: List[PeerInfo]): ToolError =
                  val names = peers.map(_.deviceName)
                  val available =
                    if names.isEmpty then "peers = [] (NebLink Server not configured / no device online)"
                    else s"available devices: ${names.mkString(", ")}"
                  ToolError(
                    s"""Device "$target" not found — $available. Nothing was started. Check /api/neblink/status on both machines before retrying; if the target machine is offline, run the task locally by omitting the device parameter."""
                  )
                svc.peers.flatMap { peers =>
                  if hit(peers) then IO.pure(Right(()))
                  else
                    svc.scanNow.flatMap { refreshed =>
                      if hit(refreshed) then IO.pure(Right(())) else IO.pure(Left(unavailable(refreshed)))
                    }
                }
  end devicePrecheck

  /** 设备名匹配（与 `RemoteExecutor.resolvePeer` 同口径：等名 / deviceId 前缀 /
    * 大小写不敏感包含）。 */
  private def peerMatches(peer: PeerInfo, target: String): Boolean =
    peer.deviceName.equalsIgnoreCase(target) ||
      peer.deviceId.startsWith(target) ||
      peer.deviceName.toLowerCase.contains(target.toLowerCase)

  /** R9 并发校验（U4=D1：等待答复中的内核同样占额度）。 */
  private def concurrencyCheck(resources: SharedResources, rootSid: String): IO[Either[ToolError, Unit]] =
    resources.agentRegistry.get.map { registry =>
      val inFlight = registry.values
        .filter(r => r.kind == AgentKind.Delegate && (rootSid.isEmpty || r.rootSessionId == rootSid))
        .toList
        .sortBy(_.startedAt)
      if inFlight.size < MaxConcurrentPerRoot then Right(())
      else
        val now = System.currentTimeMillis()
        val lines = inFlight.map { r =>
          val age = if r.startedAt > 0 then s" (up ${(now - r.startedAt) / 1000}s)" else ""
          val waiting =
            if r.status == AgentStatus.WaitingForUser then
              " — WAITING for the user's answer (still counts toward the limit: ruling U4=D1)"
            else ""
          s"  - ${r.sessionId} status=${r.status}$age$waiting"
        }
        Left(
          ToolError(
            s"""Delegate concurrency limit reached: $MaxConcurrentPerRoot kernel sessions are already in flight under this root session (limit $MaxConcurrentPerRoot).
In flight:
${lines.mkString("\n")}
Wait for one to finish, or cancel one with AgentControl(cancel) before delegating again. Do not retry blindly — a blind retry is rejected the same way."""
          )
        )
    }

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val task = input("task").flatMap(_.asString).getOrElse("")
    val description = input("description").flatMap(_.asString).map(_.trim).filter(_.nonEmpty).getOrElse("subtask")
    val device = input("device").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)

    if task.trim.isEmpty then
      IO.pure(
        Left(
          ToolError(
            """Missing required parameter: task. Give the kernel a self-contained brief (target device, absolute paths, exact commands, what "done" looks like) — it has no conversation history to fall back on."""
          )
        )
      )
    else if ctx.depth >= MaxDepth then
      IO.pure(Left(ToolError(s"Maximum sub-agent depth ($MaxDepth) reached. Cannot delegate further.")))
    else
      resolveKernelDef(ctx).flatMap {
        case Left(err) => IO.pure(Left(err))
        case Right(kernelDef) =>
          devicePrecheck(device).flatMap {
            case Left(err) => IO.pure(Left(err))
            case Right(_)  => spawnKernel(kernelDef, task, description, device, ctx)
          }
      }
  end call

  // ============================================================
  // Background (唯一的 mode): return immediately, deliver via ExternalEvent
  // ============================================================

  private def spawnKernel(
    kernelDef: AgentDef,
    task: String,
    description: String,
    device: Option[String],
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    (ctx.actorSystem, ctx.sharedResources) match
      case (Some(system), Some(resources)) =>
        val safetyModeIO = (ctx.sessionStore, ctx.sessionId) match
          case (Some(store), Some(sid)) => store.getSafetyMode(sid)
          case _                        => IO.pure("confirm-edits")
        // 调用方根会话（权限桶锚点 / 前端路由锚点）
        val callerRootIO = (ctx.sessionId) match
          case Some(sid) =>
            resources.agentRegistry.get.map(_.get(sid).map(_.rootSessionId).filter(_.nonEmpty).getOrElse(sid))
          case None => IO.pure("")
        for
          safetyMode <- safetyModeIO
          rootSid <- callerRootIO
          quota <- concurrencyCheck(resources, rootSid)
          result <- quota match
            case Left(err) => IO.pure(Left(err))
            case Right(_) =>
              IO.blocking(java.nio.file.Files.createTempDirectory("nb-kernel-")).flatMap { workRoot =>
                spawnBackground(
                  agentDef = kernelDef,
                  task = task,
                  description = description,
                  device = device,
                  workRoot = workRoot.toString,
                  system = system,
                  resources = resources,
                  parentDepth = ctx.depth,
                  parentRef = ctx.agentActorRef,
                  wsSend = ctx.wsSend,
                  parentSessionId = ctx.sessionId,
                  safetyMode = safetyMode,
                  rootSessionId = rootSid
                )
              }
        yield result
        end for
      case _ =>
        IO.pure(Left(ToolError("Delegate requires ActorSystem and SharedResources")))
  end spawnKernel

  private def spawnBackground(
    agentDef: AgentDef,
    task: String,
    description: String,
    device: Option[String],
    workRoot: String,
    system: ActorSystem,
    resources: SharedResources,
    parentDepth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    wsSend: Option[io.circe.Json => IO[Unit]],
    parentSessionId: Option[String] = None,
    safetyMode: String = "confirm-edits",
    rootSessionId: String = ""
  ): IO[Either[ToolError, String]] =
    val childDepth = parentDepth + 1
    val agentName = agentDef.name
    val subagentId = s"delegate-${agentName}-${java.util.UUID.randomUUID().toString.take(8)}"
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
      // R12-a / Q4：项目根换成**本会话的一次性工作根**——不让内核静默作用在
      // 网关安装目录 / Bash 的 user.dir 上（旧实现传的是调用方的 projectRoot）。
      projectRoot = Some(workRoot),
      safetyMode = safetyMode,
      rootSessionId = resolvedRoot
    )
    val brief =
      val deviceLine = device.map(d => s"\n[target device] $d — pass device=\"$d\" on every tool call that must run there (paths on that machine must be absolute).").getOrElse("")
      s"""[session work root] $workRoot (throwaway; file tools need absolute paths, Bash cwd is not guaranteed)$deviceLine

$task"""
    val ack =
      s"""Kernel task '$description' started in the background (device: ${device.getOrElse("local")}).
It runs in session $subagentId with the minimal kernel toolset (Read/Write/Edit/Glob/Grep/Bash + AskUserQuestion), no project context and no memory.
You will be notified when it completes via a system message. Do NOT duplicate this task — wait for the result or work on something unrelated."""
    for
      subagentRef <- NodeRunner.spawnAgentActor(system, params)
      // BackoffSupervisor is the background adapter — auto-restarts on crash,
      // handles AgentEvent.Cancelled (AgentControl cancel path + DelegateBudget
      // 的 reason="timeout" 超时链), and (source=="delegate") owns the 3600s
      // wall-clock budget lifecycle (DelegateBudget).
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
        s"Spawned supervised kernel session: $subagentId (depth=$childDepth, workRoot=$workRoot, device=${device.getOrElse("local")})"
      )
      _ <- NodeRunner.registerAgent(
        resources,
        id = subagentId,
        ref = subagentRef,
        kind = AgentKind.Delegate,
        rootSessionId = resolvedRoot,
        parentRef = parentRef,
        // AgentControl：startedAt/lastActivityMs 驱动 list 的 up/idle 列与卡死
        // 判定；supervisorRef 是 cancel 的直达通道（BackoffSupervisor 处理
        // AgentEvent.Cancelled）。
        startedAt = System.currentTimeMillis(),
        lastActivityMs = System.currentTimeMillis(),
        supervisorRef = Some(adapterRef),
        parentSessionId = parentSessionId.getOrElse("")
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
      _ <- subagentRef ! AgentCommand.UserInput(brief, Some(adapterRef))
    yield Right(ack)

end DelegateTool
