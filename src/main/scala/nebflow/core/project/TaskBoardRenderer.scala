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
 * 降级纪律（§3b）：超限=整行丢弃+计数尾注「+N more — 用 list 查看」，不截半行、
 * 不静默丢（「注入侧永不截断正文」裁定同族）；上限常量集中此处命名（MemoryBudget
 * 集中定价先例）。字符预算按整块输出计（含包裹标签/头尾行）。
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

  private val TailFmt = "+%d more — 用 list 查看"

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
