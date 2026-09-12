package nebflow.core.seed

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.{AtomicJson, NebflowLogger, PathUtil}
import nebflow.core.plugin.PluginRegistry
import nebflow.core.project.ProjectStore

import java.net.JarURLConnection
import java.security.MessageDigest
import scala.collection.immutable.SortedMap
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
 *  - 默认 agent：project-dispatcher + general（形态以 runtime trusted 版为准，preset/skills
 *    字段合法入 seed——TB #20 基线对齐 2026-09-09）
 *  - 系统插件：收缩后默认集（visual-report / slideblocks，c7501470），
 *    预装 + trusted（复用 PluginRegistry.approve）
 *  - 默认通用项目：id=general，workspace=~/.nebflow/projects/general，启动前挂载
 *
 * 触发（§4.2）：gateway boot 装配点调用 `ensureSeeded()`，位置 = 项目挂载前。
 * 判定顺序：
 *  1. `projects/` 非空（≥1 个 project.json）→ 判定「已有用户数据」：只写/更新
 *     marker（记录当前 SeedVersion），不完整播种（防在 author 现役 home 误建 general）。
 *  2. else（fresh home）：marker 缺失 → 完整冷启动播种；marker.version < seedVersion
 *     → 升级 add-only 补种（每条 `!os.exists` 守卫，只补缺失文件）；>= → no-op。
 *  3. 最后（所有分支、不受守卫/marker 门控）：插件一致性 reconcile（见下）。
 *
 * 插件一致性 reconcile（「始终保持一致」机制，2026-09-09 批）：完整播种只解决
 * fresh home；既有 home 的已装插件会因 add-only 语义永久冻结（2026-09-09 断点：
 * author home 的 slideblocks 旧快照永不收敛）。reconcile 在每次启动对 manifest
 * 声明的插件做 seed ↔ runtime digest 比对仲裁：
 *  - runtime 目录缺失 → 不动（既有 home 不新装插件，新装仍走完整播种路径）
 *  - seed digest == runtime digest → 无动作（常态，静默通过）
 *  - 不一致且 runtime digest == 信任记录 digest（干净快照，用户未改）→ 种子镜像
 *    覆盖（含删除 runtime 独有文件）+ 自动 re-approve → 冷启动插件与最新种子一致
 *  - 不一致且 runtime digest ≠ 信任记录 digest（用户改过）→ 跳过 + WARN（用户编辑 > 种子）
 *  - 不一致且无信任记录 → 跳过 + WARN（无仲裁基准，保守不覆盖）
 * 仲裁语义：trusted digest 是 approve 时刻的目录内容 fingerprint——runtime 现算
 * digest 与之一致 ⇔ 该目录自审批后未被任何一方改动，种子更新可安全接管；任何漂移
 * （用户编辑、.DS_Store 等异物）都视为「用户态」，种子永不覆盖。
 *
 * 幂等 + fail-soft（§4.3/§4.4）：每条目独立 try/catch，失败仅 WARN 跳过、不中断；
 * agent/project 任何已有文件绝不覆盖（用户编辑 > 种子）；插件仅在 digest 仲裁干净时
 * 可被种子刷新（见 reconcile）；全程 best-effort，never crash gateway。
 */
