package nebflow.core.compact

import cats.effect.IO
import io.circe.syntax.*
import nebflow.shared.*
import nebflow.shared.given

object FullCompact:

  private val MaxCharsPerFile = 5000
  private val MaxRestoreTokens = 50000 // ~200K chars

  /**
   * Parse LLM response text into a compacted message list.
   * Extracts <files> tags and builds file restore messages.
   */
  def parseResponse(
    text: String,
    originalMessages: List[Message],
    projectRoot: String = ""
  ): Either[String, List[Message]] =
    if text.isEmpty then Left("SubAgent returned empty response")
    else
      val (summaryText, filePaths) = extractFiles(text)

      val filePathsSection =
        if filePaths.isEmpty then ""
        else
          val absolutePaths = filePaths
            .map { p =>
              if p.startsWith("/") then p else s"$projectRoot/$p"
            }
            .filter(isWithinProject(_, projectRoot))
          if absolutePaths.isEmpty then ""
          else "\n\nRestored files after compaction:\n\n" + absolutePaths.map(p => s"- `$p`").mkString("\n")

      val message = Message(
        MessageRole.User,
        Left(
          s"<context-compact mode=\"full\">Compressed ${originalMessages.size} messages.\n\n$summaryText$filePathsSection</context-compact>"
        )
      )
      Right(List(message))
  end parseResponse

  /**
   * Extract <files> tag from SubAgent output.
   * Returns (summary without files tag, file path list)
   */
  private def extractFiles(text: String): (String, List[String]) =
    val startTag = "<files>"
    val endTag = "</files>"
    val startIdx = text.indexOf(startTag)
    val endIdx = text.indexOf(endTag)

    if startIdx < 0 || endIdx < 0 || endIdx <= startIdx then (text, Nil)
    else
      val filesBlock = text.substring(startIdx + startTag.length, endIdx)
      val paths = filesBlock.linesIterator
        .map(_.trim)
        .filter(_.nonEmpty)
        .take(5)
        .toList
      val summary = text.substring(0, startIdx).trim + "\n" + text.substring(endIdx + endTag.length).trim
      (summary.trim, paths)

  /** Read files and build restore message. Each file truncated to MaxCharsPerFile. */
  private def buildFileRestoreMessage(paths: List[String], projectRoot: String): Option[Message] =
    if paths.isEmpty then None
    else
      val sb = new StringBuilder
      sb.append("<context-compact>Restored files after compaction:\n\n")
      var usedChars = 0
      for path <- paths if usedChars < MaxRestoreTokens * 4 do
        val resolved = if path.startsWith("/") then path else s"$projectRoot/$path"
        // Skip paths outside project root for security
        if isWithinProject(resolved, projectRoot) then
          val content = readFileContent(resolved, MaxCharsPerFile)
          if content.nonEmpty then
            val section = s"### `$path`\n```\n$content\n```\n\n"
            if usedChars + section.length <= MaxRestoreTokens * 4 then
              sb.append(section)
              usedChars += section.length
      sb.append("</context-compact>")
      if usedChars == 0 then None
      else Some(Message(MessageRole.User, Left(sb.toString)))

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
