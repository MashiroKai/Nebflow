package nebflow.social.outbound

import nebflow.core.tools.ImageInject

/**
 * Rich-content kinds of the outbound layer (richcontent batch, 2026-09-30).
 *
 * Scope decision: this file carries ONLY the three rich kinds this batch
 * implements (`image` / `document` / `card`) plus the fail-closed `Unknown`
 * carrier. The channel-agnostic classifier that owns the FULL five-class
 * partition (`text` / `image` / `document` / `card` / `unknown`, design card
 * §1) is a sibling batch's deliverable, so the names here are deliberately
 * prefixed and distinct (`RichKind`, not `ContentClass`) to keep the two
 * definition layers from colliding on the same package.
 *
 * 🔴 Single-authority rule for the extension tables (design card §1: "复用仓内
 * 既有单一权威表，不新造第二份"):
 *   · the IMAGE table is `nebflow.core.tools.ImageInject.imageExtensions`
 *     (`ImageInject.scala:40-47`) — public and already imported by this
 *     package's dependency direction (`social → core`), so it is reused
 *     directly, never copied;
 *   · the DOCUMENT table is NOT reachable from `nebflow.social`: its authority
 *     `nebflow.core.tools.FileRefs.AllowedExtensions` is declared on a
 *     `private[tools] object` (`FileRefs.scala:27`), and the welded twin
 *     `nebflow.gateway.NfFilePolicy.NfFileAllowedExt` lives in a package that
 *     already imports `nebflow.social` (`GatewayMain.scala:1421` uses
 *     `FeishuBridgePlugin.sync`) — importing it back would close an import
 *     cycle the repo's dependency rule forbids. The set is therefore a
 *     CONSTRUCTOR PARAMETER of [[RichKind.of]]: the far side (the wiring layer
 *     that assembles this adapter) supplies the authoritative value. No second
 *     table is created here.
 */
enum RichKind:
  case Image
  case Document
  case Card

  /** Fail-closed carrier. `reason` is forced non-empty by [[RichKind.unknown]]. */
  case Unknown(reason: String)

object RichKind:

  /**
   * The card marker the frontend splits on. Authoritative definition =
   * `nebflow.core.tools.CardTool.CardSentinel` (`CardTool.scala:447`), which is
   * `private` to that object and therefore not reachable here.
   *
   * The literal is pinned instead of re-derived, and [[RichKind.isCardContent]]
   * is the only place that reads it — so a future drift is a one-line fix at
   * one site. A public accessor on `CardTool` would remove the duplication
   * entirely; that touches a production tool file, so it is reported as an
   * explicit open item rather than done unilaterally.
   */
  val CardSentinel: String = "___CARD_HTML___"

  /** True when `content` is a card payload (design card §1 `card` row: the
    *  source marker, not the extension — a card's visible product is HTML). */
  def isCardContent(content: String): Boolean =
    content.startsWith(CardSentinel)

  /** Non-empty reason helper: an unexplained unknown is a bug, not a value. */
  def unknown(reason: String): RichKind =
    Unknown(if reason.trim.isEmpty then "unspecified" else reason)

  /**
   * The mechanical rule (design card §1), reduced to the three rich kinds and
   * a PURE function so the offline spec can pin the whole table without
   * touching the filesystem.
   *
   * Priority is short-circuiting and ordered, exactly as the design card
   * requires: card (source marker) → image (extension) → document (extension).
   * Everything else is `Unknown`, which the dispatch leg must turn into a
   * degraded text — never into a silent drop.
   *
   * @param content      the product's leading content, when the event carries
   *                     one (card marker test); `""` when there is none
   * @param path         the referenced absolute path, as written by the agent
   * @param exists       whether `path` exists and is a regular file
   * @param documentExtensions the authoritative document extension set,
   *                     lower-case, without the leading dot
   */
  def of(
      content: String,
      path: Option[String],
      exists: Boolean,
      documentExtensions: Set[String]
  ): RichKind =
    if isCardContent(content) then Card
    else
      path.map(_.trim).filter(_.nonEmpty) match
        case None => unknown("no product path on the event")
        case Some(p) =>
          val lower = p.toLowerCase
          // Reuse the existing image authority (see the file header).
          val isImage = ImageInject.imageExtensions.keys.exists(lower.endsWith)
          val ext = lower.lastIndexOf('.') match
            case -1 => ""
            case i  => lower.substring(i + 1)
          if !exists then unknown(s"path does not exist or is not a regular file: $p")
          else if isImage then Image
          else if ext.nonEmpty && documentExtensions.contains(ext) then Document
          else unknown(s"extension is in neither authority table: ${if ext.isEmpty then "<none>" else ext}")

end RichKind
