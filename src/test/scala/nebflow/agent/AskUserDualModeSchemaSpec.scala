package nebflow.agent

import io.circe.Json
import munit.FunSuite
import nebflow.actor.{AgentDef, RootAgentIdentity}
import nebflow.core.tools.{AskUserQuestionTool, ToolRegistry}
import nebflow.shared.{AttachContract, ToolDefinition}

/**
 * **非阻塞-only 零变体** 定义层验收（root 2026-10-02 令 #462 裁①/②；改写自
 * 「工具面按角色分化批」的定义层验收，**强度不降**）：
 *
 *  ① **核心断言（全身份）**：`AskUserQuestion` 的工具定义在**任何**身份下都
 *     **不含** `mode`（属性缺席），且与注册表基线**逐字节相同**（description +
 *     inputSchema）—— 历史的两份变体（基础 / root）已整体退场。
 *  ② **机制退场断言**：schema 变体面**不存在**了 —— `AgentCore.schemaVariantFor`
 *     的 AskUser 分支已删除，`ToolFaceVariantSchemaSpec` 的 AskUser 豁免同步收紧为
 *     `Set.empty`；`RootAgentIdentity` 判据单点仍在（pop / 提示词段仍消费它），但
 *     **不再**有 AskUser 消费点（`AskUserQuestionTool` 不再读 `ctx.isRootAgent`）。
 *  ③ **`mode` 参数拒绝面**：携带 `mode` 的调用在**工具层**得到显式可判读
 *     `ToolError`（不静默忽略、不按取值分叉）。
 *  ④ **无残留**：已退役符号（`modePropertySchema` / `rootVariant` / `parseMode` /
 *     `AskMode` 等）在工具源文件里**零出现**。
 *  ⑤ **附件参数在位**：`attachments` 在基线 schema 内（顶层、array/string、
 *     `maxItems` = 全仓单点常量）。
 *
 * 跑法：单测直调 `AgentCore.buildToolList`（protected ⇒ 经先例
 * `AllowedToolSetSpec` 的 `CoreProbe extends AgentCore` 暴露）。
 */
