package nebflow.core

import io.circe.syntax.*
import io.circe.{Json, JsonObject}

import java.util.concurrent.ConcurrentHashMap

// ============================================================
// P0-1 / P-M1 · MCP & ScriptTool per-call approval gate
//
// 设计真源（逐节读，非摘要）：
//   · `.nebflow/Spec/20260907_sandbox-mcp-spec.md` §2 全节
//     （§2.1 定级 / §2.2 策略表 / §2.3 插点 / §2.4 卡面 / §2.5 scope=session / §2.6 覆盖面）
//   · `.nebflow/Spec/20260907_sandbox-cu-bu-roadmap.md` §2.1 交叉表 A1–A17 + §2.2 统一 payload 附表
//   · `.nebflow/Spec/20260907_sandbox-capability-integration.md` §2.6 P-M1 要点
//
// 本批机制裁点（口径，非既成事实）：S3 = a（新 kind `mcpPermission`）/ S2 = 启用（scope=session 随批）。
//
// WHY 独立判定而不并入 `ToolReversibility.isReversible`（spec §2.2 约束 + 验收 A1-8）：
//   内置工具的判定链（Write/Edit/Bash/Curl × 三档）必须**零回归**，故本门挂在
//   `AgentCore.permissionDecision` 中 isReversible **之前**，且仅对 MCP / ScriptTool
//   两个面生效 —— 内置工具的判定路径一行不改。
//
// 缺省方向（spec §2.2 + 基线 §2.6-P-M1 要点 2，fail-safe）：
//   MCP / ScriptTool 工具**未声明 = 缺省 confirm** —— 这是对
//   `permissions.scala` 头部注释「New/unknown tools (ScriptTool, MCP, etc.) default to
//   auto-approved in all modes」(现读 :23-24) 的反转，且**只对这两类工具生效**。
// ============================================================

/** 操作风险分级（spec §2.1）。判据 = 动作损害面 × 可逆性 × 目标边界。 */
enum RiskTier:
  case L0, L1, L2, L3

  def label: String = this match
    case L0 => "L0"
    case L1 => "L1"
    case L2 => "L2"
    case L3 => "L3"

  /** spec §2.3：dangerLevel 映射对齐 askPermission payload 既有 3/2/1 语义
    * （`AgentCore.askUserPermission` 的 dangerLevel 字段）；**L0 不出卡**。
    */
  def dangerLevel: Int = this match
    case L0 => 0
    case L1 => 1
    case L2 => 2
    case L3 => 3

/** 声明档（spec §2.1-2 / §2.2 列标题）；wire 值域 = 卡面 `declared` 字段。 */
enum Declared:
  case DeclaredAuto, DeclaredConfirm, Undeclared, Unclassified

  def wire: String = this match
    case DeclaredAuto    => "declared-auto"
    case DeclaredConfirm => "declared-confirm"
    case Undeclared      => "undeclared"
    case Unclassified    => "unclassified"

  /** §2.2 合成规则用：只有「声明 auto」免审（L3 红线除外）。 */
  def isAuto: Boolean = this == DeclaredAuto

/** 执行形态（spec §2.3 的 form 四值 / §4.1 三形态 + 降级裸进程）。 */
enum ExecForm:
  case Container, HostExecutor, SandboxProcess, BareProcess

  def wire: String = this match
    case Container     => "container"
    case HostExecutor  => "host-executor"
    case SandboxProcess => "sandbox-process"
    case BareProcess   => "bare-process"

  /** 「宿主执行」面（spec §2.1-1）：host-executor / 降级裸进程 = 宿主执行；沙箱进程同为宿主
    * 进程树内（seatbelt 不隔离 spawn，spec §4.2 诚实陈述），故同属宿主面；容器面为 false。
    */
  def isHostSurface: Boolean = this != Container

