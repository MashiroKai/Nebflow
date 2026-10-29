package nebflow.gateway

import nebflow.llm.decision.DecisionProvider

/**
 * settings-panel-jev-imagegen 批 ②件（2026-10-29 作者令「然后 Jev/生图，也要
 * 像我们LLM这样，有个按钮来测试可用性」）spec——JeV 分配通道探针的纯函数面 +
 * 脱敏纪律 + 真实 HTTP 一击（本地 stub，127.0.0.1 随机端口）：
 *  1. laya = metadata 腿优先（/predict → /health 推导）；typesafe = 极小真实
 *     补全体（wire contract 钉形：state/model/1 个 noul 问题）；
 *  2. 2xx + answers 壳 = Ok；非 2xx = Failed 且状态码原样进文案（401 死 key
 *     直接可读）；
 *  3. 🔴 脱敏：token 值任何出现抹成 ***；
 *  4. keyRef 在位性先判：secret 缺席 = 直接 Failed（待录入），不发注定 401 的击。
 */
class JevProbeSpec extends munit.FunSuite:

  // ── 1. 纯函数面 ──────────────────────────────────────────────

  test("healthEndpointFor: /predict 推导 /health，已带尾斜杠/无 predict 路径的端点如实保留") {
    assertEquals(JevProbe.healthEndpointFor("http://127.0.0.1:8080/predict"), "http://127.0.0.1:8080/health")
    assertEquals(JevProbe.healthEndpointFor("http://127.0.0.1:8080/predict/"), "http://127.0.0.1:8080/health")
    assertEquals(JevProbe.healthEndpointFor("http://127.0.0.1:8080/other"), "http://127.0.0.1:8080/other")
  }

  test("minimalRequestBody: 极小真实补全体 = state/model/1 个 noul 问题，零凭据位") {
    val body = JevProbe.minimalRequestBody("jev-latest").noSpaces
    assert(body.contains("\"state\":\"ping\""), s"state 在位: $body")
    assert(body.contains("\"model\":\"jev-latest\""), s"model 在位: $body")
    assert(body.contains("\"noul\""), s"noul 问题在位: $body")
    assert(!body.toLowerCase.contains("bearer") && !body.contains("authorization"), "body 永不携带凭据材料")
  }

  test("sanitize: token 值出现处一律 ***（空 token = identity）") {
    assertEquals(JevProbe.sanitize("Bearer sk-abc dead", "sk-abc"), "Bearer *** dead")
    assertEquals(JevProbe.sanitize("untouched", ""), "untouched")
    assertEquals(JevProbe.sanitize("untouched", "   "), "untouched")
  }

  // ── 2+3. 真实 HTTP 一击（本地 stub）────────────────────────────

  private def withStub[A](handler: com.sun.net.httpserver.HttpExchange => Unit)(f: String => A): A =
    val server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/", (exchange) => { handler(exchange); exchange.close() })
    server.start()
    try f(s"http://127.0.0.1:${server.getAddress.getPort}")
    finally server.stop(0)

  private val answersBody =
    """{"model":"jev-1.13.0","answers":{"q1":{"type":"noul","noul":0.98}},"usage":{"input_tokens":1,"output_tokens":1}}"""

  test("call: typesafe 2xx + answers 壳 = Ok（model 预览脱敏）") {
    withStub({ ex =>
      val b = answersBody.getBytes
      ex.sendResponseHeaders(200, b.length)
      ex.getResponseBody.write(b)
    }) { base =>
      JevProbe.call(DecisionProvider.TypesafeJev, base, "jev-latest", "tsk-live-9") match
        case JevProbe.Verdict.Ok(latency, reply) =>
          assert(latency >= 0)
          assert(reply.contains("jev-1.13.0"), s"预览带 model 读数: $reply")
        case other => fail(s"expected Ok, got $other")
    }
  }

  test("call: 2xx 无 answers 壳 = Failed（可诊断）；非 2xx = Failed 状态码原样") {
    withStub({ ex =>
      val b = "{\"error\":\"no answers here\"}".getBytes
      ex.sendResponseHeaders(200, b.length)
      ex.getResponseBody.write(b)
    }) { base =>
      JevProbe.call(DecisionProvider.TypesafeJev, base, "m", "k") match
        case JevProbe.Verdict.Failed(detail) =>
          assert(detail.startsWith("HTTP 200"), s"200-carrier 错误壳当失败并带状态: $detail")
        case JevProbe.Verdict.Ok(_, _) => fail("200 without answers must not read as Ok")
    }
    withStub({ ex =>
      val b = "{\"error\":\"invalid token\"}".getBytes
      ex.sendResponseHeaders(401, b.length)
      ex.getResponseBody.write(b)
    }) { base =>
      // token 取真实长度值（单字符 token 会把正文里所有同字符都抹成 ***——
      // 与 ProviderModelProbe.sanitize 同一防回显语义，测试值必须像真 key）。
      JevProbe.call(DecisionProvider.TypesafeJev, base, "m", "sk-real-shaped-token-401") match
        case JevProbe.Verdict.Failed(detail) =>
          assert(detail.startsWith("HTTP 401"), s"401 状态码原样可见: $detail")
          assert(detail.contains("invalid token"), "provider 自述错误进摘要")
        case JevProbe.Verdict.Ok(_, _) => fail("401 must not read as Ok")
    }
  }

  test("call: 🔴 脱敏——错误面回显 token 时只剩 ***") {
    withStub({ ex =>
      val b = "{\"error\":\"bad token tsk-live-9 for model m\"}".getBytes
      ex.sendResponseHeaders(401, b.length)
      ex.getResponseBody.write(b)
    }) { base =>
      JevProbe.call(DecisionProvider.TypesafeJev, base, "m", "tsk-live-9") match
        case JevProbe.Verdict.Failed(detail) =>
          assert(!detail.contains("tsk-live-9"), s"错误面泄漏 token: $detail")
          assert(detail.contains("***"), "token 出现处一律 ***")
        case JevProbe.Verdict.Ok(_, reply) => assert(!reply.contains("tsk-live-9"), "预览面泄漏 token")
    }
  }

  test("call: laya 腿 = metadata 优先（GET /health，2xx 即 Ok）") {
    withStub({ ex =>
      // stub 同一处理器伺服任意路径——这里验证请求真的走了 /health 推导腿。
      assert(ex.getRequestMethod == "GET", s"laya 探活必须 GET（metadata 腿），got ${ex.getRequestMethod}")
      assert(ex.getRequestURI.getPath.endsWith("/health"), s"必须打 /health 推导面, got ${ex.getRequestURI.getPath}")
      val b = "ok".getBytes
      ex.sendResponseHeaders(200, b.length)
      ex.getResponseBody.write(b)
    }) { base =>
      JevProbe.call(DecisionProvider.Laya, s"$base/predict", "m", "") match
        case JevProbe.Verdict.Ok(_, _) => ()
        case other => fail(s"expected Ok, got $other")
    }
  }

  test("callWithKeyRef: keyRef secret 缺席 = 直接 Failed（待录入，不发 HTTP 击）") {
    val verdict = JevProbe.callWithKeyRef(
      DecisionProvider.TypesafeJev,
      "http://127.0.0.1:1/v1/systemone", // 死端点——若发了 HTTP 击必然 Transport 失败
      "m",
      Some("definitely-absent-keyref-name")
    )
    verdict match
      case JevProbe.Verdict.Failed(detail) =>
        assert(detail.contains("definitely-absent-keyref-name"), s"待录入文案带 keyRef 名: $detail")
        assert(detail.contains("secrets"), "文案指明在位性判据（secrets 下缺席）")
      case JevProbe.Verdict.Ok(_, _) => fail("absent secret must not read as Ok")
  }
end JevProbeSpec
