package nebflow.cli

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*

/** `nebflow session switch <id>` —— 切换网关的**活动会话**。
  *
  * 按既有会话模型：发既有 WS 词汇表的 `switchSession` 帧（WebSocketRoutes.scala:2015
  * 的 `case "switchSession"` → `SessionService.switchSession` → `SessionStore.switchSession`
  * → 落 `_index.json` 的 activeId）。🔴 会话存储语义一字未动：本件只发既有帧 + 读
  * 既有 GET 端点，不新增存储字段、不改 `SessionStore`。
  *
  * 归属说明（同 config 两条）：挂 `session` 名下的新子命令，实现放本新件，
  * `SessionCommand.scala` 只在其 `subcommands` / `examples` 两行做追加。
  *
  * 只做「本机网关的活动会话切换」。跨实例 / 远程语义**不在本批**（见报告末尾
  * 「只登记不实现」一节），本件不碰 neblink、不发任何跨机请求。
  */
object SessionSwitchSub extends CliSubcommand:
  def name = "switch"
  def description = "Switch the active session"
  def params = List(CliParam("session-id", None, "Session ID", required = true))

  def run(ctx: CliContext): IO[CliResult] =
    ctx.client match
      case None => IO.pure(CliResult.Error("Gateway not running"))
      case Some(client) =>
        val sessionId =
          ctx.positionalArgs.headOption.orElse(ctx.args.get("session-id")).getOrElse("").trim
        if sessionId.isEmpty then IO.pure(CliResult.Error("Session ID required"))
        else
          client
            .command(Json.obj("type" -> "switchSession".asJson, "sessionId" -> sessionId.asJson))
            .flatMap { resp =>
              // 错误帧必须浮出：`/api/command` 走 handleMessagePublic，switchSession 的
              // not-found 以 HTTP 200 + `{"type":"error","message":"Session <id> not
              // found"}` 回（WebSocketRoutes.scala:2018-2025 的 handleErrorWith）。不看
              // 这个字段就会把 not-found 静默成 "Switched to …"（qa #339 同款形态）。
              errorOf(resp) match
                case Some(msg) => IO.pure(CliResult.Error(s"Session switch rejected: $msg"))
                case None      => verify(client, sessionId, ctx)
            }
            .handleErrorWith(e => IO.pure(CliResult.Error(s"Session switch failed: ${e.getMessage}")))
  end run

  /** 帧里的错误信息（无 ⇒ None）。 */
  private[cli] def errorOf(resp: Json): Option[String] =
    for
      t <- resp.hcursor.downField("type").as[String].toOption if t == "error"
      m <- resp.hcursor.downField("message").as[String].toOption
    yield m

  /** 用权威读数复核「真的切过去了」——`GET /api/sessions` 回的 `activeId` 与
    * `SessionStore.getActiveId` 同源（RestApiRoutes.scala:188-195）。不凭「请求
    * 没报错」就断言成功：switchSession 的成功回帧是 `memoryStatus`（wsSend 最后一
    * 帧覆盖先前的 `agentSessionList`），帧里根本没有会话号可对。 */
  private def verify(client: GatewayClient, sessionId: String, ctx: CliContext): IO[CliResult] =
    client.get("/api/sessions").map { sessions =>
      val activeId = sessions.hcursor.downField("activeId").as[String].getOrElse("")
      val name = sessions.hcursor
        .downField("sessions")
        .as[List[Json]]
        .getOrElse(Nil)
        .find(_.hcursor.downField("id").as[String].toOption.contains(sessionId))
        .flatMap(_.hcursor.downField("name").as[String].toOption)
        .getOrElse("-")
      if activeId != sessionId then
        CliResult.Error(
          s"Session switch did not take effect: the gateway now reports activeId '$activeId' (requested '$sessionId')"
        )
      else if ctx.json then
        CliResult.Json(
          Json.obj(
            "switched" -> true.asJson,
            "sessionId" -> sessionId.asJson,
            "name" -> name.asJson,
            "activeId" -> activeId.asJson
          )
        )
      else CliResult.text(s"Switched to session $sessionId ($name)")
    }
end SessionSwitchSub
