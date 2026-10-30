package nebflow.core.scheduler

import io.circe.Codec
import io.circe.derivation.{Configuration, ConfiguredCodec}

import java.time.{Instant, ZoneId, ZonedDateTime}
import java.util.UUID

/** A scheduled task attached to a session — fires once or on a recurring schedule. */
case class ScheduledTask(
  id: String,
  sessionId: String,
  content: String,
  triggerAt: Long,
  createdAt: Long,
  triggered: Boolean = false,
  triggeredAt: Option[Long] = None,
  /** Optional file path for the LLM to reference when the task fires. */
  referencePath: Option[String] = None,
  /** Recurrence pattern: "hourly", "daily", "weekly", or None for one-shot. */
  repeat: Option[String] = None,
  /** When false, the task is skipped by fireDueTasks but kept in storage. */
  enabled: Boolean = true,
  /**
   * Stable upsert key（2026-09-06 定时任务升级，事故根因修复）：同 name 再建
   * = 替换不叠加（ScheduledTaskStore.upsertTaskByName），重启后 re-arm 例行
   * 任务自动去重。None = 匿名任务，行为同旧版（永不参与去重）。匹配为全库
   * 跨会话精确等值。
   */
  name: Option[String] = None,
  /**
   * 双面化 kind（2026-10-24 作者令「Schedule 定时工具双面化」）：None /
   * Some("task") = 定时任务（现状，triggerAt 到点触发一次或按 repeat 续期）；
   * Some("todo") = 待办——无固定触发时间，triggerAt 复用为**下次浮出时间**
   * （每日 09:00 汇总注入一次，与定时任务同等引擎侧持久：会话压缩/重启不丢），
   * 完成 done 销账 / cancel 取消后从存储删除、永不再浮出。存量文件无 kind 键
   * → 解码 None → 一律按定时任务（老数据零迁移）。
   */
  kind: Option[String] = None,
  /**
   * 条件触发（2026-10-24 主动性增强①：从纯墙钟走向条件叫醒）。非空 = 一条
   * `sh -c` 探针命令：到点先跑探针，退出码 0 才注入（条件为真才叫醒）；非 0
   * 静默顺延——repeat 任务跳到下一周期、一次性任务每 30 分钟复查（自 createdAt
   * 起 24h 内），到期未满足自动转 PAUSED（list 可见，不发占位消息——空闲安静
   * 红线）。探针必须快（60s 硬超时=未满足）、无副作用、用绝对路径。仅定时任务
   * 面消费；待办面忽略此字段。
   */
  condition: Option[String] = None,
  /**
   * 条件评估器状态（2026-10-29 哨兵批）：有状态评估器（如 git-tip 哨兵的
   * 「上次见到的 tip」）的工作记忆，由引擎在每次评估后写回。None = 尚无基线
   * （首次评估建基线、不触发——哨兵语义：只对「武装之后的变化」叫醒）。
   */
  conditionState: Option[String] = None,
  /**
   * 例程自检（2026-10-29 主动性③降频批）：连续空转计数——上次触发以来本会话
   * 零工具动作 ⇒ 本轮触发记 1 次，有动作清零。达 IdleDownfreqThreshold 自动
   * 降频（repeat 阶梯 hour→day→week）并挂 reviewFlag。随任务持久化，重启不清。
   */
  idleStreak: Int = 0,
  /**
   * 「待审查」标记（2026-10-29 降频批）：连续空转达阈值后被引擎自动降频的
   * 例程挂此标——list 可见；作者恢复 = 同名 re-arm（upsert 换新任务，标记与
   * 计数自然归零）或 cancel 销账。粘性：一旦挂上不自动摘（自清则作者永远看不到）。
   */
  reviewFlag: Boolean = false,
  /**
   * 最近一次实际触发的墙钟（降频批）：下轮触发的空转判据 = 本会话在
   * [lastFiredAt, now) 窗口内零工具动作。None = 尚未触发过（首个周期不计空转）。
   */
  lastFiredAt: Option[Long] = None,
  /**
   * 缺卡计数（认知二期产品批·打卡缺卡调整流）：待办每浮出一次未被销账 ⇒ +1；
   * 销账/取消即随记录删除。达 MissThreshold 后浮出文案附**调整建议**（降频或
   * 重拆，呈作者确认——不擅自改计划，注意力伦理）。随任务持久化，存量文件
   * 无此键 → 0（withDefaults 零迁移）。
   */
  missCount: Int = 0
):
  /** true = 待办面（周期浮出直至销账），false = 定时任务面（现状）。 */
  def isTodo: Boolean = kind.contains(ScheduledTask.KindTodo)

