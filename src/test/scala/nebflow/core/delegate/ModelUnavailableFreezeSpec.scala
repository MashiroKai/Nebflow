package nebflow.core.delegate

import munit.FunSuite
import nebflow.actor.FreezeDisplay

/**
 * model-404-freeze 批（2026-11-07）——「模型名指错」≠「供应商挂了」的判红锚。
 *
 * 现场（`delegate-nebflow-5c905a1d`，stand.log 2026-11-07 12:38:51）：
 * ```
 * Stream: Irical/gpt-6.1-terra permanent error (ModelNotFound) — provider response:
 *   statusCode: 404, ... response=error/invalid_request_error(code=model_not_found) |
 *   upstream=Model "gpt-6.1-terra" is not available for this group
 * 冻结：lastError=[frozen:llm-failure] All providers failed (2 attempt(s); families: model):
 *   [model] Irical/gpt-6.1-terra: ModelNotFound — statusCode: 404, ...
 * 面板：freeze: reason=provider-down autoRecoverable=true
 * ```
 * 旧判据把这段正文里的 `upstream` / `provider` 字样当成上游 5xx 族（可恢复）⇒ 冻结后
 * 无限自动重试一个**供应商侧根本不存在的模型**，作者看到的就是「一直卡住没跑完」。
 *
 * 本 spec 钉死三件事：
 *  1. 404 model_not_found 归**配置失效族**（`ProviderFault.Family.ModelUnavailable` /
 *     `DelegateFreeze.Reason.ModelUnavailable`），不是 provider-down 族；
 *  2. 呈现面：该原因码 `autoRecoverable=false`（**不**承诺自动续跑）+ 面板直说改配置；
 *  3. 另一侧不回归：临时 5xx / 超时仍归 provider 故障族（`llm-failure`，可自动恢复，
 *     静默）。
 */
