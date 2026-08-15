package nebflow.core.flow

import io.circe.parser.decode
import io.circe.syntax.*
import munit.FunSuite
import nebflow.core.entity.{NodeResult, NodeRoute}

/**
 * Unit tests for the unified flow semantics (Phase 1):
 *  - VerdictFamily normalization table — every alias entry
 *  - matchCase pipeline layers (exact / family / substring / none)
 *  - NodeStatus wire mapping + toNodeStatus derivation
 *  - NodeRoute Switch `default` decoding (EntityTypes extension)
 *  - code-review scenario regression (ok/error, pass/fix)
 */
class FlowSemanticsSpec extends FunSuite:

  // ------------------------------------------------------------
  // VerdictFamily — normalization table (every entry)
  // ------------------------------------------------------------

  test("OkFamily: every alias normalizes to the ok family"):
    for alias <- VerdictFamily.OkFamily do
      assertEquals(VerdictFamily.familyOf(alias), Some("ok"), s"alias '$alias' should be ok-family")

  test("ErrorFamily: every alias normalizes to the error family"):
    for alias <- VerdictFamily.ErrorFamily do
      assertEquals(VerdictFamily.familyOf(alias), Some("error"), s"alias '$alias' should be error-family")

  test("familyOf: unknown values return None"):
    assertEquals(VerdictFamily.familyOf("unknown"), None)
    assertEquals(VerdictFamily.familyOf("retry"), None)
    assertEquals(VerdictFamily.familyOf(""), None)
    assertEquals(VerdictFamily.familyOf("merge"), None) // domain value, not a family alias

  test("familyOf: case and whitespace insensitive"):
    assertEquals(VerdictFamily.familyOf("  PASS  "), Some("ok"))
    assertEquals(VerdictFamily.familyOf("Failed"), Some("error"))
    assertEquals(VerdictFamily.familyOf("DONE"), Some("ok"))

  // ------------------------------------------------------------
  // matchCase pipeline
  // ------------------------------------------------------------

  test("matchCase: exact match wins (case-insensitive)"):
    assertEquals(VerdictFamily.matchCase("ok", Set("ok", "error")), Some("ok"))
    assertEquals(VerdictFamily.matchCase("PASS", Set("pass", "fix")), Some("pass"))
    assertEquals(VerdictFamily.matchCase("revise", Set("pass", "revise")), Some("revise"))
    assertEquals(VerdictFamily.matchCase("merge", Set("merge", "reject")), Some("merge"))
    assertEquals(VerdictFamily.matchCase("reject", Set("merge", "reject")), Some("reject"))

  test("matchCase: family alias resolves to the single family key in cases"):
    // code-review scanner: agent says "pass" but flow.json declares { ok, error } →
    // family hit maps pass → ok (Phase 2 style, works without flow.json change)
    assertEquals(VerdictFamily.matchCase("pass", Set("ok", "error")), Some("ok"))
    assertEquals(VerdictFamily.matchCase("success", Set("ok", "error")), Some("ok"))
    assertEquals(VerdictFamily.matchCase("done", Set("ok", "error")), Some("ok"))
    assertEquals(VerdictFamily.matchCase("fail", Set("ok", "error")), Some("error"))
    assertEquals(VerdictFamily.matchCase("false", Set("ok", "error")), Some("error"))
    assertEquals(VerdictFamily.matchCase("rejected", Set("ok", "error")), Some("error"))

  test("matchCase: family alias not applied when family is ambiguous in cases"):
    // cases declaring both ok-family keys is an anti-pattern (flow.json spec
    // forbids synonym duplicates) — engine refuses to guess.
    assertEquals(VerdictFamily.matchCase("pass", Set("ok", "pass")), Some("pass")) // exact wins
    assertEquals(VerdictFamily.matchCase("done", Set("ok", "pass")), None) // family hit ambiguous

  test("matchCase: substring containment with warn (legacy fallback)"):
    // raw is a superstring of a case key — substring hit (warns internally)
    assertEquals(VerdictFamily.matchCase("passes", Set("pass", "fix")), Some("pass"))
    // raw is a substring of a case key
    assertEquals(VerdictFamily.matchCase("err", Set("error", "ok")), Some("error"))

  test("matchCase: no match returns None"):
    assertEquals(VerdictFamily.matchCase("unknown", Set("ok", "error")), None)
    assertEquals(VerdictFamily.matchCase("", Set("ok", "error")), None)
    assertEquals(VerdictFamily.matchCase("merge", Set("pass", "fix")), None)

  test("matchCase: revise is an error-family alias (per normalization table)"):
    // revise ∈ ErrorFamily — family hit when cases declare a single error key
    assertEquals(VerdictFamily.matchCase("revise", Set("ok", "error")), Some("error"))
    // but against domain cases {pass, revise} the exact key wins
    assertEquals(VerdictFamily.matchCase("revise", Set("pass", "revise")), Some("revise"))

  // ------------------------------------------------------------
  // NodeStatus
  // ------------------------------------------------------------

  test("NodeStatus: wire values are stable kebab-case"):
    assertEquals(NodeStatus.Pending.wire, "pending")
    assertEquals(NodeStatus.Running.wire, "running")
    assertEquals(NodeStatus.Completed.wire, "completed")
    assertEquals(NodeStatus.Failed.wire, "failed")
    assertEquals(NodeStatus.Cancelled.wire, "cancelled")

  test("NodeStatus: fromWire round-trip + unknown is None (never silent)"):
    for s <- NodeStatus.values do assertEquals(NodeStatus.fromWire(s.wire), Some(s))
    assertEquals(NodeStatus.fromWire("PENDING"), Some(NodeStatus.Pending)) // case-insensitive
    assertEquals(NodeStatus.fromWire("weird"), None)
    assertEquals(NodeStatus.fromWire(""), None)

  test("NodeStatus: toNodeStatus derives from NodeResult.success and cancel flag"):
    assertEquals(
      NodeStatus.toNodeStatus(NodeResult("n1", "out", success = true), cancelled = false),
      NodeStatus.Completed
    )
    assertEquals(
      NodeStatus.toNodeStatus(NodeResult("n1", "", success = false, error = Some("boom")), cancelled = false),
      NodeStatus.Failed
    )
    // cancellation overrides success
    assertEquals(
      NodeStatus.toNodeStatus(NodeResult("n1", "out", success = true), cancelled = true),
      NodeStatus.Cancelled
    )

  // ------------------------------------------------------------
  // NodeRoute Switch `default` decoding (EntityTypes extension)
  // ------------------------------------------------------------

  test("Switch decodes with optional default route"):
    val json =
      """{
        |  "switch": "$reviewer.verdict",
        |  "cases": { "pass": "$return", "fix": "fixer" },
        |  "default": "$return"
        |}""".stripMargin
    val decoded = decode[NodeRoute](json)
    assert(decoded.isRight, s"should decode: $decoded")
    decoded.foreach {
      case NodeRoute.Switch(expr, cases, default, _) =>
        assertEquals(expr, "$reviewer.verdict")
        assertEquals(cases.keySet, Set("pass", "fix"))
        assertEquals(default, Some(NodeRoute.Return))
      case other => fail(s"expected Switch, got $other")
    }

  test("Switch decodes without default (backward compatible)"):
    val json =
      """{
        |  "switch": "$scanner.status",
        |  "cases": { "ok": "reviewer", "error": "$return" }
        |}""".stripMargin
    val decoded = decode[NodeRoute](json)
    assert(decoded.isRight, s"should decode: $decoded")
    decoded.foreach {
      case NodeRoute.Switch(expr, cases, default, _) =>
        assertEquals(expr, "$scanner.status")
        assertEquals(cases.keySet, Set("ok", "error"))
        assertEquals(default, None)
      case other => fail(s"expected Switch, got $other")
    }

  test("Switch encodes default back to JSON"):
    val sw = NodeRoute.Switch(
      "$reviewer.verdict",
      Map("pass" -> NodeRoute.Return, "fix" -> NodeRoute.Goto("fixer")),
      Some(NodeRoute.Return)
    )
    val json = summon[io.circe.Encoder[NodeRoute]].apply(sw)
    assertEquals(json.hcursor.downField("default").as[String], Right("$return"))

  // ------------------------------------------------------------
  // code-review scenario regression
  // ------------------------------------------------------------

  test("code-review scanner: ok/error route (JSON status field)"):
    // scanner outputs {"status": "ok"} → field value "ok" → exact case hit
    assertEquals(VerdictFamily.matchCase("ok", Set("ok", "error")), Some("ok"))
    // scanner outputs {"status": "error"} → error case
    assertEquals(VerdictFamily.matchCase("error", Set("ok", "error")), Some("error"))
    // legacy: scanner outputs "pass"/"fail" → normalized by family
    assertEquals(VerdictFamily.matchCase("pass", Set("ok", "error")), Some("ok"))
    assertEquals(VerdictFamily.matchCase("fail", Set("ok", "error")), Some("error"))

  test("code-review reviewer: pass/fix domain values route"):
    assertEquals(VerdictFamily.matchCase("pass", Set("pass", "fix")), Some("pass"))
    assertEquals(VerdictFamily.matchCase("fix", Set("pass", "fix")), Some("fix"))

  test("unmatched value produces None → route reports explicit error with cases"):
    // route() layer converts None + no default into an error listing cases;
    // matchCase itself must return None here (no silent fallback).
    assertEquals(VerdictFamily.matchCase("retry", Set("pass", "fix")), None)
    assertEquals(VerdictFamily.matchCase("whatever", Set("merge", "reject")), None)
end FlowSemanticsSpec
