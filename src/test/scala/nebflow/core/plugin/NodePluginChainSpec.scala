package nebflow.core.plugin

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behavior}
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.entity.EntityLoader
import nebflow.core.project.{FlowMapStore, NodeDef, NodeEngine, NodeLifecycle, ProjectDef, ProjectRuntime, ProjectRuntimeRegistry}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, NodeEditTool, ToolContext}
import nebflow.gateway.RateLimiter
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*
import scala.collection.concurrent.TrieMap

/**
 * 阶段 2b Plugins——动态分配全链 spec（§B.4 五步 + §B.8-1/2/3 + §G.2 回滚）。
 *
 * 真实 NodeEdit → NodeEngine 链（NodeAcceptanceSpec 基建复用：RecordingLlm 捕获
 * LlmRequest 断言首条消息注入 + 工具清单）：
 * - §B.8-1：分配后 node 首条消息含 <injected-plugins> 全文 + ${SKILL_DIR} 已替换
 * - §B.4-③：plugin MCP server 启动 + 工具进会话清单；终态回收（全终态汇合点）
 * - §B.8-3 spawn 侧：审批后改文件 → 分配节点启动即 failed（PLUGIN_UNTRUSTED）
 * - H-3①：NodePayload/节点模型带 plugins 字段
 * - §G.2：flag off → NodeEdit 忽略 plugins（不校验不存储）、不注入
 */
