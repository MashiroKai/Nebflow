package nebflow.core.ask

import cats.effect.unsafe.implicits.global
import cats.syntax.parallel.*
import nebflow.shared.NebflowLogger

import java.nio.charset.StandardCharsets
import java.nio.file.Files

/* ═══════════════════════════════════════════════════════════════════════════
 * Canvas 记事本持久层 spec（持久化缺陷批 2026-11-05）
 *
 * 覆盖面（N 组）：
 *   N1  落点 = `<dataRoot>/ask/notes/<sid>.md`（读写往返；内容逐字一致）
 *   N2  sid 硬闸：非法 sid 一律拒（读 = 空快照、写 = BadSessionId，且**不落盘**）
 *       —— 这条同时是路径穿越防线（`../`、绝对路径、盘符）
 *   N3  空文本 ⇒ 删件（不留空壳；再次读 = exists=false）
 *   N4  单件上限 256 KiB：超限拒写且**盘上旧值原样保留**（不静默截断用户内容）
 *   N5  supersededTs 语义：覆盖已有件时回报旧 mtime（并发写信号，最后写者胜）
 *   N6  UTF-8 字节计量（中文/emoji 不被当 1 字节算）
 *   N7  同 sid 幂等覆盖（不是追加——记事本是整段快照）
 *
 * 版本化条件写（frontend-data-consistency：FDC-SCN-004…016）：版本 / 墓碑 / 读错误 / 旧记录。
 * ═══════════════════════════════════════════════════════════════════════════ */
