package nebflow.cli

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*

object ProviderCommand extends CliCommand:
  def name = "provider"
  def description = "Manage providers"
  def subcommands = List(ProviderList, ProviderTest)
  def examples = List("nebflow provider list", "nebflow provider test anthropic")

  private object ProviderList extends CliSubcommand:
    def name = "list"
    def description = "List configured providers"
    def params = Nil
    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          client.command(Json.obj("type" -> "getConfig".asJson)).map { resp =>
            val configStr = resp.hcursor.downField("config").as[String].getOrElse("{}")
            io.circe.parser.parse(configStr) match
              case Right(json) =>
                val providers = json.hcursor.downField("llm").downField("providers").as[Map[String, Json]].getOrElse(Map.empty)
                if ctx.json then CliResult.Json(io.circe.Json.fromFields(providers))
                else
                  val lines = providers.map { case (name, pJson) =>
                    val baseUrl = pJson.hcursor.downField("baseUrl").as[String].getOrElse("?")
                    val protocol = pJson.hcursor.downField("protocol").as[String].getOrElse("?")
                    val modelCount = pJson.hcursor.downField("models").as[List[Json]].toOption.map(_.length).getOrElse(0)
                    s"  $name ($protocol) $baseUrl [$modelCount models]"
                  }.toList
                  if lines.isEmpty then CliResult.text("No providers configured")
                  else CliResult.Text("Providers:" :: lines)
              case Left(_) => CliResult.Error("Failed to parse config")
          }

  private object ProviderTest extends CliSubcommand:
    def name = "test"
    def description = "Test provider connectivity"
    def params = List(CliParam("name", None, "Provider name", required = true))
    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          val providerName = ctx.positionalArgs.headOption.getOrElse("")
          if providerName.isEmpty then IO.pure(CliResult.Error("Provider name required"))
          else
            // Test by requesting model options — if the provider is configured, it will appear
            client.command(Json.obj("type" -> "getModelOptions".asJson, "sessionId" -> "".asJson)).map { resp =>
              val models = resp.hcursor.downField("models").as[List[Json]].getOrElse(Nil)
              val providerModels = models.filter { m =>
                m.hcursor.downField("ref").as[String].toOption.exists(_.startsWith(s"$providerName/"))
              }
              if providerModels.nonEmpty then
                CliResult.text(s"Provider '$providerName' OK — ${providerModels.length} model(s) available")
              else
                CliResult.Error(s"Provider '$providerName' not found or no models configured")
            }
