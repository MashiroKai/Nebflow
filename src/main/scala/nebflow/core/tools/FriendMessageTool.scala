package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.neblink.{FriendRoster, FriendService, FriendSummary}

import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * SendMessage tool — A2A 一期 agent 发消息能力（friends-messaging-arch §7.1
 * 冻结设计 + 20260828 方向补充 spec §2）。LLM 通过它以用户身份向已建立好友关系的
 * 联系人发送文本消息；收方看到的是好友本人（用户身份送达，冻结语义）。
 *
 * 职责边界（零重复实现）：本工具只做「to 解析 + 参数校验 + 错误转写」；发送一律走
 * FriendService.sendAsAgent —— auto/ask/off 三档权限、双层限速（20/h/好友、60/h
 * 全局）与超限自动降级 ask 全部既有（choke point 唯一，spec §2.2）。
 *
 * to 解析（2026-09-12 ⑦ 后的完整链）：**L0 备注** → L1 username 精确（大小写不
 * 敏感，服务端唯一性口径一致）→ L2 昵称精确 → L3 昵称唯一前缀 → **L4 邮箱 α**
 * （前四级全未命中时走一次上游搜索回落，按命中卡 `userId` 精确回映射好友表）。
 * 多命中/零命中一律返回候选列表让模型自行纠错（同 turn 内最便宜的修复点）。
 * 解析 L0–L3 与候选文案的**唯一实现点** = `nebflow.neblink.FriendRoster`（批 ⑩
 * 2026-09-12 收归，`ListFriends` 同用同一份词表）——本工具只委托；L4 是数据面
 * 回落（见 `lookupFriendBySearch`），不在本地匹配口径内。
 *
 * 接线：GatewayMain 启动时 FriendMessageTool.initialize(friendService)
 * （RemoteExecutor.initialize 同款单例模式）。授权（阶段 2d，设计 D.1-11）：
 * 机制固定唯一——仅 Nebula 的静态集 NebulaOrchestrationTools 携带（2c 起从
 * 声明制迁机制固定）；agent.json tools 声明不再授能（buildAllowedToolSet 对
 * base 一律剥离本工具名，"*" 亦然——the tool name IS the permission
 * boundary）。 */
