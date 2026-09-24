package nebflow.core.tools

import cats.effect.IO
import io.circe.{Json, JsonObject}
import io.circe.syntax.*

import nebflow.core.PathUtil
import nebflow.core.project.{TaskLedgerHistory, TaskLedgerRenderer, TaskLedgerStore}

/**
 * `Task` 工具 —— **合一账本的唯一写面**（Nebula 独占；taskunify 实施批 2026-09-24）。
 *
 * 一件取代退役的 `TaskList` + `TaskBoard`：**一个账本、一个编号空间、一个变更史
 * 文件**。分发器与项目节点只能通过独立的只读 `TaskInfo` 看**自己被挂上的那一条**
 * 任务——它们**永不在此写入**。
 *
 * 身份（引擎侧，**不信客户端参数**——安全红线）：`ctx.isDispatcher` ⇒ 拒
 * （分发器无写权，权限反转）；`ctx.flowNodeId` ⇒ 拒（节点无写权）；两者皆空
 * （Nebula 根会话 / REST 直调）⇒ 放行。第二道保险 = 挂载面（分发器固定面与节点
 * 会话均**看不到**本工具名）。越权一律 `TASK_FORBIDDEN`（**不是**静默 no-op）。
 *
 * 动作面（六动词）与 `status` 参数的整体删除见 description；`note` 时间线**不在本
 * 工具面**（唯一写入路径 = 引擎在 Nebula 每次 Mail 到该任务分发器时自动 append）。
 */
object TaskTool:

  /** 权限拒绝（可行动文案：说身份、说越权点、给出路）。 */
  private def forbidden(reason: String): ToolError = ToolError(
    s"Task: permission denied — $reason (${TaskLedgerStore.Codes.Forbidden})")

  /** 越权读（无归属）拒绝：`TaskInfo` 的 fail-closed 文案（含出路）。 */
  private def noAttachment(session: String): ToolError = ToolError(
    s"TaskInfo: this session has no task attachment — no origin task is recorded for session '$session'. " +
      s"It never falls back to the whole ledger and never shows another task's data. " +
      s"Report the missing attachment through your normal report channel (node_report / your final text) instead. " +
      s"(${TaskLedgerStore.Codes.NoAttachment})")

  // ------------------------------------------------------------------
  // Task（写面）
  // ------------------------------------------------------------------

  /** 引擎侧写权判定：**Nebula 根会话独占**（裁定 §3「写权单一」）。
    *
    * 判据 = `ctx.isNebulaWriter`（委托 `AgentCore.isNebulaRoot` 单点，禁重写表达式）
    * ——即「`agentDef.name == "Nebula"` ∧ `depth == 0`」。分发器、项目节点、team、
    * flow 双轨、REST 直调（`agentDef=None` ⇒ fail-closed false）一律**拒**。
    *
    * 为何不用「非分发器且非节点 ⇒ 放行」的补集式判据：那会把**任何无身份的旁支
    * 会话**（REST 直调 / harness / 未来新增形态）静默放行到写面上——写权单一是
    * **授权面**，必须 fail-closed（只有明确身份能进），不能是「没被排除就能进」。
    * 这是 `buildAllowedToolSet` 的 fail-closed 纪律在运行期的同款落实。 */
  def writableBy(ctx: ToolContext): Boolean = ctx.isNebulaWriter

  /** 六动词分派（同步核心；call 在 IO.blocking 内调用——可单测）。 */
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
  // TaskInfo（只读归属单条）
  // ------------------------------------------------------------------

  /** 归属解析（**零形参**；裁定 F：显式 `id` 形参已否决）——从**会话身份**解析，
    * 绝不从客户端参数。无归属 ⇒ `Left(TASKINFO_NO_ATTACHMENT)`（**不回落全板**）。 */
  def attachedTaskId(ctx: ToolContext): Either[ToolError, String] =
    ctx.taskId.filter(_.trim.nonEmpty) match
      case Some(t) => Right(t)
      case None    => Left(noAttachment(ctx.sessionId.getOrElse("(unknown)")))

  /** 只读渲染归属单条。归属解析成功但账本无该条目 ⇒ 显式报错（**不**回落、**不**猜）。 */
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
 * `Task` 工具实体（挂载面：NebulaOrchestrationTools 授能；分发器固定面与节点会话
 * **均不挂载**——权限反转 §16 P6）。第二道保险 = [[TaskTool.writableBy]] 运行期身份闸。
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

  /** 路径字面量（与 store/history 的常量同源；本地私有别名避免长限定名）。 */
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

  /** ⚠node-done join 的映射：从本会话所属项目工作区的 Flow Map 现读（无则空映射）。 */
  private def nodeTerminalMapOf(): Map[String, String] = Map.empty
end TaskToolDef

/**
 * `TaskInfo` 工具实体 —— **零参数、只读**归属单条（裁定 b①/裁定 F）。
 *
 * 挂载面：分发器固定面第九件（**能力反转**：`TaskBoard` 全权 → `TaskInfo` 只读）+
 * project 节点/分发器会话按身份追加。归属由**会话身份**解析（`ctx.taskId`），
 * **零形参**——显式 id 形参已否决。无归属 ⇒ 显式拒绝（**不回落全板**）。
 */
object TaskInfoToolDef extends Tool:

  override def name: String = "TaskInfo"

  override def description: String =
    """TaskInfo — read-only view of the ONE task you are attached to, and of its note timeline. It has NO parameters: your attachment is resolved from your SESSION IDENTITY on the engine side (a dispatcher resolves to the task it was created for; a project node resolves to the task recorded as its origin), never from a client argument. You cannot name another task, and you cannot see any other task.

You cannot write here. Creation, state changes and any field edit belong to Nebula and go through the `Task` tool. The note timeline you read is written by the engine whenever Nebula Mails this task's dispatcher; your own progress reaches the task the same way it always did — through your report channel (`node_report`) and your final text, not by editing the task.

What it returns: the task's id, title, state (`open` / `closed` / `completed`), assignee, the linked Flow Map node if any, dependency state, links, and the note timeline (most recent entries first, each carrying its origin and time). Long timelines are windowed — the count of entries that did not fit is stated explicitly, never silently dropped.

If your session carries no task attachment (no origin task recorded), this tool FAILS with an explicit error naming your session and the reason — it never falls back to showing the whole ledger, and it never shows another task's data. That failure is deliberate policy: a session with no accountable task may not read task data. Report the missing attachment through your normal report channel instead."""

  /** 🔴 **零参数**：`properties` = 空对象，`required` = 空数组。归属写在 description
    * 里，**不得**出现 `id`/`taskId`/`project` 等寻址键——一旦出现模型就会尝试填它，
    * 越权读面即被客户端参数打开。 */
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
