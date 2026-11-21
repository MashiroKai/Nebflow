package nebflow.agent

import nebflow.neblink.core.*

import nebflow.agent.PromptSections.*
import nebflow.shared.PathUtil

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

  test("same-order file sections use filename order"):
    withIsolatedDataRoot {
      val dir = PathUtil.dataRoot / "prompts" / "sections"
      os.makeDir.all(dir)
      os.write(dir / "z.md", "<!-- Order: 42 -->\nZ-section")
      os.write(dir / "a.md", "<!-- Order: 42 -->\nA-section")
      val blocks = buildConditionalBlocks(PromptContext())
      assert(blocks.contains("A-section") && blocks.contains("Z-section"))
      assert(blocks.indexOf("A-section") < blocks.indexOf("Z-section"))
    }

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
  //
  // 停教批（2026-11-21 作者令，作者 10:08「按你推荐」）：显示/TTS 面封存期间
  // 提示词不再教 `<voice>` 块 ⇒ order-500 槽位不再挂载，判据从「按 flag 两态」
  // 翻转为「latch 恒停教」。段文本本体 `voiceSection` 仍在（可逆恢复面）。
  // ============================================================

  test("Voice section NOT injected while voice teaching is parked (even with voiceEnabled = true)"):
    withIsolatedDataRoot {
      val blocks = buildConditionalBlocks(PromptContext(voiceEnabled = true))
      assert(!blocks.contains("## Voice Output"), s"停教态下 voice 段不得注入：$blocks")
      assert(!blocks.contains("<voice></voice>"), s"停教态下不得教 `<voice>` 标签：$blocks")
      assert(PromptSections.voiceSection.contains("## Voice Output"), "段文本本体保留（恢复语音功能时重新挂载）")
    }

  test("Voice section excluded when voice muted"):
    val ctx = PromptContext(voiceEnabled = false)
    val blocks = buildConditionalBlocks(ctx)
    assert(!blocks.contains("## Voice Output"))

  test("数据根同序覆盖文件（prompts/sections/voice.md）在停教态下同样不挂载"):
    withIsolatedDataRoot {
      val dir = PathUtil.dataRoot / "prompts" / "sections"
      os.makeDir.all(dir)
      os.write(
        dir / "voice.md",
        "<!-- Condition: voiceEnabled is true -->\n<!-- Order: 500 -->\n## Voice Output\n\noverride body"
      )
      val blocks = buildConditionalBlocks(PromptContext(voiceEnabled = true))
      assert(!blocks.contains("override body"), s"覆盖文件不得绕过停教：$blocks")
      assert(!blocks.contains("## Voice Output"), s"覆盖文件不得绕过停教：$blocks")
    }

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
    val devicesIdx = blocks.indexOf("# Devices")
    val sessionsIdx = blocks.indexOf("# Active Sessions")
    val langIdx = blocks.indexOf("# Language")
    val skillsIdx = blocks.indexOf("# Skills")
    val rulesIdx = blocks.indexOf("## Project Rules")

    // 停教批后 order-500 voice 槽位不再挂载 ⇒ 段序从 Devices 起读。
    assert(!blocks.contains("## Voice Output"), "停教态下 voice 段不参与段序")
    assert(devicesIdx >= 0, "Devices section should be present")
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
  // stripCommentOnlyLines（govmemory 批：seed 批源注记 = 单行 HTML 注释，
  // 方案 §1.2 步骤 1「避免进入模型上下文」的机械保证）
  // ============================================================

  test("stripCommentOnlyLines removes whole-line HTML comments, keeps all other bytes"):
    // CRLF 无关化：Windows checkout（autocrlf=true）下三引号串携带 \r，
    // 期望值按 LF 断言 —— 被测契约是「注释行剥、其余字节过」，非行尾字节。
    val prompt =
      """<!-- cold-start authority note -->
        |<!-- batch source: dual-track batch -->
        |Deliver in two parts.
        |
        |You are a task node.""".stripMargin.replace("\r\n", "\n")
    val stripped = stripCommentOnlyLines(prompt)
    assert(!stripped.contains("authority note"), "annotation line stripped")
    assert(!stripped.contains("batch source"), "annotation line stripped")
    assertEquals(stripped, "Deliver in two parts.\n\nYou are a task node.")

  test("stripCommentOnlyLines is byte-identical for comment-free input (trailing newline survives)"):
    val prompt = "Line one.\n\nLine two.\n"
    assertEquals(stripCommentOnlyLines(prompt), prompt)

  test("stripCommentOnlyLines keeps inline comments inside a content line (conservative)"):
    val prompt = "HTML example: <!-- not an annotation line --> stays."
    assertEquals(stripCommentOnlyLines(prompt), prompt)

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
  // System prompt assembly uses the packaged resource, not the data root.
  // ============================================================

  test("assembly prepends the classpath resource and ignores external overrides"):
    withIsolatedDataRoot {
      os.write(PathUtil.dataRoot / "prompt-prefix.md", "EXTERNAL OVERRIDE")
      val stream = getClass.getResourceAsStream("/seed/prompt-prefix.md")
      assert(stream != null, "packaged prefix resource must exist")
      val source = scala.io.Source.fromInputStream(stream, "UTF-8")
      val resource =
        try source.mkString.trim
        finally source.close()
      val prompt = assembleSystemPrompt("AGENT-MD", "CONDITIONAL")
      assertEquals(prompt, s"--- SYSTEM PREFIX ---\n$resource\n\nAGENT-MD\n\nCONDITIONAL")
      assert(!prompt.contains("EXTERNAL OVERRIDE"))
      val withoutBlocks = assembleSystemPrompt("AGENT-MD", "")
      assertEquals(withoutBlocks, s"--- SYSTEM PREFIX ---\n$resource\n\nAGENT-MD")
    }

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

  // ============================================================
  // Report block discipline (output 块注入链核验批 2026-10-31):
  // order-375 独立段，逐字承载 Defaults.OutputBlockRule，root-only。
  // 钉因：注入链核验实证 Voice 节单 bullet 已在根上下文（builtin 代码解析 +
  // 重启 lifecycle 重建），但根回复零包裹、模型自述「规则没进我
  // 的上下文」——显著性不足；本段为引擎编译注入的存在性保证。
  //
  // report 块可见性批（作者令 2026-11-20）：`outputBlock.enabled` 总闸**整体
  // 退役**（设计稿 proposal 逐字「retired ... rather than allowing a full-text
  // leak」）。判据从「两态参数化」翻转为**恒注入**：段条件只剩 isRootAgent，
  // 配置里写不写那个键都不影响组装——「关 ⇒ 全文泄漏」的回退面已不存在。
  // ============================================================

  test("Report discipline section injected for root agent (order 375, verbatim OutputBlockRule)"):
    withIsolatedDataRoot {
      val ctx = PromptContext(isRootAgent = true)
      val blocks = buildConditionalBlocks(ctx)
      assert(
        blocks.contains(nebflow.shared.Defaults.OutputBlockRule),
        "root agent systemStable must carry the report rule as its own section"
      )
      assert(blocks.contains("<report>...</report>"), "section body is the paired-tag rule text")
      // 段序钉：必须排在 order-370 根路由纪律段之后、order-500 voice 段之前
      assert(
        blocks.indexOf("## Routing discipline (root agent)") < blocks.indexOf("── Report discipline ──"),
        "report discipline section must follow the root routing discipline section (order 375 > 370)"
      )
    }

  test("Retired kill switch: the section is injected whether or not `outputBlock.enabled` exists in config"):
    withIsolatedDataRoot {
      val cfg = PathUtil.configJsonReadPath(PathUtil.dataRoot)
      os.write(cfg, """{"outputBlock":{"enabled":false}}""", createFolders = true)
      val blocks = buildConditionalBlocks(PromptContext(isRootAgent = true))
      assert(
        blocks.contains(nebflow.shared.Defaults.OutputBlockRule),
        "the retired gate key must NOT be able to suppress the section (no full-text-leak fallback face)"
      )
      assert(blocks.contains("── Report discipline ──"), "the section header is present regardless of config")
    }

  test("Report discipline section NOT injected for non-root agents (root scoping holds)"):
    withIsolatedDataRoot {
      val dispatcher = buildConditionalBlocks(PromptContext(isRootAgent = false))
      assert(
        !dispatcher.contains(nebflow.shared.Defaults.OutputBlockRule),
        "dispatcher/node sessions carry the rule via brief/footnote faces, not the systemStable section"
      )
      val nodeCtx = PromptContext(isRootAgent = false, availableTools = Set("NodeEdit"))
      val node = buildConditionalBlocks(nodeCtx)
      assert(
        !node.contains(nebflow.shared.Defaults.OutputBlockRule),
        "flow-node sessions must not receive the root-only section"
      )
    }

  test("joined root prompt carries the report rule once from the section, and teaches no `<output>` tag"):
    withIsolatedDataRoot {
      val base = nebflow.core.entity.BuiltinAgents.entry("Nebula").map(_.systemPrompt).getOrElse("")
      val joined = PromptSections.assembleSystemPrompt(base, buildConditionalBlocks(PromptContext(isRootAgent = true)))
      assert(joined.contains(nebflow.shared.Defaults.OutputBlockRule), "order-375 section in the assembly")
      assert(joined.contains("User-visible output: wrap everything"), "Voice bullet in the base prompt")
      assert(joined.contains("<report>...</report>"), "the paired report tag is the only taught form")
      assert(
        !joined.contains("<output>"),
        s"the retired tag must not be taught anywhere: ${joined.linesIterator.filter(_.contains("output")).mkString("\n")}"
      )
    }

  // 停教批（2026-11-21 作者令，作者 10:08「按你推荐」）：**组装面**判据——
  // 显示/TTS 面封存期间，真实组装出来的根提示词（基座 + 条件段）不含 `<voice>` 教段。
  // 断言读的是组装路径上的那份文本（与 [[NebulaPromptOutputStyleSpec]] 同一口径），
  // 不是源文件里那个仍保留的段常量。
  test("assembled root prompt teaches no `<voice>` block while voice teaching is parked"):
    withIsolatedDataRoot {
      val base = nebflow.core.entity.BuiltinAgents.entry("Nebula").map(_.systemPrompt).getOrElse("")
      val joined = PromptSections.assembleSystemPrompt(base, buildConditionalBlocks(PromptContext(isRootAgent = true)))
      assert(!joined.contains("## Voice Output"), s"order-500 段不得出现在组装结果里：$joined")
      assert(!joined.contains("<voice></voice>"), "组装结果不得教 `<voice></voice>` 标签")
      assert(!joined.contains("played as audio, stripped from the display"), "教段判据句不得出现")
      // 反面：段文本本体仍在源码里（可逆恢复面），只是未挂载。
      assert(PromptSections.voiceSection.contains("## Voice Output"), "voiceSection 常量本体保留")
    }

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

  // ── 台账 #913 ②：设备标注行形（renderLinesWithDevice）──────────────

  test(
    "MountedProjectList.renderLinesWithDevice: cross-device rows carry (on device X); local rows keep the plain shape"
  ):
    val lines = MountedProjectList.renderLinesWithDevice(
      List(
        ("stand-kai", "KAI", Some("主仓")),
        ("general", "", None)
      )
    )
    assertEquals(lines, "- general\n- stand-kai (on device KAI): 主仓")

  test("MountedProjectList.renderLinesWithDevice: empty list → explicit placeholder (same as renderLines)"):
    assertEquals(MountedProjectList.renderLinesWithDevice(Nil), MountedProjectList.EmptyText)

  // ============================================================
  // SendMessage/Pop 边界（渠道文件出站+工具说明批 2026-10-31，作者 22:10 令
  // + 22:30 追加令）：order-376 独立段，逐字承载 Defaults.SendMessageScopeRule，
  // root-only。追加令明示「不能只进画像记忆，要改在提示词源」——order-375
  // （OutputBlockRule）同款存在性保证机制。
  // ============================================================

  test("SendMessage scope section injected for root agent (order 376, verbatim SendMessageScopeRule)"):
    withIsolatedDataRoot {
      val ctx = PromptContext(isRootAgent = true)
      val blocks = buildConditionalBlocks(ctx)
      assert(
        blocks.contains(nebflow.shared.Defaults.SendMessageScopeRule),
        "root agent systemStable must carry the SendMessage scope rule as its own section"
      )
      assert(blocks.contains("── SendMessage scope ──"), "section body is the scope-rule text")
      // 段序钉：必须排在 order-375 report 块纪律段之后
      assert(
        blocks.indexOf("── Report discipline ──") < blocks.indexOf("── SendMessage scope ──"),
        "SendMessage scope section must follow the report discipline section (order 376 > 375)"
      )
    }

  test("SendMessage scope section NOT injected for non-root agents (dispatcher/node sessions)"):
    withIsolatedDataRoot {
      val dispatcher = buildConditionalBlocks(PromptContext(isRootAgent = false))
      assert(
        !dispatcher.contains(nebflow.shared.Defaults.SendMessageScopeRule),
        "dispatcher/node sessions carry no SendMessage tool at all — the root-only section must not leak"
      )
      val nodeCtx = PromptContext(isRootAgent = false, availableTools = Set("NodeEdit"))
      val node = buildConditionalBlocks(nodeCtx)
      assert(
        !node.contains(nebflow.shared.Defaults.SendMessageScopeRule),
        "flow-node sessions must not receive the root-only section"
      )
    }

  // ============================================================
  // 密钥教义（密钥能力三件套批 B 件，作者令 2026-11-01「agent/分发器提示词加
  // 一节『密钥的使用与创建』」）：order-377 **always** 段，逐字承载
  // Defaults.SecretsRule。与 375/376 的 root-only 刻意不同：凭证经任务文本
  // 流进每张面（分发器写 brief、节点 Bash 消费），纪律必须在每张面上。
  // ============================================================

  test("Secrets discipline section injected for EVERY agent session (order 377, always, verbatim SecretsRule)"):
    withIsolatedDataRoot {
      val root = buildConditionalBlocks(PromptContext(isRootAgent = true))
      val dispatcher = buildConditionalBlocks(PromptContext(isRootAgent = false))
      val node = buildConditionalBlocks(
        PromptContext(isRootAgent = false, availableTools = Set("NodeEdit"))
      )
      for (who, blocks) <- Seq("root" -> root, "dispatcher" -> dispatcher, "node" -> node) do
        assert(
          blocks.contains(nebflow.shared.Defaults.SecretsRule),
          s"$who session systemStable must carry the secrets discipline section"
        )
        assert(blocks.contains("── Secrets (credentials) ──"), s"$who: section body is the secrets rule text")
      // 段序钉：紧跟 order-376 SendMessage 边界段之后
      assert(
        root.indexOf("── SendMessage scope ──") < root.indexOf("── Secrets (credentials) ──"),
        "secrets section must follow the SendMessage scope section (order 377 > 376)"
      )
      // 条款面齐：创建 / 消费 / 铁律三 clauses 都在段体里（教义条款化）
      val body = nebflow.shared.Defaults.SecretsRule
      assert(body.contains("CREATE") && body.contains("CONSUME") && body.contains("IRON RULES"), body)
    }

  test("Secrets discipline section is a static always-on section (stable-prefix reading)"):
    val s377 = PromptSections.all.filter(_.order == 377)
    assertEquals(s377.size, 1, "registry carries exactly one order-377 section")
    val sec = s377.head
    assert(sec.shouldInclude(PromptContext()), "always: included on the minimal context")
    assert(
      sec.shouldInclude(PromptContext(isRootAgent = true, voiceEnabled = false)),
      "always: included on any context"
    )
    assertEquals(sec.render(PromptContext()), nebflow.shared.Defaults.SecretsRule, "static body = the constant")

  // ============================================================
  // 引牌教义·带短名引用（msgchip 批，作者令 2026-11-01「消息身份牌系统升级」
  // 第 4 条）：order-378 **always** 段，逐字承载 Defaults.MsgQuoteRule。与
  // order-377 同判据（牌流进每张面）；随下个生命周期生效（systemStable）。
  // ============================================================

  test("Msg quote doctrine section injected for EVERY agent session (order 378, always, verbatim MsgQuoteRule)"):
    withIsolatedDataRoot {
      val root = buildConditionalBlocks(PromptContext(isRootAgent = true))
      val dispatcher = buildConditionalBlocks(PromptContext(isRootAgent = false))
      for (who, blocks) <- Seq("root" -> root, "dispatcher" -> dispatcher) do
        assert(
          blocks.contains(nebflow.shared.Defaults.MsgQuoteRule),
          s"$who session systemStable must carry the msg quote doctrine section"
        )
        assert(blocks.contains("── Quote with message cards"), s"$who: section body is the msg quote rule text")
      // 段序钉：紧跟 order-377 Secrets 段之后
      assert(
        root.indexOf("── Secrets (credentials) ──") < root.indexOf("── Quote with message cards"),
        "msg quote section must follow the secrets section (order 378 > 377)"
      )
      // 条款面齐：牌+短名形态 / 逐条引用 / 不整段复述 三要素都在段体里
      val body = nebflow.shared.Defaults.MsgQuoteRule
      assert(
        body.contains("{{msg:<sid8>-<seq>|<short-name>}}") && body.contains("cites each one separately")
          && body.contains("do NOT restate the original text"),
        body
      )
    }

  test("Msg quote doctrine section is a static always-on section (stable-prefix reading)"):
    val s378 = PromptSections.all.filter(_.order == 378)
    assertEquals(s378.size, 1, "registry carries exactly one order-378 section")
    val sec = s378.head
    assert(sec.shouldInclude(PromptContext()), "always: included on the minimal context")
    assertEquals(sec.render(PromptContext()), nebflow.shared.Defaults.MsgQuoteRule, "static body = the constant")

  // ============================================================
  // 时间纪律（时间感知修复批，作者指令 2026-11-05 18:48「修复你的时间感知问题」）：
  // order-379 **always** 段，逐字承载 Defaults.TimeDisciplineRule。
  // 与 order-377/378 同判据（相对时间误判不分角色）；静态体落 systemStable，
  // 而**锚**（「现在」的值）绝不进 stable（写进去 = 时间冻结）。
  // ============================================================

  test("Time discipline section injected for EVERY agent session (order 379, always, verbatim TimeDisciplineRule)"):
    withIsolatedDataRoot {
      val root = buildConditionalBlocks(PromptContext(isRootAgent = true))
      val dispatcher = buildConditionalBlocks(PromptContext(isRootAgent = false))
      val child = buildConditionalBlocks(PromptContext(depth = 1))
      for (who, blocks) <- Seq("root" -> root, "dispatcher" -> dispatcher, "child" -> child) do
        assert(
          blocks.contains(nebflow.shared.Defaults.TimeDisciplineRule),
          s"$who session systemStable must carry the time discipline section"
        )
        assert(blocks.contains("── Time discipline (iron rule) ──"), s"$who: section body is the rule text")
      // 段序钉：紧跟 order-378 引牌段之后
      assert(
        root.indexOf("── Quote with message cards") < root.indexOf("── Time discipline (iron rule) ──"),
        "time discipline section must follow the msg quote section (order 379 > 378)"
      )
      // 「禁止无依据的日期推断」+「允许引用时间锚」两条都在段体里（规则原文面）
      val body = nebflow.shared.Defaults.TimeDisciplineRule
      assert(body.contains("MUST be derived from that anchor"), body)
      assert(body.contains("Never from memory, never from feeling, never from a training prior"), body)
      assert(body.contains("you may NOT state an absolute date"), body)
      assert(body.contains("epoch_ms"), "让模型用精确底座换算，而不是凭印象")
    }

  test("Time discipline section is a static always-on section carrying NO timestamp (stable-prefix reading)"):
    val s379 = PromptSections.all.filter(_.order == 379)
    assertEquals(s379.size, 1, "registry carries exactly one order-379 section")
    val sec = s379.head
    assert(sec.shouldInclude(PromptContext()), "always: included on the minimal context")
    val rendered = sec.render(PromptContext())
    assertEquals(rendered, nebflow.shared.Defaults.TimeDisciplineRule, "static body = the constant")
    // 🔴 反面：纪律是静态文本，任何真实时间戳都不许出现在段体里（冻结面剥离）
    assert(
      raw"\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} [+-]\d{2}:\d{2}".r.findFirstIn(rendered).isEmpty,
      s"the stable time-discipline section must not embed a timestamp: $rendered"
    )

  // ============================================================
  // 文档溯源规范（order 350，always 段；作者 2026-09-11 裁定 / 方案 #165）
  //
  // 系统提示词 = 唯一权威面。运行时（~/.nebflow/**）9 处本地重述删除由
  // 并行节点负责、存量文档（CONVENTIONS.md）按作者裁定冻结——本 spec 只
  // 钉提示词面的三条不变量：恒注入、位置（env 后 / voice 前）、不被剥离。
  // ============================================================

  /** 正文行（英文批 prompt-en-slim-impl：中→英；语义与判据逐条不变）。 */
  private val traceBodyLine =
    "- Provenance goes only into the filename tail: stage doc `<topic>__<YYYYMMDD>_<HHMMSS>[__<chainId>].md` (topic first, time after; no suffix when unattributed); the body carries zero metadata header."

  test("文档溯源段恒注入（最小 PromptContext，order 350）：段名 + 正文行逐字"):
    withIsolatedDataRoot {
      val blocks = buildConditionalBlocks(PromptContext())
      assert(blocks.contains("## Document provenance"), s"order-350 段必须恒注入（always）：$blocks")
      assert(blocks.contains(traceBodyLine), s"正文行必须逐字出现：$blocks")
    }

  test("文档溯源段位于 env(order 100) 之后（停教态 order-500 voice 槽位不挂载）"):
    withIsolatedDataRoot {
      // 隔离数据根内自建 order-100 env 文件段（沿用 envInfoSection 用例写法）。
      val envDir = PathUtil.dataRoot / "prompts" / "sections" / "environment"
      os.makeDir.all(envDir)
      os.write.over(envDir / "condition.json", """{"order": 100, "condition": "always"}""")
      os.write.over(envDir / "prompt.md", "## Environment\n\n| Chat width | 1024px |")

      val blocks = buildConditionalBlocks(PromptContext(voiceEnabled = true))
      val envIdx = blocks.indexOf("## Environment")
      val traceIdx = blocks.indexOf("## Document provenance")
      assert(envIdx >= 0, s"env 段应存在：$blocks")
      assert(traceIdx >= 0, s"溯源段应存在：$blocks")
      assert(envIdx < traceIdx, "溯源段必须在 env(100) 之后")
      // 停教批（2026-11-21 作者令）翻转前锚：「voice 段应存在」+「溯源段在 voice(500) 之前」。
      assert(!blocks.contains("## Voice Output"), s"停教态下 voice 段不挂载：$blocks")

      // 读数：注册表 order 分布——env 文件段(100) < 溯源(350)；voice(500) 停教不挂载。
      val orders = PromptSections.all.map(_.order).sorted
      assertEquals(orders.head, 100, s"env(100) 仍是首个段：$orders")
      assert(orders.contains(350), s"注册表须含 order 350：$orders")
      assert(!orders.contains(PromptSections.VoiceSectionOrder), s"停教态注册表不含 voice(500)：$orders")
      assert(orders.indexOf(350) > orders.indexOf(100), s"350 在 100 之后：$orders")
    }

  test("文档溯源段是静态体（无动态字段）——落在稳定前缀的读数依据"):
    withIsolatedDataRoot {
      val s350 = PromptSections.all.filter(_.order == 350)
      assertEquals(s350.size, 1, "注册表中恰一个 order-350 段")
      val sec = s350.head
      assertEquals(sec.content, PromptSections.traceSection, "静态体 = traceSection 常量")
      assertEquals(sec.render(PromptContext()), PromptSections.traceSection, "render 不依赖 PromptContext")
      assertEquals(
        sec.content,
        sec.render(PromptContext(agentCategory = "team", isRootAgent = true, voiceEnabled = false)),
        "任意上下文渲染结果相同 ⇒ 无动态字段"
      )
    }

  test("文档溯源段不被 stripAllMigrated 剥掉"):
    withIsolatedDataRoot {
      // CRLF 无关化：Windows checkout（autocrlf=true）会让源码三引号串携带 \r，
      // linesIterator 系剥离会归一掉 \r —— 测的是「段不被剥」，不是行尾字节。
      val trace = PromptSections.traceSection.replace("\r\n", "\n")
      // stripAllMigrated 不剥离文档溯源段。
      assertEquals(stripAllMigrated(trace), trace)

      // 条件块在 stripAllMigrated 之后拼接，故溯源段必达。
      val rawSystemMd =
        """## Team Workflow
          |
          |## Teams & Flows
          |
          |- Mail 你的团队 Lead 汇报进度。
          |
          |## Domain Knowledge
          |
          |- 保持最小改动。""".stripMargin
      val teamPrompt = assembleSystemPrompt(
        stripAllMigrated(rawSystemMd),
        buildConditionalBlocks(
          PromptContext(
            agentCategory = "team",
            agentName = "Backend",
            isTeamLead = false,
            guardrailsOn = true,
            voiceEnabled = false
          )
        )
      )
      assert(teamPrompt.contains("## Document provenance"), s"team 路径不得剥掉溯源段：$teamPrompt")
      assert(teamPrompt.contains(traceBodyLine), s"team 路径正文行必达：$teamPrompt")
    }

  // order-360 段（node terminal report 兜底段）已随终态申报工具通道整体退役删除：
  // 终态协议 = 引擎贡献面单点（`NodeEngine.ProtocolFootnote`，完成点文本锚定），
  // 系统提示词面的常驻兜底段不再是权威（防双权威分叉）。本文件原「order-360 段长门
  // + wrap-up 铁律」用例随之移除；权威面协议文法由 NodeEngineContract 相关 spec 判定。

end PromptSectionsSpec
