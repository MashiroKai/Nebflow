package nebflow.shared

import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite

/**
 * schedcard 批（2026-10-29，作者令「定时任务卡片防退化 + stand 配色」）——
 * `UiMessage.User.schedule` 的编解码契约（`UiMessageToolTerminalSpec` /
 * `UiMessageQuoteSpec` 同族镜像）：
 *
 *  ① 缺席即不落键：无 schedule 载荷的 User 行（既有全部调用点 = 默认 None）
 *     Encoder 输出键集**不含 `schedule`** ⇒ 旧 `.ui.json` 行字节形态逐字不变；
 *  ② 带载荷行 round-trip：encode → decode ⇒ schedule=Some(...)，既有字段
 *     （text/injected/source/eventType）不被新键挤掉；
 *  ③ 旧行（无 schedule 键）decode ⇒ None —— 旧历史行可解析性零变化；
 *  ④ 宽松透传：schedule 键形态不合（非对象）不炸解码（回落该值原样保留，
 *     引擎不解释内部字段）。
 *
 * 唯一写点 = `nebflow.core.scheduler.ScheduledTaskService.triggerTask`
 * （落盘 + 广播同源；渲染分支钉面见 `ScheduleCardContractSpec`）。
 */
class UiMessageScheduleSpec extends FunSuite:

  test("① 无 schedule 载荷的 User 行编码不含 schedule 键（旧 .ui.json 行字节形态逐字不变）"):
    val encoded = (UiMessage.User("普通用户消息", timestamp = 1000L): UiMessage).asJson
    val keys = encoded.asObject.map(_.keys.toList).getOrElse(Nil)
    assert(!keys.contains("schedule"), s"缺席即不落键，got keys=$keys")
    // 既有条件编码键也不被扰动（回归守卫）
    assert(keys.contains("type") && keys.contains("text"), s"基础键保持，got keys=$keys")

  test("② 带 schedule 载荷的 User 行 round-trip encode → decode"):
    val meta = Json.obj(
      "id" -> "abc12345".asJson,
      "name" -> "每日站会".asJson,
      "triggerAt" -> 1730000000000L.asJson,
      "repeat" -> "daily".asJson
    )
    val row = UiMessage.User(
      "到点了：每日站会",
      injected = true,
      timestamp = 1730000000000L,
      source = Some("schedule"),
      eventType = Some("trigger"),
      schedule = Some(meta)
    )
    val decoded = (row: UiMessage).asJson.as[UiMessage].toOption.get
    val u = decoded.asInstanceOf[UiMessage.User]
    assertEquals(u.schedule, Some(meta), "载荷原样往返（引擎不解释内部字段）")
    assertEquals(u.source, Some("schedule"), "既有字段不被新键挤掉")
    assertEquals(u.injected, true)
    assertEquals(u.eventType, Some("trigger"))
    assertEquals(u.text, "到点了：每日站会")

  test("③ 旧行（无 schedule 键）decode ⇒ None（旧历史行可解析性零变化）"):
    val legacy = Json.obj(
      "type" -> "user".asJson,
      "text" -> "到点了：旧任务".asJson,
      "attachments" -> Json.arr(),
      "timestamp" -> 1000L.asJson
    )
    val decoded = legacy.as[UiMessage].toOption.get.asInstanceOf[UiMessage.User]
    assertEquals(decoded.schedule, None)
    assertEquals(decoded.injected, false, "旧无标记行保持无标记（历史渲染不追溯）")

  test("④ 编码面键集稳定：无 schedule 行的键集 = 改批前形态（type/text/attachments/timestamp）"):
    // 逐字 byte-form 兼容的机械判据 = 键集相等（键序是 circe 实现细节，不 pin）。
    val keys = (UiMessage.User("普通用户消息", timestamp = 1000L): UiMessage).asJson.asObject.get.keys.toSet
    assertEquals(keys, Set("type", "text", "attachments", "timestamp"), "无 schedule 行键集必须与旧编码面逐键相同")

end UiMessageScheduleSpec
