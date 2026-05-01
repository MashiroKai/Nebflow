package nebflow.core.tools

import cats.effect.{IO, Ref}
import io.circe.JsonObject
import nebflow.shared.{LlmHandle, Message, TokenUsage}

/** 工具执行上下文 */
case class ToolContext(
  projectRoot: String,
  llm: Option[LlmHandle[IO]] = None,
  replUi: Option[nebflow.core.ReplUi] = None,
  messagesRef: Option[Ref[IO, List[Message]]] = None,
  sessionStore: Option[nebflow.gateway.SessionStore] = None,
  usageRef: Option[Ref[IO, Option[TokenUsage]]] = None
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
