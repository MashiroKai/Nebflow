package nebflow.db

import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite

import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

/**
 * V10 —— 遗忘刀可对拍：fnv1a 确定性（同输入同保留集）；tombstone 逻辑视图翻转可回滚
 * 且原文字节不变；欠债豁免三域；物化 purge 一等显式动词。
 */
class RetentionSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  // ── fnv1a64：官方公布向量（FNV-1a 64-bit test vectors）─────────────────────

  test("fnv1a64 matches published FNV-1a 64 vectors (donor retention.rs:150 same algorithm)"):
    assertEquals(Retention.fnv1a64(""), 0xcbf29ce484222325L, "empty = offset basis（数学必然）")
    assertEquals(Retention.fnv1a64("a"), 0xaf63dc4c8601ec8cL, "published vector")
    assertEquals(Retention.fnv1a64("foobar"), 0x85944171f73967e8L, "published vector")

  test("fnv1a64 hashes UTF-8 bytes (donor semantics), deterministic across calls"):
    assertEquals(Retention.fnv1a64("节点-3f2a"), Retention.fnv1a64("节点-3f2a"))
    assert(Retention.fnv1a64("节点-3f2a") != Retention.fnv1a64("节点-3f2b"))

  test("V10: same input ⇒ same retention plan (deterministic sampling, replayable)"):
    val candidates = (1 to 50)
      .map(i =>
        Retention.Candidate(s"unit-$i", ts = 1000L, domain = Retention.Domain.EventStream, eventType = "node-completed")
      )
      .toList
    val cfg = Retention.Config(maxAgeMs = None, sampleRatioPer10k = 3000)
    val p1 = Retention.plan(candidates, cfg, nowMs = 999999L)
    val p2 = Retention.plan(candidates, cfg, nowMs = 999999L)
    assertEquals(p1, p2, "同输入同保留集（可对拍）")
    val kept = p1.count {
      case Retention.Decision.KeepSampled => true
      case _ => false
    }
    assert(kept > 5 && kept < 45, s"sampling ratio 30% should keep a middle share, got $kept/50")

  test("D-9 three exemption domains are debt (both knives never apply)"):
    val old = Retention.Config(maxAgeMs = Some(0L), sampleRatioPer10k = 0L) // 两刀全开最狠档
    val now = 2000L
    val debtCases = List(
      Retention.Candidate("u1", 1000L, Retention.Domain.PendingReview, "Permission"),
      Retention.Candidate("u2", 1000L, Retention.Domain.Snapshot, ""),
      Retention.Candidate("u3", 1000L, Retention.Domain.Quarantined, ""),
      Retention.Candidate("u4", 1000L, Retention.Domain.EventStream, "audit-node-changed"),
      Retention.Candidate("u5", 1000L, Retention.Domain.EventStream, "budget-exhausted"),
      Retention.Candidate("u6", 1000L, Retention.Domain.EventStream, "node-failed"),
      Retention.Candidate("u7", 1000L, Retention.Domain.EventStream, "escalate")
    )
    debtCases.foreach { c =>
      assertEquals(Retention.hasDebt(c), true, s"$c must be debt-exempt")
      assertEquals(Retention.plan(List(c), old, now), List(Retention.Decision.KeepDebt), s"$c: has_debt ⇒ Keep（两刀皆不适用）")
    }
    // 非豁免面在最狠档下被两刀收走。
    val normal = Retention.Candidate("u8", 1000L, Retention.Domain.EventStream, "node-completed")
    assertEquals(Retention.plan(List(normal), old, now), List(Retention.Decision.DropByAge))

  test("age knife: threshold is saturating (now < maxAge ⇒ age knife neutralized)"):
    val c = Retention.Candidate("u", ts = 500L, domain = Retention.Domain.EventStream)
    // 供体刀序：年龄刀幸存者落采样刀——ratio=10000 恒留 ⇒ KeepSampled（年龄刀未误杀）。
    val cfg = Retention.Config(maxAgeMs = Some(10_000L), sampleRatioPer10k = 10_000L)
    assertEquals(
      Retention.plan(List(c), cfg, nowMs = 1000L),
      List(Retention.Decision.KeepSampled),
      "饱和减 ⇒ 阈值 0 ⇒ 年龄刀不生效；采样恒留 ⇒ 保留"
    )
    // 对照：同样的记录一旦到龄（maxAge 很小）⇒ DropByAge。
    val cfgOld = Retention.Config(maxAgeMs = Some(100L), sampleRatioPer10k = 10_000L)
    assertEquals(Retention.plan(List(c), cfgOld, nowMs = 1000L), List(Retention.Decision.DropByAge))

  test("V10: tombstone logical view flips, is reversible, and original bytes never change"):
    val root = Files.createTempDirectory("db-retention-tombstone")
    val h = Db.open(DbConfig(root)).unsafeRunSync()
    try
      def ev(n: Int, typ: String, subject: String) = Json.obj(
        "type" -> Json.fromString(typ),
        "occurredAt" -> Json.fromLong(1762224000000L + n),
        "sessionId" -> Json.Null,
        "seqInSession" -> Json.Null,
        "payload" -> Json.obj(),
        "subject" -> Json.fromString(subject)
      )
      h.appendEvent(ev(1, "node-completed", "node-a"), None).unsafeRunSync()
      h.appendEvent(ev(2, "node-completed", "node-b"), None).unsafeRunSync()
      h.appendEvent(ev(3, "node-failed", "node-c"), None).unsafeRunSync() // 失败取证（欠债）
      val shardFile = root.resolve("db").resolve("events").resolve("events.1.jsonl")
      val rawBefore = Files.readString(shardFile)
      // sweep：全老 + 全采样删 ⇒ 非欠债面标记遗忘（node-a/node-b），node-failed 全留。
      val report = h
        .retentionSweep(Retention.Config(maxAgeMs = Some(1L), sampleRatioPer10k = 0L), now = 1762300000000L)
        .unsafeRunSync()
      assertEquals(report.dropByAge, 2L)
      assertEquals(report.keepDebt >= 1L, true, "node-failed 计入欠债豁免")
      assertEquals(Files.readString(shardFile), rawBefore, "V10: 遗忘=逻辑标记，原文字节不变")
      // 逻辑视图：只剩欠债行。
      h.readEvents(0L, Int.MaxValue, logical = true).unsafeRunSync() match
        case ReadResult.Present(sr) =>
          assertEquals(sr.records.map(r => unitType(h, r)), List("node-failed"))
        case other => fail(s"unexpected $other")
      // raw 视图仍在（可回滚的证据基座）。
      h.readEvents(0L, Int.MaxValue, logical = false).unsafeRunSync() match
        case ReadResult.Present(sr) => assertEquals(sr.records.size, 3)
        case other => fail(s"unexpected $other")
      // 回滚：复活 node-a ⇒ 逻辑视图重新可见（追加 revive 行，原文仍不动）。
      h.reviveUnits(List("node-a|node-completed"), 1762300001000L).unsafeRunSync()
      val rawAfterRevive = Files.readString(shardFile)
      assertEquals(rawAfterRevive, rawBefore, "复活同样零改写")
      h.readEvents(0L, Int.MaxValue, logical = true).unsafeRunSync() match
        case ReadResult.Present(sr) =>
          assertEquals(sr.records.map(r => unitType(h, r)).sorted, List("node-completed", "node-failed"))
        case other => fail(s"unexpected $other")
    finally h.close()

    end try

  test("purgeTombstonedEvents (explicit verb): physically drops tombstoned non-debt rows only"):
    val root = Files.createTempDirectory("db-retention-purge")
    val h = Db.open(DbConfig(root)).unsafeRunSync()
    try
      def ev(n: Int, typ: String, subject: String) = Json.obj(
        "type" -> Json.fromString(typ),
        "occurredAt" -> Json.fromLong(1762224000000L + n),
        "sessionId" -> Json.Null,
        "seqInSession" -> Json.Null,
        "payload" -> Json.obj(),
        "subject" -> Json.fromString(subject)
      )
      (1 to 2).foreach(n =>
        h.appendEvent(ev(n, "node-completed", s"node-$n"), None)
          .unsafeRunSync()
      )
      h.appendEvent(ev(3, "audit-boot", "sys"), None).unsafeRunSync() // 欠债（audit 族）
      h.retentionSweep(Retention.Config(maxAgeMs = Some(1L), sampleRatioPer10k = 0L), now = 1762300000000L)
        .unsafeRunSync()
      val dropped = h.purgeTombstonedEvents().unsafeRunSync()
      assertEquals(dropped, 2L, "两行 node-completed 物理删除")
      h.readEvents(0L, Int.MaxValue, logical = false).unsafeRunSync() match
        case ReadResult.Present(sr) =>
          assertEquals(sr.records.map(r => unitType(h, r)), List("audit-boot"), "欠债行物理保留（失败样本不可丢）")
        case other => fail(s"unexpected $other")
      h.verify().unsafeRunSync() match
        case r if r.ok => ()
        case r => fail(s"verify after purge: $r")
    finally h.close()

    end try

  test("sweep arithmetic identity: scanned == kept + dropped"):
    val root = Files.createTempDirectory("db-retention-math")
    val h = Db.open(DbConfig(root)).unsafeRunSync()
    try
      def ev(n: Int) = Json.obj(
        "type" -> Json.fromString("node-completed"),
        "occurredAt" -> Json.fromLong(1762224000000L + n),
        "sessionId" -> Json.Null,
        "seqInSession" -> Json.Null,
        "payload" -> Json.obj(),
        "subject" -> Json.fromString(s"n$n")
      )
      (1 to 10).foreach(n => h.appendEvent(ev(n), None).unsafeRunSync())
      val r = h
        .retentionSweep(Retention.Config(maxAgeMs = Some(1L), sampleRatioPer10k = 5000L), now = 1762300000000L)
        .unsafeRunSync()
      assertEquals(r.scanned, r.kept + r.dropped, "算术恒等式")
      assert(r.dropped >= 0 && r.kept >= 0)
    finally h.close()

    end try

  private def unitType(h: DbHandle, e: nebflow.db.format.EventEnvelope): String =
    e.event.hcursor.downField("type").as[String].toOption.getOrElse("?")

end RetentionSpec
