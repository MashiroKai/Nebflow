package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.ActorRef
import nebflow.agent.AgentCommand
import nebflow.core.{AskItem, NebflowLogger, PathUtil}
import nebflow.core.entity.EntityLoader
import nebflow.core.project.*

import scala.util.Try

/**
 * Node 工具集（#28 阶段 0，方案 §2.2 —— 任务分发器用）。
 *
 * 全部走工具、0 文件写入（验收①）：拓扑变更经 FlowMapStore 单事务原子更新；
 * 校验（agent 存在性 / 引用存在 / 环检测 / 1 对多 / 状态守卫 / loop detect）
 * 在工具层统一拦截，0 spawn 0 token。
 *
 * 边模型（§2.3 原子性 + P1 语义门控 20260908）：`node.out` 是边列表单权威（OutEdge：
 * to/on⊆{pass,failed}/mode∈{result,signal}）——设 out 时事务内同步更新目标节点 `in`
 *（反向一致），被移除目标 in 移除、新增目标 in 追加，杜绝双写漂移。in 参数用于
 * 创建/追加 barrier 入边（等价给上游 out 追加指向本节点的 pass 边）。
 */
object NodeTools:
  private val logger = NebflowLogger.forName("nebflow.node.tools")

  /** ⑥ 合并节点 in 上限（merge-node 设计落实批 U2/P1，2026-09-11 作者拍板「升引擎硬闸」）。
    * 纪律原文（分发器提示词「合并节点」节 + 设计指南 §0bis）：「in ≤4，超限拆多个合并
    * 节点」——此前纯纪律、引擎查无校验。边界：=4 合法、≥5 拒（`NODE_MERGE_IN_CAP`）。 */
  val MergeInCap: Int = 4

  /** ⑧ 「已触发」状态集（同一裁定）：running/blocked/completed 的合并节点其 in 账本冻结
    * ——新 worktree 配新合并节点，禁向已触发节点追加 in（`NODE_MERGE_FIRED_NO_IN`）。
    * un-triggered（wiring/pending）追加是正常回流，放行。
    *
    * failed/cancelled **不在集内**（有意）：二者是 NodeEdit 重激活闸唯一放行的两个状态
    * （`ReactivateStatuses`），把 in 追加一起拒会把「重激活时重接上游」这条恢复路径掐死
    * ——与设计原文只列 running/blocked/completed 逐字一致。 */
  val MergeFiredStatuses: Set[String] =
    Set(NodeLifecycle.Running, NodeLifecycle.Blocked, NodeLifecycle.Completed)

  /** 解析 project（参数优先，fallback ctx.projectName；项目名大小写不敏感——
    * registry.get 已做 equalsIgnoreCase 兜底，Nebflow/nebflow 等价）。 */
  def resolveProject(project: Option[String], ctx: ToolContext): IO[Either[String, ProjectRuntime]] =
    val name = project.orElse(ctx.projectName).getOrElse("")
    if name.isEmpty then IO.pure(Left("Missing 'project' parameter (and no project context)"))
    else
      ProjectRuntimeRegistry.get(name).flatMap {
        case Some(rt) => IO.pure(Right(rt))
        case None =>
          ProjectRuntimeRegistry.all.map(rts => Left(mountError(name, rts.map(_.project.name))))
      }

  /** 项目未挂载报错（附可用项目列表提示实际名称）——纯函数便于单测。
    * 精确项目名不匹配时列出已挂载项目（提示实际名称，不裸报 not mounted）；
    * 空列表（无任何挂载项目）给单独一行提示。 */
  def mountError(name: String, available: List[String]): String =
    if available.isEmpty then
      s"Project '$name' is not mounted. No projects are currently mounted. Use ProjectCreate first."
    else
      s"Project '$name' is not mounted. Available projects: ${available.mkString(", ")}. Use ProjectCreate first."

  /** 引用节点存在性（活动区 + 归档区）。 */
  def ensureNodeExists(rt: ProjectRuntime, id: String): IO[Either[String, Unit]] =
    rt.store.findNode(id).map {
      case Some(_) => Right(())
      case None => Left(s"Referenced node '$id' not found in project '${rt.project.name}'")
    }

  /** out 目标解析（20260909 in 丢失事故修复面单点）：out 边目标串接受节点 id 或节点名
    * （in/deps 维持纯 id 契约，不经此解析）。返回解析后的节点 id；悬空 → None。
    * 校验（存在性/状态守卫/环检测）、in 镜像记账、投递结算统一以解析结果为准——
    * 原始串形态只留在存储层（边 to 字段，前端/审计可读）。 */
  def resolveOutTarget(rt: ProjectRuntime, target: String): IO[Option[String]] =
    if target == OutEdge.NebulaTarget then IO.pure(None)
    else rt.store.snapshot.map(s => OutEdge.resolveTargetId(s.nodes, target))

  /** 状态守卫：目标已运行 → 拒绝接线（§2.2「目标已运行，输入已冻结」）。 */
  def ensureTargetNotRunning(rt: ProjectRuntime, id: String): IO[Either[String, Unit]] =
    rt.store.getNode(id).map {
      case Some(n) if n.status == NodeLifecycle.Running =>
        Left(s"Node '$id' is running — its input is frozen. NodeCancel it first, then rewire.")
      case _ => Right(())
    }

  /** P1 out 表面语法解析（spec §2.2；向后兼容 + 扇出 + 失败信号边）：
    *   out="B"                  → List(OutEdge("B"))                          旧单值等价
    *   out="(pass)B, (failed)C" → List(OutEdge("B"), OutEdge("C",{failed}))   扇出
    *   out="(failed)C:signal"   → List(OutEdge("C",{failed},"signal"))        失败纯信号边
    * 段语法：(gates)? target (:mode)?——gates 逗号分隔 ⊆{pass,failed}（缺省 pass；
    * **Nebula 缺省 {pass,failed}**=completed/failed 双通报旧形态零漂移，显式门控按
    * 声明收紧）；mode ∈{result,signal}（缺省 result）。null/"null" → Nil = 断开
    *（编辑侧 EMPTY_NODE_CONNECTION 守卫区分「未传」与「显式断开」，语义不变）；
    * JSON 数组 → 拒（1 对多改用逗号段语法）。输出经 canonical 规范化（同 (to,mode)
    * 合并 on 集）。纯函数便于单测。 */
  def parseOut(outJson: Option[Json]): Either[String, List[OutEdge]] =
    outJson match
      case None => Right(Nil) // 未传 out = 不改动（哨兵语义不变）
      case Some(j) if j.isNull => Right(Nil) // out=null = 断开（悬空化）
      case Some(j) if j.isArray =>
        Left("'out' must be a target spec string — fan-out uses the \"(pass)B, (failed)C\" segment syntax, not a JSON array")
      case Some(j) =>
        j.asString match
          case Some(s) => parseOutSyntax(s)
          case None => Left("'out' must be a target spec string (node id, \"Nebula\", or \"(gates)target:mode\" segments), or null")

  /** 表面语法段解析入口：整值 trim；"null"（任意大小写）= 断开；逗号分段逐段解析
    * 后 canonical 规范化。分段为**括号深度感知**——门组内的逗号（"(pass,failed)B"）
    * 不是段分隔符。 */
  def parseOutSyntax(s: String): Either[String, List[OutEdge]] =
    val t = s.trim
    if t.isEmpty then Left("'out' must be a non-empty target spec, \"Nebula\", or null")
    else if t.equalsIgnoreCase("null") then Right(Nil)
    else
      val segs = splitOutSegments(t)
      if segs.isEmpty then Left("'out' must be a non-empty target spec, \"Nebula\", or null")
      else segs.traverse(parseOutSegment).map(OutEdge.canonical)

  /** 括号深度感知的段分割：depth==0 处的逗号分段，门组内逗号保留。 */
  private def splitOutSegments(s: String): List[String] =
    val buf = new StringBuilder
    var depth = 0
    s.foreach { ch =>
      ch match
        case '(' => depth += 1; buf += ch
        case ')' => depth -= 1; buf += ch
        case ',' if depth == 0 => buf += '\u0000'
        case _ => buf += ch
    }
    buf.result().split('\u0000').map(_.trim).filter(_.nonEmpty).toList

  /** 门控组解析："(pass)" / "(pass,failed)"——空组/非法门 → 可行动错误（附原文）。 */
  private def parseOutGates(inner: String, seg: String): Either[String, Set[String]] =
    val parts = inner.split(',').map(_.trim).filter(_.nonEmpty).toList
    if parts.isEmpty then Left(s"invalid out segment '$seg': empty '(gates)' — use pass and/or failed")
    else
      val gs = parts.map(_.toLowerCase).toSet
      val invalid = gs -- OutEdge.Gates
      if invalid.nonEmpty then
        Left(s"invalid out gate(s) '${invalid.mkString(",")}' in '$seg' — gates ⊆ {pass, failed}")
      else Right(gs)

  /** 单段解析：(gates)? target (:mode)?。target 空 / 字面 null / mode 非法 → 错误。 */
  private def parseOutSegment(seg: String): Either[String, OutEdge] =
    val parsed =
      if seg.startsWith("(") then
        seg.indexOf(')') match
          case -1 => Left(s"invalid out segment '$seg': unclosed '(gates)' — expected \"(pass)target:mode\" form")
          case i => parseOutGates(seg.slice(1, i), seg).map(gs => (gs, seg.substring(i + 1).trim))
      else Right((OutEdge.DefaultOn, seg))
    parsed.flatMap { case (declaredGates, rest) =>
      val gatesImplicit = !seg.startsWith("(")
      val colon = rest.lastIndexOf(':')
      val (target, mode) =
        if colon > 0 then (rest.substring(0, colon).trim, rest.substring(colon + 1).trim.toLowerCase)
        else (rest.trim, OutEdge.Result)
      if target.isEmpty || target.startsWith(":") then Left(s"invalid out segment '$seg': missing target node id")
      else if target.equalsIgnoreCase("null") then Left(s"invalid out segment '$seg': null is only valid as the whole out value")
      else if !OutEdge.Modes.contains(mode) then Left(s"invalid out mode ':$mode' in '$seg' — mode ∈ {result, signal}")
      else
        // Nebula 缺省双通报门（旧形态零漂移）；显式门控按声明
        val on = if target == OutEdge.NebulaTarget && gatesImplicit then OutEdge.NebulaDefaultOn else declaredGates
        Right(OutEdge(target, on, mode))
    }

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

  /** 事务内设 out 边集（单权威，P1 多边版）：被移除边目标 in 移除 + 新增边目标 in
    * 追加 + 本节点 out 替换（canonical 规范化落库）。同 to 保留边不重触发 in 写
    *（幂等）。归档感知（fix a「新建边→归档上游」修复 20260903）：from 在归档区
    * 时——活动区侧照常做目标 in 增删，from.out 补写归档区（mutateArchive；归档节点
    * 不可复活，仅元数据保持单权威一致）；旧目标也在归档区时其 in 不回写（归档 in
    * 表是死数据，避免跨区二跳写）。 */
  def setOut(rt: ProjectRuntime, fromId: String, newOut: List[OutEdge]): IO[Unit] =
    val newEdges = OutEdge.canonical(newOut)
    def rewire(nodes: Map[String, NodeDef], from: NodeDef): Map[String, NodeDef] =
      // in 镜像记账按解析后的节点 id 做（20260909 in 丢失事故修复）：目标串有 id/名字
      // 两种形态，removed/added 的 diff 必须在「节点身份」空间进行——按原始串 diff 时，
      // 「旧 id 形态边 → 新名字形态边指向同一节点」会被误判为改接他点，removed 侧把
      // 下游 in 抹掉、added 侧又因名字查 Map MISS 而漏记（净效应 = in 蒸发）。
      val oldIds = from.out.map(_.to).filterNot(_ == OutEdge.NebulaTarget).distinct
        .flatMap(OutEdge.resolveTargetId(nodes, _)).distinct
      val newIds = newEdges.map(_.to).filterNot(_ == OutEdge.NebulaTarget).distinct
        .flatMap(OutEdge.resolveTargetId(nodes, _)).distinct
      val afterRemoved = oldIds.diff(newIds).foldLeft(nodes)((acc, tid) =>
        acc.get(tid).map(tn => acc.updated(tid, tn.copy(in = tn.in.filterNot(_ == fromId)))).getOrElse(acc))
      val afterAdded = newIds.diff(oldIds).foldLeft(afterRemoved)((acc, tid) =>
        acc.get(tid).map(tn => acc.updated(tid, tn.copy(in = (tn.in :+ fromId).distinct))).getOrElse(acc))
      afterAdded.updated(fromId, from.copy(out = newEdges))
    rt.store.getNode(fromId).flatMap {
      case Some(_) =>
        rt.store.mutate { s => s.copy(nodes = rewire(s.nodes, s.nodes(fromId))) }.void
      case None =>
        rt.store.findNode(fromId).flatMap {
          case None => IO.unit // 两区皆无（并发 TTL 迁移已删）→ no-op
          case Some(archFrom) =>
            // 归档分支：活动侧做目标 in 增删（rewire），但 rewire 会把 from 按
            // 活动节点回插（updated(fromId, …)）——归档原件必须剔除，不复活进活动区
            //（历史损伤 n-52670d20 形态回归面：NodeEdgeRepairSpec ③）。
            rt.store.mutate { s => s.copy(nodes = rewire(s.nodes, archFrom) - fromId) }.void *>
              rt.store.mutateArchive(a => a.copy(nodes = a.nodes.updatedWith(fromId)(_.map(_.copy(out = newEdges))))).void
        }
    }

  /** in 声明的边侧镜像（P1 事务内纯函数）：上游 out **追加**指向本节点的缺省 pass
    * 边——上游已有指向本节点的边（任意门控）则保持不变（in 声明不覆盖既有门控声明，
    * 扇出拓扑的其它边零扰动；旧单值世界的「改指」语义由多边追加天然兼容）。 */
  def appendEdgeTo(nodes: Map[String, NodeDef], upId: String, toNodeId: String): Map[String, NodeDef] =
    nodes.get(upId) match
      case Some(up) if !up.out.exists(_.to == toNodeId) =>
        nodes.updated(upId, up.copy(out = up.out :+ OutEdge(toNodeId)))
      case _ => nodes

  /** P1 校验层①（spec §2.2，wf3 §3.7 护栏）：指向 merge 节点的 on-failed 边 → 硬拒
    * （NODE_MERGE_PASS_ONLY）。merge 触发语义 = 全部上游 completed（in-barrier）；
    * 上游 failed 由 D5+MergeNodePolicy 转 blocked 可见终态——failed 门控入边与该语义
    * 矛盾，创建/改接期 0 spawn 拒绝。运行期兜底（deliverFailed merge 分支与门控无关）
    * 原样保留为纵深（覆盖校验生效前落盘的旧拓扑）。返回首个违规的错误文本。 */
  def ensureMergePassOnly(rt: ProjectRuntime, edges: List[OutEdge]): IO[Option[String]] =
    edges
      .filter(e => e.on.contains(OutEdge.Failed) && e.to != OutEdge.NebulaTarget)
      .toList.traverse { e =>
        // 目标按解析后 id 查（20260909 修复面：名字形态的 failed 边不再绕过本门控）
        NodeTools.resolveOutTarget(rt, e.to).flatMap {
          case None => IO.pure(None)
          case Some(tid) =>
            rt.store.getNode(tid).map {
              case Some(t) if MergeNodePolicy.isMerge(t) =>
                Some(s"out edge '(failed)${e.to}' targets a merge node — on-failed edges into a merge node are rejected: a merge fires only when ALL upstreams complete, and upstream failure already converts it to a visible blocked state (D5). Use a pass-only edge, or signal a non-merge fallback node instead. (NODE_MERGE_PASS_ONLY)")
              case _ => None
            }
        }
      }.map(_.flatten.headOption)

  /** P1 校验层②（spec §2.2，wf3 §3.7 护栏）：下游持 in 边而上游已 failed 且上游
    * 无指向本下游的 on-failed 边、下游非 merge → WARNING 级提示（**不阻断**——
    * 「愿意人工兜底」合法；死锁持续可见性由既有 mount-stalled 事件承载）。触发面 =
    * NodeEdit（create/edit）对最终 in 集的声明式复检：仅上游当前已 failed 时提示
    * （死锁是活的真实形态，正常 pass-only 拓扑零噪音）。返回提示行列表（空=无）。 */
  def stalledInWarning(rt: ProjectRuntime, nodeId: String, nodeName: String, finalIn: List[String], isMerge: Boolean): IO[List[String]] =
    if isMerge || finalIn.isEmpty then IO.pure(Nil)
    else
      finalIn.distinct.traverse { upId =>
        rt.store.findNode(upId).map {
          case Some(up) if up.status == NodeLifecycle.Failed
              && !up.out.exists(e => e.to == nodeId && e.on.contains(OutEdge.Failed)) =>
            Some(s"⚠ upstream '$upId' ('${up.name}') has FAILED and no on-failed edge reaches this node — its in-barrier can never clear (stays wiring; mount-stalled will keep reporting it until the upstream is fixed and reactivated). Proceeding is fine if manual fallback is intended.")
          case _ => None
        }
      }.map(_.flatten)

  // ── 环检测（对外暴露给测试）────────────────────────────

  def wouldCreateCycle(rt: ProjectRuntime, fromId: String, to: String): IO[Boolean] =
    rt.store.wouldCreateCycle(fromId, to)

  /** P2 retry 风暴防护两校验（spec §2.3，0 spawn；批E2）。Some(错误) = 拒绝：
    *   ① 邻居限定（NODE_RETRY_NEIGHBOR）：retry.upstream 必须是持有节点的 in/deps
    *      邻居——回跳语义 =「重取上游产物再试」，跨子图回跳无输入语义支撑（沿 deps
    *      「下游单侧持有」连通性先例）。
    *   ② retry 环（NODE_RETRY_CYCLE）：retry 图自身无环（B.retry→C 且 C.retry→B
    *      拒绝）——retry 边不进 DAG（wf3 §4.2 方案①：绕开环检是设计前提），自环
    *      防护必须独立成检（规模极小：每节点至多一条 retry 出边，链走即判）。
    * upstream 存在性无需单查：邻居限定以 in/deps 为值域（其成员均经存在性校验），
    * 非邻居即拒（错误文案指向先接线）。
    *
    * @param holderId   retry 持有节点 id（创建期 = 新节点临时 id，环检起点）
    * @param policy     本次要设置的 retry 策略
    * @param neighbors  持有节点的最终 in ∪ deps（编辑路径 = 应用本次改动后的全集） */
  def retryGuard(
    rt: ProjectRuntime,
    holderId: String,
    holderName: String,
    policy: RetryPolicy,
    neighbors: List[String]
  ): IO[Option[String]] =
    if !neighbors.distinct.contains(policy.upstream) then
      IO.pure(Some(
        s"retry.upstream '${policy.upstream}' must be an in/deps neighbor of node '$holderName' — " +
          "retry re-runs an upstream whose output feeds this node; declare the in/deps edge first, then set retry. (NODE_RETRY_NEIGHBOR)"))
    else
      retryCycleExists(rt, holderId, policy.upstream).map {
        case true =>
          Some(
            s"retry chain cycle: setting retry on '$holderName' → '${policy.upstream}' closes a retry loop " +
              "(B.retry→C and C.retry→B would re-run nodes forever) — retry chains must stay acyclic. (NODE_RETRY_CYCLE)")
        case false => None
      }

  /** retry 环判定（retryGuard ②的载体）：沿 retry.upstream 链行走（候选边视同已
    * 设置），回到持有者即环；访问集防既有数据（手改 flow-map.json）已环时死循环。 */
  def retryCycleExists(rt: ProjectRuntime, holderId: String, candidateUp: String): IO[Boolean] =
    rt.store.snapshot.map { s =>
      def nextOf(id: String): Option[String] =
        if id == holderId then Some(candidateUp)
        else s.nodes.get(id).flatMap(_.retry.map(_.upstream))
      def walk(cur: String, visited: Set[String]): Boolean =
        if cur == holderId then true
        else if visited.contains(cur) then false
        else nextOf(cur) match
          case Some(n) => walk(n, visited + cur)
          case None    => false
      walk(candidateUp, Set.empty)
    }


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

  /** NodeList 工具 / REST flow-map 端点共用的载荷（nodes/worktrees/chains/meta，
    * §2.2 NodeList 返回结构）。节点序列化统一走 NodePayload.buildNodeJson（与 WS
    * 事件 payload 同构）。
    *
    * statusFilter（观测面上下文经济学批 20260907 裁定⑤a）：可选生命周期枚举多选
    * 过滤——**缺省 None = 全量，输出字节级等于现状**（向后兼容铁律；REST/前端
    * 调用零改动）。命中过滤时 liveness 探测也只对入选 running 节点做。
    *
    * chains 顶层旁挂 + 节点级 chainId / chainIds（链级抽象 P0 · spec §6.2；U1 多链
    * 归属批）：派生单点 = FlowMapStore.topologicalChains（活动∪归档合并集，D8），
    * 旁挂仅收「分量成员数 ≥2 且含活动成员」的链（与节点级 chainId 判据同口径——
    * 凡载荷带 chainId 的节点其链条目必在旁挂中，前端 chainId → 链查找恒命中）；
    * 条目形状 {id,title,entries,ends,memberIds}，title 由 FlowMapStore.chainTitle
    * 三级推导下发（前端零派生）；entries/ends/memberIds = 分量全量（含归档成员，
    * spec §6.2「全成员」——主图渲染由前端按节点缓存过滤）。节点级 chainIds 条件键
    * **仅 merge 节点且成员链数 ≥2** 带（作者裁定①：多链归属只对合并节点做；普通
    * 节点恒单值 chainId），值 = 全量成员链（无上限、无降级）。
    * WS 不带链级帧，结构变化由前端对账重拉快照消化。 */
  def buildNodeListPayload(rt: ProjectRuntime, statusFilter: Option[Set[String]] = None): IO[Json] =
    for
      s <- rt.store.snapshot
      arch <- rt.store.archiveSnapshot
      // 清场 c-①（20260903 03:04 清场误杀事故复盘）：running 节点携带 liveness
      // 字段——true = 有在飞执行 fiber（活会话，取消信号可达）；false = 无在飞
      // fiber（死会话残留/实例重启泄漏，可经 NodeCancel / abandon 收殓）。非
      // running 节点不带该键（wiring/pending 无会话存活概念、终态无存活可言
      // ——语义明确）。仅快照（NodeList 工具 / REST flow-map）带；WS 事件单一
      // 序列化点（NodePayload.buildNodeJson）不动 → 事件键集断言零影响。
      // 裁定⑤a：过滤先行——liveness 只探入选节点（缺省 None 集合不变）。
      selected = statusFilter match
        case None    => s.nodes.values.toList.sortBy(_.createdAt)
        case Some(fs) => s.nodes.values.toList.sortBy(_.createdAt).filter(n => fs.contains(n.status))
      liveness <- selected
        .filter(_.status == NodeLifecycle.Running)
        .traverse(n => rt.engine.isRunning(n.id).map(alive => n.id -> alive))
        .map(_.toMap)
    yield
      val now = System.currentTimeMillis()
      // 链派生（合并集分量）+ chainId 条件键注入 + chains 旁挂组装（同源单点）
      val combined = s.nodes ++ arch.nodes
      val chains = FlowMapStore.topologicalChains(combined.values)
        .filter(c => c.memberIds.size >= 2 && c.memberIds.exists(s.nodes.contains))
      val chainIdByNode = chains.flatMap(c => c.memberIds.map(_ -> c.id)).toMap
      // U1 多链归属（作者裁定①）：仅 merge 节点、成员链数 ≥2 才有值——普通节点恒
      // 缺席（单值 chainId 语义不变）；派生与 chainId 同源（同一份 chains 分量表，
      // 禁双端二次派生）。
      val chainIdsByNode = combined.values.filter(_.merge).flatMap { n =>
        FlowMapStore.mergeChainIds(combined, chains, n.id).map(n.id -> _)
      }.toMap
      val nodes = selected.map { n =>
        val base = NodePayload.buildNodeJson(n, now, chainIdByNode.get(n.id), chainIdsByNode.get(n.id))
        liveness.get(n.id) match
          case Some(alive) => base.deepMerge(Json.obj("liveness" -> Json.fromBoolean(alive)))
          case None        => base
      }
      val chainsJson = chains.map { c =>
        val members = c.memberIds.flatMap(combined.get)
        Json.obj(
          "id" -> c.id.asJson,
          "title" -> FlowMapStore.chainTitle(members, c.id).asJson,
          "entries" -> c.entries.asJson,
          "ends" -> c.ends.asJson,
          "memberIds" -> c.memberIds.asJson
        )
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
        "chains" -> chainsJson.asJson,
        "meta" -> Json.obj(
          "project" -> rt.project.name.asJson,
          "updatedAt" -> s.updatedAt.asJson,
          "archived" -> arch.nodes.size.asJson
        )
      )

