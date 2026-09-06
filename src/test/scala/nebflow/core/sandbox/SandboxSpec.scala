package nebflow.core.sandbox

import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.agent.AgentState
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
    // 逃生门（沙箱工作区会话）：os.home 不可写时由 NB_SANDBOX_SPEC_DATAROOT 显式
    // 注入 pinned 根（指向既不在系统读面也不在写面的目录，语义不变——宿主/CI
    // 无此环境变量，行为与原实现完全一致）。
    val pinnedBase = sys.env.get("NB_SANDBOX_SPEC_DATAROOT") match
      case Some(dir) => os.Path(dir)
      case None      => os.home / s".nb-sbx-dataroot-${System.nanoTime()}"
    val pinned = if sys.env.contains("NB_SANDBOX_SPEC_DATAROOT") then
      val p = pinnedBase / s"run-${System.nanoTime()}"
      os.makeDir.all(p)
      p
    else pinnedBase
    os.makeDir.all(pinned / "skills" / "fixture-skill")
    os.write.over(pinned / "skills" / "fixture-skill" / "SKILL.md", "fixture skill body")
    os.makeDir.all(pinned / "prompts")
    os.makeDir.all(pinned / "docs")
    os.makeDir.all(pinned / "agents")
    os.write.over(pinned / "agents" / "Nebula.md", "fixture agent")
    os.write.over(pinned / "auth.json", "\"fixture-token\"")
    // 读白名单补全（tool-results/uploads/logs/sessions/projects/agents）+ 凭据层 fixture：
    // 正向样本（系统运行数据目录）与负向样本（根层凭据 + agent 私有记忆）齐备。
    os.makeDir.all(pinned / "tool-results" / "tr-001")
    os.write.over(pinned / "tool-results" / "tr-001" / "result.json", """{"ok":true,"needle":"NBX_TR_OK"}""")
    os.makeDir.all(pinned / "uploads" / "u-001")
    os.write.over(pinned / "uploads" / "u-001" / "report.txt", "upload body NBX_UP_OK")
    os.makeDir.all(pinned / "logs")
    os.write.over(pinned / "logs" / "nb.log", "log line NBX_LOG_OK")
    os.makeDir.all(pinned / "sessions" / "s-001")
    os.write.over(pinned / "sessions" / "s-001" / "session.json", """{"id":"s-001","needle":"NBX_SES_OK"}""")
    os.makeDir.all(pinned / "projects" / "proj-a")
    os.write.over(pinned / "projects" / "proj-a" / "project.json", """{"name":"proj-a","needle":"NBX_PRJ_OK"}""")
    os.write.over(pinned / "projects" / "proj-a" / "AGENTS.md", "# proj-a agents NBX_AGENTS_MD_OK")
    os.makeDir.all(pinned / "agents" / "Nebula")
    os.write.over(pinned / "agents" / "Nebula" / "agent.json", """{"name":"Nebula"}""")
    os.write.over(pinned / "agents" / "Nebula" / "system.md", "Nebula system NBX_SYS_OK")
    os.write.over(pinned / "agents" / "Nebula" / "memory.md", "secret memory NBX_MEM_SECRET")
    os.makeDir.all(pinned / "agents" / "Coder")
    os.write.over(pinned / "agents" / "Coder" / "agent.json", """{"name":"Coder"}""")
    os.write.over(pinned / "agents" / "Coder" / "memory.md", "coder memory NBX_CODER_MEM_SECRET")
    // 根层凭据负向样本（vps.env/*.env/*credentials*/stt-config/model-presets/nebflow.json/User.md）
    os.write.over(pinned / "vps.env", "VPS_SECRET=1")
    os.write.over(pinned / "nebflow.json", "{}")
    os.write.over(pinned / "User.md", "# user private")
    os.write.over(pinned / "model-presets.json", "{}")
    os.write.over(pinned / "stt-config.json", "{}")
    os.write.over(pinned / "host-credentials.txt", "creds")
    os.write.over(pinned / "deploy.env", "KEY=1")
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

  test("A.2: writableRoots 派生自 policy——含 root、数据根、/private/tmp、/tmp、java.io.tmpdir，canonical 去重") {
    val tmp = os.Path(Files.createTempDirectory("nb-sbx-policy"))
    val p = policyIn(tmp)
    val roots = SandboxPolicy.writableRoots(p)
    assert(roots.exists(_.toString == p.root.toString), s"root must be writable: $roots")
    assert(roots.contains(os.Path("/private/tmp")), s"/private/tmp must be writable: $roots")
    // 跨平台：darwin 上 /tmp 是符号链（canonicalize→/private/tmp），Linux 上为独立
    // 真实目录（canonicalize 保持 /tmp）——以 canonical 域断言可写，禁硬编码 darwin 路径。
    val canonTmp = os.Path(SandboxPolicy.canonicalize(Paths.get("/tmp")))
    assert(roots.contains(canonTmp), s"/tmp canonical ($canonTmp) must be writable: $roots")
    val tmpdir = SandboxPolicy.canonicalize(Paths.get(sys.props("java.io.tmpdir")))
    assert(roots.map(_.toString).contains(tmpdir.toString), s"java.io.tmpdir must be writable: $roots")
    // 2026-09-05 数据根入可写面（作者 20:24 裁定）：PathUtil.dataRoot 必须在
    // 可写根（beforeEach 已钉 dataRoot——断言即「跟随数据根推导、不硬编码」的
    // 机制证明；nebflowDataRoot 与 root 同源，Nebula 会话 root=dataRoot 去重）
    assert(roots.exists(_.toString == SandboxPolicy.canonicalize(PathUtil.dataRoot.wrapped).toString),
      s"dataRoot must be writable: $roots")
    // 写 ⊆ 读不变量（2026-09-06 读宽批：readableRoots 恒为全盘根，不变量自动
    // 成立——承重断言 = readableRoots 恒为 "/" 且 contains 对写根恒真）
    val readable = SandboxPolicy.readableRoots(p)
    assertEquals(readable, List(os.Path("/")), "读宽：readableRoots 必须恒为全盘根")
    roots.foreach(r => assert(SandboxPolicy.contains(readable.head.wrapped, r.wrapped),
      s"writable must be readable: $r"))
  }

  test("A.2: canonicalize 解析 symlink（/tmp→/private/tmp）且对已存在部分用内核 realpath 语义") {
    // 跨平台：darwin /tmp 符号链 → realpath=/private/tmp；Linux 为真实目录 → realpath=/tmp。
    // 以内核 realpath 为基准断言（禁硬编码 darwin 路径）。
    assertEquals(SandboxPolicy.canonicalize(Paths.get("/tmp")), Paths.get("/tmp").toRealPath())
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

  test("A.2: 读宽（2026-09-06 作者裁定）——readableRoots 恒为全盘根，原系统目录/白名单九子目录/数据根全被吸收") {
    val p = policyIn(os.Path(Files.createTempDirectory("nb-sbx-extras")))
    val readable = SandboxPolicy.readableRoots(p)
    assertEquals(readable, List(os.Path("/")), "读宽：readableRoots 必须恒为全盘根（唯一元素）")
    // canonical 域比较不变：contains 对任意绝对路径恒真——样本覆盖原读面白名单
    // 各代表（系统目录、九子目录、数据根本身），防「白名单退出承重后样本路径
    // 反而读不到」的误回归
    val samples = List(os.Path("/usr"), os.Path("/private/etc")) ++
      List("skills", "prompts", "docs", "tool-results", "uploads", "logs", "sessions", "projects", "agents")
        .map(d => PathUtil.dataRoot / d) :+ PathUtil.dataRoot
    samples.foreach { s =>
      assert(SandboxPolicy.contains(readable.head.wrapped, SandboxPolicy.canonicalize(s.wrapped)),
        s"读宽：$s 必须落在全盘读面内")
    }
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
        // 跨平台：darwin /etc 符号链 → canonical=/private/etc/hosts；Linux → /etc/hosts。
        assert(err.message.contains(SandboxPolicy.canonicalize(Paths.get("/etc/hosts")).toString),
          s"应含 canonical 路径: ${err.message}")
        assert(err.message.contains(s"outside sandbox root"), err.message)
        assert(err.message.contains("Writable roots:"), err.message)
        assert(err.message.contains("Readable roots:"), err.message)
        assert(err.message.contains("report to the dispatcher"), s"应含自纠指引: ${err.message}")
      case Right(_) => fail("/etc/hosts 写必须被拒")
  }

  // ------------------------------------------------------------------
  // §A.8-2：.. 逃逸拒；词法冗余归一放行
  // ------------------------------------------------------------------

  /** 沙箱工作区会话逃生门（见 beforeEach 注释）：宿主/CI = os.home（不在任何
    * 读写面，「项目根/外部目标」语义成立）；注入 NB_SANDBOX_SPEC_DATAROOT 时
    * 落到注入根下（由测试驱动方指定在既非系统读面、也非 policy root/tmp 的
    * 目录，语义等价）。 */
  private def homeLikeRoot(tag: String): os.Path =
    sys.env.get("NB_SANDBOX_SPEC_DATAROOT") match
      case Some(dir) => os.Path(dir) / s".nb-sbx-$tag"
      case None      => os.home / s".nb-sbx-$tag"

  test("A.8-2: <root>/../escape.txt 拒；<root>//sub//new.txt 归一后放行且返回 fresh 路径") {
    // root 用 home 下目录（模拟真实 project root）——tmpdir 本身在 java.io.tmpdir
    // 可写根内，<tmpdir>/../escape 会落回 tmpdir 而合法可写，不能当项目根用。
    val tmp = homeLikeRoot(s"root-${System.nanoTime()}")
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
  // §A.8-3：root 内 symlink 指外 → 写拒读放（2026-09-06 读宽）；root 内深层新文件创建成功
  // ------------------------------------------------------------------

  /** home 下的外部目标（home 不在任何 read/writable root——/private/var 等
    * tempdir 落点反而 readable/writable，不能当「外部」用）。先清残留再建。
    * 逃生门见 homeLikeRoot。 */
  private def outsideDir(tag: String): os.Path =
    val d = homeLikeRoot(s"outside-$tag")
    os.remove.all(d)
    os.makeDir.all(d)
    d

  test("A.8-3: root 内 symlink 指外写拒读放（2026-09-06 读宽）；深层新文件 checkWrite 放行") {
    val tmp = os.Path(Files.createTempDirectory("nb-sbx-sym"))
    val outside = outsideDir("sym")
    val outsideFile = outside / "target.txt"
    os.write.over(outsideFile, "secret")
    val link = tmp / "link.txt"
    os.symlink(link, outsideFile) // os.symlink(链接落点, 指向目标)

    val ctx = ctxIn(tmp)
    // 写拒（canonicalize 解析出 symlink → 越界；写窄零变化）
    FileSandbox.checkWrite(ctx, link.toString) match
      case Left(err) => assert(err.message.startsWith("SANDBOX_DENIED"), err.message)
      case Right(_) => fail(s"指向外部的 symlink 写必须被拒: $link")
    // 读放行（2026-09-06 读宽批翻转：全盘读面下 symlink 指外普通文件可读——
    // canonicalize 解析去向落入全盘根；与上方写拒构成读宽写窄不对称性取证）
    FileSandbox.checkRead(ctx, link.toString) match
      case Right(_) => () // 读宽：指外 symlink 读放行
      case Left(err) => fail(s"读宽后指向外部的 symlink 读应放行: ${err.message}")

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

    // 搜索根翻转（2026-09-06 读宽批）：会话根外的 home 下小目录（原「readableRoots
    // 外必拒」样本）→ 放行且真实命中。Glob 遍历面随读宽放开；agents 子树负向
    // 排除不受读宽影响（READLIST+ 遍历面用例钉死）。逃生门形态见 homeLikeRoot。
    val wide = homeLikeRoot(s"wide-${System.nanoTime()}")
    os.makeDir.all(wide)
    os.write.over(wide / "wide.txt", "wide-ok")
    GlobTool.call(
      JsonObject("pattern" -> "*.txt".asJson, "path" -> wide.toString.asJson),
      ctx
    ).unsafeRunSync() match
      case Right(out) => assert(out.contains("wide.txt"), s"读宽后根外搜索根应放行且命中: $out")
      case Left(err) => fail(s"读宽后根外搜索根应放行: ${err.message}")
    os.remove.all(wide)
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

  test("G.1 回滚: 配置链 forRoot(cfg(enabled=false)) 必须产出 off 策略（config→forRoot 全链）") {
    // [verify-fix] 2026-09-03 独立验证节点：原 forRoot 硬编码 enabled=true，全局
    // flag 只停 Bash probe、文件闸照常激活——隔离实例 E2E 实证回滚失效。本用例
    // 钉住 config→forRoot 全链的回滚语义。
    val tmp = os.Path(Files.createTempDirectory("nb-sbx-flagoff"))
    val policy = SandboxPolicy.forRoot(tmp, SandboxConfig(enabled = false))
    assertEquals(policy.enabled, false, "forRoot(enabled=false) 必须短路为 off")
    val ctx = ToolContext(projectRoot = tmp.toString, sandbox = policy)
    val res = FileSandbox.checkWrite(ctx, "/Users/dev/nb-flagoff-probe.txt")
    assert(res.isRight, s"flag off 时界外写必须放行（旧行为）: ${res.left.map(_.message)}")
    val denied = FileSandbox.checkRead(ctx, s"$tmp/../escape.txt")
    assert(denied.isRight, s"flag off 时相对/越界路径不再有根校验（旧行为按绝对路径判定后放行）: ${denied.left.map(_.message)}")
  }

  // ------------------------------------------------------------------
  // ~/.nebflow 白名单（H-12①）：三子目录可读、根层拒读写、不在可写根
  // ------------------------------------------------------------------

  // ------------------------------------------------------------------
  // 2026-09-05 数据根入可写面（作者 20:24 裁定）：锚点 (a) 正向 + (b) 反向
  // ------------------------------------------------------------------

  test("WFROOT+: 数据根整目录可写——projects/<测试名>/tmpfile 与 User.md 写放行（锚点 a）") {
    val tmp = os.Path(Files.createTempDirectory("nb-sbx-wfroot"))
    val ctx = ctxIn(tmp)
    // (a) 数据根子路径写放行（真实落盘 + 读回）：projects/<测试名>/tmpfile 形态
    // ——项目仓 git commit / docs 归档场景的最小等价物
    val projDir = PathUtil.dataRoot / "projects" / "wfroot-write-test"
    FileSandbox.checkWrite(ctx, (projDir / "tmpfile").toString) match
      case Right(fresh) =>
        Files.createDirectories(fresh.getParent)
        Files.write(fresh, "wfroot-ok".getBytes(StandardCharsets.UTF_8))
        assert(Files.readString(fresh) == "wfroot-ok", "数据根内写必须真实落盘")
      case Left(err) => fail(s"数据根子路径写必须放行: ${err.message}")
    // (a) User.md 同款路径（记忆主文件——残留风险钉死：从此节点可直写）
    assert(FileSandbox.checkWrite(ctx, (PathUtil.dataRoot / "User.md").toString).isRight,
      "User.md 随数据根整目录放行可写（残留风险=纪律约束）")
    // 定义层写放行（plugin/agent 定义层直改场景——beforeEach fixture 既有文件）
    assert(FileSandbox.checkWrite(ctx, (PathUtil.dataRoot / "agents" / "Nebula.md").toString).isRight,
      "定义层文件应随数据根可写")
  }

  test("WFROOT-: 沙箱根外普通系统路径写仍拒——~/Desktop、/etc（锚点 b）") {
    val tmp = os.Path(Files.createTempDirectory("nb-sbx-wfroot-neg"))
    val ctx = ctxIn(tmp)
    FileSandbox.checkWrite(ctx, (os.home / "Desktop" / "nb-wfroot-deny.txt").toString) match
      case Left(err) => assert(err.message.startsWith("SANDBOX_DENIED"), err.message)
      case Right(_) => fail("~/Desktop 写必须仍拒（数据根不等于 home）")
    FileSandbox.checkWrite(ctx, "/etc/hosts") match
      case Left(err) => assert(err.message.startsWith("SANDBOX_DENIED"), err.message)
      case Right(_) => fail("/etc/hosts 写必须仍拒（/private/etc 只在读面，不在写面）")
  }

  // ------------------------------------------------------------------
  // 读宽写窄（2026-09-06 作者裁定，对齐业界标准）：读面全盘放开 + 写面零变化
  // 的不对称性锚点。读样本 = 会话根外随意路径（主仓 docs/assets 同形态）+ 系统
  // 文件；写对照 = 同路径 SANDBOX_DENIED。
  // ------------------------------------------------------------------

  test("READWIDE: 读宽写窄不对称——会话根外读放行（home 随意路径/系统文件），同路径写仍 SANDBOX_DENIED") {
    val tmp = os.Path(Files.createTempDirectory("nb-sbx-readwide"))
    val ctx = ctxIn(tmp)
    // 会话根外普通文件（home 下随意路径——不在写面）：读放行（读宽核心语义）
    val outside = homeLikeRoot(s"rw-${System.nanoTime()}")
    os.makeDir.all(outside)
    os.write.over(outside / "dark.png", "png-bytes")
    assert(FileSandbox.checkRead(ctx, (outside / "dark.png").toString).isRight,
      "读宽：会话根外普通文件必须可读（主仓 docs/Nebflow/assets 同形态）")
    // 同一路径写仍拒（写窄零变化——不对称性钉死）
    FileSandbox.checkWrite(ctx, (outside / "dark.png").toString) match
      case Left(err) => assert(err.message.startsWith("SANDBOX_DENIED"), err.message)
      case Right(_) => fail("写窄：会话根外同路径写必须仍拒")
    // 系统文件：读放行 + 写拒对照（/private/etc 只读，写面不含）
    assert(FileSandbox.checkRead(ctx, "/etc/hosts").isRight, "读宽：/etc/hosts 必须可读")
    FileSandbox.checkWrite(ctx, "/etc/hosts") match
      case Left(err) => assert(err.message.startsWith("SANDBOX_DENIED"), err.message)
      case Right(_) => fail("写窄：/etc/hosts 写必须仍拒")
    os.remove.all(outside)
  }

  // ------------------------------------------------------------------
  // 读白名单补全（2026-09 沙箱批·单件）：系统运行数据目录可读、凭据层拒读不变
  // ------------------------------------------------------------------

  private val newReadDirs = List(
    "tool-results" -> "tr-001/result.json",
    "uploads" -> "u-001/report.txt",
    "logs" -> "nb.log",
    "sessions" -> "s-001/session.json",
    "projects" -> "proj-a/project.json",
    "agents" -> "Nebula/agent.json"
  )

  test("READLIST+: 新六目录逐个可读（checkRead 正向断言，代表文件取自 fixture）") {
    val tmp = os.Path(Files.createTempDirectory("nb-sbx-readlist"))
    val ctx = ctxIn(tmp)
    newReadDirs.foreach { case (dir, file) =>
      // file 可能含子路径段（tr-001/result.json）——逐段拼接（os-lib 拒含 / 的单段）
      val p = file.split('/').foldLeft(PathUtil.dataRoot / dir)(_ / _)
      val res = FileSandbox.checkRead(ctx, p.toString)
      assert(res.isRight, s"~/.nebflow/$dir 必须可读: ${res.left.map(_.message)}")
    }
    // 深层子路径同样可读（projects/proj-a/AGENTS.md）
    val agentsMd = FileSandbox.checkRead(ctx, (PathUtil.dataRoot / "projects" / "proj-a" / "AGENTS.md").toString)
    assert(agentsMd.isRight, s"projects 下 AGENTS.md 必须可读: ${agentsMd.left.map(_.message)}")
  }

  test("READLIST+: ReadTool 端到端读 tool-results 内容成功") {
    val tmp = os.Path(Files.createTempDirectory("nb-sbx-readlist-e2e"))
    val res = ReadTool.call(
      JsonObject("file_path" -> (PathUtil.dataRoot / "tool-results" / "tr-001" / "result.json").toString.asJson),
      ctxIn(tmp)
    ).unsafeRunSync()
    res match
      case Right(content) => assert(content.contains("NBX_TR_OK"), content)
      case Left(err) => fail(s"tool-results ReadTool 必须读通: ${err.message}")
  }

  test("WFROOT±: 根层凭据随整目录放行——读通+写放行（vps.env/auth.json/nebflow.json+通配样本；残留风险=纪律约束）") {
    val tmp = os.Path(Files.createTempDirectory("nb-sbx-cred"))
    val ctx = ctxIn(tmp)
    val credFiles = List(
      "vps.env", "auth.json", "nebflow.json",
      "model-presets.json", "stt-config.json",
      "host-credentials.txt", // *credentials* 通配样本
      "deploy.env" // *.env 通配样本
    )
    // 2026-09-05 数据根入可写面批：不加新 deny、不建新配置面——整目录放行即
    // 终态，根层凭据读写两面均随 dataRoot 放行（原逐个拒读断言被本裁定取代）
    credFiles.foreach { f =>
      assert(FileSandbox.checkRead(ctx, (PathUtil.dataRoot / f).toString).isRight,
        s"~/.nebflow/$f 随整目录放行可读（残留风险=纪律约束）")
    }
    assert(FileSandbox.checkWrite(ctx, (PathUtil.dataRoot / "vps.env").toString).isRight,
      "凭据写面同样随整目录放行（裁定终态；残留风险=纪律约束）")
    // User.md 读通不变（§4.2-B 审计白名单 → 现被 dataRoot 覆盖，双保险）
    assert(FileSandbox.checkRead(ctx, (PathUtil.dataRoot / "User.md").toString).isRight,
      "User.md 必须可读")
  }

  test("READLIST-: agents/ 目录开读但 memory.md 负向规则拒读（Coder 同规保留）+ Reason 行；§4.2-B 唯一例外 = Nebula memory.md 精确放行") {
    val tmp = os.Path(Files.createTempDirectory("nb-sbx-mem"))
    val ctx = ctxIn(tmp)
    // 同目录非记忆文件可读
    assert(FileSandbox.checkRead(ctx, (PathUtil.dataRoot / "agents" / "Nebula" / "system.md").toString).isRight)
    assert(FileSandbox.checkRead(ctx, (PathUtil.dataRoot / "agents" / "Nebula" / "agent.json").toString).isRight)
    assert(FileSandbox.checkRead(ctx, (PathUtil.dataRoot / "agents" / "Coder" / "agent.json").toString).isRight)
    // §4.2-B 审计只读例外（2026-09-05）：Nebula 本人的 memory.md 精确放行
    assert(
      FileSandbox.checkRead(ctx, (PathUtil.dataRoot / "agents" / "Nebula" / "memory.md").toString).isRight,
      "Nebula memory.md = audit-read-only exception, must be readable")
    // 其余 agents/**/memory.md 负向规则不变（team agent 同规拒读 + Reason 行）
    FileSandbox.checkRead(ctx, (PathUtil.dataRoot / "agents" / "Coder" / "memory.md").toString) match
      case Left(err) =>
        assert(err.message.startsWith("SANDBOX_DENIED"), err.message)
        assert(err.message.contains("private-memory deny rule"), s"应含 Reason 解释行: ${err.message}")
      case Right(_) => fail("~/.nebflow/agents/Coder/memory.md 私有记忆必须拒读")
  }

  test("AUDIT-RO: §4.2-B 两记忆文件读通（User.md 根层 + Nebula memory.md）；写随数据根整目录放行（原「写仍拒」被 2026-09-05 写面裁定取代）") {
    val tmp = os.Path(Files.createTempDirectory("nb-sbx-auditro"))
    val ctx = ctxIn(tmp)
    // 读通：User.md（根层精确文件）+ agents/Nebula/memory.md
    assert(FileSandbox.checkRead(ctx, (PathUtil.dataRoot / "User.md").toString).isRight,
      "User.md 必须进审计只读读面")
    assert(FileSandbox.checkRead(ctx, (PathUtil.dataRoot / "agents" / "Nebula" / "memory.md").toString).isRight,
      "Nebula memory.md 必须进审计只读读面")
    // 写随数据根整目录放行（2026-09-05 数据根入可写面批：残留风险=纪律约束，
    // 批次报告钉死；负向规则例外集同源 → Nebula memory.md 写闸同样豁免）
    List("User.md", "agents/Nebula/memory.md").foreach { rel =>
      assert(FileSandbox.checkWrite(ctx, (PathUtil.dataRoot / os.RelPath(rel)).toString).isRight,
        s"~/.nebflow/$rel 随数据根整目录放行可写（残留风险=纪律约束）")
    }
    // 路径契约：auditReadableFiles 与 MemoryStore 权威路径零漂移
    assertEquals(
      SandboxPolicy.auditReadableFiles.map(_.toString),
      List(nebflow.service.MemoryStore.userMemoryPath.toString,
        nebflow.service.MemoryStore.agentMemoryPath("Nebula").toString),
      "audit paths must mirror MemoryStore paths")
  }

  test("AUDIT-RO MUT: 变异验红——负向例外剔除即拒读写（readDeniedWith 空集，读/写双闸同承重）；数据根换钉即拒旧根（nebflowDataRoot 承重）") {
    val tmp = os.Path(Files.createTempDirectory("nb-sbx-auditmut"))
    val ctx = ctxIn(tmp)
    val nebulaMem = (PathUtil.dataRoot / "agents" / "Nebula" / "memory.md").toString
    val nebulaMemCanonical = SandboxPolicy.canonicalize(java.nio.file.Paths.get(nebulaMem))
    // 基线绿（读 + 写——写随数据根整目录放行）
    assert(FileSandbox.checkRead(ctx, nebulaMem).isRight, "基线：Nebula memory.md 应可读")
    assert(FileSandbox.checkWrite(ctx, nebulaMem).isRight, "基线：Nebula memory.md 应可写（数据根写面）")
    // 变异 A（红）：负向规则例外集置空 = 旧规则（一切 agents/**/memory.md 拒）承重
    // ——读拒（既有）且写拒（本批写闸消费同一规则，红线不随写面扩大）
    assert(SandboxPolicy.readDeniedWith(nebulaMemCanonical, Set.empty),
      "变异：例外集为空时 Nebula memory.md 必须重新命中负向规则（读）")
    // 变异 B：数据根换钉（setDataRoot → 第二 pinned 根）——写面跟随新根推导
    // （隔离实例换 HOME 后写不穿旧根，承重面 = 数据根推导）；读面在 2026-09-06
    // 读宽批后全盘化，不随换钉变化（旧根文件仍可读——「读宽写窄」不对称性的
    // 换钉取证形态）。原「旧根退出读面」断言被读宽批取代。
    val secondRoot = pinnedDataRoot match
      case Some(p) => p / os.up / s"nb-sbx-second-${System.nanoTime()}"
      case None    => os.home / s"nb-sbx-second-${System.nanoTime()}"
    val oldRoot = pinnedDataRoot.getOrElse(secondRoot / os.up)
    os.makeDir.all(secondRoot)
    os.write.over(secondRoot / "marker.txt", "second")
    PathUtil.setDataRoot(secondRoot)
    try
      // 读宽：换钉不影响读面（旧根文件仍可读）
      FileSandbox.checkRead(ctx, (oldRoot / "auth.json").toString) match
        case Right(_) => () // 读宽：读面全盘化，不随数据根换钉变化
        case Left(err) => fail(s"读宽后旧根 auth.json 应仍可读（读面不随换钉变化）: ${err.message}")
      FileSandbox.checkWrite(ctx, nebulaMem) match
        case Left(err) => assert(err.message.startsWith("SANDBOX_DENIED"), err.message)
        case Right(_) => fail("换钉后旧根 Nebula memory.md 必须退出写面（例外集随新根推导 + 数据根推导承重）")
      // 新钉根内的文件可写（可写面跟随 dataRoot）
      assert(FileSandbox.checkWrite(ctx, (secondRoot / "marker.txt").toString).isRight,
        "换钉后新根文件必须可写（可写面跟随数据根）")
    finally
      // 还原（绿）：恢复原 pinned 根
      pinnedDataRoot.foreach(PathUtil.setDataRoot)
      os.remove.all(secondRoot)
    assert(FileSandbox.checkRead(ctx, nebulaMem).isRight, "恢复后 Nebula memory.md 应复绿（读）")
    assert(FileSandbox.checkWrite(ctx, nebulaMem).isRight, "恢复后 Nebula memory.md 应复绿（写）")
  }

  test("WFROOT-: agents/**/memory.md 非 Nebula 份写仍拒（锚点 d：红线延续，数据根入写面不被写闸扩大击穿）；Nebula 份随整目录放行") {
    val tmp = os.Path(Files.createTempDirectory("nb-sbx-memw"))
    val ctx = ctxIn(tmp)
    // 非 Nebula 份：写拒 + Reason 行（FileSandbox 写闸消费同一 readDenied 规则
    // ——同一既有红线读/写双闸延续，非本批新增 deny）
    FileSandbox.checkWrite(ctx, (PathUtil.dataRoot / "agents" / "Coder" / "memory.md").toString) match
      case Left(err) =>
        assert(err.message.startsWith("SANDBOX_DENIED"), err.message)
        assert(err.message.contains("private-memory deny rule"), s"应含 Reason 解释行: ${err.message}")
      case Right(_) => fail("team agent 私有记忆必须拒写（数据根入写面不扩大红线）")
    // Nebula 份：负向规则例外集同源豁免 → 落数据根写面放行（残留风险=纪律约束）
    assert(FileSandbox.checkWrite(ctx, (PathUtil.dataRoot / "agents" / "Nebula" / "memory.md").toString).isRight,
      "Nebula memory.md 随数据根整目录放行可写（残留风险=纪律约束）")
    // root 内 symlink 间接路径同样拦截（写闸 canonical+fresh 双查）
    val tmp2 = os.Path(Files.createTempDirectory("nb-sbx-memw-sym"))
    os.symlink(tmp2 / "alias.md", PathUtil.dataRoot / "agents" / "Coder" / "memory.md")
    FileSandbox.checkWrite(ctxIn(tmp2), (tmp2 / "alias.md").toString) match
      case Left(err) => assert(err.message.startsWith("SANDBOX_DENIED"), err.message)
      case Right(_) => fail("symlink 间接写 team agent 私有记忆必须被拒")
  }

  test("SUBSUME: 读宽后 readExtras 白名单整体退出读面承重——剔除全部条目读面不变（readableRoots 恒为 /，字段保留仅为快照锚点）") {
    val tmp = os.Path(Files.createTempDirectory("nb-sbx-mut"))
    val ctx = ctxIn(tmp)
    val trFile = (PathUtil.dataRoot / "tool-results" / "tr-001" / "result.json").toString
    // 基线绿
    assert(FileSandbox.checkRead(ctx, trFile).isRight, "基线：tool-results 应可读")
    // 变异：剔除全部 readExtras——2026-09-06 读宽批后读面由全盘根承载，白名单
    // 条目剔除与否不影响读面（防未来误把条目当承重面；字段保留仅为 forRoot
    // 构造链与变异用例的快照锚点）
    val mutated = ctx.sandbox.copy(readExtras = Nil)
    assert(FileSandbox.checkRead(ctxIn(tmp, mutated), trFile).isRight,
      "剔除全部 readExtras 后仍应可读（读宽：readableRoots 恒为全盘根）")
    // 恢复（绿）
    assert(FileSandbox.checkRead(ctx, trFile).isRight, "恢复后 tool-results 应复绿")
  }

  test("MSG: SANDBOX_DENIED 文案的 Readable roots 动态反映 readableRoots 推导（读宽后=全盘根；负向规则拒读探针不变）") {
    val tmp = os.Path(Files.createTempDirectory("nb-sbx-msg2"))
    // 拒读探针 = 负向规则一票拒绝的 team agent 私有记忆（读宽批后读面唯一拒绝
    // 源；拒绝文案同样带全量 roots）
    val res = FileSandbox.checkRead(ctxIn(tmp), (PathUtil.dataRoot / "agents" / "Coder" / "memory.md").toString)
    res match
      case Left(err) =>
        val rootsSeg = err.message.split("Readable roots: ")(1)
        assert(rootsSeg.contains("/"), s"文案 Readable roots 应为全盘根: ${err.message}")
      case Right(_) => fail("Coder memory.md 必须拒读（该用例验证拒读文案的 roots 动态性）")
  }

  test("READLIST+: Grep/Glob 遍历面——agents 根搜索可跑但扫不出 memory.md；tool-results 正常命中") {
    assume(rgAvailable, "rg 不可用则跳过（Glob/Grep 依赖 ripgrep）")
    val tmp = os.Path(Files.createTempDirectory("nb-sbx-trav"))
    val ctx = ctxIn(tmp)

    // Grep agents/ 子树（content 模式——负向断言看的是命中文本，非文件名）：
    // 私有记忆内容不得命中，system.md 内容命中
    val grep = GrepTool.call(
      JsonObject(
        "pattern" -> "NBX_.*_OK|NBX_.*_SECRET".asJson,
        "path" -> (PathUtil.dataRoot / "agents").toString.asJson,
        "output_mode" -> "content".asJson
      ),
      ctx
    ).unsafeRunSync()
    grep match
      case Right(out) =>
        assert(out.contains("NBX_SYS_OK"), s"应命中 system.md 内容: $out")
        assert(!out.contains("NBX_MEM_SECRET"), s"私有记忆内容不得泄入 Grep: $out")
        assert(!out.contains("NBX_CODER_MEM_SECRET"), s"team agent 记忆内容不得泄入 Grep: $out")
      case Left(err) => fail(s"agents 根 Grep 应成功: ${err.message}")

    // Grep tool-results/：新目录遍历读通（本次实证缺口的正向）
    val grepTr = GrepTool.call(
      JsonObject("pattern" -> "NBX_TR_OK".asJson, "path" -> (PathUtil.dataRoot / "tool-results").toString.asJson),
      ctx
    ).unsafeRunSync()
    grepTr match
      case Right(out) => assert(out.contains("result.json"), s"tool-results Grep 应命中: $out")
      case Left(err) => fail(s"tool-results Grep 必须读通: ${err.message}")

    // Glob agents/ 根：memory.md 不列出
    val glob = GlobTool.call(
      JsonObject("pattern" -> "**/*.md".asJson, "path" -> (PathUtil.dataRoot / "agents").toString.asJson),
      ctx
    ).unsafeRunSync()
    glob match
      case Right(out) =>
        assert(out.contains("system.md"), s"Glob 应列出 system.md: $out")
        assert(!out.contains("memory.md"), s"Glob 不得列出 memory.md: $out")
      case Left(err) => fail(s"agents 根 Glob 应成功: ${err.message}")

    // 用户 include glob 不得压过记忆排除（rg last-match-wins：排除参数必须后置）
    val grepGlob = GrepTool.call(
      JsonObject(
        "pattern" -> "SECRET".asJson,
        "path" -> (PathUtil.dataRoot / "agents").toString.asJson,
        "output_mode" -> "content".asJson,
        "glob" -> "*.md".asJson
      ),
      ctx
    ).unsafeRunSync()
    grepGlob match
      case Right(out) => assert(!out.contains("NBX_MEM_SECRET"), s"用户 glob 不得放行 memory.md: $out")
      case Left(err) => fail(s"带用户 glob 的 Grep 应成功: ${err.message}")
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
    pinnedDataRoot.foreach { p =>
      os.remove.all(p)
      // 逃生门模式：外层注入根也一并清理（保留注入根自身，供下轮复用）
      if sys.env.contains("NB_SANDBOX_SPEC_DATAROOT") then os.remove.all(p / os.up)
    }
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

  // ------------------------------------------------------------------
  // Nebula 根会话沙箱（2026-09-05 作者裁定 13:09：写根=~/.nebflow 数据根，
  // 让 Nebula 直接处理定义层与运维配置）。判定基准 = WS 根会话 ∧ agent==Nebula
  // （SandboxPolicy.isNebulaRootSession：sandboxEnabled ∧ depth==0 ∧ name）；
  // root 推导 = SandboxPolicy.sessionRoot（Nebula → PathUtil.dataRoot，与其余
  // 会话 projectRoot 语义隔离）。
  // ------------------------------------------------------------------

  test("Nebula 根会话判据：sandboxEnabled ∧ depth==0 ∧ name==Nebula 三分量缺一不可") {
    // Nebula WS 根会话（唯一命中形态）
    assert(SandboxPolicy.isNebulaRootSession(sandboxEnabled = true, depth = 0, agentName = "Nebula"))
    // 其余 WS 根会话（standalone 非 Nebula 聊天 / team Manager / flow 入口）不命中
    assert(!SandboxPolicy.isNebulaRootSession(sandboxEnabled = true, depth = 0, agentName = "general"))
    assert(!SandboxPolicy.isNebulaRootSession(sandboxEnabled = true, depth = 0, agentName = "Manager"))
    // NodeDef.agent="Nebula" 声明的节点会话（depth=1）不命中——root 必须留在
    // projectRoot/worktree（§A.6 节点写根语义零回归）
    assert(!SandboxPolicy.isNebulaRootSession(sandboxEnabled = true, depth = 1, agentName = "Nebula"))
    assert(!SandboxPolicy.isNebulaRootSession(sandboxEnabled = true, depth = 1, agentName = "project-dispatcher"))
    // 未启用（feature flag / 未置位）不命中
    assert(!SandboxPolicy.isNebulaRootSession(sandboxEnabled = false, depth = 0, agentName = "Nebula"))
  }

  test("Nebula 会话沙箱 root==PathUtil.dataRoot（数据根）且跟随 setDataRoot 重定向——隔离安全机制证明") {
    // beforeEach 已把 dataRoot 钉到 os.home 下一次性目录（NEBFLOW_HOME 重定向的
    // 等价形态）：本断言即「root 跟随数据根推导、绝不硬编码 os.home」的机制证明
    // ——隔离实例（NEBFLOW_HOME=/tmp/...）下 root 必然落在隔离 HOME，不可能写
    // 真 ~/.nebflow。
    val pinned = PathUtil.dataRoot
    // 前置断言按形态分支（逃生门补全）：宿主/CI 无注入变量时维持原断言逐字节
    // 不变；注入形态下钉点在注入根下（既不在系统读面也不在写面，隔离语义不变）。
    if sys.env.contains("NB_SANDBOX_SPEC_DATAROOT") then
      assert(pinned.toString.startsWith(sys.env("NB_SANDBOX_SPEC_DATAROOT")),
        s"前置：注入形态下 dataRoot 应已被钉到注入根下: $pinned")
    else
      assert(pinned.toString.startsWith(os.home.toString) && pinned.toString.contains(".nb-sbx-dataroot-"),
        s"前置：dataRoot 应已被钉到一次性目录: $pinned")
    // Nebula 根会话 → 数据根
    assertEquals(
      SandboxPolicy.sessionRoot(sandboxEnabled = true, depth = 0, agentName = "Nebula",
        projectRoot = Some((pinned / "projects").toString), fallbackProjectRoot = "/fallback"),
      pinned.toString
    )
    // 节点会话（depth=1，即使 agent 名叫 Nebula）→ projectRoot（零回归）
    assertEquals(
      SandboxPolicy.sessionRoot(sandboxEnabled = true, depth = 1, agentName = "Nebula",
        projectRoot = Some("/ws/a/.nebflow/wt-x"), fallbackProjectRoot = "/fallback"),
      "/ws/a/.nebflow/wt-x"
    )
    // 分发器会话（depth=1）→ projectRoot（零回归）
    assertEquals(
      SandboxPolicy.sessionRoot(sandboxEnabled = true, depth = 1, agentName = "project-dispatcher",
        projectRoot = Some("/ws/a"), fallbackProjectRoot = "/fallback"),
      "/ws/a"
    )
    // 非 Nebula WS 根会话（sandboxEnabled=false 现状）→ fallback 既有语义（零回归）
    assertEquals(
      SandboxPolicy.sessionRoot(sandboxEnabled = false, depth = 0, agentName = "general",
        projectRoot = None, fallbackProjectRoot = "/fallback"),
      "/fallback"
    )
  }

  test("Nebula 会话沙箱：写根内配置文件放行（daemons.json 补丁形态：追加后读回验证）") {
    // 模拟 AgentCore 特判产物：root=dataRoot 的开启态策略
    val policy = policyIn(PathUtil.dataRoot)
    assertEquals(policy.root.toString, PathUtil.dataRoot.toString, "Nebula 策略 root 必须是数据根")
    val ctx = ctxIn(PathUtil.dataRoot, policy)
    val target = PathUtil.dataRoot / "daemons.json"
    os.write.over(target, "{\"keepalive\":{}}\n")
    FileSandbox.checkWrite(ctx, target.toString) match
      case Right(fresh) =>
        // 补丁形态：追加一行 → 读回验证（调用方必须用返回的 fresh 路径执行写）
        val freshOs = os.Path(fresh)
        os.write.append(freshOs, "{\"patched\":true}\n")
        val content = os.read(freshOs)
        assert(content.contains("keepalive") && content.contains("patched"), s"读回验证失败: $content")
      case Left(err) => fail(s"Nebula 写根内配置文件必须放行: ${err.message}")
  }

  test("Nebula 会话沙箱：写根外路径拒（SANDBOX_DENIED，FileSandbox 层）") {
    val policy = policyIn(PathUtil.dataRoot)
    val ctx = ctxIn(PathUtil.dataRoot, policy)
    // 数据根外的用户目录文件（os.home 本身不在 writableRoots：root=dataRoot +
    // tempRoots，home 不含其中）
    FileSandbox.checkWrite(ctx, (os.home / "nb-sbx-outside-should-deny.txt").toString) match
      case Left(err) =>
        assert(err.message.startsWith("SANDBOX_DENIED"), err.message)
        assert(err.message.contains("Writable roots:"), err.message)
      case Right(_) => fail("数据根外写必须被拒")
  }

  test("Nebula 会话沙箱读面：白名单可读 + agents/**/memory.md 负向规则不被 root 面扩大击穿") {
    val policy = policyIn(PathUtil.dataRoot)
    val ctx = ctxIn(PathUtil.dataRoot, policy)
    // 白名单九目录之一（skills fixture）可读
    FileSandbox.checkRead(ctx, (PathUtil.dataRoot / "skills" / "fixture-skill" / "SKILL.md").toString) match
      case Right(_) => ()
      case Left(err) => fail(s"读白名单子目录应可读: ${err.message}")
    // 凭据红线：agents/**/memory.md 负向规则一票优先——root=dataRoot 使 agents/
    // 整体落进 root 读面，但 readDenied 先于 readableRoots 判定，仍拒（红线不被
    // root 面扩大击穿的关键回归断言）
    FileSandbox.checkRead(ctx, (PathUtil.dataRoot / "agents" / "Coder" / "memory.md").toString) match
      case Left(err) => assert(err.message.startsWith("SANDBOX_DENIED"), err.message)
      case Right(_) => fail("agents/**/memory.md 在 Nebula 策略下必须仍拒读")
    // Nebula 自身 memory.md：§4.2-B 审计只读例外（2026-09-05 memory-mech 批）已
    // 落地——负向规则精确豁免该路径，可读。[main 存量红修复] 原断言写于例外落地
    // 前（「审计例外未落地前仍拒读」），与 AUDIT-RO 用例直接矛盾，基线实测红；
    // 本批对齐为可读。数据根入写面后写亦放行（WFROOT- 用例钉死，残留风险=纪律约束）。
    FileSandbox.checkRead(ctx, (PathUtil.dataRoot / "agents" / "Nebula" / "memory.md").toString) match
      case Right(_) => ()
      case Left(err) => fail(s"Nebula memory.md 应循审计例外可读: ${err.message}")
  }

  test("Nebula 会话沙箱对照（2026-09-05 数据根入写面后）：两会话形态根层同进读面；node 会话 root/worktree 写根语义零变化") {
    val policy = policyIn(PathUtil.dataRoot)
    val ctx = ctxIn(PathUtil.dataRoot, policy)
    // Nebula 策略（root=dataRoot）：根层文件在读面
    FileSandbox.checkRead(ctx, (PathUtil.dataRoot / "auth.json").toString) match
      case Right(_) => () // root=dataRoot ⇒ 根层进读面（写⊆读；作者信任边界内）
      case Left(err) => fail(s"Nebula 策略 root=dataRoot 下根层文件在读面（写⊆读）: ${err.message}")
    // 对照：node 会话策略形态（root=worktree 等价物）——数据根入写面后根层凭据
    // 同样进读面（写⊆读不变量的读面后果，本批裁定预期，非回归）。[main 存量红
    // 修复] 原 nodeRoot 直接落 os.home 下——沙箱会话内 os.home 不可写（基线实测
    // Operation not permitted），改走 homeLikeRoot 逃生门（既不在系统读面、也非
    // policy root/tmp 的目录）。
    val nodeRoot = homeLikeRoot(s"node-root-${System.nanoTime()}")
    os.makeDir.all(nodeRoot)
    val nodeCtx = ctxIn(nodeRoot)
    // node 会话：数据根可读 + 可写（本批核心语义）
    assert(FileSandbox.checkRead(nodeCtx, (PathUtil.dataRoot / "auth.json").toString).isRight,
      "node 会话数据根应随整目录放行可读")
    assert(FileSandbox.checkWrite(nodeCtx, (PathUtil.dataRoot / "auth.json").toString).isRight,
      "node 会话数据根应随整目录放行可写")
    // node 会话自身 root 写语义零回归（worktree 写根不受数据根扩充影响）
    assert(FileSandbox.checkWrite(nodeCtx, (nodeRoot / "w.txt").toString).isRight,
      "node 会话自身 root 内写必须照常放行")
    // node 会话自身 root 外、数据根外的写仍拒（worktree 语义不因数据根扩大）
    FileSandbox.checkWrite(nodeCtx, (os.home / "nb-sbx-node-outside-deny.txt").toString) match
      case Left(err) => assert(err.message.startsWith("SANDBOX_DENIED"), err.message)
      case Right(_) => fail("node 会话 root 外（且数据根外）写必须仍拒")
    os.remove.all(nodeRoot)
  }

  test("Nebula 会话沙箱 Seatbelt 真执行（沙箱可用时）：Bash 写数据根内成功、写外被 OS 拒") {
    assume(SandboxBackend.Seatbelt.probe(), "sandbox-exec 不可用则跳过（非 macOS/CI 环境）")
    val policy = policyIn(PathUtil.dataRoot)
    val ctx = ctxIn(PathUtil.dataRoot, policy).copy(sessionId = Some("nb-sbx-nebula-seatbelt"))
    SandboxRuntime.backend = new SandboxBackend.Seatbelt()
    val target = PathUtil.dataRoot / "daemons.json"
    os.write.over(target, "{\"keepalive\":{}}\n")
    // ① 写数据根内（daemons.json 补丁形态）：沙箱内真执行 + 读回验证
    BashTool.call(
      JsonObject("command" -> s"""echo '{"patched":true}' >> ${target.toString}""".asJson),
      ctx
    ).unsafeRunSync() match
      case Right(out) =>
        assert(os.read(target).contains("patched"), s"写根内 Bash 追加应落盘: $out")
      case Left(err) => fail(s"Seatbelt 下写根内 Bash 必须成功: ${err.message}")
    // ② 写数据根外：OS 层拒绝（last-match-wins：deny file-write* 无 allow 覆盖）
    val outside = os.home / "nb-sbx-seatbelt-outside.txt"
    os.remove.all(outside) // 先清残留，断言写被拒后文件不存在
    BashTool.call(
      JsonObject("command" -> s"echo x > $outside".asJson),
      ctx
    ).unsafeRunSync() match
      case Left(_) => () // ShellSession 报执行失败即取证（Operation not permitted 形态）
      case Right(out) =>
        if !os.exists(outside) then () // 输出形态各异，以文件未落盘为准
        else fail(s"写根外 Bash 必须 OS 拒绝（文件不应落盘）: $out")
    assert(!os.exists(outside), "写根外文件不得落盘")
  }

  // ------------------------------------------------------------------
  // worktree 节点沙箱根继承项目工作区（2026-09-05 21:05 作者裁定：worktree 的
  // 沙箱限制继承项目沙箱，不再收窄到 worktree 目录自身）。
  // 信号链 = NodeEngine spawn 点显式 sandboxRoot=工作区根 → SpawnParams →
  // AgentActor → SessionContext.sandboxRoot → AgentCore → sessionRoot 显式优先；
  // 禁止按路径形态硬猜 workspace 布局。非目标零回归面：分发器（None→projectRoot
  // 旧行为）、Nebula 根会话特判（优先级最高）、enabled=false 回滚（AgentCore
  // 短路 + G.1 既有用例）。
  // ------------------------------------------------------------------

  test("WT-INHERIT①: sessionRoot 显式 sandboxRoot 优先——worktree 节点 root==项目工作区根") {
    // 断言①：worktree 节点（NodeEngine 传 sandboxRoot=工作区根、projectRoot=
    // worktree 路径）→ root = 工作区根（不再收窄）
    assertEquals(
      SandboxPolicy.sessionRoot(sandboxEnabled = true, depth = 1, agentName = "swift-dev",
        projectRoot = Some("/ws/proj/.nebflow/wt-fix"), fallbackProjectRoot = "/fallback",
        sandboxRoot = Some("/ws/proj")),
      "/ws/proj"
    )
    // 显式信号为空串 = 缺省（fail-safe：不把空串当根，回落 projectRoot 既有链）
    assertEquals(
      SandboxPolicy.sessionRoot(sandboxEnabled = true, depth = 1, agentName = "swift-dev",
        projectRoot = Some("/ws/proj/.nebflow/wt-fix"), fallbackProjectRoot = "/fallback",
        sandboxRoot = Some("")),
      "/ws/proj/.nebflow/wt-fix"
    )
    // Nebula 根会话特判优先于 sandboxRoot（depth==0 不被节点信号误覆盖，零回归）
    assertEquals(
      SandboxPolicy.sessionRoot(sandboxEnabled = true, depth = 0, agentName = "Nebula",
        projectRoot = Some("/ws/proj/.nebflow/wt-fix"), fallbackProjectRoot = "/fallback",
        sandboxRoot = Some("/ws/proj")),
      PathUtil.dataRoot.toString
    )
  }

  test("WT-INHERIT④: 非 worktree 会话（sandboxRoot=None→projectRoot）与 enabled=false 回滚行为不变") {
    // ④a 分发器/未接线节点：sandboxRoot=None → projectRoot 旧行为逐字节不变
    assertEquals(
      SandboxPolicy.sessionRoot(sandboxEnabled = true, depth = 1, agentName = "project-dispatcher",
        projectRoot = Some("/ws/proj"), fallbackProjectRoot = "/fallback", sandboxRoot = None),
      "/ws/proj"
    )
    assertEquals(
      SandboxPolicy.sessionRoot(sandboxEnabled = true, depth = 1, agentName = "dev",
        projectRoot = None, fallbackProjectRoot = "/fallback", sandboxRoot = None),
      "/fallback"
    )
    // ④b enabled=false 回滚：sandboxEnabled=false 不命中 Nebula 判据（零变化），
    // 且 AgentCore 侧 if state.sandboxEnabled 短路根本不走 sessionRoot——策略为
    // off（G.1 既有用例 forRoot(enabled=false)→off 已覆盖，sbt test 全绿即回归证明）
    assertEquals(
      SandboxPolicy.sessionRoot(sandboxEnabled = false, depth = 0, agentName = "Nebula",
        projectRoot = None, fallbackProjectRoot = "/fallback", sandboxRoot = Some("/ws/proj")),
      "/ws/proj"
    )
  }

  test("WT-INHERIT: SessionContext 信号链透传（NodeEngine spawn 点同构构造）") {
    // builder（AgentState.apply）→ SessionContext 新字段逐级透传不断链（编译器
    // 之外最轻的运行时锚点：字段默认 None 保全部既有构造点兼容）
    val st = AgentState(
      sandboxEnabled = true,
      projectRoot = Some("/ws/proj/.nebflow/wt-fix"),
      sandboxRoot = Some("/ws/proj")
    )
    assertEquals(st.session.sandboxRoot, Some("/ws/proj"))
    assertEquals(st.session.sandboxEnabled, true)
    assertEquals(st.session.projectRoot, Some("/ws/proj/.nebflow/wt-fix"))
    // 缺省构造 = None（旧行为，全部既有 spawn 点零变化）
    assertEquals(AgentState(sandboxEnabled = true).session.sandboxRoot, None)
  }

  test("WT-INHERIT②a: worktree 内 git commit 真实走通（真仓库真执行）+ 新旧根 FileSandbox 差分") {
    // 工作区必须落在一切既有可写根之外（os.home 根层）：若落 tmpdir，tempRoots
    // 本就在写面内，旧语义（root=worktree）下主仓 .git 也被放行，差分失效。
    // 本用例不带 Seatbelt assume：嵌套沙箱会话（在沙箱 Bash 里跑的 sbt test JVM
    // ——macOS 禁嵌套 sandbox_apply）probe 必败，此时 JVM 层 FileSandbox 与
    // Seatbelt 同源（writableRoots 唯一推导）承担差分取证；OS 强制层由 ②b 在
    // 宿主/CI（非嵌套）环境补齐。
    val ws = homeLikeRoot(s"ws-${System.nanoTime()}")
    val main = ws / "repo"
    val wt = main / ".nebflow" / "wt-fix" // 对齐真实布局 <workspace>/.nebflow/<name>
    os.makeDir.all(main)
    // setup（真 git 主仓 + 首提交 + worktree）
    def git(args: String*): Unit =
      os.proc("git", args).call(cwd = main, stdout = os.Pipe, stderr = os.Pipe, check = true)
    git("init", "-q")
    git("config", "user.email", "nb-sbx@test")
    git("config", "user.name", "nb-sbx")
    os.write.over(main / "README.md", "base\n")
    git("add", "-A")
    git("commit", "-q", "-m", "init")
    os.makeDir.all(main / ".nebflow")
    git("worktree", "add", "-q", "-b", "nb-sbx-wt", wt.toString)
    assert(os.exists(wt / ".git"), "前置：worktree 未建好")
    val indexInMainGit = main / ".git" / "worktrees" / "wt-fix" / "index"
    try
      // 新语义策略（root=工作区根，sessionRoot 继承链产物）：主仓 worktree 元
      // 数据路径在写面内（JVM 闸层）
      val newPolicy = policyIn(main)
      assertEquals(newPolicy.root.toString, os.Path(SandboxPolicy.canonicalize(main.wrapped)).toString,
        "前置：继承策略 root==工作区根（canonical）")
      FileSandbox.checkWrite(ToolContext(projectRoot = wt.toString, sandbox = newPolicy), indexInMainGit.toString) match
        case Right(_) => () // .git/worktrees/<name>/index 可写（git commit 的落盘点）
        case Left(err) => fail(s"继承根下主仓 worktree 元数据必须可写: ${err.message}")
      // 旧语义策略（root=worktree 自身）：同一路径 SANDBOX_DENIED（缺陷根因形态
      // 的差分取证——index.lock 正是旧语义 EPERM 的第一张倒下的牌）
      val oldPolicy = policyIn(wt)
      FileSandbox.checkWrite(ToolContext(projectRoot = wt.toString, sandbox = oldPolicy), indexInMainGit.toString) match
        case Left(err) => assert(err.message.startsWith("SANDBOX_DENIED"), err.message)
        case Right(_) => fail("旧语义差分失效：root=worktree 下主仓 .git 元数据不应可写")
      // 真实执行（非 mock）：BashTool 真跑 git commit——off 策略=旧行为直执行
      //（本会话嵌套沙箱限制下 OS 层由 ②b 补强制；commit 落盘真实性在此取证）
      val offCtx = ToolContext(projectRoot = wt.toString, sandbox = SandboxPolicy.off)
        .copy(sessionId = Some("nb-sbx-wt-real"))
      val cmd =
        s"""echo wt-inherit > "${wt / "file.txt"}" && git -C "${wt}" add -A && git -C "${wt}" commit -q -m wt-inherit-root"""
      BashTool.call(JsonObject("command" -> cmd.asJson), offCtx).unsafeRunSync() match
        case Right(_) => ()
        case Left(err) => fail(s"worktree git commit 必须真实走通: ${err.message}")
      val log = os.proc("git", "-C", main, "log", "--oneline", "-1", "nb-sbx-wt").call(cwd = main, stdout = os.Pipe).out.text()
      assert(log.contains("wt-inherit-root"), s"主仓 nb-sbx-wt ref 必须收到 commit: $log")
      assert(os.exists(indexInMainGit),
        s"主仓 worktree 元数据 index 必须真实写入: $indexInMainGit")
    finally os.remove.all(ws)
  }

  test("WT-INHERIT②b: Seatbelt 真 OS 沙箱（非嵌套环境）：root=工作区根 commit 走通、旧语义 OS 拒") {
    assume(SandboxBackend.Seatbelt.probe(),
      "sandbox-exec 不可用/嵌套沙箱会话（sandbox_apply 被外层拒）则跳过——OS 强制层由宿主/CI 全量补齐")
    val ws = homeLikeRoot(s"ws2-${System.nanoTime()}")
    val main = ws / "repo"
    val wt = main / ".nebflow" / "wt-fix"
    os.makeDir.all(main)
    def git(args: String*): Unit =
      os.proc("git", args).call(cwd = main, stdout = os.Pipe, stderr = os.Pipe, check = true)
    git("init", "-q")
    git("config", "user.email", "nb-sbx@test")
    git("config", "user.name", "nb-sbx")
    os.write.over(main / "README.md", "base\n")
    git("add", "-A")
    git("commit", "-q", "-m", "init")
    os.makeDir.all(main / ".nebflow")
    git("worktree", "add", "-q", "-b", "nb-sbx-wt", wt.toString)
    val indexInMainGit = main / ".git" / "worktrees" / "wt-fix"
    val wtStr = wt.toString
    val fileTarget = (wt / "file.txt").toString
    try
      SandboxRuntime.backend = new SandboxBackend.Seatbelt()
      // 新语义：root=工作区根——worktree 内 git commit 真实执行（OS 层写放行）
      val policy = policyIn(main)
      val ctx = ToolContext(projectRoot = wt.toString, sandbox = policy)
        .copy(sessionId = Some("nb-sbx-wt-inherit"))
      val cmd =
        s"""echo wt-inherit > "$fileTarget" && git -C "$wtStr" add -A && git -C "$wtStr" commit -q -m wt-inherit-root"""
      BashTool.call(JsonObject("command" -> cmd.asJson), ctx).unsafeRunSync() match
        case Right(_) => ()
        case Left(err) => fail(s"继承根下 worktree git commit 必须真实走通: ${err.message}")
      val log = os.proc("git", "-C", main, "log", "--oneline", "-1", "nb-sbx-wt").call(cwd = main, stdout = os.Pipe).out.text()
      assert(log.contains("wt-inherit-root"), s"主仓 nb-sbx-wt ref 必须收到 commit: $log")
      assert(os.exists(indexInMainGit / "index"),
        s"主仓 worktree 元数据 index 必须真实写入: $indexInMainGit")
      // 对照（旧语义 root=worktree）：主仓 .git 在根外 → OS 层拒（同布局同命令，
      // 差分即修复必要性证明）
      val oldPolicy = policyIn(wt)
      val oldCtx = ToolContext(projectRoot = wt.toString, sandbox = oldPolicy)
        .copy(sessionId = Some("nb-sbx-wt-oldroot"))
      val cmd2 = s"""echo again > "$fileTarget" && git -C "$wtStr" add -A"""
      BashTool.call(JsonObject("command" -> cmd2.asJson), oldCtx).unsafeRunSync() match
        case Left(_) => () // OS 拒（Operation not permitted 形态）= 旧语义差分取证
        case Right(out) => fail(s"旧语义对照失效：root=worktree 下 git add 不应成功（$out）——差分不成立")
      val log2 = os.proc("git", "-C", main, "log", "--oneline", "-1", "nb-sbx-wt").call(cwd = main, stdout = os.Pipe).out.text()
      assert(!log2.linesIterator.exists(_.contains("again")), s"旧语义下不得产生新提交: $log2")
    finally os.remove.all(ws)
  }

  test("WT-INHERIT③: 继承根=工作区后项目外写仍拒（FileSandbox SANDBOX_DENIED + Seatbelt OS 层）") {
    val ws = homeLikeRoot(s"ws3-${System.nanoTime()}")
    os.makeDir.all(ws)
    val outside = outsideDir("wt3") // 工作区外（不在 workspace 子树、不在 tempRoots）
    try
      val policy = policyIn(ws)
      val ctx = ctxIn(ws, policy)
      // JVM 层：workspace 外目标 SANDBOX_DENIED
      FileSandbox.checkWrite(ctx, (outside / "escape.txt").toString) match
        case Left(err) =>
          assert(err.message.startsWith("SANDBOX_DENIED"), err.message)
          assert(err.message.contains("Writable roots:"), err.message)
        case Right(_) => fail("继承根下项目外写必须仍拒（SANDBOX_DENIED）")
      // 继承收益对照：workspace 内（含 .nebflow/worktrees 布局）深层新文件放行
      FileSandbox.checkWrite(ctx, (ws / ".nebflow" / "wt-fix" / "deep" / "new.txt").toString) match
        case Right(_) => ()
        case Left(err) => fail(s"workspace 内（.git/worktrees 布局同域）必须放行: ${err.message}")
      // OS 层：Seatbelt 下 workspace 外 bash 写被拒、文件不落盘
      if SandboxBackend.Seatbelt.probe() then
        SandboxRuntime.backend = new SandboxBackend.Seatbelt()
        val osCtx = ctx.copy(sessionId = Some("nb-sbx-wt3-os"))
        val osEscape = (outside / "os-escape.txt").toString
        BashTool.call(
          JsonObject("command" -> s"""echo x > "$osEscape"""".asJson), osCtx
        ).unsafeRunSync() match
          case Left(_) => () // OS 拒
          case Right(_) => assert(!os.exists(outside / "os-escape.txt"), "OS 层必须拒绝 workspace 外写")
        assert(!os.exists(outside / "os-escape.txt"), "workspace 外文件不得落盘")
      else ()
    finally os.remove.all(ws)
  }

end SandboxSpec
