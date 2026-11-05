package nebflow.core.ask

import cats.effect.unsafe.implicits.global
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
    val p = root / "ask" / "notes" / s"$Sid.md"
    assert(os.exists(p), clue(s"expected $p"))
    assertEquals(os.read(p), text)
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

  test("N3 空文本 ⇒ 删件（不留空壳；再读 exists=false）") {
    val (s, root) = store()
    s.write(Sid, "有内容").unsafeRunSync()
    val p = root / "ask" / "notes" / s"$Sid.md"
    assert(os.exists(p))
    s.write(Sid, "").unsafeRunSync()
    assert(!os.exists(p), clue("empty text must remove the note file"))
    val snap = s.read(Sid).unsafeRunSync()
    assert(!snap.exists)
    assertEquals(snap.text, "")
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
    val p = root / "ask" / "notes" / s"$Sid.md"
    assertEquals(os.read(p), "旧值")
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
