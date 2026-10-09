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
   * askucancel 批（2026-10-08）：**用户发起的取消**——按 `requestId` 精确终结一个
   * 仍在等待作答的 AskUser 问题。与 [[CloseRequest]] 的三点差别（CloseRequest 语义
   * 逐字节保留：调用侧撤回，无 kind 闸、无裁决、无发起方通知）：
   *
   *   ① **kind 闸**：只服务 `InteractionKind.AskUser` 槽位（permission/confirm 卡
   *      各有自身生命周期——approve/deny 即终态、SendConfirm 有超时自撤）⇒ 非
   *      AskUser 槽位回 `AskCancelOutcome.NotAskUser`，不静默；
   *   ② **发起方终结通知**：撤回信号（`AskUserCancelled.signal`）经槽位既有答复
   *      管道投达请求方——阻塞面板腿（pathPanel）据此判 Shelved 解除挂起；非阻塞
   *      桥腿注入取消通告（发起的代理/节点收到明确结论，而非永久等一条永不来的答复）；
   *   ③ **显式裁决**：`reply` 在场时回 `Cancelled`（含 root/sourceSession，供调用方
   *      追加取消落盘行）/ `UnknownRequestId`（已答/已收/从未存在——幂等，重复取消
   *      语义明确）/ `NotAskUser`。
   *
   * 广播面与 CloseRequest 同族：`askUserClosed{requestId, sourceSession, reason}`
   * 走**全部**已注册 root 窗口（fallback 渲染的卡也收得到），`reason` 缺省
   * `"user-cancelled"`（旧前端忽略未知键；前端按 reason 映射「已取消」文案是
   * 前端位的扩展点）。
   */
  final case class CancelRequest(
    requestId: String,
    reason: String = "",
    by: String = "",
    reply: Option[ActorRef[AskCancelOutcome]] = None
  ) extends InteractionHubCommand

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

  /**
   * askuser-secret 批（2026-10-08）：**只读**槽位查询 —— `requestId` 的 pending
   * AskUser 卡里哪些题是 secret 型（含各自的落盘名/一次性参数）。唯一消费方 =
   * 网关 `handleAskUserAnswer`：`.ui.json` 脱敏行与 hub 载荷占位**必须**由引擎侧
   * 判定哪些槽是秘密槽（旧前端把值打在普通 `answers` 里时，网关靠它识别「明文
   * 泄漏企图」并落拒收行——前端自报的任何标记都不可信）。hub 是槽位唯一权威，
   * 故查询走 hub；只读（不碰 pending/渲染/广播），`Option.empty` = 无此槽位
   * （已答/已收/从未存在——网关按旧路径透传，hub 侧守卫兜底拒收）。
   */
  final case class SecretSlotsOf(requestId: String, reply: ActorRef[Option[List[SecretSlotSpec]]])
      extends InteractionHubCommand
end InteractionHubCommand

/**
 * askuser-secret 批：一条 secret 型题的落库参数（hub 从 pending 载荷的 items 里
 * 解出，经 [[InteractionHubCommand.SecretSlotsOf]] 回给网关）。零值字段——值只在
 * `askUserAnswer` 帧的 `secretSlots` 键里，本类型只是「哪几题、按什么名字落」。
 */
final case class SecretSlotSpec(
  idx: Int,
  secretName: Option[String] = None,
  ephemeral: Boolean = false,
  ttlSec: Option[Int] = None
)

/** askucancel 批（2026-10-08）：`CancelRequest` 的裁决载荷（经 `reply` 回调用方）。 */
sealed trait AskCancelOutcome

object AskCancelOutcome:

  /**
   * 槽位已终结并广播。`rootSessionId` = 卡片渲染/落盘所属会话；`sourceSession` =
   * 发起提问的会话（审计/归因）。
   */
  final case class Cancelled(requestId: String, kind: InteractionKind, rootSessionId: String, sourceSession: String)
      extends AskCancelOutcome

  /** 幂等终态：该 requestId 无在册槽位（已作答 / 已被回收 / 从未存在）。 */
  case object UnknownRequestId extends AskCancelOutcome

  /** 槽位在册但不是 AskUser（permission / mcpPermission / confirm 卡不进本取消面）。 */
  final case class NotAskUser(kind: InteractionKind) extends AskCancelOutcome
end AskCancelOutcome
