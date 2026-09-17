package nebflow.core

import java.nio.file.{Files, Paths}
import java.nio.file.attribute.FileTime
import java.security.MessageDigest

/**
 * Data-root render layer（home 硬编码 → 运行时动态化批，2026-09-11）回归门。
 *
 * 钉住三条不可回退的语义（改动前的主实例行为 = 字节不变的基准）：
 *  1. 默认 home ⇒ 渲染值恰为 `~/<homeDirName>`（当前品牌 `~/.nebflow`）—— 主实例
 *     提示词/工具描述逐字节不变（P2-b 关键回归守卫）；
 *  2. 非默认 home（隔离实例 `--home` / 测试换根）⇒ 渲染值为该 root 的绝对路径；
 *  3. `substituteDataRoot` 是纯文本变换：无占位符的文本逐字节透传（改前写成的
 *     `~/.nebflow` 字面不会被改写），`{{data_root}}` 被替换为渲染值。
 *
 * 工具描述侧另钉一点：描述里的路径 token 必须在**运行期**取渲染值（val 会在对象
 * 初始化时冻结，换根后失真）——同一 JVM 内其它 suite 会重定向 dataRoot（串行执行），
 * 故此处断言「自洽形态」（描述里的值 == 当前 dataRootRenderValue），默认档与旧字面
 * 同形由 renderDataRootValue 纯函数用例钉住。
 */
