package nebflow.llm.providers

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.{Json, JsonObject}
import munit.FunSuite
import nebflow.llm.SendMessageParams
import nebflow.shared.*

import scala.collection.mutable

/**
 * 缓存前缀装配契约（cache-warmup 批 2026-11-06，作者补充令 §二）——生产级硬闸。
 *
 * 契约（不变量，适用协议/端点见各判据）：
 *   C1 持久/临时边界：AgentSessionExecution 装配的「持久历史」与「请求级上下文」
 *      （contextMsg/branchMsg/maintenanceMsg + 时间锚 systemDynamic）必须显式分离；
 *      适配器 wire 上缓存断点只能落在**持久历史**的最后一块上，全部临时段严格
 *      位于断点之后（Anthropic 面）；OpenAI 面临时段只在历史尾部（隐式前缀缓存）。
 *   C2 前缀可续性：下一轮请求（历史只追加 assistant/tool_result）必须能命中
 *      上一轮在**持久边界**写入的缓存条目（mock cache 严格建模「只有真实断点
 *      才写入」——不假定任意稳定前缀自动存在于缓存）。
 *   C3 前缀稳定：提醒出现→变化→消失三轮，历史/工具/system 有效内容跨轮
 *      逐块不变；时间锚每轮真实换 epoch（禁止两轮复用固定 dynamic 字符串）。
 *   C4 临时段不写回：请求级提醒不进持久历史（不落盘、下一轮装配不含）。
 *
 * 缺陷史（红→绿证据，2026-11-06 21:11:41 实测）：修复前 AgentSessionExecution 把
 * contextMsg/branchMsg/maintenanceMsg 直接拼接进 `LlmRequest.messages`，本规格
 * 以旧装配形态驱动时判据①②红（expected 2, obtained 3——断点落在临时提醒块
 * idx3 而非持久边界 idx2）、判据④红（提醒不在断点之后）。修复 = LlmRequest/
 * SendMessageParams 新增 `requestOnlyContext`，断点只对持久历史负责。
 *
 * 运行入口：`sbt "Test/testOnly nebflow.llm.providers.CachePrefixAssemblySpec"`
 * 归属 CI：既有 `sbt test` 家族（Test/testOnly 与全量家族两条入口都跑）。
 *
 * 变异红线：把临时提醒重新拼回 messages（requestOnlyContext=Nil）⇒ 判据①②④
 * 必须当场红（变异验证在隔离副本执行，见批报告 §变异）。
 */
