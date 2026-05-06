package nebflow.core.tools

import cats.effect.std.Semaphore
import cats.effect.{IO, Ref}
import io.circe.{Json, JsonObject}
import nebflow.agent.{AgentCommand, AgentDef, AgentLibrary}
import nebflow.core.task.TaskStore
import nebflow.core.{AskItem, AskOption, FileChangeTracker}
import nebflow.shared.*
import org.apache.pekko.actor.typed.ActorRef

/** Tool execution context — provides all dependencies a tool may need. */
case class ToolContext(
  projectRoot: String,
  llm: Option[LlmHandle[IO]] = None,
  sessionStore: Option[nebflow.gateway.SessionStore] = None,
  agentActorRef: Option[ActorRef[AgentCommand]] = None,
  contextWindow: Int = Defaults.ContextWindow,
  sessionId: Option[String] = None,
  taskStore: Option[TaskStore] = None,
  wsSend: Option[Json => IO[Unit]] = None,
  readTracker: Option[ReadTracker] = None,
  // Agent-scoped context for formerly-synthetic tools
  parentRef: Option[ActorRef[AgentCommand]] = None,
  depth: Int = 0,
  agentDef: Option[AgentDef] = None,
  agentLibrary: Option[AgentLibrary] = None,
  askSemaphore: Option[Semaphore[IO]] = None,
  pekkoScheduler: Option[org.apache.pekko.actor.typed.Scheduler] = None,
  fileLockManager: Option[FileLockManager] = None,
  fileChangeTracker: Option[FileChangeTracker] = None
)

/** Tool error */
case class ToolError(message: String)

/** Process execution result */
case class ProcessResult(stdout: String, stderr: String, exitCode: Int, cwd: String)

/** Tool interface — all tools (built-in, synthetic, MCP) implement this trait. */
trait Tool:
  def name: String
  def description: String
  def inputSchema: JsonObject
  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]]
  def summarize(input: JsonObject): String
  def summarizeResult(input: JsonObject, result: String): String
