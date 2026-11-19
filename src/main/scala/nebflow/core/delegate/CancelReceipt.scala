package nebflow.core.delegate

import cats.effect.IO
import cats.syntax.all.*
import nebflow.core.SubAgentTaskStorePort
import nebflow.shared.NebflowLogger

import scala.concurrent.duration.*

/**
 * CancelReceipt —— **cancel 后回执确认**（R1 缝隙 A 修，2026-10-28 实施批 · 报告二
 * §1.3）：`AgentControlTool.doCancel` 监督者路径的原 fire-and-forget tell 改造的
 * 确认半边。
 *
 * == 机制 ==
 * 监督者处理 `AgentEvent.Cancelled` → `notifyParentAndStop("cancelled")` 的
 * `taskUpdate`（任务行翻 cancelled）是终态结算的**盘面证据**。本件在投递之后
 * 有界轮询该证据：窗内翻终态 = 确认（工具回执如实写 CONFIRMED）；超窗 = 未确认
 * （调用方降级兜底直落终态，不再回假成功文案）。
 *
 * == 关闭的缝隙 ==
 * 缝隙 A：`sup ! Cancelled` 是 mailbox offer（从不失败）——监督者已死 ⇒ 零写入
 * 零报错、工具却回成功文案（28 僵尸事故的缝隙之一）。本件把「发了」升级为
 * 「发了且结算落盘」，未结算显式可见。
 *
 * fail-soft 纪律：轮询只是读面（store 查询异常 = 视作未命中继续轮询，绝不抛出
 * 拖垮 cancel 主链）。
 */
object CancelReceipt:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.cancel-receipt")

  /**
   * 默认确认窗（毫秒）：notifyParentAndStop 正常结算 <1s；5s = 慢盘/繁忙 mailbox
   * 的宽裕上界，同时也是「监督者真死了」的最坏延迟代价（cancel 是低频操作）。
   * sys prop `nebflow.agentcontrol.cancelVerifyMs` 可调（测试/诊断旋钮）。
   */
  val DefaultVerifyBudgetMs: Long = 5000L

  def verifyBudgetMs: Long =
    sys.props.get("nebflow.agentcontrol.cancelVerifyMs").flatMap(v => scala.util.Try(v.toLong).toOption).getOrElse(DefaultVerifyBudgetMs)

  /**
   * 轮询任务行翻 `cancelled`（R1-A 确认判据）。返回 true = 窗内确认；
   * false = 超窗未确认（调用方降级兜底）。`pollMs` 缺省 250ms。
   */
  def awaitTerminal(store: SubAgentTaskStorePort, sessionId: String, budgetMs: Long, pollMs: Long = 250): IO[Boolean] =
    val deadline = System.currentTimeMillis() + budgetMs
    def attempt: IO[Boolean] =
      store
        .findByTaskId(sessionId)
        .map {
          case None    => false // 行缺席（尚未写/已被清）——继续轮询到超窗
          case Some(t) => t.status == "cancelled"
        }
        .handleErrorWith(e =>
          logger
            .warn(s"[cancel-receipt] task-row probe failed for '$sessionId' (treating as not-yet-settled): ${e.getMessage}")
            .as(false)
        )
    def go: IO[Boolean] =
      attempt.flatMap {
        case true  => IO.pure(true)
        case false =>
          if System.currentTimeMillis() >= deadline then IO.pure(false)
          else IO.sleep(pollMs.millis) >> go
      }
    go
end CancelReceipt
