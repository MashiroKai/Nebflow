package nebflow.gateway

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials, Method, Request, Uri}

/**
 * The unified GatewayAuth criterion (phase 3b, plan §4 / criterion 6):
 *
 *  - fixed priority session > Bearer > legacy cookie > ?token=;
 *  - presented-but-invalid rails FALL THROUGH (never short-circuit into a
 *    rejection) — pinned by "wrong param + right cookie authenticates";
 *  - per-face rail sets (ApiFull / WsAuth / StaticAuth / OpenAiCompat /
 *    BearerFileOnly) with the single-rail regression matrix (phase-A monotonic
 *    relaxation: legacy single-rail behavior is byte-identical);
 *  - the R1-1 pin: BearerFileOnly refuses a VALID session cookie;
 *  - the rotate linkage: a rotated file token kills the Bearer rail but not
 *    the session rail (criterion 7's unit face).
 *
 * PAT positive-path (a real signed JWT against JWKS) is an integration face —
 * here an INVALID PAT is asserted to fall through, which is the phase-A
 * regression that matters.
 */
class GatewayAuthSpec extends CatsEffectSuite:

  private val FileToken = "file-token-abc123"

  private val sessions = GatewaySessionStore.unsafeCreate()

  private def req(
    path: String = "/x",
    paramToken: Option[String] = None,
    bearer: Option[String] = None,
    cookies: (String, String)*
  ): Request[IO] =
    val uri0 = Uri.unsafeFromString(paramToken.fold(path)(t => s"$path?token=$t"))
    val base = Request[IO](Method.GET, uri0)
    val withBearer = bearer.fold(base)(t => base.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, t))))
    cookies.foldLeft(withBearer)((r, c) => r.addCookie(c._1, c._2))

  private def auth(r: Request[IO], rails: GatewayAuth.Rails, fileToken: String = FileToken): IO[Option[Credential]] =
    GatewayAuth.authenticate(r, fileToken, sessions, rails)

  private def liveSession(origin: String = GatewaySessionStore.OriginRelayMint): IO[String] =
    sessions.create("logto|user-42", origin, "net-1").map(_.sessionId)

  // ── criterion 6 core: cookie beats an INVALID param ────────────────────

  test("wrong ?token= + valid session cookie => ViaSession (WsAuth), never a rejection"):
    for
      sid <- liveSession()
      r = req(paramToken = Some("wrong-param-token"), cookies = (GatewaySessionStore.CookieName, sid))
      hit <- auth(r, GatewayAuth.Rails.WsAuth)
      _ = assertEquals(hit.map(_.railName), Some("session"))
    yield ()

  test("the same shape under ApiFull (RestApiCtx.checkAuth contract) authenticates"):
    for
      sid <- liveSession()
      r = req(paramToken = Some("wrong-param-token"), cookies = (GatewaySessionStore.CookieName, sid))
      hit <- auth(r, GatewayAuth.Rails.ApiFull)
      _ = assert(hit.isDefined, "checkAuth must accept: invalid param falls through to the session rail")
    yield ()

  test("fall-through: invalid session cookie + valid ?token= => ViaParam"):
    val r = req(paramToken = Some(FileToken), cookies = (GatewaySessionStore.CookieName, "stale-session-id"))
    for
      hit <- auth(r, GatewayAuth.Rails.WsAuth)
      _ = assertEquals(hit, Some(Credential.ViaParam))
    yield ()

  test("fall-through: invalid Bearer + valid legacy cookie => ViaLegacyCookie (WsAuth)"):
    val r = req(bearer = Some("not-a-valid-token"), cookies = ("nebflow_token", FileToken))
    for
      hit <- auth(r, GatewayAuth.Rails.WsAuth)
      _ = assertEquals(hit, Some(Credential.ViaLegacyCookie))
    yield ()

  // ── single-rail regression matrix (phase A: strictly monotonic relaxation) ──

  test("only ?token= (valid): WsAuth/ApiFull/StaticAuth accept; OpenAiCompat/BearerFileOnly refuse"):
    val r = req(paramToken = Some(FileToken))
    for
      ws <- auth(r, GatewayAuth.Rails.WsAuth)
      api <- auth(r, GatewayAuth.Rails.ApiFull)
      static <- auth(r, GatewayAuth.Rails.StaticAuth)
      openai <- auth(r, GatewayAuth.Rails.OpenAiCompat)
      admin <- auth(r, GatewayAuth.Rails.BearerFileOnly)
      _ = assertEquals(ws, Some(Credential.ViaParam))
      _ = assertEquals(api, Some(Credential.ViaParam))
      _ = assertEquals(static, Some(Credential.ViaParam))
      _ = assertEquals(openai, None, "OpenAiCompat never gained param")
      _ = assertEquals(admin, None, "BearerFileOnly never accepts param")
    yield ()

  test("only legacy nebflow_token cookie (valid): WsAuth/StaticAuth accept; ApiFull/OpenAiCompat/BearerFileOnly refuse (R1-2)"):
    val r = req(cookies = ("nebflow_token", FileToken))
    for
      ws <- auth(r, GatewayAuth.Rails.WsAuth)
      static <- auth(r, GatewayAuth.Rails.StaticAuth)
      api <- auth(r, GatewayAuth.Rails.ApiFull)
      openai <- auth(r, GatewayAuth.Rails.OpenAiCompat)
      admin <- auth(r, GatewayAuth.Rails.BearerFileOnly)
      _ = assertEquals(ws, Some(Credential.ViaLegacyCookie))
      _ = assertEquals(static, Some(Credential.ViaLegacyCookie))
      _ = assertEquals(api, None, "R1-2: the /api face never had a cookie leg; it must not gain one")
      _ = assertEquals(openai, None)
      _ = assertEquals(admin, None)
    yield ()

  test("only session cookie (valid): every session-carrying set accepts; BearerFileOnly refuses (R1-1 pin)"):
    for
      sid <- liveSession()
      mk = req(cookies = (GatewaySessionStore.CookieName, sid))
      ws <- auth(mk, GatewayAuth.Rails.WsAuth)
      api <- auth(mk, GatewayAuth.Rails.ApiFull)
      static <- auth(mk, GatewayAuth.Rails.StaticAuth)
      openai <- auth(mk, GatewayAuth.Rails.OpenAiCompat)
      admin <- auth(mk, GatewayAuth.Rails.BearerFileOnly)
      _ = assertEquals(ws.map(_.railName), Some("session"))
      _ = assertEquals(api.map(_.railName), Some("session"))
      _ = assertEquals(static.map(_.railName), Some("session"))
      _ = assertEquals(openai.map(_.railName), Some("session"))
      _ = assertEquals(
        admin,
        None,
        "R1-1: a relay-mint session cookie must NEVER reach the AuthAdmin face (renewal-chain cut)"
      )
    yield ()

  test("only Bearer file token: ApiFull/WsAuth/OpenAiCompat/BearerFileOnly accept; StaticAuth refuses and falls through"):
    val r = req(bearer = Some(FileToken))
    for
      api <- auth(r, GatewayAuth.Rails.ApiFull)
      ws <- auth(r, GatewayAuth.Rails.WsAuth)
      openai <- auth(r, GatewayAuth.Rails.OpenAiCompat)
      admin <- auth(r, GatewayAuth.Rails.BearerFileOnly)
      static <- auth(r, GatewayAuth.Rails.StaticAuth)
      _ = assertEquals(api, Some(Credential.ViaBearer(isFileToken = true)))
      _ = assertEquals(ws, Some(Credential.ViaBearer(isFileToken = true)))
      _ = assertEquals(openai, Some(Credential.ViaBearer(isFileToken = true)))
      _ = assertEquals(admin, Some(Credential.ViaBearer(isFileToken = true)))
      _ = assertEquals(static, None, "StaticAuth has no Bearer leg (unchanged from pre-3b)")
    yield ()

  test("only an INVALID PAT: falls through everywhere (positive PAT needs JWKS = integration face)"):
    val r = req(bearer = Some("eyJhbGciOiJFUzI1NiJ9.invalid.payload"))
    for
      api <- auth(r, GatewayAuth.Rails.ApiFull)
      ws <- auth(r, GatewayAuth.Rails.WsAuth)
      // Neither request carries a param/cookie, so after the invalid Bearer
      // falls through there is nothing left to accept it.
      _ = assertEquals(api, None)
      _ = assertEquals(ws, None)
    yield ()

  test("StaticAuth: an invalid Bearer falls through to a valid param (bearer rail off, next rail consulted)"):
    val r = req(paramToken = Some(FileToken), bearer = Some("wrong-bearer"))
    for
      hit <- auth(r, GatewayAuth.Rails.StaticAuth)
      _ = assertEquals(hit, Some(Credential.ViaParam))
    yield ()

  test("priority INSIDE the Bearer rail: a valid file token wins regardless of PAT validity, and vice versa"):
    val fileReq = req(bearer = Some(FileToken))
    for
      hit <- auth(fileReq, GatewayAuth.Rails.ApiFull)
      _ = assertEquals(hit, Some(Credential.ViaBearer(isFileToken = true)))
    yield ()

  // ── rotate linkage (criterion 7, unit face) ────────────────────────────

  test("rotate: the OLD file token stops authenticating; the live session keeps working"):
    for
      sid <- liveSession()
      newToken = "file-token-ROTATED"
      oldBearer = req(bearer = Some(FileToken))
      session = req(cookies = (GatewaySessionStore.CookieName, sid))
      oldHit <- auth(oldBearer, GatewayAuth.Rails.WsAuth)
      _ = assertEquals(oldHit, Some(Credential.ViaBearer(isFileToken = true)), "pre-condition: old token valid")
      afterOld <- auth(oldBearer, GatewayAuth.Rails.WsAuth, fileToken = newToken)
      _ = assertEquals(afterOld, None, "rotated: the old Bearer must be dead on the live gateway")
      afterSession <- auth(session, GatewayAuth.Rails.WsAuth, fileToken = newToken)
      _ = assertEquals(afterSession.map(_.railName), Some("session"), "rotate must NOT kill live sessions")
    yield ()

  // ── probe header contract ──────────────────────────────────────────────

  test("rail names are exactly session/bearer/legacy/param; the header name is brand-derived"):
    assertEquals(Credential.ViaSession(nullRecord).railName, "session")
    assertEquals(Credential.ViaBearer(isFileToken = true).railName, "bearer")
    assertEquals(Credential.ViaLegacyCookie.railName, "legacy")
    assertEquals(Credential.ViaParam.railName, "param")
    assertEquals(GatewayAuth.CredentialHeader, s"x-${nebflow.shared.Branding.lowerName}-credential")
    assertEquals(GatewayAuth.CredentialHeader, "x-nebflow-credential")

  private def nullRecord: SessionRecord =
    SessionRecord("sid", "sub", "relay-mint", "net", 0L, 0L, 0L, 0L)

end GatewayAuthSpec
