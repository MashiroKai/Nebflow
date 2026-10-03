package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.AgentRuntimePort
import nebflow.core.node.NodeRunner
import nebflow.shared.{NebflowLogger, SubAgentTask}

// 严格DAG第⑥步第三批A裁定(dwfq-5c7a31ea-1,M1/M4/M6):定位器参数窄化,SubAgentTask 已下沉 shared
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
 * **设备面（2026-09-14 作者裁定 U1/U2：schema 层结构性摘除）**：本工具**没有**
 * `device` 参数——它是**本地编排件**，永远在本机 spawn 内核子会话，不存在把整条
 * `Delegate` 调用投到对端的路径（旧的 `device` 处理链已随 schema 一并删除）。
 * 远端执行只发生在**内核自己的六件工具**上（`Read/Write/Edit/Glob/Grep/Bash` 带
 * `device=`，集合单点来源 = `RemoteExecutor.remoteableTools`）；委派任务要作用在
 * 对端时，目标设备写进**任务简报文本**，由内核逐条工具调用自行带 `device=`。
 */
object DelegateTool extends Tool:
  private val logger = NebflowLogger(getClass)

  /** 内核 agent 定义名（作者裁定 U6=沿用 kernel）：会话 id `delegate-kernel-<8hex>`。 */
  val KernelAgentName = "kernel"

  /** Concurrency cap RETIRED (author decision 2026-09-26: "at most 4 kernel sessions -
    * no concurrency limit"). There is deliberately NO per-root-session cap on kernel
    * spawns; in-flight kernels run concurrently. Knowledge retention (the gate was not
    * removed out of ignorance): the old cap (R9, ruling U4=D1, limit 4 per root
    * session) guarded against a real incident - on 2026-08-xx 12:39 seven concurrent
    * Delegates hit the API rate limit and each fell into retry/fallback loops
    * (`TaskStuckWatcher.scala` header note on record). Cap removed by author decision
    * 2026-09-26; the rate-limit risk is accepted by the author (own API key).
    * Reintroducing any cap needs a fresh author ruling (DelegateToolSpec pins the
    * retirement at the source face). */

  /** Sub-agent 深度上限（与 `AgentCore.MaxDepth` 同值；内核是叶子，恒 depth=1）。 */
  val MaxDepth: Int = 5

  val name = "Delegate"

  val description =
    """Delegate a one-shot task to the minimal kernel sub-agent — a throwaway session with Read/Write/Edit/Glob/Grep/Bash (each accepts device= for another machine) plus AskUserQuestion. It has no project context, no memory and no history: the task text must be fully self-contained.

**When to use — all three must hold:**
- The target does NOT live in a mounted project workspace, needs no project AGENTS.md / review / merge chain, and will not write a git repo (those go to Mail(address="project:<name>", message=...)).
- It is a single action ending in one text result (no artifact to review or archive, no multi-step plan).
- You cannot do it yourself: you have no Bash/Write/Edit.

**When NOT to use:** anything project-owned, anything whose output is a work unit needing review, or anything you can finish yourself — use Mail(address="project:<name>", message=...) or do it directly.

**Capability boundary (hard):** the kernel has NO plugin surface and NO project context — it cannot mount plugins, read project AGENTS.md, or see the Plugin Catalog. For any deliverable-production task (PPT/decks, cards, diagrams, websites, papers, research reports) use Mail(address="project:<name>", message=...) instead, and never ask the kernel to pick a technology route or to reject an existing capability route.

**Parameters:**
- `task` (required): self-contained brief. Include the target device (if any), ABSOLUTE paths, the exact command/limits, and what "done" looks like. State explicit non-goals when the risk matters (e.g. "do not delete anything").
- `description` (required): short UI label (session name, sub-agent panel row, ask-card attribution).

**Path semantics (hard facts, measured):**
- The file tools require ABSOLUTE paths — relative paths are rejected (no sandbox root in this session). `~` is not expanded.
- Bash's initial working directory is NOT guaranteed (it follows the gateway process, not the session). Use absolute paths or `cd` explicitly.
- Glob/Grep without an explicit root search from the gateway process cwd — pass an absolute root.
- Each kernel session gets a throwaway work root (temp directory), written on the first line of its brief. If the task names its own absolute directory, the task wins.
- Remote (device=) paths are paths on THAT machine and must be absolute.

**Limits:** No hard concurrency limit — in-flight kernels run concurrently. Each kernel has a 3600s wall-clock budget that EXCLUDES user-wait time.

**Result and continuation address:** the tool result is an ACK, not the kernel's answer — it carries the instance's continuation address `kernel:<id>`. The kernel's actual result is delivered back to your session as a system message when it finishes. To send the instance more input while it is live, use `Mail(address="kernel:<id>", message=...)` (Nebula-exclusive); a `kernel:<id>` whose instance already finished is an explicit MAIL_KERNEL_NOT_LIVE error — start a new one with Delegate. Mail can no longer START a kernel: spawning is Delegate-only.

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
        )
      ),
      "required" -> io.circe.Json.arr("task".asJson, "description".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val desc = input("description").flatMap(_.asString).getOrElse("")
    s"Delegate($desc)"

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
   *
   * builtin-def 批 (2026-10-03, 作者令①「唯一标准源就是代码」+②「Delegate 直接触发
   * kernel」)：def 解析**只在代码**——`BuiltinAgents.entry("kernel")`（代码级定义，
   * 含内嵌 system prompt；模型链经 AgentEntry.toAgentDef 的 SchemePolicy 单点解析，
   * 与磁盘 def 时代的 loadFromDir 同一解析面）。旧的「磁盘 def → runtime mirror
   * system.md → classpath seed → embedded default」四级 fail-safe 链随种子面退役
   * 一并删除：代码即权威，不存在缺件态（"always spawnable" 语义保留——解析不读盘、
   * 不抛、绝不因任何盘上状态拒绝起 kernel）。
   */
  private[tools] def resolveKernelDef(ctx: ToolContext): IO[Either[ToolError, AgentDef]] =
    IO.blocking {
      Right(nebflow.core.entity.BuiltinAgents.entry(KernelAgentName).map(_.toAgentDef).get)
    }
  end resolveKernelDef

  // Concurrency check RETIRED (author decision 2026-09-26: no hard concurrency limit).
  // The former R9 gate trio - the in-flight snapshot type, the pure rejection
  // criterion and the registry-count read (limit 4 per root session, waiting sessions
  // counted) - was removed from this single point, which both the Delegate tool leg and
  // the Mail->kernel leg shared. The 2026-08 rate-limit incident note stays on the
  // object-level comment at the top of this object (knowledge kept, gate gone).

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val task = input("task").flatMap(_.asString).getOrElse("")
    val description = input("description").flatMap(_.asString).map(_.trim).filter(_.nonEmpty).getOrElse("subtask")

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
          spawnKernel(kernelDef, task, description, ctx)
      }
    end if
  end call

  // ============================================================
  // Background (唯一的 mode): return immediately, deliver via ExternalEvent
  // ============================================================

  private def spawnKernel(
    kernelDef: AgentDef,
    task: String,
    description: String,
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    (ctx.actorSystem, ctx.sharedResources) match
      case (Some(system), Some(resources)) =>
        // 调用方根会话（权限桶锚点 / 前端路由锚点）
        val callerRootIO = (ctx.sessionId) match
          case Some(sid) =>
            resources.agentRegistry.get.map(_.get(sid).map(_.rootSessionId).filter(_.nonEmpty).getOrElse(sid))
          case None => IO.pure("")
        for
          rootSid <- callerRootIO
          // permshield S1（2026-09-13）：子代理继承的档位 = 应用级全局持久档位
          // （`SharedResources.effectiveSafetyMode`，唯一入口）。既不读
          // `store.getSafetyMode` 的盘上遗留值（那会让全局 confirm-edits 时 fork 出的
          // 子代理继承盘上 auto-all），也不再有"本会话覆盖"可继承——档位对全应用一致。
          safetyMode <- resources.effectiveSafetyMode.map(nebflow.core.SafetyMode.toString)
          // No concurrency gate (retired 2026-09-26, author decision: no hard limit) -
          // every spawn proceeds; see the retirement note at the top of this object.
          result <-
            IO.blocking(java.nio.file.Files.createTempDirectory("nb-kernel-")).flatMap { workRoot =>
                spawnBackground(
                  agentDef = kernelDef,
                  task = task,
                  description = description,
                  workRoot = workRoot.toString,
                  system = system,
                  resources = resources,
                  parentDepth = ctx.depth,
                  parentRef = ctx.agentActorRef,
                  wsSend = ctx.wsSend,
                  parentSessionId = ctx.sessionId,
                  safetyMode = safetyMode,
                  rootSessionId = rootSid
                ).map(_.map {
                    case (subagentId, ack) =>
                      // builtin-def 批（2026-10-03 作者令②「Delegate 直接触发 kernel，
                      // 返回地址」）：回执携带续聊地址——后续就本实例的通信一律走
                      // Mail(address="kernel:<id>")（Mail 降级为通信原语后的唯一内核腿）。
                      ack + continuationAddressLine(subagentId)
                  })
              }
        yield result
        end for
      case _ =>
        IO.pure(Left(ToolError("Delegate requires ActorSystem and SharedResources")))
  end spawnKernel

  /** The continuation-address suffix every kernel dispatch receipt carries
    * (builtin-def batch 2026-10-03, author directive ②: Delegate returns the
    * address; Mail(address="kernel:<id>") is the ONLY kernel communication leg). */
  private[tools] def continuationAddressLine(subagentId: String): String =
    s"\nContinuation address: \"kernel:$subagentId\" — later communication about THIS instance goes through Mail(address=\"kernel:$subagentId\"). It runs until its result is delivered back (source=\"delegate\"); mailing it injects at its next turn boundary."

  /** Spawn one background kernel instance. Returns `(subagentId, ack)` — the id is the
    * continuation-address payload: the caller stamps it into the receipt
    * ([[continuationAddressLine]] for the Delegate tool result; MailTool stamped its
    * own copy until the Mail `kernel` spawn leg retired in the same batch). */
  private def spawnBackground(
    agentDef: AgentDef,
    task: String,
    description: String,
    workRoot: String,
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
      s"""[session work root] $workRoot (throwaway; file tools need absolute paths, Bash cwd is not guaranteed)

$task"""
    val ack =
      s"""Kernel task '$description' started in the background.
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
        s"Spawned supervised kernel session: $subagentId (depth=$childDepth, workRoot=$workRoot)"
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
    yield Right((subagentId, ack))

end DelegateTool
