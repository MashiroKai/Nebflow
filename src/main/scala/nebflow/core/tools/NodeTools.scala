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

  /** 1 对多拒绝：out 必须是单值 string 或 null（数组 → 拒绝，§2.4）。 */
  def parseOut(outJson: Option[Json]): Either[String, Option[String]] =
    outJson match
      case None => Right(None) // 未传 out = 不改动
      case Some(j) if j.isNull => Right(None) // out=null = 断开（悬空化）
      case Some(j) if j.isArray => Left("'out' must be a single node id or null — 1-to-many is not supported. Create N independent entry nodes instead.")
      case Some(j) =>
        j.asString match
          case Some(s) if s.nonEmpty => Right(Some(s))
          case _ => Left("'out' must be a non-empty string node id, \"Nebula\", or null")

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
    * 返回匹配的节点（无则 None）。NodeEdit 创建入口节点时校验（0 spawn）。 */
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
- **out** (optional, single value): node id, "Nebula", or null. Single-value semantics: setting replaces the old out (rewire); arrays are rejected (1-to-many not supported); null disconnects (result retained, re-wire later auto-delivers).
- **skill** / **mcp** / **worktree** / **preset** (optional): node configuration (worktree must exist under workspace/.nebflow/).
- **maxRetries** (optional, default 1).