class NodePluginChainSpec extends CatsEffectSuite:

  override val munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-plugin-chain"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "test-agent")
  os.write.over(
    tempRoot / "agents" / "test-agent" / "agent.json",
    """{"name":"test-agent","description":"plugin chain agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "test-agent" / "system.md", "# test-agent\n")
  // 2026-09-05 agent 退役：新建节点执行统一 general——fixture 侧补 general agent
  os.makeDir.all(tempRoot / "agents" / "general")
  os.write.over(tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":[],"category":"standalone"}""")
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")
  os.write.over(tempRoot / "nebflow.json", "{}")

  // ── plugin fixtures（真实目录 + 审批走 PluginRegistry 单点）──────
  private val echoServerFile = tempRoot / "fixture_echo_server.py"
  os.write.over(echoServerFile,
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
      |        send({"id": mid, "result": {"protocolVersion": "2025-06-18", "capabilities": {"tools": {}}, "serverInfo": {"name": "f", "version": "1"}}})
      |    elif method == "tools/list":
      |        send({"id": mid, "result": {"tools": [{"name": "echo", "description": "echo back", "inputSchema": {"type": "object", "properties": {}}}]}})
      |    elif method == "tools/call":
      |        send({"id": mid, "result": {"content": [{"type": "text", "text": "echo-ok"}]}})
      |    else:
      |        send({"id": mid, "result": {}})
      |""".stripMargin)

  // inject-skill：只有 skills（${SKILL_DIR} 引用 → 断言替换）
  private val injectSkillDir = tempRoot / "plugins" / "inject-skill"
  os.makeDir.all(injectSkillDir / "skills" / "howto")
  os.write.over(injectSkillDir / "plugin.json",
    s"""{"$$schema":"${PluginRegistry.CanonicalSchema}","name":"inject-skill","version":"1.0.0","description":"skill injection fixture"}""")
  os.write.over(injectSkillDir / "skills" / "howto" / "SKILL.md",
    """---
      |name: howto
      |description: injection test skill
      |---
      |## HowTo Body Marker
      |Read bundled refs at ${SKILL_DIR}/refs/spec.md before answering.""".stripMargin)

  // echo-mcp：只有 mcp（python stdio fixture server）
  private val echoMcpDir = tempRoot / "plugins" / "echo-mcp"
  os.makeDir.all(echoMcpDir)
  os.write.over(echoMcpDir / "plugin.json",
    s"""{"$$schema":"${PluginRegistry.CanonicalSchema}","name":"echo-mcp","version":"1.0.0","description":"mcp lifecycle fixture"}""")
  os.write.over(echoMcpDir / "mcp.json",
    Json.obj("$schema" -> PluginRegistry.CanonicalMcpSchema.asJson,
      "mcpServers" -> Json.obj("srv" -> Json.obj(
        "type" -> "stdio".asJson,
        "command" -> "python3".asJson, "args" -> List(echoServerFile.toString).asJson))).noSpaces)

  // never-approved：本 spec 任何测试都不审批——信任门校验测试的顺序无关 fixture
  private val neverApprovedDir = tempRoot / "plugins" / "never-approved"
  os.makeDir.all(neverApprovedDir / "skills" / "s")
  os.write.over(neverApprovedDir / "plugin.json",
    s"""{"$$schema":"${PluginRegistry.CanonicalSchema}","name":"never-approved","version":"1.0.0","description":"default-deny fixture"}""")
  os.write.over(neverApprovedDir / "skills" / "s" / "SKILL.md",
    """---
      |name: s
      |description: never approved
      |---
      |body""".stripMargin)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  // ── 基建（NodeAcceptanceSpec 同款）─────────────────────────

  private class RecordingLlm(capture: TrieMap[String, LlmRequest], delay: FiniteDuration = Duration.Zero) extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      val put = IO(capture.update(req.sessionId, req)).void
      val body: Stream[IO, StreamChunk] = Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))
      val out: Stream[IO, StreamChunk] =
        if delay == Duration.Zero then Stream.eval(put).drain ++ body
        else (Stream.eval(IO.sleep(delay)) ++ Stream.eval(put)).drain ++ body
      out

  private def mkResources(system: ActorSystem, tmp: os.Path, llm: LlmHandle[IO]): IO[SharedResources] =
    for
      dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- nebflow.core.FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = llm,
      dispatcher = dispatcher,
      sessionStore = nebflow.gateway.SessionStore(tmp / "sessions", tmp / "tasks"),
      projectRoot = os.pwd,
      thinkingConfigRef = thinkingRef,
      rateLimiter = rateLimiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new AgentLibrary(tmp / "agents"),
      taskStore = FileTaskStore,
      historyArchiver = null,
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = voiceMuted
    )

  private def mkCtx(res: SharedResources, system: ActorSystem, ws: String): ToolContext =
    ToolContext(
      projectRoot = ws,
      sessionId = Some("chain-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

  private def mountProject(name: String, ws: os.Path, system: ActorSystem, res: SharedResources): IO[ProjectRuntime] =
    for
      store <- FlowMapStore.open(name, ws.toString)
      engine = new NodeEngine(
        store, system, res,
        wsSendFn = (_: Json) => IO.unit,
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = name,
        emitEvent = (_, _, _) => IO.unit
      )
      pd = ProjectDef(name = name, workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString, createdAt = System.currentTimeMillis())
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield rt

  private def nodeEdit(input: Json, ctx: ToolContext): IO[Either[String, String]] =
    NodeEditTool.call(input.asObject.get, ctx).map(_.left.map(_.message))

  private def nodeInput(project: String, nodename: String, extra: (String, Json)*): Json =
    Json.obj(("project" -> Json.fromString(project)) :: ("nodename" -> Json.fromString(nodename)) :: extra.toList*)

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 50.millis)(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError("waitUntil: condition not met in time"))
          else IO.sleep(every) *> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  private def approve(name: String): IO[Unit] =
    PluginRegistry.approve(name).flatMap {
      case Right(_) => IO.unit
      case Left(e)  => IO.raiseError(new RuntimeException(s"fixture approve failed: $e"))
    }

  // ── §B.8-1：skill 全文注入首条消息 ─────────────────────────

  test("§B.8-1: 分配 skill plugin 的节点——首条消息含 <injected-plugins> 全文且 ${SKILL_DIR} 已替换") {
    val capture = TrieMap[String, LlmRequest]()
    val ws = tempRoot / "ws-inject"
    os.makeDir.all(ws)
    val system = ActorSystem(s"plc-inject-${scala.util.Random.nextInt(100000)}")
    val program =
      for
        _ <- approve("inject-skill")
        res <- mkResources(system, tempRoot, new RecordingLlm(capture))
        rt <- mountProject("plc-inject", ws, system, res)
        ctx = mkCtx(res, system, ws.toString)
        created <- nodeEdit(nodeInput("plc-inject", "injected", "description" -> Json.fromString("test node purpose"),
          "task" -> Json.fromString("use the howto skill"), "out" -> Json.fromString("Nebula"),
          "plugins" -> Json.arr(Json.fromString("inject-skill"))), ctx)
        _ = assert(created.isRight, s"NodeEdit with trusted plugin must succeed: $created")
        _ <- waitUntil(30.seconds)(rt.store.snapshot.map(
          _.nodes.values.exists(n => n.name == "injected" && n.status == NodeLifecycle.Completed)))
        reqOpt = capture.values.headOption
        node <- rt.store.snapshot.map(_.nodes.values.find(_.name == "injected")).flatMap {
          case Some(n) => IO.pure(n)
          case None => IO.raiseError(new RuntimeException("node vanished"))
        }
        payload = payloadFor(node)
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      yield (reqOpt, node, payload)
    program.map { case (reqOpt, node, payload) =>
      val req = reqOpt.getOrElse(fail("no LLM request captured"))
      val userMsg = req.messages.find(_.role == nebflow.shared.MessageRole.User).map(_.textContent).getOrElse("")
      assert(userMsg.contains("<injected-plugins>"), s"first user message must carry the injection block, got:\n${userMsg.take(600)}")
      assert(userMsg.contains("<plugin name=\"inject-skill\" skill=\"howto\">"), "plugin tag with §B.4 shape must be present")
      assert(userMsg.contains("## HowTo Body Marker"), "SKILL.md body (frontmatter stripped) must be injected in full")
      assert(userMsg.contains("/refs/spec.md"), "SKILL_DIR substitution must keep the relative ref path")
      assert(!userMsg.contains("${SKILL_DIR}"), "${SKILL_DIR} placeholder must be replaced with the absolute dir")
      assert(userMsg.contains(injectSkillDir.toString), "SKILL_DIR must resolve to the plugin skill dir")
      assertEquals(node.plugins, List("inject-skill"), "node model must record the allocation (H-3①)")
      assert(payload.toString.contains("\"plugins\""), "NodePayload must expose plugins (H-3①)")
    }
  }

  private def payloadFor(n: NodeDef): Json =
    nebflow.core.project.NodePayload.buildNodeJson(n, System.currentTimeMillis())

  // ── §B.4-③/⑤：MCP 启动 → 会话工具清单 → 终态回收 ─────────────

  test("§B.4 全链: mcp plugin 分配 → 工具注册进会话清单 → 节点完成后回收归零") {
    val capture = TrieMap[String, LlmRequest]()
    val ws = tempRoot / "ws-mcp"
    os.makeDir.all(ws)
    val system = ActorSystem(s"plc-mcp-${scala.util.Random.nextInt(100000)}")
    // 慢 LLM（1.5s）：RecordingLlm 瞬回会让「注册→完成→回收」在 150ms 内全链跑完，
    // 观察窗口关闭快于轮询间隔——延迟拉住运行中态，refcount/注册中间态可断言
    val program =
      for
        _ <- approve("echo-mcp")
        res <- mkResources(system, tempRoot, new RecordingLlm(capture, delay = 1500.millis))
        rt <- mountProject("plc-mcp", ws, system, res)
        ctx = mkCtx(res, system, ws.toString)
        created <- nodeEdit(nodeInput("plc-mcp", "mcpped", "description" -> Json.fromString("test node purpose"),
          "task" -> Json.fromString("use the echo tool"), "out" -> Json.fromString("Nebula"),
          "plugins" -> Json.arr(Json.fromString("echo-mcp"))), ctx)
        _ = assert(created.isRight, s"NodeEdit must succeed: $created")
        // MCP 启动 + 引用记账（acquire 在 spawn 前完成——工具先于 LLM 请求注册）
        // 已知 flake（满载偶红、单跑绿，checkjs-gate-fix 批 2026-09-05 加宽）：
        // 满载下 python3 子进程冷启 + MCP 握手可超过原 15s；回收窗口同理。
        _ <- waitUntil(30.seconds)(
          IO.blocking(nebflow.core.tools.ToolRegistry.ALL_TOOLS.map(_.name))
            .map(_.exists(_.startsWith("mcp__plugin_echo-mcp_srv__"))))
        sessionRunning <- res.pluginMcp.runningServers
        _ <- waitUntil(30.seconds)(rt.store.snapshot.map(
          _.nodes.values.exists(n => n.name == "mcpped" && n.status == NodeLifecycle.Completed)))
        _ <- waitUntil(30.seconds)(res.pluginMcp.sessionHolds.map(_.isEmpty))
        toolsAfter <- IO.blocking(nebflow.core.tools.ToolRegistry.ALL_TOOLS.map(_.name))
        runningAfter <- res.pluginMcp.runningServers
        reqOpt = capture.values.headOption
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      yield (reqOpt, sessionRunning, toolsAfter, runningAfter)
    program.map { case (reqOpt, sessionRunning, toolsAfter, runningAfter) =>
      assertEquals(sessionRunning.get("plugin_echo-mcp_srv"), Some(1), "refcount 1 while session runs (§B.8-6 precondition)")
      val req = reqOpt.getOrElse(fail("no LLM request captured"))
      val toolNames = req.tools.getOrElse(Nil).map(_.name)
      assert(toolNames.exists(_.startsWith("mcp__plugin_echo-mcp_srv__")),
        s"plugin MCP tool must be in the session tool list (allowedSet §B.4-③), got: ${toolNames.mkString(",")}")
      assert(!toolsAfter.exists(_.startsWith("mcp__plugin_echo-mcp_srv__")),
        "plugin tools must be unregistered after node terminal state (recycle)")
      assert(!runningAfter.contains("plugin_echo-mcp_srv"), "server must be stopped after terminal recycle")
    }
  }

  // ── §B.8-3（spawn 侧）：审批后改文件 → 分配节点启动即失败 ───────

  test("§B.8-3 spawn 侧: 分配校验通过后插件被改 → 节点启动即 failed（旧 digest 拒用）") {
    val capture = TrieMap[String, LlmRequest]()
    val ws = tempRoot / "ws-stale"
    os.makeDir.all(ws)
    val system = ActorSystem(s"plc-stale-${scala.util.Random.nextInt(100000)}")
    // 慢 LLM：让上游节点 A 保持 running，为 B 争取「校验后、启动前」的窗口
    val program =
      for
        _ <- approve("inject-skill")
        res <- mkResources(system, tempRoot, new RecordingLlm(capture, delay = 1500.millis))
        rt <- mountProject("plc-stale", ws, system, res)
        ctx = mkCtx(res, system, ws.toString)
        // A：入口节点（task，out→Nebula），慢 LLM 下保持 running
        a <- nodeEdit(nodeInput("plc-stale", "A-slow", "description" -> Json.fromString("test node purpose"),
          "task" -> Json.fromString("slow upstream"), "out" -> Json.fromString("Nebula")), ctx)
        _ = assert(a.isRight, s"upstream A create failed: $a")
        aId <- rt.store.snapshot.map(_.nodes.values.find(_.name == "A-slow").map(_.id)).flatMap {
          case Some(id) => IO.pure(id)
          case None => IO.raiseError(new RuntimeException("A-slow vanished"))
        }
        // B：wiring（in=[A] 无 task，运行中上游不投递 → 不启动），分配 inject-skill
        b <- nodeEdit(nodeInput("plc-stale", "B-stale", "description" -> Json.fromString("test node purpose"),
          "in" -> Json.arr(Json.fromString(aId)), "out" -> Json.fromString("Nebula"),
          "plugins" -> Json.arr(Json.fromString("inject-skill"))), ctx)
        _ = assert(b.isRight, s"downstream B create failed: $b")
        // 校验通过后再改插件（digest 失效——§B.8-3「升级即重审」）
        _ <- IO.sleep(20.millis)
        _ <- IO.blocking(os.write.append(injectSkillDir / "skills" / "howto" / "SKILL.md", "\ntampered\n"))
        // A 完成 → 投递 → barrier 归零 → B 启动 → spawn 期 resolve 失败 → failNode
        _ <- waitUntil(30.seconds)(rt.store.getNode(aId).map(
          _.exists(_.status == NodeLifecycle.Completed)))
        _ <- waitUntil(30.seconds)(rt.store.snapshot.map(
          _.nodes.values.find(_.name == "B-stale").exists(_.status == NodeLifecycle.Failed)))
        bNode <- rt.store.snapshot.map(_.nodes.values.find(_.name == "B-stale")).flatMap {
          case Some(n) => IO.pure(n)
          case None => IO.raiseError(new RuntimeException("B-stale vanished"))
        }
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      yield bNode
    program.map { bNode =>
      assertEquals(bNode.status, NodeLifecycle.Failed, "allocation with stale digest must fail the node (no silent degradation)")
      val result = bNode.result.getOrElse("")
      assert(result.contains("PLUGIN_UNTRUSTED"), s"failure must carry the trust-gate guidance, got: $result")
    }
  }

  // ── NodeEdit 校验面（§B.4 第 3 步）────────────────────────

  test("NodeEdit 校验: 不存在的插件 → PLUGIN_NOT_FOUND；未审批插件 → PLUGIN_UNTRUSTED（0 spawn 拒绝）") {
    val capture = TrieMap[String, LlmRequest]()
    val ws = tempRoot / "ws-validate"
    os.makeDir.all(ws)
    val system = ActorSystem(s"plc-val-${scala.util.Random.nextInt(100000)}")
    val program =
      for
        res <- mkResources(system, tempRoot, new RecordingLlm(capture))
        rt <- mountProject("plc-val", ws, system, res)
        ctx = mkCtx(res, system, ws.toString)
        missing <- nodeEdit(nodeInput("plc-val", "v1", "description" -> Json.fromString("test node purpose"),
          "task" -> Json.fromString("t"), "out" -> Json.fromString("Nebula"),
          "plugins" -> Json.arr(Json.fromString("no-such-plugin"))), ctx)
        untrusted <- nodeEdit(nodeInput("plc-val", "v2", "description" -> Json.fromString("test node purpose"),
          "task" -> Json.fromString("t"), "out" -> Json.fromString("Nebula"),
          "plugins" -> Json.arr(Json.fromString("never-approved"))), ctx)
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      yield (missing, untrusted)
    program.map { case (missing, untrusted) =>
      assert(missing.isLeft, s"missing plugin must be refused, got: $missing")
      val mErr = missing.swap.toOption.getOrElse("")
      assert(mErr.contains("PLUGIN_NOT_FOUND"), s"missing plugin error code expected, got: $mErr")
      assert(untrusted.isLeft, s"untrusted plugin must be refused, got: $untrusted")
      val uErr = untrusted.swap.toOption.getOrElse("")
      assert(uErr.contains("PLUGIN_UNTRUSTED") && uErr.contains("approve"),
        s"untrusted error must carry gate code + guidance, got: $uErr")
    }
  }

  // ── §G.2 flag 回滚 ─────────────────────────────────────

  test("§G.2 回滚: plugins.enabled=false → NodeEdit 忽略 plugins（不校验不存储）、无注入") {
    val capture = TrieMap[String, LlmRequest]()
    val ws = tempRoot / "ws-flagoff"
    os.makeDir.all(ws)
    val system = ActorSystem(s"plc-off-${scala.util.Random.nextInt(100000)}")
    val program =
      for
        _ <- IO.blocking(os.write.over(tempRoot / "nebflow.json", """{"plugins":{"enabled":false}}"""))
        res <- mkResources(system, tempRoot, new RecordingLlm(capture))
        rt <- mountProject("plc-off", ws, system, res)
        ctx = mkCtx(res, system, ws.toString)
        // 引用不存在的插件名也被忽略（不校验）——flag off 的回滚语义
        created <- nodeEdit(nodeInput("plc-off", "ignored", "description" -> Json.fromString("test node purpose"),
          "task" -> Json.fromString("no plugins"), "out" -> Json.fromString("Nebula"),
          "plugins" -> Json.arr(Json.fromString("no-such-plugin"))), ctx)
        _ = assert(created.isRight, s"flag off must ignore plugins param, got: $created")
        _ <- waitUntil(30.seconds)(rt.store.snapshot.map(
          _.nodes.values.find(_.name == "ignored").exists(_.status == NodeLifecycle.Completed)))
        node <- rt.store.snapshot.map(_.nodes.values.find(_.name == "ignored")).flatMap {
          case Some(n) => IO.pure(n)
          case None => IO.raiseError(new RuntimeException("node vanished"))
        }
        reqOpt = capture.values.headOption
        _ <- IO.blocking(os.write.over(tempRoot / "nebflow.json", "{}"))
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      yield (node, reqOpt)
    program.map { case (node, reqOpt) =>
      assertEquals(node.plugins, Nil, "flag off must not store the ignored plugins param")
      val userMsg = reqOpt.flatMap(_.messages.find(_.role == nebflow.shared.MessageRole.User).map(_.textContent)).getOrElse("")
      assert(!userMsg.contains("<injected-plugins>"), "flag off must suppress injection")
    }
  }

  // ── §E.3：node.preset 消费接通（协议符合度批，2b 遗留）─────────

  test("§E.3 preset: node.preset → 预设链进会话 LLM 请求（agentModel）；未知 preset → 节点 failed 含可用清单") {
    val capture = TrieMap[String, LlmRequest]()
    val ws = tempRoot / "ws-preset"
    os.makeDir.all(ws)
    // 预设 fixture：fast 链 = fast-model + fb-fallback（PresetStore 单点解析）
    os.write.over(tempRoot / "model-presets.json", Json.obj(
      "defaultPreset" -> "general".asJson,
      "presets" -> Json.obj(
        "general" -> Json.obj("name" -> "general".asJson, "preferred" -> "general-model".asJson, "fallbacks" -> List.empty[String].asJson),
        "fast" -> Json.obj("name" -> "fast".asJson, "preferred" -> "fast-model".asJson, "fallbacks" -> List("fb-fallback").asJson))).noSpaces)
    val system = ActorSystem(s"plc-preset-${scala.util.Random.nextInt(100000)}")
    val program =
      for
        res <- mkResources(system, tempRoot, new RecordingLlm(capture))
        rt <- mountProject("plc-preset", ws, system, res)
        ctx = mkCtx(res, system, ws.toString)
        ok <- nodeEdit(nodeInput("plc-preset", "preset-ok", "agent" -> Json.fromString("test-agent"),
          "task" -> Json.fromString("t"), "out" -> Json.fromString("Nebula"),
          "preset" -> Json.fromString("fast")), ctx)
        _ = assert(ok.isRight, s"NodeEdit with valid preset must succeed: $ok")
        _ <- waitUntil(30.seconds)(rt.store.snapshot.map(
          _.nodes.values.exists(n => n.name == "preset-ok" && n.status == NodeLifecycle.Completed)))
        bad <- nodeEdit(nodeInput("plc-preset", "preset-bad", "agent" -> Json.fromString("test-agent"),
          "task" -> Json.fromString("t-preset-missing"), "out" -> Json.fromString("Nebula"),
          "preset" -> Json.fromString("no-such-preset")), ctx)
        _ = assert(bad.isRight, s"NodeEdit accepts the preset param (validation is spawn-side §E.3): $bad")
        _ <- waitUntil(30.seconds)(rt.store.snapshot.map(
          _.nodes.values.exists(n => n.name == "preset-bad" && n.status == NodeLifecycle.Failed)))
        badNode <- rt.store.snapshot.map(_.nodes.values.find(_.name == "preset-bad")).flatMap {
          case Some(n) => IO.pure(n)
          case None => IO.raiseError(new RuntimeException("preset-bad vanished"))
        }
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      yield (capture.values.find(_.sessionId.startsWith("node-")), badNode)
    program.map { case (reqOpt, badNode) =>
      val req = reqOpt.getOrElse(fail("no LLM request captured for preset node"))
      assertEquals(req.agentModel, Some(nebflow.shared.AgentModelConfig(Some("fast-model"), List("fb-fallback"))),
        s"node.preset must drive the session model chain (§E.3 consumption), got: ${req.agentModel}")
      assertEquals(badNode.status, NodeLifecycle.Failed, "unresolvable preset must fail the node")
      val result = badNode.result.getOrElse("")
      assert(result.contains("preset") && result.contains("unresolved") && result.contains("general"),
        s"failure must carry §E.3 guidance with available presets, got: $result")
    }
  }

end NodePluginChainSpec