/** notifyDispatcher 参数载体（dispatch-notify 批 2026-09-05）：call() 解析的
  * （flag, provided）经 implicit 从 call() 词法作用域自动填入 createNode/proceed/
  * editNode——三者的既有调用点零文本改动（在飞批 trigger-chain-fix 占用了这些
  * 调用点行，碰撞规避；implicit 参数解析为 Scala 标准机制，非 hack）。 */
final case class NodeEditNotify(flag: Boolean, provided: Boolean)

/** loop 参数载体（LoopNode 批 2026-09-06）：call() 解析的（config, provided）经
  * implicit 自动填入 createNode/proceed/editNode——调用点零文本改动（与
  * NodeEditNotify 同机制）。provided=false（未传）= 编辑不改动 / 创建 None；
  * provided=true 且 config=Some(LoopConfig) = 启用 loop；provided=true 且
  * config=None = 显式停用 loop（loop=false）。 */
final case class NodeEditLoop(config: Option[LoopConfig], provided: Boolean)

/** retry 参数载体（批E2 P2 failed 回跳，spec §2.3）：call() 解析的（policy,
  * provided）经 implicit 自动填入 createNode/proceed/editNode（与 notify/loop 同
  * 机制）。provided=false（未传）= 编辑不改动 / 创建 None；provided=true 且
  * policy=Some = 设置/替换（对象或字符串形态）；provided=true 且 policy=None =
  * 显式清除（retry=null，replace-on-provide 与 deps 同款）。 */
final case class NodeEditRetry(policy: Option[RetryPolicy], provided: Boolean)

