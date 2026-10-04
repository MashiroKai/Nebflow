package nebflow.shared

/**
 * 记忆预算闸（memory-management-plan §2.2-3 / §3.3 / §6.2-2.2，2026-09-05 第二批机制）。
 *
 * 预算现行裁定（50KB/30KB 硬顶）+ 80% 软触发（40KB/24KB）。执行位置在【写入侧】：
 * 注入侧不加截断（§3.3 裁定——截断=静默丢记忆，比超预算更危险且不可观测；该裁定
 * 现由注入侧渲染与 MemoryDirectWriteGuard 的提醒/拒绝文案共同承载）。
 *
 * 消费方（同一判据防绕过）：
 *   - MemoryDirectWriteGuard（govmemory 批 2026-09-25）：Edit/Write 直写三层
 *     记忆文件的写前检查——超硬顶且净增 ⇒ 结构化拒绝（附 top-3 最大节+整理指引）；
 *     超 80% 软线 ⇒ 放行 + 结果附 system-reminder（24h 防骚扰）。真收缩豁免
 *     （判据单源 [[MemoryWriteGate.shrinkExempt]]）——收缩是超限后的自救通道，
 *     闸掉它就断了整理路径。
 *   - [[MemoryWriteGate.decide]]（M4 单点闸）：WS `saveMemory` 旁路的落盘前校验。
 *   - ProjectMemory.injectionBlock（project-memory 批 2026-09-05）：项目记忆
 *     注入渲染共用三态判据——预算内全文、软警区全文+WARN 脚注、超硬顶头部+统计。
 *     project 维度常量独立（10KB/8KB，见常量处定值依据）。
 *     （旧消费方（已退役记账工具 与 DreamMode.updateMemory）已随各自机制退役——
 *     govmemory 批 / DreamMode 停用批。）
 *
 * 纯函数、零 IO、零 ToolError 依赖（service 不反向依赖 core.tools——错误包装由
 * 调用方完成），供两侧与 spec 共享同一判据。
 */
