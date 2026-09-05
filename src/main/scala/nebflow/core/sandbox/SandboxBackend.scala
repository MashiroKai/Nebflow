package nebflow.core.sandbox

import java.util.concurrent.ConcurrentHashMap

import nebflow.core.NebflowLogger

/**
 * 阶段 2a 沙箱（设计文档 §A.4）：Bash 层 OS 强制后端抽象。
 *
 * 预留 SandboxBackend trait（Linux 可移植性，【沙箱调研】§5 建议）；本轮只实现
 * macOS Seatbelt 后端。Linux 后端（bubblewrap/landlock）未实现——见 hardening
 * 清单。
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

  /** 不可用后端（probe 失败 / 非 darwin / 未初始化）：永远 wrap 不出。 */
  object Unavailable extends SandboxBackend:
    val name = "unavailable"
    val available = false
    def wrap(argv: List[String], policy: SandboxPolicy): Option[List[String]] = None

  /**
   * macOS Seatbelt 后端（§A.4）。
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
 * 进程级后端注册表：GatewayMain 启动 probe 一次并缓存（§A.4-4）；ShellSession
 * 每次包命令时读取。var 可写仅供测试注入假后端（probe 失败模拟）。
 */
object SandboxRuntime:
  private val logger = NebflowLogger(getClass)

  @volatile var backend: SandboxBackend = SandboxBackend.Unavailable

  /** 启动时调用一次：配置开启才 probe（阻塞 <1s），结果缓存。 */
  def init(cfg: SandboxConfig): Unit =
    if cfg.enabled then
      val seatbelt = new SandboxBackend.Seatbelt()
      backend = seatbelt
      logger.infoSync(
        if seatbelt.available then s"sandbox backend ready: ${seatbelt.name}"
        else "sandbox-exec probe FAILED — Bash fail-closed (sandbox.bash.failIfUnavailable=false 降级可配)"
      )
    else
      backend = SandboxBackend.Unavailable
      logger.infoSync("sandbox disabled by config (sandbox.enabled=false) — 旧行为")

  def current: SandboxBackend = backend

  /** §A.4-4 fail-closed 默认错误（区别宿主执行失败）。 */
  def unavailableMessage(root: os.Path): String =
    s"""SANDBOX_UNAVAILABLE
       |[sandbox: sandbox-exec probe failed at startup; Bash execution is refused (fail-closed)]
       |Root: $root
       |Fix: set "sandbox": { "bash": { "failIfUnavailable": false } } in nebflow.json to explicitly run unsandboxed (WARN + [unsandboxed] prefix), or repair sandbox-exec on this machine.""".stripMargin
end SandboxRuntime
