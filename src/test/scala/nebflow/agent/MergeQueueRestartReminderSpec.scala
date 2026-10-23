package nebflow.agent

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import nebflow.actor.ActorSystem
import nebflow.core.delegate.{ChainStore, DelegateRegistry, MergeQueue}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk}

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*

/**
 * 合并队列「留队待重试」的**跨重启**判读（steward 批 D3-②③，2026-10-23）：
 *
 * 验收「重启后提醒仍达」：提醒不可投（`reminder-deferred`）与合并轮被拒
 * （`refused-keep-queued`）的条目**留队待重试**——其持久账本行在重启后同样按
 * 中断成员被 boot sweep 重挂，合并提醒经 revive 通道**仍达**；后继 `dequeued`
 * 行 = 干净退出，不重挂。
 */
class MergeQueueRestartReminderSpec extends FunSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-mq-restart-reminder"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    os.remove.all(tempRoot / "chains")
    os.remove.all(tempRoot / "delegates")
    os.remove.all(tempRoot / "subagent-tasks")

  override def afterEach(context: munit.AfterEach): Unit = ()

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

  private def seedRecord(id: String, repo: os.Path): Unit =
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = id, executor = "nebflow", cwd = repo.toString,
        project = None, taskId = Some("99"), parentSessionId = "nebula-root", title = "survivor", createdAt = 1L,
        branch = Some(s"delegate/$id"), baseBranch = Some("main"),
        projectWorkspace = Some(repo.toString),
        worktree = None
      )
    )

  /** main@init + delegate 分支一笔交付提交（不并回 main ⇒ 有可并产物）。 */
  private def mkRepo(dir: os.Path, branch: String): os.Path =
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
    dir

  test("refused-keep-queued last row ⇒ interrupted member after restart; the sweep re-mounts it and the revive channel re-delivers the merge reminder") {
    val repo = mkRepo(tempRoot / "rr-refused-repo", branch = "delegate/rr-refused")
    seedRecord("rr-refused", repo)
    ledgerRow("rr-refused", "enqueued", readyAt = 700L)
    ledgerRow("rr-refused", "refused-keep-queued", readyAt = 700L)
    val members = MergeQueueBootSweep.interruptedMembers()
    assert(members.exists(_.delegateId == "rr-refused"), "the kept-for-retry membership survives the restart as interrupted")
    val captured = ListBuffer[String]()
    val revive: DelegateRegistry.Record => String => IO[Either[String, Unit]] =
      _ => msg => IO { captured += msg; Right(()) }
    val system = ActorSystem(s"rr-1-${scala.util.Random.nextInt(100000)}")
    val (report, mounted) = (for
      res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
      report <- MergeQueueBootSweep.sweep(res, reviveInternal = Some(revive))
      mounted = MergeQueue.find("rr-refused")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield (report, mounted)).unsafeRunSync()
    assertEquals(report.reMounted, List("rr-refused"), "the refused membership re-mounts")
    assert(captured.size == 1 && captured.head.contains("merge --no-ff"), s"the reminder STILL ARRIVES via the revive channel: $captured")
    assert(mounted.isDefined, "the entry lives in the in-process queue again")
  }

  test("reminder-deferred last row ⇒ same restart story: interrupted member, reminder re-delivered on revival") {
    val repo = mkRepo(tempRoot / "rr-deferred-repo", branch = "delegate/rr-deferred")
    seedRecord("rr-deferred", repo)
    ledgerRow("rr-deferred", "enqueued", readyAt = 800L)
    ledgerRow("rr-deferred", "reminder-deferred", readyAt = 800L)
    val members = MergeQueueBootSweep.interruptedMembers()
    assert(members.exists(_.delegateId == "rr-deferred"), "the deferred-reminder membership survives the restart as interrupted")
    val captured = ListBuffer[String]()
    val revive: DelegateRegistry.Record => String => IO[Either[String, Unit]] =
      _ => msg => IO { captured += msg; Right(()) }
    val system = ActorSystem(s"rr-2-${scala.util.Random.nextInt(100000)}")
    val (report, capturedCount) = (for
      res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
      report <- MergeQueueBootSweep.sweep(res, reviveInternal = Some(revive))
      count = captured.size
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield (report, count)).unsafeRunSync()
    assertEquals(report.reMounted, List("rr-deferred"), "the deferred membership re-mounts")
    assertEquals(capturedCount, 1, "the merge reminder is re-delivered exactly once on revival")
  }

  test("a dequeued successor row = clean exit: NOT interrupted, no re-mount") {
    val repo = mkRepo(tempRoot / "rr-clean-repo", branch = "delegate/rr-clean")
    seedRecord("rr-clean", repo)
    ledgerRow("rr-clean", "enqueued", readyAt = 900L)
    ledgerRow("rr-clean", "refused-keep-queued", readyAt = 900L)
    ledgerRow("rr-clean", "dequeued", readyAt = 900L) // 后继干净退出（重试已消费）
    val members = MergeQueueBootSweep.interruptedMembers()
    assert(!members.exists(_.delegateId == "rr-clean"), "the consumed membership does not re-mount")
  }

end MergeQueueRestartReminderSpec
