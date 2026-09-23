package nebflow.core.presets

import munit.FunSuite
import nebflow.agent.AgentLibrary
import nebflow.core.PathUtil
import nebflow.shared.AgentModelConfig

/**
 * panelscheme 批（2026-09-21，作者令）——SchemePolicy spec：
 * 面板模型方案收敛的解析侧单点策略（名称 → 有效方案引用矩阵）。
 *
 * 覆盖（每条都钉「谁说了算」）：
 * - 可设两类（Nebula / project-dispatcher）：自有引用原样生效（回归红线）。
 * - kernel：动态继承 Nebula **当前**引用——Nebula 改则 kernel 随变（重读即变）。
 * - general：动态继承 project-dispatcher 当前引用（节点无自有设置）。
 * - 其余 agent：存储引用被引擎忽略（数据留盘），回落默认 preset。
 * - 继承根缺失：宽容回落默认 preset（不炸装载）。
 * - AgentLibrary.loadFromDir 端到端：def.preset 显示继承根的方案名（面板药丸可见）。
 */
class SchemePolicySpec extends FunSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-scheme-policy"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  private def writeAgent(name: String, json: String): Unit =
    os.makeDir.all(tempRoot / "agents" / name)
    os.write.over(tempRoot / "agents" / name / "agent.json", json)

  private def writePresets(): Unit =
    os.write.over(
      tempRoot / "model-presets.json",
      """{"defaultPreset":"general","presets":{""" +
        """"general":{"name":"general","description":"","preferred":"default-model","fallbacks":[]},""" +
        """"fast":{"name":"fast","description":"","preferred":"fast-model","fallbacks":["fb-fallback"]},""" +
        """"deep":{"name":"deep","description":"","preferred":"deep-model","fallbacks":[]}}}"""
    )

  private def resetFixtures(): Unit =
    writePresets()
    // 可设两类：Nebula=fast、project-dispatcher=deep（自有引用，红线）
    writeAgent("Nebula", """{"name":"Nebula","description":"orchestrator","preset":"fast"}""")
    writeAgent("project-dispatcher", """{"name":"project-dispatcher","description":"dispatcher","preset":"deep"}""")
    // 其余：kernel/general 不带自有引用（继承体）；memory-consolidator 带死引用（忽略面）
    writeAgent("kernel", """{"name":"kernel","description":"minimal kernel"}""")
    writeAgent("general", """{"name":"general","description":"general executor"}""")
    writeAgent("memory-consolidator", """{"name":"memory-consolidator","description":"memory","preset":"fast"}""")

  private val FastChain = AgentModelConfig(Some("fast-model"), List("fb-fallback"))
  private val DeepChain = AgentModelConfig(Some("deep-model"), Nil)
  private val DefaultChain = AgentModelConfig(Some("default-model"), Nil)

  // ── effectiveRefs 单元矩阵 ─────────────────────────────────

  test("effectiveRefs: kernel 继承 Nebula 当前引用；general 继承 project-dispatcher 当前引用"):
    resetFixtures()
    val (kp, _) = SchemePolicy.effectiveRefs("kernel", None, None)
    assertEquals(kp, Some("fast"), "kernel must inherit Nebula's current preset ref")
    val (gp, _) = SchemePolicy.effectiveRefs("general", None, None)
    assertEquals(gp, Some("deep"), "general must inherit project-dispatcher's current preset ref")

  test("effectiveRefs: 可设两类自有引用原样（回归红线）；其余 agent 存储引用被忽略"):
    resetFixtures()
    assertEquals(SchemePolicy.effectiveRefs("Nebula", Some("fast"), None)._1, Some("fast"))
    assertEquals(SchemePolicy.effectiveRefs("project-dispatcher", Some("deep"), None)._1, Some("deep"))
    // memory-consolidator 带 preset:"fast"（盘上留审计）——引擎忽略 → None
    assertEquals(SchemePolicy.effectiveRefs("memory-consolidator", Some("fast"), None)._1, None)
    // 自有 legacy model 同样被忽略
    assertEquals(SchemePolicy.effectiveRefs("custom-agent", None, Some(AgentModelConfig(Some("x/y"), Nil)))._2, None)

  test("effectiveRefs: 动态跟随——Nebula 改方案，kernel 重读即随变"):
    resetFixtures()
    assertEquals(SchemePolicy.effectiveRefs("kernel", None, None)._1, Some("fast"))
    writeAgent("Nebula", """{"name":"Nebula","description":"orchestrator","preset":"deep"}""")
    assertEquals(
      SchemePolicy.effectiveRefs("kernel", None, None)._1,
      Some("deep"),
      "kernel must follow Nebula's changed preset on the next read (dynamic)"
    )

  test("effectiveRefs: 继承根缺失 → (None, None) 宽容回落（不炸装载）"):
    resetFixtures()
    os.remove.all(tempRoot / "agents" / "Nebula")
    assertEquals(SchemePolicy.effectiveRefs("kernel", None, None), (None, None))

  // ── loadFromDir 端到端（AgentLibrary 装载路径）──────────────

  test("loadFromDir: kernel def = Nebula 当前方案链，def.preset 显示 Nebula 的方案名"):
    resetFixtures()
    val lib = new AgentLibrary(tempRoot / "agents")
    val kernel = lib.loadFromDir(tempRoot / "agents" / "kernel").getOrElse(fail("kernel def missing"))
    assertEquals(kernel.model, Some(FastChain), "kernel must resolve through Nebula's preset chain")
    assertEquals(kernel.preset, Some("fast"), "panel pill must show the inherited preset name")

  test("loadFromDir: Nebula 改方案 → kernel def 随变（动态跟随，无缓存）"):
    resetFixtures()
    val lib = new AgentLibrary(tempRoot / "agents")
    val before = lib.loadFromDir(tempRoot / "agents" / "kernel").getOrElse(fail("kernel def missing"))
    assertEquals(before.model, Some(FastChain))
    writeAgent("Nebula", """{"name":"Nebula","description":"orchestrator","preset":"deep"}""")
    val after = lib.loadFromDir(tempRoot / "agents" / "kernel").getOrElse(fail("kernel def missing"))
    assertEquals(after.model, Some(DeepChain), "reload must pick up Nebula's new scheme")
    assertEquals(after.preset, Some("deep"))

  test("loadFromDir: general def = 分发器当前方案链（节点模型的唯一路径）"):
    resetFixtures()
    val lib = new AgentLibrary(tempRoot / "agents")
    val general = lib.loadFromDir(tempRoot / "agents" / "general").getOrElse(fail("general def missing"))
    assertEquals(general.model, Some(DeepChain), "general must run the dispatcher's current chain")
    assertEquals(general.preset, Some("deep"))

  test("loadFromDir: 可设两类行为不变（红线）——Nebula/dispatcher 解析走自有引用"):
    resetFixtures()
    val lib = new AgentLibrary(tempRoot / "agents")
    val nebula = lib.loadFromDir(tempRoot / "agents" / "Nebula").getOrElse(fail("Nebula def missing"))
    assertEquals(nebula.model, Some(FastChain))
    assertEquals(nebula.preset, Some("fast"))
    val dispatcher =
      lib.loadFromDir(tempRoot / "agents" / "project-dispatcher").getOrElse(fail("dispatcher def missing"))
    assertEquals(dispatcher.model, Some(DeepChain))
    assertEquals(dispatcher.preset, Some("deep"))

  test("loadFromDir: 其余 agent 存储方案被忽略——回落默认 preset（数据仍留盘）"):
    resetFixtures()
    val lib = new AgentLibrary(tempRoot / "agents")
    val mem =
      lib.loadFromDir(tempRoot / "agents" / "memory-consolidator").getOrElse(fail("memory-consolidator def missing"))
    assertEquals(mem.model, Some(DefaultChain), "ignored refs must fall through to the default preset")
    assertEquals(mem.preset, None, "ignored agents must not advertise a preset name")
    // 数据留盘：盘上 preset 引用原样保留（零删除）
    val raw = os.read(tempRoot / "agents" / "memory-consolidator" / "agent.json")
    assert(raw.contains("\"preset\""), "the stored preset ref must stay on disk for audit")

end SchemePolicySpec
