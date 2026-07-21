package nebflow.coordinator

import io.circe.generic.semiauto.*
import io.circe.{Decoder, Encoder}

// ===== Public API data models =====

/** Describes how to reach a device. */
case class DeviceEndpoint(
  address: String,   // IP or hostname
  port: Int,         // port (default 8080)
  kind: String,      // "lan" | "wan" | "public"
  label: String = "" // optional description
)
object DeviceEndpoint:
  given Encoder[DeviceEndpoint] = deriveEncoder
  given Decoder[DeviceEndpoint] = deriveDecoder

/** Peer info returned to clients (never contains session tokens). */
case class PeerInfo(
  deviceId: String,
  deviceName: String,
  platform: String,
  endpoints: List[DeviceEndpoint],
  online: Boolean
)
object PeerInfo:
  given Encoder[PeerInfo] = deriveEncoder
  given Decoder[PeerInfo] = deriveDecoder

// ===== API request / response types =====

case class CreateNetworkRequest(name: String)
object CreateNetworkRequest:
  given Decoder[CreateNetworkRequest] = deriveDecoder

case class CreateNetworkResponse(networkId: String, secret: String)
object CreateNetworkResponse:
  given Encoder[CreateNetworkResponse] = deriveEncoder

case class LoginRequest(
  networkId: String,
  secret: String,
  deviceId: String,
  deviceName: String,
  platform: String,
  endpoints: List[DeviceEndpoint]
)
object LoginRequest:
  given Decoder[LoginRequest] = deriveDecoder

case class LoginResponse(
  token: String,
  networkId: String,
  deviceId: String,
  peers: List[PeerInfo]
)
object LoginResponse:
  given Encoder[LoginResponse] = deriveEncoder

case class HeartbeatResponse(peers: List[PeerInfo])
object HeartbeatResponse:
  given Encoder[HeartbeatResponse] = deriveEncoder

case class UpdateEndpointsRequest(endpoints: List[DeviceEndpoint])
object UpdateEndpointsRequest:
  given Decoder[UpdateEndpointsRequest] = deriveDecoder

case class ErrorResponse(error: String)
object ErrorResponse:
  given Encoder[ErrorResponse] = deriveEncoder
