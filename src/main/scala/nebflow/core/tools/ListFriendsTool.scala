package nebflow.core.tools

import cats.effect.IO
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import nebflow.neblink.{FriendRoster, FriendService, FriendSummary}

/**
 * ListFriends — Nebula 编排面**只读**好友名册（好友消息改造批 ⑩；方案
 * `20260912_011320_好友消息改造批方案` §4.5 定稿 + 判词
 * `20260912_014147_friendmsg-plan-reverify` 首行 PASS）。
 *
 * 缺口（本工具存在的理由）：`SendMessage` 的候选文案只在**失败路径**出现，且当
 * `displayName == username` 时零区分力 ⇒ 模型此前只能靠「试错 + 报错」寻址。
 *
 * 职责边界（零重复实现）：
 *  - 取数唯一走 `FriendService.listFriends`（**分态穿透**，上游 `Left` 原样上抛）——
 *    **不用** `refreshFriends`（折叠版会把「未登录 / 上游故障」谎报成「没有好友」，
 *    违反判据 L6）。零新 REST 面（上游仍是 `NeblinkClient.listFriends` →
 *    `FriendListResponse`/`FriendSummary`）。
 *  - 行文案唯一走 `FriendRoster.candidateLine`（与 `SendMessage` 失败候选同形 ——
 *    成功路径与失败路径同一套词表）。本工具**不做解析**（`resolve` 归 `SendMessage`）。
 *  - 只读、零副作用：一次读，不刷本地态、不动未读游标、不触发任何写。
 *
 * 授能面（方案 §4.5 #2/#5 定稿）：**仅** `AgentCore.NebulaOrchestrationTools` 单点
 * 携带；`DispatcherFixedTools` / `BaseTools` / 全 agent 面均不携带；agent.json 声明
 * （含 `"*"`）不授能 —— 名字进 `AgentCore.NebulaExclusiveTools`（防声明逃逸单点，
 * 同时覆盖 `AgentLibrary` 定义保存侧 strip）。插件白名单（
 * `PluginRegistry.BuiltinToolWhitelist`）不含；`RemoteExecutor.remoteableTools` 不含
 * （零参数 schema，**不得**被注入 `device` 参数）。
 *
 * 服务注入：`ctx.sharedResources.flatMap(_.friendService)`（先例
 * `TransferFileTool.scala:298-301`；`NeblinkWiring.sharedResourcesSlot` 恒 `Some`）
 * ⇒ `GatewayMain` 零改动（与本工具同族的 `FriendMessageTool.initialize` 单例模式
 * 无关，其迁移属 ⑩-7 另立批）。
 */
object ListFriendsTool extends Tool:

  val name = "ListFriends"

  /** 名册行封顶（方案 §4.5 `inputSchema` 条定稿）：**不加 `limit` 参数**——静默截断
    * 会让模型得出「此人不是我好友」的错误结论。超长名册的正确处理 = 代码内封顶 +
    * 显式尾行 `... N more friends not shown`。
    *
    * 取值 = 200：对真实名册（username 多为 NL 号 / 邮箱，displayName 多为短名）
    * 渲染约 10–20 KB，稳在 `ToolResultGuard` 的单结果阈值（
    * `Defaults.DefaultMaxResultSizeChars` = 50_000，第 1 层 guard）之下 ⇒ **封顶尾行
    * 是名册的实际截断点**，不会被 guard 的静默 preview 替换抢先把尾行吃掉。 */
  private[tools] val MaxRows = 200

  val description =
    """List the user's NebLink friends — the roster of accepted friendships — read-only, with no side effects. Friends are NOT devices: a device is one of the user's own other machines on the same NebLink account (a separate surface, and one this tool never lists); a friend is a different account, linked by an accepted friend request. Use this to see who can be messaged before calling SendMessage.

## Output
One friend per line: `<display name> (<username>)[ [blocked]]`. The first line is a count line (`Friends: N`); a very long roster is capped in code and ends with an explicit `... N more friends not shown` line instead of being silently truncated. An empty roster and a failed read are reported differently: an empty roster is a successful result — `Friends: 0` followed by `The friend list is empty (no accepted friendships).` — whereas an unreadable roster (not signed in to NebLink, or the friends service is down) is an error, and must never be read as "that person is not my friend". No online / reachable / last-seen state is reported, because the friends data carries no such field: Do not assume a friend is reachable from this list. `[blocked]` marks a friend the user has blocked (the marker is absent for everyone else); it is a roster annotation only — this tool makes no claim about whether messaging that friend is permitted.

## Notes
Read-only, zero side effects: a single read of the friend roster — it does not refresh local state, does not move any unread cursor, and triggers no write of any kind. Hand the values from this list to `SendMessage`'s `to` parameter unchanged (the username, or the display name)."""

  /** 零参数（显式给出，`ToolRegistry.ALL_TOOLS` 会把 schema 交给 LLM）：
    * 不加 `filter`（会再造一条解析路径，与 `FriendRoster.resolve` 的模糊/唯一前缀
    * 语义必然分叉）；不加 `limit`（见 `MaxRows`）。 */
  val inputSchema: JsonObject = JsonObject(
    "type"       -> "object".asJson,
    "properties" -> Json.obj(),
    "required"   -> Json.arr()
  )

  private[tools] def service(ctx: ToolContext): Either[ToolError, FriendService] =
    ctx.sharedResources.flatMap(_.friendService) match
      case Some(fs) => Right(fs)
      case None =>
        Left(ToolError("Friend list is unavailable: NebLink friends service is not initialized."))

  /** 名册 → 平铺文本行（非 JSON）。首行恒为计数行（`Friends: <总数>`，**总数**而非
    * 显示行数——封顶时也不让模型误判好友总数）。 */
  private[tools] def render(friends: List[FriendSummary]): String =
    val count = s"Friends: ${friends.size}"
    if friends.isEmpty then s"$count\n${FriendRoster.availableHint(friends)}"
    else
      val rows = friends.take(MaxRows).map(f => FriendRoster.candidateLine(f))
      val tail =
        if friends.size > MaxRows then List(s"... ${friends.size - MaxRows} more friends not shown")
        else Nil
      (count :: (rows ++ tail)).mkString("\n")

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    service(ctx) match
      case Left(err) => IO.pure(Left(err))
      case Right(fs) =>
        // listFriends = 分态穿透（Left 上抛）。失败文案必须显式说「读不到」并点明
        // 「这不是空名册」——判据 L6（上游故障被报成「没有好友」= 红）。
        fs.listFriends.map {
          case Left(err) =>
            Left(
              ToolError(
                s"Unable to read the friend list: $err. This is not an empty friend list — the roster could not be loaded " +
                  "(check the NebLink sign-in state, then retry)."
              )
            )
          case Right(resp) => Right(render(resp.friends))
        }

  def summarize(input: JsonObject): String = "ListFriends()"

  def summarizeResult(input: JsonObject, result: String): String = result

end ListFriendsTool
