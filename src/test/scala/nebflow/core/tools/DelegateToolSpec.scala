package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.agent.{AgentDef, AgentLibrary, AgentStatus}
import nebflow.core.PathUtil

/**
 * DelegateTool 前门（2026-09-11 Delegate 恢复批 · 极简内核形态）：
 *
 *  1. schema 面：恰三参数 `task`/`description`/`device`（旧 `agent`/`lifecycle`/
 *     `taskDescription`/`images`/`preset`/`prompt` 全部退役——旧断言在本文件里
 *     逐条反向钉死）。
 *  2. 目标解析：内置 `kernel` def；缺失给自描述错误（不再有 standalone 目录）。
 *  3. R9 并发：每根会话 ≤ 4（U4=D1：等待答复占额度；错误含在飞清单 + 等待标注）。
 *  4. 设备预检：无 NebLink 时带 `device=` **fail-fast**（不静默本地执行）。
 *  5. description 硬事实：绝对路径 / Bash cwd 不保证 / 4 并发 / 3600s 预算。
 *
 * 无 ActorSystem 的用例停在「requires ActorSystem and SharedResources」——spawn
 * 链本身由隔离实例 e2e 覆盖（见交付结果 §②）。
 */
class DelegateToolSpec extends CatsEffectSuite:
  private val tempRoot: os.Path = os.pwd / "target" / "test-delegate-kernel"
  PathUtil.setDataRoot(tempRoot)
  private val agentsDir = tempRoot / "agents"
  private val lib = AgentLibrary(agentsDir)

  private def reset(): IO[Unit] =
    IO.delay { if os.exists(tempRoot) then os.remove.all(tempRoot) } *>
      IO.delay { os.makeDir.all(agentsDir) }

  /** 写一个 agent 定义（默认写内核；`name` 可换以验证「目标恒为 kernel」）。 */
  private def writeAgent(
      name: String = "kernel",
      category: String = "standalone",
      touchSystemMd: Boolean = true
  ): Unit =
    val dir = agentsDir / name
    os.makeDir.all(dir)
    os.write(
      dir / "agent.json",
      Json
        .obj(
          "name" -> name.asJson,
          "description" -> s"$name agent".asJson,
          "tools" -> Json.arr("*".asJson),
          "category" -> category.asJson
        )
        .noSpaces
    )
    if touchSystemMd then os.write(dir / "system.md", s"You are $name.")

  private def ctxWith(libOpt: Option[AgentLibrary] = Some(lib)): ToolContext =
    ToolContext(
      sessionId = Some("test"),
      sessionStore = None,
      agentDef = Some(AgentDef(name = "Nebula", description = "", tools = List("*"), systemPrompt = "")),
      agentLibrary = libOpt,
      agentActorRef = None,
      actorSystem = None,
      sharedResources = None,
      depth = 0,
      messages = Nil,
      wsSend = None,
      projectRoot = ""
    )

  private def props: Set[String] =
    DelegateTool.inputSchema("properties").flatMap(_.asObject).map(_.keys.toSet).getOrElse(Set.empty)

  private def required: List[String] =
    DelegateTool.inputSchema("required").flatMap(_.asArray).map(_.flatMap(_.asString).toList).getOrElse(Nil)

  // ---------- 1. schema 面 ----------

  test("schema: exactly task/description/device — the legacy parameters are gone"):
    assertEquals(props, Set("task", "description", "device"))
    assertEquals(required, List("task", "description"))
    Set("prompt", "agent", "lifecycle", "taskDescription", "images", "preset").foreach { legacy =>
      assert(!props.contains(legacy), s"legacy parameter must be deleted from the schema: $legacy")
    }
    assertEquals(DelegateTool.name, "Delegate")

  test("description carries the hard facts (absolute paths / cwd / 4 in flight / 3600s budget)"):
    val d = DelegateTool.description
    assert(d.contains("ABSOLUTE paths"), "description must state the absolute-path fact")
    assert(d.toLowerCase.contains("working directory is not guaranteed"), "description must state the cwd fact")
    assert(d.contains("4 kernel sessions in flight"), "description must state the R9 limit")
    assert(d.contains("3600s"), "description must state the wall-clock budget")
    // 旧语义残留守护：不再有 standalone 目标 / persistent 模式 / images 参数
    assert(!d.contains("standalone agent"), "standalone-target wording must be gone")
    assert(!d.contains("persistent"), "persistent mode must be gone")

  test("summarize: description + optional device target"):
    assertEquals(DelegateTool.summarize(JsonObject("description" -> "pull log".asJson)), "Delegate(pull log)")
    assertEquals(
      DelegateTool.summarize(JsonObject("description" -> "pull log".asJson, "device" -> "KAI".asJson)),
      "Delegate(pull log @ KAI)"
    )

  // ---------- 2. 目标解析 ----------

  test("task is required: empty task fails with a self-describing error"):
    for
      _ <- reset()
      input = JsonObject("task" -> "  ".asJson, "description" -> "x".asJson)
      res <- DelegateTool.call(input, ctxWith())
    yield res match
      case Left(err) => assert(err.message.contains("Missing required parameter: task"), err.message)
      case Right(v)  => fail(s"expected failure for empty task, got: $v")

  test("kernel definition missing → self-describing error (no standalone catalog any more)"):
    for
      _ <- reset()
      input = JsonObject("task" -> "do work".asJson, "description" -> "x".asJson)
      res <- DelegateTool.call(input, ctxWith())
    yield res match
      case Left(err) =>
        assert(err.message.contains("Kernel agent definition 'kernel' not found"), err.message)
        // 数据根渲染（home 硬编码 → 运行时动态化批 2026-09-11）：期望路径由运行期
        // 数据根插值 —— 本 suite 已 setDataRoot(tempRoot)，故断言取渲染值（默认 home
        // 下恰为旧字面 `~/.nebflow/agents/kernel`）。
        assert(
          err.message.contains(s"${PathUtil.dataRootRenderValue}/agents/kernel"),
          err.message
        )
        assert(!err.message.contains("Targetable standalone agents"), "standalone catalog must be gone")
      case Right(v) => fail(s"expected kernel-missing failure, got: $v")

  test("no agent library → self-describing error"):
    for res <- DelegateTool.call(JsonObject("task" -> "t".asJson), ctxWith(libOpt = None))
    yield assert(res.left.exists(_.message.contains("No agent library")), res.toString)

  test("target is fixed to the built-in kernel def — a non-kernel agent named in the call is impossible"):
    for
      _ <- reset()
      _ = writeAgent(name = "kernel")
      _ = writeAgent(name = "Coder")
      // 合法内核定义存在 + 无 ActorSystem ⇒ 走到 spawn 前置检查（证明解析成功）
      res <- DelegateTool.call(JsonObject("task" -> "do work".asJson, "description" -> "x".asJson), ctxWith())
    yield res match
      case Left(err) => assert(err.message.contains("requires ActorSystem"), err.message)
      case Right(v)  => fail(s"expected spawn-prerequisite failure, got: $v")

  test("depth limit still applies (kernel is a leaf, depth < MaxDepth required)"):
    for
      _ <- reset()
      _ = writeAgent(name = "kernel")
      deep = ctxWith().copy(depth = DelegateTool.MaxDepth)
      res <- DelegateTool.call(JsonObject("task" -> "t".asJson, "description" -> "x".asJson), deep)
    yield assert(res.left.exists(_.message.contains("Maximum sub-agent depth")), res.toString)

  // ---------- 3. R9 并发（U4=D1） ----------

  test("R9: the 5th concurrent call is rejected with the in-flight list (4 already in flight)"):
    val three = (1 to 3).toList.map(i => DelegateTool.InFlight(s"delegate-kernel-0000000$i", AgentStatus.Processing, 1000L))
    assert(DelegateTool.concurrencyError(three, now = 1000L).isEmpty, "3 in flight ⇒ the 4th call is admitted")
    val four = three :+ DelegateTool.InFlight("delegate-kernel-00000004", AgentStatus.Processing, 1000L)
    val err = DelegateTool.concurrencyError(four, now = 61_000L).getOrElse(fail("the 5th call must be rejected"))
    assert(err.message.contains("Delegate concurrency limit reached"), err.message)
    assertEquals(err.message.linesIterator.count(_.trim.startsWith("- delegate-kernel-")), 4)
    assert(err.message.contains("status=Processing"), err.message)

  test("R9/U4=D1: sessions waiting for an answer count toward the limit and are flagged"):
    val waiting = DelegateTool.InFlight("delegate-kernel-wait0001", AgentStatus.WaitingForUser, 1000L)
    val inFlight = waiting :: (2 to 4).toList.map(i => DelegateTool.InFlight(s"delegate-kernel-0000000$i", AgentStatus.Processing, 1000L))
    val err = DelegateTool.concurrencyError(inFlight, now = 5000L).getOrElse(fail("limit must be enforced"))
    assert(err.message.contains("delegate-kernel-wait0001"), err.message)
    assert(err.message.contains("WAITING for the user's answer"), err.message)
    assert(err.message.contains("ruling U4=D1"), err.message)

  test("R9 负控: after one finishes (2 in flight) delegation is allowed again"):
    val two = (1 to 2).toList.map(i => DelegateTool.InFlight(s"delegate-kernel-0000000$i", AgentStatus.Processing, 0L))
    assert(DelegateTool.concurrencyError(two, now = 0L).isEmpty)
    assertEquals(DelegateTool.MaxConcurrentPerRoot, 4)

  // ---------- 4. 设备预检（fail-fast，不静默本地执行） ----------

  test("device precheck: unknown device fails fast when NebLink is not initialized (never a silent local run)"):
    // 隔离实例上 RemoteExecutor.current 只在 GatewayMain initialize 后非空；本
    // 单元测试 JVM 里通常为 None。若同 JVM 的其它 spec 初始化了它，则跳过（用
    // assume）而不是给出假绿。
    assume(RemoteExecutor.current.isEmpty, "NebLink initialized in this JVM — covered by the isolated e2e instead")
    for
      _ <- reset()
      _ = writeAgent(name = "kernel")
      res <- DelegateTool.call(
        JsonObject("task" -> "restart the service".asJson, "description" -> "x".asJson, "device" -> "KAI".asJson),
        ctxWith()
      )
    yield res match
      case Left(err) =>
        assert(err.message.contains("""device="KAI""""), err.message)
        assert(err.message.contains("NebLink"), err.message)
        assert(err.message.contains("run LOCALLY"), err.message)
      case Right(v) => fail(s"expected fail-fast for unknown device, got: $v")

  test("device precheck 负控: no device parameter ⇒ no fail-fast (local runs are the default)"):
    for
      _ <- reset()
      _ = writeAgent(name = "kernel")
      res <- DelegateTool.call(JsonObject("task" -> "do work".asJson, "description" -> "x".asJson), ctxWith())
    yield res match
      case Left(err) =>
        // 走到 spawn 前置检查即证明设备预检没有拦（它只在 device 非空时生效）
        assert(err.message.contains("requires ActorSystem"), err.message)
      case Right(v) => fail(s"expected spawn-prerequisite failure, got: $v")

  // ---------- 5. 内核工具面常量（装配面单点来源） ----------

  test("kernel tool face = BaseTools + AskUserQuestion (7 items) and excludes Delegate/SubTask/TaskBoard"):
    val face = nebflow.agent.AgentCore.KernelFixedTools
    assertEquals(face, nebflow.agent.AgentCore.BaseTools + "AskUserQuestion")
    assertEquals(face.size, 7)
    Set("Delegate", "SubTask", "Task", "TaskBoard", "node_report", "AgentControl", "Mail").foreach { t =>
      assert(!face.contains(t), s"kernel must not hold: $t")
    }
    assertEquals(nebflow.agent.AgentCore.fixedToolsFor(AgentDef(name = "kernel", description = "", tools = Nil, systemPrompt = "")), face)

  test("kernel agent.json declaration is inert (ConvergedAgentNames) — mechanism-fixed only"):
    assert(nebflow.agent.AgentCore.ConvergedAgentNames.contains("kernel"))

end DelegateToolSpec
