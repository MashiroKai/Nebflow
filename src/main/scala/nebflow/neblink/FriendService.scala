package nebflow.neblink

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.JsonObject
import io.circe.parser.decode
import io.circe.syntax.*
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
 *    ask（弹确认）而非硬失败（spec §7.2-7.3，用户裁定 auto 为一期默认）；
 *  - 本地好友备注（⑦）：`remarkRef` 持内存态、`FriendRemarkStore` 持久化，出站
 *    口（refreshFriends / listFriends / refreshConversations）统一注入。
 *
 * 事件与确认回调由 GatewayMain 接线（UI/通知中心/AskUser 交互），FriendService
 * 本身不依赖任何 UI 层——纯逻辑（限速/去重/cursor）在 FriendMessagingGuard，
 * 可独立单测。
 *
 * client 引用统一（2026-09-10 好友搜索失效根修 F1）：本服务不再持有构造期
 * NeblinkClient 快照，而是每次调用经 `currentClient` 读权威 live client
 * （NeblinkDiscovery.clientRef——enrollment hot-swap 的唯一替换点）。此前
 * GatewayMain 构造 FriendService 时注入 client₀，而 hot-swap 只换 discovery
 * 的引用：UI 重新登录/换账号后 discovery 侧 session 有效、本服务侧 session
 * 已被服务端 one-live-session 策略踢掉，403 持续到进程重启（搜索 502、
 * 列表静默折叠空态）。与 performLocalLogout 读 discovery.currentClient 的
 * 既有先例语义一致（权威 live client = discovery.clientRef）。
 */
