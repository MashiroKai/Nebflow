package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.NebflowLogger

import java.util.concurrent.TimeUnit

import scala.io.Source
import scala.util.Using.resource

class ScriptTool(config: ExternalToolConfig) extends Tool:
  private val logger = NebflowLogger(getClass)

  val name = config.name
  val description = config.description
  val inputSchema = config.inputSchema

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val jsonInput = Json.fromJsonObject(input).noSpaces
    logger.info(s"ScriptTool executing", "name" -> config.name, "command" -> config.command) *>
      IO.blocking {
        val pb = ProcessBuilder("sh", "-c", config.command)
        pb.directory(new java.io.File(ctx.projectRoot))
        pb.redirectErrorStream(false)
        val process = pb.start()

        // Write JSON input to stdin
        val stdin = process.getOutputStream
        stdin.write(jsonInput.getBytes("UTF-8"))
        stdin.close()

        val finished = process.waitFor(config.timeoutSeconds, TimeUnit.SECONDS)
        if !finished then
          process.destroyForcibly()
          Left(ToolError(s"Script '${config.name}' timed out after ${config.timeoutSeconds}s"))
        else if process.exitValue() == 0 then
          val stdout = resource(Source.fromInputStream(process.getInputStream, "UTF-8"))(_.mkString)
          Right(stdout)
        else
          val stderr = resource(Source.fromInputStream(process.getErrorStream, "UTF-8"))(_.mkString)
          Left(ToolError(stderr))
      }.handleError(e => Left(ToolError(s"Script execution failed: ${e.getMessage}")))
  end call

  def summarize(input: JsonObject): String = s"${config.name}(...)"

  def summarizeResult(input: JsonObject, result: String): String = s"${config.name} completed"
end ScriptTool
