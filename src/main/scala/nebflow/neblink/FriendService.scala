package nebflow.neblink

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.JsonObject
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.core.NebflowLogger
// 补拉帧（`dispatchPulled:521`）要对 `attachments` 做 `asJson` ⇒ 需
// `Encoder[AttachmentSummary]`。该类码与全部 neblink 线上 codec 同在 `FriendCodecs`
// —— 它是**普通 object**（非 `AttachmentSummary` 伴生对象）⇒ given **不在**隐式域内，
// 必须显式引入；本文件此前只编原生类型故从未引入（批 A 首次落 `.asJson` 时漏掉）。
// 形式与同包两个既有消费点逐字一致（`NeblinkClient.scala:96`、`RestApiRoutes.scala:30`）。
// 🔴 只**引入**既有 given：不新增第二份 codec、不改写既有 given、不遮蔽任何候选。
import nebflow.neblink.FriendCodecs.given

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
/** 补拉的**触发源**（显式字面量，进结构化行的 `trigger=` 字段）。
  *
  * 为什么必须有这个字段（判据①）：修前只能靠「这条 pull 有没有配对的 `processed`
  * 行」**反推**它是不是事件触发的——那是间接证据：事件支路一改（新增/合并分支）
  * 这个反推就静默失真。改成调用方**当场声明**的字面量后，配对判据变成
  * 「`friend_pull … eventId=<X>` ⟺ `friend_event processed … id=<X>`」的直接对账，
  * 不再依赖分支拓扑。自送条（`send:*`）与客户端自播由此与事件触发的补拉**天然可分**，
  * 这正是判据①要求「以显式 trigger 字段区分」的落点。
  *
  * 取值冻结（**跨批契约**：批 C 的对账拍复用同一字段与同一行格式，禁另起一套）：
  *  - `event:message_new` / `event:message_new_self` —— 隧道事件触发（唯一带 `eventId=`）；
  *  - `refresh_all` —— 启动/重连的全量补拉（`refreshAll`）；
  *  - `send:agent` —— agent 代发后的**自送**补拉（`doSend`）；
  *  - `send:user` —— UI 直发后的**自送**补拉（`sendAsUser`）；
  *  - `reconcile` —— **批 C 对账拍**（`reconcileConversations`）：非事件触发、无
  *    `eventId`，判据①的配对不变式因此不受影响（`isEventTriggered` 只认 `event:`
  *    前缀，单点判据未改）。 */
object FriendPullTrigger:
  val EventMessageNew: String = "event:message_new"
  val EventMessageNewSelf: String = "event:message_new_self"
  val RefreshAll: String = "refresh_all"
  val SendAsAgent: String = "send:agent"
  val SendAsUser: String = "send:user"
  /** §3.6 对账拍（批 C）：45s 拍上「服务端 keyset 水位 vs 本地 `dispatchedMax`」
    * 的差态补齐。**不是** `event:` 族 ⇒ 不带 `eventId`、不参与判据①配对。 */
  val Reconcile: String = "reconcile"

  /** 事件触发族判据（**单点**：判据①「事件触发的 pull」只按本方法判，禁各处写死字面量）。
    * 只有事件触发的补拉才携带 `eventId` ⇒ 与 `processed` 行配对。 */
  def isEventTriggered(trigger: String): Boolean =
    trigger.startsWith("event:")

/** 一次补拉的**记账**（§3.7 对账计数；按会话累加，进程内累计值，不落盘）。
  *
  * 恒等式 `pulled == dispatched + skipped` 由 `pulled`/`skipped` **同源推出**
  * （`skipped = pulled - dispatched`）⇒ 计数与对账日志行不可能各说各话。
  * top-level 定义（不嵌在 `FriendMessagingGuard` 里）：避免路径依赖类型，
  * 使「批 C 暴露 `/api/neblink/status`」与各 spec 都能直接引用同一类型。 */
final case class FriendPullStats(pulled: Long, dispatched: Long, skipped: Long)

