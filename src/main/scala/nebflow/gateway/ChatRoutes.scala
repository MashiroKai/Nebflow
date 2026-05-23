package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.NebflowError
import nebflow.gateway.GatewayCodecs.given
import nebflow.llm.FallbackExhaustedError
import nebflow.shared.given
import nebflow.shared.{LlmHandle, LlmRequest, StreamChunk}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.headers.Authorization
import org.http4s.{HttpRoutes, ServerSentEvent}

class ChatRoutes(handle: LlmHandle[IO], token: String):

  private def checkAuth(req: org.http4s.Request[IO]): IO[Either[org.http4s.Response[IO], Unit]] =
    IO.delay {
      req.headers.get[Authorization] match
        case Some(Authorization(org.http4s.Credentials.Token(org.http4s.AuthScheme.Bearer, t))) =>
          if Auth.validateToken(t, token) then Right(())
          else
            Left(
              org.http4s
                .Response[IO](status = org.http4s.Status.Unauthorized)
                .withEntity(Json.obj("error" -> "Invalid token".asJson))
            )
        case _ =>
          Left(
            org.http4s
              .Response[IO](status = org.http4s.Status.Unauthorized)
              .withEntity(Json.obj("error" -> "Missing Authorization header".asJson))
          )
    }

  def routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "v1" / "chat" =>
      checkAuth(req).flatMap {
        case Left(resp) => IO.pure(resp)
        case Right(_) =>
          req
            .as[LlmRequest]
            .flatMap { llmReq =>
              handle.send(llmReq).flatMap(resp => Ok(resp.asJson))
            }
            .handleErrorWith {
              case e: FallbackExhaustedError =>
                val attemptSummaries =
                  e.attempts.map(a => s"${a.providerId}/${a.model}: ${a.reason.map(_.toString).getOrElse("unknown")}")
                val msg = NebflowError.toUserMessage(NebflowError.LlmFailed(e.getMessage, attemptSummaries))
                BadGateway(Json.obj("error" -> msg.asJson, "attempts" -> e.attempts.asJson))
              case other =>
                val msg = NebflowError.toUserMessage(
                  NebflowError.Internal(
                    Option(other.getMessage).getOrElse("internalError")
                  )
                )
                InternalServerError(Json.obj("error" -> msg.asJson))
            }
      }

    case req @ POST -> Root / "v1" / "chat" / "stream" =>
      checkAuth(req).flatMap {
        case Left(resp) => IO.pure(resp)
        case Right(_) =>
          req
            .as[LlmRequest]
            .flatMap { llmReq =>
              val sseStream = handle
                .sendStream(llmReq)
                .map(toSse)
                .handleErrorWith { err =>
                  fs2.Stream.emit(errorSse(err))
                }
              Ok(sseStream)
            }
            .handleErrorWith {
              case e: FallbackExhaustedError =>
                val attemptSummaries =
                  e.attempts.map(a => s"${a.providerId}/${a.model}: ${a.reason.map(_.toString).getOrElse("unknown")}")
                val msg = NebflowError.toUserMessage(NebflowError.LlmFailed(e.getMessage, attemptSummaries))
                BadGateway(Json.obj("error" -> msg.asJson, "attempts" -> e.attempts.asJson))
              case other =>
                val msg = NebflowError.toUserMessage(
                  NebflowError.Internal(
                    Option(other.getMessage).getOrElse("internalError")
                  )
                )
                InternalServerError(Json.obj("error" -> msg.asJson))
            }
      }
  }

  private def toSse(chunk: StreamChunk): ServerSentEvent =
    ServerSentEvent(data = Some(chunk.asJson.noSpaces))

  private def errorSse(err: Throwable): ServerSentEvent =
    val payload = Json.obj(
      "error" -> Option(err.getMessage).getOrElse("streamError").asJson
    )
    err match
      case e: FallbackExhaustedError =>
        ServerSentEvent(
          data = Some(
            Json
              .obj(
                "error" -> "allProvidersFailed".asJson,
                "attempts" -> e.attempts.asJson
              )
              .noSpaces
          ),
          eventType = Some("error")
        )
      case _ =>
        ServerSentEvent(data = Some(payload.noSpaces), eventType = Some("error"))
    end match
  end errorSse
end ChatRoutes
