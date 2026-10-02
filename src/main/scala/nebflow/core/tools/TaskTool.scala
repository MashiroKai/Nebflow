package nebflow.core.tools

import cats.effect.IO
import io.circe.{Json, JsonObject}
import io.circe.syntax.*

import nebflow.shared.PathUtil
import nebflow.core.project.{TaskLedgerHistory, TaskLedgerRenderer, TaskLedgerStore}

/**
 * The `Task` tool -- the **single write face of the unified ledger** (Nebula-exclusive;
 * taskunify batch 2026-09-24).
 *
 * One tool replaces the retired `TaskList` + `TaskBoard`: **one ledger, one id space, one
 * change-history file**. The dispatcher and project nodes can only look at **the one task
 * they are attached to** through the separate read-only `TaskInfo` -- they **never write
 * here**.
 *
 * Identity (engine-side, **never from client parameters** -- a security red line):
 * `ctx.isDispatcher` ⇒ refused (a dispatcher has no write right; this is the capability
 * inversion); `ctx.flowNodeId` ⇒ refused (a node has no write right); both empty (a Nebula
 * root session / a direct REST call) ⇒ allowed. The second safeguard = the mount face
 * (neither the dispatcher's fixed set nor a node session **ever sees** this tool's name).
 * Any privilege violation is `TASK_FORBIDDEN` (**not** a silent no-op).
 *
 * The six-verb action face and the deletion of the `status` parameter are documented in the
 * description; the `note` timeline is **not on this tool's face** (its only write path is
 * the engine appending automatically on every Mail from Nebula to that task's dispatcher).
 */
