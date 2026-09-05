package nebflow.core

import cats.effect.unsafe.implicits.global
import io.circe.parser
import munit.FunSuite

import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import scala.jdk.CollectionConverters.*

/** 方案 B（审计 20260903 §5）：ToolsLogWriter 单元级验收——写入格式 ①、按天
  * 滚动 ②、保留 prune ③、异步不阻塞 ④。真链路（真实 AgentCore）见
  * nebflow.agent.ToolsLogAgentCoreSpec ⑤。 */
class ToolsLogWriterSpec extends FunSuite:

  private val fixedKeys =
    Set("ts", "tool", "agent", "sessionId", "kind", "isError", "elapsedMs", "errorText", "inputSummary", "resultChars", "requestId")

  private def tmpDir(): Path = Files.createTempDirectory("tools-log-spec-")

  private def todayStr: String = Instant.now().toString.take(10)

  private def readLines(dir: Path, date: String): List[String] =
    val p = dir.resolve(s"$date.jsonl")
    if Files.exists(p) then Files.readAllLines(p).asScala.toList.filter(_.nonEmpty) else Nil

  private def parseLine(line: String): io.circe.Json =
    parser.parse(line).toOption.fold(fail(s"unparseable JSONL line: ${line.take(120)}"))(identity)

  override def afterEach(context: munit.AfterEach): Unit =
    ToolsLogWriter.setWriteDelayMsForTest(0)
    ToolsLogWriter.flushSync()
    ToolsLogWriter.resetClockForTest()
    ToolsLogWriter.resetDirForTest()
    super.afterEach(context)

  // ── ① 写入格式：字段齐全类型正确 ─────────────────────────────────────

  test("format: all 11 fixed keys, success row (isError=false, errorText=\"\", resultChars>0)") {
    val dir = tmpDir()
    ToolsLogWriter.setDirForTest(dir)
    ToolsLogWriter
      .log(
        tool = "Read",
        agent = Some("Nebula"),
        sessionId = Some("sess-fmt"),
        kind = Some("Root"),
        isError = false,
        elapsedMs = 12L,
        errorText = "",
        inputSummary = "Read(hello.txt)",
        resultChars = 345,
        requestId = Some("req-fmt-1")
      )
      .unsafeRunSync()
    ToolsLogWriter.flushSync()

    val lines = readLines(dir, todayStr)
    assertEquals(lines.size, 1)
    val json = parseLine(lines.head)
    assertEquals(json.asObject.get.keys.toSet, fixedKeys, "fixed key set — every key present, no extras")
    assertEquals(json.hcursor.get[String]("tool").toOption, Some("Read"))
    assertEquals(json.hcursor.get[String]("agent").toOption, Some("Nebula"))
    assertEquals(json.hcursor.get[String]("sessionId").toOption, Some("sess-fmt"))
    assertEquals(json.hcursor.get[String]("kind").toOption, Some("Root"))
    assertEquals(json.hcursor.get[Boolean]("isError").toOption, Some(false))
    assertEquals(json.hcursor.get[Long]("elapsedMs").toOption, Some(12L))
    assertEquals(json.hcursor.get[String]("errorText").toOption, Some(""))
    assertEquals(json.hcursor.get[String]("inputSummary").toOption, Some("Read(hello.txt)"))
    assert(json.hcursor.get[Int]("resultChars").toOption.exists(_ > 0), "success row resultChars > 0")
    assertEquals(json.hcursor.get[String]("requestId").toOption, Some("req-fmt-1"))
    // ts: ISO8601 with fixed milliseconds, sortable
    val ts = json.hcursor.get[String]("ts").toOption.get
    assert(ts.matches("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z"""), s"ts ISO8601 millis: $ts")
  }

  test("format: failure row keeps FULL error text (2500 chars, no truncation); absent optionals are null") {
    val dir = tmpDir()
    ToolsLogWriter.setDirForTest(dir)
    val bigError = "ERR-" + ("x" * 2496) // 2500 chars total
    ToolsLogWriter
      .log(
        tool = "Bash",
        agent = None,
        sessionId = None,
        kind = None,
        isError = true,
        elapsedMs = 3L,
        errorText = bigError,
        inputSummary = "Bash(long-failing-cmd)",
        resultChars = 2500,
        requestId = None
      )
      .unsafeRunSync()
    ToolsLogWriter.flushSync()

    val json = parseLine(readLines(dir, todayStr).head)
    assertEquals(json.hcursor.get[Boolean]("isError").toOption, Some(true))
    val stored = json.hcursor.get[String]("errorText").toOption.get
    assertEquals(stored.length, 2500, "errorText NOT truncated")
    assertEquals(stored, bigError, "errorText byte-identical to source")
    // 非 LLM 触发：requestId / agent / sessionId / kind 值可空，但 key 必在
    assertEquals(json.hcursor.get[Option[String]]("requestId").toOption, Some(None))
    assert(json.asObject.get.keys.exists(_ == "requestId"))
    assert(json.asObject.get.keys.exists(_ == "agent"))
  }

  // ── ② 按天滚动（注入时钟）────────────────────────────────────────────

  test("daily rollover: injected clock crossing midnight splits lines across date files") {
    val dir = tmpDir()
    val clock = AtomicLong(Instant.parse("2030-01-01T23:30:00Z").toEpochMilli)
    ToolsLogWriter.setDirForTest(dir)
    ToolsLogWriter.setClockForTest(() => Instant.ofEpochMilli(clock.get()))

    def one(i: Int) = ToolsLogWriter
      .log("Read", Some("a"), Some("s"), None, isError = false, elapsedMs = 1, "", s"Read(f$i)", 10, None)
      .unsafeRunSync()
    one(1)
    ToolsLogWriter.flushSync()
    assertEquals(readLines(dir, "2030-01-01").size, 1, "first line in day-1 file")

    clock.addAndGet(26 * 3600 * 1000L) // +26h → 2030-01-03T01:30Z
    one(2)
    ToolsLogWriter.flushSync()
    assertEquals(readLines(dir, "2030-01-01").size, 1, "day-1 file untouched")
    assertEquals(readLines(dir, "2030-01-03").size, 1, "second line in day-2 file (date-named)")
    val ts2 = parseLine(readLines(dir, "2030-01-03").head).hcursor.get[String]("ts").toOption.get
    assert(ts2.startsWith("2030-01-03T01:30"), s"injected clock drives ts: $ts2")
  }

  // ── ③ 保留 prune（与 router 对齐）────────────────────────────────────

  test("retention aligned with router default (3 days); old files pruned, recent kept") {
    assertEquals(ToolsLogWriter.retentionDays, LlmLogWriter.retentionDays, "shared retention constant")
    assertEquals(ToolsLogWriter.retentionDays, 3, "router 现行默认保留天数")

    val dir = tmpDir()
    ToolsLogWriter.setDirForTest(dir)
    ToolsLogWriter.setClockForTest(() => Instant.parse("2030-01-10T10:00:00Z"))
    // 预置旧文件（cutoff = T-3d = 2030-01-07）
    Files.writeString(dir.resolve("2030-01-01.jsonl"), "{\"tool\":\"ancient\"}\n") // T-9d → 删
    Files.writeString(dir.resolve("2030-01-08.jsonl"), "{\"tool\":\"recent\"}\n")  // T-2d → 留
    // 触发一次真实写入 → maybePrune 在 append 后运行
    ToolsLogWriter
      .log("Read", None, None, None, isError = false, elapsedMs = 1, "", "Read(x)", 5, None)
      .unsafeRunSync()
    ToolsLogWriter.flushSync()

    assert(!Files.exists(dir.resolve("2030-01-01.jsonl")), "file older than cutoff pruned")
    assert(Files.exists(dir.resolve("2030-01-08.jsonl")), "file within retention kept")
    assert(Files.exists(dir.resolve("2030-01-10.jsonl")), "today's file kept")
  }

  // ── ④ 异步：入队不阻塞 + 队列满丢弃 WARN ─────────────────────────────

  test("async: log() returns before disk write (enqueue never blocks on I/O)") {
    val dir = tmpDir()
    ToolsLogWriter.setDirForTest(dir)
    // 让后台写入延迟 500ms —— log() 返回时文件必然尚未落盘
    ToolsLogWriter.setWriteDelayMsForTest(500)
    val t0 = System.nanoTime()
    ToolsLogWriter
      .log("Read", None, None, None, isError = false, elapsedMs = 1, "", "Read(async)", 5, None)
      .unsafeRunSync()
    val enqueueMs = (System.nanoTime() - t0) / 1_000_000
    assert(enqueueMs < 250, s"log() returned in ${enqueueMs}ms — enqueue does not block on the 500ms disk write")
    // 落盘尚未发生
    assert(!Files.exists(dir.resolve(todayStr)), "line not yet on disk right after enqueue")
    // 随后台完成而出现（证明写入确实发生，只是异步）
    val deadline = System.currentTimeMillis() + 5000
    while System.currentTimeMillis() < deadline && readLines(dir, todayStr).isEmpty do
      Thread.sleep(50)
    assertEquals(readLines(dir, todayStr).size, 1, "line lands on disk via background writer")
  }

  test("async: queue-full drops with WARN, log() keeps returning (main path unblocked)") {
    val dir = tmpDir()
    ToolsLogWriter.setDirForTest(dir)
    // 写入极慢 → 队列只进不出，填满 Capacity 后开始丢弃
    ToolsLogWriter.setWriteDelayMsForTest(3000)

    // 捕获 WARN（logback ListAppender，不碰真实日志文件）
    val lbLogger = org.slf4j.LoggerFactory.getLogger("nebflow.tools.logger").asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]()
    appender.start()
    lbLogger.addAppender(appender)

    val offered = ToolsLogWriter.capacityForTest + 500
    val t0 = System.nanoTime()
    try
      for i <- 1 to offered do
        ToolsLogWriter
          .log("Bash", None, None, None, isError = true, elapsedMs = 1, s"err-$i", s"Bash(cmd-$i)", 1, None)
          .unsafeRunSync() // 每次调用都必须正常返回——绝不阻塞主路径
    finally
      val fillMs = (System.nanoTime() - t0) / 1_000_000
      assert(fillMs < 60_000, s"$offered enqueues took ${fillMs}ms — all returned without blocking")

      val warns = appender.list.asScala.count(_.getFormattedMessage.contains("queue full"))
      assert(warns >= 1, s"expected >=1 queue-full WARN, got ${appender.list.asScala.toList.map(_.getFormattedMessage)}")
      lbLogger.detachAppender(appender)
      appender.stop()

      ToolsLogWriter.setWriteDelayMsForTest(0)
      ToolsLogWriter.flushSync()
      val onDisk = readLines(dir, todayStr).size
      assert(onDisk <= ToolsLogWriter.capacityForTest + 1, s"overflow dropped: $onDisk lines on disk (capacity ${ToolsLogWriter.capacityForTest})")
  }

end ToolsLogWriterSpec
