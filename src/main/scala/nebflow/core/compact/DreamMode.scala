package nebflow.core.compact

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.*
import nebflow.shared.*

/** Dream mode — extract durable facts from idle conversation and update memory. */
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

  /** Parse <facts> block from LLM response → list of fact strings. */
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

  /** Append extracted facts to ~/.nebflow/NEBFLOW.md and update usage pattern section. */
  def updateMemory(facts: List[String], pattern: UsagePattern): IO[Unit] = IO.blocking {
    val memPath = PathUtil.dataRoot / "NEBFLOW.md"
    val existing = if os.exists(memPath) then os.read(memPath) else ""

    val factsSection = if facts.nonEmpty then
      val now = java.time.Instant.now().toString
      val formatted = facts.map(f => s"- $f").mkString("\n")
      s"\n\n## Dream Extract ($now)\n$formatted\n"
    else ""

    val patternSection = buildUsagePatternSection(pattern)
    val updated = updateOrAppendSection(existing, "## 使用模式", patternSection) + factsSection
    os.write.over(memPath, updated, createFolders = true)
    logger.info(s"Dream: updated memory with ${facts.size} facts")
  }.void

  private def buildUsagePatternSection(pattern: UsagePattern): String =
    val zdt = java.time.ZonedDateTime.now()
    val dateStr =
      s"${zdt.getYear}-${"%02d".format(zdt.getMonthValue)}-${"%02d".format(zdt.getDayOfMonth)} ${zdt.getHour}:${"%02d".format(zdt.getMinute)}"
    val activeHours = pattern.hourlyActivity.zipWithIndex
      .filter(_._1 > 0.3)
      .map(_._2)
      .toList
    val idleHours = pattern.hourlyActivity.zipWithIndex
      .filter(_._1 < 0.2)
      .map(_._2)
      .toList
    def fmtHour(h: Int): String = s"${"%02d".format(h)}:00"
    s"""## 使用模式
       |- 活跃时段：${if activeHours.nonEmpty then activeHours.map(fmtHour).mkString(", ") else "数据不足"}
       |- 通常空闲：${if idleHours.nonEmpty then idleHours.map(fmtHour).mkString(", ") else "数据不足"}
       |- 上次分析：$dateStr（基于 ${pattern.totalRecords} 条记录）
       |""".stripMargin

  end buildUsagePatternSection

  /** Replace existing section (starting with header) or append new one. */
  private def updateOrAppendSection(content: String, header: String, newSection: String): String =
    val headerIdx = content.indexOf(header)
    if headerIdx >= 0 then
      // Find next section (## ) or end of content
      val afterHeader = content.indexOf("\n## ", headerIdx + header.length)
      val sectionEnd = if afterHeader >= 0 then afterHeader else content.length
      content.substring(0, headerIdx) + newSection + content.substring(sectionEnd)
    else content + "\n\n" + newSection

  /** Check if dream should trigger based on 5 conditions. */
  def shouldTriggerDream(
    now: Long,
    pattern: UsagePattern,
    lastActivity: Long,
    messageCount: Int,
    lastDreamAt: Option[Long],
    lastDreamMessageCount: Int
  ): Boolean =
    val newMessages = messageCount - lastDreamMessageCount
    val hasEnoughMaterial = newMessages >= 30
    val notTooRecent = lastDreamAt.forall(now - _ > 30 * 60_000L)
    val inWindow =
      if pattern.isReliable then pattern.isInIdleWindow(now)
      else (now - lastActivity) > 30 * 60_000L
    hasEnoughMaterial && notTooRecent && inWindow
  end shouldTriggerDream

end DreamMode
