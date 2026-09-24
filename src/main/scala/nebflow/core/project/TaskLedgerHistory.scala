package nebflow.core.project

import io.circe.Codec
import io.circe.syntax.*
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.parser.decode

import java.time.Instant

import nebflow.core.{NebflowLogger, PathUtil}

/**
 * TaskLedgerHistory —— 合一账本的变更史（taskunify 实施批，2026-09-24）。
 *
 * 定位：`Task` 工具（Nebula 独占写面）的**变更史数据面**——append-only JSONL，
 * **单全局文件** `~/.nebflow/tasks-v2-history.jsonl`（与账本同层同目录，非
 * per-workspace：合一账本 = 单编号空间 + 单水位 ⇒ 单一史文件，按条目 id 过滤）。
 *
 * 与旧两史的差别（合一，非别名）：旧面 = `TaskListHistory`（`tasks-history.jsonl`
 * per-home）+ `TaskBoardHistory`（`task-history.jsonl` per-workspace）两套并存；
 * 本件 = **一套**，落 `<dataRoot>/tasks-v2-history.jsonl`。旧两史**原地不动、零删
 * 零覆写**（只读归档），新代码不再读写它们。
 *
 * 行 schema（注册式扩展：未知 kind / 未知键读取侧照收不拒）：`at`（ISO-8601 UTC）/
 * `kind` / `id`（条目 id；全局事件省略）/ `actor`（`nebula` | `dispatcher` |
 * `node` | `system`；**引擎侧派生，不信客户端参数**）/ `from`（note 类事件的来源
 * 域，见 [[TaskLedgerHistory.Origins]]）/ `text`（note 类事件正文全文）/ `prev`·
 * `next`（note 覆盖类事件两侧全文）/ `links` / `detail`（自由文本）。**无 seq**：
 * 排序键 = 文件行序（append-only 天然时序），`at` 仅用于显示。
 *
 * **主体 = note 时间线**（作者裁定 n 项：结构化 = `from` + 时间戳 + 正文摘录）：
 * note 的唯一写入路径 = 引擎在 Nebula 每次 Mail 到该任务分发器时自动 append
 * （工具层**无** note 参数 ⇒ 结构上做不到）。每次 append 落一条 `kind=note` 行，
 * 带 `from`（来源域）+ `at`（时间戳）+ `text`（正文）。单条上限 16,000 字符
 * （见 [[TaskLedgerStore.NoteCapChars]]）；**超长分段而非截断**（🔴 禁「截断丢弃」
 * ——分段后全文仍在史里）。
 *
 * 轮转（沿用现读参数）：活动文件 > 5 MiB 或 > 20,000 行（先到为准）⇒ **下一次写**
 * 惰性触发；活动 → `.1`（**覆盖上一代**），新活动文件首行写一条 `rotate` 事件
 * （含被归档行数与字节数）。磁盘硬顶 ≈ 10 MiB。读路径零写盘（不轮转）。
 *
 * 锁口径：本模块**零自有锁**——所有 append 都发生在 `TaskLedgerStore.fileLock`
 * 临界区内（写路径），与状态写同临界区天然串行。本模块不持有任务状态。
 */
case class TaskLedgerEvent(
  at: String,
  kind: String,
  id: Option[String] = None,
  actor: String = TaskLedgerHistory.Actors.System,
  from: Option[String] = None,
  text: Option[String] = None,
  prev: Option[String] = None,
  next: Option[String] = None,
  links: List[String] = Nil,
  detail: Option[String] = None
)
object TaskLedgerEvent:
  given Configuration = Configuration.default.withDefaults
  given Codec[TaskLedgerEvent] = ConfiguredCodec.derived

