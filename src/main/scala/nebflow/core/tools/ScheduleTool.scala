package nebflow.core.tools

import cats.effect.IO
import cats.syntax.foldable.toFoldableOps
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.scheduler.{
  DoneRecord,
  ScheduledTask,
  ScheduledTaskService,
  ScheduledTaskStore,
  TodoDue,
  DueZoneSource,
  TodoDecision,
  TodoTaxonomy
}
import nebflow.shared.TimeAnchor

import java.time.*

object ScheduleTool extends Tool:
  val name = "Schedule"

  val description =
    """Schedule a task to fire at a specified time, optionally recurring — or keep a persistent
todo (待办) on the USER's inbox until they write it off — or manage existing entries
(list / cancel / done). At the scheduled time the content is injected into your conversation
as an instruction — you then execute it.

Semantic split (three names, one ledger — 待办数据模型与命名规范批 2026-11-18):
- 提醒 Reminder = what the AUTHOR creates FOR HIMSELF. Never created by you (the server
  records source="user" on the frontend reminder entry). No options needed.
- 待办 Todo = what YOU dispatch to the author (kind="todo"). It lands in the user's todo
  inbox (消息列表 → 待办事项), surfaces in the daily morning digest on your side, and waits
  until the user decides/checks it off. **Every 待办 MUST carry `decision.options`** —
  a decision item without options is rejected at the write side. The user answers WHEN
  FREE — never expect an immediate reply. Non-blocking decisions/approvals belong here,
  NOT in an AskUser popup.
- 定时任务 ScheduledTask = a timed task (kind="task", the default): fires into your
  conversation at triggerAt. Not a todo, no options. (This face is what the code calls
  kind="task"; it is NOT called a "reminder" any more — the word 提醒 is reserved for the
  author's own items.)
A todo (待办) and a scheduled task (定时任务) live in the SAME store and the SAME list
query — one inbox, two faces.

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
  without it: create with kind="todo" (content = the decision to make) **plus a `decision`
  with the concrete options** — a 待办 without options is rejected. It appears in the
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
- **get**: read the complete current entry by id, including the untruncated body, across sessions.
- **cancel**: remove an entry by id (works across sessions, both kinds). A cancelled scheduled
  task never fires; a cancelled todo never surfaces again. It disappears from list immediately.
- **done**: write off a TODO by id after completing it (todos only — for a scheduled task
  use cancel). The written-off todo is removed from storage and never surfaces again.
- **edit**: modify an existing entry by id — content and/or project label (字段级 patch,
  提供的字段才改; project 传 "" 清空归属 = 未分组). Works across sessions, both kinds.

## Fields

- **action** (optional): "create" (default) | "list" | "get" | "cancel" | "done" | "edit"
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
- **decision** (create, **REQUIRED for kind="todo"**): the structured options the author
  will choose from. `{"question": "...", "options": [{"id":"o1", "title": "<REQUIRED>",
  "detail": "...", "media": [{"kind":"image"|"video", "refType":"attachment"|"path"|"url",
  "ref": "<id|abs path|url>", "caption": "...", "role": "cover"}]}]}`.
  At least one option; every option needs a non-blank `title`. Media is OPTIONAL (an
  option with no media is a legal shape) and one option may carry MULTIPLE media (they
  render as stacked cards; `role:"cover"` marks the top one). Media is a reference — it is
  not checked for reachability, and a dead reference only degrades to "no media".

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
{"action":"create", "kind":"todo", "content":"选择周报模板的落地位置", "name":"weekly-report-template", "decision":{"question":"周报模板放哪？", "options":[{"id":"o1","title":"整理进 memory"},{"id":"o2","title":"落到 design/ 目录"}]}}
{"action":"create", "kind":"todo", "content":"等 CI 绿后合并 release 分支（需用户批准）", "name":"release-merge-approval", "project":"stand", "decision":{"question":"是否合并 release 分支？", "options":[{"id":"o1","title":"批准合并"},{"id":"o2","title":"先不合并","detail":"等下一个窗口"},{"id":"o3","title":"看截图再定","media":[{"kind":"image","refType":"attachment","ref":"<attachment-id>","role":"cover"},{"kind":"image","refType":"attachment","ref":"<attachment-id-2>"}]}]}}
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
- kind="todo" without `decision` → REJECTED (TODO_OPTIONS_REQUIRED): every 待办 must carry at
  least one option with a non-blank title. Add `decision`, or use kind="task" if it really
  is a timed task, or tell the user to create it himself (that is a 提醒, options optional).
- An option's `media` is a reference, not bytes → pass a ref (attachment id / absolute path
  / URL); never inline base64. A missing/expired ref is fine (renders as no media).
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

  /**
   * 重复档白名单（单源）。schedule-created-card 批（2026-11-05）起对 gateway 的
   *  `updateScheduledTask` 帧公开 —— 卡片内联编辑的 repeat 校验必须命中**同一份**
   *  白名单，复制一份字符串集合 = 两条校验路必然漂移。
   */
  val validRepeat = Set("hourly", "daily", "weekly")
  private val validActions = Set("create", "list", "get", "cancel", "done", "edit")

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "action" -> Json.obj(
          "type" -> "string".asJson,
          "enum" -> Json
            .arr("create".asJson, "list".asJson, "get".asJson, "cancel".asJson, "done".asJson, "edit".asJson),
          "description" -> "create (default) = schedule a task or register a todo; list = show all pending entries; get = read full entry by id across sessions; cancel = remove an entry by id; done = write off a completed todo by id; edit = modify an existing entry by id (content/project)".asJson
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
          "description" -> "(create/edit) Short TITLE — one short readable line. kind=\"todo\": this is the todo's displayed name, the `#<name>` reference token users type, and the upsert key (same name + kind replaces the existing entry instead of stacking). kind=\"task\": a stable identifier for routine re-arm. Omit for an anonymous entry.".asJson
        ),
        "detail" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "(create/edit, kind=\"todo\") The todo's long body — whatever context the user will want when they open it (the daily digest still injects `content`). The todo list shows `name` as the row and folds `detail` behind a disclosure. On edit, pass \"\" to clear it. Ignored for kind=\"task\".".asJson
        ),
        "project" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "(create/edit) Project label the entry belongs to (e.g. \"DOSTEL\", \"stand\") — the todo inbox groups and filters by it. Omit = ungrouped; on edit, pass \"\" to clear the label.".asJson
        ),
        "condition" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "(create, kind=\"task\" only) conditional trigger — fires only when TRUE at trigger time. Forms: bare `sh -c` probe (exit 0 = true; fast, absolute paths, 60s timeout = unmet); \"git-tip:<repoPath>[#<branch>]\" (stateful sentinel — fires when branch tip changed since last check); \"task-terminal:<project>:<nodeId>\" (fires when that flow-map node reaches a terminal state). Unmet: repeating tasks skip silently to the next cycle; one-shots re-check every 30 min for up to 24h then auto-PAUSE. Nothing is injected while unmet.".asJson
        ),
        "creationZone" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "(edit) Explicit IANA creation-zone correction; submit separately from other edits.".asJson
        ),
        "id" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "(cancel/done/edit) Entry id — from action=list.".asJson
        ),
        "decision" -> Json.obj(
          "type" -> "object".asJson,
          "description" ->
            """(create, REQUIRED for kind="todo") The todo's DECISION — every agent-dispatched todo must carry options (the user decides, not just reads). Shape:
              |{"question":"<one line>", "multiSelect":false, "allowOther":false, "secret":false, "expiresAt":<epoch ms|null>,
              | "options":[{"id":"o1","title":"<REQUIRED short label>","detail":"<optional long text>",
              |             "media":[{"kind":"image|video","refType":"attachment|path|url","ref":"<id|abs path|url>",
              |                       "mime":null,"caption":null,"role":"cover"}]}]}
              |Rules: >=1 option; each option needs a non-blank `title` (media is OPTIONAL — an option with no media is legal);
              |one option may carry MULTIPLE media (they render as stacked cards, `role:"cover"` = the top one).
              |Media is a REFERENCE, never inline bytes, and is NOT checked for reachability — a dead reference degrades to "no media"; it never invalidates the todo.
              |Omit `decision` on kind="task" (定时任务) — it is not consumed there.""".stripMargin.asJson
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
            case "get" =>
              input("id").flatMap(_.asString).map(_.trim).filter(_.nonEmpty) match
                case None => IO.pure(Left(ToolError("get requires id")))
                case Some(id) =>
                  taskStore.findTaskById(id).map {
                    case None => Left(ToolError(s"Entry not found: $id"))
                    case Some(t) => Right(s"Entry ${t.id} · ${t.name.getOrElse("")}\n${t.detail.getOrElse(t.content)}")
                  }
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
                  // 待办数据模型批（2026-11-18）：「待办必须有选项」的**唯一写侧强制点**。
                  // 判定依据 = 本工具的创建一律 source="tool" ⇒ 落入 TodoClass.Todo
                  // （作者令「你创建的是待办和定时任务」）⇒ 选项必备。定时任务面
                  // （kind≠todo）不强制，但带了 decision 就必须成形。
                  val createKind = Option.when(isTodo)(ScheduledTask.KindTodo)
                  TodoTaxonomy.decodeDecision(Json.fromJsonObject(input)) match
                    case Left(v) => IO.pure(Left(ToolError(v.message)))
                    case Right(decision) =>
                      TodoTaxonomy.validateCreate(createKind, Some(TodoTaxonomy.SourceTool), decision) match
                        case Left(v) => IO.pure(Left(ToolError(v.message)))
                        case Right(_) =>
                          // 只在通过校验后归一化（空白选项 id 补齐 / title 定案），
                          // 保证落库形态干净且与校验所见一致。
                          val normalized = decision.map(TodoTaxonomy.normalizeDecision)
                          if isTodo then createTodoAction(taskStore, taskService, input, ctx, sessionId, normalized)
                          else createAction(taskStore, taskService, input, ctx, sessionId, normalized)
            case other => IO.pure(Left(ToolError(s"Unhandled action $other"))) // unreachable (guarded above)
          end match
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
              // 两段化：detail 有则展示它（这才是"那条待办的具体内容"），否则回落 content。
              s"- ${t.id} | todo | created ${formatDate(t.createdAt)} | $status$nm$proj | ${t.detail.getOrElse(t.content).take(60)}"
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
        taskStore.cancelTask(id).flatMap {
          case Nil =>
            taskStore.getAllPendingTasks.map { pending =>
              val ids = if pending.isEmpty then "(none)" else pending.map(_.id).mkString(", ")
              Left(
                ToolError(
                  s"No entry with id \"$id\". Pending ids: $ids — if this looks stale, call action=list to refresh."
                )
              )
            }
          case removed =>
            for
              _ <- taskService match
                case Some(svc) => svc.notifyTaskChange()
                case None => IO.unit
              // Notify each stored session without treating any conflicting copy as authority.
              _ <- ctx.wsSend match
                case Some(send) =>
                  removed.map(_.sessionId).distinct.traverse_ { sessionId =>
                    send(
                      Json.obj(
                        "type" -> "scheduledTaskDeleted".asJson,
                        "id" -> id.asJson,
                        "sessionId" -> sessionId.asJson
                      )
                    )
                  }
                case None => IO.unit
            yield removed match
              case t :: Nil =>
                val kindWord = if t.isTodo then "todo" else "task"
                val wasWhat = if t.isTodo then "todo" else formatTime(t.triggerAt)
                val willNote = if t.isTodo then "it will not surface again" else "it will not fire"
                Right(
                  s"Cancelled $kindWord $id (was $wasWhat${t.repeat.map(r => s", $r").getOrElse("")}): " +
                    s"${t.content.take(60)} — $willNote."
                )
              case _ =>
                Right(s"Cancelled entry $id and all stored copies — it will not fire or surface again.")
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
    val correction = input("creationZone")
    if correction.isDefined then
      val zone = correction.flatMap(_.asString).map(_.trim)
      val mixed = List("content", "project", "name", "detail").exists(input.contains)
      if idOpt.isEmpty then IO.pure(Left(ToolError("edit requires id")))
      else if mixed then IO.pure(Left(ToolError("creationZone correction must be submitted separately")))
      else if !zone.exists(ZoneId.getAvailableZoneIds.contains) then IO.pure(Left(ToolError("creationZone must be a valid IANA region")))
      else taskStore.findTaskById(idOpt.get).flatMap {
        case None => IO.pure(Left(ToolError("entry not found")))
        case Some(task) if !task.isTodo || task.due.isEmpty => IO.pure(Left(ToolError("entry has no todo due rule")))
        case Some(_) =>
          (for
            _ <- taskStore.changeCreationZone(idOpt.get, zone.get, System.currentTimeMillis())
            saved <- taskStore.findTaskById(idOpt.get)
            _ <- taskService.traverse_(_.notifyTaskChange())
            _ <- ctx.wsSend.traverse_(send => saved.traverse_(row =>
              send(Json.obj("type" -> "scheduledTaskUpdated".asJson,
                "sessionId" -> row.sessionId.asJson, "task" -> row.asJson))))
          yield saved.toRight(ToolError("entry no longer exists")).map(_ => s"Updated creation zone to ${zone.get}"))
            .handleError(e => Left(ToolError(s"creationZone correction failed: ${e.getMessage}")))
      }
    else idOpt match
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
            // 两段化批（2026-11-05）：name / detail 进入 patch 面。
            // detail 传 "" = 清空第二段（Some(None)）；缺席 = 不改。
            val nameOpt: Option[Option[String]] =
              input("name").flatMap(_.asString).map(raw => Some(raw.trim).filter(_.nonEmpty))
            val detailOpt: Option[Option[String]] =
              input("detail").flatMap(_.asString).map(raw => Some(raw.trim).filter(_.nonEmpty))
            if contentOpt.isEmpty && projectOpt.isEmpty && nameOpt.isEmpty && detailOpt.isEmpty then
              IO.pure(
                Left(
                  ToolError(
                    "edit requires at least one field to change: content (new text), name (short title), " +
                      "detail (long text; \"\" clears it) and/or project (new label; \"\" clears)."
                  )
                )
              )
            else
              val updated = t.copy(
                content = contentOpt.getOrElse(t.content),
                project = projectOpt.getOrElse(t.project),
                name = nameOpt.getOrElse(t.name),
                // 定时任务面没有两段语义：只有待办接受 detail 改写。
                detail = if t.isTodo then detailOpt.getOrElse(t.detail) else t.detail
              )
              taskStore.updateTask(t.sessionId, id)(_ => updated).flatMap { changed =>
                if !changed then
                  IO.pure(
                    Left(ToolError(s"Edit did not apply — entry $id vanished mid-flight; retry after action=list."))
                  )
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
                              "detail" -> updated.detail.asJson,
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
                      nameOpt.map(_ => "name").toList,
                      detailOpt.map(_ => "detail").toList,
                      projectOpt.map(p => s"project=${p.getOrElse("(none)")}" :: Nil).toList.flatten
                    ).flatten.mkString(", ")
                    Right(
                      s"Updated ${if updated.isTodo then "todo" else "task"} $id ($what): ${updated.content.take(60)}"
                    )
              }
            end if
        }
  end editAction

  // ---------------------------------------------------------------------------
  // action = create — legacy behavior + name upsert
  // ---------------------------------------------------------------------------

  private def createAction(
    taskStore: ScheduledTaskStore,
    taskService: Option[ScheduledTaskService],
    input: JsonObject,
    ctx: ToolContext,
    sessionId: String,
    decision: Option[TodoDecision] = None
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
                // 时间串单源（时间感知修复批 2026-11-05）：改前此处自带
                // `formatTime` + 「ms + zone」两段自拼格式，与每回合时间锚的格式
                // 不同源（同一时刻在提示词里两种写法 = 模型要做二次解释）。现统一走
                // [[nebflow.shared.TimeAnchor]]：`stamp` 已含本地时间 + 偏移 + 时区名 +
                // 星期 + epoch_ms，故「= $now ms, zone …」冗余段一并去掉。
                s"triggerAt must be in the future (given ${TimeAnchor.stamp(TimeAnchor.at(triggerAt))}, " +
                  s"current ${TimeAnchor.stamp(TimeAnchor.at(now))})."
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
            input("project").flatMap(_.asString),
            decision = decision
          )
          val ownedTask = task.copy(source = Some(TodoTaxonomy.SourceTool))
          for
            rearm <- taskName match
              case Some(n) => taskStore.upsertTaskByName(ownedTask, ScheduledTaskStore.toolOwnsLegacyNamedTask)
              case None => taskStore.addTask(ownedTask).as(ScheduledTaskStore.Rearm(ownedTask, Nil))
            saved = rearm.saved
            _ <- taskService match
              case Some(svc) => svc.notifyTaskChange()
              case None => IO.unit
            // Broadcast replaced/dropped copies (possibly cross-session) so panels drop them
            _ <- ctx.wsSend match
              case Some(send) =>
                rearm.replaced.traverse_(old =>
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
                    "sessionId" -> saved.sessionId.asJson,
                    "task" -> Json.obj(
                      "id" -> saved.id.asJson,
                      "content" -> saved.content.asJson,
                      "triggerAt" -> saved.triggerAt.asJson,
                      "createdAt" -> saved.createdAt.asJson,
                      "referencePath" -> Json.Null,
                      "repeat" -> saved.repeat.asJson,
                      "name" -> saved.name.asJson,
                      "kind" -> saved.kind.asJson
                    )
                  )
                )
              case None => IO.unit
          yield
            val replacedNote = rearm.replaced match
              case Nil => ""
              case olds =>
                s" — replaced ${olds.size} existing task(s) with the same name (${olds.map(o => s"${o.id} @ ${formatTime(o.triggerAt)}").mkString(", ")})"
            val nameNote = taskName.map(n => s" [name: $n]").getOrElse("")
            Right(
              s"Scheduled task ${saved.id} for ${formatTime(saved.triggerAt)}" +
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
    sessionId: String,
    decision: Option[TodoDecision] = None
  ): IO[Either[ToolError, String]] =
    val content = input("content").flatMap(_.asString).getOrElse("")
    val taskName = input("name").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
    val todoDetail = input("detail").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
    val project = input("project").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
    val now = System.currentTimeMillis()
    val dueInput = input("triggerAt").filter(j => !j.isNull && !j.asNumber.flatMap(_.toLong).contains(0L))
    val dueResult: Either[String, Option[(TodoDue, Long)]] = dueInput match
      case None => Right(None)
      case Some(raw) =>
        val zoned = raw.asString.flatMap(s => scala.util.Try(ZonedDateTime.parse(s)).toOption)
        val originalOffset = raw.asString.flatMap(s => scala.util.Try(OffsetDateTime.parse(s.takeWhile(_ != '['))).toOption)
        val local = raw.asString.flatMap(s => scala.util.Try(LocalDateTime.parse(s)).toOption)
        val explicit = zoned.map(_.getZone.getId).filter(ZoneId.getAvailableZoneIds.contains)
        for
          region <- TodoDue.requireCreationZone(explicit, ctx.deviceZone.orElse(Option.when(ctx.deviceZoneFallbackAllowed)(ctx.recentDeviceZone).flatten.map(_.zoneId)))
          repeat = input("repeat").flatMap(_.asString).getOrElse("once")
          _ <- Either.cond(Set("", "once", "weekly").contains(repeat), (), "Todo repeat must be once or weekly")
          _ <- Either.cond(repeat != "weekly" || local.exists(t => t.getSecond == 0 && t.getNano == 0), (), "Weekly reminders require a local date and minute")
          _ <- Either.cond(zoned.isDefined || raw.asString.exists(s => scala.util.Try(LocalDateTime.parse(s)).isSuccess || s.trim.toLowerCase.startsWith("tomorrow")), (), "Please clarify an explicit local date and time")
          at <- originalOffset.map(z => Right(z.toInstant.toEpochMilli)).orElse(local.map { value =>
            val zone = ZoneId.of(region)
            val transition = zone.getRules.getTransition(value)
            val resolved = if transition != null && transition.isGap then transition.getDateTimeAfter else value
            Right(resolved.atZone(zone).toInstant.toEpochMilli)
          }).getOrElse(parseTriggerAt(Some(raw), now, ZoneId.of(region)))
          _ <- Either.cond(at > now, (), "Please clarify a future reminder time")
        yield
          val wall = local.map(_.toString).getOrElse(Instant.ofEpochMilli(at).atZone(ZoneId.of(region)).toLocalDateTime.toString)
          val offset = originalOffset.map(_.getOffset.toString)
          val rule = if repeat == "weekly" then
            val value = local.get
            TodoDue.Weekly(value.getDayOfWeek.getValue, value.toLocalTime.toString, region, wall)
          else TodoDue.Once(at, region, wall, offset)
          Some((rule, at))
    if content.isBlank then
      IO.pure(Left(ToolError("content is required and must not be blank")))
    else if dueResult.isLeft then
      IO.pure(Left(ToolError(dueResult.swap.toOption.get)))
    else
      // The daily digest surface remains separate from the reminder deadline.
      val surfaceAt = ScheduledTask.nextTodoSurfaceAt(now, ZoneId.systemDefault())
      val todo = ScheduledTask.create(
        sessionId,
        content,
        surfaceAt,
        None,
        None,
        taskName,
        Some(ScheduledTask.KindTodo),
        None,
        project,
        0,
        todoDetail,
        decision
      )
      val preparedDue = dueResult.toOption.flatten
      val due = preparedDue.map(_._1)
      val zoneSource = due.map { _ =>
        val explicit = dueInput.flatMap(_.asString).flatMap(s => scala.util.Try(ZonedDateTime.parse(s)).toOption)
          .exists(z => ZoneId.getAvailableZoneIds.contains(z.getZone.getId))
        if explicit then DueZoneSource("explicit-region")
        else if ctx.deviceZone.isDefined then DueZoneSource("initiating-device")
        else
          val report = ctx.recentDeviceZone.get
          DueZoneSource("recent-device", Some(report.deviceId), Some(report.reportedAt))
      }
      val ownedTodo = todo.copy(source = Some(TodoTaxonomy.SourceTool), due = due,
        nextDueAt = preparedDue.map(_._2), dueZoneSource = zoneSource)
      for
        rearm <- taskName match
          case Some(n) => taskStore.upsertTaskByName(ownedTodo, ScheduledTaskStore.toolOwnsLegacyNamedTask)
          case None => taskStore.addTask(ownedTodo).as(ScheduledTaskStore.Rearm(ownedTodo, Nil))
        saved = rearm.saved
        _ <- taskService match
          case Some(svc) => svc.notifyTaskChange()
          case None => IO.unit
        // Broadcast replaced/dropped copies (possibly cross-session) so panels drop them
        _ <- ctx.wsSend match
          case Some(send) =>
            rearm.replaced.traverse_(old =>
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
                "sessionId" -> saved.sessionId.asJson,
                "task" -> Json.obj(
                  "id" -> saved.id.asJson,
                  "content" -> saved.content.asJson,
                  "triggerAt" -> saved.triggerAt.asJson,
                  "createdAt" -> saved.createdAt.asJson,
                  "referencePath" -> Json.Null,
                  "repeat" -> Json.Null,
                  "name" -> saved.name.asJson,
                  "detail" -> saved.detail.asJson,
                  "kind" -> ScheduledTask.KindTodo.asJson,
                  "project" -> saved.project.asJson
                )
              )
            )
          case None => IO.unit
      yield
        val replacedNote = rearm.replaced match
          case Nil => ""
          case olds =>
            s" — replaced ${olds.size} existing todo(s) with the same name (${olds.map(_.id).mkString(", ")})"
        val nameNote = taskName.map(n => s" [name: $n]").getOrElse("")
        val dueNote = preparedDue.map { (rule, at) =>
          val (wall, region) = rule match
            case r: TodoDue.Once => (r.wallClock, r.zoneId)
            case r: TodoDue.Weekly => (r.wallClock, r.zoneId)
          val local = LocalDateTime.parse(wall)
          val transition = ZoneId.of(region).getRules.getTransition(local)
          val adjustment =
            if transition != null && transition.isGap then " (adjusted to first valid time)"
            else if transition != null && transition.isOverlap then
              s" (overlap: ${Instant.ofEpochMilli(at).atZone(ZoneId.of(region)).getOffset})"
            else ""
          s" [due: $wall $region$adjustment]"
        }.getOrElse("")
        Right(
          s"Todo ${saved.id} registered (surfaces daily at ${formatTime(saved.triggerAt).takeRight(5)} until done/cancelled)" +
            s"$nameNote$dueNote: ${content.take(80)}$replacedNote"
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
              // 时间串单源（时间感知修复批 2026-11-05）：与每回合时间锚同源同格式。
              s"${TimeAnchor.line(TimeAnchor.at(now, zone))}"
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
    // 格式单源（时间感知修复批 2026-11-05）：本工具不再自带 `ofPattern`，
    // 行内时刻一律走 [[nebflow.shared.TimeAnchor.compact]]（与每回合时间锚同源）。
    TimeAnchor.compact(epochMs)

end ScheduleTool
