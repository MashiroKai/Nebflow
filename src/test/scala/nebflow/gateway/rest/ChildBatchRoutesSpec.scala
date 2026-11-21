package nebflow.gateway.rest

import nebflow.gateway.*

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.agent.SharedResources
import nebflow.core.SessionStore
import nebflow.core.compact.HistoryArchiver
import nebflow.core.delegate.DelegateRegistry
import nebflow.core.tools.FileLockManager
import nebflow.llm.ModelCandidate
import nebflow.shared.*
import org.http4s.*
import org.http4s.server.Router
import org.typelevel.ci.CIString

/**
 * `GET /api/child-batch`（手机面板卡顿修复批 2026-11-21 · 根因一）。
 *
 * 修前面板每渲一轮都对**每个父**各发一条 `/api/delegates/<id>/children`
 * （诊断批实测一拍 116+ 条，屏蔽该族后 9.9fps → 119.8fps）。本端点是它的
 * 批量替身（与 #2702 `/api/chain-digests` 同一手法）：**一次请求**聚合全部父。
 *
 * 钉住的判据（任何一条退回 N+1 都会红）：
 *   · 一次请求覆盖多个父，且每个父的子集与单父腿**逐字相同**（同源 findByParent）；
 *   · 未在册的父**缺席**而非空数组——「无读数」与「确无子」必须可分辨
 *     （前端据此保旧值 vs 记结果，与单父腿 404 同源）；
 *   · 超过上限 ⇒ 400 显式拒绝，**绝不**静默截断丢父；
 *   · 空 `ids` ⇒ 合法空结果（不是 400）。
 */
