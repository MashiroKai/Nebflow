package nebflow.core.plugin

import cats.effect.IO
import munit.CatsEffectSuite
import nebflow.core.PathUtil

import scala.concurrent.duration.*

/**
 * 协议符合度批——mcp.json 协议 1.0.0 符合 spec（审计项 4 + Agent Plugins 1.0.0
 * §7.2.1/§7.2.2/§9，官方 mcp.schema.json 闭合变体）。
 *
 * 覆盖：
 * - §7.2.1 组件级：$schema 缺失/非 canonical、未知顶层字段 → 组件 invalid 或
 *   告警+忽略（插件继续装载，§6.2 边界）
 * - §7.2.2 entry 级（隔离边界：单条违规只跳该条）：type 必填 ∈
 *   stdio|streamable-http|sse；stdio command 单 token + ./ 相对解析+围栏；
 *   env 禁声明 PLUGIN_ROOT/PLUGIN_DATA（§9.1）；cwd 形态；url 语义；
 *   sse = 客户端未实现 transport → skip+报告（OPTIONAL，§7.2.2-4）
 */
class PluginMcpProtocolSpec extends CatsEffectSuite:

  override val munitIOTimeout: FiniteDuration = 60.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-plugin-mcp-proto"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "plugins")
  os.write.over(tempRoot / "nebflow.json", "{}")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  private val PR = "${PLUGIN_ROOT}" // 纯字符串字面量（无 s-插值），占位符原文
  private val PD = "${PLUGIN_DATA}"

  private def mkPlugin(name: String, mcpJson: String, skills: Boolean = true): Unit =
    val d = tempRoot / "plugins" / name
    os.makeDir.all(d)
    os.write.over(d / "plugin.json",
      s"""{"$$schema":"${PluginRegistry.CanonicalSchema}","name":"$name","version":"1.0.0","description":"$name"}""")
    if skills then
      os.makeDir.all(d / "skills" / "s")
      os.write.over(d / "skills" / "s" / "SKILL.md",
        """---
          |name: s
          |description: fixture
          |---
          |body""".stripMargin)
    os.write.over(d / "mcp.json", mcpJson)

  /** servers 段拼装。schema="" → 不写 $schema（测缺失形态）。 */
  private def mcp(servers: String, schema: String = PluginRegistry.CanonicalMcpSchema, extraTop: String = ""): String =
    val top = if extraTop.isEmpty then "" else "," + extraTop
    if schema.isEmpty then "{\"mcpServers\":{" + servers + "}" + top + "}"
    else "{\"$schema\":\"" + schema + "\",\"mcpServers\":{" + servers + "}" + top + "}"

  private def loaded(name: String): IO[Option[PluginRegistry.PluginDef]] =
    PluginRegistry.scan().map(_.find(_.name == name))

  private def warningsOf(name: String): IO[String] =
    loaded(name).map {
      case Some(p) => p.warnings.mkString("; ")
      case None    => fail(s"$name not loaded")
    }

  // ── §7.2.1 组件级 ─────────────────────────────────────

  test("§7.2.1: mcp.json 缺 $schema → MCP 组件 invalid（插件继续装载，无 MCP）") {
    mkPlugin("mcp-noschema", mcp("\"a\":{\"type\":\"stdio\",\"command\":\"python3\"}", schema = ""))
    loaded("mcp-noschema").map {
      case Some(p) =>
        assertEquals(p.mcpServers.isEmpty, true, "MCP component must be invalid → no servers")
        assert(p.warnings.exists(_.contains("$schema")), s"got: ${p.warnings}")
      case None => fail("plugin must continue loading without MCP")
    }
  }

  test("§7.2.1: mcp.json 非 canonical $schema → 组件 invalid；未知顶层字段 → 告警+忽略（继续）") {
    mkPlugin("mcp-wrongschema", mcp("\"a\":{\"type\":\"stdio\",\"command\":\"python3\"}",
      schema = "https://example.com/mcp.schema.json"))
    mkPlugin("mcp-extra", mcp("\"a\":{\"type\":\"stdio\",\"command\":\"python3\"}", extraTop = "\"notes\":\"hi\""))
    for w1 <- warningsOf("mcp-wrongschema"); w2 <- warningsOf("mcp-extra")
    yield
      assert(w1.contains("canonical"), s"non-canonical must invalidate the component: $w1")
      assert(w2.contains("notes") && w2.contains("forward-compat"), s"unknown top-level must warn+ignore: $w2")
  }

  // ── §7.2.2 entry 级（隔离边界）───────────────────────────

  test("§7.2.2: 未知 type / 缺 type → 仅该 entry 跳过；同文件好 entry 照常装载") {
    mkPlugin("entry-isolation", mcp(
      "\"bad\":{\"type\":\"carrier-pigeon\",\"command\":\"x\"}," +
        "\"notype\":{\"command\":\"x\"}," +
        "\"good\":{\"type\":\"stdio\",\"command\":\"python3\"}"))
    loaded("entry-isolation").map {
      case Some(p) =>
        assertEquals(p.mcpServers.keySet, Set("good"), "invalid entries skipped, valid entry loads (§7.2.2)")
        val w = p.warnings.mkString("; ")
        assert(w.contains("carrier-pigeon") && w.contains("bad"), s"got: ${p.warnings}")
        assert(w.contains("missing required 'type'"), s"got: ${p.warnings}")
      case None => fail("plugin must load")
    }
  }

  test("§7.2.2-4: transport 'sse'（客户端未实现，OPTIONAL）→ skip + 报告，不静默") {
    mkPlugin("sse-skip", mcp(
      "\"legacy\":{\"type\":\"sse\",\"url\":\"https://x.io/mcp\"}," +
        "\"good\":{\"type\":\"stdio\",\"command\":\"python3\"}"))
    loaded("sse-skip").map {
      case Some(p) =>
        assertEquals(p.mcpServers.keySet, Set("good"), "sse entry must be skipped")
        assert(p.warnings.exists(w => w.contains("sse") && w.contains("not supported")),
          s"sse skip must be reported, got: ${p.warnings}")
      case None => fail("plugin must load")
    }
  }

  test("§7.2.2: stdio command 带空白（shell 串）→ entry invalid；./ 相对命令按插件根解析为绝对路径") {
    val d = tempRoot / "plugins" / "cmd-space"
    os.makeDir.all(d / "bin")
    os.write.over(d / "bin" / "srv", "#!/bin/sh\nexit 0\n")
    mkPlugin("cmd-space", mcp(
      "\"bad\":{\"type\":\"stdio\",\"command\":\"python3 -c print(1)\"}," +
        "\"good\":{\"type\":\"stdio\",\"command\":\"./bin/srv\"}"))
    loaded("cmd-space").map {
      case Some(p) =>
        assertEquals(p.mcpServers.keySet, Set("good"), "whitespace command must be invalid (single-token rule)")
        val cmd = p.mcpServers("good").command.getOrElse(fail("command missing"))
        assert(cmd.startsWith(d.toString) && cmd.endsWith("/bin/srv"),
          s"./bin/srv must resolve to plugin-root-absolute, got: $cmd")
      case None => fail("plugin must load")
    }
  }

  test("§9.1: env 声明 PLUGIN_ROOT/PLUGIN_DATA → entry invalid（占位变量为客户端专属）") {
    mkPlugin("env-placeholder", mcp(
      "\"bad\":{\"type\":\"stdio\",\"command\":\"python3\",\"env\":{\"PLUGIN_ROOT\":\"/x\"}}," +
        "\"good\":{\"type\":\"stdio\",\"command\":\"python3\"}"))
    loaded("env-placeholder").map {
      case Some(p) =>
        assertEquals(p.mcpServers.keySet, Set("good"))
        assert(p.warnings.exists(_.contains("PLUGIN_ROOT")), s"got: ${p.warnings}")
      case None => fail("plugin must load")
    }
  }

  test("§7.2.2: cwd 合法形态归一装载；裸相对路径/越界 → invalid") {
    mkPlugin("cwd-forms", mcp(
      "\"dot\":{\"type\":\"stdio\",\"command\":\"python3\",\"cwd\":\"./data\"}," +
        "\"root\":{\"type\":\"stdio\",\"command\":\"python3\",\"cwd\":\"" + PR + "/d\"}," +
        "\"data\":{\"type\":\"stdio\",\"command\":\"python3\",\"cwd\":\"" + PD + "/w\"}," +
        "\"bare\":{\"type\":\"stdio\",\"command\":\"python3\",\"cwd\":\"data\"}," +
        "\"escape\":{\"type\":\"stdio\",\"command\":\"python3\",\"cwd\":\"../out\"}"))
    loaded("cwd-forms").map {
      case Some(p) =>
        val w = p.warnings.mkString("; ")
        assertEquals(p.mcpServers.keySet, Set("dot", "root", "data"), s"got: ${p.mcpServers.keySet} / $w")
        val dot = p.mcpServers("dot").cwd.getOrElse(fail("cwd missing"))
        assert(dot.startsWith("${PLUGIN_ROOT}"), s"./data must normalize to PLUGIN_ROOT placeholder form, got: $dot")
        assert(w.contains("bare") && w.contains("escape"), s"invalid cwd forms must be reported: $w")
      case None => fail("plugin must load")
    }
  }

  test("§7.2.2: url 语义——http 非 loopback 拒；userinfo/fragment 拒；https 合法载入") {
    mkPlugin("url-forms", mcp(
      "\"plain-http\":{\"type\":\"streamable-http\",\"url\":\"http://api.example.com/mcp\"}," +
        "\"with-user\":{\"type\":\"streamable-http\",\"url\":\"http://u:p@x.io/mcp\"}," +
        "\"with-frag\":{\"type\":\"streamable-http\",\"url\":\"https://x.io/mcp#frag\"}," +
        "\"ok-https\":{\"type\":\"streamable-http\",\"url\":\"https://x.io/mcp\"}," +
        "\"ok-loop\":{\"type\":\"streamable-http\",\"url\":\"http://localhost:9999/mcp\"}"))
    loaded("url-forms").map {
      case Some(p) =>
        val w = p.warnings.mkString("; ")
        assertEquals(p.mcpServers.keySet, Set("ok-https", "ok-loop"), s"got: ${p.mcpServers.keySet} / $w")
        assert(w.contains("plain-http") && w.contains("with-user") && w.contains("with-frag"),
          s"invalid urls must be reported: $w")
      case None => fail("plugin must load")
    }
  }

  test("§7.2.2: entry 未知字段 → 告警+忽略，entry 照常装载（report-and-ignore）") {
    mkPlugin("entry-unknown", mcp("\"a\":{\"type\":\"stdio\",\"command\":\"python3\",\"turbo\":true}"))
    loaded("entry-unknown").map {
      case Some(p) =>
        assertEquals(p.mcpServers.keySet, Set("a"), "unknown entry field must not invalidate the entry")
        assert(p.warnings.exists(_.contains("turbo")), s"got: ${p.warnings}")
      case None => fail("plugin must load")
    }
  }

end PluginMcpProtocolSpec
