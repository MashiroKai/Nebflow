/* 从 NodeTools.scala 迁出(行为保持重构)。 */
/* 工具更名硬替换：ProjectCreate 更名 Project——同文件
 * `git mv` 承接(ProjectCreateTool.scala → ProjectTool.scala);旧名入
 * AgentCore.RetiredToolGuides 指路 Project(N1 rename 先例同型),guard 表
 * 登记见 DeletedToolGuardSpec rename-retired (N2) 块。增 unmount 面(S17/⑩A):三步序
 * 「在飞任务先停(①)→挂载面移除(②)→工作区文件零删(③,硬约束)」,实施单点 =
 * [[nebflow.core.project.ProjectUnmount]];archive 保留软下线档(语义分档见
 * ProjectUnmount 文件头注)。#1229 边界:项目管理/挂载面属常轨能力,不随 #1229
 * 引擎族拆除(spec §2.3 #1229 排除件条款;核对锚点 = ProjectUnmountSpec 头注)。 */
package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.{ActorRef, AgentCommand}
import nebflow.core.project.*
import nebflow.shared.{AskItem, AskUserCancelled, AskUserDispatch, InteractionRequestId, PathUtil}

import scala.util.Try

object ProjectTool extends Tool:
  val name = "Project"

  // 工具更名 Project（原 ProjectCreate），
  // 增 `action="unmount"` 面；create 语义逐字保留，仅首行改双面题头＋新增 Unmount 节。
  // 台账 #913（2026-10-26）：增只读 `list`/`show` 面（设备归属标注的 agent 查询面）
  // 与 `update` 面（description 单字段写穿）。
  val description =
    """Manage a Project (Nebula use) — `action="create"` (default): project definition + workspace .nebflow/ scaffolding + mount; `action="list"`: list mounted projects with their device annotation; `action="show"`: one project's full definition; `action="update"`: rewrite the description field only; `action="unmount"`: hard-unmount a mounted project (stops its in-flight work first; zero workspace deletion).
## When to Use (create)
- **Known workspace path** (the user told you, or you know it): pass `workspace` (absolute path; `name`/`description` optional) — direct create: writes projects/<name>/project.json, workspace root AGENTS.md template, workspace/.nebflow/ + .gitignore scaffolding, and mounts the project (FlowMapStore + ProjectActor ready). Existing workspace files are never overwritten; only missing pieces are backfilled, and the result lists what was created vs left alone.
- **Unknown workspace path**: omit `workspace` — an AskUserQuestion-style card pops up on the user's window with a prominent "选择工作区" (pick workspace) target that opens the in-app folder browser (no native OS dialog, no candidate chips). Alongside that target the card still shows a free-input box (displayed whenever the card carries no options), so the user can hand-type an absolute path with `~` expansion handled by the backend. The chosen path flows back into the card and creation proceeds automatically.
- `name` defaults to the workspace path's basename when omitted.
## After Creation
- Dispatch work with Delegate(task=<self-contained brief>, project=<name>) — the project is mounted and dispatchable immediately (Mail dispatches nothing; the former Mail(project:) trigger leg is retired).
## Create Semantics
- Same name + same workspace → idempotent (returns "already exists", re-mounts and backfills any missing scaffold file; safe to repeat).
- Same name + different workspace → explicit error (never silently re-points an existing project).
- **Different name + workspace already used by another project → explicit error (default-deny).** Workspace occupancy is compared with trailing slashes and case ignored; an archived project still counts as occupying its workspace. The error names the occupying project and gives two ways out: reuse that project, or pass a different workspace directory. Never let a second project silently share an occupied workspace — report the conflict to the user instead.
- Panel dismissed (cancel / empty answer) → clear shelved message, nothing created — re-invoke with a known path or ask the user again.
## List / Show (read-only)
- `action="list"`: every non-archived, non-unmounted project, one line each — name, device annotation (`device=local` or the device's display name resolved from the roster), workspace path, mounted state, description. This is the device-attribution query face: a project marked with another device keeps its workspace on that device (its paths are device-local absolute paths; file tools route there via the device channel).
- `action="show"` (requires `name`): one project in full — description, workspace, device (storage value + display name), origin pointer, brain root (engine-side ledger anchor; = workspace for local projects), mounted/archived/unmounted state, createdAt.
## Update Description (`action="update"`, requires `name` + `description`)
- Rewrites ONLY the `description` field in projects/<name>/project.json — every other field is kept byte-for-byte (surgical in-place write, atomic). Idempotent: same value → "unchanged" (zero write). An empty/whitespace `description` clears the field. Updating an archived/unmounted project's description is allowed (definition-level metadata, not a mount action).
## Unmount (action="unmount", requires `name`)
- **Hard unmount in three ordered steps** — stricter than archive (archive = soft retirement: only tags project.json, running tasks keep running, zero deletion):
  1. **In-flight work stops first**: running nodes get the cancel signal (dead running sessions are finalized as cancelled), active chains are cancelled (cascade), and the project's dispatcher sessions plus its in-flight internal delegate sessions are cancelled; the unmount waits until the flow map has no running node before moving on — if the drain deadline passes with work still running, the unmount aborts with an explicit error and the mount face is left untouched.
  2. **Mount face removed**: the project disappears from the Projects list (source filter shared with the startup auto-mount, so a restart will not re-mount it) and its runtime is torn down.
  3. **Zero workspace deletion (hard constraint)**: project.json / AGENTS.md / .nebflow/ / flow-map files are all kept byte-for-byte — nothing is deleted or moved.
- Re-mounting an unmounted project is refused with an actionable error — remove the `unmounted`/`unmountedAt` keys in projects/<name>/project.json to restore it first (single-way semantics, same shape as archive)."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "action" -> Json.obj(
          "type" -> "string".asJson,
          "enum" -> Json.arr("create".asJson, "list".asJson, "show".asJson, "update".asJson, "unmount".asJson),
          "description" -> "create (default): create / re-mount a project from `workspace`. list: list non-archived/non-unmounted projects with their device annotation. show: one project's full definition (requires `name`). update: rewrite ONLY the description field (requires `name` + `description`; idempotent). unmount: hard-unmount the mounted project `name` — stops its in-flight work first (nodes, chains, dispatcher/delegate sessions), then removes it from the mount face; every workspace file is kept (zero deletion). Ignored for create: `workspace`/`description`.".asJson
        ),
        "name" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Project name (single path segment). Optional for create — defaults to the workspace path basename. Required for unmount, show and update.".asJson
        ),
        "workspace" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Absolute path to the project workspace (create only). Omit to pop the interactive path-selection panel (AskUserQuestion-style card).".asJson
        ),
        "device" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Optional device name/id (create only): mount the project whose workspace lives on that paired device (brain/ledgers stay on this host; the existence check and scaffold run on the device via the device channel). Omit or \"self\" for a local workspace.".asJson
        ),
        "description" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "create: optional one-line description. update: the new one-line description written through to project.json (only field changed; empty clears it).".asJson
        )
      ),
      "required" -> Json.arr()
    )
  )

  /** 前端卡片取消哨兵（chat.js 取消按钮回填 answers=['__cancelled__']）。 */
  val CancelSentinel = "__cancelled__"

  def summarize(input: JsonObject): String =
    val n = input("name").flatMap(_.asString).orElse(input("workspace").flatMap(_.asString))
    val action = input("action").flatMap(_.asString).orElse(Some("create"))
    s"Project(${action.getOrElse("create")}:${n.getOrElse("<panel>")})"

  def summarizeResult(input: JsonObject, result: String): String = result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val rawName = input("name").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
    val rawWorkspace = input("workspace").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
    val description = input("description").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
    // 跨设备批（2026-10-25）：可选 device = 项目挂载到哪台配对设备（缺省/self = 本机）。
    val device = input("device").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
    input("action").flatMap(_.asString).map(_.trim.toLowerCase(java.util.Locale.ROOT)).getOrElse("create") match
      // N2 project-tool 批：unmount 面（S17/⑩A 三步序；实施单点 ProjectUnmount）。
      case "unmount" => unmountFace(rawName, ctx)
      case "create" => createFace(rawName, rawWorkspace, description, device, ctx)
      // 台账 #913（2026-10-26）：只读 list/show 面 + description 单字段 update 面。
      case "list" => listFace(ctx)
      case "show" => showFace(rawName, ctx)
      case "update" => updateFace(rawName, description)
      case other =>
        IO.pure(
          Left(
            ToolError(
              s"Unknown action '$other'. Supported actions: create (default), list, show, update, unmount."
            )
          )
        )

  end call

  // ============================================================
  // list / show / update 面（台账 #913 ②④，2026-10-26）
  // ============================================================

  /**
   * 名册名解析（deviceId → 显示名）：数据源 = 会话上下文的 neblink 名册
   * （self + peers 现查，Ref 内存价）；无名册上下文（REST 直调/harness）= 空表
   * ⇒ 显示名诚实降级为原始 id（displayDevice 的兜底语义）。
   */
  private def rosterNameMap(ctx: ToolContext): IO[Map[String, String]] =
    ctx.sharedResources.flatMap(_.neblinkService) match
      case None => IO.pure(Map.empty[String, String])
      case Some(svc) =>
        (
          svc.identity.attempt,
          svc.peers.attempt
        ).mapN { (self, peers) =>
          val selfEntry = self.toOption.map(i => i.deviceId -> i.deviceName).toMap
          val peerEntries = peers.getOrElse(Nil).map(p => p.deviceId -> p.deviceName)
          (selfEntry ++ peerEntries).filter((_, n) => n.nonEmpty)
        }

  /** `action="list"`：非归档/非卸载项目逐台一行（设备归属标注 + 挂载态）。 */
  private def listFace(ctx: ToolContext): IO[Either[ToolError, String]] =
    for
      names <- rosterNameMap(ctx)
      projects <- ProjectStore.list()
      mounted <- ProjectRuntimeRegistry.all.map(_.map(rt => rt.project.name).toSet)
    yield
      if projects.isEmpty then
        Right("No mounted projects (当前无挂载项目). Create one with action=\"create\" and a workspace path.")
      else
        val lines = projects.sortBy(_.name).map { pd =>
          val dev = ProjectStore.displayDevice(ProjectStore.effectiveDevice(pd), names.get)
          val devTag = if dev == "local" then "device=local" else s"device=$dev"
          val mountTag = if mounted.contains(pd.name) then "mounted" else "not mounted"
          val ws = pd.workspace
          val desc = pd.description.filter(_.nonEmpty).map(d => s" — $d").getOrElse("")
          s"- ${pd.name} [$devTag, $mountTag] $ws$desc"
        }
        Right(lines.mkString("\n") +
          "\n\nA project tagged with another device keeps its workspace on that device (device-local absolute paths; file tools route there via the device channel). action=\"show\" for one project's full definition.")

  /** `action="show"`：单项目全量定义（含设备归属的存储值 + 显示名 + 大脑根）。 */
  private def showFace(rawName: Option[String], ctx: ToolContext): IO[Either[ToolError, String]] =
    rawName match
      case None =>
        IO.pure(
          Left(
            ToolError(
              "show requires 'name' — the project to display. action=\"list\" shows every mounted project."
            )
          )
        )
      case Some(proj) =>
        for
          names <- rosterNameMap(ctx)
          mounted <- ProjectRuntimeRegistry.get(proj).map(_.isDefined)
          pd <- ProjectStore.load(proj)
        yield pd match
          case None =>
            Left(ToolError(s"Project '$proj' not found (no projects/$proj/project.json). action=\"list\" shows existing projects."))
          case Some(d) =>
            val devRaw = ProjectStore.effectiveDevice(d)
            val devDisplay = ProjectStore.displayDevice(devRaw, names.get)
            val b = new StringBuilder
            b ++= s"Project '${d.name}'\n"
            b ++= s"  description: ${d.description.filter(_.nonEmpty).getOrElse("(none)")}\n"
            b ++= s"  device: ${if ProjectOrigin.isSelfLiteral(devRaw) then "local" else s"$devDisplay ($devRaw)"}\n"
            d.origin.foreach { o =>
              b ++= s"  origin: {device: ${o.device}, path: ${o.path}} — workspace lives on that device (device-local path)\n"
            }
            b ++= s"  workspace: ${d.workspace}\n"
            b ++= s"  brainRoot: ${ProjectStore.brainWorkspace(d)} (engine-side ledger anchor; = workspace for local projects)\n"
            b ++= s"  agentFile: ${d.agentFile}\n"
            b ++= s"  mounted: ${if mounted then "yes" else "no"}\n"
            if d.archived.contains(true) then b ++= "  archived: yes (hidden from the Projects list)\n"
            if d.unmounted.contains(true) then b ++= "  unmounted: yes (hard-unmounted; re-mount refused until the keys are removed)\n"
            b ++= s"  createdAt: ${d.createdAt}\n"
            Right(b.toString)

  /** `action="update"`：description 单字段写穿（实施单点 ProjectStore.updateDescription）。 */
  private def updateFace(rawName: Option[String], description: Option[String]): IO[Either[ToolError, String]] =
    rawName match
      case None =>
        IO.pure(
          Left(
            ToolError(
              "update requires 'name' — the project whose description to rewrite. " +
                "Pass action=\"update\" with name and the new description."
            )
          )
        )
      case Some(proj) =>
        description match
          case None =>
            IO.pure(
              Left(
                ToolError(
                  "update requires 'description' — the new one-line description (this action changes ONLY " +
                    "that field; an empty string clears it). Nothing was written."
                )
              )
            )
          case Some(desc) =>
            ProjectStore.updateDescription(proj, desc).map {
              case Left(err) => Left(ToolError(err))
              case Right("unchanged") =>
                Right(s"Project '$proj' description unchanged (already set — idempotent no-op, zero write).")
              case Right(_) =>
                Right(s"Project '$proj' description updated (only the description field changed — every other project.json field is byte-for-byte).")
            }

  /** create 面（原 ProjectCreate 语义，逐字保持）。 */
  private def createFace(
    rawName: Option[String],
    rawWorkspace: Option[String],
    description: Option[String],
    device: Option[String],
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    rawWorkspace match
      // 已知路径直建（口径①，既有路径语义不变，name 缺省改 basename 派生）。
      case Some(ws) =>
        Try(os.Path(ws, PathUtil.dataRoot)) match
          case scala.util.Success(_) => createChain(rawName, ws, description, device, ctx)
          case scala.util.Failure(_) => pathPanel(rawName, description, device, ctx) // path 不可用 → 面板兜底
      // 未知/缺省 path（口径②）→ AskUser 式交互面板。
      case None => pathPanel(rawName, description, device, ctx)

  // ============================================================
  // unmount 面（N2 project-tool 批，S17/⑩A 三步序）
  // ============================================================

  /**
   * `action="unmount"`：硬卸载已挂载项目。三步序与零删硬约束的实施单点 =
   * [[nebflow.core.project.ProjectUnmount]]；本方法只做两件事——①参数/上下文守卫
   * （`name` 必填；无会话上下文/无 agent runtime 显式拒绝，镜像 mount 腿 M3
   * 「无会话上下文的挂载视为非法」先例：stop 步要经 agentRegistry 找到在飞
   * dispatcher/delegate 会话，凭空缺上下文的调用不卸）；②结果渲染。
   */
  private def unmountFace(
    rawName: Option[String],
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    rawName match
      case None =>
        IO.pure(
          Left(
            ToolError(
              "unmount requires 'name' — the project to unmount. Create/re-mount is the default action; " +
                "pass action=\"unmount\" explicitly with the project name."
            )
          )
        )
      case Some(proj) =>
        val rootKey = ctx.rootSessionId.filter(_.nonEmpty).orElse(ctx.sessionId.filter(_.nonEmpty))
        (ctx.sharedResources, rootKey) match
          case (Some(res), Some(_)) =>
            ProjectUnmount
              .unmount(
                proj,
                res.agentRegistry,
                frame => ctx.wsSend.fold(IO.unit)(send => send(frame))
              )
              .map(_.left.map(ToolError.apply).map(renderUnmountResult))
          case _ =>
            IO.pure(
              Left(
                ToolError(
                  "Project refused to unmount: this call carries no session context (both rootSessionId " +
                    "and sessionId are empty or absent) and no agent runtime, so the stop step could not " +
                    "enumerate the project's in-flight dispatcher/delegate sessions — and an unmount that " +
                    "cannot stop first must not proceed. Re-invoke Project(action=\"unmount\", name=...) " +
                    "from an agent session (Nebula / project dispatcher)."
                )
              )
            )

        end match

  /** unmount 结果句（成功/幂等两态；report 计数逐项可读，幂等态显式声明 no-op）。 */
  private def renderUnmountResult(r: ProjectUnmount.UnmountReport): String =
    val op = if r.alreadyUnmounted then "was already unmounted (no-op — zero side effects)" else "unmounted"
    val stopped =
      if r.alreadyUnmounted then ""
      else
        s"Stopped first (step 1): ${r.chainsCancelled.size} active chain(s) cancelled" +
          (if r.chainsCancelled.nonEmpty then s" [${r.chainsCancelled.mkString(", ")}]" else "") +
          s", ${r.nodesCancelled.size} running node(s) stopped/finalized" +
          (if r.nodesCancelled.nonEmpty then s" [${r.nodesCancelled.mkString(", ")}]" else "") +
          s", ${r.sessionsCancelled.size} dispatcher session(s) and ${r.delegatesCancelled.size} internal delegate session(s) cancelled. "
    s"Project '${r.project}' $op. " + stopped +
      "Mount face removed (step 2): hidden from the Projects list; startup auto-mount will skip it. " +
      "Zero deletion (step 3): every workspace file is kept byte-for-byte — nothing was deleted or moved."

  // ============================================================
  // 创建链（直建与面板选择路径共用）
  // ============================================================

  /**
   * 创建 + 幂等挂载。name 缺省 = workspace basename（口径①）。
   * 同名冲突语义（任务口径）：同 workspace → 幂等（"already exists" + 挂载）；
   * 异 workspace → 明确报错（绝不静默改指旧定义）。
   *
   * 跨设备批（2026-10-25 · 去重收口）：本方法降为 [[ProjectCreateService.create]]
   * 的**薄壳**——该服务自 2026-09-24 抽取批起就是双面（工具面 + WS projectCreate）
   * 的单一实现，但工具面此前仍留有一份**逐行重复**的旧挂载体（占用守卫/幂等分支/
   * mount 闭包全部双份），两份已现实漂移（服务面缺 unmounted 拒绝、工具面缺设备
   * 腿与本批挂载预检）。本批统一走服务面：挂载预检（铁律：workspace 必须真实存在
   * 才准挂，零写入拒绝）、设备创建+挂载腿、unmounted 单程拒绝在两处恒同源。
   */
  private def createChain(
    nameOpt: Option[String],
    workspace: String,
    description: Option[String],
    device: Option[String],
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    ProjectCreateService.create(
      nameOpt,
      workspace,
      description,
      ProjectCreateService.CreateIdentity(
        actorSystem = ctx.actorSystem,
        sharedResources = ctx.sharedResources,
        rootSessionId = ctx.rootSessionId,
        sessionId = ctx.sessionId,
        wsSend = ctx.wsSend
      ),
      device = device
    )


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

    /** 派发失败信号 → 明确报错（不是用户动作）。 */
    case class Failed(signal: String) extends PanelAnswer
  end PanelAnswer

  /** 去尾部斜杠（Scala String.stripTrailing 无参版，斜杠语义手写）。 */
  private def stripTrailingSlashes(s: String): String = s.replaceAll("/+$", "")

  /**
   * 面板答案解析（纯函数，spec 覆盖）：派发失败信号 → Failed（明确报错，**不是**
   * 用户动作）；取消信号（askucancel 批：hub CancelRequest 经既有答复管道投回的
   * `AskUserCancelled` 信号——用户在网页/渠道侧撤销了这张工作区卡）→ Shelved（与
   * 既有 `__cancelled__` 哨兵同一落点：用户撤回 ≙ 搁置，预算恢复走配对的
   * `answerLanded`，不造第二套终态）；首槽空 / 取消哨兵 → Shelved；'~' 展开后非
   * 绝对 → BadPath；合法 → Chosen（去尾斜杠）。
   * isAbsolute 为跨平台判定（POSIX / 盘符 / UNC）——朴素 startsWith("/")
   * 会拒绝 Windows 盘符答案（diag-win-paths P1）。
   */
  private def parsePanelAnswer(answers: List[String]): PanelAnswer =
    if AskUserDispatch.isFailure(answers) then PanelAnswer.Failed(answers.head)
    else if AskUserCancelled.isCancelled(answers) then PanelAnswer.Shelved
    else
      val raw = answers.headOption.map(_.trim).getOrElse("")
      if raw.isEmpty || raw == CancelSentinel then PanelAnswer.Shelved
      else
        val expanded = expandTilde(raw)
        if !PathUtil.isAbsolute(expanded) then PanelAnswer.BadPath(raw, "path must be absolute")
        else PanelAnswer.Chosen(stripTrailingSlashes(expanded))

  /**
   * 面板答案落地（纯分派）：Failed → 明确报错（问题从未上卡，不是用户动作）；
   * Shelved → 搁置消息；BadPath → 明确报错；Chosen → 创建链。
   */
  private def applyPanelAnswer(
    ans: PanelAnswer,
    nameOpt: Option[String],
    description: Option[String],
    device: Option[String],
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    ans match
      case PanelAnswer.Failed(signal) =>
        IO.pure(
          Left(
            ToolError(
              s"Path-selection panel could not be dispatched — the question was never shown to the user " +
                s"and no answer can arrive, so nothing was created. Signal: $signal. " +
                s"Re-invoke with an absolute 'workspace' path (and optionally 'name') to proceed without the panel."
            )
          )
        )
      case PanelAnswer.Shelved =>
        IO.pure(
          Right(
            "Project shelved: the path-selection panel was dismissed without a choice — " +
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
      case PanelAnswer.Chosen(path) => createChain(nameOpt, path, description, device, ctx)

  /**
   * 未知路径分支：复用 AskUser pending 机制（AgentCommand.AskUser →
   * InteractionHub → 前端 AskUserQuestion 卡片），零新增前端/问答通道。
   *
   * 语义边界（全部与 AskUserQuestionTool 对齐，不另起一套）：
   * - headless（NEBFLOW_HEADLESS=1）→ askGuard 同款报错（无交互用户，面板会
   *   永久悬挂）；
   * - 无 agent 会话（REST 直调 / harness）→ 明确报错不悬挂；
   * - 等待无人工超时——与 AskUserQuestion 同语义（pending 期间会话标记
   *   WaitingForUser 豁免 TaskStuckWatcher）；承接面缺席（无 InteractionHub 可用）
   *   ⇒ 派发腿回 [[nebflow.shared.AskUserDispatch]] 失败信号 ⇒ 本条给明确报错
   *   （问题从未上卡、答案永不投达，与「用户关掉面板」区分开）；
   * - 取消/空答案 → 明确搁置消息（无创建、无悬挂）；
   * - 答案落定 → restoreRegistryAfterAnswer 配对恢复（与 AskUserQuestionTool
   *   同一实现——#43 修复的答案来源校验语义原样适用：面板答案只能来自用户
   *   点选帧 askUserAnswer（非 injected 用户消息），agent 侧注入负载永不消费）。
   */
  private def pathPanel(
    nameOpt: Option[String],
    description: Option[String],
    device: Option[String],
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
            // 现行口径原句保留可读：「面板不再下发候选（无候选 chips、无「其他…」）。
            // 选择面 = 应用内目录浏览器（dirPicker=true → 前端大目标 → workspacePicker.js，
            // 可逐级浏览/新建文件夹/显示隐藏目录）+ 空 options 使自由输入 textarea 直接
            // 可见（~ 手输兜底，后端 expandTilde/parsePanelAnswer 负责展开与绝对化校验）」。
            // 其中**末一条已被现行口径取代**：本卡显式
            // freeInput=false ⇒ 前端不渲染自由输入 textarea（选择面不可用时按需揭示降级
            // 输入面兜底）；点选即自动提交保持不变；「不下发候选 options」一条继续成立、禁改。
            // 后端 expandTilde / parsePanelAnswer 的绝对化校验保留（降级兜底 / legacy / CLI 面
            // 仍喂自由文本；见 test ⑥）。
            val question =
              s"Project 需要项目工作区路径 — 点击上方「选择工作区」打开应用内目录浏览器" +
                s"（可逐级浏览、新建文件夹，含隐藏目录）；选择后即完成创建。"
            val item = AskItem(question, List.empty, dirPicker = true, freeInput = false)
            // #250 第⑤项：requestId 熵强化（单点生成器，作用域 panel-）
            val requestId = InteractionRequestId.forDirPanel()
            for
              answers <- agentRef
                .?(
                  (replyTo: ActorRef[List[String]]) =>
                    // awaitsAnswer=true：本面板在自己的 fiber 里阻塞等待，且答复是
                    // `applyPanelAnswer` 的入参 ⇒ AgentProcessing 必须据此标
                    // WaitingForUser 并暂停内核预算（否则等人选路径被判成真卡死）。
                    AgentCommand.AskUser(requestId, List(item), Some(replyTo), awaitsAnswer = true),
                  timeout = None
                )
              _ <- AskUserQuestionTool.answerLanded(ctx)
              result <- applyPanelAnswer(parsePanelAnswer(answers), nameOpt, description, device, ctx)
            yield result
end ProjectTool
