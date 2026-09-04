package nebflow.core.plugin

import cats.effect.IO
import cats.syntax.all.*
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import nebflow.core.{AtomicJson, NebflowLogger, PathUtil}
import nebflow.llm.McpServerConfig

import java.security.MessageDigest
import scala.collection.mutable

/**
 * PluginRegistry —— 插件注册表与信任门（阶段 2b §B.2/§B.3，蓝图唯一权威）。
 *
 * 目录格式（Agent Plugins 1.0.0 对齐，§B.2）：
 * {{{
 * ~/.nebflow/plugins/<name>/
 *   plugin.json            根级 manifest：{"$schema":"https://agent-plugins.org/schema/1.0.0","name":..,"version":..,"description":..}
 *   skills/<skill>/SKILL.md  可选——skill id = <plugin>/<skill>（两级 namespace 先例）
 *   mcp.json               可选——{"$schema":..,"mcpServers":{"<server>":{command|url,...}}}
 *   org.nebflow/tools.json 可选——nebflow 扩展命名空间：{"tools":["WebSearch",...]}
 * }}}
 *
 * 装载校验（裁定 12 / §B.8-7）：skills/ 与 mcp.json 至少其一，全无 → 拒载+告警；
 * manifest 缺 name → 拒载；未知字段/目录 → 宽容忽略+告警（§B.8-5 前向兼容）。
 *
 * 信任门（裁定 14 / §B.3）：默认拒绝。审批记录存 nebflow.json
 * `plugins.trust.<name> = {"sha256":<目录内容树 digest>,"approvedAt":..,"scope":"all"}`。
 * 目录内容 digest ≠ 审批记录（含 version bump / 任一文件改动）→ untrusted——
 * 「升级即重审」，堵住先审批后改内容的绕过（§B.8-3）。untrusted plugin：
 * 不进分发器目录、不可被分配、MCP 不启动、skill 不注入。
 *
 * 扫描节奏（§B.3）：进程内 mtime 缓存——目录树任一 mtime 变化即全量重扫
 * （对齐 skill「改后即时生效」机制）；spawn/NodeEdit 校验路径每次走 list/resolve
 * 天然新鲜。
 */
