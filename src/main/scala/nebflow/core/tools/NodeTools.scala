package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.NebflowLogger
import nebflow.core.PathUtil
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

  /** 事务内设 out 边（单权威）：旧目标 in 移除 + 新目标 in 追加 + 本节点 out 更新。
    * 归档感知（fix a「新建边→归档上游」修复 20260903）：from 在归档区（活动区无）
    * 时——活动区侧照常做旧目标 in 移除 / 新目标 in 追加，from.out 补写归档区
    *（mutateArchive；归档节点不可复活，仅元数据保持单权威一致）；旧目标也在归档区
    * 时其 in 不回写（归档 in 表是死数据，避免跨区二跳写）。修复前 `s.nodes(fromId)`
    * 直接 apply → 归档 from 必 NoSuchElementException——补建 in 边指向已归档上游的
    * 路径整体崩溃（混合 adds 时还会部分应用后中断，留下半成品接线）。 */
  def setOut(rt: ProjectRuntime, fromId: String, newOut: Option[String]): IO[Unit] =
    rt.store.getNode(fromId).flatMap {
      case Some(_) =>
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
      case None =>
        rt.store.findNode(fromId).flatMap {
          case None => IO.unit // 两区皆无（并发 TTL 迁移已删）→ no-op
          case Some(archFrom) =>
            rt.store.mutate { s =>
              val afterOld = archFrom.out match
                case Some(t) if t != "Nebula" =>
                  s.nodes.get(t).map(tn => s.nodes.updated(t, tn.copy(in = tn.in.filterNot(_ == fromId)))).getOrElse(s.nodes)
                case _ => s.nodes
              val afterNew = newOut match
                case Some(t) if t != "Nebula" =>
                  afterOld.get(t).map(tn => afterOld.updated(t, tn.copy(in = (tn.in :+ fromId).distinct))).getOrElse(afterOld)
                case _ => afterOld
              s.copy(nodes = afterNew)
            } *> rt.store.mutateArchive(a => a.copy(nodes = a.nodes.updatedWith(fromId)(_.map(_.copy(out = newOut))))).void
        }
    }

  // ── 环检测（对外暴露给测试）────────────────────────────

  def wouldCreateCycle(rt: ProjectRuntime, fromId: String, to: String): IO[Boolean] =
    rt.store.wouldCreateCycle(fromId, to)

  /** task 文本归一化（loop detect 用）：trim + 空白折叠。 */
  def normalizeTask(t: String): String = t.trim.replaceAll("\\s+", " ")

  /** loop detect（§2.6）：同 agent + 同 task 归一化，活动区已有
    * running/completed 节点 → 疑似重复派发（TTL 窗口内 = 活动区仍显示）。
    * 返回匹配的节点（无则 None）。NodeEdit 创建入口节点时校验（0 spawn）。
    * R1 复核（blocked 反馈重入设计 §6）：Terminal 含 blocked——blocked 节点
    * **应**计入重复派发（防对同一任务重复派发；blocked ≠ 可重派）。
    * hold 批（20260903 暂停/人在回路设计 §2.5 #6）：追加 Held——held 节点
    * 任务未完（结果已产出但等放行），同 agent+task 重派仍应报「疑似重复派发」。 */
  def findDuplicateDispatch(rt: ProjectRuntime, agentName: String, task: String): IO[Option[NodeDef]] =
    val norm = normalizeTask(task)
    if norm.isEmpty then IO.pure(None)
    else
      rt.store.snapshot.map { s =>
        s.nodes.values.find { n =>
          n.agent == agentName &&
          n.task.exists(t => normalizeTask(t) == norm) &&
          (n.status == NodeLifecycle.Running || n.status == NodeLifecycle.Held || NodeLifecycle.Terminal.contains(n.status))
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
      // 清场 c-①（20260903 03:04 清场误杀事故复盘）：running 节点携带 liveness
      // 字段——true = 有在飞执行 fiber（活会话，取消信号可达）；false = 无在飞
      // fiber（死会话残留/实例重启泄漏，可经 NodeCancel / abandon 收殓）。非
      // running 节点不带该键（wiring/pending 无会话存活概念、终态无存活可言
      // ——语义明确）。仅快照（NodeList 工具 / REST flow-map）带；WS 事件单一
      // 序列化点（NodePayload.buildNodeJson）不动 → 事件键集断言零影响。
      liveness <- s.nodes.values.toList
        .filter(_.status == NodeLifecycle.Running)
        .traverse(n => rt.engine.isRunning(n.id).map(alive => n.id -> alive))
        .map(_.toMap)
    yield
      val now = System.currentTimeMillis()
      val nodes = s.nodes.values.toList.sortBy(_.createdAt).map { n =>
        val base = NodePayload.buildNodeJson(n, now)
        liveness.get(n.id) match
          case Some(alive) => base.deepMerge(Json.obj("liveness" -> Json.fromBoolean(alive)))
          case None        => base
      }
      val wtDir = os.Path(rt.project.workspace) / ".nebflow"
      val authDir = wtDir / "worktrees"
      // worktrees[] 双位置合并去重（20260903 worktree 参数修复——参照系统一）：
      // worktrees/ 权威位置目录名 ∪ .nebflow/ 顶层目录/软链名（os.isDir 沿软链），
      // distinct + sorted 排序稳定。worktrees/ 容器本身不算 worktree。今日生产
      // 顶层同名条目为指向权威位置的软链 → 去重后名单与可传 worktree 名单一致。
      // 节点 payload 的 worktree 字段零改动（仍原样序列化存储值）。
      val authNames = if os.exists(authDir) then os.list(authDir).filter(os.isDir).map(_.last).toList else Nil
      val topNames =
        // 保留名（worktrees/skills/commands）不是 worktree 候选（QC P1：否则
        // NodeList 主动教 LLM 传 "skills" 且校验层会放行）
        if os.exists(wtDir) then
          os.list(wtDir).filter(os.isDir).map(_.last).filterNot(PathUtil.ReservedTopLevelNames.contains).toList
        else Nil
      val worktrees = (authNames ++ topNames).distinct.sorted
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
    """Create or edit a Node in a project's Flow Map — the task dispatcher's single tool for topology (create / wire / rewire).
## When to Use
- The task dispatcher builds the project's node graph: create entry nodes (task + out), wire barriers (in), rewire running/completed nodes. Disconnecting (out=null) is no longer supported — every node keeps its out edge; rewire to a new target instead.
- All topology changes go through this tool — 0 files written (0 hand-written files; the store owns flow-map.json).

## Parameters
- **project** (required): project name (the dispatcher's project).
- **nodename**: display name (unique within the Flow Map; existing name = edit that node).
- **agent** (required on create): global agent name for the node.
- **task** (optional): the node's task context — an entry node (task present, no in) starts running immediately on create.
- **in** (optional): upstream node id(s) to add as barrier inputs (multi-in = barrier; each upstream's out is rewired to this node).
- **deps** (optional, replace-on-provide): upstream node id(s) this node waits on for COMPLETION SIGNAL only — no result is injected (downstream input = its own task, self-sufficient). Needs a result? Use in. Only needs "run after upstream completes"? Use deps. Need both? Write both. Not passed = unchanged; passed (any form, including [] / null) = whole-list replacement. Upstream failed/cancelled/blocked never triggers a deps waiter (it stays pending and visible). A running upstream is legal to depend on (waiting for it IS the semantics); editing deps on a RUNNING node is rejected (input frozen).
- **out** (required on create, single value): node id or "Nebula" (flow exit). Single-value semantics: setting replaces the old out (rewire); arrays are rejected (1-to-many not supported); null (disconnect) is REJECTED — nodes must keep their out edge, rewire to a new target instead.
- **skill** / **mcp** / **worktree** / **preset** (optional): node configuration. worktree: bare directory name under workspace/.nebflow/worktrees/ (must exist) — e.g. "micorb-config-hide", NOT "worktrees/micorb-config-hide"; no path separators, no absolute paths, no "..". Prefix forms ("worktrees/<name>" / ".nebflow/<name>") are tolerated but the bare name is canonical. Create via: git worktree add <workspace>/.nebflow/worktrees/<name> -b <branch>.
- **maxRetries** (optional, default 1).
- **hold** (optional, default false): human-gate flag (human-in-the-loop) — when this node completes it does NOT deliver downstream and does NOT settle deps: status becomes HELD (result saved in full; never expires; stays on the main map), the full result is announced to Nebula, and the chain waits for NodeEdit(release=true). Requires out to be a NODE id — out="Nebula" is refused (Nebula-bound results deliver directly, nothing to hold). Settable/withdrawable while wiring/pending/running (a running node may be gated: the switch takes effect at completion); refused on completed/terminal/held.
- **release** (optional, default false): release a HELD node (the ONLY way out of held) — a STANDALONE action: combining it with task/agent/in/deps/out/abandon/hold/skill/mcp/worktree/preset/maxRetries refuses the whole call (no half-release-half-rewire; release first, then issue a separate NodeEdit to rewire). held → completed: the downstream delivery chain runs (deliverOut → deps settlement); display TTL restarts from release; completedAt keeps the held moment (when the work actually finished).
- **note** (optional, ONLY together with release=true): user supplementary text — atomically appended to the out target node's task ("== 用户补充（放行时注入） ==" section) so the downstream input carries it. Any other combination refuses.
- **abandon** (optional, default false): abandon a terminal (blocked/completed/failed/cancelled), wiring/pending, HELD, or a STALE RUNNING node whose session is dead (no live execution fiber, e.g. after an instance restart) → status=cancelled + display TTL (audit-logged). The dispatcher's give-up action for blocked nodes, the topology-cleanup exit for retired wiring/pending nodes, the clean-up exit for abandoned hold chains (held has no live session — no interruption side effects), and the reaping exit for dead-session running nodes. A LIVE running node is refused (mis-kill protection) — use NodeCancel for running nodes instead.

## Semantics
- nodename missing → create (agent required; 'out' is mandatory — a downstream node id or "Nebula"; plus an input side: task (entry semantics) or in. Dangling nodes and out-only relay nodes are rejected — EMPTY_NODE_CONNECTION).
- nodename exists → edit: in appends barrier inputs; deps replaces the whole dependency list (when provided); out sets/replaces (rewire). Disconnecting (out=null on a node that has an out edge) is rejected — rewire to a new target instead.
- Connection policy (20260903 收紧): creation requires BOTH an out edge (node id or "Nebula") AND an input side (task or in — task counts as the in-side connection, so entry nodes (task + out, no in) are legal and run on create; in and task may coexist). Missing out, or out with neither task nor in → EMPTY_NODE_CONNECTION. Disconnecting to a no-out state on edit → EMPTY_NODE_CONNECTION (rewire, don't disconnect). Legacy dangling nodes (out=null created before this policy) stay editable: edit their task/agent/in/deps freely, rewire their out to a target and the retained result auto-delivers.
- Blocked node edit: changing task/agent (or in/out) on a blocked node reactivates it — status returns to wiring/pending, deliveredTo cleared, blockCount preserved (round history kept), completed upstream results re-delivered, then the node reruns with the new input. No-op if nothing actually changed.
- abandon=true → terminal/wiring/pending/held node becomes cancelled with display TTL (frontend removes it after TTL; result kept in archive).
- Hold / release (human-in-the-loop, 20260903 design): a hold=true node completes to HELD — a NON-terminal paused state: result retained, announced to Nebula in full, no downstream delivery, no deps settlement; the node never expires or archives (awaits release indefinitely). Release with NodeEdit(release=true, note=?) — held → completed and the delivery chain runs as if it had just completed. To rewire a held node: release first, then a separate NodeEdit with out (the result delivers to the new target).
- Validation (0 spawn): agent exists; referenced nodes exist; DAG cycle check (DFS); 1-to-many rejected; target running → rejected ("input frozen — NodeCancel first").
- Create an entry node (task present, no in) → it starts running immediately (async, non-blocking). Node result = the agent's final output, auto-saved to the node's result and delivered along out (node → downstream barrier / Nebula → injected to root session / legacy no-out → retained, auto-delivered when rewired)."""
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
        ).asJson, "description" -> "Single out target: node id or \"Nebula\" (required on create). Arrays rejected (1-to-many); null (disconnect) rejected — rewire instead".asJson),
        "skill" -> Json.obj("type" -> "string".asJson),
        "mcp" -> Json.obj("type" -> "string".asJson),
        "worktree" -> Json.obj("type" -> "string".asJson, "description" -> "Bare directory name under workspace/.nebflow/worktrees/ (must exist). e.g. \"micorb-config-hide\" — NOT \"worktrees/micorb-config-hide\"; no path separators, no absolute paths".asJson),
        "preset" -> Json.obj("type" -> "string".asJson),
        "maxRetries" -> Json.obj("type" -> "integer".asJson),
        "hold" -> Json.obj("type" -> "boolean".asJson, "description" -> "Human-gate: node completes to HELD (result saved+announced to Nebula, no downstream delivery) awaiting release=true. Requires a node-id out (not \"Nebula\"). wiring/pending/running only".asJson),
        "release" -> Json.obj("type" -> "boolean".asJson, "description" -> "Release a HELD node → completed, delivery chain runs. STANDALONE action — any other edit parameter in the same call refuses (release first, then rewire separately)".asJson),
        "note" -> Json.obj("type" -> "string".asJson, "description" -> "User supplementary text, ONLY with release=true — appended to the out target's task (carried into downstream input)".asJson),
        "abandon" -> Json.obj("type" -> "boolean".asJson, "description" -> "Abandon a TERMINAL (blocked/completed/failed/cancelled), wiring/pending, HELD, or dead-session running node → cancelled + display TTL".asJson)
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
    // hold 三参（20260903 暂停/人在回路设计 §2.5）：Option 保留「显式传入」信号——
    // hold=false 显式传入 = 撤销闸点（缺省 = 不改动）；release/note 布尔/文本语义。
    val holdProvided = input("hold").flatMap(_.asBoolean)
    val hold = holdProvided.getOrElse(false)
    val release = input("release").flatMap(_.asBoolean).getOrElse(false)
    val note = input("note").flatMap(_.asString)
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
              case Some(existing) => editNode(rt, existing, agent, task, abandon, holdProvided, hold, release, note, skill, mcp, worktree, preset, maxRetries, inJson, depsJson, outJson, ctx)
              case None =>
                // 归档节点编辑兜底（fix b「已存在边+归档上游不补投递」修复 20260903）：
                // 活动区按名未命中 → 归档区按名兜底。归档节点只支持 out 改接（悬空
                // 完成结果的补投递——「悬空节点后来被接线」的归档变体：editNode 的
                // completed+newOut 投递分支沿 deliverOutTo 补投，barrier 随之结算）；
                // 其余编辑域（task/agent/in/deps/配置/abandon）拒绝——归档是显示过期
                //（TTL 满、结果保留可投递），不是重激活通道（重激活只属于 Blocked 态）。
                // 修复前：归档名落 createNode → 同名重复节点（拓扑污染）或静默失败。
                rt.store.archiveSnapshot.flatMap { arch =>
                  arch.nodes.values.find(_.name == nodename) match
                    case Some(archived) =>
                      val forbidden =
                        agent.isDefined || task.isDefined || skill.isDefined || mcp.isDefined ||
                          worktree.isDefined || preset.isDefined || maxRetries.isDefined ||
                          abandon || inJson.isDefined || depsJson.isDefined ||
                          holdProvided.isDefined || release || note.isDefined
                      if abandon then
                        IO.pure(Left(ToolError(s"Node '$nodename' is archived (display TTL expired) — abandon is not applicable; it already ages out of views on its own.")))
                      else if release || note.isDefined || holdProvided.isDefined then
                        IO.pure(Left(ToolError(
                          s"Node '$nodename' is archived (display TTL expired) — hold/release do not apply: held is a non-terminal state and is never archived; archived nodes are terminal. Only 'out' rewiring is supported.")))
                      else if !outJson.isDefined then
                        IO.pure(Left(ToolError(
                          s"Node '$nodename' is archived (display TTL expired, result retained). Only 'out' rewiring is supported for archived nodes (result re-delivery).")))
                      else if forbidden then
                        IO.pure(Left(ToolError(
                          s"Node '$nodename' is archived — only 'out' rewiring is supported (result re-delivery); task/agent/in/deps/config/hold edits are not.")))
                      else editNode(rt, archived, agent, task, abandon, holdProvided, hold, release, note, skill, mcp, worktree, preset, maxRetries, inJson, depsJson, outJson, ctx)
                    case None =>
                      if abandon then IO.pure(Left(ToolError(s"Node '$nodename' not found — abandon requires an existing node")))
                      else createNode(rt, nodename, agent, task, skill, mcp, worktree, preset, maxRetries, inJson, depsJson, outJson, hold)
                }
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
    outJson: Option[Json],
    hold: Boolean = false
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
        // 校验五（连接规范收紧 v2，20260903 裁定①②③）：创建必须 (task ∨ in) ∧ out。
        // out 是唯一出口权威边——悬空新节点不再支持，「先创建后补 out」工作流废弃；
        // in 侧 task 即连接（入口节点 task+out 创建即运行语义保持），in 边与 task 可
        // 并存；纯空挂载（无 task 无 in 无 out）由 out 检查一并拒绝。deps 是完成信号
        // 不是输入内容，不计入 in 侧（deps+out 纯信号中继仍须 task 或 in 承载输入
        // 语义）。错误码沿用 EMPTY_NODE_CONNECTION（两条文案都携带，供分发器自纠）。
        if out.isEmpty then
          IO.pure(Left(ToolError(
            s"Node '$nodename' must declare 'out' on creation — a downstream node id or \"Nebula\" (flow exit). " +
              "Dangling nodes are no longer supported (create-then-wire-out is retired): wire the out edge in the same NodeEdit. (EMPTY_NODE_CONNECTION)")))
        else if !task.exists(_.trim.nonEmpty) && ins.isEmpty then
          IO.pure(Left(ToolError(
            s"Node '$nodename' must declare an input side — 'task' (entry semantics: starts running on create) or 'in' (barrier upstream). " +
              "Out-only relay nodes are no longer supported. (EMPTY_NODE_CONNECTION)")))
        // hold 校验①（20260903 暂停/人在回路设计 §2.5 #1）：hold=true 要求 out 为
        // 节点 id——out=Nebula 结果直投 Nebula，无投递可扣，hold 无意义。
        else if hold && out.contains("Nebula") then
          IO.pure(Left(ToolError(
            "hold=true requires a node-target out edge — out=\"Nebula\" nodes deliver to Nebula directly, nothing to hold.")))
        else
          // 1. agent 存在性
          EntityLoader.loadAgent(agentName).flatMap {
            case None => IO.pure(Left(ToolError(s"Agent '$agentName' not found in global library")))
            case Some(_) =>
              // 2. worktree 归一化 + 双位置实存校验（20260903 worktree 参数修复，
              //    根因报告 20260903_nodeedit-worktree-param-fix.md §一/§六）：入参
              //    宽容归一（裸名 / "worktrees/<名>" / ".nebflow/<名>" / "./<名>" 均
              //    收，PathUtil.normalizeWorktree 单点，与 NodeEngine cwd 同源）；
              //    拒绝走 WORKTREE_FORMAT 可行动文案——os-lib InvalidSegment（面向
              //    Scala 开发者，LLM 不可自纠）不再外泄。实存双查：worktrees/ 权威
              //    位置优先、.nebflow/ 顶层存量 fallback（零迁移不破现网）。存储仍
              //    存裸名（Some(bare)，零迁移）。
              worktree match
                case Some(wt) =>
                  PathUtil.normalizeWorktree(wt) match
                    case Left(err) => IO.pure(Left(ToolError(err)))
                    case Right(bare) =>
                      if PathUtil.resolveWorktreeDir(os.Path(rt.project.workspace), bare).isEmpty then
                        IO.pure(Left(ToolError(
                          s"Worktree '$bare' not found under ${rt.project.workspace}/.nebflow/worktrees/ (nor top-level .nebflow/) — " +
                            s"create it first via: git worktree add ${rt.project.workspace}/.nebflow/worktrees/$bare -b <branch>")))
                      else proceed(rt, nodename, agentName, task, skill, mcp, Some(bare), preset, maxRetries, ins, deps, out, hold)
                case None => proceed(rt, nodename, agentName, task, skill, mcp, worktree, preset, maxRetries, ins, deps, out, hold)
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
    out: Option[String],
    hold: Boolean = false
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
            hold = hold,
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
    *
    * 死会话 running 收殓（清场 c-②，20260903 03:04 清场误杀事故复盘）：running 且
    * 无在飞执行 fiber（会话死于传输中断/实例重启泄漏）→ 可收殓（cancelled + TTL）。
    * 误杀防护（硬约束）：活 running（isRunning=true = 有在飞 fiber，取消信号可达）
    * 绝对拒绝，只能走 NodeCancel。无复活竞态：running 节点不会被 startNode 二次
    * spawn（入口状态幂等跳过），死会话不可能复活 → 预检后无需事务内复查 IO 信号。
    *
    * held 接受域（20260903 暂停/人在回路设计 §2.5 #7）：held 无在飞会话（会话已随
    * 完成销毁），无中断副作用 → cancelled + TTL + 审计同款；放弃一条暂停链的节点
    * 是合法清场。机械上 fresh 守卫（status != Running）天然放行 held，零分支改动，
    * 仅接受域文档与文案同步。 */
  private def abandonNode(rt: ProjectRuntime, node: NodeDef): IO[Either[ToolError, String]] =
    def doAbandon(allowDeadRunning: Boolean): IO[Either[ToolError, String]] =
      for
        now <- IO(System.currentTimeMillis())
        s <- rt.store.mutate { st =>
          st.nodes.get(node.id) match
            // R2：fresh 仍可收殓（终态/wiring/pending；或预检过死会话的 running）
            // 才写——并发重激活成 running 且未预检死会话时拒写（该窗口内节点已有
            // 在飞会话，abandon 不得中断）
            case Some(fresh) if fresh.status != NodeLifecycle.Running || allowDeadRunning =>
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
                s"node abandoned via NodeEdit (${node.status}${if allowDeadRunning && node.status == NodeLifecycle.Running then "/dead-session" else ""} → cancelled + display TTL)")
          case _ => IO.unit
      yield Right(s"Node '${node.name}' abandoned — cancelled with display TTL (archived after TTL, result retained)")

    if node.status == NodeLifecycle.Running then
      rt.engine.isRunning(node.id).flatMap {
        case true =>
          IO.pure(Left(ToolError(
            s"Node '${node.name}' is running with a live session — abandon refused (mis-kill protection: abandon accepts terminal/wiring/pending/held and dead-session running only). Use NodeCancel for running nodes.")))
        case false =>
          doAbandon(allowDeadRunning = true)
      }
    else doAbandon(allowDeadRunning = false)

  private def editNode(
    rt: ProjectRuntime,
    node: NodeDef,
    agent: Option[String],
    task: Option[String],
    abandon: Boolean,
    holdProvided: Option[Boolean],
    hold: Boolean,
    release: Boolean,
    note: Option[String],
    skill: Option[String],
    mcp: Option[String],
    worktree: Option[String],
    preset: Option[String],
    maxRetries: Option[Int],
    inJson: Option[Json],
    depsJson: Option[Json],
    outJson: Option[Json],
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    // ── 动作分支（20260903 暂停/人在回路设计 §2.5 校验③④ + 决策④）──
    // release 与 abandon 互斥；note 仅与 release 同用；release 是独立动作，
    // 与任何其他编辑参数同传 → 整调用拒绝（防半放行半改线；改线需求 =
    // 先 release 后再单独 NodeEdit 改接）。
    if release && abandon then
      IO.pure(Left(ToolError(
        s"release is a standalone action — it cannot be combined with abandon (or any other edit). Release '${node.name}' first, then issue separate NodeEdit calls.")))
    else if abandon then abandonNode(rt, node)
    else if note.isDefined && !release then
      // 校验④：note 仅与 release 同用
      IO.pure(Left(ToolError(
        "'note' is only valid together with release=true — it injects user supplementary text into the out target's task at release time.")))
    else if release then
      // 校验③：release 仅接受 held 节点（guard 在 releaseNode 内做 fresh 判定）；
      // 与其他编辑参数同传 → 拒绝（可行动文案指引先 release 再改接）
      val conflicts =
        List(
          task.isDefined -> "'task'", agent.isDefined -> "'agent'",
          inJson.isDefined -> "'in'", depsJson.isDefined -> "'deps'",
          outJson.isDefined -> "'out'", skill.isDefined -> "'skill'",
          mcp.isDefined -> "'mcp'", worktree.isDefined -> "'worktree'",
          preset.isDefined -> "'preset'", maxRetries.isDefined -> "'maxRetries'",
          hold -> "'hold'"
        ).collect { case (true, name) => name }
      if conflicts.nonEmpty then
        IO.pure(Left(ToolError(
          s"release is a standalone action — conflicting parameters present (${conflicts.mkString(", ")}). " +
            s"Release '${node.name}' first, then issue a separate NodeEdit for rewiring/editing.")))
      else
        rt.engine.releaseNode(node.id, note).map {
          case Right(msg) => Right(msg)
          case Left(err)  => Left(ToolError(err))
        }
    // 校验三（deps 设计 §1.2）：编辑 running 下游传 deps → 同款冻结拒绝（「输入已冻结」
    // 对齐 in 语义）；给下游加 deps 的上游是 running 则合法（等它完成正是 deps 语义）。
    else if depsJson.isDefined && node.status == NodeLifecycle.Running then
      IO.pure(Left(ToolError(
        s"Node '${node.name}' is running — its input is frozen. NodeCancel it first, then rewire.")))
    else
      // out 处理（单值替换/断开）——同步校验先 match，再进 IO 链
      NodeTools.parseOut(outJson) match
        case Left(err) => IO.pure(Left(ToolError(err)))
        // 校验六-a（连接规范收紧 v2，裁定④）：out 断开（显式 null/"null" 且节点现有
        // out）→ 拒绝——改接新目标而非断开（「先断开再改接」废弃）。存量悬空节点
        //（out 已 null）显式传 null = 无状态变更 no-op，放行（口径⑤ 存量不回溯）。
        case Right(newOut) if outJson.isDefined && newOut.isEmpty && node.out.isDefined =>
          IO.pure(Left(ToolError(
            s"Node '${node.name}' has an out edge — disconnecting (out=null) is no longer supported. " +
              "Rewire to a new target instead (out=<node-id|\"Nebula\">); every node keeps its out edge. (EMPTY_NODE_CONNECTION)")))
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
                          // hold 校验（20260903 暂停/人在回路设计 §2.5 #1/#2/#5）：
                          // #2 完成/终态/held 上设置或撤销 → 拒（held 的撤销出口是 release/
                          //   abandon，回退语义各司其职；blocked 属终态同拒——重激活后另设）；
                          // #5 running 合法（hold 是完成时行为开关，不属输入冻结域）；
                          // #1 hold=true 要求（最终）out 为节点 id（同调用改接出节点边也认）。
                          val holdStatusOk = holdProvided.isEmpty ||
                            (node.status == NodeLifecycle.Wiring || node.status == NodeLifecycle.Pending || node.status == NodeLifecycle.Running)
                          val holdOutOk = !hold ||
                            finalOut.exists(t => t != "Nebula")
                          val earlyReject: Option[ToolError] =
                            if finalIn.isEmpty && finalDeps.isEmpty && finalOut.isEmpty then
                              Some(ToolError(
                                s"Node '${node.name}' must keep at least one connection — this rewiring would leave it disconnected. (EMPTY_NODE_CONNECTION)"))
                            else if dChecks.exists(_.isLeft) then
                              Some(ToolError(dChecks.collectFirst { case Left(e) => e }.getOrElse("invalid deps")))
                            else if !holdStatusOk then
                              Some(ToolError(
                                s"hold can only be set or withdrawn before completion (wiring/pending/running) — node '${node.name}' is ${node.status} (result already delivered or held). Use release/abandon for held nodes; re-activation is the exit for blocked."))
                            else if !holdOutOk then
                              Some(ToolError(
                                "hold=true requires a node-target out edge — out=\"Nebula\" nodes deliver to Nebula directly, nothing to hold."))
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
                                      // hold 设置/撤销写回（20260903 暂停/人在回路设计 §2.5 #2/#5）：
                                      // 校验已在 earlyReject 拦截（终态/held/blocked → 拒）；
                                      // running 合法（完成时行为开关，rule 5）。事务内现读
                                      // fresh（R2 纪律）；状态校验双重保险。
                                      _ <-
                                        if holdProvided.isDefined then
                                          rt.store.mutate { s =>
                                            s.nodes.get(node.id) match
                                              case Some(fresh) if fresh.status == NodeLifecycle.Wiring || fresh.status == NodeLifecycle.Pending || fresh.status == NodeLifecycle.Running =>
                                                s.copy(nodes = s.nodes.updated(node.id, fresh.copy(hold = hold)))
                                              case _ => s
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
                                                  // V8: 重激活 = 该节点身份重跑一轮，out=Nebula 投递
                                                  // 记账同样清零——重跑完成后的结果重新投递+记账。
                                                  nebulaDeliveredAt = None,
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
                                          // 补投递完整性（fix b「已存在边+归档上游不补投递」修复
                                          // 20260903）：不只投递本次追加上游——全部 in 上游中
                                          // 「终态有结果而 deliveredTo 未记」的都补投（findNode
                                          // 活动/归档兜底）。覆盖两类缺口：①边已存在但投递曾丢失
                                          //（陈旧 out 覆盖时代的历史悬空，实证 n-219106db：两个
                                          // 归档 completed 上游在 in 里、deliveredTo 恒空、下游
                                          // 永久等待）；②新建边指向归档上游（fix a 路径，setOut
                                          // 归档感知后不再崩溃）。运行中上游天然跳过（无 result，
                                          // 等 completeNode → deliverOut 正常投）。
                                          // R1：blocked 反馈串不是可投结果，显式排除（同 create 路径）。
                                          _ <- rt.store.getNode(node.id).flatMap {
                                            case Some(nFresh) =>
                                              nFresh.in.traverse_ { upId =>
                                                rt.store.findNode(upId).flatMap {
                                                  case Some(up) if up.result.isDefined && up.status != NodeLifecycle.Blocked && NodeLifecycle.Terminal.contains(up.status) =>
                                                    rt.engine.deliverOutTo(up, node.id, up.result.get)
                                                  case _ => IO.unit
                                                }
                                              }
                                            case None => IO.unit
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
                                        (if holdProvided.isDefined then s" — hold → $hold" else "") +
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
- Cancel target must be running (non-running → no-op with notice; a HELD node is likewise a no-op — its exit is NodeEdit release=true or abandon=true, not cancel). A running node with a live session gets a cancel signal; a STALE running node (dead session, e.g. after an instance restart) is reaped — finalized as cancelled immediately instead of a fake success."""
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
              // 清场 c-③（20260903 事故复盘）：有在飞执行 fiber → 正常取消信号（原
              // 语义）；无在飞 fiber（会话已死/实例重启泄漏）→ 直接收殓终态化——修复
              // 「返回成功但节点状态不落终态」的假成功（假成功下分发器以为已止损，
              // stale running 永久滞留）。收殓内部二次复核 isRunning（防窗口竞态）。
              rt.engine.isRunning(nodeId).flatMap {
                case true => rt.engine.cancelNodeById(nodeId).as(Right(s"Node '${n.name}' cancel signal sent"))
                case false => rt.engine.reapStaleRunning(nodeId).map(_.left.map(ToolError(_)))
              }
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
