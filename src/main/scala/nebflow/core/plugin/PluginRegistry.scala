package nebflow.core.plugin

import cats.effect.IO
import cats.syntax.all.*
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import nebflow.core.{AtomicJson, NebflowLogger, PathUtil}
import nebflow.llm.McpServerConfig

import java.security.MessageDigest
import scala.collection.mutable
import scala.util.matching.Regex

/**
 * PluginRegistry —— 插件注册表与信任门（阶段 2b §B.2/§B.3，蓝图唯一权威；
 * 20260904 协议符合度批对齐 Agent Plugins 1.0.0 官方 spec 全文）。
 *
 * 目录格式（Agent Plugins 1.0.0，§B.2）：
 * {{{
 * ~/.nebflow/plugins/<name>/
 *   plugin.json            根级 manifest：闭合 schema 十字段（§5.2）——$schema（必填，
 *                          canonical = https://agent-plugins.org/schemas/1.0.0/plugin.schema.json）、
 *                          name（必填，§5.5 命名约束）、version/description/author(object)/
 *                          homepage/repository/license/keywords/extensions
 *   skills/<skill>/SKILL.md  可选——skill id = <plugin>/<skill>；frontmatter 须含
 *                          name+description（agentskills.io，§7.1 不符 → skip+告警）
 *   mcp.json               可选——{"$schema":<canonical mcp>,"mcpServers":{...}}（§7.2.1
 *                          闭合 schema；server entry type ∈ stdio|streamable-http|sse）
 *   org.nebflow/tools.json 可选——nebflow 扩展命名空间：{"tools":["WebSearch",...]}
 * }}}
 *
 * 装载校验（裁定 12 / §B.8-7）：skills/ 与 mcp.json 至少其一，全无 → 拒载+告警；
 * manifest 缺 name / 缺 $schema / 非 canonical $schema / name 违反 §5.5 /
 * 已知字段类型违规 → 拒载（§5.2/§5.3 fatal）；未知字段/未知目录 → 宽容忽略+告警
 * （§B.8-5 前向兼容）。mcp.json 级违规 → MCP 组件整体 invalid（插件继续装载，
 * §6.2）；单 server entry 违规 → 仅该 entry skip+告警（§7.2.2 隔离边界）。
 * 路径围栏（§4.1）：plugin.json/SKILL.md/tools.json/sse 之外被读路径解析符号链接
 * 后必须仍在插件根内，越界 → 拒绝/跳过。
 *
 * 官方身份校验（P0-3 身份脚手架，基线 §2.4；判定实现在 [[OfficialPackages]]）：
 * `nebflow-` 前缀 = 官方保留 namespace —— 该前缀的包**必须在分发内置官方允许列表中且目录
 * 内容 digest 逐字相等**，否则拒载（`OFFICIAL_IMPERSONATION`，登记在 digest 之后、内容面判定
 * 之前）。非保留前缀的包不经本闸。拒载走**同一**装载错误面（WARN / 段尾缺席注记 / 健康摘要
 * `[load-failed]` / `listWithRejected`），不新增第四种错误面。
 *
 * 内容面判定（**无审批批 2026-09-13**，作者令「装了就是信任」）：**在位即信任**
 * （default-allow）+ **点名封禁**（deny-list）。判定顺序（[[PluginRegistry.loadPlugin]]
 * 末尾，勿交换）：① 命中 `nebflow.json → plugins.revoked.<name> = {at, by, reason}`
 * ⇒ `TrustStatus.Blocked`（不进目录 / 拒装载 / 在飞 MCP 停）；② 否则 ⇒
 * `TrustStatus.Trusted`（扫到即受信）。
 *
 * 审批记录 `plugins.trust.<name> = {"sha256":<目录内容树 digest>,"approvedAt":..,
 * "scope":"all","files":{relPath:sha256}}` **不再决定装载**，只剩两个用途：① 审计
 * （面板「变更摘要」逐文件 diff 的数据源）；② **seed 覆盖仲裁基准**
 * （[[PluginRegistry.trustRecordDigest]] ⇒ `SeedService.reconcilePlugin` 的「干净快照」
 * 判据）。**12 条存量记录零改写、零迁移**（本批不做任何数据动作）。
 *
 * 内容变更（目录 digest ≠ 记录 digest）**不再拦装载** ⇒ 降级为**非拦截可见性**
 * （`PluginDef.contentChanged`）：① REST `GET /plugins` 字段 `contentChanged`；
 * ② 目录段尾注记（[[PluginRegistry.renderCatalog]]）；③ 启动/重扫健康摘要
 * （[[PluginRegistry.healthSummary]]）。
 * 被封禁的包：不进目录、`resolve` 返 Left（闸 A/B/C/E 拒）、在飞 MCP ≤30s 停（闸 D）。
 *
 * 扫描节奏（§B.3）：进程内 mtime 缓存——目录树任一 mtime 变化即全量重扫
 * （对齐 skill「改后即时生效」机制）；spawn/NodeEdit 校验路径每次走 list/resolve
 * 天然新鲜。⚠️ 封禁表在 `nebflow.json` 里（不在 plugins/ 树内）⇒ 封禁/解封写面必须
 * 显式失效该缓存（[[PluginBlockPolicy]] 已接）。
 *
 * 装载可见性（可见性批 2026-09-10，P1 静默缩容；无审批批 2026-09-13 口径更新）：
 * 装载失败 / 封禁都会让包从 Plugin Catalog 消失，此前只有逐包 WARN、目录静默缩容。
 * 现在两条聚合出口：①目录段尾缺席注记（[[PluginRegistry.renderCatalog]] 单点，分发器
 * 注入段与 REST GET /plugins/catalog 同字节）；②启动/重扫健康摘要
 * （[[PluginRegistry.healthSummary]] / [[PluginRegistry.logHealthSummary]]）。两者都只加
 * 可见性——装载校验与内容面判定语义不受影响。
 */
