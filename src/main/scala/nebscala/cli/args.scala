package nebscala.cli

import scopt.OParser

enum CliMode:
  case Interactive, ContinueSession, SingleShot

case class Args(
  mode: CliMode = CliMode.Interactive,
  input: String = ""
)

object Args:
  private val builder = OParser.builder[Args]
  private val parser = {
    import builder.*
    OParser.sequence(
      programName("nebscala"),
      head("nebscala", "1.0.0"),
      opt[Unit]('c', "continue")
        .action((_, c) => c.copy(mode = CliMode.ContinueSession))
        .text("Continue last session"),
      arg[String]("<query>")
        .optional()
        .action((q, c) => c.copy(mode = CliMode.SingleShot, input = q))
        .text("Single-shot query (non-interactive)")
    )
  }

  def parse(args: Array[String]): Option[Args] =
    OParser.parse(parser, args, Args())
