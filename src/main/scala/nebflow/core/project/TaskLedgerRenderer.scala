package nebflow.core.project

/**
 * TaskLedgerRenderer -- the single rendering point of the unified ledger (taskunify
 * batch, 2026-09-24).
 *
 * Shared by two consumers: the `Task` tool's `list`/`show` line text (Nebula's write
 * face) and `TaskInfo`'s read-only rendering of the attributed single entry (the
 * dispatcher / node read face). Pure functions -- zero IO, zero store access.
 *
 * Field face (ruling F): the output follows the current `show` field set
 * (`TaskBoardTool.scala:160-166`) with the **board-only fields removed** (no `note`
 * field -- notes live on the timeline; no per-project board context). A note is truncated
 * per [[NoteShowMaxChars]] with an "N more" tail marker; terminal entries go through
 * [[renderArchived]].
 *
 * Degradation discipline: over budget = drop whole lines + a counted tail note, **never
 * cut a line in half, never drop silently** (visibly truncated).
 */
object TaskLedgerRenderer:

  // ---- Cap constants (centralized in one place) ----
  val TitleMaxChars = 40 // compact-line title truncation

  // ---- show rendering budgets ----
  val NoteShowMaxChars        = 16_000 // current note (one timeline entry) render cap = writer-side NoteCapChars
  val TimelineNoteMaxVersions = 50     // note-mainline read window (**number of note-class events**)
  val TimelineNoteMaxChars    = 24_000 // note-mainline char budget (over budget drops whole oldest entries, never half an entry)
  val TimelineContentMaxChars = 10_000 // per-side content render cap (over cap = explicit truncation)
  val TimelineStateMaxLines   = 30     // state-class secondary section line count (minimal lines)
  val TimelineStateMaxChars   = 3_000  // state-class secondary section char budget
  val ReverseDepsShowMax      = 20     // reverse-dependency lookup (who depends on me) count
  val ShowHardCapChars        = 48_000 // final hard truncation for show (< the tool result hard cap of 50,000)

  /** Timeline-window tail marker: N more entries not shown (**explicit**, never dropped
    * silently). */
  def moreTail(n: Int, what: String): String = s"(+$n more $what not shown)"

  def truncate(s: String, max: Int): String =
    if s.length <= max then s else s.take(math.max(0, max - 1)) + "…"

  /** Dependency-not-closed test: any **known** entry inside `blocks` has not reached a
    * terminal state (unknown ids do not count -- the write side already rejected them).
    * The criterion is [[TaskLedgerStore.Status.isTerminal]] (**both terminal states count
    * as closed**). */
  def depsOpen(e: TaskEntry, tasks: List[TaskEntry]): Boolean =
    e.blocks.exists(dep => tasks.find(_.id == dep).exists(t => !TaskLedgerStore.Status.isTerminal(t.status)))

  /** Compact-line grammar: `#<id>[<status> @<assignee>] <title truncated to 40>` +
    * (→node <id>) + ⚠deps-open + (parent #N). */
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

  /** One entry's detail block: all fields + links + current dependency state +
    * **reverse dependencies** (who depends on me) + parent chain / direct sub-entries +
    * the note timeline (main section) + the state-class secondary section. */
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

  /** Parent chain (walking up the parent edges, at most 5 hops + a depth hint). */
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

  /** Archive hit (the degraded path): the main ledger no longer has this id (pruned after
    * 30 days) but the history file still holds its records ⇒ render the timeline in
    * degraded mode, **do not error out**; the ledger fields are honestly marked as
    * "cleaned up, unavailable". */
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

  /** `TaskInfo`'s read-only rendering of the attributed single entry (ruling F): **the
    * same granularity** (the dispatcher and a node see identical text), no board-only
    * fields; a note is truncated per [[NoteShowMaxChars]] with an "N more" tail marker. */
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
  // Note timeline rendered in two sections: main = note-class (restorable), secondary = minimal state-class lines
  // ------------------------------------------------------------------

  /** **Main section (the note timeline)**: ascending time order; each entry renders
    * `from` (origin domain) + timestamp + body. Assembled entry-by-entry as whole blocks;
    * over the char budget the oldest whole blocks are dropped (**explicitly**). */
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

  /** One note entry's block (1 header line + body lines). */
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

  /** One side's content lines (multi-line indented; over the cap = **explicit**
    * truncation -- never dropped silently). */
  private def contentLines(label: String, body: String, fullLen: Option[Int]): List[String] =
    val cut = body.length > TimelineContentMaxChars
    val shown = if cut then body.take(TimelineContentMaxChars) else body
    val head = if cut then
      s"      $label (showing first $TimelineContentMaxChars of ${fullLen.getOrElse(body.length)} chars)"
    else s"      $label:"
    head :: shown.split("\n", -1).toList.map(ln => s"        $ln")

  /** **Secondary section (state-class events)**: minimal lines, a dual budget on line
    * count and characters, newest preferred, dropping whole oldest lines -- the note
    * mainline is never drowned out by state-class events. */
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

  /** Minimal state-class line: `<at> <kind> [detail] actor=...`. */
  def minimalLine(ev: TaskLedgerEvent): String =
    List(
      Some(s"${ev.at} ${ev.kind}"),
      ev.from.map(f => s"from=$f"),
      ev.detail.map(d => s"detail=${short(d)}"),
      Some(s"actor=${ev.actor}")
    ).flatten.mkString(" ")

  private def short(s: String): String = truncate(s.replace("\n", " ⏎ "), 120)

  /** Final hard truncation: cut at the last newline before the char cap (never producing a
    * half line) and append a visible truncation note. */
  private def hardCap(out: String, tail: String): String =
    if out.length + tail.length <= ShowHardCapChars then out
    else
      val room = math.max(0, ShowHardCapChars - tail.length)
      val cut = out.take(room)
      val at = cut.lastIndexOf('\n')
      (if at > 0 then cut.take(at) else cut) + tail

  /** Generic whole-line assembly (the single point of the degradation discipline):
    * header + as many whole lines as fit (≤ maxLines) + (when anything was dropped) a tail
    * note + footer; total output ≤ maxChars; a line is either taken whole or dropped
    * whole. */
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
    def tail(d: Int): String = s"+$d more -- use list to browse, show for one entry's full text"
    def total(ts: List[String], d: Int): Int =
      headLen + (if ts.isEmpty then 0 else ts.mkString("\n").length) +
        (if d > 0 then 1 + tail(d).length else 0) + footLen
    while taken.nonEmpty && total(taken, dropped) > maxChars do
      taken = taken.init
      dropped += 1
    val parts = header.toList ++ taken ++ (if dropped > 0 then List(tail(dropped)) else Nil) ++ footer
    parts.mkString("\n")

end TaskLedgerRenderer
