package nebflow.core.executor

import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax.*
import io.circe.{Encoder, Json}
import nebflow.shared.PathUtil

import java.io.File

/**
 * ExecutorRegistry —— 统一 delegate 的执行器注册与检测（executor-registry 批
 * 2026-10-03）。
 *
 * 大改后的使用形态：Nebula 的 `Delegate(task, project?, executor?)`，执行器由
 * 主 agent 动态选择（external-executors 批 #1225，S14 运行时半——`executor`
 * 参数为唯一显式选择输入、缺省内置 `nebflow`；nebflow.json `executor` 块
 * **止读**，⑫）。本注册表提供：
 *
 *   - `nebflow` —— 内置执行器（BuiltinAgents 合并后的执行 agent），无需检测；
 *   - 外部 CLI 智能体 —— ZCode / Claude Code / Codex / Hermes：检测面 ＋ 真适配
 *     路径（适配器见 [[nebflow.core.executor.ExternalExecutor]]；hermes 只做
 *     检测 + 图标 + 占位，`adapterReady=false`，选择面显式拒绝）。
 *
 * ==检测模式取自 RgHelper==
 * PATH 扫描 + 常用前缀探测（launchd/systemd 服务的精简 PATH 教训同款，
 * RgHelper.curatedProbeDirs 注释 2026-09-21）。外部 CLI **不做** bundled /
 * `~/.nebflow/bin` 腿——它们不是 Nebflow 安装的。
 *
 * ==配置（nebflow.json 顶层 `executor` 节）==
 *   "executor": { "default": "nebflow", "disabled": ["hermes"] }
 *
 * 热读（每次快照现读，仿 [[nebflow.core.jev.JevConfigReader]]）：只读本节
 * 标量，块级宽容解码——坏形一律降级为缺省值，绝不抛出（检测面不楔死派发）。
 * 写侧 = [[nebflow.service.ConfigService.setExecutorSection]]（白名单键）。
 */
