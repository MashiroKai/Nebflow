package nebflow.neblink

import cats.effect.IO
import io.circe.generic.semiauto.*
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json, JsonObject}
import nebflow.core.PathUtil

import java.util.UUID

// ===== Device Identity =====

/** Local device identity — generated once, stored in ~/.nebflow/device.json. */
case class DeviceIdentity(
  deviceId: String,
  deviceName: String,
  platform: String,
  deviceSecret: String = "",
  capabilities: Map[String, String] = Map.empty,
  userDescription: String = "",
  avatarUrl: Option[String] = None,
  githubLogin: Option[String] = None
)

object DeviceIdentity:
  given Encoder[DeviceIdentity] = deriveEncoder
  given Decoder[DeviceIdentity] = deriveDecoder

  private val devicePath = PathUtil.dataRoot / "device.json"

  private def detectPlatform: String =
    val osName = System.getProperty("os.name", "unknown").toLowerCase
    if osName.contains("mac") then "macos"
    else if osName.contains("win") then "windows"
    else if osName.contains("linux") then "linux"
    else "unknown"

  private def detectDeviceName: String =
    Option(System.getenv("HOSTNAME"))
      .orElse(Option(System.getenv("COMPUTERNAME")))
      .orElse(
        try Some(java.net.InetAddress.getLocalHost.getHostName)
        catch case _: Exception => None
      )
      .map(_.stripSuffix(".local")) // macOS mDNS returns "hostname.local"
      .getOrElse("Unknown")

  def loadOrCreate: IO[DeviceIdentity] =
    IO.blocking {
      if os.exists(devicePath) then
        decode[DeviceIdentity](os.read(devicePath)) match
          case Right(d) => ensureSecret(ensureCleanDeviceName(d))
          case Left(_) => createNew()
      else createNew()
    }.flatMap { id =>
      // Persist if file doesn't exist yet, or if we just migrated (deviceSecret or deviceName)
      val needsSave = !os.exists(devicePath) ||
        decode[DeviceIdentity](os.read(devicePath)).toOption.exists { saved =>
          saved.deviceSecret.isEmpty || saved.deviceName != id.deviceName
        }
      if needsSave then save(id).as(id) else IO.pure(id)
    }

  def save(identity: DeviceIdentity): IO[Unit] =
    IO.blocking {
      os.write.over(devicePath, identity.asJson.spaces2, createFolders = true)
    }

  private def createNew(): DeviceIdentity =
    DeviceIdentity(
      deviceId = UUID.randomUUID().toString,
      deviceName = detectDeviceName,
      platform = detectPlatform,
      deviceSecret = UUID.randomUUID().toString + UUID.randomUUID().toString
    )

  /** Migrate old DeviceIdentity without deviceSecret — generate one on first load. */
  private def ensureSecret(id: DeviceIdentity): DeviceIdentity =
    if id.deviceSecret.isEmpty then id.copy(deviceSecret = UUID.randomUUID().toString + UUID.randomUUID().toString)
    else id

  /** Migrate old DeviceIdentity with ".local" suffix in deviceName (macOS mDNS artifact). */
  private def ensureCleanDeviceName(id: DeviceIdentity): DeviceIdentity =
    if id.deviceName.endsWith(".local") then id.copy(deviceName = id.deviceName.stripSuffix(".local"))
    else id
end DeviceIdentity

// ===== Device Discovery Info =====

/**
 * Device info exchanged during NebLink discovery (returned by GET /api/neblink/discover).
 *
 *  Note: userDescription is intentionally NOT included — descriptions are purely local,
 *  never exchanged between devices. See NeblinkService.handleAnnounce.
 */
case class DeviceDiscoveryInfo(
  deviceId: String,
  deviceName: String,
  platform: String,
  capabilities: Map[String, String] = Map.empty
)

object DeviceDiscoveryInfo:
  given Encoder[DeviceDiscoveryInfo] = deriveEncoder
  given Decoder[DeviceDiscoveryInfo] = deriveDecoder

// ===== Peer Info =====

case class PeerInfo(
  deviceId: String,
  deviceName: String,
  platform: String,
  address: String,
  deviceSecret: String = "",
  capabilities: Map[String, String] = Map.empty,
  userDescription: String = "",
  lastSeen: Long = System.currentTimeMillis()
)

