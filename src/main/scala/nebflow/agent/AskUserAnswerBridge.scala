package nebflow.agent

import cats.effect.IO
import nebflow.actor.{ActorPath, ActorRef}
import nebflow.core.tools.{AskUserQuestionTool, ToolContext}
import nebflow.core.{AskItem, NebflowLogger}

import scala.concurrent.duration.*

/**
 * AskUserQuestion **非阻塞模式的回投桥**（工具面按角色分化批 B5/L9，2026-09-13）。
 *
 * 职责（规格 §2.2 D3/D4）：非阻塞模式下，hub 槽位的 `reply` 不指向「等着工具结果的
 * 工具 fiber 回执」，而指向本桥。用户答复 → hub `complete`（`InteractionHub` 零改动，
 * `AskUserReply(Option[ActorRef[List[String]]])` 本就是任意 ref）→ `bridge ! answers`
 * → 格式化（与阻塞模式**同一实现** `AskUserQuestionTool.formatAnswer`）→ 向请求方
 * **会话本体**发 `AgentCommand.ImmediateInput(text, fromUser = true)`——会话 Idle 则
 * 唤醒新 turn，在跑则进 `pendingUserInputs`，在**下一个 turn 边界**注入（D5：答案
 * 不是工具结果，是**用户输入**）。
 *
 * ## 为什么是「一次性引用」而不是 spawn 一个 Actor（设计 §9 未证项 5 的显式处理）
 *
 * 设计建议落点写着「一次性桥接 Actor（`Behaviors.setup` → 收答案 → `Behaviors.stopped`）」，
 * 同时把「**桥接 Actor 若在答复前已停止 ⇒ 答案进 dead letters 静默丢**」列为未证项，
 * 要求实施批给显式处理。本实现直接**消掉该风险面**：
 *
 *  - 本桥**不是 actor**，没有 mailbox、没有生命周期、不占线程：它就是一个
 *    `ActorRef[List[String]]` 实现（树内先例 `SendConfirm.oneShotReply:188-193`，
 *    同款「一次性引用」形态）。⇒ 「桥在答复前已停止」这种状态**不存在**——
 *    只要 hub 槽位还在（槽位生命周期 = 规格 §3.4 D6 复用），桥就可达；
 *    槽位被撤（答复 / `CleanupForSession` 源会话死亡 / `CloseRequest`）时，
 *    对它的引用自然被丢弃，**零泄漏**（一个 Actor 反而会常驻到答复为止）。
 *  - 残余风险只剩一种：**目标会话本体已消失**而槽位尚未回收。此处**显式处理**
 *    （不落 dead letters 静默）：交付前查一次注册表，行不存在 ⇒ WARN 明说
 *    「答案到达了一个已不存在的会话」，并放弃投递。**这是显式日志面，不是静默面。**
 *
 * 线程/IO 归属：本对象不自己跑 IO——`!` 返回的 IO 由**调用方**（hub 的
 * `handleAnswered` fiber）执行。真正的 Actor 层（
 * `AgentActor`）仍是唯一的状态机；本桥只做「一条消息 → 一条注入」的搬运，符合
 * 「Keep your Actors out of your cats-effect」铁律。
 */
object AskUserAnswerBridge:

  private val logger = NebflowLogger.forName("nebflow.agent.askuser")

  /**
   * 桥的唯一构造点。`requestId` 进 path（日志/诊断可归因），`items` 用于把
   * 答复格式化成模型可读文本（与阻塞模式**同一** `formatAnswer`）。
   */
  def ref(
    target: ActorRef[AgentCommand],
    items: List[AskItem],
    requestId: String,
    ctx: ToolContext
  ): ActorRef[List[String]] =
    new ActorRef[List[String]]:
      val path: ActorPath = ActorPath("__askuser-bridge", List(requestId))

      def !(answers: List[String]): IO[Unit] =
        deliver(target, items, requestId, answers, ctx)

      def ?[R](makeMsg: ActorRef[R] => List[String], t: Option[FiniteDuration]): IO[R] =
        IO.raiseError(
          new UnsupportedOperationException(
            "the one-shot askUser answer bridge does not accept asks"
          )
        )

  /**
   * 答复 → 注入（D4）。`fromUser = true`（裁定 T6=(a)：真人点了卡）——该标志使
   * `AgentActor.injectionSourceFor` 把 `source` 折成 None（呈现为普通 user 气泡）
   * ⇒ 不需要前端登记面、零前端改动。
   */
  private def deliver(
    target: ActorRef[AgentCommand],
    items: List[AskItem],
    requestId: String,
    answers: List[String],
    ctx: ToolContext
  ): IO[Unit] =
    val text = AskUserQuestionTool.formatAnswer(items, answers)
    sessionAlive(ctx).flatMap {
      case false =>
        logger.warn(
          s"askUser answer bridge: requestId=$requestId — the requesting session is gone " +
            s"(sessionId=${ctx.sessionId.getOrElse("<unknown>")}); answer NOT delivered"
        )
      case true =>
        logger.info(
          s"askUser answer bridge: requestId=$requestId → injected as a user message into " +
            s"session=${ctx.sessionId.getOrElse("<unknown>")} (${answers.size} answer slot(s))"
        ) *>
          (target ! AgentCommand.ImmediateInput(
            text = text,
            source = Some(AskMode.AnswerSource),
            eventType = Some(AskMode.AnswerSource),
            fromUser = true
          ))
    }

  end deliver

  /**
   * 目标会话是否还在（注册表行存在 = 活着）。无 sharedResources / 无 sessionId
   * （spec harness、非 agent 上下文）⇒ 不阻拦投递（保持可独立单测），但那条
   * 路径在生产里不存在（工具只在 agent 会话里被调用）。
   */
  private def sessionAlive(ctx: ToolContext): IO[Boolean] =
    (ctx.sharedResources, ctx.sessionId) match
      case (Some(res), Some(sid)) => res.agentRegistry.get.map(_.contains(sid))
      case _ => IO.pure(true)

end AskUserAnswerBridge
