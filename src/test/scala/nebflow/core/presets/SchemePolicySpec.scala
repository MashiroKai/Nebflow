package nebflow.core.presets

import munit.FunSuite
import nebflow.agent.AgentLibrary
import nebflow.shared.{AgentModelConfig, PathUtil}

/**
 * panelscheme 批（2026-09-21，作者令）——SchemePolicy spec：
 * 面板模型方案收敛的解析侧单点策略（名称 → 有效方案引用矩阵）。
 *
 * 覆盖（每条都钉「谁说了算」）：
 * - 可设两类（Nebula / project-dispatcher）：自有引用原样生效（回归红线）。
 * - 执行 agent（builtin-merge 批 2026-10-03：kernel+general ⇒ nebflow）：动态继承
 *   project-dispatcher **当前**引用——执行会话无自有设置，分发器改则随变（重读即变）；
 *   退役名（kernel/general，旧数据 sidecar）经 resolveRole 归一到同一行。
 * - 其余 agent：存储引用被引擎忽略（数据留盘），回落默认 preset。
 * - 继承根缺失：宽容回落默认 preset（不炸装载）。
 * - AgentLibrary.loadFromDir 端到端：自定义名 def.preset 显示根方案名；收敛名磁盘
 *   目录是死信（loadFromDir 返回 None，内置定义在代码面）。
 *
 * P1-2 对齐（2026-10-03，任务书 P1-2 + 本批唯一的行为收敛登记）：旧表两行
 * `kernel→Nebula / general→dispatcher` 合并为一行 `executor→dispatcher`（合并后
 * 执行 agent 只有这一个继承根——原 kernel 行跟随 Nebula 的面随合并并入，交付报告
 * 显式登记）。基线既有红 4 条（loadFromDir 四连「def missing」——builtin-def 批
 * `isBuiltin ⇒ None` 使然）随本批对齐一并翻绿。
 */
