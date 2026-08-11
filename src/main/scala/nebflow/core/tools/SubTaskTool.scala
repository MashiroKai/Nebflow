package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.NebflowLogger
import nebflow.shared.{Message, MessageRole}

/**
 * SubTaskTool — team-agent task delegation (Delegate split, 方案 A).
 *
 * Lets a team member spawn a self-cloned, ephemeral sub-agent (worker) for a
 * subtask. Unlike Delegate (Nebula/调度器专用), SubTask:
 *
 *   - **Self-clone only**: no `agent`/`flow` targeting, no `lifecycle`,
 *     no `fork`. The worker starts with a clean context — the prompt is its
 *     only input.
 *   - **No Mail identity**: never registered in TeamSessionRegistry / sessionMap.
 *     The worker is a leaf: Mail/SubTask/Delegate are stripped from its tool
 *     set (see AgentCore.buildAllowedToolSet), and the Worker Identity Block
 *     appended to its system prompt declares it has no team.
 *   - **Ephemeral only**: the adapter deletes the worker's session store entry
 *     on completion — no `subtask-*` residue.
 *   - Result is delivered to the parent via ExternalEvent(source="subtask")
 *     exactly like Delegate's background mode.
 *
 * Depth limit: when depth >= MaxDepth, the tool is filtered out by
 * buildToolList in AgentCore, preventing infinite recursion.
 */
object SubTaskTool extends Tool:
  private val logger = NebflowLogger(getClass)

  /** Maximum sub-agent depth (matches AgentCore.MaxDepth / DelegateTool). */
  val MaxDepth: Int = 5

  val name = "SubTask"

  val description =
    """Spawn a self-cloned background worker (sub-task) to handle a subtask while you continue your own work. The worker runs autonomously with a clean context and reports back when done.

Multiple SubTask calls in one response run concurrently — use this to parallelize independent work.

**When to use:**
- A task splits into independent parts (e.g. "write the API and its tests" → SubTask one, do the other)
- A subtask needs deep focus without cluttering your main context
- You want to hand off a self-contained piece of work and keep your own turn going

**When NOT to use:**
- Steps depend on each other (Step B needs Step A's output) — do serially
- Trivial — faster to just do it yourself
- Subtasks touch the same files — conflict risk
- You need to target a specific agent or trigger a flow — those are Nebula's Delegate / Mail semantics

**Rules:**
- The prompt MUST be self-contained — the worker has no history, no memory, no team context. It must include: (1) background & goal, (2) all inputs (file paths, data, references), (3) constraints (files to touch/avoid, style, commit policy), (4) definition of done, (5) the exact report format for the worker's final message.
- State what "done" looks like (e.g. "Report findings — do not modify files").
- Do NOT duplicate the worker's work — work on non-overlapping files or topics."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "prompt" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Self-contained task description for the worker. Must include all context: background, inputs, constraints, definition of done, and the exact final-message report format.".asJson
        ),
        "description" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Short label for the task (shown in UI).".asJson
        )
      ),
      "required" -> io.circe.Json.arr("prompt".asJson, "description".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val desc = input("description").flatMap(_.asString).getOrElse("")
    s"SubTask($desc)".trim

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 200 then result.take(197) + "..." else result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val prompt = input("prompt").flatMap(_.asString).getOrElse("")
    val description = input("description").flatMap(_.asString).getOrElse("subtask")

    if prompt.trim.isEmpty then IO.pure(Left(ToolError("Missing required parameter: prompt")))
    else if ctx.depth >= MaxDepth then
      IO.pure(Left(ToolError(s"Maximum sub-task depth ($MaxDepth) reached. Cannot delegate further.")))
    else
      // Self-clone only: the worker inherits the caller's agent def and a
      // CLEAN context (initialMessages = Nil — the prompt is the only input).
      ctx.agentDef match
        case None => IO.pure(Left(ToolError("No agent definition available")))
        case Some(agentDef) =>
          (ctx.actorSystem, ctx.sharedResources) match
            case (Some(system), Some(resources)) =>
              // Query parent session's safety mode so the worker inherits it
              val safetyModeIO = (ctx.sessionStore, ctx.sessionId) match
                case (Some(store), Some(sid)) => store.getSafetyMode(sid)
                case _ => IO.pure("confirm-edits")
              // P2: resolve the caller's permission-policy bucket (root session)
              // so the worker inherits the same policy — interactions (askUser /
              // permission) render in the parent's window.
              val callerRootIO = (ctx.sharedResources, ctx.sessionId) match
                case (Some(res), Some(sid)) =>
                  res.agentRegistry.get.map(_.get(sid).map(_.rootSessionId).filter(_.nonEmpty).getOrElse(sid))
                case _ => IO.pure(ctx.sessionId.getOrElse(""))
              for
                safetyMode <- safetyModeIO
                rootSid <- callerRootIO
                result <- spawnWorker(
                  agentDef = agentDef,
                  prompt = prompt,
                  description = description,
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
              IO.pure(Left(ToolError("SubTask requires ActorSystem and SharedResources")))

  /**
   * Wraps wsSend so that the parent session's sessionId is injected into every
   * event JSON. This lets the frontend route worker events to the correct
   * window (primary or secondary) without modifying the worker's internal
   * state — the worker still sees sessionId=None for session-busy / error
   * logic, but its streaming events carry the parent's sessionId for display.
   * (Same routing as DelegateTool.routeWsSend.)
   */
  private def routeWsSend(
    wsSend: Option[io.circe.Json => IO[Unit]],
    parentSessionId: Option[String],
    subtaskId: String
  ): io.circe.Json => IO[Unit] =
    val base = wsSend.getOrElse((_: io.circe.Json) => IO.unit)
    parentSessionId match
      case Some(sid) => json =>
        json.asObject match
          case Some(obj) =>
            val builder = obj.add("rootSessionId", sid.asJson).add("sessionId", sid.asJson)
            // Preserve an existing nodeSessionId (nested sub-agents); otherwise
            // stamp the worker's own nodeSessionId for popup routing.
            val finalObj =
              if obj.contains("nodeSessionId") then builder
              else builder.add("nodeSessionId", subtaskId.asJson)
            base(Json.fromJsonObject(finalObj))
          case None => base(json)
      case None => json =>
        json.asObject match
          case Some(obj) => base(Json.fromJsonObject(obj.add("nodeSessionId", subtaskId.asJson)))
          case None => base(json)

  private def spawnWorker(
    agentDef: AgentDef,
    prompt: String,
    description: String,
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
      subtaskId = s"subtask-${java.util.UUID.randomUUID().toString.take(8)}"
      childWsSend = routeWsSend(wsSend, parentSessionId, subtaskId)
      workerRef <- system.spawn(
        AgentActor(
          agentDef = agentDef,
          resources = resources,
          wsSend = childWsSend,
          depth = childDepth,
          parentRef = parentRef,
          sessionId = Some(subtaskId),
          sessionName = Some(description),
          initialMessages = Nil, // clean context — prompt is the only input
          readTracker = Some(readTracker),
          fileHistory = Some(fileHistory),
          contextWindow = resources.contextWindow,
          projectRoot = Some(projectRoot),
          safetyMode = safetyMode,
          rootSessionId = if rootSessionId.nonEmpty then rootSessionId else parentSessionId.getOrElse(subtaskId),
          isSubTaskWorker = true
        ),
        subtaskId
      )
      adapterRef <- system.spawn(
        subtaskAdapter(workerRef, parentRef, description, agentDef.name, subtaskId, resources),
        s"$subtaskId-adapter"
      )
      _ = logger.info(s"Spawned sub-task worker: $subtaskId (depth=$childDepth, agent=${agentDef.name})")
      // Registered in agentRegistry (active snapshot + kind) but NOT in
      // TeamSessionRegistry/sessionMap — the worker has no Mail identity.
      _ <- resources.agentRegistry.update(_ + (
        subtaskId -> AgentRecord(
          subtaskId,
          workerRef,
          AgentKind.SubTask,
          if rootSessionId.nonEmpty then rootSessionId else parentSessionId.getOrElse(subtaskId),
          parentRef
        )
      ))
      _ <- workerRef ! AgentCommand.UserInput(prompt, Some(adapterRef))
    yield Right(
      s"""Sub-task worker started for: $description.
