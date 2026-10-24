package nebflow.agent

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import nebflow.actor.ActorSystem
import nebflow.core.delegate.{ChainStore, DelegateRegistry, MergeQueue}
import nebflow.core.GitWorktrees
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, SubAgentTask, StreamChunk}

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*
import scala.util.Try

/**
 * MergeQueueBootSweep 合并队列跨重启恢复判读（引擎加固批 2026-10-09，P2-5）：
 *
 *  - ① 账本判据（单点）：末行 action ∈ {enqueued, reminder-sent} = 中断成员
 *    （readyAt 取 payload 原值，升序）；末行 dequeued / inject-failed = 干净退出
 *    跳过；
 *  - ② 已并成员（祖先真）⇒ `recovered-merged` 行 + [[PostMergeCleanup]] 同守卫
 *    收残留现场（worktree 移除 + branch -d）+ 报告 alreadyMerged；
 *  - ③ 无可并物（支零领先）⇒ `recovered-dropped-empty` 行 + 报告 droppedEmpty；
 *  - ④ 可重挂内置腿（reviveInternal 假通道）⇒ MergeQueue 重挂 + readyAt 原值 +
 *    revive 收到合并 brief + 报告 reMounted；onTerminal 放窗；
 *  - ⑤ 内置腿无复活通道（reviveInternal=None）⇒ `recovery-blocked` 行 + 任务行
 *    翻 failed + 报告 blocked（挂不进诚实处置）。
 *
 * （父通知腿 = F2 注入队文件 correlationId 幂等，与本批 SubAgentStartupRecovery
 * 同机制；spec 未建父会话 ⇒ 通知静默跳过——本件不断言通知内容。）
 */
