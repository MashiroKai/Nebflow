package nebflow.core

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import nebflow.core.compact.{CompactConfig, FullCompact}
import nebflow.core.tools.{ToolError, ToolRegistry}
import nebflow.shared.*
import nebflow.shared.TerminalUtils.*
import nebflow.skill.SkillDiscovery

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

  def buildUserMessage(input: String): IO[Message] = IO.blocking { // public for AgentActor
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

  def loadSystemPrompt(): String = // public for AgentActor
    val resource = Option(getClass.getResourceAsStream("/prompts/system.md"))
    resource match
      case Some(is) =>
        try Source.fromInputStream(is).mkString
        catch case _: Exception => ""
      case None => ""

  def buildEnvInfo(projectRoot: String): String = // public for AgentActor
    val sb = new StringBuilder
    sb.append("## Environment\n\n")
    sb.append("| Property | Value |\n")
    sb.append("|----------|-------|\n")
    sb.append(s"| Working directory | `$projectRoot` |\n")
    sb.append(s"| Platform | ${sys.props.getOrElse("os.name", "unknown").toLowerCase} |\n")
    sb.append(s"| Shell | ${sys.env.getOrElse("SHELL", "unknown")} |\n")
    sb.append(s"| OS Version | ${sys.props.getOrElse("os.name", "")} ${sys.props.getOrElse("os.version", "")} |\n")
    sb.append(s"| Nebflow version | v${nebflow.Version.string} |\n")
    sb.append("\n")

    // Git info
    try
      val dir = new java.io.File(projectRoot)
      val isGitRepo = new java.io.File(dir, ".git").exists()
      if isGitRepo then
        sb.append("## Git Status\n\n")
        def gitCmd(args: String): String =
          val proc = Runtime.getRuntime.exec(s"git $args".split(" "), null, dir)
          val out = scala.io.Source.fromInputStream(proc.getInputStream).mkString.trim
          proc.waitFor()
          out

        val branch = gitCmd("rev-parse --abbrev-ref HEAD")
        sb.append(s"| Property | Value |\n")
        sb.append("|----------|-------|\n")
        sb.append(s"| Current branch | `$branch` |\n")

        val mainBranch = Seq("main", "master")
          .find(b =>
            val p = Runtime.getRuntime.exec(Array("git", "rev-parse", "--verify", b), null, dir)
            p.waitFor() == 0
          )
          .getOrElse("main")
        sb.append(s"| Main branch | `$mainBranch` |\n")

        val gitUser = gitCmd("config user.name")
        if gitUser.nonEmpty then sb.append(s"| Git user | $gitUser |\n")

        val status = gitCmd("status --porcelain")
        if status.nonEmpty then
          val lines = status.linesIterator.take(10).toList
          val formatted = lines.map(_.trim).mkString("`", "`<br>`", "`")
          sb.append(s"| Modified | $formatted |\n")

        val log = gitCmd("log --oneline -5")
        if log.nonEmpty then
          sb.append("\n### Recent Commits\n\n")
          sb.append("| Commit | Message |\n")
          sb.append("|--------|--------|\n")
          log.linesIterator.foreach { line =>
            val parts = line.split(" ", 2)
            if parts.length == 2 then sb.append(s"| `${parts(0)}` | ${parts(1)} |\n")
          }
        sb.append("\n")
      end if
    catch case _: Exception => ()
    end try

    sb.toString
  end buildEnvInfo

  private case class ConsumeResult(
    text: String,
    toolCalls: List[ToolCall],
    results: List[(ToolCall, ToolExecResult)],
    stopReason: Option[String],
    usage: Option[TokenUsage] = None
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
    messagesRef: Option[Ref[IO, List[Message]]] = None,
    fileChangeTracker: Option[FileChangeTracker] = None,
    sessionStore: Option[nebflow.gateway.SessionStore] = None,
    sessionTag: Option[String] = None,
    inspectMappingRef: Option[Ref[IO, Option[List[Int]]]] = None,
    sessionActorRef: Option[org.apache.pekko.actor.typed.ActorRef[nebflow.agent.SessionCommand]] = None
  ): IO[ConsumeResult] =
    for
      abortSignal <- Deferred[IO, Unit]
      interruptedRef <- IO.ref(false)
      textRef <- IO.ref("")
      textStartedRef <- IO.ref(false)
      usageRef <- IO.ref(Option.empty[TokenUsage])
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
                      executeTool(
                        toolCall,
                        System.getProperty("user.dir"),
                        Some(llm),
                        Some(store),
                        permState,
                        messagesRef,
                        fileChangeTracker,
                        sessionStore,
                        Some(usageRef),
                        sessionTag,
                        inspectMappingRef,
                        sessionActorRef
                      )
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
              usage.foreach(u => usageRef.set(Some(u)))
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
      usage <- usageRef.get
    yield ConsumeResult(text, toolCalls, results, None, usage)

  private def appendToolRound(
    messages: List[Message],
    text: String,
    toolCalls: List[ToolCall],
    results: List[(ToolCall, ToolExecResult)],
    reminders: List[SystemReminder] = Nil
  ): List[Message] =
    val assistantBlocks = scala.collection.mutable.ListBuffer.empty[ContentBlock]
    if text.nonEmpty then assistantBlocks += ContentBlock.Text(text)
    toolCalls.foreach { call =>
      assistantBlocks += ContentBlock.ToolUse(call.id, call.name, call.input)
    }
    val assistantMsg = Message(MessageRole.Assistant, Right(assistantBlocks.toList))

    val resultBlocks = scala.collection.mutable.ListBuffer.empty[ContentBlock]
    results.foreach { case (call, result) =>
      resultBlocks += ContentBlock.ToolResult(call.id, result.content, Some(result.isError))
    }
    // Inject system reminders alongside tool results (merged into one block)
    if reminders.nonEmpty then resultBlocks += ContentBlock.Text(SystemReminder.renderAll(reminders))
    val resultMsg = Message(MessageRole.User, Right(resultBlocks.toList))

    messages ++ List(assistantMsg, resultMsg)

  def runRepl(
    userMessage: Message,
    llm: LlmHandle[IO],
    projectRoot: String,
    initialMessages: List[Message],
    store: ReplUi,
    onToolRound: Option[List[Message] => IO[Unit]],
    silent: Boolean,
    thinkingMode: Option[io.circe.Json],
    permState: Option[PermissionState],
    contextWindow: Int,
    reminderStateRef: Option[Ref[IO, ReminderState]],
    fileChangeTracker: Option[FileChangeTracker],
    skillDiscovery: Option[SkillDiscovery] = None,
    userText: Option[String] = None,
    sessionStore: Option[nebflow.gateway.SessionStore] = None,
    systemPromptOverride: Option[String] = None,
    toolFilter: Option[List[String]] = None,
    sessionTag: Option[String] = None,
    sessionActorRef: Option[org.apache.pekko.actor.typed.ActorRef[nebflow.agent.SessionCommand]] = None
  ): IO[List[Message]] =
    val basePrompt = systemPromptOverride.getOrElse(loadSystemPrompt())
    val envInfo = buildEnvInfo(projectRoot)
    val systemPrompt = s"$basePrompt\n\n$envInfo"
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
      permState,
      contextWindow,
      reminderStateRef,
      fileChangeTracker,
      skillDiscovery,
      userText,
      sessionStore,
      toolFilter,
      sessionTag,
      sessionActorRef
    )

  end runRepl

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
    permState: Option[PermissionState],
    contextWindow: Int,
    reminderStateRef: Option[Ref[IO, ReminderState]],
    fileChangeTracker: Option[FileChangeTracker],
    skillDiscovery: Option[SkillDiscovery],
    userText: Option[String],
    sessionStore: Option[nebflow.gateway.SessionStore],
    toolFilter: Option[List[String]],
    sessionTag: Option[String],
    sessionActorRef: Option[org.apache.pekko.actor.typed.ActorRef[nebflow.agent.SessionCommand]]
  ): IO[List[Message]] =
    // Ref for ContextManageTool to modify messages in-place
    Ref.of[IO, List[Message]](initialMessages).flatMap { ctxMsgRef =>
      // Per-session inspect mapping for ContextManageTool
      Ref.of[IO, Option[List[Int]]](None).flatMap { inspectMappingRef =>

        def collectReminders(usage: Option[TokenUsage]): IO[List[SystemReminder]] =
          for
            fileChangesOpt <- fileChangeTracker.traverse(_.checkChanges()).map(_.flatten)
            skillMatchOpt <- skillMatchForTurn
            currentPolicy <- permState match
              case Some(ps) => ps.policy
              case None => IO.pure(PermissionPolicy.default)
            reminders <- reminderStateRef match
              case Some(ref) =>
                SystemReminders.collectAll(ref, usage, contextWindow, fileChangesOpt, currentPolicy, skillMatchOpt)
              case None => IO.pure(fileChangesOpt.toList)
          yield reminders

        // Skill discovery: only run once on first turn using original user text
        lazy val skillMatchForTurn: IO[Option[nebflow.skill.SkillMatch]] =
          (skillDiscovery, userText) match
            case (Some(sd), Some(text)) if text.nonEmpty =>
              sd.findRelevantSkill(text).handleErrorWith { e =>
                IO.pure(None)
              }
            case _ => IO.pure(None)

        def loop(messages: List[Message]): IO[List[Message]] =
          // Sync the ref so ContextManageTool sees current messages
          ctxMsgRef.set(messages) *>
            // Collect system reminders for this turn
            collectReminders(None).flatMap { reminders =>
              val systemMsg = Message(MessageRole.System, Left(systemPrompt))
              // Inject reminders for first turn (no tool results yet)
              val tagged = systemMsg :: messages
              val messagesWithReminders =
                if reminders.nonEmpty then
                  tagged ++ List(
                    Message(MessageRole.User, Right(List(ContentBlock.Text(SystemReminder.renderAll(reminders)))))
                  )
                else tagged

              val stream = llm.sendStream(
                LlmRequest(
                  messages = messagesWithReminders,
                  sessionId = "repl",
                  agentId = "user",
                  tools = Some(
                    toolFilter
                      .map(f => ToolRegistry.ALL_TOOLS.filter(t => f.contains(t.name)))
                      .getOrElse(ToolRegistry.ALL_TOOLS)
                  ),
                  thinking = thinkingMode
                )
              )

              consumeStream(
                stream,
                store,
                llm,
                silent,
                permState,
                Some(ctxMsgRef),
                fileChangeTracker,
                sessionStore,
                sessionTag,
                Some(inspectMappingRef),
                sessionActorRef
              )
                .flatMap { consumed =>
                  // === Auto-compact check ===
                  val autoCompactIO: IO[Unit] = consumed.usage match
                    case Some(usage) if usage.inputTokens.toFloat / contextWindow > 0.80f =>
                      for
                        msgs <- ctxMsgRef.get
                        config = CompactConfig.forContextWindow(contextWindow)
                        result <- FullCompact.compact(msgs, llm, config, projectRoot)
                        _ <- result match
                          case Right(compacted) =>
                            ctxMsgRef.set(compacted) *>
                              NebflowLogger
                                .forName("nebflow.repl")
                                .info(s"Auto-compact: ${msgs.size} -> ${compacted.size} messages")
                          case Left(err) =>
                            NebflowLogger.forName("nebflow.repl").warn(s"Auto-compact failed: $err")
                      yield ()
                    case _ => IO.unit

                  autoCompactIO.flatMap { _ =>
                    // Collect reminders using actual usage (works for both tool and no-tool paths)
                    collectReminders(consumed.usage).flatMap { reminders =>
                      if consumed.toolCalls.isEmpty then
                        // No tool calls — append assistant reply + reminders (if any)
                        val assistantMsg =
                          if consumed.text.nonEmpty then Some(Message(MessageRole.Assistant, Left(consumed.text)))
                          else None
                        val reminderMsg =
                          if reminders.nonEmpty then
                            Some(
                              Message(
                                MessageRole.User,
                                Right(List(ContentBlock.Text(SystemReminder.renderAll(reminders))))
                              )
                            )
                          else None
                        val extra = List(assistantMsg, reminderMsg).flatten
                        val updated = if extra.nonEmpty then messages ++ extra else messages
                        // Always save to disk after each LLM response
                        onToolRound.traverse_(_.apply(updated)) *> IO.pure(updated)
                      else
                        // Read from ctxMsgRef in case ContextManageTool modified messages
                        ctxMsgRef.get.flatMap { managedMsgs =>
                          val base = if managedMsgs != messages then managedMsgs else messages
                          val updated =
                            appendToolRound(base, consumed.text, consumed.toolCalls, consumed.results, reminders)
                          onToolRound.traverse_(_.apply(updated)) *> loop(updated)
                        }
                    }
                  }
                }
                .handleErrorWith {
                  case _: UserAbort => IO.pure(messages)
                  case e => IO.raiseError(e)
                }
            }

        loop(initialMessages :+ userMessage)
      } // end inspectMappingRef flatMap
    } // end ctxMsgRef flatMap
  end runReplLoop
end Repl
