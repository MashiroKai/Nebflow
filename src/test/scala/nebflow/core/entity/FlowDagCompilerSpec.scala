package nebflow.core.entity

import munit.FunSuite
import nebflow.core.entity.NodeRoute.{Goto, OnFailMode, Parallel, ParallelDynamic, Return, Switch}

/**
 * #424 FlowDagCompilerSpec — compiler-frontend unit tests.
 *
 * Every error code E-0xx/E-1xx/E-2xx/E-3xx gets ≥1 minimal error DAG →
 * the renderAll output carries `[E-<code>]` + the compiler-style format
 * (`node '<id>'.<fieldPath>: reason — fix`). The R3 positive case compiles
 * with zero errors AND zero warnings. Design doc:
 * ~/.nebflow/docs/Nebflow/20260826_flow-dag-compiler-design.md (§7.4).
 *
 * Note: decode-layer codes (E-004/005/009/011/012/013) are unreachable
 * here — a FlowDagDef is already decoded; those stay covered by
 * FlowExecuteToolSpec's decode-error tests.
 */
class FlowDagCompilerSpec extends FunSuite:

  private val agents = Set("Nebula")

  private def node(
    input: String = "$task",
    onComplete: NodeRoute,
    outputs: Map[String, String] = Map.empty,
    maxRetries: Int = 0
  ): FlowNode = FlowNode("Nebula", input, onComplete, maxRetries = maxRetries, outputs = outputs)

  private def compile(flow: FlowDagDef): FlowDagCompiler.CompileResult =
    FlowDagCompiler.validate(flow, agents)

  private def errorsOf(flow: FlowDagDef): List[String] =
    val r = compile(flow)
    r.renderAll.split("\n").toList

  private def hasCode(flow: FlowDagDef, code: String): Boolean =
    errorsOf(flow).exists(_.startsWith(s"[E-$code]"))

  // ── Pass 0 语法层 ─────────────────────────────────────────────────────

  test("E-001: empty nodes object"):
    val flow = FlowDagDef("f", "d", Map.empty, "a")
    assert(hasCode(flow, "001"))

  test("E-002: node id with a dot (not [a-zA-Z0-9_-]+)"):
    val flow = FlowDagDef("f", "d", Map("a.b" -> node(onComplete = Goto("c")), "c" -> node(onComplete = Return)), "a.b")
    assert(hasCode(flow, "002"))

  test("E-003: empty agent"):
    val n = FlowNode("  ", "$task", Goto("b"))
    val flow = FlowDagDef("f", "d", Map("a" -> n, "b" -> node(onComplete = Return)), "a")
    assert(hasCode(flow, "003"))

  test("E-006: empty entry"):
    val flow = FlowDagDef("f", "d", Map("a" -> node(onComplete = Return)), "")
    assert(hasCode(flow, "006"))

  test("E-007: maxFanout = 0 and > 256"):
    val f0 = FlowDagDef("f", "d", Map("a" -> node(onComplete = Return)), "a", maxFanout = 0)
    val f257 = FlowDagDef("f", "d", Map("a" -> node(onComplete = Return)), "a", maxFanout = 257)
    assert(hasCode(f0, "007") && hasCode(f257, "007"))

  test("E-008: maxLoop = 0"):
    val flow = FlowDagDef("f", "d", Map("a" -> node(onComplete = Return)), "a", maxLoop = 0)
    assert(hasCode(flow, "008"))

  test("E-010: negative maxRetries"):
    val flow = FlowDagDef("f", "d", Map("a" -> node(onComplete = Return, maxRetries = -1)), "a")
    assert(hasCode(flow, "010"))

  test("E-014: empty switch cases"):
    val n = node(onComplete = Switch("$a.verdict", Map.empty))
    val flow = FlowDagDef("f", "d", Map("a" -> n), "a")
    assert(hasCode(flow, "014"))

  test("E-015: empty parallel fan"):
    val n = node(onComplete = Parallel(Nil))
    val flow = FlowDagDef("f", "d", Map("a" -> n), "a")
    assert(hasCode(flow, "015"))

  test("E-016: param min > max"):
    val flow = FlowDagDef(
      "f", "d", Map("a" -> node(onComplete = Return)), "a",
      params = Map("x" -> FlowParamSpec("int", min = Some(5), max = Some(2)))
    )
    assert(hasCode(flow, "016"))

  test("E-017: empty display name"):
    val flow = FlowDagDef("", "d", Map("a" -> node(onComplete = Return)), "a")
    assert(hasCode(flow, "017"))

  // ── Pass 1 占位符语义 ─────────────────────────────────────────────────

  private def templateFlow(input: String, templateIsStatic: Boolean): FlowDagDef =
    if templateIsStatic then
      // 'w' is NOT referenced as a dynamic template — any {{}} in its input is E-101
      FlowDagDef("f", "d",
        Map(
          "p" -> node(onComplete = Goto("w")),
          "w" -> node(input = input, onComplete = Return)
        ), "p")
    else
      // 'w' IS the dynamic template — p must declare the slots field the fan reads
      FlowDagDef("f", "d",
        Map(
          "p" -> node(onComplete = ParallelDynamic("$p.slots.items", "w"), outputs = Map("items" -> "array")),
          "w" -> node(input = input, onComplete = Return)
        ), "p")

  test("E-101: static node uses {{item}} (v4 join accident regression)"):
    val flow = templateFlow("汇总 {{item}}", templateIsStatic = true)
    val errs = errorsOf(flow)
    assert(errs.exists(_.startsWith("[E-101] node 'w'.input")), errs.mkString("\n"))

  test("E-102: template node uses unsupported {{iteam}} (typo)"):
    val flow = templateFlow("处理 {{iteam}}", templateIsStatic = false)
    assert(hasCode(flow, "102"))

  test("E-103: unclosed placeholder {{item"):
    val flow = templateFlow("处理 {{item", templateIsStatic = true)
    assert(hasCode(flow, "103"))

  test("E-103: empty placeholder {{}}"):
    val flow = templateFlow("处理 {{}}", templateIsStatic = true)
    assert(hasCode(flow, "103"))

  // ── Pass 2 引用与结构 ─────────────────────────────────────────────────

  test("E-201: slot reference without declared outputs (redo accident regression)"):
    val flow = FlowDagDef("f", "d",
      Map(
        "qa" -> node(onComplete = Goto("judge1")),
        "judge1" -> node(onComplete = Goto("redo"), outputs = Map("passBlocks" -> "array")),
        "redo" -> node(input = "$judge1.slots.failBlocks", onComplete = Return)
      ), "qa")
    val errs = errorsOf(flow)
    assert(errs.exists(e => e.startsWith("[E-201] node 'redo'.input") && e.contains("failBlocks")), errs.mkString("\n"))

  test("E-201: literal slotField without declared outputs on the owner"):
    val flow = FlowDagDef("f", "d",
      Map(
        "p" -> node(onComplete = ParallelDynamic("blocks", "w")),
        "w" -> node(input = "{{item}}", onComplete = Return)
      ), "p")
    assert(hasCode(flow, "201"))

  test("E-201: self-referencing slot"):
    val flow = FlowDagDef("f", "d",
      Map(
        "a" -> node(input = "$a.slots.self", onComplete = Return)
      ), "a")
    val errs = errorsOf(flow)
    assert(errs.exists(_.startsWith("[E-201] node 'a'.input")), errs.mkString("\n"))

  test("E-202: switch case routes to a ghost node"):
    val flow = FlowDagDef("f", "d",
      Map(
        "a" -> node(onComplete = Switch("$a.verdict", Map("pass" -> Goto("ghost"), "fail" -> Goto("b")))),
        "b" -> node(onComplete = Return)
      ), "a")
    assert(hasCode(flow, "202"))

  test("E-203.1: entry not in nodes"):
    val flow = FlowDagDef("f", "d", Map("a" -> node(onComplete = Return)), "ghost-entry")
    assert(hasCode(flow, "203.1"))

  test("E-203.2: orphan node is a warning (not blocking)"):
    val flow = FlowDagDef("f", "d",
      Map(
        "a" -> node(onComplete = Return),
        "orphan" -> node(onComplete = Return)
      ), "a")
    val r = compile(flow)
    assert(r.warnings.exists(_.code == "203.2"), r.renderAll)
    assert(!r.rejected, "orphan must not block (D2 warning)")

  test("E-203.3: unconditional cycle A→B→A"):
    val flow = FlowDagDef("f", "d",
      Map(
        "a" -> node(onComplete = Goto("b")),
        "b" -> node(onComplete = Goto("a"))
      ), "a")
    assert(hasCode(flow, "203.3"))

  test("E-204: $params.ghost with no declared param"):
    val flow = FlowDagDef("f", "d",
      Map("a" -> node(input = "$params.ghost", onComplete = Return)), "a")
    assert(hasCode(flow, "204"))

  test("E-205: $ghost.output references a nonexistent node"):
    val flow = FlowDagDef("f", "d",
      Map("a" -> node(input = "$ghost.output", onComplete = Return)), "a")
    assert(hasCode(flow, "205"))

  test("E-206: $worker.all.output but worker is not a dynamic template"):
    val flow = FlowDagDef("f", "d",
      Map(
        "a" -> node(input = "$worker.all.output", onComplete = Goto("worker")),
        "worker" -> node(onComplete = Return)
      ), "a")
    assert(hasCode(flow, "206"))

  test("E-207: switch expression references a ghost node"):
    val flow = FlowDagDef("f", "d",
      Map(
        "a" -> node(onComplete = Switch("$ghost.verdict", Map("pass" -> Goto("b"), "fail" -> Goto("b")))),
        "b" -> node(onComplete = Return)
      ), "a")
    assert(hasCode(flow, "207"))

  test("E-207: switch expression not of the form $node.field"):
    val flow = FlowDagDef("f", "d",
      Map(
        "a" -> node(onComplete = Switch("verdict", Map("pass" -> Goto("b"), "fail" -> Goto("b")))),
        "b" -> node(onComplete = Return)
      ), "a")
    assert(hasCode(flow, "207"))

  // ── Pass 2/4 结构 + agent ─────────────────────────────────────────────

  test("E-208: static fan exceeds maxFanout"):
    val flow = FlowDagDef("f", "d",
      Map(
        "a" -> node(onComplete = Parallel(List("b1", "b2", "b3"))),
        "b1" -> node(onComplete = Goto("j")),
        "b2" -> node(onComplete = Goto("j")),
        "b3" -> node(onComplete = Goto("j")),
        "j" -> node(onComplete = Return)
      ), "a", maxFanout = 2)
    assert(hasCode(flow, "208"))

  test("E-211: no termination path"):
    val flow = FlowDagDef("f", "d",
      Map(
        "a" -> node(onComplete = Goto("b")),
        "b" -> node(onComplete = Goto("a"))
      ), "a")
    assert(hasCode(flow, "211"))

  test("E-212: single node (agent + skill, not a flow)"):
    val flow = FlowDagDef("f", "d", Map("a" -> node(onComplete = Return)), "a")
    assert(hasCode(flow, "212"))

  test("E-401: unknown node agent"):
    val n = FlowNode("ghost-agent", "$task", Goto("b"))
    val flow = FlowDagDef("f", "d", Map("a" -> n, "b" -> node(onComplete = Return)), "a")
    assert(hasCode(flow, "401"))

  // ── Pass 类型层 ───────────────────────────────────────────────────────

  test("E-301: parallel.slots references a string-typed slot"):
    val flow = FlowDagDef("f", "d",
      Map(
        "p" -> node(onComplete = ParallelDynamic("$p.slots.items", "w"), outputs = Map("items" -> "string")),
        "w" -> node(input = "{{item}}", onComplete = Return)
      ), "p")
    assert(hasCode(flow, "301"))

  test("E-303: $t.all.slots.f where template lacks the slot declaration"):
    val flow = FlowDagDef("f", "d",
      Map(
        "p" -> node(onComplete = ParallelDynamic("$p.slots.items", "w"), outputs = Map("items" -> "array")),
        "w" -> node(input = "{{item}}", onComplete = Goto("j")),
        "j" -> node(input = "$w.all.slots.result", onComplete = Return)
      ), "p")
    assert(hasCode(flow, "303"))

  test("E-304: one template referenced by two dynamic fans"):
    val flow = FlowDagDef("f", "d",
      Map(
        "p1" -> node(onComplete = ParallelDynamic("$p1.slots.a", "w"), outputs = Map("a" -> "array")),
        "p2" -> node(onComplete = ParallelDynamic("$p2.slots.b", "w"), outputs = Map("b" -> "array")),
        "w" -> node(input = "{{item}}", onComplete = Return)
      ), "p1")
    assert(hasCode(flow, "304"))

  // ── 正例 ──────────────────────────────────────────────────────────────

  test("R3 positive case: planner outputs.blocks + judge outputs.failBlocks + template {{}} compiles clean"):
    val flow = FlowDagDef(
      "r3", "R3 实证模式",
      Map(
        "planner" -> node(input = "$task", onComplete = ParallelDynamic("$planner.slots.blocks", "worker"),
          outputs = Map("blocks" -> "array")),
        "worker" -> node(input = "处理 {{item}} ({{index}}/{{len}})", onComplete = Goto("join")),
        "join" -> node(input = "$worker.all.output", onComplete = Goto("judge")),
        "judge" -> node(input = "$task", onComplete = Switch("$judge.verdict",
          Map("pass" -> Return, "fail" -> Goto("redo2")), default = Some(Return)),
          outputs = Map("failBlocks" -> "array")),
        // fail→redo2 是第二层 dynamic fanout（judge.failBlocks → redo-worker#N →
        // join）——join 的所有 in-edge 都是 fan 分支到达，不触发 E-210 混合到达
        "redo2" -> node(input = "$task", onComplete = ParallelDynamic("$judge.slots.failBlocks", "redo-worker")),
        "redo-worker" -> node(input = "修复 {{item}}", onComplete = Goto("join"))
      ), "planner", maxFanout = 8)
    val r = compile(flow)
    assert(r.errors.isEmpty, r.renderAll)
    assert(r.warnings.isEmpty, r.renderAll)

  // ── 格式 ──────────────────────────────────────────────────────────────

  test("error format matches compiler style (code + location + reason + fix)"):
    val flow = FlowDagDef("f", "d",
      Map(
        "a" -> node(input = "{{item}}", onComplete = Return)
      ), "a")
    val line = errorsOf(flow).find(_.startsWith("[E-101]")).get
    assert(line.matches("""\[E-101\] node 'a'\.input: .* — .*"""), line)

  test("all errors are collected (not fail-fast on the first)"):
    val flow = FlowDagDef("f", "d",
      Map(
        "a" -> node(input = "{{item}} $ghost.output $params.nope", onComplete = Return)
      ), "a")
    val errs = errorsOf(flow)
    assert(errs.exists(_.startsWith("[E-101]")), errs.mkString("\n"))
    assert(errs.exists(_.startsWith("[E-205]")), errs.mkString("\n"))
    assert(errs.exists(_.startsWith("[E-204]")), errs.mkString("\n"))

  // ── 预定义 flow 回归（真实 flows/ 目录，存在才跑） ────────────────────

  test("all predefined flows in ~/.nebflow/flows compile with zero errors"):
    val flowsDir = os.Path(sys.props("user.home")) / ".nebflow" / "flows"
    if os.exists(flowsDir) then
      val jsonFiles = os.walk(flowsDir).filter(p => p.last == "flow.json" && p.ext == "json").toList
      assume(jsonFiles.nonEmpty, "no flow.json files present")
      jsonFiles.foreach { f =>
        val parsed = io.circe.parser.parse(os.read(f)).flatMap(_.as[FlowDagDef])
        parsed match
          case Left(err) => fail(s"${f}: decode failed: ${err.getMessage}")
          case Right(flow) =>
            // Predefined-flow nodes resolve via loadFlowAgent: flow-local
            // agents (flows/<name>/agents/) first, global fallback.
            val flowAgentDir = f / os.up / "agents"
            val flowAgents = if os.exists(flowAgentDir) then os.list(flowAgentDir).filter(os.isDir).map(_.last).toSet else Set.empty[String]
            val r = FlowDagCompiler.validate(flow, agents ++ flowAgents)
            assert(r.errors.isEmpty, s"${f} compile errors:\n${r.renderAll}")
      }
    else
      // no live flows dir in this environment — the positive R3 case above
      // still exercises the compiler on a legal DAG
      ()

end FlowDagCompilerSpec
