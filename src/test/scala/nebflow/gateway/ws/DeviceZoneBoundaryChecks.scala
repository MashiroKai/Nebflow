package nebflow.gateway.ws

import cats.effect.{IO, Ref}
import cats.effect.std.Queue
import cats.syntax.all.*
import munit.Assertions.*
import nebflow.actor.{ActorPath, ActorRef, AgentCommand}
import scala.concurrent.duration.*

/** Existing boundary assertions, run in order on the same actor and queues. */
object DeviceZoneBoundaryChecks:
  def run(
    ref: ActorRef[AgentCommand],
    captured: Queue[IO, Option[String]],
    done: Queue[IO, Unit],
    blockTool: Ref[IO, Boolean],
    overrideCall: Ref[IO, Option[Boolean]],
    toolEntered: Queue[IO, Unit],
    toolRelease: Queue[IO, Unit]
  ): IO[Unit] =
    for
      barrier <- Queue.unbounded[IO, List[String]]
      barrierRef = new ActorRef[List[String]]:
        def path: ActorPath = null
        def !(msg: List[String]): IO[Unit] = barrier.offer(msg)
        def ?[R](f: ActorRef[R] => List[String], timeout: Option[FiniteDuration] = Some(30.seconds)): IO[R] =
          IO.raiseError(new Exception("not used"))
      // Enter the real registered tool, queue the immediate while it is held,
      // then use the actor's AskUser unavailable response as a FIFO barrier.
      _ <- blockTool.set(true)
      _ <- ref ! AgentCommand.UserInput("boundary start", clientMessageId = Some("boundary-start"),
        fromUser = true, deviceZone = Some("Asia/Tokyo"))
      boundaryFirst <- captured.take.timeout(30.seconds)
      _ = assertEquals(boundaryFirst, Some("Asia/Tokyo"))
      _ <- toolEntered.take.timeout(30.seconds)
      _ <- ref ! AgentCommand.ImmediateInput("same-zone injection", fromUser = true, deviceZone = Some("Asia/Tokyo"))
      _ <- ref ! AgentCommand.AskUser("boundary-fifo", Nil, Some(barrierRef))
      barrierAnswer <- barrier.take.timeout(30.seconds)
      _ = assert(nebflow.shared.AskUserDispatch.isFailure(barrierAnswer) && barrierAnswer.head.contains("boundary-fifo"))
      _ <- overrideCall.set(Some(true))
      _ <- toolRelease.offer(())
      boundaryNext <- captured.take.timeout(30.seconds)
      _ = assertEquals(boundaryNext, Some("Asia/Tokyo"))
      _ <- overrideCall.set(Some(false))
      _ <- done.take.timeout(30.seconds)
      _ <- (for
        _ <- overrideCall.set(Some(true))
        _ <- blockTool.set(true)
        _ <- ref ! AgentCommand.UserInput("different start", clientMessageId = Some("different-start"),
          fromUser = true, deviceZone = Some("Asia/Tokyo"))
        first <- captured.take.timeout(30.seconds)
        _ = assertEquals(first, Some("Asia/Tokyo"))
        _ <- toolEntered.take.timeout(30.seconds)
        _ <- ref ! AgentCommand.ImmediateInput("different zone", fromUser = true, deviceZone = Some("Europe/Paris"))
        _ <- ref ! AgentCommand.AskUser("different-fifo", Nil, Some(barrierRef))
        ack <- barrier.take.timeout(30.seconds)
        _ = assert(nebflow.shared.AskUserDispatch.isFailure(ack) && ack.head.contains("different-fifo"))
        _ <- overrideCall.set(Some(true))
        _ <- toolRelease.offer(())
        second <- captured.take.timeout(30.seconds)
        _ = assertEquals(second, None)
        _ <- overrideCall.set(Some(false))
        _ <- done.take.timeout(30.seconds)
      yield ())
      _ <- (for
        _ <- overrideCall.set(Some(true))
        _ <- blockTool.set(true)
        _ <- ref ! AgentCommand.UserInput("missing start", clientMessageId = Some("missing-start"),
          fromUser = true, deviceZone = Some("Asia/Tokyo"))
        first <- captured.take.timeout(30.seconds)
        _ = assertEquals(first, Some("Asia/Tokyo"))
        _ <- toolEntered.take.timeout(30.seconds)
        _ <- ref ! AgentCommand.ImmediateInput("missing zone", fromUser = true)
        _ <- ref ! AgentCommand.AskUser("missing-fifo", Nil, Some(barrierRef))
        ack <- barrier.take.timeout(30.seconds)
        _ = assert(nebflow.shared.AskUserDispatch.isFailure(ack) && ack.head.contains("missing-fifo"))
        _ <- overrideCall.set(Some(true))
        _ <- toolRelease.offer(())
        second <- captured.take.timeout(30.seconds)
        _ = assertEquals(second, None)
        _ <- overrideCall.set(Some(false))
        _ <- done.take.timeout(30.seconds)
      yield ())
      _ <- (for
        _ <- overrideCall.set(Some(true))
        _ <- blockTool.set(true)
        _ <- ref ! AgentCommand.UserInput("mail boundary start", clientMessageId = Some("mail-boundary-start"),
          fromUser = true, deviceZone = Some("Asia/Tokyo"))
        first <- captured.take.timeout(30.seconds)
        _ = assertEquals(first, Some("Asia/Tokyo"))
        _ <- toolEntered.take.timeout(30.seconds)
        _ <- ref ! AgentCommand.UserInput("mail injected", source = Some("mail"))
        _ <- ref ! AgentCommand.AskUser("mail-fifo", Nil, Some(barrierRef))
        ack <- barrier.take.timeout(30.seconds)
        _ = assert(nebflow.shared.AskUserDispatch.isFailure(ack) && ack.head.contains("mail-fifo"))
        _ <- overrideCall.set(Some(true))
        _ <- toolRelease.offer(())
        second <- captured.take.timeout(30.seconds)
        _ = assertEquals(second, None)
        _ <- overrideCall.set(Some(false))
        _ <- done.take.timeout(30.seconds)
      yield ())
    yield ()
