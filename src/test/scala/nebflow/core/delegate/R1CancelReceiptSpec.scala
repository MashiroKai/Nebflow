package nebflow.core.delegate

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.agent.SubAgentTaskStore
import nebflow.shared.{PathUtil, SubAgentTask}

import scala.concurrent.duration.*

/**
 * R1 缝隙 A 修 spec：**cancel 后回执确认**（fire-and-forget tell 改造的确认半边）。
 *
 * 红绿锚：
 *  - 红（修前）：`sup ! Cancelled` 是 mailbox offer（从不失败）——监督者已死 ⇒
 *    零写入零报错、工具却回成功文案（缝隙 A）。
 *  - 绿（修后）：[[CancelReceipt.awaitTerminal]] 有界轮询任务行翻 `cancelled`
 *    ——①已终态即刻确认；②窗内落终态确认；③永不落终态超窗返回 false
 *    （调用方降级兜底的触发信号）。fail-soft：查询异常视作未命中继续轮询。
 */
class R1CancelReceiptSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot
  private var tmp: os.Path = os.root / "nonexistent"

  override def beforeEach(context: munit.BeforeEach): Unit =
    tmp = os.temp.dir(prefix = "r1-receipt")
    PathUtil.setDataRoot(tmp / "data")

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(originalRoot)
    os.remove.all(tmp)

  private def mkStore(): SubAgentTaskStore = new SubAgentTaskStore(tmp / "subagent-tasks")

  private def mkTask(id: String, parent: String, status: String): SubAgentTask =
    SubAgentTask(
      taskId = id,
      parentSessionId = parent,
      agentName = "nebflow",
      prompt = "p",
      description = "d",
      status = status,
      retryCount = 0,
      spawnedAt = System.currentTimeMillis() - 60_000L,
      completedAt = None,
      lastError = None,
      source = "delegate"
    )

  test("C1 任务行已 cancelled ⇒ 即刻确认（true，零等待尾差）") {
    val store = mkStore()
    val id = "delegate-nebflow-c1"
    store.recordTask(mkTask(id, "parent-c1", "cancelled")).unsafeRunSync()
    val t0 = System.currentTimeMillis()
    val verified = CancelReceipt.awaitTerminal(store, id, budgetMs = 2000, pollMs = 50).unsafeRunSync()
    assertEquals(verified, true)
    assert(System.currentTimeMillis() - t0 < 1000, "already-settled row must confirm immediately")
  }

  test("C2 窗内落终态 ⇒ 确认（模拟监督者慢结算）") {
    val store = mkStore()
    val id = "delegate-nebflow-c2"
    store.recordTask(mkTask(id, "parent-c2", "running")).unsafeRunSync()
    // 400ms 后监督者结算（模拟 notifyParentAndStop 的 taskUpdate）。
    // 🔴 .start 必须当场 run：`.start` 只是「启动 fiber」的效果描述，C2 旧写法把
    // 它整个攒成 settle 值、awaitTerminal 结束后才 run——结算从未与轮询并发，
    // 轮询窗内永无写入（空转假红）。
    val settle = (IO.sleep(400.millis) *> store.updateStatus("parent-c2", id, "cancelled")).start.unsafeRunSync()
    val verified = CancelReceipt.awaitTerminal(store, id, budgetMs = 5000, pollMs = 50).unsafeRunSync()
    assertEquals(verified, true, "row settling within the budget must confirm")
    settle.join.unsafeRunSync()
  }

  test("C3 永不落终态 ⇒ 超窗返回 false（降级兜底的触发信号）") {
    val store = mkStore()
    val id = "delegate-nebflow-c3"
    store.recordTask(mkTask(id, "parent-c3", "running")).unsafeRunSync()
    val t0 = System.currentTimeMillis()
    val verified = CancelReceipt.awaitTerminal(store, id, budgetMs = 500, pollMs = 100).unsafeRunSync()
    assertEquals(verified, false, "never-settling row must time out to false")
    assert(System.currentTimeMillis() - t0 >= 450, "must respect the budget before giving up")
  }

  test("C4 行缺席（监督者已死从未写）⇒ 超窗 false（缝隙 A 的直接红形态）") {
    val store = mkStore()
    val verified =
      CancelReceipt.awaitTerminal(store, "delegate-nebflow-ghost", budgetMs = 400, pollMs = 100).unsafeRunSync()
    assertEquals(verified, false)
  }

  test("C5 查询异常 = fail-soft 视作未命中（不抛出拖垮 cancel 主链）") {
    val exploding = new nebflow.core.SubAgentTaskStorePort:
      def recordTask(task: SubAgentTask): IO[Unit] = IO.unit
      def updateStatus(
        parentSessionId: String,
        taskId: String,
        status: String,
        retryCount: Option[Int] = None,
        lastError: Option[String] = None,
        completedAt: Option[Long] = None
      ): IO[Unit] = IO.unit
      def findRunningTasks: IO[List[SubAgentTask]] = IO.pure(Nil)
      def findByTaskId(taskId: String): IO[Option[SubAgentTask]] = IO.raiseError(new RuntimeException("boom"))
    val verified = CancelReceipt.awaitTerminal(exploding, "delegate-nebflow-c5", budgetMs = 350, pollMs = 100).unsafeRunSync()
    assertEquals(verified, false, "probe failure must degrade to not-settled, never throw")
  }
end R1CancelReceiptSpec
