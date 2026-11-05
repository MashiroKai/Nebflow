package nebflow.core.delegate

import cats.effect.IO
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, AgentCommand, AgentEvent}
import nebflow.shared.PathUtil

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*

/**
 * 合并队列**宣告闸**（缺陷批：合并队列会把「明确压着不合的半成品分支」自动合进 main ·
 * 2026-11-04）。
 *
 * 病灶（见 [[MergeQueue]] / [[MergeEnqueueWatch]] 的入队判据）：队列的**授予判据**里
 * 没有任何一条问过「这批到底完成没完成」——`pump` 只核账实（已并）、道闸、净空闸、
 * 通道可达；于是**任何**进了账的成员（包括「分支上有提交」「idle 够久」「write-ahead
 * 通道有镜像」这些与完成无关的形态）都能拿到合并窗。属主失联时队列自己把账推到
 * 「可合」——owner 不在场、没人能被征询、队列自行决定。
 *
 * 本 spec 钉死四条硬判据（作者语义）：
 *   - A：**未宣告 = 不授窗**（分支有提交不算完成）；
 *   - B：**属主不在场 = 不授窗**（宁可留人处理 + 可见告警，绝不自动合）；
 *   - C：**半成品分支（文件里留着冲突标记 / 健康闸拒绝）= 不授窗 + 报原因**；
 *   - D（对照腿）：**属主明确宣告 = 正常授窗**。
 *
 * 判据实现 = [[MergeDeclaration]]（属主宣告账）+ [[MergeQueue.grant]] 的放行前三闸。
 */
class MergeQueueDeclarationGateSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-merge-declaration-gate"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  if os.exists(tempRoot) then os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  private val alerts = ListBuffer.empty[(String, String)]

  override def beforeEach(context: munit.BeforeEach): Unit =
    MergeQueue.reset()
    MergeDeclaration.reset()
    MergeHealthGate.clear()
    MergeQueue.clearDeclarationLookup()
    alerts.clear()

  override def afterEach(context: munit.AfterEach): Unit =
    MergeQueue.reset()
    MergeDeclaration.reset()
    MergeHealthGate.clear()
    nebflow.core.delegate.MergeQueueTestPorts.install()
    MergeQueue.OwnerGonePort.clear()
    MergeQueue.BlockNotifyPort.clear()
    MergeQueue.clearDeclarationLookup()

  // ── 夹具 ─────────────────────────────────────────────────────────────

  private def g(dir: os.Path, args: String*): os.CommandResult =
    os.proc(Seq("git", "-C", dir.toString) ++ args).call(check = true, stdout = os.Pipe, stderr = os.Pipe)

  /** 真 git 基座：main@init + 分支 delegate/<id>（可选：分支文件里留冲突标记 = 半成品）。 */
  private def mkRepo(dir: os.Path, delegateId: String, conflictMarkers: Boolean = false): os.Path =
    if os.exists(dir) then os.remove.all(dir)
    os.makeDir.all(dir)
    os.proc("git", "init", "-b", "main", dir.toString).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    g(dir, "config", "user.email", "spec@nebflow.local")
    g(dir, "config", "user.name", "spec")
    g(dir, "commit", "--allow-empty", "-m", "init")
    g(dir, "checkout", "-b", s"delegate/$delegateId")
    val body =
      if conflictMarkers then
        "<<<<<<< HEAD\nleft half\n=======\nright half\n>>>>>>> delegate/x\n"
      else "deliverable\n"
    os.write(dir / "deliverable.txt", body)
    g(dir, "add", ".")
    g(dir, "commit", "-m", "wip" )
    g(dir, "checkout", "main")
    dir

  private class Sink(records: ListBuffer[String]) extends nebflow.actor.Behavior[AgentCommand]:
    def receive(ctx: nebflow.actor.ActorContext[AgentCommand], msg: AgentCommand): IO[nebflow.actor.Behavior[AgentCommand]] =
      msg match
        case AgentCommand.UserInput(text, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
          IO { records += text; this }
        case _ => IO.pure(this)

  private def baseMember(delegateId: String, repo: os.Path, readyAt: Long): MergeQueue.Member =
    MergeQueue.Member(
      delegateId = delegateId,
      branch = s"delegate/$delegateId",
      baseWs = repo.toString,
      baseBranch = "main",
      worktree = true,
      projectKey = s"proj-$delegateId",
      taskId = Some("77"),
      parentSessionId = "nebula-root",
      description = s"task for $delegateId",
      readyAt = readyAt,
      channelKind = "identity"
    )

  /** 活体属主腿（内部 actor 注入通道可用）。 */
  private def withLiveOwner(
    system: ActorSystem,
    records: ListBuffer[String],
    m: MergeQueue.Member
  ): IO[MergeQueue.Member] =
    for
      child <- system.spawn(new Sink(records), s"child-${m.delegateId}-${scala.util.Random.nextInt(100000)}")
      sup <- system.spawn(
        new nebflow.actor.Behavior[AgentEvent]:
          def receive(ctx: nebflow.actor.ActorContext[AgentEvent], msg: AgentEvent): IO[nebflow.actor.Behavior[AgentEvent]] =
            IO.pure(this),
        s"sup-${m.delegateId}-${scala.util.Random.nextInt(100000)}"
      )
    yield
      // P1 投递身份化：端口转发到同一个 Sink（真实投递路径）。
      nebflow.core.delegate.MergeQueueTestPorts.toActor(child, sup)
      m.copy(ownerSessionId = m.delegateId)

  private def installAlertPort(): Unit =
    MergeQueue.BlockNotifyPort.install(new MergeQueue.BlockNotifyPort.Face:
      def deliver(m: MergeQueue.Member, kind: String, reason: String): IO[Unit] =
        IO { alerts += ((kind, reason)) }
    )

  private def chainActions(): List[String] =
    ChainStore.listSync().flatMap(_.entries).filter(_.kind == MergeQueue.EntryKind).flatMap { e =>
      e.payload.hcursor.get[String]("action").toOption
    }

  /** 属主宣告（唯一机械动作 `delegate_report(category="merge")` 的落账面）。 */
  private def declare(delegateId: String): Unit =
    assert(
      MergeDeclaration
        .write(delegateId, MergeDeclaration.Source.ReportMerge, Some(s"delegate/$delegateId"), Some("main"), None)
        .isDefined,
      "the owner declaration must be recorded"
    )

  // ── A：未宣告 = 不授窗（分支有提交不算完成） ─────────────────────────

  test("A: a member whose branch HAS commits but whose owner never declared 'done and mergeable' never receives a merge turn") {
    val system = ActorSystem(s"mq-decl-a-${scala.util.Random.nextInt(100000)}")
    val records = ListBuffer.empty[String]
    val repo = mkRepo(tempRoot / "repo-a", "d-undeclared")
    for
      _ <- IO(installAlertPort())
      base = baseMember("d-undeclared", repo, readyAt = 1_000L)
      m <- withLiveOwner(system, records, base)
      _ <- IO { MergeQueue.enqueue(m) } // pump 在 enqueue 内
      after <- IO(MergeQueue.find("d-undeclared"))
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(
        records.toList,
        Nil,
        "NO merge turn may be injected: 'the branch has commits' is not 'the batch is finished' — the owner never declared ready-to-merge"
      )
      assertEquals(after.map(_.state), Some("waiting"), "the membership stays open (parked), never turn-granted")
      assert(
        after.flatMap(_.needsHuman).isDefined,
        s"the queue must stop auto-granting this member (needsHuman marker), got: ${after.map(m => (m.state, m.needsHuman))}"
      )
      assert(
        alerts.exists((kind, _) => kind.contains("undeclared")),
        s"a visible alert names the real blocker (no owner declaration), got alerts: ${alerts.toList.map(_._1)}"
      )
      assert(
        !chainActions().contains("granted"),
        s"no grant may be audited, got: ${chainActions()}"
      )
  }

  // ── B：属主不在场 = 不授窗（默认不与） ───────────────────────────────

  test("B: an owner-gone member (no live delivery channel) is never granted a merge turn — parked with a visible alert instead") {
    val system = ActorSystem(s"mq-decl-b-${scala.util.Random.nextInt(100000)}")
    val records = ListBuffer.empty[String]
    val repo = mkRepo(tempRoot / "repo-b", "d-absent")
    for
      _ <- IO(installAlertPort())
      // 无 internal / external 腿 = 重启后通道丢失的形态（write-ahead 通道仍有镜像）
      m = baseMember("d-absent", repo, readyAt = 1_000L)
      _ <- IO { MergeQueue.enqueue(m) }
      after <- IO(MergeQueue.find("d-absent"))
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(records.toList, Nil, "no merge turn may be injected to a member whose owner is not present")
      assert(
        after.exists(_.open),
        s"the membership must stay OPEN (human handling), not silently closed as owner-gone/merged, got: ${after.map(_.state)}"
      )
      assert(
        after.flatMap(_.needsHuman).isDefined,
        s"owner-gone must park the member out of rotation (needsHuman), got: ${after.map(_.needsHuman)}"
      )
      assert(
        alerts.exists((kind, reason) => kind.contains("undeclared") && reason.contains("owner")),
        s"a visible alert must name the blocker (nobody declared, so the absent owner cannot be asked either), got: ${alerts.toList.map(_._1)}"
      )
  }

  test("B2: the idle-safety watch never queues a delegate whose owner never declared — it raises a visible undeclared alert instead") {
    val system = ActorSystem(s"mq-decl-b2-${scala.util.Random.nextInt(100000)}")
    val repo = mkRepo(tempRoot / "repo-b2", "d-watched")
    val queued = ListBuffer.empty[String]
    val notices = ListBuffer.empty[(String, String)]
    for
      _ <- IO {
        MergeQueue.BlockNotifyPort.install(new MergeQueue.BlockNotifyPort.Face:
          def deliver(m: MergeQueue.Member, kind: String, reason: String): IO[Unit] =
            IO { notices += ((kind, reason)) }
        )
      }
      _ <- IO(MergeQueue.reset())
      rec = DelegateRegistry.Record(
        id = "d-watched",
        executor = "nebflow",
        cwd = repo.toString,
        project = None,
        taskId = Some("78"),
        parentSessionId = "nebula-root",
        title = "watched task",
        createdAt = 1L,
        branch = Some("delegate/d-watched"),
        baseBranch = Some("main"),
        projectWorkspace = Some(repo.toString)
      )
      watchChild <- system.spawn(
        new nebflow.actor.Behavior[AgentCommand]:
          def receive(ctx: nebflow.actor.ActorContext[AgentCommand], msg: AgentCommand): IO[nebflow.actor.Behavior[AgentCommand]] =
            IO.pure(this),
        s"watch-child-${scala.util.Random.nextInt(100000)}"
      )
      port = new MergeEnqueueWatch.Port:
        def delegates = IO.pure(List(rec))
        def liveRecords = IO.pure(
          Map(
            "d-watched" -> nebflow.actor.AgentRecord(
              sessionId = "d-watched",
              ref = watchChild,
              kind = nebflow.actor.AgentKind.Delegate,
              rootSessionId = "nebula-root",
              status = nebflow.actor.AgentStatus.Idle,
              lastActivityMs = 1_000L
            )
          )
        )
        def taskRowStatuses = IO.pure(Map("78" -> "running"))
        def commitsAhead(r: DelegateRegistry.Record): Option[Int] = Some(3)
        def hasOpenEntry(delegateId: String): Boolean = false
        def queue(r: DelegateRegistry.Record, c: MergeEnqueueWatch.Candidate, live: Option[nebflow.actor.AgentRecord]): IO[Unit] =
          IO { queued += r.id }
      proxied <- MergeEnqueueWatch.scan(port, nowMs = 5_000_000L, thresholdMs = 600_000L)
      entry = MergeQueue.find("d-watched")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(proxied, Nil, "an undeclared delegate is NOT proxied into the merge queue (a branch with commits is not a finished batch)")
      assertEquals(queued.toList, Nil, "the queue leg never ran")
      assertEquals(entry, None, "no ledger membership was created for it")
      assert(
        notices.exists((kind, reason) =>
          kind == MergeEnqueueWatch.UndeclaredNotifyKind && reason.contains("NEVER DECLARED")
        ),
        s"a visible alert names the real blocker, got: ${notices.toList.map((k, r) => (k, r.take(90)))}"
      )
  }

  // ── C：半成品分支（冲突标记）= 不授窗 + 报原因 ───────────────────────

  test("C: a declared but half-finished branch (conflict markers left in its files) is refused by the health gate with the reason") {
    val system = ActorSystem(s"mq-decl-c-${scala.util.Random.nextInt(100000)}")
    val records = ListBuffer.empty[String]
    val repo = mkRepo(tempRoot / "repo-c", "d-half", conflictMarkers = true)
    for
      _ <- IO(installAlertPort())
      _ <- IO(declare("d-half")) // 属主宣告在案：让健康闸成为唯一拒绝面
      base = baseMember("d-half", repo, readyAt = 1_000L)
      m <- withLiveOwner(system, records, base)
      _ <- IO { MergeQueue.enqueue(m) }
      after <- IO(MergeQueue.find("d-half"))
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(records.toList, Nil, "a merge turn must not be injected for a branch that does not pass the health gate")
      assert(
        after.flatMap(_.needsHuman).exists(_.toLowerCase.contains("conflict")),
        s"the refusal reason must be visible on the member AND name the real blocker, got: ${after.map(_.needsHuman)}"
      )
      assert(
        alerts.exists((kind, reason) => kind.contains("health") && reason.toLowerCase.contains("conflict")),
        s"a visible alert must report the health refusal reason, got: ${alerts.toList.map((k, r) => (k, r.take(80)))}"
      )
      assert(after.exists(_.open), "the membership stays open (nothing merged, nothing closed)")
      assert(!chainActions().contains("granted"), "nothing may be audited as granted")
  }

  test("C2: a declared branch whose build is broken (health gate says the compile fails) is refused and the reason is reported") {
    val system = ActorSystem(s"mq-decl-c2-${scala.util.Random.nextInt(100000)}")
    val records = ListBuffer.empty[String]
    val repo = mkRepo(tempRoot / "repo-c2", "d-broken")
    for
      _ <- IO(installAlertPort())
      _ <- IO(declare("d-broken"))
      _ <- IO {
        MergeHealthGate.install(new MergeHealthGate.Face:
          def check(m: MergeQueue.Member): MergeHealthGate.Verdict =
            MergeHealthGate.Verdict.Refused("compile FAILED: src/Main.scala:12: ';' expected — the branch does not build")
        )
      }
      base = baseMember("d-broken", repo, readyAt = 1_000L)
      m <- withLiveOwner(system, records, base)
      _ <- IO { MergeQueue.enqueue(m) }
      after <- IO(MergeQueue.find("d-broken"))
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(records.toList, Nil, "a branch that does not compile must never receive a merge turn")
      assert(
        after.flatMap(_.needsHuman).exists(_.contains("compile FAILED")),
        s"the compile refusal reason must be reported verbatim, got: ${after.map(_.needsHuman)}"
      )
      assert(after.exists(_.open), "the membership stays open (nothing merged, nothing closed)")
  }

  // ── D（对照腿）：属主宣告 → 正常授窗 ─────────────────────────────────

  test("D: a declared member with a live owner IS granted the merge turn normally (the gate does not block healthy batches)") {
    val system = ActorSystem(s"mq-decl-d-${scala.util.Random.nextInt(100000)}")
    val records = ListBuffer.empty[String]
    val repo = mkRepo(tempRoot / "repo-d", "d-ready")
    for
      _ <- IO(installAlertPort())
      _ <- IO(declare("d-ready")) // 属主的显式宣告（delegate_report(category="merge")）
      base = baseMember("d-ready", repo, readyAt = 1_000L)
      m <- withLiveOwner(system, records, base)
      _ <- IO { MergeQueue.enqueue(m) }
      after <- IO(MergeQueue.find("d-ready"))
      grantedRows = ChainStore
        .listSync()
        .flatMap(_.entries)
        .filter(_.kind == MergeQueue.EntryKind)
        .filter(_.payload.hcursor.get[String]("action").toOption.contains("granted"))
        .map(_.payload)
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(records.toList.size, 1, s"the declared member must receive exactly one merge turn, got: ${records.toList.size}")
      assert(records.headOption.exists(_.contains("merge --no-ff")), "the turn must be the merge brief")
      assertEquals(after.map(_.state), Some("turn-granted"), "the member holds the merge window")
      assertEquals(after.flatMap(_.needsHuman), None, "no park marker on a healthy declared member")
      assert(grantedRows.nonEmpty, "the grant must be audited")
      // 可回溯记录（作者令 4）：判据 + 宣告来源 + 属主在场 + tip。
      val payload = grantedRows.head
      assertEquals(payload.hcursor.get[String]("criterion").toOption, Some("owner-declaration"), "the audit row names the criterion")
      assertEquals(payload.hcursor.get[Boolean]("ownerPresent").toOption, Some(true), "the audit row records owner presence")
      assert(payload.hcursor.get[String]("declareSource").toOption.contains(MergeDeclaration.Source.ReportMerge), "declare source on record")
      assert(payload.hcursor.get[String]("branchTip").toOption.exists(_.nonEmpty), "the branch tip at grant time is recorded")
  }

