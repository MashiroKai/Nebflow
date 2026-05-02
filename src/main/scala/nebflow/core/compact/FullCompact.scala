package nebflow.core.compact

import cats.effect.IO
import io.circe.syntax.*
import nebflow.shared.*
import nebflow.shared.given

object FullCompact:

  private val MaxCharsPerFile = 5000
  private val MaxRestoreTokens = 50000   // ~200K chars

  /** Full compact: SubAgent generates summary + specifies files to restore, replaces all messages.
    *
    * @param messages     current message list
    * @param llm          LLM handle
    * @param config       compact config
    * @param projectRoot  project root (for resolving relative paths)
    * @return Right(compacted) — summary message + file restore message, or Left(errorMessage)
    */
  def compact(
    messages: List[Message],
    llm: LlmHandle[IO],
    config: CompactConfig,
    projectRoot: String = ""
  ): IO[Either[String, List[Message]]] =
    if messages.size < 6 then IO.pure(Right(messages))
    else
      val cleaned = CompactUtils.stripImages(messages)
      val messagesJson = cleaned.asJson.noSpaces

      val request = LlmRequest(
        messages = List(
          Message(MessageRole.System, Left(CompactPrompts.full)),
          Message(MessageRole.User, Left(messagesJson))
        ),
        sessionId = "compact",
        agentId = "compact",
        tools = None,
        maxTokens = Some(Defaults.MaxTokensCompact)
      )

      for
        chunks <- llm.sendStream(request).compile.toList
        text = chunks.collect { case StreamChunk.TextDelta(d) => d }.mkString
      yield
        if text.isEmpty then Left("SubAgent returned empty response")
        else
          val (summaryText, filePaths) = extractFiles(text)

          val summaryMessage = Message(MessageRole.User, Left(
            s"<system-reminder>Context compaction completed. Compressed ${messages.size} messages.\n\n$summaryText</system-reminder>"
          ))

          val fileRestoreMessage = buildFileRestoreMessage(filePaths, projectRoot)
          Right(List(summaryMessage) ++ fileRestoreMessage.toList)
  end compact

  /** Extract <files> tag from SubAgent output.
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
      sb.append("<system-reminder>Restored files after compaction:\n\n")
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
      sb.append("</system-reminder>")
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
