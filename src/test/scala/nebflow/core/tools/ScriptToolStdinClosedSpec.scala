package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import munit.FunSuite
import nebflow.core.PathUtil

/**
 * #547 前置修复位 · S2 组真缺陷回归守卫（ScriptTool stdin 竞态）。
 *
 * 被测缺陷（ToolLoaderSpec TOOL_DIR 两个用例的负载敏红形态 =
 * `Left("Script execution failed: Stream closed")`）：
 * 外部工具命令若**不读 stdin 就结束**（`printf '%s' "$TOOL_DIR"`、`exit`、
 * 任何即刻返回的命令），子进程退出后 JDK 的进程回收线程会关掉父进程侧的
 * stdin 管道；此后 `ScriptTool.call` 的 `stdin.write(...)` 抛
 * `IOException("Stream closed")`（管道读端全关时则为 `Broken pipe`）。
 * 该异常经 `handleError` 变成工具失败 —— **把一次完全成功的执行
 * （exit 0 + 正确 stdout）报成失败**，且丢失脚本真实结局（退出码/stdout/stderr）。
 *
 * 本 spec 用**超出管道容量**的输入把竞态钉成确定性：管道缓冲区（macOS 16–64KB）
 * 写不下 → 父进程 `write` 阻塞 → 子进程此刻必然已经退出 → 回收线程已关流 →
 * 写入必然抛异常。故「子进程不读 stdin + 输入大于管道容量」= 100% 复现，
 * 不依赖负载（改前实测红：`Script execution failed: Stream closed`）。
 *
 * 断言本体 = 工具对外契约：脚本的权威结局是**退出码 + stdout**，stdin 不可写
 * 不得改变它。`printf` 形态（真实 TOOL_DIR 用例的命令）与 `exit` 形态都钉。
 */
class ScriptToolStdinClosedSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  private def toolDirFixture(prefix: String): os.Path =
    val d = os.temp.dir(prefix = prefix)
    os.write.over(d / "tool.json", s"""{"name":"$prefix","description":"stdin race fixture","command":"true"}""")
    d

  private def scriptTool(command: String, dir: os.Path): ScriptTool =
    ScriptTool(
      ExternalToolConfig(
        name = "stdin-race-probe",
        description = "stdin race probe",
        command = command,
        inputSchema = JsonObject.empty,
        timeoutSeconds = 20
      ),
      dir
    )

  /**
   * 大于任何平台管道缓冲区的载荷（512KB ≫ macOS 16–64KB）——把「写入晚于子进程退出」
   * 从竞态钉成必然（见类头注）。
   */
  private def oversizedInput: JsonObject =
    JsonObject("pad" -> io.circe.Json.fromString("x" * (512 * 1024)))

  private val ctx = ToolContext(projectRoot = os.pwd.toString)

  test("S1: command that never reads stdin (printf form) still reports its real stdout") {
    val dir = toolDirFixture("stdin-race-printf")
    // 与 ToolLoaderSpec「TOOL_DIR env var points at the tool config directory」逐字同形：
    // 命令是 shell 内建 printf —— 不读 stdin、立即退出。
    val t = scriptTool("""printf '%s' "$TOOL_DIR"""", dir)
    val result = t.call(oversizedInput, ctx).unsafeRunSync()
    result match
      case Right(out) => assertEquals(out, dir.toString)
      case Left(err) =>
        fail(
          s"a print-only command (exit 0) must not be reported as a failure; got: ${err.message}"
        )
  }

  test("S2: command that exits without reading stdin still reports its real exit status") {
    val dir = toolDirFixture("stdin-race-exit")
    // 非零退出 + 有 stderr：stdin 不可写不得掩盖脚本**自己的**结局。
    val t = scriptTool("""printf 'real-stderr' >&2; exit 7""", dir)
    val result = t.call(oversizedInput, ctx).unsafeRunSync()
    result match
      case Left(err) =>
        assert(
          err.message.contains("real-stderr"),
          s"the script's own stderr must survive the closed stdin pipe; got: ${err.message}"
        )
        assert(
          !err.message.contains("Stream closed") && !err.message.contains("Broken pipe"),
          s"a closed stdin pipe must not mask the script's own outcome; got: ${err.message}"
        )
      case Right(out) =>
        fail(s"exit 7 must not be reported as success; got: $out")
  }

  test("S3: successful command whose stdin is closed still returns stdout (exit 0 wins)") {
    val dir = toolDirFixture("stdin-race-ok")
    val t = scriptTool("printf 'stdout-ok'; exit 0", dir)
    val result = t.call(oversizedInput, ctx).unsafeRunSync()
    assertEquals(result, Right[ToolError, String]("stdout-ok"))
  }

end ScriptToolStdinClosedSpec
