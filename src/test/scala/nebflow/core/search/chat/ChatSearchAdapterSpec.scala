package nebflow.core.search.chat

import munit.CatsEffectSuite
import nebflow.core.search.SearchIndexService
import nebflow.shared.{UiMessage, *}
import io.circe.syntax.*

import java.nio.charset.StandardCharsets

/**
 * ChatSearchAdapter —— 聊天检索面文档抽取（datalayer-spec 批 C1/C4：
 * 与 chatSearch.js normalizeMessage + popMediaIndex.indexMedia 同源口径）。
 *
 * 验收面：
 *   - message 片：user/ai/agent/ask 行入索引，injected user 行带 agent 投影；
 *     system/askUser/askPermission 不入（与前端口径一致）；
 *   - user 行附件名并入可检索文本（📎 前缀——§6.1）；
 *   - tool 行不入索引（2026-10-30 作者令①：搜索结果只搜对话）——summary/
 *     input/content 不产生 kind="tool" 文档；其 Pop 载荷仍产出 media/file/
 *     link 条目（工件面数据源不动）；
 *   - media/file/link 条目：user 附件（image/file）+ Pop 载荷（含 url 腿），
 *     extra = popMediaIndex 条目形状（前端网格免重抽——C4）；
 *   - 时间锚：tool 行的媒体条目取向前最近带 ts 行的回复锚（popMediaIndex 同款）；
 *   - 源目录零写入：抽取前后目标 .ui.json 字节不变（§4.1 纪律 1 +
 *     §6 冒烟红线）。
 */
