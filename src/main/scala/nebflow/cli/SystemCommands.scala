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
  def examples = List("stand help", "stand help session")

  private object HelpShow extends CliSubcommand:
    def name = "show"
    def description = "Show help (default)"
    def params = Nil

    def run(ctx: CliContext): IO[CliResult] =
      // T7: the previous line said "Use 'stand' without arguments to show
      // help" — the opposite of the implementation (no arguments starts the
      // Gateway, Main.scala:47-50). Replaced with the pointer line that
      // printHelp already uses (no new wording).
      IO.pure(CliResult.text("Use 'stand <command> --help' for command details"))

end HelpCommand

object UpdateCommand extends CliCommand:
  def name = "update"
  def description = s"Update ${nebflow.shared.Branding.productName} to the latest version"
  def subcommands = List(UpdateRun)
  def examples =
    List("stand update", "stand update --beta", "stand update --device DESKTOP-IJBBEOT", "stand update --from <jar>")

  private object UpdateRun extends CliSubcommand:
    def name = "run"
    def description = "Run update (--from <jar> two-phase local; --beta for latest beta, --device for remote)"

    def params = List(
      CliParam("beta", short = Some('b'), description = "Install latest beta version", isFlag = true),
      CliParam("device", short = Some('d'), description = "Update a remote device (requires running gateway)"),
      CliParam(
        "from",
        None,
        "Two-phase update from a local jar: Phase1 stage+verify (engine untouched) → Phase2 pointer promote + OS-detached apply with health gate and auto-rollback"
      ),
      CliParam(
        "stage-only",
        None,
        "With --from: stop after Phase1 staging — no pointer change, no restart",
        isFlag = true
      )
    )

    def run(ctx: CliContext): IO[CliResult] =
      ctx.args.get("device") match
        case Some(deviceName) if deviceName != "true" =>
          // Remote update — call the target device's /api/neblink/update via neblink
          remoteUpdate(deviceName, ctx.args.get("beta").contains("true"))
        case _ =>
          ctx.args.get("from") match
            case Some(jar) if jar != "true" && jar.trim.nonEmpty =>
              twoPhaseLocal(os.Path(jar.trim), stageOnly = ctx.args.get("stage-only").contains("true"))
            case Some(_) =>
              IO.pure(CliResult.Error("--from requires the jar path: stand update --from <path>/stand-assembly-<version>.jar", 1))
            case None =>
              // Local channel update (existing behavior, unchanged)
              IO.blocking {
                import sys.process.*
                val isWindows = System.getProperty("os.name").toLowerCase.contains("win")
                val script =
                  if ctx.args.get("beta").contains("true") then
                    if isWindows then
                      """powershell -Command "$env:CHANNEL='beta'; iwr """ + nebflow.shared.Branding.installPs1Url + """ | iex" """
                    else "curl -fsSL " + nebflow.shared.Branding.installUrl + " | sh -s -- --beta"
                  else if isWindows then
                    """powershell -Command "& { iwr """ + nebflow.shared.Branding.installPs1Url + """ | iex }" """
                  else "curl -fsSL " + nebflow.shared.Branding.installUrl + " | sh"
                val exitCode = script.!
                if exitCode == 0 then CliResult.text("Update completed")
                else CliResult.Error("Update failed", exitCode)
              }

    // ── two-phase local update（台账 #916）────────────────────────────────

    /**
     * Phase1 [[UpdateStage.stage]]（校验+staging，引擎/指针零触碰）→ 全就绪才
     * Phase2：pointer 布局 = [[nebflow.core.hotrestart.InstallPointer.promote]]
     * 原子切（previous←旧 current，回滚锚就位）；两种布局随后都注册 OS 托管
     * detached apply（stop→start→健康闸含版本断言→失败自动回滚旧版）。注册
     * 失败 → 指针原样放回（staged 包留在盘上无害）。引擎未在跑 → 不注册 apply，
     * 下一次 start 直接吃新指针/新包。
     */
    private[cli] def twoPhaseLocal(
        jar: os.Path,
        stageOnly: Boolean,
        resolveL: () => Option[String] = () =>
          DetachedApply.resolveLauncher(sys.env, sys.props.getOrElse("user.home", ""), isWindows),
        doStage: (os.Path, os.Path, DetachedApply.Layout) => IO[Either[String, UpdateStage.Staged]] =
          (j, dir, lay) => UpdateStage.stage(j, dir, lay),
        doRegister: DetachedApply.Plan => IO[Either[String, String]] = p => DetachedApply.register(p)
    ): IO[CliResult] =
      IO.blocking(isWindows).flatMap { isWin =>
        resolveL() match
          case None =>
            IO.pure(
              CliResult.Error(
                "stand launcher not found (posix: ~/.stand/bin/stand; windows: %LOCALAPPDATA%\\stand\\stand.cmd; override: STAND_LAUNCHER)",
                1
              )
            )
          case Some(launcher) =>
            val installDir = DetachedApply.installDirOf(launcher)
            val layout = DetachedApply.detectLayout(installDir)
            doStage(jar, installDir, layout).flatMap {
              case Left(err) => IO.pure(CliResult.Error(s"phase1 stage failed (nothing promoted): $err", 1))
              case Right(staged) if stageOnly =>
                IO.pure(
                  CliResult.text(
                    s"phase1 staged: ${staged.jarName} → ${staged.stagedPath} (sha256 ${staged.sha256.take(16)}…${if staged.idempotentHit then ", idempotent hit" else ""})\n  pointer untouched, engine untouched — run without --stage-only to promote + restart"
                  )
                )
              case Right(staged) =>
                val promote = IO.blocking {
                  if layout == DetachedApply.Layout.Pointer then
                    for
                      preCur <- nebflow.core.hotrestart.InstallPointer.readCurrent(installDir)
                      prePrev <- nebflow.core.hotrestart.InstallPointer.readPrevious(installDir)
                      promoted <- nebflow.core.hotrestart.InstallPointer.promote(installDir, staged.version)
                    yield promoted
                      .map(_ => (preCur, prePrev): (Option[String], Option[String]))
                      .left.map(_.toString)
                  else IO.pure(Right((None, None): (Option[String], Option[String])))
                }.flatten
                promote.flatMap {
                  case Left(err) =>
                    IO.pure(CliResult.Error(s"phase2 promote failed (pointer untouched): $err", 1))
                  case Right(pointersBefore) =>
                    val port = nebflow.gateway.GatewayConfig.load.map(_.port.value)
                    port.flatMap { p =>
                      val pidAlive = ProcessManager.readPid().exists(ProcessManager.isRunning)
                      val listener =
                        IO.blocking(SingleInstanceGuard.connectProbeAccepted(p))
                      listener.map(accepted => pidAlive || accepted).flatMap { engineRunning =>
                        if !engineRunning then
                          IO.pure(
                            CliResult.text(
                              s"phase2 done: staged ${staged.version} promoted (pointer/winsort selection face) — engine not running, next start runs ${staged.version}"
                            )
                          )
                        else
                          IO.blocking(
                            DetachedApply.defaultPlan(
                              DetachedApply.Kind.Update,
                              p,
                              Some(staged.version),
                              ProcessManager.readPid()
                            )
                          ).flatMap {
                            case Left(err) => restoreAndFail(installDir, layout, pointersBefore, err)
                            case Right(plan) =>
                              doRegister(plan).flatMap {
                                case Right(msg) =>
                                  IO.pure(
                                    CliResult.text(
                                      s"phase1 staged ${staged.version} (sha256 ${staged.sha256.take(16)}…) → phase2 promoted + detached apply registered\n  $msg\n  on health-gate failure the previous version is restored automatically (cause lands in the log)"
                                    )
                                  )
                                case Left(err) =>
                                  restoreAndFail(installDir, layout, pointersBefore, err)
                              }
                          }
                      }
                    }
                  }
            }
      }

    /** 注册失败 → 指针原样放回（staged 包留在盘上无害），报因退出。 */
    private def restoreAndFail(
        installDir: os.Path,
        layout: DetachedApply.Layout,
        pointersBefore: (Option[String], Option[String]),
        cause: String
    ): IO[CliResult] =
      if layout != DetachedApply.Layout.Pointer then
        IO.pure(CliResult.Error(s"apply registration failed (selection face unchanged): $cause", 1))
      else
        pointersBefore match
          case (Some(cur), prev) =>
            nebflow.core.hotrestart.InstallPointer
              .writePointers(installDir, cur, prev)
              .attempt
              .map {
                _ =>
                  CliResult.Error(s"apply registration failed — pointers restored to $cur: $cause", 1)
              }
          case (None, _) =>
            IO.pure(CliResult.Error(s"apply registration failed (no pointer to restore — layout had none): $cause", 1))

    private def isWindows: Boolean = System.getProperty("os.name").toLowerCase.contains("win")

    /** Trigger update on a remote device via the gateway (P2P first, relay fallback). */
    private def remoteUpdate(deviceName: String, beta: Boolean): IO[CliResult] =
      GatewayClient.create.flatMap {
        case None =>
          IO.pure(
            CliResult.Error("Gateway not running. Start with 'stand start' (local gateway needed for neblink status)")
          )
        case Some(client) =>
          // hotupdate 批 3 · G8（设计 §9:173 契约增量）：载荷**新增可选**幂等键
          // `clientRequestId`（加法字段 + 双向容错——老端忽略未知键，本端缺席即现行为
          // 逐字节不变）。🔴 既有 `device` / `beta` 两字段的语义与存在性零改动。
          // 键的生成方 = 调用方：一次命令行调用 = 一次逻辑更新请求。命令行面无自动重试
          // 链（一次尝试零重试），故无需跨调用保留键；界面侧设备行的重试复用规则见
          // resources/web/js/contacts.js 的触发点注释。
          val clientRequestId = java.util.UUID.randomUUID().toString
          val payload = io.circe.Json.obj(
            "device" -> deviceName.asJson,
            "beta" -> beta.asJson,
            "clientRequestId" -> clientRequestId.asJson
          )
          client
            .post("/api/neblink/remote-update", payload)
            .flatMap { resp =>
              val success = resp.hcursor.downField("success").as[Boolean].getOrElse(false)
              val msg = resp.hcursor
                .downField("message")
                .as[String]
                .orElse(resp.hcursor.downField("error").as[String])
                .getOrElse("Unknown error")
              if success then IO.pure(CliResult.text(s"Remote update on $deviceName: $msg"))
              else IO.pure(CliResult.Error(msg))
            }
            .handleErrorWith { e =>
              IO.pure(CliResult.Error(s"Gateway request failed: ${e.getMessage}"))
            }
      }

  end UpdateRun

