package nebflow.llm

import munit.FunSuite
import nebflow.shared.{ModelConfig, ProviderConfig}

/**
 * model-404-freeze 批（2026-11-07）——**钉链失效回落**规则的判红锚（降级规则两侧）。
 *
 * 背景：钉链（[[PinnedChain]]）是 spawn 时刻的链快照，配置是热读的。现场实证
 * `delegate-nebflow-5c905a1d`：10:04 spawn 时链首 = `Irical/gpt-6.1-terra`，
 * 12:50 配置把该模型从 `Irical` 的模型清单移走（只剩在 `IricalGpt` 分组下）⇒ 钉链
 * 指着 `Irical/gpt-6.1-terra` 这个已不存在的 ref ⇒ 每个请求都吃
 * `404 model_not_found`，冻结后无限重试。
 *
 * 判据 = **该 ref 是否仍在当前配置里**（写死在代码里、可测，不隐式猜）：
 *   - 仍配置 ⇒ 保留钉链（快照语义不回归；「配了但不在链首」不算失效）；
 *   - 已不在配置（provider 删了 / disabled / 模型从该 provider 清单移除）⇒ 弃用钉链，
 *     回落现配置链首；连链都没有 ⇒ `to=None`（交给空链 fail-fast，不猜模型）。
 */
class PinStaleFallbackSpec extends FunSuite:

  private def pin(head: String): Option[PinnedChain] =
    Some(
      PinnedChain(
        headRef = head,
        orderedRefs = List("UTSC/glm-5.3-flash"),
        refs = List(head.takeWhile(_ != '/') -> head.dropWhile(_ != '/').drop(1), "UTSC" -> "glm-5.3-flash"),
        pinnedProviderId = head.takeWhile(_ != '/')
      )
    )

  private def provider(models: String*): ProviderConfig =
    ProviderConfig(
      baseUrl = "http://localhost:1/v1",
      apiKey = "k",
      protocol = nebflow.shared.LlmProtocol.OpenAI,
      models = models.toList.map(id => ModelConfig(id))
    )

  // ── 生效侧：钉链失效 ⇒ 降级到现配置链 ──────────────────────────────────

  test("生效侧：钉链首已不在当前配置里 ⇒ 弃用钉链，降级到现配置链首") {
    val v = ProviderConcurrency.pinVerdict(
      pin("Irical/gpt-6.1-terra"),
      headStillConfigured = false,
      currentChain = List("Irical/DeepSeek-V4.1-Flash-1017", "IricalGpt/gpt-6.1-sol")
    )
    assertEquals(
      v,
      ProviderConcurrency.PinVerdict.Drop(
        "Irical/gpt-6.1-terra",
        Some("Irical/DeepSeek-V4.1-Flash-1017")
      )
    )
  }

  test("生效侧的配置判据：模型从该 provider 的清单移走 / provider 删了 / disabled ⇒ 都算失效") {
    val moved: Map[String, ProviderConfig] =
      Map("Irical" -> provider("DeepSeek-V4.1-Flash-1017"), "IricalGpt" -> provider("gpt-6.1-sol", "gpt-6.1-terra"))
    assertEquals(
      ProviderConcurrency.refConfigured("Irical/gpt-6.1-terra", moved),
      false,
      "Irical 的清单里已经没有 terra（现网 12:50 之后的形态）"
    )
    assertEquals(ProviderConcurrency.refConfigured("IricalGpt/gpt-6.1-terra", moved), true)
    assertEquals(ProviderConcurrency.refConfigured("Nope/gpt-6.1-terra", moved), false, "provider 被删")
    val disabled = Map("IricalGpt" -> provider("gpt-6.1-terra").copy(disabled = Some(true)))
    assertEquals(ProviderConcurrency.refConfigured("IricalGpt/gpt-6.1-terra", disabled), false, "provider disabled")
    assertEquals(ProviderConcurrency.refConfigured("no-slash-ref", moved), false, "非法 ref 不得当有效")
  }

  // ── 不生效侧：钉链仍有效 ⇒ 不降级（快照语义不回归）──────────────────────

  test("不生效侧：钉链首仍在配置里 ⇒ 保留钉链（哪怕它不是现配置链首）") {
    val v = ProviderConcurrency.pinVerdict(
      pin("IricalGpt/gpt-6.1-terra"),
      headStillConfigured = true,
      currentChain = List("Irical/DeepSeek-V4.1-Flash-1017")
    )
    assertEquals(v, ProviderConcurrency.PinVerdict.Keep)
  }

  test("边界：无钉链 ⇒ Keep（零行为变化）；现配置链为空 ⇒ Drop 且 to=None（不许猜模型）") {
    assertEquals(
      ProviderConcurrency.pinVerdict(None, headStillConfigured = false, currentChain = List("a/b")),
      ProviderConcurrency.PinVerdict.Keep
    )
    assertEquals(
      ProviderConcurrency.pinVerdict(pin("Irical/gpt-6.1-terra"), headStillConfigured = false, currentChain = Nil),
      ProviderConcurrency.PinVerdict.Drop("Irical/gpt-6.1-terra", None)
    )
  }

end PinStaleFallbackSpec
