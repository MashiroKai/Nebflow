package nebflow.llm

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/** Minimal repro driver for the E2E timing anomaly: E2E params (max=1,
  * rpm=20, timeout=2s) driven directly at the gate — if this passes, the
  * missing-timeout behavior lives in the interface layer, not the gate. */
class GateE2EParamsReproSpec extends CatsEffectSuite:

  override val munitIOTimeout = 30.seconds

  test("E2E params at gate level: queued acquires DO time out at 2s") {
    val g = new ConcurrencyGate(
      "repro",
      maxConcurrency = 1,
      rpm = Some(20),
      queueTimeout = 2.seconds,
      rpmWindow = 60.seconds
    )
    for
      p1 <- g.acquire // holder
      f2 <- g.acquire.start
      f3 <- g.acquire.start
      _ <- IO.sleep(100.millis) // let both enqueue
      o2 <- f2.joinWithNever.attempt
      o3 <- f3.joinWithNever.attempt
      _ <- p1.release
      // after both timed out, gate must be fully usable
      p4 <- g.acquire.timeoutTo(1.second, IO.raiseError(new RuntimeException("wedged")))
      _ <- p4.release
    yield
      List(("f2", o2), ("f3", o3)).foreach { case (tag, o) =>
        o match
          case Left(_: QueueTimeout) => ()
          case other => fail(s"$tag: expected QueueTimeout, got $other")
      }
  }

  test("fromProvider honors E2E config: max=1 serializes concurrent acquires") {
    val provider = ProviderConfig(
      baseUrl = "http://127.0.0.1:1",
      apiKey = "k",
      protocol = LlmProtocol.Anthropic,
      models = List(ModelConfig("m1", maxTokens = 1024)),
      maxConcurrency = Some(1),
      rpm = Some(20),
      queueTimeoutMs = Some(2000)
    )
    val g = ConcurrencyGate.fromProvider("a", provider)
    for
      p1 <- g.acquire
      f2 <- g.acquire.start
      _ <- IO.sleep(300.millis)
      stillQueued <- IO.defer(f2.join.timeoutTo(50.millis, IO.pure(None)))
      _ <- p1.release
      p2 <- f2.joinWithNever
      _ <- p2.release
    yield assert(stillQueued == None, "second acquire must queue behind maxConcurrency=1")
  }

end GateE2EParamsReproSpec
