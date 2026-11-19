package nebflow.llm.providers

import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite
import nebflow.shared.*

/**
 * protocol-compat 批（2026-11-01）——协议归一化层的纯函数钉。
 *
 * 这批的三个判据（[[WireShape]] 文件头）逐条钉死，任一退化即红：
 *
 *   N 组  不认的块不判死：未知块 / 未知 delta 携带文本必须降级保留；
 *         tool_use / image 不得被误判成文本。
 *   S 组  形状指纹：请求块类型序列、响应类型序列（anthropic 错误体 /
 *         thinking-first 成功体 / openai message+delta / 非 JSON 网关页）。
 *   R 组  路由自述：provider=<id> model=<id> 两个字段都在。
 *   P 组  可自愈判据：只有"上游明说思考必须回传"的 400 才算可自愈。
 *
 * 变异验红：把 classifyBlock 的未知分支改回 None（丢弃）→ N1/N2 红；把
 * responseShape 的 content 分支删掉 → S2 红（thinking-first 报不出类型序列）。
 */
class WireShapeSpec extends FunSuite:

  private def json(s: String): Json = parse(s).fold(e => throw e, identity)

  // ── R：路由自述 ──────────────────────────────────────────────────────

  test("R1: routeBanner carries provider AND model, readable in the system prompt") {
    val b = WireShape.routeBanner("deepseek", "deepseek-flash")
    assert(b.contains("provider=deepseek"), b)
    assert(b.contains("model=deepseek-flash"), b)
  }

  test("R2: routeBanner/routeLine degrade blanks to 'unknown' instead of empty fields") {
    assert(WireShape.routeBanner("", "  ").contains("provider=unknown model=unknown"))
    assert(WireShape.routeLine(null, null) == "provider=unknown model=unknown")
  }

  // ── N：未知块降级 ────────────────────────────────────────────────────

  test("N1: unknown block type carrying text degrades to Text (content never dropped)") {
    val b = json("""{"type":"output_text","text":"hello"}""")
    assertEquals(WireShape.classifyBlock(b), WireShape.Block.Text("hello"))
  }

  test("N2: unknown block carrying reasoning_content degrades to Thinking") {
    val b = json("""{"type":"vendor_reasoning_v2","reasoning_content":"why"}""")
    assertEquals(WireShape.classifyBlock(b), WireShape.Block.Thinking("why", None))
  }

  test("N3: redacted_thinking carries no readable body → Opaque, never fabricated text") {
    val b = json("""{"type":"redacted_thinking","data":"opaque"}""")
    assertEquals(WireShape.classifyBlock(b), WireShape.Block.Opaque("redacted_thinking"))
    // 关键：绝不把密文 data 当正文发出（伪造正文 = 双倍计费 + 幻觉污染）
    assert(WireShape.classifyBlock(b) != WireShape.Block.Text("opaque"))
  }

  test("N4: tool_use / image are NEVER degraded to text (tool pairing + image channel intact)") {
    assert(WireShape.classifyBlock(json("""{"type":"tool_use","id":"a","name":"Bash"}""")).isInstanceOf[WireShape.Block.Opaque])
    assert(WireShape.classifyBlock(json("""{"type":"image","source":{}}""")).isInstanceOf[WireShape.Block.Opaque])
  }

  test("N5: a block with no type but with text is still readable") {
    assertEquals(WireShape.classifyBlock(json("""{"text":"bare"}""")), WireShape.Block.Text("bare"))
  }

  // ── S：形状指纹 ──────────────────────────────────────────────────────

  test("S1: requestShape renders the per-message block alphabet (thinking+tool turns)") {
    val msgs = List(
      Message(MessageRole.User, Left("hi")),
      Message(
        MessageRole.Assistant,
        Right(
          List(
            ContentBlock.Thinking("t", None),
            ContentBlock.Text("a"),
            ContentBlock.ToolUse("id1", "Bash", io.circe.JsonObject.empty)
          )
        )
      ),
      Message(MessageRole.User, Right(List(ContentBlock.ToolResult("id1", "ok", None)))),
      Message(MessageRole.User, Left("go on"))
    )
    // 工具结果消息的角色是 user（wire 上就是 user 携带 tool_result 块）
    assertEquals(WireShape.requestShape(msgs), "u:t,a:ktu,u:r,u:t")
  }

  test("S2: responseShape names the thinking-first success sequence (the格式事故形态)") {
    val body = """{"content":[{"type":"thinking","thinking":"x"},{"type":"text","text":"y"}]}"""
    assertEquals(WireShape.responseShape(body), "thinking>text")
  }

  test("S3: responseShape names an anthropic error body with its error type and code") {
    val body =
      """{"type":"error","error":{"type":"invalid_request_error","code":"invalid_request_error","message":"m"}}"""
    assertEquals(WireShape.responseShape(body), "error/invalid_request_error(code=invalid_request_error)")
  }

  test("S4: responseShape distinguishes an openai non-stream message from a stream delta") {
    assertEquals(
      WireShape.responseShape("""{"choices":[{"message":{"role":"assistant","content":"a","reasoning_content":"b"}}]}"""),
      "message:content+reasoning_content"
    )
    assertEquals(
      WireShape.responseShape("""{"choices":[{"delta":{"reasoning_content":"x","content":"y"}}]}"""),
      "delta:content+reasoning_content"
    )
  }

  test("S5: responseShape survives a non-JSON gateway page (keeps a length + head)") {
    val s = WireShape.responseShape("<html>502 Bad Gateway</html>")
    assert(s.startsWith("non-json(28):"), s)
    assert(s.contains("502"), s)
  }

  test("S6: diagnose puts route + request shape + response shape in ONE line") {
    val d = WireShape.diagnose(
      "deepseek",
      "deepseek-flash",
      List(Message(MessageRole.User, Left("hi"))),
      Some(400),
      """{"error":{"type":"invalid_request_error","code":"invalid_request_error","message":"The `content[].thinking` in the thinking mode must be passed back"}}"""
    )
    assert(d.contains("provider=deepseek"), d)
    assert(d.contains("model=deepseek-flash"), d)
    assert(d.contains("request=u:t"), d)
    assert(d.contains("response=error/invalid_request_error"), d)
    assert(d.contains("HTTP 400"), d)
  }

  // ── P：可自愈判据 ────────────────────────────────────────────────────

  test("P1: the real deepseek passback body is recognised as repairable") {
    val body =
      """{"error":{"message":"The `content[].thinking` in the thinking mode must be passed back to the API. (request_id: x)","type":"invalid_request_error","code":"invalid_request_error"}}"""
    assert(WireShape.isThinkingPassbackRejection(400, body))
  }

  test("P2: other 400s are NOT repairable (no blind retry storm)") {
    assert(!WireShape.isThinkingPassbackRejection(400, """{"error":{"message":"Model \"x\" is not supported on this endpoint"}}"""))
    assert(!WireShape.isThinkingPassbackRejection(401, """{"error":{"message":"thinking must be passed back"}}"""))
    assert(!WireShape.isThinkingPassbackRejection(400, ""))
  }

  test("P3: upstreamExcerpt surfaces the provider's own sentence, whitespace-collapsed") {
    val body = """{"error":{"message":"line1\n  line2   spaced"}}"""
    assertEquals(WireShape.upstreamExcerpt(body), "line1 line2 spaced")
  }
