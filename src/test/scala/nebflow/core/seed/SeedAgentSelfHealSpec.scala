package nebflow.core.seed

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.PathUtil

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/**
 * agents 面缺失自愈 spec（2026-09-13 缺失自愈批 / 方案 C；govmemory 批 2026-09-25
 * 随 memory-consolidator 退役改写夹具：默认集 = project-dispatcher / general / Nebula，
 * 消费链启动校验退役，改为 manifest 组成断言）。
 *
 * 与 plugins 面 `SeedPluginReconcileSpec` 同构（同一类缺口、同一处修复形态）：
 *  ① 既有 home + 默认集 agent 缺失 ⇒ 从种子整目录自愈补装（三个默认集全部就位，
 *     general 逐字节 == 种子树）
 *  ② 幂等：第二次 ensure 对该目录零动作（内容与 mtime 双证）
 *  ③ 自愈面 = manifest 默认集（不向种子树全集扩张；非默认集无 agent 面）
 *  ④ 已存在目录零覆盖：用户改过的运行时不进自愈分支，内容逐字节保留
 *     （差集非空 ⇒ REFUSING，零写盘）
 *  ⑤ manifest 组成：含 agents:Nebula；不含已退役的 memory agent
 *     （消费链校验 verifyMemoryConsumptionChain 已随其唯一守护对象退役）
 *
 * kernelgen-ext batch (2026-09-26, author directive "kernel into the seed tree"):
 * the manifest gains a fourth agent item `agents:kernel` as a PROMPT-ONLY seed
 * (system.md, no seed agent.json). The self-heal anchor is agent.json, so kernel
 * is NOT directory-self-healed; its availability in an existing home is carried by
 * the spawn-time fail-safe def (DelegateTool.resolveKernelDef: runtime mirror ->
 * classpath seed -> embedded default). The composition assertions below pin both
 * the presence of the kernel item and the absence of a seed agent.json.
 *
 * classpath 资源（src/main/resources/seed/）在 sbt test classpath 上，种子读取走真实链路。
 */
