package nebflow.shared

/**
 * 失败链文本（假 DOWN 批 2026-11-05，作者报障逐字：「但是我们的fallback逻辑，
 * 当cmdcode不可用，不是就是会连接到deepseek吗？」）。
 *
 * 病灶之一在**可诊断性**：现场健康表里存的原因是
 *
 * {{{
 * Exception when sending request: POST https://api.deepseek.com/anthropic/v1/messages
 * }}}
 *
 * 这是 sttp 对**任意**传输层失败的统一包装文案（connect 失败、握手被上游掐断、
 * 连接重置、读超时、响应体流中断……全都长这一句），真正的根因在 `getCause` 链里，
 * 而健康面历史上只取 `err.getMessage` ⇒ 表里那条原因既不可归类也不可复盘，
 * 作者面只能看到一句「发送请求时异常」。本对象把那条链做成**单点格式化**：
 *
 *   - 形态：`<Class>: <message> | <Class>: <message> | …`（最多 [[MaxDepth]] 层）；
 *   - null message 用类名兜底（否则整段信息消失——历史上正是这样丢的）；
 *   - 循环引用防护（cause 链自指）。
 *
 * 同一形态早已在 decision 腿自建（`DecisionHttpTransport.messageChainOf`），
 * 本批收口到此处，避免两份实现各自漂移。
 *
 * 🔴 凭据纪律：本对象**不**做抹除——它只看异常文本。把结果写进日志/健康表/
 * 通知面之前，调用方必须过 `LlmLogWriter.redactSecrets`（provider 可能把收到的
 * 凭据回显在自己的错误体里）。
 */
object FailureChain:

  /** 沿 cause 链最多下钻这么多层（sttp → JDK HttpClient → socket/SSL 通常 2-4 层）。 */
  final val MaxDepth: Int = 6

  /** 单条 message 的保留长度（防止上游回显超长响应体撑爆日志行）。 */
  final val MaxPartChars: Int = 400

  /**
   * 完整失败链（类名 + message，逐层 ` | ` 连接）。空链返回空串。
   *
   * 类名保留：`java.net.SocketException` / `javax.net.ssl.SSLHandshakeException`
   * 这类信息只在**类名**里，message 可能为 null 或只有一句人话。
   */
  def of(t: Throwable): String =
    if t == null then ""
    else
      val parts = scala.collection.mutable.ListBuffer.empty[String]
      var cur: Throwable = t
      var depth = 0
      while cur != null && depth < MaxDepth do
        val msg = Option(cur.getMessage).map(_.trim).filter(_.nonEmpty)
        val head = Option(cur.getClass.getSimpleName).filter(_.nonEmpty).getOrElse("Throwable")
        parts += msg.fold(head)(m => s"$head: ${m.take(MaxPartChars)}")
        val next = cur.getCause
        // 自指防护：cause 链打环时停手。
        cur = if next eq cur then null else next
        depth += 1
      parts.mkString(" | ")

  /**
   * 健康表/账本用的原因文本：**优先取失败链**，没有链时退回调用方的分类 message。
   *
   * 判据（为什么不直接 `message.orElse(getMessage)`）：包装层的一句
   * "Exception when sending request: POST …" 携带零诊断价值，而它恰好是
   * `getMessage` 的全部内容。只有当 cause 链存在（长度 > 1 层）时链才**比**
   * 分类 message 信息更多——否则两者同源，保留分类 message（它可能带 provider
   * 响应体，比异常包装更可读）。
   */
  def reasonFor(message: Option[String], t: Throwable): String =
    val chain = of(t)
    val parts = if chain.isEmpty then 0 else chain.count(_ == '|') + 1
    if parts >= 2 then chain
    else message.map(_.trim).filter(_.nonEmpty).getOrElse(if chain.isEmpty then "unknown failure" else chain)
end FailureChain
