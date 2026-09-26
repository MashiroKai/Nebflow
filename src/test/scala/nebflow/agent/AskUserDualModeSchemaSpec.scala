package nebflow.agent

import io.circe.Json
import munit.FunSuite
import nebflow.actor.{AgentDef, AskMode}
import nebflow.core.tools.{AskUserQuestionTool, ToolRegistry}
import nebflow.shared.ToolDefinition

/**
 * 工具面按角色分化批（2026-09-13 作者裁定 T1–T9）**定义层**验收：
 *
 *  ① **核心断言**：非 Nebula-root 角色的 `AskUserQuestion` 工具定义里
 *     **不存在** `mode`（属性**缺席** —— T9=(a)；不是 `enum:["blocking"]`、
 *     也不是「有属性但值非法」）；
 *  ① **互补断言**：root 侧 `mode` 存在 + `enum` 含 `"non-blocking"` +
 *     `default == "blocking"`（防「两份都缺席」这种假绿）；
 *  ③ **字节一致性**：非 root 两例的 schema + description 与基线定义
 *     **逐字节相同**（防「分化时顺手改了基础变体」）；
 *  变异 ② 的钉子：`NodeDef.agent="Nebula"` 的 **depth=1 节点**会话拿**基础**
 *     变体（成员资格按名判 ⇒ 它照样持卡 —— 只按名实现分化即放行漏洞）；
 *  **禁第二份表达式**：`name=="Nebula" && depth==0` 只许出现在判据单点内
 *     （grep 级静态断言）+ 三个消费点均为委托调用。
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

  /** 注册表里的**基线定义**（= 变更前后 NON-root 会话看到的那一份）。 */
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

  // 三个身份横断面（规格 §6① 的三例）
  private val rootFace = CoreProbe.face(defNamed("Nebula"), depth = 0)
  private val generalNodeFace = CoreProbe.face(defNamed("general"), depth = 1, flowNodeSession = true)
  private val kernelFace = CoreProbe.face(defNamed("kernel"), depth = 1)

  // ============================================================
  // ① 核心断言 + 互补断言
  // ============================================================

  test("① 核心断言: 非 root 两例（general 节点 / kernel）工具定义里 mode 缺席") {
    for (name, face) <- List("general(depth=1, flowNodeSession)" -> generalNodeFace, "kernel(depth=1)" -> kernelFace)
    do
      val td = askOf(face)
      assert(
        modeOf(td).isEmpty,
        s"$name 的 AskUserQuestion 定义里出现了 mode（第一性机制失效）：${modeOf(td)}"
      )
      // 属性缺席而非「enum 收窄」——properties 整体仍存在且含 questions（防把整个 properties 写坏）
      assert(
        td.inputSchema("properties").flatMap(_.asObject).exists(_.contains("questions")),
        s"$name: properties.questions 缺失 —— 基础 schema 结构被破坏"
      )
  }

  test("① 互补断言: root（Nebula depth=0）mode 存在 + enum 含 non-blocking + default=blocking") {
    val td = askOf(rootFace)
    val mode = modeOf(td).getOrElse(fail("root 侧 mode 缺席 —— 分化方向写反或两份都缺席（假绿）"))
    assertEquals(mode.hcursor.downField("type").as[String], Right("string"))
    val enumVals = mode.hcursor.downField("enum").as[List[String]].getOrElse(Nil)
    assert(enumVals.contains(AskMode.NonBlockingWire), s"enum 不含 non-blocking：$enumVals")
    assert(enumVals.contains(AskMode.BlockingWire), s"enum 不含 blocking：$enumVals")
    assertEquals(mode.hcursor.downField("default").as[String], Right(AskMode.BlockingWire))
  }

  test("① 基础变体不变量: 工具自述 schema 本身不含 mode（防并集 schema 写回）+ 常量分工") {
    assert(!AskUserQuestionTool.baseHasModeProperty, "基础 inputSchema 里出现了 mode —— 非 root 会话可见性被破坏")
    val rootVariant = ToolDefinition(
      AskUserQuestionTool.Name,
      AskUserQuestionTool.descriptionRoot,
      AskUserQuestionTool.schemaRoot(AskUserQuestionTool.inputSchema)
    )
    assert(
      modeOf(rootVariant).isDefined,
      "root 变体常量里 mode 缺席"
    )
    assertEquals(AskUserQuestionTool.descriptionBase, AskUserQuestionTool.description)
    assert(
      AskUserQuestionTool.descriptionRoot.startsWith(AskUserQuestionTool.description),
      "root 变体 description 必须以基础 description 原样开头（基础段零改动）"
    )
    assert(
      AskUserQuestionTool.descriptionRoot.length > AskUserQuestionTool.description.length,
      "root 变体 description 未增加非阻塞说明"
    )
  }

  // ============================================================
  // ③ 字节一致性（非 root 面零漂移）
  // ============================================================

  test("③ 字节一致性: 非 root 两例 schema + description 与基线定义逐字节相同") {
    for (name, face) <- List("general(depth=1)" -> generalNodeFace, "kernel(depth=1)" -> kernelFace)
    do
      val td = askOf(face)
      assertEquals(td.description, baseline.description, s"$name 的 description 发生漂移")
      assertEquals(td.inputSchema, baseline.inputSchema, s"$name 的 inputSchema 发生漂移")
      // 最强形态：整个 ToolDefinition 相等（含 name）
      assertEquals(td, baseline, s"$name 的 AskUserQuestion 定义不再是基线定义本身")
      assertEquals(td.description, AskUserQuestionTool.descriptionBase)
  }

  // ============================================================
  // 变异 ② 的钉子：按名判 vs 按「名 ∧ depth」判
  // ============================================================

  test("变异②钉子: NodeDef.agent=\"Nebula\" 的 depth=1 节点会话拿【基础】变体") {
    val face = CoreProbe.face(defNamed("Nebula"), depth = 1, flowNodeSession = true)
    val td = askOf(face) // 成员资格按名判 ⇒ 该形态照样持卡
    assert(modeOf(td).isEmpty, "depth=1 的 Nebula 节点会话拿到了 root 变体 —— 谓词丢了 depth 分量（静默放行）")
    assertEquals(td, baseline)
  }

  test("变异③钉子: 未登记/未知会话形态（AgentDef=None 的运行期对偶 + 陌生名）落基础变体") {
    // 定义期：任何非 root 形态都落基础变体（fail-closed 默认分支）——取一个
    // 「面内含本工具但名不是 Nebula」的未登记形态（声明了 AskUserQuestion）。
    val unknown = CoreProbe.face(defNamed("some-future-agent", tools = List(AskUserQuestionTool.Name)), depth = 0)
    assert(modeOf(askOf(unknown)).isEmpty, "depth=0 的非 Nebula 会话拿到了 root 变体（T1=(a) 口径被放宽）")
    // 运行期对偶：agentDef=None（REST 直调 / harness）+ depth=0 ⇒ fail-closed false
    assert(!AgentCore.isRootAgent(None, 0), "agentDef=None 时判据必须 fail-closed")
  }

  test("分化不改成员资格: 三例的工具面组成与基线一致（只换 schema，不插删元素）") {
    val baselineNames = ToolRegistry.ALL_TOOLS.map(_.name)
    for (name, face) <- List("Nebula" -> rootFace, "general" -> generalNodeFace, "kernel" -> kernelFace)
    do
      val names = face.map(_.name)
      // 顺序：扁平化自 ALL_TOOLS ⇒ 必然是注册表序的子序列（逐位不变）
      assertEquals(names, baselineNames.filter(names.contains), s"$name 的工具序不再是注册表序的子序列")
      assertEquals(names.distinct.size, names.size, s"$name 的工具面出现重复元素")
      assert(names.contains(AskUserQuestionTool.Name), s"$name 丢了 AskUserQuestion 成员资格")
  }

  // ============================================================
  // 判据单点：委托关系 + 四个身份组合
  // ============================================================

  test("判据单点: 四个身份组合的真值表（含 depth 分量与 fail-closed）") {
    val nebula = Some(defNamed("Nebula"))
    val general = Some(defNamed("general"))
    assertEquals(AgentCore.isRootAgent(nebula, 0), true, "Nebula+depth=0 必须是 root")
    assertEquals(AgentCore.isRootAgent(nebula, 1), false, "Nebula+depth=1（节点会话）不是 root")
    assertEquals(AgentCore.isRootAgent(general, 0), false, "非 Nebula 的 depth=0 根会话不是 root（T1=(a)）")
    assertEquals(AgentCore.isRootAgent(None, 0), false, "agentDef=None fail-closed")
    // ToolContext 派生 def 委托同一真值（运行期求值面）
    val ctxRoot = nebflow.core.tools.ToolContext(projectRoot = "", agentDef = nebula, depth = 0)
    val ctxNode = nebflow.core.tools.ToolContext(projectRoot = "", agentDef = nebula, depth = 1)
    val ctxNoDef = nebflow.core.tools.ToolContext(projectRoot = "", agentDef = None, depth = 0)
    assert(ctxRoot.isRootAgent, "ToolContext.isRootAgent 未委托单点（root 侧）")
    assert(!ctxNode.isRootAgent, "ToolContext.isRootAgent 未委托单点（depth 分量丢失）")
    assert(!ctxNoDef.isRootAgent, "ToolContext.isRootAgent 未 fail-closed")
  }

  test("禁第二份表达式: 谓词字面量只许出现在 AgentCore 单点内 + 三消费点均为委托") {
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
    // re-pin（2026-09-25 身份谓词单点化批）：判据名分字面量 "Nebula" 收敛为常量
    // RootAgentIdentity.Name（值不变），正则同步钉常量形态。
    val predicate = """(?s)name\s*==\s*RootAgentIdentity\.Name\s*\)?\s*&&\s*[^\n]{0,40}depth\s*==\s*0""".r
    val holders = files
      .filter(f => predicate.findFirstIn(stripComments(os.read(f))).isDefined)
      .map(_.last)
      .sorted
    assertEquals(
      holders,
      List("AgentCore.scala"),
      "谓词 `name == RootAgentIdentity.Name && depth == 0` 出现了第二份实现（规格 §3.1 判红纪律：一处实现、三个消费点）"
    )

    val byName = files.map(f => f.last -> os.read(f)).toMap
    val delegating = List(
      "AgentCore.scala" -> "isRootAgent(Some(agentDef), depth)", // 定义期选变体
      "types.scala" -> "AgentCore.isRootAgent(agentDef, depth)", // 运行期求值面
      "PopTool.scala" -> "AgentCore.isRootAgent(ctx.agentDef, ctx.depth)", // Pop 身份闸
      "AskUserQuestionTool.scala" -> "ctx.isRootAgent" // 非阻塞兜底闸
    )
    for (file, needle) <- delegating do
      assert(
        byName.getOrElse(file, "").contains(needle),
        s"$file 未委托判据单点（缺 '$needle'）—— 第二个谓词实现或消费点掉线"
      )
  }

  test("运行期闸位置: 闸在 askGuard 之后、askUser/副作用之前（源码级 pin）") {
    val src = os.read(os.pwd / "src" / "main" / "scala" / "nebflow" / "core" / "tools" / "AskUserQuestionTool.scala")
    val iParse = src.indexOf("parseMode(input) match")
    val iBlocking = src.indexOf("case Right(AskMode.Blocking) => askUser(input, ctx)")
    val iOtherwise = src.indexOf("else askUserNonBlocking(input, ctx)")
    assert(iParse > 0 && iBlocking > iParse && iOtherwise > iBlocking, "call() 的闸/分支顺序被改动")
    // askGuard 必须在解析之前（headless 恒第一顺位，规格 §3.4#1）
    val iGuard = src.indexOf("askGuard() match")
    assert(iGuard > 0 && iGuard < iParse, "askGuard 不再是 call() 的第一顺位（headless 守卫被降级）")
  }

  test("B4 分支结构 pin: AgentProcessing 的等待态标记（WaitingForUser + 预算 pause）只在阻塞模式发生") {
    // re-pin（2026-09-25 processing 域迁移）：AskUser 分支随 processing 行为自
    // AgentActor 迁至 AgentProcessing.scala，读取目标改为新文件（分支文本逐字
    // 未动，判据语义不变）。
    val src = os.read(os.pwd / "src" / "main" / "scala" / "nebflow" / "agent" / "AgentProcessing.scala")
    val iBranch = src.indexOf("case AgentCommand.AskUser(requestId, items, replyToOpt, askMode) =>")
    assert(iBranch > 0, "AgentProcessing 的 AskUser 分支未接收 mode（B4 未接线）")
    val iCond = src.indexOf("if AskMode.parksTurn(askMode) then", iBranch)
    val iTouch = src.indexOf("touchRegistryActivity(resources, state.sessionId, AgentStatus.WaitingForUser)", iBranch)
    val iPause = src.indexOf("DelegateBudget.pause(srcSession)", iBranch)
    val iRequest = src.indexOf("(hub ! InteractionHubCommand.Request(", iBranch)
    assert(iCond > iBranch, "等待态标记未由 AskMode.parksTurn 门控（非阻塞会被标成等待态）")
    assert(iTouch > iCond, "WaitingForUser 标注不在 parksTurn 条件内")
    assert(iPause > iTouch, "DelegateBudget.pause 不在 parksTurn 条件内")
    assert(iRequest > iPause, "hub 派发位置异常（应在标记之后）")
    // 反向：条件分支必须带 else IO.unit（否则非阻塞路径仍然执行标记）
    assert(
      src.substring(iCond, iRequest).contains("else IO.unit"),
      "parksTurn 条件缺 else 分支 —— 非阻塞路径仍会落到标记"
    )
    // 纯判据真值表（运行态不可判红的部分由此承担，见交付说明）
    assert(AskMode.parksTurn(AskMode.Blocking), "阻塞必须 park")
    assert(!AskMode.parksTurn(AskMode.NonBlocking), "非阻塞不得 park")
  }

end AskUserDualModeSchemaSpec
