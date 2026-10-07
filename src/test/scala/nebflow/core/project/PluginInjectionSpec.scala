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
 *    分配集 → 解析注入（`<injected-plugins>` 块内容 = 注册表技能全文）；选中包被封禁
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
  private def writeSkillPlugin(name: String, marker: String): Unit =
    val dir = tempRoot / "plugins" / name
    os.makeDir.all(dir / "skills" / "howto")
    os.write.over(
      dir / "plugin.json",
      s"""{"$$schema":"${nebflow.core.plugin.PluginRegistry.CanonicalSchema}","name":"$name","version":"1.0.0","description":"$marker fixture"}""",
      createFolders = true
    )
    os.write.over(
      dir / "skills" / "howto" / "SKILL.md",
      s"""---
         |name: howto
         |description: injection test skill
         |---
         |## $marker""".stripMargin,
      createFolders = true
    )

  /** 总闸写入（nebflow.json plugins.enabled）。 */
  private def setPluginsEnabled(on: Boolean): Unit =
    os.write.over(
      tempRoot / "nebflow.json",
      if on then """{"plugins":{"enabled":true}}""" else """{"plugins":{"enabled":false}}""",
      createFolders = true
    )

  /** 分配桩面：固定返回 names（via=Jev），并记录调用。 */
  private def installStub(
      names: List[String],
      calls: scala.collection.mutable.ListBuffer[String] = scala.collection.mutable.ListBuffer.empty
  ): scala.collection.mutable.ListBuffer[String] =
    PluginAllocator.install(new PluginAllocator.Face:
      def allocatePlugins(subject: String, taskText: String, catalog: List[CatalogEntry]) =
        IO {
          calls += s"$subject|${taskText.take(20)}"
          PluginAllocator.PluginAllocationResult(JevAllocation.Allocated(names), Some(PluginAllocator.AllocationVia.Jev))
        })
    calls

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
      assert(prep.injectedBlock.contains("INJECTED-MARKER"), "the skill body reached the block")
      assertEquals(prep.mcpPlugins, Nil, "a skills-only package carries no MCP leg")
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

  test("forNode S9: per-project engine (delegateScope=false) + empty declaration → legacy behaviour byte-identical (no allocation call)") {
    setPluginsEnabled(true)
    writeSkillPlugin("p1", "NEVER")
    val calls = installStub(List("p1"))
    for r <- PluginInjection.forNode(node, delegateScope = false, scopeKey = "scope-x")
    yield
      assertEquals(r.toOption.get.injectedBlock, "")
      assertEquals(calls.size, 0, "per-project dispatch is NOT an allocation trigger (S9 触发点全集之外)")
      assertEquals(os.exists(tempRoot / "chains"), false, "no step-plugins line without an allocation")
  }

  test("forNode S9: delegate-scope engine + empty declaration → allocation + step-plugins structure line (ChainStore, zero new storage)") {
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

end PluginInjectionSpec