object NodeEditTool extends Tool:
  val name = "NodeEdit"

  /** 观测面上下文经济学批（20260907 裁定⑤b）：描述 7,438 → 3,742 字符（-50%，
    * 压措辞不改语义——参数面/动作语义/错误码/校验/重激活规则全保留）；合并
    * 观测面P0P1引擎批时统一进基线后落的 main 语义（failed 重激活条款 / abandon
    * 无 TTL 裁定 / notifyDispatcher completion-only）→ 4,308 字符（仍 -42% vs
    * 7,438；NodeSchemaSlimSpec 预算断言随之 3800→4400，语义不可删故放宽预算）。
    * 批D2 描述重写（20260908 语义门控 spec §5 批D2，行为基线=E1+E2 已实施代码）：
    * out 门控语法逐段展开（单值/扇出/失败信号边/门缺省 on={pass} 零漂移/Nebula
    * 双门例外/mode=signal deps 同款）+ retry 自动回跳链（下游单侧持有/gen 代次/
    * cap 耗尽 RetryCap 升级/邻居与环两校验）+ NODE_MERGE_PASS_ONLY 硬拒与
    * 无 on-failed 边 WARNING 两条新校验条款 → ~5,3xx 字符（预算断言随之
    * 4700→5400，同窗合并使前缀缓存一次性失效——spec §4.2 cache 纪律）。
    * 该描述随 tools 数组进分发器每次请求。长度上限由 NodeSchemaSlimSpec 断言钉住）。 */
  val description =
    """Create or edit a Flow Map node — the dispatcher's single topology tool (create / wire / rewire). No files written (the store owns flow-map.json).
## Parameters
- project (optional; defaults to current project): project name.
- nodename: unique display name; missing = create, existing = edit.
- description (required on create, ≤60 chars): one-line purpose — always-loaded card/payload metadata (results read on demand). Replace on edit.
- descriptionLong (optional, ≤200 chars): longer summary, detail channel only (never in default payloads). Replace on edit.
- task (optional): node task; an entry node (task, no in) starts running on create.
- in (optional): upstream id(s) added as barrier inputs (multi-in = barrier); each upstream's out gains a default pass edge here (existing edges kept).
- deps (optional, replace-on-provide): upstream id(s) awaited for COMPLETION SIGNAL only — no result injected (input = own task; needs result? in, ordering only? deps). Passed (any form, incl []/null) = whole-list replacement. failed/cancelled/blocked upstream never triggers; running upstream legal; editing deps on RUNNING node rejected (input frozen).
- retry (optional, downstream-held like deps): failed auto-retry {upstream:"<in/deps-neighbor>", max:N} or "<id>:<N>"; null clears. On FAIL with gen<N: auto-reactivates self (gen+1) and re-runs that upstream — fresh result re-delivers via the pass edge (replaces that round's failed notify; retry logged); gen≥N → failed + RetryCap escalation. max 1-10; neighbor-only (NODE_RETRY_NEIGHBOR); chain acyclic (NODE_RETRY_CYCLE).
- out (required on create; rewrites the edge set on edit): "B" = pass edge with result payload (legacy); "Nebula" = root notify on completed+failed; fan-out "(pass)B, (failed)C"; failure edge to a node = "(failed)C:signal". Gates ⊆ pass,failed (default pass — legacy zero-drift; implicit-gate Nebula = pass+failed); mode :result (payload; default) | :signal (signal only — deps parity). null disconnect rejected — rewire (EMPTY_NODE_CONNECTION). on-failed edge into a merge node rejected (NODE_MERGE_PASS_ONLY).
- plugins (optional, replace-on-provide): plugin name(s) — THE capability mechanism (no per-node agent; nodes run general). A plugin = skills + mcp.json (either alone valid); allocation injects skills into the first message, starts MCP servers (mcp__plugin_<p>_<s>__<t>), grants builtin tools. Names must be in the Plugin Catalog AND trusted (default-deny).
- worktree (optional boolean, create-time only): true = isolated git worktree auto-created at .nebflow/worktrees/<derived-from-name> (same-name branch, baseline main HEAD); immediate + fail-fast. Refused on edits.
- preset: legacy (unused).
- abandon (optional, default false): terminal (blocked/completed/failed/cancelled), wiring/pending, or STALE running (dead session) node → cancelled, retained on map, result kept (no TTL — failed/cancelled never auto-archive; cleanup is the caller's; audit-logged). Blocked give-up / dead-session reaping exit. LIVE running refused — use NodeCancel.
- notifyDispatcher (optional, default false): on COMPLETION (flag on), trigger a dispatcher session with this node's result reference (independent channel, no out-edge cost). Completion-only — failed notifies automatically (never flag-gated); blocked reserved. Settable/withdrawable while wiring/pending/running; dispatcher-created nodes default false.
- Retired (rejected, NODE_AGENT_RETIRED): agent / skill / mcp — capability = plugins; legacy values display-only.
## Semantics
- Create requires out AND an input side (task or in; coexist ok) → else EMPTY_NODE_CONNECTION. Entry (task + out) runs on create (async).
- out delivery: completed → pass edges fire (:result payload / :signal bare start; ≤1 delivery per (target,mode)). failed → on-failed :signal edges fire; the rest keeps waiting (D5 zero-settlement — fix + reactivate the upstream; completion re-delivers via pass edges). Wiring into an already-FAILED upstream with no on-failed edge → non-blocking warning (manual fallback legal).
- merge=true (create-only): batch landing sink — fires when ALL upstreams completed; upstream failure → blocked (upstream-incomplete). REQUIRES in ≥1 (NODE_MERGE_REQUIRES_UPSTREAM: create upstreams first); must NOT carry worktree (lands on workspace root repo).
- Edit: in appends; deps replaces (when provided); out rewrites the whole edge set; description(s) replace (when provided). Rewire removing a consumed target (running/terminal) rejected — NodeCancel first; completed (or legacy dangling, pre-policy out=null) rewire → retained result auto-delivers to new targets (edge-gated).
- Blocked node edit (task/description/in/out/deps/loop changed) reactivates: status → wiring/pending, deliveredTo cleared, blockCount kept, completed upstreams re-delivered, reruns. No-op if nothing changed.
- Failed node edit: actual change (task/description/in/out/deps/loop) on a FAILED node reactivates like blocked — first-choice recovery (topology kept); round history resets (blockCount→0; next real failure notifies again). completed/cancelled not reactivatable (create successor instead).
- Validation (0 spawn except worktree): description rules; referenced nodes exist; DAG cycle check; running target → input frozen. Result = agent's final output, auto-saved per-node, delivered along out ("Nebula" → root session). Full result: NodeList(detail=<nodeId>)."""
  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "project" -> Json.obj("type" -> "string".asJson, "description" -> "Project name (optional; defaults to the dispatcher's current project)".asJson),
        "nodename" -> Json.obj("type" -> "string".asJson, "description" -> "Display name; unique within the Flow Map".asJson),
        "description" -> Json.obj("type" -> "string".asJson, "description" -> "REQUIRED on create: one-line summary of the node's purpose, 1-60 chars (always-loaded card/payload metadata; replace on edit)".asJson),
        "descriptionLong" -> Json.obj("type" -> "string".asJson, "description" -> "Optional longer summary, ≤200 chars — detail channel only (NodeList detail= / REST), never in default payloads".asJson),
        "task" -> Json.obj("type" -> "string".asJson, "description" -> "Node task context; entry nodes (task, no in) run immediately".asJson),
        "in" -> Json.obj("oneOf" -> Json.arr(
          Json.obj("type" -> "string".asJson),
          Json.obj("type" -> "array".asJson, "items" -> Json.obj("type" -> "string".asJson))
        ).asJson, "description" -> "Upstream node id(s) to add as barrier inputs".asJson),
        "deps" -> Json.obj("oneOf" -> Json.arr(
          Json.obj("type" -> "string".asJson),
          Json.obj("type" -> "array".asJson, "items" -> Json.obj("type" -> "string".asJson))
        ).asJson, "description" -> "Upstream node id(s) this node waits on for completion signal only (no result injected). Replace-on-provide: [] / null clears. Needs the result? Use in".asJson),
        "out" -> Json.obj("type" -> "string".asJson,
          "description" -> "Out-edge spec (required on create; on edit replaces the whole edge set): edge = target + gates + mode. Target = node id OR node name (the engine resolves names to nodes for validation, in-edge mirrors and delivery). \"B\" = pass edge with payload (legacy); \"Nebula\" = root notify on completed+failed; fan-out \"(pass)B, (failed)C\"; node failure edge = \"(failed)C:signal\" (failure starts C on its own task — error text never injected). Gates (parens, comma-sep) ⊆ pass,failed — default pass; implicit-gate Nebula = pass+failed; explicit gates narrow. mode :result (payload; default) | :signal (barrier settle only — deps parity). On-failed edge into a merge node rejected (NODE_MERGE_PASS_ONLY); null disconnect rejected while an edge exists — rewire (EMPTY_NODE_CONNECTION); JSON arrays rejected — use segment syntax. Same (target,mode) edges merge gates".asJson),
        "plugins" -> Json.obj("oneOf" -> Json.arr(
          Json.obj("type" -> "string".asJson),
          Json.obj("type" -> "array".asJson, "items" -> Json.obj("type" -> "string".asJson))
        ).asJson, "description" -> "Plugin package name(s) allocated to this node (§B.4) — THE capability mechanism (no per-node agent): skills injected into the first message + plugin MCP servers + builtin tool grants. Replace-on-provide (like deps). Names must exist in the Plugin Catalog AND be approved (trust gate default-deny) — unapproved allocation is refused".asJson),
        "worktree" -> Json.obj("type" -> "boolean".asJson, "description" -> "Create-time only: true = isolated git worktree auto-created at .nebflow/worktrees/<derived-from-node-name> (same-name branch, baseline = main HEAD; failure rejects the NodeEdit). false/omitted = workspace direct-run. Refused on edits".asJson),
        "preset" -> Json.obj("type" -> "string".asJson),
        "merge" -> Json.obj("type" -> "boolean".asJson, "description" -> "Merge/collection node (batch landing sink, create-only): triggers only when ALL upstreams completed (in-barrier); an upstream failure converts this node to blocked (category=upstream-incomplete) instead of the collect placeholder-start. Must NOT carry 'worktree' — a merge node lands on the workspace root repo (sandbox root = workspace, .git writable); task should embed the upstream branch/worktree list + landing command set. REQUIRES 'in' (≥1 existing upstream id) on create — zero-upstream merge is rejected (NODE_MERGE_REQUIRES_UPSTREAM): create the upstreams first, then this node with in=<ids>".asJson),
        "notifyDispatcher" -> Json.obj("type" -> "boolean".asJson, "description" -> "dispatch-notify backflow: on terminal state (completion wired) trigger a dispatcher session with this node's result reference (independent signal channel, no out-edge cost). Settable/withdrawable while wiring/pending/running".asJson),
        "abandon" -> Json.obj("type" -> "boolean".asJson, "description" -> "Abandon a TERMINAL (blocked/completed/failed/cancelled), wiring/pending, or dead-session running node → cancelled, retained on map (no TTL — failed/cancelled never auto-archive; upper layer decides cleanup; audit-logged)".asJson),
        "loop" -> Json.obj("type" -> "boolean".asJson, "description" -> "LoopNode flag (create/edit): true = this node iterates — a WORKER session produces a result, a VERIFY session checks it; PASS → delivered downstream; FAIL → worker re-runs (same session, constant input) up to maxRounds(K). false/omitted = normal single-pass node. A loop node still declares its normal in/out/deps (and may merge) — loop only adds the inner iterate-verify loop".asJson),
        "retry" -> Json.obj(
          "oneOf" -> Json.arr(
            Json.obj("type" -> "object".asJson, "properties" -> Json.obj(
              "upstream" -> Json.obj("type" -> "string".asJson),
              "max" -> Json.obj("type" -> "integer".asJson)),
              "required" -> Json.arr("upstream".asJson, "max".asJson)),
            Json.obj("type" -> "string".asJson)
          ).asJson,
          "description" -> "Failed auto-retry policy (P2, downstream-held like deps): {upstream:\"<in/deps-neighbor id>\", max:N} or \"<id>:<N>\". On this node's FAILURE with gen<N the engine auto-reactivates it (gen+1, deliveredTo cleared) AND re-runs the upstream (terminal upstreams only — in-flight reruns untouched); the fresh result re-delivers along the pass edge as a new attempt. The auto-retry replaces that round's failed dispatcher notification (each retry is logged via nodeUpdated + retry event); gen≥N → terminal failed: dispatcher notification AND RetryCap escalation to Nebula both fire. max 1-10 (NODE_RETRY_MAX_RANGE; max=1 = one retry, two runs). upstream must be an in/deps neighbor (NODE_RETRY_NEIGHBOR — wire first); retry chains must stay acyclic (NODE_RETRY_CYCLE). Settable in any status — arms on the NEXT failure; a retry edit never reactivates by itself (edit task to re-arm an already-failed node). null clears (replace-on-provide)".asJson),
        "maxRounds" -> Json.obj("type" -> "integer".asJson, "description" -> "Loop round cap K (1-50, default 5): re-run the worker up to K times before the verify PASSes; reaching K without PASS terminalizes the node as failed (result states 'loop reached maxRounds'). Only meaningful with loop=true".asJson),
        "verify" -> Json.obj("type" -> "string".asJson, "description" -> "Verify-agent name (default \"general\"): the session that checks each worker output (general agent + plugins — shares the node's plugins). Must exist in the Agent Catalog. Only with loop=true".asJson),
        "verifyTask" -> Json.obj("type" -> "string".asJson, "description" -> "Verify checklist template (against the original task / acceptance baseline). Empty → engine default checklist. Only with loop=true".asJson)
      ),
      "required" -> Json.arr("nodename".asJson)
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
    val task = input("task").flatMap(_.asString)
    val description = input("description").flatMap(_.asString)
    // 裁定⑤c（20260907）：双层化长描述——可选，≤200，仅 detail 通道消费
    val descriptionLong = input("descriptionLong").flatMap(_.asString)
    val worktree = input("worktree")
    val preset = input("preset").flatMap(_.asString)
    val abandon = input("abandon").flatMap(_.asBoolean).getOrElse(false)
    // merge（merge-node 批 20260905）：create-only 标记——edit 路径不接收（对既有
    // 节点传 merge 会被静默忽略；归档节点传 merge 走 forbidden 拒绝）。
    val merge = input("merge").flatMap(_.asBoolean).getOrElse(false)
    val mergeProvided = input("merge").flatMap(_.asBoolean).isDefined
    // notifyDispatcher（dispatch-notify 批 2026-09-05）：Option 保留「显式传入」信号
    // （缺省 = 不改动——同 plugins 缺省不改动形态）。载体经 implicit 传入 createNode/
    // proceed/editNode——三者的既有调用点零改动（碰撞规避：在飞批占用了这些调用点行）。
    val notifyProvided = input("notifyDispatcher").flatMap(_.asBoolean)
    implicit val notifyFlag: NodeEditNotify = NodeEditNotify(notifyProvided.getOrElse(false), notifyProvided.isDefined)
    // loop 参数（LoopNode 批 2026-09-06）：Option 区分「未传」（编辑不改动 / 创建 None，
    // provided=false）与「传了」（loop true 启用 / false 停用）。载体经 implicit 传入
    // createNode/proceed/editNode——调用点零文本改动（与 notifyFlag 同机制）。maxRounds
    // 缺省 5（与主设计 §2.5 K=5 同参）；verify 缺省 "general"（通用 agent + plugins）。
    val loopJson = input("loop")
    val loopProvided = loopJson.exists(j => !j.isNull)
    val loopEnabled = loopJson.flatMap(_.asBoolean).getOrElse(false)
    val maxRounds = input("maxRounds").flatMap(_.asNumber).flatMap(_.toInt).getOrElse(5)
    val verify = input("verify").flatMap(_.asString).getOrElse("general")
    val verifyTask = input("verifyTask").flatMap(_.asString).getOrElse("")
    implicit val loopFlag: NodeEditLoop =
      if !loopProvided then NodeEditLoop(None, provided = false)
      else NodeEditLoop(if loopEnabled then Some(LoopConfig(maxRounds, verify, verifyTask)) else None, provided = true)
    // retry 参数（批E2 P2 failed 回跳，spec §2.3）：两形态——JSON 对象
    // {"upstream":"<id>","max":N} 或字符串 "<id>:<N>"；null=清除。replace-on-provide
    // 与 deps 同款：传了（任何形态含 null）= 整体替换/清除，未传 = 不改动。
    // max 范围 1-10 在解析单点校验（NODE_RETRY_MAX_RANGE）——创建/编辑共用。
    val retryJson = input("retry")
    val retryProvided = retryJson.isDefined
    val retryParsed: Either[String, Option[RetryPolicy]] =
      retryJson match
        case None => Right(None)
        case Some(j) if j.isNull => Right(None)
        case Some(j) if j.isObject =>
          (j.hcursor.get[String]("upstream"), j.hcursor.get[Int]("max")) match
            case (Right(up), Right(mx)) =>
              if mx < 1 || mx > 10 then Left(s"'retry.max' must be between 1 and 10 (got $mx) — the auto-retry budget per node. (NODE_RETRY_MAX_RANGE)")
              else if up.trim.isEmpty then Left("'retry.upstream' must be a non-empty node id")
              else Right(Some(RetryPolicy(up.trim, mx)))
            case _ => Left("'retry' object form requires string 'upstream' and integer 'max'")
        case Some(j) if j.isString =>
          j.asString.getOrElse("").trim.split(':').toList match
            case up :: mx :: Nil if up.trim.nonEmpty =>
              mx.trim.toIntOption match
                case Some(n) if n >= 1 && n <= 10 => Right(Some(RetryPolicy(up.trim, n)))
                case Some(n) => Left(s"'retry.max' must be between 1 and 10 (got $n) — the auto-retry budget per node. (NODE_RETRY_MAX_RANGE)")
                case None => Left(s"'retry' string form must be '<upstream-id>:<max-int>', got '${j.asString.getOrElse("")}'")
            case _ => Left(s"'retry' string form must be '<upstream-id>:<max-int>', got '${j.asString.getOrElse("")}'")
        case Some(_) => Left("'retry' must be an object {upstream, max} or a string '<upstream-id>:<max>'")
    implicit val retryFlag: NodeEditRetry = NodeEditRetry(retryParsed.getOrElse(None), retryProvided)
    val inJson = input("in")
    val depsJson = input("deps")
    val outJson = input("out")
    // 阶段 2b（§B.4 第 3 步）：plugins 参数。Option 区分「未传」（编辑不改动 /
    // 创建 Nil）与「已传」（整列表替换，replace-on-provide 与 deps 同款）；
    // 解析复用 parseIn 三形态宽容（string / array / 逗号串）。
    val pluginsJson = input("plugins")
    val pluginsProvided = pluginsJson.exists(j => !j.isNull)
    val pluginsParsed: Either[String, List[String]] =
      if !pluginsProvided then Right(Nil)
      else NodeTools.parseIn(pluginsJson).left.map(err => err.replace("'in'", "'plugins'"))
    if nodename.isEmpty then IO.pure(Left(ToolError("Missing 'nodename'")))
    // 退役参数硬闸（2026-09-05 插件架构对齐）：agent/skill/mcp 一律拒绝——schema 层
    // 已删属性（严格调用方过不了 schema），此处运行时兜底（宽容调用方拿到可行动错误，
    // 指向 plugins）。形态：NODE_AGENT_RETIRED 单一错误码三参数共用。
    else if input.contains("agent") || input.contains("skill") || input.contains("mcp") then
      IO.pure(Left(ToolError(
        "'agent'/'skill'/'mcp' node params are RETIRED and no longer accepted (2026-09-05 plugin-architecture alignment) — " +
          "every node executes the general agent; capability differentiation goes through 'plugins' (a plugin = skills + mcp.json, either " +
          "alone is valid; see the Plugin Catalog in your prompt). Existing flow-map nodes keep their old values for display only. " +
          "(NODE_AGENT_RETIRED)")))
    // worktree 类型闸：布尔显式化（String→Boolean 改造）——传字符串（旧形态）不再
    // 宽容收编，直接拒绝（旧文案教「先建 worktree 再传裸名」的工作流已被
    // 「worktree=true 即时创建」取代）。
    else if worktree.exists(w => !w.isBoolean && !w.isNull) then
      IO.pure(Left(ToolError(
        "'worktree' must be a boolean (2026-09-05 explicit-boolean rework): true = an isolated worktree is auto-created for this node " +
          "(derived from the node name, baseline = main HEAD); false/omitted = run directly in the workspace. " +
          "The old string form (pre-existing bare name) is no longer accepted. (WORKTREE_NOT_BOOLEAN)")))
    else if pluginsProvided && pluginsParsed.isLeft then
      IO.pure(Left(ToolError(pluginsParsed.swap.toOption.getOrElse("invalid plugins"))))
    // loop maxRounds 范围校验（LoopNode 批 2026-09-06）：1-50 合理区间（主设计 K=5
    // 口径，上限防御异常配置）；loop=true 时校验，非 loop 忽略。
    else if loopEnabled && (maxRounds < 1 || maxRounds > 50) then
      IO.pure(Left(ToolError(
        s"'maxRounds' must be between 1 and 50 (got $maxRounds) — the loop round cap K (loop node iteration budget). (NODE_LOOP_MAXROUNDS_RANGE)")))
    // retry 解析/范围错误前置拦截（批E2）：对象/字符串形态与 max 范围在解析单点
    // 已判，这里统一拦在进 create/edit 之前（0 spawn）。
    else if retryProvided && retryParsed.isLeft then
      IO.pure(Left(ToolError(retryParsed.swap.toOption.getOrElse("invalid retry"))))
    // description 校验（创建必写 + 编辑可 update 共用）：trim 非空 + ≤60 字符
    // （裁定⑤c 双层化：短文进默认载荷；长文走 descriptionLong ≤200）。
    else if description.exists(d => d.trim.isEmpty) then
      IO.pure(Left(ToolError("'description' must be a non-empty one-line summary of the node's purpose (NODE_DESCRIPTION_REQUIRED)")))
    else if description.exists(_.trim.length > 60) then
      IO.pure(Left(ToolError(s"'description' must be ≤60 characters (got ${description.map(_.trim.length).getOrElse(0)}) — keep it to one line; longer context goes in the task or descriptionLong (NODE_DESCRIPTION_TOO_LONG)")))
    else if descriptionLong.exists(d => d.trim.isEmpty) then
      IO.pure(Left(ToolError("'descriptionLong' must be non-empty when provided (NODE_DESCRIPTION_LONG_REQUIRED)")))
    else if descriptionLong.exists(_.trim.length > 200) then
      IO.pure(Left(ToolError(s"'descriptionLong' must be ≤200 characters (got ${descriptionLong.map(_.trim.length).getOrElse(0)}) (NODE_DESCRIPTION_LONG_TOO_LONG)")))
    else
      val plugins = pluginsParsed.getOrElse(Nil)
      // flag off（§G.2 回滚语义）：NodeEdit 忽略 plugins 参数——不校验不存储
      val pluginsEffective: IO[Option[List[String]]] =
        if !pluginsProvided then IO.pure(None)
        else nebflow.core.plugin.PluginsConfig.enabled.map(enabled => if enabled then Some(plugins) else None)

      pluginsEffective.flatMap { pluginsOpt =>
        val pluginsForCall = pluginsOpt.getOrElse(Nil)
        // 插件存在性 + 信任门白名单校验（0 spawn 快速失败，§B.4 第 3 步）；
        // flag off → pluginsOpt=None → 跳过校验（参数被忽略）。
        val pluginValidation: IO[Either[String, Unit]] =
          pluginsOpt match
            case None => IO.pure(Right(()))
            case Some(names) =>
              if names.isEmpty then IO.pure(Right(()))
              else
                names.traverse(nebflow.core.plugin.PluginRegistry.resolve).map { results =>
                  results.find(_.isLeft) match
                    case Some(Left(err)) => Left(err)
                    case _ => Right(())
                }
        pluginValidation.flatMap {
          case Left(err) => IO.pure(Left(ToolError(err)))
          case Right(_) =>
            NodeTools.resolveProject(project, ctx).flatMap {
              case Left(err) => IO.pure(Left(ToolError(err)))
              case Right(rt) =>
                rt.store.snapshot.flatMap { s =>
                  s.nodes.values.find(_.name == nodename) match
                    case Some(existing) => editNode(rt, existing, task, description, descriptionLong, abandon, worktree.flatMap(_.asBoolean), preset, pluginsOpt, inJson, depsJson, outJson, ctx)
                    case None =>
                      // 归档节点编辑兜底（fix b「已存在边+归档上游不补投递」修复 20260903）：
                      // 活动区按名未命中 → 归档区按名兜底。归档节点只支持 out 改接（悬空
                      // 完成结果的补投递——「悬空节点后来被接线」的归档变体：editNode 的
                      // completed+newOut 投递分支沿 deliverOutTo 补投，barrier 随之结算）；
                      // 其余编辑域（task/description/in/deps/配置/abandon）拒绝——归档是显示
                      // 过期（TTL 满、结果保留可投递），不是重激活通道（重激活只属于
                      // Blocked 态）。修复前：归档名落 createNode → 同名重复节点（拓扑
                      // 污染）或静默失败。
                      rt.store.archiveSnapshot.flatMap { arch =>
                        arch.nodes.values.find(_.name == nodename) match
                          case Some(archived) =>
                            val forbidden =
                              task.isDefined || description.isDefined || descriptionLong.isDefined || worktree.isDefined ||
                                preset.isDefined ||
                                abandon || inJson.isDefined || depsJson.isDefined ||
                                pluginsProvided ||
                                mergeProvided || notifyProvided.isDefined || retryProvided
                            if abandon then
                              IO.pure(Left(ToolError(s"Node '$nodename' is archived (display TTL expired) — abandon is not applicable; it already ages out of views on its own.")))
                            else if mergeProvided then
                              IO.pure(Left(ToolError(
                                s"Node '$nodename' is archived — 'merge' is a create-only flag and cannot be set on an archived node.")))
                            else if !outJson.isDefined then
                              IO.pure(Left(ToolError(
                                s"Node '$nodename' is archived (display TTL expired, result retained). Only 'out' rewiring is supported for archived nodes (result re-delivery).")))
                            else if forbidden then
                              IO.pure(Left(ToolError(
                                s"Node '$nodename' is archived — only 'out' rewiring is supported (result re-delivery); task/description/in/deps/config edits are not.")))
                            else editNode(rt, archived, task, description, descriptionLong, abandon, worktree.flatMap(_.asBoolean), preset, pluginsOpt, inJson, depsJson, outJson, ctx)
                          case None =>
                            if abandon then IO.pure(Left(ToolError(s"Node '$nodename' not found — abandon requires an existing node")))
                            else createNode(rt, nodename, task, description, descriptionLong, worktree.flatMap(_.asBoolean), preset, pluginsForCall, inJson, depsJson, outJson, merge)
                      }
                  }
              }
          }
      }

  // ── 新建 ─────────────────────────────────────────────

  /** description 校验单点（create 必写；edit 传了才校验）。裁定⑤c（20260907
    * 双层化）：短文 ≤60 进默认载荷；长文走 validateDescriptionLong（≤200，
    * detail 通道）。存量 ≤200 长描述不回溯。 */
  private def validateDescription(description: Option[String], creating: Boolean): Option[ToolError] =
    description match
      case None if creating =>
        Some(ToolError(
          "New node requires 'description' — a one-line summary (≤60 chars) of what this node does. " +
            "It is the always-loaded metadata shown on the Flow Map card (the node's result is read on demand). (NODE_DESCRIPTION_REQUIRED)"))
      case Some(d) if d.trim.isEmpty =>
        Some(ToolError("'description' must be non-empty (trim) — one line, ≤60 chars (NODE_DESCRIPTION_REQUIRED)"))
      case Some(d) if d.trim.length > 60 =>
        Some(ToolError(s"'description' must be ≤60 characters (got ${d.trim.length}) — one line; longer context goes in the task or descriptionLong (NODE_DESCRIPTION_TOO_LONG)"))
      case _ => None

  /** descriptionLong 校验单点（裁定⑤c，可选参数：传了才校验；≤200 字符）。 */
  private def validateDescriptionLong(descriptionLong: Option[String]): Option[ToolError] =
    descriptionLong match
      case Some(d) if d.trim.isEmpty =>
        Some(ToolError("'descriptionLong' must be non-empty (trim) — ≤200 chars (NODE_DESCRIPTION_LONG_REQUIRED)"))
      case Some(d) if d.trim.length > 200 =>
        Some(ToolError(s"'descriptionLong' must be ≤200 characters (got ${d.trim.length}) (NODE_DESCRIPTION_LONG_TOO_LONG)"))
      case _ => None

  /** worktree 派生规则（2026-09-05 显式布尔改造，规则写死）：
    * 目录名 = 节点名 sanitize——空白与 git-ref 非法字符（~^:?*[\\ 及控制符）→ '-'、
    * 连续 '-' 折叠、首尾 '-.' 剥除、截 40 字符、空则 "node"（**保留 CJK/Unicode
    * 字母数字**——生产节点名以中文为主，全部归一成 "node" 会让派生名失去区分度；
    * git 分支名与 macOS/Linux 文件名均合法接受 Unicode）。冲突追加 -2..-99；
    * 分支同名；基线 = main HEAD（无 main → 当前 HEAD）。
    * 创建时机选型（裁决 a「NodeEdit 即时创建」）：fail-fast——分发器组图当下拿到
    * 可行动错误，零 spawn 零 token 浪费；spawn 时懒创建（方案 b）会把失败推迟到
    * 执行链中段，形成 failed 节点 + 重入轮次。git 不可用/非 git 工作区 → 拒建节点。 */
  private def createWorktreeFor(ws: os.Path, nodename: String): Either[String, String] =
    def git(args: String*): Either[String, String] =
      val res = os.proc(Seq("git", "-C", ws.toString) ++ args).call(cwd = ws, check = false, mergeErrIntoOut = true)
      if res.exitCode != 0 then Left(res.out.trim().take(300)) else Right(res.out.trim())
    val sanitized =
      nodename.trim.replaceAll("[\\s~^:?*\\[\\\\]+", "-").replaceAll("-{2,}", "-").stripPrefix("-").stripSuffix("-").stripPrefix(".").take(40).stripSuffix("-").stripSuffix(".lock").stripSuffix(".")
    val base = if sanitized.isEmpty then "node" else sanitized
    val wtRoot = ws / ".nebflow" / "worktrees"
    val candidates = (1 to 99).map(i => if i == 1 then base else s"$base-$i")
    candidates.find { n => !os.exists(wtRoot / n) && !os.exists(ws / ".nebflow" / n) } match
      case None =>
        Left(s"Cannot derive a free worktree name for node '$nodename' (tried '$base', '$base-2'…'$base-99') — pass a shorter/unique nodename")
      case Some(name) =>
        val path = wtRoot / name
        os.makeDir.all(wtRoot)
        val baseRef = if git("rev-parse", "--verify", "--quiet", "main").isRight then "main" else "HEAD"
        git("worktree", "add", "-b", name, path.toString, baseRef).map(_ => name)

  private def createNode(
    rt: ProjectRuntime,
    nodename: String,
    task: Option[String],
    description: Option[String],
    descriptionLong: Option[String],
    worktree: Option[Boolean],
    preset: Option[String],
    plugins: List[String],
    inJson: Option[Json],
    depsJson: Option[Json],
    outJson: Option[Json],
    merge: Boolean = false
  )(implicit notify: NodeEditNotify, loopFlag: NodeEditLoop, retryFlag: NodeEditRetry): IO[Either[ToolError, String]] =
    // 执行统一 general（2026-09-05 插件架构对齐）：新建节点不再接受 agent 参数，
    // 专业能力由 plugins 差异化；NodeDef.agent 字段保留（存量兼容读 + spawn 读取）。
    val agentName = "general"
    val inIds = NodeTools.parseIn(inJson)
    // deps 复用 parseIn 三形态宽容解析（deps 设计 §1.2：不新写解析器）；报错文案
    // 把参数名替换成 deps，避免误导（解析器文案写死 'in'）。
    val depsIds = NodeTools.parseIn(depsJson).left.map(err => err.replace("'in'", "'deps'"))
    val outEither = NodeTools.parseOut(outJson)
    (validateDescription(description, creating = true), validateDescriptionLong(descriptionLong)) match
      case (Some(err), _) => IO.pure(Left(err))
      case (_, Some(err)) => IO.pure(Left(err))
      case (None, None) =>
        (inIds, depsIds, outEither) match
          case (Left(err), _, _) => IO.pure(Left(ToolError(err)))
          case (_, Left(err), _) => IO.pure(Left(ToolError(err)))
          case (_, _, Left(err)) => IO.pure(Left(ToolError(err)))
          case (Right(ins), Right(deps), Right(out)) =>
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
            // merge 校验①（merge-node 批 20260905 §权限选型③；worktree 布尔化后
            // contains(true) 语义）：合并节点不配 worktree——落地收口在 workspace 根仓
            // 执行，沙箱根必须 = workspace（.git 在根内可写）；配 worktree=true 则
            // 沙箱根 = worktree 目录，主仓 .git 在根外 → git 变更 EPERM。
            else if merge && worktree.contains(true) then
              IO.pure(Left(ToolError(
                "merge=true (batch landing sink) must NOT carry 'worktree' — a merge node lands on the workspace " +
                  "root repo; its sandbox root must be the workspace itself so .git is writable. Drop 'worktree'.")))
            // merge 校验②（mount-enforce 批 20260905，作者裁定「节点不允许空挂载」）：
            // merge=true 必须 ≥1 上游（in 非空）——合并节点靠上游 completed 投递清
            // in-barrier 触发（deliverOut→startNode），零上游=可触发点永不到达=空挂
            // pending 永不生效（实证 n-371cf932：merge=true、in=[] 悬挂 10+min，靠
            // 人工 abandon+重建救援）。合法创建顺序：先建上游节点，再建合并节点并
            // in=<上游 id>（in 声明自动改接各上游 out → 本节点）。
            else if merge && ins.isEmpty then
              IO.pure(Left(ToolError(
                "merge=true (batch landing sink) requires at least one upstream: declare 'in' with existing upstream node id(s). " +
                  "A merge node triggers only when its in-barrier clears via upstream completed delivery — with zero upstreams " +
                  "it can never fire (empty mount = pending forever). Create the upstream node(s) first, then create this merge " +
                  "node with in=<upstream-id(s)> (the in declaration rewires each upstream's out to this node). " +
                  "(NODE_MERGE_REQUIRES_UPSTREAM)")))
            // ⑥ in 上限闸（U2/P1 · 2026-09-11 作者拍板）：合并节点 in ≤4，超限拆多个合并
            // 节点。此前纯纪律、引擎查无校验 ⇒ 一条超限 merge 会把 >4 条轨道压成单点
            // （落地冲突面与失败面同步放大）。边界 =4 合法 / ≥5 拒，码 NODE_MERGE_IN_CAP。
            else if merge && ins.distinct.size > NodeTools.MergeInCap then
              IO.pure(Left(ToolError(
                s"merge=true (batch landing sink) accepts at most ${NodeTools.MergeInCap} upstreams in one ledger — " +
                  s"this create declares ${ins.distinct.size} distinct upstream(s). Split the batch: create one merge " +
                  s"node per ≤${NodeTools.MergeInCap} upstream group, then wire the groups' merge nodes into a " +
                  "follow-up merge/report node. (NODE_MERGE_IN_CAP)")))
            else
              // 1. agent 存在性（新建恒 "general"——库缺 general = 环境残缺，fail-fast）
              EntityLoader.loadAgent(agentName).flatMap {
                case None => IO.pure(Left(ToolError(s"Agent 'general' not found in global library — every node executes the general agent (plugin-architecture alignment); install/restore it first")))
                case Some(_) =>
                  // 2. worktree 布尔派生（2026-09-05 显式化）：true → 即时创建（裁决 a，
                  //    fail-fast；失败拒绝建节点）；false/缺省 → workspace 直跑（现状）。
                  val viaWorktree: IO[Either[ToolError, String]] =
                    worktree match
                      case Some(true) =>
                        val ws = os.Path(rt.project.workspace)
                        // 必须是 git 仓**根**：--show-toplevel 输出与 workspace 一致。
                        // 只查 rev-parse 退出码会把「嵌在别的 git 仓子目录里的 workspace」
                        // 误判为 git 仓（worktree add 会挂到外层仓上）。
                        val repoRoot = os.proc("git", "-C", ws.toString, "rev-parse", "--show-toplevel")
                          .call(cwd = ws, check = false, mergeErrIntoOut = true)
                        val isGitRoot = repoRoot.exitCode == 0 && repoRoot.out.trim() == ws.toString
                        if !isGitRoot then
                          IO.pure(Left(ToolError(
                            s"worktree=true requires the project workspace to be a git repository root (${ws} is not) — " +
                              "run in the workspace instead (omit worktree), or git init the workspace first.")))
                        else
                          IO.blocking(createWorktreeFor(ws, nodename)).flatMap {
                            case Left(err) => IO.pure(Left(ToolError(
                              s"worktree=true auto-creation failed for node '$nodename' — node NOT created (fail-fast). git said: $err")))
                            case Right(bare) => proceed(rt, nodename, agentName, task, description, descriptionLong, Some(bare), preset, plugins, ins, deps, out, merge)
                          }
                      case _ => proceed(rt, nodename, agentName, task, description, descriptionLong, None, preset, plugins, ins, deps, out, merge)
                  // loop verify agent 存在性（§2.6 校验②，0 spawn 拦截）：loop=true 时校验
                  // verify agent 可装载——缺失即拒（与 worker agent 同纪律，fail-fast）。
                  loopFlag.config match
                    case Some(lc) =>
                      EntityLoader.loadAgent(lc.verify).flatMap {
                        case None => IO.pure(Left(ToolError(
                          s"Verify agent '${lc.verify}' not found in global library — loop node requires a valid verify agent (worker '${agentName}', verify '${lc.verify}'). (NODE_LOOP_VERIFY_AGENT)")))
                        case Some(_) => viaWorktree
                      }
                    case None => viaWorktree
              }

  private def proceed(
    rt: ProjectRuntime,
    nodename: String,
    agentName: String,
    task: Option[String],
    description: Option[String],
    descriptionLong: Option[String],
    worktree: Option[String],
    preset: Option[String],
    plugins: List[String],
    ins: List[String],
    deps: List[String],
    out: List[OutEdge],
    merge: Boolean = false
  )(implicit notify: NodeEditNotify, loopFlag: NodeEditLoop, retryFlag: NodeEditRetry): IO[Either[ToolError, String]] =
    val nodeId = s"n-${java.util.UUID.randomUUID().toString.take(8)}"
    val outTargets = out.map(_.to).filterNot(_ == OutEdge.NebulaTarget).distinct
    for
      // 引用存在性 + 环检测（in 上游 → 本节点；deps 上游 → 本节点；本节点 → out 目标）。
      // wouldCreateCycle 已含 deps 反向边（deps 设计 §1.2 校验二：混合图单点覆盖）——
      // create 场景新节点无出边，环检天然为 false，保留调用与 in 对称（防御未来变化）。
      inOk <- ins.traverse(id => NodeTools.ensureNodeExists(rt, id))
      depsOk <- deps.traverse(id => NodeTools.ensureNodeExists(rt, id))
      cycleIn <- ins.traverse(id => NodeTools.wouldCreateCycle(rt, fromId = id, to = nodeId))
      cycleDeps <- deps.traverse(id => NodeTools.wouldCreateCycle(rt, fromId = id, to = nodeId))
      // out 目标解析（20260909 in 丢失事故修复面）：接受节点 id 或节点名，校验/环检/
      // 镜像记账全部在解析后的 id 上做——名字形态不再被 id-only 校验误拒，也不再绕过
      // 环检测（原实现按原始串查 Map，名字串查不到 → 环检测静默放行）。
      outResolved <- outTargets.traverse(t => NodeTools.resolveOutTarget(rt, t).map(opt => t -> opt))
      outOk = outResolved.map { case (t, opt) =>
        opt.toRight(s"Referenced node '$t' not found in project '${rt.project.name}' (not a node id, nor any node's name)")
      }
      cycleOut <- outResolved.traverse { case (_, opt) =>
        opt match
          case Some(tid) => NodeTools.wouldCreateCycle(rt, fromId = nodeId, to = tid)
          case None      => IO.pure(false) // 悬空已被 outOk 拒，环检无意义
      }
      // P1 校验层①（spec §2.2）：指向 merge 的 on-failed 边 → 硬拒（NODE_MERGE_PASS_ONLY）
      mergeGate <- NodeTools.ensureMergePassOnly(rt, out)
      // P1 校验层②（spec §2.2）：下游持 in 边而上游已 failed 无 on-failed 边且非 merge →
      // WARNING（不阻断，人工兜底合法）——补投链/死锁可见性由既有 mount-stalled 承载
      stallWarn <- NodeTools.stalledInWarning(rt, nodeId, nodename, ins, merge)
      // 批E2 retry 风暴防护（spec §2.3，0 spawn）：邻居限定 + retry 环。值域 =
      // 本次声明的 in ∪ deps（节点尚未落库，in 邻接关系沿声明）。
      retryGate <- retryFlag.policy match
        case None => IO.pure(None)
        case Some(p) => NodeTools.retryGuard(rt, nodeId, nodename, p, ins ++ deps)
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
        else if outOk.exists(_.isLeft) then
          IO.pure(Left(ToolError(outOk.collectFirst { case Left(e) => e }.getOrElse("invalid out"))))
        else if cycleOut.exists(identity) then
          IO.pure(Left(ToolError(s"Cycle detected: out → ${outTargets.mkString(", ")} would create a loop — DAG must stay acyclic")))
        else if mergeGate.isDefined then
          IO.pure(Left(ToolError(mergeGate.get)))
        else if retryGate.isDefined then
          IO.pure(Left(ToolError(retryGate.get)))
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
            worktree = worktree,
            preset = preset,
            task = task,
            description = description.map(_.trim),
            descriptionLong = descriptionLong.map(_.trim),
            in = Nil,
            deps = deps,
            merge = merge,
            // LoopNode 配置（LoopNode 批 2026-09-06）：loop=true 时 NodeDef.loop=Some(LoopConfig)，
            // 否则 None（普通节点）。loop 不构成「可立即运行」的输入语义——仍须 task/in 承载
            // 输入（与 deps 同款：完成信号/迭代语义不是输入），入口/barrier 判定不变。
            loop = loopFlag.config,
            // P2 failed 回跳 retry 策略（spec §2.3）：未传 None（旧行为）；传了 = 校验
            // 通过的 RetryPolicy（下游单侧持有）。
            retry = retryFlag.policy,
            // dispatch-notify 回流标志（创建期按需开启；缺省 false=分发器新建节点
            // 不继承——收敛保证见 DispatchNotify）
            notifyDispatcher = notify.flag,
            out = OutEdge.canonical(out),
            status = if task.isDefined && ins.isEmpty then NodeLifecycle.Pending else NodeLifecycle.Wiring,
            createdAt = now,
            plugins = plugins
          )
          // 单事务：加节点（deps 单侧持有，无上游侧镜像边要写）+ in 边（上游 out 追加 → 本节点）
          // + out 边（每个非 Nebula 目标 in 追加本节点）。P1 多边：in 声明为上游 out **追加**
          // 指向本节点的缺省 pass 边（不覆盖既有门控，扇出拓扑零扰动）。
          val mutateIO = rt.store.mutate { s =>
            val withNode = s.copy(nodes = s.nodes + (nodeId -> node))
            val insNodes = ins.foldLeft(withNode.nodes)((acc, upId) => NodeTools.appendEdgeTo(acc, upId, nodeId))
            val withIns = withNode.copy(nodes = insNodes)
            val withInList = ins.foldLeft(withIns) { (acc, upId) =>
              acc.nodes.get(nodeId) match
                case Some(n) => acc.copy(nodes = acc.nodes.updated(nodeId, n.copy(in = (n.in :+ upId).distinct)))
                case None => acc
            }
            val outNodes = out.foldLeft(withInList.nodes)((acc, e) =>
              if e.to != OutEdge.NebulaTarget then
                // in 镜像按解析后 id 记账（同 setOut——20260909 事故修复）：目标串可能是名字
                OutEdge.resolveTargetId(acc, e.to) match
                  case Some(tid) =>
                    acc.get(tid) match
                      case Some(tn) => acc.updated(tid, tn.copy(in = (tn.in :+ nodeId).distinct))
                      case None => acc
                  case None => acc
              else acc)
            withInList.copy(nodes = outNodes.updated(nodeId, outNodes(nodeId).copy(out = OutEdge.canonical(out))))
          }
          // create 回执一致性断言（20260909 in 丢失事故护栏①）：回执返回前写后读，
          // 断言 in 已随节点同事务落定。现实现里 in 追加与节点插入在同一 Ref 事务
          // （不可分离）；本断言是防回归哨兵——in 追加若被挪出 mutate（或未来重构
          // 引入后置写）即在此显式红，杜绝「回执成功但 in 空 → barrier 空真」的静默形态。
          val createIO: IO[Either[ToolError, String]] =
            mutateIO.flatMap { s =>
              val created = s.nodes(nodeId)
              val missingIn = ins.filterNot(created.in.contains)
              if missingIn.nonEmpty then
                val msg =
                  s"engine consistency tripwire: node '$nodename' ($nodeId) was created but in edge(s) [${missingIn.mkString(", ")}] are missing from the persisted state " +
                    s"(in=[${created.in.mkString(", ")}]) — its in-barrier would silently never fire. (ENGINE_IN_RECEIPT_MISMATCH)"
                IO(logger.errorSync(s"[node.tools] $msg")).as(Left(ToolError(msg)))
              else
                (
                // 新节点事件（NodeList 同构 payload，in/out 以 store 最终态为准）
                rt.engine.emitCreated(s.nodes(nodeId)) *>
                // wiring 变更事件（barrier 合并接线 §2.3）：上游 out 追加指向本节点 +
                // out 目标 in 追加——此前只有 nodeCreated，改写的节点无事件（缺失补齐）
                NodeTools.emitWiringUpdates(rt, ins ++ out.map(_.to).filterNot(_ == OutEdge.NebulaTarget)) *>
                // D1 修复（验收④b，@a4b5d184 spec 实证）：in 引用已完成上游（活动区
                // 或归档区——findNode 兜底）→ 立即投递其结果，等同 §2.3「悬空节点
                // out 接入下游 = 已完成节点改接」路径。归档节点活动区已消失，
                // completeNode 的 deliverOut 不会再触发，唯一投递入口就是这里。
                // 运行中上游不投递（barrier 等待语义——completeNode 时 deliverOut 自然投）。
                // 后台化（收口③）：deliverOutTo 内 startNode 同步等下游节点终态，
                // 直接调用会阻塞 NodeEdit 工具 fiber（见 runDetached 注释）。
                // merge 分流（mount-enforce 批）：合并节点创建时 in 引用已 failed 的
                // 上游（创建顺序翻转后的合法形态）——failed 错误文本≠产物，不作输入
                // 投递，走 MergeNodePolicy 转 blocked 可见终态不悬挂（与 deliverFailed
                // 运行期路径同语义单点 mergeBlockedByUpstreamFailure）；非 merge 下游
                // 的 failed 错误投递语义保持不变。
                NodeTools.runDetached(rt, s"deliver retained upstream results -> $nodeId")(
                  ins.traverse_ { upId =>
                    rt.store.findNode(upId).flatMap {
                      // R1（blocked 反馈重入设计 §6）：blocked 是终态且 result=反馈渲染串，
                      // 但反馈串不是可投结果——D1 显式排除 blocked（failed 错误投递语义保持不变）
                      case Some(up) if up.result.isDefined && up.status != NodeLifecycle.Blocked && NodeLifecycle.Terminal.contains(up.status) =>
                        if s.nodes(nodeId).merge && up.status == NodeLifecycle.Failed then
                          rt.engine.mergeBlockedByUpstreamFailure(s.nodes(nodeId), up, up.result.get)
                        else rt.engine.deliverOutTo(up, nodeId, up.result.get)
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
                // 合并节点例外（merge-node 批 20260905）：merge=true 时 task 只是
                // 落地指令集，**不构成可立即运行的入口语义**——落地收口必须等齐
                // 全部上游（in barrier 归零后由上游投递链经 startNode 启动）。
                // 否则合并节点会在零上游时开跑（S1 E2E 实测捕获）。
                (if task.isDefined && ins.isEmpty && !merge then
                   NodeTools.runDetached(rt, s"start entry node $nodeId")(rt.engine.startNode(nodeId))
                 else IO.unit)
                ).as(Right(
                  s"Node '$nodename' ($nodeId) created in project '${rt.project.name}'" +
                    (if task.isDefined && ins.isEmpty && !merge then " — entry node started running." else "") +
                    (if deps.nonEmpty then s" deps ← ${deps.mkString(",")}" else "") +
                    (if out.nonEmpty then s" out → ${out.map(_.to).mkString(", ")}" else "") +
                    (retryFlag.policy match
                      case Some(p) => s" retry ← ${p.upstream}:max=${p.max}"
                      case None => "") +
                    (if stallWarn.nonEmpty then "\n" + stallWarn.mkString("\n") else "")
                ))
            }
          createIO
    yield result
    end for

  // ── 编辑 ─────────────────────────────────────────────

  /** abandon 动作（blocked 反馈重入设计 §7.7）：终态节点 → status=cancelled + 审计
    * 事件（无显示 TTL——2026-09-07 裁定：failed/cancelled 永不自动归档，主图保留
    * 待上层处置）。分发器处置 blocked 节点的「放弃」载体；NodeCancel 语义不动（仅 running）。
    * R2 纪律：mutate 内现读 fresh，fresh 已非终态（并发重激活）→ 拒写。
    *
    * 接受域扩展（裁定①待实施语义，deps 设计 §1.6）：终态 ∪ wiring/pending——拓扑
    * 清场时退役节点常是活的 wiring/pending（「悬空活节点」无处置出口），abandon 是
    * 其唯一出口（NodeCancel 仅 running）。wiring/pending 无在飞会话，无中断副作用；
    * 被退役节点的上游其后完成时 deliverOut → startNode 幂等跳过（cancelled ∈ Terminal）。
    *
    * 死会话 running 收殓（清场 c-②，20260903 03:04 清场误杀事故复盘）：running 且
    * 无在飞执行 fiber（会话死于传输中断/实例重启泄漏）→ 可收殓（cancelled，无 TTL，
    * 留主图——2026-09-07 裁定）。
    * 误杀防护（硬约束）：活 running（isRunning=true = 有在飞 fiber，取消信号可达）
    * 绝对拒绝，只能走 NodeCancel。无复活竞态：running 节点不会被 startNode 二次
    * spawn（入口状态幂等跳过），死会话不可能复活 → 预检后无需事务内复查 IO 信号。 */
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
                // 2026-09-07 作者裁定：cancelled 无 TTL 强制清——abandon 是上层
                // 裁决动作，节点留主图（不再 24h 后静默消失），由后续拓扑清理处置。
                ttlExpireAt = None)))
            case _ => st // 状态已变（并发重激活/移除）→ 拒写
        }
        _ <- s.nodes.get(node.id) match
          case Some(c) if c.status == NodeLifecycle.Cancelled =>
            rt.engine.emitUpdated(c) *>
              FlowMapEventLog.append(rt.project.workspace, rt.project.name, node.id, "abandoned",
                s"node abandoned via NodeEdit (${node.status}${if allowDeadRunning && node.status == NodeLifecycle.Running then "/dead-session" else ""} → cancelled, retained on map)") *>
              // P2 G11（spec §3.4）：cancelled 级联清理该会话 pending asks（与
              // cancelNode/reap 同款单点——问出问题的节点被放弃，卡片必须关闭）。
              rt.engine.cleanupPendingAsks(c.sessionRef)
          case _ => IO.unit
      yield Right(s"Node '${node.name}' abandoned — cancelled (retained on map, no TTL; result retained)")

    if node.status == NodeLifecycle.Running then
      rt.engine.isRunning(node.id).flatMap {
        case true =>
          IO.pure(Left(ToolError(
            s"Node '${node.name}' is running with a live session — abandon refused (mis-kill protection: abandon accepts terminal/wiring/pending and dead-session running only). Use NodeCancel for running nodes.")))
        case false =>
          doAbandon(allowDeadRunning = true)
      }
    else doAbandon(allowDeadRunning = false)

  private def editNode(
    rt: ProjectRuntime,
    node: NodeDef,
    task: Option[String],
    description: Option[String],
    descriptionLong: Option[String],
    abandon: Boolean,
    worktree: Option[Boolean],
    preset: Option[String],
    pluginsOpt: Option[List[String]],
    inJson: Option[Json],
    depsJson: Option[Json],
    outJson: Option[Json],
    ctx: ToolContext
  )(implicit notify: NodeEditNotify, loopFlag: NodeEditLoop, retryFlag: NodeEditRetry): IO[Either[ToolError, String]] =
    // ── 动作分支 ──
    if abandon then abandonNode(rt, node)
    // worktree 创建期绑定闸（2026-09-05 显式布尔改造）：worktree 是 create-time
    // 参数——编辑路径出现即拒（旧实现对编辑路径静默忽略，布尔语义下静默忽略
    // = 「我说要建 worktree 你却没建」的陷阱；显式拒绝 + 指引重建）。
    else if worktree.isDefined then
      IO.pure(Left(ToolError(
        s"'worktree' is a create-time parameter — node '${node.name}' already exists ${if node.worktree.isDefined then s"with worktree '${node.worktree.get}'" else "in the workspace"}. " +
          "It cannot be added/changed on an existing node; recreate the node if the binding is wrong. (WORKTREE_CREATE_ONLY)")))
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
        case Right(newOut) if outJson.isDefined && newOut.isEmpty && node.out.nonEmpty =>
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
              // P1: out 变更判定与目标集（canonical 比较消歧「(pass)B」与既有单边等价）
              val outProvided = outJson.isDefined
              val newTargets = newOut.map(_.to).filterNot(_ == OutEdge.NebulaTarget).distinct
              val oldTargets = node.out.map(_.to).filterNot(_ == OutEdge.NebulaTarget).distinct
              val outChanged = outProvided && OutEdge.canonical(newOut) != OutEdge.canonical(node.out)
              // out 目标解析 + 存在性闸（20260909 in 丢失事故修复面）：新目标接受节点 id
              // 或节点名，统一解析为 id 后再过状态守卫/环检测/merge 门控——原实现按原始
              // 串查 Map，名字形态「查无此点」被状态守卫与环检测静默放行，坏边由此进入
              // 拓扑。悬空目标现在显式拒绝（可行动错误，不再静默半接线）。
              val resolvedIds: IO[Either[String, List[String]]] =
                if outProvided && newTargets.nonEmpty then
                  newTargets.traverse(t => NodeTools.resolveOutTarget(rt, t).map(opt => t -> opt)).map { pairs =>
                    val missing = pairs.collect { case (t, None) => t }
                    if missing.nonEmpty then
                      Left(s"out target '${missing.head}' not found in project '${rt.project.name}' — not a node id, nor any node's name. Wire an existing node or \"Nebula\".")
                    else Right(pairs.collect { case (_, Some(id)) => id }.distinct)
                  }
                else IO.pure(Right(Nil))
              // D2 修复（验收②c，@a4b5d184 spec 实证）：旧目标已消费 → 拒绝改接。
              // 完成节点改投新目标时，若被移除的旧目标已启动/已完成（结果已投递、输入已消费），
              // 改投会造成语义漂移 + 重复投递风险。保留「旧目标未启动（Wiring/Pending）→
              // 原子改投」分支（§2.3 场景表第 3 行，②b PASS 依赖）；断开（out=null）已由
              // 上方 EMPTY_NODE_CONNECTION 拒绝、不投新目标无重复投递。**P1 多边改接只对
              // 被移除目标检查**（扇出改接可保留部分目标，保留者不拦）。
              val removedTargets = if outProvided then oldTargets.diff(newTargets) else Nil
              val consumedGuard: IO[Either[String, Unit]] =
                if node.status != NodeLifecycle.Completed then IO.pure(Right(()))
                else removedTargets.traverse(oldT => NodeTools.resolveOutTarget(rt, oldT).flatMap {
                  case Some(tid) => rt.store.findNode(tid).map {
                    case Some(x) if x.status != NodeLifecycle.Wiring && x.status != NodeLifecycle.Pending =>
                      Left(
                        s"Node '${node.name}' result already delivered to '${x.name}' (status=${x.status}) — input consumed. " +
                          s"NodeCancel it first, then rewire, then recreate the old target node."
                      )
                    case _ => Right(())
                  }
                  case None => IO.pure(Right(())) // 悬空旧目标（手改数据防御）：保守不拦
                }).map(_.collectFirst { case Left(e) => e }.toLeft(()))
              // 守卫：新目标已运行 → 拒绝（§2.2 状态守卫，对所有声明的 out 目标——含未变者，
              // 与旧单值行为一致）+ P1 校验层①（NODE_MERGE_PASS_ONLY：指向 merge 的
              // on-failed 边硬拒）+ 旧目标已消费 → 拒绝（§2.3）。全部按解析后 id 判定。
              val guard: IO[Either[String, Unit]] =
                resolvedIds.flatMap {
                  case Left(err) => IO.pure(Left(err))
                  case Right(ids) =>
                    val statusOk: IO[Either[String, Unit]] =
                      if outProvided && ids.nonEmpty then
                        ids.traverse(t => NodeTools.ensureTargetNotRunning(rt, t)).flatMap { rs =>
                          rs.collectFirst { case Left(e) => e } match
                            case Some(e) => IO.pure(Left(e))
                            case None =>
                              NodeTools.ensureMergePassOnly(rt, newOut).flatMap {
                                case Some(gate) => IO.pure(Left(gate): Either[String, Unit])
                                case None       => consumedGuard
                              }
                        }
                      else consumedGuard
                    statusOk
                }
              guard.flatMap {
                case Left(err) => IO.pure(Left(ToolError(err)))
                case Right(_) =>
                  // 环检测（新 out：每个非 Nebula 目标，按解析后 id）
                  val cycle: IO[List[Boolean]] = resolvedIds.flatMap {
                    case Left(_)  => IO.pure(Nil) // guard 已拒，不可达
                    case Right(ids) => ids.traverse(t => NodeTools.wouldCreateCycle(rt, node.id, t))
                  }
                  cycle.flatMap { isCycles =>
                    val cycleTarget = newTargets.zip(isCycles).collectFirst { case (t, true) => t }
                    if cycleTarget.isDefined then
                      IO.pure(Left(ToolError(s"Cycle detected: out → ${cycleTarget.get} would create a loop — DAG must stay acyclic")))
                    else
                      // out 变更（原子：被移除目标 in 移除 + 新目标 in 追加）。outJson 未出现
                      // = 不改动（parseOut 注释「not passed = no change」的落地）。
                      val setOutIO =
                        if outProvided && outChanged then NodeTools.setOut(rt, node.id, newOut)
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
                          // ⑥⑧ 合并节点闸门的触发面（本批 U2/P1）：只有**真的新加上游**
                          // 才算「加 in」——重复回显既有 in（幂等重发）不是加，不触发任何
                          // 闸门（防「同参数重编辑」被误拒的回归）。
                          val genuinelyNewIn = adds.distinct.filterNot(node.in.contains)
                          val finalDeps = if depsProvided then newDeps else node.deps
                          val finalOut = if outJson.isDefined then newOut else node.out
                          // notifyDispatcher 校验（dispatch-notify 批）：设置/撤销域为
                          // wiring/pending/running（完成时行为开关，不属输入冻结域）；
                          // 终态拒（blocked 出口=重激活）。
                          val notifyStatusOk = !notify.provided ||
                            (node.status == NodeLifecycle.Wiring || node.status == NodeLifecycle.Pending || node.status == NodeLifecycle.Running)
                          // loop 校验（LoopNode 批 2026-09-06）：设置/撤销域同 hold——enabled
                          // 开关是行为开关（loop 迭代仅在 running 期驱动、PASS 完成时经
                          // completeNode 走 hold/merge/投递），wiring/pending/running 可设；
                          // 终态/held 拒（held 出口=release，blocked 出口=重激活）。blocked
                          // 节点 loop 变更走 actualChange → 重激活（下方 reactivate 分支应用）。
                          val loopStatusOk = !loopFlag.provided ||
                            (node.status == NodeLifecycle.Wiring || node.status == NodeLifecycle.Pending || node.status == NodeLifecycle.Running)
                          val earlyReject: Option[ToolError] =
                            validateDescription(description, creating = false).orElse(
                            validateDescriptionLong(descriptionLong)).orElse(
                            if finalIn.isEmpty && finalDeps.isEmpty && finalOut.isEmpty then
                              Some(ToolError(
                                s"Node '${node.name}' must keep at least one connection — this rewiring would leave it disconnected. (EMPTY_NODE_CONNECTION)"))
                            else if dChecks.exists(_.isLeft) then
                              Some(ToolError(dChecks.collectFirst { case Left(e) => e }.getOrElse("invalid deps")))
                            else if !notifyStatusOk then
                              Some(ToolError(
                                s"notifyDispatcher can only be set or withdrawn before completion (wiring/pending/running) — node '${node.name}' is ${node.status} (result already delivered). re-activation is the exit for blocked/failed."))
                            else if !loopStatusOk then
                              Some(ToolError(
                                s"loop can only be set or withdrawn before completion (wiring/pending/running) — node '${node.name}' is ${node.status} (result already delivered). re-activation is the exit for blocked/failed."))
                            // ⑥ in 上限（edit 路径，U2/P1）：合并节点 in 只增（append-only），
                            // 追加后 distinct 计数 >4 → 拒（=4 合法）。码 NODE_MERGE_IN_CAP。
                            else if node.merge && genuinelyNewIn.nonEmpty &&
                              finalIn.distinct.size > NodeTools.MergeInCap then
                              Some(ToolError(
                                s"merge node '${node.name}' accepts at most ${NodeTools.MergeInCap} upstreams in one ledger — " +
                                  s"this edit would leave ${finalIn.distinct.size} distinct upstream(s). Split the batch: create " +
                                  s"a second merge node for the extra upstream(s), then wire both merge nodes into a " +
                                  "follow-up merge/report node. (NODE_MERGE_IN_CAP)"))
                            // ⑧ 已触发禁加 in（edit 路径，U2/P1）：running/blocked/completed
                            // 的合并节点 in 账本冻结——它已按旧账本开火/终态化，追加的上游
                            // 永远不会被这一轮 barrier 消费（静默的半接边）。新 worktree 配新
                            // 合并节点。码 NODE_MERGE_FIRED_NO_IN。
                            else if node.merge && genuinelyNewIn.nonEmpty &&
                              NodeTools.MergeFiredStatuses.contains(node.status) then
                              Some(ToolError(
                                s"merge node '${node.name}' has already fired (status=${node.status}) — its in ledger is " +
                                  "frozen: an upstream appended now would never be consumed by this round's barrier " +
                                  "(silent half-wired edge). Create a NEW merge node for the new upstream(s) and wire it " +
                                  "downstream; un-triggered (wiring/pending) merges still accept appended upstreams. " +
                                  "(NODE_MERGE_FIRED_NO_IN)"))

                            else None
                            )
                          // 前置拒绝集统一闸（description 校验 + 零连接 + deps 校验）
                          if earlyReject.isDefined then IO.pure(Left(earlyReject.get))
                          // 批E2 retry 风暴防护（spec §2.3，0 spawn）：邻居限定（值域 =
                          // 应用本次改动后的最终 in ∪ deps）+ retry 环。校验失败卡在
                          // 全部写路径（in 追加/重激活/写回）之前——本次编辑零副作用。
                          else (retryFlag.policy match
                            case None => IO.pure(None)
                            case Some(p) =>
                              NodeTools.retryGuard(rt, node.id, node.name, p, finalIn ++ finalDeps)
                          ).flatMap {
                            case Some(err) => IO.pure(Left(ToolError(err)))
                            case None => {
                          // blocked/failed 重激活（blocked 反馈重入设计 §6 #5；failed
                          // 放开=2026-09-07 批作者裁定③）：编辑 blocked/failed 节点且
                          // task/description/in/out/deps 实际变更 → status 回
                          // wiring/pending、deliveredTo 清空、completedAt/ttlExpireAt/
                          // startedAt 复位，随后 D1 补投递链重投全部已完成上游。
                          // blocked 版 blockCount 保留（§3.1 轮次历史）；failed 版轮次
                          // 历史复位（重跑非语义阻塞轮次，见下方事务内注释）。
                          // （agent 已退役不可编辑——重激活沿用节点自身 agent。）
                          val taskChanged = task.exists(t => NodeTools.normalizeTask(t) != node.task.map(NodeTools.normalizeTask).getOrElse(""))
                          val descriptionChanged = description.exists(d => d.trim != node.description.getOrElse(""))
                          val depsChanged = depsProvided && newDeps != node.deps
                          val loopChanged = loopFlag.provided && loopFlag.config != node.loop
                          // out 未传 ≠ 变更（actualChange quirk 修，2026-09-07）：
                          // parseOut 未传=Nil 与 node.out 非空恒不等——裸比较会让
                          // 终态节点「未传 out 的编辑」恒判 actualChange=true → 意外重
                          // 激活，违背描述「No-op if nothing actually changed」。与
                          // 上方 setOutIO / finalOut 同款 outProvided 守卫（canonical
                          // 比较消歧段语法等价形态）。
                          val actualChange = taskChanged || descriptionChanged || outChanged || adds.nonEmpty || depsChanged || loopChanged
                          val reactivate = (node.status == NodeLifecycle.Blocked || node.status == NodeLifecycle.Failed) && actualChange
                          val appliedTask = task.orElse(node.task)
                          val appliedDeps = if depsProvided then newDeps else node.deps
                          // wiring 变更涉及节点集（最终态事件）：本节点（in/out 变更，in 追加
                              // 也改自身 in）、in 上游（out 追加指向本节点）、被移除/新增 out
                              // 目标（in 增删）。deps 目标不入列——下游单侧持有，上游
                              // payload 无「被谁依赖」字段，其卡片无需事件。
                              val affected: List[String] =
                                val fromOut =
                                  if outChanged then oldTargets ++ newTargets
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
                                      // in 追加（barrier）：每个上游 out 追加指向本节点的缺省
                                      // pass 边（P1 多边镜像——不覆盖上游既有边/门控，原子改投
                                      // §2.3 的单值语义在多边下自然兼容）。归档上游（活动区无
                                      // 副本）走 setOut 归档分支：活动侧目标 in 追加 + 归档 out
                                      // 镜像补写（旧 setOut(rt, upId, Some(node.id)) 同域语义）。
                                      _ <- adds.traverse_(upId =>
                                        rt.store.getNode(upId).flatMap {
                                          case Some(up) if !up.out.exists(_.to == node.id) =>
                                            NodeTools.setOut(rt, upId, up.out :+ OutEdge(node.id))
                                          case Some(_) => IO.unit // 边已存在（幂等）
                                          case None =>
                                            rt.store.findNode(upId).flatMap {
                                              case Some(archUp) if !archUp.out.exists(_.to == node.id) =>
                                                NodeTools.setOut(rt, upId, archUp.out :+ OutEdge(node.id))
                                              case _ => IO.unit
                                            }
                                        })
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
                                      // plugins 替换写回（阶段 2b §B.4 第 3 步，replace-on-provide
                                      // 与 deps 同款）：passed（any form, including []）= 整列表
                                      // 替换；未传 = 不改动。存在性+信任门校验已在 call() 前置
                                      // 拦截；这里纯写。对运行中节点仅改元数据——已运行会话的
                                      // 注入/MCP 不回溯（能力集 spawn 时冻结），下次运行生效。
                                      _ <-
                                        if pluginsOpt.isDefined then
                                          rt.store.mutate { s =>
                                            s.nodes.get(node.id) match
                                              case Some(fresh) =>
                                                s.copy(nodes = s.nodes.updated(node.id, fresh.copy(plugins = pluginsOpt.get)))
                                              case None => s
                                          }.void
                                        else IO.unit
                                      // description 写回（2026-09-05 创建必写批，编辑可 update）：
                                      // 校验（非空/≤200）已在 earlyReject 前置拦截；这里纯写
                                      //（trim 归一，与 create 同源）。blocked 节点的 description
                                      // 变更参与重激活判定（descriptionChanged）；非 blocked 节点
                                      // 纯元数据更新（不影响执行——description 是展示层）。
                                      _ <-
                                        if description.isDefined && !reactivate then
                                          rt.store.mutate { s =>
                                            s.nodes.get(node.id) match
                                              case Some(fresh) =>
                                                s.copy(nodes = s.nodes.updated(node.id, fresh.copy(description = description.map(_.trim))))
                                              case None => s
                                          }.void
                                        else IO.unit
                                      // descriptionLong 写回（裁定⑤c 双层化 20260907）：纯展示
                                      // 元数据（detail 通道/前端详情消费，不进默认载荷）——
                                      // 不参与重激活判定（任务语义信号由 task/description 承载）。
                                      _ <-
                                        if descriptionLong.isDefined && !reactivate then
                                          rt.store.mutate { s =>
                                            s.nodes.get(node.id) match
                                              case Some(fresh) =>
                                                s.copy(nodes = s.nodes.updated(node.id, fresh.copy(descriptionLong = descriptionLong.map(_.trim))))
                                              case None => s
                                          }.void
                                        else IO.unit
                                      // pendingSuccession 清除（取消静默死锁修复批 R4）：
                                      // 本节点带「待承接」标（其 in 上曾有被引擎取消并
                                      // 自动摘除的上游）而本次编辑有**实际变更** =
                                      // 分发器已介入处置（承接节点 append 回 in / 改接 /
                                      // 其它拓扑调整）→ 解除闸门，barrier 恢复可触发。
                                      // 清除只在 actualChange 时发生（纯 no-op 编辑不
                                      // 解锁，防误放行）；标记本身由 R4 摘除写入。
                                      _ <-
                                        if actualChange then
                                          rt.store.mutate { s =>
                                            s.nodes.get(node.id) match
                                              case Some(fresh) if fresh.pendingSuccession.nonEmpty =>
                                                s.copy(nodes = s.nodes.updated(node.id,
                                                  fresh.copy(pendingSuccession = Nil)))
                                              case _ => s
                                          }.void
                                        else IO.unit
                                      // notifyDispatcher 设置/撤销写回（dispatch-notify 批）：
                                      // 校验已在 earlyReject 拦截（终态 → 拒）；running 合法
                                      // （完成时行为开关）。事务内现读 fresh（R2 纪律）+ 状态双重保险。
                                      _ <-
                                        if notify.provided then
                                          rt.store.mutate { s =>
                                            s.nodes.get(node.id) match
                                              case Some(fresh) if fresh.status == NodeLifecycle.Wiring || fresh.status == NodeLifecycle.Pending || fresh.status == NodeLifecycle.Running =>
                                                s.copy(nodes = s.nodes.updated(node.id, fresh.copy(notifyDispatcher = notify.flag)))
                                              case _ => s
                                          }.void
                                        else IO.unit
                                      // loop 设置/撤销写回（LoopNode 批 2026-09-06）：校验已在
                                      // earlyReject 拦截（终态/held → 拒）；wiring/pending/running 合法
                                      // （loop 是 running 期行为开关）。事务内现读 fresh（R2 纪律）+
                                      // 状态双重保险；blocked 重激活在下方 reactivate 分支应用（不再走此）。
                                      _ <-
                                        if loopFlag.provided && !reactivate then
                                          rt.store.mutate { s =>
                                            s.nodes.get(node.id) match
                                              case Some(fresh) if fresh.status == NodeLifecycle.Wiring || fresh.status == NodeLifecycle.Pending || fresh.status == NodeLifecycle.Running =>
                                                s.copy(nodes = s.nodes.updated(node.id, fresh.copy(loop = loopFlag.config)))
                                              case _ => s
                                          }.void
                                        else IO.unit
                                      // retry 设置/清除写回（批E2 P2，spec §2.3）：replace-on-provide
                                      //（传了即整体替换，null=清除）；不参与重激活判定（retry 是
                                      // 「下一次失败」的行为开关，语义同 notifyDispatcher——已有
                                      // failed 节点要立即生效须另行重激活，如改 task）。
                                      // 语义校验（邻居/环）已在写路径前 retryGuard 拦截；此处纯写
                                      //（任意状态可设——failed 节点配置 retry 后，人工重激活触发的
                                      // 重跑再失败时自动回跳）。
                                      _ <-
                                        if retryFlag.provided then
                                          rt.store.mutate { s =>
                                            s.nodes.get(node.id) match
                                              case Some(fresh) =>
                                                s.copy(nodes = s.nodes.updated(node.id, fresh.copy(retry = retryFlag.policy)))
                                              case None => s
                                          }.void
                                        else IO.unit
                                      // blocked/failed 重激活写回（R2 纪律）：事务内现读
                                      // fresh，fresh 仍 Blocked/Failed 才写；状态已变（并发
                                      // abandon/重激活）→ 拒写不重激活。failed 版差异
                                      // （2026-09-07 批，作者裁定①②语义点）：**轮次历史复位**
                                      // 而非保留——重跑不是语义阻塞轮次：blockCount→0、
                                      // blockedFeedback 清除（blockCount 复位后旧反馈成孤
                                      // 儿数据）；notifySentAt 清除（重激活后再次真失败 →
                                      // 分发器再获通知——重试回路非循环口径，DispatchNotify
                                      // §2.2；inFlight 占位在通知尝试后已释放，不会挡第二次）。
                                      // blocked 版三者保留（blockCount 记语义阻塞轮次，
                                      // FeedbackRouter LoopCap 依赖）。
                                      didReactivate <-
                                        if reactivate then
                                          rt.store.mutate { s =>
                                            s.nodes.get(node.id) match
                                              case Some(fresh) if fresh.status == NodeLifecycle.Blocked || fresh.status == NodeLifecycle.Failed =>
                                                val fromFailed = fresh.status == NodeLifecycle.Failed
                                                val nextStatus =
                                                  if appliedTask.exists(_.trim.nonEmpty) && fresh.in.isEmpty then NodeLifecycle.Pending
                                                  else NodeLifecycle.Wiring
                                                s.copy(nodes = s.nodes.updated(node.id, fresh.copy(
                                                  status = nextStatus,
                                                  task = appliedTask,
                                                  description = description.map(_.trim).orElse(fresh.description),
                                                  descriptionLong = descriptionLong.map(_.trim).orElse(fresh.descriptionLong),
                                                  deps = appliedDeps,
                                                  loop = loopFlag.config.orElse(fresh.loop), // loop 变更随重激活应用
                                                  result = None, // blocked 反馈渲染串 / failed 错误文本均不复存在
                                                  deliveredTo = Nil,
                                                  // V8: 重激活 = 该节点身份重跑一轮，out=Nebula 投递
                                                  // 记账同样清零——重跑完成后的结果重新投递+记账。
                                                  nebulaDeliveredAt = None,
                                                  startedAt = None,
                                                  completedAt = None,
                                                  ttlExpireAt = None,
                                                  blockCount = if fromFailed then 0 else fresh.blockCount, // blocked 保留轮次 / failed 复位
                                                  blockedFeedback = if fromFailed then None else fresh.blockedFeedback,
                                                  notifySentAt = if fromFailed then None else fresh.notifySentAt)))
                                              case _ => s
                                          }.map(s2 =>
                                            s2.nodes.get(node.id).exists(n => n.status == NodeLifecycle.Wiring || n.status == NodeLifecycle.Pending))
                                        else IO.pure(false)
                                      _ <- if didReactivate then
                                        val reactivateNote =
                                          if node.status == NodeLifecycle.Failed then
                                            s"failed node edited (round history reset: blockCount→0, notifySentAt cleared) → rerun: ${appliedTask.map(t => s"task=${t.take(80)}").getOrElse("")}"
                                          else
                                            s"blocked node edited (round ${node.blockCount} preserved) → ${appliedTask.map(t => s"task=${t.take(80)}").getOrElse("")}"
                                        FlowMapEventLog.append(rt.project.workspace, rt.project.name, node.id, "reactivated", reactivateNote) *>
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
                                      // 已完成节点改接 → 立即投递（§2.3：结果缓冲/归档 → 向新目标
                                      // 投递）。P1 多边：向全部新声明目标投（Nebula/门控判定由
                                      // deliverOutTo 内部处理）。
                                      // 后台化（收口③）：deliverOutTo 内 startNode 同步等下游终态，
                                      // 直接调用会阻塞 NodeEdit 工具 fiber（见 runDetached 注释）。
                                      _ <- (outProvided, node.status) match
                                        case (true, NodeLifecycle.Completed) if newOut.nonEmpty =>
                                          node.result match
                                            case Some(res) =>
                                              NodeTools.runDetached(rt, s"deliver retained result ${node.id} -> ${newTargets.mkString(",")}")(
                                                newOut.traverse_(e => rt.engine.deliverOutTo(node, e.to, res))
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
                                        (if didReactivate then
                                          if node.status == NodeLifecycle.Failed then " — reactivated from failed (round history reset; next real failure re-notifies dispatcher)"
                                          else s" — reactivated from blocked (round ${node.blockCount} preserved)"
                                        else "") +
                                        (if notify.provided then s" — notifyDispatcher → ${notify.flag}" else "") +
                                        (if retryFlag.provided then
                                           retryFlag.policy match
                                             case Some(p) => s" — retry ← ${p.upstream}:max=${p.max}"
                                             case None    => " — retry cleared"
                                         else "") +
                                        (if outProvided && newOut.nonEmpty then s" — out → ${newOut.map(_.to).mkString(",")}" else "") +
                                        (if adds.nonEmpty then s" — in += ${adds.mkString(",")}" else "") +
                                        (if depsProvided && depsChanged then s" — deps → [${newDeps.mkString(",")}]" else "")
                                    )
                                // P1 校验层②（spec §2.2）：下游持 in 边而上游已 failed 无 on-failed
                                // 边且非 merge → WARNING 级提示（不阻断，附成功结果尾部；死锁持续
                                // 可见性由既有 mount-stalled 事件承载）
                                stallWarn <- NodeTools.stalledInWarning(rt, node.id, node.name, finalIn, node.merge)
                              yield inResult.map(r =>
                                if stallWarn.isEmpty then r else r + "\n" + stallWarn.mkString("\n"))
                          }
                          }
                          }
                  }
              }

