package nebflow.core.tools

import cats.effect.IO
import cats.syntax.foldable.toFoldableOps
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.scheduler.{DoneRecord, ScheduledTask, ScheduledTaskService, ScheduledTaskStore}

import java.time.*

object ScheduleTool extends Tool:
  val name = "Schedule"

  val description =
    """Schedule a task to fire at a specified time, optionally recurring — or keep a persistent
todo (待办) on the USER's inbox until they write it off — or manage existing entries
(list / cancel / done). At the scheduled time the content is injected into your conversation
as an instruction — you then execute it.

Semantic split (two faces, one ledger):
- kind="task" = YOUR reminder: fires into your conversation at the time — the agent face
  (unchanged semantics).
- kind="todo" = the USER's face: it lands in the user's todo inbox (消息列表 → 待办事项),
  surfaces in the daily morning digest on your side, and waits there until the user checks
  it off or cancels it. The user answers WHEN FREE — never expect an immediate reply to a
  todo. Non-blocking decisions/approvals belong here, NOT in an AskUser popup.

## When to Use

- 定时汇报 — schedule a progress report for a specific time ("at 17:00, summarize today's work")
- 定时维护 — periodic maintenance ("every Monday morning, review and clean up memory")
- 闲时执行 — defer a non-urgent task to an idle window ("at 23:00 when the user is idle, consolidate memory")
- 高峰规避 — avoid peak LLM pricing hours ("at 09:00 tomorrow, run the batch analysis")
- 用户提醒 — remind the user about something at a future time
- 待办 — the user says "记个待办/别忘了 X/记得做 X" (no fixed time): create with kind="todo".
  Stored engine-side (survives session compaction AND restarts, like scheduled tasks) and
  re-injected every morning at 09:00 as one digest until done/cancelled. Persist it — never
  let an unfinished todo rot in context memory.
- 待决策/待批准（非阻塞）— you need the user's decision or approval but CAN keep working
  without it: create with kind="todo" (content = the decision to make). It appears in the
  user's todo inbox; they batch-approve when free. Use AskUserQuestion ONLY when truly
  blocked (the next step is impossible without the answer).

## Actions (optional `action` field, default "create")

- **create** (default): schedule new content at triggerAt (kind="task", default) or register
  a todo (kind="todo", no triggerAt needed). If `name` is set, creating with a name that
  already exists REPLACES the existing entry of the same kind instead of stacking a duplicate
  (upsert). ALWAYS use a fixed, descriptive name for routine/recurring tasks (e.g.
  "dream-daily-review") — re-arming the same routine after a restart then converges to a
  single copy instead of stacking duplicates.
- **list**: show ALL pending entries across all sessions — scheduled tasks (id, trigger time,
  repeat, status active/PAUSED) AND todos (id, created date, content). Call this before
  re-arming a routine task: if it already exists, don't create a copy.
- **cancel**: remove an entry by id (works across sessions, both kinds). A cancelled scheduled
  task never fires; a cancelled todo never surfaces again. It disappears from list immediately.
- **done**: write off a TODO by id after completing it (todos only — for a scheduled task
  use cancel). The written-off todo is removed from storage and never surfaces again.
- **edit**: modify an existing entry by id — content and/or project label (字段级 patch,
  提供的字段才改; project 传 "" 清空归属 = 未分组). Works across sessions, both kinds.

## Fields

- **action** (optional): "create" (default) | "list" | "cancel" | "done"
- **kind** (create, optional): "task" (default) | "todo". "task" = timed task, requires
  triggerAt. "todo" = no fixed time — triggerAt is ignored; the engine surfaces it in the
  daily morning digest (09:00) until done/cancelled.
- **content** (create): Task content — a natural-language instruction describing what you
  should do when the task fires / what the todo is. Write it as a direct command ("汇报 XX 进度",
  "买牛奶", "review the latest commits"). Include any necessary context (which project, what scope).
- **triggerAt** (create, required for kind="task"): When the task should fire. A JSON integer
  (epoch ms) or a QUOTED JSON string — NEVER a bare unquoted ISO literal (that is invalid JSON).
  Must be in the future. Accepted string forms, in parser priority order:
  1. "1791612000000" — epoch ms as a 13-digit digit-string (= 2026-10-10T14:00:00+08:00)
  2. ISO 8601 WITH zone — "2026-10-10T14:00:00+08:00" or "2026-10-10T06:00:00Z" (safest —
     no ambiguity; use dashes, never slashes)
  3. ISO 8601 / date WITHOUT zone — "2026-10-10T14:00", "2026-10-10 14:00",
     "2026-10-10 14:00:30", "2026-10-10" — interpreted in the system timezone
  4. Natural language, exactly these shapes: "21:30" / "at 21:30" (today, or tomorrow if
     already past), "tomorrow" (= 09:00), "tomorrow at 21:00", "in 30 minutes",
     "in 2 hours", "in 1 day", "in 90m", "7pm" / "7:30 am" / "noon" / "midnight",
     weekday with optional time — "friday", "next monday 9:00", "monday 7pm",
     "friday 21:00" (next strictly-future occurrence, default 09:00), and Chinese
     forms — "周一" / "周日下午3点" / "明天下午3点" / "后天上午9点" / "今天 21:30" /
     "晚上11点半".
  NOT supported — convert to a supported form yourself before calling: "next week",
  slash dates ("2026/10/10"), "3 hours later", relative weekday ("in 2 days at 5pm" —
  use "in 2 days"). Only the bare "HH:mm" / "H点" / am-pm forms auto-roll to the
  next day when already past; an absolute form lying in the past is REJECTED — roll
  it forward yourself.
- **condition** (create, optional, kind="task" only): CONDITIONAL trigger (哨兵语义 —
  the task fires only when the condition is TRUE at trigger time; "CI 转红才叫" not
  "几点都叫"). Three forms, dispatched by prefix:
  1. bare `sh -c` one-liner PROBE — fires when it exits 0 (e.g. "test -f /tmp/ci-red && exit 0 || exit 1").
     Use absolute paths; keep it fast and side-effect-free (60s hard timeout = unmet).
  2. "git-tip:<abs-repo-path>[#<branch>]" — STATEFUL sentinel: fires when the branch tip
     CHANGED since the last check (first evaluation only records a baseline and does not fire).
     e.g. "git-tip:/Users/me/repo#main" — "branch has new commits only then wake".
  3. "task-terminal:<project>:<nodeId>" — fires when that project's flow-map node reaches a
     terminal state (completed/failed/cancelled/blocked) — "指定的 delegate/任务收尾了才叫".
  When unmet: repeating tasks silently skip to the next cycle; one-shots silently re-check
  every 30 minutes for up to 24h, then auto-PAUSE (visible in list as PAUSED). NOTHING is
  injected while the condition is false — no placeholder messages.
- **repeat** (create, optional, kind="task" only): "hourly" | "daily" | "weekly" — omit for a
  one-shot task (a literal "once" is tolerated and treated as omitted). This is NOT cron: the
  next fire = previous triggerAt + 1h / 24h / 7d, so the clock time of your FIRST triggerAt is
  the anchor — set it to the next occurrence of the wanted time (e.g. an every-day-09:00
  routine armed at 14:00 → triggerAt "tomorrow at 09:00", repeat "daily").
- **name** (create, recommended): Stable identifier for upsert (within the same kind). Same
  name + kind replaces the old entry (across all sessions). Required practice for
  recurring/routine tasks; omit for one-offs.
- **project** (create/edit, optional): Project label the entry belongs to ("DOSTEL",
  "stand"…) — the user's todo inbox groups and filters by it, so ALWAYS tag a todo with its
  project when one is obvious from context. Omit = ungrouped; on edit, "" clears the label.
- **id** (cancel/done/edit): Entry id — from action=list.

## Establish "now" before calling (top failure cause)

Your context does NOT show the current date/time — never invent an absolute date.
- Easiest: use a relative form ("in 30 minutes", "21:30") — no date arithmetic needed, and
  a past "HH:mm" rolls to tomorrow automatically.
- For an absolute timestamp, first get the real clock: Bash {command:"date '+%s %z'"} gives
  epoch SECONDS (×1000 = ms) and the local UTC offset (e.g. +0800 → "+08:00").
- Every Schedule rejection embeds the server's current time (formatted; parse errors also
  carry raw epoch ms and zone id) — recalibrate from that line instead of guessing again.

## Examples

{"action":"create", "content":"汇总今日 commit 并向用户汇报进度", "triggerAt":"in 30 minutes", "name":"evening-commit-digest"}
{"action":"create", "content":"整理记忆并输出明日三件事", "triggerAt":"tomorrow at 09:00", "repeat":"daily", "name":"morning-review"}
{"action":"create", "content":"提醒用户 review PR #42", "triggerAt":"2026-10-10T17:00:00+08:00", "name":"pr42-review-reminder"}
{"action":"create", "content":"检查 CI 状态并向用户汇报", "triggerAt":"21:30", "repeat":"hourly", "condition":"test -f /tmp/ci-red && exit 0 || exit 1", "name":"ci-red-watch"}
{"action":"create", "kind":"todo", "content":"用户说过要把周报模板整理进 memory", "name":"weekly-report-template"}
{"action":"create", "kind":"todo", "content":"等 CI 绿后合并 release 分支（需用户批准）", "name":"release-merge-approval", "project":"stand"}
{"action":"edit", "id":"af17698a", "content":"改为：整理周报模板进 design/", "project":"stand"}
{"action":"list"}
{"action":"done", "id":"af17698a"}
{"action":"cancel", "id":"af17698a"}

## Common Mistakes (each costs a wasted round-trip)

- Invented absolute date (you cannot see today) → use a relative form, or `date '+%s %z'` first.
- Bare unquoted ISO literal in the arguments JSON → always a number or a quoted string.
- Absolute triggerAt in the past → only "HH:mm" / "H点" / am-pm auto-roll; roll absolute forms forward yourself.
- repeat "weekly" assumed to mean "every Monday" → it means +7d from your anchor; put the
  first triggerAt on the wanted weekday (or just use "friday" — weekday forms pick the next
  occurrence for you).
- Routine duplicated after a restart → you omitted `name`, so upsert could not converge it.
- action="done" on a scheduled task id → done is todo-only; use cancel for scheduled tasks.
- Condition probe that always exits 0 → the task fires unconditionally; a probe that never
  does → one-shot goes PAUSED after 24h. Test the probe with Bash first.

## Notes

- Scheduled tasks fire as an ExternalEvent in the current session; the injected content is
  the instruction you receive.
- For recurring tasks, the next fire time is computed from the previous triggerAt (not wall clock), maintaining consistent intervals. If the engine was down across one or more whole periods, the missed periods are NOT replayed one-by-one — the first fire after recovery is marked 补跑 (make-up run; one-shots missed across downtime are marked 错过后补跑) and the schedule snaps forward to the next future slot.
- 例程自检（idle-routine self-check): if a recurring routine fires N=3 consecutive times
  with NO tool activity in its session since the previous fire, the engine auto-downfreqs
  it one step (hourly→daily→weekly) and flags it 「待审查」 (visible in list). Restore by
  re-arming the same name (upsert resets flag and counter) or cancel it. Dead routines
  must not keep nagging.
- Todos re-surface in a daily morning digest (engine-side, all unfinished todos in one
  message); the digest rides the same ExternalEvent channel, so it also lands on the next
  idle turn. Todo persistence equals scheduled-task persistence (same engine storage)."""

  private val validRepeat = Set("hourly", "daily", "weekly")
  private val validActions = Set("create", "list", "cancel", "done", "edit")

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "action" -> Json.obj(
          "type" -> "string".asJson,
          "enum" -> Json.arr("create".asJson, "list".asJson, "cancel".asJson, "done".asJson, "edit".asJson),
          "description" -> "create (default) = schedule a task or register a todo; list = show all pending entries across sessions; cancel = remove an entry by id; done = write off a completed todo by id; edit = modify an existing entry by id (content/project)".asJson
        ),
        "kind" -> Json.obj(
          "type" -> "string".asJson,
          "enum" -> Json.arr("task".asJson, "todo".asJson),
          "description" -> "(create) \"task\" (default) = timed task, requires triggerAt. \"todo\" = persistent todo with NO fixed time — triggerAt is ignored; the engine re-injects it in a daily 09:00 digest until done/cancelled. Survives compaction and restarts like scheduled tasks.".asJson
        ),
        "content" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "(create) The instruction to execute when the task fires / the todo's content — a direct, self-contained command (\"汇报今日 commit 进度\"); include whatever context it needs.".asJson
        ),
        "triggerAt" -> Json.obj(
          "type" -> "string".asJson,
          "description" ->
            """(create, required for kind="task") When the task fires. Schema type is string, but a bare JSON integer (epoch ms) is also accepted — never a bare unquoted ISO literal (invalid JSON). String forms: "2026-10-10T14:00:00+08:00" (ISO with zone; dashes not slashes), "tomorrow at 09:00", "in 30 minutes", "21:30" (auto-rolls to tomorrow when already past). Must be in the future — if unsure of today's date, use a relative form or get the time via Bash first. Ignored for kind="todo".""".asJson
        ),
        "repeat" -> Json.obj(
          "type" -> "string".asJson,
          "enum" -> Json.arr("hourly".asJson, "daily".asJson, "weekly".asJson),
          "description" -> "(create, kind=\"task\" only) \"hourly\" | \"daily\" | \"weekly\". Omit for one-shot (\"once\" is tolerated = omitted). Not cron: next fire = previous triggerAt + 1h/24h/7d — the first triggerAt's clock time is the anchor, so set it to the next occurrence of the wanted time.".asJson
        ),
        "name" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "(create) Stable identifier — same name + kind replaces the existing entry instead of stacking (upsert). Use a fixed name for routine/recurring tasks.".asJson
        ),
        "project" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "(create/edit) Project label the entry belongs to (e.g. \"DOSTEL\", \"stand\") — the todo inbox groups and filters by it. Omit = ungrouped; on edit, pass \"\" to clear the label.".asJson
        ),
        "condition" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "(create, kind=\"task\" only) conditional trigger — fires only when TRUE at trigger time. Forms: bare `sh -c` probe (exit 0 = true; fast, absolute paths, 60s timeout = unmet); \"git-tip:<repoPath>[#<branch>]\" (stateful sentinel — fires when branch tip changed since last check); \"task-terminal:<project>:<nodeId>\" (fires when that flow-map node reaches a terminal state). Unmet: repeating tasks skip silently to the next cycle; one-shots re-check every 30 min for up to 24h then auto-PAUSE. Nothing is injected while unmet.".asJson
        ),
        "id" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "(cancel/done/edit) Entry id — from action=list.".asJson
        )
      )
      // required 刻意留空（2026-09-06）：action=list/cancel 不携带 content/triggerAt，
      // 分 action 的必填校验在 call 内做并给可自愈错误。
    )
  )

  def summarize(input: JsonObject): String =
    val action = input("action").flatMap(_.asString).getOrElse("create")
    action match
      case "list" => "Schedule(list)"
      case "cancel" =>
        s"Schedule(cancel ${input("id").flatMap(_.asString).getOrElse("?")})"
      case "done" =>
        s"Schedule(done ${input("id").flatMap(_.asString).getOrElse("?")})"
      case "edit" =>
        s"Schedule(edit ${input("id").flatMap(_.asString).getOrElse("?")})"
      case _ =>
        val content = input("content").flatMap(_.asString).getOrElse("")
        val kind = input("kind").flatMap(_.asString).getOrElse("task")
        val repeat = input("repeat").flatMap(_.asString).getOrElse("once")
        val triggerAt = input("triggerAt").flatMap(_.asNumber.flatMap(_.toLong)).getOrElse(0L)
        val nameTag = input("name").flatMap(_.asString).filter(_.nonEmpty).map(n => s" name=$n").getOrElse("")
        val condTag = if input("condition").flatMap(_.asString).exists(_.trim.nonEmpty) then " cond" else ""
        if kind == "todo" then s"Schedule(todo$nameTag: $content)"
        else s"Schedule(create$nameTag$condTag $repeat @ $triggerAt: $content)"

    end match

  end summarize

  def summarizeResult(input: JsonObject, result: String): String =
    val action = input("action").flatMap(_.asString).getOrElse("create")
    action match
      case "list" =>
        if result.startsWith("Pending") || result.toLowerCase.contains("no pending") then "listed" else "failed"
      case "cancel" => if result.startsWith("Cancelled") then "cancelled" else "failed"
      case "done" => if result.startsWith("Done") then "done" else "failed"
      case "edit" => if result.startsWith("Updated") then "edited" else "failed"
      case _ =>
        if result.startsWith("Scheduled task") then if result.contains("replaced") then "replaced" else "scheduled"
        else if result.startsWith("Todo") then
          if result.contains("replaced") then "todo-replaced" else "todo-registered"
        else "failed"

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val action = input("action").flatMap(_.asString).getOrElse("create")
    if !validActions.contains(action) then
      IO.pure(
        Left(
          ToolError(
            s"Unknown action \"$action\" — expected one of ${validActions.toList.sorted.mkString(", ")} " +
              "(omit action for create; use list to inspect, cancel/done by id)."
          )
        )
      )
    else
      ctx.sharedResources match
        case None => IO.pure(Left(ToolError("SharedResources not available")))
        case Some(sr) =>
          // Phase 5 D 步:scheduledTaskStore / scheduledTaskService 均为 core.scheduler
          // 类型——以底层值直传,不再以 agent 定位器类型流经本文件。
          val taskStore = sr.scheduledTaskStore
          val taskService = sr.scheduledTaskService
          action match
            case "list" => listAction(taskStore)
            case "cancel" => cancelAction(taskStore, taskService, input, ctx)
            case "done" => doneAction(taskStore, taskService, input, ctx)
            case "edit" => editAction(taskStore, taskService, input, ctx)
            case "create" =>
              ctx.sessionId match
                case None => IO.pure(Left(ToolError("No session ID available")))
                case Some(sessionId) =>
                  // 双面化（2026-10-24）：kind="todo" 走待办面（无 triggerAt，
                  // 每日浮出直至 done/cancel）；默认/缺省走定时任务面（现状零变化）。
                  val isTodo = input("kind").flatMap(_.asString).map(_.trim.toLowerCase).contains("todo")
                  if isTodo then createTodoAction(taskStore, taskService, input, ctx, sessionId)
                  else createAction(taskStore, taskService, input, ctx, sessionId)
            case other => IO.pure(Left(ToolError(s"Unhandled action $other"))) // unreachable (guarded above)
    end if
  end call

  // ---------------------------------------------------------------------------
  // action = list — all pending entries across ALL sessions, two sections
  // (定时任务 + 待办，双面化 2026-10-24)
  // ---------------------------------------------------------------------------

  private def listAction(taskStore: ScheduledTaskStore): IO[Either[ToolError, String]] =
    taskStore.getAllPendingTasks.map { tasks =>
      val tasksOnly = tasks.filterNot(_.isTodo)
      val todos = tasks.filter(_.isTodo)
      if tasksOnly.isEmpty && todos.isEmpty then Right("No pending scheduled tasks.")
      else
        val taskSection =
          if tasksOnly.isEmpty then None
          else
            val lines = tasksOnly.sortBy(_.triggerAt).map { t =>
              val status = if t.enabled then "active" else "PAUSED"
              val nm = t.name.getOrElse("-")
              // 条件触发标记：让 agent 一眼看出这是条件叫醒（探针命令截断展示）
              val cond = t.condition.map(c => s" | cond: ${c.take(40)}").getOrElse("")
              // 「待审查」标记（降频批 2026-10-29）：连续空转被引擎自动降频的
              // 例程挂此标——恢复 = 同名 re-arm（upsert 换新任务，标记归零），
              // 不再需要 = cancel 销账。
              val review = if t.reviewFlag then " | ⚠待审查" else ""
              s"- ${t.id} | ${formatTime(t.triggerAt)} | ${t.repeat.getOrElse("once")} | $status | name: $nm$cond$review | ${t.content.take(60)}"
            }
            Some(
              s"Pending scheduled tasks (${tasksOnly.size}) — cancel via Schedule {action:\"cancel\", id:\"<id>\"}:\n" +
                lines.mkString("\n") +
                (if tasksOnly.exists(_.reviewFlag) then "\n⚠待审查 = 该例程连续空转被自动降频；恢复：同名 create re-arm（标记与计数归零）；销账：cancel。"
                 else "")
            )
        val todoSection =
          if todos.isEmpty then None
          else
            val lines = todos.sortBy(t => (t.triggerAt, t.createdAt)).map { t =>
              val status = if t.enabled then "active" else "PAUSED"
              val nm = t.name.map(n => s" | name: $n").getOrElse("")
              val proj = t.project.map(p => s" | project: $p").getOrElse("")
              s"- ${t.id} | todo | created ${formatDate(t.createdAt)} | $status$nm$proj | ${t.content.take(60)}"
            }
            Some(
              s"Todos (${todos.size}) — 无固定时间，每日 09:00 浮出直至处理；完成 → {action:\"done\", id:\"<id>\"} 销账，" +
                s"不再需要 → {action:\"cancel\", id:\"<id>\"}:\n" + lines.mkString("\n")
            )
        Right((taskSection.toList ++ todoSection.toList).mkString("\n\n"))
      end if
    }

  /** yyyy-MM-dd local date — todo creation date display. */
  private def formatDate(epochMs: Long): String =
    java.time.format.DateTimeFormatter
      .ofPattern("yyyy-MM-dd")
      .withZone(java.time.ZoneId.systemDefault())
      .format(java.time.Instant.ofEpochMilli(epochMs))

  // ---------------------------------------------------------------------------
  // action = cancel — by id, across sessions, both kinds
  // ---------------------------------------------------------------------------

  private def cancelAction(
    taskStore: ScheduledTaskStore,
    taskService: Option[ScheduledTaskService],
    input: JsonObject,
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    val idOpt = input("id").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
    idOpt match
      case None =>
        IO.pure(
          Left(ToolError("cancel requires id — call Schedule {action:\"list\"} first, then pass the entry's id."))
        )
      case Some(id) =>
        taskStore.findTaskById(id).flatMap {
          case None =>
            taskStore.getAllPendingTasks.map { pending =>
              val ids = if pending.isEmpty then "(none)" else pending.map(_.id).mkString(", ")
              Left(
                ToolError(
                  s"No entry with id \"$id\". Pending ids: $ids — if this looks stale, call action=list to refresh."
                )
              )
            }
          case Some(t) =>
            for
              _ <- taskStore.deleteTask(t.sessionId, id)
              _ <- taskService match
                case Some(svc) => svc.notifyTaskChange()
                case None => IO.unit
              // Broadcast so the frontend reminder panel updates in real-time
              _ <- ctx.wsSend match
                case Some(send) =>
                  send(
                    Json.obj(
                      "type" -> "scheduledTaskDeleted".asJson,
                      "id" -> id.asJson,
                      "sessionId" -> t.sessionId.asJson
                    )
                  )
                case None => IO.unit
            yield
              // 双面化：定时任务保留逐字旧句式（"Cancelled task … will not fire"）；
              // 待办用同构句式（"Cancelled todo … will not surface again"）。
              val kindWord = if t.isTodo then "todo" else "task"
              val wasWhat = if t.isTodo then "todo" else formatTime(t.triggerAt)
              val willNote = if t.isTodo then "it will not surface again" else "it will not fire"
              Right(
                s"Cancelled $kindWord $id (was $wasWhat${t.repeat.map(r => s", $r").getOrElse("")}): " +
                  s"${t.content.take(60)} — $willNote."
              )
        }
    end match
  end cancelAction

  // ---------------------------------------------------------------------------
  // action = done — 销账待办（双面化 2026-10-24）：删除后永不再浮出
  // ---------------------------------------------------------------------------

  private def doneAction(
    taskStore: ScheduledTaskStore,
    taskService: Option[ScheduledTaskService],
    input: JsonObject,
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    val idOpt = input("id").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
    idOpt match
      case None =>
        IO.pure(Left(ToolError("done requires id — call Schedule {action:\"list\"} first, then pass the todo's id.")))
      case Some(id) =>
        taskStore.findTaskById(id).flatMap {
          case None =>
            taskStore.getAllPendingTasks.map { pending =>
              val ids = if pending.isEmpty then "(none)" else pending.map(_.id).mkString(", ")
              Left(
                ToolError(
                  s"No entry with id \"$id\". Pending ids: $ids — if this looks stale, call action=list to refresh."
                )
              )
            }
          case Some(t) if !t.isTodo =>
            IO.pure(
              Left(
                ToolError(
                  s"Id \"$id\" is a scheduled task (fires ${formatTime(t.triggerAt)}), not a todo — " +
                    "action=\"done\" writes off todos only; use action=\"cancel\" for scheduled tasks."
                )
              )
            )
          case Some(t) =>
            for
              _ <- taskStore.deleteTask(t.sessionId, t.id)
              // 认知二期产品批·打卡 done 历史：销账即留痕（只增 JSONL）——
              // 周报「本周打卡 N/M」的聚合数据源；删除行不留痕则无从聚合。
              _ <- ctx.sharedResources match
                case Some(sr) =>
                  sr.doneRecordStore.record(
                    DoneRecord(
                      taskId = t.id,
                      name = t.name,
                      content = t.content,
                      sessionId = t.sessionId,
                      doneAt = System.currentTimeMillis(),
                      via = DoneRecord.ViaTool
                    )
                  )
                case None => IO.unit
              _ <- taskService match
                case Some(svc) => svc.notifyTaskChange()
                case None => IO.unit
              // Broadcast so the frontend reminder panel updates in real-time
              _ <- ctx.wsSend match
                case Some(send) =>
                  send(
                    Json.obj(
                      "type" -> "scheduledTaskDeleted".asJson,
                      "id" -> t.id.asJson,
                      "sessionId" -> t.sessionId.asJson
                    )
                  )
                case None => IO.unit
            yield
              val nm = t.name.map(n => s" [name: $n]").getOrElse("")
              Right(
                s"Done: todo ${t.id}$nm written off (\"${t.content.take(60)}\") — removed from storage, it will not surface again."
              )
        }
    end match
  end doneAction

  // ---------------------------------------------------------------------------
  // action = edit — 增删改查补齐（改版批追加令 2026-10-31 23:14 ②）：按 id 改
  // 内容 / 改项目分组（done/cancel 已覆盖改状态销账与删除两态）。字段级 patch：
  // 提供的字段才改；project 传 "" = 清空归属（未分组）。跨会话（按 id 全库寻址）。
  // ---------------------------------------------------------------------------

  private def editAction(
    taskStore: ScheduledTaskStore,
    taskService: Option[ScheduledTaskService],
    input: JsonObject,
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    val idOpt = input("id").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
    idOpt match
      case None =>
        IO.pure(Left(ToolError("edit requires id — call Schedule {action:\"list\"} first, then pass the entry's id.")))
      case Some(id) =>
        taskStore.findTaskById(id).flatMap {
          case None =>
            taskStore.getAllPendingTasks.map { pending =>
              val ids = if pending.isEmpty then "(none)" else pending.map(_.id).mkString(", ")
              Left(
                ToolError(
                  s"No entry with id \"$id\". Pending ids: $ids — if this looks stale, call action=list to refresh."
                )
              )
            }
          case Some(t) =>
            val contentOpt = input("content").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
            // project 提供即生效（"" = 清空归属）；缺席 = 不改。
            val projectOpt: Option[Option[String]] =
              input("project").flatMap(_.asString).map(raw => Some(raw.trim).filter(_.nonEmpty))
            if contentOpt.isEmpty && projectOpt.isEmpty then
              IO.pure(
                Left(
                  ToolError(
                    "edit requires at least one field to change: content (new text) and/or project (new label; \"\" clears)."
                  )
                )
              )
            else
              val updated = t.copy(
                content = contentOpt.getOrElse(t.content),
                project = projectOpt.getOrElse(t.project)
              )
              taskStore.updateTask(t.sessionId, id)(_ => updated).flatMap { changed =>
                if !changed then
                  IO.pure(Left(ToolError(s"Edit did not apply — entry $id vanished mid-flight; retry after action=list.")))
                else
                  for
                    _ <- taskService match
                      case Some(svc) => svc.notifyTaskChange()
                      case None => IO.unit
                    _ <- ctx.wsSend match
                      case Some(send) =>
                        send(
                          Json.obj(
                            "type" -> "scheduledTaskUpdated".asJson,
                            "sessionId" -> t.sessionId.asJson,
                            "task" -> Json.obj(
                              "id" -> updated.id.asJson,
                              "content" -> updated.content.asJson,
                              "triggerAt" -> updated.triggerAt.asJson,
                              "createdAt" -> updated.createdAt.asJson,
                              "triggered" -> updated.triggered.asJson,
                              "triggeredAt" -> updated.triggeredAt.asJson,
                              "referencePath" -> updated.referencePath.asJson,
                              "repeat" -> updated.repeat.asJson,
                              "enabled" -> updated.enabled.asJson,
                              "kind" -> updated.kind.asJson,
                              "name" -> updated.name.asJson,
                              "missCount" -> updated.missCount.asJson,
                              "project" -> updated.project.asJson,
                              "sessionId" -> updated.sessionId.asJson
                            )
                          )
                        )
                      case None => IO.unit
                  yield
                    val what = List(
                      contentOpt.map(_ => "content").toList,
                      projectOpt.map(p => s"project=${p.getOrElse("(none)")}" :: Nil).toList.flatten
                    ).flatten.mkString(", ")
                    Right(s"Updated ${if updated.isTodo then "todo" else "task"} $id ($what): ${updated.content.take(60)}")
              }
        }
    end match
  end editAction

  // ---------------------------------------------------------------------------
  // action = create — legacy behavior + name upsert
  // ---------------------------------------------------------------------------

  private def createAction(
    taskStore: ScheduledTaskStore,
    taskService: Option[ScheduledTaskService],
    input: JsonObject,
    ctx: ToolContext,
    sessionId: String
  ): IO[Either[ToolError, String]] =
    val content = input("content").flatMap(_.asString).getOrElse("")
    val repeat = input("repeat").flatMap(_.asString).filter(validRepeat.contains)
    val taskName = input("name").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
    val now = System.currentTimeMillis()

    parseTriggerAt(input("triggerAt"), now, ZoneId.systemDefault()) match
      case Left(msg) => IO.pure(Left(ToolError(msg)))
      case Right(triggerAt) =>
        if content.isBlank then
          IO.pure(
            Left(
              ToolError("content is required and must not be blank (for list/cancel use action=\"list\"/\"cancel\")")
            )
          )
        else if triggerAt <= now then
          IO.pure(
            Left(
              ToolError(
                s"triggerAt must be in the future (given $triggerAt = ${formatTime(triggerAt)}, " +
                  s"current ${formatTime(now)} = $now ms, zone ${ZoneId.systemDefault().getId})."
              )
            )
          )
        else
          val task = ScheduledTask.create(
            sessionId,
            content,
            triggerAt,
            None,
            repeat,
            taskName,
            None,
            input("condition").flatMap(_.asString),
            input("project").flatMap(_.asString)
          )
          for
            removed <- taskName match
              case Some(n) => taskStore.upsertTaskByName(task)
              case None => taskStore.addTask(task).as(Nil)
            _ <- taskService match
              case Some(svc) => svc.notifyTaskChange()
              case None => IO.unit
            // Broadcast removed (possibly cross-session) so other panels drop them
            _ <- ctx.wsSend match
              case Some(send) =>
                removed.traverse_(old =>
                  send(
                    Json.obj(
                      "type" -> "scheduledTaskDeleted".asJson,
                      "id" -> old.id.asJson,
                      "sessionId" -> old.sessionId.asJson
                    )
                  )
                )
              case None => IO.unit
            // Broadcast to frontend so the reminder panel updates in real-time
            _ <- ctx.wsSend match
              case Some(send) =>
                send(
                  Json.obj(
                    "type" -> "scheduledTaskCreated".asJson,
                    "sessionId" -> sessionId.asJson,
                    "task" -> Json.obj(
                      "id" -> task.id.asJson,
                      "content" -> task.content.asJson,
                      "triggerAt" -> task.triggerAt.asJson,
                      "createdAt" -> task.createdAt.asJson,
                      "referencePath" -> Json.Null,
                      "repeat" -> task.repeat.asJson,
                      "name" -> task.name.asJson,
                      "kind" -> task.kind.asJson
                    )
                  )
                )
              case None => IO.unit
          yield
            val replacedNote = removed match
              case Nil => ""
              case olds =>
                s" — replaced ${olds.size} existing task(s) with the same name (${olds.map(o => s"${o.id} @ ${formatTime(o.triggerAt)}").mkString(", ")})"
            val nameNote = taskName.map(n => s" [name: $n]").getOrElse("")
            Right(
              s"Scheduled task ${task.id} for ${formatTime(triggerAt)}" +
                repeat.map(r => s" (recurring: $r)").getOrElse("") +
                s"$nameNote: ${content.take(80)}$replacedNote"
            )
          end for
        end if
    end match
  end createAction

  // ---------------------------------------------------------------------------
  // action = create, kind="todo" — 待办面（双面化 2026-10-24）：无固定触发时间，
  // 首次浮出 = 明晨（或今晨未过则今晨）09:00，每日汇总注入直至 done/cancel。
  // 引擎侧同款存储 ⇒ 会话压缩/重启同等不丢。
  // ---------------------------------------------------------------------------

  private def createTodoAction(
    taskStore: ScheduledTaskStore,
    taskService: Option[ScheduledTaskService],
    input: JsonObject,
    ctx: ToolContext,
    sessionId: String
  ): IO[Either[ToolError, String]] =
    val content = input("content").flatMap(_.asString).getOrElse("")
    val taskName = input("name").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
    val project = input("project").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
    val now = System.currentTimeMillis()
    if content.isBlank then
      IO.pure(
        Left(
          ToolError(
            "content is required and must not be blank (for list/cancel/done use action=\"list\"/\"cancel\"/\"done\")"
          )
        )
      )
    else
      // triggerAt 不属于待办面（无固定触发时间）——首次浮出固定为下一次 09:00。
      val surfaceAt = ScheduledTask.nextTodoSurfaceAt(now, ZoneId.systemDefault())
      val todo = ScheduledTask.create(
        sessionId, content, surfaceAt, None, None, taskName,
        Some(ScheduledTask.KindTodo), None, project
      )
      for
        removed <- taskName match
          case Some(n) => taskStore.upsertTaskByName(todo)
          case None => taskStore.addTask(todo).as(Nil)
        _ <- taskService match
          case Some(svc) => svc.notifyTaskChange()
          case None => IO.unit
        // Broadcast removed (possibly cross-session) so other panels drop them
        _ <- ctx.wsSend match
          case Some(send) =>
            removed.traverse_(old =>
              send(
                Json.obj(
                  "type" -> "scheduledTaskDeleted".asJson,
                  "id" -> old.id.asJson,
                  "sessionId" -> old.sessionId.asJson
                )
              )
            )
          case None => IO.unit
        _ <- ctx.wsSend match
          case Some(send) =>
            send(
              Json.obj(
                "type" -> "scheduledTaskCreated".asJson,
                "sessionId" -> sessionId.asJson,
                "task" -> Json.obj(
                  "id" -> todo.id.asJson,
                  "content" -> todo.content.asJson,
                  "triggerAt" -> todo.triggerAt.asJson,
                  "createdAt" -> todo.createdAt.asJson,
                  "referencePath" -> Json.Null,
                  "repeat" -> Json.Null,
                  "name" -> todo.name.asJson,
                  "kind" -> ScheduledTask.KindTodo.asJson,
                  "project" -> todo.project.asJson
                )
              )
            )
          case None => IO.unit
      yield
        val replacedNote = removed match
          case Nil => ""
          case olds =>
            s" — replaced ${olds.size} existing todo(s) with the same name (${olds.map(_.id).mkString(", ")})"
        val nameNote = taskName.map(n => s" [name: $n]").getOrElse("")
        Right(
          s"Todo ${todo.id} registered (surfaces daily at ${formatTime(surfaceAt).takeRight(5)} until done/cancelled)" +
            s"$nameNote: ${content.take(80)}$replacedNote"
        )
      end for
    end if
  end createTodoAction

  // ---------------------------------------------------------------------------
  // triggerAt parsing: epoch-ms integer, ISO 8601, or natural language
  // ---------------------------------------------------------------------------

  /** Parse the triggerAt JSON value into epoch milliseconds. Pure & testable. */
  def parseTriggerAt(input: Option[Json], now: Long, zone: ZoneId): Either[String, Long] =
    input match
      case None =>
        Left("triggerAt is required: epoch-ms integer, ISO 8601 string, or natural-language time string.")
      case Some(json) =>
        json.asNumber.flatMap(_.toLong) match
          case Some(ms) => Right(ms)
          case None =>
            json.asString match
              case None =>
                Left(
                  s"triggerAt must be an epoch-ms integer or a time string, got JSON ${json.getClass.getSimpleName}."
                )
              case Some(raw) => parseTimeString(raw, now, zone)

  private def parseTimeString(raw: String, now: Long, zone: ZoneId): Either[String, Long] =
    val s = raw.trim.replaceAll("\\s+", " ")
    if s.isEmpty then Left("triggerAt string is empty.")
    // epoch-ms passed as a string ("1755298800000")
    else if s.matches("\\d{13}") then Right(s.toLong)
    else
      val nowZdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
      tryIso(s, zone)
        .orElse(tryWeekday(s, nowZdt))
        .orElse(tryTimeOfDay(s, nowZdt))
        .orElse(tryAmpm(s, nowZdt))
        .orElse(tryTomorrow(s, nowZdt))
        .orElse(tryChinese(s, nowZdt))
        .orElse(tryRelative(s, now)) match
        case Some(ms) => Right(ms)
        case None =>
          Left(
            s"Cannot parse triggerAt: \"$raw\". Use epoch ms, ISO 8601 (e.g. 2026-08-16T21:00:00+08:00), " +
              s"or natural language (\"tomorrow at 21:00\", \"in 2 hours\", \"21:30\", \"7pm\", " +
              s"\"friday 21:00\", \"周一\", \"明天下午3点\"). " +
              s"Current time: ${formatTime(now)} (${now} ms, zone ${zone.getId})."
          )
      end match

    end if

  end parseTimeString

  /** ISO 8601 with offset/Z, ISO local (T or space separated), or bare date. */
  private def tryIso(s: String, zone: ZoneId): Option[Long] =
    scala.util
      .Try(OffsetDateTime.parse(s))
      .toOption
      .map(_.toInstant.toEpochMilli)
      .orElse(scala.util.Try(LocalDateTime.parse(s)).toOption.map(_.atZone(zone).toInstant.toEpochMilli))
      .orElse(
        scala.util
          .Try(LocalDateTime.parse(s, SpaceDateTimeFormatter))
          .toOption
          .map(_.atZone(zone).toInstant.toEpochMilli)
      )
      .orElse(scala.util.Try(LocalDate.parse(s)).toOption.map(_.atStartOfDay(zone).toInstant.toEpochMilli))

  private val SpaceDateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss]")

  /** "21:30" / "at 21:30" / "9:05:30" — today, or tomorrow when already past. */
  private val TimeOfDayRegex = """(?:at )?(\d{1,2}):(\d{2})(?::(\d{2}))?""".r

  private def tryTimeOfDay(s: String, nowZdt: ZonedDateTime): Option[Long] =
    s match
      case TimeOfDayRegex(h, m, sec) =>
        val (hh, mm, ss) = (h.toInt, m.toInt, Option(sec).map(_.toInt).getOrElse(0))
        if hh <= 23 && mm <= 59 && ss <= 59 then
          var t = nowZdt.toLocalDate.atTime(hh, mm, ss).atZone(nowZdt.getZone)
          if !t.toInstant.isAfter(nowZdt.toInstant) then t = t.plusDays(1)
          Some(t.toInstant.toEpochMilli)
        else None
      case _ => None

  // ------------------------------------------------------------------
  // ④自然语言解析增强（2026-10-24 主动性批）：am/pm、星期（中英）、
  // 中文日期时段（「明天下午3点」）。作者日常口语形态，解析面加宽。
  // ------------------------------------------------------------------

  private val AmpmRegex = """(?i)^(?:at )?(\d{1,2})(?::(\d{2}))?\s*(am|pm)$""".r

  /** am/pm 换算 24h（12am=0, 12pm=12）；None = 值非法。 */
  private def ampmTo24(hh: Int, ap: String): Option[Int] =
    ap.toLowerCase match
      case "am" => Some(if hh == 12 then 0 else hh)
      case "pm" => Some(if hh == 12 then 12 else hh + 12)
      case _ => None

  private def atDate(hh: Int, mm: Int, date: java.time.LocalDate, zone: ZoneId): Option[Long] =
    if hh <= 23 && mm <= 59 then Some(date.atTime(hh, mm).atZone(zone).toInstant.toEpochMilli)
    else None

  /** "7pm" / "7:30 pm" / "noon" / "midnight" — today, or tomorrow when already past. */
  private def tryAmpm(s: String, nowZdt: ZonedDateTime): Option[Long] =
    val lower = s.trim.toLowerCase
    val parsed: Option[(Int, Int)] = lower match
      case "noon" => Some((12, 0))
      case "midnight" => Some((0, 0))
      case _ =>
        lower match
          case AmpmRegex(h, m, ap) =>
            ampmTo24(h.toInt, ap).map(h24 => (h24, Option(m).map(_.toInt).getOrElse(0)))
          case _ => None
    parsed.flatMap { case (hh, mm) =>
      var t = nowZdt.toLocalDate.atTime(hh, mm).atZone(nowZdt.getZone)
      if !t.toInstant.isAfter(nowZdt.toInstant) then t = t.plusDays(1)
      Some(t.toInstant.toEpochMilli)
    }

  end tryAmpm

  /** 星期 token → ISO day-of-week（周一=1 … 周日=7）。 */
  private val WeekdayNames: Map[String, Int] =
    val en = Map(
      "monday" -> 1,
      "mon" -> 1,
      "tuesday" -> 2,
      "tues" -> 2,
      "tue" -> 2,
      "wednesday" -> 3,
      "wed" -> 3,
      "thursday" -> 4,
      "thurs" -> 4,
      "thur" -> 4,
      "thu" -> 4,
      "friday" -> 5,
      "fri" -> 5,
      "saturday" -> 6,
      "sat" -> 6,
      "sunday" -> 7,
      "sun" -> 7
    )
    val zhPrefixes = List("周", "星期", "週")
    val zhNums = Map("一" -> 1, "二" -> 2, "三" -> 3, "四" -> 4, "五" -> 5, "六" -> 6, "日" -> 7, "天" -> 7)
    en ++ zhPrefixes.flatMap(p => zhNums.map { case (n, v) => s"$p$n" -> v })

  end WeekdayNames

  /** "monday" / "friday 21:00" / "next monday 7pm" / "周一 21:30" / "周日下午3点". */
  private val WeekdayRegex =
    """(?i)^(?:next\s+)?([a-z]{3,9}|周[一二三四五六日天]|星期[一二三四五六日天]|週[一二三四五六日天])(?:[\s　]+(?:at\s+)?(.+))?$""".r

  /** 下一个严格未来的该星期几（今天=该日 ⇒ +7 天，不回今天）。 */
  private def tryWeekday(raw: String, nowZdt: ZonedDateTime): Option[Long] =
    raw.trim match
      case WeekdayRegex(tok, tail) =>
        WeekdayNames.get(tok.toLowerCase).flatMap { wd =>
          val daysAhead =
            val d = (wd - nowZdt.getDayOfWeek.getValue + 7) % 7
            if d == 0 then 7 else d
          val date = nowZdt.toLocalDate.plusDays(daysAhead)
          Option(tail).map(_.trim).filter(_.nonEmpty) match
            case None => atDate(9, 0, date, nowZdt.getZone) // 裸星期 = 默认 09:00
            case Some(timeStr) => parseLooseTime(timeStr, date, nowZdt.getZone)
        }
      case _ => None

  /**
   * 时间尾巴："21:00" / "at 9:30" / "7pm" / "noon" / "下午3点" / "晚上11点半"
   * （日期已由星期/中文日期词定死）。
   */
  private def parseLooseTime(timeStr: String, date: java.time.LocalDate, zone: ZoneId): Option[Long] =
    chineseTail(timeStr) match
      case Some((hh, mm, extraDays)) => atDate(hh, mm, date.plusDays(extraDays), zone)
      case None =>
        val lower = timeStr.trim.toLowerCase
        lower match
          case TimeOfDayRegex(h, m, sec) =>
            val ss = Option(sec).map(_.toInt).getOrElse(0)
            if ss <= 59 then atDate(h.toInt, m.toInt, date, zone) else None
          case AmpmRegex(h, m, ap) =>
            ampmTo24(h.toInt, ap).flatMap(hh => atDate(hh, Option(m).map(_.toInt).getOrElse(0), date, zone))
          case "noon" => atDate(12, 0, date, zone)
          case "midnight" => atDate(0, 0, date, zone)
          case _ => None

  /**
   * 中文时段尾巴 → (24h 时, 分, 顺延天数)：「下午3点」→(15,0,0)、「晚上11点半」→(23,30,0)、
   * 「晚上12点」→(0,0,1)（次日零点）。None = 形态不匹配。
   */
  private def chineseTail(s: String): Option[(Int, Int, Int)] =
    s.trim match
      case ChineseTailRegex(period, hStr, mStr, half) =>
        val (h24, extraDays) = mapChineseHour(Option(period), hStr.toInt)
        Some((h24, if half != null then 30 else Option(mStr).map(_.toInt).getOrElse(0), extraDays))
      case _ => None

  /** 时段词交替（整句/尾巴两个 regex 共用；「晚」兜底单字）。 */
  private val PeriodAlt = "凌晨|清晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里|晚"

  /**
   * 「(今天|明天|后天|後天|大后天)?(时段词)?H[点时:：](M分?|半)?」（组间容忍半/全角空格，
   * 连写「明天下午3点」与带空格「明天 下午3点」两形态共用此式）。五个捕获组：
   * day, period, hour, minute, half —— 组数必须与 tryChinese 的 unapplySeq 绑定严格一致：
   * 少一组 ⇒ 整个 case 永不匹配、全部中文形态 Left（2026-10-25 判红回喂：
   * 4 组对 5 绑定 ⇒「明天下午3点」Cannot parse）。
   */
  private val ChineseDtRegex =
    ("""^(今天|明天|后天|後天|大后天)?[\s　]*(""" + PeriodAlt + """)?[\s　]*(\d{1,2})[点时:：][\s　]*(?:(\d{1,2})分?|(半))?$""").r

  /**
   * 中文时段+时刻尾巴：「(时段词)?H[点时:：](M分?|半)?」（四组：period, hour, minute, half）——
   * 星期尾巴（「周日下午3点」）与整句中文形态共用。
   */
  private val ChineseTailRegex =
    ("""^(""" + PeriodAlt + """)?[\s　]*(\d{1,2})[点时:：][\s　]*(?:(\d{1,2})分?|(半))?$""").r

  /** 时段词 → (24h 时, 额外天数)（「晚上12点」= 次日零点）。 */
  private def mapChineseHour(period: Option[String], h: Int): (Int, Int) =
    period match
      case Some(p) =>
        p match
          case "凌晨" | "清晨" | "早上" | "早晨" | "上午" => (if h == 12 then 0 else h, 0)
          case "中午" => (if h >= 1 && h <= 5 then h + 12 else h, 0)
          case "下午" => (if h < 12 then h + 12 else h, 0)
          case "傍晚" | "晚上" | "夜里" | "晚" =>
            if h == 12 then (0, 1) else if h < 12 then (h + 12, 0) else (h, 0)
          case _ => (h, 0)
      case None => (h, 0)

  private def tryChinese(s: String, nowZdt: ZonedDateTime): Option[Long] =
    s.trim match
      case ChineseDtRegex(day, period, hStr, mStr, half) =>
        val h = hStr.toInt
        val mm = half match
          case "半" => 30
          case _ => Option(mStr).map(_.toInt).getOrElse(0)
        val dayOffset = Option(day)
          .map {
            case "明天" => 1
            case "后天" | "後天" => 2
            case "大后天" => 3
            case _ => 0
          }
          .getOrElse(0)
        val (h24, extraDays) = mapChineseHour(Option(period), h)
        val date = nowZdt.toLocalDate.plusDays(dayOffset + extraDays)
        atDate(h24, mm, date, nowZdt.getZone).map { ms =>
          // 今天/无日子词且已过 ⇒ 顺延一天（与裸 "HH:mm"/"H点" 同规，spec 钉死：
          // 「今天 9点」在 10:00 ⇒ 明晨 9:00）；明天/后天/大后天是绝对日，不滚；
          // 「晚上12点」自带 extraDays（次零点）恒在未来。
          val absolute = (day != null && day != "今天") || extraDays > 0
          if !absolute && ms <= nowZdt.toInstant.toEpochMilli then ms + 86_400_000L else ms
        }
      case _ => None

  /** "tomorrow" (defaults 09:00), "tomorrow 21:00", "tomorrow at 21:00". */
  private val TomorrowRegex = """tomorrow(?: (?:at )?(\d{1,2}):(\d{2})(?::(\d{2}))?)?""".r

  private def tryTomorrow(s: String, nowZdt: ZonedDateTime): Option[Long] =
    s.toLowerCase match
      case TomorrowRegex(h, m, sec) =>
        val (hh, mm, ss) = (
          Option(h).map(_.toInt).getOrElse(9),
          Option(m).map(_.toInt).getOrElse(0),
          Option(sec).map(_.toInt).getOrElse(0)
        )
        if hh <= 23 && mm <= 59 && ss <= 59 then
          val t = nowZdt.toLocalDate.plusDays(1).atTime(hh, mm, ss).atZone(nowZdt.getZone)
          Some(t.toInstant.toEpochMilli)
        else None
      case _ => None

  /** "in 30 minutes" / "in 2 hours" / "in 1 day" / "in 90m" (optionally "from now"). */
  private val InRegex = """(?i)in (\d+)\s*(minutes?|mins?|m|hours?|hrs?|h|days?|d)(?: from now)?""".r

  private def tryRelative(s: String, now: Long): Option[Long] =
    s match
      case InRegex(n, unit) =>
        val count = n.toLong
        val millis = unit.toLowerCase.take(1) match
          case "m" => count * 60_000L
          case "h" => count * 3_600_000L
          case "d" => count * 86_400_000L
        Some(now + millis)
      case _ => None

  private def formatTime(epochMs: Long): String =
    val instant = java.time.Instant.ofEpochMilli(epochMs)
    val formatter = java.time.format.DateTimeFormatter
      .ofPattern("yyyy-MM-dd HH:mm")
      .withZone(java.time.ZoneId.systemDefault())
    formatter.format(instant)

end ScheduleTool
