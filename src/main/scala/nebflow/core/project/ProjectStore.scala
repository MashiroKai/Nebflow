package nebflow.core.project

import cats.effect.{IO, Ref}
import io.circe.parser.parse as jsonParse
import io.circe.syntax.*
import nebflow.actor.ActorRef
import nebflow.agent.AgentCommand
import nebflow.core.{AtomicJson, NebflowLogger, PathUtil}

/**
 * Project 实体存储（#28 阶段 0）：
 * - 磁盘 `~/.nebflow/projects/<name>/project.json`（定义）
 * - 工作区 `.nebflow/`（Agent.md + flow-map.json + .gitignore）
 * - ProjectRegistry：name → ProjectActor ref（挂载映射，Mail 路由/前端面板用）
 */
object ProjectStore:
  private val logger = NebflowLogger.forName("nebflow.project.store")

  def projectsDir: os.Path = PathUtil.dataRoot / "projects"

  def projectDir(name: String): os.Path = projectsDir / name

  def projectJsonPath(name: String): os.Path = projectDir(name) / "project.json"

  /** Load a project definition by name. 名称非法（路径穿越）→ None。 */
  def load(name: String): IO[Option[ProjectDef]] =
    IO.blocking {
      if name.isEmpty || name.contains("/") || name.contains("\\") || name == "." || name == ".." then None
      else
        val p = projectJsonPath(name)
        if os.exists(p) then
          jsonParse(os.read(p)).flatMap(_.as[ProjectDef]) match
            case Right(pd) => Some(pd)
            case Left(e) =>
              logger.warnSync(s"Failed to parse project '$name': $e")
              None
        else None
    }

  /** List all projects. */
  def list(): IO[List[ProjectDef]] =
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

  /** Create a project definition + workspace `.nebflow/` scaffolding.
    *
    * 产出（验收①「只有 flow-map.json 被 store 写，无手写文件」相关）：
    * - `projects/<name>/project.json`（定义）
    * - `<workspace>/.nebflow/Agent.md`（模板）
    * - `<workspace>/.nebflow/flow-map.json`（由 FlowMapStore 首写）
    * - `<workspace>/.gitignore`（内容含 `.nebflow/`，防项目 repo 污染，R6——写 workspace 根，
    *   git 语义：`.nebflow/` 不带前导斜杠匹配任意层级；根已有 .gitignore → 追加不覆盖）
    * 已存在 → 拒绝（防覆盖）。
    */
  def create(
    name: String,
    workspace: String,
    description: Option[String],
    agentMdTemplate: String
  ): IO[Either[String, ProjectDef]] =
    if name.isEmpty || name.contains("/") || name.contains("\\") || name == "." || name == ".." then
      IO.pure(Left(s"Invalid project name: '$name'"))
    else
      IO.blocking {
        if os.exists(projectJsonPath(name)) then Left(s"Project '$name' already exists")
        else
          val ws = os.Path(workspace, PathUtil.dataRoot)
          val nebflowDir = ws / ".nebflow"
          os.makeDir.all(nebflowDir)
          val agentFile = (nebflowDir / "Agent.md").toString
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
          // 工作区 .nebflow/ 脚手架（仅缺省时写，不覆盖已有 Agent.md）
          if !os.exists(nebflowDir / "Agent.md") then os.write.over(nebflowDir / "Agent.md", agentMdTemplate)
          // R6：.gitignore 写 workspace 根（防 .nebflow/ 落项目 repo）；根已有 → 追加 .nebflow/ 行
          writeNebflowGitignore(ws)
          Right(pd)
      }

  /** R6：workspace 根 .gitignore 防项目 repo 污染（`.nebflow/` 不落 repo）。
    * 根已有 .gitignore → 若未含 `.nebflow/` 行则追加（不覆盖用户已有内容）。
    * 匹配 `.nebflow/` 与 `.nebflow` 两种写法（均忽略目录本身）。 */
  private def writeNebflowGitignore(ws: os.Path): Unit =
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
    else os.write.over(gi, ".nebflow/\n")

  def delete(name: String): IO[Unit] =
    IO.blocking {
      if os.exists(projectDir(name)) then os.remove.all(projectDir(name))
    }

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