object NodeListTool extends Tool:
  val name = "NodeList"

  val description =
    """List a project's Flow Map snapshot — the task dispatcher's opening move (read the graph before deciding).
## When to Use
- The dispatcher reads the current topology at session start (and before ending) — nodes with status/description/hasWorktree drive merge-node decisions.
- Frontend panel data source (REST mirrors this shape).

## Parameters
- **project** (optional; defaults to current project): project name.
- **status** (optional): filter the map to specific lifecycle state(s) — single value, comma-separated list, or array. Valid: wiring, pending, running, completed, failed, cancelled, blocked. Example: "running,pending" to see only active work. Omitted = ALL nodes (use this for the full picture; filter only when the map is large and you know which slice you need). Applies to the map listing only, not to detail=.
- **detail** (optional): a node id — returns that ONE node's full record instead of the whole map: metadata + task + result FULL TEXT (+ historical blockedFeedback when present). Payloads carry no result text; this is the on-demand read channel, same source as the REST result endpoint.

## Returns
Default: {nodes: [{id, name, agent, description, status, in, out, hasWorktree, worktree, blockCount, createdAt, completedAt, ttlLeftSec, + conditional: hasResult, taskPreview (legacy no-description fallback), deps, plugins, merge, loop, blockedFeedback (blocked only), skill/mcp/preset (legacy values only), liveness (running only), chainId (multi-member chain only), chainIds (merge nodes with 2+ member chains only)}], chains: [{id, title, entries, ends, memberIds}] (topological task chains derived backend-side; a node's chainId joins its entry here; members may include archived nodes — filter by your node cache for on-graph rendering), worktrees: [...], meta: {project, updatedAt, archived}} — metadata only, NO result text (Flow Map slim-payload contract: results live in per-node files, read on demand).
With detail=<nodeId>: the same node shape + task + result (full text)."""
  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "project" -> Json.obj("type" -> "string".asJson, "description" -> "Project name (optional; defaults to the dispatcher's current project)".asJson),
        "status" -> Json.obj("oneOf" -> Json.arr(
          Json.obj("type" -> "string".asJson),
          Json.obj("type" -> "array".asJson, "items" -> Json.obj("type" -> "string".asJson))
        ).asJson, "description" -> "Optional lifecycle filter: wiring|pending|running|completed|failed|cancelled|blocked — single, comma-separated, or array. Omitted = all nodes".asJson),
        "detail" -> Json.obj("type" -> "string".asJson, "description" -> "Node id — return that node's full record (metadata + task + result FULL TEXT) instead of the whole map".asJson)
      ),
      "required" -> Json.arr()
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
        // detail 通道（2026-09-05 载荷收敛配套）：指定 nodeId → 单节点全记录
        // （元数据 + task + 结果全文）。数据源与 REST result 端点同源
        // （findNode 活动区优先归档兜底；内存 result/task = 加载时从 per-node
        // 文件 results/<id>.md / tasks/<id>.md 水合的全文——落盘 JSON 只存摘要
        // +指针，detail 聚合即「per-node 文件内容」，单源等价，2026-09-06 存储
        // 瘦身批）。payload 键集 = NodeList 同构字段 + task + result。
        input("detail").flatMap(_.asString).map(_.trim).filter(_.nonEmpty) match
          case Some(nodeId) =>
            rt.store.findNode(nodeId).flatMap {
              case None => IO.pure(Left(ToolError(s"Node '$nodeId' not found (active or archived) — check NodeList without detail for the current topology")))
              case Some(n) =>
                // chainId / chainIds 条件键（链级抽象 P0 + U1 多链归属批）：detail
                // 单节点记录与快照/WS 同构——判据单点 FlowMapStore.chainAttrsOf
                // （双区可达，归档节点同样带链归属；chainIds 仅 merge 多链节点带）。
                rt.store.chainAttrsOf(n.id).map { case (chainId, chainIds) =>
                  val now = System.currentTimeMillis()
                  val base = NodePayload.buildNodeJson(n, now, chainId, chainIds)
                  // 裁定②历史参照补挂（20260907 上下文经济学批）：默认载荷仅 blocked 态
                  // 携带 blockedFeedback——detail 按需通道对非 blocked 节点补挂存储值
                  // （blocked 节点 base 已含，deepMerge 同值幂等）。
                  val histFeedback = n.blockedFeedback.toList.map(bf => "blockedFeedback" -> bf.asJson)
                  // 裁定⑤c 双层化：descriptionLong 仅 detail 通道（不进默认载荷）——
                  // 条件键，存量节点无长文 → 缺键（消费方回退短文 description）。
                  val descLong = n.descriptionLong.toList.map(d => "descriptionLong" -> d.asJson)
                  val full = base
                    .deepMerge(Json.obj(
                      "task" -> n.task.asJson,
                      "result" -> n.result.asJson
                    ))
                    .deepMerge(Json.obj((histFeedback ++ descLong)*))
                  Right(full.noSpaces)
                }
            }
          case None =>
            // status 过滤（裁定⑤a，20260907）：解析三形态宽容（string/array/逗号串），
            // 逐值枚举校验；缺省 None = 全量（字节级现状）。
            parseStatusFilter(input("status")) match
              case Left(err) => IO.pure(Left(ToolError(err)))
              case Right(statusFilter) =>
                NodeTools.buildNodeListPayload(rt, statusFilter).map(payload => Right(payload.noSpaces))
    }

  /** status 过滤参数解析（裁定⑤a）：三形态宽容（单值 string / array / 逗号串，
    * 复用 parseIn 语义），逐值对生命周期枚举校验——非法值给可行动错误（列出全部
    * 合法值）。缺省（未传/null）→ None = 全量。 */
  private def parseStatusFilter(v: Option[Json]): Either[String, Option[Set[String]]] =
    v match
      case None | Some(Json.Null) => Right(None)
      case Some(json) =>
        NodeTools.parseIn(Some(json)).left.map(err => err.replace("'in'", "'status'")).flatMap { values =>
          val trimmed = values.map(_.trim).filter(_.nonEmpty)
          if trimmed.isEmpty then Right(None)
          else trimmed.find(!NodeLifecycle.All.contains(_)) match
            case Some(bad) => Left(s"Unknown status '$bad' — valid: ${NodeLifecycle.All.mkString(", ")} (single value, comma-separated list, or array)")
            case None => Right(Some(trimmed.toSet))
        }

