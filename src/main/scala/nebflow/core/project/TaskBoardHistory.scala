package nebflow.core.project

import io.circe.Codec
import io.circe.syntax.*
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.parser.decode

import java.time.Instant

import nebflow.core.{NebflowLogger, PathUtil}

/**
 * TaskBoardHistory —— 任务板变更史（TaskBoard 升级批，2026-09-11）。
 *
 * 定位：TaskBoard 的「变更史」数据面——append-only JSONL，**per-workspace** 独立
 * 落盘（`<workspace>/.nebflow/task-history.jsonl`，一项目一板面一史文件）。板面
 * 状态仍唯一归 `task-board.json`（[[TaskBoardStore]] 是唯一状态机）；本模块只做
 * 「事件行的追加 / 读取 / 轮转」，零状态、零锁（append 全部发生在 store 的
 * fileLock.synchronized 段内 ⇒ 与状态写同临界区，天然串行）。
 *
 * 为何独立落盘（而非塞进条目字段）：板面每 30 天惰性 prune 掉 done 条目，若史挂在
 * 条目上，条目消亡即史消亡——「关单后仍被改」这类事实就再也查不回来。独立文件
 * ⇒ prune 只过滤 `store.tasks`，史零接触；反过来 prune 会为每条被清条目写一条
 * `prune` 事件 ⇒「任务条目消亡」本身留痕（show 对已 prune 的 id 仍可读史）。
 *
 * 行 schema（一行一事件、JSON object、注册式扩展：未知 kind / 未知键读取侧照收不
 * 拒）：`at`（ISO-8601 UTC，与 `createdAt/updatedAt/closedAt` 同口径）/
  * `id`（条目 id；全局事件省略）/ `actor`（`dispatcher` | `node` | `system`，
 * **引擎侧派生，不信客户端参数**）/ `kind` ∈ `create`·`update`·`close`·`log`·
 * `prune`·`quarantine`·`rotate` / `field`（update：本次变更的字段名，**逐字段一行**）
 * / `from`·`to`（status 迁移前后）/ `prev`·`next`（变更前后值：**note 类 = 全文**，
 * 结构类 = ≤200 字摘要）/ `text`（仅 log：追加段全文）/ `links`（仅 log，可选）/
 * `detail`（自由文本）。**无 seq**：排序键 = 文件行序（append-only 天然时序，免
 * 读改写状态）；`at` 仅用于显示，同毫秒多事件靠行序。
 *
 * **主体 = note 内容变更**（作者 2026-09-11 17:27 口径，最高优先）：目标能力是回答
 * 「这个任务在第 N 次补充里把做法从 A 改成了 B」——所以 ①`update` 覆盖 note **必须
 * 同时记 `prev` 与 `next` 两侧全文**（只记「变了」或只记 next ⇒ 覆盖动作不可还原 ⇒ 红）；
 * ②`close` 的 `[done] outcome` 追加同样记两侧（note 追加）；③`log` 的 `text` = 追加
 * 段全文；④状态类事件（任务状态变更）只作**次要留痕**（极简行），不得淹没 note 主线。
 *
 * 容量与生命周期（作者裁定口径）：活动文件 > 5 MiB 或 > 20,000 行 → **下一次写**
 * 惰性轮转（`task-history.jsonl` → `task-history.1.jsonl`，覆盖上一代），新活动
 * 文件首行写 `rotate` 事件（含被淘汰的行数/字节数）⇒ 全盘硬顶 ≤ 2 代 ≈ 10 MiB。
 * **读路径不写盘**（与「list/show 零写入」同口径）。无时间 TTL：唯一淘汰路径 =
 * 容量轮转（整代淘汰）。手动清理：`mv <workspace>/.nebflow/task-history.jsonl
 * {,.archive-<日期>}`（归档，工具无感，下次写自建）或 `rm .../task-history.jsonl*`
 * （全清，**零主数据影响**——`task-board.json` 不受任何影响）。
 *
 * 边界：史文件是任务状态的**审计副本**，仍属任务域 ⇒ ① 不写记忆；② 不进 system
 * prompt / 注入块（注入口径不变，明细永不注入）；③ 落 `.nebflow/` 运行时数据层
 * ⇒ 不进 git。整批零 repo 数据面改动。
 *
 * 容错：单行不可解析 → 读取侧跳过并计数（`show` 末尾提示 `history: <k> unreadable
 * line(s) skipped` + WARN），绝不崩；追加前若尾字节非 `\n`（crash 半行）先补一个
 * `\n`，防半行与下一条粘连；追加失败 → 返回 `Some(reason)`，由调用方决定（板面
 * 变更类：不失败主操作但在结果行附 `NOTE: history append failed (...)`；log 类：
 * log 本身就是载荷，直接报 `TBOARD_HISTORY`）。
 */
