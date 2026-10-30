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
 * tiering 选中。本批修复 = fail-closed：链 tiering 全路径（全局链腿 / seed 兜底 /
 * agent 显式 ref 腿 / 储备层）一律不可被选；未 disabled 的 provider 不受影响。
 *
 * 断言纪律：agent 链断言只经 `getCandidatesForAgent` / `getCandidateForRef`（真实
 * 调用链）；储备层直调 `reserveTier`（private[llm]，同包内合法）。配置全部在 spec
 * 内构造，不读/不写真实 HOME（不触发 `getCandidatesUnfiltered` 的默认 preset 面）。
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

  test("T1: disabled provider 不进储备层（fail-closed），未 disabled 的照常进链") {
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
      assert(r.contains("zhipu/glm-5.3-flash"), s"未 disabled 的储备层项必须照常进链，实际链 = $r")
      assertEquals(r.head, "kimi/kimi-for-coding", s"主链前缀不变，实际链 = $r")
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

  test("T4: reserveTier 直调——disabled provider 全部剔除，链上已有项原样保留") {
    val cfg = NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map(
      "cmdcode" -> mkProvider(List("a1", "a2"), disabled = Some(true)),
      "kimi"    -> mkProvider(List("k1"))
    )))
    val base = List(
      ModelCandidate("kimi", mkProvider(List("k1")), "k1")
    )
    for
      reg <- mkRegistry(cfg)
      chain = reg.reserveTier(cfg, base)
    yield
      val r = refs(chain)
      assertEquals(r, List("kimi/k1"), s"disabled provider 的 model 不得进储备层，实际链 = $r")
  }

  test("T5: 全 provider disabled 时 reserveTier 不凭空造候选（fail-closed 不反转为编造）") {
    val cfg = NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map(
      "cmdcode" -> mkProvider(List("a1"), disabled = Some(true))
    )))
    for
      reg <- mkRegistry(cfg)
      chain = reg.reserveTier(cfg, Nil)
    yield assertEquals(refs(chain), Nil, "全 disabled 时储备层 = 空，不得编造候选")
  }
end DisabledProviderSelectionSpec
