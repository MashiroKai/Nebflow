/* 作答落库归属批（2026-11-03，作者报障「回答后台智能体的提问泄漏进主会话」）。
 *
 * ## 缺陷（作者 2026-11-03 13:07 原话）
 *
 * 「我回答后台智能体的问题时，回答的内容会泄露到前端的会话里，变成一条被记录的
 * 消息……它不会触发你的认知，它只是被记录到我们的这个对话消息里面了」。
 *
 * 代码判据（改前）：`WsSessionChatHandlers.handleAskUserAnswer` 把作答行写进
 * **帧里的 sessionId**，而 hub 渲染卡片时恒把 `sessionId` 改写成
 * `rootSessionId`（`InteractionHub.renderAskUser`）⇒ 前端作答帧回的
 * `targetSid = msg.sessionId = rootSessionId` ⇒ **子代理 / 项目节点 / 内核的作答
 * 全文落进根会话的 `.ui.json`**（前端历史恢复把 user 行渲染成正文气泡：作者看到
 * 的那条「被记录的消息」）。而根会话的模型上下文里从来没有它（作答经
 * `AskUserAnswerBridge` 注入的是**提问会话**）——「看得见、认知不到」正是本缺陷
 * 的形状。
 *
 * 实证（作者实例 `.stand/sessions/d7c3cb54-…（根）.ui.json`，2026-11-03）：
 * `idx 788 askUser` + `idx 790 user answerOf=asknb-ba738974fc8b4743 text="收货，
 * 无需再改 ⏎ 授权一次性回改历史文档 ⏎ 授权加放大面板"` —— 提问源自后台委托会话，
 * 作答全文却躺在根会话流里。
 *
 * ## 口径（作者令：「回答谁，记录在谁的会话」）
 *
 *   · **自己问自己**（`AskOrigin.isSelfAsk`：根会话自问 / 无 sourceSession 的旧槽位）
 *     ⇒ 与改前**逐字同路径**：作答全文落帧内 sessionId（= 卡片会话），一行不多。
 *   · **子代理 / 项目节点 / 内核提问** ⇒ 两条写：
 *       ① 作答**全文** → 提问会话（`sourceSession`）：谁的提问，答案记在谁的会话；
 *       ② 根会话（帧内 sessionId = 卡片所在会话）只留一条**不含作答内容**的代答
 *          痕迹行（`answerOf` 仍在 ⇒ 历史卡照旧渲染「已作答」，不会退化成一张
 *          永远未答的死卡；`persistence.js askUserAnswerText` 的①精确腿按
 *          `answerOf` 取值，故痕迹文本即历史卡锁定行）。
 *   · **归属未知**（`origin = None`：槽位已消费 / hub 未装配 / 查询失败 / 旧客户端）
 *     ⇒ 回落改前路径（全文落帧内 sessionId）。回落方向刻意与改前一致：归属不明时
 *     宁可维持既有落库形态，也不猜一个「谁的会话」把答案写丢。
 *
 * ## 边界（刻意不做的）
 *
 *   · 取消行（`AskUserCancelRoutes` 的 `__cancelled__`）**不**走本裁决：它不携任何
 *     作答内容（哨兵常量），且「已取消」必须落在卡片所在会话才能关历史卡。
 *   · 已落库的历史行**不回溯清洗**（作者令）：改前写进根会话的全文行原样保留，
 *     本批只剪「新写的」泄漏路径。
 *   · 秘密槽的脱敏是**上游既有闸**（`handleSecretAwareAnswer` /
 *     `BridgeAnswerReflection.sanitizedText`）：本对象只搬运它们算好的文本，不
 *     重新判定秘密性，也绝不把明文写进痕迹行。
 *
 * 单点纪律：本对象是**纯函数面**（无 IO、无 hub 依赖）；两条 IO 连接件
 * （hub 只读查询 `originOf`、落库 `persist`）也收在这里，唯一消费方是网关的
 * 三条作答腿（web 腿两条 + 桥腿一条）——落点判定散回调用点就会再次漂移。
 */
package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import nebflow.agent.SharedResources
import nebflow.actor.{AskOrigin, InteractionHubCommand}
import nebflow.shared.{NebflowLogger, UiMessage}

