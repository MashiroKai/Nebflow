package nebflow.core.sandbox

import java.util.concurrent.ConcurrentHashMap

import nebflow.core.NebflowLogger

/**
 * 执行环境 provider（拆围栏批 design §4.2 / R4=d2，2026-09-10 作者裁定）：
 * 语义从「宿主 Bash 的 OS 强制后端」改为「**执行环境 provider**」——回答的问题是
 * 「代码在哪跑」，不再是「围栏包在哪」。
 *
 * 取值（design §4.2 表）：
 *  - `host`（**缺省**）：宿主直跑，无包裹。**本缺省取代 docker 批 A2「缺省 seatbelt」**
 *    （design §6.4 取代项 6）。
 *  - `local-process`：进程级 OS 约束（macOS Seatbelt）降级复用（R6=f2：不删代码、
 *    保留为**可选非缺省**后端）。宿主 Bash 包裹由此恢复 ⇒ design §4.5 的回退点之一。
 *  - `container`：per-task 容器（「纯赋能」靶心）。**本批不实现容器实施**（作者裁定：
 *    容器线等作者看完方案再动）——只保留取值与失败语义：选中即**显式失败**。
 *  - `auto`：container → local-process → host 三段降级（design §4.2，带三重可见）。
 *    **本批不实现**——同上：显式失败，绝不静默降级。
 *
 * U7 铁律（design §6.2，与 R7=g1 并案）：任何**未实现或不可用**的 provider 取值
 * 必须**显式失败**（启动期 ERROR + 执行期 ToolError），**绝不静默回落 `host`**。
 */
enum SandboxProvider:
  case Host, LocalProcess, Container, Auto

object SandboxProvider:
  /** 合法取值表（校验文案与文档共用）。 */
  val names: List[String] = List("host", "local-process", "container", "auto")

  /** 解析 provider 取值。非法值 → `Left(可读文案)`：**绝不静默回落 host**（U7）——
    * 调用方把原因带在 `SandboxConfig.providerError` 上，由 `SandboxRuntime.init`
    * 转成 Bash 的显式失败。 */
  def parse(raw: String): Either[String, SandboxProvider] =
    raw.trim.toLowerCase match
      case "host"          => Right(Host)
      case "local-process" => Right(LocalProcess)
      case "container"     => Right(Container)
      case "auto"          => Right(Auto)
      case "seatbelt" | "docker" =>
        Left(
          s""""sandbox.provider": "$raw" 是 docker 批 A2/A3 的旧取值，已由「执行环境 provider」语义取代（design §4.2 / R4=d2）——请改用 ${names.mkString("\"", "\", \"", "\"")}（"seatbelt" → "local-process"）。"""
        )
      case other =>
        Left(s""""sandbox.provider": 未知取值 "$other"（合法值：${names.mkString("\"", "\", \"", "\"")}）。""")
end SandboxProvider

/**
 * 阶段 2a 沙箱（设计文档 §A.4）：Bash 层 OS 强制后端抽象。
 *
 * [拆围栏批 S3，2026-09-10] 本 trait 现在是 **provider 的执行面**：`Host`（缺省，
 * 无包裹）/ `Seatbelt`（local-process）/ `Unavailable`（旧行为回退位）。
 * 预留 trait（Linux 可移植性，【沙箱调研】§5 建议）；Linux 后端（bubblewrap/
 * landlock）未实现——见 hardening 清单。
 */
trait SandboxBackend:
  def name: String

  /** probe 是否通过。false 时 wrap 必须返回 None（fail-closed 判定在 BashTool）。 */
  def available: Boolean

  /**
   * 包裹 argv 使其在 OS 沙箱内执行。argv[0] 约定为解释器（"bash"）。返回 None =
   * 后端不可用/不支持该形态（调用方按 fail-closed 或显式降级处理）。
   */
  def wrap(argv: List[String], policy: SandboxPolicy): Option[List[String]]

