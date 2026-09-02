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
 * Project agent 指令 REST 端点契约测试（读取优先级反转，2026-09-03 裁定）。
 *
 * 背景：AGENTS.md 迁移（2a0dada0）后 slideblocks 偏差回滚——其根 AGENTS.md 是 GitHub 上游英文
 * 内容不可动，中文项目指令保留为 `.nebflow/Agent.md` 真文件。因此读取优先级反转为：
 * **`.nebflow/Agent.md` 存在则优先（symlink 算存在）；缺失回落工作区根 `AGENTS.md`**。钉死：
 *  1. GET：`.nebflow/Agent.md` 真文件存在 → 返回它，根 AGENTS.md 另有内容时不误读（slideblocks）
 *  2. GET：`.nebflow/Agent.md` → `../AGENTS.md` symlink → 经链接读到根内容（已迁移项目语义不变）
 *  3. GET：无 `.nebflow/Agent.md` → 回落根 AGENTS.md；悬空 symlink 同样回落；两处皆无 → 404
 *  4. PUT：落点与 GET 优先位置一致——真文件场景写 `.nebflow/Agent.md`（根不动）；
 *     symlink 场景经链接写真实目标（链接保留）；无文件场景写根 AGENTS.md
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

  /** 相对 symlink `../AGENTS.md`（已迁移项目的真实形状）。 */
  private def mkRelativeAgentsSymlink(ws: os.Path): Unit =
    java.nio.file.Files.createSymbolicLink(
      (ws / ".nebflow" / "Agent.md").toNIO,
      java.nio.file.Paths.get("../AGENTS.md")
    )

  private def getBody(resp: Response[IO]): IO[(Status, String)] =
    resp.as[io.circe.Json].map(j => (resp.status, j.hcursor.downField("content").as[String].getOrElse("")))

  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── tests ───────────────────────────────────────────────

  test("auth gate: no token -> 403") {
    run(Request[IO](Method.GET, Uri.unsafeFromString("/projects/x/agent.md")))
      .map(resp => assertEquals(resp.status, Status.Forbidden))
  }

  // ① 真文件优先：slideblocks 场景——.nebflow/Agent.md 中文真文件命中，根 AGENTS.md（上游英文）不误读
  test("GET: real .nebflow/Agent.md takes priority over root AGENTS.md (slideblocks rollback)") {
    val ws = tempRoot / "ws-real-priority"
    os.makeDir.all(ws / ".nebflow")
    mkProject("af-real-priority", ws).flatMap { _ =>
      os.write.over(ws / "AGENTS.md", "# upstream root content\n") // 根另有内容（不可动）
      os.write.over(ws / ".nebflow" / "Agent.md", "# 中文项目指令\n")
      run(authed(Request[IO](Method.GET, Uri.unsafeFromString("/projects/af-real-priority/agent.md"))))
        .flatMap(getBody)
        .map { (status, content) =>
          assertEquals(status, Status.Ok)
          assertEquals(content, "# 中文项目指令\n")
        }
    }
  }

  // ② symlink 命中：已迁移项目 .nebflow/Agent.md → ../AGENTS.md，经链接读到根文件同一内容
  test("GET: .nebflow/Agent.md symlink to ../AGENTS.md returns root content through the link") {
    val ws = tempRoot / "ws-symlink"
    os.makeDir.all(ws / ".nebflow")
    mkProject("af-symlink", ws).flatMap { _ =>
      os.write.over(ws / "AGENTS.md", "# migrated root content\n")
      mkRelativeAgentsSymlink(ws)
      run(authed(Request[IO](Method.GET, Uri.unsafeFromString("/projects/af-symlink/agent.md"))))
        .flatMap(getBody)
        .map { (status, content) =>
          assertEquals(status, Status.Ok)
          assertEquals(content, "# migrated root content\n")
        }
    }
  }

  // ③ 无文件回落：无 .nebflow/Agent.md → 返回根 AGENTS.md
  test("GET: no .nebflow/Agent.md -> falls back to root AGENTS.md") {
    val ws = tempRoot / "ws-fallback"
    os.makeDir.all(ws)
    mkProject("af-fallback", ws).flatMap { _ =>
      os.write.over(ws / "AGENTS.md", "# root fallback content\n")
      run(authed(Request[IO](Method.GET, Uri.unsafeFromString("/projects/af-fallback/agent.md"))))
        .flatMap(getBody)
        .map { (status, content) =>
          assertEquals(status, Status.Ok)
          assertEquals(content, "# root fallback content\n")
        }
    }
  }

  // 悬空 symlink 兜底：视为不存在 → 回落根 AGENTS.md
  test("GET: dangling .nebflow/Agent.md symlink falls back to root AGENTS.md") {
    val ws = tempRoot / "ws-dangling"
    os.makeDir.all(ws / ".nebflow")
    mkProject("af-dangling", ws).flatMap { _ =>
      os.write.over(ws / "AGENTS.md", "# root behind dangling link\n")
      java.nio.file.Files.createSymbolicLink(
        (ws / ".nebflow" / "Agent.md").toNIO,
        java.nio.file.Paths.get("../no-such-target.md")
      )
      run(authed(Request[IO](Method.GET, Uri.unsafeFromString("/projects/af-dangling/agent.md"))))
        .flatMap(getBody)
        .map { (status, content) =>
          assertEquals(status, Status.Ok)
          assertEquals(content, "# root behind dangling link\n")
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

  // PUT 落点①真文件场景：写 .nebflow/Agent.md（slideblocks 中文指令），根 AGENTS.md 不动；GET 读回一致
  test("PUT: real .nebflow/Agent.md present -> writes legacy file, root AGENTS.md untouched") {
    val ws = tempRoot / "ws-put-real"
    os.makeDir.all(ws / ".nebflow")
    mkProject("af-put-real", ws).flatMap { _ =>
      os.write.over(ws / "AGENTS.md", "# upstream root untouched\n")
      os.write.over(ws / ".nebflow" / "Agent.md", "# 中文旧内容\n") // slideblocks：真文件已存在
      val body = io.circe.Json.obj("content" -> "# 中文已保存\n".asJson)
      run(authed(Request[IO](Method.PUT, Uri.unsafeFromString("/projects/af-put-real/agent.md")).withEntity(body)))
        .flatMap(resp => IO(assertEquals(resp.status, Status.Ok)))
        .flatMap { _ =>
          IO {
            assertEquals(os.read(ws / ".nebflow" / "Agent.md"), "# 中文已保存\n")
            assertEquals(os.read(ws / "AGENTS.md"), "# upstream root untouched\n")
          }
        }
        .flatMap { _ =>
          run(authed(Request[IO](Method.GET, Uri.unsafeFromString("/projects/af-put-real/agent.md"))))
            .flatMap(getBody)
            .map { (status, content) =>
              assertEquals(status, Status.Ok)
              assertEquals(content, "# 中文已保存\n")
            }
        }
    }
  }

  // PUT 落点②symlink 场景：经链接写真实目标（根 AGENTS.md 内容更新），链接本身保留不破坏
  test("PUT: symlink .nebflow/Agent.md -> writes through link to root, link preserved") {
    val ws = tempRoot / "ws-put-link"
    os.makeDir.all(ws / ".nebflow")
    mkProject("af-put-link", ws).flatMap { _ =>
      os.write.over(ws / "AGENTS.md", "# root before save\n")
      mkRelativeAgentsSymlink(ws)
      val body = io.circe.Json.obj("content" -> "# root after save\n".asJson)
      run(authed(Request[IO](Method.PUT, Uri.unsafeFromString("/projects/af-put-link/agent.md")).withEntity(body)))
        .flatMap(resp => IO(assertEquals(resp.status, Status.Ok)))
        .flatMap { _ =>
          IO {
            assertEquals(os.read(ws / "AGENTS.md"), "# root after save\n") // 落点=链接目标（根）
            assertEquals(os.isLink(ws / ".nebflow" / "Agent.md"), true) // 链接未被原子替换破坏
          }
        }
        .flatMap { _ =>
          run(authed(Request[IO](Method.GET, Uri.unsafeFromString("/projects/af-put-link/agent.md"))))
            .flatMap(getBody)
            .map { (status, content) =>
              assertEquals(status, Status.Ok)
              assertEquals(content, "# root after save\n")
            }
        }
    }
  }

  // PUT 落点③无文件场景：写工作区根 AGENTS.md，GET 回落读到同一内容
  test("PUT: no .nebflow/Agent.md -> writes root AGENTS.md; GET then returns it") {
    val ws = tempRoot / "ws-put-root"
    os.makeDir.all(ws)
    mkProject("af-put-root", ws).flatMap { _ =>
      val body = io.circe.Json.obj("content" -> "# saved to root\n".asJson)
      run(authed(Request[IO](Method.PUT, Uri.unsafeFromString("/projects/af-put-root/agent.md")).withEntity(body)))
        .flatMap(resp => IO(assertEquals(resp.status, Status.Ok)))
        .flatMap { _ =>
          IO {
            assertEquals(os.read(ws / "AGENTS.md"), "# saved to root\n")
            assertEquals(os.exists(ws / ".nebflow" / "Agent.md"), false) // 不额外长出旧位置文件
          }
        }
        .flatMap { _ =>
          run(authed(Request[IO](Method.GET, Uri.unsafeFromString("/projects/af-put-root/agent.md"))))
            .flatMap(getBody)
            .map { (status, content) =>
              assertEquals(status, Status.Ok)
              assertEquals(content, "# saved to root\n")
            }
        }
    }
  }

end ProjectAgentFileRoutesSpec
