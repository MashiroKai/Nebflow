package nebflow.core.tools

import munit.FunSuite
import cats.effect.unsafe.implicits.global
import nebflow.shared.PathUtil
import scala.compiletime.uninitialized

/**
 * 后台任务完成事件持久账本 spec（修复批 2026-10-24）。
 *
 * 病历锚 = PPT 批 delegate 会话「任务账目丢了、wake 路径也没了、永久空等」。
 * 钉面：
 *   - 账本 round-trip：完成事件落账（先落账本再投递的账面）→ pending → 消费出账；
 *   - orphan 升级/不降级：cap 孤儿登记 ↔ 迟到 completion 的原位升级与竞态保护；
 *   - 回合尾栅栏（spec ③）：非空在飞集 armBarrier 恒成功（必有栅栏）+ 新等待期
 *     首拍才出警告（防逐轮刷转录）+ 空集清栅栏；
 *   - 判重拆分（spec ② 的幂等面）：payloadHead 已在转录/在途 ⇒ 只消费不重注；
 *   - 警告文案（spec ① 的上下文可见面）：任务数 + id + 描述 + 起止。
 */
class BgTaskLedgerSpec extends FunSuite:

  private var prevRoot: os.Path = uninitialized
  private var tmp: os.Path = uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    tmp = os.temp.dir()
    prevRoot = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp / "data")

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(prevRoot)
    os.remove.all(tmp)

  private val payload =
    "[Background task completed] \"render deck\":\nline-1\nline-2 (exit 0)"

  test("L1 完成事件落账→pending→消费出账（先落账本再投递的账面；永不蒸发）"):
    BgTaskLedger.recordCompletion("sid-l1", "job-1", "render deck", "completed", payload).unsafeRunSync()
    val pending = BgTaskLedger.pending("sid-l1").unsafeRunSync()
    assertEquals(pending.map(_.jobId), List("job-1"))
    assertEquals(pending.head.kind, "completion")
    assertEquals(pending.head.payload, payload)
    assertEquals(pending.head.payloadHead, payload.take(BgTaskLedger.PayloadHeadLen))
    assert(pending.head.completedAtMs.isDefined, "completion carries its completion timestamp")
    // 消费 = 出账；全部出清后文件不留壳
    BgTaskLedger.consume("sid-l1", List("job-1")).unsafeRunSync()
    assertEquals(BgTaskLedger.pending("sid-l1").unsafeRunSync(), Nil)
    assert(!os.exists(PathUtil.dataRoot / "sessions" / "sid-l1" / "bg-task-ledger.json"), "empty ledger leaves no shell file")

  test("L2 orphan 登记（cap 孤儿）→ 迟到 completion 原位升级（kind 不降级）"):
    BgTaskLedger.markOrphaned("sid-l2", List(("job-2", "long build", Some("tail lines")))).unsafeRunSync()
    val orphan = BgTaskLedger.pending("sid-l2").unsafeRunSync().head
    assertEquals(orphan.kind, "orphan")
    assertEquals(orphan.tailSnapshot, Some("tail lines"))
    assertEquals(orphan.payload, "", "orphan carries no payload until its completion arrives")
    // 迟到的完成事件原位升级 orphan（完成事件+输出尾段入账，续会话拾取有据）
    BgTaskLedger.recordCompletion("sid-l2", "job-2", "long build", "completed", payload).unsafeRunSync()
    val upgraded = BgTaskLedger.pending("sid-l2").unsafeRunSync().head
    assertEquals(upgraded.kind, "completion", "orphan upgraded in place by the late completion")
    assertEquals(upgraded.payload, payload)
    assert(!upgraded.consumed, "upgraded entry is pending again")

  test("L3 竞态保护：已完成的条目不被迟到的 markOrphaned 降级"):
    BgTaskLedger.recordCompletion("sid-l3", "job-3", "quick task", "failed", "[Background task failed] \"quick task\":\nboom").unsafeRunSync()
    BgTaskLedger.markOrphaned("sid-l3", List(("job-3", "quick task", None))).unsafeRunSync()
    val kept = BgTaskLedger.pending("sid-l3").unsafeRunSync().head
    assertEquals(kept.kind, "completion", "completion must not be downgraded by a late orphan mark")
    assertEquals(kept.eventType, "failed")

  test("L4 回合尾栅栏（spec ③）：非空集必有栅栏；同集不重发警告；空集清栅栏"):
    // 新等待期首拍：栅栏登记 + 警告
    BgTaskLedger.armBarrier("sid-l4", List("job-a")).unsafeRunSync()
    val b1 = BgTaskLedger.barrierOf("sid-l4").unsafeRunSync()
    assertEquals(b1.map(_.taskIds), Some(List("job-a")), "turn end with in-flight tasks arms the barrier")
    // 同集重臂：changed=false（警告不重发），栅栏时间戳刷新（仍在挂）
    val changedSame = BgTaskLedger.armBarrier("sid-l4", List("job-a")).unsafeRunSync()
    assertEquals(changedSame, false, "same wait set must not re-arm the warning")
    assert(BgTaskLedger.barrierOf("sid-l4").unsafeRunSync().isDefined, "barrier stays armed while tasks pending")
    // 集合变化（新增任务）：changed=true（新等待期）
    val changedNew = BgTaskLedger.armBarrier("sid-l4", List("job-a", "job-b")).unsafeRunSync()
    assertEquals(changedNew, true)
    // 空集：清栅栏
    BgTaskLedger.armBarrier("sid-l4", Nil).unsafeRunSync()
    assertEquals(BgTaskLedger.barrierOf("sid-l4").unsafeRunSync(), None, "empty wait set clears the barrier")

  test("L5 判重拆分（spec ② 幂等面）：转录命中/在途命中 ⇒ 只消费不重注"):
    val e1 = BgTaskLedger.Entry("job-1", "d", "completion", "completed", payload.take(160), payload, None, 1L, Some(1L))
    val e2 = BgTaskLedger.Entry("job-2", "d", "completion", "completed", payload.take(160), payload, None, 2L, Some(2L))
    val e3 = BgTaskLedger.Entry("job-3", "d", "completion", "completed", "other-payload-head", "other", None, 3L, Some(3L))
    // e1 在转录、e2 在途、e3 两者皆非 ⇒ 只有 e3 需补投；e1/e2 只消费
    val (inject, consumeOnly) =
      BgTaskLedger.splitForRedelivery(List(e1, e2, e3), s"turn transcript mentions ${payload.take(160)} already", List(payload))
    assertEquals(inject.map(_.jobId), List("job-3"), "only never-delivered entries are re-injected")
    assertEquals(consumeOnly.map(_.jobId).sorted, List("job-1", "job-2"), "delivered/in-flight entries are consumed silently")

  test("L6 警告文案（spec ①）：任务数 + id + 描述 + 起止齐全"):
    val started = 1730000000000L
    val text = BgTaskLedger.turnEndWarning(List(("job-x", "render deck", started)), started + 120000)
    assert(text.contains("<system-reminder>"), text)
    assert(text.contains("1 background task"), s"count visible: $text")
    assert(text.contains("job-x") && text.contains("render deck"), s"id+description visible: $text")
    assert(text.contains("started=") && text.contains("running 2m"), s"start+elapsed visible: $text")

  test("G1 回合尾门（BgTurnEndGate）：非空集 = 栅栏 + 首拍警告；同集 = 仅栅栏"):
    val first = BgTurnEndGate.onTurnEnd(
      "sid-g1",
      List(BgTaskRegistry.ActiveTask("job-g", "sid-g1", "render deck", startedAtMs = 1L, kind = "local"))
    ).unsafeRunSync()
    assertEquals(first.waitingCount, 1)
    assert(first.warning.isDefined, "first turn-end of a new wait period warns")
    assert(first.warning.get.contains("job-g"), "warning names the job")
    assert(BgTaskLedger.barrierOf("sid-g1").unsafeRunSync().isDefined, "gate arms the persistent barrier")
    // 同集第二个回合尾：栅栏仍在，警告不重发
    val second = BgTurnEndGate.onTurnEnd(
      "sid-g1",
      List(BgTaskRegistry.ActiveTask("job-g", "sid-g1", "render deck", startedAtMs = 1L, kind = "local"))
    ).unsafeRunSync()
    assertEquals(second.warning, None, "same wait set must not re-warn every turn")
    assertEquals(second.changed, false)

  test("G2 回合尾门：空集清栅栏、零警告（正常收尾路径零行为漂移）"):
    BgTaskLedger.armBarrier("sid-g2", List("job-old")).unsafeRunSync()
    val out = BgTurnEndGate.onTurnEnd("sid-g2", Nil).unsafeRunSync()
    assertEquals(out.warning, None)
    assertEquals(out.waitingCount, 0)
    assertEquals(BgTaskLedger.barrierOf("sid-g2").unsafeRunSync(), None)

  test("G3 空 sessionId 全 no-op（无主任务不落账不挂栅栏）"):
    BgTaskLedger.recordCompletion("", "job-e", "d", "completed", payload).unsafeRunSync()
    BgTaskLedger.markOrphaned("", List(("job-e", "d", None))).unsafeRunSync()
    val changed = BgTaskLedger.armBarrier("", List("job-e")).unsafeRunSync()
    assertEquals(changed, false)
    assertEquals(BgTaskLedger.pending("").unsafeRunSync(), Nil)
end BgTaskLedgerSpec
