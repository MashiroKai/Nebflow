package nebflow.neblink

import nebflow.core.Branding

import java.net.URI

/**
  * Isolation guard for automatic enrollment (2026-09-11 作者裁定).
  *
  * Device ids are now machine-code derived (see `DeviceIdentity`), so a
  * redirected data root (`--home /tmp/qa-x`, `<PREFIX>_HOME=…`) deliberately
  * derives a *different* id than the author's main client — the two do not share
  * an identity. That alone, however, does not stop a throwaway instance from
  * registering a brand-new device row in the PRODUCTION network, which is how
  * `/tmp/nebflow-coldstart` polluted the production device table on 2026-09-11
  * (dup-device-verdict §1.2 类 2, P1).
  *
  * Policy (avoids any behaviour change on the default data root):
  *   (non-default data root) AND (target host is inside the production domain)
  *   AND (no explicit switch)  ⇒  refuse the *automatic* enroll/join, with a
  *   loud log — never silently.
  *
  * The switch is `NEBFLOW_ALLOW_PROD_ENROLL=1` (also `=true` / `=yes`), read via
  * [[Branding.env]] (brand prefix, with the hardcoded NEBFLOW_ prefix as the
  * fallback — same name today). With it set, behaviour is exactly as before.
  *
  * Local / custom hosts are unaffected (small blast radius): the gate only
  * fires for the production domain.
  *
  * Explicit-user-action release (2026-09-14 作者裁定「案 C」): the gate exists to
  * stop **automatic** enrollment side effects on a redirected data root — it was
  * never meant to refuse a login the user started by hand. A caller that can
  * PROVE the user initiated the login (`explicitUserAction = true`) is let
  * through; the gate's own semantics are untouched for every other path (boot,
  * silent re-login, device-flow poll, tests), which all pass `false`.
  *
  * The proof is mechanical, not a convention — see
  * `RestApiRoutes.handleAuthCallback`: the flag is set only downstream of
  * `PkceLoginSession.take(state)` returning `Some`, i.e. the loopback callback
  * matched a single-use in-memory attempt created by `POST /api/neblink/auth/start`
  * (the login button's endpoint). An automatic path cannot carry that marker:
  * it never calls `/auth/start`, and the marker is consumed on first use and
  * expires after 15 min (`PkceLoginSession.ExpiryMs`).
  */
