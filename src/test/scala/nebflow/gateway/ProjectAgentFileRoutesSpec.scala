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
 * Project agent 指令 REST 端点契约测试（E.3 双轨移除，2026-09 裁定 13）。
 *
 * 背景更新：canonical = 工作区根 `AGENTS.md`。旧位 `.nebflow/Agent.md` 优先逻辑
 * （2026-09-03 裁定反转）废止——迁移统一在 ProjectStore.load 执行（仅旧位→落根+
 * 删旧；并存→根胜出+旧位保留；symlink→根文件＝已迁移稳态 no-op）。本 spec 钉死：
 *  1. GET：只读根 AGENTS.md——仅旧位项目经 load 迁移后返回迁移内容（旧位已删）
 *  2. GET：两者并存 → 根胜出；symlink→根稳态 → 经链接读同一内容；悬空 symlink 回落根；两处皆无 → 404
 *  3. PUT：只落根 AGENTS.md（仅旧位项目先迁移再写根；并存项目旧位不动）——响应形状不变
 *  4. auth 门禁（无 token → 403）
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

  // ① 仅旧位真文件：load 迁移先行（落根+删旧），GET 返回迁移后的根内容
  test("GET: legacy-only project migrated on load -> returns migrated root content, legacy removed") {
    val ws = tempRoot / "ws-real-priority"
    os.makeDir.all(ws / ".nebflow")
    mkProject("af-real-priority", ws).flatMap { _ =>
      os.remove(ws / "AGENTS.md") // 仅旧位形态
      os.write.over(ws / ".nebflow" / "Agent.md", "# 中文项目指令\n")
      run(authed(Request[IO](Method.GET, Uri.unsafeFromString("/projects/af-real-priority/agent.md"))))
        .flatMap(getBody)
        .map { (status, content) =>
          assertEquals(status, Status.Ok)
          assertEquals(content, "# 中文项目指令\n")
          assertEquals(os.exists(ws / ".nebflow" / "Agent.md"), false, "migration removed legacy")
        }
    }
  }

  // ② 两者并存：根胜出（裁定 13，slideblocks 并存形态读根上游内容）
  test("GET: both files exist -> root AGENTS.md wins (canonical)") {
    val ws = tempRoot / "ws-both-win"
    os.makeDir.all(ws / ".nebflow")
    mkProject("af-both-win", ws).flatMap { _ =>
      os.write.over(ws / "AGENTS.md", "# upstream root content\n")
      os.write.over(ws / ".nebflow" / "Agent.md", "# 中文旧位内容\n")
      run(authed(Request[IO](Method.GET, Uri.unsafeFromString("/projects/af-both-win/agent.md"))))
        .flatMap(getBody)
        .map { (status, content) =>
          assertEquals(status, Status.Ok)
          assertEquals(content, "# upstream root content\n")
          assertEquals(os.read(ws / ".nebflow" / "Agent.md"), "# 中文旧位内容\n") // 并存不删旧位
        }
    }
  }

  // ③ symlink → ../AGENTS.md 稳态：经链接读到根文件同一内容（语义不变）
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

  // PUT ①仅旧位：load 迁移先行（落根+删旧），随后 PUT 落根；旧位不复活
  test("PUT: legacy-only project -> migrated to root first, save lands at root, no legacy revival") {
    val ws = tempRoot / "ws-put-real"
    os.makeDir.all(ws / ".nebflow")
    mkProject("af-put-real", ws).flatMap { _ =>
      os.remove(ws / "AGENTS.md")
      os.write.over(ws / ".nebflow" / "Agent.md", "# 中文旧内容\n") // 仅旧位形态
      val body = io.circe.Json.obj("content" -> "# 中文已保存\n".asJson)
      run(authed(Request[IO](Method.PUT, Uri.unsafeFromString("/projects/af-put-real/agent.md")).withEntity(body)))
        .flatMap(resp => IO(assertEquals(resp.status, Status.Ok)))
        .flatMap { _ =>
          IO {
            assertEquals(os.read(ws / "AGENTS.md"), "# 中文已保存\n", "save lands at canonical root")
            assertEquals(os.exists(ws / ".nebflow" / "Agent.md"), false, "legacy removed by migration")
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

  // PUT ②并存：只落根 AGENTS.md；旧位保留不动（迁移分支②语义：并存不删）
  test("PUT: both exist -> writes root AGENTS.md only, legacy file untouched") {
    val ws = tempRoot / "ws-put-both"
    os.makeDir.all(ws / ".nebflow")
    mkProject("af-put-both", ws).flatMap { _ =>
      os.write.over(ws / "AGENTS.md", "# root before save\n")
      os.write.over(ws / ".nebflow" / "Agent.md", "# legacy stays\n")
      val body = io.circe.Json.obj("content" -> "# root after save\n".asJson)
      run(authed(Request[IO](Method.PUT, Uri.unsafeFromString("/projects/af-put-both/agent.md")).withEntity(body)))
        .flatMap(resp => IO(assertEquals(resp.status, Status.Ok)))
        .flatMap { _ =>
          IO {
            assertEquals(os.read(ws / "AGENTS.md"), "# root after save\n")
            assertEquals(os.read(ws / ".nebflow" / "Agent.md"), "# legacy stays\n")
          }
        }
    }
  }

  // PUT ③symlink → 根稳态：写穿透到根文件内容，链接本身保留（稳态 no-op 后直写根）
  test("PUT: symlink .nebflow/Agent.md -> writes root, link preserved") {
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

  // PUT ④无旧位：写工作区根 AGENTS.md，GET 读回一致
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
