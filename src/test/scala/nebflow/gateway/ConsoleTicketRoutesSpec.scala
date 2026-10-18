package nebflow.gateway

import cats.effect.IO
import munit.CatsEffectSuite
import nebflow.neblink.{ConsoleTicketStore, PortalHostFaces}
import org.http4s.{Method, Request, Status, Uri}

import scala.concurrent.duration.*

/**
 * The `/?ticket=` redemption face (phase 3b, plan §3 / criterion 4 + 3):
 *
 *  - success: 303 See Other to "/" carrying the frozen session-cookie
 *    attribute set (HttpOnly / SameSite=Lax / Path=/ / Max-Age=43200
 *    / host-only, criterion 4's response-header face) — with `Secure` IFF the
 *    consuming browser origin is HTTPS (#522): direct https and
 *    tunnel-marked (relay console) legs carry `Secure`; a plain-http DIRECT
 *    leg (the local console) must NOT — WebKit drops Secure cookies over
 *    plain http (no localhost exemption), which silently bricked local
 *    session seating (WS handshake + REST then 403 behind a login loop);
 *  - atomic one-shot: the SAME ticket a second time lands on the readable
 *    failure page with NO Set-Cookie (criterion 3);
 *  - the origin branch: a relay-mint ticket is refused while the binding is
 *    unbound; a local-ticket is EXEMPT (the break-glass escape, v3 :290);
 *  - an absent face (tunnel not assembled) answers a readable 503, never a
 *    bare 500;
 *  - a plain `GET /` (no ticket) does NOT match this route (fall-through to
 *    the static tree is preserved by construction).
 */
