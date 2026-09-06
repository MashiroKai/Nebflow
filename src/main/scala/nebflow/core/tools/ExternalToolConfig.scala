package nebflow.core.tools

import io.circe.*
import io.circe.syntax.*

/**
 * External tool definition loaded from a JSON config file.
 *
 * `layer` and `scope` describe which layer the tool was loaded from and are
 * inferred from the config file's directory — they are NOT read from user
 * config files (which never carry these fields):
 *   - layer = "global", scope = None     → ~/.nebflow/tools/ (json files)
 *   - layer = "team",   scope = Some(name) → ~/.nebflow/teams/&lt;name&gt;/tools/
 *   - layer = "flow",   scope = Some(name) → ~/.nebflow/flows/&lt;name&gt;/tools/
 * (The per-agent layer — agents/…/agents/x/tools/ scans —
 * retired 2026-09-06 with the panel capability sections.)
 */
case class ExternalToolConfig(
  name: String,
  description: String,
  command: String,
  inputSchema: JsonObject,
  timeoutSeconds: Int = 120,
  layer: String = "global",
  scope: Option[String] = None
):

  /** Copy with explicit layer/scope — used by ToolLoader when loading from a directory. */
  def withLayer(layer: String, scope: Option[String]): ExternalToolConfig =
    copy(layer = layer, scope = scope)

object ExternalToolConfig:

  given Encoder[ExternalToolConfig] = Encoder.instance { cfg =>
    val base = List(
      "name" -> cfg.name.asJson,
      "description" -> cfg.description.asJson,
      "command" -> cfg.command.asJson,
      "inputSchema" -> Json.fromJsonObject(cfg.inputSchema),
      "timeoutSeconds" -> cfg.timeoutSeconds.asJson,
      "layer" -> cfg.layer.asJson
    )
    val withScope = cfg.scope match
      case Some(s) => base :+ ("scope" -> s.asJson)
      case None => base
    Json.obj(withScope*)
  }

  given Decoder[ExternalToolConfig] = Decoder.instance { c =>
    for
      name <- c.downField("name").as[String]
      description <- c.downField("description").as[String]
      command <- c.downField("command").as[String]
      inputSchema <- c.downField("inputSchema").as[JsonObject]
      timeoutSeconds <- c.downField("timeoutSeconds").as[Option[Int]].map(_.getOrElse(120))
      // Defaults keep backward compatibility with existing configs that omit these fields;
      // ToolLoader overrides them with withLayer() based on the source directory.
      layer <- c.downField("layer").as[Option[String]].map(_.getOrElse("global"))
      scope <- c.downField("scope").as[Option[String]]
    yield ExternalToolConfig(name, description, command, inputSchema, timeoutSeconds, layer, scope)
  }
end ExternalToolConfig
