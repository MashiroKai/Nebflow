package nebflow.cli

import scopt.OParser

enum CliMode:
  case Interactive, ContinueSession, SingleShot, Server, Stop

case class Args(
  mode: CliMode = CliMode.Interactive,
  input: String = ""
)

object Args:
  private val builder = OParser.builder[Args]

  private val parser =
    import builder.*
    OParser.sequence(
      programName("nebflow"),
      head("nebflow", nebflow.Version.string),
      cmd("stop")
        .action((_, c) => c.copy(mode = CliMode.Stop))
        .text("Stop the running nebflow service"),
      opt[Unit]('c', "continue")
        .action((_, c) => c.copy(mode = CliMode.ContinueSession))
        .text("Continue last session"),
      opt[Unit]('s', "server")
        .action((_, c) => c.copy(mode = CliMode.Server))
        .text("Start web server"),
      arg[String]("<query>")
        .optional()
        .action((q, c) => c.copy(mode = CliMode.SingleShot, input = q))
        .text("Single-shot query (non-interactive)")
    )

  def parse(args: Array[String]): Option[Args] =
    OParser.parse(parser, args, Args())
end Args