You will be notified when it completes via a system message.
Do NOT duplicate this worker's work — avoid working with the same files or topics it is using. Work on non-overlapping tasks, or briefly tell the user what you launched and end your response."""
    )

  /**
   * Subtask adapter: forwards the worker's final message to the parent via
   * ExternalEvent, then cleans up — unregisters from agentRegistry, deletes
   * the worker's session store entry (no `subtask-*` residue), stops the
   * worker and itself. Watches the worker for crashes.
   */
  private def subtaskAdapter(
    workerRef: ActorRef[AgentCommand],
    parentRef: Option[ActorRef[AgentCommand]],
    description: String,
    agentName: String,
    subtaskId: String,
    resources: SharedResources
  ): Behavior[AgentEvent] =
    Behaviors.setup { ctx =>
      ctx.watch(workerRef)

      def notifyParentAndStop(eventType: String, payload: String): IO[Behavior[AgentEvent]] =
        val notify = parentRef match
          case Some(ref) =>
            ref ! AgentCommand.ExternalEvent(
              source = "subtask",
              eventType = eventType,
              payload = payload,
              metadata = JsonObject("description" -> description.asJson, "agentName" -> agentName.asJson, "kind" -> "SubTask".asJson),
              correlationId = Some(subtaskId)
            )
          case None => IO.unit
        (notify *> resources.agentRegistry.update(_ - subtaskId) *>
          resources.sessionStore.deleteSession(subtaskId).handleErrorWith(_ => IO.unit) *>
          (workerRef ! AgentCommand.Stop("subtask-complete")) *>
          IO.pure(Behaviors.stopped[AgentEvent]))
          .handleErrorWith(_ => IO.pure(Behaviors.stopped[AgentEvent]))

      IO.pure(
        new Behavior[AgentEvent]:
          def receive(ctx: ActorContext[AgentEvent], event: AgentEvent): IO[Behavior[AgentEvent]] =
            val (eventType, payload) = event match
              case AgentEvent.Completed(_, messages) =>
                val text = extractLastAssistantText(messages)
                if text.nonEmpty then ("completed", s"""[Sub-task completed] "$description":
$text""")
                else ("completed", s"""[Sub-task completed] "$description" (no text output)""")
              case AgentEvent.Failed(sessionId, error) =>
                val sessionInfo =
                  if sessionId.nonEmpty then s" [session=$sessionId]" else ""
                ("failed", s"""[Sub-task failed] "$description": ${error.message}$sessionInfo""")
            notifyParentAndStop(eventType, payload)

          override def onSignal(ctx: ActorContext[AgentEvent], signal: SystemSignal): IO[Behavior[AgentEvent]] =
            signal match
              case SystemSignal.Terminated(_) =>
                notifyParentAndStop("failed", s"""[Sub-task crashed] "$description": terminated unexpectedly""")
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

end SubTaskTool
