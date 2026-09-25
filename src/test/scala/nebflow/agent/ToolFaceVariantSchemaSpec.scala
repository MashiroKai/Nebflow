package nebflow.agent

import munit.FunSuite
import nebflow.core.tools.{AskUserQuestionTool, MailTool, NodeReportToolDef, ToolRegistry}
import nebflow.shared.ToolDefinition

/**
 * 工具面通则 Q4+Q5 实施批（2026-09-13 作者裁定）**定义层机制**验收：
 *
 *  ① **机制可判红**：变体选择单点 `AgentCore.schemaVariantFor`（唯一消费点）+
 *     两份定义常量（基础变体 = 逐字节现状 / 分化变体）+ 选择逻辑可证；去掉接线
 *     ⇒ 目标角色回落基础面（判红面 = 本 spec 的「分化面缺席」断言）。
 *  ② **零回归负控（逐字节）**：非目标工具、非目标角色的定义与基线**逐字节相同**；
 *     `inputSchema` 全量 sha256 对照（Q5 只动 `description`）。
 *  ③ **试点读数**：`node_report` 逐角色 description（改前 = 注册表并集面 / 改后 =
 *     角色变体）+ 该角色取到的变体说明。
 *  ④ **Q5 读数**：Mail 地址面逐角色 + schema 逐字节不变断言。
 *  ⑤ **变异三条的钉子**（变异实测见证据目录 `mutations.md`）：
 *     ①去掉变体选择分支 ⇒ 「分化描述缺失」断言必红；
 *     ②默认分支改分化变体 ⇒ 「基础身份 == 注册表定义」断言必红（fail-closed）；
 *     ③判据漏身份维度（丢 depth / 丢角色归一）⇒ 「旁支会话误取变体」断言必红。
 *  ⑥ **措辞来源纪律**：分化变体**零新造字**——逐段取自基础文本、纯删段可逆。
 *
 * 跑法：单测直调 `AgentCore.buildToolList`（protected ⇒ 经先例
 * `AllowedToolSetSpec` 的 `CoreProbe extends AgentCore` 暴露）。
 */
