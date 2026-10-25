package nebflow.core.delegate

import cats.effect.IO
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, AgentCommand, AgentEvent, Behavior}
import nebflow.shared.PathUtil

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*

/**
 * MergeQueue **滞留台账 + 重试注入 + 状态投影**判读（merge-queue 滞留两病灶修复批
 * 2026-10-25 腿②③的队列侧单点）：
 *
 *  - ① 被拒终态（keepForRetry）⇒ 滞留台账进位：retryCount +1、退避锚重置、
 *    拒绝通知复位；账本行携 retryCount；
 *  - ② 台账盖章面：markStallNotified / markStallEscalated / bumpStallAttempt；
 *  - ③ retryInject 通道腿：成功 = 重发 + 盖章 + 持窗 + `retry-injected` 行；
 *    失败 = 条目留队不动 + 退避进位；内部腿 = Left 标识（复活归 RevivalPort）；
 *  - ④ stallSnapshot：全队列投影（位次/持窗者/停靠/滞留台账字段）。
 */
class MergeQueueStallSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-mq-stall"
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

  private class Sink(records: ListBuffer[String]) extends Behavior[AgentCommand]:
    def receive(ctx: nebflow.actor.ActorContext[AgentCommand], msg: AgentCommand): IO[Behavior[AgentCommand]] =
      msg match
        case AgentCommand.UserInput(text, _, _, _, _, _, _, _, _, _, _, _, _) => IO { records += text; this }
        case _ => IO.pure(this)

  private def internalEntry(
    system: ActorSystem,
    delegateId: String,
    projectKey: String,
    readyAt: Long
  ): IO[MergeQueue.Entry] =
    for
      child <- system.spawn(new Sink(ListBuffer.empty[String]), s"child-$delegateId")
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

  private def channelEntry(delegateId: String, projectKey: String, readyAt: Long)(send: String => IO[Either[String, Unit]]): MergeQueue.Entry =
    MergeQueue.Entry(
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
      childRef = MergeQueue.NoopChild,
      supervisorRef = MergeQueue.NoopSupervisor,
      external = Some(MergeQueue.ExternalLeg(send))
    )

  private def chainActions(delegateId: String): List[(String, Json)] =
    ChainStore.listSync().flatMap(_.entries).filter(_.kind == MergeQueue.EntryKind).flatMap { e =>
      e.payload.hcursor.get[String]("delegateId").toOption
        .filter(_ == delegateId)
        .map(_ => (e.payload.hcursor.get[String]("action").toOption.getOrElse(""), e.payload))
    }

  // ── ① 被拒终态 ⇒ 滞留台账进位 ────────────────────────────────────────

  test("① refused terminal advances the stall ledger (retryCount, backoff anchor, notice reset) and the row carries retryCount") {
    val system = ActorSystem(s"mqs-1-${scala.util.Random.nextInt(100000)}")
    for
      entry <- internalEntry(system, "d-q1", "p-q1", readyAt = 1_000L)
      _ <- IO { MergeQueue.enqueue(entry) }
      _ <- IO.sleep(60.millis)
      _ <- IO { MergeQueue.onTerminal("d-q1", keepForRetry = true) }
      once = MergeQueue.find("d-q1")
      _ <- IO { MergeQueue.onTerminal("d-q1", keepForRetry = true) }
      twice = MergeQueue.find("d-q1")
      rows = chainActions("d-q1")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(once.map(_.retryCount), Some(1), "first refusal = attempt 1")
      assertEquals(once.map(_.pendingRetry), Some(true))
      assert(once.exists(_.lastAttemptAt > 0), "the backoff anchor is stamped")
      assertEquals(twice.map(_.retryCount), Some(2), "second refusal = attempt 2")
      val refusedRows = rows.collect { case (a, p) if a == "refused-keep-queued" => p }
      assertEquals(refusedRows.size, 2, s"both refusals audited: ${rows.map(_._1)}")
      val counts = refusedRows.flatMap(_.hcursor.get[Int]("retryCount").toOption)
      assertEquals(counts, List(1, 2), "the refusal rows carry the retry counter")
  }

  // ── ② 台账盖章面 ─────────────────────────────────────────────────────

  test("② stall bookkeeping marks flip the entry fields and land ledger rows") {
    val system = ActorSystem(s"mqs-2-${scala.util.Random.nextInt(100000)}")
    for
      entry <- internalEntry(system, "d-q2", "p-q2", readyAt = 1_000L)
      _ <- IO { MergeQueue.enqueue(entry) }
      _ <- IO.sleep(60.millis)
      _ <- IO { MergeQueue.onTerminal("d-q2", keepForRetry = true) }
      _ <- IO { MergeQueue.markStallNotified("d-q2") }
      notified = MergeQueue.find("d-q2")
      _ <- IO { MergeQueue.bumpStallAttempt("d-q2", "hygiene left src/Main.scala dirty") }
      bumped = MergeQueue.find("d-q2")
      _ <- IO { MergeQueue.markStallEscalated("d-q2") }
      escalated = MergeQueue.find("d-q2")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(notified.map(_.stallNotified), Some(true), "notice stamp")
      assertEquals(bumped.map(_.retryCount), Some(2), "bump advances the counter")
      assertEquals(bumped.map(_.stallNotified), Some(false), "bump resets the notice gate (next refusal re-notifies)")
      assertEquals(escalated.map(_.stallEscalated), Some(true), "escalation stamp")
      assert(chainActions("d-q2").exists(_._1 == "stall-notice-sent"), "notice row audited")
      assert(chainActions("d-q2").exists(_._1 == "stall-attempt"), "attempt row audited")
      assert(chainActions("d-q2").exists(_._1 == "stall-escalated"), "escalation row audited")
      val attemptRow = chainActions("d-q2").collectFirst { case ("stall-attempt", p) => p }.getOrElse(Json.Null)
      assert(
        attemptRow.hcursor.get[String]("reason").toOption.exists(_.contains("src/Main.scala")),
        s"the attempt row carries the blocking reason: $attemptRow"
      )
  }

  // ── ③ retryInject 通道腿 ─────────────────────────────────────────────

  test("③a channel-leg retry: success re-fires + stamps + HOLDS the window; failure keeps the entry parked and advances the counter") {
    val system = ActorSystem(s"mqs-3-${scala.util.Random.nextInt(100000)}")
    val sends = ListBuffer.empty[String]
    def sendOk(brief: String): IO[Either[String, Unit]] = IO { sends += brief; Right(()) }
    // 失败腿 = 第二次调用起失败（入队即注入的那次必须成功，条目才能在队）
    val failAfterFirst = new java.util.concurrent.atomic.AtomicBoolean(false)
    def sendFailOnRetry(brief: String): IO[Either[String, Unit]] =
      if failAfterFirst.get then IO.pure(Left("cli session gone"))
      else IO { failAfterFirst.set(true); Right(()) }
    val a = channelEntry("d-q3a", "p-q3", readyAt = 1_000L)(sendOk)
    val b = channelEntry("d-q3b", "p-q3b", readyAt = 1_000L)(sendFailOnRetry)
    for
      _ <- IO { MergeQueue.enqueue(a) } // 通道腿入队即注入（窗持）
      _ <- IO.sleep(60.millis)
      _ <- IO { MergeQueue.onTerminal("d-q3a", keepForRetry = true) } // 被拒停靠，窗释放
      windowFreeAfterRefusal = MergeQueue.isInFlight("p-q3")
      _ <- IO { MergeQueue.enqueue(b) }
      _ <- IO.sleep(60.millis)
      _ <- IO { MergeQueue.onTerminal("d-q3b", keepForRetry = true) }
      retryOk <- MergeQueue.retryInject(a)
      aAfter = MergeQueue.find("d-q3a")
      retryFail <- MergeQueue.retryInject(b)
      bAfter = MergeQueue.find("d-q3b")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(windowFreeAfterRefusal, false, "the refusal released the single-flight window")
      assertEquals(MergeQueue.isInFlight("p-q3"), true, "successful channel retry re-holds the single-flight window")
      assertEquals(retryOk, Right(()))
      assert(aAfter.flatMap(_.reminderSentAt).isDefined, "the retry re-stamps the merge round")
      assertEquals(sends.size, 2, "the brief was delivered twice (initial grant + retry)")
      assertEquals(chainActions("d-q3a").map(_._1).contains("retry-injected"), true, "the retry is audited")
      assertEquals(retryFail, Left("cli session gone"))
      assert(bAfter.isDefined, "a failed retry keeps the entry parked (never dropped)")
      assertEquals(bAfter.map(_.retryCount), Some(2), "the failed retry advanced the counter")
      assertEquals(MergeQueue.isInFlight("p-q3b"), false, "a failed retry does not hold the window")
  }

  test("③b internal-leg entry ⇒ retryInject returns the revival-port marker without touching the entry") {
    val system = ActorSystem(s"mqs-4-${scala.util.Random.nextInt(100000)}")
    for
      entry <- internalEntry(system, "d-q4", "p-q4", readyAt = 1_000L)
      _ <- IO { MergeQueue.enqueue(entry) }
      _ <- IO.sleep(60.millis)
      _ <- IO { MergeQueue.onTerminal("d-q4", keepForRetry = true) }
      e = MergeQueue.find("d-q4").getOrElse(fail("entry parked"))
      res <- MergeQueue.retryInject(e)
      after = MergeQueue.find("d-q4")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assert(res.isLeft, s"internal legs are routed to the RevivalPort: $res")
      assert(res.left.exists(_.contains("internal-leg")), s"the marker names the routing: $res")
      assertEquals(after.map(_.retryCount), Some(1), "the marker leg does not advance the counter")
  }

  // ── ④ stallSnapshot 投影 ─────────────────────────────────────────────

  test("④ stallSnapshot projects position, holder, parked flag and the stall ledger for the whole queue") {
    val system = ActorSystem(s"mqs-5-${scala.util.Random.nextInt(100000)}")
    for
      a <- internalEntry(system, "d-q5a", "p-q5", readyAt = 1_000L)
      _ <- IO { MergeQueue.enqueue(a) }
      _ <- IO.sleep(60.millis)
      _ <- IO { MergeQueue.onTerminal("d-q5a", keepForRetry = true) }
      b <- internalEntry(system, "d-q5b", "p-q5", readyAt = 2_000L)
      _ <- IO { MergeQueue.enqueue(b) } // 活条目轮转（停靠条目被 pump 跳过）
      _ <- IO.sleep(60.millis)
      snap = MergeQueue.stallSnapshot.filter(_.projectKey == "p-q5")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(snap.map(_.delegateId), List("d-q5a", "d-q5b"), "FIFO order preserved (readyAt)")
      val aView = snap.find(_.delegateId == "d-q5a").getOrElse(fail("a view"))
      val bView = snap.find(_.delegateId == "d-q5b").getOrElse(fail("b view"))
      assertEquals(aView.position, 1)
      assertEquals(aView.pendingRetry, true)
      assertEquals(aView.retryCount, 1)
      assertEquals(aView.stallNotified, false)
      assertEquals(bView.position, 2)
      assertEquals(bView.pendingRetry, false)
      assertEquals(bView.holder, Some("d-q5b"), "the live head holds the window")
      assertEquals(snap.map(_.total).distinct, List(2), "queue depth is projected")
  }

end MergeQueueStallSpec
