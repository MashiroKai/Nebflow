package nebflow.server

import cats.effect.{IO, Ref}
import fs2.concurrent.Topic
import io.circe.syntax.given
import io.circe.Json
import nebflow.core.NebflowLogger

/**
 * Manages WebSocket connections for real-time relay push.
 *
 * When a device connects, it registers here. When a relay command is submitted
 * for that device, the command is pushed instantly via WebSocket (no polling).
 */
class WebSocketManager private (
  connectionsRef: Ref[IO, Map[(String, String), fs2.concurrent.Topic[IO, String]]]
):
  private val logger = NebflowLogger.forName("nebflow.server.ws")

  /** Register a device's WebSocket connection. Returns the Topic to listen on. */
  def register(userId: String, deviceId: String): IO[Topic[IO, String]] =
    for
      topic <- Topic[IO, String]
      key = (userId, deviceId)
      _ <- connectionsRef.update(_ + (key -> topic))
      _ <- logger.info(s"WebSocket registered: user=${userId.take(8)} device=$deviceId")
    yield topic

  /** Unregister a device's WebSocket connection. */
  def unregister(userId: String, deviceId: String): IO[Unit] =
    val key = (userId, deviceId)
    connectionsRef.update(_.removed(key)) *>
      logger.info(s"WebSocket unregistered: user=${userId.take(8)} device=$deviceId")

  /**
   * Push a relay command to the target device via WebSocket.
   * Best-effort: if device is not connected, the command stays in the relay
   * queue and will be picked up by HTTP polling.
   */
  def pushRelayCommand(
    userId: String, toDeviceId: String,
    relayId: String, fromDeviceId: String,
    action: String, params: Json
  ): IO[Unit] =
    val key = (userId, toDeviceId)
    connectionsRef.get.flatMap { m =>
      m.get(key) match
        case Some(topic) =>
          val msg = Json.obj(
            "type" -> "relay".asJson,
            "relayId" -> relayId.asJson,
            "fromDeviceId" -> fromDeviceId.asJson,
            "action" -> action.asJson,
            "params" -> params
          )
          topic.publish1(msg.noSpaces).void *> logger.debug(s"WS push relay $relayId to $toDeviceId")
        case None =>
          IO.unit
    }

  /**
   * Push relay result back to the originator's devices via WebSocket.
   * Best-effort: falls back to HTTP polling if device is not connected.
   */
  def pushRelayResult(userId: String, relayId: String, result: String, error: String): IO[Unit] =
    val msg = Json.obj(
      "type" -> "relay-result".asJson,
      "relayId" -> relayId.asJson,
      "result" -> result.asJson,
      "error" -> error.asJson
    )
    connectionsRef.get.flatMap { m =>
      val userTopics = m.filter { case ((uid, _), _) => uid == userId }.values
      userTopics.foldLeft(IO.unit: IO[Unit])((acc, topic) => acc *> topic.publish1(msg.noSpaces).void)
    }

  def isConnected(userId: String, deviceId: String): IO[Boolean] =
    connectionsRef.get.map(_.contains((userId, deviceId)))

end WebSocketManager

object WebSocketManager:
  def create: IO[WebSocketManager] =
    Ref.of[IO, Map[(String, String), Topic[IO, String]]](Map.empty)
      .map(new WebSocketManager(_))
