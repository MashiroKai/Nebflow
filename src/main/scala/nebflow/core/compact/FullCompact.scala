package nebflow.core.compact

import cats.effect.IO
import io.circe.syntax.*
import nebflow.core.NebflowLogger
import nebflow.shared.*
import nebflow.shared.given

object FullCompact:

  private val logger = NebflowLogger.forName("nebflow.compact")

  /**
   * Parse LLM compaction response into a compacted message list.
   *
   * Expected format:
   *   <analysis>...drafting...</analysis>
   *   <summary>
   *   1. Primary Request: ...
   *   2. Key Concepts: ...
   *   ...
   *   </summary>
   *   <files>
   *   path/to/file1
   *   path/to/file2
   *   </files>
   */
  def parseResponse(
    text: String,
    originalMessages: List[Message],
    projectRoot: String = ""
  ): Either[String, List[Message]] =
    if text.isEmpty then Left("SubAgent returned empty response")
    else
      // 1. Strip <analysis> block (drafting scratchpad)
      val withoutAnalysis = stripAnalysis(text)

      // 2. Extract <summary> content
      val summaryText = extractSummary(withoutAnalysis)

      // 3. Extract <files> list
      val (cleanSummary, filePaths) = extractFiles(withoutAnalysis)

      // 4. Build file restore content
      val fileRestoreSection = buildFileRestoreSection(filePaths, projectRoot)

      // 5. Assemble compact message
      val message = Message(
        MessageRole.User,
        Left(
          s"<context-compact mode=\"full\">Compressed ${originalMessages.size} messages.\n\n" +
            cleanSummary +
            "\n\nIf you need specific details from before compaction (like exact code snippets, " +
            "error messages, or content you generated), use Read to check the original files." +
            fileRestoreSection +
            "\n</context-compact>"
        )
      )
      Right(List(message))
  end parseResponse

  /**
   * Strip <analysis>...</analysis> block — it's a drafting scratchpad that
   * improves summary quality but has no value in the final context.
   */
  private def stripAnalysis(text: String): String =
    val result = text.replaceFirst("(?s)<analysis>.*?</analysis>\\s*", "")
    if result.trim.isEmpty then text else result

  /**
   * Extract content from <summary>...</summary> tags.
   * Falls back to full text if no summary tags found.
   */
  private def extractSummary(text: String): String =
    "(?s)<summary>(.*?)</summary>".r.findFirstMatchIn(text) match
      case Some(m) => m.group(1).trim
      case None => text.trim

  /** Extract <files> tag — returns (textWithoutFilesTag, filePaths). */
  private def extractFiles(text: String): (String, List[String]) =
    val startTag = "<files>"
    val endTag = "</files>"
    val startIdx = text.indexOf(startTag)
    val endIdx = text.indexOf(endTag)

    if startIdx < 0 || endIdx < 0 || endIdx <= startIdx then (text.trim, Nil)
    else
      val filesBlock = text.substring(startIdx + startTag.length, endIdx)
      val paths = filesBlock.linesIterator
        .map(_.trim)
        .filter(_.nonEmpty)
        .take(5)
        .toList
      val clean = (text.substring(0, startIdx) + text.substring(endIdx + endTag.length)).trim
      (clean, paths)

  /**
   * Read files and build restore content section.
   * Each file truncated to postCompactMaxCharsPerFile.
   */
  private def buildFileRestoreSection(paths: List[String], projectRoot: String): String =
    if paths.isEmpty then ""
    else
      val config = CompactConfig()
      val absolutePaths = paths
        .map { p =>
          if p.startsWith("/") then p else s"$projectRoot/$p"
        }
        .filter(isWithinProject(_, projectRoot))

      if absolutePaths.isEmpty then ""
      else
        val sb = new StringBuilder("\n\nRestored files after compaction:\n")
        var usedChars = 0
        val budget = config.postCompactTokenBudget * 4 // rough chars
        for
          path <- absolutePaths
          if usedChars < budget
        do
          val content = readFileContent(path, config.postCompactMaxCharsPerFile)
          if content.nonEmpty then
            val section = s"\n### `$path`\n```\n$content\n```\n"
            if usedChars + section.length <= budget then
              sb.append(section)
              usedChars += section.length
        sb.toString

  /** Read file content, truncate to maxChars. Returns empty string on failure. */
  private def readFileContent(path: String, maxChars: Int): String =
    try
      val file = java.nio.file.Paths.get(path.replaceFirst("^~", sys.props("user.home")))
      if !java.nio.file.Files.exists(file) || !java.nio.file.Files.isRegularFile(file) then ""
      else
        val content = new String(java.nio.file.Files.readAllBytes(file), "UTF-8")
        if content.length <= maxChars then content
        else content.take(maxChars) + s"\n... [truncated, ${content.length - maxChars} more chars]"
    catch case _: Exception => ""

  /** Check if a path is within the project root directory. */
  private def isWithinProject(path: String, projectRoot: String): Boolean =
    try
      val resolved = java.nio.file.Paths.get(path).toAbsolutePath.normalize
      val root = java.nio.file.Paths.get(projectRoot).toAbsolutePath.normalize
      resolved.startsWith(root)
    catch case _: Exception => false

end FullCompact
