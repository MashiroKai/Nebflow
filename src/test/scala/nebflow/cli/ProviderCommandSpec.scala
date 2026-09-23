package nebflow.cli

import io.circe.Json
import munit.FunSuite

/**
 * provider 段（D-H3 三案 / D-H4 ⒜ / H6-H8）防回归：
 *   · 已批文案逐字锁（H6 两行 / H7 / H8 两形态）——任何改写即红；
 *   · 写载荷落点 `llm.providers.<name>`（复用 ConfigCommand.buildNestedJson 的嵌套语义，
 *     非法嵌套会把 provider 写错层：服务端 mergeConfig 整块替换，静默丢数据）；
 *   · 删除标记 = `<name>: null`（ConfigService.scala:238「null = 显式删除」）；
 *   · H8 失败形态的 HTTP 码/报文从 GatewayClient 既有报文体里取，取不到就退回原报文
 *     （不伪造码位）。
 * 纯函数面，不起网关、不触网。
 */
class ProviderCommandSpec extends FunSuite:

  private def parse(s: String): Json = io.circe.parser.parse(s).fold(e => throw e, identity)

  test("H6 帮助两行：逐字节等于作者 07:27 批文案") {
    assertEquals(
      ProviderCommand.HelpUsageLine,
      "nebflow provider add <name> --base-url <url> --protocol <anthropic|openai> [--model <id>]..."
    )
    assertEquals(
      ProviderCommand.HelpKeyLine,
      "API key: --api-key-stdin (recommended) | --api-key-file <path> | env NEBFLOW_PROVIDER_API_KEY"
    )
  }

  test("H6 两行挂在 examples（子命令帮助可印面），其余既有示例保留") {
    val ex = ProviderCommand.examples
    assert(ex.contains(ProviderCommand.HelpUsageLine), s"examples 缺 H6 首行：$ex")
    assert(ex.contains(ProviderCommand.HelpKeyLine), s"examples 缺 H6 次行：$ex")
    assert(ex.contains("nebflow provider list"))
  }

  test("H7 密钥缺失/非法文案：逐字节") {
    assertEquals(
      ProviderCommand.MissingKeyMessage,
      "No API key provided. Use --api-key-stdin, --api-key-file <path>, or set NEBFLOW_PROVIDER_API_KEY."
    )
  }

  test("H8 成功形态：模型数内插、逐字节") {
    assertEquals(
      ProviderCommand.reachableMsg("anthropic", 7),
      "Provider 'anthropic' reachable — 7 model(s) listed."
    )
    assertEquals(
      ProviderCommand.reachableMsg("anthropic", 0),
      "Provider 'anthropic' reachable — 0 model(s) listed."
    )
  }

  test("H8 失败形态：HTTP 码 + 报文（GatewayClient.requestError 既有报文体）") {
    val e = new RuntimeException("Gateway request failed (HTTP 502): upstream refused")
    assertEquals(
      ProviderCommand.unreachableMsg("deadend", e),
      "Provider 'deadend' unreachable: HTTP 502 upstream refused"
    )
  }

  test("H8 失败形态：取不到 HTTP 码时退回原报文，不伪造码位") {
    val e = new RuntimeException("Connection refused")
    assertEquals(
      ProviderCommand.unreachableMsg("deadend", e),
      "Provider 'deadend' unreachable: Connection refused"
    )
    assertEquals(
      ProviderCommand.unreachableMsg("deadend", new RuntimeException()),
      "Provider 'deadend' unreachable: RuntimeException"
    )
  }

  test("add 写载荷：落 llm.providers.<name>，含 baseUrl/protocol/apiKey/models") {
    val got = ProviderCommand.providerConfigJson("p", "http://x/v1", "openai", "k-1", List("m1"))
    assertEquals(
      got,
      parse(
        """{"llm":{"providers":{"p":{"baseUrl":"http://x/v1","protocol":"openai","apiKey":"k-1","models":[{"id":"m1"}]}}}}"""
      )
    )
    // 下钻回读对称（键名不是值）
    assertEquals(
      got.hcursor.downField("llm").downField("providers").downField("p").downField("baseUrl").as[String].toOption,
      Some("http://x/v1")
    )
  }

  test("add 写载荷：多模型（含去重）与空模型时省略 models 键") {
    val multi = ProviderCommand.providerConfigJson("p", "u", "anthropic", "k", List("a", "b", "a"))
    assertEquals(
      multi.hcursor.downField("llm").downField("providers").downField("p").downField("models").as[List[Json]].toOption,
      Some(List(parse("""{"id":"a"}"""), parse("""{"id":"b"}""")))
    )
    val empty = ProviderCommand.providerConfigJson("p", "u", "anthropic", "k", Nil)
    assertEquals(
      empty.hcursor.downField("llm").downField("providers").downField("p").downField("models").focus,
      None
    )
  }

  test("add 写载荷：同名不同名互不串层（provider 名不进值位）") {
    val got = ProviderCommand.providerConfigJson("weird.name", "u", "openai", "k", List("m"))
    assertEquals(
      got.hcursor
        .downField("llm")
        .downField("providers")
        .downField("weird.name")
        .downField("protocol")
        .as[String]
        .toOption,
      Some("openai")
    )
  }

  test("remove 载荷：null 删除标记（服务端 mergeConfig 删键语义）") {
    assertEquals(
      ProviderCommand.removePayload("p"),
      parse("""{"llm":{"providers":{"p":null}}}""")
    )
    assert(
      ProviderCommand
        .removePayload("p")
        .hcursor
        .downField("llm")
        .downField("providers")
        .downField("p")
        .focus
        .contains(Json.Null)
    )
  }

  test("密钥环境变量名 = 已批文案第二行所载") {
    assertEquals(ProviderCommand.ApiKeyEnvVar, "NEBFLOW_PROVIDER_API_KEY")
    assert(ProviderCommand.HelpKeyLine.contains(ProviderCommand.ApiKeyEnvVar))
  }
end ProviderCommandSpec
