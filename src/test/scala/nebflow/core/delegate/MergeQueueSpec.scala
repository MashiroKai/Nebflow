package nebflow.core.delegate

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, AgentCommand, AgentEvent, AgentRecord, AgentKind, Behavior, Behaviors}
import nebflow.shared.PathUtil

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*

/**
 * MergeQueue 判读（worktree-judge-fifo 批 #1224 增补 §3.2/§3.3）：
 *
 *  - ① **FIFO 排序键 = ready-to-merge 终态（完成）时间**（S11）——夹具把 readyAt
 *    与入队顺序刻意反置：后入队但更早完成的必须先合并；若实现按入队序/创建序排，
 *    本用例必红；
 *  - ② 每项目单飞行：一个项目的合并轮在飞时，后续 ready-to-merge 只入队不注入；
 *  - ③ 出队轮转：在飞持有者终态（onTerminal）⇒ 队列下一条获得注入；
 *  - ④ 跨项目并行：两个 projectKey 各自单飞行，互不阻塞；
 *  - ⑤ 合并指令文本：`<system-reminder>` 包裹、`git -C <baseWs> merge --no-ff
 *    <branch>` 字面在场（方向钉死基工作区——worktree 里 merge 会并进自己分支）；
 *  - ⑥ 审计行：enqueue / reminder-sent / dequeued 三态迁移落 ChainStore
 *    `merge-queue` 行（fail-soft 面不在此测——账本失败不阻断队列）。
 *
 * 注入面 = 真 actor（TestSink actor 收 AgentCommand.UserInput，replyTo 断言 =
 * 监督者 ref——合并轮 Completed 必须回终态钩子）。
 */
class MergeQueueSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-merge-queue"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    MergeQueue.reset()
    os.remove.all(tempRoot / "chains")

  override def afterEach(context: munit.AfterEach): Unit = MergeQueue.reset()

  /** 收单 actor：把收到的 UserInput 记进共享缓冲（text + replyTo ref 逐条留存）。 */
  private class Sink(records: ListBuffer[(String, Option[ActorRef[AgentEvent]])]) extends Behavior[AgentCommand]:
    def receive(ctx: nebflow.actor.ActorContext[AgentCommand], msg: AgentCommand): IO[Behavior[AgentCommand]] =
      msg match
        case AgentCommand.UserInput(text, replyTo, _, _, _, source, _, _, _, _, _, _, _) =>
          IO {
            records += ((text, replyTo))
            this
          }
        case _ => IO.pure(this)

  /** 每条目的监督者 ref（replyTo 断言的期望值——全局 var 会被后建条目覆盖）。 */
  private val sups = scala.collection.mutable.Map.empty[String, ActorRef[AgentEvent]]

  private def entry(
      system: ActorSystem,
      records: ListBuffer[(String, Option[ActorRef[AgentEvent]])],
      delegateId: String,
      projectKey: String,
      readyAt: Long,
      taskId: Option[String] = Some("31")
  ): IO[MergeQueue.Entry] =
    for
      child <- system.spawn(new Sink(records), s"child-$delegateId")
      sup <- system.spawn(new Behavior[AgentEvent]:
        def receive(ctx: nebflow.actor.ActorContext[AgentEvent], msg: AgentEvent): IO[Behavior[AgentEvent]] = IO.pure(this)
      , s"sup-$delegateId")
    yield
      sups(delegateId) = sup
      MergeQueue.Entry(
        delegateId = delegateId,
        branch = s"delegate/$delegateId",
        baseWs = tempRoot.toString,
        baseBranch = "main",
        worktree = false,
        projectKey = projectKey,
        taskId = taskId,
        parentSessionId = "nebula-root",
        description = s"task for $delegateId",
        readyAt = readyAt,
        childRef = child,
        supervisorRef = sup
      )

  private def chainLines(action: String): List[ChainStore.Entry] =
    ChainStore.listSync().flatMap(_.entries).filter(_.kind == MergeQueue.EntryKind).filter { e =>
      e.payload.hcursor.get[String]("action").toOption.contains(action)
    }

  // ── ① FIFO 按完成时间 ────────────────────────────────────────────────

  test("① FIFO rank = readyAt (completion time), NOT enqueue order — the late-enqueued earlier-finisher merges first") {
    val system = ActorSystem(s"mq-1-${scala.util.Random.nextInt(100000)}")
    val records = ListBuffer.empty[(String, Option[ActorRef[AgentEvent]])]
    for
      later <- entry(system, records, "d-late", "p1", readyAt = 2_000L) // 后完成、先入队
      earlier <- entry(system, records, "d-early", "p1", readyAt = 1_000L) // 先完成、后入队
      _ <- IO { MergeQueue.enqueue(later) } // 先入队者 readyAt 更晚
      _ <- IO.sleep(50.millis)
      // 单飞行：后入队者此刻不该注入（持有者 = d-late 的注入已在飞）
      _ <- IO { MergeQueue.enqueue(earlier) } // 后入队但 readyAt 更早
      _ <- IO.sleep(50.millis)
      first = records.toList
      // 出队：持有者终态 ⇒ 下一条 = d-early（readyAt 序，非入队序）
      _ <- IO { MergeQueue.onTerminal("d-late") }
      _ <- IO.sleep(50.millis)
      both = records.toList
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(first.size, 1, "single-flight: only the FIRST injected entry's reminder may appear before the dequeue")
      assert(first.head._1.contains("<system-reminder>"), "the merge reminder must be a system-reminder wrapped injection")
      assertEquals(
        first.head._2,
        Some(sups("d-late")),
        "the injected turn's replyTo must be the INJECTED entry's supervisor (merge-round Completed must return to the terminal hook)"
      )
      // 第二次注入 = readyAt 更早的 d-early（若实现按入队序，这里会拿到 d-early 之外的东西或保持等待）
      assertEquals(both.size, 2, "after the holder's terminal the queued entry must be injected (pump)")
      assert(both(1)._1.contains("delegate/d-early"), s"the SECOND merge turn must be d-early (readyAt rank), got: ${both(1)._1.take(120)}")
      assertEquals(both(1)._2, Some(sups("d-early")), "the released entry's turn targets its own supervisor")
      assertEquals(chainLines("reminder-sent").size, 2, "both injections must be audited as reminder-sent ChainStore lines")
  }

  // ── ② 单飞行（同项目第二并入队不注入） ──────────────────────────────

  test("② single-flight per project: a second ready-to-merge in the SAME project queues without injection until the in-flight merge terminates") {
    val system = ActorSystem(s"mq-2-${scala.util.Random.nextInt(100000)}")
    val records = ListBuffer.empty[(String, Option[ActorRef[AgentEvent]])]
    for
      a <- entry(system, records, "d-a", "p1", readyAt = 1_000L)
      b <- entry(system, records, "d-b", "p1", readyAt = 2_000L)
      _ <- IO { MergeQueue.enqueue(a) }
      _ <- IO { MergeQueue.enqueue(b) }
      _ <- IO.sleep(80.millis)
      afterBoth = records.toList
      _ <- IO { MergeQueue.onTerminal("d-a") }
      _ <- IO.sleep(80.millis)
      afterRelease = records.toList
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(afterBoth.size, 1, "the in-flight merge blocks the second same-project entry (single-flight)")
      assertEquals(afterRelease.size, 2, "the holder's terminal releases the queue")
      assert(afterRelease(1)._1.contains("delegate/d-b"), "the released entry is the queued one")
      assertEquals(afterRelease(1)._2, Some(sups("d-b")), "the released entry's turn targets its own supervisor")
      assert(MergeQueue.find("d-a").isEmpty, "the terminal entry is dequeued")
  }

  // ── ③ 跨项目并行 ─────────────────────────────────────────────────────

  test("③ cross-project: two different projectKeys each inject immediately (per-project single-flight never blocks the other)") {
    val system = ActorSystem(s"mq-3-${scala.util.Random.nextInt(100000)}")
    val records = ListBuffer.empty[(String, Option[ActorRef[AgentEvent]])]
    for
      a <- entry(system, records, "d-x", "proj-A", readyAt = 1_000L)
      b <- entry(system, records, "d-y", "proj-B", readyAt = 2_000L)
      _ <- IO { MergeQueue.enqueue(a) }
      _ <- IO { MergeQueue.enqueue(b) }
      _ <- IO.sleep(80.millis)
      got = records.toList
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(got.size, 2, "different projects merge in parallel — no cross-key blocking")
      assert(got.exists(_._1.contains("delegate/d-x")) && got.exists(_._1.contains("delegate/d-y")))
  }

  // ── ④ 合并指令文本 ───────────────────────────────────────────────────

  test("④ merge brief pins the direction: git -C <baseWs> merge --no-ff <branch>, system-reminder wrapped, conflict = STOP") {
    val system = ActorSystem(s"mq-4-${scala.util.Random.nextInt(100000)}")
    val records = ListBuffer.empty[(String, Option[ActorRef[AgentEvent]])]
    for
      e <- entry(system, records, "d-dir", "p-dir", readyAt = 1L)
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      val brief = MergeQueue.mergeBriefFor(e)
      assert(brief.startsWith("<system-reminder>"), "merge brief must be a system-reminder injection (S12: node-reminder same channel)")
      assert(brief.contains(s"git -C ${e.baseWs} merge --no-ff ${e.branch}"), "the exact single command must be spelled out (worktree leg: direction pinned to the BASE workspace)")
      assert(brief.toLowerCase.contains("stop"), "conflict/refusal must instruct a clean stop (no resolution, no force)")
      assert(brief.contains(e.baseBranch), "the target base branch must be named")
  }

  // ── ⑤ 幂等与审计 ─────────────────────────────────────────────────────

  test("⑤ onTerminal is idempotent and audited: dequeued lines land in ChainStore; unknown delegate is a no-op") {
    val system = ActorSystem(s"mq-5-${scala.util.Random.nextInt(100000)}")
    val records = ListBuffer.empty[(String, Option[ActorRef[AgentEvent]])]
    for
      a <- entry(system, records, "d-idem", "p1", readyAt = 1L)
      _ <- IO { MergeQueue.enqueue(a) }
      _ <- IO.sleep(50.millis)
      _ <- IO { MergeQueue.onTerminal("d-idem") }
      _ <- IO { MergeQueue.onTerminal("d-idem") } // 幂等：第二次 no-op
      _ <- IO { MergeQueue.onTerminal("d-unknown") } // 查无条目 = no-op
      _ <- IO.sleep(50.millis)
      got = records.toList
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(got.size, 1, "no re-injection after dequeue (no zombie merge rounds)")
      assertEquals(chainLines("enqueued").size, 1, "one enqueued audit line")
      assertEquals(chainLines("dequeued").size, 1, "exactly one dequeued audit line (idempotent)")
  }

end MergeQueueSpec
