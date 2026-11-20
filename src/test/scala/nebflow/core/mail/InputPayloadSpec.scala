package nebflow.core.mail

import io.circe.syntax.*
import io.circe.parser.decode
import munit.CatsEffectSuite
import nebflow.actor.AgentCommand
import nebflow.shared.ContentBlock

/**
 * 用户输入落磁盘邮箱批 P3 —— 载荷契约验收（设计 §2.5 D1/D2）。
 *
 * 承重断言只有两条：
 *  1. **往返无损**：可序列化字段逐字回来（否则重放会静默丢字段＝"自洽但漏掉主腿"）；
 *  2. **运行句柄绝不落盘**：`UserInput.replyTo`（`ActorRef`，本次运行句柄）必须被
 *     剥掉，只留 `replyToSessionId`（身份）；重建命令时 `replyTo` 恒 `None`。
 */
class InputPayloadSpec extends CatsEffectSuite:

  private def fullUserInput: AgentCommand.UserInput =
    AgentCommand.UserInput(
      text = "hello world",
      replyTo = None, // 运行句柄不入载荷，见下方专项用例
      clientMessageId = Some("cid-123"),
      blocks = Some(List(ContentBlock.Text("blk"))),
      chatWidth = 42,
      source = Some("delegate-wake"),
      sender = Some("panel"),
      senderTeam = Some("core"),
      delivery = Some("queue"),
      eventType = Some("info"),
      intake = Some("mail"),
      fromUser = true,
      project = Some("stand"),
      queuedAtMs = Some(1700000000000L)
    )

  test("UserInput round-trips through JSON with every serializable field intact") {
    val p = InputPayload.ofUser(fullUserInput, replyToSessionId = Some("sid-parent"))
    val json = p.asJson
    val back = decode[InputPayload](json.noSpaces)
    assert(back.isRight, s"decode failed: $back")
    assertEquals(back.toOption.get, p)
    // 逐字段点名（等价性之外的可读判据）
    val r = back.toOption.get
    assertEquals(r.kind, InputPayload.KindUser)
    assertEquals(r.text, "hello world")
    assertEquals(r.clientMessageId, Some("cid-123"))
    assertEquals(r.chatWidth, 42)
    assertEquals(r.source, Some("delegate-wake"))
    assertEquals(r.sender, Some("panel"))
    assertEquals(r.senderTeam, Some("core"))
    assertEquals(r.delivery, Some("queue"))
    assertEquals(r.eventType, Some("info"))
    assertEquals(r.intake, Some("mail"))
    assertEquals(r.fromUser, true)
    assertEquals(r.project, Some("stand"))
    assertEquals(r.queuedAtMs, Some(1700000000000L))
    assertEquals(r.replyToSessionId, Some("sid-parent"))
  }

  test("ImmediateInput round-trips, windowItems included (notifypack 载荷不得丢)") {
    val imm = AgentCommand.ImmediateInput(
      text = "notify",
      source = Some("node"),
      eventType = Some("completed"),
      fromUser = false,
      project = Some("stand"),
      windowItems = Some(List(AgentCommand.WindowItem("t1", "n1", "done", "p/n1")))
    )
    val p = InputPayload.ofImmediate(imm)
    val back = decode[InputPayload](p.asJson.noSpaces).toOption.get
    assertEquals(back, p)
    assertEquals(back.kind, InputPayload.KindImmediate)
    assertEquals(back.windowItems.map(_.map(_.nodeName)), Some(List("n1")))
  }

  test("运行句柄不落盘：replyTo 被剥掉，只留身份；重建恒 replyTo=None") {
    val p = InputPayload.ofUser(fullUserInput, replyToSessionId = Some("sid-parent"))
    val json = p.asJson.noSpaces
    assert(!json.contains("replyTo\""), s"raw replyTo key must not be persisted: $json")
    val rebuilt = InputPayload.toCommand(decode[InputPayload](json).toOption.get)
    assert(rebuilt.isDefined)
    rebuilt.get match
      case ui: AgentCommand.UserInput =>
        assertEquals(ui.replyTo, None, "重放不得复原上一进程的 ActorRef")
        assertEquals(ui.text, "hello world")
      case other => fail(s"expected UserInput, got $other")
  }

  test("缺席可选键的旧形 JSON 仍可解码（向后兼容，默认值兜底）") {
    val minimal = """{"kind":"immediate","text":"bare"}"""
    val back = decode[InputPayload](minimal)
    assert(back.isRight, s"decode failed: $back")
    val r = back.toOption.get
    assertEquals(r.text, "bare")
    assertEquals(r.fromUser, false)
    assertEquals(r.chatWidth, 0)
    assertEquals(r.blocks, None)
    assertEquals(r.replyToSessionId, None)
  }

  test("未知 kind 不重建命令（非法载荷不得变成任意命令）") {
    val p = InputPayload(kind = "bogus", text = "x")
    assertEquals(InputPayload.toCommand(p), None)
  }
end InputPayloadSpec
