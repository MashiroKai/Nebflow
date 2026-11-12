package nebflow.core.delegate

import cats.effect.IO
import nebflow.actor.{ActorRef, AgentCommand, AgentKind, AgentRecord}
import nebflow.shared.NebflowLogger

/**
 * DelegateNotify —— 通知改向通道与规则（S19①⑥ ＋ ⑬A 兜底；worktree-judge-fifo
 * 批 #1224 增补 §3.6）.
 *
 * 规则（本批就位）：flow 终态/状态异常的**通知投递对象＝创建者 delegate 本人**
 * （自己创建、自己管理），通道 = ImmediateInput 注入（与合并 reminder 同通道族）；
 * 状态另经终态状态槽入 DelegateRegistry——**双通道并存**。flow 面本身的
 * 接线在 N1（先有非阻塞 flow 才有「完成即通知」）；本批保证通道与规则两个构件：
 *
 *   - [[inject]]：对**任意活会话**的 turn 边界注入（ImmediateInput 命令直投——
 *     live 判据 = 统一注册表有行且非终态清理；投递失败/目标不 live = `false`，
 *     调用方自行走兜底）；
 *   - [[NotifyRule]]：投递对象规则的常量承载（N1 消费）。
 *
 * ⑬A（零改动面）：delegate 自身冻结沿父链 level+1 上送、父缺失跳级 P0 到用户
 * ——既有 AgentFrozen 升级链**原样兜底**，本件不建任何旁路上送面。
 * delegate 自身终态三态仍报创建它的 Nebula（原文「报 Nebula」腿不变——改向只
 * 适用于 delegate 所建 flow，N1 接线）；旧 per-project 引擎通知面（DispatchNotify/
 * 门集）不改向，随 #1229 退役（过渡期照旧）。
 */
object DelegateNotify:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.notify")

  /** 注入源标记（ImmediateInput.source）——归因 + 审计 grep 锚。 */
  val Source: String = "delegate-notify"

  /** 投递对象规则（S19①）：创建者 delegate 本人。N1 的 flow 接线消费本常量。 */
  object NotifyRule:
    val Target: String = "creator-delegate"
    val Channel: String = "immediate-input"

    /** 双通道并存（状态面 = 终态状态槽 → DelegateRegistry）。 */
    val StatusChannel: String = "delegate-report"
  end NotifyRule

  /**
   * Turn 边界注入（与合并 reminder 同通道族：ImmediateInput 命令直投活 actor；
   * idle 分支将其转为普通 UserInput——既有通道语义，零新机制）。
   *
   * live 判据 = 统一注册表有行且 kind 属于 delegate/flow/session 家族（Root/Team
   * 的行也可投——本通道不做身份窄化，规则面在调用方）。投递失败/目标不 live =
   * `false`，调用方自行走兜底（⑬A 既有升级链）。
   *
   * @return true = the receiver intake is durable and actor notification was attempted;
   *         this is not confirmation of business processing. False means the target is not live.
   */
  def inject(
    agentRegistry: cats.effect.Ref[IO, Map[String, AgentRecord]],
    targetSessionId: String,
    text: String,
    identity: Option[String] = None
  ): IO[Boolean] =
    agentRegistry.get.flatMap { agents =>
      agents.get(targetSessionId) match
        case None =>
          logger.info(s"delegate-notify: target '$targetSessionId' not live — delivery refused (caller falls back)")
          IO.pure(false)
        case Some(rec: AgentRecord) =>
          val ref: ActorRef[AgentCommand] = rec.ref
          // 🔴 `ref ! msg` 是 IO[Unit]（LocalActorRef.! = queue.offer），**必须被执行**。
          // 改前此处直接丢弃该 IO 并 `IO.pure(true)` ⇒ 通知从不入队，但调用方收到
          // 「已投达」的肯定答复（P0 批 D1 实测：`dispatch-notify-delivery
          // outcome=delivered` 落了审计行，创建者 delegate 一条都没收到——
          // 「通知说发了、其实没发」的第二形态）。`*>` 串接即真实投递，返回语义
          // 不变（true = 注册表有行，与改前契约一致）。
          val input = AgentCommand.ImmediateInput(
            text = text, source = Some(Source), eventType = Some("flow-notify"), fromUser = false
          )
          val key = identity.getOrElse(nebflow.core.mail.ProducerInput.identity(targetSessionId, text))
          nebflow.core.mail.ProducerInput.persist(targetSessionId, input, key)
            .flatMap(prepared => (ref ! prepared).as(true))
    }

end DelegateNotify
