package nebflow.cli

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.PathUtil

object ConfigCommand extends CliCommand:
  def name = "config"
  def description = "Manage configuration"
  def subcommands = List(ConfigGet, ConfigSet, ConfigShow, ConfigEdit)

  def examples = List(
    "nebflow config show",
    "nebflow config get workSchedule",
    "nebflow config set workSchedule.enabled false"
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
                    val value = k
                      .split("\\.")
                      .foldLeft(json)((j, segment) => j.hcursor.downField(segment).as[Json].getOrElse(Json.Null))
                    if ctx.json then CliResult.Json(value)
                    else CliResult.text(value.spaces2)
                  case Left(_) => CliResult.Error("Failed to parse config")
              case None =>
                if ctx.json then CliResult.Json(io.circe.parser.parse(configStr).getOrElse(Json.Null))
                else CliResult.text(configStr)
          }

  end ConfigGet

  private object ConfigSet extends CliSubcommand:
    def name = "set"
    def description = "Set a config value"

    def params = List(
      CliParam("key", None, "Config key", required = true),
      CliParam("value", None, "Config value", required = true)
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
            client
              .command(
                Json.obj(
                  "type" -> "updateConfig".asJson,
                  "config" -> configJson.spaces2.asJson
                )
              )
              .flatMap { resp =>
                // 服务端校验拒绝回 {"type":"error","message":...}——必须浮出，
                // 无条件 "Config updated" 会把 no-op 静默成成功（qa #339 打回）
                (for
                  t <- resp.hcursor.downField("type").as[String].toOption if t == "error"
                  m <- resp.hcursor.downField("message").as[String].toOption
                yield m) match
                  case Some(err) => IO.pure(CliResult.Error(s"Config update rejected: $err"))
                  case None      => IO.pure(CliResult.text(s"Config updated: $key = $value"))
              }
              .handleErrorWith(e => IO.pure(CliResult.Error(s"Config update failed: ${e.getMessage}")))

  end ConfigSet

  /** dot 路径 → 嵌套 JSON。末段路径是键名（与 ConfigGet 的全段下钻对称）。
    * qa #339 打回：旧基例返回裸值、丢末段键——`set a.b.c true` 实发
    * {"b": true} 而非 {"a":{"b":{"c":true}}}，静默写错位置。private[cli]
    * 供 ConfigCommandSpec 直测。 */
  private[cli] def buildNestedJson(path: List[String], value: String): Json =
    path match
      case Nil => Json.Null
      case last :: Nil =>
        // Value: try JSON parse (true/42/{...}), fallback to bare string
        Json.obj(last -> io.circe.parser.parse(value).getOrElse(Json.fromString(value)))
      case head :: tail =>
        Json.obj(head -> buildNestedJson(tail, value))

  private object ConfigShow extends CliSubcommand:
    def name = "show"
    def description = "Show full configuration"
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
      val configPath = PathUtil.configJsonReadPath(PathUtil.dataRoot)
      val editor = sys.env.getOrElse("EDITOR", sys.env.getOrElse("VISUAL", "vi"))
      IO.blocking {
        val pb = new ProcessBuilder((editor.split("\\s+").toList :+ configPath.toString)*)
        pb.inheritIO().start().waitFor()
      }.as(CliResult.ok)
end ConfigCommand
