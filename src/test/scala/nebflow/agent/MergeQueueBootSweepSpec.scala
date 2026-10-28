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

import scala.concurrent.duration.*
import scala.util.Try

/**
 * MergeQueueBootSweep 重启对账判读（台账 #896 交通引导员制 2026-10-26；前身 =
 * 引擎加固批 2026-10-09 的「重挂 + 复活」sweep——重挂/复活/换键腿已整体退役）：
 *
 *  - ① 已并成员（祖先真）⇒ 关账 merged + [[PostMergeCleanup]] 同守卫收残留现场
 *    （worktree 移除 + branch -d）+ 报告 alreadyMerged——**零复活零重挂**；
 *  - ② 无可并物（分支引用消失）⇒ 关账 merged（诚实注记）+ 报告 droppedEmpty；
 *  - ③ 可并但属主失联（重启后属主会话必死）⇒ **owner-gone** 关账 + 任务行翻
 *    failed + 报告 ownerGone（需 dispatcher 处置——绝不静默 park，绝不续起
 *    合并回合）；
 *  - ④ 无开放成员 = 零动作。
 *
 * 账本判据单点 = 持久账本（merge-ledger.json）——ChainStore 行只做审计。
 * （父通知腿 = F2 注入队文件 correlationId 幂等；spec 未建父会话 ⇒ 通知静默
 * 跳过——本件不断言通知内容。）
 */
