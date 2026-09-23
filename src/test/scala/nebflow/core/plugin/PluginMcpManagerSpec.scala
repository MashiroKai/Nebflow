package nebflow.core.plugin

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.core.PathUtil
import nebflow.core.tools.ToolRegistry

import scala.concurrent.duration.*

/**
 * 阶段 2b Plugins——PluginMcpManager spec（§B.5 引用计数生命周期 + §B.8-6 refcount）。
 *
 * 用真实 stdio MCP server（python3 fixture，newline-delimited JSON-RPC）验证：
 * - acquire 启动 server → 工具以 `mcp__plugin_<p>_<s>__<t>` 注册（§B.4 命名）
 * - §B.8-6：两个并发会话分配同一 plugin → server 仅 1 份（refcount=2）；
 *   一个 release → 仍运行；最后一个 release → 进程关闭 + 工具注销
 * - release 幂等（双保险兜底路径二次调用 no-op）
 * - 启动失败语义（§B.5）：坏命令 → Left（failNode 载体，不静默）
 * - 闸 D（§B.5 内容面运行时联动；无审批批 2026-09-13 判据放宽）：**内容变更不停飞**、
 *   **封禁立即停飞 + 持有会话收到提醒**、包消失仍停飞
 */
class PluginMcpManagerSpec extends CatsEffectSuite:

  override val munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-plugin-mcp"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)

  /**
   * 最小 stdio MCP server：initialize / tools/list / tools/call 三方法。
   * newline-delimited JSON-RPC（StdioTransport 协议）。
   */
  private val serverPy: String =
    """#!/usr/bin/env python3
      |import sys, json
      |def send(obj):
      |    sys.stdout.write(json.dumps(obj) + "\n")
      |    sys.stdout.flush()
      |for line in sys.stdin:
      |    line = line.strip()
      |    if not line:
      |        continue
      |    try:
      |        msg = json.loads(line)
      |    except Exception:
      |        continue
      |    if "id" not in msg or msg.get("id") is None:
      |        continue
      |    mid = msg["id"]
      |    method = msg.get("method", "")
      |    if method == "initialize":
      |        send({"id": mid, "result": {"protocolVersion": "2025-06-18", "capabilities": {"tools": {}}, "serverInfo": {"name": "fixture", "version": "1.0.0"}}})
      |    elif method == "tools/list":
      |        send({"id": mid, "result": {"tools": [{"name": "echo", "description": "echo back the arguments", "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}}}}]}})
      |    elif method == "tools/call":
      |        args = msg.get("params", {}).get("arguments", {})
      |        send({"id": mid, "result": {"content": [{"type": "text", "text": "echo:" + json.dumps(args, sort_keys=True)}]}})
      |    else:
      |        send({"id": mid, "result": {}})
      |""".stripMargin

  private val serverFile = tempRoot / "fixture_echo_server.py"

  // 隔离 nebflow.json + plugins 目录（trust 表读落在临时根）
  os.makeDir.all(tempRoot / "plugins")
  os.write.over(tempRoot / "nebflow.json", "{}")
  os.write.over(serverFile, serverPy)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  private def mkPlugin(
    name: String,
    serverName: String,
    command: String,
    args: List[String]
  ): PluginRegistry.PluginDef =
    PluginRegistry.PluginDef(
      name = name,
      version = "1.0.0",
      description = s"$name fixture",
      author = "spec",
      skills = Nil,
      mcpServers = Map(serverName -> nebflow.llm.McpServerConfig(command = Some(command), args = Some(args))),
      toolsExtension = Nil,
      digest = s"digest-$name",
      fileCount = 2,
      warnings = Nil,
      trust = PluginRegistry.TrustStatus.Trusted(approvedAt = 0L, digest = s"digest-$name"),
      dir = tempRoot.toString
    )

  private val goodPlugin = mkPlugin("echo-plugin", "srv", "python3", List(serverFile.toString))
  private val badPlugin = mkPlugin("bad-plugin", "srv", "/nonexistent-binary-xyz", Nil)

  // ── acquire / refcount / recycle ─────────────────────────

  test("§B.4-③ acquire: server 启动 + 工具以 mcp__plugin_<p>_<s>__<t> 注册") {
    for
      mgr <- PluginMcpManager.create
      grant <- mgr.acquire("sess-a", List(goodPlugin)).map(_.getOrElse(fail("acquire must succeed")))
      _ = assertEquals(grant.serverIds, List("plugin_echo-plugin_srv"))
      registered <- IO
        .blocking(ToolRegistry.ALL_TOOLS.map(_.name))
        .map(_.exists(_.startsWith("mcp__plugin_echo-plugin_srv__")))
      running <- mgr.runningServers
      _ <- mgr.release("sess-a")
      registeredAfter <- IO
        .blocking(ToolRegistry.ALL_TOOLS.map(_.name))
        .map(_.exists(_.startsWith("mcp__plugin_echo-plugin_srv__")))
      runningAfter <- mgr.runningServers
    yield
      assert(registered, "plugin MCP tool must be registered under the §B.4 name during the session")
      assertEquals(running.get("plugin_echo-plugin_srv"), Some(1), "refcount must be 1 after single acquire")
      assert(!registeredAfter, "tools must be unregistered after terminal recycle (release to zero)")
      assert(!runningAfter.contains("plugin_echo-plugin_srv"), "server must be stopped after refcount reaches zero")
  }

  test("§B.8-6 refcount: 两个并发会话分配同一 plugin → server 仅 1 份；先后 release → 最后一个退出行程") {
    for
      mgr <- PluginMcpManager.create
      g1 <- mgr.acquire("sess-1", List(goodPlugin)).map(_.getOrElse(fail("acquire 1")))
      g2 <- mgr.acquire("sess-2", List(goodPlugin)).map(_.getOrElse(fail("acquire 2")))
      mid <- mgr.runningServers
      _ <- mgr.release("sess-1")
      afterOne <- mgr.runningServers
      toolsAfterOne <- IO
        .blocking(ToolRegistry.ALL_TOOLS.map(_.name))
        .map(_.exists(_.startsWith("mcp__plugin_echo-plugin_srv__")))
      _ <- mgr.release("sess-2")
      afterBoth <- mgr.runningServers
      toolsAfterBoth <- IO
        .blocking(ToolRegistry.ALL_TOOLS.map(_.name))
        .map(_.exists(_.startsWith("mcp__plugin_echo-plugin_srv__")))
    yield
      assertEquals(g1.serverIds, g2.serverIds, "same plugin → same serverId (one shared server)")
      assertEquals(mid.get("plugin_echo-plugin_srv"), Some(2), "refcount must be 2 with two concurrent holders")
      assertEquals(afterOne.get("plugin_echo-plugin_srv"), Some(1), "first release must only decrement")
      assert(toolsAfterOne, "tools must stay registered while another session still holds the server")
      assert(!afterBoth.contains("plugin_echo-plugin_srv"), "last release must stop the server")
      assert(!toolsAfterBoth, "tools must be unregistered after the last release")
  }

  test("release 幂等: 双保险兜底路径二次调用 no-op") {
    for
      mgr <- PluginMcpManager.create
      _ <- mgr.acquire("sess-x", List(goodPlugin)).void
      _ <- mgr.release("sess-x")
      _ <- mgr.release("sess-x") // 兜底重复调用必须安全
      running <- mgr.runningServers
      holds <- mgr.sessionHolds
    yield
      assert(!running.contains("plugin_echo-plugin_srv"), "double release must not resurrect or error")
      assert(!holds.contains("sess-x"), "session hold set must be cleared")
  }

  // ── 启动失败语义（§B.5）────────────────────────────────

  test("§B.5 启动失败: MCP server 起不来 → Left（failNode 载体），不静默跳过") {
    for
      mgr <- PluginMcpManager.create
      result <- mgr.acquire("sess-bad", List(badPlugin))
      running <- mgr.runningServers
    yield result match
      case Right(g) => fail(s"bad command must fail the acquire, got $g")
      case Left(err) =>
        assert(err.contains("PLUGIN_MCP_START_FAILED"), s"error must carry the failure code, got: $err")
        assert(!running.contains("plugin_bad-plugin_srv"), "failed server must leave no refcount residue")
  }

  // ── 内容面运行时联动（§B.5；无审批批 2026-09-13 闸 D 判据放宽）────────

  test("闸 D 放宽: acquire 后内容变更（digest 漂移）→ **不停用**（在飞工具面不动）") {
    val (reminded, setReminded) =
      val ref = new java.util.concurrent.ConcurrentLinkedQueue[String]()
      (ref, (sid: String, text: String) => IO(ref.add(s"$sid|$text")).void)
    for
      mgr <- PluginMcpManager.create
      _ <- mgr.acquire("sess-live", List(goodPlugin)).void
      before <- mgr.runningServers
      // 目录内容变更的运行时等价场景：新 digest、仍可用（在位即信任）
      changedPlugin = goodPlugin.copy(
        digest = "digest-CHANGED",
        trust = PluginRegistry.TrustStatus.Trusted(0L, "digest-CHANGED")
      )
      affected <- mgr.revalidate(IO.pure(List(changedPlugin)), setReminded)
      after <- mgr.runningServers
      tools <- IO
        .blocking(ToolRegistry.ALL_TOOLS.map(_.name))
        .map(_.exists(_.startsWith("mcp__plugin_echo-plugin_srv__")))
      _ <- mgr.release("sess-live")
    yield
      assertEquals(before.get("plugin_echo-plugin_srv"), Some(1), "precondition: server running")
      assertEquals(affected, Nil, "a content change must not mark the running server stale any more")
      assertEquals(after.get("plugin_echo-plugin_srv"), Some(1), "content change must NOT stop the in-flight server")
      assert(tools, "in-flight tools stay registered across a content change")
      assert(reminded.isEmpty, "no reminder on mere content drift (the node keeps working)")
    end for
  }

  test("闸 D: digest 未变 → 不停用；无运行 server → 不扫描") {
    val (reminded, setReminded) =
      val ref = new java.util.concurrent.ConcurrentLinkedQueue[String]()
      (ref, (sid: String, text: String) => IO(ref.add(s"$sid|$text")).void)
    for
      mgr <- PluginMcpManager.create
      emptyScan <- mgr.revalidate(IO.pure(Nil), setReminded) // 无运行 server 快速路径
      _ <- mgr.acquire("sess-keep", List(goodPlugin)).void
      _ <- mgr.revalidate(IO.pure(List(goodPlugin)), setReminded) // 内容面一致
      running <- mgr.runningServers
      _ <- mgr.release("sess-keep")
    yield
      assertEquals(emptyScan, Nil, "no running servers → no scan, no stops")
      assertEquals(
        running.get("plugin_echo-plugin_srv"),
        Some(1),
        "unchanged content face must keep the server running"
      )
      assert(reminded.isEmpty, "no reminders when the content face is intact")
    end for
  }

  test("闸 D 封禁（硬验收）: 封禁 ⇒ 运行中 server 立即停用 + 持有会话收到提醒（≤30s tick 内）") {
    val (reminded, setReminded) =
      val ref = new java.util.concurrent.ConcurrentLinkedQueue[String]()
      (ref, (sid: String, text: String) => IO(ref.add(s"$sid|$text")).void)
    for
      mgr <- PluginMcpManager.create
      _ <- mgr.acquire("sess-blocked", List(goodPlugin)).void
      before <- mgr.runningServers
      blockedPlugin = goodPlugin.copy(trust = PluginRegistry.TrustStatus.Blocked(0L, "spec", "deny-list probe"))
      affected <- mgr.revalidate(IO.pure(List(blockedPlugin)), setReminded)
      after <- mgr.runningServers
      tools <- IO
        .blocking(ToolRegistry.ALL_TOOLS.map(_.name))
        .map(_.exists(_.startsWith("mcp__plugin_echo-plugin_srv__")))
    // 注：revalidate 只摘 serverRefs（既有行为），会话持有集由 release 清理——此处不断言持有集
    yield
      assertEquals(before.get("plugin_echo-plugin_srv"), Some(1), "precondition: server running")
      assertEquals(affected, List("echo-plugin"), "a blocked package must be reported as affected")
      assert(!after.contains("plugin_echo-plugin_srv"), "blocked ⇒ in-flight server must be stopped")
      assert(!tools, "blocked ⇒ tools must be unregistered")
      val msgs = reminded.toArray.map(_.toString).toList
      assert(
        msgs.exists(m => m.startsWith("sess-blocked|") && m.contains("[plugin-block]") && m.contains("echo-plugin")),
        s"holding session must receive a block reminder, got: $msgs"
      )
      assert(
        msgs.forall(m => !m.contains("Re-approve")),
        s"the retired 'Re-approve the plugin if intended' wording must be gone, got: $msgs"
      )
      assert(msgs.exists(_.contains("unblock")), s"the reminder must point at the action that exists, got: $msgs")
    end for
  }

  test("闸 D: 包从注册表消失（目录被删）→ 停用（既有行为保留）") {
    val (reminded, setReminded) =
      val ref = new java.util.concurrent.ConcurrentLinkedQueue[String]()
      (ref, (sid: String, text: String) => IO(ref.add(s"$sid|$text")).void)
    for
      mgr <- PluginMcpManager.create
      _ <- mgr.acquire("sess-gone", List(goodPlugin)).void
      affected <- mgr.revalidate(IO.pure(Nil), setReminded)
      after <- mgr.runningServers
    yield
      assertEquals(affected, List("echo-plugin"), "a vanished package must still be stopped")
      assert(!after.contains("plugin_echo-plugin_srv"), "server of a removed package must be stopped")
      assert(
        reminded.toArray.map(_.toString).exists(_.contains("removed from registry")),
        "the reminder must state the removal reason"
      )
  }

end PluginMcpManagerSpec
