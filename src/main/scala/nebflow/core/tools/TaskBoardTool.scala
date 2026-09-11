package nebflow.core.tools

import cats.effect.IO
import io.circe.{Json, JsonObject}
import io.circe.syntax.*

import nebflow.core.project.{ProjectRuntime, TaskBoardHistory, TaskBoardStore}

/**
 * TaskBoard 工具（TaskBoard 批 2 接线，规格 .nebflow/Spec/20260908_project-task-board.md
 * §1b/§1d/§1e/§2，2026-09-08）——项目内共享工作项看板的工具面。
 *
 * 定位：Flow Map 管节点（执行单元），TaskBoard 管任务（工作项）；语义与全局
 * TaskList 同族（四态 + blocks 依赖闸 + close 幂等 + 30 天 prune——状态机/错误码
 * 全在 TaskBoardStore，批 1）。本工具只做两件事：project 解析（§1e）+ 权限判定
 * （§1d），动作本体全部委托 store 同步方法（IO.blocking 内调用）。
 *
 * project 解析（§1e）：显式 `project` 参数 > ctx.projectName（批 2 接通——分发器
 * /节点 spawn 注入）→ 报错；项目名经 ProjectRuntimeRegistry 校验（大小写不敏感
 * 兜底），复用 NodeTools.resolveProject 单点。
 *
 * 权限矩阵（§1d，引擎侧身份判定、不信客户端参数——安全红线）：
 *   | 动作   | 分发器                 | 节点                                  | 其他（Nebula/team/flow 双轨/REST） |
 *   | create | ✅ 全量                | ❌（决策 b：规划权威单点=分发器）      | ❌ |
 *   | update | ✅ 全板任意任务含结构字段 | ⚠ 仅自己名下（assignee=自身节点 id）且仅 status+note 两字段 | ❌ |
 *   | close  | ✅ 全板                | ⚠ 仅自己名下                          | ❌ |
 *   | list   | ✅ 全板                | ✅ 全板只读                            | ❌ |
 *   | show   | ✅ 全板                | ✅ 全板只读（升级批 R3/R8）            | ❌ |
 *   | log    | ✅ 全板                | ⚠ 仅自己名下（升级批 R1）              | ❌ |
 * 身份来源：ctx.isDispatcher（ProjectActor 分发器 spawn 置位）/ ctx.flowNodeId
 * （NodeEngine spawn 置位 NodeDef.id）。非项目会话两字段皆空 → 工具未挂载（挂载
 * 面 §1c）+ 本处 TBOARD_FORBIDDEN 双保险不可达。
 *
 * 变更史 actor 派生（升级批 R7）：身份 → `dispatcher` | `node`（system 由工具内建
 * 事件即 prune/quarantine/rotate 写）；客户端参数一律忽略（不信客户端参数）。
 *
 * ⚠node-done（§2d）：list 渲染时对带 nodeId 的任务 join Flow Map 真实终态
 * （completed/failed/cancelled——blocked 节点永不过期不标），只读漂移标记，不改状态。
 *
 * 跨进程并发不锁（TaskListTool.scala:29 纪律同款）；错误码 TBOARD_* 语义逐条
 * 对齐 TASKLIST_*（Codes 族单点在 TaskBoardStore.Codes）。
 */

/** 调用者身份（§1d 三值；从 ToolContext 引擎侧字段派生，不信客户端参数）。 */
sealed trait BoardCaller extends Product with Serializable
object BoardCaller:
  case object Dispatcher extends BoardCaller
  case class FlowNode(nodeId: String) extends BoardCaller
  case object Other extends BoardCaller

  /** 引擎侧身份派生：分发器标记优先；其次 flow 节点 id；两者皆空 = 非项目会话。 */
  def fromContext(ctx: ToolContext): BoardCaller =
    if ctx.isDispatcher then Dispatcher
    else ctx.flowNodeId.map(FlowNode.apply).getOrElse(Other)

