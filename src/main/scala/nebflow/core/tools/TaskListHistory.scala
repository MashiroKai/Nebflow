package nebflow.core.tools

import io.circe.{Codec, Json}
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.parser.decode
import io.circe.syntax.*

import java.time.Instant

import scala.collection.mutable

import nebflow.core.{NebflowLogger, PathUtil}

/**
 * TaskList 变更史 —— 独立 append-only JSONL 数据面（TaskList 升级批 D1/D2/D6）。
 *
 * 动机：`tasks.json` 是整库覆盖写（read-modify-write），前后值即弃；作者裁定
 * 「变更史单独落盘、不随任务消失」⇒ 史不再放任务体，独占一份文件。
 *
 * 落盘：`<dataRoot>/tasks-history.jsonl`（生产 = `~/.nebflow/tasks-history.jsonl`），
 * 归档代 `tasks-history.1.jsonl`。与 tasks.json 同层同目录 ⇒ 同测试隔离
 * （`PathUtil.setDataRoot`）、同「运行时数据层、不进 git、不进任何注入」口径。
 *
 * 行 schema（注册式扩展：未知 kind / 未知键在读取侧照收不拒，同
 * FlowMapEventLog 口径）：`at`（ISO-8601 UTC，与 tasks.json 的
 * createdAt/updatedAt/closedAt 同源）/ `actor`（引擎侧派生，不信客户端）/
 * `kind` ∈ create·update·close·log·correction·prune·quarantine·rotate /
 * `id`（全局事件省略 = null）/ `field`（仅 update）/ `from`·`to`（变更前后值）/
 * `text`（仅 log）/ `links`（仅 log）/ `detail`（outcome 或 system 事件说明）。
 * **无 `seq`**：排序键 = 文件行序（append-only 天然时序），`at` 仅用于显示。
 *
 * **史的主体 = note 内容的变更（作者 2026-09-11 17:27 口径）**：每一次 note 被改都
 * 落一条可还原的内容行 —— `update` 覆盖既有 note 时 `from`/`to` **两侧全文入档**
 * （`field="note"`，JSON 字符串，不截断）；`log` 的 `text` 同日径；`create` 带初始
 * note、`close` 的 `[done] outcome` 追加，同样各落一条 `field="note"` 的
 * `from → to` 内容行。⇒ 时间线可回答「第 N 次补充把做法从 A 改成了 B」，且被覆盖
 * 前的那一版仍可读出。状态类事件（create / close / 非 note 字段 update / prune /
 * quarantine / rotate）保留，但在 `show` 里**退居次要小节 + 极简行**（不淹没主线）。
 *
 * 轮转（D6）：活动文件 > 5 MiB 或 > 20,000 行（先到为准）⇒ **下一次写**时惰性
 * 触发（读路径零写盘，`readFor` 从不轮转）；活动 → `.1`（**覆盖上一代**），新
 * 活动文件首行写一条 `rotate` 事件（含被归档行数与字节数）。磁盘硬顶 ≈ 10 MiB。
 * 手动清理：`mv ~/.nebflow/tasks-history.jsonl{,.archive-<日期>}`（归档，工具无感）
 * 或 `rm ~/.nebflow/tasks-history.jsonl*`（全清，**零主数据影响**）。
 *
 * 锁口径：本模块**零自有锁**——所有 append 都发生在 `TaskListStore.fileLock`
 * 临界区内（写路径），与状态写同临界区天然串行。本模块不持有任务状态。
 */
case class TaskListEvent(
  at: String,
  actor: String,
  kind: String,
  id: Option[String] = None,
  field: Option[String] = None,
  from: Option[Json] = None,
  to: Option[Json] = None,
  text: Option[String] = None,
  links: List[String] = Nil,
  detail: Option[String] = None
)
object TaskListEvent:
  given Configuration = Configuration.default.withDefaults
  given Codec[TaskListEvent] = ConfiguredCodec.derived

/** `actor` 值域（引擎侧派生，不信客户端参数）。实际可达 = [`Nebula`]、[`System`]；
  * [`Node`] / [`Author`] 为预留值（当前无写入点，不自造调用点）。 */
object TaskListActor:
  val Nebula     = "nebula"
  val Node       = "node"
  val Author     = "author"
  val System     = "system"
  val Dispatcher = "dispatcher"
  val Unknown    = "unknown"

