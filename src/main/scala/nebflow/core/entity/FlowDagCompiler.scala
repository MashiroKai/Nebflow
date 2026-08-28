package nebflow.core.entity

import nebflow.core.entity.NodeRoute.{Goto, Parallel, ParallelDynamic, Return, Switch}

/**
 * Flow DAG 编译前端（#424，用户裁定 2026-08-26「以编译器的思想去设计」）。
 *
 * 单一编译入口：在 FlowExecute 调用返回前（0 spawn / 0 token）完成全部
 * 静态可查校验——语法（E-0xx）→ 占位符（E-1xx）→ 引用结构（E-2xx）→
 * 类型（E-3xx）→ agent 存在性。运行时只留动态判定（verdict 值、slots
 * 值、maxLoop、onError 分支）。
 *
 * 设计文档：~/.nebflow/docs/Nebflow/20260826_flow-dag-compiler-design.md
 * （commit 92eca5e，36 错误码三层体系，冻结）。
 *
 * 组合复用：结构规则调用 FlowStructure.validate（不复制逻辑），结果码化
 * E-208..E-212；agent 存在性由调用方传 agentNames（EntityLoader.validateFlow
 * 保留给其他调用路径）。
 */
object FlowDagCompiler:

  enum Severity:
    case Error, Warning

  /** 单条编译错误/警告——编译器风格：错误码 + 位置 + 原因 + 修正建议。 */
  case class Issue(
    code: String,
    nodeId: Option[String],
    fieldPath: String,
    reason: String,
    fix: String,
    severity: Severity
  ):
    def render(flowName: String): String =
      val loc = nodeId match
        case Some(id) if fieldPath.nonEmpty => s"node '$id'.$fieldPath"
        case Some(id)                       => s"node '$id'"
        case None                           => s"flow '$flowName'"
      s"[E-$code] $loc: $reason — $fix"

  /** 全量收集结果：errors 非空即拒绝（0 spawn）；warnings 不阻塞。 */
  case class CompileResult(flowName: String, issues: List[Issue]):
    def errors: List[Issue] = issues.filter(_.severity == Severity.Error)
    def warnings: List[Issue] = issues.filter(_.severity == Severity.Warning)
    def rejected: Boolean = errors.nonEmpty
    /** 按错误码排序输出（E-0xx → E-1xx → E-2xx → E-3xx 天然分层）。 */
    def renderAll: String =
      issues.sortBy(i => (i.code, i.nodeId.getOrElse(""))).map(_.render(flowName)).mkString("\n")

  private val MaxFanoutHardLimit = 256 // D5: 64-page deck × 4 headroom; prevents runaway fanout

  private val idRe = "^[a-zA-Z0-9_-]+$".r
  private val placeholderOpenRe = "\\{\\{".r
  private val placeholderRe = "\\{\\{([^}]*)\\}\\}".r
  // 与 FlowDagExecutor.resolveInput / parallelDispatchDynamic 同款正则族（单点对齐）
  private val allSlotsRe = "\\$([a-zA-Z0-9_-]+)\\.all\\.slots\\.([a-zA-Z0-9_-]+)".r
  private val allOutRe = "\\$([a-zA-Z0-9_-]+)\\.all\\.output".r
  private val slotsRe = "\\$([a-zA-Z0-9_-]+)\\.slots\\.([a-zA-Z0-9_-]+)".r
  private val outRe = "\\$([a-zA-Z0-9_-]+)\\.output".r
  private val paramsRe = "\\$params\\.([a-zA-Z0-9_-]+)".r
  // switch 表达式形态（FlowDagExecutor.extractSwitchValue 同款）
  private val switchExprRe = "^\\$([a-zA-Z0-9_-]+)\\.([a-zA-Z0-9_-]+)$".r

  /** 编译入口：Pass 0 语法 → 1 占位符 → 2 引用结构 → 3 结构 → 4 agent，全量收集。 */
  def validate(flow: FlowDagDef, agentNames: Set[String]): CompileResult =
    val issues = List.newBuilder[Issue]
    val nodeIds = flow.nodes.keySet

    def err(code: String, nodeId: Option[String], fieldPath: String, reason: String, fix: String): Unit =
      issues += Issue(code, nodeId, fieldPath, reason, fix, Severity.Error)
    def warn(code: String, nodeId: Option[String], fieldPath: String, reason: String, fix: String): Unit =
      issues += Issue(code, nodeId, fieldPath, reason, fix, Severity.Warning)

    // ── Pass 0: 语法层 E-0xx ─────────────────────────────────────────────
    if flow.nodes.isEmpty then
      err("001", None, "nodes", "must be a JSON object with ≥2 node entries",
        "add node definitions or fix the top-level JSON shape")
    flow.nodes.keys.filterNot(id => idRe.matches(id)).toList.sorted.foreach { id =>
      err("002", Some(id), "id", s"node id must match [a-zA-Z0-9_-]+ (got '$id')",
        "template references like $<id>.output cannot resolve it; rename to a plain identifier")
    }
    flow.nodes.toList.sortBy(_._1).foreach { (id, node) =>
      if node.agent.trim.isEmpty then
        err("003", Some(id), "agent", "must be a non-empty string naming a global agent",
          "set \"agent\": \"<name>\"")
      if node.maxRetries < 0 then
        err("010", Some(id), "maxRetries", s"must be a non-negative integer (got ${node.maxRetries})",
          "number of restart attempts")
    }
    if flow.entry.trim.isEmpty then
      err("006", None, "entry", "must name a node in nodes",
        "set \"entry\": \"<node-id>\"")
    if flow.maxFanout <= 0 || flow.maxFanout > MaxFanoutHardLimit then
      err("007", None, "maxFanout", s"must be a positive integer ≤ $MaxFanoutHardLimit (got ${flow.maxFanout})",
        "declare the parallelism this flow actually needs")
    if flow.maxLoop < 1 then
      err("008", None, "maxLoop", s"must be a positive integer (got ${flow.maxLoop})",
        "loop protection bound for redo cycles")
    if flow.name.trim.isEmpty then
      err("017", None, "name", "a non-empty display name is required for WS/UI events",
        "pass \"name\" to FlowExecute")
    flow.params.toList.sortBy(_._1).foreach { (pname, spec) =>
      (spec.min, spec.max) match
        case (Some(lo), Some(hi)) if lo > hi =>
          err("016", None, s"params.$pname", s"min ($lo) > max ($hi)",
            "declare a valid param spec (type int|string|bool, min/max only for int, min ≤ max)")
        case _ => ()
    }
    flow.nodes.toList.sortBy(_._1).foreach { (id, node) =>
      node.onComplete match
        case Switch(_, cases, _, _) if cases.isEmpty =>
          err("014", Some(id), "onComplete.cases", "switch needs at least one case",
            "declare the verdict routes (e.g. {\"pass\": \"agg\", \"fail\": \"redo\"})")
        case Parallel(Nil, _) =>
          err("015", Some(id), "onComplete.parallel", "fan must list at least one target",
            "an empty fan deadlocks at runtime")
        case _ => ()
    }

    // ── Pass 1: 占位符语义 E-1xx ────────────────────────────────────────
    val templateNodes: Set[String] =
      FlowStructure.parallelRoutes(flow).collect { case (_, pd: ParallelDynamic) => pd.template }.toSet
    flow.nodes.toList.sortBy(_._1).foreach { (id, node) =>
      scanPlaceholders(node.input, id, templateNodes.contains(id)) { (code, reason, fix) =>
        err(code, Some(id), "input", reason, fix)
      }
    }

    // ── Pass 2: 引用与结构语义 E-2xx + 类型 E-3xx ───────────────────────
    // 路由目标存在性（E-202）：覆盖 Goto / switch cases+default / parallel fan /
    // dynamic template 全部目标（递归展开 switch）。
    flow.nodes.toList.sortBy(_._1).foreach { (id, node) =>
      FlowStructure.routeTargets(node.onComplete).foreach { (target, _) =>
        if target != FlowStructure.ReturnNode && !nodeIds.contains(target) then
          err("202", Some(id), "onComplete", s"routes to unknown node '$target'",
            "the target must be a declared node id")
      }
    }
    // switch 表达式（E-207）
    flow.nodes.toList.sortBy(_._1).foreach { (id, node) =>
      node.onComplete match
        case Switch(expr, _, _, _) =>
          expr match
            case switchExprRe(snode, _) if !nodeIds.contains(snode) =>
              err("207", Some(id), "onComplete.switch", s"expression '$expr' references unknown node '$snode'",
                "point the switch at a declared node's verdict (e.g. \"$agg.verdict\")")
            case switchExprRe(_, _) => () // node exists — runtime evaluates the value
            case _ =>
              err("207", Some(id), "onComplete.switch", s"expression '$expr' is not of the form $$<node>.$$<field>",
                "point the switch at a declared node's verdict (e.g. \"$$agg.verdict\")")
        case _ => ()
    }
    // template 多主（E-304）：同一节点被 >1 个 ParallelDynamic 引用 → 实例 id 冲突
    FlowStructure.parallelRoutes(flow)
      .collect { case (owner, pd: ParallelDynamic) => (pd.template, owner) }
      .groupBy(_._1)
      .collect { case (t, pairs) if pairs.size > 1 => (t, pairs.map(_._2).sorted) }
      .toList.sortBy(_._1).foreach { (t, owners) =>
        err("304", Some(t), "", s"referenced as dynamic template by ${owners.size} fans (${owners.mkString(", ")})",
          "instance ids collide (template#1...); give each fan its own template node or restructure to share one fan")
      }

    def declaredOutputs(srcNode: String): Map[String, String] =
      flow.nodes.get(srcNode).map(_.outputs).getOrElse(Map.empty)

    /** allowSelf: ParallelDynamic.slotField reads the OWNER's slot — that is the
      * fanout's native semantics (planner fans out planner.slots.blocks), NOT the
      * E-201 self-reference error (which is a node's INPUT referencing its own
      * slots before it has run). */
    def checkSlotRef(nodeId: String, fieldPath: String, srcNode: String, field: String, requireArray: Boolean, allowSelf: Boolean): Unit =
      val declared = declaredOutputs(srcNode)
      if !nodeIds.contains(srcNode) then
        err("201", Some(nodeId), fieldPath,
          s"slot reference '$$$srcNode.slots.$field' — node '$srcNode' does not exist",
          "reference a declared node that declares outputs.$field (add \"outputs\": {\"$field\": \"string|array\"})")
      else if srcNode == nodeId && !allowSelf then
        err("201", Some(nodeId), fieldPath,
          s"slot reference '$$$srcNode.slots.$field' — self-reference: own slots do not exist before the node runs",
          "reference an upstream node's declared slot instead")
      else
        declared.get(field) match
          case None =>
            val known = if declared.isEmpty then "(none)" else declared.keys.toList.sorted.mkString(", ")
            err("201", Some(nodeId), fieldPath,
              s"slot reference '$$$srcNode.slots.$field' — node '$srcNode' does not declare outputs.$field (declared: $known)",
              s"add \"$field\": \"array\"|\"string\" to $srcNode.outputs, or fix the reference")
          case Some(t) if requireArray && t != "array" =>
            err("301", Some(nodeId), fieldPath,
              s"slot '$field' on '$srcNode' is declared \"$t\"",
              "dynamic fanout needs an \"array\" slot; change " + s"$srcNode.outputs.$field to \"array\"")
          case _ => ()

    flow.nodes.toList.sortBy(_._1).foreach { (id, node) =>
      // input 模板扫描（顺序：all.slots → all.output → slots → output → params）
      allSlotsRe.findAllMatchIn(node.input).foreach { m =>
        val (t, f) = (m.group(1), m.group(2))
        if !templateNodes.contains(t) then
          err("206", Some(id), "input",
            s"aggregate reference '$$$t.all.slots.$f' — '$t' is not a dynamic-fanout template (no {parallel:{slots,template}} references it)",
            "only template instances aggregate via .all; declare a fan with template '$t' or fix the reference")
        else if !declaredOutputs(t).contains(f) then
          val known = if declaredOutputs(t).isEmpty then "(none)" else declaredOutputs(t).keys.toList.sorted.mkString(", ")
          err("303", Some(id), "input",
            s"aggregate slot reference '$$$t.all.slots.$f' — template '$t' does not declare outputs.$f (declared: $known)",
            s"add \"$f\": \"string|array\" to $t.outputs so instances report the slot")
      }
      allOutRe.findAllMatchIn(node.input).foreach { m =>
        val t = m.group(1)
        if !templateNodes.contains(t) then
          err("206", Some(id), "input",
            s"aggregate reference '$$$t.all.output' — '$t' is not a dynamic-fanout template (no {parallel:{slots,template}} references it)",
            "only template instances aggregate via .all; declare a fan with template '$t' or fix the reference")
      }
      slotsRe.findAllMatchIn(node.input).foreach { m =>
        checkSlotRef(id, "input", m.group(1), m.group(2), requireArray = false, allowSelf = false)
      }
      outRe.findAllMatchIn(node.input).foreach { m =>
        if !nodeIds.contains(m.group(1)) then
          err("205", Some(id), "input", s"output reference '$$${m.group(1)}.output' — node '${m.group(1)}' does not exist",
            "reference a declared upstream node")
      }
      paramsRe.findAllMatchIn(node.input).foreach { m =>
        if !flow.params.contains(m.group(1)) then
          val known = if flow.params.isEmpty then "(none)" else flow.params.keys.toList.sorted.mkString(", ")
          err("204", Some(id), "input", s"parameter reference '$$params.${m.group(1)}' — flow declares no param '${m.group(1)}' (declared: $known)",
            "add it to params or fix the reference")
      }
      // dynamic fanout slotField（全引用 / 字面量两种形态）——owner 读自己的槽
      // 是 fanout 原生语义，allowSelf=true
      node.onComplete match
        case pd: ParallelDynamic =>
          pd.slotField match
            case slotsRe(src, f) => checkSlotRef(id, "onComplete.parallel.slots", src, f, requireArray = true, allowSelf = true)
            case literal        => checkSlotRef(id, "onComplete.parallel.slots", id, literal, requireArray = true, allowSelf = true)
        case _ => ()
    }

    // ── Pass 3: 结构 E-203 / E-208..E-212（FlowStructure.validate 组合复用+码化） ──
    // E-203.1 entry 存在性
    if flow.entry.nonEmpty && !nodeIds.contains(flow.entry) then
      err("203.1", Some(flow.entry), "", s"entry node '${flow.entry}' not in nodes",
        "point entry at a declared node")
    // E-203.2 孤儿节点（D2: warning 不阻塞）
    if flow.entry.nonEmpty && nodeIds.contains(flow.entry) then
      val (reached, _) = FlowStructure.reachFrom(flow, flow.entry, Set.empty)
      (nodeIds -- reached).toList.sorted.foreach { orphan =>
        warn("203.2", Some(orphan), "", s"unreachable from entry '${flow.entry}' (orphan)",
          "no route chain leads here; check routing targets or remove the node")
      }
    // E-203.3 无条件环（Goto/Parallel 直接回边；switch 内边是条件边可退出）
    unconditionalCycle(flow).foreach { cycle =>
      val path = cycle.mkString("→")
      err("203.3", Some(cycle.last), "", s"participates in an unconditional cycle ($path) with no switch condition to exit",
        "add a switch verdict route or a route to $return to break it")
    }
    // FlowStructure.validate 码化 E-208..E-212
    FlowStructure.validate(flow).foreach { msg =>
      (msg, flow.nodes.size) match
        case (m, n) if m.startsWith("flow must have at least 2 nodes") && n > 0 =>
          err("212", None, "", s"needs at least 2 nodes (got $n)",
            "a single agent is an agent + skill, not a flow")
        case (m, _) if m.startsWith("flow must have at least 2 nodes") => () // nodes.isEmpty → E-001 已报
        case (m, _) if m.startsWith("flow has no termination path") =>
          err("211", None, "", "no termination path — no node routes to $return",
            "add a route to $return (pure cycles only end in 'Max loop exceeded')")
        case (m, _) if m.startsWith("parallel fan of node") =>
          // "parallel fan of node 'x' has N branches — exceeds maxFanout M"
          val id = m.stripPrefix("parallel fan of node '").takeWhile(_ != '\'')
          err("208", Some(id), "onComplete.parallel", m, "raise maxFanout or trim the fan")
        case (m, _) if m.startsWith("parallel branch") =>
          // "parallel branch 'b' (fan of 'o') can reach $return before converging…"
          val id = m.stripPrefix("parallel branch '").takeWhile(_ != '\'')
          err("209", Some(id), "onComplete.parallel", m, "branches must converge first; early return is ambiguous")
        case (m, _) if m.startsWith("join ") && m.contains("mixes parallel and serial") =>
          val id = m.stripPrefix("join '").takeWhile(_ != '\'')
          err("210", Some(id), "", m, "a serial arrival corrupts the barrier count — route the serial in-edge through the fan's downstream")
        case (m, _) => err("299", None, "", m, "see the structural validation message") // 未知结构错误兜底（不应出现）
    }

    // ── Pass 4: agent 存在性 ─────────────────────────────────────────────
    flow.nodes.toList.sortBy(_._1).foreach { (id, node) =>
      if node.agent.nonEmpty && !agentNames.contains(node.agent) then
        err("401", Some(id), "agent", s"agent '${node.agent}' not found in the global library",
          "register the agent or fix the reference")
    }

    CompileResult(flow.name, issues.result())

  end validate

  /** E-103/101/102：扫描 input 的 {{}} 占位符——畸形（未闭合/空/坏字符）→ E-103；
    * 非 template 节点含任何 {{...}} → E-101；template 节点变量 ∉ {item,index,len} → E-102。 */
  private def scanPlaceholders(input: String, nodeId: String, isTemplate: Boolean)(
    report: (String, String, String) => Unit
  ): Unit =
    var idx = 0
    while idx < input.length do
      val start = input.indexOf("{{", idx)
      if start < 0 then return
      val end = input.indexOf("}}", start + 2)
      if end < 0 then
        report("103", s"malformed placeholder '${input.substring(start).take(40)}' — every {{ must close with }}",
          "close the placeholder or remove it")
        return
      val inner = input.substring(start + 2, end)
      if inner.isEmpty then
        report("103", "malformed placeholder '{{}}' — empty variable",
          "name a variable ({{item}}/{{index}}/{{len}} on templates)")
      else if !inner.matches("[a-zA-Z0-9_-]+") then
        report("103", s"malformed placeholder '{{$inner}}' — variable name has invalid characters",
          "use plain identifiers ({{item}}/{{index}}/{{len}} on templates)")
      else if isTemplate then
        if inner != "item" && inner != "index" && inner != "len" then
          report("102", s"template variable '{{$inner}}' is not supported",
            "only {{item}} {{index}} {{len}} are substituted on dynamic instances; check the spelling or remove it")
      else
        report("101", s"contains '{{$inner}}' but this node is NOT a dynamic-fanout template",
          "static routes substitute no {{}} placeholders — reference the upstream slot instead ($<node>.slots.<field> / $<template>.all.slots.<field>), or make this node a dynamic fanout template")
      idx = end + 2
    end while

  /** E-203.3：在「无条件边」子图（Goto / Parallel fan / dynamic template；排除
    * switch 内边——条件是运行时可退出）上找第一个环。 */
  private def unconditionalCycle(flow: FlowDagDef): Option[List[String]] =
    val succ: Map[String, List[String]] = flow.nodes.toList.map { (id, node) =>
      val targets = node.onComplete match
        case Goto(t)              => t :: Nil
        case Parallel(fan, _)     => fan
        case ParallelDynamic(_, t, _) => t :: Nil
        case Switch(_, _, _, _)   => Nil
        case Return               => Nil
      id -> targets.filter(t => t != FlowStructure.ReturnNode && flow.nodes.contains(t)).distinct
    }.toMap

    // 三色 DFS：0=White 未访问, 1=Gray 在栈上, 2=Black 已出栈
    val color = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    val stack = scala.collection.mutable.ListBuffer.empty[String]

    def dfs(node: String): Option[List[String]] =
      color(node) = 1
      stack += node
      var result: Option[List[String]] = None
      val it = succ.getOrElse(node, Nil).iterator
      while result.isEmpty && it.hasNext do
        val nxt = it.next()
        color(nxt) match
          case 1 => // 环：从 nxt 到当前栈顶
            result = Some(stack.dropWhile(_ != nxt).toList :+ nxt)
          case 0 =>
            dfs(nxt) match
              case c @ Some(_) => result = c
              case None        => ()
          case _ => ()
      if result.isEmpty then
        stack.remove(stack.length - 1)
        color(node) = 2
      result

    flow.nodes.keys.toList.sorted.iterator.flatMap { n =>
      if color(n) == 0 then dfs(n) else None
    }.toList.headOption

  /** Flow-node supervision P2 (2026-08-26 §5.P2): dynamic (inline FlowExecute)
    * DAG nodes get supervision defaults at COMPILE time — onError=Restart +
    * maxRetries=1 — so a retryable LLM failure checkpoint-restarts the node
    * instead of scrapping the whole flow. Explicit user JSON always wins (an
    * onError=stop or a custom maxRetries is preserved as written). Predefined
    * flow.json files do NOT pass through this function (compatibility
    * commitment: their nodes keep onError=None → Stop exactly as before).
    * The transform is visible in the compiled output (what you compile is
    * what runs). */
  def applyDynamicDefaults(flow: FlowDagDef): FlowDagDef =
    flow.copy(nodes = flow.nodes.map { (id, node) =>
      if node.onError.isEmpty then
        id -> node.copy(
          onError = Some(OnError.Restart),
          maxRetries = if node.maxRetries == 0 then 1 else node.maxRetries
        )
      else id -> node
    })

end FlowDagCompiler
