package nebflow.pilogic

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite

/** hook 隔离性（本轮必测项二，验收 A-6 三档）+ 执行序/短路/否决传播（§Ⅲ.3）。 */
class HookIsolationSpec extends CatsEffectSuite:

  private val input = Json.obj("k" -> Json.fromInt(1))

  test("Observer 抛错 ⇒ HookErrored + 后续 hook 照跑（隔离 Continue 档——一个 hook 炸不崩整轮）"):
    EventSink.collecting[IO].flatMap { (sink, collected) =>
      Ref.of[IO, Vector[String]](Vector.empty).flatMap { ran =>
        val throwing: Observer[IO] = _ => IO.raiseError(new RuntimeException("observer boom"))
        val normal: Observer[IO] = _ => ran.update(_ :+ "second")
        val registry = HookRegistry
          .empty[IO]
          .withObserver(HookPoint.MessageDelta, throwing)
          .withObserver(HookPoint.MessageDelta, normal)
        HookDispatch.dispatch(HookPoint.MessageDelta, input, registry.snapshot, sink).flatMap { res =>
          (collected, ran.get).mapN { (events, ranNow) =>
            assertEquals(res, DispatchResult.Observed)
            assertEquals(ranNow, Vector("second"))
            val hookErrs = events.collect { case LoopEvent.HookErrored(p, id, cause) => (p, id, cause) }
            assertEquals(hookErrs, Vector(("message.Delta", "hook-0", "observer boom")))
          }
        }
      }
    }

  test("Interceptor(ToolPre) 抛错 ⇒ Block fail-closed 不回落 + HookErrored（Block 档）"):
    EventSink.collecting[IO].flatMap { (sink, collected) =>
      val boom: Interceptor[IO] = _ => IO.raiseError(new RuntimeException("tool hook boom"))
      val registry = HookRegistry.empty[IO].withInterceptor(HookPoint.ToolPre, boom)
      HookDispatch.dispatch(HookPoint.ToolPre, input, registry.snapshot, sink).flatMap { res =>
        collected.map { events =>
          assertEquals(
            res,
            DispatchResult.Decided(HookEffect.Block("hook failed: tool hook boom", terminate = false))
          )
          assert(events.exists {
            case LoopEvent.HookErrored(p, _, c) => p == "tool.Pre" && c == "tool hook boom"
            case _ => false
          })
        }
      }
    }

  test("Interceptor(RunGate) 抛错 ⇒ 整趟失败 HookRunFatal（FailRun 档）+ 先发 HookErrored"):
    EventSink.collecting[IO].flatMap { (sink, collected) =>
      val boom: Interceptor[IO] = _ => IO.raiseError(new IllegalStateException("gate down"))
      val registry = HookRegistry.empty[IO].withInterceptor(HookPoint.RunGate, boom)
      HookDispatch.dispatch(HookPoint.RunGate, input, registry.snapshot, sink).attempt.flatMap {
        case Left(e: LoopError.HookRunFatal) =>
          collected.map { events =>
            assertEquals(e.point, "run.Gate")
            assertEquals(e.cause, "gate down")
            assert(events.exists(_.isInstanceOf[LoopEvent.HookErrored]))
          }
        case other => IO(fail(s"expected HookRunFatal, got: $other"))
      }
    }

  test("ToolPre 首个 Block 即止：后续 hook 不再执行（FirstWins 短路）"):
    Ref.of[IO, Vector[String]](Vector.empty).flatMap { ran =>
      val blocker: Interceptor[IO] = _ => IO.pure(HookEffect.Block("no", terminate = true))
      val never: Interceptor[IO] = _ => ran.update(_ :+ "should-not-run").as(HookEffect.Proceed)
      val registry = HookRegistry
        .empty[IO]
        .withInterceptor(HookPoint.ToolPre, blocker)
        .withInterceptor(HookPoint.ToolPre, never)
      HookDispatch.dispatch(HookPoint.ToolPre, input, registry.snapshot, EventSink.noop[IO]).flatMap { res =>
        ran.get.map { r =>
          assertEquals(res, DispatchResult.Decided(HookEffect.Block("no", terminate = true)))
          assertEquals(r, Vector.empty)
        }
      }
    }

  test("preRequest.Context 链式改写：后者见前者结果，最终 Modify 为链尾值（Chain 聚合）"):
    Ref.of[IO, Vector[Json]](Vector.empty).flatMap { seen =>
      val first: Interceptor[IO] = in => IO.pure(HookEffect.Modify(in.deepMerge(Json.obj("step" -> "1".asJson))))
      val second: Interceptor[IO] =
        in => seen.update(_ :+ in).as(HookEffect.Modify(in.deepMerge(Json.obj("step" -> "2".asJson))))
      val registry = HookRegistry
        .empty[IO]
        .withInterceptor(HookPoint.PreRequestContext, first)
        .withInterceptor(HookPoint.PreRequestContext, second)
      HookDispatch.dispatch(HookPoint.PreRequestContext, input, registry.snapshot, EventSink.noop[IO]).flatMap { res =>
        seen.get.map { s =>
          assertEquals(
            res,
            DispatchResult.Decided(
              HookEffect.Modify(
                input
                  .deepMerge(Json.obj("step" -> "1".asJson))
                  .deepMerge(Json.obj("step" -> "2".asJson))
              )
            )
          )
          assertEquals(s, Vector(input.deepMerge(Json.obj("step" -> "1".asJson))))
        }
      }
    }

  test("Reserved.SessionOps.BeforeSwitch 预留位可分派：Veto 首个即胜（G-1 格子——有类型无触发点）"):
    val vetoer: Interceptor[IO] = _ => IO.pure(HookEffect.Veto("not now"))
    val registry = HookRegistry.empty[IO].withInterceptor(Reserved.SessionOps.BeforeSwitch, vetoer)
    HookDispatch
      .dispatch(Reserved.SessionOps.BeforeSwitch, input, registry.snapshot, EventSink.noop[IO])
      .flatMap { res =>
        IO(assertEquals(res, DispatchResult.Decided(HookEffect.Veto("not now"))))
      }
end HookIsolationSpec
