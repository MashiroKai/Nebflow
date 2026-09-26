package nebflow.agent

import cats.effect.unsafe.implicits.global
import cats.effect.{Deferred, IO, IOLocal}
import nebflow.actor.{ActorPath, ActorRef}
import nebflow.core.tools.ToolContext
import nebflow.core.{AskItem, AskOption}
import nebflow.shared.NebflowLogger

import scala.concurrent.duration.*

/**
 * 出站代发动作的**用户确认**（#147 接线段，2026-09-12）—— 全仓唯一实现。
 *
 * WHY（事实锚：作者 2026-09-11 裁定 U-5 / 方案件 §1）：`FriendService` 三档权限
 * 里 `ask` 支所需的确认链**全仓未接线** —— `askConfirm` 只有默认值 `None`，
 * `GatewayMain` 经 `NeblinkWiring.friendService` 也没传实现 ⇒ `ask` 档一调即
 * `Left("ask mode requires a confirmation callback (not wired)")`。本对象即那条
 * 缺失的链路：**出确认请求（AskUser 卡）→ 收确认/拒绝 → 据此投递或不投递**。
 *
 * 装配形态（为什么长这样）：
 *  - **装配缝值 = 本对象的 `production`**（`GatewayMain` → `NeblinkWiring.friendService
 *    (askConfirm = Some(production))`）。它就是运行时真正执行的那个函数 ——
 *    不存在「接了但走不到」的第二条实现（反对照：若装配缝传一个 `None` 桩、
 *    真实实现另走参数，则装配缝的值在生产里从不执行）。
 *  - **会话靶（谁在问）由调用侧按次挂上**：`FriendService` 是进程级单例，
 *    boot 期装配缝不知道任何会话；而确认卡的可渲染前提是**一个真实会话 id**
 *    （前端 `askUser` 帧按 `msg.sessionId` 找 view，`main.js:1321`；`askUser`
 *    没有 `askPermission` 那种 fallback 全局 toast 通道，F4 #433 只覆盖后者；
 *    hub 渲染目标必须是已注册的 root，`InteractionHub.scala:127-168`）。
 *    唯一持会话身份的地方 = `ToolContext`（`FriendMessageTool.sendTo`）⇒ 它用
 *    `locally` 把靶挂进 **fiber-local**（`IOLocal`：并发会话零串台，不是全局
 *    可变注册表），`production` 在本次调用内读它。
 *  - 依赖方向：本文件在 `nebflow.agent`；`FriendService`/`NeblinkWiring` 只见
 *    `String => IO[Boolean]`（**不**导入 `nebflow.agent` —— 无包级循环依赖）。
 *
 * 语义（三条都 fail-closed，不存在静默路径）：
 *  - 批准 = 卡片答复**逐字等于** `ApproveLabel`；其它一切（拒绝标签、Esc/取消的
 *    `__cancelled__`、输入框直通文本、空答复）一律不投递 —— 无「静默当批准」路径。
 *  - 无靶 / 无交互面（hub 未起）/ 无会话 id ⇒ `Unavailable`（显式失败，不发送）。
 *  - 窗口内未答复 ⇒ `TimedOut`（显式失败，不发送）**并撤回卡片**
 *    （`InteractionHubCommand.CloseRequest`）—— 不留用户点得动、点了也没用的
 *    僵尸卡（本仓「错误路径折成静默成功」缺陷族的对治）。
 *
 * 未做（申报）：不解 WaitingForUser 标注（`AgentActor.AskUser` 分支的副产品）——
 * 等待上限 60s ≪ TaskStuckWatcher 阈值 10min，窗口内不可能触发 stuck 兜底。
 */
