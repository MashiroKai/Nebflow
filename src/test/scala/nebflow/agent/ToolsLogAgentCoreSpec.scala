package nebflow.agent

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import io.circe.parser
import io.circe.syntax.*
import nebflow.core.ToolExecResult
import nebflow.core.ToolsLogWriter
import nebflow.core.LlmLogWriter
import nebflow.core.tools.ToolContext
import nebflow.shared.{LlmRequest, ToolCall}
import munit.FunSuite

import java.nio.file.{Files, Path}
import java.time.Instant
import scala.jdk.CollectionConverters.*

/** 方案 B（审计 20260903 §5）真链路验收 ⑤：经真实 AgentCore.executeTool 链
  * （registry 查找 → hook 引擎 → 工具执行 → flatTap 埋点）跑真实工具执行，
  * 成功行 + 三种失败变体（工具不存在 / Left 返回 / 抛异常）各落一条 JSONL 到
  * 临时目录；⑥ requestId 关联——同一条 id 同时出现在 tools 与 router 两类 JSONL
  * （LlmLogWriter 目录重定向到临时路径，不碰真实日志）。
  * 风格照 HookExecutionIntegrationSpec（CoreProbe 模式，AgentCore private[agent]）。 */
class ToolsLogAgentCoreSpec extends FunSuite:

  private val toolsDate: String = Instant.now().toString.take(10)

  // AgentCore.executeTool is protected; expose via minimal stub (AllowedToolSetSpec pattern)
  private object CoreProbe extends AgentCore:
    def exec(call: ToolCall, ctx: ToolContext): IO[ToolExecResult] = executeTool(call, ctx)

  private def tmpDir(prefix: String): Path = Files.createTempDirectory(prefix)

  private def mkCtx(root: os.Path, requestId: Option[String] = None): ToolContext =
    ToolContext(
      projectRoot = root.toString,
      sessionId = Some("sess-toolslog"),
      requestId = requestId
    )

  private def readToolLines(dir: Path): List[io.circe.Json] =
    val p = dir.resolve(s"$toolsDate.jsonl")
    if !Files.exists(p) then Nil
    else
      Files.readAllLines(p).asScala.toList.filter(_.nonEmpty).map { line =>
        parser.parse(line).toOption.fold(fail(s"unparseable line: ${line.take(120)}"))(identity)
      }

  private def findByTool(lines: List[io.circe.Json], tool: String): io.circe.Json =
    lines.filter(_.hcursor.get[String]("tool").toOption.contains(tool)) match
      case j :: _ => j
      case Nil    => fail(s"no tools JSONL line for tool=$tool in ${lines.map(_.hcursor.get[String]("tool").toOption)}")

  override def afterEach(context: munit.AfterEach): Unit =
    ToolsLogWriter.flushSync()
    ToolsLogWriter.resetDirForTest()
    LlmLogWriter.resetLogDirForTest()
    super.afterEach(context)

  test("success: real Read tool via AgentCore chain lands a complete success row") {
    val root = os.Path(tmpDir("nb-toolslog-ok-").toString)
    val dir = tmpDir("nb-toolslog-out-")
    ToolsLogWriter.setDirForTest(dir)
    try
      os.write(root / "hello.txt", "hello tools log")
      val call = ToolCall(id = "t-ok", name = "Read", input = JsonObject("file_path" -> (root / "hello.txt").toString.asJson))
      val res = CoreProbe.exec(call, mkCtx(root)).unsafeRunSync()
      assert(!res.isError, s"Read failed: ${res.content.take(200)}")

      ToolsLogWriter.flushSync()
      val lines = readToolLines(dir)
      assertEquals(lines.size, 1, s"exactly one structured row: ${lines.size}")
      val json = findByTool(lines, "Read")
      assertEquals(json.hcursor.get[Boolean]("isError").toOption, Some(false))
      assertEquals(json.hcursor.get[String]("errorText").toOption, Some(""))
      assert(json.hcursor.get[Int]("resultChars").toOption.exists(_ > 0), "success row resultChars > 0")
      val summary = json.hcursor.get[String]("inputSummary").toOption.get
      // Read.summarize 输出多行格式："Read(hello.txt)\n  (\"<path>\")"
      assert(summary.startsWith("Read(") && summary.contains("hello.txt"), s"inputSummary 同源于 tool.summarize: $summary")
      assertEquals(json.hcursor.get[String]("sessionId").toOption, Some("sess-toolslog"))
      // 非 agentDef / 非 registry 上下文：值可空、key 必在
      assert(json.asObject.get.keys.exists(_ == "agent"))
      assert(json.asObject.get.keys.exists(_ == "kind"))
      assert(json.asObject.get.keys.exists(_ == "requestId"))
      assert(json.hcursor.get[Long]("elapsedMs").toOption.exists(_ >= 0))
    finally
      os.remove.all(root)
      os.remove.all(os.Path(dir))
  }

  test("failure variant 1: tool not found (path converged — bypassed flatTap pre-fix)") {
    val root = os.Path(tmpDir("nb-toolslog-nf-").toString)
    val dir = tmpDir("nb-toolslog-nfout-")
    ToolsLogWriter.setDirForTest(dir)
    try
      val call = ToolCall(id = "t-nf", name = "NoSuchTool_XYZ", input = JsonObject())
      val res = CoreProbe.exec(call, mkCtx(root)).unsafeRunSync()
      assert(res.isError)

      ToolsLogWriter.flushSync()
      val json = findByTool(readToolLines(dir), "NoSuchTool_XYZ")
      assertEquals(json.hcursor.get[Boolean]("isError").toOption, Some(true))
      assertEquals(
        json.hcursor.get[String]("errorText").toOption,
        Some("No such tool available: NoSuchTool_XYZ")
      )
    finally
      os.remove.all(root)
      os.remove.all(os.Path(dir))
  }

  test("failure variant 2: tool returns Left(ToolError) — Read on missing file") {
    val root = os.Path(tmpDir("nb-toolslog-left-").toString)
    val dir = tmpDir("nb-toolslog-leftout-")
    ToolsLogWriter.setDirForTest(dir)
    try
      val missing = root / "nope.txt"
      val call = ToolCall(id = "t-left", name = "Read", input = JsonObject("file_path" -> missing.toString.asJson))
      val res = CoreProbe.exec(call, mkCtx(root)).unsafeRunSync()
      assert(res.isError, "Read on missing file must error")

      ToolsLogWriter.flushSync()
      val json = findByTool(readToolLines(dir), "Read")
      val err = json.hcursor.get[String]("errorText").toOption.get
      assertEquals(json.hcursor.get[Boolean]("isError").toOption, Some(true))
      assert(err.contains("File does not exist"), s"Left(ToolError) message preserved: ${err.take(120)}")
    finally
      os.remove.all(root)
      os.remove.all(os.Path(dir))
  }

  test("failure variant 3: tool throws — exception message (2500 chars) preserved untruncated") {
    val root = os.Path(tmpDir("nb-toolslog-ex-").toString)
    val dir = tmpDir("nb-toolslog-exout-")
    ToolsLogWriter.setDirForTest(dir)
    val big = "BOOM-" + ("z" * 2495) // 2500 chars
    // 测试专用爆炸工具：经真实 ToolRegistry + AgentCore 链执行
    val exploding = new nebflow.core.tools.Tool:
      def name = "ExplodingTool_ToolsLogSpec"
      def description = "spec-only tool that always throws"
      def inputSchema = JsonObject.empty
      def summarize(input: JsonObject) = "ExplodingTool(spec)"
      def summarizeResult(input: JsonObject, result: String) = result
      def call(input: JsonObject, ctx: ToolContext): IO[Either[nebflow.core.tools.ToolError, String]] =
        IO.raiseError(new RuntimeException(big))
    nebflow.core.tools.ToolRegistry.registerTool(exploding)
    try
      val call = ToolCall(id = "t-ex", name = "ExplodingTool_ToolsLogSpec", input = JsonObject())
      val res = CoreProbe.exec(call, mkCtx(root)).unsafeRunSync()
      assert(res.isError, "throwing tool must surface as error result")

      ToolsLogWriter.flushSync()
      val json = findByTool(readToolLines(dir), "ExplodingTool_ToolsLogSpec")
      val err = json.hcursor.get[String]("errorText").toOption.get
      assertEquals(json.hcursor.get[Boolean]("isError").toOption, Some(true))
      assert(err.contains("Tool execution error:"), s"wrapped exception prefix: ${err.take(80)}")
      assert(err.contains(big), "full 2500-char exception message preserved (no truncation)")
      assert(err.length >= 2500, s"errorText length ${err.length} >= 2500")
    finally
      nebflow.core.tools.ToolRegistry.unregisterTool("ExplodingTool_ToolsLogSpec")
      os.remove.all(root)
      os.remove.all(os.Path(dir))
  }

  test("⑥ requestId: tools row and router row share the same request_id; non-LLM rows carry null") {
    val root = os.Path(tmpDir("nb-toolslog-align-").toString)
    val toolsDir = tmpDir("nb-toolslog-tools-")
    val routerDir = tmpDir("nb-toolslog-router-")
    ToolsLogWriter.setDirForTest(toolsDir)
    LlmLogWriter.setLogDirForTest(routerDir)
    val alignId = "req-align-toolslog-42"
    try
      os.write(root / "f.txt", "align me")
      // 同一轮：LLM 请求（真实 LlmLogWriter 写 router summary/full/sse）+
      // 其触发的工具执行（真实 AgentCore 链，ctx.requestId 由 pipeToolExecutions
      // 从 ConsumeResult 注入——此处直接给 ToolContext 赋同源 id）
      LlmLogWriter
        .log(
          request = LlmRequest(messages = Nil, sessionId = "sess-toolslog", agentId = "spec-agent"),
          chunks = Nil,
          resultText = "ok",
          resultToolCalls = Nil,
          resultThinking = None,
          resultStopReason = Some("end_turn"),
          resultUsage = None,
          resultModel = Some("spec-model"),
          isSubagent = false,
          isCompaction = false,
          requestId = Some(alignId)
        )
        .unsafeRunSync()
      val call = ToolCall(id = "t-align", name = "Read", input = JsonObject("file_path" -> (root / "f.txt").toString.asJson))
      CoreProbe.exec(call, mkCtx(root, requestId = Some(alignId))).unsafeRunSync()
      // 非 LLM 触发的执行（requestId 缺失）——key 必在、值 null
      val callNoReq = ToolCall(id = "t-noreq", name = "Read", input = JsonObject("file_path" -> (root / "f.txt").toString.asJson))
      CoreProbe.exec(callNoReq, mkCtx(root, requestId = None)).unsafeRunSync()

      ToolsLogWriter.flushSync()

      // router 侧：summary 行（真实 request entry）携带该 id
      val routerLines =
        Files.readAllLines(routerDir.resolve(s"${toolsDate}_summary.jsonl")).asScala.toList.filter(_.nonEmpty)
      val routerReq = routerLines.find(_.contains(alignId))
      assert(routerReq.isDefined, s"router summary line with $alignId not found in: ${routerLines.map(_.take(80))}")
      val routerJson = parser.parse(routerReq.get).toOption.get
      assertEquals(routerJson.hcursor.get[String]("type").toOption, Some("request"))
      assertEquals(routerJson.hcursor.get[String]("request_id").toOption, Some(alignId))

      // tools 侧：同一 id 出现在真实链路产生的行
      val toolLines = readToolLines(toolsDir)
      val aligned = toolLines.find(_.noSpaces.contains(alignId))
      assert(aligned.isDefined, s"tools line with $alignId not found in ${toolLines.size} lines")
      assertEquals(aligned.get.hcursor.get[String]("requestId").toOption, Some(alignId))

      // 非 LLM 行：requestId key 存在、值为 null
      val others = toolLines.filterNot(_.noSpaces.contains(alignId))
      assert(others.size >= 1, "the non-LLM row exists")
      others.foreach { j =>
        assert(j.asObject.get.keys.exists(_ == "requestId"), "requestId key always present")
        assertEquals(j.hcursor.get[Option[String]]("requestId").toOption, Some(None), "null when not LLM-triggered")
      }
    finally
      os.remove.all(root)
      os.remove.all(os.Path(toolsDir))
      os.remove.all(os.Path(routerDir))
  }

end ToolsLogAgentCoreSpec
