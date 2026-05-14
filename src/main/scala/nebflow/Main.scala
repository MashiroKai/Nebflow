package nebflow

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import nebflow.cli.*
import nebflow.llm.Config

object Main extends IOApp:

  def run(args: List[String]): IO[ExitCode] =
    val parsed = Args.parse(args.toArray)
    parsed match
      case None =>
        IO.println("Usage: nebflow [stop | -c | -s | <query>]") *> IO.pure(ExitCode.Error)
      case Some(cliArgs) =>
        cliArgs.mode match
          case CliMode.Stop =>
            ProcessManager.stop().as(ExitCode.Success)
          case _ =>
            val configPath = Config.DefaultConfigPath.toString
            if !os.exists(Config.DefaultConfigPath) then
              IO.println(s"Config file not found: $configPath") *>
                IO.println("Create ~/.nebflow/nebflow.json with your LLM provider settings.") *>
                IO.pure(ExitCode.Error)
            else
              ProcessManager.readPid() match
                case Some(pid) if ProcessManager.isRunning(pid) =>
                  IO.println(s"nebflow is already running (pid: $pid)") *>
                    IO.println("Run 'nebflow stop' to stop it.") *>
                    IO.pure(ExitCode.Error)
                case _ =>
                  val pid = java.lang.ProcessHandle.current().pid()
                  ProcessManager.writePid(pid)
                  nebflow.gateway.GatewayMain.run
                    .guarantee(IO.blocking(ProcessManager.removePid()))
                    .as(ExitCode.Success)
    end match
  end run
end Main
