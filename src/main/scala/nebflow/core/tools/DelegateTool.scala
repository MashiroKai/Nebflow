package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.NebflowLogger
import nebflow.core.node.NodeRunner
import nebflow.core.presets.PresetStore
import nebflow.shared.{ContentBlock, Message, MessageRole}

/**
 * DelegateTool — Nebula/调度器专用 sub-agent delegation.
 *
 * Only available to the Nebula root agent (NebulaExclusiveTools). Lets Nebula
 * spawn a sub-agent for a subtask, targeting a standalone agent by explicit
 * name. Self-cloning is banned (#28, 2026-08-20): the root orchestrator exists
 * exactly once and must never be spawned — neither as the no-agent default nor
 * via agent="Nebula". Team members delegate via SubTaskTool instead (self-clone
 * + ephemeral worker, no Mail identity). Flow pipelines are engine-triggered
 * (Mail to a flow name); the agent-side FlowTrigger tool retired 2026-09-06.
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
      case Some(sid) =>
        json =>
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
      case None =>
        json =>
          json.asObject match
            case Some(obj) => base(Json.fromJsonObject(obj.add("nodeSessionId", subagentId.asJson)))
            case None => base(json)
    end match
  end routeWsSend

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

**When NOT to use:**
- Steps depend on each other (Step B needs Step A's result) — do serially
- Trivial — faster to just do it yourself
- Subtasks touch the same files — conflict risk

**Targeting:**
- `agent` is REQUIRED: it names the standalone agent to spawn (e.g. Coder, Explorer). Self-cloning is not supported — the calling agent must not be re-spawned (issue #28).
- Dispatch directly: if your routing rules already assign this task type to a specific agent, pass that `agent=` directly — dispatching a generic agent that re-dispatches creates a wasteful two-layer chain (double token cost, and results may not propagate back).
- Cannot target team agents — those require Mail

**Rules:**
- Prompt must be self-contained (the sub-agent starts with a clean context — your conversation history is NOT passed).
- State what "done" looks like (e.g. "Report findings — do not modify files").
- Do NOT duplicate the sub-agent's work — work on non-overlapping files or topics.
- Images: optional `images` parameter attaches up to 5 absolute local image paths to the prompt — the sub-agent sees them directly. For other files, reference paths in the prompt text.
- **Safety**: NEVER send signals to or kill any sbt/java/nebflow process — you run inside a Nebflow instance; killing it kills you and the user's session. Process inspection with `ps` (read-only) is fine; any write operation (signals, kills) is strictly forbidden."""

  /**
   * Dynamic preset parameter doc: renders the live preset catalog (name —
   * user's description) so agents can factor user intent into preset
   * selection. Re-evaluated per LLM call (ALL_TOOLS is a def; PresetStore
   * reads fresh) — preset edits in Settings reach agents on the next request
   * without restart.
   */
  private def presetParamDescription: String =
    val catalog = PresetStore.catalogLines()
    val catalogText =
      if catalog.isEmpty then ""
      else " Available presets (name — the user's note on it):\n" + catalog.map(l => s"  $l").mkString("\n")
    s"Optional named model preset — overrides the sub-agent's own preset/model for this spawn. Pick by the catalog below when the task fits a preset's profile, or to fall back when your default provider is down.$catalogText"

  def inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "prompt" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Self-contained task description for the sub-agent. Must include all context needed — the sub-agent starts with a clean context.".asJson
        ),
        "description" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Short label for the task (shown in UI).".asJson
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
          "description" -> "Required. Target standalone agent name (e.g. 'Coder', 'Explorer'). Only stateless standalone agents can be targeted — team agents require Mail, and the calling agent itself cannot be delegated to (self-clone is banned, issue #28).".asJson
        ),
        "images" -> io.circe.Json.obj(
          "type" -> "array".asJson,
          "items" -> io.circe.Json.obj("type" -> "string".asJson).asJson,
          "maxItems" -> 5.asJson,
          "description" -> "Optional absolute local image paths (PNG/JPG/JPEG/GIF/WEBP/BMP, max 5) attached to the prompt — the sub-agent sees the images directly.".asJson,
          "default" -> io.circe.Json.arr()
        ),
        "preset" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> presetParamDescription.asJson
        )
      ),
      "required" -> io.circe.Json.arr("prompt".asJson, "description".asJson, "agent".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val desc = input("description").flatMap(_.asString).getOrElse("")
    val agent = input("agent").flatMap(_.asString).getOrElse("")
    val target = if agent.nonEmpty then s"→ $agent" else ""
    s"Delegate($desc $target)".trim

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 200 then result.take(197) + "..." else result

  /**
   * #28: dynamic list of targetable standalone agents, appended to
   * self-describing rejection errors so the caller can retry with a legal
   * target without another round-trip. Best-effort — empty on library failure
   * (the rejection itself must never fail). 'Nebula' and the caller itself
   * are excluded: both are banned targets (#28) and listing them as
   * "targetable" would be self-contradictory.
   */
  private def standaloneCatalog(ctx: ToolContext): IO[String] =
    ctx.agentLibrary match
      case Some(lib) =>
        val banned = Set("Nebula") ++ ctx.agentDef.map(_.name)
        lib
          .loadAll()
          .map { all =>
            val names = all.values
              .filter(d => d.category == "standalone" && !banned.contains(d.name))
              .map(_.name)
              .toList
              .sorted
            if names.isEmpty then " No targetable standalone agents are currently defined."
            else s" Targetable standalone agents: ${names.mkString(", ")}."
          }
          .handleErrorWith(_ => IO.pure(""))
      case None => IO.pure("")

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val prompt = input("prompt").flatMap(_.asString).getOrElse("")
    val description = input("description").flatMap(_.asString).getOrElse("subtask")
    val lifecycle = input("lifecycle").flatMap(_.asString).getOrElse("ephemeral")
    val taskDescription = input("taskDescription").flatMap(_.asString).getOrElse(description)
    val targetAgentName = input("agent").flatMap(_.asString).filter(_.nonEmpty)
    val presetName = input("preset").flatMap(_.asString).filter(_.nonEmpty)

    if prompt.trim.isEmpty then IO.pure(Left(ToolError("Missing required parameter: prompt")))
    else if ctx.depth >= MaxDepth then
      IO.pure(Left(ToolError(s"Maximum sub-agent depth ($MaxDepth) reached. Cannot delegate further.")))
    else if targetAgentName.isEmpty then
      // #28 (2026-08-20): self-clone is banned — the root orchestrator (Nebula)
      // exists exactly once and must never be spawned, so Delegate requires an
      // explicit standalone target. The error lists the legal targets.
      standaloneCatalog(ctx).map { catalog =>
        Left(ToolError(
          s"Missing required parameter: agent. Delegate no longer supports self-cloning the caller (issue #28: the root orchestrator exists exactly once and must never be re-spawned).$catalog"
        ))
      }
    else
      // G3: resolve optional image attachments before spawning — fail fast on
      // invalid paths (nothing is spawned).
      ImageInject.parseImagesParam(input) match
        case Left(err) => IO.pure(Left(err))
        case Right(imagePaths) =>
          ImageInject.resolveImages(imagePaths).flatMap {
            case Left(err) => IO.pure(Left(err))
            case Right(attachments) =>
              // Resolve the target agent def. #28: an explicit standalone target
              // is mandatory; the identity checks ban delegating to 'Nebula'
              // (the root exists exactly once — its def defaults to category
              // "standalone", so the category check alone cannot catch it) and
              // to the caller itself (self-clone via the back door). Done
              // before the ActorSystem check so that invalid targets are
              // reported even without a live actor system.
              val resolveAgent: IO[Either[ToolError, AgentDef]] =
                ctx.agentLibrary match
                  case Some(lib) =>
                    lib.get(targetAgentName.get).flatMap {
                      case Some(targetDef) if targetDef.category != "standalone" =>
                        standaloneCatalog(ctx).map { catalog =>
                          Left(ToolError(s"'${targetAgentName.get}' is not a standalone agent (team agents are reached via Mail).$catalog"))
                        }
                      case Some(targetDef) if targetDef.name == "Nebula" =>
                        standaloneCatalog(ctx).map { catalog =>
                          Left(ToolError(s"Cannot Delegate to 'Nebula' — the root orchestrator exists exactly once and must never be spawned (issue #28).$catalog"))
                        }
                      case Some(targetDef) if ctx.agentDef.exists(_.name == targetDef.name) =>
                        standaloneCatalog(ctx).map { catalog =>
                          Left(ToolError(s"Cannot Delegate to '${targetDef.name}' — that is the calling agent itself; self-clone is banned (issue #28).$catalog"))
                        }
                      case Some(targetDef) => IO.pure(Right(targetDef))
                      case None =>
                        standaloneCatalog(ctx).map { catalog =>
                          Left(ToolError(s"Agent '${targetAgentName.get}' not found.$catalog"))
                        }
                    }
                  case None =>
                    IO.pure(Left(ToolError("No agent library available")))

              resolveAgent.flatMap {
                case Left(err) => IO.pure(Left(err))
                case Right(agentDef) =>
                  // #291: explicit preset param overrides the target's own
                  // preset/model — resolve before spawning so the child's LLM
                  // requests run on the requested provider chain. Missing
                  // preset → self-describing error with the available list.
                  PresetResolver.applyPreset(PresetStore(), agentDef, presetName) match
                    case Left(err) => IO.pure(Left(ToolError(err)))
                    case Right(effectiveDef) =>
                      spawnDelegate(
                        effectiveDef,
                        prompt,
                        description,
                        taskDescription,
                        lifecycle,
                        attachments,
                        ctx
                      )
              }
          }
    end if
  end call

  /** Shared spawn entry for background and persistent modes (after preset resolution). */
  private def spawnDelegate(
    agentDef: AgentDef,
    prompt: String,
    description: String,
    taskDescription: String,
    lifecycle: String,
    attachments: List[ContentBlock],
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    (ctx.actorSystem, ctx.sharedResources) match
      case (Some(system), Some(resources)) =>
        val agentName = agentDef.name

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
                prompt = prompt,
                attachments = attachments,
                description = description,
                taskDescription = taskDescription,
                agentName = agentName,
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
                prompt = prompt,
                attachments = attachments,
                description = description,
                agentName = agentName,
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
        IO.pure(Left(ToolError("Delegate requires ActorSystem and SharedResources")))
  end spawnDelegate

  // ============================================================
  // Background: return immediately, deliver result via ExternalEvent
  // ============================================================

  private def spawnBackground(
    agentDef: AgentDef,
    prompt: String,
    attachments: List[ContentBlock],
    description: String,
    agentName: String,
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
    val childDepth = parentDepth + 1
    val subagentId = s"delegate-${agentName}-${java.util.UUID.randomUUID().toString.take(8)}"
    val childWsSend = routeWsSend(wsSend, parentSessionId, subagentId)
    val resolvedRoot = if rootSessionId.nonEmpty then rootSessionId else parentSessionId.getOrElse(subagentId)
    val params = NodeRunner.SpawnParams(
      agentDef = agentDef,
      resources = resources,
      sessionId = subagentId,
      sessionName = description,
      depth = childDepth,
      parentRef = parentRef,
      wsSend = childWsSend,
      projectRoot = Some(projectRoot),
      safetyMode = safetyMode,
      rootSessionId = resolvedRoot
    )
    for
      subagentRef <- NodeRunner.spawnAgentActor(system, params)
      // P2: BackoffSupervisor is the background adapter — auto-restarts on crash,
      // handles AgentEvent.Cancelled (AgentControl cancel path)
      adapterRef <- NodeRunner.spawnSupervisedAdapter(
        system = system,
        params = params,
        childRef = subagentRef,
        childName = subagentId,
        description = description,
        agentName = agentName,
        subagentId = subagentId,
        parentSessionId = parentSessionId.getOrElse(""),
        initialPrompt = prompt,
        source = "delegate",
        wsSend = Some(childWsSend)
      )
      _ = logger.info(s"Spawned supervised background sub-agent: $subagentId (depth=$childDepth, agent=$agentName)")
      _ <- NodeRunner.registerAgent(
        resources,
        id = subagentId,
        ref = subagentRef,
        kind = AgentKind.Delegate,
        rootSessionId = resolvedRoot,
        parentRef = parentRef,
        // AgentControl：startedAt/lastActivityMs 驱动 list 的 up/idle 列与
        // 卡死判定；supervisorRef 是 cancel 的直达通道（BackoffSupervisor
        // 处理 AgentEvent.Cancelled）。
        startedAt = System.currentTimeMillis(),
        lastActivityMs = System.currentTimeMillis(),
        supervisorRef = Some(adapterRef),
        parentSessionId = parentSessionId.getOrElse("")
      )
      // P3.1: persist task metadata for crash recovery
      _ <- resources.subAgentTaskStore
        .recordTask(
          SubAgentTask(
            taskId = subagentId,
            parentSessionId = parentSessionId.getOrElse(""),
            agentName = agentName,
            prompt = prompt,
            description = description,
            status = "running",
            retryCount = 0,
            spawnedAt = System.currentTimeMillis(),
            completedAt = None,
            lastError = None,
            source = "delegate"
          )
        )
        .handleErrorWith(e => logger.warn(s"subAgentTaskStore.recordTask failed: ${e.getMessage}"))
      // G3: blocks carry attachments with the prompt text as the first Text
      // block (UserInput drops `text` when blocks are present).
      _ <- subagentRef ! AgentCommand.UserInput(
        prompt,
        Some(adapterRef),
        blocks = ImageInject.messageBlocks(prompt, attachments)
      )
    yield Right(
      s"""Sub-agent '$agentName' started in background for: $description.
You will be notified when it completes via a system message.
Do NOT duplicate this agent's work — avoid working with the same files or topics it is using. Work on non-overlapping tasks, or briefly tell the user what you launched and end your response."""
    )

  // ============================================================
  // Persistent: sub-agent stays alive, address returned to caller
  // ============================================================

  private def spawnPersistent(
    agentDef: AgentDef,
    prompt: String,
    attachments: List[ContentBlock],
    description: String,
    taskDescription: String,
    agentName: String,
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
    val childDepth = parentDepth + 1
    val subagentId = s"delegate-${agentName}-${java.util.UUID.randomUUID().toString.take(8)}"
    val childWsSend = routeWsSend(wsSend, parentSessionId, subagentId)
    val resolvedRoot = if rootSessionId.nonEmpty then rootSessionId else parentSessionId.getOrElse(subagentId)
    val params = NodeRunner.SpawnParams(
      agentDef = agentDef,
      resources = resources,
      sessionId = subagentId,
      sessionName = description,
      depth = childDepth,
      parentRef = parentRef,
      wsSend = childWsSend,
      projectRoot = Some(projectRoot),
      safetyMode = safetyMode,
      rootSessionId = resolvedRoot
    )
    for
      subagentRef <- NodeRunner.spawnAgentActor(system, params)
      address = subagentRef.path.toString
      adapterRef <- system.spawn(
        persistentAdapter(subagentRef, parentRef, description, agentName, subagentId, address, resources),
        s"$subagentId-adapter"
      )
      _ = logger.info(s"Spawned persistent sub-agent: $subagentId (depth=$childDepth, agent=$agentName, addr=$address)")
      _ <- NodeRunner.registerAgent(
        resources,
        id = subagentId,
        ref = subagentRef,
        kind = AgentKind.Delegate,
        rootSessionId = resolvedRoot,
        parentRef = parentRef,
        // AgentControl：persistent 的 cancel 走 persistentAdapter 的
        // Cancelled 分支（ExternalEvent + SessionUpdate("cancelled") +
        // registry 移除 + child Stop）；restart 不支持（无 supervisor）。
        startedAt = System.currentTimeMillis(),
        lastActivityMs = System.currentTimeMillis(),
        supervisorRef = Some(adapterRef),
        parentSessionId = parentSessionId.getOrElse("")
      )
      _ <- parentRef.fold(IO.unit)(ref => ref ! AgentCommand.SessionStarted(address, agentName, taskDescription))
      // G3: blocks carry attachments with the prompt text as the first Text
      // block (UserInput drops `text` when blocks are present).
      _ <- subagentRef ! AgentCommand.UserInput(
        prompt,
        Some(adapterRef),
        blocks = ImageInject.messageBlocks(prompt, attachments)
      )
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

      ctx.watch(subagentRef) *> IO.pure(
        new Behavior[AgentEvent]:
          def receive(ctx: ActorContext[AgentEvent], event: AgentEvent): IO[Behavior[AgentEvent]] =
            event match
              case AgentEvent.Cancelled(_, reason) =>
                // AgentControl cancel（spec §3.2b）：persistent 语义——通知父 +
                // SessionUpdate("cancelled") + registry 移除 + child Stop + 自停。
                // 与自然终态不同：child 必须终止（用户主动取消），不能留活等 Mail。
                val reasonSuffix = if reason.nonEmpty then s" ($reason)" else ""
                val notify = parentRef.fold(IO.unit)(ref =>
                  (ref ! AgentCommand.ExternalEvent(
                    source = address,
                    eventType = "cancelled",
                    payload = s"[Session cancelled] \"$description\": cancelled by Nebula via AgentControl$reasonSuffix",
                    metadata = JsonObject(
                      "description" -> description.asJson,
                      "agentName" -> agentName.asJson,
                      "cancelled" -> true.asJson,
                      "reason" -> reason.asJson
                    ),
                    correlationId = Some(subagentId)
                  )) *> (ref ! AgentCommand.SessionUpdate(address, "cancelled"))
                )
                (notify *> resources.agentRegistry.update(_ - subagentId) *>
                  (subagentRef ! AgentCommand.Stop("agent-control-cancel")) *>
                  IO.pure(Behaviors.stopped[AgentEvent]))
                  .handleErrorWith(_ => IO.pure(Behaviors.stopped[AgentEvent]))

              case AgentEvent.Completed(_, messages) =>
                val text = extractLastAssistantText(messages)
                val p =
                  if text.nonEmpty then s"[Session update] \"$description\":\n$text"
                  else s"[Session update] \"$description\" (task complete, awaiting instructions)"
                notifyParentAndStop("completed", p, "idle (awaiting instructions)")
              case AgentEvent.Failed(_, error) =>
                notifyParentAndStop("failed", s"[Session error] \"$description\": ${error.message}", "failed")

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
      .filter(_.trim.nonEmpty)
      .getOrElse("")

end DelegateTool