class MergeQueueBootSweepSpec extends FunSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-mq-boot-sweep"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  nebflow.ForceRemove.removeAll(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    MergeQueue.reset()
    nebflow.ForceRemove.removeAll(tempRoot / "chains")
    nebflow.ForceRemove.removeAll(tempRoot / "delegates")
    nebflow.ForceRemove.removeAll(tempRoot / "subagent-tasks")

  private class SilentLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): fs2.Stream[IO, StreamChunk] =
      fs2.Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

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
    nebflow.ForceRemove.removeAll(dir)
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

  /** 把一条开放成员直接写进持久账本（模拟重启前的在账成员；无通道闭包）。 */
  private def seedMember(id: String, repo: os.Path, readyAt: Long): Unit =
    MergeQueue.seedMemberForTest(
      MergeQueue.Member(
        delegateId = id,
        branch = s"delegate/$id",
        baseWs = repo.toString,
        baseBranch = "main",
        worktree = false,
        projectKey = Try(GitWorktrees.projectKeyOf(repo)).getOrElse(repo.toString),
        taskId = Some("99"),
        parentSessionId = "nebula-root",
        description = "survivor",
        readyAt = readyAt,
        channelKind = "internal"
      )
    )

  test(
    "① already-merged member ⇒ closed merged + leftover scene cleaned under the zero-ahead guard + report; NO merge round is revived"
  ) {
    val repo = mkRepo(tempRoot / "sweep-merged-repo", branch = "delegate/mq-merged", merge = true)
    val wt = tempRoot / "sweep-merged-wt"
    nebflow.ForceRemove.removeAll(wt) // 夹具幂等：上一轮残留的 kept 场景 wt 会撞 worktree add already exists
    os.proc("git", "-C", repo.toString, "worktree", "add", wt.toString, "delegate/mq-merged")
      .call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    seedRecord("mq-merged", repo, worktree = Some(wt))
    seedMember("mq-merged", repo, readyAt = 111L)
    MergeQueue.dropMemoryStateForTest() // 模拟重启：账在盘上、内存清零、属主通道闭包消失
    val system = ActorSystem(s"mqbs-2-${scala.util.Random.nextInt(100000)}")
    val (report, actions, cleanupActions, state) = (for
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
      state = MergeQueue.find("mq-merged").map(_.state)
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield (report, actions, cleanup, state)).unsafeRunSync()
    assertEquals(report.alreadyMerged, List("mq-merged"), "the landed merge is recognized")
    assert(actions.contains("merge-merged"), s"the close-out must be audited: $actions")
    assert(cleanupActions.contains("removed"), s"the leftover worktree scene must be cleaned: $cleanupActions")
    assert(!os.exists(wt), "the worktree directory must be gone after the sweep cleanup")
    assert(!GitWorktrees.branchExists(repo, "delegate/mq-merged"), "the merged branch must be deleted (branch -d)")
    assertEquals(
      state,
      Some("merged"),
      "the membership is closed as merged (owner-gone must NOT apply to a landed merge)"
    )
  }

  test("② missing branch ref (nothing mergeable) ⇒ closed merged with the honest note + report droppedEmpty") {
    val repo = mkRepo(tempRoot / "sweep-empty-repo", branch = "delegate/mq-empty", merge = false)
    os.proc("git", "-C", repo.toString, "branch", "-D", "delegate/mq-empty")
      .call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    assert(!GitWorktrees.branchExists(repo, "delegate/mq-empty"), "fixture sanity: the branch ref is gone")
    seedRecord("mq-empty", repo)
    seedMember("mq-empty", repo, readyAt = 222L)
    MergeQueue.dropMemoryStateForTest()
    val system = ActorSystem(s"mqbs-3-${scala.util.Random.nextInt(100000)}")
    val (report, actions, state) = (for
      res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
      report <- MergeQueueBootSweep.sweep(res)
      actions = ledgerActions("mq-empty")
      state = MergeQueue.find("mq-empty").map(m => (m.state, m.closeReason))
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield (report, actions, state)).unsafeRunSync()
    assertEquals(report.droppedEmpty, List("mq-empty"), "an empty membership closes honestly")
    assertEquals(state.map(_._1), Some("merged"), "the membership is closed as merged (nothing to merge)")
    assert(
      state.flatMap(_._2).exists(_.contains("nothing to merge")),
      s"the honest note rides the close reason: $state"
    )
    assert(actions.contains("merge-merged"), s"the close-out row must be audited: $actions")
  }

  test(
    "③ mergeable but the owner session died with the restart ⇒ owner-gone + task row failed; NO revival channel is invoked"
  ) {
    val repo = mkRepo(tempRoot / "sweep-gone-repo", branch = "delegate/mq-gone", merge = false)
    seedRecord("mq-gone", repo)
    seedMember("mq-gone", repo, readyAt = 444L)
    MergeQueue.dropMemoryStateForTest()
    val system = ActorSystem(s"mqbs-4-${scala.util.Random.nextInt(100000)}")
    val (report, actions, task, state) = (for
      res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
      _ <- res.subAgentTaskStore.recordTask(
        SubAgentTask(
          taskId = "mq-gone",
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
      report <- MergeQueueBootSweep.sweep(res)
      actions = ledgerActions("mq-gone")
      task <- res.subAgentTaskStore.findByTaskId("mq-gone")
      state = MergeQueue.find("mq-gone").map(_.state)
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield (report, actions, task, state)).unsafeRunSync()
    assertEquals(report.ownerGone, List("mq-gone"), "the orphaned member is reported owner-gone")
    assert(actions.contains("owner-gone"), s"the owner-gone row must be audited: $actions")
    assertEquals(state, Some("owner-gone"), "the membership is closed as owner-gone (needs dispatcher handling)")
    assertEquals(task.map(_.status), Some("failed"), "the ghost ready-to-merge row must close as failed")
    assert(
      task.flatMap(_.lastError).exists(_.contains("merge-queue owner-gone")),
      s"the reason channel carries the cause: ${task.flatMap(_.lastError)}"
    )
  }

  test("④ no open members = zero-action sweep") {
    val system = ActorSystem(s"mqbs-5-${scala.util.Random.nextInt(100000)}")
    val report = (for
      res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
      report <- MergeQueueBootSweep.sweep(res)
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield report).unsafeRunSync()
    assertEquals(report, MergeQueueBootSweep.Report(), "an empty ledger sweeps to an empty report")
  }

end MergeQueueBootSweepSpec