object ExecutorRegistry:

  /** 单个执行器的静态定义（注册表权威；iconKey = id，前端单点映射）。 */
  final case class ExecutorDef(
    id: String,
    displayName: String,
    /** 逐个探测的可执行名（Windows 自动追加 .exe/.cmd 形态）。 */
    binNames: List[String],
    description: String,
    /** false = nebflow 内置执行器（不需要文件系统检测）。 */
    external: Boolean,
    /** false = 仅检测 + 图标占位，delegate 尚不可选（适配器未落地）。 */
    adapterReady: Boolean
  )

  /** 快照行：静态定义 + 检测结果 + 配置态。 */
  final case class ExecutorState(
    id: String,
    displayName: String,
    iconKey: String,
    description: String,
    external: Boolean,
    adapterReady: Boolean,
    detected: Boolean,
    path: Option[String],
    enabled: Boolean,
    isDefault: Boolean
  )

  object ExecutorState:
    given Encoder[ExecutorState] = deriveEncoder

  final case class Settings(default: Option[String], disabled: List[String])

  /** 内置执行器永远在册且可用（不可 disabled——它是唯一的零依赖兜底面）。 */
  val DefaultId: String = "nebflow"

  val defs: List[ExecutorDef] = List(
    ExecutorDef(
      id = "nebflow",
      displayName = nebflow.shared.Branding.productName,
      binNames = Nil,
      description = "Built-in executor agent (kernel base tools + subagent + workflow)",
      external = false,
      adapterReady = true
    ),
    ExecutorDef(
      id = "zcode",
      displayName = "ZCode",
      binNames = List("zcode"),
      description = "ZCode CLI agent (headless print mode)",
      external = true,
      adapterReady = true
    ),
    ExecutorDef(
      id = "claude-code",
      displayName = "Claude Code",
      binNames = List("claude"),
      description = "Claude Code CLI agent (claude -p, stream-json output)",
      external = true,
      adapterReady = true
    ),
    ExecutorDef(
      id = "codex",
      displayName = "Codex",
      binNames = List("codex"),
      description = "OpenAI Codex CLI agent (codex exec --json)",
      external = true,
      adapterReady = true
    ),
    ExecutorDef(
      id = "hermes",
      displayName = "Hermes",
      binNames = List("hermes"),
      description = "Hermes CLI agent (adapter pending — detection only)",
      external = true,
      adapterReady = false
    )
  )

  private def find(id: String): Option[ExecutorDef] = defs.find(_.id == id)

  def isWindows: Boolean =
    sys.props.getOrElse("os.name", "").toLowerCase.contains("win")

  /**
   * 外部 CLI 的常用前缀（launchd/systemd 精简 PATH 同款教训；见
   * RgHelper.curatedProbeDirs）。Windows 空——npm 全局安装落在 PATH 腿。
   */
  val curatedProbeDirs: List[String] =
    if isWindows then Nil
    else List("/opt/homebrew/bin", "/usr/local/bin", "/snap/bin")

  /** 单可执行名的探测核（纯函数，spec 可驱动；PATH 腿 → 前缀腿）。 */
  private[executor] def resolveBinFrom(
    pathEnv: String,
    probeDirs: List[String],
    binName: String,
    windows: Boolean
  ): Option[String] =
    val names = if windows then List(binName, s"$binName.exe", s"$binName.cmd") else List(binName)
    def scan(dirs: Iterator[String]): Option[String] =
      dirs.flatMap(d => names.iterator.map(n => new File(d, n))).find(_.isFile).map(_.getAbsolutePath)
    scan(pathEnv.split(File.pathSeparator).iterator).orElse(scan(probeDirs.iterator))

  /** 一个执行器的文件系统检测。内置执行器恒 Some("(built-in)")。 */
  def detect(defn: ExecutorDef, pathEnv: String, probeDirs: List[String]): Option[String] =
    if !defn.external then Some("(built-in)")
    else
      defn.binNames.iterator
        .flatMap(b => resolveBinFrom(pathEnv, probeDirs, b, isWindows))
        .nextOption()

  /** 便捷形态：当前进程环境下检测。 */
  def detect(defn: ExecutorDef): Option[String] =
    detect(defn, sys.env.getOrElse("PATH", ""), curatedProbeDirs)

  // ── 配置热读（executor 节）────────────────────────────────────────────

  /** 块级宽容读：坏形/缺失一律 `Settings(None, Nil)`，绝不抛出。 */
  def readSettings: Settings =
    try
      val p = PathUtil.configJsonReadPath(PathUtil.dataRoot)
      if !os.exists(p) then Settings(None, Nil)
      else
        io.circe.parser
          .parse(os.read(p))
          .toOption
          .flatMap(_.hcursor.downField("executor").focus)
          .flatMap(_.asObject)
          .map(readBlock)
          .getOrElse(Settings(None, Nil))
    catch case _: Throwable => Settings(None, Nil)

  private def readBlock(o: io.circe.JsonObject): Settings =
    val c = Json.fromJsonObject(o).hcursor
    Settings(
      default = c.downField("default").as[String].toOption,
      disabled = c.downField("disabled").as[List[String]].toOption.getOrElse(Nil)
    )

  /**
   * Delegate 实际生效的 default：显式配置存在、在册、且未被 disabled 才采纳；
   * 否则回落内置 [[DefaultId]]（内置执行器不可 disabled，兜底恒成立）。
   */
  def effectiveDefault(settings: Settings): String =
    settings.default
      .filter(id => find(id).exists(d => d.adapterReady && !settings.disabled.contains(d.id)))
      .getOrElse(DefaultId)

  def effectiveDefault: String = effectiveDefault(readSettings)

  // ── 执行器选择反转——运行时半（external-executors 批 #1225，S14＋②⑫）──────
  //
  // 选择语义反转为「主 agent 动态选择，缺省 nebflow」：Delegate 的显式
  // `executor` 参数是唯一选择输入；nebflow.json `executor` 块的
  // default/disabled **均不再作为选择输入**（⑫——块退役落 S14 拆除小批；
  // 本批运行时先止读）。可用集 = GET /api/executors 检测面（快照语义不变，
  // ConfigRoutes:143 零改动）：adapterReady ＋ detected。
  //
  // fail-closed（增补条款 3.4，R2/R11）：请求外部执行器但不在册 / 适配未
  // 就绪 / 未检测到二进制 ⇒ Left 显式错误，**绝不静默回落 nebflow**。

  /** 选择解析结果：内置执行器（原 spawn 路径）或外部执行器（真适配器路径）。 */
  enum ResolvedSelection:
    case Internal
    case External(defn: ExecutorDef, binPath: String)

  /**
   * Delegate `executor` 参数 → 选择解析。None（参数缺省）＝内置缺省
   * [[DefaultId]]——模型可见语义「缺省 nebflow」。错误消息面向主 agent
   * 自纠错：列出当前可用集（检测面）。
   *
   * @param executorParam Delegate 调用的 `executor` 参数（trim 后非空才生效）
   * @param pathEnv/probeDirs 检测输入（spec 可注入；生产 = 进程环境）
   */
  def resolveRequested(
    executorParam: Option[String],
    pathEnv: String = sys.env.getOrElse("PATH", ""),
    probeDirs: List[String] = curatedProbeDirs
  ): Either[String, ResolvedSelection] =
    val requested = executorParam.map(_.trim).filter(_.nonEmpty).getOrElse(DefaultId)
    if requested == DefaultId then Right(ResolvedSelection.Internal)
    else
      find(requested) match
        case None =>
          Left(selectionError(requested, pathEnv, probeDirs, "not in the registry"))
        case Some(d) if !d.adapterReady =>
          Left(selectionError(requested, pathEnv, probeDirs, "its adapter is not wired (detection-only placeholder)"))
        case Some(d) =>
          detect(d, pathEnv, probeDirs) match
            case None =>
              Left(selectionError(requested, pathEnv, probeDirs, "its CLI binary was not detected on this machine"))
            case Some(bin) => Right(ResolvedSelection.External(d, bin))

  end resolveRequested

  /** 显式失败面（不回落）；available 行 = 检测面快照（同 fail-closed 口径）。 */
  private def selectionError(requested: String, pathEnv: String, probeDirs: List[String], why: String): String =
    val available = snapshot(pathEnv, probeDirs)
      .filter(s => s.adapterReady && s.detected)
      .map(s => s.id)
      .mkString(", ")
    s"Delegate: executor '$requested' cannot be selected — $why. " +
      s"Pick one of the available executors: $available, or omit the executor parameter for the built-in default. " +
      "(No silent fallback to 'nebflow' — re-dispatch with an explicit choice.)"

  /** 可用集一行（主 agent prompt 可用集段数据源；检测面口径，与 resolveRequested 同）。 */
  def availableLine(pathEnv: String, probeDirs: List[String]): String =
    snapshot(pathEnv, probeDirs)
      .filter(s => s.adapterReady && s.detected)
      .map(s => s"- ${s.id} — ${s.displayName}${s.path.filter(_ != "(built-in)").map(p => s" ($p)").getOrElse("")}")
      .mkString("\n")

  /** 快照：全部在册执行器 + 检测 + 配置态（/api/executors 的数据源）。 */
  def snapshot(pathEnv: String, probeDirs: List[String]): List[ExecutorState] =
    val settings = readSettings
    val dflt = effectiveDefault(settings)
    defs.map { d =>
      val path = detect(d, pathEnv, probeDirs)
      val disabled = settings.disabled.contains(d.id)
      // 内置执行器恒可用：detected=true、enabled 不吃 disabled。
      val enabled = if d.external then !disabled else true
      ExecutorState(
        id = d.id,
        displayName = d.displayName,
        iconKey = d.id,
        description = d.description,
        external = d.external,
        adapterReady = d.adapterReady,
        detected = path.isDefined,
        path = path,
        enabled = enabled && d.adapterReady,
        isDefault = d.id == dflt
      )
    }
  end snapshot

  def snapshot: List[ExecutorState] = snapshot(sys.env.getOrElse("PATH", ""), curatedProbeDirs)

  /** 可选执行器 id 集（delegate 校验用）：在册 + adapterReady + 未 disabled。 */
  def selectableIds: List[String] =
    val s = readSettings
    defs.filter(d => d.adapterReady && !s.disabled.contains(d.id)).map(_.id)

  /** JSON 面（/api/executors 响应体）。 */
  def snapshotJson: Json = Json.obj("executors" -> snapshot.asJson)

end ExecutorRegistry
