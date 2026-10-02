package nebflow.social

import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite

import nebflow.social.outbound.{AskPromptCodec, PromptAction, PromptView, PromptOption}

/**
 * Offline spec for the inbound answer mapping (askuser batch, 2026-10-01):
 * turning a card callback payload into an action, and an action set into the
 * answer list (T-4 / T-5 / T-6).
 *
 * Pure functions only — the callback is synthesised, no network and no card
 * event is needed. The answer semantics pinned here are byte-for-byte the ones
 * the local frontend already ships (`chat.js`: a fixed-length array indexed by
 * question, `''` for an unanswered slot, a JSON array string for a
 * multi-select answer, the trimmed input for a free-text answer).
 */
class FeishuAskCardParseSpec extends FunSuite:

  private def view(idx: Int, q: String, opts: List[String], allowOther: Boolean = true,
      multiple: Boolean = false): PromptView =
    PromptView(idx, q, opts.map(PromptOption(_)), allowOther = allowOther, multiple = multiple)

  private def payload(rid: String, qi: Int, oi: Int, ns: String = "ask", extra: Map[String, Json] = Map.empty): Json =
    Json.obj(
      "nf" -> Json.fromString(ns),
      "rid" -> Json.fromString(rid),
      "qi" -> Json.fromInt(qi),
      "oi" -> Json.fromInt(oi)
    ).deepMerge(Json.obj(extra.toSeq*))

  // ───────────────────────── T-4: callback → action, with named refusals ─────────────────────────

  test("T-4 a well-formed callback becomes the indexed action") {
    val r = FeishuAskCard.parse(payload("ask-1", 1, 0), knownRequestIds = Set("ask-1"))
    assertEquals(r, Right(PromptAction.Pick(1, 0)))
  }

  test("T-4b a foreign namespace is refused by name, never assumed to be ours") {
    val r = FeishuAskCard.parse(payload("ask-1", 0, 0, ns = "other"), knownRequestIds = Set("ask-1"))
    assertEquals(r.left.toOption.map(_.reason), Some("unknown-namespace"))
    val missing = FeishuAskCard.parse(Json.obj("rid" -> Json.fromString("ask-1")), knownRequestIds = Set("ask-1"))
    assertEquals(missing.left.toOption.map(_.reason), Some("unknown-namespace"))
  }

  test("T-4c an unknown or expired request id is refused by name") {
    val r = FeishuAskCard.parse(payload("ask-gone", 0, 0), knownRequestIds = Set("ask-1"))
    assertEquals(r.left.toOption.map(_.reason), Some("unknown-request-id"))
    // an empty id is as unknown as a stale one
    assertEquals(FeishuAskCard.parse(payload("", 0, 0), knownRequestIds = Set("ask-1")).left.toOption.map(_.reason),
      Some("unknown-request-id"))
  }

  test("T-4d a callback from another chat is refused, not answered into the wrong conversation") {
    val ok = FeishuAskCard.parse(payload("ask-1", 0, 0), knownRequestIds = Set("ask-1"),
      expectedChatId = Some("oc_a"), callbackChatId = Some("oc_a"))
    assertEquals(ok, Right(PromptAction.Pick(0, 0)))
    val bad = FeishuAskCard.parse(payload("ask-1", 0, 0), knownRequestIds = Set("ask-1"),
      expectedChatId = Some("oc_a"), callbackChatId = Some("oc_b"))
    assertEquals(bad.left.toOption.map(_.reason), Some("chat-mismatch"))
  }

  test("T-4e out-of-range indices and repeats are refused by name (never clamped)") {
    assertEquals(FeishuAskCard.parse(payload("ask-1", -1, 0), knownRequestIds = Set("ask-1"))
      .left.toOption.map(_.reason), Some("index-out-of-range"))
    assertEquals(FeishuAskCard.parse(payload("ask-1", 0, -2), knownRequestIds = Set("ask-1"))
      .left.toOption.map(_.reason), Some("index-out-of-range"))
    assertEquals(FeishuAskCard.parse(payload("ask-1", 0, 0), knownRequestIds = Set("ask-1"),
      alreadyAnswered = Set(0)).left.toOption.map(_.reason), Some("duplicate"))
    // a non-integer index is a refusal, not a default to question 0
    assertEquals(FeishuAskCard.parse(Json.obj(
      "nf" -> Json.fromString("ask"), "rid" -> Json.fromString("ask-1"),
      "qi" -> Json.fromString("zero"), "oi" -> Json.fromInt(0)),
      knownRequestIds = Set("ask-1")).left.toOption.map(_.reason), Some("index-out-of-range"))
  }

  test("T-4f the reserved index is the free-text action, and it carries the trimmed input") {
    val r = FeishuAskCard.parse(payload("ask-1", 0, AskPromptCodec.OtherOptionIndex,
      extra = Map("input_value" -> Json.fromString("  自定义  "))), knownRequestIds = Set("ask-1"))
    assertEquals(r, Right(PromptAction.Other(0, "自定义")))
    val empty = FeishuAskCard.parse(payload("ask-1", 0, AskPromptCodec.OtherOptionIndex), knownRequestIds = Set("ask-1"))
    assertEquals(empty, Right(PromptAction.Other(0, "")))
  }

  // ───────────────────────── T-5: action → answers, fixed-length slots ─────────────────────────

  test("T-5 answers are a fixed-length list aligned with question order, gaps filled with the empty string") {
    val views = List(view(0, "Q0", List("A0", "A1")), view(1, "Q1", List("B0", "B1")))
    val answers = AskPromptCodec.answers(views, List(PromptAction.Pick(1, 0)))
    assertEquals(answers.size, views.size, "length == question count, always")
    assertEquals(answers, List("", "B0"))
  }

  test("T-5b each question's own option label lands in its own slot") {
    val views = List(view(0, "Q0", List("A0", "A1")), view(1, "Q1", List("B0")))
    assertEquals(AskPromptCodec.answers(views, List(PromptAction.Pick(0, 1), PromptAction.Pick(1, 0))),
      List("A1", "B0"))
  }

  test("T-5c a free-text answer fills the slot with the trimmed input; a blank input leaves it empty") {
    val views = List(view(0, "Q0", List("A0")))
    assertEquals(AskPromptCodec.answers(views, List(PromptAction.Other(0, "  hello "))), List("hello"))
    assertEquals(AskPromptCodec.answers(views, List(PromptAction.Other(0, "   "))), List(""))
  }

  test("T-5d an out-of-range option index contributes nothing (it is never an answer)") {
    val views = List(view(0, "Q0", List("A0")))
    assertEquals(AskPromptCodec.answers(views, List(PromptAction.Pick(0, 7))), List(""))
    assertEquals(AskPromptCodec.answers(views, List(PromptAction.Pick(9, 0))), List(""))
  }

  // ───────────────────────── T-6: multi-select ─────────────────────────

  test("T-6 a multi-select answer is the JSON array string of the labels in option order") {
    val views = List(view(0, "Q0", List("L0", "L1", "L2"), multiple = true))
    val answers = AskPromptCodec.answers(views, List(PromptAction.Pick(0, 2), PromptAction.Pick(0, 0)))
    assertEquals(answers.size, 1)
    // the same wire shape the local frontend produces: JSON.stringify(labels)
    assertEquals(parse(answers.head).flatMap(_.as[List[String]]), Right(List("L0", "L2")))
    assertEquals(answers.head, """["L0","L2"]""")
  }

  test("T-6b a single pick on a multi-select question still serialises as an array") {
    val views = List(view(0, "Q0", List("L0", "L1"), multiple = true))
    assertEquals(AskPromptCodec.answers(views, List(PromptAction.Pick(0, 1))), List("""["L1"]"""))
  }

  test("T-6c a single-select question keeps its label verbatim, never an array") {
    val views = List(view(0, "Q0", List("L0", "L1"), multiple = false))
    assertEquals(AskPromptCodec.answers(views, List(PromptAction.Pick(0, 0), PromptAction.Pick(0, 1))), List("L0"))
  }

  test("T-6d the answers round-trip through the local decoder (the frontend's own shape)") {
    val views = List(view(0, "Q0", List("a", "b"), multiple = true), view(1, "Q1", List("x")))
    val answers = AskPromptCodec.answers(views, List(PromptAction.Pick(0, 0), PromptAction.Pick(1, 0)))
    assertEquals(io.circe.parser.decode[List[String]](answers.head), Right(List("a")))
    assertEquals(answers(1), "x")
  }

  // ───────────────────────── validation ─────────────────────────

  test("validation names the offending index instead of failing generically") {
    val views = List(view(0, "Q0", List("A0")))
    assertEquals(AskPromptCodec.validate(views, List(PromptAction.Pick(0, 0))), Right(()))
    assert(AskPromptCodec.validate(views, List(PromptAction.Pick(0, 3))).left.toOption.get
      .startsWith("option-index-out-of-range"))
    assert(AskPromptCodec.validate(views, List(PromptAction.Pick(5, 0))).left.toOption.get
      .startsWith("question-index-out-of-range"))
    // the reserved index is never an option, so it passes the range check
    assertEquals(AskPromptCodec.validate(views, List(PromptAction.Other(0, "t"))), Right(()))
  }
