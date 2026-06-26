package nebflow.mesh

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.NebflowLogger
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import sttp.client4.*

import scala.compiletime.uninitialized
import scala.concurrent.duration.*

// ============================================================
// Sync command protocol — public so callers can send commands
// ============================================================

sealed trait SyncCommand

object SyncCommand:
  /** Start periodic sync (sent on login). */
  case object StartSync extends SyncCommand

  /** Stop periodic sync (sent on logout). */
  case object StopSync extends SyncCommand

  /** Periodic sync timer fired. */
  private[mesh] case object SyncTick extends SyncCommand

  /** A peer was discovered — trigger immediate sync. */
  case object PeerDiscovered extends SyncCommand
end SyncCommand

/**
 * Core mesh service — account-based login, cloud discovery, cross-device tool execution.
 *
 * Event-driven design:
 *   - SyncActor replaces the old recursive IO.sleep loop.
 *   - No UDP broadcast/listen — cloud discovery is sufficient for peer detection.
 *   - Sync only runs when logged in. Logged out = zero CPU, zero network.
 */
class MeshService private (
  identityRef: Ref[IO, DeviceIdentity],
  accountRef: Ref[IO, Option[AccountInfo]],
  configRef: Ref[IO, MeshConfig],
  peersRef: Ref[IO, Map[String, PeerInfo]],
  serverPort: Int,
  private val _syncActorRef: ActorRef[SyncCommand]
):
  private val logger = NebflowLogger.forName("nebflow.mesh")

  // Lifecycle hooks — wired by GatewayMain after construction (avoids a constructor cycle
  // with MeshRelayClient, which itself depends on this MeshService). Each hook is an IO.
  private val loginHooks: Ref[IO, List[IO[Unit]]] =
    Ref.unsafe[IO, List[IO[Unit]]](Nil)
  private val logoutHooks: Ref[IO, List[IO[Unit]]] =
    Ref.unsafe[IO, List[IO[Unit]]](Nil)

  /** Register a side effect to run after a successful login (e.g. connect the WS relay client). */
  def addLoginHook(hook: IO[Unit]): IO[Unit] = loginHooks.update(_ :+ hook)
  /** Register a side effect to run on logout (e.g. disconnect the WS relay client). */
  def addLogoutHook(hook: IO[Unit]): IO[Unit] = logoutHooks.update(_ :+ hook)

  // ===== Identity =====

  def identity: IO[DeviceIdentity] = identityRef.get

  /** Update device capabilities and/or user description. Persists to disk and cloud. */
  def updateDeviceInfo(
    capabilities: Option[Map[String, String]] = None,
    userDescription: Option[String] = None
  ): IO[Unit] =
    for
      updated <- identityRef.modify { id =>
        val newId = id.copy(
          capabilities = capabilities.getOrElse(id.capabilities),
          userDescription = userDescription.getOrElse(id.userDescription)
        )
        (newId, newId)
      }
      _ <- DeviceIdentity.save(updated)
      _ <- callCloudFunction(
        "device/capabilities",
        "deviceId" -> updated.deviceId.asJson,
        "capabilities" -> updated.capabilities.asJson,
        "userDescription" -> updated.userDescription.asJson
      ).handleErrorWith(e => logger.debug(s"Cloud capabilities update: ${e.getMessage}"))
    yield ()

  /** Run capability self-check and update device identity + cloud. Called on startup. */
  def selfCheckCapabilities: IO[Unit] =
    for
      caps <- DeviceIdentity.detectCapabilities
      id <- identityRef.get
      _ <- if id.capabilities != caps then updateDeviceInfo(capabilities = Some(caps)) else IO.unit
    yield ()

  def isLoggedIn: IO[Boolean] = accountRef.get.map(_.isDefined)

  def account: IO[Option[AccountInfo]] = accountRef.get

  def userId: IO[Option[String]] = accountRef.get.map(_.map(_.userId))

  /** ActorRef of the sync actor — callers can send commands directly. */
  def syncActor: ActorRef[SyncCommand] = _syncActorRef

  /** P2P auth token: userId:deviceSecret. Used as Bearer in peer-to-peer requests. */
  def peerAuthToken: IO[String] =
    for
      uidOpt <- userId
      id <- identityRef.get
    yield uidOpt match
      case Some(uid) => s"$uid:${id.deviceSecret}"
      case None => ""

  /**
   * Verify an incoming P2P request. Bearer format: userId:deviceSecret.
   * Checks: userId matches local account AND deviceSecret is from a known peer.
   */
  def verifyPeerToken(bearer: String): IO[Boolean] =
    val parts = bearer.split(":", 2)
    if parts.length != 2 then IO.pure(false)
    else
      val (callerUserId, callerSecret) = (parts(0), parts(1))
      for
        myUserId <- userId
        peersMap <- peersRef.get
        myId <- identityRef.get
      yield myUserId match
        case Some(uid) if uid == callerUserId =>
          peersMap.values.exists(_.deviceSecret == callerSecret) ||
          myId.deviceSecret == callerSecret
        case _ => false

  // ===== Account =====

  /** Check if a username is available via cloud function. */
  def checkUsernameAvailable(username: String): IO[Boolean] =
    if username.length < 3 then IO.pure(true)
    else
      getCloudUrl.flatMap { cloudUrl =>
        callCloud(
          cloudUrl,
          Json.obj(
            "action" -> "auth/check-username".asJson,
            "username" -> username.asJson
          )
        ).map { json =>
          json.hcursor.downField("available").as[Boolean].getOrElse(false)
        }.handleError(_ => false)
      }

  /** Register a new Nebflow account via cloud function. */
  def register(username: String, password: String): IO[Either[MeshError, AccountInfo]] =
    registerOrLogin(username, password, register = true)

  /** Login to an existing Nebflow account via cloud function. */
  def login(username: String, password: String): IO[Either[MeshError, AccountInfo]] =
    registerOrLogin(username, password, register = false)

  /**
   * Shared login/register flow. Returns a structured MeshError instead of throwing,
   * so the gateway can map it to a precise HTTP response (e.g. CloudUrlNotConfigured →
   * prompt the user to configure the server URL).
   */
  private def registerOrLogin(
    username: String, password: String, register: Boolean
  ): IO[Either[MeshError, AccountInfo]] =
    val action = if register then "auth/register" else "auth/login"
    val flow: IO[AccountInfo] = for
      _ <- if register then validateUsername(username) else IO.unit
      _ <- if register then validatePassword(password) else IO.unit
      cloudUrl <- getCloudUrl
      resp <- callCloud(
        cloudUrl,
        Json.obj(
          "action" -> action.asJson,
          "username" -> username.asJson,
          "password" -> password.asJson
        )
      )
      acc <- parseAccountResponse(resp, username)
      _ <- AccountInfo.save(acc)
      _ <- accountRef.set(Some(acc))
      _ <- startDiscovery(acc.userId)
      _ <- logger.info(s"${if register then "Registered" else "Logged in"} as $username")
    yield acc

    flow.attempt.map {
      case Right(acc) => Right(acc)
      case Left(err)  => Left(MeshError.fromThrowable(err))
    }
  end registerOrLogin

  /** Logout — clear account, stop sync, clear peers. Preserves cloudUrl so re-login is seamless. */
  def logout: IO[Unit] =
    for
      _ <- AccountInfo.clear
      _ <- accountRef.set(None)
      _ <- peersRef.set(Map.empty)
      _ <- configRef.update(cfg => cfg.copy(enabled = false))
      _ <- configRef.get.flatMap(cfg => MeshConfig.save(cfg))
      _ = _syncActorRef ! SyncCommand.StopSync
      // Run logout hooks (e.g. disconnect the WS relay client) before we finish.
      _ <- logoutHooks.get.flatMap(_.sequence_.handleErrorWith(e =>
        logger.debug(s"Logout hook failed: ${e.getMessage}")
      ))
      _ <- logger.info("Logged out of Mesh")
    yield ()

  // ===== Config =====

  def meshConfig: IO[MeshConfig] = configRef.get

  def updateConfig(fn: MeshConfig => MeshConfig): IO[Unit] =
    configRef
      .modify { cfg =>
        val u = fn(cfg); (u, u)
      }
      .flatMap(MeshConfig.save)

  // ===== Peers =====

  def peers: IO[List[PeerInfo]] = peersRef.get.map(_.values.toList)

  /** Handle an incoming handshake from a peer. Called by the REST endpoint. */
  def handleHandshake(
    callerUserId: String,
    deviceId: String,
    deviceName: String,
    platform: String,
    callerIp: String,
    port: Int,
    callerSecret: String = ""
  ): IO[Unit] =
    for
      myUserId <- userId
      _ <- myUserId match
        case Some(uid) => IO.raiseWhen(callerUserId != uid)(new RuntimeException("User mismatch"))
        case None => IO.raiseError(new RuntimeException("Not logged in"))
      address = s"http://$callerIp:$port"
      peer = PeerInfo(deviceId, deviceName, platform, address, callerSecret)
      _ <- peersRef.update(_ + (deviceId -> peer))
      _ <- logger.info(s"Peer joined: $deviceName at $address")
    yield ()

  // ===== Discovery =====

  private def startDiscovery(uid: String): IO[Unit] =
    for
      // Enable discovery AND persist the *actual* config (preserves cloudUrl/syncIntervalSec).
      // Previously this wrote MeshConfig(enabled=true) — a blank default that wiped cloudUrl,
      // silently breaking discovery after a restart.
      _ <- configRef.update(_.copy(enabled = true))
      _ <- configRef.get.flatMap(MeshConfig.save)
      _ = _syncActorRef ! SyncCommand.StartSync
      // Fire one immediate discovery so this device registers itself and learns its peers
      // right at login, instead of waiting for the first periodic tick.
      _ <- cloudDiscover.handleErrorWith(e => logger.debug(s"Initial discovery: ${e.getMessage}"))
      // Run login hooks (e.g. connect the WS relay client for real-time tool delivery).
      _ <- loginHooks.get.flatMap(_.sequence_.handleErrorWith(e =>
        logger.debug(s"Login hook failed: ${e.getMessage}")
      ))
      _ <- logger.info(s"Started discovery for userId=${uid.take(8)}...")
    yield ()

  // ===== Sync =====

  /** Run one sync cycle: cloud discover to keep peer list fresh. */
  def runSyncCycle: IO[Unit] = cloudDiscover

  // ===== Cloud Discovery =====

  def cloudDiscover: IO[Unit] =
    for
      accOpt <- accountRef.get
      _ <- accOpt match
        case None => IO.unit
        case Some(acc) =>
          configRef.get.flatMap { cfg =>
            cfg.cloudUrl match
              case None => IO.unit
              case Some(url) =>
                for
                  addr <- detectLocalAddress
                  id <- identityRef.get
                  _ <- cloudRegister(url, acc, id, addr)
                  _ <- cloudLookup(url, acc, id)
                yield ()
          }
    yield ()

  private def cloudRegister(cloudUrl: String, acc: AccountInfo, id: DeviceIdentity, address: String): IO[Unit] =
    IO.blocking {
      val body = Json.obj(
        "action" -> "discover/register".asJson,
        "userId" -> acc.userId.asJson,
        "sessionToken" -> acc.sessionToken.asJson,
        "deviceId" -> id.deviceId.asJson,
        "deviceName" -> id.deviceName.asJson,
        "platform" -> id.platform.asJson,
        "address" -> address.asJson,
        "capabilities" -> id.capabilities.asJson,
        "userDescription" -> id.userDescription.asJson,
        "deviceSecret" -> id.deviceSecret.asJson
      )
      val resp = basicRequest
        .post(sttp.model.Uri.unsafeParse(cloudUrl))
        .contentType("application/json")
        .body(body.noSpaces)
        .readTimeout(10.seconds)
        .response(asStringAlways)
        .send(httpBackend)
      if !resp.code.isSuccess then throw new RuntimeException(s"Cloud register failed: ${resp.code}")
    }.handleErrorWith(e => logger.debug(s"Cloud register: ${e.getMessage}"))

  private def cloudLookup(cloudUrl: String, acc: AccountInfo, id: DeviceIdentity): IO[Unit] =
    IO.blocking {
      val body = Json.obj(
        "action" -> "discover/lookup".asJson,
        "userId" -> acc.userId.asJson,
        "sessionToken" -> acc.sessionToken.asJson,
        "deviceId" -> id.deviceId.asJson
      )
      val resp = basicRequest
        .post(sttp.model.Uri.unsafeParse(cloudUrl))
        .contentType("application/json")
        .body(body.noSpaces)
        .readTimeout(10.seconds)
        .response(asStringAlways)
        .send(httpBackend)
      if resp.code.isSuccess then resp.body else ""
    }.flatMap { body =>
      if body.isEmpty then IO.unit
      else
        io.circe.parser.decode[Json](body) match
          case Right(json) =>
            val dataCursor = json.hcursor.downField("data")
            val peersCursor =
              if dataCursor.succeeded then dataCursor.downField("peers")
              else json.hcursor.downField("peers")
            peersCursor.as[List[PeerInfo]] match
              case Right(cloudPeers) =>
                peersRef.update(_ ++ cloudPeers.map(p => p.deviceId -> p))
              case Left(e) =>
                logger.debug(s"Cloud lookup parse error: ${e.getMessage}")
            IO.unit
          case Left(e) =>
            logger.debug(s"Cloud lookup JSON error: ${e.getMessage}")
      end if
    }.handleErrorWith(e => logger.debug(s"Cloud lookup: ${e.getMessage}"))

  // ===== Cloud Call =====

  /**
   * Call a cloud function action with auth context injected automatically.
   * Requires the user to be logged in.
   * Returns the response JSON (unwrapped from the cloud function envelope).
   */
  def callCloudFunction(action: String, extraFields: (String, Json)*): IO[Json] =
    for
      accOpt <- accountRef.get
      acc <- IO.fromOption(accOpt)(new RuntimeException("Not logged in"))
      cloudUrl <- getCloudUrl
      body = Json
        .obj(
          "action" -> action.asJson,
          "userId" -> acc.userId.asJson,
          "sessionToken" -> acc.sessionToken.asJson
        )
        .deepMerge(Json.obj(extraFields*))
      resp <- callCloud(cloudUrl, body)
    yield resp

  /** Current cloud URL, or empty string if not configured. */
  def cloudUrl: IO[String] = configRef.get.map(_.cloudUrl.getOrElse(""))

  // ===== Helpers =====

  private[nebflow] lazy val httpBackend = DefaultSyncBackend()

  def detectLocalAddress: IO[String] =
    IO.blocking {
      val fallback = s"http://localhost:$serverPort"

      def tryUdp: String =
        try
          val socket = new java.net.DatagramSocket()
          try
            socket.connect(java.net.InetAddress.getByName("8.8.8.8"), 53)
            val addr = socket.getLocalAddress.getHostAddress
            if addr != "0.0.0.0" && addr != "127.0.0.1" then s"http://$addr:$serverPort"
            else ""
          finally socket.close()
        catch case _: Exception => ""

      def tryInterfaces: String =
        try
          var result = ""
          val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
          val it = java.util.Collections.list(interfaces).iterator
          while it.hasNext && result.isEmpty do
            val ni = it.next()
            if ni.isUp && !ni.isLoopback && !ni.isVirtual then
              val addrs = java.util.Collections.list(ni.getInetAddresses).iterator
              while addrs.hasNext && result.isEmpty do
                val addr = addrs.next()
                if addr.isInstanceOf[java.net.Inet4Address] && !addr.isLoopbackAddress then
                  result = s"http://${addr.getHostAddress}:$serverPort"
          result
        catch case _: Exception => ""

      tryUdp match
        case s if s.nonEmpty => s
        case _ =>
          tryInterfaces match
            case s if s.nonEmpty => s
            case _ => fallback
    }

  private def getCloudUrl: IO[String] =
    configRef.get.flatMap(_.cloudUrl match
      case Some(url) => IO.pure(url)
      case None => IO.raiseError(new RuntimeException("Cloud URL not configured")))

  private def callCloud(cloudUrl: String, body: Json): IO[Json] =
    IO.blocking {
      val backend = DefaultSyncBackend()
      val resp = basicRequest
        .post(sttp.model.Uri.unsafeParse(cloudUrl))
        .contentType("application/json")
        .body(body.noSpaces)
        .readTimeout(15.seconds)
        .response(asStringAlways)
        .send(backend)
      if !resp.code.isSuccess then throw new RuntimeException(s"Cloud API ${resp.code}: ${resp.body.take(200)}")
      io.circe.parser.decode[Json](resp.body) match
        case Right(json) =>
          json.hcursor.downField("code").as[Int] match
            case Right(code) if code != 200 =>
              val msg = json.hcursor.downField("message").as[String].getOrElse("Unknown")
              throw new RuntimeException(s"Cloud error $code: $msg")
            case _ => json
        case Left(err) => throw new RuntimeException(s"JSON decode error: ${err.getMessage}")
    }

  private def parseAccountResponse(json: Json, username: String): IO[AccountInfo] =
    val dataCursor = json.hcursor.downField("data")
    val data = if dataCursor.succeeded then dataCursor else json.hcursor
    for
      userId <- IO.fromEither(data.downField("userId").as[String].leftMap(_ => new RuntimeException("Missing userId")))
      token <- IO.fromEither(
        data.downField("sessionToken").as[String].leftMap(_ => new RuntimeException("Missing sessionToken"))
      )
    yield AccountInfo(userId, username, token)

  private def validateUsername(username: String): IO[Unit] =
    IO.raiseWhen(username.length < 3)(new RuntimeException("Username must be at least 3 characters")) *>
      IO.raiseWhen(!username.matches("^[a-zA-Z0-9_-]+$"))(
        new RuntimeException("Username can only contain letters, numbers, _ and -")
      )

  private def validatePassword(password: String): IO[Unit] =
    IO.raiseWhen(password.length < 6)(new RuntimeException("Password must be at least 6 characters"))

