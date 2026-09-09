package nebflow.core.project

import munit.FunSuite
import nebflow.agent.AgentState

/**
 * TaskBoard 注入拼装 + 身份透传 spec（TaskBoard 批 2：规格 §3a/§3c + §1d-2）。
 *
 * 覆盖：
 *  - newTaskPrompt/reentryPrompt 的 <task-board> 注入块拼装（§3a）：空串零注入
 *    （旧行为逐字节不变——DispatcherSpawnPromptSpec 回归基线不动）；非空时整块
 *    原样进入且【在任务文本之前】（拼装相对顺序 …→目录→记忆→任务板→任务文本）；
 *  - ProtocolFootnote 上报指引行（§3c）：末行措辞固化（spawn 注入链与 blocked
 *    协议同点同生命周期）；
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
  private val feedback = BlockedFeedback(category = "task-underspecified", detail = "缺参数", suggestion = "补任务文本")

  private val boardBlock =
    s"""<task-board>
       |全板速览：
       |  #1[open @n-inj] 夹具工单
       |</task-board>""".stripMargin

  // ===== 分发器注入（§3a）=====

  test("newTaskPrompt: 空板块零注入（旧行为不变）"):
    val p = ProjectActor.newTaskPrompt(pd, "任务文本X", "", "", "")
    assert(!p.contains("<task-board>"), p)
    assert(p.contains("任务：任务文本X"), p)

  test("newTaskPrompt: 板块在「任务：」之前（…记忆→任务板→任务文本顺序）"):
    val p = ProjectActor.newTaskPrompt(pd, "任务文本X", "", "", boardBlock)
    val boardIdx = p.indexOf("<task-board>")
    val taskIdx = p.indexOf("任务：任务文本X")
    assert(boardIdx >= 0 && taskIdx >= 0, p)
    assert(boardIdx < taskIdx, s"board block must precede the task text:\n$p")
    assert(p.contains("#1[open @n-inj] 夹具工单"), p)

  test("newTaskPrompt: 目录/记忆/任务板三段全量时的相对顺序（目录→记忆→任务板→任务）"):
    val p = ProjectActor.newTaskPrompt(pd, "T", "CATALOG-段", "MEMORY-段", boardBlock)
    val idx = List(p.indexOf("CATALOG-段"), p.indexOf("MEMORY-段"), p.indexOf("<task-board>"), p.indexOf("任务：T"))
    assert(idx.forall(_ >= 0) && idx == idx.sorted, s"order broken:\n$p")

  test("reentryPrompt: 空板块零注入；非空时板块在四动作块之前"):
    val plain = ProjectActor.reentryPrompt(pd, node, feedback, 1, "", "", "")
    assert(!plain.contains("<task-board>"), plain)
    assert(plain.contains("先 NodeList 读现状"), plain) // 四动作块仍在
    val withBoard = ProjectActor.reentryPrompt(pd, node, feedback, 1, "", "", boardBlock)
    val boardIdx = withBoard.indexOf("<task-board>")
    val actionsIdx = withBoard.indexOf("先 NodeList 读现状")
    assert(boardIdx >= 0 && boardIdx < actionsIdx, s"board must precede the four-action block:\n$withBoard")

  // ===== 节点侧脚注（§3c，措辞原文固化）=====

  test("ProtocolFootnote 末行上报指引：close=完成，blocked=受阻（与 blocked 协议同点注入）"):
    val last = NodeEngine.ProtocolFootnote.linesIterator.toList.last
    assertEquals(
      last,
      "若上方 <task-board> 给了你工单编号，完成或受阻时用 TaskBoard 工具更新其状态（close=完成，blocked=受阻）。")
    // blocked 协议本体不动（既有断言锚）
    assert(NodeEngine.ProtocolFootnote.contains("needs-split"))

  test("ProtocolFootnote 措辞（blocked 结构化信号批 20260909，spec §5.2 #7）：工具优先 + 文本备用通道裸形态强调"):
    val fn = NodeEngine.ProtocolFootnote
    assert(fn.contains("report_blocked"), s"第一优先=工具申报:\n$fn")
    assert(fn.contains("随后照常输出"), s"申报后照常收尾（申报≠终止输出）:\n$fn")
    assert(fn.contains("工具不可用时才用文本备用通道"), s"文本通道降级定位:\n$fn")
    assert(fn.contains("不加 # / ** / 导语等任何前缀"), s"裸形态强调（6 例 markdown 形态侵蚀实证）:\n$fn")

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