object ExecForm:
  // 现读读数（本批）：全仓 `grep -rn 'host-executor|ExecForm' src/main/scala/` = **0 命中**
  // ⇒ 现读**无形态来源**。按任务书 §九 口径：缺省 host-executor + 单列
  // 「form 来源待 P1 容器面收口」。装配点留在本处，P1 容器面落地时在此接真值
  // （届时改 `current` 的唯一写入点即可，判定逻辑零改动）。
  @volatile private var current: ExecForm = ExecForm.HostExecutor

  def get: ExecForm = current

  /** 测试 / P1 容器面装配点。 */
  def install(f: ExecForm): Unit = current = f

  def reset(): Unit = current = ExecForm.HostExecutor

/** 工具面（spec §2.6：本门一次覆盖两类 —— MCP + ScriptTool）。 */
enum ToolSurface:
  case Mcp, ScriptTool

  def wire: String = this match
    case Mcp        => "mcp"
    case ScriptTool => "script-tool"

/**
 * 声明来源接缝（spec §2.1-2 / 基线 §2.3-3）——**本批唯一允许的接缝**。
 *
 * 真值面 = `org.nebflow/sandbox.json` extensions 档表，其解析属 **P0-4 对象面**
 * （候 P0-6 schema + 作者拍板）⇒ 本批**不实现 manifest 解析**，缺省实现一律
 * [[DeclarationSource.alwaysUndeclared]]；P0-4 落地在此换实现。
 *
 * A1-2 的「声明 auto / 声明 confirm」对照经本接缝注入达成（单测级）；**端到端
 * manifest 消费 = P0-4 面**，两者在报告里显式区分。
 */
trait DeclarationSource:
  /** 返回 None = 该 (面, server, 工具) 未声明（⇒ 缺省 confirm）。 */
  def lookup(surface: ToolSurface, serverId: String, tool: String): Option[Declared]

object DeclarationSource:
  /** P0-1 缺省实现（本批唯一实现）：一律未声明。 */
  val alwaysUndeclared: DeclarationSource = new DeclarationSource:
    def lookup(surface: ToolSurface, serverId: String, tool: String): Option[Declared] = None

  @volatile private var current: DeclarationSource = alwaysUndeclared

  def get: DeclarationSource = current

  /** 装配点：P0-4（真档表）/ 测试（A1-2 对照）在此注入。 */
  def install(src: DeclarationSource): Unit = current = src

  def reset(): Unit = current = alwaysUndeclared

/**
 * 工具标识解析结果（roadmap §2.2 附表「serverId / plugin / tool」三元组）。
 *
 * 源码口径（已核）：MCP 工具注册名 = `mcp__<serverId>__<tool>`（`McpClient.scala:120`）；
 * serverId 两种来源：
 *   · 插件 MCP `plugin_<plugin>_<server>`（现读 `PluginMcpManager.scala:17`）
 *   · agent 级 MCP `agent-<agentName>-<serverName>`（现读 `AgentMcpLoader.scala:29-31`）
 */
final case class McpToolRef(
  fullName: String,
  surface: ToolSurface,
  serverId: String,
  plugin: Option[String],
  tool: String
)

object McpToolRef:

  private val McpPrefix = "mcp__"

  /** 解析 MCP 注册名。非 `mcp__` 名 / 缺 server 或缺工具名 ⇒ None（不猜）。
    *
    * 分隔判据：前缀之后**第一个** `__` 为 serverId↔tool 分界（serverId 自身只可能含
    * 单下划线：`plugin_<plugin>_<server>` / `agent-<agent>-<server>`）——与
    * `summarizeToolCall`（`handlers.scala:33-36`）的 `split("__")` 口径同族。
    *
    * plugin 解析：`plugin_<plugin>_<server>` 以**最后一个** `_` 为 plugin↔server 分界
    * （plugin 名含 `-` 不含 `_` 时为精确解；含 `_` 的 plugin 名属下界歧义，落
    * `pluginAmbiguous` 注释面，不静默猜错）。
    */
  def parse(fullName: String): Option[McpToolRef] =
    if !fullName.startsWith(McpPrefix) then None
    else
      val rest = fullName.substring(McpPrefix.length)
      val idx = rest.indexOf("__")
      if idx <= 0 then None
      else
        val serverId = rest.substring(0, idx)
        val tool = rest.substring(idx + 2)
        if tool.isEmpty then None
        else
          val plugin =
            if serverId.startsWith("plugin_") then
              val tail = serverId.substring("plugin_".length)
              val last = tail.lastIndexOf('_')
              if last > 0 then Some(tail.substring(0, last)) else None
            else None
          Some(McpToolRef(fullName, ToolSurface.Mcp, serverId, plugin, tool))

