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
    canvas: Option[String] = None,
    /** The question's `id` when the caller carries one (askuser-remote P1): the
      * key `dependsOn` refs resolve against. Absent on every pre-P1 caller —
      * [[AskPromptCodec.fromItems]] is byte-for-byte unchanged and simply never
      * sets it. */
    id: Option[String] = None,
    /** The `dependsOn.ref` this question is conditioned on, when it has one.
      * Only the REF travels: a remote one-shot surface cannot evaluate the
      * `equals` condition, so the condition's existence — not its value — is
      * what the flatten decision ([[AskPromptCodec.flatten]]) needs. */
    dependsOnRef: Option[String] = None
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

  // ─────────────── askuser-remote P1 additions (new members only; the
  // ─────────────── functions above are the 2026-10-01 frozen set) ───────────────

  /** A6 (askuser-remote P1): attachments travel as PATH LINES only — no file
    * upload exists on either remote face (the feishu rich upload leg is a P2
    * candidate, weixin has no media outbound leg at all). One shared head
    * string, so the two channels cannot drift. */
  val AttachmentLineHead = "附件路径："

  /** The attachment block a remote prompt renders, or `""` when there is
    * nothing to attach. */
  def attachmentLines(paths: List[String]): String =
    paths.map(_.trim).filter(_.nonEmpty) match
      case Nil => ""
      case ps  => (AttachmentLineHead :: ps.map(p => s"- $p")).mkString("\n")

  /** D1 (askuser-remote P1, author ruling): the flattened form of a question
    * set for a ONE-SHOT remote surface. A remote card has no conditional
    * branch, so the set ships whole, in one pass — and a question whose
    * `dependsOn` can never be satisfied BY CONSTRUCTION is not sent at all
    * (「不可达题不发」).
    *
    * "Never satisfiable by construction" means: the ref does not name another
    * SURVIVING question of the same set (a missing id, a self-ref, or a chain
    * through an already-dropped question — the filter runs to a fixpoint so a
    * dropped question takes its dependents with it). A question whose ref
    * SURVIVES is potentially reachable and ships unconditionally; whether the
    * user's answer satisfies the condition is the asking side's concern, and
    * the answer slots stay aligned with the FULL original set via
    * [[FlatSet.expandAnswerSlots]].
    */
  final case class FlatSet(
    /** The kept questions, renumbered so `idx` == list position (0-based,
      * contiguous) — every renderer that prints "第 i/n 问" stays correct. */
    views: List[PromptView],
    /** Kept position → ORIGINAL slot index, so an answer list computed over
      * `views` can be re-expanded into the engine's fixed-length shape. */
    originalIndices: List[Int],
    /** The FULL original question count — the length the engine's answer
      * array must have regardless of what was dropped. */
    originalSize: Int
  ):
    /** Expand the kept set's answer slots into the engine's fixed-length
      * answer list: `""` at every dropped position, the answered value at its
      * original position. Total = [[originalSize]], always. */
    def expandAnswerSlots(keptSlots: List[String]): List[String] =
      val slots = Array.fill(originalSize)("")
      keptSlots.zip(originalIndices).foreach { (a, orig) =>
        if orig >= 0 && orig < originalSize then slots(orig) = a
      }
      slots.toList

  def flatten(views: List[PromptView]): FlatSet =
    var survivors = views
    var next = filterUnreachable(survivors)
    while next.length != survivors.length do
      survivors = next
      next = filterUnreachable(survivors)
    val kept = next
    FlatSet(
      views = kept.zipWithIndex.map { case (v, i) => v.copy(idx = i) },
      originalIndices = kept.map(_.idx),
      originalSize = views.size
    )

  /** One flatten pass: drop every question whose `dependsOnRef` does not name
    * another surviving question's id. Pure. */
  private def filterUnreachable(views: List[PromptView]): List[PromptView] =
    val ids = views.flatMap(_.id).toSet
    views.filter(v => v.dependsOnRef.forall(ref => ref != v.id.orNull && ids.contains(ref)))

  /** Decode the hub `askUser` frame's `items` array into the transport-neutral
    * views — the read side of the exact wire form the engine writes
    * (`AgentActor.buildAskUserJson`; the hub snapshot frames carry the same
    * items). Tolerant by construction: an item without a usable question is
    * SKIPPED, never synthesised; unknown fields are ignored.
    *
    * `allowOther` ANDs the wire's `freeInput` (default true): a question that
    * explicitly switched the free-input face off (`freeInput:false`, the
    * dirPicker form) has no free-text face on a remote prompt either, so the
    * "其他" affordance must not be offered. */
  def viewsFromItemsJson(items: List[Json]): List[PromptView] =
    items.zipWithIndex.flatMap { (item, pos) =>
      val c = item.hcursor
      c.downField("question").as[String].toOption.map(_.trim).filter(_.nonEmpty).map { q =>
        val options = c.downField("options").as[List[Json]].toOption.getOrElse(Nil).flatMap { o =>
          o.hcursor.downField("label").as[String].toOption.map(_.trim).filter(_.nonEmpty).map { label =>
            PromptOption(
              label = label,
              description = o.hcursor.downField("description").as[String].toOption.map(_.trim).filter(_.nonEmpty),
              hasPreview = o.hcursor.downField("preview").focus.exists(j => !j.isNull)
            )
          }
        }
        val allowOther = c.downField("allowOther").as[Boolean].toOption.getOrElse(true) &&
          c.downField("freeInput").as[Boolean].toOption.getOrElse(true)
        PromptView(
          // The WIRE POSITION is the question's slot identity: the engine's
          // fixed-length answer array is aligned with the items array, and
          // flatten needs the ORIGINAL index to expand kept answers back into
          // that array (FlatSet.originalIndices). Renderers renumber the KEPT
          // list themselves (flatten reindexes views), so nothing user-visible
          // ever shows this raw value.
          idx = pos,
          question = q,
          options = options,
          allowOther = allowOther,
          multiple = c.downField("multiple").as[Boolean].toOption.getOrElse(false),
          dirPicker = c.downField("dirPicker").as[Boolean].toOption.getOrElse(false),
          canvas = c.downField("canvas").as[String].toOption.map(_.trim).filter(_.nonEmpty),
          id = c.downField("id").as[String].toOption.map(_.trim).filter(_.nonEmpty),
          dependsOnRef = c.downField("dependsOn").downField("ref").as[String].toOption.map(_.trim).filter(_.nonEmpty)
        )
      }
    }

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