object MemoryBudget:

  // ---------------------------------------------------------------
  // 预算常量（钉死值：plan §6.2 常量锚点；改动需过作者裁定）
  // ---------------------------------------------------------------

  /** User.md 硬顶 50KB —— 超出 = 写入拒绝。 */
  val UserHardBytes: Long = 50L * 1024

  /** User.md 软警线 40KB（80%）—— 超出 = 放行但 WARN 提示整理。 */
  val UserSoftBytes: Long = 40L * 1024

  /** agents/Nebula/memory.md 硬顶 30KB。 */
  val AgentHardBytes: Long = 30L * 1024

  /** agent memory.md 软警线 24KB（80%）。 */
  val AgentSoftBytes: Long = 24L * 1024

  /**
   * 单项目 `<workspace>/.nebflow/memory.md` 硬顶 10KB（project-memory 批
   * 2026-09-05，建议区间 8-12KB 定值取中）。定值依据：项目记忆注入面与全局
   * 不同——它随【每个】该项目分发器 spawn + 该项目【每个】节点首条消息重复
   * 注入（乘法面：一批 N 节点 = N 份拷贝），而全局两文件只进 Nebula 单会话；
   * 全局 30-50KB 预算按「常驻单份」定价，项目记忆须低一个量级按「乘法分发」
   * 定价。10KB 在典型注入规模（分发器 1 份 + 批内 ≤10 节点 ≈ 100KB 瞬时）
   * 与「单行条目纪律下 ~60 条容量」（均值 ~170B/条，对齐 §2.1 T3 测算口径）
   * 之间取平衡；软警线取 80% 惯例（8KB）。
   */
  val ProjectHardBytes: Long = 10L * 1024

  /** 单项目 memory.md 软警线 8KB（80%）。 */
  val ProjectSoftBytes: Long = 8L * 1024

  // ---------------------------------------------------------------
  // 注入预算 × 上下文窗口联动（personal-agent 批 ⑥，2026-10-04）
  // ---------------------------------------------------------------

  /**
   * token → 字节的保守估算（中英混排近似）。
   *
   * 为什么不引真 tokenizer：预算判据必须在**注入前**、零网络、零依赖地算出，
   * 且两侧（渲染 / 写闸）要共用同一个数字。4 B/token 是既能覆盖英文（~4）也能
   * 覆盖中文（~3）的常用保守值——偏保守 ⇒ 预算是**上限**语义，宁可早提醒。
   */
  val BytesPerToken: Long = 4L

  /**
   * 记忆可占上下文窗口的比例（**字节口径**，见 [[BytesPerToken]]）。
   *
   * 取 10% 的依据：默认窗口 128,000 tokens ⇒ 512,000 B ⇒ 上限 51,200 B，
   * 恰**不低于**现役两级硬顶（user 50KB / agent 30KB）⇒ **默认口径零变化**
   * （既有常量继续绑定）。窗口变小（32k：32,768 × 4 × 10% = 13,107 B）时才收紧
   * ——正是「小肺不该背大行李」的场景。作者令（2026-10-04 09:02 第 2 条）要求
   * onboarding 设的 context window 接入本判据；本常量是纯函数式的连接点。
   */
  val InjectionShareOfContext: Double = 0.10

  /**
   * 注入侧字节上限（由**生效**上下文窗口推导）。
   *
   * 🔴 入参必须是**生效窗口**（`ProviderRegistry.effectiveContextWindow` 的产物，
   * 即 `min(configured, modelMaxContext)`），消费链 = `ModelCandidate.contextWindow`
   * → `AgentState.contextWindow` → `ContextRefresher`。**禁另立旁路**：本函数不做
   * clamp、不读配置，只做比例换算（clamp 的取数单点在 `llm/registry.scala`）。
   *
   * 非正数（未配置 / 探不到）⇒ `Long.MaxValue` ⇒ 由既有常量绑定（fail-open 到
   * 旧行为，绝不因缺值把预算压成 0）。
   */
  def injectionCapBytes(contextWindow: Int): Long =
    if contextWindow <= 0 then Long.MaxValue
    else (contextWindow.toLong * BytesPerToken * InjectionShareOfContext).toLong

  /** 注入侧生效硬顶 = min(既有常量, 窗口推导上限)。 */
  def effectiveHardBytes(baseHard: Long, contextWindow: Int): Long =
    math.min(baseHard, injectionCapBytes(contextWindow))

  /** 注入侧生效软线 = 生效硬顶的 80%（沿用既有 80% 惯例）。 */
  def effectiveSoftBytes(baseHard: Long, contextWindow: Int): Long =
    (effectiveHardBytes(baseHard, contextWindow) * 4) / 5

  /** user 级注入硬顶（窗口联动）。 */
  def userInjectionHardBytes(contextWindow: Int): Long = effectiveHardBytes(UserHardBytes, contextWindow)

  /** user 级注入软线（窗口联动）。 */
  def userInjectionSoftBytes(contextWindow: Int): Long = effectiveSoftBytes(UserHardBytes, contextWindow)

  /** agent（Soul）级注入硬顶（窗口联动）。 */
  def agentInjectionHardBytes(contextWindow: Int): Long = effectiveHardBytes(AgentHardBytes, contextWindow)

  /** agent（Soul）级注入软线（窗口联动）。 */
  def agentInjectionSoftBytes(contextWindow: Int): Long = effectiveSoftBytes(AgentHardBytes, contextWindow)

  // ---------------------------------------------------------------
  // 判定
  // ---------------------------------------------------------------

  sealed trait Verdict:
    def bytes: Long

  /** 预算内（含恰好等于软线）。 */
  case object Within extends Verdict:
    val bytes: Long = 0L

  /** 超 80% 软线，未超硬顶 —— 放行 + WARN。 */
  final case class Warn(override val bytes: Long, softCap: Long, hardCap: Long) extends Verdict

  /** 超硬顶 —— 拒绝。 */
  final case class Exceeded(override val bytes: Long, hardCap: Long) extends Verdict

  /** target 标识（"user" / "agent" / "project"）。project 维度
    * （project-memory 批）：单个项目的 `<workspace>/.nebflow/memory.md`，
    * 常量独立于全局两级。 */
  def verdict(target: String, newSizeBytes: Long): Verdict =
    target match
      case "user" =>
        if newSizeBytes > UserHardBytes then Exceeded(newSizeBytes, UserHardBytes)
        else if newSizeBytes > UserSoftBytes then Warn(newSizeBytes, UserSoftBytes, UserHardBytes)
        else Within
      case "agent" =>
        if newSizeBytes > AgentHardBytes then Exceeded(newSizeBytes, AgentHardBytes)
        else if newSizeBytes > AgentSoftBytes then Warn(newSizeBytes, AgentSoftBytes, AgentHardBytes)
        else Within
      case "project" =>
        if newSizeBytes > ProjectHardBytes then Exceeded(newSizeBytes, ProjectHardBytes)
        else if newSizeBytes > ProjectSoftBytes then Warn(newSizeBytes, ProjectSoftBytes, ProjectHardBytes)
        else Within
      case other => throw new IllegalArgumentException(s"unknown memory target '$other'")

  // ---------------------------------------------------------------
  // 节字节统计（超限拒绝消息的「先整理」定位线索）
  // ---------------------------------------------------------------

  private def isHeading(line: String): Boolean = line.trim.startsWith("## ")

  /**
   * 按 "## " 标题切节统计字节（标题行起至下一标题行/文件尾），按字节降序。
   * 无标题内容归 "(no section)"。
   */
  def sectionSizes(content: String): Vector[(String, Long)] =
    val lines = content.linesIterator.toVector
    val headingIdx = lines.indices.filter(i => isHeading(lines(i))).toVector
    if headingIdx.isEmpty then
      val total = content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length.toLong
      if total == 0 then Vector.empty
      else Vector("(no section)" -> total)
    else
      val head = lines.takeWhile(!isHeading(_)).mkString("\n")
      val preamble =
        if head.trim.isEmpty then Vector.empty
        else Vector("(preamble)" -> head.getBytes(java.nio.charset.StandardCharsets.UTF_8).length.toLong)
      val sections = headingIdx.indices.map { k =>
        val start = headingIdx(k)
        val end = if k + 1 < headingIdx.length then headingIdx(k + 1) else lines.length
        val name = lines(start).trim.stripPrefix("## ").trim
        val body = lines.slice(start, end).mkString("\n")
        name -> body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length.toLong
      }.toVector
      (preamble ++ sections).sortBy(-_._2)

    end if

  end sectionSizes

  /** top-n 最大节文案（拒绝消息内嵌）：`  12,345 B  ## Section Name`。 */
  def topSections(content: String, n: Int = 3): String =
    val sizes = sectionSizes(content).take(n)
    if sizes.isEmpty then "  (empty file)"
    else sizes.map((name, b) => f"  $b%,d B  ## $name").mkString("\n")

  // ---------------------------------------------------------------
  // 文案（调用方包装成 ToolError / 结果附录）
  // ---------------------------------------------------------------

  /** 超限拒绝消息体（MemoryDirectWriteGuard / MemoryWriteGate 预算拒绝共用文案基底；
    * govmemory 批起为直写口径）。project 维度的真实路径由调用方经 targetPath 传入
    * （各项目 workspace 不同，无法从 target 常量推出）。 */
  def exceededMessage(action: String, target: String, targetPath: String, newSizeBytes: Long, newContent: String): String =
    val (label, hard) = target match
      case "user" => ("~/.nebflow/User.md", UserHardBytes)
      case "agent" => ("~/.nebflow/Soul.md", AgentHardBytes)
      case "project" => (targetPath, ProjectHardBytes)
      case other => (other, -1L)
    val pct = if hard > 0 then f"${newSizeBytes * 100.0 / hard}%.0f%%" else "?"
    s"""Memory write ($action) rejected — $label would reach $newSizeBytes bytes ($pct of the $hard-byte hard budget).
       |Budget is enforced on the WRITE side (injection is never truncated — over-budget memory silently degrades every future session instead).
       |Consolidate first, then write. Largest sections:
       |${topSections(newContent)}
       |Trim stale/duplicate entries in place (the append and the remove/update are paired in the same round; memory-consolidation skill), or demote detail into ~/.nebflow/memory/<id>.md files.
       |A write that shrinks the file stays exempt from the hard cap. (MEMORYEDIT_BUDGET)""".stripMargin

end MemoryBudget
