package nebflow.core.delegate

import cats.effect.IO
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, AgentRecord, ActorSystem, AgentCommand, AgentEvent, AgentKind, AgentStatus}
import nebflow.shared.{Defaults, PathUtil}

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*
import scala.util.Try

/**
 * merge-fifo 批（2026-11-03 · 作者报障「两个会话都已经报了 merge，为什么还没收到
 * 通知」）—— 合并队列**活性守卫**判读（[[MergeEnqueueWatch]]）。
 *
 *   - **D1 纯矩阵**：`shouldProxy` 七维真值表（Idle / !frozen / !cancelledOrArchived /
 *     ahead>0 / !hasOpenEntry / idle≥threshold / taskRow ∈ {running,restarting}）——
 *     每一维单独翻假都必须让判据为 false（防止「任一条就代办」的误伤面）；
 *   - **D2 阈值边界**：999 vs 1000（`>=` 而非 `>`）；
 *   - **D3 代办锚**：`readyAt = lastActivityMs`（不是扫描时刻）+ `watch-queued` 通知文案；
 *   - **D4 幂等**：账本已有开放条目 ⇒ 扫两拍零重复入队、零重复通知。
 *
 * 假端口驱动（不碰真注册表/真任务行）；git 面用真仓（projectKey / 成员构造走真路径）。
 */
class MergeEnqueueWatchSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 90.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-merge-enqueue-watch"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  removeRetry(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

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
    blockNotices.clear()
    removeRetry(tempRoot / "chains")
    removeRetry(tempRoot / "merge-channel")

  override def afterEach(context: munit.AfterEach): Unit =
    MergeQueue.reset()
    MergeQueue.OwnerGonePort.clear()
    MergeQueue.BlockNotifyPort.clear()

  private val blockNotices = ListBuffer.empty[(String, String)]

  private def installNotices(): Unit =
    blockNotices.clear()
    MergeQueue.BlockNotifyPort.install((m, kind, reason) => IO { blockNotices += ((kind, reason)) })

  private def mkRepo(dir: os.Path, branch: String): os.Path =
    removeRetry(dir)
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

  private def recOf(id: String, repo: os.Path): DelegateRegistry.Record =
    DelegateRegistry.Record(
      id = id,
      executor = "nebflow",
      cwd = repo.toString,
      project = None,
      taskId = Some("31"),
      parentSessionId = "nebula-root",
      title = s"task $id",
      createdAt = 1L,
      branch = Some(s"delegate/$id"),
      baseBranch = Some("main"),
      projectWorkspace = Some(repo.toString)
    )

  /** D1/D2 的纯候选（只喂 shouldProxy，零 IO）。 */
  private def cand(
    status: Option[AgentStatus] = Some(AgentStatus.Idle),
    frozen: Boolean = false,
    cancelledOrArchived: Boolean = false,
    aheadOfBase: Option[Int] = Some(1),
    hasOpenEntry: Boolean = false,
    idleMs: Long = 600_000L,
    lastActivityMs: Long = 1_000L,
    taskRow: Option[String] = Some("running"),
    // 宣告闸批 2026-11-04 第八维：属主完成宣告（缺省 true = 旧七维矩阵的语境不变）
    declared: Boolean = true
  ): MergeEnqueueWatch.Candidate =
    MergeEnqueueWatch.Candidate(
      delegateId = "delegate-w1",
      status = status,
      frozen = frozen,
      cancelledOrArchived = cancelledOrArchived,
      aheadOfBase = aheadOfBase,
      hasOpenEntry = hasOpenEntry,
      idleMs = idleMs,
      lastActivityMs = lastActivityMs,
      taskRow = taskRow,
      declared = declared
    )

  // ══════════════════════════════════════════════════════════════════════
  // D1 —— 纯矩阵（七维真值表）
  // ══════════════════════════════════════════════════════════════════════

  test("D1 七维真值表：全真才代办；任一维翻假都必须零动作") {
    val t = Defaults.MergeEnqueueWatchIdleSec * 1000L
    val base = cand(idleMs = t)
    // 全真 ⇒ 代办
    assert(MergeEnqueueWatch.shouldProxy(base, t), "all seven dimensions true ⇒ proxy")
    // 逐维翻假
    assert(!MergeEnqueueWatch.shouldProxy(base.copy(status = Some(AgentStatus.Processing)), t), "Processing = a turn is running ⇒ hands off")
    assert(!MergeEnqueueWatch.shouldProxy(base.copy(status = Some(AgentStatus.Waiting)), t), "Waiting (explicit dock) ⇒ hands off")
    assert(!MergeEnqueueWatch.shouldProxy(base.copy(status = None), t), "not in the live registry ⇒ hands off")
    assert(!MergeEnqueueWatch.shouldProxy(base.copy(frozen = true), t), "frozen is a deliberate stop ⇒ never book it")
    assert(!MergeEnqueueWatch.shouldProxy(base.copy(cancelledOrArchived = true), t), "cancelled/archived = already written off ⇒ never revive")
    assert(!MergeEnqueueWatch.shouldProxy(base.copy(aheadOfBase = Some(0)), t), "nothing unmerged on the branch ⇒ nothing to queue")
    assert(!MergeEnqueueWatch.shouldProxy(base.copy(aheadOfBase = None), t), "unreadable ahead ⇒ hands off (no guessing)")
    assert(!MergeEnqueueWatch.shouldProxy(base.copy(hasOpenEntry = true), t), "already queued ⇒ idempotent zero-action")
    assert(!MergeEnqueueWatch.shouldProxy(base.copy(idleMs = t - 1), t), "not silent long enough ⇒ hands off")
    assert(!MergeEnqueueWatch.shouldProxy(base.copy(taskRow = Some("completed")), t), "task row completed ⇒ already accounted for")
    assert(!MergeEnqueueWatch.shouldProxy(base.copy(taskRow = Some("cancelled")), t), "task row cancelled ⇒ already accounted for")
    assert(!MergeEnqueueWatch.shouldProxy(base.copy(taskRow = Some("ready-to-merge")), t), "ready-to-merge row ⇒ the dock leg already ran")
    assert(!MergeEnqueueWatch.shouldProxy(base.copy(taskRow = None), t), "no task row ⇒ hands off")
    // restarting 也算活着
    assert(MergeEnqueueWatch.shouldProxy(base.copy(taskRow = Some("restarting")), t), "task row restarting still counts as alive")
  }

  // ══════════════════════════════════════════════════════════════════════
  // D2 —— 阈值边界 999 / 1000
  // ══════════════════════════════════════════════════════════════════════

  test("D1b 宣告维（第八维）：属主未宣告 ⇒ 绝不代办入队；同一候选改发 undeclared-not-queued 告警") {
    val t = Defaults.MergeEnqueueWatchIdleSec * 1000L
    val undeclared = cand(idleMs = t, declared = false)
    assertEquals(MergeEnqueueWatch.shouldProxy(undeclared, t), false, "no owner declaration ⇒ never proxied (a branch with commits is not a finished batch)")
    assertEquals(MergeEnqueueWatch.shouldAlertUndeclared(undeclared, t), true, "it becomes a VISIBLE alert instead")
    val declaredCand = cand(idleMs = t, declared = true)
    assertEquals(MergeEnqueueWatch.shouldProxy(declaredCand, t), true, "declared ⇒ proxied as before")
    assertEquals(MergeEnqueueWatch.shouldAlertUndeclared(declaredCand, t), false, "a declared member is not an alert case")
  }

  test("D2 阈值边界：idle=999 不代办、idle=1000 代办（>= 而非 >）；缺省阈值 = Defaults.MergeEnqueueWatchIdleSec=600s") {
    val t = 1_000L
    assert(!MergeEnqueueWatch.shouldProxy(cand(idleMs = 999L), t), "999ms < 1000ms ⇒ not yet")
    assert(MergeEnqueueWatch.shouldProxy(cand(idleMs = 1_000L), t), "1000ms >= 1000ms ⇒ proxy")
    assertEquals(Defaults.MergeEnqueueWatchIdleSec, 600L, "the default silent threshold is 10 minutes")
    // 系统属性缺省时不设 ⇒ 回落 Defaults
    assertEquals(MergeEnqueueWatch.idleThresholdMs, 600_000L, "without the system property the default threshold applies")
  }

  // ══════════════════════════════════════════════════════════════════════
  // D3 —— 代办锚 + 通知文案
  // ══════════════════════════════════════════════════════════════════════

  test("D3 代办锚：readyAt = lastActivityMs（不是扫描时刻）+ watch-queued 通知点名漏排事实") {
    val repo = mkRepo(tempRoot / "d3-repo", branch = "delegate/delegate-d3")
    val id = "delegate-d3"
    val rec = recOf(id, repo)
    val nowMs = 9_000_000L
    val lastActivity = 8_000_000L // 「它什么时候干完的」——readyAt 必须锚在这里
    installNotices()
    val system = ActorSystem(s"mw-d3-${scala.util.Random.nextInt(100000)}")
    for
      child <- system.spawn(
        new nebflow.actor.Behavior[AgentCommand]:
          def receive(ctx: nebflow.actor.ActorContext[AgentCommand], msg: AgentCommand): IO[nebflow.actor.Behavior[AgentCommand]] =
            IO.pure(this)
        ,
        "d3-child"
      )
      sup <- system.spawn(
        new nebflow.actor.Behavior[AgentEvent]:
          def receive(ctx: nebflow.actor.ActorContext[AgentEvent], msg: AgentEvent): IO[nebflow.actor.Behavior[AgentEvent]] =
            IO.pure(this)
        ,
        "d3-sup"
      )
      live = AgentRecord(
        sessionId = id,
        ref = child,
        kind = AgentKind.Delegate,
        rootSessionId = "nebula-root",
        status = AgentStatus.Idle,
        lastActivityMs = lastActivity,
        supervisorRef = Some(sup)
      )
      c = cand(idleMs = nowMs - lastActivity, lastActivityMs = lastActivity)
      _ <- MergeEnqueueWatch.queueNow(rec, c, Some(live))
      entry = MergeQueue.find(id)
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assert(entry.isDefined, "the orphaned member is booked")
      assertEquals(entry.map(_.readyAt), Some(lastActivity), "readyAt anchors on lastActivityMs (NOT the scan time — the FIFO rank must reflect when it finished)")
      assertEquals(entry.map(_.state), Some(MergeQueue.State.Waiting), "the watch only pre-books (the grant belongs to the care tick)")
      assertEquals(entry.map(_.ownerSession), Some(rec.id), "P1：成员的收件身份 = 属主会话本身（投递走落盘邮箱，不再挂 ref 闭包）")
      val queued = blockNotices.toList.filter(_._1 == MergeEnqueueWatch.NotifyKind)
      assertEquals(queued.size, 1, s"exactly one watch-queued notice, got: ${blockNotices.toList.map(_._1)}")
      assertEquals(MergeEnqueueWatch.NotifyKind, "watch-queued", "the notice kind is pinned (SharedResources framing key)")
      assert(
        queued.head._2.contains("no report/terminal leg ever enqueued it"),
        s"the notice states the orphan fact: ${queued.head._2.take(220)}"
      )
      assert(queued.head._2.contains("commit(s) ahead of the base branch"), s"the notice carries the ahead count: ${queued.head._2.take(220)}")
      assertEquals(entry.map(_.projectKey), Some(Try(os.Path(repo.toString)).map(nebflow.core.GitWorktrees.projectKeyOf).get), "the project key comes from the base workspace (per-project single window)")
    end for
  }

  test("D3b 零猜：无 branch/baseBranch 锚的漏网候选 ⇒ 不入队、零通知（引擎绝不猜 main）") {
    val repo = mkRepo(tempRoot / "d3b-repo", branch = "delegate/delegate-d3b")
    installNotices()
    val rec = recOf("delegate-d3b", repo).copy(baseBranch = None)
    for
      _ <- MergeEnqueueWatch.queueNow(rec, cand(), None)
      entry = MergeQueue.find("delegate-d3b")
    yield
      assertEquals(entry, None, "no anchor ⇒ no membership (never guess the merge target)")
      assertEquals(blockNotices.toList, Nil, "no notice is issued for a member that was never booked")
  }

  // ══════════════════════════════════════════════════════════════════════
  // D4 —— 幂等（账本开放条目是唯一闸）
  // ══════════════════════════════════════════════════════════════════════

  test("D4 幂等：账本已有开放条目 ⇒ 同一读数扫两拍零代办、零通知（hasOpenEntry 是唯一闸）") {
    val repo = mkRepo(tempRoot / "d4-repo", branch = "delegate/delegate-d4")
    val id = "delegate-d4"
    val rec = recOf(id, repo)
    installNotices()
    val nowMs = 5_000_000L
    val threshold = Defaults.MergeEnqueueWatchIdleSec * 1000L
    val proxied = ListBuffer.empty[String]
    val system = ActorSystem(s"mw-d4-${scala.util.Random.nextInt(100000)}")
    for
      child <- system.spawn(
        new nebflow.actor.Behavior[AgentCommand]:
          def receive(ctx: nebflow.actor.ActorContext[AgentCommand], msg: AgentCommand): IO[nebflow.actor.Behavior[AgentCommand]] =
            IO.pure(this)
        ,
        "d4-child"
      )
      // 账本里先摆一条开放成员（模拟「申报点已预记账 / 终态腿已入队」后的常态）
      _ <- IO {
        MergeQueue.seedMemberForTest(
          MergeQueue.Member(
            delegateId = id,
            branch = s"delegate/$id",
            baseWs = repo.toString,
            baseBranch = "main",
            worktree = false,
            projectKey = "p-d4",
            taskId = Some("31"),
            parentSessionId = "nebula-root",
            description = "already queued",
            readyAt = 42L,
            channelKind = "identity",
            // 宣告闸批 2026-11-04：属主宣告在案——授窗前提
            declaredAt = Some(1_000L),
            declareSource = Some(nebflow.core.delegate.MergeDeclaration.Source.ReportMerge)
          )
        )
      }
      live = Map(id -> AgentRecord(sessionId = id, ref = child, kind = AgentKind.Delegate, rootSessionId = "nebula-root", status = AgentStatus.Idle, lastActivityMs = 1_000_000L))
      port = new MergeEnqueueWatch.Port:
        def delegates = IO.pure(List(rec))
        def liveRecords = IO.pure(live)
        def taskRowStatuses = IO.pure(Map("31" -> "running")) // 键是 taskId（rec.taskId），不是 delegateId
        def commitsAhead(r: DelegateRegistry.Record): Option[Int] = Some(3)
        def hasOpenEntry(delegateId: String): Boolean =
          Try(MergeQueue.find(delegateId).exists(_.open)).getOrElse(false)
        def queue(r: DelegateRegistry.Record, c: MergeEnqueueWatch.Candidate, l: Option[AgentRecord]): IO[Unit] =
          IO { proxied += r.id } *> MergeEnqueueWatch.queueNow(r, c, l)
      first <- MergeEnqueueWatch.scan(port, nowMs, threshold)
      second <- MergeEnqueueWatch.scan(port, nowMs + 1_000L, threshold)
      entry = MergeQueue.find(id)
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(first, Nil, "an already-queued member is proxied NOTHING (every other dimension is true — hasOpenEntry is the only gate)")
      assertEquals(second, Nil, "and the next tick is equally inert")
      assertEquals(proxied.toList, Nil, "the queue leg never ran (no duplicate booking)")
      assertEquals(blockNotices.toList, Nil, "and nothing was announced")
      assertEquals(entry.map(_.readyAt), Some(42L), "the original FIFO rank is untouched")
    end for
  }

  test("D4b 幂等（真入队腿）：已代办的成员在下一拍不再被代办（hasOpenEntry 闸），零重复通知") {
    val repo = mkRepo(tempRoot / "d4b-repo", branch = "delegate/delegate-d4b")
    val id = "delegate-d4b"
    val rec = recOf(id, repo)
    installNotices()
    // 宣告闸批 2026-11-04：本腿测的是 hasOpenEntry 幂等面 ⇒ 属主宣告必须先满足。
    MergeDeclaration.write(id, nebflow.core.delegate.MergeDeclaration.Source.ReportMerge)
    val nowMs = 5_000_000L
    val threshold = Defaults.MergeEnqueueWatchIdleSec * 1000L
    val proxied = ListBuffer.empty[String]
    // 活体 Idle 行（假 ref 不参与判据——判据只看 status/lastActivityMs）
    val system = ActorSystem(s"mw-d4b-${scala.util.Random.nextInt(100000)}")
    for
      child <- system.spawn(
        new nebflow.actor.Behavior[AgentCommand]:
          def receive(ctx: nebflow.actor.ActorContext[AgentCommand], msg: AgentCommand): IO[nebflow.actor.Behavior[AgentCommand]] =
            IO.pure(this)
        ,
        "d4b-child"
      )
      live = Map(id -> AgentRecord(sessionId = id, ref = child, kind = AgentKind.Delegate, rootSessionId = "nebula-root", status = AgentStatus.Idle, lastActivityMs = 1_000_000L))
      port2 = new MergeEnqueueWatch.Port:
        def delegates = IO.pure(List(rec))
        def liveRecords = IO.pure(live)
        def taskRowStatuses = IO.pure(Map("31" -> "running")) // 键是 taskId（rec.taskId），不是 delegateId
        def commitsAhead(r: DelegateRegistry.Record): Option[Int] = Some(3)
        def hasOpenEntry(delegateId: String): Boolean =
          Try(MergeQueue.find(delegateId).exists(_.open)).getOrElse(false)
        def queue(r: DelegateRegistry.Record, c: MergeEnqueueWatch.Candidate, l: Option[AgentRecord]): IO[Unit] =
          IO { proxied += r.id } *> MergeEnqueueWatch.queueNow(r, c, l)
      portNoLive = new MergeEnqueueWatch.Port:
        def delegates = IO.pure(List(rec))
        def liveRecords = IO.pure(Map.empty[String, AgentRecord])
        def taskRowStatuses = IO.pure(Map("31" -> "running"))
        def commitsAhead(r: DelegateRegistry.Record): Option[Int] = Some(3)
        def hasOpenEntry(delegateId: String): Boolean =
          Try(MergeQueue.find(delegateId).exists(_.open)).getOrElse(false)
        def queue(r: DelegateRegistry.Record, c: MergeEnqueueWatch.Candidate, l: Option[AgentRecord]): IO[Unit] =
          IO { proxied += r.id } *> MergeEnqueueWatch.queueNow(r, c, l)
      first <- MergeEnqueueWatch.scan(port2, nowMs, threshold)
      _ <- MergeEnqueueWatch.scan(portNoLive, nowMs, threshold) // 无活体行 ⇒ 判据全假（零动作，验证不误伤）
      second <- MergeEnqueueWatch.scan(port2, nowMs + 1_000L, threshold)
      entry = MergeQueue.find(id)
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(first.map(_.delegateId), List(id), "the first tick books the orphan (Idle + ahead + running row + silent long enough)")
      assertEquals(second, Nil, "the second tick proxies NOTHING (the ledger already holds an open entry — idempotent)")
      assertEquals(proxied.toList, List(id), "the queue leg ran exactly once")
      assertEquals(entry.map(_.readyAt), Some(1_000_000L), "readyAt stays anchored on lastActivityMs (never rewritten)")
      val queued = blockNotices.toList.filter(_._1 == MergeEnqueueWatch.NotifyKind)
      assertEquals(queued.size, 1, "exactly one watch-queued notice across both ticks (no repeat announcements)")
    end for
  }

end MergeEnqueueWatchSpec
