package nebflow.db

import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite

import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

/**
 * V6/V8/V9 —— 恢复面：
 *  - V6 待审权限单不丢：挂起 Permission 卡 + kill -9（失锁模拟）+ 重启 ⇒ 单据重装可答；
 *    孤儿 ask 计数 == 0；
 *  - V8 guard 重启不归零；坏 guard ⇒ 保守向（全部仍在冷却）；
 *  - V9 追加腿耐久：PerRecord 档 ack 后失锁重启 ⇒ 已 ack 行不丢（真跑，不转引）。
 */
class DbRecoverySpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private def kernelEvent(n: Int): Json = Json.obj(
    "type" -> Json.fromString("node-created"),
    "occurredAt" -> Json.fromLong(1762224000000L + n),
    "sessionId" -> Json.Null,
    "seqInSession" -> Json.Null,
    "payload" -> Json.obj("summary" -> Json.fromString(s"recovery-$n"))
  )

  private def openDb(root: Path): DbHandle = Db.open(DbConfig(root)).unsafeRunSync()

  test("V6: permission card survives kill-9 + restart and is reinstalled; asks expire-closed"):
    val root = Files.createTempDirectory("db-recovery-pending")
    val a = openDb(root)
    val permSeq = a
      .openPending(PendingRecordTest.card("perm-1", kind = "Permission"))
      .unsafeRunSync()
    a.openPending(PendingRecordTest.card("ask-1", kind = "AskUser")).unsafeRunSync()
    a.openPending(PendingRecordTest.card("mcp-1", kind = "McpPermission")).unsafeRunSync()
    // kill -9：失锁（OS 锁随进程消失；flush 语义对 PerRecord 面无影响）。
    a.simulateCrash()
    // 重启 + 恢复腿。
    val b = openDb(root)
    try
      assertEquals(b.orphanOpenAsks().unsafeRunSync(), 1, "恢复前：ask-1 是孤儿")
      val plan = b.recoverPendingD8().unsafeRunSync()
      assertEquals(
        plan.reinstall.map(_.requestId).sorted,
        List("mcp-1", "perm-1"),
        "D-8 工作默认：Permission/McpPermission ⇒ 重装（不丢待审单）"
      )
      assertEquals(plan.expiredAsks.map(_.requestId), List("ask-1"), "AskUser ⇒ 过期关闭（补终态行）")
      assertEquals(b.orphanOpenAsks().unsafeRunSync(), 0, "V6 不变式：孤儿 ask == 0")
      // 重装卡仍 open（等待方可继续答）；ask 已闭。
      b.readPending(PendingStream.Permission).unsafeRunSync() match
        case ReadResult.Present(sr) =>
          assertEquals(sr.records.count(_.state == "open"), 2)
          assertEquals(sr.records.map(_.seq).max > permSeq, true)
        case other => fail(s"unexpected $other")
      b.readPending(PendingStream.Ask).unsafeRunSync() match
        case ReadResult.Present(sr) =>
          val ask = sr.records.filter(_.requestId == "ask-1")
          assertEquals(ask.last.state, "closed")
          assertEquals(ask.last.closeReason, Some("expired-by-restart"))
          assertEquals(ask.last.closedBy, Some("system"))
          assertEquals(ask.size, 2, "原 open 行仍在（决议是追加行，不改写）")
        case other => fail(s"unexpected $other")
      // 恢复幂等：再跑一次零动作。
      val plan2 = b.recoverPendingD8().unsafeRunSync()
      assertEquals(plan2.expiredAsks.size, 0)
      assertEquals(b.orphanOpenAsks().unsafeRunSync(), 0)
      b.verify().unsafeRunSync() match
        case r if r.ok => ()
        case r => fail(s"verify: $r")
    finally b.close()

    end try

  test("V8: notify-guard cooldown survives restart (never reset to zero)"):
    val root = Files.createTempDirectory("db-recovery-guard")
    val a = openDb(root)
    a.writeNotifyGuard(
      GuardDocsTest.notify(
        "dispatch-notify:failed",
        List(1762224000000L, 1762224005000L),
        cooldownUntil = 1762224300000L,
        updatedAt = 1762224005000L
      )
    ).unsafeRunSync()
    a.simulateCrash() // kill -9
    val b = openDb(root)
    try
      val recovered = b.recoverNotifyGuardConservative("dispatch-notify:failed", now = 1762224100000L).unsafeRunSync()
      assertEquals(recovered.cooldownUntil, 1762224300000L, "D-10：冷却窗不归零（现状 Ref.unsafe 内存态的反面）")
      assertEquals(recovered.failedAt, List(1762224000000L, 1762224005000L))
    finally b.close()

  test("V8: corrupt guard doc ⇒ conservative all-cooling + quarantined (宁可少发不可风暴)"):
    val root = Files.createTempDirectory("db-recovery-guard-bad")
    val a = openDb(root)
    a.simulateCrash()
    val guardDir = root.resolve("db").resolve("guard")
    Files.createDirectories(guardDir)
    Files.writeString(guardDir.resolve("notify-dispatch-notify-failed.json"), "}{ broken")
    val b = openDb(root)
    try
      val recovered = b.recoverNotifyGuardConservative("dispatch-notify:failed", now = 1762224100000L).unsafeRunSync()
      assertEquals(recovered.cooldownUntil, Long.MaxValue, "保守向：全部仍在冷却")
      // 隔离副本在场（原值可查）。
      val q = Quarantine.listQuarantined(guardDir)
      assertEquals(q.size, 1)
      assertEquals(Files.readString(q.head), "}{ broken")
    finally b.close()

  test("V9: PerRecord appends acknowledged before crash survive restart (durable ack contract)"):
    val root = Files.createTempDirectory("db-recovery-durable")
    val a = openDb(root)
    val acked =
      (1 to 8).map(n => a.appendEvent(kernelEvent(n), None).unsafeRunSync())
    val guardAcked = a.writeNotifyGuard(GuardDocsTest.notify("dispatch-notify:failed", Nil, 123L, 1L)).unsafeRunSync()
    // 崩溃：不做优雅 flush/close（Batched 档允许丢批——但本测试只断言 PerRecord 面）。
    a.simulateCrash()
    val b = openDb(root)
    try
      b.readEvents(0L, Int.MaxValue, logical = false).unsafeRunSync() match
        case ReadResult.Present(sr) =>
          assertEquals(sr.records.map(_.seq), acked.toList, "已 ack 行在重启后全数在场（appendDurable force(true) 语义——真跑）")
        case other => fail(s"unexpected $other")
      b.readNotifyGuard("dispatch-notify:failed").unsafeRunSync() match
        case ReadResult.Present(d) => assertEquals(d.cooldownUntil, 123L, "五步写档同理")
        case other => fail(s"unexpected $other")
    finally b.close()

end DbRecoverySpec
