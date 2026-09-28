/* 严格DAG第⑥步第二批裁定(2026-09-27):本文件为 shared 承载件——R9 自 nebflow/neblink/NeblinkModel.scala 剪出下沉的 PeerInfo(含伴生 codec)/FriendSummary/FriendRequestSummary/OutgoingRequestSummary/FriendListResponse/GroupSummary(整块逐字,含伴生与字段注释);R6 的 Protocol.DeviceHeader 与 R7 的 DefaultRelayTimeout、R8 的 RelayMailResult 亦并落本文件(各自剪出点留裁定注释)。斩断 core→neblink / core→dropbox / dropbox→neblink 边;neblink/dropbox/core 内引用改指本包。 */
package nebflow.shared

import io.circe.*
import io.circe.generic.semiauto.*

// ── R6:Protocol.DeviceHeader(自 nebflow/neblink/Protocol.scala 剪出,剪出点留裁定注释)──

/**
 * Device identity header for peer requests. Set by the P2P leg
 * (`RemoteExecutor` `remote-exec` dispatch) and read by the gateway's peer
 * criterion (`RestApiRoutes#verifyPeerAccess`). The RELAY leg does NOT carry
 * it — a relayed exec authenticates with the tunnel's own
 * `Authorization: Bearer` session, so this header has no relayed read/write
 * point (2026-09-20 勘误②: the previous wording claimed "and relayed
 * requests", which no code supported).
 */
val DeviceHeader: String = "X-Neblink-Device"

// ── R7:DefaultRelayTimeout(自 nebflow/neblink/NeblinkClient.scala 剪出,剪出点留裁定注释)──

/**
 * Default per-call relay timeout — **保留既有 10 s 语义**作为默认值。
 *
 * 附件腿批（2026-09-12）把 `sendRequest` / `relayExec` 的超时改为**按调用可传**：
 * 分块腿传长超时而**不动全局默认**——`sendRequest` 是全部 relay 调用的共用路径，
 * 全局放宽会连带改掉工具执行 / 好友消息的既有语义（设计件 §3.5）。
 */
val DefaultRelayTimeout: scala.concurrent.duration.FiniteDuration =
  scala.concurrent.duration.FiniteDuration(10, scala.concurrent.duration.SECONDS)

// ── R8:RelayMailResult(自 nebflow/neblink/NeblinkClient.scala 剪出,剪出点留裁定注释)──

/**
 * 设备邮件中继的响应读数（B 批，2026-09-16 作者裁定「路径 B」一步到位）。
 *
 *   - `id` = 服务端事件 id（`message-` 前缀已剥；响应未带可判读 id 即**空串** ——
 *     **不伪造 id**，原语义逐字不变）；
 *   - `delivered` = 服务端 `delivered` 键的读数：`true` = 载荷已推进对端的**活体隧道**
 *     （`push_background` 被接受 ⇒ **≠ 对端已注入**）；`false` = 此刻无该设备隧道 ⇒
 *     行已持久、随下一轮隧道注册补齐，**不是错误**（服务端口径逐字见 `neblink-server`
 *     `agentmail.rs:227-259` / `routes.rs:1893-1906`）。
 *
 * **键缺席降级口径（首次选定，加性）**：旧服务端 / 契约外响应不带 `delivered` ⇒ 按
 * `false`（**保守支**）——只在服务端**显式**断言 `true` 时才声称一次活体推送，缺席
 * 一律按「服务端已接受、尚未确认活体推送」处理（**禁冒认**服务端未断言的推送）。
 * 既有语义面（`error` 判读 / id 三候选宽容读取 / `Left` 失败语义 / 请求面 / ack 面）
 * 全部**零变更**。
 */
final case class RelayMailResult(id: String, delivered: Boolean)

// ── R9:自 NeblinkModel.scala 剪出的模型块(整块逐字)──

// ===== Peer Info =====

case class PeerInfo(
  deviceId: String,
  deviceName: String,
  platform: String,
  address: String,
  deviceSecret: String = "",
  capabilities: Map[String, String] = Map.empty,
  userDescription: String = "",
  lastSeen: Long = System.currentTimeMillis(),
  /**
   * C1 (2026-09-11 P2P 直连修复批): **every** endpoint the peer declared to the
   * NebLink Server, preference-ordered (see [[EndpointPreference]]), `address`
   * being `endpoints.head`.
   *
   * WHY: the namelist drops down to `endpoints.head` on the receiving side
   * (`NeblinkClient.toNeblinkPeers`) — in the 2026-09-11 incident that head was
   * an unreachable LAN address while a working Tailscale endpoint sat at index
   * 1, so P2P could never come up. `address` alone is a single point of failure;
   * this field is the candidate list the dial / execute sides walk.
   *
   * Empty (`Nil`) for peers built by paths that carry no server namelist
   * (inbound presence route, test fixtures) — those fall back to `address`,
   * i.e. pre-C1 behaviour. Defaulted ⇒ wire/JSON decoding stays backward
   * compatible (`NeblinkModelSpec` "endpoints 缺省" 回归).
   */
  endpoints: List[String] = Nil
)

