package nebflow.core.delegate

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, AgentCommand, AgentEvent, Behavior}
import nebflow.shared.PathUtil

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*

/**
 * 合并队列「队头堵塞」治理判读（steward 批 D3-②③，2026-10-23）：
 *
 *  - ③ 验收「队头堵塞跳过」：合并轮**被拒**（keepForRetry=true）的队头不再堵塞
 *    后续——单飞行窗立即放行轮转下一条，被拒条目**留队待重试**（pendingRetry），
 *    且不被重复投（待重试条目不参与授窗）；
 *  - ② 验收「提醒不绑活体」：注入目标已死 ⇒ 不盲投、不盖章、不持窗——条目留队
 *    待重试 + 账本行 `reminder-deferred`（提醒持久化面），下一条即刻轮转；
 *  - ② 复活轮：`reattach` 换绑新子 actor + 清待重试标记 + 队首窗空闲 ⇒ 立即重投；
 *    已发过提醒的合并轮只换绑不重投（断点续跑轮续推）。
 *
 * 注入面 = 真 actor（TestSink 收 AgentCommand.UserInput，MergeQueueSpec 同款）。
 */
class MergeQueueHeadSkipSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-mq-head-skip"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    MergeQueue.reset()
    os.remove.all(tempRoot / "chains")
    MergeQueue.installLivenessProbe(_ => IO.pure(true))

  override def afterEach(context: munit.AfterEach): Unit =
    MergeQueue.reset()
    MergeQueue.installLivenessProbe(_ => IO.pure(true))

  /** 收单 actor：把收到的 UserInput 记进共享缓冲。 */
  private class Sink(records: ListBuffer[String]) extends Behavior[AgentCommand]:
    def receive(ctx: nebflow.actor.ActorContext[AgentCommand], msg: AgentCommand): IO[Behavior[AgentCommand]] =
      msg match
        case AgentCommand.UserInput(text, _, _, _, _, _, _, _, _, _, _, _, _) => IO { records += text; this }
        case _ => IO.pure(this)

  private def entry(
    system: ActorSystem,
    records: ListBuffer[String],
    delegateId: String,
    projectKey: String,
    readyAt: Long
  ): IO[MergeQueue.Entry] =
    for
      child <- system.spawn(new Sink(records), s"child-$delegateId")
      sup <- system.spawn(
        new Behavior[AgentEvent]:
          def receive(ctx: nebflow.actor.ActorContext[AgentEvent], msg: AgentEvent): IO[Behavior[AgentEvent]] =
            IO.pure(this),
        s"sup-$delegateId"
      )
    yield MergeQueue.Entry(
      delegateId = delegateId,
      branch = s"delegate/$delegateId",
      baseWs = tempRoot.toString,
      baseBranch = "main",
      worktree = false,
      projectKey = projectKey,
      taskId = Some("31"),
      parentSessionId = "nebula-root",
      description = s"task for $delegateId",
      readyAt = readyAt,
      childRef = child,
      supervisorRef = sup
    )

  private def chainActions(delegateId: String): List[String] =
    ChainStore.listSync().flatMap(_.entries).filter(_.kind == MergeQueue.EntryKind).flatMap { e =>
      val c = e.payload.hcursor
      c.get[String]("delegateId").toOption.filter(_ == delegateId).map(_ => c.get[String]("action").toOption.getOrElse(""))
    }

  // ── ③ 验收：队头被拒不堵塞后续 ────────────────────────────────────────

  test("③ refused head no longer blocks: window released, next entry injected, head KEPT pending retry (not re-injected)") {
    val system = ActorSystem(s"mq-hs-1-${scala.util.Random.nextInt(100000)}")
    val records = ListBuffer.empty[String]
    for
      a <- entry(system, records, "d-a", "p1", readyAt = 1_000L)
      b <- entry(system, records, "d-b", "p1", readyAt = 2_000L)
      _ <- IO { MergeQueue.enqueue(a) }
      _ <- IO { MergeQueue.enqueue(b) } // b 排队在飞队头之后（窗持，等待轮转）
      _ <- IO.sleep(60.millis)
      first = records.toList // a injected, window held
      // 队头被拒（合并轮冲突/机械验收拒绝的终态清理腿）：
      _ <- IO { MergeQueue.onTerminal("d-a", keepForRetry = true) }
      _ <- IO.sleep(60.millis)
      afterRefusal = records.toList // b injected (skip happened)
      aEntry = MergeQueue.find("d-a")
      // b 终态出队后：待重试的 a 不重投（无人给死条目重开合并轮）。
      _ <- IO { MergeQueue.onTerminal("d-b") }
      _ <- IO.sleep(60.millis)
      afterB = records.toList
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(first.size, 1, "the head was injected first (single-flight)")
      assertEquals(afterRefusal.size, 2, "the refused head no longer blocks: the NEXT entry got its merge turn")
      assert(afterRefusal(1).contains("delegate/d-b"), s"the skipped-to entry is d-b, got: ${afterRefusal(1).take(120)}")
      assert(aEntry.isDefined, "the refused head is KEPT in the queue (pending retry), not dropped")
      assertEquals(aEntry.map(_.pendingRetry), Some(true), "the refused head carries the pending-retry flag")
      assert(chainActions("d-a").contains("refused-keep-queued"), "the refusal is audited as refused-keep-queued")
      assertEquals(afterB.size, 2, "the pending-retry head is never re-injected while parked")
      assert(MergeQueue.find("d-b").isEmpty, "the done entry is dequeued")
  }

  // ── ② 验收：提醒不绑活体（目标已死 ⇒ 留队待重试 + 账本行）────────────

  test("② dead injection target: reminder deferred (not stamped, window not held), next entry turns immediately, ledger row lands") {
    val system = ActorSystem(s"mq-hs-2-${scala.util.Random.nextInt(100000)}")
    val records = ListBuffer.empty[String]
    for
      a <- entry(system, records, "d-x", "p2", readyAt = 1_000L)
      b <- entry(system, records, "d-y", "p2", readyAt = 2_000L)
      _ = MergeQueue.installLivenessProbe(ref => IO.pure(ref != a.childRef)) // d-x 的会话已死
      _ <- IO { MergeQueue.enqueue(a) }
      _ <- IO.sleep(60.millis)
      afterA = records.toList
      xEntry = MergeQueue.find("d-x")
      _ <- IO { MergeQueue.enqueue(b) }
      _ <- IO.sleep(60.millis)
      afterB = records.toList
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(afterA, Nil, "the reminder is NOT blind-fired into the dead session's queue")
      assert(xEntry.isDefined, "the undeliverable entry is KEPT (pending retry), not dropped")
      assertEquals(xEntry.map(_.pendingRetry), Some(true))
      assertEquals(xEntry.flatMap(_.reminderSentAt), None, "the reminder is not stamped (no phantom merge round)")
      assert(chainActions("d-x").contains("reminder-deferred"), "the deferred reminder is persisted in the ledger")
      assertEquals(afterB.size, 1, "the next entry turned immediately — the dead head did not hold the window")
      assert(afterB.head.contains("delegate/d-y"), s"the injected one is d-y, got: ${afterB.head.take(120)}")
  }

  // ── ② 复活轮：reattach 换绑 + 清标记 + 立即重投 ──────────────────────

  test("② revival: reattach swaps the child ref, clears pending-retry, and re-fires the deferred reminder to the LIVE session") {
    val system = ActorSystem(s"mq-hs-3-${scala.util.Random.nextInt(100000)}")
    val records = ListBuffer.empty[String]
    for
      a <- entry(system, records, "d-r", "p3", readyAt = 1_000L)
      _ = MergeQueue.installLivenessProbe(ref => IO.pure(ref != a.childRef)) // 旧会话已死
      _ <- IO { MergeQueue.enqueue(a) }
      _ <- IO.sleep(60.millis)
      before = records.toList
      // 复活：监督者 respawn 出新子 actor → reattach 换绑（事件驱动）。
      revivedRecords = ListBuffer.empty[String]
      newChild <- system.spawn(new Sink(revivedRecords), "child-d-r-respawned")
      _ <- IO { MergeQueue.reattach("d-r", newChild) }
      _ <- IO.sleep(60.millis)
      after = revivedRecords.toList
      rEntry = MergeQueue.find("d-r")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(before, Nil, "sanity: the deferred head was not injected while dead")
      assertEquals(after.size, 1, "the revival re-fired the merge reminder to the LIVE session")
      assert(after.head.contains("delegate/d-r"), s"the revived brief targets d-r, got: ${after.head.take(120)}")
      assertEquals(rEntry.map(_.pendingRetry), Some(false), "the pending-retry flag is cleared on revival")
      assert(chainActions("d-r").contains("child-reattached"), "the rebind is audited")
  }

  test("② reattach on an in-flight merge round (reminder already sent) only rebinds — no duplicate merge turn") {
    val system = ActorSystem(s"mq-hs-4-${scala.util.Random.nextInt(100000)}")
    val records = ListBuffer.empty[String]
    for
      a <- entry(system, records, "d-f", "p4", readyAt = 1_000L)
      _ <- IO { MergeQueue.enqueue(a) } // 提醒已发（合并轮在飞）
      _ <- IO.sleep(60.millis)
      before = records.toList
      newRecords = ListBuffer.empty[String]
      newChild <- system.spawn(new Sink(newRecords), "child-d-f-respawned")
      _ <- IO { MergeQueue.reattach("d-f", newChild) }
      _ <- IO.sleep(60.millis)
      after = newRecords.toList
      total = records.toList
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(before.size, 1, "the merge round was injected once")
      assertEquals(after, Nil, "the rebind does NOT duplicate the merge turn (resume rides recovered messages)")
      assertEquals(total.size, 1, "exactly one reminder overall")
      assert(MergeQueue.find("d-f").isDefined, "the re-bound entry is still queued (merge round in flight)")
  }

end MergeQueueHeadSkipSpec
