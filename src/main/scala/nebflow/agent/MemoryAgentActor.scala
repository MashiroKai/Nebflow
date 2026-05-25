package nebflow.agent

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import nebflow.agent.MemoryAgentProtocol.*
import nebflow.core.NebflowLogger
import nebflow.core.tools.{FileLockManager, ToolContext, ToolRegistry}
import nebflow.shared.*
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

/**
 * Ephemeral memory management agent — full agent loop with Read/Write/Edit tools.
 *
 * All scopes use per-mail temporary actors. The Manager handles Dream scheduling.
 * Every actor processes one task then dies.
 */
object MemoryAgentActor:

  private val logger = NebflowLogger.forName("nebflow.memory-agent")

  // ============================================================
  // Per-scope system prompts — minimal token footprint
  // ============================================================

  private def userPrompt: String =
    """You manage the user preferences memory file. It stores the user's personal preferences and explicit instructions: preferred language, coding style, communication preferences, tool usage habits.
      |
      |Only add things the user explicitly asked to remember or demonstrated through repeated behavior.
      |Do NOT record project-specific facts or temporary states here.
      |
      |Format: one entry per line, `- [tag] content`. Tags: decision, fact, gotcha, convention, todo, fix.
      |Keep the `# Memory` heading if present; do not add new headings.
      |
      |For each incoming entry:
      |1. Read the file.
      |2. Decide: accept (new knowledge), merge (partial overlap), replace (supersedes old), or discard (trivial/wrong/transient).
      |3. Use Edit for targeted changes, Write for full rewrites. Prefer Edit.
      |
      |Rules: no duplicates, no stale data (line numbers, temp errors), keep entries concise and self-contained, merge keeps the more precise version, group related entries together.
      |
      |When finished, respond: DONE""".stripMargin

  private def userDreamPrompt: String =
    """You manage the user preferences memory file. You are now in Dream mode.
      |
      |You will receive a digest of the user's conversations from the past 24 hours.
      |Your task: extract user preferences and behavioral patterns from these conversations, then update the memory file.
      |
      |Look for:
      |- Explicit preferences the user stated (language, style, workflow habits)
      |- Implicit patterns: what the user asks for repeatedly, how they like code structured
      |- Tool usage preferences: which tools the user favors or avoids
      |- Communication preferences: detail level, language, response format
      |
      |Do NOT extract:
      |- Project-specific facts (those go in folder memory)
      |- Temporary states (current task, open files)
      |- One-off requests that don't indicate a pattern
      |
      |Format: one entry per line, `- [tag] content`. Tags: decision, fact, gotcha, convention, todo, fix.
      |Keep the `# Memory` heading if present; do not add new headings.
      |
      |Read the file first. Then integrate any new preferences you found. Merge with existing entries when they overlap. Remove any entries that are clearly outdated.
      |Prefer Edit for targeted changes. Use Write only if a major reorganization is needed.
      |
      |When finished, respond: DONE""".stripMargin

  private def agentPrompt(name: String): String =
    s"""You manage the agent "$name" knowledge memory file. It stores durable knowledge that persists across all sessions and folders: architecture decisions, debugging patterns, codebase conventions, gotchas, common pitfalls.
       |
       |Focus on knowledge that helps this agent work better in ANY future session.
       |Do NOT record session-specific state (current task progress, open files, temporary errors).
       |
       |Format: one entry per line, `- [tag] content`. Tags: decision, fact, gotcha, convention, todo, fix.
       |Keep the `# Memory` heading if present; do not add new headings.
       |
       |For each incoming entry:
       |1. Read the file.
       |2. Decide: accept (new knowledge), merge (partial overlap), replace (supersedes old), or discard (trivial/wrong/transient).
       |3. Use Edit for targeted changes, Write for full rewrites. Prefer Edit.
       |
       |Rules: no duplicates, no stale data (line numbers, temp errors), keep entries concise and self-contained, merge keeps the more precise version, group related entries together.
       |
       |When finished, respond: DONE""".stripMargin

  private def folderPrompt: String =
    """You manage a folder project knowledge memory file. It stores project-specific knowledge: codebase structure, conventions, API details, config values, build instructions.
      |Child folders inherit this knowledge automatically.
      |
      |Do NOT record agent-level patterns or user preferences here.
      |
      |Format: one entry per line, `- [tag] content`. Tags: decision, fact, gotcha, convention, todo, fix.
      |Keep the `# Memory` heading if present; do not add new headings.
      |
      |For each incoming entry:
      |1. Read the file.
      |2. Decide: accept (new knowledge), merge (partial overlap), replace (supersedes old), or discard (trivial/wrong/transient).
      |3. Use Edit for targeted changes, Write for full rewrites. Prefer Edit.
      |
      |Rules: no duplicates, no stale data (line numbers, temp errors), keep entries concise and self-contained, merge keeps the more precise version, group related entries together.
      |
      |When finished, respond: DONE""".stripMargin

  private def systemPromptFor(scope: MemoryAgentScope): String =
    scope match
      case MemoryAgentScope.User        => userPrompt
      case MemoryAgentScope.Agent(name) => agentPrompt(name)
      case MemoryAgentScope.Folder(_)   => folderPrompt

  // ============================================================
  // Memory file path
  // ============================================================

  private def memoryFilePath(scope: MemoryAgentScope): String =
    import nebflow.service.MemoryStore
    scope match
      case MemoryAgentScope.User             => MemoryStore.userMemoryPath.toString
      case MemoryAgentScope.Agent(name)      => MemoryStore.agentMemoryPath(name).toString
      case MemoryAgentScope.Folder(folderId) => MemoryStore.folderMemoryPath(folderId).toString

  // ============================================================
  // Tools
  // ============================================================

  private val memoryToolNames = Set("Read", "Write", "Edit")

  private val memoryToolDefs: List[ToolDefinition] =
    ToolRegistry.ALL_TOOLS.filter(t => memoryToolNames.contains(t.name))

  private def buildToolContext(scope: MemoryAgentScope): ToolContext =
    val filePath = memoryFilePath(scope)
    val parentDir = filePath.split("/").dropRight(1).mkString("/")
    ToolContext(
      projectRoot = parentDir,
      fileLockManager = Some(FileLockManager.create.unsafeRunSync())
    )

  private def executeToolCall(call: ToolCall, ctx: ToolContext): String =
    ToolRegistry.TOOL_MAP.get(call.name) match
      case Some(tool) =>
        tool.call(call.input, ctx).unsafeRunSync() match
          case Right(output) => output
          case Left(err)     => s"Tool error: ${err.message}"
      case None =>
        s"Tool not available: ${call.name}. Only Read, Write, Edit are allowed."

  // ============================================================
  // Agent loop: LLM → tools → LLM → ... → DONE
  // ============================================================

  private def runAgentLoop(
    llm: LlmHandle[IO],
    dispatcher: cats.effect.std.Dispatcher[IO],
    scope: MemoryAgentScope,
    systemPrompt: String,
    userMessage: String
  ): Unit =
    val toolCtx = buildToolContext(scope)

    var messages: List[Message] = List(
      Message(MessageRole.User, Left(userMessage))
    )
    var depth = 0
    var finished = false

    while !finished do
      depth += 1
      val request = LlmRequest(
        messages = messages,
        sessionId = s"memory-${scope.label}",
        agentId = "memory-agent",
        tools = Some(memoryToolDefs),
        systemStable = Some(systemPrompt)
      )

      val response = dispatcher.unsafeRunSync(
        llm.send(request)
          .handleError { e =>
            logger.warnSync(s"Memory Agent [${scope.label}] LLM call failed at depth $depth: ${e.getMessage}")
            LlmResponse("DONE", Nil, None, LlmMeta(s"memory-${scope.label}", "memory-agent", "?", "?", 0L))
          }
      )

      if response.reply.trim == "DONE" && response.toolCalls.isEmpty then
        finished = true
      else
        val assistantContent: List[ContentBlock] =
          val textBlock = if response.reply.nonEmpty then List(ContentBlock.Text(response.reply)) else Nil
          val toolUseBlocks = response.toolCalls.map(tc => ContentBlock.ToolUse(tc.id, tc.name, tc.input))
          textBlock ++ toolUseBlocks
        messages = messages :+ Message(MessageRole.Assistant, Right(assistantContent))

        if response.toolCalls.nonEmpty then
          val toolResults: List[ContentBlock] = response.toolCalls.map { tc =>
            if !memoryToolNames.contains(tc.name) then
              ContentBlock.ToolResult(tc.id, s"Tool not available: ${tc.name}. Only Read, Write, Edit are allowed.", Some(true))
            else
              val output = executeToolCall(tc, toolCtx)
              val isError = output.startsWith("Tool error:") || output.startsWith("Tool not available:")
              ContentBlock.ToolResult(tc.id, output, Some(isError))
          }
          messages = messages :+ Message(MessageRole.User, Right(toolResults))
        else
          finished = true
    end while

    invalidateMemoryStoreCache(scope)
    logger.infoSync(s"Memory Agent [${scope.label}]: completed in $depth round(s)")
  end runAgentLoop

  // ============================================================
  // Cache invalidation
  // ============================================================

  private def invalidateMemoryStoreCache(scope: MemoryAgentScope): Unit =
    import nebflow.service.MemoryStore
    scope match
      case MemoryAgentScope.User             => MemoryStore.invalidateUserCache()
      case MemoryAgentScope.Agent(name)      => MemoryStore.invalidateAgentCache(name)
      case MemoryAgentScope.Folder(folderId) => MemoryStore.invalidateFolderCache(folderId)

  // ============================================================
  // Dream: read recent user inputs from input_history.jsonl
  // ============================================================

  private val inputHistoryPath: os.Path = os.home / ".nebflow" / "input_history.jsonl"

  def collectRecentUserInputs: IO[String] = IO.blocking {
    if !os.exists(inputHistoryPath) then ""
    else
      import java.time.*
      import java.time.format.DateTimeFormatter
      val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
      val cutoff = LocalDateTime.now().minusHours(24)
      val lines = os.read.lines(inputHistoryPath).toList
      val recent = lines.flatMap { line =>
        io.circe.parser.parse(line).toOption.flatMap { json =>
          val tsStr = json.hcursor.downField("ts").as[String].toOption.getOrElse("")
          val text = json.hcursor.downField("text").as[String].toOption.getOrElse("")
          val inputType = json.hcursor.downField("type").as[String].toOption.getOrElse("input")
          val ts = try Some(LocalDateTime.parse(tsStr, fmt)) catch case _: Exception => None
          ts.filter(_.isAfter(cutoff)).map(_ => s"[$inputType] $text")
        }
      }
      if recent.isEmpty then ""
      else
        s"""Here is a digest of the user's inputs from the past 24 hours:
           |
           |${recent.mkString("\n")}
           |
           |Extract any user preferences or behavioral patterns, then update the memory file if needed.
           |If nothing worth recording, just respond DONE.""".stripMargin
  }

  // ============================================================
  // Ephemeral actor: process one mail, die
  // ============================================================

  def apply(
    scope: MemoryAgentScope,
    llm: LlmHandle[IO],
    dispatcher: cats.effect.std.Dispatcher[IO],
    replyTo: Option[ActorRef[MemoryAgentDone.type]] = None
  ): Behavior[MemoryAgentCommand] =
    Behaviors.setup { _ =>
      Behaviors.receiveMessage {
        case mail: MemoryWriteMail if mail.entry.nonEmpty =>
          logger.infoSync(s"Memory Agent [${scope.label}]: processing entry from session ${mail.sourceSessionId}")
          try
            val entry = s"- [${mail.category}] ${mail.entry}"
            val userMsg = s"""New observations to integrate into the memory file at:
                             |${memoryFilePath(scope)}
                             |
                             |Entry:
                             |$entry
                             |
                             |Read the file, then integrate. Respond DONE when finished.""".stripMargin
            runAgentLoop(llm, dispatcher, scope, systemPromptFor(scope), userMsg)
          catch
            case e: Exception =>
              logger.warnSync(s"Memory Agent [${scope.label}]: failed: ${e.getMessage}")
          Behaviors.stopped

        case _ =>
          Behaviors.stopped
      }
    }

  // ============================================================
  // Dream actor: run Dream cycle on User memory, die
  // ============================================================

  /** Spawned by MemoryAgentManager when Dream is due. Receives pre-collected digest. */
  def dream(
    llm: LlmHandle[IO],
    dispatcher: cats.effect.std.Dispatcher[IO],
    digest: String
  ): Behavior[MemoryAgentCommand] =
    Behaviors.setup { _ =>
      try
        runAgentLoop(llm, dispatcher, MemoryAgentScope.User, userDreamPrompt, digest)
      catch
        case e: Exception =>
          logger.warnSync(s"Memory Agent [user]: Dream failed: ${e.getMessage}")
      Behaviors.stopped
    }

end MemoryAgentActor
