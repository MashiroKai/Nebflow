package nebflow.core

import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.{BeforeEach, FunSuite}

/**
 * P0-1 / P-M1 审批门验收（spec `.nebflow/Spec/20260907_sandbox-mcp-spec.md` §2.7 的 A1-1…A1-8
 * 中可在**门本体**层面二值取值的部分）。
 *
 * 取值纪律（本批）：断言打在**生产代码本体**上 —— `McpToolGate.decide` /
 * `needApproval` / `cardPayload` / `SessionApprovals` / `PermissionUpgrade.parse`，
 * 不另造等价物。A1-5 / A1-7 的取值面在 `nebflow.agent.McpPermissionReplaySpec`
 * （生产 `answerCompletes` / `snapshotFrames`）。
 *
 * 逐条红验口径：见报告 §A1 表（改前必红 / 改后绿，A1-8 例外=改前改后均绿）。
 */
class McpToolGateSpec extends FunSuite:

  private val C = SafetyMode.ConfirmEdits
  private val AE = SafetyMode.AutoEdits
  private val AA = SafetyMode.AutoAll

  // 词表命中前提（spec §2.1 启发式词表；本 spec 用它们构造 L0/L1/L2/L3 四档样件）
  private val L0Tool = "mcp__plugin_acme_browser__get_content"
  private val L1Tool = "mcp__plugin_acme_browser__tabs"
  private val L2Tool = "mcp__plugin_acme_browser__navigate"
  private val L3Tool = "mcp__plugin_nebflow-computer-use_mac__click"
  private val L3Shot = "mcp__plugin_nebflow-computer-use_mac__screenshot"
  private val Server = "plugin_acme_browser"

  override def beforeEach(context: BeforeEach): Unit =
    SessionApprovals.reset()
    DeclarationSource.reset()
    ExecForm.reset()

  private def decide(
    name: String,
    mode: SafetyMode,
    session: String = "s1",
    input: JsonObject = JsonObject.empty,
    form: ExecForm = ExecForm.HostExecutor
  ): McpGateOutcome =
    val ref = McpToolGate
      .surfaceOf(name)
      .getOrElse(fail(s"$name 不是审批门面内的工具（面判定为 None）"))
    McpToolGate.decide(ref, input, mode, session, form = form)

  private def cards(name: String, mode: SafetyMode): Int =
    if decide(name, mode).needApproval then 1 else 0

  // ============================================================
  // A1-1 未声明 MCP 工具在 ConfirmEdits 触发审批卡（缺省 confirm 反转）
  // ============================================================
  test("A1-1 未声明 MCP 工具在 ConfirmEdits 出卡；声明档标 undeclared（反转免审缺省）") {
    val o = decide(L2Tool, C)
    assertEquals(o.tier, RiskTier.L2)
    assertEquals(o.declared, Declared.Undeclared)
    assertEquals(o.needApproval, true, "未声明工具缺省 confirm（spec §2.2 fail-safe 反转）")
    assertEquals(o.allowed, false)
    assertEquals(o.tier.dangerLevel, 2, "dangerLevel 映射 L2→2（对齐既有 askPermission 3/2/1 语义）")
    // 出卡形状（type=mcpPermission）由 cardPayload 给；此处只判「要出卡」
    assertEquals(cards(L2Tool, C), 1)
    // 三档对照：C=审 / AE=审 / AA=放（§2.2 未声明列）
    assertEquals(cards(L2Tool, AE), 1)
    assertEquals(cards(L2Tool, AA), 0)
    // 未声明**只读**工具同样缺省 confirm（缺省档不看级别 —— §2.2 未声明列 L0 行 = 审｜审｜放）
    assertEquals(decide(L0Tool, C).tier, RiskTier.L0)
    assertEquals(cards(L0Tool, C), 1, "未声明 L0 在 ConfirmEdits 仍出卡（缺省 confirm 不分级）")
  }

  // ============================================================
  // A1-2 声明来源接缝：声明 auto 的 L1 在 AutoEdits 不出卡 vs 声明 confirm 的外域动作出卡（0 vs ≥1）
  // ============================================================
  test("A1-2 接缝注入 declare：同 server 两工具对照（auto→0 卡 / confirm→≥1 卡）") {
    DeclarationSource.install(new DeclarationSource:
      def lookup(surface: ToolSurface, serverId: String, tool: String): Option[Declared] =
        if serverId != Server then None
        else
          tool match
            case "tabs"     => Some(Declared.DeclaredAuto)
            case "navigate" => Some(Declared.DeclaredConfirm)
            case _          => None
    )
    val autoCards = cards(L1Tool, AE)
    val confirmCards = cards(L2Tool, AE)
    assertEquals(autoCards, 0, "声明 auto 的 L1 在 AutoEdits 不出卡")
    assertEquals(confirmCards, 1, "声明 confirm 的外域动作在 AutoEdits 出卡")
    assertEquals(decide(L1Tool, AE).declared, Declared.DeclaredAuto)
    assertEquals(decide(L2Tool, AE).declared, Declared.DeclaredConfirm)
    // 声明 auto 的语义面：全档免审（§2.2 auto 列 放｜放｜放）
    assertEquals(cards(L1Tool, C), 0)
    assertEquals(cards(L1Tool, AA), 0)
  }

  test("A1-2(接缝边界) 接缝级达成 vs 端到端 manifest 消费 —— 本批缺省实现恒 undeclared") {
    // P0-1 缺省实现 = DeclarationSource.alwaysUndeclared（P0-4 才换真档表）
    assertEquals(DeclarationSource.get.lookup(ToolSurface.Mcp, Server, "tabs"), None)
    assertEquals(decide(L1Tool, C).declared, Declared.Undeclared)
  }

  // ============================================================
  // A1-3 L3 宿主动作在 AutoAll 仍出卡且带 hostBanner
  // ============================================================
  test("A1-3 L3 宿主动作在 AutoAll 仍出卡且带 hostBanner（全表唯一 AA 仍审行）") {
    val o = decide(L3Tool, AA)
    assertEquals(o.tier, RiskTier.L3)
    assertEquals(o.needApproval, true, "宿主面红线：AutoAll 仍审")
    assertEquals(o.hostBanner, true, "form=host ∧ tier=L3 ⇒ 红横幅字段")
    assertEquals(o.allowed, false)
    assertEquals(o.tier.dangerLevel, 3)
    // 三档皆审（§2.2 L3 行 审｜审｜审 —— 未声明 / 声明 auto / 声明 confirm 全列）
    List(C, AE, AA).foreach(m => assertEquals(cards(L3Tool, m), 1, s"L3 在 $m 档必须出卡"))
    // 拒绝后动作不执行 = AgentCore 的 `if answer.approved then executeTool(...) else <deny>` 分支
    // （见报告 §A1 表 A1-3 行的代码判据锚点；端到端「目标 app 无状态变化」属 verify/E2E 面）
  }

  test("A1-3(声明面) 声明 auto 亦不能豁免 L3（宿主红线优先于声明档）") {
    DeclarationSource.install(new DeclarationSource:
      def lookup(surface: ToolSurface, serverId: String, tool: String): Option[Declared] =
        Some(Declared.DeclaredAuto)
    )
    val o = decide(L3Tool, AA)
    assertEquals(o.declared, Declared.DeclaredAuto)
    assertEquals(o.needApproval, true, "声明 auto + L3 ⇒ 仍审")
    assertEquals(o.hostBanner, true)
  }

  // ============================================================
  // A1-4 递进放行与红线
  // ============================================================
  test("A1-4 非 L3 卡可升档；L3 卡不渲染升级选项；deny+upgrade 与未知目标被拒") {
    assertEquals(decide(L1Tool, C).allowUpgrade, true, "非 L3 卡渲染升级选项")
    assertEquals(decide(L2Tool, C).allowUpgrade, true)
    assertEquals(decide(L3Tool, C).allowUpgrade, false, "L3 卡不渲染升级选项（宿主红线不可因升档免审）")
    // PermissionUpgrade.parse 语义原样（本批零改动）
    assertEquals(PermissionUpgrade.parse(false, Some("auto-edits")).isLeft, true, "deny 带 upgrade 被拒")
    assertEquals(PermissionUpgrade.parse(false, Some("auto-all")).isLeft, true)
    assertEquals(PermissionUpgrade.parse(true, Some("auto-edits")), Right(Some(SafetyMode.AutoEdits)))
    assertEquals(PermissionUpgrade.parse(true, Some("auto-all")), Right(Some(SafetyMode.AutoAll)))
    assertEquals(PermissionUpgrade.parse(true, Some("confirm-edits")).isLeft, true, "未知升级目标被拒")
  }

  test("A1-4(策略表口径) 升档到 auto-edits 后**未声明 L1 仍出卡** —— 与 A1-4 字面「L1 免审」不一致（呈裁项）") {
    // §2.2 策略表 L1·未声明列 = 审｜审｜放（roadmap §2.2 总表同格）⇒ 升到 auto-edits 不免审。
    // A1-4 字面「L1 卡回 upgradeMode=auto-edits → 后续同 server L1 免审」与策略表冲突，
    // 本批按**策略表**（统一收口件）实施，冲突如实单列呈裁（报告 §开放项 O-2）。
    assertEquals(cards(L1Tool, AE), 1, "auto-edits 档未声明 L1 仍审（表口径）")
    assertEquals(cards(L1Tool, AA), 0, "只有 AutoAll 才放行未声明 L1")
  }

  // ============================================================
  // A1-6 scope=session 会话放行记忆（P0-2，S2=启用）
  // ============================================================
  test("A1-6 session 放行免审 + 新会话恢复出卡 + 会话终态清空") {
    assert(decide(L2Tool, C, session = "session-A").needApproval, "放行前出卡")
    SessionApprovals.remember("session-A", Server, "navigate")
    val after = decide(L2Tool, C, session = "session-A")
    assertEquals(after.sessionApproved, true)
    assertEquals(after.needApproval, false, "同会话同 (server,tool) 免审")
    assertEquals(decide(L2Tool, C, session = "session-B").needApproval, true, "新会话不继承 ⇒ 恢复出卡")
    SessionApprovals.clear("session-A")
    assertEquals(decide(L2Tool, C, session = "session-A").needApproval, true, "会话终态清空")
  }

  test("A1-6(L3 红线) 会话放行不外溢宿主红线：L3 恒审") {
    SessionApprovals.remember("session-A", "plugin_nebflow-computer-use_mac", "click")
    val o = decide(L3Tool, C, session = "session-A")
    assertEquals(o.sessionApproved, false, "L3 不适用会话放行（§6 #2 口径 = L0-L2）")
    assertEquals(o.needApproval, true)
    assertEquals(o.hostBanner, true)
  }

  // ============================================================
  // A1-8 内置工具零回归
  // ============================================================
  test("A1-8 内置工具面判定为 None（不进审批门）；isReversible 四类判定逐条不变") {
    List("Read", "Write", "Edit", "MultiEdit", "Bash", "Curl", "Grep", "Glob", "Pop", "Card", "AskUserQuestion")
      .foreach(t => assertEquals(McpToolGate.surfaceOf(t), None, s"内置工具 $t 不得进审批门"))

    // 与 tools/scala 既有 ToolReversibilitySpec 同判据（改前改后均绿）
    assertEquals(ToolReversibility.isReversible("Write", JsonObject.empty, C), false)
    assertEquals(ToolReversibility.isReversible("Write", JsonObject.empty, AE), true)
    assertEquals(ToolReversibility.isReversible("Write", JsonObject.empty, AA), true)
    assertEquals(ToolReversibility.isReversible("Edit", JsonObject.empty, C), false)
    assertEquals(ToolReversibility.isReversible("Edit", JsonObject.empty, AE), true)
    assertEquals(ToolReversibility.isReversible("Read", JsonObject.empty, C), true)
    assertEquals(ToolReversibility.isReversible("Bash", JsonObject.empty, C), true)
    assertEquals(ToolReversibility.isReversible("Curl", JsonObject.empty, C), true)
    assertEquals(
      ToolReversibility.isReversible("Curl", JsonObject("method" -> Json.fromString("POST")), C),
      false
    )
    assertEquals(
      ToolReversibility.isReversible("Curl", JsonObject("method" -> Json.fromString("get")), C),
      true
    )
  }

  // ============================================================
  // §2.2 策略表逐格（未声明 / 声明 auto / 声明 confirm × L0-L3 × 三档）
  // ============================================================
  test("§2.2 策略表逐格：12 格 × 3 档 = 36 格全对（含 L3 全表唯一恒审行）") {
    // 表真源：spec §2.2（沙箱章）+ roadmap §2.2 总表
    // 行 = 级，列 = (未声明, 声明auto, 声明confirm)，每格三档 = C|AE|AA，值 = 是否出卡
    val table: Map[RiskTier, List[(Declared, List[Boolean])]] = Map(
      RiskTier.L0 -> List(
        Declared.Undeclared -> List(true, true, false),
        Declared.DeclaredAuto -> List(false, false, false),
        Declared.DeclaredConfirm -> List(true, true, false)
      ),
      RiskTier.L1 -> List(
        Declared.Undeclared -> List(true, true, false),
        Declared.DeclaredAuto -> List(false, false, false),
        Declared.DeclaredConfirm -> List(true, true, false)
      ),
      RiskTier.L2 -> List(
        Declared.Undeclared -> List(true, true, false),
        Declared.DeclaredAuto -> List(false, false, false),
        Declared.DeclaredConfirm -> List(true, true, false)
      ),
      RiskTier.L3 -> List(
        Declared.Undeclared -> List(true, true, true),
        Declared.DeclaredAuto -> List(true, true, true),
        Declared.DeclaredConfirm -> List(true, true, true)
      )
    )
    val modes = List(C, AE, AA)
    table.foreach { case (tier, rows) =>
      rows.foreach { case (declared, expected) =>
        modes.zip(expected).foreach { case (mode, exp) =>
          assertEquals(
            McpToolGate.needApproval(tier, declared, mode),
            exp,
            s"§2.2 表 $tier × $declared × $mode"
          )
        }
      }
    }
  }

  // ============================================================
  // 形态修正（spec §2.1-1）—— 设计点，非 A 项
  // ============================================================
  test("形态修正：宿主形态下 screenshot 判 L3；容器形态下 L3 结构性失效、无 hostBanner") {
    val host = decide(L3Shot, C, form = ExecForm.HostExecutor)
    assertEquals(host.tier, RiskTier.L3)
    assertEquals(host.hostBanner, true)
    val container = decide(L3Shot, C, form = ExecForm.Container)
    assertNotEquals(container.tier, RiskTier.L3, "容器形态 ⇒ 宿主面动作结构性不可达，不得判 L3")
    assertEquals(container.hostBanner, false)
    assert(container.tierSource.contains("form-correction"), container.tierSource)
  }

  // ============================================================
  // 工具标识解析（roadmap §2.2 附表 三元组）+ 覆盖面（MCP + ScriptTool）
  // ============================================================
  test("工具标识解析：plugin 三段式 / agent 级 / 畸形名不猜") {
    val p = McpToolRef.parse("mcp__plugin_nebflow-browser-qa_playwright__browser_navigate")
    assertEquals(p.map(_.serverId), Some("plugin_nebflow-browser-qa_playwright"))
    assertEquals(p.flatMap(_.plugin), Some("nebflow-browser-qa"))
    assertEquals(p.map(_.tool), Some("browser_navigate"))
    val a = McpToolRef.parse("mcp__agent-Nebula_myserver__do_thing")
    assertEquals(a.map(_.serverId), Some("agent-Nebula_myserver"))
    assertEquals(a.flatMap(_.plugin), None)
    assertEquals(a.map(_.tool), Some("do_thing"))
    assertEquals(McpToolRef.parse("Write"), None)
    assertEquals(McpToolRef.parse("mcp__onlyserver"), None)
    assertEquals(McpToolRef.parse("mcp____tool"), None)
    assertEquals(McpToolRef.parse("mcp__srv__"), None)
  }

  test("覆盖面：MCP（名模式）与 ScriptTool（注册表身份）两类都进面判定；普通未知名不进") {
    assert(McpToolGate.surfaceOf(L2Tool).isDefined)
    // ScriptTool：名字是自由文本（`ScriptTool.name = config.name`），**无名字模式** ——
    // 判据 = ToolRegistry 注册表身份。未注册的名字 ⇒ 不是 ScriptTool（这里无法注册真实例，
    // 故只断言「未知名不进面」这半边；注册表身份的正面用例见报告 §ScriptTool 读数）。
    assertEquals(McpToolGate.surfaceOf("some_external_tool"), None)
    assertEquals(McpToolGate.surfaceOf("Write"), None)
  }

  // ============================================================
  // 卡面 payload（roadmap §2.2 附表 + N3 redact）
  // ============================================================
  test("卡面 payload：必备字段齐备（§2.2 附表）、凭据被遮蔽、不带原始 input") {
    val input = JsonObject(
      "url" -> Json.fromString("https://example.com/x"),
      "api_key" -> Json.fromString("sk-SECRET-DO-NOT-LEAK"),
      "nested" -> Json.obj("password" -> Json.fromString("hunter2"), "ok" -> Json.fromString("v"))
    )
    val o = decide(L2Tool, C, input = input)
    val card = McpToolGate.cardPayload(o, "confirm-edits")
    val keys = card.asObject.map(_.keys.toSet).getOrElse(Set.empty)
    List(
      "type", "toolName", "serverId", "plugin", "tool", "inputSummary", "summary",
      "riskTier", "declared", "tierSource", "execForm", "hostBanner", "allowUpgrade",
      "dangerLevel", "safetyMode"
    ).foreach(k => assert(keys.contains(k), s"payload 缺字段 $k"))
    assertEquals(card.hcursor.downField("type").as[String].toOption, Some("mcpPermission"))
    assertEquals(card.hcursor.downField("hostBanner").as[Boolean].toOption, Some(false))
    assertEquals(card.hcursor.downField("allowUpgrade").as[Boolean].toOption, Some(true))
    assert(!card.noSpaces.contains("sk-SECRET-DO-NOT-LEAK"), "凭据不得上卡面")
    assert(!card.noSpaces.contains("hunter2"), "嵌套凭据不得上卡面")
    assert(!keys.contains("input"), "mcpPermission 卡不得携带原始 input（与内置 askPermission 分化）")
    assert(card.noSpaces.contains("example.com"), "非凭据参数应保留（可判性）")
  }

  test("N3 redact：字段名启发式遮蔽（递归）+ 截断防卡面爆炸") {
    assertEquals(GateRedact.isSecretKey("api_key"), true)
    assertEquals(GateRedact.isSecretKey("PASSWORD"), true)
    assertEquals(GateRedact.isSecretKey("accessToken"), true)
    assertEquals(GateRedact.isSecretKey("client_secret"), true)
    assertEquals(GateRedact.isSecretKey("credential"), true)
    assertEquals(GateRedact.isSecretKey("url"), false)
    assertEquals(GateRedact.isSecretKey("path"), false)

    val red = GateRedact.summarize(JsonObject(
      "a" -> Json.fromString("keep-me"),
      "token" -> Json.fromString("t0k3n"),
      "list" -> Json.arr(Json.obj("secret" -> Json.fromString("s")), Json.fromString("plain"))
    ))
    assert(red.contains("keep-me"))
    assert(red.contains("***"))
    assert(!red.contains("t0k3n"))
    assert(red.contains("\"plain\""), "非凭据字段照常展示")
    assert(!red.contains("\"s\""), "嵌套对象内的凭据同样遮蔽")

    val big = GateRedact.summarize(JsonObject("blob" -> Json.fromString("x" * 5000)))
    assert(big.length < 1200, s"摘要必须截断（实际 ${big.length}）")
    assert(big.contains("..."), "截断须显式标注")
  }

  // ============================================================
  // 审计行（spec §2.3 末条）
  // ============================================================
  test("审计行：含 tier / declared / 来源 / 形态 / 决定") {
    val line = McpToolGate.auditLine(decide(L3Tool, AA, session = "sess-9"), AA, "sess-9")
    List("event=mcpGate", "decision=ask", s"tool=$L3Tool", "tier=L3", "declared=undeclared",
      "tierSource=", "form=host-executor", "safetyMode=auto-all", "hostBanner=true", "session=sess-9")
      .foreach(frag => assert(line.contains(frag), s"审计行缺 $frag : $line"))
  }
