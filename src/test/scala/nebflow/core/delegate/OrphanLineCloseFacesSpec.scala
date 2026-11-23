package nebflow.core.delegate

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.shared.{PathUtil, SubAgentTask}

/**
 * 面板行收口面补齐（2026-11-21 甲′）spec：
 *  - C1 终态行（cancelled）墓碑保留；C2 活行（running）墓碑保留；
 *  - C3 终态行（failed）重启旗清除；C4 活行（pending-recovery）重启旗保留；
 *  - C5 settled 联动：终态取消行保留墓碑且视图收口 ⇒ 历史窗可移（legacy 归档证据）。
 *  - C6 clearCancelRequested 幂等零写；查无记录 = Left。
 */
class OrphanLineCloseFacesSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot
  private var tmp: os.Path = os.root / "nonexistent"

  override def beforeEach(context: munit.BeforeEach): Unit =
    tmp = os.temp.dir(prefix = "orphan-close")
    PathUtil.setDataRoot(tmp / "data")

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(originalRoot)
    os.remove.all(tmp)

  private def mkRecord(id: String, taskId: String) = DelegateRegistry.Record(
    id = id,
    executor = "nebflow",
    cwd = tmp.toString,
    project = None,
    taskId = Some(taskId),
    parentSessionId = "parent-close",
    title = s"close $id",
    createdAt = System.currentTimeMillis() - 120_000L
  )

  private def mkRow(id: String, status: String) = SubAgentTask(
    taskId = id,
    parentSessionId = "parent-close",
    agentName = "nebflow",
    prompt = "p",
    description = s"close-$id",
    status = status,
    retryCount = 0,
    spawnedAt = System.currentTimeMillis() - 60_000L,
    completedAt = Some(System.currentTimeMillis()),
    lastError = None,
    source = "delegate"
  )

  /** 行存储：真实 SubAgentTaskStore（与 DelegateLedgerReconcileSpec 同 tmp 形态）。 */
  private def store(): nebflow.agent.SubAgentTaskStore =
    new nebflow.agent.SubAgentTaskStore(PathUtil.dataRoot / "subagent-tasks")

  private def seed(id: String, status: String): IO[Unit] =
    store().recordTask(mkRow(id, status))

  test("C1 终态行（cancelled）墓碑保留；C2 活行（running）墓碑保留") {
    val terminal = "delegate-nebflow-c1"
    val live = "delegate-nebflow-c2"
    (for
      _ <- seed(terminal, "cancelled")
      _ <- seed(live, "running")
      _ <- IO(DelegateRegistry.put(mkRecord(terminal, terminal)))
      _ <- IO(DelegateRegistry.put(mkRecord(live, live)))
      _ <- IO(DelegateRegistry.markCancelRequested(terminal, "the user panel"))
      _ <- IO(DelegateRegistry.markCancelRequested(live, "the user panel"))
      n <- DelegateLedgerReconcile.reconcileTerminalMarks(store())
      t <- IO(DelegateRegistry.find(terminal).get)
      l <- IO(DelegateRegistry.find(live).get)
      _ = assertEquals(n, 1, "exactly the terminal row is cleared")
      _ = assert(t.cancelRequestedAt.isDefined, "terminal row tombstone remains as revival protection")
      _ = assertEquals(t.cancelRequestedBy, Some("the user panel"), "tombstone initiator is retained")
      _ = assert(l.cancelRequestedAt.isDefined, "live row tombstone kept (fail-close)")
    yield ()).unsafeRunSync()
  }

  test("C3 终态行（failed）重启旗清除；C4 活行（pending-recovery）重启旗保留") {
    val terminal = "delegate-nebflow-c3"
    val manual = "delegate-nebflow-c4"
    (for
      _ <- seed(terminal, "failed")
      _ <- seed(manual, "pending-recovery")
      _ <- IO(DelegateRegistry.put(mkRecord(terminal, terminal).copy(restartInterruptedAt = Some(1L))))
      _ <- IO(DelegateRegistry.put(mkRecord(manual, manual).copy(restartInterruptedAt = Some(1L))))
      _ <- DelegateLedgerReconcile.reconcileTerminalMarks(store())
      t <- IO(DelegateRegistry.find(terminal).get)
      m <- IO(DelegateRegistry.find(manual).get)
      _ = assertEquals(t.restartInterruptedAt, None, "terminal row restart flag cleared")
      _ = assertEquals(m.restartInterruptedAt, Some(1L), "pending-recovery keeps the flag (manual restore stays open)")
    yield ()).unsafeRunSync()
  }

  test("C5 settled 联动：终态取消行保留墓碑且视图收口 ⇒ 历史窗可移") {
    val id = "delegate-nebflow-c5"
    (for
      _ <- seed(id, "cancelled")
      _ <- IO(DelegateRegistry.put(mkRecord(id, id).copy(cancelRequestedAt = Some(1L), archivedAt = Some(2L))))
      before <- IO(DelegateVisibility.readOf(id))
      _ = assert(before.settled, "cancelled task makes tombstone non-live in the view")
      _ <- DelegateLedgerReconcile.reconcileTerminalMarks(store())
      after <- IO(DelegateVisibility.readOf(id))
      _ = assert(after.settled, "cancelled task stays settled with legacy archive evidence")
      _ = assertEquals(DelegateRegistry.find(id).flatMap(_.cancelRequestedAt), Some(1L), "recovery tombstone remains")
      // 存量归档旗 = 只读兼容折叠成历史窗（DelegateVisibility.historyWindow:140）。
      _ = assert(after.historyWindow, "settled + legacy archive evidence ⇒ folds into history window")
      _ <- IO(DelegateRegistry.setDisposition(id, DelegateRegistry.DispositionAcknowledged).fold(e => fail(e), _ => ()))
      done <- IO(DelegateVisibility.readOf(id))
      _ = assert(done.historyWindow, "settled + disposition ⇒ movable to history window")
    yield ()).unsafeRunSync()
  }

  test("C6 clearCancelRequested 幂等零写；查无记录 = Left") {
    val id = "delegate-nebflow-c6"
    DelegateRegistry.put(mkRecord(id, id))
    assertEquals(DelegateRegistry.clearCancelRequested(id), Right(()), "untagged = idempotent zero-write")
    DelegateRegistry.markCancelRequested(id, "x")
    assertEquals(DelegateRegistry.clearCancelRequested(id), Right(()))
    assertEquals(DelegateRegistry.find(id).get.cancelRequestedAt, None)
    assert(DelegateRegistry.clearCancelRequested("delegate-nebflow-ghost").isLeft)
  }

end OrphanLineCloseFacesSpec
