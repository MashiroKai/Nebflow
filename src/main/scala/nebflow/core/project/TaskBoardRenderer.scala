package nebflow.core.project

/**
 * TaskBoardRenderer —— 任务板渲染单点（TaskBoard 设计规格 §3a/§3b，2026-09-08）。
 * 两消费者共用：分发器注入块（renderDispatcher）与节点注入块（renderNodeInject），
 * 批 2 接线；工具 list 行文亦复用 compactLine。纯函数——零 IO、零 store 访问；
 * ⚠node-done 的 Flow Map join 本批为纯函数（入参 nodeId→终态映射），批 2 传真实
 * 映射（读同目录 flow-map.json）。
 *
 * 渲染格式（§3b）：
 *   - 紧凑行文法：`#<id>[<status> @<assignee>] <title截40>`，附标记
 *     `⚠deps-open`（依赖未闭环）/ `⚠node-done`（链接节点已终态但任务未 close）/
 *     `(→node <nodeId>)`（有链接时）。
 *   - 节点块三段：①项目目标一行（ProjectDef.description；None 则整行省略）
 *     ②自己名下工单详情块（含 note 与 ⚠deps-open 明细行）③全板速览紧凑行。
 *
 * 降级纪律（§3b）：超限=整行丢弃+计数尾注「+N more — 用 list 查看 · show 看单条全文」，
 * 不截半行、不静默丢（「注入侧永不截断正文」裁定同族）；上限常量集中此处命名
 * （MemoryBudget 集中定价先例）。字符预算按整块输出计（含包裹标签/头尾行）。
 *
 * 升级批（2026-09-11）新增 `renderShow` / `renderArchived` / `renderNoteTimeline` /
 * `renderStateEvents`（R3 详情查询 + R8 依赖反查 + R7 变更史渲染），并把注入尾注从
 * 「只用 list」扩为「list + show」（R4：新 action 的可发现性）。show 的主预算 =
 * 当前 note 16,000 / note 主线区 24,000 字符 / 状态次区 3,000 / 最终 48,000 硬截断，
 * 与 DispatcherMaxChars 同源：全部是**可见截断**，绝不静默丢。
 */
