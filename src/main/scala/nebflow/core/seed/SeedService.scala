package nebflow.core.seed

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.{AtomicJson, NebflowLogger, PathUtil}
import nebflow.core.plugin.PluginRegistry
import nebflow.core.project.ProjectStore

import java.net.JarURLConnection
import scala.jdk.CollectionConverters.*

/**
 * SeedService —— 冷启动播种引擎（cold-start seed batch 2026-09-07 设计定稿实现）。
 *
 * 载体 = A：种子资源打包进 jar（`src/main/resources/seed/`），运行时从 classpath
 * 读取并复制到 fresh home。`build.sbt` 默认把 `src/main/resources` 打进 jar
 * （`unmanagedResources` 已含 VERSION/brand.conf 先例，resources 无排除），无需
 * 改安装脚本。
 *
 * 三件种子（三 keeper = Nebula 引擎自带 + 本服务补 project-dispatcher / general）：
 *  - 默认 agent：project-dispatcher + general（蒸馏版：去 preset 具名引用、去空 skills）
 *  - 系统插件：3 个主集（explorer-toolkit / design-spec / visual-report），
 *    预装 + trusted（复用 PluginRegistry.approve）
 *  - 默认通用项目：id=general，workspace=~/.nebflow/projects/general，启动前挂载
 *
 * 触发（§4.2）：gateway boot 装配点调用 `ensureSeeded()`，位置 = 项目挂载前。
 * 判定顺序：
 *  1. `projects/` 非空（≥1 个 project.json）→ 判定「已有用户数据」：只写/更新
 *     marker（记录当前 SeedVersion），不完整播种（防在 author 现役 home 误建 general）。
 *  2. else（fresh home）：marker 缺失 → 完整冷启动播种；marker.version < seedVersion
 *     → 升级 add-only 补种（每条 `!os.exists` 守卫，只补缺失文件）；>= → no-op。
 *
 * 幂等 + fail-soft（§4.3/§4.4）：每条目独立 try/catch，失败仅 WARN 跳过、不中断；
 * 任何已有文件绝不覆盖（用户编辑 > 种子）；全程 best-effort，never crash gateway。
 */
