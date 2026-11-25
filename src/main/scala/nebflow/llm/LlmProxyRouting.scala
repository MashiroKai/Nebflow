package nebflow.llm

import nebflow.shared.NebflowServiceConfig

import java.io.IOException
import java.net.{Proxy, ProxySelector, SocketAddress, URI}
import java.util.List as JList

/**
 * Direct-connect routing for the engine's LLM transport (作者令 2026-11-19 16:06：
 * 「可以加进去。把我配置的llm 都改直连吧」)。
 *
 * 背景（已实测）：引擎 JVM 通过 macOS SystemConfiguration 继承系统代理
 * `http(s).proxyHost=127.0.0.1:7890`，而两处 LLM `HttpClient`
 * （[[LlmInterface.makeAttemptTransport]] per-attempt、[[LlmInterface.createLlm]]
 * shared）都没有显式 `.proxy(...)` ⇒ 走默认选择器 ⇒ 每个新连接白付 ~1.0s
 * （同端点直连 p50 0.396s vs 代理 1.313s；本机 N=15/臂实测见
 * `.stand/tmp/llm-direct-20261119/`）。
 *
 * 设计（最小侵入 + 按 host 粒度）：
 *   · **直连集** = 现配置 `llm.providers.*.baseUrl` 的全部 host（自动派生、自维护：
 *     加一个 provider 其 host 自动直连）——即作者「配置的 llm 都改直连」的字面语义；
 *   · **命中直连集的 host** → 返回 `[NO_PROXY]`（直连）；
 *   · **其余 host** → 回落 JVM 默认选择器（= 系统代理），行为与本批前逐字节一致。
 *
 * 回落保护（**必读**）：JDK `java.net.http.HttpClient` 对 `ProxySelector.select`
 * 返回的**列表只用第一项**（本批 N=4 实测：`[deadProxy, realProxy]` 与
 * `[deadProxy, NO_PROXY]` 均在死代理上直接 `ConnectException`，不回落第二项
 * ——见 `ProxyOrderProbe.java`）⇒ **无法**用选择器表达「直连失败自动回落代理」。
 * 因此本设计按 **host 粒度** 隔离：某 host 直连失败只影响发往该 host 的请求，
 * 且该失败仍走**既有**错误可见面（ConnectException → 分类/日志 → ProviderHealthMonitor
 * → 模型级 fallback 链换候选），**不存在「一个 host 直连失败把整个 LLM 面打挂」**。
 * 其余 host（非直连集）继续走系统代理，互不影响。
 *
 * 生效：`createLlm`（引擎 boot）期安装；**重启后生效**。回滚：
 * `-Dnebflow.llm.directConnect=false`（[[nebflow.shared.Defaults.LlmDirectConnect]]）
 * 或回退本文件/本批 commit。
 */
object LlmProxyRouting:

  /** URL 字符串的 host（小写），无法解析则 None。 */
  def hostOf(url: String): Option[String] =
    try
      Option(url)
        .map(_.trim)
        .filter(_.nonEmpty)
        .flatMap(u => Option(new URI(u).getHost))
        .map(_.toLowerCase)
    catch case _: Throwable => None

  /** provider baseUrl 集合 → 直连 host 集（小写、去重、剔除不可解析项）。 */
  def directHosts(baseUrls: Iterable[String]): Set[String] =
    baseUrls.flatMap(hostOf).filter(_.nonEmpty).toSet

  /**
   * 按 host 路由的代理选择器：命中 `direct` 的 host 直连（NO_PROXY），其余委托
   * `fallback`（通常 = JVM 默认选择器 = 系统代理）。`fallback` 为 null 时其余
   * host 亦直连（兜底不炸）。
   */
  final class HostDirectProxySelector(val direct: Set[String], val fallback: ProxySelector) extends ProxySelector:

    override def select(uri: URI): JList[Proxy] =
      val h = Option(uri).flatMap(u => Option(u.getHost)).map(_.toLowerCase)
      if h.exists(direct.contains) then JList.of(Proxy.NO_PROXY)
      // 底为 null 或指向自身（会自我递归）时，其余 host 亦直连——不依赖
      // `ProxySelector.getDefault()`（避免默认器恰为本类时的自递归）。
      else if fallback == null || fallback.eq(this) then JList.of(Proxy.NO_PROXY)
      else fallback.select(uri)

    override def connectFailed(uri: URI, sa: SocketAddress, ioe: IOException): Unit =
      if fallback != null then fallback.connectFailed(uri, sa, ioe)
  end HostDirectProxySelector

  /**
   * 由服务配置构造选择器；`None` = 不启用/无 host（**零行为变化**，调用方保持
   * 不设 `.proxy`）。
   *
   * @param enabled
   *   kill-switch（[[nebflow.shared.Defaults.LlmDirectConnect]]）；false ⇒ None。
   */
  def fromConfig(config: NebflowServiceConfig, enabled: Boolean): Option[ProxySelector] =
    if !enabled then None
    else
      val hosts = directHosts(config.llm.providers.values.map(_.baseUrl))
      if hosts.isEmpty then None
      else
        // 幂等：若默认选择器已是本类（createLlm 二次调用），取其底（可能为 null）
        // 而非自我套娃；否则用默认选择器（通常是系统代理 DefaultProxySelector）。
        val base = ProxySelector.getDefault() match
          case h: HostDirectProxySelector => h.fallback
          case other => other
        Some(new HostDirectProxySelector(hosts, base))
  end fromConfig

end LlmProxyRouting
