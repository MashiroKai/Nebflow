package nebflow.db

import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax.*

import java.nio.file.Path

/**
 * 阵隔与遗忘（设计 §4，D-9 采纳）——**纯函数面**（零 I/O、零时钟、零随机，可对拍）。
 *
 * - **双轨**：逻辑标记先行（遗忘 = tombstone，可回滚）+ 物化删除另立一等显式动词
 *   `purge`（高危、需显式触发，见 [[nebflow.db.DbHandle]].purgeTombstonedEvents；
 *   默认只跑逻辑轨）。后台物化 sweep 的接口 + 默认调度位 = [[Sweep]] /
 *   [[DefaultSweepIntervalMs]]。
 * - **两刀**：年龄刀 `maxAgeMs`（分域给值）+ 确定性采样刀
 *   `fnv1a64(unitId) % 10000 < ratio`（供体 `retention.rs:295-296` 形；确定性 = 同输入
 *   同保留集，可对拍）。
 * - **欠债豁免三域**〔提案，D-9〕：①审计轨迹（事件流 type ∈ audit/escalate/budget 族）
 *   ②待审单（`pending/` 整域）③失败取证（失败节点结果、压缩前后快照、全部
 *   `.corrupt-<ts>` 隔离档）。供体 `retention.rs:264-268` 的 scala 落地——供体自述其后端
 *   has_debt 恒 false，本侧落成**真判定**。
 * - 刀只作用于 db 树；tombstone 逻辑视图翻转可回滚且原文字节不变（V10）。
 */
