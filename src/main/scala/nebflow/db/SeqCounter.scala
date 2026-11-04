package nebflow.db

import io.circe.Json
import io.circe.parser.parse

import java.nio.file.{Files, Path}

/**
 * 全局单调计数器（设计 §3.6，D-6 采纳）：seq = 计数器文件，**绝不数行**。
 *
 * - cap `agentd:4502-4506` 数行 ⇒ 并发重号/截断重号（R34）的反面：发号只出自计数器区间。
 * - 区间分配（D-6 工作默认 64/次）：一次领 `[n, n+interval)`，**先五步持久化**
 *   `n+interval` 再发号 ⇒ 崩溃至多弃用未用号——**只跳号不重号**。
 * - 交叉校验（open 期）：计数器必须 ≥ 各流尾 seq；不等 ⇒ 以大者为准 + WARN + 持久化。
 * - 计数器坏 / 缺但树有数据 ⇒ **拒启**（不得从 0 重号——triple §5.1「计数器不可重建」逐字沿用）。
 *
 * 文件形：`seq/counter.json` = `{"version":1,"next":<最低未领取号>}`（五步写）。
 */
final class SeqCounter private[db] (
  file: Path,
  interval: Long,
  private var nextUnclaimed: Long, // 已持久化的最低未领取号
  private var base: Long,          // 当前区间基（0 = 尚未领取区间）
  private var limit: Long,         // 当前区间上界（exclusive）
):

  require(interval >= 1, "seq interval must be >= 1")

  /**
   * 取下一个号。区间耗尽 ⇒ 先持久化新区间下界再发号（先落盘后发号 ⇒ 任何崩溃点
   * 都不可能重号）。仅在写闸内调用（[[nebflow.db.DbHandle]]）。
   */
  def next(): Long =
    if base == limit then claimInterval()
    val n = base
    base += 1
    n

  private def claimInterval(): Unit =
    val newNext = nextUnclaimed + interval
    // 先持久化（五步写），后变更内存面：写失败 ⇒ 异常上抛，区间未动，号未发。
    WriteFace.writeFiveStepText(file, SeqCounter.encode(newNext))
    base = nextUnclaimed
    limit = newNext
    nextUnclaimed = newNext

  /** 已持久化的最低未领取号（诊断/对拍/交叉校验面）。 */
  def persistedNext: Long = nextUnclaimed

  /**
   * 开机交叉校验（设计 §3.6）：本计数器必须 ≥ 各流已见最大 seq。`maxSeenSeq >= next`
   * ⇒ 计数器档落后（理论上五步序下不可能——防御性收口，如手工回档）：以大者为准，
   * 抬升并持久化，作废内存区间。返回是否发生了抬升（调用方 WARN）。
   */
  def reconcileWith(maxSeenSeq: Long): Boolean =
    if maxSeenSeq >= nextUnclaimed then
      nextUnclaimed = maxSeenSeq + 1
      base = 0L
      limit = 0L
      WriteFace.writeFiveStepText(file, SeqCounter.encode(nextUnclaimed))
      true
    else false

end SeqCounter

object SeqCounter:

  private val Version = 1

  /** open 判据（设计 §3.11 步 2）：缺+树有数据 ⇒ 拒启；坏 ⇒ 拒启；缺+全新 ⇒ 初始 next=1。 */
  def open(
    file: Path,
    treeHasData: Boolean,
    interval: Long
  ): Either[DbError.RefuseStart, SeqCounter] =
    if !Files.exists(file) then
      if treeHasData then
        Left(DbError.RefuseStart(
          s"seq counter MISSING at $file while the db tree carries data — refusing to start " +
            "(renumber-from-zero forbidden; design §3.6)"))
      else
        // 全新数据根：初始化权威面（首个区间仍是发号时才领——档先在、号后发）。
        WriteFace.writeFiveStepText(file, encode(1L))
        Right(new SeqCounter(file, interval, 1L, 0L, 0L))
    else
      decode(Files.readString(file)) match
        case Right(next) =>
          Right(new SeqCounter(file, interval, next, 0L, 0L))
        case Left(why) =>
          Left(DbError.RefuseStart(
            s"seq counter CORRUPT at $file ($why) — refusing to start (renumber-from-zero " +
              "forbidden; design §3.6)"))

  def encode(next: Long): String =
    (Json.obj(
      "version" -> Json.fromInt(Version),
      "next" -> Json.fromLong(next)
    ).noSpaces) + "\n"

  private def decode(text: String): Either[String, Long] =
    if text.trim.isEmpty then Left("empty")
    else
      parse(text) match
        case Left(e) => Left(s"parse: ${e.getMessage}")
        case Right(j) =>
          val c = j.hcursor
          c.downField("version").as[Int] match
            case Left(e) => Left(s"version: ${e.getMessage}")
            case Right(v) if v != Version => Left(s"unsupported version $v")
            case Right(_) =>
              c.downField("next").as[Long] match
                case Left(e) => Left(s"next: ${e.getMessage}")
                case Right(n) if n < 1L => Left(s"next must be >= 1 (got $n)")
                case Right(n) => Right(n)

end SeqCounter
