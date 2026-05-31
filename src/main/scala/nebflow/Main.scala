package nebflow

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import nebflow.cli.*
import nebflow.core.PathUtil
import nebflow.gateway.GatewayConfig
import nebflow.llm.Config

import scala.util.Try

object Main extends IOApp:

  def run(args: List[String]): IO[ExitCode] =
    // Phase 1: Parse --home and --port global flags before any dispatching
    val (homeOpt, portOpt, remaining) = parseGlobalFlags(args)

    homeOpt.foreach { h =>
      PathUtil.setDataRoot(os.Path(expandHome(h), os.pwd))
    }
    portOpt.foreach { p =>
      GatewayConfig.setPort(p)
    }

    // Phase 2: Command dispatching
    val startModes = Set("start", "-s", "--server")
    val stopModes = Set("stop")
    // Filter out display flags for command matching
    val cmdArgs = remaining.filterNot(a => a == "--json" || a == "--quiet")

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
        CliRouter.run(remaining)

    end match

  end run

  /** Parse --home and --port from args, returning (homeOpt, portOpt, remainingArgs). */
  private def parseGlobalFlags(args: List[String]): (Option[String], Option[Int], List[String]) =
    var home: Option[String] = None
    var port: Option[Int] = None
    val remaining = List.newBuilder[String]
    var i = 0
    while i < args.length do
      args(i) match
        case "--home" if i + 1 < args.length =>
          home = Some(args(i + 1))
          i += 2
        case "--port" if i + 1 < args.length =>
          port = Try(args(i + 1).toInt).toOption
          i += 2
        case other =>
          remaining += other
          i += 1
    (home, port, remaining.result())

  /** Expand ~ to user home directory. */
  private def expandHome(path: String): String =
    if path.startsWith("~/") then sys.props("user.home") + path.substring(1)
    else if path == "~" then sys.props("user.home")
    else path

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
