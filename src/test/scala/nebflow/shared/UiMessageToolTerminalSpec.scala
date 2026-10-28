package nebflow.shared

import io.circe.Json
import io.circe.syntax.*
import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.core.SessionStore

import java.nio.file.Files
import scala.concurrent.duration.*

/**
 * 卫生批（2026-10-28，回放批 §5 残余①根治）——`UiMessage.Tool.terminal` 的编解码 +
 * `SessionStore.markRoundTerminal` 段感知终态落点契约（`UiMessageFileChangesSpec` /
 * `UiMessageQuoteSpec` 同族镜像）：
 *
 *  ① 缺席即不落键：无标记工具行的 Encoder 输出与旧 `.ui.json` 行字节形态**逐字相同**
 *     （键集不含 `terminal`）；旧行（无键）decode ⇒ None —— 旧行可解析性零变化；
 *  ② 带标记行 round-trip：encode → decode ⇒ terminal=Some(true)；
 *  ③ markRoundTerminal 段感知三面：
 *     - 退化全工具轮（段内零 ai 行）→ terminal 盖到段内最后一行 **Tool** 行（等价
 *       终态面——回放折叠判据与 Ai.terminal 同读 data-nf-turn-terminal）；
 *     - 段内有 ai 行（中间 flush）→ 照旧回填 durationMs/model/timestamp + terminal
 *       到该 ai 行，Tool 行不动；
 *     - **不越段**：上一轮的行（含其 durationMs/model）零改写；空段（无行）no-op；
 *  ④ terminal 只加不减：重复回填不清除既有标记。
 *
 * 调用面 = `WebSocketRoutes` 的 `done`/`agentDone` 空缓冲分支（唯二）。
 */
