package nebflow.core.delegate

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.GitWorktrees
import nebflow.shared.NebflowLogger

/**
 * DelegateWorkspace —— spawn 前的 **worktree 分配腿**（参数决定论；delegate-param-decisions
 * 批 2026-11-04 · 作者令「把 delegate 的两个小模型决策点改成编排方直接填的参数」）.
 *
 * == 语义（作者令 2026-11-04 20:58 + 21:03 补充令）==
 *   - **`worktree` 参数缺省 ⇒ 零分配**：一棵树都不建、不复制，delegate 就地坐在项目
 *     工作区（无 project 时 = 默认 general 工作区）里跑。这条通道是**只读任务的专用
 *     通道**——会写文件的任务必须填 `worktree`（工具描述里写死，见 [[DelegateTool]]）。
 *   - **`worktree = "<name>"` ⇒ 按名建树/按名复用**：`<基工作区>/.nebflow/worktrees/<name>`，
 *     分支 `delegate/<name>`；目录已存在（同 name 的既有树）⇒ 复用同一棵树，绝不建
 *     第二个目录。
 *   - **旧的解析兜底保留为次选**（delegate id / 目录名 / 绝对路径，见
 *     [[WorktreeCandidates.resolveNamed]]）；命中哪条规则由 [[Binding.seatSource]]
 *     记录并写进派发回执（不许静默）。
 *
 * == 本批删掉的东西 ==
 * 判定机（worktree judge：Jev 问句 → SmallJudge LLM 兜底 → fail-open 判是）**整条
 * 删除**——它曾在 spawn 路径上用一次模型调用决定「分配不分配树」。现在分配与否
 * 完全由参数决定，spawn 路径上零模型调用。连带作废的概念：判否「只读座位」变体
 * （`worktreeJudged` 裁决）；只读性不再是模型的裁决，而是**参数的适用面**。
 *
 * == 保留的失败语义（fail-open 合同不变）==
 *   - 非 git 工作区 ⇒ 就地腿返回 None（无可建之树，派发照旧）；
 *   - 按名建树失败 ⇒ WARN + 就地回落（[[SeatSource.Fallback]] 标红回执，不静默）；
 *   - 分支建失败（罕见：并发撞名）⇒ Binding 照回但无 brief 行（git 面降级，不阻断）。
 *
 * == 就地腿的机械护栏（补充令第 2 条）==
 * 就地腿在 spawn 时记快照（HEAD + `git status --porcelain` 路径集），随记录落盘；
 * 收尾时由 [[DelegateTerminal.inPlaceWriteAlarm]] 对比——「没分配树却动了工作区」
 * 必须在回执里标红，不许静默通过。
 */
