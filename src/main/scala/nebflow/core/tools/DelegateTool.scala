package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.NebflowLogger
import nebflow.shared.{Message, MessageRole}

/**
 * DelegateTool — Nebula/调度器专用 sub-agent delegation.
 *
 * Only available to the Nebula root agent (NebulaExclusiveTools). Lets Nebula
 * spawn a sub-agent for a subtask, either targeting a standalone agent or
 * triggering a flow pipeline. Team members delegate via SubTaskTool instead
 * (self-clone + ephemeral worker, no Mail identity).
 *
 * Two modes:
 *   - **Background** (default): returns immediately. The sub-agent's result
 *     arrives later via ExternalEvent. The tool result includes an
 *     anti-duplication instruction so the LLM doesn't compete with the
 *     sub-agent.
 *   - **Persistent** (`lifecycle: "persistent"`): sub-agent stays alive after
 *     task completion, returns actor address for follow-up Mail communication.
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
    parentSessionId: Option[String],
    subagentId: String
  ): io.circe.Json => IO[Unit] =
    val base = wsSend.getOrElse((_: io.circe.Json) => IO.unit)
    parentSessionId match
      case Some(sid) => json =>
        // Carry the original parent sessionId as rootSessionId so nested
        // delegates (child → grandchild) still index against the top-level
        // main session on the frontend. routeWsSend wrappers compose such that
        // the wrapper created first (closest to the root session) executes
        // last, so overwriting here always yields the true root session id.
        // JsonObject.add is O(1) per field, replacing the O(n) deepMerge chain.
        json.asObject match
          case Some(obj) =>
            val builder = obj.add("rootSessionId", sid.asJson).add("sessionId", sid.asJson)
            // Preserve an existing nodeSessionId: for nested delegates, toJson
            // already stamped the grandchild's own nodeSessionId — overwriting it
            // would route grandchild events into the child's popup.
            val finalObj =
              if obj.contains("nodeSessionId") then builder
              else builder.add("nodeSessionId", subagentId.asJson)
            base(Json.fromJsonObject(finalObj))
          case None => base(json)
      case None => json =>
        json.asObject match
          case Some(obj) => base(Json.fromJsonObject(obj.add("nodeSessionId", subagentId.asJson)))
          case None => base(json)

  /** Maximum sub-agent depth (matches AgentCore.MaxDepth). */
  val MaxDepth: Int = 5

  /** Sub-agent completion relies on the agent loop turn limit (natural termination). */

  val name = "Delegate"

  val description =
    """Spawn a background sub-agent to work on a subtask while you continue your own work. The sub-agent shares your tools and system prompt, runs autonomously with its own context, and reports back when done.

Multiple Delegate calls in one response run concurrently — use this to parallelize independent work.

**When to use:**
- A task splits into independent parts (e.g. "implement the API and write its tests" → Delegate one, do the other)
- A new Mail arrives while you're mid-task → Delegate it instead of interrupting
- A subtask needs deep focus without cluttering your main context
- Trigger a flow pipeline: Delegate(flow="code-review", prompt="review branch feature-x")

**When NOT to use:**
- Steps depend on each other (Step B needs Step A's result) — do serially
- Trivial — faster to just do it yourself
- Subtasks touch the same files — conflict risk

**Targeting:**
- Default: spawns a copy of the calling agent (self-clone)
- With `agent` parameter: spawns the specified standalone agent (e.g. Coder, Explorer)
- With `flow` parameter: triggers a flow DAG pipeline (e.g. code-review)
- `agent` and `flow` are mutually exclusive
- Cannot target team agents — those require Mail

**Rules:**
- Prompt must be self-contained (the sub-agent starts with a clean context). Set fork=true to pass your conversation history.
- State what "done" looks like (e.g. "Report findings — do not modify files").
- Do NOT duplicate the sub-agent's work — work on non-overlapping files or topics."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
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
        "lifecycle" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "enum" -> io.circe.Json.arr("ephemeral".asJson, "persistent".asJson),
          "description" -> "ephemeral (default): sub-agent completes and exits. persistent: sub-agent stays alive after task completion, its address is added to your Active Sessions, and you can send follow-up messages via MailAgent.".asJson,
          "default" -> "ephemeral".asJson
        ),
        "taskDescription" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "For persistent mode: describes the ongoing task for the session list. Required when lifecycle=persistent.".asJson
        ),
        "agent" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Target standalone agent name (e.g. 'Coder', 'Explorer'). If omitted, clones the calling agent. Only standalone agents can be targeted.".asJson
        ),
        "flow" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Flow name to trigger (e.g. \"code-review\", \"release-beta\"). When specified, triggers a flow DAG pipeline. The flow result is delivered via ExternalEvent when complete. Cannot be combined with \"agent\" parameter.".asJson
        )
      ),
      "required" -> io.circe.Json.arr("prompt".asJson, "description".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val desc = input("description").flatMap(_.asString).getOrElse("")
    val forked = input("fork").flatMap(_.asBoolean).getOrElse(false)
    val agent = input("agent").flatMap(_.asString).getOrElse("")
    val flow = input("flow").flatMap(_.asString).getOrElse("")
    val target = if agent.nonEmpty then s"→ $agent" else if flow.nonEmpty then s"→ flow:$flow" else ""
    if forked then s"Delegate($desc [fork] $target)".trim
    else s"Delegate($desc $target)".trim

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 200 then result.take(197) + "..." else result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val prompt = input("prompt").flatMap(_.asString).getOrElse("")
    val description = input("description").flatMap(_.asString).getOrElse("subtask")
    val fork = input("fork").flatMap(_.asBoolean).getOrElse(false)
    val lifecycle = input("lifecycle").flatMap(_.asString).getOrElse("ephemeral")
    val taskDescription = input("taskDescription").flatMap(_.asString).getOrElse(description)
    val targetAgentName = input("agent").flatMap(_.asString).filter(_.nonEmpty)
    val flowName = input("flow").flatMap(_.asString).filter(_.nonEmpty)

    if prompt.trim.isEmpty then IO.pure(Left(ToolError("Missing required parameter: prompt")))
    else if flowName.isDefined && targetAgentName.isDefined then
      IO.pure(Left(ToolError("Cannot specify both 'agent' and 'flow' parameters")))
    else if flowName.isDefined then
      // Validate: agent must declare this flow in its flows list
      ctx.agentDef match
        case Some(ad) if !ad.flows.contains(flowName.get) =>
          val allowed = if ad.flows.isEmpty then "(none — no flows declared)" else ad.flows.mkString(", ")
          IO.pure(Left(ToolError(s"Flow '${flowName.get}' not allowed for agent '${ad.name}'. Allowed: $allowed")))
        case _ =>
      // Trigger flow via FlowDagRunner
      (ctx.sharedResources, ctx.actorSystem, ctx.agentActorRef) match
        case (Some(resources), Some(sys), Some(callerRef)) =>
          for
            // P2: resolve the caller's root session so flow nodes inherit the
            // same permission policy and render interactions in the Nebula window.
            callerRoot <- ctx.sessionId match
              case Some(sid) => resources.agentRegistry.get.map(_.get(sid).map(_.rootSessionId).filter(_.nonEmpty).getOrElse(sid))
              case None => IO.pure("")
            flowOpt <- nebflow.core.entity.EntityLoader.loadFlow(flowName.get)
            r <- flowOpt match
              case Some(flowDef) =>
                for
                  runnerRef <- sys.spawn(
                    nebflow.core.flow.FlowDagRunner(resources, ctx.wsSend),
                    s"dag-runner-${flowName.get.take(10)}-${System.currentTimeMillis().toString.takeRight(6)}"
                  )
                  _ <- (runnerRef ! nebflow.core.flow.FlowDagRunner.RunFlow(flowDef, prompt, callerRef, callerRoot)).void
                yield Right(s"Flow '${flowName.get}' started. Result will be delivered when complete.")
              case None =>
                IO.pure(Left(ToolError(s"Flow '${flowName.get}' not found")))
          yield r
        case _ =>
          IO.pure(Left(ToolError("Cannot start flow: missing resources")))
    else if ctx.depth >= MaxDepth then
      IO.pure(Left(ToolError(s"Maximum sub-agent depth ($MaxDepth) reached. Cannot delegate further.")))
    else
      // Resolve the effective agent def: either a target standalone agent
      // or a clone of the calling agent. Done before the ActorSystem check so
      // that invalid agent names are reported even without a live actor system.
      val resolveAgent: IO[Either[ToolError, (AgentDef, List[Message])]] =
        targetAgentName match
          case Some(agentName) =>
            ctx.agentLibrary match
              case Some(lib) =>
                lib.get(agentName).map {
                  case Some(targetDef) if targetDef.category == "standalone" =>
                    Right((targetDef, Nil)) // no fork for cross-agent
                  case Some(_) =>
                    Left(ToolError(s"'$agentName' is not a standalone agent"))
                  case None =>
                    Left(ToolError(s"Agent '$agentName' not found"))
                }
              case None =>
                IO.pure(Left(ToolError("No agent library available")))
          case None =>
            ctx.agentDef match
              case Some(parentAgentDef) =>
                IO.pure(Right((parentAgentDef, if fork then ctx.messages else Nil)))
              case None =>
                IO.pure(Left(ToolError("No agent definition available")))

      resolveAgent.flatMap {
        case Left(err) => IO.pure(Left(err))
        case Right((agentDef, initialMessages)) =>
          (ctx.actorSystem, ctx.sharedResources) match
            case (Some(system), Some(resources)) =>
              val agentName = agentDef.name
              val adjustedPrompt =
                if fork && targetAgentName.isEmpty then
                  s"""<system-reminder>
