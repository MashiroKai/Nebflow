package nebflow.shared

import java.util.concurrent.atomic.AtomicReference

/** 宿主睡眠/冻结窗（墙钟毫秒，闭区间语义 `[sleepAtMs, wakeAtMs]`）。
  *
  * hostresume 批 2026-09-22，设计卡
  * `~/.nebflow/docs/Nebflow/20260921_235342_host-interrupt-resume-design__chain-n-494ba650.md`
  * §4 #1（作者「七项全照推荐」裁定，D-1 A1 纯 JVM 双钟断流探测）。窗由
  * `nebflow.core.project.WakeSensor` 在唤醒后判出（睡眠期间纤维冻结、无睡前预告），
  * 消费方 = `TaskStuckWatcher.assessDetailed` 两轴与整流 no-progress 守卫的时间基修正
  * （卡 §4 #4/#5，D-6 首批仅此两处消费点）。
  */
final case class SleepWindow(sleepAtMs: Long, wakeAtMs: Long):
  def durationMs: Long = math.max(0L, wakeAtMs - sleepAtMs)

/** 时间基修正纯函数面（零状态、零配置读、可独立单测——卡 §4 #1「纯函数」要求）。
  *
  * 口径：`effectiveElapsed(startMs, nowMs)` = 墙钟差值 − 与窗集的交集时长。
  * **空窗集 ⇒ 修正量恒为零 ⇒ 返回值与既有裸差值逐字节相等**（含 `nowMs < startMs`
  * 的时钟回拨负值——负值在消费点只会落入「不判」分支，与现状同判；卡 §6 口径 1）。
  */
object PowerStateMath:

  /** `[startMs, nowMs]` 与窗集的交集总时长（毫秒，≥0；窗集假定互不重叠——
    * 睡眠期结构性不重叠；即使输入重叠，外层减法也已被 [[effectiveElapsed]] 钳 ≥0）。 */
  def frozenOverlapMs(windows: List[SleepWindow], startMs: Long, nowMs: Long): Long =
    if startMs >= nowMs then 0L
    else
      windows.foldLeft(0L) { (acc, w) =>
        val lo = math.max(w.sleepAtMs, startMs)
        val hi = math.min(w.wakeAtMs, nowMs)
        if hi > lo then acc + (hi - lo) else acc
      }

  /** 时间基修正（卡 §4 #1 的纯函数本体）：墙钟差值扣去窗集交集。
    * 空窗集（或差值 ≤0）时**逐字节**等于裸差值 `nowMs - startMs`。 */
  def effectiveElapsed(windows: List[SleepWindow], startMs: Long, nowMs: Long): Long =
    val raw = nowMs - startMs
    if windows.isEmpty || raw <= 0L then raw
    else math.max(0L, raw - frozenOverlapMs(windows, startMs, nowMs))

/** 睡眠窗集持有者（进程内单点；时间基修正的消费入口）。
  *
  * 生产实例 = [[PowerStateTracker.global]]（`WakeSensor` 登记窗、`TaskStuckWatcher` /
  * 整流守卫读）；测试可 `new PowerStateTracker()` 自建隔离实例（互不污染）。
  *
  * 依赖方向：本件落 `nebflow.shared`——`core`（TaskStuckWatcher）与 `llm`
  * （interface 整流守卫）双消费，shared 是两者的共同下游（卡 §4 #1 依赖约束）。
  */
final class PowerStateTracker private (windowsRef: AtomicReference[List[SleepWindow]]):

  def this() = this(new AtomicReference[List[SleepWindow]](Nil))

  /** 当前窗集快照（只读）。 */
  def windows: List[SleepWindow] = windowsRef.get()

  /** 登记一个睡眠窗（append-only + 双重修剪：超过保留期的旧窗与超量的最老窗）。
    * 幂等性（卡 §2.3）：时间基修正是纯函数查询，同窗重复登记只让交集计算多扫一行，
    * 不改变修正结果。 */
  def register(w: SleepWindow): Unit =
    windowsRef.updateAndGet { ws =>
      val horizon = w.wakeAtMs - PowerStateTracker.WindowRetentionMs
      (ws :+ w).filter(_.wakeAtMs >= horizon).takeRight(PowerStateTracker.MaxWindows)
    }
    ()

  /** 测试复位（生产无调用点——进程生命周期内只增不减）。 */
  def resetForTest(): Unit = windowsRef.set(Nil)

  /** 时间基修正（实例入口，**不过** kill-switch 闸——闸在伴生的全局入口）。 */
  def effectiveElapsed(startMs: Long, nowMs: Long): Long =
    PowerStateMath.effectiveElapsed(windows, startMs, nowMs)

object PowerStateTracker:

  /** 窗保留上限（条）：防长寿命进程窗集无界增长；`effectiveElapsed` 单次调用为
    * O(窗数) 纯算术，128 条 × 每扫描轮每会话一次，开销可忽略。 */
  val MaxWindows: Int = 128

  /** 窗保留期（毫秒）：超龄窗对消费点已无意义（判据窗口 ≤ 分钟级），登记时修剪。 */
  val WindowRetentionMs: Long = 24L * 60 * 60 * 1000

  /** 生产单点。 */
  private val global: PowerStateTracker = new PowerStateTracker()

  /** 当前全局窗集快照（观测/取证面）。 */
  def windows: List[SleepWindow] = global.windows

  /** WakeSensor 登记入口（全局单点）。 */
  def registerSleepWindow(sleepAtMs: Long, wakeAtMs: Long): Unit =
    global.register(SleepWindow(sleepAtMs, wakeAtMs))

  /** 时间基修正全局消费入口（TaskStuckWatcher / 整流守卫的注入默认值）。
    *
    * **kill-switch 闸（卡 D-2）**：`nebflow.wake.sense.enabled=false` 时**恒等返回
    * 裸差值**——即使窗集因开关翻转前的检测而有残留（prop 每次现读、可即时翻转），
    * 修正量也恒为零，消费点判词与文案逐字回现状。 */
  def effectiveElapsed(startMs: Long, nowMs: Long): Long =
    if !Defaults.WakeSenseEnabled then nowMs - startMs
    else global.effectiveElapsed(startMs, nowMs)

  /** 测试复位全局窗集（`nebflow.shared` 包内测试专用；生产零调用点）。 */
  private[shared] def resetGlobalForTest(): Unit = global.resetForTest()
