package nebflow.gateway

import munit.FunSuite

/**
 * NF_HOME 补注入批（2026-11）——服务端注入半面的结构钉。
 *
 * 钉四件事：① `normalizeHomeDir` 尾分隔符剥离（`/` 与 `\`，含连续多个）；
 * ② 注入脚本形态 `window.NF_HOME=<json literal>;`（与 __WEB_FINGERPRINT__ 同族
 * 载体，前端 delegatePanoramaView.shortPath 消费）；③ Windows 反斜杠在 JS 字面
 * 量里必须转义（circe 编码）、`</` 标签突破加固；④ 空家目录不注入（客户端真值
 * 守卫静默跳过，绝不注入 `""` 让前端把一切路径缩成 `~`）。
 */
class StaticRoutesHomeScriptSpec extends FunSuite:

  test("normalizeHomeDir: strips trailing separators of both flavours, keeps the rest verbatim") {
    assertEquals(StaticRoutes.normalizeHomeDir("/Users/author/"), "/Users/author")
    assertEquals(StaticRoutes.normalizeHomeDir("/Users/author//"), "/Users/author")
    assertEquals(StaticRoutes.normalizeHomeDir("/Users/author"), "/Users/author")
    assertEquals(StaticRoutes.normalizeHomeDir("""C:\Users\author\"""), """C:\Users\author""")
    assertEquals(StaticRoutes.normalizeHomeDir("  /Users/author  "), "/Users/author", "首尾空白不入户")
    assertEquals(StaticRoutes.normalizeHomeDir("/"), "", "根目录剥光 ⇒ 空（调用侧据此不注入）")
    assertEquals(StaticRoutes.normalizeHomeDir(""), "")
  }

  test("homeScriptTagOf: pinned injected shape window.NF_HOME=<json literal>;") {
    assertEquals(
      StaticRoutes.homeScriptTagOf("/Users/author"),
      """<script>window.NF_HOME="/Users/author";</script>""",
      "注入形态钉死（与前端 shortPath 消费面配对）"
    )
    assertEquals(
      StaticRoutes.homeScriptTagOf("/Users/author/"),
      """<script>window.NF_HOME="/Users/author";</script>""",
      "尾分隔符在注入前已剥（比对侧不再拼出 //）"
    )
  }

  test("homeScriptTagOf: Windows backslashes stay escaped in the JS literal, and `</` cannot break out") {
    assertEquals(
      StaticRoutes.homeScriptTagOf("""C:\Users\author"""),
      """<script>window.NF_HOME="C:\\Users\\author";</script>""",
      "反斜杠走 JSON 转义 ⇒ JS 解出来仍是原生形态"
    )
    assertEquals(
      StaticRoutes.homeScriptTagOf("/tmp/a</script><script>alert(1)"),
      """<script>window.NF_HOME="/tmp/a<\/script><script>alert(1)";</script>""",
      "`</` 转成 `<\\/`（script 标签突破加固，与 brandScriptJson 同纪律）"
    )
  }

  test("homeScriptTagOf: empty home injects nothing") {
    assertEquals(StaticRoutes.homeScriptTagOf(""), "")
    assertEquals(StaticRoutes.homeScriptTagOf("/"), "")
  }

  test("homeScriptTag: real JVM home is injected, non-empty and separator-trimmed") {
    assert(StaticRoutes.homeDirValue.nonEmpty, "user.home 必可得（否则前端静默跳过）")
    assert(!StaticRoutes.homeDirValue.endsWith("/") && !StaticRoutes.homeDirValue.endsWith("\\"), StaticRoutes.homeDirValue)
    assertEquals(StaticRoutes.homeScriptTag, StaticRoutes.homeScriptTagOf(StaticRoutes.homeDirValue))
    assert(StaticRoutes.homeScriptTag.startsWith("<script>window.NF_HOME=\""), StaticRoutes.homeScriptTag)
  }