class UiMessageToolTerminalSpec extends CatsEffectSuite:

  private var tmp: os.Path = null
  private var sessionsDir: os.Path = null
  private var store: SessionStore = null

  override def beforeEach(context: BeforeEach): Unit =
    tmp = os.Path(Files.createTempDirectory("nb-tool-terminal"))
    sessionsDir = tmp / "sessions"
    os.makeDir.all(sessionsDir)
    store = SessionStore(sessionsDir, tmp / "tasks")

  override def afterEach(context: AfterEach): Unit =
    os.remove.all(tmp)

  private val sid = "tool-terminal-spec-session"

  private def rows: IO[List[UiMessage]] =
    store.getHistoryPage(sid, 500, None).map(_._1)

  test("① 无标记 Tool 行编码不含 terminal 键（旧 .ui.json 行字节形态逐字不变）"):
    val encoded = (UiMessage.Tool("Mail", "→ delegate", "ok", isError = false, "{}"): UiMessage).asJson
    val keys = encoded.asObject.map(_.keys.toList).getOrElse(Nil)
    assert(!keys.contains("terminal"), s"缺席标记绝不落键，got keys=$keys")

  test("② 带 terminal 的 Tool 行 round-trip encode → decode"):
    val row = UiMessage.Tool("Bash", "deploy.sh", "ok", isError = false, "{}", terminal = Some(true))
    val decoded = (row: UiMessage).asJson.as[UiMessage].toOption.get
    assertEquals(decoded.asInstanceOf[UiMessage.Tool].terminal, Some(true))
    assertEquals(decoded.asInstanceOf[UiMessage.Tool].label, "Bash", "既有字段不被新键挤掉")

  test("②b 旧行（无 terminal 键）decode ⇒ None"):
    val legacy = Json.obj(
      "type" -> "tool".asJson,
      "label" -> "Mail".asJson,
      "summary" -> "→ delegate".asJson,
      "content" -> "ok".asJson,
      "isError" -> false.asJson,
      "input" -> "{}".asJson
    )
    val decoded = legacy.as[UiMessage].toOption.get.asInstanceOf[UiMessage.Tool]
    assertEquals(decoded.terminal, None)

  test("③a 退化全工具轮（段内零 ai 行）→ terminal 盖到段内最后一行 Tool 行"):
    val program =
      store.appendUiMessages(sid, List(UiMessage.User("把结论发给 delegate 续跑"))) *>
        store.appendUiMessages(sid, List(UiMessage.Tool("Mail", "→ delegate", "ok"))) *>
        store.markRoundTerminal(sid, Some(8000L), Some("glm-5.3"), 1000L)
    for _ <- program; r <- rows
    yield
      val tools = r.collect { case t: UiMessage.Tool => t }
      assertEquals(tools.length, 1, "fixture 形态 = user + 单张工具卡")
      assertEquals(tools.head.terminal, Some(true), "等价终态面 = 末行 Tool 行盖戳")
      val ais = r.collect { case a: UiMessage.Ai => a }
      assertEquals(ais.length, 0, "段内零 ai 行（退化形态本身）")

  test("③b 段内有 ai 行（中间 flush）→ 回填照旧落 ai 行，Tool 行不动"):
    val program =
      store.appendUiMessages(sid, List(UiMessage.User("跑个任务"))) *>
        store.appendUiMessages(sid, List(UiMessage.Ai("中间结论。"))) *>
        store.appendUiMessages(sid, List(UiMessage.Tool("Bash", "ls", "ok"))) *>
        store.markRoundTerminal(sid, Some(61000L), Some("glm-5.3"), 2000L)
    for _ <- program; r <- rows
    yield
      val ais = r.collect { case a: UiMessage.Ai => a }
      val tools = r.collect { case t: UiMessage.Tool => t }
      assertEquals(ais.head.durationMs, Some(61000L), "回填落段内 ai 行")
      assertEquals(ais.head.model, Some("glm-5.3"))
      assertEquals(ais.head.terminal, Some(true))
      assertEquals(tools.head.terminal, None, "有 ai 载体时 Tool 行不盖戳")

  test("③c 不越段：上一轮的行零改写（旧全局版会把新时值盖到上一轮末条 ai 行）"):
    val program =
      store.appendUiMessages(sid, List(UiMessage.User("第一轮"))) *>
        store.appendUiMessages(sid, List(UiMessage.Ai("第一轮完成。", durationMs = Some(5000L), model = Some("model-a"), timestamp = 900L, terminal = Some(true)))) *>
        store.appendUiMessages(sid, List(UiMessage.User("第二轮（全工具）"))) *>
        store.appendUiMessages(sid, List(UiMessage.Tool("Mail", "→ delegate", "ok"))) *>
        store.markRoundTerminal(sid, Some(8000L), Some("model-b"), 2000L)
    for _ <- program; r <- rows
    yield
      val ais = r.collect { case a: UiMessage.Ai => a }
      val tools = r.collect { case t: UiMessage.Tool => t }
      assertEquals(ais.head.durationMs, Some(5000L), "上一轮的时长不被越段改写")
      assertEquals(ais.head.model, Some("model-a"), "上一轮的模型不被越段改写")
      assertEquals(ais.head.terminal, Some(true), "上一轮既有标记保持（只加不减）")
      assertEquals(tools.head.terminal, Some(true), "本轮退化全工具轮的末行 Tool 行盖戳")

  test("③d 空段（最后一条 user 行之后无行）→ no-op"):
    val program =
      store.appendUiMessages(sid, List(UiMessage.User("第一轮"))) *>
        store.appendUiMessages(sid, List(UiMessage.Ai("完成。", durationMs = Some(3000L), terminal = Some(true)))) *>
        store.appendUiMessages(sid, List(UiMessage.User("空轮"))) *>
        store.markRoundTerminal(sid, Some(1000L), Some("m"), 3000L)
    for _ <- program; r <- rows
    yield
      val ais = r.collect { case a: UiMessage.Ai => a }
      assertEquals(ais.head.durationMs, Some(3000L), "空轮不越段改写任何行")

  test("④ terminal 只加不减：重复 markRoundTerminal 不清除既有标记"):
    val program =
      store.appendUiMessages(sid, List(UiMessage.User("轮"))) *>
        store.appendUiMessages(sid, List(UiMessage.Tool("Mail", "→ delegate", "ok"))) *>
        store.markRoundTerminal(sid, Some(1000L), None, 100L) *>
        store.markRoundTerminal(sid, Some(2000L), None, 200L)
    for _ <- program; r <- rows
    yield
      val tools = r.collect { case t: UiMessage.Tool => t }
      assertEquals(tools.head.terminal, Some(true), "二次回填只加不减")

  test("⑤ markRoundTerminal 与 Ai.terminal 同一判据面（Ai 分支回归守卫）"):
    val program =
      store.appendUiMessages(sid, List(UiMessage.User("轮"))) *>
        store.appendUiMessages(sid, List(UiMessage.Tool("Bash", "ls", "ok"))) *>
        store.appendUiMessages(sid, List(UiMessage.Ai("完成。", durationMs = Some(1000L), terminal = Some(true))))
    for _ <- program; r <- rows
    yield
      val terminalCarrier = r.count {
        case a: UiMessage.Ai => a.terminal.contains(true)
        case t: UiMessage.Tool => t.terminal.contains(true)
        case _ => false
      }
      assertEquals(terminalCarrier, 1, "每轮恰一个 terminal 载体（append 直写分支不受本批影响）")
