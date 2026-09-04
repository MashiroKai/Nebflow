package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.neblink.{FriendService, FriendSummary}

import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * SendFriendMessage tool — A2A 一期 agent 发消息能力（friends-messaging-arch §7.1
 * 冻结设计 + 20260828 方向补充 spec §2）。LLM 通过它以用户身份向已建立好友关系的
 * 联系人发送文本消息；收方看到的是好友本人（用户身份送达，冻结语义）。
 *
 * 职责边界（零重复实现）：本工具只做「to 解析 + 参数校验 + 错误转写」；发送一律走
 * FriendService.sendAsAgent —— auto/ask/off 三档权限、双层限速（20/h/好友、60/h
 * 全局）与超限自动降级 ask 全部既有（choke point 唯一，spec §2.2）。
 *
 * to 解析三级（照 RemoteExecutor.resolvePeer 模式，spec 任务书）：
 *   1. neblinkId 精确（大小写不敏感，服务端唯一性口径一致）
 *   2. 昵称精确
 *   3. 昵称唯一前缀
 * 多命中/零命中一律返回候选列表让模型自行纠错（同 turn 内最便宜的修复点）。
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

  val name = "SendFriendMessage"

  val description =
    """Send a text message to one of the user's NebLink friends, acting on the user's behalf. Only established friend relationships can receive messages; the message is delivered as the user (the recipient sees it as the user themselves). Subject to permission tiers and rate limits (per-friend and global hourly caps); depending on the user's configuration the send may require explicit user confirmation or be disabled outright.

## Parameters
- to (string, required): The recipient — the friend's NebLink ID or display name. Resolution order: exact NebLink ID, exact name, unique name prefix. On no or ambiguous match the error lists available friends.
- message (string, required): Message text, max 4000 characters. Plain text only."""

  val inputSchema: JsonObject = JsonObject(
    "type" -> "object".asJson,
    "properties" -> Json.obj(
      "to" -> Json.obj(
        "type"        -> "string".asJson,
        "description" -> "Friend's NebLink ID or display name.".asJson
      ),
      "message" -> Json.obj(
        "type"        -> "string".asJson,
        "description" -> s"Message text (max $MaxMessageLength characters).".asJson
      )
    ),
    "required" -> List("to", "message").asJson
  )

  /** Three-level resolution (spec §2 task item 2). Pure — public for tests. */
  def resolveFriend(query: String, friends: List[FriendSummary]): Either[ToolError, FriendSummary] =
    val q = query.trim
    val candidatesHint =
      if friends.isEmpty then "The friend list is empty (no accepted friendships)."
      else s"Available friends: ${friends.map(f => s"${f.name} (${f.neblinkId})").mkString(", ")}"

    if q.isEmpty then Left(ToolError(s"'to' is empty. $candidatesHint"))
    else
      val byId = friends.filter(_.neblinkId.equalsIgnoreCase(q))
      byId match
        case single :: Nil => Right(single)
        case _ =>
          val byName = friends.filter(_.name.equalsIgnoreCase(q))
          byName match
            case single :: Nil => Right(single)
            case multi =>
              val byPrefix = friends.filter(_.name.toLowerCase.startsWith(q.toLowerCase))
              val hits     = (multi ++ byPrefix).distinct
              hits match
                case single :: Nil => Right(single)
                case many =>
                  Left(
                    ToolError(
                      if many.isEmpty then s"Friend '$q' not found. $candidatesHint"
                      else s"Friend '$q' is ambiguous (${many.size} matches). Candidates: ${many.map(f => s"${f.name} (${f.neblinkId})").mkString(", ")} — use the exact NebLink ID."
                    )
                  )
  end resolveFriend

  /** Send after resolution — extracted so the IO composition stays flat
    * (Scala 3: multi-line matches inside nested flatMap braces are fragile). */
  private def sendTo(fs: FriendService, friend: FriendSummary, message: String): IO[Either[ToolError, String]] =
    fs.sendAsAgent(friend.userId, message).map {
      case Right(_)  => Right(s"已发送给 ${friend.name}（${LocalTime.now().format(TimeFormat)}）")
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
          case (None, _) => bad("Missing required parameter 'to' (friend's NebLink ID or display name).")
          case (_, None) => bad(s"Missing required parameter 'message'.")
          case (Some(t), Some(m)) if m.isEmpty =>
            bad("'message' is empty — nothing to send.")
          case (Some(t), Some(m)) if m.length > MaxMessageLength =>
            bad(s"Message too long (${m.length} chars, max $MaxMessageLength).")
          case (Some(t), Some(m)) =>
            // refreshFriends() resolves to a snapshot directly (swallows
            // upstream errors into an empty list — acceptable: resolution
            // then reports "friend list is empty" with no candidates).
            val prepared: IO[Either[ToolError, FriendSummary]] =
              fs.refreshFriends().map(right => resolveFriend(t, right.friends))
            prepared.flatMap {
              case Left(err)     => IO.pure(Left(err))
              case Right(friend) => sendTo(fs, friend, m)
            }
    end match
  end call

  def summarize(input: JsonObject): String =
    val to = input("to").flatMap(_.asString).getOrElse("?")
    s"SendFriendMessage(to=$to)"

  def summarizeResult(input: JsonObject, result: String): String = result
end FriendMessageTool
