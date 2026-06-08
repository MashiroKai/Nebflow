package nebflow.core.telemetry

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}

/**
 * A single telemetry event.
 *
 * Only contains structural metadata — never user content (no prompts, code, or file names).
 */
case class TelemetryEvent(
  event: String,
  timestamp: Long,
  properties: JsonObject = JsonObject.empty
)

object TelemetryEvent:

  given Encoder[TelemetryEvent] = deriveEncoder
  given Decoder[TelemetryEvent] = deriveDecoder

  /** Encode a batch of events as the CloudBase API request body. */
  def encodeBatch(
    events: List[TelemetryEvent],
    clientId: String,
    appVersion: String,
    os: String
  ): Json =
    Json.obj(
      "action" -> "events".asJson,
      "client_id" -> clientId.asJson,
      "app_version" -> appVersion.asJson,
      "os" -> os.asJson,
      "events" -> events.map(_.asJson).asJson
    )
end TelemetryEvent
