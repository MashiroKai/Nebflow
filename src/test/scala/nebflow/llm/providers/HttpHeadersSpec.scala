package nebflow.llm.providers

import munit.FunSuite

/**
 * protocol-compat 批（2026-11-01）——HTTP 头装配的**唯一一点**钉。
 *
 * 事故（作者实测 17:56 定论，与本批探测一致）：cmdcode 请求**没带 User-Agent**
 * 被 Cloudflare 边缘直接挡成 `HTTP 403 error code 1010`；同 key、同 URL、同 body
 * 换成带诚实的 UA 立刻 200。历史上的「厂家重置 key 后启用」是误判——key 从未死。
 *
 * H1 引擎缺省必带 User-Agent（非空、可识别）
 * H2 provider 级 headers 能覆盖缺省（大小写不敏感，不产生同名双头）
 * H3 真发一次请求：线上**确实**带上了 UA（见 AnthropicCompatSpec A5）
 * H4 边缘拦截有专门的人话诊断（读的人不会再去查 key）
 */
class HttpHeadersSpec extends FunSuite:

  test("H1: defaults always carry a non-empty, identifiable User-Agent") {
    val ua = HttpHeaders.merge(None).toMap.get("user-agent")
    assert(ua.exists(_.trim.nonEmpty), "user-agent must be present")
    assert(ua.exists(_.startsWith("stand/")), s"identifiable product token expected: $ua")
  }

  test("H2: provider headers override the defaults case-insensitively (no duplicate header)") {
    val merged = HttpHeaders.merge(Some(Map("User-Agent" -> "custom/9", "X-Extra" -> "1")))
    val uas = merged.filter { case (k, _) => k.equalsIgnoreCase("user-agent") }
    assertEquals(uas.size, 1, s"exactly one user-agent entry: $merged")
    assertEquals(uas.head._2, "custom/9")
    assert(merged.exists { case (k, v) => k == "X-Extra" && v == "1" })
  }

  test("H3: a provider that supplies headers can also override accept") {
    val merged = HttpHeaders.merge(Some(Map("accept" -> "application/json; charset=utf-8"))).toMap
    assertEquals(merged.get("accept"), Some("application/json; charset=utf-8"))
    assertEquals(merged.get("user-agent").exists(_.startsWith("stand/")), true, "UA survives an accept override")
  }

  test("H4: a cloudflare 1010 page produces a header-problem hint, not an auth story") {
    assert(HttpHeaders.isEdgeBlock(403, "error code: 1010"))
    val hint = HttpHeaders.edgeBlockHint(403, "error code: 1010")
    assert(hint.contains("REQUEST-HEADER problem"), hint)
    assert(hint.contains("not a dead credential"), hint)
    // 普通 403（真没权限）不得被误标成边缘拦截
    assert(!HttpHeaders.isEdgeBlock(403, """{"error":{"message":"permission denied"}}"""))
    assertEquals(HttpHeaders.edgeBlockHint(200, ""), "")
  }
end HttpHeadersSpec
