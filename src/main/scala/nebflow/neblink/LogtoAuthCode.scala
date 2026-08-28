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
    * rotates refresh tokens — always persist the latest value. */
  final case class TokenResult(accessToken: String, refreshToken: Option[String])

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

  /** The hosted authorize URL the browser opens. Loopback redirect (RFC
    * 8252 §7.3): the port is added at request time and accepted by the
    * provider against the registered port-less URI. */
  def authorizeUrl(
    endpoint: String,
    clientId: String,
    redirectUri: String,
    codeChallenge: String,
    state: String
  ): String =
    s"${endpoint.stripSuffix("/")}${Protocol.LogtoOidc.authorize}?" +
      LogtoDeviceFlow.formEncode(
        "client_id" -> clientId,
        "redirect_uri" -> redirectUri,
        "response_type" -> "code",
        "scope" -> "openid offline_access",
        "code_challenge" -> codeChallenge,
        "code_challenge_method" -> "S256",
        "state" -> state
      )

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
        "scope" -> "openid offline_access"
      )
    )

  // ── response mapping (pure) ─────────────────────────────────────────────

  /** Parse a token-endpoint success body. Missing refresh_token is legal
    * (provider choice) but logged/flagged by callers. */
  def parseTokenResponse(body: String): Either[String, TokenResult] =
    for
      json <- parser.parse(body).left.map(_.message)
      c = json.hcursor
      accessToken <- c.downField("access_token").as[String].left.map(_.message)
      refreshToken = c.downField("refresh_token").as[Option[String]].toOption.flatten
    yield TokenResult(accessToken, refreshToken)

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
