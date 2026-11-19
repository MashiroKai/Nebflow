/* askuser-remote P1 反射补口（2026-10-09，feishu-answer-card-flip）：
 * 桥面作答（飞书卡片点击 / 微信编号回复）的「桌面端两面」落点。
 *
 * 改前桥面作答只进 hub + 会话注入（AskUserAnswerBridge → ImmediateInput），桌面端
 * 两个面都收不到：
 *   ① `.ui.json` 无作答行 ⇒ 刷新后历史卡永远渲染「未答」（persistence.js 按
 *      `answerOf` 行数据驱动取值）；
 *   ② 无关卡帧 ⇒ 活卡不翻面——渠道答了、桌面零动静（2026-10-09 12:36 用户报单
 *      「我在飞书回答了问题，但是好像前端的问题卡片没有做相应的变化」的根因；
 *      10:20:31 飞书作答在册可证：audit event=answer + askUser answer bridge 注入，
 *      而 `.ui.json` 无 answerOf 行、12:36:22 web 端 askUserCancel 对同一 rid 得
 *      hub `unknown`——槽位早已消费、界面从未跟上）。
 *
 * web 腿（WsSessionChatHandlers.handleAskUserAnswer）两面都做（appendUiMessages +
 * 前端自翻），此处对齐：
 *   ① 作答行：同款单一构造点 `UiMessage.askUserAnswer`（answerOf = requestId）⇒
 *      历史卡渲染「已作答」（chat.js renderAskUserHistory 按 answerOf 取值）；
 *   ② 翻面帧 `askUserAnsweredRemote {type, sessionId, requestId, answerText}` ⇒
 *      前端既有 `closeAskUserCard`（requestId 寻址、已答幂等跳过）以**作答原文**
 *      为锁定文案关活卡——与卡片自身确认路径同一终态（`-> <answer>`）。
 *
 * 🔴 秘密槽脱敏（askuser-secret 批口径，web 腿 handleSecretAwareAnswer rowLines
 * 同款）：`secretIdxs` 由网关经 hub `SecretSlotsOf` 查得（hub 是槽位唯一权威）。
 * 秘密槽**恒空**（渠道对 secret 题 fail-closed，合法桥面载荷的秘密槽必为空串）；
 * 秘密槽出现非空明文 = 泄漏企图（hub 会拒收整卡）⇒ 行/帧一律落**拒收文案**、
 * 零值——绝不把明文落进 `.ui.json` 或广播给所有 WS 客户端。
 *
 * 本对象是纯函数面（无 IO、无网络）：连接件（落库 / wsHub 广播）在
 * `WebSocketRoutes.forwardBridgeInteractionAnswer`——桥面作答的单一汇聚点。
 * 非 askUser 形载荷（无 `answers` 键，如许可类 `{approved:…}`）⇒ 双 None：许可卡
 * 无此反射面，改前行为逐字不变。
 */
package nebflow.gateway

import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.UiMessage

private[gateway] object BridgeAnswerReflection:

  /** askUser 形判据单点：载荷携带 `answers` 数组（桥腿三条作答路径的统一形态）。 */
  def answersOf(payload: Json): Option[List[String]] =
    payload.hcursor.downField("answers").as[List[String]].toOption

  /** 秘密槽明文泄漏判据（hub `secretValueInPlainAnswers` 的网关侧同构，只用于
    * 行/帧脱敏方向）：任一秘密槽非空 ⇒ 整卡按拒收文案落盘/广播。合法回执占位
    * （`(secret` 前缀，hub `complete` 出站形态）不算明文。 */
  def plainLeakInSecretSlots(answers: List[String], secretIdxs: Set[Int]): Boolean =
    secretIdxs.exists(i => answers.lift(i).exists(a => a.nonEmpty && !a.startsWith("(secret")))

  /** 拒收文案（= web 腿 handleSecretAwareAnswer rowLines 的明文拒收行逐字同源）。 */
  val SecretLeakRefusedLine = "该题为安全输入题：明文作答未接收——请刷新页面后在桌面面板作答"

  /** 行/帧共用的脱敏文本：秘密槽置空；无泄漏 ⇒ 其余槽 `'\n'` 连接（= web 腿
    * handleAskUserAnswer :86 的 `answers.mkString("\n")` 同构）；有泄漏 ⇒ 拒收文案
    * （值不落任何面）。 */
  def sanitizedText(answers: List[String], secretIdxs: Set[Int]): String =
    if plainLeakInSecretSlots(answers, secretIdxs) then SecretLeakRefusedLine
    else
      answers.indices
        .map(i => if secretIdxs.contains(i) then "" else answers.lift(i).getOrElse(""))
        .mkString("\n")

  /** ① 落库行：web 腿同款单一构造点。空白 requestId ⇒ answerOf 不标记（构造点既有
    * 语义，AskUserAnswerPersistSpec ①）。非 askUser 形 ⇒ None。 */
  def rowFor(requestId: String, payload: Json, secretIdxs: Set[Int]): Option[UiMessage.User] =
    answersOf(payload).map { ans =>
      UiMessage.askUserAnswer(sanitizedText(ans, secretIdxs), requestId, System.currentTimeMillis())
    }

  /** ② 翻面帧：前端 `onMessage('askUserAnsweredRemote')` → closeAskUserCard +
    * removePendingAsk。`answerText` = 脱敏文本（活卡锁定行 = 刷新后历史卡作答行，
    * 逐字一致；泄漏形态下同为拒收文案）。非 askUser 形 ⇒ None（不发帧）。 */
  def frame(sessionId: String, requestId: String, payload: Json, secretIdxs: Set[Int]): Option[Json] =
    answersOf(payload).map { ans =>
      Json.obj(
        "type" -> "askUserAnsweredRemote".asJson,
        "sessionId" -> sessionId.asJson,
        "requestId" -> requestId.asJson,
        "answerText" -> sanitizedText(ans, secretIdxs).asJson
      )
    }

end BridgeAnswerReflection
