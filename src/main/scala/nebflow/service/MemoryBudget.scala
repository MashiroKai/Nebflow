package nebflow.service

/**
 * 记忆预算闸（memory-management-plan §2.2-3 / §3.3 / §6.2-2.2，2026-09-05 第二批机制）。
 *
 * 预算现行裁定（50KB/30KB 硬顶）+ 80% 软触发（40KB/24KB）。执行位置在【写入侧】：
 * 注入侧不加截断（§3.3 裁定——截断=静默丢记忆，比超预算更危险且不可观测；该裁定
 * 随本对象固化进 MemoryEdit 工具 description）。
 *
 * 消费方（两处写面，同一判据防绕过）：
 *   - MemoryEditTool append/update：落盘前校验【新文件总字节】——超硬顶=结构化拒绝
 *     （MEMORYEDIT_BUDGET，附 top-3 最大节+整理指引）；超 80%=放行+结果文本附 WARN。
 *     replace_section 不闸——它是超限后的整理通道，闸掉它就断了收缩路径。
 *   - DreamMode.updateMemory（hook 自动写入）：合并产物超硬顶 → 跳过合并+WARN，
 *     防 hook 侧绕过预算（§6.2-2.2）。
 *   - ProjectMemory.injectionBlock（project-memory 批 2026-09-05）：项目记忆
 *     注入渲染共用三态判据——预算内全文、软警区全文+WARN 脚注、超硬顶头部+统计。
 *     project 维度常量独立（10KB/8KB，见常量处定值依据）。
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

  /** 单项目 `<workspace>/.nebflow/memory.md` 硬顶 10KB（project-memory 批
    * 2026-09-05，建议区间 8-12KB 定值取中）。定值依据：项目记忆注入面与全局
    * 不同——它随【每个】该项目分发器 spawn + 该项目【每个】节点首条消息重复
    * 注入（乘法面：一批 N 节点 = N 份拷贝），而全局两文件只进 Nebula 单会话；
    * 全局 30-50KB 预算按「常驻单份」定价，项目记忆须低一个量级按「乘法分发」
    * 定价。10KB 在典型注入规模（分发器 1 份 + 批内 ≤10 节点 ≈ 100KB 瞬时）
    * 与「单行条目纪律下 ~60 条容量」（均值 ~170B/条，对齐 §2.1 T3 测算口径）
    * 之间取平衡；软警线取 80% 惯例（8KB）。 */
  val ProjectHardBytes: Long = 10L * 1024

  /** 单项目 memory.md 软警线 8KB（80%）。 */
  val ProjectSoftBytes: Long = 8L * 1024

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

  /** target 标识（MemoryEdit 的 "user"/"agent"/"project"；Dream 固定写
    * User.md → "user"）。project 维度（project-memory 批 2026-09-05）：单个
    * 项目的 `<workspace>/.nebflow/memory.md`，常量独立于全局两级。 */
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

  /** 按 "## " 标题切节统计字节（标题行起至下一标题行/文件尾），按字节降序。
    * 无标题内容归 "(no section)"。 */
  def sectionSizes(content: String): Vector[(String, Long)] =
    val lines = content.linesIterator.toVector
    val headingIdx = lines.indices.filter(i => isHeading(lines(i))).toVector
    if headingIdx.isEmpty then
      val total = content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length.toLong
      if total == 0 then Vector.empty
      else Vector("(no section)" -> total)
    else
      val head = lines.takeWhile(!isHeading(_)).mkString("\n")
      val preamble = if head.trim.isEmpty then Vector.empty
      else Vector("(preamble)" -> head.getBytes(java.nio.charset.StandardCharsets.UTF_8).length.toLong)
      val sections = headingIdx.indices.map { k =>
        val start = headingIdx(k)
        val end = if k + 1 < headingIdx.length then headingIdx(k + 1) else lines.length
        val name = lines(start).trim.stripPrefix("## ").trim
        val body = lines.slice(start, end).mkString("\n")
        name -> body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length.toLong
      }.toVector
      (preamble ++ sections).sortBy(-_._2)

  /** top-n 最大节文案（拒绝消息内嵌）：`  12,345 B  ## Section Name`。 */
  def topSections(content: String, n: Int = 3): String =
    val sizes = sectionSizes(content).take(n)
    if sizes.isEmpty then "  (empty file)"
    else sizes.map((name, b) => f"  $b%,d B  ## $name").mkString("\n")

  // ---------------------------------------------------------------
  // 文案（调用方包装成 ToolError / 结果附录）
  // ---------------------------------------------------------------

  /** 超限拒绝消息体（MemoryEdit 包装为 MEMORYEDIT_BUDGET；Dream 侧写日志）。
    * project 维度的真实路径由调用方经 targetPath 传入（各项目 workspace 不同，
    * 无法从 target 常量推出）。 */
  def exceededMessage(action: String, target: String, targetPath: String, newSizeBytes: Long, newContent: String): String =
    val (label, hard) = target match
      case "user"    => ("~/.nebflow/User.md", UserHardBytes)
      case "agent"   => ("~/.nebflow/agents/Nebula/memory.md", AgentHardBytes)
      case "project" => (targetPath, ProjectHardBytes)
      case other     => (other, -1L)
    val pct = if hard > 0 then f"${newSizeBytes * 100.0 / hard}%.0f%%" else "?"
    s"""MemoryEdit: $action rejected — $label would reach $newSizeBytes bytes ($pct of the $hard-byte hard budget).
       |Budget is enforced on the WRITE side (injection is never truncated — over-budget memory silently degrades every future session instead).
       |Consolidate first, then write. Largest sections:
       |${topSections(newContent)}
       |Trim stale/duplicate entries via remove/replace_section (memory-consolidation skill), or demote detail into ~/.nebflow/memory/<id>.md files.
       |(MEMORYEDIT_BUDGET)""".stripMargin

  /** 80% 软警文案（追加在成功结果之后，放行不拦截）。project 维度真实路径由
    * targetPath 传入（缺省空串仅兼容旧调用方——Dream/全局侧不受影响）。 */
  def warnNotice(target: String, newSizeBytes: Long, targetPath: String = ""): String =
    val (label, soft, hard) = target match
      case "user"    => ("~/.nebflow/User.md", UserSoftBytes, UserHardBytes)
      case "agent"   => ("~/.nebflow/agents/Nebula/memory.md", AgentSoftBytes, AgentHardBytes)
      case "project" => (targetPath, ProjectSoftBytes, ProjectHardBytes)
      case other     => (other, -1L, -1L)
    s"""WARN: $label is now $newSizeBytes bytes (over the 80% soft line of $soft bytes; hard budget $hard).
       |Schedule a consolidation pass this turn or at the next lifecycle node — do not wait for the weekly audit. (MEMORYEDIT_BUDGET_WARN)"""

end MemoryBudget
