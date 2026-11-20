package nebflow.db

import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.db.format.EventEnvelope

import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

/**
 * db 接线二阶 B3——遗忘刀 sweep 调度（[[RetentionSweeper]]，D-9 接线）：
 *  - **避让窗（乙件 S16→S17 同语义：清扫不得在 boot 恢复完成前跑）**：未开
 *    recovery 门 ⇒ 超周期等待后零 tombstone（fail-safe：不恢复就绝不遗忘）；
 *  - 开门（recoverPendingD8 成功）⇒ 首扫立即：超龄非欠债事件逻辑遗忘、欠债族
 *    （audit/failed）与新鲜事件全留（has_debt⇒Keep）；
 *  - DbHost 链贯通：openBestEffort ⇒ 自动 arm ⇒ 开门 ⇒ sweep 生效（生产形态）；
 *  - 逻辑轨可回滚：reviveUnits 后逻辑视图恢复（原文字节不动——V10 面）。
 */
class RetentionSweeperSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val DayMs = 24L * 3600 * 1000

  private def event(typ: String, occurredAt: Long, dedup: String): (Json, Option[String]) =
    Json.obj(
      "type" -> Json.fromString(typ),
      "occurredAt" -> Json.fromLong(occurredAt),
      "sessionId" -> Json.Null,
      "seqInSession" -> Json.Null,
      "payload" -> Json.obj("summary" -> Json.fromString(s"sweep-$dedup"))
    ) -> Some(dedup)

  /** 有界自旋等待（deadline 10s，步 100ms）——等待异步 sweep 生效。 */
  private def waitUntil(deadlineMs: Long = 10_000L)(cond: => Boolean): Unit =
    val step = 100L
    var waited = 0L
    while !cond && waited < deadlineMs do
      Thread.sleep(step)
      waited += step
    assert(cond, s"condition not met within ${deadlineMs}ms")

  private def logicalUnitIds(h: DbHandle): Set[String] =
    h.readEvents(0L, Int.MaxValue, logical = true).unsafeRunSync() match
      case ReadResult.Present(sr) => sr.records.map(h.unitKeyOf).toSet
      case other => fail(s"unexpected $other")

  test("B3 避让窗：recovery 门未开 ⇒ 超周期等待仍零遗忘（S16→S17）"):
    val root = Files.createTempDirectory("db-sweep-gate")
    val h = Db.open(DbConfig(root)).unsafeRunSync()
    val old = System.currentTimeMillis() - 400L * DayMs
    h.appendEvent(event("node-created", old, "g1")._1, Some("g1")).unsafeRunSync()
    // arm（100ms 周期——远小于等待窗；门不开）。
    RetentionSweeper.arm(
      h,
      cfg = Retention.Config(maxAgeMs = Some(365L * DayMs), sampleRatioPer10k = 10000),
      intervalMs = 100L
    )
    Thread.sleep(800L) // > 8 个周期
    assertEquals(logicalUnitIds(h), Set("g1"), "避让窗内绝不遗忘（门不开 ⇒ 不扫）")
    h.close()

  test("B3 开门即扫：超龄遗忘 + 欠债豁免 + 新鲜保留 + 逻辑轨可回滚"):
    val root = Files.createTempDirectory("db-sweep-run")
    val h = Db.open(DbConfig(root)).unsafeRunSync()
    val now = System.currentTimeMillis()
    val old = now - 400L * DayMs
    val fresh = now - DayMs
    h.appendEvent(event("node-created", old, "old-plain")._1, Some("old-plain")).unsafeRunSync()
    h.appendEvent(event("audit-trail", old, "old-audit")._1, Some("old-audit")).unsafeRunSync()
    h.appendEvent(event("node-failed", old, "old-failed")._1, Some("old-failed")).unsafeRunSync()
    h.appendEvent(event("node-created", fresh, "fresh-plain")._1, Some("fresh-plain")).unsafeRunSync()
    RetentionSweeper.arm(
      h,
      cfg = Retention.Config(maxAgeMs = Some(365L * DayMs), sampleRatioPer10k = 10000),
      intervalMs = 5_000L
    )
    h.recoverPendingD8().unsafeRunSync() // 门开（空 pending 恢复成功）
    waitUntil()(logicalUnitIds(h) == Set("old-audit", "old-failed", "fresh-plain"))
    // 逻辑轨回滚（V10 面）：revive 后逻辑视图恢复，原文字节不动。
    def physicalSize: Int = h.readEvents(0L, Int.MaxValue, logical = false).unsafeRunSync() match
      case ReadResult.Present(sr) => sr.records.size
      case other => fail(s"unexpected $other")
    val physicalCount = physicalSize
    h.reviveUnits(List("old-plain"), System.currentTimeMillis()).unsafeRunSync()
    waitUntil()(logicalUnitIds(h).contains("old-plain"))
    assertEquals(physicalSize, physicalCount, "逻辑轨不动原文（物化面行数不变）")
    h.close()

  test("B3 DbHost 链：openBestEffort 自动 arm + 生产默认 cfg（365d 年龄刀）"):
    val root = Files.createTempDirectory("db-sweep-dbhost")
    DbHost.release(root) // 防串（同根残留）
    val h = DbHost.openBestEffort(root).getOrElse(fail("openBestEffort 应成功"))
    val veryOld = System.currentTimeMillis() - 366L * DayMs
    val fresh = System.currentTimeMillis() - DayMs
    h.appendEvent(event("node-created", veryOld, "vh-old")._1, Some("vh-old")).unsafeRunSync()
    h.appendEvent(event("node-created", fresh, "vh-fresh")._1, Some("vh-fresh")).unsafeRunSync()
    h.recoverPendingD8().unsafeRunSync()
    waitUntil()(logicalUnitIds(h) == Set("vh-fresh"))
    DbHost.release(root)

end RetentionSweeperSpec