class SeedAgentSelfHealSpec extends FunSuite:

  private var prevRoot: os.Path = os.Path("/tmp")
  private var home: os.Path = os.Path("/tmp")

  override def beforeAll(): Unit =
    prevRoot = PathUtil.dataRoot
    home = os.Path(Files.createTempDirectory("nb-seed-agent-heal"))
    PathUtil.setDataRoot(home)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(prevRoot)
    os.remove.all(home)

  private def ensure(): Unit = SeedService.ensureSeeded().unsafeRunSync()
  private def rm(p: os.Path): Unit = if os.exists(p) then os.remove.all(p)

  /** 种子 agent 目录（test classpath 上的实件）。 */
  private def seedDirOf(name: String): os.Path =
    val url = getClass.getClassLoader.getResource(s"seed/agents/$name/agent.json")
    os.Path(java.nio.file.Paths.get(url.toURI)) / os.up

  private def treeAsText(d: os.Path): Map[String, String] =
    os.walk(d).filter(os.isFile).map(p =>
      p.relativeTo(d).toString -> new String(os.read.bytes(p), UTF_8)).toMap

  private def manifestAgentNames: List[String] =
    val in = getClass.getClassLoader.getResourceAsStream("seed/manifest.json")
    try
      io.circe.parser.parse(new String(in.readAllBytes(), UTF_8)).toOption.get
        .hcursor.downField("items").as[List[String]].toOption.get
        .collect { case id if id.startsWith("agents:") => id.stripPrefix("agents:") }
    finally in.close()

  private def manifestVersion: String =
    val in = getClass.getClassLoader.getResourceAsStream("seed/manifest.json")
    try
      io.circe.parser.parse(new String(in.readAllBytes(), UTF_8)).toOption.get
        .hcursor.downField("seedVersion").as[String].toOption.get
    finally in.close()

  /** 既有 home 形态：有项目（守卫命中，不完整播种）+ marker 同版本（marker 分支 no-op）。 */
  private def existingHome(): Unit =
    rm(home / "agents"); rm(home / "projects"); rm(home / "plugins"); rm(home / ".seed-state.json")
    val myproj = home / "projects" / "myproj"
    os.makeDir.all(myproj)
    os.write.over(myproj / "project.json", """{"name":"myproj"}""")
    os.write.over(home / ".seed-state.json", s"""{"version":"$manifestVersion","seededAt":1,"items":[]}""")

  // ── ① 缺失 ⇒ 自愈补装 ├────────────────────────────────────
  test("existing home + default-set agents missing => self-healed from seed (double-file default set in place, general byte-identical to the seed tree; kernel is prompt-only and is NOT healed)"):
    existingHome()
    // 前置：不预种任何 agent（agents/ 整面缺席 = 本条的自愈触发形态）
    assert(!os.exists(home / "agents"), "前置：agents 面整体缺席")

    ensure()

    // Full-deck default-set agents land with BOTH files (agent.json + system.md).
    // kernelgen-ext 2026-09-26: kernel is excluded from this loop on purpose — it is
    // a prompt-only seed item with NO seed agent.json, and agent.json is the
    // self-heal anchor (installAgentFromSeed lists resources by that anchor), so the
    // reconcile pass writes NOTHING for kernel (loud WARN, zero files): pinned by
    // the negative right below.
    for name <- manifestAgentNames.filter(_ != "kernel") do
      assert(os.exists(home / "agents" / name / "agent.json"), s"默认集 agent '$name' 就位")
      assert(os.exists(home / "agents" / name / "system.md"), s"默认集 agent '$name' 提示词就位")
    assert(manifestAgentNames.contains("kernel"),
      "kernel is a manifest agent item (kernelgen-ext 2026-09-26: prompt-only seed)")
    assert(!os.exists(home / "agents" / "kernel"),
      "kernel is NOT directory-self-healed (no seed agent.json anchor; the spawn-time fail-safe def covers availability, kernelgen-ext 2026-09-26)")
    // 自愈内容 == 种子树（逐字节）
    assertEquals(treeAsText(home / "agents" / "general"), treeAsText(seedDirOf("general")),
      "自愈内容 == 种子树（逐字节）")
    // 守卫语义不变：不完整播种（既有 home 走 marker-only 分支、不重播默认集）。项目面自
    // 作者 2026-09-17 裁定②（既有 home 亦 add-only 补种）起由 `reconcileProjects` 补**缺失**
    // 的内置项目 ⇒ 本条原负向断言「既有 home 不建 general 项目」已随前令作废，翻转为正向。
    // 2026-09-26 kernelgen: the manifest carries no project items ⇒ reconcileProjects is dormant; existing general projects stay untouched — sealed family.
    assert(!os.exists(home / "projects" / "general" / "project.json"),
      "the general project is NOT backfilled into an existing home: the manifest carries no project items, so reconcileProjects stays dormant (existing general projects stay untouched — sealed family)")

  // ── ② 幂等 ├────────────────────────────────────────────────
  test("自愈幂等：第二次 ensure 对该目录零动作（内容与 mtime 双证）"):
    existingHome()
    ensure()
    val dir = home / "agents" / "general"
    val again = treeAsText(dir)
    val mtimes = os.walk(dir).filter(os.isFile).map(p => p -> os.mtime(p)).toMap
    ensure()
    assertEquals(treeAsText(dir), again, "第二次零动作（digest 已与种子一致）")
    mtimes.foreach { (p, before) =>
      assertEquals(os.mtime(p), before, s"第二次零写入（mtime 未变）：${p.relativeTo(home)}")
    }

  // ── ③ 已存在目录零覆盖 ├────────────────────────────────────
  test("自愈不改已存在目录（用户版本保留——差集非空 ⇒ REFUSING，零写盘）"):
    existingHome()
    val dir = home / "agents" / "general"
    os.makeDir.all(dir)
    os.write.over(dir / "agent.json", """{"name":"general","userEdited":true}""")
    os.write.over(dir / "system.md", "user edited system prompt\n")
    val before = treeAsText(dir)

    ensure()

    assertEquals(treeAsText(dir), before, "已存在目录逐字节未动（零覆盖）")
  // 注：差集非空 ⇒ REFUSING（零写盘）分支的行为面由本条钉住；「差集为空 ⇒ 备份后镜像
  // 覆盖」的仲裁机制属既有 reconcile 机制，不在本 spec 扩面。

  // ── ④ manifest 组成（取代退役的消费链启动校验）├────────────
  test("manifest 组成：含 agents:Nebula；不含已退役 memory agent；种子树实件在位"):
    assert(manifestAgentNames.contains("Nebula"), "manifest 含 agents:Nebula")
    // 已退役名以拼接字面书写，保持仓内 grep 零残留（仓内既有先例："Memory" + "Edit"）
    assert(!manifestAgentNames.contains("memory-" + "consolidator"),
      "manifest 不含已退役的 memory 消费 agent")
    assert(os.exists(seedDirOf("Nebula") / "system.md"), "Nebula 种子实件在位")
    assert(getClass.getClassLoader.getResource("seed/agents/memory-" + "consolidator/agent.json") == null,
      "已退役 agent 的种子资源已从 classpath 移除")
    // kernelgen-ext 2026-09-26: kernel joins the manifest as a PROMPT-ONLY item —
    // the seed ships system.md and deliberately NO agent.json (the def face is
    // mechanism-fixed via KernelFixedTools + ConvergedAgentNames; the spawn-time
    // fail-safe def needs no agent.json). Adding a seed agent.json for kernel is a
    // contract change and must land consciously (this negative goes red).
    assert(manifestAgentNames.contains("kernel"), "manifest carries the agents:kernel item (kernelgen-ext 2026-09-26)")
    assert(getClass.getClassLoader.getResource("seed/agents/kernel/system.md") != null,
      "kernel seed prompt artifact is on the classpath")
    assert(getClass.getClassLoader.getResource("seed/agents/kernel/agent.json") == null,
      "kernel ships NO seed agent.json (prompt-only item; def face is mechanism-fixed, kernelgen-ext 2026-09-26)")

end SeedAgentSelfHealSpec
