package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.{ActorRef, AgentCommand}
import nebflow.core.PathUtil
import nebflow.core.entity.EntityLoader
import nebflow.core.project.*
import nebflow.shared.{AskItem, NebflowLogger}

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
 * （反向一致），被移除目标 in 移除、新增目标 in 追加，杜绝双写漂移。in 参数用于
 * 创建/追加 barrier 入边（等价给上游 out 追加指向本节点的 pass 边）。
 */
object NodeTools:
  private val logger = NebflowLogger.forName("nebflow.node.tools")

  /**
   * ⑥ 合并节点 in 上限（merge-node 设计落实批 U2/P1，2026-09-11 作者拍板「升引擎硬闸」）。
   * 纪律原文（分发器提示词「合并节点」节 + 设计指南 §0bis）：「in ≤4，超限拆多个合并
   * 节点」——此前纯纪律、引擎查无校验。边界：=4 合法、≥5 拒（`NODE_MERGE_IN_CAP`）。
   */
  val MergeInCap: Int = 4

  /**
   * ⑧ 「已触发」状态集（同一裁定）：running/blocked/completed 的合并节点其 in 账本冻结
   * ——新 worktree 配新合并节点，禁向已触发节点追加 in（`NODE_MERGE_FIRED_NO_IN`）。
   * un-triggered（wiring/pending）追加是正常回流，放行。
   *
   * failed/cancelled **不在集内**（有意）：二者是 NodeEdit 重激活闸唯一放行的两个状态
   * （`ReactivateStatuses`），把 in 追加一起拒会把「重激活时重接上游」这条恢复路径掐死
   * ——与设计原文只列 running/blocked/completed 逐字一致。
   */
  val MergeFiredStatuses: Set[String] =
    Set(NodeLifecycle.Running, NodeLifecycle.Blocked, NodeLifecycle.Completed)

  /**
   * 解析 project（参数优先，fallback ctx.projectName；项目名大小写不敏感——
   * registry.get 已做 equalsIgnoreCase 兜底，Nebflow/nebflow 等价）。
   */
  def resolveProject(project: Option[String], ctx: ToolContext): IO[Either[String, ProjectRuntime]] =
    val name = project.orElse(ctx.projectName).getOrElse("")
    if name.isEmpty then IO.pure(Left("Missing 'project' parameter (and no project context)"))
    else
      ProjectRuntimeRegistry.get(name).flatMap {
        case Some(rt) => IO.pure(Right(rt))
        case None =>
          ProjectRuntimeRegistry.all.map(rts => Left(mountError(name, rts.map(_.project.name))))
      }

  /**
   * 项目未挂载报错（附可用项目列表提示实际名称）——纯函数便于单测。
   * 精确项目名不匹配时列出已挂载项目（提示实际名称，不裸报 not mounted）；
   * 空列表（无任何挂载项目）给单独一行提示。
   */
  def mountError(name: String, available: List[String]): String =
    if available.isEmpty then
      s"Project '$name' is not mounted. No projects are currently mounted. Use ProjectCreate first."
    else s"Project '$name' is not mounted. Available projects: ${available.mkString(", ")}. Use ProjectCreate first."

  /** 引用节点存在性（活动区 + 归档区）。 */
  def ensureNodeExists(rt: ProjectRuntime, id: String): IO[Either[String, Unit]] =
    rt.store.findNode(id).map {
      case Some(_) => Right(())
      case None => Left(s"Referenced node '$id' not found in project '${rt.project.name}'")
    }

  /**
   * out 目标解析（20260909 in 丢失事故修复面单点）：out 边目标串接受节点 id 或节点名
   * （in/deps 维持纯 id 契约，不经此解析）。返回解析后的节点 id；悬空 → None。
   * 校验（存在性/状态守卫/环检测）、in 镜像记账、投递结算统一以解析结果为准——
   * 原始串形态只留在存储层（边 to 字段，前端/审计可读）。
   */
  def resolveOutTarget(rt: ProjectRuntime, target: String): IO[Option[String]] =
    if target == OutEdge.RootTarget then IO.pure(None)
    else rt.store.snapshot.map(s => OutEdge.resolveTargetId(s.nodes, target))

  /**
   * 状态守卫：目标已运行 → 拒绝接线（§2.2「目标已运行，输入已冻结」）。
   *
   * B5 缺口③ 后的**适用面收窄**（作者 2026-09-17 M-3 裁定，选项①）：本守卫只对
   * **非控制边**（真实输入边 result / signal）与「已接线且未变」的目标生效；指向
   * running 目标的**控制边**（`:loop`）改由 [[deferredLoopEdges]] 入待接线队列
   * （`NodeDef.pendingOut`），不再整单拒绝——控制边零投递、不进 in 镜像/屏障，对其
   * 冻结输入的理由不成立（同族先例：环检 `:2071` 早已过滤控制边）。
   */
  def ensureTargetNotRunning(rt: ProjectRuntime, id: String): IO[Either[String, Unit]] =
    rt.store.getNode(id).map {
      case Some(n) if n.status == NodeLifecycle.Running =>
        Left(
          s"Node '$id' is running — its input is frozen. NodeCancel it first, then rewire. " +
            "(NODE_TARGET_RUNNING_INPUT_FROZEN; control edges — `(fail)<worker>:loop` — are exempt: they queue in " +
            "pendingOut and auto-wire once the target leaves running)"
        )
      case _ => Right(())
    }

  /**
   * **编辑期待接线分区判据单点**（B5 缺口③ · 作者 2026-09-17 M-3 裁定「待接线队列
   * （到点自动接）」，选项①）：把本次声明的 `newOut` 相对**已接线** `currentOut` 的
   * **新增边**按「目标此刻是否 running」三分为：
   *
   *   - `deferred`：新增 **控制边**（`:loop`）∧ 目标 running ⇒ 入 `NodeDef.pendingOut`
   *     待接线队列（不进 out），目标离开 running 后由 `NodeEngine.applyDeferredWiring`
   *     自动接线 + 落痕；
   *   - `frozen`：新增 **非控制边**（result / signal）∧ 目标 running ⇒ 照旧**拒**
   *     （护「in 在启动前定型」的屏障语义，merge 面保护不得被本批削弱）；
   *   - **未变边（`currentOut` 已含）**：两边皆不入 ⇒ 编辑放行（已接线者无需再接、也
   *     无输入冻结可言——旧守卫「对所有声明目标含未变者」的过宽面即在此收窄）。
   *
   * 🔴 为什么必须排除「已接线」：若把已接线的控制边也判进 `deferred`，一次只改别的字段
   * 的编辑就会把在用的回边从 `out` 摘走、丢进队列（回边在目标 running 期间静默失效）
   * ——这正是本判据按 canonical diff 计算的原因。
   *
   * 目标串支持 id / 名字两形态（`OutEdge.resolveTargetId` 单点）；悬空目标在此忽略
   * （上游 `resolvedIds` 闸已拒，正常不可达；`frozen` 只承载可行动的点名）。
   * 幂等：同一 out 重复提交给出同一分区。
   */
  def partitionDeferredWiring(
    rt: ProjectRuntime,
    currentOut: List[OutEdge],
    newOut: List[OutEdge]
  ): IO[(List[(OutEdge, String)], List[String])] =
    rt.store.snapshot.map { s =>
      val wired = OutEdge.canonical(currentOut)
      val added = OutEdge.canonical(newOut).filterNot(wired.contains)
      def runningTargetOf(e: OutEdge): Option[String] =
        OutEdge.resolveTargetId(s.nodes, e.to).filter(id => s.nodes.get(id).exists(_.status == NodeLifecycle.Running))
      val deferred = added.filter(OutEdge.isLoopEdge).flatMap(e => runningTargetOf(e).map(id => (e, id)))
      val frozen = added.filterNot(OutEdge.isLoopEdge).flatMap(runningTargetOf).distinct
      (deferred, frozen)
    }

  /**
   * P1 out 表面语法解析（spec §2.2；向后兼容 + 扇出 + 失败信号边）：
   *   out="B"                  → List(OutEdge("B"))                          旧单值等价
   *   out="(pass)B, (failed)C" → List(OutEdge("B"), OutEdge("C",{failed}))   扇出
   *   out="(failed)C:signal"   → List(OutEdge("C",{failed},"signal"))        失败纯信号边
   * 段语法：(gates)? target (:mode)?——gates 逗号分隔 ⊆{pass,failed}（缺省 pass）；
   * mode ∈{result,signal}（缺省 result）。**Nebula 两态语义（2026-09-12 裁定 1，
   * R1-a）**：隐式门（bare `"Nebula"`）＝纯出口标记 ⇒ `on={pass}` + `mode=signal`
   * （零投递、只记账）；显式门集 `"(pass)Nebula"` / `"(pass,failed)Nebula"` ＝通知
   * 声明（按声明门 + 声明 mode 真投递）。null/"null" → Nil = 断开（空 out 合法）；
   * JSON 数组 → 拒（1 对多改用逗号段语法）。输出经 canonical 规范化（同 (to,mode)
   * 合并 on 集）。纯函数便于单测。
   */
  def parseOut(outJson: Option[Json]): Either[String, List[OutEdge]] =
    outJson match
      case None => Right(Nil) // 未传 out = 不改动（哨兵语义不变）
      case Some(j) if j.isNull => Right(Nil) // out=null = 断开（悬空化）
      case Some(j) if j.isArray =>
        Left(
          "'out' must be a target spec string — fan-out uses the \"(pass)B, (failed)C\" segment syntax, not a JSON array"
        )
      case Some(j) =>
        j.asString match
          case Some(s) => parseOutSyntax(s)
          case None =>
            Left(
              "'out' must be a target spec string (node id, \"Nebula\", or \"(gates)target:mode\" segments), or null"
            )

  /**
   * 表面语法段解析入口：整值 trim；"null"（任意大小写）= 断开；逗号分段逐段解析
   * 后 canonical 规范化。分段为**括号深度感知**——门组内的逗号（"(pass,failed)B"）
   * 不是段分隔符。
   */
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

  /**
   * 门控组解析："(pass)" / "(pass,failed)" / "(fail)"——空组/非法门 → 可行动错误
   * （附原文 + **fail/failed 一字之差的区分说明**，nrloop 一期 §3.3 #11：写错门是
   * 本批最可能的 LLM 误用面，错误文案必须自己说清两词的区别）。
   */
  private def parseOutGates(inner: String, seg: String): Either[String, Set[String]] =
    val parts = inner.split(',').map(_.trim).filter(_.nonEmpty).toList
    if parts.isEmpty then
      Left(
        s"invalid out segment '$seg': empty '(gates)' — use pass and/or failed (node-status gate) or fail (verdict gate)"
      )
    else
      val gs = parts.map(_.toLowerCase).toSet
      val invalid = gs -- OutEdge.Gates
      if invalid.nonEmpty then
        Left(
          s"invalid out gate(s) '${invalid.mkString(",")}' in '$seg' — gates ⊆ {pass, failed, fail}. " +
            "Note the two: 'failed' = the NODE-STATUS gate (fires when the upstream node itself failed); " +
            "'fail' = the VERDICT gate (a verifier's reject verdict; must be paired with the ':loop' mode and " +
            "only a role=verifier node may declare it)"
        )
      else Right(gs)

    end if

  end parseOutGates

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
      else if target.equalsIgnoreCase("null") then
        Left(s"invalid out segment '$seg': null is only valid as the whole out value")
      else if !OutEdge.Modes.contains(mode) then
        // mode 白名单纳入 `loop`（控制边；nrloop 一期 §3.3 #11）：错误文案列出三态并
        // 说明 loop 的语义（回边/图上不连/由引擎显式驱动），防 LLM 猜。
        Left(
          s"invalid out mode ':$mode' in '$seg' — mode ∈ {result, signal, loop}; " +
            "':loop' = control edge (the fail-routed re-run edge: declared in out, not part of the DAG, " +
            "never settles the barrier — the engine drives it)"
        )
      else
        // 2026-09-12 裁定 1（R1-a）——Nebula 边两态分叉（隐式门 = 出口标记）：
        //   隐式门（bare "Nebula"）→ on={pass} + mode=signal：**零投递、只记账**
        //     （nebulaDelivery/deliverFailed 走既有 Signal 分支 ⇒ 不投 root，亦不补投）。
        //     该分支连写的 :mode 被出口标记语义整体覆盖——裁定 1 明文「显式回传 = 写字面
        //     门集形态」，隐式门不是声明面（要通知必须补门集）。
        //   显式门集（"(pass)Nebula" / "(pass,failed)Nebula"）→ 按声明门 + 声明 mode
        //     （缺省 result）真投递 = 通知声明。
        // NebulaDefaultOn 不再经本路径：它只服务存量读路径（fromLegacyString /
        // OutEdge.nebula）——两处语义自此分叉（见 ProjectTypes.OutEdge.NebulaDefaultOn 注释）。
        if target == OutEdge.RootTarget && gatesImplicit then
          Right(OutEdge(OutEdge.RootTarget, OutEdge.DefaultOn, OutEdge.Signal))
        else Right(OutEdge(target, declaredGates, mode))
      end if
    }

  end parseOutSegment

  /**
   * in 参数宽容解析（修复次因 B）：三形态统一接受——
   * ① 原生 JSON 数组 ["a","b"]；② JSON 数组字符串 "[\"a\",\"b\"]"（LLM 常见：
   * 把数组整体字符串化）；③ 逗号串 "a,b"。元素 trim + 空段/空串过滤。
   * 形态②解析失败 → 清晰报错附原文（绝不静默吞成单个字面 id——实证案例 1：
   * 分发器三次接线失败均因 "[\"n-8a481bd0\",...]" 被当单 id → not found）。
   * （原 NodeEditTool 私有方法，提为 NodeTools 公开以便单测。）
   */
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

  /**
   * 悬空提示（W1 = O-B 必做 4，2026-09-12 批）：创建/改接成功结果尾部一行提示——与
   * 既有 `stalledInWarning` **同款形态**（成功结果尾部附 ⚠ 行，非阻断）。判据 = 本次调用
   * 结束态该节点无出边 ⇒ 结果将滞留（零投递、零升根，见裁定第 3 条），接线后自动投递。
   * 0 spawn、零新字段、零持久痕迹（与 W2 的 `wiringGap` 载荷键互为「决策当下可见 /
   * 巡检可见」两面）。
   */
  def wiringGapHint(nodeName: String, nodeId: String): List[String] =
    List(
      s"⚠ node '$nodeName' ($nodeId) has NO out edge — its result will be retained on the node (zero delivery, no root notify). " +
        "Wire an out edge later (NodeEdit out=<target>, or a downstream in=<this node id>) and the retained result is delivered then."
    )

  /**
   * 悬空提示——**已声明**变体（nodegate 方案件 §1(ii) C-ii ① + §7#6：把「忘了」与
   * 「有意」在决策当下区分开）：`dangling=true` 建位的 ⚠ 行带 declared 标记，文案
   * 从「未声明（可疑）」升级为「已声明（预期）」。零新持久字段（wiringGap 派生键
   * 不动，`ProjectTypes` 序列化链零改动）；声明动作另落审计事件 `dangling-declared`。
   */
  def wiringGapHintDeclared(nodeName: String, nodeId: String): List[String] =
    List(
      s"⚠ node '$nodeName' ($nodeId) declared dangling=true — the missing out edge is INTENTIONAL (no delivery, no root notify). " +
        "Its result is retained and is auto-delivered once an out edge is wired. (dangling=true)"
    )

  /**
   * loop 门集不变量（**P-2 强读法**，2026-09-12 裁定 2/3）——判定单点（纯函数）。
   *
   * 判据（写死）：`loop.exists(_.enabled)`（与引擎实际路由键 `NodeEngine.spawnAndRun`
   * 的 `node.loop.exists(_.enabled)` 同源，**不是** `loop.isDefined`）∧ 最终边集非空 ∧
   * 未**同时覆盖** pass 与 failed 两腿 ⇒ 拒。**空 out 豁免**（out 可空置：结果留在
   * result，接线后再投递）——否则与「out 可空置」裁定直接冲突（该形态下 P-2 不可判，
   * 是已接受的不可判窗口）。**存量零回溯**：只在写路径入口判定（新建/改接），
   * 落盘加载路径（`Decoder[List[OutEdge]]` / `fromLegacyString`）一行不改。
   *
   * 为何 loop 单独收紧：loop 是唯一「两腿终态都由引擎内部决定」的节点类型（PASS 由
   * 内部 verify 裁决、FAIL 由轮帽/执行层失败触发）⇒ out 门集是它**唯一**的对外意图
   * 声明面，隐式/单腿门会让「PASS 要不要升根 / 达 K 轮失败要不要升根」在拓扑上不可读。
   *
   * @return Some(可行动错误) = 拒；None = 合规。
   */
  def loopGateViolation(loopEnabled: Boolean, name: String, finalOut: List[OutEdge]): Option[String] =
    if !loopEnabled || finalOut.isEmpty then None
    else
      val edges = OutEdge.canonical(finalOut)
      val hasPass = edges.exists(_.on.contains(OutEdge.Pass))
      val hasFailed = edges.exists(_.on.contains(OutEdge.Failed))
      if hasPass && hasFailed then None
      else
        val missing =
          if hasPass then "'failed'" else if hasFailed then "'pass'" else s"'pass'+'failed'"
        Some(
          s"loop node '$name' must route BOTH legs — its out edges must cover 'pass' and 'failed' (missing: $missing). " +
            "A loop's PASS is decided by its inner verify and its FAIL by the round cap / execution failure, so a bare or " +
            "one-legged route leaves one outcome invisible: write an explicit gate set, e.g. out: \"(pass,failed)<target>\" " +
            "(one edge, both legs) or \"(pass)<target>, (failed)<fallback>:signal\". Leaving out EMPTY is legal (result " +
            "retained, delivered when wired). (NODE_LOOP_GATE_INCOMPLETE)"
        )

  /** NodeDef 形态的重载（写路径入口用）。 */
  def loopGateViolation(node: NodeDef, finalOut: List[OutEdge]): Option[String] =
    loopGateViolation(node.loop.exists(_.enabled), node.name, finalOut)

  /**
   * 镜像追加路径（下游 `in:` 声明 ⇒ 上游 out 追加缺省 pass 边）的 loop 门集预检
   * （裁定 3 明文要求覆盖的面：该路径不经 outJson 校验点，故必须在**写路径**上判）。
   *
   * 复算方式：逐条调用**真实的落盘收敛函数** `appendEdgeTo` 得到追加后的上游边集再判
   * ——不复制其条件（单一真相源，避免「幂等不追加」分支被镜像成第二套语义）。归档
   * 上游不在活动区 Map ⇒ `appendEdgeTo` 恒为 no-op（与 create 事务内行为一致）。
   *
   * @return Some(错误) = 拒（调用方必须在**任何写之前**拒绝整调用 ⇒ 不落库/不落边/零残留）。
   */
  def loopGateForAppends(nodes: Map[String, NodeDef], upIds: List[String], toNodeId: String): Option[String] =
    upIds.distinct.iterator
      .flatMap { upId =>
        NodeTools.appendEdgeTo(nodes, upId, toNodeId).get(upId).flatMap(up => loopGateViolation(up, up.out))
      }
      .nextOption()

  /**
   * `NODE_VERIFIER_NEEDS_ROUTE` 可行动文案单点（nodegate 方案件 §1(i) D2 裁定：
   * 两腿同码——缺陷语义相同（verifier 无 fail 路由），码名描述缺陷不描述相位，
   * 下游分发器自纠只需认一个码）。
   *
   * @param via 相位后缀：自身边集腿为空串；镜像追加腿附「被下游 in= 追加」语境。
   */
  private def verifierNoRouteMsg(nodeName: String, via: String): String =
    s"verifier node '$nodeName' has no fail route — a verifier MUST declare exactly one '(fail)<worker>:loop' edge, otherwise " +
      "a rejected target can never be re-run (the loop silently degrades to a single pass). Write out=\"(pass)<landing>, " +
      "(fail)<worker>:loop\"." + via + " (NODE_VERIFIER_NEEDS_ROUTE)"

  /**
   * 镜像追加路径的 verdict 选通预检（nodegate 方案件 §1(i) 镜像腿，本批四项③）：
   * 下游 `in:` 声明会给每个上游 out 追加一条缺省 pass 边——上游若是 verifier 且
   * 追加后 fail 路由仍为空 ⇒ 拒（否则「pending verifier 被下游补 pass 边」立即
   * 变成「有边无 fail 路由」= 缺陷形态本身，正是 n-b9faa304 形态的成因通道）。
   *
   * **镜像腿不豁免**（方案件 §1(i) C-i ①）：`verifierRoutePending=true` 只放行
   * 创建期自身空 out 面；本函数没有 pending 参数——pending verifier 想接下游，
   * 先补挂 `(fail)<worker>:loop`（解除 = 派生，挂上即自然解除）。
   *
   * 复算方式与 `loopGateForAppends` 同款：逐条调用**真实的落盘收敛函数**
   * `appendEdgeTo` 得到追加后的上游边集再判（单一真相源，幂等分支零复制）。归档
   * 上游不在活动区 Map ⇒ `appendEdgeTo` 恒为 no-op。
   *
   * @return Some(错误) = 拒（调用方必须在**任何写之前**拒绝整调用 ⇒ 不落库/不落边/
   *   零残留）。
   */
  def verdictRouteGateForAppends(nodes: Map[String, NodeDef], upIds: List[String], toNodeId: String): Option[String] =
    upIds.distinct.iterator
      .flatMap { upId =>
        NodeTools.appendEdgeTo(nodes, upId, toNodeId).get(upId).flatMap { up =>
          val edges = OutEdge.canonical(up.out)
          val failEdges = edges.filter(_.on.contains(OutEdge.Fail))
          if NodeRoles.normalize(up.role) != NodeRoles.Verifier || failEdges.nonEmpty then None
          else
            Some(
              verifierNoRouteMsg(
                up.name,
                " The route-less verifier was being wired as an UPSTREAM by a downstream in= declaration — declare the " +
                  "fail route on it first (NodeEdit out=\"(fail)<worker>:loop\"), or wire this downstream elsewhere."
              )
            )
        }
      }
      .nextOption()

  /**
   * 事务内设 out 边集（单权威，P1 多边版）：被移除边目标 in 移除 + 新增边目标 in
   * 追加 + 本节点 out 替换（canonical 规范化落库）。同 to 保留边不重触发 in 写
   * （幂等）。归档感知（fix a「新建边→归档上游」修复 20260903）：from 在归档区
   * 时——活动区侧照常做目标 in 增删，from.out 补写归档区（mutateArchive；归档节点
   * 不可复活，仅元数据保持单权威一致）；旧目标也在归档区时其 in 不回写（归档 in
   * 表是死数据，避免跨区二跳写）。
   *
   * **loop 门集不变量（裁定 3 的落盘收敛点）**：写入前用 `loopGateViolation` 复查最终
   * 边集——违规即**拒写**（ERROR 留痕，本函数是写路径单权威，防未来新调用方绕过前置
   * 预检）。正常路径不可达：创建/改接的**前置预检**（`loopGateForAppends` + 改接侧
   * self 预检）已用同一判据在任何写之前拒绝整调用 ⇒ 零残留。
   */
  def setOut(
    rt: ProjectRuntime,
    fromId: String,
    newOut: List[OutEdge],
    pendingOut: Option[List[OutEdge]] = None
  ): IO[Unit] =
    val newEdges = OutEdge.canonical(newOut)
    def rewire(nodes: Map[String, NodeDef], from: NodeDef): Map[String, NodeDef] =
      // in 镜像记账按解析后的节点 id 做（20260909 in 丢失事故修复）：目标串有 id/名字
      // 两种形态，removed/added 的 diff 必须在「节点身份」空间进行——按原始串 diff 时，
      // 「旧 id 形态边 → 新名字形态边指向同一节点」会被误判为改接他点，removed 侧把
      // 下游 in 抹掉、added 侧又因名字查 Map MISS 而漏记（净效应 = in 蒸发）。
      //
      // **红线①（nrloop 一期 2026-09-12，设计 §3.3 #15 / 附 C2-R5）**：`mode==loop`
      // 的控制边**不写 in 镜像**——回边若照写镜像，worker 的 in 会含 verifier ⇒
      // round-1 的 in-barrier 永不归零 ⇒ 第一次就死锁（loop 边不是「谁被谁喂输入」，
      // 它是选通信号）。两侧（removed/added）都过滤，且 `afterAdded.updated` 仍写 out。
      val oldIds = from.out
        .filterNot(OutEdge.isLoopEdge)
        .map(_.to)
        .filterNot(_ == OutEdge.RootTarget)
        .distinct
        .flatMap(OutEdge.resolveTargetId(nodes, _))
        .distinct
      val newIds = newEdges
        .filterNot(OutEdge.isLoopEdge)
        .map(_.to)
        .filterNot(_ == OutEdge.RootTarget)
        .distinct
        .flatMap(OutEdge.resolveTargetId(nodes, _))
        .distinct
      val afterRemoved = oldIds
        .diff(newIds)
        .foldLeft(nodes)((acc, tid) =>
          acc.get(tid).map(tn => acc.updated(tid, tn.copy(in = tn.in.filterNot(_ == fromId)))).getOrElse(acc)
        )
      val afterAdded = newIds
        .diff(oldIds)
        .foldLeft(afterRemoved)((acc, tid) =>
          acc.get(tid).map(tn => acc.updated(tid, tn.copy(in = (tn.in :+ fromId).distinct))).getOrElse(acc)
        )
      afterAdded.updated(fromId, from.copy(out = newEdges, pendingOut = pendingOut.getOrElse(from.pendingOut)))
    end rewire
    def sinkGuard(node: NodeDef): Option[String] = NodeTools.loopGateViolation(node, newEdges)
    rt.store.getNode(fromId).flatMap {
      case Some(from) =>
        sinkGuard(from) match
          case Some(err) =>
            IO(logger.errorSync(s"[loop-gate] setOut REFUSED (write skipped, sink invariant): $err"))
          case None =>
            rt.store.mutate { s => s.copy(nodes = rewire(s.nodes, s.nodes(fromId))) }.void
      case None =>
        rt.store.findNode(fromId).flatMap {
          case None => IO.unit // 两区皆无（并发 TTL 迁移已删）→ no-op
          case Some(archFrom) =>
            // 归档分支：活动侧做目标 in 增删（rewire），但 rewire 会把 from 按
            // 活动节点回插（updated(fromId, …)）——归档原件必须剔除，不复活进活动区
            // （历史损伤 n-52670d20 形态回归面：NodeEdgeRepairSpec ③）。
            sinkGuard(archFrom) match
              case Some(err) =>
                IO(logger.errorSync(s"[loop-gate] setOut REFUSED (archive write skipped, sink invariant): $err"))
              case None =>
                rt.store.mutate { s => s.copy(nodes = rewire(s.nodes, archFrom) - fromId) }.void *>
                  rt.store
                    .mutateArchive(a => a.copy(nodes = a.nodes.updatedWith(fromId)(_.map(_.copy(out = newEdges)))))
                    .void
        }
    }

  end setOut

  /**
   * in 声明的边侧镜像（P1 事务内纯函数）：上游 out **追加**指向本节点的缺省 pass
   * 边——上游已有指向本节点的边（任意门控）则保持不变（in 声明不覆盖既有门控声明，
   * 扇出拓扑的其它边零扰动；旧单值世界的「改指」语义由多边追加天然兼容）。
   *
   * **loop 门集不变量面（裁定 3）**：本函数是镜像追加路径的落盘收敛点，调用方
   * （create 事务 / edit 侧 `setOut`）必须在写前用 `loopGateForAppends` 预检——它
   * 直接调用本函数复算追加后的上游边集，故「幂等不追加」分支零复制。
   *
   * **红线①（nrloop 一期 2026-09-12）**：追加的边恒为缺省 pass + result 模式的边（本函数
   * 不产 `:loop` 边）；`(fail)<worker>:loop` 的回边**绝不**经本函数写 in 镜像——回边
   * 由 verifier 的 out 声明承载，镜像面在 `setOut` 侧按 `mode==loop` 过滤（防 round-1
   * 死锁：worker 的 in 若含 verifier ⇒ barrier 永不归零）。
   */
  def appendEdgeTo(nodes: Map[String, NodeDef], upId: String, toNodeId: String): Map[String, NodeDef] =
    nodes.get(upId) match
      case Some(up) if !up.out.exists(_.to == toNodeId) =>
        nodes.updated(upId, up.copy(out = up.out :+ OutEdge(toNodeId)))
      case _ => nodes

  /**
   * P1 校验层①（spec §2.2，wf3 §3.7 护栏）：指向 merge 节点的 on-failed 边 → 硬拒
   * （NODE_MERGE_PASS_ONLY）。merge 触发语义 = 全部上游 completed（in-barrier）；
   * 上游 failed 由 D5+MergeNodePolicy 转 blocked 可见终态——failed 门控入边与该语义
   * 矛盾，创建/改接期 0 spawn 拒绝。运行期兜底（deliverFailed merge 分支与门控无关）
   * 原样保留为纵深（覆盖校验生效前落盘的旧拓扑）。返回首个违规的错误文本。
   */
  def ensureMergePassOnly(rt: ProjectRuntime, edges: List[OutEdge]): IO[Option[String]] =
    edges
      .filter(e => e.on.contains(OutEdge.Failed) && e.to != OutEdge.RootTarget)
      .toList
      .traverse { e =>
        // 目标按解析后 id 查（20260909 修复面：名字形态的 failed 边不再绕过本门控）
        NodeTools.resolveOutTarget(rt, e.to).flatMap {
          case None => IO.pure(None)
          case Some(tid) =>
            rt.store.getNode(tid).map {
              case Some(t) if MergeNodePolicy.isMerge(t) =>
                Some(
                  s"out edge '(failed)${e.to}' targets a merge node — on-failed edges into a merge node are rejected: a merge fires only when ALL upstreams complete, and upstream failure already converts it to a visible blocked state (D5). Use a pass-only edge, or signal a non-merge fallback node instead. (NODE_MERGE_PASS_ONLY)"
                )
              case _ => None
            }
        }
      }
      .map(_.flatten.headOption)

  /**
   * **verdict 选通校验族**（nrloop 一期 2026-09-12，设计 §3.3 #13 + §3.4；
   * 全部 **0 spawn** —— 创建/改接期拒绝，节点不落库、上游零改边）。
   *
   * 判据（逐条，返回首个违规的可行动错误；`Some` = 拒）：
   *  1. `NODE_VERDICT_GATE_ON_TASK_NODE`：`fail` 门 / `:loop` 边出现在 `role=task`
   *     的节点上；或 `failed` 门出现在 `role=verifier` 的节点上（两个方向都是「门
   *     语义与角色不符」）。
   *  2. `NODE_LOOP_EDGE_ROLE`：`fail` 门与 `:loop` 模式**必须成对**（`fail` 门只经
   *     控制边驱动、`:loop` 边只承载 verdict）；目标必须**存在**（悬空回边 = 静默
   *     不生效）且不得自指；`role=verifier` 的节点**不得**同时是旧式 loop 节点
   *     （`loop=true` 的节点内 verify 会话已天然是校验方，节点级 role 与之冲突）。
   *  3. `NODE_VERIFIER_NEEDS_ROUTE`：`role=verifier` 且 fail 选通边为空 ⇒ 拒（否则
   *     loop 静默退化为单次：verifier 报不了 fail，回边永不触发）。nodegate 方案件
   *     §1(i)（本批四项③）把**空 out 逃逸通道一并关闭**——原实现的 `edges.nonEmpty`
   *     前置使空 out verifier 建位/编辑恒放行（注释自陈「空 out 是合法形态，不在此
   *     判」正是逃逸口）；现取「空 out 且创建期显式声明 `verifierRoutePending=true`」
   *     为唯一放行形态（C-i 令牌，见 [[verifierRoutePending]] 参数），编辑面与镜像
   *     追加面不豁免（编辑面本调用 `routePending=false`；镜像腿见
   *     [[verdictRouteGateForAppends]]）。
   *  4. `NODE_LOOP_TARGET_NEBULA`：回边目标不得是 "Nebula"（回边是节点间控制信号，
   *     不是上报通道）。
   *  5. `NODE_VERIFY_MULTI_FAIL_TARGET`：v1 限**恰一条** fail 回边目标（扇出回边
   *     语义不清：哪个目标被重跑？）。多目标请在二期由 verifier 的多 pass/fail
   *     分支表达，勿用多条回边。
   *  6. `NODE_VERDICT_ROUTE_COLLISION`：verifier 的 pass 目标集与 fail 目标集不得
   *     相交——`OutEdge.canonical` 按 `(to, mode)` 合并门集，同目标两条腿会被并成
   *     一条（两条都触发），选通语义静默失效 ⇒ 硬拒。
   *  7. `NODE_RETRY_LOOP_CONFLICT`：回边目标**不得**同时配 `retry`（设计 §3.5）——
   *     两条自动重跑腿都会清 `deliveredTo`，并发命中会打乱 barrier 账本；v1 拒绝
   *     同节点既为 loop 目标又有 retry。
   *
   * 与批 A 判据共存（硬口径）：本函数**不触碰** `loopGateViolation`（旧式 loop 节点
   * 的两腿覆盖不变量）——两者是并列的写前校验，互不覆盖、互不放宽；本函数只看
   * 角色 × verdict 门 × 控制边这一族，且对旧式 loop 节点仅在「role=verifier」这一
   * 冲突形态上发声（见第 2 条第 3 分句）。
   *
   * @param selfId  本节点 id（自指回边判定；创建期 = 预分配的新 id）
   * @param role    生效后的角色（未传 = 既有 role / 缺省 task）
   * @param legacyLoopEnabled 生效后的旧式 loop 开关（`loop.exists(_.enabled)`）
   * @param routePending C-i 令牌（nodegate 方案件 §1(i)，默认 false）：创建期显式
   *   声明「verifier 暂无 fail 路由」（create-only 参数 `verifierRoutePending=true`）
   *   ⇒ **仅**「空 out」形态放行（非空 out 仍照判）；编辑面不传（缺省 false）⇒ 空
   *   out verifier 的编辑同码被拒（两腿一致，C-1）。
   */
  def verdictRouteGate(
    rt: ProjectRuntime,
    selfId: String,
    nodeName: String,
    role: String,
    legacyLoopEnabled: Boolean,
    finalOut: List[OutEdge],
    routePending: Boolean = false
  ): IO[Option[String]] =
    val edges = OutEdge.canonical(finalOut)
    val r = NodeRoles.normalize(role)
    val isVerifier = r == NodeRoles.Verifier
    val failEdges = edges.filter(_.on.contains(OutEdge.Fail))
    val loopEdges = edges.filter(OutEdge.isLoopEdge)
    val local: Option[String] =
      if !isVerifier && (failEdges.nonEmpty || loopEdges.nonEmpty) then
        Some(
          s"node '$nodeName' has role=${NodeRoles.Task} but declares a verdict route — the 'fail' gate and the ':loop' mode are " +
            "verifier-only: only a node with role=verifier produces a verdict about another node. Either declare this node " +
            "role=verifier (create-only: it must be set when the node is created) with out=\"(pass)<target>, (fail)<worker>:loop\", " +
            "or drop the fail/:loop edge and report finish/blocked. (NODE_VERDICT_GATE_ON_TASK_NODE)"
        )
      else if isVerifier && edges.exists(_.on.contains(OutEdge.Failed)) then
        Some(
          s"verifier node '$nodeName' declares a 'failed' gate — that gate fires on the NODE STATUS 'failed' (the upstream node's " +
            "own execution failure), which is not part of a verifier's routing: a verifier routes its VERDICT " +
            "(pass/fail about the judged target). Use '(fail)<target>:loop' for the reject leg and '(pass)<target>' for the accept " +
            "leg. (NODE_VERDICT_GATE_ON_TASK_NODE)"
        )
      else if isVerifier && legacyLoopEnabled then
        Some(
          s"verifier node '$nodeName' cannot also be a LoopNode (loop=true) — an in-node loop node already runs its OWN verify " +
            "session (the verifier role belongs to that session), so declaring role=verifier on it is contradictory: the routed " +
            "form is 'separate worker node + separate verifier node'. Keep loop=true with role=task, or model the loop as two " +
            "nodes with role=task / role=verifier and a '(fail)<worker>:loop' edge. (NODE_LOOP_EDGE_ROLE)"
        )
      else if loopEdges.exists(e => !e.on.contains(OutEdge.Fail)) then
        val bad = loopEdges.find(e => !e.on.contains(OutEdge.Fail)).map(_.to).getOrElse("")
        Some(
          s"node '$nodeName' declares the ':loop' mode on target '$bad' without the 'fail' gate — the ':loop' mode is the CONTROL EDGE " +
            "carrier of a verdict: it must be written as '(fail)<target>:loop' (fail gate + loop mode are a pair). A plain pass " +
            "edge with :loop has no defined semantics. (NODE_LOOP_EDGE_ROLE)"
        )
      else if failEdges.exists(e => !OutEdge.isLoopEdge(e)) then
        val bad = failEdges.find(e => !OutEdge.isLoopEdge(e)).map(_.to).getOrElse("")
        Some(
          s"node '$nodeName' declares the 'fail' gate on target '$bad' without the ':loop' mode — a fail verdict must be routed " +
            "along a control edge: write '(fail)<target>:loop'. (Without :loop the edge would settle the target's input barrier " +
            "like a normal out edge, which is exactly the deadlock the control-edge form prevents.) (NODE_LOOP_EDGE_ROLE)"
        )
      else if isVerifier && failEdges.isEmpty && !(edges.isEmpty && routePending) then
        Some(verifierNoRouteMsg(nodeName, ""))
      else None
    local match
      case Some(err) => IO.pure(Some(err))
      case None if failEdges.isEmpty => IO.pure(None)
      case None =>
        val raw = failEdges.map(_.to)
        if raw.exists(_ == OutEdge.RootTarget) then
          IO.pure(
            Some(
              s"node '$nodeName' routes its 'fail' verdict to \"Nebula\" — a fail route is an inter-node CONTROL edge (the target " +
                "re-runs), not a report channel. Report the verdict to the root by adding the root notification to a node edge " +
                "('(pass)Nebula') if you need one. (NODE_LOOP_TARGET_NEBULA)"
            )
          )
        else
          raw.distinct.traverse(t => resolveOutTarget(rt, t).map(t -> _)).flatMap { pairs =>
            pairs.collectFirst { case (t, None) => t } match
              case Some(missing) =>
                IO.pure(
                  Some(
                    s"node '$nodeName' routes its 'fail' verdict to '$missing' — not a node id nor any node's name in this project. " +
                      "The re-run edge must point at an EXISTING node (the worker that should re-run); create it first, then wire. " +
                      "(NODE_LOOP_EDGE_ROLE)"
                  )
                )
              case None =>
                val ids = pairs.map(_._2.get).distinct
                if ids.contains(selfId) then
                  IO.pure(
                    Some(
                      s"node '$nodeName' routes its 'fail' verdict back to itself — a self re-run edge is not a loop, it is a spin. " +
                        "Point the fail edge at the worker node that produced the judged artifact. (NODE_LOOP_EDGE_ROLE)"
                    )
                  )
                else if ids.size > 1 then
                  IO.pure(
                    Some(
                      s"node '$nodeName' declares ${ids.size} distinct fail targets — v1 allows exactly ONE fail re-run target " +
                        "(with several, which target is re-run is undefined). Use one fail edge; model multiple outcomes as extra " +
                        "pass edges to distinct downstream nodes. (NODE_VERIFY_MULTI_FAIL_TARGET)"
                    )
                  )
                else
                  val passIds = edges
                    .filter(e => e.on.contains(OutEdge.Pass) && !OutEdge.isLoopEdge(e))
                    .map(_.to)
                    .filterNot(_ == OutEdge.RootTarget)
                    .distinct
                  passIds.traverse(t => resolveOutTarget(rt, t).map(o => o.getOrElse(t))).flatMap { pids =>
                    if pids.exists(ids.contains) then
                      IO.pure(
                        Some(
                          s"node '$nodeName' routes BOTH its pass leg and its fail leg to '${ids.head}' — the two edges merge " +
                            "(canonical merges same (target,mode) edges) and the verdict routing silently disappears. Point the pass " +
                            "leg at a different node (e.g. the landing/merge node) than the fail leg (the worker that re-runs). " +
                            "(NODE_VERDICT_ROUTE_COLLISION)"
                        )
                      )
                    else
                      rt.store.findNode(ids.head).map {
                        case Some(t) if t.retry.isDefined =>
                          Some(
                            s"node '$nodeName' routes its 'fail' verdict to '${t.name}' (${t.id}), which also carries a retry policy — " +
                              "both auto-rerun legs clear deliveredTo, so a concurrent hit would corrupt the barrier accounting. " +
                              "Use ONE auto-rerun mechanism per target: drop either the retry policy or this fail edge. " +
                              "(NODE_RETRY_LOOP_CONFLICT)"
                          )
                        case _ => None
                      }
                  }
                end if
          }

        end if

    end match

  end verdictRouteGate

  /**
   * P1 校验层②（spec §2.2，wf3 §3.7 护栏）：下游持 in 边而上游已 failed 且上游
   * 无指向本下游的 on-failed 边、下游非 merge → WARNING 级提示（**不阻断**——
   * 「愿意人工兜底」合法；死锁持续可见性由既有 mount-stalled 事件承载）。触发面 =
   * NodeEdit（create/edit）对最终 in 集的声明式复检：仅上游当前已 failed 时提示
   * （死锁是活的真实形态，正常 pass-only 拓扑零噪音）。返回提示行列表（空=无）。
   */
  def stalledInWarning(
    rt: ProjectRuntime,
    nodeId: String,
    nodeName: String,
    finalIn: List[String],
    isMerge: Boolean
  ): IO[List[String]] =
    if isMerge || finalIn.isEmpty then IO.pure(Nil)
    else
      finalIn.distinct
        .traverse { upId =>
          rt.store.findNode(upId).map {
            case Some(up)
                if up.status == NodeLifecycle.Failed
                  && !up.out.exists(e => e.to == nodeId && e.on.contains(OutEdge.Failed)) =>
              Some(
                s"⚠ upstream '$upId' ('${up.name}') has FAILED and no on-failed edge reaches this node — its in-barrier can never clear (stays wiring; mount-stalled will keep reporting it until the upstream is fixed and reactivated). Proceeding is fine if manual fallback is intended."
              )
            case _ => None
          }
        }
        .map(_.flatten)

  /**
   * 通知策略声明自检（**WARNING 族**，b64 批 2026-09-13；形态仿 [[stalledInWarning]]：
   * 成功结果尾部附 ⚠ 行，**非阻断**）。三条判据（spec §4.2 + 作者裁定 M3）：
   *
   *   ① **M3（作者裁定「只警告，不拦」）**：`silent` ∧ **链末端**（out 里没有任何
   *      「可解析为节点的目标」——仅 Nebula 边 / 悬空名 / out=Nil 都算末端，D7 口径
   *      与 `FlowMapStore.topologicalChains` 的 `hasNodeTarget` 同源）。语义 = 该节点
   *      是链的收口位，自身静默 + 下游无节点 ⇒ 完成事实在根面**完全没有**节点级入口，
   *      只能等链归档时的链摘要。**不动引擎投递、不新增错误码、不硬拒**。
   *   ② `silent` ∧ failed：spec §4.2「`silent` ∧ failed | 不阻断（failed 由引擎恒定回
   *      分发器）但校验期 WARNING 提示『silent 不豁免 failed』」（R14）。
   *   ③ `root` ∧ out 无 Nebula 边：spec §4.2「WARNING 不阻断：声明 root 但无根出口
   *      ——因链摘要由引擎聚合派发，无 Nebula 边不等于无根可见性」。
   *
   * 返回 ⚠ 行列表（空 = 无告警）。判据纯读（0 spawn、0 写）。
   */
  def notifyPolicyWarnings(
    rt: ProjectRuntime,
    nodeName: String,
    policy: Option[String],
    legacyFlag: Boolean,
    finalOut: List[OutEdge]
  ): IO[List[String]] =
    rt.store.snapshot.map { s =>
      val edges = OutEdge.canonical(finalOut).filterNot(OutEdge.isLoopEdge)
      val hasNodeTarget =
        edges.exists(e => e.to != OutEdge.RootTarget && OutEdge.resolveTargetId(s.nodes, e.to).isDefined)
      val rootEdge =
        edges.exists(e => e.to == OutEdge.RootTarget && e.mode == OutEdge.Result && e.on.contains(OutEdge.Pass))
      // 生效策略（缺键 ⇒ legacy 三态：投根 ⇒ root / flag ⇒ dispatcher / else silent）
      val effective = policy.getOrElse(
        if rootEdge then NotifyPolicy.Root
        else if legacyFlag then NotifyPolicy.Dispatcher
        else NotifyPolicy.Silent
      )
      val isChainEnd = !hasNodeTarget
      val lines = List(
        if effective == NotifyPolicy.Silent && isChainEnd then
          Some(
            s"⚠ notify=silent on a CHAIN-END node ('$nodeName': no out-edge resolves to a node) — its completion will reach the root ONLY through the chain summary emitted when the chain is archived, and a single-member (isolated) chain emits NO summary at all. Declare notify=root if this node's result must be seen as it completes. (warning only — nothing is blocked)"
          )
        else None,
        if effective == NotifyPolicy.Silent then
          Some(
            s"⚠ notify=silent does NOT exempt failures ('$nodeName'): a failed node always notifies the dispatcher (and an explicit '(failed)Nebula' edge still reports to the root). silent suppresses COMPLETED events only."
          )
        else None,
        if effective == NotifyPolicy.Root && !rootEdge then
          Some(
            s"⚠ notify=root but no root outlet declared ('$nodeName': no '(pass)Nebula' :result edge) — the node itself will not post to the root; chain-level visibility still arrives with the archived chain summary. Add an explicit '(pass)Nebula' out-edge if a per-node root bubble is required."
          )
        else None
      ).flatten
      lines
    }

  // ── 环检测（对外暴露给测试）────────────────────────────

  def wouldCreateCycle(rt: ProjectRuntime, fromId: String, to: String): IO[Boolean] =
    rt.store.wouldCreateCycle(fromId, to)

  /**
   * P2 retry 风暴防护两校验（spec §2.3，0 spawn；批E2）。Some(错误) = 拒绝：
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
   * @param neighbors  持有节点的最终 in ∪ deps（编辑路径 = 应用本次改动后的全集）
   */
  def retryGuard(
    rt: ProjectRuntime,
    holderId: String,
    holderName: String,
    policy: RetryPolicy,
    neighbors: List[String]
  ): IO[Option[String]] =
    if !neighbors.distinct.contains(policy.upstream) then
      IO.pure(
        Some(
          s"retry.upstream '${policy.upstream}' must be an in/deps neighbor of node '$holderName' — " +
            "retry re-runs an upstream whose output feeds this node; declare the in/deps edge first, then set retry. (NODE_RETRY_NEIGHBOR)"
        )
      )
    else
      retryCycleExists(rt, holderId, policy.upstream).map {
        case true =>
          Some(
            s"retry chain cycle: setting retry on '$holderName' → '${policy.upstream}' closes a retry loop " +
              "(B.retry→C and C.retry→B would re-run nodes forever) — retry chains must stay acyclic. (NODE_RETRY_CYCLE)"
          )
        case false => None
      }

  /**
   * retry 环判定（retryGuard ②的载体）：沿 retry.upstream 链行走（候选边视同已
   * 设置），回到持有者即环；访问集防既有数据（手改 flow-map.json）已环时死循环。
   */
  def retryCycleExists(rt: ProjectRuntime, holderId: String, candidateUp: String): IO[Boolean] =
    rt.store.snapshot.map { s =>
      def nextOf(id: String): Option[String] =
        if id == holderId then Some(candidateUp)
        else s.nodes.get(id).flatMap(_.retry.map(_.upstream))
      def walk(cur: String, visited: Set[String]): Boolean =
        if cur == holderId then true
        else if visited.contains(cur) then false
        else
          nextOf(cur) match
            case Some(n) => walk(n, visited + cur)
            case None => false
      walk(candidateUp, Set.empty)
    }

  /** task 文本归一化（loop detect 用）：trim + 空白折叠。 */
  def normalizeTask(t: String): String = t.trim.replaceAll("\\s+", " ")

  /**
   * loop detect（§2.6）：同 agent + 同 task 归一化，活动区已有
   * running/completed 节点 → 疑似重复派发（TTL 窗口内 = 活动区仍显示）。
   * 返回匹配的节点（无则 None）。NodeEdit 创建入口节点时校验（0 spawn）。
   * R1 复核（blocked 反馈重入设计 §6）：Terminal 含 blocked——blocked 节点
   * **应**计入重复派发（防对同一任务重复派发；blocked ≠ 可重派）。
   */
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

  /**
   * wiring 变更最终态事件（子任务：Flow Map 事件推送补全）：受影响节点逐一读
   * store 发 nodeUpdated（NodeList 同构 payload）——上游 out 改指 / 旧新目标 in
   * 增删 / 本节点 in·out 变更都从这里走，事件与 NodeList 数据源一致。
   * 活动区已消失（TTL/归档）节点不发——归档节点不在 Flow Map 视图内。
   */
  def emitWiringUpdates(rt: ProjectRuntime, nodeIds: List[String]): IO[Unit] =
    nodeIds.traverse_ { id =>
      rt.store.getNode(id).flatMap {
        case Some(n) => rt.engine.emitUpdated(n)
        case None => IO.unit
      }
    }

  // ── 链成员制面（chainmodel 批一 ①③⑤；判据单点全部在 FlowMapStore）─────────

  /**
   * **链归属变更事件单点**（chainmodel 批一 ⑤；设计件取证 6 的「最硬未决项」：
   * 事件流 44 类里零该类型，跨链并合/拆分只能靠人写的 `node-message` 正文回溯）。
   *
   * 判据（机械、零启发式）：写操作**前后各取一次**「有效链归属视图」
   * （[[FlowMapStore.chainIdView]] = 与载荷 `chainId` 条件键、[[FlowMapStore.chainIdIn]]
   * 同一判据，禁第二判据）并逐节点比对 —— 视图全集 = 两快照节点 id 并集（写后新增
   * 的节点也在内）。**归属视图变化**或**本节点声明态变化**任一成立 ⇒ 一条
   * [[FlowMapEventLog.ChainMembershipChangedType]]。
   *
   * 为什么按「全节点」而非调用点给的受影响集比对：链 id 是**分量级**派生量——新增一条
   * 边可能把两个既有分量并成一条链（id 归并为最早 createdAt 者的 id），此时**每个**原
   * 成员节点的链号都变了，而它们都不是本次写动作「点名」的节点。少比对 = 归属变更静默
   * （正是本批要消灭的形态）。
   *
   * 三项原因（`reason`，与 [[FlowMapEventLog.chainMembershipChangedSummary]] 同值域）：
   *   - `declaration`：本节点由未声明 → 声明（旧链号可能缺省也可能有派生值）；
   *   - `re-id`：本节点声明值变更（改号）；
   *   - `fallback`：其余（派生分量重组 / 撤销声明后回落 / 归档引起的派生变化）。
   *
   * best-effort 纪律与同族（`chain-restored` / `chain-archived`）一致：留痕失败**不得**
   * 让已经落库的拓扑写动作失败；但**禁**整条静默吞掉——逐条 handleErrorWith 记 warn。
   * `before` 由调用方在**任何写动作之前**取（`rt.store.combinedNodes`），本方法只读现状。
   */
  def emitChainMembershipChanges(rt: ProjectRuntime, before: Map[String, NodeDef]): IO[Unit] =
    val beforeView = FlowMapStore.chainIdView(before)
    val beforeDecl: Map[String, String] =
      before.flatMap((id, n) => FlowMapStore.declaredChainId(n).map(id -> _))
    rt.store.combinedNodes.flatMap { after =>
      val afterView = FlowMapStore.chainIdView(after)
      val afterDecl: Map[String, String] =
        after.flatMap((id, n) => FlowMapStore.declaredChainId(n).map(id -> _))
      (before.keySet ++ after.keySet).toList.sorted
        .flatMap { id =>
          val oldV = beforeView.get(id)
          val newV = afterView.get(id)
          val oldD = beforeDecl.get(id)
          val newD = afterDecl.get(id)
          if oldV == newV && oldD == newD then Nil
          else
            val reason = (oldD, newD) match
              case (None, Some(_)) => "declaration"
              case (Some(a), Some(b)) => "re-id"
              case _ => "fallback"
            List(
              FlowMapEventLog
                .append(
                  rt.project.workspace,
                  rt.project.name,
                  id,
                  FlowMapEventLog.ChainMembershipChangedType,
                  FlowMapEventLog.chainMembershipChangedSummary(oldV, newV, reason),
                  chainId = newV
                )
                .handleErrorWith(e =>
                  IO(
                    logger.warnSync(
                      s"[${rt.project.name}] chain-membership-changed append failed for $id " +
                        s"(${oldV.getOrElse("-")}→${newV.getOrElse("-")} reason=$reason): ${Option(e.getMessage).getOrElse(e.toString).take(200)}"
                    )
                  )
                )
            )
          end if
        }
        .sequence_
        .void
    }

  end emitChainMembershipChanges

  /** 链引用的 `<id>` 段展示形（空后缀时显示原文，避免报错文本出现空引号）。 */
  private def chainRefShown(ref: String): String =
    val t = FlowMapStore.chainRefTarget(ref)
    if t.isEmpty then ref else t

  /**
   * **`in` 里出现链引用**的可行动报错（chainmodel 批一 ③；`NODE_CHAIN_REF_UNKNOWN`）。
   * 静态判据（不需要看拓扑）：链引用是 deps 专属语法——它是「等」不是「取」，不注入任何
   * 结果，故不可能充当 barrier 输入。禁静默按字面节点 id 解析（那会得到一个永远查无此点
   * 的悬空引用，报错文案还指不到真正原因）。
   */
  def chainRefInInputError(ref: String): String =
    s"'in' cannot carry a chain reference ('$ref') — \"chain:<chainId>\" is a deps-only scheduling gate: it waits for a " +
      "whole chain's completion and never injects a result, so it cannot be a barrier input. Use deps for the chain, or " +
      "name the member node ids you actually need as input. (NODE_CHAIN_REF_UNKNOWN)"

  /**
   * **链引用解析不可达**的可行动报错（③）：`chain:<id>` 的目标链在**给定节点集**上不存在
   * （既不是任何节点的声明链号，也不是任何派生分量的 `chain-<最早成员 id>`）。此时闸
   * **永不满足**（[[FlowMapStore.DepTargets.unknownChainRefs]] 由启动闸 fail-closed 处理），
   * 故写路径必须当场拒（禁静默 no-op = 「零成员即满足」，也禁只挂一条无痕停等）。
   */
  def chainRefUnknownError(ref: String): String =
    s"Unknown chain reference '${chainRefShown(ref)}' in deps: \"chain:<chainId>\" must name a chain that exists — a declared chain id, " +
      "or a derived `chain-<earliest-member-id>` for an undeclared component. As written the gate can never be satisfied (the node " +
      "would wait forever). Read the real ids from NodeList chains[].id. (NODE_CHAIN_REF_UNKNOWN)"

  /**
   * 单条链引用可达性（create/edit 两条写路径共用；判据单点，数据源 = 双区合并集
   * ——与启动闸/停滞面同源；零链引用时零派生成本）。`in` 里的链引用由
   * [[chainRefInInputError]] 静态拒（不经本闸）。
   */
  def chainRefExists(rt: ProjectRuntime, ref: String): IO[Either[String, Unit]] =
    if !FlowMapStore.isChainRef(ref) then IO.pure(Right(()))
    else
      rt.store.combinedNodes.map { combined =>
        val known = FlowMapStore.topologicalChains(combined.values).map(_.id).toSet
        val t = FlowMapStore.chainRefTarget(ref)
        if t.nonEmpty && known.contains(t) then Right(())
        else Left(chainRefUnknownError(ref))
      }

  /**
   * 节点运行后台化（收口③：dispatcher 会话结束语义修复）。
   *
   * startNode/deliverOutTo 会同步等待节点终态（runWithAgent 的 resultDeferred
   * race 无超时，下游链条投递也在该 fiber 顺序推进）。NodeEdit 工具若在分发器
   * turn 的工具 fiber 里直接调用，turn 会被阻塞整个节点运行期（实证
   * dispatcher-95f8434b：诊断节点 35min + 修复节点 19min，单 turn 56min）：
   * 期间零 lastActivity 更新 → TaskStuckWatcher 误判卡死；入口节点被串行化
   * （建 A 等 A 完才建 B）；「节点建完/接线完成」后分发器会话仍 Processing
   * 挂起。fork 到独立 fiber 后工具立即返回——NodeEdit 文档契约「async,
   * non-blocking」的落地。节点失败已在 fiber 内落 store（failNode），此处仅
   * 兜底记日志；分发器取消/结束不影响已派发节点（节点由 NodeCancel 独立管理）。
   */
  def runDetached(rt: ProjectRuntime, what: String)(io: IO[Unit]): IO[Unit] =
    io
      .handleErrorWith(e =>
        logger.error(
          s"[${rt.project.name}] detached node run '$what' failed: ${Option(e.getMessage).getOrElse(e.toString)}"
        )
      )
      .start
      .void

  /**
   * 链拉回包装（链级抽象 P2 · spec §5.3-④）：`restoreChain=true` 时**先**把目标所属
   * 归档链整体拉回活动区、写审计、再执行 `body`（原创建/编辑流）；按需在结果串前加
   * 拉回告知块（未拉回 ⇒ 结果逐字不变，含错误文案——`Either.map` 只作用于成功侧，
   * 错误码断言零影响）。`body` 以 by-value 传入但**执行在拉回之后**（scala `IO` 是
   * 描述——`body` 内的 `store.snapshot` 到 `flatMap` 真正运行时才求值，看到的是拉回
   * 后的活动区）。
   */
  def withChainRestore(
    rt: ProjectRuntime,
    flag: Boolean,
    inJson: Option[Json],
    depsJson: Option[Json],
    nodename: String
  )(body: IO[Either[ToolError, String]]): IO[Either[ToolError, String]] =
    maybeRestoreChains(rt, flag, inJson, depsJson, nodename).flatMap { restored =>
      logChainRestored(rt, restored) *>
        body.map(_.map(r => restoreNotice(restored) + r))
    }

  /**
   * `restoreChain` 旗标的拉回单点（链级抽象 P2 · spec §5.3-①②）。
   *
   * **触发集** = 本次调用的 `in` ∪ `deps` 引用（id 或名字，三形态宽容解析同上游校验）
   * ∪ `nodename`（编辑归档节点本身的情形——拉回后该名在活动区命中，归档节点编辑的
   * 「只放行 out 改接」闸自然让位）。**解析次序**与 findNode / resolveOutTarget 同序：
   * 活动区命中 = 无需拉回（现状语义零变化）；归档区按 id 或按名命中 = 拉回目标；
   * 两区皆无 = 忽略（交给既有「引用不存在」校验报错，不改变其文案）。
   *
   * 旗标 false 或触发集空 ⇒ 零 IO 零动作（`restoreChain` 缺省 = 纯引用，今日行为
   * 逐字不变）。
   */
  def maybeRestoreChains(
    rt: ProjectRuntime,
    flag: Boolean,
    inJson: Option[Json],
    depsJson: Option[Json],
    nodename: String
  ): IO[List[FlowMapStore.RestoredChain]] =
    if !flag then IO.pure(Nil)
    else
      // chainmodel 批一 ③：`deps` 里的 `chain:<id>` 是**跨链引用**（目标链成员集），不是
      // 节点 id ——先滤掉，免得它进去被当「查无此节点」而让拉回的触发集多一个永远不命中
      // 的项（现状是静默忽略，但「链引用被当节点名去归档区找同名」是误导面）。`nodename`
      // 不滤（节点名恰为 `chain:...` 的形态照旧按其本名拉回）。
      val refs = ((NodeTools.parseIn(inJson).getOrElse(Nil)
        ++ NodeTools.parseIn(depsJson).getOrElse(Nil)).filterNot(FlowMapStore.isChainRef)
        ++ List(nodename)).map(_.trim).filter(_.nonEmpty).distinct
      for
        active <- rt.store.snapshot
        archived <- rt.store.archiveSnapshot
        targets = refs.flatMap { r =>
          if active.nodes.contains(r) then None
          else if archived.nodes.contains(r) then Some(r)
          else OutEdge.resolveTargetId(archived.nodes, r)
        }.distinct
        restored <- rt.store.restoreChainsFromArchiveDetailed(targets)
      yield restored

  /**
   * 拉回告知块（链级抽象 P2 · spec §5.3-⑦ 的工具面）：空集 → **空串**（未拉回 ⇒
   * 调用方结果逐字不变）；有拉回 → 每链一行前导块，链 id 可继续用于节点引用与谱系
   * 追踪。工具结果 = 分发器唯一即时反馈面（主图重现是异步 WS 面）。
   */
  def restoreNotice(restored: List[FlowMapStore.RestoredChain]): String =
    if restored.isEmpty then ""
    else
      restored
        .map(c => s"[chain-restored] ${c.chainId} back on the active map (${c.members} node(s))")
        .mkString("", "\n", "\n")

  /**
   * 拉回审计（链级抽象 P2 · spec §5.3）：逐链一条 `chain-restored`——
   * [[FlowMapEventLog.ChainRestoredType]] 的**唯一生产写入点**（此前全仓只有接口点
   * 与消费侧回翻分支，本方法激活之）。`nodeId` = 分量内 createdAt 最早成员（= 链 id
   * 派生源节点，与 `chain-archived` 的 nodeId 口径对称）、summary =
   * [[FlowMapEventLog.chainRestoredSummary]]、顶层 `chainId` 同值——消费者
   * [[DocIndexConsumer]] 据此把 INDEX.md 的链块从「已归档」分区翻回「活跃」分区。
   * best-effort：写失败不阻断拉回与后续创建/编辑（与 chain-archived 写点同纪律）。
   */
  def logChainRestored(rt: ProjectRuntime, restored: List[FlowMapStore.RestoredChain]): IO[Unit] =
    if restored.isEmpty then IO.unit
    else
      rt.store.snapshot.flatMap { s =>
        restored.traverse_ { c =>
          val anchor = c.nodeIds
            .flatMap(s.nodes.get)
            .sortBy(n => (n.createdAt, n.id))
            .headOption
            .map(_.id)
            .orElse(c.nodeIds.headOption)
            .getOrElse(c.chainId)
          FlowMapEventLog
            .append(
              rt.project.workspace,
              rt.project.name,
              anchor,
              FlowMapEventLog.ChainRestoredType,
              FlowMapEventLog.chainRestoredSummary(c.chainId, c.restoredAt, c.members),
              Some(c.chainId)
            )
            .handleErrorWith(e =>
              IO(
                logger.warnSync(
                  s"[chain-restored] audit append failed for ${c.chainId}: ${Option(e.getMessage).getOrElse(e.toString)}"
                )
              )
            )
        }
      }

  /**
   * NodeList 工具 / REST flow-map 端点共用的载荷（nodes/worktrees/chains/meta，
   * §2.2 NodeList 返回结构）。节点序列化统一走 NodePayload.buildNodeJson（与 WS
   * 事件 payload 同构）。
   *
   * statusFilter（观测面上下文经济学批 20260907 裁定⑤a）：可选生命周期枚举多选
   * 过滤——**缺省 None = 全量，输出字节级等于现状**（向后兼容铁律；REST/前端
   * 调用零改动）。命中过滤时 liveness 探测也只对入选 running 节点做。
   *
   * chains 顶层旁挂 + 节点级 chainId / chainIds（链级抽象 P0 · spec §6.2；U1 多链
   * 归属批）：派生单点 = FlowMapStore.topologicalChains（活动∪归档合并集，D8），
   * 旁挂判据 = FlowMapStore.chainVisible ∧ 含活动成员（**chainmodel 批一 ① 起：声明链
   * 不论成员数都进旁挂**、派生分量仍需 ≥2 成员；与节点级 chainId 判据**同源**，禁第二
   * 判据——凡载荷带 chainId 的节点其链条目必在旁挂中，前端 chainId → 链查找恒命中）；
   * 条目形状 {id,title,entries,ends,memberIds}，title 由 FlowMapStore.chainTitle
   * 三级推导下发（前端零派生）；entries/ends/memberIds = 分量全量（含归档成员，
   * spec §6.2「全成员」——主图渲染由前端按节点缓存过滤）。节点级 chainIds 条件键
   * **仅 merge 节点且可达成员链数 ≥2** 带（作者裁定①：多链归属只对合并节点做；普通
   * 节点恒单值 chainId），值 = **主链 id 首项 + 全量成员链**（主链恒首项 = `chainId`
   * 逐字同值；无上限、无降级）。
   * WS 不带链级帧，结构变化由前端对账重拉快照消化。
   */
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
        case None => s.nodes.values.toList.sortBy(_.createdAt)
        case Some(fs) => s.nodes.values.toList.sortBy(_.createdAt).filter(n => fs.contains(n.status))
      liveness <- selected
        .filter(_.status == NodeLifecycle.Running)
        .traverse(n => rt.engine.isRunning(n.id).map(alive => n.id -> alive))
        .map(_.toMap)
      // 排队位次派生（排队位次可见性批 2026-09-14）：纯函数、零副作用，与闸同一单点；
      // 同键多项目（O-1）读数为显示面降级信号（键求值走进程内缓存，稳态零 git 调用）。
      // 排队位次**显示槽**（engine-defects 批 #2/#227 2026-09-15）：与闸**同一判据**
      // （mergeQueueHolders → MergeMutexPolicy.holders + verdict 准入过滤），只把持有者
      // 富化成 {rank 依据(readyAt/createdAt), 是否真在临界区, 为何未点火}。
      mergeQueueSlots = rt.engine.mergeQueueSlotsBatch(s.nodes)
      // 排队**位次**派生（queuepos 批 2026-09-15）：纯函数、零副作用，真源 = SEM-2 次序键
      // `rank=(readyAt,createdAt,id)`（[[MergeMutexPolicy.queuePosOf]] 单点）。与
      // `mergeQueueSlots` 分工：后者 = 闸判据「谁挡着我」，本项 = 队列序「我排第几」。
      mergeQueuePositions = rt.engine.mergeQueuePositionsBatch(s.nodes)
      sameKeyForeignProjects <- rt.engine.sameKeyForeignProjectsNow
    yield
      val now = System.currentTimeMillis()
      // 链派生（合并集分量）+ chainId 条件键注入 + chains 旁挂组装（同源单点）
      val combined = s.nodes ++ arch.nodes
      // chainmodel 批一 ①（判据同源收口）：旁挂判据由写死的「分量成员数 ≥2」改为判据单点
      // [[FlowMapStore.chainVisible]]（**声明链不论成员数都可见**、派生分量仍需 ≥2 成员
      // ——payload 零膨胀口径逐字保留），并保持既有「旁挂仅收含活动成员的链」约束。
      // 为什么必须同源：节点级 `chainId` 条件键（本处 `chainIdByNode`）与 WS/事件面
      // （`chainAttrsOf` → `chainIdIn`）是同一个载荷键的两条生产路径——不同源 = 同一节点
      // 在工具面与事件面报出不同链号（本批 ① 的直接反例）。零新键、零形状变化。
      val chains = FlowMapStore.payloadChains(combined, s.nodes.keySet)
      val chainIdByNode = chains.flatMap(c => c.memberIds.map(_ -> c.id)).toMap
      // U1 多链归属（作者裁定①）：仅 merge 节点、可达成员链数 ≥2 才有值（值 = 主链 id
      // 首项 + 全量成员链；普通节点恒缺席 = 单值 chainId 语义不变）；派生与 chainId
      // 同源（同一份 chains 分量表，禁双端二次派生）。
      // chainmodel 批三 ②：同一单点（FlowMapStore.mergeChainAttrs）一次给出两值——
      // 旧键 chainIds（所属链首项 + 全量成员链，语义逐字保留）与新键 mergeUpstreamChains
      // （本次汇聚的上游链）；不再分别调用两个投影（禁同一节点算两遍入口可达分解）。
      val mergeAttrsByNode = combined.values
        .filter(_.merge)
        .flatMap { n =>
          FlowMapStore.mergeChainAttrs(combined, chains, n.id).map(n.id -> _)
        }
        .toMap
      val chainIdsByNode = mergeAttrsByNode.map { case (id, a) => id -> a.chainIds }
      val mergeUpstreamByNode = mergeAttrsByNode.map { case (id, a) => id -> a.upstreamChains }
      val nodes = selected.map { n =>
        val base = NodePayload.buildNodeJson(
          n,
          now,
          chainIdByNode.get(n.id),
          chainIdsByNode.get(n.id),
          // 排队位次条件键（排队位次可见性批 2026-09-14，案 A）：判据**单点** = 引擎闸
          // 同一函数（[[NodeEngine.mergeQueueHoldersBatch]] → [[NodeEngine.mergeQueueHolders]]
          // → [[MergeMutexPolicy.holders]] + verdict 准入过滤）——🔴 禁前端/分发器复刻，
          // 🔴 禁读文件票层，🔴 禁从事件流回放。只收非空项 ⇒ 未排队节点缺键。
          mergeQueue = mergeQueueSlots.get(n.id),
          // 排队位次条件键（queuepos 批 2026-09-15）：判据**单点** = 引擎
          // [[NodeEngine.mergeQueuePositionsBatch]] → [[MergeMutexPolicy.queuePosOf]]
          // → [[MergeMutexPolicy.rankOf]]（SEM-2 次序真源）——🔴 禁前端/分发器复刻，
          // 🔴 禁读文件票层，🔴 禁从事件流回放。只收非空项 ⇒ 未成队节点缺键。
          mergeQueuePos = mergeQueuePositions.get(n.id),
          // 同键多项目（O-1）当下读数：非空 ⇒ 前端按降级红线只渲染裸「排队中」不渲染
          // 数字（🔴 禁编造数字）；空 ⇒ 位次可信。与既有两个 merge-queue 告警同源单点。
          sameKeyProjects = sameKeyForeignProjects,
          // chainmodel 批三 ② 新增键（本次汇聚的上游链；门控与 chainIds 同源，同缺席）
          mergeUpstreamChains = mergeUpstreamByNode.get(n.id),
          // verifierRoute 条件键（failroute-guard 批 2026-09-21 · 案 A A3）：判据**单点** =
          // NodePayload.verifierRouteInvalid（纯函数；「合法 fail 选通边」算面与运行期
          // NodeEngine.loopRouteTargetId 同源，🔴 禁前端/分发器复刻、禁第二处派生）。
          // 注入活动区快照 ⇒ 仅**拒绝态**的 verifier 带键（值 "lost"）；合法节点与
          // 全部 WS 事件写点（不注入）字段集字节级零漂移。
          nodes = Some(s.nodes)
        )
        liveness.get(n.id) match
          case Some(alive) => base.deepMerge(Json.obj("liveness" -> Json.fromBoolean(alive)))
          case None => base
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
        // ④-11(b)（作者 2026-09-17 12:09 裁定单）：meta 增 `workspace` = 本项目**权威工作区
        // 绝对路径**，供分发器/下游拿权威路径（根治「按项目名猜路径」——事故链：
        // 分发器按 name 拼 `<dataRoot>/projects/<name>/AGENTS.md` 而真源 workspace 是别处，
        // 见 .nebflow/reports/20260917_pcsys-mech-design.md 项2 基线 8）。
        // 🔴 只**新增**键：既有键 `project` / `updatedAt` / `archived` 的键名与类型语义零改动
        // （后端新增键对全部既有消费者是向后兼容新增，容忍读数见实施批报告 A5 节）。
        "meta" -> Json.obj(
          "project" -> rt.project.name.asJson,
          "workspace" -> rt.project.workspace.asJson,
          "updatedAt" -> s.updatedAt.asJson,
          "archived" -> arch.nodes.size.asJson
        )
      )

end NodeTools
