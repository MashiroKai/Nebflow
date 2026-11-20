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
 * 采纳 29 位点逐一适配（增量轮）：每位点在循环对应阶段的真实触发 + 效果消费 +
 * L-5 重校验正反例 + L-11 分位点声明与丢弃档机制 + 守卫（#64 消息冻结 / role 约束）。
 */
class SiteAdaptationSpec extends CatsEffectSuite:

  private val loop = AgentLoop.make[IO]
  private val userCtx = LoopContext(Vector(LoopMessage(LoopMessageRole.User, Json.fromString("hi"))))
  private val call1 = ToolCall("t-1", "probe", Json.obj("x" -> 1.asJson))

  private def runWith(
    scripts: Vector[ProviderStream],
    hooks: HookRegistry[IO] = HookRegistry.empty[IO],
    executor: ToolExecutor[IO] = ToolExecutor.failing[IO]("no-executor"),
    context: LoopContext = userCtx,
    tools: ToolSet = ToolSet.empty,
    model: Option[String] = None,
    options: Json = Json.obj()
  ): IO[(RunOutcome, Vector[LoopEvent], Vector[ProviderRequest])] =
    EventSink.collecting[IO].flatMap { (sink, collected) =>
      ScriptedProvider.of(scripts*).flatMap { sp =>
        val seed = seedOf(
          sp.port,
          executor,
          sink,
          hooks = hooks,
          context = context,
          tools = tools,
          model = model,
          options = options
        )
        loop.run(seed).flatMap(out => (collected, sp.requests).mapN((evs, reqs) => (out, evs, reqs)))
      }
    }

  private def obs(seen: Ref[IO, Vector[Json]]): Observer[IO] = recordingObserver(seen)

  // ── run 入口族 ───────────────────────────────────────────────────────────

  test("session.Started：空上下文触发、非空不触发（新会话等价=空上下文）"):
    (for
      seen <- Ref.of[IO, Vector[Json]](Vector.empty)
      hooks = HookRegistry.empty[IO].withObserver(HookPoint.SessionStarted, obs(seen))
      _ <- runWith(Vector(textTurn("a")), hooks = hooks, context = LoopContext(Vector.empty))
      _ <- runWith(Vector(textTurn("b")), hooks = hooks, context = userCtx)
      s <- seen.get
    yield s).map { s =>
      assertEquals(s.size, 1) // 仅空上下文那次
      assertEquals(s.head.hcursor.get[Boolean]("fresh").toOption, Some(true))
    }

  test("run.Gate：Veto 拒整趟（FailRun 档隔离测试见 HookIsolationSpec，此处效果消费）"):
    val gate: Interceptor[IO] = _ => IO.pure(HookEffect.Veto("denied"))
    runWith(Vector(textTurn("x")), hooks = HookRegistry.empty[IO].withInterceptor(HookPoint.RunGate, gate)).map {
      (out, _, _) =>
        assertEquals(out, RunOutcome.Failed(LoopError.RunGated("denied")))
    }

  test("preRun.Start：Modify 替换 system ⇒ 首请求 system 生效"):
    val hook: Interceptor[IO] = _ => IO.pure(HookEffect.Modify(Json.fromString("custom-system")))
    runWith(Vector(textTurn("a")), hooks = HookRegistry.empty[IO].withInterceptor(HookPoint.PreRunStart, hook)).map {
      (_, _, reqs) =>
        assertEquals(reqs.head.system, Some(Json.fromString("custom-system")))
    }

  test("preRun.Inject：Inject 注入消息 ⇒ 请求史增位"):
    val hook: Interceptor[IO] =
      _ => IO.pure(HookEffect.Inject(Vector(LoopMessage(LoopMessageRole.User, Json.fromString("injected")))))
    runWith(Vector(textTurn("a")), hooks = HookRegistry.empty[IO].withInterceptor(HookPoint.PreRunInject, hook)).map {
      (_, _, reqs) =>
        assertEquals(reqs.head.messages.count(_.content == Json.fromString("injected")), 1)
    }

  // ── preRequest 族 ────────────────────────────────────────────────────────

  test("preRequest.Context：Modify 改写消息史 ⇒ 请求见改写后史"):
    val rewritten = Vector(LoopMessage(LoopMessageRole.User, Json.fromString("rewritten")))
    val hook: Interceptor[IO] = _ =>
      IO.pure(
        HookEffect.Modify(Json.arr(rewritten.map(m => Json.obj("role" -> "user".asJson, "content" -> m.content))*))
      )
    runWith(Vector(textTurn("a")), hooks = HookRegistry.empty[IO].withInterceptor(HookPoint.PreRequestContext, hook))
      .map { (_, _, reqs) =>
        assertEquals(reqs.head.messages, rewritten)
      }

  test("preRequest.System：Modify ⇒ 请求 system 换"):
    val hook: Interceptor[IO] = _ => IO.pure(HookEffect.Modify(Json.fromString("sys2")))
    runWith(Vector(textTurn("a")), hooks = HookRegistry.empty[IO].withInterceptor(HookPoint.PreRequestSystem, hook))
      .map { (_, _, reqs) =>
        assertEquals(reqs.head.system, Some(Json.fromString("sys2")))
      }

  test("preRequest.Options：Patch=deepMerge 增量 / Modify=整块替换（#45 显式分档）"):
    val patch: Interceptor[IO] = _ => IO.pure(HookEffect.Patch(Json.obj("temp" -> 0.7.asJson)))
    runWith(
      Vector(textTurn("a")),
      options = Json.obj("base" -> 1.asJson),
      hooks = HookRegistry.empty[IO].withInterceptor(HookPoint.PreRequestOptions, patch)
    ).map { (_, _, reqs) =>
      // 基线保留 + 增量并入（Patch=deepMerge，不整块吞基线）
      assertEquals(reqs.head.options.hcursor.get[Int]("base").toOption, Some(1))
      assertEquals(reqs.head.options.hcursor.get[Double]("temp").toOption, Some(0.7))
    }

  // ── provider 边界族 ──────────────────────────────────────────────────────

  test("providerPort.Payload：Modify 整块替换（model 换）⇒ 请求生效"):
    val hook: Interceptor[IO] = in => IO.pure(HookEffect.Modify(in.deepMerge(Json.obj("model" -> "m-b".asJson))))
    runWith(
      Vector(textTurn("a")),
      model = Some("m-a"),
      hooks = HookRegistry.empty[IO].withInterceptor(HookPoint.ProviderPayload, hook)
    ).map { (_, _, reqs) =>
      assertEquals(reqs.head.model, "m-b")
    }

  test("providerPort.Payload 守卫（#64 不可追加消息）：替换追加消息 ⇒ 弃改写 + HookErrored"):
    val extra = LoopMessage(LoopMessageRole.User, Json.fromString("smuggled"))
    val hook: Interceptor[IO] = in =>
      IO.pure(
        HookEffect.Modify(
          in.deepMerge(
            Json.obj(
              "messages" ->
                Json.arr(
                  (in.hcursor.get[Vector[Json]]("messages").toOption.getOrElse(Vector.empty) :+
                    Json.obj("role" -> "user".asJson, "content" -> extra.content))*
                )
            )
          )
        )
      )
    runWith(Vector(textTurn("a")), hooks = HookRegistry.empty[IO].withInterceptor(HookPoint.ProviderPayload, hook))
      .map { (_, evs, reqs) =>
        assertEquals(reqs.head.messages.size, userCtx.messages.size) // 改写被弃
        assert(evs.exists {
          case LoopEvent.HookErrored(p, id, _) => p == "providerPort.Payload" && id == "loop.message-freeze";
          case _ => false
        })
      }

  test("providerPort.Headers：Modify ⇒ 请求 headers 替换（返值必读——#16 陷阱防重演）"):
    val hook: Interceptor[IO] = _ => IO.pure(HookEffect.Modify(Json.obj("X-Trace" -> "t-1".asJson)))
    runWith(Vector(textTurn("a")), hooks = HookRegistry.empty[IO].withInterceptor(HookPoint.ProviderHeaders, hook))
      .map { (_, _, reqs) =>
        assertEquals(reqs.head.headers.hcursor.get[String]("X-Trace").toOption, Some("t-1"))
      }

  test("providerPort.Responded：响应观察位收到 stopReason 载荷"):
    (for
      seen <- Ref.of[IO, Vector[Json]](Vector.empty)
      _ <- runWith(
        Vector(textTurn("r1")),
        hooks = HookRegistry.empty[IO].withObserver(HookPoint.ProviderResponded, obs(seen))
      )
      s <- seen.get
    yield s).map { s =>
      assertEquals(s.size, 1)
      assert(s.head.hcursor.get[String]("stopReason").toOption.contains("Stop"))
    }

  test("L-11 分位点声明：ProviderChunked=Dropping，语义位 Sequential；L-5 常量随位点"):
    assertEquals(HookPoint.ProviderChunked.backpressure, Backpressure.Dropping)
    assertEquals(HookPoint.MessageDelta.backpressure, Backpressure.Sequential)
    assertEquals(HookPoint.ToolExecutionDelta.backpressure, Backpressure.Sequential)
    assertEquals(HookPoint.TurnStarted.backpressure, Backpressure.Sequential)
    assertEquals(HookPoint.ToolPre.revalidateModifiedArgs, true)
    assertEquals(adoptedPoints.size, 29) // 采纳族单源计数

  test("L-11 丢弃档机制：忙观察者 ⇒ 帧丢弃、生产者不阻塞；空闲恢复投递"):
    (for
      received <- Ref.of[IO, Int](0)
      sink = EventSink.noop[IO]
      slow: Observer[IO] = _ => received.update(_ + 1) *> IO.sleep(120.millis)
      reg = HookRegistry.empty[IO].withObserver(HookPoint.ProviderChunked, slow)
      snap = reg.snapshot
      gates <- DropGates.of[IO](snap)
      chunk = Json.obj("d" -> 1.asJson)
      _ <- HookDispatch.dispatch(HookPoint.ProviderChunked, chunk, snap, sink, gates) // 帧1 后台投递
      _ <- IO.sleep(20.millis) // 投递已在飞行中（门忙）
      _ <- HookDispatch.dispatch(HookPoint.ProviderChunked, chunk, snap, sink, gates) // 帧2 ⇒ 丢弃
      mid <- received.get
      _ <- IO.sleep(180.millis) // 帧1 完成、门释放
      _ <- HookDispatch.dispatch(HookPoint.ProviderChunked, chunk, snap, sink, gates) // 帧3 ⇒ 再投递
      late <- IO.sleep(30.millis) *> received.get
    yield (mid, late)).map { (mid, late) =>
      assertEquals(mid, 1) // 帧2 被丢：观察者忙
      assertEquals(late, 2) // 门释后帧3 正常投递
    }

  test("providerPort.Chunked 经循环：帧投递（Dropping 档允许丢帧）；对照 message.Delta 事件全量"):
    (for
      chunkSeen <- Ref.of[IO, Vector[Json]](Vector.empty)
      result <- runWith(
        Vector(
          textTurn("a", chunks = Vector(ProviderChunk(Json.fromString("p1")), ProviderChunk(Json.fromString("p2"))))
        ),
        hooks = HookRegistry.empty[IO].withObserver(HookPoint.ProviderChunked, obs(chunkSeen))
      )
      (_, evs, _) = result
      s <- IO.sleep(50.millis) *> chunkSeen.get // Dropping 档=后台 fiber，读取前等在飞的落完
    yield (s, evs)).map { (s, evs) =>
      // ProviderChunked=Dropping：忙门期间允许丢帧 ⇒ 只断言「投递的是这两帧之一、且至少一帧」
      assert(s.forall(j => Set("p1", "p2").contains(j.asString.getOrElse(""))))
      assert(s.nonEmpty)
      // 对照：message.Delta 是**事件**（sink 直发，不过门）⇒ 两帧必全量在场
      assertEquals(evs.collect { case LoopEvent.MessageDelta(_, c) => c.asString.getOrElse("") }, Vector("p1", "p2"))
    }

  // ── message 族 ───────────────────────────────────────────────────────────

  test("message.Started/Delta/Ended：事件序+seq 一致（观察位族逐个触发见 ProviderChunked/turn 用例）"):
    runWith(Vector(textTurn("a", chunks = Vector(ProviderChunk(Json.fromString("d1")))))).map { (_, evs, _) =>
      val idxStarted = evs.indexWhere(_.isInstanceOf[LoopEvent.MessageStarted])
      val idxDelta = evs.indexWhere(_.isInstanceOf[LoopEvent.MessageDelta])
      val idxEnded = evs.indexWhere(_.isInstanceOf[LoopEvent.MessageEnded])
      assert(idxStarted < idxDelta && idxDelta < idxEnded)
      assertEquals(evalsSeq(evs), Vector(1L)) // MessageStarted/Delta/Ended 同 seq
      assertEquals(evs.collect { case LoopEvent.MessageDelta(_, c) => c }.map(_.asString.getOrElse("")), Vector("d1"))
    }

  private def evalsSeq(evs: Vector[LoopEvent]): Vector[Long] =
    evs.collect { case LoopEvent.MessageStarted(s) => s.value }

  test("message.Ended 改写：同 role ⇒ 生效；换 role ⇒ 弃改写 + HookErrored（role 约束强制）"):
    val sameRole: Interceptor[IO] =
      _ => IO.pure(HookEffect.Modify(Json.obj("role" -> "assistant".asJson, "content" -> Json.fromString("replaced"))))
    runWith(Vector(textTurn("orig")), hooks = HookRegistry.empty[IO].withInterceptor(HookPoint.MessageEnded, sameRole))
      .map { (_, evs, _) =>
        assert(evs.exists {
          case LoopEvent.MessageEnded(_, m) => m.content == Json.fromString("replaced"); case _ => false
        })
      }
    val roleFlip: Interceptor[IO] =
      _ => IO.pure(HookEffect.Modify(Json.obj("role" -> "user".asJson, "content" -> Json.fromString("bad"))))
    runWith(Vector(textTurn("orig")), hooks = HookRegistry.empty[IO].withInterceptor(HookPoint.MessageEnded, roleFlip))
      .map { (_, evs, _) =>
        assert(evs.exists {
          case LoopEvent.MessageEnded(_, m) => m.content == Json.fromString("orig"); case _ => false
        })
        assert(evs.exists {
          case LoopEvent.HookErrored(p, id, _) => p == "message.Ended" && id == "loop.role-guard"; case _ => false
        })
      }

  // ── tool 族（L-5 正反例） ────────────────────────────────────────────────

  private val probeSpec = ToolSpec(
    "probe",
    args => if args.hcursor.get[Int]("x").exists(_ > 0) then Right(()) else Left("x must be positive")
  )

  test("tool.Pre L-5 正例：Modify 合法参 ⇒ 重校验过 ⇒ 执行器见新参"):
    val hook: Interceptor[IO] = _ => IO.pure(HookEffect.Modify(Json.obj("x" -> 42.asJson)))
    RecordingExecutor.make().flatMap { rec =>
      runWith(
        Vector(toolTurn(call1), textTurn("fin")),
        tools = ToolSet.of(probeSpec),
        executor = rec.executor,
        hooks = HookRegistry.empty[IO].withInterceptor(HookPoint.ToolPre, hook)
      ).flatMap { (_, _, _) =>
        rec.calls.map { calls =>
          assertEquals(calls.map(_.args.hcursor.get[Int]("x").toOption), Vector(Some(42)))
        }
      }
    }

  test("tool.Pre L-5 反例：Modify 非法参 ⇒ 重校验拒 ⇒ 不执行 + 错误结果 + HookErrored"):
    val hook: Interceptor[IO] = _ => IO.pure(HookEffect.Modify(Json.obj("x" -> (-1).asJson)))
    RecordingExecutor.make().flatMap { rec =>
      runWith(
        Vector(toolTurn(call1), textTurn("fin")),
        tools = ToolSet.of(probeSpec),
        executor = rec.executor,
        hooks = HookRegistry.empty[IO].withInterceptor(HookPoint.ToolPre, hook)
      ).flatMap { (_, evs, _) =>
        rec.calls.map { calls =>
          assertEquals(calls, Vector.empty) // 坏参不落执行器
          assert(evs.exists {
            case LoopEvent.HookErrored(p, id, _) => p == "tool.Pre" && id == "loop.revalidate"; case _ => false
          })
          val trs = evs.collect { case LoopEvent.TurnEnded(_, _, tr, _) => tr }.flatten
          assert(
            trs.exists(m =>
              m.content.hcursor.get[Boolean]("isError").contains(true) &&
                m.content.hcursor.get[String]("output").exists(_.startsWith("hook-modified args rejected"))
            )
          )
        }
      }
    }

  test("tool.Pre 引擎侧原始参校验：原始参非法 ⇒ 不执行不触发 hook + 错误结果"):
    (for
      seen <- Ref.of[IO, Vector[Json]](Vector.empty)
      recHook: Interceptor[IO] = in => seen.update(_ :+ in).as(HookEffect.Proceed)
      rec <- RecordingExecutor.make()
      _ <- runWith(
        Vector(toolTurn(ToolCall("t-9", "probe", Json.obj("x" -> (-5).asJson))), textTurn("fin")),
        tools = ToolSet.of(probeSpec),
        executor = rec.executor,
        hooks = HookRegistry.empty[IO].withInterceptor(HookPoint.ToolPre, recHook)
      )
      s <- seen.get
      calls <- rec.calls
    yield (s, calls)).map { (s, calls) =>
      assertEquals(s.size, 0) // hook 在校验之后——原始参非法不达 hook
      assertEquals(calls, Vector.empty)
    }

  test("tool.Pre Block(terminate=true) 双调用批：后续调用合成 skipped + 当轮收轮（#37 批语义）"):
    val blocker: Interceptor[IO] = _ => IO.pure(HookEffect.Block("no", terminate = true))
    RecordingExecutor.make().flatMap { rec =>
      runWith(
        Vector(
          textTurn(
            "b",
            reason = StopReason.ToolUse,
            calls = Vector(
              ToolCall("t-1", "probe", Json.obj("x" -> 1.asJson)),
              ToolCall("t-2", "probe", Json.obj("x" -> 2.asJson))
            )
          )
        ),
        tools = ToolSet.of(probeSpec),
        executor = rec.executor,
        hooks = HookRegistry.empty[IO].withInterceptor(HookPoint.ToolPre, blocker)
      ).flatMap { (out, evs, _) =>
        rec.calls.map { calls =>
          assertEquals(calls, Vector.empty) // 阻断 ⇒ 不执行
          val ended = evs.collect { case LoopEvent.ToolExecutionEnded(id, _, _) => id }
          assertEquals(ended, Vector("t-1")) // t-2 无单调用事件（未开始）
          assert(out.isInstanceOf[RunOutcome.Completed]) // terminate ⇒ 当轮收轮
          assertEquals(turnCount(evs), 1)
        }
      }
    }

  test("tool.Pre Veto ⇒ 阻断不执行，结果为 vetoed 错误"):
    val vetoer: Interceptor[IO] = _ => IO.pure(HookEffect.Veto("not now"))
    RecordingExecutor.make().flatMap { rec =>
      runWith(
        Vector(toolTurn(call1), textTurn("fin")),
        hooks = HookRegistry.empty[IO].withInterceptor(HookPoint.ToolPre, vetoer),
        executor = rec.executor
      ).flatMap { (_, evs, _) =>
        rec.calls.map { calls =>
          assertEquals(calls, Vector.empty)
          val trs = evs.collect { case LoopEvent.TurnEnded(_, _, tr, _) => tr }.flatten
          assert(
            trs.exists(m =>
              m.content.hcursor.get[Boolean]("isError").contains(true) &&
                m.content.hcursor.get[String]("output").exists(_.startsWith("vetoed"))
            )
          )
        }
      }
    }

  test("tool.Post Modify ⇒ 结果改写；Terminate ⇒ 当轮收轮"):
    val rewriter: Interceptor[IO] = _ =>
      IO.pure(HookEffect.Modify(Json.obj("output" -> Json.fromString("post-rewritten"), "isError" -> false.asJson)))
    val terminator: Interceptor[IO] = _ => IO.pure(HookEffect.Terminate("enough"))
    for
      rec1 <- RecordingExecutor.make()
      (_, evs1, _) <- runWith(
        Vector(toolTurn(call1), textTurn("fin")),
        executor = rec1.executor,
        hooks = HookRegistry.empty[IO].withInterceptor(HookPoint.ToolPost, rewriter)
      )
      rec2 <- RecordingExecutor.make()
      (out2, evs2, _) <- runWith(
        Vector(toolTurn(call1), textTurn("never")),
        executor = rec2.executor,
        hooks = HookRegistry.empty[IO].withInterceptor(HookPoint.ToolPost, terminator)
      )
    yield
      assert(evs1.exists {
        case LoopEvent.ToolExecutionEnded(_, out, _) => out == Json.fromString("post-rewritten"); case _ => false
      })
      assert(out2.isInstanceOf[RunOutcome.Completed])
      assertEquals(turnCount(evs2), 1) // 批后收轮，不再请求模型
    end for

  test("toolExecution.Started/Ended + tool.BatchSettled：单调用事件序与批后观察"):
    (for
      batchSeen <- Ref.of[IO, Vector[Json]](Vector.empty)
      rec <- RecordingExecutor.make(progressOnce = true)
      (out, evs, _) <- runWith(
        Vector(toolTurn(call1), textTurn("fin")),
        executor = rec.executor,
        hooks = HookRegistry.empty[IO].withObserver(HookPoint.ToolBatchSettled, obs(batchSeen))
      )
      bs <- batchSeen.get
    yield (evs, bs, out)).map { (evs, bs, _) =>
      val started = evs.indexWhere { case LoopEvent.ToolExecutionStarted(id, _) => id == "t-1"; case _ => false }
      val ended = evs.indexWhere { case LoopEvent.ToolExecutionEnded(id, _, _) => id == "t-1"; case _ => false }
      assert(started >= 0 && started < ended)
      assertEquals(bs.size, 1) // 批后一次
      assert(bs.head.hcursor.get[Vector[Json]]("results").toOption.exists(_.size == 1))
    }

  test("toolExecution.Delta：executor 进度回调 ⇒ 事件 + 观察位"):
    (for
      deltas <- Ref.of[IO, Vector[Json]](Vector.empty)
      rec <- RecordingExecutor.make(progressOnce = true)
      _ <- runWith(
        Vector(toolTurn(call1), textTurn("fin")),
        executor = rec.executor,
        hooks = HookRegistry.empty[IO].withObserver(HookPoint.ToolExecutionDelta, obs(deltas))
      )
      d <- deltas.get
    yield d).map { d =>
      assertEquals(d.size, 1)
      // 位点载荷 = {callId, partial:{...}}（#59：进度增量裹在 partial 下）
      assertEquals(d.head.hcursor.downField("callId").as[String].toOption, Some("t-1"))
      assertEquals(d.head.hcursor.downField("partial").get[Int]("progress").toOption, Some(1))
    }

  // ── turn 族 ──────────────────────────────────────────────────────────────

  test("turn.Started 观察位逐轮触发（turn 从 1 起）"):
    // 轮数需有真实第二轮：文本轮单轮即自然收尾（无续行理由），故用工具轮+收尾文本轮
    (for
      seen <- Ref.of[IO, Vector[Json]](Vector.empty)
      rec <- RecordingExecutor.make()
      _ <- runWith(
        Vector(toolTurn(call1), textTurn("fin")),
        executor = rec.executor,
        hooks = HookRegistry.empty[IO].withObserver(HookPoint.TurnStarted, obs(seen))
      )
      s <- seen.get
    yield s).map { s =>
      assertEquals(s.flatMap(_.hcursor.get[Long]("turn").toOption), Vector(1L, 2L))
    }

  test("turn.Ended 位 Inject：注入消息 ⇒ 强制下一轮（pi #27 entries 通道）"):
    // 只注入一次（首轮）：注入=唯一续行理由，第二轮自然收尾——脚本恰两轮
    (for
      n <- Ref.of[IO, Int](0)
      injector: Interceptor[IO] = _ =>
        n.updateAndGet(_ + 1).flatMap { i =>
          if i == 1 then IO.pure(HookEffect.Inject(Vector(LoopMessage(LoopMessageRole.User, Json.fromString("entry")))))
          else IO.pure(HookEffect.Proceed)
        }
      out <- runWith(
        Vector(textTurn("a"), textTurn("b")),
        hooks = HookRegistry.empty[IO].withInterceptor(HookPoint.TurnEnded, injector)
      )
    yield out).map { (o, evs, reqs) =>
      assertEquals(o.isInstanceOf[RunOutcome.Completed], true)
      assertEquals(turnCount(evs), 2) // 自然本停于轮 1 ⇒ entries 续一轮
      assertEquals(reqs(1).messages.count(_.content == Json.fromString("entry")), 1)
    }

  test("turn.Ended 位 EndRun：工具结果待回填仍即收（终止权压过自然续行）"):
    val ender: Interceptor[IO] = _ => IO.pure(HookEffect.EndRun)
    RecordingExecutor.make().flatMap { rec =>
      runWith(
        Vector(toolTurn(call1)),
        executor = rec.executor,
        hooks = HookRegistry.empty[IO].withInterceptor(HookPoint.TurnEnded, ender)
      ).map { (out, evs, _) =>
        assert(out.isInstanceOf[RunOutcome.Completed])
        assertEquals(turnCount(evs), 1)
      }
    }

  test("turn.Ended 优先级：显式 finishTurn End 不被位 ContinueRun 翻转（#27 上游 End 优先）"):
    val continuer: Interceptor[IO] = _ => IO.pure(HookEffect.ContinueRun)
    EventSink.collecting[IO].flatMap { (sink, collected) =>
      ScriptedProvider.of(toolTurn(call1)).flatMap { sp =>
        RecordingExecutor.make().flatMap { rec =>
          val seed = seedOf(
            sp.port,
            rec.executor,
            sink,
            hooks = HookRegistry.empty[IO].withInterceptor(HookPoint.TurnEnded, continuer),
            finishTurn = Some(_ => IO.pure(TurnDecision.End))
          )
          loop.run(seed).flatMap { out =>
            collected.map { evs =>
              assert(out.isInstanceOf[RunOutcome.Completed])
              assertEquals(turnCount(evs), 1) // 显式 End 压过 ContinueRun——不再续轮
            }
          }
        }
      }
    }

  test("model.Selected：轮间实际换模型 ⇒ from/to 观察"):
    // 需真实第二轮（工具轮续命）；switcher 每位点每轮一次 ⇒ 第 2 次 dispatch=轮 2 换 m-b
    (for
      seen <- Ref.of[IO, Vector[Json]](Vector.empty)
      n <- Ref.of[IO, Int](0)
      switcher: Interceptor[IO] = in =>
        n.updateAndGet(_ + 1).flatMap { i =>
          if i >= 2 then IO.pure(HookEffect.Modify(in.deepMerge(Json.obj("model" -> "m-b".asJson))))
          else IO.pure(HookEffect.Proceed)
        }
      rec <- RecordingExecutor.make()
      _ <- runWith(
        Vector(toolTurn(call1), textTurn("b")),
        executor = rec.executor,
        model = Some("m-a"),
        hooks = HookRegistry
          .empty[IO]
          .withInterceptor(HookPoint.ProviderPayload, switcher)
          .withObserver(HookPoint.ModelSelected, obs(seen))
      )
      s <- seen.get
    yield s).map { s =>
      assertEquals(s.size, 1)
      assertEquals(s.head.hcursor.get[String]("from").toOption, Some("m-a"))
      assertEquals(s.head.hcursor.get[String]("to").toOption, Some("m-b"))
    }

  test("thinkingLevel.Selected：options 档位实际变化 ⇒ 观察"):
    (for
      seen <- Ref.of[IO, Vector[Json]](Vector.empty)
      n <- Ref.of[IO, Int](0)
      raiser: Interceptor[IO] = _ =>
        n.updateAndGet(_ + 1).flatMap { i =>
          if i >= 2 then IO.pure(HookEffect.Patch(Json.obj("thinkingLevel" -> "high".asJson)))
          else IO.pure(HookEffect.Proceed)
        }
      rec <- RecordingExecutor.make()
      _ <- runWith(
        Vector(toolTurn(call1), textTurn("b")),
        executor = rec.executor,
        options = Json.obj("thinkingLevel" -> "low".asJson),
        hooks = HookRegistry
          .empty[IO]
          .withInterceptor(HookPoint.PreRequestOptions, raiser)
          .withObserver(HookPoint.ThinkingLevelSelected, obs(seen))
      )
      s <- seen.get
    yield s).map { s =>
      assertEquals(s.size, 1)
      assertEquals(s.head.hcursor.get[String]("from").toOption, Some("low"))
      assertEquals(s.head.hcursor.get[String]("to").toOption, Some("high"))
    }

  // ── postRun 族 ───────────────────────────────────────────────────────────

  test("postRun.FollowUp：Inject ⇒ 额外一轮（#43）"):
    Ref.of[IO, Boolean](true).flatMap { once =>
      val hook: Interceptor[IO] = _ =>
        once
          .getAndSet(false)
          .map(first =>
            if first then HookEffect.Inject(Vector(LoopMessage(LoopMessageRole.User, Json.fromString("fu"))))
            else HookEffect.Proceed
          )
      runWith(
        Vector(textTurn("a"), textTurn("b")),
        hooks = HookRegistry.empty[IO].withInterceptor(HookPoint.PostRunFollowUp, hook)
      ).map { (_, evs, reqs) =>
        assertEquals(turnCount(evs), 2)
        assertEquals(reqs(1).messages.count(_.content == Json.fromString("fu")), 1)
      }
    }

  test("postRun.Settled：RunEnded 之后收到终局通知（#23 最末）"):
    (for
      trace <- Ref.of[IO, Vector[String]](Vector.empty)
      sink: EventSink[IO] = e => trace.update(_ :+ e.productPrefix)
      sp <- ScriptedProvider.of(textTurn("a"))
      _ <- loop.run(
        seedOf(
          sp.port,
          ToolExecutor.failing[IO]("x"),
          sink,
          hooks = HookRegistry.empty[IO].withObserver(HookPoint.PostRunSettled, _ => trace.update(_ :+ "settled-obs"))
        )
      )
      t <- trace.get
    yield t).map { t =>
      assert(t.indexOf("settled-obs") > t.indexOf("RunEnded"), s"序错：$t")
    }

  // ── 会话级（接线层触发） ────────────────────────────────────────────────

  test("SessionStages：session.Ending / compact.Ended 单点可触发（会话主人=接线层）"):
    (for
      seen <- Ref.of[IO, Vector[Json]](Vector.empty)
      hooks = HookRegistry
        .empty[IO]
        .withInterceptor(HookPoint.SessionEnding, (in: Json) => seen.update(_ :+ in).as(HookEffect.Proceed))
        .withObserver(HookPoint.CompactEnded, obs(seen))
      sink <- EventSink.collecting[IO].map(_._1)
      _ <- SessionStages.ending[IO](hooks.snapshot, sink)
      _ <- SessionStages.compactEnded[IO](hooks.snapshot, sink)
      s <- seen.get
    yield s).map { s =>
      assertEquals(s.size, 2)
      assertEquals(s.head.hcursor.get[String]("stage").toOption, Some("session.ending"))
    }
end SiteAdaptationSpec
