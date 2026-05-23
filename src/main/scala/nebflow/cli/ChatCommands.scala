package nebflow.cli

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*

object ChatCommand extends CliCommand:
  def name = "chat"
  def description = "Interactive or single-shot chat"
  def subcommands = List(ChatSend, ChatRepl)
  def examples = List(
    "nebflow chat \"what does this project do?\"",
    "nebflow chat --session abc-123 \"continue\"",
    "nebflow chat"
  )

  private object ChatSend extends CliSubcommand:
    def name = "send"
    def description = "Send a single message"
    def params = List(
      CliParam("session", Some('s'), "Session ID to use", required = false),
      CliParam("continue", None, "Continue recent session", isFlag = true),
    )
    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running. Start with 'nebflow start'"))
        case Some(client) =>
          val query = ctx.positionalArgs.headOption.getOrElse("")
          if query.isEmpty then IO.pure(CliResult.Error("No query provided"))
          else
            val sessionId = ctx.args.get("session").getOrElse("")
            client.command(Json.obj(
              "type" -> "command".asJson,
              "command" -> (if sessionId.nonEmpty then "switchSession" else "createSession").asJson,
              "sessionId" -> sessionId.asJson,
            )).flatMap { _ =>
              // Send the user message via the generic command endpoint
              client.command(Json.obj(
                "type" -> "userMessage".asJson,
                "content" -> query.asJson,
                "sessionId" -> sessionId.asJson,
              )).map { resp =>
                CliResult.Json(resp)
              }
            }

  private object ChatRepl extends CliSubcommand:
    def name = "repl"
    def description = "Enter interactive REPL mode"
    def params = List(
      CliParam("session", Some('s'), "Session ID to use", required = false),
    )
    def run(ctx: CliContext): IO[CliResult] =
      // REPL mode is handled by the existing NebflowUI
      IO.pure(CliResult.Error("REPL mode should be invoked as 'nebflow chat' without subcommands"))

object AskCommand extends CliCommand:
  def name = "ask"
  def description = "Single question (no session)"
  def subcommands = List(AskRun)

  def examples = List("nebflow ask \"what is 2+2?\"")

  private object AskRun extends CliSubcommand:
    def name = "ask"
    def description = "Ask a question"
    def params = List(
      CliParam("session", Some('s'), "Session ID (required for ask)", required = true),
    )
    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          val question = ctx.positionalArgs.headOption.getOrElse("")
          val sessionId = ctx.args.get("session").getOrElse("")
          if question.isEmpty then IO.pure(CliResult.Error("No question provided"))
          else if sessionId.isEmpty then IO.pure(CliResult.Error("Session ID required (--session)"))
          else
            client.command(Json.obj(
              "type" -> "ask".asJson,
              "question" -> question.asJson,
              "sessionId" -> sessionId.asJson,
            )).map(resp => CliResult.Json(resp))

object InterruptCommand extends CliCommand:
  def name = "interrupt"
  def description = "Interrupt current response"
  def subcommands = List(InterruptRun)

  def examples = List("nebflow interrupt --session abc-123")

  private object InterruptRun extends CliSubcommand:
    def name = "run"
    def description = "Interrupt"
    def params = List(
      CliParam("session", Some('s'), "Session ID", required = true),
    )
    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          val sessionId = ctx.args.get("session").getOrElse("")
          if sessionId.isEmpty then IO.pure(CliResult.Error("Session ID required"))
          else
            client.command(Json.obj(
              "type" -> "interrupt".asJson,
              "sessionId" -> sessionId.asJson,
            )).as(CliResult.ok)
