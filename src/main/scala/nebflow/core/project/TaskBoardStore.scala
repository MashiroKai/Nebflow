package nebflow.core.project

import io.circe.Codec
import io.circe.syntax.*
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.parser.decode

import java.time.Instant

import scala.collection.mutable

import nebflow.core.{AtomicJson, NebflowLogger, PathUtil}
import nebflow.core.tools.ToolError

/**
 * TaskBoardStore —— 项目任务板存储（TaskBoard 设计规格 §1f/§1g/§2，2026-09-08，
 * 规格稿 .nebflow/Spec/20260908_project-task-board.md）。批 1 纯引擎层：store +
 * renderer + 单测，零接线（工具面/注入/权限判定全归批 2）。
 *
 * 定位：项目内共享工作项看板的持久层——Flow Map 管节点（执行单元），TaskBoard 管
 * 任务（工作项），语义与全局 TaskList 同族（四态 + blocks 依赖闸 + close 幂等 +
 * 30 天 prune，TaskListTool.scala 同构）。
 *
 * 写入安全（§1f）：每项目一实例（[[TaskBoardStore.open]]），整读整写无长驻内存态；
 * mutation+persist 全程持实例内单锁——比 FlowMapStore 的 Ref CAS 更严：串行化覆盖
 * 到磁盘写完成，封死 stale-write 窗口。跨进程不锁（并发写者=同宿主进程内，单宿主
 * 前提；工具 description 批 2 明示）。落盘 `<workspace>/.nebflow/task-board.json`
 * （AtomicJson tmp+ATOMIC_MOVE crash-safe；刻意不叫 tasks.json，防与 FlowMapStore
 * 的 tasks/ 目录语义撞车）。
 *
 * 损坏容错（TaskListStore 同构）：读损坏→空视图+WARN 零副作用；写损坏→隔离改名
 * task-board.json.corrupt-<millis>（旧字节全保留）+ 空库续写，不静默覆盖。
 *
 * 状态机（§2a，TaskList 逐条同构）：open/in_progress/done/blocked；done 终态；
 * 同态 no-op 幂等；update 携带 done → TBOARD_DONE_VIA_CLOSE 结构化拒绝（终态单
 * 通道）。blocks 依赖闸（§2b）：闸 in_progress 迁移与 close（→blocked 不设闸，
 * 记录等待是逃生通道）；环检测 DFS 含自依赖（以改动后边集跑）；依赖不存在 id
 * 拒绝；依赖已 done 合法。
 *
 * 归属（§2c）：assignee 三值域 = 节点 id（NodeDef.id 原值）/ "dispatcher" /
 * "author"；create 缺省 "dispatcher"。权限矩阵（§1d）本批不实现——store 对身份
 * 无感知，TBOARD_FORBIDDEN 仅定义码与语义，判定逻辑归批 2 工具层。
 *
 * close 幂等 + 30 天惰性 prune（§2e）：重复 close=no-op 成功回显 closedAt；
 * create 顺带清扫 closedAt 超期 done 条目（解析失败保守保留，无定时器）。
 */

/** TaskBoard 条目（§1g wire schema；TaskListEntry 同款顶层 case class + companion
  * givens——嵌套 object 内 ConfiguredCodec.derived 在 Scala 3.5 下触发镜像前向引用
  * 错误。withDefaults：缺字段回默认（零迁移）；未知键容忍）。 */
case class TaskBoardEntry(
  id: String,
  title: String,
  status: String = TaskBoardStore.Status.Open,
  assignee: Option[String] = None, // 节点 id | "dispatcher" | "author"（§2c 三值域）
  nodeId: Option[String] = None,   // → Flow Map 节点可选单向链接（§4b）
  note: Option[String] = None,
  blocks: List[String] = Nil,      // 本条目【依赖】的条目 id（TaskList blocks 同向）
  createdAt: Option[String] = None,
  updatedAt: Option[String] = None,
  closedAt: Option[String] = None
)
object TaskBoardEntry:
  given Configuration = Configuration.default.withDefaults
  given Codec[TaskBoardEntry] = ConfiguredCodec.derived

/** task-board.json 顶层结构：version + tasks（§1g envelope）。 */
case class TaskBoardData(version: Int = 1, tasks: List[TaskBoardEntry] = Nil)
object TaskBoardData:
  given Configuration = Configuration.default.withDefaults
  given Codec[TaskBoardData] = ConfiguredCodec.derived

