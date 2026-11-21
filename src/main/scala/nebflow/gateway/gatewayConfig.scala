package nebflow.gateway

import cats.effect.IO
import com.comcast.ip4s.{Host, Port}
import nebflow.shared.Defaults
import os.Path

case class GatewayConfig(host: Host, port: Port, portVia: String = "brand default port")

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
    nebflow.shared.Config.configFilePort()

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
    resolvePortVerdict(cliOverride, envValue, configPort, overridden = false) match
      case nebflow.shared.InstanceScope.PortVerdict.Resolved(p, _, _) => Port.fromInt(p).get
      case nebflow.shared.InstanceScope.PortVerdict.Refused(_) => DefaultPort

  /**
   * 端口解析链的**唯一实现**（隔离实例误伤批 2026-11-04 §2 收口）：
   * 委托 [[nebflow.shared.InstanceScope.decide]]——与 CLI 侧 `GatewayClient.readPort`
   * 同链同序（单一口径，不另抄一份）。`overridden` = 数据根不是默认根：
   *   · 默认根：`--port` > env > config `port` > 品牌缺省口（历史行为逐字不变）；
   *   · 被覆盖（隔离实例）：`--port` > 该根的 config `port` > **拒绝**——
   *     继承来的 env 端口不算显式（WARN 点名），品牌缺省口绝不静默兜底。
   */
  private[nebflow] def resolvePortVerdict(
    cliOverride: Option[Int],
    envValue: Option[String],
    configPort: Option[Int],
    overridden: Boolean
  ): nebflow.shared.InstanceScope.PortVerdict =
    nebflow.shared.InstanceScope.decide(
      nebflow.shared.InstanceScope.PortInputs(
        cliPort = cliOverride,
        envPortName = s"${nebflow.shared.Branding.envPrefix}_GATEWAY_PORT",
        envPort = envValue,
        configPort = configPort,
        sourceName = nebflow.shared.Branding.configFileName,
        brandDefaultPort = Defaults.brandDefaultPort,
        root = nebflow.shared.PathUtil.dataRoot,
        overridden = overridden
      )
    )

  private def build(): GatewayConfig =
    // L3 rebrand compat: dual-prefix env read (brand prefix first, legacy
    // NEBFLOW_ fallback) — identical prefixes collapse to one variable.
    val host = nebflow.shared.Branding
      .env("GATEWAY_HOST")
      .flatMap(Host.fromString)
      .getOrElse(DefaultHost)
    val caller = nebflow.shared.InstanceScope.liveCaller
    resolvePortVerdict(
      _portOverride,
      nebflow.shared.Branding.env("GATEWAY_PORT"),
      configFilePort(),
      caller.overridden
    ) match
      case nebflow.shared.InstanceScope.PortVerdict.Resolved(p, via, warnings) =>
        warnings.foreach(w => System.err.println(w))
        GatewayConfig(host, Port.fromInt(p).get, via)
      case nebflow.shared.InstanceScope.PortVerdict.Refused(reason) =>
        // fail-closed：隔离实例没有自己的端口声明时绝不抢品牌缺省口。
        throw new nebflow.shared.PortRefusal(reason)
  end build

  def load: IO[GatewayConfig] = IO.delay(build())
end GatewayConfig