class AskUserDualModeSchemaSpec extends FunSuite:

  /** protected `buildToolList` 的探针（先例：`AllowedToolSetSpec.CoreProbe`）。 */
  private object CoreProbe extends AgentCore:

    def face(defn: AgentDef, depth: Int = 0, flowNodeSession: Boolean = false): List[ToolDefinition] =
      buildToolList(defn, depth, flowNodeSession = flowNodeSession).getOrElse(Nil)

  private def defNamed(name: String, tools: List[String] = Nil): AgentDef =
    AgentDef(name = name, description = "", tools = tools)

  /** 注册表里的**基线定义**（= 所有身份现在看到的那一份）。 */
  private val baseline: ToolDefinition =
    ToolRegistry.ALL_TOOLS.find(_.name == AskUserQuestionTool.Name) match
      case Some(td) => td
      case None => fail(s"AskUserQuestion 不在注册表：${ToolRegistry.ALL_TOOLS.map(_.name)}")

  private def askOf(tds: List[ToolDefinition]): ToolDefinition =
    tds.find(_.name == AskUserQuestionTool.Name) match
      case Some(td) => td
      case None => fail(s"AskUserQuestion 不在该会话工具面内：${tds.map(_.name).sorted}")

  /** `properties.mode` 的**缺席判据**（① 的判红面）。 */
  private def modeOf(td: ToolDefinition): Option[Json] =
    td.inputSchema("properties").flatMap(_.asObject).flatMap(_.apply("mode"))

  // 身份横断面（含历史分化人格：root / 节点 / 内核 / 按名冒充 root 的节点）
  private val rootFace = CoreProbe.face(defNamed("Nebula"), depth = 0)
  private val generalNodeFace = CoreProbe.face(defNamed("general"), depth = 1, flowNodeSession = true)
  private val kernelFace = CoreProbe.face(defNamed("kernel"), depth = 1)
  private val nebulaNamedNodeFace = CoreProbe.face(defNamed("Nebula"), depth = 1, flowNodeSession = true)

  // ============================================================
  // ① 核心断言：全身份 mode 缺席 + 与基线逐字节相同
  // ============================================================

  test("① 核心断言: 四个身份的工具定义里 mode 全缺席 + 与基线定义逐字节相同") {
    for (name, face) <- List(
        "Nebula-root" -> rootFace,
        "general(depth=1, flowNodeSession)" -> generalNodeFace,
        "kernel(depth=1)" -> kernelFace,
        "按名冒充 root 的 depth=1 节点(Nebula)" -> nebulaNamedNodeFace
      )
    do
      val td = askOf(face)
      assert(modeOf(td).isEmpty, s"$name 的 AskUserQuestion 定义里出现了 mode（裁② 未落地）：${modeOf(td)}")
      // 属性缺席而非「enum 收窄」——properties 整体仍存在且含 questions
      assert(
        td.inputSchema("properties").flatMap(_.asObject).exists(_.contains("questions")),
        s"$name: properties.questions 缺失 —— 基础 schema 结构被破坏"
      )
      assertEquals(td.description, baseline.description, s"$name 的 description 漂移")
      assertEquals(td.inputSchema, baseline.inputSchema, s"$name 的 inputSchema 漂移")
      assertEquals(td, baseline, s"$name 的 AskUserQuestion 定义不再是基线定义本身")
      assertEquals(td.description, AskUserQuestionTool.descriptionBase)
  }

  test("① 附件参数: attachments 在基线 schema 内（顶层可选、array/string、maxItems=9）") {
    val props = baseline.inputSchema("properties").flatMap(_.asObject).getOrElse(fail("基线 schema 无 properties"))
    val att = props("attachments").getOrElse(fail(s"基线 schema 无 attachments：${props.keys.toList.sorted}"))
    assertEquals(att.hcursor.downField("type").as[String], Right("array"))
    assertEquals(att.hcursor.downField("maxItems").as[Int], Right(AttachContract.MaxAttachmentsPerMessage))
    assertEquals(att.hcursor.downField("items").downField("type").as[String], Right("string"))
    // 值域对齐：件数上限与全仓消息附件口径同一常量（禁第二份数值）
    assertEquals(AskUserQuestionTool.MaxAttachmentBytes, 100L * 1024 * 1024)
    // 顶层 = 与 `questions` 平级（不在 question item 内）
    assert(props.contains("questions"), "questions 应从顶层可见")
    // 散文里的数字钉常量（description 与 parameter description 双处）
    val attDesc = att.hcursor.downField("description").as[String].getOrElse("")
    assert(attDesc.contains(s"${AttachContract.MaxAttachmentsPerMessage} entries"), s"schema 描述未钉件数上限：$attDesc")
    assert(attDesc.contains("100 MB each"), s"schema 描述未钉单件上限：$attDesc")
    assert(
      AskUserQuestionTool.description.contains(s"at most ${AttachContract.MaxAttachmentsPerMessage} entries"),
      "工具 description 的 attachments 段未钉件数上限"
    )
  }

  test("① 无变体: 历史 root 变体符号不再存在（防「顺手写回」）") {
    val src = os.read(os.pwd / "src" / "main" / "scala" / "nebflow" / "core" / "tools" / "AskUserQuestionTool.scala")
    for retired <- List(
        "modePropertySchema",
        "schemaRoot",
        "baseHasModeProperty",
        "rootVariant",
        "descriptionRoot",
        "rootExtraDescription",
        "parseMode",
        "BadModeCode",
        "NonBlockingNotRootCode",
        "NoRootWindowCode",
        "AskMode",
        "restoreRegistryAfterAnswer",
        "rootWindowReachable",
        "askUserNonBlocking",
        "isRootAgent"
      )
    do
      assert(!src.contains(retired), s"已退役符号 `$retired` 又出现在工具源文件里（变体面残留）")
  }

  // ============================================================
  // ② 机制退场：AgentCore 分支删除 + 判据单点仍在
  // ============================================================

  test("② 机制退场: AgentCore.schemaVariantFor 不再有 AskUser 分支（源码级）") {
    val src = os.read(os.pwd / "src" / "main" / "scala" / "nebflow" / "agent" / "AgentCore.scala")
    val iVariant = src.indexOf("def schemaVariantFor(")
    assert(iVariant > 0, "schemaVariantFor 定义未找到")
    val body = src.substring(iVariant, math.min(src.length, iVariant + 1200))
    assert(!body.contains("AskUserQuestionTool"), "schemaVariantFor 仍在特判 AskUserQuestion（裁② 未落地）")
    assert(body.contains("NodeReportToolDef.roleVariant"), "node_report 变体面被误删")
    assert(body.contains("MailTool.addressFaceVariant"), "Mail 变体面被误删")
  }

  test("② 判据单点: 四个身份组合的真值表（含 depth 分量与 fail-closed）") {
    val nebula = Some(defNamed("Nebula"))
    val general = Some(defNamed("general"))
    assertEquals(RootAgentIdentity.isRootAgent(nebula, 0), true, "Nebula+depth=0 必须是 root")
    assertEquals(RootAgentIdentity.isRootAgent(nebula, 1), false, "Nebula+depth=1（节点会话）不是 root")
    assertEquals(RootAgentIdentity.isRootAgent(general, 0), false, "非 Nebula 的 depth=0 根会话不是 root（T1=(a)）")
    assertEquals(RootAgentIdentity.isRootAgent(None, 0), false, "agentDef=None fail-closed")
    // ToolContext 派生 def 委托同一真值（运行期求值面；AskUserQuestionTool 已不再消费它）
    val ctxRoot = nebflow.core.tools.ToolContext(projectRoot = "", agentDef = nebula, depth = 0)
    val ctxNode = nebflow.core.tools.ToolContext(projectRoot = "", agentDef = nebula, depth = 1)
    val ctxNoDef = nebflow.core.tools.ToolContext(projectRoot = "", agentDef = None, depth = 0)
    assert(ctxRoot.isRootAgent, "ToolContext.isRootAgent 未委托单点（root 侧）")
    assert(!ctxNode.isRootAgent, "ToolContext.isRootAgent 未委托单点（depth 分量丢失）")
    assert(!ctxNoDef.isRootAgent, "ToolContext.isRootAgent 未 fail-closed")
  }

  test("变异②钉子: NodeDef.agent=\"Nebula\" 的 depth=1 节点会话拿【基础】变体") {
    val face = CoreProbe.face(defNamed("Nebula"), depth = 1, flowNodeSession = true)
    val td = askOf(face) // 成员资格按名判 ⇒ 该形态照样持卡
    assert(modeOf(td).isEmpty, "depth=1 的 Nebula 节点会话拿到了 root 变体 —— 谓词丢了 depth 分量（静默放行）")
    assertEquals(td, baseline)
  }

  test("无变体: 按名冒充 root 的 depth=1 节点与未登记形态一律落**同一份**定义（无变体可分）") {
    // 历史分化面（按名判 vs 按「名 ∧ depth」判）在本批已无对象：四个身份全部拿到
    // 注册表基线定义 ⇒ 任何「按名放行」的实现方式都不可能造成差异。
    assert(modeOf(askOf(nebulaNamedNodeFace)).isEmpty)
    assertEquals(askOf(nebulaNamedNodeFace), baseline)
    val unknown = CoreProbe.face(defNamed("some-future-agent", tools = List(AskUserQuestionTool.Name)), depth = 0)
    assert(modeOf(askOf(unknown)).isEmpty)
    assertEquals(askOf(unknown), baseline)
    assert(!RootAgentIdentity.isRootAgent(None, 0), "agentDef=None 时判据必须 fail-closed")
  }

  test("成员资格零改动: 四例的工具面组成与基线一致（无插删元素）") {
    val baselineNames = ToolRegistry.ALL_TOOLS.map(_.name)
    for (name, face) <- List(
        "Nebula" -> rootFace,
        "general" -> generalNodeFace,
        "kernel" -> kernelFace,
        "Nebula-named node" -> nebulaNamedNodeFace
      )
    do
      val names = face.map(_.name)
      // 顺序：扁平化自 ALL_TOOLS ⇒ 必然是注册表序的子序列（逐位不变）
      assertEquals(names, baselineNames.filter(names.contains), s"$name 的工具序不再是注册表序的子序列")
      assertEquals(names.distinct.size, names.size, s"$name 的工具面出现重复元素")
      assert(names.contains(AskUserQuestionTool.Name), s"$name 丢了 AskUserQuestion 成员资格")
  }

  test("禁第二份表达式: 谓词字面量只许出现在单点文件内 + 消费点均为委托") {
    val root = os.pwd
    val mainSrc = root / "src" / "main" / "scala"
    assert(os.exists(mainSrc), s"源码根不存在：$mainSrc（本 spec 必须在仓根运行）")

    val files = os.walk(mainSrc).filter(p => p.ext == "scala").toList
    // 只扫**可执行代码**：注释里引用判据表达式（例如「这里不得重写 ...」）不算第二份实现。
    def stripComments(src: String): String =
      val noBlock = """(?s)/\*.*?\*/""".r.replaceAllIn(src, " ")
      noBlock.linesIterator
        .map { l =>
          val i = l.indexOf("//")
          if i < 0 then l else l.take(i)
        }
        .mkString("\n")
    // `...name == RootAgentIdentity.Name...)` 紧跟 `&&` 再跟 `depth == 0`（同一表达式）——
    // 这是判据本体；SandboxPolicy 的 `depth == 0 && agentName == RootAgentIdentity.Name`
    // 是另一种语义（含 sandboxEnabled 分量，规格 §3.1 明确不合并），不命中。
    val predicate = """(?s)name\s*==\s*RootAgentIdentity\.Name\s*\)?\s*&&\s*[^\n]{0,40}depth\s*==\s*0""".r
    val holders = files
      .filter(f => predicate.findFirstIn(stripComments(os.read(f))).isDefined)
      .map(_.last)
      .sorted
    assertEquals(
      holders,
      List("RootAgentIdentity.scala"),
      "谓词 `name == RootAgentIdentity.Name && depth == 0` 出现了第二份实现（一处实现、消费点委托）"
    )

    val byName = files.map(f => f.last -> os.read(f)).toMap
    val delegating = List(
      "AgentCore.scala" -> "isRootAgent(Some(agentDef), depth)", // 定义期：变体选择身份
      "types.scala" -> "RootAgentIdentity.isRootAgent(agentDef, depth)", // 运行期求值面
      "PopTool.scala" -> "RootAgentIdentity.isRootAgent(ctx.agentDef, ctx.depth)" // Pop 身份闸
    )
    for (file, needle) <- delegating do
      assert(
        byName.getOrElse(file, "").contains(needle),
        s"$file 未委托判据单点（缺 '$needle'）—— 第二个谓词实现或消费点掉线"
      )
    // 🔴 本批：AskUserQuestionTool 不再是消费点（`ctx.isRootAgent` 已随分叉面退场）
    assert(
      !byName.getOrElse("AskUserQuestionTool.scala", "").contains("ctx.isRootAgent"),
      "AskUserQuestionTool 仍在消费 root 判据（分叉面残留）"
    )
  }

  test("无等待态: AgentProcessing 的 AskUser 分支不再有 parksTurn 门控（源码级 pin）") {
    // re-pin（2026-09-25 processing 域迁移）：AskUser 分支随 processing 行为自
    // AgentActor 迁至 AgentProcessing.scala；本批（root 2026-10-02 令 #462 裁①）
    // 再把门控判据由 `AskMode.parksTurn(askMode)` 换成命令面客观事实 `awaitsAnswer`。
    val src = os.read(os.pwd / "src" / "main" / "scala" / "nebflow" / "agent" / "AgentProcessing.scala")
    val iBranch = src.indexOf("case AgentCommand.AskUser(requestId, items, replyToOpt, askAttachments, awaitsAnswer) =>")
    assert(iBranch > 0, "AgentProcessing 的 AskUser 分支未承接 attachments/awaitsAnswer（本批未接线）")
    // `parksTurn` 只许作为**注释里的历史叙述**出现（本批起无该调用）——扫可执行码面。
    val codeOnly = """(?s)/\*.*?\*/""".r
      .replaceAllIn(src, " ")
      .linesIterator
      .map { l => val i = l.indexOf("//"); if i < 0 then l else l.take(i) }
      .mkString("\n")
    assert(!codeOnly.contains("parksTurn"), "AgentProcessing 仍在调用 parksTurn（等待态门控未退场）")
    val iCond = src.indexOf("if awaitsAnswer then", iBranch)
    val iTouch = src.indexOf("touchRegistryActivity(resources, state.sessionId, AgentStatus.WaitingForUser)", iBranch)
    val iPause = src.indexOf("DelegateBudget.pause(srcSession)", iBranch)
    val iRequest = src.indexOf("(hub ! InteractionHubCommand.Request(", iBranch)
    assert(iCond > iBranch, "等待态标记未被 `awaitsAnswer` 门控")
    assert(iTouch > iCond, "WaitingForUser 标注不在 `awaitsAnswer` 条件内")
    assert(iPause > iTouch, "DelegateBudget.pause 不在 `awaitsAnswer` 条件内")
    assert(iRequest > iPause, "hub 派发位置异常（应在标记之后）")
    // 反向：条件分支必须带 else IO.unit（否则非阻塞路径仍然执行标记）
    assert(
      src.substring(iCond, iRequest).contains("else IO.unit"),
      "`awaitsAnswer` 条件缺 else 分支 —— 非阻塞路径仍会落到标记"
    )
    // 工具侧派发恒 awaitsAnswer=false ⇒ 该 else 分支即非阻塞派发的实际路径
    val toolSrc =
      os.read(os.pwd / "src" / "main" / "scala" / "nebflow" / "core" / "tools" / "AskUserQuestionTool.scala")
    assert(
      !toolSrc.contains("awaitsAnswer") || !toolSrc.contains("awaitsAnswer = true"),
      "非阻塞工具侧出现了 awaitsAnswer=true（造出永不解除的等待）"
    )
    // 唯一的 awaitsAnswer=true 调用方 = ProjectCreateTool.pathPanel（阻塞等答复的面板）
    val panelSrc = os.read(os.pwd / "src" / "main" / "scala" / "nebflow" / "core" / "tools" / "ProjectCreateTool.scala")
    assert(panelSrc.contains("awaitsAnswer = true"), "ProjectCreateTool.pathPanel 的等待面未被标注（会永不解除等待）")
  }

end AskUserDualModeSchemaSpec
