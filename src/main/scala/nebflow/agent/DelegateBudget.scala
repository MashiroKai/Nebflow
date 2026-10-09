package nebflow.agent

import cats.effect.IO
import nebflow.shared.NebflowLogger

import scala.concurrent.duration.*

/**
 * DelegateBudget —（2026-10-09 作者裁定拆除）原 R11 第 4 层 wall-clock 预算的**墓碑壳**。
 *
 * 原机制：delegate 内核会话装配 3600s wall-clock 预算（`register` 起 `IO.race(sleep,
 * signal)` 计时环；人机等待期 pause/resume 扣减；到点经既有 cancel 链投
 * `AgentEvent.Cancelled(reason="timeout")` 强杀；合并窗停靠重挂
 * `"merge-window-timeout"` 新窗口）。
 *
 * 拆除理由（作者原话裁定 2026-10-09：「这种限制不允许加，参考我们之前对 node
 * 节点的设计，我们从来不加超时限制。」）：时间预算到点强杀与 node 节点的治理
 * 哲学相悖——node 会话从无超时，其存活治理是**无进展 stuck 传感 + 恢复**
 * （`TaskStuckWatcher`：agent 侧事件流停滞 / 工具相位卡死，`WaitingForUser` 豁免；
 * 那是活性机制，不是时间预算，本次原样保留）。delegate 家族现与之对齐：**无时间
 * 预算、长期运行不因时间被杀**；只有真卡死（无进展）才走既有恢复链。
 *
 * 本壳的存在形态：五个既有调用点（`BackoffSupervisor` 的 spawn 装配 / 合并窗重挂 /
 * respawn 重挂 / 终态 release、`AgentProcessing` 的 ask-pause、`AskUserQuestionTool`
 * 经 `DelegateBudgetPort` 的答复-resume）分布在与其他在飞批共享的文件里，按追加式
 * 纪律不改其调用形态——本对象保留原签名、全部退化为无害 no-op：
 *   - `register` **不装配任何计时器**：`onTimeout` 永不执行（参数保留仅为调用点
 *     兼容，`budget` / `clock` 为惰性摆设）；
 *   - `pause` / `resume` / `release` 恒 `IO.unit`；
 *   - `activeCount` 恒 0（永无活预算通道）。
 * 生效方式 = 重启 Nebflow 实例（新二进制装配本壳后，到点强杀路径不存在）。
 */
object DelegateBudget:

  private val logger = NebflowLogger.forName("nebflow.agent.delegate-budget")

  /** 惰性摆设（原预算值；仅保留调用点兼容语义，无任何行为）。 */
  val DefaultBudget: FiniteDuration = 3600.seconds

  /**
   * 原「起预算」入口，现为 **no-op**：不建通道、不起计时环、`onTimeout` 永不触发。
   * 参数（`budget` / `clock` / `onTimeout`）为调用点兼容保留，一律忽略。
   */
  def register(
    sessionId: String,
    budget: FiniteDuration = DefaultBudget,
    clock: () => Long = () => System.currentTimeMillis()
  )(onTimeout: IO[Unit]): IO[Unit] =
    logger
      .debug(
        s"DelegateBudget: no wall-clock budget armed for $sessionId " +
          "(mechanism dismantled 2026-10-09; governance = no-progress stuck detection only)"
      )
      .void

  /** 原 ask 发起暂停点，现为无害 no-op（无预算可暂停）。 */
  def pause(sessionId: String): IO[Unit] = IO.unit

  /** 原 ask 答复恢复点（经 `DelegateBudgetPort`），现为无害 no-op（无预算可恢复）。 */
  def resume(sessionId: String): IO[Unit] = IO.unit

  /** 原终态 / respawn 释放点，现为无害 no-op（无通道可释放）。 */
  def release(sessionId: String): IO[Unit] = IO.unit

  /** 可观测读数：恒 0——拆除后永无活预算通道（spec 锚）。 */
  def activeCount: Int = 0

end DelegateBudget
