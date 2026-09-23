package nebflow.llm

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.shared.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.StreamBackend

/**
 * 腿 a（作者令 2026-09-21 19:16）——候选链深度补足：`nebflow.json` 中**已存在但
 * 三个 preset 均未引用**的 provider/model 必须进候选链，令「有效链深 1（单点）」
 * 在结构上不可达。
 *
 * 事故面（providerwipe-arch 定谳 §5.3 G5）：候选链恒为 3 条，且恰是 2026-09-21
 * 同日退化的三条（kimi/kimi-k3 16:47 撞 5h 额度、zhipu/GLM-5.3-Flash 17:54 撞
 * 429-1308 5h 使用上限、deepseek/deepseek-flash 18:47/18:59 传输层失联）⇒ 17:54
 * 起链上只剩 deepseek 单点 ⇒ 全灭闸阻塞（AllProvidersDownTimeout）。
 *
 * 断言纪律：本 spec 只经 `getCandidatesForAgent`（真实调用链）断言，不引用实现
 * 新增的内部符号；配置全部在 spec 内构造，不读/不写真实 HOME（`getCandidates()`
 * 的默认 preset 面会读写真实 HOME，故本 spec 不触发该分支）。
 */
class ProviderChainDepthSpec extends CatsEffectSuite:

  private val NullBackend = null.asInstanceOf[StreamBackend[IO, Fs2Streams[IO]]]

  private def mkConfig(spec: (String, List[String])*): NebflowServiceConfig =
    NebflowServiceConfig(llm = ServiceLlmConfig(providers = spec.map { case (pid, models) =>
      pid -> ProviderConfig(
        baseUrl = "http://127.0.0.1:1",
        apiKey = "k",
        protocol = LlmProtocol.Anthropic,
        models = models.map(m => ModelConfig(m, vision = Some(false)))
      )
    }.toMap))

  /** 现读真源镜像：`~/.nebflow/nebflow.json` 的 provider/model 面（`apiKey` 不取）。 */
  private def liveLikeConfig: NebflowServiceConfig =
    mkConfig(
      "USTC" -> List("glm-5.3-flash", "deepseek-flash"),
      "kimi" -> List("kimi-k3"),
      "deepseek" -> List("deepseek-v4-pro", "deepseek-flash"),
      "zhipu" -> List("GLM-5.3", "GLM-5.3-Flash")
    )

  /** 现读真源镜像：`model-presets.json` 的 `general` preset（三个 preset 同形）。 */
  private def generalChain: AgentModelConfig =
    AgentModelConfig(
      preferred = Some("kimi/kimi-k3"),
      fallbacks = List("zhipu/GLM-5.3-Flash", "deepseek/deepseek-flash")
    )

  private val presetRefs =
    List("kimi/kimi-k3", "zhipu/GLM-5.3-Flash", "deepseek/deepseek-flash")

  /** 三个 preset 均未引用、但 `nebflow.json` 已存在的三根（令面 §二 腿 a）。 */
  private val unreferencedRefs =
    List("USTC/glm-5.3-flash", "USTC/deepseek-flash", "deepseek/deepseek-v4-pro")

  private def mkRegistry(cfg: NebflowServiceConfig): IO[ProviderRegistry] =
    Ref.of[IO, NebflowServiceConfig](cfg).map(ref => new ProviderRegistry(ref, NullBackend))

  private def refs(cs: List[ModelCandidate]): List[String] =
    cs.map(c => s"${c.providerId}/${c.model}")

  test("T1: 未引用的三根 provider/model 进链 ⇒ 链深 ≥3（单点在结构上不可达）") {
    for
      reg <- mkRegistry(liveLikeConfig)
      cs <- reg.getCandidatesForAgent(Some(generalChain))
    yield
      val r = refs(cs)
      // 预设链前缀原样、不插队（REST「当前模型」显示取 healthy.head）
      assertEquals(r.take(3), presetRefs, s"预设链前缀必须原样保留，实际链 = $r")
      // 改前读数：链 = 恰 3 条 preset ⇒ 下面三条全部缺
      unreferencedRefs.foreach { u =>
        assert(r.contains(u), s"$u 必须进候选链（改前读数 = 链深 3 且缺此三条），实际链 = $r")
      }
      assert(r.size >= 3, s"链深必须 ≥3，实际 ${r.size}: $r")
      assertEquals(r.size, r.distinct.size, s"候选链不得出现重复项: $r")
  }

  test("T2: 储备层追加在链尾、且不重复链上已有项（含其它未引用 model）") {
    for
      reg <- mkRegistry(liveLikeConfig)
      cs <- reg.getCandidatesForAgent(Some(generalChain))
    yield
      val r = refs(cs)
      val tail = r.drop(presetRefs.size)
      presetRefs.foreach { p =>
        assert(!tail.contains(p), s"已引用的 $p 不得再进储备层尾部，实际尾部 = $tail")
      }
      // 同一「未被引用」规则也覆盖 zhipu/GLM-5.3（nebflow.json 已存在、三 preset 未引用）
      assert(tail.contains("zhipu/GLM-5.3"), s"未引用项 zhipu/GLM-5.3 应一并纳入储备层，实际尾部 = $tail")
  }

  test("T3: 配置实存 model 少于 3 根时链深不虚增（储备层只取配置实存项，禁编造）") {
    for
      reg <- mkRegistry(mkConfig("solo" -> List("m1")))
      cs <- reg.getCandidatesForAgent(Some(AgentModelConfig(preferred = Some("solo/m1"))))
    yield assertEquals(refs(cs), List("solo/m1"), "配置只有一根时链深 = 1，不得凭空造候选")
  }

  test("T4: 储备层顺序确定（providerId → model 字典序），同配置两次调用链序一致") {
    for
      reg <- mkRegistry(liveLikeConfig)
      a <- reg.getCandidatesForAgent(Some(generalChain))
      b <- reg.getCandidatesForAgent(Some(generalChain))
    yield
      val r = refs(a)
      assertEquals(refs(b), r, s"同配置下链序必须可复现（Map 迭代序不保证），实际 $r")
      val tail = r.drop(presetRefs.size)
      assertEquals(tail, tail.sorted, s"储备层须按 providerId → model 字典序，实际尾部 = $tail")
  }
end ProviderChainDepthSpec
