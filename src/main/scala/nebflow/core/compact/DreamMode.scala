package nebflow.core.compact

import cats.effect.IO
import nebflow.core.*
import nebflow.shared.*

/**
 * Dream mode — extract durable facts from idle conversation and update memory.
 *
 * 2026-08-31 memory-system redesign: merged-write model. Extracted facts are
 * merged into a STABLE `## Dream Extract` section organized by category —
 * never appended as a new timestamped section (the old append-only behavior
 * was the structural root cause of User.md's 54KB Dream Extract bloat: one
 * new section per compaction, never merged). The `## 使用模式` section is no
 * longer auto-rewritten (U11 noise source).
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

  private val FactPattern = """FACT\s*\d+\s*:\s*\[([A-Za-z_]+)\]\s*(.*)""".r

  /** Parse a single "FACT N: [CATEGORY] text" line → (category, text). */
  def parseFact(line: String): Option[(String, String)] =
    line.trim match
      case FactPattern(cat, text) if text.trim.nonEmpty => Some((cat.toUpperCase, text.trim))
      case _ => None

  /**
   * Merge extracted facts into the stable Dream Extract section, organized by
   * category. Exact-duplicate bullets are dropped — repeated extraction cannot
   * grow the section unboundedly. Does NOT rewrite `## 使用模式` anymore.
   */
  def updateMemory(facts: List[String]): IO[Unit] = IO.blocking {
    val memPath = PathUtil.dataRoot / "User.md"
    val existing = if os.exists(memPath) then os.read(memPath) else ""
    val parsed = facts.flatMap(parseFact)
    if parsed.nonEmpty then
      val updated = mergeFactsIntoSection(existing, parsed)
      if updated != existing then
        os.write.over(memPath, updated, createFolders = true)
        logger.info(s"Dream: merged ${parsed.size} facts into $memPath")
      else
        logger.info(s"Dream: no new facts to merge (all duplicates of ${parsed.size} extracted)")
  }.void

  /** Merge (category, text) facts into the stable Dream Extract section. */
  private def mergeFactsIntoSection(content: String, facts: List[(String, String)]): String =
    val byCat = facts.groupMap(_._1)(_._2)
    val headerIdx = indexOfSection(content, DreamSectionHeader)
    if headerIdx < 0 then
      // No stable section yet — append a fresh one at the end.
      content.trim + "\n\n" + renderSection(byCat) + "\n"
    else
      val (sectionStart, sectionEnd) = sectionBounds(content, headerIdx)
      val existingSection = content.substring(sectionStart, sectionEnd)
      val existingByCat = parseExistingSection(existingSection)
      // Merge: existing bullets first, then new facts (deduped).
      val mergedByCat = existingByCat.map { case (cat, bullets) =>
        cat -> (bullets ++ byCat.getOrElse(cat, Nil)).distinct
      } ++ (byCat -- existingByCat.keySet)
      content.substring(0, sectionStart) + renderSection(mergedByCat) + content.substring(sectionEnd)

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