end ScheduledTask

object ScheduledTask:

  /** kind 值：定时任务面（None 视同此值）。 */
  val KindTask: String = "task"

  /** kind 值：待办面（每日浮出直至 done/cancel）。 */
  val KindTodo: String = "todo"

  /** kind 等值比较：None 与 Some(KindTask) 同面。 */
  def sameKind(a: Option[String], b: Option[String]): Boolean =
    a.getOrElse(KindTask) == b.getOrElse(KindTask)

  // 存量文件向后兼容（2026-09-06 实证 + 2026-10-29 修正）：缺键回构造器默认值。
  // 🔴 deriveCodec（generic.semiauto）只对 **Option 字段** 容忍缺键——非 Option
  // 字段（如本批新增的 idleStreak: Int / reviewFlag: Boolean）缺键 = 解码硬失败
  // "Missing required field" ⇒ 整个会话任务文件解码为 Nil ⇒ 存量任务静默全部
  // 消失（本批 spec 实测红：legacyJson 无 idleStreak 键）。换
  // ConfiguredCodec + withDefaults（库内先例：TaskListEntry/NotifyConfig）——
  // 缺键一律回默认值，老文件零迁移；升级前文件没有 name/kind/condition 键 →
  // 解码 None，没有 idleStreak/reviewFlag 键 → 0/false。
  given Configuration = Configuration.default.withDefaults
  given Codec[ScheduledTask] = ConfiguredCodec.derived

  def create(
    sessionId: String,
    content: String,
    triggerAt: Long,
    referencePath: Option[String] = None,
    repeat: Option[String] = None,
    name: Option[String] = None,
    kind: Option[String] = None,
    condition: Option[String] = None
  ): ScheduledTask =
    val normalizedKind = kind.map(_.trim.toLowerCase).filter(_.nonEmpty)
    ScheduledTask(
      id = UUID.randomUUID().toString.take(8),
      sessionId = sessionId,
      content = content,
      // 待办的 triggerAt = 下次浮出时间；定时任务 = 触发时间（现状不变）。
      triggerAt = triggerAt,
      createdAt = System.currentTimeMillis(),
      referencePath = referencePath,
      // 待办无 repeat 语义（浮出周期是引擎侧固定策略：每日 09:00）。
      repeat = if normalizedKind.contains(KindTodo) then None else repeat,
      name = name.map(_.trim).filter(_.nonEmpty),
      kind = normalizedKind.filter(_ == KindTodo), // 非 "todo" 值一律归定时任务面
      condition = condition.map(_.trim).filter(_.nonEmpty)
    )

  end create

  /**
   * 待办下一次浮出时间（双面化 2026-10-24）：今天本地 09:00，已过则明天
   * 09:00。纯函数（now/zone 注入）——spec 可钉。
   */
  def nextTodoSurfaceAt(now: Long, zone: ZoneId): Long =
    val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
    var t = zdt.toLocalDate.atTime(9, 0).atZone(zone)
    if !t.toInstant.isAfter(Instant.ofEpochMilli(now)) then t = t.plusDays(1)
    t.toInstant.toEpochMilli
  end nextTodoSurfaceAt

end ScheduledTask
