/* 从 NodeTools.scala 迁出(行为保持重构)。 */
package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.project.*

object NodeCancelTool extends Tool:
  val name = "NodeCancel"

  val description =
    """Cancel a running node — or a whole chain (chain-level form) — the stop-loss tool.
## When to Use
- A node is mis-wired, hung, or superseded: cancel it, then rewire or recreate. Result is NOT delivered; upstream results already delivered stay buffered/archived.
- Cancel target must be running (non-running → no-op with notice). A running node with a live session gets a cancel signal; a STALE running node (dead session, e.g. after an instance restart) is reaped — finalized as cancelled immediately instead of a fake success.
- Chain-level form (`chain`): cancels EVERY non-terminal member of that chain, cascading downstream (an explicit, irreversible intent). Already-finished chains cannot be cancelled. Address the chain by its chain id (the `chainId`/`chains[].id` value from NodeList); unknown chain = CHAIN_NOT_FOUND.

## Scope & addressing (delegate sessions)
- On a delegate session this tool operates on the delegate's OWN scope flow map (one delegate, one map); any other `project` is refused (DELEGATE_SCOPE_FOREIGN). Project dispatchers keep the per-project face unchanged.
- Dual addressing: `node-id` accepts the node id OR the node name (exact id wins; an ambiguous name is an explicit error listing the candidates — CHAIN_ADDR_AMBIGUOUS; no hit = CHAIN_NODE_NOT_FOUND)."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "project" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Project name (optional; defaults to the dispatcher's current project)".asJson
        ),
        "node-id" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Node reference to cancel (id OR name — dual addressing; from NodeList)".asJson
        ),
        "chain" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Chain id — cancel the WHOLE chain (every non-terminal member, cascading downstream). Mutually exclusive with node-id".asJson
        )
      ),
      "required" -> Json.arr()
    )
  )

  def summarize(input: JsonObject): String =
    val id = input("chain").flatMap(_.asString).map(c => s"chain:$c").orElse(input("node-id").flatMap(_.asString)).getOrElse("?")
    s"NodeCancel($id)"

  def summarizeResult(input: JsonObject, result: String): String = result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val nodeId = input("node-id").flatMap(_.asString).getOrElse("")
    val chainRef = input("chain").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
    // N1 刀2 A4：解析单点换 `resolveRuntime`（delegate 会话 = 自己作用域图；其余身份
    // per-project 路径逐字节不变）。
    NodeTools.resolveRuntime(input("project").flatMap(_.asString), ctx).flatMap {
      case Left(err) => IO.pure(Left(ToolError(err)))
      case Right(rt) =>
        (nodeId.trim.nonEmpty, chainRef.isDefined) match
          case (true, true) =>
            IO.pure(
              Left(
                ToolError(
                  "Pass EITHER 'node-id' (one node) or 'chain' (the whole chain) — not both in one call."
                )
              )
            )
          case (false, false) =>
            IO.pure(Left(ToolError("Missing 'node-id' (one node) or 'chain' (whole chain)")))
          // 链级取消（增强承接，语义复用：cancelChain 单点——级联闭包
          // /台账意图/聚合通知全套引擎侧既有，零重写）。双寻址不做链名猜测：链引用恒
          // 链号（NodeList chains[].id 是唯一名单——LLM 侧自纠通道已足）。
          case (false, true) =>
            rt.engine
              .cancelChain(
                chainRef.get,
                nebflow.core.project.CancelSource.User,
                s"cancelled via NodeCancel chain-level form (scope=${rt.project.name})",
                cascade = true
              )
              .map {
                case Left(err) => Left(ToolError(err))
                case Right(report) =>
                  Right(
                    s"Chain '${chainRef.get}' cancelled — ${report.cancelled.size} node(s) finalized " +
                      s"(cascade downstream included); the cancellation is delivered like any other chain terminal state."
                  )
              }
          case (true, false) =>
            // 双寻址（刀2 A5）：id 命中 → 直取；name 唯一命中 → 取该节点；歧义/未找到 = 显式错。
            NodeTools.resolveNodeRefIO(rt, nodeId).flatMap {
              case Left(err) => IO.pure(Left(ToolError(err)))
              case Right(n) =>
                if n.status != NodeLifecycle.Running then
                  IO.pure(Right(s"Node '${n.name}' is not running (status=${n.status}) — no-op"))
                else
                  // 清场 c-③：有在飞执行 fiber → 正常取消信号（原
                  // 语义）；无在飞 fiber（会话已死/实例重启泄漏）→ 直接收殓终态化——修复
                  // 「返回成功但节点状态不落终态」的假成功（假成功下分发器以为已止损，
                  // stale running 永久滞留）。收殓内部二次复核 isRunning（防窗口竞态）。
                  rt.engine.isRunning(n.id).flatMap {
                    case true => rt.engine.cancelNodeById(n.id).as(Right(s"Node '${n.name}' cancel signal sent"))
                    case false => rt.engine.reapStaleRunning(n.id).map(_.left.map(ToolError(_)))
                  }
            }
    }
  end call
end NodeCancelTool

// NodeMessageTool 已删净退役（不留 deprecated 壳、不留别名、不留自动转发）。语义整体并入
// `MailTool` 的 `node:<nodeId>` 腿——三态判据（终态拒绝 / wiring·pending 追加
// task / running 注入）**不复制**，由 Mail 复用引擎侧单点
// `NodeEngine.sendNodeMessage`（含 NODE_NOT_FOUND / NODE_MESSAGE_EMPTY /
// NODE_TERMINAL_NO_MESSAGE 三个错误码与 flow-map-events.jsonl 留痕）。
// 迁移指引（只产错误文案，零执行面）见 `AgentCore.RetiredToolGuides`。
