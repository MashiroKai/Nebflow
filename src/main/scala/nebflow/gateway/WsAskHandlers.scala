package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.AgentCommand
import nebflow.core.AskHandoffPort
import nebflow.core.ask.{AskCooldown, AskGatewayServices, AskSessions}

/* ═══════════════════════════════════════════════════════════════════════════
 * Canvas ask mode · WS 域（机制稿 §2/§3.3/§4/§7，实施批 2026-10-28）
 *
 * 入站四帧（WsDispatch 注册；出站流式帧沿用 AgentStreamEvent 通道——sessionId
 * = `ask-<hash>`，前端 ask 拦截面路由进气泡列，与主会话视图零交集）：
 *   askOpen     {file:{absPath,name}, mainSessionId?} → askState（绑定 + 历史
 *               回放 + 冷却组装腿；§4「按文件路径+会话键持久化，跨重启复现」）
 *   askQuestion {sessionId, content} → dispatchUserText（真实用户文本链路，
 *               source="ask-dock"；textDelta/done 流式回填气泡）
 *   askHandoff  {sessionId, question} → §7 转交（dock 输入条腿；与工具腿同
 *               限频 1/min/文件，同一 AskHandoffPort 投递面）
 *   askReply    {sessionId, content} → §7 回问（主会话侧 → ask 会话，
 *               ExternalEvent(source="main")，「主会话回问：」前缀在注入文本）
 * ═══════════════════════════════════════════════════════════════════════════ */