final class FriendService(
  currentClient: IO[Option[NeblinkClient]],
  config: AgentMessagingConfig,
  guard: FriendMessagingGuard = new FriendMessagingGuard(),
  onFriendEvent: Option[FriendEvent => IO[Unit]] = None,
  askConfirm: Option[String => IO[Boolean]] = None,
  remarkRef: Ref[IO, Map[String, String]] = Ref.unsafe[IO, Map[String, String]](Map.empty)
):
  private val logger = NebflowLogger.forName("nebflow.neblink.friends")

  // ===== 本地好友备注（2026-09-12 好友消息改造批 ⑦）=====

  /** 备注覆盖应用（唯一合并点）：把本地备注（键 = `userId`）盖到档案上。
    *
    * 先例逐行同源：`NeblinkService.applyDescOverride`（`peersRef` + `.filter(_.nonEmpty)`）。
    * 空/缺席一律**保持**档案原样（`None`）——不写空串，前端与工具两侧都不必容错
    * 「空备注」第二种形态。 */
  private def applyRemark(f: FriendSummary, remarks: Map[String, String]): FriendSummary =
    remarks.get(f.userId).filter(_.nonEmpty) match
      case Some(r) => f.copy(remark = Some(r))
      case None    => f

  /** 好友列表出站口统一注入备注。`ListFriends` / `SendMessage` 走 `refreshFriends`、
   *  REST `GET /api/friends` 走 `listFriends` —— 两条取数路径在此收口，备注只在
   *  一处合并（禁各调用点自行 map，防第二套合并语义）。 */
  private def applyRemarks(resp: FriendListResponse): IO[FriendListResponse] =
    remarkRef.get.map { remarks =>
      resp.copy(friends = resp.friends.map(f => applyRemark(f, remarks)))
    }

  /** 会话列表内嵌 `friend` 档案同样带备注值（冻结契约 2：`remark` 经
    *  `GET /api/friends` 与 conversations 的 `friend` 档案下发——前端消息列表行
    *  的「备注 > 显示名」显示优先级读的就是这里的值）。 */
  private def applyRemarksToConvs(convs: List[ConversationSummary]): IO[List[ConversationSummary]] =
    remarkRef.get.map { remarks =>
      convs.map(c => c.copy(friend = applyRemark(c.friend, remarks)))
    }

  /** 设置 / 清除某好友的备注（本地态，**零上游往返** ⇒ 无 502 面）。
    *
    * `trim` 后空串 = **清除**（从 map 删除，语义对齐 `NeblinkService.updatePeerDescription`
    * 消费侧的 `.filter(_.nonEmpty)`）。持久化同族先例：改 Ref → `save`（写失败
    * 即上抛，不由本层吞掉）。
    */
  def setRemark(friendUserId: String, remark: String): IO[Unit] =
    val r = remark.trim
    for
      updated <- remarkRef.modify { m =>
        val next = if r.isEmpty then m - friendUserId else m + (friendUserId -> r)
        (next, next)
      }
      _ <- FriendRemarkStore.save(updated)
    yield ()

  /** 当前备注快照（键 = `userId`）。仅供查询/seam；写面唯一入口是 `setRemark`。 */
  def remarks: IO[Map[String, String]] = remarkRef.get

  /** F1: resolve the authoritative live client per call. None (logged out /
    * never configured) mirrors NeblinkClient.withSession's own "Not logged in"
    * shape so callers see one uniform error domain. */
  private def withClient[A](f: NeblinkClient => IO[Either[String, A]]): IO[Either[String, A]] =
    currentClient.flatMap {
      case None      => IO.pure(Left("Not logged in"))
      case Some(cli) => f(cli)
    }

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
              // Notify the UI-facing callback (GatewayMain wires it to a WS
              // friendEvent broadcast) — best-effort, never blocks the handler.
              onFriendEvent.traverse_(cb => cb(FriendEvent(evType, event)).handleErrorWith(_ => IO.unit)) *>
              logger.info(s"friend_event processed: type=$evType id=$eventId")
        }

  private def handleEvent(evType: String, event: Json): IO[Unit] =
    evType match
      case "message_new" =>
        // 推送总是发给接收方（我方）——该消息必为对方所发，未读 +1
        // （spec §3.4 未读口径：sender != me）。正文靠补拉（REST 是事实来源）。
        //
        // A2 接缝对齐（波3 2026-09-12 ①opt-A2，方案 §2.1）：服务端的 event 信封是
        // `{"type": <eventName>, "payload": {…}}`（neblink-server friends.rs
        // `push_to_user_excluding` / `flush_undelivered_friend_events`），
        // **conversationId 在 payload 里**。修前在 event 顶层读 ⇒ 恒 None ⇒
        // 落到 `case None => IO.unit`：未读 +1 与 keyset 增量补拉永不发生
        // （即使通道健康也静默失效——L2）。顶层读取保留为**旧形状容错**分支
        // （扁平信封不再变静默无操作），规范路径 = payload 下钻。
        val convIdOpt = event.hcursor.downField("payload").get[String]("conversationId").toOption
          .orElse(event.hcursor.get[String]("conversationId").toOption)
        convIdOpt match
          case Some(convId) =>
            guard.bumpUnread(convId, 1) *>
              pullConversation(convId).void
                .handleErrorWith(e => logger.warn(s"friend_event pull failed for $convId: ${e.getMessage}"))
          case None =>
            logger.debug(s"message_new without conversationId ignored (neither payload nor flat)")
            IO.unit
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

  /** 拉会话列表，合并未读 cursor。内嵌 `friend` 档案同样盖本地备注（⑦：
    * conversations 面与 `GET /api/friends` 面出参口径一致）。 */
  def refreshConversations(): IO[List[ConversationSummary]] =
    withClient(_.listConversations).flatMap {
      case Left(err) => logger.warn(s"listConversations failed: $err").as(Nil)
      case Right(convs) =>
        applyRemarksToConvs(convs)
          .flatMap(_.traverse(c => guard.mergeUnread(c).map(_ => c)))
          .flatTap(cs => logger.debug(s"Refreshed ${cs.size} conversations"))
    }

  /** 逐会话 keyset 补拉（after=本地最大消息 id），推进本地锚点。未读由
    * 服务端 unreadCount（mergeUnread 基线）+ message_new 事件增量维护。 */
  def pullConversation(conversationId: String): IO[Unit] =
    guard.localMaxId(conversationId).flatMap { after =>
      withClient(_.listMessages(conversationId, after)).flatMap {
        case Left(err) => logger.warn(s"pullConversation $conversationId failed: $err")
        case Right(msgs) if msgs.isEmpty => IO.unit
        case Right(msgs) =>
          val maxId = msgs.map(_.id).max
          guard.advanceAnchor(conversationId, maxId) *>
            logger.info(s"Pulled ${msgs.size} message(s) for $conversationId (maxId=$maxId)")
      }
    }

  /** 好友列表快照（后台刷新语义：失败折叠空列表——refreshAll/事件触发链
    * 不该把上游故障转成 REST 错误）。REST 直通面用 listFriends（F4）。
    * 出口统一注入本地备注（⑦）：`SendMessage` 走本方法 ⇒ 工具侧零取数改动即
    * 拿到备注（`FriendRoster.resolve` 的 L0 层与候选文案都读它）。 */
  def refreshFriends(): IO[FriendListResponse] =
    withClient(_.listFriends).flatMap {
      case Left(err) => logger.warn(s"listFriends failed: $err").as(FriendListResponse(Nil))
      case Right(resp) => applyRemarks(resp)
    }

  /** F4 分态穿透（2026-09-10 好友搜索批）：好友列表 REST 直通——上游 Left
    * 原样上抛（网关折叠 502），前端得以区分「空列表」与「加载失败」。
    * 后台刷新链（refreshAll / friend_event）仍走折叠版 refreshFriends，
    * 不把后台拉取失败泄成 UI 错误。出口同样注入本地备注（⑦）。 */
  def listFriends: IO[Either[String, FriendListResponse]] =
    withClient(_.listFriends).flatMap {
      case Right(resp) => applyRemarks(resp).map(Right(_))
      case Left(err)   => IO.pure(Left(err))
    }

  /** 标记已读（本地 cursor + 服务端）。 */
  def markRead(conversationId: String, lastReadMessageId: Long): IO[Either[String, String]] =
    guard.setRead(conversationId, lastReadMessageId) *>
      withClient(_.markConversationRead(conversationId, lastReadMessageId))

  // ===== Agent 发消息（SendMessage 工具入口） =====

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
    // origin=agent：sendAsAgent 是唯一 agent 代发 choke point（#290 spec v1.1）——
    // wire 缺 origin 时服务器缺省落 "user"，agent 消息语义（徽章/审计/限速区分）失效。
    withClient(_.sendFriendMessage(friendUserId, body, origin = Some("agent"))).flatMap {
      case Left(err) => IO.pure(Left(err))
      case Right(json) =>
        val convId = json.hcursor.get[String]("conversationId").toOption
        convId.traverse_(pullConversation).void *>
          IO.pure(Right("Message sent"))
    }

  // ===== 暴露给 UI 的查询 =====

  def listMessages(conversationId: String, after: Long = 0L, limit: Int = 50): IO[Either[String, List[MessageSummary]]] =
    withClient(_.listMessages(conversationId, after, limit))

  def lookupUser(q: String): IO[Either[String, Json]] = withClient(_.lookupUser(q))

  /** 搜索（friend-search-contract §4.1）：username OR email 双键 NOCASE 精确，
    * 网关代理 → neblink-server /api/users/search（替代旧 lookup）。 */
  def searchUser(q: String): IO[Either[String, Json]] = withClient(_.searchUser(q))

  /** [U3] 自定义 NebLink 号 + 可用性检测（网关代理 → neblink-server）。 */
  def setNeblinkId(neblinkId: String): IO[Either[String, Json]] = withClient(_.setNeblinkId(neblinkId))

  def neblinkIdAvailable(q: String): IO[Either[String, Json]] = withClient(_.neblinkIdAvailable(q))

  def sendFriendRequest(query: String, note: Option[String] = None): IO[Either[String, Json]] =
    withClient(_.sendFriendRequest(query, note))

  def acceptFriendRequest(requestId: String): IO[Either[String, Json]] =
    withClient(_.acceptFriendRequest(requestId))

  def declineFriendRequest(requestId: String): IO[Either[String, String]] =
    withClient(_.declineFriendRequest(requestId))

  /** 删除好友（UI 操作，无权限/限速控制）。 */
  def removeFriend(friendUserId: String): IO[Either[String, String]] =
    withClient(_.removeFriend(friendUserId))

  /** 拉黑好友（#290 §1.2）。 */
  def blockFriend(friendUserId: String): IO[Either[String, String]] =
    withClient(_.blockFriend(friendUserId))

  /** 移出黑名单（仅拉黑方）。 */
  def unblockFriend(friendUserId: String): IO[Either[String, String]] =
    withClient(_.unblockFriend(friendUserId))

  /** 用户身份直接发送（前端 UI 输入框发送；与 agent 的 sendAsAgent 不同，无
    * 权限档位/限速——spec §7.2 限制的是 agent 代发）。发送成功后补拉会话增量。 */
  def sendAsUser(friendUserId: String, body: String): IO[Either[String, Json]] =
    withClient(_.sendFriendMessage(friendUserId, body)).flatMap {
      case Right(json) =>
        json.hcursor.get[String]("conversationId").toOption match
          case Some(convId) => pullConversation(convId).void.handleErrorWith(_ => IO.unit).as(Right(json))
          case None         => IO.pure(Right(json))
      case Left(err) => IO.pure(Left(err))
    }

  def unreadCounts: IO[Map[String, Int]] = guard.unreadSnapshot

