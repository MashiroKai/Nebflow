package nebflow.llm

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.Stream
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * Task 2 (2026-08-19): Ctrl+C 终止 sbt 后 LLM 请求未中断 — shutdown hook 现在
 * 通过 LlmInterface.inflight 注册表取消所有 in-flight 请求。本 spec 验证
 * hook→abort 链路的原语部分：注册 → cancelAllInflight → 流以 ShutdownAbort
 * 终止（sendStream 内用同一套 registerInflight/interruptWhen/unregister 接线）。
 */
class LlmInflightAbortSpec extends CatsEffectSuite:

  test("cancelAllInflight aborts a registered stream with ShutdownAbort") {
    val prog: IO[Either[Throwable, List[String]]] = for
      (_, halt) <- LlmInterface.registerInflight()
      fiber <- Stream
        .emit("x")
        .covary[IO]
        .delayBy(10.seconds)
        .interruptWhen(halt.get)
        .compile
        .toList
        .attempt
        .start
      _ <- IO.sleep(200.millis)
      _ <- LlmInterface.cancelAllInflight()
      res <- fiber.joinWithNever.timeout(3.seconds)
    yield res
    val result = prog.unsafeRunSync()
    assert(result.isLeft, s"stream should have been aborted, got: $result")
    assert(
      result.fold(_.isInstanceOf[ShutdownAbort], _ => false),
      s"abort error should be ShutdownAbort, got: $result"
    )
  }

  test("multiple registered streams are all aborted") {
    val prog: IO[Unit] = for
      (_, halt1) <- LlmInterface.registerInflight()
      (_, halt2) <- LlmInterface.registerInflight()
      f1 <- Stream.emit("a").covary[IO].delayBy(10.seconds).interruptWhen(halt1.get).compile.drain.start
      f2 <- Stream.emit("b").covary[IO].delayBy(10.seconds).interruptWhen(halt2.get).compile.drain.start
      _ <- IO.sleep(200.millis)
      _ <- LlmInterface.cancelAllInflight()
      o1 <- f1.join.timeout(3.seconds)
      o2 <- f2.join.timeout(3.seconds)
      _ <- IO(o1) *> IO(o2) // force both to be evaluated
    yield ()
    val result = prog.attempt.unsafeRunSync()
    // Both fibers must have terminated (not still running) — any error is fine,
    // the point is no request survives cancelAllInflight.
    assert(result.isRight, s"both fibers should terminate promptly: $result")
  }

  test("unregisterInflight removes a stream from the abort set") {
    val prog: IO[Either[Throwable, List[Int]]] = for
      (key, halt) <- LlmInterface.registerInflight()
      _ <- LlmInterface.unregisterInflight(key)
      // Registry empty → cancelAllInflight is a no-op that must not throw
      _ <- LlmInterface.cancelAllInflight()
      // The unregistered stream must be untouched by cancelAllInflight
      res <- Stream(1, 2, 3).interruptWhen(halt.get).compile.toList.attempt
    yield res
    assert(prog.unsafeRunSync() == Right(List(1, 2, 3)))
  }

  test("cancelAllInflightSync (hook entry) does not throw when idle") {
    val result = try
      LlmInterface.cancelAllInflightSync()
      "ok"
    catch case e: Throwable => s"threw: ${e.getMessage}"
    assertEquals(result, "ok")
  }

end LlmInflightAbortSpec
