package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.NebflowLogger
import nebflow.core.presets.PresetStore
import nebflow.shared.{ContentBlock, Message, MessageRole}

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

**Spotting parallelizable work (every agent — members AND Managers dispatching):**
A task with 2+ independent parts — different file domains, or different natures (research + implementation, multi-module changes, generation + verification) — should run as concurrent SubTasks (back-to-back calls in one response), not serial work or one-at-a-time dispatch. Canonical: 2026-08-16, Frontend spawned two workers in one response (file-browser batch ops + Canvas viewer pluginization) — same worktree, non-overlapping files, workers don't commit (parent integrates). Sequentially dependent steps (B needs A's output) stay serial. Workers have no fork mechanism — each prompt must stand alone.

**When NOT to use:**
- Steps depend on each other (Step B needs Step A's output) — do serially
- Trivial — faster to just do it yourself
- Subtasks touch the same files — conflict risk
- You need to target a specific agent or trigger a flow — those are Nebula's Delegate / Mail semantics

**Rules:**
- The prompt MUST be self-contained — the worker has no history, no memory, no team context. It must include: (1) background & goal, (2) all inputs (file paths, data, references), (3) constraints (files to touch/avoid, style, commit policy), (4) definition of done, (5) the exact report format for the worker's final message.
- State what "done" looks like (e.g. "Report findings — do not modify files").
- Do NOT duplicate the worker's work — work on non-overlapping files or topics.
- Images: optional `images` parameter attaches up to 5 absolute local image paths to the prompt — the worker sees them directly. For other files, reference paths in the prompt text.
- **Safety**: NEVER send signals to or kill any sbt/java/nebflow process — you run inside a Nebflow instance; killing it kills you and the user's session. Process inspection with `ps` (read-only) is fine; any write operation (signals, kills) is strictly forbidden."""

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
        ),
        "images" -> io.circe.Json.obj(
          "type" -> "array".asJson,
          "items" -> io.circe.Json.obj("type" -> "string".asJson).asJson,
          "maxItems" -> 5.asJson,
          "description" -> "Optional absolute local image paths (PNG/JPG/JPEG/GIF/WEBP/BMP, max 5) attached to the prompt — the worker sees the images directly.".asJson,
          "default" -> io.circe.Json.arr()
        ),
        "preset" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Optional named model preset (from model-presets.json, e.g. 'LowCost' for the free 107 gateway, 'Vision' for vision-capable models). Overrides the worker's own preset/model for this spawn. Use 'LowCost' when the task is cheap/not urgent or your default provider is down.".asJson
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
    val presetName = input("preset").flatMap(_.asString).filter(_.nonEmpty)

    if prompt.trim.isEmpty then IO.pure(Left(ToolError("Missing required parameter: prompt")))
    else if ctx.depth >= MaxDepth then
      IO.pure(Left(ToolError(s"Maximum sub-task depth ($MaxDepth) reached. Cannot delegate further.")))
    else
      // G3: resolve optional image attachments before spawning — fail fast on
      // invalid paths (nothing is spawned).
      ImageInject.parseImagesParam(input) match
        case Left(err) => IO.pure(Left(err))
        case Right(imagePaths) =>
          ImageInject.resolveImages(imagePaths).flatMap {
            case Left(err) => IO.pure(Left(err))
            case Right(attachments) =>
              // Self-clone only: the worker inherits the caller's agent def and a
              // CLEAN context (initialMessages = Nil — the prompt is the only input).
              ctx.agentDef match
                case None => IO.pure(Left(ToolError("No agent definition available")))
                case Some(agentDef) =>
                  // #291: explicit preset param overrides the worker's own
                  // preset/model — resolve before spawning so the worker's LLM
                  // requests run on the requested provider chain. Missing
                  // preset → self-describing error with the available list.
                  PresetResolver.applyPreset(PresetStore(), agentDef, presetName) match
                    case Left(err) => IO.pure(Left(ToolError(err)))
                    case Right(effectiveDef) =>
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
                              agentDef = effectiveDef,
                              prompt = prompt,
                              attachments = attachments,
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
                          end for
                        case _ =>
                          IO.pure(Left(ToolError("SubTask requires ActorSystem and SharedResources")))
          }

    end if

  end call

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
      case Some(sid) =>
        json =>
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
      case None =>
        json =>
          json.asObject match
            case Some(obj) => base(Json.fromJsonObject(obj.add("nodeSessionId", subtaskId.asJson)))
            case None => base(json)

    end match

  end routeWsSend

  private def spawnWorker(
    agentDef: AgentDef,
    prompt: String,
    attachments: List[ContentBlock],
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
      // P2: BackoffSupervisor replaces subtaskAdapter — auto-restarts on crash
      adapterRef <- system.spawn(
        BackoffSupervisor(
          childRef = workerRef,
          childSpawnFn = (sys: ActorSystem, recoveredMessages: List[Message]) =>
            sys.spawn(
              AgentActor(
                agentDef = agentDef,
                resources = resources,
                wsSend = childWsSend,
                depth = childDepth,
                parentRef = parentRef,
                sessionId = Some(subtaskId),
                sessionName = Some(description),
                initialMessages = if recoveredMessages.nonEmpty then recoveredMessages else Nil,
                contextWindow = resources.contextWindow,
                projectRoot = Some(projectRoot),
                safetyMode = safetyMode,
                rootSessionId = if rootSessionId.nonEmpty then rootSessionId else parentSessionId.getOrElse(subtaskId),
                isSubTaskWorker = true
              ),
              subtaskId
            ),
          childName = subtaskId,
          parentRef = parentRef,
          description = description,
          agentName = agentDef.name,
          subagentId = subtaskId,
          parentSessionId = parentSessionId.getOrElse(""),
          resources = resources,
          initialPrompt = prompt,
          source = "subtask",
          extraMetadata = JsonObject("kind" -> "SubTask".asJson),
          wsSend = Some(childWsSend)
        ),
        s"$subtaskId-adapter"
      )
      _ = logger.info(s"Spawned supervised sub-task worker: $subtaskId (depth=$childDepth, agent=${agentDef.name})")
      // Registered in agentRegistry (active snapshot + kind) but NOT in
      // TeamSessionRegistry/sessionMap — the worker has no Mail identity.
      _ <- resources.agentRegistry.update(
        _ + (
          subtaskId -> AgentRecord(
            subtaskId,
            workerRef,
            AgentKind.SubTask,
            if rootSessionId.nonEmpty then rootSessionId else parentSessionId.getOrElse(subtaskId),
            parentRef
          )
        )
      )
      // P3.1: persist task metadata for crash recovery
      _ <- resources.subAgentTaskStore
        .recordTask(
          SubAgentTask(
            taskId = subtaskId,
            parentSessionId = parentSessionId.getOrElse(""),
            agentName = agentDef.name,
            prompt = prompt,
            description = description,
            status = "running",
            retryCount = 0,
            spawnedAt = System.currentTimeMillis(),
            completedAt = None,
            lastError = None,
            source = "subtask"
          )
        )
        .handleErrorWith(e => IO(logger.warn(s"subAgentTaskStore.recordTask failed: ${e.getMessage}")))
      // G3: blocks carry attachments with the prompt text as the first Text
      // block (UserInput drops `text` when blocks are present).
      _ <- workerRef ! AgentCommand.UserInput(
        prompt,
        Some(adapterRef),
        blocks = ImageInject.messageBlocks(prompt, attachments)
      )
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
      def notifyParentAndStop(
        eventType: String,
        payload: String,
        extraMetadata: JsonObject = JsonObject.empty
      ): IO[Behavior[AgentEvent]] =
        val notify = parentRef match
          case Some(ref) =>
            ref ! AgentCommand.ExternalEvent(
              source = "subtask",
              eventType = eventType,
              payload = payload,
              metadata = JsonObject(
                "description" -> description.asJson,
                "agentName" -> agentName.asJson,
                "kind" -> "SubTask".asJson
              ).deepMerge(extraMetadata),
              correlationId = Some(subtaskId)
            )
          case None => IO.unit
        (notify *> resources.agentRegistry.update(_ - subtaskId) *>
          resources.sessionStore.deleteSession(subtaskId).handleErrorWith(_ => IO.unit) *>
          (workerRef ! AgentCommand.Stop("subtask-complete")) *>
          IO.pure(Behaviors.stopped[AgentEvent]))
          .handleErrorWith(_ => IO.pure(Behaviors.stopped[AgentEvent]))
      end notifyParentAndStop

      ctx.watch(workerRef) *> IO.pure(
        new Behavior[AgentEvent]:
          def receive(ctx: ActorContext[AgentEvent], event: AgentEvent): IO[Behavior[AgentEvent]] =
            event match
              case AgentEvent.Completed(_, messages) =>
                val text = extractLastAssistantText(messages)
                if text.nonEmpty then
                  notifyParentAndStop(
                    "completed",
                    s""""$description":
$text"""
                  )
                else notifyParentAndStop("completed", s""""$description" (no text output)""")
              case AgentEvent.Failed(sessionId, error) =>
                val sessionInfo =
                  if sessionId.nonEmpty then s" [session=$sessionId]" else ""
                val retryable = error.errorType match
                  case AgentErrorType.LlmFailed => true
                  case AgentErrorType.Timeout => true
                  case _ => false
                notifyParentAndStop(
                  "failed",
                  s""""$description": ${error.message}$sessionInfo""",
                  JsonObject(
                    "failedSessionId" -> subtaskId.asJson,
                    "retryable" -> retryable.asJson,
                    "failureType" -> error.errorType.toString.asJson
                  )
                )

          override def onSignal(ctx: ActorContext[AgentEvent], signal: SystemSignal): IO[Behavior[AgentEvent]] =
            signal match
              case SystemSignal.Terminated(_) =>
                notifyParentAndStop(
                  "failed",
                  s""""$description": terminated unexpectedly""",
                  JsonObject(
                    "failedSessionId" -> subtaskId.asJson,
                    "retryable" -> true.asJson,
                    "failureType" -> "crashed".asJson
                  )
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

end SubTaskTool
