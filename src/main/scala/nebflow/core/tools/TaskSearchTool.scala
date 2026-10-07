package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.index.TaskSearchIndex

/**
 * TaskSearch —— 任务语义检索工具（Nebula 面；task-rag 批 #1227 / §Batch 7）。
 *
 * 管线（原文口径）：query → embed → cosine top-k →（可选 Jev 重排）→ 返回
 * {task #N, 标题, 状态, 执行器图标, address(delegate:<id>), snippet}。未配
 * embedding ⇒ 词法降级（返回结构逐字段同一）；Jev 未配/接口不合 ⇒ 跳过重排
 * 照常返回（原文「可选」的落地）。
 *
 * 地址面（原文口径）：检索到的地址可直接续派 —— `Mail(address="delegate:<id>")`
 * 续聊该实例；`task #N` 可经 `Task` 工具继续编排。本工具只读：账本/地址簿零写。
 *
 * 授权面 = `AgentCore.RootOrchestrationTools` 单点（Nebula 固定面成员；
 * `RootExclusiveTools` 防声明逃逸——非 Nebula 身份声明不授能）。
 *
 * 🔴 模型可见文案（description / 结果信封 / Mail 提示句）＝ §16 呈审串，
 * 随批落刀＋单列候追认（batch78/N1 先例）。
 */
object TaskSearchTool extends Tool:

  val Name: String = "TaskSearch"

  /** 默认 top-k（schema 缺省；上限 25 由索引面钳制）。 */
  val DefaultK: Int = 8

  override def name: String = Name

  override def description: String =
    """TaskSearch — semantic search over the task ledger and the delegate registry (Nebula-exclusive, read-only). Given a free-text query it returns the most relevant work items; each hit carries: `task` (ledger number, "#<id>" form), `title`, `status`, `executor` (icon key of the bound executor, empty when none), `address` (continuation address, "delegate:<id>" form, present when the item is bound to a delegate instance), and a `snippet`. Embedding search runs when the `embedding` block of nebflow.json is configured; without it the search degrades to lexical matching (same result shape, still useful). An optional rerank pass may reorder hits when the JeV decision face is configured — it is skipped silently otherwise. Retrieved addresses are directly reusable: Mail(address="delegate:<id>") continues THAT instance (supplements, corrections, or follow-up dispatch), and a retrieved task # can be updated or re-dispatched with the Task tool."""

  override def inputSchema: JsonObject = JsonObject(
    "type" -> "object".asJson,
    "properties" -> Json.obj(
      "query" -> Json.obj(
        "type" -> "string".asJson,
        "description" -> "Free-text query — what to look for across task titles, note summaries and delegate briefs. Required.".asJson
      ),
      "k" -> Json.obj(
        "type" -> "integer".asJson,
        "description" -> "Max hits to return (1-25, default 8).".asJson
      )
    ),
    "required" -> Json.arr("query".asJson)
  )

  override def summarize(input: JsonObject): String =
    val q = input("query").flatMap(_.asString).getOrElse("").take(60)
    s"TaskSearch($q)"

  override def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 200 then result.take(197) + "..." else result

  override def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val query = input("query").flatMap(_.asString).getOrElse("")
    val k = input("k").flatMap(_.as[Int].toOption).getOrElse(DefaultK)
    TaskSearchIndex.search(query, k).map {
      case Left(err) => Left(ToolError(err))
      case Right(res) => Right(render(res, query.trim))
    }

  /** 结果信封（模型可见 §16 串）：命中数组 + Mail 地址面提示句。 */
  private def render(res: TaskSearchIndex.SearchResult, query: String): String =
    if res.hits.isEmpty then
      s"""TaskSearch: no hits for "$query" (mode=${res.mode}). Widen the query, or browse the ledger with Task(action="list")."""
    else
      val hitsJson = res.hits.map { h =>
        Json.obj(
          "task" -> (if h.taskId.isEmpty then Json.Null else s"#${h.taskId}".asJson),
          "title" -> h.title.asJson,
          "status" -> (if h.status.isEmpty then Json.Null else h.status.asJson),
          "executor" -> (if h.executor.isEmpty then Json.Null else h.executor.asJson),
          "address" -> (if h.address.isEmpty then Json.Null else h.address.asJson),
          "snippet" -> h.snippet.asJson,
          "kind" -> h.kind.asJson,
          "score" -> h.score.asJson
        )
      }.asJson
      val rerankNote = if res.reranked then ", reranked" else ""
      s"""TaskSearch: ${res.hits.length} hit(s) for "$query" (mode=${res.mode}$rerankNote; indexed=${res.totalIndexed})
         |${hitsJson.spaces2}
         |Mail address face: a retrieved address can be continued directly — Mail(address="delegate:<id>") reaches that delegate instance for supplements, corrections, or follow-up dispatch; a retrieved task # can be updated or re-dispatched with the Task tool.""".stripMargin

end TaskSearchTool
