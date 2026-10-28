package nebflow.core.project

import cats.effect.{IO, Ref}
import io.circe.Json
import io.circe.parser.parse as jsonParse
import io.circe.syntax.*
import nebflow.actor.{ActorRef, AgentCommand}
import nebflow.core.AtomicJson
import nebflow.shared.{NebflowLogger, PathUtil}

/**
 * Project 实体存储（#28 阶段 0）：
 * - 磁盘 `~/.nebflow/projects/<name>/project.json`（定义）
 * - 工作区根 `AGENTS.md`（agent 指令模板）+ `.nebflow/`（flow-map.json + .gitignore）
 * - ProjectRegistry：name → ProjectActor ref（挂载映射，Mail 路由/前端面板用）
 */
object ProjectStore:
  private val logger = NebflowLogger.forName("nebflow.project.store")

  /**
   * 脚手架**逐件**读数（作者 2026-09-17 12:09 裁定单 ③-8/③-9）。
   *
   * `created` = 本次调用**写盘**了该件（缺失即补）；`skipped` = 既有件原样未动。
   * `detail` 承载第三种实况的可读注记（`.gitignore` 追加到既有文件 / 既有文件已含条目），
   * 不改变 created/skipped 两值语义。
   */
  final case class ScaffoldItem(item: String, created: Boolean, detail: String = "")

  /** 一次补缺的逐件报告 + 一行摘要（③-9「有 created 时一行摘要」）。 */
  final case class ScaffoldReport(items: List[ScaffoldItem]):
    def createdCount: Int = items.count(_.created)
    def skippedCount: Int = items.size - createdCount

    /** 逐件列 created/skipped；有 created 时追加一行摘要，无 created 时明说无需补缺。 */
    def render: String =
      val perItem = items
        .map(i =>
          s"${i.item} ${if i.created then "created" else "skipped"}" +
            (if i.detail.nonEmpty then s" (${i.detail})" else "")
        )
        .mkString(", ")
      if createdCount > 0 then s"$perItem — $createdCount created, $skippedCount skipped"
      else s"$perItem — nothing to do"

  def projectsDir: os.Path = PathUtil.dataRoot / "projects"

  def projectDir(name: String): os.Path = projectsDir / name

  def projectJsonPath(name: String): os.Path = projectDir(name) / "project.json"

  // ============================================================
  // 设备项目（跨设备挂载批 2026-10-25）
  // ============================================================

  /**
   * 设备归属解析单点：`origin` 在场且 device 非 self 字面 ⇒ Some(origin)——
   * 该项目的 workspace 本体在**对端设备本盘**，引擎侧一切「把 workspace 当本机
   * 盘路径读写」的面必须改走 [[brainWorkspace]] 的本机大脑根。None = 本机项目
   * （现状逐字节）。
   */
  def deviceOrigin(pd: ProjectDef): Option[ProjectOrigin] =
    pd.origin.filter(o => !ProjectOrigin.isSelfLiteral(o.device))

  // ============================================================
  // 设备归属显式标注（台账 #913 ②，2026-10-26）
  // ============================================================

  /**
   * 归属判定单点（工具/上下文/REST 各显示面的唯一取值口，禁各自判）：
   * 显式 `device` 字段优先 → origin 回导（非 self 字面才认）→ 缺省 self。
   * 返回值 = 存储值域（`self` 字面或对端 deviceId）；显示名由调用面经
   * [[displayDevice]] 对名册现查（core.device 不持有名册）。
   */
  def effectiveDevice(pd: ProjectDef): String =
    pd.device
      .map(_.trim)
      .filter(_.nonEmpty)
      .orElse(pd.origin.filter(o => !ProjectOrigin.isSelfLiteral(o.device)).map(_.device))
      .getOrElse(ProjectOrigin.Self)

  /**
   * 存储值 → 显示名：`self`/空 = "local"；deviceId = 名册现查（`nameOf` 未命中 =
   * 原样 id——诚实降级，绝不冒认）。
   */
  def displayDevice(device: String, nameOf: String => Option[String]): String =
    if ProjectOrigin.isSelfLiteral(device) then "local"
    else nameOf(device.trim).getOrElse(device.trim)

  /**
   * #913 ② 缺字段回填：project.json 无 `device` 键的定义，加载时手术式原位插键
   * （读原始 Json → deepMerge device → AtomicJson 原子写——与 [[archive]] 同形状，
   * 不走 ProjectDef 全量 re-encode：存量字段布局逐字节稳定）。幂等：有键不写。
   * 失败只 WARN（迁移面非关键路径，**绝不阻断加载**）。
   */
  private def backfillDevice(name: String, p: os.Path, json: io.circe.Json, eff: String): Unit =
    val already = json.hcursor.downField("device").as[String].toOption.exists(_.trim.nonEmpty)
    if already then ()
    else
      try
        val updated = json.deepMerge(io.circe.Json.obj("device" -> io.circe.Json.fromString(eff)))
        AtomicJson.writeSync(p, updated.noSpaces)
        logger.infoSync(s"Project '$name': device annotation backfilled (device=$eff)")
      catch
        case e: Exception =>
          logger.warnSync(s"Project '$name': device annotation backfill failed (load continues): ${e.getMessage}")

  /** Backfill 的纯判定面（spec 直测）：定义缺显式 device ⇒ Some(存储值)；有键 ⇒ None（零写入）。 */
  def deviceBackfillValue(pd: ProjectDef): Option[String] =
    pd.device.map(_.trim).filter(_.nonEmpty).fold(Some(effectiveDevice(pd)))(_ => None)

  /**
   * 引擎侧大脑根（跨设备挂载批 2026-10-25 ·「大脑在引擎，账本一本」）：
   *   - 本机项目 = `pd.workspace` 本身（现状逐字节，存量零迁移）；
   *   - 设备项目 = `<dataRoot>/projects/<name>/brain`——FlowMap/TaskBoard/链台账/
   *     节点结果/事件日志等**全部引擎本机存储**锚在这里，绝不写设备路径（G.12
   *     路径错觉防护的挂载面落点：挂载存储根若锚在 `C:\...` 这类设备本盘路径，
   *     本机落笔即在 Mac 数据根下造出第二份假骨架——现场事故 2026-10-24）。
   *
   * 单点纪律：任何引擎本机读写面（REST agent.md / file-changes、事件日志、
   * 分发器会话座位…）一律经本函数取座位；展示面（面板 workspace 字段、文件
   * 浏览器根）仍用 `pd.workspace` 原值（= 设备本盘真实路径，配 device 通道）。
   */
  def brainWorkspace(pd: ProjectDef): String =
    deviceOrigin(pd).fold(pd.workspace)(_ => (projectDir(pd.name) / "brain").toString)

  /**
   * Load a project definition by name. 名称非法（路径穿越）→ None。
   *
   * E.3 双轨移除（裁定 13）：加载即执行旧位迁移（幂等，见 migrateAgentMd）——
   * 启动挂载/REST 首触后 canonical = 工作区根 AGENTS.md，注入面
   * （ContextRefresher.resolveAgentsMd）读到稳定根文件。
   */
  def load(name: String): IO[Option[ProjectDef]] =
    IO.blocking {
      if name.isEmpty || name.contains("/") || name.contains("\\") || name == "." || name == ".." then None
      else
        val p = projectJsonPath(name)
        if os.exists(p) then
          jsonParse(os.read(p)) match
            case Right(json) =>
              json.as[ProjectDef] match
                case Right(pd) =>
                  // 迁移失败不阻断项目加载（失败态由 WARN 留痕，下次 load 重试）
                  try migrateAgentMd(os.Path(pd.workspace, PathUtil.dataRoot), name)
                  catch
                    case e: Exception =>
                      logger.warnSync(s"Project '$name': Agent.md migration failed: ${e.getMessage}")
                  // #913 ② 设备归属标注回填（缺键才写，幂等；失败只 WARN 不阻断——
                  // 手术式原位插键用同一份已解析 Json，零二次读盘）
                  deviceBackfillValue(pd).foreach(eff => backfillDevice(name, p, json, eff))
                  Some(pd.copy(device = Some(effectiveDevice(pd))))
                case Left(e) =>
                  logger.warnSync(s"Failed to parse project '$name': $e")
                  None
            case Left(e) =>
              logger.warnSync(s"Failed to parse project '$name': $e")
              None
        else None
        end if
    }

  /**
   * E.3：`.nebflow/Agent.md` 旧位迁移到工作区根 AGENTS.md（三分支，幂等）：
   * ① 仅旧位（真文件或 symlink）→ 内容落根 + 删旧位——symlink 只删链接本体、
   *   目标文件不动（已迁移项目先例）；内容经链接读目标。
   * ② 两者并存 → 根 AGENTS.md 胜出（canonical，裁定 13）+ WARN，旧位不动。
   *   特例：旧位是 symlink 且解析到根文件本身（os.isSameFile）＝已迁移稳态，
   *   no-op 不告警（否则主仓等 symlink 兼容项目每次 load 刷 WARN）。
   * ③ 旧位不存在（含悬空 symlink——os.exists 跟随链接判存在）→ no-op。
   */
  private def migrateAgentMd(ws: os.Path, projectName: String): Unit =
    val legacy = ws / ".nebflow" / "Agent.md"
    val root = ws / "AGENTS.md"
    if os.exists(legacy) then
      val sameAsRoot =
        try java.nio.file.Files.isSameFile(legacy.toNIO, root.toNIO)
        catch case _: Exception => false
      if sameAsRoot then () // 已迁移稳态（symlink → 根文件）
      else if os.exists(root) then
        logger.warnSync(
          s"Project '$projectName': both AGENTS.md and legacy .nebflow/Agent.md exist — AGENTS.md (workspace root) wins (裁定 13), legacy left untouched"
        )
      else
        os.write.over(root, os.read(legacy)) // symlink 场景经链接读目标内容
        os.remove(legacy) // symlink 只删链接本体，目标不动
        logger.infoSync(
          s"Project '$projectName': migrated legacy .nebflow/Agent.md -> workspace-root AGENTS.md (E.3 dual-track removal)"
        )

    end if

  end migrateAgentMd

  /**
   * List all projects. 归档与已卸载项目不在列（迁移方案 v2 §6.1 归档 + N2 project-tool
   * 批 unmount，2026-10-06）：本函数是全仓唯一列表源——面板 API（GET /projects）与
   * 启动挂载（GatewayMain startupMount）同源消费，源头过滤 ⇒ 归档即从面板消失 +
   * 重启不自动挂载；unmount 同面（先停后卸，硬卸载档）⇒ 卸载即从面板消失 + 重启
   * 不自动挂载（「项目列表过滤＋startupMount 跳过」单点）。
   * 恢复显示 = 手工删除 project.json 中 archived/archivedAt（或 unmounted/unmountedAt）
   * 两键（单程语义，无自动路径）。
   */
  def list(): IO[List[ProjectDef]] =
    listAll().map(_.filterNot(pd => pd.archived.contains(true) || pd.unmounted.contains(true)))

  /**
   * 全量定义（**含归档**）——同一读盘实现，list() 只在其上做归档过滤（语义零变化）。
   *
   * 唯一消费者 = Project（原 ProjectCreate）创建面的 workspace 占用扫描：占用者**含归档定义**
   * （保守默认「宁误拒不误建」；该保守面由实施批分发器定，非作者逐字裁定，
   * 见报告「判据声明」节）。归档项若不参与占用扫描，就会出现「已占用 workspace
   * 被第二个 name 静默复用」——正是本批要堵的缺口。
   */
  def listAll(): IO[List[ProjectDef]] =
    IO.blocking {
      if !os.exists(projectsDir) then Nil
      else
        os.list(projectsDir)
          .filter(os.isDir)
          .map { dir =>
            val p = dir / "project.json"
            if os.exists(p) then jsonParse(os.read(p)).flatMap(_.as[ProjectDef]).toOption
            else None
          }
          .flatten
          .toList
    }

  /**
   * 归档（迁移方案 v2 §6.1）：project.json 原位手术式插键——读原始 Json →
   * deepMerge 写入 archived=true + archivedAt → AtomicJson 原子写回。
   * 不走 ProjectDef 全量 re-encode：derived codec 会把 None 字段编码为 null、
   * 重排既有字段布局；手术式插键保证存量字段逐字节稳定，且条件序列化天然成立
   * （键只在归档后出现）。零删除零移动：只重写这一个文件，workspace 与目录不动。
   * 幂等：已归档 → 不重写文件，返回既有 archivedAt。
   * 返回 Right(归档时间戳)；项目不存在/名字非法/JSON 损坏 → Left。
   */
  def archive(name: String): IO[Either[String, Long]] =
    if name.isEmpty || name.contains("/") || name.contains("\\") || name == "." || name == ".." then
      IO.pure(Left(s"Invalid project name: '$name'"))
    else
      IO.blocking {
        val p = projectJsonPath(name)
        if !os.exists(p) then Left(s"Project '$name' not found")
        else
          jsonParse(os.read(p)) match
            case Left(e) => Left(s"Corrupt project.json for '$name': $e")
            case Right(json) =>
              val alreadyArchived = json.hcursor.downField("archived").as[Boolean].toOption.contains(true)
              if alreadyArchived then
                val at = json.hcursor.downField("archivedAt").as[Long].toOption.getOrElse(System.currentTimeMillis())
                Right(at)
              else
                val ts = System.currentTimeMillis()
                val updated = json.deepMerge(
                  Json.obj("archived" -> Json.fromBoolean(true), "archivedAt" -> Json.fromLong(ts))
                )
                AtomicJson.writeSync(p, updated.noSpaces)
                logger.infoSync(s"Project '$name' archived (marker written to project.json; files untouched)")
                Right(ts)
        end if
      }

  /**
   * unmount 打标（N2 project-tool 批 2026-10-06，S17/⑩A 硬卸载档 · ②挂载面移除步）：
   * 与 [[archive]] 同一手术式原位插键形状——读原始 Json → deepMerge 写入
   * unmounted=true + unmountedAt → AtomicJson 原子写回。不走 ProjectDef 全量
   * re-encode（derived codec 会把 None 字段编码为 null、重排既有字段布局）；手术式
   * 插键保证存量字段逐字节稳定。零删除零移动：只重写这一个文件，workspace 与目录
   * 不动。幂等：已卸载 → 不重写文件，返回既有 unmountedAt。调用方纪律：本方法只打标
   * 不停任务——「先停后卸」的 ①停步由 ProjectUnmount.stopInFlight 在**前置**完成，
   * 调用序错置即违反三步序（禁）。
   * 返回 Right(卸载时间戳)；项目不存在/名字非法/JSON 损坏 → Left。
   */
  def markUnmounted(name: String): IO[Either[String, Long]] =
    if name.isEmpty || name.contains("/") || name.contains("\\") || name == "." || name == ".." then
      IO.pure(Left(s"Invalid project name: '$name'"))
    else
      IO.blocking {
        val p = projectJsonPath(name)
        if !os.exists(p) then Left(s"Project '$name' not found")
        else
          jsonParse(os.read(p)) match
            case Left(e) => Left(s"Corrupt project.json for '$name': $e")
            case Right(json) =>
              val alreadyUnmounted = json.hcursor.downField("unmounted").as[Boolean].toOption.contains(true)
              if alreadyUnmounted then
                val at = json.hcursor.downField("unmountedAt").as[Long].toOption.getOrElse(System.currentTimeMillis())
                Right(at)
              else
                val ts = System.currentTimeMillis()
                val updated = json.deepMerge(
                  Json.obj("unmounted" -> Json.fromBoolean(true), "unmountedAt" -> Json.fromLong(ts))
                )
                AtomicJson.writeSync(p, updated.noSpaces)
                logger.infoSync(s"Project '$name' unmounted (marker written to project.json; files untouched)")
                Right(ts)
        end if
      }

  /**
   * description 更新（台账 #913 ④，2026-10-26）：**只改 description 一个字段**
   * 的手术式原位写（读原始 Json → deepMerge/删键 → AtomicJson 原子写回——与
   * [[archive]] 同形状，不走 ProjectDef 全量 re-encode：其余字段逐字节稳定）。
   *
   * 幂等：现值与目标一致（含「目标空 + 键已缺席」）→ 零写入，`Right(unchanged)`。
   * 空目标（trim 后空串）= **清空**：删 description 键（解码回 None，与 create 面
   * 「空 description 视作缺席」同语义）。非空目标 = set 键。
   * 返回：Right("updated") 写入成功 / Right("unchanged") 幂等零写入；
   * 名字非法 / 项目不存在 / JSON 损坏 → Left（错误文案与 archive/markUnmounted 同族）。
   */
  def updateDescription(name: String, description: String): IO[Either[String, String]] =
    if name.isEmpty || name.contains("/") || name.contains("\\") || name == "." || name == ".." then
      IO.pure(Left(s"Invalid project name: '$name'"))
    else
      IO.blocking {
        val p = projectJsonPath(name)
        if !os.exists(p) then Left(s"Project '$name' not found")
        else
          jsonParse(os.read(p)) match
            case Left(e) => Left(s"Corrupt project.json for '$name': $e")
            case Right(json) =>
              val current = json.hcursor.downField("description").as[Option[String]].toOption.flatten
              val target = Option(description).map(_.trim).filter(_.nonEmpty)
              if current.map(_.trim).filter(_.nonEmpty) == target then Right("unchanged")
              else
                val updated = target match
                  case Some(d) => json.deepMerge(io.circe.Json.obj("description" -> io.circe.Json.fromString(d)))
                  case None =>
                    // 清空 = 删键（吸收既有 null/空串形态；json deepMerge 无法删键，手工重建对象）
                    io.circe.Json.fromJsonObject(
                      json.asObject.getOrElse(io.circe.JsonObject.empty).remove("description")
                    )
                AtomicJson.writeSync(p, updated.noSpaces)
                logger.infoSync(s"Project '$name': description updated (${target.fold("<cleared>")(_.take(60))})")
                Right("updated")
        end if
      }

  /**
   * Create a project definition + workspace `.nebflow/` scaffolding.
   *
   * 产出（验收①「只有 flow-map.json 被 store 写，无手写文件」相关）：
   * - `projects/<name>/project.json`（定义）
   * - `<workspace>/AGENTS.md`（agent 指令模板，工作区根——与市面标准统一）
   * - `<workspace>/.nebflow/flow-map.json`（由 FlowMapStore 首写）
   * - `<workspace>/.gitignore`（内容含 `.nebflow/`，防项目 repo 污染，R6——写 workspace 根，
   *   git 语义：`.nebflow/` 不带前导斜杠匹配任意层级；根已有 .gitignore → 追加不覆盖）
   * 已存在 → 拒绝（防覆盖）。
   *
   * 脚手架写入单点 = [[ensureScaffold]]（作者 2026-09-17 12:09 裁定单 ③-8：「缺件即补、
   * 既有永不覆盖」）。本函数对外语义**不变**：定义已存在仍返 Left（防覆盖 +
   * `ProjectStoreSpec` 「duplicate create rejected」钉住），补缺只发生在缺件面，
   * 既有件逐字节不变。需要逐件 created/skipped 报告的调用方用 [[createWithScaffold]]。
   */
  def create(
    name: String,
    workspace: String,
    description: Option[String],
    agentMdTemplate: String
  ): IO[Either[String, ProjectDef]] =
    createWithScaffold(name, workspace, description, agentMdTemplate).map(_.map(_._1))

  /**
   * [[create]] + 本次补缺的逐件报告（③-9 结果文案的数据源）。
   *
   * `origin`（v2.1 §A.5，2026-10-09 实施批）：缺省 None = 现状零变化；`Some(o)`
   * 且 `o.device` 非 self ⇒ **remote-origin 创建**——本地零脚手架（**绝不**在
   * workspace 路径上 makeDir/写文件——该路径是远端设备的本盘路径，本机落笔即
   * G.12 路径错觉），ProjectDef.origin 落字段 + agentFile 指向 origin 盘的
   * AGENTS.md（远端脚手架腿由调用方（ProjectCreateService）在**本函数之前**经
   * remote-exec 完成——G.13 半态回滚序：先校验 + 远端脚手架，再原子写本定义）。
   */
  def createWithScaffold(
    name: String,
    workspace: String,
    description: Option[String],
    agentMdTemplate: String,
    origin: Option[ProjectOrigin] = None
  ): IO[Either[String, (ProjectDef, ScaffoldReport)]] =
    if name.isEmpty || name.contains("/") || name.contains("\\") || name == "." || name == ".." then
      IO.pure(Left(s"Invalid project name: '$name'"))
    else
      IO.blocking {
        if os.exists(projectJsonPath(name)) then Left(s"Project '$name' already exists")
        else
          val ws = os.Path(workspace, PathUtil.dataRoot)
          val now = System.currentTimeMillis()
          val remote = origin.exists(o => !ProjectOrigin.isSelfLiteral(o.device))
          val pd =
            if remote then
              // 跨设备批（2026-10-25）：设备项目的 `workspace` 字段存**设备本盘原样
              // 路径**（= origin.path），绝不经本机 os.Path 解析——`C:\Users\Kai\stand`
              // 在 Mac 上经 `os.Path(_, dataRoot)` 会得到数据根下的假名路径（现场
              // 事故原形）。引擎本机读写面走 [[brainWorkspace]]；展示面与文件浏览器
              // 设备腿用本值 + origin.device 经设备通道。
              // #913 ②：设备归属显式标注 = origin.device（与 origin 同源，读面
              // effectiveDevice 单点归一）。
              ProjectDef(
                name = name,
                description = description,
                workspace = origin.get.path,
                agentFile = s"${origin.get.path}/AGENTS.md",
                createdAt = now,
                origin = origin,
                device = Some(origin.get.device)
              )
            else
              ProjectDef(
                name = name,
                description = description,
                workspace = ws.toString,
                agentFile = (ws / "AGENTS.md").toString,
                createdAt = now,
                origin = origin,
                // #913 ②：本机项目显式标 self（缺席由 load 回填补齐，创建面直写省一次回填写）
                device = Some(ProjectOrigin.Self)
              )
          os.makeDir.all(projectDir(name))
          AtomicJson.writeSync(projectJsonPath(name), pd.asJson.noSpaces)
          // remote-origin：本地零脚手架（空报告——脚手架已由远端腿完成并验收）。
          val report = if remote then ScaffoldReport(Nil) else ensureScaffoldSync(ws, agentMdTemplate)
          Right((pd, report))
      }

  /**
   * ③-8 补缺脚手架单点（作者 2026-09-17 12:09 裁定单）：逐件「缺失即补、既有永不覆盖」，
   * 返回逐件 created/skipped。
   *
   * **两条路径共用**（同批裁定）：① 新建（[[createWithScaffold]] 内部）；② 幂等挂载
   * （`NodeTools.createChain` 的 sameWorkspace 分支——定义已存在但工作区后来丢了件，
   * 现状无自愈路径，本函数补上）。归档分支与异 workspace 分支**不得调用**（保持零写入）。
   *
   * 🔴 不写 `.nebflow/flow-map.json`（由挂载首写，`FlowMapStore.scala:1243/1274`）；
   * `.nebflow/` 只建空目录。
   */
  def ensureScaffold(ws: os.Path, agentMdTemplate: String): IO[ScaffoldReport] =
    IO.blocking(ensureScaffoldSync(ws, agentMdTemplate))

  private def ensureScaffoldSync(ws: os.Path, agentMdTemplate: String): ScaffoldReport =
    os.makeDir.all(ws)
    val nebflowDir = ws / ".nebflow"
    val dirExisted = os.exists(nebflowDir)
    os.makeDir.all(nebflowDir)
    val agentsMd = ws / "AGENTS.md"
    val agentsExisted = os.exists(agentsMd)
    // agent 指令模板写工作区根 AGENTS.md（仅缺省时写，不覆盖已有；旧位置 .nebflow/Agent.md 由读路径回落兼容）
    if !agentsExisted then os.write.over(agentsMd, agentMdTemplate)
    ScaffoldReport(
      List(
        ScaffoldItem(
          ".nebflow/",
          created = !dirExisted,
          detail =
            if dirExisted then "already present"
            else "empty dir only (flow-map.json is written on mount)"
        ),
        ScaffoldItem(
          "AGENTS.md",
          created = !agentsExisted,
          detail = if agentsExisted then "existing content kept" else ""
        ),
        writeNebflowGitignore(ws)
      )
    )

  end ensureScaffoldSync

  /**
   * R6：workspace 根 .gitignore 防项目 repo 污染（`.nebflow/` 不落 repo）。
   * 根已有 .gitignore → 若未含 `.nebflow/` 行则追加（不覆盖用户已有内容）。
   * 匹配 `.nebflow/` 与 `.nebflow` 两种写法（均忽略目录本身）。
   */
  private def writeNebflowGitignore(ws: os.Path): ScaffoldItem =
    val gi = ws / ".gitignore"
    if os.exists(gi) then
      val content = os.read(gi)
      val hasEntry = content.linesIterator.exists { line =>
        val t = line.trim
        t == ".nebflow/" || t == ".nebflow"
      }
      if !hasEntry then
        val sep = if content.endsWith("\n") then "" else "\n"
        os.write.append(gi, s"$sep.nebflow/\n")
        ScaffoldItem(".gitignore", created = true, detail = "appended to the existing file")
      else ScaffoldItem(".gitignore", created = false, detail = "already lists .nebflow/")
    else
      os.write.over(gi, ".nebflow/\n")
      ScaffoldItem(".gitignore", created = true, detail = "")

  end writeNebflowGitignore

  def delete(name: String): IO[Unit] =
    IO.blocking {
      if os.exists(projectDir(name)) then os.remove.all(projectDir(name))
    }

end ProjectStore

/** 运行期注册表：project name → ProjectActor ref（挂载映射）。 */
object ProjectRegistry:
  private val actors = Ref.unsafe[IO, Map[String, ActorRef[ProjectActor.ProjectCommand]]](Map.empty)

  def register(name: String, ref: ActorRef[ProjectActor.ProjectCommand]): IO[Unit] =
    actors.update(_ + (name -> ref))

  def unregister(name: String): IO[Unit] =
    actors.update(_ - name)

  def get(name: String): IO[Option[ActorRef[ProjectActor.ProjectCommand]]] =
    actors.get.map(_.get(name))

  def all: IO[Map[String, ActorRef[ProjectActor.ProjectCommand]]] = actors.get

  def clear: IO[Unit] = actors.set(Map.empty)
end ProjectRegistry
