package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*
import io.circe.{Json}

import nebflow.core.entity.BuiltinAgents
import nebflow.core.project.*

/**
 * ChainWrite —— **整链一次登记**（N1 刀2 · 设计 v3 §5/S4；⑴′ 代裁＝独立件，不塞
 * 既有工具件）。
 *
 * == 语义（v3 §5 逐字落位）==
 * 一次调用登记**节点＋deps＋任务＋描述**整链进**调用 delegate 自己的作用域图**
 * （per-delegate flowmap，刀1 作用域族）；校验纯函数（非空/≤12/id 唯一/name 唯一/
 * name×id 不撞/deps 闭合/Kahn 无环）任一不过＝**整链显式拒绝、零落盘零 spawn**
 * （all-or-nothing）。校验全过 → FlowMapStore **单事务原子落盘**（write-through＝
 * 「链齐」物证 R1）→ ChainLedger 链号出生（reconcile 单点）→ 全链 pending **零
 * spawn** → **放行点**：入口节点（deps 空）startNode（后台 fork，工具即时返回——
 * 非阻塞）。中段节点由引擎既有机制驱动（上游完成 → settleDeps / 投递 barrier 结算
 * ——执行核零改动，§10.2 不动面）。
 *
 * == 与 FlowMap 底座的映射（单一真相源）==
 *   - `id`   → `NodeDef.id` 逐字（登记 token＝地址；字符集契约与链号同款——id 进
 *     载荷键与派生链号 `chain-<最早成员 id>` 的归档批文件名，禁路径分隔符/空白/`..`）；
 *   - `name?` → `NodeDef.name`（人读地址；R2 唯一域＝本作用域**活动区**；R4 id/name
 *     两空间不相交——name 不得等于任何既有节点 id）；
 *   - `deps` → 规范化为 id 形（R6：批内 id／批内 name 皆收，链外仅收 id）后落
 *     `NodeDef.deps`，并**综合接线**：上游 out 追加指向下游的缺省 pass 边 ＋ 下游
 *     in 镜像——deps 的「等完成」与结果的「沿边投递」由底座同一套边语义承载
     * （AgentFlow/ChainWrite 步进会话吃到上游结果块，数据流不因登记面而分叉）；
 *   - 末端节点（批内无下游依赖者）自动补显式门集根边 `(pass)Nebula`（:result）＝
 *     完成通知投**创建者 delegate 本人**（自根作用域——刀1 H5 已实证的投递面；
 *     非隐式 bare 形态，v3-⑨ 荐读法）。中段节点不挂根边（零噪音）。
 *   - `chainName?` → 全员显式 `chainId` 声明（声明即归属，chainmodel 批一语义原样；
 *     在飞链集内唯一）；缺省 → 自动 `flow-<8hex>`。链号出生经
 *     `store.reconcileChainLedger`（TtlTick 台账腿同一单点）同步落账。
 *
 * == 授权面（A4，双保险）==
 * 挂载面＝`buildAllowedToolSet` 尾段 `delegateSession` 腿（仅 delegate 会话见本件；
 * 插件声明不授能——`RootExclusiveTools` 防逃逸）；工具内第二保险＝ctx 会话身份
 * 必须 `delegate-` 前缀且宿主在册（fail-closed），作用域只认会话身份，**零**
 * `project` 参数（传即拒——作用域归属不可由客户端参数伪造）。
 *
 * 🔴 错误码族＝v2 §6.I 承继＋本刀新增（`Codes` 单点，spec 断言共用）：全部拒绝
 * 零副作用（无行、无节点、无 spawn）。
 */
