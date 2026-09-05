package nebflow.core.tools

import cats.effect.{Fiber, IO}
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

  val DEFAULT_TIMEOUT = 30_000L // 30s — fallback for synchronous remote-exec only
  val MAX_TIMEOUT = Defaults.BashMaxTimeoutMs // 60 minutes

  val name = "Bash"

  val description = """Executes a given bash command and returns its output.

Usage:
- The working directory persists between commands, but shell state does not persist across Nebflow restarts.
- Try to maintain your current working directory throughout the session by using absolute paths and avoiding usage of cd.
- You may specify an optional timeout in milliseconds (max 3600000) to set a hard deadline. If not specified, the command runs in the foreground and is automatically moved to the background after 5 minutes (300s) — the turn is released and you are notified when it finishes. Long-running commands: use run_in_background: true so you can continue other work while it runs.
- Dangerous commands (rm -rf, force push, etc.) are blocked for safety.
- For git commands: Prefer to create a new commit rather than amending an existing commit.
- Only create commits when requested by the user.

Background execution (run_in_background):
- Use for long-running commands (builds, tests, servers, deploys, remote SSH operations, etc.).
- **Use `run_in_background: true`, never `&` or `nohup`.** Shell backgrounding (`&`) bypasses Nebflow's task tracking — you won't be notified when it finishes, and the frontend won't show the background indicator.
- You will be automatically notified when the job finishes. DO NOT poll or use sleep loops.
- After starting a background job, continue with other work or finish your turn.
- Foreground commands are automatically moved to the background after 300s (5min) — the process keeps running, the turn is released, and you are notified on completion. Use run_in_background: true for commands you know will take long.

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
          .obj(
            "type" -> "number".asJson,
            "description" -> "Optional timeout in milliseconds (max 3600000). If exceeded, the command is killed.".asJson
          ),
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
    """fork\s+bomb""".r,
    // Obfuscation: encoded payload piped to shell interpreter
    """(?i)(base64|base32|xxd|openssl\s+enc).*\|\s*(sh|bash|/bin/sh|/bin/bash|zsh)\b""".r,
    // Obfuscation: interpreter relay with system/exec/eval calls
    """(?i)(python[23]?|perl|ruby|node)\s+-[ec]\b.*((os\.)?system|exec|eval|subprocess|popen)""".r,
    // Obfuscation: variable assignment then immediate $ reference to execute
    """(?i)\b\w+\s*=\s*['"]?[rm]m?['"]?\s*;?\s*\$""".r,
    // Obfuscation: env/env -i used to inject commands bypassing direct detection
    """(?i)\benv\s+(-i\s+)?\w+=.*\$\w+""".r
  )

  // Interactive command patterns: commands that require terminal interaction.
  // These cannot work in this environment because stdin is /dev/null and there is no tty.
  private val InteractivePatterns = List(
    // Pagers & editors
    ("""^\s*(less|more)\b""".r, "Interactive pager. Use Read tool, `cat`, `head`, or `tail` instead."),
    ("""^\s*(vim?|nano|emacs|pico)\b""".r, "Interactive text editor. Use Edit or Write tool instead."),
    ("""^\s*crontab\s+-e\b""".r, "`crontab -e` opens an editor. Use `crontab <file>` instead."),
    // System monitors
    ("""^\s*top\b(?!.*-b)""".r, "`top` is interactive. Use `top -b -n 1` for batch output."),
    ("""^\s*(htop|btop|atop)\b""".r, "Interactive system monitor. Use `ps aux` or `top -b -n 1` instead."),
    // Terminal multiplexers & debuggers
    ("""^\s*(tmux|screen)\b""".r, "Terminal multiplexer. Run this command manually in your terminal."),
    ("""^\s*(gdb|lldb)\b""".r, "Interactive debugger. Run this command manually in your terminal."),
    // Password / authentication
    ("""^\s*passwd\b""".r, "`passwd` requires interactive terminal. Run manually in your terminal."),
    ("""^\s*(su|sudo)\b(\s|$)""".r, "`su`/`sudo` requires interactive terminal. Run manually in your terminal."),
    (
      """^\s*ssh\b(?!.*-o\s+BatchMode)""".r,
      "`ssh` without `-o BatchMode=yes` (or without a remote command) is interactive. Use `ssh -o BatchMode=yes host 'command'` instead."
    ),
    ("""^\s*(telnet|ftp|sftp)\b""".r, "Interactive network command. Run this command manually in your terminal."),
    ("""^\s*expect\b""".r, "`expect` is interactive scripting. Not supported in this environment."),
    ("""^\s*script\b""".r, "`script` records terminal sessions. Not supported in this environment."),
    // Interactive interpreters / REPLs
    ("""^\s*(python|python3|ipython)\s*$""".r, "Interactive interpreter. Use `python -c '...'` to execute code."),
    ("""^\s*(node)\s*$""".r, "Interactive interpreter. Use `node -e '...'` to execute code."),
    ("""^\s*(irb|pry)\b""".r, "Interactive Ruby interpreter. Use `ruby -e '...'` instead."),
    // Database clients (interactive mode)
    (
      """^\s*mysql\b(?!.*-e\b)(?!.*--execute\b)""".r,
      "MySQL client without `-e` is interactive. Use `mysql -e 'query'` or provide SQL inline."
    ),
    ("""^\s*psql\b(?!.*-c\b)(?!.*--command\b)""".r, "psql without `-c` is interactive. Use `psql -c 'query'` instead."),
    (
      """^\s*sqlite3\b(?!.*\.dump|\..*\.mode)""".r,
      "SQLite interactive shell. Use `sqlite3 db 'query'` or provide commands on stdin."
    ),
    (
      """^\s*redis-cli\b(?!.*--raw\b)""".r,
      "Redis CLI is interactive without a command. Use `redis-cli <command>` instead."
    ),
    // Git interactive commands
    ("""git\s+rebase\s+-i\b""".r, "Interactive rebase. Use non-interactive git commands."),
    ("""git\s+add\s+-i\b""".r, "Interactive staging. Use `git add <file>` instead."),
    // Login/publish commands
    ("""^\s*(npm|pnpm|yarn)\s+login\b""".r, "Login command is interactive. Use a `.npmrc` token or env var instead."),
    ("""^\s*cargo\s+login\b""".r, "Login command is interactive. Use `cargo login --token TOKEN` or env var instead."),
    (
      """^\s*gh\s+auth\s+login\b""".r,
      "GitHub CLI login is interactive. Use `gh auth login --with-token < token.txt` instead."
    )
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
    else if result.startsWith("[Command executed successfully with no output]") then "No output"
    else
      val lines = result.split('\n').filter(_.trim.nonEmpty)
      if lines.isEmpty then "No output"
      else if lines.length == 1 then lines.head
      else s"${lines.length} lines of output"

  // ── Git branch change detection ──────────────────────────────────────
  // Commands that may change the current branch. After such commands,
  // we reset the agent's tracked branch so the next refreshTurn re-detects
  // silently (first detection = no notification) instead of falsely
  // alarming about an "external" branch change.
  private val GitBranchChangeRe = """\bgit\s+(checkout|switch|rebase|merge|cherry-pick|worktree\s+add)\b""".r

  private def isGitBranchChange(command: String): Boolean =
    GitBranchChangeRe.findFirstIn(command).isDefined

  // ── Pipe truncation handling ──────────────────────────────────────────
  // `cmd | tail -N` causes tail to buffer ALL output until EOF, so the stuck
  // detector sees zero output lines even though the real command is printing.
  // We strip the trailing `| tail -N` and apply truncation in Java instead.

  private val TrailingTailRe = """\|\s*tail\s+(?:-n\s+)?-?(\d+)\s*$""".r

  /** Extract trailing `| tail -N`, returning (command without pipe, N). */
  private def extractTailTruncation(command: String): (String, Option[Int]) =
    TrailingTailRe.findFirstMatchIn(command) match
      case Some(m) =>
        val stripped = command.substring(0, m.start).trim
        if stripped.nonEmpty then (stripped, Some(m.group(1).toInt))
        else (command, None)
      case None => (command, None)

  /** Take the last N lines of output. */
  private def applyTailTruncation(stdout: String, n: Int): String =
    val lines = stdout.split("\n")
    if lines.length <= n then stdout
    else lines.takeRight(n).mkString("\n")

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    // 阶段 2a 沙箱（§A.4-4）：probe 失败时 fail-closed——绝不静默放行。
    // sandbox.bash.failIfUnavailable=false 显式降级：WARN + 结果 [unsandboxed] 前缀。
    if ctx.sandbox.enabled && !nebflow.core.sandbox.SandboxRuntime.current.available then
      if ctx.sandbox.bashFailIfUnavailable then
        IO.pure(Left(ToolError(nebflow.core.sandbox.SandboxRuntime.unavailableMessage(ctx.sandbox.root))))
      else
        BashTool.logger
          .warn(
            s"sandbox-exec unavailable; running Bash UNSANDBOXED (explicit sandbox.bash.failIfUnavailable=false)",
            "sessionId" -> ctx.sessionId.getOrElse("")
          )
          *> doCall(input, ctx).map(markUnsandboxed)
    else doCall(input, ctx)

  private def doCall(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val explicitTimeoutMs: Option[Long] = input("timeout")
      .flatMap(_.asNumber)
      .flatMap(_.toLong)
      .map(t => t.max(1L).min(MAX_TIMEOUT))
    val commandOpt = input("command").flatMap(_.asString)
    val command = commandOpt.getOrElse("")
    val (actualCommand, tailN) = extractTailTruncation(command)
    val background = input("run_in_background").flatMap(_.asBoolean).getOrElse(false)
    val desc = input("description").flatMap(_.asString)
    val bgJobId = input("background_job_id").flatMap(_.asString)
    val cancelBg = input("cancel_background_job").flatMap(_.asBoolean).getOrElse(false)

    val sessionId = ctx.sessionId.getOrElse("default")
    // 阶段 2a 沙箱（§A.4-1/3）：会话 shell 初 cwd = 沙箱根（现状初 cwd=JVM
    // user.dir）；Seatbelt 包装随会话持有策略在 buildProcessBuilder 内完成。
    // 读面按 H-10① 接受全盘（写 OS 强制 + JVM 读围栏双层），hardening 清单另列。
    val sandboxOpt = Some(ctx.sandbox).filter(_.enabled)

    // If background_job_id is provided, enter query/cancel mode
    bgJobId match
      case Some(jobId) =>
        ShellSession.forSession(sessionId, initialDir = sandboxOpt.map(_.root.toString), sandbox = sandboxOpt).flatMap { shell =>
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
            ShellSession
              .forSession(sessionId, initialDir = sandboxOpt.map(_.root.toString), sandbox = sandboxOpt)
              .flatMap { shell =>
                if background then
                  val onHeartbeat = makeHeartbeatCallback(command, desc, ctx)
                  val firstLine = command.split('\n').headOption.getOrElse(command).take(80)
                  val bgDescription = desc.getOrElse(firstLine)
                  for
                    jobId <- IO.randomUUID.map(_.toString.take(8))
                    onComplete = makeNotifyCallback(command, desc, ctx, jobId, tailN)
                    _ <- shell.executeBackground(
                      actualCommand,
                      desc,
                      onComplete,
                      onHeartbeat,
                      Some(jobId),
                      hardTimeoutMs = ctx.bashConfig.hardTimeoutMs,
                      stuckWindowSec = ctx.bashConfig.stuckWindowSec,
                      healthCheckIntervalSec = ctx.bashConfig.healthCheckIntervalSec
                    )
                    _ <- emitBgTaskStarted(ctx, jobId, bgDescription)
                  yield Right(
                    s"[Background job started] Job ID: $jobId\nThe command is running in the background. You will be automatically notified when it finishes — continue with other work or finish your turn."
                  )
                else if ctx.isRemoteExec then
                  // Remote-exec: run synchronously to completion and return the
                  // real output. The caller (another Nebflow instance via HTTP)
                  // manages the lifecycle — the call blocks until the remote
                  // result arrives (or the remote timeout fires).
                  val remoteTimeout = explicitTimeoutMs.getOrElse(DEFAULT_TIMEOUT).millis
                  shell
                    .execute(actualCommand, remoteTimeout)
                    .attempt
                    .map {
                      case Right(pr) => formatResult(pr, desc, tailN, sandboxOpt.map(_.root.toString))
                      case Left(_: TimeoutException) =>
                        Left(ToolError(s"[Command timed out after ${remoteTimeout.toMillis}ms]"))
                      case Left(e) =>
                        Left(ToolError(s"Error: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}"))
                    }
                else executeForeground(shell, actualCommand, explicitTimeoutMs, desc, ctx, tailN)
              }
              .flatTap { result =>
                // After git branch-changing commands, reset branch tracking so the
                // next refreshTurn re-detects silently (no false notification).
                // Harmless if the command failed — branch didn't change, re-detect = same.
                if isGitBranchChange(actualCommand) && result.isRight then
                  ctx.agentActorRef.fold(IO.unit)(ref => ref ! AgentCommand.UpdateGitBranch(None))
                else IO.unit
              }
          end if
    end match

  /** 显式降级（sandbox.bash.failIfUnavailable=false 且 probe 失败）：结果加
    * [unsandboxed] 前缀（§A.4-4），防模型把无围栏输出当成已验证环境。 */
  private def markUnsandboxed(r: Either[ToolError, String]): Either[ToolError, String] =
    r match
      case Right(s) => Right("[unsandboxed] " + s)
      case left => left

  /** Seatbelt 违规归因（§A.4-5）：stderr 出现方言 "Operation not permitted" 时
    * 标注沙箱拒绝，防模型误诊为环境故障。 */
  private[tools] def attributeSandboxDenial(
    r: Either[ToolError, String],
    pr: ProcessResult,
    sandboxRoot: Option[String]
  ): Either[ToolError, String] =
    (r, sandboxRoot) match
      case (Right(s), Some(root)) if pr.exitCode != 0 && pr.stderr.contains("Operation not permitted") =>
        Right(s + s"\n[sandbox: bash write denied outside sandbox root $root]")
      case _ => r
  end attributeSandboxDenial

  /**
   * Execute a command in the foreground until it completes or fails.
   *
   * #26（2026-08-30 用户裁定「Bash 工具不再自动转后台，依赖卡死检测就行了，
   * 不设超时」）：恢复前台直跑语义——#391 机制 A（5 分钟自动转后台）已推翻
   * 删除。前台命令一直跑到结束（或显式 timeout watchdog 杀树），返回真实
   * 输出；不产生「[Command moved to background]」占位。无显式 timeout 时不设
   * 命令级超时（processTimeout 用 365.days 近似无限）。
   *
   * 卡死兜底保持：
   * - 显式 timeout（若有）→ watchdog 杀进程树
   * - 前台 no-progress ceiling（shell.scala #22）：10min 零输出零 CPU 且非
   *   sleep-like → 停滞杀（命令级）
   * - TaskStuckWatcher（turn 级）：活动桥接只刷新有进展的进程——真卡死命令
   *   不刷新 lastActivityMs，10min 零活动 → restart 杀进程树
   *
   * 活动桥接（#319）保持：有进展（输出/CPU≥阈值/sleep-like）刷新
   * lastActivityMs——TaskStuckWatcher「有进展不判卡死」语义；#391 机制 D
   * 让 CPU 判断对齐 CpuActiveThresholdNanos（10ms/30s 采样），卡死进程的
   * CPU 微消耗不再无脑刷新。
   */
  private def executeForeground(
    shell: ShellSession,
    command: String,
    explicitTimeoutMs: Option[Long],
    desc: Option[String],
    ctx: ToolContext,
    tailN: Option[Int] = None
  ): IO[Either[ToolError, String]] =
    // 无显式 timeout → 不设命令级超时（前台直跑语义，365.days 近似无限）。
    val processTimeout = explicitTimeoutMs.map(_.millis).getOrElse(365.days)
    // §A.4-5 归因：沙箱会话的命令失败且 stderr 含 Seatbelt 方言 → 标注拒绝来源。
    val sandboxRoot = Some(ctx.sandbox).filter(_.enabled).map(_.root.toString)
    val health = new JobHealth()
    // #22 (2026-08-19): a running Bash is INVISIBLE — the completion log only
    // fires when it returns, so a long/hung command reads as "turn went
    // silent" in every forensic timeline (20:35 Backend: 37min of zero
    // artifacts while an un-killable timeout ran). Log the start too (same
    // "nebflow.handlers" logger as the completion line so the pair greps
    // together), and keep a once-a-minute running log from the bridge below.
    val handlersLogger = NebflowLogger.forName("nebflow.handlers")
    val agentName = ctx.agentDef.map(_.name).getOrElse("-")
    val sessionLabel = ctx.sessionName.getOrElse("-")
    val logCtx = handlersLogger.ctxPrefix(agentName, sessionLabel)
    val firstLine = command.split('\n').headOption.getOrElse(command).take(100)
    val timeoutLabel = explicitTimeoutMs.map(ms => s" timeout=${ms}ms").getOrElse("")
    for
      _ <- IO(handlersLogger.infoSync(s"$logCtx Tool Bash START$timeoutLabel: $firstLine"))
      // Activity bridge: while the command runs, mirror process progress into
      // the agent registry so TaskStuckWatcher never kills a busy command.
      bridgeFiber <- startActivityBridge(shell, health, command, ctx)
      result <- shell
        .execute(command, processTimeout, Some(health))
        .attempt
        .map {
          case Right(pr) => formatResult(pr, desc, tailN, sandboxRoot)
          case Left(e: TimeoutException) =>
            Left(ToolError(s"[Command timed out after ${processTimeout.toMillis}ms]"))
          case Left(e) =>
            Left(ToolError(s"Error: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}"))
        }
      _ <- bridgeFiber.cancel // 活动桥接只保命令生命周期；命令结束即停
    yield result
    end for

  end executeForeground

  /**
   * Activity bridge (#319): periodically checks the running process and, when
   * it shows progress, refreshes the agent registry's lastActivityMs. This
   * tells TaskStuckWatcher "not stuck" for commands that are actively working
   * but produce output slowly (or none at all, e.g. sleep). Genuinely stuck
   * processes (no output, no CPU, not sleep-like) are left alone so the
   * watcher can still stop them.
   *
   * #22 (2026-08-19): while alive, also emits a once-a-minute running log
   * (INFO, WARN after 10min) — without it an in-flight command leaves zero
   * log artifacts between START and completion, which is exactly how the
   * 20:35 "silent turn death" hid a 37-minute zombie timeout.
   */
  private[tools] def startActivityBridge(
    shell: ShellSession,
    health: JobHealth,
    command: String,
    ctx: ToolContext,
    checkInterval: FiniteDuration = 30.seconds
  ): IO[Fiber[IO, Throwable, Unit]] =
    val handlersLogger = NebflowLogger.forName("nebflow.handlers")
    val agentName = ctx.agentDef.map(_.name).getOrElse("-")
    val sessionLabel = ctx.sessionName.getOrElse("-")
    val logCtx = handlersLogger.ctxPrefix(agentName, sessionLabel)
    def loop(lastLines: Int, lastCpu: Long, ticks: Int): IO[Unit] =
      IO.sleep(checkInterval) *>
        IO {
          val proc = health.processRef.get()
          val alive = proc != null && proc.isAlive
          val lines = health.outputLineCount.get()
          val cpu = if alive then shell.sampleProcessCpuTime(proc) else 0L
          (alive, lines, cpu)
        }.flatMap { case (alive, lines, cpu) =>
          // Running visibility: log every 60s (every 2nd tick) while alive.
          val tick = ticks + 1
          // #391 机制 D：CPU 判断对齐 CpuActiveThresholdNanos（10ms/30s 采样，
          // 与 shell.scala 前台 no-progress ceiling 同标准）——卡死进程的 CPU
          // 微消耗（Chrome 挂起 <10ms/30s）不再算「有进展」。
          // Strictly-greater (D-1 flake fix): macOS `ps` time quantizes to
          // centiseconds — one quantum (10ms) EQUALS the threshold, so `>=`
          // let a sleep process's startup quantum count as activity and touch
          // lastActivityMs under load.
          val cpuActive = (cpu - lastCpu) > shell.CpuActiveThresholdNanos
          val visible =
            alive && tick >= 2 && tick % 2 == 0 &&
              (lines > lastLines || cpuActive || tick >= 20)
          // Commands with progress always log; silent ones log from 10min on.
          val runningLog =
            if visible then
              val elapsed = (tick * checkInterval.toSeconds).toInt
              val msg =
                s"$logCtx Tool Bash RUNNING ${elapsed}s: output=${lines} lines cpu=${cpu / 1_000_000}ms cmd=${command.take(60)}"
              IO(if elapsed >= 600 then handlersLogger.warnSync(msg) else handlersLogger.infoSync(msg))
            else IO.unit
          val sleepLike = shell.SleepCommandRe.findFirstIn(command).isDefined
          val hasProgress = alive && (lines > lastLines || cpuActive || sleepLike)
          runningLog *> (if hasProgress then touchAgentActivity(ctx) *> loop(lines, cpu, tick) else loop(lastLines, lastCpu, tick))
        }
    loop(health.outputLineCount.get(), 0L, 0).start

  /** Refresh the agent registry's lastActivityMs for this session (if present). */
  private def touchAgentActivity(ctx: ToolContext): IO[Unit] =
    (ctx.sharedResources, ctx.sessionId) match
      case (Some(res), Some(sid)) =>
        val now = System.currentTimeMillis()
        res.agentRegistry.modify { m =>
          m.get(sid) match
            case Some(rec) => (m.updated(sid, rec.copy(lastActivityMs = now)), ())
            case None => (m, ())
        }
      case _ => IO.unit

  private def formatResult(
    result: ProcessResult,
    desc: Option[String],
    tailN: Option[Int] = None,
    sandboxRoot: Option[String] = None
  ): Either[ToolError, String] =
    val prefix = desc.map(d => s"[$d]\n").getOrElse("")
    val dirLine = s"(cwd: ${result.cwd})\n"
    val exitLine = if result.exitCode != 0 then s"(exit ${result.exitCode})\n" else ""
    val rawOut = tailN match
      case Some(n) => applyTailTruncation(result.stdout, n)
      case None => result.stdout
    val cleanedOut = cleanOutput(sanitizeCardOutput(rawOut))
    val cleanedErr = cleanOutput(result.stderr)
    val errLine = if cleanedErr.nonEmpty then s"\n[stderr]:\n$cleanedErr" else ""
    val output = cleanedOut + errLine
    val full = prefix + dirLine + exitLine + output
    val base =
      if full.trim.isEmpty then Right("[Command executed successfully with no output]")
      else Right(full)
    attributeSandboxDenial(base, result, sandboxRoot)

  end formatResult

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
    jobId: String,
    tailN: Option[Int] = None
  ): Option[Either[Throwable, ProcessResult] => IO[Unit]] =
    ctx.agentActorRef.map { ref => (result: Either[Throwable, ProcessResult]) =>
      val firstLine = command.split('\n').headOption.getOrElse(command).take(80)
      val description = desc.getOrElse(firstLine)
      val (eventType, payload, metadata, exitInfo) = result match
        case Right(pr) =>
          val rawOut = tailN match
            case Some(n) => applyTailTruncation(pr.stdout, n)
            case None => pr.stdout
          val out = cleanOutput(sanitizeCardOutput(rawOut))
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

      // Notify agent via ExternalEvent.
      // ref ! returns IO[Unit] already — do NOT wrap in IO() or it
      // becomes IO[IO[Unit]] (double-wrapped, fires at construction time).
      val notifyAgent = (ref ! AgentCommand.ExternalEvent(
        source = "background-task",
        eventType = eventType,
        payload = payload,
        metadata = metadata
      )).handleErrorWith(e => logger.warn(s"Failed to notify agent for background job $jobId: ${e.getMessage}"))

      // Notify frontend via WS so the indicator dismisses
      val notifyFrontend = ctx.wsSend.fold(IO.unit) { send =>
        send(
          io.circe.Json.obj(
            "type" -> "backgroundTaskUpdate".asJson,
            "sessionId" -> ctx.sessionId.asJson,
            // 权威分键（2026-09-05 计数/列表分叉修复）：前端直接按 rootSessionId
            // 分桶，替代已删除的 bgTaskRootFor 启发式逆向分键。缺省回退执行者
            // sessionId（根会话自己的任务 root==sessionId，分桶不变）。
            "rootSessionId" -> ctx.rootSessionId.orElse(ctx.sessionId).asJson,
            "taskId" -> jobId.asJson,
            "description" -> description.asJson,
            "status" -> eventType.asJson
          )
        ).handleErrorWith(e => logger.warn(s"WS send failed for background job $jobId: ${e.getMessage}"))
      }

      // Log first so we know the callback fired, then send both notifications
      // independently — each has its own error recovery so one failure
      // doesn't prevent the other.
      BgTaskRegistry.unregister(jobId) *>
        logger.info(
          s"Background job $jobId callback: $eventType$exitInfo",
          "sessionId" -> ctx.sessionId.getOrElse("")
        ) *>
        notifyFrontend.void *> notifyAgent
    }

  /** Emit a WS event so the frontend shows the background task indicator. */
  private def emitBgTaskStarted(ctx: ToolContext, jobId: String, description: String): IO[Unit] =
    BgTaskRegistry.register(
      jobId,
      ctx.sessionId.getOrElse(""),
      description,
      "local",
      ctx.rootSessionId.orElse(ctx.sessionId).getOrElse("")
    ) *>
      (ctx.wsSend.fold(
        logger.debug(s"Cannot notify frontend for background job $jobId: no wsSend (remote execution)")
      ) { send =>
        val json = io.circe.Json.obj(
          "type" -> "backgroundTaskUpdate".asJson,
          "sessionId" -> ctx.sessionId.asJson,
          "rootSessionId" -> ctx.rootSessionId.orElse(ctx.sessionId).asJson,
          "taskId" -> jobId.asJson,
          "description" -> description.asJson,
          "status" -> "running".asJson,
          "startedAt" -> System.currentTimeMillis().asJson
        )
        logger.info(s"Background job $jobId \"$description\" started", "sessionId" -> ctx.sessionId.getOrElse("")) *>
          send(json).handleErrorWith(e => logger.warn(s"WS send failed for job $jobId: ${e.getMessage}"))
      })

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
      val firstLine = command.split('\n').headOption.getOrElse(command).take(80)
      val description = desc.getOrElse(firstLine)

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
            "rootSessionId" -> ctx.rootSessionId.orElse(ctx.sessionId).asJson,
            "taskId" -> jobId.asJson,
            "description" -> description.asJson,
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

end BashTool
