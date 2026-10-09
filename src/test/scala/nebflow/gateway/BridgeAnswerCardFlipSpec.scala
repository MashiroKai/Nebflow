package nebflow.gateway

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.SessionStore
import nebflow.shared.UiMessage

import java.nio.file.Files
import scala.jdk.CollectionConverters.*

/**
 * askuser-remote P1 反射补口 spec（2026-10-09，feishu-answer-card-flip）。
 *
 * 缺陷定谳（2026-10-09 12:36 用户报单「我在飞书回答了问题，但是好像前端的问题卡片
 * 没有做相应的变化」）：10:20:31 飞书卡片作答「理解到位，出完整设计」在引擎侧全程
 * 落地（nebflow.log：`bridge interaction answer` → `InteractionAnswered` → audit
 * `event=answer … answersJoined=理解到位，出完整设计` → askUser answer bridge 注入
 * 会话），但桥面作答腿从不反射到桌面端——`.ui.json` 无 answerOf 行（刷新后历史卡
 * 永远「未答」）、无关卡帧（活卡不翻面）；12:36:22 web 端对该 rid 的 askUserCancel
 * 得 hub `unknown`（槽位早已消费、界面从未跟上）即此断层的直接读数。
 *
 * 修复 = 桥面作答的单一汇聚点（`WebSocketRoutes.forwardBridgeInteractionAnswer`）
 * 补两面，与 web 腿（`handleAskUserAnswer`）对齐。本 spec 钉**纯函数面**
 * （`BridgeAnswerReflection`）的四条事实；连接件（落库/广播 best-effort）六行见
 * WebSocketRoutes，由该函数单一调用。
 *
 *   ① 「飞书作答→卡翻面」落库半边：桥腿同款载荷 `{"answers":[…]}` ⇒
 *      `rowFor` = Some（`UiMessage.askUserAnswer` 单一构造点行，answerOf = rid，
 *      文本 = 槽位 `'\n'` 连接——与 web 腿 ：86 同构）；
 *   ② 翻面帧形态：`askUserAnsweredRemote{sessionId, requestId, answerText}`，
 *      answerText 与行文本**同源同值**（活卡锁定行 = 刷新后历史卡作答行）；
 *   ③ 非 askUser 形载荷（无 `answers` 键，如许可类 `{approved:…}`）⇒ row/frame
 *      双 None——许可卡无此反射面，改前行为逐字不变；
 *   ④ 秘密槽脱敏：秘密槽恒空照常落；明文打秘密槽 ⇒ 行/帧=拒收文案零值（④号用例）；
 *   ⑤ 落库互证：rowFor 的行经**真实 SessionStore**（append → flush → 读盘上字节 +
 *      getUiMessages 解码）读回 answerOf 在、文本同——与 AskUserAnswerPersistSpec ⑥
 *      同款判据（证明该行落库后前端历史恢复必渲染成「已作答」卡）。
 */