object TaskTool:

  /** Permission refusal (actionable text: names the identity, the violation point, and a
    * way out). */
  private def forbidden(reason: String): ToolError = ToolError(
    s"Task: permission denied — $reason (${TaskLedgerStore.Codes.Forbidden})")

  /** Unauthorized read (no attachment) refusal: `TaskInfo`'s fail-closed text (with a way
    * out). */
  private def noAttachment(session: String): ToolError = ToolError(
    s"TaskInfo: this session has no task attachment — no origin task is recorded for session '$session'. " +
      s"It never falls back to the whole ledger and never shows another task's data. " +
      s"Report the missing attachment through your normal report channel (node_report / your final text) instead. " +
      s"(${TaskLedgerStore.Codes.NoAttachment})")

  // ------------------------------------------------------------------
  // Task (the write face)
  // ------------------------------------------------------------------

  /** Engine-side write-right test: **exclusively the Nebula root session** (ruling §3,
    * "a single write right").
    *
    * The criterion is `ctx.isNebulaWriter` (delegating to the single point
    * `AgentCore.isNebulaRoot`; never re-writing the expression) -- that is, `agentDef.name
    * == "Nebula"` AND `depth == 0`. The dispatcher, project nodes, team, dual-track flow
    * sessions and direct REST calls (`agentDef=None` ⇒ fail-closed false) are all
    * **refused**.
    *
    * Why not the complement-style criterion "not a dispatcher and not a node ⇒ allowed":
    * that would silently let **any unidentified side session** (direct REST calls, a
    * harness, a future new shape) onto the write face -- a single write right is an
    * **authorization face** and must be fail-closed (only an explicit identity gets in),
    * never "everything not excluded gets in". This is
    * `buildAllowedToolSet`'s fail-closed discipline applied at runtime. */
  def writableBy(ctx: ToolContext): Boolean = ctx.isNebulaWriter

  /** The six-verb dispatch (the synchronous core; `call` invokes it inside
    * IO.blocking -- unit-testable). */
  def dispatchSync(
    ledger: TaskLedgerStore,
    nodeTerminal: Map[String, String],
    ctx: ToolContext,
    action: String,
    title: Option[String] = None,
    id: Option[String] = None,
    assignee: Option[String] = None,
    nodeId: Option[String] = None,
    links: Option[List[String]] = None,
    blocks: Option[List[String]] = None,
    parentId: Option[String] = None,
    project: Option[String] = None
  ): Either[ToolError, String] =
    if !writableBy(ctx) then
      val who = if ctx.isDispatcher then "a dispatcher session" else "a project node session"
      Left(forbidden(s"$who may not write the task ledger — creation, state changes and every field edit belong to " +
        s"Nebula. Use the read-only `TaskInfo` for the task you are attached to, and report through your report channel"))
    else
      action match
        case "create" =>
          ledger.createSync(title = title.getOrElse(""), assignee = assignee, nodeId = nodeId,
            blocksRaw = blocks, linksRaw = links, parentId = parentId, project = project)
        case "update" =>
          id match
            case None => Left(ToolError(s"Task: update requires `id` (entry id from action=list). (${TaskLedgerStore.Codes.Param})"))
            case Some(i) =>
              ledger.updateSync(i, title = title, assignee = assignee, nodeId = nodeId,
                blocksRaw = blocks, linksRaw = links, parentId = parentId, project = project)
        case "complete" =>
          id match
            case None => Left(ToolError(s"Task: complete requires `id` (entry id from action=list). (${TaskLedgerStore.Codes.Param})"))
            case Some(i) => ledger.completeSync(i)
        case "close" =>
          id match
            case None => Left(ToolError(s"Task: close requires `id` (entry id from action=list). (${TaskLedgerStore.Codes.Param})"))
            case Some(i) => ledger.closeSync(i)
        case "list" =>
          ledger.listSync(status = None, assignee = assignee, project = project, nodeTerminal = nodeTerminal)
        case "show" =>
          id match
            case None => Left(ToolError(s"Task: show requires `id` (entry id from action=list). (${TaskLedgerStore.Codes.Param})"))
            case Some(i) => ledger.showSync(i, nodeTerminal)
        case other =>
          Left(ToolError(
            s"Task: unknown action '$other' — one of create/update/list/complete/close/show. (${TaskLedgerStore.Codes.Param})"))

  // ------------------------------------------------------------------
  // TaskInfo (the read-only attributed single entry)
  // ------------------------------------------------------------------

  /** Attachment resolution (**zero parameters**; ruling F: an explicit `id` parameter was
    * rejected) -- resolved from the **session identity**, never from client parameters. No
    * attachment ⇒ `Left(TASKINFO_NO_ATTACHMENT)` (**no fallback to the whole ledger**). */
  def attachedTaskId(ctx: ToolContext): Either[ToolError, String] =
    ctx.taskId.filter(_.trim.nonEmpty) match
      case Some(t) => Right(t)
      case None    => Left(noAttachment(ctx.sessionId.getOrElse("(unknown)")))

  /** Read-only rendering of the attributed single entry. If resolution succeeds but the
    * ledger has no such entry ⇒ an explicit error (**no** fallback, **no** guessing). */
  def infoSync(
    ledger: TaskLedgerStore,
    ctx: ToolContext
  ): Either[ToolError, String] =
    attachedTaskId(ctx).flatMap { taskId =>
      val all = ledger.entriesSync()
      all.find(_.id == taskId) match
        case Some(entry) =>
          val history = TaskLedgerHistory.open()
          val notes = history.readFor(taskId, TaskLedgerRenderer.TimelineNoteMaxVersions, TaskLedgerHistory.isNoteEvent)
          val states = history.readFor(taskId, TaskLedgerRenderer.TimelineStateMaxLines, ev => !TaskLedgerHistory.isNoteEvent(ev))
          Right(TaskLedgerRenderer.renderInfo(entry, all, notes, states))
        case None =>
          Left(ToolError(
            s"TaskInfo: your session is attached to task #$taskId, but the ledger has no such entry " +
              s"(it may have been pruned ${TaskLedgerStore.TerminalTtlDays} days after reaching a terminal state). " +
              s"Nothing is reconstructed or guessed here. Report it through your normal channel. " +
              s"(${TaskLedgerStore.Codes.NotFound})"))
    }

end TaskTool

/**
 * The `Task` tool entity (mount face: authorized by NebulaOrchestrationTools; neither the
 * dispatcher's fixed set nor a node session **mounts** it -- the capability inversion of
 * §16 P6). The second safeguard = the runtime identity gate [[TaskTool.writableBy]].
 */
