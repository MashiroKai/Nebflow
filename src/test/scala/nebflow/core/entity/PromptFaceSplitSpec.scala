package nebflow.core.entity

import munit.FunSuite

/**
 * 提示词来源拆分判据门（2026-10-08 作者令）：`nebflow` 执行名的系统提示词按会话面
 * 拆成**两个独立来源**，本 spec 钉住拆分本身——
 *
 *  ① **flow 节点面**（解析路径 = `entry`）：节点语义四要素——接收上游/分发输入
 *     （任务文本 = 全部世界）、产出交付（末条输出 = 交付物，引擎沿 out 边投递）、
 *     沿 out 边交接、受监督约束（终态文本锚定协议 + role=verifier 判词文法）；
 *  ② **delegate 面**（解析路径 = `delegateEntry`）：delegate 终态语义（回合结束 =
 *     完成事件、交付 = 末条正文本体）＋ 零节点终态申报纪律（防回流，见对应用例）；
 *  ③ **两来源机械分离**：两路径产出互不等（两个独立常量、两条读路，非一处模板
 *     双面引用）；非 nebflow 名无 delegate 面变体（逐字回落 entry）；
 *  ④ **brief 终态语义行**（`DelegateTool.BriefReportLine`）= delegate 终态语义
 *     （delegate brief 面与 delegate 系统提示词面同判据）。
 *
 * 面性质：本文件只读代码定义与公开常量，不写盘、不碰 `PathUtil.dataRoot`。
 * 引擎贡献面（`NodeEngine.ProtocolFootnote`，节点输入面终态协议单点）
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

  test("node and delegate prompts do not prohibit direct commits to main globally"):
    for (face, text) <- Seq("node" -> nodePrompt, "delegate" -> delegatePrompt) do
      assert(!text.contains("never commit straight to `main`"), s"$face imposes a global main prohibition")

  test("node and delegate prompts follow the target repository branch and commit rules"):
    for (face, text) <- Seq("node" -> nodePrompt, "delegate" -> delegatePrompt) do
      assert(
        text.contains("follow the target repository's branch and commit rules") ||
          text.contains("Follow the target repository's branch and commit rules"),
        s"$face is missing the target repository branch and commit rules"
      )

  test("restart exception is narrow on role and tool prompt surfaces"):
    val surfaces = Seq(
      nodePrompt,
      delegatePrompt,
      nebflow.prompts.PromptCatalog.Roles.DispatcherPromptTemplate,
      nebflow.core.tools.DelegateTool.description
    )
    for text <- surfaces do
      for required <- Seq(
          "explicit user authorization",
          "higher-priority runtime/tool",
          "--canary",
          "--hot",
          "--force",
          "--no-compile",
          "idle",
          "not a guarantee",
          "manually"
        )
      do assert(text.contains(required), s"missing restart safety requirement: $required")
      assert(!text.contains("NEVER send signals to or kill any sbt/java/nebflow process"))
      for required <- Seq(
          "kill -TERM <pid>",
          "kill -KILL <pid>",
          "PID is gone",
          "port has no listener",
          "verification output",
          "pkill -f java",
          "killall java",
          "pkill sbt"
        )
      do assert(text.contains(required), s"missing isolated-process safety requirement: $required")
      assert(!text.contains("Never directly signal or kill sbt/java"))

    end for

  test("implementation delivery requires a decision-to-acceptance transition on both delegate surfaces"):
    val brief = nebflow.core.tools.DelegateTool.BriefReportLine
    for text <- Seq(delegatePrompt, brief) do
      assert(text.contains("reconnaissance and a decision are intermediate steps"))
      assert(text.contains("each acceptance criterion"))
      assert(text.contains("authorized"))
    assert(delegatePrompt.contains("Never invent results or bypass safety gates"))
    assert(brief.contains("Do not merge, push, publish, tag or delete branches/worktrees on your own initiative"))

  test("delegate 面系统提示词 = 终态语义在位（回合结束 = 完成事件；无申报工具；blocked 类别退役）"):
    val text = delegatePrompt
    assert(text.contains("no declaration tool"), "「无申报工具」明示句在位")
    assert(text.contains("Finish and waiting (plain text, no declaration tool)"), "终态语义段头在位")
    assert(text.contains("- Done (or blocked for good):"), "完成腿（含 blocked 折终稿）在位")
    assert(text.contains("- Waiting (not done):"), "等待腿在位")
    assert(!text.contains("- `blocked` —"), "blocked 类别整行退役（delegate 面无 blocked 态——作者令）")
    assert(!text.contains("`merge` —"), "merge 申报类别退役（集成 = 项目 merger 的授权任务）")
    assert(
      text.contains("If the task could not be completed, state the blocker plainly in the same text."),
      "「报不了 = 阻塞写进终稿正文」的语义在位"
    )
    assert(text.contains("Integration discipline (hard)"), "永不自并纪律在位")
    assert(text.contains("Your roles: executor, dispatcher, manager"), "三角色段在位（delegate 语义）")

  test("delegate 面系统提示词模型可见面零节点申报纪律（零出现，防回流）"):
    val hits = delegatePrompt.split("\n", -1).toList.filter(_.contains("node_report"))
    assertEquals(
      hits,
      Nil,
      s"delegate 面模型可见文本零节点申报纪律（命中 ${hits.length} 行，首行: ${hits.headOption.getOrElse("").take(120)}）"
    )
    assert(!delegatePrompt.contains("You are a task node"), "节点身份句不得出现在 delegate 面（节点面专属开头）")

  test("flow 节点面系统提示词 = 节点四要素（任务文本全部世界 / 末条输出交付物 / out 边交接 / 监督约束）"):
    val text = nodePrompt
    assert(text.startsWith("You are a task node"), "节点身份句开头（末条输出 = 交付物的承载句）")
    assert(text.contains("your final assistant text IS the deliverable"), "交付物身份句在位")
    assert(text.contains("task text is your entire world"), "任务文本 = 全部世界在位")
    assert(text.contains("delivers it downstream along the out edges"), "引擎沿 out 边投递在位")
    assert(text.contains("Hand off along the out edges"), "out 边交接段在位")

  test(
    "flow node face system prompt = terminal text-anchoring protocol (verifier verdict grammar + AskHost guidance, no declaration tool)"
  ):
    val text = nodePrompt
    // Terminal report = the final text itself (the declaration tool channel is retired).
    assert(text.contains("no separate declaration tool"), "explicit no-declaration-tool sentence present")
    assert(text.contains("`VERDICT: PASS` or `VERDICT: FAIL`"), "verifier verdict grammar present")
    assert(text.contains("ask (AskHost) instead of declaring a stuck state"), "AskHost guidance present")
    assert(text.contains("(fail)<target>:loop"), "verifier 回边路由语义在位")
    assert(text.contains("verdict ≠ status"), "verifier verdict is not node status")
    assert(
      text.contains("real execution failure (dead session / LLM error) is engine-judged"),
      "real execution failure is engine-judged"
    )
    // The blocked terminal is retired: the node face carries neither the BLOCKED grammar nor a blocked value.
    assert(!text.contains("BLOCKED") && !text.contains("`blocked`"), "node face has no blocked grammar")

  test("两来源机械分离：entry 路径 ≠ delegateEntry 路径，各持各面（非一处模板双面引用）"):
    assert(nodePrompt != delegatePrompt, "两路径产出必须互不等（两个独立常量）")
    assert(nodePrompt.contains("no separate declaration tool"), "节点面持终态文本锚定协议（申报 = 末条文本）")
    assert(delegatePrompt.contains("no declaration tool"), "delegate 面持终态语义（无申报工具）")

  test("非 nebflow 名无 delegate 面变体：delegateEntry 逐字回落 entry（机械完备性）"):
    for name <- nebflow.core.entity.BuiltinAgents.Names if name != executorName do
      val viaEntry = nebflow.core.entity.BuiltinAgents.entry(name).map(_.systemPrompt)
      val viaDelegate = nebflow.core.entity.BuiltinAgents.delegateEntry(name).map(_.systemPrompt)
      assertEquals(viaDelegate, viaEntry, s"'$name' 无 delegate 面变体（回落 entry）")

  test("brief 终态语义行（DelegateTool.BriefReportLine）= 正文交付钉、零申报工具指令（blocked 折终稿；merge 类别退役）"):
    val line = nebflow.core.tools.DelegateTool.BriefReportLine
    assert(line.contains("Your final assistant text IS the deliverable"), "brief 交付钉在位（末条正文 = 交付物）")
    assert(!line.contains("category="), "brief 语义行零申报工具调用形态（通道退役）")
    assert(!line.contains("\"merge\""), "merge 申报类别退役（集成 = 项目 merger 的授权任务）")
    assert(line.contains("say the blocker plainly in the same final text"), "blocked 折终稿语义在位（无独立 blocked 通道）")
    assert(!line.contains("node_report"), "brief 语义行零 node_report（delegate brief 面同判据）")
    assert(
      line.contains("Do not merge, push, publish, tag or delete branches/worktrees on your own initiative"),
      "永不自并纪律在位"
    )

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
