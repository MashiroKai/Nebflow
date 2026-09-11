package nebflow.core.project

import munit.FunSuite

/**
 * TaskBoardRenderer spec（TaskBoard 批 1：规格 §3a/§3b 渲染格式 + 验收⑤注入超限
 * 降级不超预算）。renderer 纯函数——直接构条目，无 store、无 IO。
 *
 * 覆盖：
 *  - 紧凑行文法逐元素（#<id>[<status> @<assignee>] <title截40> + (→node) +
 *    ⚠deps-open + ⚠node-done 纯 join）；
 *  - 分发器块：小板精确形态；空板 → 空串；
 *  - 验收⑤：50 条长标题 → ≤1200 字符且含 +N more、无半行截断（行要么完整要么
 *    被丢弃）；节点全板速览 ≤500 字符/≤10 行；目标行整行 ≤120；note ≤300；
 *  - 行数上限（DispatcherMaxLines=20）+ 尾注计数精确；
 *  - 上限常量与规格 §3a 一致（漂移即红）。
 */
class TaskBoardRendererSpec extends FunSuite:

  import TaskBoardStore.Assignee.*
  import TaskBoardStore.Status.*

  private def e(
    id: String,
    title: String,
    status: String = Open,
    assignee: Option[String] = None,
    nodeId: Option[String] = None,
    note: Option[String] = None,
    blocks: List[String] = Nil
  ): TaskBoardEntry =
    TaskBoardStore.Entry(id, title, status, assignee, nodeId, Nil, note, blocks)

  // ===== ① compactLine 紧凑行文法 =====

  test("compactLine 文法：#<id>[<status> @<assignee>] <title截40> + (→node)/⚠deps-open/⚠node-done"):
    val board = List(
      e("1", "上游"),
      e("2", "实现解析器" + "x" * 60, InProgress, Some("n-impl-x"), Some("n-impl-x"), blocks = List("1")))
    assertEquals(TaskBoardRenderer.compactLine(board(0), board), "#1[open] 上游", "无 assignee 省略 @ 段")
    val l2 = TaskBoardRenderer.compactLine(board(1), board)
    assert(l2.startsWith("#2[in_progress @n-impl-x] "), l2)
    assert(l2.contains("…"), "标题超 40 截断带省略号")
    assert(l2.contains("(→node n-impl-x)"), l2)
    assert(l2.contains("⚠deps-open"), l2)
    assert(!l2.contains("⚠node-done"), "无终态映射 → 无漂移标记")
    val l2d = TaskBoardRenderer.compactLine(board(1), board, Map("n-impl-x" -> "completed"))
    assert(l2d.contains("⚠node-done"), "链接节点已终态 + 未 close → 漂移标记")
    // 已 close 任务不标 node-done（无漂移）
    val doneT = e("3", "已关闭", Done, Some("n-z"), Some("n-z"))
    assert(!TaskBoardRenderer.nodeDone(doneT, Map("n-z" -> "completed")))
    // truncate 结果 ≤ max（含省略号）
    assertEquals(TaskBoardRenderer.truncate("a" * 100, 40).length, 40)
    assertEquals(TaskBoardRenderer.truncate("短", 40), "短")

  // ===== ② 分发器块形态 =====

  test("renderDispatcher：小板精确形态（标签+紧凑行）；空板 → 空串"):
    val board = List(
      e("1", "拍板：注入上限", Open, Some(Author)),
      e("2", "定 schema", Open, Some(Dispatcher)),
      e("3", "实现解析器", InProgress, Some("n-impl-x"), Some("n-impl-x")))
    val out = TaskBoardRenderer.renderDispatcher(board)
    assertEquals(out.split("\n").toList, List(
      "<task-board>",
      "#1[open @author] 拍板：注入上限",
      "#2[open @dispatcher] 定 schema",
      "#3[in_progress @n-impl-x] 实现解析器 (→node n-impl-x)",
      "</task-board>"))
    assertEquals(TaskBoardRenderer.renderDispatcher(Nil), "", "空板 → 空串（接线侧整块省略）")

  // ===== ③ 验收⑤：注入超限降级不超预算 =====

  test("验收⑤：50 条长标题 → 分发器渲染 ≤1200 字符且含 +N more；无半行截断（行要么完整要么被丢弃）"):
    val board = (1 to 50).map(i => e(i.toString, s"长标题任务$i-" + "很长的标题内容" * 20)).toList
    val out = TaskBoardRenderer.renderDispatcher(board)
    assert(out.length <= TaskBoardRenderer.DispatcherMaxChars, s"${out.length} > 1200")
    assert(out.contains("more — 用 list 查看"), "超限必有尾注")
    // 无半行截断：每行要么标签/尾注，要么恰好等于某条完整紧凑行
    val expected = board.map(t => TaskBoardRenderer.compactLine(t, board)).toSet
    out.split("\n").toList.foreach { ln =>
      assert(ln == "<task-board>" || ln == "</task-board>" || ln.startsWith("+") || expected.contains(ln),
        s"半行截断嫌疑: $ln")
    }
    val bodyLines = out.split("\n").toList.filter(expected.contains)
    assert(bodyLines.nonEmpty && bodyLines.size < 50, s"装入 ${bodyLines.size} 行、丢弃其余")
    assert(bodyLines.size <= TaskBoardRenderer.DispatcherMaxLines)

  test("行数上限：50 条短标题 → 恰 DispatcherMaxLines=20 任务行 + 尾注 +30 more；总长仍 ≤1200"):
    val board = (1 to 50).map(i => e(i.toString, s"任务$i")).toList
    val out = TaskBoardRenderer.renderDispatcher(board)
    assert(out.contains("+30 more — 用 list 查看"), s"尾注计数 = 50 - 20: $out")
    assertEquals(out.split("\n").toList.count(_.startsWith("#")), TaskBoardRenderer.DispatcherMaxLines)
    assert(out.length <= TaskBoardRenderer.DispatcherMaxChars)

  test("验收⑤：节点全板速览 ≤500 字符、≤NodeSummaryMaxLines 行、含尾注；目标行整行 ≤120"):
    val board = (1 to 50).map(i => e(i.toString, s"任务$i", Open, Some(s"n-$i"))).toList
    val out = TaskBoardRenderer.renderNodeInject(Some("目标"), Nil, board)
    val lines = out.split("\n").toList
    val start = lines.indexOf("全板速览：")
    val section = lines.drop(start).mkString("\n")
    assert(section.length <= TaskBoardRenderer.NodeSummaryMaxChars, s"速览段 ${section.length} > 500")
    val taskLines = lines.drop(start + 1).count(_.startsWith("#"))
    assert(taskLines <= TaskBoardRenderer.NodeSummaryMaxLines, s"速览行数 $taskLines > 10")
    assert(section.contains("more — 用 list 查看"), section)

    // 目标行整行 ≤ GoalMaxChars（500 字符 goal → 整行恰 120）
    val goalOut = TaskBoardRenderer.renderNodeInject(Some("G" * 500), Nil, Nil)
    val goalLine = goalOut.split("\n").find(_.startsWith("项目目标：")).get
    assert(goalLine.length <= TaskBoardRenderer.GoalMaxChars, s"目标行 ${goalLine.length} > 120")
    assertEquals(goalLine.length, TaskBoardRenderer.GoalMaxChars, "长 goal 截到整行恰 120")

  // ===== ④ 节点块三段形态 =====

  test("renderNodeInject：三段形态（目标/工单/速览）+ note 截 300 + deps-open 明细行 + 逃生提示"):
    val board = List(
      e("1", "定 schema", Open, Some(Dispatcher)),
      e("2", "实现解析器", Open, Some("n-impl-x"), Some("n-impl-x"), Some("N" * 400), List("1")))
    val out = TaskBoardRenderer.renderNodeInject(Some("G" * 500), List(board(1)), board)
    val lines = out.split("\n").toList
    assertEquals(lines.head, "<task-board>")
    assertEquals(lines.last, "</task-board>")

    // ①目标行：整行 ≤120
    val goalLine = lines.find(_.startsWith("项目目标：")).get
    assertEquals(goalLine.length, TaskBoardRenderer.GoalMaxChars, "长 goal 截到整行恰 120")

    // ②工单详情块：自己的行不带 @assignee；note 截 300；deps-open 明细行
    assert(lines.contains("你的工单："))
    assert(lines.exists(_ == "  #2[open] 实现解析器"), lines.mkString("\n"))
    val noteLine = lines.find(_.contains("note: ")).get
    val noteText = noteLine.drop(noteLine.indexOf("note: ") + 6)
    assertEquals(noteText.length, TaskBoardRenderer.NoteRenderMaxChars, "note 恰截 300（含省略号）")
    val depsLine = lines.find(_.contains("⚠deps-open: ")).get
    assert(depsLine.contains("#1[open]"), depsLine)
    assert(depsLine.contains("可先置 blocked 记录等待"), depsLine)

    // ③速览段：同一任务以 assignee 版出现
    assert(out.contains("#2[open @n-impl-x]"), out)

    // goal None → 无目标行；mine 空 → 无工单段
    val noGoal = TaskBoardRenderer.renderNodeInject(None, Nil, board)
    assert(!noGoal.contains("项目目标："))
    assert(!noGoal.contains("你的工单："))
    assert(noGoal.contains("全板速览："))

    // 多行 note 折叠为 ⏎ 单行
    val multiline = TaskBoardRenderer.renderNodeInject(None,
      List(e("5", "多行", note = Some("第一行\n第二行"))), Nil)
    assert(multiline.contains("第一行 ⏎ 第二行"), multiline)

  test("renderNodeInject：三段皆空 → 空串；node-done 标记经映射进入速览行"):
    assertEquals(TaskBoardRenderer.renderNodeInject(None, Nil, Nil), "")
    val board = List(e("1", "被弃任务", InProgress, Some("n-gone"), Some("n-gone")))
    val out = TaskBoardRenderer.renderNodeInject(None, Nil, board, Map("n-gone" -> "cancelled"))
    assert(out.contains("⚠node-done"), out)

  // ===== ⑤ 常量卡规格值（漂移即红）=====

  test("上限常量与规格 §3a 一致"):
    assertEquals(TaskBoardRenderer.DispatcherMaxChars, 1200)
    assertEquals(TaskBoardRenderer.DispatcherMaxLines, 20)
    assertEquals(TaskBoardRenderer.GoalMaxChars, 120)
    assertEquals(TaskBoardRenderer.NoteRenderMaxChars, 300)
    assertEquals(TaskBoardRenderer.NodeSummaryMaxChars, 500)
    assertEquals(TaskBoardRenderer.NodeSummaryMaxLines, 10)

  // ===== ⑥ 升级批：注入尾注含 show 提示（R4）=====

  test("R4 注入尾注：超限尾注同时指向 list 与 show（旧「用 list 查看」前缀逐字保留）"):
    val board = (1 to 50).map(i => e(i.toString, s"长标题$i-" + "很长" * 30)).toList
    val out = TaskBoardRenderer.renderDispatcher(board)
    assert(out.contains("more — 用 list 查看"), "旧前缀保留（既有断言兼容）")
    assert(out.contains("show 看单条全文"), s"新 action 可发现性提示: $out")
    val node = TaskBoardRenderer.renderNodeInject(None, Nil, board)
    assert(node.contains("show 看单条全文"), node)

  // ===== ⑦ 升级批：show 渲染（R3/R8）=====

  private def ev(kind: String, at: String, field: Option[String] = None, prev: Option[String] = None,
      next: Option[String] = None, text: Option[String] = None, from: Option[String] = None,
      to: Option[String] = None, actor: String = "dispatcher"): TaskBoardEvent =
    TaskBoardEvent(at = at, kind = kind, id = Some("1"), actor = actor, field = field,
      prev = prev, next = next, text = text, from = from, to = to)

  test("R3/R8 renderShow：全字段段 + note 全文 + links + 依赖反查 + 两区时间线（note 主线在前、状态类极简在后）"):
    val tasks = List(
      e("1", "被查条目", Open, Some("dispatcher"), note = Some("当前 note 正文"), blocks = List("2")),
      e("2", "依赖项", Done),
      e("3", "依赖我的", Open, Some("n-a"), blocks = List("1")))
    val notes = TaskBoardHistory.ReadResult(events = List(
      ev("log", "2026-09-11T09:00:00Z", text = Some("补充：第一段记录")),
      ev("update", "2026-09-11T10:00:00Z", field = Some("note"), prev = Some("旧版做法 A"), next = Some("新版做法 B"))), total = 2)
    val states = TaskBoardHistory.ReadResult(events = List(
      ev("create", "2026-09-11T08:00:00Z"),
      ev("update", "2026-09-11T08:30:00Z", field = Some("status"), from = Some("open"), to = Some("in_progress"))), total = 2)
    val out = TaskBoardRenderer.renderShow(tasks.head, tasks, Map.empty, notes, states)
    assert(out.startsWith("#1[open @dispatcher] 被查条目"), out)
    assert(out.contains("note (10 chars):") && out.contains("当前 note 正文"), out)
    assert(out.contains("deps (1, this entry depends on): #2[done] 依赖项"), out)
    assert(out.contains("required by (1, entries depending on this): #3[open @n-a] 依赖我的"), out)
    // 主区：note 主线（含被覆盖前的那一版 —— before/after 两侧）
    assert(out.contains("note changes (2 shown of 2, oldest first):"), out)
    assert(out.contains("before") && out.contains("旧版做法 A"), out)
    assert(out.contains("after") && out.contains("新版做法 B"), out)
    assert(out.contains("appended") && out.contains("补充：第一段记录"), "log 追加段可读")
    // 次区：状态类极简行，且不与 note 主线平铺混杂（分区标题各自独立）
    assert(out.contains("state events (2 shown of 2, minimal"), out)
    assert(out.contains("field=status open→in_progress actor=dispatcher"), out)
    val noteIdx = out.indexOf("note changes")
    val stateIdx = out.indexOf("state events")
    assert(noteIdx >= 0 && stateIdx > noteIdx, s"note 主区必须在状态类之前: $out")

  test("R3 预算：note 超写入上限的存量 → 当前 note 明示截断；note 主线区整块丢弃明示；总长 ≤ ShowHardCapChars"):
    val long = "长" * 20_000
    val tasks = List(e("1", "超长条目", Open, note = Some(long)))
    val many = (1 to 40).map(i => ev("update", f"2026-09-11T10:${i}%02d:00Z", field = Some("note"),
      prev = Some("旧" * 12_000), next = Some("新" * 12_000))).toList
    val notes = TaskBoardHistory.ReadResult(events = many, total = 60, skipped = 2)
    val out = TaskBoardRenderer.renderShow(tasks.head, tasks, Map.empty, notes, TaskBoardHistory.ReadResult())
    assert(out.contains("showing first 16000"), "当前 note 可见截断")
    assert(out.contains("showing first 10000"), "单侧内容可见截断")
    assert(out.contains("older note change(s) not shown"), "整块丢弃明示")
    assert(out.contains("unreadable line(s) skipped"), "坏行计数可见")
    assert(out.length <= TaskBoardRenderer.ShowHardCapChars, s"硬顶内: ${out.length}")
    // 预算口径：不与工具结果硬顶 50,000 相撞；note 渲染上限 = 写入侧上限（漂移闸）
    assert(TaskBoardRenderer.ShowHardCapChars < 50_000)
    assertEquals(TaskBoardRenderer.NoteShowMaxChars, TaskBoardStore.NoteWriteMaxChars, "show 上限 = 写入上限")
    assert(TaskBoardRenderer.TimelineContentMaxChars >= TaskBoardRenderer.NoteShowMaxChars / 2)

  test("R8 renderArchived：主库已无该 id → [gone] 标记 + 主库字段如实「不可得」+ note 版本仍可读（禁回填）"):
    val notes = TaskBoardHistory.ReadResult(events = List(
      ev("update", "2026-09-11T10:00:00Z", field = Some("note"), prev = Some("第一版：做法 A"), next = Some("第二版：做法 B")),
      ev("close", "2026-09-11T11:00:00Z", field = Some("note"), prev = Some("第二版：做法 B"),
        next = Some("第二版：做法 B\n[done] 收尾"), from = Some("open"), to = Some("done"))), total = 2)
    val states = TaskBoardHistory.ReadResult(events = List(ev("prune", "2026-09-11T12:00:00Z", actor = "system")), total = 1)
    val out = TaskBoardRenderer.renderArchived("1", Nil, notes, states)
    assert(out.startsWith("#1[gone]"), out)
    assert(out.contains("cleaned up — NOT available"), out)
    assert(out.contains("禁") || out.contains("nothing is reconstructed or guessed"), out)
    assert(out.contains("第一版：做法 A") && out.contains("第二版：做法 B"), out)
    assert(out.contains("[done] 收尾"), out)
    assert(out.contains("state events"), out)

  test("升级批常量卡值（漂移即红）"):
    assertEquals(TaskBoardRenderer.TimelineNoteMaxVersions, 50)
    assertEquals(TaskBoardRenderer.TimelineNoteMaxChars, 24_000)
    assertEquals(TaskBoardRenderer.TimelineContentMaxChars, 10_000)
    assertEquals(TaskBoardRenderer.TimelineStateMaxLines, 30)
    assertEquals(TaskBoardRenderer.TimelineStateMaxChars, 3_000)
    assertEquals(TaskBoardRenderer.NoteShowMaxChars, 16_000)
    assertEquals(TaskBoardRenderer.ReverseDepsShowMax, 20)
    assertEquals(TaskBoardRenderer.ShowHardCapChars, 48_000)

end TaskBoardRendererSpec