object PeerInfo:
  given Encoder[PeerInfo] = deriveEncoder

  given Decoder[PeerInfo] = Decoder.instance { c =>
    for
      deviceId <- c.downField("deviceId").as[String]
      deviceName <- c.downField("deviceName").as[String]
      platform <- c.downField("platform").as[String]
      address <- c.downField("address").as[String]
      deviceSecret <- c.downField("deviceSecret").as[Option[String]].map(_.getOrElse(""))
      capabilities <- c.downField("capabilities").as[Option[Map[String, String]]].map(_.getOrElse(Map.empty))
      userDescription <- c.downField("userDescription").as[Option[String]].map(_.getOrElse(""))
      lastSeen <- c.downField("lastSeen").as[Option[Long]].map(_.getOrElse(System.currentTimeMillis()))
    yield PeerInfo(deviceId, deviceName, platform, address, deviceSecret, capabilities, userDescription, lastSeen)
  }
end PeerInfo

// ===== Neblink Config =====

case class NeblinkConfig(
  enabled: Boolean = false,
  syncIntervalSec: Int = 45,
  neblinkServer: Option[NeblinkServerConfig] = None,
  /** External OIDC provider for device-flow authentication (Logto stage 1).
    * When set, the gateway's device-flow start and poll routes talk to the
    * provider's RFC 8628 endpoints instead of neblink-server's self-hosted
    * ones. */
  logto: Option[LogtoConfig] = None,
  /** Agent messaging permissions (A2A 一期, spec §7.2): how the
    * SendFriendMessage tool may send on the user's behalf. */
  agentMessaging: AgentMessagingConfig = AgentMessagingConfig()
)

/**
 * Agent messaging permission tier (A2A 一期, spec §7.2-7.3). `mode`:
 *  - auto (default, user ruling 2026-08-17): send directly, no prompt; bounded
 *    by the two-layer client rate limit (perFriendPerHour / globalPerHour);
 *    on exceeding, auto-downgrades to ask (confirmation prompt) — never a hard
 *    failure.
 *  - ask: every send prompts the user (60s timeout = declined).
 *  - off: tool returns "user has disabled agent messaging".
 * Server side enforces an independent 30 msg/min token bucket (§7.3).
 */
case class AgentMessagingConfig(
  mode: String = "auto",
  perFriendPerHour: Int = 20,
  globalPerHour: Int = 60
)

object AgentMessagingConfig:
  given Encoder[AgentMessagingConfig] = deriveEncoder

  given Decoder[AgentMessagingConfig] = Decoder.instance { c =>
    for
      mode <- c.downField("mode").as[Option[String]].map(_.getOrElse("auto"))
      perFriend <- c.downField("perFriendPerHour").as[Option[Int]].map(_.getOrElse(20))
      global <- c.downField("globalPerHour").as[Option[Int]].map(_.getOrElse(60))
    yield AgentMessagingConfig(mode, perFriend, global)
  }

/** Logto (OIDC provider) connection settings — Native apps (public clients,
  * no secret). `clientId` = the device-flow app (RFC 8628 legacy + fallback);
  * `pkceClientId` = the Authorization Code + PKCE app (stage 2 primary
  * login, 2026-08-28). Separate apps because the deployed Logto pins a
  * device-flow app to the device_code grant via `isDeviceFlow` and that
  * metadata is not editable through the Management API.
  *
  * Config surface (single, deliberate): decoded from
  * `<home>/neblink/config.json` → `logto{endpoint,clientId,pkceClientId}`.
  * There is NO reader for a nebflow.json `neblink.logto` block — entries
  * there are inert (2026-08-28 dispatch misdirected the file once; qa
  * fact-checked it). */
case class LogtoConfig(
  endpoint: String,
  clientId: String,
  pkceClientId: Option[String] = None
)

object LogtoConfig:
  given Encoder[LogtoConfig] = Encoder.instance { c =>
    val base = JsonObject(
      "endpoint" -> c.endpoint.asJson,
      "clientId" -> c.clientId.asJson
    )
    Json.fromJsonObject(
      c.pkceClientId.fold(base)(v => base.add("pkceClientId", v.asJson))
    )
  }

  given Decoder[LogtoConfig] = Decoder.instance { c =>
    for
      endpoint <- c.downField("endpoint").as[String]
      clientId <- c.downField("clientId").as[String]
      pkceClientId <- c.downField("pkceClientId").as[Option[String]]
    yield LogtoConfig(endpoint, clientId, pkceClientId)
  }

