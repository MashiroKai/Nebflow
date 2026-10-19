package nebflow.core.entity

import munit.FunSuite

/**
 * 提示词来源拆分判据门（2026-10-08 作者令）：`nebflow` 执行名的系统提示词按会话面
 * 拆成**两个独立来源**，本 spec 钉住拆分本身——
 *
 *  ① **flow 节点面**（解析路径 = `entry`）：节点语义四要素——接收上游/分发输入
 *     （任务文本 = 全部世界）、产出交付（末条输出 = 交付物，引擎沿 out 边投递）、
 *     沿 out 边交接、受监督约束（node_report 申报义务 + 未申报不终态 + role=verifier
 *     值域分化 pass/fail）；
 *  ② **delegate 面**（解析路径 = `delegateEntry`）：delegate_report 纪律（含配套
 *     指令），**模型可见面零 node_report 指令**；
 *  ③ **两来源机械分离**：两路径产出互不等（两个独立常量、两条读路，非一处模板
 *     双面引用）；非 nebflow 名无 delegate 面变体（逐字回落 entry）；
 *  ④ **brief 申报行**（`DelegateTool.BriefReportLine`）= delegate_report 纪律、
 *     零 node_report（delegate brief 面与 delegate 系统提示词面同判据）。
 *
 * 面性质：本文件只读代码定义与公开常量，不写盘、不碰 `PathUtil.dataRoot`。
 * 引擎贡献面（`NodeEngine.ProtocolFootnote`、`PromptSections.NodeSessionAlwaysOnSection`）
 * 不在本 spec 判定内（节点输入面协议归 `NodeChainAttributionSpec` /
 * `TaskBoardInjectionSpec` 的既有身份钉）。
 */
