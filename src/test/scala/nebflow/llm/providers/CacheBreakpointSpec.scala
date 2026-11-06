package nebflow.llm.providers

import cats.effect.IO
import io.circe.Json
import io.circe.JsonObject
import munit.FunSuite
import nebflow.llm.{CacheStats, SendMessageParams}
import nebflow.shared.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.StreamBackend

import scala.concurrent.duration.*

/**
 * cache-hit 批（2026-11-06）回归闸：**「不该 miss 的场景」钉死**。
 *
 * 现场读数（作者令「为什么现在缓存命中率这么低」的定谳证据，stand 日志
 * 2026-11-06 1574 单 / 202.7M input tokens）：命中率 4.83%；命中请求的
 * cacheRead 中位数 13,056 / p90 19,392 / max 33,152，**无一单 >50k**，而请求
 * input 中位数 ≈119k——供应商只缓存到显式 `cache_control` 断点为止，旧断点只钉
 * system 稳定段 + 末尾 tool schema（≈13-19k），会话历史（~106k）**结构性不可
 * 缓存**（「缓存面积」只盖每请求 ~11%）。
 *
 * 修法 = 消息尾增量断点（`withTailCacheBreakpoint`，装配点 `bodyOf`）：每请求
 * 把断点钉在最后一条消息的最后一个可标块上，同会话下一轮即可整段命中。本 spec
 * 四条不变量：
 *
 *   ①尾断点落位——最后一条消息的可标尾块必须带 `cache_control: ephemeral`；
 *   ②同会话连续请求 prefix 逐字节稳定——历史只增一条时，先前全部消息的 wire
 *     序列化逐字节不变（断点只影响最后一条）⇒ 供应商前缀查找必命中；
 *   ③安全退化——全 thinking 块尾消息不标（Anthropic 对 thinking 块的
 *     cache_control 无契约）、空/异形 content 不构造坏 wire；
 *   ④断点预算 ≤ 4——system 稳定段(1) + 末 tool(1) + 消息尾(1) = 3。
 *
 * 另：failover 换 provider/model = 换缓存域，属**物理性**、不算回归，但必须可
 * 观测（CacheStats 域分列，本文件 §CacheStats）。
 */
