package nebflow.core.tools

import nebflow.core.NebflowLogger

/**
 * Append-only JSONL 账本的读写基元（记忆改造批 2026-09-12，IMPL-1）。
 *
 * 形态对齐既有先例 `TaskListHistory`（`tasks-history.jsonl`，独立文件、append-only、
 * 单代覆盖轮转、行序即时序、无 seq）与 `TaskBoardHistory`；本对象只抽**读写与轮转**
 * 两件事，schema 与语义留在各自消费方（`MemoryQueue` / `MemoryHistory`）。
 *
 * 三条不变式：
 *   1. **append-only**：只追加不重写；崩溃留下的半行由读取侧容忍（`readLines`
 *      返回原始行，解析失败的调用方自行计数，不抛）。
 *   2. **惰性轮转**：活动文件越阈（先到者为准：`maxBytes` / `maxLines`）时，
 *      **下一次写**触发活动 → 归档（单代覆盖）；读路径零写盘。
 *   3. **读跨代**：`readLines` 恒读 `[archive, active]`，即先归档后活动 ⇒ 行序
 *      天然升序（归档 = 更早的行）。
 *
 * 轮转阈值由调用方传入（记忆域两个账本各自可达），本对象不持状态。
 */
object JsonlLedger:

  private val logger = NebflowLogger.forName("nebflow.memory.ledger")

  /** 行字节下界：任何一行至少含 `"atMs":<13 位>` 之类 ⇒ ≥ 24 B。文件小于
    * `maxLines * MinLineBytes` 时行数必不超阈 ⇒ 免整文件扫描（快路径）。 */
  private val MinLineBytes: Long = 24L

  /** 追加一行（自动补 `\n`；含惰性轮转）。**不抛异常**：失败返回 Left。 */
  def appendLine(
    active: os.Path,
    archive: os.Path,
    line: String,
    maxBytes: Long,
    maxLines: Int
  ): Either[String, Unit] =
    try
      rotateIfNeeded(active, archive, maxBytes, maxLines)
      // 尾字节非 `\n`（crash 半行）⇒ 先补换行，防与下一条粘连
      val pad = if os.exists(active) && os.size(active) > 0 && !endsWithNewline(active) then "\n" else ""
      os.write.append(active, pad + line + "\n", createFolders = true)
      Right(())
    catch
      case e: Exception =>
        Left(s"${e.getClass.getSimpleName}: ${e.getMessage}")

  /** 读取结果：两代原始行（升序）+ 不可读行数（IO 级失败）。
    * `unreadableLines` 只计「文件读不出」的行，不解析 JSON（解析归调用方）。 */
  final case class ReadBack(lines: Vector[String], ioError: Option[String])

  def readLines(active: os.Path, archive: os.Path): ReadBack =
    val buf = Vector.newBuilder[String]
    try
      List(archive, active).foreach { p =>
        if os.exists(p) && os.isFile(p) then
          os.read(p).split("\n", -1).foreach(l => if l.trim.nonEmpty then buf += l)
      }
      ReadBack(buf.result(), None)
    catch
      case e: Exception =>
        ReadBack(buf.result(), Some(s"${e.getClass.getSimpleName}: ${e.getMessage}"))

  /** 活动文件是否越阈（惰性轮转判定；读路径也用它做诊断）。 */
  private[tools] def overThreshold(active: os.Path, maxBytes: Long, maxLines: Int): Boolean =
    if !os.exists(active) then false
    else
      val bytes = os.size(active)
      if bytes <= maxBytes then false
      else
        val lines = if bytes >= maxLines.toLong * MinLineBytes then countLines(active) else 0
        lines > maxLines

  /** 活动文件越阈 → 归档为单代（覆盖上一代）+ 新活动文件空置。返回被归档的
    * (行数, 字节数)（供调用方落一条自述行）。 */
  private[tools] def rotateIfNeeded(
    active: os.Path,
    archive: os.Path,
    maxBytes: Long,
    maxLines: Int
  ): Option[(Int, Long)] =
    if !os.exists(active) then None
    else
      val bytes = os.size(active)
      val lines = if bytes >= maxLines.toLong * MinLineBytes then countLines(active) else 0
      if bytes <= maxBytes && lines <= maxLines then None
      else
        val totalLines = if lines > 0 then lines else countLines(active)
        if os.exists(archive) then os.remove(archive)
        os.move(active, archive)
        logger.warnSync(
          s"[memory-ledger] rotated ${active.last} ($totalLines lines / $bytes bytes) -> ${archive.last} (previous generation dropped)")
        Some((totalLines, bytes))

  private def endsWithNewline(path: os.Path): Boolean =
    val raf = new java.io.RandomAccessFile(path.toIO, "r")
    try
      raf.seek(os.size(path) - 1)
      raf.read() == '\n'.toInt
    finally raf.close()

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

end JsonlLedger
