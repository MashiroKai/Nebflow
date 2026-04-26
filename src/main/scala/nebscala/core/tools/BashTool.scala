package nebscala.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*

object BashTool extends Tool:
  val DEFAULT_TIMEOUT = 120_000L // 2 minutes
  val MAX_TIMEOUT = 600_000L // 10 minutes

  val name = "Bash"

  val description = """Executes a given bash command and returns its output.

Usage:
- The working directory persists between commands, but shell state does not persist across Nebscala restarts.
- Try to maintain your current working directory throughout the session by using absolute paths and avoiding usage of cd.
- You may specify an optional timeout in milliseconds (up to 600000ms / 10 minutes). By default, your command will timeout after 120000ms (2 minutes).
- For git commands: Prefer to create a new commit rather than amending an existing commit.
- Only create commits when requested by the user."""

  val inputSchema = JsonObject.fromIterable(List(
    "type" -> "object".asJson,
    "properties" -> io.circe.Json.obj(
      "command" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "The bash command to run".asJson),
      "timeout" -> io.circe.Json.obj("type" -> "number".asJson, "description" -> "Optional timeout in milliseconds (max 600000)".asJson),
      "description" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "Clear, concise description of what this command does".asJson)
    ),
    "required" -> io.circe.Json.arr("command".asJson)
  ))

  def summarize(input: JsonObject): String =
    val desc = input("description").flatMap(_.asString)
    desc match
      case Some(d) => s"Bash($d)"
      case None =>
        val cmd = input("command").flatMap(_.asString).getOrElse("").trim
        val firstLine = cmd.split("\\n").headOption.getOrElse(cmd)
        if firstLine.length > 50 then s"Bash(${firstLine.take(47)}...)"
        else s"Bash($firstLine)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.startsWith("[Command timed out") then "Timed out"
    else
      val lines = result.split("\\n").filter(_.trim.nonEmpty)
      if lines.length <= 1 then lines.headOption.getOrElse("No output")
      else s"${lines.length} lines of output"

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val timeout = input("timeout").flatMap(_.asNumber).flatMap(_.toLong)
      .map(t => Math.min(t, MAX_TIMEOUT))
      .getOrElse(DEFAULT_TIMEOUT)
    val command = input("command").flatMap(_.asString).getOrElse("")

    if command.isEmpty then IO.pure(Right("[Empty command]"))
    else
      val shell = PersistentShell.get()
      shell.execute(command, timeout).map { output =>
        if output.isEmpty then Right("[Command executed successfully with no output]")
        else if output.contains("timed out") then Right(s"[Command timed out after ${timeout}ms]")
        else Right(output)
      }.handleError { e =>
        val msg = e.getMessage
        if msg.contains("timed out") then Right(s"[Command timed out after ${timeout}ms]")
        else Right(s"Error: $msg")
      }
