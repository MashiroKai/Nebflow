package nebflow.core.delegate

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.GitWorktrees
import nebflow.shared.NebflowLogger

import scala.util.Try

/**
 * DelegateWorkspace —— spawn 前的判定机执行腿（R6 worktree 判定 + S13 分支纪律；
 * worktree-judge-fifo 批 #1224 增补 §3.1；A5 步1（2026-10-08 评审
 * chain-n-247e206d §六 步1 + 作者三项裁定「①②组合、①先②后」）判否腿改轻量
 * worktree）.
 *
 * 语义（A5 步1 起）：
 *   - **判是/判否一律建分支 `delegate/<id>` ＋ 轻量 worktree**（`git worktree add
 *     -b`，[[GitWorktrees]]，抽自 NodeEditTool.createWorktreeFor 的公用 util）→
 *     delegate cwd = worktree 座位（基点 = spawn 时 HEAD）；
 *   - 判「会改文件」＝工作座位（交付提交落自己分支，终态可进合并轮）；判
 *     「纯读」＝**只读座位**（read-only seat：同一条建权路径，brief 行走只读
 *     变体——零领先提交终态降级 completed，不进合并轮，见
 *     [[DelegateTerminal.onCompleted]] 的降级判据）；
 *   - worktree 建败 → 回落 [[inPlaceBinding]]（项目内建分支，共享工作树 HEAD
 *     不动）——fail-open 合同不变：**派发永不因 git 面失败而拒绝**；
 *   - 分支/worktree 创建权在判定机——delegate 不自建（brief 一行申报，见
 *     [[workingBranchLine]]）；
 *   - 非 git 工作区 = 整腿跳过（返回 None）：无可判之 repo，「禁直改 main」在
 *     非 git 目录是空集。
 *
 * 机械强制力（A5 步1 的核心论证）：座位在 worktree 内后，模型自然形态的直改
 * 基分支（`git checkout <base> && … && git commit`）被 **git 本体拒绝**（同分支
 * 双检出禁令）——自然 commit 全落 `delegate/<id>`；残余绕过 = 蓄意 plumbing
 * （`update-ref` / `branch -f` / `git -C <基工作区>`）或建败回落座位。
 *
 * 判定机两层全败（[[DelegateJudge.DelegateJudgePort.decideWorktree]] = None）=
 * 按「判是」腿执行（fail-open 反转：宁可白建分支，不可丢分支纪律——会改文件的
 * delegate 无分支即破坏合并机制）。
 *
 * 回落腿（in-place）的机械保证取舍（原 §3.1 实现注记；A5 步1 后仅建败回落腿
 * 仍适用）：
 *   - (b)「引擎代为 checkout」被现实否决——共享工作树上并行在飞批的存在（本批
 *     派发令自证）使 checkout/checkout-back 的窗口必然踩踏他人未提交态；
 *   - (a) 采纳：`git branch delegate/<id>` ＋ 会话 brief 注入**申报工作分支与
 *     临时 index 提交配方**（`GIT_INDEX_FILE` plumbing——不触共享 HEAD/index 即可
 *     把改动提交到非检出分支）＋ 终态 judge 的 git 摘要含「当前分支 / 基分支领先
 *     提交 / 脏文件」，直改 main 的漂移在判词输入面可见；
 *   - 残余风险（模型无视申报）登记为已知限制。
 */
