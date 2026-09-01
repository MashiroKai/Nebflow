package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentRecord, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.tools.{ProjectCreateTool, ToolContext}
import nebflow.llm.{ModelCandidate, ThinkingConfig}

import scala.concurrent.duration.*

/**
 * P0 rootSessionId 接线回归（Explorer 取证 c759e8c，#37 A）：
 *
 * 缺陷：ProjectCreateTool mount 时 rootSessionId = 挂载者 ctx.sessionId（非顶层会话
 * 挂载 → 节点 out="Nebula" 投递目标是挂载者，如 qa-backend，形成自维持反馈循环）。
 *
 * 修复：ToolContext thread AgentState.rootSessionId（真正顶层）→ mount 传上链根会话。
 * 本测试断言：非顶层会话（sessionId="qa-backend-sid"，rootSessionId="nebula-root"）
 * 执行 ProjectCreate 挂载后，engine.rootSessionId == "nebula-root"（真正顶层），
 * 而非挂载者自身会话。
 */
class ProjectCreateRootSessionSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-project-create-rootsession"

  // 类级：隔离 dataRoot（projects/ 落 tempRoot，不污染真实 ~/.nebflow）+ 清空
  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  private def testResources(projectRoot: os.Path): SharedResources =
    new SharedResources(
      llm = null,
      dispatcher = null,
      sessionStore = null,
      projectRoot = projectRoot,
      thinkingConfigRef = Ref.unsafe[IO, ThinkingConfig](ThinkingConfig()),
      rateLimiter = null,
      fileChangeTracker = null,
      contextWindow = 100_000,
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

  test("non-root session ProjectCreate mounts with the UPLINE rootSessionId, not the caller's session") {
    val ws = tempRoot / "ws-phd"
    os.makeDir.all(ws)
    val system = ActorSystem(s"pc-rs-${scala.util.Random.nextInt(100000)}")
    val res = testResources(ws)
    val input = Json.obj(
      "name" -> Json.fromString("root-test"),
      "workspace" -> Json.fromString(ws.toString),
      "description" -> Json.fromString("P0 rootSessionId regression")
    ).asObject.get
    // 非顶层会话：sessionId = 执行者（qa-backend），rootSessionId = 真正顶层（Nebula）
    val ctx = ToolContext(
      projectRoot = ws.toString,
      sessionId = Some("qa-backend-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )
    for
      result <- ProjectCreateTool.call(input, ctx)
      rt <- ProjectRuntimeRegistry.get("root-test")
      _ <- ProjectRuntimeRegistry.unregister("root-test")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(result.isRight, result.toString)
      assert(rt.isDefined, "project must be mounted")
      assertEquals(rt.get.engine.rootSessionId, "nebula-root", "engine rootSessionId must be the true top-level root, not the caller session")
      assert(rt.get.engine.rootSessionId != "qa-backend-sid", "must NOT anchor to the mounting caller")
  }

  test("idempotent mount: existing project ProjectCreate → mounts with upline rootSessionId; repeat call is stable") {
    val ws = tempRoot / "ws-dup"
    os.makeDir.all(ws)
    val system = ActorSystem(s"pc-rs2-${scala.util.Random.nextInt(100000)}")
    val res = testResources(ws)
    val input = Json.obj(
      "name" -> Json.fromString("root-test-dup"),
      "workspace" -> Json.fromString(ws.toString)
    ).asObject.get
    val ctx = ToolContext(
      projectRoot = ws.toString,
      sessionId = Some("qa-backend-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )
    for
      first <- ProjectCreateTool.call(input, ctx) // 新建
      rt1 <- ProjectRuntimeRegistry.get("root-test-dup")
      second <- ProjectCreateTool.call(input, ctx) // 已存在 → 幂等挂载
      rt2 <- ProjectRuntimeRegistry.get("root-test-dup")
      third <- ProjectCreateTool.call(input, ctx) // 已挂载 → 幂等返回
      _ <- ProjectRuntimeRegistry.unregister("root-test-dup")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(first.isRight, s"first create must succeed: $first")
      assert(second.isRight, s"idempotent mount must succeed: $second")
      assert(third.isRight, s"repeat call must be stable: $third")
      assert(second.toOption.get.contains("already exists"), "idempotent path must report already-exists semantics")
      assert(rt1.isDefined && rt2.isDefined, "project must be mounted after both calls")
      assertEquals(rt1.get.engine.rootSessionId, "nebula-root")
      assertEquals(rt2.get.engine.rootSessionId, "nebula-root")
  }

end ProjectCreateRootSessionSpec
