package nebflow.cli

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*

object ConfigCommand extends CliCommand:
  def name = "config"
  def description = "Manage configuration"
  def subcommands = List(ConfigGet, ConfigSet, ConfigShow, ConfigEdit)
  def examples = List(
    "nebflow config show",
    "nebflow config get llm.model.default",
    "nebflow config set llm.model.default openai/gpt-4o",
  )

  private object ConfigGet extends CliSubcommand:
    def name = "get"
    def description = "Get a config value"
    def params = List(CliParam("key", None, "Config key (dot-separated path)", required = false))
    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          client.command(Json.obj("type" -> "getConfig".asJson)).map { resp =>
            val configStr = resp.hcursor.downField("config").as[String].getOrElse("{}")
            val key = ctx.positionalArgs.headOption.orElse(ctx.args.get("key"))
            key match
              case Some(k) =>
                // Navigate dot-separated path
                io.circe.parser.parse(configStr) match
                  case Right(json) =>
                    val value = k.split("\\.").foldLeft(json)((j, segment) =>
                      j.hcursor.downField(segment).as[Json].getOrElse(Json.Null)
                    )
                    if ctx.json then CliResult.Json(value)
                    else CliResult.text(value.spaces2)
                  case Left(_) => CliResult.Error("Failed to parse config")
              case None =>
                if ctx.json then CliResult.Json(io.circe.parser.parse(configStr).getOrElse(Json.Null))
                else CliResult.text(configStr)
          }

  private object ConfigSet extends CliSubcommand:
    def name = "set"
    def description = "Set a config value"
    def params = List(
      CliParam("key", None, "Config key", required = true),
      CliParam("value", None, "Config value", required = true),
    )
    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          val key = ctx.positionalArgs.headOption.getOrElse("")
          val value = ctx.positionalArgs.lift(1).orElse(ctx.args.get("value")).getOrElse("")
          if key.isEmpty || value.isEmpty then IO.pure(CliResult.Error("Key and value required"))
          else
            // Build a nested JSON from dot-separated key
            val configJson = buildNestedJson(key.split("\\.").toList, value)
            client.command(Json.obj(
              "type" -> "updateConfig".asJson,
              "config" -> configJson.spaces2.asJson,
            )).as(CliResult.text(s"Config updated: $key = $value"))

    private def buildNestedJson(path: List[String], value: String): Json =
      path match
        case Nil => Json.Null
        case last :: Nil =>
          // Try to parse as JSON, fallback to string
          io.circe.parser.parse(value).getOrElse(Json.fromString(value))
        case head :: tail =>
          Json.obj(head -> buildNestedJson(tail, value))

  private object ConfigShow extends CliSubcommand:
    def name = "show"
    def description = "Show full configuration (API keys redacted)"
    def params = Nil
    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          client.command(Json.obj("type" -> "getConfig".asJson)).map { resp =>
            val configStr = resp.hcursor.downField("config").as[String].getOrElse("{}")
            CliResult.text(configStr)
          }

  private object ConfigEdit extends CliSubcommand:
    def name = "edit"
    def description = "Open config in $EDITOR"
    def params = Nil
    def run(ctx: CliContext): IO[CliResult] =
      val configPath = os.home / ".nebflow" / "nebflow.json"
      val editor = sys.env.getOrElse("EDITOR", sys.env.getOrElse("VISUAL", "vi"))
      IO.blocking {
        val pb = new ProcessBuilder((editor.split("\\s+").toList :+ configPath.toString)*)
        pb.inheritIO().start().waitFor()
      }.as(CliResult.ok)