class TaskBoardStore private (val project: String, workspace: String):
  import TaskBoardStore.*

  /** `def` 非 `val`：路径由 open 时传入的 workspace 派生（同族惯例）。 */
  private def file: os.Path =
    os.Path(workspace, PathUtil.dataRoot) / ".nebflow" / "task-board.json"

  /** 实例内单锁：mutation+persist 全程持有（串行化覆盖磁盘写完成）。跨进程不在
    * 锁面内。读路径（listSync）不加锁——AtomicJson 原子换入，读者只见旧或新完整
    * 文件（TaskListStore 同款取舍）。 */
  private val fileLock = new Object

  // ------------------------------------------------------------------
  // 读 / 写原语（TaskListStore 同构）
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
        TaskBoardStore.logger.warnSync(
          s"[taskboard] task-board.json corrupted, read path degrades to empty view: $reason")
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
        TaskBoardStore.logger.warnSync(
          s"[taskboard] task-board.json corrupted ($reason) — quarantined to ${renamed.getOrElse("(rename failed, proceeding on empty store)")}, starting fresh store")
        (Store(), renamed)

  private def writeSync(store: Store): Unit =
    AtomicJson.writeSync(file, store.asJson.noSpaces)

  private def notFound(store: Store, id: String): ToolError =
    val open = store.tasks.filterNot(_.status == Status.Done)
    val hint = if open.isEmpty then "(no open entries)"
      else open.map(e => s"#${e.id}[${e.status}] ${truncate(e.title, 40)}").mkString(", ")
    ToolError(
      s"""TaskBoard: no entry #$id. Open entries: $hint — pick an id from action=list. (${Codes.NotFound})""")

  // ------------------------------------------------------------------
  // 四 action 同步实现（批 2 工具在 IO.blocking 内调用）
  // ------------------------------------------------------------------

  def createSync(
    title: String,
    assignee: Option[String] = None,
    nodeId: Option[String] = None,
    note: Option[String] = None,
    blocksRaw: Option[List[String]] = None
  ): Either[ToolError, String] = fileLock.synchronized {
    if title.trim.isEmpty then
      Left(ToolError(s"TaskBoard: create requires a non-empty `title`. (${Codes.Param})"))
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
          assignee = Some(assignee.map(_.trim).filter(_.nonEmpty).getOrElse(Assignee.Dispatcher)), // §2c 缺省
          nodeId = nodeId.map(_.trim).filter(_.nonEmpty),
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
          case (0, None)    => ""
          case (n, None)    => s" Pruned $n done entr${if n == 1 then "y" else "ies"} older than ${DoneTtlDays}d."
          case (0, Some(q)) => s" NOTE: previous task-board.json was corrupted — quarantined as $q; fresh store started."
          case (n, Some(q)) => s" NOTE: previous task-board.json was corrupted — quarantined as $q. Pruned $n stale done entries."
        s"""[OK] TaskBoard created #$nextId [open @${entry.assignee.getOrElse("?")}] ${truncate(entry.title, 60)}$depsNote$pruneNote
           |Start it with action=update status=in_progress (blocked until all deps are done); close it with action=close.""".stripMargin
  }

  def updateSync(
    id: String,
    title: Option[String] = None,
    status: Option[String] = None,
    assignee: Option[String] = None,
    nodeId: Option[String] = None,
    note: Option[String] = None,
    blocksRaw: Option[List[String]] = None
  ): Either[ToolError, String] = fileLock.synchronized {
    val (store, quarantined) = readForWriteSync()
    store.tasks.find(_.id == id) match
      case None =>
        Left(notFound(store, id))
      case Some(existing) =>
        // 终态单通道：update 不得进 done（close 专属），错误给可行动出路
        if status.contains(Status.Done) && existing.status != Status.Done then
          Left(ToolError(
            s"TaskBoard: status=\"done\" is not settable via update — use action=close (the single path into the terminal state). (${Codes.DoneViaClose})"))
        else
          val target = status.getOrElse(existing.status)
          if !Status.all.contains(target) then
            Left(ToolError(
              s"TaskBoard: unknown status '$target' — one of open | in_progress | blocked (done is reached via action=close). (${Codes.Status})"))
          else if !isValidTransition(existing.status, target) then
            Left(ToolError(
              s"TaskBoard: illegal transition ${existing.status} → $target for #$id. " +
                "Legal: open→in_progress, open→blocked, in_progress→blocked, blocked→open, blocked→in_progress; done is terminal (no transitions out). " +
                s"(${Codes.Status})"))
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
                    s"TaskBoard: blocks update for #$id would create a dependency cycle (self-reference or loop). (${Codes.Cycle})"))
                else Right(())
              // 依赖闸：目标 in_progress 时 blocks 全部须闭环
              _ <-
                if target == Status.InProgress then
                  checkDepsClosed(store, newBlocks, "start", id).toLeft(())
                else Right(())
              now = nowStr
              updated = existing.copy(
                title = title.map(_.trim).filter(_.nonEmpty).getOrElse(existing.title),
                status = target,
                assignee = assignee match
                  case Some(a) => Some(a.trim).filter(_.nonEmpty) // 空串 = 清除
                  case None    => existing.assignee,
                nodeId = nodeId match
                  case Some(n) => Some(n.trim).filter(_.nonEmpty) // 空串 = 清除
                  case None    => existing.nodeId,
                note = note.map(_.trim).filter(_.nonEmpty).orElse(existing.note),
                blocks = newBlocks,
                updatedAt = Some(now)
              )
              _ <- {
                writeSync(store.copy(tasks = store.tasks.map(t => if t.id == id then updated else t)))
                Right(())
              }
            yield
              val statusNote = if target != existing.status then s" ${existing.status}→$target" else " (no status change)"
              val qNote = quarantined.map(q => s" NOTE: previous task-board.json was corrupted — quarantined as $q.").getOrElse("")
              s"[OK] TaskBoard updated #$id$statusNote$qNote"
  }

  def closeSync(id: String, note: Option[String] = None): Either[ToolError, String] = fileLock.synchronized {
    val (store, quarantined) = readForWriteSync()
    store.tasks.find(_.id == id) match
      case None =>
        Left(notFound(store, id))
      case Some(existing) if existing.status == Status.Done =>
        // 幂等：重复 close = no-op 成功（回显 closedAt；TaskList 同构）
        Right(s"[OK] TaskBoard #$id already closed at ${existing.closedAt.getOrElse("(unknown time)")} — no-op.")
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
          val qNote = quarantined.map(q => s" NOTE: previous task-board.json was corrupted — quarantined as $q.").getOrElse("")
          s"[OK] TaskBoard closed #$id ${existing.status}→done$qNote"
  }

  /** list 渲染（status/assignee 精确过滤；nodeTerminal = nodeId→终态名映射，批 2
    * 由 Flow Map 接线传入 → ⚠node-done 只读漂移标记 §2d，本批默认空映射即无标记）。 */
  def listSync(
    status: Option[String] = None,
    assignee: Option[String] = None,
    nodeTerminal: Map[String, String] = Map.empty
  ): Either[ToolError, String] =
    val store = readViewSync()
    val filtered = store.tasks
      .filter(t => status.forall(_ == t.status))
      .filter(t => assignee.forall(t.assignee.contains))
    if filtered.isEmpty then
      val scope = (status, assignee) match
        case (Some(s), Some(a)) => s" matching status=$s assignee=$a"
        case (Some(s), None)    => s" matching status=$s"
        case (None, Some(a))    => s" matching assignee=$a"
        case (None, None)       => ""
      Right(s"TaskBoard $project — empty$scope (no entries). Create one with action=create.")
    else
      val lines = filtered.map { t =>
        val deps =
          if t.blocks.isEmpty then ""
          else
            val parts = t.blocks.map { dep =>
              store.tasks.find(_.id == dep) match
                case Some(d) => s"#$dep[${d.status}]"
                case None    => s"#$dep[?]"
            }
            s" deps: ${parts.mkString(", ")}"
        val note = t.note.filter(_.nonEmpty).map(n => s" | note: ${truncate(n.replace("\n", " ⏎ "), 60)}").getOrElse("")
        s"${TaskBoardRenderer.compactLine(t, store.tasks, nodeTerminal)}$deps$note"
      }
      val openCount = filtered.count(_.status != Status.Done)
      Right(
        s"""TaskBoard $project — ${filtered.size} entr${if filtered.size == 1 then "y" else "ies"} ($openCount open)
           |${lines.mkString("\n")}""".stripMargin)
