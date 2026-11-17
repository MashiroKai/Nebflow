package nebflow.core.delegate

/**
 * ProviderFault —— 「provider 侧可恢复故障」vs「真失败」的**判据表单点**
 * （引擎批 · provider 故障降噪，2026-11-05 17:18 作者令逐字：「我说了，provider
 * 问题，不需要向上通知，原地冻结等待恢复就行。」）。
 *
 * == 为什么要有这张表（不许写成散落的 if）==
 * 作者报障那一屏 `[delegate-frozen]` 通知里，七条以上全是 provider 侧原因：kimi 403
 * 无订阅、flysin 余额不足、cmdcode/deepseek-flash 临时不可用、cmdcode 报
 * `Input too long`。它们都不是「任务失败」，却被逐条当成失败推给了作者。要让这件事
 * 可测、可审、可回滚，判定必须是**一张表**——本件就是这张表的唯一真源：
 *
 * | 族 | 观测形状（举例） | 处置 |
 * |---|---|---|
 * | `model-unavailable` | 404 model_not_found / `is not available for this group` / 模型不存在（配置失效） | [[Verdict.ConfigInvalid]] |
 * | `auth-subscription` | 401 / 403 / no subscription / invalid api key / 鉴权 / 无订阅 | [[Verdict.Recoverable]] |
 * | `quota-balance` | 402 / 429+1308·1310 / insufficient_quota / 余额 / 欠费 / 额度 / billing | Recoverable |
 * | `upstream-5xx` | 500 / 502 / 503 / 504 / **550** / 529 / overloaded / server error | Recoverable |
 * | `connect-timeout` | ECONNRESET / econnrefused / broken pipe / socket hang up / no route / DNS / timeout | Recoverable |
 * | `format-rejected` | 400 / invalid request / bad request / **context_length_exceeded** / maximum context length / **input too long** / 413 | Recoverable |
 * | `rate-limited` | 429（无配额码）/ rate limit | Recoverable |
 * | `deterministic-defect` | AssertionError / NullPointerException / ToolPipeline / 校验和不符 / 数据损坏 | [[Verdict.RealFailure]] |
 *
 * 判据纪律（**不凭形状编造**，与 `Fallback.carriesUpstreamCode` / `UpstreamUnavailable`
 * 同一条裤子）：
 *   - 状态码只在**带前缀的形状**里认（`statusCode: 550` / `HTTP 550` / `code 403`），
 *     **不认裸数字**——request_id、耗时、token 数里出现 "500" 不构成 5xx 判定；
 *   - 确定性缺陷**优先**：同一段正文既有 provider 字样又有代码缺陷字样时，按真失败
 *     处置（宁可多报一条，也不许把 bug 静音）；
 *   - 词表只增「确证过语义」的形状，与 `FreezeNotice.infraFault` 共用本表（单一尺子）。
 *
 * == 与「呈现/通知」两面的关系（本件只做分类，不做处置）==
 *   - 呈现面单点仍 = `nebflow.actor.FreezeDisplay`（`autoRecoverable` / 面板措辞）；
 *   - 通知面单点仍 = [[FreezeNotice]]（`Tier.Silent` / `Tier.Escalate`）；
 *   - 本件只回答「这段失败正文属于哪一族、该族是不是可恢复」，喂给上面两面。
 */
