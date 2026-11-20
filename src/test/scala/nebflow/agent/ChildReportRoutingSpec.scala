package nebflow.agent

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import java.nio.file.Files
import nebflow.core.mail.SessionMailbox
import nebflow.shared.PathUtil

/**
 * Behaviour-level spec for the TWO delivery legs of child progress and their ORDER
 * (report 块可见性批，作者令 2026-11-20：「③ 阻塞/异步两腿路由与顺序测试」+
 * 「⑤ 四条端到端」).
 *
 * 两腿的真实差别只有一处：**父会话此刻能不能消费**。
 *   - 阻塞腿（blocking Subagent / 工具边界）：父在飞（`outstanding > 0`）⇒ 进度件
 *     **留箱**，下一个回合边界整批注入——不是丢件，也不是打断在飞回合；
 *   - 异步腿（普通非终态路由）：父 idle ⇒ 即扫即注（W3 idle 面全取）。
 *
 * 这里驱动的是**真实盘面**（[[SessionMailbox]] 落在临时 data root 上），判据是
 * 「父端最终看到几条、什么顺序、重试会不会多一条」——不打桩、不 mock 邮箱。
 */
class ChildReportRoutingSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot
  private var tmp: os.Path = null

  override def beforeEach(context: munit.BeforeEach): Unit =
    tmp = os.Path(Files.createTempDirectory("nb-report-routing"))
    PathUtil.setDataRoot(tmp)

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(originalRoot)
    if tmp != null && os.exists(tmp) then os.remove.all(tmp)

  /** 一件真实的中间进度（走生产单一构造点 [[ChildReport.event]]）。 */
  private def progress(identity: String, body: String): IO[Unit] =
    val e = ChildReport.event(identity, body, "child")
    SessionMailbox.mailOnce("parent", e, "child-report", identity, producer = "spec").void

  private def drainedBodies(): IO[List[String]] =
    MailboxDrain.drainIdleFace("parent").map(_.map(_.payload))

  // ── ③ 阻塞腿：绕过在飞批次，但只在边界注入；从不占屏障槽 ────────────────

  test("BLOCKING leg: progress bypasses an in-flight batch yet waits for the boundary; it never holds a barrier slot") {
    // 真批次结果（source=delegate）与一条中间进度同时在箱：批次未齐 ⇒ 结果留箱，
    // 进度件在**边界**放行（不打断回合、不占槽）。
    val terminal = ChildReport.event("child-report:child:9", "占位", "child")
    val delegateResult = terminal.copy(source = "delegate", eventType = "completed")
    val run = for
      _ <- progress("child-report:child:1", "第一段进度")
      _ <- SessionMailbox.mailOnce("parent", delegateResult, "delegate-terminal", "delegate-child-1", producer = "spec")
      held <- MailboxDrain.drainBarrierFace("parent", outstanding = 2)
      afterHeld <- IO.blocking(MailboxDrain.mailboxHeldCount("parent"))
      rest <- MailboxDrain.drainBarrierFace("parent", outstanding = 1)
      after <- IO.blocking(MailboxDrain.mailboxHeldCount("parent"))
    yield (held, afterHeld, rest, after)
    val (held, afterHeld, rest, after) = run.unsafeRunSync()
    assertEquals(held._1.map(_.payload), List("第一段进度"), "进度件在边界放行（不打断在飞回合，但不占屏障槽）；真批次结果留箱等齐")
    assertEquals(held._2, 1, "留箱 = 唯一拷贝在盘上（计入徽标深度），不是丢弃")
    assertEquals(rest._1.map(_.source), List("delegate"), "批次齐后结果照常注入")
    assertEquals(after, 0, "取走后箱空")
  }

  test("BLOCKING leg: progress never counts as a sub-agent result (no barrier slot is consumed)") {
    val e = ChildReport.event("child-report:child:1", "进度", "child")
    assertEquals(e.source, "child-report")
    assert(!TurnBoundaryDrains.isSubagentResult(e), "进度件不扣屏障槽（否则父端批数会错位）")
  }

  test("ordering: arrival order wins — the boundary never reorders queued progress") {
    val run = for
      _ <- progress("child-report:child:1", "A")
      _ <- progress("child-report:child:2", "B")
      _ <- progress("child-report:child:3", "C")
      taken <- MailboxDrain.drainBarrierFace("parent", outstanding = 0)
    yield taken._1.map(_.payload)
    assertEquals(run.unsafeRunSync(), List("A", "B", "C"), "注入顺序 = 到达顺序（保序，禁重排）")
  }

  // ── ③ 异步腿：idle 父会话 ⇒ 即扫即注 ──────────────────────────────────

  test("ASYNC leg: an idle parent consumes progress immediately, one turn per piece") {
    val run = for
      _ <- progress("child-report:child:1", "异步第一段")
      first <- drainedBodies()
      _ <- progress("child-report:child:2", "异步第二段")
      second <- drainedBodies()
    yield (first, second)
    val (first, second) = run.unsafeRunSync()
    assertEquals(first, List("异步第一段"), "idle 面不扣留已产出结果")
    assertEquals(second, List("异步第二段"), "后续进度照常即注，不与前一件合批")
  }

  // ── ⑤ 端到端 1/2：中间进度进箱、终态不进 ──────────────────────────────

  test("E2E final exclusion: only intermediate turns enqueue progress; the final text does not") {
    val run = for
      // 中间轮（continues = true, 带 report 块）⇒ 一件进度
      _ <- ChildReport.body("<report>中间进度</report>", continues = true) match
        case Some(body) => progress("child-report:child:1", body)
        case None => IO.unit
      // 终态轮（continues = false）⇒ 结构上不产出进度（正文只走 result channel）
      _ <- ChildReport.body("<report>最终结论</report>", continues = false) match
        case Some(body) => progress("child-report:child:2", body)
        case None => IO.unit
      got <- drainedBodies()
    yield got
    assertEquals(run.unsafeRunSync(), List("中间进度"), "同一条消息绝不两路都投")
  }

  test("E2E retry dedup: redelivery of the same identity never queues a second copy") {
    val run = for
      _ <- progress("child-report:child:1", "进度")
      // 重试腿：同一持久身份再投一次（恢复扫描 / 重派都走这条）。
      again <- SessionMailbox.mailOnce(
        "parent",
        ChildReport.event("child-report:child:1", "进度", "child"),
        "child-report",
        "child-report:child:1",
        producer = "spec-retry"
      )
      got <- drainedBodies()
    yield (again, got)
    val (again, got) = run.unsafeRunSync()
    assertEquals(again, SessionMailbox.MailResult.AlreadyPresent, "同身份重投被抑制（不是第二件）")
    assertEquals(got, List("进度"), "父端只看到一条")
  }

  test("E2E four-message turn: progress count equals the number of distinct identities, never duplicated") {
    val run = for
      _ <- progress("child-report:child:1", "一")
      _ <- progress("child-report:child:1", "一") // 重投（同身份）
      _ <- progress("child-report:child:2", "二")
      _ <- progress("child-report:child:2", "二") // 重投（同身份）
      _ <- progress("child-report:child:3", "三")
      got <- drainedBodies()
    yield got
    assertEquals(run.unsafeRunSync(), List("一", "二", "三"), "3 条独立消息 ⇒ 恰好 3 件，重投零放大")
  }
end ChildReportRoutingSpec
