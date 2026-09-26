/* 从 NodeTools.scala 迁出(行为保持重构,2026-09-25)。 */
package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.{ActorRef, AgentCommand}
import nebflow.core.project.*
import nebflow.shared.{AskItem, PathUtil}

import scala.util.Try

object ProjectCreateTool extends Tool:
  val name = "ProjectCreate"

  // 2026-09-17 作者 12:09 裁定单 ④-10（更新旧条目、取代而非静默覆盖）：本 description
  // 的面板分支旧句逐字为「Clicking it opens the REAL OS folder browser (native directory
  // dialog on macOS/Windows) where the user browses, can create folders, and confirms; …
  // Candidate paths (first-level directories under ~/Claude code/ not already used as project
  // workspaces) remain on the card as secondary one-click hints, and the built-in "Other…"
  // free input accepts a custom absolute path.」——**该句已过时**：2026-09-09 裁定后实现
  // 为零候选 options + 应用内目录浏览器（`NodeTools.scala` 面板段 `:3217-3224`，
  // `ProjectCreatePanelSpec` 断言 `options == Nil`），OS 原生对话框/候选 chips/「Other…」
  // 三者**均不存在**。旧句按「取代而非静默覆盖」保留于此注记中可读，正文改写为实现一致。
  val description =
    """Create a Project (Nebula use) — project definition + workspace .nebflow/ scaffolding.
## When to Use
- **Known workspace path** (the user told you, or you know it): pass `workspace` (absolute path; `name`/`description` optional) — direct create: writes projects/<name>/project.json, workspace root AGENTS.md template, workspace/.nebflow/ + .gitignore scaffolding, and mounts the project (FlowMapStore + ProjectActor ready). Existing workspace files are never overwritten; only missing pieces are backfilled, and the result lists what was created vs left alone.
- **Unknown workspace path**: omit `workspace` — an AskUserQuestion-style card pops up on the user's window with a prominent "选择工作区" (pick workspace) target that opens the in-app folder browser (no native OS dialog (2026-09-06 裁定), no candidate chips (2026-09-09 裁定)). Alongside that target the card still shows a free-input box (displayed whenever the card carries no options), so the user can hand-type an absolute path with `~` expansion handled by the backend; retiring that free input is a separate S3 order that has not landed. The chosen path flows back into the card and creation proceeds automatically.
- `name` defaults to the workspace path's basename when omitted.
## After Creation
- Dispatch work with Mail(address="project:<name>", message=...) — the project is mounted and triggerable immediately.
## Semantics
- Same name + same workspace → idempotent (returns "already exists", re-mounts and backfills any missing scaffold file; safe to repeat).
- Same name + different workspace → explicit error (never silently re-points an existing project).
- **Different name + workspace already used by another project → explicit error (default-deny, 2026-09-17 裁定 ④-4/④-12).** Workspace occupancy is compared with trailing slashes and case ignored; an archived project still counts as occupying its workspace. The error names the occupying project and gives two ways out: reuse that project, or pass a different workspace directory. Never let a second project silently share an occupied workspace — report the conflict to the user instead.
- Panel dismissed (cancel / empty answer) → clear shelved message, nothing created — re-invoke with a known path or ask the user again."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "name" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Project name (single path segment). Optional — defaults to the workspace path basename.".asJson
        ),
        "workspace" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Absolute path to the project workspace. Omit to pop the interactive path-selection panel (AskUserQuestion-style card).".asJson
        ),
        "description" -> Json.obj("type" -> "string".asJson, "description" -> "Optional one-line description".asJson)
      ),
      "required" -> Json.arr()
    )
  )

  /** 前端卡片取消哨兵（chat.js 取消按钮回填 answers=['__cancelled__']）。 */
  val CancelSentinel = "__cancelled__"

  def summarize(input: JsonObject): String =
    val n = input("name").flatMap(_.asString).orElse(input("workspace").flatMap(_.asString))
    s"ProjectCreate(${n.getOrElse("<panel>")})"

  def summarizeResult(input: JsonObject, result: String): String = result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val rawName = input("name").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
    val rawWorkspace = input("workspace").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
    val description = input("description").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
    rawWorkspace match
      // 已知路径直建（口径①，既有路径语义不变，name 缺省改 basename 派生）。
      case Some(ws) =>
        Try(os.Path(ws, PathUtil.dataRoot)) match
          case scala.util.Success(_) => createChain(rawName, ws, description, ctx)
          case scala.util.Failure(_) => pathPanel(rawName, description, ctx) // path 不可用 → 面板兜底
      // 未知/缺省 path（口径②）→ AskUser 式交互面板。
      case None => pathPanel(rawName, description, ctx)

  // ============================================================
  // 创建链（直建与面板选择路径共用）
  // ============================================================

  /**
   * 创建 + 幂等挂载。name 缺省 = workspace basename（口径①）。
   * 同名冲突语义（任务口径）：同 workspace → 幂等（"already exists" + 挂载）；
   * 异 workspace → 明确报错（绝不静默改指旧定义）。
   */
  private def createChain(
    nameOpt: Option[String],
    workspace: String,
    description: Option[String],
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    val resolvedName = nameOpt.getOrElse(baseName(workspace))
    if resolvedName.isEmpty then
      IO.pure(Left(ToolError(s"Cannot derive project name from workspace '$workspace' — pass 'name' explicitly")))
    else
      /**
       * L1 **默认空**（promptopt 落地批 W3 · 2026-09-18 作者令，工作单 §2.3）：
       * `NodeEdit` 新建项目写出的工作区 `AGENTS.md` **不再预填任何内容** —— 模板 = 空
       * （`ensureScaffoldSync` 落盘成 0 字节文件），项目指令由项目/作者自持。
       * 语义边界零变化：`ProjectStore`「缺件即补、既有永不覆盖」照旧（本改动只影响
       * 「缺件补什么」，不动任何写入路径与既有件）；种子项目 general 的文本面属 L2
       * （`src/main/resources/seed/projects/general/AGENTS.md`），与本 val 不同源。
       */
      val agentMdTemplate: String = ""

      /**
       * 项目级实时事件（tabrealtime 批 2026-09-17 · 作者裁定 (b) 方案 B / (e) 两身份事件）。
       *
       * 走**既有推送面**（🔴 不自建第二套）：`ctx.wsSend` 在 WS 会话面的构造是
       * `makeRecordingWsSend(sessionId, (json) => wsHub.broadcast(json))`
       * （WebSocketRoutes.scala:395）⇒ 与既有 node 事件（ProjectActor.emitNodeEvent）
       * **同源同通道**，全连接广播（WsHub.scala:27-30）。无 wsSend（远程执行/无连接
       * 上下文）→ 静默 no-op，与 emitNodeEvent 的 `fold(IO.unit)` 同款语义。
       * 帧形只由 ProjectActor.projectCreatedFrame 生产（帧外壳单点）。
       */
      def emitProjectCreated(pd: ProjectDef, mounted: Boolean): IO[Unit] =
        ctx.wsSend.fold(IO.unit)(send => send(ProjectActor.projectCreatedFrame(pd, mounted)))

      /**
       * 挂载（新创建 + 已存在幂等共用）。ProjectRuntimeRegistry.mount 本身幂等：
       * 已挂载 → 直接返回现有 runtime（不重建不覆盖——rootSessionId 已在首次挂载
       * 用上链根接线；运行中重挂覆盖需重建 engine，试点期无此场景）。
       *
       * emit 面（§D-2 逐字）：**仅新建（created=true）广播一帧**；幂等重挂
       * （created=false，:2997 分支）**不 emit**（无视觉变化）。挂载失败（IO 失败）
       * 直接抛出 ⇒ 不 emit；rootKey 缺席的显式拒绝（下 case None 分支）为 Left
       * ⇒ 亦不 emit（失败帧不报成功）——该边界由前端低频兜底重拉（方案 C）覆盖。
       */
      def mountProject(
        pd: ProjectDef,
        created: Boolean,
        scaffold: Option[ProjectStore.ScaffoldReport] = None
      ): IO[Either[ToolError, String]] =
        // ③-9 逐件报告（作者 2026-09-17 12:09 裁定单）：成功/幂等两态的结果句统一带
        // 本次补缺读数（有 created 时含一行摘要）。既有 contains 子串（`Mail(address='project:`、
        // `already exists`）保持不变，只在句尾追加。
        val scaffoldSuffix: String = scaffold.fold("")(r => s" Scaffold: ${r.render}.")
        (ctx.actorSystem, ctx.sharedResources) match
          case (Some(system), Some(res)) =>
            // P0 接线修复（Explorer c759e8c）：mount 传**上链 rootSessionId**（真正顶层），
            // 非挂载者自身会话——否则 out="Nebula" 投递目标是挂载者（如 qa-backend），
            // 节点完成消息注入执行者形成自维持循环。fallback ctx.sessionId（老调用方）。
            // freshinstall-rootsessionid 批 M3（作者 09-14 裁定 ②）：**无会话上下文的
            // 挂载视为非法**——逐字对齐既有正例（SendConfirm.scala 的「先过滤非空、
            // 再显式拒绝」）：`orElse` 不过滤非空（`Some("")` 取胜 ⇒ 空桶），且末档
            // 伪造占位 `"default"` 是**凭空造的桶键**（作者明禁伪造兜底）⇒ 两处都走。
            // 调用面枚举（详见本批报告「M3 调用面枚举」）：ProjectCreate 的 ToolContext
            // 生产构造面共 4 处 —— AgentCore 会话面（有身份）/ WS AgentControl（不达本
            // 工具）/ neblink relay 与 neblink REST remote-exec（**两者都不携带任何会话
            // 身份**：请求体仅 action/params/projectRoot，无 sessionId 字段）⇒ 后者
            // 无法「显式传自己的身份」（凭空造身份=伪造）⇒ 走显式拒绝。
            val rootKey =
              ctx.rootSessionId.filter(_.nonEmpty).orElse(ctx.sessionId.filter(_.nonEmpty))
            rootKey match
              case None =>
                IO.pure(
                  Left(
                    ToolError(
                      "ProjectCreate refused to mount: this call carries no session context " +
                        "(both rootSessionId and sessionId are empty or absent), so the project's delivery root " +
                        "cannot be attributed — and inventing a placeholder root is not allowed. " +
                        "Re-invoke ProjectCreate from an agent session (Nebula / project dispatcher / project node)."
                    )
                  )
                )
              case Some(root) =>
                val mountedResult = ProjectRuntimeRegistry
                  .mount(pd, system, res, ctx.wsSend, root)
                  .as {
                    val verb = if created then "created" else "already exists"
                    Right(
                      s"Project '${pd.name}' $verb and mounted. Flow Map ready at ${pd.agentFile}. " +
                        s"Dispatch work with Mail(address='project:${pd.name}', message=...).$scaffoldSuffix"
                    )
                  }
                // 新建成功 → 先发帧再返回结果（挂载成功 ⇒ mounted=true）。
                if created then mountedResult.flatMap(r => emitProjectCreated(pd, mounted = true).as(r))
                else mountedResult
            end match
          case _ =>
            // 定义已就绪但无会话上下文（未挂载）：项目**已在磁盘上**（ProjectStore.create
            // 的成功分支才走到这里）⇒ 列表出口（GET /api/projects）会有它，前端必须收到
            // 事件才不陈旧 ⇒ mounted=false（§D-2 载荷语义）。幂等重挂（created=false）
            // 不 emit（无视觉变化）。
            val ready: Either[ToolError, String] =
              Right(s"Project '${pd.name}' definition ready. Mount requires an agent session.$scaffoldSuffix")
            if created then emitProjectCreated(pd, mounted = false).as(ready) else IO.pure(ready)
        end match
      end mountProject

      // ============================================================
      // ④ 反守卫（作者 2026-09-17 12:09 裁定单 ④-4 + ④-12：默认拒绝、宁误拒不误建）
      // ============================================================
      // 缺守卫的缺口（设计件项2 基线 3 现取）：创建链原有三类守卫只覆盖
      // 「同 name 覆盖 / 同 name 同 workspace 幂等 / 同 name 异 workspace 报错」，
      // **「新 name + 已被别的 name 占用的 workspace」零守卫** ⇒ 静默新建第二个项目
      // 共享同一 workspace（flow-map / task-board / worktrees 全按 workspace 落位，
      // 并列挂载必然互写；事故链见 .nebflow/reports/20260917_pcsys-mech-design.md 项2）。
      //
      // 判据纪律（逐条裁定）：
      // - **前置于任何写盘**：占用检查在 ProjectStore.create 之前 ⇒ 拒绝路径零写盘
      //   （projects/<newName>/ 不出现、workspace 逐件 sha 不变）。
      // - **占用判据与幂等判据共用同一函数** `sameWorkspace`（禁两套判据）：同一归一
      //   函数（绝对化 + 去尾斜杠 + **大小写不敏感**）。
      // - 🔴 禁引入 realpath/symlink 解析（未被裁定；`/tmp`↔`/private/tmp` 会改变现有语义）。
      // - 占用扫描源 = ProjectStore.listAll()（**含归档**定义，保守默认：宁误拒不误建；
      //   该保守面是分发器定的默认、非作者逐字裁定，见报告「判据声明」节）。
      // - 占用者 name 判等用 sameProjectName（与 ProjectRuntimeRegistry.get 的
      //   equalsIgnoreCase 兜底同源）：同 name（含仅大小写差）= 同一项目 ⇒ 幂等重挂不被误拒。
      ProjectStore.listAll().flatMap { defs =>
        defs.find(d => !sameProjectName(d.name, resolvedName) && sameWorkspace(d.workspace, workspace)) match
          case Some(occupant) =>
            IO.pure(Left(ToolError(occupiedWorkspaceError(resolvedName, workspace, occupant))))
          case None =>
            ProjectStore.createWithScaffold(resolvedName, workspace, description, agentMdTemplate).flatMap {
              case Right((pd, report)) => mountProject(pd, created = true, scaffold = Some(report))
              case Left(err) =>
                // 幂等挂载（试点重启恢复关键路径）：定义已存在 → 不重建定义；③-8 补缺脚手架
                // （缺件即补、既有永不覆盖；作者 2026-09-17 12:09 裁定单 ③-8 取代了此处原先
                // 「不动脚手架」的口径——它正是「删了 AGENTS.md 永不回、定义已存在的缺件态
                // 永不修复」的成因）。幂等重挂本就返回成功语义，补缺必须同批，否则静默零写入。
                // 同名异 workspace → 明确报错（不静默复用旧定义）。
                // 归档项目例外（迁移方案 v2 §6.1 单程语义）：拒绝挂载——否则出现「已挂载
                // 但面板不可见」（list 过滤）的僵尸态；恢复须先手工删 project.json 归档两键。
                ProjectStore.load(resolvedName).flatMap {
                  case Some(pd) if pd.archived.contains(true) =>
                    IO.pure(
                      Left(
                        ToolError(
                          s"Project '$resolvedName' is archived (hidden from the Projects panel). " +
                            s"Remove the 'archived'/'archivedAt' keys in ${PathUtil.dataRootRenderValue}/projects/$resolvedName/project.json to restore it first."
                        )
                      )
                    )
                  case None => IO.pure(Left(ToolError(err)))
                  case Some(pd) if sameWorkspace(pd.workspace, workspace) =>
                    // 🔴 补缺只挂本分支（成功幂等重挂）；归档分支与异 workspace 分支保持零写入。
                    ProjectStore
                      .ensureScaffold(os.Path(workspace, PathUtil.dataRoot), agentMdTemplate)
                      .flatMap(report => mountProject(pd, created = false, scaffold = Some(report)))
                  case Some(pd) =>
                    IO.pure(
                      Left(
                        ToolError(
                          s"Project '$resolvedName' already exists with a different workspace (${pd.workspace}) — " +
                            "choose another name or reuse the existing workspace"
                        )
                      )
                    )
                }
            }
      }

    end if

  end createChain

  /**
   * workspace 归一化（绝对化 + 去尾斜杠）——**展示与判据共用的同一归一形态**。
   * 不可解析 → None（视为不同）。
   * 🔴 禁把 realpath/symlink 解析并入本函数（未被裁定；`/tmp`↔`/private/tmp` 会改变
   * 现有语义）。
   */
  private def normalizeWorkspace(p: String): Option[String] =
    Try(os.Path(p, PathUtil.dataRoot).toString).toOption.map(stripTrailingSlashes)

  /**
   * 同一归一函数的**比较键**（大小写不敏感）——占用判据与幂等判据共用 [[sameWorkspace]]
   * 这**一条**判据（禁两套）。大小写不敏感归一由作者 2026-09-17 12:09 裁定单 ④-12 定
   * （「路径语义 + 大小写不敏感归一」，「宁误拒不误建」：在大小写敏感的 FS 上该归一更严）。
   */
  private def workspaceKey(p: String): Option[String] =
    normalizeWorkspace(p).map(_.toLowerCase(java.util.Locale.ROOT))

  private def sameWorkspace(a: String, b: String): Boolean =
    (workspaceKey(a), workspaceKey(b)) match
      case (Some(x), Some(y)) => x == y
      case _ => false

  /**
   * 项目标识判等（与 `ProjectRuntimeRegistry.get` 的 equalsIgnoreCase 兜底同源：
   * 项目标识天然大小写不敏感）。同 name（含仅大小写差）= **同一项目**，不是占用者
   * ⇒ 保证「同 name 同 workspace 幂等重挂不被误拒」（存量先例：name `nebflow` 的
   * workspace basename 为 `Nebflow`，仅大小写差，现役靠 registry 兜底解析）。
   */
  private def sameProjectName(a: String, b: String): Boolean =
    a == b || a.equalsIgnoreCase(b)

  /** 占用报错（可行动：点名占用者 name + 归档态 + 归一化 workspace + 两条出路）。 */
  private def occupiedWorkspaceError(newName: String, workspace: String, occupant: ProjectDef): String =
    val norm = normalizeWorkspace(workspace).getOrElse(workspace)
    val occupantWs = normalizeWorkspace(occupant.workspace).getOrElse(occupant.workspace)
    val arch = if occupant.archived.contains(true) then " (archived)" else ""
    s"Workspace '$norm' is already used by project '${occupant.name}'$arch " +
      s"(its project.json workspace = '$occupantWs'). ProjectCreate default-denies creating '$newName' " +
      "on an occupied workspace — a second project on the same workspace would silently share its " +
      "flow-map / task board / worktrees (2026-09-17 裁定 ④-4). Two ways out: " +
      s"(a) reuse the existing project — ProjectCreate(name='${occupant.name}') to re-mount it, or " +
      s"Mail(address='project:${occupant.name}', message=...) to dispatch work; " +
      s"(b) pass a different 'workspace' directory for '$newName'."

  /** 路径 basename（name 派生）；根路径等无 basename → ""（由调用方报错）。 */
  private def baseName(workspace: String): String =
    Try(os.Path(workspace, PathUtil.dataRoot)).map(_.last).getOrElse("")

  // ============================================================
  // 未知路径交互面板（口径②）
  // ============================================================

  /** '~' 展开（仅前缀语义，防 API 差异；非 ~ 开头原样返回）。 */
  def expandTilde(path: String): String =
    val home = sys.props("user.home")
    if path == "~" then home
    else if path.startsWith("~/") then home + path.drop(1)
    else path

  private sealed trait PanelAnswer

  private object PanelAnswer:
    /** 取消哨兵 / 空答案 → 搁置（不创建、不报错悬挂）。 */
    case object Shelved extends PanelAnswer

    /** 非绝对路径等不可用输入 → 明确报错（不创建）。 */
    case class BadPath(raw: String, why: String) extends PanelAnswer

    /** 合法绝对路径 → 进入创建链。 */
    case class Chosen(path: String) extends PanelAnswer

  /** 去尾部斜杠（Scala String.stripTrailing 无参版，斜杠语义手写）。 */
  private def stripTrailingSlashes(s: String): String = s.replaceAll("/+$", "")

  /**
   * 面板答案解析（纯函数，spec 覆盖）：首槽空 / 取消哨兵 → Shelved；
   * '~' 展开后非绝对 → BadPath；合法 → Chosen（去尾斜杠）。
   * isAbsolute 为跨平台判定（POSIX / 盘符 / UNC）——朴素 startsWith("/")
   * 会拒绝 Windows 盘符答案（diag-win-paths P1）。
   */
  private def parsePanelAnswer(answers: List[String]): PanelAnswer =
    val raw = answers.headOption.map(_.trim).getOrElse("")
    if raw.isEmpty || raw == CancelSentinel then PanelAnswer.Shelved
    else
      val expanded = expandTilde(raw)
      if !PathUtil.isAbsolute(expanded) then PanelAnswer.BadPath(raw, "path must be absolute")
      else PanelAnswer.Chosen(stripTrailingSlashes(expanded))

  /**
   * 面板答案落地（纯分派）：Shelved → 搁置消息；BadPath → 明确报错；
   * Chosen → 创建链。
   */
  private def applyPanelAnswer(
    ans: PanelAnswer,
    nameOpt: Option[String],
    description: Option[String],
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    ans match
      case PanelAnswer.Shelved =>
        IO.pure(
          Right(
            "ProjectCreate shelved: the path-selection panel was dismissed without a choice — " +
              "no project created. Re-invoke with a known 'workspace', or ask the user again."
          )
        )
      case PanelAnswer.BadPath(raw, why) =>
        IO.pure(
          Left(
            ToolError(
              s"Panel answer '$raw' is not usable ($why) — nothing created. " +
                "Re-invoke with an absolute 'workspace' path."
            )
          )
        )
      case PanelAnswer.Chosen(path) => createChain(nameOpt, path, description, ctx)

  /**
   * 未知路径分支：复用 AskUser pending 机制（AgentCommand.AskUser →
   * InteractionHub → 前端 AskUserQuestion 卡片），零新增前端/问答通道。
   *
   * 语义边界（全部与 AskUserQuestionTool 对齐，不另起一套）：
   * - headless（NEBFLOW_HEADLESS=1）→ askGuard 同款报错（无交互用户，面板会
   *   永久悬挂）；
   * - 无 agent 会话（REST 直调 / harness）→ 明确报错不悬挂；
   * - 等待无人工超时——与 AskUserQuestion 同语义（pending 期间会话标记
   *   WaitingForUser 豁免 TaskStuckWatcher；hub 缺席时 AgentActor 直接取消
   *   ask 回 Nil → 走 Shelved 搁置消息）；
   * - 取消/空答案 → 明确搁置消息（无创建、无悬挂）；
   * - 答案落定 → restoreRegistryAfterAnswer 配对恢复（与 AskUserQuestionTool
   *   同一实现——#43 修复的答案来源校验语义原样适用：面板答案只能来自用户
   *   点选帧 askUserAnswer（非 injected 用户消息），agent 侧注入负载永不消费）。
   */
  private def pathPanel(
    nameOpt: Option[String],
    description: Option[String],
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    AskUserQuestionTool.askGuard() match
      case Some(err) => IO.pure(Left(err))
      case None =>
        ctx.agentActorRef match
          case None =>
            IO.pure(
              Left(
                ToolError(
                  "Workspace path required — no interactive session available for the path-selection panel; " +
                    "pass 'workspace' (and optionally 'name') explicitly."
                )
              )
            )
          case Some(agentRef) =>
            // 2026-09-09 作者裁定原句保留可读：「面板不再下发候选（无候选 chips、无「其他…」）。
            // 选择面 = 应用内目录浏览器（dirPicker=true → 前端大目标 → workspacePicker.js，
            // 可逐级浏览/新建文件夹/显示隐藏目录）+ 空 options 使自由输入 textarea 直接
            // 可见（~ 手输兜底，后端 expandTilde/parsePanelAnswer 负责展开与绝对化校验）」。
            // 其中**末一条已被 2026-09-17 作者裁定取代**（S3 ②-5/②-6/②-7）：本卡显式
            // freeInput=false ⇒ 前端不渲染自由输入 textarea（选择面不可用时按需揭示降级
            // 输入面兜底）；点选即自动提交保持不变；「不下发候选 options」一条继续成立、禁改。
            // 后端 expandTilde / parsePanelAnswer 的绝对化校验保留（降级兜底 / legacy / CLI 面
            // 仍喂自由文本；见 test ⑥）。
            val question =
              s"ProjectCreate 需要项目工作区路径 — 点击上方「选择工作区」打开应用内目录浏览器" +
                s"（可逐级浏览、新建文件夹，含隐藏目录）；选择后即完成创建。"
            val item = AskItem(question, List.empty, dirPicker = true, freeInput = false)
            // #250 第⑤项：requestId 熵强化（单点生成器，作用域 panel-）
            val requestId = nebflow.agent.InteractionRequestId.forDirPanel()
            for
              answers <- agentRef
                .?(
                  (replyTo: ActorRef[List[String]]) => AgentCommand.AskUser(requestId, List(item), Some(replyTo)),
                  timeout = None
                )
              _ <- AskUserQuestionTool.restoreRegistryAfterAnswer(ctx)
              result <- applyPanelAnswer(parsePanelAnswer(answers), nameOpt, description, ctx)
            yield result
end ProjectCreateTool
