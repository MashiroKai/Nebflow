package nebflow.gateway

import cats.effect.IO
import com.comcast.ip4s.{Host, Port}
import io.circe.parser.parse
import nebflow.shared.Defaults
import os.Path

case class GatewayConfig(host: Host, port: Port)

object GatewayConfig:
  private val DefaultHost: Host = Host.fromString("0.0.0.0").get
  // 绑定兜底口：品牌感知单源 Defaults.brandDefaultPort（双生分家批 2026-10-31）——
  // stand 构建 6138 / nebflow 构建 6140；解析链 --port > env > config "port" > 本值，
  // 见 resolvePort。private[nebflow] 仅为本默认值的 spec 钉可见（GatewayPortDefaultSpec）。
  private[nebflow] val DefaultPort: Port = Port.fromInt(Defaults.brandDefaultPort).get

  /** CLI --port override, takes priority over env var and default. */
  private var _portOverride: Option[Int] = None

  def setPort(port: Int): Unit = _portOverride = Some(port)

  /** When true, skip opening the browser on startup (used by auto-start). */
  @volatile private var _noBrowser: Boolean = false

  def setNoBrowser(v: Boolean): Unit = _noBrowser = v
  def noBrowser: Boolean = _noBrowser

  /**
   * 配置文件显式 `port` 键（双生分家批 2026-10-31，作者裁定「config 支持显式
   * port 键并写进文档」）：读 `<dataRoot>/<configFileName>`（`nebflow.json`，
   * dual-read 惯例经 `Config.DefaultConfigPath`）顶层的**JSON 数字** `port`。
   *
   * 容忍纪律：文件缺失 / 空 / `{}` / 非法 JSON / 非数字（含数字字符串——circe
   * 对 `"6199"` 这类字面宽容解码，此处显式只认 `asNumber`，与文档「整数」口径
   * 一致）/ 越界 —— 一律 `None` 回落下一链腿，**绝不抛错**（端口门是启动前第
   * 一道闸，配置面的故障由稍后的 `Config.loadServiceConfig` 大声报出，不在此
   * 提前炸）。越界/零值由 [[resolvePort]] 的 `Port.fromInt` + 正值闸丢弃。
   */
  private[nebflow] def configFilePort(): Option[Int] =
    try
      val path = nebflow.shared.Config.DefaultConfigPath
      if !os.exists(path) then None
      else
        val raw = os.read(path).trim
        if raw.isEmpty || raw == "{}" then None
        else
          parse(raw).toOption
            .flatMap(_.hcursor.downField("port").focus.flatMap(_.asNumber).flatMap(_.toInt))
    catch case _: Exception => None

  /**
   * 端口解析链**纯核心**（spec 直接驱全矩阵，双生分家批 2026-10-31 收口）：
   *
   *    `--port`（CLI 全局旗标）＞ `GATEWAY_PORT` env（品牌双前缀，经
   *    `Branding.env`）＞ config `port` 键 ＞ 品牌缺省口（[[Defaults.brandDefaultPort]]）
   *
   * 任一腿的取值非法即跳过该腿落下一腿（非数字 / 越界 / **零**——ip4s
   * `Port.fromInt(0)` 合法但绑 0 = 随机临时口，网关将不可达，故正值闸统一拒绝）；
   * 全沉默才到兜底。`load` 与本解析的唯一实现 —— CLI 侧 `GatewayClient.readPort`
   * 同链同序。
   */
  private def bindable(p: Port): Option[Port] = if p.value > 0 then Some(p) else None

  private[nebflow] def resolvePort(
      cliOverride: Option[Int],
      envValue: Option[String],
      configPort: Option[Int]
  ): Port =
    cliOverride
      .flatMap(Port.fromInt)
      .flatMap(bindable)
      .orElse(envValue.flatMap(_.toIntOption).flatMap(Port.fromInt).flatMap(bindable))
      .orElse(configPort.flatMap(Port.fromInt).flatMap(bindable))
      .getOrElse(DefaultPort)

  def load: IO[GatewayConfig] = IO.delay {
    // L3 rebrand compat: dual-prefix env read (brand prefix first, legacy
    // NEBFLOW_ fallback) — identical prefixes collapse to one variable.
    val host = nebflow.shared.Branding
      .env("GATEWAY_HOST")
      .flatMap(Host.fromString)
      .getOrElse(DefaultHost)
    val port = resolvePort(_portOverride, nebflow.shared.Branding.env("GATEWAY_PORT"), configFilePort())
    GatewayConfig(host, port)
  }
end GatewayConfig
