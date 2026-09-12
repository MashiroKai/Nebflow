package nebflow.agent

import cats.effect.{Deferred, IO}
import nebflow.actor.{ActorPath, ActorRef}
import nebflow.core.{AskItem, AskOption, NebflowLogger}
import nebflow.core.tools.ToolContext

import scala.concurrent.duration.*

/**
 * 出站代发动作的**用户确认**（#147 接线段，2026-09-12）。
 *
 * WHY（事实锚：作者 2026-09-11 裁定 U-5 / 方案件 §1）：`FriendService` 的三档
 * 权限里 `ask` 分支所需的确认链**全仓未接线** —— `askConfirm` 只有默认值
 * `None`，`GatewayMain` 经 `NeblinkWiring.friendService` 也没传实现 ⇒ `ask`
 * 档一调即 `Left("ask mode requires a confirmation callback (not wired)")`。
 * 本对象即那条缺失的链路：**出确认请求（AskUser 卡）→ 收确认/拒绝 → 据此投递
 * 或不投递**。
 *
 * 为什么确认卡必须落在**提问会话的窗口**（本对象取 `ToolContext` 里的会话身份，
 * 而不是在装配缝上做一个「无会话」的广播确认）：
 *  - 前端对 `askUser` 帧的渲染入口是「按 `msg.sessionId` 找 view」
 *    （`web/js/main.js:1321`；非活动会话走持久化分支，需 `sid` 非空）；
 *    `sessionId` 为空的帧既无 view 也无可持久化会话 ⇒ **直接丢弃** —— 与前端的
 *    `askPermission` 不同，`askPermission` 有 fallback 全局 toast 通道
 *    （`main.js:1456-1465`，F4 #433），`askUser` **没有**该通道。
 *  - InteractionHub 的渲染目标 = 请求里的 `rootSessionId` 在 `rootWsSend` 里存在
 *    （`InteractionHub.scala:127-168`）；无改派目标时只能 fallback 到其它已注册
 *    root（F4），而 fallback 帧带 `fallback: true` ⇒ 前端 askUser 分支不认。
 *  - 结论：确认卡的可渲染前提是**一个真实会话 id**；boot 期的装配缝不知道任何
 *    会话 ⇒ 装配缝只承担「无人接交互面时 fail-closed」的默认语义，真实交互实现
 *    由唯一持 `ToolContext` 的调用侧（`SendMessage` 工具）按次注入。
 *
 * 语义（三条判据的落点，全部 fail-closed）：
 *  - 批准 = 卡片答复**逐字等于** `ApproveLabel`；其它一切（拒绝标签、Esc/取消的
 *    `__cancelled__`、输入框直通文本、空答复）**一律不投递** —— 不存在「静默当
 *    批准」的路径。
 *  - 无交互面 / 无会话绑定 / hub 未起 ⇒ `Unavailable`（显式失败，不发送）。
 *  - 窗口内未答复 ⇒ `TimedOut`（显式失败，不发送）**并撤回卡片**
 *    （`InteractionHubCommand.CloseRequest`）——不留下用户点得动、点了也没用的
 *    僵尸卡（本仓「错误路径折成静默成功」缺陷族的对治）。
 *
 * 未做（申报）：不解 WaitingForUser 标注（AgentActor.AskUser 分支的副产品）——
 * 等待上限 = `DefaultTimeout`（60s）≪ TaskStuckWatcher 阈值（10min），窗口内不可能
 * 触发 stuck 兜底；见本节点报告「未尽事项」。
 */