class PromptFaceSplitSpec extends FunSuite:

  private val executorName: String = nebflow.core.entity.BuiltinAgents.ExecutorName

  /** 节点面读路（NodeStarter → EntityLoader.loadAgent → entry 的同一单点）。 */
  private def nodePrompt: String =
    nebflow.core.entity.BuiltinAgents
      .entry(executorName)
      .map(_.systemPrompt)
      .getOrElse(fail(s"BuiltinAgents must carry a code-defined '$executorName' entry"))

  /** delegate 面读路（DelegateTool.spawnKernel / MailTool 续聊腿的同一单点）。 */
  private def delegatePrompt: String =
    nebflow.core.entity.BuiltinAgents
      .delegateEntry(executorName)
      .map(_.systemPrompt)
      .getOrElse(fail(s"BuiltinAgents must resolve '$executorName' via delegateEntry"))

  test("delegate 面系统提示词 = delegate_report 纪律在位（两态值域 + 配套指令；#566 起 blocked 类别退役）"):
    val text = delegatePrompt
    assert(text.contains("delegate_report"), "delegate_report 申报义务在位")
    assert(text.contains("Report your state (delegate_report)"), "申报段头在位")
    for v <- Seq("finish", "merge") do assert(text.contains(s"`$v`"), s"delegate_report 值域态在位: $v")
    assert(!text.contains("- `blocked` —"), "blocked 类别整行退役（delegate 面无 blocked 态——作者令）")
    assert(text.contains("NO separate blocked category"), "「报不了 = finish＋阻塞写进内容」的语义在位")
    assert(text.contains("MergeQueue"), "merge 态的 MergeQueue 语义在位")
    assert(text.contains("Your roles: executor, dispatcher, manager"), "三角色段在位（delegate 语义）")

  test("delegate 面系统提示词模型可见面零 node_report 指令（零出现，防回流）"):
    val hits = delegatePrompt.split("\n", -1).toList.filter(_.contains("node_report"))
    assertEquals(
      hits,
      Nil,
      s"delegate 面模型可见文本零 node_report（命中 ${hits.length} 行，首行: ${hits.headOption.getOrElse("").take(120)}）"
    )
    assert(!delegatePrompt.contains("You are a task node"), "节点身份句不得出现在 delegate 面（节点面专属开头）")

  test("flow 节点面系统提示词 = 节点四要素（任务文本全部世界 / 末条输出交付物 / out 边交接 / 监督约束）"):
    val text = nodePrompt
    assert(text.startsWith("You are a task node"), "节点身份句开头（末条输出 = 交付物的承载句）")
    assert(text.contains("your final assistant text IS the deliverable"), "交付物身份句在位")
    assert(text.contains("task text is your entire world"), "任务文本 = 全部世界在位")
    assert(text.contains("delivers it downstream along the out edges"), "引擎沿 out 边投递在位")
    assert(text.contains("Hand off along the out edges"), "out 边交接段在位")

  test("flow 节点面系统提示词 = node_report 申报义务 + 未申报不终态 + verifier 值域分化"):
    val text = nodePrompt
    assert(text.contains("Call `node_report` before wrapping up"), "申报义务句在位")
    assert(text.contains("never terminalizes"), "未申报不终态语义在位")
    assert(text.contains("`task` (default): `finish` / `blocked`"), "task 角色值域在位")
    assert(text.contains("`pass` / `fail` (a verdict on the object under review)"), "verifier 角色值域在位")
    assert(text.contains("(fail)<target>:loop"), "verifier 回边路由语义在位")
    assert(text.contains("Report first, then write your wrap-up text"), "报告先行句在位")

  test("两来源机械分离：entry 路径 ≠ delegateEntry 路径，各持各面（非一处模板双面引用）"):
    assert(nodePrompt != delegatePrompt, "两路径产出必须互不等（两个独立常量）")
    assert(nodePrompt.contains("node_report"), "节点面持 node_report 纪律")
    assert(!delegatePrompt.contains("node_report"), "delegate 面零 node_report")
    assert(delegatePrompt.contains("delegate_report"), "delegate 面持 delegate_report")

  test("非 nebflow 名无 delegate 面变体：delegateEntry 逐字回落 entry（机械完备性）"):
    for name <- nebflow.core.entity.BuiltinAgents.Names if name != executorName do
      val viaEntry = nebflow.core.entity.BuiltinAgents.entry(name).map(_.systemPrompt)
      val viaDelegate = nebflow.core.entity.BuiltinAgents.delegateEntry(name).map(_.systemPrompt)
      assertEquals(viaDelegate, viaEntry, s"'$name' 无 delegate 面变体（回落 entry）")

  test("brief 申报行（DelegateTool.BriefReportLine）= delegate_report 纪律、零 node_report（#566 起两态）"):
    val line = nebflow.core.tools.DelegateTool.BriefReportLine
    assert(line.contains("delegate_report(category=\"finish\"|\"merge\", detail=…)"), "brief 申报行的两态调用形态在位")
    assert(!line.contains("|\"blocked\""), "brief 申报行零 blocked 类别（delegate 面无 blocked 态——作者令）")
    assert(!line.contains("node_report"), "brief 申报行零 node_report（delegate brief 面同判据）")
    assert(line.contains("your final text remains the deliverable"), "末条输出 = 交付物语义在位")

  // prompt-panel 面板批（2026-10-09）：面板展示面的双面投影钉——面板列表/详情
  // （GET /api/agents/:name 的 `promptFaces`）按 panelPromptFaces 下发两面，
  // 「Delegate 目标标注配 node 面正文」的面板错配不再可能出现。
  test("面板双面投影（panelPromptFaces）：仅 executor 名两面、delegate 在前 node 在后、正文各归各路"):
    val faces = nebflow.core.entity.BuiltinAgents.panelPromptFaces(executorName)
    assertEquals(faces.map(_._1), List("delegate", "node"), s"恰两面且次序固定（实际: ${faces.map(_._1)}）")
    val delegateFace = faces.collectFirst { case ("delegate", p) => p }.getOrElse(fail("delegate 面缺席"))
    val nodeFace = faces.collectFirst { case ("node", p) => p }.getOrElse(fail("node 面缺席"))
    assertEquals(nodeFace, nodePrompt, "node 面正文 = entry 路径产出（NebflowNodePrompt）")
    assertEquals(delegateFace, delegatePrompt, "delegate 面正文 = delegateEntry 路径产出（NebflowDelegatePrompt）")

  test("面板双面投影：非 executor 名恒 Nil（单面 agent 面板维持单 prompt 展示）"):
    for name <- nebflow.core.entity.BuiltinAgents.Names if name != executorName do
      assertEquals(nebflow.core.entity.BuiltinAgents.panelPromptFaces(name), Nil, s"'$name' 无双面投影")

end PromptFaceSplitSpec
