package nebflow.core.delegate

import nebflow.shared.NebflowLogger

import scala.util.Try

/**
 * WorktreeRescue —— worktree 清理前的 reports/evidence 抢救守卫（作者令
 * 2026-10-24「worktree 清理抢救 reports/evidence」）.
 *
 * 背景：canvas 报告卡指向 delegate worktree 内 `reports/<ts>_<topic>/…` 的
 * 报告；该文件若未并入 main 工作区，`git worktree remove` 连带报告物理丢失
 * 且不可恢复（作者 11:53 截图实证：报告卡报「不存在或被清理」）。
 *
 * 动作：删除 worktree 前，扫描其下 `reports/` 与 `evidence/` 两类路径的常规
 * 文件——凡主工作区同相对路径不存在的，先复制进主工作区（同相对路径、补父
 * 目录）；已存在的跳过并记审计行，绝不覆盖。其余路径与清理语义零改动。
 *
 * 纪律：
 *   - fail-soft 全包——任何一步失败只 WARN 继续，绝不抛、绝不阻断清理；
 *   - 幂等——只补缺失（`os.exists` 判定），worktree 保留重清零副作用；
 *   - 消费点唯一：[[PostMergeCleanup.run]] 的移除分支（kept 现场不经此——
 *     没有删除就没有丢失）。
 */
object WorktreeRescue:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.worktree-rescue")

  /** 抢救扫描的两类路径（worktree 根相对；任务书圈定，其余路径不碰）。 */
  val GuardedDirs: List[String] = List("reports", "evidence")

  /** 单次抢救账面：路径均为相对 worktree 根的仓库相对形式。 */
  final case class Report(rescued: List[String] = Nil, skipped: List[String] = Nil, failed: List[String] = Nil):
    def isEmpty: Boolean = rescued.isEmpty && skipped.isEmpty && failed.isEmpty

    /** 清理账本行的紧凑摘要（计数 + 抢救路径样本，前 3 个）。 */
    def summary: String =
      if isEmpty then ""
      else
        val sample = rescued.take(3).mkString(",")
        val more   = if rescued.size > 3 then s"…+${rescued.size - 3}" else ""
        s"rescue rescued=${rescued.size} skipped=${skipped.size} failed=${failed.size}" +
          (if rescued.nonEmpty then s" [$sample$more]" else "")

  /** 守卫入口：把 worktree 的 reports/evidence 抢救进主工作区。绝不抛。 */
  def rescue(ws: os.Path, worktree: String): Report =
    Try(rescue(ws, os.Path(worktree))).getOrElse(Report())

  def rescue(ws: os.Path, worktree: os.Path): Report =
    if !os.isDir(worktree) then Report()
    else
      val (rescued, skipped, failed) = GuardedDirs.foldLeft((List.empty[String], List.empty[String], List.empty[String])) {
        case ((r, s, f), dir) =>
          val root = worktree / dir
          if !os.isDir(root) then (r, s, f)
          else
            val files = Try(os.walk(root)).getOrElse(Nil).filter(p => os.isFile(p, followLinks = false))
            files.foldLeft((r, s, f)) { case ((r2, s2, f2), file) =>
              // 相对路径以 os.RelPath 参与拼装（`ws / rel`——String 形态含 `/`
              // 会被 os-lib 当单段拒收），仅账面/日志用其 String 形态。
              val rel = file.subRelativeTo(worktree)
              val dst = ws / rel
              Try {
                if os.exists(dst) then
                  logger.info(s"worktree rescue skipped (already in main workspace): $rel")
                  (r2, rel.toString :: s2, f2)
                else
                  os.copy.over(file, dst, createFolders = true)
                  logger.info(s"worktree rescue copied into main workspace: $rel")
                  (rel.toString :: r2, s2, f2)
              }.fold(
                e => {
                  logger.warn(s"worktree rescue failed for $rel (kept going): ${e.getMessage}")
                  (r2, s2, rel.toString :: f2)
                },
                identity
              )
            }
      }
      Report(rescued.reverse, skipped.reverse, failed.reverse)

end WorktreeRescue
