package nebflow.llm

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.shared.*

import scala.concurrent.duration.*

import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.StreamBackend

/**
 * 统一模型层 golden 矩阵（P1 七类齐）：槽配置 / 时段内外 / 小模型 / 决策路由 / 静态断言 (d)。
 * 与 [[ModelDecisionGoldenSpec]]（无槽、钉链、超限、幂等、trace）合并构成七类矩阵。
 */
class ModelDecisionGoldenMatrixSpec extends CatsEffectSuite:

  override val munitIOTimeout = 60.seconds

  private val NullBackend = null.asInstanceOf[StreamBackend[IO, Fs2Streams[IO]]]

  private def provider(cap: Option[Int], models: String*): ProviderConfig =
    ProviderConfig(
      baseUrl = "http://127.0.0.1:1/",
      apiKey = "fake-key-not-a-secret",
      protocol = LlmProtocol.OpenAI,
      models = models.toList.map(m => ModelConfig(m, contextWindow = 1000)),
      maxConcurrentActors = cap
    )

  private def cfgWith(decisionRoutes: Option[Map[String, AgentModelConfig]] = None): NebflowServiceConfig =
    NebflowServiceConfig(
      llm = ServiceLlmConfig(
        providers = Map("a" -> provider(Some(1), "m1"), "b" -> provider(None, "m1")),
        fallback = Some(AgentModelConfig(Some("a/m1"), Nil)),
        decisionRoutes = decisionRoutes
      )
    )

  private def regWith(c: NebflowServiceConfig): IO[ProviderRegistry] =
    Ref.of[IO, NebflowServiceConfig](c).map(r => new ProviderRegistry(r, NullBackend))

  /** 临时数据根（与 ReloadStaleSessionModelSpec 同款重定向），退出即还原。 */
  private def withTempDataRoot[A](f: os.Path => IO[A]): IO[A] =
    val original = nebflow.shared.PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "nb-model-decision-golden-")
    nebflow.shared.PathUtil.setDataRoot(tmp)
    f(tmp).guarantee(IO(nebflow.shared.PathUtil.setDataRoot(original)) *> IO(os.remove.all(tmp)))

  private def writeAgent(root: os.Path, name: String, preferred: String): Unit =
    os.write.over(
      root / "agents" / name / "agent.json",
      s"""{"model":{"preferred":"$preferred","fallbacks":[]}}""",
      createFolders = true
    )

  private def keys(cs: List[ModelCandidate]) = cs.map(c => c.providerId -> c.model)

  // ── 类 2：槽配置（data-root sidecar）──────────────────────────────────────

  test("G-slot 槽已配置：组合 == 旧 slotChainOf，候选 == 旧 getCandidatesForAgent(槽链)，槽优先于钉链") {
    withTempDataRoot { root =>
      writeAgent(root, nebflow.core.SchemePolicy.NebulaName, "b/m1")
      val sid = Some("ask-golden-slot-1") // ask 会话 ⇒ 主槽（MainWire）
      val pin = ProviderConcurrency.pinAt(List("a" -> "m1"), 0)
      for
        r <- regWith(cfgWith())
        legacySlot = nebflow.core.ModelRoles.slotChainOf(sid, None)
        (composed, hits) = ModelDecision.composeAgentModel(sid, None, Some(AgentModelConfig(Some("a/m1"), Nil)), Some(pin))
        legacyCands <- r.getCandidatesForAgent(legacySlot)
        d <- ModelDecision.resolveRequest(r, ModelDecision.RequestCtx(sid, pinnedChain = Some(pin)), Some(AgentModelConfig(Some("a/m1"), Nil)))
      yield
        assertEquals(legacySlot.map(_.preferred), Some(Some("b/m1")))
        assertEquals(composed, legacySlot, "槽必须压过钉链与 base")
        assertEquals(hits.head.detail, "slot chain (slot configured)")
        assertEquals(keys(d.candidates), keys(legacyCands))
    }
  }

  // ── 类 3：时段窗口内外（注入时钟，纯函数 covers）──────────────────────────

  test("G-window 时段内外：covers 注入固定时刻，窗内 true、窗外 false（Asia/Shanghai）") {
    import java.time.{Instant, ZoneId}
    val zone = ZoneId.of("Asia/Shanghai")
    val w = UnavailableWindow("14:00", "18:00")
    val inside = Instant.parse("2026-11-21T07:00:00Z").toEpochMilli  // 15:00 +08
    val outside = Instant.parse("2026-11-21T11:00:00Z").toEpochMilli // 19:00 +08
    assert(nebflow.shared.ModelAvailability.covers(w, inside, zone), "15:00 应在 14:00-18:00 窗内")
    assert(!nebflow.shared.ModelAvailability.covers(w, outside, zone), "19:00 应在窗外")
    IO.unit
  }

  // ── 类 4：小模型（分流 + 候选同 legacy）──────────────────────────────────

  test("G-small 小模型：smallLlmCandidates == 旧 getCandidatesForAgent(smallLlmModel)；ask/small-llm 分流，普通调用不分流") {
    withTempDataRoot { root =>
      writeAgent(root, nebflow.core.SchemePolicy.NebulaName, "b/m1")
      for
        r <- regWith(cfgWith())
        legacy <- r.getCandidatesForAgent(Some(nebflow.core.SchemePolicy.smallLlmModel()))
        viaUnified <- ModelDecision.smallLlmCandidates(r)
      yield
        assertEquals(keys(viaUnified), keys(legacy))
        val askCtx = ModelDecision.RequestCtx(Some("ask-x"), isAskSession = true)
        assertEquals(ModelDecision.smallLlmRoute(askCtx), Some(nebflow.core.SchemePolicy.smallLlmModel()))
        assertEquals(ModelDecision.smallLlmRoute(ModelDecision.RequestCtx(None, callType = Some("small-llm"))),
          Some(nebflow.core.SchemePolicy.smallLlmModel()))
        assertEquals(ModelDecision.smallLlmRoute(ModelDecision.RequestCtx(None)), None)
    }
  }

  // ── 类 5：决策路由 ───────────────────────────────────────────────────────

  test("G-decision 决策路由：显式 ref 可解 ⇒ 钉首位并与全局链去重；未配 ⇒ 与 nebula 主链候选一致") {
    val routes = Map("rerank" -> AgentModelConfig(Some("b/m1"), Nil))
    withTempDataRoot { root =>
      writeAgent(root, nebflow.core.SchemePolicy.NebulaName, "a/m1")
      for
        r <- regWith(cfgWith(Some(routes)))
        routed <- ModelDecision.decisionRouteCandidates(r, "rerank")
        unrouted <- ModelDecision.decisionRouteCandidates(r, "not-configured")
        mainLegacy <- r.getCandidatesForAgent(nebflow.core.SchemePolicy.nebulaChain())
      yield
        assertEquals(routed.headOption.map(c => c.providerId -> c.model), Some("b" -> "m1"), "显式 ref 必须钉首位")
        assertEquals(routed.map(c => c.providerId -> c.model).distinct.size, routed.size, "与全局链去重")
        assertEquals(keys(unrouted), keys(mainLegacy), "未配路由 ⇒ 与主链候选一致")
    }
  }

  // ── 类 6：静态断言 (d) ─────────────────────────────────────────────────

  test("G-static (d) LlmKernel 不再自组合：源码不含 registry.getCandidatesForAgent / getCandidates() / nebulaChain() 直调") {
    val src = os.read(os.pwd / "src" / "main" / "scala" / "nebflow" / "llm" / "kernel" / "LlmKernel.scala")
    val banned = List("registry.getCandidatesForAgent(", "registry.getCandidates()", "SchemePolicy.nebulaChain()", "SchemePolicy.smallLlmModel()")
    banned.foreach { pat =>
      assertEquals(src.split(java.util.regex.Pattern.quote(pat), -1).length - 1, 0, s"LlmKernel 仍直调：$pat")
    }
    IO.unit
  }

end ModelDecisionGoldenMatrixSpec
