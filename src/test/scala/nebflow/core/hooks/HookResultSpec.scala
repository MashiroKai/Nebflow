package nebflow.core.hooks

import io.circe.JsonObject
import munit.FunSuite

class HookResultSpec extends FunSuite:

  private def obj(fields: (String, io.circe.Json)*): JsonObject = JsonObject.fromIterable(fields.map((k, v) => k -> v))

  test("allow defaults: no decision impact, no reason, no context") {
    val r = HookResult.allow
    assertEquals(r.decision, HookDecision.Allow)
    assert(r.reason.isEmpty)
    assert(r.additionalContext.isEmpty)
    assert(!r.shouldStop)
    assert(r.updatedInput.isEmpty)
  }

  test("block carries reason") {
    val r = HookResult.block("not allowed")
    assertEquals(r.decision, HookDecision.Block)
    assertEquals(r.reason, Some("not allowed"))
  }

  test("merge: block wins over allow regardless of order") {
    val blocked = HookResult.block("b1")
    val allowed = HookResult(additionalContext = Some("ctx"))
    assertEquals(HookResult.merge(allowed, blocked).decision, HookDecision.Block)
    assertEquals(HookResult.merge(blocked, allowed).decision, HookDecision.Block)
    assertEquals(HookResult.merge(blocked, blocked).decision, HookDecision.Block)
    assertEquals(HookResult.merge(allowed, allowed).decision, HookDecision.Allow)
  }

  test("merge: reasons are concatenated with '; '") {
    val r = HookResult.merge(HookResult.block("r1"), HookResult.block("r2"))
    assertEquals(r.reason, Some("r1; r2"))
  }

  test("merge: additional contexts concatenate with newline") {
    val r = HookResult.merge(
      HookResult(additionalContext = Some("first")),
      HookResult(additionalContext = Some("second"))
    )
    assertEquals(r.additionalContext, Some("first\nsecond"))
    val oneSided = HookResult.merge(HookResult(additionalContext = Some("only")), HookResult.allow)
    assertEquals(oneSided.additionalContext, Some("only"))
  }

  test("merge: updatedInput last wins") {
    val a = obj("file_path" -> io.circe.Json.fromString("/a"))
    val b = obj("file_path" -> io.circe.Json.fromString("/b"))
    val r1 = HookResult.merge(HookResult(updatedInput = Some(a)), HookResult(updatedInput = Some(b)))
    assertEquals(r1.updatedInput, Some(b))
    val r2 = HookResult.merge(HookResult(updatedInput = Some(a)), HookResult.allow)
    assertEquals(r2.updatedInput, Some(a))
  }

  test("merge: shouldStop is OR, stopReason last wins") {
    val r = HookResult.merge(
      HookResult(shouldStop = true, stopReason = Some("s1")),
      HookResult(shouldStop = false, stopReason = Some("s2"))
    )
    assert(r.shouldStop)
    assertEquals(r.stopReason, Some("s2"))
  }

end HookResultSpec