object DelegateWorkspace:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.workspace")

  /** 一条 spawn 期 git 绑定（判定机的产物，随 spawn 记账）。 */
  final case class Binding(
    branch: String,
    /** worktree 目录（判是/判否一律建；None = 建败回落腿——项目内分支）。 */
    worktree: Option[String],
    /** delegate 的实际工作座（worktree 腿 = worktree 路径；建败回落腿 = 原 workspace）。 */
    cwd: String,
    /** spawn 时基工作区（合并轮 `git -C` 目标）。 */
    baseWs: String,
    /** spawn 时基分支（合并轮 `merge --no-ff` 目标）。 */
    baseBranch: Option[String],
    /** spawn 时基 HEAD（审计锚）。 */
    baseSha: Option[String],
    /** brief 注入行（🔴 模型可见文案，§16 待过目；None = 建分支失败降级）。 */
    briefLine: Option[String],
    /**
     * 判定机的「会否改文件」裁决（引擎加固批 2026-10-09 审计两键之一；终端时
     * 只读座位诚实化的判据——P2-3）。`None` = 无绑定腿（非 git）/ 建败降级。
     */
    worktreeJudged: Option[Boolean] = None,
    /**
     * 判定腿（jev / small-judge / fail-open——值域见
     * [[DelegateJudge.JudgeLeg]]；fail-open = 两层全败按判是反转）。
     */
    judgeLeg: Option[String] = None
  )

  /**
   * 判定 + 建权执行（spawn 前单点）。`None` = 非 git 工作区（整腿跳过，spawn 照旧
   * 旧形态）。失败语义：判定失败 → 判是腿（fail-open 反转）；worktree 建失败 →
   * WARN + in-place 回落（判是/判否同回落，判否回落腿带原申报行文案）；分支建
   * 失败（罕见：并发撞名）→ Binding 照回但无 brief 行（git 面降级，不阻断
   * spawn——派发永不因 git 面失败而拒绝）。
   */
  def prepare(workspace: String, task: String, delegateId: String): IO[Option[Binding]] =
    IO.blocking {
      val ws = os.Path(workspace)
      if !GitWorktrees.isGitRepo(ws) then None else Some(ws)
    }.flatMap {
      case None => IO.pure(None)
      case Some(ws) =>
        val baseBranch = GitWorktrees.currentBranch(ws)
        val baseSha = GitWorktrees.headSha(ws)
        val branch = s"delegate/$delegateId"
        for
          // A5 步1：判是/判否一律走同一条建权路径（判否 = 只读座位——仅 brief 行
          // 走只读变体；零领先提交 ⇒ 终态降级 completed，不进合并轮）。判定机两层
          // 全败 = None ⇒ 按「判是」（fail-open 反转：宁可白建分支，不可丢分支
          // 纪律——会改文件的 delegate 无分支即破坏合并机制）。
          // 引擎加固批 2026-10-09：改走 detailed 面——裁决＋判定腿随绑定记账
          // （spawn 行 worktreeJudged/judgeLeg 两键的数据源）。
          judged <- DelegateJudge.DelegateJudgePort.decideWorktreeDetailed(task)
          changesFiles = judged.map(_.changesFiles).getOrElse(true)
          judgeLeg = judged.map(_.leg).getOrElse(DelegateJudge.JudgeLeg.FailOpen)
          binding <- IO
            .blocking {
              GitWorktrees.createWorktree(
                ws,
                dirBase = GitWorktrees.sanitizeRefName(delegateId),
                branch = branch,
                baseRef = "HEAD",
                label = s"delegate $delegateId",
                // 分支名固定 delegate/<id>（delegate 腿唯一与 legacy 不同的轴——
                // 目录候选挪位时分支不跟随；legacy 腿默认 true 逐字保持）。
                branchFollowsDir = false
              ) match
                case Right(created) =>
                  logger.info(
                    s"delegate $delegateId: worktree created at ${created.path} (branch=$branch, base=${baseBranch.getOrElse("?")})"
                  )
                  Binding(
                    branch,
                    worktree = Some(created.path.toString),
                    cwd = created.path.toString,
                    baseWs = workspace,
                    baseBranch = baseBranch,
                    baseSha = baseSha,
                    briefLine = Some(workingBranchLine(branch, baseBranch, worktree = true, readOnly = !changesFiles)),
                    worktreeJudged = Some(changesFiles),
                    judgeLeg = Some(judgeLeg)
                  )
                case Left(err) =>
                  logger.warn(
                    s"delegate $delegateId: worktree creation failed ($branch): $err — falling back to in-place branch"
                  )
                  inPlaceBinding(ws, workspace, branch, baseBranch, baseSha, delegateId, changesFiles, judgeLeg)
            }
            .map(b => Some(b): Option[Binding])
        yield binding
        end for
    }
  end prepare

  /** 建败回落腿：项目内建分支（共享 HEAD 不动）；分支再建失败 → 无 brief 行降级形态。 */
  private def inPlaceBinding(
    ws: os.Path,
    workspace: String,
    branch: String,
    baseBranch: Option[String],
    baseSha: Option[String],
    delegateId: String,
    worktreeJudged: Boolean,
    judgeLeg: String
  ): Binding =
    GitWorktrees.createBranch(ws, branch, "HEAD") match
      case Right(_) =>
        logger.info(
          s"delegate $delegateId: in-place branch created ($branch, base=${baseBranch.getOrElse("?")}) — shared HEAD untouched"
        )
        Binding(
          branch,
          worktree = None,
          cwd = workspace,
          baseWs = workspace,
          baseBranch = baseBranch,
          baseSha = baseSha,
          briefLine = Some(workingBranchLine(branch, baseBranch, worktree = false)),
          worktreeJudged = Some(worktreeJudged),
          judgeLeg = Some(judgeLeg)
        )
      case Left(err) =>
        logger.warn(
          s"delegate $delegateId: branch creation failed ($branch): $err — git binding degraded (no brief line)"
        )
        Binding(branch, None, workspace, workspace, baseBranch, baseSha, None)

  /**
   * brief 申报行（🔴 模型可见文案——§16 待过目标注：本批最小成稿，终稿归 N3）。
   * worktree 腿两个变体：工作座位（判是，交付提交）与只读座位（判否，A5 步1
   * 新增）；回落腿（worktree = false）携带临时 index 提交配方（机械保证取舍
   * (a)——见本件头注）。
   */
  def workingBranchLine(
    branch: String,
    baseBranch: Option[String],
    worktree: Boolean,
    readOnly: Boolean = false
  ): String =
    val base = baseBranch.getOrElse("the base branch")
    val mergeDiscipline =
      " When the work on this branch is complete and ready to land, call delegate_report(category=\"merge\", detail=…) — " +
        "you will receive a merge reminder when it is your turn; do NOT merge on your own initiative."
    if worktree && readOnly then
      s"[working branch] $branch — read-only seat: your worktree sits on this branch, but this task is judged read-only — do NOT create commits; " +
        s"with zero commits ahead of $base the merge queue never engages and the task simply completes when done. NEVER commit directly to $base."
    else if worktree then
      s"[working branch] $branch — your worktree sits on this branch; commit your deliverable commits here. NEVER commit directly to $base.$mergeDiscipline"
    else
      s"[working branch] $branch — the shared checkout stays on $base, so land your changes on YOUR branch only: " +
        "commit with a temporary index (GIT_INDEX_FILE=<tmp> git add -A && GIT_INDEX_FILE=<tmp> git write-tree && " +
        "git commit-tree <tree> -p <parent> && git update-ref refs/heads/" + branch + " <new-commit>), or ask the engine to move them at terminal time. " +
        s"NEVER commit directly to $base; direct base-branch drift is surfaced to the judge.$mergeDiscipline"

  end workingBranchLine

  /**
   * spawn 行的 ChainStore payload（`delegate-spawn` 行；N1/#1228 的数据源契约）。
   * jev-plugins 批 #1226 增分配摘要两键（additive，缺省不落键——旧行字节不变）：
   * `plugins` = 分配集；`allocation` = 三态模式词（jev / llm-fallback / fail-open）。
   * 引擎加固批 2026-10-09 再增判定审计两键（同 additive 纪律——无绑定/缺裁决 =
   * 不落键）：`worktreeJudged` = 「会否改文件」裁决；`judgeLeg` = 判定腿
   * （jev / small-judge / fail-open）。
   */
  def spawnPayload(
    delegateId: String,
    executor: String,
    project: Option[String],
    b: Option[Binding],
    plugins: Option[List[String]] = None,
    allocation: Option[String] = None
  ): Json =
    io.circe.Json.fromJsonObject(
      io.circe.JsonObject.fromIterable(
        List(
          "delegateId" -> delegateId.asJson,
          "executor" -> executor.asJson,
          "project" -> project.getOrElse("").asJson,
          "branch" -> b.map(_.branch).getOrElse("").asJson,
          "worktree" -> b.flatMap(_.worktree).getOrElse("").asJson,
          "cwd" -> b.map(_.cwd).getOrElse("").asJson,
          "baseWorkspace" -> b.map(_.baseWs).getOrElse("").asJson,
          "baseBranch" -> b.flatMap(_.baseBranch).getOrElse("").asJson,
          "baseSha" -> b.flatMap(_.baseSha).getOrElse("").asJson
        ) ++ plugins.toList.map(p => "plugins" -> p.asJson)
          ++ allocation.toList.map(a => "allocation" -> a.asJson)
          ++ b.flatMap(_.worktreeJudged).map(j => "worktreeJudged" -> j.asJson)
          ++ b.flatMap(_.judgeLeg).map(l => "judgeLeg" -> l.asJson)
      )
    )

end DelegateWorkspace