object SendConfirm:

  private val logger = NebflowLogger.forName("nebflow.agent.sendconfirm")

  /** 批准标签（**唯一**放行信号）。 */
  val ApproveLabel = "允许发送"

  /** 拒绝标签（展示用；非批准值一律不投递，故它不是唯一的不放行信号）。 */
  val DeclineLabel = "拒绝"

  /** 确认等待上限 = 60s（`FriendService` 既有口径「ask：每次弹确认（60s 超时=拒绝）」
    * 的现代表达：超时 = 不发送，且**可判定**为一等错误而非静默拒绝）。 */
  val DefaultTimeout: FiniteDuration = 60.seconds

  /** 超时可注入（规格/冒烟用；生产缺省 60s）。每次调用读取 —— 测试可临时覆写。 */
  val TimeoutProperty = "nebflow.sendMessage.confirmTimeoutMs"

  def timeout: FiniteDuration =
    Option(System.getProperty(TimeoutProperty))
      .flatMap(_.toLongOption)
      .filter(_ > 0)
      .map(_.millis)
      .getOrElse(DefaultTimeout)

  /** 确认链不可用（无交互面 / 无会话绑定 / hub 未起）。fail-closed。 */
  final class Unavailable(detail: String)
      extends Exception(s"no interactive confirmation surface: $detail")

  /** 窗口内未答复。fail-closed（不发送），卡片已撤回。 */
  final class TimedOut(requestId: String, waited: FiniteDuration)
      extends Exception(s"confirmation timed out after ${waited.toMillis}ms (requestId=$requestId)")

  /** 装配缝默认实现（`GatewayMain` → `NeblinkWiring.friendService(askConfirm = …)`）：
    * 调用侧没有附加交互面时**显式失败**。存在的意义 = 装配缝上不留 `None`
    * （`None` 表达的是「没接线」，是内部接线缺口；有实现但无交互面表达的是
    * 「这一次没有可问的人」——两种条件的可判定文案与归因不同）。 */
  val NoInteractiveSurface: String => IO[Boolean] = _ =>
    IO.raiseError(new Unavailable("the caller attached no session context (assembly-seam default)"))

  /** 调用侧真实实现（唯一交互实现）。`None` = 该 ctx 没有任何交互面（如
    * REST 直调 / spec harness）⇒ 由服务层回落装配缝默认值（同款 fail-closed）。 */
  def forContext(
    ctx: ToolContext,
    recipientLabel: String,
    wait: FiniteDuration = timeout
  ): Option[String => IO[Boolean]] =
    ctx.sharedResources.map(res => (body: String) => ask(ctx, res, recipientLabel, body, wait))

  private def ask(
    ctx: ToolContext,
    res: SharedResources,
    recipientLabel: String,
    body: String,
    wait: FiniteDuration
  ): IO[Boolean] =
    val rootSid = ctx.rootSessionId.filter(_.nonEmpty).orElse(ctx.sessionId.filter(_.nonEmpty)).getOrElse("")
    val sourceAgent = ctx.agentDef.map(_.name).getOrElse("agent")
    val sourceSession = ctx.sessionId.getOrElse(rootSid)
    if rootSid.isEmpty then IO.raiseError(new Unavailable("this call carries no session id to render a card in"))
    else
      for
        hubOpt <- res.interactionHubRef.get
        hub <- hubOpt match
          case Some(h) => IO.pure(h)
          case None    => IO.raiseError(new Unavailable("InteractionHub is not spawned (headless / early boot)"))
        requestId = java.util.UUID.randomUUID().toString.take(8)
        slot <- Deferred[IO, List[String]]
        _ <- logger.info(
          s"confirm requested requestId=$requestId root=$rootSid sourceAgent=$sourceAgent " +
            s"recipient=$recipientLabel waitMs=${wait.toMillis}"
        )
        _ <- hub ! InteractionHubCommand.Request(
          InteractionRequest(
            requestId = requestId,
            kind = InteractionKind.AskUser,
            // 卡面唯一实现点是 `AgentActor.buildAskUserJson`（#380 字节契约 + spec
            // 钉死）——本对象**不**自建第二份 items 编码。
            payload = AgentActor.buildAskUserJson(
              Some(rootSid),
              sourceAgent,
              List(confirmItem(recipientLabel, body)),
              Some(sourceAgent),
              Some(sourceSession)
            ),
            reply = InteractionReply.AskUserReply(Some(oneShotReply(slot, requestId))),
            rootSessionId = rootSid,
            sourceAgent = sourceAgent,
            sourceSession = sourceSession
          )
        )
        answers <- slot.get.timeoutTo(wait, retire(res, requestId, wait))
        approved = answers.headOption.exists(_.trim == ApproveLabel)
        _ <- logger.info(
          s"confirm answered requestId=$requestId approved=$approved (answer=${answers.headOption.getOrElse("")})"
        )
      yield approved

  /** 确认卡（单选两选项 + 禁自由文本：放行信号必须是**点击**，不是输入）。 */
  private def confirmItem(recipientLabel: String, body: String): AskItem =
    val shown = if body.length > 400 then body.take(400) + "…" else body
    AskItem(
      question = s"以你的身份给 $recipientLabel 发送这条 NebLink 消息？\n\n$shown",
      options = List(
        AskOption(ApproveLabel, Some("收方看到的发送者是你本人")),
        AskOption(DeclineLabel, Some("什么都不发送"))
      ),
      allowOther = false
    )

  /** 超时路径：撤回卡片（否则用户点得动、点了也没用 —— 僵尸卡）+ 上抛可判定错误。 */
  private def retire(res: SharedResources, requestId: String, wait: FiniteDuration): IO[List[String]] =
    logger.warn(
      s"confirm timed out after ${wait.toMillis}ms requestId=$requestId — message NOT sent; retiring the card"
    ) *>
      res.interactionHubRef.get
        .flatMap {
          case Some(hub) => (hub ! InteractionHubCommand.CloseRequest(requestId)).void.handleErrorWith(_ => IO.unit)
          case None      => IO.unit
        }
        .handleErrorWith(_ => IO.unit) *>
      IO.raiseError[List[String]](new TimedOut(requestId, wait))

  /** 一次性回复引用（`InteractionReply.AskUserReply` 的目标）。与 `ActorRef.?`
    * 内部的临时引用同形：只接一次答复，不支持嵌套 ask。 */
  private def oneShotReply(slot: Deferred[IO, List[String]], requestId: String): ActorRef[List[String]] =
    new ActorRef[List[String]]:
      val path: ActorPath = ActorPath("__sendconfirm", List(requestId))
      def !(msg: List[String]): IO[Unit] = slot.complete(msg).void
      def ?[R](makeMsg: ActorRef[R] => List[String], t: Option[FiniteDuration]): IO[R] =
        IO.raiseError(new UnsupportedOperationException("the one-shot confirm reply ref does not accept asks"))

end SendConfirm