private[gateway] object WsAskHandlers:

  val handlers: Map[String, WsDispatch.WsHandler] = Map(
    "askOpen" -> handleAskOpen,
    "askQuestion" -> handleAskQuestion,
    "askHandoff" -> handleAskHandoff,
    "askReply" -> handleAskReply
  )

  private def send(wsSend: Json => IO[Unit], payload: Json): IO[Unit] = wsSend(payload)

  private def askError(wsSend: Json => IO[Unit], sessionId: String, message: String): IO[Unit] =
    send(
      wsSend,
      Json.obj(
        "type" -> "askError".asJson,
        "sessionId" -> sessionId.asJson,
        "message" -> message.asJson
      )
    )

  // ── askOpen：绑定 + 冷却组装 + 历史回放 ──────────────────────────────────

  private def handleAskOpen(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: Json => IO[Unit],
    watch: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val raw = WsDispatch.parsedJson(text)
    val absPath = raw.hcursor
      .downField("file")
      .downField("absPath")
      .as[String]
      .getOrElse(
        raw.hcursor.downField("absPath").as[String].getOrElse("")
      )
    val fileName = raw.hcursor
      .downField("file")
      .downField("name")
      .as[String]
      .getOrElse(
        raw.hcursor.downField("name").as[String].getOrElse("file")
      )
    val mainSessionId = raw.hcursor.downField("mainSessionId").as[String].getOrElse("")

    if absPath.trim.isEmpty then askError(wsSend, "", "askOpen: file.absPath is required")
    else
      AskGatewayServices.instance match
        case None => askError(wsSend, "", "ask mode unavailable on this instance")
        case Some(svc) =>
          val canonical = ctx.expandTilde(absPath.trim)
          for
            binding <- svc.registry.bind(canonical, fileName)
            sid = binding.id
            _ <- if mainSessionId.nonEmpty then svc.registry.refreshMainSession(sid, mainSessionId) else IO.unit
            freshBinding <- svc.registry.byId(sid).map(_.getOrElse(binding))
            _ <- svc
              .assembleIfDue(sid, freshBinding)
              .handleErrorWith(e => logger.warn(s"ask assemble failed (non-fatal,问答继续): ${e.getMessage}"))
            msgs <- sessionStore.getUiMessages(sid, 0, 200).map(_._1)
            _ <- send(
              wsSend,
              Json.obj(
                "type" -> "askState".asJson,
                "sessionId" -> sid.asJson,
                "sessionKey" -> AskSessions.displayKey(canonical).asJson,
                "file" -> freshBinding.file.asJson,
                "fileName" -> freshBinding.fileName.asJson,
                "messages" -> encodeTranscript(msgs).asJson
              )
            )
          yield ()
          end for
      end match
    end if
  end handleAskOpen

  /** .ui.json 转录 → dock 气泡面（injected 族 = sys；回问行带前缀照常入 sys）。 */
  private[gateway] def encodeTranscript(msgs: List[nebflow.shared.UiMessage]): List[Json] =
    msgs.collect {
      case u: nebflow.shared.UiMessage.User if u.injected =>
        Json.obj("role" -> "sys".asJson, "text" -> u.text.asJson, "source" -> u.source.getOrElse("").asJson)
      case u: nebflow.shared.UiMessage.User =>
        Json.obj("role" -> "user".asJson, "text" -> u.text.asJson)
      case a: nebflow.shared.UiMessage.Agent =>
        Json.obj("role" -> "ai".asJson, "text" -> a.text.asJson)
    }

  // ── askQuestion：真实用户文本链路（与输入框同腿 dispatchUserText）────────

  private def handleAskQuestion(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: Json => IO[Unit],
    watch: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val raw = WsDispatch.parsedJson(text)
    val sid = raw.hcursor.downField("sessionId").as[String].getOrElse("")
    val content = raw.hcursor.downField("content").as[String].getOrElse("")

    if !AskSessions.isAskSession(sid) then askError(wsSend, sid, "askQuestion: sessionId must be an ask- session")
    else if content.trim.isEmpty then askError(wsSend, sid, "askQuestion: empty content")
    else
      AskGatewayServices.instance match
        case None => askError(wsSend, sid, "ask mode unavailable on this instance")
        case Some(svc) =>
          svc.registry.byId(sid).flatMap {
            case None => askError(wsSend, sid, "askQuestion: no file binding for this session")
            case Some(binding) =>
              // 冷却组装腿（§3.3：冷却期满后的下一次交互——发问——前重组）。
              svc
                .assembleIfDue(sid, binding)
                .handleErrorWith(e => logger.warn(s"ask assemble failed (non-fatal): ${e.getMessage}")) *>
                // 交互前刷新转交目标（主会话可能已切换）。
                refreshMainIfReported(svc, sid, raw) *>
                ctx.handleUserText(sid, content, "ask-dock", fromUser = true)
          }
    end if
  end handleAskQuestion

  private def refreshMainIfReported(svc: nebflow.core.ask.AskServices, sid: String, raw: Json): IO[Unit] =
    raw.hcursor.downField("mainSessionId").as[String].toOption.filter(_.nonEmpty) match
      case Some(m) => svc.registry.refreshMainSession(sid, m)
      case None => IO.unit

  // ── askHandoff：dock 输入条转交腿（§7；与工具腿同限频同投递面）────────────

  private def handleAskHandoff(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: Json => IO[Unit],
    watch: ExplorerWatchSession
  ): IO[Unit] =
    val raw = WsDispatch.parsedJson(text)
    val sid = raw.hcursor.downField("sessionId").as[String].getOrElse("")
    val question = raw.hcursor.downField("question").as[String].getOrElse("")

    if !AskSessions.isAskSession(sid) then askError(wsSend, sid, "askHandoff: sessionId must be an ask- session")
    else if question.trim.isEmpty then askError(wsSend, sid, "askHandoff: empty question")
    else
      AskGatewayServices.instance match
        case None => askError(wsSend, sid, "ask mode unavailable on this instance")
        case Some(svc) =>
          svc.registry.byId(sid).flatMap {
            case None => askError(wsSend, sid, "askHandoff: no file binding for this session")
            case Some(binding) =>
              val deliver = AskHandoffPort.deliver(
                AskHandoffPort.Request(
                  askSessionId = sid,
                  file = binding.file,
                  fileName = binding.fileName,
                  question = question.trim,
                  leg = "ask-dock"
                )
              )
              deliver.flatMap {
                case Right(_) =>
                  send(
                    wsSend,
                    Json.obj(
                      "type" -> "askHandoffAck".asJson,
                      "sessionId" -> sid.asJson,
                      "ok" -> true.asJson,
                      "question" -> question.trim.asJson
                    )
                  )
                case Left(err) =>
                  askError(wsSend, sid, err.message)
              }
          }
    end if
  end handleAskHandoff

  // ── askReply：主会话回问腿（§7 反向；前缀由本层注入文本携带）──────────────

  private def handleAskReply(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: Json => IO[Unit],
    watch: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val raw = WsDispatch.parsedJson(text)
    val sid = raw.hcursor.downField("sessionId").as[String].getOrElse("")
    val content = raw.hcursor.downField("content").as[String].getOrElse("")

    if !AskSessions.isAskSession(sid) then askError(wsSend, sid, "askReply: sessionId must be an ask- session")
    else if content.trim.isEmpty then askError(wsSend, sid, "askReply: empty content")
    else
      AskGatewayServices.instance match
        case None => askError(wsSend, sid, "ask mode unavailable on this instance")
        case Some(svc) =>
          svc.registry.byId(sid).flatMap {
            case None => askError(wsSend, sid, "askReply: no file binding for this session")
            case Some(_) =>
              val prefixed = s"主会话回问：${content.trim}"
              val metadata = io.circe.JsonObject(
                "askSession" -> sid.asJson,
                "leg" -> "main-reply".asJson
              )
              ctx.ensureAgent(sid)(ref => ref ! AgentCommand.ExternalEvent("main", "inject", prefixed, metadata, None))
          }
    end if
  end handleAskReply
end WsAskHandlers