class CacheBreakpointSpec extends FunSuite:

  private def adapter: AnthropicAdapter =
    AnthropicAdapter(
      "http://localhost:0",
      "k",
      null.asInstanceOf[StreamBackend[IO, Fs2Streams[IO]]],
      requireThinkingPassback = false,
      providerId = "zai-test"
    )

  // ── fixtures ──────────────────────────────────────────────────────────

  private def userText(t: String): Message  = Message(MessageRole.User, Left(t))
  private def asstText(t: String): Message  = Message(MessageRole.Assistant, Left(t))
  private def toolUse(id: String): Message =
    Message(MessageRole.Assistant, Right(List(ContentBlock.ToolUse(id, "Read", JsonObject.empty))))
  private def toolResult(id: String, t: String): Message =
    Message(MessageRole.User, Right(List(ContentBlock.ToolResult(id, t, Some(false)))))

  private val tools = Some(
    List(
      ToolDefinition("Read", "read a file", JsonObject.empty),
      ToolDefinition("Edit", "edit a file", JsonObject.empty)
    )
  )

  private def params(msgs: List[Message]) =
    SendMessageParams(
      messages = msgs,
      model = "glm-5.3-flash",
      tools = tools,
      systemStable = Some("STABLE-SYSTEM-PROMPT"),
      systemDynamic = Some("dynamic env info that changes every turn")
    )

  /** 标准四轮 agent 会话：user → assistant(tool_use) → user(tool_result) → assistant(text)。 */
  private def conversation: List[Message] = List(
    userText("help me inspect the repo"),
    toolUse("call_1"),
    toolResult("call_1", "file contents ..."),
    asstText("done, here is the summary")
  )

  // ── 判据① 尾断点落位 ─────────────────────────────────────────────────

  test("① tail breakpoint lands on the last markable block of the last message only") {
    val marked = adapter.withTailCacheBreakpoint(adapter.toAnthropicMessages(conversation))
    assertEquals(marked.length, 4)
    // 除最后一条外，任何消息不得携带断点
    val earlier = marked.init.flatMap(_.hcursor.downField("content").as[List[Json]].getOrElse(Nil))
    assertEquals(earlier.count(_.hcursor.downField("cache_control").focus.isDefined), 0, "no marker before the tail")
    // 尾断点在最后一条消息的尾块上，且值 = ephemeral
    val lastBlocks = marked.last.hcursor.downField("content").as[List[Json]].getOrElse(Nil)
    val tailBlock  = lastBlocks.last
    assertEquals(tailBlock.hcursor.get[String]("type").toOption, Some("text"))
    assertEquals(tailBlock.hcursor.downField("cache_control").get[String]("type").toOption, Some("ephemeral"))
    // 全消息列表恰 1 个断点（system+tools 的另 2 个在 bodyOf 层钉，见判据④）
    assertEquals(countMarkers(marked), 1, "exactly one message-tail marker")
  }

  test("① tail breakpoint lands on tool_result when the agent loop ends with a tool result") {
    val loopTail = conversation.take(3) // …assistant(tool_use) → user(tool_result)
    val marked   = adapter.withTailCacheBreakpoint(adapter.toAnthropicMessages(loopTail))
    val lastBlocks = marked.last.hcursor.downField("content").as[List[Json]].getOrElse(Nil)
    assertEquals(lastBlocks.last.hcursor.get[String]("type").toOption, Some("tool_result"))
    assertEquals(lastBlocks.last.hcursor.downField("cache_control").get[String]("type").toOption, Some("ephemeral"))
  }

  test("① plain-string message canonicalizes to a one-block array; marker lands on that block") {
    val wire = adapter.toAnthropicMessages(List(userText("plain question")))
    // 规范化（cache-hit 批）：字符串 content 一律块数组 ⇒ 相邻两轮 wire 结构稳定
    val blocks0 = wire.last.hcursor.downField("content").as[List[Json]].getOrElse(Nil)
    assertEquals(blocks0.map(_.hcursor.get[String]("text").toOption), List(Some("plain question")))
    val marked = adapter.withTailCacheBreakpoint(wire)
    val blocks = marked.last.hcursor.downField("content").as[List[Json]].getOrElse(Nil)
    assertEquals(blocks.length, 1)
    assertEquals(blocks.head.hcursor.get[String]("text").toOption, Some("plain question"))
    assertEquals(blocks.head.hcursor.downField("cache_control").get[String]("type").toOption, Some("ephemeral"))
  }

  // ── 判据② 同会话连续请求 prefix 逐字节稳定（「不该 miss」的主判据）─────
  //
  // 对比口径：**剥掉 cache_control 后逐字节相等**。断点每轮挪到新尾（滑动
  // marker 是 Anthropic 缓存契约的标准用法，Claude Code 同款）：上一轮尾块上的
  // marker 在新请求里消失属于 marker 元数据移动，**内容字节不变** ⇒ 供应商
  // 前缀查找（按内容、不含 marker 元数据）必命中整段历史。

  /** 递归剥掉所有 cache_control 键（marker 是元数据，不参与前缀字节对比）。 */
  private def stripMarkers(json: Json): Json = json.fold(
    json,
    _ => json,
    _ => json,
    _ => json,
    arr => Json.fromValues(arr.map(stripMarkers).toList),
    obj =>
      val filtered = JsonObject.fromIterable(obj.toList.collect {
        case (k, v) if k != "cache_control" => k -> stripMarkers(v)
      })
      Json.fromJsonObject(filtered)
  )

  test("② appending one message leaves every earlier message byte-identical (modulo the moved marker)") {
    val before = adapter.withTailCacheBreakpoint(adapter.toAnthropicMessages(conversation))
    val after  = adapter.withTailCacheBreakpoint(adapter.toAnthropicMessages(conversation :+ userText("next turn")))
    assertEquals(after.length, before.length + 1)
    // 前 3 条：连 marker 都不曾在场，必须逐字节相等
    before.take(3).zip(after.take(3)).zipWithIndex.foreach { case ((b, a), i) =>
      assertEquals(a.noSpaces, b.noSpaces, s"message[$i] wire bytes changed by appending a later message")
    }
    // 上一轮尾条（第 4 条）：内容字节相等（marker 从它挪到新尾）
    assertEquals(stripMarkers(after(3)).noSpaces, stripMarkers(before(3)).noSpaces, "previous tail content changed")
    // 新尾条带断点（增量写入点），且全列表仍恰 1 个 marker
    val newTailBlocks = after.last.hcursor.downField("content").as[List[Json]].getOrElse(Nil)
    assertEquals(
      newTailBlocks.last.hcursor.downField("cache_control").get[String]("type").toOption,
      Some("ephemeral"),
      "the appended tail carries the moving breakpoint"
    )
    assertEquals(countMarkers(after), 1, "exactly one moving marker per request")
  }

  test("② full body of consecutive same-session requests differs only inside the last message") {
    val bodyBefore = adapter.bodyOf(params(conversation), streaming = false)
    val bodyAfter  = adapter.bodyOf(params(conversation :+ userText("next turn")), streaming = false)
    // system + tools 跨轮逐字节稳定（稳定段本就不带挪动 marker）
    assertEquals(
      stripMarkers(bodyAfter.hcursor.downField("system").as[Json].getOrElse(Json.Null)).noSpaces,
      stripMarkers(bodyBefore.hcursor.downField("system").as[Json].getOrElse(Json.Null)).noSpaces,
      "system wire changed across turns"
    )
    assertEquals(
      stripMarkers(bodyAfter.hcursor.downField("tools").as[Json].getOrElse(Json.Null)).noSpaces,
      stripMarkers(bodyBefore.hcursor.downField("tools").as[Json].getOrElse(Json.Null)).noSpaces,
      "tools wire changed across turns"
    )
    val msgsBefore = bodyBefore.hcursor.downField("messages").as[List[Json]].getOrElse(Nil)
    val msgsAfter  = bodyAfter.hcursor.downField("messages").as[List[Json]].getOrElse(Nil)
    msgsBefore.zip(msgsAfter.take(msgsBefore.length)).zipWithIndex.foreach { case ((b, a), i) =>
      assertEquals(stripMarkers(a).noSpaces, stripMarkers(b).noSpaces, s"body messages[$i] changed across turns")
    }
  }

  // ── 判据③ 安全退化 ──────────────────────────────────────────────────

  test("③ all-thinking tail message (unsigned, no passback → blank string) gets a safe text-block marker") {
    val msgs = List(userText("go"), Message(MessageRole.Assistant, Right(List(ContentBlock.Thinking("hmm", None)))))
    val wire = adapter.toAnthropicMessages(msgs) // unsigned thinking dropped ⇒ content " " string
    val marked = adapter.withTailCacheBreakpoint(wire)
    val blocks = marked.last.hcursor.downField("content").as[List[Json]].getOrElse(Nil)
    assertEquals(blocks.length, 1)
    assertEquals(blocks.head.hcursor.get[String]("type").toOption, Some("text"))
  }

  test("③ thinking+text tail puts the marker on the text block, never on thinking") {
    val passback = AnthropicAdapter(
      "http://localhost:0",
      "k",
      null.asInstanceOf[StreamBackend[IO, Fs2Streams[IO]]],
      requireThinkingPassback = true,
      providerId = "deepseek-test"
    )
    val msgs = List(
      userText("go"),
      Message(
        MessageRole.Assistant,
        Right(List(ContentBlock.Thinking("reasoning", None), ContentBlock.Text("answer")))
      )
    )
    val marked = passback.withTailCacheBreakpoint(passback.toAnthropicMessages(msgs))
    val blocks = marked.last.hcursor.downField("content").as[List[Json]].getOrElse(Nil)
    assertEquals(blocks.count(_.hcursor.downField("cache_control").focus.isDefined), 1)
    assertEquals(blocks.last.hcursor.get[String]("type").toOption, Some("text"))
    assertEquals(blocks.head.hcursor.downField("cache_control").focus.isDefined, false, "no marker on thinking")
  }

  test("③ empty message list is a no-op (no crash, no phantom marker)") {
    assertEquals(adapter.withTailCacheBreakpoint(Nil), Nil)
  }

  // ── 判据④ 全 body 断点预算（Anthropic 上限 4）────────────────────────

  test("④ full body carries at most 4 cache_control markers (system 1 + tools 1 + tail 1)") {
    val body = adapter.bodyOf(params(conversation), streaming = false)
    val n    = countMarkersDeep(body)
    assertEquals(n, 3, s"budget: system stable + last tool + message tail = 3, got $n")
    // 断点落位：system 稳定块、末尾 tool、消息尾块
    val sys = body.hcursor.downField("system").as[List[Json]].getOrElse(Nil)
    assertEquals(sys.count(_.hcursor.downField("cache_control").focus.isDefined), 1, "marker on stable system block")
    val toolJsons = body.hcursor.downField("tools").as[List[Json]].getOrElse(Nil)
    assertEquals(toolJsons.count(_.hcursor.downField("cache_control").focus.isDefined), 1, "marker on last tool only")
  }

  private def countMarkers(msgs: List[Json]): Int = msgs.count { m =>
    m.hcursor.downField("content").focus.flatMap(_.asArray).exists(
      _.exists(_.hcursor.downField("cache_control").focus.isDefined)
    )
  }

  private def countMarkersDeep(json: Json): Int =
    json.fold(0, _ => 0, _ => 0, _ => 0, arr => arr.foldLeft(0)((acc, j) => acc + countMarkersDeep(j)), obj =>
      val self = if obj("cache_control").isDefined then 1 else 0
      obj.values.foldLeft(self)((acc, j) => acc + countMarkersDeep(j))
    )

