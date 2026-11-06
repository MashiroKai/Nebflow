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
    MergeDeclaration.reset()
    grants.clear()
    blockNotices.clear()
    goneSessions.clear()
    removeRetry(tempRoot / "chains")
    removeRetry(tempRoot / "delegates")
    removeRetry(tempRoot / "merge-channel")
    // 退避参数：缺省把窗拉长（60s）——测试窗口内「退避窗未过」是确定事实，
    // 不靠墙钟；需要「窗已过」的用例自己临时覆盖（见 R1-上界）。
    sys.props("nebflow.mergequeue.backoff.baseMs") = "60000"
    sys.props("nebflow.mergequeue.backoff.maxMs") = "600000"
    sys.props.remove("nebflow.mergequeue.backoff.maxRounds")
    installPort()

  override def afterEach(context: munit.AfterEach): Unit =
    MergeQueue.reset()
    MergeDeclaration.reset()
    nebflow.core.delegate.MergeQueueTestPorts.install()
    MergeQueue.OwnerGonePort.clear()
    MergeQueue.BlockNotifyPort.clear()
    goneSessions.clear()

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

  private val grants: ListBuffer[String] = ListBuffer.empty
  private val blockNotices = ListBuffer.empty[(String, String, String)]
  private val ownerGoneEscalations = ListBuffer.empty[(String, String)]
  private val goneSessions = scala.collection.mutable.Set.empty[String]

  /** 身份路由表 + 已删会话集（端口是进程级单例 ⇒ 全套共享一张表）。 */
  private def installPort(): Unit =
    nebflow.core.delegate.MergeQueueTestPorts.routing(
      Map.empty,
      goneSessions.contains,
      onDelivered = s => { grants += s.brief; () }
    )

  /** 既有「N 拍同因升级作者」的跑道长度（回归判据：闸类阻塞的升级阶梯零漂移）。 */
  private val MaxPrecedenceRound: Int = MergeQueue.EscalateAfterBlockedRounds

  private def installNotices(): Unit =
    blockNotices.clear()
    MergeQueue.BlockNotifyPort.install((m, kind, reason) => IO { blockNotices += ((m.delegateId, kind, reason)) })

  /** owner-gone 升级记录单点（R1 各腿的 owner-gone 面断言用）。 */
  private def installOwnerGonePort(): Unit =
    ownerGoneEscalations.clear()
    MergeQueue.OwnerGonePort.install((m, reason) => IO { ownerGoneEscalations += ((m.delegateId, reason)); () })

  /**
   * **不可投递成员**（R1 死结形态在 P1 身份化后的唯一真实来源）：收件会话**已删**
   * ⇒ 端口如实回 `parent-session-gone` ⇒ 放行时通知投不出去 ⇒ owner-gone。
   *
   * 前身 `leglessMember` 测的是闭包时代的形状（没有 `InternalLeg`/`ExternalLeg` 闭包
   * 即「无通道」，于是逐拍 `requeueBlocked(NoChannel)` + 退避 + 降级）。身份投递下
   * 没有闭包这回事：投不出去只有一种原因——收件会话没了，而那是 owner-gone，不是重试。
   */
  private def leglessMember(delegateId: String, repo: os.Path, readyAt: Long, projectKey: String): MergeQueue.Member =
    goneSessions += delegateId
    memberOf(delegateId, repo, readyAt, projectKey)

  /** 可投递成员（身份投递必达 ⇒ 放行通知送得到）。 */
  private def healthyMember(
    system: ActorSystem,
    delegateId: String,
    repo: os.Path,
    readyAt: Long,
    projectKey: String
  ): IO[MergeQueue.Member] =
    IO.pure(memberOf(delegateId, repo, readyAt, projectKey))

  private def memberOf(delegateId: String, repo: os.Path, readyAt: Long, projectKey: String): MergeQueue.Member =
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
      // 宣告闸批 2026-11-04：属主宣告在案（`delegate_report(category="merge")`）——授窗前提
      declaredAt = Some(1_000L),
      declareSource = Some(nebflow.core.delegate.MergeDeclaration.Source.ReportMerge),
      channelKind = "identity",
    )

  private def chainRows(action: String): List[ChainStore.Entry] =
    ChainStore.listSync().flatMap(_.entries).filter(_.kind == MergeQueue.EntryKind).filter { e =>
      e.payload.hcursor.get[String]("action").toOption.contains(action)
    }

  private def rowReason(e: ChainStore.Entry): String =
    e.payload.hcursor.get[String]("reason").toOption.getOrElse("")

  private def rowDelegate(e: ChainStore.Entry): String =
    e.payload.hcursor.get[String]("delegateId").toOption.getOrElse("")

  // ══════════════════════════════════════════════════════════════════════
  // R2 —— careTick 只读账实核对（全部开放成员）
  // ══════════════════════════════════════════════════════════════════════

  test("R2-红：旧语义（只核持窗成员）下，队头持窗 + 队尾分支已在 base 上 ⇒ N 拍零变化，永远开不了账") {
    val projectKey = "p-r2-red"
    val headRepo = mkRepo(tempRoot / "r2-red-head", branch = "delegate/r2-head", mergeIntoBase = false)
    val repo = mkRepo(tempRoot / "r2-red-repo", branch = "delegate/r2-red", mergeIntoBase = true)
    installNotices()
    val system = ActorSystem(s"mr2red-${scala.util.Random.nextInt(100000)}")
    val id = "r2-red"
    // 队头 = **正常持窗成员**：P1 身份投递后，「无通道永久占位」这种形态已不存在，
    // 队尾排不上的真实原因只剩「单人持窗」这一条（正是 R2 缺口要验的场景）。
    val head = memberOf("r2-head", headRepo, readyAt = 100L, projectKey)
    for
      m <- healthyMember(system, id, repo, readyAt = 200L, projectKey) // 队尾：分支已并、账仍开放
      _ <- IO(MergeQueue.enqueue(head)) // 生产路径：空队 ⇒ 立即放行，队头持窗
      _ <- MergeQueue.enqueuePendingIO(m) // 队尾预记账（不 pump）——审计 §2.5 的 A2 账面形态
      headState <- IO(MergeQueue.find("r2-head").map(_.state))
      _ <- IO(1 to 5 foreach (_ => MergeQueue.careTickWith(openFactCheck = false, headSkip = false)))
      after <- IO(MergeQueue.find(id))
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(headState, Some(MergeQueue.State.TurnGranted), "precondition: the head holds the merge window")
      assertEquals(after.map(_.state), Some(MergeQueue.State.Waiting), "OLD semantics: an already-merged waiting member stays open forever")
      assertEquals(chainRows("merge-merged").size, 0, "OLD semantics: no close-out row is ever written for it")
      assert(after.exists(_.closedAt.isEmpty), "no close-out timestamp — the human fallback is the only exit (A2: 7 members)")
      assert(
        grants.forall(g => !g.contains(s"delegate/$id")),
        s"and it is never granted either (the window is held) — zero progress in 5 ticks: ${grants.map(_.take(60))}"
      )
      assertEquals(grants.size, 1, "only the head's own merge turn was ever delivered")
  }

  test("R2-绿：同一夹具 + 一拍 careTick（生产路径）⇒ 只读核对命中，队尾成员按 merged 关账（不占合并窗）") {
    val projectKey = "p-r2-green"
    val headRepo = mkRepo(tempRoot / "r2-green-head", branch = "delegate/r2g-head", mergeIntoBase = false)
    val repo = mkRepo(tempRoot / "r2-green-repo", branch = "delegate/r2-green", mergeIntoBase = true)
    installNotices()
    val system = ActorSystem(s"mr2green-${scala.util.Random.nextInt(100000)}")
    val id = "r2-green"
    // 队头 = 正常持窗成员（单人持窗是队尾排不上的唯一现存原因）。
    val head = memberOf("r2g-head", headRepo, readyAt = 100L, projectKey)
    for
      m <- healthyMember(system, id, repo, readyAt = 200L, projectKey)
      _ <- IO(MergeQueue.enqueue(head)) // 生产路径：空队 ⇒ 队头立即持窗
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
        grants.forall(g => !g.contains(s"delegate/$id")),
        s"the merged member was never handed a merge window: ${grants.map(_.take(60))}"
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

  test("R1-红：旧语义（headSkip=false = 只挑 FIFO 队头）下，被闸堵死的队头占窗 N 拍，健康队尾零获窗（饿死）") {
    val projectKey = "p-r1-red"
    val headRepo = mkRepo(tempRoot / "r1-red-head", branch = "delegate/r1-head", mergeIntoBase = false)
    val tailRepo = mkRepo(tempRoot / "r1-red-tail", branch = "delegate/r1-tail", mergeIntoBase = false)
    // 队头形态 = **闸类死结**（方向闸：base 工作区检出的不是成员声明的 baseBranch）。
    // P1 之前「无投递通道」是另一个死结载体；身份投递下它已不存在（投不出去 = 收件
    // 会话已删 = owner-gone 终态，见 R1-绿），所以「一个死结堵死整条队」的现存形态
    // 只剩闸类——这正是旧 headSkip=false 路径必须复现的症状。
    os.proc("git", "-C", headRepo.toString, "checkout", "delegate/r1-head")
      .call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    installNotices()
    val system = ActorSystem(s"mr1red-${scala.util.Random.nextInt(100000)}")
    val headId = "r1-head"
    val tailId = "r1-tail"
    val head = memberOf(headId, headRepo, readyAt = 100L, projectKey)
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
      assertEquals(headAfter.map(_.state), Some(MergeQueue.State.Waiting), "the head keeps the head slot every tick (old path: no skip, no yield)")
      assertEquals(headAfter.map(_.blockedRounds), Some(5), "the head is re-granted and re-blocked once per tick, 5 ticks")
      assertEquals(blocked.size, 5, "5 grant-blocked audit rows — the self-lock the audit counted 220 times")
      assert(
        blocked.forall(e => rowReason(e).contains("not the checked-out branch")),
        "each tick names the same real blocker (direction gate — never silent)"
      )
  }

  test("R1-绿：同一夹具 + 一次 pump（生产路径）⇒ 不可投递的队头被逐出（owner-gone），同一拍内队尾获窗") {
    val projectKey = "p-r1-green"
    val headRepo = mkRepo(tempRoot / "r1-green-head", branch = "delegate/r1-head", mergeIntoBase = false)
    val tailRepo = mkRepo(tempRoot / "r1-green-tail", branch = "delegate/r1-tail", mergeIntoBase = false)
    installNotices()
    installOwnerGonePort()
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
      assert(grants.head.contains(tailId), s"the window went to the healthy TAIL, not the undeliverable head: ${grants.head.take(160)}")
      assertEquals(tailAfter.map(_.state), Some(MergeQueue.State.TurnGranted), "tail holds the merge window")
      // P0/P1：不可投递（收件会话已删）在第一次通知尝试时如实判 owner-gone——窗即时
      // 放给后继，绝不再有「无通道退避占位」这一层。
      assertEquals(
        headAfter.map(_.state),
        Some(MergeQueue.State.OwnerGone),
        "the undeliverable head closes owner-gone at grant time (visible, never silently parked)"
      )
      assertEquals(
        ownerGoneEscalations.map(_._1).toList,
        List(headId),
        "the head's owner-gone escalates exactly once via OwnerGonePort"
      )
      assertEquals(blocked.size, 0, "no grant-blocked rows: an undeliverable grant is owner-gone, not a retried block")
  }

  test("R1-上界：不可投递的队头第一次放行尝试即 owner-gone（旧 no-channel 退避+上界降级跑道随闭包退役）；同队的闸类死结照旧逐拍重试、永不降级、N 拍同因升级恰一次") {
    val projectKey = "p-r1-ceiling"
    val headRepo = mkRepo(tempRoot / "r1-ceiling-head", branch = "delegate/r1-ceiling", mergeIntoBase = false)
    val gateRepo = mkRepo(tempRoot / "r1-ceiling-gate", branch = "delegate/r1-gate", mergeIntoBase = false)
    // 闸类死结形态：base 工作区检出的不是成员声明的 baseBranch（方向闸——逐拍重试、
    // 不投递不 owner-gone，正是「不许无限静默」这条阶梯的现存载体）。
    os.proc("git", "-C", gateRepo.toString, "checkout", "delegate/r1-gate")
      .call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    installNotices()
    installOwnerGonePort()
    val system = ActorSystem(s"mr1ceil-${scala.util.Random.nextInt(100000)}")
    val id = "r1-ceiling"
    // 旧「两条上界」的孤身跑道长度（SoloMaxBlockedRounds）——只作**退役对照**读数：
    // 闸类成员现在会越过它继续重试（降级阶梯已随 `BlockClass.NoChannel` 退役）。
    val retiredCeiling = MergeQueue.SoloMaxBlockedRounds
    val head = leglessMember(id, headRepo, readyAt = 100L, projectKey) // 不可投递成员（对照：即时 owner-gone）
    val gate = memberOf("r1-gate", gateRepo, readyAt = 200L, projectKey) // 闸类死结（逐拍重试的载体）
    for
      _ <- MergeQueue.enqueuePendingIO(head)
      _ <- MergeQueue.enqueuePendingIO(gate)
      // 前半程：既有「N 拍同因升级作者」阶梯的跑道不许被截断。
      _ <- IO(1 to MaxPrecedenceRound foreach (_ => MergeQueue.pump(projectKey)))
      midway <- IO(MergeQueue.find("r1-gate"))
      // 后半程：越过退役上界继续拍（旧语义在这里置 needsHuman 并停止自动授窗）。
      _ <- IO(1 to (retiredCeiling * 2) foreach (_ => MergeQueue.pump(projectKey)))
      after <- IO(MergeQueue.find("r1-gate"))
      rows <- IO(chainRows("grant-blocked"))
      escalations <- IO(blockNotices.toList.filter { case (id0, kind, _) => id0 == "r1-gate" && kind == "escalate" })
      _ <- IO(1 to 10 foreach (_ => MergeQueue.pump(projectKey)))
      afterMore <- IO(MergeQueue.find("r1-gate"))
      rowsMore <- IO(chainRows("grant-blocked"))
      headAfter <- IO(MergeQueue.find(id))
      headRows <- IO(chainRows("grant-blocked").filter(e => rowDelegate(e) == id))
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      // ① 闸类成员：跑道完整（前 N 拍每拍至少一次重试），且永不降级。
      assert(
        midway.map(_.blockedRounds).exists(_ >= MaxPrecedenceRound),
        s"the N-tick escalation ladder keeps its full runway (got ${midway.map(_.blockedRounds)})"
      )
      assert(midway.flatMap(_.needsHuman).isEmpty, "no degradation mid-runway")
      assert(
        after.map(_.blockedRounds).exists(_ > retiredCeiling * 2),
        s"gate-class blocks keep being retried past the retired ceiling ($retiredCeiling): no ceiling exists for them (got ${after.map(_.blockedRounds)})"
      )
      assert(
        after.flatMap(_.needsHuman).isEmpty,
        s"a project-level gate block is NEVER degraded to needs-human (its condition is external): ${after.flatMap(_.needsHuman)}"
      )
      assertEquals(after.flatMap(_.skipUntilMs), None, "no backoff window is armed (the member is retried every tick)")
      assertEquals(escalations.size, 1, s"same-cause escalation fires exactly once, got ${escalations.size}")
      assertEquals(
        rows.size,
        after.map(_.blockedRounds).getOrElse(-1),
        "every retry is audited exactly once (never silent)"
      )
      assertEquals(
        afterMore.map(_.blockedRounds),
        after.map(_.blockedRounds).map(_ + 10),
        "further ticks keep retrying (nothing is parked, nothing stops counting)"
      )
      assertEquals(rowsMore.size, rows.size + 10, "and each of those ticks is audited exactly once")
      assertEquals(grants.toList, Nil, "no merge turn ever delivered (undeliverable head + gated tail)")
      // ② 对照腿（P1 新语义）：不可投递成员第一次放行尝试即 owner-gone，不占任何跑道。
      assertEquals(
        headAfter.map(_.state),
        Some(MergeQueue.State.OwnerGone),
        "the undeliverable member never rides a retry ladder — one attempt, closed owner-gone"
      )
      assertEquals(headAfter.map(_.blockedRounds), Some(0), "it never accumulated blocked rounds")
      assertEquals(
        ownerGoneEscalations.map(_._1).toList,
        List(id),
        "the undeliverable member's owner-gone escalates exactly once"
      )
      assertEquals(headRows, Nil, "undeliverable ≠ blocked: it writes no grant-blocked row")
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

  test("R1-两道上界：不可投递的队头第一次尝试即 owner-gone（旧「饿死别人 8 拍即降级」的短上界随闭包退役）；同队的闸类成员（项目级）照旧逐拍重试、不降级、同因升级恰一次") {
    val projectKey = "p-r1-degrade"
    val headRepo = mkRepo(tempRoot / "r1-degrade-head", branch = "delegate/r1-degrade-head", mergeIntoBase = false)
    val gateRepo = mkRepo(tempRoot / "r1-degrade-gate", branch = "delegate/r1-degrade-gate", mergeIntoBase = false)
    // 队尾的形态：base 工作区检出的不是成员声明的 baseBranch（方向闸 = 项目级阻塞）。
    os.proc("git", "-C", gateRepo.toString, "checkout", "delegate/r1-degrade-gate")
      .call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    installNotices()
    installOwnerGonePort()
    val system = ActorSystem(s"mr1deg-${scala.util.Random.nextInt(100000)}")
    val headId = "r1-degrade-head"
    val tailId = "r1-degrade-tail"
    // 旧「正当饿死别人 ⇒ MaxBlockedRounds 拍到顶即降级」的短上界——退役对照读数。
    val retiredShortCeiling = MergeQueue.MaxBlockedRounds
    val head = leglessMember(headId, headRepo, readyAt = 100L, projectKey) // 不可投递（P1：即时 owner-gone）
    for
      gate <- healthyMember(system, tailId, gateRepo, readyAt = 200L, projectKey)
      _ <- MergeQueue.enqueuePendingIO(head)
      _ <- MergeQueue.enqueuePendingIO(gate)
      _ <- IO(1 to (retiredShortCeiling * 2) foreach (_ => MergeQueue.pump(projectKey)))
      headAfter <- IO(MergeQueue.find(headId))
      gateAfter <- IO(MergeQueue.find(tailId))
      escalations <- IO(blockNotices.toList.filter { case (id0, kind, _) => id0 == tailId && kind == "escalate" })
      headRows <- IO(chainRows("grant-blocked").filter(e => rowDelegate(e) == headId))
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      // P1 对照腿：不可投递的队头不再「占窗-退回」8 拍——第一次放行尝试就 owner-gone
      // 即时收场，窗在同拍让给队尾（旧「8 拍饿死即降级」的短上界整类退役）。
      assertEquals(
        headAfter.map(_.state),
        Some(MergeQueue.State.OwnerGone),
        "the undeliverable head closes owner-gone on the first grant attempt (the 8-round starvation class is retired with the closure)"
      )
      assertEquals(ownerGoneEscalations.map(_._1).toList, List(headId), "its owner-gone escalates exactly once")
      assertEquals(headRows, Nil, "undeliverable ≠ blocked: it never writes a grant-blocked row")
      // 队尾落在**方向闸**上（base 检出分支错位 = 项目级阻塞）：逐拍重试、永不降级。
      assert(gateAfter.flatMap(_.needsHuman).isEmpty, "a PROJECT-level gate block is never degraded to needs-human (its condition is external)")
      assert(
        gateAfter.map(_.blockedRounds).exists(_ > retiredShortCeiling),
        s"the gated tail keeps being retried every tick past the short ceiling: ${gateAfter.map(_.blockedRounds)}"
      )
      assertEquals(gateAfter.map(_.state), Some(MergeQueue.State.Waiting), "it stays queued (never granted — the direction gate is real)")
      assertEquals(
        escalations.size,
        1,
        s"the gate-class member still escalates exactly once via the same-cause ladder (ladder runway intact): ${escalations.size}"
      )
      assertEquals(grants.toList, Nil, "neither member was ever handed the window (head undeliverable, tail on the wrong seat)")
  }

  test("R1-自愈：被 park 的成员在条件变化（属主宣告）后清降级、当拍获窗；不可投递成员（会话已删）绝不进退避跑道——owner-gone 即时收场") {
    val projectKey = "p-r1-revive"
    val repo = mkRepo(tempRoot / "r1-revive", branch = "delegate/r1-revive", mergeIntoBase = false)
    val goneRepo = mkRepo(tempRoot / "r1-revive-gone", branch = "delegate/r1-revive-gone", mergeIntoBase = false)
    installNotices()
    installOwnerGonePort()
    val system = ActorSystem(s"mr1revive-${scala.util.Random.nextInt(100000)}")
    val id = "r1-revive"
    // 降级载体（P1 后唯一会置 `needsHuman` 的非闸路径 = 判据不成立：属主未宣告）——
    // 旧载体是「no-channel 上界降级」，随闭包一起退役。
    val undeclared = memberOf(id, repo, readyAt = 100L, projectKey).copy(declaredAt = None, declareSource = None)
    for
      _ <- MergeQueue.enqueuePendingIO(undeclared)
      _ <- IO(MergeQueue.pump(projectKey)) // 第一拍：未宣告 ⇒ 硬 park（needsHuman）
      parked <- IO(MergeQueue.find(id))
      parkedGrants = grants.toList // 快照：park 期的投递面（后面宣告会追加一条）
      // 条件已变 = 属主自己宣告（`delegate_report(category="merge")` 的唯一落账面）。
      _ <- IO(MergeQueue.declareOwnership(id, nebflow.core.delegate.MergeDeclaration.Source.ReportMerge))
      revived <- IO(MergeQueue.find(id))
      // 对照腿：同形账面但**收件会话已删** ⇒ 投不出去 ⇒ owner-gone（自己的一条道，
      // 不被闸类队头的单人持窗挡住——闸类不让位是「不让位」那条用例的语义）。
      _ <- IO(goneSessions += "r1-revive-gone")
      _ <- MergeQueue.enqueuePendingIO(memberOf("r1-revive-gone", goneRepo, readyAt = 100L, "p-r1-revive-gone"))
      _ <- IO(MergeQueue.pump("p-r1-revive-gone"))
      dead <- IO(MergeQueue.find("r1-revive-gone"))
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assert(
        parked.flatMap(_.needsHuman).isDefined,
        s"an undeclared member is hard-parked (the queue stops auto-granting it): ${parked.map(m => (m.state, m.needsHuman))}"
      )
      assertEquals(parked.map(_.state), Some(MergeQueue.State.Waiting), "parked = still queued, just never auto-granted")
      assertEquals(parkedGrants, Nil, "no turn was delivered while parked")
      // 条件已变（属主宣告）⇒ 降级解除、当拍获窗（自愈腿：降级不是永久封存）。
      assertEquals(
        revived.map(_.state),
        Some(MergeQueue.State.TurnGranted),
        s"the declaration clears the park and the window is granted in the same tick: ${revived.map(m => (m.state, m.needsHuman))}"
      )
      assertEquals(revived.flatMap(_.needsHuman), None, "the degraded marker is cleared (self-heal, not a permanent seal)")
      assertEquals(revived.map(_.blockedRounds), Some(0), "the retry counter is reset on revival")
      assertEquals(grants.size, 1, s"exactly one merge turn was delivered after the revive: ${grants.size}")
      assert(grants.head.contains("delegate/r1-revive"), "the delivered brief is the real merge instruction")
      // 对照腿：不可投递成员第一次放行尝试即 owner-gone——绝不进退避/降级跑道。
      assertEquals(
        dead.map(_.state),
        Some(MergeQueue.State.OwnerGone),
        "the undeliverable member is closed owner-gone on the first attempt — never parked into the retry ladder"
      )
      assertEquals(dead.map(_.blockedRounds), Some(0), "it never accumulated blocked rounds")
      assertEquals(ownerGoneEscalations.map(_._1).toList, List("r1-revive-gone"), "its owner-gone escalates exactly once (audited, never silent)")
      assert(
        chainRows("grant-blocked").forall(e => rowDelegate(e) != "r1-revive-gone"),
        "undeliverable ≠ blocked: no grant-blocked row for the deleted session"
      )
  }

  test("R1-不让位：单人队列里被闸堵成员照常逐拍重试（让位是为了让别人走——没别人就不空转，既有升级阶梯零漂移）；不可投递成员一次 owner-gone 不重试") {
    val projectKey = "p-r1-solo"
    val repo = mkRepo(tempRoot / "r1-solo", branch = "delegate/r1-solo", mergeIntoBase = false)
    val gateRepo = mkRepo(tempRoot / "r1-solo-gate", branch = "delegate/r1-solo-gate", mergeIntoBase = false)
    // 闸类死结形态（方向闸）：逐拍重试的现存载体。
    os.proc("git", "-C", gateRepo.toString, "checkout", "delegate/r1-solo-gate")
      .call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    installNotices()
    installOwnerGonePort()
    val system = ActorSystem(s"mr1solo-${scala.util.Random.nextInt(100000)}")
    val id = "r1-solo"
    val gate = memberOf("r1-solo-gate", gateRepo, readyAt = 100L, projectKey)
    for
      _ <- MergeQueue.enqueuePendingIO(gate)
      _ <- IO(MergeQueue.pump(projectKey)) // 第一拍：被闸堵
      first <- IO(MergeQueue.find("r1-solo-gate"))
      _ <- IO(MergeQueue.pump(projectKey)) // 第二拍：后面没人 ⇒ 不空转跳过，照常再试
      second <- IO(MergeQueue.find("r1-solo-gate"))
      rows <- IO(chainRows("grant-blocked"))
      // 对照腿：同形账面但**收件会话已删** ⇒ 一次 owner-gone，不重试。放它自己的一条
      // 道（闸类队头不 yield，同一项目里的后位成员本来就不该被捞起来——那是「不让位」
      // 的语义；owner-gone 面单独验）。
      _ <- IO(goneSessions += id)
      _ <- MergeQueue.enqueuePendingIO(memberOf(id, repo, readyAt = 300L, "p-r1-solo-gone"))
      _ <- IO(MergeQueue.pump("p-r1-solo-gone"))
      dead <- IO(MergeQueue.find(id))
      rowsAfter <- IO(chainRows("grant-blocked"))
      _ <- IO(MergeQueue.pump(projectKey))
      deadAgain <- IO(MergeQueue.find(id))
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(first.map(_.blockedRounds), Some(1))
      assertEquals(second.map(_.blockedRounds), Some(2), "a solo gate-blocked member is retried every tick — no skip window, no idling")
      assertEquals(second.flatMap(_.skipUntilMs), None, "no skip window is armed when there is nobody behind to hand the window to")
      assertEquals(rows.size, 2, "each retry is audited exactly once (never silent)")
      assertEquals(grants.toList, Nil)
      // P1：不可投递成员一次即成终态，绝不进退避跑道（旧「无通道死结逐拍重试」形态退役）。
      assertEquals(
        dead.map(_.state),
        Some(MergeQueue.State.OwnerGone),
        "an undeliverable member closes owner-gone on its first attempt (no retry ladder exists for it)"
      )
      assertEquals(
        ownerGoneEscalations.map(_._1).toList,
        List(id),
        "its owner-gone escalates exactly once"
      )
      assertEquals(deadAgain.map(_.state), Some(MergeQueue.State.OwnerGone), "further pumps never reopen or re-close it")
      assertEquals(rowsAfter.size, rows.size, "the owner-gone member adds no grant-blocked rows (it is not a 'blocked' shape)")
  }
end MergeQueueHeadSkipFactCheckSpec
