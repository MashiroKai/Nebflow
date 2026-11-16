package nebflow.core.tools

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import io.circe.JsonObject
import nebflow.core.ask.{AskEditProposals, AskGatewayServices, AskSessions}

import scala.util.control.NonFatal

/* ═══════════════════════════════════════════════════════════════════════════
 * File-bound Canvas Ask · 受限编辑两件套（openspec canvas-ask-restricted-edit，
 * CAE-REQ-001）：`ask_edit`（old_str→new_str 替换）/ `ask_write`（整文件重写）。
 *
 * 与普通 Edit/Write 的三个本质差：
 *   1. 目标唯一：仅本会话绑定文件的绝对路径（AskEditProposals.capture 闸：
 *      逐字相等 + 无 .. + 非软链 + 非二进制；不创建/删除/移动文件）；
 *   2. 零直写：调用即生成**待审提案**（快照 = diff 基线），盘上不动——用户在
 *      前端 diff 卡逐卡 Accept（写盘）/ Reject（丢弃）；
 *   3. 授权面双保险：挂载面 = AskSessions.AskExclusiveTools（非 ask 角色剥除）；
 *      工具内侧 = isAskSession + 绑定回查（REST 直调/无绑定一律拒）。
 *
 * 提案帧：ctx.wsSend 直推 `askEditProposal`（旧/新全文在前端做行级 diff 渲染）；
 * Accept/Reject 经 WS 域 askEditAccept/askEditReject（WsAskHandlers）回
 * `askEditResolved`。
 * ═══════════════════════════════════════════════════════════════════════════ */

object AskEditTools:

  /** 提案帧下发（成功提案后；wsSend 缺席 = 无前端通道，提案仍成立）。 */
  private[tools] def pushProposalFrame(proposal: nebflow.core.ask.AskEditProposal, ctx: ToolContext): IO[Unit] =
    ctx.wsSend match
      case Some(send) =>
        send(Json.obj(
          "type" -> "askEditProposal".asJson,
          "sessionId" -> proposal.sessionId.asJson,
          "proposalId" -> proposal.id.asJson,
          "path" -> proposal.path.asJson,
          "fileName" -> proposal.fileName.asJson,
          "oldText" -> proposal.oldText.asJson,
          "newText" -> proposal.newText.asJson
        )).handleError(_ => ())
      case None => IO.unit

  /** 提案成功后给模型的回话（两件共用）。 */
  private[tools] def proposalReply(action: String, proposal: nebflow.core.ask.AskEditProposal): String =
    s"Proposal ${proposal.id} created ($action on ${proposal.path}). The file is UNCHANGED — the user reviews a diff card and must Accept (write) or Reject (discard) it. Tell the user a change proposal awaits their review."

  /** 绑定回查（SendMessageToMainChatTool 同款纪律）。 */
  private[tools] def bindingFor(sid: String): IO[Option[nebflow.core.ask.AskBinding]] =
    IO.pure(AskGatewayServices.instance.map(_.registry)).flatMap {
      case Some(r) => r.byId(sid)
      case None => IO.pure(None)
    }

  /** ask 面公共身份闸文案。 */
  val ForbiddenMessage: String =
    "ask_edit/ask_write: permission denied — these tools are exclusive to file-ask sessions. (ASK_EDIT_FORBIDDEN)"

end AskEditTools

