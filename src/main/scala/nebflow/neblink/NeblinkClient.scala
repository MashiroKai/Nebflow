package nebflow.neblink

import cats.effect.IO
import io.circe.generic.semiauto.*
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}
import nebflow.core.NebflowLogger

import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.{NetworkInterface, URI}

import scala.jdk.CollectionConverters.*

/** NebLink Server configuration. */
case class NeblinkServerConfig(
  url: String, // e.g. "http://192.168.1.200:9090"
  networkId: String,
  secret: String
)

object NeblinkServerConfig:
  given Encoder[NeblinkServerConfig] = deriveEncoder

  given Decoder[NeblinkServerConfig] = Decoder.instance { c =>
    for
      url <- c.downField("url").as[Option[String]].flatMap {
        case Some(u) => Right(u)
        case None => c.downField("server").as[String] // backward compat
      }
      networkId <- c.downField("networkId").as[String]
      secret <- c.downField("secret").as[String]
    yield NeblinkServerConfig(url, networkId, secret)
  }

// ===== Internal types (matching NebLink Server's JSON response format) =====

private[neblink] case class NeblinkEndpoint(address: String, port: Int, kind: String, label: String = "")

private[neblink] case class NeblinkPeerInfo(
  deviceId: String,
  deviceName: String,
  platform: String,
  endpoints: List[NeblinkEndpoint],
  online: Boolean
)

private case class LoginResponse(token: String, networkId: String, deviceId: String, peers: List[NeblinkPeerInfo])
private case class HeartbeatResponse(peers: List[NeblinkPeerInfo])

// ===== Circe Decoders =====

object NeblinkCodecs:

  given Decoder[NeblinkEndpoint] = Decoder.instance { c =>
    for
      address <- c.downField("address").as[String]
      port <- c.downField("port").as[Int]
      kind <- c.downField("kind").as[String]
      label <- c.downField("label").as[Option[String]].map(_.getOrElse(""))
    yield NeblinkEndpoint(address, port, kind, label)
  }

  given Decoder[NeblinkPeerInfo] = Decoder.instance { c =>
    for
      deviceId <- c.downField("deviceId").as[String]
      deviceName <- c.downField("deviceName").as[String]
      platform <- c.downField("platform").as[String]
      endpoints <- c.downField("endpoints").as[Option[List[NeblinkEndpoint]]].map(_.getOrElse(Nil))
      online <- c.downField("online").as[Option[Boolean]].map(_.getOrElse(true))
    yield NeblinkPeerInfo(deviceId, deviceName, platform, endpoints, online)
  }

  given Decoder[LoginResponse] = Decoder.instance { c =>
    for
      token <- c.downField("token").as[String]
      networkId <- c.downField("networkId").as[String]
      deviceId <- c.downField("deviceId").as[String]
      peers <- c.downField("peers").as[Option[List[NeblinkPeerInfo]]].map(_.getOrElse(Nil))
    yield LoginResponse(token, networkId, deviceId, peers)
  }

  given Decoder[HeartbeatResponse] = Decoder.instance { c =>
    c.downField("peers").as[Option[List[NeblinkPeerInfo]]].map(_.getOrElse(Nil)).map(HeartbeatResponse.apply)
  }
end NeblinkCodecs

import NeblinkCodecs.{given, *}

/**
 * Client for the NebLink Server.
 * Handles device login, periodic heartbeat, and peer discovery.
 * NebLink Server is the discovery mechanism.
 */
