package nebflow.core.executor

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * external-executors 批（#1225）验收 · 适配器 fixture 回放 ＋ 选择面 fail-closed。
 *
 * 中心性质（增补任务书 §三/§六）：
 *   - 三适配器对各自 fixture 行逐帧回放（claude stream-json / codex --json /
 *     zcode stream-json 事件流（主）＋ --json 摘要（兼容）双方言）；噪声行零帧、
 *     绝不抛；ANSI 前导控制序列剥离不伤载荷；
 *   - 选择语义：显式 `executor` 参数优先；参数缺省 ⇒ 配置默认
 *     （nebflow.json executor.default；未配置/不可用 ⇒ 内置 nebflow）；
 *   - fail-closed（3.4）：不在册 / 适配未就绪（hermes）/ 未检测到二进制 /
 *     配置默认不可检测 ⇒ Left 显式错误（消息含可用集），**零静默回落 nebflow**。
 */
class ExternalExecutorSpec extends CatsEffectSuite:

  override val munitIOTimeout = 60.seconds

  private def withTempDataRoot[A](f: os.Path => A): A =
    val original = PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "nb-external-exec-")
    PathUtil.setDataRoot(tmp)
    try f(tmp)
    finally
      PathUtil.setDataRoot(original)
      os.remove.all(tmp)

  private def withFakeBin[A](name: String)(f: os.Path => A): A =
    val dir = os.temp.dir(prefix = "nb-external-bin-")
    os.write(dir / name, "#!/bin/sh\n")
    // Windows 无 POSIX 属性视图：假 bin 的执行位置位跳过（Windows 存在性判定不需要执行位）。
    if !nebflow.core.CredentialFileAcl.isWindows(nebflow.core.CredentialFileAcl.currentOsName) then
      os.perms.set(dir / name, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
    try f(dir)
    finally os.remove.all(dir)

  test("maximum thinking reaches the model's highest advertised effort") {
    val levels = List("low", "medium", "high", "xhigh", "max", "ultra")
    val decision = ExternalReasoning.decide(nebflow.shared.ThinkingConfig(effort = Some(nebflow.shared.ThinkingEffort.Max)),
      Some("gpt-6.1-terra"), Some(ExternalReasoning.Catalog(levels, "runtime", "stamp")))
    for resume <- List(None, Some("thread")) do
      val args = ExternalReasoning.argv(ExternalExecutor.Codex.launchArgsWithModel("task", resume, Some("gpt-6.1-terra")), decision)
      assert(args.containsSlice(List("-c", "model_reasoning_effort=ultra")), args.toString)
    assert(decision.trace.contains("requested=max actual=ultra direction=up source=runtime timestamp=stamp"))
  }

  test("advertised catalogs map both endpoints and preserve matching names") {
    import nebflow.shared.ThinkingEffort.*
    val tiers = List(Minimal, Low, Medium, High, Max)
    val catalogs = List(
      List("low", "medium", "high", "xhigh", "max", "ultra") -> List("low", "low", "medium", "high", "ultra"),
      List("low", "medium", "high", "xhigh", "max") -> List("low", "low", "medium", "high", "max")
    )
    catalogs.foreach { case (levels, expected) =>
      assertEquals(tiers.map(e => ExternalReasoning.map(e, levels).get), expected)
    }
    assertEquals(ExternalReasoning.map(Medium, List("a", "b", "c", "d")), Some("c"))
    assertEquals(ExternalReasoning.map(Max, Nil), None)
    val unset = ExternalReasoning.decide(nebflow.shared.ThinkingConfig(effort = Some(Max)), None, None)
    assertEquals(unset.actual, None)
    assert(unset.reason.contains("model unknown"))
  }

  test("direction identifies exact and upward intermediate remapping") {
    import nebflow.shared.{ThinkingConfig, ThinkingEffort}
    def direction(levels: List[String], effort: ThinkingEffort) =
      ExternalReasoning.decide(ThinkingConfig(effort = Some(effort)), Some("model"),
        Some(ExternalReasoning.Catalog(levels, "test", "stamp"))).direction
    val six = List("low", "medium", "high", "xhigh", "max", "ultra")
    val five = List("low", "medium", "high", "xhigh", "max")
    assertEquals(direction(six, ThinkingEffort.Medium), "exact")
    assertEquals(direction(five, ThinkingEffort.High), "exact")
    assertEquals(direction(List("a", "b", "c", "d", "e", "f"), ThinkingEffort.Medium), "up")
    assertEquals(direction(List("a", "b", "c", "d", "e"), ThinkingEffort.Medium), "up")
    assertEquals(ExternalReasoning.decide(ThinkingConfig(), None, None).direction, "none")
    assertEquals(direction(List("max", "low", "high", "ultra"), ThinkingEffort.Max), "up")
  }

  test("runtime catalog protocol and fresh/resume traces from a fake CLI") {
    val dir = os.temp.dir(prefix = "codex-catalog-test-")
    val bin = dir / "codex"
    os.write(bin, """#!/bin/sh
read first
printf '%s\n' '{"id":1,"result":{}}'
read initialized
read list
printf '%s\n' '{"id":2,"result":{"data":[{"model":"m","supportedReasoningEfforts":[{"reasoningEffort":"low"},{"reasoningEffort":"ultra"}]}],"nextCursor":null}}'
""")
    os.perms.set(bin, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
    val catalog = ExternalReasoning.runtime(bin.toString, "m")
    assertEquals(catalog.map(_.levels), Some(List("low", "ultra")))
    assertEquals(catalog.map(_.source), Some("runtime"))
    assert(catalog.exists(_.timestamp.nonEmpty))
    val decision = ExternalReasoning.decide(nebflow.shared.ThinkingConfig(effort = Some(nebflow.shared.ThinkingEffort.Max)), Some("m"), catalog)
    assert(decision.trace.contains("requested=max actual=ultra direction=up source=runtime timestamp="))
  }

  test("external model arguments support fresh and resumed sessions without affecting unsupported CLI") {
    for resume <- List(None, Some("session-id")) do
      val claude = ExternalExecutor.ClaudeCode.launchArgsWithModel("task", resume, Some("sonnet"))
      assert(claude.containsSlice(List("--model", "sonnet")))
      val codex = ExternalExecutor.Codex.launchArgsWithModel("task", resume, Some("example-model"))
      assert(codex.containsSlice(List("--model", "example-model")))
      assertEquals(
        ExternalExecutor.ZCode.launchArgsWithModel("task", resume, Some("ignored")),
        ExternalExecutor.ZCode.launchArgs("task", resume)
      )
      assertEquals(
        ExternalExecutor.Codex.launchArgsWithModel("task", resume, None),
        ExternalExecutor.Codex.launchArgs("task", resume)
      )
  }

  test("executor models are hot read and independently updated") {
    withTempDataRoot { _ =>
      val config = nebflow.service.ConfigService
      assertEquals(config.setExecutorModel("claude-code", "sonnet").unsafeRunSync(), Right(()))
      assertEquals(config.setExecutorModel("codex", "model-b").unsafeRunSync(), Right(()))
      assertEquals(ExecutorRegistry.configuredModel("claude-code"), Some("sonnet"))
      assertEquals(ExecutorRegistry.configuredModel("codex"), Some("model-b"))
      assertEquals(config.setExecutorModel("claude-code", "").unsafeRunSync(), Right(()))
      assertEquals(ExecutorRegistry.configuredModel("claude-code"), None)
      assertEquals(ExecutorRegistry.configuredModel("codex"), Some("model-b"))
      assert(config.setExecutorModel("zcode", "model").unsafeRunSync().isLeft)
    }
  }

  test("external model selection round trips through delegate records") {
    val record = nebflow.core.delegate.DelegateRegistry.Record(
      "external-codex-x", "codex", "/tmp", None, None, "parent", "title", 1L, externalModel = Some("model-a")
    )
    assertEquals(
      record.asJson.as[nebflow.core.delegate.DelegateRegistry.Record].toOption.flatMap(_.externalModel),
      Some("model-a")
    )
    assertEquals(
      record.copy(externalModel = None).asJson.as[nebflow.core.delegate.DelegateRegistry.Record].toOption.flatMap(_.externalModel),
      None
    )
  }

  // ── claude-code 适配器 fixture 回放 ─────────────────────────────

  test("claude-code: init → Start(cliSessionId), assistant blocks → Text/Tool, result → Final") {
    val frames = List(
      """{"type":"system","subtype":"init","session_id":"sess-9f13","cwd":"/w"}""",
      """{"type":"assistant","message":{"content":[{"type":"text","text":"Planning the move."}]}}""",
      """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash","input":{"command":"ls"}}]}}""",
      """{"type":"assistant","message":{"content":[{"type":"text","text":"Done."},{"type":"tool_use","name":"Read","input":{"path":"/a"}}]}}""",
      """{"type":"result","subtype":"success","is_error":false,"result":"Moved the file.","session_id":"sess-9f13"}"""
    ).flatMap(ExternalExecutor.ClaudeCode.parseLine)

    assertEquals(frames.collect { case ExternalExecutor.Frame.Start(s) => s }, List(Some("sess-9f13")))
    assertEquals(
      frames.collect { case ExternalExecutor.Frame.MessageComplete(_, blocks) => blocks.map(_.text) }.flatten,
      List("Planning the move.", "Done.")
    )
    assertEquals(frames.collect { case ExternalExecutor.Frame.Tool(l) => l }, List("Bash", "Read"))
    assertEquals(
      frames.collect { case f: ExternalExecutor.Frame.ExecutionEnd => (f.result, f.failed) },
      List((Some("Moved the file."), false))
    )
  }

  test("claude-code: error result flags failed; noise lines yield zero frames, never throw") {
    val err = ExternalExecutor.ClaudeCode.parseLine(
      """{"type":"result","subtype":"error_during_execution","is_error":true,"result":"boom"}"""
    )
    assertEquals(
      err.collect { case f: ExternalExecutor.Frame.ExecutionEnd => (f.result, f.failed) },
      List((Some("boom"), true))
    )

    val noise = List("", "   ", "not json at all", "[1,2,3]", """{"type":"other"}""")
      .flatMap(ExternalExecutor.ClaudeCode.parseLine)
    assertEquals(noise, Nil)
  }

  test("claude-code launchArgs: print+stream-json+verbose skeleton, grants from dataRoot, resume tail") {
    withTempDataRoot { tmp =>
      // 数据根下只建部分工作子目录——grantDirs 按 os.isDir 过滤，缺席目录不入参。
      os.makeDir.all(tmp / "projects")
      os.makeDir.all(tmp / "docs")

      val fresh = ExternalExecutor.ClaudeCode.launchArgs("do it", None)
      // 骨架（前 5 元）：headless print ＋ stream-json ＋ verbose。
      assertEquals(fresh.take(5), List("-p", "do it", "--output-format", "stream-json", "--verbose"))
      // 权限形态：工作区内编辑自动接受 ＋ 命令白名单 ＋ push 硬闸。
      assert(fresh.containsSlice(List("--permission-mode", "acceptEdits")), fresh.mkString(" "))
      assert(fresh.containsSlice(List("--disallowedTools", "Bash(git push:*)")), fresh.mkString(" "))
      // allowlist 值为逗号连接的单元素（模式含空格，禁按空格切分）。
      // git 按子命令枚举：push / remote / config 不入枚举（-C 绕行无 allow 匹配）。
      val allowIdx = fresh.indexOf("--allowedTools")
      val allowed = if allowIdx >= 0 then fresh(allowIdx + 1) else ""
      assert(allowed.contains("Bash(git commit:*)"), fresh.mkString(" "))
      assert(!allowed.contains("Bash(git:*)"), fresh.mkString(" "))
      assert(!allowed.contains("push"), fresh.mkString(" "))
      assert(!allowed.contains("git config") && !allowed.contains("git remote"), fresh.mkString(" "))
      // 放行目录＝白名单子目录（存在者）＋ /tmp；逐目录点名 --add-dir。
      val granted = fresh.sliding(2).toList.filter(_(0) == "--add-dir").map(_(1))
      assertEquals(granted, ExternalExecutor.ClaudeCode.grantDirs(tmp))
      assert(granted.contains((tmp / "projects").toString), granted.mkString(" "))
      assert(granted.contains((tmp / "docs").toString), granted.mkString(" "))
      assert(granted.contains("/tmp"), granted.mkString(" "))
      assert(!granted.contains((tmp / "plugin-repos").toString), granted.mkString(" "))
      // 白名单制：数据根本体与凭据件形态永不出现在 argv。
      assert(!fresh.contains(tmp.toString), fresh.mkString(" "))
      assert(
        !fresh.exists(a => a.toLowerCase.contains("secrets") || a.toLowerCase.contains("auth")),
        fresh.mkString(" ")
      )

      // resume：--resume 对追加在 argv 尾部。
      val resume = ExternalExecutor.ClaudeCode.launchArgs("continue", Some("sess-9f13"))
      assertEquals(resume.takeRight(2), List("--resume", "sess-9f13"))
    }
  }

  test("claude-code grantDirs: only existing whitelisted subdirs + /tmp; root never granted") {
    withTempDataRoot { tmp =>
      os.makeDir.all(tmp / "general")
      val granted = ExternalExecutor.ClaudeCode.grantDirs(tmp)
      assertEquals(granted, List((tmp / "general").toString, "/tmp"))
      // /tmp 恒在（临时件落位约定面），与数据根布局无关。
      assert(granted.contains("/tmp"))
      // 数据根本体（凭据件所在地）永不入名单。
      assert(!granted.contains(tmp.toString))
    }
  }

  // ── codex 适配器 fixture 回放 ───────────────────────────────────

  test("codex: thread.started → Start, command_execution → Tool, agent_message → Text, turn.failed → Final(failed)") {
    val frames = List(
      """{"type":"thread.started","thread_id":"th-77"}""",
      """{"type":"turn.started"}""",
      """{"type":"item.started","item":{"type":"command_execution","command":"cargo test --all"}}""",
      """{"type":"item.completed","item":{"type":"agent_message","text":"Tests pass."}}""",
      """{"type":"turn.completed","usage":{"input_tokens":10}}"""
    ).flatMap(ExternalExecutor.Codex.parseLine)

    assertEquals(frames.collect { case ExternalExecutor.Frame.Start(s) => s }, List(Some("th-77")))
    assertEquals(frames.collect { case ExternalExecutor.Frame.Tool(l) => l }, List("cargo"))
    assertEquals(
      frames.collect { case ExternalExecutor.Frame.MessageComplete(_, blocks) => blocks.map(_.text) }.flatten,
      List("Tests pass.")
    )
    assertEquals(
      frames.collect { case f: ExternalExecutor.Frame.ExecutionEnd => (f.result, f.failed) },
      List((None, false))
    )

    val failed = ExternalExecutor.Codex.parseLine("""{"type":"turn.failed","error":{"message":"sandbox denied"}}""")
    assertEquals(
      failed.collect { case f: ExternalExecutor.Frame.ExecutionEnd => (f.result, f.failed) },
      List((None, true))
    )
  }

  test("codex launchArgs: fresh = exec --json (repo check skipped); resume = exec resume") {
    assertEquals(ExternalExecutor.Codex.launchArgs("t", None), List("exec", "--json", "--skip-git-repo-check", "t"))
    assertEquals(ExternalExecutor.Codex.launchArgs("t", Some("th-77")), List("exec", "resume", "--json", "th-77", "t"))
  }

  // ── zcode 适配器（晨裁①-B：--json 摘要方言＋stream-json 兼容方言）fixture 回放 ──

  test("zcode --json 摘要方言: pretty 文档键值行 → Start(sessionId) + Final(response 还原转义)") {
    // pretty 形态＝JSON.stringify(x,null,2)（zcode bundle e0/formatJson 实况；
    // 字符串值转义后恒单行）。response 含 \" 与 \n 转义、行尾逗号齐全。
    val frames = List(
      "{",
      """  "sessionId": "sess_95edc895-c65b-445d-9d84-f1ec5605b5cc",""",
      """  "traceId": "2b2f0409-7ebf-4938-ab52-7772d2feaabc",""",
      """  "turnId": "turn_bf1fd008-8df7-4d7b-ace0-6927c3b5d662",""",
      """  "response": "ok — done \"quickly\"\nsecond line",""",
      "  \"usage\": {",
      """    "input": 10,""",
      "  },",
      "  \"eventCount\": 3,",
      "  \"projection\": {",
      """    "status": "idle",""",
      "    \"turnCount\": 1",
      "  }",
      "}"
    ).flatMap(ExternalExecutor.ZCode.parseLine)

    assertEquals(
      frames.collect { case ExternalExecutor.Frame.Start(s) => s },
      List(Some("sess_95edc895-c65b-445d-9d84-f1ec5605b5cc"))
    )
    assertEquals(
      frames.collect { case f: ExternalExecutor.Frame.ExecutionEnd => f.result.getOrElse("") },
      List("ok — done \"quickly\"\nsecond line")
    )
    assertEquals(frames.collect { case f: ExternalExecutor.Frame.ExecutionEnd => f.failed }, List(false))
  }

  test("zcode stream-json 兼容方言: 事件流 JSONL → Start/Text/Tool；紧凑 result 行 → Start+Final") {
    val frames = List(
      """{"type":"session.created","sessionId":"sess_abc","eventId":"e1","seq":0,"payload":{}}""",
      """{"type":"turn.started","sessionId":"sess_abc","eventId":"e2","seq":1,"payload":{}}""",
      """{"type":"model.streaming","sessionId":"sess_abc","payload":{"kind":"text_delta","delta":"Working."}}""",
      """{"type":"tool.updated","sessionId":"sess_abc","payload":{"kind":"started","toolName":"Bash","toolCallId":"tc-1"}}""",
      """{"type":"model.streaming","sessionId":"sess_abc","payload":{"kind":"reasoning_delta","delta":"hidden"}}""",
      """{"type":"result","sessionId":"sess_abc","turnId":"turn_1","response":"All done.","eventCount":5,"projection":{"status":"idle","turnCount":1}}"""
    ).flatMap(ExternalExecutor.ZCode.parseLine)

    assertEquals(frames.collect { case ExternalExecutor.Frame.Start(s) => s }, List(Some("sess_abc"), Some("sess_abc")))
    assertEquals(frames.collect { case ExternalExecutor.Frame.Text(t) => t }, List("Working."))
    assertEquals(frames.collect { case ExternalExecutor.Frame.Tool(l) => l }, List("Bash"))
    assertEquals(
      frames.collect { case f: ExternalExecutor.Frame.ExecutionEnd => (f.result, f.failed) },
      List((Some("All done."), false))
    )
  }

  test("zcode: turn.failed flags failed; projection.status=error 摘要亦然; 噪声行零帧绝不抛") {
    val failed = ExternalExecutor.ZCode.parseLine(
      """{"type":"turn.failed","sessionId":"sess_x","payload":{"message":"provider unreachable"}}"""
    )
    assertEquals(
      failed.collect { case f: ExternalExecutor.Frame.ExecutionEnd => (f.result, f.failed) },
      List((None, true))
    )

    val errSummary = ExternalExecutor.ZCode.parseLine(
      """{"type":"result","sessionId":"sess_x","response":"partial","projection":{"status":"error"}}"""
    )
    assertEquals(
      errSummary.collect { case f: ExternalExecutor.Frame.ExecutionEnd => (f.result, f.failed) },
      List((Some("partial"), true))
    )

    val noise =
      List("", "   ", "not json", "{", "}", "[1,2,3]", """  "eventCount": 3,""", """  "traceId": "abc",""").flatMap(
        ExternalExecutor.ZCode.parseLine
      )
    assertEquals(noise, Nil)
  }

  test("zcode launchArgs: fresh = -p + stream-json（主方言）; resume = --resume 前置") {
    assertEquals(ExternalExecutor.ZCode.launchArgs("t", None), List("-p", "t", "--output-format", "stream-json"))
    assertEquals(
      ExternalExecutor.ZCode.launchArgs("t", Some("zc-1")),
      List("--resume", "zc-1", "-p", "t", "--output-format", "stream-json")
    )
  }

  test("zcode ANSI 前导剥离: ESC 序列头的 JSON 行照常解析；字符串值内 JSON 转义 ESC 原样保留") {
    // 真实形态：进度渲染把 raw ESC 控制序列留在行首（JSON 外），剥离后照常成帧。
    val prefixed = ExternalExecutor.ZCode.parseLine(
      "\u001b[2K\u001b[1G" + """{"type":"result","sessionId":"sess_ansi","response":"done","projection":{"status":"idle"}}"""
    )
    assertEquals(
      prefixed.collect { case f: ExternalExecutor.Frame.ExecutionEnd => (f.result, f.failed) },
      List((Some("done"), false))
    )
    // JSON 字符串值内的 ESC 以 6 字符转义形态上线——剥离器只碰 raw 字节，载荷无损。
    val wireEsc = "\\" + "u001b[31mRED" // 线上形态（无 raw ESC）；经 circe 解码 = raw ESC + "[31m" + "RED"
    val inPayload = ExternalExecutor.ZCode.parseLine(
      s"""{"type":"model.streaming","payload":{"kind":"text_delta","delta":"$wireEsc"}}"""
    )
    val decoded = "\u001b[31m" // raw ESC + "[31m"（解码后形态，与剥离器无关）
    assertEquals(inPayload, List(ExternalExecutor.Frame.Text(decoded + "RED")))
  }

  test("zcode 真机 stream-json 采样回放: 噪声事件零帧; text_delta 分片重组 = result.response; result 行 → Start+Final") {
    // 2026-11 实测 zcode --output-format stream-json 真机采样（PROBE-OK 探针）
    // 逐行摘录：titleUpdated/turn.started/session.updated/model.streaming
    // (start/reasoning_*) 均零帧；四个 text_delta 分片重组恰为终局 response。
    val realSample = List(
      """{"eventId":"132e99f0-2ff9-424a-aff4-f59e76e95b1f","payload":{"previousTitle":"","source":"first_input","title":"Reply with exactly PROBE-OK and nothing else."},"seq":1,"sessionId":"sess_769d2175-2590-4543-a375-1d93da2343a2","timestamp":1794291414291,"type":"session.titleUpdated"}""",
      """{"eventId":"574d5752-4b6a-45a5-b9b4-db099f34209d","payload":{"turnNumber":0,"input":"Reply with exactly PROBE-OK and nothing else."},"seq":2,"sessionId":"sess_769d2175-2590-4543-a375-1d93da2343a2","timestamp":1794291414292,"type":"turn.started"}""",
      """{"eventId":"72783931-b891-4ac4-825b-fc512a81e570","payload":{"messageCount":6,"providerId":"p","modelId":"glm","toolCount":28,"iteration":0},"seq":3,"sessionId":"sess_769d2175-2590-4543-a375-1d93da2343a2","timestamp":1794291415145,"type":"session.updated"}""",
      """{"eventId":"c1","payload":{"kind":"start","assistantMessageId":"msg_1"},"seq":5,"sessionId":"sess_769d2175-2590-4543-a375-1d93da2343a2","type":"model.streaming"}""",
      """{"eventId":"c2","payload":{"kind":"reasoning_start"},"seq":6,"sessionId":"sess_769d2175-2590-4543-a375-1d93da2343a2","type":"model.streaming"}""",
      """{"eventId":"c3","payload":{"kind":"reasoning_delta","delta":"thinking"},"seq":7,"sessionId":"sess_769d2175-2590-4543-a375-1d93da2343a2","type":"model.streaming"}""",
      """{"eventId":"c4","payload":{"kind":"reasoning_end"},"seq":8,"sessionId":"sess_769d2175-2590-4543-a375-1d93da2343a2","type":"model.streaming"}""",
      """{"eventId":"c5","payload":{"kind":"text_start","assistantMessageId":"msg_1"},"seq":27,"sessionId":"sess_769d2175-2590-4543-a375-1d93da2343a2","type":"model.streaming"}""",
      """{"eventId":"c6","payload":{"assistantMessageId":"msg_1","delta":"PRO","done":false,"kind":"text_delta"},"seq":28,"sessionId":"sess_769d2175-2590-4543-a375-1d93da2343a2","type":"model.streaming"}""",
      """{"eventId":"c7","payload":{"assistantMessageId":"msg_1","delta":"BE","done":false,"kind":"text_delta"},"seq":29,"sessionId":"sess_769d2175-2590-4543-a375-1d93da2343a2","type":"model.streaming"}""",
      """{"eventId":"c8","payload":{"assistantMessageId":"msg_1","delta":"-","done":false,"kind":"text_delta"},"seq":30,"sessionId":"sess_769d2175-2590-4543-a375-1d93da2343a2","type":"model.streaming"}""",
      """{"eventId":"c9","payload":{"assistantMessageId":"msg_1","delta":"OK","done":false,"kind":"text_delta"},"seq":31,"sessionId":"sess_769d2175-2590-4543-a375-1d93da2343a2","type":"model.streaming"}""",
      """{"eventId":"ca","payload":{"kind":"text_end"},"seq":32,"sessionId":"sess_769d2175-2590-4543-a375-1d93da2343a2","type":"model.streaming"}""",
      """{"eventId":"cb","payload":{"kind":"finish"},"seq":34,"sessionId":"sess_769d2175-2590-4543-a375-1d93da2343a2","type":"model.streaming"}""",
      """{"type":"result","sessionId":"sess_769d2175-2590-4543-a375-1d93da2343a2","turnId":"turn_3343c6c7","response":"PROBE-OK","usage":{"inputTokens":18368,"outputTokens":24},"eventCount":35,"projection":{"status":"idle","turnCount":1,"contextWindow":200000}}"""
    )
    val frames = realSample.flatMap(ExternalExecutor.ZCode.parseLine)

    // 全流只有一台 Start（result 行带 sessionId；session.created 本采样未发）。
    assertEquals(
      frames.collect { case ExternalExecutor.Frame.Start(s) => s },
      List(Some("sess_769d2175-2590-4543-a375-1d93da2343a2"))
    )
    // 分片重组 = 终局 response（PROBE-OK），证明 text_delta 通道无损。
    assertEquals(frames.collect { case ExternalExecutor.Frame.Text(t) => t }.mkString, "PROBE-OK")
    assertEquals(frames.collect { case ExternalExecutor.Frame.Tool(l) => l }, Nil)
    assertEquals(
      frames.collect { case f: ExternalExecutor.Frame.ExecutionEnd => (f.result, f.failed) },
      List((Some("PROBE-OK"), false))
    )
  }

  test("Claude adapter preserves assistant identity and original content block indexes") {
    val frames = ExternalExecutor.ClaudeCode.parseLine(
      """{"type":"assistant","message":{"id":"message-1","content":[{"type":"text","text":"First"},{"type":"tool_use","name":"Read"},{"type":"text","text":"Second"}]}}"""
    )
    assertEquals(
      frames.collect { case frame: ExternalExecutor.Frame.MessageComplete => frame },
      List(
        ExternalExecutor.Frame.MessageComplete(
          Some("message-1"),
          List(
            ExternalExecutor.Frame.ContentBlock(0, "First"),
            ExternalExecutor.Frame.ContentBlock(2, "Second")
          )
        )
      )
    )
    assertEquals(frames.collect { case ExternalExecutor.Frame.Tool(name) => name }, List("Read"))
  }

  test("Claude result UUID is not an assistant correlation identity") {
    val frames = ExternalExecutor.ClaudeCode.parseLine(
      """{"type":"result","uuid":"result-event","session_id":"session","subtype":"success","result":"Done"}"""
    )
    val end = frames.collectFirst { case frame: ExternalExecutor.Frame.ExecutionEnd => frame }.get
    assertEquals(end.messageId, None)
    assertEquals(end.metadata.hcursor.get[String]("uuid").toOption, Some("result-event"))
    assertEquals(end.result, Some("Done"))
  }

  test("Codex adapter retains stable item identity and recognizes successful turn completion") {
    val frames = ExternalExecutor.Codex.parseLine(
      """{"type":"item.completed","item":{"id":"item-1","type":"agent_message","text":"blocked: need access"}}"""
    ) ++ ExternalExecutor.Codex.parseLine("""{"type":"turn.completed","usage":{"input_tokens":7}}""")
    assertEquals(
      frames.head,
      ExternalExecutor.Frame.MessageComplete(
        Some("item-1"),
        List(ExternalExecutor.Frame.ContentBlock(0, "blocked: need access"))
      )
    )
    val end = frames.collectFirst { case frame: ExternalExecutor.Frame.ExecutionEnd => frame }.get
    assert(!end.failed)
    assertEquals(end.result, None)
    assertEquals(end.metadata.hcursor.downField("usage").get[Int]("input_tokens").toOption, Some(7))
  }

  test("Malformed adapter lines do not poison subsequent valid input") {
    ExternalExecutor.all.foreach { adapter =>
      assertEquals(adapter.parseLine("{\"type\":"), Nil)
    }
    assertEquals(
      ExternalExecutor.Codex.parseLine("""{"type":"thread.started","thread_id":"after-malformed"}"""),
      List(ExternalExecutor.Frame.Start(Some("after-malformed")))
    )
  }

  // ── 注册表与选择面 ──────────────────────────────────────────────

  test(
    "registry: three real adapters; hermes has NO adapter object (placeholder lives in ExecutorRegistry.defs only)"
  ) {
    assertEquals(ExternalExecutor.all.map(_.id), List("zcode", "claude-code", "codex"))
    assertEquals(ExternalExecutor.forId("hermes"), None)
    assert(ExecutorRegistry.defs.find(_.id == "hermes").exists(d => !d.adapterReady))
  }

  test("selection: absent param + 无配置 ⇒ 内置 nebflow（hermetic——注入空 settings，不吃机器配置）") {
    val bare = ExecutorRegistry.Settings(None, Nil)
    assertEquals(
      ExecutorRegistry.resolveRequested(None, settings = bare),
      Right(ExecutorRegistry.ResolvedSelection.Internal)
    )
    assertEquals(
      ExecutorRegistry.resolveRequested(Some(""), settings = bare),
      Right(ExecutorRegistry.ResolvedSelection.Internal)
    )
    assertEquals(
      ExecutorRegistry.resolveRequested(Some("  nebflow "), settings = bare),
      Right(ExecutorRegistry.ResolvedSelection.Internal)
    )
  }

  test("selection: 配置默认语义 — 缺省参数走 executor.default（不可检测 ⇒ fail-closed；disabled/缺省 ⇒ 内置兜底）") {
    withTempDataRoot { tmp =>
      // ① 配置 default 指向外部且二进制在 ⇒ 缺省参数解析到该外部执行器。
      os.write.over(PathUtil.configJsonReadPath(tmp), """{"executor": {"default": "claude-code"}}""")
      withFakeBin("claude") { dir =>
        ExecutorRegistry.resolveRequested(None, pathEnv = dir.toString, probeDirs = Nil) match
          case Right(ExecutorRegistry.ResolvedSelection.External(defn, bin)) =>
            assertEquals(defn.id, "claude-code")
            assertEquals(bin, (dir / "claude").toString)
          case other => fail(s"expected External(claude-code), got $other")
      }
      // ② 配置 default 指向外部但二进制不可检测 ⇒ Left 显式失败，绝不静默回落。
      ExecutorRegistry.resolveRequested(None, pathEnv = "", probeDirs = Nil) match
        case Left(msg) =>
          assert(msg.contains("claude-code"), msg)
          assert(msg.toLowerCase.contains("no silent fallback"), msg)
        case other => fail(s"expected Left fail-closed, got $other")
      // ③ 配置 default 指向 disabled 的执行器 ⇒ effectiveDefault 回落内置。
      os.write.over(
        PathUtil.configJsonReadPath(tmp),
        """{"executor": {"default": "claude-code", "disabled": ["claude-code"]}}"""
      )
      assertEquals(
        ExecutorRegistry.resolveRequested(None, pathEnv = "", probeDirs = Nil),
        Right(ExecutorRegistry.ResolvedSelection.Internal)
      )
      // ④ 显式 "nebflow" 恒解析内置（显式选择优先于配置默认）。
      os.write.over(PathUtil.configJsonReadPath(tmp), """{"executor": {"default": "claude-code"}}""")
      assertEquals(
        ExecutorRegistry.resolveRequested(Some("nebflow"), pathEnv = "", probeDirs = Nil),
        Right(ExecutorRegistry.ResolvedSelection.Internal)
      )
      // ⑤ 显式选择不吃 disabled（disabled 只作用于配置默认；检测命中即放行）。
      os.write.over(
        PathUtil.configJsonReadPath(tmp),
        """{"executor": {"default": "claude-code", "disabled": ["zcode"]}}"""
      )
      withFakeBin("zcode") { dir =>
        ExecutorRegistry.resolveRequested(Some("zcode"), pathEnv = dir.toString, probeDirs = Nil) match
          case Right(ExecutorRegistry.ResolvedSelection.External(defn, bin)) =>
            assertEquals(defn.id, "zcode")
            assertEquals(bin, (dir / "zcode").toString)
          case other => fail(s"expected External(zcode), got $other")
      }
      // ⑥ 配置块缺省（无 executor 键）⇒ 缺省参数 = 内置 nebflow（零配置零变化）。
      os.write.over(PathUtil.configJsonReadPath(tmp), """{"thinkingConfig": {}}""")
      assertEquals(
        ExecutorRegistry.resolveRequested(None, pathEnv = "", probeDirs = Nil),
        Right(ExecutorRegistry.ResolvedSelection.Internal)
      )
    }
  }

  test("selection fail-closed: unknown id / hermes placeholder / undetected binary = explicit Left, NEVER Internal") {
    withTempDataRoot { tmp =>
      def assertNoFallback(got: Either[String, ExecutorRegistry.ResolvedSelection], whyContains: String) =
        got match
          case Left(msg) =>
            assert(msg.contains(whyContains), s"error should mention '$whyContains': $msg")
            assert(msg.contains("nebflow") && msg.toLowerCase.contains("no silent fallback"), msg)
          case Right(ExecutorRegistry.ResolvedSelection.Internal) =>
            fail("fail-closed violated: silent fallback to nebflow")
          case Right(_) => fail("expected explicit failure")

      assertNoFallback(
        ExecutorRegistry.resolveRequested(Some("no-such-cli"), pathEnv = "", probeDirs = Nil),
        "not in the registry"
      )
      assertNoFallback(
        ExecutorRegistry.resolveRequested(Some("hermes"), pathEnv = "", probeDirs = Nil),
        "adapter is not wired"
      )
      assertNoFallback(
        ExecutorRegistry.resolveRequested(Some("claude-code"), pathEnv = "", probeDirs = Nil),
        "not detected"
      )
      // 错误面带可用集（自纠错输入）——内置兜底恒在可用集里。
      ExecutorRegistry.resolveRequested(Some("claude-code"), pathEnv = "", probeDirs = Nil) match
        case Left(msg) => assert(msg.contains("nebflow"), s"available-set line should list nebflow: $msg")
        case other => fail(s"expected Left, got $other")
    }
  }

  test("selection: detected external resolves with its absolute binary path") {
    withTempDataRoot { tmp =>
      withFakeBin("claude") { dir =>
        ExecutorRegistry.resolveRequested(Some("claude-code"), pathEnv = dir.toString, probeDirs = Nil) match
          case Right(ExecutorRegistry.ResolvedSelection.External(defn, bin)) =>
            assertEquals(defn.id, "claude-code")
            assertEquals(bin, (dir / "claude").toString)
          case other => fail(s"expected External(claude-code), got $other")
      }
    }
  }

  test("availableLine: built-in always listed; detected externals with path; undetected omitted") {
    withTempDataRoot { tmp =>
      val bare = ExecutorRegistry.availableLine(pathEnv = "", probeDirs = Nil)
      assert(bare.contains("- nebflow —"), bare)
      assert(!bare.contains("claude-code"), bare)
      withFakeBin("codex") { dir =>
        val line = ExecutorRegistry.availableLine(pathEnv = dir.toString, probeDirs = Nil)
        assert(line.contains("- codex — Codex"), line)
        assert(line.contains((dir / "codex").toString), line)
      }
    }
  }

  test("external session ids: prefix + validId-compatible charset (DelegateRegistry address face)") {
    val sid = ExternalSessionRunner.newSessionIdForTest("claude-code")
    assert(sid.startsWith("external-claude-code-"), sid)
    assert(nebflow.core.delegate.DelegateRegistry.validId(sid), sid)
  }
end ExternalExecutorSpec
