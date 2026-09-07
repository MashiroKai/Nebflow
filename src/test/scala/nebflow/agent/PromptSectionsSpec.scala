package nebflow.agent

import nebflow.agent.PromptSections.*
import nebflow.core.PathUtil

class PromptSectionsSpec extends munit.FunSuite:

  // ============================================================
  // 阶段 2d（§D.1-14/§D.2）：条件工具指南段已删除，内容下迁进工具
  // description（自包含）。下列用例由「段注入断言」改钉新不变量：无论
  // 工具是否可用，system prompt 都不再出现旧**内建**段；指南经工具定义
  // 必达（自包含断言见 Phase2dToolRefactorSpec）。
  // 数据根隔离：文件版条件段（~/.nebflow/prompts/sections/ask-user.md 等，
  // 定义层退役走宿主命令）不得影响内建段删除的断言。
  // ============================================================

  private def withIsolatedDataRoot[A](body: => A): A =
    val prevRoot = PathUtil.dataRoot
    val tempRoot = os.pwd / "target" / "test-2d-sections-isolation"
    try
      os.remove.all(tempRoot)
      os.makeDir.all(tempRoot)
      PathUtil.setDataRoot(tempRoot)
      body
    finally
      PathUtil.setDataRoot(prevRoot)
      os.remove.all(tempRoot)

  test("AskUserQuestion guide section no longer injected even when tool is available (2d §D.2 下迁)"):
    withIsolatedDataRoot {
      val withTool = PromptContext(availableTools = Set("AskUserQuestion", "Read"))
      val blocks = buildConditionalBlocks(withTool)
      assert(!blocks.contains("## Asking the User"), "order 400 内建段已删——指南在工具 description")
    }

  test("Read live-update section no longer injected when Read tool is available (2d §D.2 下迁)"):
    withIsolatedDataRoot {
      val ctx = PromptContext(availableTools = Set("Read", "Write"))
      val blocks = buildConditionalBlocks(ctx)
      assert(!blocks.contains("## Read Tool"), "order 410 内建段已删——live 语义在 Read description")
    }

  test("Visual reporting section no longer injected when Pop tool is available (2d §D.2 下迁)"):
    withIsolatedDataRoot {
      val ctx = PromptContext(availableTools = Set("Pop", "Read"))
      val blocks = buildConditionalBlocks(ctx)
      assert(!blocks.contains("## Visual Reporting"), "order 415 内建段已删——汇报工作流在 Pop description")
    }

  // ============================================================
  // Feature-flag sections
  // ============================================================

  test("Voice section included when voiceEnabled"):
    val ctx = PromptContext(voiceEnabled = true)
    val blocks = buildConditionalBlocks(ctx)
    assert(blocks.contains("## Voice Output"))

  test("Voice section excluded when voice muted"):
    val ctx = PromptContext(voiceEnabled = false)
    val blocks = buildConditionalBlocks(ctx)
    assert(!blocks.contains("## Voice Output"))

  // ============================================================
  // Runtime-state sections
  // ============================================================

  test("Devices section included when deviceInfo is non-empty"):
    val ctx = PromptContext(
      deviceInfo = "local (MacBook); Desktop-PC",
      hasDevices = true
    )
    val blocks = buildConditionalBlocks(ctx)
    assert(blocks.contains("# Devices"))
    assert(blocks.contains("local (MacBook); Desktop-PC"))

  test("Devices section excluded when no devices"):
    val ctx = PromptContext(deviceInfo = "", hasDevices = false)
    val blocks = buildConditionalBlocks(ctx)
    assert(!blocks.contains("# Devices"))

  test("Active Sessions section included when agentSessionsText is non-empty"):
    val sessions = "# Active Sessions\n\naddr-1 — Explorer: investigating (running)"
    val ctx = PromptContext(agentSessionsText = sessions, hasActiveSessions = true)
    val blocks = buildConditionalBlocks(ctx)
    assert(blocks.contains("# Active Sessions"))
    assert(blocks.contains("Explorer"))

  test("Language section included when language is defined"):
    val ctx = PromptContext(language = Some("Chinese"))
    val blocks = buildConditionalBlocks(ctx)
    assert(blocks.contains("# Language"))
    assert(blocks.contains("Chinese"))

  test("Language section excluded when language is None"):
    val ctx = PromptContext(language = None)
    val blocks = buildConditionalBlocks(ctx)
    assert(!blocks.contains("# Language"))

  // ============================================================
  // Catalog and rules sections
  // ============================================================

  test("Skill catalog included when non-empty"):
    val catalog = "# Skills\n\n- review: code review skill"
    val ctx = PromptContext(skillCatalog = catalog)
    val blocks = buildConditionalBlocks(ctx)
    assert(blocks.contains("# Skills"))
    assert(blocks.contains("review"))

  test("Project rules included when rulesMd is defined"):
    val ctx = PromptContext(rulesMd = Some("1. Always test.\n2. Use feature branches."))
    val blocks = buildConditionalBlocks(ctx)
    assert(blocks.contains("## Project Rules"))
    assert(blocks.contains("Always test."))

  test("Project rules excluded when rulesMd is None"):
    val ctx = PromptContext(rulesMd = None)
    val blocks = buildConditionalBlocks(ctx)
    assert(!blocks.contains("## Project Rules"))

  // ============================================================
  // Section ordering
  // ============================================================

  test("sections are ordered correctly"):
    val ctx = PromptContext(
      availableTools = Set("AskUserQuestion", "Read", "Pop"),
      voiceEnabled = true,
      deviceInfo = "device-list",
      hasDevices = true,
      agentSessionsText = "# Active Sessions",
      hasActiveSessions = true,
      language = Some("English"),
      skillCatalog = "# Skills",
      rulesMd = Some("rule1")
    )
    val blocks = buildConditionalBlocks(ctx)
    val voiceIdx = blocks.indexOf("## Voice Output")
    val devicesIdx = blocks.indexOf("# Devices")
    val sessionsIdx = blocks.indexOf("# Active Sessions")
    val langIdx = blocks.indexOf("# Language")
    val skillsIdx = blocks.indexOf("# Skills")
    val rulesIdx = blocks.indexOf("## Project Rules")

    assert(voiceIdx >= 0, "Voice section should be present")
    assert(voiceIdx < devicesIdx, "Voice should come before Devices")
    assert(devicesIdx < sessionsIdx, "Devices should come before Sessions")
    assert(sessionsIdx < langIdx, "Sessions should come before Language")
    assert(langIdx < skillsIdx, "Language should come before Skills")
    assert(skillsIdx < rulesIdx, "Skills should come before Rules")

  // ============================================================
  // Empty context
  // ============================================================

  test("minimal context with voice disabled excludes voice section"):
    val ctx = PromptContext(voiceEnabled = false)
    val blocks = buildConditionalBlocks(ctx)
    assert(!blocks.contains("## Voice Output"))

  // ============================================================
  // Reminder refactor (2026-08-20, D6): task-list semantics migrated
  // from the per-turn tasks reminder into a cached systemStable section.
  // ============================================================

  test("Task List Protocol section no longer injected for team category (2d §D.2 下迁 TeamTask description)"):
    withIsolatedDataRoot {
      val team = buildConditionalBlocks(PromptContext(agentCategory = "team", agentName = "Backend"))
      val nebula = buildConditionalBlocks(PromptContext(agentName = "Nebula"))
      val standalone = buildConditionalBlocks(PromptContext(agentCategory = "standalone", agentName = "Explorer"))
      assert(!team.contains("## Task List Protocol"), "order 630 段已删——协议在 TeamTask 三件 description（双轨期）")
      assert(!nebula.contains("## Task List Protocol"), "Nebula 无任务语义（工具已移除）")
      assert(!standalone.contains("## Task List Protocol"), "standalone agents never see it")
    }

  // ============================================================
  // stripSection / stripAllMigrated
  // ============================================================

  test("stripSection removes a header block from prompt"):
    val prompt =
      """Line one.
        |
        |## Voice Output
        |
        |This should be removed.
        |
        |More content here.
        |
        |## Another Section
        |
        |This stays.""".stripMargin
    val stripped = stripSection(prompt, "Voice Output")
    assert(!stripped.contains("This should be removed."))
    assert(stripped.contains("This stays."))
    assert(stripped.contains("Line one."))

  test("stripAllMigrated removes Voice Output"):
    val prompt =
      """## Voice Output
        |
        |Voice instructions here.""".stripMargin
    val stripped = stripAllMigrated(prompt)
    assert(!stripped.contains("Voice Output"))
    assert(!stripped.contains("Voice instructions"))

  test("stripSection handles missing section gracefully"):
    val prompt = "No sections here."
    val stripped = stripSection(prompt, "Voice Output")
    assertEquals(stripped, prompt)

  // ============================================================
  // requiresTools helper
  // ============================================================

  test("requiresTools checks all required tools"):
    val cond = requiresTools("Read", "Write")
    assert(cond(PromptContext(availableTools = Set("Read", "Write", "Grep"))))
    assert(!cond(PromptContext(availableTools = Set("Read")))) // missing Write
    assert(!cond(PromptContext(availableTools = Set.empty)))

  // ============================================================
  // Cache v2 (2026-08-11): task list moved out of systemStable
  // ============================================================

  test("Task list excluded from system prompt (cache v2 — moves to user-turn reminder)"):
    val ctx = PromptContext(taskListText = "## Current Tasks\n\n#1 [pending] Fix the cache bug")
    val blocks = buildConditionalBlocks(ctx)
    assert(!blocks.contains("## Current Tasks"), "task list must not be part of systemStable")
    assert(!blocks.contains("Fix the cache bug"))

  test("envInfoSection renders the file-based Environment block for change detection"):
    // Self-contained: build a temp environment section so the test does not
    // depend on ~/.nebflow existing or on test-class ordering (other suites
    // set a global dataRoot). The previous dataRoot is restored afterwards;
    // loadFileSectionsCached is mtime-keyed, so the next call re-reads from
    // the restored root automatically.
    val prevRoot = PathUtil.dataRoot
    val tempRoot = os.pwd / "target" / "test-env-info"
    val envDir = tempRoot / "prompts" / "sections" / "environment"
    try
      os.remove.all(tempRoot)
      os.makeDir.all(envDir)
      os.write.over(envDir / "condition.json", """{"order": 100, "condition": "always"}""")
      os.write.over(envDir / "prompt.md", "## Environment\n\n| Chat width | ~{{chat_width}}px |")
      PathUtil.setDataRoot(tempRoot)
      val env = envInfoSection(PromptContext(chatWidth = 1200))
      assert(env.contains("## Environment"), "environment section should be rendered")
      assert(env.contains("Chat width"), env)
    finally
      PathUtil.setDataRoot(prevRoot)
      os.remove.all(tempRoot)

  // ============================================================
  // System prompt assembly (provider prefix-cache contract)
  // ============================================================

  test("shared system prefix stays first in assembled prompt (provider prefix cache)"):
    // The shared system-prefix-for-all block must be the first bytes of every
    // agent's system prompt — cross-agent prefix caching depends on it.
    val prompt = assembleSystemPrompt("SHARED-PREFIX", "AGENT-MD", "CONDITIONAL")
    assert(prompt.startsWith("SHARED-PREFIX"), "shared prefix must be first")
    val prefixIdx = prompt.indexOf("SHARED-PREFIX")
    val agentIdx = prompt.indexOf("AGENT-MD")
    val condIdx = prompt.indexOf("CONDITIONAL")
    assert(prefixIdx < agentIdx && agentIdx < condIdx, "order must be prefix → agent.md → conditional")

  test("assembleSystemPrompt omits separator when no conditional blocks"):
    assertEquals(assembleSystemPrompt("P", "A", ""), "PA")
    assertEquals(assembleSystemPrompt("P", "A", "C"), "PA\n\nC")

  // ============================================================
  // Mounted projects (progressive disclosure 2026-09-07): Nebula-only
  // section + testable MountedProjectList render/delta helpers.
  // ============================================================

  test("Mounted Projects section injected for root agent with a non-empty list"):
    val ctx = PromptContext(
      isRootAgent = true,
      mountedProjectsText = "- nebflow: Scala version\n- voice-recognition-test: STT"
    )
    val blocks = buildConditionalBlocks(ctx)
    assert(blocks.contains("# Mounted Projects"), "root agent must see the mounted-projects section")
    assert(blocks.contains("- nebflow: Scala version"))
    assert(blocks.contains("- voice-recognition-test: STT"))

  test("Mounted Projects section still injected for root agent when the list is the empty placeholder"):
    // Empty list must be explicitly presented ("当前无挂载项目"), not silently dropped.
    val ctx = PromptContext(isRootAgent = true, mountedProjectsText = MountedProjectList.EmptyText)
    val blocks = buildConditionalBlocks(ctx)
    assert(blocks.contains("# Mounted Projects"))
    assert(blocks.contains(MountedProjectList.EmptyText))

  test("Mounted Projects section NOT injected for non-root agents (dispatcher/node sessions)"):
    val ctx = PromptContext(isRootAgent = false, mountedProjectsText = "- nebflow: Scala version")
    val blocks = buildConditionalBlocks(ctx)
    assert(!blocks.contains("# Mounted Projects"), "non-root agents must not see the mounted-projects section")

  test("MountedProjectList.renderLines renders empty list as the explicit placeholder"):
    assertEquals(MountedProjectList.renderLines(Nil), MountedProjectList.EmptyText)

  test("MountedProjectList.renderLines sorts by name and omits empty description"):
    val lines = MountedProjectList.renderLines(
      List(
        ("voice-recognition-test", Some("STT")),
        ("nebflow", None),
        ("czt-project", Some(""))
      )
    )
    assertEquals(
      lines,
      "- czt-project\n- nebflow\n- voice-recognition-test: STT"
    )

  test("MountedProjectList.delta yields +/- lines for added/removed projects"):
    val old = "- nebflow: Scala version\n- czt-project"
    val current = "- nebflow: Scala version\n- voice-recognition-test: STT"
    val d = MountedProjectList.delta(old, current)
    assertEquals(d, "+ voice-recognition-test: STT\n- czt-project")
    assert(!d.contains("- nebflow"), s"unchanged project should not appear: $d")

  test("MountedProjectList.delta is empty when entry sets are identical (ignores ordering)"):
    assertEquals(MountedProjectList.delta("- a\n- b", "- b\n- a"), "")
    assertEquals(MountedProjectList.delta("- a\n- b", "- a\n- b"), "")
    assertEquals(MountedProjectList.delta("- a", "- a"), "")

end PromptSectionsSpec
