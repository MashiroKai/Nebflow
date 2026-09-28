/* 严格DAG第⑥步终批裁定(2026-09-27):协议载荷纯数据类型下沉 shared(行为保持)。 */
package nebflow.shared

import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}

/**
 * 会话级计数器（AgentState 顶层字段——S3 跨 turn，turn 边界只清 S1 与
 * R 连续计数）。turnKey = state.loopTurnKey.toString（逻辑 turn 纪元：
 * UserInput/外部事件唤醒/Mail 投递/冻结唤醒等 dispatch 起点 +1；
 * ToolsComplete 续轮/retry/save-compact 续跑不递增——currentTurnId 每次
 * LLM dispatch 都 +1，wiring 实证不能当 turn 身份用）。
 *
 * turn 语义补充（R1 修正，2026-09-10 冻结缺陷）：**用户唤醒（UserWake）
 * 不只是新 turn 纪元，同时重新开始跨 turn 观察窗**——挂载点在冻结唤醒的
 * state 组装链上调用 [[Counters.resetCrossTurn]]，唤醒前累积的 crossTurn
 * 失败记录作废。否则唤醒后同 fp 只再失败 1 次就被唤醒前的记录推过
 * crossTurnFailureTurns（实测唤醒后 6.65s / 21.26s 复冻）。
 */
final case class Counters(
  turnKey: String = "",
  streakFp: String = "",
  streakErrHash: String = "",
  streakCount: Int = 0,
  /** R-call：上一支调用（连续同参计数；不同 fp 即刷新）。 */
  lastCallFp: String = "",
  lastCallTool: String = "",
  lastCallCount: Int = 0,
  /** R-text：上一轮助手文本（连续逐字相同计数；不同文本即刷新）。 */
  lastTextHash: String = "",
  lastTextCount: Int = 0,
  /** fp → 曾 L1 终止过的集合（同 fp 复发 → 直接 L2，D3）。 */
  terminatedFps: Set[String] = Set.empty,
  /** fp → 失败过的 turn 集合（S3；该 fp 任一次成功即整条清除）。 */
  crossTurn: Map[String, Set[String]] = Map.empty
):
  /** AgentControl list 的 loop×N 展示值（两路连续计数的较大者）。 */
  def repeatStreak: Int = math.max(lastCallCount, lastTextCount)

  /**
   * R1（2026-09-10 冻结族最小修法）：清零 S3 跨 turn 观察窗——用户唤醒
   * （UserWake）重新开始跨 turn 计数，唤醒前累积的同 fp 失败记录作废。
   *
   * 只清 crossTurn：terminatedFps（L1 终止记账）与一切阈值/冻结数值不动——
   * 被 L1 终止过的 fp 在后续 turn 复发仍即刻 Freeze（刻意保留的既有语义）；
   * turnKey/S1/R 连续计数无需在此清（evaluate 的 turn 边界分支按新 turnKey
   * 自动归零）。
   */
  def resetCrossTurn: Counters = copy(crossTurn = Map.empty)

end Counters

object Counters:
  val Empty: Counters = Counters()

case class MailQueueItem(
  id: String,
  from: String,
  fromSession: String,
  message: String,
  /** Advisory type tag (INFO / RESULT etc.), same vocabulary as MailTool type. */
  `type`: String,
  timestamp: Long,
  /**
   * G3: image attachment paths. The queue persists paths (not base64 — queue
   * files stay small); paths are re-read and re-compressed at drain time via
   * ImageInject.drainImagePaths. A file that vanished between send and drain
   * degrades to an `[attachment lost: path]` placeholder.
   */
  imagePaths: List[String] = Nil
)

object MailQueueItem:

  given Encoder[MailQueueItem] = Encoder.instance { item =>
    Json.obj(
      "id" -> item.id.asJson,
      "from" -> item.from.asJson,
      "fromSession" -> item.fromSession.asJson,
      "message" -> item.message.asJson,
      "type" -> item.`type`.asJson,
      "timestamp" -> item.timestamp.asJson,
      // G3 attachment paths — old decoders ignore unknown fields (hand-written
      // downField readers), so this is forward compatible.
      "imagePaths" -> item.imagePaths.asJson
    )
  }

  given Decoder[MailQueueItem] = Decoder.instance { c =>
    for
      id <- c.downField("id").as[String]
      from <- c.downField("from").as[String]
      fromSession <- c.downField("fromSession").as[String].orElse(Right(""))
      message <- c.downField("message").as[String]
      itemType <- c.downField("type").as[String].orElse(Right("INFO"))
      timestamp <- c.downField("timestamp").as[Option[Long]].map(_.getOrElse(0L))
      imagePaths <- c.downField("imagePaths").as[List[String]].orElse(Right(Nil))
    yield MailQueueItem(id, from, fromSession, message, itemType, timestamp, imagePaths)
  }

/**
 * 审批卡答复形状（spec §2.4）。
 *
 * `approved: Boolean` **必需** —— 形状不符**不消费卡**（与 `InteractionHub.answerCompletes`
 * 同构，`InteractionHub.scala:404-409`：形状不符的答复不得吃掉槽位，否则 deferred 永挂）。
 * 可选扩展 `scope: "once"|"session"`、`upgradeMode`（走既有 `PermissionUpgrade.parse`）。
 */
final case class McpPermissionAnswer(
  approved: Boolean,
  scope: Option[String],
  upgradeMode: Option[String]
):
  /** P0-2：仅 `session` 触发会话放行记忆；`once` / 其他值 / 缺省 = 仅本次。 */
  def wantsSessionScope: Boolean = scope.contains("session")

object McpPermissionAnswer:

  def decode(payload: Json): Option[McpPermissionAnswer] =
    payload.hcursor.downField("approved").as[Boolean].toOption.map { approved =>
      McpPermissionAnswer(
        approved = approved,
        scope = payload.hcursor.downField("scope").as[String].toOption,
        upgradeMode = payload.hcursor.downField("upgradeMode").as[String].toOption
      )
    }
