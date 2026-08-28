package nebflow.gateway

import cats.effect.IO
import com.comcast.ip4s.{Host, Port}

case class GatewayConfig(host: Host, port: Port)

object GatewayConfig:
  private val DefaultHost: Host = Host.fromString("0.0.0.0").get
  private val DefaultPort: Port = Port.fromInt(8080).get

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
    val host = nebflow.core.Branding
      .env("GATEWAY_HOST")
      .flatMap(Host.fromString)
      .getOrElse(DefaultHost)
    val port = _portOverride
      .flatMap(p => Port.fromInt(p))
      .getOrElse(
        nebflow.core.Branding
          .env("GATEWAY_PORT")
          .flatMap(s => s.toIntOption.flatMap(Port.fromInt))
          .getOrElse(DefaultPort)
      )
    GatewayConfig(host, port)
  }
end GatewayConfig
