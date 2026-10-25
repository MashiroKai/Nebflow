package nebflow.gateway

import cats.effect.IO
import com.comcast.ip4s.{Host, Port}
import nebflow.shared.Defaults

case class GatewayConfig(host: Host, port: Port)

object GatewayConfig:
  private val DefaultHost: Host = Host.fromString("0.0.0.0").get
  // 绑定默认口：单一来源 Defaults.GatewayPort（端口对调批 2026-10-25，8080→6138）。
  // private[nebflow] 仅为本默认值的 spec 钉可见（GatewayPortDefaultSpec）。
  private[nebflow] val DefaultPort: Port = Port.fromInt(Defaults.GatewayPort).get

  /** CLI --port override, takes priority over env var and default. */
  private var _portOverride: Option[Int] = None

  def setPort(port: Int): Unit = _portOverride = Some(port)

  /** When true, skip opening the browser on startup (used by auto-start). */
  @volatile private var _noBrowser: Boolean = false

  def setNoBrowser(v: Boolean): Unit = _noBrowser = v
  def noBrowser: Boolean = _noBrowser

  def load: IO[GatewayConfig] = IO.delay {
    // L3 rebrand compat: dual-prefix env read (brand prefix first, legacy
    // NEBFLOW_ fallback) — identical prefixes collapse to one variable.
    val host = nebflow.shared.Branding
      .env("GATEWAY_HOST")
      .flatMap(Host.fromString)
      .getOrElse(DefaultHost)
    val port = _portOverride
      .flatMap(p => Port.fromInt(p))
      .getOrElse(
        nebflow.shared.Branding
          .env("GATEWAY_PORT")
          .flatMap(s => s.toIntOption.flatMap(Port.fromInt))
          .getOrElse(DefaultPort)
      )
    GatewayConfig(host, port)
  }
end GatewayConfig
