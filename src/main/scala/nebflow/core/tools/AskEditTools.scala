package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.ask.{AskEditHunk, AskEditReviews, AskGatewayServices, AskSessions}

import scala.util.control.NonFatal

/* ═══════════════════════════════════════════════════════════════════════════
 * File-bound Canvas Ask · 受限编辑两件套（canvas-ask-restricted-edit 起骨架，
 * canvas-inline-diff-review 批 2026-11-16 翻成 Cursor 式「先应用、后审阅」）：
 * `ask_edit`（old_str→new_str 替换）/ `ask_write`（整文件重写）。
 *
 * 与普通 Edit/Write 的三个本质差：
 *   1. 目标唯一：仅本会话绑定文件的绝对路径（AskEditReviews.capture 闸：
 *      逐字相等 + 无 .. + 非软链 + 非二进制；不创建/删除/移动文件）；
 *   2. 直接落盘 + 记账：调用即写盘（作者原话「agent编辑都是直接成功的」），
 *      同时入账待审变更；用户在 canvas 文件面的**原文档内联 diff** 里逐块
 *      接受/拒绝或一键全部接受/拒绝（拒绝=回退；过期/外部改动=显式冲突，
 *      不静默覆盖）；
 *   3. 授权面双保险：挂载面 = AskSessions.AskExclusiveTools（非 ask 角色剥除）；
 *      工具内侧 = isAskSession + 绑定回查（REST 直调/无绑定一律拒）。
 *
 * 帧面：ctx.wsSend 直推 `askEditApplied`（聚合审阅态：base/current 全文 +
 * hunks 块表，前端渲染原文档内联 diff）；resolve 动作经 WS 域
 * askEditAccept/askEditReject/askEditDismiss（WsAskHandlers）回
 * `askEditResolved`（携带余下审阅态或 error）。
 * ═══════════════════════════════════════════════════════════════════════════ */

