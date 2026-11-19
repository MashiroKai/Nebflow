package nebflow.llm.providers

import io.circe.{Json, JsonObject}
import munit.FunSuite
import nebflow.llm.SendMessageParams
import nebflow.shared.*

class RequestContextCacheSpec extends FunSuite:
  private val anthropic = new AnthropicAdapter("http://localhost:0", "k", null)
  private val openai = new OpenAiAdapter("http://localhost:0", "k", null)
  // 时钟简化批（2026-11-06）：动态段时钟退役（TimeAnchor.block 已删）——本 spec
  // 验证的是「每轮变化的临时尾段」装配语义，夹具改等价字面串（仍带 epoch_ms）。
  private val clock1 = "[request clock] Now: 2026-11-05 22:13:20 +08:00 周四 | epoch_ms 1793920000000"
  private val clock2 = "[request clock] Now: 2026-11-05 22:14:20 +08:00 周四 | epoch_ms 1793920060000"
  private val history = List(
    Message(MessageRole.User, Left("inspect file")),
    Message(MessageRole.Assistant, Right(List(ContentBlock.ToolUse("c1", "Read", JsonObject.empty)))),
    Message(MessageRole.User, Right(List(ContentBlock.ToolResult("c1", "file contents", Some(false)))))
  )
  private def params(msgs: List[Message], clock: Option[String]) = SendMessageParams(
    messages = msgs,
    model = "deepseek-flash",
    systemStable = Some("Stable instructions"),
    systemDynamic = clock,
    tools = Some(List(ToolDefinition("Read", "read file", JsonObject.empty)))
  )
  private def messages(body: Json): List[Json] = body.hcursor.get[List[Json]]("messages").toOption.get
  private def blocks(msg: Json): Vector[Json] = msg.hcursor.get[Vector[Json]]("content").toOption.get

  test("Anthropic: changing clocks follow the cached tool result, preserving pairing and all history blocks") {
    for streaming <- List(false, true) do
      val p = params(history, Some(clock1))
      val a = anthropic.bodyOf(p, streaming)
      val b = anthropic.bodyOf(p.copy(systemDynamic = Some(clock2)), streaming)
      assertEquals(a.hcursor.downField("system").focus, b.hcursor.downField("system").focus)
      assertEquals(a.hcursor.downField("tools").focus, b.hcursor.downField("tools").focus)
      val am = messages(a)
      val bm = messages(b)
      assertEquals(am.size, 3)
      assertEquals(am.init, bm.init)
      assertEquals(blocks(am.last).init, blocks(bm.last).init)
      assertEquals(blocks(am.last).head.hcursor.get[String]("type").toOption, Some("tool_result"))
      assert(blocks(am.last).head.hcursor.downField("cache_control").focus.isDefined)
      assertEquals(blocks(am.last).last.hcursor.get[String]("text").toOption, Some(clock1))
      assertEquals(blocks(bm.last).last.hcursor.get[String]("text").toOption, Some(clock2))
      assert(blocks(am.last).last.hcursor.downField("cache_control").focus.isEmpty)
      assertEquals(p.messages, history, "ephemeral clock must not be persisted")
  }

  test("Anthropic: assistant tail keeps its breakpoint before a separate clock message") {
    val h = history :+ Message(MessageRole.Assistant, Left("finished"))
    val m = messages(anthropic.bodyOf(params(h, Some(clock1)), streaming = false))
    assertEquals(m.size, h.size + 1)
    assertEquals(m.last.hcursor.get[String]("role").toOption, Some("user"))
    assertEquals(blocks(m.last).head.hcursor.get[String]("text").toOption, Some(clock1))
    assert(blocks(m(m.size - 2)).last.hcursor.downField("cache_control").focus.isDefined)
  }

  test("OpenAI: both reasoning passback modes preserve the entire prefix before the current clock") {
    for passback <- List(false, true) do
      val p = params(history, Some(clock1))
      val a = openai.requestMessages(p, passback)
      val b = openai.requestMessages(p.copy(systemDynamic = Some(clock2)), passback)
      assertEquals(a.init, b.init)
      assertEquals(a.init, openai.requestMessages(p.copy(systemDynamic = None), passback))
      assertEquals(a(a.size - 2).hcursor.get[String]("role").toOption, Some("tool"))
      assertEquals(a.last.hcursor.get[String]("role").toOption, Some("user"))
      assertEquals(a.last.hcursor.get[String]("content").toOption, Some(clock1))
      assertEquals(b.last.hcursor.get[String]("content").toOption, Some(clock2))
      assertEquals(p.messages, history)
  }

  test("empty history and absent/empty clocks do not lose context or add blank messages") {
    for empty <- List(None, Some("")) do
      assertEquals(messages(anthropic.bodyOf(params(Nil, empty), false)), Nil)
      assertEquals(openai.requestMessages(params(Nil, empty), false).size, 1) // system only
    val am = messages(anthropic.bodyOf(params(Nil, Some(clock1)), false))
    assertEquals(am.size, 1)
    assertEquals(blocks(am.head).head.hcursor.get[String]("text").toOption, Some(clock1))
    assertEquals(openai.requestMessages(params(Nil, Some(clock1)), false).last.hcursor.get[String]("content").toOption, Some(clock1))
  }