object PluginRegistry:

  private val logger = NebflowLogger.forName("nebflow.plugin")

  /** 插件根目录（rebrand/测试 setDataRoot 均生效）。 */
  private def pluginsDir: os.Path = PathUtil.dataRoot / "plugins"

  /** §B.6 固定安全集：plugin 只能授予既有 builtin 工具且限于白名单——
    * 不能发明新工具、不能授予编排类（Mail/NodeEdit 等永不进白名单，
    * 角色边界由 §C.1 静态矩阵守住）。R2「一个 Mail 统一」（2026-09-12）后
    * 原举例的 `Task` 已退役、`Mail` 是唯一消息原语——本条覆盖此前相关指令。
    *
    * 2026-09-10 作者裁定（Pop 收归 Nebula 专属）：Pop 移出白名单——插件再授予
    * 通道关闭（否则「第三方包声明 org.nebflow/tools:["Pop"]」可绕过
    * AgentCore.NebulaExclusiveTools 的剥离面，把 Pop 发回任意节点）。实测对既有
    * 插件零影响：全部 plugin.json 与 org.nebflow/tools.json 无一授予 builtin
    * 工具（证据见 pop-nebula-exclusive 批 plugin-tools-scan 留档）。 */
  val BuiltinToolWhitelist: Set[String] = Set("WebSearch", "WebFetch", "Curl")

  /** §5.2 canonical manifest $schema（Agent Plugins 1.0.0）。缺失/非 canonical
    * → 拒载（required + 客户端只识别 canonical 值，§5.2/§5.3）。 */
  val CanonicalSchema = "https://agent-plugins.org/schemas/1.0.0/plugin.schema.json"

  /** §7.2.1 canonical mcp.json $schema。缺失/非 canonical → MCP 组件 invalid
    * （插件继续装载其余组件，§6.2 边界）。 */
  val CanonicalMcpSchema = "https://agent-plugins.org/schemas/1.0.0/mcp.schema.json"

  /** §5.2 闭合 schema 十一字段（capability 系 dispatcher-ctx 批新增后注释未同步，
    * 2026-09-10 顺手修正；其余能力走 extensions 命名空间）。描述单源批
    * （作者 2026-09-10 09:30 裁定）：`capability` 键转 deprecated——登记在本集合
    * 使存量包仍带该键不报错、不触发 unknown-field 告警（向后兼容），但读取/承载/
    * 渲染逻辑全部退役（PluginDef 无此字段），description 是唯一描述源。 */
  private val KnownManifestKeys = Set(
    "$schema", "name", "version", "description", "capability", "author",
    "homepage", "repository", "license", "keywords", "extensions")

  /** §9.1 客户端必须注入 stdio 子进程的两个占位变量；plugin mcp.json env 声明
    * 同名键 → entry invalid（schema propertyNames.not）。 */
  val PluginPlaceholderEnvKeys: Set[String] = Set("PLUGIN_ROOT", "PLUGIN_DATA")

  /** §B.3 红标启发式：凭据类 env 键名（仅红标提示，不拒载——信任门审批清单的
    * 「审什么」辅助，非装载规则）。 */
  val CredentialKeyPattern: Regex = "(?i)(passw(or)?d|secret|token|api.?key|access.?key|private.?key|credential|auth)".r

  /** §B.3 红标启发式：command 指向 shell / 网络类可执行（设计文档「command 指向
    * curl|sh 类」）。 */
  val ShellLikeCommands: Set[String] =
    Set("sh", "bash", "zsh", "dash", "ksh", "csh", "tcsh", "pwsh", "powershell", "cmd",
      "curl", "wget", "nc", "ncat", "netcat", "telnet", "ssh")

  // ── 数据模型 ──────────────────────────────────────────────

  final case class PluginSkill(
    id: String, // <plugin>/<skill>
    name: String,
    description: String,
    path: String // SKILL.md 绝对路径
  )

  /** 内容面可用性（2026-09-13 作者令「装了就是信任」后**唯一**的两个取值）：
    * - [[TrustStatus.Trusted]] = **在位即信任**（`loadPlugin` 扫到即受信）+ 未被封禁；
    * - [[TrustStatus.Blocked]] = 命中封禁面（`plugins.revoked.<name>`，deny-list）。
    * 全部下游闸（A/B/C/E/D）、目录过滤链、面板清单都只问 [[TrustStatus.trusted]]，
    * 故「在位即信任」与「点名封禁」两件事共用这一条链、零分叉。 */
  sealed trait TrustStatus extends Product with Serializable:
    def trusted: Boolean
  object TrustStatus:
    /** `approvedAt` = 审批记录时刻（无记录 ⇒ 0——记录**不再决定装载**，只作审计；
      * seed 覆盖仲裁基准走 `trustRecordDigest`，与这里无关）。 */
    final case class Trusted(approvedAt: Long, digest: String) extends TrustStatus:
      val trusted = true
    /** 封禁（deny-list）：`at`/`by`/`reason` 来自 `plugins.revoked.<name>`。 */
    final case class Blocked(at: Long, by: String, reason: String) extends TrustStatus:
      val trusted = false

  /** 注册表条目（§B.3 产出结构）。author 为渲染字符串（§5.4 author object 的
    * name/email/url 摘要）；homepage/repository/license/keywords 为 §5.4 元数据
    * 字段（协议符合度批新增，供审批清单完整渲染）。描述单源批（2026-09-10）：
    * PluginDef 不再承载 capability（字段退役，manifest 键 deprecated 容忍见
    * KnownManifestKeys）——description 是唯一描述源。 */
  final case class PluginDef(
    name: String,
    version: String,
    description: String,
    author: String,
    homepage: String = "",
    repository: String = "",
    license: String = "",
    keywords: List[String] = Nil,
    skills: List[PluginSkill],
    mcpServers: Map[String, McpServerConfig],
    toolsExtension: List[String],
    digest: String,
    fileCount: Int,
    warnings: List[String],
    trust: TrustStatus,
    dir: String,
    /** **非拦截可见性**（2026-09-13 无审批批）：目录内容与审批记录 digest 不符
      * （= 「内容已变更」）。**不拦装载**（在位即信任），只喂三处可见性：API 字段
      * `contentChanged` / 目录段尾注记 / 启动健康摘要。无审批记录 ⇒ false
      * （无可比对基准，不是「变更」）。 */
    contentChanged: Boolean = false
  )

  /** 审批清单条目（§B.3 面板渲染数据源）：元信息 + §5.4 元数据 + skills 摘要 +
    * mcp（env 键名打码值）+ tools 申请 + 信任状态 + 红标项（flags）+ 变更摘要
    * （changeSummary，与上次审批版本逐文件 diff——§B.3 审批清单格式表后两行）。 */
  def approvalManifest(p: PluginDef): Json =
    Json.obj(
      "name" -> p.name.asJson,
      "version" -> p.version.asJson,
      "description" -> p.description.asJson,
      "author" -> p.author.asJson,
      "homepage" -> p.homepage.asJson,
      "repository" -> p.repository.asJson,
      "license" -> p.license.asJson,
      "keywords" -> p.keywords.asJson,
      "digest" -> p.digest.asJson,
      "fileCount" -> p.fileCount.asJson,
      // 无审批批（2026-09-13）三字段（面板/CLI/复核共用契约）：
      //  - `trusted` = 内容面可用（在位即信任 ∧ 未被封禁）——无记录包首扫即 true；
      //  - `blocked` = 命中封禁面（deny-list，独立命名空间 `plugins.revoked`）；
      //  - `contentChanged` = 内容与审批记录不符（**非拦截**，仅可见性）。
      "trusted" -> p.trust.trusted.asJson,
      "blocked" -> (p.trust match
        case TrustStatus.Blocked(_, _, _) => true
        case _ => false).asJson,
      "contentChanged" -> p.contentChanged.asJson,
      "trust" -> (p.trust match
        case TrustStatus.Trusted(at, d) => Json.obj("status" -> "trusted".asJson, "approvedAt" -> at.asJson, "digest" -> d.asJson)
        case TrustStatus.Blocked(at, by, reason) =>
          Json.obj("status" -> "blocked".asJson, "blockedAt" -> at.asJson, "blockedBy" -> by.asJson, "reason" -> reason.asJson)
      ),
      // 令 1 拆面（2026-09-12）：派发面状态随清单下发——面板开关据此渲染
      // （`enabled=false` 只是「禁未来派发」，不是内容未受信）。
      "dispatch" -> Json.obj(
        "enabled" -> PluginDispatchPolicy.effective(p.name, p.trust.trusted).asJson,
        "authorEnabled" -> PluginDispatchPolicy.authorEnabled(p.name).asJson,
        "transitionActive" -> PluginDispatchPolicy.transitionActive(p.name, System.currentTimeMillis()).asJson,
        "reason" -> (if !p.trust.trusted then
                       "Plugin is blocked (deny-list) — unblock it to use it again: POST /api/plugins/" + p.name + "/unblock."
                     else if PluginDispatchPolicy.effective(p.name, trusted = true) then ""
                     else "Disabled for new dispatches by the author — in-flight nodes keep their plugin grant. " +
                       s"Re-enable via the panel switch, POST /api/plugins/${p.name}/enable, or CLI 'nebflow plugin enable ${p.name}'.")
          .asJson
      ),
      "skills" -> p.skills
        .map(s => Json.obj("id" -> s.id.asJson, "description" -> s.description.asJson, "preview" -> previewLines(s.path).asJson))
        .asJson,
      "mcpServers" -> p.mcpServers.map { case (n, c) =>
        Json.obj(
          "server" -> n.asJson,
          "transport" -> (if c.command.isDefined then "stdio" else if c.url.isDefined then "streamable-http" else "none").asJson,
          "command" -> c.command.asJson,
          "args" -> c.args.asJson,
          "url" -> c.url.asJson,
          "envKeys" -> c.env.map(_.keys.toList).getOrElse(Nil).asJson // 值打码：只出键名（§B.3 红标项）
        )
      }.asJson,
      "toolsExtension" -> p.toolsExtension.asJson,
      "warnings" -> p.warnings.asJson,
      "flags" -> approvalFlags(p).asJson,
      "changeSummary" -> changeSummary(p)
    )

  /** §B.3 审批清单红标项：无 author / 无 version（元信息行）；env 凭据类键名、
    * command 指向 shell|curl 类（mcp 行）。skills 提示词启发式扫描为设计文档
    * 「后续可加」项——非 1.0.0 必需，不实现（对照表申报）。 */
  private def approvalFlags(p: PluginDef): List[String] =
    val fs = mutable.ListBuffer[String]()
    if p.version.isEmpty then fs += "manifest has no version field"
    if p.author.isEmpty then fs += "manifest has no author field"
    p.mcpServers.foreach { case (n, c) =>
      c.env.getOrElse(Map.empty).keySet.toList.sorted.foreach { k =>
        if CredentialKeyPattern.findFirstIn(k).isDefined then
          fs += s"mcp server '$n': env key '$k' looks like a credential — review carefully"
      }
      c.command.foreach { cmd =>
        val base = cmd.split('/').last
        if ShellLikeCommands.contains(base) then
          fs += s"mcp server '$n': command '$base' is a shell/network binary — review carefully"
      }
    }
    fs.toList

  /** §B.3 变更摘要：与上次审批版本的逐文件 diff（首审 = new-install；已审批且
    * 未变 = unchanged；有变化 = changed + added/removed/modified）。审批记录无
    * files 快照（协议符合度批之前的旧记录）→ 降级为 digest 级比对并注明。 */
  private def changeSummary(p: PluginDef): Json =
    trustRecord(p.name) match
      case None => Json.obj("kind" -> "new-install".asJson)
      case Some(rec) =>
        val current = fileManifest(os.Path(p.dir))
        rec.files match
          case None =>
            Json.obj(
              "kind" -> "changed".asJson,
              "note" -> "approval record predates file-level snapshots — digest-level comparison only".asJson,
              "digestMatches" -> (rec.sha256 == p.digest).asJson)
          case Some(approved) =>
            val added = (current.keySet -- approved.keySet).toList.sorted
            val removed = (approved.keySet -- current.keySet).toList.sorted
            val modified = approved.keySet.intersect(current.keySet).toList.sorted
              .filter(k => approved(k) != current(k))
            if added.isEmpty && removed.isEmpty && modified.isEmpty then
              Json.obj("kind" -> "unchanged".asJson)
            else
              Json.obj(
                "kind" -> "changed".asJson,
                "added" -> added.asJson,
                "removed" -> removed.asJson,
                "modified" -> modified.asJson)

  private def previewLines(path: String, n: Int = 20): String =
    try
      val raw = os.read(os.Path(path))
      val body =
        if raw.trim.startsWith("---") then
          raw.trim.indexOf("---", 3) match
            case i if i > 0 => raw.trim.substring(i + 3).trim
            case _ => raw
        else raw
      val lines = body.linesIterator.toList
      if lines.size <= n then body else lines.take(n).mkString("\n") + "\n…"
    catch case _: Exception => "(unreadable)"

  // ── digest（目录内容树，确定性）────────────────────────────

  /** SHA-256 目录内容树 digest：按相对 POSIX 路径排序，逐文件喂入
    * "relPath\0<bytes>\0"。路径含文件名字段（manifest name 不参与判定——改
    * plugin.json 任何字段都会变 digest，version bump 即重审）。 */
  def computeDigest(dir: os.Path): Either[String, (String, Int)] =
    if !os.isDir(dir) then Left(s"not a directory: $dir")
    else
      try
        val files = os.walk(dir).filter(os.isFile).toList.sortBy(relPath(dir, _))
        val md = MessageDigest.getInstance("SHA-256")
        files.foreach { f =>
          md.update(s"${relPath(dir, f)}\u0000".getBytes("UTF-8"))
          md.update(os.read.bytes(f))
          md.update("\u0000".getBytes("UTF-8"))
        }
        Right((md.digest().map("%02x".format(_)).mkString, files.size))
      catch
        case e: Exception => Left(s"digest computation failed: ${e.getMessage}")

  /** §B.3 变更摘要用的逐文件 sha256 快照（relPath → hex）。 */
  private def fileManifest(dir: os.Path): Map[String, String] =
    try
      os.walk(dir).filter(os.isFile).toList.flatMap { f =>
        try Some(relPath(dir, f) -> sha256Hex(os.read.bytes(f)))
        catch case _: Exception => None
      }.toMap
    catch case _: Exception => Map.empty

  private def sha256Hex(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString

  private def relPath(root: os.Path, f: os.Path): String =
    f.relativeTo(root).toString

  /** §4.1 路径围栏：路径解析符号链接后必须仍在插件根内（越界 → false）。
    * 用于所有「按插件作者提供的路径读文件」的入口。不存在的路径无 symlink
    * 逃逸面（toRealPath 会 NoSuchFile）→ 降级为词法归一判定（E2E 取证修复：
    * 声明式 extensions 路径先存在性后围栏，不可把缺失文件误判为逃逸）。 */
  private def containedUnder(root: os.Path, p: os.Path): Boolean =
    try
      val real = java.nio.file.Paths.get(p.toString).toRealPath()
      val rootReal = java.nio.file.Paths.get(root.toString).toRealPath()
      real.startsWith(rootReal)
    catch
      case _: java.nio.file.NoSuchFileException =>
        try
          val norm = java.nio.file.Paths.get(p.toString).normalize()
          val rootNorm = java.nio.file.Paths.get(root.toString).normalize()
          norm.startsWith(rootNorm)
        catch case _: Exception => false
      case _: Exception => false

  /** §5.5 插件名约束：1-64 字符；a-z 0-9 - . ；首尾必须是字母数字；禁止
    * 连续 `--` 或 `..`。 */
  def validPluginName(n: String): Boolean =
    n.nonEmpty && n.length <= 64 &&
      n.forall(c => c.isDigit || (c >= 'a' && c <= 'z') || c == '-' || c == '.') &&
      n.headOption.exists(c => c.isDigit || (c >= 'a' && c <= 'z')) &&
      n.lastOption.exists(c => c.isDigit || (c >= 'a' && c <= 'z')) &&
      !n.contains("--") && !n.contains("..")

  // ── 扫描 ──────────────────────────────────────────────

  /** mtime 缓存（进程内）：目录树（根 + 子树全部目录**与文件**）的绝对路径 mtime
    * 快照。文件内容修改只变文件自身 mtime（父目录 mtime 不动）→ 签名必须含文件，
    * 否则「改动即重审」（§B.8-3）会在缓存命中路径上漏检。键 = 绝对路径。 */
  private case class Cache(sig: List[(String, Long)], snapshot: Snapshot)
  private val cache = new java.util.concurrent.atomic.AtomicReference[Option[Cache]](None)

  /** 一次全量扫描的完整产出：可装载条目（含**被封禁**项——面板/审计需要看到 deny-list
    * 命中者及其 block 元数据；无审批批 2026-09-13 后条目面不再有「待审」形态，受信与否
    * 只取决于「盘上在位 ∧ 未被封禁」）+ 拒载清单（§B.2 左值）。可见性批（2026-09-10 P1
    * 静默缩容）：拒载清单与条目同缓存，目录缺席注记 / 启动健康摘要 / 渲染共用同一次扫描
    * （单点、零重复装载）。 */
  private final case class Snapshot(plugins: List[PluginDef], rejected: List[(String, String)])

  private def treeSig(dir: os.Path): List[(String, Long)] =
    try
      val all = os.walk(dir).toList
      (dir :: all).map(p => p.toString -> os.mtime(p)).sortBy(_._1)
    catch case _: Exception => Nil

  /** 全量扫描唯一实现（缓存：目录树 mtime 签名未变则复用上次产出）。 */
  private def snapshot(): IO[Snapshot] =
    IO.blocking {
      if !os.isDir(pluginsDir) then Snapshot(Nil, Nil)
      else
        val subDirs = os.list(pluginsDir).filter(os.isDir).toList.sortBy(_.last)
        val full = treeSig(pluginsDir)
        cache.get match
          case Some(c) if c.sig == full => c.snapshot
          case _ =>
            val loaded = subDirs.map(d => loadPlugin(d))
            val snap = Snapshot(
              plugins = loaded.collect { case Right(p) => p },
              rejected = loaded.collect { case Left((n, r)) => n -> r })
            snap.rejected.foreach { case (n, r) => logger.warnSync(s"Plugin '$n' rejected: $r") }
            cache.set(Some(Cache(full, snap)))
            snap
    }

  /** 全量注册表（含**被封禁**项——面板/审计需要看到 deny-list 命中者及其 block 元数据）。 */
  def scan(): IO[List[PluginDef]] = snapshot().map(_.plugins)

  /** 审批清单渲染数据：全部插件 + 拒载原因（同缓存快照，与 scan 同源同时点）。 */
  def listWithRejected(): IO[(List[PluginDef], List[(String, String)])] =
    snapshot().map(snap => (snap.plugins, snap.rejected))

  /** 解析单个插件（内容面校验入口）。Left = 拒绝原因（包不存在 / **被封禁**）。
    *
    * 无审批批（2026-09-13 作者令「装了就是信任」）：装载不再要求人工审批记录 ⇒ 本方法
    * 只剩两种 Left。「封禁」（deny-list，`plugins.revoked`）是唯一的点名阻止手段——
    * 错误文案指向**存在的动作**（unblock），不再出现 `never approved (default-deny)`
    * 与 approve 指引（无审批下那是死路）。 */
  def resolve(name: String): IO[Either[String, PluginDef]] =
    scan().map { all =>
      all.find(_.name == name) match
        case None =>
          Left(s"Plugin '$name' not found in registry (scan ${all.size} plugin(s)). " +
            "Check the directory name under ~/.nebflow/plugins/. (PLUGIN_NOT_FOUND)")
        case Some(p) if !p.trust.trusted =>
          val reason = p.trust match
            case TrustStatus.Blocked(_, _, r) => r
            case _ => "blocked"
          Left(s"Plugin '$name' is BLOCKED (deny-list: $reason). " +
            s"A blocked package is refused everywhere (no load, no dispatch, in-flight MCP stopped). " +
            s"Unblock it if intended: Plugin panel, REST POST /api/plugins/$name/unblock, " +
            "or CLI 'nebflow plugin unblock $name'. (PLUGIN_BLOCKED)")
        case Some(p) => Right(p)
    }

  /** 内容面可用性查询（**令 1 拆面** 2026-09-12 引入，无审批批 2026-09-13 口径更新）：
    * 该包是否存在且内容面可用（= `resolve` 的正脸，不含任何派发许可判定）。
    * 在位即信任后 = 「装载成功 ∧ 未被封禁」；派发面单点
    * `PluginDispatchPolicy.effective(name, trusted)` 以此为唯一输入——两面唯一耦合点。 */
  def contentTrusted(name: String): IO[Boolean] =
    scan().map(_.find(_.name == name).exists(_.trust.trusted))

  // ── 单插件装载（§B.2 装载校验规则 + Agent Plugins 1.0.0 §4-§8）───────

  private def loadPlugin(dir: os.Path): Either[(String, String), PluginDef] =
    val name0 = dir.last
    val warnings = mutable.ListBuffer[String]()

    // 未知顶层条目 → 宽容跳过 + 告警（§B.8-5；LICENSE/CHANGELOG 等杂项文件同样走此规则）
    val known = Set("plugin.json", "mcp.json", "skills", "org.nebflow")
    os.list(dir).foreach { e =>
      val n = e.last
      if !known.contains(n) then warnings += s"ignored unknown entry '$n' (forward-compat: skipped)"
      else if n == "org.nebflow" && os.isDir(e) then
        os.list(e).foreach { f => if f.last != "tools.json" then warnings += s"ignored unknown org.nebflow entry '${f.last}'" }
    }

    // manifest（plugin.json 必需，§5.1；路径围栏 §4.1）
    val nsDir = dir / "org.nebflow"
    val manifestPath = dir / "plugin.json"
    if !os.isFile(manifestPath) then return Left(name0 -> "missing plugin.json manifest")
    if !containedUnder(dir, manifestPath) then
      return Left(name0 -> "plugin.json resolves outside the plugin root (symlink escape, §4.1 containment)")
    io.circe.parser.parse(os.read(manifestPath)) match
      case Left(err) => return Left(name0 -> s"plugin.json unparseable: ${err.message}")
      case Right(json) if !json.isObject =>
        return Left(name0 -> "plugin.json must contain a top-level object (§5.2)")
      case Right(json) =>
        val c = json.hcursor
        // 未知 manifest 字段 → 宽容忽略 + 告警（§5.2/§B.8-5 前向兼容：报告且忽略、继续装载）
        json.asObject.foreach { obj =>
          obj.keys.filterNot(KnownManifestKeys.contains).foreach(k =>
            warnings += s"ignored unknown manifest field '$k' (forward-compat: skipped)")
        }

        // $schema：必填 + canonical（§5.2/§5.3——客户端只识别 canonical 值，
        // 不支持声明的版本 → 拒绝并报告 unsupported version）
        val schema = c.downField("$schema").as[String].toOption.getOrElse("")
        if schema.isEmpty then
          return Left(name0 -> "manifest missing required field '$schema' (§5.3)")
        if schema != CanonicalSchema then
          return Left(name0 ->
            (s"unsupported Agent Plugins version: '$schema' is not the canonical 1.0.0 identifier " +
            s"($CanonicalSchema) — client MUST reject (§5.2) (PLUGIN_SCHEMA_UNSUPPORTED)"))

        // name：必填 + §5.5 命名约束（违反 = manifest invalid → 拒载）
        val name = c.downField("name").as[String].toOption.map(_.trim).filter(_.nonEmpty)
        if name.isEmpty then return Left(name0 -> "manifest missing required field 'name' (§5.3)")
        val pname = name.get
        if !validPluginName(pname) then
          return Left(name0 ->
            (s"manifest name '$pname' violates §5.5 constraints (1-64 chars; a-z 0-9 - .; " +
            "alphanumeric start/end; no consecutive '--' or '..') (PLUGIN_NAME_ILLEGAL)"))
        // 目录名 ≠ manifest name → 以 manifest 为准并告警（防止引用歧义）
        if pname != name0 then warnings += s"manifest name '$pname' differs from directory name '$name0' — using manifest name"

        // §5.4 元数据字段：类型校验（违反 = fatal）；version 语义（审计项 2）：
        // 协议明示 MUST NOT 因非 semver 拒载（semver 仅 RECOMMENDED）→ 不做
        // 格式约束，仅经 digest 纳入信任摘要（version bump 即重审）。
        val version = stringField(c, "version") match
          case Right(v) => v
          case l @ Left(_) => return Left(name0 -> l.swap.toOption.getOrElse(""))
        val description = stringField(c, "description") match
          case Right(v) => v
          case l @ Left(_) => return Left(name0 -> l.swap.toOption.getOrElse(""))
        // capability：描述单源批（2026-09-10）退役——不再读取不再承载（deprecated
        // 键经 KnownManifestKeys 登记而宽容装载，见集合注释）
        val homepage = stringField(c, "homepage") match
          case Right(v) => v
          case l @ Left(_) => return Left(name0 -> l.swap.toOption.getOrElse(""))
        val repository = stringField(c, "repository") match
          case Right(v) => v
          case l @ Left(_) => return Left(name0 -> l.swap.toOption.getOrElse(""))
        val license = stringField(c, "license") match
          case Right(v) => v
          case l @ Left(_) => return Left(name0 -> l.swap.toOption.getOrElse(""))
        val keywords = c.downField("keywords").as[Option[List[String]]] match
          case Right(k) => k.getOrElse(Nil)
          case Left(_) => return Left(name0 -> "manifest field 'keywords' must be an array of strings (§5.4)")
        // §5.4 author object：仅 name/email/url 三个 string 字段（其余字段或
        // 值类型 → manifest invalid → 拒载）；渲染为可读字符串供审批清单。
        val author = c.downField("author").as[Option[Json]] match
          case Left(_) => return Left(name0 -> "manifest field 'author' must be an object with optional name/email/url strings (§5.4)")
          case Right(None) => ""
          case Right(Some(a)) =>
            a.asObject match
              case None => return Left(name0 -> "manifest field 'author' must be an object with optional name/email/url strings (§5.4)")
              case Some(obj) =>
                val bad = obj.keys.filterNot(Set("name", "email", "url")).nonEmpty ||
                  obj.toMap.exists { case (k, v) => Set("name", "email", "url")(k) && v.asString.isEmpty }
                if bad then return Left(name0 -> "manifest field 'author' allows only name/email/url string fields (§5.4)")
                renderAuthor(obj)

        // skills/（§7.1：一级子目录含精确命名 SKILL.md 的常规文件 = 一个 skill；
        // 缺失 → 非错误；存在但非目录 → 组件 invalid + 继续（§6.2））
        val skillsDir = dir / "skills"
        val skills: List[PluginSkill] =
          if !os.exists(skillsDir) then Nil
          else if !os.isDir(skillsDir) then
            warnings += s"component location 'skills' is not a directory — component invalid, skipped (§6.2)"
            Nil
          else
            os.list(skillsDir).filter(os.isDir).toList.sortBy(_.last).flatMap { sd =>
              val f = sd / "SKILL.md"
              if !os.isFile(f) then
                warnings += s"skills/${sd.last} has no SKILL.md — skipped (§7.1)"
                None
              else if !containedUnder(dir, f) then
                warnings += s"skills/${sd.last}/SKILL.md resolves outside the plugin root — skill skipped (§4.1 containment)"
                None
              else
                readConformantSkill(sd, f) match
                  case Some(s) => Some(s.copy(id = s"$pname/${sd.last}"))
                  case None =>
                    warnings += s"skills/${sd.last} skipped — SKILL.md frontmatter missing required 'name'/'description' (agentskills.io via §7.1)"
                    None
            }

        // mcp.json（§7.2.1 闭合 schema；组件级违规 → MCP 组件 invalid，插件继续）
        val mcpServers: Map[String, McpServerConfig] =
          val mcpPath = dir / "mcp.json"
          if !os.exists(mcpPath) then Map.empty
          else if !os.isFile(mcpPath) then
            warnings += s"component location 'mcp.json' is not a regular file — component invalid, skipped (§6.2)"
            Map.empty
          else if !containedUnder(dir, mcpPath) then
            warnings += s"mcp.json resolves outside the plugin root — component invalid, skipped (§4.1)"
            Map.empty
          else parseMcpJson(mcpPath, dir, warnings)

        // org.nebflow/tools.json（或 manifest extensions 声明的文件名。声明值语义
        // 按 §B.2/§8：相对 org.nebflow/ 命名空间目录解析为主（扩展目录内容归命名
        // 空间自有），兼容插件根相对形态；两处均围栏校验（§4.1））
        val toolsPath =
          val declared = c.downField("extensions").downField("org.nebflow/tools").as[String].toOption
          declared match
            case Some(rel) =>
              val nsRel =
                try Some(nsDir / os.SubPath(rel))
                catch case _: Exception => None
              val rootRel =
                if rel.startsWith("./") then resolvePluginRelative(dir, rel)
                else resolvePluginRelative(dir, s"./$rel")
              List(nsRel, rootRel).flatten.find(p => os.isFile(p) && containedUnder(dir, p)) match
                case Some(p) => Some(p)
                case None =>
                  warnings += s"extensions 'org.nebflow/tools' declared '$rel' but no readable file under org.nebflow/ or plugin root — tools extension ignored"
                  None
            case None =>
              val d = nsDir / "tools.json"
              if !os.isFile(d) then None
              else if !containedUnder(dir, d) then
                warnings += s"org.nebflow/tools.json resolves outside the plugin root — tools extension ignored (§4.1)"
                None
              else Some(d)
        val toolsExtension: List[String] = toolsPath match
          case None => Nil
          case Some(p) if !containedUnder(dir, p) =>
            warnings += s"tools.json resolves outside the plugin root — tools extension ignored (§4.1)"
            Nil
          case Some(p) =>
            io.circe.parser.parse(os.read(p)) match
              case Left(err) =>
                warnings += s"tools.json unparseable (${err.message}) — tools extension ignored"
                Nil
              case Right(tjson) =>
                tjson.hcursor.downField("tools").as[List[String]] match
                  case Right(tools) =>
                    val illegal = tools.filterNot(BuiltinToolWhitelist.contains)
                    if illegal.nonEmpty then
                      return Left(pname ->
                        (s"org.nebflow/tools requests non-whitelisted tool(s): ${illegal.mkString(", ")}. " +
                          s"Allowed builtin tools: ${BuiltinToolWhitelist.toList.sorted.mkString(", ")} (§B.6). (PLUGIN_TOOLS_ILLEGAL)"))
                    tools
                  case Left(err) =>
                    warnings += s"tools.json decode failed (${err.getMessage}) — tools extension ignored"
                    Nil
              end match

        // 装载校验（裁定 12）：skills 与 mcp 至少其一
        if skills.isEmpty && mcpServers.isEmpty then
          return Left(pname -> "plugin has neither skills/ nor mcp.json — nothing to allocate (refused at load)")

        val (digest, fileCount) = computeDigest(dir) match
          case Right(d) => d
          case Left(err) => return Left(pname -> err)

        // 官方身份装载层闸（P0-3 官方包身份脚手架；基线 §2.4；验收 BU A3）：
        // `nebflow-` 前缀 = 官方保留 namespace —— 前缀匹配但 digest ∉ 分发内置官方允许列表
        // ⇒ 拒载（`OFFICIAL_IMPERSONATION`）。本闸**必须在装载层**：拒绝先于可用（拒载的包
        // 不进 registry ⇒ 不可分配、不可装载、resolve ⇒ PLUGIN_NOT_FOUND）。
        // 非保留前缀的包不经本闸 ⇒ 第三方包判定路径逐字不变（对照臂）。
        // 位置在 digest 之后：判定输入 = **目录内容 digest**（不是名字本身），故必须在
        // `computeDigest` 之后；拒载理由里带上两个短 digest 供人工核对。
        if !OfficialPackages.admits(pname, digest) then
          return Left(pname -> OfficialPackages.rejectionReason(pname, digest))

        // 内容面判定（无审批批，2026-09-13 作者令「装了就是信任」）：
        //   ① **先查封禁（deny）**：`plugins.revoked.<name>` 命中 ⇒ Blocked（指名阻止）；
        //   ② **再默认受信（allow）**：扫到即 Trusted —— 审批记录**不再决定装载**
        //      （`plugins.trust` 只作审计 + seed 覆盖仲裁基准 `trustRecordDigest`）。
        // 顺序不可交换（设计硬约束 R4：封禁是 default-allow 下唯一的点名止损手段）。
        // 内容变更（digest 漂移）= **非拦截可见性**（`contentChanged`），不改变 trust。
        val trustRec = trustRecord(pname)
        val contentChanged = trustRec.exists(_.sha256 != digest)
        val trust = PluginBlockPolicy.entryFor(pname) match
          case Some(b) => TrustStatus.Blocked(at = b.at, by = b.by, reason = b.reason)
          case None => TrustStatus.Trusted(approvedAt = trustRec.map(_.approvedAt).getOrElse(0L), digest = digest)

        Right(PluginDef(
          name = pname,
          version = version,
          description = description,
          author = author,
          homepage = homepage,
          repository = repository,
          license = license,
          keywords = keywords,
          skills = skills,
          mcpServers = mcpServers,
          toolsExtension = toolsExtension,
          digest = digest,
          fileCount = fileCount,
          warnings = warnings.toList,
          trust = trust,
          dir = dir.toString,
          contentChanged = contentChanged
        ))
    end match
  end loadPlugin

  /** §5.4 string 元数据字段：present 必须是 string（类型违规 = manifest invalid，
    * fatal）；absent → ""。 */
  private def stringField(c: io.circe.HCursor, field: String): Either[String, String] =
    c.downField(field).as[Option[String]] match
      case Right(v) => Right(v.getOrElse(""))
      case Left(_) => Left(s"manifest field '$field' must be a string (§5.4)")

  /** §5.4 author object → 可读字符串："Name <email> (url)"（缺省段略）。 */
  private def renderAuthor(obj: JsonObject): String =
    def s(k: String): String = obj(k).flatMap(_.asString).getOrElse("")
    val name = s("name")
    val email = s("email")
    val url = s("url")
    List(
      if name.nonEmpty then name else "",
      if email.nonEmpty then s"<$email>" else "",
      if url.nonEmpty then s"($url)" else ""
    ).filter(_.nonEmpty).mkString(" ")

  /** agentskills.io 一致性门（§7.1 skip+report）：frontmatter 须含非空 name 与
    * description。 */
  private def readConformantSkill(dir: os.Path, f: os.Path): Option[PluginSkill] =
    try
      val fm = frontmatter(os.read(f))
      val name = extractField(fm, "name").map(_.trim).filter(_.nonEmpty)
      val desc = extractField(fm, "description").map(_.trim).filter(_.nonEmpty)
      (name, desc) match
        case (Some(n), Some(d)) => Some(PluginSkill(id = "", name = n, description = d, path = f.toString))
        case _ => None
    catch case _: Exception => None

  /** §7.2.1 mcp.json 闭合 schema：{$schema(canonical), mcpServers(object)} 顶层；
    * 缺失/非 canonical $schema、mcpServers 缺失/非 object → MCP 组件整体 invalid
    * （告警 + 无 MCP，插件继续装载，§6.2 边界）；未知顶层字段 → 告警 + 忽略
    * （前向兼容）。server entry 逐条校验（§7.2.2 隔离边界：单条违规只跳该条）。 */
  private def parseMcpJson(mcpPath: os.Path, dir: os.Path, warnings: mutable.ListBuffer[String]): Map[String, McpServerConfig] =
    io.circe.parser.parse(os.read(mcpPath)) match
      case Left(err) =>
        warnings += s"mcp.json unparseable (${err.message}) — MCP component invalid, skipped (§7.2.1)"
        Map.empty
      case Right(j) if !j.isObject =>
        warnings += s"mcp.json must be a JSON object — MCP component invalid, skipped (§7.2.1)"
        Map.empty
      case Right(j) =>
        val c = j.hcursor
        j.asObject.foreach { obj =>
          obj.keys.filterNot(k => k == "$schema" || k == "mcpServers").foreach(k =>
            warnings += s"mcp.json unknown top-level field '$k' ignored (forward-compat)")
        }
        val schema = c.downField("$schema").as[String].toOption.getOrElse("")
        if schema != CanonicalMcpSchema then
          warnings += s"mcp.json ${'$'}schema '$schema' is not the canonical 1.0.0 identifier " +
            "— MCP component invalid, skipped (§7.2.1)"
          Map.empty
        else
          c.downField("mcpServers").as[Map[String, Json]] match
            case Left(_) =>
              warnings += s"mcp.json missing/invalid 'mcpServers' object — MCP component invalid, skipped (§7.2.1)"
              Map.empty
            case Right(entries) =>
              // §10.1：mcp.json 与 plugin.json 的 $schema 版本须一致（同为
              // canonical 1.0.0 时天然一致——版本不同在此已拦）
              entries.toList.sortBy(_._1).flatMap { case (sname, sj) =>
                validateServerEntry(sname, sj, dir, warnings).map(cfg => sname -> cfg)
              }.toMap

  /** §7.2.2 单 server entry 校验（schema 闭合变体 + 规范语义）。
    * 违规 → 告警 + 跳过该 entry（其余 entry 与组件继续）。 */
  private def validateServerEntry(
    name: String, sj: Json, dir: os.Path, warnings: mutable.ListBuffer[String]
  ): Option[McpServerConfig] =
    def invalid(why: String): Option[McpServerConfig] =
      warnings += s"mcp server '$name' invalid — skipped: $why (§7.2.2)"
      None
    if !sj.isObject then return invalid("entry is not an object")
    val c = sj.hcursor
    // entry 内未知字段 → 告警 + 忽略（前向兼容，§7.2.2 report-and-ignore 语义）
    sj.asObject.foreach(_.keys.foreach { k =>
      if !Set("type", "command", "args", "env", "cwd", "url", "headers").contains(k) then
        warnings += s"mcp server '$name' unknown field '$k' ignored (forward-compat)"
    })
    val tpe = c.downField("type").as[String].toOption
    tpe match
      case None => invalid("missing required 'type'")
      case Some("stdio") =>
        val cmd = c.downField("command").as[String].toOption.getOrElse("")
        if cmd.isEmpty then return invalid("'command' required for stdio")
        if cmd.exists(_.isWhitespace) then
          return invalid("'command' must be a single executable token (no whitespace/shell strings)")
        // ./ 相对命令 → 按插件根解析为绝对路径 + 围栏（§4.1/§7.2.2）；裸名 → PATH 搜索原样保留
        val resolvedCommand =
          if cmd.startsWith("./") then
            resolvePluginRelative(dir, cmd) match
              case None => return invalid(s"'command' '$cmd' escapes the plugin root (§4.1 containment)")
              case Some(p) => p.toString
          else cmd
        val args = c.downField("args").as[Option[List[String]]] match
          case Right(v) => v
          case Left(_) => return invalid("'args' must be an array of strings")
        val env = c.downField("env").as[Option[Map[String, String]]] match
          case Right(v) => v
          case Left(_) => return invalid("'env' must be an object of string values")
        env.foreach(_.keySet.foreach { k =>
          if PluginPlaceholderEnvKeys.contains(k) then
            warnings += s"mcp server '$name' invalid — skipped: env must not declare '${k}' (§9.1) (§7.2.2)"
        })
        if env.exists(_.keySet.exists(PluginPlaceholderEnvKeys.contains)) then return None
        // cwd：须为 ./ 前缀（插件相对）或 ${PLUGIN_ROOT}/${PLUGIN_DATA} 占位
        // （官方 schema pattern）；归一为占位形式，运行期（acquire）展开为绝对路径。
        val cwdRaw = c.downField("cwd").as[Option[String]] match
          case Right(v) => v
          case Left(_) => return invalid("'cwd' must be a string")
        cwdRaw match
          case None => ()
          case Some(w) =>
            val ok = w.startsWith("./") || w.startsWith("${PLUGIN_ROOT}") || w.startsWith("${PLUGIN_DATA}")
            if !ok then return invalid(s"'cwd' '$w' must start with './', '${"$"}{PLUGIN_ROOT}' or '${"$"}{PLUGIN_DATA}'")
            if w.startsWith("./") then
              if resolvePluginRelative(dir, w).isEmpty then
                return invalid(s"'cwd' '$w' escapes the plugin root (§4.1 containment)")
        val cwd = cwdRaw.map {
          case w if w.startsWith("./") => s"${"$"}{PLUGIN_ROOT}/${w.stripPrefix("./")}"
          case w => w
        }
        Some(McpServerConfig(
          command = Some(resolvedCommand), args = args, env = env,
          url = None, headers = None, enabled = None, timeoutMs = None, cwd = cwd))
      case Some("streamable-http") | Some("sse") =>
        val url = c.downField("url").as[String].toOption.getOrElse("")
        if url.isEmpty then return invalid(s"'url' required for transport '${tpe.get}'")
        if !validMcpUrl(url) then
          return invalid(s"'url' must be an absolute http/https URL without userinfo or fragment; non-loopback hosts require https")
        if tpe.get == "sse" then
          // legacy HTTP+SSE wire protocol 本客户端未实现（OPTIONAL，§7.2.2-4）：
          // MUST skip + report——显式跳过并留告警，非静默。
          warnings += s"mcp server '$name' uses transport 'sse' — not supported by this client " +
            "(supports stdio, streamable-http); entry skipped (§7.2.2-4)"
          return None
        val headers = c.downField("headers").as[Option[Map[String, String]]] match
          case Right(v) => v
          case Left(_) => return invalid("'headers' must be an object of string values")
        Some(McpServerConfig(
          command = None, args = None, env = None,
          url = Some(url), headers = headers, enabled = None, timeoutMs = None, cwd = None))
      case Some(other) => invalid(s"unknown transport type '$other' (expected stdio | streamable-http | sse)")
  end validateServerEntry

  /** §7.2.2 URL 语义：绝对 http/https；无 userinfo；无 fragment；
    * 非 loopback host 必须 https。 */
  private def validMcpUrl(u: String): Boolean =
    try
      val uri = java.net.URI.create(u)
      val scheme = Option(uri.getScheme).getOrElse("")
      val host = Option(uri.getHost).getOrElse("")
      if !Set("http", "https").contains(scheme) then false
      else if uri.getUserInfo != null || uri.getFragment != null then false
      else if host.isEmpty then false
      else
        val loopback = host == "localhost" || host == "::1" || host == "[::1]" || host.startsWith("127.")
        if scheme == "http" then loopback else true
    catch case _: Exception => false

  private def frontmatter(content: String): String =
    val t = content.trim
    if t.startsWith("---") then
      val end = t.indexOf("---", 3)
      if end > 0 then t.substring(3, end).trim else ""
    else ""

  private def extractField(fm: String, field: String): Option[String] =
    fm.split("\n").map(_.trim)
      .find(l => l.startsWith(s"$field:") || l.startsWith(s"$field :"))
      .map { l => val i = l.indexOf(':'); l.substring(i + 1).trim }

  /** 插件相对路径（./ 前缀）→ 插件根内绝对路径；越界/非法形态 → None（§4.1）。 */
  private def resolvePluginRelative(dir: os.Path, rel: String): Option[os.Path] =
    if !rel.startsWith("./") then None
    else
      try
        val sub = os.SubPath(rel.stripPrefix("./"))
        Some(dir / sub)
      catch case _: Exception => None

  // ── 信任表读写（nebflow.json plugins.trust）──────────────────

  private case class TrustRecord(sha256: String, approvedAt: Long, files: Option[Map[String, String]])

  private def trustRecord(name: String): Option[TrustRecord] =
    readTrustTable().get(name).flatMap { j =>
      for
        sha <- j.hcursor.downField("sha256").as[String].toOption
        at = j.hcursor.downField("approvedAt").as[Long].toOption.getOrElse(0L)
        files = j.hcursor.downField("files").as[Map[String, String]].toOption
      yield TrustRecord(sha, at, files)
    }

  /** 信任记录落库 digest（approve 时刻的目录 fingerprint；之后目录漂移不影响记录本身）。
    * 与 TrustStatus（现算状态；无审批批 2026-09-13 后在位即受信、漂移只降级为
    * `contentChanged` 可见性）互补——需要「approve 时刻基准」做对比仲裁的场景
    * （seed reconcile 判「用户是否改过」）用本方法。 */
  def trustRecordDigest(name: String): Option[String] =
    trustRecord(name).map(_.sha256)

  private def readTrustTable(): Map[String, Json] =
    val configPath = PathUtil.configJsonReadPath(PathUtil.dataRoot)
    if !os.exists(configPath) then Map.empty
    else
      io.circe.parser.parse(os.read(configPath)).toOption
        .flatMap(_.hcursor.downField("plugins").downField("trust").as[Map[String, Json]].toOption)
        .getOrElse(Map.empty)

  /** 审批记录写入（**无审批批后不再决定装载**，2026-09-13）：计算当前 digest +
    * 逐文件快照写入 trust 表。两个用途：① 审计（面板 `changeSummary` 逐文件 diff 的
    * 基准）；② **seed 覆盖仲裁基准**（`trustRecordDigest` ⇒ `SeedService.reconcilePlugin`
    * 的「干净快照」判据）。调用点：seed 首装 / 种子镜像刷新 / 面板 / REST / CLI。
    *
    * ⚠️ **已知代价（本批不修，设计 §3.3 ① / C5 登记）**：审批记录 = 仲裁基准 ⇒ 任何
    * approve 都会把基准前移到当前 digest，从而改变 seed reconcile 的判定结果（面板/CLI
    * 手动 approve 一个用户改过的默认集包，会让下一次 boot 的种子镜像覆盖视为「干净」）。
    * 无审批批下 approve 不再是必经动作，此风险面因此收窄但未消失。 */
  def approve(name: String): IO[Either[String, String]] =
    scan().flatMap { all =>
      all.find(_.name == name) match
        case None => IO.pure(Left(s"Plugin '$name' not found — nothing to record"))
        case Some(p) =>
          val now = System.currentTimeMillis() / 1000L
          val files = fileManifest(os.Path(p.dir))
          writeTrustEntry(name, Json.obj(
            "sha256" -> p.digest.asJson, "approvedAt" -> now.asJson, "scope" -> "all".asJson,
            "files" -> files.asJson
          )).map {
            case Right(_) =>
              cache.set(None) // 强制下个访问重扫 → contentChanged / changeSummary 刷新
              logger.infoSync(s"Plugin '$name' audit record refreshed (digest ${p.digest.take(12)}…, ${p.fileCount} file(s), ${files.size} file snapshot(s))")
              Right(s"Plugin '$name' audit record recorded — digest ${p.digest.take(16)}… (version ${p.version}); " +
                "loading is not gated by records any more (presence = trust), this record feeds the panel change summary and the seed-reconcile baseline")
            case l => l.map(_ => "")
          }
    }

  /** 手动清缓存（测试钩子）。 */
  def invalidateCache(): Unit = cache.set(None)

  /** §B.3 外部导入（协议符合度批补齐；无审批批 2026-09-13 口径更新）：
    * `nebflow plugin add <git-url|本地路径>` → clone/copy 进 `~/.nebflow/plugins/<manifest name>/`
    * → **落盘即生效**（在位即信任：下个扫描周期即可派发，无需审批动作）。
    * 同名已存在 → 拒绝（不覆盖）。
    * git 来源（http(s) 开头、git@ 开头或以 .git 结尾）走 `git clone --depth 1`；
    * 其余按本地目录 copy。
    */
  def installFrom(source: String): IO[Either[String, String]] =
    IO.blocking(installFromSync(source))

  private def installFromSync(source: String): Either[String, String] =
    val tmp = os.temp.dir(prefix = "nb-plugin-install")
    try
      val isGit = source.startsWith("https://") || source.startsWith("http://") ||
        source.startsWith("git@") || source.endsWith(".git")
      val staged = tmp / "repo"
      if isGit then
        val res = os.proc("git", "clone", "--depth", "1", source, staged).call(check = false)
        if res.exitCode != 0 then
          val errTail = scala.util.Try(res.err.text()).toOption.getOrElse("")
            .linesIterator.toList.takeRight(3).mkString("; ")
          return Left(s"git clone failed (exit ${res.exitCode}): $errTail")
      else
        val src = os.Path(source, os.pwd)
        if !os.isDir(src) then return Left(s"source is not a directory: $source")
        // ── 点路径 / 祖先路径守卫（2026-09-13 批，#344 点路径）────────────────
        // 规范化后源 == cwd 自身（`.`、`./`、cwd 的绝对形式）或为 cwd 的祖先 ⇒ os.copy
        // 会把**整个工作目录**递归拷进临时目录（磁盘/耗时放大；非破坏性且 finally 清理，
        // 但退化）。字符串空判（CLI 侧 isEmpty）盖不住点路径 ⇒ 判据 = canonical 路径比对。
        if isCwdOrAncestor(src) then
          return Left(
            s"refusing to install from '$source': it resolves to the current working directory (${os.pwd}) or one of its " +
              s"parents — installing it would recursively copy the whole workspace into a temp dir. Pass the plugin package " +
              s"itself: a subdirectory (e.g. 'nebflow plugin add ./my-plugin') or an absolute path (e.g. '/path/to/my-plugin').")
        os.copy(src, staged, createFolders = true, mergeFolders = true, replaceExisting = true)

      // manifest name 为准（§5.5 校验复用装载规则）
      val manifest = staged / "plugin.json"
      if !os.isFile(manifest) then return Left("source has no plugin.json manifest — not a plugin package")
      io.circe.parser.parse(os.read(manifest)) match
        case Left(err) => Left(s"source plugin.json unparseable: ${err.message}")
        case Right(json) =>
          val name = json.hcursor.downField("name").as[String].toOption.map(_.trim).filter(_.nonEmpty)
          name.filter(validPluginName) match
            case None =>
              Left("source manifest 'name' missing or violates §5.5 constraints — refusing to install")
            case Some(pname) =>
              val target = pluginsDir / pname
              if os.exists(target) then
                Left(s"Plugin '$pname' already exists at $target — refusing to overwrite (remove it first)")
              else
                os.makeDir.all(pluginsDir)
                os.copy(staged, target, createFolders = true, mergeFolders = true, replaceExisting = true)
                cache.set(None)
                logger.infoSync(s"Plugin '$pname' installed from '$source' — active on next scan (presence = trust)")
                Right(s"Plugin '$pname' installed to $target — active on the next scan (presence = trust: no approval step). " +
                  s"Block it if intended: Plugin panel, REST POST /api/plugins/$pname/revoke, or CLI 'nebflow plugin revoke $pname'.")
    finally
      try os.remove.all(tmp)
      catch case _: Exception => ()

  /** canonical 化后 `src` == cwd 自身或为 cwd 的**祖先** ⇒ true（该源一旦拷贝就是整个 cwd 树）。
    *
    * 规范化口径（2026-09-13 批，#344）：主判据 = `toRealPath` —— **两侧都先 canonical 化**
    * （相对基准 = `os.pwd`，因为 `os.Path(source, os.pwd)` 以 cwd 为基准解析），因此
    * 符号链接（macOS `/tmp` → `/private/tmp`）与 `.`/`..`/重复分隔符都被消除后再比对；
    * `toRealPath` 失败（权限/竞态等 IO 异常）回落 `normalize`（纯词法，仍消除
    * `.`/`./`/`..`，不解符号链接）⇒ 退化形态（点路径）仍被拦。两侧都拿不到 canonical
    * 形态（极端 IO 异常）⇒ **不拦**（保持既有行为：非破坏性拷贝 + `finally` 清理）。
    * 字符串空判（`source.isEmpty`，CLI 侧既有守卫）与 canonical 比对是两层：点路径非空串。
    * `os.Path("", os.pwd)`（空串形态，CLI 侧不可达）在规范化后同样命中本判据——那是同一
    * 判据的自然覆盖面，不是另立的第二道守卫。 */
  private[plugin] def isCwdOrAncestor(src: os.Path): Boolean =
    (for
      cwd <- canonicalPath(os.pwd)
      s <- canonicalPath(src)
    yield cwd == s || cwd.startsWith(s)).getOrElse(false)

  private def canonicalPath(p: os.Path): Option[java.nio.file.Path] =
    try Some(p.toNIO.toRealPath())
    catch
      case _: Exception =>
        try Some(p.toNIO.normalize())
        catch case _: Exception => None

  private def writeTrustEntry(name: String, entry: Json): IO[Either[String, Unit]] =
    IO.blocking {
      mutateNebflowJson { root =>
        val plugins = root.hcursor.downField("plugins").focus.getOrElse(Json.obj())
        val trust = plugins.hcursor.downField("trust").focus.getOrElse(Json.obj())
        val newTrust = Json.fromJsonObject(
          trust.asObject.getOrElse(JsonObject.empty).add(name, entry)
        )
        val newPlugins = Json.fromJsonObject(
          plugins.asObject.getOrElse(JsonObject.empty).add("trust", newTrust)
        )
        Json.fromJsonObject(root.asObject.getOrElse(JsonObject.empty).add("plugins", newPlugins))
      }
    }

  /** nebflow.json 手术式改写：读全量 → transform → 原子写回（保留全部其他键）。 */
  private def mutateNebflowJson(transform: Json => Json): Either[String, Unit] =
    val configPath = PathUtil.configJsonWritePath(PathUtil.dataRoot)
    if !os.exists(configPath) then
      // 首次写：只落 plugins.trust 骨架（不伪造其他配置键）
      AtomicJson.writeSync(configPath, transform(Json.obj()).noSpaces)
      Right(())
    else
      io.circe.parser.parse(os.read(configPath)) match
        case Left(err) => Left(s"nebflow.json unparseable — refusing to rewrite for trust update: ${err.message}")
        case Right(root) =>
          AtomicJson.writeSync(configPath, transform(root).noSpaces)
          Right(())

  // ── 装载可见性（P1 静默缩容，2026-09-10 可见性批；无审批批 2026-09-13 口径更新）───
  // 背景：整包拒载 / 封禁 → 目录静默缩容，作者侧无任何聚合信号。本节**只加可见性**：
  // 不改装载校验、不改内容面判定、不改目录过滤链。「内容已变更」在无审批批下不再是
  // 缺席（不拦装载）⇒ 从缺席分类移到独立的**非拦截可见性**段（目录段尾注记 +
  // 健康摘要各一行 + API 字段 `contentChanged`）。

  /** 目录缺席分类（可见性口径，不参与任何装载/内容面判定）。无审批批后只剩两类：
    * 装载失败 + 封禁——「从未审批」「digest 漂移」两个分类随 default-deny 一起消亡。 */
  enum AbsenceKind(val label: String):
    /** 装载失败：manifest/校验拒载（§B.2 装载校验）。 */
    case LoadFailed extends AbsenceKind("装载失败")
    /** 封禁：命中 `plugins.revoked` deny-list（不进目录 / 拒装载 / 停飞）。 */
    case Blocked extends AbsenceKind("已封禁")

  /** 一条缺席记录：包名 + 分类 + 原因（原因文本与注册表/拒载消息同源，不另造文案）。 */
  final case class Absence(name: String, kind: AbsenceKind, reason: String)

  /** 缺席清单 = 在 plugins/ 下存在、但不进 Plugin Catalog 的包：装载失败 + 封禁。
    * 分类依据权威来源（拒载左值 / 封禁表命中），不做原因字符串匹配。 */
  private def absencesOf(snap: Snapshot): List[Absence] =
    val rejected = snap.rejected.sortBy(_._1).map { case (n, r) =>
      Absence(n, AbsenceKind.LoadFailed, r)
    }
    val blocked = snap.plugins.filterNot(_.trust.trusted).sortBy(_.name).map { p =>
      Absence(p.name, AbsenceKind.Blocked, blockedReason(p))
    }
    rejected ++ blocked

  private def blockedReason(p: PluginDef): String = p.trust match
    case TrustStatus.Blocked(at, by, reason) =>
      val who = if by.nonEmpty then s" by $by" else ""
      val why = if reason.nonEmpty then s": $reason" else ""
      s"blocked (deny-list, recorded at $at$who)$why"
    case TrustStatus.Trusted(_, _) => "" // 调用点已按 trust.trusted 过滤

  /** 「内容已变更」包名单（非拦截可见性）：装载成功、有审批记录、digest 与记录不符。 */
  private def contentChangedNames(snap: Snapshot): List[String] =
    snap.plugins.filter(_.contentChanged).map(_.name).sorted

  /** 日志用分类 slug（英文，与既有 plugin 日志行文一致）。 */
  private def kindSlug(k: AbsenceKind): String = k match
    case AbsenceKind.LoadFailed => "load-failed"
    case AbsenceKind.Blocked => "blocked"

  /** 目录缺席注记（段尾聚合，唯一实现）：只出**非零**分类计数——本注记进分发器
    * prompt（Token 经济优先），包名+原因清单由启动健康摘要落日志。零缺席 → ""。 */
  private def absenceNote(absences: List[Absence]): String =
    if absences.isEmpty then ""
    else
      val counts = AbsenceKind.values.toList
        .filter(k => absences.count(_.kind == k) > 0)
        .map(k => s"${k.label} ${absences.count(_.kind == k)}")
      s"另有 ${absences.size} 个插件未载入（${counts.mkString(" / ")}）"

  /** 「内容已变更」目录段尾注记（非拦截可见性，C2 ②）：**点名**列出（内容被替换后
    * 用户/审计需要能指名核对）。无变更 → ""。
    * （2026-09-14 面板收敛批：原文「与『已关闭·禁派发』注记同款口径」已失效——那条
    * 注记已删除，被关插件在目录里完全不可见；本注记是段尾**唯一**的点名行。） */
  private def contentChangedNote(changed: List[String]): String =
    if changed.isEmpty then ""
    else
      s"另有 ${changed.size} 个插件内容与上次记录的版本不同（**不拦截装载**，仅提示核对）：" +
        changed.mkString(", ")

  /** 启动/重扫健康摘要（P1 可见性 + 无审批批非拦截可见性）：首行 = 总包数 / 载入数 /
    * 目录可见数 / 缺席分类计数，随后逐条缺席明细（一行一条 `[分类] 包名: 原因`）与逐条
    * 内容变更明细（`[content-changed] 包名: 记录 digest → 当前 digest`）。
    * 零缺席且零内容变更 → None（干净场景零噪音）；`plugins.enabled=false` → None。 */
  def healthSummary(): IO[Option[String]] =
    PluginsConfig.enabled.flatMap {
      case false => IO.pure(None)
      case true => snapshot().flatMap(snap => IO.blocking(healthSummaryOf(snap)))
    }

  private def healthSummaryOf(snap: Snapshot): Option[String] =
    val absences = absencesOf(snap)
    val changed = snap.plugins.filter(_.contentChanged).sortBy(_.name)
    if absences.isEmpty && changed.isEmpty then None
    else
      val visible = snap.plugins.count(_.trust.trusted)
      val counts = AbsenceKind.values.toList
        .filter(k => absences.count(_.kind == k) > 0)
        .map(k => s"${kindSlug(k)} ${absences.count(_.kind == k)}").mkString(" / ")
      val head =
        s"${snap.plugins.size + snap.rejected.size} package(s) on disk, ${snap.plugins.size} loaded, " +
          s"$visible catalog-visible, ${absences.size} absent" +
          (if counts.nonEmpty then s" ($counts)" else "") +
          (if changed.nonEmpty then s", ${changed.size} content-changed" else "")
      // 内容变更明细：审批记录 digest → 当前 digest（两个短前缀，够人工核对）；
      // 「不拦截」必须与「已停飞」在文案上可区分（本行不做任何拦截）。
      val changedDetails = changed.map { p =>
        val rec = trustRecord(p.name).map(_.sha256.take(12) + "…").getOrElse("(no record)")
        s"  [content-changed] ${p.name}: recorded $rec → current ${p.digest.take(12)}… " +
          "(loaded, not intercepted — re-record the baseline if the change is intended)"
      }
      val details = absences.map(a => s"  [${kindSlug(a.kind)}] ${a.name}: ${a.reason}") ++ changedDetails
      Some((head :: details).mkString("\n"))

  /** 健康摘要输出记账：同状态只出一次（重扫 tick 30s 一次，不重复刷屏），状态变化
    * 后重新输出，回到干净后复位（异常复现可再出）。 */
  private val healthLogState =
    new java.util.concurrent.atomic.AtomicReference[(Int, Option[String])]((0, None))

  /** 输出健康摘要（有异常才出，WARN 级；best-effort——调用方自担错误兜底）。
    * 挂接点：GatewayMain 启动（trigger "startup"）与插件信任重扫完成
    * （NodeEngine.revalidatePluginTrust，TtlTick 30s，trigger "rescan"）。 */
  def logHealthSummary(trigger: String): IO[Unit] =
    healthSummary().flatMap {
      case None =>
        IO.delay {
          val (n, last) = healthLogState.get()
          if last.isDefined then healthLogState.set((n, None))
        }
      case Some(body) =>
        IO.delay {
          val (n, last) = healthLogState.get()
          if last != Some(body) then
            healthLogState.set((n + 1, Some(body)))
            logger.warnSync(s"plugin health[$trigger]:\n$body")
        }
    }

  /** 健康摘要输出记账（测试钩子）：(已输出次数, 最近一次正文)。 */
  def healthSummaryLogStateForTest: (Int, Option[String]) = healthLogState.get()

  // ── 分发器目录注入（§B.4 第 2 步；描述单源批 2026-09-10 双渲染器收敛）─────

  /** Plugin Catalog 段头——双渲染器单点：分发器注入段（DispatcherContextCatalog
    * 委托本文件）与调试 REST GET /plugins/catalog 同字节输出（creator spec D10
    * 收敛）；分发器 system.md「Plugin Catalog 认知」按此头部识别目录段。 */
  val CatalogHeader =
    "# Plugin Catalog（可分配能力包，NodeEdit 的 plugins 参数按 name 引用；能力句 = 该插件让节点具备什么能力）"

  /** 单插件目录行（渲染规则单点）。描述单源批（作者 2026-09-10 09:30 裁定）：
    * 内容源 = manifest `description`（缺省回落 name）；`capability` 键已 deprecated，
    * 渲染层忽略。尾缀保留 [skills | mcp | tools] 结构清单——工具面本身是能力信号。 */
  def catalogLine(p: PluginDef): String =
    val desc = if p.description.isEmpty then p.name else p.description
    val skills = if p.skills.isEmpty then "-" else p.skills.map(s => s.id.split('/')(1)).mkString(", ")
    val mcp = if p.mcpServers.isEmpty then "-" else p.mcpServers.keys.mkString(", ")
    val tools = if p.toolsExtension.isEmpty then "" else s" | tools: ${p.toolsExtension.mkString(", ")}"
    s"- ${p.name}: $desc [skills: $skills | mcp: $mcp$tools]"

  /** Plugin Catalog 段（对齐 skillCatalog order 800 先例）。**被封禁**的包不出现
    * （内容面判定第一段过滤，无审批批 2026-09-13 后 = 「装载成功 ∧ 未被封禁」）。
    * 空段判定（可见性批改口径）：**无缺席注记时**才可能为空——无可用插件且无缺席包
    * （盘上无插件）/ flag 关 → ""；若一个插件都没进目录但盘上有缺席包，则只注入段头 +
    * 缺席注记（目录缩容到 0 也不许无声）。本方法是插件目录渲染的唯一实现（分发器注入与
    * 调试预览共用，双渲染器重复实现已收敛于此）。
    *
    * 段尾注记两类（都不是插件行）：缺席（装载失败/封禁）+ **内容已变更**（非拦截可见性，
    * 包**仍在本目录**里，只是加一行提示——不得与缺席注记混读）。
    * 2026-09-14 面板收敛批（作者三裁之批一）：原第三类「已关闭·禁派发」点名注记**删除**
    * ——关闭的包在目录里**完全不可见**（能力行与点名行皆无）；S5 过滤效果不变。 */
  def renderCatalog(): IO[String] =
    PluginsConfig.enabled.flatMap {
      case false => IO.pure("")
      case true =>
        snapshot().flatMap { snap =>
          IO.blocking {
            val trusted = snap.plugins.filter(_.trust.trusted).sortBy(_.name)
            // 令 1 拆面（2026-09-12）：**派发许可面**只在此过滤链生效——内容面可用
            // 但被作者关闭的包不再出现在分发给新节点的目录里（S5：关闭后新派发拿不到）；
            // 但它**仍可用**⇒ 闸 B/C/E/D 不受影响（在飞/已派发节点照跑）。
            // 零 dispatch 记录时本过滤恒全通过 ⇒ 本方法输出与改前逐字节相同（零迁移）。
            // 2026-09-14 面板收敛批（作者三裁之批一）：**段尾「已关闭·禁派发」点名注记
            // 删除**——被关插件在本目录里**完全不可见**（既无能力行、也无点名行）。
            // 行过滤逻辑本身不变（关闭仍使该包的能力行消失 = S5 既有效果）。
            val dispatchable =
              trusted.filter(p => PluginDispatchPolicy.effective(p.name, trusted = true))
            val note = absenceNote(absencesOf(snap))
            // 内容已变更（非拦截可见性）：行**不消失**，只在段尾点名提示。
            val changedNote = contentChangedNote(trusted.filter(_.contentChanged).map(_.name).sorted)
            val lines =
              dispatchable.map(catalogLine) ++
                Option.when(note.nonEmpty)(note) ++
                Option.when(changedNote.nonEmpty)(changedNote)
            if lines.isEmpty then "" else CatalogHeader + "\n" + lines.mkString("\n")
          }
        }
    }
end PluginRegistry
