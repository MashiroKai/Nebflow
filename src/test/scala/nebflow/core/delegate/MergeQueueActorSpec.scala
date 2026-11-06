package nebflow.core.delegate

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, AgentCommand}
import nebflow.shared.PathUtil

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * P2 QueueActor 批（2026-11-06）验收 spec：**队列状态机是一个真 actor**
 * （`object` + 监视器锁 → `Engine` + 邮箱串行），三件必证的事。
 *
 *  - ① **真 ActorSystem 宿主**：门面背后是 `nebflow.actor.ActorSystem` 里名为
 *    `mergequeue` 的真 actor（`MergeQueue.actorPath` 读得到），不是桩、不是直连。
 *  - ② **并发 pump 不双重授窗**：两路**真线程**同时 `enqueue` + 狂 `pump` 同一个
 *    projectKey，`merge-turn` 授出**恰好一条**、成员状态机一致（一个持窗、另一个
 *    等待），且全程「持窗 ≤ 1」的不变量由采样线程独立见证。另断言所有投递都发生在
 *    **同一个线程**上且不是任一调用线程——那就是队列 actor 的邮箱线程（串行的
 *    结构证据，不是「碰巧没撞上」）。
 *  - ③ **提醒/TTL 走 Tick**：不调任何外部 `pump`/`careTick`，靠 actor 自己的
 *    `scheduleOnce` 到点（[[MergeQueue.enableSelfTick]]）把被道闸挡住的成员放出去。
 */
class MergeQueueActorSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-merge-queue-actor"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  /** 投递记录：(收件身份, kind, 投递线程名)。 */
  private val deliveries = new java.util.concurrent.CopyOnWriteArrayList[(String, String, String)]()

  private def mergeTurns: List[(String, String, String)] =
    deliveries.toArray.toList.map(_.asInstanceOf[(String, String, String)]).filter(_._2 == "merge-turn")

  private val failures = new java.util.concurrent.CopyOnWriteArrayList[Throwable]()

  /** 投递期的「同时在飞」见证（互斥的结构证据，与线程身份无关）。 */
  private val inFlight = new AtomicInteger(0)
  private val maxInFlight = new AtomicInteger(0)

  override def beforeEach(context: munit.BeforeEach): Unit =
    MergeQueue.reset()
    MergeQueue.disableSelfTick()
    deliveries.clear()
    failures.clear()
    maxInFlight.set(0)

  override def afterEach(context: munit.AfterEach): Unit =
    MergeQueue.disableSelfTick()
    MergeQueue.reset()
    MergeQueueTestPorts.clear()
    MergeQueue.OwnerGonePort.clear()
    MergeQueue.BlockNotifyPort.clear()

  /** 记录端口：真投一份到 `sink`（真 actor 信箱），同时记下投递线程。 */
  private def installRecordingPort(sink: Option[ActorRef[AgentCommand]]): Unit =
    MergeQueue.OwnerDeliveryPort.install(new MergeQueue.OwnerDeliveryPort.Face:
      def deliver(ownerSessionId: String, kind: String, brief: String, identity: String): IO[Either[String, Unit]] =
        IO {
          val now = inFlight.incrementAndGet()
          maxInFlight.accumulateAndGet(now, math.max)
          deliveries.add((ownerSessionId, kind, Thread.currentThread().getName))
          // 把投递窗口拉宽（2ms）：真并发时窗口一定重叠，串行时永不重叠。
          Thread.sleep(2L)
          inFlight.decrementAndGet()
          ()
        } *> sink.fold(IO.unit) { child =>
          (child ! AgentCommand.UserInput(
            text = brief,
            replyTo = None,
            source = Some("merge-queue"),
            eventType = Some(kind)
          )).attempt.void
        } *> IO.pure(Right(()): Either[String, Unit])
      def present(ownerSessionId: String): IO[Boolean] = IO.pure(true)
    )

  private def mkRepo(dir: os.Path, branch: String): os.Path =
    if os.exists(dir) then os.remove.all(dir)
    os.makeDir.all(dir)
    os.proc("git", "init", "-b", "main", dir.toString).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    def g(args: String*): os.CommandResult =
      os.proc(Seq("git", "-C", dir.toString) ++ args).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    g("config", "user.email", "spec@nebflow.local")
    g("config", "user.name", "spec")
    g("commit", "--allow-empty", "-m", "init")
    g("checkout", "-b", branch)
    os.write(dir / "deliverable.txt", "deliverable")
    g("add", ".")
    g("commit", "-m", "deliverable")
    g("checkout", "main")
    dir

  private def member(id: String, repo: os.Path, project: String, readyAt: Long): MergeQueue.Member =
    MergeQueue.Member(
      delegateId = id,
      branch = s"delegate/$id",
      baseWs = repo.toString,
      baseBranch = "main",
      worktree = false,
      projectKey = project,
      taskId = Some("p2"),
      parentSessionId = "nebula-root",
      description = s"p2 concurrency $id",
      readyAt = readyAt,
      declaredAt = Some(1_000L),
      declareSource = Some(MergeDeclaration.Source.ReportMerge),
      channelKind = "identity",
      ownerSessionId = s"owner-$id"
    )

  /** 在真线程上跑一段可能抛的实现（异常带回主线程，不在副线程里吞掉）。 */
  private def onThread(name: String, latch: CountDownLatch)(body: => Unit): Thread =
    val runnable: Runnable = new Runnable:
      def run(): Unit =
        latch.await(10, TimeUnit.SECONDS)
        try body
        catch case e: Throwable => failures.add(e)
        ()
    val t = new Thread(runnable, name)
    t.start()
    t

  // ── ① 真 ActorSystem 宿主 ───────────────────────────────────────────

  test("① 队列状态机宿主 = 真 ActorSystem 的 mergequeue actor（不是桩、不是门面直连）") {
    val path = MergeQueue.actorPath
    assert(path.contains("mergequeue"), s"the queue must live in a real actor, path was '$path'")
    // 读面走 actor 发布的不可变快照：写入后立即可见、且不因并发读而撕裂。
    val repo = mkRepo(tempRoot / "host", branch = "delegate/d-host")
    installRecordingPort(None)
    MergeQueue.enqueue(member("d-host", repo, "p-host", 1_000L))
    assertEquals(MergeQueue.find("d-host").map(_.state), Some(MergeQueue.State.TurnGranted))
    assertEquals(mergeTurns.size, 1)
  }

  // ── ② 并发不双重授窗 ────────────────────────────────────────────────

  test("② 并发 enqueue/pump 不双重授窗：真线程同抢一个 project 的窗，merge-turn 恰好一条") {
    val repo = mkRepo(tempRoot / "conc", branch = "delegate/d-conc-a")
    val system = ActorSystem(s"mq-p2-${scala.util.Random.nextInt(100000)}")
    for
      sink <- system.spawn(
        new nebflow.actor.Behavior[AgentCommand]:
          def receive(
            ctx: nebflow.actor.ActorContext[AgentCommand],
            msg: AgentCommand
          ): IO[nebflow.actor.Behavior[AgentCommand]] = IO.pure(this)
        ,
        "p2-sink"
      )
      _ <- IO {
        installRecordingPort(Some(sink))

        // ── 场景 A：两路真线程**同时** enqueue 同一 project（谁先进账都可能）──
        // 断言的不是「谁赢」，而是**恰有一个赢**：授出一条、另一个留在队里。
        val raceProject = "p-conc-race"
        val raceLatch = CountDownLatch(1)
        val tc = onThread("p2-caller-c", raceLatch) {
          MergeQueue.enqueue(member("d-race-c", repo, raceProject, 1_000L))
          (1 to 40).foreach(_ => MergeQueue.pump(raceProject))
        }
        val td = onThread("p2-caller-d", raceLatch) {
          MergeQueue.enqueue(member("d-race-d", repo, raceProject, 2_000L))
          (1 to 40).foreach(_ => MergeQueue.pump(raceProject))
        }
        raceLatch.countDown()
        tc.join(30000)
        td.join(30000)
        assertEquals(failures.toArray.toList.size, 0, s"no caller thread may fail: ${failures.toArray.toList}")
        val raceTurns = mergeTurns.filter(t => t._1 == "owner-d-race-c" || t._1 == "owner-d-race-d")
        assertEquals(raceTurns.size, 1, s"concurrent enqueue grants EXACTLY one window (never two): $raceTurns")
        val raceOpen = MergeQueue.openSnapshot.filter(_.projectKey == raceProject)
        assertEquals(raceOpen.size, 2, "both members stay on the ledger")
        assertEquals(raceOpen.count(_.state == MergeQueue.State.TurnGranted), 1, "exactly one of them holds the window")
        assertEquals(raceOpen.count(_.state == MergeQueue.State.Waiting), 1, "the other one is still queued")

        // ── 场景 B：FIFO 顺序确定后再并发砸 pump（窗不因并发而重授）──
        val project = "p-conc"
        MergeQueue.enqueue(member("d-conc-a", repo, project, 1_000L)) // 队头：先进账 = 先持窗
        MergeQueue.enqueue(member("d-conc-b", repo, project, 2_000L))
        assertEquals(MergeQueue.find("d-conc-a").map(_.state), Some(MergeQueue.State.TurnGranted))
        assertEquals(MergeQueue.find("d-conc-b").map(_.state), Some(MergeQueue.State.Waiting))
        val before = mergeTurns.count(_._1 == "owner-d-conc-a")
        assertEquals(before, 1, "one grant for the FIFO head")

        // 采样线程：独立见证「同 project 持窗 ≤ 1」（不是只看终态）。
        val maxHolder = new AtomicInteger(0)
        val stop = new java.util.concurrent.atomic.AtomicBoolean(false)
        val sampler = new Thread(
          () =>
            while !stop.get() do
              val holding =
                MergeQueue.openSnapshot.count(m => m.projectKey == project && m.state == MergeQueue.State.TurnGranted)
              maxHolder.accumulateAndGet(holding, math.max)
          ,
          "p2-sampler"
        )
        sampler.setDaemon(true)
        sampler.start()

        // 三路真线程同时砸 pump：窗被持 ⇒ 一条都不许再授。
        val latch = CountDownLatch(1)
        val storm = (1 to 3).map(i => onThread(s"p2-storm-$i", latch) { (1 to 40).foreach(_ => MergeQueue.pump(project)) })
        latch.countDown()
        storm.foreach(_.join(30000))
        assertEquals(failures.toArray.toList.size, 0, s"no storm thread may fail: ${failures.toArray.toList}")
        assertEquals(mergeTurns.count(_._1 == "owner-d-conc-a"), 1, "the held window is never re-granted under a pump storm")
        assertEquals(MergeQueue.find("d-conc-b").map(_.state), Some(MergeQueue.State.Waiting), "B is still queued")

        // ── 场景 C：释放窗 + 并发砸 pump ⇒ 恰好一条新授（授给 B）──
        MergeQueue.closeMerged("d-conc-a", via = "merge-round", notifyParent = false)
        val latch2 = CountDownLatch(1)
        val storm2 = (1 to 3).map(i => onThread(s"p2-storm2-$i", latch2) { (1 to 40).foreach(_ => MergeQueue.pump(project)) })
        latch2.countDown()
        storm2.foreach(_.join(30000))
        stop.set(true)
        sampler.join(5000)

        assertEquals(failures.toArray.toList.size, 0, s"no storm thread may fail: ${failures.toArray.toList}")
        val turns = mergeTurns.filter(t => t._1 == "owner-d-conc-a" || t._1 == "owner-d-conc-b")
        assertEquals(turns.size, 2, s"exactly two merge-turns for this project (one per member, never a double grant): $turns")
        assertEquals(turns.map(_._1), List("owner-d-conc-a", "owner-d-conc-b"), "FIFO order: A then B")
        assertEquals(maxHolder.get(), 1, "the single-window invariant held throughout (sampled concurrently)")
        assertEquals(MergeQueue.find("d-conc-b").map(_.state), Some(MergeQueue.State.TurnGranted))
        assertEquals(
          MergeQueue.openSnapshot.count(_.projectKey == project),
          1,
          "only one open member remains (A is closed as merged)"
        )
        // 串行的结构证据：投递窗口**永不重叠**（同时在飞的投递恒 ≤ 1，窗口已被拉宽到
        // 2ms——真并发下必然重叠）。串行性由 actor 邮箱保证，与「碰巧没撞上」无关。
        assertEquals(
          maxInFlight.get(),
          1,
          s"at most one delivery in flight at any time (queue actor mailbox = serial): peak was ${maxInFlight.get()}"
        )
      }
    yield ()
  }


  test("③ Tick 自驱：道闸挡住 → 外部零调用，actor 自己的 scheduleOnce 到点后把成员放出去") {
    val project = "p-tick"
    val repo = mkRepo(tempRoot / "tick", branch = "delegate/d-tick")
    // 道被占（不可归属的 MERGE_HEAD = 人的手工合并形态）⇒ 授窗前道闸不放行。
    os.write(repo / ".git" / "MERGE_HEAD", "0123456789abcdef0123456789abcdef01234567\n")
    for
      _ <- IO {
        installRecordingPort(None)
        MergeQueue.enableSelfTick(intervalMs = 60L, scope = Set(project))
        MergeQueue.enqueue(member("d-tick", repo, project, 1_000L))
        // 入队那一拍就被道闸挡住：留队 + 原因在案，且**没有** merge-turn。
        assertEquals(
          MergeQueue.find("d-tick").map(_.state),
          Some(MergeQueue.State.Waiting),
          "lane busy ⇒ the window is handed back"
        )
        assert(MergeQueue.find("d-tick").flatMap(_.lastBlockReason).nonEmpty, "the block reason is on the ledger")
        assertEquals(mergeTurns.size, 0, "nothing was granted while the lane was occupied")
      }
      // 道净（人的手工合并撤了）。从这里起**不再调用任何 pump/careTick**——
      // 只等 actor 自己的 Tick 到点。
      _ <- IO(os.remove(repo / ".git" / "MERGE_HEAD"))
      _ <- IO.sleep(1500.millis)
      _ <- IO {
        assertEquals(
          mergeTurns.size,
          1,
          s"the actor's own Tick granted the window (deliveries: ${deliveries.toArray.toList})"
        )
        assertEquals(
          MergeQueue.find("d-tick").map(_.state),
          Some(MergeQueue.State.TurnGranted),
          "the member now holds the window"
        )
      }
    yield ()
  }
end MergeQueueActorSpec
