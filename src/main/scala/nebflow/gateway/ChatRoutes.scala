package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.NebflowError
import nebflow.gateway.GatewayCodecs.given
import nebflow.shared.*
import nebflow.shared.given
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
          // visionfix (甲): the failure mapping needs the REQUEST (to know
          // whether it carried images), and the request only exists inside the
          // decode continuation — so the decode result is bound explicitly
          // instead of letting the outer `handleErrorWith` reach back for a
          // variable it cannot see. Decode failures take the no-request branch,
          // which reproduces the previous mapping verbatim (generic "Internal"
          // wording); no other observable behaviour changes.
          req.as[LlmRequest].attempt.flatMap {
            case Right(llmReq) =>
              handle
                .send(llmReq)
                .flatMap(resp => Ok(resp.asJson))
                .handleErrorWith(llmFailureResponse(Some(llmReq)))
            case Left(decodeErr) =>
              llmFailureResponse(None)(decodeErr)
          }
      }

    case req @ POST -> Root / "v1" / "chat" / "stream" =>
      checkAuth(req).flatMap {
        case Left(resp) => IO.pure(resp)
        case Right(_) =>
          req.as[LlmRequest].attempt.flatMap {
            case Right(llmReq) =>
              val sseStream = handle
                .sendStream(llmReq)
                .map(toSse)
                .handleErrorWith { err =>
                  fs2.Stream.emit(errorSse(err))
                }
              Ok(sseStream).handleErrorWith(llmFailureResponse(Some(llmReq)))
            case Left(decodeErr) =>
              llmFailureResponse(None)(decodeErr)
          }
      }
  }

  /** visionfix (甲): shared failure mapping for both chat faces. `llmReq` is
    * `None` only on a decode failure, where there is no request to inspect.
    * The "images could not be delivered" wording is chosen from
    * `FallbackExhaustedError.hadImage` (set at the send point in
    * `LlmInterface`) or, as a fallback, from the request body — `hadImageIn` is
    * the single predicate. */
  private def llmFailureResponse(
    llmReq: Option[LlmRequest]
  ): Throwable => IO[org.http4s.Response[IO]] = {
    case e: FallbackExhaustedError =>
      val attemptSummaries =
        e.attempts.map(a => s"${a.providerId}/${a.model}: ${a.reason.map(_.toString).getOrElse("unknown")}")
      val hadImg = e.hadImage || llmReq.exists(nabflowHadImage)
      val msg = NebflowError.toUserMessage(
        NebflowError.LlmFailed(e.getMessage, attemptSummaries, hadImage = hadImg)
      )
      BadGateway(Json.obj("error" -> msg.asJson, "attempts" -> e.attempts.asJson))
    case other =>
      val msg = NebflowError.toUserMessage(
        NebflowError.Internal(
          Option(other.getMessage).getOrElse("internalError")
        )
      )
      InternalServerError(Json.obj("error" -> msg.asJson))
  }

  /** visionfix (甲): did this REST request carry image content? Used to pick the
    * "images could not be delivered" wording instead of a generic chain failure.
    * `LlmInterface.hadImageIn` is the single predicate (public for exactly this
    * cross-package use). */
  private def nabflowHadImage(req: LlmRequest): Boolean =
    nebflow.llm.LlmInterface.hadImageIn(req.messages)

  private def toSse(chunk: StreamChunk): ServerSentEvent =
    ServerSentEvent(data = Some(chunk.asJson.noSpaces))

  private def errorSse(err: Throwable): ServerSentEvent =
    err match
      case e: FallbackExhaustedError =>
        val sanitized = e.attempts.map { a =>
          Json.obj(
            "providerId" -> a.providerId.asJson,
            "model" -> a.model.asJson,
            "reason" -> a.reason.map(_.toString).asJson
          )
        }
        ServerSentEvent(
          data = Some(
            Json
              .obj(
                "error" -> "allProvidersFailed".asJson,
                "attempts" -> sanitized.asJson
              )
              .noSpaces
          ),
          eventType = Some("error")
        )
      case _ =>
        val msg = NebflowError.toUserMessage(
          NebflowError.Internal(Option(err.getMessage).getOrElse("streamError"))
        )
        ServerSentEvent(
          data = Some(Json.obj("error" -> msg.asJson).noSpaces),
          eventType = Some("error")
        )
    end match
  end errorSse
end ChatRoutes
