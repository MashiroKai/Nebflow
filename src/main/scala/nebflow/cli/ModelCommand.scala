package nebflow.cli

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.PathUtil

object ModelCommand extends CliCommand:
  def name = "model"
  def description = "Manage models"
  def subcommands = List(ModelList, ModelSet)

  def examples = List(
    "nebflow model list",
    "nebflow model set anthropic/claude-sonnet-4-6"
  )

  private object ModelList extends CliSubcommand:
    def name = "list"
    def description = "List available models"

    def params = List(
      CliParam("session", Some('s'), "Session ID (for session-specific models)", required = false)
    )

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          val sessionId = ctx.args.getOrElse("session", "")
          client
            .command(
              Json.obj(
                "type" -> "getModelOptions".asJson,
                "sessionId" -> sessionId.asJson
              )
            )
            .map { resp =>
              if ctx.json then CliResult.Json(resp)
              else
                val models = resp.hcursor.downField("models").as[List[Json]].getOrElse(Nil)
                val current = resp.hcursor.downField("current").as[Option[String]].toOption.flatten.getOrElse("default")
                val lines = models.map { m =>
                  val ref = m.hcursor.downField("ref").as[String].getOrElse("")
                  val label = m.hcursor.downField("label").as[String].getOrElse(ref)
                  if ref == current then s"  * $ref  ($label)" else s"    $ref  ($label)"
                }
                CliResult.Text(s"Current: $current" :: "Available:" :: lines)
            }

  end ModelList

  private object ModelSet extends CliSubcommand:
    def name = "set"
    def description = "Set default or session model"

    def params = List(
      CliParam("model-ref", None, "Model reference (provider/model)", required = true),
      CliParam("session", Some('s'), "Session ID (sets session-level model)", required = false)
    )

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          val modelRef = ctx.positionalArgs.headOption.getOrElse("")
          val sessionId = ctx.args.getOrElse("session", "")
          if modelRef.isEmpty then
            IO.pure(CliResult.Error("Model reference required (e.g. anthropic/claude-sonnet-4-6)"))
          else if sessionId.isEmpty then
            // Set default model via config
            client
              .command(
                Json.obj(
                  "type" -> "updateConfig".asJson,
                  "config" -> s"""{"llm":{"model":{"default":"$modelRef"}}}""".asJson
                )
              )
              .as(CliResult.text(s"Default model set to $modelRef"))
          else
            // Set session model
            client
              .command(
                Json.obj(
                  "type" -> "setSessionModel".asJson,
                  "sessionId" -> sessionId.asJson,
                  "modelRef" -> modelRef.asJson
                )
              )
              .as(CliResult.text(s"Session model set to $modelRef"))

          end if

  end ModelSet

end ModelCommand

object ThinkingCommand extends CliCommand:
  def name = "thinking"
  def description = "Control thinking mode"
  def subcommands = List(ThinkingOn, ThinkingOff, ThinkingStatus)
  def examples = List("nebflow thinking on", "nebflow thinking off")

  private object ThinkingOn extends CliSubcommand:
    def name = "on"
    def description = "Enable thinking mode"
    def params = List(CliParam("budget", Some('b'), "Budget tokens", required = false))

    def run(ctx: CliContext): IO[CliResult] =
      setThinking(ctx, enabled = true)

  private object ThinkingOff extends CliSubcommand:
    def name = "off"
    def description = "Disable thinking mode"
    def params = Nil

    def run(ctx: CliContext): IO[CliResult] =
      setThinking(ctx, enabled = false)

  private object ThinkingStatus extends CliSubcommand:
    def name = "status"
    def description = "Show thinking mode status"
    def params = Nil

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          IO.blocking {
            val configPath = PathUtil.dataRoot / "nebflow.json"
            if os.exists(configPath) then
              io.circe.parser
                .parse(os.read(configPath))
                .toOption
                .flatMap(_.hcursor.downField("thinkingConfig").as[io.circe.Json].toOption)
            else None
          }.map {
            case Some(cfg) =>
              val enabled = cfg.hcursor.downField("enabled").as[Boolean].getOrElse(true)
              val budget = cfg.hcursor.downField("budgetTokens").as[Int].getOrElse(32000)
              if ctx.json then CliResult.Json(cfg)
              else CliResult.text(s"Thinking: ${if enabled then "ON" else "OFF"} (budget: $budget tokens)")
            case None =>
              CliResult.text("Thinking: default (enabled)")
          }

  end ThinkingStatus

  private def setThinking(ctx: CliContext, enabled: Boolean): IO[CliResult] =
    ctx.client match
      case None => IO.pure(CliResult.Error("Gateway not running"))
      case Some(client) =>
        val budget = ctx.args.getOrElse("budget", "32000").toIntOption.getOrElse(32000)
        client
          .command(
            Json.obj(
              "type" -> "setThinking".asJson,
              "thinking" -> Json.obj("enabled" -> enabled.asJson, "budgetTokens" -> budget.asJson)
            )
          )
          .as(CliResult.text(s"Thinking mode ${if enabled then "enabled" else "disabled"}"))
end ThinkingCommand