object TaskBoardTool:

  /** 权限拒绝（可行动文案：说身份、说越权点、给出路）。 */
  private def forbidden(reason: String): ToolError = ToolError(
    s"TaskBoard: permission denied — $reason (${TaskBoardStore.Codes.Forbidden})")

  // ------------------------------------------------------------------
  // 权限判定 + 动作分派（同步核心；call 在 IO.blocking 内调用——可单测）
  // ------------------------------------------------------------------

  /** 工具六动词分派（§1d 矩阵逐格落实 + 升级批 log/show；动作本体委托 store）。
    * nodeTerminal 为 ⚠node-done join 映射（list/show 用；调用方从 Flow Map 快照取
    * 真实终态）。`actor` 由身份派生后传给 store 写史（引擎侧，不信客户端参数）。 */
  def dispatchSync(
    board: TaskBoardStore,
    nodeTerminal: Map[String, String],
    caller: BoardCaller,
    action: String,
    title: Option[String] = None,
    id: Option[String] = None,
    status: Option[String] = None,
    assignee: Option[String] = None,
    nodeId: Option[String] = None,
    note: Option[String] = None,
    blocks: Option[List[String]] = None,
    text: Option[String] = None,
    links: Option[List[String]] = None
  ): Either[ToolError, String] =
    caller match
      // 双保险第二道：非项目会话（Nebula/team/flow 双轨/REST 直调）工具未挂载
      // 仍可能经声明逃逸触达——此处按 §1d 一律拒绝（含 list/show）。
      case BoardCaller.Other =>
        Left(forbidden("this session has no project board identity (dispatcher/node only)"))
      case BoardCaller.Dispatcher =>
        dispatcherDispatch(board, nodeTerminal, action, title, id, status, assignee, nodeId, note, blocks, text, links)
      case BoardCaller.FlowNode(nid) =>
        nodeDispatch(board, nodeTerminal, nid, action, title, id, status, assignee, nodeId, note, blocks, text, links)

  /** 分发器：全权（§1d 列 1）——create 全量 / update 全板任意任务含结构字段 /
    * close 全板 / list 全板 / **show 全板 / log 全板**。参数原样透传 store。 */
  private def dispatcherDispatch(
    board: TaskBoardStore,
    nodeTerminal: Map[String, String],
    action: String,
    title: Option[String],
    id: Option[String],
    status: Option[String],
    assignee: Option[String],
    nodeId: Option[String],
    note: Option[String],
    blocks: Option[List[String]],
    text: Option[String],
    links: Option[List[String]]
  ): Either[ToolError, String] =
    action match
      case "create" =>
        board.createSync(title = title.getOrElse(""), assignee = assignee, nodeId = nodeId, note = note,
          blocksRaw = blocks, linksRaw = links, actor = TaskBoardHistory.Actors.Dispatcher)
      case "update" =>
        id match
          case None => Left(ToolError(s"TaskBoard: update requires `id` (entry id from action=list). (${TaskBoardStore.Codes.Param})"))
          case Some(i) => board.updateSync(i, title, status, assignee, nodeId, note, blocks, links,
            actor = TaskBoardHistory.Actors.Dispatcher)
      case "close" =>
        id match
          case None => Left(ToolError(s"TaskBoard: close requires `id` (entry id from action=list). (${TaskBoardStore.Codes.Param})"))
          case Some(i) => board.closeSync(i, note, actor = TaskBoardHistory.Actors.Dispatcher)
      case "log" =>
        id match
          case None => Left(ToolError(s"TaskBoard: log requires `id` (entry id from action=list). (${TaskBoardStore.Codes.Param})"))
          case Some(i) => board.logSync(i, text.getOrElse(""), links, actor = TaskBoardHistory.Actors.Dispatcher)
      case "show" =>
        id match
          case None => Left(ToolError(s"TaskBoard: show requires `id` (entry id from action=list). (${TaskBoardStore.Codes.Param})"))
          case Some(i) => board.showSync(i, nodeTerminal)
      case "list" =>
        board.listSync(status = status, assignee = assignee, nodeTerminal = nodeTerminal)
      case other =>
        Left(ToolError(s"TaskBoard: unknown action '$other' — one of create/update/list/close/log/show. (${TaskBoardStore.Codes.Param})"))

  /** 节点：受限（§1d 列 2）——create 禁止（决策 b，拆活走 BLOCKED/needs-split 闭环）；
    * update/close/log 仅自己名下（assignee=自身节点 id）且 update 仅 status+note
    * 两字段（title/assignee/nodeId/blocks 与升级批新增的 links 均属结构字段，只读，
    * 任一出现即拒）；list/show 全板只读（status/assignee 过滤参数与分发器同面——
    * 只读过滤无越权面）。 */
  private def nodeDispatch(
    board: TaskBoardStore,
    nodeTerminal: Map[String, String],
    selfNodeId: String,
    action: String,
    title: Option[String],
    id: Option[String],
    status: Option[String],
    assignee: Option[String],
    nodeId: Option[String],
    note: Option[String],
    blocks: Option[List[String]],
    text: Option[String],
    links: Option[List[String]]
  ): Either[ToolError, String] =
    action match
      case "create" =>
        Left(forbidden("nodes cannot create tasks (planning authority is the dispatcher). " +
          "If the task needs splitting, end your output with a BLOCKED result (category=needs-split) instead"))
      case "list" =>
        board.listSync(status = status, assignee = assignee, nodeTerminal = nodeTerminal)
      case "show" =>
        id match
          case None =>
            Left(ToolError(s"TaskBoard: show requires `id` (entry id from action=list). (${TaskBoardStore.Codes.Param})"))
          case Some(i) => board.showSync(i, nodeTerminal)
      case "update" | "close" | "log" =>
        id match
          case None =>
            Left(ToolError(s"TaskBoard: $action requires `id` (entry id from action=list). (${TaskBoardStore.Codes.Param})"))
          case Some(i) =>
            // 归属判定走读路径视图（单宿主内 TOCTOU 窗口可忽略——申报）；
            // 条目不存在 → 委托 store 返回带 open 清单指引的 TBOARD_NOT_FOUND。
            board.entriesSync().find(_.id == i) match
              case Some(e) if !e.assignee.contains(selfNodeId) =>
                Left(forbidden(s"task #$i is assigned to '${e.assignee.getOrElse("(none)")}', not to your node id " +
                  s"'$selfNodeId' — you may only touch tasks assigned to you (action=list to see yours)"))
              case Some(_) =>
                if action == "close" then board.closeSync(i, note, actor = TaskBoardHistory.Actors.Node)
                else if action == "log" then board.logSync(i, text.getOrElse(""), links, actor = TaskBoardHistory.Actors.Node)
                else
                  // 结构字段只读（§1d）：节点 update 携带 title/assignee/nodeId/blocks/links
                  // 任一 → 拒绝（字段出现即改动请求，与值无关）。
                  if title.isDefined || assignee.isDefined || nodeId.isDefined || blocks.isDefined || links.isDefined then
                    Left(forbidden("nodes may only change `status` and `note` of their own tasks — " +
                      "title/assignee/nodeId/blocks/links are dispatcher-only fields; request the change via your report channel"))
                  else board.updateSync(i, status = status, note = note, actor = TaskBoardHistory.Actors.Node)
              case None =>
                if action == "close" then board.closeSync(i, note, actor = TaskBoardHistory.Actors.Node)
                else if action == "log" then board.logSync(i, text.getOrElse(""), links, actor = TaskBoardHistory.Actors.Node)
                else board.updateSync(i, actor = TaskBoardHistory.Actors.Node)
      case other =>
        Left(ToolError(s"TaskBoard: unknown action '$other' — one of create/update/list/close/log/show. (${TaskBoardStore.Codes.Param})"))
