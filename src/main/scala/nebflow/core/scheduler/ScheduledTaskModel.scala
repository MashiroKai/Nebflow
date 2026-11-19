package nebflow.core.scheduler

import io.circe.Codec
import io.circe.derivation.{Configuration, ConfiguredCodec}

import java.time.{Instant, ZoneId, ZonedDateTime}
import java.util.UUID

// `TodoDecision` / `DecisionOption` / `DecisionMedia` 已迁至本包
// [[TodoDecisionModel]]（待办数据模型与命名规范批 2026-11-18）：决策/选项/媒体
// 是同一份线格式，模型与校验（[[TodoTaxonomy]]）同处一地，避免「两张表两处改」。

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
   * 待办两段化的第二段（待办结构化批 2026-11-05 作者令「待办要求用 name 和
   * detail 这样的架构」）：`name` = 短标题（列表行显示面、`#` 引用键、同名
   * upsert 键），`detail` = 具体内容（可长，列表行折叠显示）。
   *
   * 🔴 兼容矩阵（逐条申报，不许假装"已迁移"）：
   *   · **存量行**（本批之前写入）没有 detail 键 ⇒ 解码 None。前端
   *     `todoText.projectTodoText` 在**读时**把 content 投影成 {name, detail}
   *     （首句 / 前 24 字作 name，其余留 detail；只丢首尾空白，不丢内容字符），
   *     **不写回、不删改文件** ⇒ 零丢失是结构性的，投影规则变更可重算。
   *   · **新行**：创建时带 detail ⇒ 前端直接用 name/detail，不再投影。
   *   · `content` 仍是必填的**注入文本**（每日摘要、done/cancel 回执、Schedule
   *     list 都读它）——两段化不改它对既有读者的语义，只把"给人看的标题"从
   *     content 里分出来。只传 content 的旧调用方行为逐字不变。
   *   · `kind="task"` 面：schedule-todo-unify 批（2026-11-06 作者令「定时任务也要
   *     按 name description 来添加 点击展开可以查看细节」）起 detail = **description
   *     第二段**（首行 = name 的展示约定在 UI 侧）；不传 ⇒ None，旧调用方逐字节不变。
   *     （本节此前写「task 面 detail 恒 None」，由本批改写，口径随真源。）
   */
  detail: Option[String] = None,
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
  idleStreak: Int = 0,  /**
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
  missCount: Int = 0,
  /**
   * 项目维度（待办体系改版批追加令 2026-10-31 23:14「事项按项目分」）：事项
   * 归属标签（如 "DOSTEL"/"stand"），UI 分组与筛选的主维度。None = 未分组。
   * 自由文本标签（引擎不持项目注册表权威——标签即数据）；存量文件无此键 →
   * None（withDefaults 零迁移）。定/待两面通用（定时任务面 UI 暂不消费）。
   */
  project: Option[String] = None,
  /**
   * 重要度（待办体系统一批 2026-11-04）：0 = 无（缺省）/ 1 低 / 2 中 / 3 高。
   * 越界一律钳位到 0..3（[[ScheduledTask.clampPriority]] 单点，create 与 WS patch
   * 都过它——库内不可能出现 4/‑1，前端排序无须再防）。存量文件无此键 → 0
   * （ConfiguredCodec + withDefaults 缺键回默认值，先例 missCount；spec 钉）。
   * 仅待办面消费；定时任务面字段恒 0、语义零改动。
   */
  priority: Int = 0,
  /**
   * 完成时刻（待办体系统一批 2026-11-04）：None = 未完成；Some(ms) = 已完成。
   * 🔴 这是**软删标记**——`completeTodo` 不再物理删行（DoneRecord 只增不改续
   * 写 + 行留库 ⇒ 取消完成可一键回退、周报数据源不断；旧「删行」路径把二者
   * 都做绝了）。已完成行：不进 scope:"all" 收件箱默认面、不进 getAllTodos /
   * getAllDueTodos 等读口 ⇒ **绝不被 scheduler 浮出**（见 Store 读口与
   * ScheduledTaskActor.surfaceTodos 双闸）。取消完成 = 置回 None（幂等）。
   * 存量文件无此键 → None（withDefaults 零迁移）。
   */
  completedAt: Option[Long] = None,
  source: Option[String] = None,
  /** Store-owned monotonic version; legacy rows start at zero. Never ordered by trigger time. */
  revision: Long = 0L,
  /**
   * 结构化决策（选项集）——**待办面的必备形态**（待办数据模型批 2026-11-18，作者令
   * 「每个待办必须有选项」；模型见 [[TodoDecisionModel]]，强制点见
   * [[TodoTaxonomy.validateCreate]]）。None = 无决策（存量行 / 提醒面 / 定时任务面
   * 一律 None，缺键零迁移）。
   */
  decision: Option[TodoDecision] = None
):
  /** true = 待办面（周期浮出直至销账），false = 定时任务面（现状）。 */
  def isTodo: Boolean = kind.contains(ScheduledTask.KindTodo)

  /** true = 已完成（软删标记置位）——读口/触发面据此排除。 */
  def isCompleted: Boolean = completedAt.isDefined

