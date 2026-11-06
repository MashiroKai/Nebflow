package nebflow.core.delegate

import cats.effect.IO
import nebflow.core.GitWorktrees
import nebflow.shared.NebflowLogger

import java.util.concurrent.atomic.AtomicReference
import scala.util.Try

/**
 * WorktreeCandidates —— delegate worktree **续用**的候选发现与状态标注
 * （worktree-reuse-selection 批 2026-11-02 · 作者令逐字「不仅是要能分配
 * worktree 还要能选择接着已有的 worktree继续干的能力 灵活一点」）。
 *
 * ## 背景（本批的活证据）
 *
 * P0/P1/P2 修复批连换三任会话：owner 退场后链被引擎回收，续任又被杀，第三任
 * 重派时**被分到一个全新的空 worktree**，而真正的活在上一任 worktree 里（两个
 * P0 提交 + P1 未提交改动）。编排者只能事后人工发现并发 Mail 纠正——因为分配腿
 * （[[DelegateWorkspace.prepare]]）此前**只有「新建」一条路径**（`createWorktree`
 * 恒建 `delegate/<newId>`，零既有树枚举、零续用参数）。
 *
 * ## 本件职责
 *
 * 只做**发现 + 标注 + 选择解析**（纯读 + 纯函数，零 git 写）；建权仍归
 * [[DelegateWorkspace]]（新增 [[DelegateWorkspace.prepareReuse]] 腿）。三段：
 *
 *   1. [[inventory]] —— 枚举基工作区下 `.nebflow/worktrees/` 的既有 worktree，
 *      与 [[DelegateRegistry]] 记录、活会话注册表对账，逐条标注状态；
 *   2. [[render]] —— 渲染成**模型可见**的候选清单文本（根会话系统提示词段，
 *      见 `PromptSections` order 645）；
 *   3. [[resolve]] —— 解析模型给出的续用目标（`worktree` 参数三形态：id /
 *      目录名 / 绝对路径），返回裁决（可续用 / 需显式 force / 显式拒）。
 *
 * ## 状态三值（候选清单的标注词，机械可判）
 *
 *   - **orphan（孤立）**：其 delegate 不在活会话注册表里，且树上有**未提交改动
 *     或领先基分支的提交**——最该被续用的形态（今天 4b756df9 的那个）；
 *   - **active（活跃）**：其 delegate 在活会话注册表中——**禁止**续用（两个 agent
 *     同时改一棵树），除非显式 `force=true`；
 *   - **done（已完成）**：delegate 不在活会话且树上干净（无未提交、无领先提交）
 *     ——续用没有收益，但合法。
 *
 * ## 纪律
 *
 *   - **默认零变化**：不传 `worktree` 参数 ⇒ 分配腿逐字走既有「新建」路径；
 *     候选清单只是**读面提示**（不改变任何分配行为）。
 *   - **fail-soft 全包**：枚举/读数任何一步失败只降级该项（绝不抛、绝不阻断
 *     派发——与 [[DelegateWorkspace]] 的 fail-open 合同同律）。
 *   - **活会话判据** = [[LiveSessions]] 端口（M2 注册器形态，注册点 =
 *     `agent.SharedResources` 构造行）；端口缺席 = 全部按「不 live」判（保守
 *     方向：宁可把活树误判为孤立，不可把孤立树误判为活——续用仍必须**显式
 *     选择**，且被活会话真正持有的树由 git 本体的双检出禁令兜底）。
 */
