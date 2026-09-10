package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.JsonObject
import nebflow.agent.*
import nebflow.core.flow.TeamSessionRegistry
import nebflow.shared.Defaults

import scala.concurrent.duration.*

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
 *            "cancelled"，barrier 正确释放）/ 降级 Stop 路径（Ephemeral bridge
 *            watch 兜底 / 旧记录）
 *  - restart 断点重启：Stop → BackoffSupervisor Terminated → 恢复持久化消息续跑
 *            （复用 #317 crash resume，零新机制），消耗 supervisor 熔断额度
 *
 * 安全边界（spec §4 矩阵，严格）：
 *  - cancel 白名单：Delegate / SubTask / Ephemeral；Flow 指引 cancelFlow；
 *    Team 条件放行：直接父 Manager / root 全局（supervision trio Block 2 §C3）；
 *    杀 Manager 需 confirm+reason 双确认门；Root/自身拒绝（自杀守卫）
 *  - restart 白名单：Delegate(ephemeral) / SubTask；persistent Delegate 无 supervisor
 *    拒绝（提示 cancel + 重新 Delegate）；Ephemeral 无恢复载体拒绝
 *  - rootSessionId 必须与调用者同桶（P2 权限原则）
 */
object AgentControlTool extends Tool:

  private val logger = nebflow.core.NebflowLogger.forName("nebflow.tools.agent-control")

  val name = "AgentControl"

  val description =
    """Inspect and control background agent sessions — the sub-agents spawned via Delegate/SubTask, ephemeral Mail runners, and other live sessions in this instance.

Actions:
- **list**: table of all live background agents (kind, status, stuck?, phase, uptime, idle time, retry count, task). Use this FIRST when a sub-agent is silent or you suspect it is stuck — "stuck?" marks Processing agents that either produced no agent-side event (LLM chunk / tool batch) for >10min, or have a single tool call running for >10min inside an unfinished turn (same judgement as the automatic watcher — process CPU is irrelevant to it). "phase" shows the in-flight tool call (name + elapsed), which is what tells you whether the agent is executing a tool or has gone silent.
- **status**: full detail for one agent (pass sessionId from list): state, timings, in-flight tool phase, task prompt excerpt, retry count, last error. Also detects orphan task files (actor gone but task still marked running).
- **cancel**: terminate an agent's current task. The parent session receives a "cancelled" notification and any wait barrier is released — nobody waits forever. Allowed kinds: Delegate, SubTask, Ephemeral, Project node/dispatcher sessions (node-*/dispatcher-*, settles via the session bridge), and Team members (permission-scoped — see Safety rules).
- **restart**: kill the stuck turn and resume from the last persisted checkpoint (same mechanism as crash recovery — completed work is kept). Allowed kinds: Delegate (ephemeral) and SubTask (both consume the supervisor's restart budget, 2 per 5min; exceeding it fails the task), and Team members (Stop + re-activation from persisted history — no supervisor budget consumed).

Safety rules: you cannot cancel/restart yourself, Root/plan sessions (self-preservation guard), or legacy dag-* Flow workers (flow cancellation goes through cancelFlow / RunningFlowRegistry). Project node/dispatcher sessions (node-*/dispatcher-*) support cancel only (single-shot — no restart; cancel the dispatcher and re-trigger Task(project=...)). Delegate/SubTask/Ephemeral sessions under your own root session are always controllable. Team members are manageable by their team's Manager (subtree scope — own team members and their sub-agents) and by Nebula (global scope); other callers are read-only for Team. Killing a team MANAGER (cancel/restart) additionally requires confirm=true plus a non-empty reason — a killed Manager leaves its members running but coordinator-less (members remain manageable by Nebula); every such kill is recorded in the audit log.

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

  /** 无条件 cancelable kinds。Team 是条件放行（kindRejection Team 分支：直接父/root），
    * 不进此白名单——WS cancelAgent handler 复用此白名单 → 管理面板对 Team 保持只读，
    * 权限门只在工具路径。 */
  val CancelableKinds: Set[AgentKind] = Set(AgentKind.Delegate, AgentKind.SubTask, AgentKind.Ephemeral)
  private val RestartableKinds: Set[AgentKind] = Set(AgentKind.Delegate, AgentKind.SubTask)

  /** 新 Project 系统的 Flow 会话（Project 节点 node-* / 任务分发器 dispatcher-*）。
    * 旧 flow 系统的 dag-* 会话不在此列（其取消走 cancelFlow / RunningFlowRegistry）。
    * 前缀权威定义：NodeEngine.SessionPrefix / ProjectActor.DispatcherSessionPrefix。 */
  def isProjectFlowSession(sessionId: String): Boolean =
    sessionId.startsWith(nebflow.core.project.NodeEngine.SessionPrefix) ||
      sessionId.startsWith(nebflow.core.project.ProjectActor.DispatcherSessionPrefix)

  /** 面板/工具共用的 cancel 白名单：无条件 kind 白名单 + 新 Project flow 会话
    * （须有 supervisor 取消通道——观察桥的 Cancelled 分支；dag- 旧 flow 会话
    * 无此通道，保持只读）。 */
  def cancelable(rec: AgentRecord): Boolean =
    CancelableKinds.contains(rec.kind) ||
      (rec.kind == AgentKind.Flow && isProjectFlowSession(rec.sessionId) && rec.supervisorRef.isDefined)

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
              "Cancel it and re-trigger the work (Task(project=...) for the dispatcher / NodeEdit for nodes)."
          )
        else
          Some(
            "Flow workers follow the DAG lifecycle — cancelling one directly would break the flow state machine. " +
              "Use flow-level cancellation instead (cancelFlow(instanceId) / RunningFlowRegistry; NodeCancel for Project nodes)."
          )
      case AgentKind.Team =>
        // v2 升级链父重启（§5.3.3）：直接父（Manager/owner）对自有 Team 成员
        // restart/cancel 放行——升级链的决策载体。
        // Block 2（supervision trio §C3）：root 桶调用者（Nebula）对 Team 全局
        // cancel/restart 放行（let-it-crash 裁定第 6 条——杀 Manager 需 confirm，
        // 在 withGuardedRecord 的强确认门）。其他调用者保持只读。
        if callerIsDirectParent || callerIsRoot then None
        else
          Some(
            "Team members are read-only for AgentControl in this version — killing one mid-collaboration breaks the team state machine. " +
              "If a team agent is stuck, Mail its Manager or wait for the stuck watcher."
          )
      case AgentKind.Root | AgentKind.Plan =>
        Some("Root/plan sessions cannot be managed with this tool (self-preservation guard).")
      case k =>
        val allowed = if action == "restart" then RestartableKinds else CancelableKinds
        if allowed.contains(k) then None
        else if action == "restart" then
          Some(s"Ephemeral agents have no supervisor/checkpoint carrier — restart is not supported. Cancel it and spawn a new one.")
        else None

  // ── 渲染 helpers ──────────────────────────────────────────

  /** Block 1 (supervision trio §B3): walk the parentSessionId chain from
    * `from` upward (≤ maxDepth hops, cycle-safe by depth bound); true iff an
    * anchor session is reached. Pure — unit-testable. */
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
          case None    => false
    walk(from, 0)

  /** Block 1 (§B3): the manager scope anchor set — the caller itself plus
    * every session of the team instance it leads. None = caller does not
    * lead any instance (not a Manager, or a member of one) → no scope → no
    * manageability. */
  private def managerAnchors(callerSid: String): IO[Option[Set[String]]] =
    TeamSessionRegistry.teamOfSession(callerSid).flatMap {
      case None => IO.pure(None)
      case Some(inst) =>
        TeamSessionRegistry.managerOf(inst).flatMap {
          case Some(m) if m != callerSid => IO.pure(None)
          case _ =>
            TeamSessionRegistry.sessionIdsOf(inst).map(sids => Some((callerSid +: sids).toSet))
        }
    }

  /** Block 1 (§B3): a root-bucket caller's rootSessionId IS its own session id
    * (Nebula / root agents). All other callers (team Managers) are scoped to
    * their subtree instead of the bucket. A caller with NO registry record is
    * treated as root-bucket: only Nebula (Root) and team Managers hold the
    * tool, both are registered whenever they act — an unregistered caller is
    * a legacy/test shape and keeps the legacy global semantics. */
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
      case _                                        => "-"

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

  /** 统一守卫（§4）：查无 / kind 白名单 / 自杀守卫 / rootSessionId 同桶。
    * Block 1（supervision trio §B3）：root 桶调用者（Nebula，rootSessionId ==
    * 自身 sid）维持同桶=全局；非 root 调用者（team Manager，唯一非 root 被
    * 授权者）从「同桶」收紧为「子树」——同桶会放行它管**别的 team** 的子代
    * （所有挂载 team 共享挂载 root 的桶）。
    * Block 2（§C3）：Team 目标过 kind 门后，强确认门（Manager 目标需
    * confirm=true + 非空 reason）+ nebflow.audit 审计行（一切 Team
    * cancel/restart 留痕）。 */
  private def withGuardedRecord(
      resources: SharedResources,
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
      val callerIsDirectParent = rec.parentSessionId.nonEmpty && rec.parentSessionId == callerSessionId
      kindRejection(rec, action, callerIsDirectParent, callerIsRoot = rootBucket) match
        case Some(msg) => IO.pure(Left(ToolError(msg)))
        case None =>
          if rec.kind == AgentKind.Team then
            isManagerSession(rec.sessionId).flatMap {
              case true if !confirm =>
                IO.pure(
                  Left(
                    ToolError(
                      s"'${rec.sessionId}' is a team MANAGER. Cancelling/restarting it requires confirm=true " +
                        "AND a non-empty reason. Consequence: the Manager's members keep running but lose their " +
                        "coordinator (they remain manageable by Nebula via AgentControl). Re-issue with " +
                        "confirm=true and reason=\"<why>\" if this is intended."
                    )
                  )
                )
              case true if reason.isBlank =>
                IO.pure(
                  Left(
                    ToolError(
                      "confirm=true for killing a team Manager requires a non-empty reason " +
                        "(audit trail: who killed which Manager and why). Re-issue with reason=\"<why>\"."
                    )
                  )
                )
              case _ =>
                auditTeamAction(ctx, action, rec, confirm, reason) *> body(rec, callerRoot)
            }
          else body(rec, callerRoot)
    for
      registry <- resources.agentRegistry.get
      callerRoot = registry.get(callerSessionId).map(_.rootSessionId).filter(_.nonEmpty).getOrElse(callerSessionId)
      rootBucket = callerIsRootBucket(registry, callerSessionId)
      result <- registry.get(sessionId) match
        case None =>
          val manageable = registry.values
            .filter(r => CancelableKinds.contains(r.kind) || RestartableKinds.contains(r.kind) || r.kind == AgentKind.Team)
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
            // Block 1：非 root 调用者（Manager）——子树判定取代同桶
            managerAnchors(callerSessionId).flatMap {
              case Some(anchors) if anchors.contains(sessionId) || chainReaches(registry, sessionId, anchors, 8) =>
                proceed(rec, callerRoot, rootBucket)
              case _ =>
                IO.pure(
                  Left(
                    ToolError(
                      s"Permission denied: session '$sessionId' is outside your team subtree " +
                        "(your own team members and their sub-agents). Root sessions and other teams' agents are not manageable by you."
                    )
                  )
                )
            }
    yield result

  /** Block 2（§C3-4）：Team cancel/restart 审计行——logger `nebflow.audit`
    * （随 nebflow.log 落盘）。 */
  private val auditLogger = nebflow.core.NebflowLogger.forName("nebflow.audit")

  private def auditTeamAction(ctx: ToolContext, action: String, rec: AgentRecord, confirm: Boolean, reason: String): IO[Unit] =
    val callerSid = ctx.sessionId.getOrElse("-")
    val callerAgent = ctx.agentDef.map(_.name).orElse(ctx.sessionName).getOrElse("-")
    auditLogger.info(
      s"AUDIT ts=${System.currentTimeMillis()} caller=$callerSid callerAgent=$callerAgent " +
        s"action=$action target=${rec.sessionId} targetKind=Team targetAgent=${agentNameFromSessionId(rec.sessionId)} " +
        s"confirm=$confirm reason=\"$reason\"" +
        (if action == "restart" then " note=\"history restored; turn NOT auto-resumed\"" else "")
    )

  /** 审计/通知里的调用者署名：agent 名优先（Nebula/Manager），回退 session 名。 */
  private def callerLabel(ctx: ToolContext): String =
    ctx.agentDef.map(_.name).orElse(ctx.sessionName).getOrElse("AgentControl caller")

  /** 目标会话是其 team 实例的 Manager（TeamSessionRegistry 权威）。 */
  private def isManagerSession(sid: String): IO[Boolean] =
    TeamSessionRegistry.teamOfSession(sid).flatMap {
      case None => IO.pure(false)
      case Some(inst) =>
        TeamSessionRegistry.managerOf(inst).map(_ == Some(sid))
    }


  /** Block 1（supervision trio §B4）：list 的可见面。root 桶调用者（Nebula）
    * 看全部；非 root 调用者（team Manager）只看自己的子树（本队成员 + 其
    * 子代），表头附 parent 列辅助辨认层级。 */
  private def scopeFilterFor(
    resources: SharedResources,
    ctx: ToolContext
  ): IO[(Map[String, AgentRecord], Option[Set[String]])] =
    val callerSid = ctx.sessionId.getOrElse("")
    for
      registry <- resources.agentRegistry.get
      pred <- if callerIsRootBucket(registry, callerSid) then IO.pure(None)
              else managerAnchors(callerSid)
    yield (registry, pred)
  private def doList(resources: SharedResources, ctx: ToolContext): IO[Either[ToolError, String]] =
    for
      (registry, anchorsOpt) <- scopeFilterFor(resources, ctx)
      inScope = (sid: String) =>
        anchorsOpt match
          case None         => true // root bucket caller — global view
          case Some(anchors) => anchors.contains(sid) || chainReaches(registry, sid, anchors, 8)
      // V2 (2026-09-03): orphan scope — a crash-survivor task has no registry
      // row of its own; scope it by its PARENT session instead.
      orphanInScope = (parentSid: String) =>
        anchorsOpt match
          case None         => true
          case Some(anchors) => anchors.contains(parentSid) || chainReaches(registry, parentSid, anchors, 8)
      runningTasks <- resources.subAgentTaskStore.findRunningTasks
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
              case None            => "no"
          // Block 3（§3.4）：LoopGuard 计数镜像——S1 连败 streak / S2 无进展轮数
          //（streak@warn=3, terminate=8；非零即有循环嫌疑，供 Manager/Nebula 决策）
          val loop =
            if rec.loopStreak > 0 || rec.loopRounds > 0 then s"${rec.loopStreak}/${rec.loopRounds}"
            else "-"
          // Block 2（§C1）：root 桶调用者（Nebula）对 Team 全局 cancel/restart——
          // Team 行不再标 read-only；Manager 子树视图（anchorsOpt 定义）内 Team
          // 由直接父分支放行，同样不标。其余只读 kind 照标（新 Project flow 会话
          // node-/dispatcher- 有观察桥取消通道，不标）。
          val teamManageable = rec.kind == AgentKind.Team && anchorsOpt.isEmpty
          val readOnly =
            if !cancelable(rec) && !teamManageable then " （read-only）" else ""
          val taskLabel = (task.map(_.description).getOrElse("") + readOnly).trim
          // issue #31 Fix D: barrier snapshot — phantom visibility. outstanding>0
          // on an idle session with no in-flight work = a batch member hung / died
          // without a terminal event; held>0 = results parked waiting for the batch.
          val barrier =
            if rec.outstandingSubagents > 0 || rec.pendingEventCount > 0 then
              s"${rec.outstandingSubagents}/${rec.pendingEventCount}"
            else "-"
          List(
            rec.sessionId,
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
            if taskLabel.nonEmpty then taskLabel.take(48) else "-"
          )
        }
    yield
      val header = List("sessionId", "kind", "agent", "parent", "status", "stuck?", "phase", "loop", "barrier", "up", "idle", "retries", "task")
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
            (t.description + " [orphan: no live actor — lost to a process crash; swept at next startup]").take(60)
          )
        }
      val table = (header :: rows ++ orphanRows).map(r => "| " + r.mkString(" | ") + " |").mkString("\n")
      val scopeNote =
        if anchorsOpt.isDefined then
          "\nScope: your team subtree only (own members + their sub-agents) — root sessions and other teams' agents are hidden."
          else ""
      val orphanNote =
        if orphanRows.nonEmpty then
          "\norphan(running) = task survived a process crash: no live actor exists, its result was lost. The startup sweep terminalizes these and notifies the parent session on next restart."
          else ""
      val summary =
        if rows.isEmpty && orphanRows.isEmpty then "No live background agents in your scope (registry is empty or outside your subtree)."
        else
          s"""Background agents (${rows.size + orphanRows.size}):
             |
             |$table
             |
             |stuck? = Processing and either (a) no agent-side event (LLM chunk / tool batch) for >${Defaults.StuckThresholdMs / 60000}min
             |         or (b) one tool call running for >${Defaults.ToolPhaseStuckMs / 60000}min inside an unfinished turn — same judgement as the automatic watcher.
             |phase = the in-flight tool call (name + elapsed) for the current turn — "-" when no tool is executing.$orphanNote
             |loop = streak/rounds (loop-guard): streak = consecutive rounds failing the same call (warn@3); rounds = non-progressing
             |       rounds this turn (escalates at 70% turn budget / 8). Non-zero = loop suspicion — check status, consider restart.
             |barrier = outstandingSubagents/heldResults (issue #31): outstanding>0 while idle with no in-flight work = phantom slot
             |          (a batch member hung or died without a terminal event — held results never inject until restart).
             |Cancelable kinds: Delegate / SubTask / Ephemeral, and Project node/dispatcher sessions (node-*/dispatcher-*, cancel settles via the session bridge). Restartable: Delegate(ephemeral) / SubTask.
             |Team members: the team's Manager (direct parent) or Nebula (global; killing a team MANAGER requires confirm=true + reason — audited). Legacy dag-* Flow workers and Root are read-only.$scopeNote""".stripMargin
      Right(summary)

  private def doStatus(resources: SharedResources, ctx: ToolContext, sessionId: String): IO[Either[ToolError, String]] =
    resources.agentRegistry.get.flatMap { registry =>
      // Block 1（§B4）：status 同样受 scope 限制——root 全局，Manager 仅子树
      val callerSid = ctx.sessionId.getOrElse("")
      val rootBucket = callerIsRootBucket(registry, callerSid)
      if !rootBucket then
        managerAnchors(callerSid).flatMap {
          case Some(anchors) if anchors.contains(sessionId) || chainReaches(registry, sessionId, anchors, 8) =>
            doStatusBody(resources, registry, sessionId, rootBucket)
          case _ =>
            IO.pure(
              Left(
                ToolError(
                  s"Permission denied: session '$sessionId' is outside your team subtree " +
                    "(your own team members and their sub-agents)."
                )
              )
            )
        }
      else doStatusBody(resources, registry, sessionId, rootBucket)
    }

  private def doStatusBody(
      resources: SharedResources,
      registry: Map[String, AgentRecord],
      sessionId: String,
      callerIsRoot: Boolean = false
  ): IO[Either[ToolError, String]] =
    {
      val now = System.currentTimeMillis()
      registry.get(sessionId) match
        case Some(rec) =>
          resources.subAgentTaskStore.findByTaskId(sessionId).map { taskOpt =>
            // 2026-09-10 换轴：stuck 详情带判据原因（哪条轴触发）；
            // toolPhase = 当前工具名 + 已持续时长（进程占死形态的第一手信息）。
            val stuckLine = stuckAssessment(rec, now) match
              case Some((secs, reason)) => s"YES (${fmtDuration(secs * 1000)} — $reason)"
              case None                 => "no"
            val lines = List(
              s"sessionId: ${rec.sessionId}",
              s"kind: ${rec.kind}",
              s"status: ${rec.status}",
              s"stuck: $stuckLine",
              s"toolPhase: ${toolPhaseLabel(rec, now)}" +
                (if rec.turnStartedAt > 0 then s" (turn started ${fmtDuration(now - rec.turnStartedAt)} ago)" else ""),
              // Block 3（§3.4）：loop-guard 计数详情
              s"loop: streak=${rec.loopStreak} rounds=${rec.loopRounds}" +
                (if rec.loopStreak >= 3 then " ⚠ same call failing repeatedly" else ""),
              s"startedAt: ${if rec.startedAt > 0 then fmtMillis(now - rec.startedAt) + " ago" else "(unknown)"}",
              // idle 列语义收紧（2026-09-10）：agent 侧事件停滞时长——进程侧
              // 活性（processActivityMs）刻意不在此展示（避免把进程动静读成进展）。
              s"lastActivity: ${if rec.lastActivityMs > 0 then fmtMillis(now - rec.lastActivityMs) + " ago (agent-side)" else "(never)"}",
              s"rootSessionId: ${rec.rootSessionId}",
              s"parentSessionId: ${if rec.parentSessionId.nonEmpty then rec.parentSessionId else "-"}",
              s"supervised: ${rec.supervisorRef.isDefined}",
              // issue #31 Fix D: barrier snapshot (phantom visibility)
              s"barrier: outstanding=${rec.outstandingSubagents} held=${rec.pendingEventCount}" +
                (if rec.outstandingSubagents > 0 then
                   " ⚠ outstanding > 0 — if no batch is actually in flight this is a phantom slot"
                 else "")
            ) ++ taskOpt.map { t =>
              List(
                s"task.description: ${t.description}",
                s"task.status: ${t.status}",
                s"task.retryCount: ${t.retryCount}",
                s"task.prompt: ${t.prompt.take(200)}${if t.prompt.length > 200 then "…" else ""}",
                s"task.lastError: ${t.lastError.getOrElse("-")}"
              )
            }.getOrElse(List("task record: (none — not a Delegate/SubTask task, or file pruned)"))
            // Block 2 §C3 语义对齐：能到达 status body 的调用者只有两类——root 桶
            // （callerIsRoot=true）或已过子树门的 Manager（callerIsRoot=false 且
            // anchors 命中）——对 Team 目标分别对应 callerIsRoot / 直接父放行，
            // manage 行不得再用无 caller 语境的 kindRejection 恒显 read-only。
            val teamRestartable = rec.kind == AgentKind.Team
            val manage = kindRejection(rec, "cancel", callerIsDirectParent = !callerIsRoot, callerIsRoot = callerIsRoot) match
              case Some(_) => "manageable: no (read-only kind)"
              case None =>
                "manageable: cancel" +
                  (if RestartableKinds.contains(rec.kind) || teamRestartable then " / restart" else " only")
            Right((lines :+ manage).mkString("\n"))
          }
        case None =>
          resources.subAgentTaskStore.findByTaskId(sessionId).map {
            case Some(t) if t.status == "running" || t.status == "restarting" =>
              Right(
                s"Orphan task: no live actor for '$sessionId' but the task file still says status=${t.status} " +
                  s"(agent=${t.agentName}, parent=${t.parentSessionId}). The actor died without a terminal event " +
                  "(e.g. gateway restart). It can be ignored — startup recovery / pruning will clean it — or the " +
                  "task can be re-delegated."
              )
            case Some(t) =>
              Right(s"Session '$sessionId' is no longer live. Task record: status=${t.status}, retries=${t.retryCount}, lastError=${t.lastError.getOrElse("-")}.")
            case None =>
              Left(ToolError(s"No agent or task record with sessionId='$sessionId'. Run AgentControl(action=list) to see live sessions."))
          }
    }

  /** Cancel 终止任务终态（区别于 Interrupt 停当前 turn）。public：WS cancelAgent
    * handler（子 agent 管理面板）复用同链路——supervisorRef 优先（Cancelled →
    * notifyParentAndStop，barrier 正确释放），无 supervisor 走降级兜底
    * （Stop + 自补通知 + taskStore cancelled + registry 移除）。
    *
    * notifyWs：面板实时终态帧出口（Sub-Agents 面板取消实时刷新修复）——node-*
    * Project 会话经此补发 agentDone 同构帧（dispatcher-* 不在此发：其观察桥
    * 拆除点 ProjectActor 统一补发，覆盖含 Failed/watcher giveUp 的全部路径，
    * 避免双发）。工具路径传 ctx.wsSend、面板路径传连接 wsSend；None = 静默
    * （既有测试/无 WS 语境零改动）。 */
  def doCancel(
      resources: SharedResources,
      rec: AgentRecord,
      reason: String,
      by: String = "the user panel",
      notifyWs: Option[io.circe.Json => IO[Unit]] = None
  ): IO[Either[ToolError, String]] =
    val reasonSuffix = if reason.nonEmpty then s" — $reason" else ""
    rec.supervisorRef match
      case Some(sup) =>
        // 正路：supervisor 处理 Cancelled → notifyParentAndStop("cancelled")
        // （父 ExternalEvent source 保持 delegate/subtask → barrier 释放）。
        // Project flow 会话（node-/dispatcher-）的 supervisor 是会话观察桥：
        // Cancelled → 清 registry + 停 agent（node 另经 engine cancelNode 落
        // status=cancelled，结果不投递）——无父通知/任务文件语义。
        (sup ! AgentEvent.Cancelled(rec.sessionId, reason)).flatMap { _ =>
          val panelFrame =
            if rec.kind == AgentKind.Flow && rec.sessionId.startsWith(nebflow.core.project.NodeEngine.SessionPrefix)
            then
              notifyWs.fold(IO.unit)(ws =>
                nebflow.core.node.NodeRunner
                  .emitSubagentPanelDone(ws, rec.sessionId, rec.rootSessionId)
                  .handleErrorWith(_ => IO.unit)
              )
            else IO.unit
          panelFrame *>
            IO.pure(
              Right(
                if rec.kind == AgentKind.Flow then
                  s"Cancel sent for Project session '${rec.sessionId}' — it will be unregistered and the agent stopped" +
                    (if rec.sessionId.startsWith(nebflow.core.project.NodeEngine.SessionPrefix) then
                       "; the node settles to status=cancelled and its result is NOT delivered"
                     else "") + "."
                else
                  s"Cancel sent to supervisor for '${rec.sessionId}'. The parent session will receive a cancelled " +
                    s"notification and the task file will be marked cancelled. Terminal state settles " +
                    s"asynchronously (if the agent completes at the same moment, whichever terminal event is " +
                    s"processed first wins)."
              )
            )
        }
      case None =>
        // 降级路径（spec §3.2 兜底）：Ephemeral（bridge death-watch 兜底回收）或
        // 无 supervisor 记录的旧记录——直接 Stop + 自补通知 + registry 移除。
        val source = rec.kind match
          case AgentKind.SubTask => "subtask"
          case AgentKind.Delegate => "delegate"
          case _ => "background-task"
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
          if rec.kind != AgentKind.Ephemeral && rec.parentSessionId.nonEmpty then
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
        notify *> taskUpdate *> resources.agentRegistry.update(_ - rec.sessionId) *>
          (rec.ref ! AgentCommand.Stop(s"agent-control-cancel$reasonSuffix")).as(
            Right(
              s"Terminated '${rec.sessionId}' directly (no supervisor on record — fallback path). " +
                "Registry entry removed; parent notified if applicable."
            )
          )

  /** F3(b) 如实化（loop-detected 报告 §6-F3，2026-08-30）：Team 成员 restart
    * 只重建会话（持久化 history 恢复），**turn 不自动续跑**——旧文案「resumes
    * from the last persisted checkpoint」是误导（checkpoint 续跑是 Delegate/
    * SubTask supervisor 分支的真实机制，Team 分支没有）。 */
  private[tools] def teamRestartSuccessText(sessionId: String): String =
    s"Restart sent for Team member '$sessionId': stopped and re-activated from persisted history " +
      "(parent-restart). Turn NOT auto-resumed (turn 未自动续跑) — re-dispatch the task if it should continue."

  private[tools] def teamRestartDeadText(sessionId: String): String =
    s"Team member '$sessionId' did not stop within 5s — restart aborted (no respawn to avoid double-activation)."

  def doRestart(
    resources: SharedResources,
    ctx: ToolContext,
    rec: AgentRecord,
    reason: String
  ): IO[Either[ToolError, String]] =
    if rec.kind == AgentKind.Team then
      // v2 升级链父重启（§5.3.3 Team 成员分支）：Stop 旧 actor → 等死（防同名
      // 双活）→ 复用 MailTool.activateAgent 以 history 重建（断点续跑，turn 从
      // lastDispatch checkpoint 继续）。Team 成员无 supervisor——父就是恢复载体。
      (ctx.actorSystem, ctx.sharedResources) match
        case (Some(system), Some(_)) =>
          val deadline = System.currentTimeMillis() + 5000L
          def waitDead: IO[Boolean] =
            system.isAlive(rec.ref.path).flatMap {
              case false => IO.pure(true)
              case true if System.currentTimeMillis() >= deadline => IO.pure(false)
              case true => IO.sleep(200.millis) *> waitDead
            }
          for
            _ <- rec.ref ! AgentCommand.Stop(
              s"parent-restart${if reason.nonEmpty then s": $reason" else ""}"
            )
            dead <- waitDead
            refOpt <- if dead then MailTool.activateAgent(rec.sessionId, resources, system, ctx) else IO.pure(None)
          yield
            if !dead then Left(ToolError(teamRestartDeadText(rec.sessionId)))
            else if refOpt.isDefined then Right(teamRestartSuccessText(rec.sessionId))
            else
              Left(
                ToolError(
                  s"Team member '${rec.sessionId}' stopped but re-activation failed (session or agent def not found)."
                )
              )
        case _ =>
          IO.pure(
            Left(
              ToolError(
                s"Team member restart requires a live actor system (missing in this tool context)."
              )
            )
          )
    else if rec.supervisorRef.isEmpty then
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
          for
            _ <- resources.subAgentTaskStore
              .updateStatus(parentSid, rec.sessionId, "restarting")
              .handleErrorWith(e => logger.warn(s"restart taskStore update failed: ${e.getMessage}"))
            _ <- rec.ref ! AgentCommand.Stop(
              s"agent-control-restart${if reason.nonEmpty then s": $reason" else ""}"
            )
          yield Right(
            s"Restart sent for '${rec.sessionId}': the stuck turn is terminated now; the supervisor will " +
              s"respawn it after a short backoff (~5s) and resume from the last persisted checkpoint " +
              s"(completed work is kept). NOTE: manual restarts consume the supervisor's circuit-breaker " +
              s"budget — after 2 restarts within 5 minutes the task fails with a notification. " +
              s"Current task retryCount: ${task.retryCount}."
          )
      }

end AgentControlTool
