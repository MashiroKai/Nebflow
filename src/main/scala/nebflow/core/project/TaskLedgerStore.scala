package nebflow.core.project

import io.circe.Codec
import io.circe.syntax.*
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.parser.decode

import java.time.Instant

import scala.collection.mutable

import nebflow.core.{AtomicJson, NebflowLogger, PathUtil}
import nebflow.core.tools.ToolError

/** 合一账本条目（wire schema，三态）。
  *
  * 🔴 **无 `note` 字段**：note 时间线**不在条目上**——它是引擎侧的独立数据面
  * （[[TaskLedgerHistory]] 的 `note` 类事件），唯一写入路径 = Nebula 每次 Mail 到
  * 该任务分发器时自动 append。`Task` 工具**无** note 参数（结构上写不了）。
  * `withDefaults`：缺字段回默认（零迁移）；未知键容忍。 */
case class TaskEntry(
  id: String,
  title: String,
  status: String = TaskLedgerStore.Status.Open,
  assignee: Option[String] = None, // 节点 id | "dispatcher" | "author"
  nodeId: Option[String] = None,   // → Flow Map 节点可选单向链接
  links: List[String] = Nil,       // 自由锚（文档路径 / commit / 跨账本引用），**不校验可达性**
  blocks: List[String] = Nil,      // 本条目【依赖】的条目 id（依赖闸只读此字段）
  parentId: Option[String] = None, // 包含关系（子任务），与 blocks 正交
  project: Option[String] = None,  // **自由标签**（不是存储位置——单账本全实例共享）
  createdAt: Option[String] = None,
  updatedAt: Option[String] = None,
  closedAt: Option[String] = None,    // 抵 `closed` 的时点
  completedAt: Option[String] = None  // 抵 `completed` 的时点
)
object TaskEntry:
  given Configuration = Configuration.default.withDefaults
  given Codec[TaskEntry] = ConfiguredCodec.derived

/** `tasks-v2.json` 顶层结构：version + tasks + nextId（与旧两本**逐字同构**）。
  *
  * `nextId` = **单调 id 水位**（最后一次发放的 id；0 = 从未发放）。缺键回默认 0
  * （零迁移读入）；**旧两本的水位不继承**（新账本自 0 起 ⇒ 首次 create = 1）。 */
case class TaskLedgerData(version: Int = 1, tasks: List[TaskEntry] = Nil, nextId: Int = 0)
object TaskLedgerData:
  given Configuration = Configuration.default.withDefaults
  given Codec[TaskLedgerData] = ConfiguredCodec.derived

/**
 * TaskLedgerStore —— 合一账本存储（taskunify 实施批，2026-09-24）。
 *
 * 定位：`Task` 工具（Nebula 独占写面）的**唯一状态机与持久层**——取代退役的
 * `TaskListStore`（`~/.nebflow/tasks.json`，编排面）与 `TaskBoardStore`
 * （`<workspace>/.nebflow/task-board.json`，板面）。一件取代两件：**一个账本、
 * 一个编号空间、一个变更史文件**。
 *
 * ── 物理形态（裁定 L）──
 * **单全局文件** `~/.nebflow/tasks-v2.json`（`PathUtil.dataRoot` 派生 ⇒ 隔离实例
 * 同换根、同测试隔离）。顶层 `{version, tasks, nextId}` 与旧两本**逐字同构**
 * （复用现读读写原语与渲染面，改动面最小）；条目带 `project` 字段（自由标签，
 * **不是**存储位置——单账本全实例共享）。归属单条**读时过滤**，**不做物化视图**。
 *
 * 🔴 **旧两本零删除**：`~/.nebflow/tasks.json` 与 `<ws>/.nebflow/task-board.json`
 * （含各自史文件）**原地不动、禁删禁覆写**，作**只读归档**保留可查；本模块
 * **不读写**它们。水位**不继承**（新本自 0 起，首次 create = `max(∅,0)+1 = 1`）。
 *
 * ── 三态状态机（裁定 a①，取代两套四态）──
 * `{open, closed, completed}` 取代 `{open, in_progress, done, blocked}`。
 * - `open` → `completed`（达成）/ `open` → `closed`（作废/撤单）
 * - `closed` → `completed` = **合法**（一条被作废的条目后来发现其实达成了 ⇒ 用
 *   `complete` 提升它，**而不是**建重复条目）
 * - `completed` = **终态，无出边**（任何出边一律拒）
 * - 同态写 = no-op 成功（幂等，含 `completed→completed` / `closed→closed`）
 * - **无 `in_progress`、无 `blocked`**：进展与等待表达在 note 时间线里，不是状态。
 *   ⇒ 状态机**全域**：每条要么仍开着、要么作废、要么达成。
 * - `update` 的 `status` 参数**整体删除**（单通道由「参数不存在」在 schema 层强制）
 *
 * ── 依赖闸（裁定 `dep-escape`）──
 * `blocks` = 本条目**依赖**的条目 id。`complete` 在任一依赖**未抵终态**时被拒
 * （`closed` ∧ `completed` **都算闭环**——只算其一会让另一类终态的任务永远阻塞
 * 下游）；**`close` 永不受闸**（撤单恒可用，这就是依赖卡死时的逃逸口）。
 * 环检测（含自依赖）与未知依赖 id 拒绝。
 *
 * ── 子任务（parentId）与依赖（blocks）──
 * `parentId` = 包含关系（是……的子任务；深度 ≤5、环与未知 id 拒绝）；`blocks` =
 * 排序关系。二者**正交**、可共存。父状态**永不派生**：子任务全终态**不**自动
 * 完成父条目，完成父条目**也不**改写子条目。
 *
 * ── id 与生命周期 ──
 * id **永不复用**：账本持单调水位 `nextId` ⇒ 条目被 prune 后其 id 不交给别的
 * 任务（一个 id 恒映射到恰一个任务的时间线）。终态条目在其**抵达终态**后 30 天、
 * 于**下一次 `create`** 时惰性清理；其变更史在 prune 后仍可读（`show <id>` 走
 * 「归档命中」降级渲染，标 `[gone]`）。
 *
 * ── 损坏容错 ──
 * 读损坏 → 空视图 + WARN（零副作用）；写损坏 → 隔离改名
 * `tasks-v2.json.corrupt-<millis>`（旧字节全保留）+ 空库续写（**不静默覆盖**）。
 *
 * ── 锁口径 ──
 * 实例内单锁：mutation+persist 全程持有（串行化覆盖磁盘写完成）。跨进程不锁
 * （单宿主前提，与旧两本同款取舍）。
 */
