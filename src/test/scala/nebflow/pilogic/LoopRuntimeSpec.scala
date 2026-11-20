package nebflow.pilogic

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import scala.concurrent.duration.*

import LoopTestKit.*

/**
 * 双层循环体运行时（增量轮）：A-1/A-2/A-5/A-8/A-10 分支语义 + steering/follow-up/settle +
 * 决策时序（早于 TurnEnded 事件）+ RunGate + 续跑 + 并行双 run 无串扰。
 */
class LoopRuntimeSpec extends CatsEffectSuite:

  private val loop = AgentLoop.make[IO]
  private val userCtx = LoopContext(Vector(LoopMessage(LoopMessageRole.User, Json.fromString("hi"))))
  private val call1 = ToolCall("t-1", "probe", Json.obj("x" -> 1.asJson))

  private def runWith(
    scripts: Vector[ProviderStream],
    sink: EventSink[IO] = EventSink.noop[IO],
    hooks: HookRegistry[IO] = HookRegistry.empty[IO],
    policy: LoopPolicy = LoopPolicy.none,
    retry: RetryPolicy = RetryPolicy(),
    context: LoopContext = userCtx,
    executor: ToolExecutor[IO] = ToolExecutor.failing[IO]("no-executor"),
    steering: Option[IO[Vector[LoopMessage]]] = None,
    followUp: Option[IO[Vector[LoopMessage]]] = None,
    finishTurn: Option[TurnSnapshot => IO[TurnDecision]] = None,
    model: Option[String] = None,
    signal: AbortSignal[IO] = constSignal
  ): IO[(RunOutcome, Vector[LoopEvent], Vector[ProviderRequest])] =
    EventSink.collecting[IO].flatMap { (collectSink, collected) =>
      ScriptedProvider.of(scripts*).flatMap { sp =>
        val seed = seedOf(
          sp.port,
          executor,
          collectSink,
          hooks,
          policy,
          retry,
          context,
          steering = steering,
          followUp = followUp,
          finishTurn = finishTurn,
          model = model,
          signal = signal
        )
        loop.run(seed).flatMap(out => (collected, sp.requests).mapN((evs, reqs) => (out, evs, reqs)))
      }
    }

  test("自然双轮：工具轮→文本轮 ⇒ Completed，事件成对，工具结果回填次轮请求（A-5 自然分支）"):
    RecordingExecutor.make().flatMap { rec =>
      runWith(Vector(toolTurn(call1), textTurn("done")), executor = rec.executor).map { (out, evs, reqs) =>
        assertEquals(
          out,
          RunOutcome.Completed(
            Vector(
              LoopMessage(LoopMessageRole.Assistant, Json.fromString("using tool")),
              LoopMessage(LoopMessageRole.Assistant, Json.fromString("done"))
            )
          )
        )
        assertPaired(evs)
        assertEquals(turnCount(evs), 2)
        // 工具结果以 ToolResult 消息进入次轮请求
        assertEquals(reqs(1).messages.count(_.role == LoopMessageRole.ToolResult), 1)
      }
    }

  test("A-1 无帽：三元全 None 跑 6 轮脚本 ⇒ 恰 6 轮自然收尾（无 BudgetReported）"):
    val six = (1 to 5).map(_ => toolTurn(call1)).toVector :+ textTurn("fin")
    RecordingExecutor.make().flatMap { rec =>
      runWith(six, executor = rec.executor).map { (out, evs, _) =>
        assertEquals(turnCount(evs), 6)
        assert(out.isInstanceOf[RunOutcome.Completed])
        assertEquals(evs.count(_.isInstanceOf[LoopEvent.BudgetReported]), 0)
      }
    }

  test("A-2 轮帽：maxTurns=Some(3) 无限工具轮 ⇒ 恰 3 轮 + BudgetReported + Failed(BudgetExhausted)"):
    val infinite = Vector.fill(10)(toolTurn(call1))
    RecordingExecutor.make().flatMap { rec =>
      runWith(infinite, executor = rec.executor, policy = LoopPolicy(maxTurns = Some(TurnNumber(3)))).map {
        (out, evs, _) =>
          assertEquals(turnCount(evs), 3)
          assert(evs.exists(_.isInstanceOf[LoopEvent.BudgetReported]))
          assertEquals(out, RunOutcome.Failed(LoopError.BudgetExhausted))
          assertPaired(evs)
      }
    }

  test("A-2 重试独立：provider 首调抛错、RetryPolicy(2) ⇒ 轮数不受干扰；缺省(None) ⇒ 单次即 Failed"):
    def flakyProvider(failed: Ref[IO, Boolean]): ProviderPort[IO] = (req, _) =>
      failed.get.flatMap(f =>
        if f then failed.set(false) *> IO.raiseError(new RuntimeException("transient"))
        else IO.pure(textTurn("ok-after-retry"))
      )
    (for
      failed <- Ref.of[IO, Boolean](true)
      sp = flakyProvider(failed)
      r1 <- EventSink.collecting[IO].flatMap { (s, c) =>
        loop
          .run(seedOf(sp, ToolExecutor.failing[IO]("x"), s, retry = RetryPolicy(maxAttempts = Some(2))))
          .flatMap(o => c.map(o -> _))
      }
      failed2 <- Ref.of[IO, Boolean](true)
      sp2 = flakyProvider(failed2)
      r2 <- EventSink.collecting[IO].flatMap { (s, c) =>
        loop.run(seedOf(sp2, ToolExecutor.failing[IO]("x"), s)).flatMap(o => c.map(o -> _))
      }
    yield (r1, r2)).map { case ((o1, evs1), (o2, evs2)) =>
      assertEquals(o1.isInstanceOf[RunOutcome.Completed], true)
      assertEquals(turnCount(evs1), 1) // 重试不数轮——与轮数预算互不干扰（病灶 #5 反证）
      assertEquals(o2, RunOutcome.Failed(LoopError.ProviderFailed("transient")))
      assertPaired(evs2)
    }

  test("error 硬退出：stopReason=Error ⇒ Failed(ProviderFailed)；finishTurn 不被读"):
    Ref.of[IO, Int](0).flatMap { reads =>
      runWith(
        Vector(textTurn("boom", reason = StopReason.Error)),
        finishTurn = Some(_ => reads.update(_ + 1).as(TurnDecision.Continue("never")))
      ).flatMap { (out, evs, _) =>
        assertEquals(out, RunOutcome.Failed(LoopError.ProviderFailed("provider reported stopReason=error")))
        assertEquals(turnCount(evs), 1)
        assertPaired(evs)
        assert(
          evs
            .collect { case LoopEvent.TurnEnded(_, _, _, o) => o }
            .lastOption
            .contains(TurnEndOutcome.Error("provider error"))
        )
        reads.get.map(r => assertEquals(r, 0)) // 硬退出不读 finishTurn
      }
    }

  test("aborted 硬退出：stopReason=Aborted ⇒ Aborted 收尾（不读 finishTurn）"):
    runWith(Vector(textTurn("cut", reason = StopReason.Aborted))).map { (out, evs, _) =>
      assert(out.isInstanceOf[RunOutcome.Aborted])
      assertPaired(evs)
    }

  test("A-10 批内中止：首调用后翻 signal ⇒ 次调用合成 Operation aborted 不执行 + Aborted 收尾"):
    TestSignal.make.flatMap { sig =>
      RecordingExecutor.make(c => sig.abortNow *> IO.pure(ToolExecutionResult.ok(Json.fromString("partial")))).flatMap {
        rec =>
          runWith(
            Vector(
              textTurn(
                "batch",
                reason = StopReason.ToolUse,
                calls = Vector(ToolCall("t-1", "probe", Json.obj()), ToolCall("t-2", "probe", Json.obj()))
              )
            ),
            executor = rec.executor,
            signal = sig.signal
          ).flatMap { (out, evs, _) =>
            rec.calls.map { calls =>
              assertEquals(calls.map(_.callId), Vector("t-1")) // t-2 未执行
              assert(out.isInstanceOf[RunOutcome.Aborted])
              assertPaired(evs)
              // t-2 的合成中止结果在 TurnEnded 载荷（pi :610-625 同款；载荷=LoopMessage，content 即结果 json）
              val trs = evs.collect { case LoopEvent.TurnEnded(_, _, tr, _) => tr }.flatten
              assert(
                trs.exists(m =>
                  m.content.hcursor.get[Boolean]("isError").contains(true) &&
                    m.content.hcursor.get[String]("output").contains("Operation aborted")
                )
              )
            }
          }
      }
    }

  test("Length 截断：toolCalls 整批判失败不执行 + TurnEnded(Truncated)，次轮自然收尾"):
    RecordingExecutor.make().flatMap { rec =>
      runWith(
        Vector(textTurn("trunc", reason = StopReason.Length, calls = Vector(call1)), textTurn("after")),
        executor = rec.executor
      ).flatMap { (out, evs, _) =>
        rec.calls.map { calls =>
          assertEquals(calls, Vector.empty) // 不执行
          val turnEnded = evs.collect { case LoopEvent.TurnEnded(_, _, tr, o) => (tr, o) }
          assertEquals(turnEnded(0)._2, TurnEndOutcome.Truncated)
          assertEquals(turnEnded(0)._1.size, 1) // 合成失败结果在事件载荷
          assert(out.isInstanceOf[RunOutcome.Completed])
        }
      }
    }

  test("steering 轮尾排水：轮 1 后注入 steering ⇒ 轮 2 请求可见"):
    Ref.of[IO, Boolean](true).flatMap { once =>
      val steeringMsg = LoopMessage(LoopMessageRole.User, Json.fromString("steer!"))
      val steering = once.getAndSet(false).map(first => if first then Vector.empty else Vector(steeringMsg))
      runWith(Vector(toolTurn(call1), textTurn("done")), steering = Some(steering)).map { (_, _, reqs) =>
        // 轮 1 排水空、轮 1 尾排水取到 ⇒ 轮 2 请求含 steering
        assertEquals(
          reqs(1).messages.count(m => m.role == LoopMessageRole.User && m.content == Json.fromString("steer!")),
          1
        )
      }
    }

  test("follow-up：getFollowUpMessages 一次返回 ⇒ 额外一轮后 Completed"):
    Ref.of[IO, Boolean](true).flatMap { once =>
      val followUp = once
        .getAndSet(false)
        .map(first =>
          if first then Vector(LoopMessage(LoopMessageRole.User, Json.fromString("follow"))) else Vector.empty
        )
      runWith(Vector(textTurn("first"), textTurn("second")), followUp = Some(followUp)).map { (out, evs, reqs) =>
        assertEquals(turnCount(evs), 2)
        assert(out.isInstanceOf[RunOutcome.Completed])
        assertEquals(reqs(1).messages.count(_.role == LoopMessageRole.User), 2) // 原始+follow-up
      }
    }

  test("settle：postRun.Settle ContinueRun ⇒ SettledExtra(1)（#22 关键位——扩展让 agent 再跑一轮）"):
    Ref.of[IO, Boolean](true).flatMap { once =>
      val hook: Interceptor[IO] =
        _ => once.getAndSet(false).map(first => if first then HookEffect.ContinueRun else HookEffect.Proceed)
      runWith(
        Vector(textTurn("a"), textTurn("b")),
        hooks = HookRegistry.empty[IO].withInterceptor(HookPoint.PostRunSettle, hook)
      ).map { (out, evs, _) =>
        assertEquals(turnCount(evs), 2)
        assertEquals(
          out,
          RunOutcome.SettledExtra(
            1,
            Vector(
              LoopMessage(LoopMessageRole.Assistant, Json.fromString("a")),
              LoopMessage(LoopMessageRole.Assistant, Json.fromString("b"))
            )
          )
        )
      }
    }

  test("决策时序：finishTurn 决策早于 TurnEnded 事件（照抄 pi :285 早于 :286）"):
    Ref.of[IO, Vector[String]](Vector.empty).flatMap { trace =>
      val sink: EventSink[IO] = e => trace.update(_ :+ s"ev:${e.productPrefix}")
      val ft: TurnSnapshot => IO[TurnDecision] = _ => trace.update(_ :+ "decision").as(TurnDecision.End)
      ScriptedProvider.of(textTurn("one")).flatMap { sp =>
        loop
          .run(
            seedOf(
              sp.port,
              ToolExecutor.failing[IO]("x"),
              sink,
              finishTurn = Some(ft),
              policy = LoopPolicy(maxTurns = Some(TurnNumber(1)))
            )
          )
          .flatMap { _ =>
            trace.get.map { tr =>
              assert(tr.indexOf("decision") < tr.indexOf("ev:TurnEnded"), s"序错：$tr")
            }
          }
      }
    }

  test("A-8 续跑拒绝两形 ⇒ RunStarted..RunEnded(Failed(InvalidContinuation)) 成对（A-5 分支）"):
    ScriptedProvider.of(textTurn("x")).flatMap { sp =>
      loop
        .continueFrom(LoopContext(Vector.empty), seedOf(sp.port, ToolExecutor.failing[IO]("x"), EventSink.noop[IO]))
        .flatMap { o1 =>
          EventSink.collecting[IO].flatMap { (s, c) =>
            val asst = LoopContext(Vector(LoopMessage(LoopMessageRole.Assistant, Json.fromString("a"))))
            loop.continueFrom(asst, seedOf(sp.port, ToolExecutor.failing[IO]("x"), s)).flatMap { o2 =>
              c.map { evs =>
                assert(o1.isInstanceOf[RunOutcome.Failed])
                assert(o2.isInstanceOf[RunOutcome.Failed])
                assertPaired(evs)
              }
            }
          }
        }
    }

  test("A-8 续跑合法（末条 user）⇒ 从当前上下文续跑"):
    ScriptedProvider.of(textTurn("continued")).flatMap { sp =>
      EventSink.collecting[IO].flatMap { (s, c) =>
        loop.continueFrom(userCtx, seedOf(sp.port, ToolExecutor.failing[IO]("x"), s)).flatMap { o =>
          c.map { evs =>
            assert(o.isInstanceOf[RunOutcome.Completed])
            assertPaired(evs)
          }
        }
      }
    }

  test("RunGate Veto ⇒ Failed(RunGated)、零 TurnStarted、成对"):
    val gate: Interceptor[IO] = _ => IO.pure(HookEffect.Veto("not allowed"))
    runWith(Vector(textTurn("never")), hooks = HookRegistry.empty[IO].withInterceptor(HookPoint.RunGate, gate)).map {
      (out, evs, _) =>
        assertEquals(out, RunOutcome.Failed(LoopError.RunGated("not allowed")))
        assertEquals(turnCount(evs), 0)
        assertPaired(evs)
    }

  test("墙钟预算：10ms 帽 + 60ms/轮 provider ⇒ 工具轮续命后次轮头命中帽，BudgetReported 收尾"):
    // 文本轮 1 轮即自然 Completed、根本不回内层头部 ⇒ 测不到帽；改工具轮（结果待回填 ⇒
    // NextTurn ⇒ 回到内层头部，此时墙钟已越帽）
    val slow: ProviderPort[IO] = (_, _) => IO.sleep(60.millis).as(toolTurn(call1))
    RecordingExecutor.make().flatMap { rec =>
      EventSink.collecting[IO].flatMap { (s, c) =>
        loop.run(seedOf(slow, rec.executor, s, policy = LoopPolicy(wallClock = Some(10.millis)))).flatMap { o =>
          c.map { evs =>
            assertEquals(turnCount(evs), 1)
            assert(evs.exists(_.isInstanceOf[LoopEvent.BudgetReported]))
            assertEquals(o, RunOutcome.Failed(LoopError.BudgetExhausted))
            assertPaired(evs)
          }
        }
      }
    }

  test("A-4 并行双 run 无串扰：甲/乙 ToolPre 只见各自上下文"):
    def kit(tag: String): IO[(IO[RunOutcome], IO[Vector[Json]])] =
      for
        seen <- Ref.of[IO, Vector[Json]](Vector.empty)
        rec: Interceptor[IO] = in => seen.update(_ :+ in).as(HookEffect.Proceed)
        sp <- ScriptedProvider.of(toolTurn(ToolCall("c", "probe", Json.obj("who" -> tag.asJson))), textTurn("fin"))
        sink <- EventSink.collecting[IO].map(_._1)
        seed = seedOf(
          sp.port,
          ToolExecutor.failing[IO]("blocked-ok"),
          sink,
          hooks = HookRegistry.empty[IO].withInterceptor(HookPoint.ToolPre, rec)
        )
      yield (loop.run(seed), seen.get)
    (for
      a <- kit("A")
      b <- kit("B")
      _ <- (a._1, b._1).parTupled
      sa <- a._2
      sb <- b._2
    yield (sa, sb)).map { (sa, sb) =>
      assertEquals(sa.size, 1)
      assertEquals(sb.size, 1)
      assert(sa.head.hcursor.get[Json]("args").flatMap(_.hcursor.get[String]("who")).contains("A"))
      assert(sb.head.hcursor.get[Json]("args").flatMap(_.hcursor.get[String]("who")).contains("B"))
    }
end LoopRuntimeSpec