/** 变更史事件（JSONL 单行 wire schema）。字段全默认 = 缺键零迁移；未知键容忍
  *（circe 派生默认忽略未知字段）⇒ 加字段不改旧行解析，注册式扩展。
  *
  * **主体 = note 内容变更**（作者 2026-09-11 17:27 口径）：`update` 覆盖 note 时
  * `old`/`new` 两侧**全文**都记（覆盖动作必须可还原）；`close` 的 `[done] outcome`
  * 追加同样记 `old`/`new` 两侧；`log` 追加的载荷 = `text`（追加段全文，note 零改写）。
  * 状态类事件（create / 非 note 字段的 update / 无 outcome 的 close / prune /
  * quarantine / rotate）只记极简值：`from`/`to`（status 迁移）与截断到 200 字的
  * `old`/`new`（结构字段前后值）——**不得撑大文件、不得淹没 note 主线**。 */
case class TaskBoardEvent(
  at: String,
  kind: String,
  id: Option[String] = None,
  actor: String = TaskBoardHistory.Actors.System,
  field: Option[String] = None,
  from: Option[String] = None,
  to: Option[String] = None,
  prev: Option[String] = None,
  next: Option[String] = None,
  text: Option[String] = None,
  links: List[String] = Nil,
  detail: Option[String] = None
)
object TaskBoardEvent:
  given Configuration = Configuration.default.withDefaults
  given Codec[TaskBoardEvent] = ConfiguredCodec.derived

class TaskBoardHistory private (workspace: String):
  import TaskBoardHistory.*

  /** `def` 非 `val`：路径由 open 时传入的 workspace 派生（同族惯例）。与
    * `task-board.json` 同目录同层 ⇒ 同隔离（临时 workspace 一份，生产一项目一份）。 */
  private def dir: os.Path = os.Path(workspace, PathUtil.dataRoot) / ".nebflow"

  def file: os.Path = dir / FileName

  def archiveFile: os.Path = dir / ArchiveFileName

  // ------------------------------------------------------------------
  // 写：追加（轮转检查 → 尾换行补齐 → append）
  // ------------------------------------------------------------------

  /** 追加一条事件；`None` = 成功，`Some(reason)` = 失败（调用方据此在结果行附加
    * NOTE 或报错，绝不静默）。 */
  def appendSync(ev: TaskBoardEvent): Option[String] =
    try
      val rotated = rotateIfNeeded()
      if !rotated then ensureTrailingNewline()
      os.write.append(file, ev.asJson.noSpaces + "\n", createFolders = true)
      None
    catch
      case e: Exception =>
        val reason = s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("(no message)")}"
        logger.warnSync(s"[taskboard] history append failed ($reason) — path=$file")
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
      // 行数只在文件确实可能装下 2 万行时才逐行计数（小文件 O(1) 短路）
      val lines = if size > LineCountProbeBytes then countLines() else 0
      if !overBytes && lines <= RotationMaxLines then false
      else
        try
          os.move(file, archiveFile, replaceExisting = true)
          val ev = TaskBoardEvent(
            at = Instant.now().toString,
            kind = Kinds.Rotate,
            actor = Actors.System,
            detail = Some(s"rotated lines=$lines bytes=$size to=$ArchiveFileName (previous generation dropped)"))
          os.write.over(file, ev.asJson.noSpaces + "\n")
          logger.warnSync(s"[taskboard] history rotated: lines=$lines bytes=$size (archive=$ArchiveFileName)")
          true
        catch
          case e: Exception =>
            logger.warnSync(s"[taskboard] history rotation failed (${e.getMessage}) — appending to active file unchanged")
            false

  private def countLines(): Int =
    try os.read.lines(file).count(_.trim.nonEmpty)
    catch case _: Exception => 0

  // ------------------------------------------------------------------
  // 读：活动文件 + `.1` 代（先 `.1` 后活动 ⇒ 天然升序）
  // ------------------------------------------------------------------

  /** 读某条目的事件：按行序升序返回最近 `limit` 条（`only` = 事件分类过滤，用于
    * 「note 主线」与「状态类次区」各自独立的读取窗口——避免状态类事件把 note 版本
    * 挤出窗口）；`total` = 命中该 id 且通过 `only` 的行数；`skipped` = 预筛命中但
    * 不可解析的行数。读路径零写入（不轮转、不改文件）。 */
  def readFor(id: String, limit: Int = Int.MaxValue, only: TaskBoardEvent => Boolean = _ => true): ReadResult =
    val needle = s""""id":"$id""""
    val buf = scala.collection.mutable.ListBuffer[TaskBoardEvent]()
    var total = 0
    var skipped = 0

    def scan(path: os.Path): Unit =
      if os.exists(path) then
        val lines =
          try os.read.lines(path)
          catch
            case e: Exception =>
              logger.warnSync(s"[taskboard] history read failed (${e.getMessage}) — path=$path")
              Seq.empty
        lines.foreach { raw =>
          val t = raw.trim
          if t.nonEmpty && t.contains(needle) then
            decode[TaskBoardEvent](t) match
              case Right(ev) =>
                if only(ev) then
                  total += 1
                  if buf.size >= limit then buf.remove(0)
                  buf += ev
              case Left(_) => skipped += 1
        }

    scan(archiveFile) // 上一代在前（升序）
    scan(file)
    ReadResult(events = buf.toList, total = total, skipped = skipped)

  /** 廉价探针（不解析 JSON）：史文件里是否出现过该 id —— 归档命中判定用（`show`
    * 对主库已无、史里仍有的 id 走降级渲染）。坏行照计（出现即命中）。 */
  def countLinesFor(id: String): Int =
    val needle = s""""id":"$id""""
    def count(path: os.Path): Int =
      if !os.exists(path) then 0
      else
        try os.read.lines(path).count(_.contains(needle))
        catch case _: Exception => 0
    count(archiveFile) + count(file)
