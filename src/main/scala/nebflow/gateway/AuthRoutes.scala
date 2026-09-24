/* 从 RestApiRoutes 迁出(行为保持重构,2026-09-24)。 */
package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import fs2.Stream
import nebflow.neblink.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.{Location, `Content-Type`}

/**
 * 登录回调域(auth):原 RestApiRoutes 的 PKCE 单飞槽(pkceLogin)、切号单窗
 * 切换标记(switchHandoff)与 /auth 挂载面(GET /callback、GET /logged-out)
 * 整体迁入,行为保持;经 RestApiRoutes.authCallbackRoutes 委托挂载,
 * GatewayMain 零改动。依赖实例态(gatewayPort / neblinkService /
 * neblinkDiscovery / logger 与类私有 neblinkServerUrl /
 * completeDeviceEnrollmentDetailed 的 ctx 委托)的成员追加
 * (using ctx: RestApiCtx) 上下文参数 —— 调用点文本(类内 AuthRoutes.xxx
 * 限定引用、回调 case 体、方法体交叉调用)保持原状。
 */
private[gateway] object AuthRoutes:

  /** Logto AC+PKCE login single-flight state (stage 2, 2026-08-28). */
  private[gateway] val pkceLogin = PkceLoginSession.unsafe

  /**
   * Switch-account single-window handoff marker (2026-09-16, one-window
   * switch batch): the ONE place that knows whether the current logout hop is
   * a switch-account flow, so the `/auth/logged-out` landing page it ends on
   * can continue the login in the same window. Lifecycle (arm / consume /
   * disarm, single-use, TTL) lives in [[NeblinkSwitchHandoff]].
   */
  private[gateway] val switchHandoff = NeblinkSwitchHandoff.unsafe

  // ── Loopback callback (RFC 8252): Logto redirects the browser here after
  // the hosted login. No gateway token — the browser carries only the
  // provider redirect; the PKCE state parameter is the anti-CSRF check.

  /**
   * The registered loopback redirect (RFC 8252: the provider accepts ANY
   * local port against the port-less registered URI).
   */
  private def loopbackCallbackUri(using ctx: RestApiCtx): String =
    import ctx.*
    s"http://127.0.0.1:$gatewayPort/auth/callback"

  /**
   * Start a Logto AC+PKCE login: fresh verifier/state registered in
   * [[PkceLoginSession]] + the authorize URL for the SAME parameters.
   * Returns None when the provider is unconfigured (or the AC app id is
   * missing) — callers answer `logto-not-configured`, exactly as before.
   *
   * SINGLE SOURCE of the login start: `POST /api/neblink/auth/start` and the
   * switch-account landing continuation (`/auth/logged-out`) both call this,
   * so a switch continuation can never drift from a normal login (same
   * redirect URI, same PKCE parameters, same prompt mapping — `forceLogin`
   * ⇒ `prompt="login consent"`, the forced-fresh-login semantic the
   * switch-account path requires).
   */
  private[gateway] def beginPkceLogin(
    ms: NeblinkService,
    forceLogin: Boolean,
    uiLocales: String
  )(using ctx: RestApiCtx): IO[Option[String]] =
    import ctx.*

    ms.neblinkConfig.map(_.effectiveLogto).flatMap {
      case Some(lc) if lc.pkceClientId.isDefined =>
        val pkceClientId = lc.pkceClientId.getOrElse("")
        for
          verifier <- LogtoAuthCode.generateVerifier
          challenge = LogtoAuthCode.challengeS256(verifier)
          state <- LogtoAuthCode.generateState
          _ <- pkceLogin.start(verifier, state)
          authorizeUrl = LogtoAuthCode.authorizeUrl(
            lc.endpoint,
            pkceClientId,
            loopbackCallbackUri,
            challenge,
            state,
            prompt = if forceLogin then "login consent" else "consent",
            uiLocales = uiLocales
          )
        yield Some(authorizeUrl)
        end for
      case _ => IO.pure(None)
    }

  def callbackRoutes(ctx: RestApiCtx): HttpRoutes[IO] =
    import ctx.*
    given RestApiCtx = ctx

    HttpRoutes.of[IO] {
      case req @ GET -> Root / "callback" =>
        handleAuthCallback(req.uri.query.params)
      // RP-logout return target. Reachability (2026-09-15 oidcfix reading): the
      // provider REJECTS an unregistered `post_logout_redirect_uri` with 400
      // `post_logout_redirect_uri not registered` (the older "ignored, always
      // safe" note is obsolete), and it does NOT apply loopback port leniency
      // here — so this page is reached only on a port whose exact URI is
      // allow-listed on the Logto app (today: 8080 and 8097).
      //
      // Behaviour is decided in ONE place — the single-use switch handoff marker:
      // armed+fresh ⇒ consume and continue the switch-account login IN THIS
      // WINDOW (302 to the same authorize URL /auth/start would return, forced
      // fresh login); otherwise a static card (plain logout = the unchanged
      // "已退出登录" card; switch cases get an explicit spent/expired/failed card).
      case GET -> Root / "logged-out" =>
        renderLoggedOutLanding
    }

  /**
   * The `/auth/logged-out` landing page (see the route comment above for the
   * reachability constraint). This is the ONLY consumer of the switch handoff
   * marker, and it consumes it exactly once — a replay/reload therefore gets
   * [[NeblinkSwitchHandoff.Outcome.Replay]] and a visible card instead of a
   * second auto-login.
   */
  private def renderLoggedOutLanding(using ctx: RestApiCtx): IO[org.http4s.Response[IO]] =
    import ctx.*

    switchHandoff.consume.flatMap {
      case NeblinkSwitchHandoff.Outcome.Plain =>
        htmlResponse(loggedOutPage, Status.Ok)
      case NeblinkSwitchHandoff.Outcome.Replay =>
        htmlResponse(
          switchNoticePage(
            symbol = "!",
            symbolColor = "#d1242f",
            headline = "续登链接已使用",
            detail = "切号续登入口一次性有效，重放本页不会再次自动登录。如需切换账号，请在 nebflow 设置页重新点「切换账号」。"
          ),
          Status.Ok
        )
      case NeblinkSwitchHandoff.Outcome.Expired =>
        htmlResponse(
          switchNoticePage(
            symbol = "!",
            symbolColor = "#d1242f",
            headline = "续登已超时",
            detail = "本次切号续登标记已过期，未自动登录。请在 nebflow 设置页重新点「切换账号」。"
          ),
          Status.Ok
        )
      case NeblinkSwitchHandoff.Outcome.Continue(uiLocales) =>
        neblinkService match
          case None =>
            htmlResponse(
              switchNoticePage("!", "#d1242f", "续登未启动", "nebflow 服务未初始化，请在本窗口手动登录。"),
              Status.Ok
            )
          case Some(ms) =>
            beginPkceLogin(ms, forceLogin = true, uiLocales = uiLocales)
              .flatMap {
                case Some(url) =>
                  // Same 302 shape as the end-session hop (dsl `Found` returns a
                  // ResponseGenerator, i.e. IO[Response] — build it explicitly so
                  // this branch and the `None` branch share one IO type).
                  IO.pure(
                    org.http4s
                      .Response[IO](Status.Found)
                      .withHeaders(Headers(Location(Uri.unsafeFromString(url))))
                  )
                case None =>
                  htmlResponse(
                    switchNoticePage("!", "#d1242f", "续登未启动", "登录服务未配置，请在本窗口手动登录。"),
                    Status.Ok
                  )
              }
              // 🔴 缺陷 A 返工（round 1 / 判词 D1）：本页是**用户可见**的换号续登失败页。
              // `e.getMessage` 直出会把整条 authorize URL、PKCE `state` 值与文件系统路径
              // 一起送到用户眼前（判词探针 P7 原文：`发起登录失败：Invalid URI:
              // C:\Users\…\bad endpoint/oidc/auth?…&state=…` ⇒ 判据正则命中 2）。
              // 改走同文件既有的**分类通道**（与 `:981-990`（end-session 意外失败）/
              // `:4423-4431`（回调 `Left(e)` 支）**同形**，零新轮子）：三段式
              // （原因 + 动作 + 稳定诊断码）进页面，原始细节（异常类名 / 路径 / URL）
              // **只**进 WARN。页面的其它语义（headline / 状态码 / 单窗口续登契约）零改动。
              .handleErrorWith { e =>
                val diagnostic = nebflow.neblink.CredentialDiagnostics.classifyFailure(
                  e,
                  nebflow.neblink.CredentialFailure.Unclassified
                )
                logger.warn(diagnostic.logLine("switch continue login failed"), "code" -> diagnostic.code) *>
                  htmlResponse(
                    switchNoticePage("!", "#d1242f", "续登失败", diagnostic.message),
                    Status.Ok
                  )
              }
    }

  /**
   * The 8-step local teardown shared by POST /neblink/logout and the
   * RP-initiated end-session endpoint. Always completes locally — every
   * remote/best-effort step swallows failures.
   */
  private[gateway] def performLocalLogout(ms: NeblinkService)(using ctx: RestApiCtx): IO[Unit] =
    import ctx.*

    for
      // 1. Notify the NebLink Server: DELETE /api/device/logout lets the
      //    server clear the device session + relay tunnel immediately
      //    instead of waiting for the 90s TTL purge. Best-effort — logout
      //    must ALWAYS succeed locally, so any failure (network
      //    unreachable, server down) is swallowed. Uses the discovery's
      //    live client: enrollment hot-swap replaces it without updating
      //    ms.relayClientOpt, which may hold a stale instance.
      _ <- neblinkDiscovery.fold(IO.unit)(d =>
        d.currentClient.flatMap {
          case Some(client) =>
            client.logout.handleErrorWith(e =>
              logger.debug(s"NebLink server logout notify failed (continuing local logout): ${e.getMessage}")
            )
          case None => IO.unit
        }
      )
      // 2. Stop the relay tunnel — drops the local WS so no reconnect
      //    loop keeps the device visible server-side after logout.
      _ <- ms.relayTunnelOpt.fold(IO.unit)(t => t.stop().handleErrorWith(_ => IO.unit))
      // 3. Drop all P2P presence connections and cancel auto-reconnects —
      //    otherwise a reconnecting peer could silently re-enter the local
      //    list after logout (reconnectLoop re-upserts on success).
      _ <- ms.presenceServiceOpt.fold(IO.unit)(p => p.disconnectAll().handleErrorWith(_ => IO.unit))
      // 4. Delete the device credential file (~/.nebflow/neblink/device.json)
      _ <- DeviceCredential.clear
      // 5. Disable NebLink in config (keep neblinkServer address for next login).
      //    updateConfig refreshes the in-memory ref AND persists to disk, so
      //    /status reflects the change immediately without a restart.
      _ <- ms.updateConfig(_.copy(enabled = false))
      // 6. Stop the NebLink client (hot-swap to None). Both client pointers are
      //    cleared: the discovery one (authoritative live client) AND the
      //    NeblinkService relay pointer, which every relay consumer reads
      //    (RemoteExecutor / DropboxService / status) —
      //    leaving it behind kept a logged-out client reachable through the
      //    relay path (2026-09-10 隧道鉴权自愈批 convergence sweep).
      _ <- neblinkDiscovery.fold(IO.unit)(d => d.setClient(None))
      _ <- IO(ms.setRelayClient(None))
      // 7. Clear user info (avatar, github login) from the device identity.
      _ <- ms.updateDeviceInfo(avatarUrl = Some(""), githubLogin = Some(""))
      // 8. Clear all discovered peers.
      _ <- ms.clearPeers
    yield ()

  private def handleAuthCallback(query: Map[String, String])(using ctx: RestApiCtx): IO[org.http4s.Response[IO]] =
    import ctx.*

    // Provider error redirect (?error=...&error_description=...) — user
    // denied / hosted-page failure.
    //
    // 🔴 缺陷 A：失败面一律走**分类三段式**（上游 §8.2 第 4 项：禁 `getMessage`/原文直出）。
    // 原始串（服务方 error 码 + description，可能是任何文本）只进 WARN（带分类码 + 归因）。
    LogtoAuthCode.parseCallbackError(query) match
      case Some(cb) =>
        val raw = s"${cb.error}${cb.description.fold("")(d => s": $d")}"
        val diagnostic = nebflow.neblink.CredentialDiagnostics.diagnosticOf(
          nebflow.neblink.CredentialFailure.ProviderError,
          raw
        )
        logger.warn(diagnostic.logLine("provider error redirect"), "code" -> diagnostic.code) *>
          pkceLogin.failDiagnosed(diagnostic) *>
          htmlResponse(callbackPage(ok = false, diagnostic.message), Status.BadRequest)
      case None =>
        val code = query.getOrElse("code", "")
        val state = query.getOrElse("state", "")
        pkceLogin.take(state).flatMap {
          // None = mismatch / expired / stray hit (take already set the
          // error status for pending attempts).
          case None =>
            htmlResponse(
              callbackPage(
                ok = false,
                nebflow.neblink.CredentialDiagnostics
                  .diagnosticOf(nebflow.neblink.CredentialFailure.CallbackStateInvalid)
                  .message
              ),
              Status.BadRequest
            )
          case Some(verifier) =>
            neblinkService match
              case None =>
                val diagnostic = nebflow.neblink.CredentialDiagnostics.diagnosticOf(
                  nebflow.neblink.CredentialFailure.ServiceUnavailable,
                  "NebLink service not initialized"
                )
                pkceLogin.failDiagnosed(diagnostic) *>
                  htmlResponse(callbackPage(ok = false, diagnostic.message), Status.InternalServerError)
              case Some(ms) =>
                for
                  // Same resolution as /api/neblink/auth/start: explicit logto
                  // block wins, otherwise the embedded production default.
                  // The raw `_.logto` read here previously broke the PKCE
                  // chain for fresh installs (start succeeded via the
                  // embedded default, then the callback died with a
                  // misleading "Logto 登录未配置" — 2026-08-30).
                  logto <- ms.neblinkConfig.map(_.effectiveLogto)
                  target <- neblinkServerUrl(None)
                  // 案 b①（2026-09-20）：目标解析先于换码 —— `None`（隔离数据根 + 无显式开关
                  // + 无配置 URL）⇒ 整条 PKCE 腿在**换码之前**失败，环 5 的 `register`
                  // 从此没有起点。两个缺席原因（无目标 / 无 AC 应用）在此合流为 `None`，
                  // 由下面的 `case None =>` 按 `target.isEmpty` 各归其类。
                  resp <- (
                    for
                      serverUrl <- target
                      client <- logto.flatMap(lc => lc.pkceClientId.map(pkce => (lc, pkce)))
                    yield (serverUrl, client)
                  ) match
                    case Some((serverUrl, (lc, pkceClientId))) =>
                      LogtoAuthCode
                        .tokenCall(LogtoDeviceFlow.jdkSend)(
                          LogtoAuthCode.tokenRequest(
                            lc.endpoint,
                            pkceClientId,
                            loopbackCallbackUri,
                            code,
                            verifier
                          )
                        )
                        .flatMap {
                          case Right(tokens) =>
                            ms.identity.flatMap { identity =>
                              // C2 (2026-09-01 login-chain fix): id_token picture
                              // → device identity avatarUrl immediately (Logto
                              // users get an avatar without waiting for the
                              // enroll response; neblink-server avatar sync is
                              // the C1 half, this is the client-side half).
                              val pictureWrite = tokens.picture match
                                case Some(pic) if pic.nonEmpty =>
                                  ms.updateDeviceInfo(avatarUrl = Some(pic))
                                case _ => IO.unit
                              pictureWrite *>
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
                                      // 案 C（2026-09-14 作者裁定 C+B·客户端一刀）：
                                      // 显式登录放行 + 失败透真因。
                                      // ①(b) 机械判据：`explicitUserAction = true` 只在
                                      // 这里给出，而这里的唯一入口是上面
                                      // `pkceLogin.take(state)` 命中 —— 即「回调携带的
                                      // state 命中了一次性、进程内、由 POST
                                      // /api/neblink/auth/start（登录按钮的端点）建立的
                                      // 待决登录尝试」。自动路径不可能携带它：boot 客户端
                                      // 与 silent re-login 从不调 /auth/start，也从不经过
                                      // /auth/callback；该标记 take 一次即消费、15min 过期
                                      // （PkceLoginSession.ExpiryMs），且 pkceLogin 是进程内
                                      // 单飞槽（`start` 的唯一调用点 = auth/start 路由）。
                                      // ①(a) 真因：Left 原文（护栏拒绝 ⇒ EnrollGuard 的
                                      // reason 文本）上页面与 /auth/state 面板，不再吞成
                                      // 「设备注册未完成」。
                                      completeDeviceEnrollmentDetailed(
                                        ms,
                                        serverUrl,
                                        json,
                                        tokens.refreshToken,
                                        tokens.idToken,
                                        explicitUserAction = true
                                      ).attempt
                                        .flatMap {
                                          case Right(Right(_)) =>
                                            pkceLogin.succeed *> htmlResponse(callbackPage(ok = true, ""), Status.Ok)
                                          // 🔴 缺陷 A：`Right(Left(err))` 与 `Left(e)` 两支都改走
                                          // **分类映射**（上游 §8.2 第 4 项），`getMessage` / 自由串
                                          // 一律不进用户可见面。
                                          //  · `err` 是左通道自由串 —— 逐字符反查分类（`persist` 出来的
                                          //    凭据失败/服务端缺凭据都已是三段式文案），护栏拒绝则按
                                          //    既有的逐字符相等判据给 `enroll-refused-isolated-home`
                                          //    （案 C 语义：真因照实透出，护栏文本本身干净）。
                                          case Right(Left(err)) =>
                                            val diagnostic = classifyEnrollFailure(serverUrl, err)
                                            pkceLogin.failDiagnosed(diagnostic) *>
                                              htmlResponse(
                                                callbackPage(ok = false, diagnostic.message),
                                                Status.BadGateway
                                              )
                                          case Left(e) =>
                                            val diagnostic = nebflow.neblink.CredentialDiagnostics.classifyFailure(
                                              e,
                                              nebflow.neblink.CredentialFailure.Unclassified
                                            )
                                            logger.warn(
                                              diagnostic.logLine("auth callback enrollment failed"),
                                              "code" -> diagnostic.code
                                            ) *>
                                              pkceLogin.failDiagnosed(diagnostic) *>
                                              htmlResponse(
                                                callbackPage(ok = false, diagnostic.message),
                                                Status.InternalServerError
                                              )
                                        }
                                    case Left(err) =>
                                      val diagnostic = nebflow.neblink.CredentialDiagnostics.diagnosticOf(
                                        nebflow.neblink.CredentialFailure.DeviceRegisterFailed,
                                        err
                                      )
                                      logger.warn(
                                        diagnostic.logLine("device register failed"),
                                        "code" -> diagnostic.code
                                      ) *>
                                        pkceLogin.failDiagnosed(diagnostic) *>
                                        htmlResponse(callbackPage(ok = false, diagnostic.message), Status.BadGateway)
                                  }
                            }
                          case Left(err) =>
                            val diagnostic = nebflow.neblink.CredentialDiagnostics.diagnosticOf(
                              nebflow.neblink.CredentialFailure.TokenExchangeFailed,
                              err
                            )
                            logger.warn(diagnostic.logLine("token exchange failed"), "code" -> diagnostic.code) *>
                              pkceLogin.failDiagnosed(diagnostic) *>
                              htmlResponse(callbackPage(ok = false, diagnostic.message), Status.BadRequest)
                        }
                    case None =>
                      if target.isEmpty then
                        // 案 b①：无目标 —— 隔离实例不得把生产默认当入网目标。失败文案 =
                        // 护栏原文（`EnrollRefusedIsolatedHome` 的例外分支把**干净**原文
                        // 逐字送上回调页与 /auth/state 面板；见 CredentialDiagnostics）。
                        val diagnostic = nebflow.neblink.CredentialDiagnostics.diagnosticOf(
                          CredentialFailure.EnrollRefusedIsolatedHome,
                          EnrollGuard.prodFallbackRefusalReason
                        )
                        logger.warn(
                          diagnostic.logLine("no enrollment target (案 b①)"),
                          "code" -> diagnostic.code
                        ) *>
                          pkceLogin.failDiagnosed(diagnostic) *>
                          htmlResponse(callbackPage(ok = false, diagnostic.message), Status.BadRequest)
                      else
                        // With effectiveLogto this only fires when an explicit
                        // logto block exists but lacks pkceClientId (the
                        // embedded default carries one; a missing block falls
                        // back to it). Name the actual misconfiguration.
                        val diagnostic = nebflow.neblink.CredentialDiagnostics.diagnosticOf(
                          nebflow.neblink.CredentialFailure.LogtoNotConfigured,
                          "logto-pkce-client-not-configured"
                        )
                        pkceLogin.failDiagnosed(diagnostic) *>
                          htmlResponse(callbackPage(ok = false, diagnostic.message), Status.NotFound)
                yield resp
        }

  /**
   * 案 C ①(a)（2026-09-14）语义的**分类化**承接（缺陷 A / 上游 §8.2 第 4 项）：
   * 回调页与 `/auth/state` 的失败文案不再直出自由串，而是走
   * `CredentialDiagnostics` 的分类映射 —— 三段式（原因 + 动作 + 稳定诊断码）。
   *
   * 两条判据逐字保留自修前实现：
   *  - **隔离护栏拒绝**：`EnrollGuard.enrollRefusal(serverUrl)` 的 reason 是
   *    `(serverUrl, 开关)` 的纯函数（同输入同输出）⇒ 与 `err` **逐字符相等**即判定为护栏
   *    拒绝，翻译成 `enroll-refused-isolated-home`；护栏原文**照实透出**（案 C：失败透真因，
   *    且该文本本身不含路径/异常类名）。
   *  - **其余**：先按可见三段式**反查**（`persist` 出来的凭据失败/服务端缺凭据都已是分类
   *    文案）⇒ 同一个稳定 code 回到结构化通道；反查不中 ⇒ 兜底分类 + 原文进**日志**。
   */
  private def classifyEnrollFailure(
    serverUrl: String,
    err: String
  ): nebflow.neblink.CredentialDiagnostics.Diagnostic =
    import nebflow.neblink.{CredentialDiagnostics as CD, CredentialFailure as CF}
    nebflow.neblink.EnrollGuard.enrollRefusal(serverUrl) match
      case Some(reason) if reason == err => CD.diagnosticOf(CF.EnrollRefusedIsolatedHome, err)
      case _ =>
        CD.byVisibleMessage(err).map(f => CD.diagnosticOf(f)).getOrElse(CD.diagnosticOf(CF.Unclassified, err))

  /**
   * Static loopback login result page (success + error variants). The
   * frontend learns the outcome by polling /api/neblink/auth/state.
   */
  private[gateway] def callbackPage(ok: Boolean, message: String): String =
    val headline = if ok then "登录成功，可关闭本页" else "登录失败"
    val detail = if ok then "nebflow 账号已连接，本窗口可以关闭" else message
    val icon = if ok then "✓" else "✕"
    val iconColor = if ok then "#07c160" else "#d1242f"
    s"""<!doctype html>
       |<html lang="zh-CN"><head><meta charset="utf-8">
       |<meta name="viewport" content="width=device-width,initial-scale=1">
       |<title>nebflow 登录</title>
       |<style>body{font-family:-apple-system,'Segoe UI','PingFang SC',sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;background:#f6f7f9;color:#1f2328}
       |.card{text-align:center;padding:44px 52px;border-radius:14px;background:#fff;box-shadow:0 2px 14px rgba(0,0,0,.08)}
       |.icon{color:$iconColor;font-size:42px;line-height:1;margin-bottom:10px}h1{font-size:18px;font-weight:600;margin:0 0 8px}
       |p{color:#6a737d;font-size:13px;margin:0;max-width:320px;word-break:break-all}</style></head>
       |<body><div class="card"><div class="icon">$icon</div><h1>$headline</h1><p>$detail</p></div></body></html>""".stripMargin

  /**
   * RP-logout landing page (post_logout_redirect_uri target, 2026-09-06).
   * Same visual skeleton as callbackPage — a static result card.
   * 🔴 UNCHANGED by the one-window switch batch (2026-09-16): the plain-logout
   * landing must stay byte-identical, so the switch-continuation cards are a
   * separate builder ([[switchNoticePage]]) rather than a parameterisation of
   * this one.
   */
  private def loggedOutPage: String =
    s"""<!doctype html>
       |<html lang="zh-CN"><head><meta charset="utf-8">
       |<meta name="viewport" content="width=device-width,initial-scale=1">
       |<title>nebflow 已退出登录</title>
       |<style>body{font-family:-apple-system,'Segoe UI','PingFang SC',sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;background:#f6f7f9;color:#1f2328}
       |.card{text-align:center;padding:44px 52px;border-radius:14px;background:#fff;box-shadow:0 2px 14px rgba(0,0,0,.08)}
       |.icon{color:#07c160;font-size:42px;line-height:1;margin-bottom:10px}h1{font-size:18px;font-weight:600;margin:0 0 8px}
       |p{color:#6a737d;font-size:13px;margin:0;max-width:320px;word-break:break-all}</style></head>
       |<body><div class="card"><div class="icon">✓</div><h1>已退出登录</h1><p>已在浏览器中退出 nebflow 账号，本页可以关闭</p></div></body></html>""".stripMargin

  /**
   * Switch-account continuation notice card (one-window switch, 2026-09-16) —
   * the visible face of a handoff that did NOT continue into a login
   * (replayed / expired / failed to start). Same skeleton, class names and
   * literal values as [[loggedOutPage]] (no new colour literals: `#07c160`
   * and `#d1242f` are the two icon colours already used in this file).
   */
  private def switchNoticePage(symbol: String, symbolColor: String, headline: String, detail: String): String =
    s"""<!doctype html>
       |<html lang="zh-CN"><head><meta charset="utf-8">
       |<meta name="viewport" content="width=device-width,initial-scale=1">
       |<title>nebflow 切换账号</title>
       |<style>body{font-family:-apple-system,'Segoe UI','PingFang SC',sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;background:#f6f7f9;color:#1f2328}
       |.card{text-align:center;padding:44px 52px;border-radius:14px;background:#fff;box-shadow:0 2px 14px rgba(0,0,0,.08)}
       |.icon{color:$symbolColor;font-size:42px;line-height:1;margin-bottom:10px}h1{font-size:18px;font-weight:600;margin:0 0 8px}
       |p{color:#6a737d;font-size:13px;margin:0;max-width:320px;word-break:break-all}</style></head>
       |<body><div class="card"><div class="icon">$symbol</div><h1>$headline</h1><p>$detail</p></div></body></html>""".stripMargin

  /**
   * HTML response without circe's String-entity hijack (explicit bytes +
   * content type + length).
   */
  private[gateway] def htmlResponse(markup: String, status: Status): IO[org.http4s.Response[IO]] =
    val bytes = markup.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    IO.pure(
      org.http4s
        .Response(status = status)
        .withHeaders(
          Headers(
            `Content-Type`(MediaType.text.html, Charset.`UTF-8`),
            org.http4s.headers.`Content-Length`.unsafeFromLong(bytes.length.toLong)
          )
        )
        .withBodyStream(Stream.emits(bytes))
    )

end AuthRoutes
