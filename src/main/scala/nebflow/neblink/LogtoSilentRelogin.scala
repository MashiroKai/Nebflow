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
    serverUrlOf: IO[Option[String]]
  ): IO[Option[String]] =
    refreshAndRegister(ms, discovery, gatewayPort, serverUrlOf).handleErrorWith(e =>
      warn(s"silent re-login error: ${e.getMessage}").as(None)
    )

  private def refreshAndRegister(
    ms: NeblinkService,
    discovery: IO[Option[NeblinkDiscovery]],
    gatewayPort: Int,
    serverUrlOf: IO[Option[String]]
  ): IO[Option[String]] =
    // ===== 踢下线停摆门（2026-09-14 踢旧批 r2）— 🔴 必须在 `.register` **之前** =====
    //
    // 复核位判词 fail 的唯一失败点：r1 把停摆位关在 `NeblinkRelayTunnel` 里，而本函数
    // 的 `.register`（下方 `LogtoDeviceFlow.register`）**先于** `NeblinkEnrollment.persist`
    // 执行 ⇒ 服务端的 kick-on-re-enroll 在客户端 persist / 护栏之前就已发生，挡 persist
    // 挡不住重注册（r1 实测：踢后 ~24s 自愈链把 `POST /api/device/register` 打到服务端
    // 并成功）。门放在**本函数入口**（register 的最近前驱、且在任何 I/O 之前）：
    // 停摆期零 HTTP 请求 —— 不刷新 token、不注册、不落盘。
    //
    // 唯一解除口 = 用户显式登录（`NeblinkEnrollment.persist(explicitUserAction = true)`
    // → `NeblinkService.clearKickPark()`）。自动路径（心跳失效后的 `discover` →
    // `login` → 401 hook、API 自愈、隧道升级自愈）全部经过这里或
    // `NeblinkClient.ensureFreshSession`，两者读同一停摆位。
    // 🔴 判据必须**每次执行时**读停摆位 ⇒ 整个分支放进 `IO.defer`。反例（实测踩过）：
    // 直接写 `if ms.kickParked then … else …` 是**严格求值**——`make` 在客户端装配时
    // 就调用本函数，分支在**装配那一刻**（未停摆）被固化，之后无论停摆与否都走 else
    // （cps 实测：建造期 at=0 被烙进 IO，运行期 `at` 已非 0 仍照发 register）。
    IO.defer {
      if ms.kickParked then
        warn(
          "silent re-login suppressed: this device was signed out elsewhere (server-forced " +
            "disconnect) — no device re-registration is sent; an explicit user login is required"
        ).as(None)
      else
        for
          stored <- DeviceCredential.load
          refreshToken = stored.flatMap(_.logto).map(_.refreshToken)
          // Embedded-default fallback: missing logto block resolves to the
          // product's hosted auth service, so a refresh token minted via the
          // default PKCE chain stays refreshable after restart (same resolution
          // as the auth/start endpoint).
          logto <- ms.neblinkConfig.map(_.effectiveLogto)
          // ===== 案 b①（2026-09-20）：目标解析门 —— 与踢下线停摆门同款「I/O 之前」=====
          //
          // 本腿的 `register` 发生在 `persist`（护栏）**之前**（见下 `dispatchRefresh`
          // 的注释与 2026-09-14 踢旧批 r2 的取证），所以「无目标 ⇒ 不注册」必须挡在
          // **本函数入口**：目标缺席时连 token 轮换都不发起（零出站）。
          // 目标缺席 = 隔离数据根 + 无显式/配置 URL + 无 `NEBFLOW_ALLOW_PROD_ENROLL=1`
          // ⇒ `RestApiRoutes.neblinkServerUrl` 给 `None`（生产默认不得被继承）。
          target <- serverUrlOf
          out <- target match
            case Some(serverUrl) =>
              for
                fresh <- startRefresh(refreshToken, logto.map(_.endpoint), logto.flatMap(_.pkceClientId))
                out <- IO.defer(dispatchRefresh(fresh, ms, discovery, gatewayPort, serverUrl, serverUrlOf))
              yield out
            case None =>
              warn(
                "silent re-login degraded to login-required: this instance has no enrollment target " +
                  "(isolated data root with no explicit server URL — the production default is not " +
                  s"inherited; set ${EnrollGuard.AllowProdEnrollEnv}=1 to allow it explicitly)"
              ).as(None)
        yield out
    }

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
    serverUrlOf: IO[Option[String]]
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