object NodeCancelTool extends Tool:
  val name = "NodeCancel"

  val description =
    """Cancel a running node (supervisor cancel semantics) — the dispatcher's stop-loss tool.
## When to Use
- A node is mis-wired, hung, or superseded: cancel it, then rewire or recreate. Result is NOT delivered; upstream results already delivered stay buffered/archived.
- Cancel target must be running (non-running → no-op with notice). A running node with a live session gets a cancel signal; a STALE running node (dead session, e.g. after an instance restart) is reaped — finalized as cancelled immediately instead of a fake success."""
  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "project" -> Json.obj("type" -> "string".asJson, "description" -> "Project name (optional; defaults to the dispatcher's current project)".asJson),
        "node-id" -> Json.obj("type" -> "string".asJson, "description" -> "Node id to cancel (from NodeList)".asJson)
      ),
      "required" -> Json.arr("node-id".asJson)
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

/** NodeMessage（20260905 机制批，作者裁定六条语义）——任务分发器向已分发节点
  * 注入补充消息。仅分发器工具面（AgentCore.DispatcherFixedTools 第八件）；
  * Nebula/general 不加。六条语义（engine 单点 sendNodeMessage）：
  * ① 签名 (nodeId, message)：目标=本项目 Flow Map 节点；nodeId 不存在→
  *   NODE_NOT_FOUND；message 空白→NODE_MESSAGE_EMPTY。
  * ② running：下一 turn 边界注入（复用 immediate 注入机制），[NODE-MESSAGE]
  *   前缀（来源=分发器 NodeMessage+节点名+时间戳）；会话已终结→记录追加
  *   「注入未达」（留痕不丢）。
  * ③ wiring/pending（非终态无活动会话）：持久追加节点任务记录（
  *   == 分发器补充（NodeMessage <时间戳>） == 分节），启动时随任务读到。
  * ④ 终态（completed/failed/cancelled/blocked）拒绝 NODE_TERMINAL_NO_MESSAGE
  *   ——应新建节点而非倒改。
  * ⑤ 留痕：每条消息追加 FlowMapEventLog（node-message）；Flow Map 默认载荷
  *   零膨胀。
  * ⑥ 工具面：仅分发器（本文件注册 + AgentCore 分发器静态集）。 */
