package nebflow.gateway

import cats.effect.{IO, Ref}
import io.circe.Json
import io.circe.syntax.*
import nebflow.neblink.PortalHostFaces
import nebflow.shared.NebflowLogger
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

/**
 * The AuthAdmin face (phase 3b): break-glass console-ticket minting, gateway
 * token rotation, and console session enumeration/revocation.
 *
 * Auth = `GatewayAuth.Rails.BearerFileOnly` — ONLY the file-token Bearer is
 * accepted (R1-1 renewal-chain fix). Session cookies and PATs are refused
 * here even when individually valid: a relay-mint session must never reach a
 * face that mints `local-ticket`s or rotates the token, otherwise a revoked
 * host could renew its own credentials forever. (The legacy cookie and token
 * query rails this face always refused were retired outright in stage D.)
 * Break-glass and CLI are local-machine semantics (v3 :165 is a CLI
 * command); GatewayClient carries the Bearer header on every request, so the
 * CLI needs no changes.
 *
 * Endpoints:
 *  - `POST /api/auth/console-ticket`  => `{"ticket","expiresInSec"}` (120s
 *    one-shot, origin=local-ticket). Never echoes the file token.
 *  - `POST /api/auth/token/rotate`    => `{"ok":true}` — the new token is NOT
 *    echoed (zero-echo discipline): the CLI re-reads auth.json locally.
 *  - `GET  /api/auth/sessions`        => `{"sessions":[masked views]}`.
 *  - `POST /api/auth/sessions/revoke` => `{"revoked":n}`; body
 *    `{"all":true}` or `{"id":"<masked-id prefix>"}`.
 */
object AuthAdminRoutes:

  def routes(
    tokenRef: Ref[IO, String],
    sessions: GatewaySessionStore,
    faces: PortalHostFaces,
    logger: NebflowLogger
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case req @ POST -> Root / "auth" / "console-ticket" =>
        authorized(req, tokenRef, sessions).flatMap {
          case false => IO.pure(forbidden)
          case true =>
            faces.consoleTicketFace match
              case None =>
                IO.pure(jsonError(Status.ServiceUnavailable, "console ticket service is not assembled"))
              case Some(face) =>
                face.mintLocalTicket.flatMap {
                  case Right(issued) =>
                    // Zero-echo discipline: the ticket is the payload; the
                    // long-lived file token is never returned or logged.
                    logger.info(s"break-glass console ticket minted (ttl=${issued.expiresInSec}s, origin=local-ticket)")
                    Ok(Json.obj("ticket" -> issued.token.asJson, "expiresInSec" -> issued.expiresInSec.asJson))
                  case Left("rate_limited") =>
                    IO.pure(jsonError(Status.TooManyRequests, "rate_limited"))
                  case Left(other) =>
                    IO.pure(jsonError(Status.ServiceUnavailable, other))
                }
        }

      case req @ POST -> Root / "auth" / "token" / "rotate" =>
        authorized(req, tokenRef, sessions).flatMap {
          case false => IO.pure(forbidden)
          case true =>
            tokenRef.get.flatMap(current => Auth.rotateToken(current, tokenRef)).flatMap { _ =>
              logger.info("gateway token rotated (old value invalidated in-memory; auth.json rewritten atomically)")
              Ok(Json.obj("ok" -> true.asJson))
            }
        }

      case req @ GET -> Root / "auth" / "sessions" =>
        authorized(req, tokenRef, sessions).flatMap {
          case false => IO.pure(forbidden)
          case true =>
            sessions.list.flatMap { list =>
              Ok(
                Json.obj(
                  "sessions" -> list
                    .map(v =>
                      Json.obj(
                        "id" -> v.maskedId.asJson,
                        "accountSub" -> v.accountSub.asJson,
                        "origin" -> v.origin.asJson,
                        "boundNetworkId" -> v.boundNetworkId.asJson,
                        "createdAt" -> v.createdAt.asJson,
                        "absoluteExpiresAt" -> v.absoluteExpiresAt.asJson,
                        "lastSeenAt" -> v.lastSeenAt.asJson,
                        "slidingExpiresAt" -> v.slidingExpiresAt.asJson
                      )
                    )
                    .asJson
                )
              )
            }
        }

      case req @ POST -> Root / "auth" / "sessions" / "revoke" =>
        authorized(req, tokenRef, sessions).flatMap {
          case false => IO.pure(forbidden)
          case true =>
            req.as[Json].attempt.flatMap {
              case Left(_) => IO.pure(jsonError(Status.BadRequest, "request body must be a JSON object"))
              case Right(body) =>
                val all = body.hcursor.downField("all").as[Boolean].toOption.getOrElse(false)
                val idPrefix = body.hcursor.downField("id").as[String].toOption.getOrElse("").trim
                val revoke: IO[Int] =
                  if all then sessions.revokeAll()
                  else if idPrefix.nonEmpty then sessions.revokeByMaskedId(idPrefix)
                  else IO.raiseError(new IllegalArgumentException("either \"all\":true or a non-empty \"id\" prefix is required"))
                revoke.attempt.flatMap {
                  case Left(_) =>
                    IO.pure(jsonError(Status.BadRequest, "either \"all\":true or a non-empty \"id\" prefix is required"))
                  case Right(n) =>
                    logger.info(s"console sessions revoked (count=$n${if all then ", scope=all" else s", scope=prefix:$idPrefix"})")
                    Ok(Json.obj("revoked" -> n.asJson))
                }
            }
        }

  /** The single AuthAdmin verdict: file-token Bearer, nothing else (R1-1). */
  private def authorized(req: Request[IO], tokenRef: Ref[IO, String], sessions: GatewaySessionStore): IO[Boolean] =
    tokenRef.get.flatMap(t => GatewayAuth.authenticate(req, t, sessions, GatewayAuth.Rails.BearerFileOnly)).map(_.isDefined)

  /** Same body shape `withAuth` answers (no enumeration oracle between faces). */
  private def forbidden: Response[IO] =
    Response[IO](Status.Forbidden).withEntity(Json.obj("error" -> "Unauthorized".asJson))

  private def jsonError(status: Status, message: String): Response[IO] =
    Response[IO](status).withEntity(Json.obj("error" -> message.asJson))

end AuthAdminRoutes
