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
 */
object FileTypeRegistry:

  final case class Entry(itemType: String, binary: Boolean)

  private val ImageEntry = Entry("image", true)

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
    "epub" -> Entry("epub", true)
  )

  private val CodeEntry = Entry("code", false)

  /** ext → entry; unknown / case-variant extensions fall back to code. */
  def detect(ext: String): Entry =
    BuiltIn.getOrElse(ext.toLowerCase, CodeEntry)

  def itemTypeOf(ext: String): String = detect(ext).itemType

  def isBinaryExt(ext: String): Boolean = detect(ext).binary

end FileTypeRegistry
