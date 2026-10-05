package nebflow.core.delegate

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.GitWorktrees
import nebflow.shared.NebflowLogger

import scala.util.Try

/**
 * DelegateWorkspace —— spawn 前的判定机执行腿（R6 worktree 判定 + S13 分支纪律；
 * worktree-judge-fifo 批 #1224 增补 §3.1）.
 *
 * 语义（增补件逐条）：
 *   - **无论判否一律建分支 `delegate/<id>`**（基点 = spawn 时 HEAD）；
 *   - 判是 → `git worktree add -b delegate/<id>`（[[GitWorktrees]]，抽自
 *     NodeEditTool.createWorktreeFor 的公用 util）→ delegate cwd = worktree；
 *   - 判否 → 项目内建分支（共享工作树 HEAD 不动）→ delegate cwd 不变；
 *   - 分支/worktree 创建权在判定机——delegate 不自建（brief 一行申报，见
 *     [[workingBranchLine]]）；
 *   - 非 git 工作区 = 整腿跳过（返回 None）：无可判之 repo，「禁直改 main」在
 *     非 git 目录是空集。
 *
 * 判定机两层全败（[[DelegateJudge.DelegateJudgePort.decideWorktree]] = None）=
 * 按「判否」腿执行（fail-open：分支纪律照常生效，只省 worktree）。
 *
 * 无 worktree 腿的机械保证取舍（增补件 §3.1 实现注记，交付回报注明）：
 *   - (b)「引擎代为 checkout」被现实否决——共享工作树上并行在飞批的存在（本批
 *     派发令自证）使 checkout/checkout-back 的窗口必然踩踏他人未提交态；
 *   - (a) 采纳：`git branch delegate/<id>` ＋ 会话 brief 注入**申报工作分支与
 *     临时 index 提交配方**（`GIT_INDEX_FILE` plumbing——不触共享 HEAD/index 即可
 *     把改动提交到非检出分支）＋ 终态 judge 的 git 摘要含「当前分支 / 基分支领先
 *     提交 / 脏文件」，直改 main 的漂移在判词输入面可见；
 *   - 残余风险（模型无视申报）登记为已知限制；更硬的机械化（判否也建轻量
 *     worktree）按增补件留作升级路径，本批不启用（会抹平 R6 判定的区分度）。
 */
object DelegateWorkspace:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.workspace")

  /** 一条 spawn 期 git 绑定（判定机的产物，随 spawn 记账）。 */
  final case class Binding(
      branch: String,
      /** worktree 目录（判是腿）；判否腿 = None。 */
      worktree: Option[String],
      /** delegate 的实际工作座（判是腿 = worktree 路径；判否腿 = 原 workspace）。 */
      cwd: String,
      /** spawn 时基工作区（合并轮 `git -C` 目标）。 */
      baseWs: String,
      /** spawn 时基分支（合并轮 `merge --no-ff` 目标）。 */
      baseBranch: Option[String],
      /** spawn 时基 HEAD（审计锚）。 */
      baseSha: Option[String],
      /** brief 注入行（🔴 模型可见文案，§16 待过目；None = 建分支失败降级）。 */
      briefLine: Option[String]
  )

  /**
   * 判定 + 建权执行（spawn 前单点）。`None` = 非 git 工作区（整腿跳过，spawn 照旧
   * 旧形态）。失败语义：判定失败 → 判否腿；worktree 建失败 → WARN + 判否腿；
   * 分支建失败（罕见：并发撞名）→ Binding 照回但无 brief 行（git 面降级，不阻断
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
          // 判定机两层全败 = None ⇒ 按判否（fail-open；分支纪律不缺席）。
          worktreeYes <- DelegateJudge.DelegateJudgePort.decideWorktree(task).map(_.getOrElse(false))
          binding <- IO.blocking {
            if worktreeYes then
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
                  logger.info(s"delegate $delegateId: worktree created at ${created.path} (branch=$branch, base=${baseBranch.getOrElse("?")})")
                  Binding(
                    branch,
                    worktree = Some(created.path.toString),
                    cwd = created.path.toString,
                    baseWs = workspace,
                    baseBranch = baseBranch,
                    baseSha = baseSha,
                    briefLine = Some(workingBranchLine(branch, baseBranch, worktree = true))
                  )
                case Left(err) =>
                  logger.warn(s"delegate $delegateId: worktree creation failed ($branch): $err — falling back to in-place branch")
                  inPlaceBinding(ws, workspace, branch, baseBranch, baseSha, delegateId)
            else inPlaceBinding(ws, workspace, branch, baseBranch, baseSha, delegateId)
          }
        yield Some(binding)
    }
  end prepare

  /** 判否腿：项目内建分支（HEAD 不动）；建失败 → branch=None 形态（无 brief 行）。 */
  private def inPlaceBinding(
      ws: os.Path,
      workspace: String,
      branch: String,
      baseBranch: Option[String],
      baseSha: Option[String],
      delegateId: String
  ): Binding =
    GitWorktrees.createBranch(ws, branch, "HEAD") match
      case Right(_) =>
        logger.info(s"delegate $delegateId: in-place branch created ($branch, base=${baseBranch.getOrElse("?")}) — shared HEAD untouched")
        Binding(
          branch,
          worktree = None,
          cwd = workspace,
          baseWs = workspace,
          baseBranch = baseBranch,
          baseSha = baseSha,
          briefLine = Some(workingBranchLine(branch, baseBranch, worktree = false))
        )
      case Left(err) =>
        logger.warn(s"delegate $delegateId: branch creation failed ($branch): $err — git binding degraded (no brief line)")
        Binding(branch, None, workspace, workspace, baseBranch, baseSha, None)

  /**
   * brief 申报行（🔴 模型可见文案——§16 待过目标注：本批最小成稿，终稿归 N3）。
   * 判否腿携带临时 index 提交配方（机械保证取舍 (a)——见本件头注）。
   */
  def workingBranchLine(branch: String, baseBranch: Option[String], worktree: Boolean): String =
    val base = baseBranch.getOrElse("the base branch")
    if worktree then
      s"[working branch] $branch — your worktree is on this branch; commit here. NEVER commit directly to $base."
    else
      s"[working branch] $branch — the shared checkout stays on $base, so land your changes on YOUR branch only: " +
        "commit with a temporary index (GIT_INDEX_FILE=<tmp> git add -A && GIT_INDEX_FILE=<tmp> git write-tree && " +
        "git commit-tree <tree> -p <parent> && git update-ref refs/heads/" + branch + " <new-commit>), or ask the engine to move them at terminal time. " +
        s"NEVER commit directly to $base; direct base-branch drift is surfaced to the judge."

  /** spawn 行的 ChainStore payload（`delegate-spawn` 行；N1/#1228 的数据源契约）。 */
  def spawnPayload(delegateId: String, executor: String, project: Option[String], b: Option[Binding]): Json =
    Json.obj(
      "delegateId" -> delegateId.asJson,
      "executor" -> executor.asJson,
      "project" -> project.getOrElse("").asJson,
      "branch" -> b.map(_.branch).getOrElse("").asJson,
      "worktree" -> b.flatMap(_.worktree).getOrElse("").asJson,
      "cwd" -> b.map(_.cwd).getOrElse("").asJson,
      "baseWorkspace" -> b.map(_.baseWs).getOrElse("").asJson,
      "baseBranch" -> b.flatMap(_.baseBranch).getOrElse("").asJson,
      "baseSha" -> b.flatMap(_.baseSha).getOrElse("").asJson
    )

end DelegateWorkspace