object ProviderFault:

  /** 故障族（写进日志/审计行；词表是状态面的一部分）。 */
  enum Family:
    /**
     * 模型对该供应商/分组不存在（404 model_not_found / `not available for this group`）
     * ——**配置失效**，不是供应商挂了（model-404-freeze 批 2026-11-07）。
     *
     * 现场（`delegate-nebflow-5c905a1d`，2026-11-07 12:38:51）：钉住的
     * `Irical/gpt-6.1-terra` 在 Irical 分组根本不可见（该模型只在 IricalGpt 分组下），
     * 上游回 `404 Model "gpt-6.1-terra" is not available for this group`。旧判据因为
     * 正文里有 `upstream` / `provider` 字样把它算进 [[Family.Upstream5xx]]（= 可恢复）
     * ⇒ 冻结后无限自动重试，作者看到的就是「一直卡住没跑完」。
     */
    case ModelUnavailable
    /** 鉴权 / 订阅（401 / 403 / 无订阅 / 无效 key）——充值或换链即恢复。 */
    case AuthSubscription
    /** 配额 / 余额（402 / 429+额度码 / insufficient_quota / 余额不足）——等重置或充值。 */
    case QuotaBalance
    /** 上游 5xx（含实测非标准码 550 / 529 过载）——供应商此刻供不出服务。 */
    case Upstream5xx
    /** 连接失败 / 超时（重置、拒绝、DNS、无路由、超时）——等网络自己好。 */
    case ConnectTimeout
    /** 请求被上游按格式退回（400 / 上下文超长 / input too long）——等模型侧放宽或换链。 */
    case FormatRejected
    /** 频率闸（429 无配额码）——滑动窗自愈。 */
    case RateLimited
    /** 确定性缺陷（代码 bug / 越权 / 数据损坏 / 显式业务失败）——**真失败**。 */
    case DeterministicDefect

  /** 分类裁定：可恢复（原地冻结 + 静默 + 自动续跑）还是真失败（照常通知）。 */
  enum Verdict:
    /** provider 侧可恢复故障：零通知、原地冻结、退避重试至恢复。 */
    case Recoverable
    /**
     * **配置失效**（model-404-freeze 批 2026-11-07）：这句正文说的是「你指的模型在
     * 这个供应商/分组里不存在」，重试一百次也是同一个 404 —— 既不是 provider 挂了
     * （[[Recoverable]] 的无限自动重试会把会话永久钉死），也不是代码缺陷
     * （[[RealFailure]] 的判词会把责任推给 agent）。正确处置 = **fail-fast + 指名改
     * 配置**，并照常通知（人来换模型）。
     */
    case ConfigInvalid
    /** 真失败：照常通知作者（判据代码 bug / 越权 / 数据损坏 / 显式业务失败）。 */
    case RealFailure

  /** 线值（日志/审计行）。 */
  def familyWire(f: Family): String = f match
    case Family.ModelUnavailable => "model-unavailable"
    case Family.AuthSubscription => "auth-subscription"
    case Family.QuotaBalance => "quota-balance"
    case Family.Upstream5xx => "upstream-5xx"
    case Family.ConnectTimeout => "connect-timeout"
    case Family.FormatRejected => "format-rejected"
    case Family.RateLimited => "rate-limited"
    case Family.DeterministicDefect => "deterministic-defect"

  /**
   * 表行：族 + 裁定 + 观测形状 + 处置说明。`markers` 是小写包含匹配的正文形状；
   * `statusCodes` 只在「带前缀的形状」里认（见 [[statusCodePattern]]）。本表即
   * 「逐类给判定依据」的代码锚点（报告里的判据表逐行对应本表）。
   */
  final case class Row(
    family: Family,
    verdict: Verdict,
    markers: List[String],
    statusCodes: List[Int],
    disposition: String
  )

  /** Shared with the engine's failure text so its upstream recovery guidance cannot silence a real failure. */
  val EngineRecoveryFailureLead: String = "L3 hard-recovery resume failed"

  /** 判据表（唯一真源；顺序 = 判定优先级）。 */
  val Table: List[Row] = List(
    Row(
      Family.DeterministicDefect,
      Verdict.RealFailure,
      List(
        "assertionerror",
        "assertion failed",
        "nullpointerexception",
        "classcastexception",
        "indexoutofboundsexception",
        "nosuchmethoderror",
        "stackoverflow",
        "compilation failed",
        "compile error",
        "checksum mismatch",
        "data corruption",
        "corrupt",
        "toolpipeline",
        "tool pipeline",
        "explicit business failure",
        "business rule violation",
        "not allowed to write",
        "越权",
        "数据损坏",
        "业务失败",
        EngineRecoveryFailureLead.toLowerCase
      ),
      Nil,
      "确定性缺陷 ⇒ 真失败：照常通知（聚合后一条），不静默"
    ),
    Row(
      Family.ModelUnavailable,
      Verdict.ConfigInvalid,
      List(
        // 结构化分类产物（`[model]` 行由 FallbackExhaustedError 的族标签给出）
        "modelnotfound",
        "model_not_found",
        "model not found",
        // 上游原话（Irical/OpenAI 兼容面实测形状，2026-11-07 12:38:51）
        "is not available for this group",
        "available for this group",
        "no such model",
        "unknown model",
        "does not exist",
        "not a valid model",
        "invalid model",
        // 中文面
        "模型不存在",
        "模型不可用"
      ),
      List(404),
      "模型对该供应商/分组不存在（404 model_not_found）⇒ 配置失效：fail-fast + 指名改配置；" +
        "**不**归 provider-down、**不**自动重试、**不**驱逐 provider（它活着，只是没有这个模型）"
    ),
    Row(
      Family.QuotaBalance,
      Verdict.Recoverable,
      List(
        "insufficient_quota",
        "insufficient balance",
        "quota",
        "billing",
        "payment required",
        "balance",
        "budget exhausted",
        "usage limit",
        "usage_limit",
        "额度",
        "余额",
        "欠费",
        "使用上限"
      ),
      List(402),
      "配额/余额 ⇒ 可恢复：等重置或充值；静默冻结 + 自动续跑"
    ),
    Row(
      Family.AuthSubscription,
      Verdict.Recoverable,
      List(
        "no subscription",
        "subscription",
        "invalid api key",
        "invalid_api_key",
        "unauthorized",
        "permission_error",
        "authenticat",
        "无订阅",
        "鉴权"
      ),
      List(401, 403),
      "鉴权/订阅 ⇒ 可恢复：充值或换链；静默冻结 + 自动续跑"
    ),
    Row(
      Family.Upstream5xx,
      Verdict.Recoverable,
      List(
        "all candidates",
        "candidates exhausted",
        "no available",
        "overloaded",
        "server error",
        "bad gateway",
        "service unavailable",
        "gateway timeout",
        "upstream",
        "provider",
        "供应商",
        "模型层"
      ),
      List(500, 502, 503, 504, 529, 550),
      "上游 5xx（含非标准 550）⇒ 可恢复：provider 冷却 + 退避等待"
    ),
    Row(
      Family.ConnectTimeout,
      Verdict.Recoverable,
      List(
        "econnreset",
        "econnrefused",
        "connection reset",
        "connection refused",
        "broken pipe",
        "socket hang up",
        "no route to host",
        "network is unreachable",
        "network is down",
        "unknownhost",
        "dns",
        "timeout",
        "timed out",
        "network"
      ),
      Nil,
      "连接失败/超时 ⇒ 可恢复：等网络自己好，退避等待"
    ),
    Row(
      Family.FormatRejected,
      Verdict.Recoverable,
      List(
        "context_length_exceeded",
        "maximum context length",
        "reduce the length of the messages",
        "context length",
        "input too long",
        "prompt is too long",
        "too many tokens",
        "invalid request",
        "bad request"
      ),
      List(400, 413),
      "格式层拒绝（含 context 超长 / input too long）⇒ 可恢复：上游退回的是请求形状，不是任务失败"
    ),
    Row(
      Family.RateLimited,
      Verdict.Recoverable,
      List("rate limit", "ratelimit", "too many requests"),
      List(429),
      "频率闸 ⇒ 可恢复：60s 窗口自愈"
    )
  )

  /**
   * 状态码识别形状：`statusCode: 550` / `status 550` / `HTTP 550` / `code 403` /
   * `[550]`。**不认裸数字**（与 `Fallback.carriesUpstreamCode` 同纪律）。
   */
  private val statusCodePattern =
    """(?i)(?:status[_ ]?code|status|http|code|error)\s*[:=]?\s*\[?(\d{3})\]?""".r

  /** 正文里出现过的状态码（只认带前缀形状）。 */
  def statusCodesIn(detail: String): Set[Int] =
    val d = Option(detail).getOrElse("")
    statusCodePattern.findAllMatchIn(d).flatMap(m => m.group(1).toIntOption).toSet

  /** 该正文命中的表行（按表序，首个即判定；空 = 未命中任何已知族）。 */
  def matchRows(detail: String): List[Row] =
    val d = Option(detail).getOrElse("").toLowerCase
    if d.isEmpty then Nil
    else
      val codes = statusCodesIn(detail)
      Table.filter { row =>
        row.markers.exists(d.contains) || row.statusCodes.exists(codes.contains)
      }

  /** 命中的族（首个命中行；None = 未命中）。 */
  def familyOf(detail: String): Option[Family] = matchRows(detail).headOption.map(_.family)

  /** 命中行的裁定（未命中 = None）。 */
  def verdictOfDetail(detail: String): Option[Verdict] = matchRows(detail).headOption.map(_.verdict)

  /**
   * 这段失败正文是否属**provider 侧可恢复故障**（→ 静默 + 原地冻结 + 自动续跑）。
   * 未命中任何族 ⇒ false（宁可照常通知，也不许把未知形状静音）。
   */
  def isRecoverable(detail: String): Boolean = verdictOfDetail(detail).contains(Verdict.Recoverable)

  /** 这段失败正文是否属**确定性缺陷**（真失败 ⇒ 照常通知）。 */
  def isRealFailure(detail: String): Boolean = verdictOfDetail(detail).contains(Verdict.RealFailure)

  /**
   * 这段失败正文是否属**配置失效**（模型对该供应商/分组不存在 ⇒ 重试不会自己好）。
   *
   * 单点判据，两个消费者：
   *   - [[DelegateFreeze.classify]] ⇒ 归 `model-unavailable`（不进 provider 故障族 ⇒
   *     **不** autoRecoverable，「冻结后无限自动重试」正是在这里断掉的）；
   *   - `Fallback.classifyBase` 的驱逐面 ⇒ 404 不 markDown（provider 活着）。
   */
  def isConfigInvalid(detail: String): Boolean = verdictOfDetail(detail).contains(Verdict.ConfigInvalid)

  /**
   * 「这段失败该不该静默」的唯一入口（通知面 / flow-notify 闸共用）：
   * 命中可恢复族且不属确定性缺陷 ⇒ 静默。
   */
  def silent(detail: String): Boolean = isRecoverable(detail) && !isRealFailure(detail)

end ProviderFault
