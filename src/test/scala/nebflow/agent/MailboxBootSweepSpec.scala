package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.core.SessionStore
import nebflow.shared.{PathUtil, SubAgentTask}

/**
 * 邮箱 boot 对账合成腿（[[MailboxBootSweep]]）验收：
 *
 *   1. **tmp 残片清理**（kill -9 窗口1 恢复规则）——run 无条件清除 pending 目录
 *      下的 `*.tmp` 中间态文件；
 *   2. **账面合成补投**——上一 boot 纪元窗内落终态的 delegate 行，父会话邮箱既无
 *      条目又无消费墓碑 ⇒ 以账面真源合成补投条目（identity = taskId，payload 诚实
 *      标注合成来源）；
 *   3. **两道防重闸**——boot 窗闸（窗口外旧账不追）+ 墓碑闸（已消费不重合成）；
 *      「账信相符」（条目在盘）与「父不在场」零动作。
 *
 * 全部纯桩驱动（文件 store 真实例 + 真 SessionStore），零墙钟判据
 * （completedAt 只作窗口成员判定，窗口本身由两次 run 的滚动纪元构成）。
 */
class MailboxBootSweepSpec extends CatsEffectSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-mailbox-boot-sweep"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    nebflow.ForceRemove.removeAll(tempRoot)
    os.makeDir.all(tempRoot)
    // 单实例：SessionStore 的会话索引在内存（indexRef）——跨实例新建 = run 里
    // 「父在场」判定恒假，合成腿永不触发（首轮实证）。盘面由 beforeEach 清。
    taskStore = SubAgentTaskStore(tempRoot / "subagent-tasks")
    sessionStore = SessionStore(tempRoot / "sessions", tempRoot / "tasks")
    ()

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  private var taskStore: SubAgentTaskStore = null
  private var sessionStore: SessionStore = null

  private def run(): IO[MailboxBootSweep.Report] = MailboxBootSweep.run(taskStore, sessionStore)

  private def row(
    taskId: String,
    parent: String,
    status: String,
    source: String = "delegate",
    completedAt: Option[Long] = Some(System.currentTimeMillis())
  ): SubAgentTask =
    SubAgentTask(
      taskId = taskId,
      parentSessionId = parent,
      agentName = "worker",
      prompt = "do the thing",
      description = "the thing",
      status = status,
      retryCount = 0,
      spawnedAt = System.currentTimeMillis() - 60_000,
      completedAt = completedAt,
      lastError = if status == "failed" then Some("it broke") else None,
      source = source
    )

  /** run#1 播种 boot 纪元（首 run = 空窗安全侧，零合成）。 */
  private def seedEpoch(): IO[MailboxBootSweep.Report] = run()

  test("first boot seeds the epoch with an EMPTY window — no synthesis ever fires (pre-activation ledger untouched)") {
    for
      s <- sessionStore.getOrCreateSession("parent-1", "p1")
      _ = assert(s._2, "session created")
      _ <- taskStore.saveTasks("parent-1", List(row("delegate-a-11111111", "parent-1", "completed")))
      r <- seedEpoch()
      pend <- nebflow.core.mail.SessionMailbox.scanPending("parent-1")
    yield
      assertEquals(clue(r.synthesized), Nil, "first boot window is empty by construction")
      assertEquals(clue(pend), Nil, "no entry synthesized from the pre-activation ledger")
  }

  test("terminal delegate row landed in the previous window, box empty ⇒ boot synthesizes a replace-entry") {
    for
      _ <- seedEpoch() // 播种：窗口左沿 = 本次 thisBootMs
      _ <- sessionStore.getOrCreateSession("parent-2", "p2")
      _ <- taskStore.saveTasks("parent-2", List(row("delegate-b-22222222", "parent-2", "completed")))
      r <- run() // 窗口 = (run1.thisBoot, run2.thisBoot]
      pend <- nebflow.core.mail.SessionMailbox.scanPending("parent-2")
    yield
      assertEquals(clue(r.synthesized), List("delegate-b-22222222"), "missing terminal entry synthesized")
      assertEquals(clue(pend.map(_.identity)), List("delegate-b-22222222"), "identity == taskId (同一幂等键)")
      assertEquals(clue(pend.head.kind), "delegate-terminal")
      assert(
        clue(pend.head.event.payload).contains("synthesized from the task ledger"),
        "payload honestly declares its synthesized origin"
      )
      assertEquals(
        clue(pend.head.event.metadata("recoveredBy").flatMap(_.asString)),
        Some("mailbox-boot-reconcile")
      )
  }

  test("consumed terminal (tombstone in delivered.json) is NOT re-synthesized") {
    for
      _ <- seedEpoch()
      _ <- sessionStore.getOrCreateSession("parent-3", "p3")
      _ <- taskStore.saveTasks("parent-3", List(row("delegate-c-33333333", "parent-3", "completed")))
      // 上一进程已消费：unlink 落墓碑（条目已物理删除）。
      _ <- nebflow.core.mail.SessionMailbox.unlinkTaken("parent-3", List("delegate-c-33333333"))
      r <- run()
      pend <- nebflow.core.mail.SessionMailbox.scanPending("parent-3")
    yield
      assertEquals(clue(r.synthesized), Nil, "tombstone gate: delivered stays delivered")
      assertEquals(clue(pend), Nil)
  }

  test("entry already on disk (accounted) or parent session gone ⇒ zero action") {
    for
      _ <- seedEpoch()
      // 账信相符腿：条目已在盘。
      _ <- sessionStore.getOrCreateSession("parent-4", "p4")
      _ <- taskStore.saveTasks("parent-4", List(row("delegate-d-44444444", "parent-4", "failed")))
      _ <- nebflow.core.mail.SessionMailbox.mailOnce(
        "parent-4",
        nebflow.actor.AgentCommand.ExternalEvent(
          source = "delegate",
          eventType = "failed",
          payload = "already delivered by the live path",
          metadata = io.circe.JsonObject.empty,
          correlationId = Some("delegate-d-44444444")
        ),
        kind = "delegate-terminal",
        identity = "delegate-d-44444444"
      )
      // 父不在场腿：行在，父会话从不创建。
      _ <- taskStore.saveTasks("parent-gone", List(row("delegate-e-55555555", "parent-gone", "cancelled")))
      r <- run()
      pend4 <- nebflow.core.mail.SessionMailbox.scanPending("parent-4")
    yield
      assertEquals(clue(r.synthesized), Nil, "accounted rows and parent-gone rows are skipped")
      assertEquals(clue(pend4.map(_.identity)), List("delegate-d-44444444"), "live entry untouched")
  }

  test("running rows and subtask rows never synthesize (delegate terminal states only)") {
    for
      _ <- seedEpoch()
      _ <- sessionStore.getOrCreateSession("parent-5", "p5")
      _ <- taskStore.saveTasks(
        "parent-5",
        List(
          row("delegate-f-66666666", "parent-5", "running", completedAt = None),
          row("subtask-77777777", "parent-5", "completed", source = "subtask")
        )
      )
      r <- run()
      pend <- nebflow.core.mail.SessionMailbox.scanPending("parent-5")
    yield
      assertEquals(clue(r.synthesized), Nil)
      assertEquals(clue(pend), Nil)
  }

  test("tmp fragments are swept by run (report.tmpSwept)") {
    for
      _ <- IO.blocking {
        val pend = tempRoot / "sessions" / "parent-6" / "mailbox" / "pending"
        os.makeDir.all(pend)
        os.write.over(pend / "000001-crash.json.tmp", "{ half", createFolders = true)
      }
      r <- run()
      pend <- nebflow.core.mail.SessionMailbox.scanPending("parent-6")
    yield
      assertEquals(clue(r.tmpSwept), 1)
      assertEquals(clue(pend), Nil, "tmp never counts as an entry")
  }

  test("second run in the SAME boot does not re-synthesize (window rolled past the row's completedAt)") {
    for
      _ <- seedEpoch()
      _ <- sessionStore.getOrCreateSession("parent-7", "p7")
      _ <- taskStore.saveTasks("parent-7", List(row("delegate-h-88888888", "parent-7", "completed")))
      r2 <- run()
      // 同一进程内第二次 run：窗口已滚过该行的 completedAt ⇒ 不追（窗闸防重）。
      r3 <- run()
      pend <- nebflow.core.mail.SessionMailbox.scanPending("parent-7")
    yield
      assertEquals(clue(r2.synthesized), List("delegate-h-88888888"))
      assertEquals(clue(r3.synthesized), Nil, "second run: row is now outside the window")
      assertEquals(clue(pend.map(_.identity)), List("delegate-h-88888888"), "the first synthesis stays (零墙钟不丢弃)")
  }
end MailboxBootSweepSpec
