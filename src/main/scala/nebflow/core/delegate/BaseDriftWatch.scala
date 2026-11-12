package nebflow.core.delegate

import cats.effect.IO
import cats.syntax.all.*
import io.circe.{Json, JsonObject}
import io.circe.parser.parse
import io.circe.syntax.*
import nebflow.core.AgentRuntimePort
import nebflow.core.GitWorktrees
import nebflow.shared.{NebflowLogger, PathUtil}

/**
 * BaseDriftWatch —— **基分支漂移：默认静默，只在可行动时聚合通知**
 * （引擎会话治理专项 · 缺陷四最初落地 2026-11-03；**降噪重写 2026-11-05 作者报障**）。
 *
 * ==作者报障与三条语义（2026-11-05 10:30，逐字）==
 * 「…DELEGATE-NOTIFY · FLOW-NOTIFY [base-moved] main advanced … 这个通知也太多了，是
 * 怎么回事？我们现在不是用 worktree 隔离了吗，这个通知是相对于其他 delegate 的，
 * 还是说它派发的节点导致的？如果是其他 delegate 就不应该，因为有隔离，不需要通知。
 * 如果是子节点的，也不需要通知。它在派发时，应该要考虑各节点并行时不冲突。」
 *
 *  1. **其他 delegate 推进 base ⇒ 不该通知**：每个 delegate 跑在独立 worktree，
 *     别人的合并不会污染它的工作树 ⇒ base 前进**本身不构成任何行动要求**。
 *  2. **它派发的子节点推进 base ⇒ 更不该通知**：节点跑在 dispatcher 的 seat 内，
 *     本来就没有独立 base 可谈 ⇒ 节点侧这条路直接不发（结构上也不发：见
 *     [[trackable]]——节点不建分支/worktree，不进 [[DelegateRegistry]]，天然零命中）。
 *  3. **派发时就该考虑并行不冲突**：基线快照在**派发时刻**落盘
 *     （[[noteDispatch]]），后续判断冲突用这个快照比对，「预计要改的文件范围」用
 *     分支**实际**改动面（`merge-base(base, branch)..branch`）——比预测更准且零猜测；
 *     逐条弹通知的做法被聚合窗取代。
 *
 * ==改前病灶（实测）==
 * 每拍对每个在飞 delegate 比对 base tip，**只要 tip 变了就注入一条**，文案只说
 * 「base 动了，收尾前复查」。于是噪音是 O(在飞任务数 × 合并数)：2026-11-05 上午
 * 00:00–10:38 实测 **189 条**注入、涉及 **34 个任务**、**21 次 tip 移动**
 * （≈9 条/次移动）。其中绝大多数移动与收件人改动的文件毫无交集 = 纯噪音。
 *
 * ==改后判据（机械可判）==
 * 通知**仅当**下列之一成立（否则零注入）：
 *   - **C1 真冲突**：把新的 base tip 三段合并进我的分支时**确实冲突**（
 *     `git merge-tree --write-tree`，见 [[GitWorktrees.mergeTreeConflicts]]）——冲突
 *     文件列表就是通知里的文件清单；
 *   - **C2 历史被改写**：旧 tip 不再是新 tip 的祖先（`merge-base --is-ancestor`
 *     rc=1）——别人 reset/rebase 了 base，盲合会丢东西。
 * 且**同一任务 ≥ [[WindowMs]]（30min）最多一条**，内容聚合为
 * 「base 已前进 N 次，其中 M 次会与你的分支冲突：<文件列表>」，绝不一事一条。
 *
 * ===为什么 C1 不用「文件交集」（实测否决）===
 * 作者原始表述是「与我改过的文件集合有交集（潜在冲突）」。实测（2026-11-05 上午
 * 28 次 tip 移动 × 25 个在飞分支）证明**单侧 diff 算不出「我改过的文件」**：
 *   - `merge-base(base, branch)..branch`：分支并入过旧 main 的，把 main 自己的
 *     改动全算成「我的改动」；
 *   - `base..branch`：分支落在 base 后面的，把 base 走在前面的部分反向算成我的。
 * 两种口径都让 25/25 个分支对几乎每次移动都「有交集」（连 `CONTRIBUTING.md` 这种
 * 谁都没碰的文件都在列）⇒ 按交集发通知 = 复现原噪音。三段合并实况把假阳性压到
 * **2/25 个分支**。故 C1 = 真冲突，而不是文件交集。
 *
 * 关于作者原文的另一条判据「我的分支已落后到无法 ff 合并」：**任何**自基点以来有
 * 提交的 delegate 都无法 ff 到新 base（ff 会丢掉它自己的提交）——把它做成通知条件
 * 只会复现原噪音，故**不进消息腿**：它的读数（moves 计数 / 落后状态）落在**面板
 * 状态面**（[[statusJson]] 经 `GET /api/chains` 行的 `baseDrift` 键透出），
 * 「base 动了」这个事实可见而不吵。这条取舍是作者令「默认静默」的直读。
 *
 * ==通道与调度（零新增机制）==
 * 通道 = 既有会话注入面 [[DelegateNotify.inject]]（ImmediateInput → idle 分支转
 * UserInput）；调度 = 搭既有扫描拍（[[nebflow.core.processor.TaskStuckWatcher.scan]]
 * 拍尾，30s 一拍，零新增定时器）。状态落 `<dataRoot>/base-drift.json`（v2 JSON：
 * 基线 + 挂账聚合 + 上次通知时刻），重启后聚合窗与基线照旧；v1 旧格式
 * （`<id> <tip>` 逐行）原样读入迁移，**迁移绝不补发通知**。
 */
