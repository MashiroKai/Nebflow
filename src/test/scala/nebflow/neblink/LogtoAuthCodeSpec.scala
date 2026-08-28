package nebflow.neblink

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.parser.parse
import munit.FunSuite

/**
  * Logto AC+PKCE (stage 2, 2026-08-28) pure builders + scripted transport.
  * Challenge vectors are RFC 7636 §B ("test vector the Internet Draft").
  */
class LogtoAuthCodeSpec extends FunSuite:

  // ── PKCE primitives ─────────────────────────────────────────────────────

  test("challengeS256 matches the RFC 7636 appendix-B vector") {
    val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    assertEquals(LogtoAuthCode.challengeS256(verifier), "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
  }

  test("generateVerifier is 43 chars base64url, generateState is 22") {
    val verifier = LogtoAuthCode.generateVerifier.unsafeRunSync()
    val state = LogtoAuthCode.generateState.unsafeRunSync()
    assertEquals(verifier.length, 43)
    assertEquals(state.length, 22)
    assert(!verifier.contains("=") && !verifier.contains("+") && !verifier.contains("/"), "base64url, unpadded")
    // Distinct draws.
    assert(neq(LogtoAuthCode.generateVerifier.unsafeRunSync(), verifier), "verifier must be random")
  }

  private def neq(a: String, b: String): Boolean = a != b

  // ── authorize URL ───────────────────────────────────────────────────────

  test("authorizeUrl carries client_id, S256 challenge, state, offline_access, prompt=consent and the loopback redirect") {
    val url = LogtoAuthCode.authorizeUrl(
      endpoint = "https://auth.example/",
      clientId = "pkce-app",
      redirectUri = "http://127.0.0.1:8080/auth/callback",
      codeChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
      state = "st-123"
    )
    assertEquals(url, "https://auth.example/oidc/auth?client_id=pkce-app&redirect_uri=http%3A%2F%2F127.0.0.1%3A8080%2Fauth%2Fcallback&response_type=code&scope=openid+offline_access&prompt=consent&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256&state=st-123")
    // Named regression nails: Logto silently drops offline_access (→ no
    // refresh_token → silent re-login dead) when prompt=consent is missing.
    // qa real-chain probe 2026-08-28, 5 controlled experiments.
    assert(url.contains("prompt=consent"), "prompt=consent must ship on EVERY authorize — without it Logto drops offline_access and no refresh_token is issued")
    assert(url.contains("offline_access"), "offline_access scope is the precondition for the refresh_token that LogtoSilentRelogin rotates")
  }

  // ── token requests ──────────────────────────────────────────────────────

  test("tokenRequest posts the authorization_code grant with the verifier") {
    val req = LogtoAuthCode.tokenRequest("https://auth.example", "pkce-app", "http://127.0.0.1:8080/auth/callback", "abc", "ver")
    assertEquals(req.url, "https://auth.example/oidc/token")
    assertEquals(req.contentType, "application/x-www-form-urlencoded")
    assert(req.body.contains("grant_type=authorization_code"))
    assert(req.body.contains("code=abc"))
    assert(req.body.contains("code_verifier=ver"))
    assert(req.body.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A8080%2Fauth%2Fcallback"))
    assert(req.bearer.isEmpty)
  }

  test("refreshTokenRequest posts the refresh_token grant with offline_access") {
    val req = LogtoAuthCode.refreshTokenRequest("https://auth.example", "pkce-app", "rt-1")
    assert(req.body.contains("grant_type=refresh_token"))
    assert(req.body.contains("refresh_token=rt-1"))
    assert(req.body.contains("scope=openid+offline_access"))
  }

  // ── response mapping ────────────────────────────────────────────────────

  test("parseTokenResponse extracts access + optional refresh token") {
    val ok = LogtoAuthCode.parseTokenResponse("""{"access_token":"at","refresh_token":"rt","token_type":"Bearer"}""")
    assertEquals(ok, Right(LogtoAuthCode.TokenResult("at", Some("rt"))))
    val noRefresh = LogtoAuthCode.parseTokenResponse("""{"access_token":"at"}""")
    assertEquals(noRefresh, Right(LogtoAuthCode.TokenResult("at", None)))
    val bad = LogtoAuthCode.parseTokenResponse("""{"error":"nope"}""")
    assert(bad.isLeft)
  }

  test("parseCallbackError picks the OAuth error redirect") {
    assertEquals(
      LogtoAuthCode.parseCallbackError(Map("error" -> "access_denied", "error_description" -> "user said no")),
      Some(LogtoAuthCode.CallbackError("access_denied", Some("user said no")))
    )
    assertEquals(LogtoAuthCode.parseCallbackError(Map("code" -> "abc")), None)
  }

  // ── tokenCall (scripted transport) ─────────────────────────────────────

  test("tokenCall maps 200 to tokens and non-200 to the provider error") {
    val good: LogtoDeviceFlow.Send = _ => IO.pure((200, """{"access_token":"at-1","refresh_token":"rt-1"}"""))
    assertEquals(
      LogtoAuthCode.tokenCall(good)(LogtoAuthCode.refreshTokenRequest("https://a", "c", "r")).unsafeRunSync(),
      Right(LogtoAuthCode.TokenResult("at-1", Some("rt-1")))
    )
    val oauthErr: LogtoDeviceFlow.Send = _ => IO.pure((400, """{"error":"invalid_grant"}"""))
    assertEquals(
      LogtoAuthCode.tokenCall(oauthErr)(LogtoAuthCode.refreshTokenRequest("https://a", "c", "r")).unsafeRunSync(),
      Left("invalid_grant")
    )
    // Transport failure: status 0, body IS the message.
    val transport: LogtoDeviceFlow.Send = _ => IO.pure((0, "connect timed out"))
    assertEquals(
      LogtoAuthCode.tokenCall(transport)(LogtoAuthCode.refreshTokenRequest("https://a", "c", "r")).unsafeRunSync(),
      Left("connect timed out")
    )
  }

end LogtoAuthCodeSpec
