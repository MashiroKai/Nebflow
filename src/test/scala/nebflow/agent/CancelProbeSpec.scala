package nebflow.agent

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.Stream
import munit.FunSuite
import scala.concurrent.duration.*

class CancelProbeSpec extends FunSuite:
  test("IO.never fiber cancel completes immediately"):
    val r = IO.never[Unit].start.flatMap(_.cancel.timeout(2.seconds)).attempt.unsafeRunSync()
    assert(r.isRight, s"IO.never cancel must complete, got $r")

  test("fs2 Stream.never compile cancel completes"):
    val r = Stream.never[IO].compile.drain.start.flatMap(_.cancel.timeout(2.seconds)).attempt.unsafeRunSync()
    assert(r.isRight, s"Stream.never compile cancel must complete, got $r")

  test("fs2 never through simple pipe cancel completes"):
    val r = Stream.never[IO].through(identity).compile.drain.start
      .flatMap(_.cancel.timeout(2.seconds)).attempt.unsafeRunSync()
    assert(r.isRight, s"pipe cancel must complete, got $r")
end CancelProbeSpec

class CancelProbe2Spec extends FunSuite:
  test("turn-fiber-shaped never stream cancel completes"):
    val io = IO.unit *> Stream.never[IO]
      .evalTap(_ => IO.unit)
      .compile.toList
      .flatMap(_ => IO.unit)
      .attempt
      .flatMap(_ => IO.unit)
    val r = io.start.flatMap(_.cancel.timeout(3.seconds)).attempt.unsafeRunSync()
    assert(r.isRight, s"turn-fiber-shaped cancel must complete, got $r")
end CancelProbe2Spec
