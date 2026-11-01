package nebflow.llm.providers

import sttp.client4.Request

/**
 * 协议面唯一 HTTP 头装配点（protocol-compat 批，2026-11-01，作者实测令）。
 *
 * ## 为什么必须是"唯一一点"
 *
 * cmdcode 事故的**真根因不是 key、不是协议不兼容、也不是模型名**——是请求
 * **没带 User-Agent**，被 Cloudflare 边缘挡成 `HTTP 403 error code 1010`。
 * 引擎把 403 归类 Auth/Permanent ⇒ 驱逐 provider ⇒ 归档注释写「厂家重置 key 后
 * 启用」。实测（同一 key、同一 URL、同一 body）：无 UA → 403/1010，换任意诚实
 * UA → 200。也就是说：
 *
 *   - 无 UA 的客户端在 Cloudflare / Akamai / 各家 WAF 前**根本不进入业务层**；
 *   - 这类失败长得像"鉴权失败"，极易被误判成 key 死（本事故的完整误判链）；
 *   - 每个 adapter 各自埋头 `header(...)` ⇒ 总有一条路径漏掉 UA，故障面按
 *     provider 分布、难以根除。
 *
 * ## 纪律
 *
 * 1. 引擎缺省头里**必含** User-Agent（诚实、可识别、带产品版本语义）；
 * 2. provider 配置（`ProviderConfig.headers`）可追加/覆盖**任意头**——很多
 *    OpenAI/Anthropic 兼容网关还要求 `accept`、自定义鉴权头等；
 * 3. 两个协议适配器都只经本对象装配，不许私自 `.header(...)`。
 *
 * ## 键归一
 *
 * 头名大小写不敏感（HTTP 语义），装配时统一按小写比较去重：provider 配置的
 * `"User-Agent"` 能覆盖引擎缺省的 `user-agent`，不会出现两个同名头。
 */
object HttpHeaders:

  /**
   * 引擎缺省 User-Agent。
   *
   * 故意保持"诚实"：产品名 + 语义版本 + 客户端角色。**不要**伪装成浏览器
   * （那是绕过 WAF 的策略性选择，不该由引擎替运维决定；需要时用 provider
   * 级 headers 覆盖）。
   */
  def defaultUserAgent: String = s"stand/${VersionString.value} (nebflow-llm-client)"

  /** 引擎缺省头（provider 配置之前的基座）。 */
  def defaults: List[(String, String)] =
    List("user-agent" -> defaultUserAgent, "accept" -> "application/json")

  /**
   * 合并引擎缺省 + provider 覆盖。返回**已归一化**的头列表（全小写键、
   * 保留 provider 的大小写写法作展示值不影响语义）。
   */
  def merge(providerHeaders: Option[Map[String, String]]): List[(String, String)] =
    val overrides = providerHeaders.getOrElse(Map.empty).toList.filter { case (k, v) =>
      k != null && k.trim.nonEmpty && v != null
    }
    val overrideKeys = overrides.map { case (k, _) => k.trim.toLowerCase }.toSet
    val base = defaults.filterNot { case (k, _) => overrideKeys.contains(k) }
    base ++ overrides.map { case (k, v) => (k.trim, v) }

  /** 把装配好的头贴到请求上（唯一的落地点）。 */
  def applyTo[B](req: Request[B], providerHeaders: Option[Map[String, String]]): Request[B] =
    merge(providerHeaders).foldLeft(req)((r, kv) => r.header(kv._1, kv._2, replaceExisting = true))

  /**
   * WAF/边缘拦截判据（诊断用）：403/429 且响应体带云厂商边缘签名。
   *
   * 命中时诊断串必须明说"这不是 key 问题，是请求头/边缘策略问题"——否则下一
   * 个人还会把它读成 key 失效，重演本次事故的误判。
   */
  def isEdgeBlock(status: Int, body: String): Boolean =
    if status != 403 && status != 429 then false
    else
      // 压掉冒号/空白：Cloudflare 的两种写法（"error code: 1010" / "error code 1010"）
      // 都要中，别让一个冒号决定诊断成不成立。
      val low = Option(body).getOrElse("").toLowerCase.replaceAll("[\\s:]+", " ")
      low.contains("error code 1010") || // Cloudflare browser-signature ban
      low.contains("cf-ray") ||
      low.contains("just a moment") ||
      low.contains("cloudflare") ||
      low.contains("attention required")

  /** 边缘拦截的人话诊断（面板可见）。 */
  def edgeBlockHint(status: Int, body: String): String =
    if !isEdgeBlock(status, body) then ""
    else
      s" | EDGE-BLOCK: HTTP $status from an edge/WAF (Cloudflare error 1010 signature ban or similar) — " +
        "this is a REQUEST-HEADER problem, not a dead credential; set the provider's `headers` " +
        "(at least user-agent) in nebflow.json. Measured: same key+URL+body returns 200 with an honest UA."

/** 版本串（避免在 providers 包里引 core；缺省回落）。 */
private[providers] object VersionString:
  val value: String =
    try
      Option(getClass.getClassLoader.getResourceAsStream("VERSION"))
        .map(s => new String(s.readAllBytes(), "UTF-8").trim)
        .filter(_.nonEmpty)
        .getOrElse("dev")
    catch case _: Throwable => "dev"
