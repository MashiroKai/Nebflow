package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*

object BashTool extends Tool:
  val DEFAULT_TIMEOUT = 120_000L // 2 minutes
  val MAX_TIMEOUT = 600_000L // 10 minutes

  val name = "Bash"

  val description = """Executes a given bash command and returns its output.

Usage:
- The working directory persists between commands, but shell state does not persist across Nebflow restarts.
- Try to maintain your current working directory throughout the session by using absolute paths and avoiding usage of cd.
- You may specify an optional timeout in milliseconds (up to 600000ms / 10 minutes). By default, your command will timeout after 120000ms (2 minutes).
- Use run_in_background for long-running commands. Retrieve results later with the returned job ID.
- Dangerous commands (rm -rf, force push, etc.) are blocked for safety.
- For git commands: Prefer to create a new commit rather than amending an existing commit.
- Only create commits when requested by the user."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "command" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "The bash command to run".asJson),
        "timeout" -> io.circe.Json
          .obj("type" -> "number".asJson, "description" -> "Optional timeout in milliseconds (max 600000)".asJson),
        "description" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Clear, concise description of what this command does".asJson
        ),
        "run_in_background" -> io.circe.Json.obj(
          "type" -> "boolean".asJson,
          "description" -> "If true, run the command in the background and return a job ID immediately".asJson
        )
      ),
      "required" -> io.circe.Json.arr("command".asJson)
    )
  )

  // Security rules: patterns that are blocked in sandbox mode
  private val DangerousPatterns = List(
    """rm\s+-rf\s+""".r,
    """rm\s+-fr\s+""".r,
    """git\s+push\s+.*--force""".r,
    """git\s+push\s+.*-f\b""".r,
    """git\s+reset\s+--hard""".r,
    """git\s+clean\s+-f""".r,
    """git\s+checkout\s+--\s*\.""".r,
    """git\s+branch\s+-D\s+(main|master)""".r,
    """>\s*~/.ssh/""".r,
    """rm\s+.*\.env""".r,
    """rm\s+.*~/.nebflow""".r,
    """>\s*~/.gnupg/""".r,
    """>\s*~/.aws/""".r
  )

  def isDangerous(command: String): Boolean =
    DangerousPatterns.exists(_.findFirstIn(command).isDefined)

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
    else if result.startsWith("[Blocked by sandbox") then "Blocked"
    else if result.startsWith("[Background job") then "Background"
    else
      val lines = result.split("\\n").filter(_.trim.nonEmpty)
      if lines.length <= 1 then lines.headOption.getOrElse("No output")
      else s"${lines.length} lines of output"

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val timeout = input("timeout")
      .flatMap(_.asNumber)
      .flatMap(_.toLong)
      .map(t => Math.min(t, MAX_TIMEOUT))
      .getOrElse(DEFAULT_TIMEOUT)
    val command = input("command").flatMap(_.asString).getOrElse("")
    val background = input("run_in_background").flatMap(_.asBoolean).getOrElse(false)
    val desc = input("description").flatMap(_.asString)

    if command.isEmpty then IO.pure(Right("[Empty command]"))
    else if isDangerous(command) then
      IO.pure(
        Left(
          ToolError(
            "[Blocked by sandbox] This command was blocked for safety. Dangerous operations require user approval through the permission system."
          )
        )
      )
    else
      val shell = PersistentShell.get()

      if background then
        // Background execution
        val jobId = shell.executeBackground(command, timeout)
        IO.pure(Right(s"[Background job started] Job ID: $jobId\nUse this ID to check status later."))
      else
        val prefix = desc match
          case Some(d) => s"[$d]\n"
          case None => ""

        shell
          .execute(command, timeout)
          .map { output =>
            val dir = shell.getCurrentDir
            val dirLine = if dir.nonEmpty then s"(cwd: $dir)\n" else ""
            val fullOutput = prefix + dirLine + output
            if fullOutput.isEmpty then Right("[Command executed successfully with no output]")
            else if output.contains("timed out") then Left(ToolError(s"[Command timed out after ${timeout}ms]"))
            else Right(fullOutput)
          }
          .handleError { e =>
            val msg = e.getMessage
            if msg.contains("timed out") then Left(ToolError(s"[Command timed out after ${timeout}ms]"))
            else Left(ToolError(s"Error: $msg"))
          }
    end if
  end call
end BashTool
