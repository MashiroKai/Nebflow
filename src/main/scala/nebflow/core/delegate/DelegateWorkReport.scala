package nebflow.core.delegate

import nebflow.core.GitWorktrees

import scala.util.Try

/**
 * DelegateWorkReport —— delegate 完工卡的合并提交 diff 统计（完工卡 diff 统计
 * 恢复批 2026-11-01，作者令：完工卡恢复该批的代码量变化标识）.
 *
 * 本件只承载 **[[mergeDiffStat]]** 读数（+新增 −删除 行数统计）——delegate 终态
 * 交付本体走父回投链的 payload，不经本件。
 */
object DelegateWorkReport:

  /**
   * 合并提交第一亲 diff 的行数统计（`commit` = 7 位短哈希，与卡片摘要哈希段同源
   * 同值——作者看到的两个数字恒指同一提交）。
   */
  final case class MergeDiffStat(commit: String, added: Long, removed: Long)

  /**
   * 本 delegate 批次**合并**的代码量变化（+新增 −删除）。锚定序：
   *   ① finish 申报（`reportDetail`）尾哈希——7-40 位 hex（可选括号包裹，与前端
   *      摘要哈希段同一提取口径），rev-parse 可解析且为**双亲提交**才认；
   *   ② 回落 = 基分支 tip（双亲 merge 才认——归属防护：绝不把他人落在基分支上的
   *      提交误归本 delegate）。
   * 统计 = `git diff --numstat <第一亲>..<merge>`（基工作区视角）加删行总和；
   * binary 行（`- -`）与畸形行不计。
   *
   * fail-soft 全包：任一环不可读 = None（卡面无 diff 块，绝不拖垮终态投递）。
   */
  def mergeDiffStat(rec: DelegateRegistry.Record): Option[MergeDiffStat] =
    Try {
      val ws = Try(os.Path(rec.projectWorkspace.getOrElse(rec.cwd))).toOption
      (ws, rec.baseBranch) match
        case (Some(repo), Some(base)) if GitWorktrees.isGitRepo(repo) =>
          val anchor =
            declaredMergeSha(repo, rec)
              .orElse(GitWorktrees.resolveRef(repo, base).flatMap(resolveTwoParent(repo, _)))
          anchor.flatMap { case (merge, p1) =>
            numstat(repo, p1, merge).map { case (added, removed) =>
              MergeDiffStat(commit = merge.take(7), added = added, removed = removed)
            }
          }
        case _ => None
    }.getOrElse(None)

  /**
   * 申报尾哈希提取（纯读取）：`reportDetail` trim 后的 7-40 位 hex 尾段（可选
   * `(`/`[` 包裹），rev-parse 解析为双亲提交才返回 `(merge, 第一亲)`。
   */
  private def declaredMergeSha(repo: os.Path, rec: DelegateRegistry.Record): Option[(String, String)] =
    rec.reportDetail.flatMap { detail =>
      val m = """[\(\[]?([0-9a-f]{7,40})[\)\]]?\s*$""".r.findFirstMatchIn(detail.trim)
      m.flatMap(mm => resolveTwoParent(repo, mm.group(1)))
    }

  /** sha/ref ⇒ 双亲提交则 `(自身, 第一亲)`，否则 None。 */
  private def resolveTwoParent(repo: os.Path, ref: String): Option[(String, String)] =
    GitWorktrees.commitInfo(repo, ref).collect {
      case ci if ci.parents.lengthCompare(2) >= 0 => (ci.sha, ci.parents.head)
    }

  /** `git diff --numstat from..to` 的加删行总和（基工作区视角）。fail-soft。 */
  private def numstat(repo: os.Path, from: String, to: String): Option[(Long, Long)] =
    Try {
      val res = os
        .proc("git", "-C", repo.toString, "diff", "--numstat", s"$from..$to")
        .call(check = false, mergeErrIntoOut = true)
      if res.exitCode != 0 then None
      else
        val (added, removed) = res.out.trim().linesIterator.foldLeft((0L, 0L)) { case ((sa, sr), line) =>
          line.split('\t').toList match
            case adds :: dels :: _ if adds.forall(_.isDigit) && dels.forall(_.isDigit) =>
              (sa + adds.toLong, sr + dels.toLong)
            case _ => (sa, sr) // binary（`- -`）/ 畸形行不计
        }
        Some((added, removed))
    }.getOrElse(None)

end DelegateWorkReport
