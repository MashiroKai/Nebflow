package nebflow.mesh

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Decoder, Json, JsonObject}
import nebflow.core.NebflowLogger
import nebflow.core.tools.{ToolContext, ToolRegistry}
import nebflow.gateway.{Folder, SessionStore}
import nebflow.shared.{*, given}

import scala.concurrent.duration.*

/**
 * Cloud-based session synchronization service.
 *
 * Design: cloud is the single source of truth for session data.
 *   - Push: local → cloud (when session changes)
 *   - Pull: cloud → local (on startup, session switch)
 *   - Busy lock: cloud-based, prevents two devices from editing the same session
 *
 * All methods are no-ops when not logged in.
 */
class CloudSessionSync private (
  meshService: MeshService,
  sessionStore: SessionStore,
  private val _relayActorRef: Option[org.apache.pekko.actor.typed.ActorRef[RelayCommand]]
):
  private val logger = NebflowLogger.forName("nebflow.cloud-sync")

  /** Notify all reachable peers to trigger immediate sync. Fire-and-forget. */
  def notifyPeersSync: IO[Unit] =
    meshService.notifyPeers("file")

  // ===== Session Index Sync =====

  /** Push local session index (metadata + folders) to cloud. */
  def pushIndex: IO[Unit] =
    for
      loggedIn <- meshService.isLoggedIn
      _ <-
        if !loggedIn then IO.unit
        else
          for
            sessions <- sessionStore.listSessions
            folders <- sessionStore.listAllFolders
            id <- meshService.identity
            _ <- meshService.callCloudFunction(
              "session/push-index",
              "sessions" -> sessions.asJson,
              "folders" -> folders.asJson
            )
          yield ()
    yield ()

  /**
   * Pull cloud session index. Returns (sessions, folders) from cloud.
   * Caller (SessionStore) is responsible for merging with local.
   */
  def pullIndex: IO[CloudIndexSnapshot] =
    meshService.callCloudFunction("session/pull-index").flatMap { json =>
      val hc = json.hcursor
      for
        sessions <- IO.fromEither(
          hc.downField("sessions")
            .as[List[SessionMeta]]
            .leftMap(e => new RuntimeException(s"Decode sessions: ${e.getMessage}"))
        )
        folders <- IO.fromEither(
          hc.downField("folders")
            .as[List[Folder]]
            .leftMap(e => new RuntimeException(s"Decode folders: ${e.getMessage}"))
        )
      yield CloudIndexSnapshot(sessions, folders, hc.downField("updatedAt").as[Long].getOrElse(0L))
    }

  // ===== Session Data Sync =====

  /**
   * Push a single session's messages + UI messages to cloud.
   * Called after local session is updated (message saved, AI reply complete).
   */
  def pushSession(sessionId: String): IO[Unit] =
    for
      loggedIn <- meshService.isLoggedIn
      _ <-
        if !loggedIn then IO.unit
        else
          for
            messages <- sessionStore.loadMessagesForSession(sessionId)
            (_, uiTotal) <- sessionStore.getUiMessages(sessionId, 0, 1)
            uiMessages <-
              if uiTotal > 0 then sessionStore.getUiMessages(sessionId, 0, uiTotal).map(_._1)
              else IO.pure(List.empty[UiMessage])
            metaOpt <- sessionStore.getSessionMeta(sessionId)
            metaJson = metaOpt
              .map(m =>
                Json.obj(
                  "name" -> m.name.asJson,
                  "agentName" -> m.agentName.asJson,
                  "folderId" -> m.folderId.asJson
                )
              )
              .getOrElse(Json.obj())
            _ <- meshService.callCloudFunction(
              "session/push",
              "sessionId" -> sessionId.asJson,
              "messages" -> messages.asJson,
              "uiMessages" -> uiMessages.asJson,
              "meta" -> metaJson
            )
          yield ()
    yield ()

  /**
   * Pull a single session's data from cloud.
   * Returns Some(messages, uiMessages) if found, None if not in cloud.
   */
  def pullSession(sessionId: String): IO[Option[CloudSessionData]] =
    meshService.callCloudFunction("session/pull", "sessionId" -> sessionId.asJson).flatMap { json =>
      val found = json.hcursor.downField("found").as[Boolean].getOrElse(false)
      if !found then IO.pure(None)
      else
        for
          messages <- IO.fromEither(
            json.hcursor
              .downField("messages")
              .as[List[Message]]
              .leftMap(e => new RuntimeException(s"Decode messages: ${e.getMessage}"))
          )
          uiMessages <- IO.fromEither(
            json.hcursor
              .downField("uiMessages")
              .as[List[UiMessage]]
              .leftMap(e => new RuntimeException(s"Decode uiMessages: ${e.getMessage}"))
          )
          updatedAt = json.hcursor.downField("updatedAt").as[Long].getOrElse(0L)
        yield Some(CloudSessionData(messages, uiMessages, updatedAt))
      end if
    }

  /** Delete a session from cloud. */
  def deleteFromCloud(sessionId: String): IO[Unit] =
    meshService.callCloudFunction("session/delete", "sessionId" -> sessionId.asJson).void

  // ===== Busy Lock =====

  /**
   * Try to acquire busy lock for a session.
   * Returns true if acquired (or already held by this device), false if held by another.
   */
  def tryAcquireBusy(sessionId: String): IO[Boolean] =
    for
      loggedIn <- meshService.isLoggedIn
      result <-
        if !loggedIn then IO.pure(true) // no lock when offline — just proceed
        else
          for
            id <- meshService.identity
            resp <- meshService.callCloudFunction(
              "session/busy",
              "sessionId" -> sessionId.asJson,
              "busy" -> true.asJson,
              "deviceId" -> id.deviceId.asJson,
              "deviceName" -> id.deviceName.asJson
            )
            acquired = resp.hcursor.downField("acquired").as[Boolean].getOrElse(false)
          yield acquired
    yield result

  /** Release busy lock for a session (only if held by this device). */
  def releaseBusy(sessionId: String): IO[Unit] =
    for
      loggedIn <- meshService.isLoggedIn
      _ <-
        if !loggedIn then IO.unit
        else
          for
            id <- meshService.identity
            _ <- meshService.callCloudFunction(
              "session/busy",
              "sessionId" -> sessionId.asJson,
              "busy" -> false.asJson,
              "deviceId" -> id.deviceId.asJson
            )
          yield ()
    yield ()

  /**
   * Check busy state of a session.
   * Returns Some(deviceName) if busy by another device, None if free or held by this device.
   */
  def checkBusyByOther(sessionId: String): IO[Option[String]] =
    for
      loggedIn <- meshService.isLoggedIn
      result <-
        if !loggedIn then IO.pure(None)
        else
          for
            id <- meshService.identity
            resp <- meshService.callCloudFunction(
              "session/busy",
              "sessionId" -> sessionId.asJson
            )
            busy = resp.hcursor.downField("busy").as[Boolean].getOrElse(false)
            busyDeviceId = resp.hcursor.downField("busyDeviceId").as[String].getOrElse("")
            busyDeviceName = resp.hcursor.downField("busyDeviceName").as[String].getOrElse("")
          yield
            if busy && busyDeviceId != id.deviceId then Some(busyDeviceName)
            else None
    yield result

  // ===== Cloud Relay =====

  /**
   * Submit a command to a remote device via cloud relay.
   * Returns relayId for later result polling.
   */
  def relaySubmit(toDeviceId: String, action: String, params: Json): IO[String] =
    for
      id <- meshService.identity
      resp <- meshService.callCloudFunction(
        "relay/submit",
        "fromDeviceId" -> id.deviceId.asJson,
        "toDeviceId" -> toDeviceId.asJson,
        "action" -> action.asJson,
        "params" -> params
      )
      relayId <- IO.fromEither(
        resp.hcursor
          .downField("relayId")
          .as[String]
          .leftMap(e => new RuntimeException(s"Missing relayId: ${e.getMessage}"))
      )
    yield relayId

  /** Target device polls for pending relay commands. */
  def relayPoll: IO[List[RelayCommand]] =
    for
      id <- meshService.identity
      resp <- meshService.callCloudFunction("relay/poll", "deviceId" -> id.deviceId.asJson)
      commands <- IO.fromEither(
        resp.hcursor
          .downField("commands")
          .as[List[RelayCommand]]
          .leftMap(e => new RuntimeException(s"Decode commands: ${e.getMessage}"))
      )
    yield commands

  /** Target device submits execution result. */
  def relaySubmitResult(relayId: String, result: String, error: Option[String]): IO[Unit] =
    meshService
      .callCloudFunction(
        "relay/result",
        "relayId" -> relayId.asJson,
        "result" -> result.asJson,
        "error" -> error.getOrElse("").asJson
      )
      .void

  /**
   * Poll for relay command result. Blocks until result is available or timeout.
   * Polls every 2 seconds, up to 60 seconds.
   */
  def relayFetchResultBlocking(relayId: String, timeout: Duration = 120.seconds): IO[Either[String, String]] =
    val pollInterval = 2.seconds
    val deadline = timeout

    def pollOnce: IO[Option[Either[String, String]]] =
      meshService.callCloudFunction("relay/fetch-result", "relayId" -> relayId.asJson).flatMap { json =>
        val status = json.hcursor.downField("status").as[String].getOrElse("pending")
        status match
          case "done" =>
            IO.pure(Some(Right(json.hcursor.downField("result").as[String].getOrElse(""))))
          case "error" =>
            IO.pure(Some(Left(json.hcursor.downField("error").as[String].getOrElse("Unknown error"))))
          case _ => IO.pure(None)
      }

    def loop(remaining: Duration): IO[Either[String, String]] =
      if remaining <= Duration.Zero then IO.pure(Left("Relay timeout — remote device may be offline"))
      else
        pollOnce.flatMap {
          case Some(result) => IO.pure(result)
          case None => IO.sleep(pollInterval) *> loop(remaining - pollInterval)
        }

    loop(deadline)
  end relayFetchResultBlocking

  // ===== Sync Cycle =====

  /**
   * Full sync cycle — called periodically by the mesh sync actor (every 5 min).
   * 1. Pull cloud index and merge with local
   * 2. Push local index
   * 3. Push ALL sessions that are newer locally than cloud (ensures full history available)
   * 4. Poll + execute relay commands from other devices
   */
  def syncCycle: IO[Unit] =
    for
      loggedIn <- meshService.isLoggedIn
      _ <-
        if !loggedIn then IO.unit
        else
          for
            // Pull cloud index
            cloudSnapshot <- pullIndex.handleErrorWith(e =>
              logger.warn(s"Pull index failed: ${e.getMessage}").as(CloudIndexSnapshot(Nil, Nil, 0))
            )
            _ <- sessionStore.mergeCloudIndex(cloudSnapshot.sessions, cloudSnapshot.folders)
            // Push local index
            _ <- pushIndex.handleErrorWith(e => logger.warn(s"Push index failed: ${e.getMessage}"))
            // Push all sessions that are newer locally than cloud (batch sync)
            localSessions <- sessionStore.listSessions
            cloudUpdatedAt = cloudSnapshot.sessions.map(s => s.id -> s.updatedAt).toMap
            needPush = localSessions.filter { ls =>
              cloudUpdatedAt.get(ls.id) match
                case Some(cUpdated) => ls.updatedAt > cUpdated
                case None => true // not in cloud yet
            }
            _ <-
              if needPush.isEmpty then IO.unit
              else
                logger.info(s"Pushing ${needPush.size} sessions to cloud") *>
                  needPush.traverse_(s =>
                    pushSession(s.id).handleErrorWith(e => logger.debug(s"Push ${s.id.take(8)}: ${e.getMessage}"))
                  )
            // Process relay commands
            _ <- processRelayCommands.handleErrorWith(e => logger.warn(s"Relay poll failed: ${e.getMessage}"))
          yield ()
    yield ()

  /**
   * Fast sync cycle — near-real-time cross-device visibility.
   * Runs every 5 seconds. Focuses on PULL (push is handled by hooks).
   * Also pushes index so new local sessions appear on other devices quickly.
   */
  def fastSyncCycle: IO[Unit] =
    for
      loggedIn <- meshService.isLoggedIn
      _ <-
        if !loggedIn then IO.unit
        else
          for
            // 1. Pull session index — lightweight metadata only
            _ <- pullIndex
              .flatMap(snapshot => sessionStore.mergeCloudIndex(snapshot.sessions, snapshot.folders))
              .handleErrorWith(e => logger.debug(s"Fast pull index: ${e.getMessage}"))
            // 2. Push index — so other devices see our new sessions quickly
            _ <- pushIndex.handleErrorWith(e => logger.debug(s"Fast push index: ${e.getMessage}"))
            // 3. File fingerprint exchange — just hashes, content only if changed
            _ <- meshService.syncFilesWithCloud.handleErrorWith(e => logger.debug(s"Fast file sync: ${e.getMessage}"))
          yield ()
    yield ()

  /**
   * Start background pollers: fast sync (5s) + relay (10s).
   *  fastCycle is injected from IncrementalSyncEngine for blob-based state sync.
   */
  def startBackgroundPollers(
    dispatcher: cats.effect.std.Dispatcher[IO],
    fastCycle: IO[Unit] = IO.unit
  ): Unit =
    dispatcher.unsafeRunAndForget(fastLoop(fastCycle))
    dispatcher.unsafeRunAndForget(relayLoop)

  private def fastLoop(fastCycle: IO[Unit]): IO[Unit] =
    meshService.isLoggedIn.flatMap { loggedIn =>
      val action = if loggedIn then fastCycle.handleErrorWith(_ => IO.unit) else IO.unit
      action.flatMap(_ => IO.sleep(3.seconds)).flatMap(_ => fastLoop(fastCycle))
    }

  // ===== Relay Command Execution (target device side) =====

  /**
   * Poll for pending relay commands from other devices and execute them locally.
   * This makes the local device act as a relay target.
   */
  def processRelayCommands: IO[Unit] =
    for
      commands <- relayPoll.handleErrorWith(e => logger.debug(s"Relay poll: ${e.getMessage}").as(Nil))
      _ <- commands.traverse_ { cmd =>
        executeRelayCommand(cmd).handleErrorWith(e =>
          logger.warn(s"Relay exec ${cmd.action} (${cmd.relayId.take(8)}) failed: ${e.getMessage}")
        )
      }
    yield ()

  /**
   * Start a background relay poller with a fast interval (10 seconds).
   * Called once on startup — checks login status each iteration.
   */
  def startRelayPoller(dispatcher: cats.effect.std.Dispatcher[IO]): Unit =
    dispatcher.unsafeRunAndForget(relayLoop)

  private def relayLoop: IO[Unit] =
    meshService.isLoggedIn.flatMap { loggedIn =>
      val action = if loggedIn then processRelayCommands.handleErrorWith(_ => IO.unit) else IO.unit
      action.flatMap(_ => IO.sleep(10.seconds)).flatMap(_ => relayLoop)
    }

  private def executeRelayCommand(cmd: RelayCommand): IO[Unit] =
    val toolOpt = ToolRegistry.TOOL_MAP.get(cmd.action)
    toolOpt match
      case Some(tool) =>
        val params = cmd.params.asObject.getOrElse(JsonObject.empty)
        val ctx = ToolContext(projectRoot = System.getProperty("user.dir", "."))
        for
          result <- tool.call(params, ctx)
          _ <- result match
            case Right(output) => relaySubmitResult(cmd.relayId, output, None)
            case Left(err) => relaySubmitResult(cmd.relayId, "", Some(err.message))
        yield ()
      case None =>
        relaySubmitResult(cmd.relayId, "", Some(s"Unknown tool: ${cmd.action}"))

end CloudSessionSync

// ===== Data Types =====

case class CloudIndexSnapshot(sessions: List[SessionMeta], folders: List[Folder], updatedAt: Long)

case class CloudSessionData(messages: List[Message], uiMessages: List[UiMessage], updatedAt: Long)

/** A pending relay command received by the target device. */
case class RelayCommand(relayId: String, fromDeviceId: String, action: String, params: Json)

object RelayCommand:

  given Decoder[RelayCommand] = Decoder.instance { c =>
    for
      relayId <- c.downField("relayId").as[String]
      fromDeviceId <- c.downField("fromDeviceId").as[String]
      action <- c.downField("action").as[String]
      params <- c.downField("params").as[Json]
    yield RelayCommand(relayId, fromDeviceId, action, params)
  }

object CloudSessionSync:
  private val logger = NebflowLogger.forName("nebflow.cloud-sync")

  /** Create a CloudSessionSync directly (no IO needed — constructor is pure). */
  def apply(meshService: MeshService, sessionStore: SessionStore): CloudSessionSync =
    new CloudSessionSync(meshService, sessionStore, None)

end CloudSessionSync