class ConsoleTicketRoutesSpec extends CatsEffectSuite:

  private val brandSessions = GatewaySessionStore.unsafeCreate()

  /** A faces holder whose console face closes over the GIVEN real store. */
  private def mkFaces(unboundRef: cats.effect.Ref[IO, Boolean], store: ConsoleTicketStore): PortalHostFaces =
    val faces = new PortalHostFaces(gatewayPort = 1)
    faces.bindConsoleTicketFace(
      PortalHostFaces.ConsoleTicketFace(
        isUnbound = unboundRef.get,
        verifyAndConsume = t => store.verifyAndConsume(t),
        mintLocalTicket = store
          .issue(
            ConsoleTicketStore.LocalAccountSub,
            "qa-device",
            ConsoleTicketStore.OriginLocalTicket,
            Some(ConsoleTicketStore.LocalTicketTtlSeconds)
          )
          .map(Right(_))
      )
    )
    faces

  private def route(faces: PortalHostFaces) =
    ConsoleTicketRoutes.ticketRoutes(brandSessions, faces, IO.pure("net-1"), nebflow.shared.NebflowLogger.forName("test"))

  private def get(path: String): Request[IO] = Request[IO](Method.GET, Uri.unsafeFromString(path))

  private def setCookies(resp: org.http4s.Response[IO]): List[String] =
    resp.headers.headers.filter(h => h.name.toString.equalsIgnoreCase("Set-Cookie")).map(_.value)

  private def cookieValue(c: String): String =
    c.substring(c.indexOf('=') + 1).takeWhile(_ != ';')

  test("success: 303 + Location / + the frozen cookie attribute set (criterion 4)"):
    for
      unbound <- cats.effect.Ref.of[IO, Boolean](false)
      store = ConsoleTicketStore.unsafeCreate()
      faces = mkFaces(unbound, store)
      issued <- store.issue("logto|user-42", "qa-device")
      resp <- route(faces)(get(s"/?ticket=${issued.token}")).value.map(_.get)
      _ = assertEquals(resp.status, Status.SeeOther)
      loc = resp.headers.headers.find(h => h.name.toString.equalsIgnoreCase("Location")).map(_.value)
      _ = assertEquals(loc, Some("/"))
      cookies = setCookies(resp)
      _ = assertEquals(cookies.size, 1, "exactly one Set-Cookie")
      c = cookies.head
      _ = assert(c.startsWith(s"${GatewaySessionStore.CookieName}="), s"cookie name: $c")
      _ = assert(c.contains("HttpOnly"), c)
      _ = assert(c.contains("SameSite=Lax"), c)
      _ = assert(c.contains("Path=/"), c)
      _ = assert(c.contains("Max-Age=43200"), c)
      _ = assert(!c.toLowerCase.contains("domain="), s"host-only: no Domain attribute: $c")
      // #522: this request is plain-http and unmarked (the local console's
      // exact shape) — `Secure` must be ABSENT, or WebKit drops the cookie and
      // the seating silently no-ops.
      _ = assert(!c.toLowerCase.contains("secure"), s"plain-http direct leg: no Secure: $c")
      // The session is live behind the cookie value.
      verified <- brandSessions.verifyAndTouch(cookieValue(c))
      _ = assert(verified.isDefined, "the issued cookie must verify against the store")
      _ = assertEquals(verified.get.origin, GatewaySessionStore.OriginRelayMint)
    yield ()

  test("success over https => cookie carries Secure (the consuming origin is HTTPS)"):
    for
      unbound <- cats.effect.Ref.of[IO, Boolean](false)
      store = ConsoleTicketStore.unsafeCreate()
      faces = mkFaces(unbound, store)
      issued <- store.issue("logto|user-42", "qa-device")
      https = Request[IO](Method.GET, Uri.unsafeFromString(s"https://console.example/?ticket=${issued.token}"))
      resp <- route(faces)(https).value.map(_.get)
      _ = assertEquals(resp.status, Status.SeeOther)
      c = setCookies(resp).head
      _ = assert(c.contains("Secure"), s"https leg keeps Secure: $c")
    yield ()

  test("success on a tunnel-marked leg => cookie carries Secure (relay console, HTTPS origin)"):
    for
      unbound <- cats.effect.Ref.of[IO, Boolean](false)
      store = ConsoleTicketStore.unsafeCreate()
      faces = mkFaces(unbound, store)
      issued <- store.issue("logto|user-42", "qa-device")
      _ = nebflow.shared.TunnelOriginMark.arm()
      marked = nebflow.shared.TunnelOriginMark.armedValue.fold(get(s"/?ticket=${issued.token}")) { v =>
        val http = get(s"/?ticket=${issued.token}")
        http.withHeaders(org.http4s.Headers(nebflow.shared.TunnelOriginMark.headerName -> v))
      }
      resp <- route(faces)(marked).value.map(_.get)
      _ = assertEquals(resp.status, Status.SeeOther)
      c = setCookies(resp).head
      _ = assert(c.contains("Secure"), s"tunnel-marked leg keeps Secure: $c")
    yield ()

  test("criterion 3: the SAME ticket a second time => readable failure page, NO Set-Cookie"):
    for
      unbound <- cats.effect.Ref.of[IO, Boolean](false)
      store = ConsoleTicketStore.unsafeCreate()
      faces = mkFaces(unbound, store)
      issued <- store.issue("logto|user-42", "qa-device")
      first <- route(faces)(get(s"/?ticket=${issued.token}")).value.map(_.get)
      _ = assertEquals(first.status, Status.SeeOther)
      second <- route(faces)(get(s"/?ticket=${issued.token}")).value.map(_.get)
      _ = assertEquals(second.status, Status.Forbidden)
      body <- second.bodyText.compile.string
      _ = assert(body.contains("already used") || body.contains("rejected"), s"readable page: ${body.take(120)}")
      _ = assertEquals(setCookies(second), Nil, "a refused redemption must never set a cookie")
    yield ()

  test("unknown ticket => failure page + no Set-Cookie"):
    for
      unbound <- cats.effect.Ref.of[IO, Boolean](false)
      faces = mkFaces(unbound, ConsoleTicketStore.unsafeCreate())
      resp <- route(faces)(get("/?ticket=never-issued")).value.map(_.get)
      _ = assertEquals(resp.status, Status.Forbidden)
      _ = assertEquals(setCookies(resp), Nil)
    yield ()

  test("expired ticket => failure page + no Set-Cookie"):
    for
      unbound <- cats.effect.Ref.of[IO, Boolean](false)
      store = ConsoleTicketStore.unsafeCreate()
      faces = mkFaces(unbound, store)
      issued <- store.issue("logto|user-42", "qa-device", ttlSeconds = Some(1))
      _ <- IO.sleep(1100.millis)
      resp <- route(faces)(get(s"/?ticket=${issued.token}")).value.map(_.get)
      _ = assertEquals(resp.status, Status.Forbidden)
      _ = assertEquals(setCookies(resp), Nil)
      body <- resp.bodyText.compile.string
      _ = assert(body.contains("expired"), s"the page must say WHY: ${body.take(160)}")
    yield ()

  test("relay-mint + unbound binding => refused (stale-ticket semantics), no Set-Cookie"):
    for
      unbound <- cats.effect.Ref.of[IO, Boolean](true)
      store = ConsoleTicketStore.unsafeCreate()
      faces = mkFaces(unbound, store)
      issued <- store.issue("logto|user-42", "qa-device")
      resp <- route(faces)(get(s"/?ticket=${issued.token}")).value.map(_.get)
      _ = assertEquals(resp.status, Status.Forbidden)
      _ = assertEquals(setCookies(resp), Nil)
      body <- resp.bodyText.compile.string
      _ = assert(body.toLowerCase.contains("revoked") || body.toLowerCase.contains("local"), s"readable why: ${body.take(160)}")
    yield ()

  test("local-ticket + unbound binding => EXEMPT (break-glass escape): 303 + cookie"):
    for
      unbound <- cats.effect.Ref.of[IO, Boolean](true)
      store = ConsoleTicketStore.unsafeCreate()
      faces = mkFaces(unbound, store)
      issued <- store.issue(
        ConsoleTicketStore.LocalAccountSub,
        "qa-device",
        ConsoleTicketStore.OriginLocalTicket,
        Some(ConsoleTicketStore.LocalTicketTtlSeconds)
      )
      resp <- route(faces)(get(s"/?ticket=${issued.token}")).value.map(_.get)
      _ = assertEquals(resp.status, Status.SeeOther, "the break-glass leg must survive a revocation (v3 :290)")
      _ = assertEquals(setCookies(resp).size, 1)
      verified <- brandSessions.verifyAndTouch(cookieValue(setCookies(resp).head))
      _ = assertEquals(verified.map(_.origin), Some(GatewaySessionStore.OriginLocalTicket))
    yield ()

  test("absent face (tunnel not assembled) => readable 503, never a bare 500"):
    val faces = new PortalHostFaces(gatewayPort = 1) // nothing bound
    for
      resp <- route(faces)(get("/?ticket=whatever")).value.map(_.get)
      _ = assertEquals(resp.status, Status.ServiceUnavailable)
      body <- resp.bodyText.compile.string
      _ = assert(body.contains("not assembled") || body.contains("unavailable"), s"readable: ${body.take(160)}")
      _ = assertEquals(setCookies(resp), Nil)
    yield ()

  test("plain GET / (no ticket param) does NOT match the route (fall-through to the static tree)"):
    val faces = mkFaces(cats.effect.Ref.unsafe[IO, Boolean](false), ConsoleTicketStore.unsafeCreate())
    for
      none <- route(faces)(get("/")).value
      _ = assert(none.isEmpty, "no ticket => fall-through (None), the static tree keeps serving /")
    yield ()

end ConsoleTicketRoutesSpec