object EnrollGuard:

  /** The one explicit escape hatch. Env var, no config surface (it is an
    * operator/test knob, not a user setting). */
  val AllowProdEnrollEnv: String = "NEBFLOW_ALLOW_PROD_ENROLL"

  /** Production domain; subdomains included. */
  private val ProdDomain: String = "nebflow.space"

  /** True when `url` points into the production domain (subdomains included).
    *
    * Host is taken from the URI; when the string is not URI-parseable (custom
    * schemes etc.) a literal containment check keeps the gate closed instead of
    * silently opening it. */
  private[neblink] def isProdHost(url: String): Boolean =
    val raw = Option(url).map(_.trim).getOrElse("")
    if raw.isEmpty then false
    else
      hostOf(raw) match
        case Some(h) => h == ProdDomain || h.endsWith("." + ProdDomain)
        case None    => raw.contains(ProdDomain)

  private[neblink] def hostOf(url: String): Option[String] =
    try
      val withScheme = if url.contains("://") then url else s"https://$url"
      Option(URI.create(withScheme).getHost).map(_.toLowerCase).filter(_.nonEmpty)
    catch case _: Exception => None

  /** Live decision — reads the current data root and the explicit switch.
    * Automatic paths (boot client, silent re-login, device-flow poll) use this
    * and are gated exactly as before. */
  def enrollRefusal(serverUrl: String): Option[String] =
    enrollRefusal(serverUrl, DeviceIdentity.isNonDefaultHome, explicitAllowEnv)

  /** Live decision with the explicit-user-action release (案 C ①(b), 2026-09-14).
    *
    * `explicitUserAction = true` ⇒ the gate returns `None` (allowed). Only the
    * PKCE loopback callback sets it, and only after the single-use state marker
    * proved the user drove the login (see the object doc). */
  def enrollRefusal(serverUrl: String, explicitUserAction: Boolean): Option[String] =
    enrollRefusal(serverUrl, DeviceIdentity.isNonDefaultHome, explicitAllowEnv, explicitUserAction)

  /** Pure decision core (testable): `Some(reason)` = the automatic enroll must
    * be refused; `None` = proceed exactly as before. `explicitUserAction`
    * defaults to `false` so the pre-案-C call shape keeps its old semantics. */
  def enrollRefusal(
    serverUrl: String,
    nonDefaultHome: Boolean,
    explicitAllow: Boolean,
    explicitUserAction: Boolean = false
  ): Option[String] =
    if explicitUserAction then None
    else if !nonDefaultHome then None
    else if explicitAllow then None
    else if !isProdHost(serverUrl) then None
    else
      Some(
        s"refused: this instance runs on an isolated data root, so it must not auto-register " +
          s"with the production NebLink server ($serverUrl). Set $AllowProdEnrollEnv=1 to allow " +
          s"it explicitly (same account as another instance ⇒ one live session, the older one is kicked)."
      )

  // ── 案 b①（2026-09-20 作者令 · 测试卫生）：生产默认目标不得被隔离实例继承 ──────
  //
  // 事故链（核查卡 `20260920_214729_seedpath-card__chain-test-hygiene.md` §2 环 3）：
  // `/tmp/nebflow-coldstart`（:8097，`--home` 隔离）**没有**任何显式/配置服务端 URL，
  // 于是登录/注册入口的**末级回落**把 `Branding.serverUrl`（生产真值）当成了目标 ——
  // 环 4 案 C 显式登录放行 → 环 5 `POST /api/device/register` 在生产网新增设备行。
  // 与 `enrollRefusal`（拦「已解析出的目标」）不同，本判据拦的是**目标本身**：
  // 无显式 URL ⇒ 隔离实例**拿不到**生产默认目标（`None`），调用点据此给可见失败 ——
  // 而失败文案必须**不含**数据根路径 / 文件名 / `.nebflow` 字面量，因为它会经
  // `CredentialDiagnostics` 的例外分支（`EnrollRefusedIsolatedHome`）**逐字进用户可见面**。
  //
  // 误伤面（与案 a/案 C 同构，刻意最小）：只判「**回落生产默认**」这一段 ——
  // 显式 URL（请求体 / 配置）与非默认 home 之外的一切路径零影响；要拿隔离实例登
  // 生产账号的操作员通道 = `NEBFLOW_ALLOW_PROD_ENROLL=1`（放行后与改前逐字相同）。

  /** Live decision: `Some(reason)` = a redirected data root with no explicit
    * switch must NOT inherit the production default as its enrollment target. */
  def prodFallbackRefusal: Option[String] =
    prodFallbackRefusal(DeviceIdentity.isNonDefaultHome, explicitAllowEnv)

  /** Pure decision core (testable): default data root ⇒ `None`; redirected data
    * root + explicit switch ⇒ `None`; redirected without the switch ⇒ the
    * reason (which IS the user-visible text — see [[prodFallbackRefusalReason]]). */
  def prodFallbackRefusal(nonDefaultHome: Boolean, explicitAllow: Boolean): Option[String] =
    if !nonDefaultHome then None
    else if explicitAllow then None
    else Some(prodFallbackRefusalReason)

  /** The single message source for the suppressed fallback (案 b① 的「一条文案」）。
    *
    * 🔴 必须保持「干净」（无数据根路径 / 无文件名 / 无 `.nebflow` 字面量 / 无 Java
    * 类名）：`CredentialDiagnostics.diagnosticOf` 只在 detail 通过 `isCleanVisibleText`
    * 时才把 `EnrollRefusedIsolatedHome` 的原文透出到登录页 —— 带上真实域名
    * （`neblink.nebflow.space` 含 `.nebflow` 字面量）就会静默降级成模板文案。
    * 由 `EnrollGuardExplicitLoginSpec` 的负控断言钉住。 */
  def prodFallbackRefusalReason: String =
    "refused: this instance runs on an isolated data root, so it does not fall back to the " +
      "production NebLink server default; give an explicit server URL, or set " +
      s"$AllowProdEnrollEnv=1 to allow the production default explicitly"

  /** The production default enrollment target, or `None` when the isolation
    * policy forbids inheriting it (案 b①).
    *
    * **单点判据**：登录/注册的目标解析（`RestApiRoutes.neblinkServerUrl` 的末级回落、
    * 启动客户端的 silent-relogin 接缝）一律经这里取默认目标，禁各写一份
    * （本仓「第二实现」缺陷族）。 */
  def prodDefaultTarget: Option[String] =
    prodFallbackRefusal match
      case None    => Some(Branding.serverUrl)
      case Some(_) => None

  /** Parsed explicit switch: `1` / `true` / `yes` (case-insensitive).
    * `Branding.env` takes the bare suffix and prepends the brand prefix, so this
    * reads `NEBFLOW_ALLOW_PROD_ENROLL` today. */
  private[neblink] def explicitAllowEnv: Boolean =
    Branding
      .env(AllowProdEnrollEnv.stripPrefix("NEBFLOW_"))
      .map(_.trim.toLowerCase)
      .exists(v => v == "1" || v == "true" || v == "yes")

end EnrollGuard