final class FriendService(
  currentClient: IO[Option[NeblinkClient]],
  config: AgentMessagingConfig,
  guard: FriendMessagingGuard = new FriendMessagingGuard(),
  onFriendEvent: Option[FriendEvent => IO[Unit]] = None,
  askConfirm: Option[String => IO[Boolean]] = None,
  remarkRef: Ref[IO, Map[String, String]] = Ref.unsafe[IO, Map[String, String]](Map.empty),
  /** D-B（2026-09-13）：送达确证发送面。`None` = 未接线（测试 / 旧装配）——显式
    * no-op，不静默走「第二条实现」。生产由 `GatewayMain` 接到 `NeblinkRelayTunnel.sendAck`
    * （帧编码只在那一边，本层只决定「什么时候 ack 哪个 eventId」）。 */
  ackSender: Option[String => IO[Unit]] = None
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

  /** 消息推送 eventId 前缀 —— 与 neblink-server `message_event_id`
    * （`format!("message-{message_id}")`，`src/relay.rs:133-134`）**逐字同名**；
    * 服务端用 `message_receipt_message_id`（`:141-145`）反解成消息 id。
    * 本侧只**透传原帧 eventId**，不二次拼装（帧形状冻结，只此一处前缀判据）。 */
  private val MessageEventIdPrefix = "message-"

  /** D-B ack 生产者（客户端半边）：把「本设备已持久处理该事件」告诉服务端。
    *
    * 何时 ack（语义边界）：**处理完成之后**，且**重复帧也 ack**——
    *   · 首次：处理（广播 + 补拉 + 未读）完成后 ack；
    *   · 重复（eventId 已见）：这是 at-least-once 重放路径。首帧的 ack 若在链路上
    *     丢了，服务端下次隧道注册会重放；客户端去重后若**不再** ack，重放将永远
    *     退不掉（活锁）⇒ 重复路径必须补 ack。
    *
    * 只 ack 消息面（`message-<id>`）：那是本批冻结的靶（写 `sent` 回执）；
    * `friend-evt-<rowId>` 那支会推进服务端 durable 重放游标，属 S2 服务端账本批
    * 的范畴（🔴 本批禁跨仓），本侧不越界。
    *
    * 失败口径：best-effort，绝不抛出 —— ack 挂在消费链尾部，任何异常都不得反过来
    * 吃掉消费/广播（那才是会丢消息的方向）。ack 丢 = 服务端下次重放 = 无正确性代价。 */
  private def ackProcessed(eventId: String): IO[Unit] =
    ackSender match
      case Some(send) if eventId.startsWith(MessageEventIdPrefix) =>
        send(eventId).handleErrorWith(e => logger.debug(s"ack send failed for $eventId: ${e.getMessage}"))
      case _ => IO.unit

  /** 会话 id 取值的**单点**：规范路径 = `event.payload.conversationId`（服务端信封
    * `{"type":…, "payload":{…}}`），顶层读取保留为**旧形状容错**（扁平信封不再变
    * 静默无操作）。两个 `message_*` 分支共用本方法 ⇒ 字段名只此一处（禁各写一套）。 */
  private def conversationIdOf(event: Json): Option[String] =
    event.hcursor.downField("payload").get[String]("conversationId").toOption
      .orElse(event.hcursor.get[String]("conversationId").toOption)

  /** 事件自带的消息 id 取值**单点**（与 [[conversationIdOf]] 同形：规范路径在
    * `payload` 内，顶层为旧形状容错）。用途 = **冷锚**补拉的取数起点提示
    * （`after = hint - 1`）：进程刚起、该会话从未拉过时，据此取**恰好那一窗**，
    * 既拿到被吞的那条，又不会把一页最旧的消息当成新消息塞进最新窗口。
    * 取不到 ⇒ `None` ⇒ 冷锚腿退化为不取数（见 `pullConversation`）。 */
  private def messageIdOf(event: Json): Option[Long] =
    event.hcursor.downField("payload").get[Long]("messageId").toOption
      .orElse(event.hcursor.get[Long]("messageId").toOption)

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
  /** 留痕行的**发射单点**：**同一行文本**既进日志、又进 `guard` 的有界 trace 环
    * （判据①–④的机器读数面；批 C 的 `/api/neblink/status` 也读它）。
    * 两份文本不可能漂移——因为压根只有一份。 */
  private def emit(level: String, line: String): IO[Unit] =
    guard.recordPullLine(line) *>
      (if level == "warn" then logger.warn(line) else logger.info(line))

  /** §3.3 静默面统一留痕格式（W1–W8 与 §3.7 结构化行**一次定型**，批 C 复用禁另起）。
    *
    * 判据②要求每条丢弃分支的 WARN **行内同时**含 `messageId`（或 `eventId`）、
    * `conversationId`、`reason` 三者——本方法把三者都落成**键名=值**形态（不是散文
    * 措辞），使「应报数 == 三字段同行的出现数」可机械计数。缺席一律写 `<none>`
    * 而不是省键：省键会让「该分支没有会话上下文」与「本行不是本格式」同形。 */
  private def droppedWarn(
      branch: String,
      conversationId: Option[String],
      messageId: Option[Long] = None,
      eventId: Option[String] = None,
      reason: String,
      extra: String = ""
  ): IO[Unit] =
    val idPart =
      messageId.map(v => s"messageId=$v").orElse(eventId.map(v => s"eventId=$v")).getOrElse("messageId=<none>")
    emit(
      "warn",
      s"friend_event_dropped branch=$branch conversationId=${conversationId.getOrElse("<none>")} " +
        s"$idPart reason=$reason" + (if extra.isEmpty then "" else s" $extra")
    )

  def onFriendEvent(payload: Json): IO[Unit] =
    (for
      eventId <- payload.hcursor.get[String]("eventId").toOption
      event <- payload.hcursor.get[Json]("event").toOption
      evType <- event.hcursor.get[String]("type").toOption
    yield (eventId, event, evType)) match
      case None =>
        // W8（原状即为 WARN，本轮**补齐三字段**：判据②要求本行同时含 messageId/eventId +
        // conversationId + reason；畸形帧解析不出会话/消息 id ⇒ 显式写 <none>，
        // 并列出**期望字段名清单**，让「缺哪个键」一眼可判，不必再读 payload）。
        droppedWarn(
          branch = "W8",
          conversationId = None,
          messageId = None,
          reason = "malformed_friend_event",
          extra = s"expectedKeys=[eventId,event.type] payload=${payload.noSpaces.take(200)}"
        )
      case Some((eventId, event, evType)) =>
        guard.dedupe(eventId).flatMap {
          case false =>
            // W3（§3.3）：修前是 DEBUG ⇒ 这条**升 INFO**。语义要点：eventId 重复
            // **不代表消息已送达 UI** —— 首帧可能广播失败/浏览器未挂载，本条若仍
            // 寂静无声，「丢的那条」就没有任何痕迹。升级后重放路径可见。
            //
            // 🔴 与取证稿 §3.3 W3「并补一次幂等派发」的**显式偏离**（已申报）：该处
            // 若再触发一次 pull，就会出现一条**没有配对 `processed` 行**的补拉 ⇒ 判据①
            // 的配对不变式（∀ 事件触发 pull 有配对 processed）不成立。而此处**无需**补派发
            // 即已满足意图：真正需要补派发的两条支路（message_new / message_new_self）
            // 在**首见**时必定 pull（下方 case true），重放只是同一条幂等腿的重复；
            // 去重路径保持 ack-only ⇒ 配对判据与可达性同时成立。
            emit(
              "info",
              // 字段名统一用 `eventId=`（判据②的「messageId 或 eventId」可取到的那个）：
              // 本条是**新**行，字段名由本批定型，不与既有 `processed` 的 `id=` 混用。
              s"friend_event duplicate eventId=$eventId type=$evType conversationId=" +
                s"${conversationIdOf(event).getOrElse("<none>")} reason=duplicate_eventId " +
                "(at-least-once replay; already acked, pull skipped — pull is idempotent on first sight)"
            ) *>
              // at-least-once 重放：去重后仍须 ack（否则服务端重放永不退掉）。
              ackProcessed(eventId)
          case true =>
            // K-1：先广播（best-effort，绝不阻塞/绝不吃掉补拉），再补拉 + 未读维护。
            // W4（§3.3）：广播异常修前被 `handleErrorWith(_ => IO.unit)` **静默**吞掉
            // ——best-effort 语义不变（仍继续补拉），但**必须留痕**：UI 没收到帧是
            // 「前端不刷新」类症状的第一嫌疑，零痕迹等于不可归因。
            (onFriendEvent.traverse_(cb =>
              cb(FriendEvent(evType, event)).handleErrorWith(e =>
                droppedWarn(
                  branch = "W4",
                  conversationId = conversationIdOf(event),
                  messageId = event.hcursor.get[Long]("messageId").toOption
                    .orElse(event.hcursor.downField("payload").get[Long]("messageId").toOption),
                  eventId = Some(eventId),
                  reason = "broadcast_failed",
                  extra = s"type=$evType err=${e.getClass.getSimpleName}: ${e.getMessage}"
                )
              )
            ) *>
              handleEvent(evType, event, eventId) *>
              // 走 `emit`（同一行既进日志、又进 trace 环）⇒ 判据①的**配对判据**可在真
              // 调用链上机械断言：「∀ `friend_pull … eventId=X` 存在同 X 的 processed 行」。
              emit("info", s"friend_event processed: type=$evType id=$eventId")) *>
              // D-B：**处理完成后**才 ack（「已持久处理」而不是「已收帧」）。
              ackProcessed(eventId)
        }

  private def handleEvent(evType: String, event: Json, eventId: String): IO[Unit] =
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
                pullConversation(
                  convId,
                  FriendPullTrigger.EventMessageNew,
                  Some(eventId),
                  oursHint = Some(false),
                  afterHint = messageIdOf(event)
                ).void
                  .handleErrorWith(e => logger.warn(s"friend_event pull failed for $convId: ${e.getMessage}"))
            }
          case None =>
            // W5（§3.3）：修前 DEBUG ⇒ **升 WARN**。这是「未读 +1 与补拉**双失效**」
            // 的 L2 形态：帧到了、事件名对、就是拿不到会话 ⇒ 该条消息在本机**零痕迹**。
            // 升 WARN 后它与「通道健康但什么都不发生」的观测直接可区分。
            droppedWarn(
              branch = "W5",
              conversationId = None,
              eventId = Some(eventId),
              reason = "message_new_without_conversationId",
              extra = s"event=$evType payload=${event.noSpaces.take(200)}"
            )
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
            pullConversation(
              convId,
              FriendPullTrigger.EventMessageNewSelf,
              Some(eventId),
              oursHint = Some(true),
              afterHint = messageIdOf(event)
            ).void
              .handleErrorWith(e => logger.warn(s"friend_event pull failed for $convId: ${e.getMessage}"))
          case None =>
            // W6（§3.3）：与 W5 同族（self 面），同样 DEBUG ⇒ **升 WARN**。
            droppedWarn(
              branch = "W6",
              conversationId = None,
              eventId = Some(eventId),
              reason = "message_new_self_without_conversationId",
              extra = s"event=$evType payload=${event.noSpaces.take(200)}"
            )
      case "friend_request" | "friend_accepted" =>
        refreshFriends().void.handleErrorWith(e => logger.warn(s"friend event refresh failed: ${e.getMessage}"))
      case other =>
        // W7（§3.3）：未知事件类型修前是 DEBUG ⇒ **升 WARN**。判据 = 「未知类型 =
        // 契约漂移信号」：服务端新增事件名而客户端未接 ⇒ 该族事件在本机**整类静默**，
        // 只有 WARN 能让它与「上游压根没推」分开。
        droppedWarn(
          branch = "W7",
          conversationId = conversationIdOf(event),
          eventId = Some(eventId),
          reason = "unhandled_friend_event_type",
          extra = s"type=$other"
        )

  // ===== 补拉与刷新（启动时 / 隧道重连时 / 事件触发） =====

  /** 全量刷新：会话列表 + 逐会话增量补拉。重连/启动入口。 */
  def refreshAll(): IO[Unit] =
    refreshConversations().flatMap { convs =>
      convs.traverse_(c => pullConversation(c.conversationId, FriendPullTrigger.RefreshAll))
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

  /** 逐会话 keyset 补拉的**单页上限**（= `NeblinkClient.listMessages` 缺省 50）。
    * 显式写出来是因为「满页 ⇒ 服务端还有更多」这个判据要用到它；禁改客户端缺省。 */
  private val PullPageLimit = 50

  /** §3.7 结构化对账行的**唯一**装配点（批 C 的对账拍复用同一字段集与同一顺序，
    * 🔴 禁另起一套格式——两份格式 = 两套判据，正是本仓「缺陷族」的典型形态）。
    *
    * 字段集（判据③④直接读它）：
    *  `conversationId` / `trigger` / `eventId` / `after` / `serverMax` /
    *  `pulled` / `dispatched` / `skipped` / `reason` / `reasons=[…]` /
    *  `pullAnchor` / `dispatchedMax`。
    * 恒等式 `pulled == dispatched + skipped` **按构造保证**（skipped = pulled - dispatched）。
    *
    * 🔴 **批 C 补键（本行格式的加性扩键，非改语义）**：`reasons=[]` 是**列表**形态，
    * 空列表时行内**没有任何 `reason=` 键** —— 与 `droppedWarn` 已定型的「三字段必须
    * 同行、缺键显式写 `<none>`」口径不一致（W2 空拍行即此形态）。本批补 **`reason=`
    * 单值键**：有跳过原因时取 `reasons` 首项（与 `pullSkipWarn` 的 `reason=` **同源同
    * 取值** ⇒ 两行可交叉核对），无跳过原因时显式写 `<none>`。位置**追加在行尾**，
    * 既有字段名与相对顺序零变动（既有断言器的 `\breason=(\S+)` 不会误命中 `reasons=`）。 */
  private def pullLine(
      conversationId: String,
      trigger: String,
      eventId: Option[String],
      after: Long,
      serverMax: Long,
      pulled: Int,
      dispatched: Int,
      skipped: Int,
      reasons: List[String],
      anchorAfter: Long,
      dispatchedMaxAfter: Long
  ): IO[Unit] =
    // §3.7 计数与日志行**同源同拍**：计数器在写行之前累加，两个读数不可能各说各话
    // （批 C 暴露 `/api/neblink/status` 时读的就是这份累加值）。
    guard.recordPull(conversationId, pulled.toLong, dispatched.toLong, skipped.toLong) *>
      emit(
        "info",
        s"friend_pull conversationId=$conversationId trigger=$trigger " +
          s"eventId=${eventId.getOrElse("<none>")} after=$after serverMax=$serverMax " +
          s"pulled=$pulled dispatched=$dispatched skipped=$skipped " +
          s"reasons=[${reasons.distinct.mkString(",")}] " +
          s"pullAnchor=$anchorAfter dispatchedMax=$dispatchedMaxAfter " +
          s"reason=${reasons.headOption.getOrElse("<none>")}"
      )

  /** 跳过态的**独立** WARN（判据④「`skipped>0` ⇒ 同行含 `reasons=[…]` **且另有 WARN**」）。
    * 判据②要求本行**同时**含 `messageId` + `conversationId` + `reason` —— 三者都在，
    * 且 `reasons=[…]` 与对账行同源同拼法 ⇒ 两行可交叉核对。 */
  private def pullSkipWarn(
      conversationId: String,
      trigger: String,
      eventId: Option[String],
      pulled: Int,
      dispatched: Int,
      skipped: Int,
      reasons: List[String],
      skippedIds: List[Long],
      anchorAfter: Long,
      dispatchedMaxAfter: Long
  ): IO[Unit] =
    emit(
      "warn",
      s"friend_pull_skipped conversationId=$conversationId trigger=$trigger " +
        s"eventId=${eventId.getOrElse("<none>")} pulled=$pulled dispatched=$dispatched skipped=$skipped " +
        s"messageId=${skippedIds.headOption.getOrElse(-1L)} reason=${reasons.headOption.getOrElse("unknown")} " +
        s"reasons=[${reasons.distinct.mkString(",")}] skippedIds=[${skippedIds.mkString(",")}] " +
        s"pullAnchor=$anchorAfter dispatchedMax=$dispatchedMaxAfter"
    )

  /** 补拉帧构造（§3.2①「拉取即派发」）：与真 push **同形**，复用 `frontendFrame`
    * 单实现 + 构造期注入的 `onFriendEvent` 广播缝（生产 = `wsHub.broadcast`）。
    * 仅两个**加性**字段（`senderId` / `backfill`），理由见 `pullConversation` scaladoc。 */
  private def dispatchPulled(
      conversationId: String,
      m: MessageSummary,
      oursHint: Option[Boolean]
  ): IO[Unit] =
    val eventName = if oursHint.contains(true) then MessageNewSelf else MessageNew
    val envelope = Json.obj(
      "type" -> eventName.asJson,
      "payload" -> Json.fromFields(
        List(
          Some("messageId" -> Json.fromLong(m.id)),
          Some("conversationId" -> conversationId.asJson),
          Some("senderId" -> m.senderId.asJson),
          Some("kind" -> m.kind.asJson),
          Some("body" -> m.body.asJson),
          Some("createdAt" -> Json.fromLong(m.createdAt)),
          Some("backfill" -> true.asJson),
          m.attachments.filter(_.nonEmpty).map("attachments" -> _.asJson),
          m.origin.filter(_.nonEmpty).map("origin" -> _.asJson)
        ).flatten
      )
    )
    onFriendEvent.traverse_(cb =>
      cb(FriendEvent(eventName, envelope)).handleErrorWith(e =>
        emit(
          "warn",
          // 🔴 批 C 去歧义（W1 一名两处）：`W1` 是**族标签**（取证稿 §3.3 W1 =
          // 「拉取到但没派发」族），本行是族内**派发面**子分支 ⇒ 子码 `W1a`。
          // 同族取数面 = `W1b`（见 `dispatchPull` 的 `friend_pull_failed`）。
          // 为何必须拆：两处同写 `branch=W1` 时「应报数 == 出现数」**不可机械计数**
          // （一个 needle 命中两行、两种成因）。子码唯一化后逐个可数，且族前缀
          // `W1` 保留 ⇒ 与取证稿的 W 编号对照关系不丢。
          s"friend_pull_dispatch_failed branch=W1a conversationId=$conversationId messageId=${m.id} " +
            s"reason=broadcast_failed err=${e.getClass.getSimpleName}: ${e.getMessage}"
        )
      )
    )

  /** 补拉主体（单页）：取数 → 逐条过**派发门** → 先派发、后推进 → 落对账行。
    *
    * 🔴 推进语义（判据③）：`pullAnchor` 与 `dispatchedMax` 由**同一个原子调用**
    * （`guard.advanceAnchor`）同时前进，且只前进到**派发成功的最大 id** ⇒
    * `pullAnchor == dispatchedMax` 恒成立，**禁** `pullAnchor > dispatchedMax`
    * （越过未派发条 = 那条此后永久不可达，正是本批要修的病灶）。 */
  private def dispatchPull(
      conversationId: String,
      trigger: String,
      eventId: Option[String],
      oursHint: Option[Boolean],
      after: Long
  ): IO[Unit] =
    for
      anchorNow <- guard.pullAnchor(conversationId)
      sentBefore <- guard.dispatchedMaxOf(conversationId)
      _ <- withClient(_.listMessages(conversationId, after, PullPageLimit)).flatMap {
          case Left(err) =>
            // 取数失败：恒等式退化为 0 == 0 + 0（可机械断言）；失败本身必须可见。
            // 行内报**真实**水位（`anchorNow`，不是请求参数 `after`）——冷锚腿的
            // `after = hint - 1` **不是**水位，混用会让 `pullAnchor <= dispatchedMax`
            // 这条自我断言在同一条日志行上被自己的读数打脸。
            pullLine(conversationId, trigger, eventId, after, after, 0, 0, 0, Nil, anchorNow, sentBefore) *>
              emit(
                "warn",
                // 🔴 批 C 去歧义：同族**取数面**子分支 ⇒ `W1b`（族前缀 `W1` 保留）。
                s"friend_pull_failed branch=W1b conversationId=$conversationId trigger=$trigger " +
                  s"messageId=<none> reason=list_failed err=$err pullAnchor=$anchorNow dispatchedMax=$sentBefore"
              )
          case Right(msgs) if msgs.isEmpty =>
            // W2（§3.3）：空拉取是**常态**（没有新消息）⇒ **不报 WARN**；但必须**计入对账**
            // ——修前这里是 `IO.unit`（零输出），于是「拉过但没拉到」与「压根没拉」同形，
            // 判据④的恒等式在空拍上不可核。
            pullLine(conversationId, trigger, eventId, after, after, 0, 0, 0, Nil, anchorNow, sentBefore)
          case Right(msgs) =>
            val serverMax = msgs.map(_.id).max
            val dispatched = scala.collection.mutable.ListBuffer.empty[MessageSummary]
            val skippedIds = scala.collection.mutable.ListBuffer.empty[Long]
            val reasons = scala.collection.mutable.ListBuffer.empty[String]
            val seen = scala.collection.mutable.Set.empty[Long]
            val advanceable = scala.collection.mutable.ListBuffer.empty[Long]
            msgs.foreach { m =>
              val why =
                if m.id <= 0L then Some("unframable_message_id")
                else if !seen.add(m.id) then Some("duplicate_in_page")
                else if m.id <= sentBefore then Some("not_ahead_of_dispatchedMax")
                else None
              why match
                case None => dispatched += m
                case Some(r) =>
                  skippedIds += m.id
                  reasons += r
                  // 无身份键（id ≤ 0）与页内重复**不得**卡住锚点：它们没有可回补的身份，
                  // 永远停在锚点前会造成同一条被无限重取。其余（重叠重取）本就不在锚点之后。
                  if r == "unframable_message_id" || r == "duplicate_in_page" then advanceable += m.id
            }
            val pulled = msgs.size
            val dispatchedN = dispatched.size
            val skippedN = pulled - dispatchedN
            // 🔴 只在**真有派发成功**时前进；`newMark` 取「派发成功的最大 id」（含
            // 必须越过的无身份/重复条）。没有任何派发 ⇒ `None` ⇒ **不调 advanceAnchor**：
            // 「推进而未派发」正是本批要修的病灶，绝不能在修复里复现。
            val newMark = (dispatched.map(_.id).toList ++ advanceable.toList).maxOption
            dispatched.toList.traverse_(m => dispatchPulled(conversationId, m, oursHint)) *>
              newMark.traverse_(mk => guard.advanceAnchor(conversationId, mk)) *>
              // 行进终态从 guard **读回**（不靠本地推导）：日志行永远等于真实状态，
              // 判据③的不变式因此在**行内**自带自洽性。
              (for
                pa <- guard.pullAnchor(conversationId)
                dm <- guard.dispatchedMaxOf(conversationId)
                _ <- pullLine(
                  conversationId, trigger, eventId, after, serverMax,
                  pulled, dispatchedN, skippedN, reasons.toList, pa, dm
                )
                _ <-
                  if skippedN > 0 then
                    pullSkipWarn(
                      conversationId, trigger, eventId, pulled, dispatchedN, skippedN,
                      reasons.toList, skippedIds.toList, pa, dm
                    )
                  else IO.unit
              yield ())
        }
    yield ()

  /** 逐会话 keyset 补拉 = **拉取即派发**（§3.2①）+ 结构化对账行（§3.7 / W1）。
    *
    * ## 修的是什么（P0）
    * 修前本方法是**纯 cursor 维护**：拉到消息只推进锚点，**一条都不派发**——正文明明
    * 可以 REST 拉回，前端却永不渲染（「113 过 / 114 没过」这类现象的直接成因）；而锚点
    * 照推 ⇒ 那条消息在本机**永久不可达**（既没进 UI，下次 `after=` 也已越过它）。
    * 修后：**先派发、后推进**，锚点只认派发成功的 id（见 `dispatchPull`）。
    *
    * ## 冷锚（`after == 0`）：**不用「从 0 拉一页历史」建锚**
    * 冷锚（进程刚起 / 该会话从未拉过）时 keyset 从**最旧**一页开始（服务端 ASC keyset），
    * 那是**历史 backfill** 而不是「补一条被吞的推送」。故冷锚一律**不派发**，并且：
    *  - **有事件/响应提示**（`eventId` 带 `messageId`、发送响应带 `messageId`）⇒ 以
    *    `afterHint - 1` 为起点取**恰好那一窗**：既拿到被吞的那条，又不会把一页最旧的
    *    消息当成新消息塞进已开着的最新窗口；
    *  - **无提示**（`refresh_all` 常态）⇒ **不取数**（`pulled=0` 的零值对账行）。
    *    历史渲染归 UI 自己的取数路径（`openConversation`/`syncConversation`），本腿
    *    不是它的替代品。
    * 为什么必须这样：修前**锚点滞后被前端 `markRead` 顺手掩盖**（已读与拉取共用
    * `lastReadMessageId` 一字段 ⇒ 前端一开窗就把锚点抬到尾部）。§3.5 拆字段后这层
    * 掩盖消失 ⇒ 若仍用「从 0 拉一页」建锚，锚点会永远停在最旧一页的最大 id 上。
    *
    * ## 帧形状（禁新造第二条通道）
    * 复用 `FriendEvent.frontendFrame` 与构造期注入的 `onFriendEvent` 广播缝（与真 push
    * **同一条**），帧**逐字段同形**，仅两个**加性**字段：
    *  · `senderId` —— 真 push 本就带它（前端靠它判「对方所发」⇒ 计未读 / 判左右气泡）；
    *    补拉帧缺它的话，前端只能退回「不是好友 ⇒ 是我发的」的**负向推断**判气泡方向
    *    （P5 缺陷，本批已在前端修为正向判据，见 `messages.js resolveOut`）。
    *  · `backfill: true` —— **补拉回放**标记（结构化，非文本后缀）。语义 = 「这条正文
    *    来自 REST 回补，不是本拍新到的推送」⇒ 前端只做渲染/预览，**不涨未读、不自动
    *    转发、不认领乐观项**。未读的权威来源是事件增量 + 服务端 `unreadCount` 基线；
    *    补拉是**渲染修复**，不是计数依据——没有这个标记，冷启动后的历史回补会把角标
    *    刷成虚高（正是「self 不计未读」口径被冲垮的形态）。
    *
    * @param trigger   触发源（[[FriendPullTrigger]] 字面量；进结构化行 = 判据①的显式字段）
    * @param eventId   仅事件触发的补拉携带（据此与 `processed` 行机械配对）
    * @param oursHint  调用方已知的极性（`message_new_self` / `send:*` ⇒ `Some(true)`），
    *                  只用于选**事件名**（与真 push 同判据、前端落同一分支）
    * @param afterHint 冷锚时的取数起点提示（事件/响应自带的 `messageId`）
    */
  def pullConversation(
      conversationId: String,
      trigger: String = FriendPullTrigger.RefreshAll,
      eventId: Option[String] = None,
      oursHint: Option[Boolean] = None,
      afterHint: Option[Long] = None
  ): IO[Unit] =
    guard.pullAnchor(conversationId).flatMap { anchor =>
      if anchor > 0L then dispatchPull(conversationId, trigger, eventId, oursHint, anchor)
      else
        afterHint match
          case Some(hint) =>
            dispatchPull(conversationId, trigger, eventId, oursHint, math.max(0L, hint - 1L))
          case None =>
            pullLine(conversationId, trigger, eventId, 0L, 0L, 0, 0, 0, Nil, 0L, 0L)
    }

  // ===== §3.6 对账拍（批 C）=====

  /** **一个会话的差态核对与补齐**（§3.6：服务端 keyset 水位 vs 本地 `dispatchedMax`）。
    *
    * 为什么需要它（修的是什么）：批 A 把「拉取即派发」接到了**事件**上——事件到了
    * 就一定补拉。但事件本身**可能根本不到**（广播失败 / 浏览器正处 WS 重连窗口 /
    * 隧道在册但半死 / 上游丢帧）：这条路径上**没有任何一方知道发生过丢帧**，于是
    * 「服务端有、UI 没有、且没有任何回补」没有上界。本腿提供那个上界：在一个
    * **周期拍**上用 keyset 探针把「服务端水位」与「本地已派发水位」对一次账。
    *
    * 判据（可机械判）：
    *  · 探针空页 ⇒ **零日志零动作**（无差是常态；对账拍不得变成噪声源）；
    *  · 探针非空 ⇒ 报一行 `friend_pull_reconcile` WARN（**含 `conversationId=` 与
    *    `diff=`**）后**复用唯一派发腿** `pullConversation` 补齐 —— 🔴 禁在本方法里
    *    另写一份派发实现（两份实现 = 两套语义，正是本仓缺陷族形态）。
    *
    * 代价（如实申报）：差态下**多一次取数**（探针页 + 派发腿各取一次同一窗）。这是
    * 「复用唯一派发腿」的代价，且只发生在**确实有差**时才付；无差拍零额外请求。
    * 反过来若为省这一次而把派发逻辑内联，本腿就会成为第二份派发实现 —— 不换。
    *
    * `diff = serverMax - after` 的语义边界（🔴 不夸大）：`serverMax` 是**首探页**的
    * 最大 id。差 > 单页（50）时它是**下界**，真实服务端水位在后续页里由派发腿继续
    * 推进（`pullAnchor` 单调前进 ⇒ 多拍收敛）。⇒ 本字段是**「有差且差至少这么大」**
    * 的读数，不是「服务端水位」的精确值。
    *
    * 非事件触发（`trigger=reconcile`、`eventId=<none>`）⇒ 判据①的「事件触发 pull ⟺
    * processed」配对不变式**不受影响**（`isEventTriggered` 未改）。
    */
  private def reconcileConversation(conversationId: String, dispatchedMax: Long): IO[Unit] =
    withClient(_.listMessages(conversationId, dispatchedMax, PullPageLimit)).flatMap {
      case Left(err) =>
        // 探针失败必须可见（否则「对不上账」与「没对上账」同形）——但**不**自动重试
        // （禁自旋）；下一拍自然重来。
        emit(
          "warn",
          s"friend_pull_reconcile conversationId=$conversationId trigger=${FriendPullTrigger.Reconcile} " +
            s"after=$dispatchedMax diff=<none> gate=probe_failed reason=list_failed err=$err " +
            s"pullAnchor=$dispatchedMax dispatchedMax=$dispatchedMax"
        )
      case Right(msgs) if msgs.isEmpty =>
        // 无差：**零日志**。对账拍跑在 45s 周期上，若空拍也报行，日志会被常态淹没
        // （「噪声化 = 真信号被淹 = 另一种静默」——取证稿 §3.3 的降噪口径）。
        IO.unit
      case Right(msgs) =>
        val serverMax = msgs.map(_.id).max
        emit(
          "warn",
          s"friend_pull_reconcile conversationId=$conversationId trigger=${FriendPullTrigger.Reconcile} " +
            s"after=$dispatchedMax serverMax=$serverMax diff=${serverMax - dispatchedMax} " +
            s"probed=${msgs.size} gate=server_ahead reason=watermark_gap " +
            s"pullAnchor=$dispatchedMax dispatchedMax=$dispatchedMax"
        ) *>
          // 补齐：复用**唯一**派发腿（拉取即派发 + 派发后才推进 + 落对账行）。
          pullConversation(conversationId, FriendPullTrigger.Reconcile)
    }

  /** **对账拍**（§3.6，批 C）：逐个「已有派发水位」的会话核对 + 补齐。
    *
    * 逐会话**串行**（`traverse_`）：与 `refreshAll` 同形；并发多会话会把一次周期拍的
    * 上游请求数乘上会话数，而本腿是**兜底**（差态才动）⇒ 宁可慢一拍也不打突发。
    * 单会话异常不中断整轮（逐会话 `handleErrorWith` ⇒ 一个会话的上游故障不会让其余
    * 会话这一拍失去对账）。
    *
    * 触发面（批 C 接线）：`NeblinkService.syncLoop` 的**既有 45s 拍**（`runMessageReconcile`）
    * —— 🔴 禁新造第三套定时器（取证稿 §3.6 ①）。
    */
  def reconcileConversations(): IO[Unit] =
    guard.dispatchedAnchors.flatMap { anchors =>
      anchors.traverse_ { case (convId, dm) =>
        // 逐会话捕获：一个会话的上游故障不得让其余会话这一拍失去对账。
        // 走 `emit`（不是裸 `logger.warn`）——留痕行必须同时进 trace 环，否则行内容
        // 不可断言（批 A 已定型的口径：同一行既进日志、又进环，两份文本不可能漂移）。
        reconcileConversation(convId, dm).handleErrorWith(e =>
          emit(
            "warn",
            s"friend_pull_reconcile conversationId=$convId trigger=${FriendPullTrigger.Reconcile} " +
              s"after=$dm diff=<none> gate=reconcile_failed reason=unexpected_error err=${e.getMessage} " +
              s"pullAnchor=$dm dispatchedMax=$dm"
          )
        )
      }
    }

  /** §3.7 对账计数的**暴露面**（批 A 已备同源计数点，暴露归批 C；`/api/neblink/status`
    * 的 `friendPull` 字段即读本方法）。
    *
    * 形态 = ① 逐会话累计（`pulled`/`dispatched`/`skipped`）+ ② 进程总计 + ③ **判据式
    * 自述**（`identity`）+ ④ 近期留痕环（有界 64，判据①②④的行内读数面）。
    * 计数与日志行**同源**（`recordPull` 在写行之前由 `pullLine` 单点调用）⇒ 两个读数
    * 不可能各说各话。纯本地态，不入 wire（服务端零改动）。 */
  def pullCountersJson: IO[Json] =
    guard.pullStats.flatMap { stats =>
      guard.recentPullLines.map { lines =>
        val total = stats.values.foldLeft(FriendPullStats(0L, 0L, 0L))((a, b) =>
          FriendPullStats(a.pulled + b.pulled, a.dispatched + b.dispatched, a.skipped + b.skipped)
        )
        def statJson(s: FriendPullStats): Json =
          Json.obj("pulled" -> s.pulled.asJson, "dispatched" -> s.dispatched.asJson, "skipped" -> s.skipped.asJson)
        Json.obj(
          "conversations" -> Json.fromFields(stats.toList.sortBy(_._1).map { case (k, v) => k -> statJson(v) }),
          "totals" -> statJson(total),
          // 判据式自述：消费方（含人）不必自己推恒等式；读数与 `pullLine` 同源。
          "identity" -> "pulled == dispatched + skipped".asJson,
          "recentLines" -> Json.fromValues(lines.map(Json.fromString)),
          "recentLinesMax" -> 64.asJson
        )
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
  def sendAsAgent(friendUserId: String, body: String, attachments: List[os.Path] = Nil): IO[Either[String, String]] =
    if body.length > 4000 then IO.pure(Left(s"Message too long (${body.length} chars, max 4000)"))
    else
      config.mode match
        case "off" => IO.pure(Left("User has disabled agent messaging"))
        case "ask" =>
          confirmOrSend(friendUserId, body, attachments)
        case _ => // auto（含未知值回退 auto）
          guard.trySend(friendUserId, System.currentTimeMillis()).flatMap {
            case Right(()) => doSend(friendUserId, body, attachments)
            case Left(reason) =>
              logger.info(s"auto rate limit exceeded ($reason) — downgrading to ask") *>
                confirmOrSend(friendUserId, body, attachments)
          }

  private def confirmOrSend(friendUserId: String, body: String, attachments: List[os.Path]): IO[Either[String, String]] =
    askConfirm match
      case None => IO.pure(Left("ask mode requires a confirmation callback (not wired)"))
      case Some(confirmFn) =>
        confirmFn(body)
          .flatMap {
            case true => doSend(friendUserId, body, attachments)
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

  private def doSend(friendUserId: String, body: String, attachments: List[os.Path] = Nil): IO[Either[String, String]] =
    def finish(json: Json): IO[Either[String, String]] =
      val convId = json.hcursor.get[String]("conversationId").toOption
      // K-3（段 B 2026-09-12）：**先自播（通知本机 UI），再补拉**。次序理由与 K-1
      // 同源——补拉是一次串行 REST 往返（本机实测 135.9–499.0 ms），不得挡在
      // 「通知 UI」之前；自播走 wsHub 广播，零上游往返。
      convId.traverse_(replaySelfSend(_, json, body)) *>
        convId.traverse_(id =>
          pullConversation(
            id,
            FriendPullTrigger.SendAsAgent,
            oursHint = Some(true),
            afterHint = json.hcursor.get[Long]("messageId").toOption
          )
        ).void *>
        IO.pure(Right("Message sent"))

    if attachments.isEmpty then
      // origin=agent：sendAsAgent 是唯一 agent 代发 choke point（#290 spec v1.1）——
      // wire 缺 origin 时服务器缺省落 "user"，agent 消息语义（徽章/审计/限速区分）失效。
      withClient(_.sendFriendMessage(friendUserId, body, origin = Some("agent"))).flatMap {
        case Left(err)   => IO.pure(Left(err))
        case Right(json) => finish(json)
      }
    else
      // ===== 附件腿（4b 腿 A-4）=====
      // 次序硬约束（每条都有理由，禁重排）：
      //   ① 闸（件数/大小）= 本地 fail-fast，零上游往返；
      //   ② **能力探测**（A-5，fail-closed）= 必须在任何上传之前 —— 否则会对一个
      //      不支持的服务器白传字节，再以「发送失败」收场；
      //   ③ 上传（E1 + E2×n）；
      //   ④ 发送（`body` 可为空 —— §B.4：有附件时服务端生成占位正文）；
      // 任一步失败都**显式回执**（禁静默降级为纯文本发送：那正是 §5.1-C 的静默不达）。
      withClient { cli =>
        val sizes: IO[Either[String, List[Long]]] =
          IO.blocking(attachments.map(p => p -> os.stat(p).size)).attempt.map {
            case Right(list) => Right(list.map(_._2))
            case Left(e)     => Left(s"cannot stat attachment(s): ${Option(e.getMessage).getOrElse(e.toString)}")
          }
        sizes.flatMap {
          case Left(err) => IO.pure(Left(err))
          case Right(sz) =>
            // 空件本地先拒：§B.1 E1 校验表第 4 条（`size <= 0` ⇒ 422）——在本地
            // fail-fast 省一次上游往返，且把「为什么」写在文案里。
            val gate: Either[String, Unit] =
              if sz.exists(_ <= 0L) then
                Left("Attachment gate rejected: empty file (0 bytes) — the server rejects size <= 0. Nothing was uploaded and no message was sent.")
              else nebflow.dropbox.AttachContract.checkMessage(sz).left.map(bad => s"Attachment gate rejected: ${bad.render}")
            gate match
              case Left(msg) => IO.pure(Left(msg))
              case Right(_) =>
                cli.probeAttachmentCapability(friendUserId).flatMap {
                  case AttachmentCapability.Supported(ev) =>
                    logger.info(s"attachment capability probe: supported ($ev)")
                    uploadAll(cli, friendUserId, attachments).flatMap {
                      case Left(err) => IO.pure(Left(err))
                      case Right(ids) =>
                        cli
                          .sendFriendMessage(friendUserId, body, origin = Some("agent"), attachmentIds = ids)
                          .flatMap {
                            case Left(err)   => IO.pure(Left(err))
                            case Right(json) => finish(json)
                          }
                    }
                  case other =>
                    // 不对齐 / 不可判 ⇒ **明确拒绝**（文案可区分，见 AttachmentCapability.refusal）
                    logger.warn(s"attachment capability probe: ${other.getClass.getSimpleName} — attachments NOT sent")
                    IO.pure(Left(AttachmentCapability.refusal(other)))
                }
        }
      }

  // ===== 群发送（gmsgsend 批，2026-09-15 · 补充卡 §6.1–§6.4 + §8 判红三面）=====
  //
  // 两条入口，与好友腿**逐条对称**（`sendAsAgent` / `sendAsUser`）：
  //  - [[sendGroupAsAgent]]：agent 代发（三档权限 + 双层限速 + 确认链）——
  //    🔴 全系统**唯一**能写 `origin="agent"` 的群写点；
  //  - [[sendGroupAsUser]]：UI 直发（**零档位**，与 `sendAsUser` 同语义）。
  //
  // 共同点（两条都经）：服务端**单一**校验序（`groups.rs` 冻结：
  // auth → 群存在且未解散 → 成员 → 长度 → 限速 → origin → payload），本层
  // **不复制**任何一条判定（禁双实现，先例 `groupProxy` 注释）；本层只做
  // 「档位/限速/确认」这一层（与好友腿同层）与**错误转写**。

  /** 群列表（`GET /api/groups`）——群目标解析的数据面。
    *
    * 语义边界（与 `NeblinkClient.listGroups` 逐条同源，此处不重复实现）：
    * 解散态/非成员**不在表内**（服务端按鉴权身份出表）⇒ 解析层结构上不可命中
    * 已解散或非成员的群；终态判定的权威仍是服务端错误码。
    *
    * 失败**不折叠成空表**：`Left` 原样上抛（`HTTP <code>: <body>` 或 `Not logged in`）
    * ——🔴 「读不到群」与「你没有群」是两态（同 F4 对好友列表的口径），把前者折成
    * 空表会让工具报「你没有群」，即把一次上游故障伪装成一个确证结论。 */
  def listGroups: IO[Either[String, List[GroupSummary]]] =
    withClient(_.listGroups)

  /** UI 直发的群消息（**用户身份**，无 agent 权限档位）——与 [[sendAsUser]] 同语义。
    *
    * 调用链：网关鉴权路由 `POST /api/groups/{groupId}/messages` → 本方法。
    * 🔴 请求体**只读 `body` 一个字段**（补充卡 §6.5 逐字；先例 = 好友路由
    * `RestApiRoutes.scala` 的好友发送分支）：UI 面**在协议上无法自报 origin**
    * ⇒ 本方法**不发** `origin` 键 ⇒ 服务端按契约缺省落 `"user"`
    * （§5.4 写权矩阵第一行、§8.1(a) 判据）。 */
  def sendGroupAsUser(groupId: String, body: String): IO[Either[String, (Int, String)]] =
    withClient(_.sendGroupMessage(groupId, body)).flatMap {
      case Left(err) => IO.pure(Left(err))
      case Right((code, resp)) if code == 200 || code == 201 =>
        // 与 sendAsUser 同形：本机 UI 的自播由服务端 `message_new_self` 帧承担
        // （发起设备被服务端显式排除推送）⇒ 这里只补拉一次，保证本机 UI 见到自己发的行。
        val json   = decode[Json](resp).getOrElse(Json.Null)
        val convId = json.hcursor.get[String]("conversationId").toOption
        convId
          .traverse_(id =>
            pullConversation(
              id,
              FriendPullTrigger.SendAsUser,
              oursHint = Some(true),
              afterHint = json.hcursor.get[Long]("messageId").toOption
            )
          )
          .void
          .handleErrorWith(_ => IO.unit)
          .as(Right((code, resp)))
      case Right((code, resp)) => IO.pure(Right((code, resp))) // 错误码原样上抛（网关逐字透传）
    }

  /** **agent 代发群消息**（`SendMessage(to="group:<…>")` 的唯一出口）。
    *
    * 与 [[sendAsAgent]] **逐条对称**（档位/限速/确认链同层，同一条语义）：
    *  - `off` ⇒ 直拒；`ask` ⇒ 确认卡；`auto` ⇒ 限速后直发，**超限自动降级 ask**；
    *  - 限速桶：**沿用** `FriendMessagingGuard` 的 per-target 20/h + 全局 60/h
    *    （补充卡 §5.5 推荐案）。桶键 = **群会话 id**（T⑧：值域加性扩为 friend|group）。
    *    群 id = 服务端 `grp-` + UUIDv4，与 user id 命名空间不相交（T①）⇒ 同命名空间
    *    内的键不会互相串桶；「群发挤占单聊配额」是**有意**的（同一调用主体、同一
    *    凭据、一次请求 = 一行消息；拆桶需实测挤占，见 §5.5 反案）。
    *  - 确认卡的**目标名 = 群名**由调用侧（`FriendMessageTool` 的 `SendConfirm.locally`
    *    靶）给定——本层只见 `String => IO[Boolean]`，与好友腿逐字同构（零新配置面）。
    *
    * 🔴 `origin = Some("agent")` 是本层的**唯一**职责差异点（群版 choke point）——
    * 见 [[doSendGroup]]。
    *
    * 一期**不带附件**（补充卡 §6.1 一期文本；工具侧对「群 + 附件」显式拒绝，
    * 不走本方法）⇒ 本方法无附件分支，也不做能力探测。
    */
  def sendGroupAsAgent(groupId: String, body: String): IO[Either[String, String]] =
    if body.length > 4000 then IO.pure(Left(s"Message too long (${body.length} chars, max 4000)"))
    else
      config.mode match
        case "off" => IO.pure(Left("User has disabled agent messaging"))
        case "ask" =>
          confirmOrSendGroup(groupId, body)
        case _ => // auto（含未知值回退 auto）
          guard.trySend(groupId, System.currentTimeMillis()).flatMap {
            case Right(()) => doSendGroup(groupId, body)
            case Left(reason) =>
              logger.info(s"auto rate limit exceeded ($reason) — downgrading to ask") *>
                confirmOrSendGroup(groupId, body)
          }

  /** 确认链（**群支镜像** of [[confirmOrSend]]）：三条 fail-closed 语义逐条同源
    * （无确认实现 ⇒ 显式失败；拒绝 ⇒ 不投递；确认链抛错 ⇒ **既不投递也不伪装成
    * 用户拒绝**），只有「打到哪」的日志标签不同（群会话 id）。 */
  private def confirmOrSendGroup(groupId: String, body: String): IO[Either[String, String]] =
    askConfirm match
      case None => IO.pure(Left("ask mode requires a confirmation callback (not wired)"))
      case Some(confirmFn) =>
        confirmFn(body)
          .flatMap {
            case true => doSendGroup(groupId, body)
            case false =>
              logger.info(s"SendMessage declined by the user — nothing sent to group $groupId") *>
                IO.pure(Left("User declined the message"))
          }
          .handleErrorWith { e =>
            val why = Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)
            logger.warn(s"SendMessage confirmation failed (message NOT sent, group=$groupId): $why") *>
              IO.pure(Left(s"Confirmation failed — the message was NOT sent: $why"))
          }

  /** agent 群发的**唯一 choke point**（`origin = Some("agent")`）。
    *
    * 🔴 契约边界（本方法即 §8 判红三面在**客户端侧**的全部义务）：
    *  ① **标识伪造面**：`origin="agent"` 由本层单点写入；服务端 `sender_id` 恒 =
    *     鉴权身份 ⇒ origin **只标注「这条消息是否 agent 代发」，永远挂在发送者自己的
    *     sender_id 上**（冒充他人不可达）。UI 面（[[sendGroupAsUser]]）**不发**该键。
    *  ② **权限面**：群存在/未解散/成员三闸**全在服务端**（本层零复制）；本层只把
    *     服务端的 404/403 语义码**逐条转写成可判读回执**（🔴 禁静默不达）。
    *  ③ **契约兼容面**：只发 `body` + `origin` 两个键（`clientMsgId` 由调用侧可选
    *     给出），不新增必填键、不改任何既有键 → 老服务端/老客户端形态不变。
    *
    * 回执与错误转写（§6.4 同构表）：2xx ⇒ `Right("Message sent")`（与好友腿同字面）；
    * 非 2xx ⇒ 按**服务端语义码**给显式原因（`group_not_found` / `group_disbanded` /
    * `not_member` / `invalid_length` / `invalid_origin` / `rate_limited` / 其余原样）。
    */
  private def doSendGroup(groupId: String, body: String): IO[Either[String, String]] =
    def finish(json: Json): IO[Either[String, String]] =
      val convId = json.hcursor.get[String]("conversationId").toOption
      // 与好友腿 K-3 同序（先自播、再补拉）：群会话与单聊**同属会话域**（案1
      // 会话域扩容）⇒ 复用同一对方法，零新机制。补拉触发字面量复用 `send:agent`
      // （🟡 本批**不新增** trigger 字面量：它属于判据①「事件触发 pull ⟺ processed」
      // 配对不变式的枚举面，加一个字面量就要同步那条不变式的登记——群发与单聊代发
      // 在「谁触发、为什么拉」上同一语义，复用是最小面）。
      convId.traverse_(replaySelfSend(_, json, body)) *>
        convId
          .traverse_(id =>
            pullConversation(
              id,
              FriendPullTrigger.SendAsAgent,
              oursHint = Some(true),
              afterHint = json.hcursor.get[Long]("messageId").toOption
            )
          )
          .void *>
        IO.pure(Right("Message sent"))

    withClient(_.sendGroupMessage(groupId, body, origin = Some("agent"))).flatMap {
      case Left(err) => IO.pure(Left(err))
      case Right((code, resp)) =>
        if code == 200 || code == 201 then decode[Json](resp) match
          case Right(json) => finish(json)
          case Left(_)     => IO.pure(Right("Message sent"))
        else IO.pure(Left(groupSendFailure(code, resp)))
    }

  /** 群发失败的**可判读回执**（§6.4 词表）：服务端语义码逐条给出显式原因，
    * 🔴 禁静默、禁把语义码压成一个笼统的「发送失败」（那会让模型把「我不是成员」
    * 与「群解散了」当成同一件事，从而给出错误的下一步动作）。未知码/非 JSON 体
    * **原样带出**（不猜、不吞）。 */
  private def groupSendFailure(code: Int, resp: String): String =
    val code0 = decode[Json](resp).toOption.flatMap(_.hcursor.get[String]("error").toOption)
    val reason = code0 match
      case Some("group_not_found") => "the group does not exist (or is not visible to your account)"
      case Some("group_disbanded") => "the group has been disbanded"
      case Some("not_member")      => "you are not a member of this group"
      case Some("invalid_length")  => "the server rejected the message length (empty body, or > 4000 characters)"
      case Some("invalid_origin")  => "the server rejected the message origin label (contract violation)"
      case Some("rate_limited")    => "the server rate limit was hit (30 messages / 60 s)"
      case Some(other)             => s"the server rejected the send ($other)"
      case None                    => s"the server rejected the send (HTTP $code): ${resp.take(200)}"
    s"Group message NOT sent — $reason."

  /** E1 + E2×n 顺序上传（§B.1/B.6），返回 attachmentId 列表（顺序 = 入参顺序 =
    * `SendMessageBody.attachments` 的展示顺序）。
    *
    * 🔴 **分块驱动不在本方法里**（attachcl 批，2026-09-16）：E1+E2×n 链已抽到
    * [[AttachUpload]]，本方法只做「逐件顺序 + 盘上件校验」，网页腿走同一个
    * [[AttachUpload.pushFile]] —— 这就是本批要求的**同源性**（禁第二套分块逻辑）。
    *
    * 每块**单次重试**：E2 的 `offset < receivedBytes` 语义 = 截断重写 ⇒ 同 offset
    * 重发幂等（§B.1 E2）——该语义连同重试都在 [[AttachUpload.pushChunks]] 内，两条腿
    * 共用。再失败就**显式失败**（不带半成品发送）：块级失败不会留下「已发送」的假象，
    * 消息在全部上传成功后才发。 */
  private def uploadAll(cli: NeblinkClient, friendUserId: String, files: List[os.Path]): IO[Either[String, List[String]]] =
    // 逐件**顺序**上传（件数 ≤9，总字节 ≤9 GiB —— 单件上限 1024 MB = 1 GiB × 9，2026-09-14 r2 口径）。
    // 每件内部按 4 MiB 分块 ⇒ 单块在内存里的峰值 = 4 MiB（`readRange` 读一块，
    // 裸字节 `BodyPublishers.ofByteArray` 直发、**不经 base64**），与文件大小无关。
    def loopFiles(rest: List[os.Path], acc: List[String]): IO[Either[String, List[String]]] =
      rest match
        case Nil => IO.pure(Right(acc))
        case p :: tail =>
          if !os.exists(p) || os.isDir(p) then IO.pure(Left(s"Attachment is not a file: $p"))
          else
            AttachUpload.uploadFile(cli, friendUserId, p, p.last).flatMap {
              case Left(fail)  => IO.pure(Left(fail.render))
              case Right(up)   => loopFiles(tail, acc :+ up.attachmentId)
            }

    loopFiles(files, Nil)

  // ===== 网页腿上传（attachcl 批，2026-09-16）=====

  /** 网页腿的整件流式上传：浏览器**整件一次请求** → 网关把请求体流式落临时件 →
  * **复用桌面腿同一分块驱动**（[[AttachUpload.pushFile]]）。
  *
   * 与 [[sendAsAgent]] 附件链的**唯一**差别 = 字节源（HTTP 请求体 vs 本机路径），
   * 校验序/上传链/摘要口径/块大小逐字同源。能力探测（[[AttachmentCapability]]）与
   * 桌面腿同点执行 —— 探测结论按**可区分文案**拒绝（§G.3：禁止在未探测的情况下
   * 携带 `attachments` 发送）。
   *
   * `conversationId` = E1 路径段（好友 = 好友 userId；群 = 群会话 id `grp-…`；
   * 服务端 E1 对这段的判据是 `is_member_gated` / `friendship_accepted`，两腿共用）。
   *
   * 临时件在**所有出口**（成功/失败/取消）删除；取消位由 [[AttachUploadRegistry]]
   * 注入（见 [[AttachUpload.Hooks]]）。
   *
   * 🔴 **`uploadId` 入口先是形态闸**（attachid 批，2026-09-16）：id 会被当作**单个路径段**
   * 拼进临时件路径（下一条方法的第一行），而 os-lib 的 `Path / String` 对含 `/` 的段**抛异常**
   * —— 该抛点在本方法续体的 `handleErrorWith` **之外** ⇒ http4s `500` + **空体**（独立复核位
   * A1 实测的重放读数：含 `/` 的 7 个形态一律 500 / 体长 0）。闸在**任何** `os.` 调用之前，
   * 判定唯一实现在 [[AttachUploadId]]（见该类头）。
   */
  def uploadStream(
    conversationId: String,
    displayName: String,
    uploadId: String,
    body: fs2.Stream[IO, Byte],
    hooks: AttachUpload.Hooks = AttachUpload.Hooks.none
  ): IO[Either[(String, String), AttachUpload.Uploaded]] =
    AttachUploadId.validate(uploadId) match
      case Left(reason) => IO.pure(Left((AttachUploadId.ErrorCode, reason)))
      case Right(id)    => uploadStreamChecked(conversationId, displayName, id, body, hooks)

  /** [[uploadStream]] 的**已过形态闸续体**。
    *
    * `uploadId` 入参在此已是 [[AttachUploadId.validate]] 放行的值（`[A-Za-z0-9._-]` ∧
    * ≤ `MaxLength` 字节）⇒ 下面的**单段**拼接 `s"$uploadId.part"` **不可能**再抛
    * `PathError.InvalidSegment`（`/` / 空 / `.` / `..` / NUL / 控制字符 / 非 ASCII / 超长
    * 全部已在闸上拒掉）—— 本批要的形态是「**让崩点不可达**」，**不是**「先崩再 catch」。
    * 因此下面的 `tempPath` 构造不再需要（也**不得**改写成）try/catch 兜异常后走原路径。
    */
  private def uploadStreamChecked(
    conversationId: String,
    displayName: String,
    uploadId: String,
    body: fs2.Stream[IO, Byte],
    hooks: AttachUpload.Hooks
  ): IO[Either[(String, String), AttachUpload.Uploaded]] =
    currentClient.flatMap {
      case None =>
        IO.pure(Left(("not_logged_in", "Not logged in to the NebLink server — nothing was uploaded.")))
      case Some(cli) =>
        val tempPath = nebflow.core.PathUtil.dataRoot / "attach-uploads" / s"$uploadId.part"
        val bounded  = nebflow.dropbox.AttachContract.MaxFileBytes
        (for
          _ <- IO.blocking(os.makeDir.all(tempPath / os.up))
          staged <- nebflow.dropbox.DropboxUtil.streamToFileWithHashBounded(body, tempPath, bounded)
          out <- staged match
            // 流式落盘即带上限兜底：请求体超过 1 GiB 在**读的过程中**就被拦下（不是读完再判）。
            case Left(err) => IO.pure(Left(("attach_too_large", err.render)))
            case Right(whole) =>
              IO.blocking(os.stat(tempPath).size).flatMap { size =>
                if size <= 0L then
                  // 空件本地先拒（同桌面腿 doSend 的闸，文案同源自服务端 E1 的 size <= 0 ⇒ 422）。
                  IO.pure(
                    Left(
                      (
                        "empty_file",
                        "Attachment gate rejected: empty file (0 bytes) — nothing was uploaded and no message was sent."
                      )
                    )
                  )
                else
                  cli.probeAttachmentCapability(conversationId).flatMap {
                    case AttachmentCapability.Supported(ev) =>
                      logger.info(s"attachment capability probe (web leg): supported ($ev)")
                      AttachUpload
                        .pushFile(cli, conversationId, displayName, size, whole, tempPath, hooks)
                        .flatMap {
                          case Right(up)  => IO.pure(Right(up))
                          case Left(fail) => IO.pure(Left((AttachUpload.errorCode(fail), fail.render)))
                        }
                    case other =>
                      logger.warn(
                        s"attachment capability probe (web leg): ${other.getClass.getSimpleName} — attachments NOT sent"
                      )
                      IO.pure(Left(("attachment_unsupported", AttachmentCapability.refusal(other))))
                  }
              }
          _ <- IO.blocking(if os.exists(tempPath) then os.remove(tempPath) else ())
        yield out).handleErrorWith { e =>
          IO.blocking(if os.exists(tempPath) then os.remove(tempPath) else ()) *>
            IO.pure(Left(("upload_failed", s"attachment upload failed: ${Option(e.getMessage).getOrElse(e.toString)}")))
        }
    }

  // ===== 群路由代理腿（gwroutes 批，2026-09-15）=====

  /** 群域**唯一**转发口：把网关鉴权路由收到的群请求转发到 neblink-server，
    * **逐字回传**上游 `(status, body)`。
    *
    * 为什么落在本服务（而不是网关直连 [[NeblinkClient]]）：`currentClient` 是
    * enrollment hot-swap 的**唯一替换点**（见类头 F1 注记）——网关若持有构造期
    * client 快照，UI 重新登录/换账号后这里会持续 403 到进程重启（正是 F1 修掉
    * 的病）。走 [[withClient]] ⇒ 与全部既有好友面共用同一条权威 live-client 缝
    * 与同一套会话自愈语义（`Not logged in` 三态口径也逐字一致）。
    *
    * `path` = **相对段**（含 `/api` 前缀）；`method` = 上游方法字面量。本层**不做**
    * 任何字段映射 / 信封拆装 / 字段裁剪 —— 契约真源是服务端 `src/groups.rs`，
    * 任何 reshape 都会给冻结契约造出第二个真相源（客户端消费形态对表见 impl 报告）。
    */
  def groupProxy(method: String, path: String, body: String): IO[Either[String, (Int, String)]] =
    withClient(_.proxyWithStatus(method, path, body))

  // ===== MVP-2 设备会话域统一（2026-09-15）：两个新面的服务层 =====
  //
  // 与 [[groupProxy]] **同一条缝、同一条纪律**（`withClient` = enrollment hot-swap
  // 的唯一替换点；非 2xx **保留上游状态码**，不折叠成 502）：设备会话的两个新面
  // 各有**可判读的终态**（回执面 `403 device_identity_required`；发送面
  // `403 not_my_device` / `422 invalid_origin` / `422 invalid_length`，契约 §8.6/§8.7），
  // 折叠之后客户端就只能解析字符串分态了。

  /** 设备会话回执读面（`GET /api/conversations/{id}/receipts`）。
    * 🔴 兼容设备会话**与**legacy 直聊会话：服务端按 `conversations.kind` 自行分派到
    * `device_message_receipts` / `message_receipts`，**响应形状同源**（契约 §8.7）；
    * 本层不判 kind、不复制第二套分派逻辑（禁双实现）。 */
  def conversationReceipts(conversationId: String): IO[Either[String, (Int, String)]] =
    withClient(_.conversationReceipts(conversationId))

  // 注：设备会话**发送**面（`POST /api/devices/{device_id}/messages`，契约 §8.6）**不**在
  // 本层新增方法 —— 它是「按原文转发」面（请求体含 `attachments` 等加性键，解析后重编码
  // 会丢键），与群发面**共用** [[groupProxy]] 这一条通用转发缝（见网关路由的注释）。
  // 🔴 设备面的本地台账纪律（写在这里以免下一批误补）：**不得**在转发成功后就地写本机
  // legacy `read_cursors` —— 设备维度未读/游标是**服务端**权威（`device_read_cursors`，
  // 契约 §8.5），而本机 cursor 是 **friend 域**水位；服务端逐字告警「设备会话消息 id 会
  // 推进 friend 域水位，进而抑制 S1 好友唤醒」（设计卡 §5.5 第 4 行）⇒ 顺手写本地游标
  // 就是拿 friend 域水位吞 device 域消息（净回归）。

  /** A-5 探测透出（工具/诊断面用；三态语义见 [[AttachmentCapability]]）。
    * 未登录 ⇒ `Undetermined`（**不是** `Unsupported`：两者对用户是不同结论、不同文案）。 */
  def probeAttachmentCapability(friendUserId: String): IO[AttachmentCapability] =
    currentClient.flatMap {
      case None      => IO.pure(AttachmentCapability.Undetermined("Not logged in"))
      case Some(cli) => cli.probeAttachmentCapability(friendUserId)
    }

  /** E3 下载透出（腿 A-3）：**唯一**取字节入口 —— 只给网关的鉴权代理路由用。 */
  def downloadAttachment(attachmentId: String): IO[Either[String, AttachmentFetch]] =
    withClient(_.downloadAttachment(attachmentId))

  /** E4 接收完毕回执（补件批 4b1 · §B.1 / §F.1b）——**唯一**回执调用点。
    *
    * 调用链：前端（`friendsApi.js#ackAttachmentReceived`，只**上报证据**）→ 网关鉴权路由
    * `POST /api/friends/attachments/{id}/received` → 本方法。判定**全部**在
    * [[AttachmentAck.decide]]（fail-closed：缺证据一律不发 ⇒ 服务端 blob 不动，TTL 兜底）。
    *
    * 三条硬语义：
    *   ① **fail-closed**：`Skip` ⇒ **零 E4**（不删服务端 blob）；
    *   ② **失败静默容忍**：本方法**永不失败** —— `IO` 不抛、不改任何调用方返回码、
    *      不弹错、不写用户可见状态；网络/超时/4xx/5xx 只记日志；
    *   ③ **重试从简**：**零自动重试**（一次事件至多一次 E4；禁自旋、禁后台队列堆积）。
    *      丢 ack 的兜底 = 服务端 24 h 强删（§F.1b 规则 4：用户侧零损失，盘不泄漏）。
    *
    * `404`/`410` ⇒ **信息级**（无需回执：未就绪件 / 已按瞬态口径删除）；`422`/其余 ⇒ WARN。
    * 入参 `attachmentId` 仅用于日志与请求路径，**不**参与判定（判定只看证据）。 */
  def ackAttachmentReceived(attachmentId: String, evidence: AttachmentAck.Evidence): IO[AttachmentAck.Result] =
    AttachmentAck.decide(evidence) match
      case AttachmentAck.Decision.Skip(reason) =>
        logger
          .info(s"attachment ack skipped ($reason) for $attachmentId — server blob untouched (24h TTL is the safety net)")
          .as(AttachmentAck.Result.Skipped(reason))
      case AttachmentAck.Decision.Fire(digest) =>
        withClient(_.confirmAttachmentReceived(attachmentId, digest))
          .map {
            case Right((200, _)) => AttachmentAck.Result.Acknowledged
            case Right((code, _)) if code == 404 || code == 410 =>
              // 信息级：无需回执（§F.1b ⑥ 未就绪 / 已删除）；**不是**失败 ⇒ 不重试。
              logger.info(s"attachment ack for $attachmentId needs no receipt (HTTP $code) — nothing to delete")
              AttachmentAck.Result.Skipped(s"no-ack-needed-$code")
            case Right((code, body)) =>
              logger.warn(s"attachment ack for $attachmentId failed: HTTP $code ${truncate(body)}")
              AttachmentAck.Result.Failed(s"HTTP $code")
            case Left(err) =>
              logger.warn(s"attachment ack for $attachmentId failed: ${truncate(err)}")
              AttachmentAck.Result.Failed(err)
          }
          // 兜底：**任何**上抛（含日志/编解码侧意外）都收敛成失败结局 ⇒ 用户面零影响。
          .handleErrorWith(e => logger.warn(s"attachment ack for $attachmentId raised: ${truncate(msg(e))}").as(AttachmentAck.Result.Failed("raised")))

  /** 日志截断（服务端 body / 异常文案）：单行、有上限，防日志面被大 body 灌满。 */
  private def truncate(s: String, max: Int = 200): String =
    val one = Option(s).getOrElse("").replace('\n', ' ')
    if one.length <= max then one else one.take(max) + "…"

  private def msg(e: Throwable): String =
    Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)

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
        // 4b 腿 A（§B.2 M5）：附件元数据随响应体回本机 —— 真 push 的 `message_new_self`
        // 被服务端显式排除发起设备，本机唯一的元数据来源就是这里。缺席 ⇒ **省键**
        // （无附件消息的帧与今天逐字节同形；前端按「键缺席 = 无附件」读）。
        val envelope = Json.obj(
          "type" -> MessageNewSelf.asJson,
          "payload" -> Json.fromFields(
            List(
              Some("messageId" -> Json.fromLong(messageId)),
              Some("conversationId" -> conversationId.asJson),
              Some("kind" -> "text".asJson),
              Some("body" -> body.asJson),
              Some("origin" -> "agent".asJson),
              Some("createdAt" -> Json.fromLong(createdAt)),
              h.downField("attachments").focus.filterNot(_.isNull).map("attachments" -> _)
            ).flatten
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
  /** UI 直发的**双面**入口（attachcl 批加性扩面）：`attachmentIds` 为**已上传**的
    * 附件 id 列表（顺序 = 展示顺序），缺省空 ⇒ 请求体与今天**逐字节同形**（不发
    * `attachments` 键）。正文可为空**仅当**附件非空（服务端 §B.4 生成占位正文）。
    * 🔴 本方法**不做**任何上传：字节面在 [[uploadStream]]（同一分块驱动），本方法只
    * 把已确认的 id 随消息送出 —— 上传没成功就绝不会有 id 可传。
    *
    * P2-b 加性扩面（`clientMsgId`）：**幂等键**，由客户端**发送动作**侧生成并透传
    * （同动作重试复用同键，不同动作新键）。缺省 `None` ⇒ 请求体与今天逐字节同形
    * （`NeblinkClient` 只在 `Some` 时发该键）。🔴 幂等判定**全在服务端**（§8.6：同键
    * 重复仍是 201、`existing:true` 仅表示回放原行）—— 本层与网关层都**不去重**、
    * **不改**状态码、**不做**本地去重缓存（那会造出第二套真相）。
    */
  def sendAsUser(
    friendUserId: String,
    body: String,
    attachmentIds: List[String] = Nil,
    clientMsgId: Option[String] = None
  ): IO[Either[String, Json]] =
    withClient(
      _.sendFriendMessage(friendUserId, body, attachmentIds = attachmentIds, clientMsgId = clientMsgId)
    ).flatMap {
      case Right(json) =>
        json.hcursor.get[String]("conversationId").toOption match
          case Some(convId) =>
            pullConversation(
              convId,
              FriendPullTrigger.SendAsUser,
              oursHint = Some(true),
              afterHint = json.hcursor.get[Long]("messageId").toOption
            ).void.handleErrorWith(_ => IO.unit).as(Right(json))
          case None => IO.pure(Right(json))
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

  /** **拉取**水位（keyset `after=` 的唯一取值来源；§3.5 拆字段前此处读的是已读字段）。
    *
    * 方法名保留 `localMaxId`（既有调用点/单测零改动）：语义仍是「本会话本地最大的
    * 已拉取消息 id」，只是不再与**已读**水位共用一格。 */
  def localMaxId(conversationId: String): IO[Long] =
    state.get.map(_.cursors.get(conversationId).map(_.pullAnchor).getOrElse(0L))

  /** **补拉（pull/replay）锚点**的读取面 —— 与 `dispatchedMaxOf` / `readAnchor`
    * 同形同域：**同一条 `cursors` 记录**（键 = `conversationId`）、**同一个去重域**
    * （`pullAnchor`；`advanceAnchor` 按构造使它与 `dispatchedMax` 恒等）⇒ 同一消息
    * 经**真 push** 或**补拉**任一路径派发后，另一路径读到的水位已越过它 ⇒ 不重复投递
    * （统一去重优先于路径分离）。
    *
    * 补拉腿的**签名真源** = 两个调用点（`dispatchPull` 读回终态装配对账行、
    * `pullConversation` 判冷/温锚），二者皆取 `(conversationId: String) => IO[Long]`。
    *
    * 🔴 单实现：本方法**委托** `localMaxId`（§3.5 拆字段时被保留的同一读取，见其
    * scaladoc）而**不另写一份**字段读法 —— 两份实现 = 两套语义，本仓缺陷族形态。
    * 二名一物（生产走本名、单测走旧名）的收口需动既有调用点/单测，本批按最小加性边界
    * 不动，已登记为遗留项。 */
  def pullAnchor(conversationId: String): IO[Long] = localMaxId(conversationId)

  /** **已派发**水位（已构造帧并投给 UI 的最大消息 id）。判据③读它做
    * `pullAnchor == dispatchedMax` 断言。 */
  def dispatchedMaxOf(conversationId: String): IO[Long] =
    state.get.map(_.cursors.get(conversationId).map(_.dispatchedMax).getOrElse(0L))

  /** 批 C 对账拍的**扫描面**：所有「已有可信派发水位」的会话及其 `dispatchedMax`。
    *
    * 只返回 `dispatchedMax > 0` 的会话 —— `dispatchedMax == 0` 表示该会话**从未派发
    * 过**（冷锚），对它做「服务端水位 vs 本地水位」比较没有意义：keyset `after=0`
    * 服务端会从**最旧**一页开始返回（ASC），那是历史而不是「差态」，据此补拉会把
    * 一页历史当成丢帧（正是 `pullConversation` 冷锚分支明令不取数的原因）。
    * 一次读取同时给出 id 与水位，避免「先列 id 再逐个读水位」的 N+1 Ref 往返。 */
  def dispatchedAnchors: IO[List[(String, Long)]] =
    state.get.map(_.cursors.toList.collect { case (k, c) if c.dispatchedMax > 0L => k -> c.dispatchedMax })

  /** **已读**水位（用户读到哪儿）。与 `localMaxId`（拉取）**分开**——§3.5 拆字段的
    * 全部意义就在这里：前端开窗收帧即 `markConversationRead`，修前那条会**顺带把
    * 补拉锚点抬到消息尾部**，落在中间的未派发消息永久不可达。 */
  def readAnchor(conversationId: String): IO[Long] =
    state.get.map(_.cursors.get(conversationId).map(_.lastReadMessageId).getOrElse(0L))

  /**
   * 推进**拉取/已派发**水位（补拉且**派发成功**之后）。
   *
   * 🔴 两格**同一次原子更新**推进、取值相同 ⇒ `pullAnchor == dispatchedMax` 按构造
   * 成立，**禁** `pullAnchor > dispatchedMax`（越过未派发条 = 那条此后永久不可达）。
   * 调用方的义务 = 只把**派发成功的最大 id** 传进来（见 `dispatchPull`）。
   *
   * 两个字段都取 `max`（**不得回退**：重放/乱序页可能带回更小的值）。未读数不在此
   * 维护——服务端 unreadCount 是权威基线（mergeUnread），message_new 事件做增量
   * （bumpUnread），markRead 清零。
   */
  def advanceAnchor(conversationId: String, maxMessageId: Long): IO[Unit] =
    state.update { s =>
      val cur = s.cursors.getOrElse(conversationId, ConversationCursor(conversationId, 0L, 0))
      s.copy(cursors =
        s.cursors.updated(
          conversationId,
          cur.copy(
            pullAnchor = Math.max(cur.pullAnchor, maxMessageId),
            dispatchedMax = Math.max(cur.dispatchedMax, maxMessageId)
          )
        )
      )
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

  /** 标记已读：推进**已读**锚点并清零未读。
    *
    * 🔴 §3.5 拆字段的**关键改动点**：修前本方法写的是 `lastReadMessageId`，而那正是
    * 补拉锚点 ⇒ 前端开窗收帧即 `markConversationRead`（`messages.js` 的
    * `api.markConversationRead(p.conversationId, m.id)`）会把**拉取水位一并抬到该消息
    * id**。于是「读到 114、113 的推送被吞」这一形态下，113 在后续补拉里**再也取不到**
    * （`after=114`），永不渲染——静默丢失机制之一。拆开后本方法**只**动已读格，
    * 拉取/已派发水位不受影响（`pullAnchor` / `dispatchedMax` 各自由 `advanceAnchor` 推进）。 */
  def setRead(conversationId: String, lastReadMessageId: Long): IO[Unit] =
    state.update { s =>
      val cur = s.cursors.getOrElse(conversationId, ConversationCursor(conversationId, 0L, 0))
      s.copy(cursors = s.cursors.updated(conversationId, cur.copy(lastReadMessageId = Math.max(cur.lastReadMessageId, lastReadMessageId), unreadCount = 0)))
    }

  // ── §3.7 对账计数（纯本地态；批 C 复用同一批计数字段暴露到 /api/neblink/status，
  //    本批只**产**不**曝**——暴露面归批 C，禁两批各写一份计数）─────────────

  /** 一次补拉的记账（§3.7）。恒等式 `pulled == dispatched + skipped` 由 `pulled`
    * 与 `skipped` 同源推出 ⇒ 计数与日志行不会各说各话。 */
  private val pullStatsRef: Ref[IO, Map[String, FriendPullStats]] =
    Ref.unsafe[IO, Map[String, FriendPullStats]](Map.empty)

  /** 累加一次补拉的记账（按会话累加，进程内累计值，不落盘）。 */
  def recordPull(conversationId: String, pulled: Long, dispatched: Long, skipped: Long): IO[Unit] =
    pullStatsRef.update { m =>
      val cur = m.getOrElse(conversationId, FriendPullStats(0L, 0L, 0L))
      m.updated(
        conversationId,
        FriendPullStats(cur.pulled + pulled, cur.dispatched + dispatched, cur.skipped + skipped)
      )
    }

  /** 对账计数快照（判据④的机器读数面；`skipped` 恒 == `pulled - dispatched`）。 */
  def pullStats: IO[Map[String, FriendPullStats]] = pullStatsRef.get

  /** 近期对账/静默留痕行（**有界 FIFO**，进程内，不落盘）。
    *
    * 为什么需要它：日志行此前**不可断言**（本仓既有 spec 的已知缺口——只断言副作用，
    * 不断言行内容），而本批的判据①②④按定义就是**行内字段**的机械判据（三字段同行的
    * 出现数、`pulled==dispatched+skipped`、`trigger=`/`eventId=` 配对）。把同一行同时
    * 送进日志与这个环，判据就能在**真调用链**上被断言，而不是靠「重建一行文本」。
    * 上限 64（≈ 一次双消息全链 + 余量），超出丢最旧。 */
  private val pullTraceRef: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
  private val PullTraceMax = 64

  def recordPullLine(line: String): IO[Unit] =
    pullTraceRef.update(ls => (ls :+ line).takeRight(PullTraceMax))

  /** 近期留痕行快照（最新在尾）。 */
  def recentPullLines: IO[List[String]] = pullTraceRef.get

  def unreadSnapshot: IO[Map[String, Int]] =
    state.get.map(_.cursors.view.mapValues(_.unreadCount).toMap)

end FriendMessagingGuard
