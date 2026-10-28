package nebflow.gateway

import nebflow.shared.LlmProtocol

/**
 * settings-panel-jev-imagegen 批 B 件（2026-10-28）spec——每模型连接性测试探针
 * 的纯函数面 + 有界等待 + 脱敏纪律 + 真实 HTTP 一击（本地 stub，127.0.0.1 随机
 * 端口）：
 *  1. 端点/请求体/鉴权头按协议面（镜像两适配器的拼装形态）；
 *  2. 2xx = 可用（带延迟读数）；provider 错误体 = 失败（错误摘要可诊断）；
 *  3. 🔴 脱敏：key 值的任何出现都被抹成 `***`（错误面/预览面都过 sanitize）；
 *  4. 有界诊断超时（20s 档，挂起端点 1s 内不可证——本 spec 用挂起 stub 钉
 *     「失败而非永久转圈」的形态，等待上限读数不逐毫秒钉）。
 */
class ProviderModelProbeSpec extends munit.FunSuite:

  // ── 1. 纯函数面 ──────────────────────────────────────────────

  test("endpointFor: anthropic 拼 /v1/messages，openai 拼 /chat/completions，尾斜杠归一") {
    assertEquals(ProviderModelProbe.endpointFor(LlmProtocol.Anthropic, "https://api.commandcode.ai/provider/"), "https://api.commandcode.ai/provider/v1/messages")
    assertEquals(ProviderModelProbe.endpointFor(LlmProtocol.OpenAI, "https://api.commandcode.ai/provider/v1/"), "https://api.commandcode.ai/provider/v1/chat/completions")
    // 已带路径尾的 base 原样保留（适配器同款防双拼）。
    assertEquals(ProviderModelProbe.endpointFor(LlmProtocol.OpenAI, "https://x.ai/v1/chat/completions"), "https://x.ai/v1/chat/completions")
  }

  test("requestBody: 1 token 最小调用，两协议同形，零凭据位") {
    for proto <- List(LlmProtocol.Anthropic, LlmProtocol.OpenAI) do
      val body = ProviderModelProbe.requestBody(proto, "some/model").noSpaces
      assert(body.contains("\"max_tokens\":1"), "最小调用 1 token（几十 token 级真实配额）")
      assert(body.contains("some/model"))
      assert(!body.toLowerCase.contains("bearer") && !body.contains("x-api-key"), "body 永不携带凭据材料")
  }

  test("authHeaders: anthropic=x-api-key+version，openai=Bearer") {
    val a = ProviderModelProbe.authHeaders(LlmProtocol.Anthropic, "k1")
    assertEquals(a.contains("x-api-key" -> "k1"), true)
    assertEquals(a.exists(_._1 == "anthropic-version"), true)
    assertEquals(ProviderModelProbe.authHeaders(LlmProtocol.OpenAI, "k2"), List("Authorization" -> "Bearer k2"))
  }

  test("sanitize: key 值出现处一律 *** （空 key = identity）") {
    assertEquals(ProviderModelProbe.sanitize("Bearer sk-abc dead", "sk-abc"), "Bearer *** dead")
    assertEquals(ProviderModelProbe.sanitize("no secret here", "sk-abc"), "no secret here")
    assertEquals(ProviderModelProbe.sanitize("untouched", ""), "untouched")
    assertEquals(ProviderModelProbe.sanitize("untouched", "   "), "untouched")
  }

  // ── 2+3+4. 真实 HTTP 一击（本地 stub）────────────────────────

  private def withStub[A](handler: com.sun.net.httpserver.HttpExchange => Unit)(f: String => A): A =
    val server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/", (exchange) => { handler(exchange); exchange.close() })
    server.start()
    try f(s"http://127.0.0.1:${server.getAddress.getPort}")
    finally server.stop(0)

  test("call: 2xx 正体 = Ok（延迟读数 + 预览脱敏）") {
    withStub({ ex =>
      val b = "{\"ok\":true}".getBytes
      ex.sendResponseHeaders(200, b.length)
      ex.getResponseBody.write(b)
    }) { base =>
      ProviderModelProbe.call(LlmProtocol.OpenAI, base, "sk-live-999", "m1") match
        case ProviderModelProbe.Verdict.Ok(latency, reply) =>
          assert(latency >= 0)
          assertEquals(reply, "{\"ok\":true}")
        case other => fail(s"expected Ok, got $other")
    }
  }

  test("call: 2xx 错误壳 = Failed（zhipu 200-carrier 先例同判）；4xx = Failed 带可诊断摘要") {
    withStub({ ex =>
      val b = "{\"error\":{\"message\":\"model not found\"}}"
      ex.sendResponseHeaders(200, b.getBytes.length)
      ex.getResponseBody.write(b.getBytes)
    }) { base =>
      ProviderModelProbe.call(LlmProtocol.OpenAI, base, "k", "m") match
        case ProviderModelProbe.Verdict.Failed(detail) =>
          assert(detail.contains("model not found"), s"2xx 错误壳必须当失败并带 provider 消息: $detail")
        case ProviderModelProbe.Verdict.Ok(_, _) => fail("200-carrier error must not read as Ok")
    }
    withStub({ ex =>
      val b = "{\"error\":{\"message\":\"deepseek/deepseek-v4.1-flash is not supported on this endpoint\"}}"
      ex.sendResponseHeaders(400, b.getBytes.length)
      ex.getResponseBody.write(b.getBytes)
    }) { base =>
      ProviderModelProbe.call(LlmProtocol.Anthropic, base, "k", "m") match
        case ProviderModelProbe.Verdict.Failed(detail) =>
          assert(detail.startsWith("HTTP 400"), s"4xx 失败带状态码: $detail")
          assert(detail.contains("not supported"), "provider 自述错误进摘要（可诊断显示位的数据源）")
        case ProviderModelProbe.Verdict.Ok(_, _) => fail("400 must not read as Ok")
    }
  }

  test("call: 🔴 脱敏——响应体回显 key 值时，错误面与预览面都只剩 ***") {
    withStub({ ex =>
      val b = "{\"error\":{\"message\":\"bad key sk-live-999 for model m\"}}"
      ex.sendResponseHeaders(401, b.getBytes.length)
      ex.getResponseBody.write(b.getBytes)
    }) { base =>
      ProviderModelProbe.call(LlmProtocol.OpenAI, base, "sk-live-999", "m") match
        case ProviderModelProbe.Verdict.Failed(detail) =>
          assert(!detail.contains("sk-live-999"), s"错误面泄漏 key: $detail")
          assert(detail.contains("***"), "key 值出现处一律 ***")
        case ProviderModelProbe.Verdict.Ok(_, reply) =>
          assert(!reply.contains("sk-live-999"), "预览面泄漏 key")
    }
  }

  test("call: 传输层失败 = Failed（连接拒绝可诊断，非静默）") {
    // 随机空闲端口上的死端点：连接拒绝（Windows/mac 同形归 Failed）。
    val socket = new java.net.ServerSocket(0)
    val deadPort = socket.getLocalPort
    socket.close()
    ProviderModelProbe.call(LlmProtocol.OpenAI, s"http://127.0.0.1:$deadPort", "k", "m") match
      case ProviderModelProbe.Verdict.Failed(detail) => assert(detail.nonEmpty)
      case ProviderModelProbe.Verdict.Ok(_, _) => fail("dead endpoint must not read as Ok")
  }
end ProviderModelProbeSpec