class ChildBatchRoutesSpec extends CatsEffectSuite:

  private val TestToken = "test-token-child-batch"
  private val tempRoot: os.Path = os.pwd / "target" / "test-child-batch-routes"

  override def beforeAll(): Unit =
    PathUtil.setDataRoot(tempRoot)
    os.remove.all(tempRoot)
    os.makeDir.all(tempRoot / "sessions")

  private val store = SessionStore(tempRoot / "sessions", tempRoot / "tasks")

  private lazy val taskStore = new nebflow.agent.SubAgentTaskStore(tempRoot / "subagent-tasks")

  private lazy val routes = new RestApiRoutes(
    tokenRef = cats.effect.Ref.unsafe[IO, String](TestToken),
    configRef = cats.effect.Ref.unsafe[IO, NebflowServiceConfig](
      NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
    ),
    sharedResources = mkResources,
    sessionStore = store,
    wsRoutes = null
  )

  /** `SharedResources` has 30+ dependencies; only the two this route reads matter.
   *  A null-filled projection is honest here because the spec asserts the route's
   *  aggregation semantics, not the whole engine (same approach as peer route specs). */
  private def mkResources: SharedResources =
    SharedResources(
      llm = null,
      dispatcher = null,
      sessionStore = store,
      projectRoot = os.pwd,
      thinkingConfigRef = cats.effect.Ref.unsafe[IO, ThinkingConfig](ThinkingConfig()),
      rateLimiter = null,
      fileChangeTracker = null,
      fileChangeLedger = null,
      contextWindow = 100_000,
      agentLibrary = null,
      taskStore = nebflow.core.task.FileTaskStore,
      historyArchiver = HistoryArchiver.fileSystem(tempRoot / "archives"),
      fileLockManager = FileLockManager.create.unsafeRunSync(),
      sessionModelOverrides = cats.effect.Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      hookEngine = null,
      bridgeManager = None,
      scheduledTaskStore = null,
      doneRecordStore = null,
      neblinkService = None,
      friendService = None,
      attachUploads = null,
      dropboxService = None,
      scheduledTaskService = None,
      daemonService = None,
      knowledgeStore = null,
      subAgentTaskStore = taskStore,
      usageRecordStore = null,
      decisionHealth = null,
      voiceMutedRef = cats.effect.Ref.unsafe[IO, Boolean](false),
      lastWsActivity = cats.effect.Ref.unsafe[IO, Long](0L),
      runtimeModels = cats.effect.Ref.unsafe[IO, Map[String, String]](Map.empty),
      agentRegistry = cats.effect.Ref.unsafe[IO, Map[String, nebflow.actor.AgentRecord]](Map.empty)
    )

  private def req(path: String): Request[IO] =
    Request[IO](Method.GET, Uri.unsafeFromString(path))
      .putHeaders(Header.Raw(CIString("Authorization"), s"Bearer $TestToken"))

  /** `/child-batch` 住在 presence 表（与 `/session-children`、`/subagent-tasks` 同表），
   *  单探 `routes.routes` 会全量 fall through——挂载面必须两表并上。 */
  private lazy val mounted: HttpRoutes[IO] =
    routes.routes <+> routes.presenceWsRoutes(
      null.asInstanceOf[org.http4s.server.websocket.WebSocketBuilder2[IO]]
    )

  private def get(path: String): Option[Response[IO]] =
    mounted(req(path)).value.unsafeRunSync()

  private def serve(path: String): (Status, Json) =
    get(path) match
      case None => (Status.NotFound, Json.Null)
      case Some(r) =>
        val body = r.bodyText.compile.string.unsafeRunSync()
        (r.status, io.circe.parser.parse(body).getOrElse(Json.Null))

  private def seeded(): List[String] =
    // Three parents with a real registry record + real child rows; one id that is
    // NOT on record. The registry record is required by the route (same 404
    // semantics as the single-parent leg), so seeding only task rows would be a
    // different scenario than production.
    val parents = List("delegate-nebflow-aaaa1111", "delegate-nebflow-bbbb2222", "node-cccc3333")
    parents.foreach { p =>
      DelegateRegistry.put(
        DelegateRegistry.Record(
          id = p,
          executor = "nebflow",
          cwd = tempRoot.toString,
          project = None,
          taskId = None,
          parentSessionId = "delegate-nebflow-aaaa1111",
          title = s"fixture $p",
          createdAt = 1L
        )
      )
      taskStore
        .saveTasks(
          p,
          List(
            nebflow.shared.SubAgentTask(
              taskId = s"$p-child-1",
              parentSessionId = p,
              agentName = "child-agent",
              prompt = "",
              description = "",
              status = "completed",
              retryCount = 0,
              spawnedAt = 1L,
              completedAt = Some(2L),
              lastError = None,
              source = "subagent",
              rootSessionId = Some("delegate-nebflow-aaaa1111")
            )
          )
        )
        .unsafeRunSync()
    }
    parents

  test("one request returns every requested parent's children") {
    val parents = seeded()
    val (st, body) = serve(s"/child-batch?ids=${parents.mkString(",")}")
    assertEquals(st, Status.Ok)
    val map = body.hcursor.downField("children").as[Json].toOption.getOrElse(Json.Null)
    parents.foreach { p =>
      val kids = map.hcursor.downField(p).as[List[Json]].toOption
      assert(kids.isDefined, s"parent $p must be present: ${body.noSpaces.take(200)}")
      assertEquals(kids.get.size, 1, s"parent $p must carry exactly its one child")
    }
    assertEquals(body.hcursor.downField("count").as[Int].toOption, Some(parents.size))
  }

  test("unknown parent is ABSENT, never an empty array (no-reading vs no-children)") {
    val parents = seeded()
    val (_, body) = serve(s"/child-batch?ids=${parents.head},delegate-nebflow-doesnot9")
    val map = body.hcursor.downField("children").as[Json].toOption.getOrElse(Json.Null)
    assertEquals(
      map.hcursor.downField("delegate-nebflow-doesnot9").focus,
      None,
      "an unregistered parent must be absent so the client keeps its old value"
    )
    assertEquals(map.hcursor.downField(parents.head).as[List[Json]].toOption.map(_.size), Some(1))
  }

  test("over-the-limit request is refused outright (never a silent drop)") {
    val many = (1 to 200).map(i => s"delegate-nebflow-%08d".format(i)).mkString(",")
    val (st, _) = serve(s"/child-batch?ids=$many")
    assertEquals(st, Status.BadRequest)
  }

  test("empty ids is a legal empty result, not an error") {
    val (st, body) = serve("/child-batch?ids=")
    assertEquals(st, Status.Ok)
    assertEquals(body.hcursor.downField("count").as[Int].toOption, Some(0))
  }

end ChildBatchRoutesSpec