object TaskToolDef extends Tool:

  override def name: String = "Task"

  override def description: String =
    s"""Task — the single work-item ledger (Nebula-exclusive write face). This ONE tool replaces the retired `TaskList` and `TaskBoard`: one ledger, one id space, one change-history file. The dispatcher and project nodes see only the task they are attached to, through the separate read-only `TaskInfo` tool; they never write here.

Storage: ${PathUtil.dataRootRenderValue}/$tasksV2File (runtime data — never in git, never injected wholesale into prompts). Change history: ${PathUtil.dataRootRenderValue}/$historyFile (append-only, rotated at ~5 MiB / 20,000 lines into the `.1` generation; disk capped at ~2 generations).

The two retired ledgers are FROZEN READ-ONLY ARCHIVES and are never written again — the orchestration backlog file and each project's board file keep their bytes (a snapshot is taken before the switch, and they stay readable for the record). NOTHING was migrated: the new ledger starts from zero with its own id watermark; old ids are neither carried over nor renumbered, and an old `#NNN` reference means the old ledger's entry, never a new one.

`note` IS NOT A PARAMETER OF THIS TOOL. The task's note timeline is written automatically by the engine every time Nebula Mails the task's dispatcher — the Mail body becomes a note entry. This tool cannot write the note timeline, and neither can the dispatcher or a node. Read it with the `TaskInfo` tool.

## Actions
- create: new entry (state=`open`). Required: `title`. Optional: `assignee` (node id | "dispatcher" | "author"; default "dispatcher"), `nodeId` (Flow Map node link), `links`, `blocks` (ids this entry DEPENDS on), `parentId` (this entry is a sub-task of that entry), `project` (free-form tag).
- update: edit NON-STATE fields only. Required: `id`. Optional: `title`, `assignee`, `nodeId` (empty string clears), `links` (FULL replacement, [] clears), `blocks` (FULL replacement, [] clears), `parentId` (empty string clears), `project` (empty string clears). `update` can never change the state — a state is reached with `complete` / `close` (see States).
- complete: reach the `completed` state (achieved). Required: `id`. Legal from `open` and from `closed`; idempotent when already `completed`.
- close: reach the `closed` state (voided / withdrawn). Required: `id`. Idempotent when already `closed`. Refused when `completed` (terminal).
- list: render entries with state + dependencies. Optional: `assignee`, `project` (exact filters).
- show: full detail for ONE entry — every field, links, dependency state + reverse lookup (who depends on me), the parent chain / direct children, and the note timeline. Required: `id`. Use it instead of reading the ledger file by hand.

## States (three, and only three)
`open` → `completed` (achieved) and `open` → `closed` (voided). `closed` → `completed` is a LEGAL transition: a voided item that turns out to have been achieved after all is promoted with `complete` — use it instead of creating a duplicate. `completed` is TERMINAL: no transitions out of it, ever. A same-state write is a no-op success.
There is no `in_progress` and no `blocked` state. Progress and waiting are expressed in the note timeline (which the engine writes when the work is dispatched and reported), not as a state — this makes the state machine total: every entry is either still open, voided, or achieved.

## Dependency guard (blocks semantics)
`blocks` = ids this entry DEPENDS ON. `complete` is REJECTED while any dependency is not terminal (`closed` counts as terminal and satisfies the guard — a voided dependency no longer blocks). `close` is never gated: withdrawing is always available, which is the escape hatch when a dependency stalls. Dependency cycles (incl. self-reference) are rejected; unknown dep ids are rejected.

## Sub-tasks (parentId) vs dependencies (blocks)
`parentId` = containment (this is a sub-task of …; cycles rejected, depth ≤ 5). `blocks` = ordering (this DEPENDS ON …). They are orthogonal and may coexist. Parent state is NEVER derived: all children terminal does NOT auto-complete the parent, and completing a parent never rewrites children — close or complete it explicitly.

## Change history and the note timeline (separate file, append-only)
The history's PRIMARY subject is the entry's note content — the engine appends a note entry for every Mail to that task's dispatcher, and every state change is recorded with both sides of the content so an earlier wording stays readable. Written automatically; you never write timestamps or actor names. Storage: its OWN append-only file ${PathUtil.dataRootRenderValue}/$historyFile, INDEPENDENT of the entry: pruning an entry does not delete its history — `show <id>` still renders that id's note timeline afterwards, marked `[gone]` (main-ledger fields then read "cleared, unavailable"; nothing is invented).

## Ids and lifecycle
Ids are NEVER reused: the ledger keeps a monotonic id watermark, so even after a `completed` / `closed` entry is pruned its id is not handed to another task — an id always maps to exactly one task's timeline. Terminal entries auto-prune 30 days after they reached the terminal state, on the next `create`; their change history survives the prune.

## Write-side limits
`title` ≤ 300 chars; `links` ≤ 20 items of ≤ 300 chars each. Reading never enforces a limit — existing data is never rejected. The note timeline is written by the engine, not here, and is capped on its own side (see `TaskInfo`).

## Notes
- Errors carry `TASK_*` codes — they are actionable, read them.
- `complete` and `close` are idempotent (reaching the state you are already in = no-op success).
- There is no `project` WALL here: unlike the retired per-project board, this is one ledger shared by the whole instance. A `project` field is a free-form tag, not a storage location.
- Cross-process writers are not locked. A corrupted ledger degrades reads to an empty view (never a crash); the first write after corruption quarantines the file under a `.corrupt-<millis>` name — nothing is silently overwritten, and the quarantine is recorded in the history."""

  /** Path literals (same source as the store/history constants; local private aliases to
    * avoid long qualified names). */
  private def tasksV2File: String = TaskLedgerStore.FileName
  private def historyFile: String = TaskLedgerHistory.FileName

  override def inputSchema: JsonObject = JsonObject(
    "type" -> "object".asJson,
    "properties" -> Json.obj(
      "action" -> Json.obj(
        "type"        -> "string".asJson,
        "enum"        -> Json.arr("create".asJson, "update".asJson, "list".asJson, "complete".asJson,
          "close".asJson, "show".asJson),
        "description" -> "create / update / list / complete / close / show (see description).".asJson
      ),
      "id" -> Json.obj(
        "type"        -> "string".asJson,
        "description" -> "Entry id (from list). Required for update / complete / close / show.".asJson
      ),
      "title" -> Json.obj(
        "type"        -> "string".asJson,
        "description" -> "Entry title (≤300 chars). Required for create; optional replacement for update.".asJson
      ),
      "assignee" -> Json.obj(
        "type"        -> "string".asJson,
        "description" -> "Node id | \"dispatcher\" | \"author\". create: optional (default \"dispatcher\"); update: replacement (empty string clears); list: exact filter.".asJson
      ),
      "nodeId" -> Json.obj(
        "type"        -> "string".asJson,
        "description" -> "Optional Flow Map node link (NodeDef.id). create: optional; update: replacement (empty string clears).".asJson
      ),
      "links" -> Json.obj(
        "type"        -> "array".asJson,
        "items"       -> Json.obj("type" -> "string".asJson),
        "description" -> "Free-form anchors (doc path | commit | id), ≤20 items of ≤300 chars. create: initial list; update: FULL replacement ([] clears). NOT validated for reachability.".asJson
      ),
      "blocks" -> Json.obj(
        "type"        -> "array".asJson,
        "items"       -> Json.obj("type" -> "string".asJson),
        "description" -> "Ids of entries this one depends on. create: initial list; update: FULL replacement ([] clears). `complete` is refused while a dependency is still open.".asJson
      ),
      "parentId" -> Json.obj(
        "type"        -> "string".asJson,
        "description" -> "Parent entry id (sub-task relation). create: optional; update: empty string clears; depth ≤5, cycles and unknown ids rejected.".asJson
      ),
      "project" -> Json.obj(
        "type"        -> "string".asJson,
        "description" -> "Free-form project tag. create: optional; update: empty string clears; list: exact-match filter. This is a tag, NOT a storage location — the ledger is a single instance-wide file.".asJson
      )
    ),
    "required" -> Json.arr("action".asJson)
  )

  override def summarize(input: JsonObject): String =
    val a = input("action").flatMap(_.asString).getOrElse("?")
    val id = input("id").flatMap(_.asString).map(i => s"#$i").getOrElse("")
    s"Task($a$id)"

  override def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 200 then result.take(197) + "..." else result

  override def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    IO.blocking {
      val action   = input("action").flatMap(_.asString).getOrElse("")
      val title    = input("title").flatMap(_.asString)
      val id       = input("id").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
      val assignee = input("assignee").flatMap(_.asString)
      val nodeId   = input("nodeId").flatMap(_.asString)
      val links    = input("links").flatMap(_.as[List[String]].toOption)
      val blocks   = input("blocks").flatMap(_.as[List[String]].toOption)
      val parentId = input("parentId").flatMap(_.asString)
      val project  = input("project").flatMap(_.asString)
      (action, title, id, assignee, nodeId, links, blocks, parentId, project)
    }.map { case (action, title, id, assignee, nodeId, links, blocks, parentId, project) =>
      TaskTool.dispatchSync(TaskLedgerStore.open(), nodeTerminalMapOf(), ctx, action,
        title = title, id = id, assignee = assignee, nodeId = nodeId, links = links,
        blocks = blocks, parentId = parentId, project = project)
    }

  /** The mapping for the ⚠node-done join: read live from the Flow Map of the workspace
    * this session's project belongs to (empty when there is none). */
  private def nodeTerminalMapOf(): Map[String, String] = Map.empty
