package nebflow.neblink

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.decode
import nebflow.core.NebflowLogger

import scala.collection.immutable.Queue
import scala.concurrent.duration.*

/**
 * A2A 一期客户端好友服务（spec friends-messaging-arch.md v1.2 §11 客户端）。
 *
 * 职责（全部收敛在桌面客户端，服务端已在阶段 1 交付）：
 *  - REST 收发：好友请求/列表/会话/消息，经 NeblinkClient 直连 neblink-server；
 *  - 未读 cursor：本地未读数维护（给自己看角标，无回执——spec §3.4）；
 *  - 重连补拉：relay 隧道重连成功时逐会话 keyset 补拉（after=本地最大 id，
 *    spec §5.2，推荐先只做逐会话补拉）；
 *  - 事件去重：friend_event.eventId 幂等（推送与补拉可能重叠，spec §5.2）；
 *  - agent 发送限速：auto 模式每好友 20 条/h、全局 60 条/h，超限自动降级
 *    ask（弹确认）而非硬失败（spec §7.2-7.3，用户裁定 auto 为一期默认）。
 *
 * 事件与确认回调由 GatewayMain 接线（UI/通知中心/AskUser 交互），FriendService
 * 本身不依赖任何 UI 层——纯逻辑（限速/去重/cursor）在 FriendMessagingGuard，
 * 可独立单测。
 */
