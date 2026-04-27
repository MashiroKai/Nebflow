package nebflow.gateway

import cats.effect.IO
import com.comcast.ip4s.{Host, Port}

case class GatewayConfig(host: Host, port: Port)

object GatewayConfig:
  private val DefaultHost: Host = Host.fromString("0.0.0.0").get
  private val DefaultPort: Port = Port.fromInt(8080).get
  private val HostEnv = "NEBFLOW_GATEWAY_HOST"
  private val PortEnv = "NEBFLOW_GATEWAY_PORT"

  def load: IO[GatewayConfig] = IO.delay {
    val host = sys.env.get(HostEnv)
      .flatMap(Host.fromString)
      .getOrElse(DefaultHost)
    val port = sys.env.get(PortEnv)
      .flatMap(s => s.toIntOption.flatMap(Port.fromInt))
      .getOrElse(DefaultPort)
    GatewayConfig(host, port)
  }
