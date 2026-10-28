package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.core.*
import nebflow.perception.{DraftCodec, PerceptionCore, PerceptionModel, PerceptionService}
import nebflow.shared.*

/**
 * `EmailSend` 工具（r2 §7 / §13 agent 工具面，r3+ 增强）：感知邮件发送的
 * 拟稿入口。**工具只负责「落草稿 + 弹快照卡」**——发送动作永远由用户在
 * 卡片（照此发送）或画布（编辑后发送）手动触发，无 agent 侧自动发送路径
 * （r2 §7.1 裁决 2 语义，逐字保持）。
 *
 * 流程：参数 → 草稿文件（front-matter + 正文，`~/.<root>/perception/drafts/`，
 * 0600）→ 草稿注册表 → AskUser 快照卡（单题三动作，`id` 携带
 * `perceptionDraft:<draftId>` 标记供前端换肤为邮件快照面）→ 非阻塞 ack
 * （答复稍后作为消息到达本会话）。
 *
 * 附件（r3+ 加强项 1 的边界）：本批为**只读展示层**——路径进草稿与卡片
 * 展示；发送附件未开放，`sendDraft` 侧明确拒绝（不静默丢弃）。多收件人/
 * 多附件校验为宽进（卡片/画布阶段再拦）。
 */
object PerceptionMailTool extends Tool:

  val name = "EmailSend"

  def description: String =
    """Compose an email draft for the user's connected mailbox (perception mail). The draft is
saved locally and a READ-ONLY snapshot card appears in the conversation; the user then either
sends it as-is, or opens it in Canvas to edit and send there. You never send directly — the
user always clicks send. The user's answer (sent / edited-in-canvas-then-sent / abandoned)
arrives later as a message in this session.

Parameters:
- to (string or array of string, required): recipient address(es).
- subject (string, required).
- body (string, required): plain text body; converted to a clean HTML mail body (line breaks become paragraphs). Use bodyHtml only when you need rich formatting.
- cc / bcc (string or array of string, optional).
- attachments (array of string, optional): absolute local paths — DISPLAY ONLY this phase (the mail face shows them; actual attachment upload is not open yet, and a draft carrying attachments is refused at send time).
- quoteFrom / quoteText (string, optional): when replying to a received mail, pass the original sender and an excerpt — rendered as the read-only quote block below the compose area (real-mail-client reply semantics).
- inReplyTo (string, optional): Message-ID of the mail being replied to (sets the In-Reply-To header).

The mailbox must already be connected in the perception panel (未连接时本工具报错，引导用户先在感知面板「连接邮箱」）。"""

  def inputSchema: JsonObject = JsonObject(
    "type" -> Json.fromString("object"),
    "properties" -> Json.obj(
      "to" -> Json.obj("type" -> Json.fromString("string")),
      "cc" -> Json.obj("type" -> Json.fromString("string")),
      "bcc" -> Json.obj("type" -> Json.fromString("string")),
      "subject" -> Json.obj("type" -> Json.fromString("string")),
      "body" -> Json.obj("type" -> Json.fromString("string")),
      "bodyHtml" -> Json.obj("type" -> Json.fromString("string")),
      "attachments" -> Json.obj("type" -> Json.fromString("array")),
      "quoteFrom" -> Json.obj("type" -> Json.fromString("string")),
      "quoteText" -> Json.obj("type" -> Json.fromString("string")),
      "inReplyTo" -> Json.obj("type" -> Json.fromString("string"))
    ),
      "required" -> Json.arr("to".asJson, "subject".asJson, "body".asJson)
  )

  def summarize(input: JsonObject): String =
    val to = addrField(input, "to").mkString(", ")
    s"EmailSend → $to · ${str(input, "subject").take(60)}"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.startsWith("requestId=") then "draft card shown"
    else result.take(80)

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val to = addrField(input, "to")
    val cc = addrField(input, "cc")
    val bcc = addrField(input, "bcc")
    val subject = str(input, "subject")
    val bodyText = str(input, "body")
    val bodyHtmlRaw = str(input, "bodyHtml")
    val attachments = input("attachments").flatMap(_.asArray).map(_.flatMap(_.asString).toList).getOrElse(Nil)
    val quoteFrom = str(input, "quoteFrom")
    val quoteText = str(input, "quoteText")
    val inReplyTo = str(input, "inReplyTo")

    if to.isEmpty then IO.pure(Left(ToolError("EmailSend: 'to' is required (at least one recipient address)")))
    else if subject.isBlank then IO.pure(Left(ToolError("EmailSend: 'subject' is required")))
    else if bodyText.isBlank && bodyHtmlRaw.isBlank then
      IO.pure(Left(ToolError("EmailSend: 'body' is required")))
    else
      val html = if bodyHtmlRaw.nonEmpty then bodyHtmlRaw else textToHtml(bodyText)
      val quote =
        if quoteFrom.nonEmpty || quoteText.nonEmpty then
          Some(
            s"""<perception-quote data-quote="1"><div class="pq-head">发件人：${esc(
                if quoteFrom.nonEmpty then quoteFrom else "(未知发件人)"
              )}</div><div class="pq-body">${esc(quoteText.take(2000)).replace("\n", "<br>")}</div></perception-quote>"""
          )
        else None
      val doc = DraftCodec.DraftDoc(
        draftId = "", // assigned by createDraft
        to = to,
        cc = cc,
        bcc = bcc,
        subject = subject,
        bodyHtml = html,
        attachments = attachments,
        replyToMessageId = Option(inReplyTo).filter(_.nonEmpty),
        quoteHtml = quote
      )
      PerceptionService.coreNow.flatMap {
        case None =>
          IO.pure(Left(ToolError("感知守护进程未启动 · 无法创建邮件草稿（perception daemon not booted)")))
        case Some(core) =>
          core.hasAccount.flatMap:
            case false =>
              IO.pure(Left(ToolError(
                "尚未连接邮箱 · 请先在感知面板点击「连接邮箱」完成两步配置（邮箱地址 + 授权码），再发送邮件。"
              )))
            case true =>
              core.createDraft(doc.copy(draftId = "")).flatMap {
                case Left(err) => IO.pure(Left(ToolError(s"草稿落盘失败: $err")))
                case Right(rec) =>
                  emitSnapshotAsk(core, rec, doc, ctx)
              }
      }

  /** AskUser snapshot card (id marks it as a perception draft for the web face). */
  private def emitSnapshotAsk(
    core: PerceptionCore,
    rec: PerceptionModel.DraftRecord,
    doc: DraftCodec.DraftDoc,
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    ctx.agentActorRef match
      case None => IO.pure(Left(ToolError("EmailSend requires agent actor")))
      case Some(agentRef) =>
        val requestId = nebflow.shared.InteractionRequestId.forAskUserNonBlocking()
        val question = snapshotSummary(doc)
        val items = List(
          nebflow.shared.AskItem(
            question = question,
            options = List(
              nebflow.shared.AskOption(label = "照此发送"),
              nebflow.shared.AskOption(label = "在 Canvas 中修改"),
              nebflow.shared.AskOption(label = "放弃")
            ),
            allowOther = false,
            id = Some(s"perceptionDraft:${rec.draftId}")
          )
        )
        for
          _ <- core.attachRequest(rec.draftId, requestId)
          bridge = AskUserAnswerPort.ref(agentRef, items, requestId, ctx)
          _ = agentRef ! nebflow.actor.AgentCommand.AskUser(requestId, items, Some(bridge), Nil)
        yield Right(ack(rec, requestId))

  def ack(rec: PerceptionModel.DraftRecord, requestId: String): String =
    s"requestId=$requestId · draftId=${rec.draftId} · snapshot card shown · non-blocking: the user's " +
      "decision (sent as-is / edited in canvas then sent / abandoned / expired) arrives later as a " +
      "message in this session — do not wait for it."

  private def snapshotSummary(doc: DraftCodec.DraftDoc): String =
    val att =
      if doc.attachments.isEmpty then ""
      else s"\n附件：${doc.attachments.map(n => n.replaceAll(".*/", "")).mkString("、")}"
    s"待发送的邮件\n收件人：${doc.to.mkString("、")}" +
      (if doc.cc.nonEmpty then s"\n抄送：${doc.cc.mkString("、")}" else "") +
      s"\n主题：${doc.subject}$att"

  // ── param helpers ──────────────────────────────────────────────────────

  private def str(input: JsonObject, k: String): String =
    input(k).flatMap(_.asString).getOrElse("")

  private def addrField(input: JsonObject, k: String): List[String] =
    input(k).flatMap(_.asArray).map(_.flatMap(_.asString).toList)
      .orElse(input(k).flatMap(_.asString).map(List(_)))
      .getOrElse(Nil)
      .flatMap(DraftCodec.parseAddresses)

  /** Plain text → minimal clean HTML (escape + paragraphs). */
  def textToHtml(text: String): String =
    text.split("\n", -1).map(p => s"<p>${esc(p)}</p>").mkString("\n")

  private def esc(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
