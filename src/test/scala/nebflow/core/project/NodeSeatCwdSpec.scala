package nebflow.core.project

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.agent.{AgentState, sessionCwd}
import nebflow.core.tools.{BashTool, ToolContext}

import scala.concurrent.duration.*
import scala.util.Random

/**
 * **B5 缺口②（座椅 cwd）回归** —— 作者 2026-09-17 M-1 裁定「会话启动即 `cd` 座椅」
 * （选项①：新增显式 `sessionCwd` 信号，会话 shell 初 cwd = 座椅；**不推翻** 2026-09-05
 * 21:05 `sandboxRoot` 裁定——围栏根仍是工作区根）。
 *
 * 逐条对齐任务书判据：
 *  - **A① 正向**：worktree 节点会话的 `pwd` **逐字** = 座椅路径（真实 shell 读数，非
 *    纯函数断言：BashTool → ShellSession.forSession(initialDir) → buildProcessBuilder
 *    全链真跑）。
 *  - **A② fail-closed**：座椅目录缺失 ⇒ 该会话 Bash **显式失败**（`InvalidCwdError`
 *    文案，`Bash cwd 不可用`），**不**静默回落工作区根（旧根因形态）。
 *  - **A③ 负控（零回归）**：无 `sessionCwd`（= 非 worktree 节点 / 分发器 / 双轨会话）
 *    ⇒ 初 cwd **逐字不变**（= JVM `user.dir`，旧行为）。
 *  - **A④ 透传链 + 接线**：`AgentState.spawn` 携带该信号到 `SessionContext`；三处消费点
 *    （NodeEngine 两个 spawn 点 / AgentCore → ToolContext / BashTool.initialDir）接线在
 *    位（静态断言——防「字段加了但没人用」的半修形态）。
 *
 * 🔴 本 spec 零实例、零端口、零 `sbt run`；只用一次性 `target/` 目录与短命令。
 */
class NodeSeatCwdSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val root: os.Path = os.pwd / "target" / "test-node-seat-cwd"

  private def bashCall(cmd: String, ctx: ToolContext): IO[Either[String, String]] =
    BashTool.call(JsonObject("command" -> cmd.asJson), ctx).map(_.left.map(_.message))

  private def ctxFor(sessionId: String, seat: Option[String]): ToolContext =
    ToolContext(projectRoot = root.toString, sessionId = Some(sessionId), sessionCwd = seat)

  /** 每次用独立 sessionId：ShellSession 按 sessionId 全局缓存（初 cwd 只在创建时取）。 */
  private def sid(tag: String): String = s"b5-seat-$tag-${Random.nextInt(1000000)}"

  private def lines(out: String): List[String] =
    out.linesIterator.map(_.trim).filter(_.nonEmpty).toList

  /**
   * Bash 结果首行是 `(cwd: <dir>)` 前缀（BashTool.formatResult 的既有形态）——它是
   * 「本调用在哪个目录里跑」的第二读数，断言单独取用；`pwd` 读数取其后的命令行。
   */
  private def cwdPrefix(out: String): String =
    lines(out).find(_.startsWith("(cwd:")).getOrElse("")

  private def cmdLines(out: String): List[String] =
    lines(out).filterNot(_.startsWith("(cwd:"))

  test("A① 缺口② 正向：会话 shell 初 cwd = 座椅，`pwd` / `pwd -P` 逐字等于座椅路径") {
    val seat = root / "seat-live"
    os.makeDir.all(seat)
    val s = sid("live")
    for r <- bashCall("pwd; pwd -P", ctxFor(s, Some(seat.toString)))
    yield
      val out = r.fold(e => fail(s"Bash must succeed on an existing seat, got: $e"), identity)
      val ls = cmdLines(out)
      assertEquals(ls.size, 2, s"two pwd readings expected, raw output:\n$out")
      assertEquals(ls.head, seat.toString, s"`pwd` must be the seat verbatim; raw output:\n$out")
      assertEquals(ls(1), seat.toString, s"`pwd -P` (physical cwd) must be the seat verbatim; raw output:\n$out")
      assert(
        cwdPrefix(out).contains(seat.toString),
        s"the tool's own cwd stamp must name the seat too; raw output:\n$out"
      )
  }

  test("A② 缺口② fail-closed：座椅目录缺失 ⇒ 该会话 Bash 显式失败（不回落工作区根）") {
    val gone = root / "seat-removed"
    os.remove.all(gone)
    val s = sid("gone")
    for r <- bashCall("pwd", ctxFor(s, Some(gone.toString)))
    yield
      val msg = r.fold(identity, out => fail(s"must fail closed, but the command ran and printed: $out"))
      assert(msg.contains("Bash cwd 不可用"), s"explicit cwd failure text expected, got: $msg")
      assert(msg.contains("路径不存在") || msg.contains("does not exist"), s"reason must say the path is missing, got: $msg")
      assert(msg.contains(gone.toString), s"the missing seat path must be named, got: $msg")
      assert(msg.contains("site"), s"the diagnostic must name the trigger site, got: $msg")
      assert(!msg.contains(root.toString + "\n"), s"must not silently run from the workspace root, got: $msg")
  }

  test("A③ 缺口② 负控（零回归）：无 sessionCwd ⇒ 初 cwd 逐字不变（= JVM user.dir）") {
    val s = sid("noseat")
    for r <- bashCall("pwd -P", ctxFor(s, None))
    yield
      val out = r.fold(e => fail(s"plain Bash must still work without a seat signal, got: $e"), identity)
      assertEquals(
        cmdLines(out).headOption.getOrElse(""),
        os.pwd.toString,
        s"absent the seat signal the shell keeps the legacy cwd; raw output:\n$out"
      )
  }

  test("A④ 缺口② 透传链 + 消费点接线：AgentState → SessionContext，且三处接线在位（静态断言）") {
    val st = AgentState(sessionCwd = Some("/seat/demo"))
    assertEquals(st.sessionCwd, Some("/seat/demo"), "AgentState accessor must surface SessionContext.sessionCwd")
    assertEquals(st.session.sessionCwd, Some("/seat/demo"), "SessionContext must carry the signal")
    assertEquals(AgentState().sessionCwd, None, "absent the signal the field stays None (zero change)")

    val bashSrc = os.read(os.pwd / "src/main/scala/nebflow/core/tools/BashTool.scala")
    assert(
      bashSrc.contains("ctx.sessionCwd.orElse(sandboxOpt.map(_.root.toString))"),
      "BashTool must derive the shell initialDir from ctx.sessionCwd (fallback = sandbox root)"
    )
    val coreSrc = os.read(os.pwd / "src/main/scala/nebflow/agent/AgentCore.scala")
    assert(
      coreSrc.contains("sessionCwd = state.session.sessionCwd"),
      "AgentCore must thread SessionContext.sessionCwd into ToolContext"
    )
    val engineSrc = os.read(os.pwd / "src/main/scala/nebflow/core/project/NodeEngine.scala")
    assertEquals(
      "sessionCwd = Some\\(projectRoot\\),".r.findAllIn(engineSrc).size,
      2,
      "both NodeEngine spawn points (normal node / loop session) must pass the seat signal"
    )
  }
end NodeSeatCwdSpec
