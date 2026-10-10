package nebflow.gateway

import cats.effect.IO
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.core.project.{ProjectActor, ProjectDef, ProjectOrigin, ProjectStore}
import nebflow.shared.{NebflowServiceConfig, PathUtil, ServiceLlmConfig}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*

import java.nio.file.Files

/**
 * 面C 数据源缺口（UI 面批 2026-10-10 · 设计稿 §3/§6 后端②）两条 additive 出口钉：
 *
 *   - E1 `ProjectActor.projectCreatedFrame` 的 `row` additive `origin{device,path}|null`
 *     —— 与 GET /api/projects 行同构纪律（键名对齐 project.json Codec）；
 *   - E2 `GET /api/projects` 行 additive `origin`：远端项目 = `{device,path}`；
 *     存量本机项目（project.json 无 origin 键）= **显式 `null`**（不是缺键 —— 老前端
 *     忽略未知键零破坏，新前端 null/缺键同读数）。
 *
 * 🔴 只读运行：数据根隔离 temp（PathUtil.setDataRoot），零端口零 HOME 写。
 */
class ProjectOriginRowSpec extends CatsEffectSuite:

  private val tempRoot: os.Path = os.Path(Files.createTempDirectory("nb-origin-row"))
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    os.remove.all(tempRoot)

  private val Token = "test-token-origin-row"
  private val bearer = Headers("Authorization" -> s"Bearer $Token")

  private def run(req: Request[IO]): IO[(Status, Json)] =
    new RestApiRoutes(
      token = Token,
      configRef = cats.effect.Ref.unsafe[IO, NebflowServiceConfig](
        NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
      ),
      sharedResources = null,
      sessionStore = null,
      wsRoutes = null
    ).routes(req).value.map(_.getOrElse(fail("route fell through"))).flatMap { resp =>
      resp.as[Json].map(json => (resp.status, json))
    }

  test("E1 projectCreatedFrame.row additive origin：远端 = {device,path}；本机 = null") {
    val remote = ProjectDef(
      name = "remote-proj",
      workspace = "/remote/ws",
      agentFile = "/remote/ws/AGENTS.md",
      createdAt = 1L,
      origin = Some(ProjectOrigin("dev-remote-9", "/remote/ws"))
    )
    val frame = ProjectActor.projectCreatedFrame(remote, mounted = false)
    val row = frame.hcursor.downField("row")
    assertEquals(
      row.downField("origin").downField("device").as[String].toOption,
      Some("dev-remote-9"),
      "row.origin.device additive（键名对齐 project.json Codec）"
    )
    assertEquals(row.downField("origin").downField("path").as[String].toOption, Some("/remote/ws"))
    assertEquals(row.downField("name").as[String].toOption, Some("remote-proj"), "五键存量不缺")

    val local = remote.copy(origin = None)
    val localRow = ProjectActor.projectCreatedFrame(local, mounted = true).hcursor.downField("row")
    assert(
      localRow.downField("origin").focus.exists(_.isNull),
      "本机项目 row.origin = 显式 null（同构纪律，前端 null/缺键同读数）"
    )
  }

  test("E2 GET /api/projects 行 additive origin：远端项目带 {device,path}；存量本机 = null") {
    val remoteWs = tempRoot / "ws" / "origin-remote"
    os.makeDir.all(remoteWs)
    val _ = ProjectStore
      .createWithScaffold(
        "origin-remote",
        remoteWs.toString,
        None,
        "",
        origin = Some(ProjectOrigin("dev-remote-9", remoteWs.toString))
      )
      .map(_.toOption.get)
      .unsafeRunSync()

    val legacyWs = tempRoot / "ws" / "origin-legacy"
    os.makeDir.all(legacyWs)
    val _ = ProjectStore
      .createWithScaffold("origin-legacy", legacyWs.toString, None, "")
      .map(_.toOption.get)
      .unsafeRunSync()
    // 存量形态：手写旧盘面（无 origin 键）—— 直接覆写 project.json 钉零迁移读数。
    os.write.over(
      ProjectStore.projectJsonPath("origin-legacy"),
      """{"name":"origin-legacy","workspace":"WS","agentFile":"WS/AGENTS.md","createdAt":1}"""
    )

    run(Request[IO](Method.GET, Uri.unsafeFromString("/projects")).withHeaders(bearer)).map {
      case (status, body) =>
        assertEquals(status, Status.Ok)
        val rows = body.hcursor.downField("projects").as[List[Json]].toOption.getOrElse(Nil)
        val byName = rows.flatMap(r => r.hcursor.get[String]("name").toOption.map(_ -> r)).toMap
        val remoteRow = byName("origin-remote")
        assertEquals(
          remoteRow.hcursor.downField("origin").downField("device").as[String].toOption,
          Some("dev-remote-9"),
          "REST 行 origin.device additive（面C 徽标数据源）"
        )
        assertEquals(
          remoteRow.hcursor.downField("origin").downField("path").as[String].toOption,
          Some(remoteWs.toString)
        )
        val legacyRow = byName("origin-legacy")
        assert(
          legacyRow.hcursor.downField("origin").focus.exists(_.isNull),
          "存量项目（无 origin 键盘面）读数 = null（零迁移）"
        )
    }
  }

end ProjectOriginRowSpec
