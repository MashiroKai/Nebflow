package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.shared.Defaults

import scala.concurrent.TimeoutException
import scala.concurrent.duration.*

object BashTool extends Tool:
  val DEFAULT_TIMEOUT = 120_000L // 2 minutes
  val MAX_TIMEOUT = Defaults.BashMaxTimeoutMs // 60 minutes

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
        "command" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "The bash command to run. Required unless background_job_id is provided.".asJson
        ),
        "timeout" -> io.circe.Json
          .obj("type" -> "number".asJson, "description" -> "Optional timeout in milliseconds (max 600000)".asJson),
        "description" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Clear, concise description of what this command does".asJson
        ),
        "run_in_background" -> io.circe.Json.obj(
          "type" -> "boolean".asJson,
          "description" -> "If true, run the command in the background and return a job ID immediately".asJson
        ),
        "dangerouslyDisableSandbox" -> io.circe.Json.obj(
          "type" -> "boolean".asJson,
          "description" -> "ONLY use when absolutely necessary. This bypasses injection pattern checks (e.g. command substitution). Permanently dangerous commands (rm -rf, git push --force, etc.) remain blocked regardless. Use with extreme caution.".asJson
        ),
        "background_job_id" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Job ID to query or cancel. When provided, command is not required.".asJson
        ),
        "cancel_background_job" -> io.circe.Json.obj(
          "type" -> "boolean".asJson,
          "description" -> "If true with background_job_id, cancel the background job.".asJson
        )
      ),
      "required" -> io.circe.Json.arr() // command is conditionally required: required when background_job_id is absent
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

  // Injection patterns: default block, bypass with dangerouslyDisableSandbox
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
    val bgJobId = input("background_job_id").flatMap(_.asString)
    (desc, bgJobId) match
      case (Some(d), _) => s"Bash($d)"
      case (_, Some(id)) => s"Bash(query job $id)"
      case _ =>
        val cmd = input("command").flatMap(_.asString).getOrElse("").trim
        val firstLine = cmd.split('\n').headOption.getOrElse(cmd)
        if firstLine.isEmpty then "Bash(empty)"
        else if firstLine.length > 50 then s"Bash(${firstLine.take(47)}...)"
        else s"Bash($firstLine)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.contains("[Command timed out") then "Timed out"
    else if result.startsWith("[Blocked by sandbox") then "Blocked"
    else if result.startsWith("[Background job") then "Background"
    else if result.startsWith("[Sandbox bypassed]") then "Sandbox bypassed"
    else if result.startsWith("[Command executed successfully with no output]") then "No output"
    else
      val lines = result.split('\n').filter(_.trim.nonEmpty)
      if lines.isEmpty then "No output"
      else if lines.length == 1 then lines.head
      else s"${lines.length} lines of output"

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val timeout = input("timeout")
      .flatMap(_.asNumber)
      .flatMap(_.toLong)
      .map(t => t.max(1L).min(MAX_TIMEOUT))
      .getOrElse(DEFAULT_TIMEOUT)
    val commandOpt = input("command").flatMap(_.asString)
    val command = commandOpt.getOrElse("")
    val background = input("run_in_background").flatMap(_.asBoolean).getOrElse(false)
    val desc = input("description").flatMap(_.asString)
    val bypass = input("dangerouslyDisableSandbox").flatMap(_.asBoolean).getOrElse(false)
    val bgJobId = input("background_job_id").flatMap(_.asString)
    val cancelBg = input("cancel_background_job").flatMap(_.asBoolean).getOrElse(false)

    val sessionId = ctx.sessionId.getOrElse("default")
    val timeoutDuration = timeout.millis

    // If background_job_id is provided, enter query/cancel mode
    bgJobId match
      case Some(jobId) =>
        ShellSession.forSession(sessionId).flatMap { shell =>
          if cancelBg then
            shell.cancelBackgroundJob(jobId).map { cancelled =>
              if cancelled then Right(s"[Background job cancelled] Job ID: $jobId")
              else Right(s"[Background job not found] Job ID: $jobId")
            }
          else
            shell.getBackgroundResult(jobId).map {
              case None => Right(s"[Background job pending] Job ID: $jobId")
              case Some(Left(e)) =>
                val errMsg = e match
                  case _: TimeoutException => "[Command timed out]"
                  case _ => s"Error: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}"
                Right(s"[Background job failed] Job ID: $jobId\n$errMsg")
              case Some(Right(result)) =>
                val out = result.stdout
                val errLine = if result.stderr.nonEmpty then s"\n[stderr]:\n${result.stderr}" else ""
                Right(s"[Background job completed] Job ID: $jobId\n$out$errLine")
            }
        }
      case None =>
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
          (injectionWarning, bypass) match
            case (Some(warning), false) =>
              IO.pure(Left(ToolError(s"[Blocked by sandbox] Injection detected: $warning")))
            case _ =>
              val sandboxMark = injectionWarning.map(_ => "[Sandbox bypassed] ").getOrElse("")
              ShellSession.forSession(sessionId).flatMap { shell =>
                if background then
                  shell.executeBackground(command, timeoutDuration).map { jobId =>
                    Right(s"$sandboxMark[Background job started] Job ID: $jobId\nUse this ID to check status later.")
                  }
                else executeAndFormat(shell, command, timeoutDuration, desc, sandboxMark)
              }
          end match
        end if
    end match
  end call

  private def executeAndFormat(
    shell: ShellSession,
    command: String,
    timeout: FiniteDuration,
    desc: Option[String],
    sandboxMark: String
  ): IO[Either[ToolError, String]] =
    shell
      .execute(command, timeout)
      .map { result =>
        val prefix = desc.map(d => s"[$d]\n").getOrElse("")
        val dirLine = s"(cwd: ${result.cwd})\n"
        val errLine = if result.stderr.nonEmpty then s"\n[stderr]:\n${result.stderr}" else ""
        val output = result.stdout + errLine
        val full = sandboxMark + prefix + dirLine + output
        if full.trim.isEmpty then Right(sandboxMark + "[Command executed successfully with no output]")
        else Right(full)
      }
      .handleErrorWith {
        case _: TimeoutException => IO.pure(Left(ToolError(s"[Command timed out after ${timeout.toMillis}ms]")))
        case e => IO.pure(Left(ToolError(s"Error: ${e.getMessage}")))
      }
end BashTool