/** ScriptTool 面的解析（**无注册名模式** —— 见下）。 */
object ScriptToolRef:

  /** spec §6 #8「ScriptTool 注册名模式」本批现读核实结论（**读数，非臆造**）：
    *   · `ScriptTool.scala:16`：`val name = config.name` —— 名字 = `tool.json` 的
    *     `config.name` **自由文本**，无前缀、无命名空间（`ToolLoader.reload()` 原样
    *     `registerTool(t)`，现读 `ToolLoader.scala:49-51`）。
    *   · ⇒ **源码不存在可用的「注册名模式」**。名字模式核不到 ⇒ 按任务书 §九
    *     「核实不到 ⇒ 单列，禁臆造」处理：识别改走**注册表身份**（下表）。
    *   · 注册表身份判据（本批采用）= `ToolRegistry` 中该名字对应的实例是
    *     `nebflow.core.tools.ScriptTool`（`ToolRegistry.isExternalTool`）。
    *     「外部工具名模式」开放项见报告（候选：`ToolLoader` 暴露 external 名集，
    *     或 `tool.json` 增 `namePattern` 字段 —— 均需作者裁）。
    */
  def of(fullName: String): Option[McpToolRef] =
    if fullName.startsWith("mcp__") then None
    else if nebflow.core.tools.ToolRegistry.isExternalTool(fullName) then
      Some(McpToolRef(fullName, ToolSurface.ScriptTool, serverId = fullName, plugin = None, tool = fullName))
    else None

/**
 * 会话级放行记忆（P0-2，spec §2.5）。
 *
 * 键 = `(sourceSession, serverId, tool)`；**只内存、不落盘**；会话终态由
 * `AgentActor` 的 root-session 停链（`onSessionEnd` 同点）清空 ⇒ **零跨会话残留**。
 * P0-2 键按「会话作用域」隔离：另一个会话（新会话）不继承（A1-6）。
 *
 * 🔴 L3 不适用（spec §6 #2 建议口径「启用（L0-L2；不落盘）」+ §2.2「L3 全表唯一恒审行」
 * 宿主红线不可因会话放行免审）—— 判定侧强制（见 [[McpToolGate.decide]]）。
 */
object SessionApprovals:
  private val map = new ConcurrentHashMap[String, java.util.Set[String]]()

  private def key(serverId: String, tool: String): String = s"$serverId|$tool"

  private def slot(sessionId: String): java.util.Set[String] =
    map.computeIfAbsent(sessionId, _ => java.util.concurrent.ConcurrentHashMap.newKeySet[String]())

  def remember(sessionId: String, serverId: String, tool: String): Unit =
    if sessionId.nonEmpty then
      val s = slot(sessionId)
      s.add(key(serverId, tool))

  def contains(sessionId: String, serverId: String, tool: String): Boolean =
    sessionId.nonEmpty && Option(map.get(sessionId)).exists(_.contains(key(serverId, tool)))

  /** 会话终态清空（零跨会话残留）。幂等。 */
  def clear(sessionId: String): Unit =
    if sessionId.nonEmpty then map.remove(sessionId)

  /** 测试观测面：当前会话数。 */
  def sessionCount: Int = map.size()

  /** 测试观测面（全清）。 */
  def reset(): Unit = map.clear()

/**
 * 卡面参数摘要的凭据遮蔽（roadmap §2.2 附表「参数摘要 · 经凭据 redact」；规则本批统一定，统一总纲 N3/R7）。
 *
 * 规则 = **字段名启发式**：键名（大小写不敏感、含子串）命中
 * `password / token / secret / credential / key` 类 ⇒ 值整体遮蔽为 `***`（递归到嵌套
 * 对象/数组）。**不发送原始 `input`** —— MCP 卡面只带遮蔽后的摘要（防凭据上卡面 +
 * 防卡面爆炸；对照：内置 `askPermission` 卡带完整 `input`，本门不复用该形状）。
 */
