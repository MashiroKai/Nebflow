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

  test("C-4(重写): 链里残留已删 provider 的 ref ⇒ 该 ref 被跳过（绝不真实调用）；链深恒 1 ⇒ 空链 fail-closed") {
    // 合并批（2026-11-07）：原判床 `AgentModelConfig(Some("ghost/m1"), List("a/m1"))`
    // 靠 **fallbacks 里的 a/m1** 撑出 `List("a/m1")`——fallback 废除后尾链不读
    // （死数据）⇒ 该判床结构性不可达。改为两条腿：①残 ref 作 preferred ⇒ 跳过且
    // 不留空位（链空，fail-closed，绝不抛异常）；②可解析 preferred + 尾部死数据 ⇒
    // 照常出链，死数据不得干扰。
    val staleChain = Some(AgentModelConfig(Some("ghost/m1"), Nil))
    val liveWithDeadTail = Some(AgentModelConfig(Some("a/m1"), List("ghost/m1")))
    for
      stale <- mkRegistry(cfg(staleChain, "a" -> provider("m1"))).flatMap(_.getCandidatesForAgentUnfiltered(None))
      live <- mkRegistry(cfg(liveWithDeadTail, "a" -> provider("m1"))).flatMap(_.getCandidatesForAgentUnfiltered(None))
    yield
      assertEquals(refs(stale), Nil, s"不可解析的链引用必须跳过且不留空位（无尾链可接），实际 = ${refs(stale)}")
      assertEquals(refs(live), List("a/m1"), s"可解析 preferred 照常出链，实际 = ${refs(live)}")
  }

  test("C-5: agent 显式链是资格边界；配置但未列入的模型不进候选（储备层退役，链深恒 1）") {
    val agent = Some(AgentModelConfig(Some("a/m1"), Nil))
    for cs <- mkRegistry(cfg(None, "a" -> provider("m1"), "b" -> provider("m2")))
        .flatMap(_.getCandidatesForAgentUnfiltered(agent))
    yield assertEquals(refs(cs), List("a/m1"), "配置实存但未列入者不得进候选（无储备层可扩张）")
  }

  test("C-5a(重写): 声明的 fallbacks 是死数据——chains 尾巴不再扩链（作者令 2026-11-06 20:36）") {
    // 原断言：`AgentModelConfig(Some("a/m1"), List("b/m2"))` ⇒ 候选 List("a/m1","b/m2")。
    // fallback 废除批后 `fallbacks` 不再进请求链 ⇒ 候选恒 = preferred 单根。
    // （字段保留为兼容读取的旧配置死数据；原断言逐字见 git 历史。）
    val agent = Some(AgentModelConfig(Some("a/m1"), List("b/m2")))
    for cs <- mkRegistry(cfg(None, "a" -> provider("m1"), "b" -> provider("m2")))
        .flatMap(_.getCandidatesForAgentUnfiltered(agent))
    yield assertEquals(refs(cs), List("a/m1"), "fallbacks 退役：链深恒 1")
  }

  test("C-5b: agent 链空时走全局默认链，不将其它已配置模型作为储备候选") {
    val agent = Some(AgentModelConfig(None, Nil))
    val global = Some(AgentModelConfig(Some("a/m1"), Nil))
    for cs <- mkRegistry(cfg(global, "a" -> provider("m1"), "b" -> provider("m2")))
        .flatMap(_.getCandidatesForAgentUnfiltered(agent))
    yield assertEquals(refs(cs), List("a/m1"))
  }

  test("C-5c: 显式链无法解析时 fail-closed，不回退全局/储备模型") {
    val agent = Some(AgentModelConfig(Some("ghost/missing"), Nil))
    val global = Some(AgentModelConfig(Some("a/m1"), Nil))
    for cs <- mkRegistry(cfg(global, "a" -> provider("m1"), "b" -> provider("m2")))
        .flatMap(_.getCandidatesForAgentUnfiltered(agent))
    yield assertEquals(refs(cs), Nil)
  }

  test("C-5d(新): 全局默认链同样取 preferred 单根——链深恒 1 不只在 agent 链上成立") {
    val global = Some(AgentModelConfig(Some("a/m1"), List("b/m2")))
    for cs <- mkRegistry(cfg(global, "a" -> provider("m1"), "b" -> provider("m2")))
        .flatMap(_.getCandidatesForAgentUnfiltered(None))
    yield assertEquals(refs(cs), List("a/m1"), "全局链的 fallbacks 同为死数据，链深恒 1")
  }

  test("C-6(重写2): candidatePlan 可观测面——残 ref 如实进 skipped/告警，候选 fail-closed 为空，未授权者带原因") {
    // 合并批（2026-11-07）：原判床同样吃 fallbacks 尾链（`candidates` 期望 a/m1
    // 来自死数据）。改为两条腿：①preferred 不可解析 ⇒ candidates 空 + skipped/
    // 告警如实；②preferred 可解析 + 尾部死数据 ⇒ candidates = preferred 单根、
    // chain 只列 preferred（死数据不得进 chain 面）。
    val staleChain = Some(AgentModelConfig(Some("ghost/m1"), Nil))
    val liveWithDeadTail = Some(AgentModelConfig(Some("a/m1"), List("b/m2")))
    for
      stale <- mkRegistry(cfg(staleChain, "a" -> provider("m1"), "b" -> provider("m2"))).flatMap(_.candidatePlan())
      live <- mkRegistry(cfg(liveWithDeadTail, "a" -> provider("m1"), "b" -> provider("m2"))).flatMap(_.candidatePlan())
    yield
      assertEquals(stale.hcursor.downField("source").as[String].toOption, Some("llm.fallback"))
      assertEquals(stale.hcursor.downField("chain").as[List[String]].toOption, Some(List("ghost/m1")))
      assertEquals(stale.hcursor.downField("skipped").as[List[String]].toOption, Some(List("ghost/m1")))
      assertEquals(
        stale.hcursor.downField("candidates").as[List[String]].toOption,
        Some(Nil),
        "链深恒 1 且唯一根不可解析 ⇒ 候选空（fail-closed，不从配置库存编造）"
      )
      assertEquals(stale.hcursor.downField("excluded").as[List[String]].toOption, Some(List("a/m1", "b/m2")))
      assert(
        stale.hcursor.downField("warnings").as[List[String]].toOption.exists(_.exists(_.contains("ghost"))),
        "可观测面必须带上链校验告警"
      )
      assertEquals(
        live.hcursor.downField("chain").as[List[String]].toOption,
        Some(List("a/m1")),
        "chain 面只列 preferred（fallbacks 死数据）"
      )
      assertEquals(live.hcursor.downField("candidates").as[List[String]].toOption, Some(List("a/m1")))
      assertEquals(live.hcursor.downField("skipped").as[List[String]].toOption, Some(Nil))
      assertEquals(live.hcursor.downField("excluded").as[List[String]].toOption, Some(List("b/m2")))
      assertEquals(
        live.hcursor.downField("exclusionReasons").downField("b/m2").as[String].toOption,
        Some("not in selected chain (provider config is not authorization)")
      )
    end for
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
