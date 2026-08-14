package nebflow.neblink

import cats.effect.IO
import io.circe.generic.semiauto.*
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json, JsonObject}
import nebflow.core.NebflowLogger

import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.{NetworkInterface, URI}

import scala.jdk.CollectionConverters.*

/** NebLink Server configuration. */
case class NeblinkServerConfig(
  url: String, // e.g. "http://192.168.1.200:9090"
  networkId: String,
  secret: String,
  /**
   * Long-lived per-device credential (pairing-code enrollment). When present,
   * the client authenticates via `/api/device/session` instead of the legacy
   * shared-secret `/api/device/login`.
   */
  deviceToken: Option[String] = None
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
      secret <- c.downField("secret").as[Option[String]].map(_.getOrElse(""))
      deviceToken <- c.downField("deviceToken").as[Option[String]]
    yield NeblinkServerConfig(url, networkId, secret, deviceToken)
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

  // HTTP client: force HTTP/1.1 and bypass system proxy (direct LAN/WAN access).
  // HTTP/1.1 avoids the JDK HttpClient's HTTP/2 connection-reuse + TLS 1.3
  // session resumption clash with the Caddy reverse proxy in front of
  // neblink.nebflow.space (connect timeouts / bad_record_mac TLS alerts).
  private val httpClient = HttpClient
    .newBuilder()
    .version(HttpClient.Version.HTTP_1_1)
    .proxy(java.net.ProxySelector.of(null))
    .connectTimeout(java.time.Duration.ofSeconds(15))
    .build()

  @volatile private var sessionToken: Option[String] = None

  /** Expose current session token for NeblinkRelayTunnel (live read on each reconnect). */
  def currentSessionToken: Option[String] = sessionToken

  /**
   * Detect local IPv4 addresses for endpoint reporting to the NebLink Server.
   *
   * Filters out virtual NICs (Hyper-V, WSL2, Docker, VMware, VirtualBox) whose
   * addresses are unreachable from the real LAN — e.g. `192.168.121.1` on a
   * Windows `vEthernet (WSL)` adapter. If filtering would remove every
   * endpoint, falls back to the unfiltered list so P2P still has a chance.
   */
  def detectLocalEndpoints: IO[List[NeblinkEndpoint]] = IO.blocking {
    try
      val allNics = NetworkInterface.getNetworkInterfaces.asScala.toList
        .filter(_.isUp)
        .filterNot(_.isLoopback)

      def toEndpoints(nics: List[NetworkInterface]): List[NeblinkEndpoint] =
        nics.flatMap(_.getInetAddresses.asScala)
          .filter(_.isInstanceOf[java.net.Inet4Address])
          .map(addr => NeblinkEndpoint(addr.getHostAddress, serverPort, "lan", ""))

      val physical = allNics.filterNot(isLikelyVirtualNic)
      val preferred = toEndpoints(physical)
      if preferred.nonEmpty then preferred else toEndpoints(allNics) // never lose all endpoints
    catch case _: Exception => Nil
  }

  /**
   * Heuristic virtual-NIC detector. `NetworkInterface.isVirtual()` is
   * unreliable across OSes (returns false for Hyper-V/WSL on Windows), so we
   * combine it with name-pattern matching against the common virtual adapter
   * display names. Conservative: only flags well-known virtual markers.
   */
  private def isLikelyVirtualNic(nic: NetworkInterface): Boolean =
    val names = Seq(nic.getDisplayName, nic.getName).map(n => Option(n).getOrElse("").toLowerCase)
    val virtualMarkers =
      Seq("virtual", "vethernet", "vmware", "docker", "wsl", "hyper-v", "virtualbox", "loopback pseudo")
    nic.isVirtual || names.exists(n => virtualMarkers.exists(n.contains))

  /**
   * Login to NebLink Server. Stores session token. Returns initial peer list.
   *
   * If a per-device credential (`deviceToken`) is configured (pairing-code
   * enrollment), authenticates via `/api/device/session`. Otherwise falls
   * back to the legacy shared-secret `/api/device/login`.
   */
  def login(
    deviceId: String,
    deviceName: String,
    platform: String,
    endpoints: List[NeblinkEndpoint]
  ): IO[Either[String, List[NeblinkPeerInfo]]] =
    for
      localEndpoints <- if endpoints.isEmpty then detectLocalEndpoints else IO.pure(endpoints)
      endpointJson = localEndpoints.map { e =>
        Json.obj(
          "address" -> e.address.asJson,
          "port" -> e.port.asJson,
          "kind" -> e.kind.asJson
        )
      }
      // Choose path + body based on whether we have a device credential.
      (path, body) = config.deviceToken match
        case Some(token) =>
          (
            "/api/device/session",
            Json
              .obj(
                "networkId" -> config.networkId.asJson,
                "deviceId" -> deviceId.asJson,
                "deviceToken" -> token.asJson,
                "deviceName" -> deviceName.asJson,
                "platform" -> platform.asJson,
                "endpoints" -> endpointJson.asJson
              )
              .noSpaces
          )
        case None =>
          (
            "/api/device/login",
            Json
              .obj(
                "networkId" -> config.networkId.asJson,
                "secret" -> config.secret.asJson,
                "deviceId" -> deviceId.asJson,
                "deviceName" -> deviceName.asJson,
                "platform" -> platform.asJson,
                "endpoints" -> endpointJson.asJson
              )
              .noSpaces
          )
      result <- sendRequest("POST", s"${config.url}$path", body, None).flatMap {
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

  /**
   * Send heartbeat and get updated peer list. Requires prior login.
   *
   * Re-detects local endpoints on every heartbeat so the server sees our current
   * IP — after a network change (e.g. laptop moved to a different Wi-Fi), the
   * server's stored address becomes stale and peers can't reach us via P2P.
   * Including endpoints in the body lets the server update our address even
   * when it doesn't change (idempotent).
   */
  def heartbeat: IO[Either[String, List[NeblinkPeerInfo]]] =
    sessionToken match
      case None => IO.pure(Left("Not logged in"))
      case Some(token) =>
        detectLocalEndpoints.flatMap { endpoints =>
          val body = Json.obj(
            "endpoints" -> endpoints.map { e =>
              Json.obj("address" -> e.address.asJson, "port" -> e.port.asJson, "kind" -> e.kind.asJson)
            }.asJson,
            "version" -> nebflow.Version.string.asJson
          ).noSpaces
          sendRequest("POST", s"${config.url}/api/device/heartbeat", body, Some(token)).flatMap {
            case Right(respBody) =>
              decode[HeartbeatResponse](respBody) match
                case Right(hb) => IO.pure(Right(hb.peers))
                case Left(err) => IO.pure(Left(s"Decode error: ${err.getMessage}"))
            case Left(err) => IO.pure(Left(err))
          }
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

  /**
   * Execute a tool on a remote device via the NebLink Server relay tunnel.
   * Used as fallback when P2P direct connection is unreachable (cross-network).
   * The request goes: this device -> Server -> target device's WS tunnel.
   */
  def relayExec(targetDeviceId: String, action: String, params: JsonObject): IO[Either[String, String]] =
    sessionToken match
      case None => IO.pure(Left("Not logged in"))
      case Some(token) =>
        val body = Json.obj(
          "action" -> action.asJson,
          "params" -> params.asJson,
          "projectRoot" -> System.getProperty("user.dir", ".").asJson
        ).noSpaces
        sendRequest("POST", s"${config.url}/api/relay/$targetDeviceId/exec", body, Some(token)).flatMap {
          case Right(respBody) =>
            decode[Json](respBody) match
              case Right(json) =>
                val output = json.hcursor.downField("output").as[String].getOrElse("")
                val error = json.hcursor.downField("error").as[String].getOrElse("")
                if error.nonEmpty then IO.pure(Left(error))
                else IO.pure(Right(output))
              case Left(err) => IO.pure(Left(s"Decode error: ${err.getMessage}"))
          case Left(err) => IO.pure(Left(err))
        }

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

  // ===== Relay file transfer (FileTransfer action) =====

  /** Pull a file from a remote device via relay. Returns (base64 content, size). */
  def relayTransferGet(targetDeviceId: String, path: String): IO[Either[String, (String, Long)]] =
    val params = JsonObject("direction" -> "get".asJson, "path" -> path.asJson)
    relayExec(targetDeviceId, "FileTransfer", params).flatMap {
      case Right(output) =>
        decode[Json](output) match
          case Right(json) =>
            val contentB64 = json.hcursor.downField("content").as[String].getOrElse("")
            val size = json.hcursor.downField("size").as[Long].getOrElse(0L)
            if contentB64.isEmpty then IO.pure(Left("Empty response"))
            else IO.pure(Right((contentB64, size)))
          case Left(err) => IO.pure(Left(s"Decode error: ${err.getMessage}"))
      case Left(err) => IO.pure(Left(err))
    }

  /** Push a file to a remote device via relay. Returns size in bytes. */
  def relayTransferPut(
    targetDeviceId: String,
    path: String,
    contentB64: String,
    overwrite: Boolean
  ): IO[Either[String, Long]] =
    val params = JsonObject(
      "direction" -> "put".asJson,
      "path" -> path.asJson,
      "content" -> contentB64.asJson,
      "overwrite" -> overwrite.asJson
    )
    relayExec(targetDeviceId, "FileTransfer", params).flatMap {
      case Right(output) =>
        decode[Json](output) match
          case Right(json) =>
            val size = json.hcursor.downField("size").as[Long].getOrElse(0L)
            IO.pure(Right(size))
          case Left(err) => IO.pure(Left(s"Decode error: ${err.getMessage}"))
      case Left(err) => IO.pure(Left(err))
    }

  /** Send a notification message to a remote device via relay (Notify action). */
  def relayNotify(targetDeviceId: String, channel: String, payload: Json): IO[Either[String, String]] =
    val params = JsonObject(
      "channel" -> channel.asJson,
      "payload" -> payload
    )
    relayExec(targetDeviceId, "Notify", params)

  /** Trigger a remote update on a device via relay (RemoteUpdate action). */
  def relayUpdate(targetDeviceId: String, beta: Boolean): IO[Either[String, String]] =
    val params = JsonObject("beta" -> beta.asJson)
    relayExec(targetDeviceId, "RemoteUpdate", params)

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
