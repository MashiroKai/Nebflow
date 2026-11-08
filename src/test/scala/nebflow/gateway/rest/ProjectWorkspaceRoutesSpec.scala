package nebflow.gateway

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import scala.concurrent.duration.*
import nebflow.actor.{ActorSystem, AgentCommand, AgentKind, Behavior, Behaviors}
import nebflow.core.delegate.{DelegateRegistry, WorkspaceCleanup}
import nebflow.core.mail.SessionMailbox
import nebflow.core.tools.{MergerReportTool, ToolContext}
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

  test("HTTP request reaches the merger mailbox actor and persisted reports appear in the project snapshot") {
    val system = ActorSystem("project-merger-route")
    val id = "delegate-route-merger"
    val requestId = "route-request"
    val body = Json.obj("requestId" -> requestId.asJson, "workspaceIds" -> List("task").asJson)
    val exercise = for
      pair <- fixture
      (repo, tree) = pair
      _ <- IO.blocking(installMerger(repo, id))
      completed <- Deferred[IO, Either[Throwable, Unit]]
      calls <- Ref.of[IO, Int](0)
      toolContext = ToolContext(projectRoot = repo.toString, sessionId = Some(id))
      report = (phase: String, merge: Option[String], proof: Option[String]) =>
        val revision = WorkspaceLedger.default.snapshot.requests.find(_.id == requestId).get.revision
        MergerReportTool
          .call(
            Json
              .obj(
                "action" -> "report".asJson,
                "requestId" -> requestId.asJson,
                "eventId" -> phase.asJson,
                "expectedRevision" -> revision.asJson,
                "workspaceId" -> "task".asJson,
                "phase" -> phase.asJson,
                "mergeCommit" -> merge.asJson,
                "verification" -> proof.asJson
              )
              .asObject
              .get,
            toolContext
          )
          .flatMap(result => IO.fromEither(result.left.map(e => new IllegalStateException(e.toString))).void)
      process = for
        entries <- SessionMailbox.scanPending(id).flatMap(SessionMailbox.take(id, _))
        _ <- IO(assertEquals(entries.map(_.identity), List(s"merger-request:$requestId")))
        _ <- calls.update(_ + 1)
        _ <- report("checking", None, None)
        _ <- report("merging", None, None)
        merge <- IO.blocking {
          os.proc("git", "merge", "--no-ff", "delegate/task", "-m", "isolated actor integration")
            .call(cwd = repo, stdout = os.Pipe, stderr = os.Pipe)
          os.proc("git", "rev-parse", "HEAD").call(cwd = repo).out.text().trim
        }
        _ <- report("validating", Some(merge), None)
        proof <- IO.blocking {
          os.proc("git", "diff", "--check", s"$merge^1", merge).call(cwd = repo, stdout = os.Pipe, stderr = os.Pipe)
          val source = WorkspaceLedger.default.snapshot.requests.head.items.head.sourceCommit
          val path = repo / ".nebflow" / "evidence" / "actor-verification.json"
          os.makeDir.all(path / os.up)
          os.write(
            path,
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
          path.toString
        }
        _ <- report("merged-cleanup-pending", Some(merge), Some(proof))
        _ <- IO.blocking {
          val cleanup = new WorkspaceCleanup(WorkspaceLedger.default)
          val preview = cleanup.inspect("task").toOption.get
          assert(!preview.unmerged)
          assert(cleanup.remove("task", preview.previewToken, false, false).isRight)
        }
        _ <- report("completed", Some(merge), Some(proof))
        _ <- SessionMailbox.unlinkTaken(id, entries.map(_.identity))
      yield ()
      behavior =
        def loop: Behavior[AgentCommand] = Behaviors.receiveMessage {
          case AgentCommand.MailboxArrived =>
            SessionMailbox.scanPending(id).flatMap { entries =>
              if entries.isEmpty then IO.pure(loop)
              else process.attempt.flatMap(result => completed.complete(result).as(loop))
            }
          case _ => IO.pure(loop)
        }
        loop
      actor <- system.spawn(behavior, "merger")
      _ <- nebflow.core.node.NodeRunner.registerAgent(resources, id, actor, AgentKind.Delegate, "root")
      response <- request(Method.POST, "/projects/example/workspaces/merge-requests", body)
      _ = assertEquals(response.status, Status.Ok)
      _ <- completed.get.timeout(10.seconds).flatMap(IO.fromEither)
      snapshot <- request(Method.GET, "/projects/example/workspaces").flatMap(_.as[Json])
      _ = assertEquals(snapshot.hcursor.downField("mergeRequests").downArray.get[String]("status"), Right("completed"))
      _ = assertEquals(
        snapshot.hcursor.downField("workspaces").downArray.get[String]("lifecycle"),
        Right("closed-merged")
      )
      _ = assert(!os.exists(tree))
      _ = assertEquals(os.read(repo / "result.txt"), "committed result")
      retried <- request(Method.POST, "/projects/example/workspaces/merge-requests", body)
      _ = assertEquals(retried.status, Status.Ok)
      _ <- (actor ! AgentCommand.MailboxArrived)
      pending <- SessionMailbox.scanPending(id)
      _ = assert(pending.isEmpty, "retry after consumption must not create another merge task")
      count <- calls.get
      _ = assertEquals(count, 1)
      _ = assertEquals(WorkspaceLedger.default.snapshot.requests.size, 1)
    yield ()
    exercise.guarantee(system.stopAll)
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
end ProjectWorkspaceRoutesSpec
