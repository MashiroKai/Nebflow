package nebflow.gateway

import cats.effect.{IO, Ref}
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

class ChatRoutes(
  handle: LlmHandle[IO],
  /**
   * Phase 3b: the LIVE file token (Ref, not a boot snapshot) — a rotation
   * swaps it and the old Bearer dies on this running gateway immediately.
   */
  tokenRef: Ref[IO, String],
  /** Phase 3b: the console session store (the new session rail). */
  sessions: GatewaySessionStore = GatewaySessionStore.unsafeCreate()
):

  /**
   * Phase 3b: the unified GatewayAuth criterion under `OpenAiCompat` —
   * Bearer(file) + the NEW session rail; no param/legacy-cookie/PAT (no
   * surface growth). The 401 semantics are UNCHANGED, including the
   * body split: a request that presented an `Authorization: Bearer …`
   * header gets "Invalid token"; anything else gets "Missing Authorization
   * header" (the OpenAI-compat face's deliberate shape). The header-present
   * probe keeps that split exact even though the criterion now also accepts
   * session cookies.
   */
  private def checkAuth(req: org.http4s.Request[IO]): IO[Either[org.http4s.Response[IO], Unit]] =
    tokenRef.get
      .flatMap(t => GatewayAuth.authenticate(req, t, sessions, GatewayAuth.Rails.OpenAiCompat))
      .map {
        case Some(_) => Right(())
        case None =>
          val presentedBearer = req.headers.get[Authorization].exists {
            case Authorization(org.http4s.Credentials.Token(org.http4s.AuthScheme.Bearer, _)) => true
            case _ => false
          }
          Left(
            org.http4s
              .Response[IO](status = org.http4s.Status.Unauthorized)
              .withEntity(
                if presentedBearer then Json.obj("error" -> "Invalid token".asJson)
                else Json.obj("error" -> "Missing Authorization header".asJson)
              )
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

    // OpenAI wire-format alias (batch B2, "立项（最小形态）" — author ruling
    // 2026-09-24). ONE extra path on the SAME routes value, behind the SAME
    // `checkAuth` gate: no second auth face, no `/v1/models`, no SSE semantics,
    // no new dependency. `OpenAiChatShim` owns the whole line-format
    // conversion; this case only does auth → parse → convert → send → convert,
    // so the two in-tree paths above keep their existing semantics.
    //
    // The body is read as RAW TEXT (`bodyText.compile.string` — the same idiom
    // as RestApiRoutes.scala:2463 / WebSocketRoutes.scala:5704) and parsed
    // explicitly with `io.circe.parser`: this face must own its parse-failure
    // path (the OpenAI error envelope), and `req.as[Json]` / `req.as[String]`
    // resolve through the ambient implicit EntityEncoders (with this file's
    // circe imports they are the JSON-aware decoders), so neither is a stable
    // carrier for a wire-format contract. Raw stream → String removes that
    // coupling.
    case req @ POST -> Root / "v1" / "chat" / "completions" =>
      checkAuth(req).flatMap {
        case Left(resp) => IO.pure(resp)
        case Right(_) =>
          req.bodyText.compile.string.attempt.flatMap {
            case Left(_) => openAiRejected(OpenAiChatShim.malformedBody)
            case Right(raw) =>
              io.circe.parser.parse(raw) match
                case Left(_) => openAiRejected(OpenAiChatShim.malformedBody)
                case Right(body) =>
                  OpenAiChatShim.toLlmRequest(body) match
                    case Left(rejected) => openAiRejected(rejected)
                    case Right(llmReq) =>
                      handle
                        .send(llmReq)
                        .flatMap(resp => Ok(OpenAiChatShim.toOpenAiResponse(resp)))
                        .handleErrorWith(llmFailureResponse(Some(llmReq)))
          }
      }
  }

  /**
   * OpenAI-shaped refusal: the same 4xx status the shim declares plus the
   * OpenAI error envelope (`type`/`param`/`code`), so a caller that parses the
   * OpenAI error shape gets usable feedback instead of a bare status.
   */
  private def openAiRejected(
    rejected: OpenAiChatShim.Rejected
  ): IO[org.http4s.Response[IO]] =
    IO.pure(
      org.http4s
        .Response[IO](status = org.http4s.Status.fromInt(rejected.status).getOrElse(org.http4s.Status.BadRequest))
        .withEntity(OpenAiChatShim.errorEnvelope(rejected))
    )

  /**
   * visionfix (甲): shared failure mapping for both chat faces. `llmReq` is
   * `None` only on a decode failure, where there is no request to inspect.
   * The "images could not be delivered" wording is chosen from
   * `FallbackExhaustedError.hadImage` (set at the send point in
   * `LlmInterface`) or, as a fallback, from the request body — `hadImageIn` is
   * the single predicate.
   */
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

  /**
   * visionfix (甲): did this REST request carry image content? Used to pick the
   * "images could not be delivered" wording instead of a generic chain failure.
   * `LlmInterface.hadImageIn` is the single predicate (public for exactly this
   * cross-package use).
   */
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
