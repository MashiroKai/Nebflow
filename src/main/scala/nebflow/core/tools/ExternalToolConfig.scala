package nebflow.core.tools

import io.circe.{Decoder, Encoder, Json, JsonObject}
import io.circe.syntax.*

case class ExternalToolConfig(
  name: String,
  description: String,
  command: String,
  inputSchema: JsonObject,
  timeoutSeconds: Int = 120
)

object ExternalToolConfig:

  given Encoder[ExternalToolConfig] = Encoder.instance { cfg =>
    Json.obj(
      "name" -> cfg.name.asJson,
      "description" -> cfg.description.asJson,
      "command" -> cfg.command.asJson,
      "inputSchema" -> Json.fromJsonObject(cfg.inputSchema),
      "timeoutSeconds" -> cfg.timeoutSeconds.asJson
    )
  }

  given Decoder[ExternalToolConfig] = Decoder.instance { c =>
    for
      name <- c.downField("name").as[String]
      description <- c.downField("description").as[String]
      command <- c.downField("command").as[String]
      inputSchema <- c.downField("inputSchema").as[JsonObject]
      timeoutSeconds <- c.downField("timeoutSeconds").as[Option[Int]].map(_.getOrElse(120))
    yield ExternalToolConfig(name, description, command, inputSchema, timeoutSeconds)
  }
end ExternalToolConfig
