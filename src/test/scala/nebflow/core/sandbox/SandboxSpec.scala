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

  test("A.2: readExtras 含系统目录与 ~/.nebflow 白名单九子目录，不含根层凭据") {
    val p = policyIn(os.Path(Files.createTempDirectory("nb-sbx-extras")))
    val extras = SandboxPolicy.readableRoots(p)
    assert(extras.contains(os.Path("/usr")))
    assert(extras.contains(os.Path("/private/etc")))
    // 九个白名单子目录（H-12① 三目录 + 读白名单补全六目录）
    val expected = List("skills", "prompts", "docs", "tool-results", "uploads", "logs", "sessions", "projects", "agents")
    expected.foreach { d =>
      val dir = PathUtil.dataRoot / d
      assert(extras.exists(_.toString == SandboxPolicy.canonicalize(dir.wrapped).toString),
        s"~/.nebflow/$d must be readable: $extras")
    }
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
  // §A.8-3：root 内 symlink 指外 → 写拒读拒；root 内深层新文件创建成功
  // ------------------------------------------------------------------

  /** home 下的外部目标（home 不在任何 read/writable root——/private/var 等
    * tempdir 落点反而 readable/writable，不能当「外部」用）。先清残留再建。
    * 逃生门见 homeLikeRoot。 */
  private def outsideDir(tag: String): os.Path =
    val d = homeLikeRoot(s"outside-$tag")
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
    // 注：不能用 tempdir 当「外部」——/private/var 在读面内，语义上可读；
    // 也不能再用 dataRoot/agents（读白名单补全后已可读）。
    val deny = GlobTool.call(
      JsonObject("pattern" -> "*.txt".asJson, "path" -> PathUtil.dataRoot.toString.asJson),
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

  test("READLIST-: 根层凭据逐个仍 SANDBOX_DENIED（vps.env/auth.json/nebflow.json + 通配样本；User.md 已入 §4.2-B 审计白名单故移出本列）") {
    val tmp = os.Path(Files.createTempDirectory("nb-sbx-cred"))
    val ctx = ctxIn(tmp)
    val denied = List(
      "vps.env", "auth.json", "nebflow.json",
      "model-presets.json", "stt-config.json",
      "host-credentials.txt", // *credentials* 通配样本
      "deploy.env" // *.env 通配样本
    )
    denied.foreach { f =>
      FileSandbox.checkRead(ctx, (PathUtil.dataRoot / f).toString) match
        case Left(err) => assert(err.message.startsWith("SANDBOX_DENIED"), err.message)
        case Right(_) => fail(s"~/.nebflow/$f 凭据层必须拒读")
    }
    // §4.2-B：User.md 不再拒读（ AUDIT-RO 用例专测），此处仅防回归式提醒
    assert(FileSandbox.checkRead(ctx, (PathUtil.dataRoot / "User.md").toString).isRight,
      "User.md = 审计只读白名单，不得回落为拒读")
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

  test("AUDIT-RO: §4.2-B 两记忆文件双向——读通（User.md 根层 + Nebula memory.md），写仍拒") {
    val tmp = os.Path(Files.createTempDirectory("nb-sbx-auditro"))
    val ctx = ctxIn(tmp)
    // 读通：User.md（根层精确文件）+ agents/Nebula/memory.md
    assert(FileSandbox.checkRead(ctx, (PathUtil.dataRoot / "User.md").toString).isRight,
      "User.md 必须进审计只读读面")
    assert(FileSandbox.checkRead(ctx, (PathUtil.dataRoot / "agents" / "Nebula" / "memory.md").toString).isRight,
      "Nebula memory.md 必须进审计只读读面")
    // 根层其余凭据仍拒（例外是文件级精确匹配，不是根层放行）
    FileSandbox.checkRead(ctx, (PathUtil.dataRoot / "auth.json").toString) match
      case Left(err) => assert(err.message.startsWith("SANDBOX_DENIED"), err.message)
      case Right(_)  => fail("auth.json 仍必须拒读——例外仅覆盖两记忆文件")
    // 写仍拒（只读例外：readableRoots 扩，writableRoots 零变化）
    List("User.md", "agents/Nebula/memory.md").foreach { rel =>
      FileSandbox.checkWrite(ctx, (PathUtil.dataRoot / os.RelPath(rel)).toString) match
        case Left(err) => assert(err.message.startsWith("SANDBOX_DENIED"), err.message)
        case Right(_)  => fail(s"~/.nebflow/$rel 写面必须仍然拒绝（审计例外严格只读）")
    }
    // 路径契约：auditReadableFiles 与 MemoryStore 权威路径零漂移
    assertEquals(
      SandboxPolicy.auditReadableFiles.map(_.toString),
      List(nebflow.service.MemoryStore.userMemoryPath.toString,
        nebflow.service.MemoryStore.agentMemoryPath("Nebula").toString),
      "audit paths must mirror MemoryStore paths")
  }

  test("AUDIT-RO MUT: 变异验红双向——负向例外剔除即拒（readDeniedWith 空集）；读白名单条目剔除即拒（readExtras copy）") {
    val tmp = os.Path(Files.createTempDirectory("nb-sbx-auditmut"))
    val ctx = ctxIn(tmp)
    val nebulaMem = (PathUtil.dataRoot / "agents" / "Nebula" / "memory.md").toString
    val nebulaMemCanonical = SandboxPolicy.canonicalize(java.nio.file.Paths.get(nebulaMem))
    // 基线绿
    assert(FileSandbox.checkRead(ctx, nebulaMem).isRight, "基线：Nebula memory.md 应可读")
    // 变异 A（红）：负向规则例外集置空 = 旧规则（一切 agents/**/memory.md 拒）承重
    assert(SandboxPolicy.readDeniedWith(nebulaMemCanonical, Set.empty),
      "变异：例外集为空时 Nebula memory.md 必须重新命中负向规则")
    // 变异 B（红）：readExtras 剔除 auditReadableFiles 条目 → User.md 拒读
    val userMdCanonical = SandboxPolicy.canonicalize((PathUtil.dataRoot / "User.md").wrapped)
    val stripped = ctx.sandbox.copy(readExtras = ctx.sandbox.readExtras.filterNot(p =>
      p.wrapped == userMdCanonical || p.wrapped == nebulaMemCanonical))
    FileSandbox.checkRead(ctxIn(tmp, stripped), (PathUtil.dataRoot / "User.md").toString) match
      case Left(err) => assert(err.message.startsWith("SANDBOX_DENIED"), err.message)
      case Right(_)  => fail("变异后 User.md 必须变红（审计白名单条目承重）")
    // 恢复（绿）
    assert(FileSandbox.checkRead(ctx, nebulaMem).isRight, "恢复后 Nebula memory.md 应复绿")
    assert(FileSandbox.checkRead(ctx, (PathUtil.dataRoot / "User.md").toString).isRight, "恢复后 User.md 应复绿")
  }

  test("READLIST-: memory.md 不进可写根（写闸不受白名单影响）") {
    val tmp = os.Path(Files.createTempDirectory("nb-sbx-memw"))
    val ctx = ctxIn(tmp)
    FileSandbox.checkWrite(ctx, (PathUtil.dataRoot / "agents" / "Nebula" / "memory.md").toString) match
      case Left(err) => assert(err.message.startsWith("SANDBOX_DENIED"), err.message)
      case Right(_) => fail("agent 私有记忆必须拒写")
  }

  test("MUT: 变异验红——readExtras 剔除 tool-results 条目即拒读，恢复条目复绿") {
    val tmp = os.Path(Files.createTempDirectory("nb-sbx-mut"))
    val ctx = ctxIn(tmp)
    val trFile = (PathUtil.dataRoot / "tool-results" / "tr-001" / "result.json").toString
    // 基线绿
    assert(FileSandbox.checkRead(ctx, trFile).isRight, "基线：tool-results 应可读")
    // 变异（红）：等价于源码删除该白名单条目——从派生源 readExtras 剔除后
    // readableRoots 不再含 tool-results，拒读
    val trCanonical = os.Path(SandboxPolicy.canonicalize((PathUtil.dataRoot / "tool-results").wrapped))
    val mutated = ctx.sandbox.copy(readExtras = ctx.sandbox.readExtras.filterNot(_.toString == trCanonical.toString))
    FileSandbox.checkRead(ctxIn(tmp, mutated), trFile) match
      case Left(err) => assert(err.message.startsWith("SANDBOX_DENIED"), err.message)
      case Right(_) => fail("变异后 tool-results 必须变红（白名单条目承重）")
    // 恢复（绿）：policy 恢复原样 → 复绿
    assert(FileSandbox.checkRead(ctx, trFile).isRight, "恢复后 tool-results 应复绿")
  }

  test("MSG: SANDBOX_DENIED 文案的 Readable roots 动态反映新白名单（无硬编码清单）") {
    val tmp = os.Path(Files.createTempDirectory("nb-sbx-msg2"))
    val res = FileSandbox.checkRead(ctxIn(tmp), (PathUtil.dataRoot / "vps.env").toString)
    res match
      case Left(err) =>
        val rootsSeg = err.message.split("Readable roots: ")(1)
        List("tool-results", "uploads", "logs", "sessions", "projects", "agents", "skills").foreach { d =>
          assert(rootsSeg.contains(d), s"文案 Readable roots 应含 $d: ${err.message}")
        }
      case Right(_) => fail("vps.env 必须拒读（该用例验证拒读文案的 roots 动态性）")
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
    // Nebula 自身 memory.md 同规（memory-mech 批的审计只读例外落地前，负向规则
    // 原样；衔接点见报告）
    FileSandbox.checkRead(ctx, (PathUtil.dataRoot / "agents" / "Nebula" / "memory.md").toString) match
      case Left(_) => ()
      case Right(_) => fail("agents/Nebula/memory.md 在 Nebula 策略下必须仍拒读（审计例外未落地前）")
  }

  test("Nebula 会话沙箱已知边界（如实钉死）：root=dataRoot 使数据根根层文件进读面（写⊆读不变量）") {
    // root=dataRoot ⇒ readableRoots 含 dataRoot 本身 ⇒ 根层凭据（auth.json 等）
    // 在 Nebula 会话读面内。这是「写根=~/.nebflow 全域」裁定的直接机制后果
    // （nebflow.json/daemons.json 补丁场景必须能碰根层；可写必可读）。既有读
    // 白名单与负向规则机制零收窄零删除——本断言如实钉死 Nebula 会话的边界形态，
    // 节点/分发器会话（root=projectRoot）不受影响（下一条对照断言）。
    val policy = policyIn(PathUtil.dataRoot)
    val ctx = ctxIn(PathUtil.dataRoot, policy)
    FileSandbox.checkRead(ctx, (PathUtil.dataRoot / "auth.json").toString) match
      case Right(_) => () // 已知边界：Nebula 会话根层凭据可读（作者信任边界内）
      case Left(err) => fail(s"Nebula 策略 root=dataRoot 下根层文件在读面（写⊆读）: ${err.message}")
    // 对照：node 会话策略形态（root=worktree，数据根仅白名单子目录可读）下
    // auth.json 仍拒读——既有行为零回归
    val nodeRoot = os.home / s".nb-sbx-node-root-${System.nanoTime()}"
    os.makeDir.all(nodeRoot)
    val nodePolicy = policyIn(nodeRoot)
    val nodeCtx = ctxIn(nodeRoot, nodePolicy)
    FileSandbox.checkRead(nodeCtx, (PathUtil.dataRoot / "auth.json").toString) match
      case Left(err) => assert(err.message.startsWith("SANDBOX_DENIED"), err.message)
      case Right(_) => fail("node 会话策略下数据根根层凭据必须仍拒读")
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

end SandboxSpec
