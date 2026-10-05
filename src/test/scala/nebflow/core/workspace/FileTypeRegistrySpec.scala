package nebflow.core.workspace

import munit.FunSuite

class FileTypeRegistrySpec extends FunSuite:

  test("detect maps every known extension to its frozen itemType + binary flag") {
    val cases = List(
      ("md", "markdown", false),
      ("markdown", "markdown", false),
      ("html", "html", false),
      ("htm", "html", false),
      ("json", "json", false),
      ("yaml", "yaml", false),
      ("yml", "yaml", false),
      ("csv", "csv", false),
      ("tsv", "csv", false),
      ("png", "image", true),
      ("jpg", "image", true),
      ("svg", "image", true),
      ("avif", "image", true),
      ("tif", "image", true),
      ("pdf", "pdf", true),
      ("doc", "docx", true),
      ("docx", "docx", true),
      ("xls", "xlsx", true),
      ("xlsm", "xlsx", true),
      ("ppt", "pptx", true),
      ("pptx", "pptx", true),
      ("epub", "epub", true),
      // canvas-media batch (2026-10-05): the two new media families.
      ("mp4", "video", true),
      ("webm", "video", true),
      ("ogv", "video", true),
      ("ogg", "video", true),
      ("mov", "video", true),
      ("mp3", "audio", true),
      ("wav", "audio", true),
      ("oga", "audio", true),
      ("flac", "audio", true),
      ("aac", "audio", true),
      ("m4a", "audio", true),
      // m4v is deliberately absent: PopTool.VideoExtensions declares it, but
      // neither whitelist table (FileRefs / NfFilePolicy) carries it — the
      // table stays aligned with the whitelist, so .m4v keeps the code
      // fallback (canvas-media plan §4.2).
      ("m4v", "code", false)
    )
    for (ext, itemType, binary) <- cases do
      assertEquals(FileTypeRegistry.detect(ext), FileTypeRegistry.Entry(itemType, binary), s"ext=$ext")
  }

  test("detect lowercases and falls back to code for unknown extensions") {
    assertEquals(FileTypeRegistry.detect("MD").itemType, "markdown")
    assertEquals(FileTypeRegistry.detect("PNG").binary, true)
    assertEquals(FileTypeRegistry.detect("scala").itemType, "code")
    assertEquals(FileTypeRegistry.detect("scala").binary, false)
    assertEquals(FileTypeRegistry.detect("MP4").itemType, "video")
    assertEquals(FileTypeRegistry.detect("").itemType, "code")
    assertEquals(FileTypeRegistry.detect("xyzzy").itemType, "code")
  }

  test("built-in table keeps itemType strings stable (persisted tabs depend on them)") {
    // itemType values are persisted in workspace tabs / ui.json — lock them.
    // 2026-10-05 canvas-media batch: added "video" and "audio" (new itemTypes;
    // every pre-existing one is unchanged — before/after disclosed verbatim in
    // the batch report, OD-5).
    val itemTypes = FileTypeRegistry.BuiltIn.values.map(_.itemType).toSet
    val expected =
      Set(
        "markdown",
        "html",
        "json",
        "yaml",
        "csv",
        "image",
        "pdf",
        "docx",
        "xlsx",
        "pptx",
        "epub",
        "video",
        "audio",
        "code"
      )
    assertEquals(itemTypes, expected - "code") // code is the fallback, not in table
  }

  test("binary set matches the old BinaryExtensions membership exactly") {
    // The deleted PopTool.BinaryExtensions set — membership must be identical.
    // 2026-10-05 canvas-media batch: the 11 media extensions below are the
    // deliberate, disclosed addition (OD-5; whitelist-aligned families, m4v
    // excluded — see FileTypeRegistry header).
    val oldBinaryExts = Set(
      "png",
      "jpg",
      "jpeg",
      "gif",
      "svg",
      "webp",
      "bmp",
      "ico",
      "avif",
      "tiff",
      "tif",
      "pdf",
      "doc",
      "docx",
      "xls",
      "xlsx",
      "xlsm",
      "ppt",
      "pptx",
      "epub",
      "mp4",
      "webm",
      "ogv",
      "ogg",
      "mov",
      "mp3",
      "wav",
      "oga",
      "flac",
      "aac",
      "m4a"
    )
    val newBinaryExts = FileTypeRegistry.BuiltIn.collect { case (e, entry) if entry.binary => e }.toSet
    assertEquals(newBinaryExts, oldBinaryExts)
    // and no non-binary entry was in the old set
    val newTextExts = FileTypeRegistry.BuiltIn.collect { case (e, entry) if !entry.binary => e }.toSet
    assert(newTextExts.intersect(oldBinaryExts).isEmpty)
  }

end FileTypeRegistrySpec
