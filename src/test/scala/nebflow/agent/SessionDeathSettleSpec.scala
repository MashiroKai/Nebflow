package nebflow.agent

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.SessionStore
import nebflow.core.delegate.{DelegateRegistry, MergeQueue}
import nebflow.shared.{PathUtil, SubAgentTask}

/**
 * 会话死亡台账结算 spec（2026-10-28 · R1 同族监督持久性面；报告二 §3.4-②/
 * §6-R3 的行面后果收口）：
 *
 * delegate 会话死亡（引擎重启带走/进程消失）无终态事件时，boot sweep 把对应
 * 台账行落终态（interrupted）。旧 sweep 视野只有 running/restarting——已停靠
 * 合并轮的行死后永远无人终态化（实证四行僵尸：#942/#983/#1008/#1031 面板挂
 * 进行中/待合并 1-2d）。
 *
 * 红绿锚（spec 钉「重启后无僵尸行」）：
 *  - S1 ready-to-merge 且无合并队列成员 ⇒ sweep 落终态（failed + 重启指纹），
 *    registry 落重启打断旗，父 F2 队列收幂等通知；
 *  - S2 ready-to-merge 但有开放合并队列成员 ⇒ sweep 零触碰（合并轮在管——
 *    MergeQueueBootSweep 对账 owner-gone/已并，本 sweep 不越权）；
 *  - S3 全量不变量：sweep 后无「无队列成员的在飞行」残留（重启后无僵尸行）。
 */
class SessionDeathSettleSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot
  private var tmp: os.Path = os.root / "nonexistent"

  override def beforeEach(context: munit.BeforeEach): Unit =
    tmp = os.temp.dir(prefix = "sds")
    PathUtil.setDataRoot(tmp / "data")
    MergeQueue.reset()

  override def afterEach(context: munit.AfterEach): Unit =
    MergeQueue.reset()
    PathUtil.setDataRoot(originalRoot)
    os.remove.all(tmp)

  private def fixture(): (SubAgentTaskStore, SessionStore, os.Path) =
    val store = new SubAgentTaskStore(tmp / "subagent-tasks")
    val sessionStore = SessionStore(tmp / "sessions", tmp / "tasks")
    (store, sessionStore, tmp)

  private def mkTask(id: String, parent: String, status: String): SubAgentTask =
    SubAgentTask(
      taskId = id,
      parentSessionId = parent,
      agentName = "nebflow",
      prompt = "p",
      description = s"sds-$id",
      status = status,
      retryCount = 0,
      spawnedAt = System.currentTimeMillis() - 60_000L,
      completedAt = None,
      lastError = None,
      source = "delegate"
    )

  private def mkRecord(id: String, cwd: os.Path): Unit =
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = id,
        executor = "nebflow",
        cwd = cwd.toString,
        project = None,
        taskId = Some("42"),
        parentSessionId = "parent-sds",
        title = s"sds record $id",
        createdAt = System.currentTimeMillis() - 120_000L
      )
    )

  test("S1 ready-to-merge 僵尸（无队列成员）⇒ sweep 落终态 interrupted（行+旗+父通知）") {
    val (store, sessionStore, root) = fixture()
    val id = "delegate-nebflow-s1"
    val parent = sessionStore.createSession("Nebula", agentName = Some("Nebula")).unsafeRunSync()
    val task = mkTask(id, parent.id, "ready-to-merge").copy(parentSessionId = parent.id)
    store.recordTask(task).unsafeRunSync()
    val seat = os.temp.dir(prefix = "sds-seat")
    mkRecord(id, seat)
    val report = SubAgentStartupRecovery.sweep(store, sessionStore, None).unsafeRunSync()
    assert(report.terminalized.contains(id), s"zombie must be settled: ${report.summary}")
    val row = store.loadTasks(parent.id).unsafeRunSync().find(_.taskId == id).get
    assertEquals(row.status, "failed", "settled row must be terminal")
    assert(row.lastError.exists(_.startsWith(SubAgentStartupRecovery.RestartLossFingerprint)),
      "settled row must carry the restart-loss fingerprint (drives the interrupted flag)")
    assert(row.completedAt.isDefined)
    val rec = DelegateRegistry.find(id).get
    assert(rec.restartInterruptedAt.isDefined, "registry record must carry the restart-interrupted flag")
    os.remove.all(seat)
  }

  test("S2 ready-to-merge 但有开放队列成员 ⇒ sweep 零触碰（合并轮在管）") {
    val (store, sessionStore, root) = fixture()
    val id = "delegate-nebflow-s2"
    val parent = sessionStore.createSession("Nebula", agentName = Some("Nebula")).unsafeRunSync()
    store.recordTask(mkTask(id, parent.id, "ready-to-merge")).unsafeRunSync()
    // 外部腿 stub：放行通知（grant ③）投递成功 ⇒ 成员停在 turn-granted（open）
    // ——合并轮在管的形态。不接通道时 enqueue 即 pump 即 grant，通知不可达 =
    // owner-gone 关账，成员立即离开开放集（正是本测要排除的形态）。
    MergeQueue.enqueue(
      MergeQueue.Member(
        delegateId = id,
        branch = "delegate/x",
        baseWs = root.toString,
        baseBranch = "main",
        worktree = false,
        projectKey = "sds",
        taskId = None,
        parentSessionId = parent.id,
        description = "queued member",
        readyAt = System.currentTimeMillis(),
        channelKind = "external",
        external = Some(MergeQueue.ExternalLeg(_ => IO.pure(Right(()))))
      )
    )
    val report = SubAgentStartupRecovery.sweep(store, sessionStore, None).unsafeRunSync()
    assert(!report.terminalized.contains(id), s"queue-owned member must not be settled by this sweep: ${report.summary}")
    val row = store.loadTasks(parent.id).unsafeRunSync().find(_.taskId == id).get
    assertEquals(row.status, "ready-to-merge", "queue-owned member stays parked for the merge-queue boot reconcile")
  }

  test("S3 不变量：sweep 后无「无队列成员的在飞行」残留（重启后无僵尸行）") {
    val (store, sessionStore, root) = fixture()
    val parent = sessionStore.createSession("Nebula", agentName = Some("Nebula")).unsafeRunSync()
    val seat = os.temp.dir(prefix = "sds-seat3")
    val ids = List(
      ("delegate-nebflow-s3a", "running"),
      ("delegate-nebflow-s3b", "restarting"),
      ("delegate-nebflow-s3c", "ready-to-merge")
    )
    ids.foreach { (id, st) =>
      store.recordTask(mkTask(id, parent.id, st)).unsafeRunSync()
      mkRecord(id, seat)
    }
    SubAgentStartupRecovery.sweep(store, sessionStore, None).unsafeRunSync()
    val left = store.findAllTasks().unsafeRunSync().map(t => t.taskId -> t.status)
    assertEquals(left.filterNot((id, st) => st == "completed" || st == "failed" || st == "cancelled"), Nil,
      s"no in-flight zombie row may survive the boot sweep; got: $left")
    os.remove.all(seat)
  }
end SessionDeathSettleSpec