object AskEditTools:

  /** hunk 表的 wire 形（0 起行号 + 计数面）。 */
  private[tools] def hunkJson(h: AskEditHunk): Json = Json.obj(
    "id" -> h.id.asJson,
    "oldStart" -> h.oldStart.asJson,
    "oldCount" -> h.oldCount.asJson,
    "newStart" -> h.newStart.asJson,
    "newCount" -> h.newCount.asJson,
    "added" -> h.added.asJson,
    "removed" -> h.removed.asJson
  )

  /** 审阅态 wire 形（askEditApplied 帧 + askEditResolved.review + askState.review 同一单源）。 */
  private[tools] def reviewJson(r: nebflow.core.ask.AskEditReview): Json = Json.obj(
    "path" -> r.path.asJson,
    "fileName" -> r.fileName.asJson,
    "baseText" -> r.baseText.asJson,
    "currentText" -> r.currentText.asJson,
    "hunks" -> r.hunks.map(hunkJson).asJson,
    "count" -> r.changes.length.asJson,
    "updatedAt" -> r.updatedAt.asJson
  )

  /** 落盘成功帧下发（wsSend 缺席 = 无前端通道，账仍成立）。 */
  private[tools] def pushAppliedFrame(review: nebflow.core.ask.AskEditReview, ctx: ToolContext): IO[Unit] =
    ctx.wsSend match
      case Some(send) =>
        send(
          Json
            .obj(
              "type" -> "askEditApplied".asJson,
              "sessionId" -> review.changes.lastOption.map(_.sessionId).getOrElse("").asJson,
              "changeId" -> review.changes.lastOption.map(_.id).getOrElse("").asJson
            )
            .deepMerge(reviewJson(review))
        ).handleError(_ => ())
      case None => IO.unit

  /** 落盘成功后给模型的回话（两件共用）：编辑已生效，审阅在 canvas。 */
  private[tools] def appliedReply(action: String, review: nebflow.core.ask.AskEditReview): String =
    val hunks = review.hunks
    val added = hunks.map(_.added).sum
    val removed = hunks.map(_.removed).sum
    s"OK:APPLIED $action on ${review.path} (+$added -$removed lines in ${hunks.length} block${
        if hunks.length == 1 then "" else "s"
      } pending review). " +
      "The file on disk is ALREADY updated — the user reviews it as an inline diff in the canvas file tab " +
      "(per-block accept / reject, or accept-all / reject-all; rejecting reverts). " +
      "Tell the user briefly that the change is in place and can be reviewed in canvas."

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
    """Apply a search-and-replace edit with Canvas inline diff review. Changes are written immediately; rejection reverts them. Ordinary Edit is also available for general file edits."""

  override def inputSchema: JsonObject = JsonObject(
    "type" -> "object".asJson,
    "properties" -> Json.obj(
      "path" -> Json.obj(
        "type" -> "string".asJson,
        "description" -> "Absolute path of an existing text file to review.".asJson
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

    if !AskSessions.isAskSession(sid) then IO.pure(Left(ToolError(AskEditTools.ForbiddenMessage)))
    else if oldStr.isEmpty then
      IO.pure(
        Left(
          ToolError(
            "ask_edit: `old_str` must be a non-empty string (the ask face cannot create files — the bound file already exists)."
          )
        )
      )
    else if oldStr == newStr then
      IO.pure(Left(ToolError("ask_edit: `old_str` and `new_str` are identical — no change to apply.")))
    else
      AskEditTools
        .bindingFor(sid)
        .flatMap {
          case None =>
            IO.pure(
              Left(
                ToolError(
                  "ask_edit: this ask session has no file binding — open the file card in canvas first. (ASK_EDIT_NO_BINDING)"
                )
              )
            )
          case Some(binding) if !nebflow.core.ask.AskPathBoundary.isBoundDocument(binding.file, path) =>
            EditTool.call(JsonObject(
              "file_path" -> path.asJson,
              "old_string" -> oldStr.asJson,
              "new_string" -> newStr.asJson,
              "replace_all" -> replaceAll.asJson
            ), ctx)
          case Some(binding) =>
            def transform(snapshot: String): Either[String, String] =
              val count = occurrences(snapshot, oldStr)
              if count == 0 then
                Left(
                  "ask_edit refused: old_str not found in the bound file — re-read the file and match it exactly (whitespace included)."
                )
              else if count > 1 && !replaceAll then
                Left(
                  s"ask_edit refused: old_str matches $count locations — add surrounding context to make it unique, or set replace_all=true."
                )
              else if replaceAll then Right(snapshot.replace(oldStr, newStr))
              else
                val i = snapshot.indexOf(oldStr)
                Right(snapshot.substring(0, i) + newStr + snapshot.substring(i + oldStr.length))
            AskEditReviews.applyChange(sid, binding, path, "ask_edit", transform).flatMap {
              case Left(why) => IO.pure(Left(ToolError(why)))
              case Right(review) =>
                AskEditTools.pushAppliedFrame(review, ctx).as(Right(AskEditTools.appliedReply("edit", review)))
            }
        }
        .handleErrorWith(e =>
          IO.pure(Left(ToolError(s"ask_edit failed: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}")))
        )
    end if
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
    """Rewrite an existing text file with Canvas inline diff review. Changes are written immediately; rejection reverts them. Ordinary Write is also available for general file writes."""

  override def inputSchema: JsonObject = JsonObject(
    "type" -> "object".asJson,
    "properties" -> Json.obj(
      "path" -> Json.obj(
        "type" -> "string".asJson,
        "description" -> "Absolute path of an existing text file to review.".asJson
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

    if !AskSessions.isAskSession(sid) then IO.pure(Left(ToolError(AskEditTools.ForbiddenMessage)))
    else
      AskEditTools
        .bindingFor(sid)
        .flatMap {
          case None =>
            IO.pure(
              Left(
                ToolError(
                  "ask_write: this ask session has no file binding — open the file card in canvas first. (ASK_EDIT_NO_BINDING)"
                )
              )
            )
          case Some(binding) if !nebflow.core.ask.AskPathBoundary.isBoundDocument(binding.file, path) =>
            WriteTool.call(JsonObject("file_path" -> path.asJson, "content" -> content.asJson), ctx)
          case Some(binding) =>
            AskEditReviews.applyChange(sid, binding, path, "ask_write", _ => Right(content)).flatMap {
              case Left(why) => IO.pure(Left(ToolError(why)))
              case Right(review) =>
                AskEditTools.pushAppliedFrame(review, ctx).as(Right(AskEditTools.appliedReply("rewrite", review)))
            }
        }
        .handleErrorWith(e =>
          IO.pure(Left(ToolError(s"ask_write failed: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}")))
        )
    end if
  end call
end AskWriteTool
