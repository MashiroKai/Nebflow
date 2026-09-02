package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.NebflowLogger
import nebflow.core.entity.EntityLoader
import nebflow.core.project.*

/**
 * Node 工具集（#28 阶段 0，方案 §2.2 —— 任务分发器用）。
 *
 * 全部走工具、0 文件写入（验收①）：拓扑变更经 FlowMapStore 单事务原子更新；
 * 校验（agent 存在性 / 引用存在 / 环检测 / 1 对多 / 状态守卫 / loop detect）
 * 在工具层统一拦截，0 spawn 0 token。
 *
 * 边模型（§2.3 原子性）：`node.out` 是单权威边——设 out 时事务内同步更新
 * 目标节点的 `in`（反向一致），旧目标 in 移除、新目标 in 追加，杜绝双写漂移。
 * in 参数用于创建/追加 barrier 入边（等价把上游 out 改指向本节点）。
 */
object NodeTools:
  private val logger = NebflowLogger.forName("nebflow.node.tools")

  /** 解析 project（参数优先，fallback ctx.projectName）。 */
  def resolveProject(project: Option[String], ctx: ToolContext): IO[Either[String, ProjectRuntime]] =
    val name = project.orElse(ctx.projectName).getOrElse("")
    if name.isEmpty then IO.pure(Left("Missing 'project' parameter (and no project context)"))
    else
      ProjectRuntimeRegistry.get(name).flatMap {
        case Some(rt) => IO.pure(Right(rt))
        case None => IO.pure(Left(s"Project '$name' is not mounted. Use ProjectCreate first."))
      }

  /** 引用节点存在性（活动区 + 归档区）。 */
  def ensureNodeExists(rt: ProjectRuntime, id: String): IO[Either[String, Unit]] =
    rt.store.findNode(id).map {
      case Some(_) => Right(())
      case None => Left(s"Referenced node '$id' not found in project '${rt.project.name}'")
    }

  /** 状态守卫：目标已运行 → 拒绝接线（§2.2「目标已运行，输入已冻结」）。 */
  def ensureTargetNotRunning(rt: ProjectRuntime, id: String): IO[Either[String, Unit]] =
    rt.store.getNode(id).map {
      case Some(n) if n.status == NodeLifecycle.Running =>
        Left(s"Node '$id' is running — its input is frozen. NodeCancel it first, then rewire.")
      case _ => Right(())
    }

  /** 1 对多拒绝：out 必须是单值 string 或 null（数组 → 拒绝，§2.4）。
    * 字符串 "null"（LLM 断开时的常见写法）归一化为 None——历史实证：归档
    * n-8a481bd0.out 被持久化成字面串 "null"，deliverOut 沿它 findNode("null")
    * 悬空；加载侧 FlowMapStore 另有同规则净化兜底存量数据。 */
  def parseOut(outJson: Option[Json]): Either[String, Option[String]] =
    outJson match
      case None => Right(None) // 未传 out = 不改动
      case Some(j) if j.isNull => Right(None) // out=null = 断开（悬空化）
      case Some(j) if j.isArray => Left("'out' must be a single node id or null — 1-to-many is not supported. Create N independent entry nodes instead.")
      case Some(j) =>
        j.asString match
          case Some(s) =>
            val t = s.trim
            if t.equalsIgnoreCase("null") then Right(None) // 字符串 "null" = 断开
            else if t.nonEmpty then Right(Some(t))
            else Left("'out' must be a non-empty string node id, \"Nebula\", or null")
          case None => Left("'out' must be a non-empty string node id, \"Nebula\", or null")

  /** in 参数宽容解析（修复次因 B）：三形态统一接受——
    * ① 原生 JSON 数组 ["a","b"]；② JSON 数组字符串 "[\"a\",\"b\"]"（LLM 常见：
    * 把数组整体字符串化）；③ 逗号串 "a,b"。元素 trim + 空段/空串过滤。
    * 形态②解析失败 → 清晰报错附原文（绝不静默吞成单个字面 id——实证案例 1：
    * 分发器三次接线失败均因 "[\"n-8a481bd0\",...]" 被当单 id → not found）。
    * （原 NodeEditTool 私有方法，提为 NodeTools 公开以便单测。） */
  def parseIn(inJson: Option[Json]): Either[String, List[String]] =
    inJson match
      case None => Right(Nil)
      case Some(j) if j.isNull => Right(Nil)
      case Some(j) if j.isArray =>
        Right(j.asArray.getOrElse(Vector.empty).flatMap(_.asString).toList.map(_.trim).filter(_.nonEmpty))
      case Some(j) if j.isString =>
        val s = j.asString.getOrElse("").trim
        if s.isEmpty then Right(Nil)
        else if s.startsWith("[") then
          io.circe.parser.parse(s) match
            case Right(arr) if arr.isArray =>
              Right(arr.asArray.getOrElse(Vector.empty).flatMap(_.asString).toList.map(_.trim).filter(_.nonEmpty))
            case Right(_) => Left(s"'in' parses as JSON but is not an array: $s")
            case Left(err) => Left(s"'in' looks like a JSON array but failed to parse (${err.getMessage}) — raw: $s")
        else Right(s.split(',').map(_.trim).filter(_.nonEmpty).toList)
      case Some(_) => Left("'in' must be a node id string or an array of node ids")

  /** 事务内设 out 边（单权威）：旧目标 in 移除 + 新目标 in 追加 + 本节点 out 更新。 */
  def setOut(rt: ProjectRuntime, fromId: String, newOut: Option[String]): IO[Unit] =
    rt.store.mutate { s =>
      val from = s.nodes(fromId)
      val oldOut = from.out
      val afterOld = oldOut match
        case Some(t) if t != "Nebula" =>
          s.nodes.get(t).map(tn => s.nodes.updated(t, tn.copy(in = tn.in.filterNot(_ == fromId)))).getOrElse(s.nodes)
        case _ => s.nodes
      val afterNew = newOut match
        case Some(t) if t != "Nebula" =>
          afterOld.get(t).map(tn => afterOld.updated(t, tn.copy(in = (tn.in :+ fromId).distinct))).getOrElse(afterOld)
        case _ => afterOld
      s.copy(nodes = afterNew.updated(fromId, from.copy(out = newOut)))
    }.void

  // ── 环检测（对外暴露给测试）────────────────────────────

  def wouldCreateCycle(rt: ProjectRuntime, fromId: String, to: String): IO[Boolean] =
    rt.store.wouldCreateCycle(fromId, to)

  /** task 文本归一化（loop detect 用）：trim + 空白折叠。 */
  def normalizeTask(t: String): String = t.trim.replaceAll("\\s+", " ")

  /** loop detect（§2.6）：同 agent + 同 task 归一化，活动区已有
    * running/completed 节点 → 疑似重复派发（TTL 窗口内 = 活动区仍显示）。
    * 返回匹配的节点（无则 None）。NodeEdit 创建入口节点时校验（0 spawn）。
    * R1 复核（blocked 反馈重入设计 §6）：Terminal 含 blocked——blocked 节点
    * **应**计入重复派发（防对同一任务重复派发；blocked ≠ 可重派）。 */
  def findDuplicateDispatch(rt: ProjectRuntime, agentName: String, task: String): IO[Option[NodeDef]] =
    val norm = normalizeTask(task)
    if norm.isEmpty then IO.pure(None)
    else
      rt.store.snapshot.map { s =>
        s.nodes.values.find { n =>
          n.agent == agentName &&
          n.task.exists(t => normalizeTask(t) == norm) &&
          (n.status == NodeLifecycle.Running || NodeLifecycle.Terminal.contains(n.status))
        }
      }

  /** wiring 变更最终态事件（子任务：Flow Map 事件推送补全）：受影响节点逐一读
    * store 发 nodeUpdated（NodeList 同构 payload）——上游 out 改指 / 旧新目标 in
    * 增删 / 本节点 in·out 变更都从这里走，事件与 NodeList 数据源一致。
    * 活动区已消失（TTL/归档）节点不发——归档节点不在 Flow Map 视图内。 */
  def emitWiringUpdates(rt: ProjectRuntime, nodeIds: List[String]): IO[Unit] =
    nodeIds.traverse_ { id =>
      rt.store.getNode(id).flatMap {
        case Some(n) => rt.engine.emitUpdated(n)
        case None    => IO.unit
      }
    }

  /** 节点运行后台化（收口③：dispatcher 会话结束语义修复）。
    *
    * startNode/deliverOutTo 会同步等待节点终态（runWithAgent 的 resultDeferred
    * race 无超时，下游链条投递也在该 fiber 顺序推进）。NodeEdit 工具若在分发器
    * turn 的工具 fiber 里直接调用，turn 会被阻塞整个节点运行期（实证
    * dispatcher-95f8434b：诊断节点 35min + 修复节点 19min，单 turn 56min）：
    * 期间零 lastActivity 更新 → TaskStuckWatcher 误判卡死；入口节点被串行化
    * （建 A 等 A 完才建 B）；「节点建完/接线完成」后分发器会话仍 Processing
    * 挂起。fork 到独立 fiber 后工具立即返回——NodeEdit 文档契约「async,
    * non-blocking」的落地。节点失败已在 fiber 内落 store（failNode），此处仅
    * 兜底记日志；分发器取消/结束不影响已派发节点（节点由 NodeCancel 独立管理）。 */
  def runDetached(rt: ProjectRuntime, what: String)(io: IO[Unit]): IO[Unit] =
    io
      .handleErrorWith(e =>
        logger.error(
          s"[${rt.project.name}] detached node run '$what' failed: ${Option(e.getMessage).getOrElse(e.toString)}"
        )
      )
      .start
      .void

  /** NodeList 工具 / REST flow-map 端点共用的载荷（nodes/worktrees/meta，§2.2 NodeList 返回结构）。
    * 节点序列化统一走 NodePayload.buildNodeJson（与 WS 事件 payload 同构）。 */
  def buildNodeListPayload(rt: ProjectRuntime): IO[Json] =
    for
      s <- rt.store.snapshot
      arch <- rt.store.archiveSnapshot
    yield
      val now = System.currentTimeMillis()
      val nodes = s.nodes.values.toList.sortBy(_.createdAt).map(n => NodePayload.buildNodeJson(n, now))
      val wtDir = os.Path(rt.project.workspace) / ".nebflow" / "worktrees"
      val worktrees =
        if os.exists(wtDir) then os.list(wtDir).filter(os.isDir).map(_.last).toList
        else Nil
      Json.obj(
        "nodes" -> nodes.asJson,
        "worktrees" -> worktrees.asJson,
        "meta" -> Json.obj(
          "project" -> rt.project.name.asJson,
          "updatedAt" -> s.updatedAt.asJson,
          "archived" -> arch.nodes.size.asJson
        )
      )

