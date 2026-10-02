package nebflow.social.outbound

import io.circe.Json

import nebflow.shared.AskItem

/**
 * Channel-agnostic question/answer model for AskUser-style prompts (askuser
 * batch, 2026-10-01).
 *
 * This is the DEFINITION layer the author's ruling #{A5} requires: it holds the
 * data model and the answer-slot semantics, and it is deliberately free of any
 * transport concepts (no vendor type, no network, no IO, no logger, and — per
 * the T-11 guard — no channel literal). A channel adapter supplies its own
 * rendering and parsing on top; it must never push its vocabulary down here.
 *
 * Contract highlights, all pinned offline by spec:
 *   · the answer list is FIXED LENGTH and index-aligned with the question list,
 *     with `""` for a question the user did not answer — byte-for-byte the same
 *     semantics the local frontend already ships (`chat.js` builds the array as
 *     `new Array(questions.length).fill(null)` and maps `null` to `''`);
 *   · a multi-select answer is the JSON string form of the chosen labels in
 *     option order — again the frontend's existing wire shape;
 *   · a free-text answer is the trimmed input, and an empty input yields no
 *     answer for that slot;
 *   · out-of-range indices are REJECTED, never clamped: a wrong slot is worse
 *     than a visible refusal.
 */
final case class PromptOption(
    label: String,
    description: Option[String] = None,
    /** Whether this option carries an inline preview a remote surface cannot show.
      *  A preview needs the local panel, so its presence forces a text fallback
      *  rather than a silently stripped answer. */
    hasPreview: Boolean = false
)

/** One question, reduced to what a remote prompt can carry.
  *
  * `idx` is the question's position and doubles as its answer-slot index; it is
  * carried explicitly so a slot can always be traced back to its question.
  *
  * `dirPicker` / `canvas` do not exist on a remote prompt: they are local-panel
  * affordances. They are carried here so the fallback decision is a pure
  * function of this model rather than a look-up into the local types. */
final case class PromptView(
    idx: Int,
    question: String,
    options: List[PromptOption] = Nil,
    allowOther: Boolean = true,
    multiple: Boolean = false,
    dirPicker: Boolean = false,
    canvas: Option[String] = None
)

/** What a user did on one prompt.
  *
  * `Pick(qi, oi)` names an option by INDEX, never by label: the index is the
  * stable key (labels can be long, repeated or reworded). `oi = -1` is reserved
  * for the free-text entry.
  */
enum PromptAction:
  case Pick(qi: Int, oi: Int)
  case Other(qi: Int, text: String)

object PromptAction:
  /** The reserved option index meaning "the user chose to type their own answer". */
  val OtherOptionIndex = -1

/** Why a set of questions cannot be carried by a remote prompt.
  *
  * Three triggers, each a FACT rather than a configuration switch (ruling A3
  * rejects a config gate on purpose — a reading the machine always takes beats a
  * switch a human may forget):
  *   · `NoCallback`  — the measured capability result says the reply path is
  *     unreachable, so no prompt is attempted at all;
  *   · `SendRejected` — the transport answered `ok = false` (its code travels
  *     with the reason so the reading stays attributable);
  *   · `LocalOnly`   — a question needs a local-panel affordance.
  */
enum PromptFallback:
  case NoCallback(reason: String)
  case SendRejected(code: Int, detail: String)
  case LocalOnly(reason: String)

object AskPromptCodec:

  /** The reserved option index for the free-text entry, re-exported here so a
    * caller of this codec never has to reach into [[PromptAction]]. */
  val OtherOptionIndex: Int = PromptAction.OtherOptionIndex

  /** Reduce the local question model to the transport-neutral view. Pure: no
    * lookup, no IO, one output element per input element (index-preserving). */
  def fromItems(items: List[AskItem]): List[PromptView] =
    items.zipWithIndex.map { (item, i) =>
      PromptView(
        idx = i,
        question = item.question,
        options = item.options.map(o => PromptOption(o.label, o.description, o.preview.nonEmpty)),
        allowOther = item.allowOther,
        multiple = item.multiple,
        dirPicker = item.dirPicker,
        canvas = item.canvas.map(_.trim).filter(_.nonEmpty)
      )
    }

  /** The local-panel elements a remote prompt cannot carry. True ⇒ those
    * questions must fall back to text (their siblings still go out normally). */
  def requiresLocalElements(views: List[PromptView]): Boolean =
    views.exists(v => v.dirPicker || v.canvas.nonEmpty || v.options.exists(_.hasPreview))

  /** A fixed-length answer list aligned with question order.
    *
    * Every slot is `""` unless the user acted on that question; a multi-select
    * question serialises its labels as a JSON array string in option order.
    * Out-of-range actions are DROPPED here — a caller that needs a refusal must
    * validate first ([[validate]]); this method never invents a slot, and never
    * grows the list to fit a stray index.
    */
  def answers(views: List[PromptView], actions: List[PromptAction]): List[String] =
    val slots = Array.fill[String](views.size)("")
    val byIdx = views.map(v => v.idx -> v).toMap
    views.indices.foreach { pos =>
      val picks = actions.collect { case PromptAction.Pick(qi, oi) if qi == views(pos).idx => oi }
        .filter(oi => oi >= 0 && oi < views(pos).options.size)
      val others = actions.collect { case PromptAction.Other(qi, text) if qi == views(pos).idx => text.trim }
        .filter(_.nonEmpty)
      val view = views(pos)
      slots(pos) =
        if view.multiple && picks.nonEmpty then
          Json.arr(picks.sorted.map(oi => Json.fromString(view.options(oi).label))*).noSpaces
        else if picks.nonEmpty then view.options(picks.head).label
        else others.headOption.getOrElse("")
    }
    slots.toList

  /** Validate the actions against the questions. Returns the offending field
    * description on the left — named, so a refusal can say WHICH index was bad
    * instead of a generic failure. */
  def validate(views: List[PromptView], actions: List[PromptAction]): Either[String, Unit] =
    val byIdx = views.map(v => v.idx -> v).toMap
    actions.collectFirst {
      case PromptAction.Pick(qi, oi) if !byIdx.contains(qi) => s"question-index-out-of-range: $qi"
      case PromptAction.Pick(qi, oi) if oi != OtherOptionIndex && (oi < 0 || oi >= byIdx(qi).options.size) =>
        s"option-index-out-of-range: $oi (question $qi has ${byIdx(qi).options.size})"
      case PromptAction.Other(qi, _) if !byIdx.contains(qi) => s"question-index-out-of-range: $qi"
    }.toLeft(())

end AskPromptCodec
