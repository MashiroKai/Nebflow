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
 *
 * ── 升级批（TaskList 升级批 · TaskBoard 支，2026-09-11）──
 * 与全局 TaskList 同批同构升级，四项落地：
 * ① R1/R7 结构化追加 + 变更史：新增 `logSync`（追加式记录，**板面 note 零改写**）
 *    与 [[TaskBoardHistory]]（per-workspace append-only JSONL，`<workspace>/.nebflow/
 *    task-history.jsonl`）；create/update/close/prune/quarantine/rotate 各写点落史
 *    行（`update` 的 note 覆盖语义 `:235` **逐字保留**——追加能力只经 `log` 与史文件，
 *    不改既有覆盖语义；close 幂等早返分支同样逐字保留，含零史行）。
 * ② R2 关联锚：条目新增 `links`（自由数组、去重、**不校验可达性**——错路径/短 hash
 *    静默存在是 spec 已接受的代价；变更本身在史里留痕）。
 * ③ R3/R8 详情查询：新增 `showSync`（全字段 + note 全文 + links + 依赖当前态 +
 *    **依赖反查**（谁依赖我）+ 时间线；对已 prune id 走「归档命中」降级路径，不报错退出）。
 * ④ R5 写入侧容量上限（**取值本工具独有，不跨工具复用**）：note/title/text/links
 *    单次写入上限（常量见 companion，取值依据见各常量注释）；超限 = `TBOARD_PARAM`
 *    + 修法（长内容走 `action=log`）。读路径零校验（存量数据不受影响）。
 * 零迁移：旧 10 键 schema 原样解码（新字段回默认）；四态状态机、done 单通道、blocks
 * 依赖闸与环检测逐字保留。
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
  links: List[String] = Nil,       // R2 关联锚：自由文本数组（文档路径 / commit / 跨账本引用），**不校验可达性**
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

  /** 变更史（per-workspace 独立文件；无状态、无锁——append 全在 fileLock 段内）。 */
  private val history = TaskBoardHistory.open(workspace)

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
  // 变更史写入（R7）：板面写成功之后调用；返回附加到结果行的 NOTE（失败绝不静默）
  // ------------------------------------------------------------------

  /** 追加一条史事件；成功 = ""，失败 = `" NOTE: history append failed (<reason>)"`。
    * 板面变更类动作的史行是**审计副本**，append 失败不失败主操作（工具调用面已由
    * ToolsLogWriter 记录），但必须在结果行显式告知（绝不静默）。 */
  private def appendHistory(ev: TaskBoardEvent): String =
    history.appendSync(ev).map(r => s" NOTE: history append failed ($r)").getOrElse("")

  /** 批量追加（逐字段史行）；返回按序拼接的失败 NOTE（成功 = 空串）。 */
  private def appendEvents(evs: Iterable[TaskBoardEvent]): String =
    evs.map(appendHistory).mkString

  /** 结果行附加的「史落地」提示：史是独立文件，点明它才能被后续 `show` 找到。 */
  private def histNoteFor(id: String): String =
    s" History: ${history.file} (see it with action=show id=$id)."

  /** prune/quarantine 的既有结果文案（逐字保留原措辞）。 */
  private def pruneNoteOf(pruned: List[Entry], quarantined: Option[String]): String =
    (pruned.size, quarantined) match
      case (0, None)    => ""
      case (n, None)    => s" Pruned $n done entr${if n == 1 then "y" else "ies"} older than ${DoneTtlDays}d."
      case (0, Some(q)) => s" NOTE: previous task-board.json was corrupted — quarantined as $q; fresh store started."
      case (n, Some(q)) => s" NOTE: previous task-board.json was corrupted — quarantined as $q. Pruned $n stale done entries."

  /** 逐条 prune 史行（actor=system）：条目从主库消失这件事本身留痕。 */
  private def pruneHistory(pruned: List[Entry]): String =
    pruned
      .map(e =>
        appendHistory(TaskBoardEvent(
          at = nowStr,
          kind = TaskBoardHistory.Kinds.Prune,
          id = Some(e.id),
          actor = TaskBoardHistory.Actors.System,
          detail = Some(s"closedAt=${e.closedAt.getOrElse("(unknown)")} title=${truncate(e.title, 80)}"))))
      .mkString

  /** 隔离史行（actor=system）：板面文件损坏 → 隔离改名 + 空库续写。 */
  private def quarantineHistory(quarantined: Option[String]): String =
    quarantined
      .map(q =>
        appendHistory(TaskBoardEvent(
          at = nowStr,
          kind = TaskBoardHistory.Kinds.Quarantine,
          actor = TaskBoardHistory.Actors.System,
          detail = Some(s"task-board.json unreadable — quarantined as $q; fresh store started"))))
      .getOrElse("")

  // ------------------------------------------------------------------
  // 四 action 同步实现（批 2 工具在 IO.blocking 内调用）
  // ------------------------------------------------------------------

  def createSync(
    title: String,
    assignee: Option[String] = None,
    nodeId: Option[String] = None,
    note: Option[String] = None,
    blocksRaw: Option[List[String]] = None,
    linksRaw: Option[List[String]] = None,
    actor: String = TaskBoardHistory.Actors.System
  ): Either[ToolError, String] = fileLock.synchronized {
    val links = linksRaw.map(normalizeLinks).getOrElse(Nil)
    val invalid =
      if title.trim.isEmpty then Some(ToolError(s"TaskBoard: create requires a non-empty `title`. (${Codes.Param})"))
      else checkTextLimit("title", Some(title), TitleWriteMaxChars,
              "Shorten the title; put detail in `note` or append it with action=log.")
        .orElse(checkTextLimit("note", note, NoteWriteMaxChars, NoteLimitFix))
        .orElse(checkLinks(links))
    invalid match
      case Some(err) => Left(err)
      case None =>
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
            links = links,
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
          val pruneNote = pruneNoteOf(pruned, quarantined)
          val quarantineHist = quarantineHistory(quarantined)
          val histExtra = pruneHistory(pruned) +
            appendHistory(TaskBoardEvent(
              at = now, kind = TaskBoardHistory.Kinds.Create, id = Some(entry.id), actor = actor,
              links = links, detail = Some(s"title=${truncate(entry.title, 120)}")))
          s"""[OK] TaskBoard created #$nextId [open @${entry.assignee.getOrElse("?")}] ${truncate(entry.title, 60)}$depsNote$pruneNote$quarantineHist$histExtra
             |Start it with action=update status=in_progress (blocked until all deps are done); close it with action=close.""".stripMargin
  }

  def updateSync(
    id: String,
    title: Option[String] = None,
    status: Option[String] = None,
    assignee: Option[String] = None,
    nodeId: Option[String] = None,
    note: Option[String] = None,
    blocksRaw: Option[List[String]] = None,
    linksRaw: Option[List[String]] = None,
    actor: String = TaskBoardHistory.Actors.System
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
            val newLinks = linksRaw.map(normalizeLinks).getOrElse(existing.links)
            val invalid =
              checkTextLimit("title", title, TitleWriteMaxChars,
                  "Shorten the title; put detail in `note` or append it with action=log.")
                .orElse(checkTextLimit("note", note, NoteWriteMaxChars, NoteLimitFix))
                .orElse(checkLinks(newLinks))
            invalid match
              case Some(err) => Left(err)
              case None =>
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
                    links = newLinks,
                    updatedAt = Some(now)
                  )
                  _ <- {
                    writeSync(store.copy(tasks = store.tasks.map(t => if t.id == id then updated else t)))
                    Right(())
                  }
                yield
                  val statusNote = if target != existing.status then s" ${existing.status}→$target" else " (no status change)"
                  val qNote = quarantined.map(q => s" NOTE: previous task-board.json was corrupted — quarantined as $q.").getOrElse("")
                  val quarantineHist = quarantineHistory(quarantined)
                  // 逐字段史行（note 覆盖记 old/new 两侧全文；纯 no-op update 零史行）
                  val hist = appendEvents(updateEvents(id, now, actor, existing, updated))
                  s"[OK] TaskBoard updated #$id$statusNote$qNote$quarantineHist$hist"
  }

  def closeSync(
    id: String,
    note: Option[String] = None,
    actor: String = TaskBoardHistory.Actors.System
  ): Either[ToolError, String] = fileLock.synchronized {
    val (store, quarantined) = readForWriteSync()
    store.tasks.find(_.id == id) match
      case None =>
        Left(notFound(store, id))
      case Some(existing) if existing.status == Status.Done =>
        // 幂等：重复 close = no-op 成功（回显 closedAt；TaskList 同构）
        // ⚠ 本分支逐字保留（升级批 RK-5）：对已 done 条目静默丢弃 note 是既有契约，
        //   含零史行——历史追加只在下面「真实关单」分支发生。
        Right(s"[OK] TaskBoard #$id already closed at ${existing.closedAt.getOrElse("(unknown time)")} — no-op.")
      case Some(existing) =>
        checkTextLimit("note", note, NoteWriteMaxChars, NoteLimitFix) match
          case Some(err) => Left(err)
          case None =>
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
              val quarantineHist = quarantineHistory(quarantined)
              val hist = appendHistory(closeEvent(id, now, actor, existing, updated,
                note.map(_.trim).filter(_.nonEmpty)))
              s"[OK] TaskBoard closed #$id ${existing.status}→done$qNote$quarantineHist$hist"
  }

  /** R1 结构化追加：把一段记录**追加**到该条目的变更史（板面 `note` **零改写**——
    * 这正是「安全追加」的核心：不再需要把整条 note 覆盖重写成「旧文 + 新段」）。
    * 条目必须存在（存在性校验）；史 append 失败 = 本动作失败（log 的载荷就是史本身，
    * 静默成功会丢记录）⇒ `TBOARD_HISTORY`。读路径语义：只读板面视图，不触发
    * prune/quarantine 写路径（log 不属板面变更）。 */
  def logSync(
    id: String,
    text: String,
    linksRaw: Option[List[String]] = None,
    actor: String = TaskBoardHistory.Actors.System
  ): Either[ToolError, String] = fileLock.synchronized {
    val body = text.trim
    val links = linksRaw.map(normalizeLinks).getOrElse(Nil)
    val invalid =
      Option.when(body.isEmpty)(
        ToolError(s"TaskBoard: log requires a non-empty `text` (the record to append). (${Codes.Param})"))
        .orElse(checkTextLimit("text", Some(body), LogTextWriteMaxChars,
          s"Split it into multiple action=log calls (each ≤ $LogTextWriteMaxChars chars) — the append-only history keeps every segment."))
        .orElse(checkLinks(links))
    invalid match
      case Some(err) => Left(err)
      case None =>
        val store = readViewSync()
        store.tasks.find(_.id == id) match
          case None => Left(notFound(store, id))
          case Some(existing) =>
            history.appendSync(TaskBoardEvent(
              at = nowStr, kind = TaskBoardHistory.Kinds.Log, id = Some(id), actor = actor,
              text = Some(body), links = links,
              detail = Some(s"chars=${body.length} title=${truncate(existing.title, 60)}"))) match
              case Some(reason) =>
                Left(ToolError(
                  s"TaskBoard: log for #$id could not be persisted — history append failed ($reason) at ${history.file}. " +
                    s"Nothing was recorded (the board is unchanged). Retry, or fix the path/permissions. (${Codes.History})"))
              case None =>
                val linkNote = if links.isEmpty then "" else s" links=${links.size}"
                Right(
                  s"""[OK] TaskBoard logged #$id (${body.length} chars appended; note untouched, board unchanged)$linkNote.$histNoteFor(id)""")
  }

  /** R3/R8 详情查询：全字段 + note 全文 + links + 依赖当前态 + 依赖反查（谁依赖我）
    * + 变更史（**note 主线区** + 状态类次区，各自独立读取窗口）。读路径（零写入、
    * 零锁）；对「主库无该 id 但史文件有该 id」的情况降级为**归档命中**渲染（prune
    * 后仍可查回，不报错退出）；两处都没有才走 `TBOARD_NOT_FOUND`。 */
  def showSync(
    id: String,
    nodeTerminal: Map[String, String] = Map.empty
  ): Either[ToolError, String] =
    val store = readViewSync()
    val notes = history.readFor(id, TaskBoardRenderer.TimelineNoteMaxVersions, TaskBoardHistory.isNoteChange)
    val states = history.readFor(id, TaskBoardRenderer.TimelineStateMaxLines, ev => !TaskBoardHistory.isNoteChange(ev))
    store.tasks.find(_.id == id) match
      case Some(entry) =>
        Right(TaskBoardRenderer.renderShow(entry, store.tasks, nodeTerminal, notes, states))
      case None =>
        if history.countLinesFor(id) > 0 then
          Right(TaskBoardRenderer.renderArchived(id, store.tasks, notes, states))
        else Left(notFound(store, id))

  /** 全板条目快照（批 2 接线新增，向后兼容扩展——既有方法零改动）：注入渲染
    * （renderer 需原始条目做上限/降级装配）与节点归属判定的读入口。读路径不加
    * 锁（同 listSync 取舍：AtomicJson 原子换入，读者只见旧或新完整文件）。 */
  def entriesSync(): List[TaskBoardEntry] = readViewSync().tasks

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
    val History      = "TBOARD_HISTORY" // 升级批：史文件写失败（仅 log 类动作失败）

  /** done 条目保留期（create 时惰性清理，「闭环即删」T2 精神）。§2e */
  val DoneTtlDays: Long = 30L

  // ------------------------------------------------------------------
  // 写入侧容量上限（R5，升级批；**取值本工具独有 —— 严禁跨工具复用**）
  //
  // 取值依据 = 板面存量实测（分发器只读复核 2026-09-11 17:15，本支复读存档）：
  //   218 条 / 文件 475,717 B / note 合计 237,449 字符 / min 128 / P50 765 /
  //   P90 2,322 / P99 3,456 / max 10,993；title max 109 字符。
  // ⇒ 上限必须 ≥ 存量 max（10,993），否则会拒掉板上既有形态的合法更新（RK-4）。
  // ------------------------------------------------------------------

  /** 单次写入 `note` 上限。16,000 = 存量 max(10,993) + 45.6% 余量、P99(3,456) 的
    * 4.6 倍：既容得下「在最长条目上再加一段」的真实用法（存量 max 本身是 3.2×P99
    * 的离群值，卡在 max 附近会让正常更新被拒），又把单条 note 钉在 16 KB 量级——
    * 板面 note 占文件字符量 80%，show 需整条渲染，16,000 与时间线窗口（20,000）
    * 之和仍稳落在工具结果硬顶 50,000 字符之内（见 renderer ShowHardCapChars）。 */
  val NoteWriteMaxChars: Int = 16_000

  /** 单次写入 `title` 上限。300 = 存量 max(109) 的 2.75 倍（标题是列表行主键，
    * 渲染侧本就截 40，这里只封「把长文塞进标题」这一条绕行路径）。 */
  val TitleWriteMaxChars: Int = 300

  /** 单条 `action=log` 的 `text` 上限。4,000：一次追加 = 一段记录（存量 note 的
    * P99 = 3,456 恰好在此量级），单行 ≤ 约 12 KB 字节（CJK 3 B/字符）——远低于
    * JSONL 单行实用边界，同时逼出「长内容分段追加」而非单条灌水。 */
  val LogTextWriteMaxChars: Int = 4_000

  /** `links` 条数上限。20 条 × 单条 300 字符 = 最坏 6 KB，show 的 links 段有界。 */
  val LinksWriteMax: Int = 20

  /** 单条 link 上限（自由文本：文档路径 / commit / 跨账本引用；**不校验可达性**
    * ——错路径与 rebase 后消失的短 hash 静默存在，是 spec R2 已接受的代价）。 */
  val LinkWriteMaxChars: Int = 300

  /** 超限报错的统一修法（可行动：说清「去哪写」+ 存量分布依据）。 */
  val NoteLimitFix: String =
    s"Board baseline 2026-09-11 (218 entries): note min 128 / P50 765 / P90 2,322 / P99 3,456 / max 10,993 " +
      s"(this limit $NoteWriteMaxChars ≥ that max). Keep `note` as the current-state summary and append long " +
      s"content with action=log instead (append-only, never rewrites the note)."

  type Entry = TaskBoardEntry
  val Entry = TaskBoardEntry

  type Store = TaskBoardData
  val Store = TaskBoardData

  /** 每项目一实例（批 2 挂 ProjectRuntime.board；open 即建，无 IO、无失败路径）。 */
  def open(project: String, workspace: String): TaskBoardStore =
    new TaskBoardStore(project, workspace)

  /** ⚠node-done join 的映射构造单点（批 2 新增，向后兼容扩展——纯函数）：Flow Map
    * 节点集 → nodeId→终态映射。仅 completed/failed/cancelled 计入（§2d：blocked
    * 节点「永不过期+必留主图」与任务 blocked 语义自然对齐，不标漂移）；renderer
    * 的 nodeDone 只做 contains 判定，过滤语义收口在此。 */
  def nodeTerminalMap(nodes: Iterable[NodeDef]): Map[String, String] =
    val drift = Set(NodeLifecycle.Completed, NodeLifecycle.Failed, NodeLifecycle.Cancelled)
    nodes.collect { case n if drift(n.status) => n.id -> n.status }.toMap

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

  /** links 列表规整：去空白、去重（**不做可达性/格式校验**——R2 明示不校验）。 */
  private[project] def normalizeLinks(raw: List[String]): List[String] =
    raw.map(_.trim).filter(_.nonEmpty).distinct

  /** 写入侧文本长度校验（R5）：超限 → Left(说清现值/上限/修法)。 */
  private[project] def checkTextLimit(field: String, value: Option[String], max: Int, fix: String): Option[ToolError] =
    value.map(_.trim).filter(_.nonEmpty).filter(_.length > max).map { v =>
      ToolError(
        s"TaskBoard: `$field` is ${v.length} chars — the write-side limit is $max chars. $fix (${Codes.Param})")
    }

  /** links 容量校验（条数 + 单条长度）。存在性**不校验**。 */
  private[project] def checkLinks(links: List[String]): Option[ToolError] =
    if links.size > LinksWriteMax then
      Some(ToolError(
        s"TaskBoard: too many `links` (${links.size}) — max $LinksWriteMax per entry. " +
          s"Keep the most relevant anchors (paths/commits/ids) and drop the rest. (${Codes.Param})"))
    else
      links.find(_.length > LinkWriteMaxChars).map { l =>
        ToolError(
          s"TaskBoard: link '${truncate(l, 60)}' is ${l.length} chars — max $LinkWriteMaxChars per link. " +
            s"Use a path / commit hash / id, not free-form prose (that belongs in `note` or action=log). (${Codes.Param})")
      }

  /** update 的「本次实际变更」事件清单（**逐字段一行**，作者 2026-09-11 17:27 口径）：
    * note 覆盖 = `old`/`new` **两侧全文**（可还原）；status = `from`/`to`；其余结构
    * 字段 = ≤200 字摘要（状态类不得撑大文件）。零变更（纯 no-op update）→ 空清单
    *（不写史，避免噪声）。 */
  private[project] def updateEvents(id: String, at: String, actor: String, before: Entry, after: Entry): List[TaskBoardEvent] =
    val buf = scala.collection.mutable.ListBuffer[TaskBoardEvent]()
    def ev(field: String, o: Option[String], n: Option[String]): Unit =
      buf += TaskBoardEvent(at = at, kind = TaskBoardHistory.Kinds.Update, id = Some(id), actor = actor,
        field = Some(field), prev = o, next = n)
    if after.status != before.status then
      buf += TaskBoardEvent(at = at, kind = TaskBoardHistory.Kinds.Update, id = Some(id), actor = actor,
        field = Some("status"), from = Some(before.status), to = Some(after.status))
    if after.note != before.note then
      buf += TaskBoardEvent(at = at, kind = TaskBoardHistory.Kinds.Update, id = Some(id), actor = actor,
        field = Some("note"), prev = before.note.filter(_.nonEmpty), next = after.note.filter(_.nonEmpty),
        detail = Some(s"note chars ${before.note.map(_.length).getOrElse(0)}→${after.note.map(_.length).getOrElse(0)}"))
    if after.title != before.title then ev("title", shortText(Some(before.title)), shortText(Some(after.title)))
    if after.assignee != before.assignee then ev("assignee", shortText(before.assignee), shortText(after.assignee))
    if after.nodeId != before.nodeId then ev("nodeId", shortText(before.nodeId), shortText(after.nodeId))
    if after.blocks != before.blocks then ev("blocks", shortList(before.blocks), shortList(after.blocks))
    if after.links != before.links then ev("links", shortList(before.links), shortList(after.links))
    buf.toList

  /** close 事件：带 outcome（note 被追加）⇒ note 类行（`old`/`new` 两侧全文，可还原
    * `[done] outcome` 那一刻的前后内容）；无 outcome（note 未变）⇒ 极简状态行。 */
  private[project] def closeEvent(
    id: String,
    at: String,
    actor: String,
    before: Entry,
    after: Entry,
    outcome: Option[String]
  ): TaskBoardEvent =
    if after.note != before.note then
      TaskBoardEvent(at = at, kind = TaskBoardHistory.Kinds.Close, id = Some(id), actor = actor,
        field = Some("note"), from = Some(before.status), to = Some(after.status),
        prev = before.note.filter(_.nonEmpty), next = after.note.filter(_.nonEmpty),
        detail = outcome.map(o => s"outcome=${truncate(o.replace("\n", " ⏎ "), 200)}"))
    else
      TaskBoardEvent(at = at, kind = TaskBoardHistory.Kinds.Close, id = Some(id), actor = actor,
        from = Some(before.status), to = Some(after.status))

  /** 结构字段摘要（状态类极简行：<=200 字，单行化）。 */
  private def shortText(v: Option[String]): Option[String] =
    v.map(s => truncate(s.replace("\n", " ⏎ "), 200))

  private def shortList(v: List[String]): Option[String] =
    Option.when(v.nonEmpty)(truncate(v.mkString(","), 200))

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

  /** done 条目惰性清理（closedAt 超 30d；解析失败保守保留）。返回 (清理后, 被清条目)。
    * 升级批：返回被清条目本体（而非计数），供调用方逐条写 `prune` 史行——
    * 「条目消亡」这件事本身留痕，且史文件零接触（prune 只过滤 store.tasks）。 */
  private[project] def pruneDone(store: Store): (Store, List[Entry]) =
    val cutoff = Instant.now().minusSeconds(DoneTtlDays * 24 * 3600)
    val (expired, kept) = store.tasks.partition { t =>
      t.status == Status.Done &&
        t.closedAt.flatMap(s => scala.util.Try(Instant.parse(s)).toOption).exists(_.isBefore(cutoff))
    }
    (store.copy(tasks = kept), expired)
end TaskBoardStore