object WorktreeCandidates:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.worktree-candidates")

  /** 候选清单上限（模型可见文本的段长闸；排序后截断）。 */
  val MaxCandidates: Int = 8

  /** 候选状态词表（清单标注词；spec 与审计行共用）。 */
  object Status:
    /** 孤立：delegate 不 live 且树上有活（未提交 / 领先提交）——最该被续用。 */
    val Orphan: String = "orphan"

    /** 活跃：delegate 在活会话注册表中——续用需显式 force。 */
    val Active: String = "active"

    /** 已完成：不 live 且树上干净（零未提交、零领先提交）。 */
    val Done: String = "done"

    val All: List[String] = List(Orphan, Active, Done)

  /**
   * 一条候选（发现 + 对账 + 标注的产物；纯数据，渲染与裁决都从它出发）。
   *
   * @param dirName       worktree 目录名（`<基工作区>/.nebflow/worktrees/<dirName>`）
   * @param path          worktree 绝对路径
   * @param branch        当前检出分支（读不到 = None——detached）
   * @param headSha       HEAD short sha
   * @param delegateId    对账命中的 delegate 记录 id（None = 无记录：遗留树/手工树）
   * @param title         delegate 记录的任务摘要（模型判「是不是同一件事」的输入）
   * @param baseBranch    delegate 记录的基分支（领先提交数的分母）
   * @param aheadOfBase   领先基分支的提交数（fail-soft 读不到 = 0）
   * @param dirty         未提交（含未跟踪）条目数（fail-soft 读不到 = 0）
   * @param dirtySample   未提交路径样本（前 5 条，模型面用）
   * @param lastActiveMs  最后活动时刻（delegate 记录 createdAt 与 HEAD committer
   *                      time 取大；全缺 = 0）
   * @param status        [[Status]] 三值之一
   * @param live          其 delegate 是否在活会话注册表中（续用闸判据）
   */
  final case class Candidate(
    dirName: String,
    path: String,
    branch: Option[String],
    headSha: Option[String],
    delegateId: Option[String],
    title: String,
    baseBranch: Option[String],
    aheadOfBase: Int,
    dirty: Int,
    dirtySample: List[String],
    lastActiveMs: Long,
    status: String,
    live: Boolean
  ):
    /** 「树上有活」判据（孤立/已完成 的唯二分界线）。 */
    def hasWork: Boolean = dirty > 0 || aheadOfBase > 0

  /** 一次枚举的完整读数（spawn 行审计与渲染的共同输入）。 */
  final case class Inventory(all: List[Candidate]):
    def byId(id: String): Option[Candidate] = all.find(c => c.delegateId.contains(id))
    def byDirName(n: String): Option[Candidate] = all.find(c => c.dirName == n)
    def byPath(p: String): Option[Candidate] =
      val norm = (s: String) => s.stripSuffix("/")
      all.find(c => norm(c.path) == norm(p))

    /** 模型面清单的排序键：孤立优先 → 有活优先 → 脏度降序 → 新近降序。 */
    def ranked: List[Candidate] =
      all.sortBy(c =>
        (
          if c.status == Status.Orphan then 0 else if c.status == Status.Active then 1 else 2,
          if c.hasWork then 0 else 1,
          -c.dirty,
          -c.lastActiveMs
        )
      )

    /** 孤立候选（「最该被续用」的那批）。 */
    def orphans: List[Candidate] = ranked.filter(c => c.status == Status.Orphan && c.hasWork)

    def isEmpty: Boolean = all.isEmpty

  // ── 活会话端口（M2 注册器形态；core 只见面，agent 侧装配实现） ──────────

  /**
   * 活会话读数端口：给定 sessionId 集合，返回其中**仍在现场**的那些。
   *
   * 判据在实现面（`agent.SharedResources` 构造行）：会话在 agentRegistry 即
   * ``仍在场''（注册表只在演员死/终态注销时删行——退场即缺席）。
   *
   * 端口缺席（离线 spec / 未装配腿）⇒ 恒空集 = 全部按「不 live」判。保守方向
   * 是明确的：**续用永远需要模型显式选择**（默认仍建新树），而真被活会话持有的
   * 树还有 git 本体的双检出禁令兜底（`worktree add` 对已检出分支会拒）。
   */
  object LiveSessions:

    trait Face:
      /** 返回 `ids` 中仍在现场的会话 id 子集（不含已退场/未知者）。 */
      def liveAmong(ids: List[String]): IO[Set[String]]
    end Face

    private val ref = new AtomicReference[Option[Face]](None)

    def install(f: Face): Unit = ref.set(Some(f))

    def clear(): Unit = ref.set(None)

    /** fail-soft：端口缺席 / 实现抛错 ⇒ 空集（见上文保守方向论证）。 */
    def liveAmong(ids: List[String]): IO[Set[String]] =
      ref.get match
        case None => IO.pure(Set.empty)
        case Some(f) =>
          f.liveAmong(ids).handleErrorWith { e =>
            IO(logger.warn(s"live-session probe failed (treating all as gone): ${e.getMessage}")).as(Set.empty[String])
          }
  end LiveSessions

  /**
   * 候选枚举（**纯读**：零 git 写、零记录写）。
   *
   * 两遍式（成本纪律）：生产工作区实测有 **287 棵**既有 worktree——逐棵深探
   * （status + log + rev-parse ≈ 3 次进程叉 × 287）会是秒级开销，而本方法落在
   * 每次会话装配的快照腿上。故：
   *   1. **浅遍**（1 次 git 调用 + 1 次记录全扫）：`git worktree list --porcelain`
   *      一次拿全（路径 / 检出分支 / HEAD sha），按记录对账与时间/活会话排序；
   *   2. **深遍**（≤ [[MaxCandidates]] 棵）：只对**排序后进榜的候选**读脏度与
   *      领先提交数——模型面真正会看到的那几棵。
   *
   * 活会话对账在读记录之后（端口 IO 腿），排序在深遍之前——即「先按浅读数排出
   * 最值得续用的几棵，再给它们付深探成本」。
   *
   * @param baseWs 基工作区（worktree 根 = `<baseWs>/.nebflow/worktrees/`）
   */
  def inventory(baseWs: String): IO[Inventory] =
    IO.blocking {
      val ws = os.Path(baseWs)
      if !GitWorktrees.isGitRepo(ws) then Inventory(Nil)
      else
        val records = DelegateRegistry.list()
        val listed = GitWorktrees.worktreeList(ws)
        val tips = GitWorktrees.branchTips(ws).getOrElse(Nil).map { case (n, s) => n -> s.take(9) }.toMap
        // 主工作区自身不是候选（路径 == 基工作区）。
        val entries = listed.filter { case (p, _, _) => p.stripSuffix("/") != ws.toString.stripSuffix("/") }
        Inventory(entries.flatMap { case (path, branch, sha) => shallow(path, branch, sha, records, tips) })
    }.handleErrorWith { e =>
      IO(logger.warn(s"worktree candidate inventory failed (empty list): ${e.getMessage}")).as(Inventory(Nil))
    }.flatMap { shallow =>
      // 活会话对账（IO 腿：端口实现读 actor 注册表）
      val ids = shallow.all.flatMap(_.delegateId).distinct
      if ids.isEmpty then IO.pure(shallow)
      else
        LiveSessions.liveAmong(ids).map { liveSet =>
          Inventory(shallow.all.map(c => settle(c, c.delegateId.exists(liveSet.contains))))
        }
    }.map { settled =>
      // 深遍：只给进榜候选付脏度/领先提交的读数成本（排序见 [[Inventory.ranked]]）。
      val deepIds = settled.ranked.take(MaxCandidates).map(_.dirName).toSet
      Inventory(settled.all.map(c => if deepIds.contains(c.dirName) then deepen(c) else c))
    }

  /**
   * 浅遍：一次 `worktree list` + 一次 `for-each-ref` 的读数 + 记录对账（零逐树
   * 进程叉）。`aheadOfBase` 在浅遍用**廉价近似**——本树 HEAD sha ≠ 基分支 tip sha
   * 即有活（真值由 [[deepen]] 在进榜后补）；这个近似只用于**排序**，模型面看到的
   * 数字恒来自深遍。
   */
  private def shallow(
    path: String,
    branch: Option[String],
    headSha: Option[String],
    records: List[DelegateRegistry.Record],
    tips: Map[String, String]
  ): Option[Candidate] =
    Try {
      val p = os.Path(path)
      val dirName = p.last
      val norm = (s: String) => s.stripSuffix("/")
      // 对账：记录里 worktree 字段的字面路径命中优先；否则按同路径后缀兜底
      // （`<dirBase>`、`<dirBase>-2` … 的目录候选形态）。
      val rec = records
        .find(r => r.worktree.exists(w => norm(w) == norm(path)))
        .orElse(records.find(r => r.worktree.exists(w => norm(w).endsWith("/" + dirName))))
      val baseWsOf = os.Path(rec.flatMap(_.projectWorkspace).getOrElse((p / os.up / os.up / os.up).toString))
      val baseBranch = rec.flatMap(_.baseBranch).orElse(GitWorktrees.currentBranch(baseWsOf))
      val baseTip = baseBranch.flatMap(tips.get)
      val maybeWork = headSha.zip(baseTip).exists { case (h, t) => h != t }
      Candidate(
        dirName = dirName,
        path = path,
        branch = branch,
        headSha = headSha,
        delegateId = rec.map(_.id),
        title = rec.map(_.title).getOrElse(""),
        baseBranch = baseBranch,
        // 浅遍近似：HEAD ≠ 基 tip ⇒ ahead 记 1（排序信号），真值由 deepen 覆盖。
        aheadOfBase = if maybeWork then 1 else 0,
        dirty = 0,
        dirtySample = Nil,
        lastActiveMs = rec.map(_.createdAt).getOrElse(Try(os.mtime(p)).toOption.getOrElse(0L)),
        status = if maybeWork then Status.Orphan else Status.Done,
        live = false
      )
    }.toOption

  /** 深遍：进榜候选的脏度 + 领先提交数（真读数；任何一步失败保持 0）。 */
  private def deepen(c: Candidate): Candidate =
    Try {
      val p = os.Path(c.path)
      val ahead = c.branch match
        case Some(br) => c.baseBranch.map(bb => GitWorktrees.commitsAhead(p, bb, br)).getOrElse(0)
        case None     => 0
      val dirtyList = GitWorktrees.dirtyPaths(p).getOrElse(Nil)
      c.copy(
        aheadOfBase = ahead,
        dirty = dirtyList.size,
        dirtySample = dirtyList.take(5).map(_.take(80)),
        lastActiveMs = math.max(c.lastActiveMs, GitWorktrees.headCommitEpoch(p).map(_ * 1000L).getOrElse(0L)),
        // 状态按真读数重定（活会话判据已在 settle 定型——active 不因脏度改变）。
        status =
          if c.live then Status.Active
          else if dirtyList.nonEmpty || ahead > 0 then Status.Orphan
          else Status.Done
      )
    }.getOrElse(c)

  /** 活会话对账后的状态定型（纯函数）。 */
  private def settle(c: Candidate, live: Boolean): Candidate =
    val status =
      if live then Status.Active
      else if c.hasWork then Status.Orphan
      else Status.Done
    c.copy(status = status, live = live)

  /**
   * 候选清单的**模型可见**渲染（根会话系统提示词段 order 645 的正文）。
   *
   * 空清单 ⇒ 空串（段不注入——零字节变化，与 executorsText 同律）。
   * 段长纪律：只列 [[MaxCandidates]] 条（排序后），每条一行 + 脏样本子行。
   */
  def render(inv: Inventory, baseWs: String): String =
    if inv.isEmpty then ""
    else
      val shown = inv.ranked.take(MaxCandidates)
      val more = if inv.all.size > shown.size then s"\n- … (+${inv.all.size - shown.size} more)" else ""
      val lines = shown.map { c =>
        val work =
          if c.hasWork then
            val parts = List(
              Option.when(c.aheadOfBase > 0)(s"${c.aheadOfBase} commit(s) ahead of ${c.baseBranch.getOrElse("base")}"),
              Option.when(c.dirty > 0)(s"${c.dirty} uncommitted")
            ).flatten
            s" — WORK IN PROGRESS: ${parts.mkString(", ")}"
          else ""
        val sample =
          if c.dirtySample.nonEmpty then s"; uncommitted sample: ${c.dirtySample.mkString(", ")}" else ""
        val who = c.delegateId.getOrElse("(no delegate record)")
        val title = if c.title.trim.nonEmpty then s" \"${c.title.take(70)}\"" else ""
        s"- `${c.dirName}` — $who$title; branch ${c.branch.getOrElse("(detached)")} @ ${c.headSha.getOrElse("?")}; " +
          s"last active ${ageText(c.lastActiveMs)}; **${c.status}**$work$sample"
      }
      s"""# Existing delegate worktrees (name reuse candidates)

Worktrees that already exist under `$baseWs/.nebflow/worktrees/` (snapshot at session assembly — `Delegate` re-reads them live before honoring a name).

`Delegate(worktree="<name>", …)` uses the name as-is: an existing tree with that exact directory name is REUSED (never a second directory), and a new name creates `<worktreeRoot>/<name>` on branch `delegate/<name>`. A write-task MUST pass a name; omitting `worktree` allocates nothing and runs the delegate in place in the shared project workspace — that is the read-only channel only.

Reuse a listed tree when the new task CONTINUES work that sits in it (same files, same intent). Status words:
- **orphan** = its delegate is gone but the tree still holds work (uncommitted changes and/or commits ahead of the base branch) — the case where a new tree would silently strand the previous work. Prefer these.
- **active** = a live session is working in this tree right now. Reuse is REFUSED (two agents editing one tree) unless you pass `force=true` — do not force unless you are sure that session is not touching these files.
- **done** = its delegate is gone and the tree is clean; reusing buys nothing.

${lines.mkString("\n")}$more"""

  /** 「最后活动」的人类可读形态（粗粒度，模型面够用即可）。 */
  private def ageText(ms: Long): String =
    if ms <= 0 then "unknown"
    else
      val mins = math.max(0L, System.currentTimeMillis() - ms) / 60000L
      if mins < 60 then s"${mins}m ago"
      else if mins < 60 * 24 then s"${mins / 60}h ago"
      else s"${mins / (60 * 24)}d ago"

  // ── 续用目标的解析与裁决 ────────────────────────────────────────────────

  /** 续用请求的裁决（[[resolve]] 的产物）。 */
  enum Decision:
    /** 可续用：命中候选（`None` = 清单外但路径是合法 git worktree——裸路径接纳）。 */
    case Reuse(target: Option[Candidate], path: String)

    /** 拒（`code` = 装配错误码；`message` = 可行动原文）。 */
    case Refuse(code: String, message: String)
  end Decision

  /** 显式拒的错误码（字面量单点）。 */
  object RefuseCode:
    val NotFound: String = "DELEGATE_WORKTREE_NOT_FOUND"
    val Active: String = "DELEGATE_WORKTREE_ACTIVE"
    /** 座位存在但不可用（未登记/无 checkout/status 不可执行——缺陷二四查拒）。 */
    val NotReady: String = "DELEGATE_WORKTREE_NOT_READY"
    /** 名字不合法（不是 ref-safe 的 worktree 名——含路径分隔符 / 隐藏名 / 过长）。 */
    val BadName: String = "DELEGATE_WORKTREE_BAD_NAME"
  end RefuseCode

  /**
   * `worktree` 参数的座位计划（delegate-param-decisions 批 2026-11-04）：
   * 缺省 ⇒ [[InPlace]]（**零分配**，只读任务通道）；精确 name / 旧口径命中 ⇒
   * [[Reuse]]；都没命中 ⇒ [[Create]]（按名建树的调用方动作）。
   */
  enum SeatPlan:
    /** `worktree` 参数缺省：一棵树都不建，就地坐在项目工作区（只读任务通道）。 */
    case InPlace

    /** 复用既有树（`source` = [[DelegateWorkspace.SeatSource]] 的 exact-name / legacy）。 */
    case Reuse(candidate: Candidate, source: String)

    /** 按 `worktree` 参数给的名字新建（`<worktreeRoot>/<name>`，分支 `delegate/<name>`）。 */
    case Create(name: String)
  end SeatPlan

  /** worktree 名的机械判据（ref-safe：字母数字与 `-_.@`，不以点开头，≤64 字）。 */
  def nameIsSafe(n: String): Boolean =
    n.nonEmpty && n.length <= 64 && !n.startsWith(".") &&
      n.forall(c => c.isLetterOrDigit || "-_.@".contains(c))

  /**
   * `worktree` 参数解析（**纯读 + 纯函数，零模型调用**——作者令 2026-11-04：
   * 「填的内容就是 worktree 的 name」）。
   *
   * 顺序（命中哪条必须回给调用方，不许静默）：
   *   ① **精确 name**：worktree 根下同名目录（`dirName == 参数`，精确相等，不做
   *      后缀/模糊匹配）× 被判 `active` 且未 `force` ⇒ 拒（防错配红线）；
   *   ② **旧口径次选**：delegate id / 目录名 / 绝对路径（[[resolve]] 逐字逻辑）；
   *   ③ 都没命中 ⇒ [[SeatPlan.Create]]（同名目录被非 worktree 占用 ⇒ 显式拒，
   *      绝不在名字被占时悄悄换个名字建第二棵树）。
   */
  def resolveNamed(
    inv: Inventory,
    baseWs: String,
    target: String,
    force: Boolean
  ): Either[(String, String), SeatPlan] =
    val t = target.trim.stripSuffix("/")
    if t.isEmpty then Left((RefuseCode.BadName, "the worktree parameter is empty"))
    else
      // ① 精确 name（只在参数是合法名字时按 name 解释；含 `/` 的形态直接走旧口径）。
      val exact = Option.when(nameIsSafe(t))(inv.all.find(_.dirName == t)).flatten
      exact match
        case Some(c) if c.status == Status.Active && !force =>
          Left((RefuseCode.Active, activeMessage(c)))
        case Some(c) => Right(SeatPlan.Reuse(c, DelegateWorkspace.SeatSource.ExactName))
        case None =>
          // ② 旧口径次选（delegate id / 目录名 / 绝对路径）。
          resolve(inv, t, force) match
            case Decision.Reuse(Some(c), _) => Right(SeatPlan.Reuse(c, DelegateWorkspace.SeatSource.Legacy))
            case Decision.Reuse(None, path) =>
              Right(SeatPlan.Reuse(candidateAt(path, baseWs), DelegateWorkspace.SeatSource.Legacy))
            case Decision.Refuse(RefuseCode.NotFound, _) =>
              // ③ 按 name 新建。
              if !nameIsSafe(t) then
                Left(
                  (
                    RefuseCode.BadName,
                    s"'$t' is neither an existing worktree nor a usable worktree NAME — a name holds only letters, " +
                      "digits, '-', '_', '.' or '@' (no path separators, no leading dot, ≤64 chars)"
                  )
                )
              else if os.exists(os.Path(baseWs) / ".nebflow" / "worktrees" / t) then
                Left(
                  (
                    RefuseCode.NotReady,
                    s"the path '$baseWs/.nebflow/worktrees/$t' already exists but is not a registered worktree of this " +
                      "repo — clean it up or pass a different name"
                  )
                )
              else Right(SeatPlan.Create(t))
            case Decision.Refuse(code, msg) => Left((code, msg))

  /** 活会话持有的拒绝文案（精确 name 腿与旧口径腿同源）。 */
  private def activeMessage(c: Candidate): String =
    s"worktree '${c.dirName}' is held by a LIVE session (${c.delegateId.getOrElse("?")}) — " +
      "two agents must not edit one tree. Either let that session finish (its result arrives as a system message), " +
      "pass a different worktree name, or pass force=true if you are certain the live session is not touching these files."

  /** 清单外的合法 worktree 路径 → 最小候选（复用腿的输入形状）。 */
  private def candidateAt(path: String, baseWs: String): Candidate =
    val p = os.Path(path)
    val dirty = GitWorktrees.dirtyPaths(p).getOrElse(Nil)
    Candidate(
      dirName = p.last,
      path = path,
      branch = GitWorktrees.currentBranch(p),
      headSha = GitWorktrees.headSha(p),
      delegateId = None,
      title = "",
      baseBranch = GitWorktrees.currentBranch(os.Path(baseWs)),
      aheadOfBase = 0,
      dirty = dirty.size,
      dirtySample = dirty.take(5).map(_.take(80)),
      lastActiveMs = GitWorktrees.headCommitEpoch(p).map(_ * 1000L).getOrElse(0L),
      status = Status.Done,
      live = false
    )

  /**
   * 解析续用目标并裁决（纯函数 + 清单查找，零 IO 副作用）。
   *
   * 三形态（作者令「灵活一点」）：delegate id / worktree 目录名 / 绝对路径。
   *
   * 防错配（红线）：命中 **active** 且未显式 `force` ⇒ 拒，文案说清「谁在用它
   * + 两个出路」。
   */
  def resolve(inv: Inventory, target: String, force: Boolean): Decision =
    val t = target.trim.stripSuffix("/")
    if t.isEmpty then Decision.Refuse(RefuseCode.NotFound, "the worktree parameter is empty")
    else
      val hit = inv.byId(t).orElse(inv.byDirName(t)).orElse(inv.byPath(t))
      hit match
        case Some(c) if c.status == Status.Active && !force =>
          Decision.Refuse(
            RefuseCode.Active,
            s"worktree '${c.dirName}' is held by a LIVE session (${c.delegateId.getOrElse("?")}) — " +
              "two agents must not edit one tree. Either let that session finish (its result arrives as a system message), " +
              "continue into a different ORPHAN worktree, or pass force=true if you are certain the live session is not touching these files."
          )
        case Some(c) => Decision.Reuse(Some(c), c.path)
        case None =>
          // 清单未命中：按路径兜底（合法 git worktree 接纳——可能是不在 registry 的
          // 遗留树）。**座位四查**（引擎会话治理专项 · 缺陷二，2026-11-03）：旧判据
          // `os.isDir ∧ isGitRepo` 会被空目录骗过——`.nebflow/worktrees/<name>` 在主
          // 工作区内时 `rev-parse` 沿父级解析为 true，空目录被当成座位收下（3c9f1ce2
          // 实证）。改走 [[GitWorktrees.validateAllocation]]：注册可见 / 顶层即该目录
          // （checkout 完成）/ HEAD 可解 / status 可执行——任一不过 ⇒ 显式拒
          // （`DELEGATE_WORKTREE_NOT_READY`），绝不把坏座位交给任务。
          val p = os.Path(t, os.pwd)
          if os.isDir(p) && GitWorktrees.isGitRepo(p) then
            // 四查所用「仓」锚：该目录在 `git worktree list` 的首条（任一注册树都
            // 能枚举全仓）；未注册空目录 = 列表空 ⇒ 用目录自身探测（四查会点名
            // "not registered"）。
            val wsForCheck: os.Path =
              GitWorktrees.worktreeList(p).headOption.map(w => os.Path(w._1)).getOrElse(p)
            val problems = GitWorktrees.validateAllocation(wsForCheck, p.toString)
            if problems.isEmpty then Decision.Reuse(None, p.toString)
            else
              Decision.Refuse(
                RefuseCode.NotReady,
                s"path '$t' is not a usable worktree seat: ${problems.mkString("; ")}"
              )
          else
            Decision.Refuse(
              RefuseCode.NotFound,
              s"no worktree matches '$t' — pass a delegate id, a directory name under .nebflow/worktrees/, or an absolute path to an existing git worktree. " +
                "Omitting the parameter allocates no worktree at all (read-only channel — it runs in place in the shared workspace)."
            )
  end resolve

end WorktreeCandidates