class TaskLedgerStore private ():
  import TaskLedgerStore.*

  /** `def` 非 `val`：`PathUtil.dataRoot` 可被测试换根（`setDataRoot`）。 */
  private def file: os.Path = PathUtil.dataRoot / FileName

  /** 变更史（单全局文件；无状态、无锁——append 全在 fileLock 段内）。 */
  private val history = TaskLedgerHistory.open()

  /** 实例内单锁（同旧两本取舍）。读路径不加锁：AtomicJson 原子换入，读者只见
    * 旧或新完整文件。 */
  private val fileLock = new Object

  // ------------------------------------------------------------------
  // 读 / 写原语
  // ------------------------------------------------------------------

  private def readSync(): Either[String, Store] =
    if !os.exists(file) then Right(Store())
    else
      scala.util.Try(decode[Store](os.read(file))) match
        case scala.util.Success(Right(store)) => Right(store)
        case scala.util.Success(Left(err))    => Left(err.getMessage)
        case scala.util.Failure(e)            => Left(e.getMessage)

  private def readViewSync(): Store =
    readSync() match
      case Right(store) => store
      case Left(reason) =>
        logger.warnSync(
          s"[taskledger] tasks-v2.json corrupted, read path degrades to empty view: $reason")
        Store()

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
          s"[taskledger] tasks-v2.json corrupted ($reason) — quarantined to ${renamed.getOrElse("(rename failed, proceeding on empty store)")}, starting fresh store")
        (Store(), renamed)

  private def writeSync(store: Store): Unit =
    AtomicJson.writeSync(file, store.asJson.noSpaces)

  private def notFound(store: Store, id: String): ToolError =
    val open = store.tasks.filter(_.status == Status.Open)
    val hint = if open.isEmpty then "(no open entries)"
      else open.map(e => s"#${e.id}[${e.status}] ${truncate(e.title, 40)}").mkString(", ")
    ToolError(
      s"""Task: no entry #$id. Open entries: $hint — pick an id from action=list. (${Codes.NotFound})""")

  // ------------------------------------------------------------------
  // 变更史写入
  // ------------------------------------------------------------------

  /** 追加一条史事件；成功 = ""，失败 = `" NOTE: history append failed (<reason>)"`。
    * 主操作不因史失败而失败（工具调用面已由 ToolsLogWriter 记录），但**必须在结果行
    * 显式告知**（绝不静默）。 */
  private def appendHistory(ev: TaskLedgerEvent): String =
    history.appendSync(ev).map(r => s" NOTE: history append failed ($r)").getOrElse("")

  private def histNoteFor(id: String): String =
    s" History: ${history.file} (see it with action=show id=$id)."

  /** prune 的既有结果文案。 */
  private def pruneNoteOf(pruned: List[Entry], quarantined: Option[String]): String =
    (pruned.size, quarantined) match
      case (0, None)    => ""
      case (n, None)    => s" Pruned $n terminal entr${if n == 1 then "y" else "ies"} older than ${TerminalTtlDays}d."
      case (0, Some(q)) => s" NOTE: previous tasks-v2.json was corrupted — quarantined as $q; fresh ledger started."
      case (n, Some(q)) => s" NOTE: previous tasks-v2.json was corrupted — quarantined as $q. Pruned $n stale terminal entries."

  private def pruneHistory(pruned: List[Entry]): String =
    pruned
      .map(e =>
        appendHistory(TaskLedgerEvent(
          at = nowStr, kind = TaskLedgerHistory.Kinds.Prune, id = Some(e.id),
          actor = TaskLedgerHistory.Actors.System,
          detail = Some(s"status=${e.status} title=${truncate(e.title, 80)}"))))
      .mkString

  private def quarantineHistory(quarantined: Option[String]): String =
    quarantined
      .map(q =>
        appendHistory(TaskLedgerEvent(
          at = nowStr, kind = TaskLedgerHistory.Kinds.Quarantine,
          actor = TaskLedgerHistory.Actors.System,
          detail = Some(s"tasks-v2.json unreadable — quarantined as $q; fresh ledger started"))))
      .getOrElse("")

  // ------------------------------------------------------------------
  // 六 action
  // ------------------------------------------------------------------

  /** create：新条目（status=`open`）。返回条目标号。 */
  def createSync(
    title: String,
    assignee: Option[String] = None,
    nodeId: Option[String] = None,
    blocksRaw: Option[List[String]] = None,
    linksRaw: Option[List[String]] = None,
    parentId: Option[String] = None,
    project: Option[String] = None,
    actor: String = TaskLedgerHistory.Actors.Nebula
  ): Either[ToolError, String] = fileLock.synchronized {
    val links = linksRaw.map(normalizeLinks).getOrElse(Nil)
    val invalid =
      if title.trim.isEmpty then Some(ToolError(s"Task: create requires a non-empty `title`. (${Codes.Param})"))
      else checkTextLimit("title", Some(title), TitleWriteMaxChars, "Shorten the title (≤300 chars).")
        .orElse(checkLinks(links))
    invalid match
      case Some(err) => Left(err)
      case None =>
        val (base, quarantined) = readForWriteSync()
        val (store, pruned) = pruneTerminal(base)
        val blocks = blocksRaw.map(normalizeBlocks).getOrElse(Nil)
        val parent = parentId.map(_.trim).filter(_.nonEmpty)
        for
          _ <- checkBlockIds(store, blocks).toLeft(())
          _ <- checkParent(store, None, parent).toLeft(())
          nextId = nextNumId(store)
          now = nowStr
          entry = Entry(
            id = nextId.toString,
            title = title.trim,
            status = Status.Open,
            assignee = Some(assignee.map(_.trim).filter(_.nonEmpty).getOrElse(Assignee.Dispatcher)),
            nodeId = nodeId.map(_.trim).filter(_.nonEmpty),
            links = links,
            blocks = blocks,
            parentId = parent,
            project = project.map(_.trim).filter(_.nonEmpty),
            createdAt = Some(now),
            updatedAt = Some(now)
          )
          _ <- {
            writeSync(store.copy(tasks = store.tasks :+ entry, nextId = nextId))
            Right(())
          }
        yield
          val depsNote = if blocks.nonEmpty then s" (deps: ${blocks.map("#" + _).mkString(", ")})" else ""
          val parentNote = parent.map(p => s" (parent: #$p)").getOrElse("")
          val extra = pruneNoteOf(pruned, quarantined) + quarantineHistory(quarantined) + pruneHistory(pruned) +
            appendHistory(TaskLedgerEvent(
              at = now, kind = TaskLedgerHistory.Kinds.Create, id = Some(entry.id), actor = actor,
              links = links, detail = Some(s"title=${truncate(entry.title, 120)}"))) + histNoteFor(entry.id)
          s"""[OK] Task created #$nextId [open @${entry.assignee.getOrElse("?")}] ${truncate(entry.title, 60)}$depsNote$parentNote$extra
             |Reach `completed` with action=complete; `closed` (voided) with action=close.""".stripMargin
  }

  /** update：**只改非状态字段**（状态只能经 `complete`/`close` 到达——`status` 参数
    * 已整体删除）。空串 = 清除（`assignee`/`nodeId`/`parentId`/`project`）；`links`/
    * `blocks` = **全量替换**（`[]` 清空）。 */
  def updateSync(
    id: String,
    title: Option[String] = None,
    assignee: Option[String] = None,
    nodeId: Option[String] = None,
    blocksRaw: Option[List[String]] = None,
    linksRaw: Option[List[String]] = None,
    parentId: Option[String] = None,
    project: Option[String] = None,
    actor: String = TaskLedgerHistory.Actors.Nebula
  ): Either[ToolError, String] = fileLock.synchronized {
    val (store, quarantined) = readForWriteSync()
    store.tasks.find(_.id == id) match
      case None => Left(notFound(store, id))
      case Some(existing) =>
        val newBlocks = blocksRaw.map(normalizeBlocks).getOrElse(existing.blocks)
        val newLinks = linksRaw.map(normalizeLinks).getOrElse(existing.links)
        val invalid =
          checkTextLimit("title", title, TitleWriteMaxChars, "Shorten the title (≤300 chars).")
            .orElse(checkLinks(newLinks))
        invalid match
          case Some(err) => Left(err)
          case None =>
            for
              _ <- checkBlockIds(store, newBlocks).toLeft(())
              _ <-
                // 环检测：以「改动后」的边集跑 DFS（含自依赖）
                val edgeSet = store.tasks.map(t => if t.id == existing.id then t.copy(blocks = newBlocks) else t)
                if hasCycle(edgeSet) then
                  Left(ToolError(
                    s"Task: blocks update for #$id would create a dependency cycle (self-reference or loop). (${Codes.Cycle})"))
                else Right(())
              newParent = parentId.map(_.trim).filter(_.nonEmpty).orElse(existing.parentId)
              _ <- checkParent(store, Some(existing), parentId.map(_.trim).filter(_.nonEmpty)).toLeft(())
              now = nowStr
              updated = existing.copy(
                title = title.map(_.trim).filter(_.nonEmpty).getOrElse(existing.title),
                assignee = assignee match
                  case Some(a) => Some(a.trim).filter(_.nonEmpty)
                  case None    => existing.assignee,
                nodeId = nodeId match
                  case Some(n) => Some(n.trim).filter(_.nonEmpty)
                  case None    => existing.nodeId,
                blocks = newBlocks,
                links = newLinks,
                parentId = parentId match
                  case Some(p) => Some(p.trim).filter(_.nonEmpty)
                  case None    => existing.parentId,
                project = project match
                  case Some(p) => Some(p.trim).filter(_.nonEmpty)
                  case None    => existing.project,
                updatedAt = Some(now)
              )
              _ <- {
                writeSync(store.copy(tasks = store.tasks.map(t => if t.id == id then updated else t)))
                Right(())
              }
            yield
              val qNote = quarantined.map(q => s" NOTE: previous tasks-v2.json was corrupted — quarantined as $q.").getOrElse("")
              val hist = appendHistory(TaskLedgerEvent(
                at = now, kind = TaskLedgerHistory.Kinds.Update, id = Some(id), actor = actor,
                detail = Some(updateDetail(existing, updated))))
              s"[OK] Task updated #$id$qNote$hist${histNoteFor(id)}"
  }

  /** complete：抵 `completed`（达成）。**合法自 `open` 与 `closed`**；已是
    * `completed` = 幂等 no-op 成功。依赖闸：任一依赖未抵终态 ⇒ 拒。 */
  def completeSync(
    id: String,
    actor: String = TaskLedgerHistory.Actors.Nebula
  ): Either[ToolError, String] = fileLock.synchronized {
    val (store, quarantined) = readForWriteSync()
    store.tasks.find(_.id == id) match
      case None => Left(notFound(store, id))
      case Some(existing) if existing.status == Status.Completed =>
        Right(s"[OK] Task #$id already completed at ${existing.completedAt.getOrElse("(unknown time)")} — no-op.")
      case Some(existing) =>
        checkDepsTerminal(store, existing.blocks, "complete", id) match
          case Some(err) => Left(err)
          case None =>
            val now = nowStr
            val updated = existing.copy(status = Status.Completed, completedAt = Some(now), updatedAt = Some(now))
            writeSync(store.copy(tasks = store.tasks.map(t => if t.id == id then updated else t)))
            val qNote = quarantined.map(q => s" NOTE: previous tasks-v2.json was corrupted — quarantined as $q.").getOrElse("")
            val hist = appendHistory(TaskLedgerEvent(
              at = now, kind = TaskLedgerHistory.Kinds.Complete, id = Some(id), actor = actor,
              detail = Some(s"${existing.status}→completed")))
            Right(s"[OK] Task completed #$id ${existing.status}→completed$qNote$hist")
  }

  /** close：抵 `closed`（作废/撤单）。已是 `closed` = 幂等 no-op 成功；
  * **`completed` 时拒**（终态）。🔴 **依赖闸对 close 恒不适用**——撤单恒可用。 */
  def closeSync(
    id: String,
    actor: String = TaskLedgerHistory.Actors.Nebula
  ): Either[ToolError, String] = fileLock.synchronized {
    val (store, quarantined) = readForWriteSync()
    store.tasks.find(_.id == id) match
      case None => Left(notFound(store, id))
      case Some(existing) if existing.status == Status.Completed =>
        Left(ToolError(
          s"Task: #$id is `completed` — the terminal state has no transitions out, so it cannot be closed. " +
            s"Achieved work is not withdrawable; create a new entry if you need one. (${Codes.Status})"))
      case Some(existing) if existing.status == Status.Closed =>
        Right(s"[OK] Task #$id already closed at ${existing.closedAt.getOrElse("(unknown time)")} — no-op.")
      case Some(existing) =>
        val now = nowStr
        val updated = existing.copy(status = Status.Closed, closedAt = Some(now), updatedAt = Some(now))
        writeSync(store.copy(tasks = store.tasks.map(t => if t.id == id then updated else t)))
        val qNote = quarantined.map(q => s" NOTE: previous tasks-v2.json was corrupted — quarantined as $q.").getOrElse("")
        val hist = appendHistory(TaskLedgerEvent(
          at = now, kind = TaskLedgerHistory.Kinds.Close, id = Some(id), actor = actor,
          detail = Some(s"${existing.status}→closed")))
        Right(s"[OK] Task closed #$id ${existing.status}→closed (voided/withdrawn)$qNote$hist")
  }

  /** list：渲染条目（状态 + 依赖）。可选精确过滤（status/assignee/project）。 */
  def listSync(
    status: Option[String] = None,
    assignee: Option[String] = None,
    project: Option[String] = None,
    nodeTerminal: Map[String, String] = Map.empty
  ): Either[ToolError, String] =
    val store = readViewSync()
    val filtered = store.tasks
      .filter(t => status.forall(_ == t.status))
      .filter(t => assignee.forall(t.assignee.contains))
      .filter(t => project.forall(t.project.contains))
    if filtered.isEmpty then
      val scope = List(
        status.map(s => s"status=$s"), assignee.map(a => s"assignee=$a"), project.map(p => s"project=$p")
      ).flatten.mkString(" ")
      Right(s"Task ledger — empty${if scope.isEmpty then "" else s" matching $scope"} (no entries). Create one with action=create.")
    else
      val lines = filtered.map(t => TaskLedgerRenderer.compactLine(t, store.tasks, nodeTerminal))
      val openCount = filtered.count(_.status == Status.Open)
      Right(
        s"""Task ledger — ${filtered.size} entr${if filtered.size == 1 then "y" else "ies"} ($openCount open)
           |${lines.mkString("\n")}""".stripMargin)

  /** show：单条全字段 + links + 依赖当前态 + 依赖反查 + 父链/直接子条目 + note 时间线。
    * 主库已无该 id 但史里仍有 ⇒ 「归档命中」降级渲染（**不报错退出**）。 */
  def showSync(
    id: String,
    nodeTerminal: Map[String, String] = Map.empty
  ): Either[ToolError, String] =
    val store = readViewSync()
    val notes = history.readFor(id, TaskLedgerRenderer.TimelineNoteMaxVersions, TaskLedgerHistory.isNoteEvent)
    val states = history.readFor(id, TaskLedgerRenderer.TimelineStateMaxLines, ev => !TaskLedgerHistory.isNoteEvent(ev))
    store.tasks.find(_.id == id) match
      case Some(entry) =>
        Right(TaskLedgerRenderer.renderShow(entry, store.tasks, nodeTerminal, notes, states))
      case None =>
        if history.countLinesFor(id) > 0 then
          Right(TaskLedgerRenderer.renderArchived(id, store.tasks, notes, states))
        else Left(notFound(store, id))

  // ------------------------------------------------------------------
  // note 时间线（引擎侧唯一写入路径；工具层无 note 参数）
  // ------------------------------------------------------------------

  /** 引擎在 **Nebula 每次 Mail 到该任务分发器**时自动 append 一条 note 时间线记录。
    *
    * 形态（裁定 n ⓒ 结构化）= `from`（来源域）+ 时间戳（`at`）+ 正文摘录。
    * 单条上限 [[NoteCapChars]] = **16,000 字符**（取 `TaskBoardStore.scala:555`，
    * 放弃编排面旧值 2,000 —— 🔴 **行为可见变更**）。**超长分段而非截断**：
    * 正文按段边界切为多段（首段带 `(part k/n)` 标记），**全文仍在史里**——
    * 🔴 禁「截断丢弃」（旧 `TaskListStore` 的 2,000 上限会拒写，本处改为分段收下）。
    *
    * 返回 `Left` 仅当条目不存在或史 append 失败（note 的载荷就是史本身，静默成功
    * 会丢记录）。 */
  def appendNoteSync(
    id: String,
    text: String,
    from: String = TaskLedgerHistory.Origins.Nebula,
    links: List[String] = Nil,
    actor: String = TaskLedgerHistory.Actors.Nebula
  ): Either[ToolError, String] = fileLock.synchronized {
    val body = text.trim
    if body.isEmpty then
      Left(ToolError(s"Task: note append requires a non-empty body (the Mail message). (${Codes.Param})"))
    else
      val store = readViewSync()
      store.tasks.find(_.id == id) match
        case None => Left(notFound(store, id))
        case Some(existing) =>
          val chunks = chunkNote(body, NoteCapChars)
          val now = nowStr
          var firstFailure: Option[String] = None
          chunks.zipWithIndex.foreach { case (chunk, i) =>
            val partMark = if chunks.size == 1 then "" else s" (part ${i + 1}/${chunks.size})"
            val ev = TaskLedgerEvent(
              at = now, kind = TaskLedgerHistory.Kinds.Note, id = Some(id), actor = actor,
              from = Some(from), text = Option(chunk + partMark), links = links,
              detail = Some(s"chars=${chunk.length}${if chunks.size == 1 then "" else s" of ${body.length}"} title=${truncate(existing.title, 60)}"))
            history.appendSync(ev) match
              case Some(reason) if firstFailure.isEmpty => firstFailure = Some(reason)
              case _                                    => ()
          }
          firstFailure match
            case Some(reason) =>
              Left(ToolError(
                s"Task: note for #$id could not be persisted — history append failed ($reason) at ${history.file}. " +
                  s"Nothing was recorded (the ledger is unchanged). Retry, or fix the path/permissions. (${Codes.History})"))
            case None =>
              val segNote = if chunks.size == 1 then "" else s" (${chunks.size} segments)"
              Right(s"[OK] Task #$id note appended from=$from (${body.length} chars$segNote).${histNoteFor(id)}")
  }

  /** 全账本条目快照（读入口；注入渲染与归属判定用）。 */
  def entriesSync(): List[TaskEntry] = readViewSync().tasks

  /** 按 id 取单条（归属解析 / TaskInfo 用）。 */
  def findSync(id: String): Option[TaskEntry] = readViewSync().tasks.find(_.id == id)

  /** 全局注入的一行 open 摘要（taskunify 合一批 2026-09-24，取代
    * `TaskListStore.openSummaryLine` 的数据源）：**Nebula 面保留 open 摘要**（裁定
    * n 的读面之一），改指向合一账本。🔴 旧 `~/.nebflow/tasks.json` 新代码**不再读**。
    * 无 open 条目 ⇒ ""（不留常驻噪声行）。 */
  def openSummaryLine(): String =
    val store = readViewSync()
    val open = store.tasks.filter(_.status == Status.Open)
    if open.isEmpty then ""
    else
      val shown = open.take(5).map { t =>
        val proj = t.project.map(p => s"@$p").getOrElse("")
        s"#${t.id}[${t.status}] ${truncate(t.title, 40)}$proj"
      }
      val more = if open.size > 5 then s" (+${open.size - 5} more)" else ""
      val line = s"[Task] ${open.size} open task(s): ${shown.mkString(" | ")}$more — details: Task(action=list|show)"
      truncate(line, 600)

