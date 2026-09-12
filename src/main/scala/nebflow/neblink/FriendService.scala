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
 * 确认链（#147 接线段，2026-09-12）：`askConfirm` = 装配缝注入的实现
 * （`GatewayMain` 传 `nebflow.agent.SendConfirm.production`，即生产运行时执行的
 * 那个函数）；会话靶（提问会话 + 收件人标签）由调用侧按次挂上（fiber-local），
 * 本层只消费 `String => IO[Boolean]` ⇒ UI/agent 依赖不入侵本层。
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

  /** 事件名判据**单点**（段 A 2026-09-12）：
    *  - `message_new`      = 对方所发 ⇒ 补拉 + 未读 +1（§3.4 未读口径 sender!=me）；
    *  - `message_new_self` = **本账号在他处**所发（agent/工具代发，或同一账号的另一台
    *    设备）⇒ 只透传/补拉，**严禁计未读**。
    * 前端 `web/js/messages.js` 的同名常量（`EV_MESSAGE_NEW[_SELF]`）与本对字面量
    * **逐字同名**：两侧不得各写一套（事件名漂移 = 分支静默失配，正是 K-2 的病灶形态）。 */
  private val MessageNew = "message_new"
  private val MessageNewSelf = "message_new_self"

  /** 会话 id 取值的**单点**：规范路径 = `event.payload.conversationId`（服务端信封
    * `{"type":…, "payload":{…}}`），顶层读取保留为**旧形状容错**（扁平信封不再变
    * 静默无操作）。两个 `message_*` 分支共用本方法 ⇒ 字段名只此一处（禁各写一套）。 */
  private def conversationIdOf(event: Json): Option[String] =
    event.hcursor.downField("payload").get[String]("conversationId").toOption
      .orElse(event.hcursor.get[String]("conversationId").toOption)

  /**
   * 处理一条服务端推送（type:"friend_event"，payload {eventId, event}）。
   * eventId 幂等去重后，按 event.type 分发：
   *  - message_new：补拉该会话增量（推送是尽力而为，REST 是事实来源），
   *    更新未读 cursor；
   *  - message_new_self：本账号他处所发 —— 补拉（**不计未读**）；
   *  - friend_request / friend_accepted：刷新好友列表（状态变化）。
   *
   * **广播前移（K-1，段 A 2026-09-12）**：回调广播（GatewayMain 接 WS friendEvent
   * 广播）现在跑在 `handleEvent` **之前**。修前是 `handleEvent *> 回调`，而
   * `handleEvent` 的 message_new 分支是 `bumpUnread *> pullConversation`——一次
   * 串行 REST 往返（本机实测 135.9–499.0 ms，本机 → 本地网关 → neblink-server → 回）
   * 被挡在「通知 UI」之前，属关键路径上**可避免**的串行等待（正本 §3/§4-K1）。
   * 语义代价（**已接受**）：广播早于 `bumpUnread` 一拍——前端未读由帧内容自算
   * （`messages.js` 开窗置 0 / 关窗仅对「对方所发」+1），不依赖后端 cursor，故
   * 未读口径不变；去重仍在最前（`dedupe` 未动）。异常面：广播异常被
   * `handleErrorWith(_ => IO.unit)` 吞掉（best-effort），**不得**吃掉补拉。
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
            // K-1：先广播（best-effort，绝不阻塞/绝不吃掉补拉），再补拉 + 未读维护。
            onFriendEvent.traverse_(cb => cb(FriendEvent(evType, event)).handleErrorWith(_ => IO.unit)) *>
              handleEvent(evType, event) *>
              logger.info(s"friend_event processed: type=$evType id=$eventId")
        }

  private def handleEvent(evType: String, event: Json): IO[Unit] =
    evType match
      case MessageNew =>
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
        conversationIdOf(event) match
          case Some(convId) =>
            // #309：cursor 缺席（boot 期空 map / 全新会话，推送先于会话刷新到达）
            // 时 bumpUnread 会建条目并回落 +1；返回值为回落信号 ⇒ 显式留痕。
            // 禁静默吞掉这条 +1（本仓反复复现的「错误路径折成静默成功」缺陷族）。
            guard.bumpUnread(convId, 1).flatMap { materialized =>
              val trace =
                if materialized then
                  logger.warn(
                    s"message_new for $convId arrived with no local cursor — materialized with unread=1 " +
                      "(push preceded conversations refresh; server unreadCount stays authoritative baseline)"
                  )
                else IO.unit
              trace *>
                pullConversation(convId).void
                  .handleErrorWith(e => logger.warn(s"friend_event pull failed for $convId: ${e.getMessage}"))
            }
          case None =>
            logger.debug(s"message_new without conversationId ignored (neither payload nor flat)")
            IO.unit
      case MessageNewSelf =>
        // K-2（段 A 2026-09-12）：**本账号在他处所发**（agent/工具代发，或同一账号的
        // 另一台设备）。修前该类型落到下面的 `case other` 被**整条丢弃** ⇒ 本机既不补拉
        // 也不进广播 ⇒ 会话预览/角标/开着窗全都不动，只有「关窗再开」（重挂载取数）
        // 才可见（正本 §2(c)/§4-K2）。
        // 语义 = 只透传（广播由 onFriendEvent 后置统一做）+ 补拉；**严禁 bumpUnread**
        // ——自送消息不计未读（§3.4 口径 sender != me；前端亦按同一口径判定）。
        // 与 `message_new` 的差别**只有**这一处（不调 bumpUnread），故不复制未读面。
        //
        // 归属注：本条**不含发送侧补广播**（K-3 / `doSend`、`GatewayMain`）——那属段 B，
        // 本分支只保证「服务端已推来的 self 事件」不再被丢弃。
        conversationIdOf(event) match
          case Some(convId) =>
            pullConversation(convId).void
              .handleErrorWith(e => logger.warn(s"friend_event pull failed for $convId: ${e.getMessage}"))
          case None =>
            logger.debug(
              s"$MessageNewSelf without conversationId ignored (neither payload nor flat)"
            )
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
   *  - ask：每次弹确认（**确认未回 ⇒ 超时 ⇒ 不发送**，且错误文案可判定）；
   *  - off：直接返回禁用。
   * 超长消息（>4000）与服务端 403 等错误原样返回。
   *
   * 确认链（#147 接线段，2026-09-12）：`askConfirm` = 装配缝注入的确认实现
   * （`GatewayMain` 经 `NeblinkWiring.friendService` 接 `nebflow.agent.SendConfirm.production`；
   * 会话靶由调用侧 `SendConfirm.locally` 按次挂上——理由与代码锚见该文件头）。
   * 本层只见 `String => IO[Boolean]`，不依赖任何 UI/agent 类型。
   *
   * 语义（两条都是 fail-closed，不存在静默路径）：
   *  - 确认通过 ⇒ 投递；拒绝 ⇒ `Left("User declined the message")`；
   *  - 确认**失败**（无交互面 / 超时 / hub 未起）⇒ `Left("Confirmation failed — … NOT sent")`
   *    —— 既不投递，也不伪装成「用户拒绝」（两种条件归因不同，调用方/模型可区分）。
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
      case Some(confirmFn) =>
        confirmFn(body)
          .flatMap {
            case true => doSend(friendUserId, body)
            case false =>
              logger.info(s"SendMessage declined by the user — nothing sent to $friendUserId") *>
                IO.pure(Left("User declined the message"))
          }
          .handleErrorWith { e =>
            // 确认链失败 ≠ 用户拒绝：显式失败（不发送），文案带原因，供模型/用户判定。
            // 绝不落入「静默本地执行」或「静默成功」。
            val why = Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)
            logger.warn(s"SendMessage confirmation failed (message NOT sent, friend=$friendUserId): $why") *>
              IO.pure(Left(s"Confirmation failed — the message was NOT sent: $why"))
          }

  private def doSend(friendUserId: String, body: String): IO[Either[String, String]] =
    // origin=agent：sendAsAgent 是唯一 agent 代发 choke point（#290 spec v1.1）——
    // wire 缺 origin 时服务器缺省落 "user"，agent 消息语义（徽章/审计/限速区分）失效。
    withClient(_.sendFriendMessage(friendUserId, body, origin = Some("agent"))).flatMap {
      case Left(err) => IO.pure(Left(err))
      case Right(json) =>
        val convId = json.hcursor.get[String]("conversationId").toOption
        // K-3（段 B 2026-09-12）：**先自播（通知本机 UI），再补拉**。次序理由与 K-1
        // 同源——补拉是一次串行 REST 往返（本机实测 135.9–499.0 ms），不得挡在
        // 「通知 UI」之前；自播走 wsHub 广播，零上游往返。
        convId.traverse_(replaySelfSend(_, json, body)) *>
          convId.traverse_(pullConversation).void *>
          IO.pure(Right("Message sent"))
    }

  /** K-3（段 B 2026-09-12）：agent 代发的**本机自播**——把刚发出的消息按服务端
    * `message_new_self` 帧**同形**回放给本机浏览器。
    *
    * 为什么本机收不到服务端那条 self 帧：服务端 self 扇出是
    * `push_to_user_excluding(&user_id, origin_device.as_deref(), …)`
    * （neblink-server `friends.rs#send_message`，**排除发起设备**），而本网关正是发起
    * 设备（`NeblinkClient` 用 pairing 换来的 device session token ⇒ 服务端
    * `require_user_or_device_with_device` 解出 `origin_device`）。于是 agent 代发
    * （`SendMessage` 工具）这条消息在本机 UI 上**零事件**：会话预览 / 角标 / 开着窗
    * 全都不动，只有「关窗再开」（重挂载取数）才可见（正本 §3 表末行 / §4-K3）。
    *
    * 复用**既有装配缝**（禁新造第二条广播路径）：走构造期注入的 `onFriendEvent`
    * 回调——生产接线即 `GatewayMain.scala:782` 的
    * `wsHub.broadcast(FriendEvent.frontendFrame(ev))`，与本函数是**同一条**
    * `frontendFrame` 通道 ⇒ 前端契约形状零改动，不需要任何 web 侧改动。
    *
    * 信封与真 push **逐字段同形**（`{"type": <事件名>, "payload": {messageId,
    * conversationId, kind, body, origin, createdAt}}`）：`frontendFrame` 的
    * 下钻 → 展平对两种来源走**同一段代码**，「本机自播」与「他机 push」在前端落到
    * 同一分支（K-2 的 `EV_MESSAGE_NEW_SELF`）同一解析器。
    *
    * 红线：
    *  - **不计未读**：本函数**不**经 `handleEvent`（故绝不触 `bumpUnread`）；前端
    *    self 分支亦不动 `unreadCount`（§3.4 口径 sender != me）。
    *  - 发送侧只此一处：`doSend` 的两条调用点（auto 直发 / ask 批准后直发）互斥，
    *    本函数不在任何重试或循环里 ⇒ 一条消息至多一帧。
    *  - 用户 UI 直发（`sendAsUser`）**不经过本函数**（本文件 `sendAsUser` 只补拉），
    *    故乐观上屏路径零影响。
    *
    * 缺 `messageId` / `createdAt` 时**不发帧**并留 WARN：帧没有消息身份 ⇒ 前端
    * keyed diff（⑨-E 按 `data-message-id` 复用节点）会落一个 `undefined` 幽灵
    * 气泡，比「不动」更坏；此时退化为改动前形态（挂载时取数）且**显式留痕**，不静默。
    */
  private def replaySelfSend(conversationId: String, resp: Json, body: String): IO[Unit] =
    val h = resp.hcursor
    (h.get[Long]("messageId").toOption, h.get[Long]("createdAt").toOption) match
      case (Some(messageId), Some(createdAt)) =>
        val envelope = Json.obj(
          "type" -> MessageNewSelf.asJson,
          "payload" -> Json.obj(
            "messageId" -> Json.fromLong(messageId),
            "conversationId" -> conversationId.asJson,
            "kind" -> "text".asJson,
            "body" -> body.asJson,
            "origin" -> "agent".asJson,
            "createdAt" -> Json.fromLong(createdAt)
          )
        )
        onFriendEvent.traverse_(cb =>
          cb(FriendEvent(MessageNewSelf, envelope)).handleErrorWith(e =>
            logger.warn(s"self-send replay broadcast failed for $conversationId: ${e.getMessage}")
          )
        )
      case _ =>
        logger.warn(
          s"agent send response lacks messageId/createdAt (conversationId=$conversationId) — " +
            "local self replay skipped (UI falls back to the pull-on-mount path)"
        )

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

  /** 未读增量（message_new 事件：推送必为对方所发 → +1）。
    *
    * 返回值 = 本次是否因 cursor **缺席**而新建了条目（回落信号，供调用方留痕，
    * #309）。**cursor 缺席时建条目再 +delta，不得 no-op**——缺席不是异常态：
    * cursor map 在进程 boot 时为空（本类 `Ref.unsafe(St(…, Map.empty))`），由
    * `refreshAll → refreshConversations → mergeUnread` 才逐会话填充，而 relay
    * 隧道推送可能先于/独立于该刷新到达（重连窗口、全新会话的首条推送）。此前
    * 本方法是 cursor 变更面**唯一**不 materialize 条目的入口（`advanceAnchor` /
    * `mergeUnread` / `setRead` 三条兄弟路径都走
    * `getOrElse(id, ConversationCursor(id, 0L, 0))`）⇒ 该窗口内的 +1 被静默吞掉，
    * 未读角标永不亮（#309）。
    *
    * 口径不变（spec §3.4）：本方法只做**增量**；服务端 `unreadCount` 仍是权威
    * 基线（`mergeUnread` 全量覆盖 ⇒ 此处预置的 delta 会在下一次会话刷新时被对齐，
    * 不产生重复计数），`setRead` 清零——不新增第二套口径。
    */
  def bumpUnread(conversationId: String, delta: Int): IO[Boolean] =
    state.modify { s =>
      s.cursors.get(conversationId) match
        case Some(c) =>
          (s.copy(cursors = s.cursors.updated(conversationId, c.copy(unreadCount = c.unreadCount + delta))), false)
        case None =>
          // 缺席回落：materialize 条目（锚点 0 = 尚未见过消息，与三条兄弟路径
          // 的缺省构造逐字一致），未读直接落 delta 而不是被吞掉。
          (s.copy(cursors = s.cursors.updated(conversationId, ConversationCursor(conversationId, 0L, delta))), true)
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
