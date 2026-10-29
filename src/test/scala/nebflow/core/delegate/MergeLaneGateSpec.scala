package nebflow.core.delegate

import cats.effect.IO
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, AgentCommand, AgentEvent}
import nebflow.shared.PathUtil

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*

/**
 * 合并道闸（投递可靠性批 2026-10-29 · 病灶三型收口，作者令 23:44/23:55/23:57/00:13）：
 *
 *  - ① **classifyLane 纯判读五态**（spec 判红锚）：Clear / OwnLeftover /
 *    BusyLive / BusyDeadHolder / Foreign——单一判据 = MERGE_HEAD 读数 × 属主活性；
 *  - ② **授窗前道闸（病灶②预防位）**：MERGE_HEAD 在场（不可归属 = 人的手工合并
 *    形态）⇒ 不授窗——成员留队 + #943 必通知，绝不把合并轮放进被占的道
 *    （今晚 a464fdc0/b3020083 撞 exit 128 被误关账的预防）；道净后自动重排；
 *  - ③ **道忙重排（病灶②结算面）**：requeueForLane 保留成员资格退回 waiting
 *    （grantedAt 清空、阻塞原因入账、lane-requeued 审计行）——会话停靠保活由
 *    BackoffSupervisor 腿执行（本 spec 钉账本面）；
 *  - ④ **孤儿合并收场（病灶①）**：属主死灭 + MERGE_HEAD == 本成员分支 tip ⇒
 *    owner-gone 关账时 `git merge --abort` 回滚 + orphan-abort 审计行——恢复
 *    语义非代并（作者裁定 23:55「倾向=abort 回滚」）；
 *  - ⑤ **零触碰线**：残留 MERGE_HEAD 指向他人分支 ⇒ 绝不 abort（人的手工合并
 *    形态）——只留 orphan-abort-skipped 审计行；
 *  - ⑥ **窗口销账（病灶③）**：持窗成员的分支已并入基分支 ⇒ careTick 账实核对
 *    立即关账 merged + 释放窗——不依赖属主下一拍主动还窗（R1 批 109d8ab6 形态）；
 *  - ⑦ **合并 brief 自愈教学**：自家残留（MERGE_HEAD = 己分支 tip）的 abort+重试
 *    子句随行（属内自愈，不上抛）。
 *
 * 夹具：真 git 仓（MERGE_HEAD 用真冲突合并造）+ 真 actor（Sink 收通知）。
 */
class MergeLaneGateSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-merge-lane-gate"
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
    MergeQueue.BlockNotifyPort.clear()

  /** 真 git 基座：main@init + deliverable 分支（未并；mergeIntoMain=true 时已并）。 */
  private def mkRepo(dir: os.Path, branch: String, mergeIntoMain: Boolean = false): os.Path =
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
    os.write(dir / "main.txt", "main-side")
    g("add", ".")
    g("commit", "-m", "main advance")
    if mergeIntoMain then
      g("merge", "--no-ff", branch, "-m", "pre-landed merge (fixture: the commit already landed)")
    dir

  /**
   * 在 main 上对 `branch` 发起一次真冲突合并并停在冲突态（MERGE_HEAD = branch
   * tip）：分支侧与 main 侧对同一文件各改一笔 → merge 撞冲突停场。`branch` 可以
   * 是成员自己的分支（OwnLeftover 形态）也可以是旁支（Foreign/他人形态）。
   */
  private def startConflictedMerge(repo: os.Path, branch: String): String =
    def g(args: String*): os.CommandResult =
      os.proc(Seq("git", "-C", repo.toString) ++ args).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    // -B = 存在则重置到当前 HEAD、不存在则建——成员分支（mkRepo 已建）与旁支皆可
    g("checkout", "-B", branch)
    os.write.over(repo / "deliverable.txt", s"branch-side edit ($branch)")
    g("add", ".")
    g("commit", "-m", s"branch-side edit ($branch)")
    g("checkout", "main")
    os.write.over(repo / "deliverable.txt", "main-side edit")
    g("add", ".")
    g("commit", "-m", "main-side edit")
    val res = os.proc("git", "-C", repo.toString, "merge", "--no-ff", branch)
      .call(check = false, stdout = os.Pipe, stderr = os.Pipe)
    assertEquals(res.exitCode == 0, false, "fixture merge must conflict")
    val mh = os.read(repo / ".git" / "MERGE_HEAD").trim
    assert(mh.nonEmpty, "fixture must leave MERGE_HEAD behind")
    mh

  /** 收单 actor：把收到的 UserInput 记进共享缓冲。 */
  private class Sink(records: ListBuffer[String]) extends nebflow.actor.Behavior[AgentCommand]:
    def receive(ctx: nebflow.actor.ActorContext[AgentCommand], msg: AgentCommand): IO[nebflow.actor.Behavior[AgentCommand]] =
      msg match
        case AgentCommand.UserInput(text, _, _, _, _, _, _, _, _, _, _, _, _, _) => IO { records += text; this }
        case _                                                                   => IO.pure(this)

  private def member(
    system: ActorSystem,
    records: ListBuffer[String],
    delegateId: String,
    repo: os.Path,
    readyAt: Long,
    alive: Boolean = true
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
      if !alive then MergeQueue.installLivenessProbe(ref => IO.pure(false))
      MergeQueue.Member(
        delegateId = delegateId,
        branch = s"delegate/$delegateId",
        baseWs = repo.toString,
        baseBranch = "main",
        worktree = false,
        projectKey = delegateId,
        taskId = Some("77"),
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

  // ── ① classifyLane 纯判读（spec 判红锚） ─────────────────────────────

  test("① classifyLane: the five verdicts by MERGE_HEAD presence × owner liveness (pure decision face)") {
    // 道净
    assertEquals(MergeQueue.classifyLane(None, Some("tip-a"), None), MergeQueue.LaneVerdict.Clear)
    // 己方残留（MERGE_HEAD == 本成员分支 tip）
    assertEquals(
      MergeQueue.classifyLane(Some("tip-me"), Some("tip-me"), None),
      MergeQueue.LaneVerdict.OwnLeftover,
      "MERGE_HEAD equal to my own branch tip = my own conflicted merge leftover"
    )
    // 道忙：占道者会话存活
    assertEquals(
      MergeQueue.classifyLane(Some("tip-other"), Some("tip-me"), Some(("owner-1", true))),
      MergeQueue.LaneVerdict.BusyLive("owner-1"),
      "an alive occupier = lane busy, wait (failure mode ②'s grant-side prevention)"
    )
    // 占道者已死未收殓
    assertEquals(
      MergeQueue.classifyLane(Some("tip-other"), Some("tip-me"), Some(("owner-2", false))),
      MergeQueue.LaneVerdict.BusyDeadHolder("owner-2"),
      "a dead occupier waits one tick for the care-tick reap+abort — the gate itself never aborts"
    )
    // 不可归属（人的手工合并/未知分支）
    assertEquals(
      MergeQueue.classifyLane(Some("tip-human"), Some("tip-me"), None),
      MergeQueue.LaneVerdict.Foreign,
      "unattributable MERGE_HEAD = foreign — wait and notify, NEVER abort (a human may be resolving)"
    )
    // 我分支 tip 不可读 = 不误判己方残留（fail-open 落 Foreign）
    assertEquals(
      MergeQueue.classifyLane(Some("tip-x"), None, None),
      MergeQueue.LaneVerdict.Foreign,
      "unreadable own tip never misclassifies as OwnLeftover (fail-open)"
    )
  }

  // ── ② 授窗前道闸：MERGE_HEAD 在场不授窗 + 必通知；道净自动重排 ────────

  test("② grant-side lane gate: a foreign in-progress merge blocks the grant with a notice; clearing the lane re-grants") {
    val system = ActorSystem(s"mlg-2-${scala.util.Random.nextInt(100000)}")
    val records = ListBuffer.empty[String]
    val repo = mkRepo(tempRoot / "mlg2-repo", branch = "delegate/d-lane")
    // Foreign 形态：MERGE_HEAD 属于旁支（无任何队列成员对应该 tip = 不可归属，
    // 现实对应：人的手工合并 / 已关账成员的分支被手工续并）。
    val mh = startConflictedMerge(repo, "delegate/foreign-x")
    val notices = ListBuffer.empty[(String, String)]
    MergeQueue.BlockNotifyPort.install((m, kind, reason) => IO { notices += ((kind, reason)) })
    for
      a <- member(system, records, "d-lane", repo, readyAt = 1L)
      _ <- IO { MergeQueue.enqueue(a.copy(projectKey = "lane-2")) }
      _ <- IO.sleep(120.millis)
      blocked = records.toList
      state1 = MergeQueue.find("d-lane").map(_.state)
      _ <- IO { os.proc("git", "-C", repo.toString, "merge", "--abort").call(check = true, stdout = os.Pipe, stderr = os.Pipe) }
      _ <- IO.blocking(MergeQueue.pump("lane-2")) // 道净后下一拍
      _ <- IO.sleep(80.millis)
      regranted = records.toList
      state2 = MergeQueue.find("d-lane").map(_.state)
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(blocked.size, 0, "NO merge turn is ever injected into an occupied lane (a464fdc0/b3020083 prevention)")
      assertEquals(state1, Some("waiting"), "the member stays waiting while the lane is busy")
      assert(notices.exists(_._1 == "blocked"), "the block MUST notify (#943 — never silent)")
      assert(
        notices.exists((_, r) => r.contains("merge lane busy") && r.contains("manual/human merge")),
        s"the notice names the lane occupation and the never-abort policy: ${notices.map(_._2).mkString(" | ").take(200)}"
      )
      assertEquals(state2, Some("turn-granted"), "the lane cleared → the member is granted on the next pump")
      assertEquals(regranted.size, 1, "exactly one merge turn after the lane clears")
      assertEquals(chainLines("grant-blocked").size, 1, "the lane block is audited once")
    end for
  }

  // ── ③ 道忙重排（结算面账本迁移） ─────────────────────────────────────

  test("③ requeueForLane keeps the membership: turn-granted → waiting, grantedAt cleared, reason recorded, audit line rides") {
    val system = ActorSystem(s"mlg-3-${scala.util.Random.nextInt(100000)}")
    val records = ListBuffer.empty[String]
    val repo = mkRepo(tempRoot / "mlg3-repo", branch = "delegate/d-requeue")
    for
      a <- member(system, records, "d-requeue", repo, readyAt = 1L)
      _ <- IO { MergeQueue.seedMemberForTest(a.copy(projectKey = "lane-3", state = MergeQueue.State.TurnGranted, grantedAt = Some(123L))) }
      before = MergeQueue.find("d-requeue")
      _ <- IO.blocking(MergeQueue.requeueForLane("d-requeue", "merge lane busy: another queue member's merge round is in progress"))
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      afterFirst = MergeQueue.find("d-requeue")
      _ <- IO.blocking(MergeQueue.requeueForLane("d-requeue", "merge lane busy: again"))
      afterSecond = MergeQueue.find("d-requeue")
    yield
      assertEquals(before.map(_.state), Some("turn-granted"), "fixture: the member held the window")
      assertEquals(afterFirst.map(_.state), Some("waiting"), "the membership is KEPT (requeued, not closed as failed)")
      assertEquals(afterFirst.flatMap(_.grantedAt), None, "the window grant is released (grantedAt cleared)")
      assert(
        afterFirst.flatMap(_.lastBlockReason).exists(_.contains("merge lane busy")),
        "the panel-visible lastBlockReason records the lane cause"
      )
      assertEquals(chainLines("lane-requeued").size, 1, "exactly one lane-requeued audit line")
      assertEquals(afterSecond.flatMap(_.grantedAt), None, "idempotent: the second requeue on a waiting member is a no-op")
      assertEquals(chainLines("lane-requeued").size, 1, "idempotent: no second audit line")
    end for
  }

  // ── ④ 孤儿合并收场：死属主残留 MERGE_HEAD = abort + 审计 ─────────────

  test("④ owner-gone with the member's own leftover MERGE_HEAD aborts the orphan merge (recovery, not an engine merge)") {
    val system = ActorSystem(s"mlg-4-${scala.util.Random.nextInt(100000)}")
    val records = ListBuffer.empty[String]
    val repo = mkRepo(tempRoot / "mlg4-repo", branch = "delegate/d-orphan")
    val mh = startConflictedMerge(repo, "delegate/d-orphan") // 死属主自己的未收场合并
    MergeQueue.installLivenessProbe(ref => IO.pure(false)) // 属主已死
    for
      a <- member(system, records, "d-orphan", repo, readyAt = 1L)
      _ <- IO { MergeQueue.seedMemberForTest(a.copy(projectKey = "lane-4", state = MergeQueue.State.TurnGranted, grantedAt = Some(9L))) }
      _ <- IO { MergeQueue.markOwnerGone("d-orphan", "merge-round holder session is no longer live") }
      state = MergeQueue.find("d-orphan").map(_.state)
      laneClear = !os.exists(repo / ".git" / "MERGE_HEAD")
      aborted = chainLines("orphan-abort")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(state, Some("owner-gone"), "the dead holder's membership closes owner-gone")
      assertEquals(laneClear, true, s"the orphan MERGE_HEAD ($mh) was aborted — the lane is free for the next member")
      assertEquals(aborted.size, 1, "the abort is audited (orphan-abort line)")
      assert(aborted.head.payload.hcursor.get[Boolean]("aborted").toOption.contains(true), "the audit line carries the abort result")
    end for
  }

  // ── ⑤ 零触碰线：他人分支的 MERGE_HEAD 绝不 abort ─────────────────────

  test("⑤ a MERGE_HEAD belonging to another branch is NEVER aborted on owner-gone (human manual merge protection)") {
    val system = ActorSystem(s"mlg-5-${scala.util.Random.nextInt(100000)}")
    val records = ListBuffer.empty[String]
    val repo = mkRepo(tempRoot / "mlg5-repo", branch = "delegate/d-touch")
    val mh = startConflictedMerge(repo, "delegate/d-someone-else") // 残留指向他人分支
    for
      a <- member(system, records, "d-touch", repo, readyAt = 1L)
      _ <- IO { MergeQueue.seedMemberForTest(a.copy(projectKey = "lane-5", state = MergeQueue.State.TurnGranted, grantedAt = Some(9L))) }
      _ <- IO { MergeQueue.markOwnerGone("d-touch", "session ended") }
      stillThere = os.read(repo / ".git" / "MERGE_HEAD").trim
      skipped = chainLines("orphan-abort-skipped")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(stillThere, mh, "the foreign merge state is untouched (the engine never aborts what it cannot attribute)")
      assertEquals(skipped.size, 1, "the skip is audited (orphan-abort-skipped line)")
    end for
  }

  // ── ⑥ 窗口销账（病灶③）：careTick 账实核对立即释放窗 ────────────────

  test("⑥ care-tick fact-check releases the window as soon as the merge commit lands (the owner never reports back)") {
    val system = ActorSystem(s"mlg-6-${scala.util.Random.nextInt(100000)}")
    val records = ListBuffer.empty[String]
    val repo = mkRepo(tempRoot / "mlg6-repo", branch = "delegate/d-landed", mergeIntoMain = true)
    for
      a <- member(system, records, "d-landed", repo, readyAt = 1L)
      _ <- IO {
        // 属主死后窗仍持（病灶③形态：合并 commit 已落、属主未报/已死）
        MergeQueue.seedMemberForTest(
          a.copy(projectKey = "lane-6", state = MergeQueue.State.TurnGranted, grantedAt = Some(7L), internal = None)
        )
      }
      holderBefore = MergeQueue.find("d-landed").map(_.state)
      _ <- IO.blocking(MergeQueue.careTick())
      after = MergeQueue.find("d-landed").map(m => (m.state, m.closeReason))
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(holderBefore, Some("turn-granted"), "fixture: the window is held by a silent/dead owner")
      assertEquals(after.map(_._1), Some("merged"), "the care-tick fact-check closes the window as merged")
      assert(
        after.flatMap(_._2).exists(_.contains("care-tick fact-check")),
        s"the close reason names the fact-check release: ${after.flatMap(_._2)}"
      )
    end for
  }

  // ── ⑦ 合并 brief 自愈教学：自家残留 abort+重试子句 ───────────────────

  test("⑦ the merge brief teaches the OWN-leftover recovery (abort your own MERGE_HEAD and retry once in-round)") {
    val system = ActorSystem(s"mlg-7-${scala.util.Random.nextInt(100000)}")
    val records = ListBuffer.empty[String]
    val repo = mkRepo(tempRoot / "mlg7-repo", branch = "delegate/d-brief")
    for
      e <- member(system, records, "d-brief", repo, readyAt = 1L)
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      val brief = MergeQueue.mergeBriefFor(e)
      assert(brief.contains("YOUR OWN leftover merge state"), "the brief names the own-leftover special case")
      assert(brief.contains("merge --abort"), "the brief teaches the abort recovery")
      assert(brief.contains("Never abort when MERGE_HEAD points at someone else's branch"), "the brief pins the zero-touch line")
      assert(brief.contains("re-queues your membership automatically"), "the brief states lane-busy refusals requeue (not fail)")
    end for
  }

end MergeLaneGateSpec
