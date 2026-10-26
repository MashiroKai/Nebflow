package nebflow.agent

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import nebflow.actor.ActorSystem
import nebflow.core.delegate.{ChainStore, DelegateRegistry, MergeQueue}
import nebflow.core.GitWorktrees
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk}

import scala.util.Try

/**
 * 合并队列「跨重启 + 词汇表」判读（台账 #896 交通引导员制 2026-10-26；前身 =
 * steward 批 D3-②③ 的「留队待重试跨重启重挂」——重挂/复活/重试词汇已整体退役）：
 *
 *  - ① **终态成员不复活**：merged / owner-gone / failed 关账成员对 boot sweep
 *    不可见（openSnapshot 只含开放成员）——关账即关账，重启不重开；
 *  - ② **重试词汇表退役**：完整 放行→被拒 生命周期的审计行只含
 *    enqueued / granted / merge-failed——「refused-keep-queued / stall-* /
 *    retry-injected / reminder-deferred」词汇零出现（refused×N/parked 语义消失）；
 *  - ③ **账本持久**：turn-granted 成员（含 grantedAt / grantedBaseTip / 关账面）
 *    跨内存清零完整回载——重启后账实仍可对。
 */
class MergeQueueRestartReminderSpec extends FunSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-mq-restart-reminder"
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

  private def member(id: String, repo: os.Path, readyAt: Long): MergeQueue.Member =
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

  private def ledgerActions(delegateId: String): List[String] =
    ChainStore.listSync().flatMap(_.entries).filter(_.kind == MergeQueue.EntryKind).flatMap { e =>
      val c = e.payload.hcursor
      c.get[String]("delegateId").toOption.filter(_ == delegateId).map(_ => c.get[String]("action").toOption.getOrElse(""))
    }

  /** 重试/滞留/重挂词汇（退役词——账面上零出现）。 */
  private val RetiredWords: Set[String] = Set(
    "refused-keep-queued",
    "reminder-deferred",
    "stall-notice-sent",
    "stall-attempt",
    "retry-injected",
    "stall-escalated",
    "obstacle-cleared",
    "inject-failed",
    "recovered-rekeyed",
    "recovery-blocked"
  )

  test("① terminal members are invisible to the boot sweep (closed is closed — a restart never re-opens a membership)") {
    val repo = mkRepo(tempRoot / "rr-repo", branch = "delegate/rr-a", merge = true)
    // 三条终态成员（merged / owner-gone / failed）直接落账。
    MergeQueue.seedMemberForTest(member("rr-merged", repo, readyAt = 1L).copy(state = "merged", closedAt = Some(1L), closeReason = Some("landed")))
    MergeQueue.seedMemberForTest(member("rr-gone", repo, readyAt = 2L).copy(state = "owner-gone", closedAt = Some(2L), closeReason = Some("session gone")))
    MergeQueue.seedMemberForTest(member("rr-failed", repo, readyAt = 3L).copy(state = "failed", closedAt = Some(3L), closeReason = Some("CONFLICT")))
    MergeQueue.dropMemoryStateForTest() // 重启模拟
    val system = ActorSystem(s"mqr-1-${scala.util.Random.nextInt(100000)}")
    val (report, open, states) = (for
      res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
      report <- MergeQueueBootSweep.sweep(res)
      open = MergeQueue.openSnapshot
      states = MergeQueue.snapshot.map(m => m.delegateId -> m.state).toMap
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield (report, open, states)).unsafeRunSync()
    assertEquals(report, MergeQueueBootSweep.Report(), "the sweep is zero-action on an all-closed ledger")
    assertEquals(open, Nil, "no terminal member is re-opened after a restart")
    assertEquals(states.get("rr-merged"), Some("merged"), "the merged close-out survives")
    assertEquals(states.get("rr-gone"), Some("owner-gone"), "the owner-gone close-out survives")
    assertEquals(states.get("rr-failed"), Some("failed"), "the failed close-out survives")
  }

  test("② the retry vocabulary is retired: a full grant→refused lifecycle leaves no park/retry words on the ledger") {
    val repo = mkRepo(tempRoot / "rr2-repo", branch = "delegate/rr-life", merge = false)
    val system = ActorSystem(s"mqr-2-${scala.util.Random.nextInt(100000)}")
    val records = scala.collection.mutable.ListBuffer.empty[(String, Option[nebflow.actor.ActorRef[nebflow.actor.AgentEvent]])]
    val (child, sup) = (for
      child <- system.spawn(
        new nebflow.actor.Behavior[nebflow.actor.AgentCommand]:
          def receive(ctx: nebflow.actor.ActorContext[nebflow.actor.AgentCommand], msg: nebflow.actor.AgentCommand): IO[nebflow.actor.Behavior[nebflow.actor.AgentCommand]] =
            msg match
              case nebflow.actor.AgentCommand.UserInput(text, replyTo, _, _, _, _, _, _, _, _, _, _, _) =>
                IO { records += ((text, replyTo)); this }
              case _ => IO.pure(this)
        ,
        s"rr-life-child-${scala.util.Random.nextInt(100000)}"
      )
      sup <- system.spawn(
        new nebflow.actor.Behavior[nebflow.actor.AgentEvent]:
          def receive(ctx: nebflow.actor.ActorContext[nebflow.actor.AgentEvent], msg: nebflow.actor.AgentEvent): IO[nebflow.actor.Behavior[nebflow.actor.AgentEvent]] =
            IO.pure(this)
        ,
        s"rr-life-sup-${scala.util.Random.nextInt(100000)}"
      )
    yield (child, sup)).unsafeRunSync()
    val m = member("rr-life", repo, readyAt = 1L).copy(internal = Some(MergeQueue.InternalLeg(child, sup)))
    MergeQueue.seedMemberForTest(m)
    MergeQueue.pump(m.projectKey) // 放行 = 通知（真 actor 收单）
    Thread.sleep(80)
    val afterGrant = records.toList
    val actionsGrant = ledgerActions("rr-life")
    MergeQueue.onTerminal("rr-life", Some(MergeQueue.RoundOutcome.Refused("CONFLICT: file")))
    val actions = ledgerActions("rr-life")
    val retired = actions.filter(RetiredWords.contains)
    val state = MergeQueue.find("rr-life").map(_.state)
    system.stopAll.unsafeRunSync()
    assertEquals(afterGrant.size, 1, "the grant injected exactly one merge turn")
    assert(actionsGrant.contains("granted"), s"the grant is audited: $actionsGrant")
    assert(actions.contains("merge-failed"), s"the refused close-out is audited as merge-failed: $actions")
    assertEquals(retired, Nil, s"no retired retry/park vocabulary may appear: $actions")
    assertEquals(state, Some("failed"), "the refused member is closed failed (no park)")
    assertEquals(records.size, 1, "no re-notification after the close-out (no retry rounds exist)")
  }

  test("③ the ledger persists across a restart: granted facts (grantedAt / base tip / close-outs) reload from disk") {
    val repo = mkRepo(tempRoot / "rr3-repo", branch = "delegate/rr-p", merge = false)
    MergeQueue.seedMemberForTest(
      member("rr-p", repo, readyAt = 7L)
        .copy(state = "turn-granted", grantedAt = Some(1234L), grantedBaseTip = Some("abc123def456"))
    )
    MergeQueue.dropMemoryStateForTest() // 重启模拟：内存清零、盘上账在
    val reloaded = MergeQueue.find("rr-p")
    assertEquals(reloaded.map(_.state), Some("turn-granted"), "the open membership reloads")
    assertEquals(reloaded.flatMap(_.grantedAt), Some(1234L), "grantedAt survives")
    assertEquals(reloaded.flatMap(_.grantedBaseTip), Some("abc123def456"), "the granted base tip survives")
    assertEquals(reloaded.flatMap(_.internal), None, "channel closures are process-local (never persisted)")
    assertEquals(reloaded.map(_.readyAt), Some(7L), "readyAt (FIFO rank) survives")
  }

end MergeQueueRestartReminderSpec
