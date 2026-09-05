package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.agent.SharedResources
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.llm.{ModelCandidate, NebflowServiceConfig, ServiceLlmConfig, ThinkingConfig}
import org.http4s.circe.CirceEntityDecoder.circeEntityDecoder
import org.http4s.{Headers, Method, Request, Response, Status, Uri}

import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Agent 面板数据源收敛 spec（2026-09-05 08:40 作者裁定）。
 *
 * GET /api/agents 从三层聚合（global+team+flow）收敛为仅 global 层：
 *  - global 层以 agent.json 存在为准（EntityLoader.loadAgentFromDir 无
 *    agent.json 即 None 丢弃）——.archived 目录、仅 memory.md 的惰性残留、
 *    空目录天然不出现；
 *  - team/flow 两层聚合是面板污染源（team/flow 入口已随 sidebar flag 封存）
 *    ——teams 与 flows 各自的 agents 域 agent 不得再出现在面板。
 *
 * fixture：三 keeper（Nebula/project-dispatcher/general）+ 三类干扰目录 +
 * teams/flows 各一个域 agent → 断言恰三 keeper、layer 恒 "global"、无 scope。
 */
class AgentPanelConvergenceSpec extends CatsEffectSuite:

  private val TestToken = "panel-convergence-token"

  private val Keepers = Set("Nebula", "project-dispatcher", "general")

  private def writeAgentJson(dir: os.Path, name: String): Unit =
    os.makeDir.all(dir)
    os.write(dir / "agent.json", s"""{"name":"$name","description":"fixture $name"}""")
    os.write(dir / "system.md", s"system prompt for $name")

  /** dataRoot 注入 + 实体植入；teardown 恢复原 dataRoot 并清 temp。 */
  private def withFixture[A](test: os.Path => IO[A]): A =
    val tmp = os.temp.dir(dir = os.Path(Files.createTempDirectory("nb-panel-spec").toString))
    val originalRoot = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp)
    try
      // 三 keeper（global agents/）
      Keepers.foreach(n => writeAgentJson(tmp / "agents" / n, n))
      // 干扰目录 ①：仅 agent.json.archived（归档残留）
      os.makeDir.all(tmp / "agents" / "ExplorerX")
      os.write(tmp / "agents" / "ExplorerX" / "agent.json.archived", """{"name":"ExplorerX"}""")
      // 干扰目录 ②：仅 memory.md（无 agent.json 惰性残留）
      os.makeDir.all(tmp / "agents" / "MailX")
      os.write(tmp / "agents" / "MailX" / "memory.md", "stale memory")
      // 干扰目录 ③：空目录
      os.makeDir.all(tmp / "agents" / "emptyX")
      // team / flow 域 agent（旧聚合污染源）
      writeAgentJson(tmp / "teams" / "fixture-team" / "agents" / "domain-agent", "domain-agent")
      writeAgentJson(tmp / "flows" / "fixture-flow" / "agents" / "flow-agent", "flow-agent")
      test(tmp).unsafeRunSync()
    finally
      PathUtil.setDataRoot(originalRoot)
      os.remove.all(tmp)

  private def mkRoutes: RestApiRoutes =
    val config = NebflowServiceConfig(
      llm = ServiceLlmConfig(providers = Map.empty)
    )
    val resources = SharedResources(
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
      historyArchiver = HistoryArchiver.fileSystem(os.temp.dir() / "panel-spec-archives"),
      fileLockManager = FileLockManager.create.unsafeRunSync(),
      sessionModelOverrides = cats.effect.Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = cats.effect.Ref.unsafe[IO, Boolean](false),
      friendService = None
    )
    new RestApiRoutes(
      token = TestToken,
      configRef = cats.effect.Ref.unsafe[IO, NebflowServiceConfig](config),
      sharedResources = resources,
      sessionStore = null,
      wsRoutes = null
    )

  private def getAgents: IO[Response[IO]] =
    val req = Request[IO](Method.GET, Uri.unsafeFromString("/agents"))
      .withHeaders(Headers("Authorization" -> s"Bearer $TestToken"))
    mkRoutes.routes(req).value.map(_.getOrElse(fail("route fell through")))

  override def munitIOTimeout: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.DurationInt(60).seconds

  test("GET /api/agents returns exactly the three keepers — no team/flow leakage, no stale dirs"):
    withFixture { _ =>
      getAgents.flatMap { resp =>
        assertEquals(resp.status, Status.Ok)
        resp.as[io.circe.Json].map { json =>
          val agents = json.hcursor.downField("agents").as[List[io.circe.Json]].getOrElse(Nil)
          val names = agents.flatMap(_.hcursor.downField("name").as[String].toOption).toSet
          assertEquals(names, Keepers, "面板恰三 keeper——fixture team/flow 域 agent 与干扰目录一律不出现")
          assert(!names.contains("domain-agent"), "team 域 agent 不得泄漏进面板")
          assert(!names.contains("flow-agent"), "flow 域 agent 不得泄漏进面板")
          assert(!names.contains("ExplorerX") && !names.contains("MailX") && !names.contains("emptyX"),
            ".archived / 仅 memory.md / 空目录一律不出现")
        }
      }
    }

  test("every panel entry is layer=global, standalone, and carries no scope field"):
    withFixture { _ =>
      getAgents.flatMap { resp =>
        resp.as[io.circe.Json].map { json =>
          val agents = json.hcursor.downField("agents").as[List[io.circe.Json]].getOrElse(Nil)
          assertEquals(agents.size, 3, "恰三 keeper")
          agents.foreach { a =>
            assertEquals(a.hcursor.downField("layer").as[String].toOption, Some("global"), "layer 恒 global")
            assert(!a.hcursor.downField("scope").succeeded, "scope 字段随 team/flow 层删除而消失（无 scope 键）")
          }
        }
      }
    }

  test("auth gate: no token -> 403"):
    withFixture { _ =>
      val req = Request[IO](Method.GET, Uri.unsafeFromString("/agents"))
      mkRoutes.routes(req).value.map(_.getOrElse(fail("route fell through")))
        .map(resp => assertEquals(resp.status, Status.Forbidden))
    }