object PeerInfo:
  given Encoder[PeerInfo] = deriveEncoder

  given Decoder[PeerInfo] = Decoder.instance { c =>
    for
      deviceId <- c.downField("deviceId").as[String]
      deviceName <- c.downField("deviceName").as[String]
      platform <- c.downField("platform").as[String]
      address <- c.downField("address").as[String]
      deviceSecret <- c.downField("deviceSecret").as[Option[String]].map(_.getOrElse(""))
      capabilities <- c.downField("capabilities").as[Option[Map[String, String]]].map(_.getOrElse(Map.empty))
      userDescription <- c.downField("userDescription").as[Option[String]].map(_.getOrElse(""))
      lastSeen <- c.downField("lastSeen").as[Option[Long]].map(_.getOrElse(System.currentTimeMillis()))
      // C1: absent (all pre-existing persisted records / API payloads) ⇒ Nil.
      endpoints <- c.downField("endpoints").as[Option[List[String]]].map(_.getOrElse(Nil))
    yield PeerInfo(
      deviceId,
      deviceName,
      platform,
      address,
      deviceSecret,
      capabilities,
      userDescription,
      lastSeen,
      endpoints
    )
  }
end PeerInfo

/**
 * 好友/搜索结果卡（/api/users/lookup 与 /api/friends 共用形态）。
 * blocked：#290 §1.2 拉黑行透传（仅 GET /api/friends 的 friends 数组携带，
 * absent = 未拉黑）——此前该字段被网关丢弃，web 端只能靠 localStorage 镜像。
 */
/**
 * 好友/请求/会话共用的档案对象。字段即 friend-search-contract v1.0 契约词汇
 *  （NL 号 = Username，作者 2026-09-05 裁定）：username 可空语义由 String 折叠
 *  ""（未设置 NL 号）；displayName 必填（服务端永不为 null，见 Decoder 镜像
 *  fallback 链）；avatar 可 null。since/blocked 为信封字段（camelCase 维持）。
 *
 *  `remark`（2026-09-12 好友消息改造批 ⑦）：**纯本地字段**——用户设的好友备注，
 *  持久化在 `FriendRemarkStore`（`<dataRoot>/friend-remarks.json`，键 = userId），
 *  由 `FriendService.applyRemarks` 在出站口注入。**上游永不带该键**（协议零变更）：
 *  Decoder 不读它，Encoder 恒出该键（`None` ⇒ `null`，⑦-D8 加性最简形态）。
 *  形参置末且有默认值 ⇒ 既有构造点（含位置实参）零改动。
 */
case class FriendSummary(
  userId: String,
  username: String,
  displayName: String,
  avatar: Option[String] = None,
  since: Option[Long] = None,
  blocked: Option[Boolean] = None,
  remark: Option[String] = None
)

/**
 * 收到的好友请求（incoming 分组）。createdAt：请求时间透传（#290 0904 批次
 * UI 打磨——申请行时间显示；旧上游无此字段时为 None）。
 */
case class FriendRequestSummary(
  requestId: String,
  from: FriendSummary,
  note: Option[String] = None,
  createdAt: Option[Long] = None
)

/** 发出的好友请求（outgoing 分组）。 */
case class OutgoingRequestSummary(
  requestId: String,
  to: FriendSummary,
  createdAt: Option[Long] = None
)

case class FriendListResponse(
  friends: List[FriendSummary],
  incoming: List[FriendRequestSummary] = Nil,
  outgoing: List[OutgoingRequestSummary] = Nil
)

/**
 * 群会话行（`GET /api/groups` 裸数组的元素，也是 `GET /api/sync/bootstrap` 的
 * `groups` 行形态；跨仓真源 = neblink-server `src/model.rs` 的 `GroupSummary`，
 * `#[serde(rename_all = "camelCase")]`）。
 *
 * 为什么有本件（gmsgsend 批 · 补充卡 §6.2）：群目标解析（`FriendRoster.resolveGroup`）
 * 需要「本用户所属、未解散群会话」的 `groupId` + `title` 两个键。此前本仓只在网关侧
 * **逐字转发**群列表（群行从未解码成领域类型）；本件是最小解码件，
 * **不新增任何线上面**（纯客户端侧解码）。
 *
 * 字段取舍（🔴 只落解析必需 + 契约已冻结的行内键，其余键由解码器忽略）：
 *  - `groupId` / `title`：解析链 L1/L2/L3 的唯二匹配键。`title` **可重名**（服务端
 *    只校验非空且 ≤64 字符，无唯一性约束）⇒ 重名走候选列表，见 `resolveGroup`。
 *  - `role` / `memberCount` / `unreadCount` / `lastMessageId` / `createdAt`：服务端
 *    冻结行内键，保留供后续面读数；**本批零消费点**。
 *  - `selfUserId`（加性小批 `53c0be7`，契约 v2.1 §11.4）：viewer 自证键。
 *    🔴 本仓**不把它当权威**（身份权威 = 服务端鉴权解出的身份）—— 只解码、不消费，
 *    缺省空串（零群账号的裸数组退化态读不到该值，服务端已明写该退化态）。
 *  - **不解码** `lastMessage`：本批零消费点，解码它会把整条消息图钉进解析路径。
 *  - 全部非必填键带缺省值 ⇒ 行内键集未来加性扩面**不破**本解码器（与 `FriendSummary`
 *    的 Option 折叠口径同族的「键缺席 = 缺省」纪律）。
 */
case class GroupSummary(
  groupId: String,
  title: String,
  role: String = "",
  memberCount: Int = 0,
  unreadCount: Int = 0,
  lastMessageId: Long = 0L,
  createdAt: Long = 0L,
  selfUserId: String = ""
)
