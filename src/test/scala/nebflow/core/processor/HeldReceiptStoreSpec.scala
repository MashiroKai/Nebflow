package nebflow.core.processor

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.actor.AgentCommand
import nebflow.shared.{Defaults, PathUtil}

import java.nio.file.Files

/**
 * 平台修复批「delegate 回执投递可靠性」（作者令 2026-10-21）—— held 回执台账
 * （[[HeldReceiptStore]]）与重试策略（[[HeldDeliveryPolicy]]）的规格面。
 *
 * 断言面（fix 1/2 的判红锚）：
 *  - P1 receiptId：correlationId 优先、载荷摘要稳定（回执身份单点）；
 *  - P2 退避节奏：10min → ×2 → 30min 封顶（首催 =「N 分钟未投递」的 N）；
 *  - P3 到期/穷尽判定边界；
 *  - S1 台账对账：新 held 记 producedAt、已投递出账、簿记保真不重置；
 *  - S2 exhausted 终态落盘与停催（markAttempted/markExhausted/unread）；
 *  - S3 崩溃一致性：台账文件跨 load 往返无损。
 */
class HeldReceiptStoreSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  private def withDataRoot(name: String)(body: String => Unit): Unit =
    val tmp = Files.createTempDirectory(s"held-$name").toAbsolutePath.toString
    PathUtil.setDataRoot(os.Path(tmp))
    try body(s"session-$name")
    finally PathUtil.setDataRoot(originalRoot)

  private def event(payload: String, correlationId: Option[String] = Some("sub-1")): AgentCommand.ExternalEvent =
    AgentCommand.ExternalEvent("delegate", "completed", payload, correlationId = correlationId)

  // ── P1 回执身份 ────────────────────────────────────────────────────────────

  test("P1a receiptId 以 correlationId 为身份（delegate/subtask 终态恒带子会话 id）"):
    assertEquals(HeldDeliveryPolicy.receiptId(event("payload A")), "sub-1")
    assertEquals(HeldDeliveryPolicy.receiptId(event("payload B", Some("sub-2"))), "sub-2")

  test("P1b 无 correlationId ⇒ 载荷摘要兜底，同载荷稳定、异载荷不同"):
    val a1 = HeldDeliveryPolicy.receiptId(event("same body", None))
    val a2 = HeldDeliveryPolicy.receiptId(event("same body", None))
    val b = HeldDeliveryPolicy.receiptId(event("other body", None))
    assertEquals(a1, a2, "same payload ⇒ same id (deterministic)")
    assert(a1.nonEmpty && b.nonEmpty && a1 != b, "different payloads ⇒ different ids")

  // ── P2 退避节奏 ────────────────────────────────────────────────────────────

  test("P2a 退避节奏 10min → 20 → 30 封顶（首催即「N 分钟未投递」的 N）"):
    assertEquals(HeldDeliveryPolicy.delayMs(1), Defaults.HeldDeliveryFirstRetryMs)
    assertEquals(HeldDeliveryPolicy.delayMs(2), Defaults.HeldDeliveryFirstRetryMs * 2)
    assertEquals(HeldDeliveryPolicy.delayMs(3), Defaults.HeldDeliveryMaxBackoffMs)
    assertEquals(HeldDeliveryPolicy.delayMs(9), Defaults.HeldDeliveryMaxBackoffMs, "cap holds at max backoff")

  test("P2b isDue 边界：恰好到期即催，未到期不催"):
    val t0 = 1_000_000L
    val rec = HeldReceiptStore.HeldReceipt("r1", "delegate", "completed", "s", producedAt = t0, attempts = 0)
    assert(!HeldDeliveryPolicy.isDue(rec, t0 + Defaults.HeldDeliveryFirstRetryMs - 1))
    assert(HeldDeliveryPolicy.isDue(rec, t0 + Defaults.HeldDeliveryFirstRetryMs))
    val attempted = rec.copy(attempts = 2)
    assert(HeldDeliveryPolicy.isDue(attempted, t0 + Defaults.HeldDeliveryFirstRetryMs * 4))

  test("P2c 穷尽判定：下一次催注将超 HeldDeliveryMaxAttempts ⇒ 置终态停催"):
    val edge = HeldReceiptStore.HeldReceipt(
      "r",
      "delegate",
      "completed",
      "s",
      producedAt = 0L,
      attempts = Defaults.HeldDeliveryMaxAttempts
    )
    assert(HeldDeliveryPolicy.shouldExhaust(edge))
    val below = HeldReceiptStore.HeldReceipt(
      "r",
      "delegate",
      "completed",
      "s",
      producedAt = 0L,
      attempts = Defaults.HeldDeliveryMaxAttempts - 1
    )
    assert(!HeldDeliveryPolicy.shouldExhaust(below))

  // ── S1 台账对账 ────────────────────────────────────────────────────────────

  test("S1a syncFromEvents：新 held 记 producedAt、已投递出账、簿记跨对账保真"):
    withDataRoot("s1a") { sid =>
      val t0 = 10_000L
      val evA = event("A")
      val evB = event("B", Some("sub-2"))
      val first = HeldReceiptStore.syncFromEvents(sid, List(evA, evB), now = t0).unsafeRunSync()
      assertEquals(first.map(_.id), List("sub-1", "sub-2"))
      assertEquals(first.map(_.producedAt), List(t0, t0), "both rows stamped at hold time")

      // 交付 A（不在 held 集合）+ 新到 C：A 出账、B 簿记保真（producedAt 不重置）、C 新记。
      val evC = event("C", Some("sub-3"))
      val second = HeldReceiptStore.syncFromEvents(sid, List(evB, evC), now = t0 + 60_000).unsafeRunSync()
      assertEquals(second.map(_.id), List("sub-2", "sub-3"))
      assertEquals(second.head.producedAt, t0, "producedAt preserved across reconciles")
      assertEquals(second(1).producedAt, t0 + 60_000)

      // 全部投递 ⇒ 台账清空（文件删除，未读即已读）。
      val third = HeldReceiptStore.syncFromEvents(sid, Nil, now = t0 + 120_000).unsafeRunSync()
      assertEquals(third, Nil)
      assertEquals(HeldReceiptStore.load(sid).unsafeRunSync(), Nil)
    }

  // ── S2 催注记账 / 穷尽终态 ─────────────────────────────────────────────────

  test("S2a markAttempted 幂等键记账，markExhausted 置终态且幂等，unread 只报 exhausted"):
    withDataRoot("s2a") { sid =>
      val t0 = 10_000L
      val ev = event("held result")
      HeldReceiptStore.syncFromEvents(sid, List(ev), now = t0).unsafeRunSync()

      HeldReceiptStore.markAttempted(sid, List("sub-1"), t0 + 600_000).unsafeRunSync()
      assertEquals(HeldReceiptStore.load(sid).unsafeRunSync().head.attempts, 1)

      HeldReceiptStore.markAttempted(sid, List("unknown-id"), t0 + 700_000).unsafeRunSync()
      assertEquals(HeldReceiptStore.load(sid).unsafeRunSync().head.attempts, 1, "unknown id ignored")

      HeldReceiptStore.markExhausted(sid, List("sub-1")).unsafeRunSync()
      HeldReceiptStore.markExhausted(sid, List("sub-1")).unsafeRunSync()
      val rows = HeldReceiptStore.load(sid).unsafeRunSync()
      assertEquals(rows.count(_.exhausted), 1, "exhausted flag set exactly once")
      assertEquals(HeldReceiptStore.unread(sid).unsafeRunSync().map(_.id), List("sub-1"))
    }

  // ── S3 往返 / 容错 ───────────────────────────────────────────────────────

  test("S3a 缺键旧台账 / 损坏文件容错为 Nil（绝不抛）"):
    withDataRoot("s3a") { sid =>
      val f = PathUtil.dataRoot / "sessions" / sid / "held-receipts.json"
      os.makeDir.all(f / os.up)
      os.write.over(f, "not-json{{{")
      assertEquals(HeldReceiptStore.load(sid).unsafeRunSync(), Nil)
      os.write.over(f, """[{"id":"legacy"}]""")
      assertEquals(HeldReceiptStore.load(sid).unsafeRunSync(), Nil, "incomplete legacy row dropped")
      assert(HeldReceiptStore.syncFromEvents(sid, List(event("x")), now = 1L).unsafeRunSync().nonEmpty)
    }

  test("S3b empty sessionId 全面 no-op"):
    assertEquals(HeldReceiptStore.load("").unsafeRunSync(), Nil)
    HeldReceiptStore.markAttempted("", List("a"), 1L).unsafeRunSync()
    HeldReceiptStore.markExhausted("", List("a")).unsafeRunSync()
    HeldReceiptStore.clear("").unsafeRunSync()

end HeldReceiptStoreSpec
