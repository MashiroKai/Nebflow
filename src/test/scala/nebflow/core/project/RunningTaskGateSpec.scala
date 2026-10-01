package nebflow.core.project

import cats.effect.IO
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{SharedResources, SpecResources}
import nebflow.core.tools.{NodeEditTool, ToolContext}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk}

import scala.concurrent.duration.*
import scala.util.Random

/**
 * **缺陷④闸（案 A · fail-loud）—— running 位 `NodeEdit(task=)` 逻辑闸**。
 *
 * 缺陷（设计卡 `20260930_235116_nodeedit-running-failsuccess-design__chain-engdef-candidates.md`
 * §0/§1.1）：`editNode` 上 task 的**唯一写点**位于 `didReactivate` 事务内
 * （`NodeEditTool.scala:2540`，`task = appliedTask`），而 running 位 `reactivate` 恒 false
 * ⇒ 写入为零，回执却报 `updated` = **假成功**（零审计、零副作用、调用方无从分辨）。同文件
 * 同族先例 = `abandon` 的 running 闸（`:1677-1688`）、deps-frozen 闸（`:1841-1844`）、
 * out-**目标**闸（`NodeTools.ensureTargetNotRunning`）—— 唯独 `task` 无闸。
 *
 * 本批落地 = 案 A：前置拒绝链内新增一条状态闸（判据 = `task.isDefined ∧ status==Running
 * ∧ normalizeTask 不等`），错误码 `NODE_RUNNING_TASK_FROZEN`，出口指引 `Mail(node:)`。
 * 归一化复用 `NodeTools.normalizeTask`（`NodeTools.scala:882`：`trim + 折叠空白`）
 * ⇒ **幂等重发（传现值）不拒**。
 *
 * 锚（逐条对齐任务书 §2.4）：
 *  - **G1（改前红 / 改后绿）**：running 位 `NodeEdit(task="REPLACEMENT TASK")` —— 改前回执
 *    `isRight` ∧ 含 `updated` ∧ 不含错误码（假成功）；改后 `isLeft` ∧ 含错误码 ∧ `running`
 *    ∧ `Mail`。
 *  - **G2 零副作用面（改前后皆绿）**：store.task 仍为原值 ∧ `.nebflow/tasks/<id>.md`
 *    字节/mtime 不变 ∧ `flow-map-events.jsonl` 内该 nodeId 的 `node-message` 行增量 0。
 *  - **G3 幂等锚（改前后皆绿）**：传现值（空白折叠后相等）⇒ 放行（防「同参数重发」误伤）。
 *  - **G4/G5/G6 合法用法回归锚（改前后皆绿）**：blocked 复活 / completed 授权重派 /
 *    wiring 批内修正 —— 新闸只命中 `status==Running` 一支，三支行为必须逐字不动。
 *  - **G7 链序（改前后皆绿）**：running + deps 仍走既有 deps-frozen 文案（新闸插在其后，
 *    不得遮蔽既有分支）。
 *  - **G8 文案冻结锚（改前后皆绿）**：`NodeEditTool.description` / `inputSchema.task` 的
 *    三处冻结文本逐字在位（verify 侧的缺席断言在会话内先自证一次）。
 *
 * 驱动方式：真实 `NodeEditTool` + 真实 `ProjectRuntime` 夹具（静默 LLM 桩——本 spec 全域
 * 零 spawn 会话、零实例、零端口、零 live 触碰）。
 */
class RunningTaskGateSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-running-task-gate"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")

  os.write.over(
    tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  /** 静默 LLM 桩（本 spec 只验 0 spawn 的编辑期判据面）。 */
  private class QuietLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def mkCtx(res: SharedResources, system: ActorSystem, ws: String): ToolContext =
    ToolContext(
      projectRoot = ws,
      sessionId = Some("running-task-gate-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

  private def nodeEdit(input: Json, ctx: ToolContext): IO[Either[String, String]] =
    NodeEditTool.call(input.asObject.get, ctx).map(_.left.map(_.message))

  // 建位期声明闸（`plugins=[]` = 显式「无需能力面」，同 DeferredWiringSpec 先例）。
  private def nodeInput(project: String, nodename: String, extra: (String, Json)*): Json =
    Json.obj(
      ("project" -> Json
        .fromString(project)) :: ("nodename" -> Json.fromString(nodename)) :: ("plugins" -> Json.arr()) :: extra.toList*
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

  private val OriginalTask = "ORIGINAL TASK"

  /** 直种节点（绕过 NodeEdit 校验，把状态摆到待验判据上）。 */
  private def seed(rt: ProjectRuntime, nodes: NodeDef*): IO[Unit] =
    rt.store.mutate(s => s.copy(nodes = s.nodes ++ nodes.map(n => n.id -> n).toMap)).void

  private def mkNode(id: String, name: String, status: String, task: Option[String] = Some(OriginalTask)): NodeDef =
    NodeDef(
      id = id,
      name = name,
      agent = "general",
      task = task,
      status = status,
      role = NodeRoles.Task,
      createdAt = System.currentTimeMillis()
    )

  private def nodeById(rt: ProjectRuntime, id: String): IO[NodeDef] =
    rt.store.snapshot.map(_.nodes.getOrElse(id, fail(s"node '$id' must exist")))

  /** 文件面读数：`.nebflow/tasks/<id>.md` 的（字节数, mtime）。缺席 = (-1, -1)。 */
  private def taskFileFace(ws: os.Path, id: String): (Int, Long) =
    val p = ws / ".nebflow" / FlowMapStore.TasksDirName / s"$id.md"
    if !os.exists(p) then (-1, -1L) else (os.read.bytes(p).length, os.mtime(p))

  /** 事件面读数：`flow-map-events.jsonl` 内该 nodeId 的 `node-message` 行数。 */
  private def nodeMessageCount(ws: os.Path, id: String): IO[Int] =
    IO.blocking {
      val f = ws / ".nebflow" / FlowMapEventLog.FileName
      if !os.exists(f) then 0
      else
        os.read(f).linesIterator.toList.filter(_.trim.nonEmpty).flatMap(l => io.circe.parser.parse(l).toOption).count {
          j =>
            j.hcursor.get[String]("type").toOption.contains(NodeEngine.NodeMessageEventType) &&
            j.hcursor.get[String]("nodeId").toOption.contains(id)
        }
    }

  private def stop(system: ActorSystem): IO[Unit] = system.stopAll.handleErrorWith(_ => IO.unit)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── G1 主锚：running 位 task 改写 ⇒ 必拒（改前红 / 改后绿）──────────────────

  test("G1 缺陷④闸 主锚：running 位 NodeEdit(task=REPLACEMENT) ⇒ 拒 NODE_RUNNING_TASK_FROZEN + running + Mail") {
    val ws = tempRoot / "ws-g1"
    os.makeDir.all(ws)
    val system = ActorSystem(s"runtask-g1-${Random.nextInt(100000)}")
    for
      res <- SpecResources.mkResources(system, tempRoot, new QuietLlm)
      rt <- mountProject("runtask-g1", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- seed(rt, mkNode("n-target", "n-target", NodeLifecycle.Running))
      r <- nodeEdit(
        nodeInput(
          "runtask-g1",
          "n-target",
          "description" -> Json.fromString("target node"),
          "task" -> Json.fromString("REPLACEMENT TASK")
        ),
        ctx
      )
      _ <- stop(system)
    yield
      assert(
        r.isLeft,
        "G1（改前必红 / 改后必绿）：running 位 task 改写必须被拒（案 A fail-loud）；" +
          s"实得 r.isRight=${r.isRight}，回执正文=${r.getOrElse("")}，" +
          s"含 updated=${r.exists(_.contains("updated"))}，" +
          s"含 NODE_RUNNING_TASK_FROZEN=${r.exists(_.contains("NODE_RUNNING_TASK_FROZEN"))}"
      )
      val msg = r.fold(identity, _ => "")
      assert(msg.contains("NODE_RUNNING_TASK_FROZEN"), s"拒绝必须带错误码，实得: $msg")
      assert(msg.contains("running"), s"拒绝必须具名冻结态，实得: $msg")
      assert(msg.contains("Mail"), s"拒绝必须给可行动出口 Mail(node:)，实得: $msg")
    end for
  }

  // ── G2 零副作用面（改前后皆绿）───────────────────────────────────────────

  test("G2 零副作用面：running 位改写后 store.task 原值 / tasks/<id>.md 字节·mtime 不变 / node-message 增量 0") {
    val ws = tempRoot / "ws-g2"
    os.makeDir.all(ws)
    val system = ActorSystem(s"runtask-g2-${Random.nextInt(100000)}")
    for
      res <- SpecResources.mkResources(system, tempRoot, new QuietLlm)
      rt <- mountProject("runtask-g2", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- seed(rt, mkNode("n-target", "n-target", NodeLifecycle.Running))
      fileBefore = taskFileFace(ws, "n-target")
      msgBefore <- nodeMessageCount(ws, "n-target")
      _ <- nodeEdit(
        nodeInput(
          "runtask-g2",
          "n-target",
          "description" -> Json.fromString("target node"),
          "task" -> Json.fromString("REPLACEMENT TASK")
        ),
        ctx
      )
      after <- nodeById(rt, "n-target")
      fileAfter = taskFileFace(ws, "n-target")
      msgAfter <- nodeMessageCount(ws, "n-target")
      _ <- stop(system)
    yield
      assert(fileBefore._1 > 0, s"夹具前置：task 全文必须已落 tasks/<id>.md，实得 bytes=${fileBefore._1}")
      assertEquals(after.task, Some(OriginalTask), "store 内 task 必须仍为原值（零副作用）")
      assertEquals(fileAfter._1, fileBefore._1, "tasks/<id>.md 字节数必须不变")
      assertEquals(fileAfter._2, fileBefore._2, "tasks/<id>.md mtime 必须不变")
      assertEquals(msgAfter - msgBefore, 0, "flow-map-events.jsonl 内该 nodeId 的 node-message 行增量必须为 0")
    end for
  }

  // ── G3 幂等锚（改前后皆绿）───────────────────────────────────────────────

  test("G3 幂等锚：running 位传 task = 现值（空白折叠后相等）⇒ 放行（不误伤同参数重发）") {
    val ws = tempRoot / "ws-g3"
    os.makeDir.all(ws)
    val system = ActorSystem(s"runtask-g3-${Random.nextInt(100000)}")
    for
      res <- SpecResources.mkResources(system, tempRoot, new QuietLlm)
      rt <- mountProject("runtask-g3", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- seed(rt, mkNode("n-target", "n-target", NodeLifecycle.Running))
      r <- nodeEdit(
        nodeInput(
          "runtask-g3",
          "n-target",
          "description" -> Json.fromString("target node"),
          "task" -> Json.fromString("  ORIGINAL    TASK  ")
        ),
        ctx
      )
      after <- nodeById(rt, "n-target")
      _ <- stop(system)
    yield
      assert(r.isRight, s"归一化后相等的重发不得被拒（判据 = NodeTools.normalizeTask 双侧归一化），实得: $r")
      assertEquals(after.task, Some(OriginalTask), "幂等重发不得改动 store.task")
    end for
  }

  // ── G4 合法用法回归锚①：blocked 复活（改前后皆绿）─────────────────────────

  test("G4 回归锚①：Blocked 节点 NodeEdit(task=NEW) ⇒ 放行 + 回执含 reactivated from blocked + store.task 更新") {
    val ws = tempRoot / "ws-g4"
    os.makeDir.all(ws)
    val system = ActorSystem(s"runtask-g4-${Random.nextInt(100000)}")
    for
      res <- SpecResources.mkResources(system, tempRoot, new QuietLlm)
      rt <- mountProject("runtask-g4", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- seed(rt, mkNode("n-blocked", "n-blocked", NodeLifecycle.Blocked))
      r <- nodeEdit(nodeInput("runtask-g4", "n-blocked", "task" -> Json.fromString("NEW")), ctx)
      after <- nodeById(rt, "n-blocked")
      _ <- stop(system)
    yield
      val ok = r.fold(err => fail(s"blocked 节点改 task 必须放行，实得拒绝: $err"), identity)
      assert(ok.contains("reactivated from blocked"), s"回执必须申报复活，实得: $ok")
      assert(!ok.contains("NODE_RUNNING_TASK_FROZEN"), s"复活支不得命中 running 闸，实得: $ok")
      assertEquals(after.task, Some("NEW"), "blocked 复活必须写入新 task")
    end for
  }

  // ── G5 合法用法回归锚②：completed 授权重派（改前后皆绿）───────────────────

  test("G5 回归锚②：Completed 节点 + reactivateCompleted=true + task=NEW ⇒ 放行 + 回执含 NODE_COMPLETED_REACTIVATION") {
    val ws = tempRoot / "ws-g5"
    os.makeDir.all(ws)
    val system = ActorSystem(s"runtask-g5-${Random.nextInt(100000)}")
    for
      res <- SpecResources.mkResources(system, tempRoot, new QuietLlm)
      rt <- mountProject("runtask-g5", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- seed(rt, mkNode("n-done", "n-done", NodeLifecycle.Completed).copy(result = Some("previous result")))
      r <- nodeEdit(
        nodeInput(
          "runtask-g5",
          "n-done",
          "task" -> Json.fromString("NEW"),
          "reactivateCompleted" -> Json.fromBoolean(true)
        ),
        ctx
      )
      after <- nodeById(rt, "n-done")
      _ <- stop(system)
    yield
      val ok = r.fold(err => fail(s"completed 显式授权重派必须放行，实得拒绝: $err"), identity)
      assert(ok.contains("NODE_COMPLETED_REACTIVATION"), s"回执必须申报授权入口名，实得: $ok")
      assertEquals(after.task, Some("NEW"), "授权重派必须写入新 task")
    end for
  }

  // ── G6 合法用法回归锚③：wiring 批内修正（改前后皆绿）─────────────────────

  test("G6 回归锚③：Wiring 节点 NodeEdit(task=NEW) ⇒ 放行且与改前读数逐字相同（新闸不越界）") {
    val ws = tempRoot / "ws-g6"
    os.makeDir.all(ws)
    val system = ActorSystem(s"runtask-g6-${Random.nextInt(100000)}")
    for
      res <- SpecResources.mkResources(system, tempRoot, new QuietLlm)
      rt <- mountProject("runtask-g6", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- seed(rt, mkNode("n-wiring", "n-wiring", NodeLifecycle.Wiring))
      r <- nodeEdit(nodeInput("runtask-g6", "n-wiring", "task" -> Json.fromString("NEW")), ctx)
      after <- nodeById(rt, "n-wiring")
      _ <- stop(system)
    yield
      r.fold(err => fail(s"wiring 节点改 task 必须放行，实得拒绝: $err"), identity)
      val ok = r.getOrElse("")
      assert(!ok.contains("NODE_RUNNING_TASK_FROZEN"), s"wiring 支不得命中 running 闸，实得: $ok")
      // 🔴 回归判据 = 「与改前读数逐字相同」。本断言记录的是**实测落盘值**（改前/改后两轮
      // 读数必须恒等）——wiring 位 task 写点缺失属在册缺陷面 `#984`（「wiring 态 task 替换
      // 未随启动物化」），**不在本批改动域内**；若本批之后该值发生变化即为越界（本批只加了
      // 一条 running ⇒ 拒 的分支，wiring 路径零改动）。
      assertEquals(after.task, Some(OriginalTask), "wiring 支读数（改前/改后必须恒等 = 本批零越界）")
    end for
  }

  // ── G7 链序（改前后皆绿）─────────────────────────────────────────────────

  test("G7 链序不变：running 位传 deps 仍走既有 deps-frozen 文案（新闸不得遮蔽既有分支）") {
    val ws = tempRoot / "ws-g7"
    os.makeDir.all(ws)
    val system = ActorSystem(s"runtask-g7-${Random.nextInt(100000)}")
    for
      res <- SpecResources.mkResources(system, tempRoot, new QuietLlm)
      rt <- mountProject("runtask-g7", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- seed(rt, mkNode("n-target", "n-target", NodeLifecycle.Running))
      r <- nodeEdit(nodeInput("runtask-g7", "n-target", "deps" -> Json.fromString("some-upstream")), ctx)
      _ <- stop(system)
    yield
      val msg = r.fold(identity, ok => fail(s"running 位传 deps 必须被拒（deps-frozen），实得: $ok"))
      assert(msg.contains("input is frozen"), s"deps-frozen 分支文案必须逐字在位，实得: $msg")
      assert(!msg.contains("NODE_RUNNING_TASK_FROZEN"), s"deps 面不得被新闸接管，实得: $msg")
    end for
  }

  // ── G8 文案冻结锚（改前后皆绿；逐字三处）─────────────────────────────────

  test("G8 文案冻结锚：description:177 / :196 与 inputSchema.task:222 三处冻结文本逐字在位（零改动）") {
    val d = NodeEditTool.description
    assert(d.contains("- task (optional): node task."), "冻结面 :177 逐字在位")
    assert(
      d.contains(
        "- Edit: in appends; deps/out/description replace; removing a consumed target (running/terminal) rejected — " +
          "NodeCancel first; other terminal rewires auto-deliver the retained result to new targets."
      ),
      "冻结面 :196 逐字在位"
    )
    val schema = NodeEditTool.inputSchema("properties").flatMap(_.asObject).flatMap(_.apply("task")).flatMap(_.asObject)
    val taskDesc = schema.flatMap(_.apply("description")).flatMap(_.asString)
    assertEquals(
      taskDesc,
      Some("Node task context; entry nodes (task, no in) run immediately"),
      "冻结面 inputSchema.task.description 逐字在位"
    )
    assert(!d.contains("NODE_RUNNING_TASK_FROZEN"), "本批不落描述改写提案（§16 过目件）")
  }
