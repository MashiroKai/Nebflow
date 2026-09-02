package nebflow.core.sandbox

import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.PathUtil
import nebflow.core.tools.{BashTool, GlobTool, GrepTool, ReadTool, ToolContext, WriteTool}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/**
 * 阶段 2a 沙箱 JVM 层可断言项（§A.8 对应 + feature flag/降级/白名单）。
 * Seatbelt 真实 OS 行为（touch 主仓被拒等）留给下游验证节点隔离实例 E2E——
 * 本文件不依赖 sandbox-exec（probe 探测以假后端注入模拟）。
 */
class SandboxSpec extends CatsEffectSuite:

  private val rgAvailable: Boolean =
    try
      val p = new ProcessBuilder("rg", "--version").start()
      p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0
    catch case _: Exception => false

  // [verify-fix] 2026-09-03 独立验证节点：PathUtil.dataRoot 是全局可变状态——同 JVM
  // 先跑的 suite（MailToolCheckTeamScopeSpec 等）setDataRoot 指向 /private/var/folders
  // 下临时目录且不复位时，readExtras 的 /private/var 读面会把 dataRoot 整体纳入可读，
  // 令 H-12①/A.8-4 的「根层拒读」断言偶发失败（全量跑 flaky，单跑稳定）。
  // 修复：每个用例前把 dataRoot 钉到 home 下一次性目录（不在任何系统读面内），
  // 用例后还原。生产环境 dataRoot=~/.nebflow 同样不在读面内，行为语义不变。
  private var savedDataRoot: Option[os.Path] = None
  private var pinnedDataRoot: Option[os.Path] = None

  override def beforeEach(context: munit.BeforeEach): Unit =
    savedDataRoot = Some(PathUtil.dataRoot)
    val pinned = os.home / s".nb-sbx-dataroot-${System.nanoTime()}"
    os.makeDir.all(pinned / "skills" / "fixture-skill")
    os.write.over(pinned / "skills" / "fixture-skill" / "SKILL.md", "fixture skill body")
    os.makeDir.all(pinned / "prompts")
    os.makeDir.all(pinned / "docs")
    os.makeDir.all(pinned / "agents")
    os.write.over(pinned / "agents" / "Nebula.md", "fixture agent")
    os.write.over(pinned / "auth.json", "\"fixture-token\"")
    PathUtil.setDataRoot(pinned)
    pinnedDataRoot = Some(pinned)
    super.beforeEach(context)

  /** 构造以 tmpdir 为根的开启态策略（真实 canonical——macOS /tmp→/private/tmp）。 */
  private def policyIn(tmp: os.Path, cfg: SandboxConfig = SandboxConfig()): SandboxPolicy =
    SandboxPolicy.forRoot(tmp, cfg)

  private def ctxIn(tmp: os.Path, policy: SandboxPolicy = null): ToolContext =
    val p = if policy == null then policyIn(tmp) else policy
    ToolContext(projectRoot = tmp.toString, sandbox = p)

  // ------------------------------------------------------------------
  // SandboxPolicy：单一策略源 + canonicalize（§A.2）
  // ------------------------------------------------------------------

  test("A.2: writableRoots 派生自 policy——含 root、/private/tmp、/tmp、java.io.tmpdir，canonical 去重") {
    val tmp = os.Path(Files.createTempDirectory("nb-sbx-policy"))
    val p = policyIn(tmp)
    val roots = SandboxPolicy.writableRoots(p)
    assert(roots.exists(_.toString == p.root.toString), s"root must be writable: $roots")
    assert(roots.contains(os.Path("/private/tmp")), s"/private/tmp must be writable: $roots")
    assert(!roots.contains(os.Path("/tmp")), s"/tmp 应已归一进 /private/tmp: $roots")
    val tmpdir = SandboxPolicy.canonicalize(Paths.get(sys.props("java.io.tmpdir")))
    assert(roots.map(_.toString).contains(tmpdir.toString), s"java.io.tmpdir must be writable: $roots")
    // 写 ⊆ 读不变量
    val readable = SandboxPolicy.readableRoots(p)
    roots.foreach(r => assert(readable.contains(r), s"writable must be readable: $r"))
  }

  test("A.2: canonicalize 解析 symlink（/tmp→/private/tmp）且对已存在部分用内核 realpath 语义") {
    assertEquals(SandboxPolicy.canonicalize(Paths.get("/tmp")).toString, "/private/tmp")
    // <root>/../escape.txt：root 存在 → realpath 解析 .. → 出 root 的真实形态
    val tmp = os.Path(Files.createTempDirectory("nb-sbx-canon"))
    val escape = SandboxPolicy.canonicalize(Paths.get(s"$tmp/../escape.txt"))
    assert(!escape.startsWith(tmp.wrapped), s".. 必须真实解析出界: $escape")
    // 词法冗余拼接归一：<root>//sub//new.txt（sub/new 均不存在）——期望值同样
    // 以 canonical tmp 为基（macOS tmpdir /var/... 真实形态是 /private/var/...）
    val realTmp = os.Path(SandboxPolicy.canonicalize(tmp.wrapped))
    val nested = SandboxPolicy.canonicalize(Paths.get(s"$tmp//sub//new.txt"))
    assertEquals(nested.toString, (realTmp / "sub" / "new.txt").toString)
  }

  test("A.2: readExtras 含系统目录与 ~/.nebflow 三子目录（skills/prompts/docs），不含根层凭据") {
    val p = policyIn(os.Path(Files.createTempDirectory("nb-sbx-extras")))
    val extras = SandboxPolicy.readableRoots(p)
    assert(extras.contains(os.Path("/usr")))
    assert(extras.contains(os.Path("/private/etc")))
    val skills = PathUtil.dataRoot / "skills"
    assert(extras.exists(_.toString == SandboxPolicy.canonicalize(skills.wrapped).toString),
      s"~/.nebflow/skills must be readable: $extras")
    // ~/.nebflow 根层不在读面（auth.json 等凭据拒读）
    assert(!extras.exists(_.toString == SandboxPolicy.canonicalize(PathUtil.dataRoot.wrapped).toString),
      s"~/.nebflow 根层不得进读面: $extras")
  }

  // ------------------------------------------------------------------
  // §A.8-1：SANDBOX_DENIED 消息含 canonical + roots + 自纠指引
  // ------------------------------------------------------------------

  test("A.8-1: Write /etc/hosts → SANDBOX_DENIED，含 canonical 路径、Writable roots、指引") {
    val tmp = os.Path(Files.createTempDirectory("nb-sbx-msg"))
    val res = FileSandbox.checkWrite(ctxIn(tmp), "/etc/hosts")
    res match
      case Left(err) =>
        assert(err.message.startsWith("SANDBOX_DENIED"), err.message)
        assert(err.message.contains("/private/etc/hosts"), s"应含 canonical 路径: ${err.message}")
        assert(err.message.contains(s"outside sandbox root"), err.message)
        assert(err.message.contains("Writable roots:"), err.message)
        assert(err.message.contains("Readable roots:"), err.message)
        assert(err.message.contains("report to the dispatcher"), s"应含自纠指引: ${err.message}")
      case Right(_) => fail("/etc/hosts 写必须被拒")
  }

  // ------------------------------------------------------------------
  // §A.8-2：.. 逃逸拒；词法冗余归一放行
  // ------------------------------------------------------------------

  test("A.8-2: <root>/../escape.txt 拒；<root>//sub//new.txt 归一后放行且返回 fresh 路径") {
    // root 用 home 下目录（模拟真实 project root）——tmpdir 本身在 java.io.tmpdir
    // 可写根内，<tmpdir>/../escape 会落回 tmpdir 而合法可写，不能当项目根用。
    val tmp = os.home / s".nb-sbx-root-${System.nanoTime()}"
    os.makeDir.all(tmp)
    val ctx = ctxIn(tmp)
    FileSandbox.checkWrite(ctx, s"$tmp/../escape.txt") match
      case Left(err) => assert(err.message.startsWith("SANDBOX_DENIED"))
      case Right(_) => fail("../ 逃逸必须被拒")
    FileSandbox.checkWrite(ctx, s"$tmp//sub//new.txt") match
      case Right(p) =>
        assertEquals(p.toString, SandboxPolicy.canonicalize(Paths.get(s"$tmp/sub/new.txt")).toString)
      case Left(err) => fail(s"词法冗余路径应放行: ${err.message}")
    os.remove.all(tmp)
  }

  // ------------------------------------------------------------------
  // §A.8-3：root 内 symlink 指外 → 写拒读拒；root 内深层新文件创建成功
  // ------------------------------------------------------------------

  /** home 下的外部目标（home 不在任何 read/writable root——/private/var 等
    * tempdir 落点反而 readable/writable，不能当「外部」用）。先清残留再建。 */
  private def outsideDir(tag: String): os.Path =
    val d = os.home / s".nb-sbx-outside-$tag"
    os.remove.all(d)
    os.makeDir.all(d)
    d

  test("A.8-3: root 内 symlink 指外写/读均拒；深层新文件 checkWrite 放行") {
    val tmp = os.Path(Files.createTempDirectory("nb-sbx-sym"))
    val outside = outsideDir("sym")
    val outsideFile = outside / "target.txt"
    os.write.over(outsideFile, "secret")
    val link = tmp / "link.txt"
    os.symlink(link, outsideFile) // os.symlink(链接落点, 指向目标)

    val ctx = ctxIn(tmp)
    // 写拒（canonicalize 解析出 symlink → 越界）
    FileSandbox.checkWrite(ctx, link.toString) match
      case Left(err) => assert(err.message.startsWith("SANDBOX_DENIED"), err.message)
      case Right(_) => fail(s"指向外部的 symlink 写必须被拒: $link")
    // 读拒
    FileSandbox.checkRead(ctx, link.toString) match
      case Left(err) => assert(err.message.startsWith("SANDBOX_DENIED"), err.message)
      case Right(_) => fail(s"指向外部的 symlink 读必须被拒: $link")

    // root 内深层新文件：放行且真实可写
    val deep = tmp / "a" / "b" / "new.txt"
    FileSandbox.checkWrite(ctx, deep.toString) match
      case Right(fresh) =>
        Files.createDirectories(fresh.getParent)
        Files.write(fresh, "ok".getBytes(StandardCharsets.UTF_8))
        assert(Files.readString(fresh) == "ok")
      case Left(err) => fail(s"root 内深层新文件必须放行: ${err.message}")
    os.remove.all(outside)
  }

  // ------------------------------------------------------------------
  // §A.8-4：Glob/Grep 不跟随 root 内指向外部的 symlink 目录
  // ------------------------------------------------------------------

  test("A.8-4: Glob/Grep 不遍历指向外部的 symlink 目录") {
    assume(rgAvailable, "rg 不可用则跳过（Glob/Grep 依赖 ripgrep）")
    val tmp = os.Path(Files.createTempDirectory("nb-sbx-glob"))
    val outside = outsideDir("glob")
    os.write.over(outside / "leak.txt", "NEEDLE_LEAK_CONTENT")
    os.write.over(tmp / "inside.txt", "inside content")
    os.symlink(tmp / "linkdir", outside) // os.symlink(链接落点, 指向目标)

    val ctx = ctxIn(tmp)
    val glob = GlobTool.call(JsonObject("pattern" -> "**/*.txt".asJson), ctx).unsafeRunSync()
    glob match
      case Right(out) =>
        assert(out.contains("inside.txt"), s"应包含 root 内文件: $out")
        assert(!out.contains("leak.txt"), s"不得泄露 symlink 外部文件: $out")
      case Left(err) => fail(s"Glob 应成功: ${err.message}")

    val grep = GrepTool.call(JsonObject("pattern" -> "NEEDLE_LEAK_CONTENT".asJson), ctx).unsafeRunSync()
    grep match
      case Right(out) => assert(!out.contains("leak.txt"), s"Grep 不得命中 symlink 外部: $out")
      case Left(err) => fail(s"Grep 应成功（无命中也是成功态）: ${err.message}")

    // 搜索根本身指向 readableRoots 之外（~/.nebflow 根层）→ SANDBOX_DENIED。
    // 注：不能用 tempdir 当「外部」——/private/var 在读面内，语义上可读。
    val deny = GlobTool.call(
      JsonObject("pattern" -> "*.txt".asJson, "path" -> (PathUtil.dataRoot / "agents").toString.asJson),
      ctx
    ).unsafeRunSync()
    deny match
      case Left(err) => assert(err.message.startsWith("SANDBOX_DENIED"), err.message)
      case Right(_) => fail("readableRoots 外的搜索根必须被拒")
    os.remove.all(outside)
  }

  // ------------------------------------------------------------------
  // §A.8-8：相对路径按 node root 解析（非 JVM user.dir）
  // ------------------------------------------------------------------

  test("A.8-8: Read 相对路径按 sandbox.root 解析（而非 user.dir）") {
    val tmp = os.Path(Files.createTempDirectory("nb-sbx-rel"))
    os.write.over(tmp / "a.txt", "relative-by-root")
    val res = ReadTool.call(JsonObject("file_path" -> "a.txt".asJson), ctxIn(tmp)).unsafeRunSync()
    res match
      case Right(content) => assert(content.contains("relative-by-root"), content)
      case Left(err) => fail(s"相对路径应按 node root 解析: ${err.message}")
  }

  test("A.8-8b: Glob 相对 path 参数按 sandbox.root 解析") {
    assume(rgAvailable, "rg 不可用则跳过")
    val tmp = os.Path(Files.createTempDirectory("nb-sbx-relglob"))
    os.makeDir.all(tmp / "sub")
    os.write.over(tmp / "sub" / "found.txt", "x")
    val res = GlobTool.call(JsonObject("pattern" -> "*.txt".asJson, "path" -> "sub".asJson), ctxIn(tmp))
      .unsafeRunSync()
    res match
      case Right(out) => assert(out.contains("found.txt"), out)
      case Left(err) => fail(s"相对 path 应按 node root 解析: ${err.message}")
  }

  // ------------------------------------------------------------------
  // feature flag：sandbox.enabled=false 回旧行为（§G.1 回滚）
  // ------------------------------------------------------------------

  test("G.1 回滚: sandbox off——相对路径仍拒（旧行为）、root 外写放行、Glob 缺省根=user.dir") {
    val off = SandboxPolicy.off
    val tmp = os.Path(Files.createTempDirectory("nb-sbx-off"))
    val ctx = ToolContext(projectRoot = tmp.toString, sandbox = off)

    // 旧行为：相对路径拒绝
    FileSandbox.checkWrite(ctx, "rel.txt") match
      case Left(err) => assert(err.message.contains("Path must be absolute"), err.message)
      case Right(_) => fail("off 时相对路径应保持旧行为（拒绝）")

    // 旧行为：root 外绝对路径放行
    val outside = Files.createTempDirectory("nb-sbx-off-out")
    val target = outside.resolve("free.txt")
    val res = WriteTool.call(
      JsonObject("file_path" -> target.toString.asJson, "content" -> "free".asJson),
      ctx
    ).unsafeRunSync()
    res match
      case Right(_) => assert(Files.readString(target) == "free")
      case Left(err) => fail(s"off 时 root 外写必须放行: ${err.message}")

    // Glob 不带沙箱时缺省根=user.dir（旧行为）——不抛 SANDBOX_DENIED
    assume(rgAvailable, "rg 不可用则跳过")
    val glob = GlobTool.call(JsonObject("pattern" -> "*.scala".asJson), ctx).unsafeRunSync()
    assert(glob.isRight, s"off 时 Glob 保持旧行为: $glob")
  }

  // ------------------------------------------------------------------
  // ~/.nebflow 白名单（H-12①）：三子目录可读、根层拒读写、不在可写根
  // ------------------------------------------------------------------

  test("H-12①: ~/.nebflow/skills 可读；根层 auth.json 读写均拒") {
    val tmp = os.Path(Files.createTempDirectory("nb-sbx-nf"))
    val policy = policyIn(tmp)
    val ctx = ToolContext(projectRoot = tmp.toString, sandbox = policy)

    // skills 子目录可读（readExtras 派生自 dataRoot——测试隔离下用同一 dataRoot 断言）
    val skillsRead = FileSandbox.checkRead(ctx, (PathUtil.dataRoot / "skills" / "x" / "SKILL.md").toString)
    assert(skillsRead.isRight, s"skills 应可读: ${skillsRead.left.map(_.message)}")

    // 根层凭据拒读
    FileSandbox.checkRead(ctx, (PathUtil.dataRoot / "auth.json").toString) match
      case Left(err) => assert(err.message.startsWith("SANDBOX_DENIED"), err.message)
      case Right(_) => fail("~/.nebflow/auth.json 必须拒读")

    // 根层与 skills 均不在可写根（agent 只在 project 内写）
    FileSandbox.checkWrite(ctx, (PathUtil.dataRoot / "skills" / "w.txt").toString) match
      case Left(err) => assert(err.message.startsWith("SANDBOX_DENIED"), err.message)
      case Right(_) => fail("~/.nebflow/skills 不在可写根")
    FileSandbox.checkWrite(ctx, (PathUtil.dataRoot / "auth.json").toString) match
      case Left(err) => assert(err.message.startsWith("SANDBOX_DENIED"), err.message)
      case Right(_) => fail("~/.nebflow/auth.json 必须拒写")
  }

  // ------------------------------------------------------------------
  // probe 失败：fail-closed（默认）/ 显式降级（§A.4-4）
  // ------------------------------------------------------------------

  private val fakeBackend = new SandboxBackend:
    val name = "fake-unavailable"
    val available = false
    def wrap(argv: List[String], policy: SandboxPolicy): Option[List[String]] = None

  override def afterEach(context: munit.AfterEach): Unit =
    // 恢复真实后端注册（GatewayMain 语义），避免污染其他 spec
    SandboxRuntime.backend = SandboxBackend.Unavailable
    // [verify-fix] 还原被钉住的 dataRoot（见 beforeEach 注释）
    pinnedDataRoot.foreach(os.remove.all)
    savedDataRoot.foreach(PathUtil.setDataRoot)
    pinnedDataRoot = None
    savedDataRoot = None
    super.afterEach(context)

  test("§A.4-4: probe 失败默认 fail-closed——Bash 报 SANDBOX_UNAVAILABLE 不执行") {
    val tmp = os.Path(Files.createTempDirectory("nb-sbx-probe"))
    SandboxRuntime.backend = fakeBackend
    val ctx = ctxIn(tmp).copy(sessionId = Some("nb-sbx-failclosed")) // bashFailIfUnavailable 默认 true
    val res = BashTool.call(
      JsonObject("command" -> "echo should-not-run".asJson),
      ctx
    ).unsafeRunSync()
    res match
      case Left(err) =>
        assert(err.message.startsWith("SANDBOX_UNAVAILABLE"), err.message)
        assert(!err.message.contains("should-not-run"))
      case Right(out) => fail(s"fail-closed 必须拒绝执行: $out")
  }

  test("§A.4-4: failIfUnavailable=false 显式降级——命令执行且结果带 [unsandboxed] 前缀") {
    val tmp = os.Path(Files.createTempDirectory("nb-sbx-degrade"))
    SandboxRuntime.backend = fakeBackend
    val policy = SandboxPolicy.forRoot(tmp, SandboxConfig(bashFailIfUnavailable = false))
    val ctx = ctxIn(tmp, policy).copy(sessionId = Some("nb-sbx-degrade"))
    val res = BashTool.call(
      JsonObject("command" -> "echo degrade-ok".asJson),
      ctx
    ).unsafeRunSync()
    res match
      case Right(out) =>
        assert(out.startsWith("[unsandboxed]"), s"降级结果必须带前缀: $out")
        assert(out.contains("degrade-ok"), out)
      case Left(err) => fail(s"显式降级应执行命令: ${err.message}")
  }

  // ------------------------------------------------------------------
  // Seatbelt profile（纯文本断言，不跑 sandbox-exec）
  // ------------------------------------------------------------------

  test("§A.4-2: profile 含 allow default + 写白名单 + .git/hooks deny（param+-D），SBPL ≤900B") {
    val tmp = os.Path(Files.createTempDirectory("nb-sbx-profile"))
    val policy = policyIn(tmp)
    val (profile, params) = SandboxBackend.Seatbelt.profileFor(policy)
    assert(profile.startsWith("(version 1)(allow default)"), profile)
    assert(profile.contains("(deny file-write*)"), profile)
    assert(profile.contains("(literal \"/dev/null\")"), profile)
    assert(profile.contains(s"""(subpath "${policy.root}""""), s"root 子路径必须在 allow 内: $profile")
    assert(profile.contains("""(subpath "/private/tmp")"""), profile)
    assert(profile.contains("""(deny file-write* (subpath (param "SB_GIT_HOOKS")))"""), profile)
    assertEquals(params, List("-D", s"SB_GIT_HOOKS=${policy.root}/.git/hooks"))
    assert(profile.getBytes(StandardCharsets.UTF_8).length <= SandboxBackend.Seatbelt.ProfileBudgetBytes,
      s"SBPL 超 900B: ${profile.getBytes(StandardCharsets.UTF_8).length}")
  }

  test("§A.4-7: SandboxBackend trait——Unavailable 后端 wrap 返回 None") {
    val tmp = os.Path(Files.createTempDirectory("nb-sbx-backend"))
    val res = SandboxBackend.Unavailable.wrap(List("bash", "-c", "true"), policyIn(tmp))
    assertEquals(res, None)
  }

end SandboxSpec
