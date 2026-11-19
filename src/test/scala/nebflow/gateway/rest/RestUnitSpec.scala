package nebflow.gateway.rest

import nebflow.gateway.*

import munit.FunSuite

/**
 * 细粒度单元（rest 模块）：响应信封字面 + 引用注入块的纯清洗面。
 *
 * 既有 `FriendApiRoutesSpec` / `SecretsRoutesSpec` 等走的是「真起 mock 上游 + 路由
 * 端到端」。这里只钉信封 JSON 的逐字节形态与几个纯字符串判据。
 */
class RestUnitSpec extends FunSuite:

  test("ApiJson.ok: the frozen bare-success envelope"):
    assertEquals(ApiJson.ok.noSpaces, """{"ok":true}""")

  test("ApiJson.okMessage: ok first, message second (field order is the wire)"):
    assertEquals(ApiJson.okMessage("done").noSpaces, """{"ok":true,"message":"done"}""")
    assertEquals(ApiJson.okMessage("").noSpaces, """{"ok":true,"message":""}""")

  test("ApiJson.configUpdateFailed: the WS config-failure typed frame"):
    assertEquals(
      ApiJson.configUpdateFailed("bad key").noSpaces,
      """{"type":"configUpdateFailed","message":"bad key"}"""
    )

  test("RefResolver.sanitize: strips C0/C1 controls, DEL, U+2028/U+2029; keeps spaces"):
    val lineSep = 0x2028.toChar
    val paraSep = 0x2029.toChar
    val del = 0x7f.toChar
    val pilcrow = 0xb6.toChar
    assertEquals(RefResolver.sanitize(" a b "), " a b ")
    assertEquals(RefResolver.sanitize("a\nb\tc\rd"), "abcd")
    assertEquals(RefResolver.sanitize(s"x${lineSep}y${paraSep}z${del}"), "xyz")
    assertEquals(RefResolver.sanitize(s"para${pilcrow}graph"), s"para${pilcrow}graph", "printable unicode survives")

  test("RefResolver.cleanTitle: 80-char cap, applied after sanitizing"):
    val long = "t" * 100
    assertEquals(RefResolver.cleanTitle(long).length, 80)
    assertEquals(RefResolver.cleanTitle("short"), "short")
    assertEquals(RefResolver.cleanTitle("a\n" * 100).length, 80, "control chars are stripped first")

  test("RefResolver.dedupeTaskRefs: duplicate task refs collapse, first occurrence wins, order stable"):
    val refs = List(("t-1", "do a"), ("t-2", "do b"), ("t-1", "do a"))
    assertEquals(RefResolver.dedupeTaskRefs(refs), List(("t-1", "do a"), ("t-2", "do b")))

  test("RefResolver.tagOf: last selector segment's leading tag, or None"):
    assertEquals(RefResolver.tagOf("html>body>div.c>p:nth-of-type(2)"), Some("p"))
    assertEquals(RefResolver.tagOf("div"), Some("div"))
    assertEquals(RefResolver.tagOf(""), None)
    assertEquals(RefResolver.tagOf(">>"), None, "no segment carries a tag")

  test("RefResolver.formatMsgTs: MM-dd HH:mm (system zone)"):
    assert(RefResolver.formatMsgTs(0L).matches("\\d{2}-\\d{2} \\d{2}:\\d{2}"))

end RestUnitSpec