object SandboxBackend:

  /**
   * 宿主直跑后端（**缺省 provider=`host`**，design §4.2 / R4=d2）。
   *
   * `wrap` 恒 `None` ⇒ `shell.buildProcessBuilder` 的唯一 wrap 调用点走 `plain`
   * 分支：**宿主路径下不再包裹**（S3 的主体改动）。恒 `available = true`：宿主直跑
   * 天然可用，`available` 不再承担「拦 Bash」的职责（旧 §A.4-4 fail-closed 在宿主
   * 路径已退场，见 BashTool.call）。
   *
   * 为什么保留后端对象 + 接缝，而不是连代码一起删：接缝不是围栏，是**接入点**
   * （design §1.1 F11）——容器/VM 执行面的 Bash 命令未来必须从同一接缝注入，
   * 「一处切换、全量生效」的机械支点必须唯一。
   */
  object Host extends SandboxBackend:
    val name = "host"
    val available = true
    def wrap(argv: List[String], policy: SandboxPolicy): Option[List[String]] = None

  /** 不可用后端（probe 失败 / 非 darwin / 未初始化）：永远 wrap 不出。
    * [S3] 语义收窄：仅剩「provider=host 未激活 + enabled=false 旧行为」的占位与
    * 测试注入位——不再是「Bash 必须被拦」的信号（判定已移出 available）。 */
  object Unavailable extends SandboxBackend:
    val name = "unavailable"
    val available = false
    def wrap(argv: List[String], policy: SandboxPolicy): Option[List[String]] = None

  /**
   * macOS Seatbelt 后端（§A.4）= provider=`local-process` 的执行面（R6=f2：**降为
   * 可选后端而不是删除**——代码已在、保持成本为零，且它是 macOS 上唯一的进程级
   * 约束手段，对「无 docker 但需进程约束」的 CI/受限环境有复用价值）。
   * **可选 ≠ 缺省**：缺省由 R4=d2 定为 `host`。
   *
   * - 调用硬编码绝对路径 /usr/bin/sandbox-exec + /bin/bash（防 PATH 注入，
   *   codex 同理由）；Seatbelt 策略随 fork/exec 自动传播到全部子进程树。
   * - profile 按 writableRoots 集合缓存（root 集合有限；§A.4-2）。
   * - 实测语义（2026-09-03 本机验证）：Seatbelt 按 resolve 后真实路径匹配——
   *   /tmp 需与 /private/tmp 同时列出（writableRoots 已 canonical，天然覆盖）；
   *   规则 last-match-wins——(allow default) → (deny file-write*) →
   *   (allow file-write* 子路径) → (deny .git/hooks) 次序即此。
   * - (param "X")+ -D X=... 参数化（codex 做法）；param 引用必须带引号，无引号
   *   报 unbound variable。
   * - SBPL ≤ 900B（规格上限；测试断言）。
   */
  final class Seatbelt(sandboxExecPath: String = Seatbelt.SandboxExecPath) extends SandboxBackend:
    private val probeOk: Boolean = Seatbelt.probe(sandboxExecPath)
    val name: String = "seatbelt"
    def available: Boolean = probeOk

    def wrap(argv: List[String], policy: SandboxPolicy): Option[List[String]] =
      if !probeOk then None
      else
        argv match
          case "bash" :: rest =>
            val (profile, params) = Seatbelt.profileFor(policy)
            val wrapped = List(sandboxExecPath, "-p", profile) ++ params ++ List("--", "/bin/bash") ++ rest
            Some(wrapped)
          case _ => None

  object Seatbelt:
    private val logger = NebflowLogger(getClass)

    val SandboxExecPath = "/usr/bin/sandbox-exec"
    val ProfileBudgetBytes = 900

    /** profile 缓存：key = writableRoots + hooks 拒绝路径（root 集合有限，§A.4-2）。 */
    private val profileCache = new ConcurrentHashMap[String, String]()

    /** 启动 probe（§A.4-4）：只读 profile 跑 /bin/bash -c true，exit 0 才可用。
      * [verify-fix] 2026-09-03 独立验证节点：原用 /bin/true，但本机（Darwin 25.4）
      * 无 /bin/true（仅 /usr/bin/true）→ execvp ENOENT → probe 恒败 → fail-closed
      * 拒绝一切沙箱 Bash。改用 /bin/bash -c true：与 wrap() 硬编码的执行二进制
      * 完全一致——probe 探的正是沙箱路径真正依赖的那个文件。 */
    def probe(sandboxExecPath: String = SandboxExecPath): Boolean =
      val isMac = sys.props.getOrElse("os.name", "").toLowerCase.contains("mac")
      if !isMac then false
      else
        try
          val pb = new ProcessBuilder(sandboxExecPath, "-p", "(version 1)(allow default)", "--", "/bin/bash", "-c", "true")
          pb.redirectInput(new java.io.File("/dev/null"))
          pb.redirectErrorStream(true)
          val proc = pb.start()
          // 读干输出流防缓冲区满阻塞
          val out = new String(proc.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
          val ok = proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && proc.exitValue() == 0
          if !ok then logger.warnSync(s"sandbox-exec probe failed (exit=${proc.exitValue()}): ${out.take(200)}")
          ok
        catch
          case e: Exception =>
            logger.warnSync(s"sandbox-exec probe error: ${e.getClass.getSimpleName}: ${e.getMessage}")
            false

    /**
     * profile 模板（§A.4.2）。读/进程/网络默认放行（H-10①：Bash 读面本轮不设
     * 界，hardening 清单）；写 OS 强制；.git/hooks 拒绝防 hook 注入持久化、保留
     * git commit。所有子路径取自 SandboxPolicy.writableRoots（§A.1 单一策略源，
     * 已 canonical——含 /private/tmp 与 java.io.tmpdir 真实形态，覆盖 Seatbelt
     * 按 resolve 后路径匹配的语义）。
     *
     * hooks 拒绝路径经 (param "SB_GIT_HOOKS")+-D 传参（codex 做法），不进
     * profile 字面量。返回 (profile, -D 参数表)。
     */
    def profileFor(policy: SandboxPolicy): (String, List[String]) =
      val hooksDeny = SandboxPolicy.canonicalize(policy.root.wrapped.resolve(".git/hooks"))
      val cacheKey = SandboxPolicy.writableRoots(policy).map(_.toString).mkString("|") + "|" + hooksDeny
      val cached = profileCache.get(cacheKey)
      val sbpl =
        if cached != null then cached
        else
          val allows = SandboxPolicy.writableRoots(policy)
            .map(r => s"""(subpath "${sbplEscape(r.toString)}")""")
            .mkString(" ")
          val rendered =
            s"""(version 1)(allow default)(deny file-write*)""" +
              s"""(allow file-write* (literal "/dev/null") $allows)""" +
              s"""(deny file-write* (subpath (param "SB_GIT_HOOKS")))"""
          profileCache.put(cacheKey, rendered)
          rendered
      (sbpl, List("-D", s"SB_GIT_HOOKS=${hooksDeny.toString}"))

    /** SBPL 字符串转义：仅 " 与 \（路径来自配置/工程目录，不含控制字符）。 */
    private def sbplEscape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
  end Seatbelt
end SandboxBackend

/**
 * 进程级 provider 注册表：GatewayMain 启动按 provider 装配一次并缓存（§A.4-4 形态 +
 * design §4.3「SandboxRuntime 保留为 provider 注册表」）；ShellSession 每次包命令时
 * 读取当前后端。var 可写仅供测试注入假后端。
 *
 * [拆围栏批 S3，2026-09-10] 三项状态：
 *  - `provider`：当前执行环境 provider（缺省 `host`）。
 *  - `backend`：provider 的执行面（Host / Seatbelt / Unavailable）。
 *  - `failureCause`：provider 面**显式失败**原因（U7）——非空时 Bash 必须明示失败，
 *    **绝不静默回落宿主执行**（container/auto 未实现、local-process probe 失败、
 *    provider 取值非法三种形态共用此通道）。
 */
object SandboxRuntime:
  private val logger = NebflowLogger(getClass)

  @volatile var backend: SandboxBackend = SandboxBackend.Host

  @volatile private var activeProvider: SandboxProvider = SandboxProvider.Host

  @volatile private var providerFailure: Option[String] = None

  /** 当前生效的 provider（启动期由 `init` 定妥；测试可直接断言）。 */
  def provider: SandboxProvider = activeProvider

  /** provider 面显式失败原因：`Some` = 不可用/未实现 ⇒ Bash 显式失败（U7）；
    * `None` = 正常（含 provider=host 与 enabled=false 旧行为）。 */
  def failureCause: Option[String] = providerFailure

  /** 启动时调用一次：按 provider 装配执行面（provider=local-process 才 probe，阻塞 <1s）。 */
  def init(cfg: SandboxConfig): Unit =
    // [S3] 判定次序：① provider 取值非法（配置层已解析出可读原因）→ 显式失败；
    // ② enabled=false → 旧行为回退（§4.5 保留：不 probe、不包裹、不拦 Bash）；
    // ③ provider 分派。
    cfg.providerError match
      case Some(err) =>
        activeProvider = SandboxProvider.Host
        backend = SandboxBackend.Unavailable
        providerFailure = Some(err)
        logger.errorSync(s"$err ⇒ sandbox provider 不可用：Bash 显式失败（不静默回落宿主执行，design §4.2 U7）")
      case None =>
        if !cfg.bashFailIfUnavailable then
          logger.warnSync(
            "sandbox.bash.failIfUnavailable 已退役（design §4.2 U7：provider 不可用一律显式失败，不静默降级）——本配置项不再生效"
          )
        if !cfg.enabled then
          activeProvider = SandboxProvider.Host
          backend = SandboxBackend.Unavailable
          providerFailure = None
          logger.infoSync("sandbox disabled by config (sandbox.enabled=false) — 旧行为回退（design §4.5 回退点）")
          if cfg.provider != SandboxProvider.Host then
            logger.warnSync(
              s"sandbox.provider=${cfg.provider} 被忽略：sandbox.enabled=false ⇒ 旧行为（宿主直跑、无包裹）"
            )
        else
          cfg.provider match
            case SandboxProvider.Host =>
              activeProvider = SandboxProvider.Host
              backend = SandboxBackend.Host
              providerFailure = None
              logger.infoSync("sandbox provider=host — 宿主直跑，无包裹（Bash 不再 fail-closed）")
            case SandboxProvider.LocalProcess =>
              activeProvider = SandboxProvider.LocalProcess
              val seatbelt = new SandboxBackend.Seatbelt()
              backend = seatbelt
              if seatbelt.available then
                providerFailure = None
                logger.infoSync(
                  s"sandbox provider=local-process — ${seatbelt.name} 就绪，宿主 Bash 恢复包裹（§4.5 回退点）"
                )
              else
                providerFailure = Some(
                  "provider=local-process 的进程级后端不可用（sandbox-exec probe 失败；嵌套沙箱环境或被裁剪的系统上常见）"
                )
                logger.errorSync(
                  "sandbox provider=local-process: sandbox-exec probe FAILED ⇒ Bash 显式失败（不降级到宿主执行，design §4.2 U7）"
                )
            case SandboxProvider.Container =>
              activeProvider = SandboxProvider.Container
              backend = SandboxBackend.Unavailable
              providerFailure = Some(
                "provider=container 本批未实现（容器线按 design §3.2 既有分期推进，容器实施批落地前不可用）"
              )
              logger.errorSync(
                "sandbox provider=container 未实现 ⇒ Bash 显式失败（不静默回落宿主执行，design §4.2 U7）"
              )
            case SandboxProvider.Auto =>
              activeProvider = SandboxProvider.Auto
              backend = SandboxBackend.Unavailable
              providerFailure = Some(
                "provider=auto（container → local-process → host 三段降级，design §4.2）本批未实现——带三重可见的降级链属容器实施批"
              )
              logger.errorSync(
                "sandbox provider=auto 未实现 ⇒ Bash 显式失败（不静默回落宿主执行，design §4.2 U7）"
              )

  def current: SandboxBackend = backend

  /** provider 面显式失败文案（§A.4-4 的 SANDBOX_UNAVAILABLE 契约保留：模型侧只
    * 需认前缀）。语义要点：说明**为什么**、**期望是什么**、**怎么改**——并显式
    * 声明「没有回落宿主执行」，防模型误以为命令已在宿主上跑过。 */
  def failureMessage(cause: String, root: os.Path, providerRef: SandboxProvider = activeProvider): String =
    s"""SANDBOX_UNAVAILABLE
       |[sandbox: provider=$providerRef — $cause. Bash execution is refused (explicit failure) and was NOT silently downgraded to host execution.]
       |Root: $root
       |Fix: set "sandbox": { "provider": "host" } in nebflow.json to run on the host explicitly (the default), or "local-process" to wrap Bash in the macOS Seatbelt profile; for provider=container/auto wait for the container batch.""".stripMargin
end SandboxRuntime
