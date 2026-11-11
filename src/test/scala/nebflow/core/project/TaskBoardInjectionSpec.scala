package nebflow.core.project

import munit.FunSuite
import nebflow.actor.AgentState

/**
 * TaskBoard 注入拼装 + 身份透传 spec（TaskBoard 批 2：规格 §3a/§3c + §1d-2）。
 *
 * 覆盖：
 *  - newTaskPrompt/answerReentryPrompt 的 <task-board> 注入块拼装（§3a）：空串零注入
 *    （旧行为逐字节不变——DispatcherSpawnPromptSpec 回归基线不动）；非空时整块
 *    原样进入且【在任务文本之前】（拼装相对顺序 …→目录→记忆→任务板→任务文本）；
 *  - ProtocolFootnote 上报指引行（§3c）：末行措辞固化（spawn 注入链与节点协议
 *    同点同生命周期）；blocked 终态退役后值域仅 finish / pass·fail，零受阻申报出口；
 *  - 身份透传链（§1d-2）：AgentState 三字段（flowNodeId/isDispatcher/projectName）
 *    → SessionContext → extension 读回（AgentCore 据此透传 ToolContext——字段
 *    拷贝由编译器命名参数把关，此处锁数据链语义）。
 */
class TaskBoardInjectionSpec extends FunSuite:

  private val pd = ProjectDef(
    name = "inj-proj",
    workspace = "/tmp/nb-inj-spec-ws",
    agentFile = "/tmp/nb-inj-spec-ws/AGENTS.md",
    description = Some("注入拼装夹具项目目标"),
    createdAt = 0L
  )
  private val node = NodeDef(id = "n-inj", name = "注入夹具节点", agent = "general", createdAt = 0L)

  private val boardBlock =
    s"""<task-board>
       |全板速览：
       |  #1[open @n-inj] 夹具工单
       |</task-board>""".stripMargin

  // ===== 分发器注入（§3a）=====

  test("newTaskPrompt: 空板块零注入（旧行为不变）"):
    val p = ProjectActor.newTaskPrompt(pd, "任务文本X", "", "", "")
    assert(!p.contains("<task-board>"), p)
    assert(p.contains("Task: 任务文本X"), p)

  test("newTaskPrompt: 板块在「Task:」之前（…记忆→任务板→任务文本顺序）"):
    val p = ProjectActor.newTaskPrompt(pd, "任务文本X", "", "", boardBlock)
    val boardIdx = p.indexOf("<task-board>")
    val taskIdx = p.indexOf("Task: 任务文本X")
    assert(boardIdx >= 0 && taskIdx >= 0, p)
    assert(boardIdx < taskIdx, s"board block must precede the task text:\n$p")
    assert(p.contains("#1[open @n-inj] 夹具工单"), p)

  test("newTaskPrompt: 目录/记忆/任务板三段全量时的相对顺序（目录→记忆→任务板→任务）"):
    val p = ProjectActor.newTaskPrompt(pd, "T", "CATALOG-段", "MEMORY-段", boardBlock)
    val idx = List(p.indexOf("CATALOG-段"), p.indexOf("MEMORY-段"), p.indexOf("<task-board>"), p.indexOf("Task: T"))
    assert(idx.forall(_ >= 0) && idx == idx.sorted, s"order broken:\n$p")

  test("answerReentryPrompt: 空板块零注入；非空时板块在四动作块之前"):
    val plain = ProjectActor.answerReentryPrompt(pd, node, "req-1", "答复文本X", "", "", "")
    assert(!plain.contains("<task-board>"), plain)
    assert(plain.contains("Read the current state first (NodeList"), plain) // 四动作块仍在
    val withBoard = ProjectActor.answerReentryPrompt(pd, node, "req-1", "答复文本X", "", "", boardBlock)
    val boardIdx = withBoard.indexOf("<task-board>")
    val actionsIdx = withBoard.indexOf("Read the current state first (NodeList")
    assert(boardIdx >= 0 && boardIdx < actionsIdx, s"board must precede the four-action block:\n$withBoard")

  // ===== 节点侧脚注（§3c，措辞原文固化）=====

  test("ProtocolFootnote 末行上报指引：close=完成，blocked=受阻（任务板四状态域措辞，与节点协议同点注入）"):
    val fn = NodeEngine.ProtocolFootnote
    val last = fn.linesIterator.toList.last
    assertEquals(last, "TaskBoard work order ⇒ close = done, blocked = stuck.")
    // 节点生命周期 blocked 已退役：脚注零「受阻申报」出口（backtick 值域形态零出现；
    // 末行的裸 blocked 属任务板四状态域语义，不是节点生命周期）
    assert(!fn.contains("`blocked`"), s"blocked 值域形态必须零出现:\n$fn")
    assert(!fn.contains("needs-split"), s"blocked 细分域已随终态退役删除:\n$fn")
    assert(!fn.contains("BLOCKED"), s"文本锚定备用通道已随出口一并删除:\n$fn")

  test("ProtocolFootnote 措辞（blocked 终态退役后协议）：工具申报 + 角色值域 + AskHost 指引 + 无停等申报"):
    val fn = NodeEngine.ProtocolFootnote
    assert(fn.contains("node_report"), s"第一优先=工具申报:\n$fn")
    assert(fn.contains("task = `finish`"), s"task 角色值域在位（显式完成，可选）:\n$fn")
    assert(fn.contains("verifier = `pass` / `fail`"), s"verifier verdict 值域在位:\n$fn")
    assert(fn.contains("There is no stopped/waiting declaration"), s"无停等申报出口（blocked 退役语义）:\n$fn")
    assert(fn.contains("call AskHost"), s"任务/授权/依赖不清 ⇒ AskHost（与工具 description 双面同文）:\n$fn")
    assert(fn.contains("state what is missing plainly in your wrap-up report"), s"兜底 = 收尾报告写明缺什么:\n$fn")

  // node-output contract（2026-09-24）：末条输出 = 自身完成汇报、结果自包含（禁指代收尾）
  // 两条要求是**新钉**（非既有断言措辞同步——本批未触发 §五 的同步条件：末行全等钉与
  // deliverable 契约两段逐字保持，无需改写任何既有断言字面量）。
  test("ProtocolFootnote 输出契约（node-output contract）：末条输出 = 自身完成汇报（申报 ≠ 汇报）、结果自包含（禁 see above 指代）"):
    val fn = NodeEngine.ProtocolFootnote
    assert(fn.contains("Your last output is the deliverable"), s"末条输出 = 交付物（引擎取最后一条 assistant 文本）:\n$fn")
    assert(fn.contains("completion report for this node"), s"内容 = 自身完成汇报:\n$fn")
    assert(fn.contains("terminal declaration, not that report"), s"申报（node_report）≠ 汇报，两者是两件事:\n$fn")
    assert(fn.contains("Keep it self-contained"), s"要求 2 的抬头句:\n$fn")
    assert(fn.contains("\"see above\""), s"指代收尾必须点名禁用:\n$fn")
    assert(
      fn.contains("restate the conclusion, the artifact paths and the numbers in full"),
      s"自包含的可操作判据（写全结论/路径/数字）:\n$fn"
    )
    // 末行锚不受新行影响（新行只能插在它之前）
    assertEquals(fn.linesIterator.toList.last, "TaskBoard work order ⇒ close = done, blocked = stuck.")

  // ===== 身份透传链（§1d-2）=====

  test("AgentState 三字段往返：flowNodeId/isDispatcher/projectName 落 SessionContext 可读"):
    val nodeState = AgentState(flowNodeId = Some("n-9"), isDispatcher = false, projectName = Some("projT"))
    assertEquals(nodeState.session.flowNodeId, Some("n-9"))
    assertEquals(nodeState.session.isDispatcher, false)
    assertEquals(nodeState.session.projectName, Some("projT"))
    val dispatcherState = AgentState(isDispatcher = true, projectName = Some("projT"))
    assertEquals(dispatcherState.session.isDispatcher, true)
    assertEquals(dispatcherState.session.flowNodeId, None)
    // 缺省 = 非项目会话（双保险的 None 侧）
    val bare = AgentState()
    assertEquals(bare.session.flowNodeId, None)
    assertEquals(bare.session.isDispatcher, false)
    assertEquals(bare.session.projectName, None)

end TaskBoardInjectionSpec
