package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.delegate.{DelegateRegistry, RepositoryWriteLock, WorkspaceLedger}
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * Cross-process merger write exclusion（node-feedback delivery · Priority A）：
 * an executing merger's shell writes to the merge target repository must be
 * mutually exclusive across processes (different data roots over the same git
 * common-dir). Acceptance:
 *   - merger identity resolution (ledger merger row delegateId == session id;
 *     registry mode=merger fallback) and the lock file under the git common dir;
 *   - two concurrent merger sessions over one repository: exactly one proceeds,
 *     the other gets the in-use error;
 *   - non-merger sessions are unaffected; the SAME merger session reenters
 *     (its own parallel foreground + background work must not self-deadlock);
 *   - a background job holds the lock until the process reaches terminal state
 *     (cancel kills the process → the execution fiber terminalizes → release).
 */
class MergerWriteLockSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-merger-write-lock"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  nebflow.ForceRemove.removeAll(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    // Per-test ledger isolation: leftovers would make ledger.merger see a
    // repository change for the same project name.
    cats.effect.IO(nebflow.ForceRemove.removeAll(tempRoot / "workspaces")).unsafeRunSync()

  /** Git repo fixture + two merger identities over the SAME repository. */
  private def fixture(tag: String): (os.Path, String, String) =
    val repo = os.temp.dir(prefix = s"mwl-$tag-")
    def git(args: String*) =
      os.proc(Seq("git") ++ args).call(cwd = repo, stdout = os.Pipe, stderr = os.Pipe).out.text().trim
    git("init", "-b", "main")
    git("config", "user.name", "test")
    git("config", "user.email", "test@example.invalid")
    git("commit", "--allow-empty", "-m", "base")
    val ledgerRowOwner = s"delegate-merger-a-$tag"
    val registryOnly = s"delegate-merger-b-$tag"
    val ledger = WorkspaceLedger.default
    ledger.merger(s"project-$tag", WorkspaceLedger.repositoryKey(repo), ledgerRowOwner)
    List(ledgerRowOwner, registryOnly).foreach { id =>
      DelegateRegistry.put(
        DelegateRegistry.Record(
          id = id,
          executor = "default",
          cwd = repo.toString,
          project = Some("project"),
          taskId = None,
          parentSessionId = "root",
          title = "test merger",
          createdAt = System.currentTimeMillis(),
          mode = Some("merger")
        )
      )
    }
    (repo, ledgerRowOwner, registryOnly)

  private def ctx(sessionId: String): ToolContext = ToolContext(projectRoot = "/tmp", sessionId = Some(sessionId))

  private def input(json: io.circe.Json): JsonObject = json.asObject.get

  private def echoCall(sessionId: String, marker: String): IO[Either[String, String]] =
    BashTool
      .call(input(io.circe.Json.obj("command" -> s"echo $marker".asJson)), ctx(sessionId))
      .map(_.left.map(_.message))

  /** Poll until the repository is writable again (release is asynchronous after terminal state). */
  private def awaitAcquirable(repo: os.Path, tries: Int = 50): IO[Unit] =
    IO.blocking(RepositoryWriteLock.acquireFor("release-probe", WorkspaceLedger.repositoryKey(repo))).flatMap {
      case Right(hold) => IO.blocking(RepositoryWriteLock.releaseFor(hold)).void
      case Left(_) =>
        if tries <= 0 then IO.raiseError(new RuntimeException("merger write lock was never released"))
        else IO.sleep(200.millis) *> awaitAcquirable(repo, tries - 1)
    }

  // ── 锁层直测（无 shell，判定确定性）────────────────────────────────────

  test("lock layer: distinct holders exclude each other, the same holder reenters, release restores availability") {
    val repo = os.temp.dir(prefix = "mwl-layer-")
    def git(args: String*) =
      os.proc(Seq("git") ++ args).call(cwd = repo, stdout = os.Pipe, stderr = os.Pipe).out.text().trim
    git("init", "-b", "main")
    val repository = WorkspaceLedger.repositoryKey(repo)
    val lockFile = os.Path(repository) / "stand-workspace-write.lock"
    try
      val first = RepositoryWriteLock.acquireFor("session-1", repository)
      assertEquals(first.isRight, true, s"the first holder must acquire: $first")
      assertEquals(os.exists(lockFile), true, "the lock file must exist under the git common dir while held")
      val hold1 = first.toOption.get
      // Same holder reenters (depth 2) — no self-deadlock, still one file lock.
      val reentrant = RepositoryWriteLock.acquireFor("session-1", repository)
      assertEquals(reentrant.isRight, true, s"the same holder must reenter: $reentrant")
      // A distinct holder in the same JVM is refused.
      val second = RepositoryWriteLock.acquireFor("session-2", repository)
      assert(second.isLeft, s"a distinct holder must be refused: $second")
      second.left.foreach(error => assert(error.toLowerCase.contains("in use"), s"clear in-use reason: $error"))
      // Depth 2 → release once = still held; release again = free.
      RepositoryWriteLock.releaseFor(reentrant.toOption.get)
      assert(RepositoryWriteLock.acquireFor("session-2", repository).isLeft, "still held at depth 1")
      RepositoryWriteLock.releaseFor(hold1)
      val after = RepositoryWriteLock.acquireFor("session-2", repository)
      assertEquals(after.isRight, true, s"release must restore availability: $after")
      RepositoryWriteLock.releaseFor(after.toOption.get)
    finally os.remove.all(repo)
    end try
  }

  // ── BashTool 端到端（真实 shell 执行路径）──────────────────────────────

  test("two concurrent merger sessions over one repository: exactly one proceeds, the other gets the in-use error") {
    val (repo, mergerA, mergerB) = fixture("e2e")
    val sessionIds = List(mergerA, mergerB).map(sid => sid -> ctx(sid))
    for
      // Merger A starts a real background job — the hold covers its whole lifetime.
      started <- BashTool
        .call(
          input(io.circe.Json.obj("command" -> "sleep 30".asJson, "run_in_background" -> true.asJson)),
          ctx(mergerA)
        )
        .map(_.left.map(_.message))
      jobId = started.toOption.flatMap(receipt => "Job ID: ([0-9a-f]+)".r.findFirstMatchIn(receipt).map(_.group(1)))
      _ = assert(started.isRight, s"the merger background start must succeed: $started")
      _ = assert(jobId.isDefined, s"the receipt must carry the job id: ${started.toOption.getOrElse("")}")
      // Merger B (registry-only identity, same repository) is refused.
      refused <- echoCall(mergerB, "b-must-not-run")
      // The same merger session A stays workable (session-reentrant hold).
      self <- echoCall(mergerA, "a-reentrant-ok")
      // Cancel A's job → the process reaches terminal state → the hold is released.
      cancelled <- BashTool
        .call(
          input(io.circe.Json.obj("background_job_id" -> jobId.getOrElse("").asJson, "cancel_background_job" -> true.asJson)),
          ctx(mergerA)
        )
        .map(_.left.map(_.message))
      _ <- awaitAcquirable(repo)
      unblocked <- echoCall(mergerB, "b-runs-after-release")
    yield
      assert(refused.isLeft, s"merger B must be refused while A holds the repository: $refused")
      refused.foreach(output => fail(s"merger B's command must not execute: $output"))
      assert(
        refused.left.exists(_.contains("Repository write lock in use")),
        s"the refusal must be the exclusion gate: $refused"
      )
      assert(self.exists(_.contains("a-reentrant-ok")), s"the same merger session must reenter: $self")
      assert(cancelled.exists(_.contains("Background job cancelled")), s"the job must cancel: $cancelled")
      assert(unblocked.exists(_.contains("b-runs-after-release")), s"merger B runs after release: $unblocked")
    end for
  }

  test("non-merger sessions are unaffected — a plain session and a dev delegate run without touching the lock") {
    val (repo, mergerA, _) = fixture("unaffected")
    val plainSession = s"plain-${scala.util.Random.nextInt(100000)}"
    val devDelegate = s"delegate-worker-${scala.util.Random.nextInt(100000)}"
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = devDelegate,
        executor = "default",
        cwd = repo.toString,
        project = None,
        taskId = None,
        parentSessionId = "root",
        title = "dev delegate (no mode)",
        createdAt = System.currentTimeMillis()
      )
    )
    for
      // A merger holds the repository (background job, like the real merge window).
      started <- BashTool
        .call(
          input(io.circe.Json.obj("command" -> "sleep 30".asJson, "run_in_background" -> true.asJson)),
          ctx(mergerA)
        )
        .map(_.left.map(_.message))
      jobId = started.toOption.flatMap(receipt => "Job ID: ([0-9a-f]+)".r.findFirstMatchIn(receipt).map(_.group(1)))
      plain <- echoCall(plainSession, "plain-runs")
      dev <- echoCall(devDelegate, "dev-runs")
      cancelled <- BashTool
        .call(
          input(io.circe.Json.obj("background_job_id" -> jobId.getOrElse("").asJson, "cancel_background_job" -> true.asJson)),
          ctx(mergerA)
        )
        .map(_.left.map(_.message))
      _ <- awaitAcquirable(repo)
    yield
      assert(started.isRight, s"fixture: the merger job must start: $started")
      assert(plain.exists(_.contains("plain-runs")), s"a plain session is unaffected: $plain")
      assert(dev.exists(_.contains("dev-runs")), s"a dev delegate (mode absent) is unaffected: $dev")
      assert(cancelled.exists(_.contains("Background job cancelled")), s"fixture teardown: $cancelled")
    end for
  }

end MergerWriteLockSpec
