package nebflow.llm

import java.net.{Proxy, ProxySelector, URI}

import munit.FunSuite
import nebflow.shared.*

/** direct-connect 批（2026-11-19）: [[LlmProxyRouting]] 的 host 路由与开关语义。 */
class LlmProxyRoutingSpec extends FunSuite:

  private def cfg(baseUrls: String*): NebflowServiceConfig =
    NebflowServiceConfig(
      llm = ServiceLlmConfig(
        providers = baseUrls.zipWithIndex.map { case (u, i) =>
          s"p$i" -> ProviderConfig(u, "secret:config-x", LlmProtocol.OpenAI)
        }.toMap
      )
    )

  test("hostOf: parses host, tolerates trailing slash/path and rejects junk") {
    assertEquals(LlmProxyRouting.hostOf("https://api.commandcode.ai/provider/v1/"), Some("api.commandcode.ai"))
    assertEquals(LlmProxyRouting.hostOf("https://sub.lrical.com/"), Some("sub.lrical.com"))
    assertEquals(LlmProxyRouting.hostOf("https://Open.BigModel.CN/api/anthropic/"), Some("open.bigmodel.cn"))
    assertEquals(LlmProxyRouting.hostOf(""), None)
    assertEquals(LlmProxyRouting.hostOf("not a url"), None)
  }

  test("directHosts: dedupes and lowercases provider hosts") {
    assertEquals(
      LlmProxyRouting.directHosts(
        List("https://sub.lrical.com/v1/", "https://sub.lrical.com/", "https://Open.BigModel.CN/api/")
      ),
      Set("sub.lrical.com", "open.bigmodel.cn")
    )
  }

  test("selector: direct host -> NO_PROXY; other host -> fallback") {
    val fallback = new ProxySelector:
      override def select(uri: URI): java.util.List[Proxy] =
        java.util.List.of(new Proxy(Proxy.Type.HTTP, new java.net.InetSocketAddress("127.0.0.1", 7890)))
      override def connectFailed(uri: URI, sa: java.net.SocketAddress, ioe: java.io.IOException): Unit = ()
    val sel = new LlmProxyRouting.HostDirectProxySelector(Set("api.commandcode.ai"), fallback)

    assertEquals(sel.select(URI.create("https://api.commandcode.ai/provider/v1/")), java.util.List.of(Proxy.NO_PROXY))
    assertEquals(sel.select(URI.create("https://API.CommandCode.AI/x")), java.util.List.of(Proxy.NO_PROXY))
    val other = sel.select(URI.create("https://example.com/"))
    assertEquals(other.size, 1)
    assert(other.get(0).`type`() == Proxy.Type.HTTP, "non-direct host must fall through to the proxy")
  }

  test("selector: self/null fallback degrades to direct without recursion") {
    val sel = new LlmProxyRouting.HostDirectProxySelector(Set("a.example"), null)
    assertEquals(sel.select(URI.create("https://b.example/")), java.util.List.of(Proxy.NO_PROXY))
  }

  test("fromConfig: disabled switch -> None (zero behavior change)") {
    assertEquals(LlmProxyRouting.fromConfig(cfg("https://api.commandcode.ai/v1/"), enabled = false), None)
  }

  test("fromConfig: no providers -> None") {
    assertEquals(LlmProxyRouting.fromConfig(cfg(), enabled = true), None)
  }

  test("fromConfig: enabled with providers -> direct set == configured provider hosts") {
    val sel = LlmProxyRouting.fromConfig(
      cfg("https://api.commandcode.ai/provider/v1/", "https://open.bigmodel.cn/api/anthropic/"),
      enabled = true
    )
    assert(sel.isDefined)
    val h = sel.get.asInstanceOf[LlmProxyRouting.HostDirectProxySelector]
    assertEquals(h.direct, Set("api.commandcode.ai", "open.bigmodel.cn"))
    assertEquals(h.select(URI.create("https://api.commandcode.ai/")), java.util.List.of(Proxy.NO_PROXY))
  }

  test("Defaults.LlmDirectConnect default is true (direct is the new default)") {
    assert(Defaults.LlmDirectConnect)
  }
end LlmProxyRoutingSpec
