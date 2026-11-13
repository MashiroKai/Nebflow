package nebflow.gateway

import cats.effect.{IO, Ref}
import nebflow.llm.ModelCandidate
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.agent.SharedResources
import nebflow.core.project.ProjectStore
import nebflow.shared.{NebflowServiceConfig, PathUtil, ServiceLlmConfig}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*

/**
 * Compat-read spec for the project workspace REST face: the managed-workspace
 * mechanism is retired, so the endpoint only renders what the legacy ledger
 * recorded — and every mutating face (cleanup, merge requests, baseline) is
 * gone from the route table.
 */
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

  private def fixture: IO[os.Path] =
    val repo = root / "repo"
    ProjectStore
      .create("example", repo.toString, None, "# Project instructions")
      .flatMap { created => IO(assert(created.isRight)).as(repo) }

  test("the workspaces view renders the recorded legacy ledger rows read-only") {
    for
      repo <- fixture
      _ <- IO.blocking {
        os.makeDir.all(PathUtil.dataRoot / "workspaces")
        os.write.over(
          PathUtil.dataRoot / "workspaces" / "ledger.json",
          """{
            |  "workspaces": [{
            |    "id": "task", "project": "example", "repository": "legacy-repo-key",
            |    "path": "/tmp/task", "branch": "delegate/task", "baseBranch": "main",
            |    "baseCommit": "0000000000000000000000000000000000000000",
            |    "owner": "delegate-owner", "ownerMode": "kernel",
            |    "lifecycle": "merged-cleanup-pending", "activity": "idle", "revision": 4
            |  }],
            |  "mergers": [],
            |  "baselines": {},
            |  "requests": [{
            |    "id": "req-old", "repository": "legacy-repo-key", "mergerId": "delegate-merger",
            |    "authorization": "recorded", "items": [], "status": "claimed",
            |    "revision": 1, "cancelRequested": false, "events": {}
            |  }],
            |  "cleanups": {}, "revision": 9, "mergerBootstraps": {}, "mergerEvents": {},
            |  "completionConfirmations": {}
            |}""".stripMargin
        )
      }
      response <- request(Method.GET, "/api/projects/example/workspaces")
      body <- response.as[Json]
      rows = body.hcursor.downField("workspaces").as[List[Json]].getOrElse(Nil)
      reqs = body.hcursor.downField("mergeRequests").as[List[Json]].getOrElse(Nil)
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(rows.map(_.hcursor.get[String]("id").toOption), List(Some("task")))
      assertEquals(rows.head.hcursor.get[String]("lifecycle").toOption, Some("merged-cleanup-pending"))
      assertEquals(reqs.map(_.hcursor.get[String]("id").toOption), List(Some("req-old")))
      // The advisor/facts faces are retired: degraded keys, not errors.
      assertEquals(body.hcursor.downField("suggestions").as[List[Json]].getOrElse(Nil), Nil)
  }

  test("the mutating workspace faces are gone from the route table (cleanup, merge requests, baseline)") {
    for
      repo <- fixture
      cleanup <- request(Method.GET, "/api/projects/example/workspaces/task/cleanup")
      submit <- request(Method.POST, "/api/projects/example/workspaces/merge-requests")
      cancel <- request(Method.POST, "/api/projects/example/workspaces/merge-requests/req-1/cancel")
      baseline <- request(Method.PUT, "/api/projects/example/workspaces/baseline")
    yield
      assertEquals(cleanup.status, Status.NotFound)
      assertEquals(submit.status, Status.NotFound)
      assertEquals(cancel.status, Status.NotFound)
      assertEquals(baseline.status, Status.NotFound)
  }

end ProjectWorkspaceRoutesSpec
