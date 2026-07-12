package nebflow.core.tools

import cats.effect.{Deferred, IO}
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.NebflowLogger
import nebflow.shared.{Message, MessageRole}

import scala.concurrent.duration.*

/**
 * DelegateTool — lets an agent spawn a sub-agent for a subtask.
 *
 * Two modes:
 *   - **Synchronous** (default): blocks until the sub-agent finishes, returns
 *     the sub-agent's output as the tool result.  The LLM cannot race ahead.
 *   - **Background** (`run_in_background: true`): returns immediately.  The
 *     sub-agent's result arrives later via ExternalEvent.  The tool result
 *     includes an anti-duplication instruction so the LLM doesn't compete
 *     with the sub-agent.
 *
 * Multiple Delegate calls in one LLM response run in parallel via parTraverse.
 *
 * Depth limit: when depth >= MaxDepth, the tool is filtered out by
 * buildToolList in AgentCore, preventing infinite recursion.
 */
object DelegateTool extends Tool:
  private val logger = NebflowLogger(getClass)

  /**
   * Wraps wsSend so that the parent session's sessionId is injected into every
   * event JSON. This lets the frontend route sub-agent events to the correct
   * window (primary or secondary) without modifying the sub-agent's internal
   * state — the sub-agent still sees sessionId=None for session-busy / error
   * logic, but its streaming events carry the parent's sessionId for display.
   */
  private def routeWsSend(
    wsSend: Option[io.circe.Json => IO[Unit]],
    parentSessionId: Option[String]
  ): io.circe.Json => IO[Unit] =
    val base = wsSend.getOrElse((_: io.circe.Json) => IO.unit)
    parentSessionId match
      case Some(sid) => json => base(json.deepMerge(Json.obj("sessionId" -> sid.asJson)))
      case None => base

  /** Maximum sub-agent depth (matches AgentCore.MaxDepth). */
  val MaxDepth: Int = 5

  /** Sub-agent completion relies on the agent loop turn limit (natural termination). */

  val name = "Delegate"

  val description =
    """Delegate a subtask to a sub-agent. The sub-agent runs autonomously with its own context and tools, then returns its final result.

By default the tool call BLOCKS until the sub-agent completes — the result IS the sub-agent's output. Set run_in_background=true to return immediately and be notified later.

Available sub-agents (pass as agentName):
- "Explorer" — Code exploration and research. Use for: searching codebases, understanding architecture, finding relevant files, running git/test commands for investigation. Cannot modify files.
- "Planner" — Analyze requirements and create implementation plans. Use for: breaking down complex tasks, studying code before implementation, running git/test commands for analysis. Cannot modify files.
- "Nebula" — Full tool access (default). Use for: implementation tasks that require writing code.

Use Delegate when:
- A task can be broken into independent parts that benefit from focused context
- You need parallel research on different aspects of a problem
- A subtask requires deep focus without polluting your main conversation

Key rules:
- Every prompt must be self-contained (the sub-agent starts with a clean context by default).
- Set fork=true to pass your current conversation context to the sub-agent.
- State what "done" looks like (e.g. "Report findings — do not modify files").
- When using run_in_background=true: do NOT duplicate the sub-agent's work. Work on non-overlapping tasks, or briefly tell the user what you launched and end your response.

Do NOT use Delegate for:
- Trivial tasks you can handle directly with Read/Bash/etc.
- Sequential tasks where each step depends on the previous step's result."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "agentName" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Name of the agent definition to use (e.g. \"Explorer\", \"Planner\", \"Nebula\"). Defaults to \"Nebula\".".asJson,
          "default" -> "Nebula".asJson
        ),
        "prompt" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Self-contained task description for the sub-agent. Must include all context needed unless fork=true.".asJson
        ),
        "description" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Short label for the task (shown in UI).".asJson
        ),
        "fork" -> io.circe.Json.obj(
          "type" -> "boolean".asJson,
          "description" -> "If true, pass current conversation context to the sub-agent (enables prompt cache reuse, saves tokens). Default: false.".asJson,
          "default" -> false.asJson
        ),
        "run_in_background" -> io.circe.Json.obj(
          "type" -> "boolean".asJson,
          "description" -> "If true, return immediately and be notified when the sub-agent completes. Do NOT duplicate the sub-agent's work while waiting. Default: false.".asJson,
          "default" -> false.asJson
        ),
        "lifecycle" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "enum" -> io.circe.Json.arr("ephemeral".asJson, "persistent".asJson),
          "description" -> "ephemeral (default): sub-agent completes and exits. persistent: sub-agent stays alive after task completion, its address is added to your Active Sessions, and you can send follow-up messages via MailAgent.".asJson,
          "default" -> "ephemeral".asJson
        ),
        "taskDescription" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "For persistent mode: describes the ongoing task for the session list. Required when lifecycle=persistent.".asJson
        )
      ),
      "required" -> io.circe.Json.arr("prompt".asJson, "description".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val desc = input("description").flatMap(_.asString).getOrElse("")
    val agent = input("agentName").flatMap(_.asString).getOrElse("Nebula")
    val forked = input("fork").flatMap(_.asBoolean).getOrElse(false)
    val bg = input("run_in_background").flatMap(_.asBoolean).getOrElse(false)
    val tags = List(if forked then Some("fork") else None, if bg then Some("bg") else None).flatten
    if tags.nonEmpty then s"Delegate($agent: $desc [${tags.mkString(", ")}])"
    else s"Delegate($agent: $desc)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 200 then result.take(197) + "..." else result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val prompt = input("prompt").flatMap(_.asString).getOrElse("")
    val description = input("description").flatMap(_.asString).getOrElse("subtask")
    val agentName = input("agentName").flatMap(_.asString).getOrElse("Nebula")
    val fork = input("fork").flatMap(_.asBoolean).getOrElse(false)
    val runInBackground = input("run_in_background").flatMap(_.asBoolean).getOrElse(false)
    val lifecycle = input("lifecycle").flatMap(_.asString).getOrElse("ephemeral")
    val taskDescription = input("taskDescription").flatMap(_.asString).getOrElse(description)

    if prompt.trim.isEmpty then IO.pure(Left(ToolError("Missing required parameter: prompt")))
    else if ctx.depth >= MaxDepth then
      IO.pure(Left(ToolError(s"Maximum sub-agent depth ($MaxDepth) reached. Cannot delegate further.")))
    else
      (ctx.actorSystem, ctx.sharedResources, ctx.agentLibrary) match
        case (Some(system), Some(resources), Some(agentLibrary)) =>
          val adjustedPrompt =
            if fork then
              s"""<system-reminder>
