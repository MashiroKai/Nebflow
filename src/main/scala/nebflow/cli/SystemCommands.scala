package nebflow.cli

import cats.effect.IO
import io.circe.Json
import io.circe.parser.*
import io.circe.syntax.*
import nebflow.service.ConfigSnapshot

// ===== System Commands (Offline) =====

object HelpCommand extends CliCommand:
  def name = "help"
  def description = "Show help information"
  def subcommands = List(HelpShow)
  def examples = List("nebflow help", "nebflow help session")

  private object HelpShow extends CliSubcommand:
    def name = "show"
    def description = "Show help (default)"
    def params = Nil

    def run(ctx: CliContext): IO[CliResult] =
      // Delegate to CliRouter's printHelp logic
      IO.pure(
        CliResult.text(
          "Use 'nebflow' without arguments to show help, or 'nebflow <command> --help' for command details."
        )
      )

end HelpCommand

object UpdateCommand extends CliCommand:
  def name = "update"
  def description = "Update nebflow to the latest version"
  def subcommands = List(UpdateRun)
  def examples = List("nebflow update")

  private object UpdateRun extends CliSubcommand:
    def name = "run"
    def description = "Run update"
    def params = Nil

    def run(ctx: CliContext): IO[CliResult] =
      IO.blocking {
        import sys.process.*
        val script =
          if System.getProperty("os.name").toLowerCase.contains("win") then
            """powershell -Command "& { iwr https://nebflow.space/install.ps1 | iex }" """
          else "curl -fsSL https://nebflow.space/install.sh | sh"
        val exitCode = script.!
        if exitCode == 0 then CliResult.text("Update completed")
        else CliResult.Error("Update failed", exitCode)
      }

  end UpdateRun

end UpdateCommand

object UninstallCommand extends CliCommand:
  def name = "uninstall"
  def description = "Uninstall nebflow"
  def subcommands = List(UninstallRun)
  def examples = List("nebflow uninstall")

  private object UninstallRun extends CliSubcommand:
    def name = "run"
    def description = "Run uninstall"
    def params = Nil

    def run(ctx: CliContext): IO[CliResult] =
      IO.blocking {
        import sys.process.*
        val script =
          if System.getProperty("os.name").toLowerCase.contains("win") then
            """powershell -Command "& { iwr https://nebflow.space/uninstall.ps1 | iex }" """
          else "curl -fsSL https://nebflow.space/uninstall.sh | sh"
        val exitCode = script.!
        if exitCode == 0 then CliResult.text("Uninstall completed")
        else CliResult.Error("Uninstall failed", exitCode)
      }

  end UninstallRun

end UninstallCommand

object VersionCommand extends CliCommand:
  def name = "version"
  def description = "Show version"
  def subcommands = List(VersionShow)
  def examples = List("nebflow version")

  private object VersionShow extends CliSubcommand:
    def name = "show"
    def description = "Display version number"
    def params = Nil

    def run(ctx: CliContext): IO[CliResult] =
      if ctx.json then IO.pure(CliResult.Json(io.circe.Json.obj("version" -> nebflow.Version.string.asJson)))
      else IO.pure(CliResult.text(s"nebflow v${nebflow.Version.string}"))

object StartCommand extends CliCommand:
  def name = "start"
  def description = "Start the Gateway server"
  def subcommands = List(StartRun)
  def examples = List("nebflow start")

  private object StartRun extends CliSubcommand:
    def name = "run"
    def description = "Start the Gateway"
    def params = Nil

    def run(ctx: CliContext): IO[CliResult] =
      // Delegated to Main.scala — this command triggers GatewayMain
      IO.pure(CliResult.Error("start is handled by Main.scala directly"))

object StopCommand extends CliCommand:
  def name = "stop"
  def description = "Stop the running Gateway"
  def subcommands = List(StopRun)
  def examples = List("nebflow stop")

  private object StopRun extends CliSubcommand:
    def name = "run"
    def description = "Stop the Gateway"
    def params = Nil

    def run(ctx: CliContext): IO[CliResult] =
      nebflow.cli.ProcessManager.stop().as(CliResult.ok)

