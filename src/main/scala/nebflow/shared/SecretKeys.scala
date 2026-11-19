package nebflow.shared

import java.util.regex.Pattern

/**
 * 凭据键 / 凭据文本的**单一真源**（2026-11-04 两小件批 · 件一）。
 *
 * ## 缺陷（不是「顺手重构」）
 * 「哪些键算凭据」这件事在仓里曾有**四份各自的键表**：
 *
 * | 面 | 旧实现 | 旧键表 |
 * |---|---|---|
 * | 日志 / provider 回显文本 | `core.LlmLogWriter.redactSecrets` | `bearer` / `authorization` / `api-key` / `x-api-key` / `sk-` |
 * | 隧道失败体 | `neblink.RelayTunnelDiagnostics.redact` | `token` / `sessiontoken` / `devicetoken` / `refresh_token` / `access_token` + JWT |
 * | CLI 配置整树回显 | `cli.ConfigCommand.isSecretKey` | `apikey` / `secret` / `password` / `passwd` / `credential` + 单数 `token` 首尾 |
 * | MCP 卡面摘要 | `core.McpToolGate.GateRedact` | 子串 `password` / `token` / `secret` / `credential` / `key` |
 *
 * 四份的交集之外就是**缺口面**：`deviceToken` 全族在文本腿里一个都不命中。头像批
 * 读 `~/.stand/neblink/config.json` 做诊断时，正是文本腿把该键的**值原样回显**进了
 * 会话（未落盘、未进提交，已 grep 核验）。多处各写一份键表 = 这类缺口的根因；
 * 本对象是唯一真源，四条腿全部改为委托（`SecretMaskingCoverageSpec` 等四个 spec
 * 逐腿钉「八个键全被掩码」，任何一条腿再自立键表就红）。
 *
 * ## 键表口径
 *  - **词根匹配**（归一后 contains，大小写 / 下划线 / 短横线 / 点号全部同效）：
 *    `deviceToken` / `accessToken` / `refreshToken` / `idToken` / `sessionToken` /
 *    `authToken` / `token` / `clientSecret` / `apiKey` / `password` / `passwd` /
 *    `secret` / `credential` / `authorization`；
 *  - **配额豁免**（[[isQuota]]）：复数 `tokens` + 计/限类词根（`maxTokens` /
 *    `budgetTokens` / `tokensUsed`…）是**数值配额不是凭据** —— 掩码它们会把非秘密
 *    数据改坏，这是 `ConfigCommand` 在 D6 批已经立过的口径，现在升格成全域口径；
 *  - **文本腿**（[[redactText]]）按「赋值语境」掩码：`key: value` / `key=value` /
 *    `"key": "value"` 三种形态、键名过 [[isSensitive]] 才动，其余字符**逐字不动**
 *    （这是「不误伤」的判据面：普通日志 diff = 0）。
 */
