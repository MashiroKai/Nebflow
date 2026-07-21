package nebflow.coordinator

import cats.effect.{IO, Ref}

import java.time.Instant

// ===== Internal models (never sent to clients directly) =====

case class Network(
  networkId: String,
  name: String,
  secretHash: String, // SHA-256 hex — plaintext secret is never stored
  createdAt: Instant
)

case class RegisteredDevice(
  deviceId: String,
  deviceName: String,
  platform: String,
  networkId: String,
  endpoints: List[DeviceEndpoint],
  sessionToken: String,
  lastSeen: Instant,
  online: Boolean
)

/** In-memory store backed by cats-effect Refs. */
class CoordinatorStore private (
  networksRef: Ref[IO, Map[String, Network]],         // networkId -> Network
  devicesRef: Ref[IO, Map[String, RegisteredDevice]]  // sessionToken -> Device
):

  // ----- Network operations -----

  def putNetwork(network: Network): IO[Unit] =
    networksRef.update(_.updated(network.networkId, network))

  def getNetwork(networkId: String): IO[Option[Network]] =
    networksRef.get.map(_.get(networkId))

  // ----- Device operations -----

  def putDevice(device: RegisteredDevice): IO[Unit] =
    devicesRef.update(_.updated(device.sessionToken, device))

  def getDeviceByToken(token: String): IO[Option[RegisteredDevice]] =
    devicesRef.get.map(_.get(token))

  def getDeviceById(deviceId: String): IO[Option[RegisteredDevice]] =
    devicesRef.get.map(_.values.find(_.deviceId == deviceId))

  def getDevicesByNetwork(networkId: String): IO[List[RegisteredDevice]] =
    devicesRef.get.map(_.values.filter(_.networkId == networkId).toList)

  def removeDevice(token: String): IO[Unit] =
    devicesRef.update(_ - token)

  def updateDeviceHeartbeat(token: String): IO[Unit] =
    devicesRef.update { devices =>
      devices.get(token) match
        case Some(device) =>
          devices.updated(token, device.copy(lastSeen = Instant.now(), online = true))
        case None => devices
    }

  def updateDeviceEndpoints(token: String, endpoints: List[DeviceEndpoint]): IO[Unit] =
    devicesRef.update { devices =>
      devices.get(token) match
        case Some(device) => devices.updated(token, device.copy(endpoints = endpoints))
        case None => devices
    }

  def setDeviceOnline(token: String, online: Boolean): IO[Unit] =
    devicesRef.update { devices =>
      devices.get(token) match
        case Some(device) => devices.updated(token, device.copy(online = online))
        case None => devices
    }

  /** Remove devices whose lastSeen exceeds the timeout. Returns removed deviceIds. */
  def purgeStale(timeoutSeconds: Long): IO[List[String]] =
    val now = Instant.now()
    for
      devices <- devicesRef.get
      (stale, active) = devices.partition { case (_, device) =>
        java.time.Duration.between(device.lastSeen, now).getSeconds > timeoutSeconds
      }
      staleDeviceIds = stale.values.map(_.deviceId).toList
      _ <- devicesRef.set(active)
    yield staleDeviceIds

end CoordinatorStore

object CoordinatorStore:
  def make: IO[CoordinatorStore] = for
    n <- Ref.of[IO, Map[String, Network]](Map.empty)
    d <- Ref.of[IO, Map[String, RegisteredDevice]](Map.empty)
  yield new CoordinatorStore(n, d)
