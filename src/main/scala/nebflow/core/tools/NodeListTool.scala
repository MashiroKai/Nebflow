/* 从 NodeTools.scala 迁出(行为保持重构,2026-09-25)。 */
package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.project.*

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
Default: {nodes: [{id, name, agent, description, status, in, out, hasWorktree, worktree, blockCount, createdAt, completedAt, ttlLeftSec, + conditional: hasResult, taskPreview (legacy no-description fallback), deps, plugins, merge, loop, blockedFeedback (blocked only), skill/mcp/preset (legacy values only), liveness (running only), chainId (multi-member chain only), chainIds (merge nodes with 2+ reachable member chains only; value = main chain id first, then the full member chain list), mergeQueue (merge nodes currently held by the merge-window mutex gate only; value = {ahead, holders: [{id, name, status}], sameKeyProjects?} — `ahead` = the SIZE OF THE BLOCKING SET, i.e. who blocks me, NOT my rank in the queue), mergeQueuePos (merge nodes sitting in a merge-window queue with 2+ contenders only; value = {position, total, queue, arrived, readyAt, createdAt, rank: {primary, tiebreaks}, sameKeyProjects?} — `position` = 1-based rank, UNIQUE PER NODE and aligned with the real grant order rank=(readyAt,createdAt,id); `total` = contender count incl. the one already inside the critical section)}], chains: [{id, title, entries, ends, memberIds}] (topological task chains derived backend-side; a node's chainId joins its entry here; members may include archived nodes — filter by your node cache for on-graph rendering), worktrees: [...], meta: {project, updatedAt, archived}} — metadata only, NO result text (Flow Map slim-payload contract: results live in per-node files, read on demand).
With detail=<nodeId>: the same node shape + task + result (full text)."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "project" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Project name (optional; defaults to the dispatcher's current project)".asJson
        ),
        "status" -> Json.obj(
          "oneOf" -> Json
            .arr(
              Json.obj("type" -> "string".asJson),
              Json.obj("type" -> "array".asJson, "items" -> Json.obj("type" -> "string".asJson))
            )
            .asJson,
          "description" -> "Optional lifecycle filter: wiring|pending|running|completed|failed|cancelled|blocked — single, comma-separated, or array. Omitted = all nodes".asJson
        ),
        "detail" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Node id — return that node's full record (metadata + task + result FULL TEXT) instead of the whole map".asJson
        )
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
              case None =>
                IO.pure(
                  Left(
                    ToolError(
                      s"Node '$nodeId' not found (active or archived) — check NodeList without detail for the current topology"
                    )
                  )
                )
              case Some(n) =>
                // chainId / chainIds 条件键（链级抽象 P0 + U1 多链归属批）：detail
                // 单节点记录与快照/WS 同构——判据单点 FlowMapStore.chainAttrsOf
                // （双区可达，归档节点同样带链归属；chainIds 仅 merge 多链节点带，
                // 值 = 主链 id 首项 + 全量成员链；chainmodel 批三 ② 起同一单点再给出
                // mergeUpstreamChains = 本次汇聚的上游链，新增键）。
                rt.store.chainAttrsOf(n.id).map { attrs =>
                  val now = System.currentTimeMillis()
                  val base = NodePayload.buildNodeJson(
                    n,
                    now,
                    attrs.chainId,
                    attrs.chainIds,
                    mergeUpstreamChains = attrs.mergeUpstreamChains
                  )
                  // 裁定②历史参照补挂（20260907 上下文经济学批）：默认载荷仅 blocked 态
                  // 携带 blockedFeedback——detail 按需通道对非 blocked 节点补挂存储值
                  // （blocked 节点 base 已含，deepMerge 同值幂等）。
                  val histFeedback = n.blockedFeedback.toList.map(bf => "blockedFeedback" -> bf.asJson)
                  // 裁定⑤c 双层化：descriptionLong 仅 detail 通道（不进默认载荷）——
                  // 条件键，存量节点无长文 → 缺键（消费方回退短文 description）。
                  val descLong = n.descriptionLong.toList.map(d => "descriptionLong" -> d.asJson)
                  val full = base
                    .deepMerge(
                      Json.obj(
                        "task" -> n.task.asJson,
                        "result" -> n.result.asJson
                      )
                    )
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

  /**
   * status 过滤参数解析（裁定⑤a）：三形态宽容（单值 string / array / 逗号串，
   * 复用 parseIn 语义），逐值对生命周期枚举校验——非法值给可行动错误（列出全部
   * 合法值）。缺省（未传/null）→ None = 全量。
   */
  private def parseStatusFilter(v: Option[Json]): Either[String, Option[Set[String]]] =
    v match
      case None | Some(Json.Null) => Right(None)
      case Some(json) =>
        NodeTools.parseIn(Some(json)).left.map(err => err.replace("'in'", "'status'")).flatMap { values =>
          val trimmed = values.map(_.trim).filter(_.nonEmpty)
          if trimmed.isEmpty then Right(None)
          else
            trimmed.find(!NodeLifecycle.All.contains(_)) match
              case Some(bad) =>
                Left(
                  s"Unknown status '$bad' — valid: ${NodeLifecycle.All.mkString(", ")} (single value, comma-separated list, or array)"
                )
              case None => Right(Some(trimmed.toSet))
        }

end NodeListTool