class SchemePolicySpec extends FunSuite:

  /** 执行 agent 名（builtin-merge 批收敛单点）。 */
  private val executorName: String = nebflow.core.entity.BuiltinAgents.ExecutorName

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
    // 执行 agent 无自有引用（继承体）；退役名 sidecar 同理（旧数据面）
    writeAgent(executorName, s"""{"name":"$executorName","description":"executor"}""")
    writeAgent("kernel", """{"name":"kernel","description":"retired kernel sidecar"}""")
    writeAgent("general", """{"name":"general","description":"retired general sidecar"}""")
    writeAgent("custom-agent", """{"name":"custom-agent","description":"standalone custom","preset":"fast"}""")

  private val FastChain = AgentModelConfig(Some("fast-model"), List("fb-fallback"))
  private val DeepChain = AgentModelConfig(Some("deep-model"), Nil)
  private val DefaultChain = AgentModelConfig(Some("default-model"), Nil)

  // ── effectiveRefs 单元矩阵 ─────────────────────────────────

  test("effectiveRefs: 执行 agent 继承 project-dispatcher 当前引用；退役名归一到同一行"):
    resetFixtures()
    val (ep, _) = SchemePolicy.effectiveRefs(executorName, None, None)
    assertEquals(ep, Some("deep"), "executor must inherit the dispatcher's current preset ref")
    // 退役名（旧数据 sidecar）经 resolveRole 归一到执行 agent 行——合并后只有这一个
    // 继承根（原 kernel→Nebula 行并入，本批唯一行为收敛，交付报告登记）。
    val (kp, _) = SchemePolicy.effectiveRefs("kernel", None, None)
    assertEquals(kp, Some("deep"), "retired kernel sidecar resolves through the executor row (dispatcher)")
    val (gp, _) = SchemePolicy.effectiveRefs("general", None, None)
    assertEquals(gp, Some("deep"), "retired general sidecar resolves through the executor row (dispatcher)")

  test("effectiveRefs: 可设两类自有引用原样（回归红线）；其余 agent 存储引用被忽略"):
    resetFixtures()
    assertEquals(SchemePolicy.effectiveRefs("Nebula", Some("fast"), None)._1, Some("fast"))
    assertEquals(SchemePolicy.effectiveRefs("project-dispatcher", Some("deep"), None)._1, Some("deep"))
    // custom-agent 带 preset:"fast"（盘上留审计）——引擎忽略 → None
    assertEquals(SchemePolicy.effectiveRefs("custom-agent", Some("fast"), None)._1, None)
    // 自有 legacy model 同样被忽略
    assertEquals(SchemePolicy.effectiveRefs("custom-agent", None, Some(AgentModelConfig(Some("x/y"), Nil)))._2, None)

  test("effectiveRefs: 动态跟随——dispatcher 改方案，执行 agent 重读即随变"):
    resetFixtures()
    assertEquals(SchemePolicy.effectiveRefs(executorName, None, None)._1, Some("deep"))
    writeAgent("project-dispatcher", """{"name":"project-dispatcher","description":"dispatcher","preset":"fast"}""")
    assertEquals(
      SchemePolicy.effectiveRefs(executorName, None, None)._1,
      Some("fast"),
      "executor must follow the dispatcher's changed preset on the next read (dynamic)"
    )
    // 退役名同一行 ⇒ 同读数
    assertEquals(SchemePolicy.effectiveRefs("general", None, None)._1, Some("fast"))

  test("effectiveRefs: 继承根缺失 → (None, None) 宽容回落（不炸装载）"):
    resetFixtures()
    os.remove.all(tempRoot / "agents" / "project-dispatcher")
    assertEquals(SchemePolicy.effectiveRefs(executorName, None, None), (None, None))

  // ── loadFromDir 端到端（AgentLibrary 装载路径）──────────────

  test("loadFromDir: 自定义名 def = 执行 agent 继承根同款解析之外的忽略面——自定义名存储引用被忽略（回落默认）"):
    resetFixtures()
    val lib = new AgentLibrary(tempRoot / "agents")
    val custom = lib.loadFromDir(tempRoot / "agents" / "custom-agent").getOrElse(fail("custom-agent def missing"))
    assertEquals(custom.model, Some(DefaultChain), "ignored refs must fall through to the default preset")
    assertEquals(custom.preset, None, "ignored agents must not advertise a preset name")

  test("loadFromDir: 内置名（Nebula / dispatcher / 执行 agent）磁盘目录是死信——返回 None（代码定义唯一权威）"):
    resetFixtures()
    val lib = new AgentLibrary(tempRoot / "agents")
    // builtin-def 批（2026-10-03 作者令①）：收敛名的 def 面只在代码（BuiltinAgents），
    // 磁盘目录对这四个名是死信——loadFromDir 单点跳过（基线既有红 4 条正是「def
    // missing」使然；本批把判据面钉在这个单点上）。
    for name <- List("Nebula", "project-dispatcher", executorName) do
      assertEquals(
        lib.loadFromDir(tempRoot / "agents" / name),
        None,
        s"$name is a builtin — its disk directory must be a dead letter (loadFromDir ⇒ None)"
      )

  test("loadFromDir: 退役名（kernel/general）磁盘目录仍可装载——磁盘面照旧（旧数据兼容）"):
    resetFixtures()
    val lib = new AgentLibrary(tempRoot / "agents")
    // 退役名不再是 builtin（loadFromDir 不跳过）——磁盘目录照常装载，SchemePolicy
    // 名称策略经 resolveRole 把它们归一到执行 agent 行（dispatcher 继承）⇒ 旧数据
    // home 的面板/装载面语义与合并前一致。
    val kernel = lib.loadFromDir(tempRoot / "agents" / "kernel").getOrElse(fail("kernel def missing"))
    assertEquals(kernel.model, Some(DeepChain), "retired kernel must resolve through the executor row (dispatcher chain)")
    assertEquals(kernel.preset, Some("deep"), "panel pill must show the inherited preset name")
    val general = lib.loadFromDir(tempRoot / "agents" / "general").getOrElse(fail("general def missing"))
    assertEquals(general.model, Some(DeepChain), "retired general must resolve through the executor row (dispatcher chain)")
    assertEquals(general.preset, Some("deep"))

  test("loadFromDir: dispatcher 改方案 → 退役名 def 随变（动态跟随，无缓存）"):
    resetFixtures()
    val lib = new AgentLibrary(tempRoot / "agents")
    val before = lib.loadFromDir(tempRoot / "agents" / "general").getOrElse(fail("general def missing"))
    assertEquals(before.model, Some(DeepChain))
    writeAgent("project-dispatcher", """{"name":"project-dispatcher","description":"dispatcher","preset":"fast"}""")
    val after = lib.loadFromDir(tempRoot / "agents" / "general").getOrElse(fail("general def missing"))
    assertEquals(after.model, Some(FastChain), "reload must pick up the dispatcher's new scheme")
    assertEquals(after.preset, Some("fast"))

end SchemePolicySpec
