package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.agent.*
import nebflow.core.NebflowLogger
import nebflow.shared.{Message, MessageRole}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}

/**
 * DelegateTool — lets an agent spawn a sub-agent for a subtask.
 *
 * The sub-agent runs asynchronously:
 *   - Parent calls Delegate → sub-agent is spawned immediately.
 *   - Tool returns "started" confirmation — parent can continue other work.
 *   - When sub-agent finishes, its result is injected into the parent's
 *     conversation as an ExternalEvent (like Bash background task completion).
 *   - If the parent is busy processing, the result queues until the turn ends.
 *   - If the parent is idle, a new LLM turn starts automatically.
 *
 * Enhanced features:
 *   - model: assign a specific model to the sub-agent (e.g. "zhipu/GLM-5.2")
 *   - fork: pass current conversation context to the sub-agent (prompt cache)
 *   - agentName: choose which agent definition to use (e.g. "Lyra")
 *
 * Depth limit: when depth >= MaxDepth, the tool is not included in the agent's
 * tool list (filtered by buildToolList in AgentCore), making it impossible to
 * recurse further.
 */
object DelegateTool extends Tool:
  private val logger = NebflowLogger(getClass)

  /** Maximum sub-agent depth (matches AgentCore.MaxDepth). */
  val MaxDepth: Int = 5

  val name = "Delegate"

  val description =
    """Delegate a subtask to a sub-agent. The sub-agent runs autonomously with its own context and tools, then returns its final result.

The sub-agent runs ASYNCHRONOUSLY — you get an immediate confirmation and the result comes back later as a system message. This lets you continue other work instead of waiting.

Use Delegate when:
- A task can be broken into independent parts that benefit from focused context
- You need parallel research on different aspects of a problem
- A subtask requires deep focus without polluting your main conversation
- You want to start work on something else while the sub-agent handles a subtask

Key rules:
- By default the sub-agent starts with a clean context (no conversation history). Every prompt must be self-contained.
- Set fork=true to pass your current conversation context to the sub-agent. This saves tokens via prompt caching. Use fork when the subtask depends on your conversation context.
- State what "done" looks like (e.g. "Report findings — do not modify files").
- For research: "Report specific file paths, line numbers, and findings. Do not modify files."
- For implementation: "Make the change, run relevant tests, and report the result."
- You can delegate to different agent types by specifying agentName (e.g. "Nebula", "Lyra"). Defaults to "Nebula".
- You can assign a specific model with the `model` parameter (format: "provider/model-id"). Use faster/cheaper models for simple tasks, stronger models for complex ones.
- Multiple Delegate calls in a single response run in parallel.
- You will be notified when the sub-agent completes. The result arrives as a system message — continue from there.

Do NOT use Delegate for:
- Trivial tasks you can handle directly with Read/Bash/etc.
- Sequential tasks where each step depends on the previous step's result."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "agentName" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Name of the agent definition to use (e.g. \"Nebula\", \"Lyra\"). Defaults to \"Nebula\".".asJson,
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
        "model" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> """Model to use for the sub-agent, in "provider/model-id" format (e.g. "zhipu/GLM-5.2"). If omitted, uses the default model.""".asJson
        ),
        "fork" -> io.circe.Json.obj(
          "type" -> "boolean".asJson,
          "description" -> "If true, pass current conversation context to the sub-agent (enables prompt cache reuse, saves tokens). Default: false.".asJson,
          "default" -> false.asJson
        )
      ),
      "required" -> io.circe.Json.arr("prompt".asJson, "description".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val desc = input("description").flatMap(_.asString).getOrElse("")
    val agent = input("agentName").flatMap(_.asString).getOrElse("Nebula")
    val model = input("model").flatMap(_.asString)
    val forked = input("fork").flatMap(_.asBoolean).getOrElse(false)
    val suffix = List(
      model.map(m => s"model=$m"),
      if forked then Some("fork") else None
    ).flatten.mkString(", ")
    if suffix.nonEmpty then s"Delegate($agent: $desc [$suffix])"
    else s"Delegate($agent: $desc)"

  def summarizeResult(input: JsonObject, result: String): String =
    // Result is always a short "started" confirmation — the actual sub-agent
    // output arrives later via ExternalEvent injection.
    if result.length > 200 then result.take(197) + "..." else result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val prompt = input("prompt").flatMap(_.asString).getOrElse("")
    val description = input("description").flatMap(_.asString).getOrElse("subtask")
    val agentName = input("agentName").flatMap(_.asString).getOrElse("Nebula")
    val modelRef = input("model").flatMap(_.asString)
    val fork = input("fork").flatMap(_.asBoolean).getOrElse(false)

    if prompt.trim.isEmpty then IO.pure(Left(ToolError("Missing required parameter: prompt")))
    else if ctx.depth >= MaxDepth then
      IO.pure(Left(ToolError(s"Maximum sub-agent depth ($MaxDepth) reached. Cannot delegate further.")))
    else
      (ctx.actorSystem, ctx.sharedResources, ctx.agentLibrary) match
        case (Some(system), Some(resources), Some(agentLibrary)) =>
          delegateToSubagent(
            prompt = prompt,
            description = description,
            agentName = agentName,
            modelRef = modelRef.filter(_.nonEmpty),
            fork = fork,
            parentMessages = if fork then ctx.messages else Nil,
            system = system,
            resources = resources,
            agentLibrary = agentLibrary,
            parentDepth = ctx.depth,
            parentRef = ctx.agentActorRef,
            wsSend = ctx.wsSend,
            projectRoot = ctx.projectRoot
          )
        case _ =>
          IO.pure(Left(ToolError("Delegate requires ActorSystem, SharedResources, and agent library")))
    end if
  end call

  private def delegateToSubagent(
    prompt: String,
    description: String,
    agentName: String,
    modelRef: Option[String],
    fork: Boolean,
    parentMessages: List[Message],
    system: ActorSystem[?],
    resources: SharedResources,
    agentLibrary: AgentLibrary,
    parentDepth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    wsSend: Option[io.circe.Json => IO[Unit]],
    projectRoot: String
  ): IO[Either[ToolError, String]] =
    // When fork=true, wrap context instruction inside the prompt as a
    // <system-reminder> so the cache prefix (system prompt + parent
    // conversation) stays intact.  Inserting a new message before the
    // parent history would break the provider-level prompt cache.
    val adjustedPrompt = if fork then
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
        // Resolve model override if specified
        modelRef match
          case None =>
            spawnAsync(
              agentDef = agentDef,
              prompt = adjustedPrompt,
              description = description,
              agentName = agentName,
              modelOverride = None,
              initialMessages = parentMessages,
              system = system,
              resources = resources,
              parentDepth = parentDepth,
              parentRef = parentRef,
              wsSend = wsSend,
              projectRoot = projectRoot
            )
          case Some(ref) =>
            resources.providerRegistry.getCandidateForRef(ref).flatMap {
              case None =>
                IO.pure(Left(ToolError(s"Model '$ref' not found. Check available models.")))
              case Some(candidate) =>
                spawnAsync(
                  agentDef = agentDef,
                  prompt = adjustedPrompt,
                  description = description,
                  agentName = agentName,
                  modelOverride = Some(candidate),
                  initialMessages = parentMessages,
                  system = system,
                  resources = resources,
                  parentDepth = parentDepth,
                  parentRef = parentRef,
                  wsSend = wsSend,
                  projectRoot = projectRoot
                )
            }
    }

  /**
   * Spawn a sub-agent actor asynchronously and return immediately.
   * The sub-agent's result is delivered to the parent via ExternalEvent.
   */
  private def spawnAsync(
    agentDef: AgentDef,
    prompt: String,
    description: String,
    agentName: String,
    modelOverride: Option[nebflow.llm.ModelCandidate],
    initialMessages: List[Message],
    system: ActorSystem[?],
    resources: SharedResources,
    parentDepth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    wsSend: Option[io.circe.Json => IO[Unit]],
    projectRoot: String
  ): IO[Either[ToolError, String]] =
    for
      readTracker <- ReadTracker.create
      fileHistory <- FileHistory.create()
      childDepth = parentDepth + 1
      subagentId = s"delegate-${agentName}-${java.util.UUID.randomUUID().toString.take(8)}"
      contextWindow = modelOverride.map(_.contextWindow).getOrElse(resources.contextWindow)
      // Set model override for this sub-agent's path name
      _ <- modelOverride match
        case Some(candidate) =>
          logger.info(s"Sub-agent $subagentId will use model ${candidate.providerId}/${candidate.model}") *>
            resources.sessionModelOverrides.update(_ + (subagentId -> candidate))
        case None => IO.unit
      childWsSend = wsSend.getOrElse((_: io.circe.Json) => IO.unit)
      // Spawn sub-agent actor
      subagentRef = system.systemActorOf(
        AgentActor(
          agentDef = agentDef,
          resources = resources,
          wsSend = childWsSend,
          depth = childDepth,
          parentRef = parentRef,
          sessionId = None,
          sessionName = Some(description),
          initialMessages = initialMessages,
          readTracker = Some(readTracker),
          fileHistory = Some(fileHistory),
          contextWindow = contextWindow,
          projectRoot = Some(projectRoot)
        ),
        subagentId
      )
      // Spawn adapter: listens for AgentEvent from sub-agent, forwards to parent as ExternalEvent
      adapterRef = system.systemActorOf(
        delegateAdapter(
          subagentRef = subagentRef,
          parentRef = parentRef,
          description = description,
          agentName = agentName,
          subagentId = subagentId,
          resources = resources
        ),
        s"$subagentId-adapter"
      )
      forkInfo = if initialMessages.nonEmpty then s", fork=${initialMessages.size}msgs" else ""
      modelInfo = modelOverride.map(c => s", model=${c.providerId}/${c.model}").getOrElse("")
      _ = logger.info(s"Spawned sub-agent: $subagentId (depth=$childDepth, agent=$agentName$modelInfo$forkInfo)")
      // Send the task — non-blocking, sub-agent runs independently
      _ = subagentRef ! AgentCommand.UserInput(prompt, Some(adapterRef))
    yield Right(s"Sub-agent '$agentName' started for: $description. You will be notified when it completes.")

  /**
   * Adapter actor behavior: receives AgentEvent from the sub-agent, forwards
   * the result to the parent via ExternalEvent, then cleans up.
   */
  private def delegateAdapter(
    subagentRef: ActorRef[AgentCommand],
    parentRef: Option[ActorRef[AgentCommand]],
    description: String,
    agentName: String,
    subagentId: String,
    resources: SharedResources
  ): Behaviors.Receive[AgentEvent] =
    Behaviors.receive[AgentEvent] { (_, event) =>
      val (eventType, payload) = event match
        case AgentEvent.Completed(_, messages) =>
          val text = extractLastAssistantText(messages)
          if text.nonEmpty then
            ("completed", s"[Sub-agent completed] \"$description\":\n$text")
          else
            ("completed", s"[Sub-agent completed] \"$description\" (no text output)")
        case AgentEvent.Failed(_, error) =>
          ("failed", s"[Sub-agent failed] \"$description\": ${error.message}")

      // Forward result to parent via ExternalEvent mechanism
      parentRef.foreach(_ ! AgentCommand.ExternalEvent(
        source = "delegate",
        eventType = eventType,
        payload = payload,
        metadata = io.circe.JsonObject(
          "description" -> description.asJson,
          "agentName" -> agentName.asJson
        ),
        correlationId = Some(subagentId)
      ))

      // Cleanup: remove model override, stop sub-agent, stop self
      resources.dispatcher.unsafeRunAndForget(
        resources.sessionModelOverrides.update(_ - subagentId)
      )
      subagentRef ! AgentCommand.Stop("delegate-complete")
      Behaviors.stopped
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