object TaskBoardRenderer:

  // ---- 上限常量（§3a，集中一处）----
  val DispatcherMaxChars  = 1200 // 分发器注入块总字符（含 <task-board> 标签与尾注）
  val DispatcherMaxLines  = 20   // 分发器注入块任务行数
  val GoalMaxChars        = 120  // 节点块目标行整行上限（含「项目目标：」前缀）
  val NoteRenderMaxChars  = 300  // 节点块工单 note 渲染上限
  val NodeSummaryMaxChars = 500  // 节点块全板速览段总字符（含段头与尾注）
  val NodeSummaryMaxLines = 10   // 节点块全板速览任务行数
  val TitleMaxChars       = 40   // 紧凑行标题截断

  // ---- show 渲染预算（R3/R8，升级批）----
  val NoteShowMaxChars        = 16_000 // 当前 note 渲染上限（= 写入侧上限 TaskBoardStore.NoteWriteMaxChars，单测锁一致）
  val TimelineNoteMaxVersions = 50     // note 主线区读取窗口（**note 类事件条数**；状态类不占此窗口）
  val TimelineNoteMaxChars    = 24_000 // note 主线区字符预算（超出从最旧的整条丢，绝不截半条）
  val TimelineContentMaxChars = 10_000 // 单侧内容（before/after/text）渲染上限（超出明示截断）
  val TimelineStateMaxLines   = 30     // 状态类次区条数（极简行；不参与 note 主线预算）
  val TimelineStateMaxChars   = 3_000  // 状态类次区字符预算
  val ReverseDepsShowMax      = 20     // 依赖反查（谁依赖我）条数
  val ShowHardCapChars        = 48_000 // show 最终硬截断（< 工具结果硬顶 Defaults.DefaultMaxResultSizeChars = 50_000）

  private val TailFmt = "+%d more — 用 list 查看 · show 看单条全文"


  /** 保证结果 ≤ max（含省略号）——预算敏感场景用（区别于 TaskListStore.truncate
    * 的 max+1 行为；那几处无硬预算，本处全部卡预算故收紧）。 */
  def truncate(s: String, max: Int): String =
    if s.length <= max then s else s.take(math.max(0, max - 1)) + "…"

  /** 依赖未闭环判定：blocks 内任一【已知】条目 status ≠ done（未知 id 不计——
    * 写入侧已拒，正常数据不出现）。 */
  def depsOpen(e: TaskBoardEntry, tasks: List[TaskBoardEntry]): Boolean =
    e.blocks.exists(dep => tasks.find(_.id == dep).exists(_.status != TaskBoardStore.Status.Done))

  /** ⚠node-done 判定（§2d）：链接节点已终态 ∧ 任务未 close → 只读漂移标记。 */
  def nodeDone(e: TaskBoardEntry, nodeTerminal: Map[String, String]): Boolean =
    e.nodeId.exists(nodeTerminal.contains) && e.status != TaskBoardStore.Status.Done

  /** 紧凑行文法：`#<id>[<status> @<assignee>] <title截40>` + (→node <id>) +
    * ⚠deps-open + ⚠node-done。assignee 缺省时方括号内省略 @ 段。 */
  def compactLine(
    e: TaskBoardEntry,
    tasks: List[TaskBoardEntry],
    nodeTerminal: Map[String, String] = Map.empty
  ): String =
    val asg = e.assignee.map(a => s" @$a").getOrElse("")
    val node = e.nodeId.map(n => s" (→node $n)").getOrElse("")
    val warns =
      (if depsOpen(e, tasks) then " ⚠deps-open" else "") +
        (if nodeDone(e, nodeTerminal) then " ⚠node-done" else "")
    s"#${e.id}[${e.status}$asg] ${truncate(e.title, TitleMaxChars)}$node$warns"

  /** 分发器注入块（§3a）：全板紧凑行，≤DispatcherMaxChars/DispatcherMaxLines，
    * 超限整行丢弃+尾注。空板返回空串（接线侧据此整块省略）。 */
  def renderDispatcher(
    entries: List[TaskBoardEntry],
    nodeTerminal: Map[String, String] = Map.empty
  ): String =
    if entries.isEmpty then ""
    else
      val lines = entries.map(compactLine(_, entries, nodeTerminal))
      assemble(Some("<task-board>"), lines, DispatcherMaxChars, DispatcherMaxLines, Some("</task-board>"))

  /** 节点注入块（§3a/§3b）：①目标行（goal None → 整行省略）②自己名下工单详情块
    * （mine 空 → 整段省略）③全板速览（≤NodeSummaryMaxChars/MaxLines，同降级纪律）。
    * 三段皆空 → 空串（接线侧据此整块省略）。 */
  def renderNodeInject(
    goal: Option[String],
    mine: List[TaskBoardEntry],
    all: List[TaskBoardEntry],
    nodeTerminal: Map[String, String] = Map.empty
  ): String =
    val sections = scala.collection.mutable.ListBuffer[String]()
    goal.map(_.replace("\n", " ").trim).filter(_.nonEmpty).foreach { g =>
      val prefix = "项目目标："
      sections += prefix + truncate(g, math.max(0, GoalMaxChars - prefix.length))
    }
    if mine.nonEmpty then
      sections += "你的工单："
      mine.foreach { e =>
        sections += s"  #${e.id}[${e.status}] ${truncate(e.title, TitleMaxChars)}"
        e.note.filter(_.nonEmpty).foreach { n =>
          sections += s"    note: ${truncate(n.replace("\n", " ⏎ "), NoteRenderMaxChars)}"
        }
        val openDeps = e.blocks
          .flatMap(dep => all.find(_.id == dep))
          .filterNot(_.status == TaskBoardStore.Status.Done)
        if openDeps.nonEmpty then
          sections += s"    ⚠deps-open: ${openDeps.map(d => s"#${d.id}[${d.status}]").mkString(", ")} —— 可先置 blocked 记录等待"
      }
    if all.nonEmpty then
      val lines = all.map(compactLine(_, all, nodeTerminal))
      sections += assemble(Some("全板速览："), lines, NodeSummaryMaxChars, NodeSummaryMaxLines, None)
    if sections.isEmpty then ""
    else sections.mkString("<task-board>\n", "\n", "\n</task-board>")

  // ------------------------------------------------------------------
  // show（R3/R8 详情渲染，升级批）——纯函数，零 IO
  // ------------------------------------------------------------------

  /** 单条条目详情块：全字段 + 当前 note 全文（可见截断）+ links + 依赖当前态 +
    * **依赖反查**（谁依赖我，≤ ReverseDepsShowMax + `(+N more)`）+ 变更史**两区**：
    * 主区 = note 内容变更（`before`/`after` 两侧可还原，时间序）+ 次区 = 状态类极简行。
    *
    * 预算依据（为什么不会撞工具结果硬顶 50,000）：当前 note ≤16,000 + note 主线区
    * ≤24,000 + 状态次区 ≤3,000 + 其余字段/依赖/反查 ~2,000 ≈ 45,000 <
    * ShowHardCapChars(48,000) < 50,000；极端存量（close 追加出来的超长 note、超长
    * 历史行）走「单侧可见截断 + 整块丢弃 + 最终硬截断」三道闸，全部明示。 */
  def renderShow(
    e: TaskBoardEntry,
    tasks: List[TaskBoardEntry],
    nodeTerminal: Map[String, String] = Map.empty,
    notes: TaskBoardHistory.ReadResult = TaskBoardHistory.ReadResult(),
    states: TaskBoardHistory.ReadResult = TaskBoardHistory.ReadResult()
  ): String =
    val b = scala.collection.mutable.ListBuffer[String]()
    val asg = e.assignee.map(a => s" @$a").getOrElse("")
    b += s"#${e.id}[${e.status}$asg] ${e.title}"
    b += s"  status: ${e.status}    created: ${e.createdAt.getOrElse("(none)")}    " +
      s"updated: ${e.updatedAt.getOrElse("(none)")}    closed: ${e.closedAt.getOrElse("(none)")}"
    b += s"  assignee: ${e.assignee.getOrElse("(unassigned)")}"
    e.nodeId.foreach { n =>
      b += s"  nodeId: $n (→node $n)${if nodeDone(e, nodeTerminal) then " ⚠node-done" else ""}"
    }
    if e.links.nonEmpty then
      b += s"  links (${e.links.size}, not validated for reachability): ${e.links.mkString(", ")}"
    if e.blocks.nonEmpty then
      val parts = e.blocks.map { dep =>
        tasks.find(_.id == dep) match
          case Some(d) =>
            val mark = if d.status == TaskBoardStore.Status.Done then "" else " ⚠"
            s"#${d.id}[${d.status}] ${truncate(d.title, TitleMaxChars)}$mark"
          case None => s"#$dep[?]"
      }
      b += s"  deps (${e.blocks.size}, this entry depends on): ${parts.mkString(", ")}"
    val dependents = tasks.filter(t => t.blocks.contains(e.id))
    if dependents.nonEmpty then
      val shown = dependents.take(ReverseDepsShowMax)
      val more = if dependents.size > shown.size then s" (+${dependents.size - shown.size} more)" else ""
      b += s"  required by (${dependents.size}, entries depending on this): " +
        shown.map(compactLine(_, tasks, nodeTerminal)).mkString("; ") + more
    e.note.filter(_.nonEmpty).foreach { n =>
      val cut = n.length > NoteShowMaxChars
      b += s"  note (${n.length} chars${if cut then s" — showing first $NoteShowMaxChars" else ""}):"
      val body = if cut then n.take(NoteShowMaxChars) else n
      body.split("\n", -1).foreach(ln => b += s"    $ln")
    }
    b ++= renderNoteTimeline(notes)
    b ++= renderStateEvents(states)
    hardCap(
      b.mkString("\n"),
      s"\n… (show truncated at $ShowHardCapChars chars — the full note stays in task-board.json; " +
        s"the full history in ${TaskBoardHistory.FileName})")

  /** 归档命中（R8 降级路径）：主库已无该 id（30 天 prune），但史文件仍有它的记录
    * ⇒ 降级渲染时间线，**不报错退出**；主库字段如实标「已清理，不可得」——
    * 禁编造、禁回填。库与史都没有的 id 由 store 走既有 TBOARD_NOT_FOUND 路径。 */
  def renderArchived(
    id: String,
    tasks: List[TaskBoardEntry],
    notes: TaskBoardHistory.ReadResult,
    states: TaskBoardHistory.ReadResult
  ): String =
    val b = scala.collection.mutable.ListBuffer[String]()
    b += s"#${id}[gone] — not on the board any more (done entries are pruned ${TaskBoardStore.DoneTtlDays} days after closedAt)."
    b += "  board fields (title/status/assignee/nodeId/timestamps/note/links/blocks): cleaned up — NOT available " +
      "(the board entry is gone; nothing is reconstructed or guessed here)."
    b += s"  board now: ${tasks.size} entr${if tasks.size == 1 then "y" else "ies"} — action=list to see them."
    b ++= renderNoteTimeline(notes)
    b ++= renderStateEvents(states)
    hardCap(
      b.mkString("\n"),
      s"\n… (show truncated at $ShowHardCapChars chars — see ${TaskBoardHistory.FileName} for the rest)")

  // ------------------------------------------------------------------
  // 变更史两区渲染：主区 = note 内容变更（可还原），次区 = 状态类极简行
  // ------------------------------------------------------------------

  /** **主区（note 内容变更）**：时间序升序；`update` 覆盖与 `close` 追加都渲染
    * `before` / `after` 两侧全文（被覆盖前的那一版必须读得出）；`log` 渲染追加段。
    * 逐条整块装配，超出字符预算从最旧的整块丢（明示），单侧内容超
    * `TimelineContentMaxChars` 明示截断（全文仍在史文件里）。 */
  private def renderNoteTimeline(t: TaskBoardHistory.ReadResult): List[String] =
    if t.events.isEmpty then
      List("  note changes: (none recorded — this entry's note has never been changed; " +
        s"current content above, older versions in the rotated ${TaskBoardHistory.ArchiveFileName} if any)")
    else
      val blocks = scala.collection.mutable.ListBuffer[List[String]]()
      var used = 0
      var i = t.events.length - 1
      while i >= 0 && used + noteBlock(t.events(i)).map(_.length + 1).sum <= TimelineNoteMaxChars do
        val blk = noteBlock(t.events(i))
        blocks.prepend(blk)
        used += blk.map(_.length + 1).sum
        i -= 1
      val dropped = t.events.length - blocks.size
      val beyond = t.total - t.events.size
      val notes = List(
        Option.when(dropped > 0)(
          s"    (+$dropped older note change(s) not shown — char budget $TimelineNoteMaxChars; see ${TaskBoardHistory.FileName})"),
        Option.when(beyond > 0)(
          s"    (+$beyond older note change(s) beyond the $TimelineNoteMaxVersions-event window)"),
        Option.when(t.skipped > 0)(s"    (history: ${t.skipped} unreadable line(s) skipped)")
      ).flatten
      (s"  note changes (${blocks.size} shown of ${t.total}, oldest first):" :: blocks.toList.flatten) ++ notes

  /** 单条 note 变更块（含 1 行头 + 内容行）。 */
  private def noteBlock(ev: TaskBoardEvent): List[String] =
    if ev.kind == TaskBoardHistory.Kinds.Log then
      val body = ev.text.getOrElse("")
      List(
        s"    ${ev.at} log  +${body.length} chars (actor=${ev.actor}${if ev.links.nonEmpty then s", links=${ev.links.mkString(",")}" else ""}${ev.detail.map(d => s" — ${truncate(d, 120)}").getOrElse("")})"
      ) ++ contentLines("appended", body, None)
    else
      val label = if ev.kind == TaskBoardHistory.Kinds.Close then "close  [done] outcome appended" else "update  note replaced"
      val head = s"    ${ev.at} $label (${ev.prev.map(_.length).getOrElse(0)} → ${ev.next.map(_.length).getOrElse(0)} chars, actor=${ev.actor})"
      head :: (contentLines("before", ev.prev.getOrElse(""), Some(ev.prev.map(_.length).getOrElse(0))) ++
        contentLines("after", ev.next.getOrElse(""), Some(ev.next.map(_.length).getOrElse(0))))

  /** 单侧内容行（多行缩进；超限明示截断——**绝不静默丢**）。 */
  private def contentLines(label: String, body: String, fullLen: Option[Int]): List[String] =
    val pad = " " * (8 - label.length.min(7))
    val cut = body.length > TimelineContentMaxChars
    val shown = if cut then body.take(TimelineContentMaxChars) else body
    val head = s"      $label$pad:" + (if cut then s" (showing first $TimelineContentMaxChars of ${fullLen.getOrElse(body.length)} chars)" else "")
    head :: shown.split("\n", -1).toList.map(ln => s"        $ln")

  /** **次区（状态类事件）**：极简行（create / 非 note 字段的 update / 无 outcome 的
    * close / prune / quarantine / rotate），条数与字符双预算、暂新的优先，从最旧的
    * 整行丢——note 主线永不被状态类淹没。 */
  private def renderStateEvents(t: TaskBoardHistory.ReadResult): List[String] =
    if t.events.isEmpty then Nil
    else
      val lines = scala.collection.mutable.ListBuffer[String]()
      var used = 0
      var i = t.events.length - 1
      while i >= 0 && lines.size < TimelineStateMaxLines && used + minimalLine(t.events(i)).length + 6 <= TimelineStateMaxChars do
        val ln = "    " + minimalLine(t.events(i))
        lines.prepend(ln)
        used += ln.length + 1
        i -= 1
      val dropped = t.events.length - lines.size
      val beyond = t.total - t.events.size
      val notes = List(
        Option.when(dropped > 0)(s"    (+$dropped older state event(s) not shown)"),
        Option.when(beyond > 0)(s"    (+$beyond older state event(s) beyond the $TimelineStateMaxLines-event window)")
      ).flatten
      (s"  state events (${lines.size} shown of ${t.total}, minimal — status/structural, oldest first):" ::
        lines.toList) ++ notes

  /** 状态类极简行：`<at> <kind> [field=… from→to | old→new(摘要)] actor=… [detail=…]`。 */
  def minimalLine(ev: TaskBoardEvent): String =
    List(
      Some(s"${ev.at} ${ev.kind}"),
      ev.field.map(f => s"field=$f"),
      Option.when(ev.from.isDefined || ev.to.isDefined)(
        s"${ev.from.getOrElse("?")}→${ev.to.getOrElse("?")}"),
      Option.when(ev.field.isDefined && ev.field.contains("note") == false && (ev.prev.isDefined || ev.next.isDefined))(
        s"${ev.prev.map(short).getOrElse("(none)")} → ${ev.next.map(short).getOrElse("(none)")}"),
      Some(s"actor=${ev.actor}"),
      ev.detail.map(d => s"detail=${short(d)}")
    ).flatten.mkString(" ")

  private def short(s: String): String = truncate(s.replace("\n", " ⏎ "), 120)

  /** 最终硬截断：在字符上限前的最后一个换行处切（不产生半行），尾附可见截断说明。 */
  private def hardCap(out: String, tail: String): String =
    if out.length + tail.length <= ShowHardCapChars then out
    else
      val room = math.max(0, ShowHardCapChars - tail.length)
      val cut = out.take(room)
      val at = cut.lastIndexOf('\n')
      (if at > 0 then cut.take(at) else cut) + tail

  // ------------------------------------------------------------------
  // 整行装配（降级纪律唯一实现点）
  // ------------------------------------------------------------------

  /** 通用整行装配：header 固定行 + 尽可能多整行（≤maxLines）+（有丢弃时）尾注 +
    * footer。总输出（含头尾）≤ maxChars；行要么完整装入要么整体丢弃，绝不截半行。
    * 装不下时从尾部逐行回退（尾注随丢弃数变长，回退一步至多 +1 字符，收敛）。 */
  private def assemble(
    header: Option[String],
    lines: List[String],
    maxChars: Int,
    maxLines: Int,
    footer: Option[String]
  ): String =
    val headLen = header.map(_.length + 1).getOrElse(0) // + newline
    val footLen = footer.map(l => 1 + l.length).getOrElse(0)
    var taken = lines.take(maxLines)
    var dropped = lines.length - taken.length
    def tail(d: Int): String = TailFmt.format(d)
    def total(ts: List[String], d: Int): Int =
      headLen + (if ts.isEmpty then 0 else ts.mkString("\n").length) +
        (if d > 0 then 1 + tail(d).length else 0) + footLen
    while taken.nonEmpty && total(taken, dropped) > maxChars do
      taken = taken.init
      dropped += 1
    val parts = header.toList ++ taken ++ (if dropped > 0 then List(tail(dropped)) else Nil) ++ footer
    parts.mkString("\n")
end TaskBoardRenderer