class BridgeAnswerCardFlipSpec extends CatsEffectSuite:

  private val feishuClickPayload: Json =
    Json.obj("answers" -> Json.arr(Json.fromString("理解到位，出完整设计")))

  private val multiSlotPayload: Json =
    Json.obj("answers" -> Json.arr(Json.fromString("就这样，派实现"), Json.fromString("实心=启用，可以")))

  test("① 飞书作答载荷 ⇒ rowFor = Some（answerOf=rid，文本=槽位 '\\n' 连接，与 web 腿同构）") {
    val row = BridgeAnswerReflection.rowFor("asknb-2f2886dfc2c34377", feishuClickPayload, Set.empty)
    assert(row.isDefined, "askUser 形载荷必须产出作答行（卡翻面的落库半边）")
    row.foreach { u =>
      assertEquals(u.answerOf, Some("asknb-2f2886dfc2c34377"), "作答行必须自称作答的是被答的 requestId")
      assertEquals(u.text, "理解到位，出完整设计")
      assertEquals(u.injected, false, "渠道作答是真人动作，不是注入行")
    }

    val multi = BridgeAnswerReflection.rowFor("asknb-multi0multi0multi", multiSlotPayload, Set.empty)
    assertEquals(multi.map(_.text), Some("就这样，派实现\n实心=启用，可以"), "多槽行文本 = '\\n' 连接（web 腿同构）")
  }

  test("② 翻面帧形态：askUserAnsweredRemote{sessionId, requestId, answerText}，answerText 与行文本同源同值") {
    val sid = "c444ce38-d794-4d94-88d3-e8cdf04de7be"
    val f = BridgeAnswerReflection.frame(sid, "asknb-2f2886dfc2c34377", feishuClickPayload, Set.empty)
    assert(f.isDefined, "askUser 形载荷必须产出翻面帧（活卡半边）")
    f.foreach { json =>
      assertEquals(json.hcursor.downField("type").as[String].toOption, Some("askUserAnsweredRemote"))
      assertEquals(json.hcursor.downField("sessionId").as[String].toOption, Some(sid))
      assertEquals(json.hcursor.downField("requestId").as[String].toOption, Some("asknb-2f2886dfc2c34377"))
      assertEquals(json.hcursor.downField("answerText").as[String].toOption, Some("理解到位，出完整设计"))
    }
    // 帧与行同源：同一载荷产出的 answerText 与落库行 text 逐字一致
    val (row, frame) = (
      BridgeAnswerReflection.rowFor("asknb-x", multiSlotPayload, Set.empty),
      BridgeAnswerReflection.frame("s", "asknb-x", multiSlotPayload, Set.empty)
    )
    assertEquals(
      frame.flatMap(_.hcursor.downField("answerText").as[String].toOption),
      row.map(_.text),
      "活卡锁定文案必须与刷新后历史卡作答行逐字一致（两面一个事实）"
    )
  }

  test("③ 非 askUser 形载荷（无 answers 键）⇒ row/frame 双 None（许可卡不反射，改前行为不变）") {
    val permPayload = Json.obj("approved" -> Json.fromBoolean(true))
    assertEquals(BridgeAnswerReflection.rowFor("panel-1", permPayload, Set.empty), None)
    assertEquals(BridgeAnswerReflection.frame("s", "panel-1", permPayload, Set.empty), None)
    assertEquals(BridgeAnswerReflection.rowFor("panel-1", Json.obj(), Set.empty), None)
    assertEquals(BridgeAnswerReflection.frame("s", "panel-1", Json.obj(), Set.empty), None)
  }

  test("④ 秘密槽脱敏：合法载荷秘密槽置空照常落；明文打秘密槽 ⇒ 行/帧都是拒收文案、零值（禁泄漏进 .ui.json/广播）") {
    val secretOnly = Set(1)
    // 合法混合卡（渠道答非秘密题，秘密槽空串——expandAnswerSlots 补齐形态）：秘密槽置空照常落
    val mixed = Json.obj("answers" -> Json.arr(Json.fromString("图片/文件/视频入站（荐）"), Json.fromString("")))
    val row = BridgeAnswerReflection.rowFor("asknb-mixed", mixed, secretOnly).get
    assertEquals(row.text, "图片/文件/视频入站（荐）\n", "秘密槽空串照常落（桌面渲染「未提供」）")
    val f = BridgeAnswerReflection.frame("s", "asknb-mixed", mixed, secretOnly).get
    assertEquals(f.hcursor.downField("answerText").as[String].toOption, Some("图片/文件/视频入站（荐）\n"))

    // 泄漏形态（明文打进秘密槽）：行与帧都只落拒收文案，明文零出现
    val leak = Json.obj("answers" -> Json.arr(Json.fromString("选项甲"), Json.fromString("sk-secret-value")))
    val leakRow = BridgeAnswerReflection.rowFor("asknb-leak", leak, secretOnly).get
    assertEquals(leakRow.text, BridgeAnswerReflection.SecretLeakRefusedLine, "泄漏 ⇒ 行=拒收文案")
    assert(!leakRow.text.contains("sk-secret-value"), "明文不得落入 .ui.json 行")
    val leakFrame = BridgeAnswerReflection.frame("s", "asknb-leak", leak, secretOnly).get
    assertEquals(
      leakFrame.hcursor.downField("answerText").as[String].toOption,
      Some(BridgeAnswerReflection.SecretLeakRefusedLine),
      "泄漏 ⇒ 帧=拒收文案（广播面同样零值）"
    )
    // 合法回执占位（(secret 前缀，hub complete 出站形态）不算明文
    assert(!BridgeAnswerReflection.plainLeakInSecretSlots(List("a", "(secret…"), secretOnly))
  }

  test("⑤ 落库互证：rowFor 的行经真实 SessionStore 落盘读回 ⇒ answerOf 在、文本同（历史卡必渲染成已作答）") {
    val tmp = Files.createTempDirectory("nebflow-bridgeflip-store")
    val sessionsDir = os.Path(tmp.resolve("sessions"))
    val store = SessionStore(sessionsDir, os.Path(tmp.resolve("tasks")))
    val sid = "bridgeflip-1"
    val rid = "asknb-2f2886dfc2c34377"
    try
      (for
        _ <- store.load
        row <- IO(BridgeAnswerReflection.rowFor(rid, multiSlotPayload, Set.empty).get)
        _ <- store.appendUiMessages(sid, List(row))
        _ <- store.flushPendingUiWrites
        (rows, total) <- store.getUiMessages(sid, 0, 10)
        raw = os.read(sessionsDir / s"$sid.ui.json")
      yield
        assertEquals(total, 1)
        val ansRow = rows.collectFirst { case u: UiMessage.User => u }.get
        assertEquals(ansRow.answerOf, Some(rid), "读回的行仍自称作答的是该 requestId")
        assertEquals(ansRow.text, "就这样，派实现\n实心=启用，可以")
        assert(raw.contains(s""""answerOf":"$rid""""), s"盘上字节缺 answerOf 标记：$raw")
      ).unsafeRunSync()
    finally
      if Files.exists(tmp) then
        Files.walk(tmp).sorted(java.util.Comparator.reverseOrder()).iterator().asScala.foreach(Files.deleteIfExists)
    end try
  }

end BridgeAnswerCardFlipSpec