end MeshService

object MeshService:
  private val logger = NebflowLogger.forName("nebflow.mesh")

  def create(
    serverPort: Int = 8080,
    actorSystem: ActorSystem[?],
    dispatcher: Dispatcher[IO]
  ): IO[MeshService] =
    for
      identity <- DeviceIdentity.loadOrCreate
      config <- MeshConfig.load
      accountOpt <- AccountInfo.load
      idRef <- Ref.of[IO, DeviceIdentity](identity)
      accRef <- Ref.of[IO, Option[AccountInfo]](accountOpt)
      cfgRef <- Ref.of[IO, MeshConfig](config)
      peersRef <- Ref.of[IO, Map[String, PeerInfo]](Map.empty)
      serviceBox = new AtomicServiceBox
      syncActorRef <- IO {
        actorSystem.systemActorOf(
          syncActor(serviceBox, dispatcher),
          "mesh-sync"
        )
      }
      service = new MeshService(idRef, accRef, cfgRef, peersRef, serverPort, syncActorRef)
      _ = serviceBox.set(service)
      _ <- accountOpt match
        case Some(acc) =>
          logger.info(s"Restored mesh login: ${acc.username}") *>
            IO(syncActorRef ! SyncCommand.StartSync)
        case None => IO.unit
      _ = dispatcher.unsafeRunAndForget(
        service.selfCheckCapabilities.handleErrorWith(e => logger.debug(s"Capability detection: ${e.getMessage}"))
      )
    yield service

  private class AtomicServiceBox:
    @volatile private var _service: MeshService = uninitialized
    def set(s: MeshService): Unit = _service = s

    def get: MeshService =
      if _service == null then throw new IllegalStateException("MeshService not initialized")
      _service

  // ============================================================
  // Sync actor — event-driven, no polling when logged out
  // ============================================================

  private def syncActor(
    serviceBox: AtomicServiceBox,
    dispatcher: Dispatcher[IO]
  ): Behavior[SyncCommand] =
    Behaviors.withTimers { timers =>
      idle(timers, serviceBox, dispatcher)
    }

  private def idle(
    timers: org.apache.pekko.actor.typed.scaladsl.TimerScheduler[SyncCommand],
    serviceBox: AtomicServiceBox,
    dispatcher: Dispatcher[IO]
  ): Behavior[SyncCommand] =
    Behaviors.receiveMessage {
      case SyncCommand.StartSync =>
        doSync(serviceBox, dispatcher)
        scheduleNextTick(timers, serviceBox)
        running(timers, serviceBox, dispatcher)
      case SyncCommand.StopSync => Behaviors.same
      case SyncCommand.SyncTick => Behaviors.same
      // Activate the dormant fast-path: a peer joined (signaled via WS peer-joined push).
      // Discover immediately even before we've entered the running state, so newly-logged-in
      // peers become visible in seconds rather than after the first periodic tick.
      case SyncCommand.PeerDiscovered =>
        doSync(serviceBox, dispatcher)
        Behaviors.same
    }

  private def running(
    timers: org.apache.pekko.actor.typed.scaladsl.TimerScheduler[SyncCommand],
    serviceBox: AtomicServiceBox,
    dispatcher: Dispatcher[IO]
  ): Behavior[SyncCommand] =
    Behaviors.receiveMessage {
      case SyncCommand.SyncTick =>
        doSync(serviceBox, dispatcher)
        scheduleNextTick(timers, serviceBox)
        Behaviors.same
      case SyncCommand.StopSync =>
        timers.cancelAll()
        idle(timers, serviceBox, dispatcher)
      case SyncCommand.StartSync => Behaviors.same
      case SyncCommand.PeerDiscovered =>
        doSync(serviceBox, dispatcher)
        Behaviors.same
    }

  private def scheduleNextTick(
    timers: org.apache.pekko.actor.typed.scaladsl.TimerScheduler[SyncCommand],
    serviceBox: AtomicServiceBox
  ): Unit =
    // Read the configured interval instead of a hardcoded constant, so PATCH /mesh/config
    // (syncIntervalSec) actually takes effect. Pure in-memory Ref read — safe to run inline.
    val secs =
      try
        import cats.effect.unsafe.implicits.global
        serviceBox.get.meshConfig.unsafeRunSync().syncIntervalSec.max(10)
      catch case _: Exception => 300
    timers.startSingleTimer(SyncCommand.SyncTick, secs.seconds)

  private def doSync(serviceBox: AtomicServiceBox, dispatcher: Dispatcher[IO]): Unit =
    val service = serviceBox.get
    dispatcher.unsafeRunAndForget(
      service.runSyncCycle.handleErrorWith(e => logger.warn(s"Sync cycle failed: ${e.getMessage}"))
    )

end MeshService