end TaskBoardStore

// ---------------------------------------------------------------------------
// companion：常量 / 纯校验器 / open 工厂
// ---------------------------------------------------------------------------

object TaskBoardStore:

  private[project] val logger = NebflowLogger.forName("nebflow.taskboard")

  // ---- 状态常量（wire 格式唯一来源，§2a 四态）----
  object Status:
    val Open       = "open"
    val InProgress = "in_progress"
    val Done       = "done"
    val Blocked    = "blocked"
    val all: Set[String] = Set(Open, InProgress, Done, Blocked)

  /** assignee 特殊保留值（§2c 三值域中非节点的两个；节点 id 用 NodeDef.id 原值）。 */
  object Assignee:
    val Dispatcher = "dispatcher"
    val Author     = "author"

  /** TBOARD_* 错误码族（集中一处；批 2 工具层与本文件消息同源引用）。
    * Forbidden 本批仅定义码与语义（越权：节点动他人任务 / 节点动结构字段），
    * 判定逻辑归批 2 工具层——store 恒不返回 FORBIDDEN。 */
  object Codes:
    val DoneViaClose = "TBOARD_DONE_VIA_CLOSE"
    val Status       = "TBOARD_STATUS"
    val Blocked      = "TBOARD_BLOCKED"
    val BlockUnknown = "TBOARD_BLOCK_UNKNOWN"
    val Cycle        = "TBOARD_CYCLE"
    val Forbidden    = "TBOARD_FORBIDDEN"
    val NotFound     = "TBOARD_NOT_FOUND"
    val Param        = "TBOARD_PARAM"

  /** done 条目保留期（create 时惰性清理，「闭环即删」T2 精神）。§2e */
  val DoneTtlDays: Long = 30L

  type Entry = TaskBoardEntry
  val Entry = TaskBoardEntry

  type Store = TaskBoardData
  val Store = TaskBoardData

  /** 每项目一实例（批 2 挂 ProjectRuntime.board；open 即建，无 IO、无失败路径）。 */
  def open(project: String, workspace: String): TaskBoardStore =
    new TaskBoardStore(project, workspace)

  // ------------------------------------------------------------------
  // 纯校验器（TaskListStore 逐条同构；renderer 与单测复用）
  // ------------------------------------------------------------------

  /** 状态迁移矩阵（§2a）。同态 = no-op 放行。 */
  private[project] def isValidTransition(from: String, to: String): Boolean =
    (from, to) match
      case (f, t) if f == t                    => true // no-op（含 done→done 幂等）
      case (Status.Open, Status.InProgress)    => true
      case (Status.Open, Status.Blocked)       => true
      case (Status.InProgress, Status.Blocked) => true
      case (Status.Blocked, Status.Open)       => true
      case (Status.Blocked, Status.InProgress) => true
      case _                                   => false // done→任何（终态）、in_progress→open 等

  /** 依赖图环检测（id → blocks 边；含自依赖）。DFS+递归栈，TaskList 同款。 */
  private[project] def hasCycle(tasks: List[Entry]): Boolean =
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
  private[project] def normalizeBlocks(raw: List[String]): List[String] =
    raw.map(_.trim).filter(_.nonEmpty).distinct

  /** 依赖 id 存在性校验：未知 id → Left(错误)。 */
  private[project] def checkBlockIds(store: Store, blocks: List[String]): Option[ToolError] =
    val known = store.tasks.map(_.id).toSet
    blocks.find(!known.contains(_)).map { unknown =>
      val knownList = if known.isEmpty then "(none — store is empty)" else known.map("#" + _).mkString(", ")
      ToolError(
        s"TaskBoard: dependency id '#$unknown' does not exist. Known ids: $knownList. " +
          s"Fix the id or drop it. (${Codes.BlockUnknown})")
    }

  /** 依赖闸（§2b）：blocks 内全部条目须 done 才可 in_progress/done（close）。 */
  private[project] def checkDepsClosed(store: Store, blocks: List[String], action: String, id: String): Option[ToolError] =
    val open = blocks.flatMap(dep => store.tasks.find(_.id == dep)).filterNot(_.status == Status.Done)
    if open.isEmpty then None
    else
      val listing = open.map(e => s"#${e.id}[${e.status}] ${truncate(e.title, 40)}").mkString(", ")
      Some(ToolError(
        s"TaskBoard: cannot $action #$id — dependency(ies) not closed yet: $listing. " +
          s"Close them first, or set status=\"blocked\" on #$id to record the wait. (${Codes.Blocked})"))

  private[project] def truncate(s: String, max: Int): String =
    if s.length <= max then s else s.take(max) + "…"

  private[project] def nowStr: String = Instant.now().toString

  /** done 条目惰性清理（closedAt 超 30d；解析失败保守保留）。返回 (清理后, 清理数)。 */
  private[project] def pruneDone(store: Store): (Store, Int) =
    val cutoff = Instant.now().minusSeconds(DoneTtlDays * 24 * 3600)
    var pruned = 0
    val kept = store.tasks.filter { t =>
      val expired = t.status == Status.Done &&
        t.closedAt.flatMap(s => scala.util.Try(Instant.parse(s)).toOption).exists(_.isBefore(cutoff))
      if expired then pruned += 1
      !expired
    }
    (store.copy(tasks = kept), pruned)
end TaskBoardStore
