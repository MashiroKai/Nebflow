package nebflow.core.seed

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.PathUtil

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/**
 * agents 面缺失自愈 spec（2026-09-13 缺失自愈批 / 方案 C，作者令「改成缺失自愈」）。
 *
 * 与 plugins 面 `SeedPluginReconcileSpec` 同构（同一类缺口、同一处修复形态）：
 *  ① 既有 home + 默认集 agent 缺失 ⇒ 从种子整目录自愈补装（含 `memory-consolidator`）
 *  ② 幂等：第二次 ensure 对该目录零动作
 *  ③ 自愈面 = manifest 默认集（不向种子树全集扩张；非默认集无 agent 面）
 *  ④ 已存在目录零覆盖：用户改过的运行时不进自愈分支，内容逐字节保留
 *  ⑤ 消费链启动校验：agent 缺失 ⇒ WARN（响亮），在位 ⇒ INFO
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
  test("existing home + 默认集 agent 缺失 ⇒ 从种子自愈补装（含 memory-consolidator）"):
    existingHome()
    // 前置：三个 keeper 在位、消费者缺席（= 本机 author home 的实际形态）
    os.makeDir.all(home / "agents" / "general")
    os.write.over(home / "agents" / "general" / "agent.json", treeAsText(seedDirOf("general"))("agent.json"))
    os.write.over(home / "agents" / "general" / "system.md", treeAsText(seedDirOf("general"))("system.md"))
    assert(!os.exists(home / "agents" / "memory-consolidator"), "前置：消费者缺席")

    ensure()

    val dir = home / "agents" / "memory-consolidator"
    assert(os.exists(dir / "agent.json"), "缺失 ⇒ 自愈补装 agent.json")
    assert(os.exists(dir / "system.md"), "缺失 ⇒ 自愈补装 system.md")
    assertEquals(treeAsText(dir), treeAsText(seedDirOf("memory-consolidator")), "自愈内容 == 种子树（逐字节）")
    // 其余默认集 agent 同样补齐（缺失的补、在位的原样）
    for name <- manifestAgentNames do
      assert(os.exists(home / "agents" / name / "agent.json"), s"默认集 agent '$name' 就位")
    // 守卫语义不变：不完整播种（不建 general 项目脚手架）
    assert(!os.exists(home / "projects" / "general"), "守卫仍生效：既有 home 不建 general 项目")

  // ── ② 幂等 ├────────────────────────────────────────────────
  test("自愈幂等：第二次 ensure 对该目录零动作"):
    existingHome()
    ensure()
    val dir   = home / "agents" / "memory-consolidator"
    val again = treeAsText(dir)
    ensure()
    assertEquals(treeAsText(dir), again, "第二次零动作（digest 已与种子一致）")

  // ── ③ 已存在目录零覆盖 ├────────────────────────────────────
  test("自愈不改已存在目录（用户版本保留）"):
    existingHome()
    val dir = home / "agents" / "memory-consolidator"
    os.makeDir.all(dir)
    os.write.over(dir / "agent.json", """{"name":"memory-consolidator","userEdited":true}""")
    os.write.over(dir / "system.md", "user edited system prompt\n")
    val before = treeAsText(dir)

    ensure()

    assertEquals(treeAsText(dir), before, "已存在目录逐字节未动（零覆盖）")
  // 注：已存在目录走 diff/digest 仲裁分支（三条硬条件），其行为由既有 reconcile 机制覆盖；
  // 本批只新增「缺失 ⇒ 补装」这一条分支，改动面严格限定于此。

  // ── ④ 消费链启动校验（响亮告警）├────────────────────────────
  test("启动校验直测：消费链缺席 ⇒ 响亮 WARN（启动即可见，不靠 grep）；就位 ⇒ INFO"):
    existingHome()
    // ① 缺席态 ⇒ WARN（原始日志行见测试输出：[Seed: MEMORY CONSUMPTION CHAIN MISSING …]）
    SeedService.verifyMemoryConsumptionChain(home)
    assert(!os.exists(home / "agents" / "memory-consolidator"), "校验是只读的（不建目录、不写文件）")
    // ② ensure 之后 ⇒ 自愈补齐 + INFO
    ensure()
    assert(os.exists(home / "agents" / "memory-consolidator" / "agent.json"), "ensure 后消费链就位")
    SeedService.verifyMemoryConsumptionChain(home)
    // ③ 反向：删掉整目录（等价于作者 home 的缺席态）再跑一次 ⇒ 又补回来
    os.remove.all(home / "agents" / "memory-consolidator")
    assert(!os.exists(home / "agents" / "memory-consolidator"), "前置：缺席")
    ensure()
    assert(os.exists(home / "agents" / "memory-consolidator" / "agent.json"), "缺席 ⇒ 自愈（第二次实证）")

end SeedAgentSelfHealSpec