object DelegateWorkspace:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.workspace")

  /**
   * 座位来源词表（派发回执 + spawn 行 `seat` 键的值域）。**已命中哪条规则必须报
   * 出来**（作者令：「命中哪条要写进派发回执，不许静默」）。
   */
  object SeatSource:
    /** `worktree` 参数缺省 ⇒ 零分配，就地跑道（只读任务通道）。 */
    val InPlace: String = "none"

    /** 精确 name 命中：`<worktreeRoot>/<name>` 已存在，复用同一棵树。 */
    val ExactName: String = "exact-name"

    /** 旧口径命中（delegate id / 目录名 / 绝对路径——次选兜底）。 */
    val Legacy: String = "legacy"

    /** 按 `worktree` 参数给的名字新建。 */
    val Created: String = "created"

    /** 名字给定但建树失败 ⇒ 就地回落（fail-open；回执必须标出）。 */
    val Fallback: String = "fallback"

    val All: List[String] = List(InPlace, ExactName, Legacy, Created, Fallback)
  end SeatSource

  /** 一条 spawn 期 git 绑定（分配腿的产物，随 spawn 记账）。 */
  final case class Binding(
    branch: String,
    /** worktree 目录（None = 无树：就地腿 / 建败回落腿 / 非 git）。 */
    worktree: Option[String],
    /** delegate 的实际工作座（worktree 腿 = 树路径；就地腿 = 原 workspace）。 */
    cwd: String,
    /** spawn 时基工作区（合并轮 `git -C` 目标）。 */
    baseWs: String,
    /** spawn 时基分支（合并轮 `merge --no-ff` 目标）。 */
    baseBranch: Option[String],
    /** spawn 时基 HEAD（审计锚）。 */
    baseSha: Option[String],
    /** brief 注入行（🔴 模型可见文案；None = 建分支失败降级）。 */
    briefLine: Option[String],
    /** 座位来源（[[SeatSource]]；回执与 spawn 行 `seat` 键的数据源）。 */
    seatSource: String = SeatSource.InPlace,
    /** `worktree` 参数原文（None = 未填——零分配腿）。 */
    seatName: Option[String] = None,
    /** 续用腿的来源标记：接续自哪个既有 worktree（delegate id / 目录名）。 */
    reusedFrom: Option[String] = None,
    /** 就地腿的派发时快照：HEAD（收尾护栏的对比基线）。 */
    snapshotSha: Option[String] = None,
    /** 就地腿的派发时快照：`git status --porcelain` 路径集。 */
    snapshotDirty: Option[List[String]] = None
  )

  /**
   * **零分配腿**（`worktree` 参数缺省）：不建树、不复制——只建 `delegate/<id>`
   * 分支（共享 HEAD 不动）供「万一真要落提交」用，brief 明确申报「本座位是共享
   * 检出区的就地跑道，只读任务才能省略 worktree」。
   *
   * `None` = 非 git 工作区（整腿跳过，spawn 照旧）。
   */
  def prepareInPlace(workspace: String, delegateId: String): IO[Option[Binding]] =
    IO.blocking {
      val ws = os.Path(workspace)
      if !GitWorktrees.isGitRepo(ws) then None
      else Some(ws)
    }.flatMap {
      case None => IO.pure(None)
      case Some(ws) =>
        IO.blocking {
          val branch = s"delegate/$delegateId"
          val baseBranch = GitWorktrees.currentBranch(ws)
          val baseSha = GitWorktrees.headSha(ws)
          inPlaceBinding(ws, workspace, branch, baseBranch, baseSha, delegateId).copy(
            seatSource = SeatSource.InPlace,
            snapshotSha = baseSha,
            snapshotDirty = GitWorktrees.dirtyPaths(ws).orElse(Some(Nil))
          )
        }.map(b => Some(b): Option[Binding])
    }

  /**
   * **按名建树腿**（`worktree = "<name>"`）：`<基工作区>/.nebflow/worktrees/<name>`，
   * 分支 `delegate/<name>`（作者令：填的内容就是 worktree 的 name）。
   *
   * `Left` = 工作区不是 git 仓 / 建树失败——调用方决定是显式拒绝还是就地回落
   * （[[nebflow.core.tools.DelegateTool]] 的语义：名字是参数，不是模型裁决 ⇒ 建
   * 失败标 [[SeatSource.Fallback]] 回落并标红回执，绝不静默）。
   */
  def createNamed(workspace: String, name: String, delegateId: String): IO[Either[String, Binding]] =
    IO.blocking {
      val ws = os.Path(workspace)
      if !GitWorktrees.isGitRepo(ws) then
        Left(s"workspace '$workspace' is not a git repository — no worktree can be created for name '$name'")
      else
        val (baseline, commit) = WorkspaceLedger.resolveBaseline(ws)
        val baseBranch = Some(baseline)
        val baseSha = Some(commit)
        val branch = s"delegate/$name"
        GitWorktrees.createNamedWorktree(ws, name, branch, commit) match
          case Right(created) =>
            logger.info(
              s"delegate $delegateId: worktree '$name' created at ${created.path} (branch=$branch, base=${baseBranch.getOrElse("?")})"
            )
            Right(
              Binding(
                branch,
                worktree = Some(created.path.toString),
                cwd = created.path.toString,
                baseWs = workspace,
                baseBranch = baseBranch,
                baseSha = baseSha,
                briefLine = Some(workingBranchLine(branch, baseBranch, worktree = true, seatName = Some(name))),
                seatSource = SeatSource.Created,
                seatName = Some(name)
              )
            )
          case Left(err) =>
            logger.warn(s"delegate $delegateId: worktree creation failed for name '$name' ($branch): $err")
            Left(err)
        end match
      end if
    }

  /**
   * **复用既有树腿**：座位直接落在一棵**既有** worktree 上（精确 name 命中或旧口径
   * 命中），不新建任何树/分支。
   *
   * 与新建腿的差异：分支 = 该树当前检出的分支；合并轮的基分支/基 SHA = 基工作区
   * 读数（树内 HEAD 是交付点，不是合并目标）；brief 行申报「你接着上一任的活干」。
   *
   * ⚠ 反错配闸留在解析面（[[WorktreeCandidates.resolveNamed]] 已拒「被活会话持有」
   * 的树，除非显式 `force`）；本腿只负责「绑定形态正确」。
   */
  def reuse(
    workspace: String,
    delegateId: String,
    candidate: WorktreeCandidates.Candidate,
    source: String
  ): IO[Binding] =
    IO.blocking {
      val baseWs = os.Path(workspace)
      val baseBranch = GitWorktrees.currentBranch(baseWs).orElse(candidate.baseBranch)
      val baseSha = GitWorktrees.headSha(baseWs)
      val branch = candidate.branch.getOrElse(s"delegate/$delegateId")
      logger.info(
        s"delegate $delegateId: REUSING worktree ${candidate.path} via $source (branch=$branch, " +
          s"dirty=${candidate.dirty}, ahead=${candidate.aheadOfBase}, status=${candidate.status})"
      )
      Binding(
        branch,
        worktree = Some(candidate.path),
        cwd = candidate.path,
        baseWs = workspace,
        baseBranch = baseBranch,
        baseSha = baseSha,
        briefLine = Some(reuseBriefLine(branch, candidate)),
        seatSource = source,
        seatName = Some(candidate.dirName),
        reusedFrom = Some(candidate.delegateId.getOrElse(candidate.dirName))
      )
    }

  /**
   * 复用腿的 brief 申报行（🔴 模型可见文案）。
   *
   * **继承事实申报**：树上已有的未提交改动 / 领先提交是上一任留下的，模型必须
   * 「接着干」而不是「重做」。
   */
  def reuseBriefLine(branch: String, candidate: WorktreeCandidates.Candidate): String =
    val inherited = List(
      Option.when(candidate.aheadOfBase > 0)(s"${candidate.aheadOfBase} commit(s) ahead of the base branch"),
      Option.when(candidate.dirty > 0)(s"${candidate.dirty} uncommitted path(s)")
    ).flatten
    val inheritedText =
      if inherited.isEmpty then "The tree is clean (no inherited uncommitted work, nothing ahead)."
      else
        s"This tree was INHERITED from an earlier session (${candidate.delegateId.getOrElse(candidate.dirName)}): " +
          s"it already carries ${inherited.mkString(" and ")}. Continue that work — read the existing changes before you edit, " +
          "build on them rather than redoing them, and do not discard them." +
          (if candidate.dirtySample.nonEmpty then s" Uncommitted sample: ${candidate.dirtySample.mkString(", ")}."
           else "")
    s"[working branch] $branch — your worktree is a REUSED seat on this branch. $inheritedText " +
      "Commit your deliverable commits here. When the work on this branch is complete and ready to land, " +
      "say so in your final text and state what sits on the branch — the merge request is the requester's " +
      "face, and you will receive a merge reminder when it is your turn; " +
      "do NOT merge on your own initiative. NEVER commit directly to the base branch."

  end reuseBriefLine

  /** 建败回落腿：项目内建分支（共享 HEAD 不动）；分支再建失败 → 无 brief 行降级形态。 */
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
        logger.info(
          s"delegate $delegateId: in-place branch created ($branch, base=${baseBranch.getOrElse("?")}) — shared HEAD untouched, no worktree allocated"
        )
        Binding(
          branch,
          worktree = None,
          cwd = workspace,
          baseWs = workspace,
          baseBranch = baseBranch,
          baseSha = baseSha,
          briefLine = Some(workingBranchLine(branch, baseBranch, worktree = false)),
          seatSource = SeatSource.InPlace
        )
      case Left(err) =>
        logger.warn(
          s"delegate $delegateId: branch creation failed ($branch): $err — git binding degraded (no brief line)"
        )
        Binding(branch, None, workspace, workspace, baseBranch, baseSha, None, SeatSource.InPlace)

  /**
   * brief 工作座申报行（🔴 模型可见文案）。
   *
   * 三个变体：按名建树（工作座位，交付提交）/ 就地跑道（**零分配——只读任务专用**，
   * 携带临时 index 提交配方）/ 复用树（见 [[reuseBriefLine]]）。
   */
  def workingBranchLine(
    branch: String,
    baseBranch: Option[String],
    worktree: Boolean,
    seatName: Option[String] = None
  ): String =
    val base = baseBranch.getOrElse("the base branch")
    val mergeDiscipline =
      " When the work on this branch is complete and ready to land, say so in your final text and state what sits on the branch — " +
        "the merge request is the requester's face, and you will receive a merge reminder when it is your turn; do NOT merge on your own initiative."
    if worktree then
      val nameNote = seatName.map(n => s" (worktree name '$n')").getOrElse("")
      s"[working branch] $branch$nameNote — your worktree sits on this branch; commit your deliverable commits here. NEVER commit directly to $base.$mergeDiscipline"
    else
      s"[working branch] $branch — NO worktree was allocated for this session: the dispatch omitted the `worktree` parameter, " +
        "which is the READ-ONLY channel, so you run IN PLACE in the shared project workspace (other live sessions are editing the same " +
        "checkout right now). Do NOT write, create, delete or move files here — if this task really needs writes, STOP and report that " +
        "the dispatch was misconfigured instead of editing. The shared checkout stays on " + base + ", so exactly-landed work must sit on " +
        "YOUR branch via a temporary index (GIT_INDEX_FILE=<tmp> git add -A && GIT_INDEX_FILE=<tmp> git write-tree && " +
        "git commit-tree <tree> -p <parent> && git update-ref refs/heads/" + branch + " <new-commit>). " +
        s"NEVER commit directly to $base; an in-place checkout change by a worktree-less task is flagged red in the receipt.$mergeDiscipline"

  end workingBranchLine

  /**
   * spawn 行的 ChainStore payload（`delegate-spawn` 行）。
   *
   * 本批改动：判定机两键（`worktreeJudged` / `judgeLeg`）**停止写入**——判定机已
   * 删除，再写就是假话；改落**座位来源键** `seat`（命中哪条分配规则，回执同源）与
   * `seatName`（参数原文）。
   */
  def spawnPayload(
    delegateId: String,
    executor: String,
    project: Option[String],
    b: Option[Binding],
    plugins: Option[List[String]] = None,
    allocation: Option[String] = None,
    /**
     * 跨设备 worktree 归属修复批（additive，缺省不落键——旧行字节不变）：设备项目
     * **无 worktree 腿**时的直用 workspace 座位——执行形态面板 Worktree 行据此
     * 显示 workspace 路径并标注「直接使用工作区」。本机项目恒 None。
     */
    workspaceSeat: Option[String] = None
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
          // 座位来源（delegate-param-decisions 批）：分配规则的命中记录（无判定机）。
          ++ b.map(x => "seat" -> x.seatSource.asJson)
          ++ b.flatMap(_.seatName).map(n => "seatName" -> n.asJson)
          // 续用腿审计（worktree-reuse-selection 批，additive）：接续自哪棵树。
          ++ b.flatMap(_.reusedFrom).map(r => "reusedFrom" -> r.asJson)
          ++ workspaceSeat.toList.map(w => "workspaceSeat" -> w.asJson)
      )
    )

  /** 回执里的一行座位说明（「命中哪条规则」不许静默——作者令）。 */
  def receiptLine(b: Option[Binding], workspace: String): String =
    b match
      case None => s"Worktree: none — the whole seat leg was skipped (non-git workspace: $workspace)."
      case Some(x) =>
        x.seatSource match
          case SeatSource.InPlace =>
            s"Worktree: NONE ALLOCATED — the `worktree` parameter was omitted, so this is an IN-PLACE seat in the shared " +
              s"project workspace $workspace (read-only channel; writes here are flagged red when the task ends)."
          case SeatSource.ExactName =>
            s"Worktree: ${x.worktree.getOrElse("?")} — hit rule ① exact name '${x.seatName.getOrElse("")}' (existing tree reused, no second directory)."
          case SeatSource.Legacy =>
            s"Worktree: ${x.worktree.getOrElse("?")} — hit rule ② legacy target '${x.seatName.getOrElse("")}' (delegate id / directory name / absolute path)."
          case SeatSource.Created =>
            s"Worktree: ${x.worktree.getOrElse("?")} — hit rule ③ created for name '${x.seatName.getOrElse("")}' on branch ${x.branch}."
          case _ =>
            s"Worktree: NONE ALLOCATED — creating '${x.seatName.getOrElse("")}' failed, so the task fell back IN PLACE to $workspace (fail-open; see the log)."
  end receiptLine

end DelegateWorkspace
