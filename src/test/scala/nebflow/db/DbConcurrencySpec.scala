package nebflow.db

import cats.effect.IO
import cats.effect.implicits.concurrentParTraverseOps
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite

import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

/**
 * V4 —— 并发写者与 seq：两线程竞争追加（经同一句柄的三层闸串行化）⇒ 全体落地、
 * 无重号无回退；跨重启（kill -9 模拟）后新号恒高于已见最大号（只跳号不重号）。
 */
class DbConcurrencySpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private def kernelEvent(n: Int): Json = Json.obj(
    "type" -> Json.fromString("node-completed"),
    "occurredAt" -> Json.fromLong(1762224000000L + n),
    "sessionId" -> Json.Null,
    "seqInSession" -> Json.Null,
    "payload" -> Json.obj("summary" -> Json.fromString(s"conc-$n"))
  )

  test("V4: two concurrent writers through one handle — 200 appends, all unique, no regression"):
    val root = Files.createTempDirectory("db-conc")
    val h = Db.open(DbConfig(root)).unsafeRunSync()
    try
      val total = 200
      val appends: List[IO[Long]] =
        (1 to total).toList.map(n => h.appendEvent(kernelEvent(n), None))
      val seqs = appends.parTraverseN(2)(identity).unsafeRunSync()
      assertEquals(seqs.size, total)
      assertEquals(seqs.distinct.size, total, "无重号")
      assertEquals(seqs.sorted, seqs.sorted, "（恒真自检）")
      // 无回退：读面按 seq 升序排列后与全局一致（计数器单源 ⇒ 天然全序）。
      h.readEvents(0L, Int.MaxValue, logical = false).unsafeRunSync() match
        case ReadResult.Present(sr) =>
          assertEquals(sr.records.size, total, "全部落地（无丢失）")
          val seqList = sr.records.map(_.seq)
          assertEquals(seqList, seqList.sorted, "读面 seq 全序无回退")
          assertEquals(seqList.distinct.size, total)
        case other => fail(s"unexpected $other")
      h.verify().unsafeRunSync() match
        case r if r.ok => ()
        case r => fail(s"verify: $r")
    finally h.close()

    end try

  test("V4: seq never reused across kill-9 restarts (skip-only across abandoned intervals)"):
    val root = Files.createTempDirectory("db-conc-restart")
    val a = Db.open(DbConfig(root, seqInterval = 8)).unsafeRunSync()
    val seqsA = (1 to 5).map(n => a.appendEvent(kernelEvent(n), None).unsafeRunSync())
    a.simulateCrash() // kill -9：弃用区间余号 6..8
    val b = Db.open(DbConfig(root, seqInterval = 8)).unsafeRunSync()
    try
      val seqsB = (1 to 5).map(n => b.appendEvent(kernelEvent(100 + n), None).unsafeRunSync())
      assert(seqsB.min > seqsA.max, s"new seqs $seqsB must exceed old max ${seqsA.max}")
      assertEquals(seqsB.distinct.size, seqsB.size)
      val all = (seqsA ++ seqsB).toList
      assertEquals(all.distinct.size, all.size, "跨重启无重号")
    finally b.close()

  test("V4: counter destroyed after writes ⇒ reopen refuses (never renumber from zero)"):
    val root = Files.createTempDirectory("db-conc-counter")
    val a = Db.open(DbConfig(root)).unsafeRunSync()
    a.appendEvent(kernelEvent(1), None).unsafeRunSync()
    a.simulateCrash()
    // 计数器档被外部损毁（磁盘事故形态）。
    val counter = root.resolve("db").resolve("seq").resolve("counter.json")
    Files.writeString(counter, "GARBAGE")
    intercept[DbError.RefuseStart] {
      Db.open(DbConfig(root)).unsafeRunSync()
    }
    // 删除计数器（有数据树）同样拒启。
    Files.delete(counter)
    intercept[DbError.RefuseStart] {
      Db.open(DbConfig(root)).unsafeRunSync()
    }

  test("V4: cross-check — counter reconciled when shard tail outruns counter file"):
    val root = Files.createTempDirectory("db-conc-reconcile")
    val a = Db.open(DbConfig(root, seqInterval = 4)).unsafeRunSync()
    val seqs = (1 to 3).map(n => a.appendEvent(kernelEvent(n), None).unsafeRunSync())
    a.simulateCrash()
    // 计数器被回拨（备份回档形态）：next=2 < 已落盘尾 seq=3。
    val counter = root.resolve("db").resolve("seq").resolve("counter.json")
    Files.writeString(counter, """{"version":1,"next":2}""")
    val b = Db.open(DbConfig(root, seqInterval = 4)).unsafeRunSync()
    try
      assertEquals(b.counterPersistedNext > seqs.max, true, "交叉校验以大者为准（计数器 ≥ 各流尾 seq）")
      val next = b.appendEvent(kernelEvent(99), None).unsafeRunSync()
      assert(next > seqs.max, "新号不回退不重号")
    finally b.close()

end DbConcurrencySpec
