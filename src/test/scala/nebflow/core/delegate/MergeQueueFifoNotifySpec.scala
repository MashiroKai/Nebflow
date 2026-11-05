package nebflow.core.delegate

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, AgentCommand, AgentEvent}
import nebflow.core.tools.{DelegateReportTool, ToolContext}
import nebflow.shared.PathUtil

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*

/**
 * merge-fifo 批（2026-11-03 · 作者报障「两个会话都已经报了 merge，为什么还没收到
 * 通知？为什么的 merge 排队机制到底是怎么设计的，我想的就是一种很简单的，fifo
 * 结构」）—— **入队强保证 + 四类通知** 判读。
 *
 * 三组锚点：
 *   - **A（入队强保证）**：申报点预记账（report(merge) 即入队）+ 核实入队的失败腿
 *     （`enqueueVerifiedIO` 核不到开放条目 ⇒ `enqueue-failed` 必通知、绝不抛）；
 *   - **B（FIFO 排空序）**：`readyAt` 是唯一排序键——反序注入 300/100/200，
 *     放行序必须 100/200/300（「简单 fifo」的可观测承诺）；
 *   - **C（四类通知）**：`merged`（队列侧异步关账必须宣布）/ 合并轮腿零通知
 *     （18:14 矩阵裁定「不许双通知」）/ `failed` 原因全文。
 *
 * 夹具：真 git 仓（账实核对/净空闸的真 git 面）+ 真 actor（Sink 收 UserInput）。
 */
class MergeQueueFifoNotifySpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 90.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-merge-fifo-notify"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  removeRetry(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  /** Windows 只读位 flake 清理（MergeQueueSpec 同款）。 */
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

  override def afterEach(context: munit.AfterEach): Unit =
    MergeQueue.reset()
    nebflow.core.delegate.MergeQueueTestPorts.install()
    MergeQueue.OwnerGonePort.clear()
    MergeQueue.BlockNotifyPort.clear()

  // ── 夹具 ──────────────────────────────────────────────────────────────

  private def mkRepo(dir: os.Path, branch: String, merge: Boolean): os.Path =
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
    if merge then g("merge", "--no-ff", branch, "-m", "merge the branch")
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
    // P1 投递身份化：投递只认收件身份 + 落盘邮箱，spec 直接在端口上收走 brief
    // （不再探 actor 信箱）；Sink actor 保留仅为既有 ref 形参。
    nebflow.core.delegate.MergeQueueTestPorts.recording(s => grants += s.brief)
    system.spawn(new Sink(grants), name)

  /** 全量放行注入文本（B1 的排空序读数）。 */
  private val grants: ListBuffer[String] = ListBuffer.empty

  private val blockNotices = ListBuffer.empty[(String, String)]

  private def deadSup(system: ActorSystem, name: String): IO[ActorRef[AgentEvent]] =
    system.spawn(
      new nebflow.actor.Behavior[AgentEvent]:
        def receive(ctx: nebflow.actor.ActorContext[AgentEvent], msg: AgentEvent): IO[nebflow.actor.Behavior[AgentEvent]] =
          IO.pure(this)
      ,
      name
    )

  private def member(
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
      // 宣告闸批 2026-11-04：属主宣告在案（`delegate_report(category="merge")`）——授窗前提
      declaredAt = Some(1_000L),
      declareSource = Some(nebflow.core.delegate.MergeDeclaration.Source.ReportMerge),
      channelKind = "identity",
    )

  private def chainLines(action: String): List[ChainStore.Entry] =
    ChainStore.listSync().flatMap(_.entries).filter(_.kind == MergeQueue.EntryKind).filter { e =>
      e.payload.hcursor.get[String]("action").toOption.contains(action)
    }

  private def notices: ListBuffer[(String, String)] = blockNotices

  private def installNotices(): Unit =
    blockNotices.clear()
    MergeQueue.BlockNotifyPort.install((m, kind, reason) => IO { blockNotices += ((kind, reason)) })

  private def seedRecord(id: String, repo: os.Path, baseBranch: String = "main"): Unit =
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = id,
        executor = "nebflow",
        cwd = repo.toString,
        project = None,
        taskId = Some("31"),
        parentSessionId = "nebula-root",
        title = "seeded for merge",
        createdAt = 1L,
        branch = Some(s"delegate/$id"),
        baseBranch = Some(baseBranch),
        projectWorkspace = Some(repo.toString)
      )
    )

  private def report(ctxSessionId: String)(input: (String, Json)*): IO[Either[String, String]] =
    DelegateReportTool
      .call(
        Json.obj(input.toList*).asObject.get,
        ToolContext(projectRoot = tempRoot.toString, sessionId = Some(ctxSessionId))
      )
      .map(_.left.map(_.message))

  // ══════════════════════════════════════════════════════════════════════
  // A —— 入队强保证
  // ══════════════════════════════════════════════════════════════════════

  test("A1 申报点预记账：delegate_report(merge) 落槽即入队（终态腿没跑成也不会有零台账行）+ 回执显式 CONFIRMED") {
    val repo = mkRepo(tempRoot / "a1-repo", branch = "delegate/delegate-a1", merge = false)
    seedRecord("delegate-a1", repo)
    installNotices()
    for
      res <- report("delegate-a1")(("category" -> "merge".asJson), ("detail" -> "branch carries the docs pass".asJson))
      entry = MergeQueue.find("delegate-a1")
      chain = chainLines("enqueued-pending")
    yield
      assert(res.isRight, s"a legal merge report must succeed, got: $res")
      assert(
        res.getOrElse("").contains("Merge queue entry CONFIRMED"),
        s"the confirm line must state the queue entry is in place, got: ${res.getOrElse("").take(240)}"
      )
      assert(entry.isDefined, "the report MUST have pre-booked a queue membership (no more zero-ledger-row reports)")
      assertEquals(entry.map(_.state), Some(MergeQueue.State.Waiting), "the pre-booked member sits waiting (no grant before the turn ends)")
      assertEquals(entry.map(_.branch), Some("delegate/delegate-a1"))
      assertEquals(entry.map(_.baseBranch), Some("main"))
      assertEquals(chain.size, 1, "the pre-booking is audited as enqueued-pending")
      assertEquals(blockNotices.size, 0, "pre-booking never notifies (it is not a state change on the author face)")
      assertEquals(grants.toList, Nil, "pre-booking never pumps (the grant belongs to the turn-end leg / care tick)")
    end for
  }

  test("A2 零猜：ensurePending 只认 branch ∧ baseBranch 两锚（缺锚 = false，绝不猜 main；无记录 = false）") {
    val repo = mkRepo(tempRoot / "a2-repo", branch = "delegate/delegate-a2", merge = false)
    // ① 无 registry 记录
    // ② 记录缺 baseBranch（历史盘面形态）
    // ③ 记录缺 branch
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = "delegate-a2-nobase",
        executor = "nebflow",
        cwd = repo.toString,
        project = None,
        taskId = None,
        parentSessionId = "nebula-root",
        title = "no base anchor",
        createdAt = 1L,
        branch = Some("delegate/delegate-a2-nobase")
      )
    )
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = "delegate-a2-nobranch",
        executor = "nebflow",
        cwd = repo.toString,
        project = None,
        taskId = None,
        parentSessionId = "nebula-root",
        title = "no branch anchor",
        createdAt = 1L,
        baseBranch = Some("main")
      )
    )
    seedRecord("delegate-a2-ok", repo)
    for
      ghost <- MergeEnqueue.ensurePending("delegate-a2-ghost", 1_000L)
      noBase <- MergeEnqueue.ensurePending("delegate-a2-nobase", 1_000L)
      noBranch <- MergeEnqueue.ensurePending("delegate-a2-nobranch", 1_000L)
      ok <- MergeEnqueue.ensurePending("delegate-a2-ok", 1_000L)
      okAgain <- MergeEnqueue.ensurePending("delegate-a2-ok", 2_000L)
      entry = MergeQueue.find("delegate-a2-ok")
    yield
      assertEquals(ghost, false, "no registry record ⇒ nothing to book")
      assertEquals(noBase, false, "missing baseBranch ⇒ no booking (the engine never guesses 'main')")
      assertEquals(noBranch, false, "missing branch ⇒ no booking")
      assertEquals(ok, true, "both anchors present ⇒ the member is booked")
      assertEquals(okAgain, true, "re-reporting is idempotent (still open)")
      assertEquals(entry.map(_.readyAt), Some(1_000L), "the first readyAt is the FIFO rank and is NOT rewritten by a re-report")
    end for
  }

  test("A3 核实入队的失败腿：账本核不到开放条目 ⇒ 返回 false + enqueue-failed 必通知，绝不抛") {
    val repo = mkRepo(tempRoot / "a3-repo", branch = "delegate/delegate-a3", merge = false)
    installNotices()
    val system = ActorSystem(s"mf-a3-${scala.util.Random.nextInt(100000)}")
    for
      m <- member(system, "delegate-a3", repo, readyAt = 5L, projectKey = "p-a3")
      // 一条自带终态状态的成员：入队后账本里没有**开放**条目（核实必失败——
      // 这正是「入队腿没落地」的最坏形态，A3 钉它的可观测性）。
      closedState = m.copy(state = MergeQueue.State.Merged)
      verified <- MergeQueue.enqueueVerifiedIO(closedState)
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(verified, false, "no OPEN ledger entry after enqueue ⇒ the verification leg reports false")
      val failures = blockNotices.toList.filter(_._1 == "enqueue-failed")
      assertEquals(failures.size, 1, s"exactly one enqueue-failed notice, got: ${blockNotices.toList.map(_._1)}")
      assert(
        failures.head._2.contains("delegate-a3") && failures.head._2.contains("NOT scheduled"),
        s"the notice names the delegate and the consequence (never silently un-queued): ${failures.head._2.take(200)}"
      )
      assertEquals(chainLines("enqueued").size, 1, "the enqueue attempt itself is still audited")
    end for
  }

  test("A4 核实入队的好腿：开放条目核到 ⇒ 返回 true 且零通知") {
    val repo = mkRepo(tempRoot / "a4-repo", branch = "delegate/delegate-a4", merge = false)
    installNotices()
    val system = ActorSystem(s"mf-a4-${scala.util.Random.nextInt(100000)}")
    for
      m <- member(system, "delegate-a4", repo, readyAt = 7L, projectKey = "p-a4")
      verified <- MergeQueue.enqueueVerifiedIO(m)
      entry = MergeQueue.find("delegate-a4")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(verified, true, "an open ledger entry verifies the enqueue")
      assertEquals(entry.map(_.state), Some(MergeQueue.State.TurnGranted), "the verified enqueue grants the (single) window")
      assertEquals(blockNotices.toList, Nil, "a successful verified enqueue issues no failure notice")
    end for
  }

  // ══════════════════════════════════════════════════════════════════════
  // B —— FIFO 排空序（「简单 fifo」的可观测承诺）
  // ══════════════════════════════════════════════════════════════════════

  test("B1 排空序：readyAt 300/100/200 反序注入 ⇒ 放行序必须 100 → 200 → 300（FIFO = 完成时间序，不是入队序）") {
    val repo = mkRepo(tempRoot / "b1-repo", branch = "delegate/delegate-b1", merge = false)
    installNotices()
    grants.clear()
    val system = ActorSystem(s"mf-b1-${scala.util.Random.nextInt(100000)}")
    for
      late <- member(system, "delegate-b1-300", repo, readyAt = 300L, projectKey = "p-b1")
      early <- member(system, "delegate-b1-100", repo, readyAt = 100L, projectKey = "p-b1")
      mid <- member(system, "delegate-b1-200", repo, readyAt = 200L, projectKey = "p-b1")
      _ <- MergeQueue.enqueuePendingIO(late) // 反序注入（后完成者先到）
      _ <- MergeQueue.enqueuePendingIO(early)
      _ <- MergeQueue.enqueuePendingIO(mid)
      beforePump <- IO(grants.toList)
      _ <- IO { MergeQueue.pump("p-b1") }
      _ <- IO.sleep(80.millis)
      first <- IO(grants.toList)
      _ <- IO { MergeQueue.onTerminal("delegate-b1-100", Some(MergeQueue.RoundOutcome.Merged)) }
      _ <- IO.sleep(80.millis)
      second <- IO(grants.toList)
      _ <- IO { MergeQueue.onTerminal("delegate-b1-200", Some(MergeQueue.RoundOutcome.Merged)) }
      _ <- IO.sleep(80.millis)
      third <- IO(grants.toList)
      states = List("delegate-b1-100", "delegate-b1-200", "delegate-b1-300").map(id => id -> MergeQueue.find(id).map(_.state))
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(beforePump, Nil, "pending enqueue never pumps (the report point does not steal the window)")
      assertEquals(first.size, 1, "single holder: exactly one grant per window")
      assert(first.head.contains("delegate/b1-100") || first.head.contains("delegate-b1-100"), s"the earliest finisher is granted FIRST, got: ${first.head.take(120)}")
      assertEquals(second.size, 2, "closing the holder pumps the next member")
      assert(second(1).contains("delegate-b1-200"), s"the second grant is readyAt 200, got: ${second(1).take(120)}")
      assertEquals(third.size, 3, "closing the second pumps the last member")
      assert(third(2).contains("delegate-b1-300"), s"the third grant is readyAt 300 (insertion order irrelevant), got: ${third(2).take(120)}")
      assertEquals(
        states,
        List(
          "delegate-b1-100" -> Some(MergeQueue.State.Merged),
          "delegate-b1-200" -> Some(MergeQueue.State.Merged),
          "delegate-b1-300" -> Some(MergeQueue.State.TurnGranted)
        ),
        "the ledger records the drain order"
      )
      assertEquals(chainLines("granted").size, 3, "three grants are audited")
    end for
  }

  // ══════════════════════════════════════════════════════════════════════
  // C —— 四类通知
  // ══════════════════════════════════════════════════════════════════════

  test("C1 merged 必通知：队列侧异步关账（放行前账实核对命中已并）⇒ BlockNotifyPort kind=merged（落地也是状态变化）") {
    val repo = mkRepo(tempRoot / "c1-repo", branch = "delegate/delegate-c1", merge = true) // 分支已并入 main
    installNotices()
    grants.clear()
    val system = ActorSystem(s"mf-c1-${scala.util.Random.nextInt(100000)}")
    for
      m <- member(system, "delegate-c1", repo, readyAt = 1L, projectKey = "p-c1")
      _ <- IO { MergeQueue.enqueue(m) }
      _ <- IO.sleep(120.millis)
      state = MergeQueue.find("delegate-c1").map(_.state)
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(state, Some(MergeQueue.State.Merged), "the already-merged member closes as merged at grant time")
      val merged = blockNotices.toList.filter(_._1 == "merged")
      assertEquals(merged.size, 1, s"exactly one merged notice, got: ${blockNotices.toList.map(_._1)}")
      assert(
        merged.head._2.contains("already merged"),
        s"the merged notice carries the fact-check reason: ${merged.head._2.take(200)}"
      )
    end for
  }

  test("C2 不许双通知：属主合并轮终态（onTerminal Merged）⇒ 队列侧零通知（父通知由属主自己的 Release 承担）") {
    val repo = mkRepo(tempRoot / "c2-repo", branch = "delegate/delegate-c2", merge = false)
    installNotices()
    grants.clear()
    val system = ActorSystem(s"mf-c2-${scala.util.Random.nextInt(100000)}")
    for
      m <- member(system, "delegate-c2", repo, readyAt = 1L, projectKey = "p-c2")
      _ <- IO { MergeQueue.enqueue(m) }
      _ <- IO.sleep(100.millis)
      grantedNotices = blockNotices.toList
      _ <- IO { MergeQueue.onTerminal("delegate-c2", Some(MergeQueue.RoundOutcome.Merged)) }
      _ <- IO.sleep(80.millis)
      state = MergeQueue.find("delegate-c2").map(_.state)
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(grantedNotices, Nil, "a clean grant notifies nobody on the block face")
      assertEquals(state, Some(MergeQueue.State.Merged), "the round terminal closes the membership as merged")
      assertEquals(
        blockNotices.toList,
        Nil,
        "the owner's own terminal Release is the parent notice — the queue MUST NOT stack a second 'merged' notice (2026-11-03 18:14 matrix)"
      )
      assertEquals(chainLines("merge-merged").size, 1, "the close-out is still audited")
    end for
  }

  test("C3 failed 必通知：合并轮被拒 ⇒ kind=failed 且原因原文逐字随行（无重试无 park）") {
    val repo = mkRepo(tempRoot / "c3-repo", branch = "delegate/delegate-c3", merge = false)
    installNotices()
    grants.clear()
    val system = ActorSystem(s"mf-c3-${scala.util.Random.nextInt(100000)}")
    val reason = "CONFLICT (content): Merge conflict in src/main/scala/nebflow/core/delegate/MergeQueue.scala — resolve in place"
    for
      m <- member(system, "delegate-c3", repo, readyAt = 1L, projectKey = "p-c3")
      _ <- IO { MergeQueue.enqueue(m) }
      _ <- IO.sleep(100.millis)
      _ <- IO { MergeQueue.onTerminal("delegate-c3", Some(MergeQueue.RoundOutcome.Refused(reason))) }
      _ <- IO.sleep(80.millis)
      state = MergeQueue.find("delegate-c3").map(_.state)
      failed = blockNotices.toList.filter(_._1 == "failed")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(state, Some(MergeQueue.State.Failed), "a refused round closes as failed")
      assertEquals(failed.size, 1, s"exactly one failed notice, got: ${blockNotices.toList.map(_._1)}")
      assertEquals(failed.head._2, reason, "the reason rides verbatim (no truncation, no rewrite)")
    end for
  }

end MergeQueueFifoNotifySpec