object SeedService:
  private val logger = NebflowLogger.forName("nebflow.core.seed")

  private val AgentsPrefix = "agents:"
  private val PluginsPrefix = "plugins:"
  private val ProjectPrefix = "project:"
  private val GeneralProjectName = "general"
  private val DataRootPlaceholder = "<DATA_ROOT>"

  // ── 公共入口 ─────────────────────────────────────────────
  /** 冷启动播种（幂等、add-only、best-effort）。失败绝不阻止 gateway 启动。 */
  def ensureSeeded(): IO[Unit] = IO.blocking {
    val root = PathUtil.dataRoot
    try
      val manifest = loadManifest()
      if hasExistingProjects(root) then
        // 已有用户数据：只记录 marker，不完整播种（防误建 general）
        writeMarker(root, manifest.seedVersion, Nil)
        logger.infoSync(
          s"Seed: existing user data detected (projects/ non-empty) — skipped full seeding, recorded marker v${manifest.seedVersion}"
        )
      else
        readMarker(root) match
          case None =>
            val items = runSeed(root, manifest)
            writeMarker(root, manifest.seedVersion, items)
            logger.infoSync(s"Seed: fresh home seeded ${items.size} item(s) (v${manifest.seedVersion})")
          case Some(m) if m.version < manifest.seedVersion =>
            val items = runSeed(root, manifest)
            writeMarker(root, manifest.seedVersion, items)
            logger.infoSync(s"Seed: upgraded v${m.version} → v${manifest.seedVersion} (${items.size} item(s) added)")
          case _ => () // marker.version >= seedVersion → no-op
    catch
      case e: Exception =>
        // 种子全程 best-effort：单点失败绝不阻止 gateway 启动（与 startupMount fail-soft 同构）
        logger.warnSync(s"Seed skipped due to failure: ${e.getMessage}")
  }

  // ── 触发 / fresh-home 守卫 ───────────────────────────────
  /** 判定「已有用户数据」：projects/ 目录存在且含 ≥1 个含 project.json 的子目录。 */
  private def hasExistingProjects(root: os.Path): Boolean =
    val projectsDir = root / "projects"
    os.isDir(projectsDir) && os.list(projectsDir).exists { d =>
      os.isDir(d) && os.exists(d / "project.json")
    }

  // ── 播种 ─────────────────────────────────────────────────
  /** 逐条播种，返回「本轮实际写入」的 item id（add-only 语义：已存在→不计入）。 */
  private def runSeed(root: os.Path, manifest: SeedManifest): List[String] =
    manifest.items.flatMap { id =>
      if seedItem(root, id) then Some(id) else None
    }

  private def seedItem(root: os.Path, id: String): Boolean =
    try
      if id.startsWith(AgentsPrefix) then seedAgent(root, id.stripPrefix(AgentsPrefix))
      else if id.startsWith(PluginsPrefix) then seedPlugin(root, id.stripPrefix(PluginsPrefix))
      else if id.startsWith(ProjectPrefix) then seedProject(root, id.stripPrefix(ProjectPrefix))
      else
        logger.warnSync(s"Seed: unknown item id '$id' — skipping")
        false
    catch
      case e: Exception =>
        logger.warnSync(s"Seed: item '$id' failed: ${e.getMessage}")
        false

  /** agent 条目：写 seed/agents/<name>/{agent.json,system.md} 到 ~/.nebflow/agents/<name>/。
    * agent.json 与 system.md 各自独立 `!os.exists` 守卫（add-only：只补缺失文件）。 */
  private def seedAgent(root: os.Path, name: String): Boolean =
    val targetDir = root / "agents" / name
    val jsonWrote = writeIfAbsent(targetDir / "agent.json", readResource(agentResource(name, "agent.json")))
    val mdWrote = writeIfAbsent(targetDir / "system.md", readResource(agentResource(name, "system.md")))
    if jsonWrote || mdWrote then
      logger.infoSync(s"Seed: wrote agent '$name' (agent.json=$jsonWrote, system.md=$mdWrote)")
    jsonWrote || mdWrote

  /** 插件条目：整目录复制 seed/plugins/<name>/ → ~/.nebflow/plugins/<name>/，首次装即 trusted。
    * 目标目录已存在 → 跳过（绝不覆盖、不重新信任——尊重用户/author 副本）。 */
  private def seedPlugin(root: os.Path, name: String): Boolean =
    val targetDir = root / "plugins" / name
    if os.exists(targetDir) then
      logger.infoSync(s"Seed: plugin '$name' already present — skipped (no overwrite)")
      false
    else
      val base = os.SubPath(s"seed/plugins/$name")
      val files = resourceDirList(base)
      if files.isEmpty then
        logger.warnSync(s"Seed: plugin '$name' has no seed resources — skipped")
        false
      else
        files.foreach { rel =>
          readResource(join(base, rel)).foreach { text =>
            val target = targetDir / rel
            os.makeDir.all(target / os.up)
            os.write.over(target, text)
          }
        }
        // 信任（digest 与目录内容天然一致——approve 复用同一 computeDigest）
        PluginRegistry.approve(name).unsafeRunSync() match
          case Right(_) =>
            logger.infoSync(s"Seed: plugin '$name' installed + approved")
            true
          case Left(err) =>
            // 装上了但 approve 失败 → 该插件落为 untrusted（默认拒绝），其余照常
            logger.warnSync(s"Seed: plugin '$name' installed but approve failed: $err")
            true

  /** 项目条目：按 ProjectStore.create 现行产物搭 general 脚手架（project.json +
    * AGENTS.md + .gitignore；flow-map.json 由 FlowMapStore.open 首写）。 */
  private def seedProject(root: os.Path, id: String): Boolean =
    val name = GeneralProjectName
    if os.exists(root / "projects" / name / "project.json") then
      logger.infoSync(s"Seed: project '$name' already exists — skipped")
      false
    else
      val workspace = (root / "projects" / name).toString
      val agentTemplate = readResource(os.SubPath("seed/projects/general/AGENTS.md"))
        .map(_.replace(DataRootPlaceholder, root.toString))
        .getOrElse(defaultAgentTemplate(name, root))
      ProjectStore.create(name, workspace, Some("通用项目（默认工作区）"), agentTemplate).unsafeRunSync() match
        case Right(_) =>
          logger.infoSync(s"Seed: project '$name' scaffolded (workspace=$workspace)")
          true
        case Left(err) =>
          logger.warnSync(s"Seed: project '$name' create failed: $err")
          false

  // ── helpers ──────────────────────────────────────────────
  private def writeIfAbsent(path: os.Path, content: Option[String]): Boolean =
    content match
      case Some(text) if !os.exists(path) =>
        os.makeDir.all(path / os.up)
        os.write.over(path, text)
        true
      case _ => false

  private def agentResource(name: String, file: String): os.SubPath =
    os.SubPath(s"seed/agents/$name/$file")

  private def join(base: os.SubPath, child: os.SubPath): os.SubPath =
    os.SubPath(s"${base.toString}/${child.toString}")

  private def readResource(rel: os.SubPath): Option[String] =
    val stream = Option(getClass.getClassLoader.getResourceAsStream(rel.toString))
    stream.map { s =>
      try new String(s.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
      finally s.close()
    }

  /** 枚举 classpath 资源目录（prefix）下全部文件。Anchor 在已知文件 plugin.json 上，
    * 以覆盖「jar 无目录条目」场景。sbt（file: URL）/ assembly（jar: URL）两种协议都处理。 */
  private def resourceDirList(prefix: os.SubPath): List[os.SubPath] =
    val loader = getClass.getClassLoader
    val anchor = join(prefix, os.SubPath("plugin.json"))
    Option(loader.getResource(anchor.toString)).toList.flatMap { url =>
      url.getProtocol match
        case "file" =>
          val dir = os.Path(java.nio.file.Paths.get(url.toURI)) / os.up
          os.walk(dir).filter(os.isFile).toList
            .map(p => os.SubPath(p.relativeTo(dir).toString))
        case "jar" =>
          val conn = url.openConnection().asInstanceOf[JarURLConnection]
          val jar = conn.getJarFile
          val base = s"${prefix.toString}/"
          jar.entries().asScala
            .filter(e => !e.isDirectory && e.getName.startsWith(base))
            .map(e => os.SubPath(e.getName.stripPrefix(base)))
            .toList
        case _ => Nil
    }.distinct

  private def defaultAgentTemplate(name: String, root: os.Path): String =
    s"""# $name — AGENTS.md

项目级 agent 指令（取代 team rules.md，工作区根 AGENTS.md）。分发器任务文本可引用本文件。

- 工作区：${(root / "projects" / name).toString}
- Flow Map：`${(root / "projects" / name).toString}/.nebflow/flow-map.json`
- 节点规则：节点是 leaf（无记忆、无 Mail 身份、ephemeral）；结果沿 out 边投递。
"""

  // ── manifest / marker ────────────────────────────────────
  private case class SeedManifest(seedVersion: String, items: List[String])
  private case class SeedMarker(version: String, seededAt: Long, items: List[String])

  private def loadManifest(): SeedManifest =
    readResource(os.SubPath("seed/manifest.json")) match
      case None => throw new IllegalStateException("seed/manifest.json not found on classpath")
      case Some(text) =>
        io.circe.parser.parse(text) match
          case Left(e) => throw new IllegalStateException(s"seed/manifest.json invalid: ${e.getMessage}")
          case Right(json) =>
            val c = json.hcursor
            val items = c.downField("items").as[List[String]].toOption.getOrElse(Nil)
            c.downField("seedVersion").as[String].toOption match
              case Some(ver) if ver.nonEmpty => SeedManifest(ver, items)
              case _ => throw new IllegalStateException("seed/manifest.json missing 'seedVersion'")

  private def readMarker(root: os.Path): Option[SeedMarker] =
    val p = root / ".seed-state.json"
    if !os.exists(p) then None
    else
      io.circe.parser.parse(os.read(p)).toOption.flatMap { json =>
        val c = json.hcursor
        for
          v <- c.downField("version").as[String].toOption
          at = c.downField("seededAt").as[Long].toOption.getOrElse(0L)
          items = c.downField("items").as[List[String]].toOption.getOrElse(Nil)
        yield SeedMarker(v, at, items)
      }

  private def writeMarker(root: os.Path, version: String, items: List[String]): Unit =
    val p = root / ".seed-state.json"
    val json = Json.obj(
      "version" -> version.asJson,
      "seededAt" -> System.currentTimeMillis().asJson,
      "items" -> items.asJson
    )
    AtomicJson.writeSync(p, json.noSpaces)

end SeedService
