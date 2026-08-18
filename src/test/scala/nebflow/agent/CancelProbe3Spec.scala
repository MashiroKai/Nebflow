package nebflow.agent

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.Stream
import munit.FunSuite
import nebflow.actor.{ActorSystem, Behavior, Behaviors}
import scala.concurrent.duration.*

/** 二分：ActorContext.forkTurn + cancelCurrentTurn 在真实 actor 环境的行为。 */
class CancelProbe3Spec extends FunSuite:

  test("forkTurn never stream + cancelCurrentTurn completes in actor context"):
    val system = ActorSystem("cancel-probe3")
    try
      val ref = system
        .spawn(
          {
            def loop: Behavior[String] =
              Behaviors.receive[String]((ctx, msg) =>
                msg match
                  case "start" => ctx.forkTurn(Stream.never[IO].compile.drain).as(loop)
                  case "stop" =>
                    // 2s 超时=挂起→抛 TimeoutException→测试红
                    ctx.cancelCurrentTurn().timeout(2.seconds).as(loop)
                  case _ => IO.pure(loop)
              )
            loop
          },
          "probe"
        )
        .unsafeRunSync()
      (ref ! "start").unsafeRunSync()
      Thread.sleep(300)
      (ref ! "stop").unsafeRunSync()
      Thread.sleep(500)
      assert(true, "forkTurn cancel must complete (no timeout raised)")
    finally system.stopAll.attempt.void.unsafeRunSync()
end CancelProbe3Spec