object NodeEditTool extends Tool:
  val name = "NodeEdit"

  val description =
    """Create or edit a Node in a project's Flow Map — the task dispatcher's single tool for topology (create / wire / rewire / disconnect).
## When to Use
- The task dispatcher builds the project's node graph: create entry nodes (task + out), wire barriers (in), rewire running/completed nodes, disconnect (out=null).
- All topology changes go through this tool — 0 files written (0 hand-written files; the store owns flow-map.json).

## Parameters
- **project** (required): project name (the dispatcher's project).
- **nodename**: display name (unique within the Flow Map; existing name = edit that node).
- **agent** (required on create): global agent name for the node.
- **task** (optional): the node's task context — an entry node (task present, no in) starts running immediately on create.
- **in** (optional): upstream node id(s) to add as barrier inputs (multi-in = barrier; each upstream's out is rewired to this node).
- **deps** (optional, replace-on-provide): upstream node id(s) this node waits on for COMPLETION SIGNAL only — no result is injected (downstream input = its own task, self-sufficient). Needs a result? Use in. Only needs "run after upstream completes"? Use deps. Need both? Write both. Not passed = unchanged; passed (any form, including [] / null) = whole-list replacement. Upstream failed/cancelled/blocked never triggers a deps waiter (it stays pending and visible). A running upstream is legal to depend on (waiting for it IS the semantics); editing deps on a RUNNING node is rejected (input frozen).
- **out** (optional, single value): node id, "Nebula", or null. Single-value semantics: setting replaces the old out (rewire); arrays are rejected (1-to-many not supported); null disconnects (result retained, re-wire later auto-delivers).
- **skill** / **mcp** / **worktree** / **preset** (optional): node configuration (worktree must exist under workspace/.nebflow/).
- **maxRetries** (optional, default 1).
- **abandon** (optional, default false): abandon a terminal (blocked/completed/failed/cancelled) OR wiring/pending node → status=cancelled + display TTL (audit-logged). The dispatcher's give-up action for blocked nodes and the topology-cleanup exit for retired wiring/pending nodes; use NodeCancel for running nodes instead.

## Semantics
- nodename missing → create (agent required; at least one connection required — in, deps, or out; a node with task but zero connections is rejected).
- nodename exists → edit: in appends barrier inputs; deps replaces the whole dependency list (when provided); out sets/replaces/disconnects.
- Connection floor (裁定①): every node must keep at least one connection (in / deps / out, out="Nebula" counts). Create with all three absent → EMPTY_NODE_CONNECTION; an edit that would leave the node with zero connections is rejected the same way (disconnecting a single edge is legal as long as another connection remains; result is never cleared by disconnecting).
- Blocked node edit: changing task/agent (or in/out) on a blocked node reactivates it — status returns to wiring/pending, deliveredTo cleared, blockCount preserved (round history kept), completed upstream results re-delivered, then the node reruns with the new input. No-op if nothing actually changed.
- abandon=true → terminal node becomes cancelled with display TTL (frontend removes it after TTL; result kept in archive).
- Validation (0 spawn): agent exists; referenced nodes exist; DAG cycle check (DFS); 1-to-many rejected; target running → rejected ("input frozen — NodeCancel first").
- Create an entry node (task present, no in) → it starts running immediately (async, non-blocking). Node result = the agent's final output, auto-saved to the node's result and delivered along out (node → downstream barrier / Nebula → injected to root session / null → retained, auto-delivered when wired later)."""
  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "project" -> Json.obj("type" -> "string".asJson, "description" -> "Project name (required)".asJson),
        "nodename" -> Json.obj("type" -> "string".asJson, "description" -> "Display name; unique within the Flow Map".asJson),
        "agent" -> Json.obj("type" -> "string".asJson, "description" -> "Global agent name (required on create)".asJson),
        "task" -> Json.obj("type" -> "string".asJson, "description" -> "Node task context; entry nodes (task, no in) run immediately".asJson),
        "in" -> Json.obj("oneOf" -> Json.arr(
          Json.obj("type" -> "string".asJson),
          Json.obj("type" -> "array".asJson, "items" -> Json.obj("type" -> "string".asJson))
        ).asJson, "description" -> "Upstream node id(s) to add as barrier inputs".asJson),
        "deps" -> Json.obj("oneOf" -> Json.arr(
          Json.obj("type" -> "string".asJson),
          Json.obj("type" -> "array".asJson, "items" -> Json.obj("type" -> "string".asJson))
        ).asJson, "description" -> "Upstream node id(s) this node waits on for completion signal only (no result injected). Replace-on-provide: [] / null clears. Needs the result? Use in".asJson),
        "out" -> Json.obj("oneOf" -> Json.arr(
          Json.obj("type" -> "string".asJson),
          Json.obj("type" -> "null".asJson)
        ).asJson, "description" -> "Single out target: node id, \"Nebula\", or null (disconnect). Arrays rejected (1-to-many)".asJson),
        "skill" -> Json.obj("type" -> "string".asJson),
        "mcp" -> Json.obj("type" -> "string".asJson),
        "worktree" -> Json.obj("type" -> "string".asJson, "description" -> "Relative path under workspace/.nebflow/ (must exist; create via git worktree)".asJson),
        "preset" -> Json.obj("type" -> "string".asJson),
        "maxRetries" -> Json.obj("type" -> "integer".asJson),
        "abandon" -> Json.obj("type" -> "boolean".asJson, "description" -> "Abandon a TERMINAL node (blocked/completed/failed/cancelled) → cancelled + display TTL".asJson)
      ),
      "required" -> Json.arr("project".asJson, "nodename".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val nn = input("nodename").flatMap(_.asString).getOrElse("")
    s"NodeEdit($nn)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 200 then result.take(197) + "..." else result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val project = input("project").flatMap(_.asString)
    val nodename = input("nodename").flatMap(_.asString).getOrElse("")
    val agent = input("agent").flatMap(_.asString)
    val task = input("task").flatMap(_.asString)
    val skill = input("skill").flatMap(_.asString)
    val mcp = input("mcp").flatMap(_.asString)
    val worktree = input("worktree").flatMap(_.asString)
    val preset = input("preset").flatMap(_.asString)
    val maxRetries = input("maxRetries").flatMap(_.asNumber).flatMap(_.toInt)
    val abandon = input("abandon").flatMap(_.asBoolean).getOrElse(false)
    val inJson = input("in")
    val depsJson = input("deps")
    val outJson = input("out")

    if nodename.isEmpty then IO.pure(Left(ToolError("Missing 'nodename'")))
    else
      NodeTools.resolveProject(project, ctx).flatMap {
        case Left(err) => IO.pure(Left(ToolError(err)))
        case Right(rt) =>
          rt.store.snapshot.flatMap { s =>
            s.nodes.values.find(_.name == nodename) match
              case Some(existing) => editNode(rt, existing, agent, task, abandon, inJson, depsJson, outJson, ctx)
              case None =>
                if abandon then IO.pure(Left(ToolError(s"Node '$nodename' not found — abandon requires an existing node")))
                else createNode(rt, nodename, agent, task, skill, mcp, worktree, preset, maxRetries, inJson, depsJson, outJson)
          }
      }

  // ── 新建 ─────────────────────────────────────────────

  private def createNode(
    rt: ProjectRuntime,
    nodename: String,
    agent: Option[String],
    task: Option[String],
    skill: Option[String],
    mcp: Option[String],
    worktree: Option[String],
    preset: Option[String],
    maxRetries: Option[Int],
    inJson: Option[Json],
    depsJson: Option[Json],
    outJson: Option[Json]
  ): IO[Either[ToolError, String]] =
    val agentName = agent.getOrElse("")
    val inIds = NodeTools.parseIn(inJson)
    // deps 复用 parseIn 三形态宽容解析（deps 设计 §1.2：不新写解析器）；报错文案
    // 把参数名替换成 deps，避免误导（解析器文案写死 'in'）。
    val depsIds = NodeTools.parseIn(depsJson).left.map(err => err.replace("'in'", "'deps'"))
    val outEither = NodeTools.parseOut(outJson)
    (agentName, inIds, depsIds, outEither) match
      case ("", _, _, _) => IO.pure(Left(ToolError("New node requires 'agent'")))
      case (_, Left(err), _, _) => IO.pure(Left(ToolError(err)))
      case (_, _, Left(err), _) => IO.pure(Left(ToolError(err)))
      case (_, _, _, Left(err)) => IO.pure(Left(ToolError(err)))
      case (_, Right(ins), Right(deps), Right(out)) =>
        // 校验五（裁定①零连接下限，deps 设计 §1.2）：in/deps/out 三者全缺 → 拒绝创建，
        // 错误码 EMPTY_NODE_CONNECTION。task 是内容不是连接，不计入下限——旧合法形态
        // 「task-only 入口节点」自此须声明 out（通常 out=Nebula）或 in/deps。
        if ins.isEmpty && deps.isEmpty && out.isEmpty then
          IO.pure(Left(ToolError(s"Node '$nodename' must declare at least one connection — provide in, deps, or out.")))
        else
          // 1. agent 存在性
          EntityLoader.loadAgent(agentName).flatMap {
            case None => IO.pure(Left(ToolError(s"Agent '$agentName' not found in global library")))
            case Some(_) =>
              // 2. worktree 存在性
              worktree match
                case Some(wt) =>
                  val wtPath = os.Path(rt.project.workspace) / ".nebflow" / wt
                  if !os.exists(wtPath) then
                    IO.pure(Left(ToolError(s"Worktree '$wt' not found under ${rt.project.workspace}/.nebflow/ — create it first via git worktree add")))
                  else proceed(rt, nodename, agentName, task, skill, mcp, worktree, preset, maxRetries, ins, deps, out)
                case None => proceed(rt, nodename, agentName, task, skill, mcp, worktree, preset, maxRetries, ins, deps, out)
          }

  private def proceed(
    rt: ProjectRuntime,
    nodename: String,
    agentName: String,
    task: Option[String],
    skill: Option[String],
    mcp: Option[String],
    worktree: Option[String],
    preset: Option[String],
    maxRetries: Option[Int],
    ins: List[String],
    deps: List[String],
    out: Option[String]
  ): IO[Either[ToolError, String]] =
    val nodeId = s"n-${java.util.UUID.randomUUID().toString.take(8)}"
    for
      // 引用存在性 + 环检测（in 上游 → 本节点；deps 上游 → 本节点；本节点 → out 目标）。
      // wouldCreateCycle 已含 deps 反向边（deps 设计 §1.2 校验二：混合图单点覆盖）——
      // create 场景新节点无出边，环检天然为 false，保留调用与 in 对称（防御未来变化）。
      inOk <- ins.traverse(id => NodeTools.ensureNodeExists(rt, id))
      depsOk <- deps.traverse(id => NodeTools.ensureNodeExists(rt, id))
      cycleIn <- ins.traverse(id => NodeTools.wouldCreateCycle(rt, fromId = id, to = nodeId))
      cycleDeps <- deps.traverse(id => NodeTools.wouldCreateCycle(rt, fromId = id, to = nodeId))
      outOk <- out match
        case Some(t) if t != "Nebula" => NodeTools.ensureNodeExists(rt, t).map(Right(_))
        case _ => IO.pure(Right(()))
      cycleOut <- out match
        case Some(t) if t != "Nebula" => NodeTools.wouldCreateCycle(rt, fromId = nodeId, to = t).map(Some(_))
        case _ => IO.pure(None)
      // loop detect（§2.6）：入口节点（有 task）同 agent+task 归一化重复 → 拒
      dup <- task match
        case Some(t) if t.trim.nonEmpty => NodeTools.findDuplicateDispatch(rt, agentName, t)
        case _ => IO.pure(None)
      // 校验短路（0 spawn）
      validated <-
        if inOk.exists(_.isLeft) then
          IO.pure(Left(ToolError(inOk.collectFirst { case Left(e) => e }.getOrElse("invalid in"))))
        else if depsOk.exists(_.isLeft) then
          IO.pure(Left(ToolError(depsOk.collectFirst { case Left(e) => e }.getOrElse("invalid deps"))))
        else if cycleIn.exists(identity) then
          IO.pure(Left(ToolError(s"Cycle detected: adding in would create a loop (A→B→A) — DAG must stay acyclic")))
        else if cycleDeps.exists(identity) then
          IO.pure(Left(ToolError(s"Cycle detected: adding deps would create a loop — DAG must stay acyclic")))
        else if outOk.isLeft then
          IO.pure(Left(ToolError(outOk.swap.toOption.getOrElse("invalid out"))))
        else if cycleOut.exists(identity) then
          IO.pure(Left(ToolError(s"Cycle detected: out → ${out.getOrElse("")} would create a loop — DAG must stay acyclic")))
        else if dup.isDefined then
          IO.pure(Left(ToolError(s"疑似重复派发: agent '$agentName' already has a ${dup.get.status} node with the same task (node '${dup.get.name}'). Check NodeList before re-dispatching.")))
        else IO.pure(Right(()))
      result <- validated match
        case Left(err) => IO.pure(Left(err))
        case Right(_) =>
          val now = System.currentTimeMillis()
          val node = NodeDef(
            id = nodeId,
            name = nodename,
            agent = agentName,
            skill = skill,
            mcp = mcp,
            worktree = worktree,
            preset = preset,
            task = task,
            in = Nil,
            deps = deps,
            out = None,
            status = if task.isDefined && ins.isEmpty then NodeLifecycle.Pending else NodeLifecycle.Wiring,
            retries = 0,
            maxRetries = maxRetries.getOrElse(1),
            createdAt = now
          )
          // 单事务：加节点（deps 单侧持有，无上游侧镜像边要写）+ in 边（上游 out → 本节点）+ out 边
          val mutateIO = rt.store.mutate { s =>
            val withNode = s.copy(nodes = s.nodes + (nodeId -> node))
            val withIns = ins.foldLeft(withNode) { (acc, upId) =>
              acc.nodes.get(upId) match
                case Some(up) =>
                  acc.copy(nodes = acc.nodes.updated(upId, up.copy(out = Some(nodeId))))
                case None => acc
            }
            val withInList = ins.foldLeft(withIns) { (acc, upId) =>
              acc.nodes.get(nodeId) match
                case Some(n) => acc.copy(nodes = acc.nodes.updated(nodeId, n.copy(in = (n.in :+ upId).distinct)))
                case None => acc
            }
            val withOut = out match
              case Some(t) if t != "Nebula" =>
                withInList.nodes.get(t) match
                  case Some(tn) => withInList.copy(nodes = withInList.nodes.updated(t, tn.copy(in = (tn.in :+ nodeId).distinct)))
                  case None => withInList
              case _ => withInList
            withOut.copy(nodes = withOut.nodes.updated(nodeId, withOut.nodes(nodeId).copy(out = out)))
          }
          val createIO: IO[Unit] =
            mutateIO.flatMap { s =>
              // 新节点事件（NodeList 同构 payload，in/out 以 store 最终态为准）
              rt.engine.emitCreated(s.nodes(nodeId)) *>
                // wiring 变更事件（barrier 合并接线 §2.3）：上游 out 改指本节点 +
                // out 目标 in 追加——此前只有 nodeCreated，改写的节点无事件（缺失补齐）
                NodeTools.emitWiringUpdates(rt, ins ++ out.toList.filterNot(_ == "Nebula")) *>
                // D1 修复（验收④b，@a4b5d184 spec 实证）：in 引用已完成上游（活动区
                // 或归档区——findNode 兜底）→ 立即投递其结果，等同 §2.3「悬空节点
                // out 接入下游 = 已完成节点改接」路径。归档节点活动区已消失，
                // completeNode 的 deliverOut 不会再触发，唯一投递入口就是这里。
                // 运行中上游不投递（barrier 等待语义——completeNode 时 deliverOut 自然投）。
                // 后台化（收口③）：deliverOutTo 内 startNode 同步等下游节点终态，
                // 直接调用会阻塞 NodeEdit 工具 fiber（见 runDetached 注释）。
                NodeTools.runDetached(rt, s"deliver retained upstream results -> $nodeId")(
                  ins.traverse_ { upId =>
                    rt.store.findNode(upId).flatMap {
                      // R1（blocked 反馈重入设计 §6）：blocked 是终态且 result=反馈渲染串，
                      // 但反馈串不是可投结果——D1 显式排除 blocked（failed 错误投递语义保持不变）
                      case Some(up) if up.result.isDefined && up.status != NodeLifecycle.Blocked && NodeLifecycle.Terminal.contains(up.status) =>
                        rt.engine.deliverOutTo(up, nodeId, up.result.get)
                      case _ => IO.unit
                    }
                  }
                ) *>
                // D1-deps 补触发（deps 设计 §1.2，裁定③内明确要求）：接线后 deps 全满足
                // 且 in barrier 已归零且自身 wiring/pending → 立即启动（上游已 completed
                // 时接线即时触达，含归档上游——findNode 兜底）。与入口启动互斥（入口条件
                // task && ins.isEmpty 独占启动，防双 startNode 并发 spawn）；ins 非空时
                // D1 in-delivery 链的 barrier 结算同样经 startNode 闸门，双路径幂等无害。
                (if !(task.isDefined && ins.isEmpty) then
                   NodeTools.runDetached(rt, s"D1-deps settle -> $nodeId")(
                     rt.store.getNode(nodeId).flatMap {
                       case Some(n) if (n.status == NodeLifecycle.Wiring || n.status == NodeLifecycle.Pending)
                           && n.in.forall(n.deliveredTo.contains) =>
                         rt.engine.depsSatisfied(n).flatMap(ok => if ok then rt.engine.startNode(n.id) else IO.unit)
                       case _ => IO.unit
                     }
                   )
                 else IO.unit) *>
                // 入口节点（task 且无 in）→ 创建即运行。后台化（收口③根因修复）：
                // startNode 同步等节点整个任务跑完，直接调用会把分发器 turn 卡在
                // NodeEdit 上直到节点完成（实测单 turn 56min），违背工具契约
                // 「async, non-blocking」。deps 不构成「可立即运行」——即使误判也被
                // startNode 内 deps 闸门拦下（deps 设计 §1.2）。
                (if task.isDefined && ins.isEmpty then
                   NodeTools.runDetached(rt, s"start entry node $nodeId")(rt.engine.startNode(nodeId))
                 else IO.unit)
            }
          createIO.as(Right(
            s"Node '$nodename' ($nodeId) created in project '${rt.project.name}'" +
              (if task.isDefined && ins.isEmpty then " — entry node started running." else "") +
              (if deps.nonEmpty then s" deps ← ${deps.mkString(",")}" else "") +
              (out.map(t => s" out → $t").getOrElse(""))
          ))
    yield result
    end for

  // ── 编辑 ─────────────────────────────────────────────

  /** abandon 动作（blocked 反馈重入设计 §7.7）：终态节点 → status=cancelled + 显示 TTL
    * + 审计事件。分发器处置 blocked 节点的「放弃」载体；NodeCancel 语义不动（仅 running）。
    * R2 纪律：mutate 内现读 fresh，fresh 已非终态（并发重激活）→ 拒写。
    *
    * 接受域扩展（裁定①待实施语义，deps 设计 §1.6）：终态 ∪ wiring/pending——拓扑
    * 清场时退役节点常是活的 wiring/pending（「悬空活节点」无处置出口），abandon 是
    * 其唯一出口（NodeCancel 仅 running）。wiring/pending 无在飞会话，无中断副作用；
    * 被退役节点的上游其后完成时 deliverOut → startNode 幂等跳过（cancelled ∈ Terminal）。
    * running 仍排除（在飞会话，走 NodeCancel）。 */
  private def abandonNode(rt: ProjectRuntime, node: NodeDef): IO[Either[ToolError, String]] =
    if node.status == NodeLifecycle.Running then
      IO.pure(Left(ToolError(
        s"Node '${node.name}' is running — abandon only accepts terminal or wiring/pending nodes. Use NodeCancel for running nodes.")))
    else
      for
        now <- IO(System.currentTimeMillis())
        s <- rt.store.mutate { st =>
          st.nodes.get(node.id) match
            // R2：fresh 仍非 running（终态/wiring/pending）才写——并发重激活成 running
            // 时拒写（该窗口内节点已有在飞会话，abandon 不得中断）
            case Some(fresh) if fresh.status != NodeLifecycle.Running =>
              st.copy(nodes = st.nodes.updated(node.id, fresh.copy(
                status = NodeLifecycle.Cancelled,
                completedAt = Some(now),
                ttlExpireAt = Some(now + NodeEngine.TtlDisplayMs))))
            case _ => st // 状态已变（并发重激活/移除）→ 拒写
        }
        _ <- s.nodes.get(node.id) match
          case Some(c) if c.status == NodeLifecycle.Cancelled =>
            rt.engine.emitUpdated(c) *>
              FlowMapEventLog.append(rt.project.workspace, rt.project.name, node.id, "abandoned",
                s"node abandoned via NodeEdit (${node.status} → cancelled + display TTL)")
          case _ => IO.unit
      yield Right(s"Node '${node.name}' abandoned — cancelled with display TTL (archived after TTL, result retained)")

  private def editNode(
    rt: ProjectRuntime,
    node: NodeDef,
    agent: Option[String],
    task: Option[String],
    abandon: Boolean,
    inJson: Option[Json],
    depsJson: Option[Json],
    outJson: Option[Json],
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    if abandon then abandonNode(rt, node)
    // 校验三（deps 设计 §1.2）：编辑 running 下游传 deps → 同款冻结拒绝（「输入已冻结」
    // 对齐 in 语义）；给下游加 deps 的上游是 running 则合法（等它完成正是 deps 语义）。
    else if depsJson.isDefined && node.status == NodeLifecycle.Running then
      IO.pure(Left(ToolError(
        s"Node '${node.name}' is running — its input is frozen. NodeCancel it first, then rewire.")))
    else
      // out 处理（单值替换/断开）——同步校验先 match，再进 IO 链
      NodeTools.parseOut(outJson) match
        case Left(err) => IO.pure(Left(ToolError(err)))
        case Right(newOut) =>
          // deps 处理（deps 设计 §1.2，replace-on-provide 语义裁定）：未传不改；
          // 传了（任何形态，含 [] / null）整体替换——清空是合法改接动作（清空后仍须
          // 剩至少一种连接，校验六）。deps 上游侧无句柄（下游单侧持有），replace 是
          // 下游自持列表唯一完整 CRUD 原语，对分发器幂等。
          NodeTools.parseIn(depsJson).left.map(err => err.replace("'in'", "'deps'")) match
            case Left(err) => IO.pure(Left(ToolError(err)))
            case Right(newDeps) =>
              val depsProvided = depsJson.isDefined
              // D2 修复（验收②c，@a4b5d184 spec 实证）：旧目标已消费 → 拒绝改接。
              // 已完成节点改投新目标时，若旧目标已启动/已完成（结果已投递、输入已消费），
              // 改投会造成语义漂移 + 重复投递风险。保留「旧目标未启动（Wiring/Pending）→
              // 原子改投」分支（§2.3 场景表第 3 行，②b PASS 依赖）；断开（out=null）
              // 不投新目标，无重复投递 → 不拦截。
              val consumedGuard: IO[Either[String, Unit]] =
                (node.status, node.out, newOut) match
                  case (NodeLifecycle.Completed, Some(oldT), Some(_)) if oldT != "Nebula" && newOut != node.out =>
                    rt.store.findNode(oldT).map {
                      case Some(x) if x.status != NodeLifecycle.Wiring && x.status != NodeLifecycle.Pending =>
                        Left(
                          s"Node '${node.name}' result already delivered to '${x.name}' (status=${x.status}) — input consumed. " +
                            s"NodeCancel it first, then rewire, then recreate the old target node."
                        )
                      case _ => Right(())
                    }
                  case _ => IO.pure(Right(()))
              // 守卫：新目标已运行 → 拒绝（§2.2 状态守卫）+ 旧目标已消费 → 拒绝（§2.3）
              val guard = newOut match
                case Some(t) if t != "Nebula" =>
                  NodeTools.ensureTargetNotRunning(rt, t).flatMap {
                    case Left(err) => IO.pure(Left(err))
                    case Right(_)  => consumedGuard
                  }
                case _ => consumedGuard
              guard.flatMap {
                case Left(err) => IO.pure(Left(ToolError(err)))
                case Right(_) =>
                  // 环检测（新 out）
                  val cycle = newOut match
                    case Some(t) if t != "Nebula" => NodeTools.wouldCreateCycle(rt, node.id, t)
                    case _ => IO.pure(false)
                  cycle.flatMap { isCycle =>
                    if isCycle then IO.pure(Left(ToolError(s"Cycle detected: out → ${newOut.get} would create a loop — DAG must stay acyclic")))
                    else
                      // out 变更（原子：旧目标 in 移除 + 新目标 in 追加）。outJson 未出现 =
                      // 不改动（parseOut 注释「not passed = no change」的落地）——历史实现用
                      // newOut != node.out 判断，out 未传（None）遇上 out=Some 的节点会被
                      // 误判为「断开」副作用（deps-only 编辑断开 out 即此暴露，T7 锁定）。
                      val setOutIO =
                        if outJson.isDefined && newOut != node.out then NodeTools.setOut(rt, node.id, newOut)
                        else IO.unit
                      val inAdds = NodeTools.parseIn(inJson)
                      inAdds match
                        case Left(err) => IO.pure(Left(ToolError(err)))
                        case Right(adds) =>
                          // deps 校验一 + 二（deps 设计 §1.2）：目标存在（findNode 归档兜底）
                          // + 环检测（wouldCreateCycle 单点已含 deps 反向边 = in+deps 混合图
                          // 覆盖）。replace 语义：对替换后全集逐条检查（旧 deps 边将消失，
                          // 不在检查路径——环路径经 successors=out ∪ deps 反向，旧 deps 是
                          // 本节点入边不参与）。
                          val depsChecks: IO[List[Either[String, Unit]]] =
                            if depsProvided then
                              newDeps.traverse { upId =>
                                NodeTools.ensureNodeExists(rt, upId).flatMap {
                                  case Left(e) => IO.pure(Left(e): Either[String, Unit])
                                  case Right(_) =>
                                    NodeTools.wouldCreateCycle(rt, upId, node.id).map {
                                      case true => Left(s"Cycle detected: adding deps from '$upId' would create a loop — DAG must stay acyclic")
                                      case false => Right(())
                                    }
                                  }
                              }
                            else IO.pure(Nil)
                          depsChecks.flatMap { dChecks =>
                          // 校验六（裁定①零连接下限，改接侧）：断开/清空单边合法，但编辑
                          // 后交互终态连接集（in ∪ deps ∪ out）为空 → 拒绝（同码，改接侧
                          // 文案）。in 只增（finalIn = 既有 + 追加）；result 不因断开清除
                          //（既有语义，裁定①边界注记）。与 deps 校验一/二一起构成前置拒绝集。
                          // 注意 out 的「未传」与「显式 null 断开」经 parseOut 后同为 None——
                          // 必须按 outJson 是否出现区分：传了才视为断开后的 None，未传保持原 out。
                          val finalIn = node.in ++ adds
                          val finalDeps = if depsProvided then newDeps else node.deps
                          val finalOut = if outJson.isDefined then newOut else node.out
                          val earlyReject: Option[ToolError] =
                            if finalIn.isEmpty && finalDeps.isEmpty && finalOut.isEmpty then
                              Some(ToolError(
                                s"Node '${node.name}' must keep at least one connection — this rewiring would leave it disconnected. (EMPTY_NODE_CONNECTION)"))
                            else if dChecks.exists(_.isLeft) then
                              Some(ToolError(dChecks.collectFirst { case Left(e) => e }.getOrElse("invalid deps")))
                            else None
                          // blocked 重激活（blocked 反馈重入设计 §6 #5）：编辑 blocked 节点且
                          // task/agent/in/out/deps 实际变更 → status 回 wiring/pending、deliveredTo
                          // 清空、completedAt/ttlExpireAt/startedAt 复位、blockCount 保留
                          // （不清零，§3.1），随后 D1 补投递链重投全部已完成上游。
                          val taskChanged = task.exists(t => NodeTools.normalizeTask(t) != node.task.map(NodeTools.normalizeTask).getOrElse(""))
                          val agentChanged = agent.exists(_ != node.agent)
                          val depsChanged = depsProvided && newDeps != node.deps
                          val actualChange = taskChanged || agentChanged || newOut != node.out || adds.nonEmpty || depsChanged
                          val reactivate = node.status == NodeLifecycle.Blocked && actualChange
                          val appliedTask = task.orElse(node.task)
                          val appliedAgent = agent.getOrElse(node.agent)
                          val appliedDeps = if depsProvided then newDeps else node.deps
                          // agent 变更 → 存在性校验（0 spawn 拦截）
                          val agentCheck: IO[Option[ToolError]] =
                            if reactivate && agentChanged then
                              EntityLoader.loadAgent(appliedAgent).map {
                                case None    => Some(ToolError(s"Agent '$appliedAgent' not found in global library"))
                                case Some(_) => None
                              }
                            else IO.pure(None)
                          agentCheck.flatMap {
                            case Some(err) => IO.pure(Left(err))
                            case None if earlyReject.isDefined => IO.pure(Left(earlyReject.get))
                            case None =>
                              // wiring 变更涉及节点集（最终态事件）：本节点（in/out 变更，in 追加
                              // 也改自身 in）、in 上游（out 改指本节点）、旧 out 目标（in 移除）、
                              // 新 out 目标（in 追加）。deps 目标不入列——下游单侧持有，上游
                              // payload 无「被谁依赖」字段，其卡片无需事件。
                              val affected: List[String] =
                                val fromOut =
                                  if outJson.isDefined && newOut != node.out then
                                    node.out.filter(_ != "Nebula").toList ++ newOut.filter(_ != "Nebula").toList
                                  else Nil
                                (node.id +: (adds ++ fromOut)).distinct
                              for
                                // in 追加校验先行（存在性 + 环检），全部通过才动 store——旧实现
                                // traverse 内 raiseError 后被 handleErrorWith 吞成 Left 值丢弃，
                                // 后续 setOutIO/mutate 照跑且工具误报成功（一并修正）
                                inChecks <- adds.traverse { upId =>
                                  NodeTools.ensureNodeExists(rt, upId).flatMap {
                                    case Left(e) => IO.pure(Left(e): Either[String, Unit])
                                    case Right(_) =>
                                      NodeTools.wouldCreateCycle(rt, upId, node.id).map {
                                        case true => Left(s"Cycle detected: adding in from '$upId' would create a loop (A→B→A) — DAG must stay acyclic")
                                        case false => Right(())
                                      }
                                    }
                                }
                                inResult <-
                                  if inChecks.exists(_.isLeft) then
                                    IO.pure(Left(ToolError(inChecks.collectFirst { case Left(e) => e }.getOrElse("invalid in"))))
                                  else
                                    for
                                      // in 追加（barrier）：每个上游 out 改指向本节点（原子改投 §2.3）
                                      _ <- adds.traverse_(upId => NodeTools.setOut(rt, upId, Some(node.id)))
                                      _ <- setOutIO
                                      // deps 替换写回（非重激活路径；重激活在下方事务内一并写）。
                                      // deps 单侧持有：只更新本节点字段，无上游侧镜像边。
                                      _ <-
                                        if depsProvided && depsChanged && !reactivate then
                                          rt.store.mutate { s =>
                                            s.nodes.get(node.id) match
                                              case Some(fresh) =>
                                                s.copy(nodes = s.nodes.updated(node.id, fresh.copy(deps = newDeps)))
                                              case None => s
                                          }.void
                                        else IO.unit
                                      // blocked 重激活写回（R2 纪律）：事务内现读 fresh，fresh 仍
                                      // Blocked 才写；状态已变（并发 abandon/重激活）→ 拒写不重激活。
                                      didReactivate <-
                                        if reactivate then
                                          rt.store.mutate { s =>
                                            s.nodes.get(node.id) match
                                              case Some(fresh) if fresh.status == NodeLifecycle.Blocked =>
                                                val nextStatus =
                                                  if appliedTask.exists(_.trim.nonEmpty) && fresh.in.isEmpty then NodeLifecycle.Pending
                                                  else NodeLifecycle.Wiring
                                                s.copy(nodes = s.nodes.updated(node.id, fresh.copy(
                                                  status = nextStatus,
                                                  task = appliedTask,
                                                  agent = appliedAgent,
                                                  deps = appliedDeps,
                                                  result = None, // blocked 反馈渲染串不复存在（反馈保留在 blockedFeedback）
                                                  deliveredTo = Nil,
                                                  startedAt = None,
                                                  completedAt = None,
                                                  ttlExpireAt = None))) // blockCount / blockedFeedback 保留
                                              case _ => s
                                          }.map(s2 =>
                                            s2.nodes.get(node.id).exists(n => n.status == NodeLifecycle.Wiring || n.status == NodeLifecycle.Pending))
                                        else IO.pure(false)
                                      _ <- if didReactivate then
                                        FlowMapEventLog.append(rt.project.workspace, rt.project.name, node.id, "reactivated",
                                          s"blocked node edited (round ${node.blockCount} preserved) → ${appliedTask.map(t => s"task=${t.take(80)}").getOrElse("")}") *>
                                          // 重激活补投递（design §6 #5「随后走现有 D1 补投递链」）：
                                          // deliveredTo 已清空 → 全部 in 上游（终态有结果、非 blocked）
                                          // 重投（deliverOutTo dedup 幂等）→ barrier 结算 → 启动；
                                          // 入口节点（task 且无 in）→ 直接启动。后台化（runDetached：
                                          // 直接调用会把工具 fiber 卡到节点终态）。
                                          NodeTools.runDetached(rt, s"reactivated redelivery + barrier settle -> ${node.id}")(
                                            rt.store.getNode(node.id).flatMap {
                                              case None => IO.unit
                                              case Some(n) =>
                                                n.in.traverse_ { upId =>
                                                  rt.store.findNode(upId).flatMap {
                                                    case Some(up) if up.result.isDefined && up.status != NodeLifecycle.Blocked && NodeLifecycle.Terminal.contains(up.status) =>
                                                      rt.engine.deliverOutTo(up, n.id, up.result.get)
                                                    case _ => IO.unit
                                                  }
                                                } *> rt.store.getNode(node.id).flatMap {
                                                  case Some(n2) if n2.in.nonEmpty && n2.in.forall(n2.deliveredTo.contains) =>
                                                    rt.engine.startNode(n2.id)
                                                  case Some(n2) if n2.in.isEmpty && n2.status == NodeLifecycle.Pending =>
                                                    rt.engine.startNode(n2.id)
                                                  case _ => IO.unit
                                                }
                                            }
                                          )
                                      else IO.unit
                                      // 已完成节点改接 → 立即投递（§2.3：结果缓冲/归档 → 向新目标投递）。
                                      // 后台化（收口③）：deliverOutTo 内 startNode 同步等下游终态，
                                      // 直接调用会阻塞 NodeEdit 工具 fiber（见 runDetached 注释）。
                                      _ <- (newOut, node.status) match
                                        case (Some(t), NodeLifecycle.Completed) =>
                                          node.result match
                                            case Some(res) =>
                                              NodeTools.runDetached(rt, s"deliver retained result ${node.id} -> $t")(
                                                rt.engine.deliverOutTo(node, t, res)
                                              )
                                            case None => IO.unit
                                        case _ => IO.unit
                                      // 修复次因 A（edit 路径补 D1 等价投递 + barrier 结算复查，
                                      // create 路径见 proceed 内 D1 注释）：新追加上游中已终态且
                                      // 有结果的 → 立即投递（findNode 活动/归档兜底；含悬空完成的
                                      // 上游——其 completeNode 时 out 悬空未投，此处是唯一投递入口）；
                                      // 随后复查 barrier——deliveredTo 可能已含全部 in 上游（分批
                                      // 送达/重复接线）→ startNode。投递与结算串在同一 detached
                                      // fiber（deliverOutTo 自带结算 → startNode；复查随后命中
                                      // running/terminal 由 startNode 幂等跳过）；后台化遵循
                                      // runDetached 的阻塞教训（直接调用会把工具 fiber 卡到节点终态）。
                                      _ <- if adds.nonEmpty then NodeTools.runDetached(rt, s"deliver appended upstream results + settle barrier -> ${node.id}")(
                                        for
                                          _ <- adds.traverse_ { upId =>
                                            rt.store.findNode(upId).flatMap {
                                              // R1：同 create 路径——blocked 反馈串不是可投结果，显式排除
                                              case Some(up) if up.result.isDefined && up.status != NodeLifecycle.Blocked && NodeLifecycle.Terminal.contains(up.status) =>
                                                rt.engine.deliverOutTo(up, node.id, up.result.get)
                                              case _ => IO.unit
                                            }
                                          }
                                          _ <- rt.store.getNode(node.id).flatMap {
                                            case Some(n) if n.in.nonEmpty && n.in.forall(n.deliveredTo.contains) =>
                                              rt.engine.startNode(n.id)
                                            case _ => IO.unit
                                          }
                                        yield ()
                                      )
                                      else IO.unit
                                      // D1-deps 补触发（edit 路径，deps 设计 §1.2）：接线后若 deps 全
                                      // 满足 && in barrier 已归零 && 自身 wiring/pending → 立即启动。
                                      // 上游已 completed 时接 deps 边即时触达（含归档上游 findNode 兜底
                                      // ——「归档 completed 上游是唯一投递入口」先例同款）。startNode 内
                                      // 闸门二次把关，重复调用幂等。
                                      _ <- if depsProvided then NodeTools.runDetached(rt, s"D1-deps settle -> ${node.id}")(
                                        rt.store.getNode(node.id).flatMap {
                                          case Some(n) if (n.status == NodeLifecycle.Wiring || n.status == NodeLifecycle.Pending)
                                              && n.in.forall(n.deliveredTo.contains) =>
                                            rt.engine.depsSatisfied(n).flatMap(ok => if ok then rt.engine.startNode(n.id) else IO.unit)
                                          case _ => IO.unit
                                        }
                                      )
                                      else IO.unit
                                      // wiring 变更事件（NodeList 同构 payload，store 最终态）——此前只发
                                      // 本节点且 payload 含陈旧 in、改写的上游/新旧目标无事件（缺失补齐）
                                      _ <- NodeTools.emitWiringUpdates(rt, affected)
                                    yield Right(
                                      s"Node '${node.name}' updated" +
                                        (if didReactivate then s" — reactivated from blocked (round ${node.blockCount} preserved)" else "") +
                                        (newOut.map(t => s" — out → $t").getOrElse("") + (if adds.nonEmpty then s" — in += ${adds.mkString(",")}" else "")) +
                                        (if depsProvided && depsChanged then s" — deps → [${newDeps.mkString(",")}]" else "")
                                    )
                              yield inResult
                          }
                          }
                  }
              }

object NodeListTool extends Tool:
  val name = "NodeList"

  val description =
    """List a project's Flow Map snapshot — the task dispatcher's opening move (read the graph before deciding).
## When to Use
- The dispatcher reads the current topology at session start (and before ending) — nodes with status/result summary/hasWorktree drive merge-node decisions.
- Frontend panel data source (REST mirrors this shape).

## Parameters
- **project** (required): project name.

## Returns
{nodes: [{id, name, agent, skill, mcp, preset, status, in, out, hasWorktree, worktree, result (≤500-char summary), retries, createdAt, completedAt, ttlLeftSec}], worktrees: [...], meta: {project, updatedAt}}"""
  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "project" -> Json.obj("type" -> "string".asJson, "description" -> "Project name (required)".asJson)
      ),
      "required" -> Json.arr("project".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val p = input("project").flatMap(_.asString).getOrElse("?")
    s"NodeList($p)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 300 then result.take(297) + "..." else result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    NodeTools.resolveProject(input("project").flatMap(_.asString).orElse(ctx.projectName), ctx).flatMap {
      case Left(err) => IO.pure(Left(ToolError(err)))
      case Right(rt) =>
        NodeTools.buildNodeListPayload(rt).map(payload => Right(payload.noSpaces))
    }

object NodeCancelTool extends Tool:
  val name = "NodeCancel"

  val description =
    """Cancel a running node (supervisor cancel semantics) — the dispatcher's stop-loss tool.
## When to Use
- A node is mis-wired, hung, or superseded: cancel it, then rewire or recreate. Result is NOT delivered; upstream results already delivered stay buffered/archived.
- Cancel target must be running (non-running → no-op with notice)."""
  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "project" -> Json.obj("type" -> "string".asJson, "description" -> "Project name (required)".asJson),
        "node-id" -> Json.obj("type" -> "string".asJson, "description" -> "Node id to cancel (from NodeList)".asJson)
      ),
      "required" -> Json.arr("project".asJson, "node-id".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val id = input("node-id").flatMap(_.asString).getOrElse("?")
    s"NodeCancel($id)"

  def summarizeResult(input: JsonObject, result: String): String = result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val nodeId = input("node-id").flatMap(_.asString).getOrElse("")
    NodeTools.resolveProject(input("project").flatMap(_.asString).orElse(ctx.projectName), ctx).flatMap {
      case Left(err) => IO.pure(Left(ToolError(err)))
      case Right(rt) =>
        if nodeId.isEmpty then IO.pure(Left(ToolError("Missing 'node-id'")))
        else
          rt.store.getNode(nodeId).flatMap {
            case None => IO.pure(Left(ToolError(s"Node '$nodeId' not found")))
            case Some(n) if n.status != NodeLifecycle.Running =>
              IO.pure(Right(s"Node '${n.name}' is not running (status=${n.status}) — no-op"))
            case Some(n) =>
              rt.engine.cancelNodeById(nodeId).as(Right(s"Node '${n.name}' cancel signal sent"))
          }
    }

object ProjectCreateTool extends Tool:
  val name = "ProjectCreate"

  val description =
    """Create a Project (Nebula use) — project definition + workspace .nebflow/ scaffolding.
## When to Use
- Setting up a new project under the Project + Node model: name + workspace + optional description.
- Creates projects/<name>/project.json, workspace root AGENTS.md (agent instructions template), workspace/.nebflow/ (flow-map.json + .gitignore) and mounts the project (FlowMapStore + ProjectActor ready)."""
  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "name" -> Json.obj("type" -> "string".asJson, "description" -> "Project name (single path segment)".asJson),
        "workspace" -> Json.obj("type" -> "string".asJson, "description" -> "Absolute path to the project workspace".asJson),
        "description" -> Json.obj("type" -> "string".asJson, "description" -> "Optional one-line description".asJson)
      ),
      "required" -> Json.arr("name".asJson, "workspace".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val n = input("name").flatMap(_.asString).getOrElse("?")
    s"ProjectCreate($n)"

  def summarizeResult(input: JsonObject, result: String): String = result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val name = input("name").flatMap(_.asString).getOrElse("")
    val workspace = input("workspace").flatMap(_.asString).getOrElse("")
    val description = input("description").flatMap(_.asString)
    if name.isEmpty || workspace.isEmpty then IO.pure(Left(ToolError("'name' and 'workspace' are required")))
    else
      val agentMdTemplate =
        s"""# ${name} — AGENTS.md

项目级 agent 指令（取代 team rules.md，工作区根 AGENTS.md）。分发器任务文本可引用本文件。

- 工作区：$workspace
- Flow Map：`$workspace/.nebflow/flow-map.json`
- 节点规则：节点是 leaf（无记忆、无 Mail 身份、ephemeral）；结果沿 out 边投递。
"""
      /** 挂载（新创建 + 已存在幂等共用）。ProjectRuntimeRegistry.mount 本身幂等：
        * 已挂载 → 直接返回现有 runtime（不重建不覆盖——rootSessionId 已在首次挂载
        * 用上链根接线；运行中重挂覆盖需重建 engine，试点期无此场景）。 */
      def mountProject(pd: ProjectDef, created: Boolean): IO[Either[ToolError, String]] =
        (ctx.actorSystem, ctx.sharedResources) match
          case (Some(system), Some(res)) =>
            // P0 接线修复（Explorer c759e8c）：mount 传**上链 rootSessionId**（真正顶层），
            // 非挂载者自身会话——否则 out="Nebula" 投递目标是挂载者（如 qa-backend），
            // 节点完成消息注入执行者形成自维持循环。fallback ctx.sessionId（老调用方）。
            ProjectRuntimeRegistry
              .mount(pd, system, res, ctx.wsSend, ctx.rootSessionId.orElse(ctx.sessionId).getOrElse("default"))
              .as {
                val verb = if created then "created" else "already exists"
                Right(s"Project '${pd.name}' $verb and mounted. Flow Map ready at ${pd.agentFile}.")
              }
          case _ =>
            IO.pure(Right(s"Project '${pd.name}' definition ready. Mount requires an agent session."))

      ProjectStore.create(name, workspace, description, agentMdTemplate).flatMap {
        case Right(pd) => mountProject(pd, created = true)
        case Left(err) =>
          // 幂等挂载（试点重启恢复关键路径）：定义已存在 → 不重建定义、不动脚手架，
          // 直接挂载（ProjectStore.create 防覆盖返回 Left；load 命中即已存在）。
          ProjectStore.load(name).flatMap {
            case Some(pd) => mountProject(pd, created = false)
            case None => IO.pure(Left(ToolError(err)))
          }
      }
