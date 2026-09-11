package nebflow.core.compact

import cats.effect.IO
import io.circe.syntax.*
import nebflow.core.*
import nebflow.service.MemoryBudget
import nebflow.shared.*

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Dream mode — extract durable facts from idle conversation and update memory.
 *
 * 2026-08-31 memory-system redesign: merged-write model. Extracted facts are
 * merged into a STABLE `## Dream Extract` section organized by category —
 * never appended as a new timestamped section (the old append-only behavior
 * was the structural root cause of User.md's 54KB Dream Extract bloat: one
 * new section per compaction, never merged). The `## 使用模式` section is no
 * longer auto-rewritten (U11 noise source).
 *
 * 2026-09-05 memory-management batch 2 (plan §2.1 T3 / §6.2-2.4): the stable
 * section now carries a LIFECYCLE. T3 entries (hook-extracted, never explicitly
 * promoted by Nebula) are evicted at merge-write time:
 *   - TTL: unpromoted entries older than TtlDays (14) are dropped;
 *   - FIFO: at most MaxEntries (60) unpromoted entries are kept — oldest out;
 *   - promotion exemption: an entry whose text already appears verbatim in the
 *     rest of User.md (i.e. it was promoted into a permanent section) is
 *     exempt from both TTL and FIFO — the mechanism never auto-deletes items
 *     Nebula has marked.
 * Entry age lives in a sidecar JSON (`memory/.dream-timestamps.json`,
 * sha256(text) → firstSeenMs) so the injected entry format stays unchanged;
 * entries missing from the sidecar are treated as age-unknown and are NOT
 * evicted (conservative: never guess-delete on missing data — their clock
 * starts now).
 *
 * Same batch (§6.2-2.2): DreamMode shares the MemoryBudget write-side gate —
 * if the merged file would exceed the User.md hard budget the merge is
 * SKIPPED with a WARN log (the hook must not bypass the budget the MemoryEdit
 * tool enforces).
 *
 * dream-agent batch (2026-09-05): snapshot-before-write guard — the hook's save
 * path goes through MemorySnapshot.snapshotBeforeWrite like every MemoryEdit
 * action (User.md is outside the ~/.nebflow git tracking layer; the pre-write
 * snapshot is the only fine-grained rollback anchor). Snapshot failure skips
 * the merge (SnapshotBlocked) — the hook must not write unbacked.
 */
