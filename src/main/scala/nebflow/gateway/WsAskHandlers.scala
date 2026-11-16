package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.AgentCommand
import nebflow.core.AskHandoffPort
import nebflow.core.ask.{AskCooldown, AskEditProposals, AskGatewayServices, AskNoteSnapshot, AskNoteStore, AskNoteWrite, AskSessions}

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
    "askReply" -> handleAskReply,
    "askNoteGet" -> handleAskNoteGet,
    "askNotePut" -> handleAskNotePut,
    // canvas-ask-restricted-edit（CAE-REQ-001）：diff 卡 Accept/Reject 腿。
    "askEditAccept" -> handleAskEditAccept,
    "askEditReject" -> handleAskEditReject
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
          // 可问面硬闸（设计稿 §2；调研稿 §7-A2；批⑩ 2026-10-31 扩列电子书
          // epub/mobi/azw3）：canonicalize 之后、bind 之前拒非白名单——既有
          // askError 通道，绑定表零写入（仅闸新问题：存量绑定不清理，自然
          // 衰减 §9-8）。
          if !AskSessions.isAskable(canonical) then
            askError(
              wsSend,
              "",
              s"askOpen: file type not askable (allowed: ${(AskSessions.AskableExtensions ++ AskSessions.AskableEbookExtensions).toList.sorted.mkString(",")}): $fileName"
            )
          else
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
                  "messages" -> encodeTranscript(msgs).asJson,
                  // canvas-ask-restricted-edit：在列提案随回放下发（重载/换端后
                  // diff 卡重建；引擎为权威——服务端重启即清零，前端本地态让位）。
                  "edits" -> encodePendingEdits(sid).asJson
                )
              )
            yield ()
            end for
      end match
    end if
  end handleAskOpen

  /**
   * .ui.json 转录 → dock 气泡面（injected 族 = sys；回问行带前缀照常入 sys）。
   *
   * Ask 面板历史丢失批（2026-11-03，作者 15:26 报障「Ask 的历史对话记录会消失，
   * 主要是 AI 回复的内容，我提问的内容还存在」）：旧实现只把 `UiMessage.Agent`
   * 投成 ai 泡——而 ask 会话的 ai 行由 root 帧族落盘点
   * （`WebSocketRoutes.makeRecordingWsSend` 的 toolStart/roundComplete/done）写成
   * `UiMessage.Ai`（typeName=`"ai"`），`UiMessage.Agent`（typeName=`"agent"`）在
   * ask 会话从不出现 ⇒ **AI 回复行在 askOpen 回放里恒被静默丢弃**（collect 不
   * 匹配即跳过），user 行照常下发——正是「问还在、答没了」。盘上数据一直完好
   * （`.ui.json` 两族俱在），丢的是这条回放投影腿；前端 localStorage write-through
   * 与「永不缩水」合并律在同一浏览器里掩住了它，本地账缺席/被淘汰/换设备时回放
   * 是唯一真源 ⇒ 整批 AI 回复消失。
   *
   * 修法：ai 族接 `UiMessage.Ai`。相邻 ai 段（同轮被 toolStart/roundComplete 切开
   * 的多段 flush；tool 行透明不隔断）并回**一**行，正文 = 各段原文**直连**——
   * 与 live 腿同形（textDelta 跨轮续同一 ai 泡 = 原文拼接，无分隔符）。这同时是
   * dock「永不缩水」合并律的正确性前提：不并则服务端快照行数 > 本地 live 行数，
   * 回放采纳后单轮多泡（重开面板与 live 形态漂移）。纯 thinking 段（正文空白）
   * 不出空泡（live 同样无泡）。
   */
  private[gateway] def encodeTranscript(msgs: List[nebflow.shared.UiMessage]): List[Json] =
    val rows = scala.collection.mutable.ListBuffer.empty[Json]
    msgs.foreach {
      case u: nebflow.shared.UiMessage.User if u.injected =>
        rows += Json.obj("role" -> "sys".asJson, "text" -> u.text.asJson, "source" -> u.source.getOrElse("").asJson)
      case u: nebflow.shared.UiMessage.User =>
        rows += Json.obj("role" -> "user".asJson, "text" -> u.text.asJson)
      case a: nebflow.shared.UiMessage.Ai if a.text.trim.nonEmpty =>
        rows.lastOption.flatMap(_.hcursor.downField("role").as[String].toOption) match
          case Some("ai") =>
            // 同轮相邻段并回一泡（原文直连 = live delta 拼接同形；tool 行透明不隔断，
            // user/sys 行已落行故天然隔断）。
            val prev = rows.last.hcursor.downField("text").as[String].getOrElse("")
            rows(rows.length - 1) = Json.obj("role" -> "ai".asJson, "text" -> (prev + a.text).asJson)
          case _ =>
            rows += Json.obj("role" -> "ai".asJson, "text" -> a.text.asJson)
      case _ => () // tool / agent / ask / system：dock 透明纪律（live 腿同样不进泡列）
    }
    rows.toList

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

  // ── canvas-ask-restricted-edit：提案 Accept/Reject 腿（CAE-REQ-001）────────
  //   askEditAccept {sessionId, proposalId}
  //     → askEditResolved {sessionId, proposalId, outcome:"accepted", path}
  //     → askEditResolved {sessionId, proposalId, outcome:"error", error}（stale/
  //       写失败——提案保持待审，可 Reject 或重新提案）
  //   askEditReject {sessionId, proposalId}
  //     → askEditResolved {sessionId, proposalId, outcome:"rejected"}（零写盘）

  private def encodePendingEdits(sid: String): List[Json] =
    nebflow.core.ask.AskEditProposals.pendingFor(sid).map(p =>
      Json.obj(
        "proposalId" -> p.id.asJson,
        "path" -> p.path.asJson,
        "fileName" -> p.fileName.asJson,
        "oldText" -> p.oldText.asJson,
        "newText" -> p.newText.asJson,
        "createdAt" -> p.createdAt.asJson
      )
    )

  private def handleAskEditResolve(
    accept: Boolean,
    text: String,
    wsSend: Json => IO[Unit]
  ): IO[Unit] =
    val raw = WsDispatch.parsedJson(text)
    val sid = raw.hcursor.downField("sessionId").as[String].getOrElse("")
    val proposalId = raw.hcursor.downField("proposalId").as[String].getOrElse("")
    if !AskSessions.isAskSession(sid) then askError(wsSend, sid, "askEdit: sessionId must be an ask- session")
    else if proposalId.trim.isEmpty then askError(wsSend, sid, "askEdit: proposalId is required")
    else
      val outcome =
        if accept then AskEditProposals.accept(sid, proposalId.trim)
        else AskEditProposals.reject(sid, proposalId.trim)
      outcome.flatMap {
        case Right(proposal) =>
          wsSend(Json.obj(
            "type" -> "askEditResolved".asJson,
            "sessionId" -> sid.asJson,
            "proposalId" -> proposalId.asJson,
            "outcome" -> (if accept then "accepted" else "rejected").asJson,
            "path" -> proposal.path.asJson
          ))
        case Left(err) =>
          wsSend(Json.obj(
            "type" -> "askEditResolved".asJson,
            "sessionId" -> sid.asJson,
            "proposalId" -> proposalId.asJson,
            "outcome" -> "error".asJson,
            "error" -> err.asJson
          ))
      }
  end handleAskEditResolve

  private def handleAskEditAccept(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: Json => IO[Unit],
    watch: ExplorerWatchSession
  ): IO[Unit] = handleAskEditResolve(accept = true, text, wsSend)

  private def handleAskEditReject(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: Json => IO[Unit],
    watch: ExplorerWatchSession
  ): IO[Unit] = handleAskEditResolve(accept = false, text, wsSend)

  // ── Note persistence ───────────────────────────────────────────────────
  // Two inbound frames and two outbound frames, with the same file identity as the ask session
  // (sid = ask-<8 hex>); the store is `AskNoteStore` (versions, clears, size limit: see its header).
  //   askNoteGet {sessionId}
  //     → askNoteState {sessionId, text, ts, exists, version, cleared}   (`ts` equals `version`)
  //     → askNoteState {sessionId, error:"read-failed", detail}           (the note cannot be read)
  //   askNotePut {sessionId, text, baseVersion, writeId?}
  //     → askNoteAck {sessionId, ok:true, ts, version, overwritten:false}
  //     → askNoteAck {sessionId, ok:false, error:"conflict"|"version-required", text, ts, version, exists, cleared}
  //     → askNoteAck {sessionId, ok:false, error:"read-failed"|"too-large"|"bad-session-id"|"write-failed", …}
  //   `writeId` is echoed verbatim so a client can match a receipt to one write. A put without
  //   `baseVersion` (a page loaded before versions existed) is always refused: it cannot tell a note
  //   that never existed from one cleared before clears were versioned, and overwriting is the failure.
  // The note is not carried by askOpen: it is a separate data lifecycle from the transcript replay.

  private def noteStateFields(snap: AskNoteSnapshot): List[(String, Json)] =
    List(
      "text" -> snap.text.asJson,
      "ts" -> snap.ts.asJson,
      "version" -> snap.version.asJson,
      "exists" -> snap.exists.asJson,
      "cleared" -> snap.cleared.asJson
    )

  private def noteStateFrame(sid: String, snap: AskNoteSnapshot): Json =
    snap.error match
      case Some(why) =>
        Json.obj(
          "type" -> "askNoteState".asJson,
          "sessionId" -> sid.asJson,
          "error" -> "read-failed".asJson,
          "detail" -> why.asJson
        )
      case None =>
        val head = List("type" -> "askNoteState".asJson, "sessionId" -> sid.asJson)
        Json.obj((head ++ noteStateFields(snap))*)

  private def noteAckFrame(sid: String, writeId: Option[Json], result: AskNoteWrite): Json =
    def ack(fields: (String, Json)*): Json =
      val head = List("type" -> "askNoteAck".asJson, "sessionId" -> sid.asJson)
      Json.obj((head ++ writeId.map("writeId" -> _).toList ++ fields)*)
    result match
      case AskNoteWrite.Ok(version, _) =>
        // overwritten stays for older clients: a conditional write never replaces an unseen version.
        ack("ok" -> true.asJson, "ts" -> version.asJson, "version" -> version.asJson, "overwritten" -> false.asJson)
      case AskNoteWrite.Conflict(current) =>
        ack((List("ok" -> false.asJson, "error" -> "conflict".asJson) ++ noteStateFields(current))*)
      case AskNoteWrite.VersionRequired(current) =>
        ack((List("ok" -> false.asJson, "error" -> "version-required".asJson) ++ noteStateFields(current))*)
      case AskNoteWrite.ReadFailed(msg) =>
        ack("ok" -> false.asJson, "error" -> "read-failed".asJson, "detail" -> String.valueOf(msg).asJson)
      case AskNoteWrite.TooLarge(bytes, limit) =>
        ack("ok" -> false.asJson, "error" -> "too-large".asJson, "bytes" -> bytes.asJson, "limit" -> limit.asJson)
      case AskNoteWrite.BadSessionId(id) =>
        ack("ok" -> false.asJson, "error" -> "bad-session-id".asJson, "detail" -> id.asJson)
      case AskNoteWrite.Failed(msg) =>
        ack("ok" -> false.asJson, "error" -> "write-failed".asJson, "detail" -> String.valueOf(msg).asJson)

  private def handleAskNoteGet(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: Json => IO[Unit],
    watch: ExplorerWatchSession
  ): IO[Unit] =
    val raw = WsDispatch.parsedJson(text)
    val sid = raw.hcursor.downField("sessionId").as[String].getOrElse("")
    if !AskSessions.isAskSession(sid) then askError(wsSend, sid, "askNoteGet: sessionId must be an ask- session")
    else AskNoteStore.current.read(sid).map(noteStateFrame(sid, _)).flatMap(send(wsSend, _))

  private def handleAskNotePut(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: Json => IO[Unit],
    watch: ExplorerWatchSession
  ): IO[Unit] =
    val raw = WsDispatch.parsedJson(text)
    val sid = raw.hcursor.downField("sessionId").as[String].getOrElse("")
    val body = raw.hcursor.downField("text").as[String].toOption
    // A missing, null, negative or non-numeric base is "no base version".
    val baseVersion = raw.hcursor.downField("baseVersion").as[Long].toOption.filter(_ >= 0L)
    val writeId = raw.hcursor.downField("writeId").focus
    if !AskSessions.isAskSession(sid) then askError(wsSend, sid, "askNotePut: sessionId must be an ask- session")
    else body match
      case None => send(wsSend, noteAckFrame(sid, writeId, AskNoteWrite.Failed("text is required")))
      case Some(value) =>
        AskNoteStore.current.put(sid, value, baseVersion).map(noteAckFrame(sid, writeId, _)).flatMap(send(wsSend, _))
  end handleAskNotePut
end WsAskHandlers