end ScheduledTask

object ScheduledTask:

  /** kind 值：定时任务面（None 视同此值）。 */
  val KindTask: String = "task"

  /** kind 值：待办面（每日浮出直至 done/cancel）。 */
  val KindTodo: String = "todo"

  /** kind 等值比较：None 与 Some(KindTask) 同面。 */
  def sameKind(a: Option[String], b: Option[String]): Boolean =
    a.getOrElse(KindTask) == b.getOrElse(KindTask)

  /** 重要度下界（0 = 无/未分级）。 */
  val PriorityMin: Int = 0

  /** 重要度上界（3 = 高）。 */
  val PriorityMax: Int = 3

  /** 重要度钳位单点：越界收进 [PriorityMin, PriorityMax]（0..3 之外的读数只可能
   *  来自老/手改文件或旧前端，一律收敛，库内不出现越界值）。 */
  def clampPriority(n: Int): Int = math.max(PriorityMin, math.min(PriorityMax, n))

  /**
   * 收件箱排序键（服务端权威，2026-11-04）：未完成优先 → priority 降序 →
   * triggerAt 升序（下次浮出早者先）→ createdAt 升序（稳定兜底，同键不抖动）。
   * 全键可**从每行数据本身算出** ⇒ 前端只消费服务端顺序，不许自己内存重排。
   */
  def todoSortKey(t: ScheduledTask): (Int, Int, Long, Long) =
    (if t.isCompleted then 1 else 0, -t.priority, t.triggerAt, t.createdAt)

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
    condition: Option[String] = None,
    project: Option[String] = None,
    /** 重要度 0..3（越界钳位，见 [[ScheduledTask.clampPriority]]）；kind=todo 的
     *  唯一创建点即此处，定时任务面调用方不传 = 0（逐字节现状）。 */
    priority: Int = 0,
    /** 第二段 detail（两段化批 2026-11-05；定时任务面启用 = schedule-todo-unify
     *  批 2026-11-06 作者令「定时任务也要按 name description 来添加 点击展开可以
     *  查看细节」）：待办面 = detail（2026-11-05 起），定时任务面 = description。
     *  **缺键/空白 ⇒ None**（旧调用方不传 = 逐字节现状，零迁移）。
     *  末位默认参数 ⇒ 全部既有位置/命名调用零改动。 */
    detail: Option[String] = None,
    /** 决策选项集（待办数据模型批 2026-11-18）：**写侧强制**非空（待办面）——
     *  本工厂只做归一化，判定归 [[TodoTaxonomy.validateCreate]]（调用方在 create
     *  之前调用它）。末位默认参数 ⇒ 全部既有位置/命名调用零改动。 */
    decision: Option[TodoDecision] = None
  ): ScheduledTask =
    val normalizedKind = kind.map(_.trim.toLowerCase).filter(_.nonEmpty)
    val isTodo = normalizedKind.contains(KindTodo)
    ScheduledTask(
      id = UUID.randomUUID().toString.take(8),
      sessionId = sessionId,
      content = content,
      // 待办的 triggerAt = 下次浮出时间；定时任务 = 触发时间（现状不变）。
      triggerAt = triggerAt,
      createdAt = System.currentTimeMillis(),
      referencePath = referencePath,
      // 待办无 repeat 语义（浮出周期是引擎侧固定策略：每日 09:00）。
      repeat = if isTodo then None else repeat,
      name = name.map(_.trim).filter(_.nonEmpty),
      // 两面都收 detail（schedule-todo-unify 批）：空白串归一为 None（不留"空的第二段"）。
      detail = detail.map(_.trim).filter(_.nonEmpty),
      kind = normalizedKind.filter(_ == KindTodo), // 非 "todo" 值一律归定时任务面
      condition = condition.map(_.trim).filter(_.nonEmpty),
      project = project.map(_.trim).filter(_.nonEmpty),
      priority = clampPriority(priority),
      // 决策选项集：归一化（补空白 id、去空白选项）后落库；「待办必须有选项」的
      // 判定**不在这里**（工厂是纯构造器，拒绝面归 TodoTaxonomy.validateCreate），
      // 这里只保证库内形态干净：空白 title 的选项直接丢弃（不可能由写侧产生——
      // 写侧先拒；防的是手改文件）。
      decision = decision.map(TodoTaxonomy.normalizeDecision).filter(d => d.options.nonEmpty)
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
