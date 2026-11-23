package nebflow.core.delegate

import nebflow.core.GitWorktrees

/**
 * DelegateWorktreeRegistry —— `Record.worktree` 的**在场判据单源**（孤儿行全清 ops
 * 复核批 2026-11-21 · 作者令「收紧判据，让真死行恢复年龄判定」）。
 *
 * == 为什么需要本件 ==
 * 死 delegate 清理面的旧闸是「`worktree` 字段**非空** ⇒ 判在飞」
 * （[[DelegateTerminalTtl.verdict]] 旧第 122 行）。字段是**登记时刻的意图快照**，
 * 不是现场存续：ops 复核 695 条过期行，694 条该字段非空，而其中 634 条的
 * worktree **目录早已不在盘上**——字段非空 ≠ 真在飞。旧判据写松了，于是
 * 「真在飞的不收」这条保险丝被撑成了「登记过的永不收」：行永不到期，面板孤儿行
 * 计数无法归零。
 *
 * 本件把它改成**三态现场判读**（`worktree` 字段只是候选，不是判据）：
 *
 *  - [[Presence.Live]]         目录在盘上 **∧** 仍在某仓库的 `git worktree list` 登记内
 *                              ⇒ 现场没被拆除 ⇒ 清理面判 `InFlight`（保险丝语义原样保留）；
 *  - [[Presence.Gone]]         目录不在盘上 ⇒ 现场已拆除（git 登记即使残留也只是
 *                              `git worktree prune` 可清的残骸，不构成活物）⇒ 回到年龄判定；
 *  - [[Presence.Unregistered]] 目录在盘上，**但该仓库的登记清单里没有它** ⇒ 只是一个
 *                              同名目录（历史 `worktree add` 失败降级 / 手工目录），
 *                              不是活座位 ⇒ 回到年龄判定。
 *
 * == 效率（批量调用面）==
 * 判读发生在 boot 清理链上，逐条各跑一次 `git worktree list` 在千条量级是上千次进程
 * 叉。因此判据做成**一次快照**：[[Snapshot.capture]] 先按磁盘存在性筛候选（纯
 * `os.isDir`，零子进程——本轮 695 行只筛出 60 条目录仍在），再按仓库根去重，
 * **每个仓库根至多一次** `git worktree list --porcelain`（本轮 11 次）。读取本体复用
 * 既有批量读面 [[nebflow.core.GitWorktrees.worktreeList]]（单源、只读、fail-soft）。
 *
 * == 保守面（哪些情形绝不判 Live，为什么不是 fail-closed）==
 * 仓库根不可推导 / git 不可读 / 登记清单为空 ⇒ **不判 Live**（落到年龄判定）。理由：
 * 真在飞的 delegate 另有两道**更强**的上游守卫——活体 actor 在册
 * （[[nebflow.agent.DelegateTtlSweep]] 闸①）与执行保护（protected/unknown 零触碰）。
 * worktree 闸是**存量登记兼容面**（「未显式对账进台账的旧归属」），不是唯一活物证据；
 * 让它 fail-closed（读不到就留）会把旧缺陷原样复现成「git 读不到 ⇒ 永不过期」——
 * 那正是本次要修掉的形态。
 */
