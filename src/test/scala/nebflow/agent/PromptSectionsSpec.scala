package nebflow.agent

import nebflow.agent.PromptSections.*

class PromptSectionsSpec extends munit.FunSuite:

  // ============================================================
  // Tool-dependent sections
  // ============================================================

  test("AskUserQuestion section included only when tool is available"):
    val withTool = PromptContext(availableTools = Set("AskUserQuestion", "Read"))
    val withoutTool = PromptContext(availableTools = Set("Read"))
    val blocksWith = buildConditionalBlocks(withTool)
    val blocksWithout = buildConditionalBlocks(withoutTool)
    assert(blocksWith.contains("## Asking the User"), "AskUser section should be included when tool is available")
    assert(!blocksWithout.contains("## Asking the User"), "AskUser section should NOT be included when tool is missing")

  test("AskUserQuestion section excluded for sub-agents (depth > 0)"):
    // Sub-agents have AskUserQuestion stripped from their tool set by SubagentBlockedTools
    val subAgentCtx = PromptContext(availableTools = Set("Read"), depth = 1)
    val blocks = buildConditionalBlocks(subAgentCtx)
    assert(!blocks.contains("## Asking the User"))

  test("Read live-update section included when Read tool is available"):
    val ctx = PromptContext(availableTools = Set("Read", "Write"))
    val blocks = buildConditionalBlocks(ctx)
    assert(
      blocks.contains("## Read Tool — Live Results"),
      "Read section should be included when Read tool is available"
    )

  test("Read live-update section excluded when Read tool is missing"):
    val ctx = PromptContext(availableTools = Set("Write", "Grep"))
    val blocks = buildConditionalBlocks(ctx)
    assert(!blocks.contains("## Read Tool"), "Read section should NOT be included when Read tool is missing")

  test("Visual reporting section included when Pop tool is available"):
    val ctx = PromptContext(availableTools = Set("Pop", "Read"))
    val blocks = buildConditionalBlocks(ctx)
    assert(blocks.contains("## Visual Reporting"), "Visual reporting section should be included when Pop is available")
    assert(blocks.contains("matplotlib"), "should mention professional tools")
    assert(blocks.contains("SVG"), "should mention SVG format")

  test("Visual reporting section excluded when Pop tool is missing"):
    val ctx = PromptContext(availableTools = Set("Read", "Write", "Bash"))
    val blocks = buildConditionalBlocks(ctx)
    assert(!blocks.contains("## Visual Reporting"), "Visual reporting section should NOT be included when Pop is missing")

  // ============================================================
  // Feature-flag sections
  // ============================================================

  test("Voice section included when voiceEnabled"):
    val ctx = PromptContext(voiceEnabled = true, envInfo = "## Environment\n\ntest")
    val blocks = buildConditionalBlocks(ctx)
    assert(blocks.contains("## Voice Output"))

  test("Voice section excluded when voice muted"):
    val ctx = PromptContext(voiceEnabled = false, envInfo = "## Environment\n\ntest")
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
      envInfo = "## Environment",
      deviceInfo = "device-list",
      hasDevices = true,
      agentSessionsText = "# Active Sessions",
      hasActiveSessions = true,
      language = Some("English"),
      skillCatalog = "# Skills",
      rulesMd = Some("rule1")
    )
    val blocks = buildConditionalBlocks(ctx)
    val envIdx = blocks.indexOf("## Environment")
    val askIdx = blocks.indexOf("## Asking the User")
    val readIdx = blocks.indexOf("## Read Tool")
    val popIdx = blocks.indexOf("## Visual Reporting")
    val voiceIdx = blocks.indexOf("## Voice Output")
    val devicesIdx = blocks.indexOf("# Devices")
    val sessionsIdx = blocks.indexOf("# Active Sessions")
    val langIdx = blocks.indexOf("# Language")
    val skillsIdx = blocks.indexOf("# Skills")
    val rulesIdx = blocks.indexOf("## Project Rules")

    assert(envIdx < askIdx, "Environment should come before AskUser")
    assert(askIdx < readIdx, "AskUser should come before Read")
    assert(readIdx < popIdx, "Read should come before Visual Reporting")
    assert(popIdx < voiceIdx, "Visual Reporting should come before Voice")
    assert(voiceIdx < devicesIdx, "Voice should come before Devices")
    assert(devicesIdx < sessionsIdx, "Devices should come before Sessions")
    assert(sessionsIdx < langIdx, "Sessions should come before Language")
    assert(langIdx < skillsIdx, "Language should come before Skills")
    assert(skillsIdx < rulesIdx, "Skills should come before Rules")

  // ============================================================
  // Empty context
  // ============================================================

  test("minimal context with voice disabled produces empty output"):
    val ctx = PromptContext(voiceEnabled = false)
    val blocks = buildConditionalBlocks(ctx)
    assert(blocks.isEmpty)

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

end PromptSectionsSpec