class TaskLedgerHistory private ():
  import TaskLedgerHistory.*

  /** `def` 非 `val`：`PathUtil.dataRoot` 可被测试换根（`setDataRoot`）——同族陷阱。 */
  def file: os.Path = PathUtil.dataRoot / FileName

  def archiveFile: os.Path = PathUtil.dataRoot / ArchiveFileName

  // ------------------------------------------------------------------
  // 写：追加（轮转检查 → 尾换行补齐 → append）
  // ------------------------------------------------------------------

  /** 追加一条事件；`None` = 成功，`Some(reason)` = 失败（调用方据此在结果行附加
    * NOTE 或报错，**绝不静默**）。 */
  def appendSync(ev: TaskLedgerEvent): Option[String] =
    try
      val rotated = rotateIfNeeded()
      if !rotated then ensureTrailingNewline()
      os.write.append(file, ev.asJson.noSpaces + "\n", createFolders = true)
      None
    catch
      case e: Exception =>
        val reason = s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("(no message)")}"
        logger.warnSync(s"[taskledger] history append failed ($reason) — path=$file")
        Some(reason)

  /** 活动文件尾字节非 `\n` → 先补一个（crash 半行与下一条粘连的防线）。 */
  private def ensureTrailingNewline(): Unit =
    if os.exists(file) then
      val raf = new java.io.RandomAccessFile(file.toIO, "r")
      try
        val len = raf.length()
        if len > 0 then
          raf.seek(len - 1)
          if raf.read() != '\n' then os.write.append(file, "\n", createFolders = true)
      finally raf.close()

  /** 惰性轮转（只在写路径调用）：活动文件越阈值 → 改名覆盖 `.1` 代 + 新活动文件
    * 首行写 `rotate` 事件。返回是否发生轮转。 */
  private def rotateIfNeeded(): Boolean =
    if !os.exists(file) then false
    else
      val size = os.size(file)
      val overBytes = size > RotationMaxBytes
      val lines = if size > LineCountProbeBytes then countLines() else 0
      if !overBytes && lines <= RotationMaxLines then false
      else
        try
          os.move(file, archiveFile, replaceExisting = true)
          val ev = TaskLedgerEvent(
            at = Instant.now().toString,
            kind = Kinds.Rotate,
            actor = Actors.System,
            detail = Some(s"rotated lines=$lines bytes=$size to=$ArchiveFileName (previous generation dropped)"))
          os.write.over(file, ev.asJson.noSpaces + "\n")
          logger.warnSync(s"[taskledger] history rotated: lines=$lines bytes=$size (archive=$ArchiveFileName)")
          true
        catch
          case e: Exception =>
            logger.warnSync(s"[taskledger] history rotation failed (${e.getMessage}) — appending to active file unchanged")
            false

  private def countLines(): Int =
    try os.read.lines(file).count(_.trim.nonEmpty)
    catch case _: Exception => 0

  // ------------------------------------------------------------------
  // 读：`.1` 代 + 活动（先归档后活动 ⇒ 天然升序）
  // ------------------------------------------------------------------

  /** 读某条目的事件：按行序升序返回最近 `limit` 条（`only` = 分类过滤，用于
    * 「note 主线」与「状态类次区」各自独立的读取窗口）；`total` = 命中该 id 且
    * 通过 `only` 的行数；`skipped` = 预筛命中但不可解析的行数。读路径零写入。 */
  def readFor(id: String, limit: Int = Int.MaxValue, only: TaskLedgerEvent => Boolean = _ => true): ReadResult =
    val needle = s""""id":"$id""""
    val buf = scala.collection.mutable.ListBuffer[TaskLedgerEvent]()
    var total = 0
    var skipped = 0

    def scan(path: os.Path): Unit =
      if os.exists(path) then
        val lines =
          try os.read.lines(path)
          catch
            case e: Exception =>
              logger.warnSync(s"[taskledger] history read failed (${e.getMessage}) — path=$path")
              Seq.empty
        lines.foreach { raw =>
          val t = raw.trim
          if t.nonEmpty && t.contains(needle) then
            decode[TaskLedgerEvent](t) match
              case Right(ev) =>
                if only(ev) then
                  total += 1
                  if buf.size >= limit then buf.remove(0)
                  buf += ev
              case Left(_) => skipped += 1
        }

    scan(archiveFile)
    scan(file)
    ReadResult(events = buf.toList, total = total, skipped = skipped)

  /** 廉价探针（不解析 JSON）：史文件里是否出现过该 id —— 归档命中判定用。 */
  def countLinesFor(id: String): Int =
    val needle = s""""id":"$id""""
    def count(path: os.Path): Int =
      if !os.exists(path) then 0
      else
        try os.read.lines(path).count(_.contains(needle))
        catch case _: Exception => 0
    count(archiveFile) + count(file)
end TaskLedgerHistory

object TaskLedgerHistory:

  val FileName        = "tasks-v2-history.jsonl"
  val ArchiveFileName = "tasks-v2-history.1.jsonl"

  /** 活动文件轮转阈值（> 5 MiB 或 > 20,000 行，先到为准）⇒ 全盘硬顶 ≤ 2 代 ≈ 10 MiB。
    * 取值 = 沿用现读（任务书 §② 定值：「轮转参数沿用现读 = 5 MiB / 20,000 行」）。 */
  val RotationMaxBytes: Long = 5L * 1024 * 1024
  val RotationMaxLines: Int  = 20_000

  /** 行数探测门槛：不足 1 MiB 的文件不可能越 2 万行阈值（轮转检查常态 O(1)）。 */
  val LineCountProbeBytes: Long = 1L * 1024 * 1024

  /** actor 值域：引擎侧派生（Nebula / 分发器 / 流节点 / 工具内建 system）。 */
  object Actors:
    val Nebula     = "nebula"
    val Dispatcher = "dispatcher"
    val Node       = "node"
    val System     = "system"

  /** note 类事件的**来源域**（`from` 字段值；裁定 n：结构化 = `from` + 时间戳 + 正文摘录）。 */
  object Origins:
    /** Nebula 的 Mail 自动 append（唯一正常写入路径）。 */
    val Nebula = "nebula"
    /** 引擎侧系统事件（归属修复 / 迁移类）。 */
    val Engine = "engine"

  /** 事件 kind 值域（注册式扩展：新 kind = 本清单加一词 + 写入点调用，读侧零改）。 */
  object Kinds:
    val Note       = "note"       // note 时间线条目（引擎在 Mail 时自动 append）
    val Create     = "create"
    val Update     = "update"
    val Complete   = "complete"
    val Close      = "close"
    val Prune      = "prune"
    val Quarantine = "quarantine"
    val Rotate     = "rotate"

  /** note 主线判定（`show` 主区）：note 类事件 = 时间线主体，状态类退居次区。 */
  def isNoteEvent(ev: TaskLedgerEvent): Boolean =
    ev.kind == Kinds.Note || (ev.kind == Kinds.Update && ev.next.isDefined)

  private[project] val logger = NebflowLogger.forName("nebflow.taskledger.history")

  /** 单例（全局文件，非 per-workspace）。 */
  val instance: TaskLedgerHistory = new TaskLedgerHistory()

  def open(): TaskLedgerHistory = instance

  /** 读取结果：`events` 升序（最近 `limit` 条）；`total` 命中总数；`skipped` 坏行数。 */
  final case class ReadResult(
    events: List[TaskLedgerEvent] = Nil,
    total: Int = 0,
    skipped: Int = 0
  ):
    def truncated: Boolean = total > events.size
end TaskLedgerHistory
