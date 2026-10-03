package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.AgentDef
import nebflow.agent.AgentLibrary
import nebflow.shared.PathUtil

/**
 * DelegateTool 前门（2026-09-11 Delegate 恢复批 · 极简内核形态）：
 *
 *  1. schema 面：恰两参数 `task`/`project`（unified-delegate 批 2026-10-03——
 *     只填任务与可选项目；旧 `description`/`agent`/`lifecycle`/`taskDescription`/
 *     `images`/`preset`/`prompt` 全部退役——旧断言在本文件里逐条反向钉死；
 *     `device` 亦于 2026-09-14 作者裁定 U1/U2 随 schema 摘除）。
 *  2. Target resolution: the built-in `kernel` def, CODE-DEFINED (builtin-def
 *     batch 2026-10-03, author directive ① "code is the single source of truth"):
 *     resolveKernelDef returns the BuiltinAgents kernel def — no disk read, no
 *     seed mirror, no fail-safe tiers (all retired). The former "definition not
 *     found" refusal stays retired; its negative is re-pinned below, plus a
 *     negative pin that stray disk files cannot influence the code prompt.
 *  3. Concurrency: NO cap (author decision 2026-09-26 — the former R9 gate U4=D1,
 *     limit 4 per root session, is retired; pinned at the source face below).
 *  4. 设备面：**本工具已无 `device` 参数**（本地编排件；远端只发生在内核六件上）。
 *     本文件反向钉死该摘除：stray `device` 键**不被消费**（无预检、不拦、不出现在
 *     摘要），调用照常走到 spawn 前置检查。
 *  5. description hard facts: self-contained brief / continuation address
 *     `delegate:<id>` / no concurrency cap / workspace face.
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

  end writeAgent

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

  test("schema: exactly task/project — description and the legacy parameters are gone"):
    assertEquals(props, Set("task", "project"))
    assertEquals(required, List("task"))
    Set("prompt", "agent", "lifecycle", "taskDescription", "images", "preset").foreach { legacy =>
      assert(!props.contains(legacy), s"legacy parameter must be deleted from the schema: $legacy")
    }
    // 2026-09-14 作者裁定 U1/U2：`device` 是摘除目标（非 legacy 参数）——逐字钉死。
    assert(!props.contains("device"), s"device must be gone from the Delegate schema, got: $props")
    assertEquals(DelegateTool.name, "Delegate")

  test("description carries the hard facts (self-contained brief / delegate: address / no concurrency cap)"):
    val d = DelegateTool.description
    assert(d.toLowerCase.contains("self-contained brief"), "description must state the brief fact")
    assert(d.contains("delegate:<id>"), "description must state the continuation address form")
    assert(d.contains("No hard concurrency limit"), "description must state the author-directed no-limit face (2026-09-26)")
    assert(!d.contains("at most 4"), "the retired 4-cap wording must be gone from the description")
    // unified-delegate 守护：kernel:<id> 旧地址形态不再出现在 description
    assert(!d.contains("kernel:<id>"), "the retired kernel:<id> address must be gone, got the description")
    assert(!d.contains("persistent"), "persistent mode must be gone")

  test("summarize: task first-line label — stray keys must NOT surface"):
    assertEquals(DelegateTool.summarize(JsonObject("task" -> "pull the log\nsecond line".asJson)), "Delegate(pull the log)")
    // label = 任务首行截断 60；stray 键（description/device）不被消费。
    assertEquals(
      DelegateTool.summarize(JsonObject("task" -> "pull the log".asJson, "device" -> "KAI".asJson)),
      DelegateTool.summarize(JsonObject("task" -> "pull the log".asJson))
    )
    val long = "x" * 80
    assert(DelegateTool.summarize(JsonObject("task" -> long.asJson)).length < 80, "long first line is truncated")

  // ---------- 2. 目标解析 ----------

  test("task is required: empty task fails with a self-describing error"):
    for
      _ <- reset()
      input = JsonObject("task" -> "  ".asJson)
      res <- DelegateTool.call(input, ctxWith())
    yield res match
      case Left(err) => assert(err.message.contains("Missing required parameter: task"), err.message)
      case Right(v) => fail(s"expected failure for empty task, got: $v")

  test("kernel definition missing => fail-safe built-in def, NOT a refusal (kernelgen-ext 2026-09-26)"):
    for
      _ <- reset()
      input = JsonObject("task" -> "do work".asJson)
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

  test("code-defined def: stray disk files CANNOT influence the kernel prompt (single source = code, builtin-def 2026-10-03)"):
    for
      _ <- reset()
      // 写入全套磁盘假定义（mirror system.md + agent.json）：对收敛名都是死信
      _ = os.write.over(agentsDir / "kernel" / "system.md", "MIRROR-PROMPT-MARKER", createFolders = true)
      _ = os.write.over(agentsDir / "kernel" / "agent.json",
        Json.obj("name" -> "kernel".asJson, "description" -> "STRAY-DESCRIPTION-MARKER".asJson).noSpaces,
        createFolders = true)
      res <- IO.delay(nebflow.core.entity.BuiltinAgents.entry("kernel").map(_.toAgentDef).get)
    yield
      assertEquals(res.name, "kernel")
      assert(!res.systemPrompt.contains("MIRROR-PROMPT-MARKER"),
        "the disk mirror is a dead letter — the prompt comes from BuiltinAgents (code)")
      assert(!res.description.contains("STRAY-DESCRIPTION-MARKER"),
        "the disk agent.json is a dead letter — the description comes from BuiltinAgents (code)")
      assertEquals(res.category, "standalone", "converged name pins the category")

  test("code-defined def carries the kernel contract (when-to-use / boundary / plugin pointer)"):
    for
      _ <- reset() // empty agents dir: nothing on disk at all — code is enough
      res <- IO.delay(nebflow.core.entity.BuiltinAgents.entry("kernel").map(_.toAgentDef).get)
    yield
      assertEquals(res.name, "kernel")
      assert(res.systemPrompt.contains("## ⑤ Creating plugins"),
        "the author-directed plugin pointer reached the code prompt")
      assert(res.systemPrompt.contains("**Capability boundary (hard):**"),
        "the verbatim contract section reached the code prompt")
      assert(!res.systemPrompt.startsWith("<!--"),
        "the code prompt carries no seed-machinery HTML comment")

  test("unified-delegate: default workspace is created and the call reaches spawn prerequisites"):
    for
      _ <- reset()
      res <- DelegateTool.call(JsonObject("task" -> "do work".asJson), ctxWith())
    yield
      assert(res.left.exists(_.message.contains("requires ActorSystem")), res.toString)
      assert(os.exists(tempRoot / "general"), "the default general workspace is created on first contextless dispatch")

  test("unified-delegate: an unmounted project is an explicit refusal, nothing spawned"):
    for
      _ <- reset()
      res <- DelegateTool.call(JsonObject("task" -> "do work".asJson, "project" -> "no-such-project".asJson), ctxWith())
    yield
      assert(res.left.exists(_.message.contains("DELEGATE_PROJECT_UNMOUNTED")), res.toString)

  test("continuation address: the receipt-facing constant is the delegate: form"):
    val line = nebflow.core.delegate.DelegateRegistry.continuationLine("delegate-kernel-abc12345")
    assert(line.contains("\"delegate:delegate-kernel-abc12345\""), line)
    assert(!line.contains("kernel:delegate"), line)

  test("no agent library: resolution no longer depends on it — the call reaches the spawn prerequisites"):
    for res <- DelegateTool.call(JsonObject("task" -> "t".asJson), ctxWith(libOpt = None))
    yield assert(res.left.exists(_.message.contains("requires ActorSystem")), res.toString)

  test("target is fixed to the built-in kernel def — a non-kernel agent named in the call is impossible"):
    for
      _ <- reset()
      _ = writeAgent(name = "kernel")
      _ = writeAgent(name = "Coder")
      // 合法内核定义存在 + 无 ActorSystem ⇒ 走到 spawn 前置检查（证明解析成功）
      res <- DelegateTool.call(JsonObject("task" -> "do work".asJson), ctxWith())
    yield res match
      case Left(err) => assert(err.message.contains("requires ActorSystem"), err.message)
      case Right(v) => fail(s"expected spawn-prerequisite failure, got: $v")

  test("depth limit still applies (kernel is a leaf, depth < MaxDepth required)"):
    for
      _ <- reset()
      _ = writeAgent(name = "kernel")
      deep = ctxWith().copy(depth = DelegateTool.MaxDepth)
      res <- DelegateTool.call(JsonObject("task" -> "t".asJson), deep)
    yield assert(res.left.exists(_.message.contains("Maximum sub-agent depth")), res.toString)

  // ---------- 3. Concurrency cap retired (author decision 2026-09-26: no hard limit) ----------
  // The former R9 gate (ruling U4=D1, limit 4 per root session) is gone: InFlight /
  // concurrencyError / concurrencyCheck were removed from DelegateTool and nothing
  // replaced them. The two tests below pin the retirement: the prompt faces carry the
  // no-limit wording (three copies kept in sync), and the spawn-path source carries no
  // cap code - re-adding a gate turns the source-face test red (consciousness gate for
  // a cap comeback; the 2026-08 rate-limit incident note stays on the object comment).

  test("no hard concurrency limit: description and the code-defined prompt carry the no-limit wording in sync"):
    val d = DelegateTool.description
    val prompt = nebflow.core.entity.BuiltinAgents.entry("kernel").map(_.systemPrompt).getOrElse("")
    val faces = List(
      "description" -> d,
      "code-defined prompt (BuiltinAgents)" -> prompt
    )
    for (name, text) <- faces do
      assert(text.contains("No hard concurrency limit"),
        s"$name must promise no hard concurrency limit (author 2026-09-26)")
      assert(!text.contains("at most 4"), s"$name must not carry the retired 4-cap wording: $name")
    // builtin-def 批：种子资源已退役——classpath 上不得再有 seed/agents 面。
    val gone = Option(getClass.getClassLoader.getResource("seed/agents/kernel/system.md"))
    assert(gone.isEmpty, "seed/agents resources are retired (builtin-def 2026-10-03) — the classpath must not carry them")

  test("the concurrency gate stays retired at the source face (the 5th concurrent spawn is admitted - nothing rejects it)"):
    val src = os.read(os.pwd / "src" / "main" / "scala" / "nebflow" / "core" / "tools" / "DelegateTool.scala")
    assert(!src.contains("MaxConcurrentPerRoot"), "the retired cap constant must stay out of the spawn-path source")
    assert(!src.contains("concurrencyCheck"), "the retired gate check must stay out of the spawn-path source")
    assert(!src.contains("concurrencyError"), "the retired gate error must stay out of the spawn-path source")
    assert(!src.contains("case class InFlight"), "the retired in-flight snapshot type must stay out of the spawn-path source")

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
      res <- DelegateTool.call(JsonObject("task" -> "do work".asJson), ctxWith())
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
    assertEquals(
      nebflow.agent.AgentCore
        .fixedToolsFor(AgentDef(name = "kernel", description = "", tools = Nil, systemPrompt = "")),
      face
    )

  test("kernel agent.json declaration is inert (ConvergedAgentNames) — mechanism-fixed only"):
    assert(nebflow.agent.AgentCore.ConvergedAgentNames.contains("kernel"))

end DelegateToolSpec
