package nebflow.llm.decision

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.core.delegate.DualModeConfig
import nebflow.llm.{ModelCandidate, SendMessageParams}
import nebflow.shared.{LlmProtocol, PathUtil, ProviderConfig}

import scala.concurrent.duration.*

/**
 * DelegateModeMachine spec（双模式批 2026-10-28 · 设计 §3.2 选择器）——stub 腿
 * 注入，网络腿不在 spec 范围（[[PluginAllocatorFallbackSpec]] 同款纪律）：
 *
 *  - 成员规则 `select`：P(need_dispatcher=yes) ≥ 0.5 → dispatcher（等权判 dispatcher
 *    ——官方措辞「0.5 when yes and no are equally easy to act on」）；无读数 = kernel
 *    （便宜侧，绝不 fabricate dispatcher——白付链开销的两错里更贵的那个）。
 *  - `parseMode`：值域校验（kernel|dispatcher 之外 = Left）；reason 缺键容忍；
 *    非 JSON/缺键 = Left（fail-open 走 Failure，绝不 fabricate 成功）。
 *  - 编排三态（stub 腿）：jev 腿答 → leg=jev；jev 挂 → llm 腿接管（leg=llm-fallback，
 *    JevFallback 审计留痕）；llm 也无 → fail-open（方向 = DualModeConfig 旋钮热读）。
 *  - 问对形态：need_dispatcher(CHOICE yes/no) + fit_dispatcher(SCORE) 一次批调。
 *
 * 🔴 测试体纪律：断言一律落在最外层 awaited IO 里（CatsEffectSuite 不展平嵌套 IO）。
 */
