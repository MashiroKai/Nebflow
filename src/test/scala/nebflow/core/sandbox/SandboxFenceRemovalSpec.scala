package nebflow.core.sandbox

import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.agent.{AgentState, ContextRefresher}
import nebflow.core.PathUtil
import nebflow.core.tools.{GlobTool, GrepTool, ReadTool, ToolContext, ToolPathUtil}

import java.nio.file.{Files, Paths}

/**
 * 沙箱拆围栏批 S1（解耦 sandboxEnabled 信号）+ S2（拆 JVM 文件闸）的**定向行为
 * 测试**——只覆盖本两步引入/变更的行为，不替代（也不修改）`SandboxSpec` 的存量
 * 断言（那批的语义失效与重写属清理批 B5，design §5#5 / §6.1 R13）。
 *
 * 覆盖点：
 *  - S1-a：`agentsMdEnabledFor` 判据换成 `projectSession`（项目节点/分发器仍注入；
 *    Nebula 根会话仍不注入 = 首条验收「注入仍生效」）；
 *  - S1-b：路径语义载体 `SandboxPolicy.pathRoot` 与 `ToolPathUtil`（相对路径基准=
 *    会话根、`~` 展开、Grep/Glob 缺省根不回归 JVM `user.dir`）；
 *  - S2-a：写根/读拒判定退役——六工具共用的三个入口对旧写根外路径一律放行；
 *  - S2-b：`agents/<agent>/memory.md` **写拒保留**（R3=c1 写侧例外）+ Nebula 自身
 *    豁免 + symlink 间接路径同拦；读侧放开（负向规则与遍历排除退役）；
 *  - S2-c：`PathUtil`/`off`（enabled=false）回退态行为逐字不变（旧行为）。
 *
 * 环境纪律：本机会话 os.home 不可写（Seatbelt 包裹），故数据根一律钉到 `/tmp` 下
 * 一次性目录，探针不写 `$HOME`；afterEach 逐个删除，零残留。
 */
