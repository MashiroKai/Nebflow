package nebflow.cli

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*

object FeishuCommand extends CliCommand:
  def name = "feishu"
  def description = "Manage Feishu bridge"
  def subcommands = List(FeishuStatus, FeishuConfig, FeishuBind, FeishuUnbind)

  def examples = List(
    "nebflow feishu status",
    "nebflow feishu bind abc-123 oc_xxx"
  )

  private object FeishuStatus extends CliSubcommand:
    def name = "status"
    def description = "Show Feishu bridge status"
    def params = Nil

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          client.command(Json.obj("type" -> "getFeishuGlobalConfig".asJson)).map { resp =>
            if ctx.json then CliResult.Json(resp)
            else
              val configured = resp.hcursor.downField("configured").as[Boolean].getOrElse(false)
              if configured then
                val appId = resp.hcursor.downField("appId").as[String].getOrElse("?")
                CliResult.text(s"Feishu bridge: configured (appId=$appId)")
              else CliResult.text("Feishu bridge: not configured")
          }

  end FeishuStatus

  private object FeishuConfig extends CliSubcommand:
    def name = "config"
    def description = "View/set Feishu global config"

    def params = List(
      CliParam("app-id", None, "App ID", required = false),
      CliParam("app-secret", None, "App Secret", required = false)
    )

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          val appId = ctx.args.get("app-id")
          val appSecret = ctx.args.get("app-secret")
          if appId.isEmpty && appSecret.isEmpty then
            // Just show current config
            client.command(Json.obj("type" -> "getFeishuGlobalConfig".asJson)).map(resp => CliResult.Json(resp))
          else
            client
              .command(
                Json.obj(
                  "type" -> "updateFeishuGlobalConfig".asJson,
                  "appId" -> appId.getOrElse("").asJson,
                  "appSecret" -> appSecret.getOrElse("").asJson
                )
              )
              .as(CliResult.text("Feishu config updated"))

  end FeishuConfig

  private object FeishuBind extends CliSubcommand:
    def name = "bind"
    def description = "Bind session to Feishu chat"

    def params = List(
      CliParam("session-id", None, "Session ID", required = true),
      CliParam("chat-id", None, "Feishu chat ID", required = true),
      CliParam("chat-type", None, "Chat type (group/p2p)", required = false)
    )

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          val sessionId = ctx.positionalArgs.headOption.getOrElse("")
          val chatId = ctx.positionalArgs.lift(1).orElse(ctx.args.get("chat-id")).getOrElse("")
          val chatType = ctx.args.getOrElse("chat-type", "p2p")
          if sessionId.isEmpty || chatId.isEmpty then IO.pure(CliResult.Error("Session ID and Chat ID required"))
          else
            client
              .command(
                Json.obj(
                  "type" -> "updateSessionFeishu".asJson,
                  "sessionId" -> sessionId.asJson,
                  "chatId" -> chatId.asJson,
                  "chatType" -> chatType.asJson,
                  "enabled" -> true.asJson
                )
              )
              .as(CliResult.text(s"Session $sessionId bound to Feishu chat $chatId"))

  end FeishuBind

  private object FeishuUnbind extends CliSubcommand:
    def name = "unbind"
    def description = "Unbind session from Feishu"
    def params = List(CliParam("session-id", None, "Session ID", required = true))

    def run(ctx: CliContext): IO[CliResult] =
      ctx.client match
        case None => IO.pure(CliResult.Error("Gateway not running"))
        case Some(client) =>
          val sessionId = ctx.positionalArgs.headOption.getOrElse("")
          if sessionId.isEmpty then IO.pure(CliResult.Error("Session ID required"))
          else
            client
              .command(
                Json.obj(
                  "type" -> "updateSessionFeishu".asJson,
                  "sessionId" -> sessionId.asJson,
                  "chatId" -> "".asJson
                )
              )
              .as(CliResult.text(s"Session $sessionId unbound from Feishu"))
  end FeishuUnbind
end FeishuCommand