end TaskToolDef

/**
 * The `TaskInfo` tool entity -- **zero parameters, read-only** view of the attributed
 * single entry (ruling b① / ruling F).
 *
 * Mount face: the ninth item of the dispatcher's fixed set (**capability inversion**:
 * `TaskBoard` with full rights → `TaskInfo` read-only) + appended by identity for project
 * nodes / dispatcher sessions. The attachment is resolved from the **session identity**
 * (`ctx.taskId`), **zero parameters** -- an explicit id parameter was rejected. No
 * attachment ⇒ an explicit refusal (**no fallback to the whole ledger**).
 */
object TaskInfoToolDef extends Tool:

  override def name: String = "TaskInfo"

  override def description: String =
    """TaskInfo — read-only view of the ONE task you are attached to, and of its note timeline. It has NO parameters: your attachment is resolved from your SESSION IDENTITY on the engine side (a dispatcher resolves to the task it was created for; a project node resolves to the task recorded as its origin), never from a client argument. You cannot name another task, and you cannot see any other task.

You cannot write here. Creation, state changes and any field edit belong to Nebula and go through the `Task` tool. The note timeline you read is written by the engine whenever Nebula Mails this task's dispatcher; your own progress reaches the task the same way it always did — through your report channel (`node_report`) and your final text, not by editing the task.

What it returns: the task's id, title, state (`open` / `closed` / `completed`), assignee, the linked Flow Map node if any, dependency state, links, and the note timeline (most recent entries first, each carrying its origin and time). Long timelines are windowed — the count of entries that did not fit is stated explicitly, never silently dropped.

If your session carries no task attachment (no origin task recorded), this tool FAILS with an explicit error naming your session and the reason — it never falls back to showing the whole ledger, and it never shows another task's data. That failure is deliberate policy: a session with no accountable task may not read task data. Report the missing attachment through your normal report channel instead."""

  /** 🔴 **Zero parameters**: `properties` = an empty object, `required` = an empty array.
    * The attachment is described in the description; addressing keys such as
    * `id`/`taskId`/`project` **must not** appear -- as soon as one does, the model tries to
    * fill it and the unauthorized read face is opened up by a client parameter. */
  override def inputSchema: JsonObject = JsonObject(
    "type" -> "object".asJson,
    "properties" -> Json.obj(),
    "required" -> Json.arr()
  )

  override def summarize(input: JsonObject): String = "TaskInfo"

  override def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 200 then result.take(197) + "..." else result

  override def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    IO.blocking(TaskTool.infoSync(TaskLedgerStore.open(), ctx))
end TaskInfoToolDef
