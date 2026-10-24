package nebflow.core.project

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.jev.{CatalogEntry, JevAllocation, PluginAllocator}
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * PluginInjection 判读（jev-plugins 批 #1226）：
 *
 *  - allocatePreparation 全链：总闸 disabled → 空注入零调用；空目录 → empty-catalog；
 *    分配集 → 解析注入（`<injected-plugins>` 块内容 = 包根 Plugin.md——作者令
 *    「plugin.md = 插件必要文件机制」，缺文件 = Left）；选中包被封禁
 *    → Left 显式拒（分配失败是节点级失败，不静默降级）；
 *  - forNode S9 分支单点：声明集权威（分配面零调用）；per-project 引擎空声明 =
 *    旧行为（空注入零调用）；delegate 作用域引擎空声明 → 分配面 + `step-plugins`
 *    结构帧行落 ChainStore（零新建存储——既有行形态）。
 */
class PluginInjectionSpec extends CatsEffectSuite:

  override val munitIOTimeout = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-plugin-injection"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    os.remove.all(tempRoot / "plugins")
    os.remove.all(tempRoot / "logs")
    os.remove.all(tempRoot / "chains")
    os.write.over(tempRoot / "nebflow.json", "{}", createFolders = true)
    PluginAllocator.clear()

  override def afterEach(context: munit.AfterEach): Unit = PluginAllocator.clear()

  // ── 插件夹具（NodePluginChainSpec 同款单包形态：$schema canonical + 仅 skills）──
  // 作者令「plugin.md = 插件必要文件」批：分配注入的提示词 = 包根 Plugin.md 内容，
  // 夹具随该机制带 Plugin.md（marker 写在 Plugin.md 里——注入断言的锚）。
  private def writeSkillPlugin(name: String, marker: String): Unit =
    val dir = tempRoot / "plugins" / name
    os.makeDir.all(dir / "skills" / "howto")
    os.write.over(
      dir / "plugin.json",
      s"""{"$$schema":"${nebflow.core.plugin.PluginRegistry.CanonicalSchema}","name":"$name","version":"1.0.0","description":"$marker fixture"}""",
      createFolders = true
    )
    os.write.over(dir / "Plugin.md", s"# $name\n\n## $marker\n", createFolders = true)
    os.write.over(
      dir / "skills" / "howto" / "SKILL.md",
      s"""---
         |name: howto
         |description: injection test skill
         |---
         |## $marker""".stripMargin,
      createFolders = true
    )

  end writeSkillPlugin

  /** 总闸写入（nebflow.json plugins.enabled）。 */
  private def setPluginsEnabled(on: Boolean): Unit =
    os.write.over(
      tempRoot / "nebflow.json",
      if on then """{"plugins":{"enabled":true}}""" else """{"plugins":{"enabled":false}}""",
      createFolders = true
    )

  /** #523：自动分配专用闸写入（plugins.autoAllocate；总闸恒开态使用）。 */
  private def setAutoAllocate(on: Boolean): Unit =
    os.write.over(
      tempRoot / "nebflow.json",
      if on then """{"plugins":{"enabled":true,"autoAllocate":true}}"""
      else """{"plugins":{"enabled":true,"autoAllocate":false}}""",
      createFolders = true
    )

  /** 分配桩面：固定返回 names（via=Jev），并记录调用。 */
  private def installStub(
    names: List[String],
    calls: scala.collection.mutable.ListBuffer[String] = scala.collection.mutable.ListBuffer.empty
  ): scala.collection.mutable.ListBuffer[String] =
    PluginAllocator.install(
      new PluginAllocator.Face:
        def allocatePlugins(subject: String, taskText: String, catalog: List[CatalogEntry]) =
          IO {
            calls += s"$subject|${taskText.take(20)}"
            PluginAllocator.PluginAllocationResult(
              JevAllocation.Allocated(names),
              Some(PluginAllocator.AllocationVia.Jev)
            )
          }
    )
    calls

  end installStub

  private val node = NodeDef(
    id = "n1",
    name = "step-one",
    agent = "general",
    task = Some("ship the visual report"),
    createdAt = 0L
  )

  test("allocatePreparation: plugins face off → empty preparation, mode=disabled, the allocator is never consulted") {
    setPluginsEnabled(false)
    writeSkillPlugin("p1", "DISABLED")
    val calls = installStub(List("p1"))
    for r <- PluginInjection.allocatePreparation("delegate-a", "task")
    yield
      val (prep, set) = r.toOption.get
      assertEquals(prep.injectedBlock, "")
      assertEquals(set.mode, "disabled")
      assertEquals(calls.size, 0, "the total switch short-circuits before the allocation family")
  }

  test("allocatePreparation: empty catalog → mode=empty-catalog, zero allocation calls, an allocated event line") {
    setPluginsEnabled(true)
    val calls = installStub(List("p1"))
    for r <- PluginInjection.allocatePreparation("delegate-b", "task")
    yield
      val (prep, set) = r.toOption.get
      assertEquals(prep.injectedBlock, "")
      assertEquals(set, PluginInjection.AllocatedSet(Nil, "empty-catalog"))
      assertEquals(calls.size, 0, "the empty-catalog short-circuit precedes the family")
      // recordSuccess 落了一条 allocated 行（审计可查）。
      val events = os.read.lines(tempRoot / "logs" / "jev-allocation.jsonl")
      assert(events.exists(l => l.contains("\"allocated\"") && l.contains("delegate-b")), events.mkString("\n"))
  }

  test("allocatePreparation: an allocated set resolves through the registry into the injected block") {
    setPluginsEnabled(true)
    writeSkillPlugin("p1", "INJECTED-MARKER")
    installStub(List("p1"))
    for r <- PluginInjection.allocatePreparation("delegate-c", "task")
    yield
      val (prep, set) = r.toOption.get
      assertEquals(set, PluginInjection.AllocatedSet(List("p1"), "jev"))
      assert(prep.injectedBlock.contains("<injected-plugins>"), prep.injectedBlock)
      assert(prep.injectedBlock.contains("INJECTED-MARKER"), "the Plugin.md content reached the block")
      assertEquals(prep.mcpPlugins, Nil, "a skills-only package carries no MCP leg")
  }

  // ── stand-plugins 批（作者令 2026-10-23）：集合语义钉——分配面/声明面一次多挂 ──
  //
  // 考古结论（REPORT.md §2）：分配链（JevAllocator 逐包 need_ 问句批 / LlmAllocator
  // JSON 数组 / NodeEdit plugins string|array / NodeDef.plugins List / resolvePreparation
  // 逐包 traverse）端到端本就是集合语义，「JeV 只能单选」无结构依据。本组两钉把
  // 「一次声明两能力都挂上」钉死在 spec 层，防回归为单选形状。

  test("set semantics: one allocation carrying TWO packages mounts both (allocated face)") {
    setPluginsEnabled(true)
    writeSkillPlugin("p1", "SET-ALPHA")
    writeSkillPlugin("p2", "SET-BETA")
    installStub(List("p1", "p2"))
    for r <- PluginInjection.allocatePreparation("delegate-set", "task")
    yield
      val (prep, set) = r.toOption.get
      assertEquals(set, PluginInjection.AllocatedSet(List("p1", "p2"), "jev"), "both packages ride one allocation")
      assert(prep.injectedBlock.contains("SET-ALPHA"), prep.injectedBlock)
      assert(prep.injectedBlock.contains("SET-BETA"), "the second package is injected in the same block")
      assert(
        prep.injectedBlock.indexOf("SET-ALPHA") < prep.injectedBlock.indexOf("SET-BETA"),
        "catalog order preserved across the set"
      )
      assert(prep.injectedBlock.contains("<plugin name=\"p1\">") && prep.injectedBlock.contains("<plugin name=\"p2\">"))
  }

  test(
    "set semantics: one DECLARATION of two capabilities mounts both without consulting the allocator (declaration face)"
  ) {
    setPluginsEnabled(true)
    writeSkillPlugin("p1", "DECL-ALPHA")
    writeSkillPlugin("p2", "DECL-BETA")
    val calls = installStub(List("p1"))
    val declared = node.copy(plugins = List("p1", "p2"))
    for r <- PluginInjection.forNode(declared, delegateScope = true, scopeKey = "delegate-scope-set")
    yield
      val prep = r.toOption.get
      assert(prep.injectedBlock.contains("DECL-ALPHA") && prep.injectedBlock.contains("DECL-BETA"), prep.injectedBlock)
      assertEquals(calls.size, 0, "the declared set is authoritative — allocation face untouched")
      // 逐包独立 `<plugin>` 块、声明序保留（注入面形状 = 集合，非单选）。
      assert(prep.injectedBlock.contains("<plugin name=\"p1\">") && prep.injectedBlock.contains("<plugin name=\"p2\">"))
      assert(prep.injectedBlock.indexOf("DECL-ALPHA") < prep.injectedBlock.indexOf("DECL-BETA"))
  }

  // ── 作者令「plugin.md = 插件必要文件机制」：注入内容 = Plugin.md，缺文件 = Left ──

  test("injection source = Plugin.md: the block wraps Plugin.md (no skill attr) and SKILL.md is no longer injected") {
    setPluginsEnabled(true)
    writeSkillPlugin("p1", "PLUGIN-MD-ONLY")
    installStub(List("p1"))
    for r <- PluginInjection.allocatePreparation("delegate-pluginmd", "task")
    yield
      val (prep, _) = r.toOption.get
      // 注入块 = 包根 Plugin.md 原文（`<plugin name>` 无 skill 属性——旧逐 skill 形态退役）
      assert(prep.injectedBlock.contains("<plugin name=\"p1\">"), prep.injectedBlock)
      assert(!prep.injectedBlock.contains("skill="), prep.injectedBlock)
      assert(prep.injectedBlock.contains("## PLUGIN-MD-ONLY"), "Plugin.md body is the injected prompt")
      // SKILL.md 全文不再随分配注入（回归按需查阅件）
      assert(!prep.injectedBlock.contains("injection test skill"), "SKILL.md body must NOT ride the allocation prompt")
  }

  test("injection accepts the lowercase plugin.md spelling (same mechanism, zero migration)") {
    setPluginsEnabled(true)
    writeSkillPlugin("p1", "LOWER-SPELLING")
    os.move(tempRoot / "plugins" / "p1" / "Plugin.md", tempRoot / "plugins" / "p1" / "plugin.md")
    installStub(List("p1"))
    for r <- PluginInjection.allocatePreparation("delegate-lowermd", "task")
    yield
      val (prep, _) = r.toOption.get
      assert(prep.injectedBlock.contains("LOWER-SPELLING"), "lowercase plugin.md is the same required file")
  }

  test("a package missing the required Plugin.md is an explicit refusal (Left) naming the path") {
    setPluginsEnabled(true)
    writeSkillPlugin("p1", "NO-PLUGIN-MD")
    os.remove(tempRoot / "plugins" / "p1" / "Plugin.md")
    installStub(List("p1"))
    for r <- PluginInjection.allocatePreparation("delegate-missing-md", "task")
    yield
      assert(r.isLeft, s"expected Left, got: $r")
      val err = r.left.toOption.get
      assert(err.contains("required file Plugin.md"), err)
      assert(err.contains("no silent degradation"), err)
  }

  test("allocatePreparation: a blocked allocated package is an explicit refusal (Left), never a silent downgrade") {
    setPluginsEnabled(true)
    writeSkillPlugin("p1", "WILL-BLOCK")
    writeSkillPlugin("p2", "KEEPER")
    // 目录含 p2（非空 → 分配面真实调用）；p1 封禁模拟「目录构建后、解析前被封禁」
    // 的竞态——桩返回已封禁包名，解析面必须显式拒。
    for
      _ <- nebflow.core.plugin.PluginBlockPolicy.block("p1", "blocked fixture", "spec")
      _ = installStub(List("p1"))
      r <- PluginInjection.allocatePreparation("delegate-d", "task")
    yield
      assert(r.isLeft, s"expected Left, got: $r")
      assert(r.left.toOption.get.contains("PLUGIN_BLOCKED"), r.left.toOption.get)
  }

  test("forNode S9: declared plugins win — the allocation face is not consulted (dispatch permit stays one-shot)") {
    setPluginsEnabled(true)
    writeSkillPlugin("p1", "DECLARED")
    writeSkillPlugin("p2", "ALLOC")
    val calls = installStub(List("p2"))
    val declared = node.copy(plugins = List("p1"))
    for r <- PluginInjection.forNode(declared, delegateScope = true, scopeKey = "delegate-scope-1")
    yield
      val prep = r.toOption.get
      assert(prep.injectedBlock.contains("DECLARED"))
      assert(!prep.injectedBlock.contains("ALLOC"), "the declared set is the whole assignment")
      assertEquals(calls.size, 0, "declared plugins short-circuit the allocation face")
  }

  test(
    "forNode S9: per-project engine (delegateScope=false) + empty declaration → legacy behaviour byte-identical (no allocation call)"
  ) {
    setPluginsEnabled(true)
    writeSkillPlugin("p1", "NEVER")
    val calls = installStub(List("p1"))
    for r <- PluginInjection.forNode(node, delegateScope = false, scopeKey = "scope-x")
    yield
      assertEquals(r.toOption.get.injectedBlock, "")
      assertEquals(calls.size, 0, "per-project dispatch is NOT an allocation trigger (S9 触发点全集之外)")
      assertEquals(os.exists(tempRoot / "chains"), false, "no step-plugins line without an allocation")
  }

  test(
    "forNode S9: delegate-scope engine + empty declaration → allocation + step-plugins structure line (ChainStore, zero new storage)"
  ) {
    setPluginsEnabled(true)
    writeSkillPlugin("p1", "STEP-ALLOC")
    val calls = installStub(List("p1"))
    for r <- PluginInjection.forNode(node, delegateScope = true, scopeKey = "delegate-scope-9")
    yield
      val prep = r.toOption.get
      assert(prep.injectedBlock.contains("STEP-ALLOC"))
      assertEquals(calls.size, 1, "the step spawn consulted the allocation face exactly once")
      assert(calls.head.startsWith("node:n1|"), s"the subject names the node: ${calls.head}")
      val key = nebflow.core.delegate.ChainStore.keyFor(None, "delegate-scope-9")
      val rec = nebflow.core.delegate.ChainStore.readSync(key).get
      val line = rec.entries.find(_.kind == "step-plugins").get
      assertEquals(line.payload.hcursor.get[String]("nodeId").toOption, Some("n1"))
      assertEquals(line.payload.hcursor.get[List[String]]("plugins").toOption, Some(List("p1")))
      assertEquals(line.payload.hcursor.get[String]("allocation").toOption, Some("jev"))
  }

  // ── #523 补齐批：plugins.autoAllocate 专用闸 ＋ Jev 不可用回退路径（注入层）──

  test("allocatePreparation: autoAllocate=false → mode=auto-off, zero model calls, zero allocation audit") {
    setPluginsEnabled(true)
    setAutoAllocate(false)
    writeSkillPlugin("p1", "NEVER-ALLOC")
    val calls = installStub(List("p1"))
    for r <- PluginInjection.allocatePreparation("delegate-auto-off", "task")
    yield
      val (prep, set) = r.toOption.get
      assertEquals(prep.injectedBlock, "", "the auto gate short-circuits before any injection")
      assertEquals(set, PluginInjection.AllocatedSet(Nil, "auto-off"))
      assertEquals(calls.size, 0, "autoAllocate=false never consults the Jev/LLM family")
      // 惰性态 = 不写分配审计（与 disabled 同族）；连 allocated 成功行也不落。
      val logLines =
        if os.exists(tempRoot / "logs" / "jev-allocation.jsonl") then
          os.read.lines(tempRoot / "logs" / "jev-allocation.jsonl")
        else Vector.empty
      assert(!logLines.exists(_.contains("delegate-auto-off")), logLines.mkString("\n"))
  }

  test(
    "forNode S9: autoAllocate=false + delegate-scope + empty declaration → no step-plugins line, no allocation call"
  ) {
    setPluginsEnabled(true)
    setAutoAllocate(false)
    writeSkillPlugin("p1", "NEVER-STEP")
    val calls = installStub(List("p1"))
    for r <- PluginInjection.forNode(node, delegateScope = true, scopeKey = "delegate-scope-auto-off")
    yield
      assertEquals(r.toOption.get.injectedBlock, "")
      assertEquals(calls.size, 0, "the step spawn is gated by the same auto switch")
      assertEquals(os.exists(tempRoot / "chains"), false, "inert mode writes no step-plugins line")
  }

  test(
    "allocatePreparation: both allocation arms fail → mode=fail-open, empty injection, dispatch continues (Jev 不可用回退路径)"
  ) {
    setPluginsEnabled(true)
    writeSkillPlugin("p1", "FAIL-OPEN") // 非空目录——越过 empty-catalog 短路，分配面真实到场
    PluginAllocator.install(
      new PluginAllocator.Face:
        def allocatePlugins(subject: String, taskText: String, catalog: List[CatalogEntry]) =
          IO.raiseError(new java.util.concurrent.TimeoutException("jev+llm both down"))
    )
    for r <- PluginInjection.allocatePreparation("delegate-fail-open", "task")
    yield
      val (prep, set) = r.toOption.get
      assertEquals(prep.injectedBlock, "", "fail-open = empty set, dispatch is never wedged")
      assertEquals(set, PluginInjection.AllocatedSet(Nil, "fail-open"))
      // fail-open 落 Failure 事件行（JevFallback WARN + jsonl 审计——④判定结果落审计）。
      val events = os.read.lines(tempRoot / "logs" / "jev-allocation.jsonl")
      assert(events.exists(l => l.contains("delegate-fail-open") && l.contains("failure")), events.mkString("\n"))
  }

  test("allocatePreparation: LLM fallback arm produces mode=llm-fallback (Jev unavailable → small-model takeover)") {
    setPluginsEnabled(true)
    writeSkillPlugin("p1", "LLM-FALLBACK")
    PluginAllocator.install(
      new PluginAllocator.Face:
        def allocatePlugins(subject: String, taskText: String, catalog: List[CatalogEntry]) =
          IO.pure(
            PluginAllocator.PluginAllocationResult(
              JevAllocation.Allocated(List("p1")),
              Some(PluginAllocator.AllocationVia.LlmFallback)
            )
          )
    )
    for r <- PluginInjection.allocatePreparation("delegate-llm-fb", "task")
    yield
      val (prep, set) = r.toOption.get
      assertEquals(set, PluginInjection.AllocatedSet(List("p1"), "llm-fallback"))
      assert(prep.injectedBlock.contains("LLM-FALLBACK"), prep.injectedBlock)
  }

end PluginInjectionSpec
