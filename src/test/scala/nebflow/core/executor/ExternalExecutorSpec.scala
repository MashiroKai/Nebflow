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
 *     zcode --json 摘要＋stream-json 兼容双方言）；噪声行零帧、绝不抛；
 *   - 选择反转：显式参数是唯一输入、缺省 nebflow；nebflow.json executor 块
 *     （default/disabled）**止读**（⑫）——配置怎么写都不改选择；
 *   - fail-closed（3.4）：不在册 / 适配未就绪（hermes）/ 未检测到二进制 ⇒
 *     Left 显式错误（消息含可用集），**零静默回落 nebflow**。
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
    os.perms.set(dir / name, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
    try f(dir)
    finally os.remove.all(dir)

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
    assertEquals(frames.collect { case ExternalExecutor.Frame.Text(t) => t }, List("Planning the move.", "Done."))
    assertEquals(frames.collect { case ExternalExecutor.Frame.Tool(l) => l }, List("Bash", "Read"))
    assertEquals(
      frames.collect { case f: ExternalExecutor.Frame.Final => f },
      List(ExternalExecutor.Frame.Final("Moved the file.", failed = false))
    )
  }

  test("claude-code: error result flags failed; noise lines yield zero frames, never throw") {
    val err = ExternalExecutor.ClaudeCode.parseLine(
      """{"type":"result","subtype":"error_during_execution","is_error":true,"result":"boom"}"""
    )
    assertEquals(err, List(ExternalExecutor.Frame.Final("boom", failed = true)))

    val noise = List("", "   ", "not json at all", "[1,2,3]", """{"type":"other"}""")
      .flatMap(ExternalExecutor.ClaudeCode.parseLine)
    assertEquals(noise, Nil)
  }

  test("claude-code launchArgs: fresh = print+stream-json+verbose; resume appends --resume") {
    assertEquals(
      ExternalExecutor.ClaudeCode.launchArgs("do it", None),
      List("-p", "do it", "--output-format", "stream-json", "--verbose")
    )
    assertEquals(
      ExternalExecutor.ClaudeCode.launchArgs("continue", Some("sess-9f13")),
      List("-p", "continue", "--output-format", "stream-json", "--verbose", "--resume", "sess-9f13")
    )
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
    assertEquals(frames.collect { case ExternalExecutor.Frame.Text(t) => t }, List("Tests pass."))
    assert(frames.collect { case f: ExternalExecutor.Frame.Final => f }.isEmpty)

    val failed = ExternalExecutor.Codex.parseLine("""{"type":"turn.failed","error":{"message":"sandbox denied"}}""")
    assertEquals(failed, List(ExternalExecutor.Frame.Final("sandbox denied", failed = true)))
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
      frames.collect { case f: ExternalExecutor.Frame.Final => f.text },
      List("ok — done \"quickly\"\nsecond line")
    )
    assertEquals(frames.collect { case f: ExternalExecutor.Frame.Final => f.failed }, List(false))
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
      frames.collect { case f: ExternalExecutor.Frame.Final => f },
      List(ExternalExecutor.Frame.Final("All done.", failed = false))
    )
  }

  test("zcode: turn.failed flags failed; projection.status=error 摘要亦然; 噪声行零帧绝不抛") {
    val failed = ExternalExecutor.ZCode.parseLine(
      """{"type":"turn.failed","sessionId":"sess_x","payload":{"message":"provider unreachable"}}"""
    )
    assertEquals(failed, List(ExternalExecutor.Frame.Final("provider unreachable", failed = true)))

    val errSummary = ExternalExecutor.ZCode.parseLine(
      """{"type":"result","sessionId":"sess_x","response":"partial","projection":{"status":"error"}}"""
    )
    assertEquals(
      errSummary,
      List(ExternalExecutor.Frame.Start(Some("sess_x")), ExternalExecutor.Frame.Final("partial", failed = true))
    )

    val noise =
      List("", "   ", "not json", "{", "}", "[1,2,3]", """  "eventCount": 3,""", """  "traceId": "abc",""").flatMap(
        ExternalExecutor.ZCode.parseLine
      )
    assertEquals(noise, Nil)
  }

  test("zcode launchArgs: fresh = -p + --json; resume = --resume 前置（晨裁①-B 旗标轮换）") {
    assertEquals(ExternalExecutor.ZCode.launchArgs("t", None), List("-p", "t", "--json"))
    assertEquals(ExternalExecutor.ZCode.launchArgs("t", Some("zc-1")), List("--resume", "zc-1", "-p", "t", "--json"))
  }

  // ── 注册表与选择面 ──────────────────────────────────────────────

  test(
    "registry: three real adapters; hermes has NO adapter object (placeholder lives in ExecutorRegistry.defs only)"
  ) {
    assertEquals(ExternalExecutor.all.map(_.id), List("zcode", "claude-code", "codex"))
    assertEquals(ExternalExecutor.forId("hermes"), None)
    assert(ExecutorRegistry.defs.find(_.id == "hermes").exists(d => !d.adapterReady))
  }

  test("selection: absent param resolves built-in nebflow (缺省语义)") {
    assertEquals(ExecutorRegistry.resolveRequested(None), Right(ExecutorRegistry.ResolvedSelection.Internal))
    assertEquals(ExecutorRegistry.resolveRequested(Some("")), Right(ExecutorRegistry.ResolvedSelection.Internal))
    assertEquals(
      ExecutorRegistry.resolveRequested(Some("  nebflow ")),
      Right(ExecutorRegistry.ResolvedSelection.Internal)
    )
  }

  test("selection (⑫止读): executor block default/disabled are NOT consulted — config can't steer the pick") {
    withTempDataRoot { tmp =>
      // 写一块最真实的配置：default 指向外部、disabled 掐掉另一个外部——
      // 选择面必须当它不存在（快照/显示面语义不变，那是另一条轴）。
      os.write.over(
        PathUtil.configJsonReadPath(tmp),
        """{"executor": {"default": "claude-code", "disabled": ["zcode"]}}"""
      )
      // 缺省参数仍 = 内置 nebflow（配置的 default 不再是选择输入）。
      assertEquals(ExecutorRegistry.resolveRequested(None), Right(ExecutorRegistry.ResolvedSelection.Internal))
      // disabled 的外部执行器照样可选（disabled 不再是选择输入）——检测命中即放行。
      withFakeBin("zcode") { dir =>
        ExecutorRegistry.resolveRequested(Some("zcode"), pathEnv = dir.toString, probeDirs = Nil) match
          case Right(ExecutorRegistry.ResolvedSelection.External(defn, bin)) =>
            assertEquals(defn.id, "zcode")
            assertEquals(bin, (dir / "zcode").toString)
          case other => fail(s"expected External(zcode), got $other")
      }
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
