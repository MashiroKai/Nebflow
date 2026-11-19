package nebflow.db

import io.circe.Json

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * 分片/轮转/索引（设计 §3.8）：`<base>.<n>.jsonl`（n 从 1）按字节切档；
 * `events.idx` 记每片首尾 seq——**派生面**（坏 ⇒ 删了重建，三标签③）。
 * 轮转 ≠ 删除：物化删除只走遗忘刀的显式 purge 动词（设计 §4）。
 *
 * 会话流同样走本机制（base = messages|ui；分片参数 64 MiB〔提案〕——与 triple 侧
 * 对拍属 D-12/#148 在途对齐位）。单行超限例外放行独占新片并 WARN。
 */
object Shards:

  /** 分片索引行（events.idx 的一行）。 */
  final case class IdxRow(shard: Int, firstSeq: Long, lastSeq: Long, bytes: Long, count: Long)

  val IdxFileName: String = "events.idx"

  def shardPath(dir: Path, base: String, n: Int): Path = dir.resolve(s"$base.$n.jsonl")

  /** 目录下该 base 的全部分片号（升序；排除隔离档与 tmp 残留）。 */
  def listShardNos(dir: Path, base: String): List[Int] =
    listNames(dir)
      .flatMap(shardNoOf(base))
      .distinct
      .sorted

  /** 当前（最新）分片号；0 = 尚无分片。 */
  def currentShardNo(dir: Path, base: String): Int =
    val nos = listShardNos(dir, base)
    if nos.isEmpty then 0 else nos.last

  private def listNames(dir: Path): List[String] =
    if !Files.exists(dir) then Nil
    else
      val s = Files.list(dir)
      try s.iterator().asScala.map(_.getFileName.toString).toList
      finally s.close()

  private def shardNoOf(base: String)(name: String): Option[Int] =
    val prefix = s"$base."
    val suffix = ".jsonl"
    if name.startsWith(prefix) && name.endsWith(suffix)
      && !Quarantine.isQuarantinedName(name) && !WriteFace.isTmpName(name) then
      val mid = name.substring(prefix.length, name.length - suffix.length)
      if mid.nonEmpty && mid.forall(_.isDigit) then mid.toIntOption else None
    else None

  /** 一次追加的结果（shardNo = 落点；rotated = 本次开新片；oversized = 单行超限例外放行）。 */
  final case class AppendOutcome(shardNo: Int, rotated: Boolean, oversized: Boolean)

  /**
   * 追加一行到分片族（调用方已过 [[WriteFace.prepareAppend]] 前置检）。
   * 轮转判据：现片非空且加行将超 `maxShardBytes` ⇒ 开新片；`rotated` 且旧片为 Batched 档
   * ⇒ 收口 force 旧片。单行自身超限 ⇒ 例外放行独占新片（返回 oversized，调用方 WARN）。
   */
  def appendLine(
    dir: Path,
    base: String,
    line: String,
    tier: WriteFace.FsyncTier,
    maxShardBytes: Long
  ): AppendOutcome =
    val cur = currentShardNo(dir, base)
    val lineBytes = line.getBytes("UTF-8").length.toLong + 1L
    val (no, rotatedFrom, oversized) =
      if cur == 0 then (1, 0, lineBytes > maxShardBytes)
      else
        val curSize = Files.size(shardPath(dir, base, cur))
        if curSize > 0 && curSize + lineBytes > maxShardBytes then
          (cur + 1, cur, lineBytes > maxShardBytes)
        else (cur, 0, false)
    // 轮转离场的 Batched 片在此收口（PerRecord 片每条已 force）。
    if rotatedFrom >= 1 && tier == WriteFace.FsyncTier.Batched then
      WriteFace.forceFile(shardPath(dir, base, rotatedFrom))
    WriteFace.appendDurable(shardPath(dir, base, no), line, tier)
    AppendOutcome(no, rotatedFrom >= 1 || cur == 0, oversized)
  end appendLine

  /** 扫描结果：完整帧原文（按序）+ 撕裂尾（残段计数 0/1，崩溃窗工件）。 */
  final case class Framed(records: List[String], tornTailDropped: Int)

  /**
   * 扫描一个分片文件。文件缺 ⇒ None（Absent 判据）；0 长 ⇒ 空帧集（合法：建档与首写
   * 之间崩溃）。**framed-but-invalid 行不在本面判定**（解码是调用方的语义——坏帧升级
   * Corrupt 由读面做）；撕裂尾在此显式计数而非静默。
   */
  def scanShard(file: Path): Option[Framed] =
    if !Files.exists(file) then None
    else
      val bytes = Files.readAllBytes(file)
      if bytes.isEmpty then Some(Framed(Nil, 0))
      else
        val framed = List.newBuilder[String]
        var lastNl = -1
        var i = 0
        while i < bytes.length do
          if bytes(i) == '\n'.toByte then
            framed += new String(bytes, lastNl + 1, i - (lastNl + 1), "UTF-8")
            lastNl = i
          i += 1
        val torn = bytes.length - (lastNl + 1)
        Some(Framed(framed.result(), if torn > 0 then 1 else 0))

  // ── 分片索引（events.idx；派生面：坏 ⇒ 删了重建，三标签③）────────────────

  /** 读 idx：缺 ⇒ Right(Nil)；任何坏行/撕裂尾 ⇒ Left(reason)——调用方删除重建。 */
  def readIdx(dir: Path): Either[String, List[IdxRow]] =
    val f = dir.resolve(IdxFileName)
    scanShard(f) match
      case None => Right(Nil)
      case Some(Framed(lines, torn)) =>
        if torn > 0 then Left("torn tail in idx")
        else
          val rows = List.newBuilder[IdxRow]
          var bad: Option[String] = None
          lines.foreach { l =>
            if bad.isEmpty && l.nonEmpty then // 空白行容忍（空 idx 的合法形态）
              io.circe.parser.parse(l).flatMap(_.as[IdxRow]) match
                case Right(r) => rows += r
                case Left(e) => bad = Some(s"bad idx line: ${e.getMessage}")
          }
          bad.toLeft(rows.result())

  /** 全量重写 idx（五步写；仅 rebuild 与增量维护调用）。空清单 ⇒ 空档（零字节）。 */
  def writeIdx(dir: Path, rows: List[IdxRow]): Unit =
    val text = if rows.isEmpty then "" else rows.map(idxRowJson).mkString("", "\n", "\n")
    WriteFace.writeFiveStepText(dir.resolve(IdxFileName), text)

  def idxRowJson(r: IdxRow): String =
    Json.obj(
      "shard" -> Json.fromInt(r.shard),
      "firstSeq" -> Json.fromLong(r.firstSeq),
      "lastSeq" -> Json.fromLong(r.lastSeq),
      "bytes" -> Json.fromLong(r.bytes),
      "count" -> Json.fromLong(r.count)
    ).noSpaces

  private given io.circe.Decoder[IdxRow] = c =>
    for
      shard <- c.downField("shard").as[Int]
      first <- c.downField("firstSeq").as[Long]
      last <- c.downField("lastSeq").as[Long]
      bytes <- c.downField("bytes").as[Long]
      count <- c.downField("count").as[Long]
    yield IdxRow(shard, first, last, bytes, count)

end Shards
