package nebflow.core

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.parser.parse
import munit.FunSuite
import nebflow.shared.*

import java.nio.file.{Files, Path}
import java.time.Instant
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * 审计 20260903 子项⑤——router 日志逐事件落盘（观测性，对齐「日志完整性
 * 优先」裁定）：
 *
 * 旧批量模式：流收集完成后一次性写 request/response/全部 SSE 行——时间戳
 * 均为写盘时刻，request→response 实测全部 <1.2s 而 output_tokens 高达
 * 2 万，首 token 延迟/chunk 间隙无法实测（90s/120s 阈值复核因此无数据）。
 *
 * 新三段式：logRequest（流派发时）→ logStreamEvent（每 chunk 到达即落，
 * ts=到达时刻）→ logResponse（流结束时，附 request_id）。写路径异步
 * （有界队列+后台 fiber，ToolsLogWriter 模式）——吞吐零回归（T4）。
 *
 * 变异验红：logStreamEvent 改回写盘时刻取时间戳（批量模式形态）→ T2 红
 * （首 token 行与完成行时间戳差 ≈0 不可区分）。
 */
class LlmLogWriterStreamSpec extends FunSuite:

  private var dir: Path = null

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    dir = Files.createTempDirectory("llm-log-stream-spec")
    LlmLogWriter.setLogDirForTest(dir)
    LlmLogWriter.setEnabled(true)

  override def afterEach(context: AfterEach): Unit =
    LlmLogWriter.resetLogDirForTest()
    LlmLogWriter.flushSync()
    LlmLogWriter.setEnabled(true)
    // best-effort cleanup
    try
      Files.walk(dir).sorted(java.util.Comparator.reverseOrder[Path]()).forEach(p => Files.deleteIfExists(p))
    catch case _: Throwable => ()
    super.afterEach(context)

  private def readLines(suffix: String): List[String] =
    val today = Instant.now().toString.take(10)
    val path = dir.resolve(s"${today}_$suffix.jsonl")
    if Files.exists(path) then Files.readAllLines(path).asScala.toList.filter(_.nonEmpty)
    else Nil

  private def requestFixture: LlmRequest =
    LlmRequest(
      messages = List(Message(MessageRole.User, Left("hi"))),
      sessionId = "stream-log-spec",
      agentId = "stream-log-agent",
      systemStable = Some("sys")
    )

  private def parseLine(line: String): io.circe.Json =
    parse(line).toOption.getOrElse(fail(s"non-JSON log line: $line"))

  private def tsOf(json: io.circe.Json): Instant =
    json.hcursor.get[String]("timestamp").toOption
      .map(Instant.parse)
      .getOrElse(fail(s"no timestamp in: ${json.noSpaces.take(200)}"))

  test("T1: logRequest writes request line at dispatch; logResponse appends the response line after") {
    LlmLogWriter.logRequest(requestFixture, "req-T1", isSubagent = false, isCompaction = false).unsafeRunSync()
    LlmLogWriter.logResponse(
      requestId = "req-T1",
      resultText = "hello",
      resultToolCalls = Nil,
      resultThinking = None,
      resultStopReason = Some("end_turn"),
      resultUsage = Some(TokenUsage(10, 20000)),
      resultModel = Some("test-model")
    ).unsafeRunSync()

    val summary = readLines("summary").map(parseLine)
    assertEquals(summary.count(_.hcursor.get[String]("type").contains("request")), 1)
    assertEquals(summary.count(_.hcursor.get[String]("type").contains("response")), 1)
    // request 行在流派发时落盘：ts 与 response 行来自真实时序（非同刻批量）。
    val reqLine = summary.find(_.hcursor.get[String]("type").contains("request")).get
    assertEquals(reqLine.hcursor.get[String]("request_id").toOption, Some("req-T1"))
    // full 文件也落了 request+response 两行（对象引用面）。
    assertEquals(readLines("full").size, 2)
  }

  test("T2: per-event sse lines carry arrival-time timestamps — first-token vs Done are distinguishable") {
    val encoder = new LlmLogWriter.StreamEventEncoder("req-T2", "stream-log-agent")
    // 首 token 行：t0
    LlmLogWriter.logStreamEvent(encoder, StreamChunk.TextDelta("first-token")).unsafeRunSync()
    // 生成间隙：真实流逝 80ms（缩窗模拟长生成——严禁真实等待秒级）
    Thread.sleep(80)
    LlmLogWriter.logStreamEvent(encoder, StreamChunk.TextDelta("more")).unsafeRunSync()
    // 完成行：t2（meta 携带 model——逐事件 model 归因面）
    LlmLogWriter.logStreamEvent(
      encoder,
      StreamChunk.Done(Some("end_turn"), Some(TokenUsage(10, 20000)), None, None)
    ).unsafeRunSync()
    LlmLogWriter.flushSync()

    val sse = readLines("sse").map(parseLine)
    assertEquals(sse.size, 3, s"expected 3 per-event sse lines, got ${sse.size}")
    val first = tsOf(sse.head)
    val mid = tsOf(sse(1))
    val done = tsOf(sse(2))
    val gap1 = java.time.Duration.between(first, mid).toMillis
    assert(gap1 >= 50, s"first-token → next-event gap must reflect real arrival times (≥50ms), got ${gap1}ms — timestamps would be indistinguishable under batch flushing")
    assert(done.isAfter(mid), "Done line must be after the last delta")
    // 完成行（message_delta）与首 token 行（content_block_delta）类型可区分。
    assertEquals(sse.head.hcursor.get[String]("sse_event_type").toOption, Some("content_block_delta"))
    assertEquals(sse(2).hcursor.get[String]("sse_event_type").toOption, Some("message_delta"))
  }

  test("T3: tool-call chunk produces block-start + delta pair (index state kept across events)") {
    val encoder = new LlmLogWriter.StreamEventEncoder("req-T3", "stream-log-agent")
    val tc = ToolCall("toolu-1", "Bash", io.circe.JsonObject(("command", io.circe.Json.fromString("ls"))))
    LlmLogWriter.logStreamEvent(encoder, StreamChunk.ToolCallChunk(tc)).unsafeRunSync()
    LlmLogWriter.flushSync()
    val sse = readLines("sse").map(parseLine)
    assertEquals(sse.size, 2)
    assertEquals(sse.head.hcursor.get[String]("sse_event_type").toOption, Some("content_block_start"))
    assertEquals(sse(1).hcursor.get[String]("sse_event_type").toOption, Some("content_block_delta"))
  }

  test("T4: async write path — slow disk never blocks the caller (throughput non-regression)") {
    // 模拟慢盘：每行写盘耗时 60ms（ToolsLogWriter.writeDelayMsForTest 同款注入面）。
    LlmLogWriter.setWriteDelayMsForTest(60)
    try
      val encoder = new LlmLogWriter.StreamEventEncoder("req-T4", "stream-log-agent")
      val t0 = System.nanoTime()
      // 3 行入队；若同步写将 ≥180ms 阻塞调用方。
      for i <- 1 to 3 do
        LlmLogWriter.logStreamEvent(encoder, StreamChunk.TextDelta(s"chunk-$i")).unsafeRunSync()
      val elapsedMs = (System.nanoTime() - t0) / 1000000
      assert(
        elapsedMs < 50,
        s"logStreamEvent must return without waiting for the write path (async queue), blocked ${elapsedMs}ms"
      )
      LlmLogWriter.flushSync()
      assertEquals(readLines("sse").size, 3, "all lines land on disk after flushSync")
    finally LlmLogWriter.setWriteDelayMsForTest(0)
  }

  test("T5: in-flight counter never drifts negative — sync direct writes must not cancel flushSync's in-flight wait") {
    // pre-fix 形态：logRequest/logResponse 同步直写 appendJsonl 时无条件
    // decrement（从未 increment）→ 每次直写 -2，累计负基座让 flushSync 的
    // `pendingWrites > 0` 在飞行等待整体失效——worker「已take未append」窗口
    // 裸奔，高负载 CI 上 T2 偶发少行（期望 3 行只见 2）。
    LlmLogWriter.logRequest(requestFixture, "req-T5a", isSubagent = false, isCompaction = false)
      .unsafeRunSync()
    LlmLogWriter.logResponse(
      requestId = "req-T5a",
      resultText = "hello",
      resultToolCalls = Nil,
      resultThinking = None,
      resultStopReason = Some("end_turn"),
      resultUsage = Some(TokenUsage(1, 2)),
      resultModel = Some("m")
    ).unsafeRunSync()
    // 队列零积压：同步直写路径不得动队列在飞行计数（pre-fix 此处 = -4，红）。
    assertEquals(LlmLogWriter.ssePendingWritesForTest, 0L)

    // 队列路径照常记账：flushSync 后清零且行落盘。
    val encoder = new LlmLogWriter.StreamEventEncoder("req-T5", "stream-log-agent")
    LlmLogWriter.logStreamEvent(encoder, StreamChunk.TextDelta("inflight")).unsafeRunSync()
    LlmLogWriter.flushSync()
    assertEquals(LlmLogWriter.ssePendingWritesForTest, 0L, "flushSync must settle the in-flight counter")
    assertEquals(readLines("sse").count(_.contains("\"inflight\"")), 1)
  }
end LlmLogWriterStreamSpec
