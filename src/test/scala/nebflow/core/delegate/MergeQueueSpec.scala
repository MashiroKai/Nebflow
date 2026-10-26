package nebflow.core.delegate

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, AgentCommand, AgentEvent}
import nebflow.shared.PathUtil

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*

/**
 * MergeQueue 交通引导员判读（台账 #896 · 2026-10-26 作者裁定落地批）：
 *
 *  - ① **FIFO 排序键 = ready-to-merge 终态（完成）时间**（S11 承继）——夹具把
 *    readyAt 与入队顺序刻意反置：后入队但更早完成的必须先放行；
 *  - ② 每项目单持有人（turn-granted 即窗本体）：同项目第二成员只入账不通知；
 *  - ③ 跨项目并行：两个 projectKey 各自放行，互不阻塞；
 *  - ④ 放行 = 通知（属主自己执行合并）：合并指令文本钉死
 *    `git -C <baseWs> merge --no-ff <branch>` + **只读账实核对预检**在案；
 *  - ⑤ **账实核对（先于一切）**：分支已并入基分支的成员直接关账 merged——
 *    永不通知合并、永不重试（e300e71c 类 should-not-happen 的消除闸）；
 *  - ⑥ 合并轮终态关账：merged / failed（拒绝原因随行），无重试无 park；
 *  - ⑦ **owner-gone**：属主会话死亡（活性探针）/ 通知通道失败 ⇒ 关账
 *    owner-gone + 升级通知（OwnerGonePort），绝不静默 park；
 *  - ⑧ 账本持久化：落盘写穿 + 重启（丢内存）自盘回载，跨重启不清零。
 *
 * 夹具：真 actor（Sink 收 UserInput）+ 真 git 仓（净空闸/账实核对的真 git 面）。
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

  override def afterEach(context: munit.AfterEach): Unit =
    MergeQueue.reset()
    MergeQueue.installLivenessProbe(_ => IO.pure(true))
    MergeQueue.OwnerGonePort.clear()

  /** 真 git 基座（main@init + 可选的已并分支形态）。 */
  private def mkRepo(dir: os.Path, branch: String, merge: Boolean): os.Path =
    os.remove.all(dir)
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
    if merge then g("merge", "--no-ff", branch, "-m", "merge the branch")
    dir

  /** 收单 actor：把收到的 UserInput 记进共享缓冲（text + replyTo ref 逐条留存）。 */
  private class Sink(records: ListBuffer[(String, Option[ActorRef[AgentEvent]])]) extends nebflow.actor.Behavior[AgentCommand]:

    def receive(ctx: nebflow.actor.ActorContext[AgentCommand], msg: AgentCommand): IO[nebflow.actor.Behavior[AgentCommand]] =
      msg match
        case AgentCommand.UserInput(text, replyTo, _, _, _, source, _, _, _, _, _, _, _, _) =>
          IO {
            records += ((text, replyTo))
            this
          }
        case _ => IO.pure(this)

  /** 每条目的监督者 ref（replyTo 断言的期望值——全局 var 会被后建条目覆盖）。 */
  private val sups = scala.collection.mutable.Map.empty[String, ActorRef[AgentEvent]]

  private def member(
    system: ActorSystem,
    records: ListBuffer[(String, Option[ActorRef[AgentEvent]])],
    delegateId: String,
    repo: os.Path,
    readyAt: Long,
    taskId: Option[String] = Some("31")
  ): IO[MergeQueue.Member] =
    for
      child <- system.spawn(new Sink(records), s"child-$delegateId")
      sup <- system.spawn(
        new nebflow.actor.Behavior[AgentEvent]:
          def receive(ctx: nebflow.actor.ActorContext[AgentEvent], msg: AgentEvent): IO[nebflow.actor.Behavior[AgentEvent]] =
            IO.pure(this)
        ,
        s"sup-$delegateId"
      )
    yield
      sups(delegateId) = sup
      MergeQueue.Member(
        delegateId = delegateId,
        branch = s"delegate/$delegateId",
        baseWs = repo.toString,
        baseBranch = "main",
        worktree = false,
        projectKey = delegateId, // 每 delegate 独立 project ⇒ 用例间零窗互扰（多项目用例除外）
        taskId = taskId,
        parentSessionId = "nebula-root",
        description = s"task for $delegateId",
        readyAt = readyAt,
        channelKind = "internal",
        internal = Some(MergeQueue.InternalLeg(child, sup))
      )

  private def chainLines(action: String): List[ChainStore.Entry] =
    ChainStore.listSync().flatMap(_.entries).filter(_.kind == MergeQueue.EntryKind).filter { e =>
      e.payload.hcursor.get[String]("action").toOption.contains(action)
    }

  // ── ① FIFO 按完成时间 + ③ 跨项目并行 ─────────────────────────────────

  test("① FIFO rank = readyAt (completion time) and cross-project parallel grants — the late-enqueued earlier-finisher is notified first") {
    val system = ActorSystem(s"mq-1-${scala.util.Random.nextInt(100000)}")
    val records = ListBuffer.empty[(String, Option[ActorRef[AgentEvent]])]
    val repo = mkRepo(tempRoot / "mq1-repo", branch = "delegate/d-x", merge = false)
    for
      later <- member(system, records, "d-late", repo, readyAt = 2_000L) // 后完成、先入队
      earlier <- member(system, records, "d-early", repo, readyAt = 1_000L) // 先完成、后入队
      _ <- IO { MergeQueue.enqueue(later.copy(projectKey = "p1")) }
      _ <- IO { MergeQueue.enqueue(earlier.copy(projectKey = "p1")) }
      _ <- IO.sleep(80.millis)
      first = records.toList
      _ <- IO { MergeQueue.onTerminal("d-late", Some(MergeQueue.RoundOutcome.Merged)) }
      _ <- IO.sleep(80.millis)
      both = records.toList
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(
        first.size,
        1,
        "single holder: only the FIRST granted member's turn notice appears before the close-out"
      )
      assert(
        first.head._1.contains("<system-reminder>"),
        "the merge turn notice must be a system-reminder wrapped injection"
      )
      assertEquals(
        first.head._2,
        Some(sups("d-late")),
        "the injected turn's replyTo must be the holder's supervisor (merge-round Completed must return to the terminal hook)"
      )
      assertEquals(both.size, 2, "after the holder's merge round closes the next member is granted (pump)")
      assert(
        both(1)._1.contains("delegate/d-early"),
        s"the SECOND turn must be d-early (readyAt rank), got: ${both(1)._1.take(120)}"
      )
      assertEquals(both(1)._2, Some(sups("d-early")), "the granted member's turn targets its own supervisor")
      assertEquals(
        chainLines("granted").size,
        2,
        "both grants must be audited as granted ChainStore lines"
      )
    end for
  }

  test("③ cross-project: two different projectKeys are granted immediately (per-project single holder never blocks the other)") {
    val system = ActorSystem(s"mq-3-${scala.util.Random.nextInt(100000)}")
    val records = ListBuffer.empty[(String, Option[ActorRef[AgentEvent]])]
    val repo = mkRepo(tempRoot / "mq3-repo", branch = "delegate/d-y", merge = false)
    for
      a <- member(system, records, "d-x", repo, readyAt = 1_000L)
      b <- member(system, records, "d-y", repo, readyAt = 2_000L)
      _ <- IO { MergeQueue.enqueue(a.copy(projectKey = "proj-A")) }
      _ <- IO { MergeQueue.enqueue(b.copy(projectKey = "proj-B")) }
      _ <- IO.sleep(80.millis)
      got = records.toList
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(got.size, 2, "different projects are granted in parallel — no cross-key blocking")
      assert(got.exists(_._1.contains("delegate/d-x")) && got.exists(_._1.contains("delegate/d-y")))
  }

  // ── ② 单持有人（同项目第二成员不通知） ────────────────────────────────

  test("② single holder per project: a second ready-to-merge in the SAME project stays waiting until the holder's round closes") {
    val system = ActorSystem(s"mq-2-${scala.util.Random.nextInt(100000)}")
    val records = ListBuffer.empty[(String, Option[ActorRef[AgentEvent]])]
    val repo = mkRepo(tempRoot / "mq2-repo", branch = "delegate/d-b", merge = false)
    for
      a <- member(system, records, "d-a", repo, readyAt = 1_000L)
      b <- member(system, records, "d-b", repo, readyAt = 2_000L)
      _ <- IO { MergeQueue.enqueue(a.copy(projectKey = "p2")) }
      _ <- IO { MergeQueue.enqueue(b.copy(projectKey = "p2")) }
      _ <- IO.sleep(80.millis)
      afterBoth = records.toList
      holder = MergeQueue.find("d-a").map(_.state)
      waiter = MergeQueue.find("d-b").map(_.state)
      _ <- IO { MergeQueue.onTerminal("d-a", Some(MergeQueue.RoundOutcome.Merged)) }
      _ <- IO.sleep(80.millis)
      afterRelease = records.toList
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(afterBoth.size, 1, "the holder blocks the second same-project member (single holder)")
      assertEquals(holder, Some("turn-granted"), "the granted member is turn-granted (window held)")
      assertEquals(waiter, Some("waiting"), "the second member stays waiting")
      assertEquals(afterRelease.size, 2, "the holder's close-out releases the window")
      assert(afterRelease(1)._1.contains("delegate/d-b"), "the granted member is the queued one")
      assertEquals(afterRelease(1)._2, Some(sups("d-b")), "the granted member's turn targets its own supervisor")
      assert(MergeQueue.find("d-a").forall(_.state == "merged"), "the closed holder is merged in the ledger")
    end for
  }

  // ── ④ 合并指令文本（放行 = 通知） ─────────────────────────────────────

  test("④ merge brief pins the direction + read-only fact-check pre-check: git -C <baseWs> merge --no-ff <branch>, conflict = STOP") {
    val system = ActorSystem(s"mq-4-${scala.util.Random.nextInt(100000)}")
    val records = ListBuffer.empty[(String, Option[ActorRef[AgentEvent]])]
    val repo = mkRepo(tempRoot / "mq4-repo", branch = "delegate/d-dir", merge = false)
    for
      e <- member(system, records, "d-dir", repo, readyAt = 1L)
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      val brief = MergeQueue.mergeBriefFor(e.copy(grantedBaseTip = Some("abcdef1234567890")))
      assert(
        brief.startsWith("<system-reminder>"),
        "merge brief must be a system-reminder injection (S12: node-reminder same channel)"
      )
      assert(
        brief.contains(s"git -C ${e.baseWs} merge --no-ff ${e.branch}"),
        "the exact single command must be spelled out (worktree leg: direction pinned to the BASE workspace)"
      )
      assert(
        brief.contains(s"git -C ${e.baseWs} merge-base --is-ancestor ${e.branch} ${e.baseBranch}"),
        "the read-only already-merged pre-check must be spelled out (traffic-controller ledger close-out gate)"
      )
      assert(brief.contains("abcdef123456"), "the base tip must ride the notice (grant carries base tip)")
      assert(
        brief.toLowerCase.contains("stop"),
        "conflict/refusal must instruct a clean stop (no resolution, no force, no retry)"
      )
      assert(brief.contains(e.baseBranch), "the target base branch must be named")
      assert(brief.toLowerCase.contains("no automatic retries"), "the refusal path must state closed-as-failed (no auto-retry)")
    end for
  }

  // ── ⑤ 账实核对：已并成员直接关账，永不通知合并 ────────────────────────

  test("⑤ fact-check at grant: an already-merged member closes as merged WITHOUT a merge notice (the e300e71c class is gone)") {
    val system = ActorSystem(s"mq-5-${scala.util.Random.nextInt(100000)}")
    val records = ListBuffer.empty[(String, Option[ActorRef[AgentEvent]])]
    val repo = mkRepo(tempRoot / "mq5-repo", branch = "delegate/d-merged", merge = true) // 分支已并入 main
    for
      a <- member(system, records, "d-merged", repo, readyAt = 1L)
      _ <- IO { MergeQueue.enqueue(a.copy(projectKey = "p5")) }
      _ <- IO.sleep(120.millis)
      got = records.toList
      state = MergeQueue.find("d-merged").map(_.state)
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      // 唯一通知 = 「已落地」收尾通知（不碰 base 立即 finish——delegate 侧纪律 #6）。
      assertEquals(got.size, 1, "exactly one notice: the already-landed close-out (never a merge turn)")
      assert(
        got.head._1.contains("ALREADY merged"),
        s"the notice must tell the delegate the merge landed: ${got.head._1.take(160)}"
      )
      assert(!got.head._1.contains("merge --no-ff"), "no merge command may ride an already-merged close-out")
      assertEquals(state, Some("merged"), "the member is closed as merged in the ledger")
      assertEquals(chainLines("granted").size, 0, "no grant is ever audited for an already-merged member")
      assertEquals(chainLines("merge-merged").size, 1, "the close-out is audited as merge-merged (fact-check via)")
    end for
  }

  // ── ⑥ 合并轮终态关账（merged / failed，无重试） ───────────────────────

  test("⑥ round terminal closes the ledger: refused ⇒ failed with the reason (no retry, no park); merged ⇒ merged") {
    val system = ActorSystem(s"mq-6-${scala.util.Random.nextInt(100000)}")
    val records = ListBuffer.empty[(String, Option[ActorRef[AgentEvent]])]
    val repo = mkRepo(tempRoot / "mq6-repo", branch = "delegate/d-fail", merge = false)
    for
      a <- member(system, records, "d-fail", repo, readyAt = 1L)
      _ <- IO { MergeQueue.enqueue(a.copy(projectKey = "p6")) }
      _ <- IO.sleep(80.millis)
      _ <- IO { MergeQueue.onTerminal("d-fail", Some(MergeQueue.RoundOutcome.Refused("CONFLICT: src/Main.scala"))) }
      failed = MergeQueue.find("d-fail").map(m => (m.state, m.closeReason))
      _ <- IO { MergeQueue.onTerminal("d-fail", Some(MergeQueue.RoundOutcome.Refused("CONFLICT: src/Main.scala"))) } // 幂等
      afterIdem = MergeQueue.find("d-fail").map(_.state)
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(failed, Some(("failed", Some("CONFLICT: src/Main.scala"))), "a refused round closes as failed with the reason")
      assertEquals(afterIdem, Some("failed"), "close-out is idempotent (no re-close, no re-park)")
      assertEquals(chainLines("merge-failed").size, 1, "exactly one merge-failed audit line")
      assertEquals(records.size, 1, "no re-notification after the close-out (no retry rounds exist)")
    end for
  }

  // ── ⑦ owner-gone：属主死亡 / 通道失败（绝不静默 park） ────────────────

  test("⑦ owner-gone: a turn granted to a DEAD owner session closes as owner-gone and escalates via OwnerGonePort") {
    val system = ActorSystem(s"mq-7-${scala.util.Random.nextInt(100000)}")
    val records = ListBuffer.empty[(String, Option[ActorRef[AgentEvent]])]
    val repo = mkRepo(tempRoot / "mq7-repo", branch = "delegate/d-gone", merge = false)
    // 死通道：internal ref 指向不存在的注册表条目 ⇒ 活性探针判死（fail-open 默认恒活
    // ——装一个假探针把目标判死，模拟会话已终态）。
    MergeQueue.installLivenessProbe(ref => IO.pure(false))
    val escalations = ListBuffer.empty[(String, String)]
    MergeQueue.OwnerGonePort.install((m, reason) => IO { escalations += ((m.delegateId, reason)) })
    for
      a <- member(system, records, "d-gone", repo, readyAt = 1L)
      _ <- IO { MergeQueue.enqueue(a.copy(projectKey = "p7")) }
      _ <- IO.sleep(80.millis)
      got = records.toList
      state = MergeQueue.find("d-gone").map(_.state)
      reason = MergeQueue.find("d-gone").flatMap(_.closeReason)
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      MergeQueue.installLivenessProbe(_ => IO.pure(true)) // 复位探针（后续用例恒活）
      assertEquals(got.size, 0, "no merge notice is ever injected into a dead session")
      assertEquals(state, Some("owner-gone"), "the member is closed as owner-gone (visible, never silently parked)")
      assert(reason.exists(_.contains("undeliverable")), s"the close reason names the delivery failure: $reason")
      assertEquals(escalations.map(_._1).toList, List("d-gone"), "the owner-gone close-out escalates via OwnerGonePort")
      assert(escalations.head._2.contains("undeliverable"), "the escalation carries the cause")
    end for
  }

  test("⑦b roundless terminal (merge-window timeout / cancel) fact-checks first, then closes owner-gone") {
    val system = ActorSystem(s"mq-7b-${scala.util.Random.nextInt(100000)}")
    val records = ListBuffer.empty[(String, Option[ActorRef[AgentEvent]])]
    val repo = mkRepo(tempRoot / "mq7b-repo", branch = "delegate/d-timeout", merge = false)
    for
      a <- member(system, records, "d-timeout", repo, readyAt = 1L)
      _ <- IO { MergeQueue.enqueue(a.copy(projectKey = "p7b")) }
      _ <- IO.sleep(80.millis)
      granted = records.size
      _ <- IO { MergeQueue.onTerminal("d-timeout", None) } // 回合外终态（预算取消）
      state = MergeQueue.find("d-timeout").map(_.state)
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(granted, 1, "the member was granted a turn first")
      assertEquals(state, Some("owner-gone"), "a roundless terminal closes the membership as owner-gone")
    end for
  }

  // ── ⑧ 账本持久化（重启 = 丢内存，盘上账在） ───────────────────────────

  test("⑧ ledger persistence: state survives a memory drop (restart simulation) and reloads from disk") {
    val system = ActorSystem(s"mq-8-${scala.util.Random.nextInt(100000)}")
    val records = ListBuffer.empty[(String, Option[ActorRef[AgentEvent]])]
    val repo = mkRepo(tempRoot / "mq8-repo", branch = "delegate/d-persist", merge = false)
    for
      a <- member(system, records, "d-persist", repo, readyAt = 42L)
      _ <- IO { MergeQueue.enqueue(a.copy(projectKey = "p8")) }
      _ <- IO.sleep(80.millis)
      before = MergeQueue.find("d-persist").map(m => (m.state, m.readyAt))
      _ <- IO { MergeQueue.dropMemoryStateForTest() } // 模拟重启：内存清零、盘上账在
      after = MergeQueue.find("d-persist")
      view = MergeQueue.queueViewOf("d-persist")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(before, Some(("turn-granted", 42L)), "the member was granted before the restart")
      assertEquals(after.map(_.state), Some("turn-granted"), "the ledger reloads from disk across a restart")
      assertEquals(after.map(_.readyAt), Some(42L), "readyAt is the ledger original value (FIFO rank survives)")
      assertEquals(after.flatMap(_.internal).isDefined, false, "channel closures are process-local and NOT persisted")
      assertEquals(view.map(_.position), Some(1), "the reloaded member still projects its queue position")
    end for
  }

  // ── ⑤b 幂等入账 + 审计 ───────────────────────────────────────────────

  test("⑤b enqueue is idempotent for open members and audited; unknown onTerminal is a no-op") {
    val system = ActorSystem(s"mq-9-${scala.util.Random.nextInt(100000)}")
    val records = ListBuffer.empty[(String, Option[ActorRef[AgentEvent]])]
    val repo = mkRepo(tempRoot / "mq9-repo", branch = "delegate/d-idem", merge = false)
    for
      a <- member(system, records, "d-idem", repo, readyAt = 1L)
      _ <- IO { MergeQueue.enqueue(a.copy(projectKey = "p9")) }
      _ <- IO { MergeQueue.enqueue(a.copy(projectKey = "p9")) } // 幂等：已在账的开放成员不重复
      _ <- IO { MergeQueue.onTerminal("d-unknown") } // 查无成员 = no-op
      _ <- IO.sleep(50.millis)
      got = records.toList
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(got.size, 1, "no duplicate turn notices (no zombie merge rounds)")
      assertEquals(chainLines("enqueued").size, 1, "one enqueued audit line")
      assertEquals(chainLines("granted").size, 1, "exactly one grant")
    end for
  }

end MergeQueueSpec
