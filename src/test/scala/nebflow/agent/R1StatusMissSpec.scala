package nebflow.agent

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.shared.{PathUtil, SubAgentTask}

/**
 * R1 缝隙 B 修 spec：**updateStatus 父文件/行不匹配 → 告警留痕不静默**。
 *
 * 红绿锚：
 *  - 红（修前）：行未命中 = 静默 no-op——「调用方以为写了」零痕迹（报告二 §1.3
 *    缝隙 B：取消意图全押任务行一行，行不匹配时什么都没发生且无人知晓）。
 *  - 绿（修后）：未命中 ⇒ ①`recentStatusMisses` 留痕（诊断/断言面）+ WARN 告警；
 *    ②状态机零改动（未命中仍不写、不凭空造行、不建父文件）；命中路径零新增留痕。
 */
class R1StatusMissSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot
  private var tmp: os.Path = os.root / "nonexistent"

  override def beforeEach(context: munit.BeforeEach): Unit =
    tmp = os.temp.dir(prefix = "r1-miss")
    PathUtil.setDataRoot(tmp / "data")

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(originalRoot)
    os.remove.all(tmp)

  private def mkTask(id: String, parent: String): SubAgentTask =
    SubAgentTask(
      taskId = id,
      parentSessionId = parent,
      agentName = "nebflow",
      prompt = "p",
      description = "d",
      status = "running",
      retryCount = 0,
      spawnedAt = System.currentTimeMillis() - 60_000L,
      completedAt = None,
      lastError = None,
      source = "delegate"
    )

  test("B1 父文件缺失的 updateStatus ⇒ 留痕 + 不建文件（状态机零改动）") {
    val store = new SubAgentTaskStore(tmp / "subagent-tasks")
    val before = store.recentStatusMisses.size
    store.updateStatus("parent-never-existed", "delegate-nebflow-b1", "cancelled").unsafeRunSync()
    val after = store.recentStatusMisses
    assertEquals(after.size, before + 1, "a missed write must leave a trace")
    assert(after.head.contains("parent-never-existed") && after.head.contains("delegate-nebflow-b1"),
      "trace must name the parent and the task")
    assert(!os.exists(tmp / "subagent-tasks" / "parent-never-existed.json"),
      "a missed write must NOT create the parent file (no fabricated rows)")
  }

  test("B2 父文件在但行不匹配 ⇒ 留痕 + 原文件零改写") {
    val store = new SubAgentTaskStore(tmp / "subagent-tasks")
    store.recordTask(mkTask("delegate-nebflow-b2", "parent-b2")).unsafeRunSync()
    val file = tmp / "subagent-tasks" / "parent-b2.json"
    val before = os.mtime(file)
    val missesBefore = store.recentStatusMisses.size
    store.updateStatus("parent-b2", "delegate-nebflow-other-row", "cancelled").unsafeRunSync()
    assertEquals(store.recentStatusMisses.size, missesBefore + 1, "row mismatch must leave a trace")
    assertEquals(os.mtime(file), before, "row mismatch must not rewrite the file")
    val row = store.loadTasks("parent-b2").unsafeRunSync().find(_.taskId == "delegate-nebflow-b2").get
    assertEquals(row.status, "running", "the real row stays untouched")
  }

  test("B3 命中路径零新增留痕（正常写不产生 miss 噪音）") {
    val store = new SubAgentTaskStore(tmp / "subagent-tasks")
    store.recordTask(mkTask("delegate-nebflow-b3", "parent-b3")).unsafeRunSync()
    val missesBefore = store.recentStatusMisses.size
    store.updateStatus("parent-b3", "delegate-nebflow-b3", "cancelled", completedAt = Some(1L)).unsafeRunSync()
    assertEquals(store.recentStatusMisses.size, missesBefore, "a hit must not add miss noise")
    val row = store.loadTasks("parent-b3").unsafeRunSync().find(_.taskId == "delegate-nebflow-b3").get
    assertEquals(row.status, "cancelled")
    assertEquals(row.completedAt, Some(1L))
  }
end R1StatusMissSpec
