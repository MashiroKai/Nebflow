package nebflow.core.tools

import cats.effect.{Deferred, IO}
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.agent.AgentCommand
import nebflow.core.NebflowLogger
import nebflow.shared.Defaults

import scala.concurrent.TimeoutException
import scala.concurrent.duration.*

object BashTool extends Tool:
  private val logger = NebflowLogger(getClass)

  /** Bash output can be very large — persist early. */
  override val maxResultSizeChars: Int = 30_000

  val DEFAULT_TIMEOUT = 120_000L // 2 minutes
  val MAX_TIMEOUT = Defaults.BashMaxTimeoutMs // 60 minutes
  /** Foreground commands running longer than this are automatically moved to background. */
  val AutoBackgroundThresholdMs = 120_000L

  val name = "Bash"

  val description = """Executes a given bash command and returns its output.

Usage:
- The working directory persists between commands, but shell state does not persist across Nebflow restarts.
- Try to maintain your current working directory throughout the session by using absolute paths and avoiding usage of cd.
- You may specify an optional timeout in milliseconds (up to 600000ms / 10 minutes). By default, your command will timeout after 120000ms (2 minutes).
- Dangerous commands (rm -rf, force push, etc.) are blocked for safety.
- For git commands: Prefer to create a new commit rather than amending an existing commit.
- Only create commits when requested by the user.

Background execution (run_in_background):
- Use for long-running commands (builds, tests, servers, deploys, remote SSH operations, etc.).
- **Use `run_in_background: true`, never `&` or `nohup`.** Shell backgrounding (`&`) bypasses Nebflow's task tracking — you won't be notified when it finishes, and the frontend won't show the background indicator.
- You will be automatically notified when the job finishes. DO NOT poll or use sleep loops.
- After starting a background job, continue with other work or finish your turn.
- If a foreground command exceeds 2 minutes, it is automatically moved to background — same rules apply.

Querying background jobs (background_job_id):
- Only query when you receive a "stuck" notification or the user asks about a job's status.

Do not use Bash when a dedicated tool exists:
| Task | Use | Not Bash |
|------|-----|----------|
| Read a file | `Read` | `cat`, `head`, `tail` |
| Search file contents | `Grep` | `grep`, `rg` |
| Find files by name | `Glob` | `find`, `ls` |
| Edit a file | `Edit` | `sed`, `awk` |
| Create a file | `Write` | `echo >` |
| Emit card / JSON payload | `Card` | `echo` |

Never use Bash `echo` to emit card JSON or structured UI payloads. Always use the `Card` tool — it bypasses shell escaping issues and is recognized by the frontend renderer.

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
          "description" -> "Run the command in the background. You will be automatically notified when it finishes — continue with other work or end your turn, the result will come to you.".asJson
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

  // Security rules: patterns that require user approval
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
    // Process killing — prevents agent from killing nebflow or other critical processes
    """\bpkill\b""".r,
    """\bkillall\b""".r,
    """(?i)\bkill\s+(-[0-9]+)?\s*\d+""".r,
    """(?i)\bsystemctl\s+(stop|kill|restart)\b""".r,
    """(?i)\blaunchctl\s+(stop|kill)\b""".r,
    // Git branch switching — can lose uncommitted changes
    """git\s+checkout\s+(?!-b\b)(?!--\s)(?!\*\.)""".r,
    """git\s+switch\b""".r,
    """git\s+stash\s+(drop|clear)\b""".r,
    """git\s+rebase\b""".r,
    """git\s+merge\b""".r,
    // Fork bomb
    """:\(\)\{\s*:\|:&\s*\}""".r,
    """fork\s+bomb""".r
  )

  // Interactive command patterns: commands that require terminal interaction.
  // These cannot work in this environment because stdin is /dev/null and there is no tty.
  private val InteractivePatterns = List(
    ("""^\s*top\b(?!.*-b)""".r, "`top` is interactive. Use `top -b -n 1` for batch output."),
    ("""^\s*(htop|btop|atop)\b""".r, "Interactive system monitor. Use `ps aux` or `top -b -n 1` instead."),
    ("""^\s*(less|more)\b""".r, "Interactive pager. Use Read tool, `cat`, `head`, or `tail` instead."),
    ("""^\s*(vim?|nano|emacs|pico)\b""".r, "Interactive text editor. Use Edit or Write tool instead."),
    ("""^\s*(tmux|screen)\b""".r, "Terminal multiplexer. Run this command manually in your terminal."),
    ("""^\s*(gdb|lldb)\b""".r, "Interactive debugger. Run this command manually in your terminal."),
    ("""^\s*passwd\b""".r, "`passwd` requires interactive terminal. Run manually in your terminal."),
    ("""^\s*su\b""".r, "`su` requires interactive terminal. Run manually in your terminal."),
    ("""crontab\s+-e\b""".r, "`crontab -e` opens an editor. Use `crontab <file>` instead."),
    ("""git\s+rebase\s+-i\b""".r, "Interactive rebase. Use non-interactive git commands."),
    ("""git\s+add\s+-i\b""".r, "Interactive staging. Use `git add <file>` instead.")
  )

  // Injection patterns: gated by ToolReversibility — requires user confirmation
  private val InjectionPatterns = List(
    ("""\$\(\s*.*?\brm\b""".r, "Command substitution containing rm detected"),
    ("""`\s*.*?\brm\b""".r, "Backtick substitution containing rm detected"),
    ("""IFS\s*=""".r, "IFS manipulation detected"),
    ("""\\x00""".r, "Null byte injection detected")
  )

  def isDangerous(command: String): Boolean =
    DangerousPatterns.exists(_.findFirstIn(command).isDefined)

  /** Returns a danger level: 0 = safe, 1 = warning (git ops), 2 = dangerous (deletion/kill), 3 = critical (system destruction). */
  def dangerLevel(command: String): Int =
    val criticalPatterns = List(
      """rm\s+-rf\s+/\s*$""".r,
      """rm\s+-rf\s+/\*\s*""".r,
      """pkill\s+(-f\s+)?.*nebflow""".r,
      """killall\s+.*java""".r,
      """(?i)format\s+/""".r,
      """mkfs\b""".r,
      """dd\s+if=.*of=/dev/""".r
    )
    val dangerousPatterns = List(
      """rm\s+-rf\s+""".r,
      """rm\s+-fr\s+""".r,
      """\bpkill\b""".r,
      """\bkillall\b""".r,
      """(?i)\bkill\s+(-[0-9]+)?\s*\d+""".r,
      """docker\s+(system|volume)\s+prune""".r,
      """(?i)\bkubectl\s+delete\b""".r,
      """git\s+reset\s+--hard""".r,
      """git\s+clean\s+-f""".r
    )
    val warningPatterns = List(
      """git\s+checkout\s+(?!-b\b)(?!--\s)(?!\*\.)""".r,
      """git\s+switch\b""".r,
      """git\s+stash\s+(drop|clear)\b""".r,
      """git\s+rebase\b""".r,
      """git\s+merge\b""".r,
      """git\s+branch\s+-D""".r,
      """git\s+push\s+.*--force""".r
    )
    if criticalPatterns.exists(_.findFirstIn(command).isDefined) then 3
    else if dangerousPatterns.exists(_.findFirstIn(command).isDefined) then 2
    else if warningPatterns.exists(_.findFirstIn(command).isDefined) then 1
    else 0

  end dangerLevel

  def checkInjection(command: String): Option[String] =
    InjectionPatterns.collectFirst {
      case (pattern, msg) if pattern.findFirstIn(command).isDefined => msg
    }

  def checkInteractive(command: String): Option[String] =
    InteractivePatterns.collectFirst {
      case (pattern, msg) if pattern.findFirstIn(command).isDefined => msg
    }

  def summarize(input: JsonObject): String =
    val cmd = input("command").flatMap(_.asString).getOrElse("").trim
    val bgJobId = input("background_job_id").flatMap(_.asString)
    bgJobId match
      case Some(id) => s"Bash(query job $id)"
      case _ =>
        val firstLine = cmd.split('\n').headOption.getOrElse(cmd)
        if firstLine.isEmpty then "Bash(empty)"
        else if firstLine.length > 120 then s"Bash\n  (${firstLine.take(117)}...)"
        else s"Bash\n  ($firstLine)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.contains("[Command timed out") then "Timed out"
    else if result.startsWith("[Interactive command]") then "Interactive blocked"
    else if result.startsWith("[Background job") then "Background"
    else if result.startsWith("[moved to background]") then "Auto-background"
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
    val bgJobId = input("background_job_id").flatMap(_.asString)
    val cancelBg = input("cancel_background_job").flatMap(_.asBoolean).getOrElse(false)

    val sessionId = ctx.sessionId.getOrElse("default")
    val timeoutDuration = timeout.millis

    // If background_job_id is provided, enter query/cancel mode
    bgJobId match
      case Some(jobId) =>
        ShellSession.forSession(sessionId, Some(ctx.projectRoot)).flatMap { shell =>
          if cancelBg then
            shell.cancelBackgroundJob(jobId).map { cancelled =>
              if cancelled then Right(s"[Background job cancelled] Job ID: $jobId")
              else Right(s"[Background job not found] Job ID: $jobId")
            }
          else
            shell.getBackgroundResult(jobId).flatMap {
              case None =>
                // Job still running — fetch health info for the agent
                shell.getBackgroundJobHealth(jobId).map {
                  case Some(h) =>
                    val stuckThreshold = Defaults.BgStuckThresholdSec * 1000L
                    val isStuck = h.idleMs > stuckThreshold
                    val idleInfo = if h.idleMs > 60000 then s" | idle ${h.idleMs / 1000}s" else ""
                    val stuckWarning =
                      if isStuck then
                        s"\n⚠ No output for ${h.idleMs / 1000}s — process may be stuck. Use cancel_background_job: true to kill it."
                      else ""
                    Right(
                      s"[Background job running] Job ID: $jobId\n  ${h.runningMs / 1000}s running | ${h.outputLineCount} lines output | alive: ${h.isAlive}$idleInfo$stuckWarning"
                    )
                  case None =>
                    Right(s"[Background job pending] Job ID: $jobId")
                }
              case Some(Left(e)) =>
                val errMsg = e match
                  case _: TimeoutException => "[Command timed out]"
                  case _ => s"Error: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}"
                IO.pure(Right(s"[Background job failed] Job ID: $jobId\n$errMsg"))
              case Some(Right(result)) =>
                val out = cleanOutput(sanitizeCardOutput(result.stdout))
                val cleanedErr = cleanOutput(result.stderr)
                val errLine = if cleanedErr.nonEmpty then s"\n[stderr]:\n$cleanedErr" else ""
                IO.pure(Right(s"[Background job completed] Job ID: $jobId\n$out$errLine"))
            }
        }
      case None =>
        if command.isEmpty then IO.pure(Right("[Empty command]"))
        else
          val interactiveWarning = checkInteractive(command)
          if interactiveWarning.isDefined then
            IO.pure(
              Left(
                ToolError(
                  s"[Interactive command] ${interactiveWarning.get}\nThis command cannot run in the agent environment (no terminal available). Please execute it manually in your terminal."
                )
              )
            )
          else
            ShellSession.forSession(sessionId, Some(ctx.projectRoot)).flatMap { shell =>
              if background then
                val onHeartbeat = makeHeartbeatCallback(command, desc, ctx)
                val firstLine = command.split('\n').headOption.getOrElse(command).take(80)
                val bgDescription = desc.getOrElse(firstLine)
                for
                  jobId <- IO.randomUUID.map(_.toString.take(8))
                  onComplete = makeNotifyCallback(command, desc, ctx, jobId)
                  _ <- shell.executeBackground(command, timeoutDuration, desc, onComplete, onHeartbeat, Some(jobId))
                  _ <- emitBgTaskStarted(ctx, jobId, bgDescription)
                yield Right(
                  s"[Background job started] Job ID: $jobId\nThe command is running in the background. You will be automatically notified when it finishes — continue with other work or finish your turn."
                )
              else executeForegroundWithAutoBackground(shell, command, timeoutDuration, desc, ctx)
            }
          end if
    end match
  end call

  /**
   * Execute a command in the foreground with an auto-background threshold.
   * If the command completes within the threshold, return result directly.
   * If the threshold expires first, the command continues running in the background
   * and its result will be delivered via BackgroundTaskNotification when it finishes.
   */
  private def executeForegroundWithAutoBackground(
    shell: ShellSession,
    command: String,
    timeout: FiniteDuration,
    desc: Option[String],
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    val threshold = AutoBackgroundThresholdMs.millis
    val health = new JobHealth()
    for
      // Ref to store command result when it finishes
      resultRef <- IO.ref[Option[Either[Throwable, ProcessResult]]](None)
      // Deferred signals "something happened" (either command done or threshold expired)
      signal <- Deferred[IO, Unit]
      // Flag to distinguish which side won
      thresholdWon <- IO.ref(false)
      autoBgJobId <- IO.randomUUID.map(_.toString.take(8))

      // Start the actual command — runs to completion, never cancelled
      commandFiber <- (for
        r <- shell.execute(command, timeout, Some(health)).attempt
        _ <- resultRef.set(Some(r))
        _ <- signal.complete(()).void // no-op if threshold already completed it
      yield ()).start

      // Start the threshold timer — capture fiber reference for cleanup
      thresholdFiber <- (for
        _ <- IO.sleep(threshold)
        _ <- thresholdWon.set(true)
        _ <- signal.complete(()).void // no-op if command already completed it
      yield ()).start

      // Block until either command finishes or threshold expires
      _ <- signal.get
      didThresholdWin <- thresholdWon.get
      resultOpt <- resultRef.get
      // If threshold won, start a background fiber that waits for command completion and notifies agent
      _ <-
        if didThresholdWin then
          val onComplete = makeNotifyCallback(command, desc, ctx, autoBgJobId)
          val onHeartbeat = makeHeartbeatCallback(command, desc, ctx)
          val firstLine = command.split('\n').headOption.getOrElse(command).take(80)
          val bgDescription = desc.getOrElse(firstLine)
          // Register the commandFiber into ShellSession so cancelBackgroundJob can find it
          val register = ShellSession
            .forSession(ctx.sessionId.getOrElse(""))
            .flatMap { shell =>
              shell.registerBackgroundJob(autoBgJobId, commandFiber, command, health)
            }
            .handleErrorWith(_ => IO.unit)
          emitBgTaskStarted(ctx, autoBgJobId, bgDescription) *>
            register *>
            startAutoBgHeartbeat(autoBgJobId, health, resultRef, onHeartbeat) *>
            onComplete.fold(IO.unit) { cb =>
              (for
                _ <- commandFiber.joinWithNever
                r <- resultRef.get
                _ <- cb(r.getOrElse(Left(new Exception("No result after fiber completed"))))
              yield ()).start.void
            }
        else thresholdFiber.cancel // Command finished before threshold — cancel timer to prevent fiber leak
    yield
      if !didThresholdWin then
        resultOpt match
          case Some(Right(pr)) => formatResult(pr, desc)
          case Some(Left(_: TimeoutException)) => Left(ToolError(s"[Command timed out after ${timeout.toMillis}ms]"))
          case Some(Left(e)) => Left(ToolError(s"Error: ${e.getMessage}"))
          case None => Left(ToolError("[Unexpected: no result from foreground command]"))
      else
        Right(
          s"[Command moved to background] It has been running for over ${threshold.toSeconds}s and will continue in the background. You will be automatically notified when it finishes — continue with other work or finish your turn."
        )
    end for

  end executeForegroundWithAutoBackground

  private def formatResult(
    result: ProcessResult,
    desc: Option[String]
  ): Either[ToolError, String] =
    val prefix = desc.map(d => s"[$d]\n").getOrElse("")
    val dirLine = s"(cwd: ${result.cwd})\n"
    val cleanedOut = cleanOutput(sanitizeCardOutput(result.stdout))
    val cleanedErr = cleanOutput(result.stderr)
    val errLine = if cleanedErr.nonEmpty then s"\n[stderr]:\n$cleanedErr" else ""
    val output = cleanedOut + errLine
    val full = prefix + dirLine + output
    if full.trim.isEmpty then Right("[Command executed successfully with no output]")
    else Right(full)

  /**
   * Best-effort repair of shell-damaged card JSON output.
   * When Bash echo is used to emit card markers with HTML-heavy JSON,
   * shell metacharacters can corrupt the payload. The most common
   * corruption is literal \xNN sequences (e.g. \x27 for single quote)
   * because single-quoted bash strings do not interpret backslash escapes.
   *
   * We only apply this repair when the output looks like it contains
   * a card marker, to avoid changing legitimate tool output.
   */
  private def sanitizeCardOutput(output: String): String =
    if looksLikeCardPayload(output) then decodeHexEscapes(output) else output

  private val CardMarkerPattern = """___\w+_JSON___""".r

  private def looksLikeCardPayload(output: String): Boolean =
    CardMarkerPattern.findFirstIn(output).isDefined

  /** Decode \xNN hex escape sequences into their literal characters. */
  private def decodeHexEscapes(input: String): String =
    val hexPattern = """\\x([0-9a-fA-F]{2})""".r
    hexPattern.replaceAllIn(
      input,
      m =>
        val code = Integer.parseInt(m.group(1), 16)
        code.toChar.toString
    )

  // ── Output sanitization for LLM consumption ──────────────────────────

  /**
   * Clean raw command output before passing to the LLM.
   * Collapses excessive blank lines and trims trailing whitespace.
   * Collapses excessive blank lines and trims trailing whitespace.
   */
  private def cleanOutput(raw: String): String =
    val collapsed = collapseBlankLines(raw)
    trimTrailingWhitespace(collapsed)

  /** Collapse 3+ consecutive blank lines into 2 blank lines. */
  private def collapseBlankLines(s: String): String =
    s.replaceAll("(?m)^[ \t]*\n[ \t]*\n[ \t]*\n+", "\n\n")

  /** Trim trailing whitespace from every line. */
  private def trimTrailingWhitespace(s: String): String =
    s.split("\n").map(_.replaceAll("[ \t]+$", "")).mkString("\n")

  /** Build an on_complete callback that notifies agent + frontend + logs. */
  private def makeNotifyCallback(
    command: String,
    desc: Option[String],
    ctx: ToolContext,
    jobId: String
  ): Option[Either[Throwable, ProcessResult] => IO[Unit]] =
    ctx.agentActorRef.map { ref => (result: Either[Throwable, ProcessResult]) =>
      val firstLine = command.split('\n').headOption.getOrElse(command).take(80)
      val description = desc.getOrElse(firstLine)
      val (eventType, payload, metadata, exitInfo) = result match
        case Right(pr) =>
          val out = cleanOutput(sanitizeCardOutput(pr.stdout))
          val cleanedErr = cleanOutput(pr.stderr)
          val output = out + (if cleanedErr.nonEmpty then s"\n[stderr]:\n$cleanedErr" else "")
          val exitTxt = if pr.exitCode != 0 then s" (exit ${pr.exitCode})" else ""
          (
            "completed",
            s"[Background task completed] \"$description\"$exitTxt:\n$output",
            JsonObject(
              "description" -> description.asJson,
              "exitCode" -> pr.exitCode.asJson,
              "output" -> output.asJson
            ),
            exitTxt
          )
        case Left(e) =>
          val errInfo = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
          (
            "failed",
            s"[Background task failed] \"$description\":\n$errInfo",
            JsonObject("description" -> description.asJson),
            s" ($errInfo)"
          )

      // Notify agent via ExternalEvent
      val notifyAgent = IO(
        ref ! AgentCommand.ExternalEvent(
          source = "background-task",
          eventType = eventType,
          payload = payload,
          metadata = metadata
        )
      )

      // Notify frontend via WS so the indicator dismisses
      val notifyFrontend = ctx.wsSend.fold(IO.unit) { send =>
        send(
          io.circe.Json.obj(
            "type" -> "backgroundTaskUpdate".asJson,
            "sessionId" -> ctx.sessionId.asJson,
            "taskId" -> jobId.asJson,
            "description" -> description.asJson,
            "status" -> eventType.asJson
          )
        ).handleErrorWith(_ => IO.unit)
      }

      notifyFrontend *> notifyAgent *>
        logger.info(
          s"Background job $jobId \"$description\" $eventType$exitInfo",
          "sessionId" -> ctx.sessionId.getOrElse("")
        )
    }

  /** Emit a WS event so the frontend shows the background task indicator. */
  private def emitBgTaskStarted(ctx: ToolContext, jobId: String, description: String): IO[Unit] =
    ctx.wsSend.fold(
      logger.warn(s"Cannot notify frontend for background job $jobId: no wsSend")
    ) { send =>
      val json = io.circe.Json.obj(
        "type" -> "backgroundTaskUpdate".asJson,
        "sessionId" -> ctx.sessionId.asJson,
        "taskId" -> jobId.asJson,
        "description" -> description.asJson,
        "status" -> "running".asJson,
        "startedAt" -> System.currentTimeMillis().asJson
      )
      logger.info(s"Background job $jobId \"$description\" started", "sessionId" -> ctx.sessionId.getOrElse("")) *>
        send(json).handleErrorWith(e => logger.warn(s"WS send failed for job $jobId: ${e.getMessage}"))
    }

  /** Build a heartbeat callback that sends WS updates to frontend. */
  private def makeHeartbeatCallback(
    command: String,
    desc: Option[String],
    ctx: ToolContext
  ): Option[(String, JobHealth) => IO[Unit]] =
    ctx.wsSend.map { send => (jobId: String, health: JobHealth) =>
      val proc = health.processRef.get()
      val alive = proc != null && proc.isAlive
      val now = System.currentTimeMillis()
      val idleMs = now - health.lastActivityMs.get()
      val runningMs = now - health.startedAtMs.get()
      val lines = health.outputLineCount.get()

      // Log if idle for a while (possible stuck indicator)
      val logStuck =
        if alive && idleMs > Defaults.BgStuckThresholdSec * 1000L then
          logger.warn(
            s"Background job $jobId idle for ${idleMs / 1000}s (running ${runningMs / 1000}s, $lines lines)",
            "sessionId" -> ctx.sessionId.getOrElse("")
          )
        else IO.unit

      logStuck *>
        send(
          io.circe.Json.obj(
            "type" -> "backgroundTaskUpdate".asJson,
            "sessionId" -> ctx.sessionId.asJson,
            "taskId" -> jobId.asJson,
            "status" -> "running".asJson,
            "heartbeat" -> io.circe.Json.obj(
              "alive" -> alive.asJson,
              "outputLines" -> lines.asJson,
              "idleMs" -> idleMs.asJson,
              "runningMs" -> runningMs.asJson
            )
          )
        ).handleErrorWith(_ => IO.unit)
    }

  /**
   * Heartbeat fiber for auto-backgrounded commands (not managed by ShellSession).
   *  Uses the same backoff logic as ShellSession.startHeartbeat.
   */
  private def startAutoBgHeartbeat(
    jobId: String,
    health: JobHealth,
    resultRef: cats.effect.Ref[IO, Option[Either[Throwable, ProcessResult]]],
    onHeartbeat: Option[(String, JobHealth) => IO[Unit]]
  ): IO[Unit] =
    onHeartbeat match
      case None => IO.unit
      case Some(cb) =>
        val baseSec = Defaults.BgHeartbeatIntervalSec
        def nextInterval: FiniteDuration =
          val idleSec = (System.currentTimeMillis() - health.lastActivityMs.get()) / 1000
          if idleSec < 120 then baseSec.seconds
          else if idleSec < 600 then 60.seconds
          else 120.seconds
        def loop: IO[Unit] = IO.sleep(nextInterval) *>
          resultRef.get.flatMap {
            case Some(_) => IO.unit
            case None => cb(jobId, health).handleErrorWith(_ => IO.unit) *> loop
          }
        loop.start.void

end BashTool