object FriendMessageTool extends Tool:

  private val TimeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")
  private val MaxMessageLength = 4000

  @volatile private var service: Option[FriendService] = None

  /** Startup wiring (GatewayMain). No-op safe to call once. */
  def initialize(fs: FriendService): Unit = service = Some(fs)

  val name = "SendMessage"

  val description =
    """Send a text message to one of the user's NebLink friends, acting on the user's behalf. Only established friend relationships can receive messages; the message is delivered as the user (the recipient sees it as the user themselves). Subject to permission tiers and rate limits (per-friend and global hourly caps); depending on the user's configuration the send may require explicit user confirmation or be disabled outright.

## Parameters
- to (string, required): The recipient — the friend's remark (a local nickname the user set), their NebLink username (NL ID), or their email address; their display name also works. Resolution order: exact remark, exact username, exact display name, unique display-name prefix, then an account lookup by email. On no or ambiguous match the error lists available friends (remarks shown in `[remark: …]`).
- message (string, required): Message text, max 4000 characters. Plain text only."""

  val inputSchema: JsonObject = JsonObject(
    "type" -> "object".asJson,
    "properties" -> Json.obj(
      "to" -> Json.obj(
        "type"        -> "string".asJson,
        "description" -> "Friend's remark, username (NL ID), or email — or their display name.".asJson
      ),
      "message" -> Json.obj(
        "type"        -> "string".asJson,
        "description" -> s"Message text (max $MaxMessageLength characters).".asJson
      )
    ),
    "required" -> List("to", "message").asJson
  )

  /** Three-level resolution (spec §2 task item 2). Pure — public for tests.
    *
    * 批 ⑩（2026-09-12，方案 `20260912_011320` §4.5 同步点 ③）：实现已收归唯一
    * 单点 `FriendRoster.resolve`（与 `ListFriends` 共用同一份候选文案 —— 成功路径
    * 与失败路径同一套词表）。本方法**只做委托**：对外文案、L1–L3 解析顺序与逐字
    * 输出零变更，参数名校验与 IO 组合全在 `call` 侧不变。
    *
    * 批 ⑦（同日）：`FriendRoster.resolve` 内部前置了 L0 备注层（⑦-D5）。
    */
  def resolveFriend(query: String, friends: List[FriendSummary]): Either[ToolError, FriendSummary] =
    FriendRoster.resolve(query, friends)

  /** L4 邮箱层（α 方案，⑦-D4/⑦-D11，本批唯一邮箱路径）。
    *
    * `FriendRoster.resolve` 的 L0–L3 全未命中时，把 query 交给**既有**搜索端点
    * （`FriendService.searchUser` → `NeblinkClient.searchUser` → neblink-server
    * `GET /api/users/search`，username OR email 双键 NOCASE 精确）；命中卡自带
    * `userId`（服务端 `SearchUserCard.user_id`，`~/.nebflow/projects/neblink-server/src/model.rs:491-500`）
    * ⇒ 按 `userId` **精确回映射好友表**，映射成功才发送（搜索能命中非好友，而只有
    * 好友可被发消息）。
    *
    * 边界（全部硬口径）：
    *  - **仅失败路径** +1 次上游往返；命中卡不带 email 字段也无需它（只用 userId）。
    *  - **计入同一限流桶**：本调用是 `/api/users/search` 的唯一客户端通路 ⇒ 服务端
    *    `{uid}:search` 20/min 桶（`src/friends.rs:279`）自然会计入（⑦-D11：不新开
    *    桶语义、失败路径不得成为绕过限流的探测面）。
    *  - **上游失败（429/5xx/网络）⇒ `None`**：调用方回落「原样 not-found + 候选」，
    *    **不得**把上游故障升格成「好友不存在」。
    *  - **禁回显**（R3）：本函数不把 query 写进任何错误文案 / 日志（备注与邮箱都是
    *    用户私人数据，且错误文案会进模型上下文并随会话落盘）。空 query 直接短路
    *    （上游对空白输入会消耗一个配额单位，`src/friends.rs:279-283`）。
    */
  private def lookupFriendBySearch(
    fs: FriendService,
    query: String,
    friends: List[FriendSummary]
  ): IO[Option[FriendSummary]] =
    val q = query.trim
    if q.isEmpty then IO.pure(None)
    else
      fs.searchUser(q).map {
        case Left(_) => None
        case Right(json) =>
          val found = json.hcursor.get[Boolean]("found").getOrElse(false)
          if !found then None
          else
            json.hcursor
              .downField("user")
              .get[String]("userId")
              .toOption
              .flatMap(uid => friends.find(_.userId == uid))
      }

  /** Send after resolution — extracted so the IO composition stays flat
    * (Scala 3: multi-line matches inside nested flatMap braces are fragile). */
  private def sendTo(fs: FriendService, friend: FriendSummary, message: String): IO[Either[ToolError, String]] =
    fs.sendAsAgent(friend.userId, message).map {
      // 回执形态（⑦-D6）：`备注（username）`——让用户/模型能确认「打到的是谁」。
      // 备注缺席 ⇒ 退回 displayName；username 缺席（存量账号未设 NL 号，
      // Decoder 折叠空串）⇒ 省略该括号，不渲染空壳。
      case Right(_) =>
        val label = friend.remark.filter(_.nonEmpty).getOrElse(friend.displayName)
        val idPart = if friend.username.nonEmpty then s"（${friend.username}）" else ""
        Right(s"已发送给 $label$idPart（${LocalTime.now().format(TimeFormat)}）")
      case Left(err) => Left(ToolError(err))
    }

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val to      = input("to").flatMap(_.asString)
    val message = input("message").flatMap(_.asString)

    def bad(msg: String): IO[Either[ToolError, String]] = IO.pure(Left(ToolError(msg)))

    service match
      case None =>
        bad("Friend messaging is unavailable: NebLink friends service is not initialized.")
      case Some(fs) =>
        (to, message) match
          case (None, _) => bad("Missing required parameter 'to' (friend's remark, username, or email).")
          case (_, None) => bad(s"Missing required parameter 'message'.")
          case (Some(t), Some(m)) if m.isEmpty =>
            bad("'message' is empty — nothing to send.")
          case (Some(t), Some(m)) if m.length > MaxMessageLength =>
            bad(s"Message too long (${m.length} chars, max $MaxMessageLength).")
          case (Some(t), Some(m)) =>
            // refreshFriends() resolves to a snapshot directly (swallows
            // upstream errors into an empty list — acceptable: resolution
            // then reports "friend list is empty" with no candidates).
            // 该出口已由 FriendService.applyRemarks 注入本地备注 ⇒ L0 层与候选
            // 文案都读得到备注（⑦：工具侧零取数改动）。
            val prepared: IO[(Either[ToolError, FriendSummary], List[FriendSummary])] =
              fs.refreshFriends().map(resp => resolveFriend(t, resp.friends) -> resp.friends)
            prepared.flatMap {
              case (Right(friend), _) => sendTo(fs, friend, m)
              case (Left(err), friends) =>
                // L0–L3 全未命中 ⇒ 走 L4 邮箱 α（仅失败路径，+1 次上游往返）。
                // 命中且能回映射成好友 ⇒ 发送；否则（miss / 上游故障 / 非好友）
                // 回落**原样**的 not-found + 候选错误（不升格、不回显 query）。
                lookupFriendBySearch(fs, t, friends).flatMap {
                  case Some(friend) => sendTo(fs, friend, m)
                  case None         => IO.pure(Left(err))
                }
            }
    end match
  end call

  def summarize(input: JsonObject): String =
    val to = input("to").flatMap(_.asString).getOrElse("?")
    s"SendMessage(to=$to)"

  def summarizeResult(input: JsonObject, result: String): String = result
end FriendMessageTool