class NeblinkClient(config: NeblinkServerConfig, serverPort: Int):
  private val logger = NebflowLogger.forName("nebflow.neblink.client")

  // HTTP client that bypasses system proxy (direct LAN/WAN access)
  private val httpClient = HttpClient
    .newBuilder()
    .proxy(java.net.ProxySelector.of(null))
    .build()

  @volatile private var sessionToken: Option[String] = None

  /** Detect local IPv4 addresses for endpoint reporting to the NebLink Server. */
  def detectLocalEndpoints: IO[List[NeblinkEndpoint]] = IO.blocking {
    try
      NetworkInterface.getNetworkInterfaces.asScala.toList
        .filter(_.isUp)
        .filterNot(_.isLoopback)
        .flatMap(_.getInetAddresses.asScala)
        .filter(_.isInstanceOf[java.net.Inet4Address])
        .map(addr => NeblinkEndpoint(addr.getHostAddress, serverPort, "lan", ""))
    catch case _: Exception => Nil
  }

  /** Login to NebLink Server. Stores session token. Returns initial peer list. */
  def login(
    deviceId: String,
    deviceName: String,
    platform: String,
    endpoints: List[NeblinkEndpoint]
  ): IO[Either[String, List[NeblinkPeerInfo]]] =
    for
      localEndpoints <- if endpoints.isEmpty then detectLocalEndpoints else IO.pure(endpoints)
      body = Json
        .obj(
          "networkId" -> config.networkId.asJson,
          "secret" -> config.secret.asJson,
          "deviceId" -> deviceId.asJson,
          "deviceName" -> deviceName.asJson,
          "platform" -> platform.asJson,
          "endpoints" -> localEndpoints.map { e =>
            Json.obj(
              "address" -> e.address.asJson,
              "port" -> e.port.asJson,
              "kind" -> e.kind.asJson
            )
          }.asJson
        )
        .noSpaces
      result <- sendRequest("POST", s"${config.url}/api/device/login", body, None).flatMap {
        case Right(respBody) =>
          decode[LoginResponse](respBody) match
            case Right(login) =>
              IO { sessionToken = Some(login.token) } *>
                logger.info(s"Logged into NebLink Server: ${login.peers.size} peer(s)").as(Right(login.peers))
            case Left(err) =>
              logger
                .warn(s"NebLink Server login decode error: ${err.getMessage}")
                .as(Left(s"Decode error: ${err.getMessage}"))
        case Left(err) => IO.pure(Left(err))
      }
    yield result

  /** Send heartbeat and get updated peer list. Requires prior login. */
  def heartbeat: IO[Either[String, List[NeblinkPeerInfo]]] =
    sessionToken match
      case None => IO.pure(Left("Not logged in"))
      case Some(token) =>
        sendRequest("POST", s"${config.url}/api/device/heartbeat", "", Some(token)).flatMap {
          case Right(respBody) =>
            decode[HeartbeatResponse](respBody) match
              case Right(hb) => IO.pure(Right(hb.peers))
              case Left(err) => IO.pure(Left(s"Decode error: ${err.getMessage}"))
          case Left(err) => IO.pure(Left(err))
        }

  /**
   * Smart discovery: login if needed, otherwise heartbeat.
   * If heartbeat fails (token expired), re-login automatically.
   */
  def discover(
    deviceId: String,
    deviceName: String,
    platform: String,
    endpoints: List[NeblinkEndpoint]
  ): IO[Either[String, List[NeblinkPeerInfo]]] =
    sessionToken match
      case None => login(deviceId, deviceName, platform, endpoints)
      case Some(_) =>
        heartbeat.flatMap {
          case Right(peers) => IO.pure(Right(peers))
          case Left(err) =>
            logger.info(s"Heartbeat failed ($err), re-logging in...") *>
              IO { sessionToken = None } *>
              login(deviceId, deviceName, platform, endpoints)
        }

  /** Logout from NebLink Server. */
  def logout: IO[Unit] =
    sessionToken match
      case None => IO.unit
      case Some(token) =>
        sendRequest("DELETE", s"${config.url}/api/device/logout", "", Some(token))
          .handleErrorWith(_ => IO.unit)
          *> IO { sessionToken = None }

  /** Convert NebLink Server peers to neblink PeerInfo. Picks first endpoint as address. */
  def toNeblinkPeers(serverPeers: List[NeblinkPeerInfo]): List[PeerInfo] =
    serverPeers.filter(_.endpoints.nonEmpty).map { p =>
      val ep = p.endpoints.head
      PeerInfo(
        deviceId = p.deviceId,
        deviceName = p.deviceName,
        platform = p.platform,
        address = s"http://${ep.address}:${ep.port}"
      )
    }

  /** Extract all peer IP addresses from NebLink Server peer list. */
  def peerAddresses(serverPeers: List[NeblinkPeerInfo]): Set[String] =
    serverPeers.flatMap(_.endpoints.map(_.address)).toSet

  // ===== Private helpers =====

  private def sendRequest(
    method: String,
    url: String,
    body: String,
    token: Option[String]
  ): IO[Either[String, String]] =
    IO.blocking {
      try
        val builder = HttpRequest
          .newBuilder()
          .uri(URI.create(url))
          .timeout(java.time.Duration.ofSeconds(10))
        token.foreach(t => builder.header("Authorization", s"Bearer $t"))
        if method == "POST" then
          builder.header("Content-Type", "application/json")
          if body.nonEmpty then builder.POST(HttpRequest.BodyPublishers.ofString(body))
          else builder.POST(HttpRequest.BodyPublishers.noBody())
        else if method == "DELETE" then builder.DELETE()
        else builder.GET()
        val request = builder.build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if response.statusCode() >= 200 && response.statusCode() < 300 then Right(response.body())
        else Left(s"HTTP ${response.statusCode()}: ${response.body()}")
      catch case e: Exception => Left(e.getMessage)
    }.handleErrorWith(e => IO.pure(Left(e.getMessage)))

end NeblinkClient
