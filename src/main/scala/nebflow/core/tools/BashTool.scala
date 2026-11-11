package nebflow.core.tools

import cats.effect.{Fiber, IO, Ref}
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.actor.{AgentCommand, AgentState}
import nebflow.shared.{Defaults, NebflowLogger}

import scala.concurrent.TimeoutException
import scala.concurrent.duration.*

object BashTool extends Tool:
  private val logger = NebflowLogger(getClass)

  /** Bash output can be very large — persist early. */
  override val maxResultSizeChars: Int = 30_000

  val DEFAULT_TIMEOUT = 30_000L // 30s — fallback for synchronous remote-exec only

  /**
   * Auto-background: the former "max timeout / kill
   * line" is now the foreground auto-background threshold — a foreground command
   * still running when this value (or an explicit, clamped `timeout`) is reached
   * is adopted into the background registry, never killed by time. `def` (not
   * `val`) so the spec prop compression of Defaults.BashMaxTimeoutMs takes
   * effect at every call.
   *
   * main-agent-nonblocking 批（2026-10-21 作者裁定「主 agent 一点不能阻塞」）
   * 语义收缩：本值退为**入参钳 + remote-exec 面**（见 Defaults.BashMaxTimeoutMs
   * 注释）；前台实际等待预算 = ctx.bashConfig.foregroundWaitBudgetMs
   * （缺省 Defaults.ForegroundWaitBudgetMs = 120s，作者经 nebflow.json
   * `bashForegroundWaitBudgetMs` 可调）。`def` 保留（spec 压缩面不变）。
   */
  def MAX_TIMEOUT: Long = Defaults.BashMaxTimeoutMs

  val name = "Bash"

  val description = """Executes a given bash command and returns its output.

Usage:
- The working directory persists between commands, but shell state does not persist across Stand restarts.
- You may specify an optional timeout in milliseconds (max 3600000) as a foreground wait budget hint. The effective foreground wait is capped by the configured foreground wait budget (default 120s; author-adjustable via the config file (`bashForegroundWaitBudgetMs`)). When the budget is reached the command is NOT killed: it is auto-backgrounded (you immediately get a background receipt with its job id; the command keeps running to completion and the result is delivered on completion). Auto-backgrounding is time-based ONLY — a chatty long-running command crosses the budget exactly like a silent one; the main session is never blocked longer than the budget.
- Dangerous commands (rm -rf, force push, etc.) are blocked for safety.
- For git commands: Prefer to create a new commit rather than amending an existing commit.
- Only create commits when requested by the user.

Background execution (run_in_background):
- Use for long-running commands (builds, tests, deploys, remote SSH operations, etc.).
- **Use `run_in_background: true`, never `&` or `nohup`.** Shell backgrounding (`&`) bypasses Stand's task tracking — you won't be notified when it finishes, and the frontend won't show the background indicator.
- You will be automatically notified when the job finishes. DO NOT poll or use sleep loops.
- After starting a background job, continue with other work or finish your turn — the completion notification arrives as a new turn.
- Long-running background tasks in Project nodes: the node waits for all waiting-type background jobs to finish before its result is delivered, so the completion output is not lost.

Service-type background jobs (persistent):
- `persistent: true` (with run_in_background: true) declares a LONG-LIVED SERVER (dev server, watcher, daemon) instead of a finishable task.
- Waiting-type (default): the system waits for it to complete and you are notified. No time limit — background jobs run to completion (the idle/stuck sensor only alerts; use cancel_background_job: true to kill).
- persistent jobs are EXEMPT from the idle/hard-timeout/stall guards — a server that sits idle is healthy. They run until you cancel them (cancel_background_job: true) or the session ends. You own their lifecycle; the frontend indicator stays visible.

Waiting on background jobs (BgWait):
- `BgWait` is the ONLY sanctioned way to wait on a background job inside a turn: it blocks (bounded) until the jobIds you list are terminal or its deadline is reached, and it NEVER kills a job. Its deadline defaults to — and is capped at — the same foreground wait budget as Bash.
- Do NOT repeat `background_job_id` status queries. `background_job_id` now has exactly two uses: cancelling (`cancel_background_job: true`), and checking a job's state after a stuck notification.

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
- Commit inside the repository you changed, and state the purpose in the commit message — never commit across repositories.
- Before switching branches, save the current branch's progress first (commit or stash) — never switch branches with unsaved changes.
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
            "description" -> "Optional foreground wait hint in milliseconds (max 3600000). Effective wait = min(hint, configured foreground budget, default 120s). When reached, the command is auto-backgrounded — it keeps running to completion and the result is delivered on completion; the command is never killed by time.".asJson
          ),
        "description" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Clear, concise description of what this command does".asJson
        ),
        "run_in_background" -> io.circe.Json.obj(
          "type" -> "boolean".asJson,
          "description" -> "Run the command in the background. You will be automatically notified when it finishes — continue with other work or end your turn, the result will come to you. Blocked on it inside this turn? Wait with BgWait (jobIds: [\"<jobId>\"]).".asJson
        ),
        "persistent" -> io.circe.Json.obj(
          "type" -> "boolean".asJson,
          "description" -> "With run_in_background: true — declare this job as a long-lived service (dev server, watcher, daemon) instead of a finishable task. Service jobs are exempt from idle/timeout/stall guards and run until explicitly cancelled; the system does not wait for them.".asJson
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
  private val FixedDangerousPatterns = List(
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

  /**
   * `rm` against the data root — derived at call time from PathUtil.dataRoot
   * （home 硬编码 → 运行时动态化；数据根指向切换批 2026-10 起 legacy 字面同样在列）。
   *
   * 旧实现是固定字面 `rm\s+.*~/.nebflow`：隔离实例（--home /tmp/...）下护错对象
   * ——真正要护的是**本实例**的数据根。此处同时覆盖三种写法，保护面只增不减：
   *   - `~/<homeDirName>`（默认 home 的字面形态，2026-10 起 = `~/.stand`）——
   *     与切换后新根同一目标串（Regex.quote 精确转义）；
   *   - `~/<LegacyHomeDirName>`（`~/.nebflow` 旧根字面）—— 老机器/过渡期数据仍在
   *     旧根，rm 旧根依旧是本批要拦的头号事故形态（保护面不因换根缩水）；
   *   - dataRoot 绝对路径（隔离实例 / 显式 --home / STAND_HOME 实例的真实写根）。
   * `def` on purpose：dataRoot 可在对象初始化后被换根 —— 正则必须现算。
   */
  private def dataRootWipePatterns: List[scala.util.matching.Regex] =
    List(
      "~/" + nebflow.shared.Branding.homeDirName,
      "~/" + nebflow.shared.PathUtil.LegacyHomeDirName,
      nebflow.shared.PathUtil.dataRoot.toString
    ).distinct
      .map(root => ("""rm\s+.*""" + scala.util.matching.Regex.quote(root)).r)

  private def allDangerousPatterns: List[scala.util.matching.Regex] =
    FixedDangerousPatterns ++ dataRootWipePatterns

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
    allDangerousPatterns.exists(_.findFirstIn(command).isDefined)

  /** Returns a danger level: 0 = safe, 1 = warning (git ops / network egress), 2 = dangerous (deletion/kill), 3 = critical (system destruction). */
  def dangerLevel(command: String): Int =
    val criticalPatterns = List(
      """rm\s+-rf\s+/\s*$""".r,
      """rm\s+-rf\s+/\*\s*""".r,
      // R11: 递归删除打到盘根（Windows 形态的 `rm -rf /`）——整盘/整卷级
      // 两个前瞻：① 含 -Recurse ② 目标恰为盘根（`C:\` / `C:/` / `C:`，其后是空白或行尾）；
      // 顺序无关（-Recurse 在路径前/后均可），但不把 `C:\Users\x` 之类子路径误判为盘根。
      """(?i)\bRemove-Item\b(?=[\s\S]*-Recurse)(?=[\s\S]*['"]?[A-Za-z]:[\\/]{0,2}['"]?(?=\s|$))""".r,
      """(?i)\b(rd|rmdir)\s+/s\b[\s\S]*['"]?[A-Za-z]:[\\/]{0,2}['"]?(?=\s|$)""".r,
      """(?i)\b(shutil\.rmtree|rmSync|rmdirSync)\s*\(\s*['"]/['"]""".r,
      """pkill\s+(-f\s+)?.*nebflow""".r,
      """killall\s+.*java""".r,
      """(?i)format\s+/""".r,
      """mkfs\b""".r,
      """dd\s+if=.*of=/dev/""".r
    )
    val dangerousPatterns = List(
      """rm\s+-rf\s+""".r,
      """rm\s+-fr\s+""".r,
      // R11: Windows 递归删除（两条肇事形态此前判 0/safe）
      """(?i)\bRemove-Item\b[\s\S]*-Recurse""".r,
      """(?i)\b(rd|rmdir)\s+/s\b""".r,
      """(?i)\bdel\s+(?:/[a-z]+\s+)*/s\b""".r,
      // R11: 脚本语言递归删除（本机 .mjs/.py 的删除点全走这两条路径）
      """(?i)\bshutil\.rmtree\b""".r,
      """(?i)\brm(?:Sync|dirSync)?\s*\([\s\S]*\brecursive\s*:\s*true""".r,
      """(?i)\bfind\b[\s\S]*\s-delete\b""".r,
      """\bpkill\b""".r,
      """\bkillall\b""".r,
      """(?i)\bkill\s+(-[0-9]+)?\s*\d+""".r,
      """docker\s+(system|volume)\s+prune""".r,
      """(?i)\bkubectl\s+delete\b""".r,
      """git\s+reset\s+--hard""".r,
      """git\s+clean\s+-f""".r
    )
    val warningPatterns = List(
      // T4（网络档）：出网命令统一 **1 档**——只做可见性分级，
      // 不与 delguard 的删除形态分级（2/3）打架。规格点名 6 条形态；PowerShell
      // 的 `powershell -c "Invoke-WebRequest …"` 由「引号前置」分支接住。
      // 前置 = 行首 / 空白 / shell 分隔符（; & | (）/ 引号，后置 = 空白 / 行尾，
      // 故 `curling`、`ssh-keygen` 之类子串不误判。**别名（sftp/nc/telnet/iwr/irm）不纳入**。注意：auto-all 下 dangerLevel 不参与闸门决策，可见性由
      // relay 执行审计行（RelayExecAudit）承担。
      """(?i)(?:^|[\s;&|("'`])(?:ssh|scp|curl|wget|ncat|Invoke-WebRequest)(?=\s|$)""".r,
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

  // ── sleep-poll 禁令批 B 段：护栏状态与生产面（判据单点 = SleepPollGuard）──

  /**
   * 会话级 sleep-poll 计数表（键 = sessionId）。进程内存态——护栏是行为矫正
   * 面，重启归零无碍（LoopGuard counters 同哲学）。
   */
  private val sleepPollCounts: Ref[IO, Map[String, SleepPollGuard.State]] = Ref.unsafe(Map.empty)

  /**
   * sleep-poll 护栏包裹（B 段生产面）：判定（含计数落账）→ 分发。
   *   - WarnExecute：命令照常执行，回执首部附警告+指导文案；
   *   - Reject：**不执行**，先升级面板可见帧（taskStuck/action=sleep-poll，
   *     既有 TERMINAL 帧族，main.js 按 sessionId 标注子代理行）再拒绝。
   */
  private def sleepPollGate(sessionId: String, commandText: String, ctx: ToolContext)(
    execute: IO[Either[ToolError, String]]
  ): IO[Either[ToolError, String]] =
    sleepPollCounts
      .modify { m =>
        val prev = m.getOrElse(sessionId, SleepPollGuard.State())
        SleepPollGuard.decide(commandText, prev, System.currentTimeMillis()) match
          case SleepPollGuard.Verdict.Pass => (m, None)
          case SleepPollGuard.Verdict.WarnExecute(next, secs) =>
            (m.updated(sessionId, next), Some((next.count, secs, false)))
          case SleepPollGuard.Verdict.Reject(next, secs) => (m.updated(sessionId, next), Some((next.count, secs, true)))
      }
      .flatMap {
        case None => execute
        case Some((count, secs, isReject)) =>
          if isReject then
            escalateSleepPoll(ctx, sessionId, count, secs).as(
              Left(ToolError(SleepPollGuard.rejectionText(count, secs)))
            )
          else
            execute.map {
              case Right(text) => Right(s"${SleepPollGuard.warningText(secs)}\n\n$text")
              case left => left
            }
      }

  /**
   * B 段升级腿（面板可见）：taskStuck 帧（action/kind=sleep-poll——前端
   * 只对 action='restart' 有特判，其余值只标注行，向后兼容）。
   */
  private def escalateSleepPoll(ctx: ToolContext, sessionId: String, count: Int, sleepSec: Long): IO[Unit] =
    ctx.wsSend.fold(IO.unit) { send =>
      send(
        io.circe.Json.obj(
          "type" -> "taskStuck".asJson,
          "sessionId" -> sessionId.asJson,
          "kind" -> "sleep-poll".asJson,
          "action" -> "sleep-poll".asJson,
          "idleSecs" -> 0L.asJson,
          "reason" -> s"sleep-poll guard escalation: foreground sleep-poll command #$count above the ${Defaults.SleepPollThresholdSec}s threshold (this one sleeps ${sleepSec}s) — call rejected".asJson
        )
      ).handleErrorWith(e => logger.warn(s"sleep-poll escalation frame failed: ${e.getMessage}"))
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

  // ── Merger repository write exclusion ─────────────────────────────────
  //
  // An executing merger's shell writes to the merge target repository must be
  // mutually exclusive across processes (different data roots over the same git
  // common-dir). Resolution order: the durable ledger merger row (its
  // delegateId equals the session id) carries the repository key; a registry
  // mode=merger record without a ledger row falls back to the record's own
  // workspace. Sessions that are not mergers (dev delegates, root, read-only
  // legacy) resolve None and are untouched — zero added cost beyond one small
  // registry file check. The device leg (isRemoteExec) is exempt: the receiving
  // session acts as a device channel for a peer-initiated command and is never
  // a merger itself.
  private def mergerRepositoryOf(rawSessionId: String): Option[String] =
    if rawSessionId.trim.isEmpty then None
    else
      val record = nebflow.core.delegate.DelegateRegistry.find(rawSessionId)
      if !record.exists(_.mode.contains(nebflow.core.delegate.DualModeConfig.ModeMerger)) then None
      else
        scala.util
          .Try {
            nebflow.core.delegate.WorkspaceLedger.default.snapshot.mergers
              .find(_.delegateId == rawSessionId)
              .map(_.repository)
              .orElse(
                record
                  .flatMap(r => r.projectWorkspace.orElse(Option(r.cwd)))
                  .flatMap(ws =>
                    scala.util.Try(nebflow.core.delegate.WorkspaceLedger.repositoryKey(os.Path(ws))).toOption
                  )
              )
          }
          .toOption
          .flatten

      end if

  /**
   * Acquire the session-reentrant repository write hold for a merger session.
   * `Right(None)` = not a merger (pass through); `Left` = the repository is
   * being written by another merger writer (any process) — the command is
   * refused without starting a process.
   */
  private[tools] def acquireMergerHold(
    ctx: ToolContext
  ): IO[Either[ToolError, Option[nebflow.core.delegate.RepositoryWriteLock.Hold]]] =
    val rawSessionId = ctx.sessionId.getOrElse("default")
    IO.blocking(mergerRepositoryOf(rawSessionId)).flatMap {
      case None => IO.pure(Right(None))
      case Some(repo) =>
        IO.blocking(nebflow.core.delegate.RepositoryWriteLock.acquireFor(rawSessionId, repo)).map {
          case Right(hold) => Right(Some(hold))
          case Left(inUse) =>
            Left(
              ToolError(
                s"[Repository write lock in use] $inUse — another merger writer is active on this repository; " +
                  "wait for it to finish before running this command (cross-process merger exclusion)"
              )
            )
        }
    }

  end acquireMergerHold

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    // provider 语义（design §4.2）：
    //
    // ① 宿主路径不再 fail-closed：围栏拆掉后「无包裹」是**缺省态**（provider=host），
    //    旧判据 `ctx.sandbox.enabled && !SandboxRuntime.current.available` 失去对象；
    //    连带解除 Linux 上的**隐性不可用**（非 macOS 时 `SandboxBackend.probe` 恒
    //    false × 缺省 bashFailIfUnavailable=true ⇒ Linux Bash 整体被拒；现已无此路）。
    // ② 非宿主 provider（local-process / container / auto）**不可用或未实现 = 明示
    //    失败**，绝不静默回落宿主执行（design §6.2 U7）——原因在启动期由
    //    `SandboxRuntime.init` 定妥，此处只读结果（单一判据来源，避免两处漂移）。
    // ③ `[unsandboxed]` 前缀与 §A.4-4 显式降级档一并退役：新语义下没有「降级到无
    //    包裹」这一档（要么宿主直跑 = 正常态，要么显式失败），`markUnsandboxed` 失
    //    触发源已删除。
    nebflow.core.sandbox.SandboxRuntime.failureCause match
      case Some(cause) =>
        IO.pure(
          Left(ToolError(nebflow.core.sandbox.SandboxRuntime.failureMessage(cause, ctx.sandbox.root)))
        )
      case None => doCall(input, ctx)

  private def doCall(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val explicitTimeoutMs: Option[Long] = input("timeout")
      .flatMap(_.asNumber)
      .flatMap(_.toLong)
      .map(t => t.max(1L).min(MAX_TIMEOUT))
    val commandOpt = input("command").flatMap(_.asString)
    val command = commandOpt.getOrElse("")
    // askuser-secret 批（2026-10-08，规格 §2「读取 = 原子 rename 出目录再读」）：
    // 一次性验证码的唯一消费闸。命令引用 `secrets/.otp/` 下的文件 ⇒ 先原子消费
    // （rename-out，防两次 Bash 同时读到）并把值折进**本条命令**的环境变量
    // （命令文本改写为变量引用，零值落命令/日志）；引用已消费/过期 ⇒ 显式拒绝，
    // 不执行（方法尾部统一判读）。不含 otp 引用的命令在此零开销透传（字符串
    // 标记检测，内容零改动）。
    val otpFace: Either[String, (String, Map[String, String])] =
      nebflow.core.secrets.AskUserSecretSink.consumeOtpReferences(
        command,
        nebflow.shared.PathUtil.dataRoot
      )
    val commandForExec = otpFace match
      case Left(_) => command // 拒绝面在方法尾部短路（body 不执行）
      case Right((rew, _env)) => rew
    val otpEnv = otpFace.fold(_ => Map.empty[String, String], _._2)
    val (actualCommand, tailN) = extractTailTruncation(commandForExec)
    val background = input("run_in_background").flatMap(_.asBoolean).getOrElse(false)
    val desc = input("description").flatMap(_.asString)
    val bgJobId = input("background_job_id").flatMap(_.asString)
    val cancelBg = input("cancel_background_job").flatMap(_.asBoolean).getOrElse(false)
    // 节点完成闸：persistent=true = 服务型后台
    // （长驻 server）——豁免 B1/B2 看护杀、不纳入节点等待集。默认 false = 等待型。
    val persistent = input("persistent").flatMap(_.asBoolean).getOrElse(false)

    val sessionId = ctx.sessionId.getOrElse("default")
    // 阶段 2a 沙箱（§A.4-1/3）：会话 shell 初 cwd = 沙箱根（现状初 cwd=JVM
    // user.dir）；Seatbelt 包装随会话持有策略在 buildProcessBuilder 内完成。
    // 读面按 H-10① 接受全盘（写 OS 强制 + JVM 读围栏双层），hardening 清单另列。
    val sandboxOpt = Some(ctx.sandbox).filter(_.enabled)
    // 会话初始 cwd（「会话启动即 `cd` 座椅」）：shell 初
    // cwd 优先取**显式座椅信号**（worktree 节点 ⇒ 座椅路径），缺省回落沙箱根（旧行为
    // 逐字节不变：非 worktree 节点两值同源）。🔴 fail-closed：座椅目录缺失 ⇒
    // ShellSession.buildProcessBuilder 的 `resolveCwdOrFail` 抛 InvalidCwdError ⇒ 本调用
    // 显式失败（经下方 `Error: …` 通道回到模型），**禁**静默回落工作区根。围栏面
    // （sandbox=工作区根）不动。
    val sessionInitialDir = ctx.sessionCwd.orElse(sandboxOpt.map(_.root.toString))
    // Secrets-consumption batch (2026-10-01): the C-case allowlist, resolved
    // for THIS agent. Empty allowlist (the default) => `Map.empty` => the
    // session's command environments are byte-identical to the pre-batch
    // behaviour. 🔴 `materialize` is the only place a credential value is read
    // for injection, and it is not reached at all when the list is empty.
    val secretEnv: Map[String, String] =
      val injection = nebflow.core.secrets.SecretInjection.load(nebflow.shared.PathUtil.dataRoot)
      val entries = nebflow.core.secrets.SecretInjection.resolve(injection, ctx.agentDef.map(_.name))
      val env = nebflow.core.secrets.SecretInjection.materialize(nebflow.shared.PathUtil.dataRoot / "secrets", entries)
      // Audible, name-only: which allowlisted secrets did NOT resolve. A
      // silently absent variable is the failure mode that costs an hour of
      // debugging, and a NAME never leaks anything.
      val missing = nebflow.core.secrets.SecretInjection.unresolved(entries, env)
      if missing.nonEmpty then
        NebflowLogger
          .forName("nebflow.secrets")
          .warn(s"allowlisted secrets did not resolve (absent / unreadable / oversized): ${missing.mkString(", ")}")
      env

    // If background_job_id is provided, enter query/cancel mode
    // (askuser-secret 批：执行面前先经 otpFace 拒绝闸——引用已消费/过期的一次性
    // 验证码的命令在此显式失败，不执行、不部分消费。)
    val runPlan = bgJobId match
      case Some(jobId) =>
        // 🔴 `secretEnv` is passed on the QUERY path too: sessions are keyed by
        // sessionId, so a query that created the session first would otherwise
        // hand the later execute path a session created without injection.
        // askuser-secret 批：`++ otpEnv` = 本条命令消费的一次性验证码（otpEnv 为空
        // ⇒ 与改前逐字节同参）。
        ShellSession
          .forSession(sessionId, initialDir = sessionInitialDir, sandbox = sandboxOpt, secretEnv = secretEnv ++ otpEnv)
          .flatMap { shell =>
            if cancelBg then
              shell.cancelBackgroundJob(jobId).flatMap { cancelled =>
                // #1193 收口腿：显式取消同样是终态——**取走 registry 行** +
                // 输出留存区翻转。原实现只出会话任务图、不取行：行残留 →
                // death-watch 下一拍以「owner lost: not owned by session」
                // 二次收割，账本/通知留下「引擎埋任务」假证（10-31 实证
                // f25b9f1f/8641d0d5：模型显式取消 5s 后 owner-lost 收割到）。
                // first-terminal-wins：真完成竞态已先取走行 ⇒ no-op 幂等。
                val takeRow =
                  if cancelled then
                    BgTaskRegistry.unregisterReturning(jobId).void *>
                      BgTaskOutputStore
                        .finalizeTask(jobId, "cancelled", None, Some("Cancelled by agent (tool call)"))
                        .handleErrorWith(_ => IO.unit)
                  else IO.unit
                takeRow.as {
                  if cancelled then Right(s"[Background job cancelled] Job ID: $jobId")
                  else Right(s"[Background job not found] Job ID: $jobId")
                }
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
            // D12-b（delegate reset 零闸治理，作者裁定 2026-10-23）：reset 系命令
            // 审计事件——auto-all 顶档下危险命令清单是死信（ToolReversibility
            // AutoAll ⇒ true），就地腿 delegate 与他方共享检出区，一次 reset 即
            // 清掉他方未持久化的进行中工作且无痕。此处（权限闸之后、进程起跑之前）
            // 落至少一行审计（谁/何时/命令/受波及的 live delegate 及其当时状态/任务）。
            // best-effort：审计失败只 WARN，绝不影响命令执行（RelayExecAudit 同款
            // 失败语义）；零超时设计——非 reset 命令零开销透传（一组正则判别）。
            val resetAuditIo =
              if ResetAudit.isResetCommand(command) then ResetAudit.record(command, ctx, ctx.sharedResources)
              else IO.unit
            resetAuditIo *> ShellSession
              .forSession(
                sessionId,
                initialDir = sessionInitialDir,
                sandbox = sandboxOpt,
                secretEnv = secretEnv ++ otpEnv
              ) // otpEnv：一次性验证码（空 Map = 改前同参）
              .flatMap { shell =>
                if background then
                  val onHeartbeat = makeHeartbeatCallback(command, desc, ctx)
                  val firstLine = command.split('\n').headOption.getOrElse(command).take(80)
                  val bgDescription = desc.getOrElse(firstLine)
                  // 等待型任务的完成回调需带闸记账信息（超时杀 → 节点 failed 注明）。
                  val gateOwned = !persistent && isNodeSession(ctx)
                  // #753 会话级创建速率闸：熔断激活/触发 ⇒ 本拍拒绝（可读错误回执，
                  // 不建任务）。persistent=true（服务型 server）不设闸——dev server
                  // 等长驻任务是合法低频创建，闸针对的是 waiter/poller 刷屏族。
                  def startBg: IO[Either[ToolError, String]] =
                    // Merger write exclusion: the hold covers the WHOLE background job
                    // lifetime — the release rides the job's execution fiber at terminal
                    // state (completion, idle/stall kill, explicit cancel, session
                    // reclaim all terminalize that fiber). Acquisition failure = another
                    // merger writer is active = the command is refused before any
                    // process starts (correctness over convenience).
                    acquireMergerHold(ctx).flatMap {
                      case Left(err) => IO.pure(Left(err))
                      case Right(mergerHold) =>
                        // Policy "backgrounded commands have no limit": waiting-type background jobs have NO time cap —
                        // the old 30min tier (ctx.bashConfig.hardTimeoutMs) and the node
                        // 4h tier (BgGateNodeHardTimeoutMs) are both retired from the
                        // production face. Both the explicit run_in_background path (here)
                        // and the auto-backgrounded path pass the NoHardTimeout sentinel,
                        // making the B2 hard-timeout stall kill unreachable — full-class
                        // exemption, one unified "background = no time limit" semantics.
                        // B1 stays alert-only; persistent exemption, explicit cancel and
                        // killSessionProcesses are unchanged. The finite-value parameters
                        // remain on ShellSession for spec injection (mechanism testability).
                        val hardTimeoutMs = Defaults.BashBackgroundNoHardTimeoutMs
                        for
                          jobId <- IO.randomUUID.map(_.toString.take(8))
                          onComplete = makeNotifyCallback(command, desc, ctx, jobId, tailN, gateOwned)
                          // 先建缓冲拿句柄，sink 随
                          // executeBackground 挂上 JobHealth——运行中逐行入尾窗缓冲，
                          // 终态后由 onComplete finalizeTask 进留存区（详情卡 REST 读）。
                          // 输出查看补全批（作者令 2026-10-25 需求④「本地任务输出落盘 +
                          // 记录路径」）：origin 登记 + lineSink 复合口——persistDir 由
                          // Main.run boot 面开启后，行同步落 bg-task-logs/ + 终态 meta，
                          // 留存淘汰 / 引擎重启后的旧任务详情卡照样可回看（read 落盘兜底）；
                          // 未开启（spec）= 纯内存，行为与批前一致。
                          outBuffer <- BgTaskOutputStore.open(
                            jobId,
                            Some(
                              BgTaskOutputStore.Origin(
                                "local",
                                description = bgDescription,
                                startedAtMs = System.currentTimeMillis()
                              )
                            )
                          )
                          _ <- shell.executeBackground(
                            actualCommand,
                            desc,
                            onComplete,
                            onHeartbeat,
                            Some(jobId),
                            hardTimeoutMs = hardTimeoutMs,
                            stuckWindowSec = ctx.bashConfig.stuckWindowSec,
                            healthCheckIntervalSec = ctx.bashConfig.healthCheckIntervalSec,
                            persistent = persistent,
                            outputSink = Some(BgTaskOutputStore.lineSink(jobId, outBuffer)),
                            // B1 只提醒腿的审计出口：经 ctx 身份解析
                            // 项目工作区写 `bg-slow` 事件。无项目上下文 ⇒ None ⇒ 只留 logger.warn。
                            onSlowDetected = Some((summary: String) => auditBgSlow(ctx, jobId, summary)),
                            releaseAtTerminal = mergerHold.map(hold =>
                              IO.blocking(nebflow.core.delegate.RepositoryWriteLock.releaseFor(hold)).attempt.void
                            )
                          )
                          _ <- emitBgTaskStarted(ctx, jobId, bgDescription, persistent)
                        yield Right(
                          s"[Background job started] Job ID: $jobId\nThe command is running in the background. You will be automatically notified when it finishes — continue with other work or finish your turn. " +
                            s"""Wait with BgWait (jobIds: ["$jobId"]) if you are genuinely blocked, or end your turn — you will be notified on completion. Cancel with background_job_id + cancel_background_job: true."""
                        )
                        end for
                    }
                  end startBg
                  val run =
                    if persistent then startBg
                    else
                      // 告警帧只在熔断**触发沿**发（breakerEngaged）——熔断激活期
                      // 内的每次拒绝只回工具面错误，不再重复 toast（防告警刷屏）。
                      BgTaskRegistry.gateCreation(ctx.sessionId.getOrElse("")).flatMap { d =>
                        if d.breakerEngaged then emitRateAlert(ctx) *> IO.pure(Left(ToolError(d.message.getOrElse(""))))
                        else if !d.allowed then IO.pure(Left(ToolError(d.message.getOrElse(""))))
                        else startBg
                      }
                  run
                else if ctx.isRemoteExec then
                  // ── 设备腿（本实例替对端实例跑一条 Bash 请求）────────────────
                  // T7（作者令 2026-11-04，自查件 D9 M6/§4）：**与本地前台腿同构**。
                  //
                  // 改前（缺陷态）：`explicitTimeoutMs.getOrElse(DEFAULT_TIMEOUT = 30s)`
                  // 被当作**进程超时**——30s 到点即杀对端命令并回
                  // `[Command timed out after 30000ms]`（`isError=true`，喂 S1 同败
                  // streak，8 次就 L1 杀会话）。而本地腿早在 main-agent-nonblocking 批
                  // 就把「到点」从**杀**改成了**收编为后台**。
                  //
                  // 改后（本块）：唯一上限 = min(显式 timeout, 前台等待预算
                  // `ctx.bashConfig.foregroundWaitBudgetMs`）——与 [[executeForeground]]
                  // **同源单点**，禁第二个上限（尤其禁 30s 兜底）。竞速（racePair，落败
                  // 侧不取消）：
                  //   - 预算内完成 ⇒ 既有 [[formatOutcome]]（成功/停滞/真错误三态照旧）；
                  //   - 到点未完成 ⇒ **收编为后台任务**：进程不杀，按 host 下发的
                  //     `bgJobId`（= 对端发起侧的任务 id，两侧同一 id）登记本侧
                  //     `BgTaskRegistry`（`kind="remote"`，命令来自对端）并把仍在跑的执行
                  //     fiber 挂进会话任务图，回**与本地 auto-background 逐字同形**的回执
                  //     （同一构造点 [[RemoteExecutor.autoBackgroundReceipt]]），
                  //     `isError=false`，可被 BgWait 等、可被 BgTaskCancel 按同一 id 取消。
                  // 本侧无 PeerInfo，故「远端后台机制」在本侧的同构体 =
                  // `shell.adoptAsBackgroundJob` + `BgTaskRegistry.register`（= host 侧
                  // `executeRemoteBackground`/`startRemoteBgFiber` 的等价面）。
                  // 真失败（cwd 不存在、shell 起不来、真错误）**照旧** Left(ToolError)
                  // [[formatOutcome]]，绝不被吞成后台回执。
                  // `run_in_background: true` 的显式后台语义不经过本块（上方 background 腿）。
                  val deviceBudgetMs = math.max(1L, ctx.bashConfig.foregroundWaitBudgetMs)
                  val waitBudgetMs = explicitTimeoutMs.fold(deviceBudgetMs)(_.min(deviceBudgetMs))
                  // P2 单任务取消远端腿：host 随下发参数带 bgJobId ⇒ 对端把本进程
                  // 登记进 RemoteExecCancelRegistry（execKey），bg-cancel 按它精确杀树；
                  // 同一 id 也是收编行的 jobId（登记行与回执同一 id，不铸第二个）。
                  val execKey = input("bgJobId").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
                  val deviceHealth = new JobHealth()
                  for
                    // 进程级时间杀退役（同本地腿）：365.days 送下去，判据只有预算竞速。
                    execFiber <- shell
                      .execute(actualCommand, 365.days, Some(deviceHealth), execKey = execKey)
                      .attempt
                      .start
                    raced <- IO.racePair(joinResult(execFiber), IO.sleep(waitBudgetMs.millis))
                    result <- (raced match
                      case Left((winOutcome, budgetFiber)) =>
                        // 预算内完成 —— 既有渲染三态（毫秒不差）。
                        budgetFiber.cancel *> unwrapOutcome(winOutcome).map(res => formatOutcome(res, desc, tailN))
                      case Right(_) =>
                        // 预算到点仍在跑 —— 收编，进程不杀，回执 isError=false。
                        remoteLegAutoBackground(
                          shell,
                          execFiber,
                          deviceHealth,
                          actualCommand,
                          desc,
                          ctx,
                          tailN,
                          waitBudgetMs,
                          execKey
                        )
                    ).handleErrorWith(e =>
                      // 契约 2：真失败（进程起不来、cwd 不存在、执行期异常）如实走
                      // Left(ToolError)，绝不被吞成后台回执（与改前同域）。
                      IO.pure(Left(ToolError(s"Error: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}")))
                    )
                  yield result
                  end for
                else
                  // sleep-poll 禁令批 B 段：护栏只包前台腿（显式 run_in_background
                  // 豁免——后台是正道姿势；auto-background 收编路径也在本腿内，
                  // 收编前的长 sleep 照样计犯）。判据/阶梯/文案单点 =
                  // [[SleepPollGuard]]（纯函数，747cd36a `sleep 570; echo wN`
                  // 心跳形态的机制补位——LoopGuard R-call 判同参指纹抓不到变参
                  // 心跳；本护栏看命令形态）：
                  //   - 首犯：放行执行，回执首部附警告+指导；
                  //   - 第 N 次（Defaults.SleepPollEscalateAfter，默认 3）：
                  //     拒绝执行 + taskStuck 面板可见帧升级。
                  // Merger write exclusion: the repository is resolved once per call
                  // (cheap registry check; mergers only) and handed to the foreground
                  // leg, which acquires the non-blocking write lock right before the
                  // process fiber starts and releases it at process terminal state.
                  IO.blocking(mergerRepositoryOf(ctx.sessionId.getOrElse("default"))).flatMap { mergerRepo =>
                    sleepPollGate(sessionId, actualCommand, ctx) {
                      executeForeground(shell, actualCommand, explicitTimeoutMs, desc, ctx, tailN, mergerRepo)
                    }
                  }
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

    // askuser-secret 批：otpFace 拒绝闸（消费失败 ⇒ 原命令不执行）。
    otpFace match
      case Left(refusal) => IO.pure(Left(ToolError(refusal)))
      case Right(_) => runPlan

  end doCall

  /**
   * Execute a command in the foreground until it completes or fails.
   *
   * main-agent-nonblocking 批（2026-10-21 作者裁定重申「主 agent 一点不能阻塞，
   * 之前的设计是不杀但自动转后台」）：前台等待预算收缩为**短预算**——
   * ctx.bashConfig.foregroundWaitBudgetMs（缺省 Defaults.ForegroundWaitBudgetMs
   * = 120s，作者经 nebflow.json `bashForegroundWaitBudgetMs` 可调）。显式
   * `timeout` 入参收窄到该预算（min）——agent 不能越过作者的零阻塞裁定。预算
   * 判据 = **时间**（exec fiber 与预算 sleep 的 racePair），与输出活性无关：
   * 有输出的长命令同样在到点转后台，不因「还在吐进度」占着主会话。到点 →
   * autoBackground 收编（回执含 job id + 查询方式），进程不杀，完成照旧经
   * makeNotifyCallback 通知。
   *
   * 本次裁定之前的旧事故面（为何收缩）：旧缺省预算 = BashMaxTimeoutMs（60min）
   * ——前台克隆 1GB+ 大仓无输出运行时预算从未到点，600s 无输出停滞阈值
   * （shell.scala foregroundNoProgressWatch）先触发**杀**树，主会话每次硬冻结
   * 10 分钟。收缩后默认 120s < 600s：停滞阈值在缺省面结构性不可达（自然消亡），
   * 仅作者把预算调大于 600s 时作为前台真挂死兜底保留；转后台后的任务豁免
   * （JobHealth.adoptedAsBackground → ceiling 退役）。
   *
   * 卡死兜底保持（收缩后的剩余管辖面）：
   * - 前台阶段 no-progress ceiling（shell.scala）：仅当配置预算 > 600s 时可达。
   * - TaskStuckWatcher（turn 级换轴）：判据 = agent 侧事件停滞 ∪ 单个工具调用
   *   持续超 10min（工具相位）——预算 120s ⇒ 工具相位轴对 Bash 结构性不可达。
   *
   * 活动桥接（#319）保持：有进展（输出/CPU≥阈值/sleep-like）刷新
   * **processActivityMs**（进程侧字段）——该戳与 agent 侧活动分家。
   */
  private def executeForeground(
    shell: ShellSession,
    command: String,
    explicitTimeoutMs: Option[Long],
    desc: Option[String],
    ctx: ToolContext,
    tailN: Option[Int] = None,
    /**
     * Merger write exclusion: when present (this session is a merger), the
     * non-blocking repository write lock is acquired immediately before the
     * process fiber starts and the fiber releases it at terminal state. The
     * guarantee rides the SAME fiber that runs the process, so an
     * auto-background adoption keeps the exclusion until the process actually
     * exits; a plain foreground completion releases at completion. Acquisition
     * failure = another writer is active = the call is refused before any
     * process starts.
     */
    mergerRepo: Option[String] = None
  ): IO[Either[ToolError, String]] =
    // Policy "no hard cap; auto-background at the foreground wait budget
    // (main-agent-nonblocking: default 120s, author-adjustable); backgrounded
    // commands have no limit"): an explicit `timeout` is a budget HINT clamped
    // to the configured budget (min) — the author's zero-blocking ruling wins
    // over any agent-requested long foreground wait. When the budget elapses
    // and the command is still running, it is adopted into the session's
    // background registry (adoptAsBackgroundJob) and the tool returns a
    // background receipt immediately (same face as an explicit
    // run_in_background); the process keeps running to completion and the
    // completion notification rides the existing background callback chain
    // (makeNotifyCallback). The process-level time kill is retired as a class
    // (runProcess always gets 365.days now — it is never shortened). The only
    // retained foreground kill is the no-progress ceiling (the stall watchdog —
    // not a time limit; structurally unreachable while budget < 600s).
    val budgetMs = math.max(1L, ctx.bashConfig.foregroundWaitBudgetMs)
    val waitBudgetMs = explicitTimeoutMs.fold(budgetMs)(_.min(budgetMs))
    val health = new JobHealth()
    // A running Bash is INVISIBLE — the completion log only
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
      // Process-activity bridge: while the command runs, mirror **process-side**
      // liveness into the registry (AgentRecord.processActivityMs) so operators
      // can tell "the child process is still alive" from "the agent is making
      // progress". 换轴后：该桥不再影响卡死判据（旧行为=进程 CPU
      // 微动持续刷新 agent 侧戳 → 2h50m 判据失明，事故根因）。
      bridgeFiber <- startActivityBridge(shell, health, command, ctx)
      // Foreground run with NO command-level time kill, raced against the wait
      // budget. racePair (not race) so the losing fiber keeps running: on the
      // budget side the execution fiber must survive for the adoption.
      // The merger hold is acquired immediately before the fiber starts and its
      // release is registered INSIDE that fiber (`guarantee`), so every
      // acquisition has a guaranteed release exactly at process terminal state.
      result <- mergerRepo match
        case None =>
          raceExec(
            shell,
            shell.execute(command, 365.days, Some(health)).attempt,
            health,
            command,
            desc,
            ctx,
            tailN,
            waitBudgetMs
          )
        case Some(repo) =>
          IO.blocking(nebflow.core.delegate.RepositoryWriteLock.acquireFor(ctx.sessionId.getOrElse("default"), repo))
            .flatMap {
              case Left(inUse) =>
                IO.pure(
                  Left(
                    ToolError(
                      s"[Repository write lock in use] $inUse — another merger writer is active on this repository; " +
                        "wait for it to finish before running this command (cross-process merger exclusion)"
                    )
                  )
                )
              case Right(hold) =>
                val guarded = shell
                  .execute(command, 365.days, Some(health))
                  .attempt
                  .guarantee(
                    IO.blocking(nebflow.core.delegate.RepositoryWriteLock.releaseFor(hold)).attempt.void
                  )
                raceExec(shell, guarded, health, command, desc, ctx, tailN, waitBudgetMs)
            }
      _ <- bridgeFiber.cancel // 活动桥接只保命令生命周期；命令返回（含转后台）即停
    yield result
    end for

  end executeForeground

  /**
   * The foreground race body shared by the plain and the merger-guarded exec
   * fiber: run the (already configured) execution IO against the foreground
   * wait budget; within the budget = plain foreground result, budget reached =
   * adopt into the background registry (process keeps running, so a merger
   * hold configured on the fiber keeps guarding it to terminal state).
   */
  private def raceExec(
    shell: ShellSession,
    execIo: IO[Either[Throwable, ProcessResult]],
    health: JobHealth,
    command: String,
    desc: Option[String],
    ctx: ToolContext,
    tailN: Option[Int],
    waitBudgetMs: Long
  ): IO[Either[ToolError, String]] =
    for
      execFiber <- execIo.start
      raced <- IO.racePair(joinResult(execFiber), IO.sleep(waitBudgetMs.millis))
      result <- raced match
        case Left((winOutcome, budgetFiber)) =>
          // Completed within the budget — plain foreground result.
          budgetFiber.cancel *> unwrapOutcome(winOutcome).map(res => formatOutcome(res, desc, tailN))
        case Right(_) =>
          // Budget reached while still running — adopt into the background
          // registry and return the receipt immediately (process keeps running).
          autoBackground(shell, execFiber, health, command, desc, ctx, tailN, waitBudgetMs)
    yield result

  /**
   * Unwrap a started fiber's Outcome into its value (Cancelled/Error rethrown).
   * join alone returns Outcome[IO, Throwable, A]; the foreground race and the
   * adoption watcher both want the plain A.
   */
  /** Unwrap a fiber's join Outcome into its value (Cancelled/Error rethrown). */
  private def unwrapOutcome[A](oc: cats.effect.kernel.Outcome[IO, Throwable, A]): IO[A] =
    oc match
      case cats.effect.kernel.Outcome.Succeeded(fa) => fa
      case cats.effect.kernel.Outcome.Errored(e) => IO.raiseError[A](e)
      case cats.effect.kernel.Outcome.Canceled() =>
        IO.raiseError[A](new InterruptedException("foreground command fiber cancelled"))

  private def joinResult[A](fiber: Fiber[IO, Throwable, A]): IO[A] =
    fiber.join.flatMap(unwrapOutcome)

  /**
   * Render a foreground outcome: real result / stall-watchdog error / generic
   * error. With the time kill retired, the only
   * TimeoutException reaching here is the foreground no-progress ceiling.
   */
  private def formatOutcome(
    outcome: Either[Throwable, ProcessResult],
    desc: Option[String],
    tailN: Option[Int]
  ): Either[ToolError, String] =
    outcome match
      case Right(pr) => formatResult(pr, desc, tailN)
      case Left(e: TimeoutException) =>
        Left(ToolError(s"[Command stalled] ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}"))
      case Left(e) =>
        Left(ToolError(s"Error: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}"))

  /**
   * Auto-background: adopt a still-running foreground execution as a background
   * job and produce the receipt. Registry / indicator / completion-notify faces
   * are fully aligned with an explicit run_in_background (BgTaskRegistry + WS
   * indicator + BgTaskOutputStore + makeNotifyCallback); the only gap = no live
   * tail lines (foreground-phase output lines were consumed by the read threads
   * before the store existed — the running detail card shows 0 lines; the
   * background_job_id query still returns the full output after completion).
   */
  private def autoBackground(
    shell: ShellSession,
    execFiber: Fiber[IO, Throwable, Either[Throwable, ProcessResult]],
    health: JobHealth,
    command: String,
    desc: Option[String],
    ctx: ToolContext,
    tailN: Option[Int],
    waitedMs: Long,
    // T7：设备腿复用本收编腿时的两个缝——jobId 由 host 下发（两侧同一 id）与
    // 台账 kind（设备腿 = "remote"，本地腿 = "local"）。缺省 = 本地腿逐字节不变。
    jobIdOverride: Option[String] = None,
    kind: String = "local",
    originOverride: Option[(String, String)] = None
  ): IO[Either[ToolError, String]] =
    for
      jobId <- jobIdOverride.fold(IO.randomUUID.map(_.toString.take(8)))(IO.pure)
      onHeartbeat = makeHeartbeatCallback(command, desc, ctx)
      firstLine = command.split('\n').headOption.getOrElse(command).take(80)
      bgDescription = desc.getOrElse(firstLine)
      // Waiting-type (persistent is always false): node sessions carry the gate
      // bookkeeping, same as the explicit background path.
      gateOwned = isNodeSession(ctx)
      onComplete = makeNotifyCallback(command, desc, ctx, jobId, tailN, gateOwned)
      // 输出查看补全批：收编路径同样登记 origin（前台段行已被读线程消费、无
      // live 尾行——落盘/留存保终态 meta（状态/exit）可回看，不再 404）。
      _ <- BgTaskOutputStore.open(
        jobId,
        Some(
          BgTaskOutputStore.Origin(
            "local",
            description = bgDescription,
            startedAtMs = System.currentTimeMillis()
          )
        )
      )
      _ <- shell.adoptAsBackgroundJob(
        jobId,
        execFiber,
        health,
        command,
        description = desc,
        on_complete = onComplete,
        on_heartbeat = onHeartbeat,
        onSlowDetected = Some((summary: String) => auditBgSlow(ctx, jobId, summary))
      )
      _ <- emitBgTaskStarted(
        ctx,
        jobId,
        bgDescription,
        persistent = false,
        kind = kind,
        originOverride = originOverride
      )
      // #753 速率闸：收编计一次创建（本拍可能正好触发熔断——本任务已在前台
      // 跑着，不能拒；熔断生效后**下一个**创建被拒）。Left 消息在此无消费面
      // （任务已存在），触发沿告警帧照发。
      _ <- BgTaskRegistry.gateCreation(ctx.sessionId.getOrElse("")).flatMap { d =>
        if d.breakerEngaged then emitRateAlert(ctx) else IO.unit
      }
    // 回执契约（main-agent-nonblocking 批，裁定原文「转后台时给主 agent 的
    // 回执要含：已转后台 + job 标识 + 如何查询」）：① [Background job started]
    // 头 + ② Job ID + ③ 已转后台说明（Auto-backgrounded after … NOT killed）
    // + ④ 完成通知承诺 + ⑤ 等待方式（BgWait 唯一有界等待原语）/取消方式
    // （background_job_id + cancel_background_job）。
    // 🔴 bash overhaul T1（作者令 2026-11-04）：原回执里「随时查询进度」式的
    // `background_job_id` 邀请是**受逼轮询**的来源（缺陷 D3——提示词禁止轮询，回执却
    // 邀请轮询，而当时没有任何等待原语）。现回执只给两条合法出路：真被阻塞 ⇒
    // BgWait 一次有界等待；否则结束回合等通知。
    // 🔴 T7（作者令 2026-11-04）：回执正文的唯一构造点移到
    // [[RemoteExecutor.autoBackgroundReceipt]] —— 本地腿与设备腿**逐字同形**由
    // 同一个函数保证（不是两处字符串「看起来一样」）。调用方加的前缀（如 sleep-poll
    // 警告）在 [[sleepPollGate]] 里拼，不在此处。
    yield Right(RemoteExecutor.autoBackgroundReceipt(jobId, waitedMs))

  /**
   * T7（作者令 2026-11-04）：**设备腿的收编腿**——把仍在跑的对端请求命令收编成本
   * 实例的后台任务，回执与本地腿逐字同形。
   *
   * 与本地 [[autoBackground]] 的差异只有三处，全部是「这是对端发起的一条命令」的
   * 必然结果：
   *  1. jobId = host 下发的 `bgJobId`（对端发起侧铸的 id）——两侧同一 id，使
   *     `BgWait`/`BgTaskCancel`/对端 `bg-cancel` 全按同一个 id 工作（禁在本侧铸第二
   *     个 id 让登记行与回执发散）；
   *  2. 台账 `kind="remote"`（命令不在本实例的会话里发起）；
   *  3. 该 kind 的死亡看护只读 `RemoteExecutor` 的等待句柄图（[[BgTaskLiveness]]
   *     的 remote 判据），故把「等命令终态」的 joiner 挂进图 —— 否则收编行会在收割
   *     器面前立刻显示为「终态腿绝路」，被误收割成 owner-lost（命令其实还在跑）。
   *
   * 真错误语义不变：本腿只在**到点未完成**时进入；命令自身的失败照旧经完成回调
   * （makeNotifyCallback）落终态。
   */
  private def remoteLegAutoBackground(
    shell: ShellSession,
    execFiber: Fiber[IO, Throwable, Either[Throwable, ProcessResult]],
    health: JobHealth,
    command: String,
    desc: Option[String],
    ctx: ToolContext,
    tailN: Option[Int],
    waitedMs: Long,
    hostJobId: Option[String]
  ): IO[Either[ToolError, String]] =
    val jobId = hostJobId.getOrElse(java.util.UUID.randomUUID().toString.take(8))
    for
      _ <- RemoteExecutor.current.fold(IO.unit) { exec =>
        joinResult(execFiber).attempt.void
          .guarantee(exec.unregisterWaitFiber(jobId))
          .start
          .flatMap(fib => exec.registerWaitFiber(jobId, fib))
      }
      out <- autoBackground(
        shell,
        execFiber,
        health,
        command,
        desc,
        ctx,
        tailN,
        waitedMs,
        jobIdOverride = Some(jobId),
        kind = "remote"
      )
    yield out

    end for

  end remoteLegAutoBackground

  /**
   * Activity bridge (#319): periodically checks the running process and, when
   * it shows progress, refreshes the agent registry's **process-side** activity
   * stamp (AgentRecord.processActivityMs) — NOT the agent-side stamp.
   *
   * 卡死判据换轴（取证在案）：
   * 本桥不再 touch `lastActivityMs` —— 事故实证：一条前台常驻 dev server 命令
   * （输出恒 0 行、CPU 微动 1.786 ms/s = 10ms 阈值的 5.4 倍）经此桥每 30s 刷新
   * agent 侧戳，令 TaskStuckWatcher 判据 / SessionKick / AgentControl 的 idle
   * **全部失明 2h50m**。「进程还有动静」不是「agent 还在推进」：本桥现在只写
   * `processActivityMs`（不得被 TaskStuckWatcher / SessionKick / 人可见 idle 读取），
   * agent 侧戳只由 agent 事件写（AgentCore.touchRegistryActivity）。
   *
   * While alive, also emits a once-a-minute running log
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
          // 机制 D：CPU 判断对齐 CpuActiveThresholdNanos（10ms/30s 采样，
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
          runningLog *> (if hasProgress then touchProcessActivity(ctx) *> loop(lines, cpu, tick)
                         else loop(lastLines, lastCpu, tick))
        }
    loop(health.outputLineCount.get(), 0L, 0).start

  end startActivityBridge

  /**
   * Refresh the agent registry's **process-side** activity stamp for this
   * session (if present). 换轴后：目标字段 = `processActivityMs`
   * （旧实现写 `lastActivityMs`，把进程活性混进 agent 侧判据——事故根因）。
   * 本戳不参与卡死判据，只作「子进程确实活着」的旁证。
   *
   * stuck 自动恢复（R-3）：本方法同时是**正信号（进展
   * 证据）的传感器**——调用点位于 `startActivityBridge` 的 `hasProgress` 分支，
   * 即「上一个采样窗内 stdout 行数增长 ∨ 单窗 CPU 增量 > 活动桥阈值」。两个字段
   * **同点采样、不同语义**：
   *   - `processActivityMs`「子进程还活着」——**明文禁被 watcher 读取**（旁证）；
   *   - `AgentRecord.lastProgressSignalAt`「本窗有进展」——写入语义单点 =
   *     [[nebflow.agent.AgentCore.markToolProgress]]，**只阻止判死、绝不促成判死**
   *     （消费点 = `TaskStuckWatcher.classify` 的类② 分流）。
   * 之所以在本采样点接线：设计 §2.3 原则 2 要求正信号必须**结构化**、判据不得解析
   * 自有日志——本桥的采样循环是全仓唯一持有「行数增量 / 单窗 CPU 增量」的结构化点。
   */
  private def touchProcessActivity(ctx: ToolContext): IO[Unit] =
    (ctx.sharedResources, ctx.sessionId) match
      case (Some(res), Some(sid)) =>
        val now = System.currentTimeMillis()
        res.agentRegistry.modify { m =>
          m.get(sid) match
            case Some(rec) =>
              // 纯函数已下沉 actor.AgentState(AgentCore 不留转发别名)。
              (m.updated(sid, AgentState.markToolProgress(rec.copy(processActivityMs = now), now)), ())
            case None => (m, ())
        }
      case _ => IO.unit

  private def formatResult(
    result: ProcessResult,
    desc: Option[String],
    tailN: Option[Int] = None
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
    if full.trim.isEmpty then Right("[Command executed successfully with no output]")
    else Right(full)

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

  /**
   * 节点完成闸批：本调用是否发生在 Project 节点会话（node- 前缀，NodeEngine
   * spawn 的单发 Flow 会话）。前缀常量单一来源 = NodeEngine.SessionPrefix。
   */
  private def isNodeSession(ctx: ToolContext): Boolean =
    ctx.sessionId.exists(_.startsWith(nebflow.core.project.NodeEngine.SessionPrefix))

  /**
   * Build the terminal-completion callback: agent + frontend + ledger + output retention.
   *
   * **T2 契约（bash overhaul，作者令 2026-11-04）——恒有回调、先落账再推送、绝不
   * 静默丢弃**（事实源 §D5/§M3/§5.1-A1·A3；业界依据 = dsh「结算通知推会话 + 忙时
   * next-step inbox + awaited 去重」）：
   *
   *  1. 返回 `Some(...)` **恒定**——旧形态 `ctx.agentActorRef.map{...}` 让「无 agent
   *     上下文」（REST 直调 / pre-spawn / 设备腿）整条回调链静默缺席：不注销行、
   *     不终态化输出留存、不落账本、不通知（缺陷 D5）。可见性放宽到
   *     `private[tools]`，供同包 spec 直调判红。
   *  2. 链内顺序**钉死**（每一环 fail-soft，任一环失败都不阻断后续；但**投递失败
   *     绝不吞账本**——顺序本身即保证）：
   *     `BgTaskRegistry.unregisterReturning` → `BgTaskOutputStore.finalizeTask`
   *     （fail-soft）→ `BgTaskLedger.recordCompletion`（**无条件**，fail-soft +
   *     日志）→ 前端 WS 帧 → agent 事件。
   *  3. 无 `agentActorRef` ⇒ 只跳过「推给它自己的 actor」那一段：投递面转
   *     [[BgNotifyDelivery.deliverAgentEvent]] 的 `None` 门（持久补投腿），
   *     事件照样有落点，绝不 IO.unit 蒸发。
   *  4. `awaited` 去重（[[BgTaskRegistry.markAwaited]] 的反面消费）：注销行带
   *     `awaited=true`（终态结果已被 BgWait 当面收走）⇒ **只跳过 agent 推送**
   *     （审计 `bg-notify-awaited-skip`），账本行与输出留存照做。
   *  5. 忙时下一站 = 既有 next-step 收件口：目标会话在 Processing 时事件入
   *     `pendingEvents`、回合边界统一 drain（`AgentProcessing.scala:1360-1441`），
   *     不另立第二套队列（详见 deliverAgentEvent 文档）。
   */
  private[tools] def makeNotifyCallback(
    command: String,
    desc: Option[String],
    ctx: ToolContext,
    jobId: String,
    tailN: Option[Int] = None,
    gateOwned: Boolean = false
  ): Option[Either[Throwable, ProcessResult] => IO[Unit]] =
    Some { (result: Either[Throwable, ProcessResult]) =>
      val firstLine = command.split('\n').headOption.getOrElse(command).take(80)
      val description = desc.getOrElse(firstLine)
      // 通知详情批（作者报障 2026-10-24「后台任务通知很不详细甚至有截断」）：注销
      // 即取回注册行——startedAtMs 算耗时进通知负载；负载组装依赖它 ⇒ unregister
      // 前置（旧链在负载定型后才 unregister，取不到）。
      val finishedAtMs = System.currentTimeMillis()
      BgTaskRegistry.unregisterReturning(jobId).flatMap { registered =>
        // awaited 去重读数（T2 契约 3）：注销即读——markAwaited 写的是同一行，
        // `unregisterReturning` 的原子取回就是终局读数（两拍分开读会漏掉「读后、
        // 注销前」落下的标记）。行已消失 = 极早终态/重复回调 ⇒ 视为未 awaited。
        val awaited = registered.exists(_.awaited)

        val durationMs = registered.map(st => (finishedAtMs - st.startedAtMs).max(0L))
        val (eventType, payload, metadata, exitInfo, exitCodeOpt, errHintOpt, storeStatus) = result match
          case Right(pr) =>
            val rawOut = tailN match
              case Some(n) => applyTailTruncation(pr.stdout, n)
              case None => pr.stdout
            val out = cleanOutput(sanitizeCardOutput(rawOut))
            val cleanedErr = cleanOutput(pr.stderr)
            // 负载组装单点 = BgNotifyPayload（纯函数）：退出码恒在（exit 0 = 显式
            // 成功信号，旧形态只在非零才带——作者无法从通知判断任务成没成功）+
            // 耗时 + 输出尾段（默认 20 行，截断标注带 jobId 指回详情卡看全文；
            // 全文照旧 BgTaskOutputStore.finalizeTask 落盘）。
            val built = BgNotifyPayload.completed(
              description,
              Some(pr.exitCode),
              durationMs,
              out,
              cleanedErr,
              jobId
            )
            (
              "completed",
              built.payload,
              built.metadata,
              if built.logTag.isEmpty then "" else s" (${built.logTag})",
              Some(pr.exitCode),
              None: Option[String],
              "completed"
            )
          case Left(e) =>
            val errInfo = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
            // 输出留存区语义（仅 store；agent 通知路径 eventType 不变）：取消类
            // 异常（cancelBackgroundJob / 会话收割 complete Left(InterruptedException)）
            // 在留存区记 cancelled 而非 failed——用户回看时语义准确。
            val store = e match
              case _: InterruptedException => "cancelled"
              case _ => "failed"
            // 失败通知同面补料（spec ③）：错误尾段 + 耗时；异常 message 常为单行，
            // 尾段截断只在多行错误栈生效。
            val built = BgNotifyPayload.failed(description, errInfo, durationMs, jobId)
            (
              "failed",
              built.payload,
              built.metadata,
              if built.logTag.isEmpty then s" ($errInfo)" else s" (${built.logTag}; $errInfo)",
              None: Option[Int],
              Some(errInfo),
              store
            )

        // 🔴 「只提醒」改造口径：本处的**台账写点
        // 已摘除**——原「等待集内任务被看护杀（TimeoutException）→ `markFailed` 终局
        // 记账 → 节点 failed+注明」链的**生产端取消**（消费端 `NodeEngine.drainFailures`
        // 与 `bgFailureMessage` **原样保留** ⇒ 台账恒空 ⇒ 自然走放行支）。
        // 判据本身**不删**：`TimeoutException` 仍是**读数**（B2 硬超时+停滞杀仍经
        // `killWith` 产生它 ⇒ 本分支仍会被命中），故保留一处**只提醒**审计：
        // 写 `bg-slow` 事件（与 #27 mount-stalled 同族：只写事件、不终态化）。
        // 语义分工：① 事件 = 「任务被判为停滞」这一事实的读数；② 节点终态**不再**由此
        // 改写（此前 = 必 failed，现 = 可正常 completed）。
        // 🔴 调用点删 ≠ 函数删：`BgTaskRegistry.markFailed` 本体保留（测试面 `:460`
        // 与 `BashPersistentBgTaskSpec` P5 直调；唯一生产点 = 本处，现读 `rg` 1 命中 ⇒ 0）。
        // 🔴 persistent 不在等待集、显式取消（InterruptedException）是 agent 自主决策
        // ⇒ 两者均不入账（口径不变）。
        val gateLedger = result match
          case Left(e: scala.concurrent.TimeoutException) if gateOwned =>
            auditBgSlow(
              ctx,
              jobId,
              s"wait-set bg task guard-hit: job $jobId (session ${ctx.sessionId.getOrElse("")}) " +
                s"ended with ${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("").take(160)} — " +
                "sensor only: no failure ledger, no node failure"
            )
          case _ => IO.unit

        // Notify agent via ExternalEvent.
        // D1（steward 批 2026-10-23；作者裁定 09:46「目标会话死了就激活发送，不用发
        // 给root」）：投递不再盲投「创建时焊死的会话 ActorRef」——`ref !` 是队列 offer
        // 永不失败，目标会话已死 = 消息进无人消费的队列静默蒸发（bg-wait cap 强制收
        // 尾后同样投尸）。交付面 = 活性判定 + 激活（复活）目标会话本身（持久化恢复
        // spawn 同源，通知落其新回合）+ 激活不可用则向**目标会话**注入队落「待补投」
        // 标记（复活重放补投给它）+ 三态全审计（消灭 WARN 吞掉的静默路径），见
        // BgNotifyDelivery。无 actorSystem（harness）保持旧行为直投。
        // ref ! 返回 IO[Unit] already — do NOT wrap in IO() or it
        // becomes IO[IO[Unit]] (double-wrapped, fires at construction time).
        // T2 契约 1/3：`targetRef` 传 **ctx 原值**（可能是 None）——
        //   - None ⇒ deliverAgentEvent 的 None 门（有目标会话 = 持久补投腿；连会话
        //     都没有 = 响亮 WARN + 审计），事件绝不蒸发；
        //   - awaited（终态结果已被 BgWait 当面交付）⇒ 跳过推送这一整段
        //     （账本与输出留存已在上面无条件落完），审计 `bg-notify-awaited-skip`。
        val notifyAgent =
          if awaited then
            logger.info(
              s"[bg-notify-awaited-skip] background job $jobId ($eventType): terminal result was already delivered by an awaiting caller (BgWait) — agent push skipped; ledger row + output retention kept",
              "sessionId" -> ctx.sessionId.getOrElse("")
            )
          else
            BgNotifyDelivery.deliverAgentEvent(
              targetRef = ctx.agentActorRef,
              targetSessionId = ctx.sessionId,
              system = ctx.actorSystem,
              registry = ctx.sharedResources.map(_.agentRegistry),
              projectName = ctx.projectName,
              flowNodeId = ctx.flowNodeId,
              jobId = jobId,
              source = "background-task",
              eventType = eventType,
              payload = payload,
              metadata = metadata
            )

        // Notify frontend via WS so the indicator dismisses. 通知详情批：终态帧
        // 补 exitCode / durationMs / finishedAt 三个只增键（面板行 exit chip 与
        // 冻结时长；旧前端缺读法 = 忽略，向后兼容）。
        val notifyFrontend = ctx.wsSend.fold(IO.unit) { send =>
          send(
            io.circe.Json.obj(
              "type" -> "backgroundTaskUpdate".asJson,
              "sessionId" -> ctx.sessionId.asJson,
              // 权威分键（计数/列表分叉修复）：前端直接按 rootSessionId
              // 分桶，替代已删除的 bgTaskRootFor 启发式逆向分键。缺省回退执行者
              // sessionId（根会话自己的任务 root==sessionId，分桶不变）。
              "rootSessionId" -> ctx.rootSessionId.orElse(ctx.sessionId).asJson,
              "taskId" -> jobId.asJson,
              "description" -> description.asJson,
              "status" -> eventType.asJson,
              "exitCode" -> exitCodeOpt.asJson,
              "durationMs" -> durationMs.asJson,
              "finishedAt" -> finishedAtMs.asJson
            )
          ).handleErrorWith(e => logger.warn(s"WS send failed for background job $jobId: ${e.getMessage}"))
        }

        // Log first so we know the callback fired, then send both notifications
        // independently — each has its own error recovery so one failure
        // doesn't prevent the other.
        // 输出查看批：终态翻转 + 进留存区（completed/failed；cancelled 走 WS
        // cancelBackgroundJob / reclaimSession 的 finalizeTask，幂等防双写）。
        // T2 契约 2（顺序钉死）：finalize → **无条件** recordCompletion → 前端帧 →
        // agent 事件。P2 first-terminal-wins 的**闸门退场**（原 `alreadyTerminal`
        // 跳过三段的写法会连带吃掉「无 agent 上下文/迟到腿」的全部落账——那正是
        // 缺陷 D5 的一半）：幂等改由各面自身承担——`finalizeTask` 首终态胜出、
        // `recordCompletion` 同 jobId 原位替换、注入队 correlationId 幂等。行已不在
        // 册的事实只进日志，不再改行为。
        val alreadyTerminal = registered.isEmpty
        BgTaskOutputStore
          .finalizeTask(jobId, storeStatus, exitCodeOpt, errHintOpt)
          .handleErrorWith(e2 => logger.warn(s"bg-output finalize failed for job $jobId: ${e2.getMessage}")) *>
          gateLedger *>
          // 完成事件持久账本（修复批 2026-10-24「先落账本再投递」+ T2 无条件化）：
          // 事件与输出尾段先落账，再走投递——投递面任何一环失联（目标会话 idle /
          // 无栅栏 / 直投未唤醒 / 激活 Deferred / 根本没有 agent ref），账本条目
          // 挂起，会话下次唤醒由补投腿（AgentActor.flushPendingLedgerCompletions）
          // 先补投未消费事件，永不蒸发。fail-soft：账本故障绝不阻断投递；反之
          // **投递失败也绝不吞账本**（顺序在前）。
          BgTaskLedger
            .recordCompletion(
              ctx.sessionId.getOrElse(""),
              jobId,
              description,
              eventType,
              payload
            )
            .handleErrorWith(e2 => logger.warn(s"bg-ledger record failed for job $jobId: ${e2.getMessage}")) *>
          notifyFrontend.void *>
          notifyAgent *>
          logger.info(
            s"Background job $jobId callback: $eventType$exitInfo" +
              (if alreadyTerminal then " (row already terminal — recorded + retained anyway, T2 durable-first)"
               else "") +
              (if awaited then " (awaited — agent push suppressed)" else ""),
            "sessionId" -> ctx.sessionId.getOrElse("")
          )
      }
    }

  /**
   * `bg-slow` 审计腿（「只提醒」）：
   * 把「后台任务被判停滞」这一事实写进项目事件流（nodeId = 节点 id，与 #27
   * mount-stalled 同族：**只写事件、不终态化、不杀进程**）。
   *
   * 🔴 依赖方向：`core.tools` **不得**依赖 `core.project`（BashTool 经 `FlowMapEventLog`
   * 在本模块内对项目面**零新增依赖**——`FlowMapEventLog` 是 project 包内的事件写入器，
   * 而本模块已有的 layer 边界只禁 `core.project`→`core.tools` 的反向。为守住「不新增
   * 跨层 import」，本腿经 `ToolContext` 已注入的**引擎侧身份**（`projectName` /
   * `flowNodeId`）+ `ProjectRuntimeRegistry` 解析 workspace——与编排工具族
   * 的 `ctx.projectName → ProjectRuntimeRegistry → workspace` 单点同款（判据不猜：
   * 解析不到 ⇒ 静默 no-op，只留 `logger.warn`；猜错 = 把事件写进别人的日志更糟）。
   */
  private def auditBgSlow(ctx: ToolContext, jobId: String, summary: String): IO[Unit] =
    val nodeId = ctx.flowNodeId.map(_.trim).filter(_.nonEmpty)
    ctx.projectName.map(_.trim).filter(_.nonEmpty) match
      case None => IO.unit
      case Some(name) =>
        nebflow.core.project.ProjectRuntimeRegistry.get(name).flatMap {
          case None => IO.unit
          case Some(rt) =>
            nebflow.core.project.FlowMapEventLog
              .append(
                rt.project.workspace,
                rt.project.name,
                nodeId.getOrElse(ctx.sessionId.getOrElse("")),
                "bg-slow",
                summary
              )
              .handleErrorWith(e => logger.warn(s"bg-slow audit append failed for job $jobId: ${e.getMessage}"))
        }

    end match

  end auditBgSlow

  /**
   * Emit a WS event so the frontend shows the background task indicator.
   *
   * T7（作者令 2026-11-04）：`kind` / `originOverride` 两个缝供设备腿收编用——
   * 设备腿（替对端实例跑的命令）登记 `kind="remote"`（命令不在本会话发起），
   * origin 可由调用方外部指定；本地腿两个缺省值 ⇒ 逐字节不变（注册面与 WS
   * 信封同源同值）。
   */
  private def emitBgTaskStarted(
    ctx: ToolContext,
    jobId: String,
    description: String,
    persistent: Boolean = false,
    kind: String = "local",
    originOverride: Option[(String, String)] = None
  ): IO[Unit] =
    // 来源标注：类别+显示名从
    // 注册会话推导（node- → Flow Map 节点名，dispatcher- → dispatcher/<project>，
    // 其余 → Nebula），注册与 WS 信封同源携带。
    val (origin, originLabel) =
      originOverride.getOrElse(BgTaskRegistry.originFor(ctx.sessionId.getOrElse(""), ctx.sessionName))
    // 任务模式标注批（2026-10-30）：开启任务的 delegate 会话生效执行形态
    // （kernel/dispatcher；modeFaceOf = 双模式总闸 + delegate 会话 + 记录三闸，
    // 闸关/非 delegate ⇒ None ⇒ 空串零渲染）。
    val ownerMode = nebflow.core.delegate.DelegateRegistry.modeFaceOf(ctx.sessionId).getOrElse("")
    // 后台任务归属批（2026-10-31）：开启会话的 delegate 批次短名（ownerNameOf
    // 单点；非 delegate/无名 ⇒ 空串零渲染），注册面与 WS 信封同源携带。
    val ownerName = nebflow.core.delegate.DelegateRegistry.ownerNameOf(ctx.sessionId).getOrElse("")
    BgTaskRegistry.register(
      jobId,
      ctx.sessionId.getOrElse(""),
      description,
      kind,
      ctx.rootSessionId.orElse(ctx.sessionId).getOrElse(""),
      persistent,
      origin,
      originLabel,
      ownerMode = ownerMode,
      ownerName = ownerName
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
          "startedAt" -> System.currentTimeMillis().asJson,
          "kind" -> kind.asJson,
          "origin" -> origin.asJson,
          "originLabel" -> originLabel.asJson,
          "ownerMode" -> ownerMode.asJson,
          "ownerName" -> ownerName.asJson
        )
        logger.info(s"Background job $jobId \"$description\" started", "sessionId" -> ctx.sessionId.getOrElse("")) *>
          send(json).handleErrorWith(e => logger.warn(s"WS send failed for job $jobId: ${e.getMessage}"))
      })

  end emitBgTaskStarted

  /**
   * #753 速率闸告警帧（熔断触发时发 wsSend）：`bgRateAlert` —
   * {sessionId, rootSessionId, count, windowSec, cooldownSec}。前端 toast 告警
   * （作者看得见「某会话在刷屏」），引擎侧同点另有 logger.warn（gateCreation
   * 内）。无 wsSend（remote 执行）时静默降级——引擎日志仍是告警面。
   */
  private def emitRateAlert(ctx: ToolContext): IO[Unit] =
    ctx.wsSend.fold(IO.unit) { send =>
      val json = io.circe.Json.obj(
        "type" -> "bgRateAlert".asJson,
        "sessionId" -> ctx.sessionId.asJson,
        "rootSessionId" -> ctx.rootSessionId.orElse(ctx.sessionId).asJson,
        "count" -> Defaults.BgRateBreakerAfter.asJson,
        "windowSec" -> (Defaults.BgRateWindowMs / 1000L).asJson,
        "cooldownSec" -> (Defaults.BgRateBreakerCooldownMs / 1000L).asJson
      )
      send(json).handleErrorWith(e => logger.warn(s"bgRateAlert WS send failed: ${e.getMessage}"))
    }

  end emitRateAlert

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
