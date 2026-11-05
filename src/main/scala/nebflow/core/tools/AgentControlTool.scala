package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.actor.*
import nebflow.core.AgentRuntimePort
import nebflow.core.delegate.CancelCascade
import nebflow.shared.Defaults

import scala.concurrent.duration.*

// 严格DAG第⑥步第三批A裁定(dwfq-5c7a31ea-1,M1/M6):定位器参数窄化
/**
 * AgentControl — Nebula 专用后台 agent 管控（spec v1.1，2026-08-19）。
 *
 * 起因：派出的 sub-agent 6 小时无产出只能干等——Nebula 缺主动「查看/取消/重启」
 * 手段，TaskStuckWatcher 只覆盖 Processing 卡死一类。
 *
 * 四操作：
 *  - list    全部后台 agent 表格（kind/status/stuck?/phase/up/idle/retries/task）
 *  - status  单个详情卡片 + 孤儿任务检测（registry 无记录但 task=running）
 *  - cancel  终止任务：supervisor 路径（AgentEvent.Cancelled → notifyParentAndStop
 *            "cancelled"，barrier 正确释放）/ 降级 Stop 路径（无 supervisor 的
 *            bridge watch 兜底 / 旧记录）
 *  - restart 断点重启：Stop → BackoffSupervisor Terminated → 恢复持久化消息续跑
 *            （复用 #317 crash resume，零新机制），消耗 supervisor 熔断额度
 *
 * 安全边界（spec §4 矩阵，严格）：
 *  - cancel 白名单：Delegate / SubTask；Flow 指引 cancelFlow；
 *    Team 条件放行：直接父 Manager / root 全局（supervision trio Block 2 §C3）；
 *    杀 Manager 需 confirm+reason 双确认门；Root/自身拒绝（自杀守卫）
 *  - restart 白名单：Delegate(ephemeral) / SubTask；persistent Delegate 无 supervisor
 *    拒绝（提示 cancel + 重新 Delegate）
 *  - rootSessionId 必须与调用者同桶（P2 权限原则）
 */
