package nebflow.llm

import munit.CatsEffectSuite
import nebflow.shared.*
import sttp.client4.HttpError
import sttp.model.StatusCode

/**
 * 腿 b 的分类面（作者令 2026-09-21 19:16）——**配额类与瞬时类分层**。
 *
 * 语料逐字取自现读真源 `~/.nebflow/logs/nebflow.log`（2026-09-21，无凭据面）：
 *   · kimi/kimi-k3 403（16:47:33.722）：`permission_error` + `5-hour usage limit`
 *   · zhipu/GLM-5.3-Flash 429 code **1308**（17:54:58，当日最后一条 zhipu 事件）：
 *     「已达到 5 小时的使用上限」——**计划性额度耗尽**
 *   · zhipu 429 code **1302**（16:58–17:54 共 16 条）：「已达到速率限制，请您控制
 *     请求频率」——**同族码，现读判读为频率类**（60s 窗口自愈）⇒ **不并入**配额
 *     分层，保持既有 overload 退避语义（本 spec T3 = 反向臂）
 *
 * 分层判据要点：配额类是**计划性**（额度耗尽，短窗内不会自愈）⇒ 换链（把该
 * provider 退出本轮候选）优于阻塞等待；瞬时/频率类是**自愈性** ⇒ 等待/探测才对。
 *
 * T1/T2/T3/T4 不引用实现新增符号（可在改前基线码上编译并判红 = 红腿真红）；
 * T5 断言配额标志面本身，随实现同支提交。
 */