private[gateway] object AskAnswerPlacement:

  private val logger = NebflowLogger.forName("nebflow.gateway.askuser")

  /** 痕迹行里提问方标注的兜底文案（归属读数缺标注时用，不含任何作答内容）。 */
  val UnknownAskerLabel = "后台智能体"

  /** 代答痕迹行文案（唯一构造点；只含「谁问的」，永不含作答内容）。 */
  def traceText(askerLabel: String): String =
    val label = Option(askerLabel).map(_.trim).filter(_.nonEmpty).getOrElse(UnknownAskerLabel)
    s"已代答（提问会话：$label）"

  /**
   * 落库裁决结果。
   *
   * @param writes         (会话 id → 该会话要写入的行)，顺序即写序
   * @param delegated      true = 子代理/节点提问（全文进提问会话、根只有痕迹行）
   * @param ownerSessionId 作答**全文**的归属会话（航线/日志用）
   */
  final case class Plan(
    writes: List[(String, List[UiMessage])],
    delegated: Boolean,
    ownerSessionId: String
  )

  /**
   * 纯裁决：给定提问归属（可能未知）、帧内 sessionId、作答文本（已由上游完成秘密槽
   * 脱敏与附件拼接）⇒ 要写哪些行、写进哪些会话。
   *
   * @param frameSessionId 作答帧携带的 sessionId（= 卡片渲染/落盘所在会话，改前
   *                       唯一落点；缺省时调用方传卡片会话）
   * @param answerAttachments 随卡回传的已落盘附件清单（只随全文行落 —— 痕迹行是
   *                       「没有内容」的轻量记录，不带附件也能让历史卡渲染已作答）
   */
  def plan(
    origin: Option[AskOrigin],
    frameSessionId: String,
    answerText: String,
    requestId: String,
    timestamp: Long,
    answerAttachments: List[Json] = Nil
  ): Plan =
    val fullRow = UiMessage.askUserAnswer(answerText, requestId, timestamp, answerAttachments)
    origin match
      case Some(o) if !o.isSelfAsk && o.sourceSession.nonEmpty =>
        // ① 全文 → 提问会话；② 根只留痕迹行（answerOf 在 ⇒ 历史卡仍渲染已作答）。
        val traceRow = UiMessage.askUserAnswer(traceText(o.askerLabel), requestId, timestamp)
        val traceSession =
          if frameSessionId.nonEmpty then frameSessionId else o.rootSessionId
        val writes =
          if traceSession.nonEmpty && traceSession != o.sourceSession then
            List(o.sourceSession -> List(fullRow), traceSession -> List(traceRow))
          else List(o.sourceSession -> List(fullRow))
        Plan(writes, delegated = true, ownerSessionId = o.sourceSession)
      case _ =>
        // 自己问自己 / 归属未知 ⇒ 改前路径逐字不变（全文落帧内 sessionId）。
        Plan(List(frameSessionId -> List(fullRow)), delegated = false, ownerSessionId = frameSessionId)

  end plan

  /**
   * IO 连接件①：hub 只读归属查询（`AskOriginOf`）。hub 未装配 / 查询失败 ⇒
   * `None`（回落改前落库路径；与 `secretSlotsFor` 同款降级方向）。
   */
  def originOf(sharedResources: SharedResources, requestId: String): IO[Option[AskOrigin]] =
    if requestId.trim.isEmpty then IO.pure(None)
    else
      sharedResources.interactionHubRef.get.flatMap {
        case None => IO.pure(None)
        case Some(hub) =>
          hub
            .?[Option[AskOrigin]](reply => InteractionHubCommand.AskOriginOf(requestId, reply))
            .handleErrorWith { e =>
              logger.warn(
                s"ask-origin lookup failed for requestId=$requestId — falling back to the legacy answer row placement: ${e.getMessage}"
              ) *> IO.pure(None)
            }
      }

  /**
   * IO 连接件②：按裁决结果落库（逐会话 `appendUiMessages`，顺序保持）。
   * `delegated` 时留一行 info（可归因：全文落谁、痕迹落谁）——作答内容本身**不**进
   * 日志（与 `auditAnswer` 同一纪律：审计面已有独立脱敏口径）。
   */
  def persist(
    sessionStore: nebflow.core.SessionStore,
    requestId: String,
    p: Plan
  ): IO[Unit] =
    val writes = p.writes.traverse_ { case (sid, rows) => sessionStore.appendUiMessages(sid, rows) }
    if p.delegated then
      writes *> logger.info(
        s"askUserAnswer delegated: requestId=$requestId full answer → session=${p.ownerSessionId}, " +
          s"trace-only row → root (no answer text in the root session stream)"
      )
    else writes

end AskAnswerPlacement
