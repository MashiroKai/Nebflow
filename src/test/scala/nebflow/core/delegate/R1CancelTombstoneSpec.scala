package nebflow.core.delegate

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.agent.DelegateRevival
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * R1 缝隙 C 修 spec（2026-10-28 实施批 · 报告二 §1.3 硬验收）：**取消墓碑**。
 *
 * 红绿锚：
 *  - 红（修前）：DelegateRegistry 无取消痕迹字段，复活资格七闸不查取消指纹 ⇒
 *    「已取消 × 被复活」僵尸（实证 e25cd0c2 revival#1 / b5646a58 revival#2）。
 *  - 绿（修后）：①`markCancelRequested` 幂等单写（已旗零重写；查无记录 Left）；
 *    ②旧盘面记录（无墓碑键）零迁移照读；③[[DelegateRevival.eligibility]] 七闸
 *    加一道——墓碑在场 ⇒ 复活拒绝（reason 指名 cancel tombstone）。
 */
class R1CancelTombstoneSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot
  private var tmp: os.Path = os.root / "nonexistent"

  override def beforeEach(context: munit.BeforeEach): Unit =
    tmp = os.temp.dir(prefix = "r1-tombstone")
    PathUtil.setDataRoot(tmp / "data")

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(originalRoot)
    os.remove.all(tmp)

  private def mkRecord(id: String) = DelegateRegistry.Record(
    id = id,
    executor = "nebflow",
    cwd = tmp.toString,
    project = None,
    taskId = None,
    parentSessionId = "parent-1",
    title = s"tombstone spec $id",
    createdAt = System.currentTimeMillis() - 60_000L
  )

  test("T1 墓碑单写：markCancelRequested 落旗 + 发起方随行，幂等零重写") {
    val id = "delegate-nebflow-t1"
    DelegateRegistry.put(mkRecord(id))
    val first = DelegateRegistry.markCancelRequested(id, "the user panel")
    assert(first.isRight, s"first write should succeed: $first")
    val ts1 = first.getOrElse(0L)
    val rec1 = DelegateRegistry.find(id).get
    assertEquals(rec1.cancelRequestedAt, Some(ts1))
    assertEquals(rec1.cancelRequestedBy, Some("the user panel"))
    // 幂等：第二次 = 原值返回、零重写。
    Thread.sleep(5)
    val second = DelegateRegistry.markCancelRequested(id, "someone-else")
    assertEquals(second, Right(ts1), "idempotent re-write must return the original timestamp")
    val rec2 = DelegateRegistry.find(id).get
    assertEquals(rec2.cancelRequestedAt, Some(ts1), "idempotent re-write must not overwrite the tombstone")
    assertEquals(rec2.cancelRequestedBy, Some("the user panel"), "first writer wins")
  }

  test("T2 查无记录 = Left（墓碑只在真实 spawn 记录上生效）") {
    val res = DelegateRegistry.markCancelRequested("delegate-nebflow-ghost", "the user panel")
    assert(res.isLeft, "tombstone on a non-existent record must be refused")
  }

  test("T3 旧盘面记录（无墓碑键）零迁移照读（解码缺键 = None）") {
    val id = "delegate-nebflow-t3"
    // 手写一条无 cancelRequestedAt/cancelRequestedBy 键的旧形态 JSON。
    val dir = tmp / "data" / "delegates"
    os.makeDir.all(dir)
    os.write.over(
      dir / s"$id.json",
      """{"id":"delegate-nebflow-t3","executor":"nebflow","cwd":"/tmp","project":null,"taskId":null,
        |"parentSessionId":"parent-1","title":"legacy","createdAt":1700000000000}""".stripMargin
    )
    val rec = DelegateRegistry.find(id)
    assert(rec.isDefined, "legacy record without tombstone keys must still decode")
    assertEquals(rec.get.cancelRequestedAt, None)
    assertEquals(rec.get.cancelRequestedBy, None)
  }

  test("T4 复活资格七闸加一道：墓碑在场 ⇒ 永不复活（reason 指名 cancel tombstone）") {
    val id = "delegate-nebflow-t4"
    val parentSession = os.temp.dir(prefix = "r1-t4-parent")
    val sessionStore = new nebflow.core.SessionStore(parentSession / "sessions", parentSession / "tasks")
    val seat = os.temp.dir(prefix = "r1-t4-seat")
    DelegateRegistry.put(mkRecord(id).copy(cwd = seat.toString))
    DelegateRegistry.markCancelRequested(id, "the user panel")
    val task = nebflow.shared.SubAgentTask(
      taskId = id,
      parentSessionId = "parent-1",
      agentName = "nebflow",
      prompt = "p",
      description = "d",
      status = "running", // 行仍 running——墓碑必须压过它
      retryCount = 0,
      spawnedAt = System.currentTimeMillis() - 1000L,
      completedAt = None,
      lastError = None,
      source = "delegate"
    )
    val verdict = DelegateRevival.eligibility(sessionStore, task).unsafeRunSync()
    assert(verdict.exists(_.contains("cancel tombstone")), s"revival must be refused by the tombstone gate, got: $verdict")
    os.remove.all(parentSession)
    os.remove.all(seat)
  }

  test("T5 无墓碑的同形孤儿：资格判读不因本批改变（新闸只在墓碑在场时触发）") {
    val id = "delegate-nebflow-t5"
    val parentSession = os.temp.dir(prefix = "r1-t5-parent")
    val sessionStore = new nebflow.core.SessionStore(parentSession / "sessions", parentSession / "tasks")
    // 其余六闸全过（父会话在、seat 在、预算内、年龄内）——判读只由墓碑有无分叉。
    val parent = sessionStore.createSession("Nebula", agentName = Some("Nebula")).unsafeRunSync()
    val seat = os.temp.dir(prefix = "r1-t5-seat")
    DelegateRegistry.put(mkRecord(id).copy(cwd = seat.toString))
    val task = nebflow.shared.SubAgentTask(
      taskId = id,
      parentSessionId = parent.id,
      agentName = "nebflow",
      prompt = "p",
      description = "d",
      status = "running",
      retryCount = 0,
      spawnedAt = System.currentTimeMillis() - 1000L,
      completedAt = None,
      lastError = None,
      source = "delegate"
    )
    val verdict = DelegateRevival.eligibility(sessionStore, task).unsafeRunSync()
    assertEquals(verdict, None, "no tombstone => the new gate stays silent (existing gates unchanged)")
    os.remove.all(parentSession)
    os.remove.all(seat)
  }
end R1CancelTombstoneSpec
