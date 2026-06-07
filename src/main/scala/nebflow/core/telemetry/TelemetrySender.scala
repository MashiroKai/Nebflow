package nebflow.core.telemetry

import cats.effect.IO
import io.circe.Json
import nebflow.core.NebflowLogger
import nebflow.shared.SharedBackend
import sttp.client4.*
import sttp.client4.httpclient.HttpClientSyncBackend
import sttp.model.Uri

import scala.concurrent.duration.*

/**
 * Sends telemetry events to the server over HTTPS.
 * Failures are silently swallowed — telemetry must never break the user's workflow.
 */
trait TelemetrySender:
  def send(endpoint: String, payload: Json): IO[Unit]

/**
 * HTTP-based telemetry sender using sttp.
 * Reuses the shared SyncBackend to leverage connection pooling.
 */
object HttpTelemetrySender extends TelemetrySender:
  private val logger = NebflowLogger.forName("nebflow.telemetry.sender")

  def send(endpoint: String, payload: Json): IO[Unit] =
    Uri.parse(endpoint) match
      case Left(err) =>
        IO(logger.warnSync(s"Invalid telemetry endpoint: $err"))
      case Right(uri) =>
        IO.blocking {
          val request = basicRequest
            .post(uri)
            .contentType("application/json")
            .body(payload.noSpaces)
            .readTimeout(5.seconds)
            .response(asStringAlways)
          try
            val response = request.send(SharedBackend.instance)
            if !response.code.isSuccess then logger.warnSync(s"Telemetry server returned ${response.code}")
          catch
            case e: Exception =>
              logger.warnSync(s"Telemetry send failed: ${e.getMessage}")
        }

end HttpTelemetrySender

/** No-op sender for testing or when telemetry is disabled. */
object NoOpTelemetrySender extends TelemetrySender:
  def send(endpoint: String, payload: Json): IO[Unit] = IO.unit