object AskEditTool extends Tool:

  val Name: String = "ask_edit"

  override def name: String = Name

  override def description: String =
    """Propose a search-and-replace edit to THE file this ask session is bound to (the only editable path; absolute path required). The edit is held as a pending proposal rendered to the user as a diff card — the file is NOT changed until the user accepts it. Rejects: any other path, `..` traversal, symlinks, binary content, old_str not found / ambiguous without replace_all. Prefer ask_edit for targeted changes; use ask_write to rewrite the whole file."""

  override def inputSchema: JsonObject = JsonObject(
    "type" -> "object".asJson,
    "properties" -> Json.obj(
      "path" -> Json.obj(
        "type" -> "string".asJson,
        "description" -> "Absolute path of the bound file (the ask session's file — the only accepted value).".asJson
      ),
      "old_str" -> Json.obj(
        "type" -> "string".asJson,
        "description" -> "Exact text to replace (include surrounding context to make it unique).".asJson
      ),
      "new_str" -> Json.obj(
        "type" -> "string".asJson,
        "description" -> "Replacement text.".asJson
      ),
      "replace_all" -> Json.obj(
        "type" -> "boolean".asJson,
        "description" -> "Replace every occurrence (default false).".asJson
      )
    ),
    "required" -> Json.arr("path".asJson, "old_str".asJson, "new_str".asJson)
  )

  override def summarize(input: JsonObject): String =
    val p = input("path").flatMap(_.asString).getOrElse("").takeRight(40)
    val o = input("old_str").flatMap(_.asString).getOrElse("").take(24)
    s"ask_edit($p, \"$o\")"

  override def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 200 then result.take(197) + "..." else result

  override def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val sid = ctx.sessionId.getOrElse("")
    val path = input("path").flatMap(_.asString).getOrElse("")
    val oldStr = input("old_str").flatMap(_.asString).getOrElse("")
    val newStr = input("new_str").flatMap(_.asString).getOrElse("")
    val replaceAll = input("replace_all").flatMap(_.asBoolean).getOrElse(false)

    if !AskSessions.isAskSession(sid) then
      IO.pure(Left(ToolError(AskEditTools.ForbiddenMessage)))
    else if oldStr.isEmpty then
      IO.pure(Left(ToolError("ask_edit: `old_str` must be a non-empty string (the ask face cannot create files — the bound file already exists).")))
    else if oldStr == newStr then
      IO.pure(Left(ToolError("ask_edit: `old_str` and `new_str` are identical — no change proposed.")))
    else
      AskEditTools.bindingFor(sid).flatMap {
        case None =>
          IO.pure(Left(ToolError("ask_edit: this ask session has no file binding — open the file card in canvas first. (ASK_EDIT_NO_BINDING)")))
        case Some(binding) =>
          def transform(snapshot: String): Either[String, String] =
            val count = occurrences(snapshot, oldStr)
            if count == 0 then
              Left("ask_edit refused: old_str not found in the bound file — re-read the file and match it exactly (whitespace included).")
            else if count > 1 && !replaceAll then
              Left(s"ask_edit refused: old_str matches $count locations — add surrounding context to make it unique, or set replace_all=true.")
            else if replaceAll then Right(snapshot.replace(oldStr, newStr))
            else
              val i = snapshot.indexOf(oldStr)
              Right(snapshot.substring(0, i) + newStr + snapshot.substring(i + oldStr.length))
          AskEditProposals.propose(sid, binding, path, transform).flatMap {
            case Left(why) => IO.pure(Left(ToolError(why)))
            case Right(proposal) =>
              AskEditTools.pushProposalFrame(proposal, ctx).as(Right(AskEditTools.proposalReply("edit", proposal)))
          }
      }.handleErrorWith(e => IO.pure(Left(ToolError(s"ask_edit failed: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}"))))
  end call

  private def occurrences(haystack: String, needle: String): Int =
    var count = 0
    var i = haystack.indexOf(needle)
    while i >= 0 do
      count += 1
      i = haystack.indexOf(needle, i + needle.length)
    count
end AskEditTool

object AskWriteTool extends Tool:

  val Name: String = "ask_write"

  override def name: String = Name

  override def description: String =
    """Propose a whole-file rewrite of THE file this ask session is bound to (absolute path required; the only editable path). The new content is held as a pending proposal rendered as a diff card — the file is NOT changed until the user accepts it. Use for restructuring that is larger than a few replacements; otherwise prefer ask_edit."""

  override def inputSchema: JsonObject = JsonObject(
    "type" -> "object".asJson,
    "properties" -> Json.obj(
      "path" -> Json.obj(
        "type" -> "string".asJson,
        "description" -> "Absolute path of the bound file (the ask session's file — the only accepted value).".asJson
      ),
      "content" -> Json.obj(
        "type" -> "string".asJson,
        "description" -> "The complete new content of the file.".asJson
      )
    ),
    "required" -> Json.arr("path".asJson, "content".asJson)
  )

  override def summarize(input: JsonObject): String =
    val p = input("path").flatMap(_.asString).getOrElse("").takeRight(40)
    val c = input("content").flatMap(_.asString).getOrElse("")
    s"ask_write($p, ${c.length} chars)"

  override def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 200 then result.take(197) + "..." else result

  override def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val sid = ctx.sessionId.getOrElse("")
    val path = input("path").flatMap(_.asString).getOrElse("")
    val content = input("content").flatMap(_.asString).getOrElse("")

    if !AskSessions.isAskSession(sid) then
      IO.pure(Left(ToolError(AskEditTools.ForbiddenMessage)))
    else
      AskEditTools.bindingFor(sid).flatMap {
        case None =>
          IO.pure(Left(ToolError("ask_write: this ask session has no file binding — open the file card in canvas first. (ASK_EDIT_NO_BINDING)")))
        case Some(binding) =>
          AskEditProposals.propose(sid, binding, path, _ => Right(content)).flatMap {
            case Left(why) => IO.pure(Left(ToolError(why)))
            case Right(proposal) =>
              AskEditTools.pushProposalFrame(proposal, ctx).as(Right(AskEditTools.proposalReply("rewrite", proposal)))
          }
      }.handleErrorWith(e => IO.pure(Left(ToolError(s"ask_write failed: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}"))))
  end call
end AskWriteTool