class ModelUnavailableFreezeSpec extends FunSuite:

  /** 现场原文（12:38:51；截断处保留判据形状）。 */
  private val live404 =
    """All providers failed (2 attempt(s); families: model):
      |  [model] Irical/gpt-6.1-terra: ModelNotFound — statusCode: 404, response: [wire] HTTP 404
      |  provider=Irical model=gpt-6.1-terra | request=u:t,a:tuu,u:rr,a:kuu | response=error/invalid_request_error(code=model_not_found)
      |  | upstream=Model "gpt-6.1-terra" is not available for this group
      |  | raw={"error":{"code":"model_not_found","message":"Model \"gpt-6.1-terra\" is not available for this group","type":"invalid_request_error"}}""".stripMargin

  private val live5xx =
    """All providers failed (1 attempt(s); families: server):
      |  [server] Irical/DeepSeek-V4.1-Flash-1017: ServerError — statusCode: 503, response: upstream=service unavailable""".stripMargin

  private val liveTimeout =
    """All providers failed (1 attempt(s); families: timeout):
      |  [timeout] UTSC/glm-5.3-flash: Timeout — statusCode: none, response: connect timed out""".stripMargin

  // ── ① 分类面 ───────────────────────────────────────────────────────────

  test("404 model_not_found ⇒ 配置失效族（model-unavailable），不再是上游 5xx 族") {
    assertEquals(ProviderFault.familyOf(live404), Some(ProviderFault.Family.ModelUnavailable))
    assertEquals(ProviderFault.verdictOfDetail(live404), Some(ProviderFault.Verdict.ConfigInvalid))
    assert(ProviderFault.isConfigInvalid(live404), "404 必须判为配置失效")
    assert(!ProviderFault.isRecoverable(live404), "配置失效不得被当成可恢复的 provider 故障")
    assert(!ProviderFault.isRealFailure(live404), "配置失效不是 agent 的失败")
    assert(!ProviderFault.silent(live404), "配置失效需要人改配置 ⇒ 不许静默")
  }

  test("另一侧不回归：临时 5xx / 超时仍是可恢复的 provider 故障族（静默 + 自动续跑）") {
    assertEquals(ProviderFault.familyOf(live5xx), Some(ProviderFault.Family.Upstream5xx))
    assert(ProviderFault.isRecoverable(live5xx), "5xx 必须仍判可恢复")
    assert(ProviderFault.silent(live5xx), "5xx 必须仍静默")
    assert(!ProviderFault.isConfigInvalid(live5xx), "5xx 不得被误判成配置失效")
    assert(ProviderFault.isRecoverable(liveTimeout), "超时必须仍判可恢复")
    assert(!ProviderFault.isConfigInvalid(liveTimeout))
  }

  test("判据边界：裸数字不构成 404（与 carriesUpstreamCode 同纪律）") {
    val noCode = "[model] x/y: ModelNotFound — request id 123404 tokens 4040" // 无 statusCode 前缀
    // 仍靠标记命中（modelnotfound），不是靠裸 404 数字
    assertEquals(ProviderFault.familyOf(noCode), Some(ProviderFault.Family.ModelUnavailable))
    val bareOnly = "provider failed after 404 retries without a labelled status" // 无标记、无前缀码
    assertNotEquals(ProviderFault.familyOf(bareOnly), Some(ProviderFault.Family.ModelUnavailable))
  }

  // ── ② 冻结原因码 ───────────────────────────────────────────────────────

  test("DelegateFreeze.classify: 404 ⇒ model-unavailable；5xx/超时 ⇒ llm-failure（可自动恢复族）") {
    assertEquals(DelegateFreeze.classify("LlmFailed", live404), DelegateFreeze.Reason.ModelUnavailable)
    assertEquals(DelegateFreeze.classify("LlmFailed", live5xx), DelegateFreeze.Reason.LlmFailure)
    assertEquals(DelegateFreeze.classify("LlmFailed", liveTimeout), DelegateFreeze.Reason.LlmFailure)
  }

  test("线值 round-trip：delegate-frozen:model-unavailable 可读回，且不属 provider 故障族") {
    val line = DelegateFreeze.wireValue(DelegateFreeze.Reason.ModelUnavailable)
    assertEquals(line, "delegate-frozen:model-unavailable")
    assertEquals(DelegateFreeze.parse(line), Some(DelegateFreeze.Reason.ModelUnavailable))
    assert(DelegateFreeze.isFreezeWire(line))
    assert(!DelegateFreeze.isProviderRecoverable(DelegateFreeze.Reason.ModelUnavailable))
    assert(DelegateFreeze.isProviderRecoverable(DelegateFreeze.Reason.LlmFailure))
  }

  // ── ③ 呈现面（面板措辞 + 是否承诺自动恢复）─────────────────────────────

  test("呈现面：model-unavailable = 需人改配置、autoRecoverable=false（不许说「自动重试中」）") {
    val fd = FreezeDisplay.ofWire("delegate-frozen:model-unavailable")
    assertEquals(fd.needsHuman, true)
    assertEquals(fd.autoRecoverable, false)
    assert(fd.labelZh.contains("模型不可用"), s"面板必须点名模型不可用：${fd.labelZh}")
    assert(fd.hintZh.contains("改配置"), s"必须指出动作是改配置：${fd.hintZh}")
    assert(!fd.hintZh.contains("自动续跑"), s"配置失效不许承诺自动续跑：${fd.hintZh}")
    assert(!FreezeDisplay.isInfraReasonCode("model-unavailable"))
  }

  test("呈现面另一侧不回归：llm-failure 仍 = provider 故障族（可自动恢复 + 通知静默）") {
    val fd = FreezeDisplay.ofWire("delegate-frozen:llm-failure")
    assertEquals(fd.autoRecoverable, true)
    assert(FreezeDisplay.isInfraReasonCode("llm-failure"))
    // 配置失效族与 provider 故障族互斥（同一码不许同时出现在两表）
    assert(FreezeDisplay.InfraReasonCodes.intersect(FreezeDisplay.ConfigInvalidReasonCodes).isEmpty)
  }

  test("通知面：配置失效族不静默（一条聚合告警到人），provider 故障族仍静默") {
    assertEquals(
      FreezeNotice.tierOfReason(DelegateFreeze.Reason.ModelUnavailable),
      FreezeNotice.Tier.Escalate
    )
    assertEquals(FreezeNotice.tierOfReason(DelegateFreeze.Reason.LlmFailure), FreezeNotice.Tier.Silent)
  }

end ModelUnavailableFreezeSpec