end TaskBoardHistory

object TaskBoardHistory:

  val FileName        = "task-history.jsonl"
  val ArchiveFileName = "task-history.1.jsonl"

  /** 活动文件轮转阈值（> 5 MiB 或 > 20,000 行，先到为准）⇒ 全盘硬顶 ≤ 2 代 ≈ 10 MiB。 */
  val RotationMaxBytes: Long = 5L * 1024 * 1024
  val RotationMaxLines: Int  = 20_000

  /** 行数探测门槛：单行最小序列化形态 ≈ 70 B ⇒ 2 万行 ≥ 1.4 MiB，故不足 1 MiB 的
    * 文件不可能越行数阈值——小文件跳过逐行计数（轮转检查常态 O(1)）。 */
  val LineCountProbeBytes: Long = 1L * 1024 * 1024

  /** actor 值域：引擎侧派生（分发器身份 / 流节点身份 / 工具内建 system）。 */
  object Actors:
    val Dispatcher = "dispatcher"
    val Node       = "node"
    val System     = "system"

  /** 事件 kind 值域（注册式扩展：新 kind = 本清单加一词 + 写入点调用，读侧零改）。 */
  object Kinds:
    val Create     = "create"
    val Update     = "update"
    val Close      = "close"
    val Log        = "log"
    val Prune      = "prune"
    val Quarantine = "quarantine"
    val Rotate     = "rotate"

  private[project] val logger = NebflowLogger.forName("nebflow.taskboard.history")

  /** note 类事件判定（作者口径 2026-09-11 17:27：历史的主体是 **note 内容变更**）：
    * `update` 覆盖 note / `close` 带 `[done] outcome` 追加 / `log` 补充记录。
    * 其余（create、非 note 字段的 update、无 outcome 的 close、prune、quarantine、
    * rotate）= 状态类事件 ⇒ `show` 归入次区（极简行）。写读两侧共用本单点。 */
  def isNoteChange(ev: TaskBoardEvent): Boolean =
    (ev.kind == Kinds.Update && ev.field.contains("note")) ||
      (ev.kind == Kinds.Close && (ev.prev.isDefined || ev.next.isDefined)) ||
      ev.kind == Kinds.Log

  /** 每 workspace 一实例（无 IO、无失败路径）。 */
  def open(workspace: String): TaskBoardHistory = new TaskBoardHistory(workspace)

  /** 读取结果：`events` 升序（最近 `limit` 条）；`total` 命中总数；`skipped` 坏行数。 */
  final case class ReadResult(
    events: List[TaskBoardEvent] = Nil,
    total: Int = 0,
    skipped: Int = 0
  ):
    /** 是否因 `limit` 截断（`show` 据此渲染 `(+N older ...)`）。 */
    def truncated: Boolean = total > events.size
end TaskBoardHistory