object AgentControlTool extends Tool:

  private val logger = nebflow.shared.NebflowLogger.forName("nebflow.tools.agent-control")

  val name = "AgentControl"

  val description =
    """Inspect and control background agent sessions — the sub-agents spawned via Delegate/SubTask, ephemeral Mail runners, and other live sessions in this instance.

Actions:
- **list**: table of all live background agents (kind, status, stuck?, phase, uptime, idle time, retry count, task). Use this FIRST when a sub-agent is silent or you suspect it is stuck — "stuck?" marks Processing agents that either produced no agent-side event (LLM chunk / tool batch) for >10min, or have a single tool call running for >10min inside an unfinished turn (same judgement as the automatic watcher — process CPU is irrelevant to it). "phase" shows the in-flight tool call (name + elapsed), which is what tells you whether the agent is executing a tool or has gone silent.
- **status**: full detail for one agent (pass sessionId from list): state, timings, in-flight tool phase, task prompt excerpt, retry count, last error. Also detects orphan task files (actor gone but task still marked running).
- **cancel**: terminate an agent's current task. The parent session receives a "cancelled" notification and any wait barrier is released — nobody waits forever. Allowed kinds: Delegate, SubTask, Project node/dispatcher sessions (node-*/dispatcher-*, settles via the session bridge). With scope="bgTask" + jobId: cancel ONE background task inside the target session instead of the session (local shell job or remote device job) — the session and its other tasks keep running.
- **restart**: kill the stuck turn and resume from the last persisted checkpoint (same mechanism as crash recovery — completed work is kept). Allowed kinds: Delegate (ephemeral) and SubTask (both consume the supervisor's restart budget, 2 per 5min; exceeding it fails the task).

Safety rules: you cannot cancel/restart yourself, Root/plan sessions (self-preservation guard), or dag-* Flow workers (flow cancellation goes through cancelFlow / RunningFlowRegistry). Project node/dispatcher sessions (node-*/dispatcher-*) support cancel only (single-shot — no restart; cancel the dispatcher and re-dispatch via Delegate(task=..., project=<name>) — Mail dispatches nothing). Delegate/SubTask sessions under your own root session are always controllable. Team targets are not manageable at all: the team feature (team members, team Managers and their scope) was retired on 2026-11-04, so the team scope of this tool is gone with it — a `kind: Team` target is refused explicitly rather than silently downgraded.

When to use:
- A delegated task has been silent far longer than expected → list, then status the suspicious session.
- Confirmed useless/stuck task → cancel (parent gets notified).
- Likely stuck but the task still has value → restart (resumes from checkpoint)."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "action" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "enum" -> io.circe.Json.arr("list".asJson, "status".asJson, "cancel".asJson, "restart".asJson),
          "description" -> "list=all background agents; status=one agent detail; cancel=terminate task; restart=resume from checkpoint".asJson
        ),
        "sessionId" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Required for status/cancel/restart. The AgentRecord sessionId (= subagentId = taskId), shown in list output.".asJson
        ),
        "reason" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Optional for cancel/restart. Recorded into the task file (lastError) and the parent notification for traceability. REQUIRED (non-empty) when cancelling/restarting a team Manager with confirm=true.".asJson
        ),
        "scope" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "enum" -> io.circe.Json.arr("session".asJson, "bgTask".asJson),
          "description" -> "Optional for cancel. Omit or \"session\" = the existing whole-session cancel (unchanged). \"bgTask\" = precise single-task cancel: kill ONLY the background task identified by jobId in the target session (local shell job or remote device job), leaving the session and its other tasks untouched. Requires jobId; the task must belong to the target session (own sessionId or its rootSessionId).".asJson
        ),
        "jobId" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Required when scope=\"bgTask\": the background task id (jobId) to cancel — as shown in the bg tasks panel / background_task_id outputs.".asJson
        ),
        "confirm" -> io.circe.Json.obj(
          "type" -> "boolean".asJson,
          "description" -> "Strong confirmation for killing a team MANAGER (cancel/restart). Must be true AND accompanied by a non-empty reason — a killed Manager leaves its members without a coordinator (members keep running and remain manageable by Nebula). Not needed for regular team members.".asJson
        )
      ),
      "required" -> io.circe.Json.arr("action".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val action = input("action").flatMap(_.asString).getOrElse("?")
    val sid = input("sessionId").flatMap(_.asString).getOrElse("")
    if sid.nonEmpty then s"AgentControl($action ${sid.take(20)})" else s"AgentControl($action)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 200 then result.take(197) + "..." else result

  // ── kind policy（§4 矩阵）──────────────────────────────────

  /**
   * 无条件 cancelable kinds。WS cancelAgent handler 复用此白名单 ⇒ 管理面板对非
   * 白名单 kind 保持只读，权限门只在工具路径。
   *
   * 团队功能删除批（team-feature-deletion-20261104）阶段 3：`Team` 原先「条件放行」
   * （直接父 / root 桶 → 放行，并配 Manager 强确认门），该分支随 team scope 整段
   * 删除（作者裁定）——`Team` 现由 [[kindRejection]] 显式拒绝，也不再进本白名单。
   */
  val CancelableKinds: Set[AgentKind] = Set(AgentKind.Delegate, AgentKind.SubTask)
  private val RestartableKinds: Set[AgentKind] = Set(AgentKind.Delegate, AgentKind.SubTask)

  /**
   * 新 Project 系统的 Flow 会话（Project 节点 node-* / 任务分发器 dispatcher-*）。
   * 旧 flow 系统的 dag-* 会话不在此列（其取消走 cancelFlow / RunningFlowRegistry）。
   * 前缀权威定义：NodeEngine.SessionPrefix / ProjectActor.DispatcherSessionPrefix。
   */
  def isProjectFlowSession(sessionId: String): Boolean =
    sessionId.startsWith(nebflow.core.project.NodeEngine.SessionPrefix) ||
      sessionId.startsWith(nebflow.core.project.ProjectActor.DispatcherSessionPrefix)

  /**
   * 面板/工具共用的 cancel 白名单：无条件 kind 白名单 + 新 Project flow 会话
   * （须有 supervisor 取消通道——观察桥的 Cancelled 分支；dag- 旧 flow 会话
   * 无此通道，保持只读）。
   */
  def cancelable(rec: AgentRecord): Boolean =
    CancelableKinds.contains(rec.kind) ||
      (rec.kind == AgentKind.Flow && isProjectFlowSession(rec.sessionId) && rec.supervisorRef.isDefined)

  /**
   * 外部腿（#1225）的根桶守卫：外部 delegate 无 agentRegistry 行，走不了
   * withGuardedRecord 的同桶判定——这里只保留「调用者必须是 root 桶」一条
   * （Nebula / 无注册记录的 legacy 形态放行；非 root 调用者拒绝）。
   */
  private def withExternalRootGuard(
    resources: AgentRuntimePort,
    ctx: ToolContext
  )(
    body: => IO[Either[ToolError, String]]
  ): IO[Either[ToolError, String]] =
    val callerSessionId = ctx.sessionId.getOrElse("")
    if callerSessionId.isEmpty then body
    else
      resources.agentRegistry.get.flatMap { registry =>
        val rootBucket = callerIsRootBucket(registry, callerSessionId)
        if rootBucket then body
        else
          IO.pure(
            Left(
              ToolError(
                s"Permission denied: external delegate management is root-scope — session '$callerSessionId' is not a root-bucket caller."
              )
            )
          )
      }

  end withExternalRootGuard

  private def kindRejection(
    rec: AgentRecord,
    action: String,
    callerIsDirectParent: Boolean = false,
    callerIsRoot: Boolean = false
  ): Option[String] =
    rec.kind match
      case AgentKind.Flow =>
        if action == "cancel" && isProjectFlowSession(rec.sessionId) && rec.supervisorRef.isDefined then
          None // 观察桥 Cancelled 通道：node → engine cancelNode（结果不投递）；dispatcher → 清 registry + 停 agent
        else if isProjectFlowSession(rec.sessionId) then
          Some(
            s"Session '${rec.sessionId}' is a single-shot Project session — restart is not supported. " +
              "Cancel it and re-trigger the work (Mail(address=\"project:<name>\", message=...) for the dispatcher / NodeEdit for nodes)."
          )
        else
          Some(
            "Flow workers follow the DAG lifecycle — cancelling one directly would break the flow state machine. " +
              "Use flow-level cancellation instead (cancelFlow(instanceId) / RunningFlowRegistry; NodeCancel for Project nodes)."
          )
      case AgentKind.Team =>
        // 团队功能删除批（team-feature-deletion-20261104）阶段 3：本条原为「条件
        // 放行」——直接父（团队 Manager）/ root 桶调用者放行，其余只读，并配
        // Manager 强确认门与审计行。作者裁定 team scope **整段删除**，故条件放行
        // 与其后的 confirm+reason 审计腿一并删除；`AgentKind.Team` 目标改为显式
        // 拒绝（fail-closed：不留「静默变成不可管理」的模糊态，枚举值本身保留 ——
        // 去留仍待作者裁定）。
        Some(
          "Team targets are not manageable: the team feature was retired on 2026-11-04 " +
            "(team members / team Managers / team scope are gone, so no caller has a team scope any more). " +
            "Manage background sessions (Delegate / SubTask / Project node / dispatcher) instead."
        )
      case AgentKind.Root =>
        Some("Root sessions cannot be managed with this tool (self-preservation guard).")
      case k =>
        val allowed = if action == "restart" then RestartableKinds else CancelableKinds
        if allowed.contains(k) then None
        else if action == "restart" then
          Some(
            s"$k sessions have no supervisor/checkpoint carrier — restart is not supported. Cancel it and spawn a new one."
          )
        else None

  // ── 渲染 helpers ──────────────────────────────────────────

  /**
   * Block 1 (supervision trio §B3): walk the parentSessionId chain from
   * `from` upward (≤ maxDepth hops, cycle-safe by depth bound); true iff an
   * anchor session is reached. Pure — unit-testable.
   */
  private def chainReaches(
    registry: Map[String, AgentRecord],
    from: String,
    anchors: Set[String],
    maxDepth: Int
  ): Boolean =
    def walk(sid: String, depth: Int): Boolean =
      if depth > maxDepth then false
      else
        val parentOpt = registry.get(sid).map(_.parentSessionId).filter(_.nonEmpty)
        parentOpt match
          case Some(p) => anchors.contains(p) || walk(p, depth + 1)
          case None => false
    walk(from, 0)

  // ═══ 团队功能删除批（team-feature-deletion-20261104）阶段 3：team scope 退役
  // `managerAnchors`（Block 1 §B3 的 Manager 作用域锚点集 = 调用者自己 + 本团队
  // 全部 sessionId）在此删除 —— 作者裁定「AgentControl 的 team scope 整段删除，
  // 只留 session / bgTask」。它唯一的两个调用点（cancel/restart 的子树判定、
  // list 的可见范围）见下方各自的同位墓碑注。

  /**
   * Block 1 (§B3): a root-bucket caller's rootSessionId IS its own session id
   * (Nebula / root agents). All other callers (team Managers) are scoped to
   * their subtree instead of the bucket. A caller with NO registry record is
   * treated as root-bucket: only Nebula (Root) and team Managers hold the
   * tool, both are registered whenever they act — an unregistered caller is
   * a legacy/test shape and keeps the legacy global semantics.
   */
  private def callerIsRootBucket(registry: Map[String, AgentRecord], callerSid: String): Boolean =
    callerSid.isEmpty || registry.get(callerSid).fold(true)(_.rootSessionId == callerSid)

  private def fmtDuration(ms: Long): String =
    if ms < 0 then "-"
    else if ms < 60_000 then s"${ms / 1000}s"
    else if ms < 3_600_000 then s"${ms / 60_000}m${(ms % 60_000) / 1000}s"
    else s"${ms / 3_600_000}h${(ms % 3_600_000) / 60_000}m"

  private def fmtMillis(ms: Long): String = if ms > 0 then fmtDuration(ms) else "-"

  /** "delegate-Explorer-a1b2c3d4" → "Explorer"（sessionId 内嵌 agent 名，零 IO）。 */
  private def agentNameFromSessionId(sid: String): String =
    sid.split("-").toList match
      case _ :: rest if rest.nonEmpty =>
        val name = rest.dropRight(1).filter(_.nonEmpty).mkString("-")
        if name.nonEmpty then name else sid
      case _ => sid

  /**
   * 2026-09-10 卡死判据换轴：判定收敛到 TaskStuckWatcher.assess（唯一事实源）——
   * agent 侧事件停滞 ∪ 工具相位超时，二者都不引用进程 CPU。旧实现只读
   * lastActivityMs，而该戳曾被 BashTool 活动桥用进程 CPU 微动刷新 → stuck? 列恒
   * "no"（本次事故 2h56m 零可见性）。
   */
  private def stuckAssessment(rec: AgentRecord, now: Long): Option[(Long, String)] =
    if rec.status == AgentStatus.Processing then nebflow.core.processor.TaskStuckWatcher.assess(rec, now)
    else None

  /**
   * 2026-09-10 换轴（人可见性）：当前工具相位文本——工具名 + 已持续时长
   * （如 "Bash 3m20s"）。无在飞工具 → "-"。空转的「有工具在跑」是人判断
   * 「进程占死 vs agent 停摆」的第一手信息（事故现场唯一缺口）。
   */
  private def toolPhaseLabel(rec: AgentRecord, now: Long): String =
    (rec.currentToolName, rec.currentToolStartedAt) match
      case (Some(name), startedAt) if startedAt > 0 => s"$name ${fmtDuration(now - startedAt)}"
      case _ => "-"

  // ── actions ───────────────────────────────────────────────

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    ctx.sharedResources match
      case None => IO.pure(Left(ToolError("AgentControl requires SharedResources (no live runtime in this context)")))
      case Some(resources) =>
        val action = input("action").flatMap(_.asString).getOrElse("")
        val sessionId = input("sessionId").flatMap(_.asString).getOrElse("")
        val reason = input("reason").flatMap(_.asString).getOrElse("")

        action match
          case "list" => doList(resources, ctx)
          case "status" =>
            if sessionId.isEmpty then IO.pure(Left(ToolError("status requires sessionId (run list first to get one)")))
            else doStatus(resources, ctx, sessionId)
          case "cancel" =>
            if sessionId.isEmpty then IO.pure(Left(ToolError("cancel requires sessionId (run list first to get one)")))
            else
              val scope = input("scope").flatMap(_.asString).getOrElse("").trim
              if scope == "bgTask" then
                // P2 单任务取消 agent 面（设计稿 §4.1-b）：scope=bgTask + jobId =
                // 只杀目标会话名下的一条后台任务（local 直调 / remote 设备通道），
                // 会话与其余任务逐字不动。护栏整体复用 withGuardedRecord（查无 /
                // kind 门 / 自杀守卫 / 同桶-子树 scope），其上再校验任务归属。
                val bgJobId = input("jobId").flatMap(_.asString).getOrElse("").trim
                if bgJobId.isEmpty then
                  IO.pure(Left(ToolError("scope=\"bgTask\" requires jobId (the background task id to cancel)")))
                else
                  withGuardedRecord(resources, ctx, sessionId, "cancel", confirm = false, reason)((rec, _) =>
                    doCancelBgTask(resources, ctx, rec, bgJobId, reason)
                  )
              else if sessionId.startsWith(nebflow.core.executor.ExternalSessionRunner.SessionPrefix) then
                // 外部腿（external-executors 批 #1225）：外部 delegate 无 actor /
                // agentRegistry 行——取消直达进程树（原书：ProcessTree.killProcessTree）。
                // 权限 = root 桶调用者（与 delegate 派发面同族）。
                withExternalRootGuard(resources, ctx) {
                  nebflow.core.executor.ExternalSessionRunner
                    .cancel(sessionId, reason, by = callerLabel(ctx))
                    .map(_.left.map(ToolError(_)))
                }
              else
                val confirm = input("confirm").flatMap(_.asBoolean).getOrElse(false)
                withGuardedRecord(resources, ctx, sessionId, "cancel", confirm, reason)((rec, _) =>
                  // notifyWs = 调用者 WS 出口：node-* 会话取消补发面板终态帧
                  // （Sub-Agents 面板取消实时刷新修复）。
                  doCancel(resources, rec, reason, by = callerLabel(ctx), notifyWs = ctx.wsSend)
                )
          case "restart" =>
            if sessionId.isEmpty then IO.pure(Left(ToolError("restart requires sessionId (run list first to get one)")))
            else
              val confirm = input("confirm").flatMap(_.asBoolean).getOrElse(false)
              withGuardedRecord(resources, ctx, sessionId, "restart", confirm, reason)((rec, _) =>
                doRestart(resources, ctx, rec, reason)
              )
          case other =>
            IO.pure(
              Left(ToolError(s"Unknown action '$other'. Supported: list, status, cancel, restart."))
            )

        end match

  /**
   * 统一守卫（§4）：查无 / kind 白名单 / 自杀守卫 / rootSessionId 同桶。
   * Block 1（supervision trio §B3）：root 桶调用者（Nebula，rootSessionId ==
   * 自身 sid）维持同桶=全局；非 root 调用者（team Manager，唯一非 root 被
   * 授权者）从「同桶」收紧为「子树」——同桶会放行它管**别的 team** 的子代
   * （所有挂载 team 共享挂载 root 的桶）。
   * Block 2（§C3）：Team 目标过 kind 门后，强确认门（Manager 目标需
   * confirm=true + 非空 reason）+ nebflow.audit 审计行（一切 Team
   * cancel/restart 留痕）。
   */
  private def withGuardedRecord(
    resources: AgentRuntimePort,
    ctx: ToolContext,
    sessionId: String,
    action: String,
    confirm: Boolean,
    reason: String
  )(
    body: (AgentRecord, String) => IO[Either[ToolError, String]]
  ): IO[Either[ToolError, String]] =
    val callerSessionId = ctx.sessionId.getOrElse("")
    def proceed(rec: AgentRecord, callerRoot: String, rootBucket: Boolean): IO[Either[ToolError, String]] =
      // v2 升级链父重启（§5.3.3）：直接父 = 调用者会话 == 目标记录的 parentSessionId
      // （Manager/owner 管理自有 Team 成员；Delegate/SubTask 的父同理）。
      // 团队功能删除批阶段 3：Team 分支的「Manager 需 confirm=true + 非空 reason」
      // 强确认门与 `auditTeamAction` 审计腿随 team scope 整段删除（作者裁定）——
      // `AgentKind.Team` 目标现由 [[kindRejection]] 直接显式拒绝，走不到这里。
      val callerIsDirectParent = rec.parentSessionId.nonEmpty && rec.parentSessionId == callerSessionId
      kindRejection(rec, action, callerIsDirectParent, callerIsRoot = rootBucket) match
        case Some(msg) => IO.pure(Left(ToolError(msg)))
        case None => body(rec, callerRoot)
      end match
    end proceed
    for
      registry <- resources.agentRegistry.get
      callerRoot = registry.get(callerSessionId).map(_.rootSessionId).filter(_.nonEmpty).getOrElse(callerSessionId)
      rootBucket = callerIsRootBucket(registry, callerSessionId)
      result <- registry.get(sessionId) match
        case None =>
          val manageable = registry.values
            .filter(r => CancelableKinds.contains(r.kind) || RestartableKinds.contains(r.kind))
            .map(_.sessionId)
            .toList
          val hint = if manageable.isEmpty then "(none currently)" else manageable.mkString(", ")
          IO.pure(
            Left(
              ToolError(
                s"No live agent with sessionId='$sessionId'. Currently manageable sessions: $hint. " +
                  "(Registry is in-memory — a sessionId from before a restart no longer exists; check the task file via status.)"
              )
            )
          )
        case Some(rec) =>
          if rec.sessionId == callerSessionId then
            IO.pure(Left(ToolError(s"Self-guard: you cannot $action your own session.")))
          else if rootBucket then
            // root 桶调用者：同桶 = 全局（原语义不变）
            if rec.rootSessionId.nonEmpty && callerRoot.nonEmpty && rec.rootSessionId != callerRoot then
              IO.pure(
                Left(
                  ToolError(
                    s"Permission denied: session '$sessionId' belongs to root session '${rec.rootSessionId}', " +
                      s"yours is '$callerRoot'. You can only manage agents under your own root session."
                  )
                )
              )
            else proceed(rec, callerRoot, rootBucket)
          else
            // Block 1 原为「非 root 调用者（团队 Manager）→ 子树判定取代同桶」。
            // 团队功能删除批阶段 3：team scope 整段删除后，非 root 桶调用者不再有
            // **任何**可管理范围（唯一产生非 root 桶记录的是团队挂载）⇒ 显式拒绝，
            // 不留「不在作用域」的模糊文案（作者裁定口径：删就要明说）。
            IO.pure(
              Left(
                ToolError(
                  s"Permission denied: '$sessionId' is outside your management scope. The team scope " +
                    "(team Managers managing their own members) was retired with the team feature on 2026-11-04 — " +
                    "only a root session (Nebula / project dispatcher) manages background agents now."
                )
              )
            )
    yield result
    end for
  end withGuardedRecord

  // ═══ 团队功能删除批阶段 3：`auditLogger`（nebflow.audit）+ `auditTeamAction`
  // （一切 Team cancel/restart 的审计行）随 team scope 整段删除 —— 唯一调用点是
  // Manager 强确认门之后的那条腿（同上注）。**审计面本身没有损失**：它记录的是
  // 「谁杀了哪个 Team Manager 及理由」，而 Team 目标已整体不可管理。

  /** 审计/通知里的调用者署名：agent 名优先（Nebula/Manager），回退 session 名。 */
  private def callerLabel(ctx: ToolContext): String =
    ctx.agentDef.map(_.name).orElse(ctx.sessionName).getOrElse("AgentControl caller")

  // 团队功能删除批阶段 3：`isManagerSession`（目标会话是否其团队实例的 Manager，
  // 团队会话登记表权威）随 team scope 删除 —— 唯一调用点是已删的强确认门。

  /**
   * Block 1（supervision trio §B4）：list 的可见面。root 桶调用者（Nebula）
   * 看全部；非 root 调用者（team Manager）只看自己的子树（本队成员 + 其
   * 子代），表头附 parent 列辅助辨认层级。
   */
  private def scopeFilterFor(
    resources: AgentRuntimePort,
    ctx: ToolContext
  ): IO[(Map[String, AgentRecord], Option[Set[String]])] =
    val callerSid = ctx.sessionId.getOrElse("")
    for
      registry <- resources.agentRegistry.get
      pred <-
        if callerIsRootBucket(registry, callerSid) then IO.pure(None)
        else
          // 团队功能删除批阶段 3：原为 `managerAnchors(callerSid)`（团队 Manager 的
          // 子树锚点集）。team scope 删除后非 root 桶调用者不存在于生产路径；这里
          // 给**空范围**（看得见 0 行）而非全局范围 —— 失败方向是收窄，不是放宽。
          IO.pure(Some(Set.empty[String]))
    yield (registry, pred)

  private def doList(resources: AgentRuntimePort, ctx: ToolContext): IO[Either[ToolError, String]] =
    for
      (registry, anchorsOpt) <- scopeFilterFor(resources, ctx)
      inScope = (sid: String) =>
        anchorsOpt match
          case None => true // root bucket caller — global view
          case Some(anchors) => anchors.contains(sid) || chainReaches(registry, sid, anchors, 8)
      // V2 (2026-09-03): orphan scope — a crash-survivor task has no registry
      // row of its own; scope it by its PARENT session instead.
      orphanInScope = (parentSid: String) =>
        anchorsOpt match
          case None => true
          case Some(anchors) => anchors.contains(parentSid) || chainReaches(registry, parentSid, anchors, 8)
      runningTasks <- resources.subAgentTaskStore.findRunningTasks
      // 外部行（external-executors 批 #1225）：地址列内外统一，源 =
      // DelegateRegistry（持久地址簿）；与 ExternalOutputStore 的活体/终态留存
      // 条目 join——只展示有缓冲面的行（重启后进程消失的外部任务由下方
      // orphanRows 面覆盖：外部任务同在 runningTasks，无 registry 行即 orphan）。
      externalRows <- nebflow.core.delegate.DelegateRegistry
        .list()
        .filter(_.executor != nebflow.core.executor.ExecutorRegistry.DefaultId)
        .traverse(rec => nebflow.core.executor.ExternalOutputStore.read(rec.id, 0).map(_.map(o => (rec, o))))
        .map(_.flatten)
      taskMap = runningTasks.map(t => t.taskId -> t).toMap
      now = System.currentTimeMillis()
      rows = registry.values.toList
        .filter(rec => inScope(rec.sessionId))
        .sortBy(r => if r.startedAt > 0 then r.startedAt else Long.MaxValue)
        .map { rec =>
          val task = taskMap.get(rec.sessionId)
          val agent = task.map(_.agentName).getOrElse(agentNameFromSessionId(rec.sessionId))
          // 2026-09-10 换轴：stuck? 由 assess 判定（agent 侧停滞 ∪ 工具相位超时），
          // 括号内数字取真正触发的停滞秒数（工具相位触发时即工具已运行时长）。
          val stuck =
            stuckAssessment(rec, now) match
              case Some((secs, _)) => s"⚠ ${fmtDuration(secs * 1000)}"
              case None => "no"
          // Block 3（§3.4）：LoopGuard 计数镜像——S1 连败 streak / S2 无进展轮数
          // （streak@warn=3, terminate=8；非零即有循环嫌疑，供 Manager/Nebula 决策）
          val loop =
            if rec.loopStreak > 0 || rec.loopRounds > 0 then s"${rec.loopStreak}/${rec.loopRounds}"
            else "-"
          // Block 2（§C1）原为「root 桶调用者（Nebula）对 Team 全局 cancel/restart ⇒
          // Team 行不标 read-only」。团队功能删除批阶段 3：Team 目标不可管理 ⇒ 该
          // 放行标注删除，Team 行按普通只读 kind 呈现（rest 只读 kind 照标；新
          // Project flow 会话 node-/dispatcher- 有观察桥取消通道，不标）。
          val readOnly = if !cancelable(rec) then " （read-only）" else ""
          val taskLabel = (task.map(_.description).getOrElse("") + readOnly).trim
          // 会话续命名与位修正批（2026-11-03 · A 部分）：任务列显示名走**去叠加读影**
          // （[[nebflow.core.delegate.SessionResumption.stripContinuationPrefix]] 单点）
          // ——续命派生代的存量 description 行带 `continuation:` 叠加前缀，此处读面
          // 归一（纯读投影，零写盘），面板/AgentControl 行名逐代稳定 = 第一代名。
          val taskLabelClean = nebflow.core.delegate.SessionResumption.stripContinuationPrefix(taskLabel)
          // 续命血缘（B 部分）：派生代在 task 列尾标注来源（审计可读）——血缘在
          // DelegateRegistry.Record.resumedFrom（本批新增槽），任务行不动。
          val lineage =
            if rec.kind == AgentKind.Delegate then
              try
                nebflow.core.delegate.DelegateRegistry
                  .find(rec.sessionId)
                  .flatMap(_.resumedFrom)
                  .filter(_.nonEmpty)
                  .map(f => s" ←resumed:$f")
                  .getOrElse("")
              catch case _: Throwable => ""
            else ""
          // issue #31 Fix D: barrier snapshot — phantom visibility. outstanding>0
          // on an idle session with no in-flight work = a batch member hung / died
          // without a terminal event; held>0 = results parked waiting for the batch.
          // held-receipt 批（fix 3）：held 列追加 unread 终态计数（催注穷尽的未读
          // 回执）——`o/h(+u)` 形态，u>0 即「父会话有 N 条回执注入不了」的可见积压。
          // 投递可靠性批 2026-10-29（可观测③，作者令「给 barrier 列补
          // pendingDelivery 可见性」）：追加 `!N` = 本会话**作为父**名下未确认
          // （未 ack）的 delegate 交付回执数——「子代理终态已发、父会话尚未注入
          // 确认」的在途投递（含停靠投递与合并回执）。N>0 且持续 = 投递链可疑，
          // sweep 会按 grace 重投。
          val pendingDelivery =
            try
              nebflow.core.delegate.DelegateDeliveryReceipts.list().count(r =>
                r.parentSessionId == rec.sessionId && r.ackedAt.isEmpty
              )
            catch case _: Throwable => 0
          val barrier =
            if rec.outstandingSubagents > 0 || rec.pendingEventCount > 0 || rec.unreadReceiptCount > 0 || pendingDelivery > 0 then
              val base = s"${rec.outstandingSubagents}/${rec.pendingEventCount}"
              val withUnread = if rec.unreadReceiptCount > 0 then s"$base(+${rec.unreadReceiptCount})" else base
              if pendingDelivery > 0 then s"$withUnread!$pendingDelivery" else withUnread
            else "-"
          List(
            rec.sessionId,
            // unified-delegate 批：地址列——Delegate 行给出 Mail 续聊地址
            // `delegate:<sessionId>`（与 Delegate 回执同一形态），其余行 "-"。
            if rec.kind == AgentKind.Delegate then s"delegate:${rec.sessionId}" else "-",
            rec.kind.toString,
            agent,
            if rec.parentSessionId.nonEmpty then rec.parentSessionId.take(16) else "-",
            rec.status.toString,
            stuck,
            // 2026-09-10 换轴：当前工具相位（工具名 + 已持续时长）——「进程占死
            // 但 agent 侧零事件」的第一手可见性。
            toolPhaseLabel(rec, now),
            loop,
            barrier,
            fmtMillis(if rec.startedAt > 0 then now - rec.startedAt else 0),
            fmtMillis(if rec.lastActivityMs > 0 then now - rec.lastActivityMs else 0),
            task.map(_.retryCount.toString).getOrElse("-"),
            if taskLabelClean.nonEmpty then (taskLabelClean + lineage).take(64) else lineage.take(64)
          )
        }
    yield
      val header = List(
        "sessionId",
        "address",
        "kind",
        "agent",
        "parent",
        "status",
        "stuck?",
        "phase",
        "loop",
        "barrier",
        "up",
        "idle",
        "retries",
        "task"
      )
      // V2 (2026-09-03): orphan rows — tasks with status=running but NO live
      // registry entry (previous process crashed mid-task; the registry is
      // memory-only). This join-free listing is the only surface where the
      // orphan stays visible between the crash and the next startup sweep.
      val orphanRows = runningTasks
        .filter(t => !registry.contains(t.taskId))
        .filter(t => orphanInScope(t.parentSessionId))
        .map { t =>
          List(
            t.taskId,
            if t.source == "subtask" then "SubTask" else "Delegate",
            t.agentName,
            if t.parentSessionId.nonEmpty then t.parentSessionId.take(16) else "-",
            "orphan(running)",
            "crash",
            "-",
            "-",
            "-",
            fmtMillis(math.max(0L, now - t.spawnedAt)),
            fmtMillis(math.max(0L, now - t.spawnedAt)),
            t.retryCount.toString,
            (nebflow.core.delegate.SessionResumption.stripContinuationPrefix(t.description) +
              " [orphan: no live actor — lost to a process crash; swept at next startup]").take(60)
          )
        }
      // 外部行（#1225）：address 列内外统一（源 DelegateRegistry）；stuck?/phase/
      // loop/barrier 面不适用（无 actor 心跳轴，"-"）；status = 进度面终态词表
      // （running/completed/blocked/cancelled——blocked 即三态判词直显）。
      val externalRowList = externalRows.map { case (rec, o) =>
        val statusLabel =
          if o.status == "running" && o.mailboxDepth > 0 then s"running (mailbox ${o.mailboxDepth})"
          else o.status
        List(
          rec.id,
          s"delegate:${rec.id}",
          "Delegate",
          rec.executor,
          if rec.parentSessionId.nonEmpty then rec.parentSessionId.take(16) else "-",
          statusLabel,
          "-",
          "-",
          "-",
          "-",
          fmtMillis(math.max(0L, now - o.startedAtMs)),
          "-",
          "-",
          // 会话续命名与位修正批（A 部分）：外部行同走去叠加读影（存量续命代
          // 记录的 title 带 `continuation:` 叠加前缀——读面归一，零写盘）。
          nebflow.core.delegate.SessionResumption.displayTitle(rec).take(48)
        )
      }
      val table =
        (header :: rows ++ orphanRows ++ externalRowList).map(r => "| " + r.mkString(" | ") + " |").mkString("\n")
      val scopeNote =
        if anchorsOpt.isDefined then
          "\nScope: your team subtree only (own members + their sub-agents) — root sessions and other teams' agents are hidden."
        else ""
      val orphanNote =
        if orphanRows.nonEmpty then
          "\norphan(running) = task survived a process crash: no live actor exists, its result was lost. The startup sweep terminalizes these and notifies the parent session on next restart."
        else ""
      val externalNote =
        if externalRowList.nonEmpty then
          "\nexternal-* rows = external CLI delegates (address = delegate:<id>, Mail continues them; cancel kills the process tree; status/buffer via GET /api/delegates/<id>/output)."
        else ""
      val summary =
        if rows.isEmpty && orphanRows.isEmpty && externalRowList.isEmpty then
          "No live background agents in your scope (registry is empty or outside your subtree)."
        else
          s"""Background agents (${rows.size + orphanRows.size + externalRowList.size}):
             |
             |$table
             |
             |stuck? = Processing and either (a) no agent-side event (LLM chunk / tool batch) for >${Defaults.StuckThresholdMs / 60000}min
             |         or (b) one tool call running for >${Defaults.ToolPhaseStuckMs / 60000}min inside an unfinished turn — same judgement as the automatic watcher.
             |phase = the in-flight tool call (name + elapsed) for the current turn — "-" when no tool is executing.$orphanNote$externalNote
             |loop = streak/rounds (loop-guard): streak = consecutive rounds failing the same call (warn@3); rounds = non-progressing
             |       rounds this turn (escalates at 70% turn budget / 8). Non-zero = loop suspicion — check status, consider restart.
             |barrier = outstandingSubagents/heldResults (issue #31): outstanding>0 while idle with no in-flight work = phantom slot
             |          (a batch member hung or died without a terminal event — held results never inject until restart).
             |          The HeldDeliveryWatchdog (60s) now nudges undelivered held receipts with exponential backoff; after
             |          ${nebflow.shared.Defaults.HeldDeliveryMaxAttempts} nudges they become unread (+u) and flush ahead of the session's next input.
             |Cancelable kinds: Delegate / SubTask, and Project node/dispatcher sessions (node-*/dispatcher-*, cancel settles via the session bridge). Restartable: Delegate(ephemeral) / SubTask.
             |Team targets: not manageable (the team feature was retired on 2026-11-04; team scope is gone). Legacy dag-* Flow workers and Root are read-only.$scopeNote""".stripMargin
      Right(summary)

  private def doStatus(
    resources: AgentRuntimePort,
    ctx: ToolContext,
    sessionId: String
  ): IO[Either[ToolError, String]] =
    resources.agentRegistry.get.flatMap { registry =>
      // Block 1（§B4）：status 同样受 scope 限制——root 全局，Manager 仅子树
      val callerSid = ctx.sessionId.getOrElse("")
      val rootBucket = callerIsRootBucket(registry, callerSid)
      if !rootBucket then
        // 团队功能删除批阶段 3：team scope（Manager 子树）整体删除 ⇒ 非 root 桶
        // 调用者无范围，显式拒绝（与 cancel/restart 同一口径）。
        IO.pure(
          Left(
            ToolError(
              s"Permission denied: '$sessionId' is outside your management scope. The team scope was retired " +
                "with the team feature on 2026-11-04 — only a root session manages background agents now."
            )
          )
        )
      else doStatusBody(resources, registry, sessionId, rootBucket)
      end if
    }

  private def doStatusBody(
    resources: AgentRuntimePort,
    registry: Map[String, AgentRecord],
    sessionId: String,
    callerIsRoot: Boolean = false
  ): IO[Either[ToolError, String]] =
    val now = System.currentTimeMillis()
    registry.get(sessionId) match
      case Some(rec) =>
        resources.subAgentTaskStore.findByTaskId(sessionId).map { taskOpt =>
          // 2026-09-10 换轴：stuck 详情带判据原因（哪条轴触发）；
          // toolPhase = 当前工具名 + 已持续时长（进程占死形态的第一手信息）。
          val stuckLine = stuckAssessment(rec, now) match
            case Some((secs, reason)) => s"YES (${fmtDuration(secs * 1000)} — $reason)"
            case None => "no"
          // 冻结态诚实呈现批（2026-11-05，作者报障「它显示已冻结，但我没有冻结它、
          // 控制台也没有恢复按钮」）：`status: Frozen` 一个词把「系统在退避/在等」
          // 与「要人来处理」写成同一句话。此处按 FreezeDisplay（判定单点）补足三件
          // 事——谁停的（reason）、能不能自己好（autoRecoverable）、要不要人
          // （needsHuman）与出口。状态词首词仍是枚举名（机器可读面不破坏）。
          val freezeDisplay =
            if rec.status == nebflow.actor.AgentStatus.Frozen then
              Some(nebflow.actor.FreezeDisplay.ofWire(rec.frozenReason.getOrElse(""), None, now))
            else None
          val statusSuffix = freezeDisplay
            .map(fd =>
              s" (${fd.kind.wire} · " +
                (if fd.needsHuman then "needs your decision" else "no action needed — it resumes on its own") + ")"
            )
            .getOrElse("")
          val freezeLines = freezeDisplay.toList.flatMap { fd =>
            List(
              s"freeze: kind=${fd.kind.wire} needsHuman=${fd.needsHuman} autoRecoverable=${fd.autoRecoverable} " +
                s"reason=${fd.reasonWire}",
              s"freeze.label: ${fd.labelEn}",
              "freeze.recovery: " +
                (if fd.needsHuman then
                   "AgentControl restart (resume from the checkpoint in the SAME session) or cancel (terminal); " +
                     "the console row's Wake button calls delegateWake"
                 else "none required — the engine retries on its own")
            )
          }
          val lines = List(
            s"sessionId: ${rec.sessionId}",
            s"kind: ${rec.kind}",
            s"status: ${rec.status}$statusSuffix",
            s"stuck: $stuckLine",
            s"toolPhase: ${toolPhaseLabel(rec, now)}" +
              (if rec.turnStartedAt > 0 then s" (turn started ${fmtDuration(now - rec.turnStartedAt)} ago)" else ""),
            // Block 3（§3.4）：loop-guard 计数详情
            s"loop: streak=${rec.loopStreak} rounds=${rec.loopRounds}" +
              (if rec.loopStreak >= 3 then " ⚠ same call failing repeatedly" else ""),
            s"startedAt: ${if rec.startedAt > 0 then fmtMillis(now - rec.startedAt) + " ago" else "(unknown)"}",
            // idle 列语义收紧（2026-09-10）：agent 侧事件停滞时长——进程侧
            // 活性（processActivityMs）刻意不在此展示（避免把进程动静读成进展）。
            s"lastActivity: ${
                if rec.lastActivityMs > 0 then fmtMillis(now - rec.lastActivityMs) + " ago (agent-side)" else "(never)"
              }",
            s"rootSessionId: ${rec.rootSessionId}",
            s"parentSessionId: ${if rec.parentSessionId.nonEmpty then rec.parentSessionId else "-"}",
            s"supervised: ${rec.supervisorRef.isDefined}",
            // issue #31 Fix D: barrier snapshot (phantom visibility)
            s"barrier: outstanding=${rec.outstandingSubagents} held=${rec.pendingEventCount}" +
              (if rec.outstandingSubagents > 0 then
                 " ⚠ outstanding > 0 — if no batch is actually in flight this is a phantom slot"
               else "") +
              // held-receipt 批（fix 3）：unread 终态面——催注穷尽的未读回执，
              // 随本会话下一输入先补注入（HeldDeliveryWatchdog 已停催）。
              (if rec.unreadReceiptCount > 0 then
                 s"\nunread receipts: ${rec.unreadReceiptCount} — held delivery receipt(s) exhausted their retry " +
                   "budget and will be flushed ahead of this session's next input"
               else "")
          ) ++ taskOpt
            .map { t =>
              List(
                s"task.description: ${t.description}",
                s"task.status: ${t.status}",
                s"task.retryCount: ${t.retryCount}",
                s"task.prompt: ${t.prompt.take(200)}${if t.prompt.length > 200 then "…" else ""}",
                s"task.lastError: ${t.lastError.getOrElse("-")}"
              )
            }
            .getOrElse(List("task record: (none — not a Delegate/SubTask task, or file pruned)"))
          // 团队功能删除批阶段 3：原 `teamRestartable`（Team 目标在 root 桶/直接父下
          // 可 restart）随 team scope 删除 —— Team 目标现恒 read-only，manage 行不再
          // 需要特殊放行。
          val manage =
            kindRejection(rec, "cancel", callerIsDirectParent = !callerIsRoot, callerIsRoot = callerIsRoot) match
              case Some(_) => "manageable: no (read-only kind)"
              case None =>
                "manageable: cancel" +
                  // 阶段 3：`|| teamRestartable` 随 team scope 删除（Team 目标恒 read-only）；
                  // main 侧的 freeze 展示腿（freezeLines）保留。
                  (if RestartableKinds.contains(rec.kind) then " / restart" else " only")
          Right((lines ++ freezeLines :+ manage).mkString("\n"))
        }
      case None =>
        // 外部腿（#1225）：外部 delegate 无 registry 行——status 读进度缓冲面
        // （ExternalOutputStore）＋ DelegateRegistry 记录（原书：status 读缓冲）。
        if sessionId.startsWith(nebflow.core.executor.ExternalSessionRunner.SessionPrefix) then
          externalStatusBody(sessionId)
        else
          resources.subAgentTaskStore.findByTaskId(sessionId).map {
            case Some(t) if t.status == "running" || t.status == "restarting" =>
              Right(
                s"Orphan task: no live actor for '$sessionId' but the task file still says status=${t.status} " +
                  s"(agent=${t.agentName}, parent=${t.parentSessionId}). The actor died without a terminal event " +
                  "(e.g. gateway restart). It can be ignored — startup recovery / pruning will clean it — or the " +
                  "task can be re-delegated."
              )
            case Some(t) =>
              Right(
                s"Session '$sessionId' is no longer live. Task record: status=${t.status}, retries=${t.retryCount}, lastError=${t.lastError.getOrElse("-")}."
              )
            case None =>
              Left(
                ToolError(
                  s"No agent or task record with sessionId='$sessionId'. Run AgentControl(action=list) to see live sessions."
                )
              )
          }

    end match

  end doStatusBody

  /** 外部 delegate 的 status 详情（进度缓冲 + 地址簿记录 + mailbox 深度）。 */
  private def externalStatusBody(sessionId: String): IO[Either[ToolError, String]] =
    nebflow.core.executor.ExternalOutputStore.read(sessionId, 0).flatMap {
      case None =>
        nebflow.core.delegate.DelegateRegistry.find(sessionId) match
          case Some(rec) =>
            IO.pure(
              Right(
                s"External delegate '$sessionId' (executor=${rec.executor}) has no output buffer on this instance " +
                  "(process finished and its retained buffer was evicted, or it ran before the last restart). " +
                  s"Record: workspace=${rec.cwd}, project=${rec.project.getOrElse("-")}, taskId=${rec.taskId.getOrElse("-")}. " +
                  "Continue it via Mail(address=\"delegate:<id>\")."
              )
            )
          case None =>
            IO.pure(
              Left(
                ToolError(
                  s"No external delegate '$sessionId' on record (DelegateRegistry). Run AgentControl(action=list)."
                )
              )
            )
      case Some(o) =>
        nebflow.core.delegate.DelegateRegistry.find(sessionId) match
          case Some(rec) =>
            val lines = List(
              s"sessionId: ${o.taskId}",
              "kind: Delegate (external CLI)",
              s"executor: ${rec.executor}",
              s"status: ${o.status}" + (if o.mailboxDepth > 0 then s" (mailbox: ${o.mailboxDepth} queued)" else ""),
              s"startedAt: ${fmtMillis(System.currentTimeMillis() - o.startedAtMs)} ago",
              s"finishedAt: ${o.finishedAtMs.map(f => fmtMillis(System.currentTimeMillis() - f) + " ago").getOrElse("-")}",
              s"exitCode: ${o.exitCode.map(_.toString).getOrElse("-")}",
              s"lastError: ${o.errorHint.getOrElse("-")}",
              s"workspace: ${rec.cwd}",
              s"project: ${rec.project.getOrElse("-")}",
              s"taskId: ${rec.taskId.getOrElse("-")}",
              s"cliSessionId: ${o.cliSessionId.orElse(rec.cliSessionId).map(_.toString).getOrElse("-")}",
              "address: delegate:" + rec.id,
              "manageable: cancel (kills the CLI process tree); continue via Mail(address)"
            )
            IO.pure(Right(lines.mkString("\n")))
          case None =>
            // 有缓冲无记录（理论形态——记录先于缓冲写）：缓冲面照报。
            IO.pure(
              Right(
                s"External delegate session '$sessionId': status=${o.status}, exitCode=${o.exitCode.map(_.toString).getOrElse("-")}, " +
                  s"lastError=${o.errorHint.getOrElse("-")} (no DelegateRegistry record found)."
              )
            )
    }

  /**
   * Cancel 终止任务终态（区别于 Interrupt 停当前 turn）。public：WS cancelAgent
   * handler（子 agent 管理面板）复用同链路——supervisorRef 优先（Cancelled →
   * notifyParentAndStop，barrier 正确释放），无 supervisor 走降级兜底
   * （Stop + 自补通知 + taskStore cancelled + registry 移除）。
   *
   * notifyWs：面板实时终态帧出口（Sub-Agents 面板取消实时刷新修复）——node-*
   * Project 会话经此补发 agentDone 同构帧（dispatcher-* 不在此发：其观察桥
   * 拆除点 ProjectActor 统一补发，覆盖含 Failed/watcher giveUp 的全部路径，
   * 避免双发）。工具路径传 ctx.wsSend、面板路径传连接 wsSend；None = 静默
   * （既有测试/无 WS 语境零改动）。
   */
  /**
   * P2 scope=bgTask 执行面（设计稿 §4.1-b）：校验任务归属目标会话（自有
   * sessionId ∪ 根挂靠 rootSessionId——与 waitingFor 同口径），经
   * [[nebflow.core.tools.BgTaskCancel.cancelOne]] 精确取消一条（local 直调 /
   * remote 经设备通道 bg-cancel）。会话取消语义零触碰。
   */
  private def doCancelBgTask(
    resources: AgentRuntimePort,
    ctx: ToolContext,
    rec: AgentRecord,
    jobId: String,
    reason: String
  ): IO[Either[ToolError, String]] =
    nebflow.core.tools.BgTaskRegistry.find(jobId).flatMap {
      case None =>
        IO.pure(Left(ToolError(s"No active background task with jobId='$jobId' (not found or already terminal).")))
      case Some(row) =>
        val owned =
          row.sessionId == rec.sessionId ||
            (row.rootSessionId.nonEmpty && row.rootSessionId == rec.sessionId)
        if !owned then
          IO.pure(
            Left(
              ToolError(
                s"Background task '$jobId' does not belong to session '${rec.sessionId}' " +
                  s"(owner='${row.sessionId}', root='${row.rootSessionId}'). Refused — you can only cancel tasks of the targeted session."
              )
            )
          )
        else
          nebflow.core.tools.BgTaskCancel
            .cancelOne(
              jobId,
              by = s"${callerLabel(ctx)}${if reason.nonEmpty then s" ($reason)" else ""}",
              wsSend = ctx.wsSend,
              agentRef = Some(rec.ref)
            )
            .map(_.leftMap(ToolError(_)))
    }

  def doCancel(
    resources: AgentRuntimePort,
    rec: AgentRecord,
    reason: String,
    by: String = "the user panel",
    notifyWs: Option[io.circe.Json => IO[Unit]] = None
  ): IO[Either[ToolError, String]] =
    // ── 缺陷七 · cancel 级联（引擎会话治理专项，作者 2026-11-03 15:09 报障逐字
    // 「你杀掉父会话，子会话也应该一并关闭，但是现在好像是只会清理父会话，子会话
    // 变成孤儿」）：级联**后序**编队（先孙后子、父最后结算），在两条成功腿的文案
    // 尾部追加逐条处置报告（killed / already-terminal / refused）。本项目域会话
    // （dispatcher-/node-）另解析 flow map 的节点 sessionRef 作种子——注册表行没有
    // parentSessionId 边，属主边只在图盘上。
    val cascadeLeg: String => IO[String] = finalText =>
      projectSeeds(resources, rec).flatMap(seeds =>
        CancelCascade
          .cascade(
            new CancelCascade.Port:
              def registry = resources.agentRegistry.get
              def tombstoned = IO
                .blocking(nebflow.core.delegate.DelegateRegistry.list())
                .map(_.filter(_.cancelRequestedAt.isDefined).map(_.id).toSet)
                .handleError(_ => Set.empty[String])
              def cancelChild(kid: nebflow.actor.AgentRecord, why: String) =
                // 同一原语递归（cascade=false 防二次走查；报告逐条如实）。
                doCancel(resources, kid, why, by = s"$by (cascade)", notifyWs = None)
                  .flatMap {
                    case Right(_)  => IO.pure(Right(()))
                    case Left(err) => IO.pure(Left(err.message))
                  }
                  .handleErrorWith(e => IO.pure(Left(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))))
          ,
            rec.sessionId,
            seeds
          )
      ).map { steps =>
        if steps.nonEmpty then
          logger.info(
            s"[cancel-cascade] ${rec.sessionId}: ${steps.count(_.outcome == CancelCascade.Outcome.Killed)} killed, " +
              s"${steps.count(_.outcome == CancelCascade.Outcome.AlreadyTerminal)} already-terminal, " +
              s"${steps.count(_.outcome == CancelCascade.Outcome.Refused)} refused"
          )
        finalText + CancelCascade.report(steps)
      }
    val reasonSuffix = if reason.nonEmpty then s" — $reason" else ""
    rec.supervisorRef match
      case Some(sup) =>
        // R1 缝隙 A+C 修（2026-10-28 实施批 · 报告二 §1.3 硬验收）：
        //  ① 取消意图**先行落墓碑**（DelegateRegistry.markCancelRequested——缝隙 C
        //     件：意图先于信号持久在案，crash 窗口内「信号已发意图未落」不可能）；
        //  ② Cancelled 投递后**带回执确认**（原 fire-and-forget tell 改造）：有界窗
        //     内轮询任务行翻 cancelled（notifyParentAndStop 的 taskUpdate = 终态结算
        //     的盘面证据）——监督者已死/消息被弃的场景不再回假成功文案；
        //  ③ 超窗未确认 = **降级兜底直落**（taskUpdate cancelled + registry 活体
        //     清除 + child Stop），回执如实申报「监督者路径未确认，已走兜底」。
        // Project flow 会话（node-/dispatcher-）与 Ephemeral 无任务行语义（观察桥
        // 清理形态，见下注）——确认腿不适用，保持旧投递语义（墓碑照写，查无记录
        // = Left 只 WARN）。
        val tombstoneWrite = IO.blocking(nebflow.core.delegate.DelegateRegistry.markCancelRequested(rec.sessionId, by)).attempt.flatMap {
          case Right(_)  => IO.unit
          case Left(err) => logger.warn(s"cancel tombstone write failed for '${rec.sessionId}': $err")
        }
        val rowReceiptApplies = rec.kind == AgentKind.Delegate || rec.kind == AgentKind.SubTask
        val verifyBudget = nebflow.core.delegate.CancelReceipt.verifyBudgetMs
        tombstoneWrite *> (sup ! AgentEvent.Cancelled(rec.sessionId, reason)).flatMap { _ =>
          val panelFrame =
            if rec.kind == AgentKind.Flow && rec.sessionId.startsWith(nebflow.core.project.NodeEngine.SessionPrefix)
            then
              notifyWs.fold(IO.unit)(ws =>
                nebflow.core.node.NodeRunner
                  .emitSubagentPanelDone(ws, rec.sessionId, rec.rootSessionId)
                  .handleErrorWith(_ => IO.unit)
              )
            else IO.unit
          val verifiedIo: IO[Boolean] =
            if rowReceiptApplies then
              nebflow.core.delegate.CancelReceipt.awaitTerminal(resources.subAgentTaskStore, rec.sessionId, verifyBudget)
            else IO.pure(true) // 无任务行语义的形态：投递即成功（旧语义逐字）
          verifiedIo.flatMap {
            case true =>
              panelFrame *> cascadeLeg(
                if rec.kind == AgentKind.Flow then
                  s"Cancel sent for Project session '${rec.sessionId}' — it will be unregistered and the agent stopped" +
                    (if rec.sessionId.startsWith(nebflow.core.project.NodeEngine.SessionPrefix) then
                       "; the node settles to status=cancelled and its result is NOT delivered"
                     else "") + "."
                else
                  s"Cancel sent to supervisor for '${rec.sessionId}' and CONFIRMED (task row settled to cancelled). " +
                    "The parent session receives a cancelled notification. A cancel tombstone is on record — " +
                    "this instance can never be revived across restarts."
              ).map(Right(_))
            case false =>
              // 监督者路径未确认（监督者已死/超窗未结算）——降级兜底直落终态。
              // 兜底不重复父通知（正路若迟到，其通知仍会到——双发比假成功诚实）。
              logger
                .warn(
                  s"[cancel-receipt] supervisor path for '${rec.sessionId}' unconfirmed within ${verifyBudget}ms — applying fallback terminal state"
                )
                .flatMap { _ =>
                  val taskUpdate =
                    if rowReceiptApplies && rec.parentSessionId.nonEmpty then
                      resources.subAgentTaskStore
                        .updateStatus(
                          rec.parentSessionId,
                          rec.sessionId,
                          "cancelled",
                          completedAt = Some(System.currentTimeMillis()),
                          lastError = Some(s"cancelled by $by via AgentControl$reasonSuffix (supervisor unconfirmed — fallback terminal)")
                        )
                        .handleErrorWith(e => logger.warn(s"cancel fallback taskStore update failed: ${e.getMessage}"))
                    else IO.unit
                  taskUpdate *> resources.agentRegistry.update(_ - rec.sessionId) *>
                    (rec.ref ! AgentCommand.Stop(s"agent-control-cancel-fallback$reasonSuffix")).flatMap { _ =>
                      cascadeLeg(
                        s"Cancel sent to supervisor for '${rec.sessionId}' but the terminal state was NOT confirmed " +
                          s"within ${verifyBudget}ms — fallback terminal state written directly " +
                          "(task row cancelled, registry entry removed, agent stopped). If the supervisor settles later, " +
                          "its terminal event still wins (first-terminal-wins). Cancel tombstone is on record."
                      ).map(Right(_))
                    }
                }
          }
        }
      case None =>
        // 降级路径（spec §3.2 兜底）：Ephemeral（bridge death-watch 兜底回收）或
        // 无 supervisor 记录的旧记录——直接 Stop + 自补通知 + registry 移除。
        // R1-C 修：取消意图同样先行落墓碑（幂等；查无 registry 记录 = Left 只
        // WARN——墓碑只在真实 spawn 记录上生效）。
        val source = rec.kind match
          case AgentKind.SubTask => "subtask"
          case AgentKind.Delegate => "delegate"
          case _ => "background-task"
        val tombstoneWrite = IO
          .blocking(nebflow.core.delegate.DelegateRegistry.markCancelRequested(rec.sessionId, by))
          .attempt
          .flatMap {
            case Right(_)  => IO.unit
            case Left(err) => logger.warn(s"cancel tombstone write failed for '${rec.sessionId}': $err")
          }
        val metadata = JsonObject(
          "failedSessionId" -> rec.sessionId.asJson,
          "retryable" -> false.asJson,
          "failureType" -> "cancelled".asJson,
          "cancelled" -> true.asJson,
          "reason" -> reason.asJson
        )
        val notify = rec.parentRef.fold(IO.unit)(p =>
          p ! AgentCommand.ExternalEvent(
            source = source,
            eventType = "cancelled",
            payload = s"\"${rec.sessionId}\": cancelled by $by via AgentControl$reasonSuffix",
            metadata = metadata,
            correlationId = Some(rec.sessionId)
          )
        )
        val taskUpdate =
          if rec.parentSessionId.nonEmpty then
            resources.subAgentTaskStore
              .updateStatus(
                rec.parentSessionId,
                rec.sessionId,
                "cancelled",
                completedAt = Some(System.currentTimeMillis()),
                lastError = Some(s"cancelled by $by via AgentControl$reasonSuffix")
              )
              .handleErrorWith(e => logger.warn(s"cancel taskStore update failed: ${e.getMessage}"))
          else IO.unit
        tombstoneWrite *> notify *> taskUpdate *> resources.agentRegistry.update(_ - rec.sessionId) *>
          (rec.ref ! AgentCommand.Stop(s"agent-control-cancel$reasonSuffix")).flatMap { _ =>
            cascadeLeg(
              s"Terminated '${rec.sessionId}' directly (no supervisor on record — fallback path). " +
                "Registry entry removed (cancel tombstone retained); parent notified if applicable."
            ).map(Right(_))
          }

    end match

  end doCancel

  /**
   * 项目域级联种子（缺陷七 · cancel 级联的项目腿）：`dispatcher-*` / `node-*` 会话在
   * 统一注册表里**没有 `parentSessionId` 边**——属主边只活在 flow map 的节点
   * `sessionRef` 上（crash-recovery 批 D1 落库）。因此取消一个项目会话时，先按它
   * 归属的项目（`AgentRecord.project`）取回该项目的在飞节点会话 id 作种子，再交给
   * [[CancelCascade.cascade]] 统一编队/报告（非项目域 = 空集，fail-soft 零动作）。
   */
  private def projectSeeds(resources: AgentRuntimePort, rec: AgentRecord): IO[List[String]] =
    if rec.kind != AgentKind.Flow then IO.pure(Nil)
    else
      rec.project match
        case None => IO.pure(Nil)
        case Some(project) =>
          nebflow.core.project.ProjectRuntimeRegistry
            .get(project)
            .flatMap {
              case None => IO.pure(Nil)
              case Some(rt) =>
                rt.store.snapshot.map { snap =>
                  snap.nodes.values.toList
                    .filter(n => !nebflow.core.project.NodeLifecycle.Terminal.contains(n.status))
                    .flatMap(_.sessionRef)
                    .filter(id => id != rec.sessionId)
                }
            }
            .handleErrorWith(e =>
              logger.warn(s"[cancel-cascade] project seeds for '${rec.sessionId}' unresolved: ${e.getMessage}").as(Nil)
            )

  /**
   * Restart a supervised session: kill the stuck turn, let the supervisor
   * respawn it from the last persisted checkpoint.
   *
   * 团队功能删除批（team-feature-deletion-20261104）阶段 3：Team 成员 restart 腿
   * 整段删除（作者裁定「team scope 整段删除」）——包含 `teamRestartSuccessText` /
   * `teamRestartDeadText` 两条专用文案与「Stop 旧 actor → 等死 → 经
   * `MailTool.activateAgent` 从持久化 history 重建」的执行腿。该腿在团队退役后
   * 已无对象可指（Team 目标现由 kindRejection 显式拒绝），故不留半套。
   * 本方法的入口判定因之直接落到 supervisor 分支（Delegate/SubTask 重启腿逐字未动）。
   */
  def doRestart(
    resources: AgentRuntimePort,
    ctx: ToolContext,
    rec: AgentRecord,
    reason: String
  ): IO[Either[ToolError, String]] =
    if rec.supervisorRef.isEmpty then
      // registry 是内存态、与代码同版本——Delegate/SubTask 记录恒有 supervisor；
      // 走到这里说明内部不一致，拒绝而非盲杀。
      IO.pure(
        Left(
          ToolError(
            s"Session '${rec.sessionId}' has no supervisor on record (internal inconsistency) — " +
              "restart would kill it without recovery. Use cancel instead."
          )
        )
      )
    else
      resources.subAgentTaskStore.findByTaskId(rec.sessionId).flatMap {
        case None =>
          // persistent Delegate 不 recordTask → 无断点恢复载体
          IO.pure(
            Left(
              ToolError(
                "Persistent delegates do not support restart (no supervisor restart loop; Terminated is " +
                  "reported as a crash). Cancel it and Delegate again — its session history stays persisted " +
                  "and can be re-activated via Mail or a fresh spawn."
              )
            )
          )
        case Some(task) =>
          val parentSid = if rec.parentSessionId.nonEmpty then rec.parentSessionId else task.parentSessionId
          // R1 缝隙 C 修收口（restart 腿同闸，2026-10-29 三路径批 · 报告二 §1.3）：
          // 取消墓碑在场 = 已取消会话**不可 restart**。restart 的续跑机制就是
          // respawn（Stop → Terminated → BackoffSupervisor.respawn），监督者的
          // 墓碑闸会把 spawn 拒回 cancelled 终态；此处先行显式拒绝、回执如实
          // ——不再「回了 restarting 又被闸翻回去」。查无 registry 记录 = 无墓碑
          // （subtask 等形态），照旧放行。
          IO
            .blocking(nebflow.core.delegate.DelegateRegistry.find(rec.sessionId))
            .handleErrorWith(_ => IO.pure(None))
            .flatMap {
              case Some(r) if r.cancelRequestedAt.isDefined =>
                IO.pure(
                  Left(
                    ToolError(
                      s"Session '${rec.sessionId}' has a cancel tombstone on record (cancelled by " +
                        s"${r.cancelRequestedBy.getOrElse("?")}) — a cancelled session cannot be restarted. " +
                        "Delegate again if the work should continue."
                    )
                  )
                )
              case _ =>
                for
                  _ <- resources.subAgentTaskStore
                    .updateStatus(parentSid, rec.sessionId, "restarting")
                    .handleErrorWith(e => logger.warn(s"restart taskStore update failed: ${e.getMessage}"))
                  _ <- rec.ref ! AgentCommand.Stop(
                    s"agent-control-restart${if reason.nonEmpty then s": $reason" else ""}"
                  )
                  // 父决策销账（v2 反推送风暴批）：restart 即关闭其未决升级事件。
                  _ <- clearRegistryEscalation(resources, rec.sessionId)
                yield Right(
                  s"Restart sent for '${rec.sessionId}': the stuck turn is terminated now; the supervisor will " +
                    s"respawn it after a short backoff (~5s) and resume from the last persisted checkpoint " +
                    s"(completed work is kept). NOTE: manual restarts consume the supervisor's circuit-breaker " +
                    s"budget — after 2 restarts within 5 minutes the task fails with a notification. " +
                    s"Current task retryCount: ${task.retryCount}."
                )
            }
      }

  /**
   * 父决策销账（v2 反推送风暴批 2026-10-28，修复面①）：父会话对目标会话执行
   * restart（或显式裁定）即关闭其未决升级事件——registry 的 escalation 快照清
   * None。既有实证（stand.log 2026-10-28 19:23-20:10）：升级链状态（actor 内存态
   * + registry 快照）在父裁定后从不销账 ⇒ 「已 restart 且生效，事件仍逐级重投」。
   * 本 helper 供 AgentControl.doRestart / WS parentRestart 裁定点调用；actor 层
   * 权威态由 AgentFrozen 的销账腿（恢复/唤醒/重启路径 withEscalation(None)）清。
   * registry 无该会话（cancel 路径整条移除）= no-op；best-effort，失败仅日志。
   */
  def clearRegistryEscalation(resources: AgentRuntimePort, sessionId: String): IO[Unit] =
    if sessionId.trim.isEmpty then IO.unit
    else
      resources.agentRegistry
        .update(m => m.get(sessionId) match
          case Some(rec) if rec.escalation.isDefined => m.updated(sessionId, rec.copy(escalation = None))
          case _ => m
        )
        .handleErrorWith(e => logger.warn(s"clearRegistryEscalation($sessionId) failed: ${e.getMessage}"))

end AgentControlTool
