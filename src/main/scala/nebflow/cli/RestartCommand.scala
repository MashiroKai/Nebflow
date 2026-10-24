package nebflow.cli

import cats.effect.{ExitCode, IO}
import nebflow.gateway.GatewayConfig
import nebflow.shared.PathUtil

/**
 * `stand restart` (phase 2 daemon plan §3.4) = hardened stop → daemon
 * start, as ONE command. The stop leg runs the full hardened face (identity
 * check, ownership gate, 8080/8097 dev-host gate, port confirmation) — a
 * refused or failed stop never starts a second instance. The start leg is
 * the same daemon spawn path as `stand start`, with the same single-line
 * confirmation and exit codes.
 *
 * Hot-restart (`--succeed` / SuccessorGate) is NOT touched by this command —
 * that is the in-place update mechanism, this is the cold stop-and-start
 * face.
 */
object RestartCommand extends CliCommand:
  def name = "restart"
  def description = "Stop the running Gateway and start it again (daemon)"
  def subcommands = List(RestartRun)
  def examples = List("stand restart", "stand restart --force")

  private object RestartRun extends CliSubcommand:
    def name = "run"
    def description = "Stop then start the Gateway (stop flags pass through)"

    def params = List(
      CliParam("force", None, "Skip the stop ownership gate (identity and dev-host gates always apply)", isFlag = true),
      CliParam("tree", None, "Stop the whole sbt-managed process chain before starting", isFlag = true)
    )

    def run(ctx: CliContext): IO[CliResult] =
      runRestart(
        ProcessManager.StopOptions(
          force = ctx.args.get("force").contains("true"),
          tree = ctx.args.get("tree").contains("true")
        )
      )

  end RestartRun

  /**
   * Orchestration core (spec-driven): `launch` injectable so the spec can
   * assert "refused stop ⇒ zero spawns" without any real process.
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

end RestartCommand
