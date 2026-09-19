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
                    // C2: text mode prints the value, not pretty JSON (`config
                    // get workSchedule` answered a `{ "enabled" : false }`
                    // block where a scalar was asked for).
                    if ctx.json then CliResult.Json(value)
                    else CliResult.text(value.asString.getOrElse(value.noSpaces))
                  case Left(_) => CliResult.Error("Failed to parse config")
              case None =>
                // Whole-config dump — masked like `config show` (D6).
                val redacted = io.circe.parser.parse(configStr).map(redact).map(_.noSpaces)
                if ctx.json then CliResult.Json(io.circe.parser.parse(redacted.getOrElse(configStr)).getOrElse(Json.Null))
                else CliResult.text(redacted.getOrElse(configStr))
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
          val key = ctx.positionalArgs.headOption.orElse(ctx.args.get("key"))
          val value = ctx.positionalArgs.lift(1).orElse(ctx.args.get("value")).getOrElse("")
          if key.isEmpty || value.isEmpty then IO.pure(CliResult.Error("Key and value required"))
          else
            // Build a nested JSON from dot-separated key
            val configJson = buildNestedJson(key.get.split("\\.").toList, value)
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
                  case None      => IO.pure(CliResult.text(s"Config updated: ${key.get} = $value"))
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

  /** D6: mask credentials in a whole-config dump. The design document already
    * promised this ("`config show` = API key 脱敏", §5.2 drift table) while the
    * implementation printed the config verbatim, including provider apiKeys and
    * tokens. Values of secret-named keys become `***`; every other field is
    * passed through unchanged (structure and non-secret values preserved).
    * A targeted `config get <key>` is NOT masked — the user asked for that key
    * by name and a masked answer would be useless.
    *
    * The name test is deliberately narrower than a bare `contains("token")`:
    * `thinkingConfig.budgetTokens` / `*.maxTokens` (llm/config.scala:183) are
    * numeric QUOTA fields, not credentials, and masking them would corrupt a
    * non-secret field (the requirement is "secrets masked, non-secrets as-is").
    * Plural quota names are therefore exempt; singular `token` / `authToken` /
    * `accessToken` and any `apikey|secret|password|passwd|credential` name are
    * masked.
    */
  private[cli] def isSecretKey(key: String): Boolean =
    val k = key.toLowerCase.filter(_.isLetterOrDigit)
    val named =
      k.contains("apikey") || k.contains("secret") || k.contains("password") ||
        k.contains("passwd") || k.contains("credential")
    // "token" only when it is a singular credential-ish name, never a plural quota
    val tokenish = k.startsWith("token") || k.endsWith("token")
    named || tokenish

  private[cli] def redact(json: Json): Json =
    json.fold(
      Json.Null,
      b => Json.fromBoolean(b),
      n => Json.fromJsonNumber(n),
      s => Json.fromString(s),
      arr => Json.fromValues(arr.map(redact)),
      obj =>
        Json.fromFields(obj.toList.map { case (k, v) =>
          if isSecretKey(k) && !v.isNull then k -> (Json.fromString("***"): Json)
          else k -> redact(v)
        })
    )

  private object ConfigShow extends CliSubcommand:
    def name = "show"
    def description = "Show full configuration (credentials masked)"
    def params = Nil

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          client.command(Json.obj("type" -> "getConfig".asJson)).map { resp =>
            val configStr = resp.hcursor.downField("config").as[String].getOrElse("{}")
            CliResult.text(io.circe.parser.parse(configStr).map(redact).map(_.noSpaces).getOrElse(configStr))
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
