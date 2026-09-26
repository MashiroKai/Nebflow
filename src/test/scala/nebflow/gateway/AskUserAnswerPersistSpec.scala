package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.SessionStore
import nebflow.shared.UiMessage

import java.nio.file.Files
import scala.jdk.CollectionConverters.*

/**
 * 双开缺陷批「案 B」定向 spec（2026-09-21，chain-askuserdup）。
 *
 * 定谳：`.nebflow/reports/20260921_181620_askuserdup-arch__chain-askuserdup.md`
 *   §1.2 落盘（无 id）/ §1.5 三行判据 / §5 案 B。
 *
 * 案 B = 数据面根治（同时关掉双开 + uiclean D 项「#272 残余边界」+「历史卡不可按 id
 * 关闭」三件事）。本 spec 钉两条**落盘事实**与一条**旧行兼容方向**：
 *   ① 作答行带**显式来源标记** `answerOf = 被作答的 requestId`（单一构造点
 *      [[UiMessage.askUserAnswer]]，生产消费者 = `WebSocketRoutes` 的 askUserAnswer
 *      帧处理）；
 *   ② 提问行**随行落盘 requestId**（[[UiMessage.AskUser]]）⇒ 历史恢复出的卡可
 *      id 寻址（前端重放腿按 id 替换 / `askUserClosed` 关卡可达 / 取值由数据决定）；
 *   ③ 🔴 **旧行字节不变 + 旧文件可读**：缺席即不落键（编码器逐键断言），解码旧行 ⇒
 *      `None` ⇒ 前端**回落「未作答」**（禁把历史一律读成「已作答」——那正是双开首卡
 *      恒死的成因）。
 *
 * 落盘面读数走**真实 SessionStore**（`appendUiMessages` → `flushPendingUiWrites` →
 * 读盘上 `.ui.json` 原始字节）+ 解码面读数（`getUiMessages`），两处互证。
 */