object Retention:

  /** 采样刀基数（供体 `% 10000` 逐字）。 */
  val SampleDenominator: Long = 10000L

  /**
   * fnv1a64（供体 `retention.rs:150-159` 逐字同款：OFFSET_BASIS 起、**逐 UTF-8 字节**
   * xor-mul、wrapping——注意供体哈希的是字节不是字符，非 ASCII 输入同值）。判定时以
   * **无符号**取模（u64 语义），正值域确定性可对拍。
   */
  def fnv1a64(seed: String): Long =
    val OffsetBasis = 0xcbf29ce484222325L
    val Prime = 0x00000100000001b3L
    val bytes = seed.getBytes("UTF-8")
    var h = OffsetBasis
    var i = 0
    while i < bytes.length do
      h ^= bytes(i).toLong & 0xffL
      h = h * Prime // Long 乘法自带 wrapping（JLS 15.17.1）
      i += 1
    h

  /** 无符号 `h % 10000`（u64 语义折算；供体同值）。 */
  def sampleBucket(unitId: String): Long =
    java.lang.Long.remainderUnsigned(fnv1a64(unitId), SampleDenominator)

  /** 遗忘判定的候选单位域。豁免域③＝快照与隔离档（整域 Keep）；①②见 [[hasDebt]]。 */
  enum Domain:
    case EventStream, PendingReview, Snapshot, Quarantined

  /** 一条候选（unitId = 判定单位，事件面按 `subject+type` 分域派生——调用方给）。 */
  final case class Candidate(unitId: String, ts: Long, domain: Domain, eventType: String = "")

  /** 判定（四支，供体形：Keep 两因 + 两刀；采样刀施于年龄刀幸存者）。 */
  enum Decision:
    case KeepDebt       // 欠债豁免（两刀皆不适用——失败样本不可丢）
    case KeepSampled    // 采样刀命中保留（含 ratio=10000 恒留）
    case DropByAge      // 年龄刀
    case DropBySampling // 采样刀

  final case class Config(
    maxAgeMs: Option[Long],        // None = 年龄刀不生效
    sampleRatioPer10k: Long        // ≥10000 ⇒ 恒留；0 ⇒ 恒删（非欠债面）
  )

  /** 豁免族默认集（D-9 三域的字段级落点；可注入覆写）。 */
  val DefaultAuditFamily: Set[String] = Set("audit", "escalate", "budget")
  val DefaultFailureFamily: Set[String] = Set("failed")

  private def inFamily(eventType: String, family: Set[String]): Boolean =
    family.exists(f => eventType == f || eventType.startsWith(s"$f-") || eventType.endsWith(s"-$f"))

  /**
   * **欠债判定**（`has_debt ⇒ Keep`，两刀皆不适用）——三豁免域的真判定：
   *  - PendingReview / Snapshot / Quarantined：整域豁免（未闭合待审单、压缩取证、隔离档）；
   *  - EventStream：type ∈ audit/escalate/budget 族（域①）或 failed 族（域③失败取证）。
   */
  def hasDebt(c: Candidate, auditFamily: Set[String] = DefaultAuditFamily,
    failureFamily: Set[String] = DefaultFailureFamily): Boolean =
    c.domain match
      case Domain.PendingReview | Domain.Snapshot | Domain.Quarantined => true
      case Domain.EventStream =>
        inFamily(c.eventType, auditFamily) || inFamily(c.eventType, failureFamily)

  /**
   * 留存规划（纯函数两刀，刀序逐条可机械核对——供体 `retention.rs:276-302` 形）：
   *  0. 欠债 ⇒ KeepDebt（两刀皆不适用）；
   *  1. 年龄刀：`ts < now - maxAgeMs`（饱和减：now < maxAge ⇒ 阈值 0 ⇒ 本刀不生效）
   *     ⇒ DropByAge；maxAgeMs=None 本刀不生效；
   *  2. 采样刀（施于年龄刀幸存者）：`fnv1a64(unitId) % 10000 < ratio` ⇒ KeepSampled，
   *     否则 DropBySampling（≥10000 ⇒ 恒留、0 ⇒ 恒删——供体同句）。
   */
  def plan(candidates: List[Candidate], cfg: Config, nowMs: Long): List[Decision] =
    val ageThreshold = cfg.maxAgeMs.map(m => math.max(0L, nowMs - math.max(0L, m)))
    candidates.map { c =>
      if hasDebt(c) then Decision.KeepDebt
      else if ageThreshold.exists(c.ts < _) then Decision.DropByAge
      else if cfg.sampleRatioPer10k >= SampleDenominator
        || sampleBucket(c.unitId) < cfg.sampleRatioPer10k then Decision.KeepSampled
      else Decision.DropBySampling
    }

  // ── tombstone（逻辑标记轨；可回滚；原文字节不动）──────────────────────────

  /** 一条逻辑标记行（events.tombstones.jsonl 的一行；存储面自有，不动事件原文）。 */
  final case class TombstoneRow(unitId: String, at: Long, revived: Boolean)

  object TombstoneRow:
    given Encoder[TombstoneRow] = r =>
      Json.obj(
        "unitId" -> Json.fromString(r.unitId),
        "at" -> Json.fromLong(r.at),
        "revived" -> Json.fromBoolean(r.revived)
      )
    given Decoder[TombstoneRow] = c =>
      for
        unitId <- c.downField("unitId").as[String]
        at <- c.downField("at").as[Long]
        revived <- c.downField("revived").as[Boolean]
      yield TombstoneRow(unitId, at, revived)

  /**
   * 遗忘集（逻辑视图的死面）：同 unitId **最后一行**定生死——revived=false 的 tombstone
   * ⇒ 已遗忘（逻辑视图过滤其行；revive 行回滚遗忘）。命名口径：forgotten = 逻辑死者。
   */
  def forgottenUnits(rows: List[TombstoneRow]): Set[String] =
    rows.foldLeft(Map.empty[String, TombstoneRow]) { (acc, r) =>
      acc.updated(r.unitId, r)
    }.collect { case (id, r) if !r.revived => id }.toSet

  // ── 后台物化 sweep：接口 + 默认调度位（D-9 最小面）─────────────────────────

  /** 一轮 sweep 的纯计数报告（算术恒等：scanned == kept + dropped）。 */
  final case class SweepReport(
    scanned: Long, keepDebt: Long, keepSampled: Long,
    dropByAge: Long, dropBySampling: Long, tombstonesAppended: Long
  ):
    def kept: Long = keepDebt + keepSampled
    def dropped: Long = dropByAge + dropBySampling

  /** 物化 sweep 接口（默认调度位见 [[DefaultSweepIntervalMs]]；接线期由宿主定时器挂）。 */
  trait Sweep:
    def sweep(nowMs: Long): cats.effect.IO[SweepReport]

  /** 默认调度位：每日一次（接线期把 DbHandle.retentionSweep 挂上宿主的定时面即可）。 */
  val DefaultSweepIntervalMs: Long = 24L * 3600 * 1000

end Retention
