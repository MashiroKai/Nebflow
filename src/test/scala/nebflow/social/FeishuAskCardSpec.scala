package nebflow.social

import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite

import nebflow.shared.{AskItem, AskOption}
import nebflow.social.outbound.{AskPromptCodec, PromptView, PromptOption}

/**
 * Offline spec for the outbound ask-card mapping (askuser batch, 2026-10-01).
 * Pure functions only — no network, no credential, no card callback.
 *
 * The card here is an artefact: ruling A9 keeps the interactive leg unwired, so
 * what is pinned is the mapping the author reviewed (T-1…T-3), the local caps
 * and the sequential-card overflow (T-3), and the fallback decision (T-12).
 */
class FeishuAskCardSpec extends FunSuite:

  private def view(idx: Int, q: String, opts: List[String], allowOther: Boolean = true,
      multiple: Boolean = false): PromptView =
    PromptView(idx, q, opts.map(PromptOption(_)), allowOther = allowOther, multiple = multiple)

  private def values(cardJson: String): List[Json] =
    val json = parse(cardJson).toOption.get
    json.hcursor.downField("elements").as[List[Json]].getOrElse(Nil)
      .filter(_.hcursor.downField("tag").as[String].toOption.contains("action"))
      .flatMap(_.hcursor.downField("actions").as[List[Json]].getOrElse(Nil))
      .flatMap(_.hcursor.downField("value").focus)

  private def buttons(cardJson: String): List[Json] =
    val json = parse(cardJson).toOption.get
    json.hcursor.downField("elements").as[List[Json]].getOrElse(Nil)
      .filter(_.hcursor.downField("tag").as[String].toOption.contains("action"))
      .flatMap(_.hcursor.downField("actions").as[List[Json]].getOrElse(Nil))

  private def field(v: Json, k: String): Option[String] = v.hcursor.downField(k).as[String].toOption
  private def intField(v: Json, k: String): Option[Int] = v.hcursor.downField(k).as[Int].toOption

  // ───────────────────────── T-1: one question, options + Other ─────────────────────────

  test("T-1 one question with two options and Other yields three buttons with the namespace payload") {
    val cards = FeishuAskCard.render(List(view(0, "Q?", List("A", "B"))), "ask-abc")
    assertEquals(cards.size, 1)
    val vs = values(cards.head)
    assertEquals(vs.size, 3, s"two options + the Other entry:\n${cards.head}")
    assertEquals(vs.map(v => (field(v, "nf"), field(v, "rid"), intField(v, "qi"), intField(v, "oi"))),
      List[(Option[String], Option[String], Option[Int], Option[Int])](
        (Some("ask"), Some("ask-abc"), Some(0), Some(0)),
        (Some("ask"), Some("ask-abc"), Some(0), Some(1)),
        (Some("ask"), Some("ask-abc"), Some(0), Some(-1))))
    assertEquals(buttons(cards.head).map(b => field(b.hcursor.downField("text").focus.get, "content")),
      List(Some("A"), Some("B"), Some(FeishuAskCard.OtherCopy)))
  }

  test("T-1b the payload never carries a session id (the answer resolves it from its own ledger)") {
    val card = FeishuAskCard.render(List(view(0, "Q?", List("A"))), "ask-abc").head
    values(card).foreach { v =>
      val keys = v.asObject.get.keys.toSet
      assertEquals(keys, Set("nf", "rid", "qi", "oi"), s"no extra key may ride the payload: $keys")
    }
  }

  test("T-1c a question with allowOther=false carries no Other button") {
    val card = FeishuAskCard.render(List(view(0, "pick a directory", List("w1"), allowOther = false)), "ask-x").head
    assertEquals(values(card).size, 1)
    assertEquals(intField(values(card).head, "oi"), Some(0))
  }

  // ───────────────────────── T-2: multiple questions, one card ─────────────────────────

  test("T-2 several questions fitting the caps ride ONE card with increasing question indices") {
    val views = List(view(0, "Q0", List("a")), view(1, "Q1", List("b")), view(2, "Q2", List("c")))
    val cards = FeishuAskCard.render(views, "ask-multi")
    assertEquals(cards.size, 1, "a prompt within the caps must not be split")
    assertEquals(values(cards.head).flatMap(v => intField(v, "qi")).distinct.sorted, List(0, 1, 2))
    // each question section is labelled with its 1-based position
    assert(cards.head.contains(FeishuAskCard.questionCopy(0, 3)), cards.head)
  }

  test("T-2b the header and title use the reviewed copy") {
    val card = FeishuAskCard.render(List(view(0, "Q", List("A"))), "ask-1").head
    assertEquals(parse(card).toOption.get.hcursor.downField("header").downField("title")
      .downField("content").as[String].toOption, Some(FeishuAskCard.TitleCopy))
  }

  // ───────────────────────── T-3: over the local caps ⇒ sequential cards ─────────────────────────

  test("T-3 more questions than the local cap falls back to sequential cards, one question each") {
    val views = (0 until FeishuAskCard.MaxQuestions + 1).toList.map(i => view(i, s"Q$i", List("a", "b")))
    val cards = FeishuAskCard.render(views, "ask-over")
    assertEquals(cards.size, views.size, "one card per question once the cap is exceeded")
    cards.foreach { c =>
      assertEquals(values(c).flatMap(v => intField(v, "qi")).distinct.size, 1,
        s"a sequential card carries exactly one question:\n$c")
    }
    // the whole set still spans every question index
    assertEquals(cards.flatMap(c => values(c).flatMap(v => intField(v, "qi"))).distinct.sorted,
      (0 until views.size).toList)
  }

  test("T-3b too many options on one question also overflows, and the caps are local (not claimed remote)") {
    val views = List(view(0, "Q", (0 until FeishuAskCard.MaxOptionsPerQuestion + 1).toList.map(i => s"o$i")))
    assertEquals(FeishuAskCard.fitsOneCard(views), false)
    assertEquals(FeishuAskCard.render(views, "ask-o").size, 1, "one question still is one card")
    // exactly at the cap still fits
    assertEquals(FeishuAskCard.fitsOneCard(
      List(view(0, "Q", (0 until FeishuAskCard.MaxOptionsPerQuestion).toList.map(i => s"o$i")))), true)
  }

  test("T-3c an empty prompt renders no card at all") {
    assertEquals(FeishuAskCard.render(Nil, "ask-none"), Nil)
  }

  // ───────────────────────── T-12: fallback decision + degraded text ─────────────────────────

  test("T-12 the text fallback triggers on a local-panel element, purely") {
    assertEquals(FeishuAskCard.needsTextFallback(List(view(0, "Q", List("A")))), false)
    assertEquals(FeishuAskCard.needsTextFallback(List(PromptView(0, "Q", List(PromptOption("A")), dirPicker = true))), true)
    assertEquals(FeishuAskCard.needsTextFallback(List(PromptView(0, "Q", List(PromptOption("A")), canvas = Some("/x.html")))), true)
    assertEquals(FeishuAskCard.needsTextFallback(List(PromptView(0, "Q", List(PromptOption("A", hasPreview = true))))), true)
  }

  test("T-12b the degraded text names the reason and numbers the options, closing with the instruction") {
    val text = FeishuAskCard.renderText(List(view(0, "Q?", List("A", "B"))), "some-reason")
    assert(text.contains("第 1/1 问 Q?"), text)
    assert(text.contains("  1) A"), text)
    assert(text.contains("  2) B"), text)
    assert(text.contains(FeishuAskCard.DegradeFoot), text)
    assert(text.contains(FeishuAskCard.degradeHead("some-reason")), text)
  }

  test("T-12c the adapter and the definition layer agree on the fallback predicate") {
    val views = List(PromptView(0, "Q", List(PromptOption("A", hasPreview = true))))
    assertEquals(FeishuAskCard.needsTextFallback(views), AskPromptCodec.requiresLocalElements(views))
  }

  // ───────────────────────── reject reasons → toasts (S-7/S-8/S-9) ─────────────────────────

  test("T-12d every refusal answers with the reviewed toast that fits its reason") {
    assertEquals(FeishuAskCard.toastFor(FeishuAskCard.Reject.duplicate(0)), FeishuAskCard.ToastAlreadyAnswered)
    assertEquals(FeishuAskCard.toastFor(FeishuAskCard.Reject.unknownRequestId("r")), FeishuAskCard.ToastExpired)
    assertEquals(FeishuAskCard.toastFor(FeishuAskCard.Reject.unknownNamespace("x")), FeishuAskCard.ToastCannotHandle)
    assertEquals(FeishuAskCard.toastFor(FeishuAskCard.Reject.chatMismatch("a", "b")), FeishuAskCard.ToastCannotHandle)
    assertEquals(FeishuAskCard.toastFor(FeishuAskCard.Reject.indexOutOfRange("oi", 9)), FeishuAskCard.ToastCannotHandle)
  }

  // ───────────────────────── definition-layer boundary (T-11 companion) ─────────────────────────

  test("T-12e fromItems preserves index alignment and maps the local-only affordances") {
    val items = List(
      AskItem("Q0", List(AskOption("a"), AskOption("b"))),
      AskItem("Q1", List.empty, dirPicker = true, freeInput = false),
      AskItem("Q2", List(AskOption("c", preview = Some(nebflow.shared.AskPreview("swatch", Some(List("#fff")))))))
    )
    val views = AskPromptCodec.fromItems(items)
    assertEquals(views.map(_.idx), List(0, 1, 2))
    assertEquals(views.map(_.question), List("Q0", "Q1", "Q2"))
    assertEquals(views(1).dirPicker, true)
    assertEquals(views(2).options.head.hasPreview, true)
    assertEquals(AskPromptCodec.requiresLocalElements(views), true)
  }
