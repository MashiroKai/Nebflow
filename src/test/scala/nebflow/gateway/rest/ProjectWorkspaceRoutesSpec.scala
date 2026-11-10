package nebflow.gateway

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import nebflow.core.delegate.DelegateRegistry
import nebflow.core.mail.SessionMailbox
import io.circe.syntax.*
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.agent.SharedResources
import nebflow.core.delegate.WorkspaceLedger
import nebflow.core.project.ProjectStore
import nebflow.llm.ModelCandidate
import nebflow.shared.{NebflowServiceConfig, PathUtil, ServiceLlmConfig}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*

import scala.concurrent.duration.*

class ProjectWorkspaceRoutesSpec extends CatsEffectSuite:
  private val previous = PathUtil.dataRoot
  private var root: os.Path = os.pwd
  private var currentResources: SharedResources = null

  override def beforeEach(context: BeforeEach): Unit =
    root = os.temp.dir(prefix = "project-workspace-routes-")
    PathUtil.setDataRoot(root / "data")
    currentResources = makeResources

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(previous)
    os.remove.all(root)

  private def resources = currentResources

  private def makeResources = SharedResources(
    llm = null,
    dispatcher = null,
    sessionStore = null,
    projectRoot = root,
    thinkingConfigRef = null,
    rateLimiter = null,
    fileChangeTracker = null,
    contextWindow = 100000,
    agentLibrary = null,
    taskStore = null,
    historyArchiver = null,
    fileLockManager = null,
    sessionModelOverrides = Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
    providerRegistry = null,
    healthMonitor = null,
    actorSystem = null,
    voiceMutedRef = Ref.unsafe[IO, Boolean](false)
  )

  private def routes = new RestApiRoutes(
    tokenRef = Ref.unsafe[IO, String]("workspace-test"),
    configRef =
      Ref.unsafe[IO, NebflowServiceConfig](NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))),
    sharedResources = resources,
    sessionStore = null,
    wsRoutes = null
  ).routes

  private def request(method: Method, path: String, body: Json = Json.obj(), auth: Boolean = true): IO[Response[IO]] =
    val req = Request[IO](method, Uri.unsafeFromString(path)).withEntity(body)
    val authenticated =
      if auth then req.putHeaders(Header.Raw(org.typelevel.ci.CIString("Authorization"), "Bearer workspace-test"))
      else req
    routes(authenticated).value.map(_.getOrElse(fail("route not found")))

  private def fixture: IO[(os.Path, os.Path)] =
    val repo = root / "repo"
    val tree = root / "task"
    IO.blocking {
      os.makeDir.all(repo)
      def git(args: String*) =
        os.proc(Seq("git") ++ args).call(cwd = repo, stdout = os.Pipe, stderr = os.Pipe).out.text().trim
      git("init", "-b", "main"); git("config", "user.name", "test"); git("config", "user.email", "test@example.invalid")
      git("commit", "--allow-empty", "-m", "base")
      val base = git("rev-parse", "HEAD")
      git("worktree", "add", "-b", "delegate/task", tree.toString, "main")
      os.write(tree / "result.txt", "committed result")
      os.proc("git", "add", "result.txt").call(cwd = tree)
      os.proc("git", "commit", "-m", "result").call(cwd = tree, stdout = os.Pipe)
      WorkspaceLedger.default.register(
        WorkspaceLedger.Workspace(
          "task",
          "example",
          WorkspaceLedger.repositoryKey(repo),
          tree.toNIO.toRealPath().toString,
          "delegate/task",
          "main",
          base,
          "delegate-owner",
          "kernel"
        )
      )
    } *> ProjectStore.create("example", repo.toString, None, "# Project instructions").flatMap { created =>
      IO(assert(created.isRight)).as((repo, tree))
    }

  end fixture

  private def installMerger(repo: os.Path, id: String): Unit =
    WorkspaceLedger.default.merger("example", WorkspaceLedger.repositoryKey(repo), id)
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = id,
        executor = "default",
        cwd = repo.toString,
        project = Some("example"),
        taskId = None,
        parentSessionId = "root",
        title = "project merger",
        createdAt = System.currentTimeMillis(),
        mode = Some("merger")
      )
    )

  end installMerger

  /** Poll until the request merges (the POST wake runs its merger on a fiber). */
  private def awaitMerged(requestId: String, tries: Int = 300): IO[Unit] =
    IO.blocking {
      WorkspaceLedger.default.snapshot.requests.find(_.id == requestId).map(_.status)
    }.flatMap {
      case Some("merged") => IO.unit
      case _ =>
        assert(tries > 0, s"merge request $requestId never merged")
        IO.sleep(100.millis) *> awaitMerged(requestId, tries - 1)
    }

  test("already integrated sources are refused without reserving the workspace or delivering work") {
    for
      pair <- fixture
      (repo, _) = pair
      _ <- IO.blocking {
        installMerger(repo, "delegate-integrated-merger")
        os.proc("git", "merge", "--no-ff", "delegate/task", "-m", "isolated preexisting integration")
          .call(cwd = repo, stdout = os.Pipe, stderr = os.Pipe)
      }
      response <- request(
        Method.POST,
        "/projects/example/workspaces/merge-requests",
        Json.obj("requestId" -> "already-integrated".asJson, "workspaceIds" -> List("task").asJson)
      )
      _ = assertEquals(response.status, Status.Conflict)
      body <- response.as[Json]
      _ = assert(body.hcursor.get[String]("error").toOption.exists(_.contains("use workspace cleanup")))
      _ = assert(WorkspaceLedger.default.snapshot.requests.isEmpty)
      _ = assertEquals(WorkspaceLedger.default.snapshot.workspaces.head.lifecycle, "open")
      pending <- SessionMailbox.scanPending("delegate-integrated-merger")
      _ = assert(pending.isEmpty)
    yield ()
  }

  test("HTTP request wakes a per-task merger, the instance merges it, and the snapshot shows the result") {
    val id = "delegate-route-merger"
    val requestId = "route-request"
    val body = Json.obj("requestId" -> requestId.asJson, "workspaceIds" -> List("task").asJson)
    for
      pair <- fixture
      (repo, tree) = pair
      _ <- IO.blocking(installMerger(repo, id))
      response <- request(Method.POST, "/projects/example/workspaces/merge-requests", body)
      _ = assertEquals(response.status, Status.Ok)
      queued <- response.as[Json]
      _ = assert(queued.hcursor.get[Boolean]("queued").toOption.contains(true), "the route wakes a per-task merger")
      _ <- awaitMerged(requestId)
      snapshot <- request(Method.GET, "/projects/example/workspaces").flatMap(_.as[Json])
      _ = assertEquals(snapshot.hcursor.downField("mergeRequests").downArray.get[String]("status"), Right("merged"))
      _ = assertEquals(
        snapshot.hcursor.downField("workspaces").downArray.get[String]("lifecycle"),
        Right("closed-merged")
      )
      _ = assert(!os.exists(tree))
      // The worker merges refs via plumbing (merge-tree/commit-tree/update-ref CAS);
      // the main checkout is not touched, so read the content from the branch ref.
      _ = assertEquals(
        os.proc("git", "show", "refs/heads/main:result.txt").call(cwd = repo, stdout = os.Pipe).out.text().trim,
        "committed result",
        "the worker's merge lands on the target branch ref"
      )
      retried <- request(Method.POST, "/projects/example/workspaces/merge-requests", body)
      _ = assertEquals(retried.status, Status.Ok)
      pending <- SessionMailbox.scanPending(id)
      _ = assert(pending.isEmpty, "the worker consumes requests without any mailbox delivery")
      _ = assertEquals(WorkspaceLedger.default.snapshot.requests.size, 1)
    yield ()
  }

  test("authenticated cleanup requires exact preview and unmerged confirmation, retains branch and closes owner") {
    for
      pair <- fixture
      (repo, tree) = pair
      denied <- request(Method.GET, "/projects/example/workspaces/task/cleanup", auth = false)
      _ = assertEquals(denied.status, Status.Forbidden)
      snapshot <- request(Method.GET, "/projects/example/workspaces")
      initial <- snapshot.as[Json]
      revision = initial.hcursor.get[Long]("revision").toOption.get
      previewResponse <- request(Method.GET, "/projects/example/workspaces/task/cleanup")
      _ = assertEquals(previewResponse.status, Status.Ok)
      preview <- previewResponse.as[Json]
      token = preview.hcursor.get[String]("previewToken").toOption.get
      missing <- request(Method.POST, "/projects/example/workspaces/task/cleanup")
      _ = assertEquals(missing.status, Status.BadRequest)
      unconfirmed <- request(
        Method.POST,
        "/projects/example/workspaces/task/cleanup",
        Json.obj("previewToken" -> Json.fromString(token))
      )
      _ = assertEquals(unconfirmed.status, Status.Conflict)
      _ = assert(os.exists(tree))
      completed <- request(
        Method.POST,
        "/projects/example/workspaces/task/cleanup",
        Json.obj("previewToken" -> Json.fromString(token), "confirmUnmerged" -> Json.True)
      )
      _ = assertEquals(completed.status, Status.Ok)
      result <- completed.as[Json]
      _ = assert(result.hcursor.get[Boolean]("branchRetained").toOption.contains(true))
      _ = assert(!os.exists(tree))
      _ = assert(WorkspaceLedger.default.admit("delegate-owner", true).isLeft)
      _ = assertEquals(
        os.proc("git", "rev-parse", "--verify", "delegate/task").call(cwd = repo, stdout = os.Pipe).exitCode,
        0
      )
      after <- request(Method.GET, "/projects/example/workspaces").flatMap(_.as[Json])
      _ = assert(after.hcursor.get[Long]("revision").toOption.get > revision)
    yield ()
  }

  test("busy cleanup is refused and baseline configuration cannot select the task branch") {
    for
      _ <- fixture
      _ <- IO(assert(WorkspaceLedger.default.claimDevelopment("task", "delegate-owner", 0).isRight))
      busy <- request(Method.GET, "/projects/example/workspaces/task/cleanup")
      _ = assertEquals(busy.status, Status.Conflict)
      invalid <- request(
        Method.PUT,
        "/projects/example/workspaces/baseline",
        Json.obj("branch" -> Json.fromString("delegate/task"))
      )
      _ = assertEquals(invalid.status, Status.Conflict)
      state <- request(Method.GET, "/projects/example/workspaces").flatMap(_.as[Json])
      _ = assertEquals(state.hcursor.get[String]("baseline"), Right("main"))
    yield ()
  }

  test("workspace facts report ahead/dirty/HEAD per seat and fail soft on one broken workspace") {
    for
      _ <- fixture
      _ <- IO.blocking {
        // A second ledger row whose directory does not exist: its facts must
        // degrade to an explicit error marker while the healthy seat stays exact.
        WorkspaceLedger.default.register(
          WorkspaceLedger.Workspace(
            "broken",
            "example",
            WorkspaceLedger.repositoryKey(root / "repo"),
            // The path does not exist: toRealPath() would throw, so register the raw path string.
            (root / "missing-tree").toNIO.toString,
            "delegate/broken",
            "main",
            "0" * 40,
            "delegate-broken",
            "kernel"
          )
        )
      }
      snapshot <- request(Method.GET, "/projects/example/workspaces").flatMap(_.as[Json])
      facts = snapshot.hcursor.downField("facts")
      healthy = facts.downField("task")
      _ = assertEquals(healthy.get[Int]("aheadCount").toOption, Some(1), "one committed result commit ahead of main")
      _ = assertEquals(healthy.get[Int]("dirtyTracked").toOption, Some(0))
      _ = assertEquals(healthy.get[Int]("dirtyUntracked").toOption, Some(0))
      _ = assert(healthy.get[String]("headCommit").toOption.exists(_.nonEmpty))
      _ = assert(healthy.get[String]("error").toOption.isEmpty, "a healthy seat carries no error marker")
      _ = assertEquals(healthy.downField("reconcile").as[List[Json]].toOption.getOrElse(Nil).length, 0)
      broken = facts.downField("broken")
      _ = assert(
        broken.get[String]("error").toOption.exists(_.contains("unavailable")),
        "the broken seat carries the failure"
      )
      _ = assertEquals(broken.get[Option[Int]]("aheadCount").toOption.flatten, None, "no fabricated numbers")
      _ = assertEquals(broken.get[Option[String]]("headCommit").toOption.flatten, None)
    yield ()
  }
end ProjectWorkspaceRoutesSpec
