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

  test("authorizeUrl carries client_id, S256 challenge, state, openid/email/profile scope, prompt=consent and the loopback redirect") {
    val url = LogtoAuthCode.authorizeUrl(
      endpoint = "https://auth.example/",
      clientId = "pkce-app",
      redirectUri = "http://127.0.0.1:8080/auth/callback",
      codeChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
      state = "st-123"
    )
    assertEquals(url, "https://auth.example/oidc/auth?client_id=pkce-app&redirect_uri=http%3A%2F%2F127.0.0.1%3A8080%2Fauth%2Fcallback&response_type=code&scope=openid+email+profile&prompt=consent&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256&state=st-123")
    // O5 (2026-09-11, refresh-revoke plan): the client MUST NOT request
    // offline_access — the provider then issues no refresh_token for this
    // grant, which removes the L1 credential at its source (every
    // revocation timing inherits the fix).
    assert(!url.contains("offline_access"), "O5: authorize must not request offline_access (no refresh_token is to be issued)")
    // prompt=consent still ships, but it is NO LONGER a refresh-token
    // guard — that rationale died with the offline_access scope (see the
    // LogtoAuthCode.authorizeUrl scaladoc); it stays for the shipped UX.
    assert(url.contains("prompt=consent"), "prompt=consent ships on EVERY authorize (UX; no longer a refresh-token guard)")
    assert(url.contains("email"), "#290 gap 1: email scope is the neblink_id source for pure-Logto accounts — the me endpoint scopes claims to the grant")
    assert(url.contains("profile"), "C2 (2026-09-01): profile scope carries the id_token picture claim — the Logto-user avatar source")
  }

  test("authorizeUrl forceLogin variant ships prompt=login+consent and does NOT request offline_access (RP-logout fix)") {
    val url = LogtoAuthCode.authorizeUrl(
      endpoint = "https://auth.example",
      clientId = "pkce-app",
      redirectUri = "http://127.0.0.1:8080/auth/callback",
      codeChallenge = "ch",
      state = "st",
      prompt = "login consent"
    )
    // `login` forces the hosted account page even with a live SSO session;
    // `consent` stays as the shipped UX default (its old offline_access
    // rationale is gone — O5).
    assert(url.contains("prompt=login+consent"), "switch-account entry forces the account form")
    assert(!url.contains("offline_access"), "O5: the switch-account authorize must not request offline_access either")
  }

  test("authorizeUrl appends ui_locales only when non-empty (BYUI locale handoff)") {
    val base = (endpoint: String, clientId: String, redirectUri: String, challenge: String, state: String) =>
      LogtoAuthCode.authorizeUrl(endpoint, clientId, redirectUri, challenge, state)
    // Non-empty → the OIDC standard ui_locales hint rides along.
    val zh = LogtoAuthCode.authorizeUrl(
      endpoint = "https://auth.example",
      clientId = "pkce-app",
      redirectUri = "http://127.0.0.1:8080/auth/callback",
      codeChallenge = "ch",
      state = "st",
      uiLocales = "zh"
    )
    assert(zh.contains("ui_locales=zh"), s"BYUI locale handoff must reach the authorize URL: $zh")
    val en = LogtoAuthCode.authorizeUrl(
      endpoint = "https://auth.example",
      clientId = "pkce-app",
      redirectUri = "http://127.0.0.1:8080/auth/callback",
      codeChallenge = "ch",
      state = "st",
      uiLocales = "en"
    )
    assert(en.contains("ui_locales=en"), s"en handoff must survive: $en")
    // Default / empty → param omitted ENTIRELY: byte-identical legacy URL,
    // page falls back to navigator.language (pre-BYUI behavior unchanged).
    val bare = base("https://auth.example", "pkce-app", "http://127.0.0.1:8080/auth/callback", "ch", "st")
    assert(!bare.contains("ui_locales"), s"empty uiLocales must omit the param: $bare")
    val explicitEmpty = LogtoAuthCode.authorizeUrl(
      endpoint = "https://auth.example",
      clientId = "pkce-app",
      redirectUri = "http://127.0.0.1:8080/auth/callback",
      codeChallenge = "ch",
      state = "st",
      uiLocales = ""
    )
    assert(!explicitEmpty.contains("ui_locales"), s"explicit empty must equal the legacy shape: $explicitEmpty")
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

  test("refreshTokenRequest posts the refresh_token grant WITHOUT offline_access (post-O5 scope)") {
    val req = LogtoAuthCode.refreshTokenRequest("https://auth.example", "pkce-app", "rt-1")
    assert(req.body.contains("grant_type=refresh_token"))
    assert(req.body.contains("refresh_token=rt-1"))
    // O5: the request keeps the post-O5 scope list — RFC 6749 §6 forbids a
    // refresh request from ADDING a scope the original grant never carried,
    // so shipping offline_access here would contradict the authorize change.
    assert(req.body.contains("scope=openid+email+profile"))
    assert(!req.body.contains("offline_access"), "O5: the refresh request must not ask for offline_access")
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

  test("decodeIdTokenClaims extracts email+name for switch-account memory (2026-09-10)") {
    // Payload {"email":"a@b.io","name":"Alice","sub":"u1"} →
    // base64url "eyJlbWFpbCI6ImFAYi5pbyIsIm5hbWUiOiJBbGljZSIsInN1YiI6InUxIn0"
    val tok = "eyJhbGciOiJSUzI1NiJ9.eyJlbWFpbCI6ImFAYi5pbyIsIm5hbWUiOiJBbGljZSIsInN1YiI6InUxIn0.sig"
    assertEquals(
      LogtoAuthCode.decodeIdTokenClaims(tok, Seq("email", "name")),
      Map("email" -> "a@b.io", "name" -> "Alice")
    )
    // Missing claims are omitted (empty map when none requested exist).
    val noClaims = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1MSJ9.sig"
    assertEquals(LogtoAuthCode.decodeIdTokenClaims(noClaims, Seq("email", "name")), Map.empty[String, String])
    // Non-string claim values are skipped (never throws).
    val nonString = "eyJhbGciOiJSUzI1NiJ9.eyJlbWFpbCI6NDIsIm5hbWUiOnsiYSI6MX19.sig"
    assertEquals(LogtoAuthCode.decodeIdTokenClaims(nonString, Seq("email", "name")), Map.empty[String, String])
    // Malformed input → empty map, no exception.
    assertEquals(LogtoAuthCode.decodeIdTokenClaims("a.b%%%c", Seq("email")), Map.empty[String, String])
    assertEquals(LogtoAuthCode.decodeIdTokenClaims("single-part", Seq("email")), Map.empty[String, String])
    // Empty-string claims are treated as absent.
    val emptyClaim = "eyJhbGciOiJSUzI1NiJ9.eyJlbWFpbCI6IiJ9.sig"
    assertEquals(LogtoAuthCode.decodeIdTokenClaims(emptyClaim, Seq("email")), Map.empty[String, String])
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