class CachePrefixAssemblySpec extends FunSuite:

  private def anthropic: AnthropicAdapter = new AnthropicAdapter("http://localhost:0", "k", null)
  private def openai: OpenAiAdapter       = new OpenAiAdapter("http://localhost:0", "k", null)

  // ── fixtures：三轮真实场景 ────────────────────────────────────────────
  // 历史只追加（assistant/tool_result）；提醒出现→变化→消失；每轮真实 TimeAnchor。
  // 装配形态 = 生产链现状（AgentSessionExecution.scala 请求构造点）：
  // messages = 持久历史 H；requestOnlyContext = 请求级提醒 R；systemDynamic = 真锚。

  private def userText(t: String): Message = Message(MessageRole.User, Left(t))
  private def asstText(t: String): Message = Message(MessageRole.Assistant, Left(t))
  private def toolUse(id: String): Message =
    Message(MessageRole.Assistant, Right(List(ContentBlock.ToolUse(id, "Read", JsonObject.empty))))
  private def toolResult(id: String, t: String): Message =
    Message(MessageRole.User, Right(List(ContentBlock.ToolResult(id, t, Some(false)))))

  /** 生产 contextMsg 形态（SystemReminder.renderAll 包裹的 user 文本）。 */
  private def contextReminder(body: String): Message =
    Message(MessageRole.User, Left(s"<system-reminder>\n$body\n</system-reminder>"))

  private val h1: List[Message] = List(
    userText("inspect the repo"),
    toolUse("call_1"),
    toolResult("call_1", "file contents v1")
  )
  private val h2: List[Message] = h1 :+ asstText("done, summary v1")
  private val h3: List[Message] = h2 ++ List(toolUse("call_2"), toolResult("call_2", "file contents v2"))

  /** schedule 提醒（root 会话真实形态）：出现 → 变化 → 消失。 */
  private val r1 = List(contextReminder("schedule: 1 pending task (report at 22:00)"))
  private val r2 = List(contextReminder("schedule: 2 pending tasks (report at 22:00, backup at 23:00)"))
  private val r3 = List.empty[Message]

  /** 每轮真实换 epoch 的时间锚（禁固定字符串夹具）。 */
  private def clock(turn: Int): Option[String] =
    Some(TimeAnchor.block(TimeAnchor.at(1793970000000L + turn * 61000L)))

  /** 生产装配形态：持久历史与 REQUEST-ONLY 提醒段分离（本批修复后的形状）。 */
  private def production(h: List[Message], r: List[Message], turn: Int): SendMessageParams =
    SendMessageParams(
      messages = h,
      model = "glm-5.3-flash",
      tools = Some(List(ToolDefinition("Read", "read a file", JsonObject.empty))),
      systemStable = Some("STABLE-SYSTEM-PROMPT"),
      systemDynamic = clock(turn),
      requestOnlyContext = r
    )

  private def paramsNoDyn(msgs: List[Message]): SendMessageParams =
    production(msgs, Nil, turn = 0).copy(systemDynamic = None)

  // ── 结构指纹（剥 cache_control：marker 元数据移动 ≠ 语义前缀变更）─────

  private def hashString(s: String): Long =
    var h = 0xcbf29ce484222325L
    var i = 0
    while i < s.length do
      h = (h ^ s.charAt(i).toLong) * 0x100000001b3L
      i += 1
    h

  private def hashJson(j: Json): Long =
    def mix(acc: Long, v: Long): Long = (acc ^ v) * 0x100000001b3L
    j.fold(
      mix(0xcbf29ce484222325L, 1L),
      b => mix(0xcbf29ce484222325L, if b then 2L else 3L),
      n => mix(0xcbf29ce484222325L, hashString(n.toString)),
      s => mix(0xcbf29ce484222325L, hashString(s)),
      arr => arr.foldLeft(0xcbf29ce484222325L)((a, x) => mix(a, hashJson(x))),
      obj =>
        obj.toList.foldLeft(0xcbf29ce484222325L) { case (a, (k, v)) =>
          if k == "cache_control" then a else mix(mix(a, hashString(k)), hashJson(v))
        }
    )

  /** wire body 的全部消息内容块（跨消息拍平，按 wire 顺序）。 */
  private def blocksOf(body: Json): Vector[Json] =
    body.hcursor.downField("messages").focus.map(_.asArray.getOrElse(Vector.empty)).getOrElse(Vector.empty)
      .flatMap(m => m.hcursor.downField("content").focus.flatMap(_.asArray).getOrElse(Vector.empty))

  private def markedIndex(blocks: Vector[Json]): Int =
    blocks.lastIndexWhere(_.hcursor.downField("cache_control").focus.isDefined)

  // ── mock 前缀缓存：严格建模「只有真实断点才写入」──────────────────────
  // Anthropic 语义：缓存条目只写在请求声明的 cache_control 断点处；下一轮要
  // 复用，必须在新请求里找到与已存条目**逐块相等的前缀**（本模型按块指纹比对，
  // 不含 marker 元数据）。禁止「任意稳定前缀自动命中」的天真假设。

  private class MockPrefixCache:
    private val entries = mutable.Map.empty[Int, Long]
    def writeAtBreakpoint(blocks: Vector[Json]): Unit =
      val marked = markedIndex(blocks)
      // Anthropic 语义：断点覆盖**含标记块在内的闭前缀**（prefix = blocks[0..marked]）
      if marked >= 0 then entries.update(marked + 1, prefixHash(blocks, marked + 1))
    def hitLen(blocks: Vector[Json], marked: Int): Int =
      (1 to math.min(marked + 1, blocks.length)).filter(l => entries.get(l).exists(_ == prefixHash(blocks, l))).maxOption
        .getOrElse(0)
    private def prefixHash(blocks: Vector[Json], len: Int): Long =
      blocks.take(len).foldLeft(0xcbf29ce484222325L)((a, b) => (a ^ hashJson(b)) * 0x100000001b3L)

  // ── C1+C2+C3（Anthropic 面，流/非流）：断点落位 + 跨轮命中 ───────────

  test("①②③ anthropic ×流/非流: 断点必须在持久历史边界；下一轮必须命中此前写过的持久前缀"):
    for streaming <- List(false, true) do
      val cache = new MockPrefixCache
      val turns = List((h1, r1), (h2, r2), (h3, r3))
      val bodies = turns.zipWithIndex.map { case ((h, r), i) => anthropic.bodyOf(production(h, r, i), streaming) }
      val seqs            = bodies.map(b => blocksOf(b))
      val marks           = seqs.map(markedIndex)
      val persistedCounts = turns.map { case (h, _) => blocksOf(anthropic.bodyOf(paramsNoDyn(h), streaming)).length }
      val persistedTails =
        turns.map { case (h, _) => blocksOf(anthropic.bodyOf(paramsNoDyn(h), streaming)).last }

      // C1a: 每轮断点存在且落在持久历史的最后一块上（不是临时提醒块）
      turns.indices.foreach { i =>
        assert(marks(i) >= 0, s"turn${i + 1}: no message-level breakpoint")
        assertEquals(
          marks(i),
          persistedCounts(i) - 1,
          s"turn${i + 1}: breakpoint must sit on the LAST PERSISTED block (persisted=${persistedCounts(i)}), got ${marks(i)} — ephemeral tail got marked (C1 红)"
        )
        assertEquals(
          hashJson(seqs(i)(marks(i))),
          hashJson(persistedTails(i)),
          s"turn${i + 1}: marked block must be the persisted tail block (C1 红)"
        )
      }

      // C3: 时间锚每轮真实变化（临时段存在且逐轮不同——夹具不许复用固定 dynamic）
      val clockTexts = bodies.map { b =>
        blocksOf(b).last.hcursor.get[String]("text").toOption.getOrElse("")
      }
      assert(clockTexts(0).contains("epoch_ms"), "request-level clock anchor must be present")
      assert(clockTexts(0) != clockTexts(1) && clockTexts(1) != clockTexts(2), "clock must re-anchor per turn")

      // C2: 下一轮命中上一轮写下的持久边界（mock 只认真实断点写入）
      var prevPersistedCount = 0
      turns.indices.foreach { i =>
        if i > 0 then
          val hit = cache.hitLen(seqs(i), marks(i))
          assert(
            hit >= prevPersistedCount,
            s"turn${i + 1}: must find turn${i}'s persisted boundary in cache (expected ≥$prevPersistedCount blocks, hit=$hit) — H+R was written, H alone is unreachable (C2 红)"
          )
        cache.writeAtBreakpoint(seqs(i))
        prevPersistedCount = persistedCounts(i)
      }

  // ── C3（Anthropic 面）：已有历史前缀跨轮逐块不变 ─────────────────────

  test("③ anthropic: appending turns leaves every earlier persisted block unchanged (modulo marker)"):
    val turns  = List((h1, r1), (h2, r2), (h3, r3))
    val bodies = turns.zipWithIndex.map { case ((h, r), i) => anthropic.bodyOf(production(h, r, i), streaming = false) }
    val seqs   = bodies.map(b => blocksOf(b))
    val pc     = turns.map { case (h, _) => blocksOf(anthropic.bodyOf(paramsNoDyn(h), streaming = false)).length }
    // 上一轮持久块在本轮 wire 上逐块指纹相等（位置不变）
    for i <- 1 until turns.length do
      val prevBlocks = seqs(i - 1).take(pc(i - 1))
      prevBlocks.zipWithIndex.foreach { case (pb, bi) =>
        assertEquals(hashJson(seqs(i)(bi)), hashJson(pb), s"turn${i + 1} block[$bi] changed vs turn${i} persisted prefix")
      }
    // marker 移动 ≠ 语义变更：剥 cache_control 后上一轮持久块逐字节可复现
    assertEquals(stripMarkers(seqs(1).take(pc(0))), stripMarkers(seqs(0).take(pc(0))),
      "persisted prefix bytes changed across turns (beyond marker metadata)")

  private def stripMarkers(blocks: Vector[Json]): Vector[Json] = blocks.map(stripMarker)
  private def stripMarker(json: Json): Json = json.fold(
    json,
    _ => json,
    _ => json,
    _ => json,
    arr => Json.fromValues(arr.map(stripMarker).toList),
    obj =>
      val filtered = JsonObject.fromIterable(obj.toList.collect { case (k, v) if k != "cache_control" => k -> stripMarker(v) })
      Json.fromJsonObject(filtered)
  )

  // ── C4（Anthropic 面）：临时段不进持久历史；断点预算不超限 ────────────

  test("④ anthropic: ephemeral tail stays after the breakpoint; marker budget ≤ endpoint limit"):
    val body   = anthropic.bodyOf(production(h1, r1, 0), streaming = false)
    val n      = countMarkersDeep(body)
    assert(n <= 4, s"marker budget: got $n")
    val blocks = blocksOf(body)
    val marked = markedIndex(blocks)
    // 断点之后的块 = 全部临时段（提醒 + 时间锚），且自身不带 marker
    blocks.drop(marked + 1).zipWithIndex.foreach { case (b, k) =>
      assert(b.hcursor.downField("cache_control").focus.isEmpty, s"ephemeral block[$k] must not carry a marker")
    }
    assert(blocks.drop(marked + 1).exists(_.hcursor.get[String]("text").toOption.exists(_.contains("<system-reminder>"))),
      "request-only reminder must be on the wire after the breakpoint")
    assert(blocks.drop(marked + 1).exists(_.hcursor.get[String]("text").toOption.exists(_.contains("epoch_ms"))),
      "request-level clock must be on the wire after the breakpoint")
    // C4: 请求级提醒不在 params.messages（持久历史）里
    assert(production(h1, r1, 0).messages == h1, "request-only reminders must never ride in params.messages")

  private def countMarkersDeep(json: Json): Int =
    json.fold(0, _ => 0, _ => 0, _ => 0, arr => arr.foldLeft(0)((acc, j) => acc + countMarkersDeep(j)), obj =>
      val self = if obj("cache_control").isDefined then 1 else 0
      obj.values.foldLeft(self)((acc, j) => acc + countMarkersDeep(j))
    )

  // ── C2/C3（OpenAI 面）：隐式前缀缓存 — 持久历史逐条目保持，临时段只在尾部 ──

  test("⑤ openai ×passback: persisted history list preserved across turns; ephemeral only at the tail"):
    for passback <- List(false, true) do
      val turns          = List((h1, r1), (h2, r2), (h3, r3))
      val fulls          = turns.zipWithIndex.map { case ((h, r), i) => openai.requestMessages(production(h, r, i), passback) }
      val persistedLists = turns.map { case (h, _) => openai.requestMessages(paramsNoDyn(h), passback) }
      // 上一轮已发的持久列表在本轮逐条目原样保留（隐式缓存复用面）
      for i <- 1 until turns.length do
        val pre = persistedLists(i - 1)
        val cur = fulls(i).take(pre.length)
        assertEquals(cur, pre, s"turn${i + 1}: first ${pre.length} wire entries changed (openai face, passback=$passback)")
      // 临时段只出现在持久段之后（顺序：提醒段 → 时间锚）
      turns.zipWithIndex.foreach { case ((_, r), i) =>
        val tailCount    = fulls(i).length - persistedLists(i).length
        val expectedTail = r.map(_.textContent).count(_.nonEmpty) + clock(i).count(_.nonEmpty)
        assertEquals(tailCount, expectedTail, s"turn${i + 1}: ephemeral tail entry count (openai face)")
        if r.nonEmpty then
          assert(
            fulls(i).drop(persistedLists(i).length).exists(_.hcursor.get[String]("content").toOption.exists(_.contains("<system-reminder>"))),
            s"turn${i + 1}: request-only reminder must be in the ephemeral tail (openai face)"
          )
      }

  // ── ⑥ 录制型 mock backend：真实 sendMessage 发送体捕获（流/非流）──────
  // 补充令 §二.1：至少一条用录制型 mock backend 捕获**实际发送体**（不是只测
  // bodyOf helper）。本地 HttpServer 原样回放响应体，断言真 wire 的断点落位。

  test("⑥ anthropic ×流/非流: recorded mock backend — actual wire body puts the breakpoint on the persisted tail"):
    for streaming <- List(false, true) do
      val seenBody = new java.util.concurrent.atomic.AtomicReference[String]("")
      val respBody =
        if streaming then
          "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}\n\n" +
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"OK\"}}\n\n" +
            "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
        else
          """{"id":"m","type":"message","role":"assistant","content":[{"type":"text","text":"OK"}],""" +
            """"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}"""
      val server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0)
      server.createContext(
        "/v1/messages",
        (ex: com.sun.net.httpserver.HttpExchange) =>
          val bytes = ex.getRequestBody.readAllBytes()
          seenBody.set(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
          val resp = respBody.getBytes(java.nio.charset.StandardCharsets.UTF_8)
          ex.getResponseHeaders.add("Content-Type", if streaming then "text/event-stream" else "application/json")
          ex.sendResponseHeaders(200, if streaming then 0 else resp.length.toLong)
          val os = ex.getResponseBody
          os.write(resp)
          os.flush()
          os.close()
          ex.close()
      )
      server.start()
      try
        val backend = sttp.client4.httpclient.fs2.HttpClientFs2Backend
          .usingClient[IO](
            java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).build(),
            cats.effect.std.Dispatcher.parallel[IO].allocated.unsafeRunSync()._1
          )
        val adapter = AnthropicAdapter(
          providerId = "mock",
          apiKey = "sk-test",
          baseUrl = s"http://127.0.0.1:${server.getAddress.getPort}/",
          backend = backend,
          providerHeaders = None
        )
        if streaming then
          adapter.sendMessageStream(production(h1, r1, 0)).compile.drain.unsafeRunSync()
        else
          adapter.sendMessage(production(h1, r1, 0)).unsafeRunSync()
        val wire  = io.circe.parser.parse(seenBody.get()).getOrElse(Json.Null)
        val wireBlocks = blocksOf(wire)
        val marked = markedIndex(wireBlocks)
        val persisted = blocksOf(anthropic.bodyOf(paramsNoDyn(h1), streaming = false))
        assert(wireBlocks.nonEmpty, "mock server captured no body")
        assertEquals(marked, persisted.length - 1,
          s"actual wire breakpoint must sit on the persisted tail (streaming=$streaming), got $marked of ${wireBlocks.length}")
        assert(
          wireBlocks.drop(marked + 1).exists(_.hcursor.get[String]("text").toOption.exists(_.contains("<system-reminder>"))),
          s"actual wire must carry the reminder AFTER the breakpoint (streaming=$streaming)"
        )
      finally server.stop(0)

end CachePrefixAssemblySpec