final class FriendService(
  client: NeblinkClient,
  config: AgentMessagingConfig,
  guard: FriendMessagingGuard = new FriendMessagingGuard(),
  onFriendEvent: Option[FriendEvent => IO[Unit]] = None,
  askConfirm: Option[String => IO[Boolean]] = None
):
  private val logger = NebflowLogger.forName("nebflow.neblink.friends")

  // ===== 事件入口（NeblinkRelayTunnel friend_event case 回调） =====

  /**
   * 处理一条服务端推送（type:"friend_event"，payload {eventId, event}）。
   * eventId 幂等去重后，按 event.type 分发：
   *  - message_new：补拉该会话增量（推送是尽力而为，REST 是事实来源），
   *    更新未读 cursor；
   *  - friend_request / friend_accepted：刷新好友列表（状态变化）。
   */
  def onFriendEvent(payload: Json): IO[Unit] =
    (for
      eventId <- payload.hcursor.get[String]("eventId").toOption
      event <- payload.hcursor.get[Json]("event").toOption
      evType <- event.hcursor.get[String]("type").toOption
    yield (eventId, event, evType)) match
      case None =>
        logger.warn(s"Malformed friend_event ignored: ${payload.noSpaces.take(200)}")
      case Some((eventId, event, evType)) =>
        guard.dedupe(eventId).flatMap {
          case false =>
            logger.debug(s"Duplicate friend_event $eventId ignored")
          case true =>
            handleEvent(evType, event) *>
              logger.info(s"friend_event processed: type=$evType id=$eventId")
        }

  private def handleEvent(evType: String, event: Json): IO[Unit] =
    evType match
      case "message_new" =>
        // 推送总是发给接收方（我方）——该消息必为对方所发，未读 +1
        // （spec §3.4 未读口径：sender != me）。正文靠补拉（REST 是事实来源）。
        event.hcursor.get[String]("conversationId").toOption match
          case Some(convId) =>
            guard.bumpUnread(convId, 1) *>
              pullConversation(convId).void
                .handleErrorWith(e => logger.warn(s"friend_event pull failed for $convId: ${e.getMessage}"))
          case None => IO.unit
      case "friend_request" | "friend_accepted" =>
        refreshFriends().void.handleErrorWith(e => logger.warn(s"friend event refresh failed: ${e.getMessage}"))
      case other =>
        logger.debug(s"Unhandled friend_event type: $other")

  // ===== 补拉与刷新（启动时 / 隧道重连时 / 事件触发） =====

  /** 全量刷新：会话列表 + 逐会话增量补拉。重连/启动入口。 */
  def refreshAll(): IO[Unit] =
    refreshConversations().flatMap { convs =>
      convs.traverse_(c => pullConversation(c.conversationId))
    } *> refreshFriends().void

  /** 拉会话列表，合并未读 cursor。 */
  def refreshConversations(): IO[List[ConversationSummary]] =
    client.listConversations.flatMap {
      case Left(err) => logger.warn(s"listConversations failed: $err").as(Nil)
      case Right(convs) =>
        convs
          .traverse(c => guard.mergeUnread(c).map(_ => c))
          .flatTap(_ => logger.debug(s"Refreshed ${convs.size} conversations"))
    }

  /** 逐会话 keyset 补拉（after=本地最大消息 id），推进本地锚点。未读由
    * 服务端 unreadCount（mergeUnread 基线）+ message_new 事件增量维护。 */
  def pullConversation(conversationId: String): IO[Unit] =
    guard.localMaxId(conversationId).flatMap { after =>
      client.listMessages(conversationId, after).flatMap {
        case Left(err) => logger.warn(s"pullConversation $conversationId failed: $err")
        case Right(msgs) if msgs.isEmpty => IO.unit
        case Right(msgs) =>
          val maxId = msgs.map(_.id).max
          guard.advanceAnchor(conversationId, maxId) *>
            logger.info(s"Pulled ${msgs.size} message(s) for $conversationId (maxId=$maxId)")
      }
    }

  /** 好友列表快照（UI 渲染数据源）。 */
  def refreshFriends(): IO[FriendListResponse] =
    client.listFriends.flatMap {
      case Left(err) => logger.warn(s"listFriends failed: $err").as(FriendListResponse(Nil))
      case Right(resp) => IO.pure(resp)
    }

  /** 标记已读（本地 cursor + 服务端）。 */
  def markRead(conversationId: String, lastReadMessageId: Long): IO[Either[String, String]] =
    guard.setRead(conversationId, lastReadMessageId) *>
      client.markConversationRead(conversationId, lastReadMessageId)

  // ===== Agent 发消息（SendFriendMessage 工具入口） =====

  /**
   * 以用户身份向好友发消息（spec §7.2 权限模型）：
   *  - auto（默认）：直接发送；超双层限速自动降级 ask（不硬失败）；
   *  - ask：每次弹确认（60s 超时=拒绝）；
   *  - off：直接返回禁用。
   * 超长消息（>4000）与服务端 403 等错误原样返回。
   */
  def sendAsAgent(friendUserId: String, body: String): IO[Either[String, String]] =
    if body.length > 4000 then IO.pure(Left(s"Message too long (${body.length} chars, max 4000)"))
    else
      config.mode match
        case "off" => IO.pure(Left("User has disabled agent messaging"))
        case "ask" =>
          confirmOrSend(friendUserId, body)
        case _ => // auto（含未知值回退 auto）
          guard.trySend(friendUserId, System.currentTimeMillis()).flatMap {
            case Right(()) => doSend(friendUserId, body)
            case Left(reason) =>
              logger.info(s"auto rate limit exceeded ($reason) — downgrading to ask") *>
                confirmOrSend(friendUserId, body)
          }

  private def confirmOrSend(friendUserId: String, body: String): IO[Either[String, String]] =
    askConfirm match
      case None => IO.pure(Left("ask mode requires a confirmation callback (not wired)"))
      case Some(confirm) =>
        confirm(body).flatMap {
          case true => doSend(friendUserId, body)
          case false => IO.pure(Left("User declined the message"))
        }

  private def doSend(friendUserId: String, body: String): IO[Either[String, String]] =
    client.sendFriendMessage(friendUserId, body).flatMap {
      case Left(err) => IO.pure(Left(err))
      case Right(json) =>
        val convId = json.hcursor.get[String]("conversationId").toOption
        convId.traverse_(pullConversation).void *>
          IO.pure(Right("Message sent"))
    }

  // ===== 暴露给 UI 的查询 =====

  def listMessages(conversationId: String, after: Long = 0L, limit: Int = 50): IO[Either[String, List[MessageSummary]]] =
    client.listMessages(conversationId, after, limit)

  def lookupUser(q: String): IO[Either[String, Json]] = client.lookupUser(q)

  def sendFriendRequest(query: String, note: Option[String] = None): IO[Either[String, Json]] =
    client.sendFriendRequest(query, note)

  def acceptFriendRequest(requestId: String): IO[Either[String, Json]] = client.acceptFriendRequest(requestId)

  def declineFriendRequest(requestId: String): IO[Either[String, String]] = client.declineFriendRequest(requestId)

  def unreadCounts: IO[Map[String, Int]] = guard.unreadSnapshot

end FriendService

/** 推送事件（friend_event 的 event payload 解码形态，供回调消费）。 */
case class FriendEvent(
  eventType: String,
  payload: Json
)

