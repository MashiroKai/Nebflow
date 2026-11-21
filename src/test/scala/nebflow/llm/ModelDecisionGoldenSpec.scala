package nebflow.llm

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.shared.*

import scala.concurrent.duration.*

import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.StreamBackend

/**
 * 统一模型层 golden（模型分配规则引擎 P1，行为等价）：新 `ModelDecision` 与旧直接调用逐项相等。
 *
 * 覆盖（本文件，无需数据根 sidecar 的场景）：
 *   - 无槽（sessionId=None）× 有钉 / 无钉：组合结果 = 旧 `pin.fold(base)` 组合；
 *   - 钉链失效（钉首不在配置）：组合仍按钉链物化（失效判据在 AgentSessionExecution 上游，不变）；
 *   - 全部超限：spawnPin = None 且 assign 不占坑；
 *   - 占坑幂等：同 actor 二次 spawnPin 不迁移；
 *   - trace：每个候选至少一条 `candidate` 命中。
 *
 * 未覆盖（P1 后续补齐，需数据根 fixture）：有槽（slot sidecar）、时段内外（需固定时钟注入）、
 * 小模型、决策路由——这些类别的 golden 在 P1 收尾前补，见终报「未闭环项」。
 */
class ModelDecisionGoldenSpec extends CatsEffectSuite:

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

  private val cfg: NebflowServiceConfig =
    NebflowServiceConfig(
      llm = ServiceLlmConfig(
        providers = Map("a" -> provider(Some(1), "m1"), "b" -> provider(None, "m1")),
        fallback = Some(AgentModelConfig(Some("a/m1"), Nil))
      )
    )

  private def reg: IO[ProviderRegistry] =
    Ref.of[IO, NebflowServiceConfig](cfg).map(r => new ProviderRegistry(r, NullBackend))

  private val base: Option[AgentModelConfig] = Some(AgentModelConfig(Some("b/m1"), Nil))
  private val pinned: PinnedChain = ProviderConcurrency.pinAt(List("a" -> "m1", "b" -> "m1"), 0)

  test("G1 无槽无钉：resolveRequest 候选 == 旧 getCandidatesForAgent(base)") {
    for
      r <- reg
      legacy <- r.getCandidatesForAgent(base)
      d <- ModelDecision.resolveRequest(r, ModelDecision.RequestCtx(None), base)
    yield
      assertEquals(d.candidates.map(c => c.providerId -> c.model), legacy.map(c => c.providerId -> c.model))
      assertEquals(d.pin, None)
  }

  test("G2 无槽有钉：组合链 == 钉链物化（旧 agentModelWithPin 语义）") {
    for
      r <- reg
      legacy <- r.getCandidatesForAgent(Some(pinned.toAgentModelConfig))
      d <- ModelDecision.resolveRequest(r, ModelDecision.RequestCtx(None, pinnedChain = Some(pinned)), base)
    yield
      assertEquals(d.candidates.map(c => c.providerId -> c.model), legacy.map(c => c.providerId -> c.model))
      assertEquals(d.pin, Some(pinned))
  }

  test("G3 钉链失效判据：组合仍按钉链物化，失效回落由上游 pinVerdict 决定（不变）") {
    val stale = ProviderConcurrency.pinAt(List("gone" -> "x"), 0)
    val (composed, _) = ModelDecision.composeAgentModel(None, None, base, Some(stale))
    assertEquals(composed, Some(stale.toAgentModelConfig))
  }

  test("G4 全部超限：spawnPin = None 且不占坑") {
    for
      r <- reg
      counts <- Ref.of[IO, ProviderConcurrency.Counts](ProviderConcurrency.empty)
      // 占满 a（cap=1）后，链 = [a/m1] ⇒ 全满 ⇒ None
      _ <- ModelDecision.spawnPin(r, counts, "actor-1", "agent", Some(AgentModelConfig(Some("a/m1"), Nil)))
      d <- ModelDecision.spawnPin(r, counts, "actor-2", "agent", Some(AgentModelConfig(Some("a/m1"), Nil)))
      c <- counts.get
    yield
      assertEquals(d.pin, None)
      assert(!c.byProvider.values.exists(_.contains("actor-2")), "all-full must not occupy a slot")
  }

  test("G5 占坑幂等：同 actor 二次 spawnPin（同一 agent 链）不迁移，返回首钉") {
    // 注意：幂等路径按「现链里找首钉 provider 的下标」回快照（ProviderConcurrency.assign:
    // 幂等分支 indexWhere）——故二次派发的链必须与首次同源（生产同 agent 同链）。
    // 换链二次派发会返回 None，这是既有行为，P1 逐字保留，不在本批修改。
    for
      r <- reg
      counts <- Ref.of[IO, ProviderConcurrency.Counts](ProviderConcurrency.empty)
      first <- ModelDecision.spawnPin(r, counts, "actor-x", "agent", base)
      again <- ModelDecision.spawnPin(r, counts, "actor-x", "agent", base)
    yield
      assertEquals(first.pin.map(_.headRef), Some("b/m1"))
      assertEquals(again.pin.map(_.headRef), first.pin.map(_.headRef))
  }

  test("G6 trace：每个候选至少一条 candidate 命中") {
    for
      r <- reg
      d <- ModelDecision.resolveRequest(r, ModelDecision.RequestCtx(None), base)
    yield
      val candHits = d.trace.count(_.step == "candidate")
      assertEquals(candHits, d.candidates.size)
      assert(d.candidates.nonEmpty, "fixture must yield at least one candidate")
  }

end ModelDecisionGoldenSpec
