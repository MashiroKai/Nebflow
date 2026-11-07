package nebflow.llm

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.shared.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.StreamBackend

/**
 * config-single-source batch (author rulings ①③ 2026-11-05) — the runtime face:
 * the config file's global-model section drives the candidate, and a provider
 * that leaves the config really leaves the candidate set (the author's reported
 * defect: a deleted provider kept being called because the chain lived outside
 * the file).
 *
 * **fallback 废除批改写（作者令 2026-11-06 20:36「不再支持模型fallback」，逐条申报）**：
 *   - B/C-1 / C-2 / C-5 / C-6 按「链深恒 1 = preferred 单候选」**重写**（原断言的
 *     多候选顺序面随 fallback 退役；原文字见 git 历史）；
 *   - C-7（储备层确定序）**整条删除**——储备层本身就是 fallback，面已退役
 *     （`reserveTier` 从 ProviderRegistry 移除）；
 *   - C-3 / C-4 / C-8 断言原样保留（其判据在新语义下依然成立：contains / 跳过 /
 *     fail-closed 面）。
 *
 * Assertion discipline: only public API (`getCandidatesForAgentUnfiltered`,
 * `candidatePlan`) is used, and the config is built in-spec — no real HOME is
 * read (a non-empty `llm.fallback.preferred` keeps the resolution on the config
 * leg).
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

  test("B/C-1(重写): llm.fallback 的 preferred 就是运行期唯一候选（链深恒 1）") {
    val chain = Some(AgentModelConfig(Some("c/m3"), List("a/m1")))
    for
      reg <- mkRegistry(cfg(chain, "a" -> provider("m1"), "b" -> provider("m2"), "c" -> provider("m3")))
      cs <- reg.getCandidatesForAgentUnfiltered(None)
    yield
      val r = refs(cs)
      assertEquals(r, List("c/m3"), s"候选必须恰等于 llm.fallback.preferred（fallbacks 数组退役），实际 = $r")
  }

  test("C-2(重写): 改 llm.fallback.preferred ⇒ 运行期候选随之改变（同配置其余不动）") {
    val forward = Some(AgentModelConfig(Some("c/m3"), List("a/m1")))
    val reversed = Some(AgentModelConfig(Some("a/m1"), List("c/m3")))
    for
      reg1 <- mkRegistry(cfg(forward, "a" -> provider("m1"), "b" -> provider("m2"), "c" -> provider("m3")))
      before <- reg1.getCandidatesForAgentUnfiltered(None)
      reg2 <- mkRegistry(cfg(reversed, "a" -> provider("m1"), "b" -> provider("m2"), "c" -> provider("m3")))
      after <- reg2.getCandidatesForAgentUnfiltered(None)
    yield
      assertEquals(refs(before), List("c/m3"))
      assertEquals(refs(after), List("a/m1"))
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

  test("C-5(重写): 候选集只来自链 preferred（配置实存；不追加储备层——该面已退役）") {
    val chain = Some(AgentModelConfig(Some("a/m1"), Nil))
    for cs <- mkRegistry(cfg(chain, "a" -> provider("m1"), "b" -> provider("m2")))
        .flatMap(_.getCandidatesForAgentUnfiltered(None))
    yield assertEquals(refs(cs), List("a/m1"), "链深恒 1：未引用的 b/m2 不再被追加")
  }

  test("C-6(重写): candidatePlan 可观测面列出「这次调用会试哪个、哪个被跳过」") {
    val staleChain = Some(AgentModelConfig(Some("ghost/m1"), List("a/m1")))
    for plan <- mkRegistry(cfg(staleChain, "a" -> provider("m1"))).flatMap(_.candidatePlan())
    yield
      assertEquals(plan.hcursor.downField("source").as[String].toOption, Some("llm.fallback"))
      assertEquals(plan.hcursor.downField("chain").as[List[String]].toOption, Some(List("ghost/m1")))
      assertEquals(plan.hcursor.downField("skipped").as[List[String]].toOption, Some(List("ghost/m1")))
      assertEquals(plan.hcursor.downField("candidates").as[List[String]].toOption, Some(List("a/m1")))
      assert(
        plan.hcursor.downField("warnings").as[List[String]].toOption.exists(_.exists(_.contains("ghost"))),
        "可观测面必须带上链校验告警"
      )
  }

  // （原 C-7 储备层确定序断言整条删除——储备层本身就是 fallback，随 fallback 废除
  // 批（作者令 2026-11-06 20:36）退役，`reserveTier` 已从 ProviderRegistry 移除。）

  test("C-8: disabled:true 的 provider 一律不进候选集（fail-closed 不回退）") {
    val disabled = provider("m3").copy(disabled = Some(true))
    val chain = Some(AgentModelConfig(Some("a/m1"), Nil))
    for cs <- mkRegistry(cfg(chain, "a" -> provider("m1"), "c" -> disabled))
        .flatMap(_.getCandidatesForAgentUnfiltered(None))
    yield assert(!refs(cs).exists(_.startsWith("c/")), s"disabled provider 不得被选，实际 = ${refs(cs)}")
  }
end FallbackChainCandidateSpec
