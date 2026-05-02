package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.agent.AgentCommand.*
import nebflow.core.*
import nebflow.core.compact.{CompactConfig, FullCompact}
import nebflow.core.tools.ToolRegistry
import nebflow.shared.*
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import org.apache.pekko.actor.typed.{ActorRef, Behavior, SupervisorStrategy}

/** Core agent actor — the heart of the multi-agent system.
  *
  * Message flow:
  *   UserInput → startLlm → LlmComplete → [executeTools → ToolsComplete → startLlm → ...] → idle
  */
object AgentActor:

  private val MaxStashCapacity = 100
  private val MaxDepth = 5

  def apply(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]] = None
  ): Behavior[AgentCommand] =
    Behaviors.supervise(
      Behaviors.withStash[AgentCommand](MaxStashCapacity) { stash =>
        Behaviors.setup[AgentCommand] { context =>
          idle(agentDef, resources, depth, parentRef, AgentState(
            messages = Nil, status = AgentStatus.Idle, depth = depth,
            subagents = Map.empty, activeStreamFiber = None
          ), stash, context)
        }
      }
    ).onFailure[Exception](SupervisorStrategy.restart.withLimit(2, java.time.Duration.ofSeconds(30)))

  // ============================================================
  // Idle state
  // ============================================================

  private def idle(
    agentDef: AgentDef, resources: SharedResources, depth: Int,
    parentRef: Option[ActorRef[AgentCommand]], state: AgentState,
    stash: StashBuffer[AgentCommand], ctx: ActorContext[AgentCommand]
  ): Behavior[AgentCommand] =
    Behaviors.receiveMessage:
      case AgentCommand.UserInput(text, replyTo) =>
        val userMsg = Message(MessageRole.User, Left(text))
        val newMessages = state.messages :+ userMsg
        pipeLlmCall(agentDef, resources, depth, parentRef,
          state.copy(messages = newMessages), stash, ctx, replyTo)

      case AgentCommand.Stop(_) =>
        state.subagents.values.foreach(ctx.stop)
        Behaviors.stopped

      case msg =>
        stash.stash(msg)
        Behaviors.same

  // ============================================================
  // Processing state — LLM or tools in flight
  // ============================================================

  private def processing(
    agentDef: AgentDef, resources: SharedResources, depth: Int,
    parentRef: Option[ActorRef[AgentCommand]], state: AgentState,
    stash: StashBuffer[AgentCommand], ctx: ActorContext[AgentCommand]
  ): Behavior[AgentCommand] =
    Behaviors.receiveMessage:

      // --- LLM completed ---
      case LlmComplete(result, replyTo) =>
        val shouldCompact = result.usage.exists { u =>
          u.inputTokens.toFloat / agentDef.contextWindow > 0.80f
        }

        if shouldCompact then
          val compactIO = FullCompact.compact(
            state.messages, resources.llm, CompactConfig.forContextWindow(agentDef.contextWindow),
            resources.projectRoot.toString
          )
          resources.dispatcher.unsafeRunAndForget(
            compactIO.flatMap {
              case Right(compacted) =>
                IO(ctx.self ! AgentCommand.CompactionDone(compacted, result, replyTo))
              case Left(err) =>
                NebflowLogger.forName("nebflow.agent").warn(s"Agent auto-compact failed: $err")
                // Compact failed, continue normally with original messages
                IO(ctx.self ! AgentCommand.LlmComplete(result, replyTo))
            }
          )
          Behaviors.same // Wait for CompactionDone or fallback LlmComplete

        else
          if result.toolCalls.isEmpty then
            finishTurn(agentDef, resources, depth, parentRef, state, stash, ctx, result.text, replyTo)
          else
            pipeToolExecutions(agentDef, resources, depth, parentRef, state, stash, ctx, result, replyTo)

      case LlmFailed(error, replyTo) =>
        val agentError = AgentError(ctx.self.path.name, agentDef.name, depth, AgentErrorType.LlmFailed, error.getMessage)
        parentRef match
          case Some(p) => p ! AgentCommand.DelegateResult(ctx.self.path.name, Left(agentError))
          case None => emitStream(resources, ctx, AgentStreamEvent.Done)
        replyTo.foreach(_ ! AgentEvent.Failed(agentError))
        stash.unstashAll(idle(agentDef, resources, depth, parentRef,
          state.copy(status = AgentStatus.Error(error.getMessage)), stash, ctx))

      // --- Auto-compact completed ---
      case AgentCommand.CompactionDone(compactedMessages, originalResult, replyTo) =>
        val newState = state.copy(messages = compactedMessages)
        if originalResult.toolCalls.isEmpty then
          finishTurn(agentDef, resources, depth, parentRef, newState, stash, ctx, originalResult.text, replyTo)
        else
          pipeToolExecutions(agentDef, resources, depth, parentRef, newState, stash, ctx, originalResult, replyTo)

      // --- Tools completed ---
      case ToolsComplete(results, originalText, replyTo) =>
        val toolCalls = results.map((call, _) => call)
        val assistantMsg = Message(MessageRole.Assistant, Right(
          (if originalText.nonEmpty then List(ContentBlock.Text(originalText)) else Nil) ++
            toolCalls.map(tc => ContentBlock.ToolUse(tc.id, tc.name, tc.input))
        ))
        val resultBlocks = results.map { (call, r) =>
          ContentBlock.ToolResult(call.id, r.content, Some(r.isError))
        }
        val resultMsg = Message(MessageRole.User, Right(resultBlocks))
        val newMessages = state.messages ++ List(assistantMsg, resultMsg)

        // Loop: start next LLM call with updated messages
        pipeLlmCall(agentDef, resources, depth, parentRef,
          state.copy(messages = newMessages), stash, ctx, replyTo)

      // --- Subagent stream forwarding ---
      case AgentCommand.SubagentStreamEvent(subId, event) =>
        emitStream(resources, ctx, event)
        Behaviors.same

      // --- Subagent returned result ---
      case AgentCommand.DelegateResult(subId, result) =>
        state.subagents.get(subId).foreach(ctx.unwatch)
        emitStream(resources, ctx, AgentStreamEvent.AgentEnd(subId))
        val resultText = result match
          case Right(text) => s"[Subagent $subId completed]: $text"
          case Left(err) => s"[Subagent $subId failed]: ${err.message}"
        processing(agentDef, resources, depth, parentRef,
          state.copy(
            subagents = state.subagents - subId,
            messages = state.messages :+ Message(MessageRole.User, Left(resultText))
          ), stash, ctx)

      // --- Interrupt ---
      case AgentCommand.Interrupt() =>
        state.activeStreamFiber.foreach(f => resources.dispatcher.unsafeRunAndForget(f.cancel))
        state.subagents.values.foreach(_ ! AgentCommand.Interrupt())
        emitStream(resources, ctx, AgentStreamEvent.Done)
        stash.unstashAll(idle(agentDef, resources, depth, parentRef,
          state.copy(status = AgentStatus.Idle, activeStreamFiber = None, subagents = Map.empty), stash, ctx))

      // --- Subagent definition loaded (piped from IO) — spawn on actor thread ---
      case AgentCommand.SubagentDefLoaded(call, agentName, task, defnOpt, subDepth) =>
        defnOpt match
          case Some(subDef) =>
            val subId = s"${agentName}-${java.util.UUID.randomUUID().toString.take(8)}"
            val subActor = ctx.spawn(
              AgentActor(subDef, resources, subDepth, Some(ctx.self)),
              subId
            )
            ctx.watchWith(subActor, AgentCommand.DelegateResult(subId, Left(
              AgentError(subId, subDef.name, subDepth, AgentErrorType.Interrupted, "Actor terminated unexpectedly")
            )))
            subActor ! AgentCommand.UserInput(task, None)
            emitStream(resources, ctx, AgentStreamEvent.AgentStart(subDef.name, subDef.description))
          case None =>
            emitStream(resources, ctx, AgentStreamEvent.ToolEnd(
              s"Delegate($agentName)", s"Subagent definition not found: $agentName", isError = true))
        Behaviors.same

      case AgentCommand.Stop(_) =>
        state.activeStreamFiber.foreach(f => resources.dispatcher.unsafeRunAndForget(f.cancel))
        state.subagents.values.foreach(ctx.stop)
        Behaviors.stopped

      case msg =>
        stash.stash(msg)
        Behaviors.same

  // ============================================================
  // Start LLM call → pipe result back to self
  // ============================================================

  private def pipeLlmCall(
    agentDef: AgentDef, resources: SharedResources, depth: Int,
    parentRef: Option[ActorRef[AgentCommand]], state: AgentState,
    stash: StashBuffer[AgentCommand], ctx: ActorContext[AgentCommand],
    replyTo: Option[ActorRef[AgentEvent]]
  ): Behavior[AgentCommand] =
    emitStream(resources, ctx, AgentStreamEvent.AgentStart(agentDef.name, agentDef.description))

    val systemPrompt = buildSystemPrompt(agentDef, resources)
    val tools = buildToolList(agentDef, depth, parentRef.isDefined)
    val messagesWithSystem = Message(MessageRole.System, Left(systemPrompt)) :: state.messages
    val request = LlmRequest(
      messages = messagesWithSystem,
      sessionId = "agent",
      agentId = agentDef.name,
      tools = tools,
      maxTokens = Some(agentDef.maxTokens)
    )

    val io = resources.llm.sendStream(request)
      .through(streamEmitter(resources, ctx))
      .compile
      .toList
      .map(aggregateChunks)
      .attempt
      .flatMap {
        case Right(result) => IO(ctx.self ! LlmComplete(result, replyTo))
        case Left(e) => IO(ctx.self ! LlmFailed(e, replyTo))
      }

    resources.dispatcher.unsafeRunAndForget(io)
    processing(agentDef, resources, depth, parentRef, state, stash, ctx)

  // ============================================================
  // Execute tools → pipe results back to self
  // ============================================================

  private def pipeToolExecutions(
    agentDef: AgentDef, resources: SharedResources, depth: Int,
    parentRef: Option[ActorRef[AgentCommand]], state: AgentState,
    stash: StashBuffer[AgentCommand], ctx: ActorContext[AgentCommand],
    result: ConsumeResult, replyTo: Option[ActorRef[AgentEvent]]
  ): Behavior[AgentCommand] =
    val allowedTools = agentDef.tools.toSet
    val filteredCalls = if allowedTools.isEmpty then result.toolCalls
      else result.toolCalls.filter(tc => allowedTools.contains(tc.name))

    val io = filteredCalls.traverse { call =>
      emitStreamIO(resources, ctx,
        AgentStreamEvent.ToolStart(nebflow.core.summarizeToolCall(call))) *>
        (call.name match
          case "delegate" =>
            handleDelegate(call, agentDef, resources, depth, parentRef, state, ctx)
          case "report" =>
            handleReport(call, agentDef, parentRef, ctx)
          case "ask_parent" =>
            handleAskParent(call, parentRef, ctx)
          case _ =>
            executeTool(
              call, resources.projectRoot.toString, Some(resources.llm), None, None, None, None, None
            )
        ).map(r => (call, r)).attempt.map {
          case Right(pair) => pair
          case Left(e) => (call, ToolExecResult(s"Tool error: ${e.getMessage}", isError = true))
        }.flatTap { (call, r) =>
          val summary = summarizeToolResult(call, r.content)
          emitStreamIO(resources, ctx,
            AgentStreamEvent.ToolEnd(nebflow.core.summarizeToolCall(call), summary, r.isError))
        }
    }.flatMap { results =>
      IO(ctx.self ! ToolsComplete(results, result.text, replyTo))
    }

    resources.dispatcher.unsafeRunAndForget(io)
    processing(agentDef, resources, depth, parentRef, state, stash, ctx)

  // ============================================================
  // Synthetic tool handlers
  // ============================================================

  private def handleDelegate(
    call: ToolCall, agentDef: AgentDef, resources: SharedResources,
    depth: Int, parentRef: Option[ActorRef[AgentCommand]], state: AgentState,
    ctx: ActorContext[AgentCommand]
  ): IO[ToolExecResult] =
    if depth >= MaxDepth then
      IO.pure(ToolExecResult(s"Cannot delegate: maximum depth ($MaxDepth) exceeded", isError = true))
    else
      val agentName = call.input("agent").flatMap(_.asString).getOrElse("")
      val task = call.input("task").flatMap(_.asString).getOrElse("")
      agentDef.subagents.find(_.name == agentName) match
        case None =>
          IO.pure(ToolExecResult(s"Unknown subagent: '$agentName'. Available: ${agentDef.subagents.map(_.name).mkString(", ")}", isError = true))
        case Some(slot) =>
          // Load definition via IO, pipe result back to self so ctx.spawn runs on actor thread
          resources.agentLibrary.get(slot.agent).flatMap { defnOpt =>
            IO(ctx.self ! AgentCommand.SubagentDefLoaded(call, agentName, task, defnOpt, depth + 1))
          }.as(ToolExecResult(s"Delegated to $agentName: ${task.take(100)}"))

  private def handleReport(
    call: ToolCall, agentDef: AgentDef,
    parentRef: Option[ActorRef[AgentCommand]], ctx: ActorContext[AgentCommand]
  ): IO[ToolExecResult] =
    parentRef match
      case None =>
        IO.pure(ToolExecResult("Cannot report: no parent agent", isError = true))
      case Some(parent) =>
        val message = call.input("message").flatMap(_.asString).getOrElse("")
        parent ! AgentCommand.DelegateResult(ctx.self.path.name, Right(message))
        IO.pure(ToolExecResult("Reported to parent"))

  private def handleAskParent(
    call: ToolCall,
    parentRef: Option[ActorRef[AgentCommand]], ctx: ActorContext[AgentCommand]
  ): IO[ToolExecResult] =
    parentRef match
      case None =>
        IO.pure(ToolExecResult("Cannot ask_parent: no parent agent", isError = true))
      case Some(parent) =>
        val question = call.input("question").flatMap(_.asString).getOrElse("")
        // Use ask pattern — block until parent answers
        import scala.concurrent.ExecutionContext.Implicits.global
        implicit val scheduler: org.apache.pekko.actor.typed.Scheduler = ctx.system.scheduler
        implicit val askTimeout: org.apache.pekko.util.Timeout = org.apache.pekko.util.Timeout(scala.concurrent.duration.Duration(60, "seconds"))
        IO.fromFuture(IO {
          parent.ask[ParentAnswer](replyTo => AgentCommand.SubagentQuestion(ctx.self.path.name, question, replyTo))
        }).map(answer => ToolExecResult(answer.answer))
          .handleError(e => ToolExecResult(s"Ask parent failed: ${e.getMessage}", isError = true))

  // ============================================================
  // Finish turn — emit done, return to idle
  // ============================================================

  private def finishTurn(
    agentDef: AgentDef, resources: SharedResources, depth: Int,
    parentRef: Option[ActorRef[AgentCommand]], state: AgentState,
    stash: StashBuffer[AgentCommand], ctx: ActorContext[AgentCommand],
    text: String, replyTo: Option[ActorRef[AgentEvent]]
  ): Behavior[AgentCommand] =
    emitStream(resources, ctx, AgentStreamEvent.Done)
    parentRef match
      case Some(parent) =>
        parent ! AgentCommand.DelegateResult(ctx.self.path.name, Right(text))
        Behaviors.stopped
      case None =>
        replyTo.foreach(_ ! AgentEvent.Completed(text))
        val newMessages = state.messages :+ Message(MessageRole.Assistant, Left(text))
        stash.unstashAll(idle(agentDef, resources, depth, parentRef,
          state.copy(messages = newMessages, status = AgentStatus.Idle), stash, ctx))

  // ============================================================
  // Stream helpers
  // ============================================================

  private def emitStream(resources: SharedResources, ctx: ActorContext[?], event: AgentStreamEvent): Unit =
    resources.dispatcher.unsafeRunAndForget(
      resources.wsSend(event.toJson(ctx.self.path.name))
    )

  private def emitStreamIO(resources: SharedResources, ctx: ActorContext[?], event: AgentStreamEvent): IO[Unit] =
    resources.wsSend(event.toJson(ctx.self.path.name))

  /** Emit streaming events as chunks arrive. */
  private def streamEmitter(resources: SharedResources, ctx: ActorContext[AgentCommand]): fs2.Pipe[IO, StreamChunk, StreamChunk] =
    stream => stream.evalTap {
      case StreamChunk.TextDelta(delta) if delta.nonEmpty =>
        resources.wsSend(AgentStreamEvent.TextDelta(delta).toJson(ctx.self.path.name))
      case StreamChunk.ToolCallChunk(tc) =>
        resources.wsSend(AgentStreamEvent.ToolStart(nebflow.core.summarizeToolCall(tc)).toJson(ctx.self.path.name))
      case _ => IO.unit
    }

  private def aggregateChunks(chunks: List[StreamChunk]): ConsumeResult =
    val text = chunks.collect { case StreamChunk.TextDelta(d) => d }.mkString
    val toolCalls = chunks.collect { case StreamChunk.ToolCallChunk(tc) => tc }
    val usage = chunks.collectFirst { case StreamChunk.Done(_, u, _) => u }.flatten
    val stopReason = chunks.collectFirst { case StreamChunk.Done(sr, _, _) => sr }.flatten
    ConsumeResult(text, toolCalls, Nil, stopReason, usage)

  // ============================================================
  // Prompt / tool helpers
  // ============================================================

  private def buildSystemPrompt(agentDef: AgentDef, resources: SharedResources): String =
    if agentDef.systemPrompt.nonEmpty then agentDef.systemPrompt
    else Repl.loadSystemPrompt() + "\n\n" + Repl.buildEnvInfo(resources.projectRoot.toString)

  private def buildToolList(agentDef: AgentDef, depth: Int, hasParent: Boolean): Option[List[ToolDefinition]] =
    val base = if agentDef.tools.nonEmpty then
      ToolRegistry.ALL_TOOLS.filter(t => agentDef.tools.contains(t.name))
    else ToolRegistry.ALL_TOOLS
    val synthetic = List.newBuilder[ToolDefinition]
    // delegate — only if this agent has subagent slots
    if agentDef.subagents.nonEmpty then
      synthetic += ToolDefinition(
        name = "delegate",
        description = s"Delegate a task to a sub-agent. Available agents: ${agentDef.subagents.map(s => s"${s.name} (${s.agent})").mkString(", ")}",
        inputSchema = JsonObject.fromIterable(List(
          "type" -> Json.fromString("object"),
          "properties" -> Json.fromFields(List(
            "agent" -> Json.fromFields(List("type" -> Json.fromString("string"), "description" -> Json.fromString("Name of the sub-agent to delegate to"))),
            "task" -> Json.fromFields(List("type" -> Json.fromString("string"), "description" -> Json.fromString("The task description to delegate")))
          )),
          "required" -> Json.fromValues(List(Json.fromString("agent"), Json.fromString("task")))
        ))
      )
    // report — only for subagents (hasParent)
    if hasParent then
      synthetic += ToolDefinition(
        name = "report",
        description = "Report a result or message back to the parent agent that delegated to you. Use this when you have completed your assigned task.",
        inputSchema = JsonObject.fromIterable(List(
          "type" -> Json.fromString("object"),
          "properties" -> Json.fromFields(List(
            "message" -> Json.fromFields(List("type" -> Json.fromString("string"), "description" -> Json.fromString("The result or message to report")))
          )),
          "required" -> Json.fromValues(List(Json.fromString("message")))
        ))
      )
      // ask_parent — only for subagents
      synthetic += ToolDefinition(
        name = "ask_parent",
        description = "Ask the parent agent a question and wait for a response. Use this when you need clarification or information from the parent.",
        inputSchema = JsonObject.fromIterable(List(
          "type" -> Json.fromString("object"),
          "properties" -> Json.fromFields(List(
            "question" -> Json.fromFields(List("type" -> Json.fromString("string"), "description" -> Json.fromString("The question to ask the parent")))
          )),
          "required" -> Json.fromValues(List(Json.fromString("question")))
        ))
      )
    Some(base ++ synthetic.result())

  private def summarizeToolResult(call: ToolCall, result: String): String =
    nebflow.core.summarizeToolResult(call, result)

end AgentActor
