/* 从 NodeTools.scala 迁出(行为保持重构,2026-09-25)。 */
package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.project.*

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
        "project" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Project name (optional; defaults to the dispatcher's current project)".asJson
        ),
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
  end call
end NodeCancelTool

// NodeMessageTool 已删净退役（R2「一个 Mail 统一」批，2026-09-12 作者裁定
// B5-c：不留 deprecated 壳、不留别名、不留自动转发）。语义整体并入
// `MailTool` 的 `node:<nodeId>` 腿——三态判据（终态拒绝 / wiring·pending 追加
// task / running 注入）**不复制**，由 Mail 复用引擎侧单点
// `NodeEngine.sendNodeMessage`（含 NODE_NOT_FOUND / NODE_MESSAGE_EMPTY /
// NODE_TERMINAL_NO_MESSAGE 三个错误码与 flow-map-events.jsonl 留痕）。
// 迁移指引（只产错误文案，零执行面）见 `AgentCore.RetiredToolGuides`。
