package nebflow.gateway

import cats.effect.{IO, Ref}
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.agent.SharedResources
import nebflow.core.delegate.WorkspaceLedger
import nebflow.core.project.ProjectStore
import nebflow.llm.ModelCandidate
import nebflow.shared.{NebflowServiceConfig, PathUtil, ServiceLlmConfig}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*

class ProjectWorkspaceRoutesSpec extends CatsEffectSuite:
  private val previous = PathUtil.dataRoot
  private var root: os.Path = os.pwd

  override def beforeEach(context: BeforeEach): Unit =
    root = os.temp.dir(prefix = "project-workspace-routes-")
    PathUtil.setDataRoot(root / "data")

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(previous)
    os.remove.all(root)

  private def resources = SharedResources(
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
end ProjectWorkspaceRoutesSpec
