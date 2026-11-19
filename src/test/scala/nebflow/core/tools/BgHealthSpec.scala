package nebflow.core.tools

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import munit.FunSuite
import nebflow.core.EventSink

/**
 * P0 面板投影批（设计稿 20261030_background-task-lifecycle §3.1/§3.4/§7 P0）——
 * 后台任务健康轨**数据面** spec。判据（何时升/降级）归 P1 BgLifecycleReconciler，
 * 本批只钉：行内健康字段单一事实源、枚举投影派生、非法值容错、徽标帧往返。
 * 零 wall-clock 判死：健康轨全部语义由监测/启发式表达（P1），本批无任何时钟腿。
 *
 * 用唯一 jobId + 结尾 unregister，避免污染并行 spec 共享的全局 registry。
 */
class BgHealthSpec extends FunSuite:

  private def cleanup(jobIds: String*): IO[Unit] =
    jobIds.traverse_(BgTaskRegistry.unregister)

  /** 捕获广播帧的 EventSink（徽标帧往返断言面）。 */
  private final class CaptureSink extends EventSink:
    val frames: Ref[IO, List[Json]] = Ref.unsafe(Nil)
    override def broadcast(json: Json): IO[Unit] = frames.update(json +: _)

  // ── 行内字段默认值（向后兼容铁律：旧调用方/旧行全零 = healthy）──────────────

  test("ActiveTask 健康字段默认值全零 ⇒ 投影恒 healthy（纯增量零行为变化）") {
    val t = BgTaskRegistry.ActiveTask("job", "sid", "desc", 0L, "local")
    assertEquals(t.status, "running")
    assertEquals(t.suspicionLevel, 0)
    assertEquals(t.escalatedAtMs, 0L)
    assertEquals(t.lastActivityMs, 0L)
    assertEquals(BgHealth.of(t), BgHealth.Healthy)
  }

  // ── 枚举投影派生（单一事实源）：escalated 优先于 suspect ──────────────────

  test("BgHealth.of 派生：escalatedAtMs>0 ⇒ escalated，优先于 suspicionLevel") {
    val base = BgTaskRegistry.ActiveTask("job", "sid", "desc", 0L, "local")
    assertEquals(BgHealth.of(base.copy(suspicionLevel = 2)), BgHealth.Suspect)
    assertEquals(BgHealth.of(base.copy(suspicionLevel = 2, escalatedAtMs = 99L)), BgHealth.Escalated)
  }

  // ── 枚举非法值容错（spec 验收③）：任何非 suspect/escalated 一律回落 healthy ──

  test("非法枚举值容错：normalize 与 updateHealth 对脏值一律回落 healthy") {
    assertEquals(BgHealth.normalize("bogus"), BgHealth.Healthy)
    assertEquals(BgHealth.normalize(""), BgHealth.Healthy)
    assertEquals(BgHealth.normalize("SUSPECT"), BgHealth.Healthy) // 大小写不匹配 = 脏值
    assertEquals(BgHealth.normalize(null.asInstanceOf[String]), BgHealth.Healthy)

    val job = "bghealth-invalid-1"
    val io =
      BgTaskRegistry.register(job, "sid-health-invalid", "spec", "local") *>
        BgTaskRegistry.updateHealth(job, "bogus") *>
        BgTaskRegistry.find(job).map { t =>
          assertEquals(BgHealth.of(t.get), BgHealth.Healthy, "脏值写入后行仍 healthy")
          assertEquals(t.get.suspicionLevel, 0)
          assertEquals(t.get.escalatedAtMs, 0L)
        } *> cleanup(job)
    io.unsafeRunSync()
  }

  // ── updateHealth 写缝往返（P1 reconciler 落点的语义钉）──────────────────

  test("updateHealth 往返：suspect→escalated 幂等升级戳→healthy 重排清零；行不存在 ⇒ false") {
    val job = "bghealth-roundtrip-1"
    val io =
      BgTaskRegistry.register(job, "sid-health-rt", "spec", "local") *>
        BgTaskRegistry.updateHealth(job, "suspect", nowMs = 1000L).map(ok => assert(ok)) *>
        BgTaskRegistry.find(job).map(t => assertEquals(BgHealth.of(t.get), BgHealth.Suspect)) *>
        BgTaskRegistry.updateHealth(job, "escalated", nowMs = 2000L).map(ok => assert(ok)) *>
        BgTaskRegistry.find(job).map { t =>
          assertEquals(BgHealth.of(t.get), BgHealth.Escalated)
          assertEquals(t.get.escalatedAtMs, 2000L)
        } *>
        // 幂等：重复 escalated 写入保留首次升级戳（禁漂移）。
        BgTaskRegistry.updateHealth(job, "escalated", nowMs = 3000L) *>
        BgTaskRegistry.find(job).map(t => assertEquals(t.get.escalatedAtMs, 2000L)) *>
        // 重排不判死（设计稿 §3.1）：降回 healthy ⇒ 停滞计数与升级戳清零。
        BgTaskRegistry.updateHealth(job, "healthy") *>
        BgTaskRegistry.find(job).map { t =>
          assertEquals(BgHealth.of(t.get), BgHealth.Healthy)
          assertEquals(t.get.suspicionLevel, 0)
          assertEquals(t.get.escalatedAtMs, 0L)
        } *>
        BgTaskRegistry.updateHealth("no-such-job", "suspect").map(ok => assert(!ok)) *>
        cleanup(job)
    io.unsafeRunSync()
  }

  // ── 徽标计数与帧（heldReceipts 同款机制，spec 验收「徽标帧往返」）──────────

  test("healthCounts 全表归并；syncHealthBadge 计数变化才广播、帧形状钉死") {
    val (j1, j2, j3) = ("bghealth-badge-1", "bghealth-badge-2", "bghealth-badge-3")
    val sink = new CaptureSink
    val io =
      BgTaskRegistry.register(j1, "sid-health-badge", "spec a", "local") *>
        BgTaskRegistry.register(j2, "sid-health-badge", "spec b", "local") *>
        BgTaskRegistry.register(j3, "sid-health-badge", "spec c", "local") *>
        BgTaskRegistry.healthCounts.map(c => assertEquals(c, BgTaskRegistry.BgHealthCounts(0, 0, 0))) *>
        // 全 healthy：与初始基准一致 ⇒ 零广播（P0 生产面零行为变化的钉）。
        BgTaskRegistry.syncHealthBadge(sink).map(changed => assert(!changed)) *>
        sink.frames.get.map(fs => assertEquals(fs.size, 0)) *>
        // 一 suspect 一 escalated ⇒ 计数变化 ⇒ 广播一帧。
        BgTaskRegistry.updateHealth(j1, "suspect") *>
        BgTaskRegistry.updateHealth(j2, "escalated", nowMs = 5000L) *>
        BgTaskRegistry.healthCounts.map(c => assertEquals(c, BgTaskRegistry.BgHealthCounts(0, 1, 1))) *>
        BgTaskRegistry.syncHealthBadge(sink).map(changed => assert(changed)) *>
        sink.frames.get.map { fs =>
          assertEquals(fs.size, 1, "计数变化只广播一帧")
          val f = fs.head
          assertEquals(f.hcursor.get[String]("type"), Right(BgHealth.FrameType))
          assertEquals(f.hcursor.get[Int]("unread"), Right(0), "unread P1 前桩 = 0")
          assertEquals(f.hcursor.get[Int]("suspect"), Right(1))
          assertEquals(f.hcursor.get[Int]("escalated"), Right(1))
        } *>
        // 计数未变 ⇒ 再对账零广播（heldReceipts 同款防刷屏）。
        BgTaskRegistry.syncHealthBadge(sink).map(changed => assert(!changed)) *>
        sink.frames.get.map(fs => assertEquals(fs.size, 1)) *>
        // 回落 healthy（重排）⇒ 计数回零 ⇒ 再广播一帧（徽标消退对账）。
        BgTaskRegistry.updateHealth(j1, "healthy") *>
        BgTaskRegistry.updateHealth(j2, "healthy") *>
        BgTaskRegistry.syncHealthBadge(sink).map(changed => assert(changed)) *>
        sink.frames.get.map { fs =>
          assertEquals(fs.size, 2)
          assertEquals(fs.head.hcursor.get[Int]("suspect"), Right(0))
          assertEquals(fs.head.hcursor.get[Int]("escalated"), Right(0))
        } *>
        cleanup(j1, j2, j3)
    io.unsafeRunSync()
  }

  // ── 快照投影：activeTasksJson 带 health/status/lastActivityMs（验收①④）──

  test("activeTasksJson 行带 health（行内派生）与 lastActivityMs；status 读行内实际态") {
    val job = "bghealth-snap-1"
    val io =
      BgTaskRegistry.register(job, "sid-health-snap", "spec snap", "local", "root-health-snap") *>
        BgTaskRegistry.updateHealth(job, "suspect") *>
        BgTaskRegistry.activeTasksJson.flatMap { json =>
          IO {
            val rows = json.hcursor.downField("root-health-snap").values.map(_.toList).getOrElse(Nil)
            val t = rows.head
            assertEquals(t.hcursor.get[String]("health"), Right("suspect"), "快照帧健康与行同源")
            assertEquals(t.hcursor.get[String]("status"), Right("running"), "status 读行内实际态")
            assertEquals(t.hcursor.get[Long]("lastActivityMs"), Right(0L))
          }
        } *>
        BgTaskRegistry.updateHealth(job, "escalated", nowMs = 7000L) *>
        BgTaskRegistry.activeTasksJson.flatMap { json =>
          IO {
            val rows = json.hcursor.downField("root-health-snap").values.map(_.toList).getOrElse(Nil)
            assertEquals(rows.head.hcursor.get[String]("health"), Right("escalated"))
          }
        } *>
        cleanup(job)
    io.unsafeRunSync()
  }
end BgHealthSpec