object SecretKeys:

  /**
   * 敏感词根（归一口径：小写 + 仅字母数字）。表中任一元素被归一后的键名
   * **包含**即视为凭据。新增词根只改这一处 —— 四个消费点自动跟上。
   */
  val Roots: List[String] = List(
    "devicetoken",
    "accesstoken",
    "refreshtoken",
    "idtoken",
    "sessiontoken",
    "authtoken",
    "token",
    "clientsecret",
    "apikey",
    "password",
    "passwd",
    "secret",
    "credential",
    "authorization"
  )

  /**
   * 配额类判定：键名含复数 `tokens` **且**含计/限类词根 ⇒ 数值配额，不是凭据。
   * 单数 `token` 永不豁免（`token` / `accessToken` / `deviceToken` 都是凭据位）。
   */
  private val QuotaRoots: List[String] = List(
    "max",
    "min",
    "budget",
    "count",
    "used",
    "usage",
    "estimate",
    "limit",
    "context",
    "prompt",
    "completion",
    "output",
    "input",
    "total",
    "remaining"
  )

  /** 归一：小写 + 去掉一切非字母数字（`device_token` / `device-token` / `DeviceToken` 同归一）。 */
  def normalize(key: String): String =
    if key == null then "" else key.toLowerCase.filter(_.isLetterOrDigit)

  /** 配额类键（非凭据）。 */
  def isQuota(key: String): Boolean =
    val k = normalize(key)
    k.contains("tokens") && QuotaRoots.exists(k.contains)

  /** 该键名是否**凭据位**（唯一判据：四条腿全走这里）。 */
  def isSensitive(key: String): Boolean =
    val k = normalize(key)
    k.nonEmpty && !isQuota(k) && Roots.exists(k.contains)

  // ── 文本腿 ────────────────────────────────────────────────────────────

  /** `Bearer <value>`（保留 `Bearer` 一词，值换标记；无长度下限 —— 短令牌同样是令牌）。 */
  private val BearerPattern = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/-]+=*")

  /** JWT（三段 base64url；`eyJ` 是 `{"` 的 base64 头，形状唯一）。 */
  private val JwtPattern =
    Pattern.compile("eyJ[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}(\\.[A-Za-z0-9_-]+)?")

  /** 已知密钥前缀（OpenAI `sk-`）。 */
  private val SkPattern = Pattern.compile("sk-[A-Za-z0-9_\\-]{12,}")

  /**
   * 赋值语境：`<key><sep><value>`，其中
   *  · key = `[A-Za-z0-9_-]+`，**前面必须是非标识符边界**（`(?<![A-Za-z0-9_.-])`）——
   *    这条负向回顾是「不误伤」的关键：logger 名 `nebflow.neblink.credential:` 里
   *    `credential` 前面是点号 ⇒ 不算键名（否则一整行日志被掩码，`SecretMaskingCoverageSpec`
   *    的 diff=0 断言会红）。代价如实记：**点分限定名里的键不参与文本掩码**（点分名是
   *    路径/logger 名，不是赋值位）；JSON 结构的掩码走 [[isSensitive]] 的 JSON 面
   *    （`ConfigCommand.redact` / `GateRedact.redact`），不受此限；
   *  · sep = 可选的引号 + `:` 或 `=` + 空白 + 可选引号（JSON / YAML / env 同吃）；
   *  · value = 直到引号 / 空白 / `,` / `;` / `}` 为止（不吞后续结构）。
   */
  private val AssignmentPattern =
    Pattern.compile("""(?<![A-Za-z0-9_.\-])([A-Za-z0-9_-]+)(["']?\s*[:=]\s*["']?)([^"'\s,;}]+)""")

  private val Mask = "<redacted>"

  /**
   * 文本腿：把凭据形态抹成标记。**幂等**（掩码产物不会再被掩码）。
   *
   * 顺序有讲究（`LlmHttpErrorLogSpec` T4 钉死）：`Bearer` 腿**先**跑 —— 否则
   * `Authorization: Bearer <jwt>` 里 label 腿只吃掉 `Bearer` 一词，token 本体反而
   * 活下来（`Authorization: <redacted> <jwt>`），Bearer 腿再找不到前缀 ⇒ 静默泄露。
   */
  def redactText(s: String): String =
    if s == null || s.isEmpty then s
    else
      val bearers = BearerPattern.matcher(s).replaceAll("Bearer " + Mask)
      val assigned = replaceAssignments(bearers)
      val sk = SkPattern.matcher(assigned).replaceAll("sk-" + Mask)
      JwtPattern.matcher(sk).replaceAll("<redacted-jwt>")

  /** 逐匹配回调：只有键名过 [[isSensitive]] 才换值，否则**原地不动**（unmodified 判据）。 */
  private def replaceAssignments(s: String): String =
    val m = AssignmentPattern.matcher(s)
    // java.lang.StringBuilder（**不是** scala 的）：只有它才有 append(CharSequence, Int, Int)。
    val sb = new java.lang.StringBuilder(s.length)
    var last = 0
    while m.find() do
      sb.append(s, last, m.start())
      val key = m.group(1)
      if isSensitive(key) then sb.append(key).append(m.group(2)).append(Mask)
      else sb.append(m.group(0))
      last = m.end()
    sb.append(s, last, s.length)
    sb.toString

end SecretKeys
