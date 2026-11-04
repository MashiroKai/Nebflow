package nebflow.db

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite

import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

/**
 * V2/V3 —— 损坏可表示 + 读失败不参与下一次写：
 * 注入坏档 ⇒ Absent/Corrupt/Present 三分正确；Corrupt 必有 `.corrupt-<ts>` 副本 +
 * WARN + 计数；任何路径不得以 Nil/空表顶替 Corrupt；坏档在场 append/save ⇒ 拒绝并报错
 * （对拍 PresetStore 坏档写回默认的反例——同样的注入在现状下会销毁用户数据）。
 */
class DbCorruptionSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private def kernelEvent(n: Int): Json = Json.obj(
    "type" -> Json.fromString("node-failed"),
    "occurredAt" -> Json.fromLong(1762224000000L + n),
    "sessionId" -> Json.Null,
    "seqInSession" -> Json.Null,
    "payload" -> Json.obj("summary" -> Json.fromString(s"corrupt-spec-$n"))
  )

  private def openDb(root: Path): DbHandle = Db.open(DbConfig(root)).unsafeRunSync()

  test("V2: three-way read on guard docs — absent is absent, garbage is Corrupt, good is Present"):
    val root = Files.createTempDirectory("db-corrupt-three-way")
    val h = openDb(root)
    try
      // 缺 = Absent（新档，不隔离不告警）。
      assertEquals(h.readNotifyGuard("dispatch-notify:failed").unsafeRunSync(),
        ReadResult.Absent())
      // 好档 = Present。
      h.writeNotifyGuard(GuardDocsTest.notify("dispatch-notify:failed",
        List(1L), 99L, 100L)).unsafeRunSync()
      h.readNotifyGuard("dispatch-notify:failed").unsafeRunSync() match
        case ReadResult.Present(_) => ()
        case other => fail(s"expected Present, got $other")
      // 坏档 = Corrupt（**不是** Nil 顶替）——不同 source 的档互不影响。
      val f = root.resolve("db").resolve("guard").resolve("notify-dispatch-notify-cancelled.json")
      Files.createDirectories(f.getParent)
      Files.writeString(f, "{ this is not json ")
      h.readNotifyGuard("dispatch-notify:cancelled").unsafeRunSync() match
        case ReadResult.Corrupt(kind, evidence) =>
          assert(kind.nonEmpty)
          assertEquals(evidence, f)
        case other => fail(s"expected Corrupt, got $other")
    finally h.close()

  test("V2: Corrupt has .corrupt-<ts> copy with original bytes; read flips to Absent; stats count"):
    val root = Files.createTempDirectory("db-corrupt-quarantine")
    val h = openDb(root)
    try
      val guardDir = root.resolve("db").resolve("guard")
      val f = guardDir.resolve("notify-dispatch-notify-failed.json")
      Files.createDirectories(guardDir)
      val garbage = "<<<garbage-bytes>>>"
      Files.writeString(f, garbage)
      val before = h.stats.quarantined
      val q = h.quarantineGuardDoc("dispatch-notify:failed", now = 1762224999999L)
        .unsafeRunSync().get
      assert(q.getFileName.toString.endsWith(".corrupt-1762224999999"), s"name=${q.getFileName}")
      assert(Files.exists(q), "隔离副本在场")
      assertEquals(Files.readString(q), garbage, "原值全字节可查（隔离不删除）")
      assert(!Files.exists(f), "正名让位（消费面只认正名）")
      assertEquals(h.readNotifyGuard("dispatch-notify:failed").unsafeRunSync(),
        ReadResult.Absent())
      assertEquals(h.stats.quarantined, before + 1L, "计数上报")
    finally h.close()

  test("V3: guard write refused while corrupt doc in place; proceeds after quarantine"):
    val root = Files.createTempDirectory("db-corrupt-v3-guard")
    val h = openDb(root)
    try
      val guardDir = root.resolve("db").resolve("guard")
      val f = guardDir.resolve("notify-dispatch-notify-failed.json")
      Files.createDirectories(guardDir)
      Files.writeString(f, "\"半档\"")
      // 坏档在场 ⇒ 写拒绝（读失败不得参与下一次写）。
      intercept[DbError.StreamCorrupt] {
        h.writeNotifyGuard(GuardDocsTest.notify("dispatch-notify:failed", Nil, 5L, 6L))
          .unsafeRunSync()
      }
      assert(Files.readString(f) == "\"半档\"", "拒绝路径零修改")
      // 隔离后 ⇒ 写放行。
      h.quarantineGuardDoc("dispatch-notify:failed", 1762224999999L).unsafeRunSync()
      h.writeNotifyGuard(GuardDocsTest.notify("dispatch-notify:failed", Nil, 5L, 6L))
        .unsafeRunSync()
      h.readNotifyGuard("dispatch-notify:failed").unsafeRunSync() match
        case ReadResult.Present(d) => assertEquals(d.cooldownUntil, 5L)
        case other => fail(s"unexpected $other")
    finally h.close()

  test("V1/V2: injected half-line JSONL (kill-9 tear) — no half line enters the db, tail repaired"):
    val root = Files.createTempDirectory("db-corrupt-torn")
    val h = openDb(root)
    try
      h.appendEvent(kernelEvent(1), None).unsafeRunSync()
      val f = root.resolve("db").resolve("events").resolve("events.1.jsonl")
      h.flush().unsafeRunSync()
      // kill-9 撕裂窗：直写半行（无换行）。
      Files.writeString(f, """{"seq":2,"checksum":"sha256:ab""", // torn append
        java.nio.file.StandardOpenOption.APPEND)
      val goodBytes = h.readEvents(0L, Int.MaxValue, logical = false).unsafeRunSync() match
        case ReadResult.Present(sr) =>
          assertEquals(sr.records.size, 1, "半行不入库")
          assertEquals(sr.tornTailDropped, 1, "撕裂尾显式计数（非静默）")
          sr.records
        case other => fail(s"unexpected $other")
      // 追加前置检：修复撕裂尾后正常续写。
      h.appendEvent(kernelEvent(2), None).unsafeRunSync()
      val after = Files.readString(f)
      assert(!after.contains("sha256:ab\""), "残段已被截断")
      h.readEvents(0L, Int.MaxValue, logical = false).unsafeRunSync() match
        case ReadResult.Present(sr) => assertEquals(sr.records.size, 2)
        case other => fail(s"unexpected $other")
      assertEquals(h.stats.tornTailsRepaired, 1L, "修复计数")
    finally h.close()

  test("V3: event append refused on bad framed tail (complete-but-invalid last line)"):
    val root = Files.createTempDirectory("db-corrupt-badtail")
    val h = openDb(root)
    try
      h.appendEvent(kernelEvent(1), None).unsafeRunSync()
      val f = root.resolve("db").resolve("events").resolve("events.1.jsonl")
      Files.writeString(f, "COMPLETE-BUT-NOT-JSON\n", java.nio.file.StandardOpenOption.APPEND)
      intercept[DbError.StreamCorrupt] {
        h.appendEvent(kernelEvent(2), None).unsafeRunSync()
      }
      // 处置：隔离该片 ⇒ 追加恢复（隔离片原值可查）。
      val q = h.quarantineEventShard(1, 1762224999999L).unsafeRunSync().get
      assert(Files.readString(q).contains("COMPLETE-BUT-NOT-JSON"))
      h.appendEvent(kernelEvent(3), None).unsafeRunSync()
      h.readEvents(0L, Int.MaxValue, logical = false).unsafeRunSync() match
        case ReadResult.Present(sr) => assertEquals(sr.records.size, 1, "隔离片按缺席处理（取证面②）")
        case other => fail(s"unexpected $other")
    finally h.close()

  test("V2: corrupt mid-frame in events shard surfaces as Corrupt on read (never empty list)"):
    val root = Files.createTempDirectory("db-corrupt-midframe")
    val h = openDb(root)
    try
      h.appendEvent(kernelEvent(1), None).unsafeRunSync()
      h.appendEvent(kernelEvent(2), None).unsafeRunSync()
      val f = root.resolve("db").resolve("events").resolve("events.1.jsonl")
      // 中间坏帧：把第二行改坏（保留首尾行完整）。
      val lines = Files.readString(f).linesIterator.toList
      val poisoned = lines.head + "\n{\"seq\":999,\"broken\":\n" + lines.last + "\n"
      Files.writeString(f, poisoned)
      h.readEvents(0L, Int.MaxValue, logical = false).unsafeRunSync() match
        case ReadResult.Corrupt(kind, evidence) =>
          assert(kind.startsWith("bad-frame"))
          assertEquals(evidence, f)
        case other => fail(s"expected Corrupt, got $other — 不得以空表顶替")
    finally h.close()

  test("V2: corrupt pending file — recovery quarantines and refuses explicitly (non-regenerable ①)"):
    val root = Files.createTempDirectory("db-corrupt-pending")
    val h = openDb(root)
    try
      val pendingDir = root.resolve("db").resolve("pending")
      Files.createDirectories(pendingDir)
      Files.writeString(pendingDir.resolve("ask.jsonl"), "NOT-JSON\n")
      intercept[DbError.StreamCorrupt] {
        h.recoverPendingD8().unsafeRunSync()
      }
      // 已隔离：原值可查，读面回 Absent（处置权在调用方）。
      val quarantined = pendingDir.toFile.listFiles()
        .map(_.getName).filter(_.contains(".corrupt-")).toList
      assertEquals(quarantined.size, 1, s"quarantined files: $quarantined")
      assert(!Files.exists(pendingDir.resolve("ask.jsonl")))
    finally h.close()

end DbCorruptionSpec
