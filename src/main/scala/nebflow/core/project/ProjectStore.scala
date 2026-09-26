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
          jsonParse(os.read(p)).flatMap(_.as[ProjectDef]) match
            case Right(pd) =>
              // 迁移失败不阻断项目加载（失败态由 WARN 留痕，下次 load 重试）
              try migrateAgentMd(os.Path(pd.workspace, PathUtil.dataRoot), name)
              catch
                case e: Exception =>
                  logger.warnSync(s"Project '$name': Agent.md migration failed: ${e.getMessage}")
              Some(pd)
            case Left(e) =>
              logger.warnSync(s"Failed to parse project '$name': $e")
              None
        else None
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
   * List all projects. 归档项目不在列（迁移方案 v2 §6.1）：本函数是全仓唯一列表源——
   * 面板 API（RestApiRoutes GET /projects）与启动挂载（GatewayMain startupMount）
   * 同源消费，源头过滤 → 归档即从面板消失 + 重启不自动挂载。
   * 恢复显示 = 手工删除 project.json 中 archived/archivedAt 两键（单程语义，无自动路径）。
   */
  def list(): IO[List[ProjectDef]] =
    listAll().map(_.filterNot(_.archived.contains(true)))

  /**
   * 全量定义（**含归档**）——同一读盘实现，list() 只在其上做归档过滤（语义零变化）。
   *
   * 唯一消费者 = ProjectCreate 创建面的 workspace 占用扫描：占用者**含归档定义**
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

  /** [[create]] + 本次补缺的逐件报告（③-9 结果文案的数据源）。 */
  def createWithScaffold(
    name: String,
    workspace: String,
    description: Option[String],
    agentMdTemplate: String
  ): IO[Either[String, (ProjectDef, ScaffoldReport)]] =
    if name.isEmpty || name.contains("/") || name.contains("\\") || name == "." || name == ".." then
      IO.pure(Left(s"Invalid project name: '$name'"))
    else
      IO.blocking {
        if os.exists(projectJsonPath(name)) then Left(s"Project '$name' already exists")
        else
          val ws = os.Path(workspace, PathUtil.dataRoot)
          val agentFile = (ws / "AGENTS.md").toString
          val now = System.currentTimeMillis()
          val pd = ProjectDef(
            name = name,
            description = description,
            workspace = ws.toString,
            agentFile = agentFile,
            createdAt = now
          )
          os.makeDir.all(projectDir(name))
          AtomicJson.writeSync(projectJsonPath(name), pd.asJson.noSpaces)
          val report = ensureScaffoldSync(ws, agentMdTemplate)
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
