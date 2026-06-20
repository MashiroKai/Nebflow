package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.agent.*
import nebflow.core.NebflowLogger
import nebflow.shared.{Message, MessageRole}
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.util.Timeout

import scala.concurrent.duration.*

/**
 * DelegateTool — lets an agent spawn a sub-agent for a subtask.
 *
 * Design (borrowed from Claude Code's AgentTool):
 *   - Parent agent calls Delegate with a self-contained prompt and target agent name.
 *   - A fresh sub-agent Actor is spawned via ActorSystem.systemActorOf (same
 *     pattern as MemoryAgentManager spawning the Dream agent).
 *   - Sub-agent runs a single turn (UserInput -> LLM -> tools -> done), then
 *     returns its final assistant text via AgentEvent.Completed.
 *   - Parent receives the text as the tool result.
 *
 * Depth limit: when depth >= MaxDepth, the tool is not included in the agent's
 * tool list (filtered by buildToolList in AgentCore), making it impossible to
 * recurse further.
 */
object DelegateTool extends Tool:
  private val logger = NebflowLogger(getClass)

  /** Maximum sub-agent depth (matches AgentCore.MaxDepth). */
  val MaxDepth: Int = 5

  /** Timeout for sub-agent completion. */
  private val SubagentTimeout: FiniteDuration = 10.minutes

  private implicit val askTimeout: Timeout = Timeout(SubagentTimeout)

  val name = "Delegate"

  val description =
    """Delegate a subtask to a sub-agent. The sub-agent runs autonomously with its own context and tools, then returns its final result.

Use Delegate when:
- A task can be broken into independent parts that benefit from focused context
- You need parallel research on different aspects of a problem
- A subtask requires deep focus without polluting your main conversation

Key rules:
- The sub-agent CANNOT see your conversation history. Every prompt must be self-contained with all context needed.
- State what "done" looks like (e.g. "Report findings — do not modify files").
- For research: "Report specific file paths, line numbers, and findings. Do not modify files."
- For implementation: "Make the change, run relevant tests, and report the result."
- You can delegate to different agent types by specifying agentName (e.g. "Nebula"). Defaults to "Nebula".
- Multiple Delegate calls in a single response run in parallel.

Do NOT use Delegate for:
- Trivial tasks you can handle directly with Read/Bash/etc.
- Tasks that require seeing your full conversation context.
- Sequential tasks where each step depends on the previous step's result."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "agentName" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Name of the agent definition to use (e.g. \"Nebula\"). Defaults to \"Nebula\".".asJson,
          "default" -> "Nebula".asJson
        ),
        "prompt" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Self-contained task description for the sub-agent. Must include all context needed — the sub-agent cannot see your conversation.".asJson
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
    val agent = input("agentName").flatMap(_.asString).getOrElse("Nebula")
    s"Delegate($agent: $desc)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 200 then result.take(197) + "..." else result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val prompt = input("prompt").flatMap(_.asString).getOrElse("")
    val description = input("description").flatMap(_.asString).getOrElse("subtask")
    val agentName = input("agentName").flatMap(_.asString).getOrElse("Nebula")

    if prompt.trim.isEmpty then IO.pure(Left(ToolError("Missing required parameter: prompt")))
    else if ctx.depth >= MaxDepth then
      IO.pure(Left(ToolError(s"Maximum sub-agent depth ($MaxDepth) reached. Cannot delegate further.")))
    else
      (ctx.actorSystem, ctx.sharedResources, ctx.agentLibrary, ctx.pekkoScheduler) match
        case (Some(system), Some(resources), Some(agentLibrary), Some(scheduler)) =>
          delegateToSubagent(
            prompt = prompt,
            description = description,
            agentName = agentName,
            system = system,
            resources = resources,
            agentLibrary = agentLibrary,
            scheduler = scheduler,
            parentDepth = ctx.depth,
            parentRef = ctx.agentActorRef,
            wsSend = ctx.wsSend,
            projectRoot = ctx.projectRoot
          )
        case _ =>
          IO.pure(Left(ToolError("Delegate requires ActorSystem, SharedResources, agent library, and Pekko scheduler")))
    end if
  end call

  private def delegateToSubagent(
    prompt: String,
    description: String,
    agentName: String,
    system: ActorSystem[?],
    resources: SharedResources,
    agentLibrary: AgentLibrary,
    scheduler: org.apache.pekko.actor.typed.Scheduler,
    parentDepth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    wsSend: Option[io.circe.Json => IO[Unit]],
    projectRoot: String
  ): IO[Either[ToolError, String]] =
    agentLibrary.get(agentName).flatMap {
      case None =>
        IO.pure(Left(ToolError(s"Agent '$agentName' not found in agent library")))
      case Some(agentDef) =>
        spawnAndRun(
          agentDef = agentDef,
          prompt = prompt,
          description = description,
          agentName = agentName,
          system = system,
          resources = resources,
          scheduler = scheduler,
          parentDepth = parentDepth,
          parentRef = parentRef,
          wsSend = wsSend,
          projectRoot = projectRoot
        )
    }

  private def spawnAndRun(
    agentDef: AgentDef,
    prompt: String,
    description: String,
    agentName: String,
    system: ActorSystem[?],
    resources: SharedResources,
    scheduler: org.apache.pekko.actor.typed.Scheduler,
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
      childWsSend = wsSend.getOrElse((_: io.circe.Json) => IO.unit)
      subagentRef = system.systemActorOf(
        AgentActor(
          agentDef = agentDef,
          resources = resources,
          wsSend = childWsSend,
          depth = childDepth,
          parentRef = parentRef,
          sessionId = None,
          sessionName = Some(description),
          initialMessages = Nil,
          readTracker = Some(readTracker),
          fileHistory = Some(fileHistory),
          contextWindow = resources.contextWindow,
          projectRoot = Some(projectRoot)
        ),
        subagentId
      )
      _ = logger.info(s"Spawned sub-agent: $subagentId (depth=$childDepth, agent=$agentName)")
      result <- IO
        .fromFuture(
          IO(
            subagentRef.ask[AgentEvent](replyTo => AgentCommand.UserInput(prompt, Some(replyTo)))(using
              askTimeout,
              scheduler
            )
          )
        )
        .map {
          case AgentEvent.Completed(_, messages) =>
            val text = extractLastAssistantText(messages)
            if text.nonEmpty then Right(text)
            else Right("(sub-agent completed with no text output)")
          case AgentEvent.Failed(_, error) =>
            Left(ToolError(s"Sub-agent '${error.agentName}' failed: ${error.message}"))
        }
        .recover { case _: java.util.concurrent.TimeoutException =>
          Left(ToolError(s"Sub-agent timed out after $SubagentTimeout"))
        }
      _ = subagentRef ! AgentCommand.Stop("delegate-complete")
    yield result

  /** Extract the last assistant message text from a list of messages. */
  private def extractLastAssistantText(messages: List[Message]): String =
    messages.reverse
      .collectFirst {
        case msg if msg.role == MessageRole.Assistant => msg.textContent
      }
      .filter(_.nonEmpty)
      .getOrElse("")

end DelegateTool
