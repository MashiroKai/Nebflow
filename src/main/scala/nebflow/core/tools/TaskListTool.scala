package nebflow.core.tools

import cats.effect.IO
import io.circe.Codec
import io.circe.Json
import io.circe.syntax.*
import io.circe.JsonObject
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.parser.decode

import java.time.Instant

import scala.collection.mutable

import nebflow.core.{AtomicJson, NebflowLogger, PathUtil}

/**
 * TaskList 任务工具（2026-09-06 作者 00:07 提议 + 00:11 首期无前端拍板）。
 *
 * 动机（设计文档 ~/.nebflow/memory/tasklist-design.md）：「Nebula 把记忆当任务
 * 工具用」根因 = 记忆是唯一持久外部存储。三边界：记忆 = T1 慢变事实（永久）；
 * 任务 = 快变状态（闭环即退役）；Flow Map = 单次派发执行。本工具是 Nebula 专属
 * 编排件（工具面隔离：仅 NebulaOrchestrationTools 携带 + NebulaExclusiveTools
 * 防声明逃逸剥离，dispatcher/general 面零出现），首期纯工具面、零 web/ 改动。
 *
 * 存储：`<dataRoot>/tasks.json`（~/.nebflow/tasks.json）——运行时数据层：
 * 不进 git、不进任何 system prompt 注入。落盘用 AtomicJson（tmp + ATOMIC_MOVE
 * rename，crash-safe）；进程内单锁串行化读-改-写；跨进程写入不在锁面内
 * （MemoryEditTool 同款取舍，description 已明示）。
 *
 * 无内存态：每次操作整读整写同一文件——重启后 tasks.json 自然持久（验收项）。
 *
 * 条目 schema（任务书口径）：id / title / status / project? / note? / blocks。
 * `blocks` = 本条目【依赖】的条目 id 列表（任务书原文「blocks(依赖条目 id 列表)」
 * ——即旧 TeamTask 的 blockedBy 方向；申报差异点）。
 *
 * 状态机（语义抄旧 TeamTask 四态机精神，按本 schema 落地四态）：
 *   open → in_progress → done；open/in_progress → blocked（记录等待）；
 *   blocked → open（回退待办）/ blocked → in_progress（恢复，受依赖闸）。
 *   - done 是终态：done → 任何状态拒绝（含 done→open，任务书明示必须拒绝）。
 *   - 进入 done 唯一路径 = close action（update 携带 status="done" 结构化拒绝，
 *     指向 close——终态迁移单通道）。
 *   - 同态迁移（如 in_progress→in_progress）= no-op 放行（TeamTask 同款）。
 *   - in_progress → open 不允许（TeamTask in_progress→pending 同款拒绝）。
 *
 * blocks 依赖闸（任务书第 3 条「被依赖条目未闭环时，受制条目迁移到
 * in_progress/done 的行为按设计文档语义落地」；设计文档 = 语义抄 TeamTask 四态
 * 机 + 本 schema 落地——落地为硬闸，边界情况见交付申报）：
 *   - 迁移到 in_progress 与 close（→done）要求 blocks 列表内全部条目均已 done；
 *     未闭环 → TASKLIST_BLOCKED 拒绝，错误列出未闭环依赖并给两条出路：
 *     先闭环依赖，或显式置 status=blocked 记录等待。
 *   - 迁移到 blocked 不设闸（记录等待本身是逃生通道）。
 *   - 依赖图环（含自依赖）→ TASKLIST_CYCLE 拒绝（DFS，TeamTask #3 同款）。
 *   - 依赖不存在 id → TASKLIST_BLOCK_UNKNOWN 拒绝；依赖已 done 的条目合法
 *     （闸恒过，记录「依赖已完成项」合法）。
 *
 * 损坏容错（任务书验收：坏 JSON 不崩、按可恢复策略处理并申报）：
 *   - 读路径（list / openSummaryLine）：损坏 → 空视图 + WARN 日志，绝不崩、
 *     绝无写副作用。
 *   - 写路径（create/update/close）：损坏 → 隔离 quarantaine（改名
 *     tasks.json.corrupt-<millis>，旧字节全保留供人工恢复）+ 全新空库继续本次
 *     写入。策略申报：隔离 + 空库续写，不静默覆盖。
 *
 * 闭环即删（T2 精神）：done 条目保留 note 作审计，create 时顺带清理 closedAt
 * 超 30 天的 done 条目（惰性清理，无定时器；解析失败的保守保留）。
 *
 * 注入语义：不全量进 system prompt；openSummaryLine 只在生命周期节点（重启/
 * 压缩，MemoryHygieneSignal 先例）随 Nebula 记忆块注入一行 open 摘要；全 done
 * 后该行为空（提醒消失）。按需明细一律走本工具 list。
 */
