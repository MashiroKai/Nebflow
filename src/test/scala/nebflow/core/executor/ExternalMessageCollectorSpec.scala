package nebflow.core.executor

import io.circe.Json
import munit.FunSuite
import nebflow.core.executor.ExternalExecutor.Frame

class ExternalMessageCollectorSpec extends FunSuite:

  private def fixture(name: String): List[String] =
    val stream = Option(getClass.getResourceAsStream(s"/external-message-collector/$name.jsonl"))
      .getOrElse(fail(s"Missing fixture: $name"))
    val source = scala.io.Source.fromInputStream(stream, "UTF-8")
    try source.getLines().toList
    finally source.close()

  private def collect(name: String, adapter: ExternalExecutor.ExternalExecutor): ExternalMessageCollector =
    val collector = new ExternalMessageCollector("run-one")
    fixture(name).flatMap(adapter.parseLine).foreach(collector.accept)
    collector.flush()
    collector

  private def message(id: String, text: String): Frame.MessageComplete =
    Frame.MessageComplete(Some(id), List(Frame.ContentBlock(0, text)))

  test("Codex blocked plain text is preserved with an explicit successful terminal") {
    val collector = collect("codex-blocked", ExternalExecutor.Codex)
    assertEquals(collector.allTexts, List("blocked: credentials are unavailable"))
    assertEquals(collector.messages.map(_.protocolMessageId), List(Some("item-report")))
    assert(collector.hasTerminal)
    assert(!collector.failed)
    assertEquals(collector.finalResults.size, 1)
    assertEquals(collector.diagnostics, Nil)
  }

  test("Claude report blocks, trailing message and every result survive independently") {
    val frames = fixture("claude-multiple-results").flatMap(ExternalExecutor.ClaudeCode.parseLine)
    val collector = new ExternalMessageCollector("run-one")
    val emitted = frames.flatMap(collector.accept) ++ collector.flush()
    assertEquals(emitted, collector.messages)
    assertEquals(collector.allTexts, List("Report summary.", "Report detail.", "Waiting for instructions."))
    assertEquals(collector.messages.map(_.blockIndex), List(0, 2, 0))
    assertEquals(collector.messages.map(_.protocolMessageId), List(Some("report"), Some("report"), Some("trailing")))
    assertEquals(collector.finalResults.size, 3)
    assertEquals(collector.diagnostics, Nil)
  }

  test("Identity suppresses retransmission but never suppresses legitimate identical text") {
    val collector = collect("claude-identities", ExternalExecutor.ClaudeCode)
    assertEquals(collector.allTexts, List("Same text.", "Same text."))
    assertEquals(collector.messages.map(_.protocolMessageId), List(Some("message-one"), Some("message-two")))
    assertEquals(collector.messages.map(_.id).distinct.size, 2)
    assertEquals(collector.flush(), Nil)
    val other = new ExternalMessageCollector("run-two")
    other.accept(message("message-one", "Same text."))
    assertNotEquals(other.messages.head.id, collector.messages.head.id)
  }

  test("Malformed input cannot poison following complete messages; EOF is not success") {
    val collector = collect("codex-malformed-missing-terminal", ExternalExecutor.Codex)
    assertEquals(collector.allTexts, List("Still collected."))
    assert(!collector.hasTerminal)
    assertEquals(collector.finalResults, Nil)
    assertEquals(collector.diagnostics, List("The external CLI stream ended without a protocol terminal event."))
  }

  test("Result correlation without identity requires exact immediate adjacency") {
    val collector = new ExternalMessageCollector("adjacency")
    collector.accept(message("old", "Repeated"))
    collector.accept(message("new", "Different"))
    collector.accept(Frame.ExecutionEnd(false, Some("Repeated")))
    assertEquals(collector.allTexts, List("Repeated", "Different", "Repeated"))
    collector.accept(message("last", "Adjacent"))
    assertEquals(collector.accept(Frame.ExecutionEnd(false, Some("Adjacent"))), Nil)
    collector.accept(message("tool-separated", "Again"))
    collector.accept(Frame.Tool("Read"))
    collector.accept(Frame.ExecutionEnd(false, Some("Again")))
    assertEquals(collector.allTexts.takeRight(2), List("Again", "Again"))
  }

  test("Explicit correlation does not discard a different assistant message with identical text") {
    val collector = new ExternalMessageCollector("identity")
    collector.accept(message("one", "Same"))
    collector.accept(message("two", "Same"))
    collector.accept(Frame.ExecutionEnd(false, Some("Same"), Some("one")))
    assertEquals(collector.allTexts, List("Same", "Same"))
    collector.accept(Frame.ExecutionEnd(false, Some("Same"), Some("unknown")))
    assertEquals(collector.allTexts, List("Same", "Same", "Same"))
  }

  test("Result-event identity deduplicates fallback text, not whole history") {
    val collector = new ExternalMessageCollector("results")
    val end = Frame.ExecutionEnd(false, Some("Fallback"), metadata = Json.obj("uuid" -> Json.fromString("result-one")))
    collector.accept(end)
    collector.accept(message("legitimate", "Fallback"))
    collector.accept(end)
    assertEquals(collector.allTexts, List("Fallback", "Fallback"))
    assertEquals(collector.finalResults.size, 2)
  }

  test("Failed terminals preserve result text without marking collection corruption") {
    val collector = new ExternalMessageCollector("failure")
    collector.accept(message("report", "Partial report"))
    collector.accept(Frame.ExecutionEnd(true, Some("Blocked: missing permissions.")))
    collector.accept(Frame.ExecutionEnd(false))
    assert(collector.failed)
    assert(collector.hasTerminal)
    assertEquals(collector.allTexts, List("Partial report", "Blocked: missing permissions."))
    assertEquals(collector.finalResults.size, 2)
    assertEquals(collector.diagnostics, Nil)
  }

  test("Transport error envelopes remain metadata rather than assistant output") {
    val collector = new ExternalMessageCollector("transport-error")
    ExternalExecutor.Codex
      .parseLine(
        """{"type":"turn.failed","error":{"message":"secret transport error"}}"""
      )
      .foreach(collector.accept)
    assert(collector.failed)
    assert(collector.hasTerminal)
    assertEquals(collector.allTexts, Nil)
    assertEquals(collector.diagnostics, Nil)
    assertEquals(
      collector.finalResults.head.metadata.hcursor.downField("error").get[String]("message").toOption,
      Some("secret transport error")
    )
  }

  test("ZCode deltas are committed once per complete message, not once per token") {
    val frames = fixture("zcode-stream").flatMap(ExternalExecutor.ZCode.parseLine)
    val collector = new ExternalMessageCollector("zcode")
    frames.foreach {
      case frame: Frame.Text => assertEquals(collector.accept(frame), Nil)
      case frame => collector.accept(frame)
    }
    collector.flush()
    assertEquals(collector.allTexts, List("Hello world"))
    assertEquals(collector.messages.head.protocolMessageId, Some("stream-one"))
    assert(collector.hasTerminal)
  }

  test("Bounded delta drain preserves ordered chunks and exact terminal correlation") {
    val collector = new ExternalMessageCollector("chunks")
    collector.accept(Frame.MessageBoundary(Some("stream")))
    collector.accept(Frame.Text("Hello "))
    assertEquals(collector.drainPending().map(_.text), List("Hello "))
    assert(!collector.hasTerminal)
    assertEquals(collector.diagnostics, Nil)
    assertEquals(collector.drainPending(), Nil)
    collector.accept(Frame.Text("world"))
    assertEquals(collector.accept(Frame.ExecutionEnd(false, Some("Hello world"))).map(_.text), List("world"))
    assertEquals(collector.allTexts, List("Hello ", "world"))
    assertEquals(collector.messages.map(_.blockIndex), List(0, 1))
    assertEquals(collector.messages.map(_.protocolMessageId), List(Some("stream"), Some("stream")))
    assertEquals(collector.flush(), Nil)
  }

  test("Legacy frames remain source compatible and EOF flushes pending text exactly once") {
    val collector = new ExternalMessageCollector("legacy")
    collector.accept(Frame.Text("partial"))
    assertEquals(collector.flush().map(_.text), List("partial"))
    assertEquals(collector.flush(), Nil)
    assert(!collector.hasTerminal)
    val finalFrame = Frame.Final("done", failed = false)
    assertEquals(
      finalFrame match
        case Frame.Final(text, _) => text
      ,
      "done"
    )
    collector.accept(finalFrame)
    assert(collector.hasTerminal)
  }
end ExternalMessageCollectorSpec