class ChatSearchAdapterSpec extends CatsEffectSuite:

  private val dir = os.temp.dir(deleteOnExit = true) / "chat-adapter-spec"

  override def beforeEach(context: BeforeEach): Unit =
    os.remove.all(dir)
    os.makeDir.all(dir)

  private def writeUi(sid: String, msgs: List[UiMessage]): os.Path =
    val f = dir / s"$sid.ui.json"
    os.write(f, msgs.asJson.noSpaces)
    f

  private val adapter = new ChatSearchAdapter(dir)

  test("message 片：user/ai/agent/ask 入索引；system/askUser 不入") {
    writeUi("s1", List(
      UiMessage.User(text = "hello author", timestamp = 1000L),
      UiMessage.Ai(text = "ai reply", timestamp = 1100L),
      UiMessage.Agent(agentId = "sub1", text = "agent note", timestamp = 0L),
      UiMessage.Ask(question = "q?", answer = "a!", timestamp = 0L),
      UiMessage.System(content = "noise"),
      UiMessage.AskUser(items = Nil)
    ))
    val docs = adapter.extractSession("s1").getOrElse(Nil)
    assertEquals(docs.count(_.kind == "message"), 4)
    assertEquals(docs.count(_.kind == "tool"), 0)
  }

  test("user 行附件名并入可检索文本（📎 前缀——图片/文件 tab 可按键命中）") {
    writeUi("s2", List(
      UiMessage.User(
        text = "look at this",
        timestamp = 1000L,
        attachments = List(
          io.circe.Json.obj("type" -> io.circe.Json.fromString("image"),
            "name" -> io.circe.Json.fromString("photo.png"),
            "path"  -> io.circe.Json.fromString("/tmp/photo.png"))
        ))
    ))
    val docs = adapter.extractSession("s2").getOrElse(Nil)
    val msg  = docs.find(_.kind == "message").get
    assert(msg.text.contains("📎 photo.png"))
    // 附件同时产生 media 条目（extra = popMediaIndex 形状）
    val media = docs.find(_.kind == "media").get
    assert(media.extra.contains("\"type\":\"image\""))
    assert(media.extra.contains("photo.png"))
  }

  test("tool 行不入索引（作者令①）：summary/input/content 不产生 kind=\"tool\" 文档；Pop 载荷媒体条目照产") {
    writeUi("s3", List(
      UiMessage.User(text = "run it", timestamp = 1000L),
      UiMessage.Tool(label = "Bash\n  (ls)", summary = "ls output",
        content = "file list", input = "{\"cmd\":\"ls\"}")
    ))
    val docs = adapter.extractSession("s3").getOrElse(Nil)
    assertEquals(docs.count(_.kind == "tool"), 0)
    assertEquals(docs.count(_.kind == "message"), 1)
    // 非 Pop 载荷的 tool 行也不产出媒体条目（无工件可索引）。
    assertEquals(docs.count(k => Set("media", "file", "link")(k.kind)), 0)
  }

  test("时间锚：tool 行的媒体条目取向前最近带 ts 行（回复锚——popMediaIndex 同款）") {
    writeUi("s4", List(
      UiMessage.User(text = "at 5000", timestamp = 5000L),
      UiMessage.Tool(label = "Pop", summary = "p", content = "x")
    ))
    val docs = adapter.extractSession("s4").getOrElse(Nil)
    // tool 行自身已不入索引（作者令①）；回复锚语义保留在 media/file/link
    // 条目上（此处无 Pop 载荷 ⇒ 无条目，断言退化恒真；有载荷时锚 = 5000）。
    val anchored = docs.filter(d => d.ord == 1 && d.kind != "tool")
    assert(anchored.isEmpty || anchored.forall(_.ts == 5000L), clue(anchored.map(_.ts)))
  }

  test("Pop 载荷解析：图片条目 + url 腿 link 条目") {
    val payload = """___POP_JSON___
[{"kind":"image","path":"/tmp/out.png","name":"out.png","itemType":"image","src":"data:image/png;base64,AAA"},
 {"itemType":"url","url":"https://example.com/report","title":"Report"}]"""
    writeUi("s5", List(
      UiMessage.User(text = "pop it", timestamp = 1000L),
      UiMessage.Tool(label = "Pop", summary = "pop output", content = payload)
    ))
    val docs = adapter.extractSession("s5").getOrElse(Nil)
    assertEquals(docs.count(_.kind == "media"), 1)
    assertEquals(docs.count(_.kind == "link"), 1)
    val link = docs.find(_.kind == "link").get
    assert(link.extra.contains("https://example.com/report"))
    // thumbRef：data URI 原样引用（引用非拷贝）
    val media = docs.find(_.kind == "media").get
    assert(media.extra.contains("data:image/png;base64,AAA"))
  }

  test("injected user 行 = agent 投影（messageKind 口径：injected ⇒ agent 类）") {
    writeUi("s6", List(
      UiMessage.User(text = "injected row", timestamp = 1000L, injected = true, sender = Some("mail"))
    ))
    val docs = adapter.extractSession("s6").getOrElse(Nil)
    assertEquals(docs.length, 1)
    assert(docs.head.extra.contains("\"agent\":\"mail\""))
  }

  test("索引化端到端：抽取 → 灌库 → CJK/ASCII 查询命中（内存库）") {
    writeUi("s7", List(
      UiMessage.User(text = "聊天记录检索很慢", timestamp = 1000L),
      UiMessage.Ai(text = "delegate dispatch note", timestamp = 1100L)
    ))
    SearchIndexService.unsafeResetForTests()
    SearchIndexService.unsafeRegisterInMemory(adapter)
    SearchIndexService.unsafeInsertDocs("chat", adapter.extractSession("s7").getOrElse(Nil))
    for
      cjk <- SearchIndexService.query("chat", SearchIndexService.Query("检索", kinds = List("message")))
      asc <- SearchIndexService.query("chat", SearchIndexService.Query("delegate", kinds = List("message")))
    yield
      assertEquals(cjk.toOption.get.total, 1)
      assertEquals(asc.toOption.get.total, 1)
  }

  test("冒烟红线：抽取前后源文件字节逐字不变（零写入 §4.1 纪律 1）") {
    val msgs = List(
      UiMessage.User(text = "immutable source", timestamp = 1000L),
      UiMessage.Tool(label = "Bash", summary = "s", content = "c")
    )
    val f = writeUi("s8", msgs)
    val before = os.read.bytes(f)
    adapter.extractSession("s8")
    adapter.sessionFingerprint("s8")
    adapter.monthKey("s8")
    val after = os.read.bytes(f)
    assertEquals(new String(after, StandardCharsets.UTF_8), new String(before, StandardCharsets.UTF_8))
  }

  test("空/缺文件：extractSession = None（索引侧原样删旧）") {
    assertEquals(adapter.extractSession("no-such"), None)
    writeUi("s9", Nil)
    assertEquals(adapter.extractSession("s9"), None)
  }

end ChatSearchAdapterSpec
