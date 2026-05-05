package nebflow.gateway

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json

/**
 * WebSocket multicast hub — decouples root agents from individual connections.
 * All WebSocket connections register their per-connection send callback here;
 * root agents broadcast events to every registered connection.
 */
class WsHub:

  private val connsRef: Ref[IO, Map[String, Json => IO[Unit]]] =
    Ref.unsafe[IO, Map[String, Json => IO[Unit]]](Map.empty)

  /** Register a connection; returns a handle for later unregister. */
  def register(wsSend: Json => IO[Unit]): IO[String] =
    val id = java.util.UUID.randomUUID().toString.take(8)
    connsRef.update(_ + (id -> wsSend)) *> IO.pure(id)

  /** Remove a connection. */
  def unregister(id: String): IO[Unit] =
    connsRef.update(_ - id)

  /** Broadcast a JSON message to every registered connection. */
  def broadcast(json: Json): IO[Unit] =
    connsRef.get.flatMap(_.values.toList.traverse_(_.apply(json)))

  /** Send to a single connection by its handle. */
  def sendTo(id: String, json: Json): IO[Unit] =
    connsRef.get.flatMap(_.get(id).traverse_(_.apply(json)))
end WsHub