class SandboxFenceRemovalSpec extends CatsEffectSuite:

  private val rgAvailable: Boolean =
    try
      val p = new ProcessBuilder("rg", "--version").start()
      p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0
    catch case _: Exception => false

  private var savedDataRoot: Option[os.Path] = None
  private var pinnedDataRoot: Option[os.Path] = None
  private val createdDirs = scala.collection.mutable.ListBuffer.empty[os.Path]

  override def beforeEach(context: munit.BeforeEach): Unit =
    savedDataRoot = Some(PathUtil.dataRoot)
    val pinned = os.Path(Files.createTempDirectory("nb-s2-dataroot"))
    createdDirs += pinned
    os.makeDir.all(pinned / "agents" / "Coder")
    os.makeDir.all(pinned / "agents" / "Nebula")
    os.write.over(pinned / "agents" / "Coder" / "memory.md", "coder memory NBX_S2_CODER_MEM")
    os.write.over(pinned / "agents" / "Nebula" / "memory.md", "nebula memory NBX_S2_NEBULA_MEM")
    pinnedDataRoot = Some(pinned)
    PathUtil.setDataRoot(pinned)
    super.beforeEach(context)

  override def afterEach(context: munit.AfterEach): Unit =
    savedDataRoot.foreach(PathUtil.setDataRoot)
    savedDataRoot = None
    pinnedDataRoot = None
    // 自清：本批建的临时目录一律删除（跑完零残留；与 AGENTS.md「自起进程跑完即清」
    // 同口径的测试侧纪律）
    createdDirs.foreach(p => try os.remove.all(p) catch case _: Exception => ())
    createdDirs.clear()
    super.afterEach(context)

  /** 开启态策略（真实 canonical）——pathRoot 随 forRoot 与 root 同源。 */
  private def policyIn(tmp: os.Path): SandboxPolicy = SandboxPolicy.forRoot(tmp, SandboxConfig())

  private def ctxIn(tmp: os.Path): ToolContext = ToolContext(projectRoot = tmp.toString, sandbox = policyIn(tmp))

  private def freshDir(tag: String): os.Path =
    val d = os.Path(SandboxPolicy.canonicalize(Files.createTempDirectory(s"nb-s2-$tag").toAbsolutePath))
    createdDirs += d
    d

  // ------------------------------------------------------------------
  // S1：会话信号解耦
  // ------------------------------------------------------------------

  test("S1-a: AGENTS.md 注入判据 = projectSession——项目节点会话仍注入，Nebula 根会话仍不注入") {
    assert(
      ContextRefresher.agentsMdEnabledFor(projectSession = true, projectRoot = Some("/ws/a"), agentName = "qa-backend"),
      "项目节点会话（projectSession=true）必须仍注入 AGENTS.md"
    )
    assert(
      ContextRefresher.agentsMdEnabledFor(projectSession = true, projectRoot = Some("/ws/a"), agentName = "project-dispatcher"),
      "分发器会话必须仍注入 AGENTS.md"
    )
    // 边界：Nebula 根会话（WS 根会话形态位不置 false→projectSession=false；名字排除为第二道保险）
    assert(
      !ContextRefresher.agentsMdEnabledFor(projectSession = false, projectRoot = Some("/x/.nebflow/projects"), agentName = "Nebula"),
      "Nebula 根会话不得注入"
    )
    assert(
      !ContextRefresher.agentsMdEnabledFor(projectSession = true, projectRoot = Some("/x/.nebflow/projects"), agentName = "Nebula"),
      "即便 projectSession 误置 true，Nebula 名字排除仍拦（第二道保险）"
    )
    assert(!ContextRefresher.agentsMdEnabledFor(projectSession = true, projectRoot = None), "无 projectRoot 不注入")
    assert(!ContextRefresher.agentsMdEnabledFor(projectSession = true, projectRoot = Some("")), "空 projectRoot 不注入")
  }

  test("S1-a: 会话信号与围栏总闸解耦——AgentState.session.projectSession 与 sandboxEnabled 独立置位") {
    val nodeShape = AgentState(sessionId = Some("s-node"), projectSession = true, sandboxEnabled = true)
    assertEquals(nodeShape.session.projectSession, true)
    assertEquals(nodeShape.session.sandboxEnabled, true)
    // 解耦核心：围栏总闸关掉（sandboxEnabled=false）不影响项目会话注入判据
    val fenceOffShape = AgentState(sessionId = Some("s-node-off"), projectSession = true, sandboxEnabled = false)
    assert(ContextRefresher.agentsMdEnabledFor(fenceOffShape.session.projectSession, Some("/ws/b"), "qa-backend"),
      "sandboxEnabled=false + projectSession=true ⇒ 仍注入（拆围栏不得连带关注入）")
    // 默认位 = 非项目会话（WS 根会话 / team / flow / Delegate / SubTask 双轨面）
    assertEquals(AgentState().session.projectSession, false)
    assertEquals(AgentState().session.sandboxEnabled, false)
  }

  test("S1-b: pathRoot 与 root 同源（forRoot）；off = 无会话根（路径语义回旧行为）") {
    val tmp = freshDir("pathroot")
    val p = policyIn(tmp)
    assertEquals(p.pathRoot.map(_.toString), Some(p.root.toString), "有会话根时会话根 == policy.root（同一次 canonicalize）")
    assertEquals(SandboxPolicy.off.pathRoot, None, "off = 无会话根")
    // 回退点（cfg.enabled=false → off）不删：路径语义亦随之回旧
    assertEquals(SandboxPolicy.forRoot(tmp, SandboxConfig(enabled = false)).pathRoot, None)
  }

  test("S1-b: ToolPathUtil.searchBaseDir——有会话根取会话根，无会话根才回落 JVM user.dir") {
    val tmp = freshDir("base")
    assertEquals(ToolPathUtil.searchBaseDir(policyIn(tmp), "/sentinel/userdir").toString, policyIn(tmp).root.toString)
    assertEquals(ToolPathUtil.searchBaseDir(SandboxPolicy.off, "/sentinel/userdir").toString, "/sentinel/userdir")
  }

  test("S1-b: ToolPathUtil.resolveAgainstToolRoot——相对路径基准=会话根 + ~ 展开；无会话根=旧行为（仅绝对路径）") {
    val tmp = freshDir("resolve")
    val p = policyIn(tmp)
    // 相对路径 → 会话根为基准（canonical 域比较：/var → /private/var 归一）
    assertEquals(
      ToolPathUtil.resolveAgainstToolRoot(p, "sub/x.txt").toOption.map(_.toString),
      Some(SandboxPolicy.canonicalize((tmp / "sub" / "x.txt").wrapped).toString)
    )
    // 绝对路径原样
    assertEquals(ToolPathUtil.resolveAgainstToolRoot(p, "/abs/x.txt").toOption.map(_.toString), Some("/abs/x.txt"))
    // `~` 展开（PathUtil.expandTilde 等价能力）
    val expanded = Paths.get(PathUtil.expandTilde("~/probe.txt")).toString
    assertEquals(ToolPathUtil.resolveAgainstToolRoot(p, "~/probe.txt").toOption.map(_.toString), Some(expanded))
    // 无会话根（off / 回退态）= 旧行为逐字不变：相对路径拒（含 ~ 形态，不展开），绝对路径原样
    assertEquals(
      ToolPathUtil.resolveAgainstToolRoot(SandboxPolicy.off, "rel.txt").swap.toOption.map(_.message),
      Some("Path must be absolute, got: rel.txt")
    )
    assertEquals(ToolPathUtil.resolveAgainstToolRoot(SandboxPolicy.off, "~/rel.txt").swap.toOption.map(_.message),
      Some("Path must be absolute, got: ~/rel.txt"))
    assertEquals(ToolPathUtil.resolveAgainstToolRoot(SandboxPolicy.off, "/abs/y.txt").toOption.map(_.toString),
      Some("/abs/y.txt"))
  }

  // ------------------------------------------------------------------
  // S2：拆闸（写根/读拒退役）+ 保解析 + R3=c1 写侧例外
  // ------------------------------------------------------------------

  test("S2-a: 六工具共用入口不再做写根判定——旧写根外路径一律放行（JVM 工具层实证）") {
    val tmp = freshDir("gate")
    val ctx = ctxIn(tmp)
    val outsideHome = os.home / "nb-s2-decouple-probe.txt"
    // Write/Edit/MultiEdit → checkWrite；Read → checkRead；Glob/Grep 搜索根 → checkReadRoot
    for
      case (probe, isWrite) <- List(
        (outsideHome.toString, true),
        ("/etc/hosts", true),
        (outsideHome.toString, false),
        ((os.home / "Desktop").toString, false)
      )
    do
      val res = if isWrite then FileSandbox.checkWrite(ctx, probe) else FileSandbox.checkRead(ctx, probe)
      res match
        case Right(_) => ()
        case Left(err) =>
          fail(s"旧写根外路径（$probe, ${if isWrite then "write" else "read"}）不得因文件闸被拒：${err.message}")
    FileSandbox.checkReadRoot(ctx, os.Path(os.home.toString)) match
      case Right(_) => ()
      case Left(err) => fail(s"checkReadRoot(os.home) 不得因文件闸被拒：${err.message}")
    // 相对路径基准仍 = 会话根（保解析，未随闸门一起丢）
    assertEquals(
      FileSandbox.checkWrite(ctx, "rel/new.txt").toOption.map(_.toString),
      Some(SandboxPolicy.canonicalize((tmp / "rel" / "new.txt").wrapped).toString),
      "相对路径基准必须仍是会话根"
    )
  }

  test("S2-a: 回退态（off / enabled=false）行为逐字不变——四件套仍拒相对路径，无 canon/无闸") {
    val tmp = freshDir("rollback")
    val offCtx = ToolContext(projectRoot = tmp.toString, sandbox = SandboxPolicy.off)
    assertEquals(FileSandbox.checkWrite(offCtx, "rel.txt").swap.toOption.map(_.message),
      Some("Path must be absolute, got: rel.txt"))
    assertEquals(FileSandbox.checkRead(offCtx, "rel.txt").swap.toOption.map(_.message),
      Some("Path must be absolute, got: rel.txt"))
    assertEquals(FileSandbox.checkWrite(offCtx, "/tmp/rollback-probe.txt").toOption.map(_.toString),
      Some("/tmp/rollback-probe.txt"), "off 态原样返回（不 canonicalize，旧行为）")
    // cfg.enabled=false → forRoot 短路 off（§4.5 回退点保留）
    val cfgOff = ToolContext(projectRoot = tmp.toString, sandbox = SandboxPolicy.forRoot(tmp, SandboxConfig(enabled = false)))
    assertEquals(FileSandbox.checkWrite(cfgOff, "rel.txt").swap.toOption.map(_.message),
      Some("Path must be absolute, got: rel.txt"))
  }

  test("S2-b: agents/<agent>/memory.md 写拒仍在（R3=c1 写侧例外）；Nebula 自身豁免；symlink 间接路径同拦") {
    val tmp = freshDir("memkeep")
    val ctx = ctxIn(tmp)
    val coderMem = PathUtil.dataRoot / "agents" / "Coder" / "memory.md"
    val nebulaMem = PathUtil.dataRoot / "agents" / "Nebula" / "memory.md"
    FileSandbox.checkWrite(ctx, coderMem.toString) match
      case Left(err) =>
        assert(err.message.startsWith("SANDBOX_DENIED"), err.message)
        assert(err.message.contains("private-memory"), s"文案须点名规则: ${err.message}")
      case Right(_) => fail("agents/<agent>/memory.md（非 Nebula 份）写必须仍拒——R3=c1 写侧例外")
    assert(FileSandbox.checkWrite(ctx, nebulaMem.toString).isRight,
      "Nebula 自身 memory.md 走审计只读例外（写放行，数据根写面承载）——例外语义不得被误扩大")
    // symlink 间接路径：canonical 域比较拦下（与旧实现同构）
    val alias = tmp / "alias.md"
    try
      os.symlink(alias, coderMem)
      FileSandbox.checkWrite(ctx, alias.toString) match
        case Left(err) => assert(err.message.startsWith("SANDBOX_DENIED"), err.message)
        case Right(_) => fail("指向私有记忆的 symlink 间接写必须仍拒")
    catch case _: Exception => () // 文件系统不支持 symlink：跳过该子断言（其余断言已覆盖）
  }

  test("S2-b: 读侧放开——agents/<agent>/memory.md 读不再拒（负向规则退役）+ ReadTool 端到端读到内容") {
    val tmp = freshDir("readopen")
    val ctx = ctxIn(tmp)
    val coderMem = PathUtil.dataRoot / "agents" / "Coder" / "memory.md"
    assert(FileSandbox.checkRead(ctx, coderMem.toString).isRight, "R3=c1 读拆：读侧负向规则退役")
    FileSandbox.checkReadRoot(ctx, PathUtil.dataRoot / "agents") match
      case Right(_) => ()
      case Left(err) => fail(s"checkReadRoot(agents) 不得被拒：${err.message}")
    ReadTool.call(JsonObject("file_path" -> coderMem.toString.asJson), ctx).unsafeRunSync() match
      case Right(out) => assert(out.contains("NBX_S2_CODER_MEM"), s"读侧放开后应读到内容: $out")
      case Left(err) => fail(s"ReadTool 不得再拒 agents/<agent>/memory.md：${err.message}")
  }

  test("S2-c: rg 遍历排除退役——Grep/Glob 在 agents 子树内可命中 memory.md") {
    assume(rgAvailable, "rg 不可用（跳过；该子项为读侧放开的工具层实证）")
    val tmp = freshDir("traverse")
    val ctx = ctxIn(tmp)
    val agentsDir = PathUtil.dataRoot / "agents"
    GrepTool
      .call(
        JsonObject(
          "pattern" -> "NBX_S2_CODER_MEM".asJson,
          "path" -> agentsDir.toString.asJson,
          "output_mode" -> "content".asJson
        ),
        ctx
      )
      .unsafeRunSync() match
      case Right(out) => assert(out.contains("memory.md"), s"遍历排除退役后应命中 memory.md: $out")
      case Left(err) => fail(s"Grep 不得报错：${err.message}")
    GlobTool.call(JsonObject("pattern" -> "**/memory.md".asJson, "path" -> agentsDir.toString.asJson), ctx).unsafeRunSync() match
      case Right(out) =>
        assert(out.contains("memory.md"), s"Glob 应命中 memory.md: $out")
        assert(out.contains("Nebula") && out.contains("Coder"), s"应命中两份 agent 记忆: $out")
      case Left(err) => fail(s"Glob 不得报错：${err.message}")
  }

  test("S2-c: Grep/Glob 缺省根 = 会话根（不回归 JVM user.dir）") {
    assume(rgAvailable, "rg 不可用（跳过）")
    val tmp = freshDir("defroot")
    os.write.over(tmp / "needle.txt", "NBX_S2_DEFAULT_ROOT_NEEDLE")
    val ctx = ctxIn(tmp)
    GrepTool.call(JsonObject("pattern" -> "NBX_S2_DEFAULT_ROOT_NEEDLE".asJson), ctx).unsafeRunSync() match
      case Right(out) => assert(out.contains("needle.txt"), s"Grep 缺省根应为会话根: $out")
      case Left(err) => fail(s"Grep 缺省根调用失败：${err.message}")
    GlobTool.call(JsonObject("pattern" -> "**/needle.txt".asJson), ctx).unsafeRunSync() match
      case Right(out) => assert(out.contains("needle.txt"), s"Glob 缺省根应为会话根: $out")
      case Left(err) => fail(s"Glob 缺省根调用失败：${err.message}")
  }