final class AskNoteStoreSpec extends munit.FunSuite:

  private val logger = NebflowLogger.forName("nebflow.ask.notes.spec")
  private val Sid = "ask-1a2b3c4d"
  private val OtherSid = "ask-ffff0000"

  private def tempRoot(): os.Path =
    os.Path(Files.createTempDirectory("ask-note-spec"))

  private def store(): (AskNoteStore, os.Path) =
    val root = tempRoot()
    (new AskNoteStore(root), root)

  test("N1 落点与往返：写入后文件落在 <dataRoot>/ask/notes/<sid>.md，读出内容逐字一致") {
    val (s, root) = store()
    val text = "第一行：随手记的内容\n第二行：换行与缩进  都原样\n\ttab 也原样"
    val wrote = s.write(Sid, text).unsafeRunSync()
    assert(wrote.isInstanceOf[AskNoteWrite.Ok], clue(wrote))
    // The record is the versioned envelope `<sid>.json`; the text is stored verbatim inside it.
    val p = root / "ask" / "notes" / s"$Sid.json"
    assert(os.exists(p), clue(s"expected $p"))
    assertEquals(io.circe.parser.parse(os.read(p)).toOption.flatMap(_.hcursor.get[String]("text").toOption), Some(text))
    val snap = s.read(Sid).unsafeRunSync()
    assertEquals(snap.text, text)
    assert(snap.exists)
    assert(snap.ts > 0L)
  }

  test("N2a 非法 sid 读取 ⇒ 空快照且不触盘") {
    val (s, _) = store()
    for bad <- List("", "../etc/passwd", "/etc/passwd", "ask-123", "ask-ZZZZZZZZ", "ask-1a2b3c4d/../x", null) do
      val snap = s.read(bad).unsafeRunSync()
      assert(!snap.exists, clue(bad))
      assertEquals(snap.text, "", clue(bad))
  }

  test("N2b 非法 sid 写入 ⇒ BadSessionId 且盘上零新增文件（路径穿越防线）") {
    val (s, root) = store()
    for bad <- List("../escape", "/tmp/absolute", "ask-", "ask-1a2b3c4d.md/../../x", "ok-file.md") do
      val wrote = s.write(bad, "内容").unsafeRunSync()
      assert(wrote.isInstanceOf[AskNoteWrite.BadSessionId], clue((bad, wrote)))
    val notesDir = root / "ask" / "notes"
    val files = if os.exists(notesDir) then os.list(notesDir).map(_.last).toList else Nil
    assertEquals(files, Nil)
  }

  test("N3 空文本 ⇒ 清空（再读 exists=false 且无文本；留下带版本的清空记录而不是无版本的缺席）") {
    val (s, root) = store()
    s.write(Sid, "有内容").unsafeRunSync()
    val p = root / "ask" / "notes" / s"$Sid.json"
    assert(os.exists(p))
    val before = s.read(Sid).unsafeRunSync()
    s.write(Sid, "").unsafeRunSync()
    val snap = s.read(Sid).unsafeRunSync()
    assert(!snap.exists)
    assertEquals(snap.text, "")
    assert(snap.cleared, clue("a clear is recorded, not an absence"))
    assert(snap.ts > before.ts, clue((snap.ts, before.ts)))
    assert(!os.read(p).contains("有内容"), clue("the cleared text must not stay on disk"))
  }

  test("N4 单件上限 256 KiB：超限拒写，盘上旧值原样保留（不静默截断）") {
    val (s, root) = store()
    assertEquals(s.MaxBytes, 256 * 1024)
    s.write(Sid, "旧值").unsafeRunSync()
    val tooBig = "字" * (s.MaxBytes / 3 + 10) // 3 字节/字 ⇒ 必超
    val res = s.write(Sid, tooBig).unsafeRunSync()
    res match
      case AskNoteWrite.TooLarge(bytes, limit) =>
        assert(bytes > limit, clue((bytes, limit)))
        assertEquals(limit, s.MaxBytes)
      case other => fail(s"expected TooLarge, got $other")
    assertEquals(s.read(Sid).unsafeRunSync().text, "旧值")
    assertEquals(os.list(root / "ask" / "notes").map(_.last).toList, List(s"$Sid.json"))
  }

  test("N5 覆盖写回报 supersededTs（并发写信号；首次写为 0）") {
    val (s, _) = store()
    val first = s.write(Sid, "v1").unsafeRunSync()
    first match
      case AskNoteWrite.Ok(_, superseded) => assertEquals(superseded, 0L)
      case other => fail(s"expected Ok, got $other")
    Thread.sleep(5) // mtime 分辨率
    val second = s.write(Sid, "v2").unsafeRunSync()
    second match
      case AskNoteWrite.Ok(ts, superseded) =>
        assert(superseded > 0L, clue(superseded))
        assert(ts >= superseded, clue((ts, superseded)))
      case other => fail(s"expected Ok, got $other")
    assertEquals(s.read(Sid).unsafeRunSync().text, "v2")
  }

  test("N6 UTF-8 字节计量：中文按 3 字节计入上限（不是按字符数）") {
    val (s, _) = store()
    val bytesOf = (t: String) => t.getBytes(StandardCharsets.UTF_8).length
    // 恰好低于上限的多字节文本可写
    val near = "记" * ((s.MaxBytes / 3) - 2)
    assert(bytesOf(near) <= s.MaxBytes, clue(bytesOf(near)))
    assert(s.write(Sid, near).unsafeRunSync().isInstanceOf[AskNoteWrite.Ok])
    // 超上限即拒（多一个字就过界）
    val over = "记" * ((s.MaxBytes / 3) + 1)
    assert(bytesOf(over) > s.MaxBytes, clue(bytesOf(over)))
    assert(s.write(Sid, over).unsafeRunSync().isInstanceOf[AskNoteWrite.TooLarge])
  }

  test("N7 同 sid 幂等覆盖（整段快照语义，不是追加）") {
    val (s, _) = store()
    s.write(Sid, "AAA").unsafeRunSync()
    s.write(Sid, "BBB").unsafeRunSync()
    assertEquals(s.read(Sid).unsafeRunSync().text, "BBB")
  }

  test("N8 不同 sid 互不干扰（每个文件一份记事本）") {
    val (s, _) = store()
    s.write(Sid, "文件 A 的记事本").unsafeRunSync()
    s.write(OtherSid, "文件 B 的记事本").unsafeRunSync()
    assertEquals(s.read(Sid).unsafeRunSync().text, "文件 A 的记事本")
    assertEquals(s.read(OtherSid).unsafeRunSync().text, "文件 B 的记事本")
  }

  test("N9 缺件读取 ⇒ exists=false 而非抛错（新文件第一次打开的可读降级）") {
    val (s, _) = store()
    val snap = s.read("ask-0000feed").unsafeRunSync()
    assert(!snap.exists)
    assertEquals(snap.text, "")
    assertEquals(snap.ts, 0L)
  }

  // ═══ Versioned conditional writes (frontend-data-consistency) ═══════════════

  private def put(s: AskNoteStore, sid: String, text: String, base: Option[Long]): AskNoteWrite =
    s.put(sid, text, base).unsafeRunSync()

  private def versionOf(w: AskNoteWrite): Long = w match
    case AskNoteWrite.Ok(ts, _) => ts
    case other => fail(s"expected Ok, got $other")

  private def recordFiles(root: os.Path): List[String] =
    val notes = root / "ask" / "notes"
    if os.exists(notes) then os.list(notes).map(_.last).toList.sorted else Nil

  test("FDC-SCN-004 a write based on an older version is refused and the stored note is unchanged") {
    val (s, root) = store()
    val v1 = versionOf(put(s, Sid, "one", Some(0L)))
    val v2 = versionOf(put(s, Sid, "two", Some(v1)))
    assert(v2 > v1, clue((v1, v2)))
    val before = os.read(root / "ask" / "notes" / s"$Sid.json")
    put(s, Sid, "stale", Some(v1)) match
      case AskNoteWrite.Conflict(current) =>
        assertEquals(current.text, "two")
        assertEquals(current.version, v2)
        assert(current.exists && !current.cleared)
      case other => fail(s"expected Conflict, got $other")
    // A writer that never saw the note (base 0) is refused as well.
    assert(put(s, Sid, "blind", Some(0L)).isInstanceOf[AskNoteWrite.Conflict])
    assertEquals(os.read(root / "ask" / "notes" / s"$Sid.json"), before)
    assertEquals(recordFiles(root), List(s"$Sid.json"), "no temp residue beside the record")
  }

  test("FDC-SCN-004 versions strictly increase even for writes in the same millisecond") {
    val (s, _) = store()
    var base = versionOf(put(s, Sid, "v0", Some(0L)))
    for i <- 1 to 25 do
      val next = versionOf(put(s, Sid, s"v$i", Some(base)))
      assert(next > base, clue((i, base, next)))
      base = next
  }

  test("FDC-SCN-004 a failed write leaves the stored note intact and no temp file behind") {
    assume(System.getProperty("user.name") != "root", "permission bits do not bind root")
    val (s, root) = store()
    val v1 = versionOf(put(s, Sid, "kept", Some(0L)))
    val notes = root / "ask" / "notes"
    val readOnly = java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x")
    val writable = java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x")
    Files.setPosixFilePermissions(notes.toNIO, readOnly)
    try
      put(s, Sid, "lost", Some(v1)) match
        case AskNoteWrite.Failed(_) => ()
        case other => fail(s"expected Failed, got $other")
    finally Files.setPosixFilePermissions(notes.toNIO, writable)
    assertEquals(s.read(Sid).unsafeRunSync().text, "kept")
    assertEquals(s.read(Sid).unsafeRunSync().version, v1)
    assertEquals(recordFiles(root), List(s"$Sid.json"))
  }

  test("FDC-SCN-005 text equal to the stored text succeeds at the stored version without a new version") {
    val (s, root) = store()
    val v1 = versionOf(put(s, Sid, "same", Some(0L)))
    val before = os.read(root / "ask" / "notes" / s"$Sid.json")
    // A retry whose first attempt already landed carries an older base.
    assertEquals(put(s, Sid, "same", Some(0L)), AskNoteWrite.Ok(v1, v1))
    assertEquals(put(s, Sid, "same", Some(v1)), AskNoteWrite.Ok(v1, v1))
    assertEquals(os.read(root / "ask" / "notes" / s"$Sid.json"), before, "the record was not rewritten")
    assertEquals(s.read(Sid).unsafeRunSync().version, v1)
  }

  test("FDC-SCN-005 a write without a base version fails and writes nothing, for an existing and a missing note") {
    val (s, root) = store()
    put(s, Sid, "text", None) match
      case AskNoteWrite.VersionRequired(current) => assert(!current.exists && current.version == 0L, clue(current))
      case other => fail(s"expected VersionRequired, got $other")
    assertEquals(recordFiles(root), Nil, "a refused write creates no file")
    val v1 = versionOf(put(s, Sid, "kept", Some(0L)))
    put(s, Sid, "overwrite", None) match
      case AskNoteWrite.VersionRequired(current) =>
        assertEquals(current.text, "kept")
        assertEquals(current.version, v1)
      case other => fail(s"expected VersionRequired, got $other")
    assertEquals(s.read(Sid).unsafeRunSync().text, "kept")
  }

  test("FDC-SCN-004 two writes with the same base: exactly one is accepted, even from separate store instances") {
    val (first, root) = store()
    val second = new AskNoteStore(root) // the gateway builds an instance per call
    for round <- 1 to 25 do
      val base = first.read(Sid).unsafeRunSync().version
      val results = List(first.put(Sid, s"A$round", Some(base)), second.put(Sid, s"B$round", Some(base))).parSequence.unsafeRunSync()
      assertEquals(results.count(_.isInstanceOf[AskNoteWrite.Ok]), 1, clue((round, results)))
      assertEquals(results.count(_.isInstanceOf[AskNoteWrite.Conflict]), 1, clue((round, results)))
      assert(Set(s"A$round", s"B$round").contains(first.read(Sid).unsafeRunSync().text))
  }

  test("FDC-SCN-029 the size limit is 256 KiB of UTF-8 bytes: exact accepted, one more refused, for ASCII, Chinese and emoji") {
    val (s, _) = store()
    val limit = s.MaxBytes
    val texts = List(
      "ascii" -> ("a" * limit, "a" * (limit + 1)),
      "chinese" -> ("记" * (limit / 3) + "a" * (limit % 3), "记" * (limit / 3) + "a" * (limit % 3 + 1)),
      "emoji" -> ("😀" * (limit / 4), "😀" * (limit / 4) + "a")
    )
    var base = 0L
    for (kind, (exact, over)) <- texts do
      assertEquals(exact.getBytes(StandardCharsets.UTF_8).length, limit, clue(kind))
      assertEquals(over.getBytes(StandardCharsets.UTF_8).length, limit + 1, clue(kind))
      base = versionOf(put(s, Sid, exact, Some(base)))
      put(s, Sid, over, Some(base)) match
        case AskNoteWrite.TooLarge(bytes, max) =>
          assertEquals(bytes, limit + 1, clue(kind))
          assertEquals(max, limit)
        case other => fail(s"$kind: expected TooLarge, got $other")
      val stored = s.read(Sid).unsafeRunSync()
      assertEquals(stored.text, exact, clue(kind))
      assertEquals(stored.version, base, clue(kind))
  }

  test("FDC-SCN-005 a clear keeps a version: a stale write over it is a conflict and a write based on it succeeds") {
    val (s, root) = store()
    val v1 = versionOf(put(s, Sid, "text", Some(0L)))
    val v2 = versionOf(put(s, Sid, "", Some(v1)))
    val cleared = s.read(Sid).unsafeRunSync()
    assert(!cleared.exists && cleared.cleared && cleared.text.isEmpty, clue(cleared))
    assertEquals(cleared.version, v2)
    assert(v2 > v1, clue((v1, v2)))
    // A client that last saw `text` must not bring it back.
    put(s, Sid, "text again", Some(v1)) match
      case AskNoteWrite.Conflict(current) => assert(current.cleared && current.version == v2, clue(current))
      case other => fail(s"expected Conflict, got $other")
    assert(s.read(Sid).unsafeRunSync().cleared)
    val v3 = versionOf(put(s, Sid, "fresh", Some(v2)))
    assert(v3 > v2, clue((v2, v3)))
    assertEquals(s.read(Sid).unsafeRunSync().text, "fresh")
    assertEquals(recordFiles(root), List(s"$Sid.json"))
    // Clearing a note that never existed records nothing.
    val (empty, emptyRoot) = store()
    assertEquals(put(empty, OtherSid, "", Some(0L)), AskNoteWrite.Ok(0L, 0L))
    assertEquals(recordFiles(emptyRoot), Nil)
  }

  test("FDC-SCN-012 an unreadable record is a read error, is never overwritten and never replaced by the older file") {
    val (s, root) = store()
    val notes = root / "ask" / "notes"
    os.makeDir.all(notes)
    os.write(notes / s"$Sid.json", "{not json")
    os.write(notes / s"$Sid.md", "stale legacy text")
    val garbage = os.read(notes / s"$Sid.json")
    val snap = s.read(Sid).unsafeRunSync()
    assert(snap.error.isDefined, clue(snap))
    assert(!snap.exists && snap.text.isEmpty)
    assert(put(s, Sid, "overwrite", Some(0L)).isInstanceOf[AskNoteWrite.ReadFailed])
    assert(s.write(Sid, "overwrite").unsafeRunSync().isInstanceOf[AskNoteWrite.ReadFailed])
    assertEquals(os.read(notes / s"$Sid.json"), garbage, "the record is byte-for-byte unchanged")
    assertEquals(os.read(notes / s"$Sid.md"), "stale legacy text")
    // A record that contradicts itself and one that cannot be opened are read errors too.
    os.write.over(notes / s"$Sid.json", """{"v":1,"version":5,"text":"x","cleared":true}""")
    assert(s.read(Sid).unsafeRunSync().error.isDefined)
    os.remove.all(notes / s"$Sid.json")
    os.remove(notes / s"$Sid.md")
    os.makeDir(notes / s"$Sid.md") // a directory where the legacy file should be
    val unopened = s.read(Sid).unsafeRunSync()
    assert(unopened.error.isDefined && !unopened.exists, clue(unopened))
    assert(put(s, Sid, "x", Some(0L)).isInstanceOf[AskNoteWrite.ReadFailed])
  }

  test("FDC-SCN-030 a note written before versioning reads with its modification time and migrates on the first accepted write") {
    val (s, root) = store()
    val notes = root / "ask" / "notes"
    os.makeDir.all(notes)
    os.write(notes / s"$Sid.md", "legacy text")
    val legacyVersion = os.mtime(notes / s"$Sid.md")
    val read = s.read(Sid).unsafeRunSync()
    assertEquals((read.text, read.version, read.exists, read.cleared), ("legacy text", legacyVersion, true, false))
    // A writer that never saw the note changes nothing.
    assert(put(s, Sid, "blind", Some(0L)).isInstanceOf[AskNoteWrite.Conflict])
    assert(put(s, Sid, "blind", None).isInstanceOf[AskNoteWrite.VersionRequired])
    assertEquals(os.read(notes / s"$Sid.md"), "legacy text")
    assert(!os.exists(notes / s"$Sid.json"))
    // The writer that read it succeeds and leaves one versioned record.
    val migrated = versionOf(put(s, Sid, "edited", Some(legacyVersion)))
    assert(migrated > legacyVersion, clue((legacyVersion, migrated)))
    assertEquals(recordFiles(root), List(s"$Sid.json"))
    assertEquals(s.read(Sid).unsafeRunSync().text, "edited")
    // A cleared legacy note drops its old text from disk.
    os.write(notes / s"${OtherSid}.md", "to be cleared")
    val otherVersion = os.mtime(notes / s"$OtherSid.md")
    versionOf(put(s, OtherSid, "", Some(otherVersion)))
    assert(!os.exists(notes / s"$OtherSid.md"))
    assert(s.read(OtherSid).unsafeRunSync().cleared)
    assert(!os.read(notes / s"$OtherSid.json").contains("to be cleared"))
  }
