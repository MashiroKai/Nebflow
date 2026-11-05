package nebflow.llm

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.shared.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.StreamBackend

/**
 * config-single-source batch (author rulings ①③ 2026-11-05) — the runtime face:
 * the config file's `llm.fallback` section drives the candidate order, and a
 * provider that leaves the config really leaves the candidate set (the author's
 * reported defect: a deleted provider kept being called because the chain lived
 * outside the file).
 *
 * Assertion discipline: only public API (`getCandidatesForAgentUnfiltered`,
 * `candidatePlan`) plus the package-visible `reserveTier` are used, and the
 * config is built in-spec — no real HOME is read (a non-empty `llm.fallback`
 * keeps the resolution on the config leg).
 */
class FallbackChainCandidateSpec extends CatsEffectSuite:

  private val NullBackend = null.asInstanceOf[StreamBackend[IO, Fs2Streams[IO]]]

  private def provider(models: String*): ProviderConfig =
    ProviderConfig(
      baseUrl = "http://127.0.0.1:1/",
      apiKey = "fake-key-not-a-secret",
      protocol = LlmProtocol.OpenAI,
      models = models.toList.map(m => ModelConfig(m, contextWindow = 1000))
    )

  private def cfg(chain: Option[AgentModelConfig], providers: (String, ProviderConfig)*): NebflowServiceConfig =
    NebflowServiceConfig(llm = ServiceLlmConfig(providers = providers.toMap, fallback = chain))

  private def mkRegistry(c: NebflowServiceConfig): IO[ProviderRegistry] =
    Ref.of[IO, NebflowServiceConfig](c).map(ref => new ProviderRegistry(ref, NullBackend))

  private def refs(cs: List[ModelCandidate]): List[String] = cs.map(c => s"${c.providerId}/${c.model}")

  test("B/C-1: llm.fallback 的顺序就是运行期候选顺序（首选 + 兜底按文件序）") {
    val chain = Some(AgentModelConfig(Some("c/m3"), List("a/m1")))
    for
      reg <- mkRegistry(cfg(chain, "a" -> provider("m1"), "b" -> provider("m2"), "c" -> provider("m3")))
      cs <- reg.getCandidatesForAgentUnfiltered(None)
    yield
      val r = refs(cs)
      assertEquals(r.take(2), List("c/m3", "a/m1"), s"候选前缀必须逐字等于 llm.fallback 顺序，实际 = $r")
      assertEquals(r.size, r.distinct.size, "候选不得重复")
  }

  test("C-2: 改 llm.fallback 顺序 ⇒ 运行期候选顺序随之改变（同配置其余不动）") {
    val forward = Some(AgentModelConfig(Some("c/m3"), List("a/m1")))
    val reversed = Some(AgentModelConfig(Some("a/m1"), List("c/m3")))
    for
      reg1 <- mkRegistry(cfg(forward, "a" -> provider("m1"), "b" -> provider("m2"), "c" -> provider("m3")))
      before <- reg1.getCandidatesForAgentUnfiltered(None)
      reg2 <- mkRegistry(cfg(reversed, "a" -> provider("m1"), "b" -> provider("m2"), "c" -> provider("m3")))
      after <- reg2.getCandidatesForAgentUnfiltered(None)
    yield
      assertEquals(refs(before).take(2), List("c/m3", "a/m1"))
      assertEquals(refs(after).take(2), List("a/m1", "c/m3"))
  }

  test("C-3: 删掉 provider ⇒ 候选集不含它；加回 ⇒ 立刻含它（断言正反两面）") {
    val chainWithC = Some(AgentModelConfig(Some("c/m3"), List("a/m1")))
    val withC = cfg(chainWithC, "a" -> provider("m1"), "b" -> provider("m2"), "c" -> provider("m3"))
    val withoutC = cfg(Some(AgentModelConfig(Some("a/m1"), Nil)), "a" -> provider("m1"), "b" -> provider("m2"))
    for
      before <- mkRegistry(withC).flatMap(_.getCandidatesForAgentUnfiltered(None))
      gone <- mkRegistry(withoutC).flatMap(_.getCandidatesForAgentUnfiltered(None))
      back <- mkRegistry(withC).flatMap(_.getCandidatesForAgentUnfiltered(None))
    yield
      assert(refs(before).contains("c/m3"), "删前必须含 c/m3")
      assert(!refs(gone).exists(_.startsWith("c/")), s"删掉 provider 后候选集不得残留 c/*，实际 = ${refs(gone)}")
      assert(refs(back).contains("c/m3"), "加回后必须立即含 c/m3")
  }

  test("C-4: 链里残留已删 provider 的 ref ⇒ 该 ref 被跳过（绝不真实调用）") {
    val staleChain = Some(AgentModelConfig(Some("ghost/m1"), List("a/m1")))
    for cs <- mkRegistry(cfg(staleChain, "a" -> provider("m1"))).flatMap(_.getCandidatesForAgentUnfiltered(None))
    yield assertEquals(refs(cs), List("a/m1"), s"不可解析的链引用必须跳过且不留空位，实际 = ${refs(cs)}")
  }

  test("C-5: 候选集只来自配置实存的 provider/model（不凭空造；储备层只追加）") {
    val chain = Some(AgentModelConfig(Some("a/m1"), Nil))
    for cs <- mkRegistry(cfg(chain, "a" -> provider("m1"), "b" -> provider("m2")))
        .flatMap(_.getCandidatesForAgentUnfiltered(None))
    yield assertEquals(refs(cs), List("a/m1", "b/m2"), "链顺序在前，储备层按 providerId 字典序追加")
  }

  test("C-6: candidatePlan 可观测面列出「这次调用会试哪些、按什么顺序、哪些被跳过」") {
    val staleChain = Some(AgentModelConfig(Some("ghost/m1"), List("a/m1")))
    for plan <- mkRegistry(cfg(staleChain, "a" -> provider("m1"))).flatMap(_.candidatePlan())
    yield
      assertEquals(plan.hcursor.downField("source").as[String].toOption, Some("llm.fallback"))
      assertEquals(plan.hcursor.downField("chain").as[List[String]].toOption, Some(List("ghost/m1", "a/m1")))
      assertEquals(plan.hcursor.downField("skipped").as[List[String]].toOption, Some(List("ghost/m1")))
      assertEquals(plan.hcursor.downField("candidates").as[List[String]].toOption, Some(List("a/m1")))
      assert(
        plan.hcursor.downField("warnings").as[List[String]].toOption.exists(_.exists(_.contains("ghost"))),
        "可观测面必须带上链校验告警"
      )
  }

  test("C-7: 无 fallback 小节时储备层语义不变（确定序、不重排已引用项）") {
    val c = cfg(None, "b" -> provider("m2"), "a" -> provider("m1"))
    val reg = new ProviderRegistry(Ref.unsafe(c), NullBackend)
    assertEquals(refs(reg.reserveTier(c, Nil)), List("a/m1", "b/m2"))
  }

  test("C-8: disabled:true 的 provider 一律不进候选集（fail-closed 不回退）") {
    val disabled = provider("m3").copy(disabled = Some(true))
    val chain = Some(AgentModelConfig(Some("a/m1"), Nil))
    for cs <- mkRegistry(cfg(chain, "a" -> provider("m1"), "c" -> disabled))
        .flatMap(_.getCandidatesForAgentUnfiltered(None))
    yield assert(!refs(cs).exists(_.startsWith("c/")), s"disabled provider 不得被选，实际 = ${refs(cs)}")
  }
end FallbackChainCandidateSpec