/** 事件 kind 值域（未知值读取侧照收）。 */
object TaskListEventKind:
  val Create     = "create"
  val Update     = "update"
  val Close      = "close"
  val Log        = "log"
  val Correction = "correction"
  val Prune      = "prune"
  val Quarantine = "quarantine"
  val Rotate     = "rotate"

object TaskListHistory:

  private val logger = NebflowLogger.forName("nebflow.tasklist")

  /** 活动史文件 / 上一代归档（同目录、覆盖式轮转）。`def` 非 `val`：
    * `PathUtil.dataRoot` 可被测试换根（TaskListStore.file 同款陷阱）。 */
  val ActiveFileName: String  = "tasks-history.jsonl"
  val ArchiveFileName: String = "tasks-history.1.jsonl"

  def activePath: os.Path  = PathUtil.dataRoot / ActiveFileName
  def archivePath: os.Path = PathUtil.dataRoot / ArchiveFileName

  /** 轮转阈值（活动文件，先到为准）与磁盘硬顶（活动 + `.1` 两代）。 */
  val MaxActiveBytes: Long   = 5L * 1024 * 1024
  val MaxActiveLines: Int    = 20000
  val DiskHardCapBytes: Long = 2 * MaxActiveBytes

  /** 行字节下界：任何一行至少含 `"at":"<20+ 字符 ISO-8601>"` ⇒ ≥ 32 B。文件小于
    * `MaxActiveLines * MinLineBytes` 时行数必不超阈 ⇒ 免整文件扫描（快路径）。 */
  private val MinLineBytes: Long = 32L

  private def nowStr: String = Instant.now().toString

  // ------------------------------------------------------------------
  // 身份派生（D5：引擎侧，不信客户端）
  // ------------------------------------------------------------------

  /** system 事件不走本函数（由工具内建写入点直接传 `TaskListActor.System`）。 */
  def actorOf(ctx: ToolContext): String =
    if ctx.isDispatcher then TaskListActor.Dispatcher
    else if ctx.flowNodeId.exists(_.trim.nonEmpty) then TaskListActor.Node
    else
      ctx.agentDef
        .map(_.name.trim.toLowerCase)
        .filter(_.nonEmpty)
        .getOrElse(TaskListActor.Unknown)

  // ------------------------------------------------------------------
  // 写：append（含惰性轮转）
  // ------------------------------------------------------------------

  private def encode(ev: TaskListEvent): String = ev.asJson.noSpaces + "\n"

  /** 追加一条事件。**不抛异常**：append 失败返回 Left（调用方按「不失败主操作 +
   * 结果行 NOTE + WARN」处置，绝不静默）。轮转检查先于追加（下一次写时惰性触发）。 */
  def appendEvent(ev: TaskListEvent): Either[String, Unit] =
    try
      rotateIfNeeded()
      val path = activePath
      // 尾字节非 `\n`（crash 半行）⇒ 先补一个换行，防与下一条粘连
      val pad = if os.exists(path) && os.size(path) > 0 && !endsWithNewline(path) then "\n" else ""
      os.write.append(path, pad + encode(ev), createFolders = true)
      Right(())
    catch
      case e: Exception =>
        val reason = s"${e.getClass.getSimpleName}: ${e.getMessage}"
        logger.warnSync(s"[tasklist] history append failed: $reason")
        Left(reason)

  private def endsWithNewline(path: os.Path): Boolean =
    val raf = new java.io.RandomAccessFile(path.toIO, "r")
    try
      raf.seek(os.size(path) - 1)
      raf.read() == '\n'.toInt
    finally raf.close()

  /** 活动文件越阈 → 归档为 `.1`（覆盖上一代）+ 新活动文件首行写 `rotate` 事件。 */
  private[tools] def rotateIfNeeded(): Option[String] =
    val path = activePath
    if !os.exists(path) then None
    else
      val bytes = os.size(path)
      val lines = if bytes >= MaxActiveLines.toLong * MinLineBytes then countLines(path) else 0
      if bytes <= MaxActiveBytes && lines <= MaxActiveLines then None
      else
        val totalLines = if lines > 0 then lines else countLines(path)
        if os.exists(archivePath) then os.remove(archivePath)
        os.move(path, archivePath)
        val rot = TaskListEvent(
          at = nowStr,
          actor = TaskListActor.System,
          kind = TaskListEventKind.Rotate,
          detail = Some(s"lines=$totalLines bytes=$bytes archived=$ArchiveFileName")
        )
        os.write.over(path, encode(rot), createFolders = true)
        logger.warnSync(
          s"[tasklist] history rotated: $ActiveFileName ($totalLines lines / $bytes bytes) -> $ArchiveFileName (previous generation dropped; disk hard cap ${DiskHardCapBytes} B)")
        Some(s"lines=$totalLines bytes=$bytes")

  private def countLines(path: os.Path): Int =
    val in = java.nio.file.Files.newInputStream(path.toNIO)
    try
      val buf = new Array[Byte](64 * 1024)
      var count = 0
      var n = in.read(buf)
      while n > 0 do
        var i = 0
        while i < n do
          if buf(i) == '\n'.toByte then count += 1
          i += 1
        n = in.read(buf)
      count
    finally in.close()

  // ------------------------------------------------------------------
  // 读：活动 + `.1`（先归档后活动 ⇒ 行序天然升序）
  // ------------------------------------------------------------------

  /** 读取结果：`events` = 该 id 最近 ≤ limit 条（升序）；`matched` = 该 id 事件
    * 总数；`matchedNote` = 其中 **note/log 内容事件**数（作者 17:27 口径的史主体；
    * 其余为状态/结构类事件）；`globalRecent` = 全局事件（无 id）最近 ≤ globalLimit
    * 条（升序）；`unreadable` = 不可解析行数。计数在整文件范围内统计（不受 limit
    * 影响）⇒ 渲染侧的「未显示」计数可精确对账。 */
  final case class ReadResult(
    events: List[TaskListEvent],
    matched: Int,
    matchedNote: Int,
    globalRecent: List[TaskListEvent],
    unreadable: Int
  ):
    def matchedState: Int = matched - matchedNote

  object ReadResult:
    val empty: ReadResult = ReadResult(Nil, 0, 0, Nil, 0)

  /** note/log 内容事件 = 变更史主线（作者 2026-09-11 17:27 口径：史的主体是
    * **note 内容的变更**，可还原「第 N 次补充把做法从 A 改成 B」）。其余
    * （create/close/非 note 字段 update/prune/quarantine/rotate）= 状态类，退居次要区。 */
  def isNoteEvent(ev: TaskListEvent): Boolean =
    ev.kind == TaskListEventKind.Log ||
      ev.kind == TaskListEventKind.Correction ||
      (ev.kind == TaskListEventKind.Update && ev.field.contains("note"))

  def readFor(id: String, limit: Int = 50, globalLimit: Int = 3): ReadResult =
    if limit <= 0 then ReadResult.empty
    else
      val needle = "\"id\":\"" + id + "\""
      var matched = 0
      var matchedNote = 0
      var unreadable = 0
      val perId = mutable.ListBuffer[TaskListEvent]()
      val globals = mutable.ListBuffer[TaskListEvent]()

      def handle(line: String): Unit =
        val t = line.trim
        if t.isEmpty then ()
        // 廉价损坏门：半行 / 非 JSON —— 直接计入坏行（免解析，且不误判为「别的 id」）
        else if !(t.startsWith("{") && t.endsWith("}")) then unreadable += 1
        else if t.contains(needle) then
          decode[TaskListEvent](t).toOption match
            case Some(ev) =>
              matched += 1
              if isNoteEvent(ev) then matchedNote += 1
              perId += ev
              if perId.size > limit then perId.remove(0)
            case None => unreadable += 1
        else if !t.contains("\"id\":") || t.contains("\"id\":null") then
          // 全局事件（id 缺省）候选：只有它们进 globalRecent
          decode[TaskListEvent](t).toOption match
            case Some(ev) if ev.id.isEmpty =>
              globals += ev
              if globals.size > globalLimit then globals.remove(0)
            case Some(_) => ()
            case None    => unreadable += 1
        // 其它：格式良好但属于别的 id —— 跳过（非坏行，零解析开销）

      List(archivePath, activePath).foreach { p =>
        if os.exists(p) then os.read(p).split("\n", -1).foreach(handle)
      }
      ReadResult(perId.toList, matched, matchedNote, globals.toList, unreadable)
  end readFor

end TaskListHistory

/** 史 append 失败的累积器：主操作不因史写失败而失败，但结果行必须附
  * `NOTE: history append failed (<reason>)`（绝不静默）。 */
private[tools] final class HistoryLog:
  private val errors = mutable.ListBuffer[String]()

  def append(ev: TaskListEvent): Unit =
    TaskListHistory.appendEvent(ev) match
      case Right(_)  => ()
      case Left(err) => errors += err

  def note: String =
    if errors.isEmpty then ""
    else s" NOTE: history append failed (${errors.distinct.mkString("; ")})."
