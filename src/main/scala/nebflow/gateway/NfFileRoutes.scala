/* 从 WebSocketRoutes 迁出(行为保持重构,2026-09-24)。 */
package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.gateway.NfFilePolicy.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.http4s.{Charset, HttpRoutes, MediaType, Response, Status, StaticFile}

object NfFileRoutes:

  /**
   * Plain-text response builder.
   *
   * Deliberately NOT `Response.withEntity(String)` / the dsl generators:
   * this file imports `org.http4s.circe.CirceEntityCodec.*`, whose
   * `circeEntityEncoder[String]` (circe has an `Encoder[String]`) takes
   * precedence over http4s' own text encoder, so `withEntity("prose")` emits
   * a JSON-quoted string (`"prose"`). That is invisible to a status-only
   * assertion but wrong for a diagnostic body — the reason text is meant to
   * be read by a human and grepped by a spec.
   */
  private def nfText(status: Status, body: String): Response[IO] =
    Response[IO](status)
      .putHeaders(`Content-Type`(MediaType.text.plain, Charset.`UTF-8`))
      .withBodyStream(fs2.Stream.emit(body).through(fs2.text.utf8.encode))

  /**
   * Render a refusal. The `reason` rides in both the body and a header so
   * clients (and specs) can branch without parsing prose.
   */
  private def nfDenied(resp: NfVerdict.Denied): Response[IO] =
    nfText(resp.status, s"${resp.reason}: ${resp.message}")
      .putHeaders(org.http4s.Header.Raw(org.typelevel.ci.CIString("X-Nf-Reason"), resp.reason))

  /**
   * Serves whitelisted local files from disk for card iframes
   * (GET /api/nf-file?path=xxx&ticket=xxx). Supports Range requests for
   * video seeking. Standalone (zero class deps) so it is directly
   * unit-testable (NfFileRoutesSpec); composed ahead of the instance
   * routes like uploadsRoutes.
   *
   * 2026-09-11 (C batch, R5 = ticket-only): the global gateway token leg is
   * GONE — a permanent, path-agnostic credential is exactly what this batch
   * removes. A request must carry a ticket minted by `POST /api/nf-ticket`
   * for this precise realpath. Missing ticket → 401; unknown/expired/
   * mismatched → 403 with `reason`. The route body has no `handleErrorWith`
   * (it used to be three total functions), so the new resolution step is an
   * explicit `IO.blocking` + in-function try — never a throwing lambda.
   */
  def nfFileRoutes(
    token: String,
    store: NfTicketStore,
    policy: NfPathPolicy = NfPathPolicy.memoized()
  ): HttpRoutes[IO] = HttpRoutes.of[IO] { case req @ GET -> Root / "api" / "nf-file" =>
    val rawPath = req.params.get("path").getOrElse("")
    val ticket = req.params.get("ticket").getOrElse("")
    if rawPath.isEmpty then BadRequest("Missing 'path' parameter")
    else if ticket.isEmpty then
      // Not the DSL `Unauthorized`: that constructor demands a
      // WWW-Authenticate challenge, and this route has no auth scheme to
      // advertise (the ticket is an opaque bearer value, not Basic/Digest).
      IO.pure(nfText(Status.Unauthorized, "Missing 'ticket' parameter"))
    else
      nfFileVerdictTolerant(rawPath, policy).flatMap {
        case denied: NfVerdict.Denied => IO.pure(nfDenied(denied))
        case NfVerdict.Allowed(real, _) =>
          store.verifyAndConsume(ticket, real.toString).flatMap {
            case Left(reason) =>
              IO.pure(nfDenied(NfVerdict.Denied(Status.Forbidden, reason, "ticket rejected")))
            case Right(_) =>
              StaticFile
                .fromPath(fs2.io.file.Path(real.toString), Some(req))
                .getOrElseF(NotFound())
          }
      }
    end if
  }

  /**
   * C2-2: `POST /api/nf-ticket` — mint short-lived, per-path read tickets.
   *
   * Body `{sessionId, paths[]}`. Every path goes through the SAME verdict as
   * the read endpoint (C1-5), so an unreadable path can never be ticketed;
   * rejections come back in `rejected[]` with their `reason` instead of
   * failing the whole batch (the frontend degrades per path — §4.3 F4).
   *
   * Auth: the ordinary gateway token through `extractToken` (cookie first,
   * Bearer second, `?token=` last). The frontend sends no explicit
   * credential — the cookie rides along on the same-origin fetch.
   */
  def nfTicketRoutes(
    token: String,
    store: NfTicketStore,
    policy: NfPathPolicy = NfPathPolicy.memoized()
  ): HttpRoutes[IO] = HttpRoutes.of[IO] { case req @ POST -> Root / "api" / "nf-ticket" =>
    if !Auth.validateToken(WebSocketRoutes.extractToken(req), token) then Forbidden("Invalid token")
    else
      req.as[Json].attempt.flatMap {
        case Left(e) =>
          BadRequest(s"malformed ticket request body: ${e.getMessage}")
        case Right(body) =>
          val sessionId = body.hcursor.downField("sessionId").as[String].getOrElse("")
          val paths = body.hcursor
            .downField("paths")
            .as[List[String]]
            .getOrElse(Nil)
            .filter(_.nonEmpty)
            .distinct
          // Re-read `nfFile.ticketTtlSeconds` (throttled + mtime-gated) so a
          // config edit takes effect without a restart (R3).
          store.refreshTtlFromConfig() *>
            paths
              .traverse { p =>
                nfFileVerdictTolerant(p, policy).flatMap {
                  case NfVerdict.Allowed(real, _) =>
                    store.issue(sessionId, real.toString).map { issued =>
                      Right(
                        p,
                        Json.obj(
                          "t" -> issued.token.asJson,
                          "exp" -> issued.expiresAt.asJson,
                          "uses" -> issued.remaining.asJson
                        )
                      )
                    }
                  case denied: NfVerdict.Denied =>
                    IO.pure(Left(p, Json.obj("path" -> p.asJson, "reason" -> denied.reason.asJson)))
                }
              }
              .map { results =>
                val tickets = results.collect { case Right((p, j)) => p -> j }
                val rejected = results.collect { case Left((_, j)) => j }
                Json.obj(
                  "tickets" -> Json.obj(tickets*),
                  "rejected" -> Json.arr(rejected*)
                )
              }
              .flatMap(Ok(_))
      }
  }

  /**
   * R9 = O-A: `GET /api/nf-authcheck` — the cookie-reachability probe target.
   *
   * Replaces the old "bare `/api/nf-file` → 400 = cookie works" signal, which
   * the ticket leg turns into 401 (a status that overlaps the genuine
   * failure case, so the old probe could no longer distinguish anything).
   * This route touches ZERO disk and has no other side effect: 204 when the
   * presented credential validates (cookie / Bearer / `?token=` all through
   * `extractToken`), 403 otherwise.
   */
  def nfAuthcheckRoutes(token: String): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "nf-authcheck" =>
      if Auth.validateToken(WebSocketRoutes.extractToken(req), token) then NoContent()
      else Forbidden("Invalid token")
  }

end NfFileRoutes
