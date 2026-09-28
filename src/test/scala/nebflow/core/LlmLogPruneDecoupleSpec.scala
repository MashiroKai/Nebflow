package nebflow.core

import cats.effect.unsafe.implicits.global
import io.circe.Json
import munit.FunSuite
import nebflow.shared.*

import java.nio.file.{Files, Path}
import java.time.Instant
import scala.jdk.CollectionConverters.*

/**
 * 回收腿与写入开关**解耦**（2026-09-13 批，`llmlogdefault` 的前向对齐）。
 *
 * 判红（作者点名）：
 *   ① **写入开关关**状态下 `logResponse` ⇒ prune **确实执行**（出窗件被删 + 孤儿被清）；
 *   ② 同一调用**写入面仍零新增**（解耦没有顺带打开写入面）；
 *   ③ **负控**：窗内被引用的 objects 必不被删；
 *   ④ 默认值 / 持久化面 / 保留窗常数（3 天）**未被本批改动**。
 *
 * 走的是真实调用链：`logResponse` → `pruneTick` → `maybePrune` → `retentionRound`
 * （唯一生产触发点 = `AgentCore` 里那次无条件 `logResponse` 调用）。
 */
class LlmLogPruneDecoupleSpec extends FunSuite:

  private var prevEnabled = true

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    prevEnabled = LlmLogWriter.isEnabled
    LlmLogWriter.resetLogDirForTest()
    LlmLogWriter.resetPruneStateForTest()
    LlmLogWriter.disarmRetentionForTest()

  override def afterEach(context: AfterEach): Unit =
    LlmLogWriter.flushSync()
    LlmLogWriter.resetLogDirForTest()
    LlmLogWriter.resetPruneStateForTest()
    LlmLogWriter.resetPruneRetryBackoffMsForTest()
    LlmLogWriter.disarmRetentionForTest()
    LlmLogWriter.setEnabled(prevEnabled)
    super.afterEach(context)

  private def today: String = Instant.now().toString.take(10)
  private def cutoff: String = Instant.now().minusSeconds(3 * 86400L).toString.take(10)
  private def outOfWindow: String = Instant.now().minusSeconds(9 * 86400L).toString.take(10)

  private def fullEntry(hash: String): String =
    s"""{"timestamp":"2026-09-01T00:00:00Z","type":"request","system_ref":"sys-$hash","tools_ref":"tools-$hash","message_refs":["m-$hash"]}\n"""

  private def objNames(dir: Path): List[String] =
    val o = dir.resolve("objects")
    if !Files.exists(o) then Nil
    else Files.list(o).iterator().asScala.toList.map(_.getFileName.toString).filter(_.endsWith(".json"))

  private def names(dir: Path): List[String] =
    Files.list(dir).iterator().asScala.toList.map(_.getFileName.toString).sorted

  private def touch(dir: Path, hash: String): Unit =
    Files.createDirectories(dir.resolve("objects"))
    Files.writeString(dir.resolve("objects").resolve(s"$hash.json"), "{}")

  private def response(): Unit =
    LlmLogWriter
      .logResponse(
        requestId = "req-decouple",
        resultText = "hello",
        resultToolCalls = Nil,
        resultThinking = None,
        resultStopReason = Some("end_turn"),
        resultUsage = Some(TokenUsage(10, 20000)),
        resultModel = Some("test-model")
      )
      .unsafeRunSync()

  test("① enabled=false: logResponse still prunes (out-of-window jsonl reclaimed, orphan swept)"):
    val dir = Files.createTempDirectory("llm-decouple-off-")
    LlmLogWriter.setLogDirForTest(dir)
    LlmLogWriter.setEnabled(false)
    LlmLogWriter.armRetention() // 实例武装（boot 语义）；写开关保持关
    // 出窗三件（删除腿目标）+ 窗内 full（引用面）+ 引用对象/孤儿对象
    for s <- List("summary", "full", "sse") do
      Files.writeString(dir.resolve(s"${outOfWindow}_$s.jsonl"), "{}")
    Files.writeString(dir.resolve(s"${today}_full.jsonl"), fullEntry("keep"))
    touch(dir, "sys-keep")
    touch(dir, "orphan")
    val before = names(dir)

    response() // ← 关态下的真实调用点

    val after = names(dir)
    assert(!after.exists(_.startsWith(outOfWindow)), s"out-of-window files reclaimed: $before → $after")
    assert(objNames(dir).contains("sys-keep.json"), "negative control: in-window referenced object kept")
    assert(!objNames(dir).contains("orphan.json"), "orphan swept once the pass completed")
    assertEquals(
      LlmLogWriter.pruneStateForTest.cutoff,
      cutoff,
      "retention window = now − 3 days (retentionDays unchanged)"
    )

  test("①b UN-armed process never prunes (spec/script on a real dataRoot can never delete logs)"):
    val dir = Files.createTempDirectory("llm-decouple-unarmed-")
    LlmLogWriter.setLogDirForTest(dir)
    LlmLogWriter.setEnabled(false)
    // 刻意**不**武装：模拟「未隔离 dataRoot 的 spec / e2e 脚本走完整轮次」的调用者
    Files.writeString(dir.resolve(s"${outOfWindow}_full.jsonl"), "{}")
    Files.writeString(dir.resolve(s"${today}_full.jsonl"), fullEntry("keep"))
    touch(dir, "orphan")
    response()
    response()
    assert(
      Files.exists(dir.resolve(s"${outOfWindow}_full.jsonl")),
      "un-armed ⇒ the destructive delete leg never runs (2026-09-13 incident regression guard)"
    )
    assert(objNames(dir).contains("orphan.json"), "un-armed ⇒ no orphan sweep")
    assert(!LlmLogWriter.isRetentionArmedForTest, "default state stays disarmed")


  test("② enabled=false: the write face stays at ZERO new lines/files"):
    val dir = Files.createTempDirectory("llm-decouple-nowrite-")
    LlmLogWriter.setLogDirForTest(dir)
    LlmLogWriter.setEnabled(false)
    Files.writeString(dir.resolve(s"${today}_full.jsonl"), fullEntry("keep"))
    touch(dir, "sys-keep")
    val bytesBefore = Files.size(dir.resolve(s"${today}_full.jsonl"))
    val objCountBefore = objNames(dir).size

    response()
    response()

    assertEquals(Files.size(dir.resolve(s"${today}_full.jsonl")), bytesBefore, "no request/response line appended")
    assert(!Files.exists(dir.resolve(s"${today}_summary.jsonl")), "no summary face created")
    assert(!Files.exists(dir.resolve(s"${today}_sse.jsonl")), "no sse face created")
    assert(objNames(dir).size <= objCountBefore, "no object stored while disabled")
    assertEquals(objNames(dir), List("sys-keep.json"), "only the pre-existing referenced object remains")

  test("③ contrast: with enabled=true the write face does land lines (② is not a vacuous pass)"):
    val dir = Files.createTempDirectory("llm-decouple-on-")
    LlmLogWriter.setLogDirForTest(dir)
    LlmLogWriter.setEnabled(true)
    LlmLogWriter
      .logRequest(
        LlmRequest(
          messages = List(Message(MessageRole.User, Left("hi"))),
          sessionId = "decouple-spec",
          agentId = "spec-agent",
          systemStable = Some("sys")
        ),
        requestId = "req-on",
        isSubagent = false,
        isCompaction = false
      )
      .unsafeRunSync()
    response()
    assert(Files.exists(dir.resolve(s"${today}_summary.jsonl")), "summary face written when enabled")
    assert(Files.exists(dir.resolve(s"${today}_full.jsonl")), "full face written when enabled")

  test("④ boundaries unchanged by this batch"):
    assertEquals(LlmLogWriter.retentionDays, 3, "保留窗数值（3 天）本批零改动")
    assertEquals(LlmLogWriter.DefaultEnabled, false, "默认值属 llmlogdefault 批，本批零改动")
    assertEquals(LlmLogWriter.configSection, "llmLog", "持久化落点节名本批零改动")
    assertEquals(LlmLogWriter.loadEnabled(Some(Json.obj("enabled" -> Json.fromBoolean(false)))), Some(false))
    assertEquals(LlmLogWriter.loadEnabled(None), None)
end LlmLogPruneDecoupleSpec
