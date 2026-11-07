package nebflow.llm

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.shared.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.StreamBackend

/**
 * disabled provider 链 tiering 语义 spec（reserveTier 语义修复批，2026-10-30）——
 * 生图批侦察实证的引擎缺口（docs/20261030_cmdcode-provider-removal.md §5）：摘除
 * 时配置增补 `disabled: true`，但引擎不读该标记 ⇒ 已摘除 provider 仍可能被链
 * tiering 选中。本批修复 = fail-closed：链 tiering 全路径一律不可被选；未 disabled
 * 的 provider 不受影响。
 *
 * **fallback 废除批改写（作者令 2026-11-06 20:36「不再支持模型fallback」，如实申报）**：
 * 原 T4/T5 直调 `reserveTier`（储备层）——储备层本身就是 fallback，随本批**退役**；
 * 两条 fail-closed 断言按新语义**重写**到链构建面（链深恒 1：disabled provider 不得
 * 经任何腿进链；全 disabled 时链构建不凭空造候选）。原断言的逐字内容见 git 历史。
 *
 * 断言纪律：全部经 `getCandidatesForAgent(Unfiltered)` / `getCandidateForRef` /
 * `getCandidatesUnfiltered`（真实调用链）。配置全部在 spec 内构造，不读/不写真实
 * HOME（不触发 `getCandidatesUnfiltered` 的默认 preset 面）。
 */
class DisabledProviderSelectionSpec extends CatsEffectSuite:

  private val NullBackend = null.asInstanceOf[StreamBackend[IO, Fs2Streams[IO]]]

  private def mkProvider(models: List[String], disabled: Option[Boolean] = None): ProviderConfig =
    ProviderConfig(
      baseUrl = "http://127.0.0.1:1",
      apiKey = "k",
      protocol = LlmProtocol.Anthropic,
      models = models.map(m => ModelConfig(m)),
      disabled = disabled
    )

  private def mkRegistry(cfg: NebflowServiceConfig): IO[ProviderRegistry] =
    Ref.of[IO, NebflowServiceConfig](cfg).map(ref => new ProviderRegistry(ref, NullBackend))

  private def refs(cs: List[ModelCandidate]): List[String] =
    cs.map(c => s"${c.providerId}/${c.model}")

  /** cmdcode 摘除形态镜像：cmdcode disabled=true，zhipu/kimi 正常。 */
  private def removalLikeConfig: NebflowServiceConfig =
    NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map(
      "cmdcode" -> mkProvider(List("deepseek-v4.1-flash"), disabled = Some(true)),
      "kimi"    -> mkProvider(List("kimi-for-coding")),
      "zhipu"   -> mkProvider(List("glm-5.3-flash"))
    )))

  test("T1(重写): disabled provider 不进链（fail-closed），链深恒 1 = preferred 单候选") {
    val cfg = removalLikeConfig
    for
      reg <- mkRegistry(cfg)
      cs  <- reg.getCandidatesForAgent(Some(AgentModelConfig(
               preferred = Some("kimi/kimi-for-coding"),
               fallbacks = Nil
             )))
    yield
      val r = refs(cs)
      assert(!r.exists(_.startsWith("cmdcode/")), s"disabled provider 不得进链，实际链 = $r")
      // fallback 废除批：链 = preferred 单候选（储备层退役 ⇒ 不再追加 zhipu 等
      // 未 disabled 项——原「储备层项照常进链」断言随该面退役）。
      assertEquals(r, List("kimi/kimi-for-coding"), s"链深恒 1，实际链 = $r")
  }

  test("T2: agent 显式 ref 指向 disabled provider ⇒ 不可被选，链落到其余候选") {
    val cfg = removalLikeConfig
    for
      reg <- mkRegistry(cfg)
      one <- reg.getCandidateForRef("cmdcode/deepseek-v4.1-flash")
      cs  <- reg.getCandidatesForAgent(Some(AgentModelConfig(
               preferred = Some("cmdcode/deepseek-v4.1-flash"),
               fallbacks = List("kimi/kimi-for-coding")
             )))
    yield
      assertEquals(one, None, "disabled provider 的显式 ref 必须解析为 None（fail-closed）")
      val r = refs(cs)
      assert(!r.exists(_.startsWith("cmdcode/")), s"disabled provider 不得经显式 ref 进链，实际链 = $r")
      assertEquals(r.head, "kimi/kimi-for-coding", s"显式链余下候选须原序保留，实际链 = $r")
  }

  test("T3: disabled 不影响未 disabled 的解析（零行为变化对照腿）") {
    val cfg = removalLikeConfig
    for
      reg <- mkRegistry(cfg)
      one <- reg.getCandidateForRef("zhipu/glm-5.3-flash")
    yield
      assert(one.isDefined, "未 disabled provider 的 ref 必须照常解析")
      assertEquals(one.map(_.providerId), Some("zhipu"))
  }

  test("T4(重写): 链深恒 1——disabled provider 不得进链，且链上不追加任何其它候选") {
    val cfg = NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map(
      "cmdcode" -> mkProvider(List("a1", "a2"), disabled = Some(true)),
      "kimi"    -> mkProvider(List("k1"))
    )))
    for
      reg <- mkRegistry(cfg)
      chain <- reg.getCandidatesForAgentUnfiltered(
        Some(AgentModelConfig(preferred = Some("kimi/k1"), fallbacks = Nil))
      )
    yield
      val r = refs(chain)
      assertEquals(
        r,
        List("kimi/k1"),
        s"disabled provider 的 model 不得进链；链深恒 1 不追加储备层，实际链 = $r"
      )
  }

  test("T5(重写): 全 provider disabled 时链构建不凭空造候选（fail-closed 不反转为编造）") {
    val cfg = NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map(
      "cmdcode" -> mkProvider(List("a1"), disabled = Some(true))
    )))
    for
      reg <- mkRegistry(cfg)
      chain <- reg.getCandidatesUnfiltered()
    yield assertEquals(refs(chain), Nil, "全 disabled 时链 = 空，不得编造候选")
  }
end DisabledProviderSelectionSpec
