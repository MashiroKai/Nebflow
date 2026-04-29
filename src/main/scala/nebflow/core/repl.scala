package nebflow.core

import cats.effect.{IO, Ref, Deferred}
import cats.syntax.all.*
import nebflow.core.tools.{ToolError, ToolRegistry}
import nebflow.shared.*
import nebflow.shared.TerminalUtils.*

import java.nio.file.{Files, Paths}

import scala.concurrent.duration.*
import scala.io.Source

object Repl:
  private val IMAGE_EXTENSIONS = List(".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".svg")
  private val VIDEO_EXTENSIONS = List(".mp4", ".mov", ".avi", ".mkv", ".webm", ".flv", ".wmv", ".m4v")
  private val MEDIA_EXTENSIONS = IMAGE_EXTENSIONS ++ VIDEO_EXTENSIONS

  private val MEDIA_REGEX =
    s"(?:^|\\s)((?:/[^\\s]+|[~.][^\\s]+)\\.(?:${MEDIA_EXTENSIONS.map(_.drop(1)).mkString("|")}))".r

  private val MIME_MAP = Map(
    "png" -> "image/png",
    "jpg" -> "image/jpeg",
    "jpeg" -> "image/jpeg",
    "gif" -> "image/gif",
    "webp" -> "image/webp",
    "bmp" -> "image/bmp",
    "svg" -> "image/svg+xml",
    "mp4" -> "video/mp4",
    "mov" -> "video/quicktime",
    "avi" -> "video/x-msvideo",
    "mkv" -> "video/x-matroska",
    "webm" -> "video/webm",
    "flv" -> "video/x-flv",
    "wmv" -> "video/x-ms-wmv",
    "m4v" -> "video/x-m4v"
  )

  private val MAX_IMAGE_SIZE = 5 * 1024 * 1024 // 5MB

  def replaceMediaPaths(input: String): String =
    val matches = MEDIA_REGEX.findAllMatchIn(input).toList
    if matches.isEmpty then input
    else
      var result = ""
      var lastEnd = 0
      matches.zipWithIndex.foreach { case (m, i) =>
        val path = m.group(1)
        val fullMatch = m.matched
        val pathIndexInMatch = fullMatch.indexOf(path)
        val pathStart = m.start + pathIndexInMatch
        result += input.substring(lastEnd, pathStart)
        result += s"[media ${i + 1}]"
        lastEnd = pathStart + path.length
      }
      result + input.substring(lastEnd)

  private def buildUserMessage(input: String): IO[Message] = IO.blocking {
    val matches = MEDIA_REGEX.findAllMatchIn(input).toList
    if matches.isEmpty then Message(MessageRole.User, Left(input))
    else
      val imageBlocks = scala.collection.mutable.ListBuffer.empty[ContentBlock.Image]
      val mediaItems = scala.collection.mutable.ListBuffer.empty[String]

      matches.foreach { m =>
        val filePathStr = m.group(1).replaceFirst("^~", sys.props.getOrElse("user.home", "~"))
        val ext = filePathStr.split("\\.").lastOption.map(_.toLowerCase).getOrElse("")
        val filePath = Paths.get(filePathStr)

        if !Files.exists(filePath) || !Files.isRegularFile(filePath) then mediaItems += s"Not a file: $filePathStr"
        else if VIDEO_EXTENSIONS.exists(filePathStr.toLowerCase.endsWith) then
          val sizeKb = (Files.size(filePath) / 1024).toInt
          mediaItems += s"Video: $filePathStr (${sizeKb}KB)"
        else if Files.size(filePath) > MAX_IMAGE_SIZE then
          val sizeMb = Files.size(filePath).toDouble / 1024 / 1024
          mediaItems += s"Image too large: $filePathStr (${sizeMb}%.1fMB > 5MB)"
        else
          val mediaType = MIME_MAP.getOrElse(ext, "application/octet-stream")
          val bytes = Files.readAllBytes(filePath)
          val base64 = java.util.Base64.getEncoder.encodeToString(bytes)
          val sizeKb = (bytes.length / 1024).toInt
          mediaItems += s"Image: $filePathStr (${sizeKb}KB, $mediaType)"
          imageBlocks += ContentBlock.Image(base64, mediaType)
      }

      val displayInput = replaceMediaPaths(input)
      val blocks = ContentBlock.Text(displayInput) :: imageBlocks.toList
      Message(MessageRole.User, Right(blocks))
    end if
  }

  def loadSystemPrompt(): String =
    val resource = Option(getClass.getResourceAsStream("/prompts/system.md"))
    resource match
      case Some(is) =>
        try Source.fromInputStream(is).mkString
        catch case _: Exception => ""
      case None => ""

  private val fallbackPrompt = """You are the Nebflow assistant. Your duties:
1. Understand the user's intent and break it into executable subtasks
2. Use tools to execute those subtasks
3. Summarize and deliver results to the user

Principles: work until the task is resolved; diagnose failures before trying a new strategy; never suggest changes to code you haven't read; don't create files unless absolutely necessary. Be concise and direct, mark file paths with backticks, and do not use emoji."""

  private case class ConsumeResult(
    text: String,
    toolCalls: List[ToolCall],
    results: List[(ToolCall, ToolExecResult)],
    stopReason: Option[String]
  )

  private val SpinnerFrames = List("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

  private def spinLoop(label: String, i: Int): IO[Unit] =
    IO.print(s"\r${Dim}${SpinnerFrames(i % SpinnerFrames.length)} $label...${Reset}") *>
      IO(Console.out.flush()) *>
      IO.sleep(80.millis) >> spinLoop(label, i + 1)

  private def clearSpinner(label: String): IO[Unit] =
    val padLen = label.length + 20
    IO.print(s"\r${" " * padLen}\r") *> IO(Console.out.flush())

  private def withSpinner[A](label: String, silent: Boolean)(io: IO[A]): IO[A] =
    if silent then io
    else
      for
        fiber <- spinLoop(label, 0).start
        result <- io.onError { _ => fiber.cancel *> clearSpinner(label) }
        _ <- fiber.cancel
        _ <- clearSpinner(label)
      yield result

  /**
   * Run tool executions sequentially, checking interruptedRef before each one.
   *  This allows the Stop button to cancel pending tool calls.
   */
  private def runExecutionsWithInterruptCheck(
    execs: List[IO[(ToolCall, ToolExecResult)]],
    interruptedRef: cats.effect.Ref[IO, Boolean]
  ): IO[List[(ToolCall, ToolExecResult)]] =
    def loop(
      remaining: List[IO[(ToolCall, ToolExecResult)]],
      acc: List[(ToolCall, ToolExecResult)]
    ): IO[List[(ToolCall, ToolExecResult)]] =
      remaining match
        case Nil => IO.pure(acc.reverse)
        case io :: rest =>
          interruptedRef.get.flatMap {
            case true => IO.raiseError(new UserAbort())
            case false => io.flatMap(r => loop(rest, r :: acc))
          }
    loop(execs, Nil)

  private def consumeStream(
    stream: fs2.Stream[IO, StreamChunk],
    store: ReplUi,
    llm: LlmHandle[IO],
    silent: Boolean = false,
    permState: Option[PermissionState] = None,
    messagesRef: Option[Ref[IO, List[Message]]] = None
  ): IO[ConsumeResult] =
    for
      abortSignal <- Deferred[IO, Unit]
      interruptedRef <- IO.ref(false)
      textRef <- IO.ref("")
      textStartedRef <- IO.ref(false)
      toolCallsRef <- IO.ref(List.empty[ToolCall])
      executionsRef <- IO.ref(List.empty[IO[(ToolCall, ToolExecResult)]])
      thinkingFiberRef <- IO.ref(Option.empty[cats.effect.Fiber[IO, Throwable, Unit]])
      _ <- store.onEscInterrupt(interruptedRef.set(true) *> abortSignal.complete(()).void)

      _ <- store.emitThinking()
      _ <- (if silent then IO.unit else spinLoop("Thinking", 0).start.flatMap(f => thinkingFiberRef.set(Some(f))))

      processStream = stream
        .interruptWhen(abortSignal.get.map(_ => Left(new UserAbort())))
        .evalMap { chunk =>
          chunk match
            case StreamChunk.TextDelta(delta) =>
              if delta.nonEmpty then
                thinkingFiberRef.get.flatMap {
                  case Some(fiber) =>
                    fiber.cancel *> (if silent then IO.unit else clearSpinner("Thinking")) *> thinkingFiberRef.set(
                      None
                    )
                  case None => IO.unit
                } *> textStartedRef.get.flatMap { started =>
                  textRef.update(_ + delta) *>
                    store.emitTextDelta(delta) *>
                    (if silent then IO.unit else IO.print(delta) *> IO(Console.out.flush())) *>
                    textStartedRef.set(true)
                }
              else IO.unit
            case StreamChunk.ToolCallChunk(toolCall) =>
              thinkingFiberRef.get.flatMap {
                case Some(fiber) =>
                  fiber.cancel *> (if silent then IO.unit else clearSpinner("Thinking")) *> thinkingFiberRef.set(
                    None
                  )
                case None => IO.unit
              } *>
                toolCallsRef.update(_ :+ toolCall) *>
                textStartedRef.get.flatMap { hasText =>
                  if hasText then store.emitTextDone() *> (if silent then IO.unit else IO.println("")) else IO.unit
                } *>
                IO.delay(summarizeToolCall(toolCall)).flatMap { label =>
                  val execIO = store.emitToolStart(label) *>
                    withSpinner(label, silent) {
                      executeTool(toolCall, System.getProperty("user.dir"), Some(llm), Some(store), permState, messagesRef)
                        .race(abortSignal.get *> IO.raiseError(new UserAbort()))
                        .flatMap {
                          case Left(result) => IO.pure(result)
                          case Right(_) => IO.raiseError(new UserAbort())
                        }
                    }.flatMap { result =>
                      val resultSummary = summarizeToolResult(toolCall, result.content)
                      val icon = if result.isError then s"${Red}✗${Reset}" else s"${Green}✓${Reset}"
                      val inputJson = Some(io.circe.Json.fromJsonObject(toolCall.input).noSpaces)
                      (if silent then IO.unit else IO.println(s"$icon $label - $resultSummary")) *>
                        store
                          .emitToolEnd(label, resultSummary, result.content, result.isError, inputJson)
                          .as((toolCall, result))
                    }
                  executionsRef.update(_ :+ execIO)
                }
            case StreamChunk.Done(stopReason, usage, _) =>
              stopReason match
                case Some("max_tokens") => store.emitMaxTokens() *> (if silent then IO.unit else IO.println(""))
                case Some("timeout") => store.emitTimeout() *> (if silent then IO.unit else IO.println(""))
                case _ =>
                  textStartedRef.get.flatMap { hasText =>
                    if hasText then store.emitTextDone() *> (if silent then IO.unit else IO.println(""))
                    else IO.unit
                  }
        }
        .compile
        .drain

      _ <- processStream
        .handleErrorWith {
          case _: UserAbort => store.emitInterrupted() *> IO.unit
          case e => IO.raiseError(e)
        }
        .timeout(300.seconds)
        .handleErrorWith {
          case _: java.util.concurrent.TimeoutException =>
            store.emitTimeout() *> IO.unit
          case e => IO.raiseError(e)
        }
      _ <- thinkingFiberRef.get.flatMap {
        case Some(fiber) => fiber.cancel *> (if silent then IO.unit else clearSpinner("Thinking"))
        case None => IO.unit
      }
      execs <- executionsRef.get
      results <- runExecutionsWithInterruptCheck(execs, interruptedRef)
      text <- textRef.get
      toolCalls <- toolCallsRef.get
    yield ConsumeResult(text, toolCalls, results, None)

  private def appendToolRound(
    messages: List[Message],
    text: String,
    toolCalls: List[ToolCall],
    results: List[(ToolCall, ToolExecResult)]
  ): List[Message] =
    val assistantBlocks = scala.collection.mutable.ListBuffer.empty[ContentBlock]
    if text.nonEmpty then assistantBlocks += ContentBlock.Text(text)
    toolCalls.foreach { call =>
      assistantBlocks += ContentBlock.ToolUse(call.id, call.name, call.input)
    }
    val assistantMsg = Message(MessageRole.Assistant, Right(assistantBlocks.toList))

    val resultBlocks = results.map { case (call, result) =>
      ContentBlock.ToolResult(call.id, result.content, Some(result.isError))
    }
    val resultMsg = Message(MessageRole.User, Right(resultBlocks))

    messages ++ List(assistantMsg, resultMsg)

  def runRepl(
    userInput: String,
    llm: LlmHandle[IO],
    projectRoot: String,
    initialMessages: List[Message],
    store: ReplUi,
    onToolRound: Option[List[Message] => IO[Unit]],
    silent: Boolean,
    thinkingMode: Option[io.circe.Json] = None,
    permState: Option[PermissionState] = None
  ): IO[List[Message]] =
    val systemPrompt = loadSystemPrompt()
    buildUserMessage(userInput).flatMap { userMessage =>
      runReplLoop(
        userMessage,
        llm,
        projectRoot,
        initialMessages,
        store,
        onToolRound,
        silent,
        systemPrompt,
        thinkingMode,
        permState
      )
    }

  end runRepl

  def runRepl(
    userMessage: Message,
    llm: LlmHandle[IO],
    projectRoot: String,
    initialMessages: List[Message],
    store: ReplUi,
    onToolRound: Option[List[Message] => IO[Unit]],
    silent: Boolean,
    thinkingMode: Option[io.circe.Json],
    permState: Option[PermissionState]
  ): IO[List[Message]] =
    val systemPrompt = loadSystemPrompt()
    runReplLoop(
      userMessage,
      llm,
      projectRoot,
      initialMessages,
      store,
      onToolRound,
      silent,
      systemPrompt,
      thinkingMode,
      permState
    )

  private def runReplLoop(
    userMessage: Message,
    llm: LlmHandle[IO],
    projectRoot: String,
    initialMessages: List[Message],
    store: ReplUi,
    onToolRound: Option[List[Message] => IO[Unit]],
    silent: Boolean,
    systemPrompt: String,
    thinkingMode: Option[io.circe.Json],
    permState: Option[PermissionState]
  ): IO[List[Message]] =
    // Ref for ContextManageTool to modify messages in-place
    Ref.of[IO, List[Message]](initialMessages).flatMap { ctxMsgRef =>
    def loop(messages: List[Message]): IO[List[Message]] =
      // Sync the ref so ContextManageTool sees current messages
      ctxMsgRef.set(messages) *> {
      val systemMsg = Message(MessageRole.System, Left(systemPrompt))
      val tagged = systemMsg :: tagMessages(messages)
      val stream = llm.sendStream(
        LlmRequest(
          messages = tagged,
          sessionId = "repl",
          agentId = "user",
          tools = Some(ToolRegistry.ALL_TOOLS),
          thinking = thinkingMode
        )
      )

      consumeStream(stream, store, llm, silent, permState, Some(ctxMsgRef))
        .flatMap { consumed =>
          if consumed.toolCalls.isEmpty then IO.pure(messages)
          else
            // Read from ctxMsgRef in case ContextManageTool modified messages
            ctxMsgRef.get.flatMap { managedMsgs =>
              val base = if managedMsgs != messages then managedMsgs else messages
              val updated = appendToolRound(base, consumed.text, consumed.toolCalls, consumed.results)
              onToolRound.traverse_(_.apply(updated)) *> loop(updated)
            }
        }
        .handleErrorWith {
          case _: UserAbort => IO.pure(messages)
          case e => IO.raiseError(e)
        }
      } // end ctxMsgRef.set *> block

    loop(initialMessages :+ userMessage)
    } // end ctxMsgRef flatMap
  end runReplLoop

  /** Inject [ctx:N] tags into all messages in the conversation array.
   *  System prompt is injected separately (untracked) so it's never editable.
   *  All messages here get a tag — having a tag means editable by ContextManage.
   */
  private def tagMessages(messages: List[Message]): List[Message] =
    messages.zipWithIndex.map { case (msg, idx) =>
      val tag = s"[ctx:$idx] "
      msg.content match
        case Left(text) => msg.copy(content = Left(tag + text))
        case Right(blocks) =>
          blocks match
            case (ContentBlock.Text(t)) :: rest =>
              msg.copy(content = Right(ContentBlock.Text(tag + t) :: rest))
            case (tr: ContentBlock.ToolResult) :: _ =>
              msg.copy(content = Right(ContentBlock.ToolResult(tr.toolUseId, tag + tr.content, tr.isError) :: blocks.tail))
            case _ => msg.copy(content = Left(tag + msg.textContent))
    }
end Repl
