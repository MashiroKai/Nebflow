package nebflow.service

import cats.effect.IO
import nebflow.core.NebflowLogger

/**
 * 记忆落盘单点闸（M4 —— 工具面收敛设计 §4 Q2-④ ④-b / M2 表 M4 行；作者 2026-09-13
 * 裁定「M4 闸下沉 = 独立小批立刻立项」）。
 *
 * 动机：预算闸与写前快照闸此前的**实现在调用方**（旧 MemoryEdit 路径内 / Dream hook 内），
 * 而落盘单点 [[MemoryStore.saveUserMemory]] / [[MemoryStore.saveAgentMemory]] 与
 * `ProjectMemory.save` 自身**零闸** ⇒ 不经调用方闸的写入没有任何闸。今天生产面上唯一的
 * 这类写入 = WS `saveMemory` 旁路（`WebSocketRoutes`），它直调 `MemoryStore.save*`。
 * 本对象把两道闸下沉到落盘单点，使「过闸」成为**落盘的必要条件**，而不是调用方的自觉。
 *
 * 边界（写死，越界即不合）：
 *   - **不覆盖** Write / Edit / Bash 直写路径（Bash 完备 ⇒ 无机械封堵；本对象**不声称**
 *     覆盖它们，也不为此加壳、不改工具面）；
 *   - **不退役** MemoryEdit（Q2 组已落「保留」）；
 *   - **不动**授能面 / 静态集 / 注册表（非本项范围）。
 *
 * 闸序 = **快照 → 预算 → 落盘**。理由：
 *   ① 快照的既有纪律就是「快照先行 = 硬护栏」（写前备份是写的前置条件）；
 *   ② 被预算拒的那一次写入**也留下当前盘上真身的快照**（拒绝不减少回滚锚）；
 *   ③ 与旧 MemoryEdit 路径的次序（预算 → 快照，拒绝时不快照）**不同**，这是有意的：
 *      本项正控要求「预算超限 ⇒ 被拒绝，且产生快照」。
 *
 * 判据来源（零新语义）：硬顶 / 软线一律取自 [[MemoryBudget]]（唯一常量源），本对象
 * 不复制数值、不新增阈值、不改判据函数。
 *
 * 可见出口（不引入任何新的静默路径）：
 *   - 超硬顶 ⇒ **结构化拒绝**（[[Rejected]] + [[Code.Budget]]，文本含硬顶 / 实际字节 /
 *     top-3 最大节 / 整理出路）——与工具侧的既有处置同形（结构化拒绝）；
 *   - 快照失败 ⇒ **结构化拒绝**（[[Code.Snapshot]]），**零写入**（fail-closed）——与
 *     hook 侧「快照失败 ⇒ 跳过合并零写入」同形；
 *   - 超软线未超硬顶 ⇒ **放行**，并落一条带 [[WarnMarker]] 的结构化 WARN（日志可判）。
 *
 * 调用方义务：[[Rejected]] 走 **IO 错误通道**（不 handle 即传播）——调用方不得把它吞成
 * 静默 no-op；需要面向用户的措辞时按 `code` 分派（见 `WebSocketRoutes` 的 `saveMemory`
 * 错误帧）。
 */
object MemoryWriteGate:

  private val logger = NebflowLogger.forName("nebflow.memory.writegate")

  /** 稳定码（结构化拒绝的机器可判标识）。 */
  object Code:
    /** 超硬顶：拒绝。 */
    val Budget: String = "MEMORYSTORE_BUDGET"
    /** 写前快照失败：拒绝（fail-closed，零写入）。 */
    val Snapshot: String = "MEMORYSTORE_SNAPSHOT"

  /** 软线放行的日志标记（可 grep；与结构化拒绝同族的可判出口）。 */
  val WarnMarker: String = "MEMORYSTORE_BUDGET_WARN"

  /** 落盘单点闸的结构化拒绝。`code` 面向机器（可判），`detail` 面向人（可行动）。 */
  final class Rejected(val code: String, val detail: String)
      extends RuntimeException(s"MemoryWriteGate: rejected [$code] — $detail")

  /** 过闸判定（fail-closed）。`Right` = 允许落盘；`Left` = 调用方**必须中止**（零写入）。
    *
    * 纯于 IO 之外（除快照与日志两处副作用），便于 spec 直测；生产入口见 [[guard]]。 */
  def decide(target: String, path: os.Path, newContent: String): Either[Rejected, Unit] =
    MemorySnapshot.snapshotBeforeWrite(path) match
      case Left(reason) =>
        // 闸 1（fail-closed）：无快照不落笔。零写入由「调用方中止」保证（本对象不落盘）。
        Left(Rejected(Code.Snapshot, snapshotDetail(target, path, reason)))
      case Right(_) =>
        // 闸 2：预算（超硬顶 = 结构化拒绝；超软线 = 放行 + 可见 WARN）。
        // 判据是【新内容的总字节】——落盘单点拿到的就是整文件新内容。
        val bytes = newContent.getBytes(java.nio.charset.StandardCharsets.UTF_8).length.toLong
        MemoryBudget.verdict(target, bytes) match
          case MemoryBudget.Exceeded(_, hard) =>
            Left(Rejected(Code.Budget, budgetDetail(target, path, bytes, hard, newContent)))
          case MemoryBudget.Warn(_, soft, hard) =>
            logger.warnSync(warnDetail(target, path, bytes, soft, hard))
            Right(())
          case MemoryBudget.Within =>
            Right(())

  /** IO 形态：`Left` ⇒ `raiseError`（错误沿 IO 通道向上；调用方无法在不知不觉中吞掉，
    * 与旧路径 `Either[ToolError, …]` 的「结构化拒绝」同形）。 */
  def guard(target: String, path: os.Path, newContent: String): IO[Unit] =
    IO.blocking(decide(target, path, newContent)).flatMap {
      case Right(()) => IO.unit
      case Left(err) => IO.raiseError(err)
    }

  // ---------------------------------------------------------------
  // 文案（调用方按 code 分派；正文自含「是什么 / 期望是什么 / 怎么修」）
  // ---------------------------------------------------------------

  private def snapshotDetail(target: String, path: os.Path, reason: String): String =
    s"""the pre-write snapshot failed ($reason), so nothing was written — $path is untouched.
       |The copy would have gone to ${MemorySnapshot.backupRoot} (target='$target').
       |Check space/permissions on that directory, then retry. (${Code.Snapshot})""".stripMargin

  private def budgetDetail(target: String, path: os.Path, bytes: Long, hard: Long, newContent: String): String =
    val pct = if hard > 0 then f"${bytes * 100.0 / hard}%.0f%%" else "?"
    val label = target match
      case "user"    => "~/.nebflow/User.md"
      case "agent"   => "~/.nebflow/agents/Nebula/memory.md"
      case other     => path.toString
    s"""$path would reach $bytes bytes ($pct of the $hard-byte hard budget for target='$target'), so the write was refused; $label is untouched.
       |Budget is enforced on the WRITE side (injection is never truncated — an over-budget memory taxes every future session instead).
       |Consolidate first, then write. Largest sections:
       |${MemoryBudget.topSections(newContent)}
       |Trim stale/duplicate entries, or demote detail into ~/.nebflow/memory/<id>.md files. (${Code.Budget})""".stripMargin

  private def warnDetail(target: String, path: os.Path, bytes: Long, soft: Long, hard: Long): String =
    s"$WarnMarker target='$target' $path is now $bytes bytes (over the 80% soft line of $soft bytes; hard budget $hard) — " +
      "the write was allowed. Schedule a consolidation pass this turn or at the next lifecycle node."

end MemoryWriteGate
