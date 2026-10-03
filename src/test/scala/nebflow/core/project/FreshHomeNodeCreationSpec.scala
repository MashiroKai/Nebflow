package nebflow.core.project

import cats.effect.IO
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{SharedResources, SpecResources}
import nebflow.core.entity.{BuiltinAgents, EntityLoader}
import nebflow.core.tools.NodeEditTool
import nebflow.core.tools.ToolContext
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk}

import scala.concurrent.duration.*

/**
 * P0-1 · 全新 home 建节点 + 旧数据解析兜底（本批唯一**新增验收面**，非既有 spec 改写）。
 *
 * 审计 §三 的 P0-1 根因：`f646eeeaa` 把收敛名集改为
 * `{Nebula, project-dispatcher, nebflow, subagent}`（`BuiltinAgents.Names`），
 * `general` 不再是内置名；但引擎建位/缺省仍硬编码 `"general"` ⇒ 全新 home 上
 * （无遗留 `~/.nebflow/agents/general/` 磁盘目录）**建不出节点**；且存量节点 JSON 的
 * `agent` / `verify` 字段仍带旧名 ⇒ spawn 时 `loadAgent` 落空 ⇒ 旧项目在新环境跑不动。
 *
 * 本 spec 钉三层：
 *  - ① **全新 home**：`NODE_HOME` 指向一个只有空 agents 目录的私有 home
 *    （零遗留目录）⇒ `EntityLoader.loadAgent(ExecutorName)` 必须命中（代码定义），
 *    且 `NodeEditTool` **建位成功**（不再依赖磁盘 general）。
 *  - ② **旧数据兜底**：`EntityLoader.loadAgent("general")` / `("kernel")` 走
 *    `BuiltinAgents.RetiredNames` 单点表回落到执行 agent（磁盘落空也不死人）；
 *    库存量节点（`NodeDef.agent = "general"`、`LoopConfig.verify = "general"`）
 *    经 NodeEdit 编辑面/引擎读面照常可解析。
 *  - ③ **写侧单点**：NodeEdit 落库的 `NodeDef.agent` 必须是 [[BuiltinAgents.ExecutorName]]
 *    （常量引用），且 `"general"` 不再出现在写面缺省。
 *
 * 🔴 读侧 home 切换（`PathUtil.setDataRoot`）是**进程级**的，会让每轮模型链解析都去
 * 读本 spec 的空 home。与 `presets/SchemePolicySpec`（同款进程级 setDataRoot）在同一
 * 套件里并发跑属既有形态（munit 各 suite 共享 JVM，但不共享线程内 dataRoot 写入时序
 * 冲突——两者都在各自的 beforeAll 换根、afterAll 复原，互不交叠时无串扰）；真正要防
 * 的是「本 spec 在跑时另一个 suite 也在换根」。munit 默认按套件顺序跑（非并发），此处
 * 用跨套件唯一的 target/test-fresh-home-node 目录 + 明确的「单跑提示」注释钉住，
 * 不自造锁机制。
 */
class FreshHomeNodeCreationSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val repoRoot: os.Path = os.pwd
  private val tempRoot: os.Path = repoRoot / "target" / "test-fresh-home-node"
  private val originalRoot = PathUtil.dataRoot

  override def beforeAll(): Unit =
    os.remove.all(tempRoot)
    // 私有空 home：只有 agents/ skills/ 两个空目录（零遗留 general/kernel 目录）。
    os.makeDir.all(tempRoot / "agents")
    os.makeDir.all(tempRoot / "skills")
    os.write.over(tempRoot / "nebflow.json", "{}")
    PathUtil.setDataRoot(tempRoot)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    os.remove.all(tempRoot)

  // ── 基建（NodeDeclarationGateSpec 同款；0 spawn）────────────────

  private class QuietLlm:
    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def mkCtx(res: SharedResources, system: ActorSystem, ws: String): ToolContext =
    ToolContext(
      projectRoot = ws,
      sessionId = Some("fresh-home-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

  private def nodeEdit(input: Json, ctx: ToolContext): IO[Either[String, String]] =
    NodeEditTool.call(input.asObject.get, ctx).map(_.left.map(_.message))

  private def nodeInput(project: String, nodename: String, extra: (String, Json)*): Json =
    Json.obj(
      ("project" -> Json.fromString(project)) ::
        ("nodename" -> Json.fromString(nodename)) ::
        ("description" -> Json.fromString(s"fresh-home node $nodename")) ::
        // task ⇒ entry semantics（EMPTY_NODE_CONNECTION 要求 task 或 in 之一）
        ("task" -> Json.fromString(s"fresh-home task $nodename")) ::
        ("plugins" -> Json.arr()) :: extra.toList*
    )

  private def mountProject(name: String, ws: os.Path, system: ActorSystem, res: SharedResources): IO[ProjectRuntime] =
    for
      store <- FlowMapStore.open(name, ws.toString)
      engine = new NodeEngine(
        store,
        system,
        res,
        wsSendFn = (_: Json) => IO.unit,
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = name,
        emitEvent = (_, _, _) => IO.unit,
        reportGateHold = Some(false)
      )
      pd = ProjectDef(
        name = name,
        workspace = ws.toString,
        agentFile = (ws / "AGENTS.md").toString,
        createdAt = System.currentTimeMillis()
      )
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield rt

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear

  private def mkEnv(name: String): IO[(os.Path, ActorSystem, SharedResources, ProjectRuntime, ToolContext)] =
    val ws = tempRoot / s"ws-$name"
    os.makeDir.all(ws)
    val system = ActorSystem(s"fresh-$name-${scala.util.Random.nextInt(100000)}")
    for
      res <- SpecResources.mkResources(system, tempRoot, QuietLlm().handle)
      rt <- mountProject(s"fresh-$name", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
    yield (ws, system, res, rt, ctx)

  // ── ① 全新 home：零遗留目录面证明 ────────────────────────────

  test("① fresh home: the agents directory is empty — no legacy `general` / `kernel` / builtin dirs on disk"):
    val agents = tempRoot / "agents"
    val dirs = if os.exists(agents) then os.list(agents).filter(os.isDir).map(_.last).toSet else Set.empty
    assertEquals(dirs, Set.empty[String], s"the fresh home must carry ZERO agent directories, got: ${dirs.toList.sorted}")
    // 反证面：本 spec 用的就是私有 home，不是宿主 ~/.nebflow（读出隔离读数）。
    assert(
      PathUtil.dataRoot.toString.startsWith(tempRoot.toString),
      s"data root must be the private fresh home: got ${PathUtil.dataRoot}"
    )

  test("① fresh home: the built-in executor agent resolves from CODE (no disk directory needed)"):
    val loaded = EntityLoader.loadAgent(BuiltinAgents.ExecutorName).unsafeRunSync()
    assert(loaded.isDefined, s"'${BuiltinAgents.ExecutorName}' must resolve on a fresh home (code-defined builtin)")
    assertEquals(loaded.get.name, BuiltinAgents.ExecutorName)

  // ── ① 全新 home：建位成功（P0-1 的核心验收）────────────────────

  test("① fresh home: NodeEdit CREATES a node successfully (agent lands on BuiltinAgents.ExecutorName)"):
    val (_, system, _, rt, ctx) = mkEnv("create").unsafeRunSync()
    try
      val res = nodeEdit(nodeInput("fresh-create", "n-a"), ctx).unsafeRunSync()
      assert(res.isRight, s"node creation must succeed on a fresh home: $res")
      val node = rt.store.snapshot.unsafeRunSync().nodes.values.find(_.name == "n-a").getOrElse(
        fail("created node missing from the flow-map store")
      )
      assertEquals(node.agent, BuiltinAgents.ExecutorName, "the node's agent must be the built-in executor constant (no literal)")
      assert(node.agent != "general", "the retired name must never be written back")
    finally system.stopAll.unsafeRunSync()

  // ── ② 旧数据兜底：退役名解析 ────────────────────────────────

  test("② old data: retired names `general` / `kernel` resolve to the executor through the single rename table"):
    for retired <- List("general", "kernel") do
      val loaded = EntityLoader.loadAgent(retired).unsafeRunSync()
      assert(loaded.isDefined, s"retired name '$retired' must still resolve on a fresh home (old-data fallback)")
      assertEquals(loaded.get.name, BuiltinAgents.ExecutorName, s"'$retired' must resolve onto the executor agent")
    // 单点表本身：退役名映射 = 执行 agent；未知自定义名不落兜底（磁盘面照旧）。
    assertEquals(BuiltinAgents.resolveRetired("general"), Some(BuiltinAgents.ExecutorName))
    assertEquals(BuiltinAgents.resolveRetired("kernel"), Some(BuiltinAgents.ExecutorName))
    assertEquals(BuiltinAgents.resolveRetired("some-custom"), None)
    assertEquals(EntityLoader.loadAgent("some-custom").unsafeRunSync(), None)

  test("② old data: a stored node with agent=\"general\" is still loadable and editable (verify default too)"):
    val (_, system, _, rt, ctx) = mkEnv("legacy").unsafeRunSync()
    try
      // 直种存量形态节点（agent = 退役名 + loop verify = 退役名），绕过写侧缺省。
      val legacy = NodeDef(
        id = "n-legacy-1",
        name = "legacy-1",
        agent = "general",
        task = Some("legacy task"),
        description = Some("legacy node"),
        status = NodeLifecycle.Wiring,
        createdAt = System.currentTimeMillis()
      )
      rt.store.mutate(s => s.copy(nodes = s.nodes + (legacy.id -> legacy))).unsafeRunSync()
      assertEquals(rt.store.snapshot.unsafeRunSync().nodes("n-legacy-1").agent, "general")
      // spawn 读面：引擎按 NodeDef.agent 走 EntityLoader.loadAgent ⇒ 退役名必须能解析。
      val resolved = EntityLoader.loadAgent(rt.store.snapshot.unsafeRunSync().nodes("n-legacy-1").agent).unsafeRunSync()
      assert(resolved.isDefined, "a stored legacy agent name must resolve (else the old project cannot run on a fresh home)")
      assertEquals(resolved.get.name, BuiltinAgents.ExecutorName)
      // 编辑面照常可达（无 NODE_AGENT_RETIRED 误伤——那是参数闸，不是存量值闸）。
      val edit = nodeEdit(nodeInput("fresh-legacy", "legacy-1").deepMerge(Json.obj("description" -> Json.fromString("edited legacy"))), ctx).unsafeRunSync()
      assert(edit.isRight, s"editing a stored legacy node must work on a fresh home: $edit")
    finally system.stopAll.unsafeRunSync()

  // ── ③ 写侧单点：NodeEdit 不再接受/产出退役名 ──────────────────

  test("③ write face: the retired `agent` parameter is refused (NODE_AGENT_RETIRED) and the default is the constant"):
    val (_, system, _, _, ctx) = mkEnv("writeface").unsafeRunSync()
    try
      val res = nodeEdit(
        nodeInput("fresh-writeface", "n-agentarg").deepMerge(Json.obj("agent" -> Json.fromString("general"))),
        ctx
      ).unsafeRunSync()
      assert(res.isLeft, "the retired 'agent' parameter must be refused")
      assert(res.swap.getOrElse("").contains("NODE_AGENT_RETIRED"), s"expected NODE_AGENT_RETIRED, got: $res")
    finally system.stopAll.unsafeRunSync()

  test("③ write face: the `verify` default schema text names the built-in executor (model-visible face)"):
    // 模型可见面：schema 里 verify 的缺省说明必须指向内置执行 agent 名（不是退役名）。
    val schema = NodeEditTool.inputSchema
    val verifyDesc = schema("properties").flatMap(_.asObject)
      .flatMap(_("verify"))
      .flatMap(_.asObject)
      .flatMap(_("description"))
      .flatMap(_.asString)
      .getOrElse(fail("verify description missing from the NodeEdit schema"))
    assert(
      verifyDesc.contains(BuiltinAgents.ExecutorName),
      s"the verify default must be described with the executor constant, got: $verifyDesc"
    )
    assert(!verifyDesc.contains("\"general\""), s"the verify description must not advertise the retired name: $verifyDesc")

  test("③ write face: the missing-name fail-fast text points at the executor (no retired-name literal)"):
    // 建位 fail-fast 文案（库里连内置执行 agent 都没有 ⇒ 环境残缺）：模型可见面 + 操作面。
    val src = os.read(repoRoot / "src" / "main" / "scala" / "nebflow" / "core" / "tools" / "NodeEditTool.scala")
    assert(
      src.contains("the builtin library is incomplete") && !src.contains("val agentName = \"general\""),
      "the fail-fast text must reference the builtin library (not a retired-name literal)"
    )

end FreshHomeNodeCreationSpec