object NeblinkConfig:
  given Encoder[NeblinkConfig] = deriveEncoder

  given Decoder[NeblinkConfig] = Decoder.instance { c =>
    for
      enabled <- c.downField("enabled").as[Option[Boolean]].map(_.getOrElse(false))
      syncIntervalSec <- c.downField("syncIntervalSec").as[Option[Int]].map(_.getOrElse(45))
      // Backward compat: try "neblinkServer" first, fall back to "coordinator"
      neblinkServer <- c.downField(Protocol.neblinkServerField).as[Option[NeblinkServerConfig]].flatMap {
        case Some(config) => Right(Some(config))
        case None => c.downField("coordinator").as[Option[NeblinkServerConfig]]
      }
      logto <- c.downField("logto").as[Option[LogtoConfig]]
      agentMessaging <- c.downField("agentMessaging").as[Option[AgentMessagingConfig]].map(_.getOrElse(AgentMessagingConfig()))
    yield NeblinkConfig(enabled, syncIntervalSec, neblinkServer, logto, agentMessaging)
  }

  private val configPath = PathUtil.dataRoot / "neblink" / "config.json"

  def load: IO[NeblinkConfig] =
    IO.blocking {
      if os.exists(configPath) then
        decode[NeblinkConfig](os.read(configPath)) match
          case Right(c) => c
          case Left(_) => NeblinkConfig()
      else NeblinkConfig()
    }

  def save(config: NeblinkConfig): IO[Unit] =
    IO.blocking {
      os.write.over(configPath, config.asJson.spaces2, createFolders = true)
    }
end NeblinkConfig

// ===== A2A 好友与消息域类型（spec §6.1 REST 响应，客户端侧解码） =====

/** 好友/搜索结果卡（/api/users/lookup 与 /api/friends 共用形态）。 */
case class FriendSummary(
  userId: String,
  neblinkId: String,
  name: String,
  avatarUrl: Option[String] = None,
  since: Option[Long] = None
)

/** 收到的好友请求（incoming 分组）。 */
case class FriendRequestSummary(
  requestId: String,
  from: FriendSummary,
  note: Option[String] = None
)

/** 发出的好友请求（outgoing 分组）。 */
case class OutgoingRequestSummary(
  requestId: String,
  to: FriendSummary
)

case class FriendListResponse(
  friends: List[FriendSummary],
  incoming: List[FriendRequestSummary] = Nil,
  outgoing: List[OutgoingRequestSummary] = Nil
)

case class MessageSummary(
  id: Long,
  senderId: String,
  kind: String,
  body: String,
  createdAt: Long
)

case class ConversationSummary(
  conversationId: String,
  friend: FriendSummary,
  lastMessage: Option[MessageSummary] = None,
  unreadCount: Int = 0
)

/** 客户端本地未读 cursor 状态（spec §3.4：自己看角标，无回执）。 */
case class ConversationCursor(conversationId: String, lastReadMessageId: Long, unreadCount: Int)

object FriendCodecs:
  import io.circe.Decoder
  import io.circe.Encoder
  import io.circe.generic.semiauto.*

  given Decoder[FriendSummary] = deriveDecoder
  given Decoder[FriendRequestSummary] = deriveDecoder
  given Decoder[OutgoingRequestSummary] = deriveDecoder
  given Decoder[FriendListResponse] = deriveDecoder
  given Decoder[MessageSummary] = deriveDecoder
  given Decoder[ConversationSummary] = deriveDecoder
  // Encoders for gateway REST responses (client decodes server JSON; gateway
  // re-encodes the same domain objects for the frontend UI).
  given Encoder[FriendSummary] = deriveEncoder
  given Encoder[FriendRequestSummary] = deriveEncoder
  given Encoder[OutgoingRequestSummary] = deriveEncoder
  given Encoder[FriendListResponse] = deriveEncoder
  given Encoder[MessageSummary] = deriveEncoder
  given Encoder[ConversationSummary] = deriveEncoder
end FriendCodecs

// ===== Peer Description Store =====

/** Persists user-set peer descriptions across restarts. Stored in ~/.nebflow/peer-descriptions.json. */
object PeerDescriptionStore:
  private val path = PathUtil.dataRoot / "peer-descriptions.json"

  def load: IO[Map[String, String]] =
    IO.blocking {
      if os.exists(path) then decode[Map[String, String]](os.read(path)).getOrElse(Map.empty)
      else Map.empty
    }

  def save(descs: Map[String, String]): IO[Unit] =
    IO.blocking {
      os.write.over(path, descs.asJson.spaces2, createFolders = true)
    }
