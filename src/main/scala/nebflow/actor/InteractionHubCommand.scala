/* 严格DAG第⑥步第三批A裁定(dwfq-5c7a31ea-1,R-B=B1):本命令 ADT自 nebflow.agent.InteractionHub
 * 整体下沉 nebflow.actor(与 commit 1a91838「agent 协议面下沉 actor」同构的先例:命令 ADT 与
 * ActorRef/InteractionRequest/InteractionAnswered 同居底层)。成员与注释逐字迁移,零行为差;
 * core(NodeCompletion/AskUserQuestionTool)经 actor 合法向下依赖持 interactionHubRef,
 * agent 侧实现(InteractionHub)原地不搬,全仓引用改指。 */
package nebflow.actor

import cats.effect.IO
import io.circe.Json

/** Commands accepted by the InteractionHub actor. */
sealed trait InteractionHubCommand

object InteractionHubCommand:
  /** Gateway → hub: register a root session's recording wsSend (render target). */
  final case class RegisterRoot(rootSessionId: String, wsSend: Json => IO[Unit]) extends InteractionHubCommand

  /** Gateway → hub: root session closed — remove its render target. */
  final case class UnregisterRoot(rootSessionId: String) extends InteractionHubCommand

  /** Agent → hub: a permission / AskUser request from any agent in the tree. */
  final case class Request(req: InteractionRequest) extends InteractionHubCommand

  /** Gateway → hub: user answered (translated from permissionAnswer/askUserAnswer). */
  final case class Answered(ans: InteractionAnswered) extends InteractionHubCommand

  /**
   * 刷新存活 (2026-09-03): gateway → hub — snapshot the still-pending AskUser
   * cards for `rootSessionId` (oldest first), each rendered exactly like the
   * first send plus `replayed: true`. The gateway re-sends them when a client
   * (re)subscribes to the session (initial history load after browser refresh
   * / WS reconnect / session switch) so the card and its requestId binding
   * survive regardless of history pagination. Read-only: never touches the
   * pending map or the reply slots.
   */
  final case class ListPendingAsks(rootSessionId: String, reply: ActorRef[List[Json]]) extends InteractionHubCommand

  /**
   * P2 G11 (20260908 spec §3.4): engine → hub — the node owning `sessionId`
   * (sourceSession of its asks) reached cancelled (cancelNode / abandon /
   * dead-session reap cascade). Remove every pending slot sourced from that
   * session and broadcast askUserClosed{requestId} so no zombie card outlives
   * its asker.
   *
   * #250 第②项（2026-09-13 作者裁定「6 项全补」）：同一语义也覆盖**用户中断
   * turn**（root 会话此前没有清理入口）。`reason` 是可选来源标注
   * （"turn-interrupted"），缺省 "" 时广播帧逐字节不变（旧前端忽略未知键）。
   */
  final case class CleanupForSession(sessionId: String, reason: String = "") extends InteractionHubCommand

  /**
   * 多 AskUser 并发批（#250 第③项，2026-09-13 作者裁定「6 项全补」）：
   * gateway → hub — **全局** pending-AskUser 快照（跨 root，按 createdAt 升序），
   * 每帧与 `ListPendingAsks` 逐字节同构（`replayed: true`，`sessionId` = 各自 root）。
   * 前端重连时用它把待办条/badge 的本地镜像与 hub 权威一次性对齐，不再依赖
   * 「哪个会话恰好被（重新）订阅」。只读：不触碰 pending map 或 reply 槽位。
   */
  final case class ListAllPendingAsks(reply: ActorRef[List[Json]]) extends InteractionHubCommand

  /**
   * #147 接线段（2026-09-12）：requester → hub — the caller itself stopped
   * waiting for `requestId` (SendMessage ask-档 confirm timed out) and returns
   * a judged failure. Remove exactly that slot and broadcast
   * askUserClosed{requestId, reason:"caller-withdrew"} so the user is never
   * left with a clickable card whose answer goes nowhere. Precise by
   * requestId — never the session-wide sweep of CleanupForSession. Idempotent:
   * an unknown/already-answered requestId is a no-op.
   */
  final case class CloseRequest(requestId: String) extends InteractionHubCommand

  /**
   * 工具面按角色分化批 B6（2026-09-13）：**只读**可达性查询 ——
   * `rootSessionId` 当前是否有已注册的客户端窗口（`RegisterRoot` 的 wsSend）。
   * 非阻塞 AskUserQuestion 在注册槽位**之前**用它做前置预检：非阻塞下没人等待
   * ⇒ 卡渲染进不可达 root（`handleRequest` 的 None 分支只 warn）= 答案静默丢失。
   * 预检把这条路径变成**显式拒绝 + 零槽位**（fail-closed）。
   *
   * 读侧纪律：不碰 `pending`（不建/不删槽位）、不渲染、不广播 —— 纯查询，故可安全
   * 放在任何副作用之前。阻塞模式**不用**它（阻塞路径 root 不可达时的扇出回落
   * `fallback:true` 语义保持不变）。
   */
  final case class RootReachable(rootSessionId: String, reply: ActorRef[Boolean]) extends InteractionHubCommand
end InteractionHubCommand