end FriendService

/** 推送事件（friend_event 的 event payload 解码形态，供回调消费）。 */
case class FriendEvent(
  eventType: String,
  payload: Json
)

object FriendEvent:
  /** 前端 `friend_event` 帧构造——**唯一实现**（波3 ①opt-A2，方案 §2.1）。
    *
    * 前端契约（`messages.js onMessage('friend_event')`/`contacts.js`）：帧的
    * `event` = 事件名，payload 字段（conversationId/messageId/body/sender/…）**平铺
    * 在帧顶层**。而 `FriendEvent.payload` 拿到的实参来自
    * `FriendService.onFriendEvent` 的 `event` 子对象 = `{"type":…, "payload":{…}}`
    * ——比注释假设的「字段袋」**高一层**。修前直接 `ev.payload.asObject.remove("type")`
    * ⇒ 展平的是外层，帧里多出一层 `payload`，前端按扁平读 ⇒ `conversationId`
    * 恒 `undefined` ⇒ 永远走「未知会话 → refreshConversations()」早退（L3，
    * 打开的聊天窗永不 append）。
    *
    * 修法 = 下钻一级再展平；`orElse(ev.payload.asObject)` 是旧形状容错（已是
    * 字段袋时保持原行为）。`remove("type")` 保留：防事件名键污染帧信封。
    */
  def frontendFrame(ev: FriendEvent): Json =
    val payloadFields = ev.payload.hcursor
      .downField("payload")
      .focus
      .filter(_.isObject)
      .flatMap(_.asObject)
      .orElse(ev.payload.asObject)
      .getOrElse(JsonObject.empty)
      .remove("type")
    Json
      .obj("type" -> "friend_event".asJson, "event" -> ev.eventType.asJson)
      .deepMerge(Json.fromJsonObject(payloadFields))

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
