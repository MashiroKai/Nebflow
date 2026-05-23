package nebflow.core.compact

import cats.effect.IO
import io.circe.syntax.*
import nebflow.shared.*
import nebflow.shared.given

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId}

/** Result of archiving a compaction — paths to generated files. */
case class CompactionArchive(
  sessionDir: String,
  reportPath: String,
  beforeJsonPath: String,
  afterJsonPath: String
)

trait HistoryArchiver:

  /**
   * Archive a compaction run. Writes:
   *   - report.md   : human-readable debug report
   *   - before.json : raw messages before compaction
   *   - after.json  : raw messages after compaction
   *
   * Returns Right(archive) on success; callers must treat failure as non-blocking.
   */
  def archiveCompaction(
    sessionId: String,
    sessionName: Option[String],
    agentName: String,
    before: List[Message],
    after: List[Message],
    mode: String,
    extra: Map[String, String] = Map.empty
  ): IO[Either[String, CompactionArchive]]

end HistoryArchiver

object HistoryArchiver:

  def fileSystem(root: os.Path): HistoryArchiver = new:

    def archiveCompaction(
      sessionId: String,
      sessionName: Option[String],
      agentName: String,
      before: List[Message],
      after: List[Message],
      mode: String,
      extra: Map[String, String]
    ): IO[Either[String, CompactionArchive]] = IO.blocking {
      try
        val shortSid = sessionId.take(8)
        val now = Instant.now()
        val ts = formatTimestamp(now)
        val dir = root / "archives" / shortSid
        os.makeDir.all(dir)

        val prefix = s"$ts"
        val reportFile = dir / s"$prefix-report.md"
        val beforeFile = dir / s"$prefix-before.json"
        val afterFile = dir / s"$prefix-after.json"

        // --- 1. Raw JSON dumps ---
        os.write.over(beforeFile, before.asJson.spaces2)
        os.write.over(afterFile, after.asJson.spaces2)

        // --- 2. Human-readable report ---
        val report = buildReport(
          sessionId,
          sessionName,
          agentName,
          now,
          mode,
          before,
          after,
          extra
        )
        os.write.over(reportFile, report)

        Right(
          CompactionArchive(
            sessionDir = dir.toString,
            reportPath = reportFile.toString,
            beforeJsonPath = beforeFile.toString,
            afterJsonPath = afterFile.toString
          )
        )
      catch case e: Throwable => Left(e.getMessage)
    }

  // ------------------------------------------------------------------
  // Report builder
  // ------------------------------------------------------------------

  private val ReportTimeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault())
  private val FileTimeFmt = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())

  private def formatTimestamp(i: Instant): String = FileTimeFmt.format(i)
  private def formatReportTime(i: Instant): String = ReportTimeFmt.format(i)

  private def buildReport(
    sessionId: String,
    sessionName: Option[String],
    agentName: String,
    time: Instant,
    mode: String,
    before: List[Message],
    after: List[Message],
    extra: Map[String, String]
  ): String =
    val shortSid = sessionId.take(8)
    val sname = sessionName.getOrElse("-")

    val beforeStats = messageStats(before)
    val afterStats = messageStats(after)

    val preserved = extra.get("preservedRounds").map(v => s"**Preserved rounds:** $v\n").getOrElse("")
    val extraLines = extra.filter(_._1 != "preservedRounds").map { (k, v) => s"- **$k:** $v" }.mkString("\n")

    s"""# Context Compaction Report
       |
       |## Metadata
       |
       || Field | Value |
       ||-------|-------|
       || Session | $sname (`$shortSid`) |
       || Agent | $agentName |
       || Time | ${formatReportTime(time)} |
       || Mode | $mode |
       || Before messages | ${before.size} |
       || After messages | ${after.size} |
       || Delta | ${before.size - after.size} (${
        if before.size > 0 then ((before.size - after.size) * 100 / before.size) else 0
      }%) |
       |
       |$preserved${if extraLines.nonEmpty then extraLines + "\n" else ""}
       |## Stats
       |
       || Metric | Before | After |
       ||--------|--------|-------|
       || User msgs | ${beforeStats.userCount} | ${afterStats.userCount} |
       || Assistant msgs | ${beforeStats.assistantCount} | ${afterStats.assistantCount} |
       || System msgs | ${beforeStats.systemCount} | ${afterStats.systemCount} |
       || Tool results | ${beforeStats.toolResultCount} | ${afterStats.toolResultCount} |
       || Tool uses | ${beforeStats.toolUseCount} | ${afterStats.toolUseCount} |
       || Total chars | ${beforeStats.totalChars} | ${afterStats.totalChars} |
       |
       |---
       |
       |## Before (${before.size} messages)
       |
       |```
       |${messagesToText(before)}
       |```
       |
       |---
       |
       |## After (${after.size} messages)
       |
       |```
       |${messagesToText(after)}
       |```
       |""".stripMargin
  end buildReport

  // ------------------------------------------------------------------
  // Message statistics
  // ------------------------------------------------------------------

  private case class MsgStats(
    userCount: Int,
    assistantCount: Int,
    systemCount: Int,
    toolResultCount: Int,
    toolUseCount: Int,
    totalChars: Int
  )

  private def messageStats(messages: List[Message]): MsgStats =
    var userCount = 0
    var assistantCount = 0
    var systemCount = 0
    var toolResultCount = 0
    var toolUseCount = 0
    var totalChars = 0

    for msg <- messages do
      msg.role match
        case MessageRole.User => userCount += 1
        case MessageRole.Assistant => assistantCount += 1
        case MessageRole.System => systemCount += 1

      msg.content match
        case Left(text) => totalChars += text.length
        case Right(blocks) =>
          for block <- blocks do
            block match
              case ContentBlock.Text(t) => totalChars += t.length
              case ContentBlock.ToolResult(_, content, _) =>
                toolResultCount += 1
                totalChars += content.length
              case ContentBlock.ToolUse(_, _, input) =>
                toolUseCount += 1
                totalChars += input.asJson.noSpaces.length
              case ContentBlock.Thinking(t, _) => totalChars += t.length
              case ContentBlock.Image(_, _) => totalChars += 100
    end for

    MsgStats(userCount, assistantCount, systemCount, toolResultCount, toolUseCount, totalChars)
  end messageStats

  // ------------------------------------------------------------------
  // Human-readable message rendering
  // ------------------------------------------------------------------

  private val ToolResultMaxChars = 500

  private def messagesToText(messages: List[Message]): String =
    val sb = new StringBuilder()
    for (msg, i) <- messages.zipWithIndex do
      val roleLabel = msg.role match
        case MessageRole.User => "User"
        case MessageRole.Assistant => "Assistant"
        case MessageRole.System => "System"
      sb.append(s"\n[$i] --- $roleLabel ---\n")
      sb.append(messageToText(msg))
      sb.append("\n")
    end for
    sb.toString
  end messagesToText

  private def messageToText(msg: Message): String =
    msg.content match
      case Left(text) => text
      case Right(blocks) =>
        val sb = new StringBuilder()
        for block <- blocks do
          block match
            case ContentBlock.Text(text) =>
              sb.append(text).append("\n")
            case ContentBlock.Image(_, mediaType) =>
              sb.append(s"[image: $mediaType]\n")
            case ContentBlock.ToolUse(id, name, input) =>
              val inputJson = input.asJson.noSpaces
              val display =
                if inputJson.length > 300 then inputJson.take(300) + s" ... [${inputJson.length - 300} more chars]"
                else inputJson
              sb.append(s"[ToolUse: $name id=$id] $display\n")
            case ContentBlock.ToolResult(toolUseId, content, isError) =>
              val prefix = if isError.contains(true) then "[ToolResult ERROR" else "[ToolResult"
              val display =
                if content.length > ToolResultMaxChars then
                  content.take(ToolResultMaxChars) + s"\n... [${content.length - ToolResultMaxChars} more chars]"
                else content
              sb.append(s"$prefix id=$toolUseId]\n$display\n")
            case ContentBlock.Thinking(thinking, _) =>
              sb.append(s"[Thinking]\n$thinking\n")
        end for
        sb.toString
  end messageToText

end HistoryArchiver
