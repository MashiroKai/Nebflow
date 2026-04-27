package nebflow.core.tools

import nebflow.shared.LlmHandle
import io.circe.JsonObject
import cats.effect.IO

/** 工具执行上下文 */
case class ToolContext(
  projectRoot: String,
  llm: Option[LlmHandle[IO]] = None
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
