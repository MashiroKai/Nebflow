package nebflow.llm

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.Stream
import munit.CatsEffectSuite
import nebflow.llm.LlmInterface.inactivityTimeout

import scala.concurrent.duration.*

class StreamWatchdogSpec extends CatsEffectSuite:

  private def runStream[O](
    stream: Stream[IO, O],
    firstToken: FiniteDuration,
    subsequent: FiniteDuration
  ): Either[Throwable, List[O]] =
    stream
      .through(inactivityTimeout(firstToken, subsequent))
      .compile
      .toList
      .attempt
      .unsafeRunSync()

  private def errMsg(e: Either[Throwable, ?]): String =
    e.fold(_.getMessage, _ => "")

  // === Phase 1: First-Token Timeout ===

  test("first-token timeout fires when stream never emits") {
    val stream = Stream.eval(IO.never[Option[Int]]).timeout(5.seconds)
    val result = runStream(stream, 200.millis, 1.second)
    assert(result.isLeft, "should timeout")
    val msg = errMsg(result)
    assert(msg.contains("no response"), s"got: $msg")
    assert(
      result.fold(_.isInstanceOf[java.util.concurrent.TimeoutException], _ => false),
      "should be TimeoutException"
    )
  }

  test("first-token timeout fires when stream is slow to start") {
    val stream = Stream.eval(IO.sleep(3.seconds)).map(_ => 1) ++ Stream.emit(2)
    val result = runStream(stream, 1.second, 5.seconds)
    assert(result.isLeft)
    assert(errMsg(result).contains("no response"))
  }

  // === Phase 2: Inactivity Timeout (after first chunk) ===

  test("inactivity timeout fires when stream stalls after first chunk") {
    val stream = Stream.emit(1) ++ Stream.eval(IO.sleep(3.seconds)).map(_ => 2)
    val result = runStream(stream, 5.seconds, 1.second)
    assert(result.isLeft)
    assert(errMsg(result).contains("inactive"))
  }

  test("uses longer subsequent timeout after first chunk arrives") {
    val stream = Stream.emit(1) ++ Stream.eval(IO.sleep(400.millis)).map(_ => 2)
    val result = runStream(stream, 200.millis, 1.second)
    assertEquals(result, Right(List(1, 2)))
  }

  // === Normal Flow: No False Positives ===

  test("does not timeout when stream emits continuously") {
    val stream = Stream.range(0, 5).evalMap(i => IO.sleep(100.millis).as(i))
    val result = runStream(stream, 500.millis, 500.millis)
    assertEquals(result, Right(List(0, 1, 2, 3, 4)))
  }

  test("does not timeout when stream emits slowly but within limits") {
    val stream = Stream.range(0, 3).evalMap(i => IO.sleep(300.millis).as(i))
    val result = runStream(stream, 1.second, 1.second)
    assertEquals(result, Right(List(0, 1, 2)))
  }

  test("completes normally for a fast stream") {
    val stream = Stream.emits(List("a", "b", "c"))
    val result = runStream(stream, 5.seconds, 5.seconds)
    assertEquals(result, Right(List("a", "b", "c")))
  }

  // === Edge Cases ===

  test("handles empty stream without timeout") {
    val stream: Stream[IO, Int] = Stream.empty
    val result = runStream(stream, 10.seconds, 10.seconds)
    assertEquals(result, Right(Nil))
  }

  test("resets timer on each chunk") {
    val stream = Stream.range(0, 4).evalMap(i => IO.sleep(200.millis).as(i))
    val result = runStream(stream, 500.millis, 500.millis)
    assertEquals(result, Right(List(0, 1, 2, 3)))
  }

  test("produces different error messages for first-token vs inactivity") {
    val s1 = Stream.eval(IO.sleep(3.seconds)).map(_ => 1)
    val r1 = runStream(s1, 1.second, 5.seconds)
    assert(errMsg(r1).contains("no response"))

    val s2 = Stream.emit(1) ++ Stream.eval(IO.sleep(3.seconds)).map(_ => 2)
    val r2 = runStream(s2, 5.seconds, 1.second)
    assert(errMsg(r2).contains("inactive"))
  }

end StreamWatchdogSpec
