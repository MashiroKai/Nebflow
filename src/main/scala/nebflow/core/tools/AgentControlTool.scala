package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.JsonObject
import nebflow.agent.*
import nebflow.shared.Defaults

import scala.concurrent.duration.*

/**
 * AgentControl — Nebula 专用后台 agent 管控（spec v1.1，2026-08-19）。
 *
 * 起因：派出的 sub-agent 6 小时无产出只能干等——Nebula 缺主动「查看/取消/重启」
 * 手段，TaskStuckWatcher 只覆盖 Processing 卡死一类。
 *
 * 四操作：
 *  - list    全部后台 agent 表格（kind/status/stuck?/up/idle/retries/task）
 *  - status  单个详情卡片 + 孤儿任务检测（registry 无记录但 task=running）
 *  - cancel  终止任务：supervisor 路径（AgentEvent.Cancelled → notifyParentAndStop
 *            "cancelled"，barrier 正确释放）/ 降级 Stop 路径（Ephemeral bridge
 *            watch 兜底 / 旧记录）
 *  - restart 断点重启：Stop → BackoffSupervisor Terminated → 恢复持久化消息续跑
 *            （复用 #317 crash resume，零新机制），消耗 supervisor 熔断额度
 *
 * 安全边界（spec §4 矩阵，严格）：
 *  - cancel 白名单：Delegate / SubTask / Ephemeral；Flow 指引 cancelFlow；
 *    Team 一期只读；Root/自身拒绝（自杀守卫）
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
- **list**: table of all live background agents (kind, status, stuck?, uptime, idle time, retry count, task). Use this FIRST when a sub-agent is silent or you suspect it is stuck — "stuck?" marks Processing agents with no activity for >10min (same threshold as the automatic watcher).
- **status**: full detail for one agent (pass sessionId from list): state, timings, task prompt excerpt, retry count, last error. Also detects orphan task files (actor gone but task still marked running).
- **cancel**: terminate an agent's current task. The parent session receives a "cancelled" notification and any wait barrier is released — nobody waits forever. Allowed kinds: Delegate, SubTask, Ephemeral.
- **restart**: kill the stuck turn and resume from the last persisted checkpoint (same mechanism as crash recovery — completed work is kept). Allowed kinds: Delegate (ephemeral), SubTask. Consumes the supervisor's restart budget (2 per 5min; exceeding it fails the task).

Safety rules: you cannot cancel/restart yourself, Root sessions, Team members (read-only for now — killing them mid-collaboration breaks the team state machine), or Flow workers (flow cancellation goes through cancelFlow / RunningFlowRegistry). Only sessions under your own root session are controllable.

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
          "description" -> "Optional for cancel/restart. Recorded into the task file (lastError) and the parent notification for traceability.".asJson
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

  /** Cancelable kinds（WS cancelAgent handler 复用同一白名单——子 agent 管理面板
    * 与工具层一致：Team/Flow/Root 只读）。 */
  val CancelableKinds: Set[AgentKind] = Set(AgentKind.Delegate, AgentKind.SubTask, AgentKind.Ephemeral)
  private val RestartableKinds: Set[AgentKind] = Set(AgentKind.Delegate, AgentKind.SubTask)

  private def kindRejection(kind: AgentKind, action: String, callerIsDirectParent: Boolean = false): Option[String] =
    kind match
      case AgentKind.Flow =>
        Some(
          "Flow workers follow the DAG lifecycle — cancelling one directly would break the flow state machine. " +
            "Use flow-level cancellation instead (cancelFlow(instanceId) / RunningFlowRegistry)."
        )
      case AgentKind.Team =>
        // v2 升级链父重启（§5.3.3）：直接父（Manager/owner）对自有 Team 成员
        // restart/cancel 放行——升级链的决策载体；其他调用者保持只读。
        if callerIsDirectParent then None
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

  private def isStuck(rec: AgentRecord, now: Long): Boolean =
    rec.status == AgentStatus.Processing &&
      rec.lastActivityMs > 0 &&
      now - rec.lastActivityMs > Defaults.StuckThresholdMs

  // ── actions ───────────────────────────────────────────────

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    ctx.sharedResources match
      case None => IO.pure(Left(ToolError("AgentControl requires SharedResources (no live runtime in this context)")))
      case Some(resources) =>
        val action = input("action").flatMap(_.asString).getOrElse("")
        val sessionId = input("sessionId").flatMap(_.asString).getOrElse("")
        val reason = input("reason").flatMap(_.asString).getOrElse("")

        action match
          case "list" => doList(resources)
          case "status" =>
            if sessionId.isEmpty then IO.pure(Left(ToolError("status requires sessionId (run list first to get one)")))
            else doStatus(resources, sessionId)
          case "cancel" =>
            if sessionId.isEmpty then IO.pure(Left(ToolError("cancel requires sessionId (run list first to get one)")))
            else withGuardedRecord(resources, ctx, sessionId, "cancel")((rec, _) => doCancel(resources, rec, reason))
          case "restart" =>
            if sessionId.isEmpty then IO.pure(Left(ToolError("restart requires sessionId (run list first to get one)")))
            else withGuardedRecord(resources, ctx, sessionId, "restart")((rec, _) => doRestart(resources, ctx, rec, reason))
          case other =>
            IO.pure(
              Left(ToolError(s"Unknown action '$other'. Supported: list, status, cancel, restart."))
            )

  /** 统一守卫（§4）：查无 / kind 白名单 / 自杀守卫 / rootSessionId 同桶。 */
  private def withGuardedRecord(resources: SharedResources, ctx: ToolContext, sessionId: String, action: String)(
    body: (AgentRecord, String) => IO[Either[ToolError, String]]
  ): IO[Either[ToolError, String]] =
    val callerSessionId = ctx.sessionId.getOrElse("")
    for
      registry <- resources.agentRegistry.get
      callerRoot = registry.get(callerSessionId).map(_.rootSessionId).filter(_.nonEmpty).getOrElse(callerSessionId)
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
          else if rec.rootSessionId.nonEmpty && callerRoot.nonEmpty && rec.rootSessionId != callerRoot then
            IO.pure(
              Left(
                ToolError(
                  s"Permission denied: session '$sessionId' belongs to root session '${rec.rootSessionId}', " +
                    s"yours is '$callerRoot'. You can only manage agents under your own root session."
                )
              )
            )
          else
            // v2 升级链父重启（§5.3.3）：直接父 = 调用者会话 == 目标记录的 parentSessionId
            // （Manager/owner 管理自有 Team 成员；Delegate/SubTask 的父同理）。
            val callerIsDirectParent = rec.parentSessionId.nonEmpty && rec.parentSessionId == callerSessionId
            kindRejection(rec.kind, action, callerIsDirectParent) match
              case Some(msg) => IO.pure(Left(ToolError(msg)))
              case None => body(rec, callerRoot)
    yield result

  private def doList(resources: SharedResources): IO[Either[ToolError, String]] =
    for
      registry <- resources.agentRegistry.get
      runningTasks <- resources.subAgentTaskStore.findRunningTasks
      taskMap = runningTasks.map(t => t.taskId -> t).toMap
      now = System.currentTimeMillis()
      rows = registry.values.toList
        .sortBy(r => if r.startedAt > 0 then r.startedAt else Long.MaxValue)
        .map { rec =>
          val task = taskMap.get(rec.sessionId)
          val agent = task.map(_.agentName).getOrElse(agentNameFromSessionId(rec.sessionId))
          val stuck = if isStuck(rec, now) then s"⚠ ${fmtMillis(now - rec.lastActivityMs)}" else "no"
          val readOnly = if !CancelableKinds.contains(rec.kind) then " （read-only）" else ""
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
            rec.status.toString,
            stuck,
            barrier,
            fmtMillis(if rec.startedAt > 0 then now - rec.startedAt else 0),
            fmtMillis(if rec.lastActivityMs > 0 then now - rec.lastActivityMs else 0),
            task.map(_.retryCount.toString).getOrElse("-"),
            if taskLabel.nonEmpty then taskLabel.take(48) else "-"
          )
        }
    yield
      val header = List("sessionId", "kind", "agent", "status", "stuck?", "barrier", "up", "idle", "retries", "task")
      val table = (header :: rows).map(r => "| " + r.mkString(" | ") + " |").mkString("\n")
      val summary =
        if rows.isEmpty then "No live background agents (registry is empty)."
        else
          s"""Background agents (${rows.size}):
             |
             |$table
             |
             |stuck? = Processing with no activity for >${Defaults.StuckThresholdMs / 60000}min (same threshold as the automatic watcher).
             |barrier = outstandingSubagents/heldResults (issue #31): outstanding>0 while idle with no in-flight work = phantom slot
             |          (a batch member hung or died without a terminal event — held results never inject until restart).
             |Cancelable kinds: Delegate / SubTask / Ephemeral. Restartable: Delegate(ephemeral) / SubTask. Team/Flow/Root are read-only.""".stripMargin
      Right(summary)

  private def doStatus(resources: SharedResources, sessionId: String): IO[Either[ToolError, String]] =
    resources.agentRegistry.get.flatMap { registry =>
      val now = System.currentTimeMillis()
      registry.get(sessionId) match
        case Some(rec) =>
          resources.subAgentTaskStore.findByTaskId(sessionId).map { taskOpt =>
            val lines = List(
              s"sessionId: ${rec.sessionId}",
              s"kind: ${rec.kind}",
              s"status: ${rec.status}",
              s"stuck: ${if isStuck(rec, now) then s"YES (idle ${fmtMillis(now - rec.lastActivityMs)})" else "no"}",
              s"startedAt: ${if rec.startedAt > 0 then fmtMillis(now - rec.startedAt) + " ago" else "(unknown)"}",
              s"lastActivity: ${if rec.lastActivityMs > 0 then fmtMillis(now - rec.lastActivityMs) + " ago" else "(never)"}",
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
            val manage = kindRejection(rec.kind, "cancel") match
              case Some(_) => "manageable: no (read-only kind)"
              case None => "manageable: cancel" + (if RestartableKinds.contains(rec.kind) then " / restart" else " only")
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
    * （Stop + 自补通知 + taskStore cancelled + registry 移除）。 */
  def doCancel(resources: SharedResources, rec: AgentRecord, reason: String): IO[Either[ToolError, String]] =
    val reasonSuffix = if reason.nonEmpty then s" — $reason" else ""
    rec.supervisorRef match
      case Some(sup) =>
        // 正路：supervisor 处理 Cancelled → notifyParentAndStop("cancelled")
        // （父 ExternalEvent source 保持 delegate/subtask → barrier 释放）。
        (sup ! AgentEvent.Cancelled(rec.sessionId, reason)).flatMap { _ =>
          IO.pure(
            Right(
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
            payload = s"\"${rec.sessionId}\": cancelled by Nebula via AgentControl$reasonSuffix",
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
                lastError = Some(s"cancelled by Nebula via AgentControl$reasonSuffix")
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
            if !dead then
              Left(
                ToolError(
                  s"Team member '${rec.sessionId}' did not stop within 5s — restart aborted (no respawn to avoid double-activation)."
                )
              )
            else if refOpt.isDefined then
              Right(
                s"Restart sent for Team member '${rec.sessionId}': stopped and re-activated from history " +
                  "(parent-restart). Turn resumes from the last persisted checkpoint."
              )
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