You are a sub-agent working on a delegated task. Your parent agent has forked this conversation to give you background context.

Focus ONLY on the specific task described below. Do not work on other topics from the conversation history — those are your parent's responsibilities.
</system-reminder>

$prompt"""
                else prompt

              // Query parent session's safety mode so sub-agent inherits it
              val safetyModeIO = (ctx.sessionStore, ctx.sessionId) match
                case (Some(store), Some(sid)) => store.getSafetyMode(sid)
                case _ => IO.pure("confirm-edits")
              // P2: resolve the caller's permission-policy bucket (root session)
              // so the delegate inherits the same policy as the caller — the
              // whole tree anchors to one rootSessionId.
              val callerRootIO = (ctx.sharedResources, ctx.sessionId) match
                case (Some(res), Some(sid)) =>
                  res.agentRegistry.get.map(_.get(sid).map(_.rootSessionId).filter(_.nonEmpty).getOrElse(sid))
                case _ => IO.pure(ctx.sessionId.getOrElse(""))
              for
                safetyMode <- safetyModeIO
                rootSid <- callerRootIO
                result <-
                  if lifecycle == "persistent" then
                    spawnPersistent(
                      agentDef = agentDef,
                      prompt = adjustedPrompt,
                      description = description,
                      taskDescription = taskDescription,
                      agentName = agentName,
                      initialMessages = initialMessages,
                      system = system,
                      resources = resources,
                      parentDepth = ctx.depth,
                      parentRef = ctx.agentActorRef,
                      wsSend = ctx.wsSend,
                      projectRoot = ctx.projectRoot,
                      parentSessionId = ctx.sessionId,
                      safetyMode = safetyMode,
                      rootSessionId = rootSid
                    )
                  else
                    spawnBackground(
                      agentDef = agentDef,
                      prompt = adjustedPrompt,
                      description = description,
                      agentName = agentName,
                      initialMessages = initialMessages,
                      system = system,
                      resources = resources,
                      parentDepth = ctx.depth,
                      parentRef = ctx.agentActorRef,
                      wsSend = ctx.wsSend,
                      projectRoot = ctx.projectRoot,
                      parentSessionId = ctx.sessionId,
                      safetyMode = safetyMode,
                      rootSessionId = rootSid
                    )
              yield result
            case _ =>
              IO.pure(Left(ToolError("Delegate requires ActorSystem and SharedResources")))
      }
    end if
  end call

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
    safetyMode: String = "confirm-edits",
    rootSessionId: String = ""
  ): IO[Either[ToolError, String]] =
    for
      readTracker <- ReadTracker.create
      fileHistory <- FileHistory.create()
      childDepth = parentDepth + 1
      subagentId = s"delegate-${agentName}-${java.util.UUID.randomUUID().toString.take(8)}"
      childWsSend = routeWsSend(wsSend, parentSessionId, subagentId)
      subagentRef <- system.spawn(
        AgentActor(
          agentDef = agentDef,
          resources = resources,
          wsSend = childWsSend,
          depth = childDepth,
          parentRef = parentRef,
          sessionId = Some(subagentId),
          sessionName = Some(description),
          initialMessages = initialMessages,
          readTracker = Some(readTracker),
          fileHistory = Some(fileHistory),
          contextWindow = resources.contextWindow,
          projectRoot = Some(projectRoot),
          safetyMode = safetyMode,
          rootSessionId = if rootSessionId.nonEmpty then rootSessionId else parentSessionId.getOrElse(subagentId)
        ),
        subagentId
      )
      adapterRef <- system.spawn(
        backgroundAdapter(subagentRef, parentRef, description, agentName, subagentId, resources),
        s"$subagentId-adapter"
      )
      _ = logger.info(s"Spawned background sub-agent: $subagentId (depth=$childDepth, agent=$agentName)")
      _ <- resources.agentRegistry.update(_ + (
        subagentId -> AgentRecord(
          subagentId,
          subagentRef,
          AgentKind.Delegate,
          if rootSessionId.nonEmpty then rootSessionId else parentSessionId.getOrElse(subagentId),
          parentRef
        )
      ))
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
        (notify *> resources.agentRegistry.update(_ - subagentId) *>
          (subagentRef ! AgentCommand.Stop("delegate-complete")) *>
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
              case AgentEvent.Failed(sessionId, error) =>
                val sessionInfo =
                  if sessionId.nonEmpty then s" [session=$sessionId]" else ""
                ("failed", s"[Sub-agent failed] \"$description\": ${error.message}$sessionInfo")
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
    safetyMode: String = "confirm-edits",
    rootSessionId: String = ""
  ): IO[Either[ToolError, String]] =
    for
      readTracker <- ReadTracker.create
      fileHistory <- FileHistory.create()
      childDepth = parentDepth + 1
      subagentId = s"delegate-${agentName}-${java.util.UUID.randomUUID().toString.take(8)}"
      childWsSend = routeWsSend(wsSend, parentSessionId, subagentId)
      subagentRef <- system.spawn(
        AgentActor(
          agentDef = agentDef,
          resources = resources,
          wsSend = childWsSend,
          depth = childDepth,
          parentRef = parentRef,
          sessionId = Some(subagentId),
          sessionName = Some(description),
          initialMessages = initialMessages,
          readTracker = Some(readTracker),
          fileHistory = Some(fileHistory),
          contextWindow = resources.contextWindow,
          projectRoot = Some(projectRoot),
          safetyMode = safetyMode,
          rootSessionId = if rootSessionId.nonEmpty then rootSessionId else parentSessionId.getOrElse(subagentId)
        ),
        subagentId
      )
      address = subagentRef.path.toString
      adapterRef <- system.spawn(
        persistentAdapter(subagentRef, parentRef, description, agentName, subagentId, address, resources),
        s"$subagentId-adapter"
      )
      _ = logger.info(s"Spawned persistent sub-agent: $subagentId (depth=$childDepth, agent=$agentName, addr=$address)")
      _ <- resources.agentRegistry.update(_ + (
        subagentId -> AgentRecord(
          subagentId,
          subagentRef,
          AgentKind.Delegate,
          if rootSessionId.nonEmpty then rootSessionId else parentSessionId.getOrElse(subagentId),
          parentRef
        )
      ))
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
    address: String,
    resources: SharedResources
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
                // Persistent session died — unregister, notify parent, adapter stops
                resources.agentRegistry.update(_ - subagentId) *>
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