end UpdateCommand

object UninstallCommand extends CliCommand:
  def name = "uninstall"
  def description = s"Uninstall ${nebflow.shared.Branding.productName}"
  def subcommands = List(UninstallRun)
  def examples = List("stand uninstall")

  private object UninstallRun extends CliSubcommand:
    def name = "run"
    def description = "Run uninstall"
    def params = Nil

    def run(ctx: CliContext): IO[CliResult] =
      IO.blocking {
        import sys.process.*
        val script =
          if System.getProperty("os.name").toLowerCase.contains("win") then
            """powershell -Command "& { iwr """ + nebflow.shared.Branding.uninstallPs1Url + """ | iex }" """
          else "curl -fsSL " + nebflow.shared.Branding.uninstallUrl + " | sh"
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
  def examples = List("stand version")

  private object VersionShow extends CliSubcommand:
    def name = "show"
    def description = "Display version number"
    def params = Nil

    def run(ctx: CliContext): IO[CliResult] =
      if ctx.json then IO.pure(CliResult.Json(io.circe.Json.obj("version" -> nebflow.Version.string.asJson)))
      else IO.pure(CliResult.text(s"${nebflow.shared.Branding.productName} ${nebflow.Version.string}"))

object StartCommand extends CliCommand:
  def name = "start"
  def description = "Start the Gateway server"
  def subcommands = List(StartRun)
  def examples = List("stand start")

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
  def examples = List("stand stop", "stand stop --force", "stand stop --tree")

  private[cli] object StopRun extends CliSubcommand:
    def name = "run"
    def description = "Stop the Gateway (--force skips the ownership gate; --tree kills an sbt-managed chain)"
    def params = List(
      CliParam("force", None, "Skip the ownership gate (identity check, port confirmation and the 8080/8097 dev-host gate always apply)", isFlag = true),
      CliParam("tree", None, "Stop the whole sbt-managed process chain; degrades to a single-process stop when no sbt ancestor exists", isFlag = true)
    )

    def run(ctx: CliContext): IO[CliResult] =
      runStop(stopOptions(ctx))

  /**
   * StopOutcome → CliResult mapping (piece 4): Stopped/NotRunning exit 0
   * (not-running is idempotent success), Refused/Failed exit 1. Exposed for
   * the spec to drive with injected deps — zero real signals there.
   */
  private[cli] def runStop(
      opts: ProcessManager.StopOptions,
      deps: ProcessManager.StopDeps = ProcessManager.StopDeps()
  ): IO[CliResult] =
    ProcessManager.stopHardened(opts, deps).map {
      case ProcessManager.StopOutcome.NotRunning =>
        CliResult.Exit(0, s"${nebflow.shared.Branding.productName} is not running")
      case ProcessManager.StopOutcome.Stopped(message) =>
        CliResult.Exit(0, message)
      case ProcessManager.StopOutcome.Refused(reason) =>
        CliResult.Error(reason, 1)
      case ProcessManager.StopOutcome.Failed(reason) =>
        CliResult.Error(reason, 1)
    }

  private[cli] def stopOptions(ctx: CliContext): ProcessManager.StopOptions =
    ProcessManager.StopOptions(
      force = ctx.args.get("force").contains("true"),
      tree = ctx.args.get("tree").contains("true")
    )

object StatusCommand extends CliCommand:
  def name = "status"
  def description = "Check if Gateway is running"
  def subcommands = List(StatusCheck)
  def examples = List("stand status")

  private[cli] object StatusCheck extends CliSubcommand:
    def name = "check"
    def description = "Check Gateway status"
    def params = Nil

    def run(ctx: CliContext): IO[CliResult] =
      GatewayClient.readPort.flatMap { port =>
        IO.blocking {
          val pidOpt = nebflow.cli.ProcessManager.readPid()
          val pidRunning = pidOpt.exists(nebflow.cli.ProcessManager.isRunning)
          // A5: the pid file alone made `status` report "✗ Gateway not running"
          // while the gateway was actually serving (cross-home start, raced
          // cleanup, an older build that wrote no pid). Probe the port too:
          // an identity probe first (is the occupant ours?), then a plain TCP
          // connect to tell "someone else is on this port" from "free".
          val ours =
            if pidRunning then None
            else nebflow.cli.SingleInstanceGuard.checkPortBlocking("127.0.0.1", port)
          val foreign =
            if pidRunning || ours.isDefined then false
            else nebflow.cli.SingleInstanceGuard.connectProbeAccepted(port)
          val running = pidRunning || ours.isDefined

          // Phase 2 (§3.1): ownership sidecar fields — daemon true only for
          // launcher-started instances; no meta file → null (not false).
          val meta = PidMeta.read()
          val startedBy = meta.map(_.startedBy)
          val mode = meta.flatMap(_.mode)
          val daemon: Option[Boolean] = startedBy.map(_ == PidMeta.StartedByLauncher)

          // Exit codes: 0 running / 3 not running / 4 abnormal (foreign
          // occupant). CliResult.Exit keeps the text face prefix-free.
          val code = if running then 0 else if foreign then 4 else 3

          if ctx.json then
            CliResult.Exit(
              code,
              io.circe.Json.obj(
                "running" -> running.asJson,
                "pid" -> pidOpt.asJson,
                "port" -> port.asJson,
                "version" -> nebflow.Version.string.asJson,
                "daemon" -> daemon.asJson,
                "startedBy" -> startedBy.asJson,
                "mode" -> mode.asJson
              ).spaces2
            )
          else
            val head =
              if pidRunning then s"✓ Gateway running (pid: ${pidOpt.get}, port: $port)"
              else if ours.isDefined then s"✓ Gateway running (port: $port, no pid file)"
              else if foreign then s"✗ Gateway not running (port $port is in use by another program)"
              else "✗ Gateway not running"
            val metaLines = startedBy match
              case Some(by) =>
                List(
                  s"  started-by: $by" +
                    mode.fold("")(m => s" ($m)") +
                    (if daemon.contains(true) then " [daemon]" else "")
                )
              case None => Nil
            CliResult.Exit(code, (head :: metaLines).mkString("\n"))
        }
      }

    end run

  end StatusCheck

end StatusCommand

object DoctorCommand extends CliCommand:
  def name = "doctor"
  def description = "Diagnose and fix environment issues"
  def subcommands = List(DoctorRun)
  def examples = List("stand doctor", "stand doctor --fix")

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
        val configPath = nebflow.shared.PathUtil.configJsonReadPath(configDir)

        // --- 1. Java ---
        val javaVer = sys.props.getOrElse("java.version", "unknown")
        val javaMajor =
          try javaVer.takeWhile(_.isDigit).toInt
          catch case _ => 0
        val javaOk = javaMajor >= 21
        checks += Diagnostic(
          "Java",
          javaOk,
          javaVer,
          if !javaOk && fix then
            fixes += "Java 21+ required — please install or update JDK (https://adoptium.net/temurin/releases/?version=21)"
            "Java 21+ required — install JDK 21, then re-run 'stand doctor --fix'"
          else if !javaOk then
            "Java 21+ required — install JDK 21 (https://adoptium.net/temurin/releases/?version=21), then re-run 'stand doctor'"
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
        val port = nebflow.shared.Branding.env("GATEWAY_PORT").flatMap(_.toIntOption).getOrElse(nebflow.shared.Defaults.GatewayPort)
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
              "Run 'stand start' to start"
            )
          else if pidExists then
            checks += Diagnostic(
              "Gateway",
              false,
              "Not running (stale PID file)",
              "Run with --fix to clean, or 'stand start'"
            )
          else checks += Diagnostic("Gateway", false, "Not running", "Run 'stand start'")
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
          lines += s"${nebflow.shared.Branding.productName} ${nebflow.Version.string} — diagnostics"
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
