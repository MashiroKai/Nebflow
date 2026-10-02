package nebflow.social.outbound

import nebflow.core.tools.ImageInject

/**
 * Content-class split for the outbound rich-content layer.
 *
 * Source of truth: `.nebflow/Spec/20260930_203506_socoutbound-design__chain-socoutbound.md`
 * section 1 (mechanical split criteria + unknown-class ownership). This file is
 * the definition layer: it is CHANNEL-AGNOSTIC (zero `com.lark` import, zero
 * network, zero IO monad) so the Feishu adapter is an implementation, not the
 * place the criteria live. WeChat reuses this same layer.
 *
 * The two observable inputs are exactly the two the design card names:
 *   - the SOURCE MARKER (does the event carry a card product marker?);
 *   - the PATH EXTENSION of the referenced artifact.
 * No MIME sniffing, no decoding, no byte reads: `ImageInject.prepareImage`
 * already owns the one decode hop that exists in this repo, and re-deciding
 * content here would be a second, more fragile judging surface.
 */
enum ContentClass:
  /** No attachment path reference: the existing text leg. */
  case Text
  /** Path extension is in the repo's single image table ([[ImageInject.imageExtensions]]). */
  case Image
  /** Path extension is in the repo's allowed-extension table and is not an image. */
  case Document
  /** The event carries a card product marker: this turn's visible product is an HTML card. */
  case Card
  /** Nothing matched. `reason` is never empty: an undecidable class is an explicit class. */
  case Unknown(reason: String)

object ContentClass:

  /** Stat reading for one referenced artifact. Metadata only -- never file bytes. */
  final case class Artifact(path: os.Path, bytes: Long, regularFile: Boolean)

  /**
   * Stat seam. Production = [[fsStat]] (the filesystem); the offline spec injects
   * a pinned map so classification is judged without touching disk.
   */
  type StatProbe = os.Path => Option[Artifact]

  /** Production probe. A path that cannot be read back is simply absent, which
    * the caller turns into [[ContentClass.Unknown]] rather than an exception. */
  val fsStat: StatProbe = p =>
    try
      if os.exists(p) then
        if os.isFile(p) then Some(Artifact(p, os.size(p), regularFile = true))
        else Some(Artifact(p, 0L, regularFile = false))
      else None
    catch case _: Throwable => None

  /** Lower-cased extension INCLUDING the dot (`.png`), or "" when there is none.
    * A leading-dot name (`.gitignore`) counts as "no extension" -- the dot is at
    * index 0 of the base name. */
  def extensionOf(name: String): String =
    val base = name.substring(name.lastIndexOf('/') + 1)
    val dot = base.lastIndexOf('.')
    if dot <= 0 then "" else base.substring(dot).toLowerCase

  /** Image face -- delegates to the repo's single image table. */
  def imageMime(name: String): Option[String] = ImageInject.imageMimeType(name)

  def isImageName(name: String): Boolean = imageMime(name).isDefined

  /**
   * The allowed-extension table, PASSED IN by the caller rather than imported.
   *
   * Why a parameter and not a direct read: this layer must stay channel-agnostic
   * and free of any gateway dependency, and the repo's tool-side twin
   * (`FileRefs.AllowedExtensions`) is `private[tools]`. The production caller
   * therefore hands in the endpoint authority `NfFilePolicy.NfFileAllowedExt`,
   * which `FileRefsWhitelistSpec` A14 welds item-by-item to that twin. No second
   * table is created here and no `private` scope is widened to reach one.
   */
  type ExtensionTable = Set[String]

  /**
   * No-table behaviour: only the image table is consulted, everything else is
   * `unknown`. This is what a caller that cannot reach the endpoint authority
   * gets -- fail-closed (a class we cannot decide becomes an explicit note, not
   * a wrong upload).
   */
  val emptyTable: ExtensionTable = Set.empty

  /**
   * Document face: extension is in the allowed-extension table AND is not an
   * image extension. The second half is what keeps `png` (which sits in the
   * allowed table too) from making an image a document.
   */
  def documentExtension(name: String, table: ExtensionTable): Option[String] =
    val ext = extensionOf(name).stripPrefix(".")
    if ext.nonEmpty && table.contains(ext) && !isImageName(name) then Some(ext)
    else None

  /**
   * IO-free classification of ONE referenced artifact -- the mechanical core.
   * Priority order (short-circuit, never parallel): image -> document -> unknown.
   */
  def classifyPath(path: os.Path, stat: StatProbe = fsStat, table: ExtensionTable = emptyTable): ContentClass =
    stat(path) match
      case None =>
        Unknown(s"artifact path does not exist: ${path.toString}")
      case Some(a) if !a.regularFile =>
        Unknown(s"artifact path is not a regular file: ${path.toString}")
      case Some(a) =>
        val name = a.path.last
        if isImageName(name) then Image
        else if documentExtension(name, table).isDefined then Document
        else Unknown(s"extension not in any known table: ${a.path.toString}")

  /**
   * Event-level classification, in the design card's order (card -> image ->
   * document -> text -> unknown), short-circuiting on the first hit.
   *
   * `cardMarker` is read by the caller from the event's source marker; this
   * layer deliberately does not parse the event schema, so the definition layer
   * stays usable from any channel that can answer those two questions.
   */
  def classify(
      cardMarker: Boolean,
      paths: List[os.Path],
      stat: StatProbe = fsStat,
      table: ExtensionTable = emptyTable
  ): ContentClass =
    if cardMarker then Card
    else
      paths match
        case Nil      => Text
        case p :: _   => classifyPath(p, stat, table)

  /** Plain-language reading of a class -- for logs and degraded text, never for credentials. */
  def describe(c: ContentClass): String = c match
    case Text            => "text"
    case Image           => "image"
    case Document        => "document"
    case Card            => "card"
    case Unknown(reason) => s"unknown ($reason)"
