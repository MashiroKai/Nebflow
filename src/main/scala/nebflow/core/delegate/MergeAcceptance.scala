package nebflow.core.delegate

import nebflow.core.GitWorktrees

import scala.util.Try

/**
 * MergeAcceptance —— 合并轮终态的**机械验收闸**（引擎加固批 2026-10-09，上游审计
 * #31 结论 P1-1①）.
 *
 * 合并轮（mergeRound=true）delegate 报完成后、Release 前，引擎对 `git -C <baseWs>`
 * 做**确定性校验**（零 LLM、零自申报信任）：
 *   - a) 基分支 HEAD 存在**双亲 merge commit** 且第二亲 = delegate 分支头
 *     （rev-parse 校验——`git log -1 <baseTip>` 的父结构直读）；
 *   - b) `merge-base --is-ancestor <delegateBranch> <baseBranch>` 为真。
 *
 * 不满足 ⇒ 拒绝 Release、判词回落 blocked（报父）——**绝不凭自申报放行**。
 * 消费点两处：内部腿 [[DelegateTerminal.onCompleted]]（合并轮 completed 判词的
 * Release 前置闸）；外部腿 [[nebflow.core.executor.ExternalSessionRunner]] settle
 * （合并轮 resume 终态的 completed 父通知前置闸——同闸同判据）。
 *
 * 读数不可确证（ref 缺失 / git 失败）= 判 fail（fail-**closed**：合并轮的放行
 * 面与漂移闸的 fail-open 相反——放行的代价是交付丢失/假合并，宁可 blocked 留
 * 现场）。evidence 携带 baseTip / branchTip / 父结构 / 祖先判定四件证据，随
 * blocked 判词入 ChainStore 行（source=`merge-verify`）。
 */
object MergeAcceptance:

  /** 单次验收结论：`ok` + 证据行（blocked 判词 reason / 审计行共用文案）。 */
  final case class Result(ok: Boolean, evidence: String)

  /** 记录驱动的验收（锚 = 记录的 projectWorkspace|cwd + baseBranch + branch）。
    * 锚缺失（无记录 / 无分支 / 无基分支）= `Some(fail)`——合并轮语境下无法确证
    * 本身就是拒绝理由（调用方在合并轮语境才调本函数）。 */
  def verifyRecord(rec: Option[DelegateRegistry.Record]): Result =
    rec match
      case None => Result(false, "no registry record for the delegate — merge acceptance unverifiable")
      case Some(r) =>
        val ws = Try(os.Path(r.projectWorkspace.getOrElse(r.cwd))).toOption
        (ws, r.branch, r.baseBranch) match
          case (Some(baseWs), Some(branch), Some(baseBranch)) => verify(baseWs, baseBranch, branch)
          case _ =>
            Result(
              false,
              s"incomplete spawn binding (branch=${r.branch.getOrElse("-")}, baseBranch=${r.baseBranch.getOrElse("-")}, " +
                s"workspace=${r.projectWorkspace.getOrElse(r.cwd)}) — merge acceptance unverifiable"
            )

  /** 双路确定性校验（a 双亲 merge ＋第二亲=支头；b 祖先真）。任一路不可读 = fail。 */
  def verify(baseWs: os.Path, baseBranch: String, branch: String): Result =
    if !GitWorktrees.isGitRepo(baseWs) then
      Result(false, s"base workspace '$baseWs' is not a git repo — merge acceptance unverifiable")
    else
      val baseTip   = GitWorktrees.resolveRef(baseWs, baseBranch)
      val branchTip = GitWorktrees.resolveRef(baseWs, branch)
      val parents   = baseTip.flatMap(t => GitWorktrees.commitInfo(baseWs, t)).map(_.parents)
      // a) HEAD 双亲 merge commit 且第二亲 = delegate 分支头
      val twoParentMerged = (parents, branchTip) match
        case (Some(ps), Some(bt)) => ps.lengthCompare(2) >= 0 && ps.lift(1).contains(bt)
        case _                    => false
      // b) delegate 分支已是基分支祖先（merge 真落地的构造性判据）
      val ancestor = GitWorktrees.isAncestor(baseWs, branch, baseBranch)
      val evidence =
        s"baseTip(${baseBranch})=${baseTip.getOrElse("<unreadable>")} branchTip(${branch})=${branchTip.getOrElse("<unreadable>")} " +
          s"baseHeadParents=${parents.map(_.mkString("[", ",", "]")).getOrElse("<unreadable>")} " +
          s"secondParentIsBranchTip=$twoParentMerged isAncestor(branch→base)=${ancestor.fold("<unreadable>")(_.toString)}"
      Result(twoParentMerged && ancestor.contains(true), evidence)

end MergeAcceptance