object ChainWriteTool extends Tool:
  val name = "ChainWrite"

  /** 规模上限（AgentFlow/ChainWrite 同一上限——防误用对称；v2 §6.A/§6.B 家族口径）。 */
  val MaxNodes: Int = 12

  /** 错误码族单点（v2 §6.I 承继＋刀2 新增；spec 断言禁字面量）。 */
  object Codes:
    val Empty = "CHAIN_EMPTY"
    val TooMany = "CHAIN_TOO_MANY_STEPS"
    val DuplicateId = "CHAIN_DUPLICATE_ID"
    val IdInvalid = "CHAIN_ID_INVALID"
    val NameInvalid = "CHAIN_NAME_INVALID"
    val NameConflict = "CHAIN_NAME_CONFLICT"
    val NameIdCollision = "CHAIN_NAME_ID_COLLISION"
    val AddrAmbiguous = "CHAIN_ADDR_AMBIGUOUS"
    val NodeNotFound = "CHAIN_NODE_NOT_FOUND"
    val DepsUnknown = "CHAIN_DEPS_UNKNOWN"
    val Cycle = "CHAIN_CYCLE"
    val TaskRequired = "CHAIN_TASK_REQUIRED"
    val NotDelegate = "CHAINWRITE_SCOPE_FORBIDDEN"
    val ScopeUnavailable = "DELEGATE_SCOPE_UNAVAILABLE"
  end Codes

  val description =
    """Create a whole task chain in ONE call inside YOUR OWN scope flow map (one delegate, one map) and run it: `nodes` = [{id, name?, task, deps?, description?}] — register every node, its dependencies and its brief together; the whole chain is validated first (unique ids, unique names, deps closure, acyclic) and ANY failure rejects the WHOLE call — nothing runs, nothing is recorded. On success the chain lands atomically, gets a chain id, and the ENTRY nodes (no deps) start immediately; later nodes start as their upstreams complete. Results of end nodes arrive in this session as node completion notifications.

**Parameters:**
- `nodes` (required): the whole chain — id: short unique token (the node's address; letters/digits/'.'/'-'/'_' , <=120 chars); name: optional human address (unique among your scope's active nodes, never equal to an existing node id; wherever a node is addressed, id AND name are both accepted); task: self-contained brief (what done looks like); deps: node ids (or in-chain names) that must finish first — the engine wires the edges, so every step receives its upstream results; description: optional one-liner per node (default: first line of the task).
- `chainName` (optional): id-safe label, also the chain's id (unique among your active chains). Omit = auto id.

**Rules:**
- Do not create a partial chain and "add the rest later" — register the complete chain, or build node-by-node with NodeEdit.
- Re-running a failed branch = submit a fresh chain containing the failed leg (chains are append-only; no in-place rerun). Cancel a running chain with NodeCancel(chain=<chainId>); read it with NodeList (chains[]).
- Non-blocking: the ack carries the chain id — do NOT wait idle and do NOT re-dispatch the same work."""

  def inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "nodes" -> io.circe.Json.obj(
          "type" -> "array".asJson,
          "description" -> "The whole chain: [{\"id\": \"survey\", \"name\"?: \"Survey\", \"task\": \"...\", \"deps\"?: [\"...\"], \"description\"?: \"...\"}]. deps omitted = entry step.".asJson,
          "items" -> io.circe.Json.obj(
            "type" -> "object".asJson,
            "properties" -> io.circe.Json.obj(
              "id" -> io.circe.Json.obj("type" -> "string".asJson),
              "name" -> io.circe.Json.obj("type" -> "string".asJson),
              "task" -> io.circe.Json.obj("type" -> "string".asJson),
              "deps" -> io.circe.Json
                .obj("type" -> "array".asJson, "items" -> io.circe.Json.obj("type" -> "string".asJson)),
              "description" -> io.circe.Json.obj("type" -> "string".asJson)
            ),
            "required" -> io.circe.Json.arr("id".asJson, "task".asJson)
          )
        ),
        "chainName" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Optional id-safe chain label (the declared chain id; unique among your active chains)".asJson
        )
      ),
      "required" -> io.circe.Json.arr("nodes".asJson)
    )
  )

  /** 登记批节点（解析后的规范形：name 缺省 = id；deps = 原始引用，规范化在校验段）。 */
  final case class ChainNode(id: String, name: Option[String], task: String, deps: List[String], description: Option[String])

  def summarize(input: JsonObject): String =
    val n = input("nodes").flatMap(_.asArray).map(_.size).getOrElse(0)
    val label = input("chainName").flatMap(_.asString).getOrElse("")
    s"ChainWrite(${n} nodes${if label.nonEmpty then s": $label" else ""})"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 200 then result.take(197) + "..." else result

  // ── 纯解析（形态层；逐条可行动错误，all-or-nothing 的第一站）────────────────

  def parseNodes(input: JsonObject): Either[ToolError, List[ChainNode]] =
    input("nodes").flatMap(_.asArray) match
      case None => Left(ToolError("Missing required parameter: nodes (an array of {id, name?, task, deps?, description?})."))
      case Some(arr) =>
        arr.foldLeft[Either[ToolError, List[ChainNode]]](Right(Nil)) { (acc, item) =>
          for
            ns <- acc
            obj <- item.asObject.toRight(ToolError("each node must be an object {id, name?, task, deps?, description?}."))
            id <- obj("id").flatMap(_.asString).map(_.trim).toRight(ToolError("each node needs an \"id\" string."))
            task <- obj("task").flatMap(_.asString).map(_.trim).filter(_.nonEmpty).toRight(
              ToolError(s"node '$id' needs a non-empty \"task\" brief. (${Codes.TaskRequired})")
            )
            name = obj("name").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
            deps = obj("deps").flatMap(_.asArray).map(_.flatMap(_.asString).toList).getOrElse(Nil)
            desc = obj("description").flatMap(_.asString)
          yield ns :+ ChainNode(id, name, task, deps.map(_.trim).filter(_.nonEmpty), desc)
        }

  /** id 字符集契约（与链号/作用域同族：成为载荷键与派生归档批文件名——路径安全第一）。 */
  def validNodeId(id: String): Boolean = FlowMapStore.isDeclarableChainId(id)

  /** 结构校验（纯函数；deps 尚未规范化——引用既可能是批内 id 也可能是批内 name）。 */
  def validateStructure(nodes: List[ChainNode]): Either[ToolError, Unit] =
    if nodes.isEmpty then Left(ToolError(s"nodes is empty — nothing to register. (${Codes.Empty})"))
    else if nodes.size > MaxNodes then
      Left(ToolError(s"Too many nodes (${nodes.size}) — the cap is $MaxNodes. Split into multiple chains. (${Codes.TooMany})"))
    else
      val ids = nodes.map(_.id)
      val dupIds = ids.diff(ids.distinct)
      if dupIds.nonEmpty then
        Left(ToolError(s"Duplicate node id(s): ${dupIds.mkString(", ")}. (${Codes.DuplicateId})"))
      else
        val badId = ids.find(!validNodeId(_))
        if badId.isDefined then
          Left(
            ToolError(
              s"node id '${badId.get}' is not a valid id token — non-empty, <=${FlowMapStore.ChainIdMaxLength} chars, first char " +
                "alphanumeric, then letters/digits/'.'/'-'/'_' only (no path separators, whitespace or '..'): the id is used " +
                s"verbatim as the node address and inside derived archive file names. (${Codes.IdInvalid})"
            )
          )
        else
          val names = nodes.flatMap(_.name)
          val dupNames = names.diff(names.distinct)
          if dupNames.nonEmpty then
            Left(
              ToolError(
                s"Duplicate node name(s): ${dupNames.mkString(", ")} — names are addresses and must be unique in the chain. (${Codes.NameConflict})"
              )
            )
          else
            val badName = names.find(n => n.trim.isEmpty || n.length > 120)
            if badName.isDefined then
              Left(ToolError(s"node name must be 1-120 characters (got '${badName.get.take(20)}…'). (${Codes.NameInvalid})"))
            else
              // R4（批内形态）：name 不得等于批内任何 id（两地址空间不相交）。
              val collisions = names.filter(idSet(nodes).contains)
              if collisions.nonEmpty then
                Left(
                  ToolError(
                    s"'${collisions.head}' collides with an existing node id — a name must never equal an id (the two " +
                      "address spaces stay disjoint). " + s"(${Codes.NameIdCollision})"
                  )
                )
              else if hasCycleNormalized(nodes) then
                Left(ToolError(s"Cycle detected in deps — the chain must be a DAG. (${Codes.Cycle})"))
              else Right(())

  private def idSet(nodes: List[ChainNode]): Set[String] = nodes.map(_.id).toSet

  /** Kahn 判环（按批内 id 归域——deps 里的批内 name 视同其 id；批外引用不参与判环）。 */
  def hasCycleNormalized(nodes: List[ChainNode]): Boolean =
    val byId = nodes.map(n => n.id -> n).toMap
    val byName = nodes.flatMap(n => n.name.toList.map(_ -> n.id)).toMap
    def norm(ref: String): Option[String] = byId.get(ref).map(_ => ref).orElse(byName.get(ref))
    val depsOf: Map[String, Set[String]] = nodes.map { n =>
      n.id -> n.deps.flatMap(norm).toSet
    }.toMap
    @scala.annotation.tailrec
    def go(remaining: Set[String], resolved: Set[String]): Boolean =
      if remaining.isEmpty then false
      else
        val ready = remaining.filter(id => depsOf(id).forall(resolved.contains))
        if ready.isEmpty then true else go(remaining -- ready, resolved ++ ready)
    go(nodes.map(_.id).toSet, Set.empty)

  // ── IO 校验（唯一域现读：作用域活动区/归档区）＋ 落库 ＋ 放行 ───────────────

  /** 非.delegate 会话的 fail-closed 文案（工具内第二保险——挂载面之外的调用一概拒）。 */
  def notDelegateError: String =
    s"ChainWrite registers a whole chain into the calling DELEGATE's own scope flow map — it is not available outside " +
      "a delegate session (dispatch work with Delegate; persistent chains live in the delegate's scope). " +
      s"(${Codes.NotDelegate})"

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    if !nebflow.core.delegate.DelegateSessions.isDelegateSession(ctx.sessionId) then IO.pure(Left(ToolError(notDelegateError)))
    else if input.contains("project") then
      IO.pure(
        Left(
          ToolError(
            "ChainWrite has no 'project' parameter — it always registers into the calling delegate's own scope map " +
              "(the scope follows the session identity, never a client-supplied name)."
          )
        )
      )
    else
      parseNodes(input) match
        case Left(err) => IO.pure(Left(err))
        case Right(nodes) =>
          val chainName = input("chainName").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
          nebflow.core.project.DelegateFlowHosts.get(ctx.sessionId.getOrElse("")).flatMap {
            case None =>
              IO.pure(Left(ToolError(NodeTools.scopeUnavailableError(ctx.sessionId.getOrElse("")))))
            case Some(h) => registerChain(h.projectRuntime, nodes, chainName)
          }

  /**
   * 登记核心（ChainWrite 与 AgentFlow 薄壳共用单点——两个粒度的入口，一个校验器，
   * 一个落库事务，一个放行点）。全部校验在任何写之前完成（all-or-nothing）。
   */
  private[tools] def registerChain(
      rt: ProjectRuntime,
      nodes: List[ChainNode],
      chainName: Option[String]
  ): IO[Either[ToolError, String]] =
    validateStructure(nodes) match
      case Left(err) => IO.pure(Left(err))
      case Right(_) =>
        rt.store.snapshot.flatMap { s =>
          rt.store.archiveSnapshot.flatMap { arch =>
            val combined = s.nodes ++ arch.nodes
            val activeChainIds = s.nodes.values.flatMap(FlowMapStore.declaredChainId).toSet
            val chainView = FlowMapStore.chainIdView(s.nodes)

            // ── 唯一域校验（R2/R4；全部过才写——collectFirst 拿第一个显式错）────
            val chainNameCheck: Option[ToolError] = chainName.collect {
              case n if !FlowMapStore.isDeclarableChainId(n) =>
                ToolError(
                  s"chainName '$n' is not a valid id token — non-empty, <=${FlowMapStore.ChainIdMaxLength} chars, first char " +
                    "alphanumeric, then letters/digits/'.'/'-'/'_' only. " + s"(${Codes.IdInvalid})"
                )
              case n if activeChainIds.contains(n) =>
                ToolError(
                  s"chainName '$n' is already used by an active chain in this scope — chain labels are addresses. " +
                    s"(${Codes.NameConflict})"
                )
            }
            // id 唯一域＝两区全集（归档不占名但占 id——id 是持久地址，永不复用）
            val idConflicts = nodes.map(_.id).filter(combined.contains)
            val idCheck: Option[ToolError] =
              Option.when(idConflicts.nonEmpty) {
                ToolError(
                  s"Duplicate node id(s): ${idConflicts.mkString(", ")} — already used in this scope (ids are permanent " +
                    s"addresses, archived included). (${Codes.DuplicateId})"
                )
              }
            // R2：name 唯一域＝活动区（归档链不占名——可复用、可作解析候选）
            val nameCheck: Option[ToolError] = nodes.flatMap { n =>
              n.name.toList.flatMap(nm => s.nodes.values.find(nd => nd.name == nm).map(nm -> _))
            }.collectFirst { case (nm, holder) =>
              val holderChain = chainView.getOrElse(holder.id, "-")
              ToolError(
                s"node name '$nm' is already used by $holderChain#${holder.id} in this scope's active map — names are " +
                  "addresses and must be unique; pick another name. " + s"(${Codes.NameConflict})"
              )
            }
            // R4：name ≠ 任何既有 id（两区全集）
            val idCollisions = nodes.flatMap(n => n.name.toList.filter(combined.contains))
            val r4Check: Option[ToolError] =
              Option.when(idCollisions.nonEmpty) {
                ToolError(
                  s"'${idCollisions.head}' collides with an existing node id in this scope — a name must never equal an id " +
                    "(the two address spaces stay disjoint). " + s"(${Codes.NameIdCollision})"
                )
              }
            // deps 规范化（R6：批内 id／批内 name 皆收；链外仅 id——歧义显式列候选不猜）
            val byId = nodes.map(n => n.id -> n).toMap
            val byName = nodes.flatMap(n => n.name.toList.map(_ -> n.id)).toMap
            def normRef(ref: String): Either[ToolError, String] =
              if byId.contains(ref) then Right(ref)
              else if byName.contains(ref) then Right(byName(ref))
              else if combined.contains(ref) then Right(ref)
              else if combined.values.count(_.name.contains(ref)) > 1 then
                val cands =
                  combined.values.filter(_.name.contains(ref)).map(v => s"${v.id}(${v.task.getOrElse("").take(30)})")
                Left(
                  ToolError(
                    s"'$ref' matches ${cands.size} nodes by name — ${cands.mkString(", ")} — address by node id. " +
                      s"(${Codes.AddrAmbiguous})"
                  )
                )
              else
                Left(
                  ToolError(
                    s"deps reference unknown node id(s): $ref — batch ids/names or existing scope node ids only. " +
                      s"(${Codes.DepsUnknown})"
                  )
                )
            val normCheck: Either[ToolError, Map[String, List[String]]] =
              nodes.foldLeft[Either[ToolError, Map[String, List[String]]]](Right(Map.empty)) { (acc, n) =>
                for
                  m <- acc
                  ds <- n.deps.foldLeft[Either[ToolError, List[String]]](Right(Nil)) { (a, ref) =>
                    for done <- a; nid <- normRef(ref) yield done :+ nid
                  }
                yield m.updated(n.id, ds.distinct)
              }
            val normCheckOpt = normCheck.left.toOption
            val firstErr = chainNameCheck.orElse(idCheck).orElse(nameCheck).orElse(r4Check).orElse(normCheckOpt)

            firstErr match
              case Some(err) => IO.pure(Left(err))
              case None =>
                val depsNorm = normCheck.getOrElse(Map.empty)
                // ── 落库（单事务原子写＝「链齐」物证）＋ 放行 ────────────────
                val cid = chainName.getOrElse(autoChainId(activeChainIds ++ nodes.map(_.id).toSet))
                val now = System.currentTimeMillis()
                // 综合接线：下游依赖（批内）→ 上游 out 追加缺省 pass 边；末端（批内无下游）→
                // 显式门集根边（完成通知投 delegate 本人——自根作用域，刀1 H5 投递面）。
                val dependents: Map[String, List[String]] = depsNorm.toList
                  .foldLeft(Map.empty[String, List[String]]) { case (m, (down, ups)) =>
                    ups.foldLeft(m)((mm, up) => mm.updated(up, mm.getOrElse(up, Nil) :+ down))
                  }
                def outEdgesOf(id: String): List[OutEdge] =
                  val downs = dependents.getOrElse(id, Nil).filter(byId.contains)
                  val edges = downs.map(d => OutEdge(d))
                  // 末端（批内无下游者）显式门集根边 (pass)Nebula :result = 完成通知投
                  // 创建者 delegate 本人（v3-⑨ 荐读法，非隐式 bare 形态）。
                  if downs.isEmpty then List(OutEdge(OutEdge.RootTarget, Set(OutEdge.Pass), OutEdge.Result)) else edges
                val newNodes: Map[String, NodeDef] = nodes
                  .map { n =>
                    val deps = depsNorm.getOrElse(n.id, Nil)
                    n.id ->
                      NodeDef(
                        id = n.id,
                        name = n.name.getOrElse(n.id),
                        agent = BuiltinAgents.ExecutorName,
                        task = Some(n.task),
                        description =
                          Some(n.description.map(_.trim).filter(_.nonEmpty).getOrElse(n.task.trim.take(60))),
                        in = deps,
                        deps = deps,
                        out = outEdgesOf(n.id),
                        status = NodeLifecycle.Pending,
                        createdAt = now,
                        plugins = Nil,
                        chainId = Some(cid)
                      )
                  }
                  .toMap
                // 批外上游（既有节点）out 追加指向新下游（appendEdgeTo 同语义：幂等、不覆盖既有门控）
                val upstreamIds = depsNorm.values.flatten.filterNot(byId.contains).toSet
                val mutateIO = rt.store.mutateWithResult { st =>
                  var touched = List.empty[String]
                  val withBatch = st.copy(nodes = st.nodes ++ newNodes)
                  val withUpstreams = upstreamIds.foldLeft(withBatch) { (acc, up) =>
                    acc.nodes.get(up) match
                      case None => acc // 归档上游不回写活动区（findNode 兜底读；归档 out 面不复活）
                      case Some(un) =>
                        val targets = dependents.getOrElse(up, Nil).filter(byId.contains)
                        val added = targets.filterNot(t => un.out.exists(_.to == t))
                        if added.isEmpty then acc
                        else {
                          touched = up :: touched
                          acc.copy(nodes = acc.nodes.updated(up, un.copy(out = un.out ++ added.map(t => OutEdge(t)))))
                        }
                  }
                  (withUpstreams, touched.distinct)
                }
                mutateIO.flatMap { (_, touchedUps) =>
                  // 审计一行（链登记事件——type 值域加宽面，FlowMapEventLog 自由串形态）
                  FlowMapEventLog
                    .append(
                      rt.project.workspace,
                      rt.project.name,
                      nodes.headOption.map(_.id).getOrElse(cid),
                      "chain-registered",
                      s"chain '$cid' registered via ChainWrite: ${nodes.size} node(s) [${nodes.map(_.id).mkString(",")}]" +
                        chainName.map(n => s" declared-id=$n").getOrElse("")
                    )
                    .handleErrorWith(_ => IO.unit)
                    // 新节点 WS 面（S1/S10/S11 卡渲染数据源）＋ 既有上游 out 变更事件
                    .flatMap(_ => newNodes.values.toList.traverse_(n => rt.engine.emitCreated(n)))
                    .flatMap(_ =>
                      touchedUps.traverse_(up =>
                        rt.store.getNode(up).flatMap {
                          case Some(n) => rt.engine.emitUpdated(n)
                          case None    => IO.unit
                        }
                      )
                    )
                    // ChainLedger 链号出生（reconcile 单点＝TtlTick 台账腿同一函数；声明分量即出生）
                    .flatMap(_ => rt.store.reconcileChainLedger(now).handleErrorWith(_ => IO.unit))
                    // ── 放行点（S4）：入口节点（deps 空）startNode——后台 fork，工具即时返回
                    .flatMap { _ =>
                      val entries = nodes.map(_.id).filter(id => depsNorm.getOrElse(id, Nil).isEmpty)
                      entries
                        .traverse_(id => NodeTools.runDetached(rt, s"chain release -> $id")(rt.engine.startNode(id)))
                        .as(
                          Right(
                            s"Chain '$cid' registered with ${nodes.size} node(s) [${nodes.map(_.id).mkString(", ")}] — " +
                              (if entries.nonEmpty then
                                 s"released: entry node(s) ${entries.mkString(", ")} starting; "
                               else "") +
                              "end-node results arrive here as completion notifications. Non-blocking: do NOT wait idle " +
                              "and do NOT re-dispatch the same work."
                          )
                        )
                    }
                }
          }
        }

  /** 自动链号（flow-<8hex>；与在飞声明链号/批内 id 不撞——碰撞重摇，上限防御）。 */
  private def autoChainId(taken: Set[String]): String =
    (1 to 16)
      .map(_ => s"flow-${java.util.UUID.randomUUID().toString.take(8)}")
      .find(!taken.contains(_))
      .getOrElse(s"flow-${System.currentTimeMillis().toString.takeRight(12)}")

end ChainWriteTool
