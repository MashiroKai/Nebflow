package nebflow.core.seed

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.plugin.PluginRegistry
import nebflow.core.project.ProjectStore
import nebflow.core.{AtomicJson, PathUtil}
import nebflow.shared.NebflowLogger

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
 * 种子面（三 keeper = Nebula 引擎自带 + 本服务补 project-dispatcher / general）：
 *  - 默认 agent：project-dispatcher + general（形态以 runtime trusted 版为准，preset/skills
 *    字段合法入 seed——TB #20 基线对齐 2026-09-09）
 *  - 系统插件：收缩后默认集（visual-report / slideblocks，c7501470），
 *    预装 + trusted（复用 PluginRegistry.approve）
 *  - 默认通用项目：id=general，workspace=`<root>/projects/general`，启动前挂载
 *    （`project:` 条目 + `seed/projects/general/AGENTS.md` 模板，`<DATA_ROOT>` 占位
 *    替换为数据根）
 *
 * **项目面 = 新环境播种 + 既有 home 严格 add-only 补种**（作者 2026-09-17 裁定①②：撤销
 * 2026-09-16「移除内置 general 项目」令，且既有 home 亦补种）——干净 home 播种
 * `projects/general` 脚手架；既有 home（`projects/` 非空）由 [[reconcileProjects]] 与
 * agents/plugins 两面**同构**地补**缺失**的内置项目。守卫 = **目录级**
 * `!os.exists(root/projects/<name>/project.json)`（与 `ProjectStore` 同粒度）：缺 ⇒ 建、
 * 已存在 ⇒ 零动作零写盘；**已有内容零覆盖 / 零搬移 / 零删除**，既有 `general`（多为手工
 * 建成、内容可与种子不同）**绝不被本面改写** ⇒ 存量 `~/.nebflow/projects/general/` 目录下
 * 全部文件逐字节不变。本面**只做「缺失 → 建」**，不做 seed → runtime 内容仲裁（不触发 #304
 * 零覆盖差集纪律）。
 * 前令（**已作废，不得再按此口径复述**）：`961e2cbd3` 提交记录所写「作者裁定否决既有 home
 * 补种」= 前令；后令（裁定②：两面都补、既有严格 add-only，作者对「波及所有缺它的 home
 * （含隔离 home）」明示知情接受）治前论。更早（2026-09-16 ~ 2026-09-17）的「项目面零播种」
 * 口径已随撤销令作废。
 *
 * 触发（§4.2）：gateway boot 装配点调用 `ensureSeeded()`，位置 = 项目挂载前。
 * 判定顺序：
 *  1. `projects/` 非空（≥1 个 project.json）→ 判定「已有用户数据」：只写/更新
 *     marker（记录当前 SeedVersion），不完整播种（既有 home 不重播默认集）；
 *     **默认集内缺失的 agent / plugin / project 分别由三条 reconcile 自愈补装**
 *     （agents 见 2026-09-13 批 reconcileAgents；project 见本批 reconcileProjects）。
 *  2. else（fresh home）：marker 缺失 → 完整冷启动播种；marker.version < seedVersion
 *     → 升级 add-only 补种（每条 `!os.exists` 守卫，只补缺失文件）；>= → no-op。
 *  3. 最后（所有分支、不受守卫/marker 门控）：插件一致性 reconcile（见下）
 *     + agents 一致性 reconcile（缺失自愈）+ 项目一致性 reconcile（缺失补建）
 *     + 记忆消费链启动校验（消费链缺失 ⇒ 响亮 WARN）。
 *
 * 插件一致性 reconcile（「始终保持一致」机制，2026-09-09 批）：完整播种只解决
 * fresh home；既有 home 的已装插件会因 add-only 语义永久冻结（2026-09-09 断点：
 * author home 的 slideblocks 旧快照永不收敛）。reconcile 在每次启动对 manifest
 * 声明的插件做 seed ↔ runtime digest 比对仲裁：
 *  - runtime 目录缺失 → **自愈安装**（2026-09-12 批）：从种子整目录装 + approve。缺失永不
 *    自愈是既有 home 的结构性缺口（author home 的 nebflow-plugin-creator 有种子无实件、
 *    `PluginRegistry.resolve` 永远 PLUGIN_NOT_FOUND）；新装面 = manifest items 本身
 *    （= 默认预装集），故既有 home 的插件面积只在**默认集内**收敛，绝不向种子树全集
 *    （`seed/plugins/` 资源树 = 可手动装全集）扩张。已存在目录一律不覆盖、不改写
 *    （既有目录走下面的 digest 仲裁，用户态 > 种子）
 *  - **`userRemoved` 标记在位 → 跳过自愈**（2026-09-13 批，#105 P-1「用户主动删除」；
 *    见「插件存在台账」段）：只有**用户主动删掉**的默认集插件不装回，其余缺失情形
 *    （无历史 / 存储级重置）的自愈语义逐字不变
 *  - seed digest == runtime digest → 无动作（常态，静默通过）
 *  - 不一致且 runtime digest == 信任记录 digest（干净快照，用户未改）→ **覆盖前备份**
 *    （`<root>/plugins-backups/<ts>_pre-sync-<name>/`，逐文件 pre-sha256；与
 *    [[reconcileAgent]] 硬条件 2 的 `agents-backups` 形态逐项对称）→ 种子镜像
 *    覆盖（含删除 runtime 独有文件）+ 自动 re-approve → 冷启动插件与最新种子一致
 *  - 不一致且 runtime digest ≠ 信任记录 digest（用户改过）→ 跳过 + WARN（用户编辑 > 种子）
 *  - 不一致且无信任记录 → 跳过 + WARN（无仲裁基准，保守不覆盖）
 * 仲裁语义：trusted digest 是 approve 时刻的目录内容 fingerprint——runtime 现算
 * digest 与之一致 ⇔ 该目录自审批后未被任何一方改动，种子更新可安全接管；任何漂移
 * （用户编辑、.DS_Store 等异物）都视为「用户态」，种子永不覆盖。
 *
 * 插件存在台账（2026-09-13 批，#105 P-1「用户主动删除的不装回」）：载体 =
 * `<root>/.plugin-presence.json`（home 数据根、与 `.seed-state.json` 同级；随 home 走、
 * 跨重启存活、不落 repo）。两字段：
 *  - `seen` = **推断性「曾就位」历史**（某默认集插件在本 home 被观察到在位过，无论谁装的）。
 *    推断的证据是**插件存储自身** ⇒ **容器规则**：`<root>/plugins/` 整体缺失（存储级重置）
 *    时 `seen` **作废**（等价「从未装过」），按现行口径补齐——粗粒度手势（整个存储被删）
 *    保持旧语义，「缺失即自愈」只在**存储仍在而某个包目录消失**时才被判为用户定向删除。
 *  - `userRemoved` = **用户显式删除意图标记**（tombstone，name → 检出时刻 epoch 秒）：
 *    在一次 boot 上观察到「名字在 `seen` 中且目录已消失」时落盘，**不随存储级重置作废**
 *    （显式意图 > 推断历史）。在位时自愈面**跳过**（零 install、零 approve）；
 *    该插件目录**再次存在**时清除（清理三问：谁清 = 本 pass；何时清 = 目录再次存在后的
 *    首次 boot；依据 = `os.exists(<root>/plugins/<name>)`）。
 * 写入者唯一 = 本 pass（无第二写点）；读者唯一 = `reconcilePlugin` 缺失分支；幂等 =
 * 状态未变则不写盘（连续两次 boot 第二次零动作）；损坏/缺失 ⇒ 当空台账 + WARN（fail-soft）。
 * **来源边界（诚实申报）**：产品无卸载入口，删除只能带外（`rm -rf`）⇒ 标记由**观察**
 * 得出（比较历史在位与当前缺失），故上线后**首次**启动无法回溯判定历史删除（无基线，
 * 按现行口径补齐）；非用户来源的消失会被判为「用户删除」（保守方向 = 不装回）。
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

  /**
   * 插件存在台账文件名（home 数据根下、与 `.seed-state.json` 同级；随 home 走、不落 repo）。
   * 语义 / 生命周期见类注释「插件存在台账」段。
   */
  private val PluginLedgerFileName = ".plugin-presence.json"
  private val PluginLedgerVersion = 1

  /** 记忆队列的消费者 agent 名（单点引用 `AgentCore` 常量，不复制字面量）。 */
  private val MemoryConsumptionAgent: String = nebflow.agent.AgentCore.MemoryConsolidatorName

  // ── 插件存在台账（#105 P-1「用户主动删除」标记）──────────
  /**
   * 台账（`seen` = 曾就位的默认集插件名，单调只增；`userRemoved` = 用户主动删除标记）。
   * 纯值对象 + 三个转移函数，便于逐条断言与幂等（无变化 ⇒ `equals` 为真 ⇒ 不写盘）。
   */
  private[seed] final case class PluginLedger(seen: Set[String], userRemoved: Map[String, Long]):
    /** 该插件在位（含用户手动装回）⇒ 记名 + **清 tombstone**（清理语义的落点）。 */
    def observe(name: String): PluginLedger = PluginLedger(seen + name, userRemoved - name)

    /** 检出「曾就位、现已消失」⇒ 落用户删除标记（幂等：同值覆盖不改变状态）。 */
    def tombstone(name: String, at: Long): PluginLedger =
      PluginLedger(seen + name, userRemoved.updated(name, at))
    def isEmpty: Boolean = seen.isEmpty && userRemoved.isEmpty

    /** 存储级重置（`<root>/plugins/` 整体缺失）⇒ 推断历史作废、显式删除意图保留。 */
    def withoutHistory: PluginLedger = PluginLedger(Set.empty, userRemoved)

  private[seed] object PluginLedger:
    val empty: PluginLedger = PluginLedger(Set.empty, Map.empty)

  /** 台账路径（home 数据根下；`private[seed]` = spec 直读面）。 */
  private[seed] def pluginLedgerPath(root: os.Path): os.Path = root / PluginLedgerFileName

  /** 读台账：缺失 / 损坏 / 字段非法 ⇒ 空台账 + WARN（fail-soft，绝不阻断启动）。 */
  private def readPluginLedger(root: os.Path): PluginLedger =
    val p = pluginLedgerPath(root)
    if !os.exists(p) then PluginLedger.empty
    else
      io.circe.parser.parse(os.read(p)) match
        case Left(e) =>
          logger.warnSync(
            s"Seed: plugin presence ledger unreadable (${e.getMessage}) — treating as empty (existing install/self-heal semantics)"
          )
          PluginLedger.empty
        case Right(json) =>
          val c = json.hcursor
          // tombstone 条目：`{"at": <epoch秒>}`（现行格式）或裸 epoch 数字（读侧容错——
          // 写侧格式见 writePluginLedger；两侧必须同形，否则标记读不回来）
          val rawRemoved = c.downField("userRemoved").as[Map[String, Json]].toOption.getOrElse(Map.empty)
          val removed = rawRemoved.flatMap { (name, j) =>
            val at = j.asNumber.flatMap(_.toLong).orElse(j.hcursor.downField("at").as[Long].toOption)
            at.map(name -> _)
          }
          PluginLedger(
            seen = c.downField("seen").as[List[String]].toOption.getOrElse(Nil).toSet,
            userRemoved = removed
          )

    end if

  end readPluginLedger

  /** 读台账 + 容器规则（`<root>/plugins/` 整体缺失 ⇒ 推断历史作废，见类注释）。 */
  private def pluginLedgerFor(root: os.Path): PluginLedger =
    val stored = readPluginLedger(root)
    if os.exists(root / "plugins") then stored
    else
      val voided = stored.withoutHistory
      if !stored.seen.isEmpty then
        logger.infoSync(
          "Seed: plugin store is absent — presence history voided (store-level reset; the current install/self-heal semantics apply to the default set)"
        )
      voided

  /**
   * 写台账：**内容未变则不写盘**（幂等 ⇒ 连续两次启动第二次零动作、mtime 不变）；
   * 空台账不落盘（若残留则删除，保持 home 无冗余文件）。
   */
  private def writePluginLedger(root: os.Path, before: PluginLedger, after: PluginLedger): Unit =
    if after == before then ()
    else if after.isEmpty then
      val p = pluginLedgerPath(root)
      if os.exists(p) then os.remove(p)
    else
      val json = Json.obj(
        "version" -> PluginLedgerVersion.asJson,
        "seen" -> after.seen.toList.sorted.asJson,
        "userRemoved" -> Json.fromFields(
          after.userRemoved.toList.sortBy(_._1).map((name, at) => name -> Json.obj("at" -> at.asJson))
        )
      )
      AtomicJson.writeSync(pluginLedgerPath(root), json.noSpaces)

  // ── 公共入口 ─────────────────────────────────────────────
  /**
   * 冷启动播种（幂等、add-only、best-effort）+ 插件一致性 reconcile。
   * 失败绝不阻止 gateway 启动。
   */
  def ensureSeeded(): IO[Unit] = IO.blocking {
    val root = PathUtil.dataRoot
    try
      val manifest = loadManifest()
      if hasExistingProjects(root) then
        // 已有用户数据：只记录 marker，不完整播种（既有 home 不重播默认集）
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
      end if
      // 插件一致性 pass（所有分支都跑，不受守卫/marker 门控——digest 一致时是无操作）
      reconcilePlugins(root, manifest)
      // agents 一致性 pass（#304-④，2026-09-12）：与插件 pass 同构，但仲裁基准
      // 换成「三条硬条件」（见 reconcileAgents 文档）；digest 一致时同样无操作。
      // 缺失 → 自愈补装（2026-09-13 批，作者令「改成缺失自愈」）。
      reconcileAgents(root, manifest)
      // 项目一致性 pass（作者 2026-09-17 裁定②「既有 home 亦 add-only 补种」）：与上面两条
      // reconcile 同构，**与 `hasExistingProjects` 门无关**——既有 home 分支同样走到本行，
      // 迭代面 = manifest items；缺 ⇒ 建、已在 ⇒ 零动作零写盘（见 reconcileProjects）。
      reconcileProjects(root, manifest)
      // 启动期消费链校验（2026-09-13 批）：把「记忆队列没有消费者」变成启动即可见的告警
      verifyMemoryConsumptionChain(root)
    catch
      case e: Exception =>
        // 种子全程 best-effort：单点失败绝不阻止 gateway 启动（与 startupMount fail-soft 同构）
        logger.warnSync(s"Seed skipped due to failure: ${e.getMessage}")
    end try
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

  /**
   * agent 条目：写 seed/agents/<name>/{agent.json,system.md} 到 ~/.nebflow/agents/<name>/。
   * agent.json 与 system.md 各自独立 `!os.exists` 守卫（add-only：只补缺失文件）。
   */
  private def seedAgent(root: os.Path, name: String): Boolean =
    val targetDir = root / "agents" / name
    val jsonWrote = writeIfAbsent(targetDir / "agent.json", readResource(agentResource(name, "agent.json")))
    val mdWrote = writeIfAbsent(targetDir / "system.md", readResource(agentResource(name, "system.md")))
    if jsonWrote || mdWrote then
      logger.infoSync(s"Seed: wrote agent '$name' (agent.json=$jsonWrote, system.md=$mdWrote)")
    jsonWrote || mdWrote

  /**
   * 插件条目（安装路径）：整目录复制 seed/plugins/<name>/ → ~/.nebflow/plugins/<name>/，
   * 首次装即 trusted。目标目录已存在 → 跳过（安装语义不覆盖；已装插件的刷新由
   * reconcilePlugins 的 digest 仲裁接管）。
   */
  private def seedPlugin(root: os.Path, name: String): Boolean =
    val targetDir = root / "plugins" / name
    if os.exists(targetDir) then
      logger.infoSync(s"Seed: plugin '$name' already present — skipped (no overwrite)")
      false
    else installPluginFromSeed(root, name, "installed")

  /**
   * 从种子整目录安装 + approve（`seedPlugin` 的安装路径与 `reconcilePlugin` 的缺失自愈
   * 共用单点）。`outcome` 只影响日志措辞，便于把「播种安装」与「既有 home 自愈安装」
   * 在读日志时分开（两者落盘语义相同：整目录复制 + approve）。approve 复用同一
   * computeDigest ⇒ 信任记录 digest 与刚落盘内容天然一致。
   */
  private def installPluginFromSeed(root: os.Path, name: String, outcome: String): Boolean =
    val targetDir = root / "plugins" / name
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
      // 审批记录（digest 与目录内容天然一致——approve 复用同一 computeDigest）。
      // 无审批批 2026-09-13：记录**不再决定装载**（在位即信任），但它是 seed 覆盖的
      // 仲裁基准（`trustRecordDigest`）⇒ 安装路径必须继续写它（否则该包永远落入
      // `reconcilePlugin` 的「无基准 ⇒ 保守不覆盖」分支，种子更新到不了它）。
      PluginRegistry.approve(name).unsafeRunSync() match
        case Right(_) =>
          logger.infoSync(s"Seed: plugin '$name' $outcome + trust record written")
          true
        case Left(err) =>
          // 装上了但记录写失败 → 该包照常可用（在位即信任），但缺仲裁基准 ⇒ 种子不会覆盖它
          logger.warnSync(s"Seed: plugin '$name' $outcome but the trust record write failed: $err")
          true

    end if

  end installPluginFromSeed

  /**
   * 项目条目：按 [[ProjectStore.create]] 现行产物搭 general 脚手架（project.json +
   * AGENTS.md + .gitignore；flow-map.json 由 FlowMapStore.open 首写）。模板里的
   * `DataRootPlaceholder` 替换为本 home 的数据根（模板随 home 走，禁写死路径）。
   *
   * **两条调用路径**（作者 2026-09-17 裁定①②）：① fresh home 的完整播种
   * （[[runSeed]] → `seedItem` 的 `project:` 分派）；② 既有 home 的 add-only 补种
   * （[[reconcileProjects]]）——后者与 `hasExistingProjects` 门**无关**，故既有 home 也会
   * 走到本方法，但**只补缺失**：守卫 = **目录级**
   * `!os.exists(root / "projects" / name / "project.json")`（与 `ProjectStore` 同粒度），
   * 已在 ⇒ 零动作零写盘（既有 `general` 的 project.json / AGENTS.md 逐字节不变——
   * 本方法**绝不**用种子文本覆写用户态）。幂等：`project.json` 已在 ⇒ 跳过
   * （`ProjectStore.create` 亦自带「已存在 ⇒ 拒绝」兜底，双保险，用户编辑 > 种子）。
   */
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
      ProjectStore
        .create(
          name,
          workspace,
          Some(
            "General-purpose project for executing general tasks: any domain, one-off or recurring; Nebula dispatches, nodes execute."
          ),
          agentTemplate
        )
        .unsafeRunSync() match
        case Right(_) =>
          logger.infoSync(s"Seed: project '$name' scaffolded (workspace=$workspace)")
          true
        case Left(err) =>
          logger.warnSync(s"Seed: project '$name' create failed: $err")
          false

      end match

    end if

  end seedProject

  // ── 项目一致性 reconcile（既有 home 的缺失内置项目 add-only 补种）──
  /**
   * 每次启动对 manifest 声明的项目做存在性检查 + 缺失补种（与 [[reconcilePlugins]] /
   * [[reconcileAgents]] **同构**：迭代面 = manifest items、逐条 try/catch、失败仅 WARN、
   * 绝不中断启动、digest/内容一致时无操作）。
   *
   * 守卫粒度 = **目录级** `!os.exists(root / "projects" / <name> / "project.json")`
   * （与 `ProjectStore.create` 同粒度，设计件 §三(2) 明定）：缺 ⇒ 经 [[seedProject]] 建
   * 脚手架；**已在 ⇒ 零动作、零写盘**（幂等：连续两次 boot 第二次零写入、mtime 不变）。
   * 既有内容**零覆盖、零搬移、零删除**——手工建的 `projects/<name>/` 目录（含其
   * project.json / AGENTS.md）逐字节不变。本面**只做「缺失 → 建」**，不做 seed → runtime
   * 内容仲裁（作者 2026-09-17 裁定②：严格 add-only）；因此**不触发** #304 零覆盖差集纪律
   * （一旦将来引入镜像覆写，即刻触发，见设计件 §二发现①「对既有口径的冲击」末段）。
   *
   * 与前令的关系（不得再复述前令口径）：`961e2cbd3` 提交记录所写「作者裁定否决既有 home
   * 补种」= **前令，已作废**；后令（2026-09-17 裁定②：两面都补、既有严格 add-only，作者对
   * 波及隔离 home 亦明示知情接受）治前论 ⇒ 本面即先前缺口「缺失的一件」。缺口与
   * [[reconcileAgents]] 同源：既有 home 受 `projects/` 非空守卫**永不完整播种**，缺失的
   * 内置项目因此永久不愈（agents 面 2026-09-13 已自愈，项目面本批对齐）。
   *
   * 写入面 = 项目定义目录的**第二写点**（第一 = `runSeed` 的完整播种）；风险与回滚见设计件
   * §六（代码回滚 = revert 本批提交；**数据不随回滚撤销**——add-only 建出的目录会留下）。
   */
  private def reconcileProjects(root: os.Path, manifest: SeedManifest): Unit =
    manifest.items
      .collect {
        case id if id.startsWith(ProjectPrefix) => id.stripPrefix(ProjectPrefix)
      }
      .foreach { name =>
        try seedProject(root, name)
        catch
          case e: Exception =>
            logger.warnSync(s"Seed: project '$name' reconcile failed: ${e.getMessage}")
      }

  // ── 插件一致性 reconcile（「始终保持一致」机制）──────────
  /**
   * 每次启动对 manifest 声明的插件做 seed ↔ runtime 比对（digest 仲裁，见类注释）。
   * 迭代面 = manifest items（= 默认预装集），不遍历 `seed/plugins/` 资源树全集——故本 pass
   * 的安装/刷新面积由 manifest 决定：缺失的默认集插件被自愈安装，非默认集的种子树包
   * 永不因本 pass 落盘（作者 2026-09-12 裁定：种子树文件保留可手动装，默认集只三条）。
   * 本 pass 同时是**插件存在台账的唯一写入者**（逐包判定 → 末尾一次写盘，见类注释）。
   */
  private def reconcilePlugins(root: os.Path, manifest: SeedManifest): Unit =
    val names = manifest.items.collect {
      case id if id.startsWith(PluginsPrefix) => id.stripPrefix(PluginsPrefix)
    }
    val ledgerBefore = pluginLedgerFor(root)
    val ledgerAfter = names.foldLeft(ledgerBefore) { (ledger, name) =>
      try reconcilePlugin(root, name, ledger)
      catch
        case e: Exception =>
          logger.warnSync(s"Seed: plugin '$name' reconcile failed: ${e.getMessage}")
          ledger
    }
    try writePluginLedger(root, ledgerBefore, ledgerAfter)
    catch
      case e: Exception =>
        logger.warnSync(s"Seed: plugin presence ledger write failed: ${e.getMessage}")

  end reconcilePlugins

  private def reconcilePlugin(root: os.Path, name: String, ledger: PluginLedger): PluginLedger =
    val targetDir = root / "plugins" / name
    if !os.exists(targetDir) then
      if ledger.userRemoved.contains(name) then
        // ① 用户主动删除标记在位 ⇒ 不装回（负控）：零 install、零 approve
        val at = ledger.userRemoved(name)
        logger.infoSync(
          s"Seed: plugin '$name' is missing and carries the user-removed marker (recorded at $at) — " +
            s"skipping self-heal (no install, no approve). Reinstall the package to bring it back, or delete that entry in " +
            s"${pluginLedgerPath(root).toString.stripPrefix(root.toString + "/")} to let the seed manage it again"
        )
        ledger
      else if ledger.seen.contains(name) then
        // ② 曾就位 + 目录消失（存储仍在）⇒ 判为用户定向删除：落标记并跳过自愈，本次即生效
        val at = System.currentTimeMillis() / 1000L
        logger.warnSync(
          s"Seed: plugin '$name' was present in this home at an earlier boot and its directory is now gone — " +
            s"reading this as a deliberate user removal: recording the marker (${pluginLedgerPath(root).toString.stripPrefix(root.toString + "/")}) " +
            s"and skipping self-heal (no install, no approve). Reinstall the package to bring it back; the marker is cleared automatically once its directory exists again"
        )
        ledger.tombstone(name, at)
      else
        // ③ 无标记 / 无历史（含存储级重置）⇒ 现行自愈语义逐字不变（正控）
        installPluginFromSeed(root, name, "self-healed (missing in existing home)")
        ledger.observe(name)
    else
      seedResources(name) match
        case None => () // 无种子资源，无从比对（fresh 路径 seedPlugin 已 WARN）
        case Some(seedFiles) =>
          val seedDigest = treeDigest(seedFiles)
          PluginRegistry.computeDigest(targetDir) match
            case Right((runtimeDigest, _)) if runtimeDigest == seedDigest => () // 已一致
            case Right((runtimeDigest, _)) =>
              // 仲裁基准 = 信任记录落库 digest（approve 时刻 fingerprint，目录漂移不影响记录
              // 本身）。无审批批 2026-09-13：记录不再决定装载（在位即信任），故**只能**从记录表
              // 取基准——现算 TrustStatus 恒受信，取不到「用户是否改过」这一信息。
              PluginRegistry.trustRecordDigest(name) match
                case Some(td) if td == runtimeDigest =>
                  // 干净运行时（自 approve 后零漂移）→ 覆盖前备份 + 种子镜像覆盖 + 自动重审
                  // 覆盖前备份（与 [[reconcileAgent]] 的硬条件 2 对称）：**凡覆盖写必须有
                  // pre-sync 备份**，不因「按设计这是安全覆盖」而豁免——「本路径按设计安全」
                  // 与「覆盖后有可回滚材料」是两件事，后者是回滚材料完整性的要求。
                  val stamp = java.time.format.DateTimeFormatter
                    .ofPattern("yyyyMMdd_HHmmss")
                    .format(java.time.LocalDateTime.now())
                  val backupDir = root / "plugins-backups" / s"${stamp}_pre-sync-$name"
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
                    s"# plugin=$name  sampled=$stamp\n" +
                      preShas.map((rel, sha) => s"$sha  $rel").mkString("\n") + "\n"
                  )
                  mirrorSeed(targetDir, seedFiles)
                  PluginRegistry.approve(name).unsafeRunSync()
                  logger.infoSync(
                    s"Seed: plugin '$name' refreshed from seed (digest ${runtimeDigest.take(12)}… → ${seedDigest.take(12)}…) and re-approved; " +
                      s"pre-sync backup at ${backupDir.toString.stripPrefix(root.toString + "/")} (${preShas.size} file(s))"
                  )
                case Some(td) =>
                  // 用户改过（runtime 漂移出 trust 记录）→ 用户编辑 > 种子
                  logger.warnSync(
                    s"Seed: plugin '$name' differs from seed (seed ${seedDigest.take(12)}…) — runtime is user-modified (digest ${runtimeDigest.take(12)}… ≠ trusted ${td.take(12)}…), keeping user version"
                  )
                case None =>
                  // 无信任记录（从未 approve）→ 无仲裁基准，保守不覆盖
                  logger.warnSync(
                    s"Seed: plugin '$name' differs from seed (seed ${seedDigest.take(12)}… vs runtime ${runtimeDigest.take(12)}…) and has no trust record — keeping runtime version"
                  )
            case Left(err) =>
              logger.warnSync(s"Seed: plugin '$name' runtime digest failed: $err — skipped")
          end match
      end match
      // ③ 清理语义：目录再次存在（用户手动装回）⇒ 记 seen + 清 tombstone（内容零改写：
      // 在位目录只走上面的 digest 仲裁），此后该包重新纳入自愈面
      if ledger.userRemoved.contains(name) then
        logger.infoSync(
          s"Seed: plugin '$name' is present again — clearing its user-removed marker (seed reconciliation resumes for it)"
        )
      ledger.observe(name)

    end if

  end reconcilePlugin

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

  /**
   * 资源树 digest——与 PluginRegistry.computeDigest 同算法（按 rel 路径排序，
   * 逐文件 "rel\0<bytes>\0" 喂入 SHA-256）。SortedMap 保序 ⇒ 与「先字节保真写盘
   * 再 computeDigest」等价（seedPlugin 文本写入 + 本方法字节写入均 UTF-8 保真）。
   */
  private def treeDigest(files: SortedMap[String, Array[Byte]]): String =
    val md = MessageDigest.getInstance("SHA-256")
    files.foreach { (rel, bytes) =>
      md.update(s"$rel\u0000".getBytes("UTF-8"))
      md.update(bytes)
      md.update("\u0000".getBytes("UTF-8"))
    }
    md.digest().map("%02x".format(_)).mkString

  /**
   * 种子镜像覆盖：字节保真写全部种子文件 → 删 runtime 独有文件 → 清理删空目录。
   * 仅在「runtime digest == trusted digest」已证干净后调用（脏目录绝不进此路径）。
   * **调用方契约：覆写前必先落 pre-sync 备份**（两处调用点均已满足——
   * [[reconcileAgent]] 落 `agents-backups/`、[[reconcilePlugin]] 落 `plugins-backups/`）。
   */
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
    os.walk(targetDir)
      .toList
      .filter(os.isDir)
      .sortBy(d => -d.relativeTo(targetDir).segments.length)
      .foreach { d => if os.list(d).isEmpty then os.remove(d) }

  end mirrorSeed

  // ── agents 一致性 reconcile（#304-④，2026-09-12）──────────
  /**
   * 每次启动对 manifest 声明的 agent 做 seed ↔ runtime 比对。
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
   * 失败面（2026-09-13 缺失自愈批 / 作者令「改成缺失自愈」，取代 D-8「缺失不新装」口径）：
   * **missing runtime → 从种子整目录补装**（与 [[reconcilePlugin]] 的 `:218-224` 自愈分支
   * 对称；安装面严格限定在 **manifest 声明的默认集**，不向种子树全集扩张、既有目录零覆盖）；
   * digest 一致 → 静默通过（幂等：连续两次启动第二次零动作）。
   * 全程 best-effort：单条失败只 WARN，绝不阻止 gateway 启动。
   *
   * 为什么要改（取证件 §0-3 的鸡生蛋）：既有 home 受 `projects/` 非空守卫**永不完整播种**，
   * 而旧 reconcile 遇缺失直接 return ⇒ 「seed 只填新 home、reconcile 只修旧 home，交集为空」
   * ⇒ 默认集 agent（`memory-consolidator`）在既有 home **永不可能就位** ⇒ 记忆队列的
   * 唯一消费者结构性缺席，队列只进不出。插件面对同类缺口已于 2026-09-12 自愈，agents 面
   * 本批对齐。
   *
   * **落点纪律**：本机制**只**落代码；不在 `~/.nebflow/bin/` 或任何运维文档面
   * 新建护栏载体，也不回改任何既有留痕件（作者 2026-09-12 裁定）。
   */
  private def reconcileAgents(root: os.Path, manifest: SeedManifest): Unit =
    manifest.items
      .collect {
        case id if id.startsWith(AgentsPrefix) => id.stripPrefix(AgentsPrefix)
      }
      .foreach { name =>
        try reconcileAgent(root, name)
        catch
          case e: Exception =>
            logger.warnSync(s"Seed: agent '$name' reconcile failed: ${e.getMessage}")
      }

  private def reconcileAgent(root: os.Path, name: String): Unit =
    val targetDir = root / "agents" / name
    if !os.exists(targetDir) then
      // 缺失 → 自愈补装（2026-09-13 批，见 reconcileAgents 文档）：既有 home 受 projects/
      // 非空守卫永不完整播种，缺失目录因此永久不愈（本机 memory-consolidator 实例）。
      // 安装面 = manifest 声明的默认集；已存在目录绝不进此分支（零覆盖）。
      installAgentFromSeed(root, name, "self-healed (missing in existing home)")
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
                  s"pre-sync backup at ${backupDir.toString.stripPrefix(root.toString + "/")} (${preShas.size} file(s))"
              )

            end if

          end if

    end if

  end reconcileAgent

  /**
   * 从种子整目录安装一个 agent（[[reconcileAgent]] 的缺失自愈路径与 [[seedAgent]] 的
   * 播种路径共用单点）。缺种子资源（jar 陈旧 / 资源缺失）⇒ WARN 且**不落盘**——
   * 消费链缺失必须以响亮日志收口，绝不留静默空目录（否则 `AgentLibrary.get` 仍解析
   * 不到、队列仍无消费者，而现场看起来「装过了」）。
   */
  private def installAgentFromSeed(root: os.Path, name: String, outcome: String): Boolean =
    val targetDir = root / "agents" / name
    val base = os.SubPath(s"seed/agents/$name")
    val files = resourceDirList(base, "agent.json")
    if files.isEmpty then
      logger.warnSync(
        s"Seed: agent '$name' is missing in this home but has no seed resources on the classpath — cannot self-heal " +
          s"(stale jar / missing seed/agents/$name). The memory queue would then have no consumer; nothing was written"
      )
      false
    else
      files.foreach { rel =>
        readResource(join(base, rel)).foreach { text =>
          val target = targetDir / rel
          os.makeDir.all(target / os.up)
          os.write.over(target, text)
        }
      }
      logger.infoSync(
        s"Seed: agent '$name' $outcome (${files.size} file(s) from seed/agents/$name/, zero overwrite of existing dirs)"
      )
      true

    end if

  end installAgentFromSeed

  /**
   * 启动期消费链校验（2026-09-13 缺失自愈批 / 方案 D「启动明确告警」）：播种 + reconcile
   * 跑完之后，默认集里**记忆队列的消费者**（[[MemoryConsumptionAgent]]）是否真的就位。
   * 不就位 ⇒ 响亮 WARN（说明后果：队列只进不出、`MemoryNote` 是纯记账、没有任何东西会
   * 被应用）——这是把「静默 no-op」变成「启动即可见」的最后一道门。
   * 零副作用：只读文件系统，不建目录、不写 marker。`private[seed]`：spec 直测面。
   */
  private[seed] def verifyMemoryConsumptionChain(root: os.Path): Unit =
    val dir = root / "agents" / MemoryConsumptionAgent
    val ok = os.exists(dir / "agent.json") && os.exists(dir / "system.md")
    if !ok then
      logger.warnSync(
        s"Seed: MEMORY CONSUMPTION CHAIN MISSING — 'agents/$MemoryConsumptionAgent/{agent.json,system.md}' is not in this home " +
          s"(seed = src/main/resources/seed/agents/$MemoryConsumptionAgent/). The memory queue (${root.toString}/memory/queue.jsonl) " +
          s"therefore has NO consumer: every recorded MemoryNote note stays pending forever, nothing is ever applied to the " +
          s"memory files, and the queue only accumulates. Fix = restore the seed resources and restart, or install that agent manually."
      )
    else logger.infoSync(s"Seed: memory consumption chain present (agents/$MemoryConsumptionAgent)")

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
    os.walk(dir)
      .filter(os.isFile)
      .toList
      .map(f => f.relativeTo(dir).toString -> sha256File(os.read.bytes(f)))
      .sortBy(_._1)

  private def sha256File(bytes: Array[Byte]): String =
    val md = MessageDigest.getInstance("SHA-256")
    md.digest(bytes).map("%02x".format(_)).mkString

  /**
   * 硬条件 1 的差集计算：**运行时独有行**（逐文件比对同 rel 路径的 seed 文件；
   * 运行时独有文件 ⇒ 其全部行都算独有）。返回「文件 → (行号, 原文) 列表」。
   * 纯函数式读取，零写入——本方法只读不写。
   */
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

  end runtimeUniqueLines

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

  /**
   * 枚举 classpath 资源目录（prefix）下全部文件。Anchor 在已知文件 plugin.json 上，
   * 以覆盖「jar 无目录条目」场景。sbt（file: URL）/ assembly（jar: URL）两种协议都处理。
   */
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
          os.walk(dir)
            .filter(os.isFile)
            .toList
            .map(p => os.SubPath(p.relativeTo(dir).toString))
        case "jar" =>
          val conn = url.openConnection().asInstanceOf[JarURLConnection]
          val jar = conn.getJarFile
          val base = s"${prefix.toString}/"
          jar
            .entries()
            .asScala
            .filter(e => !e.isDirectory && e.getName.startsWith(base))
            .map(e => os.SubPath(e.getName.stripPrefix(base)))
            .toList
        case _ => Nil
    }.distinct

  end resourceDirList

  /**
   * 项目 AGENTS.md 的兜底模板（种子资源缺失时用；短形态，不承载完整节点协议——
   * 与 `seed/projects/general/AGENTS.md` 的完整模板非同一文本）。路径由数据根拼出，
   * 禁写死绝对路径。
   */
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
