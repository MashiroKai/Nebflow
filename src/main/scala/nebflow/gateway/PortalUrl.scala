package nebflow.gateway

import cats.effect.IO
import nebflow.neblink.NeblinkConfig
import nebflow.shared.Branding

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 门户开门 URL 解析单点（neblink portal MVP，2026-10-10，方案 §3.2 E2）。
 *
 * 解析优先级（降序）：
 *   ① `NEBFLOW_PORTAL_URL` env（[[Branding.env]] 双前缀惯例，裸后缀 `PORTAL_URL`）；
 *   ② `<dataRoot>/neblink/config.json` 顶层 `portalUrl`（自托管覆盖位）；
 *   ③ 生产缺省 = `Branding.serverUrl`（今日真值 `https://neblink.nebflow.space`——
 *      方案 §7 冻结常量；引用单源而不重抄字面量，重命名日只改 brand.conf）。
 *
 * 两个回落口（都回落到调用方的 localhost 既有行为）：
 *   · `NEBFLOW_OPEN_UI=local`（大小写不敏感）⇒ [[decide]] 返回 None —— 开发态逃生口
 *     （方案 R4；与既有 `--no-browser`/headless 正交）；
 *   · config `portalUrl` 为空串/全空白 ⇒ 显式关门户 ⇒ None（本批任务书：
 *     「localhost 直开保留为开发态，配置关门户时回落」）。
 *
 * 出口 URL = base + `?device=<deviceId>`（会话跳转参数，方案 §3.0/§7 冻结形态；
 * 仅在调用方给得出 deviceId 时携带；`?`/`&` 边界按 base 现状；deviceId URL 编码）。
 * 🔴 G.14 MVP 边界：**不**透传网关令牌（`?token=` 上门户 URL 会落访问日志查询串，
 * 违反阶段 2 验收线——见方案 §3.4）。
 *
 * 隔离边界（有意不挂 EnrollGuard）：开门 = 浏览器导航，零本地写、零注册副作用；
 * 案 a / 案 b① 拦的是入网注册**写面**（见 EnrollGuard 文档）。隔离实例照常开门
 * （可由 `NEBFLOW_OPEN_UI=local` 或 config `""` 关闭）。该边界由 PortalUrlSpec 钉住。
 */
object PortalUrl:

  /** env 名（全形，含 NEBFLOW_ 前缀；读取经 Branding.env 双前缀）。 */
  val PortalUrlEnv: String = "NEBFLOW_PORTAL_URL"
  val OpenUiEnv: String = "NEBFLOW_OPEN_UI"
  private val OpenUiLocalValue: String = "local"

  /** 生产缺省门户（`Branding.serverUrl` 单源；方案冻结常量的今日真值）。 */
  def defaultPortalUrl: String = Branding.serverUrl

  /** 纯判据：`NEBFLOW_OPEN_UI=local` ⇒ 逃生口开（回落 localhost）。 */
  def isOpenUiLocal(openUiEnv: Option[String]): Boolean =
    openUiEnv.exists(_.trim.equalsIgnoreCase(OpenUiLocalValue))

  /**
   * 纯解析核（env 注入式，可测）：`Some(url)` = 开门户；`None` = 调用方回落
   * localhost 现状。
   */
  def decide(
    portalUrlConfig: Option[String],
    portalEnv: Option[String],
    openUiEnv: Option[String],
    deviceId: Option[String]
  ): Option[String] =
    if isOpenUiLocal(openUiEnv) then None
    else
      val explicit = portalEnv.map(_.trim).filter(_.nonEmpty).orElse(portalUrlConfig.map(_.trim))
      explicit match
        case Some("") => None // config/env 显式空 = 关门户 → localhost（开发态）
        case Some(base) => Some(withDeviceParam(base, deviceId))
        case None => Some(withDeviceParam(defaultPortalUrl, deviceId))

  /** base + `?device=<id>`（或 `&`，当 base 已带 query）；deviceId 空 ⇒ base 原样。
    * 编码口径同 RestApiCtx.encSeg 先例：URLEncoder 后把 `+` 归一 `%20`
    * （form-urlencoded 的 `+` 在通用 URL 解码面会碎）。 */
  def withDeviceParam(base: String, deviceId: Option[String]): String =
    deviceId
      .map(_.trim)
      .filter(_.nonEmpty)
      .fold(base) { id =>
        val enc = URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20")
        val sep = if base.contains("?") then "&" else "?"
        s"$base${sep}device=$enc"
      }

  // ── live 面（读真 env + 真 config）───────────────────────────────────────

  private def livePortalEnv: Option[String] = Branding.env(PortalUrlEnv.stripPrefix("NEBFLOW_"))
  private def liveOpenUiEnv: Option[String] = Branding.env(OpenUiEnv.stripPrefix("NEBFLOW_"))

  /** Live 解析：`Some(portalUrl)` = 开门户；None = 回落 localhost。 */
  def openUrl(deviceId: Option[String]): IO[Option[String]] =
    NeblinkConfig.load.map(c => decide(c.portalUrl, livePortalEnv, liveOpenUiEnv, deviceId))

  /**
   * 启动开门目标（GatewayMain :1640 调用点）：门户命中 ⇒ 门户 URL（带 device
   * 参数），否则 localhost 既有 url 原样。deviceId 读失败降级为不带参数开门
   * （身份件读不开不该把用户拦在本地）。config 读失败同降级（handleError）——
   * 门户是增强件，绝不为它打断启动链。
   */
  def startupUrl(deviceId: IO[Option[String]], localUrl: String): IO[String] =
    deviceId
      .flatMap(id => openUrl(id))
      .handleError { _ => None }
      .map(_.getOrElse(localUrl))

  /**
   * 聚焦既有实例目标（Main 两处 focus 调用点）：同 [[startupUrl]] 但**不带**
   * device 参数——聚焦路径在 guard 快道上，不值得为拼参数读身份件（可能触发
   * 身份件铸造写盘）。
   */
  def focusUrl(localUrl: String): IO[String] =
    openUrl(None).handleError(_ => None).map(_.getOrElse(localUrl))

end PortalUrl
