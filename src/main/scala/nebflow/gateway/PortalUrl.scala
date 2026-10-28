package nebflow.gateway

import cats.effect.IO
import nebflow.neblink.{DeviceIdentity, NeblinkConfig}
import nebflow.shared.Branding

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Browser entry resolution. The website portal is independent of the relay
 * server. Explicit portal overrides and local mode work on every device.
 */
object PortalUrl:

  /** env 名（全形，含 NEBFLOW_ 前缀；读取经 Branding.env 双前缀）。 */
  val PortalUrlEnv: String = "NEBFLOW_PORTAL_URL"
  val OpenUiEnv: String = "NEBFLOW_OPEN_UI"
  private val OpenUiLocalValue: String = "local"

  /** Default browser portal from brand.conf; never the relay server URL. */
  def defaultPortalUrl: String = Branding.portalUrl

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

  /** Resolve the website portal, or establish a local browser session.
   * Configuration/identity read failures leave local access available.
   */
  def startupUrl(deviceId: IO[Option[String]], localUrl: String, authenticateLocal: Boolean = true): IO[String] =
    deviceId
      .flatMap(id => openUrl(id))
      .handleError { _ => None }
      .flatMap {
        case Some(portal) => IO.pure(portal)
        case None if authenticateLocal => LocalConsoleUrl.authenticated(localUrl)
        case None => IO.pure(localUrl)
      }

  /** Focus uses only the persisted identity; never mint an identity on relaunch. */
  def focusUrl(localUrl: String, authenticateLocal: Boolean = true): IO[String] =
    startupUrl(IO.blocking(DeviceIdentity.readDeviceIdOnly), localUrl, authenticateLocal)

end PortalUrl