/** TaskList 条目（TaskModel.scala 同款顶层 case class + companion givens——嵌套
  * object 内 ConfiguredCodec.derived 在 Scala 3.5 下触发镜像前向引用错误）。
  * withDefaults：缺字段回默认（零迁移）；未知键容忍（strictDeserialization 关）。 */
case class TaskListEntry(
  id: String,
  title: String,
  status: String = TaskListStore.Status.Open,
  project: Option[String] = None,
  note: Option[String] = None,
  blocks: List[String] = Nil,
  createdAt: Option[String] = None,
  updatedAt: Option[String] = None,
  closedAt: Option[String] = None
)
object TaskListEntry:
  given Configuration = Configuration.default.withDefaults
  given Codec[TaskListEntry] = ConfiguredCodec.derived

/** tasks.json 顶层结构：version + tasks。 */
case class TaskListData(version: Int = 1, tasks: List[TaskListEntry] = Nil)
object TaskListData:
  given Configuration = Configuration.default.withDefaults
  given Codec[TaskListData] = ConfiguredCodec.derived

object TaskListStore:

  private val logger = NebflowLogger.forName("nebflow.tasklist")

  // ---- 状态常量（wire 格式唯一来源）----
  object Status:
    val Open       = "open"
    val InProgress = "in_progress"
    val Done       = "done"
    val Blocked    = "blocked"
    val all: Set[String] = Set(Open, InProgress, Done, Blocked)

  /** done 条目保留期（create 时惰性清理，「闭环即删」T2 精神）。 */
  val DoneTtlDays: Long = 30L

  type Entry = TaskListEntry
  val Entry = TaskListEntry

  type Store = TaskListData
  val Store = TaskListData

  /** `def` 非 `val`：PathUtil.dataRoot 可被测试换根（FileTaskStore 同款陷阱）。 */
  private def file: os.Path = PathUtil.dataRoot / "tasks.json"

  /** 进程内单锁：整段读-改-写串行化（单文件，无需 per-path 锁）。跨进程不在面内。 */
  private val fileLock = new Object

  // ------------------------------------------------------------------
  // 读 / 写原语
  // ------------------------------------------------------------------

  private def readSync(): Either[String, Store] =
    if !os.exists(file) then Right(Store())
    else
      // 读取本身抛异常（权限/目录顶替等）与解码失败同归损坏路径——不崩
      scala.util.Try(decode[Store](os.read(file))) match
        case scala.util.Success(Right(store)) => Right(store)
        case scala.util.Success(Left(err))    => Left(err.getMessage)
        case scala.util.Failure(e)            => Left(e.getMessage)

  /** 读路径视图：损坏 → 空视图 + WARN（零副作用）。 */
  private def readViewSync(): Store =
    readSync() match
      case Right(store) => store
      case Left(reason) =>
        logger.warnSync(s"[tasklist] tasks.json corrupted, read path degrades to empty view: $reason")
        Store()

  /** 写路径读：损坏 → 隔离（改名保旧字节）+ 全新空库。返回 (库, 隔离文件名?)。 */
  private def readForWriteSync(): (Store, Option[String]) =
    readSync() match
      case Right(store) => (store, None)
      case Left(reason) =>
        val quarantine = os.Path(s"${file}.corrupt-${System.currentTimeMillis()}")
        val renamed =
          try
            os.move(file, quarantine)
            Some(quarantine.last)
          catch case _: Exception => None
        logger.warnSync(
          s"[tasklist] tasks.json corrupted ($reason) — quarantined to ${renamed.getOrElse("(rename failed, proceeding on empty store)")}, starting fresh store")
        (Store(), renamed)

  private def writeSync(store: Store): Unit =
    AtomicJson.writeSync(file, store.asJson.noSpaces)

  // ------------------------------------------------------------------
  // 校验器
  // ------------------------------------------------------------------

  /** 状态迁移矩阵（TeamTask 四态机精神落地）。同态 = no-op 放行。 */
  private[tools] def isValidTransition(from: String, to: String): Boolean =
    (from, to) match
      case (f, t) if f == t                  => true // no-op（含 done→done 幂等）
      case (Status.Open, Status.InProgress)  => true
      case (Status.Open, Status.Blocked)     => true
      case (Status.InProgress, Status.Blocked) => true
      case (Status.Blocked, Status.Open)     => true
      case (Status.Blocked, Status.InProgress) => true
      case _                                 => false // done→任何（终态）、in_progress→open 等

  /** 依赖图环检测（id → blocks 边；含自依赖）。TeamTask #3 DFS 同款。 */
  private[tools] def hasCycle(tasks: List[Entry]): Boolean =
    val adj = tasks.map(t => t.id -> t.blocks.filter(_.nonEmpty)).toMap
    val visited = mutable.Set[String]()
    val recStack = mutable.Set[String]()

    def dfs(id: String): Boolean =
      visited += id
      recStack += id
      val found = adj.getOrElse(id, Nil).exists { n =>
        if !visited.contains(n) then dfs(n)
        else if recStack.contains(n) then true
        else false
      }
      recStack -= id
      found

    adj.keys.exists(id => !visited.contains(id) && dfs(id))
  end hasCycle

  /** blocks 列表规整：去空白、去重。 */
  private def normalizeBlocks(raw: List[String]): List[String] =
    raw.map(_.trim).filter(_.nonEmpty).distinct

  /** 依赖 id 存在性校验：未知 id → Left(错误)。 */
  private def checkBlockIds(store: Store, blocks: List[String]): Option[ToolError] =
    val known = store.tasks.map(_.id).toSet
    blocks.find(!known.contains(_)).map { unknown =>
      val knownList = if known.isEmpty then "(none — store is empty)" else known.map("#" + _).mkString(", ")
      ToolError(
        s"TaskList: dependency id '#$unknown' does not exist. Known ids: $knownList. " +
          s"Fix the id or drop it. (TASKLIST_BLOCK_UNKNOWN)")
    }

  /** 依赖闸（TeamTask 精神落地）：blocks 内全部条目须 done 才可 in_progress/done。 */
  private def checkDepsClosed(store: Store, blocks: List[String], action: String, id: String): Option[ToolError] =
    val open = blocks.flatMap(dep => store.tasks.find(_.id == dep)).filterNot(_.status == Status.Done)
    if open.isEmpty then None
    else
      val listing = open.map(e => s"#${e.id}[${e.status}] ${truncate(e.title, 40)}").mkString(", ")
      Some(ToolError(
        s"TaskList: cannot $action #$id — dependency(ies) not closed yet: $listing. " +
          s"Close them first, or set status=\"blocked\" on #$id to record the wait. (TASKLIST_BLOCKED)"))

  private def truncate(s: String, max: Int): String =
    if s.length <= max then s else s.take(max) + "…"

  private def nowStr: String = Instant.now().toString

  /** done 条目惰性清理（closedAt 超 30d；解析失败保守保留）。返回清理数。 */
  private def pruneDone(store: Store): (Store, Int) =
    val cutoff = Instant.now().minusSeconds(DoneTtlDays * 24 * 3600)
    var pruned = 0
    val kept = store.tasks.filter { t =>
      val expired = t.status == Status.Done &&
        t.closedAt.flatMap(s => scala.util.Try(Instant.parse(s)).toOption).exists(_.isBefore(cutoff))
      if expired then pruned += 1
      !expired
    }
    (store.copy(tasks = kept), pruned)

  // ------------------------------------------------------------------
  // 四 action 同步实现（工具在 IO.blocking 内调用）
  // ------------------------------------------------------------------

  def createSync(
    title: String,
    project: Option[String],
    note: Option[String],
    blocksRaw: Option[List[String]]
  ): Either[ToolError, String] = fileLock.synchronized {
    if title.trim.isEmpty then
      Left(ToolError("TaskList: create requires a non-empty `title`. (TASKLIST_PARAM)"))
    else
      val (base, quarantined) = readForWriteSync()
      val (store, pruned) = pruneDone(base)
      val blocks = blocksRaw.map(normalizeBlocks).getOrElse(Nil)
      for
        _ <- checkBlockIds(store, blocks).toLeft(())
        nextId = store.tasks.flatMap(_.id.toIntOption).maxOption.getOrElse(0) + 1
        now = nowStr
        entry = Entry(
          id = nextId.toString,
          title = title.trim,
          status = Status.Open,
          project = project.map(_.trim).filter(_.nonEmpty),
          note = note.map(_.trim).filter(_.nonEmpty),
          blocks = blocks,
          createdAt = Some(now),
          updatedAt = Some(now)
        )
        _ <- {
          writeSync(store.copy(tasks = store.tasks :+ entry))
          Right(())
        }
      yield
        val depsNote = if blocks.nonEmpty then s" (deps: ${blocks.map("#" + _).mkString(", ")})" else ""
        val pruneNote = (pruned, quarantined) match
          case (0, None)       => ""
          case (n, None)       => s" Pruned $n done entr${if n == 1 then "y" else "ies"} older than ${DoneTtlDays}d."
          case (0, Some(q))    => s" NOTE: previous tasks.json was corrupted — quarantined as $q; fresh store started."
          case (n, Some(q))    => s" NOTE: previous tasks.json was corrupted — quarantined as $q. Pruned $n stale done entries."
        s"""[OK] TaskList created #$nextId [open] ${truncate(entry.title, 60)}$depsNote$pruneNote
           |Start it with action=update status=in_progress (blocked until all deps are done); close it with action=close.""".stripMargin
  }

  def updateSync(
    id: String,
    title: Option[String],
    note: Option[String],
    project: Option[String],
    blocksRaw: Option[List[String]],
    status: Option[String]
  ): Either[ToolError, String] = fileLock.synchronized {
    val (store, quarantined) = readForWriteSync()
    store.tasks.find(_.id == id) match
      case None =>
        val open = store.tasks.filterNot(_.status == Status.Done)
        val hint = if open.isEmpty then "(no open entries)" else open.map(e => s"#${e.id}[${e.status}] ${truncate(e.title, 40)}").mkString(", ")
        Left(ToolError(
          s"""TaskList: no entry #$id. Open entries: $hint — pick an id from action=list. (TASKLIST_NO_ID)"""))
      case Some(existing) =>
        // 终态单通道：update 不得进 done（close 专属），错误给可行动出路
        if status.contains(Status.Done) && existing.status != Status.Done then
          Left(ToolError(
            s"TaskList: status=\"done\" is not settable via update — use action=close (the single path into the terminal state). (TASKLIST_DONE_VIA_CLOSE)"))
        else
          val target = status.getOrElse(existing.status)
          if !Status.all.contains(target) then
            Left(ToolError(
              s"TaskList: unknown status '$target' — one of open | in_progress | blocked (done is reached via action=close). (TASKLIST_STATUS)"))
          else if !isValidTransition(existing.status, target) then
            Left(ToolError(
              s"TaskList: illegal transition ${existing.status} → $target for #$id. " +
                "Legal: open→in_progress, open→blocked, in_progress→blocked, blocked→open, blocked→in_progress; done is terminal (no transitions out). (TASKLIST_STATUS)"))
          else
            val newBlocks = blocksRaw.map(normalizeBlocks).getOrElse(existing.blocks)
            for
              _ <- checkBlockIds(store, newBlocks).toLeft(())
              // 环检测：以「改动后」的边集跑 DFS（含自依赖）
              _ <-
                val candidate = Entry(id = existing.id, title = existing.title, status = target,
                  blocks = newBlocks) // 只取边集，其余字段无关环检测
                val edgeSet = store.tasks.map(t => if t.id == existing.id then candidate else t)
                if hasCycle(edgeSet) then
                  Left(ToolError(
                    s"TaskList: blocks update for #$id would create a dependency cycle (self-reference or loop). (TASKLIST_CYCLE)"))
                else Right(())
              // 依赖闸：目标 in_progress 时 blocks 全部须闭环
              _ <-
                if target == Status.InProgress then
                  checkDepsClosed(store, newBlocks, "start", id).toLeft(())
                else Right(())
              now = nowStr
              updated = existing.copy(
                title = title.map(_.trim).filter(_.nonEmpty).getOrElse(existing.title),
                note = note.map(_.trim).filter(_.nonEmpty).orElse(existing.note),
                project = project match
                  case Some(p) => Some(p.trim).filter(_.nonEmpty) // 空串 = 清除
                  case None    => existing.project,
                blocks = newBlocks,
                status = target,
                updatedAt = Some(now)
              )
              _ <- {
                writeSync(store.copy(tasks = store.tasks.map(t => if t.id == id then updated else t)))
                Right(())
              }
            yield
              val statusNote = if target != existing.status then s" ${existing.status}→$target" else " (no status change)"
              val qNote = quarantined.map(q => s" NOTE: previous tasks.json was corrupted — quarantined as $q.").getOrElse("")
              s"[OK] TaskList updated #$id$statusNote$qNote"
  }

  def closeSync(id: String, note: Option[String]): Either[ToolError, String] = fileLock.synchronized {
    val (store, quarantined) = readForWriteSync()
    store.tasks.find(_.id == id) match
      case None =>
        val open = store.tasks.filterNot(_.status == Status.Done)
        val hint = if open.isEmpty then "(no open entries)" else open.map(e => s"#${e.id}[${e.status}] ${truncate(e.title, 40)}").mkString(", ")
        Left(ToolError(
          s"""TaskList: no entry #$id. Open entries: $hint — pick an id from action=list. (TASKLIST_NO_ID)"""))
      case Some(existing) if existing.status == Status.Done =>
        // 幂等：重复 close = no-op 成功（TeamTask complete 双发竞态同款）
        Right(s"[OK] TaskList #$id already closed at ${existing.closedAt.getOrElse("(unknown time)")} — no-op.")
      case Some(existing) =>
        for
          _ <- checkDepsClosed(store, existing.blocks, "close", id).toLeft(())
          now = nowStr
          // close 的 note = 结果备注，追加保留工作 note（update 语义是替换，close 语义是追加）
          newNote = note.map(_.trim).filter(_.nonEmpty) match
            case Some(outcome) =>
              Some(existing.note.filter(_.nonEmpty).map(n => s"$n\n[done] $outcome").getOrElse(s"[done] $outcome"))
            case None => existing.note
          updated = existing.copy(
            status = Status.Done,
            note = newNote,
            closedAt = Some(now),
            updatedAt = Some(now)
          )
          _ <- {
            writeSync(store.copy(tasks = store.tasks.map(t => if t.id == id then updated else t)))
            Right(())
          }
        yield
          val qNote = quarantined.map(q => s" NOTE: previous tasks.json was corrupted — quarantined as $q.").getOrElse("")
          s"[OK] TaskList closed #$id ${existing.status}→done$qNote"
  }

  /** list 渲染（可选 project 过滤，精确匹配）。 */
  def listSync(project: Option[String]): Either[ToolError, String] =
    val store = readViewSync()
    val filtered = project.map(_.trim).filter(_.nonEmpty) match
      case Some(p) => store.tasks.filter(_.project.contains(p))
      case None    => store.tasks
    val open = filtered.filterNot(_.status == Status.Done)
    if filtered.isEmpty then
      val scopeNote = project.map(p => s" for project '$p'").getOrElse("")
      Right(s"TaskList — empty$scopeNote (no entries). Create one with action=create.")
    else
      val lines = filtered.map { t =>
        val proj = t.project.map(p => s" ($p)").getOrElse("")
        val deps =
          if t.blocks.isEmpty then ""
          else
            val parts = t.blocks.map { depId =>
              store.tasks.find(_.id == depId) match
                case Some(d) => s"#$depId[${d.status}]"
                case None    => s"#$depId[?]"
            }
            val openDeps = t.blocks.flatMap(dep => store.tasks.find(_.id == dep)).exists(_.status != Status.Done)
            val warn = if openDeps && t.status != Status.Done then " ⚠deps-open" else ""
            s" deps: ${parts.mkString(", ")}$warn"
        val note = t.note.filter(_.nonEmpty).map(n => s" | note: ${truncate(n.replace("\n", " ⏎ "), 60)}").getOrElse("")
        s"#${t.id} [${t.status}] ${truncate(t.title, 60)}$proj$deps$note"
      }
      Right(
        s"""TaskList — ${filtered.size} entr${if filtered.size == 1 then "y" else "ies"} (${open.size} open)${project.map(p => s" for project '$p'").getOrElse("")}
           |${lines.mkString("\n")}""".stripMargin)

  /**
   * 生命周期注入摘要行（ContextRefresher.buildMemoryBlock 消费）：open 条目存在
   * 时返回一行摘要；全 done / 空 / 文件损坏 → 空串（提醒消失）。
   * 摘要行 = 一行、首 5 条、标题截 40、总长封顶 600。
   */
  def openSummaryLine(): String =
    val store = readViewSync()
    val open = store.tasks.filterNot(_.status == Status.Done)
    if open.isEmpty then ""
    else
      val shown = open.take(5).map { t =>
        val proj = t.project.map(p => s"@$p").getOrElse("")
        s"#${t.id}[${t.status}] ${truncate(t.title, 40)}$proj"
      }
      val more = if open.size > 5 then s" (+${open.size - 5} more)" else ""
      val line = s"[TaskList] ${open.size} open task(s): ${shown.mkString(" | ")}$more — details: TaskList(action=list)"
      truncate(line, 600)
  end openSummaryLine
