package nebflow.gateway

import cats.effect.IO
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.agent.SharedResources
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.project.{ProjectStore, ProjectRuntimeRegistry}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.llm.{ModelCandidate, NebflowServiceConfig, ServiceLlmConfig, ThinkingConfig}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

import scala.concurrent.duration.*

/**
 * Project AGENTS.md REST 端点契约测试（AGENTS.md 迁移，2026-09-02 裁定）。
 *
 * 磁盘位置从 `<workspace>/.nebflow/Agent.md` 迁移到工作区根 `AGENTS.md`（URL 不变）。钉死：
 *  1. GET：工作区根 AGENTS.md 存在 → 返回其内容（新位置优先）
 *  2. GET：仅剩旧 .nebflow/Agent.md → 仍 200 读旧（AC-5 迁移兼容）
 *  3. GET：两者皆无 → 404
 *  4. PUT：写入工作区根 AGENTS.md（旧位置保留不动，下次 GET 新位置优先——保存即迁移）
 *  5. auth 门禁（无 token → 403）
 */
class ProjectAgentFileRoutesSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val TestToken = "test-token-123"
  private val tempRoot: os.Path = os.pwd / "target" / "test-project-agentfile-routes"

  // 全局 dataRoot 指向临时目录（同 ProjectStoreSpec 模式），类级设一次
  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  // ── routes under test（仿 FriendApiRoutesSpec 轻量装配）──

  private def mkResources: SharedResources =
    SharedResources(
      llm = null,
      dispatcher = null,
      sessionStore = null,
      projectRoot = os.pwd,
      thinkingConfigRef = cats.effect.Ref.unsafe[IO, ThinkingConfig](ThinkingConfig()),
      rateLimiter = null,
      fileChangeTracker = null,
      contextWindow = 100_000,
      agentLibrary = null,
      taskStore = FileTaskStore,
      historyArchiver = HistoryArchiver.fileSystem(os.temp.dir() / "agentfile-spec-archives"),
      fileLockManager = FileLockManager.create.unsafeRunSync(),
      sessionModelOverrides = cats.effect.Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = cats.effect.Ref.unsafe[IO, Boolean](false),
      friendService = None
    )

  private val routes = new RestApiRoutes(
    token = TestToken,
    configRef = cats.effect.Ref.unsafe[IO, NebflowServiceConfig](
      NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
    ),
    sharedResources = mkResources,
    sessionStore = null,
    wsRoutes = null
  )

  private def authed(req: Request[IO]): Request[IO] =
    req.withHeaders(Headers("Authorization" -> s"Bearer $TestToken"))

  private def run(req: Request[IO]): IO[Response[IO]] =
    routes.routes(req).value.map(_.getOrElse(fail("route fell through")))

  private def mkProject(name: String, ws: os.Path): IO[Unit] =
    ProjectStore.create(name, ws.toString, None, s"# $name template\n").map {
      case Right(_)  => ()
      case Left(err) => fail(s"create failed: $err")
    }

  private def getBody(resp: Response[IO]): IO[(Status, String)] =
    resp.as[io.circe.Json].map(j => (resp.status, j.hcursor.downField("content").as[String].getOrElse("")))

  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── tests ───────────────────────────────────────────────

  test("auth gate: no token -> 403") {
    run(Request[IO](Method.GET, Uri.unsafeFromString("/projects/x/agent.md")))
      .map(resp => assertEquals(resp.status, Status.Forbidden))
  }

  test("GET: workspace root AGENTS.md preferred over legacy .nebflow/Agent.md") {
    val ws = tempRoot / "ws-new"
    os.makeDir.all(ws / ".nebflow")
    mkProject("af-new", ws).flatMap { _ =>
      os.write.over(ws / ".nebflow" / "Agent.md", "# legacy old content\n")
      os.write.over(ws / "AGENTS.md", "# new root content\n")
      run(authed(Request[IO](Method.GET, Uri.unsafeFromString("/projects/af-new/agent.md"))))
        .flatMap(getBody)
        .map { (status, content) =>
          assertEquals(status, Status.Ok)
          assertEquals(content, "# new root content\n")
        }
    }
  }

  test("GET: legacy-only .nebflow/Agent.md still returns 200 (AC-5 migration compat)") {
    val ws = tempRoot / "ws-legacy"
    os.makeDir.all(ws / ".nebflow")
    mkProject("af-legacy", ws).flatMap { _ =>
      os.remove(ws / "AGENTS.md") // 模拟未迁移旧项目：仅剩旧位置文件（create 已写根模板，删掉）
      os.write.over(ws / ".nebflow" / "Agent.md", "# legacy only\n")
      run(authed(Request[IO](Method.GET, Uri.unsafeFromString("/projects/af-legacy/agent.md"))))
        .flatMap(getBody)
        .map { (status, content) =>
          assertEquals(status, Status.Ok)
          assertEquals(content, "# legacy only\n")
        }
    }
  }

  test("GET: neither file -> 404") {
    val ws = tempRoot / "ws-none"
    os.makeDir.all(ws)
    mkProject("af-none", ws).flatMap { _ =>
      os.remove(ws / "AGENTS.md") // create 已写根模板，删掉以验证两处皆无
      run(authed(Request[IO](Method.GET, Uri.unsafeFromString("/projects/af-none/agent.md"))))
        .map(resp => assertEquals(resp.status, Status.NotFound))
    }
  }

  test("PUT: writes workspace root AGENTS.md; legacy file untouched; GET then prefers new (save = migration)") {
    val ws = tempRoot / "ws-put"
    os.makeDir.all(ws / ".nebflow")
    mkProject("af-put", ws).flatMap { _ =>
      os.write.over(ws / ".nebflow" / "Agent.md", "# legacy pre-save\n")
      val body = io.circe.Json.obj("content" -> "# saved to root\n".asJson)
      run(authed(Request[IO](Method.PUT, Uri.unsafeFromString("/projects/af-put/agent.md")).withEntity(body)))
        .flatMap(resp => IO(assertEquals(resp.status, Status.Ok)))
        .flatMap { _ =>
          IO {
            assertEquals(os.read(ws / "AGENTS.md"), "# saved to root\n")
            assertEquals(os.read(ws / ".nebflow" / "Agent.md"), "# legacy pre-save\n") // 旧文件保留不动
          }
        }
        .flatMap { _ =>
          run(authed(Request[IO](Method.GET, Uri.unsafeFromString("/projects/af-put/agent.md"))))
            .flatMap(getBody)
            .map { (status, content) =>
              assertEquals(status, Status.Ok)
              assertEquals(content, "# saved to root\n")
            }
        }
    }
  }

end ProjectAgentFileRoutesSpec
