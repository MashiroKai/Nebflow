package nebflow.agent

import munit.FunSuite
import nebflow.core.tools.{AskUserQuestionTool, MailTool, NodeReportToolDef, ToolRegistry}
import nebflow.shared.ToolDefinition

/**
 * Q4+Q5 批 **读数矩阵**（验收 ③/④/⑦a 的证据生成器）：把「逐身份的
 * tools 载荷 sha256 + 字节数（改前/改后）」与「目标工具逐角色 description
 * sha256 + 变体归属」打印到 stdout，供 `sbt testOnly` 输出直接抄进证据目录。
 *
 * **改前（before）的模拟口径**（逐字声明，避免误读为第二份实现）：
 *   改前面 = `ToolRegistry.ALL_TOOLS` 按该身份的 allowedSet 过滤，**只**对
 *   AskUserQuestion 施加既有 root 变体（AskUser 批已合并的历史行为），
 *   Mail / node_report 一律并集面。本模拟**只**用于生成改前后对照数字，
 *   不参与任何产品判据（产品路径只有 `buildToolList` 一条）。
 */
class ToolFaceVariantReadingsSpec extends FunSuite:

  private object CoreProbe extends AgentCore:

    def allowed(
      defn: AgentDef,
      depth: Int,
      flowNodeSession: Boolean,
      projectBoardSession: Boolean
    ): Set[String] =
      buildAllowedToolSet(
        defn,
        depth,
        isSubTaskWorker = false,
        isFlowNode = false,
        isTeamLead = false,
        userFacingNode = false,
        guardrailsOn = false,
        projectBoardSession = projectBoardSession,
        flowNodeSession = flowNodeSession
      )

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

  private def payload(tds: List[ToolDefinition]): String =
    tds
      .map(t => s"""{"name":${t.name},"description":${t.description},"input_schema":${t.inputSchema}}""")
      .mkString("[", ",", "]")

  /** 改前模拟器（见类注释；**只**用于对照数字）。 */
  private def beforeFace(
    defn: AgentDef,
    depth: Int,
    flowNodeSession: Boolean,
    projectBoardSession: Boolean,
    root: Boolean
  ): List[ToolDefinition] =
    val allowed = CoreProbe.allowed(defn, depth, flowNodeSession, projectBoardSession)
    ToolRegistry.ALL_TOOLS.flatMap { td =>
      if !allowed.contains(td.name) then None
      else if root && td.name == AskUserQuestionTool.Name then Some(AskUserQuestionTool.rootVariant(td))
      else Some(td)
    }

  private final case class Cross(
    label: String,
    defn: AgentDef,
    depth: Int,
    flowNodeSession: Boolean,
    projectBoardSession: Boolean,
    isDispatcher: Boolean,
    role: Option[String],
    root: Boolean
  )

  private val crosses: List[Cross] = List(
    Cross("Nebula-root", defNamed("Nebula"), 0, false, false, false, None, root = true),
    Cross("project-dispatcher", defNamed("project-dispatcher"), 0, false, true, true, None, root = false),
    Cross("node(role=task)", defNamed("general"), 1, true, true, false, Some("task"), root = false),
    Cross("node(role=verifier)", defNamed("general"), 1, true, true, false, Some("verifier"), root = false),
    Cross("node(role=<none>)", defNamed("general"), 1, true, true, false, None, root = false),
    Cross("kernel(depth=1)", defNamed("kernel"), 1, false, false, false, None, root = false),
    Cross("team-member(Coder)", defNamed("Coder", category = "team"), 0, false, false, false, None, root = false)
  )

  test("③④⑦a 读数矩阵: 逐身份 tools 载荷 / 目标工具 description / schema 全量（改前 vs 改后）") {
    val p = (s: String) => println(s"[Q4Q5-READINGS] $s")

    p("== 逐身份横断面 ==")
    for c <- crosses do
      val after = CoreProbe.face(c.defn, c.depth, c.flowNodeSession, c.role, c.isDispatcher, c.projectBoardSession)
      val before = beforeFace(c.defn, c.depth, c.flowNodeSession, c.projectBoardSession, c.root)
      val bBytes = payload(before).getBytes("UTF-8").length
      val aBytes = payload(after).getBytes("UTF-8").length
      val bSchema = sha(before.map(t => s"${t.name}:${t.inputSchema}").mkString("|"))
      val aSchema = sha(after.map(t => s"${t.name}:${t.inputSchema}").mkString("|"))
      p(s"FACE ${c.label}: tools=${after.map(_.name).mkString(",")}")
      p(s"PAYLOAD ${c.label}: before_bytes=$bBytes after_bytes=$aBytes delta_bytes=${aBytes - bBytes}")
      p(s"PAYLOAD-SHA256 ${c.label}: before=${sha(payload(before))} after=${sha(payload(after))}")
      p(s"SCHEMA-SHA256 ${c.label}: before=$bSchema after=$aSchema identical=${bSchema == aSchema}")
      for t <- after if t.name == "Mail" || t.name == NodeReportToolDef.Name do
        val b = before.find(_.name == t.name).getOrElse(fail("改前面丢了目标工具")).description
        val variant =
          if t.name == "Mail" then
            if t.description == MailTool.descriptionRoot then "Mail.descriptionNebulaRoot"
            else if t.description == MailTool.descriptionDispatcher then "Mail.descriptionDispatcher"
            else "Mail.descriptionBase(并集面)"
          else if t.description == NodeReportToolDef.descriptionTask then "NodeReport.descriptionTask"
          else if t.description == NodeReportToolDef.descriptionVerifier then "NodeReport.descriptionVerifier"
          else "NodeReport.descriptionBase(并集面)"
        p(
          s"TOOL ${c.label} ${t.name}: variant=$variant " +
            s"before_desc_sha256=${sha(b)} after_desc_sha256=${sha(t.description)} " +
            s"before_desc_bytes=${b.getBytes("UTF-8").length} after_desc_bytes=${t.description.getBytes("UTF-8").length}"
        )
      end for
    end for

    p("== 基础变体（= 现状）与注册表的字节一致性 ==")
    p(
      s"Mail.descriptionBase   sha256=${sha(MailTool.descriptionBase)} bytes=${MailTool.descriptionBase.getBytes("UTF-8").length}"
    )
    p(
      s"NodeReport.descriptionBase sha256=${sha(NodeReportToolDef.descriptionBase)} bytes=${NodeReportToolDef.descriptionBase.getBytes("UTF-8").length}"
    )
    p(
      s"Mail.descriptionNebulaRoot sha256=${sha(MailTool.descriptionRoot)} bytes=${MailTool.descriptionRoot.getBytes("UTF-8").length}"
    )
    p(
      s"Mail.descriptionDispatcher sha256=${sha(MailTool.descriptionDispatcher)} bytes=${MailTool.descriptionDispatcher.getBytes("UTF-8").length}"
    )
    p(
      s"NodeReport.descriptionTask sha256=${sha(NodeReportToolDef.descriptionTask)} bytes=${NodeReportToolDef.descriptionTask.getBytes("UTF-8").length}"
    )
    p(
      s"NodeReport.descriptionVerifier sha256=${sha(NodeReportToolDef.descriptionVerifier)} bytes=${NodeReportToolDef.descriptionVerifier.getBytes("UTF-8").length}"
    )
    p(
      s"registry Mail desc sha256=${sha(ToolRegistry.ALL_TOOLS.find(_.name == "Mail").map(_.description).getOrElse(""))}"
    )
    p(
      s"registry NodeReport desc sha256=${sha(ToolRegistry.ALL_TOOLS.find(_.name == NodeReportToolDef.Name).map(_.description).getOrElse(""))}"
    )

    // 结构性结论的断言（读数矩阵本身的自洽性）
    assertEquals(
      MailTool.descriptionBase,
      ToolRegistry.ALL_TOOLS.find(_.name == "Mail").map(_.description).getOrElse("")
    )
    assert(payload(CoreProbe.face(defNamed("Nebula"), 0)).getBytes("UTF-8").length > 0)
  }
end ToolFaceVariantReadingsSpec
