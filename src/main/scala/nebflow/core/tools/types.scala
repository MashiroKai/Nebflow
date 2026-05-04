package nebflow.core.tools

import cats.effect.{IO, Ref}
import io.circe.{Json, JsonObject}
import nebflow.core.task.TaskStore
import nebflow.shared.*

/** 工具执行上下文 */
case class ToolContext(
  projectRoot: String,
  llm: Option[LlmHandle[IO]] = None,
  // Access constraint: sessionStore is a persistence layer — tools should not modify
  // messages directly. Only session-management tools (e.g. NewSessionTool) may use it.
  sessionStore: Option[nebflow.gateway.SessionStore] = None,
  agentActorRef: Option[org.apache.pekko.actor.typed.ActorRef[nebflow.agent.AgentCommand]] = None,
  contextWindow: Int = Defaults.ContextWindow,
  sessionId: Option[String] = None,
  taskStore: Option[TaskStore] = None,
  wsSend: Option[Json => IO[Unit]] = None,
  readTracker: Option[ReadTracker] = None
)

/** 工具错误 */
case class ToolError(message: String)

/** 工具接口 */
trait Tool:
  def name: String
  def description: String
  def inputSchema: JsonObject
  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]]
  def summarize(input: JsonObject): String
  def summarizeResult(input: JsonObject, result: String): String
