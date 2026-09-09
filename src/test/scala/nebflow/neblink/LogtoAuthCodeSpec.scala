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
    assertEquals(url, "https://auth.example/oidc/auth?client_id=pkce-app&redirect_uri=http%3A%2F%2F127.0.0.1%3A8080%2Fauth%2Fcallback&response_type=code&scope=openid+offline_access+email+profile&prompt=consent&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256&state=st-123")
    // Named regression nails: Logto silently drops offline_access (→ no
    // refresh_token → silent re-login dead) when prompt=consent is missing.
    // qa real-chain probe 2026-08-28, 5 controlled experiments.
    assert(url.contains("prompt=consent"), "prompt=consent must ship on EVERY authorize — without it Logto drops offline_access and no refresh_token is issued")
    assert(url.contains("offline_access"), "offline_access scope is the precondition for the refresh_token that LogtoSilentRelogin rotates")
    assert(url.contains("email"), "#290 gap 1: email scope is the neblink_id source for pure-Logto accounts — the me endpoint scopes claims to the grant")
    assert(url.contains("profile"), "C2 (2026-09-01): profile scope carries the id_token picture claim — the Logto-user avatar source")
  }

  test("authorizeUrl forceLogin variant ships prompt=login+consent and KEEPS offline_access (RP-logout fix)") {
    val url = LogtoAuthCode.authorizeUrl(
      endpoint = "https://auth.example",
      clientId = "pkce-app",
      redirectUri = "http://127.0.0.1:8080/auth/callback",
      codeChallenge = "ch",
      state = "st",
      prompt = "login consent"
    )
    // `login` forces the hosted account page even with a live SSO session;
    // `consent` must STAY (offline_access regression guard, 2026-08-28 probe).
    assert(url.contains("prompt=login+consent"), "switch-account entry forces the account form")
    assert(url.contains("offline_access"), "consent kept in the prompt list → refresh_token invariant intact")
  }

  // ── RP-initiated logout (end_session) ──────────────────────────────────

  test("endSessionUrl points at /oidc/session/end with hint + return uri") {
    val url = LogtoAuthCode.endSessionUrl(
      endpoint = "https://auth.example",
      idTokenHint = Some("tok.abc.sig"),
      postLogoutRedirectUri = Some("http://127.0.0.1:8080/auth/logged-out")
    )
    assertEquals(url, "https://auth.example/oidc/session/end?id_token_hint=tok.abc.sig&post_logout_redirect_uri=http%3A%2F%2F127.0.0.1%3A8080%2Fauth%2Flogged-out")
  }

  test("endSessionUrl omits absent params and tolerates trailing-slash endpoints") {
    val noHint = LogtoAuthCode.endSessionUrl("https://auth.example", None, Some("http://127.0.0.1:9/x"))
    assert(noHint.startsWith("https://auth.example/oidc/session/end?post_logout_redirect_uri="))
    val bare = LogtoAuthCode.endSessionUrl("https://auth.example/", None, None)
    assertEquals(bare, "https://auth.example/oidc/session/end")
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
    assert(req.body.contains("scope=openid+offline_access+email+profile"))
  }

  // ── response mapping ────────────────────────────────────────────────────

  test("parseTokenResponse extracts access + optional refresh token") {
    val ok = LogtoAuthCode.parseTokenResponse("""{"access_token":"at","refresh_token":"rt","token_type":"Bearer"}""")
    assertEquals(ok, Right(LogtoAuthCode.TokenResult("at", Some("rt"), None)))
    val noRefresh = LogtoAuthCode.parseTokenResponse("""{"access_token":"at"}""")
    assertEquals(noRefresh, Right(LogtoAuthCode.TokenResult("at", None, None)))
    val bad = LogtoAuthCode.parseTokenResponse("""{"error":"nope"}""")
    assert(bad.isLeft)
  }

  test("parseTokenResponse extracts picture from id_token (C2)") {
    // JWT payload {"picture":"https://avatars.example/pic.png"} — header/sig are filler.
    val idToken = "eyJhbGciOiJSUzI1NiJ9.eyJwaWN0dXJlIjoiaHR0cHM6Ly9hdmF0YXJzLmV4YW1wbGUvcGljLnBuZyIsInN1YiI6InVzZXItMSJ9.c2ln"
    val body = s"""{"access_token":"at","refresh_token":"rt","id_token":"$idToken"}"""
    val parsed = LogtoAuthCode.parseTokenResponse(body)
    assertEquals(parsed.map(_.picture), Right(Some("https://avatars.example/pic.png")))
    // id_token missing / malformed → None, token still parsed
    assertEquals(LogtoAuthCode.parseTokenResponse("""{"access_token":"at"}""").map(_.picture), Right(None))
    assertEquals(LogtoAuthCode.parseTokenResponse("""{"access_token":"at","id_token":"not-a-jwt"}""").map(_.picture), Right(None))
  }

  test("parseTokenResponse carries the RAW id_token through (RP-logout hint source)") {
    // The end-session handoff must replay the id_token VERBATIM (a mutated
    // or fabricated hint is rejected 400 by the provider, probed 2026-09-06).
    val idToken = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1MSJ9.sig"
    val body = s"""{"access_token":"at","refresh_token":"rt","id_token":"$idToken"}"""
    assertEquals(LogtoAuthCode.parseTokenResponse(body).map(_.idToken), Right(Some(idToken)))
    assertEquals(LogtoAuthCode.parseTokenResponse("""{"access_token":"at"}""").map(_.idToken), Right(None))
  }

  test("decodeIdTokenPicture is pure and rejects malformed JWTs") {
    // Base64url payload of {"picture":"https://x.io/a.png"}
    val good = "eyJhbGciOiJSUzI1NiJ9.eyJwaWN0dXJlIjoiaHR0cHM6Ly94LmlvL2EucG5nIn0.sig"
    assertEquals(LogtoAuthCode.decodeIdTokenPicture(good), Some("https://x.io/a.png"))
    // No picture claim → None
    val noPic = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1MSJ9.sig"
    assertEquals(LogtoAuthCode.decodeIdTokenPicture(noPic), None)
    // Malformed base64url payload → None (no exception)
    assertEquals(LogtoAuthCode.decodeIdTokenPicture("a.b%%%c"), None)
    assertEquals(LogtoAuthCode.decodeIdTokenPicture("single-part"), None)
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