object StatusCommand extends CliCommand:
  def name = "status"
  def description = "Check if Gateway is running"
  def subcommands = List(StatusCheck)
  def examples = List("nebflow status")

  private object StatusCheck extends CliSubcommand:
    def name = "check"
    def description = "Check Gateway status"
    def params = Nil

    def run(ctx: CliContext): IO[CliResult] =
      val pidOpt = nebflow.cli.ProcessManager.readPid()
      val running = pidOpt.exists(nebflow.cli.ProcessManager.isRunning)
      val port = sys.env.get("NEBFLOW_GATEWAY_PORT").flatMap(_.toIntOption).getOrElse(8080)
      if ctx.json then
        IO.pure(
          CliResult.Json(
            io.circe.Json.obj(
              "running" -> running.asJson,
              "pid" -> pidOpt.asJson,
              "port" -> port.asJson,
              "version" -> nebflow.Version.string.asJson
            )
          )
        )
      else if running then IO.pure(CliResult.text(s"✓ Gateway running (pid: ${pidOpt.get}, port: $port)"))
      else IO.pure(CliResult.text("✗ Gateway not running"))

    end run

  end StatusCheck

end StatusCommand

object DoctorCommand extends CliCommand:
  def name = "doctor"
  def description = "Diagnose and fix environment issues"
  def subcommands = List(DoctorRun)
  def examples = List("nebflow doctor", "nebflow doctor --fix")

  private object DoctorRun extends CliSubcommand:
    def name = "check"
    def description = "Run diagnostics (--fix to auto-repair)"

    def params = List(
      CliParam("fix", short = Some('f'), description = "Automatically fix detected issues", isFlag = true)
    )

    def run(ctx: CliContext): IO[CliResult] =
      IO.blocking {
        val fix = ctx.args.get("fix").contains("true")
        val osName = sys.props.getOrElse("os.name", "").toLowerCase
        val isWindows = osName.contains("win")
        val isMac = osName.contains("mac")
        val isLinux = osName.contains("linux")

        val checks = scala.collection.mutable.ListBuffer.empty[Diagnostic]
        val fixes = scala.collection.mutable.ListBuffer.empty[String]
        val configDir = ctx.configDir
        val configPath = configDir / "nebflow.json"

        // --- 1. Java ---
        val javaVer = sys.props.getOrElse("java.version", "unknown")
        val javaMajor =
          try javaVer.takeWhile(_.isDigit).toInt
          catch case _ => 0
        val javaOk = javaMajor >= 11
        checks += Diagnostic(
          "Java",
          javaOk,
          javaVer,
          if !javaOk && fix then
            fixes += "Java 11+ required — please install or update JDK"
            "Java 11+ required"
          else if !javaOk then "Java 11+ required"
          else ""
        )

        // --- 2. Config file ---
        val configExists = os.exists(configPath)
        val (configOk, configDetail, configFix) =
          if !configExists then
            if fix then
              os.write.over(configPath, "{}", createFolders = true)
              fixes += s"Created empty config: $configPath"
              (true, s"Created: $configPath", "")
            else (false, s"Not found: $configPath", "Run with --fix to create")
          else
            // Validate JSON
            val content =
              try os.read(configPath)
              catch case _ => ""
            parse(content) match
              case Right(json) =>
                // Check it's a valid object (not array or primitive)
                if json.isObject then (true, s"Valid JSON ($configPath)", "")
                else if fix then
                  val backupPath = configDir / "nebflow.json.bak"
                  os.move.over(configPath, backupPath)
                  os.write.over(configPath, "{}")
                  fixes += s"Invalid JSON (was ${json.name}) — backed up to $backupPath, reset to {}"
                  (true, s"Reset to empty: $configPath", "")
                else (false, s"Invalid: expected JSON object, got ${json.name}", "Run with --fix to reset")
              case Left(err) =>
                // Try to restore from snapshot
                val snaps = ConfigSnapshot.snapshots()
                if fix then
                  snaps.headOption match
                    case Some(snap) =>
                      try
                        os.copy.over(snap, configPath, createFolders = true)
                        fixes += s"Restored config from snapshot: ${snap.last}"
                        (true, s"Restored from snapshot: ${snap.last}", "")
                      catch
                        case e: Exception =>
                          val backupPath = configDir / "nebflow.json.bak"
                          try os.move.over(configPath, backupPath)
                          catch case _ => ()
                          os.write.over(configPath, "{}")
                          fixes += s"Corrupt config (parse error at ${err.message}) — backed up, reset to {}"
                          (true, "Reset to empty", "")
                    case None =>
                      val backupPath = configDir / "nebflow.json.bak"
                      try os.move.over(configPath, backupPath)
                      catch case _ => ()
                      os.write.over(configPath, "{}")
                      fixes += s"Corrupt config (no snapshots) — backed up, reset to {}"
                      (true, "Reset to empty", "")
                else (false, s"Corrupt JSON: ${err.message}", "Run with --fix to restore from snapshot")
                end if
            end match
        checks += Diagnostic("Config", configOk, configDetail, configFix)

        // --- 3. Config snapshots ---
        val snaps = ConfigSnapshot.snapshots()
        if snaps.nonEmpty then
          checks += Diagnostic("Snapshots", true, s"${snaps.length} snapshot(s), latest: ${snaps.head.last}", "")
        else checks += Diagnostic("Snapshots", true, "No snapshots yet (created on next successful start)", "")

        // --- 4. Auth token ---
        val authPath = configDir / "auth.json"
        val authExists = os.exists(authPath)
        checks += Diagnostic(
          "Auth",
          authExists,
          if authExists then authPath.toString else s"Not found: $authPath",
          if !authExists then "Created automatically on next start" else ""
        )

        // --- 5. PID file / Gateway status ---
        val pidOpt = ProcessManager.readPid()
        val gatewayRunning = pidOpt.exists(ProcessManager.isRunning)
        val port = sys.env.get("NEBFLOW_GATEWAY_PORT").flatMap(_.toIntOption).getOrElse(8080)
        if gatewayRunning then checks += Diagnostic("Gateway", true, s"Running (pid=${pidOpt.get}, port=$port)", "")
        else
          // Stale PID file?
          val pidExists = os.exists(configDir / ".pid")
          if pidExists && fix then
            ProcessManager.removePid()
            fixes += "Removed stale PID file"
            checks += Diagnostic(
              "Gateway",
              false,
              "Not running (cleaned stale PID file)",
              "Run 'nebflow start' to start"
            )
          else if pidExists then
            checks += Diagnostic(
              "Gateway",
              false,
              "Not running (stale PID file)",
              "Run with --fix to clean, or 'nebflow start'"
            )
          else checks += Diagnostic("Gateway", false, "Not running", "Run 'nebflow start'")
          end if
        end if

        // --- 6. Disk space ---
        val homeDir = java.io.File(sys.props("user.home"))
        val freeGB = homeDir.getUsableSpace / (1024 * 1024 * 1024)
        val totalGB = homeDir.getTotalSpace / (1024 * 1024 * 1024)
        val usagePercent = ((totalGB - freeGB) * 100 / Math.max(totalGB, 1))
        val diskOk = usagePercent < 90
        checks += Diagnostic(
          "Disk",
          diskOk,
          s"${freeGB}GB free / ${totalGB}GB total (${usagePercent}% used)",
          if !diskOk then "Free up disk space" else ""
        )

        // --- 7. Sessions directory ---
        val sessionsDir = configDir / "sessions"
        val sessionsOk = os.exists(sessionsDir)
        if !sessionsOk && fix then
          os.makeDir.all(sessionsDir)
          fixes += s"Created sessions directory: $sessionsDir"
          checks += Diagnostic("Sessions", true, s"Created: $sessionsDir", "")
        else
          checks += Diagnostic(
            "Sessions",
            sessionsOk,
            if sessionsOk then s"$sessionsDir" else "Not found",
            if !sessionsOk then "Run with --fix to create" else ""
          )

        // --- 8. Port availability (if gateway not running) ---
        if !gatewayRunning then
          try
            val serverSocket = new java.net.ServerSocket(port)
            serverSocket.close()
            checks += Diagnostic("Port", true, s"$port available", "")
          catch
            case _: java.net.BindException =>
              checks += Diagnostic(
                "Port",
                false,
                s"Port $port in use by another process",
                if isWindows then "Run 'netstat -ano | findstr :$port' to find the process"
                else s"Run 'lsof -i :$port' to find the process"
              )
            case _ =>
              checks += Diagnostic("Port", true, s"$port (check skipped)", "")
        end if

        // --- 9. Platform-specific checks ---
        if isWindows then
          // Check if running from a path with non-ASCII characters (common issue on Chinese Windows)
          val home = sys.props("user.home")
          val hasNonAscii = home.exists(c => c > 127)
          if hasNonAscii then
            checks += Diagnostic("Platform", true, s"Windows (home has non-ASCII chars — usually OK)", "")
          else checks += Diagnostic("Platform", true, "Windows", "")

          // Cross-drive check: warn if working directory and home are on different drives
          val pwd = sys.props("user.dir")
          val homeDrive = home.take(1).toUpperCase
          val pwdDrive = pwd.take(1).toUpperCase
          if homeDrive != pwdDrive then
            checks += Diagnostic(
              "Paths",
              true,
              s"Home=$homeDrive: drive, CWD=$pwdDrive: drive (cross-drive paths resolved automatically)",
              ""
            )
          else checks += Diagnostic("Paths", true, s"Home=$home CWD=$pwd", "")
        else
          // Unix: check write permission on config dir
          val canWrite = configDir.toIO.canWrite()
          checks += Diagnostic(
            "Permissions",
            canWrite,
            if canWrite then s"Write OK: $configDir" else s"Cannot write: $configDir",
            if !canWrite && fix then
              try
                configDir.toIO.setWritable(true)
                fixes += s"Fixed write permission: $configDir"
                "Fixed"
              catch case _ => "Run: chmod u+w " + configDir.toString
            else if !canWrite then "Run: chmod u+w " + configDir.toString
            else ""
          )
        end if

        // --- 10. Logs directory ---
        val logsDir = configDir / "logs"
        val logsOk = os.exists(logsDir)
        if !logsOk && fix then
          os.makeDir.all(logsDir)
          fixes += s"Created logs directory: $logsDir"
          checks += Diagnostic("Logs", true, s"Created: $logsDir", "")
        else
          checks += Diagnostic(
            "Logs",
            logsOk,
            if logsOk then s"$logsDir" else "Not found (auto-created on start)",
            if !logsOk then "" else ""
          )

        // --- Build output ---
        if ctx.json then
          CliResult.Json(
            Json.obj(
              "version" -> nebflow.Version.string.asJson,
              "checks" -> checks.toList.map { d =>
                Json.obj(
                  "name" -> d.name.asJson,
                  "ok" -> d.ok.asJson,
                  "detail" -> d.detail.asJson,
                  "hint" -> d.hint.asJson
                )
              }.asJson,
              "fixes" -> fixes.toList.asJson
            )
          )
        else
          val lines = scala.collection.mutable.ListBuffer.empty[String]
          lines += s"nebflow v${nebflow.Version.string} — diagnostics"
          lines += ""
          for d <- checks do
            val icon = if d.ok then "✓" else "✗"
            val line = s"  $icon ${d.name}: ${d.detail}"
            lines += line
            if d.hint.nonEmpty then lines += s"     → ${d.hint}"
          if fixes.nonEmpty then
            lines += ""
            lines += "Fixes applied:"
            fixes.foreach(f => lines += s"  ✓ $f")
          CliResult.Text(lines.toList)
        end if
      }
  end DoctorRun

  private case class Diagnostic(name: String, ok: Boolean, detail: String, hint: String)
end DoctorCommand
