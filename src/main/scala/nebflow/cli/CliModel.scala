package nebflow.cli

import cats.effect.IO
import io.circe.Json

// ===== Command definition traits =====

trait CliCommand:
  /** Command name, e.g. "session" or "model" */
  def name: String
  /** Subcommands */
  def subcommands: List[CliSubcommand]
  /** Short description for --help */
  def description: String
  /** One-line examples */
  def examples: List[String]

trait CliSubcommand:
  /** Subcommand name, e.g. "list" */
  def name: String
  /** Short description */
  def description: String
  /** Parameter definitions */
  def params: List[CliParam]
  /** Execute the command */
  def run(ctx: CliContext): IO[CliResult]

case class CliParam(
  name: String,
  short: Option[Char] = None,
  description: String,
  required: Boolean = false,
  default: Option[String] = None,
  isFlag: Boolean = false
)

// ===== Context & Results =====

case class CliContext(
  args: Map[String, String],   // parsed named args
  positionalArgs: List[String], // positional args (e.g. session id, query text)
  json: Boolean,                // --json flag
  quiet: Boolean,               // --quiet flag
  client: Option[GatewayClient], // HTTP client (None for offline)
  configDir: os.Path             // ~/.nebflow/
)

object CliContext:
  def offline(args: Map[String, String] = Map.empty, positionalArgs: List[String] = Nil): CliContext =
    CliContext(args, positionalArgs, json = false, quiet = false, client = None, os.home / ".nebflow")

sealed trait CliResult

object CliResult:
  case class Text(lines: List[String]) extends CliResult
  case class Json(json: io.circe.Json) extends CliResult
  case class Error(message: String, exitCode: Int = 1) extends CliResult
  case object Success extends CliResult

  def text(lines: String*): Text = Text(lines.toList)
  def error(msg: String): Error = Error(msg)
  def ok: Success.type = Success
end CliResult
