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
- Only create commits when requested by the user.

Do not use Bash when a dedicated tool exists:
| Task | Use | Not Bash |
|------|-----|----------|
| Read a file | `Read` | `cat`, `head`, `tail` |
| Search file contents | `Grep` | `grep`, `rg` |
| Find files by name | `Glob` | `find`, `ls` |
| Edit a file | `Edit` | `sed`, `awk` |
| Create a file | `Write` | `echo >` |

Git safety:
- NEVER update the git config (`git config`).
- NEVER run destructive git commands (`push --force`, `reset --hard`, `checkout .`, `restore .`, `clean -f`, `branch -D`) unless the user explicitly requests these actions.
- CRITICAL: Always create NEW commits rather than amending. Only amend when the user explicitly says to.
- When staging files, prefer adding specific files by name rather than `git add -A`.
- NEVER commit files that likely contain secrets (`.env`, `credentials.json`, service account keys, etc.).
- NEVER use `git rebase -i` or `git add -i` — these require interactive input which is not supported."""

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
    """>\s*~/.aws/""".r,
    // Database destructive operations
    """(?i)\bDROP\s+(TABLE|DATABASE|SCHEMA)""".r,
    """(?i)\bTRUNCATE\s+TABLE?\b""".r,
    // Infrastructure destructive operations
    """(?i)\bkubectl\s+delete\s+(namespace|cluster|deployment|statefulset|pv|pvc)""".r,
    """(?i)\bterraform\s+(destroy|apply).*(-destroy)""".r,
    """(?i)\bdocker\s+(system|volume)\s+prune""".r,
    // Package publishing
    """(?i)\bnpm\s+publish""".r,
    """(?i)\bpypi\s+upload""".r,
    """(?i)\btwine\s+upload""".r,
    """(?i)\bmvn\s+deploy""".r,
    """(?i)\bsbt\s+publish""".r,
    // Fork bomb
    """:\(\)\{\s*:\|:&\s*\}""".r,
    """fork\s+bomb""".r
  )

  // Injection patterns: warned but not blocked
  private val InjectionPatterns = List(
    ("""\$\(\s*.*?\brm\b""".r, "Command substitution containing rm detected"),
    ("""`\s*.*?\brm\b""".r, "Backtick substitution containing rm detected"),
    ("""IFS\s*=""".r, "IFS manipulation detected"),
    ("""\\x00""".r, "Null byte injection detected")
  )

  def isDangerous(command: String): Boolean =
    DangerousPatterns.exists(_.findFirstIn(command).isDefined)

  def checkInjection(command: String): Option[String] =
    InjectionPatterns.collectFirst {
      case (pattern, msg) if pattern.findFirstIn(command).isDefined => msg
    }

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
            "[Blocked by sandbox] This command is permanently blocked for safety and cannot be approved."
          )
        )
      )
    else
      val injectionWarning = checkInjection(command)
      if injectionWarning.isDefined then
        val warning = injectionWarning.get
        val shell = PersistentShell.get()
        val prefix = desc match
          case Some(d) => s"[$d]\n"
          case None => ""

        shell
          .execute(command, timeout)
          .map { output =>
            val dir = shell.getCurrentDir
            val dirLine = if dir.nonEmpty then s"(cwd: $dir)\n" else ""
            val warnLine = s"[Warning: $warning]\n"
            val fullOutput = warnLine + prefix + dirLine + output
            if fullOutput.isEmpty then Right(s"$warnLine[Command executed successfully with no output]")
            else if output.contains("timed out") then Left(ToolError(s"[Command timed out after ${timeout}ms]"))
            else Right(fullOutput)
          }
          .handleError { e =>
            val msg = e.getMessage
            if msg.contains("timed out") then Left(ToolError(s"[Command timed out after ${timeout}ms]"))
            else Left(ToolError(s"Error: $msg"))
          }
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
      end if
    end if
  end call
end BashTool