object PluginRegistry:

  private val logger = NebflowLogger.forName("nebflow.plugin")

  /** 插件根目录（rebrand/测试 setDataRoot 均生效）。 */
  private def pluginsDir: os.Path = PathUtil.dataRoot / "plugins"

  /** §B.6 固定安全集：plugin 只能授予既有 builtin 工具且限于白名单——
    * 不能发明新工具、不能授予编排类（Task/Mail/NodeEdit 等永不进白名单，
    * 角色边界由 §C.1 静态矩阵守住）。 */
  val BuiltinToolWhitelist: Set[String] = Set("WebSearch", "WebFetch", "Curl", "Pop")

  /** canonical $schema（Agent Plugins 1.0.0）。非 canonical → 告警不拒载（宽容）。 */
  val CanonicalSchema = "https://agent-plugins.org/schema/1.0.0"

  // ── 数据模型 ──────────────────────────────────────────────

  final case class PluginSkill(
    id: String, // <plugin>/<skill>
    name: String,
    description: String,
    path: String // SKILL.md 绝对路径
  )

  sealed trait TrustStatus extends Product with Serializable:
    def trusted: Boolean
  object TrustStatus:
    final case class Trusted(approvedAt: Long, digest: String) extends TrustStatus:
      val trusted = true
    final case class Untrusted(reason: String) extends TrustStatus:
      val trusted = false

  /** 注册表条目（§B.3 产出结构）。 */
  final case class PluginDef(
    name: String,
    version: String,
    description: String,
    author: String,
    skills: List[PluginSkill],
    mcpServers: Map[String, McpServerConfig],
    toolsExtension: List[String],
    digest: String,
    fileCount: Int,
    warnings: List[String],
    trust: TrustStatus,
    dir: String
  )

  /** 审批清单条目（§B.3 面板渲染数据源）：元信息 + skills 摘要 + mcp（env 键名打码值）
    * + tools 申请 + 信任状态。 */
  def approvalManifest(p: PluginDef): Json =
    Json.obj(
      "name" -> p.name.asJson,
      "version" -> p.version.asJson,
      "description" -> p.description.asJson,
      "author" -> p.author.asJson,
      "digest" -> p.digest.asJson,
      "fileCount" -> p.fileCount.asJson,
      "trust" -> (p.trust match
        case TrustStatus.Trusted(at, d) => Json.obj("status" -> "trusted".asJson, "approvedAt" -> at.asJson, "digest" -> d.asJson)
        case TrustStatus.Untrusted(reason) => Json.obj("status" -> "untrusted".asJson, "reason" -> reason.asJson)
      ),
      "skills" -> p.skills
        .map(s => Json.obj("id" -> s.id.asJson, "description" -> s.description.asJson, "preview" -> previewLines(s.path).asJson))
        .asJson,
      "mcpServers" -> p.mcpServers.map { case (n, c) =>
        Json.obj(
          "server" -> n.asJson,
          "transport" -> (if c.command.isDefined then "stdio" else if c.url.isDefined then "url" else "none").asJson,
          "command" -> c.command.asJson,
          "args" -> c.args.asJson,
          "url" -> c.url.asJson,
          "envKeys" -> c.env.map(_.keys.toList).getOrElse(Nil).asJson // 值打码：只出键名（§B.3 红标项）
        )
      }.asJson,
      "toolsExtension" -> p.toolsExtension.asJson,
      "warnings" -> p.warnings.asJson
    )

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

  private def relPath(root: os.Path, f: os.Path): String =
    f.relativeTo(root).toString

  // ── 扫描 ──────────────────────────────────────────────

  /** mtime 缓存（进程内）：目录树（根 + 子树全部目录**与文件**）的绝对路径 mtime
    * 快照。文件内容修改只变文件自身 mtime（父目录 mtime 不动）→ 签名必须含文件，
    * 否则「改动即重审」（§B.8-3）会在缓存命中路径上漏检。键 = 绝对路径。 */
  private case class Cache(sig: List[(String, Long)], plugins: List[PluginDef])
  private val cache = new java.util.concurrent.atomic.AtomicReference[Option[Cache]](None)

  private def treeSig(dir: os.Path): List[(String, Long)] =
    try
      val all = os.walk(dir).toList
      (dir :: all).map(p => p.toString -> os.mtime(p)).sortBy(_._1)
    catch case _: Exception => Nil

  /** 全量注册表（含 untrusted——审批清单/面板需要看到待审插件）。 */
  def scan(): IO[List[PluginDef]] =
    IO.blocking {
      if !os.isDir(pluginsDir) then Nil
      else
        val subDirs = os.list(pluginsDir).filter(os.isDir).toList.sortBy(_.last)
        val full = treeSig(pluginsDir)
        cache.get match
          case Some(c) if c.sig == full => c.plugins
          case _ =>
            val loaded = subDirs.map(d => loadPlugin(d))
            val defs = loaded.collect { case Right(p) => p }
            val rejected = loaded.collect { case Left((n, r)) => n -> r }
            rejected.foreach { case (n, r) => logger.warnSync(s"Plugin '$n' rejected: $r") }
            cache.set(Some(Cache(full, defs)))
            defs
    }

  /** 审批清单渲染数据：全部插件（含拒载原因）。 */
  def listWithRejected(): IO[(List[PluginDef], List[(String, String)])] =
    IO.blocking {
      if !os.isDir(pluginsDir) then (Nil, Nil)
      else
        val subDirs = os.list(pluginsDir).filter(os.isDir).toList.sortBy(_.last)
        val loaded = subDirs.map(d => loadPlugin(d))
        (loaded.collect { case Right(p) => p }, loaded.collect { case Left((n, r)) => n -> r })
    }

  /** 解析单个插件（信任门校验入口）。Left = 分配失败原因（含审批指引）。 */
  def resolve(name: String): IO[Either[String, PluginDef]] =
    scan().map { all =>
      all.find(_.name == name) match
        case None =>
          Left(s"Plugin '$name' not found in registry (scan ${all.size} plugin(s)). " +
            "Check the directory name under ~/.nebflow/plugins/. (PLUGIN_NOT_FOUND)")
        case Some(p) if !p.trust.trusted =>
          val reason = p.trust match
            case TrustStatus.Untrusted(r) => r
            case _ => "untrusted"
          Left(s"Plugin '$name' is NOT approved (untrusted: $reason). " +
            s"Trust gate is default-deny (§B.3): approve it via the Plugin panel, " +
            s"REST POST /api/plugins/$name/approve, or CLI 'nebflow plugin approve $name' — " +
            "approval records the directory digest; any later file change re-triggers review. (PLUGIN_UNTRUSTED)")
        case Some(p) => Right(p)
    }

  // ── 单插件装载（§B.2 装载校验规则）──────────────────────────

  private def loadPlugin(dir: os.Path): Either[(String, String), PluginDef] =
    val name0 = dir.last
    val warnings = mutable.ListBuffer[String]()

    // 未知顶层条目 → 宽容跳过 + 告警（§B.8-5）
    val known = Set("plugin.json", "mcp.json", "skills", "org.nebflow")
    os.list(dir).foreach { e =>
      val n = e.last
      if !known.contains(n) then warnings += s"ignored unknown entry '$n' (forward-compat: skipped)"
      else if n == "org.nebflow" && os.isDir(e) then
        os.list(e).foreach { f => if f.last != "tools.json" then warnings += s"ignored unknown org.nebflow entry '${f.last}'" }
    }

    // manifest（plugin.json 必需；缺 name → 拒载）
    val manifestPath = dir / "plugin.json"
    if !os.isFile(manifestPath) then return Left(name0 -> "missing plugin.json manifest")
    io.circe.parser.parse(os.read(manifestPath)) match
      case Left(err) => return Left(name0 -> s"plugin.json unparseable: ${err.message}")
      case Right(json) =>
        val c = json.hcursor
        val schema = c.downField("$schema").as[String].toOption.getOrElse("")
        if schema.nonEmpty && schema != CanonicalSchema then
          warnings += s"non-canonical ${"$"}schema '$schema' (expected $CanonicalSchema)"
        val name = c.downField("name").as[String].toOption.map(_.trim).filter(_.nonEmpty)
        if name.isEmpty then return Left(name0 -> "manifest missing required field 'name'")
        val pname = name.get
        // 目录名 ≠ manifest name → 以 manifest 为准并告警（防止引用歧义）
        if pname != name0 then warnings += s"manifest name '$pname' differs from directory name '$name0' — using manifest name"
        // 未知 manifest 字段 → 宽容忽略 + 告警（§B.8-5 前向兼容：2b 后新增字段不炸旧版）
        val knownManifestKeys = Set("$schema", "name", "version", "description", "author", "extensions")
        json.asObject.foreach { obj =>
          obj.keys.filterNot(knownManifestKeys.contains).foreach(k =>
            warnings += s"ignored unknown manifest field '$k' (forward-compat: skipped)")
        }
        val version = c.downField("version").as[String].toOption.getOrElse("")
        if version.isEmpty then warnings += "manifest has no version field"
        val description = c.downField("description").as[String].toOption.getOrElse("")
        val author = c.downField("author").as[String].toOption.getOrElse("")

        // skills/（一级：<skill>/SKILL.md；id = <plugin>/<skill>）
        val skillsDir = dir / "skills"
        val skills: List[PluginSkill] =
          if !os.isDir(skillsDir) then Nil
          else
            os.list(skillsDir).filter(os.isDir).toList.sortBy(_.last).flatMap { sd =>
              resolveSkillFile(sd) match
                case Some(f) =>
                  val raw = os.read(f)
                  val fm = frontmatter(raw)
                  Some(PluginSkill(
                    id = s"$pname/${sd.last}",
                    name = extractField(fm, "name").getOrElse(sd.last),
                    description = extractField(fm, "description").getOrElse(""),
                    path = f.toString
                  ))
                case None =>
                  warnings += s"skills/${sd.last} has no SKILL.md — skipped"
                  None
            }

        // mcp.json（{"$schema":..,"mcpServers":{...}}）
        val mcpServers: Map[String, McpServerConfig] =
          val mcpPath = dir / "mcp.json"
          if !os.isFile(mcpPath) then Map.empty
          else
            io.circe.parser.parse(os.read(mcpPath)) match
              case Left(err) =>
                warnings += s"mcp.json unparseable (${err.message}) — no MCP servers loaded"
                Map.empty
              case Right(mjson) =>
                mjson.hcursor.downField("mcpServers").as[Map[String, McpServerConfig]] match
                  case Right(m) => m
                  case Left(err) =>
                    warnings += s"mcpServers decode failed (${err.getMessage}) — no MCP servers loaded"
                    Map.empty

        // org.nebflow/tools.json（或 manifest extensions 指定路径）
        val toolsPath =
          val declared = c.downField("extensions").downField("org.nebflow/tools").as[String].toOption
          declared match
            case Some(rel) => Some(dir / os.RelPath(rel))
            case None =>
              val d = dir / "org.nebflow" / "tools.json"
              if os.isFile(d) then Some(d) else None
        val toolsExtension: List[String] = toolsPath match
          case None => Nil
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

        // 信任门（默认拒绝）：审批记录 digest 比对
        val trust = trustRecord(pname) match
          case None => TrustStatus.Untrusted("never approved (default-deny)")
          case Some(rec) =>
            if rec.sha256 == digest then TrustStatus.Trusted(approvedAt = rec.approvedAt, digest = digest)
            else TrustStatus.Untrusted(
              s"directory digest changed since approval (approved=${rec.sha256.take(12)}…, current=${digest.take(12)}…) — re-approval required (upgrade = re-review)")

        Right(PluginDef(
          name = pname,
          version = version,
          description = description,
          author = author,
          skills = skills,
          mcpServers = mcpServers,
          toolsExtension = toolsExtension,
          digest = digest,
          fileCount = fileCount,
          warnings = warnings.toList,
          trust = trust,
          dir = dir.toString
        ))
    end match
  end loadPlugin

  private def resolveSkillFile(dir: os.Path): Option[os.Path] =
    List("SKILL.md", "skill.md").map(dir / _).find(os.isFile)

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

  // ── 信任表读写（nebflow.json plugins.trust）──────────────────

  private case class TrustRecord(sha256: String, approvedAt: Long)

  private def trustRecord(name: String): Option[TrustRecord] =
    readTrustTable().get(name).flatMap { j =>
      for
        sha <- j.hcursor.downField("sha256").as[String].toOption
        at = j.hcursor.downField("approvedAt").as[Long].toOption.getOrElse(0L)
      yield TrustRecord(sha, at)
    }

  private def readTrustTable(): Map[String, Json] =
    val configPath = PathUtil.configJsonReadPath(PathUtil.dataRoot)
    if !os.exists(configPath) then Map.empty
    else
      io.circe.parser.parse(os.read(configPath)).toOption
        .flatMap(_.hcursor.downField("plugins").downField("trust").as[Map[String, Json]].toOption)
        .getOrElse(Map.empty)

  /** 审批：计算当前 digest 写入 trust 表（面板/CLI/REST 共用单点）。 */
  def approve(name: String): IO[Either[String, String]] =
    scan().flatMap { all =>
      all.find(_.name == name) match
        case None => IO.pure(Left(s"Plugin '$name' not found — nothing to approve"))
        case Some(p) =>
          val now = System.currentTimeMillis() / 1000L
          writeTrustEntry(name, Json.obj(
            "sha256" -> p.digest.asJson, "approvedAt" -> now.asJson, "scope" -> "all".asJson
          )).map {
            case Right(_) =>
              cache.set(None) // 强制下个访问重扫 → trust 状态刷新
              logger.infoSync(s"Plugin '$name' approved (digest ${p.digest.take(12)}…, ${p.fileCount} file(s))")
              Right(s"Plugin '$name' approved — digest ${p.digest.take(16)}… recorded (version ${p.version}); next spawn/allocation takes effect immediately")
            case l => l.map(_ => "")
          }
    }

  /** 撤审：删除 trust 表条目 → 下次重扫即 untrusted（运行中 MCP 由
    * PluginMcpManager.revalidate 停用）。 */
  def revoke(name: String): IO[Either[String, String]] =
    IO.blocking {
      if !trustRecord(name).isDefined then Left(s"Plugin '$name' has no approval record — nothing to revoke")
      else
        removeFromTrustTable(name) match
          case Right(_) =>
            cache.set(None)
            logger.infoSync(s"Plugin '$name' approval revoked — falls back to untrusted (default-deny)")
            Right(s"Plugin '$name' approval revoked — back to untrusted; running plugin MCP servers will be stopped at next trust revalidation")
          case l => l.map(_ => "")
    }

  /** 手动清缓存（测试钩子）。 */
  def invalidateCache(): Unit = cache.set(None)

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

  private def removeFromTrustTable(name: String): Either[String, Unit] =
    mutateNebflowJson { root =>
      val plugins = root.hcursor.downField("plugins").focus.getOrElse(Json.obj())
      val trust = plugins.hcursor.downField("trust").focus.getOrElse(Json.obj())
      val newTrust = Json.fromJsonObject(trust.asObject.getOrElse(JsonObject.empty).remove(name))
      val newPlugins = Json.fromJsonObject(
        plugins.asObject.getOrElse(JsonObject.empty).add("trust", newTrust)
      )
      Json.fromJsonObject(root.asObject.getOrElse(JsonObject.empty).add("plugins", newPlugins))
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

  // ── 分发器目录注入（§B.4 第 2 步）───────────────────────────

  /** Plugin Catalog 段（对齐 skillCatalog order 800 先例）。untrusted 不出现。
    * 无受信插件 / flag 关 → ""（不注入空段）。 */
  def renderCatalog(): IO[String] =
    PluginsConfig.enabled.flatMap {
      case false => IO.pure("")
      case true =>
        scan().map { all =>
          val trusted = all.filter(_.trust.trusted).sortBy(_.name)
          if trusted.isEmpty then ""
          else
            val lines = trusted.map { p =>
              val skills = if p.skills.isEmpty then "-" else p.skills.map(s => s.id.split('/')(1)).mkString(", ")
              val mcp = if p.mcpServers.isEmpty then "-" else p.mcpServers.keys.mkString(", ")
              val tools = if p.toolsExtension.isEmpty then "" else s" | tools: ${p.toolsExtension.mkString(", ")}"
              val desc = if p.description.isEmpty then p.name else p.description
              s"- ${p.name}: $desc [skills: $skills | mcp: $mcp$tools]"
            }
            "# Plugin Catalog（可分配能力包，NodeEdit 的 plugins 参数按 name 引用）\n" + lines.mkString("\n")
        }
    }
end PluginRegistry
