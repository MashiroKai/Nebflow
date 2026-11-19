package nebflow.agent

import munit.FunSuite
import nebflow.actor.AgentCommand
import nebflow.shared.ContentBlock

/**
 * 媒体统一适配批（weixin-inbound-media-unify, 2026-10-20）——ExternalEvent
 * vision 块在注入组装面的两条钉：
 *
 *   EB-1 buildEventReminder 无块事件 = 既有单 reminder 包裹路径**逐字现状**；
 *   EB-2 任一事件带块 ⇒ `Right(blocks)`：逐事件「编号行（各含 reminder 包裹 +
 *        retryable 提示）的 Text 块 + 该事件的块」顺序拼合（单事件 = 桥媒体常态）；
 *   EB-3 idle 直投组装单点 immediateEventMessage：无块 ⇒ Left(text) 逐字；
 *        带块 ⇒ Right(Text(injectionText) + blocks)。
 */
class ExternalEventBlocksSpec extends FunSuite:

  private val img = ContentBlock.Image("QUJD", "image/png")

  private def event(
    payload: String,
    blocks: Option[List[ContentBlock]] = None,
    eventType: String = "user-message"
  ): AgentCommand.ExternalEvent =
    AgentCommand.ExternalEvent("bridge", eventType, payload, correlationId = None, blocks = blocks)

  test("EB-1 block-less events keep the single-reminder wording byte-identical") {
    val m = AgentActor.buildEventReminder(List(event("hello"), event("world")))
    val text = m.content.fold(identity, _ => fail("expected Left(text)"))
    assertEquals(
      text,
      "<system-reminder>\n1. hello\n\n2. world\n</system-reminder>",
      "the pre-batch numbered single-reminder form, verbatim"
    )
    assertEquals(m.source, Some("external"))
  }

  test("EB-2 a block event upgrades the reminder to Right(blocks): numbered text then the blocks") {
    val m = AgentActor.buildEventReminder(List(
      event("<system-reminder>…marker…</system-reminder>\n[用户附加图片: /r/m1.png]", Some(List(img)))
    ))
    m.content match
      case Right(blocks) =>
        assertEquals(blocks.size, 2, "one numbered text line + one image block")
        blocks.head match
          case ContentBlock.Text(t) =>
            assert(t.startsWith("<system-reminder>\n1. "), t)
            assert(t.contains("[用户附加图片: /r/m1.png]"), t)
            assert(t.endsWith("</system-reminder>"), t)
          case other => fail(s"expected a Text head, got $other")
        assertEquals(blocks(1), img)
      case Left(_) => fail("expected Right(blocks) for a block-carrying event")
  }

  test("EB-2b a retryable-failure hint rides the per-event numbered line inside the block path too") {
    val failing = event(
      "boom",
      eventType = "failed",
      blocks = Some(List(img))
    ).copy(metadata = io.circe.JsonObject(
      "retryable" -> io.circe.Json.True,
      "failureType" -> io.circe.Json.fromString("crash"),
      "failedSessionId" -> io.circe.Json.fromString("s9"),
      "agentName" -> io.circe.Json.fromString("Worker")
    ))
    val m = AgentActor.buildEventReminder(List(failing))
    m.content match
      case Right(blocks) =>
        blocks.head match
          case ContentBlock.Text(t) =>
            assert(t.contains("failed (failure type: crash)"), t)
            assert(t.contains("consider re-delegating"), t)
          case other => fail(s"expected a Text head, got $other")
      case Left(_) => fail("expected Right(blocks)")
  }

  test("EB-2c block-less events in a MIXED batch keep their numbered line (per-event reminder wrapper)") {
    val m = AgentActor.buildEventReminder(List(event("plain"), event("with pic", Some(List(img)))))
    m.content match
      case Right(blocks) =>
        val texts = blocks.collect { case ContentBlock.Text(t) => t }
        assertEquals(texts.size, 2)
        assert(texts(0).contains("1. plain"), texts(0))
        assert(texts(1).contains("2. with pic"), texts(1))
        assertEquals(blocks.collect { case i: ContentBlock.Image => i }, List(img))
      case Left(_) => fail("expected Right(blocks)")
  }

  test("EB-3 immediateEventMessage: no blocks ⇒ Left verbatim; blocks ⇒ Right(Text + blocks)") {
    val plain = AgentActor.immediateEventMessage("text-only", event("x"), Some("external"))
    assertEquals(plain.content, Left("text-only"))
    assertEquals(plain.source, Some("external"))

    val withPic = AgentActor.immediateEventMessage(
      "marker + [用户附加图片: /r/m1.png]",
      event("x", Some(List(img))),
      Some("external")
    )
    withPic.content match
      case Right(blocks) =>
        assertEquals(blocks.head, ContentBlock.Text("marker + [用户附加图片: /r/m1.png]"))
        assertEquals(blocks(1), img)
      case Left(_) => fail("expected Right(blocks)")
    assertEquals(withPic.source, Some("external"))
  }

end ExternalEventBlocksSpec
