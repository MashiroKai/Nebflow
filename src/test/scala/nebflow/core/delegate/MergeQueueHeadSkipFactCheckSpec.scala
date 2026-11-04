package nebflow.core.delegate

import cats.effect.IO
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, AgentCommand, AgentEvent}
import nebflow.shared.PathUtil

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*

/**
 * 停顿审计修复批（2026-11-04 · `20261104_164800_delegate-stall-audit` §5 排序 1+2）
 * —— **R2 只读账实核对** + **R1 队头跳过/退避/上界降级** 的判红锚。
 *
 * 每个修复都给**双向**读数（同一夹具、同一拍语义）：
 *   - **反向验红**：`careTickWith(openFactCheck = false)` / `pumpWith(headSkip = false)`
 *     = 修复前的旧代码路径（不是「注释掉断言」，而是真的走旧分支）——必须复现
 *     原症状（已合成员永远开不了账 / 队尾被队头饿死）；
 *   - **正向验绿**：`careTick()` / `pump()` = 生产路径——同一夹具下一拍收口 / 同一
 *     拍内队尾获窗。
 *
 * 夹具：真 git 仓（`merge-base --is-ancestor` 的真读数）+ 真 actor（Sink 收放行
 * 注入）。**不跑真合并**：R2 的语义正是「分支已在 main 上，账上还开着」。
 */
class MergeQueueHeadSkipFactCheckSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-merge-head-skip"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  removeRetry(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    sys.props.remove("nebflow.mergequeue.backoff.baseMs")
    sys.props.remove("nebflow.mergequeue.backoff.maxMs")
    sys.props.remove("nebflow.mergequeue.backoff.maxRounds")

  private def removeRetry(p: os.Path): Unit =
    if os.exists(p) then
      var attempt = 0
      while os.exists(p) && attempt < 8 do
        try os.remove.all(p)
        catch case _: Throwable => ()
        if os.exists(p) then
          attempt += 1
          Thread.sleep(200)

  override def beforeEach(context: munit.BeforeEach): Unit =
    MergeQueue.reset()
    grants.clear()
    blockNotices.clear()
    removeRetry(tempRoot / "chains")
    removeRetry(tempRoot / "delegates")
    removeRetry(tempRoot / "merge-channel")
    // 退避参数：缺省把窗拉长（60s）——测试窗口内「退避窗未过」是确定事实，
    // 不靠墙钟；需要「窗已过」的用例自己临时覆盖（见 R1-上界）。
    sys.props("nebflow.mergequeue.backoff.baseMs") = "60000"
    sys.props("nebflow.mergequeue.backoff.maxMs") = "600000"
    sys.props.remove("nebflow.mergequeue.backoff.maxRounds")

  override def afterEach(context: munit.AfterEach): Unit =
    MergeQueue.reset()
    MergeQueue.installLivenessProbe(_ => IO.pure(true))
    MergeQueue.OwnerGonePort.clear()
    MergeQueue.BlockNotifyPort.clear()

  // ── 夹具 ──────────────────────────────────────────────────────────────

  private def mkRepo(dir: os.Path, branch: String, mergeIntoBase: Boolean): os.Path =
    removeRetry(dir)
    os.makeDir.all(dir)
    os.proc("git", "init", "-b", "main", dir.toString).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    def g(args: String*): os.CommandResult =
      os.proc(Seq("git", "-C", dir.toString) ++ args).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    g("config", "user.email", "spec@nebflow.local")
    g("config", "user.name", "spec")
    g("commit", "--allow-empty", "-m", "init")
    g("checkout", "-b", branch)
    os.write(dir / s"${branch.replace('/', '-')}.txt", "deliverable")
    g("add", ".")
    g("commit", "-m", "deliverable")
    g("checkout", "main")
    if mergeIntoBase then g("merge", "--no-ff", branch, "-m", "merge the branch by hand (human fallback)")
    dir

  private class Sink(records: ListBuffer[String]) extends nebflow.actor.Behavior[AgentCommand]:
    def receive(ctx: nebflow.actor.ActorContext[AgentCommand], msg: AgentCommand): IO[nebflow.actor.Behavior[AgentCommand]] =
      msg match
        case AgentCommand.UserInput(text, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
          IO {
            records += text
            this
          }
        case _ => IO.pure(this)

  private def sink(system: ActorSystem, name: String): IO[ActorRef[AgentCommand]] =
    system.spawn(new Sink(grants), name)

  private def deadSup(system: ActorSystem, name: String): IO[ActorRef[AgentEvent]] =
    system.spawn(
      new nebflow.actor.Behavior[AgentEvent]:
        def receive(ctx: nebflow.actor.ActorContext[AgentEvent], msg: AgentEvent): IO[nebflow.actor.Behavior[AgentEvent]] =
          IO.pure(this),
      name
    )

  private val grants: ListBuffer[String] = ListBuffer.empty
  private val blockNotices = ListBuffer.empty[(String, String, String)]

  /** 既有「N 拍同因升级作者」的跑道长度（上界降级不得截断它——回归判据）。 */
  private val MaxPrecedenceRound: Int = MergeQueue.EscalateAfterBlockedRounds

  private def installNotices(): Unit =
    blockNotices.clear()
    MergeQueue.BlockNotifyPort.install((m, kind, reason) => IO { blockNotices += ((m.delegateId, kind, reason)) })

  /** 无投递通道成员（`79c40e17` 形态：预记账，属主回合尚未收尾 ⇒ 无 leg）。 */
  private def leglessMember(delegateId: String, repo: os.Path, readyAt: Long, projectKey: String): MergeQueue.Member =
    MergeQueue.Member(
      delegateId = delegateId,
      branch = s"delegate/$delegateId",
      baseWs = repo.toString,
      baseBranch = "main",
      worktree = false,
      projectKey = projectKey,
      taskId = Some("31"),
      parentSessionId = "nebula-root",
      description = s"task for $delegateId",
      readyAt = readyAt,
      channelKind = "internal",
      internal = None,
      external = None
    )

  /** 健康成员（真 actor leg ⇒ 放行通知可送达）。 */
  private def healthyMember(
    system: ActorSystem,
    delegateId: String,
    repo: os.Path,
    readyAt: Long,
    projectKey: String
  ): IO[MergeQueue.Member] =
    for
      child <- sink(system, s"child-$delegateId")
      sup <- deadSup(system, s"sup-$delegateId")
    yield MergeQueue.Member(
      delegateId = delegateId,
      branch = s"delegate/$delegateId",
      baseWs = repo.toString,
      baseBranch = "main",
      worktree = false,
      projectKey = projectKey,
      taskId = Some("31"),
      parentSessionId = "nebula-root",
      description = s"task for $delegateId",
      readyAt = readyAt,
      channelKind = "internal",
      internal = Some(MergeQueue.InternalLeg(child, sup))
    )

  private def chainRows(action: String): List[ChainStore.Entry] =
    ChainStore.listSync().flatMap(_.entries).filter(_.kind == MergeQueue.EntryKind).filter { e =>
      e.payload.hcursor.get[String]("action").toOption.contains(action)
    }

  private def rowReason(e: ChainStore.Entry): String =
    e.payload.hcursor.get[String]("reason").toOption.getOrElse("")

  // ══════════════════════════════════════════════════════════════════════
  // R2 —— careTick 只读账实核对（全部开放成员）
  // ══════════════════════════════════════════════════════════════════════

  test("R2-红：旧语义（只核持窗成员）下，队头堵着 + 队尾分支已在 base 上 ⇒ N 拍零变化，永远开不了账") {
    val projectKey = "p-r2-red"
    val headRepo = mkRepo(tempRoot / "r2-red-head", branch = "delegate/r2-head", mergeIntoBase = false)
    val repo = mkRepo(tempRoot / "r2-red-repo", branch = "delegate/r2-red", mergeIntoBase = true)
    installNotices()
    val system = ActorSystem(s"mr2red-${scala.util.Random.nextInt(100000)}")
    val id = "r2-red"
    val head = leglessMember("r2-head", headRepo, readyAt = 100L, projectKey) // 队头（无通道，永久占位）
    for
      m <- healthyMember(system, id, repo, readyAt = 200L, projectKey) // 队尾：分支已并、账仍开放
      _ <- MergeQueue.enqueuePendingIO(head)
      _ <- MergeQueue.enqueuePendingIO(m) // 预记账（不 pump）——审计 §2.5 的 A2 账面形态
      _ <- IO(1 to 5 foreach (_ => MergeQueue.careTickWith(openFactCheck = false, headSkip = false)))
      after <- IO(MergeQueue.find(id))
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(after.map(_.state), Some(MergeQueue.State.Waiting), "OLD semantics: an already-merged waiting member stays open forever")
      assertEquals(chainRows("merge-merged").size, 0, "OLD semantics: no close-out row is ever written for it")
      assert(after.exists(_.closedAt.isEmpty), "no close-out timestamp — the human fallback is the only exit (A2: 7 members)")
      assertEquals(grants.toList, Nil, "and it is never granted either (it is not the head) — zero progress in 5 ticks")
  }

  test("R2-绿：同一夹具 + 一拍 careTick（生产路径）⇒ 只读核对命中，队尾成员按 merged 关账（不占合并窗）") {
    val projectKey = "p-r2-green"
    val headRepo = mkRepo(tempRoot / "r2-green-head", branch = "delegate/r2g-head", mergeIntoBase = false)
    val repo = mkRepo(tempRoot / "r2-green-repo", branch = "delegate/r2-green", mergeIntoBase = true)
    installNotices()
    val system = ActorSystem(s"mr2green-${scala.util.Random.nextInt(100000)}")
    val id = "r2-green"
    val head = leglessMember("r2g-head", headRepo, readyAt = 100L, projectKey) // 队头仍然堵着（R1 之外的另一半）
    for
      m <- healthyMember(system, id, repo, readyAt = 200L, projectKey)
      _ <- MergeQueue.enqueuePendingIO(head)
      _ <- MergeQueue.enqueuePendingIO(m)
      before <- IO(MergeQueue.find(id))
      _ <- IO(MergeQueue.careTick()) // 生产路径：openFactCheck = true
      after <- IO(MergeQueue.find(id))
      rows <- IO(chainRows("merge-merged").filter(e => rowReason(e).contains("read-only care-tick fact-check")))
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(before.map(_.state), Some(MergeQueue.State.Waiting), "precondition: the member is open before the tick")
      assertEquals(after.map(_.state), Some(MergeQueue.State.Merged), "NEW: one tick closes it as merged — no merge window, no human")
      assert(after.exists(_.closedAt.isDefined), "close-out timestamp written")
      assertEquals(rows.size, 1, s"exactly one care-tick fact-check close-out row, got ${rows.size}")
      assert(
        grants.toList.forall(g => !g.contains(id)),
        s"the merged member was never handed a merge window: ${grants.toList.map(_.take(60))}"
      )
      assert(
        blockNotices.toList.exists { case (id0, kind, _) => id0 == id && kind == "merged" },
        s"the parent is told the membership closed as merged: ${blockNotices.toList}"
      )
  }

  test("R2-红线：核对读数不可得（base 工作区不是 git 仓/不可读）⇒ fail-soft，绝不关账") {
    val projectKey = "p-r2-soft"
    val notARepo = os.temp.dir(prefix = "r2-soft-nonrepo")
    installNotices()
    val system = ActorSystem(s"mr2soft-${scala.util.Random.nextInt(100000)}")
    val id = "r2-soft"
    for
      m <- healthyMember(system, id, notARepo, readyAt = 100L, projectKey)
      _ <- MergeQueue.enqueuePendingIO(m)
      _ <- IO(1 to 3 foreach (_ => MergeQueue.careTick()))
      after <- IO(MergeQueue.find(id))
      rows <- IO(chainRows("merge-merged"))
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assert(
        after.exists(m0 => m0.state != MergeQueue.State.Merged),
        s"a failed fact-check must NEVER close a member, got state=${after.map(_.state)}"
      )
      assertEquals(rows.size, 0, "no merge-merged row from an unreadable fact-check")
  }

  // ══════════════════════════════════════════════════════════════════════
  // R1 —— 队头不再堵死全队（跳过 + 退避 + 上界降级）
  // ══════════════════════════════════════════════════════════════════════

  test("R1-红：旧语义（headSkip=false）下，无投递通道的队头占窗 N 拍，健康队尾零获窗（饿死）") {
    val projectKey = "p-r1-red"
    val headRepo = mkRepo(tempRoot / "r1-red-head", branch = "delegate/r1-head", mergeIntoBase = false)
    val tailRepo = mkRepo(tempRoot / "r1-red-tail", branch = "delegate/r1-tail", mergeIntoBase = false)
    installNotices()
    val system = ActorSystem(s"mr1red-${scala.util.Random.nextInt(100000)}")
    val headId = "r1-head"
    val tailId = "r1-tail"
    val head = leglessMember(headId, headRepo, readyAt = 100L, projectKey)
    for
      tail <- healthyMember(system, tailId, tailRepo, readyAt = 200L, projectKey)
      _ <- MergeQueue.enqueuePendingIO(head)
      _ <- MergeQueue.enqueuePendingIO(tail)
      started <- IO(System.currentTimeMillis())
      _ <- IO(1 to 5 foreach (_ => MergeQueue.pumpWith(projectKey, headSkip = false)))
      elapsed <- IO(System.currentTimeMillis() - started)
      headAfter <- IO(MergeQueue.find(headId))
      tailAfter <- IO(MergeQueue.find(tailId))
      blocked <- IO(chainRows("grant-blocked"))
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(grants.toList, Nil, s"OLD semantics: the healthy tail is starved for 5 straight ticks (elapsed ${elapsed}ms)")
      assertEquals(tailAfter.map(_.state), Some(MergeQueue.State.Waiting), "tail never left waiting")
      assertEquals(headAfter.map(_.blockedRounds), Some(5), "the leg-less head was re-granted and re-blocked 5 times")
      assertEquals(blocked.size, 5, "5 grant-blocked audit rows — the self-lock the audit counted 220 times")
      assert(blocked.forall(e => rowReason(e).contains("no live delivery channel")), "the block reason is the misleading 'after restart' text")
  }

  test("R1-绿：同一夹具 + 一次 pump（生产路径）⇒ 队头让位退避，同一拍内队尾获窗") {
    val projectKey = "p-r1-green"
    val headRepo = mkRepo(tempRoot / "r1-green-head", branch = "delegate/r1-head", mergeIntoBase = false)
    val tailRepo = mkRepo(tempRoot / "r1-green-tail", branch = "delegate/r1-tail", mergeIntoBase = false)
    installNotices()
    val system = ActorSystem(s"mr1green-${scala.util.Random.nextInt(100000)}")
    val headId = "r1-head"
    val tailId = "r1-tail"
    val head = leglessMember(headId, headRepo, readyAt = 100L, projectKey)
    for
      tail <- healthyMember(system, tailId, tailRepo, readyAt = 200L, projectKey)
      _ <- MergeQueue.enqueuePendingIO(head)
      _ <- MergeQueue.enqueuePendingIO(tail)
      started <- IO(System.currentTimeMillis())
      _ <- IO(MergeQueue.pump(projectKey)) // 一次生产拍
      elapsed <- IO(System.currentTimeMillis() - started)
      headAfter <- IO(MergeQueue.find(headId))
      tailAfter <- IO(MergeQueue.find(tailId))
      blocked <- IO(chainRows("grant-blocked"))
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(grants.size, 1, s"NEW: exactly one merge turn in ONE tick (elapsed ${elapsed}ms), got ${grants.size}")
      assert(grants.head.contains(tailId), s"the window went to the healthy TAIL, not the stuck head: ${grants.head.take(160)}")
      assertEquals(tailAfter.map(_.state), Some(MergeQueue.State.TurnGranted), "tail holds the merge window")
      assertEquals(headAfter.map(_.state), Some(MergeQueue.State.Waiting), "head returned to waiting (membership kept)")
      assertEquals(headAfter.map(_.blockedRounds), Some(1), "head's blocked round is counted")
      assert(headAfter.flatMap(_.skipUntilMs).exists(_ > 0L), "head carries a backoff deadline (skip window)")
      assertEquals(headAfter.flatMap(_.needsHuman), None, "one round is far from the retry ceiling — no human-needed degradation yet")
      assertEquals(blocked.size, 1, "one grant-blocked row for the head (audited, never silent)")
  }

  test("R1-上界：孤身死结连续被堵到 SoloMaxBlockedRounds ⇒ needsHuman 显式降级 + 一次 escalate + 队列停止自动授窗（不许无限重试）") {
    val projectKey = "p-r1-ceiling"
    val headRepo = mkRepo(tempRoot / "r1-ceiling-head", branch = "delegate/r1-ceiling", mergeIntoBase = false)
    installNotices()
    val system = ActorSystem(s"mr1ceil-${scala.util.Random.nextInt(100000)}")
    val id = "r1-ceiling"
    val ceiling = MergeQueue.SoloMaxBlockedRounds
    // 零退避：每拍都能再试（「N 拍到顶」这件事与墙钟脱钩，纯记账语义）。
    sys.props("nebflow.mergequeue.backoff.baseMs") = "0"
    sys.props("nebflow.mergequeue.backoff.maxMs") = "0"
    val head = leglessMember(id, headRepo, readyAt = 100L, projectKey)
    for
      _ <- MergeQueue.enqueuePendingIO(head)
      // 前半程：既有「N 拍同因升级作者」阶梯的跑道不许被上界截断（⑬/MergeQueueSpec 面）。
      _ <- IO(1 to MaxPrecedenceRound foreach (_ => MergeQueue.pump(projectKey)))
      midway <- IO(MergeQueue.find(id))
      _ <- IO(1 to (ceiling * 2) foreach (_ => MergeQueue.pump(projectKey)))
      after <- IO(MergeQueue.find(id))
      rows <- IO(chainRows("grant-blocked"))
      escalations <- IO(blockNotices.toList.filter { case (id0, kind, _) => id0 == id && kind == "escalate" })
      _ <- IO(1 to 10 foreach (_ => MergeQueue.pump(projectKey))) // 降级后再来十拍
      afterMore <- IO(MergeQueue.find(id))
      rowsMore <- IO(chainRows("grant-blocked"))
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assert(
        midway.flatMap(_.needsHuman).isEmpty,
        s"a solo dead end keeps retrying through round $MaxPrecedenceRound (the N-tick escalation ladder keeps its full runway)"
      )
      assertEquals(after.map(_.blockedRounds), Some(ceiling), s"retries stop at the absolute ceiling ($ceiling): no infinite retry")
      assert(after.flatMap(_.needsHuman).isDefined, "the member degrades into the explicit needs-human state")
      assert(
        after.flatMap(_.needsHuman).exists(h => h.contains("needs human handling") && h.contains("merge 'delegate/r1-ceiling'")),
        s"the degradation names the actionable requirement: ${after.flatMap(_.needsHuman)}"
      )
      assertEquals(after.flatMap(_.skipUntilMs), None, "a degraded member has no skip window left — it simply never gets granted")
      // 升级恰好一次来自**降级**（既有 N 拍同因升级已在上面的中途拍点发生过，本批不改它）。
      val degradeEscalations = escalations.count(_._3.contains("needs human handling"))
      assertEquals(degradeEscalations, 1, s"exactly one escalation announces the degradation, got $degradeEscalations of ${escalations.size}")
      assertEquals(afterMore.map(_.blockedRounds), Some(ceiling), "further ticks do not raise the round counter")
      assertEquals(rowsMore.size, rows.size, "further ticks add no grant-blocked rows (the queue stopped picking it)")
      assertEquals(grants.toList, Nil, "no merge turn ever delivered (leg-less member) — but the queue no longer re-tries forever")
  }

  test("R1-读数：退避指数×2、封顶上界、抖动在前两者之内（纯函数，零等待）") {
    val base = 1000L
    val max = 60_000L
    val noJitterMid = 0.5 // factor = 1.0
    assertEquals(MergeQueue.backoffDelayMs(1, base, max, noJitterMid), base)
    assertEquals(MergeQueue.backoffDelayMs(2, base, max, noJitterMid), 2 * base)
    assertEquals(MergeQueue.backoffDelayMs(3, base, max, noJitterMid), 4 * base)
    assertEquals(MergeQueue.backoffDelayMs(20, base, max, noJitterMid), max, "exponential growth is capped by the ceiling")
    val low = MergeQueue.backoffDelayMs(3, base, max, 0.0)
    val high = MergeQueue.backoffDelayMs(3, base, max, 1.0)
    assert(low < high, s"jitter spreads siblings apart ($low < $high)")
    assert(math.abs(high - 4 * base) <= (4 * base * MergeQueue.BackoffJitterRatio) + 1, s"jitter stays inside ±ratio: $high")
    assert(math.abs(low - 4 * base) <= (4 * base * MergeQueue.BackoffJitterRatio) + 1, s"jitter stays inside ±ratio: $low")
  }

  test("R1-两道上界：正当饿死别人的死结 8 拍到顶即降级（不再挡住队尾）；同一队里的闸类成员（项目级）照旧逐拍重试、不降级") {
    val projectKey = "p-r1-degrade"
    val headRepo = mkRepo(tempRoot / "r1-degrade-head", branch = "delegate/r1-degrade", mergeIntoBase = false)
    val gateRepo = mkRepo(tempRoot / "r1-degrade-gate", branch = "delegate/r1-degrade-gate", mergeIntoBase = false)
    // 队尾的形态：base 工作区检出的不是成员声明的 baseBranch（方向闸 = 项目级阻塞）
    os.proc("git", "-C", gateRepo.toString, "checkout", "delegate/r1-degrade-gate")
      .call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    installNotices()
    val system = ActorSystem(s"mr1deg-${scala.util.Random.nextInt(100000)}")
    val headId = "r1-degrade"
    val gateId = "r1-degrade-gate"
    sys.props("nebflow.mergequeue.backoff.baseMs") = "0"
    sys.props("nebflow.mergequeue.backoff.maxMs") = "0"
    val head = leglessMember(headId, headRepo, readyAt = 100L, projectKey)
    for
      gate <- healthyMember(system, gateId, gateRepo, readyAt = 200L, projectKey)
      _ <- MergeQueue.enqueuePendingIO(head)
      _ <- MergeQueue.enqueuePendingIO(gate)
      _ <- IO(1 to (MergeQueue.MaxBlockedRounds * 2) foreach (_ => MergeQueue.pump(projectKey)))
      headAfter <- IO(MergeQueue.find(headId))
      gateAfter <- IO(MergeQueue.find(gateId))
      escalations <- IO(blockNotices.toList.filter { case (id0, kind, _) => id0 == headId && kind == "escalate" })
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(headAfter.map(_.blockedRounds), Some(MergeQueue.MaxBlockedRounds), "the head that starves a tail stops at the short ceiling (8 rounds)")
      assert(headAfter.flatMap(_.needsHuman).isDefined, "and degrades to the explicit needs-human state")
      assert(
        headAfter.flatMap(_.needsHuman).exists(_.contains("no-channel")),
        s"the degradation names the block class: ${headAfter.flatMap(_.needsHuman)}"
      )
      assertEquals(escalations.size, 1, s"one escalation, not one per tick: ${escalations.size}")
      assert(gateAfter.flatMap(_.needsHuman).isEmpty, "a PROJECT-level gate block is never degraded to needs-human (its condition is external)")
      assert(
        gateAfter.map(_.blockedRounds).exists(_ > MergeQueue.MaxBlockedRounds),
        s"the gated tail keeps being retried every tick past the short ceiling: ${gateAfter.map(_.blockedRounds)}"
      )
      assertEquals(gateAfter.map(_.state), Some(MergeQueue.State.Waiting), "it stays queued (never granted — the direction gate is real)")
      assertEquals(grants.toList, Nil, "neither member was ever handed the window (head has no channel, tail is on the wrong seat)")
  }

  test("R1-自愈：降级后投递通道回来 ⇒ 复活（清退避/needsHuman）并在该拍获窗；不带新腿的重入队仍钉在 needsHuman") {
    val projectKey = "p-r1-revive"
    val repo = mkRepo(tempRoot / "r1-revive", branch = "delegate/r1-revive", mergeIntoBase = false)
    installNotices()
    val system = ActorSystem(s"mr1revive-${scala.util.Random.nextInt(100000)}")
    val id = "r1-revive"
    val ceiling = MergeQueue.SoloMaxBlockedRounds
    sys.props("nebflow.mergequeue.backoff.baseMs") = "0"
    sys.props("nebflow.mergequeue.backoff.maxMs") = "0"
    val head = leglessMember(id, repo, readyAt = 100L, projectKey)
    for
      _ <- MergeQueue.enqueuePendingIO(head)
      _ <- IO(1 to (ceiling * 2) foreach (_ => MergeQueue.pump(projectKey)))
      degraded <- IO(MergeQueue.find(id))
      // 红腿：属主回合又申报一次，**但没有带来新的投递腿** ⇒ 条件没变，不许复活
      _ <- IO(MergeQueue.enqueue(leglessMember(id, repo, readyAt = 100L, projectKey)))
      _ <- IO(1 to 3 foreach (_ => MergeQueue.pump(projectKey)))
      stillDegraded <- IO(MergeQueue.find(id))
      grantsBefore <- IO(grants.size)
      // 绿腿：属主回合收尾，leg 回来了（同一成员、同一 readyAt——FIFO 位次不变）
      leg <- healthyMember(system, id, repo, readyAt = 100L, projectKey)
      _ <- IO(MergeQueue.enqueue(leg))
      revived <- IO(MergeQueue.find(id))
      grantsAfter <- IO(grants.toList)
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(degraded.flatMap(_.needsHuman).isDefined, true, "precondition: the member sits at the ceiling")
      assertEquals(stillDegraded.flatMap(_.needsHuman).isDefined, true, "red: a re-report without a delivery leg changes nothing — no revival on noise")
      assertEquals(grantsBefore, 0, "red: still zero merge turns before the channel came back")
      assert(revived.flatMap(_.needsHuman).isEmpty, s"NEW: the delivery leg coming back clears the degradation, got ${revived.flatMap(_.needsHuman)}")
      assertEquals(revived.map(_.blockedRounds), Some(0), "the retry counter restarts on revival")
      assertEquals(revived.flatMap(_.skipUntilMs), None, "no stale backoff window survives the revival")
      assert(grantsAfter.exists(_.contains(id)), s"NEW: the revived member is granted the window in that same enqueue tick: ${grantsAfter.map(_.take(80))}")
  }

  test("R1-不让位：单人队列里被堵成员照常逐拍重试（让位是为了让别人走——没别人就不空转，既有升级阶梯零漂移）") {
    val projectKey = "p-r1-solo"
    val repo = mkRepo(tempRoot / "r1-solo", branch = "delegate/r1-solo", mergeIntoBase = false)
    installNotices()
    val system = ActorSystem(s"mr1solo-${scala.util.Random.nextInt(100000)}")
    val id = "r1-solo"
    val head = leglessMember(id, repo, readyAt = 100L, projectKey)
    for
      _ <- MergeQueue.enqueuePendingIO(head)
      _ <- IO(MergeQueue.pump(projectKey)) // 第一拍：被堵
      first <- IO(MergeQueue.find(id))
      _ <- IO(MergeQueue.pump(projectKey)) // 第二拍：后面没人 ⇒ 不空转跳过，照常再试
      second <- IO(MergeQueue.find(id))
      rows <- IO(chainRows("grant-blocked"))
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(first.map(_.blockedRounds), Some(1))
      assertEquals(second.map(_.blockedRounds), Some(2), "a solo member is retried every tick — no skip window, no idling")
      assertEquals(second.flatMap(_.skipUntilMs), None, "no skip window is armed when there is nobody behind to hand the window to")
      assertEquals(rows.size, 2, "each retry is audited exactly once (never silent)")
      assertEquals(grants.toList, Nil)
  }
end MergeQueueHeadSkipFactCheckSpec