class QuotaClassificationSpec extends CatsEffectSuite:

  // ---- 现读语料（原文照录，request_id 已截尾）----
  private val Kimi403QuotaBody =
    """{"error":{"type":"permission_error","message":"You've reached your 5-hour usage limit. Your quota will reset when the current 5-hour window ends. To continue now, purchase extra usage or upgrade your plan."}}"""

  private val Zhipu429Quota1308Body =
    """{"type":"error","error":{"type":"rate_limit_error","code":"1308","message":"[1308][已达到 5 小时的使用上限。您的限额将在 2026-09-21 19:46:31 重置。]"},"request_id":"20260921175458ae12"}"""

  private val Zhipu429Freq1302Body =
    """{"type":"error","error":{"type":"rate_limit_error","code":"1302","message":"[1302][您的账户已达到速率限制，请您控制请求频率][20260921165837f29e2852adcb4c37]"},"request_id":"20260921165837f29e2852adcb4c37"}"""

  private def http(body: String, code: Int) = HttpError(body, StatusCode(code))

  // ============================================================
  // 配额类：计划性 ⇒ 不做同 provider 的退避重试，直接换链
  // ============================================================

  test("T1: 429 + 上游 code 1308（配额类）⇒ 不可自愈（Permanent ⇒ 立即换链，不睡退避）") {
    val c = Fallback.classifyError(http(Zhipu429Quota1308Body, 429))
    assertEquals(c.statusCode, Some(429))
    assertEquals(c.evict, true)
    // 改前读数 = Transient ⇒ 走 interface.scala 的 Transient 分支：睡
    // OverloadBackoffMinMs(60_000) 后重试同一 provider（阻塞式等待），再换链。
    assertEquals(
      c.permanence,
      ErrorPermanence.Permanent,
      "配额类 = 计划性额度耗尽，短窗内不会自愈 ⇒ 必须判为不可自愈（直接换链），不得同 provider 退避重试"
    )
    // reason 面不变（零新枚举，UI/attempt 面零改）：429 仍读作 RateLimit
    assertEquals(c.reason, FailoverReason.RateLimit)
  }

  test("T2: 403（LLM 面唯一实证形态 = 5h 额度闸）⇒ 确证下线但归入配额分层") {
    val c = Fallback.classifyError(http(Kimi403QuotaBody, 403))
    assertEquals(c.statusCode, Some(403))
    assertEquals(c.reason, FailoverReason.Auth, "reason 面保持 Auth（401/403 同族，零枚举变更）")
    assertEquals(c.permanence, ErrorPermanence.Permanent)
    assertEquals(c.evict, true, "配额类必须退出本轮候选（驱逐）")
  }

  // ============================================================
  // 反向臂：瞬时/频率类保持现形态（分层不得吞掉等待路径）
  // ============================================================

  test("T3（反向臂）: 频率类 429 code 1302 保持 Transient ⇒ 仍走 >=60s 退避等待") {
    val c = Fallback.classifyError(http(Zhipu429Freq1302Body, 429))
    assertEquals(c.statusCode, Some(429))
    assertEquals(c.reason, FailoverReason.RateLimit)
    assertEquals(c.permanence, ErrorPermanence.Transient, "频率类 = 60s 窗口自愈 ⇒ 等待才是对的")
    assert(
      Fallback.retryDelayMs(Fallback.InitialBackoffMs, c.reason, 500) >= Fallback.OverloadBackoffMinMs,
      "频率类仍须受 overload 退避下界约束（等待路径逐条不变）"
    )
  }

  test("T4（反向臂）: 超时 / 连接类保持现形态（软回避 + 探测路径不变）") {
    val firstToken = Fallback.classifyError(new java.util.concurrent.TimeoutException("timeout"))
    assertEquals(firstToken.reason, FailoverReason.Timeout)

    val reset = Fallback.classifyError(new java.io.IOException("Connection reset by peer"))
    assertEquals(reset.reason, FailoverReason.ConnectionReset)
    assertEquals(reset.permanence, ErrorPermanence.Transient)

    val connectTimeout = Fallback.classifyError(new RuntimeException("connect timed out"))
    assertEquals(connectTimeout.reason, FailoverReason.Timeout)

    val auth401 = Fallback.classifyError(http("unauthorized", 401))
    assertEquals(auth401.reason, FailoverReason.Auth)
    assertEquals(
      auth401.permanence,
      ErrorPermanence.Permanent
    )

    // 400 Format 不驱逐（审计 20260903 子项②）——分层不得回退该分流
    val format400 = Fallback.classifyError(http("invalid request", 400))
    assertEquals(format400.reason, FailoverReason.Format)
    assertEquals(format400.evict, false)
  }

  // ============================================================
  // 配额标志面（T5 引用实现新增字段，故随实现同支提交）
  // ============================================================

  test("T5: 配额标志面——403 / 429-1308 = true；频率类 / 超时 / 连接 / 401 / 400 = false") {
    val q403 = Fallback.classifyError(http(Kimi403QuotaBody, 403))
    assertEquals(q403.quota, true)
    assertEquals(q403.permanence, ErrorPermanence.Permanent)

    val q429 = Fallback.classifyError(http(Zhipu429Quota1308Body, 429))
    assertEquals(q429.quota, true)
    assertEquals(q429.permanence, ErrorPermanence.Permanent, "配额类 = 不可自愈 ⇒ 不做同 provider 退避重试")

    val freq429 = Fallback.classifyError(http(Zhipu429Freq1302Body, 429))
    assertEquals(freq429.quota, false, "同族码 1302 = 频率类，不得并入配额分层")

    // 裸数字不进判据（request_id 等十六进制串可能偶然含 1308）
    val decoy = Fallback.classifyError(new RuntimeException("""{"request_id":"a1308b"}"""))
    assertEquals(decoy.quota, false, "无 `\"code\":\"1308\"` 形状的裸数字不得判为配额")

    val quotaShapeDecoy = Fallback.classifyError(http("""{"code":"1308x"}""", 429))
    assertEquals(quotaShapeDecoy.quota, false, "码必须整段匹配（1308x 不是 1308）")

    List(
      Fallback.classifyError(new java.util.concurrent.TimeoutException("timeout")),
      Fallback.classifyError(new java.io.IOException("Connection reset by peer")),
      Fallback.classifyError(http("unauthorized", 401)),
      Fallback.classifyError(http("model gone", 404)),
      Fallback.classifyError(http("invalid request", 400)),
      Fallback.classifyError(new AllProvidersDownTimeout(120000L))
    ).foreach(c => assertEquals(c.quota, false, s"非配额类必须保持 quota=false（reason=${c.reason}）"))
  }
end QuotaClassificationSpec
