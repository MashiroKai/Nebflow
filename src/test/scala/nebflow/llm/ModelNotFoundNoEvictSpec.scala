package nebflow.llm

import munit.FunSuite
import nebflow.shared.FailoverReason

/**
 * model-404-freeze 批（2026-11-07）——**404 模型不存在不驱逐 provider** 的判红锚。
 *
 * 现场链条（stand.log 2026-11-07 12:38:51 → 之后每次请求）：
 * ```
 * Stream: Irical/gpt-6.1-terra permanent error (ModelNotFound) — statusCode: 404 ... model_not_found
 * health:  Provider Irical/gpt-6.1-terra still DOWN (fresh reason): HttpError: statusCode: 404 ...
 * 面板:    freeze: reason=provider-down autoRecoverable=true   ← 无限自动重试
 * ```
 * 旧 `evict` 判据只豁免 400-Format / 5xx，404 走 `evict=true` ⇒ markDown ⇒ 下一次请求
 * 落进全灭闸等待 120s ⇒ `AllProvidersDownTimeout` ⇒ `FreezeReason.ProviderDown`。
 * 真相是「我们指错了模型名」，provider 一直活着。
 *
 * 本 spec 钉的是**纯判据函数**（[[Fallback.evictOnStatus]]）：两侧都测——
 * 404 不驱逐（生效侧）+ 401/403/429/超时仍驱逐（不生效侧，防回归）。
 */
class ModelNotFoundNoEvictSpec extends FunSuite:

  test("404 model_not_found ⇒ 不驱逐（provider 活着，错的是我们指的模型）") {
    assertEquals(Fallback.evictOnStatus(404, FailoverReason.ModelNotFound, isContextOverflow = false), false)
  }

  test("另一侧不回归：401/403/429/未知仍驱逐（确证死亡 / 需换链）") {
    assertEquals(Fallback.evictOnStatus(401, FailoverReason.Auth, isContextOverflow = false), true)
    assertEquals(Fallback.evictOnStatus(403, FailoverReason.Auth, isContextOverflow = false), true)
    assertEquals(Fallback.evictOnStatus(429, FailoverReason.RateLimit, isContextOverflow = false), true)
    assertEquals(Fallback.evictOnStatus(500, FailoverReason.ServerError, isContextOverflow = false), false)
    assertEquals(Fallback.evictOnStatus(550, FailoverReason.ServerError, isContextOverflow = false), false)
    assertEquals(Fallback.evictOnStatus(400, FailoverReason.Format, isContextOverflow = false), false)
    assertEquals(Fallback.evictOnStatus(400, FailoverReason.Format, isContextOverflow = true), true)
  }

  test("reason 面独立成立：无状态码但已判 ModelNotFound ⇒ 仍不驱逐") {
    assertEquals(Fallback.evictOnStatus(0, FailoverReason.ModelNotFound, isContextOverflow = false), false)
  }

end ModelNotFoundNoEvictSpec
