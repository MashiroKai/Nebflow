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
 *  1. schema 面：恰两参数 `task`/`description`（旧 `agent`/`lifecycle`/
 *     `taskDescription`/`images`/`preset`/`prompt` 全部退役——旧断言在本文件里
 *     逐条反向钉死；`device` 亦于 2026-09-14 作者裁定 U1/U2 随 schema 摘除）。
 *  2. Target resolution: the built-in `kernel` def. kernelgen-ext batch (2026-09-26): a missing disk
 *     def no longer refuses to start — resolveKernelDef synthesizes the built-in
 *     fail-safe def, prompt = runtime mirror system.md -> classpath seed
 *     (`seed/agents/kernel/system.md`, prompt-only manifest item `agents:kernel`)
 *     -> the embedded default. Availability first; the former "definition not
 *     found" refusal is retired (its negative is re-pinned below).
 *  3. R9 并发：每根会话 ≤ 4（U4=D1：等待答复占额度；错误含在飞清单 + 等待标注）。
 *  4. 设备面：**本工具已无 `device` 参数**（本地编排件；远端只发生在内核六件上）。
 *     本文件反向钉死该摘除：stray `device` 键**不被消费**（无预检、不拦、不出现在
 *     摘要），调用照常走到 spawn 前置检查。
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

  test("schema: exactly task/description — the legacy parameters AND device are gone"):
    assertEquals(props, Set("task", "description"))
    assertEquals(required, List("task", "description"))
    Set("prompt", "agent", "lifecycle", "taskDescription", "images", "preset").foreach { legacy =>
      assert(!props.contains(legacy), s"legacy parameter must be deleted from the schema: $legacy")
    }
    // 2026-09-14 作者裁定 U1/U2：`device` 是摘除目标（非 legacy 参数）——逐字钉死。
    assert(!props.contains("device"), s"device must be gone from the Delegate schema, got: $props")
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

  test("summarize: description only — a stray device key must NOT surface (device face removed)"):
    assertEquals(DelegateTool.summarize(JsonObject("description" -> "pull log".asJson)), "Delegate(pull log)")
    // 摘除前本行断言 `"Delegate(pull log @ KAI)"`；摘除后 stray 键**不被消费**：
    // 摘要与不带 device 时逐字相同（反向钉死，防 device 面回潮）。
    assertEquals(
      DelegateTool.summarize(JsonObject("description" -> "pull log".asJson, "device" -> "KAI".asJson)),
      DelegateTool.summarize(JsonObject("description" -> "pull log".asJson))
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

  test("kernel definition missing => fail-safe built-in def, NOT a refusal (kernelgen-ext 2026-09-26)"):
    for
      _ <- reset()
      input = JsonObject("task" -> "do work".asJson, "description" -> "x".asJson)
      res <- DelegateTool.call(input, ctxWith())
    yield res match
      case Left(err) =>
        // The engine never refuses to start a kernel because the disk def is
        // missing: resolveKernelDef synthesizes the built-in def (prompt chain:
        // runtime mirror -> classpath seed -> embedded default) and the call
        // reaches the spawn prerequisites — here failing only on the absent
        // ActorSystem (suite runs without one by design).
        assert(err.message.contains("requires ActorSystem"), err.message)
        assert(!err.message.contains("Kernel agent definition"),
          "the old kernel-missing refusal must be retired, got: " + err.message)
        assert(!err.message.contains("Restore the definition and retry"),
          "the old restore-and-retry refusal must be retired, got: " + err.message)
      case Right(v) => fail(s"expected spawn-prerequisite failure, got: $v")

  test("fail-safe tier 1: a mirror system.md without agent.json drives the synthesized def's prompt"):
    for
      _ <- reset()
      _ = os.write.over(agentsDir / "kernel" / "system.md", "MIRROR-PROMPT-MARKER", createFolders = true)
      res <- DelegateTool.resolveKernelDef(ctxWith())
    yield res match
      case Right(defn) =>
        assertEquals(defn.name, "kernel")
        assert(defn.systemPrompt.contains("MIRROR-PROMPT-MARKER"),
          "tier 1 = the seed-managed runtime mirror file (what the prompt editor reads/writes)")
        assertEquals(defn.tools.toSet, nebflow.agent.AgentCore.KernelFixedTools,
          "the synthesized def carries the mechanism-fixed tool face")
        assertEquals(defn.category, "standalone", "converged name pins the category")
      case Left(err) => fail(s"fail-safe def expected, got: ${err.message}")

  test("fail-safe tier 2: no mirror => the classpath seed text drives the prompt (contract + plugin pointer present)"):
    for
      _ <- reset() // empty agents dir: no disk def, no mirror file
      res <- DelegateTool.resolveKernelDef(ctxWith())
    yield res match
      case Right(defn) =>
        assertEquals(defn.name, "kernel")
        assert(defn.systemPrompt.contains("## ⑤ Creating plugins"),
          "the seed text (with the author-directed plugin pointer) reached the prompt")
        assert(defn.systemPrompt.contains("**Capability boundary (hard):**"),
          "the verbatim migrated contract section reached the prompt")
        // Note: like the three agents' mirrors, the seed file travels verbatim — its
        // leading HTML comment (cold-start authority note) stays part of the file the
        // prompt is read from. Only the embedded default tier (code constant) is
        // comment-free by construction.
      case Left(err) => fail(s"fail-safe def expected, got: ${err.message}")

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

  // ---------- 4. 设备面摘除后的行为（2026-09-14 作者裁定 U1/U2） ----------
  // 摘除前本段 = 「设备预检 fail-fast 正控 + 负控」两条（预检函数已随 S1b 全删）。
  // 同步到新形态（同条数、非弱化）：**stray `device` 键不再被本工具消费**——
  // 既无预检也无拦截，调用与不带 device 时走同一条路（spawn 前置检查）。

  test("device face removed: a stray device key is NOT consumed — the call proceeds to the spawn prerequisite"):
    for
      _ <- reset()
      _ = writeAgent(name = "kernel")
      res <- DelegateTool.call(
        JsonObject("task" -> "restart the service".asJson, "description" -> "x".asJson, "device" -> "KAI".asJson),
        ctxWith()
      )
    yield res match
      case Left(err) =>
        assert(err.message.contains("requires ActorSystem"), err.message)
        // 旧 fail-fast 文案必须已删净（否则就是「schema 摘了、实现还在处理」的漂移）
        assert(!err.message.contains("""device="KAI""""), err.message)
        assert(!err.message.toLowerCase.contains("neblink"), err.message)
        assert(!err.message.contains("run LOCALLY"), err.message)
      case Right(v) => fail(s"expected spawn-prerequisite failure, got: $v")

  test("device face removed 负控: no device parameter ⇒ identical behaviour (local runs are the only mode)"):
    for
      _ <- reset()
      _ = writeAgent(name = "kernel")
      res <- DelegateTool.call(JsonObject("task" -> "do work".asJson, "description" -> "x".asJson), ctxWith())
    yield res match
      case Left(err) =>
        // 走到 spawn 前置检查即证明没有设备面拦截（工具本体已不读 device）
        assert(err.message.contains("requires ActorSystem"), err.message)
      case Right(v) => fail(s"expected spawn-prerequisite failure, got: $v")

  // ---------- 5. 内核工具面常量（装配面单点来源） ----------

  test("kernel tool face = BaseTools + AskUserQuestion (7 items) and excludes Delegate/SubTask/TaskBoard"):
    val face = nebflow.agent.AgentCore.KernelFixedTools
    assertEquals(face, nebflow.agent.AgentCore.BaseTools + "AskUserQuestion")
    assertEquals(face.size, 7)
    // R2 2026-09-12：kernel 面照旧零 Mail（节点面不挂 Mail），并在排除清单中
    // 加挂两个已删净退役件 Task/NodeMessage——他们不得因 R2 回潮。
    Set("Delegate", "SubTask", "Task", "NodeMessage", "TaskBoard", "node_report", "AgentControl", "Mail").foreach { t =>
      assert(!face.contains(t), s"kernel must not hold: $t")
    }
    assertEquals(nebflow.agent.AgentCore.fixedToolsFor(AgentDef(name = "kernel", description = "", tools = Nil, systemPrompt = "")), face)

  test("kernel agent.json declaration is inert (ConvergedAgentNames) — mechanism-fixed only"):
    assert(nebflow.agent.AgentCore.ConvergedAgentNames.contains("kernel"))

end DelegateToolSpec
