package nebflow.llm

/**
 * 上游不可用族（5xx）的**单点定义与处置**（5xx 归批 2026-11-05，作者令逐字：
 * 「修复一下 deepseek 500 的问题」）。
 *
 * ## 为什么需要这一族（事故形态）
 *
 * 冻结回执里出现过 `deepseek/deepseek-flash: ProviderError — statusCode: 550,
 * response:`（**响应体为空**）。550 不是标准 HTTP 码，`classifyBase` 的
 * `case _` 兜底把它归成 [[FailoverReason.ProviderError]]——语义上应归「上游暂时
 * 不可用」的 5xx 族。归类错了，处置也就跟着错：
 *
 *   1. `AgentActor.llmFailureRetryable` 只认 overload（429/529）/ 流间隙超时 ⇒
 *      5xx 链穷尽被判**不可重试**；
 *   2. `AgentProcessing` 的 `shouldErrorFreeze` 对 `FallbackExhaustedError` 复用
 *      同一判定 ⇒ **不进等待、直接 fatal**（delegate 侧就是
 *      `FROZEN (reason=llm-failure)` = 作者看到的「抖一下就冻死」）；
 *   3. 非 400 的失败 `evict = true` ⇒ 每次 5xx 都 `markDown` 进探测集 ⇒ 探测
 *      空历史「hi」秒成功 → 秒回 UP → 下一发再 5xx，DOWN/UP flap 风暴。
 *
 * ## 归类表（唯一真源；本族 = `server`）
 *
 * | 观测面 | 语义 | 族 | 重试 | 换 provider | 冻结/终态 |
 * |---|---|---|---|---|---|
 * | 500 / 502 / 503 / 504 / **550** | 上游暂时不可用（过载、网关/边缘抖动、内部错误，含响应体为空的非标准码） | `server`（[[FailoverReason.ServerError]]） | 是（同 provider 1 次，指数退避） | 是 | **否**——全部候选 5xx ⇒ 退避等待（FreezeReason.WaitingNetwork 同款），不是失败判词 |
 * | 401 / 403 | 认证/权限（含配额闸的 403 形态） | `auth` | 否 | 是 | 确证死亡：驱逐 + fatal（等不来） |
 * | 402 / 429+配额码（1308 计划额度、1310 周/月额度） | 额度耗尽 | `quota` | 否（同 provider 等无意义） | 是（立即换链） | 配额软回避窗，不 markDown |
 * | 429（无配额码） | 频率闸 | `quota`/`RateLimit` | 是（≥60s 窗口） | 是 | 否 |
 * | 400 | 请求形状 vs 契约 | `format` | 1 次就地降级 | 是 | 否（`evict=false`） |
 * | 404 | 模型不存在 | `model` | 否 | 是 | 确证死亡 |
 * | 529 | 过载（Anthropic 面） | `overload` | 是（≥60s 窗口） | 是 | 否 |
 * | 连接重置 / 不可达 / 超时 | 网络面 | `conn-reset` / `network` / `timeout` | 见 #692 判据 | 视族 | 不可达类 ⇒ 退避等待 |
 *
 * 判据方向：**状态码优先于响应体文本**。响应体为空不影响归类（550 实测即空体）；
 * 状态码缺失时才回落到文本形状（[[isUpstreamUnavailableMessage]]）。
 *
 * ## 处置参数（provider 级冷却 + 指数退避）
 *
 * 连续 5xx ⇒ `strikes += 1`，冷却窗 = `CooldownBaseMs * 2^(strikes-1)` 封顶
 * [[CooldownMaxMs]]：15s → 30s → 60s → 120s → 300s（strikes 封顶
 * [[CooldownMaxStrikes]]，此后恒为 300s）。冷却 = `HealthMonitor.softAvoid`
 * （**不进健康 Down 状态、不进探测集**，消除 flap 风暴）；窗口到期候选自然回到
 * 链上 = **半开试探**——试一发成功即 `markUp`/清零 strikes，失败则 strikes 继续
 * 递增。所有动作都落 WARN 日志（`soft-avoided for <ms>ms (upstream 5xx ...)`），
 * 面板 `/api/health` 的软回避面同样可见。
 *
 * 注：本对象只放「纯判据 + 参数」，不持状态（状态在
 * [[ProviderHealthMonitor.strikesRef]]），也不发请求——spec 可直接断言。
 */
object UpstreamUnavailable:

  /**
   * 归入「上游暂时不可用」的 HTTP 状态码。
   *
   * 500/502/503/504 = 标准服务端失败；**550 = 非标准码实测形态**（deepseek
   * anthropic 兼容网关，2026-11-05 冻结回执：`statusCode: 550, response:` 空体）。
   * 未观测到 deepseek 的 500：截至本批实测（同一 key、同一 URL、最小请求）deepseek
   * 返 200；历史日志（`~/.stand/logs/stand.log` 及轮转档）**只有 550、没有 500/502/
   * 503/504**——「500 与 550 是同一现象的两种码」这一点目前**无证据**，因此两个码
   * 都按同一族处理（语义相同：上游暂时不可用），而不假设它们必然同源。
   */
  val HttpStatuses: Set[Int] = Set(500, 502, 503, 504, 550)

  def isStatus(code: Int): Boolean = HttpStatuses.contains(code)

  /**
   * 文本形状兜底（无结构化状态码的残余路径）：`statusCode: 550` /
   * `HTTP 550` / `status 550` / `5xx`。**不认裸数字**——裸匹配会凭空制造 5xx 判定
   * （与 [[Fallback.carriesUpstreamCode]] 同纪律）。
   */
  def isUpstreamUnavailableMessage(message: String): Boolean =
    val low = Option(message).getOrElse("").toLowerCase
    val codes = HttpStatuses.toList.sorted.map(_.toString)
    codes.exists(c => low.contains(s"statuscode: $c") || low.contains(s"status code: $c") || low.contains(s"http $c"))

  /** 冷却窗基数（首个 5xx）。 */
  val CooldownBaseMs: Long = 15_000L

  /** 冷却窗上限（连续 5xx 后的最长回避）。 */
  val CooldownMaxMs: Long = 300_000L

  /** strikes 封顶：超过后不再增长（窗恒为 [[CooldownMaxMs]]）。 */
  val CooldownMaxStrikes: Int = 5

  /**
   * 第 `strikes` 次连续 5xx 对应的冷却窗（strikes ≥ 1）。
   * 15s → 30s → 60s → 120s → 300s(cap) → 300s(…)。
   */
  def cooldownMs(strikes: Int): Long =
    val n = math.max(1, math.min(strikes, CooldownMaxStrikes))
    math.min(CooldownBaseMs * (1L << (n - 1)), CooldownMaxMs)

end UpstreamUnavailable
