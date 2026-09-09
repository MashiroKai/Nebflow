package nebflow.neblink

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, parser}

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/**
  * Authorization Code + PKCE (RFC 7636/6749) client for the external OIDC
  * provider (Logto) — Stage 2 of the Logto migration (2026-08-28). Replaces
  * the copy-a-user-code device flow with a browser round-trip: the gateway
  * builds the authorize URL, the user logs in on the hosted page, and Logto
  * redirects to the gateway's loopback callback (`/auth/callback`, RFC 8252
  * — the registered port-less loopback URI accepts ANY local port, verified
  * against the deployed Logto 2026-08-28).
  *
  * Pure builders (URL/form/parse) are total functions; the network steps are
  * transport-injected (`Send` = `LogtoDeviceFlow.Send`) and unit-tested
  * without network, mirroring `LogtoDeviceFlow`.
  *
  * Production note (Q1, 2026-08-28): the PKCE flow uses a SEPARATE Native
  * app (`nebflow-desktop-pkce`) because the legacy `nebflow-desktop` app is
  * pinned to the device-code grant by its `isDeviceFlow` metadata (and that
  * field is not PATCHable via the Management API). `LogtoConfig.pkceClientId`
  * carries the AC app id; `clientId` keeps meaning the device-flow app.
  */