object NodeMessageTool extends Tool:
  val name = "NodeMessage"

  val description =
    """Send a supplementary message to an already-dispatched node in a project's Flow Map — the task dispatcher's course-correction tool.
## When to Use
- After dispatching: new constraints, corrections, or extra context that must reach a node WITHOUT re-dispatching or editing topology. Routing is automatic by node status.
- **Dispatcher-only tool** (mechanism-fixed). Do not use for terminal nodes — see below.
## Parameters
- **project** (optional; defaults to current project): project name.
- **nodeId** (required): target node id (from NodeList).
- **message** (required, non-empty): the supplementary instruction text.
## Routing semantics (by target node status)
- **running**: the message is injected into the node's live session at the NEXT turn boundary (reuses the immediate-input mechanism — it does NOT interrupt the current turn). The injected text carries a recognizable `[NODE-MESSAGE]` header (source: dispatcher NodeMessage + node name + timestamp), clearly distinct from the node's original task and from result deliveries. Race fallback: if the session ends before delivery, the message is recorded on the node's task annotated「注入未达」— the trace is never lost.
- **wiring / pending** (non-terminal, no live session): the message is persistently appended to the node's task record as a `== 分发器补充（NodeMessage <timestamp>） ==` section — the node reads it as part of its task when it starts.
- **terminal (completed / failed / cancelled / blocked)**: REFUSED (NODE_TERMINAL_NO_MESSAGE). A finished node is never retro-edited — create a new node instead (NodeEdit), even for blocked/failed (their exit = NodeEdit reactivation or abandon, not messages).
## Errors
- `NODE_NOT_FOUND` — nodeId does not exist in the project (active or archived).
- `NODE_MESSAGE_EMPTY` — message is blank after trim.
- `NODE_TERMINAL_NO_MESSAGE` — target is terminal; create a new node instead.
## Traceability
Every message (injected / appended / not-delivered) is appended to the project's flow-map-events.jsonl audit log (type=node-message). Flow Map default payloads stay unchanged (no extra keys — the payload-slimming contract holds)."""
  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "project" -> Json.obj("type" -> "string".asJson, "description" -> "Project name (optional; defaults to the dispatcher's current project)".asJson),
        "nodeId" -> Json.obj("type" -> "string".asJson, "description" -> "Target node id (from NodeList)".asJson),
        "message" -> Json.obj("type" -> "string".asJson, "description" -> "Supplementary instruction text (non-empty; injected at turn boundary if running, appended to task if not started)".asJson)
      ),
      "required" -> Json.arr("nodeId".asJson, "message".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val id = input("nodeId").flatMap(_.asString).getOrElse("?")
    s"NodeMessage($id)"

  def summarizeResult(input: JsonObject, result: String): String = result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val nodeId = input("nodeId").flatMap(_.asString).getOrElse("")
    val message = input("message").flatMap(_.asString).getOrElse("")
    if nodeId.trim.isEmpty then IO.pure(Left(ToolError("Missing 'nodeId' — target node id is required (from NodeList)")))
    else if message.trim.isEmpty then
      IO.pure(Left(ToolError("'message' must be non-empty (trim) — nothing to inject (NODE_MESSAGE_EMPTY)")))
    else
      NodeTools.resolveProject(input("project").flatMap(_.asString).orElse(ctx.projectName), ctx).flatMap {
        case Left(err) => IO.pure(Left(ToolError(err)))
        case Right(rt) => rt.engine.sendNodeMessage(nodeId, message).map(_.left.map(ToolError(_)))
      }

object ProjectCreateTool extends Tool:
  val name = "ProjectCreate"

  val description =
    """Create a Project (Nebula use) — project definition + workspace .nebflow/ scaffolding.
## When to Use
- **Known workspace path** (the user told you, or you know it): pass `workspace` (absolute path; `name`/`description` optional) — direct create: writes projects/<name>/project.json, workspace root AGENTS.md template, workspace/.nebflow/ + .gitignore scaffolding, and mounts the project (FlowMapStore + ProjectActor ready).
- **Unknown workspace path**: omit `workspace` — an AskUserQuestion-style card pops up on the user's window with a prominent "选择工作区" (pick workspace) target. Clicking it opens the REAL OS folder browser (native directory dialog on macOS/Windows) where the user browses, can create folders, and confirms; the chosen path flows back into the card and creation proceeds automatically. If the native dialog is unavailable (headless JVM) or fails, the card automatically falls back to an in-app folder browser. Candidate paths (first-level directories under ~/Claude code/ not already used as project workspaces) remain on the card as secondary one-click hints, and the built-in "Other…" free input accepts a custom absolute path.
- `name` defaults to the workspace path's basename when omitted.
## After Creation
- Dispatch work with Task(project=<name>, task=...) — the project is mounted and triggerable immediately.
## Semantics
- Same name + same workspace → idempotent (returns "already exists", re-mounts; safe to repeat).
- Same name + different workspace → explicit error (never silently re-points an existing project).
- Panel dismissed (cancel / empty answer) → clear shelved message, nothing created — re-invoke with a known path or ask the user again."""
  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "name" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Project name (single path segment). Optional — defaults to the workspace path basename.".asJson
        ),
        "workspace" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Absolute path to the project workspace. Omit to pop the interactive path-selection panel (AskUserQuestion-style card).".asJson
        ),
        "description" -> Json.obj("type" -> "string".asJson, "description" -> "Optional one-line description".asJson)
      ),
      "required" -> Json.arr()
    )
  )

  /** 前端卡片取消哨兵（chat.js 取消按钮回填 answers=['__cancelled__']）。 */
  val CancelSentinel = "__cancelled__"

  def summarize(input: JsonObject): String =
    val n = input("name").flatMap(_.asString).orElse(input("workspace").flatMap(_.asString))
    s"ProjectCreate(${n.getOrElse("<panel>")})"

  def summarizeResult(input: JsonObject, result: String): String = result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val rawName = input("name").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
    val rawWorkspace = input("workspace").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
    val description = input("description").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
    rawWorkspace match
      // 已知路径直建（口径①，既有路径语义不变，name 缺省改 basename 派生）。
      case Some(ws) =>
        Try(os.Path(ws, PathUtil.dataRoot)) match
          case scala.util.Success(_) => createChain(rawName, ws, description, ctx)
          case scala.util.Failure(_) => pathPanel(rawName, description, ctx) // path 不可用 → 面板兜底
      // 未知/缺省 path（口径②）→ AskUser 式交互面板。
      case None => pathPanel(rawName, description, ctx)

  // ============================================================
  // 创建链（直建与面板选择路径共用）
  // ============================================================

  /** 创建 + 幂等挂载。name 缺省 = workspace basename（口径①）。
    * 同名冲突语义（任务口径）：同 workspace → 幂等（"already exists" + 挂载）；
    * 异 workspace → 明确报错（绝不静默改指旧定义）。 */
  private def createChain(
      nameOpt: Option[String],
      workspace: String,
      description: Option[String],
      ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    val resolvedName = nameOpt.getOrElse(baseName(workspace))
    if resolvedName.isEmpty then
      IO.pure(Left(ToolError(s"Cannot derive project name from workspace '$workspace' — pass 'name' explicitly")))
    else
      val agentMdTemplate =
        s"""# ${resolvedName} — AGENTS.md

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
                Right(
                  s"Project '${pd.name}' $verb and mounted. Flow Map ready at ${pd.agentFile}. " +
                    s"Dispatch work with Task(project='${pd.name}', task=...)."
                )
              }
          case _ =>
            IO.pure(Right(s"Project '${pd.name}' definition ready. Mount requires an agent session."))

      ProjectStore.create(resolvedName, workspace, description, agentMdTemplate).flatMap {
        case Right(pd) => mountProject(pd, created = true)
        case Left(err) =>
          // 幂等挂载（试点重启恢复关键路径）：定义已存在 → 不重建定义、不动脚手架，
          // 直接挂载（ProjectStore.create 防覆盖返回 Left；load 命中即已存在）。
          // 同名异 workspace → 明确报错（不静默复用旧定义）。
          // 归档项目例外（迁移方案 v2 §6.1 单程语义）：拒绝挂载——否则出现「已挂载
          // 但面板不可见」（list 过滤）的僵尸态；恢复须先手工删 project.json 归档两键。
          ProjectStore.load(resolvedName).flatMap {
            case Some(pd) if pd.archived.contains(true) =>
              IO.pure(Left(ToolError(
                s"Project '$resolvedName' is archived (hidden from the Projects panel). " +
                  s"Remove the 'archived'/'archivedAt' keys in ~/.nebflow/projects/$resolvedName/project.json to restore it first."
              )))
            case None => IO.pure(Left(ToolError(err)))
            case Some(pd) if sameWorkspace(pd.workspace, workspace) => mountProject(pd, created = false)
            case Some(pd) =>
              IO.pure(Left(ToolError(
                s"Project '$resolvedName' already exists with a different workspace (${pd.workspace}) — " +
                  "choose another name or reuse the existing workspace"
              )))
          }
      }

  /** workspace 归一化（绝对化 + 去尾斜杠），用于同名冲突判定。
    * 不可解析 → None（视为不同）。 */
  private def normalizeWorkspace(p: String): Option[String] =
    Try(os.Path(p, PathUtil.dataRoot).toString).toOption

  private def sameWorkspace(a: String, b: String): Boolean =
    (normalizeWorkspace(a), normalizeWorkspace(b)) match
      case (Some(x), Some(y)) => stripTrailingSlashes(x) == stripTrailingSlashes(y)
      case _                  => false

  /** 路径 basename（name 派生）；根路径等无 basename → ""（由调用方报错）。 */
  private def baseName(workspace: String): String =
    Try(os.Path(workspace, PathUtil.dataRoot)).map(_.last).getOrElse("")

  // ============================================================
  // 未知路径交互面板（口径②）
  // ============================================================

  /** '~' 展开（仅前缀语义，防 API 差异；非 ~ 开头原样返回）。 */
  def expandTilde(path: String): String =
    val home = sys.props("user.home")
    if path == "~" then home
    else if path.startsWith("~/") then home + path.drop(1)
    else path

  private sealed trait PanelAnswer
  private object PanelAnswer:
    /** 取消哨兵 / 空答案 → 搁置（不创建、不报错悬挂）。 */
    case object Shelved extends PanelAnswer
    /** 非绝对路径等不可用输入 → 明确报错（不创建）。 */
    case class BadPath(raw: String, why: String) extends PanelAnswer
    /** 合法绝对路径 → 进入创建链。 */
    case class Chosen(path: String) extends PanelAnswer

  /** 去尾部斜杠（Scala String.stripTrailing 无参版，斜杠语义手写）。 */
  private def stripTrailingSlashes(s: String): String = s.replaceAll("/+$", "")

  /** 面板答案解析（纯函数，spec 覆盖）：首槽空 / 取消哨兵 → Shelved；
    * '~' 展开后非绝对 → BadPath；合法 → Chosen（去尾斜杠）。
    * isAbsolute 为跨平台判定（POSIX / 盘符 / UNC）——朴素 startsWith("/")
    * 会拒绝 Windows 盘符答案（diag-win-paths P1）。 */
  private def parsePanelAnswer(answers: List[String]): PanelAnswer =
    val raw = answers.headOption.map(_.trim).getOrElse("")
    if raw.isEmpty || raw == CancelSentinel then PanelAnswer.Shelved
    else
      val expanded = expandTilde(raw)
      if !PathUtil.isAbsolute(expanded) then PanelAnswer.BadPath(raw, "path must be absolute")
      else PanelAnswer.Chosen(stripTrailingSlashes(expanded))

  /** 面板答案落地（纯分派）：Shelved → 搁置消息；BadPath → 明确报错；
    * Chosen → 创建链。 */
  private def applyPanelAnswer(
      ans: PanelAnswer,
      nameOpt: Option[String],
      description: Option[String],
      ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    ans match
      case PanelAnswer.Shelved =>
        IO.pure(Right(
          "ProjectCreate shelved: the path-selection panel was dismissed without a choice — " +
            "no project created. Re-invoke with a known 'workspace', or ask the user again."
        ))
      case PanelAnswer.BadPath(raw, why) =>
        IO.pure(Left(ToolError(
          s"Panel answer '$raw' is not usable ($why) — nothing created. " +
            "Re-invoke with an absolute 'workspace' path."
        )))
      case PanelAnswer.Chosen(path) => createChain(nameOpt, path, description, ctx)

  /** 未知路径分支：复用 AskUser pending 机制（AgentCommand.AskUser →
    * InteractionHub → 前端 AskUserQuestion 卡片），零新增前端/问答通道。
    *
    * 语义边界（全部与 AskUserQuestionTool 对齐，不另起一套）：
    * - headless（NEBFLOW_HEADLESS=1）→ askGuard 同款报错（无交互用户，面板会
    *   永久悬挂）；
    * - 无 agent 会话（REST 直调 / harness）→ 明确报错不悬挂；
    * - 等待无人工超时——与 AskUserQuestion 同语义（pending 期间会话标记
    *   WaitingForUser 豁免 TaskStuckWatcher；hub 缺席时 AgentActor 直接取消
    *   ask 回 Nil → 走 Shelved 搁置消息）；
    * - 取消/空答案 → 明确搁置消息（无创建、无悬挂）；
    * - 答案落定 → restoreRegistryAfterAnswer 配对恢复（与 AskUserQuestionTool
    *   同一实现——#43 修复的答案来源校验语义原样适用：面板答案只能来自用户
    *   点选帧 askUserAnswer（非 injected 用户消息），agent 侧注入负载永不消费）。
    */
  private def pathPanel(
      nameOpt: Option[String],
      description: Option[String],
      ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    AskUserQuestionTool.askGuard() match
      case Some(err) => IO.pure(Left(err))
      case None =>
        ctx.agentActorRef match
          case None =>
            IO.pure(Left(ToolError(
              "Workspace path required — no interactive session available for the path-selection panel; " +
                "pass 'workspace' (and optionally 'name') explicitly."
            )))
          case Some(agentRef) =>
            // 2026-09-09 作者裁定：面板不再下发候选（无候选 chips、无「其他…」）。
            // 选择面 = 应用内目录浏览器（dirPicker=true → 前端大目标 → workspacePicker.js，
            // 可逐级浏览/新建文件夹/显示隐藏目录）+ 空 options 使自由输入 textarea 直接
            // 可见（~ 手输兜底，后端 expandTilde/parsePanelAnswer 负责展开与绝对化校验）。
            val question =
              s"ProjectCreate 需要项目工作区路径 — 点击上方「选择工作区」打开应用内目录浏览器" +
                s"（可逐级浏览、新建文件夹，含隐藏目录）；或在下方输入框手输绝对路径（支持 ~ 展开）。"
            val item = AskItem(question, List.empty, dirPicker = true)
            val requestId = java.util.UUID.randomUUID().toString.take(8)
            for
              answers <- agentRef
                .?(
                  (replyTo: ActorRef[List[String]]) => AgentCommand.AskUser(requestId, List(item), Some(replyTo)),
                  timeout = None
                )
              _ <- AskUserQuestionTool.restoreRegistryAfterAnswer(ctx)
              result <- applyPanelAnswer(parsePanelAnswer(answers), nameOpt, description, ctx)
            yield result
end ProjectCreateTool