class ToolFaceVariantSchemaSpec extends FunSuite:

  /** protected `buildToolList` 的探针（先例：`AskUserDualModeSchemaSpec.CoreProbe`）。 */
  private object CoreProbe extends AgentCore:

    def face(
      defn: AgentDef,
      depth: Int = 0,
      flowNodeSession: Boolean = false,
      flowNodeRole: Option[String] = None,
      isDispatcher: Boolean = false,
      projectBoardSession: Boolean = false
    ): List[ToolDefinition] =
      buildToolList(
        defn,
        depth,
        flowNodeSession = flowNodeSession,
        flowNodeRole = flowNodeRole,
        isDispatcher = isDispatcher,
        projectBoardSession = projectBoardSession
      ).getOrElse(Nil)

  end CoreProbe

  private def defNamed(name: String, category: String = "standalone"): AgentDef =
    AgentDef(name = name, description = "", tools = Nil, category = category)

  private def sha(s: String): String =
    java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(s.getBytes("UTF-8"))
      .map(b => f"$b%02x")
      .mkString

  private def baseOf(name: String): ToolDefinition =
    ToolRegistry.ALL_TOOLS.find(_.name == name).getOrElse(fail(s"$name 不在注册表"))

  private def find(tds: List[ToolDefinition], name: String): Option[ToolDefinition] =
    tds.find(_.name == name)

  /**
   * 载荷序列化（与 `AnthropicAdapter.toAnthropicTools` 同形；spec 内单一实现）：
   * 用于「工具载荷字节数 / sha256」读数（cache 前缀代价的字节面）。
   */
  private def payload(tds: List[ToolDefinition]): String =
    tds
      .map(t => s"""{"name":${t.name},"description":${t.description},"input_schema":${t.inputSchema}}""")
      .mkString("[", ",", "]")

  // ============================================================
  // 身份横断面（六个）
  // ============================================================
  private val nebulaRootFace = CoreProbe.face(defNamed("Nebula"), depth = 0)

  private val dispatcherFace =
    CoreProbe.face(defNamed("project-dispatcher"), isDispatcher = true, projectBoardSession = true)

  private val taskNodeFace =
    CoreProbe.face(
      defNamed("general"),
      depth = 1,
      flowNodeSession = true,
      projectBoardSession = true,
      flowNodeRole = Some("task")
    )

  private val verifierNodeFace =
    CoreProbe.face(
      defNamed("general"),
      depth = 1,
      flowNodeSession = true,
      projectBoardSession = true,
      flowNodeRole = Some("verifier")
    )
  private val kernelFace = CoreProbe.face(defNamed("kernel"), depth = 1)
  private val teamFace = CoreProbe.face(defNamed("Coder", category = "team"))

  private val crossSections: List[(String, List[ToolDefinition])] = List(
    "Nebula-root" -> nebulaRootFace,
    "project-dispatcher" -> dispatcherFace,
    "node(role=task)" -> taskNodeFace,
    "node(role=verifier)" -> verifierNodeFace,
    "kernel(depth=1)" -> kernelFace,
    "team-member(legacy)" -> teamFace
  )

  // ============================================================
  // ① 机制：单点选择 + 两份常量存在 + 选择逻辑可证
  // ============================================================

  test("① 机制存在: 两份定义常量（基础变体逐字节=现状 / 分化变体）") {
    // 基础变体恒等于「现状那一份」——分化绝不改基础面
    assertEquals(MailTool.descriptionBase, MailTool.description, "Mail 基础变体被改动")
    assertEquals(NodeReportToolDef.descriptionBase, NodeReportToolDef.description, "node_report 基础变体被改动")
    assertEquals(MailTool.description, baseOf("Mail").description, "注册表里 Mail 的 description 不是基础变体")
    assertEquals(NodeReportToolDef.description, baseOf(NodeReportToolDef.Name).description)

    // 分化变体存在且与基础面不同、且更短（纯删段 ⇒ 不可能更长）
    assert(MailTool.descriptionRoot != MailTool.descriptionBase, "Mail root 分化变体与基础面相同（机制空转）")
    assert(MailTool.descriptionDispatcher != MailTool.descriptionBase, "Mail dispatcher 分化变体与基础面相同（机制空转）")
    assert(MailTool.descriptionRoot.length < MailTool.descriptionBase.length, "root 变体未变短")
    assert(MailTool.descriptionDispatcher.length < MailTool.descriptionBase.length, "dispatcher 变体未变短")
    assert(NodeReportToolDef.descriptionTask != NodeReportToolDef.descriptionBase, "task 变体与基础面相同")
    assert(NodeReportToolDef.descriptionVerifier != NodeReportToolDef.descriptionBase, "verifier 变体与基础面相同")
    assert(NodeReportToolDef.descriptionTask.length < NodeReportToolDef.descriptionBase.length)
    assert(NodeReportToolDef.descriptionVerifier.length < NodeReportToolDef.descriptionBase.length)
  }

  test("① 选择逻辑可证: 同一 ToolDefinition 输入按身份产出不同定义；基础身份恒等映射") {
    val mail = baseOf("Mail")
    assertEquals(AgentCore.schemaVariantFor(mail, AgentCore.ToolFaceIdentity()), mail, "基础身份未恒等映射")
    assertEquals(
      AgentCore.schemaVariantFor(mail, AgentCore.ToolFaceIdentity(isRootAgent = true)).description,
      MailTool.descriptionRoot
    )
    assertEquals(
      AgentCore.schemaVariantFor(mail, AgentCore.ToolFaceIdentity(isDispatcher = true)).description,
      MailTool.descriptionDispatcher
    )
    val nr = baseOf(NodeReportToolDef.Name)
    assertEquals(
      AgentCore.schemaVariantFor(nr, AgentCore.ToolFaceIdentity(nodeRole = Some("task"))).description,
      NodeReportToolDef.descriptionTask
    )
    assertEquals(
      AgentCore.schemaVariantFor(nr, AgentCore.ToolFaceIdentity(nodeRole = Some("verifier"))).description,
      NodeReportToolDef.descriptionVerifier
    )
    // 变体选择**只**动 description（Q5 判据 + 试点口径）
    for (td, id) <- List(
        (mail, AgentCore.ToolFaceIdentity(isRootAgent = true)),
        (mail, AgentCore.ToolFaceIdentity(isDispatcher = true)),
        (nr, AgentCore.ToolFaceIdentity(nodeRole = Some("task"))),
        (nr, AgentCore.ToolFaceIdentity(nodeRole = Some("verifier")))
      )
    do assertEquals(AgentCore.schemaVariantFor(td, id).inputSchema, td.inputSchema, s"${td.name}: 变体改了 inputSchema")
  }

  test("① 判据单点: 三个变体构造器只被 AgentCore 单点调用（源码级 pin）") {
    val root = os.pwd
    val mainSrc = root / "src" / "main" / "scala"
    assert(os.exists(mainSrc), s"源码根不存在：$mainSrc（本 spec 必须在仓根运行）")
    val callers = Map(
      "rootVariant(" -> "AgentCore.scala",
      "roleVariant(" -> "AgentCore.scala",
      "addressFaceVariant(" -> "AgentCore.scala"
    )
    for (needle, owner) <- callers do
      val files = os
        .walk(mainSrc)
        .filter(p => p.ext == "scala")
        .filter { p =>
          val src = os.read(p)
          // 定义行（`def rootVariant(`）不算调用；其余出现处必须是单点文件。
          src.linesIterator.exists(l => l.contains(needle) && !l.contains("def " + needle))
        }
        .map(_.last)
        .toList
        .sorted
      assertEquals(files, List(owner), s"变体构造器 '$needle' 出现了第二个调用点（变体选择必须单点）")

    // 身份装配判据（角色维度）只许出现在 AgentCore 单点内
    val roleFilterFiles = os
      .walk(mainSrc)
      .filter(p => p.ext == "scala")
      .filter(_.last != "AgentCore.scala")
      .filter(p => os.read(p).contains("filter(NodeRoles.isValid)"))
      .map(_.last)
      .toList
    assertEquals(roleFilterFiles, Nil, "节点角色判据出现了第二份表达式（规格：一处实现、单点消费）")

    // 既有 root 判据仍单点（与 AskUser 批同一纪律；此处复验不回归）。
    // re-pin（2026-09-25 身份谓词单点化批）：判据名分字面量 "Nebula" 收敛为常量
    // RootAgentIdentity.Name（值不变），正则同步钉常量形态。
    val pred = """(?s)name\s*==\s*RootAgentIdentity\.Name\s*\)?\s*&&\s*[^\n]{0,40}depth\s*==\s*0""".r
    val holders = os
      .walk(mainSrc)
      .filter(p => p.ext == "scala")
      .filter { p =>
        val noBlock = """(?s)/\*.*?\*/""".r.replaceAllIn(os.read(p), " ")
        val noLine = noBlock.linesIterator
          .map { l =>
            val i = l.indexOf("//"); if i < 0 then l else l.take(i)
          }
          .mkString("\n")
        pred.findFirstIn(noLine).isDefined
      }
      .map(_.last)
      .toList
      .sorted
    assertEquals(holders, List("AgentCore.scala"), "root 判据出现第二份实现")
  }

  // ============================================================
  // ② 零回归负控（逐字节）+ schema 全量 sha256
  // ============================================================

  test("② 负控: 基础身份 / 无目标工具的身份面 == 注册表定义（逐字节，含 sha256）") {
    for (name, face) <- List(
        "kernel(depth=1, 无 Mail 无 node_report)" -> kernelFace,
        "Nebula 名 depth=1 节点会话" -> CoreProbe.face(defNamed("Nebula"), depth = 1, flowNodeSession = true)
      )
    do
      val baselineNames = ToolRegistry.ALL_TOOLS.map(_.name)
      assertEquals(face.map(_.name), baselineNames.filter(n => face.map(_.name).contains(n)))
      for td <- face do
        val base = baseOf(td.name)
        assertEquals(td.description, base.description, s"$name 的 ${td.name} description 漂移")
        assertEquals(td.inputSchema, base.inputSchema, s"$name 的 ${td.name} inputSchema 漂移")
        assertEquals(td, base, s"$name 的 ${td.name} 定义不再是注册表定义本身")
        assertEquals(sha(td.description), sha(base.description))
  }

  test("② inputSchema 全量 sha256 对照: 六个身份 × 全部工具，schema 逐字节不变") {
    val baseline = ToolRegistry.ALL_TOOLS.map(td => td.name -> td.inputSchema).toMap
    for (name, face) <- crossSections do
      val drifted = face.filter(td => td.inputSchema != baseline(td.name)).map(_.name).toSet
      // **全注册表唯一允许的 schema 差异** = AskUser 批（已合并）的 root `mode` 属性；
      // 本批（Q4/Q5）在**任何身份**下都不得改 schema（Q5 口径 + 试点口径）。
      val allowed = if name == "Nebula-root" then Set(AskUserQuestionTool.Name) else Set.empty[String]
      assertEquals(drifted, allowed, s"$name 的 schema 漂移超出允许面（本批判据 = 只动 description）")
      for td <- face do
        if !allowed.contains(td.name) then
          assertEquals(
            td.inputSchema,
            baseline(td.name),
            s"$name 的 ${td.name} schema 漂移 —— 本批判据 = 只动 description（Q5 口径）"
          )
  }

  test("② 负控: 每个身份的工具面组成 = 注册表序子序列 + 零重复（成员资格零改动）") {
    val baselineNames = ToolRegistry.ALL_TOOLS.map(_.name)
    for (name, face) <- crossSections do
      val names = face.map(_.name)
      assertEquals(names, baselineNames.filter(names.contains), s"$name 的工具序不再是注册表序的子序列")
      assertEquals(names.distinct.size, names.size, s"$name 工具面出现重复元素")
  }

  test("② 负控: 非目标角色的目标工具 == 注册表定义（并集面，逐字节）") {
    // team 成员（legacy 面）持 Mail 但无身份维度 ⇒ 必须仍是并集面
    val teamMail = find(teamFace, "Mail").getOrElse(fail("team 面丢了 Mail"))
    assertEquals(teamMail, baseOf("Mail"), "未登记身份拿到了分化面（fail-closed 破裂）")
    // 节点会话持 node_report 但 role 缺省/非法 ⇒ 基础面
    for role <- List(None, Some(""), Some("unknown-role"), Some("verifier-x"))
    do
      val face = CoreProbe.face(defNamed("general"), depth = 1, flowNodeSession = true, flowNodeRole = role)
      assertEquals(
        find(face, NodeReportToolDef.Name).getOrElse(fail("节点面丢了 node_report")),
        baseOf(NodeReportToolDef.Name),
        s"role=$role 拿到了分化面（未登记形态必须落基础面）"
      )
  }

  // ============================================================
  // ③ 试点读数：node_report 逐角色
  // ============================================================

  test("③ 试点: node_report 逐角色变体 + 旁支面缺席") {
    val taskNr = find(taskNodeFace, NodeReportToolDef.Name).getOrElse(fail("task 节点面丢了 node_report"))
    val verNr = find(verifierNodeFace, NodeReportToolDef.Name).getOrElse(fail("verifier 节点面丢了 node_report"))
    assertEquals(taskNr.description, NodeReportToolDef.descriptionTask, "task 节点未拿到 task 变体")
    assertEquals(verNr.description, NodeReportToolDef.descriptionVerifier, "verifier 节点未拿到 verifier 变体")
    // 分化面**缺席判据**（变异①：去掉变体选择 ⇒ 目标角色拿到并集面 ⇒ 这两条必红）
    assert(!taskNr.description.contains("(2) role=verifier"), "task 变体仍含 verifier 段（分化面缺失）")
    assert(!verNr.description.contains("(1) role=task"), "verifier 变体仍含 task 段（分化面缺失）")
    // 第三条读数：dispatcher 面**不含** node_report（成员资格由 flowNodeSession 单点决定，未受影响）
    assert(find(dispatcherFace, NodeReportToolDef.Name).isEmpty, "dispatcher 面出现了 node_report（成员资格被本批改动）")
    assert(find(nebulaRootFace, NodeReportToolDef.Name).isEmpty, "Nebula root 面出现了 node_report")
  }

  // ============================================================
  // ④ Q5 读数：Mail 地址面逐角色
  // ============================================================

  test("④ Q5: Mail 地址面逐角色 + schema 逐字节不变") {
    val rootMail = find(nebulaRootFace, "Mail").getOrElse(fail("Nebula 面丢了 Mail"))
    val dispMail = find(dispatcherFace, "Mail").getOrElse(fail("dispatcher 面丢了 Mail"))
    val taskMail = find(taskNodeFace, "Mail")
    assertEquals(rootMail.description, MailTool.descriptionRoot, "root 未拿到地址面变体")
    assertEquals(dispMail.description, MailTool.descriptionDispatcher, "dispatcher 未拿到地址面变体")
    assertEquals(rootMail.inputSchema, baseOf("Mail").inputSchema, "root 变体改了 inputSchema（Q5 判据）")
    assertEquals(dispMail.inputSchema, baseOf("Mail").inputSchema, "dispatcher 变体改了 inputSchema（Q5 判据）")
    // 节点面无 Mail（成员资格不受本批影响）——登记为读数
    assert(taskMail.isEmpty, "节点面出现 Mail（成员资格被本批改动）")
    // 分化面缺席判据（变异①）
    assert(!rootMail.description.contains("**Project dispatcher**"), "root 地址面仍含 dispatcher 段")
    assert(!rootMail.description.contains("**Team context (legacy)**"), "root 地址面仍含 team 段")
    assert(!dispMail.description.contains("**Nebula (root)**"), "dispatcher 地址面仍含 root 段")
    assert(!dispMail.description.contains("**Team context (legacy)**"), "dispatcher 地址面仍含 team 段")
    // 参数级 address description 属于 inputSchema ⇒ 本批按 Q5 口径冻结（登记在交付说明）
    assert(
      rootMail
        .inputSchema("properties")
        .flatMap(_.asObject)
        .flatMap(_.apply("address"))
        .exists(_.noSpaces.contains("Project dispatcher")),
      "参数级 address description 被改动了 —— Q5 口径 = inputSchema 逐字节不变（若作者要放开须先裁）"
    )
  }

  // ============================================================
  // ⑤ 变异钉子
  // ============================================================

  test("⑤② 默认分支 fail-closed: 未知/未登记形态（含 None 身份）一律基础面") {
    // 定义期：ToolFaceIdentity.Base 是「未知形态」的规范代表
    for td <- ToolRegistry.ALL_TOOLS do
      val v = AgentCore.schemaVariantFor(td, AgentCore.ToolFaceIdentity.Base)
      assertEquals(v, td, s"基础身份下 ${td.name} 仍是分化面（默认分支被改成变体）")
    // 未登记 agent 名 depth=0（形如未来新增的根会话）
    val unknownRoot = CoreProbe.face(defNamed("some-future-agent"), depth = 0)
    for td <- unknownRoot do assertEquals(td, baseOf(td.name), s"未登记根会话 ${td.name} 拿到分化面")
    // 身份装配面：None/非法 nodeRole ⇒ None（不猜缺省即 task）
    assertEquals(AgentCore.toolFaceIdentity(defNamed("general"), 1, None, false).nodeRole, None)
    assertEquals(AgentCore.toolFaceIdentity(defNamed("general"), 1, Some(""), false).nodeRole, None)
    assertEquals(AgentCore.toolFaceIdentity(defNamed("general"), 1, Some("nope"), false).nodeRole, None)
    // 归一（大小写宽容）走 NodeRoles 单点 ⇒ 合法值归一后仍是白名单值
    assertEquals(AgentCore.toolFaceIdentity(defNamed("general"), 1, Some("VERIFIER"), false).nodeRole, Some("verifier"))
    assertEquals(
      AgentCore.toolFaceIdentity(defNamed("general"), 1, Some(" Verifier "), false).nodeRole,
      Some("verifier")
    )
  }

  test("⑤③ 身份维度完整: depth 分量 + 角色维度缺失即误取变体（判红面）") {
    // depth 分量：Nebula 名 + depth=1（节点会话）绝不拿 root 变体
    val nebulaNamedNode =
      CoreProbe.face(defNamed("Nebula"), depth = 1, flowNodeSession = true, flowNodeRole = Some("task"))
    val mail = find(nebulaNamedNode, "Mail")
    assert(mail.forall(_.description == MailTool.descriptionBase), "depth=1 的 Nebula 节点会话拿到了 root 地址面（谓词丢 depth）")
    assertEquals(
      find(nebulaNamedNode, NodeReportToolDef.Name).map(_.description),
      Some(NodeReportToolDef.descriptionTask)
    )

    // dispatcher 维度：只有 isDispatcher 才拿 dispatcher 面
    assert(
      CoreProbe.face(defNamed("project-dispatcher")).forall(td => td.description == baseOf(td.name).description),
      "未标 isDispatcher 的分发器名会话拿到了 dispatcher 面"
    )

    // 角色维度：角色互斥（task 面不得含 verifier 段，反之亦然）+ 大小写宽容
    val upper = CoreProbe.face(defNamed("general"), depth = 1, flowNodeSession = true, flowNodeRole = Some("Verifier"))
    assertEquals(
      find(upper, NodeReportToolDef.Name).map(_.description),
      Some(NodeReportToolDef.descriptionVerifier),
      "大写角色名未归一（判据丢归一维度）"
    )
  }

  // ============================================================
  // ⑥ 措辞来源纪律：分化变体零新造字（纯删段可逆）
  // ============================================================

  test("⑥ 措辞来源: Mail 分化变体 = 基础删去他角色段（逐字节可逆）") {
    val base = MailTool.descriptionBase
    for seg <- List(
        MailTool.AddressFaceHeader,
        MailTool.AddressFaceRoot,
        MailTool.AddressFaceDispatcher,
        MailTool.AddressFaceTeam,
        MailTool.AddressFaceClosing
      )
    do assert(base.contains(seg), s"地址面段常量不是基础文本的逐字子串（含新造字）：${seg.take(60)}…")
    assertEquals(
      base.replace(MailTool.AddressFaceDispatcher + MailTool.AddressFaceTeam, ""),
      MailTool.descriptionRoot,
      "root 变体 ≠ 基础删去另两段（出现了改写/新造字）"
    )
    // dispatcher 变体：基础里两段**不相邻**（Nebula 段在题首、team 段在题尾）⇒ 两次定点删除
    assertEquals(
      base.replace(MailTool.AddressFaceRoot, "").replace(MailTool.AddressFaceTeam, ""),
      MailTool.descriptionDispatcher,
      "dispatcher 变体 ≠ 基础删去另两段（出现了改写/新造字）"
    )
  }

  test("⑥ 措辞来源: node_report 分化变体 = 基础删去另一角色行（行级、零新造字）") {
    val lines = NodeReportToolDef.descriptionBase.split("\n", -1).toList
    val verifierLine = lines.find(_.startsWith("(2) role=verifier")).getOrElse(fail("基础文本里没有 verifier 段"))
    val taskLine = lines.find(_.startsWith("(1) role=task")).getOrElse(fail("基础文本里没有 task 段"))
    assertEquals(lines.size, 5, "基础 description 行数变化（本 spec 的行级判据前提）")
    assertEquals(NodeReportToolDef.descriptionTask, NodeReportToolDef.descriptionBase.replace(verifierLine + "\n", ""))
    assertEquals(NodeReportToolDef.descriptionVerifier, NodeReportToolDef.descriptionBase.replace(taskLine + "\n", ""))
    // 变体的每一行都逐字节来自基础（保序子序列）
    def isOrderedSubseq(sub: List[String], all: List[String]): Boolean =
      if sub.isEmpty then true
      else
        val i = all.indexOf(sub.head)
        i >= 0 && isOrderedSubseq(sub.tail, all.drop(i + 1))
    for v <- List(NodeReportToolDef.descriptionTask, NodeReportToolDef.descriptionVerifier) do
      assert(isOrderedSubseq(v.split("\n", -1).toList, lines), "变体出现基础里没有的行（新造字）")
  }
end ToolFaceVariantSchemaSpec
