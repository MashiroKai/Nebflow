package nebflow.core.tools

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.delegate.{MergerFacts, WorkspaceLedger}
import nebflow.shared.PathUtil

/**
 * Recovery face of merger_report (node-feedback delivery · Priority C): the
 * inspect action exposes the read-only reconcile reading, the report action
 * rejects verification evidence without the command, exit code and timestamp,
 * and the request state does not move on rejection.
 */
class MergerReportRecoverySpec extends CatsEffectSuite:
  private val previous = PathUtil.dataRoot
  private var root: os.Path = os.pwd
  private var repo: os.Path = os.pwd
  private var tree: os.Path = os.pwd
  private var source: String = ""

  override def beforeEach(context: munit.BeforeEach): Unit =
    root = os.temp.dir(prefix = "merger-recovery-")
    PathUtil.setDataRoot(root / "data")
    repo = root / "repo"
    os.makeDir.all(repo)
    def git(args: String*) =
      os.proc(Seq("git") ++ args).call(cwd = repo, stdout = os.Pipe, stderr = os.Pipe).out.text().trim
    git("init", "-b", "main")
    git("config", "user.name", "test")
    git("config", "user.email", "test@example.invalid")
    git("commit", "--allow-empty", "-m", "base")
    val base = git("rev-parse", "HEAD")
    tree = root / "task"
    git("worktree", "add", "-b", "delegate/task", tree.toString, "main")
    os.write(tree / "task.txt", "authorized work")
    os.proc("git", "add", "task.txt").call(cwd = tree)
    os.proc("git", "commit", "-m", "task").call(cwd = tree, stdout = os.Pipe)
    source = git("rev-parse", "delegate/task")
    val ledger = WorkspaceLedger.default
    val repository = WorkspaceLedger.repositoryKey(repo)
    ledger.merger("project", repository, "delegate-merger")
    val workspace = WorkspaceLedger.Workspace(
      "task",
      "project",
      repository,
      tree.toNIO.toRealPath().toString,
      "delegate/task",
      "main",
      base,
      "owner",
      "kernel"
    )
    ledger.register(workspace)
    val item = MergerFacts.pinSource(workspace).toOption.get
    assert(
      ledger.requestMerge(WorkspaceLedger.MergeRequest("request", repository, "delegate-merger", "fixture authorization", List(item))).isRight
    )

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(previous)
    os.remove.all(root)

  private def ctx: ToolContext = ToolContext(projectRoot = root.toString, sessionId = Some("delegate-merger"))

  private def report(eventId: String, phase: String, merge: Option[String], proof: Option[String]): IO[Either[ToolError, String]] =
    IO.blocking(WorkspaceLedger.default.snapshot.requests.find(_.id == "request").get.revision).flatMap { revision =>
      MergerReportTool
        .call(
          Json
            .obj(
              "action" -> "report".asJson,
              "requestId" -> "request".asJson,
              "eventId" -> eventId.asJson,
              "expectedRevision" -> revision.asJson,
              "workspaceId" -> "task".asJson,
              "phase" -> phase.asJson,
              "mergeCommit" -> merge.asJson,
              "verification" -> proof.asJson
            )
            .asObject
            .get,
          ctx
        )
    }

  private def request = WorkspaceLedger.default.snapshot.requests.find(_.id == "request").get

  test("evidence without command, exit code or timestamp is rejected and the request state does not move") {
    for
      checked <- report("check", "checking", None, None)
      _ = assert(checked.isRight, s"checking must be accepted: $checked")
      _ <- IO.blocking {
        os.proc("git", "merge", "--no-ff", "delegate/task", "-m", "recovery fixture integration")
          .call(cwd = repo, stdout = os.Pipe, stderr = os.Pipe)
      }
      merge = os.proc("git", "rev-parse", "HEAD").call(cwd = repo).out.text().trim
      closed <- report("close", "merged-cleanup-pending", Some(merge), None)
      _ = assert(closed.isRight, s"closure with the actual commit must be accepted: $closed")
      proofPath = repo / ".nebflow" / "evidence" / "recovery-verification.json"
      _ <- IO.blocking {
        os.makeDir.all(proofPath / os.up)
        os.write(
          proofPath,
          Json.obj("status" -> "passed".asJson, "sourceCommit" -> source.asJson, "mergeCommit" -> merge.asJson).noSpaces
        )
        os.proc("git", "worktree", "remove", tree.toString).call(cwd = repo, stdout = os.Pipe, stderr = os.Pipe)
      }
      revisionBefore = request.revision
      rejected <- report("done", "completed", Some(merge), Some(proofPath.toString))
      _ = assert(rejected.isLeft, s"incomplete evidence must be rejected: $rejected")
      _ = assert(rejected.left.exists(_.message.contains("command")), s"the rejection names the missing field: $rejected")
      _ = assertEquals(request.revision, revisionBefore, "a rejected report must not move the request state")
      _ = assertEquals(request.items.head.phase, "merged-cleanup-pending")
      _ <- IO.blocking {
        os.write.over(
          proofPath,
          Json
            .obj(
              "status" -> "passed".asJson,
              "sourceCommit" -> source.asJson,
              "mergeCommit" -> merge.asJson,
              "command" -> "git diff --check".asJson,
              "exitCode" -> 0.asJson,
              "ranAt" -> "2026-11-08T00:00:00Z".asJson
            )
            .noSpaces
        )
      }
      completed <- report("done-2", "completed", Some(merge), Some(proofPath.toString))
      _ = assert(completed.isRight, s"complete evidence must be accepted: $completed")
      _ = assertEquals(request.status, "completed")
    yield ()
  }

  test("inspect exposes the read-only reconcile reading of the in-flight request") {
    for
      _ <- report("check", "checking", None, None)
      _ <- IO.blocking {
        os.proc("git", "merge", "--ff-only", "delegate/task").call(cwd = repo, stdout = os.Pipe, stderr = os.Pipe)
      }
      inspected <- MergerReportTool.call(Json.obj("action" -> "inspect".asJson).asObject.get, ctx)
      _ = assert(inspected.isRight, s"inspect must read: $inspected")
      parsed = io.circe.parser.parse(inspected.toOption.get).fold(e => fail(e.getMessage), identity)
      reconcile = parsed.hcursor.downField("reconcile").downArray
      _ = assertEquals(reconcile.get[String]("requestId"), Right("request"))
      item = reconcile.downField("items").downArray
      _ = assertEquals(item.get[String]("reading"), Right("integrated"))
      _ = assertEquals(item.get[String]("integration"), Right("source-ancestor"))
      _ = assert(item.downField("mergeCommit").focus.exists(_.isNull), "an externally integrated source carries no merge commit")
    yield ()
  }
end MergerReportRecoverySpec
