package nebflow.agent

import cats.effect.{Deferred, IO}
import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite

/**
 * P0-1 / P-M1 卡面侧的验收取值面（A1-5 形状校验 / A1-7 重连重放 / 卡面归属字段）。
 *
 * 取值纪律：断言打在**生产代码本体**上 —— `InteractionHub.answerCompletes`
 * （形状校验的单一判据）与 `InteractionHub.snapshotFrames`（重放快照的单一构造点），
 * 不另造等价物。两者可见性由 `private` 放宽为 `private[agent]`（同包可见，零行为影响），
 * 目的正是让变异臂（去掉形状校验 / 去掉重放纳入）落在**生产行**上而不是测试夹具上。
 */
class McpPermissionReplaySpec extends FunSuite:

  private val McpToolName = "mcp__plugin_acme_browser__navigate"
  private val McpServerId = "plugin_acme_browser"

  private def mcpPayload: Json =
    Json.obj(
      "type" -> "mcpPermission".asJson,
      "toolName" -> McpToolName.asJson,
      "serverId" -> McpServerId.asJson,
      "tool" -> "navigate".asJson,
      "riskTier" -> "L2".asJson,
      "declared" -> "undeclared".asJson,
      "hostBanner" -> false.asJson,
      "allowUpgrade" -> true.asJson
    )

  private def mcpPending(createdAt: Long, root: String = "root-1"): InteractionHub.PendingRequest =
    InteractionHub.PendingRequest(
      reply = InteractionReply.McpPermissionReply(Deferred.unsafe[IO, nebflow.core.McpPermissionAnswer]),
      rootSessionId = root,
      sourceAgent = "agent-x",
      sourceSession = "sess-x",
      kind = InteractionKind.McpPermission,
      payload = mcpPayload,
      createdAt = createdAt
    )

  private def askUserPending(createdAt: Long, root: String = "root-1"): InteractionHub.PendingRequest =
    InteractionHub.PendingRequest(
      reply = InteractionReply.AskUserReply(None),
      rootSessionId = root,
      sourceAgent = "agent-x",
      sourceSession = "sess-x",
      kind = InteractionKind.AskUser,
      payload = Json.obj("items" -> Json.arr(Json.obj("question" -> "q".asJson))),
      createdAt = createdAt
    )

  private def permissionPending(createdAt: Long, root: String = "root-1"): InteractionHub.PendingRequest =
    InteractionHub.PendingRequest(
      reply = InteractionReply.PermissionReply(Deferred.unsafe[IO, Boolean]),
      rootSessionId = root,
      sourceAgent = "agent-x",
      sourceSession = "sess-x",
      kind = InteractionKind.Permission,
      payload = Json.obj("type" -> "askPermission".asJson, "toolName" -> "Write".asJson),
      createdAt = createdAt
    )

  private def answered(payload: Json, root: String = "root-1"): InteractionAnswered =
    InteractionAnswered("req-mcp", root, payload)

  // ============================================================
  // A1-5 形状校验：无 approved 字段的答复不消费卡
  // ============================================================
  test("A1-5 mcpPermission 答复必需 approved:Boolean —— 形状不符不消费卡（生产 answerCompletes）") {
    val p = mcpPending(1)
    assertEquals(
      InteractionHub.answerCompletes(p, answered(Json.obj("scope" -> "session".asJson))),
      false,
      "无 approved ⇒ 不消费卡（否则 deferred 永挂、卡片消失）"
    )
    assertEquals(
      InteractionHub.answerCompletes(p, answered(Json.obj("upgradeMode" -> "auto-edits".asJson))),
      false
    )
    assertEquals(
      InteractionHub.answerCompletes(p, answered(Json.obj("approved" -> "yes".asJson))),
      false,
      "approved 非布尔 ⇒ 同样不消费"
    )
    assertEquals(InteractionHub.answerCompletes(p, answered(Json.obj("approved" -> true.asJson))), true)
    assertEquals(
      InteractionHub.answerCompletes(
        p,
        answered(Json.obj("approved" -> false.asJson, "scope" -> "session".asJson, "upgradeMode" -> "auto-all".asJson))
      ),
      true,
      "approved + 可选扩展字段 ⇒ 消费"
    )
  }

  test("A1-5(零回归) 既有两 kind 的形状判据逐条不变") {
    val a = askUserPending(1)
    assertEquals(InteractionHub.answerCompletes(a, answered(Json.obj("approved" -> true.asJson))), false)
    assertEquals(InteractionHub.answerCompletes(a, answered(Json.obj("answers" -> List("x").asJson))), true)
    val perm = permissionPending(1)
    assertEquals(InteractionHub.answerCompletes(perm, answered(Json.obj("answers" -> List("x").asJson))), false)
    assertEquals(InteractionHub.answerCompletes(perm, answered(Json.obj("approved" -> true.asJson))), true)
  }

  test("A1-5(答复解码) McpPermissionAnswer：approved 必需，scope/upgradeMode 可选") {
    import nebflow.core.McpPermissionAnswer
    assertEquals(McpPermissionAnswer.decode(Json.obj("approved" -> true.asJson)).map(_.approved), Some(true))
    assertEquals(McpPermissionAnswer.decode(Json.obj("scope" -> "session".asJson)), None)
    assertEquals(
      McpPermissionAnswer
        .decode(Json.obj("approved" -> true.asJson, "scope" -> "session".asJson))
        .exists(_.wantsSessionScope),
      true
    )
    assertEquals(
      McpPermissionAnswer
        .decode(Json.obj("approved" -> false.asJson, "scope" -> "session".asJson))
        .exists(_.wantsSessionScope),
      true,
      "scope 解析与 approved 无关（是否记忆由调用侧按 approved 决定）"
    )
    assertEquals(
      McpPermissionAnswer
        .decode(Json.obj("approved" -> true.asJson, "scope" -> "once".asJson))
        .exists(_.wantsSessionScope),
      false
    )
  }

  // ============================================================
  // A1-7 重连重放：pending 期间的 mcpPermission 卡进 ListPendingAsks 快照
  // ============================================================
  test("A1-7 重连重放：mcpPermission 卡进快照（replayed 只读快照 + requestId 绑定）") {
    val frames = InteractionHub.snapshotFrames(
      Map(
        "req-mcp" -> mcpPending(createdAt = 2),
        "req-ask" -> askUserPending(createdAt = 1),
        "req-perm" -> permissionPending(createdAt = 3)
      ),
      Some("root-1")
    )
    assertEquals(frames.size, 2, "AskUser + McpPermission 进快照；内置 Permission 不进（既有口径不变）")
    val byType = frames.map(f => f.hcursor.downField("type").as[String].toOption.getOrElse("") -> f).toMap
    assertEquals(byType.keySet, Set("askUser", "mcpPermission"))
    assert(frames.forall(_.hcursor.downField("replayed").as[Boolean].toOption.contains(true)), "重放标记")
    assertEquals(frames.head.hcursor.downField("type").as[String].toOption, Some("askUser"), "createdAt 升序")
    val mcpFrame = byType("mcpPermission")
    assertEquals(mcpFrame.hcursor.downField("requestId").as[String].toOption, Some("req-mcp"), "requestId 绑定保留")
    assertEquals(mcpFrame.hcursor.downField("sessionId").as[String].toOption, Some("root-1"))
    assertEquals(mcpFrame.hcursor.downField("sourceAgent").as[String].toOption, Some("agent-x"))
    assertEquals(mcpFrame.hcursor.downField("sourceSession").as[String].toOption, Some("sess-x"))
    assertEquals(mcpFrame.hcursor.downField("serverId").as[String].toOption, Some(McpServerId), "payload 原样回放")
    assertEquals(mcpFrame.hcursor.downField("riskTier").as[String].toOption, Some("L2"))
    assertEquals(mcpFrame.hcursor.downField("hostBanner").as[Boolean].toOption, Some(false))
  }

  test("A1-7(只读 + root 过滤) 快照不改槽位；别的 root 不出现") {
    val m = Map("req-mcp" -> mcpPending(createdAt = 1, root = "root-1"))
    assertEquals(InteractionHub.snapshotFrames(m, Some("root-2")).size, 0)
    assertEquals(InteractionHub.snapshotFrames(m, Some("root-1")).size, 1)
    assertEquals(m.size, 1, "只读：快照不得改动 pending map")
    // 全局快照（rootFilter=None）同样纳入 —— #250 第③项的对账面
    assertEquals(InteractionHub.snapshotFrames(m, None).size, 1)
  }
