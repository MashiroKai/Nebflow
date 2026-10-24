package nebflow.core.delegate

import cats.effect.IO
import nebflow.actor.*
import nebflow.shared.NebflowLogger

/**
 * DelegateNotify —— 通知改向通道与规则（S19①⑥ ＋ ⑬A 兜底；worktree-judge-fifo
 * 批 #1224 增补 §3.6）.
 *
 * 规则（本批就位）：flow 终态/状态异常的**通知投递对象＝创建者 delegate 本人**
 * （自己创建、自己管理），通道 = ImmediateInput 注入（与合并 reminder 同通道族）；
 * 状态另经 delegate_report 入 DelegateRegistry——**双通道并存**。flow 面本身的
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

    /** 双通道并存（状态面 = delegate_report → DelegateRegistry）。 */
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
   * @return true = 已投递（actor 收单）；false = 目标不 live（注册表无行）。
   */
  def inject(
    agentRegistry: cats.effect.Ref[IO, Map[String, AgentRecord]],
    targetSessionId: String,
    text: String
  ): IO[Boolean] =
    agentRegistry.get.flatMap { agents =>
      agents.get(targetSessionId) match
        case None =>
          logger.info(s"delegate-notify: target '$targetSessionId' not live — delivery refused (caller falls back)")
          IO.pure(false)
        case Some(rec: AgentRecord) =>
          val ref: ActorRef[AgentCommand] = rec.ref
          ref ! AgentCommand.ImmediateInput(
            text = text,
            source = Some(Source),
            eventType = Some("flow-notify"),
            fromUser = false
          )
          IO.pure(true)
    }

end DelegateNotify