object SendConfirm:

  private val logger = NebflowLogger.forName("nebflow.agent.sendconfirm")

  /** 批准标签（**唯一**放行信号）。 */
  val ApproveLabel = "允许发送"

  /** 拒绝标签（展示用；非批准值一律不投递，故它不是唯一的不放行信号）。 */
  val DeclineLabel = "拒绝"

  /**
   * 确认等待上限 = 60s（`FriendService` 既有口径「ask：每次弹确认（60s 超时=拒绝）」
   * 的现代表达：超时 = 不发送，且**可判定**为一等错误而非静默拒绝）。
   */
  val DefaultTimeout: FiniteDuration = 60.seconds

  /** 超时可注入（规格/冒烟用；生产缺省 60s）。每次读取 —— 测试可临时覆写。 */
  val TimeoutProperty = "nebflow.sendMessage.confirmTimeoutMs"

  def timeout: FiniteDuration =
    Option(System.getProperty(TimeoutProperty))
      .flatMap(_.toLongOption)
      .filter(_ > 0)
      .map(_.millis)
      .getOrElse(DefaultTimeout)

  /**
   * 一次代发的交互靶：「谁在问（会话/agent）+ 问谁（收件人标签）+ 等多久」。
   * 字段名 `waitFor`（**非** `wait`）：`wait` 是 `java.lang.Object.wait` 的
   * final 成员，case class 字段同名即编译报 E164。
   */
  final case class AskTarget(ctx: ToolContext, recipientLabel: String, waitFor: FiniteDuration)

  /** 确认链不可用（无靶 / hub 未起 / 无会话 id）。fail-closed。 */
  final class Unavailable(detail: String) extends Exception(s"no interactive confirmation surface: $detail")

  /** 窗口内未答复。fail-closed（不发送），卡片已撤回。 */
  final class TimedOut(requestId: String, waited: FiniteDuration)
      extends Exception(s"confirmation timed out after ${waited.toMillis}ms (requestId=$requestId)")

  /**
   * fiber-local 靶（`IOLocal`：CE 标准原语，fiber 作用域 ⇒ 多会话并发零串台）。
   * 对象初始化期创建（`LlmLogWriter` 的 `Queue.bounded(...).unsafeRunSync()` 同族先例）。
   */
  private val target: IOLocal[Option[AskTarget]] =
    IOLocal[Option[AskTarget]](None).unsafeRunSync()

  /** 调用侧唯一入口：本次代发期间挂靶（退出即还原上一值，可嵌套）。 */
  def locally[A](t: AskTarget)(io: IO[A]): IO[A] =
    target.getAndSet(Some(t)).bracket(_ => io)(prev => target.set(prev).void)

  /** 调用侧构造靶（`waitFor` 缺省取 `timeout`）。 */
  def targetFor(ctx: ToolContext, recipientLabel: String, waitFor: FiniteDuration = timeout): AskTarget =
    AskTarget(ctx, recipientLabel, waitFor)

  /**
   * **装配缝实现**（`GatewayMain` 经 `NeblinkWiring.friendService` 接的就是它，
   * 生产运行时执行的就是它）。无靶（调用侧未进 `locally`，如 REST 直调/harness）
   * ⇒ 显式 fail-closed。
   */
  val production: String => IO[Boolean] =
    body =>
      target.get.flatMap {
        case Some(t) => ask(t, body)
        case None =>
          IO.raiseError(new Unavailable("this call carries no session context (no ask target attached)"))
      }

  // ============================================================
  // 卡面 → 投递 → 等答复
  // ============================================================

  private def ask(t: AskTarget, body: String): IO[Boolean] =
    val ctx = t.ctx
    ctx.sharedResources match
      case None =>
        IO.raiseError(new Unavailable("this call carries no session context to render a card in"))
      case Some(res) =>
        val rootSid = ctx.rootSessionId.filter(_.nonEmpty).orElse(ctx.sessionId.filter(_.nonEmpty)).getOrElse("")
        val sourceAgent = ctx.agentDef.map(_.name).getOrElse("agent")
        val sourceSession = ctx.sessionId.getOrElse(rootSid)
        if rootSid.isEmpty then IO.raiseError(new Unavailable("this call carries no session id to render a card in"))
        else
          for
            hubOpt <- res.interactionHubRef.get
            hub <- hubOpt match
              case Some(h) => IO.pure(h)
              case None => IO.raiseError(new Unavailable("InteractionHub is not spawned (headless / early boot)"))
            // #250 第⑤项：requestId 熵强化（单点生成器，作用域 confirm-）
            requestId = InteractionRequestId.forSendConfirm()
            slot <- Deferred[IO, List[String]]
            _ <- logger.info(
              s"confirm requested requestId=$requestId root=$rootSid sourceAgent=$sourceAgent " +
                s"recipient=${t.recipientLabel} waitMs=${t.waitFor.toMillis}"
            )
            _ <- hub ! InteractionHubCommand.Request(
              InteractionRequest(
                requestId = requestId,
                kind = InteractionKind.AskUser,
                // 卡面唯一实现点 = `AgentActor.buildAskUserJson`（#380 字节契约 + spec 钉死）
                // —— 本对象**不**自建第二份 items 编码。
                payload = AgentActor.buildAskUserJson(
                  Some(rootSid),
                  sourceAgent,
                  List(confirmItem(t.recipientLabel, body)),
                  Some(sourceAgent),
                  Some(sourceSession)
                ),
                reply = InteractionReply.AskUserReply(Some(oneShotReply(slot, requestId))),
                rootSessionId = rootSid,
                sourceAgent = sourceAgent,
                sourceSession = sourceSession
              )
            )
            answers <- slot.get.timeoutTo(t.waitFor, retire(res, requestId, t.waitFor))
            approved = answers.headOption.exists(_.trim == ApproveLabel)
            _ <- logger.info(
              s"confirm answered requestId=$requestId approved=$approved " +
                s"(answer=${answers.headOption.getOrElse("")})"
            )
          yield approved

        end if

    end match

  end ask

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

  /** 超时路径：撤回卡片（否则用户点得动、点了也没用 = 僵尸卡）+ 上抛可判定错误。 */
  private def retire(res: SharedResources, requestId: String, wait: FiniteDuration): IO[List[String]] =
    logger.warn(
      s"confirm timed out after ${wait.toMillis}ms requestId=$requestId — message NOT sent; retiring the card"
    ) *>
      res.interactionHubRef.get
        .flatMap {
          case Some(hub) => (hub ! InteractionHubCommand.CloseRequest(requestId)).void.handleErrorWith(_ => IO.unit)
          case None => IO.unit
        }
        .handleErrorWith(_ => IO.unit) *>
      IO.raiseError[List[String]](new TimedOut(requestId, wait))

  /**
   * 一次性回复引用（`InteractionReply.AskUserReply` 的目标）。与 `ActorRef.?` 内部的
   * 临时引用同形：只接一次答复，不支持嵌套 ask。
   */
  private def oneShotReply(slot: Deferred[IO, List[String]], requestId: String): ActorRef[List[String]] =
    new ActorRef[List[String]]:
      val path: ActorPath = ActorPath("__sendconfirm", List(requestId))
      def !(msg: List[String]): IO[Unit] = slot.complete(msg).void
      def ?[R](makeMsg: ActorRef[R] => List[String], t: Option[FiniteDuration]): IO[R] =
        IO.raiseError(new UnsupportedOperationException("the one-shot confirm reply ref does not accept asks"))

end SendConfirm
