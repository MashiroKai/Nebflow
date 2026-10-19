package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.core.tools.AskUserQuestionTool
import nebflow.shared.{AttachContract, PathUtil}

import java.nio.file.Files
import scala.concurrent.duration._

/**
 * askuser-dragdrop 实施批（2026-10-19，批 #551）配对 spec：`POST /api/askuser/attachments`
 * 上传域的**判据面**。路由本体是薄壳（鉴权 / hub 闸 / multipart 解码在
 * `AskUserAttachmentRoutes.routes`），判据按该文件自己的约定「spec 直取」——
 * 纯闸（计数 / 并发 / 尺寸）、文件名清洗、seq 命名、出向记录行、台账推进、
 * 惰性孤儿清扫（真盘，`PathUtil.setDataRoot` 换临时根）。
 *
 * 🔴 与既有 spec 的分工（禁重复层）：
 *   - `AskUserHistoryAttachmentsSpec`：askUser 落盘行的 attachments 键（帧解析层 +
 *     盘上字节）——历史恢复腿；
 *   - `AskUserAnswerPersistSpec`：`UiMessage.AskUser` 类型层编码/解码；
 *   - 本 spec：**上传受理域**（端点闸 + 落盘布局 + 清扫），与两者零重叠。
 *
 * 端到端链路（浏览器拖入 → XHR 上传 → 落盘 → 作答帧携带 → 终验）由
 * `tests/askuser-attach.spec.mjs` 覆盖（前端面 + 端点集成，Playwright）。
 */