object GateRedact:
  private val SecretKeyHints = List("password", "token", "secret", "credential", "key")
  private val MaxValueChars = 120
  private val MaxSummaryChars = 600

  def isSecretKey(k: String): Boolean =
    val l = k.toLowerCase
    SecretKeyHints.exists(l.contains)

  def redact(json: Json): Json =
    json.fold(
      jsonNull = Json.Null,
      jsonBoolean = Json.fromBoolean,
      jsonNumber = Json.fromJsonNumber,
      jsonString = Json.fromString,
      jsonArray = arr => Json.arr(arr.map(redact)*),
      jsonObject = obj =>
        Json.fromJsonObject(JsonObject.fromIterable(obj.toIterable.map { case (k, v) =>
          k -> (if isSecretKey(k) then Json.fromString("***") else redact(v))
        }))
    )

  /** 截断防卡面爆炸 + 凭据遮蔽。 */
  def summarize(input: JsonObject): String =
    if input.isEmpty then "(no parameters)"
    else
      val red = redact(Json.fromJsonObject(input)).asObject.getOrElse(JsonObject.empty)
      val parts = red.toIterable.map { case (k, v) =>
        val raw = v.noSpaces
        val shown =
          if raw.length > MaxValueChars then raw.take(MaxValueChars) + s"...(+${raw.length - MaxValueChars})"
          else raw
        s"$k=$shown"
      }.toList
      val joined = parts.mkString(", ")
      if joined.length > MaxSummaryChars then
        joined.take(MaxSummaryChars) + s"...(truncated, +${joined.length - MaxSummaryChars} chars)"
      else joined

/** 判定结果（卡面 + 审计的唯一来源；纯数据，可单测）。 */
final case class McpGateOutcome(
  ref: McpToolRef,
  tier: RiskTier,
  declared: Declared,
  /** 定级来源标注（可溯源）：form-correction | declared | heuristic:<word> | unclassified */
  tierSource: String,
  form: ExecForm,
  sessionApproved: Boolean,
  needApproval: Boolean,
  inputSummary: String
):
  def hostBanner: Boolean = form.isHostSurface && tier == RiskTier.L3

  /** spec §2.4：**L3 卡不渲染升级选项**（宿主红线不可因升档免审）。 */
  def allowUpgrade: Boolean = tier != RiskTier.L3

  /** 判定折成既有 `PermissionDecision` 的二值语义（Allow / Ask）。 */
  def allowed: Boolean = !needApproval

/**
 * McpToolGate —— P-M1 审批门本体（spec §2.3）。
 *
 * 插点：`AgentCore.permissionDecision`（现读 `AgentCore.scala:1484-1491`）的
 * `ToolReversibility.isReversible` **第三分支之前**；仅 MCP / ScriptTool 面改走本门，
 * 内置工具路径逐字不变（A1-8）。
 *
 * 纯函数（无 IO、无全局副作用除两处显式装配点）—— 便于逐条红验与变异臂复算。
 */