class MergeQueueBootSweepSpec extends FunSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-mq-boot-sweep"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    os.remove.all(tempRoot / "chains")
    os.remove.all(tempRoot / "delegates")
    os.remove.all(tempRoot / "subagent-tasks")

  private class SilentLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): fs2.Stream[IO, StreamChunk] =
      fs2.Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def ledgerRow(delegateId: String, action: String, readyAt: Long): Unit =
    ChainStore.appendSync(
      ChainStore.keyFor(None, delegateId),
      MergeQueue.EntryKind,
      Json.obj(
        "action" -> action.asJson,
        "delegateId" -> delegateId.asJson,
        "branch" -> s"delegate/$delegateId".asJson,
        "readyAt" -> readyAt.asJson
      )
    )

  private def ledgerActions(delegateId: String): List[String] =
    ChainStore.listSync().flatMap(_.entries).filter(_.kind == MergeQueue.EntryKind).flatMap { e =>
      val c = e.payload.hcursor
      c.get[String]("delegateId")
        .toOption
        .filter(_ == delegateId)
        .map(_ => c.get[String]("action").toOption.getOrElse(""))
    }

  private def seedRecord(
    id: String,
    repo: os.Path,
    executor: String = "nebflow",
    worktree: Option[os.Path] = None
  ): Unit =
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = id,
        executor = executor,
        cwd = worktree.map(_.toString).getOrElse(repo.toString),
        project = None,
        taskId = Some("99"),
        parentSessionId = "nebula-root",
        title = "survivor",
        createdAt = 1L,
        branch = Some(s"delegate/$id"),
        baseBranch = Some("main"),
        projectWorkspace = Some(repo.toString),
        worktree = worktree.map(_.toString)
      )
    )

  /** main@init + delegate 分支一笔交付提交；merge=true ⇒ 分支 --no-ff 并回 main。 */
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

  end mkRepo

  test(
    "① interruptedMembers: last row enqueued/reminder-sent = interrupted (readyAt from payload, ascending); dequeued/inject-failed = clean"
  ) {
    ledgerRow("mq-a", "enqueued", readyAt = 300L)
    ledgerRow("mq-b", "enqueued", readyAt = 100L)
    ledgerRow("mq-b", "dequeued", readyAt = 100L)
    ledgerRow("mq-c", "enqueued", readyAt = 200L)
    ledgerRow("mq-c", "reminder-sent", readyAt = 200L)
    ledgerRow("mq-d", "inject-failed", readyAt = 50L)
    val members = MergeQueueBootSweep.interruptedMembers()
    assertEquals(
      members.map(_.delegateId),
      List("mq-c", "mq-a"),
      "only the unfinished members survive, FIFO by readyAt"
    )
    assertEquals(members.map(_.readyAt), List(200L, 300L), "readyAt comes from the ledger payload original value")
  }

  test("② already-merged member ⇒ recovered-merged row + leftover scene cleaned under the zero-ahead guard + report") {
    val repo = mkRepo(tempRoot / "sweep-merged-repo", branch = "delegate/mq-merged", merge = true)
    val wt = tempRoot / "sweep-merged-wt"
    os.remove.all(wt) // 夹具幂等：上一轮残留的 kept 场景 wt 会撞 worktree add already exists
    os.proc("git", "-C", repo.toString, "worktree", "add", wt.toString, "delegate/mq-merged")
      .call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    seedRecord("mq-merged", repo, worktree = Some(wt))
    ledgerRow("mq-merged", "enqueued", readyAt = 111L)
    val system = ActorSystem(s"mqbs-2-${scala.util.Random.nextInt(100000)}")
    val (report, actions, cleanupActions) = (for
      res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
      report <- MergeQueueBootSweep.sweep(res)
      actions = ledgerActions("mq-merged")
      cleanup <- IO {
        ChainStore
          .listSync()
          .flatMap(_.entries)
          .filter(_.kind == "worktree-cleanup")
          .flatMap(_.payload.hcursor.get[String]("action").toOption)
      }
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield (report, actions, cleanup)).unsafeRunSync()
    assertEquals(report.alreadyMerged, List("mq-merged"), "the landed merge is recognized")
    assert(actions.contains("recovered-merged"), s"the recovered-merged row must be audited: $actions")
    assert(cleanupActions.contains("removed"), s"the leftover worktree scene must be cleaned: $cleanupActions")
    assert(!os.exists(wt), "the worktree directory must be gone after the sweep cleanup")
    assert(!GitWorktrees.branchExists(repo, "delegate/mq-merged"), "the merged branch must be deleted (branch -d)")
  }

  test("③ missing branch ref (nothing mergeable) ⇒ recovered-dropped-empty + report droppedEmpty") {
    val repo = mkRepo(tempRoot / "sweep-empty-repo", branch = "delegate/mq-empty", merge = false)
    // 落 dropped-empty 臂的可达形态 = 分支引用消失（典型：合并后 branch -d、或
    // 重启前被清）。可读 git 面下 ancestor 假 ⟺ ahead≥1，`ahead==0` 腿对存在的
    // 分支不可达（扫臂序：ancestor 真 ⇒ already-merged；否则分支在 ⇒ mountable）。
    os.proc("git", "-C", repo.toString, "branch", "-D", "delegate/mq-empty")
      .call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    assert(!GitWorktrees.branchExists(repo, "delegate/mq-empty"), "fixture sanity: the branch ref is gone")
    seedRecord("mq-empty", repo)
    ledgerRow("mq-empty", "reminder-sent", readyAt = 222L)
    val system = ActorSystem(s"mqbs-3-${scala.util.Random.nextInt(100000)}")
    val (report, actions) = (for
      res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
      report <- MergeQueueBootSweep.sweep(res)
      actions = ledgerActions("mq-empty")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield (report, actions)).unsafeRunSync()
    assertEquals(report.droppedEmpty, List("mq-empty"), "an empty membership closes honestly")
    assert(actions.contains("recovered-dropped-empty"), s"the dropped-empty row must be audited: $actions")
    assert(MergeQueue.find("mq-empty").isEmpty, "nothing must be re-mounted for an empty branch")
  }

  test(
    "④ mountable internal leg ⇒ re-mounted with the ORIGINAL readyAt + revive channel received the merge brief; onTerminal frees the window"
  ) {
    val repo = mkRepo(tempRoot / "sweep-mount-repo", branch = "delegate/mq-mount", merge = false)
    seedRecord("mq-mount", repo) // executor = nebflow ⇒ internal leg
    ledgerRow("mq-mount", "enqueued", readyAt = 555000L)
    val captured = ListBuffer[String]()
    val revive: DelegateRegistry.Record => String => IO[Either[String, Unit]] =
      _ => msg => IO { captured += msg; Right(()) }
    val system = ActorSystem(s"mqbs-4-${scala.util.Random.nextInt(100000)}")
    val (report, mounted, inFlight, actions) = (for
      res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
      report <- MergeQueueBootSweep.sweep(res, reviveInternal = Some(revive))
      mounted = MergeQueue.find("mq-mount")
      // 🔴 键语义：isInFlight 收 projectKey（git common-dir 实路径），非 delegate id。
      inFlight = MergeQueue.isInFlight(GitWorktrees.projectKeyOf(repo))
      actions = ledgerActions("mq-mount")
      _ <- IO.sleep(100.millis)
      _ = MergeQueue.onTerminal("mq-mount")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield (report, mounted, inFlight, actions)).unsafeRunSync()
    val released = MergeQueue.queueViewOf("mq-mount").isEmpty && !MergeQueue.isInFlight(GitWorktrees.projectKeyOf(repo))
    assertEquals(report.reMounted, List("mq-mount"), "the membership re-mounts")
    assert(mounted.isDefined, "the entry must live in the in-process queue again")
    assertEquals(
      mounted.map(_.readyAt),
      Some(555000L),
      "readyAt is the ledger original value (FIFO rank survives the restart)"
    )
    assert(inFlight, "the single-flight window is held by the re-mounted member")
    assert(
      captured.size == 1 && captured.head.contains("merge --no-ff"),
      s"the revive channel got the merge brief: $captured"
    )
    assert(actions.contains("reminder-sent"), s"the channel injection is audited: $actions")
    assert(released, "onTerminal of the re-mounted member releases the window")
  }

  test("⑤ internal leg without a revival channel ⇒ recovery-blocked row + task row failed + report blocked") {
    val repo = mkRepo(tempRoot / "sweep-blocked-repo", branch = "delegate/mq-blocked", merge = false)
    seedRecord("mq-blocked", repo)
    ledgerRow("mq-blocked", "enqueued", readyAt = 444L)
    val system = ActorSystem(s"mqbs-5-${scala.util.Random.nextInt(100000)}")
    val (report, actions, task) = (for
      res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
      _ <- res.subAgentTaskStore.recordTask(
        SubAgentTask(
          taskId = "mq-blocked",
          parentSessionId = "nebula-root",
          agentName = "nebflow",
          prompt = "brief",
          description = "survivor",
          status = "ready-to-merge",
          retryCount = 0,
          spawnedAt = 1L,
          completedAt = None,
          lastError = None,
          source = "delegate"
        )
      )
      report <- MergeQueueBootSweep.sweep(res) // reviveInternal = None ⇒ 诚实挂不进
      actions = ledgerActions("mq-blocked")
      task <- res.subAgentTaskStore.findByTaskId("mq-blocked")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield (report, actions, task)).unsafeRunSync()
    assertEquals(report.blocked.map(_._1), List("mq-blocked"), "the unmountable member is reported blocked")
    assert(actions.contains("recovery-blocked"), s"the recovery-blocked row must be audited: $actions")
    assertEquals(
      task.map(_.status),
      Some("failed"),
      "the ghost ready-to-merge row must close as failed (blocked semantics)"
    )
    assert(
      task.flatMap(_.lastError).exists(_.contains("merge-queue recovery blocked")),
      s"the reason channel carries the cause: ${task.flatMap(_.lastError)}"
    )
    assert(MergeQueue.find("mq-blocked").isEmpty, "nothing must be re-mounted for a blocked member")
  }

end MergeQueueBootSweepSpec
