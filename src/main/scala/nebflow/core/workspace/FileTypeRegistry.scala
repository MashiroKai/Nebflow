package nebflow.core.workspace

/**
 * Single source of truth for file-extension → (viewer itemType, binary)
 * mapping (F3 P1). Replaces three parallel hand-maintained tables:
 * PopTool.BinaryExtensions + PopTool.detectItemType + the two inline
 * match blocks in WebSocketRoutes (readFile / pop.readFile).
 *
 * Behavior-frozen migration — the two old tables were verified equivalent
 * before the switch (every BinaryExtensions member mapped to a binary
 * itemType and vice versa, including the doc→docx / xls→xlsx / ppt→pptx
 * aliases and svg→image). itemType strings MUST NOT change: persisted
 * workspace tabs and ui.json history store them.
 *
 * 2026-10-05 canvas-media batch: added the video/audio entries (binary =
 * true). No existing itemType changed — every pre-existing extension keeps
 * its frozen itemType, and the MUST-NOT-CHANGE rule above continues to
 * apply to all of them. The two new sets mirror the /api/nf-file whitelist
 * families verbatim (FileRefs.AllowedExtensions == NfFilePolicy.NfFileAllowedExt):
 * video = mp4 webm ogv ogg mov, audio = mp3 wav oga flac aac m4a. `m4v` is
 * deliberately absent: PopTool.VideoExtensions declares it but neither
 * whitelist table carries it, so a tool-face reference to .m4v is rejected
 * before any viewer could serve it — the table stays aligned with the
 * whitelist (canvas-media plan §4.2). ogg is claimed by video only (the
 * whitelists file it in the video block); audio never claims it, so no
 * extension is claimed twice.
 */
object FileTypeRegistry:

  final case class Entry(itemType: String, binary: Boolean)

  private val ImageEntry = Entry("image", true)
  private val VideoEntry = Entry("video", true)
  private val AudioEntry = Entry("audio", true)

  /** Built-in extension table. Binary = frontend fetches via /api/nf-file. */
  val BuiltIn: Map[String, Entry] = Map(
    // text formats (binary = false)
    "md" -> Entry("markdown", false),
    "markdown" -> Entry("markdown", false),
    "html" -> Entry("html", false),
    "htm" -> Entry("html", false),
    "json" -> Entry("json", false),
    "yaml" -> Entry("yaml", false),
    "yml" -> Entry("yaml", false),
    "csv" -> Entry("csv", false),
    "tsv" -> Entry("csv", false),
    // images (binary)
    "png" -> ImageEntry,
    "jpg" -> ImageEntry,
    "jpeg" -> ImageEntry,
    "gif" -> ImageEntry,
    "svg" -> ImageEntry,
    "webp" -> ImageEntry,
    "bmp" -> ImageEntry,
    "ico" -> ImageEntry,
    "avif" -> ImageEntry,
    "tiff" -> ImageEntry,
    "tif" -> ImageEntry,
    // documents (binary)
    "pdf" -> Entry("pdf", true),
    "doc" -> Entry("docx", true),
    "docx" -> Entry("docx", true),
    "xls" -> Entry("xlsx", true),
    "xlsx" -> Entry("xlsx", true),
    "xlsm" -> Entry("xlsx", true),
    "ppt" -> Entry("pptx", true),
    "pptx" -> Entry("pptx", true),
    "epub" -> Entry("epub", true),
    // media — video (binary; the frontend fetches bytes via /api/nf-file,
    // which already whitelists every extension below and supports Range).
    "mp4" -> VideoEntry,
    "webm" -> VideoEntry,
    "ogv" -> VideoEntry,
    "ogg" -> VideoEntry,
    "mov" -> VideoEntry,
    // media — audio (binary; same whitelist-aligned discipline as video).
    "mp3" -> AudioEntry,
    "wav" -> AudioEntry,
    "oga" -> AudioEntry,
    "flac" -> AudioEntry,
    "aac" -> AudioEntry,
    "m4a" -> AudioEntry
  )

  private val CodeEntry = Entry("code", false)

  /** ext → entry; unknown / case-variant extensions fall back to code. */
  def detect(ext: String): Entry =
    BuiltIn.getOrElse(ext.toLowerCase, CodeEntry)

  def itemTypeOf(ext: String): String = detect(ext).itemType

  def isBinaryExt(ext: String): Boolean = detect(ext).binary

end FileTypeRegistry
