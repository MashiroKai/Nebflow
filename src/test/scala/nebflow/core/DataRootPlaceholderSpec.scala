package nebflow.core

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
      "MemoryEditTool" -> nebflow.core.tools.MemoryEditTool.description,
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
      nebflow.core.tools.MemoryEditTool.description.contains(s"$rendered/User.md"),
      "MemoryEdit 必须教 user 目标文件（默认 home 下 = 旧字面 ~/.nebflow/User.md）"
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

