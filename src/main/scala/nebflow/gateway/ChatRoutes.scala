package nebflow.gateway

import nebflow.shared.{LlmHandle, LlmRequest, StreamChunk}
import nebflow.shared.given
import nebflow.llm.FallbackExhaustedError
import nebflow.gateway.GatewayCodecs.given

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.{HttpRoutes, ServerSentEvent}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

class ChatRoutes(handle: LlmHandle[IO]):
  def routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "v1" / "chat" =>
      req.as[LlmRequest].flatMap { llmReq =>
        handle.send(llmReq).flatMap(resp => Ok(resp.asJson))
      }.handleErrorWith {
        case e: FallbackExhaustedError =>
          BadGateway(Json.obj(
            "error"    -> "allProvidersFailed".asJson,
            "attempts" -> e.attempts.asJson,
          ))
        case other =>
          InternalServerError(Json.obj(
            "error" -> Option(other.getMessage).getOrElse("internalError").asJson,
          ))
      }

    case req @ POST -> Root / "v1" / "chat" / "stream" =>
      req.as[LlmRequest].flatMap { llmReq =>
        val sseStream = handle.sendStream(llmReq)
          .map(toSse)
          .handleErrorWith { err =>
            fs2.Stream.emit(errorSse(err))
          }
        Ok(sseStream)
      }.handleErrorWith {
        case e: FallbackExhaustedError =>
          BadGateway(Json.obj(
            "error"    -> "allProvidersFailed".asJson,
            "attempts" -> e.attempts.asJson,
          ))
        case other =>
          InternalServerError(Json.obj(
            "error" -> Option(other.getMessage).getOrElse("internalError").asJson,
          ))
      }
  }

  private def toSse(chunk: StreamChunk): ServerSentEvent =
    ServerSentEvent(data = Some(chunk.asJson.noSpaces))

  private def errorSse(err: Throwable): ServerSentEvent =
    val payload = Json.obj(
      "error" -> Option(err.getMessage).getOrElse("streamError").asJson,
    )
    err match
      case e: FallbackExhaustedError =>
        ServerSentEvent(
          data      = Some(Json.obj(
            "error"    -> "allProvidersFailed".asJson,
            "attempts" -> e.attempts.asJson,
          ).noSpaces),
          eventType = Some("error"),
        )
      case _ =>
        ServerSentEvent(data = Some(payload.noSpaces), eventType = Some("error"))
