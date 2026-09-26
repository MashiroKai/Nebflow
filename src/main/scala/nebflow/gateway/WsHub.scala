package nebflow.gateway

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import nebflow.core.{EventSink, WsHubPort}

/**
 * WebSocket multicast hub — decouples root agents from individual connections.
 * All WebSocket connections register their per-connection send callback here;
 * root agents broadcast events to every registered connection.
 */
// Phase 5 解耦:混入 core 窄端口(实现原地不搬;core 的 TaskStuckWatcher 只面向
// broadcast 这一个成员编程)。
class WsHub extends EventSink, WsHubPort:

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
    connsRef.get.flatMap { conns =>
      conns.values.toList.traverse_(send => send(json).handleErrorWith(_ => IO.unit))
    }

  /** Send to a single connection by its handle. */
  def sendTo(id: String, json: Json): IO[Unit] =
    connsRef.get.flatMap(_.get(id).traverse_(send => send(json).handleErrorWith(_ => IO.unit)))
end WsHub