You are a sub-agent working on a delegated task. Your parent agent has forked this conversation to give you background context.

Focus ONLY on the specific task described below. Do not work on other topics from the conversation history — those are your parent's responsibilities.
</system-reminder>

$prompt"""
            else prompt

          agentLibrary.get(agentName).flatMap {
            case None =>
              IO.pure(Left(ToolError(s"Agent '$agentName' not found in agent library")))
            case Some(agentDef) =>
              // Query parent session's bypass status so sub-agent inherits it
              val bypassIO = (ctx.sessionStore, ctx.sessionId) match
                case (Some(store), Some(sid)) => store.getBypass(sid)
                case _ => IO.pure(false)
              bypassIO.flatMap { bypass =>
                if lifecycle == "persistent" then
                  spawnPersistent(
                    agentDef = agentDef,
                    prompt = adjustedPrompt,
                    description = description,
                    taskDescription = taskDescription,
                    agentName = agentName,
                    initialMessages = if fork then ctx.messages else Nil,
                    system = system,
                    resources = resources,
                    parentDepth = ctx.depth,
                    parentRef = ctx.agentActorRef,
                    wsSend = ctx.wsSend,
                    projectRoot = ctx.projectRoot,
                    parentSessionId = ctx.sessionId,
                    bypass = bypass
                  )
                else if runInBackground then
                  spawnBackground(
                    agentDef = agentDef,
                    prompt = adjustedPrompt,
                    description = description,
                    agentName = agentName,
                    initialMessages = if fork then ctx.messages else Nil,
                    system = system,
                    resources = resources,
                    parentDepth = ctx.depth,
                    parentRef = ctx.agentActorRef,
                    wsSend = ctx.wsSend,
                    projectRoot = ctx.projectRoot,
                    parentSessionId = ctx.sessionId,
                    bypass = bypass
                  )
                else
                  spawnSync(
                    agentDef = agentDef,
                    prompt = adjustedPrompt,
                    description = description,
                    agentName = agentName,
                    initialMessages = if fork then ctx.messages else Nil,
                    system = system,
                    resources = resources,
                    parentDepth = ctx.depth,
                    parentRef = ctx.agentActorRef,
                    wsSend = ctx.wsSend,
                    projectRoot = ctx.projectRoot,
                    parentSessionId = ctx.sessionId,
                    bypass = bypass
                  )
              }
          }
        case _ =>
          IO.pure(Left(ToolError("Delegate requires ActorSystem, SharedResources, and agent library")))
    end if
  end call

  // ============================================================
  // Synchronous: block until sub-agent completes
  // ============================================================

  private def spawnSync(
    agentDef: AgentDef,
    prompt: String,
    description: String,
    agentName: String,
    initialMessages: List[Message],
    system: ActorSystem,
    resources: SharedResources,
    parentDepth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    wsSend: Option[io.circe.Json => IO[Unit]],
    projectRoot: String,
    parentSessionId: Option[String] = None,
    bypass: Boolean = false
  ): IO[Either[ToolError, String]] =
    for
      resultDeferred <- Deferred[IO, Either[ToolError, String]]
      readTracker <- ReadTracker.create
      fileHistory <- FileHistory.create()
      childDepth = parentDepth + 1
      subagentId = s"delegate-${agentName}-${java.util.UUID.randomUUID().toString.take(8)}"
      childWsSend = routeWsSend(wsSend, parentSessionId)
      subagentRef <- system.spawn(
        AgentActor(
          agentDef = agentDef,
          resources = resources,
          wsSend = childWsSend,
          depth = childDepth,
          parentRef = parentRef,
          sessionId = parentSessionId,
          sessionName = Some(description),
          initialMessages = initialMessages,
          readTracker = Some(readTracker),
          fileHistory = Some(fileHistory),
          contextWindow = resources.contextWindow,
          projectRoot = Some(projectRoot),
          bypass = bypass
        ),
        subagentId
      )
      adapterRef <- system.spawn(
        guardedAdapter(subagentRef, agentName, resources, resultDeferred, DelegateTimeout, prompt, MaxDelegateRetries),
        s"$subagentId-adapter"
      )
      _ = logger.info(s"Spawned sync sub-agent: $subagentId (depth=$childDepth, agent=$agentName)")
      _ <- subagentRef ! AgentCommand.UserInput(prompt, Some(adapterRef))
      result <- resultDeferred.get
    yield result

  /** Timeout for sync delegation — generous to allow LLM retries (including Mac sleep/wake recovery). */
  private val DelegateTimeout: FiniteDuration = 30.minutes

  /** Max retry attempts for sync delegation before giving up. */
  private val MaxDelegateRetries: Int = 2

  /** Backoff between retry attempts (gives sub-agent time to reset). */
  private val RetryBackoff: FiniteDuration = 3.seconds

  /**
   * Guarded adapter — ensures the parent ALWAYS gets a result, with actor-level
   * state recovery retry on timeout.
   *
   * Four-layer defense:
   *   1. Normal completion: sub-agent sends Completed/Failed → complete Deferred
   *   2. Death watch: sub-agent's Fiber terminates → complete Deferred with error
   *   3. Timeout + retry: no response within timeout → send Retry message
   *      Sub-agent cancels current work and re-dispatches from last checkpoint
   *      (LLM call or tool execution), preserving full conversation history.
   *   4. Retry exhausted: complete Deferred with timeout error, stop sub-agent
   *
   * Uses complete (first wins) so all layers can fire safely.
   */
  private def guardedAdapter(
    subagentRef: ActorRef[AgentCommand],
    agentName: String,
    resources: SharedResources,
    resultDeferred: Deferred[IO, Either[ToolError, String]],
    timeout: FiniteDuration,
    originalPrompt: String,
    maxRetries: Int
  ): Behavior[AgentEvent] =
    Behaviors.setup { ctx =>
      given system: ActorSystem = ctx.system

      /** Recursive timeout fiber: on timeout, either retry or give up. */
      def timeoutLoop(retriesLeft: Int): IO[Unit] =
        IO.sleep(timeout) *>
          resultDeferred.complete(Left(ToolError(""))).flatMap { weSetIt =>
            if !weSetIt then IO.unit // Deferred already completed by normal path or death watch
            else if retriesLeft > 0 then
              val attempt = maxRetries - retriesLeft + 1
              logger.info(s"Sub-agent '$agentName' timeout, retrying (attempt $attempt/$maxRetries)") *>
                // Send Retry — sub-agent cancels current work and re-dispatches
                // from last checkpoint (LLM call or tool execution). Conversation
                // history is preserved; no new UserInput needed.
                (subagentRef ! AgentCommand.Retry(s"timeout-attempt-$attempt")) *>
                timeoutLoop(retriesLeft - 1)
            else
              logger.warn(s"Sub-agent '$agentName' timed out, no retries left") *>
                resultDeferred
                  .complete(
                    Left(
                      ToolError(
                        s"Sub-agent '$agentName' timed out after ${timeout.toMinutes}m ($maxRetries retries exhausted)"
                      )
                    )
                  )
                  .void *>
                system.stop(subagentRef).handleErrorWith(_ => IO.unit)
          }

      ctx.watch(subagentRef) *>
        ctx.forkTurn(timeoutLoop(maxRetries)) *>
        IO.pure(
          // Layer 1: normal message handler
          new Behavior[AgentEvent]:
            def receive(ctx: ActorContext[AgentEvent], event: AgentEvent): IO[Behavior[AgentEvent]] =
              val result: Either[ToolError, String] = event match
                case AgentEvent.Completed(_, messages) =>
                  val text = extractLastAssistantText(messages)
                  Right(if text.nonEmpty then text else "(sub-agent produced no text output)")
                case AgentEvent.Failed(_, error) =>
                  Left(ToolError(s"Sub-agent '$agentName' failed: ${error.message}"))

              (resultDeferred.complete(result).void *>
                system.stop(subagentRef) *>
                IO.pure(Behaviors.stopped[AgentEvent]))
                .handleErrorWith(_ => IO.pure(Behaviors.stopped[AgentEvent]))

            override def onSignal(ctx: ActorContext[AgentEvent], signal: SystemSignal): IO[Behavior[AgentEvent]] =
              signal match
                case SystemSignal.Terminated(_) =>
                  (resultDeferred
                    .complete(Left(ToolError(s"Sub-agent '$agentName' terminated unexpectedly (crashed)")))
                    .void *>
                    IO.pure(Behaviors.stopped[AgentEvent]))
                    .handleErrorWith(_ => IO.pure(Behaviors.stopped[AgentEvent]))

            override def onStop(ctx: ActorContext[AgentEvent]): IO[Unit] =
              resultDeferred
                .complete(Left(ToolError(s"Adapter for '$agentName' stopped unexpectedly")))
                .void
                .handleErrorWith(_ => IO.unit)
        )
    }

  // ============================================================
  // Background: return immediately, deliver result via ExternalEvent
  // ============================================================

  private def spawnBackground(
    agentDef: AgentDef,
    prompt: String,
    description: String,
    agentName: String,
    initialMessages: List[Message],
    system: ActorSystem,
    resources: SharedResources,
    parentDepth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    wsSend: Option[io.circe.Json => IO[Unit]],
    projectRoot: String,
    parentSessionId: Option[String] = None,
    bypass: Boolean = false
  ): IO[Either[ToolError, String]] =
    for
      readTracker <- ReadTracker.create
      fileHistory <- FileHistory.create()
      childDepth = parentDepth + 1
      subagentId = s"delegate-${agentName}-${java.util.UUID.randomUUID().toString.take(8)}"
      childWsSend = routeWsSend(wsSend, parentSessionId)
      subagentRef <- system.spawn(
        AgentActor(
          agentDef = agentDef,
          resources = resources,
          wsSend = childWsSend,
          depth = childDepth,
          parentRef = parentRef,
          sessionId = parentSessionId,
          sessionName = Some(description),
          initialMessages = initialMessages,
          readTracker = Some(readTracker),
          fileHistory = Some(fileHistory),
          contextWindow = resources.contextWindow,
          projectRoot = Some(projectRoot),
          bypass = bypass
        ),
        subagentId
      )
      adapterRef <- system.spawn(
        backgroundAdapter(subagentRef, parentRef, description, agentName, subagentId, resources),
        s"$subagentId-adapter"
      )
      _ = logger.info(s"Spawned background sub-agent: $subagentId (depth=$childDepth, agent=$agentName)")
      _ <- subagentRef ! AgentCommand.UserInput(prompt, Some(adapterRef))
    yield Right(
      s"""Sub-agent '$agentName' started in background for: $description.
