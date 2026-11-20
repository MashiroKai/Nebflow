package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.ask.{AskGatewayServices, AskSessions}

/* ═══════════════════════════════════════════════════════════════════════════
 * 分支管理问答（gitviz）· 只读取数件 `gitviz_graph`
 *
 * 作者 2026-11-19 18:41 第二轮令：「这个是专门设计的分支管理问答 agent，它也是像
 * canvas ask 一样只问答，做事就转发给主会话」+「这个 agent 没有任何 git 写能力——
 * 数据面沿用只读子命令白名单」。
 *
 * 授权面（值 = 名字本身，the tool name IS the permission boundary）：
 *   · 挂载面 = `AskSessions.GitVizAllowedTools`（`buildAllowedToolSet` 的 askGitViz
 *     早退集）——只有 `ask-gv-` 会话拿得到；
 *   · 名字同时进 `AskSessions.AskExclusiveTools` ⇒ 从其余一切身份（Nebula root /
 *     项目节点 / 分发器 / delegate / subagent / kernel）剥净；
 *   · 工具内侧身份闸 = `AskSessions.isGitVizSession(ctx.sessionId)`（双保险，与
 *     PdfPageTool / CardTool 同款）。
 *
 * 🔴 零写面：本工具**只调用** `GitGraph.build`（唯一取数口），后者的 `git` 包装只放行
 *   `ReadOnlySubcommands`（rev-parse / for-each-ref / log / rev-list / merge-base），
 *   越界子命令直接 `Left("refused non-read-only git subcommand: …")`。本文件不建分支、
 *   不 checkout、不 merge、不 fetch、不写工作区、不写 `.git/`。
 *
 * 作用域来源 = **绑定表**（`askOpen` 的 gitviz 分支按项目解出仓库根后落账）——本工具
 * 不自己解析项目、不碰 gateway 的 ProjectStore（层界：core 不依赖 gateway）。
 * ═══════════════════════════════════════════════════════════════════════════ */
object GitVizGraphTool extends Tool:

  val Name: String = "gitviz_graph"

  override def name: String = Name

  override def description: String =
    """Read the branch graph of THIS project's repository (read-only). Input (all optional): {"limit": <max commits, default 400>, "lanes": <lane count, default 8>, "heatDays": <heatmap window in days, default 84>}. Returns refs (name/tip/ahead/behind/merge-base), commits (sha/parents/date/author/subject/lane) and contribution heatmap — the same read-only payload the Git 可视化 panel renders. Backed ONLY by `git rev-parse / for-each-ref / log / rev-list / merge-base`; this tool cannot merge, branch, push or touch any ref. When the user asks you to perform a write, produce a structured handoff request instead."""

  override def inputSchema: JsonObject = JsonObject(
    "type" -> "object".asJson,
    "properties" -> Json.obj(
      "limit" -> Json.obj(
        "type" -> "integer".asJson,
        "description" -> "Max commits to walk (1..4000, default 400).".asJson
      ),
      "lanes" -> Json.obj(
        "type" -> "integer".asJson,
        "description" -> "Lane count for the swimlane layout (1..24, default 8).".asJson
      ),
      "heatDays" -> Json.obj(
        "type" -> "integer".asJson,
        "description" -> "Heatmap window in days (7..400, default 84 = 12 weeks).".asJson
      )
    ),
    "required" -> Json.arr()
  )

  override def summarize(input: JsonObject): String =
    val limit = intOf(input, "limit").getOrElse(400)
    s"gitviz_graph(limit=$limit)"

  override def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 200 then result.take(197) + "..." else result

  private def intOf(input: JsonObject, key: String): Option[Int] =
    input(key).flatMap(_.asNumber).flatMap(_.toInt)

  private def clamp(v: Int, lo: Int, hi: Int): Int = math.max(lo, math.min(hi, v))

  override def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val sid = ctx.sessionId.getOrElse("")

    // 身份闸（工具内侧那一层）：非 gitviz ask 会话（含 REST 直调 / 其余一切身份）拒绝。
    if !AskSessions.isGitVizSession(sid) then
      IO.pure(Left(ToolError(
        "gitviz_graph: permission denied — this tool is exclusive to branch-management ask sessions (ask-gv-*). (GITVIZ_FORBIDDEN)")))
    else
      AskGatewayServices.instance.map(_.registry) match
        case None =>
          IO.pure(Left(ToolError("gitviz_graph: ask services unavailable on this instance. (GITVIZ_FORBIDDEN)")))
        case Some(registry) =>
          registry.byId(sid).flatMap {
            case None =>
              IO.pure(Left(ToolError(s"gitviz_graph: ask session $sid has no binding. (GITVIZ_NO_SCOPE)")))
            case Some(binding) if !binding.isGitViz =>
              IO.pure(Left(ToolError(s"gitviz_graph: binding for $sid is not a branch-management scope. (GITVIZ_NO_SCOPE)")))
            case Some(binding) =>
              IO.blocking {
                val limit = clamp(intOf(input, "limit").getOrElse(400), 1, 4000)
                val lanes = clamp(intOf(input, "lanes").getOrElse(8), 1, 24)
                val heatDays = clamp(intOf(input, "heatDays").getOrElse(84), 7, 400)
                // 唯一取数口：既有只读数据面（与面板端点同一个 build）。
                nebflow.core.project.GitGraph.build(
                  project = binding.fileName,
                  workspace = binding.file,
                  limit = limit,
                  lanes = lanes,
                  heatDays = heatDays
                ).asJson.noSpaces
              }.map(Right(_))
          }
  end call
end GitVizGraphTool