class DelegateModeMachineSpec extends CatsEffectSuite:

  override val munitIOTimeout = 60.seconds

  private val failopenKernel = IO.pure(DualModeConfig.ModeKernel)

  private def withTempDataRoot[A](f: os.Path => IO[A]): IO[A] =
    val original = PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "nb-mode-machine-")
    PathUtil.setDataRoot(tmp / "data")
    os.makeDir.all(tmp / "data")
    f(tmp).guarantee(IO(PathUtil.setDataRoot(original)) *> IO(os.remove.all(tmp)))

  /** gate Applied 的 config 面：jev 块 enabled+已知 provider（JevConfigReader 热读）。 */
  private def writeJevConfig(): Unit =
    os.write.over(PathUtil.configJsonReadPath(PathUtil.dataRoot), """{"jev":{"enabled":true,"provider":"typesafe-jev"}}""")

  private def choice(yes: Double): DecisionAnswer =
    DecisionAnswer.Choice(
      if yes >= DecisionAnswer.YesThreshold then "yes" else "no",
      None,
      Map("yes" -> yes, "no" -> (1.0 - yes))
    )

  private def resp(answers: (String, DecisionAnswer)*): DecisionResponse =
    DecisionResponse(None, answers.toMap, None)

  private def machine(
    jev: Option[DecisionProvider[IO]],
    llm: Option[(ModelCandidate, SendMessageParams => IO[Option[String]])],
    failopen: IO[String] = failopenKernel
  ): DelegateModeMachine =
    new DelegateModeMachine(jevLeg = IO.pure(jev), llmLeg = IO.pure(llm), failopen = failopen)

  private def candidate: ModelCandidate =
    ModelCandidate(providerId = "p", provider = ProviderConfig("https://x", "k", LlmProtocol.OpenAI), model = "util-1")

  // ── 成员规则（单目标形） ────────────────────────────────────────────────────

  test("select：P(yes)≥0.5 → dispatcher（0.5 等权判 dispatcher）；<0.5 → kernel") {
    val m = machine(None, None)
    assertEquals(m.select(resp("need_dispatcher" -> choice(0.7))), DualModeConfig.ModeDispatcher)
    assertEquals(m.select(resp("need_dispatcher" -> choice(0.5))), DualModeConfig.ModeDispatcher)
    assertEquals(m.select(resp("need_dispatcher" -> choice(0.3))), DualModeConfig.ModeKernel)
  }

  test("select：probabilities 无 yes 读数 / 答案缺席 ⇒ kernel（便宜侧，绝不猜 dispatcher）") {
    val m = machine(None, None)
    val noYes = DecisionAnswer.Choice("yes", None, Map("no" -> 1.0))
    assertEquals(m.select(resp("need_dispatcher" -> noYes)), DualModeConfig.ModeKernel)
    assertEquals(m.select(resp()), DualModeConfig.ModeKernel, "no answer at all = kernel")
    assertEquals(m.select(resp("fit_dispatcher" -> choice(0.9))), DualModeConfig.ModeKernel, "only the SCORE answered")
  }

  // ── 问对形态（两问一次批调） ────────────────────────────────────────────────

  test("questionsFor：need_dispatcher(CHOICE yes/no) + fit_dispatcher(SCORE poor/partial/good)") {
    val m = machine(None, None)
    val qs = m.questionsFor
    assertEquals(qs.map(_.id), List("need_dispatcher", "fit_dispatcher"))
    assertEquals(qs.head.qtype, DecisionQuestionType.Choice)
    val c = qs.head.asInstanceOf[DecisionQuestion.Choice]
    assertEquals(c.criteria.keySet, Set("yes", "no"))
    assertEquals(qs(1).qtype, DecisionQuestionType.Score)
    assertEquals(qs(1).asInstanceOf[DecisionQuestion.Score].criteria, List("poor", "partial", "good"))
  }

  test("stateFor：固定前导 + 截断后的任务文（超长截断，防止 brief 撑爆决策调用）") {
    val m = machine(None, None)
    val short = m.stateFor("do the thing")
    assert(short.startsWith(DelegateModeMachine.StatePreamble), "state carries the fixed preamble")
    assert(short.contains("do the thing"), "state carries the verbatim brief")
    val cap = JevAllocator.DefaultStateCapChars
    val long = m.stateFor("x" * (cap * 2))
    assert(long.length < cap * 2, s"overlong briefs are truncated to the allocation cap ($cap)")
  }

  // ── LLM fallback 腿回文解析 ────────────────────────────────────────────────

  test("parseMode：bare / fenced / prose-wrapped JSON 均解析；reason 缺键容忍（空串）") {
    val m = machine(None, None)
    assertEquals(m.parseMode("""{"mode":"dispatcher","reason":"multi-leg"}"""), Right(("dispatcher", "multi-leg")))
    assertEquals(m.parseMode("```json\n{\"mode\": \"kernel\"}\n```"), Right(("kernel", "")), "missing reason = tolerated empty")
    assertEquals(
      m.parseMode("I'd say {\"mode\":\"kernel\",\"reason\":\"single artifact\"} is right."),
      Right(("kernel", "single artifact"))
    )
  }

  test("parseMode：非 JSON / 缺 mode = Left；大小写变体归一容忍（Kernel→kernel，容错读法）；值域外 = Left") {
    val m = machine(None, None)
    assert(m.parseMode("no json here").isLeft)
    assert(m.parseMode("""{"reason":"no mode key"}""").isLeft)
    assertEquals(
      m.parseMode("""{"mode":"Kernel","reason":"case"}"""),
      Right(("kernel", "case")),
      "case variants are normalized-and-tolerated (LLM output tolerance; the recorded word lands lowercase)"
    )
    assert(m.parseMode("""{"mode":"both","reason":""}""").isLeft)
  }

  test("prompt：选择基线骨架逐字在案（单会话→kernel / 多阶段并行→dispatcher / 等权取便宜侧 + JSON 指令）") {
    val m = machine(None, None)
    val p = m.prompt("audit this")
    assert(p.contains(DelegateModeMachine.StatePreamble))
    assert(p.contains("single-session single-artifact work"), "the kernel baseline is stated")
    assert(p.contains("multi-stage dependencies, parallel multi-leg work"), "the dispatcher baseline is stated")
    assert(p.contains("choose the cheaper side"), "the tie-break rule is stated")
    assert(p.contains("\"mode\": \"kernel\" | \"dispatcher\""), "the answer contract is stated")
  }

  // ── 编排三态（stub 腿） ────────────────────────────────────────────────────

  test("gate off + 无 llm 候选 ⇒ fail-open kernel（leg=fail-open）") {
    withTempDataRoot { _ =>
      val m = machine(None, None)
      m.decideMode("delegate-x", "task").map { d =>
        assertEquals(d.mode, DualModeConfig.ModeKernel)
        assertEquals(d.leg, "fail-open")
      }
    }
  }

  test("gate off + failopen 旋钮 dispatcher ⇒ fail-open dispatcher（方向热读注入）") {
    withTempDataRoot { _ =>
      val m = machine(None, None, failopen = IO.pure(DualModeConfig.ModeDispatcher))
      m.decideMode("delegate-x", "task").map { d =>
        assertEquals(d.mode, DualModeConfig.ModeDispatcher)
        assertEquals(d.leg, "fail-open")
      }
    }
  }

  test("gate off + llm 腿答 JSON dispatcher ⇒ leg=llm-fallback（mode 采纳，单次调用）") {
    withTempDataRoot { _ =>
      val calls = new java.util.concurrent.atomic.AtomicInteger(0)
      val m = machine(
        None,
        Some(
          candidate ->
            (_ => IO { calls.incrementAndGet(); Some("""{"mode":"dispatcher","reason":"parallel legs"}""" ) })
        )
      )
      m.decideMode("delegate-x", "task").flatMap { d =>
        IO {
          assertEquals(d.mode, DualModeConfig.ModeDispatcher)
          assertEquals(d.leg, "llm-fallback")
          assertEquals(calls.get(), 1, "the fallback leg is a SINGLE JSON call — no retry loop")
        }
      }
    }
  }

  test("gate off + llm 腿回垃圾 ⇒ fail-open（leg=fail-open，审计 Failure）") {
    withTempDataRoot { _ =>
      val m = machine(None, Some(candidate -> (_ => IO.pure(Some("garbage, no json")))))
      m.decideMode("delegate-x", "task").map { d =>
        assertEquals(d.mode, DualModeConfig.ModeKernel)
        assertEquals(d.leg, "fail-open")
        assert(d.reason.contains("unparsable"), s"the refusal reason is carried: ${d.reason}")
      }
    }
  }

  test("jev 腿答 need_dispatcher=yes ⇒ leg=jev（llm 腿零调用）") {
    withTempDataRoot { tmp =>
      writeJevConfig()
      val provider = new DecisionProvider[IO]:
        def id: String = "typesafe-jev"
        def predict(req: DecisionRequest) = IO.pure(Right(resp("need_dispatcher" -> choice(0.95))))
      var llmCalled = 0
      val m = machine(
        Some(provider),
        Some(candidate -> (_ => IO { llmCalled += 1; Some("""{"mode":"kernel","reason":"n/a"}""" ) }))
      )
      m.decideMode("delegate-x", "task").flatMap { d =>
        IO {
          assertEquals(d.mode, DualModeConfig.ModeDispatcher)
          assertEquals(d.leg, "jev")
          assertEquals(llmCalled, 0, "the jev leg answered — the llm fallback leg never ran")
        }
      }
    }
  }

  test("jev 腿挂（provider 失败）⇒ llm 腿接管（reason 带 jev fell back 前缀，fallback 从不静默）") {
    withTempDataRoot { tmp =>
      writeJevConfig()
      val provider = new DecisionProvider[IO]:
        def id: String = "typesafe-jev"
        def predict(req: DecisionRequest) = IO.pure(Left(DecisionError.Transport("net down")))
      val m = machine(
        Some(provider),
        Some(candidate -> (_ => IO.pure(Some("""{"mode":"kernel","reason":"single"}""" ))))
      )
      m.decideMode("delegate-x", "task").flatMap { d =>
        IO {
          assertEquals(d.mode, DualModeConfig.ModeKernel)
          assertEquals(d.leg, "llm-fallback")
          assert(d.reason.startsWith("jev leg fell back"), s"the fallback is loud: ${d.reason}")
        }
      }
    }
  }

  test("jev 腿 provider 未装配（gate Applied 但 jevLeg=None）⇒ llm 腿接管") {
    withTempDataRoot { tmp =>
      writeJevConfig()
      val m = machine(None, Some(candidate -> (_ => IO.pure(Some("""{"mode":"dispatcher","reason":"p"}""" )))))
      m.decideMode("delegate-x", "task").map { d =>
        assertEquals(d.mode, DualModeConfig.ModeDispatcher)
        assertEquals(d.leg, "llm-fallback")
      }
    }
  }
end DelegateModeMachineSpec