object LogtoAuthCode:

  /** Reuse the device-flow transport shape and its production instance. */
  type Send = LogtoDeviceFlow.Send

  /** OAuth error redirect payload (Logto redirects errors back to the
    * callback: `?error=...&error_description=...`). */
  final case class CallbackError(error: String, description: Option[String])

  /** Token endpoint result: the access token for registration plus the
    * refresh token (present when `offline_access` was granted). Logto
    * rotates refresh tokens — always persist the latest value.
    * `picture` (2026-09-01 login-chain fix, C2): parsed from the id_token's
    * `picture` claim when the grant carried the `profile` scope — the
    * client-side avatar source for Logto users (Logto picture → device
    * identity → activity bar).
    * `idToken` (RP-logout fix, 2026-09-06): the raw JWT, persisted so the
    * end-session handoff can carry it as `id_token_hint` (skips Logto's
    * logout confirmation page for a one-shot sign-out). */
  final case class TokenResult(
    accessToken: String,
    refreshToken: Option[String],
    picture: Option[String] = None,
    idToken: Option[String] = None
  )

  // ── PKCE primitives (pure) ──────────────────────────────────────────────

  /** RFC 7636 §4.1: high-entropy random string, 43-128 chars, base64url. */
  def generateVerifier: IO[String] =
    IO.delay {
      val bytes = new Array[Byte](32) // 32 bytes -> 43 base64url chars
      new java.security.SecureRandom().nextBytes(bytes)
      base64Url(bytes)
    }

  /** RFC 7636 §4.2: BASE64URL-ENCODE(SHA256(ASCII(code_verifier))). */
  def challengeS256(verifier: String): String =
    base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)))

  /** Opaque state for CSRF protection (RFC 6749 §10.12). */
  def generateState: IO[String] =
    IO.delay {
      val bytes = new Array[Byte](16)
      new java.security.SecureRandom().nextBytes(bytes)
      base64Url(bytes)
    }

  private def base64Url(bytes: Array[Byte]): String =
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  // ── URL / request builders (pure) ───────────────────────────────────────

  /**
   * The hosted authorize URL the browser opens. Loopback redirect (RFC
   * 8252 §7.3): the port is added at request time and accepted by the
   * provider against the registered port-less URI.
   *
   * `prompt=consent` is REQUIRED: this Logto build silently drops the
   * `offline_access` scope from the grant when the authorize request lacks
   * it — the token response then carries no refresh_token and the silent
   * re-login chain (LogtoSilentRelogin) is dead on arrival. Real-chain
   * probe (qa e2e 2026-08-28, 5 controlled experiments): baseline,
   * `alwaysIssueRefreshToken=true`, and a zero-history fresh account all
   * granted "openid" only; with prompt=consent the grant is
   * "openid offline_access" + refresh_token. Consent is NOT remembered
   * across authorizes (exp. 5), so the prompt ships on EVERY login — the
   * cost is one extra consent screen on the low-frequency browser login
   * path (daily use rides the deviceToken + refresh_token silent chain).
   *
   * `prompt` is parameterized (RP-logout fix, 2026-09-06): the default
   * stays "consent" (refresh-token invariant above MUST NOT regress); the
   * switch-account entry passes "login consent" — `login` forces the
   * hosted sign-in page even when the browser's Logto SSO session is
   * alive, so the user gets the account input instead of a silent
   * redirect back to the original account. Both values are legal
   * space-separated OIDC prompt lists (RFC 6749-bis / Core §3.1.2.1).
   *
   * `uiLocales` (BYUI handoff, 2026-09-09): optional OIDC standard
   * `ui_locales` hint so the hosted page renders in the CLIENT's UI
   * language ("zh" / "en", single tag per the handoff). Harmless pre-BYUI —
   * the stock hosted page just picks the sign-in language; active once the
   * BYUI SPA ships (its resolveLocale reads ui_locales first). Empty
   * (default) omits the param entirely: the URL is byte-identical to the
   * pre-BYUI shape and the page falls back to navigator.language. Gating
   * note: NO feature flag here (handoff ① ruling) — the rollback lever is
   * server-side (Logto Console custom-UI upload/removal), and a release
   * strip would defeat the point (release builds are the ones that log in).
   */
  def authorizeUrl(
    endpoint: String,
    clientId: String,
    redirectUri: String,
    codeChallenge: String,
    state: String,
    prompt: String = "consent",
    uiLocales: String = ""
  ): String =
    s"${endpoint.stripSuffix("/")}${Protocol.LogtoOidc.authorize}?" +
      LogtoDeviceFlow.formEncode(
        (
          Seq(
            "client_id" -> clientId,
            "redirect_uri" -> redirectUri,
            "response_type" -> "code",
            "scope" -> "openid offline_access email profile",
            "prompt" -> prompt,
            "code_challenge" -> codeChallenge,
            "code_challenge_method" -> "S256",
            "state" -> state
          ) ++ (if uiLocales.nonEmpty then Seq("ui_locales" -> uiLocales) else Seq.empty)
        )*
      )

  /** RP-initiated logout (OIDC Session Management §5, RP-logout fix
    * 2026-09-06): the URL the browser is navigated to, terminating the
    * PROVIDER session — without it a fresh authorize redirects silently
    * back into the original account (no account choice).
    *
    * `idTokenHint`: the raw id_token persisted at login. A VALID hint lets
    * the provider skip the "Do you want to sign out?" confirmation; a
    * malformed/fabricated one is REJECTED with 400 (probed on the deployed
    * Logto 2026-09-06) — so only pass a stored-hint through verbatim, and
    * omit it entirely when none is stored (session-cookie logout still
    * works, one confirmation screen). Never synthesize a hint.
    *
    * `postLogoutRedirectUri`: optional return target. The deployed provider
    * IGNORES an unregistered uri (200 + default logged-out page, probed
    * 2026-09-06 — no error), so passing it is always safe; it activates
    * automatically once the uri is allow-listed on the Logto app (see the
    * ops checklist in the RP-logout report). */
  def endSessionUrl(
    endpoint: String,
    idTokenHint: Option[String],
    postLogoutRedirectUri: Option[String]
  ): String =
    val params =
      idTokenHint.map("id_token_hint" -> _).toSeq ++
        postLogoutRedirectUri.map("post_logout_redirect_uri" -> _).toSeq
    val base = s"${endpoint.stripSuffix("/")}${Protocol.LogtoOidc.endSession}"
    if params.isEmpty then base
    else base + "?" + LogtoDeviceFlow.formEncode(params*)

  /** Exchange the authorization code (+ verifier) for tokens. */
  def tokenRequest(
    endpoint: String,
    clientId: String,
    redirectUri: String,
    code: String,
    verifier: String
  ): LogtoDeviceFlow.Request =
    LogtoDeviceFlow.Request(
      url = s"${endpoint.stripSuffix("/")}${Protocol.LogtoOidc.token}",
      contentType = "application/x-www-form-urlencoded",
      body = LogtoDeviceFlow.formEncode(
        "grant_type" -> "authorization_code",
        "code" -> code,
        "redirect_uri" -> redirectUri,
        "client_id" -> clientId,
        "code_verifier" -> verifier
      )
    )

  /** Silent re-login: rotate the refresh token for a fresh access token.
    * The response carries a NEW refresh token (Logto rotation) — callers
    * must persist it. */
  def refreshTokenRequest(endpoint: String, clientId: String, refreshToken: String): LogtoDeviceFlow.Request =
    LogtoDeviceFlow.Request(
      url = s"${endpoint.stripSuffix("/")}${Protocol.LogtoOidc.token}",
      contentType = "application/x-www-form-urlencoded",
      body = LogtoDeviceFlow.formEncode(
        "grant_type" -> "refresh_token",
        "refresh_token" -> refreshToken,
        "client_id" -> clientId,
        "scope" -> "openid offline_access email profile"
      )
    )

  // ── response mapping (pure) ─────────────────────────────────────────────

  /** Parse a token-endpoint success body. Missing refresh_token is legal
    * (provider choice) but logged/flagged by callers. `picture` is extracted
    * from the id_token's `picture` claim when present (C2, 2026-09-01). */
  def parseTokenResponse(body: String): Either[String, TokenResult] =
    for
      json <- parser.parse(body).left.map(_.message)
      c = json.hcursor
      accessToken <- c.downField("access_token").as[String].left.map(_.message)
      refreshToken = c.downField("refresh_token").as[Option[String]].toOption.flatten
      idToken = c.downField("id_token").as[Option[String]].toOption.flatten
      picture = idToken.flatMap(decodeIdTokenPicture)
    yield TokenResult(accessToken, refreshToken, picture, idToken)

  /** Decode the `picture` claim from a JWT payload (base64url, no signature
    * check — the token response arrives over TLS from the provider's token
    * endpoint, and the caller already exchanged the code for it; we only
    * read a claim, never act on the token). Pure, testable. */
  def decodeIdTokenPicture(idToken: String): Option[String] =
    idToken.split("\\.") match
      case parts if parts.length >= 2 =>
        try
          val payload = new String(
            Base64.getUrlDecoder.decode(parts(1)),
            StandardCharsets.UTF_8
          )
          parser.parse(payload).toOption
            .flatMap(_.hcursor.downField("picture").as[Option[String]].toOption.flatten)
            .filter(_.nonEmpty)
        catch case _: IllegalArgumentException => None
      case _ => None

  /** Parse an OAuth error redirect (callback query params). */
  def parseCallbackError(query: Map[String, String]): Option[CallbackError] =
    query.get("error").filter(_.nonEmpty).map(CallbackError(_, query.get("error_description")))

  // ── flow steps (IO, transport-injected) ─────────────────────────────────

  /** Run a token request (code exchange or refresh) and parse the outcome. */
  def tokenCall(send: Send)(req: LogtoDeviceFlow.Request): IO[Either[String, TokenResult]] =
    send(req).flatMap { case (status, body) =>
      IO.pure(
        if status == 200 then parseTokenResponse(body)
        else Left(extractError(status, body))
      )
    }

  private def extractError(status: Int, body: String): String =
    if status == 0 then body // transport failure: body carries the message
    else
      parser
        .parse(body)
        .toOption
        .flatMap(_.hcursor.downField("error").as[String].toOption)
        .getOrElse(s"HTTP $status")

end LogtoAuthCode
