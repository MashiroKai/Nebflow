package nebflow.cli

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import nebflow.gateway.GatewayConfig
import nebflow.shared.PathUtil

/**
 * `stand restart` (phase 2 daemon plan §3.4; 台账 #916 2026-10-26 detached
 * apply 重地基) = stop + start as ONE command. TWO faces:
 *
 *  - '''detached (default)''': the apply phase (stop→start→health) is executed
 *    by an OS-managed one-shot job (macOS launchd agent / Windows scheduled
 *    task — [[DetachedApply]]), not by this process. The CLI only REGISTERS
 *    the job and returns. Rationale is today's Mac incident (12:06): a
 *    restart issued from inside the dying engine's own process tree died with
 *    it — the start leg never ran and left zero artifacts. An OS-managed job
 *    survives every invocation form (ssh / console / daemon / engine tree).
 *    The apply script logs to `<dataRoot>/logs/apply-restart.log` and records
 *    state phases in `<dataRoot>/apply/state.json` — never unobserved again.
 *  - '''inline (--inline)''': the legacy face — stop, then daemon start from
 *    THIS process (spec-driven; kept for callers that script around the old
 *    synchronous confirmation line).
 *
 * Hot-restart (`--succeed` / SuccessorGate) is NOT touched by this command —
 * that is the in-place update mechanism, this is the cold stop-and-start face.
 */
object RestartCommand extends CliCommand:
  def name = "restart"
  def description = "Stop the running Gateway and start it again (OS-detached apply by default)"
  def subcommands = List(RestartRun)
  def examples = List("stand restart", "stand restart --inline", "stand restart --force")

  private object RestartRun extends CliSubcommand:
    def name = "run"
    def description = "Stop then start the Gateway (stop flags pass through; default apply is OS-detached)"
    def params = List(
      CliParam("force", None, "Skip the stop ownership gate (identity and dev-host gates always apply)", isFlag = true),
      CliParam("tree", None, "Stop the whole sbt-managed process chain before starting", isFlag = true),
      CliParam(
        "inline",
        None,
        "Legacy inline face: stop and start inside this process instead of the OS-detached apply job",
        isFlag = true
      )
    )

    def run(ctx: CliContext): IO[CliResult] =
      val opts = ProcessManager.StopOptions(
        force = ctx.args.get("force").contains("true"),
        tree = ctx.args.get("tree").contains("true")
      )
      if ctx.args.get("inline").contains("true") then
        runRestart(opts, launch = DaemonSpawn.launch)
      else if opts.force || opts.tree then
        IO.pure(
          CliResult.Error(
            "--force/--tree belong to the inline face (--inline); the detached apply stop leg always runs the launcher's hardened stop",
            1
          )
        )
      else runDetached(opts)

  /**
   * Orchestration core of the INLINE face (spec-driven): `launch` injectable
   * so the spec can assert "refused stop ⇒ zero spawns" without any real
   * process.
   */
  private[cli] def runRestart(
      opts: ProcessManager.StopOptions,
      deps: ProcessManager.StopDeps = ProcessManager.StopDeps(),
      launch: (os.Path, Int, Boolean) => IO[ExitCode] = DaemonSpawn.launch
  ): IO[CliResult] =
    ProcessManager.stopHardened(opts, deps).flatMap {
      case ProcessManager.StopOutcome.NotRunning =>
        startFresh(launch)
      case ProcessManager.StopOutcome.Stopped(_) =>
        startFresh(launch)
      case ProcessManager.StopOutcome.Refused(reason) =>
        IO.pure(CliResult.Error(s"restart refused — not starting: $reason", 1))
      case ProcessManager.StopOutcome.Failed(reason) =>
        IO.pure(CliResult.Error(s"restart failed during stop — not starting: $reason", 1))
    }

  private def startFresh(
      launch: (os.Path, Int, Boolean) => IO[ExitCode]
  ): IO[CliResult] =
    GatewayConfig.load.flatMap { cfg =>
      launch(PathUtil.dataRoot, cfg.port.value, GatewayConfig.noBrowser)
        .map(ec => CliResult.Exit(ec.code, ""))
    }

  // ── detached face（#916）：注册 OS 托管 apply，CLI 立即返回 ─────────────

  /**
   * Registering decision (all pre-checks BEFORE any registration — a refused
   * restart registers nothing):
   *   1. gateway NOT running (no live pid, port free) → plain daemon start
   *      inline (nothing to hand over; the start cannot race a dying engine).
   *   2. port held by a FOREIGN process → refuse now (the apply stop leg would
   *      only time out against it — fail at the registration gate instead).
   *   3. gateway running (pid alive and/or our listener on the port) →
   *      register the detached apply job (stop→start→health with logging).
   */
  private[cli] def runDetached(
      opts: ProcessManager.StopOptions,
      register: DetachedApply.Plan => IO[Either[String, String]] = p => DetachedApply.register(p),
      launch: (os.Path, Int, Boolean) => IO[ExitCode] = DaemonSpawn.launch
  ): IO[CliResult] =
    GatewayConfig.load.flatMap { cfg =>
      val port = cfg.port.value
      val pidAlive = ProcessManager.readPid().exists(ProcessManager.isRunning)
      val portProbe = IO.blocking(SingleInstanceGuard.checkPortBlocking("127.0.0.1", port))
      val foreignListener = IO.blocking(SingleInstanceGuard.connectProbeAccepted(port))
      (portProbe, foreignListener).mapN((ours, anyListener) => (pidAlive, ours, anyListener)).flatMap {
        case (false, None, false) =>
          // 1. nothing running → start inline (no apply machinery needed)
          startFresh(launch)
        case (_, None, true) =>
          IO.pure(
            CliResult.Error(
              s"restart refused — port $port is held by a non-stand process (the detached apply would only time out against it)",
              1
            )
          )
        case (pidAlive, ours, _) =>
          // 2/3. our gateway (pid alive and/or identity probe) → detached apply
          IO.blocking(DetachedApply.defaultPlan(DetachedApply.Kind.Restart, port, None, ProcessManager.readPid()))
            .flatMap {
              case Left(err) => IO.pure(CliResult.Error(s"restart not registered: $err", 1))
              case Right(plan) =>
                register(plan).map {
                  case Left(err) => CliResult.Error(s"restart not registered: $err", 1)
                  case Right(msg) =>
                    CliResult.text(
                      msg +
                        s"\n  the running instance (pid ${ProcessManager.readPid().getOrElse("-")}) will be stopped by the OS-managed job;" +
                        "\n  progress: tail the log above — this command returns before the restart completes"
                    )
                }
            }
      }
    }

end RestartCommand
