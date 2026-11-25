package nebflow.gateway

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import org.http4s.websocket.WebSocketFrame

/** Bounded FIFO: classification travels with each frame until it is consumed. */
private[gateway] final class WsOutboundBuffer private (
  capacity: Int,
  state: Ref[IO, WsOutboundBuffer.State]
):
  import WsOutboundBuffer.*

  def enqueue(frame: WebSocketFrame, isStream: Boolean): IO[Result] =
    IO.uncancelable { _ => state.modify { s =>
      val entry = (frame, isStream)
      if s.frames.size < capacity then (s.copy(frames = s.frames :+ entry), (Accepted, Some(s.ready)))
      else if isStream then
        val index = s.frames.indexWhere(_._2)
        if index >= 0 then
          (s.copy(frames = s.frames.patch(index, Nil, 1) :+ entry), (Evicted, None))
        else (s, (Full, None))
      else (s, (Full, None))
    }.flatMap { case (result, wake) => wake.traverse_(_.complete(()).void).as(result) } }

  def take: IO[WebSocketFrame] =
    Deferred[IO, Unit].flatMap { nextReady =>
      state.modify { s =>
        s.frames.headOption match
          case Some((frame, _)) =>
            val rest = s.frames.tail
            (s.copy(frames = rest, ready = if rest.isEmpty then nextReady else s.ready), Right(frame))
          case None => (s, Left(s.ready))
      }.flatMap {
        case Right(frame) => IO.pure(frame)
        case Left(ready) => ready.get *> take
      }
    }

private[gateway] object WsOutboundBuffer:
  sealed trait Result
  case object Accepted extends Result
  case object Evicted extends Result
  case object Full extends Result
  private final case class State(frames: Vector[(WebSocketFrame, Boolean)], ready: Deferred[IO, Unit])

  def create(capacity: Int): IO[WsOutboundBuffer] =
    require(capacity > 0)
    for
      ready <- Deferred[IO, Unit]
      state <- Ref.of[IO, State](State(Vector.empty, ready))
    yield new WsOutboundBuffer(capacity, state)