/** CacheStats：命中率聚合读数（日志聚合即可）的机械判据 + 面板口径对照。 */
class CacheStatsSpec extends FunSuite:

  private val zaiRead  = TokenUsage(inputTokens = 1000, outputTokens = 10, cacheReadTokens = Some(100))
  private val dsNoRead = TokenUsage(inputTokens = 500, outputTokens = 5, cacheReadTokens = None)

  override def beforeEach(context: BeforeEach): Unit = CacheStats.resetForTest()

  test("record aggregates per (provider,model) domain and total with panel-identical denominators") {
    CacheStats.record("zai", "glm-5.3-flash", Some(zaiRead))
    CacheStats.record("zai", "glm-5.3-flash", Some(zaiRead))
    CacheStats.record("deepseek", "deepseek-v4.1", Some(dsNoRead))
    val total = CacheStats.totalSnapshot
    assertEquals(total.requests, 3L)
    assertEquals(total.inputTokens, 2500L)
    assertEquals(total.cacheRead, 200L)
    assertEquals(total.hitPct, 200.0 * 100.0 / 2500.0, 1e-9)
    val domains = CacheStats.domainSnapshots
    assertEquals(domains("zai/glm-5.3-flash").requests, 2L)
    assertEquals(domains("zai/glm-5.3-flash").hitPct, 10.0, 1e-9)
    assertEquals(domains("deepseek/deepseek-v4.1").cacheRead, 0L)
  }

  test("None usage (failed request) never enters the denominator") {
    CacheStats.record("zai", "glm-5.3-flash", None)
    assertEquals(CacheStats.totalSnapshot.requests, 0L)
    assertEquals(CacheStats.totalSnapshot.inputTokens, 0L)
  }

  test("maybeEmit(force=true) emits and checkpoints the window without throwing") {
    CacheStats.record("zai", "glm-5.3-flash", Some(zaiRead))
    CacheStats.maybeEmit(force = true) // 落一条 INFO 行（日志面）
    // 窗口已打点：紧随其后的非 force 调用不再重复发射
    CacheStats.maybeEmit()
    assertEquals(CacheStats.totalSnapshot.requests, 1L, "emission never mutates counters")
  }

  test("domain rows make failover cache-domain flips visible (physical, not a regression)") {
    // 同一 model 两条 provider 腿各记一笔 = 换域在 domains 分列可见
    CacheStats.record("zhipu", "glm-5.3-flash", Some(zaiRead))
    CacheStats.record("Zai", "glm-5.3-flash", Some(zaiRead))
    assertEquals(CacheStats.domainSnapshots.size, 2, "two legs of one model = two cache domains")
  }

  test("emission gate fires on the 200th request without force") {
    val u = TokenUsage(inputTokens = 10, outputTokens = 0, cacheReadTokens = None)
    (1 to 199).foreach(_ => CacheStats.record("zai", "glm-5.3-flash", Some(u)))
    CacheStats.maybeEmit() // 未满 200 ⇒ 静默（无断言副作用，只验证不抛）
    CacheStats.record("zai", "glm-5.3-flash", Some(u))
    CacheStats.maybeEmit() // 第 200 单 ⇒ 落行（同样只验证不抛）
    assertEquals(CacheStats.totalSnapshot.requests, 200L)
  }
