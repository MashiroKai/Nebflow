package nebflow.coordinator

import cats.effect.{Fiber, IO}
import cats.syntax.all.*
import fs2.Stream
import nebflow.core.NebflowLogger

import java.security.{MessageDigest, SecureRandom}
import java.time.Instant
import java.util.{Base64, UUID}
import scala.concurrent.duration.*

class CoordinatorService(store: CoordinatorStore):
  private val logger = NebflowLogger.forName("nebflow.coordinator")

  /** Create a new network. The secret is returned exactly once. */
  def createNetwork(name: String): IO[CreateNetworkResponse] =
    for
      networkId <- IO(UUID.randomUUID().toString)
      secret <- generateSecret
      secretHash = sha256Hex(secret)
      network = Network(networkId, name, secretHash, Instant.now())
      _ <- store.putNetwork(network)
      _ <- logger.info(s"Created network: $networkId (name=$name)")
    yield CreateNetworkResponse(networkId, secret)

  /** Authenticate a device against a network and register it. */
  def loginDevice(req: LoginRequest): IO[Either[String, LoginResponse]] =
    store.getNetwork(req.networkId).flatMap {
      case None => IO.pure(Left(s"Network not found: ${req.networkId}"))
      case Some(network) =>
        if !verifySecret(req.secret, network.secretHash) then
          IO.pure(Left("Invalid secret"))
        else
          for
            token <- generateToken
            device = RegisteredDevice(
              deviceId = req.deviceId,
              deviceName = req.deviceName,
              platform = req.platform,
              networkId = req.networkId,
              endpoints = req.endpoints,
              sessionToken = token,
              lastSeen = Instant.now(),
              online = true
            )
            _ <- store.putDevice(device)
            peers <- buildPeerList(req.networkId, req.deviceId)
            _ <- logger.info(s"Device logged in: ${req.deviceId} (${req.deviceName})")
          yield Right(LoginResponse(token, req.networkId, req.deviceId, peers))
    }

  /** Refresh device liveness and return the latest peer list. */
  def heartbeat(token: String): IO[Either[String, HeartbeatResponse]] =
    store.getDeviceByToken(token).flatMap {
      case None => IO.pure(Left("Invalid or expired token"))
      case Some(device) =>
        for
          _ <- store.updateDeviceHeartbeat(token)
          peers <- buildPeerList(device.networkId, device.deviceId)
        yield Right(HeartbeatResponse(peers))
    }

  /** Return peers in the caller's network (excluding the caller). */
  def getPeers(token: String): IO[Either[String, List[PeerInfo]]] =
    store.getDeviceByToken(token).flatMap {
      case None => IO.pure(Left("Invalid or expired token"))
      case Some(device) =>
        buildPeerList(device.networkId, device.deviceId).map(Right(_))
    }

  /** Update the caller's advertised endpoints. */
  def updateEndpoints(token: String, req: UpdateEndpointsRequest): IO[Either[String, Unit]] =
    store.getDeviceByToken(token).flatMap {
      case None => IO.pure(Left("Invalid or expired token"))
      case Some(_) =>
        store.updateDeviceEndpoints(token, req.endpoints).map(Right(_))
    }

  /** Remove a device session. */
  def logout(token: String): IO[Unit] =
    store.removeDevice(token) *> logger.info(s"Device logged out (token=${token.take(8)}...)")

  /** Validate a session token, returning the associated device if valid. */
  def validateToken(token: String): IO[Option[RegisteredDevice]] =
    store.getDeviceByToken(token)

  /** Background fiber that periodically purges stale devices. */
  def startCleanupTask(
    timeoutSeconds: Long = 90,
    intervalSeconds: Long = 30
  ): IO[Fiber[IO, Throwable, Unit]] =
    Stream
      .awakeEvery[IO](intervalSeconds.seconds)
      .evalMap(_ =>
        store.purgeStale(timeoutSeconds).flatMap { removed =>
          if removed.nonEmpty then logger.info(s"Purged stale devices: ${removed.mkString(", ")}")
          else IO.unit
        }.handleErrorWith(e => logger.warn(s"Cleanup error: ${e.getMessage}"))
      )
      .compile
      .drain
      .start

  // ===== private helpers =====

  private def generateSecret: IO[String] = IO.delay {
    val bytes = new Array[Byte](24)
    new SecureRandom().nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
  }

  private def generateToken: IO[String] = IO.delay {
    val bytes = new Array[Byte](32)
    new SecureRandom().nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
  }

  private def sha256Hex(input: String): String =
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(input.getBytes("UTF-8"))
    hash.map(b => f"${b & 0xff}%02x").mkString

  /** Constant-time secret comparison via SHA-256 hash. */
  private def verifySecret(provided: String, expectedHash: String): Boolean =
    val providedHash = sha256Hex(provided)
    MessageDigest.isEqual(
      providedHash.getBytes("UTF-8"),
      expectedHash.getBytes("UTF-8")
    )

  private def buildPeerList(networkId: String, excludeDeviceId: String): IO[List[PeerInfo]] =
    store.getDevicesByNetwork(networkId).map { devices =>
      devices.filter(_.deviceId != excludeDeviceId).map { d =>
        PeerInfo(d.deviceId, d.deviceName, d.platform, d.endpoints, d.online)
      }
    }

end CoordinatorService