## Semantics
- nodename missing → create (agent required; task and/or in required, otherwise the node sits in wiring state).
- nodename exists → edit: in appends barrier inputs; out sets/replaces/disconnects.
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
        "out" -> Json.obj("oneOf" -> Json.arr(
          Json.obj("type" -> "string".asJson),
          Json.obj("type" -> "null".asJson)
        ).asJson, "description" -> "Single out target: node id, \"Nebula\", or null (disconnect). Arrays rejected (1-to-many)".asJson),
        "skill" -> Json.obj("type" -> "string".asJson),
        "mcp" -> Json.obj("type" -> "string".asJson),
        "worktree" -> Json.obj("type" -> "string".asJson, "description" -> "Relative path under workspace/.nebflow/ (must exist; create via git worktree)".asJson),
        "preset" -> Json.obj("type" -> "string".asJson),
        "maxRetries" -> Json.obj("type" -> "integer".asJson)
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
    val inJson = input("in")
    val outJson = input("out")

    if nodename.isEmpty then IO.pure(Left(ToolError("Missing 'nodename'")))
    else
      NodeTools.resolveProject(project, ctx).flatMap {
        case Left(err) => IO.pure(Left(ToolError(err)))
        case Right(rt) =>
          rt.store.snapshot.flatMap { s =>
            s.nodes.values.find(_.name == nodename) match
              case Some(existing) => editNode(rt, existing, inJson, outJson, ctx)
              case None => createNode(rt, nodename, agent, task, skill, mcp, worktree, preset, maxRetries, inJson, outJson)
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
    outJson: Option[Json]
  ): IO[Either[ToolError, String]] =
    val agentName = agent.getOrElse("")
    val inIds = parseIn(inJson)
    val outEither = NodeTools.parseOut(outJson)
    (agentName, inIds, outEither) match
      case ("", _, _) => IO.pure(Left(ToolError("New node requires 'agent'")))
      case (_, Left(err), _) => IO.pure(Left(ToolError(err)))
      case (_, _, Left(err)) => IO.pure(Left(ToolError(err)))
      case (_, Right(ins), Right(out)) =>
        if task.isEmpty && ins.isEmpty && out.isEmpty then
          IO.pure(Left(ToolError("New node needs 'task' and/or 'in' (otherwise it would sit in wiring state forever)")))
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
                  else proceed(rt, nodename, agentName, task, skill, mcp, worktree, preset, maxRetries, ins, out)
                case None => proceed(rt, nodename, agentName, task, skill, mcp, worktree, preset, maxRetries, ins, out)
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
    out: Option[String]
  ): IO[Either[ToolError, String]] =
    val nodeId = s"n-${java.util.UUID.randomUUID().toString.take(8)}"
    for
      // 引用存在性 + 环检测（in 上游 → 本节点；本节点 → out 目标）
      inOk <- ins.traverse(id => NodeTools.ensureNodeExists(rt, id))
      cycleIn <- ins.traverse(id => NodeTools.wouldCreateCycle(rt, fromId = id, to = nodeId))
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
        else if cycleIn.exists(identity) then
          IO.pure(Left(ToolError(s"Cycle detected: adding in would create a loop (A→B→A) — DAG must stay acyclic")))
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
            out = None,
            status = if task.isDefined && ins.isEmpty then NodeLifecycle.Pending else NodeLifecycle.Wiring,
            retries = 0,
            maxRetries = maxRetries.getOrElse(1),
            createdAt = now
          )
          // 单事务：加节点 + in 边（上游 out → 本节点）+ out 边
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
                ins.traverse_ { upId =>
                  rt.store.findNode(upId).flatMap {
                    case Some(up) if up.result.isDefined && NodeLifecycle.Terminal.contains(up.status) =>
                      rt.engine.deliverOutTo(up, nodeId, up.result.get)
                    case _ => IO.unit
                  }
                } *>
                // 入口节点（task 且无 in）→ 创建即运行
                (if task.isDefined && ins.isEmpty then rt.engine.startNode(nodeId) else IO.unit)
            }
          createIO.as(Right(
            s"Node '$nodename' ($nodeId) created in project '${rt.project.name}'" +
              (if task.isDefined && ins.isEmpty then " — entry node started running." else "") +
              (out.map(t => s" out → $t").getOrElse(""))
          ))
    yield result
    end for

  // ── 编辑 ─────────────────────────────────────────────

  private def editNode(
    rt: ProjectRuntime,
    node: NodeDef,
    inJson: Option[Json],
    outJson: Option[Json],
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    // out 处理（单值替换/断开）——同步校验先 match，再进 IO 链
    NodeTools.parseOut(outJson) match
      case Left(err) => IO.pure(Left(ToolError(err)))
      case Right(newOut) =>
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
                // out 变更（原子：旧目标 in 移除 + 新目标 in 追加）
                val setOutIO =
                  if newOut != node.out then NodeTools.setOut(rt, node.id, newOut)
                  else IO.unit
                val inAdds = parseIn(inJson)
                inAdds match
                  case Left(err) => IO.pure(Left(ToolError(err)))
                  case Right(adds) =>
                    // wiring 变更涉及节点集（最终态事件）：本节点（in/out 变更，in 追加
                    // 也改自身 in）、in 上游（out 改指本节点）、旧 out 目标（in 移除）、
                    // 新 out 目标（in 追加）
                    val affected: List[String] =
                      val fromOut =
                        if newOut != node.out then
                          node.out.filter(_ != "Nebula").toList ++ newOut.filter(_ != "Nebula").toList
                        else Nil
                      (node.id +: (adds ++ fromOut)).distinct
                    for
                      // in 追加（barrier）：每个上游 out 改指向本节点（原子改投 §2.3）
                      _ <- adds.traverse_ { upId =>
                        NodeTools.ensureNodeExists(rt, upId).flatMap {
                          case Left(e) => IO.raiseError(new RuntimeException(e))
                          case Right(_) =>
                            NodeTools.wouldCreateCycle(rt, upId, node.id).flatMap { cyc =>
                              if cyc then IO.raiseError(new RuntimeException(s"Cycle detected via in from '$upId'"))
                              else NodeTools.setOut(rt, upId, Some(node.id))
                            }
                        }
                      }.handleErrorWith { e =>
                        IO.pure(Left(ToolError(Option(e.getMessage).getOrElse("in-rewire failed"))))
                      }
                      _ <- setOutIO
                      // 已完成节点改接 → 立即投递（§2.3：结果缓冲/归档 → 向新目标投递）
                      _ <- (newOut, node.status) match
                        case (Some(t), NodeLifecycle.Completed) =>
                          node.result match
                            case Some(res) => rt.engine.deliverOutTo(node, t, res)
                            case None => IO.unit
                        case _ => IO.unit
                      // wiring 变更事件（NodeList 同构 payload，store 最终态）——此前只发
                      // 本节点且 payload 含陈旧 in、改写的上游/新旧目标无事件（缺失补齐）
                      _ <- NodeTools.emitWiringUpdates(rt, affected)
                    yield Right(
                      s"Node '${node.name}' updated" +
                        (newOut.map(t => s" — out → $t").getOrElse("") + (if adds.nonEmpty then s" — in += ${adds.mkString(",")}" else ""))
                    )
            }
        }

  private def parseIn(inJson: Option[Json]): Either[String, List[String]] =
    inJson match
      case None => Right(Nil)
      case Some(j) if j.isNull => Right(Nil)
      case Some(j) if j.isString =>
        val s = j.asString.getOrElse("")
        if s.isEmpty then Right(Nil) else Right(List(s))
      case Some(j) if j.isArray =>
        val ids = j.asArray.getOrElse(Vector.empty).flatMap(_.asString)
        Right(ids.toList.filter(_.nonEmpty))
      case Some(_) => Left("'in' must be a node id string or an array of node ids")

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
