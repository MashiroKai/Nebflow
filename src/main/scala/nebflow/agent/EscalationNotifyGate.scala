package nebflow.agent

import cats.effect.IO
import cats.effect.kernel.Ref
import nebflow.shared.Defaults

/**
 * 升级链通知风暴闸（v2 反推送风暴批，2026-10-28 作者报障「什么东西一直重复推送」）。
 *
 * 实证（stand.log 2026-10-28 19:23-20:10）：一次 provider-down 波及 root + 7 个
 * delegate 子会话，各自独立 10min 升级梯且 escalateAt 时间错开 ⇒ root 每 ~30-90s
 * 收一条 `error-escalated` ExternalEvent（L2→L5 共 4 波 ≈ 28 条），每条打断父会话
 * 一个完整回合。既有面只有「单会话单级 10min 窗」（ErrorEscalateAfterMs），对
 * 「多会话同源 + 恢复/决策不销账 + 队列重放」零防御。
 *
 * 本闸是**总量总闸**（修复面④）：按父会话（parentSid）滑窗计数——窗口
 * [[Defaults.EscalationNotifyWindowMs]]（1h）内最多放行
 * [[Defaults.EscalationNotifyCapPerHour]]（6）条升级通知；超限静默吞掉（升级链
 * 状态照常推进，冻结恢复语义不受影响），并累计 suppressed 计数；下一个获准投递
 * 的通知携带「已压制 N 条」摘要行，保证父侧可感知风暴而非完全失明。
 *
 * 实例纪律：SharedResources 默认字段（`Ref.unsafe` 先例，同 attachUploads /
 * decisionHealth）——存量测试构造点零改动；每实例独立（spec 隔离防跨 suite 污染）。
 */
final class EscalationNotifyGate private (
  cap: Int,
  windowMs: Long,
  state: Ref[IO, EscalationNotifyGate.State]
):

  import EscalationNotifyGate.*

  /** admit 裁决：allow=放行；suppressed=本父会话累计被压制条数（放行时随行消费归零）。 */
  final case class Verdict(allow: Boolean, suppressed: Int)

  /**
   * 判定 + 记账（Ref.modify 原子单飞）。放行 ⇒ 本条时间戳入窗；超限 ⇒
   * suppressed+1、不入窗（下窗重试）。返回 suppressed 供调用方拼摘要（放行时
   * 通常为 0；>0 = 本条是风暴后的首条放行，需携带压制摘要）。
   */
  def admit(parentSid: String, now: Long = System.currentTimeMillis()): IO[Verdict] =
    state.modify { s =>
      val ps = s.parents.getOrElse(parentSid, ParentState())
      val fresh = ps.stamps.filter(_ > now - windowMs)
      if fresh.size >= cap then
        val bumped = ps.copy(stamps = fresh, suppressed = ps.suppressed + 1)
        (s.copy(parents = s.parents.updated(parentSid, bumped)), Verdict(allow = false, suppressed = bumped.suppressed))
      else
        val cleared = ps.copy(stamps = fresh :+ now, suppressed = 0)
        (s.copy(parents = s.parents.updated(parentSid, cleared)), Verdict(allow = true, suppressed = ps.suppressed))
    }

  /** 测试专用：清空全部窗口态（spec 隔离）。 */
  private[agent] def resetAll: IO[Unit] = state.set(State())

end EscalationNotifyGate

object EscalationNotifyGate:

  private final case class State(parents: Map[String, ParentState] = Map.empty)
  private final case class ParentState(stamps: Vector[Long] = Vector.empty, suppressed: Int = 0)

  /** 生产默认：Defaults 常量 + 独立 Ref（`Ref.unsafe` 先例，构造点零改动）。 */
  def unsafe: EscalationNotifyGate =
    new EscalationNotifyGate(Defaults.EscalationNotifyCapPerHour, Defaults.EscalationNotifyWindowMs, unsafeRef())

  private def unsafeRef(): Ref[IO, State] = Ref.unsafe[IO, State](State())

  /** 测试/装配面：显式 cap/window 的独立实例。 */
  def make(
    cap: Int = Defaults.EscalationNotifyCapPerHour,
    windowMs: Long = Defaults.EscalationNotifyWindowMs
  ): IO[EscalationNotifyGate] =
    Ref.of[IO, State](State()).map(new EscalationNotifyGate(cap, windowMs, _))
end EscalationNotifyGate
