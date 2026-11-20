package nebflow.social.outbound

import io.circe.Json

import scala.util.Try

/**
 * The channel-agnostic rich product of one turn (weixin-media batch, 2026-10-08;
 * batch carrying since the pop batch-media fix, 2026-10-08): what a tool result
 * can contribute to the OUTBOUND decision. Promoted here from its original
 * single-channel home ([[nebflow.social.FeishuBridgePlugin]], where the
 * richcontent main-link batch shipped it as private plumbing) so a second
 * channel — the weixin leg's toolEnd memory — reuses ONE parser instead of
 * growing a twin. The feishu bridge keeps a `type` alias at its historical
 * spelling, so its readers and specs are unchanged; the parsing lives only HERE.
 *
 * The two observables a classifier reads ([[RichKind.of]] takes exactly these):
 * the leading `content` (which may carry the card or Pop payload) and the
 * artifact `media` list.
 *
 * Why `content` and `input` are BOTH consulted (unchanged from the feishu
 * batch): two different tools carry the artifact differently, and the produced
 * result is the authority where it exists.
 *
 *   - a CARD product is the card sentinel + payload, i.e. the `content` itself
 *     (the shape [[RichKind.CardSentinel]] pins); a card has no per-item media,
 *     so `media` is empty and the `content` is the whole product;
 *   - a Pop result is the pop sentinel + JSON whose `items[]` carry the
 *     RESOLVED absolute paths (`PopTool`'s payload; that resolution is the
 *     authority — re-deriving a path from the call arguments would be a second,
 *     weaker judgement). EVERY carriable item is carried, in PAYLOAD order
 *     (pop batch-media fix: one Pop of N images is N media items, not its
 *     first item — the old single-pick read dropped the other N−1 on the wire);
 *   - failing both, the call's own `input` is read for path arguments, but
 *     only for extensions that are rich kinds: an arbitrary file argument must
 *     not turn a turn rich. Every rich path found is carried, in field order.
 */
final case class RichProduct(content: String, media: List[os.Path], label: String):

  /**
   * The leading item — the single-product face the two channels shipped before
   * the pop batch-media fix. Derived, never stored: the list is the authority,
   * so the two can never drift.
   */
  def path: Option[os.Path] = media.headOption

object RichProduct:

  /**
   * The pop payload sentinel — mirrors `nebflow.core.tools.PopTool.Sentinel`
   * (the frontend splits on the same literal in `chat.js`). Pinned rather than
   * imported so this file gains no `core.tools` dependency for one string;
   * the tool and the frontend are the authority and this is a mirror.
   */
  val PopSentinel: String = "___POP_JSON___"

  /** Argument names a tool result may carry a path under. */
  private val PathFields = List("filePath", "path", "file", "attachment", "artifact")

  /**
   * Read the rich product a tool result contributes, or `None` when it is not
   * a rich product at all (the overwhelmingly common case).
   */
  def of(content: String, input: Option[Json], label: String, documentExtensions: Set[String]): Option[RichProduct] =
    if RichKind.isCardContent(content) then Some(RichProduct(content, Nil, label))
    else if content.startsWith(PopSentinel) then
      PopProduct.fromPayload(content.stripPrefix(PopSentinel)) match
        case Nil => None // nothing carriable in the payload (e.g. video-only) — not a rich product
        case ps => Some(RichProduct(content, ps, label))
    else
      pathsFromInput(input).filter(isRichPath(_, documentExtensions)) match
        case Nil => None
        case ps => Some(RichProduct("", ps, label))

  /**
   * Path arguments on the tool call, if any — the single-path fields first,
   * then a `paths` list, in that field order.
   */
  private def pathsFromInput(input: Option[Json]): List[os.Path] =
    input.toList.flatMap { j =>
      val c = j.hcursor
      val single = PathFields.flatMap(f => c.downField(f).as[String].toOption).map(_.trim).filter(_.nonEmpty)
      val many = c.downField("paths").as[List[String]].toOption.getOrElse(Nil).map(_.trim).filter(_.nonEmpty)
      (single ++ many).flatMap(p => Try(os.Path(p)).toOption)
    }

  /**
   * True when the path's extension puts it in one of the two rich tables —
   * the same predicate [[RichKind.of]] applies, used here to keep the fallback
   * arm from calling an ordinary file argument "rich".
   */
  private def isRichPath(p: os.Path, documentExtensions: Set[String]): Boolean =
    val lower = p.toString.toLowerCase
    nebflow.core.tools.ImageInject.imageExtensions.keys.exists(lower.endsWith) ||
    (lower.lastIndexOf('.') match
      case -1 => false
      case i => documentExtensions.contains(lower.substring(i + 1)))

  /**
   * The artifacts read out of a Pop payload's `items[]` — ALL carriable ones,
   * in PAYLOAD order (pop batch-media fix: the old read kept only the first
   * `image`, else the first `file`, and a one-call-many-artifacts Pop arrived
   * as one artifact). Deliberately ONLY the two kinds the rich legs can carry
   * today (image → `image_path`, file → `file_path` on the weixin leg; the
   * feishu leg renders both): a `video` item rides the same payload shape but
   * is not a rich-content product of this layer, so it is skipped rather than
   * mis-delivered. An item without a usable path is skipped too — a listed
   * but unresolvable artifact is the tool's own reject face, not a path. If a
   * payload carries only uncarriable items, the turn keeps its text reply.
   */
  private object PopProduct:

    /**
     * Every `image` / `file` item's path, in the order the payload lists them.
     * `kind` / `path` are the payload's own field names (`PopTool.itemJson`).
     */
    def fromPayload(json: String): List[os.Path] =
      io.circe.parser.parse(json).toOption match
        case None => Nil
        case Some(j) =>
          val items = j.hcursor.downField("items").as[List[Json]].toOption.getOrElse(Nil)
          items.flatMap { i =>
            val c = i.hcursor
            val kind = c.downField("kind").as[String].toOption
            val p = c.downField("path").as[String].toOption.map(_.trim).filter(_.nonEmpty)
            (kind, p) match
              case (Some(k), Some(path)) if k == "image" || k == "file" => Try(os.Path(path)).toOption
              case _ => None
          }
  end PopProduct

end RichProduct