object SeedService:
  private val logger = NebflowLogger.forName("nebflow.core.seed")

  private val AgentsPrefix = "agents:"
  private val PluginsPrefix = "plugins:"
  private val ProjectPrefix = "project:"
  private val GeneralProjectName = "general"
  private val DataRootPlaceholder = "<DATA_ROOT>"

  // ── 公共入口 ─────────────────────────────────────────────
  /** 冷启动播种（幂等、add-only、best-effort）+ 插件一致性 reconcile。
    * 失败绝不阻止 gateway 启动。 */
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
      // 插件一致性 pass（所有分支都跑，不受守卫/marker 门控——digest 一致时是无操作）
      reconcilePlugins(root, manifest)
      // agents 一致性 pass（#304-④，2026-09-12）：与插件 pass 同构，但仲裁基准
      // 换成「三条硬条件」（见 reconcileAgents 文档）；digest 一致时同样无操作。
      reconcileAgents(root, manifest)
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

  /** 插件条目（安装路径）：整目录复制 seed/plugins/<name>/ → ~/.nebflow/plugins/<name>/，
    * 首次装即 trusted。目标目录已存在 → 跳过（安装语义不覆盖；已装插件的刷新由
    * reconcilePlugins 的 digest 仲裁接管）。 */
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

  // ── 插件一致性 reconcile（「始终保持一致」机制）──────────
  /** 每次启动对 manifest 声明的插件做 seed ↔ runtime 比对（digest 仲裁，见类注释）。
    * 只刷新 runtime 已存在的插件；缺失不新装（既有 home 面积不扩张，新装走完整播种）。 */
  private def reconcilePlugins(root: os.Path, manifest: SeedManifest): Unit =
    manifest.items.collect {
      case id if id.startsWith(PluginsPrefix) => id.stripPrefix(PluginsPrefix)
    }.foreach { name =>
      try reconcilePlugin(root, name)
      catch
        case e: Exception =>
          logger.warnSync(s"Seed: plugin '$name' reconcile failed: ${e.getMessage}")
    }

  private def reconcilePlugin(root: os.Path, name: String): Unit =
    val targetDir = root / "plugins" / name
    if !os.exists(targetDir) then () // 缺失 → 不在既有 home 新装（注释见 reconcilePlugins）
    else seedResources(name) match
      case None => () // 无种子资源，无从比对（fresh 路径 seedPlugin 已 WARN）
      case Some(seedFiles) =>
        val seedDigest = treeDigest(seedFiles)
        PluginRegistry.computeDigest(targetDir) match
          case Right((runtimeDigest, _)) if runtimeDigest == seedDigest => () // 已一致
          case Right((runtimeDigest, _)) =>
            // 仲裁基准 = 信任记录落库 digest（approve 时刻 fingerprint，目录漂移不影响记录本身；
            // TrustStatus 现算漂移即 untrusted，取不到基准，不适用）
            PluginRegistry.trustRecordDigest(name) match
              case Some(td) if td == runtimeDigest =>
                // 干净运行时（自 approve 后零漂移）→ 种子镜像覆盖 + 自动重审（零用户操作）
                mirrorSeed(targetDir, seedFiles)
                PluginRegistry.approve(name).unsafeRunSync()
                logger.infoSync(
                  s"Seed: plugin '$name' refreshed from seed (digest ${runtimeDigest.take(12)}… → ${seedDigest.take(12)}…) and re-approved")
              case Some(td) =>
                // 用户改过（runtime 漂移出 trust 记录）→ 用户编辑 > 种子
                logger.warnSync(
                  s"Seed: plugin '$name' differs from seed (seed ${seedDigest.take(12)}…) — runtime is user-modified (digest ${runtimeDigest.take(12)}… ≠ trusted ${td.take(12)}…), keeping user version")
              case None =>
                // 无信任记录（untrusted/未审）→ 无仲裁基准，保守不覆盖
                logger.warnSync(
                  s"Seed: plugin '$name' differs from seed (seed ${seedDigest.take(12)}… vs runtime ${runtimeDigest.take(12)}…) and has no trust record — keeping runtime version")
          case Left(err) =>
            logger.warnSync(s"Seed: plugin '$name' runtime digest failed: $err — skipped")

  /** seed 资源字节面（rel POSIX 路径 → bytes）。复用 resourceDirList（file/jar 双协议）。 */
  private def seedResources(name: String): Option[SortedMap[String, Array[Byte]]] =
    val base = os.SubPath(s"seed/plugins/$name")
    val rels = resourceDirList(base)
    if rels.isEmpty then None
    else
      val entries = rels.flatMap { rel =>
        readResourceBytes(join(base, rel)).map(rel.toString -> _)
      }
      Some(SortedMap.from(entries))

  /** 资源树 digest——与 PluginRegistry.computeDigest 同算法（按 rel 路径排序，
    * 逐文件 "rel\0<bytes>\0" 喂入 SHA-256）。SortedMap 保序 ⇒ 与「先字节保真写盘
    * 再 computeDigest」等价（seedPlugin 文本写入 + 本方法字节写入均 UTF-8 保真）。 */
  private def treeDigest(files: SortedMap[String, Array[Byte]]): String =
    val md = MessageDigest.getInstance("SHA-256")
    files.foreach { (rel, bytes) =>
      md.update(s"$rel\u0000".getBytes("UTF-8"))
      md.update(bytes)
      md.update("\u0000".getBytes("UTF-8"))
    }
    md.digest().map("%02x".format(_)).mkString

  /** 种子镜像覆盖：字节保真写全部种子文件 → 删 runtime 独有文件 → 清理删空目录。
    * 仅在「runtime digest == trusted digest」已证干净后调用（脏目录绝不进此路径）。 */
  private def mirrorSeed(targetDir: os.Path, seedFiles: SortedMap[String, Array[Byte]]): Unit =
    seedFiles.foreach { (rel, bytes) =>
      val target = targetDir / os.SubPath(rel)
      os.makeDir.all(target / os.up)
      os.write.over(target, bytes)
    }
    os.walk(targetDir).toList.filter(os.isFile).foreach { f =>
      if !seedFiles.contains(f.relativeTo(targetDir).toString) then os.remove(f)
    }
    // 清理删空的残留目录（digest 只计文件，此步纯整洁）
    os.walk(targetDir).toList.filter(os.isDir)
      .sortBy(d => -d.relativeTo(targetDir).segments.length)
      .foreach { d => if os.list(d).isEmpty then os.remove(d) }

  // ── agents 一致性 reconcile（#304-④，2026-09-12）──────────
  /** 每次启动对 manifest 声明的 agent 做 seed ↔ runtime 比对。
    *
    * 与 [[reconcilePlugins]] 的机制差异：plugins 有 `trustRecordDigest`（approve 时刻
    * fingerprint）作仲裁基准，**agents 没有信任记录**。D-8 拍板取**最保守档**：
    * 不新造基准、不做「种子为准」的自动覆盖——**仲裁基准 = seed（种子权威）**，
    * 并以**三条硬条件**（作者 2026-09-12 裁定，取代 D-8「两条硬加」措辞）为前置，
    * **缺一即拒**：
    *   1. **覆盖前差集检查**：任何 seed→runtime **全量覆写**执行前，先做「运行时独有
    *      内容并入 seed 源」的差集检查；**差集非空 ⇒ 立即停手、零覆盖、上报**
    *      （列运行时独有行原文 + 行号），**禁静默覆盖**；
    *   2. **覆盖前备份**：覆写前必留可回滚备份（`<root>/agents-backups/<ts>_pre-sync-<name>/`，
    *      逐文件 pre-sha256）；
    *   3. **运行时独有内容不得静默丢弃**：任何路径下都不得静默丢掉运行时独有内容
    *      （与 1 的「停手上报」同源，缺一即拒）。
    *
    * 失败面：missing runtime → 不动（缺失不新装，既有 home 面积不扩张）；
    * digest 一致 → 静默通过（幂等：连续两次启动第二次零动作）。
    * 全程 best-effort：单条失败只 WARN，绝不阻止 gateway 启动。
    *
    * **落点纪律**：本机制**只**落代码；不在 `~/.nebflow/bin/` 或任何运维文档面
    * 新建护栏载体，也不回改任何既有留痕件（作者 2026-09-12 裁定）。 */
  private def reconcileAgents(root: os.Path, manifest: SeedManifest): Unit =
    manifest.items.collect {
      case id if id.startsWith(AgentsPrefix) => id.stripPrefix(AgentsPrefix)
    }.foreach { name =>
      try reconcileAgent(root, name)
      catch
        case e: Exception =>
          logger.warnSync(s"Seed: agent '$name' reconcile failed: ${e.getMessage}")
    }

  private def reconcileAgent(root: os.Path, name: String): Unit =
    val targetDir = root / "agents" / name
    if !os.exists(targetDir) then () // 缺失 → 不在既有 home 新装（同 reconcilePlugins 注释）
    else
      seedResourcesIn(os.SubPath(s"seed/agents/$name"), "agent.json") match
        case None => () // 无种子资源，无从比对
        case Some(seedFiles) =>
          val seedDigest = treeDigest(seedFiles)
          val runtimeDigest = runtimeTreeDigest(targetDir)
          if runtimeDigest == seedDigest then () // 已一致（幂等：第二次启动零动作）
          else
            // ── 硬条件 1（⑦(i) 护栏）：覆盖前差集检查 ──
            val unique = runtimeUniqueLines(targetDir, seedFiles)
            if unique.nonEmpty then
              logger.warnSync(
                s"Seed: agent '$name' runtime has content NOT present in seed — REFUSING to overwrite " +
                  s"(zero files written). Merge the runtime-only content into the seed source first, then re-sync. " +
                  s"Runtime-only: " + unique.map(e => s"${e.file}:${e.lines.size} line(s)").mkString(", ")
              )
              unique.foreach { e =>
                e.lines.take(40).foreach { (ln, text) =>
                  logger.warnSync(s"Seed:   runtime-only [$name] ${e.file}:$ln: $text")
                }
              }
            else
              // 差集为空（runtime 是 seed 的子集/一致内容）⇒ 种子权威：备份后镜像覆盖。
              // ── 硬条件 2：覆盖前备份 ──
              val stamp = java.time.format.DateTimeFormatter
                .ofPattern("yyyyMMdd_HHmmss")
                .format(java.time.LocalDateTime.now())
              val backupDir = root / "agents-backups" / s"${stamp}_pre-sync-$name"
              val preShas = runtimeFileShas(targetDir)
              os.makeDir.all(backupDir)
              os.walk(targetDir).filter(os.isFile).foreach { f =>
                val rel = f.relativeTo(targetDir)
                val dest = backupDir / os.SubPath(rel.toString)
                os.makeDir.all(dest / os.up)
                os.copy.over(f, dest)
              }
              os.write.over(
                backupDir / "PRE-SHA256.txt",
                s"# agent=$name  sampled=$stamp\n" +
                  preShas.map((rel, sha) => s"$sha  $rel").mkString("\n") + "\n"
              )
              mirrorSeed(targetDir, seedFiles)
              logger.infoSync(
                s"Seed: agent '$name' refreshed from seed (digest ${runtimeDigest.take(12)}… → ${seedDigest.take(12)}…); " +
                  s"pre-sync backup at ${backupDir.toString.stripPrefix(root.toString + "/")} (${preShas.size} file(s))")

  /** 同 [[seedResources]]，anchor 参数化（agents 面 = `agent.json`）。 */
  private def seedResourcesIn(base: os.SubPath, anchorFile: String): Option[SortedMap[String, Array[Byte]]] =
    val rels = resourceDirList(base, anchorFile)
    if rels.isEmpty then None
    else
      val entries = rels.flatMap { rel =>
        readResourceBytes(join(base, rel)).map(rel.toString -> _)
      }
      Some(SortedMap.from(entries))

  /** 运行时目录的同一 digest 算法（目录不存在 → 空串，调用方已守卫）。 */
  private def runtimeTreeDigest(dir: os.Path): String =
    val files = SortedMap.from(
      os.walk(dir).filter(os.isFile).toList.map { f =>
        f.relativeTo(dir).toString -> os.read.bytes(f)
      }
    )
    treeDigest(files)

  /** 运行时逐文件 sha256（备份留痕用；按 rel 路径排序保证可复算）。 */
  private def runtimeFileShas(dir: os.Path): List[(String, String)] =
    os.walk(dir).filter(os.isFile).toList
      .map(f => f.relativeTo(dir).toString -> sha256File(os.read.bytes(f)))
      .sortBy(_._1)

  private def sha256File(bytes: Array[Byte]): String =
    val md = MessageDigest.getInstance("SHA-256")
    md.digest(bytes).map("%02x".format(_)).mkString

  /** 硬条件 1 的差集计算：**运行时独有行**（逐文件比对同 rel 路径的 seed 文件；
    * 运行时独有文件 ⇒ 其全部行都算独有）。返回「文件 → (行号, 原文) 列表」。
    * 纯函数式读取，零写入——本方法只读不写。 */
  private[seed] final case class RuntimeUnique(file: String, lines: List[(Int, String)])

  private[seed] def runtimeUniqueLines(
      targetDir: os.Path,
      seedFiles: SortedMap[String, Array[Byte]]
  ): List[RuntimeUnique] =
    val seedLineSets: Map[String, Set[String]] = seedFiles.map { (rel, bytes) =>
      rel -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8).split("\n", -1).toSet
    }.toMap
    os.walk(targetDir).filter(os.isFile).toList.sortBy(_.toString).flatMap { f =>
      val rel = f.relativeTo(targetDir).toString
      val seedLines = seedLineSets.getOrElse(rel, Set.empty[String])
      val runtimeLines =
        try new String(os.read.bytes(f), java.nio.charset.StandardCharsets.UTF_8).split("\n", -1).toList
        catch case _: Throwable => Nil
      val unique = runtimeLines.zipWithIndex
        .filterNot { (line, _) => seedLines.contains(line) }
        .map { (line, idx) => (idx + 1, line) }
      if unique.isEmpty then Nil else List(RuntimeUnique(file = rel, lines = unique))
    }

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

  private def readResourceBytes(rel: os.SubPath): Option[Array[Byte]] =
    val stream = Option(getClass.getClassLoader.getResourceAsStream(rel.toString))
    stream.map { s =>
      try s.readAllBytes()
      finally s.close()
    }

  /** 枚举 classpath 资源目录（prefix）下全部文件。Anchor 在已知文件 plugin.json 上，
    * 以覆盖「jar 无目录条目」场景。sbt（file: URL）/ assembly（jar: URL）两种协议都处理。 */
  private def resourceDirList(prefix: os.SubPath): List[os.SubPath] =
    resourceDirList(prefix, "plugin.json")

  /** 同上，anchor 文件名参数化（agents 面 anchor = `agent.json`）。 */
  private def resourceDirList(prefix: os.SubPath, anchorFile: String): List[os.SubPath] =
    val loader = getClass.getClassLoader
    val anchor = join(prefix, os.SubPath(anchorFile))
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