end TaskListStore

/**
 * TaskList 工具（Nebula 专属编排件，2026-09-06 作者提议 + 首期无前端拍板）。
 *
 * 工具面隔离（硬约束）：仅 AgentCore.NebulaOrchestrationTools 携带（14 件之一，
 * TaskList 批 +1）；NebulaExclusiveTools 防声明逃逸——dispatcher（DispatcherFixedTools）
 * / general（BaseTools+Pop）与任何非 Nebula 身份声明（含 "*"）均剥离。
 */
object TaskListTool extends Tool:

  val name = "TaskList"

  val description =
    """Persistent task tracker — your personal orchestration backlog (Nebula-exclusive). Fast-changing work state lives here, NOT in memory (memory = slow-changing facts; closed tasks retire). Storage: ~/.nebflow/tasks.json (runtime data — never in git, never injected wholesale into prompts; a ONE-LINE open summary rides your memory block at lifecycle events (restart/compaction) only).
## Actions
- create: new entry (status=open). Required: `title`. Optional: `project`, `note`, `blocks` (ids of entries THIS one depends on).
- update: edit entry fields. Required: `id`. Optional: `status` ("open" | "in_progress" | "blocked" — NEVER "done", use close), `title`, `note` (replaces), `project` (empty string clears), `blocks` (FULL list replacement; [] clears).
- list: render all entries with status + dependencies. Optional: `project` (exact filter). On-demand detail queries — do not wait for the lifecycle reminder.
- close: finish an entry (status→done, terminal). Required: `id`. Optional: `note` appended as a "[done] outcome" line (working note is preserved).
## Status machine (TeamTask four-state heritage)
open → in_progress → done (via close only); open/in_progress → blocked (record a wait); blocked → open (back to backlog) or blocked → in_progress (resume). Same-state update = no-op. done is TERMINAL: no transitions out (done→open is rejected). in_progress→open is rejected (reopen via blocked→open if truly needed — or create anew).
## Dependency guard (blocks semantics)
`blocks` = ids this entry DEPENDS ON. Starting (→in_progress) or closing (→done) is REJECTED while any dependency is not done (TASKLIST_BLOCKED lists the open deps): close them first, or set this entry to blocked to record the wait. Dependency cycles (incl. self-reference) are rejected (TASKLIST_CYCLE); unknown dep ids rejected (TASKLIST_BLOCK_UNKNOWN); depending on an already-done entry is legal.
## Notes
- Errors carry codes (TASKLIST_*) — they are actionable, read them.
- close is idempotent (closing a done entry = no-op success).
- Done entries auto-prune after 30 days on the next create (outcome notes are your audit trail until then).
- Concurrency: reads tolerate a corrupted tasks.json (empty view, never crash); the first write after corruption quarantines the file (tasks.json.corrupt-<ts>) and starts fresh — nothing is silently overwritten. Cross-process writers are not locked."""

  val inputSchema: JsonObject = JsonObject(
    "type" -> "object".asJson,
    "properties" -> Json.obj(
      "action" -> Json.obj(
        "type"        -> "string".asJson,
        "enum"        -> Json.arr("create".asJson, "update".asJson, "list".asJson, "close".asJson),
        "description" -> "create / update / list / close (see description).".asJson
      ),
      "title" -> Json.obj(
        "type"        -> "string".asJson,
        "description" -> "Entry title. Required for create; optional replacement for update.".asJson
      ),
      "id" -> Json.obj(
        "type"        -> "string".asJson,
        "description" -> "Entry id (from list). Required for update/close.".asJson
      ),
      "status" -> Json.obj(
        "type"        -> "string".asJson,
        "enum"        -> Json.arr("open".asJson, "in_progress".asJson, "blocked".asJson),
        "description" -> "New status for update. \"done\" is reached via action=close only.".asJson
      ),
      "project" -> Json.obj(
        "type"        -> "string".asJson,
        "description" -> "Project tag. create: optional; update: empty string clears; list: exact-match filter.".asJson
      ),
      "note" -> Json.obj(
        "type"        -> "string".asJson,
        "description" -> "create: initial note; update: REPLACES the note; close: appended as a [done] outcome line.".asJson
      ),
      "blocks" -> Json.obj(
        "type"        -> "array".asJson,
        "items"       -> Json.obj("type" -> "string".asJson),
        "description" -> "Ids of entries this one depends on. create: initial list; update: FULL replacement ([] clears).".asJson
      )
    ),
    "required" -> Json.arr("action".asJson)
  )

  def summarize(input: JsonObject): String =
    val a = input("action").flatMap(_.asString).getOrElse("?")
    val id = input("id").flatMap(_.asString).map(i => s"#$i").getOrElse("")
    s"TaskList($a$id)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 200 then result.take(197) + "..." else result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    IO.blocking {
      val action  = input("action").flatMap(_.asString).getOrElse("")
      val title   = input("title").flatMap(_.asString)
      val id      = input("id").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
      val status  = input("status").flatMap(_.asString)
      val project = input("project").flatMap(_.asString)
      val note    = input("note").flatMap(_.asString)
      val blocks  = input("blocks").flatMap(_.as[List[String]].toOption)

      action match
        case "create" =>
          TaskListStore.createSync(
            title = title.getOrElse(""),
            project = project,
            note = note,
            blocksRaw = blocks)
        case "update" =>
          id match
            case None =>
              Left(ToolError("TaskList: update requires `id` (entry id from action=list). (TASKLIST_PARAM)"))
            case Some(i) =>
              TaskListStore.updateSync(i, title, note, project, blocks, status)
        case "list" =>
          TaskListStore.listSync(project)
        case "close" =>
          id match
            case None =>
              Left(ToolError("TaskList: close requires `id` (entry id from action=list). (TASKLIST_PARAM)"))
            case Some(i) =>
              TaskListStore.closeSync(i, note)
        case other =>
          Left(ToolError(
            s"TaskList: unknown action '$other' — one of create/update/list/close. (TASKLIST_ACTION)"))
    }
end TaskListTool