class AskUserAnswerPersistSpec extends CatsEffectSuite:

  private val askItems: List[Json] =
    List(Json.obj("question" -> Json.fromString("双开批案 B：提问行随行落盘 requestId")))

  private def withStore(test: SessionStore => IO[Unit]): Unit =
    val tmp = Files.createTempDirectory("nebflow-askpersist-test")
    val store = SessionStore(os.Path(tmp.resolve("sessions")), os.Path(tmp.resolve("tasks")))
    try
      store.load.unsafeRunSync()
      test(store).unsafeRunSync()
    finally
      if Files.exists(tmp) then
        Files.walk(tmp).sorted(java.util.Comparator.reverseOrder()).iterator().asScala.foreach(Files.deleteIfExists)

  private def uiFile(sessionsDir: os.Path, sid: String): os.Path = sessionsDir / s"$sid.ui.json"

  /**
   * 🔴 统一经 `UiMessage` 静态类型出编码（本仓只有 `given Encoder[UiMessage]`，
   *  子类型无 given ⇒ `User(...).asJson` 编译不过）。落盘形态 = 生产同款编码器。
   */
  private def js(m: UiMessage): Json = m.asJson

  private def field(m: UiMessage, key: String): Option[String] =
    m.asJson.hcursor.downField(key).as[String].toOption

  private def keys(m: UiMessage): Set[String] =
    m.asJson.asObject.map(_.keys.toSet).getOrElse(Set.empty)

  // ============================================================
  // ① 单一构造点：作答行的显式来源标记
  // ============================================================
  test("① 构造单点 askUserAnswer：answerOf = 被作答的 requestId；空白 requestId ⇒ 不标记") {
    val marked = UiMessage.askUserAnswer("alpha\nbeta", "asknb-1a2b3c4d5e6f7a8b", 1757000000000L)
    assertEquals(marked.text, "alpha\nbeta")
    assertEquals(marked.answerOf, Some("asknb-1a2b3c4d5e6f7a8b"))
    assertEquals(marked.timestamp, 1757000000000L)
    assertEquals(marked.injected, false, "卡片作答是真人动作，不是注入行")

    val unmarked = UiMessage.askUserAnswer("alpha", "   ", 1L)
    assertEquals(unmarked.answerOf, None, "无从标记来源 ⇒ 宁可不标记（禁落空串污染「有标记」语义）")
  }

  test("② 编码：有标记 ⇒ 落 answerOf 键；无标记 ⇒ 逐字不落键（旧行字节不变）") {
    val json = js(UiMessage.askUserAnswer("alpha", "ask-1", 1757000000000L))
    assertEquals(json.hcursor.downField("answerOf").as[String].toOption, Some("ask-1"))
    assertEquals(json.hcursor.downField("type").as[String].toOption, Some("user"))
    assertEquals(json.hcursor.downField("text").as[String].toOption, Some("alpha"))

    // 旧行形态：只 type/text/attachments/timestamp 四键（新增字段缺席即不落键）
    val legacyShape = UiMessage.User("alpha", timestamp = 1757000000000L)
    assertEquals(keys(legacyShape), Set("type", "text", "attachments", "timestamp"))
  }

  test("③ 解码：案 B 行往返保真；旧行缺席 ⇒ None（前端回落「未作答」）") {
    val row = UiMessage.askUserAnswer("alpha", "ask-1", 1757000000000L)
    val back = io.circe.parser.decode[UiMessage](js(row).noSpaces)
    assertEquals(back.toOption.flatMap { case u: UiMessage.User => u.answerOf; case _ => None }, Some("ask-1"))

    val legacyJson = Json.obj(
      "type" -> Json.fromString("user"),
      "text" -> Json.fromString("alpha"),
      "timestamp" -> Json.fromLong(1757000000000L)
    )
    val decoded = io.circe.parser.decode[UiMessage](legacyJson.noSpaces)
    assert(decoded.isRight, s"旧行必须可解码：$decoded")
    assertEquals(
      decoded.toOption.flatMap { case u: UiMessage.User => u.answerOf; case _ => None },
      None,
      "旧行无标记 ⇒ None（回落方向 = 未作答，禁读成已作答）"
    )
  }

  // ============================================================
  // ④ 提问行：requestId 随行落盘（历史卡可 id 寻址）+ 旧行兼容
  // ============================================================
  test("④ 编码：AskUser 带 requestId ⇒ 落键；不带 ⇒ 只 {type, items}（旧行逐字不变）") {
    val withRid = js(UiMessage.AskUser(askItems, Some("asknb-1a2b3c4d5e6f7a8b")))
    assertEquals(withRid.hcursor.downField("requestId").as[String].toOption, Some("asknb-1a2b3c4d5e6f7a8b"))
    assertEquals(withRid.asObject.map(_.keys.toSet), Some(Set("type", "items", "requestId")))

    val legacy = js(UiMessage.AskUser(askItems))
    assertEquals(
      legacy.asObject.map(_.keys.toSet),
      Some(Set("type", "items")),
      "旧 .ui.json 行的字节形态必须逐字不变（缺席即不落键）"
    )
  }

  test("⑤ 解码：带 requestId 往返保真；旧行（无 requestId 键）⇒ None ⇒ 前端走形态兜底去重腿") {
    val round = io.circe.parser.decode[UiMessage](js(UiMessage.AskUser(askItems, Some("ask-9"))).noSpaces)
    assertEquals(round.toOption.collect { case a: UiMessage.AskUser => a.requestId }.flatten, Some("ask-9"))

    val legacyJson = Json.obj(
      "type" -> Json.fromString("askUser"),
      "items" -> Json.arr(Json.obj("question" -> Json.fromString("旧行")))
    )
    val decoded = io.circe.parser.decode[UiMessage](legacyJson.noSpaces)
    assertEquals(
      decoded.toOption.collect { case a: UiMessage.AskUser => a.requestId },
      Some(None),
      "旧行请求 id 未知（不是「已作答」）"
    )
  }

  // ============================================================
  // ⑥ 真实落盘 + 读回（SessionStore 两处互证：盘上字节 + 解码面）
  // ============================================================
  test("⑥ SessionStore：提问行与作答行落盘 ⇒ 盘上字节含 requestId/answerOf，读回同值") {
    val tmp = Files.createTempDirectory("nebflow-askpersist-store")
    val sessionsDir = os.Path(tmp.resolve("sessions"))
    val store = SessionStore(sessionsDir, os.Path(tmp.resolve("tasks")))
    val sid = "askpersist-1"
    val rid = "asknb-0123456789abcdef"
    try
      (for
        _ <- store.load
        _ <- store.appendUiMessages(sid, List(UiMessage.AskUser(askItems, Some(rid))))
        _ <- store.appendUiMessages(sid, List(UiMessage.askUserAnswer("alpha\nbeta", rid, 1757000000000L)))
        _ <- store.flushPendingUiWrites
        (rows, total) <- store.getUiMessages(sid, 0, 10)
      yield
        assertEquals(total, 2)
        val askRow = rows.collectFirst { case a: UiMessage.AskUser => a }.get
        val ansRow = rows.collectFirst { case u: UiMessage.User => u }.get
        assertEquals(askRow.requestId, Some(rid), "提问行可 id 寻址（案 B 目标态）")
        assertEquals(ansRow.answerOf, Some(rid), "作答行自称作答的是这个 requestId")
        assertEquals(ansRow.text, "alpha\nbeta")
        // 盘上原始字节（落盘形态 = 前端历史恢复拿到的同一形状）
        val raw = os.read(uiFile(sessionsDir, sid))
        assert(raw.contains(s""""requestId":"$rid""""), s"盘上提问行缺 requestId：$raw")
        assert(raw.contains(s""""answerOf":"$rid""""), s"盘上作答行缺 answerOf 标记：$raw")
      ).unsafeRunSync()
    finally
      if Files.exists(tmp) then
        Files.walk(tmp).sorted(java.util.Comparator.reverseOrder()).iterator().asScala.foreach(Files.deleteIfExists)
    end try
  }

  test("⑦ 旧 .ui.json 文件（无新键）可读，且读出的两行都不带新字段（回落方向）") {
    val tmp = Files.createTempDirectory("nebflow-askpersist-legacy")
    val sessionsDir = os.Path(tmp.resolve("sessions"))
    os.makeDir.all(sessionsDir)
    // 逐字 = 案 B 之前 gateway 写出的形态（问句行只 {type, items}；作答行无标记）
    os.write(
      uiFile(sessionsDir, "legacy-1"),
      """[{"type":"askUser","items":[{"question":"旧行"}]},{"type":"user","text":"alpha","timestamp":1757000000000}]"""
    )
    val store = SessionStore(sessionsDir, os.Path(tmp.resolve("tasks")))
    try
      (for
        _ <- store.load
        (rows, total) <- store.getUiMessages("legacy-1", 0, 10)
      yield
        assertEquals(total, 2, "旧文件照常可读（禁因新字段而拒绝历史）")
        assertEquals(rows.collectFirst { case a: UiMessage.AskUser => a.requestId }.flatten, None)
        assertEquals(rows.collectFirst { case u: UiMessage.User => u.answerOf }.flatten, None)
      ).unsafeRunSync()
    finally
      if Files.exists(tmp) then
        Files.walk(tmp).sorted(java.util.Comparator.reverseOrder()).iterator().asScala.foreach(Files.deleteIfExists)
  }
end AskUserAnswerPersistSpec