You will be notified when it completes via a system message.
Do NOT duplicate this agent's work — avoid working with the same files or topics it is using. Work on non-overlapping tasks, or briefly tell the user what you launched and end your response."""
    )

  /**
   * Background adapter: forwards result to parent via ExternalEvent, then cleans up.
   * Watches sub-agent for crashes — notifies parent if sub-agent dies unexpectedly.
   */
  private def backgroundAdapter(
    subagentRef: ActorRef[AgentCommand],
    parentRef: Option[ActorRef[AgentCommand]],
    description: String,
    agentName: String,
    subagentId: String,
    resources: SharedResources
  ): Behavior[AgentEvent] =
    Behaviors.setup { ctx =>
      ctx.watch(subagentRef)

      def notifyParentAndStop(eventType: String, payload: String): IO[Behavior[AgentEvent]] =
        val notify = parentRef match
          case Some(ref) =>
            ref ! AgentCommand.ExternalEvent(
              source = "delegate",
              eventType = eventType,
              payload = payload,
              metadata = JsonObject("description" -> description.asJson, "agentName" -> agentName.asJson),
              correlationId = Some(subagentId)
            )
          case None => IO.unit
        (notify *> (subagentRef ! AgentCommand.Stop("delegate-complete")) *>
          IO.pure(Behaviors.stopped[AgentEvent]))
          .handleErrorWith(_ => IO.pure(Behaviors.stopped[AgentEvent]))

      IO.pure(
        new Behavior[AgentEvent]:
          def receive(ctx: ActorContext[AgentEvent], event: AgentEvent): IO[Behavior[AgentEvent]] =
            val (eventType, payload) = event match
              case AgentEvent.Completed(_, messages) =>
                val text = extractLastAssistantText(messages)
                if text.nonEmpty then ("completed", s"[Sub-agent completed] \"$description\":\n$text")
                else ("completed", s"[Sub-agent completed] \"$description\" (no text output)")
              case AgentEvent.Failed(_, error) =>
                ("failed", s"[Sub-agent failed] \"$description\": ${error.message}")
            notifyParentAndStop(eventType, payload)

          override def onSignal(ctx: ActorContext[AgentEvent], signal: SystemSignal): IO[Behavior[AgentEvent]] =
            signal match
              case SystemSignal.Terminated(_) =>
                notifyParentAndStop("failed", s"[Sub-agent crashed] \"$description\": terminated unexpectedly")
      )
    }

  // ============================================================
  // Persistent: sub-agent stays alive, address returned to caller
  // ============================================================

  private def spawnPersistent(
    agentDef: AgentDef,
    prompt: String,
    description: String,
    taskDescription: String,
    agentName: String,
    initialMessages: List[Message],
    system: ActorSystem,
    resources: SharedResources,
    parentDepth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    wsSend: Option[io.circe.Json => IO[Unit]],
    projectRoot: String,
    parentSessionId: Option[String] = None,
    bypass: Boolean = false
  ): IO[Either[ToolError, String]] =
    for
      readTracker <- ReadTracker.create
      fileHistory <- FileHistory.create()
      childDepth = parentDepth + 1
      subagentId = s"delegate-${agentName}-${java.util.UUID.randomUUID().toString.take(8)}"
      childWsSend = routeWsSend(wsSend, parentSessionId)
      subagentRef <- system.spawn(
        AgentActor(
          agentDef = agentDef,
          resources = resources,
          wsSend = childWsSend,
          depth = childDepth,
          parentRef = parentRef,
          sessionId = parentSessionId,
          sessionName = Some(description),
          initialMessages = initialMessages,
          readTracker = Some(readTracker),
          fileHistory = Some(fileHistory),
          contextWindow = resources.contextWindow,
          projectRoot = Some(projectRoot),
          bypass = bypass
        ),
        subagentId
      )
      address = subagentRef.path.toString
      adapterRef <- system.spawn(
        persistentAdapter(subagentRef, parentRef, description, agentName, subagentId, address),
        s"$subagentId-adapter"
      )
      _ = logger.info(s"Spawned persistent sub-agent: $subagentId (depth=$childDepth, agent=$agentName, addr=$address)")
      _ <- parentRef.fold(IO.unit)(ref => ref ! AgentCommand.SessionStarted(address, agentName, taskDescription))
      _ <- subagentRef ! AgentCommand.UserInput(prompt, Some(adapterRef))
    yield Right(
      s"""Persistent session started: $agentName for: $taskDescription
Session address: $address
The sub-agent will stay alive after completing this task. You can send follow-up messages via Mail(address=$address, message=...).
You will be notified when the initial task completes."""
    )

  /**
   * Persistent adapter: forwards completion to parent but does NOT stop the sub-agent.
   * Watches sub-agent for crashes — notifies parent if persistent session dies.
   */
  private def persistentAdapter(
    subagentRef: ActorRef[AgentCommand],
    parentRef: Option[ActorRef[AgentCommand]],
    description: String,
    agentName: String,
    subagentId: String,
    address: String
  ): Behavior[AgentEvent] =
    Behaviors.setup { ctx =>
      ctx.watch(subagentRef)

      def notifyParentAndStop(eventType: String, payload: String, sessionStatus: String): IO[Behavior[AgentEvent]] =
        val actions = parentRef match
          case Some(ref) =>
            (ref ! AgentCommand.ExternalEvent(
              source = address,
              eventType = eventType,
              payload = payload,
              metadata = JsonObject("description" -> description.asJson, "agentName" -> agentName.asJson),
              correlationId = Some(subagentId)
            )) *> (ref ! AgentCommand.SessionUpdate(address, sessionStatus))
          case None => IO.unit
        // Adapter stops itself; sub-agent stays alive for future Mail messages
        // (unless Terminated — in that case sub-agent is already dead)
        (actions *> IO.pure(Behaviors.stopped[AgentEvent]))
          .handleErrorWith(_ => IO.pure(Behaviors.stopped[AgentEvent]))
      end notifyParentAndStop

      IO.pure(
        new Behavior[AgentEvent]:
          def receive(ctx: ActorContext[AgentEvent], event: AgentEvent): IO[Behavior[AgentEvent]] =
            val (eventType, payload, sessionStatus) = event match
              case AgentEvent.Completed(_, messages) =>
                val text = extractLastAssistantText(messages)
                val p =
                  if text.nonEmpty then s"[Session update] \"$description\":\n$text"
                  else s"[Session update] \"$description\" (task complete, awaiting instructions)"
                ("completed", p, "idle (awaiting instructions)")
              case AgentEvent.Failed(_, error) =>
                ("failed", s"[Session error] \"$description\": ${error.message}", "failed")
            notifyParentAndStop(eventType, payload, sessionStatus)

          override def onSignal(ctx: ActorContext[AgentEvent], signal: SystemSignal): IO[Behavior[AgentEvent]] =
            signal match
              case SystemSignal.Terminated(_) =>
                // Persistent session died — notify parent, adapter stops
                notifyParentAndStop(
                  "failed",
                  s"[Session crashed] \"$description\": persistent session terminated unexpectedly",
                  "crashed"
                )
      )
    }

  /** Extract the last assistant message text from a list of messages. */
  private def extractLastAssistantText(messages: List[Message]): String =
    messages.reverse
      .collectFirst {
        case msg if msg.role == MessageRole.Assistant => msg.textContent
      }
      .filter(_.nonEmpty)
      .getOrElse("")

end DelegateTool
