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
      logto <- ms.neblinkConfig.map(_.logto)
      serverUrl <- serverUrlOf
      fresh <- startRefresh(refreshToken, logto.map(_.endpoint), logto.flatMap(_.pkceClientId))
      out <- IO.defer(dispatchRefresh(fresh, ms, discovery, gatewayPort, serverUrl, serverUrlOf))
    yield out

  /** Refresh-token rotation (grant_type=refresh_token). Unavailable when
    * there is no stored refresh token or no AC app configured. */
  private def startRefresh(
    refreshToken: Option[String],
    endpoint: Option[String],
    pkceClientId: Option[String]
  ): IO[Either[String, LogtoAuthCode.TokenResult]] =
    (refreshToken, endpoint, pkceClientId) match
      case (Some(rt), Some(ep), Some(pc)) =>
        LogtoAuthCode.tokenCall(LogtoDeviceFlow.jdkSend)(LogtoAuthCode.refreshTokenRequest(ep, pc, rt))
      case _ =>
        IO.pure(Left("silent re-login unavailable: no stored refresh token or AC app id"))

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
        tokens.refreshToken.traverse_(DeviceCredential.updateLogtoRefresh) *>
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
