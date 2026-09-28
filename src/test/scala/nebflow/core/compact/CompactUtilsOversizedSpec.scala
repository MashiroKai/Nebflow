package nebflow.core.compact

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import munit.CatsEffectSuite
import nebflow.core.PathUtil
import nebflow.shared.{ContentBlock, Defaults, Message, MessageRole}

import java.nio.file.Files

/** #38 Layer B — compact 轮输入的大结果精准剔除（2026-09-01）。
  *
  * 回归护栏：压缩轮喂给 LLM 的输入中，超大 ToolResult 必须被替换为
  * 占位符+落盘路径（否则历史超 provider 上限时压缩死锁）；小结果与
  * 结构原样保留；已落盘预览不重复处理。
  */
class CompactUtilsOversizedSpec extends CatsEffectSuite:

  private val testRoot = Files.createTempDirectory("compact-oversized-test")

  override def beforeAll(): Unit =
    PathUtil.setDataRoot(os.Path(testRoot))

  override def afterAll(): Unit =
    def deleteRecursively(path: java.nio.file.Path): Unit =
      if Files.exists(path) then
        val stream = Files.walk(path)
        try stream.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
        finally stream.close()
    deleteRecursively(testRoot)

  private def userMsg(blocks: ContentBlock*): Message =
    Message(MessageRole.User, Right(blocks.toList))

  private def toolResult(id: String, content: String): ContentBlock.ToolResult =
    ContentBlock.ToolResult(id, content)

  private def assistantMsg(toolUseId: String): Message =
    Message(
      MessageRole.Assistant,
      Right(List(ContentBlock.ToolUse(toolUseId, "Read", JsonObject.empty)))
    )

  private val big = "x" * 300_000
  private val small = "s" * 1_000
  private val persistedPreview = "<persisted-output>\nOutput too large (60.0 KB). Full output saved to: /x/y.txt\n</persisted-output>"

  test("300K ToolResult is stripped to placeholder + path, message body < 10K"):
    val messages = List(assistantMsg("call-1"), userMsg(toolResult("call-1", big)))
    val stripped = CompactUtils.stripOversizedToolResults(messages, "sess-b1")
    val resultContent = stripped(1).content.toOption.get.collectFirst { case tr: ContentBlock.ToolResult => tr.content }.get
    assert(resultContent.startsWith("<persisted-output>"), "must be persisted marker")
    assert(resultContent.contains("Full output saved to:"), "must reference on-disk file")
    assert(resultContent.contains("tool-results/sess-b1/call-1.txt"), "must carry the exact path")
    assert(resultContent.length < 10_000, s"stripped content must be small, got ${resultContent.length}")
    // 结构保留：仍是 2 条消息、assistant tool_use 原样
    assertEquals(stripped.size, 2)

  test("small results (<50K) pass through unchanged"):
    val messages = List(assistantMsg("call-s"), userMsg(toolResult("call-s", small)))
    val stripped = CompactUtils.stripOversizedToolResults(messages, "sess-b2")
    val content = stripped(1).content.toOption.get.collectFirst { case tr: ContentBlock.ToolResult => tr.content }.get
    assertEquals(content, small)

  test("already-persisted preview is not re-stripped"):
    val messages = List(assistantMsg("call-p"), userMsg(toolResult("call-p", persistedPreview)))
    val stripped = CompactUtils.stripOversizedToolResults(messages, "sess-b3")
    val content = stripped(1).content.toOption.get.collectFirst { case tr: ContentBlock.ToolResult => tr.content }.get
    assertEquals(content, persistedPreview)

  test("non-ToolResult blocks and text messages are untouched"):
    val textMsg = Message(MessageRole.User, Left("plain text"))
    val messages =
      List(
        textMsg,
        assistantMsg("call-t"),
        userMsg(
          ContentBlock.Text("intro"),
          toolResult("call-t", small),
          ContentBlock.Thinking("think")
        )
      )
    val stripped = CompactUtils.stripOversizedToolResults(messages, "sess-b4")
    assertEquals(stripped(0).content, Left("plain text"))
    val blocks = stripped(2).content.toOption.get
    assertEquals(blocks.count(_.isInstanceOf[ContentBlock.Text]), 1)
    assertEquals(blocks.count(_.isInstanceOf[ContentBlock.Thinking]), 1)
    assertEquals(
      blocks.collectFirst { case tr: ContentBlock.ToolResult => tr.content }.get,
      small
    )

  test("persistOversizedToolResults writes full text to disk, skips persisted"):
    val messages = List(assistantMsg("call-w"), userMsg(toolResult("call-w", big)))
    CompactUtils.persistOversizedToolResults(messages, "sess-b5").unsafeRunSync()
    val file = testRoot.resolve("tool-results").resolve("sess-b5").resolve("call-w.txt")
    assert(Files.exists(file), "oversized result must be persisted")
    assertEquals(new String(Files.readAllBytes(file), "UTF-8"), big)

  test("prepareCompactionInput combines persist + strip (end-to-end input shape)"):
    val messages = List(assistantMsg("call-e"), userMsg(toolResult("call-e", big)))
    val input = CompactUtils.prepareCompactionInput(messages, "sess-b6").unsafeRunSync()
    val content = input(1).content.toOption.get.collectFirst { case tr: ContentBlock.ToolResult => tr.content }.get
    assert(content.startsWith("<persisted-output>"))
    // 落盘文件可读回原文——路径引用真实可用
    val file = testRoot.resolve("tool-results").resolve("sess-b6").resolve("call-e.txt")
    assert(Files.exists(file))
    assertEquals(new String(Files.readAllBytes(file), "UTF-8"), big)

  test("strip is idempotent"):
    val messages = List(assistantMsg("call-i"), userMsg(toolResult("call-i", big)))
    val once = CompactUtils.stripOversizedToolResults(messages, "sess-b7")
    val twice = CompactUtils.stripOversizedToolResults(once, "sess-b7")
    assertEquals(twice, once)

end CompactUtilsOversizedSpec
