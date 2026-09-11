package nebflow.neblink

import cats.effect.IO
import cats.syntax.all.*

/**
  * Logto silent re-login (stage 2, 2026-08-28): the server rejected the
  * long-lived deviceToken (HTTP 401 on the session exchange) — rotate the
  * provider refresh token, re-register the device, and persist + hot-swap.
  *
  * Returns the NEW device token for the client's single retry, or None when
  * silent re-login is impossible (no stored refresh token / no AC app
  * configured / provider or server failure) so the frontend surfaces the
  * login prompt instead.
  *
  * `discovery` is a late-bound provider (GatewayMain creates the client
  * before NeblinkDiscovery exists) so the hot-swap target is resolved at
  * call time. The freshly-enrolled client receives THIS SAME hook (rebuilt
  * from the same late-bound provider), so relogin capability survives
  * hot-swaps.
  *
  * O5 decision (2026-09-11, refresh-revoke plan §2 O5 row / §3④): after the
  * client stopped requesting `offline_access` ([[LogtoAuthCode.authorizeUrl]])
  * this hook loses its token source, so it is KEPT AS A NO-REFRESH-TOKEN
  * ATTEMPT rather than disabled or re-pointed:
  *  - "disable" was rejected — it would delete a working fallback for every
  *    install enrolled BEFORE the scope change (those device.json files
  *    still hold a usable refresh token) on an intuition, not on evidence;
  *  - "re-login via the device credential" is not an alternative: this hook
  *    IS the reaction to the server rejecting that very credential with
  *    HTTP 401 (`NeblinkClient.reloginAllowed` — 401 + relogin budget); the
  *    routine device-credential re-login is the primary path that already
  *    ran and failed before this hook is entered.
  * Evidence for the call site / trigger (falsifiable): the hook is passed as
  * `NeblinkClient.onDeviceTokenRejected` from `RestApiRoutes.persistEnrollment`
  * and from the GatewayMain startup wiring, and is reachable ONLY through
  * `NeblinkClient.doLogin`'s 401 branch (`NeblinkClient.scala:261`). It has
  * zero callers otherwise (`LogtoSilentRelogin.make` has exactly those two).
  * The degradation is observable, never silent: see [[startRefresh]].
  */