object McpToolGate:

  // ---- §2.1-3 启发式词表（来源③；**仅参考**：落日志与卡面展示，非权限裁决） ----
  // 出处 = spec §2.1 表格「工具名模式参考（启发式词表）」列，逐词照抄。
  private val L0Words = List("read", "get", "list", "search", "query", "describe", "health", "dom", "content")
  private val L1Words = List("write", "create", "update", "delete", "tabs", "close")
  private val L2Words = List("navigate", "fetch", "send", "push", "commit", "publish")
  private val L3Words = List("act", "click", "type", "key", "exec", "spawn", "screenshot", "ax")

  /** 工具名 → 词元（按非字母数字切分；camelCase 整词由 [[wordHits]] 的前后缀判据覆盖）。 */
  private def tokens(tool: String): List[String] =
    tool.toLowerCase.split("[^a-z0-9]+").toList.filter(_.nonEmpty)

  /** 命中判据：词元 == 词 ∨ 词元以词开头 ∨ 词元以词结尾（覆盖 camelCase / snake_case 两形态）。
    * 这是**启发式**，宁可多审不可漏审（fail-safe 方向）。 */
  private def wordHits(tool: String, words: List[String]): Option[String] =
    val ts = tokens(tool)
    words.find(w => ts.exists(t => t == w || t.startsWith(w) || t.endsWith(w)))

  /** 定级（spec §2.1 四级优先：① 形态修正 → ③ 启发式；② 包声明只改「档」，不改「级」）。 */
  private def classify(tool: String, form: ExecForm): (RiskTier, String) =
    val l3 = wordHits(tool, L3Words)
    // ① 形态修正（**先于一切**）：L3 仅在 form = 宿主执行时成立；容器形态下宿主面动作
    //    结构性不可达（不经审批面）⇒ L3 判定被结构性关闭，改由其余词表定级。
    if form.isHostSurface then
      l3 match
        case Some(w) => (RiskTier.L3, s"form-correction+heuristic:$w")
        case None =>
          wordHits(tool, L2Words) match
            case Some(w) => (RiskTier.L2, s"heuristic:$w")
            case None =>
              wordHits(tool, L1Words) match
                case Some(w) => (RiskTier.L1, s"heuristic:$w")
                case None =>
                  wordHits(tool, L0Words) match
                    case Some(w) => (RiskTier.L0, s"heuristic:$w")
                    case None    => (RiskTier.L0, "unclassified")
    else
      // 容器形态：L3 词表结构性失效（宿主面不可达）⇒ 落到其余词表；全不匹配 = unclassified。
      if l3.isDefined then
        wordHits(tool, L2Words) match
          case Some(w) => (RiskTier.L2, s"form-correction(container)+heuristic:$w")
          case None =>
            wordHits(tool, L1Words) match
              case Some(w) => (RiskTier.L1, s"form-correction(container)+heuristic:$w")
              case None =>
                wordHits(tool, L0Words) match
                  case Some(w) => (RiskTier.L0, s"form-correction(container)+heuristic:$w")
                  case None    => (RiskTier.L0, "form-correction(container)+unclassified")
      else
        wordHits(tool, L2Words) match
          case Some(w) => (RiskTier.L2, s"heuristic:$w")
          case None =>
            wordHits(tool, L1Words) match
              case Some(w) => (RiskTier.L1, s"heuristic:$w")
              case None =>
                wordHits(tool, L0Words) match
                  case Some(w) => (RiskTier.L0, s"heuristic:$w")
                  case None    => (RiskTier.L0, "unclassified")

  /** §2.2 合成规则（原文）：`needApproval = L3 ? true : declared==auto ? false : mode==AutoAll ? false : true`。 */
  def needApproval(tier: RiskTier, declared: Declared, mode: SafetyMode): Boolean =
    if tier == RiskTier.L3 then true
    else if declared.isAuto then false
    else mode != SafetyMode.AutoAll

  /** 面判定：MCP 名模式 或 ScriptTool 注册表身份。 */
  def surfaceOf(fullName: String): Option[McpToolRef] =
    McpToolRef.parse(fullName).orElse(ScriptToolRef.of(fullName))

  /** 判定本体（插点调用的单入口）。 */
  def decide(
    ref: McpToolRef,
    input: JsonObject,
    mode: SafetyMode,
    sessionId: String,
    form: ExecForm = ExecForm.get,
    source: DeclarationSource = DeclarationSource.get,
    approvals: SessionApprovals.type = SessionApprovals
  ): McpGateOutcome =
    val (rawTier, tierSource) = classify(ref.tool, form)
    val declared = source.lookup(ref.surface, ref.serverId, ref.tool) match
      case Some(d) => d
      case None    => if tierSource.contains("unclassified") then Declared.Unclassified else Declared.Undeclared
    // spec §6 #2 建议口径：会话放行只对 L0-L2 生效；L3 恒审（宿主红线）。
    val sessionApproved = rawTier != RiskTier.L3 && approvals.contains(sessionId, ref.serverId, ref.tool)
    val need = if sessionApproved then false else needApproval(rawTier, declared, mode)
    McpGateOutcome(
      ref = ref,
      tier = rawTier,
      declared = declared,
      tierSource = tierSource,
      form = form,
      sessionApproved = sessionApproved,
      needApproval = need,
      inputSummary = GateRedact.summarize(input)
    )

  /**
   * 卡面 payload（roadmap §2.2 统一 payload schema 附表；spec §2.4）。
   *
   * 基础字段 `requestId / sourceAgent / sourceSession / sessionId` 由挂点补齐
   * （`InteractionHub` 单点，与 askPermission / askUser 同口径）。
   *
   * 🔴 只带**遮蔽+截断后的** `inputSummary`，**不带**原始 `input`（防凭据上卡面）。
   */
  def cardPayload(o: McpGateOutcome, safetyMode: String): Json =
    Json.obj(
      "type" -> "mcpPermission".asJson,
      "toolName" -> o.ref.fullName.asJson,
      "serverId" -> o.ref.serverId.asJson,
      "plugin" -> o.ref.plugin.fold(Json.Null)(p => Json.fromString(p)),
      "tool" -> o.ref.tool.asJson,
      "surface" -> o.ref.surface.wire.asJson,
      "inputSummary" -> o.inputSummary.asJson,
      "summary" -> o.inputSummary.asJson,
      "riskTier" -> o.tier.label.asJson,
      "declared" -> o.declared.wire.asJson,
      "tierSource" -> o.tierSource.asJson,
      "execForm" -> o.form.wire.asJson,
      "hostBanner" -> o.hostBanner.asJson,
      "allowUpgrade" -> o.allowUpgrade.asJson,
      "dangerLevel" -> o.tier.dangerLevel.asJson,
      "safetyMode" -> safetyMode.asJson
    )

  /**
   * 决策审计（spec §2.3 末条）：每次 Allow / Ask 落结构化审计面，含
   * tier / declared / 来源。审计面 = `nebflow.audit`（`InteractionHub` 的
   * ask/answer 审计同族单点，零新存储、零新事件类型）。
   */
  def auditLine(o: McpGateOutcome, mode: SafetyMode, sessionId: String): String =
    val decision = if o.needApproval then "ask" else "allow"
    s"event=mcpGate decision=$decision tool=${o.ref.fullName} surface=${o.ref.surface.wire} " +
      s"serverId=${o.ref.serverId} tool=${o.ref.tool} tier=${o.tier.label} declared=${o.declared.wire} " +
      s"tierSource=${o.tierSource} form=${o.form.wire} safetyMode=${SafetyMode.toString(mode)} " +
      s"sessionApproved=${o.sessionApproved} hostBanner=${o.hostBanner} session=$sessionId"

/**
 * 审批卡答复形状（spec §2.4）。
 *
 * `approved: Boolean` **必需** —— 形状不符**不消费卡**（与 `InteractionHub.answerCompletes`
 * 同构，`InteractionHub.scala:404-409`：形状不符的答复不得吃掉槽位，否则 deferred 永挂）。
 * 可选扩展 `scope: "once"|"session"`、`upgradeMode`（走既有 `PermissionUpgrade.parse`）。
 */
final case class McpPermissionAnswer(
  approved: Boolean,
  scope: Option[String],
  upgradeMode: Option[String]
):
  /** P0-2：仅 `session` 触发会话放行记忆；`once` / 其他值 / 缺省 = 仅本次。 */
  def wantsSessionScope: Boolean = scope.contains("session")

object McpPermissionAnswer:
  def decode(payload: Json): Option[McpPermissionAnswer] =
    payload.hcursor.downField("approved").as[Boolean].toOption.map { approved =>
      McpPermissionAnswer(
        approved = approved,
        scope = payload.hcursor.downField("scope").as[String].toOption,
        upgradeMode = payload.hcursor.downField("upgradeMode").as[String].toOption
      )
    }
