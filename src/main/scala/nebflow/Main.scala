package nebflow

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import nebflow.cli.*
import nebflow.llm.Config

object Main extends IOApp:

  def run(args: List[String]): IO[ExitCode] =
    // Special case: "start" or no subcommand that maps to start
    val startModes = Set("start", "-s", "--server")
    val stopModes = Set("stop")
    // Filter out global flags for the check
    val cmdArgs = args.filterNot(a => a == "--json" || a == "--quiet")

    cmdArgs match
      case Nil =>
        // No args → start Gateway (backward compatible)
        startGateway()
      case head :: _ if stopModes.contains(head) =>
        // stop command
        ProcessManager.stop().as(ExitCode.Success)
      case head :: rest if startModes.contains(head) =>
        // start command — launch Gateway
        startGateway()
      case "help" :: _ =>
        CliRouter.run(Nil)
      case _ =>
        // All other commands go through CLI router
        CliRouter.run(args)

    end match

  end run

  private def startGateway(): IO[ExitCode] =
    // Ensure config directory exists (first-run setup)
    val configPath = Config.DefaultConfigPath
    if !os.exists(configPath) then os.write.over(configPath, "{}", createFolders = true)

    ProcessManager.readPid() match
      case Some(pid) if ProcessManager.isRunning(pid) =>
        IO.println(s"nebflow is already running (pid: $pid)") *>
          IO.println("Run 'nebflow stop' to stop it.") *>
          IO.pure(ExitCode.Error)
      case _ =>
        val pid = java.lang.ProcessHandle.current().pid()
        ProcessManager.writePid(pid)
        // JVM shutdown hook as backup — ensures PID file cleanup even on SIGINT/SIGTERM
        Runtime.getRuntime.addShutdownHook(new Thread(() => ProcessManager.removePid()))
        nebflow.gateway.GatewayMain.run
          .guarantee(IO.blocking(ProcessManager.removePid()))
          .as(ExitCode.Success)
  end startGateway
end Main