object LogtoSilentRelogin:

  def make(
    ms: NeblinkService,
    discovery: IO[Option[NeblinkDiscovery]],
    gatewayPort: Int,
    serverUrlOf: IO[String]
  ): IO[Option[String]] =
    refreshAndRegister(ms, discovery, gatewayPort, serverUrlOf).handleErrorWith(e =>
      warn(s"silent re-login error: ${e.getMessage}").as(None)
    )

  private def refreshAndRegister(
    ms: NeblinkService,
    discovery: IO[Option[NeblinkDiscovery]],
    gatewayPort: Int,
    serverUrlOf: IO[String]
  ): IO[Option[String]] =
    for
      stored <- DeviceCredential.load
      refreshToken = stored.flatMap(_.logto).map(_.refreshToken)
      // Embedded-default fallback: missing logto block resolves to the
      // product's hosted auth service, so a refresh token minted via the
      // default PKCE chain stays refreshable after restart (same resolution
      // as the auth/start endpoint).
      logto <- ms.neblinkConfig.map(_.effectiveLogto)
      serverUrl <- serverUrlOf
      fresh <- startRefresh(refreshToken, logto.map(_.endpoint), logto.flatMap(_.pkceClientId))
      out <- IO.defer(dispatchRefresh(fresh, ms, discovery, gatewayPort, serverUrl, serverUrlOf))
    yield out

  /** O5 degradation notice (2026-09-11) — shared rationale, logged at the
    * exact moment this path is entered or found unusable so an operator can
    * tell "the scope change removed the token source" apart from "the
    * provider rejected the rotation". */
  private val o5Reason =
    "O5: authorize no longer requests offline_access (alwaysIssueRefreshToken=false), " +
      "so no refresh_token is issued for a new login"

  /** Refresh-token rotation (grant_type=refresh_token). Unavailable when
    * there is no stored refresh token or no AC app configured.
    *
    * O5 (2026-09-11): a refresh token can now only come from a PRE-O5
    * enrollment, so both branches are explicitly observable at ≥INFO with
    * the timing (entering the attempt / degrading to unavailable) and the
    * reason ([o5Reason]). Nothing here returns an empty request silently —
    * the absent-token case yields a Left that the caller logs at WARN. */
  private def startRefresh(
    refreshToken: Option[String],
    endpoint: Option[String],
    pkceClientId: Option[String]
  ): IO[Either[String, LogtoAuthCode.TokenResult]] =
    (refreshToken, endpoint, pkceClientId) match
      case (Some(rt), Some(ep), Some(pc)) =>
        // Timing: the device credential was just rejected with 401 and a
        // stored refresh token exists — the credential is therefore a pre-O5
        // one (post-O5 logins persist none). Rotation degrades on its own
        // once that token is consumed / expired / revoked.
        log(s"attempting refresh-token rotation with a stored pre-O5 credential — $o5Reason") *>
          LogtoAuthCode.tokenCall(LogtoDeviceFlow.jdkSend)(LogtoAuthCode.refreshTokenRequest(ep, pc, rt))
      case _ =>
        // Timing: hook entered, nothing to rotate — the frontend will be
        // asked for a fresh login (NeblinkClient surfaces the original 401).
        log(
          s"silent re-login degraded to login-required (storedRefreshToken=${refreshToken.isDefined}, " +
            s"acApp=${pkceClientId.isDefined}) — $o5Reason"
        ).as(Left(s"silent re-login unavailable: no stored refresh token or AC app id ($o5Reason)"))

  /** Rotation write-back FIRST (using the token invalidates it — a failed
    * register must still leave a usable refresh credential), then register
    * and persist + hot-swap. */
  private def dispatchRefresh(
    fresh: Either[String, LogtoAuthCode.TokenResult],
    ms: NeblinkService,
    discovery: IO[Option[NeblinkDiscovery]],
    gatewayPort: Int,
    serverUrl: String,
    serverUrlOf: IO[String]
  ): IO[Option[String]] =
    fresh match
      case Right(tokens) =>
        // Rotation write-back: new refresh token + (when the refresh grant
        // response carried one) the fresh id_token — keeps the end-session
        // hint current with the provider's latest session issuance.
        tokens.refreshToken.traverse_(rt => DeviceCredential.updateLogtoRefresh(rt, tokens.idToken)) *>
          ms.identity.flatMap { identity =>
            LogtoDeviceFlow
              .register(LogtoDeviceFlow.jdkSend)(
                serverUrl,
                tokens.accessToken,
                identity.deviceId,
                identity.deviceName,
                identity.platform
              )
              .flatMap {
                case Right(json) =>
                  discovery.flatMap(d =>
                    NeblinkEnrollment
                      .persist(
                        ms,
                        serverUrl,
                        json,
                        tokens.refreshToken,
                        d,
                        gatewayPort,
                        reloginHook = Some(make(ms, discovery, gatewayPort, serverUrlOf))
                      )
                      .flatMap {
                        case Right(tok) => log("silent re-login succeeded").as(Some(tok))
                        case Left(err)  => warn(s"silent re-login persist failed: $err").as(None)
                      }
                  )
                case Left(err) =>
                  warn(s"silent re-login register failed: $err").as(None)
              }
          }
      case Left(err) => warn(s"silent re-login refresh failed: $err").as(None)

  /** Production transport — same policy as LogtoDeviceFlow.jdkSend. */
  private def log(message: String): IO[Unit] =
    nebflow.core.NebflowLogger.forName("nebflow.neblink.relogin").info(s"[logto-ac] $message")

  /** Failure-path logging — warn level so silent-relogin breakage surfaces in
    * instance logs instead of hiding among routine info lines. */
  private def warn(message: String): IO[Unit] =
    nebflow.core.NebflowLogger.forName("nebflow.neblink.relogin").warn(s"[logto-ac] $message")

end LogtoSilentRelogin