class AskUserAttachmentRoutesSpec extends CatsEffectSuite:

  private val RID = "asknb-e2e0001"

  // ── 上限尺单一数值权威（双向同源防口径漂移）────────────────────────────

  test("MaxBytes 与工具出向面同值常量（100MB 单一数值权威）") {
    assertEquals(AskUserAttachmentRoutes.MaxBytes, AskUserQuestionTool.MaxAttachmentBytes)
    assertEquals(AskUserAttachmentRoutes.MaxBytes, 100L * 1024 * 1024)
  }

  test("MaxPerCard 与 AttachContract 同源（每卡 ≤9）") {
    assertEquals(AskUserAttachmentRoutes.MaxPerCard, AttachContract.MaxAttachmentsPerMessage)
    assertEquals(AskUserAttachmentRoutes.MaxPerCard, 9)
  }

  // ── 文件名清洗（设计稿 §3.2：去路径分隔符与控制字符，保留 unicode）──────

  test("sanitizeName 去路径分隔符与 ..，保留 unicode") {
    assertEquals(AskUserAttachmentRoutes.Store.sanitizeName("a/b/c.png"), "a_b_c.png")
    assertEquals(AskUserAttachmentRoutes.Store.sanitizeName("..\\evil.sh"), "___evil.sh")
    assertEquals(AskUserAttachmentRoutes.Store.sanitizeName("报告 v1.pdf"), "报告 v1.pdf")
    assertEquals(AskUserAttachmentRoutes.Store.sanitizeName("\u0007bell.txt"), "bell.txt")
  }

  test("sanitizeName 全空 ⇒ \"file\"（seq 前缀仍保唯一性）") {
    assertEquals(AskUserAttachmentRoutes.Store.sanitizeName("///"), "file")
    assertEquals(AskUserAttachmentRoutes.Store.fileNameFor(3, "file"), "3-file")
  }

  test("dirName 单一拼接点：ask-<requestId>") {
    assertEquals(AskUserAttachmentRoutes.Store.dirName(RID), s"ask-$RID")
  }

  // ── 纯闸（顺序即路由实现序）────────────────────────────────────────────

  test("countGate：满 9 ⇒ Left(TOO_MANY)；8 ⇒ Right") {
    val full = AskUserAttachmentRoutes.CardEntry(9, 0, Map.empty)
    val ok = AskUserAttachmentRoutes.CardEntry(8, 0, Map.empty)
    assertEquals(AskUserAttachmentRoutes.countGate(full), Left("ASKUSER_ATTACH_TOO_MANY"))
    assertEquals(AskUserAttachmentRoutes.countGate(ok), Right(()))
  }

  test("concurrencyGate：3 并发在飞 ⇒ Left(BUSY)；2 ⇒ Right") {
    val busy = AskUserAttachmentRoutes.CardEntry(0, 3, Map.empty)
    val ok = AskUserAttachmentRoutes.CardEntry(0, 2, Map.empty)
    assertEquals(AskUserAttachmentRoutes.concurrencyGate(busy), Left("ASKUSER_ATTACH_BUSY"))
    assertEquals(AskUserAttachmentRoutes.concurrencyGate(ok), Right(()))
  }

  test("sizeGate：恰在限额（含）⇒ Right；超 1 字节 ⇒ Left(TOO_LARGE)") {
    assertEquals(AskUserAttachmentRoutes.sizeGate(AskUserAttachmentRoutes.MaxBytes), Right(()))
    assertEquals(
      AskUserAttachmentRoutes.sizeGate(AskUserAttachmentRoutes.MaxBytes + 1),
      Left("ASKUSER_ATTACH_TOO_LARGE")
    )
  }

  test("accept：count 恒增（去重也计数防刷）；sha 索引记录新落盘件") {
    val e0 = AskUserAttachmentRoutes.CardEntry(0, 0, Map.empty)
    val rec = AskUserAttachmentRoutes.Rec("/uploads/s/ask-r/1-a.png", 1, 10L, "image/png", "sha-a")
    val e1 = AskUserAttachmentRoutes.accept(e0, "sha-a", rec)
    assertEquals(e1.count, 1)
    assertEquals(e1.files.get("sha-a").map(_.path), Some(rec.path))
    // 去重再受理（同一 sha 指向既有 path）：count 照加，索引不换。
    val e2 = AskUserAttachmentRoutes.accept(e1, "sha-a", rec)
    assertEquals(e2.count, 2)
    assertEquals(e2.files.size, 1)
  }

  // ── 出向记录行（上传响应 / .ui.json / 前端 chips 同一形状族）────────────

  test("recJson：id=att-<rid>-<seq>，kind 按 mime 分 image/file") {
    val rec = AskUserAttachmentRoutes.Rec("/x/1-a.png", 1, 10L, "image/png", "sha-a")
    val j = AskUserAttachmentRoutes.Store.recJson(RID, rec, "a.png")
    assertEquals(j.hcursor.get[String]("id").toOption, Some(s"att-$RID-1"))
    assertEquals(j.hcursor.get[String]("kind").toOption, Some("image"))
    assertEquals(j.hcursor.get[String]("path").toOption, Some(rec.path))
    val j2 = AskUserAttachmentRoutes.Store.recJson(
      RID, rec.copy(mime = "application/pdf"), "a.pdf"
    )
    assertEquals(j2.hcursor.get[String]("kind").toOption, Some("file"))
  }

  // ── 惰性孤儿清扫（真盘：换临时 dataRoot）────────────────────────────────

  /** 换临时根跑判据（先例 ReloadStaleSessionModelSpec：存 original，guarantee 复位）。 */
  private def withTempRoot(test: os.Path => IO[Unit]): Unit =
    val tmp = Files.createTempDirectory("nebflow-askattach-test")
    val root = os.Path(tmp)
    val original = PathUtil.dataRoot
    PathUtil.setDataRoot(root)
    try test(root).unsafeRunSync()
    finally
      PathUtil.setDataRoot(original)
      os.remove.all(root)

  test("markAnswered 落标记；sweepOrphans 保留有标记目录、清掉无标记陈旧目录") {
    withTempRoot { root =>
      IO {
        val sid = "sweep-sid"
        val dirA = root / "uploads" / sid / s"ask-$RID-a" // 已作答：有标记 ⇒ 留
        val dirB = root / "uploads" / sid / s"ask-$RID-b" // 孤儿：陈旧无标记 ⇒ 清
        val dirC = root / "uploads" / sid / s"ask-$RID-c" // 新鲜无标记 ⇒ 留（24h 内）
        val dirD = root / "uploads" / sid / "not-ask" // 非 ask- 目录 ⇒ 永不触碰
        List(dirA, dirB, dirC, dirD).foreach(d => os.makeDir.all(d))
        os.write.over(dirA / "1-photo.png", Array[Byte](1))
        os.write.over(dirB / "1-note.txt", Array[Byte](2))
        os.write.over(dirD / "keeper.txt", Array[Byte](3))
        // 陈旧化：把 A/B 的 mtime 拨回 25h 前（A 有标记，陈旧与否无关紧要——同拨保险）。
        val stale = System.currentTimeMillis() - (25L * 60 * 60 * 1000)
        os.write.over(dirA / AskUserAttachmentRoutes.AnsweredMarker, Array.emptyByteArray)
        List(dirA, dirB).foreach(d => os.mtime.set(d, stale))

        val removed = AskUserAttachmentRoutes.sweepOrphans().timeout(10.seconds).unsafeRunSync()
        assertEquals(removed, 1, "只清 B（无标记且陈旧）")
        assert(os.isDir(dirA), "已作答卡目录带 .answered 标记 ⇒ 永久保留")
        assert(os.exists(dirA / "1-photo.png"))
        assert(os.isDir(dirC), "新鲜目录 24h 内 ⇒ 留")
        assert(os.isDir(dirD), "非 ask- 目录 ⇒ 留")
        assert(!os.exists(dirB))
      }
    }
  }

  test("sweepOrphans：无 uploads 基座 ⇒ 0 且不抛") {
    withTempRoot { root =>
      IO {
        assertEquals(AskUserAttachmentRoutes.sweepOrphans().unsafeRunSync(), 0)
        assert(!os.exists(root / "uploads"))
      }
    }
  }
end AskUserAttachmentRoutesSpec