/**
 * FriendService 纯状态机：限速（agent 发送双层滑动窗口）+ 事件去重（eventId）
 * + 会话 cursor（本地最大消息 id / 已读 / 未读数）。无 IO 副作用以外的依赖，
 * 可独立单测。所有方法纯 Ref 操作，线程安全。
 */
final class FriendMessagingGuard(
  perFriendPerHour: Int = 20,
  globalPerHour: Int = 60,
  maxSeenEvents: Int = 2048
):
  private case class St(
    perFriendWindow: Map[String, Queue[Long]],
    globalWindow: Queue[Long],
    seenEvents: Queue[String],
    cursors: Map[String, ConversationCursor]
  )
  private val state: Ref[IO, St] =
    Ref.unsafe(St(Map.empty, Queue.empty, Queue.empty, Map.empty))

  private val WindowMs = 60 * 60 * 1000L

  /** 事件幂等：首次见返回 true 并记录；重复返回 false。上限裁剪防无限增长。 */
  def dedupe(eventId: String): IO[Boolean] =
    state.modify { s =>
      if s.seenEvents.contains(eventId) then (s, false)
      else
        val next = (s.seenEvents :+ eventId).drop(Math.max(0, s.seenEvents.size + 1 - maxSeenEvents))
        (s.copy(seenEvents = next), true)
    }

  /** agent 发送限速检查：成功 Right(())；超限 Left(原因)。滑动窗口裁剪过期时间戳。 */
  def trySend(friendId: String, now: Long): IO[Either[String, Unit]] =
    state.modify { s =>
      val pf = s.perFriendWindow.getOrElse(friendId, Queue.empty).filter(t => now - t < WindowMs)
      val gl = s.globalWindow.filter(t => now - t < WindowMs)
      if pf.size >= perFriendPerHour then
        (s, Left(s"rate limit: $perFriendPerHour msgs/hour to this friend"))
      else if gl.size >= globalPerHour then
        (s, Left(s"rate limit: $globalPerHour msgs/hour total"))
      else
        (s.copy(perFriendWindow = s.perFriendWindow.updated(friendId, pf :+ now), globalWindow = gl :+ now), Right(()))
    }

  /** 本地已读的最大消息 id（补拉锚点）。 */
  def localMaxId(conversationId: String): IO[Long] =
    state.get.map(_.cursors.get(conversationId).map(_.lastReadMessageId).getOrElse(0L))

  /**
   * 推进本地锚点（补拉后）：lastReadMessageId = max(当前, 本次最大消息 id)。
   * 未读数不在此维护——服务端 unreadCount 是权威基线（mergeUnread），
   * message_new 事件做增量（bumpUnread），markRead 清零。
   */
  def advanceAnchor(conversationId: String, maxMessageId: Long): IO[Unit] =
    state.update { s =>
      val cur = s.cursors.getOrElse(conversationId, ConversationCursor(conversationId, 0L, 0))
      s.copy(cursors = s.cursors.updated(conversationId, cur.copy(lastReadMessageId = Math.max(cur.lastReadMessageId, maxMessageId))))
    }

  /** 未读增量（message_new 事件：推送必为对方所发 → +1）。 */
  def bumpUnread(conversationId: String, delta: Int): IO[Unit] =
    state.update { s =>
      s.cursors.get(conversationId) match
        case Some(c) => s.copy(cursors = s.cursors.updated(conversationId, c.copy(unreadCount = c.unreadCount + delta)))
        case None => s
    }

  /** 合并服务端会话未读数（服务端权威口径：自己发的消息不计，spec §3.4）。 */
  def mergeUnread(conv: ConversationSummary): IO[Unit] =
    state.update { s =>
      val cur = s.cursors.getOrElse(conv.conversationId, ConversationCursor(conv.conversationId, 0L, 0))
      s.copy(cursors = s.cursors.updated(conv.conversationId, cur.copy(unreadCount = conv.unreadCount)))
    }

  /** 标记已读：推进已读锚点并清零未读。 */
  def setRead(conversationId: String, lastReadMessageId: Long): IO[Unit] =
    state.update { s =>
      val cur = s.cursors.getOrElse(conversationId, ConversationCursor(conversationId, 0L, 0))
      s.copy(cursors = s.cursors.updated(conversationId, cur.copy(lastReadMessageId = Math.max(cur.lastReadMessageId, lastReadMessageId), unreadCount = 0)))
    }

  def unreadSnapshot: IO[Map[String, Int]] =
    state.get.map(_.cursors.view.mapValues(_.unreadCount).toMap)

end FriendMessagingGuard