object BaseDriftWatch:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.base-drift")

  /** 单条通知里最多列出的文件数（防提示词膨胀）。 */
  val MaxFiles: Int = 20

  /** 每条挂账最多记住的文件数（超出截断——聚合窗内极端情况下的状态文件护栏）。 */
  private val MaxPendingFiles: Int = 200

  /** 聚合窗：同一任务在该窗内**最多一条** base 漂移通知（作者令建议 ≥30min）。 */
  val WindowMs: Long = 30L * 60L * 1000L

  /** 状态文件版本（v1 = `<id> <tip>` 文本行；v2 = 本文件的 Entry JSON 表）。 */
  val StateVersion: Int = 2

  private def statePath: os.Path = PathUtil.dataRoot / "base-drift.json"

  /** 状态文件读改写锁（[[noteDispatch]] 的读-改-写短临界区走 blocking 池）。 */
  private def withStateLock[A](f: => A): A = synchronized(f)

  /**
   * 单个 delegate 的漂移记账。
   *
   * @param baselineTip      **派发时刻**的 base tip（[[noteDispatch]] 落；缺失 = 首
   *                         次扫见时的 tip，只记账不通知——不知道「从哪来」）
   * @param lastSeenTip      已记账到的最新 base tip（节流锚：同一 tip 不再算移动）
   * @param moves            自基线以来观测到的 base 移动次数（**面板可见量**）
   * @param pendingMoves     上次通知以来累计的移动次数（聚合文案的 N；含非冲突移动）
   * @param pendingConflicts 上次通知以来**会与我分支冲突**的移动次数（聚合文案的 M）
   * @param pendingFiles     挂账的冲突文件名（去重截断；聚合文案的文件列表）
   * @param pendingRewritten 挂账期间是否发生过 base 历史改写（C2）
   * @param lastNotifyMs     上次实际注入时刻（0 = 从未）——聚合窗的时间锚
   */
  final case class Entry(
    baselineTip: String,
    lastSeenTip: String,
    moves: Int = 0,
    pendingMoves: Int = 0,
    pendingConflicts: Int = 0,
    pendingFiles: List[String] = Nil,
    pendingRewritten: Boolean = false,
    lastNotifyMs: Long = 0L
  ):
    /** 挂账里是否有**可行动**内容（无 = 静默，无论挂了几次移动）。 */
    def actionablePending: Boolean = pendingConflicts > 0 || pendingRewritten

    def toJson: Json =
      Json.obj(
        "baselineTip" -> baselineTip.asJson,
        "lastSeenTip" -> lastSeenTip.asJson,
        "moves" -> moves.asJson,
        "pendingMoves" -> pendingMoves.asJson,
        "pendingConflicts" -> pendingConflicts.asJson,
        "pendingFiles" -> pendingFiles.asJson,
        "pendingRewritten" -> pendingRewritten.asJson,
        "lastNotifyMs" -> lastNotifyMs.asJson
      )

  end Entry

  object Entry:

    def fromJson(j: Json): Option[Entry] =
      val c = j.hcursor
      for lastSeen <- c.get[String]("lastSeenTip").toOption
      yield Entry(
        baselineTip = c.get[String]("baselineTip").toOption.getOrElse(lastSeen),
        lastSeenTip = lastSeen,
        moves = c.get[Int]("moves").toOption.getOrElse(0),
        pendingMoves = c.get[Int]("pendingMoves").toOption.getOrElse(0),
        pendingConflicts = c.get[Int]("pendingConflicts").toOption.getOrElse(0),
        pendingFiles = c.get[List[String]]("pendingFiles").toOption.getOrElse(Nil),
        pendingRewritten = c.get[Boolean]("pendingRewritten").toOption.getOrElse(false),
        lastNotifyMs = c.get[Long]("lastNotifyMs").toOption.getOrElse(0L)
      )

  /** 端口（生产 = [[fromResources]]；测试 = 假端口注入全部 IO 面）。 */
  trait Port:
    def delegates: IO[List[DelegateRegistry.Record]]
    def liveSessionIds: IO[Set[String]]
    def headTip(workspace: String, branch: String): Option[String]

    /**
     * 三段合并实况：把 base 的新 tip 并进我的分支**会不会真冲突**（冲突文件列表；
     * 空 = 无冲突）。见 [[GitWorktrees.mergeTreeConflicts]]——这是「有没有撞上我」
     * 的唯一可算判据（单侧 diff 的「文件交集」实测假阳性 25/25）。
     */
    def conflictFiles(workspace: String, branch: String, baseTip: String): List[String]

    /** base 历史被改写 = 旧 tip 不再是新 tip 的祖先（不可确证 = false，宁静默）。 */
    def baseRewritten(workspace: String, oldTip: String, newTip: String): Boolean

    /**
     * 在飞工作读数（唤醒价值判据 [[worthWaking]] 的第一项）。判据单点 =
     * [[DelegateInflightGate.read]]（与在飞闸同源判据；申报通道退役后本读数为其唯一存活面）；
     * `None` = 读面不可用（`readable=false`）——调用方按「宁可多发一条」处理。
     */
    def inFlightWork(delegateId: String): IO[Option[Boolean]] =
      DelegateInflightGate
        .read(delegateId)
        .map(r => if r.readable then Some(r.inFlight) else None)
        .handleErrorWith(_ => IO.pure(None))

    /**
     * 本分支相对基分支的领先提交数（唤醒价值判据第二项；无分支/读失败 = `None`）。
     * 与判词链的 `commitsAhead` 同源（[[GitWorktrees.commitsAhead]]）。
     */
    def unmergedCommits(workspace: String, baseBranch: String, branch: String): Option[Int] =
      scala.util.Try(os.Path(workspace)).toOption.map(ws => GitWorktrees.commitsAhead(ws, baseBranch, branch))

    def notify(sessionId: String, text: String): IO[Boolean]
    def nowMs: IO[Long] = IO.realTime.map(_.toMillis)
    def loadState: IO[Map[String, Entry]] = IO.blocking(BaseDriftWatch.readState())
    def saveState(state: Map[String, Entry]): IO[Unit] = IO.blocking(BaseDriftWatch.writeState(state))

  end Port

  /** 生产端口（AgentRuntimePort 装配；git 读数走 GitWorktrees）。 */
  def fromResources(resources: AgentRuntimePort): Port = new Port:
    private def pathOf(workspace: String): Option[os.Path] =
      scala.util.Try(os.Path(workspace)).toOption

    def delegates: IO[List[DelegateRegistry.Record]] =
      IO.blocking(DelegateRegistry.list()).handleErrorWith(_ => IO.pure(Nil))
    def liveSessionIds: IO[Set[String]] = resources.agentRegistry.get.map(_.keySet)
    def headTip(workspace: String, branch: String): Option[String] =
      pathOf(workspace).flatMap(ws => GitWorktrees.headShaOfBranch(ws, branch))
    def conflictFiles(workspace: String, branch: String, baseTip: String): List[String] =
      pathOf(workspace).map(ws => GitWorktrees.mergeTreeConflicts(ws, branch, baseTip)).getOrElse(Nil)
    def baseRewritten(workspace: String, oldTip: String, newTip: String): Boolean =
      pathOf(workspace).flatMap(ws => GitWorktrees.isAncestor(ws, oldTip, newTip)).contains(false)
    def notify(sessionId: String, text: String): IO[Boolean] =
      DelegateNotify.inject(resources.agentRegistry, sessionId, text)

  /**
   * 派发期基线快照（作者令第三条：**派发时就记**下基线，后续判断冲突拿它比对，
   * 而不是每条都打扰人）。
   *
   * 幂等：已有记账 = 零动作（scan 可能已先落第一条；先到者为准，绝不覆盖已累计的
   * 挂账）。fail-soft：非 git / 读不到 tip = 零动作（scan 首次见会自动补基线）。
   * 与 scan 的拍尾写盘存在良性竞态（本函数只补**缺席**行；被拍尾覆盖 = 下次首见
   * 重新取基线，偏晚一个扫描拍，零通知、零丢挂账）。
   */
  def noteDispatch(
    delegateId: String,
    workspace: Option[String],
    baseBranch: Option[String]
  ): IO[Unit] =
    val snapshot = for
      ws <- workspace.toList
      branch <- baseBranch.toList
      path <- scala.util.Try(os.Path(ws)).toOption.toList
      tip <- GitWorktrees.headShaOfBranch(path, branch).toList
    yield tip
    snapshot.headOption match
      case None => IO.unit
      case Some(tip) =>
        IO.blocking {
          withStateLock {
            val st = readState()
            if !st.contains(delegateId) then
              writeState(st.updated(delegateId, Entry(baselineTip = tip, lastSeenTip = tip)))
          }
        }.handleErrorWith(e => logger.warn(s"[base-drift] dispatch baseline failed for $delegateId: ${e.getMessage}"))
          .void

  end noteDispatch

  /** 通知文案（模型可见；`[base-moved]` 前缀 = 作者令的 grep 锚；**聚合一条**）。 */
  def message(delegateId: String, baseBranch: String, e: Entry): String =
    val shown = e.pendingFiles.take(MaxFiles)
    val extra = e.pendingFiles.size - shown.size
    val filePart =
      if shown.isEmpty then ""
      else shown.mkString("\n  - ", "\n  - ", "") + (if extra > 0 then s"\n  … (+$extra more)" else "")
    val rewritePart =
      if !e.pendingRewritten then ""
      else
        s"""
           |⚠ $baseBranch's history was rewritten (its previous tip is no longer an ancestor of the current one) — do NOT merge blindly; re-create your branch from the new tip and check that nothing you depend on was dropped.""".stripMargin
    s"""[base-moved] $baseBranch advanced ${e.pendingMoves} time(s) since you were dispatched (baseline ${e.baselineTip} → now ${e.lastSeenTip}); ${e.pendingConflicts} of those moves now conflict with your branch:$filePart
       |$rewritePart
       |Why this one reached you (aggregated, at most once per ${WindowMs / 60000} min): your worktree is isolated, so a base move on its own needs no action from you — only a move that makes your branch conflict when it lands does. Merge $baseBranch into your branch and resolve these before you report merge; if the collision changes your plan, say so in your report instead of merging over it.""".stripMargin

  /**
   * 单拍：对每个在飞 delegate 推进记账，**只在可行动且聚合窗开放时**注入一条。
   *
   *   - 首次见（无记账）⇒ 只记账（baseline，不通知——不知道「从哪来」）；
   *   - tip 未变 ⇒ 若挂账已到窗口 ⇒ 补发聚合通知（否则零动作）；
   *   - tip 变了 ⇒ 记账 moves/pending；**非可行动 ⇒ 只记账不通知**（默认静默）；
   *     可行动 ⇒ 挂账；到窗口才注入（[[WindowMs]] 内最多一条）。
   *
   * @return 本拍实际注入的通知摘要（审计/测试读数）
   */
  def scan(port: Port): IO[List[String]] =
    for
      recs <- port.delegates
      live <- port.liveSessionIds
      prev <- port.loadState
      now <- port.nowMs
      pairs = trackable(recs).flatMap { r =>
        for
          base <- r.baseBranch
          // 自己的分支 = 「我会不会真的撞上」的另一半（dispatcher 终态批 2026-11-05：
          // 改前这里把 **base 分支**当成本 delegate 的分支传给三段合并 ⇒ 两次比较
          // 同一棵树的 tip 与其自身 base ⇒ C1「真冲突」在结构上永不成立，只有 C2
          // 历史改写能发通知——「少发通知」是靠漏掉真冲突换来的。散文自查：判据
          // C1 的对象必须是「我的分支 vs 新 base」）。
          mine <- r.branch
          ws <- r.projectWorkspace.orElse(Some(r.cwd))
          tip <- port.headTip(ws, base)
        yield (r, ws, base, mine, tip)
      }
      stepped <- pairs.traverse { case (r, ws, base, mine, tip) =>
        prev.get(r.id) match
          case None =>
            // 首见（派发期快照缺席）= 只记账当基线。绝不因「不知道从哪来」而通知。
            IO.pure(r.id -> ((Entry(baselineTip = tip, lastSeenTip = tip), Option.empty[String])))
          case Some(e) =>
            step(port, r, ws, base, mine, tip, e, now, live.contains(r.id)).map(out => r.id -> out)
      }
      notices = stepped.flatMap { case (_, (_, n)) => n }
      // 记账：跟踪项写当前 Entry；**registry 里已消失的 id 顺手清掉**（零残留膨胀）。
      known = recs.iterator.map(_.id).toSet
      next = prev.filter { case (id, _) => known.contains(id) } ++ stepped.map { case (id, (e, _)) => id -> e }
      _ <- if next == prev then IO.unit else port.saveState(next)
    yield notices

  /** 单条记录的推进（纯逻辑 + 端口读数）。 */
  private def step(
    port: Port,
    r: DelegateRegistry.Record,
    ws: String,
    base: String,
    mine: String,
    tip: String,
    e: Entry,
    now: Long,
    live: Boolean
  ): IO[(Entry, Option[String])] =
    if e.lastSeenTip == tip then flushIfDue(port, r, ws, base, mine, e, now, live)
    else
      val oldTip = e.lastSeenTip
      // C1 的对象 = **我的分支** vs 新 base tip（见 scan 的 `mine` 注）。
      val conflicts = port.conflictFiles(ws, mine, tip).distinct
      val rewritten = port.baseRewritten(ws, oldTip, tip)
      val counted = e.copy(
        lastSeenTip = tip,
        moves = e.moves + 1,
        pendingMoves = e.pendingMoves + 1,
        pendingConflicts = e.pendingConflicts + (if conflicts.nonEmpty then 1 else 0),
        pendingFiles = (e.pendingFiles ++ conflicts).distinct.take(MaxPendingFiles),
        pendingRewritten = e.pendingRewritten || rewritten
      )
      // 🔴 默认静默（作者令 2026-11-05 第一条与第二条）：base 前进**本身不构成任何
      // 行动要求**——每个 delegate 跑在独立 worktree 里，别的 delegate 的合并不会
      // 污染它的工作树；节点跑在 dispatcher 的 seat 内，base 变动由 dispatcher 统一
      // 处理。⇒ 不可行动的移动只记进面板计数器（moves），不产生任何消息。
      flushIfDue(port, r, ws, base, mine, counted, now, live)

  /**
   * **唤醒价值判据**（纯函数，spec 判红锚 · dispatcher 终态批 2026-11-05）。
   *
   * 作者令：「对**无在飞工作且无相关冲突**的会话不再反复投递；不得因为少发通知而
   * 漏掉真正需要重跑的 base 移动。」「无相关冲突」已由 [[Entry.actionablePending]]
   * （C1 真冲突 / C2 历史改写）承担，本函数补前半句：
   *
   *   - `inFlight = Some(true)` —— 作用域图里还有非终态节点（判据单点
   *     [[DelegateInflightGate.read]]）⇒ 它还在干活，冲突通知有行动面 ⇒ 投递；
   *   - `inFlight = Some(false)` + 分支领先提交 > 0 —— 那批提交还要落地，撞上 base
   *     就是真麻烦 ⇒ 投递；
   *   - `inFlight = Some(false)` + 领先 0 —— **无活可干**：没有可重跑的节点，也没有
   *     待落地的提交 ⇒ 唤醒只能开一个空回合（实测形态：一条 base-moved 把已收口的
   *     dispatcher 会话拽起来跑一轮，再收尾再被拽起，永不收敛）⇒ 静默；
   *   - `inFlight = None`（读面不可用）⇒ **投递**（宁可多发一条，绝不漏掉真需要重跑
   *     的那一次移动）。
   */
  def worthWaking(inFlight: Option[Boolean], unmergedCommits: Option[Int]): Boolean =
    inFlight match
      case Some(true) => true
      case None => true
      case Some(false) => unmergedCommits.exists(_ > 0)

  /** 聚合窗到点且挂账可行动 ⇒ 注入一条；否则零动作（挂账留着下次拍再判）。 */
  private def flushIfDue(
    port: Port,
    r: DelegateRegistry.Record,
    ws: String,
    base: String,
    mine: String,
    e: Entry,
    now: Long,
    live: Boolean
  ): IO[(Entry, Option[String])] =
    val cleared = e.copy(
      pendingMoves = 0,
      pendingConflicts = 0,
      pendingFiles = Nil,
      pendingRewritten = false
    )
    // `lastNotifyMs == 0` = 从未通知过 ⇒ 窗恒开（不能用 `now - 0 >= WindowMs`：那只在
    // 时钟绝对值够大时才成立，语义是错的）。
    val windowOpen = e.lastNotifyMs == 0L || now - e.lastNotifyMs >= WindowMs
    if !e.actionablePending then IO.pure((e, None))
    else if !live then
      // 会话不 live = 不是本通道的活：丢弃挂账（唤醒后绝不补发过期漂移），记账照走。
      IO.pure((cleared, None))
    else if !windowOpen then IO.pure((e, None))
    else
      // 唤醒价值闸（见 [[worthWaking]]）：无在飞工作且无待落地提交 ⇒ 不唤醒，
      // 挂账**丢弃**（与 `!live` 同口径：唤醒后绝不补发过期漂移）。
      port.inFlightWork(r.id).flatMap { inFlight =>
        val unmerged = port.unmergedCommits(ws, base, mine)
        if !worthWaking(inFlight, unmerged) then
          logger
            .info(
              s"[base-drift] suppressed for ${r.id} (inFlight=${inFlight.map(_.toString).getOrElse("?")}, " +
                s"unmerged=${unmerged.map(_.toString).getOrElse("?")}): nothing to redo — aggregate cleared without waking the session " +
                s"(${e.pendingMoves} move(s), ${e.pendingConflicts} conflicting)"
            )
            .as((cleared, None))
        else notifyNow(port, r, base, e, cleared, now)
      }

    end if

  end flushIfDue

  /** 真注入腿（聚合窗 + 可行动 + 有活可干三条都过）。 */
  private def notifyNow(
    port: Port,
    r: DelegateRegistry.Record,
    base: String,
    e: Entry,
    cleared: Entry,
    now: Long
  ): IO[(Entry, Option[String])] =
    val text = message(r.id, base, e)
    port
      .notify(r.id, text)
      .map { ok =>
        if ok then
          (
            cleared.copy(lastNotifyMs = now),
            Some(
              s"${r.id}: $base +${e.pendingMoves} move(s) since last notice, " +
                s"${e.pendingConflicts} conflicting (${e.pendingFiles.size} file(s) listed)"
            )
          )
        else (cleared, None)
      }
      .handleErrorWith { e2 =>
        logger.warn(s"[base-drift] notify failed for ${r.id}: ${e2.getMessage}").as((cleared, None))
      }

  end notifyNow

  /**
   * 跟踪面闸（**节点结构性排除点**）：只有「在册 ∧ 未归档 ∧ 未取消 ∧ 真有分支与基
   * 分支」的 delegate 记录在跟踪范围。节点（AgentFlow step）跑在 dispatcher 的 seat
   * 内、不建分支与 worktree、**从不写 [[DelegateRegistry]]** ⇒ 结构上零命中，节点
   * 侧这条通知永不发出（作者令第二条）。
   *
   * 会话是否 live **不进本闸**（进 [[flushIfDue]]）：已收尾会话照样推进记账，唤醒后
   * 不补发过期漂移。
   */
  private def trackable(recs: List[DelegateRegistry.Record]): List[DelegateRegistry.Record] =
    recs.filter(r =>
      r.archivedAt.isEmpty && r.cancelRequestedAt.isEmpty &&
        r.branch.isDefined && r.baseBranch.isDefined
    )

  // ── 面板状态面（作者令第五条：「base 动了」这个事实放面板，不进消息流）───────

  /** 全量状态（面板读面用；读盘失败 = 空表）。 */
  def statuses(): Map[String, Entry] = readState()

  /**
   * 面板投影（纯函数，测试可直喂状态表）：`{ "<delegateId>": {moves, pendingMoves,
   * pendingConflicts, pendingFiles, lastNotifyMs, baselineTip, lastSeenTip} }`
   * ——**只含 moves > 0 的行**（没动过的行不占面板）。
   */
  def statusProjection(st: Map[String, Entry]): Json =
    val moved = st.filter { case (_, e) => e.moves > 0 }
    Json.fromJsonObject(JsonObject.fromMap(moved.view.mapValues(_.toJson).toMap))

  /** 面板投影（读盘版；挂入 `GET /api/chains` 的行 `baseDrift` 键）。 */
  def statusJson(): Json = statusProjection(statuses())

  // ── 状态文件（v2 JSON；v1 文本行原样迁移，绝不补发）────────────────────────

  private def readState(): Map[String, Entry] =
    val p = statePath
    if !os.exists(p) then Map.empty
    else
      val raw = scala.util.Try(os.read(p)).toOption.getOrElse("").trim
      if raw.isEmpty then Map.empty
      else if raw.startsWith("{") then
        parse(raw).toOption
          .flatMap(_.hcursor.downField("entries").as[Map[String, Json]].toOption)
          .map(_.flatMap { case (id, j) => Entry.fromJson(j).map(id -> _) })
          .getOrElse(Map.empty)
      else
        // v1 迁移：`<delegateId> <tip>` 逐行；baseline = lastSeen = tip，moves = 0
        // （不追溯历史 ⇒ 升级本身零通知）。
        raw.linesIterator
          .map(_.trim)
          .filter(_.nonEmpty)
          .flatMap { line =>
            line.split("\\s+", 2) match
              case Array(k, v) if k.nonEmpty && v.nonEmpty => Some(k -> Entry(baselineTip = v, lastSeenTip = v))
              case _ => None
          }
          .toMap

      end if

    end if

  end readState

  private def writeState(state: Map[String, Entry]): Unit =
    try
      os.makeDir.all(statePath / os.up)
      val body =
        Json
          .obj(
            "version" -> StateVersion.asJson,
            "entries" -> Json.fromJsonObject(
              JsonObject.fromMap(state.toList.sortBy(_._1).map((k, e) => k -> e.toJson).toMap)
            )
          )
          .spaces2
      os.write.over(statePath, body)
    catch case e: Throwable => logger.warn(s"[base-drift] state write failed: ${e.getMessage}")

end BaseDriftWatch