end TaskBoardTool

/**
 * TaskBoard 工具实体（挂载面 §1c：分发器 DispatcherFixedTools 第九件 + project
 * 节点会话按身份追加；Nebula 一期不挂、plugins 声明不授能——NebulaExclusiveTools
 * 防声明逃逸，双保险见 TaskBoardTool.dispatchSync 的 Other 拒绝）。
 */
object TaskBoardToolDef extends Tool:

  /** ⚠node-done 的 Flow Map join：项目运行时 → nodeId→终态映射（仅 completed/
    * failed/cancelled 计入，§2d；映射构造单点 TaskBoardStore.nodeTerminalMap）。
    * 批 2 接线点：批 1 renderer 的 nodeDone 是纯函数入参，本处从 Flow Map 快照
    * 取真实终态（rt.engine.store.snapshot 现读，不缓存）。 */
  private def nodeTerminalOf(rt: ProjectRuntime): IO[Map[String, String]] =
    rt.engine.store.snapshot.map(snap => TaskBoardStore.nodeTerminalMap(snap.nodes.values))

  /** 解析错误统一带 TBOARD 码（project 解析文本复用 NodeTools 单点）。 */
  private def resolveError(msg: String): ToolError =
    ToolError(s"TaskBoard: $msg (${TaskBoardStore.Codes.Param})")

  override def name: String = "TaskBoard"

  override def description: String =
    """Project-scoped shared task board — work items for THIS project (Flow Map tracks nodes = execution units; TaskBoard tracks tasks = work items). Storage: <project workspace>/.nebflow/task-board.json (one board per project, shared by dispatcher + all project nodes); the change history lives in <project workspace>/.nebflow/task-history.jsonl (append-only, one history file per board). Same family as TaskList: four states, blocks dependency guard, idempotent close, 30-day prune.
## Who may do what (identity comes from the session, NOT from your arguments)
- dispatcher: full control — create any task, update/close/log ANY task incl. structural fields (title/assignee/nodeId/blocks/links).
- project node (you, if the board showed you a work order): update/close/log ONLY tasks whose assignee is YOUR node id, and update only `status` + `note` (structural fields are dispatcher-only); list/show the whole board read-only. Nodes NEVER create tasks — if the work needs splitting, end with a BLOCKED result (category=needs-split) instead.
- everyone else: no access.
## Actions
- create (dispatcher): required `title`; optional `assignee` (node id | "dispatcher" | "author"; default "dispatcher"), `nodeId` (optional Flow Map link), `links`, `note`, `blocks` (ids this entry DEPENDS on).
- update: required `id`; optional `status` ("open" | "in_progress" | "blocked" — NEVER "done", use close), `note` (REPLACES the note), `links` (FULL replacement, [] clears). Dispatcher may also pass `title`/`assignee`/`nodeId` (empty string clears) / `blocks` (FULL replacement).
- close: required `id`; optional `note` appended as a "[done] outcome" line. Idempotent (closing a done entry = no-op success; its `note` argument is then deliberately ignored).
- log: required `id` + `text` — APPEND a record to the entry's history WITHOUT touching `note` or the board (the safe way to add progress/results: unlike update, it cannot lose or rewrite existing text). Optional `links` (anchors for this record). Rejected (TBOARD_NOT_FOUND) if the entry does not exist.
- list: optional `status` / `assignee` filters. Rows carry markers: ⚠deps-open (a dependency is not done yet), ⚠node-done (the linked Flow Map node reached a terminal state but the task is not closed — dispatcher decides: close / reassign / reopen).
- show: required `id` — full detail for ONE entry: every field, the FULL current note, links, current dependency states, reverse dependencies (who depends on this one, ≤20 + "(+N more)") and the change history in two sections: note-content changes first (each `update` that replaced the note and each `close` outcome is shown with BOTH the previous and the new content, oldest first, so any earlier wording is still readable) and state events second (create / status-only updates / prune, minimal lines). Use it instead of reading the board JSON by hand. If the entry was pruned away but its history survives, show renders that history with a `#<id>[gone]` marker instead of failing; only an id absent from both board and history returns TBOARD_NOT_FOUND.
## Status machine (TaskList heritage)
open → in_progress → done (via close only); open/in_progress → blocked (record a wait); blocked → open or blocked → in_progress. Same-state update = no-op. done is TERMINAL: update with status="done" is rejected (TBOARD_DONE_VIA_CLOSE — use close). in_progress→open rejected.
## Dependency guard
Starting (→in_progress) or closing is REJECTED while any `blocks` dependency is not done (TBOARD_BLOCKED lists them): close them first, or set this entry to blocked to record the wait. Cycles rejected (TBOARD_CYCLE); unknown dep ids rejected (TBOARD_BLOCK_UNKNOWN).
## History, links and write-side limits
- Change history is a NOTE-CENTRIC record: `log` appends a record (its `text` is stored in full), `update` that replaces `note` stores the previous AND the new note content, `close` with an outcome stores the note before and after the `[done]` line. So the note's evolution can be reconstructed from `action=show` (or from the raw JSONL) — the previous wording of a note is never lost when it is replaced. Task STATUS changes (create / status-only update / prune) are recorded too, but only as minimal lines in a secondary section: they must never drown the note mainline.
- One append-only file per board: <workspace>/.nebflow/task-history.jsonl (every line carries the task `id`, so filtering by id gives that task's timeline; the file is NOT split per task — one file keeps rotation/capacity logic in one place). Ids are NEVER reused: the board file keeps a monotonic id watermark, so even after a done entry is pruned away its id is not handed to another task — an id always maps to exactly one task's timeline (a pruned id reads back as `#<id>[gone]`, never as somebody else's history). Rotation is lazy and write-triggered: an active file over 5 MiB or 20,000 lines becomes task-history.1.jsonl (previous generation replaced) => at most ~10 MiB on disk. History IS allowed to be truncated/rotated away (the record is a rolling audit trail, not an archive); what is never lost is a note version inside the retention window. Read it with action=show — never by editing the file. Manual cleanup: `mv .../task-history.jsonl{,.archive-<date>}` (archived; a new one is created on the next write) or `rm .../task-history.jsonl*` (drops history only — task-board.json is untouched).
- `links` are free-form anchors (doc paths, commit hashes, cross-ledger ids). They are NOT validated for reachability, so a stale path or a rebased short hash stays silently — that is accepted; use them as searchable pointers, not as live references.
- Write-side limits (this tool's own values, NOT TaskList's): `note` and close outcome ≤16000 chars (board baseline 2026-09-11: note min 128 / P50 765 / P90 2322 / P99 3456 / max 10993 over 218 entries — 16000 ≥ that max), `title` ≤300, `log` `text` ≤4000 (split long content into several action=log calls), `links` ≤20 items of ≤300 chars each. Over the limit => TBOARD_PARAM with the fix. Reading never enforces these limits (existing data is never rejected).
## Notes
- Errors carry TBOARD_* codes — actionable, read them. TBOARD_HISTORY means the history file could not be written (the log was NOT recorded; the board was not changed).
- No explicit `project` → your session's project is used (dispatcher/node sessions carry it); cross-process writers are not locked. The same applies to the history file and to the corrupted-file quarantine: if task-board.json cannot be decoded it is renamed to task-board.json.corrupt-<millis> and a fresh store is started (quarantine is recorded in the history); a corrupt line inside task-history.jsonl is skipped on read and reported by show (it never blocks the board)."""

  override def inputSchema: JsonObject = JsonObject(
    "type" -> "object".asJson,
    "properties" -> Json.obj(
      "action" -> Json.obj(
        "type"        -> "string".asJson,
        "enum"        -> Json.arr("create".asJson, "update".asJson, "list".asJson, "close".asJson,
          "log".asJson, "show".asJson),
        "description" -> "create / update / list / close / log / show (see description).".asJson
      ),
      "title" -> Json.obj(
        "type"        -> "string".asJson,
        "description" -> "Entry title (≤300 chars). Required for create; dispatcher-only replacement for update.".asJson
      ),
      "id" -> Json.obj(
        "type"        -> "string".asJson,
        "description" -> "Entry id (from list). Required for update/close/log/show.".asJson
      ),
      "status" -> Json.obj(
        "type"        -> "string".asJson,
        "enum"        -> Json.arr("open".asJson, "in_progress".asJson, "blocked".asJson),
        "description" -> "New status for update. \"done\" is reached via action=close only.".asJson
      ),
      "assignee" -> Json.obj(
        "type"        -> "string".asJson,
        "description" -> "Node id | \"dispatcher\" | \"author\". create: optional (default \"dispatcher\"); update: dispatcher-only (empty string clears); list: exact filter.".asJson
      ),
      "nodeId" -> Json.obj(
        "type"        -> "string".asJson,
        "description" -> "Optional Flow Map node link (NodeDef.id). create: optional; update: dispatcher-only (empty string clears).".asJson
      ),
      "note" -> Json.obj(
        "type"        -> "string".asJson,
        "description" -> "create: initial note; update: REPLACES the note; close: appended as a [done] outcome line; log: untouched. ≤16000 chars (long content → action=log).".asJson
      ),
      "text" -> Json.obj(
        "type"        -> "string".asJson,
        "description" -> "action=log only: the record to APPEND to the entry's history (≤4000 chars; it never rewrites `note`).".asJson
      ),
      "links" -> Json.obj(
        "type"        -> "array".asJson,
        "items"       -> Json.obj("type" -> "string".asJson),
        "description" -> "Free-form anchors (doc path | commit | id), ≤20 items of ≤300 chars. create: initial list; update: FULL replacement ([] clears); log: anchors for that record. NOT validated for reachability.".asJson
      ),
      "blocks" -> Json.obj(
        "type"        -> "array".asJson,
        "items"       -> Json.obj("type" -> "string".asJson),
        "description" -> "Ids of entries this one depends on. create: initial list; update: FULL replacement ([] clears). Dispatcher-only.".asJson
      ),
      "project" -> Json.obj(
        "type"        -> "string".asJson,
        "description" -> "Optional project name override. Defaults to this session's project context.".asJson
      )
    ),
    "required" -> Json.arr("action".asJson)
  )

  override def summarize(input: JsonObject): String =
    val a = input("action").flatMap(_.asString).getOrElse("?")
    val id = input("id").flatMap(_.asString).map(i => s"#$i").getOrElse("")
    s"TaskBoard($a$id)"

  override def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 200 then result.take(197) + "..." else result

  override def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    // 参数解析在 blocking 内（纯 CPU，亚毫秒）；resolveProject 走 IO（registry Ref）。
    IO.blocking {
      val action  = input("action").flatMap(_.asString).getOrElse("")
      val title   = input("title").flatMap(_.asString)
      val id      = input("id").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
      val status  = input("status").flatMap(_.asString)
      val project = input("project").flatMap(_.asString)
      val assignee = input("assignee").flatMap(_.asString)
      val nodeId  = input("nodeId").flatMap(_.asString)
      val note    = input("note").flatMap(_.asString)
      val blocks  = input("blocks").flatMap(_.as[List[String]].toOption)
      // 升级批参数：log 的正文与关联锚（客户端传入的 actor/history 一律忽略——
      // actor 由身份派生，见 description）
      val text    = input("text").flatMap(_.asString)
      val links   = input("links").flatMap(_.as[List[String]].toOption)
      (action, title, id, status, project, assignee, nodeId, note, blocks, text, links)
    }.flatMap { case (action, title, id, status, project, assignee, nodeId, note, blocks, text, links) =>
      NodeTools.resolveProject(project, ctx).flatMap {
        case Left(err) => IO.pure(Left(resolveError(err)))
        case Right(rt) =>
          rt.board match
            case None =>
              IO.pure(Left(ToolError(
                s"TaskBoard: project '${rt.project.name}' has no board mounted (runtime built without one). (${TaskBoardStore.Codes.Param})")))
            case Some(board) =>
              nodeTerminalOf(rt).flatMap { terminal =>
                IO.blocking(TaskBoardTool.dispatchSync(
                  board, terminal, BoardCaller.fromContext(ctx), action,
                  title = title, id = id, status = status, assignee = assignee,
                  nodeId = nodeId, note = note, blocks = blocks, text = text, links = links))
              }
      }
    }
end TaskBoardToolDef