object DreamMode:
  private val logger = NebflowLogger.forName("nebflow.dream")

  val DreamPrompt: String =
    """<system-reminder>
      |Dream mode — the user is idle. Reflect on the conversation since the
      |last dream and extract durable facts worth remembering permanently.
      |Your response must contain exactly two blocks:
      |
      |1. <analysis> — your reasoning about what's durable vs ephemeral
      |2. <facts>
      |FACT 1: [CATEGORY] [fact description]
      |FACT 2: [CATEGORY] [fact description]
      |...
      |</facts>
      |
      |Categories: USER_PREFERENCE, ROUTING_RULE, ENVIRONMENT, PATTERN, DECISION
      |Rules:
      | - Only extract facts that will matter in FUTURE sessions
      | - Skip task-specific details ("user asked about file X")
      | - If the user communicated in Chinese, write facts in Chinese
      | - If nothing durable, output empty <facts></facts>
      |</system-reminder>""".stripMargin

  /** Parse <facts> block from LLM response → list of raw fact lines. */
  def parseResponse(text: String): List[String] =
    "(?s)<facts>(.*?)</facts>".r.findFirstMatchIn(text) match
      case Some(m) =>
        m.group(1)
          .trim
          .linesIterator
          .map(_.trim)
          .filter(_.startsWith("FACT"))
          .toList
      case None => Nil

  /** Stable header of the merged fact section — NO timestamp, so repeated
    * extraction merges into the same section instead of appending new ones.
    * (The old timestamped headers `## Dream Extract (2026-…)` are matched by
    * P0 cleanup; this exact header is what the hook maintains.) */
  val DreamSectionHeader = "## Dream Extract"

  /** Category display order under the dream section. */
  val CategoryOrder = List("USER_PREFERENCE", "ROUTING_RULE", "ENVIRONMENT", "PATTERN", "DECISION")

  // ---------------------------------------------------------------
  // T3 lifecycle constants（plan §2.1 钉死值：M=14 天 + 60 条 FIFO）
  // ---------------------------------------------------------------

  /** 未晋升条目 TTL：M = 14 天（毫秒）。 */
  val TtlMs: Long = 14L * 24 * 60 * 60 * 1000

  /** 稳定节未晋升条目上限（FIFO，新进旧出）。 */
  val MaxEntries: Int = 60

  /** Sidecar：条目 sha256 → firstSeen epoch ms（条目格式零侵入）。 */
  def timestampsPath: os.Path = PathUtil.dataRoot / "memory" / ".dream-timestamps.json"

  private val FactPattern = """FACT\s*\d+\s*:\s*\[([A-Za-z_]+)\]\s*(.*)""".r

  /** Parse a single "FACT N: [CATEGORY] text" line → (category, text). */
  def parseFact(line: String): Option[(String, String)] =
    line.trim match
      case FactPattern(cat, text) if text.trim.nonEmpty => Some((cat.toUpperCase, text.trim))
      case _ => None

  /** sha256 of the normalized entry text — sidecar key (rename-stable). */
  def entryHash(text: String): String =
    MessageDigest.getInstance("SHA-256")
      .digest(text.trim.getBytes(StandardCharsets.UTF_8))
      .map("%02x".format(_))
      .mkString

  /** Merge outcome — spec-visible summary of what updateMemory did. */
  enum MergeResult:
    /** Written: final file size, evicted entry count (TTL+FIFO), added count. */
    case Merged(newBytes: Long, evicted: Int, added: Int)
    /** Over User.md hard budget — merge SKIPPED (file untouched), WARN logged. */
    case BudgetBlocked(newBytes: Long)
    /** Pre-write snapshot failed — merge SKIPPED fail-closed (nothing written). */
    case SnapshotBlocked(reason: String)
    /** Nothing to do (no parseable facts / merge is a no-op). */
    case Noop

  // ---------------------------------------------------------------
  // Write path
  // ---------------------------------------------------------------

  /**
   * Merge extracted facts into the stable Dream Extract section, organized by
   * category. Exact-duplicate bullets are dropped; T3 lifecycle eviction runs
   * at merge time (see object doc); the MemoryBudget gate guards the result.
   *
   * **SUPERSEDED（2026-09-12 记忆改造批 / spec §5 R7(4) O-A）——生产链路已关闭**：
   * `NebulaMemoryHook` 不再调用本方法（facts 改走 `MemoryQueue` 入队
   * `note{target:"user", action:"append", source.trigger:"dream"}`，见
   * `NebulaMemoryHook.enqueueFacts`）；`~/.nebflow/User.md` 的直写通道（spec §3.1 W2）
   * 由此关闭。本方法与其纯函数合并核（`mergeFactsIntoSection` / `t3Evolve` /
   * `promotedTexts`）**保留为 T3 生命周期的参考实现与 spec 直测面**，但**没有生产
   * 调用点**——不要重新接回：那是队列化的绕过通道。退役与否留给独立收敛批裁定。
   */
  def updateMemory(facts: List[String]): IO[MergeResult] = IO.blocking {
    val memPath = PathUtil.dataRoot / "User.md"
    val existing = if os.exists(memPath) then os.read(memPath) else ""
    val parsed = facts.flatMap(parseFact)
    if parsed.isEmpty then MergeResult.Noop
    else
      val sidecar = readTimestamps()
      val merged = mergeFactsIntoSection(existing, parsed, sidecar, nowMs())
      if merged.content == existing then
        logger.info(s"Dream: no new facts to merge (all duplicates of ${parsed.size} extracted)")
        MergeResult.Noop
      else
        val newBytes = merged.content.getBytes(StandardCharsets.UTF_8).length.toLong
        // 预算闸（§6.2-2.2）：hook 写面与 MemoryEdit 同判据——超硬顶跳过合并并
        // WARN，不落盘。整理通道（MemoryEdit replace_section/remove）不受此闸，
        // 超限后仍有收缩路径。
        MemoryBudget.verdict("user", newBytes) match
          case MemoryBudget.Exceeded(_, hard) =>
            logger.warn(
              s"Dream: merge SKIPPED — User.md would reach $newBytes bytes (hard budget $hard). " +
                "Consolidate memory first; facts not written. (MEMORYEDIT_BUDGET)")
            MergeResult.BudgetBlocked(newBytes)
          case _ =>
            // 快照先行（dream-agent 批）：写前备份磁盘真身；失败 → fail-closed
            // 跳过合并（hook 不写无备份的覆盖）。快照读的是磁盘当前态，不吃行缓存。
            nebflow.service.MemorySnapshot.snapshotBeforeWrite(memPath) match
              case Left(reason) =>
                logger.warn(s"Dream: merge SKIPPED — pre-write snapshot failed ($reason). Nothing written. (MEMORYEDIT_SNAPSHOT)")
                MergeResult.SnapshotBlocked(reason)
              case Right(_) =>
                os.write.over(memPath, merged.content, createFolders = true)
                writeTimestamps(merged.timestamps)
                logger.info(
                  s"Dream: merged ${merged.added} facts into $memPath " +
                    s"(T3 evicted ${merged.evicted.size}: ttl=${merged.evictedTtl} fifo=${merged.evictedFifo}; section now ${merged.entryCount} entries, $newBytes bytes)")
                MergeResult.Merged(newBytes, merged.evicted.size, merged.added)
  }

  private def nowMs(): Long = System.currentTimeMillis()

  // ---------------------------------------------------------------
  // Pure merge core（spec 直测面；IO 薄壳仅读写文件）
  // ---------------------------------------------------------------

  /** 合并计划：新全文 + 应写回的 sidecar + 计数。 */
  final case class MergedPlan(
    content: String,
    timestamps: Map[String, Long],
    added: Int,
    evicted: Vector[String],
    evictedTtl: Int,
    evictedFifo: Int,
    entryCount: Int
  )

  /**
   * 纯函数合并：现有全文 + 解析后 facts + sidecar 时钟 + 当前时刻 → MergedPlan。
   *  - 现存稳定节条目先过 T3 淘汰（t3Evolve）；
   *  - 新 facts 按 category 合并、exact-dedup（对保留条目与新条目全体去重）；
   *  - 新条目记 firstSeen=now；保留条目时钟原样；淘汰条目从 sidecar GC。
   */
  def mergeFactsIntoSection(
    content: String,
    facts: List[(String, String)],
    sidecar: Map[String, Long],
    now: Long
  ): MergedPlan =
    val byCat = facts.groupMap(_._1)(_._2)
    val headerIdx = indexOfSection(content, DreamSectionHeader)
    if headerIdx < 0 then
      // No stable section yet — append a fresh one at the end.
      val fresh = byCat.values.flatten.toList
      val rendered = content.trim + "\n\n" + renderSection(byCat) + "\n"
      val ts = fresh.map(t => entryHash(t) -> now).toMap
      MergedPlan(rendered, ts ++ sidecar, fresh.size, Vector.empty, 0, 0, fresh.size)
    else
      val (sectionStart, sectionEnd) = sectionBounds(content, headerIdx)
      val existingSection = content.substring(sectionStart, sectionEnd)
      val existingByCat = parseExistingSection(existingSection)
      val existingEntries = existingByCat.toList.flatMap((cat, bullets) => bullets.map(b => (cat, b))).toVector
      // 晋升豁免集：条目文本（规范化）出现在 Dream 节之外的正文其余部分。
      val rest = content.substring(0, sectionStart) + content.substring(sectionEnd)
      val promoted = promotedTexts(existingEntries.map(_._2), rest)
      // Sidecar 时钟：缺失 = 年龄未知 → 记 now（免淘保守语义，从此起算）。
      val clocked = existingEntries.map((cat, b) =>
        (cat, b, sidecar.getOrElse(entryHash(b), now)))
      val (kept, (evictedTtl, evictedFifo)) = t3Evolve(clocked, promoted, now)
      val keptTexts = kept.map(_._2).toSet
      // 合并：各 category 内 = 保留旧条目 ++ 新 facts（distinct）
      val mergedByCat0 = kept.map(e => e._1 -> e._2).groupMap(_._1)(_._2).map((k, v) => k -> v.toList)
      val mergedByCat = (mergedByCat0.keySet ++ byCat.keySet).toList.map { cat =>
        cat -> ((mergedByCat0.getOrElse(cat, Nil) ++ byCat.getOrElse(cat, Nil)).distinct)
      }.toMap
      val rendered = content.substring(0, sectionStart) + renderSection(mergedByCat) + content.substring(sectionEnd)
      // 新增条目 = 合并后全集中既有 kept 之外的（exact-dedup 后真正新进的）
      val allAfter = mergedByCat.values.flatten.toList
      val addedTexts = allAfter.filterNot(keptTexts.contains).distinct
      val ts =
        kept.map((_, b, seen) => entryHash(b) -> seen).toMap ++
          addedTexts.map(t => entryHash(t) -> now).toMap
      // sidecar GC 由调用方约束在写回集合内（ts 已只含现存条目）
      MergedPlan(
        rendered,
        ts,
        addedTexts.size,
        evictedTtl.map(_._2) ++ evictedFifo.map(_._2),
        evictedTtl.size,
        evictedFifo.size,
        kept.size + addedTexts.size
      )
    end if
  end mergeFactsIntoSection

  /** 晋升判定：条目文本（trim 规范化）在正文其余部分精确出现 → 已晋升。
    * 晋升时改写措辞 → 视为未晋升（按 TTL 淘——正文改写版已承载事实，无损失）。 */
  def promotedTexts(entryTexts: Seq[String], restOfContent: String): Set[String] =
    val rest = restOfContent.trim
    entryTexts.filter(t => rest.contains(t.trim)).map(_.trim).toSet

  /**
   * T3 淘汰核心（纯函数）。clocked: (category, text, firstSeenMs) 保序。
   *  - 晋升豁免域：不参与 TTL/FIFO，全保留；
   *  - 未晋升域：firstSeen 距今 > TtlMs → TTL 淘；余下按 firstSeen 【最新优先】
   *    保留 MaxEntries 条（新进旧出），最老的超出部分 FIFO 淘；
   * 返回 (保留（原顺序），(TTL 淘汰， FIFO 淘汰))。
   */
  def t3Evolve(
    clocked: Vector[(String, String, Long)],
    promoted: Set[String],
    now: Long
  ): (Vector[(String, String, Long)], (Vector[(String, String, Long)], Vector[(String, String, Long)])) =
    val (exempt, unpromoted) = clocked.partition((_, b, _) => promoted.contains(b.trim))
    val (fresh, expired) = unpromoted.partition((_, _, seen) => now - seen <= TtlMs)
    val newestFirst = fresh.sortBy(-_._3)
    val (fifoKept, fifoEvicted) = newestFirst.splitAt(MaxEntries)
    val keptSet = fifoKept.toSet
    val kept = clocked.filter(e => exempt.contains(e) || keptSet.contains(e))
    (kept, (expired, fifoEvicted))

  // ---------------------------------------------------------------
  // Sidecar IO（mtime-free：小 JSON 全量读写；读失败 → 空 = 免淘保守语义）
  // ---------------------------------------------------------------

  private def readTimestamps(): Map[String, Long] =
    try
      if !os.exists(timestampsPath) then Map.empty
      else
        io.circe.parser.decode[Map[String, Long]](os.read(timestampsPath)) match
          case Right(m) => m
          case Left(_)  => Map.empty
    catch case _: Exception => Map.empty

  private def writeTimestamps(ts: Map[String, Long]): Unit =
    if ts.nonEmpty then
      os.makeDir.all(timestampsPath / os.up)
      os.write.over(timestampsPath, ts.asJson.noSpaces, createFolders = true)

  // ---------------------------------------------------------------
  // Section parsing/rendering（2026-08-31 语义不变）
  // ---------------------------------------------------------------

  /** Byte index of the section header line, or -1. */
  private def indexOfSection(content: String, header: String): Int =
    val lines = content.linesIterator.toVector
    lines.indexWhere(_.trim == header) match
      case -1 => -1
      case i =>
        lines.take(i).foldLeft(0)((acc, l) => acc + l.length + 1)

  /** (start, end) of the section starting at headerIdx — up to the next `## ` or EOF. */
  private def sectionBounds(content: String, headerIdx: Int): (Int, Int) =
    val nextHeader = content.indexOf("\n## ", headerIdx + 1)
    val end = if nextHeader >= 0 then nextHeader else content.length
    (headerIdx, end)

  /** Parse an existing dream section into category → bullets map. */
  private def parseExistingSection(section: String): Map[String, List[String]] =
    var current: String = ""
    val out = scala.collection.mutable.Map.empty[String, List[String]]
    section.linesIterator.foreach { line =>
      val trimmed = line.trim
      if trimmed.startsWith("### ") then
        current = trimmed.drop(4).trim.toUpperCase
        out.getOrElseUpdate(current, Nil)
      else if trimmed.startsWith("- ") && current.nonEmpty then
        out.update(current, out(current) :+ trimmed.drop(2).trim)
      // non-bullet lines (headers, blanks) are ignored — rebuild is canonical
    }
    out.toMap

  /** Render the dream section with per-category sub-sections in CategoryOrder. */
  private def renderSection(byCat: Map[String, List[String]]): String =
    val cats =
      (CategoryOrder.filter(byCat.contains) ++ byCat.keys.toList.filterNot(CategoryOrder.contains)).distinct
    val sb = new StringBuilder
    sb.append(DreamSectionHeader).append("\n\n")
    cats.foreach { cat =>
      sb.append("### ").append(cat).append("\n")
      byCat(cat).foreach(bullet => sb.append("- ").append(bullet).append("\n"))
      sb.append("\n")
    }
    sb.toString.trim + "\n"

end DreamMode
