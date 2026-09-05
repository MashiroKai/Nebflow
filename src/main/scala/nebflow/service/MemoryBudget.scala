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

  /** target 标识（MemoryEdit 的 "user"/"agent"；Dream 固定写 User.md → "user"）。 */
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

  /** 超限拒绝消息体（MemoryEdit 包装为 MEMORYEDIT_BUDGET；Dream 侧写日志）。 */
  def exceededMessage(action: String, target: String, targetPath: String, newSizeBytes: Long, newContent: String): String =
    val (label, hard) = target match
      case "user"  => ("~/.nebflow/User.md", UserHardBytes)
      case "agent" => ("~/.nebflow/agents/Nebula/memory.md", AgentHardBytes)
      case other   => (other, -1L)
    val pct = if hard > 0 then f"${newSizeBytes * 100.0 / hard}%.0f%%" else "?"
    s"""MemoryEdit: $action rejected — $label would reach $newSizeBytes bytes ($pct of the $hard-byte hard budget).
       |Budget is enforced on the WRITE side (injection is never truncated — over-budget memory silently degrades every future session instead).
       |Consolidate first, then write. Largest sections:
       |${topSections(newContent)}
       |Trim stale/duplicate entries via remove/replace_section (memory-consolidation skill), or demote detail into ~/.nebflow/memory/<id>.md files.
       |(MEMORYEDIT_BUDGET)""".stripMargin

  /** 80% 软警文案（追加在成功结果之后，放行不拦截）。 */
  def warnNotice(target: String, newSizeBytes: Long): String =
    val (label, soft, hard) = target match
      case "user"  => ("~/.nebflow/User.md", UserSoftBytes, UserHardBytes)
      case "agent" => ("~/.nebflow/agents/Nebula/memory.md", AgentSoftBytes, AgentHardBytes)
      case other   => (other, -1L, -1L)
    s"""WARN: $label is now $newSizeBytes bytes (over the 80% soft line of $soft bytes; hard budget $hard).
       |Schedule a consolidation pass this turn or at the next lifecycle node — do not wait for the weekly audit. (MEMORYEDIT_BUDGET_WARN)"""

end MemoryBudget