end TaskLedgerStore

// ---------------------------------------------------------------------------
// companion：常量 / 纯校验器 / 单例
// ---------------------------------------------------------------------------

object TaskLedgerStore:

  private[project] val logger = NebflowLogger.forName("nebflow.taskledger")

  /** 合一本物理路径（裁定 L 定值；🔴 **绝不覆写** `~/.nebflow/tasks.json`）。 */
  val FileName: String = "tasks-v2.json"

  /** 三态常量（wire 格式唯一来源，裁定 a①）。 */
  object Status:
    val Open      = "open"
    val Closed    = "closed"
    val Completed = "completed"
    val all: Set[String] = Set(Open, Closed, Completed)

    /** 终态判定（依赖闸判据单点）：**两个终态都算闭环**——`completed`（达成）与
      * `closed`（作废）。只算其一会让另一类终态的任务永远阻塞下游。 */
    def isTerminal(s: String): Boolean = s != Open
  end Status

  /** assignee 特殊保留值（节点 id 用 `NodeDef.id` 原值）。 */
  object Assignee:
    val Dispatcher = "dispatcher"
    val Author     = "author"

  /** `TASK_*` 错误码族（集中一处；工具层与本文件消息同源引用）。 */
  object Codes:
    val Param           = "TASK_PARAM"
    val Status          = "TASK_STATUS"
    val Blocked         = "TASK_BLOCKED"
    val BlockUnknown    = "TASK_BLOCK_UNKNOWN"
    val Cycle           = "TASK_CYCLE"
    val ParentCycle     = "TASK_PARENT_CYCLE"
    val ParentUnknown   = "TASK_PARENT_UNKNOWN"
    val ParentDepth     = "TASK_PARENT_DEPTH"
    val Forbidden       = "TASK_FORBIDDEN"
    val NotFound        = "TASK_NOT_FOUND"
    val History         = "TASK_HISTORY"
    val NoAttachment    = "TASKINFO_NO_ATTACHMENT"

  /** 终态条目保留期（`create` 时惰性清理）。 */
  val TerminalTtlDays: Long = 30L

  /** note 单条上限 = **16,000 字符**（裁定 n 定值；取板面现读
    * `TaskBoardStore.scala:555`，放弃编排面旧值 2,000）。
    * 🔴 **行为可见变更**：编排面旧上限 2,000 会**拒写**超长 note；本账本改为
    * **分段收下**（全文仍在史里），不再拒写。 */
  val NoteCapChars: Int = 16_000

  /** 单次写入 `title` 上限（与旧两面同值：300）。 */
  val TitleWriteMaxChars: Int = 300

  /** `links` 条数上限（20 条 × 单条 300 字符 = 最坏 6 KB）。 */
  val LinksWriteMax: Int = 20
  val LinkWriteMaxChars: Int = 300

  /** 子任务链最大深度。 */
  val ParentMaxDepth: Int = 5

  type Entry = TaskEntry
  val Entry = TaskEntry

  type Store = TaskLedgerData
  val Store = TaskLedgerData

  /** 单例（单全局文件 ⇒ 非 per-workspace）。 */
  val instance: TaskLedgerStore = new TaskLedgerStore()

  def open(): TaskLedgerStore = instance

  /** 纯函数：note 正文分段（**不截断**——超长切成多段，全文都进史）。切点优先落在
    * 换行/空白处（避免拦腰截断词），找不到就在上限硬切。 */
  private[project] def chunkNote(body: String, cap: Int): List[String] =
    if body.length <= cap then List(body)
    else
      val out = scala.collection.mutable.ListBuffer[String]()
      var rest = body
      while rest.length > cap do
        val window = rest.take(cap)
        val cut =
          val nl = window.lastIndexOf('\n')
          val sp = window.lastIndexOf(' ')
          if nl > cap / 2 then nl + 1 else if sp > cap / 2 then sp + 1 else cap
        out += rest.take(cut)
        rest = rest.drop(cut)
      if rest.nonEmpty then out += rest
      out.toList

  // ------------------------------------------------------------------
  // 纯校验器
  // ------------------------------------------------------------------

  /** 状态迁移矩阵（裁定 a①）。同态 = no-op 放行（幂等）。
    * `completed` 无出边；`closed → completed` **合法**。 */
  private[project] def isValidTransition(from: String, to: String): Boolean =
    (from, to) match
      case (f, t) if f == t                => true // no-op（含 completed→completed 幂等）
      case (Status.Open, Status.Completed) => true
      case (Status.Open, Status.Closed)    => true
      case (Status.Closed, Status.Completed) => true // 裁定 a①：作废条目后来发现达成了 ⇒ 提升
      case _                               => false // completed→任何（终态无出边）等

  /** 依赖图环检测（id → blocks 边；含自依赖）。DFS+递归栈。 */
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

  /** parentId 链环检测（含自依赖）：沿 parent 边上溯，撞回自身即环。 */
  private[project] def hasParentCycle(tasks: List[Entry]): Boolean =
    val parentOf = tasks.map(t => t.id -> t.parentId.filter(_.nonEmpty)).toMap
    parentOf.keys.exists { start =>
      var cur = parentOf.getOrElse(start, None)
      var steps = 0
      var cyc = false
      val seen = mutable.Set[String](start)
      while cur.isDefined && !cyc && steps <= tasks.size + 1 do
        val c = cur.get
        if seen.contains(c) then cyc = true
        else
          seen += c
          cur = parentOf.getOrElse(c, None)
        steps += 1
      cyc
    }

  /** parent 链深度（沿 parent 边上溯计数；防环由 [[hasParentCycle]] 单独把关）。 */
  private[project] def parentDepth(tasks: List[Entry], id: String): Int =
    val parentOf = tasks.map(t => t.id -> t.parentId.filter(_.nonEmpty)).toMap
    var cur = parentOf.getOrElse(id, None)
    var d = 0
    val seen = mutable.Set[String](id)
    while cur.isDefined && d <= tasks.size + 1 do
      val c = cur.get
      if seen.contains(c) then cur = None
      else
        seen += c
        d += 1
        cur = parentOf.getOrElse(c, None)
    d

  /** blocks 列表规整：去空白、去重。 */
  private[project] def normalizeBlocks(raw: List[String]): List[String] =
    raw.map(_.trim).filter(_.nonEmpty).distinct

  /** links 列表规整：去空白、去重（**不做可达性校验**）。 */
  private[project] def normalizeLinks(raw: List[String]): List[String] =
    raw.map(_.trim).filter(_.nonEmpty).distinct

  /** 写入侧文本长度校验。 */
  private[project] def checkTextLimit(field: String, value: Option[String], max: Int, fix: String): Option[ToolError] =
    value.map(_.trim).filter(_.nonEmpty).filter(_.length > max).map { v =>
      ToolError(s"Task: `$field` is ${v.length} chars — the write-side limit is $max chars. $fix (${Codes.Param})")
    }

  /** links 容量校验（条数 + 单条长度）。存在性不校验。 */
  private[project] def checkLinks(links: List[String]): Option[ToolError] =
    if links.size > LinksWriteMax then
      Some(ToolError(
        s"Task: too many `links` (${links.size}) — max $LinksWriteMax per entry. " +
          s"Keep the most relevant anchors (paths/commits/ids) and drop the rest. (${Codes.Param})"))
    else
      links.find(_.length > LinkWriteMaxChars).map { l =>
        ToolError(
          s"Task: link '${truncate(l, 60)}' is ${l.length} chars — max $LinkWriteMaxChars per link. " +
            s"Use a path / commit hash / id, not free-form prose. (${Codes.Param})")
      }

  /** 依赖 id 存在性校验。 */
  private[project] def checkBlockIds(store: Store, blocks: List[String]): Option[ToolError] =
    val known = store.tasks.map(_.id).toSet
    blocks.find(!known.contains(_)).map { unknown =>
      val knownList = if known.isEmpty then "(none — ledger is empty)" else known.map("#" + _).mkString(", ")
      ToolError(
        s"Task: dependency id '#$unknown' does not exist. Known ids: $knownList. " +
          s"Fix the id or drop it. (${Codes.BlockUnknown})")
    }

  /** 依赖闸（裁定 `dep-escape`）：`complete` 时 blocks 内全部须**抵任一终态**
    * （`closed` ∧ `completed` 都算闭环）。**`close` 不走本闸**（恒可用）。 */
  private[project] def checkDepsTerminal(store: Store, blocks: List[String], action: String, id: String): Option[ToolError] =
    val open = blocks.flatMap(dep => store.tasks.find(_.id == dep)).filterNot(e => Status.isTerminal(e.status))
    if open.isEmpty then None
    else
      val listing = open.map(e => s"#${e.id}[${e.status}] ${truncate(e.title, 40)}").mkString(", ")
      Some(ToolError(
        s"Task: cannot $action #$id — dependency(ies) not terminal yet: $listing. " +
          s"Complete or close them first. (Withdrawing is the escape hatch: `close` is NEVER gated.) (${Codes.Blocked})"))

  /** parentId 校验（创建/改动时）：存在性 + 环 + 深度 ≤ `ParentMaxDepth`。
    * `self` = 改动场景的自身条目（防把 parent 设成自己/后代形成环）；`create`
    * 场景传 `None`（候选条目尚未入册，用哨兵 id 参与深度计算）。 */
  private[project] def checkParent(store: Store, self: Option[Entry], parent: Option[String]): Option[ToolError] =
    parent match
      case None => None
      case Some(p) =>
        if !store.tasks.exists(_.id == p) then
          Some(ToolError(s"Task: parent id '#$p' does not exist. (${Codes.ParentUnknown})"))
        else if self.exists(_.id == p) then
          Some(ToolError(s"Task: parent cannot be the entry itself (#$p). (${Codes.ParentCycle})"))
        else if self.exists(e => isDescendant(store, p, e.id)) then
          // parent 是自身的后代 ⇒ 环（self → … → p → self）
          Some(ToolError(
            s"Task: setting parent=#$p on #${self.get.id} would create a parent cycle (it is a descendant of this entry). (${Codes.ParentCycle})"))
        else
          val candidate = self match
            case Some(e) => store.tasks.map(t => if t.id == e.id then t.copy(parentId = Some(p)) else t)
            case None    => store.tasks :+ Entry(id = NewEntrySentinel, title = "(new)", parentId = Some(p))
          val focus = self.map(_.id).getOrElse(NewEntrySentinel)
          if hasParentCycle(candidate) then
            Some(ToolError(s"Task: setting parent=#$p would create a parent cycle. (${Codes.ParentCycle})"))
          else
            val d = parentDepth(candidate, focus)
            if d > ParentMaxDepth then
              Some(ToolError(s"Task: parent chain depth $d exceeds the max of $ParentMaxDepth. (${Codes.ParentDepth})"))
            else None

  /** 新建条目的哨兵 id（仅参与 parent 深度计算的候选集；永不落盘）。 */
  private[project] val NewEntrySentinel: String = "(new)"

  /** `candidate` 是否为 `ancestor` 的后代（沿 parent 边上溯判据）。 */
  private[project] def isDescendant(store: Store, candidate: String, ancestor: String): Boolean =
    val parentOf = store.tasks.map(t => t.id -> t.parentId.filter(_.nonEmpty)).toMap
    var cur = parentOf.getOrElse(candidate, None)
    var found = false
    var steps = 0
    while cur.isDefined && !found && steps <= store.tasks.size + 1 do
      if cur.get == ancestor then found = true
      else cur = parentOf.getOrElse(cur.get, None)
      steps += 1
    found

  private[project] def truncate(s: String, max: Int): String =
    if s.length <= max then s else s.take(max) + "…"

  private[project] def nowStr: String = Instant.now().toString

  /** update 的变更摘要（非状态字段；状态只能经 complete/close ⇒ 本处不记状态）。 */
  private[project] def updateDetail(before: Entry, after: Entry): String =
    val fields = scala.collection.mutable.ListBuffer[String]()
    if after.title != before.title then fields += "title"
    if after.assignee != before.assignee then fields += "assignee"
    if after.nodeId != before.nodeId then fields += "nodeId"
    if after.blocks != before.blocks then fields += "blocks"
    if after.links != before.links then fields += "links"
    if after.parentId != before.parentId then fields += "parentId"
    if after.project != before.project then fields += "project"
    if fields.isEmpty then "no-op (nothing changed)" else s"changed: ${fields.mkString(", ")}"

  /** 下一个 id（**单调水位**，id 永不复用）：`max(存量数字 id 的 max, 持久化水位
    * nextId) + 1`。旧本水位**不继承**（本账本自 0 起 ⇒ 首次 create = 1）。 */
  private[project] def nextNumId(store: Store): Int =
    math.max(store.tasks.flatMap(_.id.toIntOption).maxOption.getOrElse(0), store.nextId) + 1

  /** 终态条目惰性清理（抵达终态的时点超 30 天；解析失败保守保留）。返回
    * (清理后, 被清条目)——「条目消亡」本身留痕，且史文件零接触。 */
  private[project] def pruneTerminal(store: Store): (Store, List[Entry]) =
    val cutoff = Instant.now().minusSeconds(TerminalTtlDays * 24 * 3600)
    def terminalAt(t: Entry): Option[Instant] =
      val raw = if t.status == Status.Completed then t.completedAt.orElse(t.closedAt) else t.closedAt.orElse(t.completedAt)
      raw.flatMap(s => scala.util.Try(Instant.parse(s)).toOption)
    val (expired, kept) = store.tasks.partition { t =>
      Status.isTerminal(t.status) && terminalAt(t).exists(_.isBefore(cutoff))
    }
    (store.copy(tasks = kept), expired)
end TaskLedgerStore
