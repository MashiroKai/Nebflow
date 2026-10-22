package nebflow.gateway

import cats.effect.IO
import nebflow.neblink.PortalHostFaces
import nebflow.shared.NebflowLogger
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.Location

/**
 * `GET /?ticket=<one-time console ticket>` — the redemption face that trades a
 * short-lived portal/break-glass ticket for an HttpOnly session cookie
 * (portal-first v3 criterion 4 / plan §3).
 *
 * Mount: composed AHEAD of the gzipped static tree on the `"/"` slot
 * (GatewayMain). The match predicate is "the query carries a non-empty
 * `ticket`" — a plain `GET /` does NOT match this route, so it falls through
 * `<+>` to the static tree unchanged (per-request cost of the guard is one
 * Map lookup).
 *
 * Processing chain (order fixed, plan §3.2):
 *   1. face absent (no tunnel assembled / test shape) => readable 503 page;
 *   2. `verifyAndConsume` — atomic one-shot (the store deletes the entry in
 *      the same transaction as the successful verification, so a second use
 *      of the same ticket always lands on `invalid-ticket`, criterion 3);
 *   3. origin branch: a `relay-mint` ticket is refused when the binding is
 *      unbound (the mirror of the bridge's unbound mint refusal); a
 *      `local-ticket` is EXEMPT from that gate — it is the post-revocation
 *      break-glass escape (v3 :290), and its minting authority is the
 *      file-token-only AuthAdmin face, so the exemption opens no renewal
 *      chain;
 *   4. session created with the ticket's origin verbatim;
 *   5. `303 See Other` to `/` + Set-Cookie (HttpOnly session cookie) — the
 *      ticket exits the URL with the redirect and never reaches browser
 *      history beyond this navigation.
 *
 * Known edges, registered and accepted (plan §3.2): a ticket consumed but the
 * session-create/response leg failing (in-memory Ref write; near-zero), and a
 * lost first 303 (the retry lands on `invalid-ticket` because the first hit
 * destroyed the ticket). Both direct the user to mint a fresh ticket — the
 * failure page says so. A same-ticket idempotent replay is deliberately NOT
 * built: it would violate criterion 3's "second use => refuse" verbatim.
 */
object ConsoleTicketRoutes:

  def ticketRoutes(
    sessions: GatewaySessionStore,
    faces: PortalHostFaces,
    networkIdNow: IO[String],
    logger: NebflowLogger
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO] { case req @ GET -> Root if req.params.get("ticket").exists(_.nonEmpty) =>
      val ticket = req.params.get("ticket").getOrElse("")
      faces.consoleTicketFace match
        case None =>
          // Tunnel not assembled (pre-assembly / test shape): a readable 503
          // result page, never a bare 500 (the browser shows this page).
          IO.pure(resultPage(Status.ServiceUnavailable, "Console unavailable",
            "The console ticket service is not assembled on this instance. Open the local URL instead."))
        case Some(face) =>
          face.verifyAndConsume(ticket).flatMap {
            case Left(reason) =>
              val (headline, detail) = reason match
                case "expired" => ("Ticket expired", "This one-time ticket's validity window elapsed. Mint a fresh ticket and try again.")
                case "invalid-ticket" => ("Ticket rejected", "The ticket is unknown, already used, or revoked. Mint a fresh ticket and try again.")
                case other => ("Ticket rejected", s"The ticket was refused ($other). Mint a fresh ticket and try again.")
              IO.pure(resultPage(Status.Forbidden, headline, detail))
            case Right(t) =>
              val isRelayMint = t.origin == GatewaySessionStore.OriginRelayMint
              def refuseUnbound: IO[Response[IO]] =
                IO.pure(resultPage(Status.Forbidden, "Binding revoked",
                  "This host's account binding was revoked, so portal-minted tickets are no longer accepted. " +
                    "Use a locally minted ticket (stand token) to reach the console."))
              (if isRelayMint then face.isUnbound else IO.pure(false)).flatMap { unbound =>
                if unbound then refuseUnbound
                else
                  networkIdNow.flatMap { nid =>
                    sessions.create(t.accountSub, t.origin, nid).flatMap { rec =>
                      // #522: Secure rides the consuming browser origin, not the
                      // server's preference — https and tunnel-marked legs (the
                      // relay console behind the HTTPS ingress) keep `Secure`;
                      // a plain-http DIRECT leg (the local console) must NOT
                      // carry it: WebKit refuses to store Secure cookies over
                      // plain http (no localhost exemption), which silently
                      // dropped every local session seating — WS handshake and
                      // REST then answered 403 behind a "please log in" loop.
                      // A direct client cannot forge the mark (boot-armed
                      // 256-bit in-process value, client-supplied copies of the
                      // header are dropped by the bridge before injection), so
                      // "marked" is exactly "the cookie will be consumed at an
                      // HTTPS origin".
                      val secure =
                        req.uri.scheme.exists(_.value.equalsIgnoreCase("https")) ||
                          nebflow.shared.TunnelOriginMark.isMarked(req)
                      logger.info(
                        s"console ticket redeemed: session established (accountSub=${t.accountSub}, " +
                          s"origin=${t.origin}, sid=${rec.sessionId.take(8)}…, networkId=${nid.take(24)})"
                      )
                      IO.pure(
                        Response[IO](Status.SeeOther)
                          .putHeaders(Location(Uri(path = Uri.Path.unsafeFromString("/"))))
                          .addCookie(GatewaySessionStore.newCookie(rec.sessionId, secure = secure))
                      )
                    }
                  }
              }
          }
    }

  /**
   * Readable result page (same three-part card skeleton as the login
   * callback page): icon + headline + detail. Never renders the ticket value
   * and never renders a bare status code.
   */
  private def resultPage(status: Status, headline: String, detail: String): Response[IO] =
    val body =
      s"""<!doctype html>
         |<html lang="en"><head><meta charset="utf-8">
         |<meta name="viewport" content="width=device-width,initial-scale=1">
         |<title>Console ticket</title>
         |<style>body{font-family:-apple-system,'Segoe UI',sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;background:#f6f7f9;color:#1f2328}
         |.card{text-align:center;padding:44px 52px;border-radius:14px;background:#fff;box-shadow:0 2px 14px rgba(0,0,0,.08)}
         |.icon{color:#d1242f;font-size:42px;line-height:1;margin-bottom:10px}h1{font-size:18px;font-weight:600;margin:0 0 8px}
         |p{color:#6a737d;font-size:13px;margin:0;max-width:340px;word-break:break-word}</style></head>
         |<body><div class="card"><div class="icon">&#10005;</div><h1>$headline</h1><p>$detail</p></div></body></html>""".stripMargin
    // Raw byte stream with an explicit content type — deliberately NOT
    // `withEntity(String)`: this file's sibling imports pull circe's String
    // entity encoder into scope, which would JSON-quote the whole page (the
    // same trap NfFileRoutes.nfText documents). Raw bytes bypass encoder
    // resolution entirely.
    Response[IO](status)
      .putHeaders(org.http4s.headers.`Content-Type`(MediaType.text.html, Charset.`UTF-8`))
      .withBodyStream(fs2.Stream.emit(body).through(fs2.text.utf8.encode))

end ConsoleTicketRoutes