object DelegateWorktreeRegistry:

  /** `worktree` 字段的现场三态（判据值域；见对象头注）。 */
  enum Presence:
    /** 目录在盘上 ∧ 仍被 git 登记 ⇒ 现场未拆（清理面判 InFlight）。 */
    case Live

    /** 目录不在盘上 ⇒ 现场已拆（回到年龄判定）。 */
    case Gone

    /** 目录在盘上，但该仓库的登记清单里没有它 ⇒ 不是活座位（回到年龄判定）。 */
    case Unregistered

  /** 一条登记记录的工作区现场判读（`worktree` 缺省 ⇒ [[Presence.Gone]]）。 */
  def presenceOf(rec: DelegateRegistry.Record, snapshot: Snapshot): Presence =
    rec.worktree.map(_.trim).filter(_.nonEmpty) match
      case None => Presence.Gone
      case Some(wt) => snapshot.presenceOf(wt, rec.projectWorkspace)

  /**
   * 批量快照：`(仓库根 → 登记清单)` 的一次性读数。构造即定值、之后不再触碰盘面
   * （判读是纯查表——spec 可直驱，见 [[Snapshot.of]]）。
   */
  final class Snapshot private (
    /** 归一化仓库根 → 归一化工作树路径集（`None` = 该根不可读/无登记）。 */
    registered: Map[String, Option[Set[String]]]
  ):

    /**
     * 现场判读（就地查表）：
     * 目录不在盘上 ⇒ `Gone`（**先判存在性**——已拆现场占多数，且零 git 依赖）；
     * 目录在盘上 ⇒ 取它的仓库根，查登记清单：命中 = `Live`，未命中/不可读 = `Unregistered`。
     */
    def presenceOf(worktree: String, repoHint: Option[String]): Presence =
      val dir = canonical(worktree)
      if !os.isDir(os.Path(dir, os.pwd)) then Presence.Gone
      else
        val hit = repoRootsOf(dir, repoHint).exists { root =>
          registered.get(root).flatten.exists(_.contains(dir))
        }
        if hit then Presence.Live else Presence.Unregistered

    /** 快照覆盖的仓库根集（审计/日志读数）。 */
    def repoRoots: Set[String] = registered.keySet

    /** 快照内被登记的工作树总数（审计读数；不可读根不计）。 */
    def registeredCount: Int = registered.values.flatten.map(_.size).sum

  end Snapshot

  object Snapshot:

    /** 空快照（零 git 调用）：任何在盘上的 worktree 字段都判 `Unregistered`。spec 直驱面。 */
    val empty: Snapshot = new Snapshot(Map.empty)

    /** 直驱构造（spec/诊断面：给定 `仓库根 → 登记路径集`，零 git 调用）。 */
    def of(registered: Map[String, Set[String]]): Snapshot =
      new Snapshot(registered.map { case (root, paths) => canonical(root) -> Some(paths.map(canonical)) })

    /**
     * 一次快照：从记录集里收集**目录仍在盘上**的 worktree（纯 `os.isDir`，零子进程），
     * 按仓库根去重后每个根跑一次 `git worktree list --porcelain`。
     *
     * 目录已不在盘上的记录**不参与取数**——它们判 `Gone` 与 git 登记无关，因此不产生
     * 任何进程开销（本轮 634/695 条走这条零成本路径）。
     */
    def capture(records: Iterable[DelegateRegistry.Record]): Snapshot =
      val roots = records.iterator
        .flatMap(rec => rec.worktree.map(_.trim).filter(_.nonEmpty).map(wt => canonical(wt) -> rec.projectWorkspace))
        .filter { case (wt, _) => os.isDir(os.Path(wt, os.pwd)) }
        .flatMap { case (wt, hint) => repoRootsOf(wt, hint) }
        .toSet
      new Snapshot(roots.map(root => root -> readRegistered(root)).toMap)

    /** 单个仓库根的一次读（`None` = git 不可读 / 无输出；fail-soft，绝不抛）。 */
    private def readRegistered(root: String): Option[Set[String]] =
      GitWorktrees.worktreeList(os.Path(root, os.pwd)).map { case (p, _, _) => canonical(p) } match
        case Nil => None
        case paths => Some(paths.toSet)

  end Snapshot

  /**
   * 一个 worktree 目录的候选仓库根（**纯本地读**，零子进程）：
   *  1. `<wt>/.git` 是**目录** ⇒ `<wt>` 本身就是仓库根（主工作树形态）；
   *  2. `<wt>/.git` 是**文件** ⇒ 读 `gitdir: <repo>/.git/worktrees/<name>`，上溯三级得
   *     `<repo>`（linked worktree 形态——本仓工作树的标准形态）；
   *  3. 以上都推不出（同名空目录 / 无 `.git`）⇒ 回落记录里的 `projectWorkspace`
   *     （派发时解析的基工作区，本盘 694/694 条记录都有）。
   */
  private[delegate] def repoRootsOf(worktree: String, repoHint: Option[String]): List[String] =
    (selfRepoRoot(worktree).toList ++ repoHint.map(_.trim).filter(_.nonEmpty).map(canonical).toList).distinct

  /** 由 worktree 目录自身的 `.git` 推导仓库根（`None` = 非仓库形态 / 不可读）。 */
  private def selfRepoRoot(worktree: String): Option[String] =
    val gitPath = os.Path(worktree, os.pwd) / ".git"
    if os.isDir(gitPath) then Some(canonical(worktree))
    else if os.isFile(gitPath) then
      scala.util
        .Try(os.read(gitPath))
        .toOption
        .flatMap(_.linesIterator.map(_.trim).find(_.startsWith("gitdir:")))
        .map(_.stripPrefix("gitdir:").trim)
        .filter(_.nonEmpty)
        .flatMap(repoOfGitdirPointer)
    else None

  /**
   * `gitdir:` 指针 → 仓库根。linked worktree 的指针形如 `<repo>/.git/worktrees/<name>`
   * （上溯三级）；其它形态（如 `<wt>/.git` 文件内容为 `gitdir: <repo>/.git`）上溯两级。
   * 不可解析 = None（不猜——宁可不判 Live）。
   */
  private[delegate] def repoOfGitdirPointer(raw: String): Option[String] =
    scala.util.Try(os.Path(raw, os.pwd).toNIO.normalize().toString).toOption.flatMap { pointer =>
      // 归一化后的绝对路径按 "/" 切段（POSIX 文件名不含 "/"，段级切割无歧义）。
      val segs = pointer.split("/").toList.filter(_.nonEmpty)
      def abs(parts: List[String]): String = "/" + parts.mkString("/")
      // 自尾向前：`<repo>/.git/worktrees/<name>` → `parts(n-3)==".git"`；`<repo>/.git` → 末段 ".git"。
      if segs.length >= 4 && segs(segs.length - 3) == ".git" && segs(segs.length - 2) == "worktrees" then
        val repo = segs.dropRight(3)
        if repo.isEmpty then None else Some(abs(repo))
      else if segs.length >= 2 && segs.last == ".git" then Some(abs(segs.dropRight(1)))
      else None
    }

  /** 路径归一化：软链解析（失败回落原文）+ 去尾斜杠——`worktree list` 输出与登记字段可比。 */
  def canonical(path: String): String =
    val trimmed = path.trim
    val resolved = scala.util.Try(os.Path(trimmed, os.pwd).toNIO.toRealPath().toString).getOrElse(trimmed)
    if resolved.length > 1 && resolved.endsWith("/") then resolved.dropRight(1) else resolved

end DelegateWorktreeRegistry
