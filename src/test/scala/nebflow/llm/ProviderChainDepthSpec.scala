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
        models = models.map(m => ModelConfig(m))
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

  test("T1: agent 显式链外的配置模型不得被 reserveTier 扩权") {
    for
      reg <- mkRegistry(liveLikeConfig)
      cs <- reg.getCandidatesForAgent(Some(generalChain))
    yield
      val r = refs(cs)
      // 预设链前缀原样、不插队（REST「当前模型」显示取 healthy.head）
      assertEquals(r.take(3), presetRefs, s"预设链前缀必须原样保留，实际链 = $r")
      // 只有 general preset 明确列出的三条属于该 agent；储备/provider inventory 不得扩权。
      unreferencedRefs.foreach { u =>
        assert(!r.contains(u), s"$u 未列入 agent allowlist，不得进候选链，实际链 = $r")
      }
      assertEquals(r, presetRefs, s"候选只能由明确链授权，实际 ${r.size}: $r")
      assertEquals(r.size, r.distinct.size, s"候选链不得出现重复项: $r")
  }

  test("T2: 未引用配置项不得追加到 agent 链尾") {
    for
      reg <- mkRegistry(liveLikeConfig)
      cs <- reg.getCandidatesForAgent(Some(generalChain))
    yield
      val r = refs(cs)
      val tail = r.drop(presetRefs.size)
      assertEquals(tail, Nil, s"未明确列入的模型不得作为储备链，实际尾部 = $tail")
  }

  test("T3: 配置实存 model 少于 3 根时链深不虚增（储备层只取配置实存项，禁编造）") {
    for
      reg <- mkRegistry(mkConfig("solo" -> List("m1")))
      cs <- reg.getCandidatesForAgent(Some(AgentModelConfig(preferred = Some("solo/m1"))))
    yield assertEquals(refs(cs), List("solo/m1"), "配置只有一根时链深 = 1，不得凭空造候选")
  }

  test("T4: agent 链顺序确定且不受 provider inventory 排序影响") {
    for
      reg <- mkRegistry(liveLikeConfig)
      a <- reg.getCandidatesForAgent(Some(generalChain))
      b <- reg.getCandidatesForAgent(Some(generalChain))
    yield
      val r = refs(a)
      assertEquals(refs(b), r, s"同配置下链序必须可复现（Map 迭代序不保证），实际 $r")
      assertEquals(r, presetRefs, s"provider inventory 不得更改已授权链序，实际 $r")
  }
end ProviderChainDepthSpec
