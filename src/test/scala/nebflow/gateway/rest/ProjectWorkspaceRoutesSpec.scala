package nebflow.gateway

import cats.effect.{IO, Ref}
import nebflow.llm.ModelCandidate
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.agent.SharedResources
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

  private def requestOpt(
    method: Method,
    path: String,
    body: Json = Json.obj(),
    auth: Boolean = true
  ): IO[Option[Response[IO]]] =
    val req = Request[IO](method, Uri.unsafeFromString(path)).withEntity(body)
    val authenticated =
      if auth then req.putHeaders(Header.Raw(org.typelevel.ci.CIString("Authorization"), "Bearer workspace-test"))
      else req
    routes(authenticated).value

  test("the managed-workspace faces are gone from the route table end to end (view, cleanup, merge requests, baseline)") {
    // 3007e48da（2026-11-13 退役托管工作区机制）：托管工作区/merger/清理/基线整族
    // 机制 end to end 摘除（提交语「Remove the managed-workspace mechanism end to
    // end」），路由**壳**也随之消失。改前本 spec 钉的是「视图渲染台账 + 变更面
    // 404」——两者的世界都已退役：现行「gone」正钉 = 路由表 fall-through（None），
    // 读视图腿随其世界删除（removed-mechanism spec 同删纪律）。
    for
      view <- requestOpt(Method.GET, "/api/projects/example/workspaces")
      cleanup <- requestOpt(Method.GET, "/api/projects/example/workspaces/task/cleanup")
      submit <- requestOpt(Method.POST, "/api/projects/example/workspaces/merge-requests")
      cancel <- requestOpt(Method.POST, "/api/projects/example/workspaces/merge-requests/req-1/cancel")
      baseline <- requestOpt(Method.PUT, "/api/projects/example/workspaces/baseline")
    yield
      assertEquals(view, None)
      assertEquals(cleanup, None)
      assertEquals(submit, None)
      assertEquals(cancel, None)
      assertEquals(baseline, None)
  }

end ProjectWorkspaceRoutesSpec