class DataRootPlaceholderSpec extends munit.FunSuite:

  private val DefaultLiteral = "~/" + Branding.homeDirName

  private def sha256(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8")).map("%02x".format(_)).mkString

  test("P2-b: 默认 home 渲染为 ~/.nebflow 字面（主实例字节零变）"):
    assertEquals(
      PathUtil.renderDataRootValue(os.home / Branding.homeDirName),
      DefaultLiteral,
      "默认数据根必须渲染成 ~/<homeDirName>，否则主实例 system prompt 漂移"
    )

  test("P2-b: 非默认 home 渲染为 dataRoot 绝对路径（隔离实例）"):
    val isolated = os.Path("/tmp/hrd-probe-isolated")
    assertEquals(PathUtil.renderDataRootValue(isolated), "/tmp/hrd-probe-isolated")

  test("substituteDataRoot: {{data_root}} → 渲染值，其余字节零改动"):
    // 断言取「运行期渲染值」自洽形态：本 suite 与其它 suite 同 JVM 串行（build.sbt
    // Test / parallelExecution := false），另有 suite 会换根，故不钉死 ~/.nebflow —
    // 默认档由上面 renderDataRootValue 纯函数用例钉住。
    val before = "沙箱写根 = {{data_root}}：可写定义层/运维配置/记忆文件"
    assertEquals(
      PathUtil.substituteDataRoot(before),
      s"沙箱写根 = ${PathUtil.dataRootRenderValue}：可写定义层/运维配置/记忆文件"
    )

  test("substituteDataRoot: 无占位符文本逐字节透传（改前写成的 ~/.nebflow 字面不被改写）"):
    // 改前磁盘上的形态：字面 `~/.nebflow`（不含占位符）。默认 home 下新旧管线输出
    // 必须 sha256 相同 —— 这是「主实例零漂移」的机器可验形态。
    val legacy = List(
      "- 沙箱写根 = ~/.nebflow：可写定义层/运维配置/记忆文件",
      "缺口与后续小批见 ~/.nebflow/docs/Nebflow/20260910_node-notify-routing-audit.md。",
      "- 需要落盘时写 `~/.nebflow/docs/<域>/`，不落 /tmp。"
    )
    legacy.foreach { text =>
      val out = PathUtil.substituteDataRoot(text)
      assertEquals(sha256(out), sha256(text), s"改造前文本必须逐字节透传: $text")
      assertEquals(out, text)
    }

  test("提示词管线：stripAllMigrated → substituteDataRoot → assemble 对改前文本字节等价"):
    // 直接跑真实实现（AgentCore.buildSystemPrompt 的同序调用），不复制管线。
    val systemMdBefore = "# Nebula\n\n- 沙箱写根 = ~/.nebflow：可写定义层\n"
    val blocks = "## Environment\n\n| PID | 1 |"
    val legacyPath = nebflow.agent.PromptSections.assembleSystemPrompt(
      nebflow.agent.PromptSections.stripAllMigrated(systemMdBefore),
      blocks
    )
    val currentPath = nebflow.agent.PromptSections.assembleSystemPrompt(
      PathUtil.substituteDataRoot(nebflow.agent.PromptSections.stripAllMigrated(systemMdBefore)),
      blocks
    )
    assertEquals(
      sha256(currentPath),
      sha256(legacyPath),
      "默认 home 下改造前后 system prompt sha256 必须相等（主实例零漂移）"
    )

  test("工具描述：路径 token 运行期取渲染值（非对象初始化冻结的 val）"):
    val rendered = PathUtil.dataRootRenderValue
    val probes = List(
      "CardTool" -> nebflow.core.tools.CardTool.description,
      "MemoryNoteTool" -> nebflow.core.tools.MemoryNoteTool.description,
      "TaskListTool" -> nebflow.core.tools.TaskListTool.description,
      "LoadTool" -> nebflow.core.tools.LoadTool.description
    )
    probes.foreach { (name, desc) =>
      assert(
        desc.contains(rendered),
        s"$name description must interpolate PathUtil.dataRootRenderValue ($rendered) at call time"
      )
    }
    assert(
      nebflow.core.tools.CardTool.description.contains(s"$rendered/projects/<name>/"),
      "CardTool 必须教 workspace 路径形态（默认 home 下 = 旧字面 ~/.nebflow/projects/<name>/）"
    )
    assert(
      nebflow.core.tools.MemoryNoteTool.description.contains(s"$rendered/User.md"),
      "MemoryNote 必须教 user 目标文件（默认 home 下 = 旧字面 ~/.nebflow/User.md）"
    )
    assert(
      nebflow.core.tools.TaskListTool.description.contains(s"$rendered/tasks.json"),
      "TaskList 必须教存储路径（默认 home 下 = 旧字面 ~/.nebflow/tasks.json）"
    )
    assert(
      nebflow.core.tools.LoadTool.description.contains(s"$rendered/teams/<name>/team.json"),
      "Load 必须教 team 定义路径（默认 home 下 = 旧字面 ~/.nebflow/teams/<name>/team.json）"
    )

  test("BashTool 危险命令面：数据根本身仍在保护面内（保护面只增不减）"):
    assert(
      nebflow.core.tools.BashTool.isDangerous("rm -rf ~/.nebflow/agents"),
      "默认 home 字面形态必须仍判危险（旧正则的等价面）"
    )
    assert(
      nebflow.core.tools.BashTool.isDangerous(s"rm -rf $DefaultLiteral/projects"),
      "品牌名推导的默认形态必须仍判危险"
    )
    assert(
      nebflow.core.tools.BashTool.isDangerous(s"rm -rf ${PathUtil.dataRoot}/projects"),
      "当前数据根绝对路径必须判危险（隔离实例下护的对象）"
    )
    assert(
      !nebflow.core.tools.BashTool.isDangerous("ls -la"),
      "安全命令不被误判"
    )

  // ── Environment 段（prompt.md ↔ data.sh JSON 通道）消费侧 ────────────────────
  // 作者 2026-09-13 裁定（#303 U-1(a) 的处置）：Environment 段**加一行**
  // `{{data_root}}`（不删 data.sh 键）。上面 7 例只覆盖 substituteDataRoot 通道
  // （agent system.md / 插件注入块 / 分发器目录段）+ 工具描述 + BashTool 保护面，
  // **不含** Environment 段的 JSON 替换通道（PromptSections.scala:462-478 建段 →
  // :550-580 渲染）。下两例补该缺口：
  //  - 正控：该行渲染为**本实例 dataRoot 绝对路径**（值源 = renderWithScript 的
  //    env 表 `NEBFLOW_DATA_ROOT` ← PathUtil.dataRoot.toString，:559）且零残留；
  //  - 负控：供键缺失 ⇒ 占位符静默留原样、不抛异常（依据 :574-578：只对 JSON
  //    对象里**存在**的键做 replace，无残留检查、无 raiseError）。
  // 注意与 substituteDataRoot 通道的差异：后者默认 home 下渲染 `~/.nebflow` 字面
  // （renderDataRootValue），本通道恒为绝对路径 —— 同指一个目录，形态不同。
  test("Environment 段（data.sh JSON 通道）：{{data_root}} → 本实例数据根绝对路径，零占位符残留"):
    val prevRoot = PathUtil.dataRoot
    val tempRoot = os.pwd / "target" / "test-data-root-envline"
    val envDir = tempRoot / "prompts" / "sections" / "environment"
    try
      os.remove.all(tempRoot)
      os.makeDir.all(envDir)
      os.write.over(envDir / "condition.json", """{"order": 100, "condition": "always"}""")
      // fixture 与 home 实件同形（~/.nebflow/prompts/sections/environment/{prompt.md,data.sh}）
      os.write.over(
        envDir / "prompt.md",
        """## Environment
          |
          || Property | Value |
          ||----------|-------|
          || Working directory | `{{working_dir}}` |
          || Gateway port | {{gateway_port}} |
          || Data root | `{{data_root}}` |
          |""".stripMargin
      )
      os.write.over(
        envDir / "data.sh",
        """#!/usr/bin/env bash
          |set -euo pipefail
          |cat << JSON
          |{
          |  "working_dir": "$(pwd)",
          |  "gateway_port": "${NEBFLOW_GATEWAY_PORT:-8080}",
          |  "data_root": "${NEBFLOW_DATA_ROOT:-unknown}"
          |}
          |JSON
          |""".stripMargin
      )
      // fixture mtime 拨到极早：loadFileSectionsCached 按 max-mtime 判缓存，
      // 恢复真实 root 后（真实 mtime ≫ 1000）必然重读，不污染后续套件
      val old = FileTime.fromMillis(1000L)
      for p <- List("condition.json", "prompt.md", "data.sh") do
        Files.setLastModifiedTime(Paths.get((envDir / p).toString), old)

      PathUtil.setDataRoot(tempRoot)
      val env = nebflow.agent.PromptSections.envInfoSection(nebflow.agent.PromptSections.PromptContext())

      assert(
        env.contains(s"| Data root | `$tempRoot` |"),
        s"data_root 行必须渲染为**本实例**数据根绝对路径（值源 NEBFLOW_DATA_ROOT = PathUtil.dataRoot.toString，PromptSections.scala:559）：$env"
      )
      assert(!env.contains("{{"), s"渲染后不得残留任何占位符：$env")
    finally
      PathUtil.setDataRoot(prevRoot)
      os.remove.all(tempRoot)

  test("Environment 段负控：data.sh 缺 data_root 键 ⇒ 占位符静默留原样（既有语义，不抛异常）"):
    val prevRoot = PathUtil.dataRoot
    val tempRoot = os.pwd / "target" / "test-data-root-envline-nokey"
    val envDir = tempRoot / "prompts" / "sections" / "environment"
    try
      os.remove.all(tempRoot)
      os.makeDir.all(envDir)
      os.write.over(envDir / "condition.json", """{"order": 100, "condition": "always"}""")
      os.write.over(
        envDir / "prompt.md",
        "## Environment\n\n| Gateway port | {{gateway_port}} |\n| Data root | `{{data_root}}` |\n"
      )
      os.write.over(
        envDir / "data.sh",
        """#!/usr/bin/env bash
          |set -euo pipefail
          |echo "{\"gateway_port\": \"${NEBFLOW_GATEWAY_PORT:-8080}\"}"
          |""".stripMargin
      )
      val old = FileTime.fromMillis(1000L)
      for p <- List("condition.json", "prompt.md", "data.sh") do
        Files.setLastModifiedTime(Paths.get((envDir / p).toString), old)

      PathUtil.setDataRoot(tempRoot)
      val env = nebflow.agent.PromptSections.envInfoSection(nebflow.agent.PromptSections.PromptContext())

      // 既有语义（已知事实，非理想态）：缺键 ⇒ 静默留原样。若未来改为显式 WARN /
      // 抛错（更响的契约），本用例须同步改写 —— 断言的是「当前语义」而非「最佳语义」。
      assert(
        env.contains("| Data root | `{{data_root}}` |"),
        s"缺键时既有语义为静默透传（PromptSections.scala:574-578 只替换 JSON 中存在键），实测：$env"
      )
      assert(
        env.contains("| Gateway port | 8080 |"),
        s"同段其它键仍被正常替换（缺键不得拖垮整段渲染）：$env"
      )
    finally
      PathUtil.setDataRoot(prevRoot)
      os.remove.all(tempRoot)

