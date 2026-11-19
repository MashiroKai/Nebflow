package nebflow.social

import io.circe.Json

/** Permission cards deliberately exclude all tool-supplied content, including names and summaries. */
private[social] object FeishuPermissionCard:
  val namespace = "stand-permission-v1"
  def card(requestId: String, answered: Option[Boolean] = None): String =
    def text(s: String): Json = Json.obj("tag" -> Json.fromString("plain_text"), "content" -> Json.fromString(s))
    def button(label: String, decision: String): Json = Json.obj(
      "tag" -> Json.fromString("button"), "text" -> text(label), "type" -> Json.fromString("default"),
      "value" -> Json.obj("nf" -> Json.fromString(namespace), "requestId" -> Json.fromString(requestId),
        "decision" -> Json.fromString(decision))
    )
    val body = answered match
      case None => List(
        Json.obj("tag" -> Json.fromString("div"), "text" -> text(s"请求执行操作。请求编号：$requestId\n敏感详情已隐藏，请在 Stand 中核对后决定。")),
        Json.obj("tag" -> Json.fromString("action"), "actions" -> Json.arr(button("批准", "allow"), button("拒绝", "deny")))
      )
      case Some(value) => List(Json.obj("tag" -> Json.fromString("div"), "text" -> text(if value then "已批准" else "已拒绝")))
    Json.obj("header" -> Json.obj("template" -> Json.fromString("orange"), "title" -> text("权限审批")),
      "elements" -> Json.arr(body*)).noSpaces
end FeishuPermissionCard
