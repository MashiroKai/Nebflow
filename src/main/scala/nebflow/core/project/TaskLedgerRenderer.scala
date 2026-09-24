package nebflow.core.project

/**
 * TaskLedgerRenderer —— 合一本渲染单点（taskunify 实施批，2026-09-24）。
 *
 * 两个消费者共用：`Task` 工具的 `list`/`show` 行文（Nebula 写面）与 `TaskInfo`
 * 的只读归属单条渲染（分发器/节点读面）。纯函数——零 IO、零 store 访问。
 *
 * 字段面（裁定 F）：输出沿用现读 show 字段集（`TaskBoardTool.scala:160-166`）
 * **去掉板面专属字段**（无 `note` 字段——note 走时间线；无板面 per-project 语境）。
 * note 按 [[NoteShowMaxChars]] 截断 + 「还有 N 条」尾标；终态走 [[renderArchived]]。
 *
 * 降级纪律：超限 = 整行丢弃 + 计数尾注，**不截半行、不静默丢**（可见截断）。
 */
object TaskLedgerRenderer:

  // ---- 上限常量（集中一处）----
  val TitleMaxChars = 40 // 紧凑行标题截断

  // ---- show 渲染预算 ----
  val NoteShowMaxChars        = 16_000 // 当前 note（时间线单条）渲染上限 = 写入侧 NoteCapChars
  val TimelineNoteMaxVersions = 50     // note 主线区读取窗口（**note 类事件条数**）
  val TimelineNoteMaxChars    = 24_000 // note 主线区字符预算（超出从最旧的整条丢，绝不截半条）
  val TimelineContentMaxChars = 10_000 // 单侧内容渲染上限（超出明示截断）
  val TimelineStateMaxLines   = 30     // 状态类次区条数（极简行）
  val TimelineStateMaxChars   = 3_000  // 状态类次区字符预算
  val ReverseDepsShowMax      = 20     // 依赖反查（谁依赖我）条数
  val ShowHardCapChars        = 48_000 // show 最终硬截断（< 工具结果硬顶 50,000）

  /** 时间线窗口尾标：还有 N 条未展示（**明示**，绝不静默丢）。 */
  def moreTail(n: Int, what: String): String = s"(+$n more $what not shown)"

  def truncate(s: String, max: Int): String =
    if s.length <= max then s else s.take(math.max(0, max - 1)) + "…"

  /** 依赖未闭环判定：blocks 内任一【已知】条目未抵终态（未知 id 不计——写入侧已拒）。
    * 判据 = [[TaskLedgerStore.Status.isTerminal]]（**两个终态都算闭环**）。 */
  def depsOpen(e: TaskEntry, tasks: List[TaskEntry]): Boolean =
    e.blocks.exists(dep => tasks.find(_.id == dep).exists(t => !TaskLedgerStore.Status.isTerminal(t.status)))

  /** 紧凑行文法：`#<id>[<status> @<assignee>] <title截40>` + (→node <id>) + ⚠deps-open
    * + (parent #N)。 */
  def compactLine(
    e: TaskEntry,
    tasks: List[TaskEntry],
    nodeTerminal: Map[String, String] = Map.empty
  ): String =
    val asg = e.assignee.map(a => s" @$a").getOrElse("")
    val node = e.nodeId.map(n => s" (→node $n)").getOrElse("")
    val parent = e.parentId.map(p => s" (parent #$p)").getOrElse("")
    val deps = if depsOpen(e, tasks) then " ⚠deps-open" else ""
    val proj = e.project.map(p => s" [project:$p]").getOrElse("")
    s"#${e.id}[${e.status}$asg] ${truncate(e.title, TitleMaxChars)}$proj$node$parent$deps"

  /** 单条条目详情块：全字段 + links + 依赖当前态 + **依赖反查**（谁依赖我）+
    * 父链/直接子条目 + note 时间线（主区）+ 状态类次区。 */
  def renderShow(
    e: TaskEntry,
    tasks: List[TaskEntry],
    nodeTerminal: Map[String, String] = Map.empty,
    notes: TaskLedgerHistory.ReadResult = TaskLedgerHistory.ReadResult(),
    states: TaskLedgerHistory.ReadResult = TaskLedgerHistory.ReadResult()
  ): String =
    val b = scala.collection.mutable.ListBuffer[String]()
    val asg = e.assignee.map(a => s" @$a").getOrElse("")
    b += s"#${e.id}[${e.status}$asg] ${e.title}"
    b += s"  state: ${e.status}    created: ${e.createdAt.getOrElse("(none)")}    " +
      s"updated: ${e.updatedAt.getOrElse("(none)")}    closed: ${e.closedAt.getOrElse("(none)")}    " +
      s"completed: ${e.completedAt.getOrElse("(none)")}"
    b += s"  assignee: ${e.assignee.getOrElse("(unassigned)")}"
    e.project.foreach(p => b += s"  project: $p (free-form tag — the ledger is one instance-wide file)")
    e.nodeId.foreach(n => b += s"  nodeId: $n (→node $n)")
    if e.links.nonEmpty then
      b += s"  links (${e.links.size}, not validated for reachability): ${e.links.mkString(", ")}"
    if e.blocks.nonEmpty then
      val parts = e.blocks.map { dep =>
        tasks.find(_.id == dep) match
          case Some(d) =>
            val mark = if TaskLedgerStore.Status.isTerminal(d.status) then "" else " ⚠"
            s"#${d.id}[${d.status}] ${truncate(d.title, TitleMaxChars)}$mark"
          case None => s"#$dep[?]"
      }
      b += s"  deps (${e.blocks.size}, this entry depends on): ${parts.mkString(", ")}"
    val dependents = tasks.filter(_.blocks.contains(e.id))
    if dependents.nonEmpty then
      val shown = dependents.take(ReverseDepsShowMax)
      val more = if dependents.size > shown.size then s" (+${dependents.size - shown.size} more)" else ""
      b += s"  required by (${dependents.size}, entries depending on this): " +
        shown.map(compactLine(_, tasks, nodeTerminal)).mkString("; ") + more
    renderParentChain(e, tasks).foreach(b += _)
    val children = tasks.filter(_.parentId.contains(e.id))
    if children.nonEmpty then
      b += s"  sub-tasks (${children.size}): " +
        children.take(ReverseDepsShowMax).map(compactLine(_, tasks, nodeTerminal)).mkString("; ")
    b ++= renderNoteTimeline(notes)
    b ++= renderStateEvents(states)
    hardCap(
      b.mkString("\n"),
      s"\n… (show truncated at $ShowHardCapChars chars — the full note timeline stays in ${TaskLedgerHistory.FileName})")

  /** 父链（沿 parent 边上溯，最多 5 跳 + 深度提示）。 */
  private def renderParentChain(e: TaskEntry, tasks: List[TaskEntry]): Option[String] =
    e.parentId.filter(_.nonEmpty).map { p =>
      val chain = scala.collection.mutable.ListBuffer[String]()
      var cur: Option[String] = Some(p)
      var steps = 0
      while cur.isDefined && steps < 6 do
        val c = cur.get
        tasks.find(_.id == c) match
          case Some(t) =>
            chain += s"#${t.id}[${t.status}] ${truncate(t.title, TitleMaxChars)}"
            cur = t.parentId.filter(_.nonEmpty)
          case None =>
            chain += s"#$c[?]"
            cur = None
        steps += 1
      if cur.isDefined then
        s"  parent chain: ${chain.mkString(" ← ")} ← … (deeper than 5 levels)"
      else s"  parent chain: ${chain.mkString(" ← ")}"
    }

  /** 归档命中（降级路径）：主库已无该 id（30 天 prune），但史文件仍有它的记录
    * ⇒ 降级渲染时间线，**不报错退出**；主库字段如实标「已清理，不可得」。 */
  def renderArchived(
    id: String,
    tasks: List[TaskEntry],
    notes: TaskLedgerHistory.ReadResult,
    states: TaskLedgerHistory.ReadResult
  ): String =
    val b = scala.collection.mutable.ListBuffer[String]()
    b += s"#${id}[gone] — not in the ledger any more (terminal entries are pruned ${TaskLedgerStore.TerminalTtlDays} days after they reached the terminal state)."
    b += "  ledger fields (title/state/assignee/nodeId/timestamps/links/blocks): cleaned up — NOT available " +
      "(the entry is gone; nothing is reconstructed or guessed here)."
    b += s"  ledger now: ${tasks.size} entr${if tasks.size == 1 then "y" else "ies"} — action=list to see them."
    b ++= renderNoteTimeline(notes)
    b ++= renderStateEvents(states)
    hardCap(
      b.mkString("\n"),
      s"\n… (show truncated at $ShowHardCapChars chars — see ${TaskLedgerHistory.FileName} for the rest)")

  /** `TaskInfo` 只读归属单条渲染（裁定 F）：**同一颗粒度**（分发器与节点同文），
    * 无板面专属字段；note 按 [[NoteShowMaxChars]] 截断 + 「还有 N 条」尾标。 */
  def renderInfo(
    e: TaskEntry,
    tasks: List[TaskEntry],
    notes: TaskLedgerHistory.ReadResult,
    states: TaskLedgerHistory.ReadResult
  ): String =
    val b = scala.collection.mutable.ListBuffer[String]()
    b += s"#${e.id}[${e.status}] ${e.title}"
    b += s"  state: ${e.status}    assignee: ${e.assignee.getOrElse("(unassigned)")}"
    e.nodeId.foreach(n => b += s"  linked node: $n")
    if e.links.nonEmpty then b += s"  links: ${e.links.mkString(", ")}"
    if e.blocks.nonEmpty then
      val parts = e.blocks.map { dep =>
        tasks.find(_.id == dep) match
          case Some(d) => s"#${d.id}[${d.status}]"
          case None    => s"#$dep[?]"
      }
      b += s"  depends on: ${parts.mkString(", ")}"
    e.parentId.foreach(p => b += s"  sub-task of: #$p")
    val children = tasks.filter(_.parentId.contains(e.id))
    if children.nonEmpty then b += s"  sub-tasks: ${children.map(c => s"#${c.id}[${c.status}]").mkString(", ")}"
    b ++= renderNoteTimeline(notes)
    b ++= renderStateEvents(states)
    hardCap(
      b.mkString("\n"),
      s"\n… (truncated at $ShowHardCapChars chars — see ${TaskLedgerHistory.FileName} for the rest)")

  // ------------------------------------------------------------------
  // note 时间线两区渲染：主区 = note 类（可还原），次区 = 状态类极简行
  // ------------------------------------------------------------------

  /** **主区（note 时间线）**：时间序升序；每条渲染 `from`（来源域）+ 时间戳 + 正文。
    * 逐条整块装配，超出字符预算从最旧的整条丢（**明示**）。 */
  private def renderNoteTimeline(t: TaskLedgerHistory.ReadResult): List[String] =
    if t.events.isEmpty then
      List("  note timeline: (none yet — an entry is written automatically when Nebula Mails this task's dispatcher)")
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
          s"    (+$dropped older note entr${if dropped == 1 then "y" else "ies"} not shown — char budget $TimelineNoteMaxChars; see ${TaskLedgerHistory.FileName})"),
        Option.when(beyond > 0)(
          s"    (+$beyond older note entr${if beyond == 1 then "y" else "ies"} beyond the $TimelineNoteMaxVersions-event window)"),
        Option.when(t.skipped > 0)(s"    (history: ${t.skipped} unreadable line(s) skipped)")
      ).flatten
      (s"  note timeline (${blocks.size} shown of ${t.total}, oldest first):" :: blocks.toList.flatten) ++ notes

  /** 单条 note 条目块（1 行头 + 正文行）。 */
  private def noteBlock(ev: TaskLedgerEvent): List[String] =
    if ev.kind == TaskLedgerHistory.Kinds.Note then
      val body = ev.text.getOrElse("")
      val head =
        s"    ${ev.at} from=${ev.from.getOrElse(TaskLedgerHistory.Origins.Nebula)}" +
          (if ev.links.nonEmpty then s" links=${ev.links.mkString(",")}" else "") +
          s" (${body.length} chars)"
      head :: contentLines("body", body, None)
    else
      val head = s"    ${ev.at} ${ev.kind}" +
        ev.prev.map(p => s" (${p.length} → ${ev.next.map(_.length).getOrElse(0)} chars)").getOrElse("")
      head :: (ev.prev.map(p => contentLines("before", p, Some(p.length))).getOrElse(Nil) ++
        ev.next.map(n => contentLines("after", n, Some(n.length))).getOrElse(Nil))

  /** 单侧内容行（多行缩进；超限**明示**截断——绝不静默丢）。 */
  private def contentLines(label: String, body: String, fullLen: Option[Int]): List[String] =
    val cut = body.length > TimelineContentMaxChars
    val shown = if cut then body.take(TimelineContentMaxChars) else body
    val head = if cut then
      s"      $label (showing first $TimelineContentMaxChars of ${fullLen.getOrElse(body.length)} chars)"
    else s"      $label:"
    head :: shown.split("\n", -1).toList.map(ln => s"        $ln")

  /** **次区（状态类事件）**：极简行、条数与字符双预算、暂新的优先，从最旧的整行丢
    * ——note 主线永不被状态类淹没。 */
  private def renderStateEvents(t: TaskLedgerHistory.ReadResult): List[String] =
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
      (s"  state events (${lines.size} shown of ${t.total}, minimal, oldest first):" :: lines.toList) ++ notes

  /** 状态类极简行：`<at> <kind> [detail] actor=…`。 */
  def minimalLine(ev: TaskLedgerEvent): String =
    List(
      Some(s"${ev.at} ${ev.kind}"),
      ev.from.map(f => s"from=$f"),
      ev.detail.map(d => s"detail=${short(d)}"),
      Some(s"actor=${ev.actor}")
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

  /** 通用整行装配（降级纪律单点）：header + 尽可能多整行（≤maxLines）+（有丢弃时）
    * 尾注 + footer；总输出 ≤ maxChars；行要么完整装入要么整体丢弃。 */
  def assemble(
    header: Option[String],
    lines: List[String],
    maxChars: Int,
    maxLines: Int,
    footer: Option[String]
  ): String =
    val headLen = header.map(_.length + 1).getOrElse(0)
    val footLen = footer.map(l => 1 + l.length).getOrElse(0)
    var taken = lines.take(maxLines)
    var dropped = lines.length - taken.length
    def tail(d: Int): String = s"+$d more — 用 list 查看 · show 看单条全文"
    def total(ts: List[String], d: Int): Int =
      headLen + (if ts.isEmpty then 0 else ts.mkString("\n").length) +
        (if d > 0 then 1 + tail(d).length else 0) + footLen
    while taken.nonEmpty && total(taken, dropped) > maxChars do
      taken = taken.init
      dropped += 1
    val parts = header.toList ++ taken ++ (if dropped > 0 then List(tail(dropped)) else Nil) ++ footer
    parts.mkString("\n")

end TaskLedgerRenderer
