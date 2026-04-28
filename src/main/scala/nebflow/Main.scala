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
        IO.println("Usage: nebflow [-c] [query]") *> IO.pure(ExitCode.Error)
      case Some(cliArgs) =>
        val configPath = Config.DefaultConfigPath.toString
        if !os.exists(Config.DefaultConfigPath) then
          IO.println(s"Config file not found: $configPath") *>
            IO.println("Create ~/.nebflow/nebflow.json with your LLM provider settings.") *>
            IO.pure(ExitCode.Error)
        else nebflow.gateway.GatewayMain.run.as(ExitCode.Success)
