package nebflow.dropbox

import munit.CatsEffectSuite
import nebflow.shared.AttachContract

/**
 * 附件闸位（作者数三条）逐条判定 —— 硬判据 ①。
 *
 * 口径 = 作者 2026-09-14 09:14「把文件传输的上限增加到一个g。1024MB」⇒ 单件
 * `1024 MB = 1 GiB = 1,073,741,824 B`；件数 ≤9 不变。
 *
 * 边界值一律**实测**，禁静态论证：
 *   - 单文件：`1,073,741,824 B` **通过** / `1,073,741,825 B` **拒绝**（+1 B）；
 *   - 件数：`9` 通过 / `10` 拒绝；
 *   - 超限错误体**回显实际值**（`actual`）与上限（`limit`）。
 */
class AttachLimitsSpec extends CatsEffectSuite:

  private val MaxBytes = 1_073_741_824L

  test("量纲冻结：上限 = 1024 MB = 1 GiB = 1,073,741,824 B（= 1024 MiB）") {
    assertEquals(AttachContract.MaxFileBytes, 1_073_741_824L)
    assertEquals(AttachContract.MaxAttachmentsPerMessage, 9)
    assertEquals(AttachContract.MaxFileBytes, 1024L * 1024 * 1024, "1024 MB = 1 GiB = 2^30 B")
    assert(AttachContract.MaxFileBytes != 1_024_000_000L, "must not be 1024 decimal MB (1.024e9)")
  }

  test("单文件边界：1,073,741,824 B 通过（正控，防量纲写错）") {
    assertEquals(AttachContract.checkFileSize(MaxBytes), Right(()))
  }

  test("单文件边界：1,073,741,825 B 拒绝（+1 B）且回显实际值 + 上限") {
    AttachContract.checkFileSize(MaxBytes + 1) match
      case Left(err) =>
        assertEquals(err.code, AttachContract.Codes.AttachTooLarge)
        assertEquals(err.actual, Some(MaxBytes + 1))
        assertEquals(err.limit, Some(MaxBytes))
        assert(err.message.contains("1073741825"), s"message must echo the actual byte count: ${err.message}")
        assert(err.message.contains("1,073,741,824"), s"message must echo the limit: ${err.message}")
      case Right(_) => fail("1,073,741,825 B must be rejected")
  }

  test("旧上限已作废（回归）：100 MB 十进制 / 100 MiB 现都必须通过 —— 不留 100 MB 假上限") {
    assertEquals(AttachContract.checkFileSize(100_000_000L), Right(()))
    assertEquals(AttachContract.checkFileSize(104_857_600L), Right(()))
  }

  test("件数边界：9 件通过（正控）") {
    assertEquals(AttachContract.checkAttachmentCount(9), Right(()))
    assertEquals(AttachContract.checkMessage(List.fill(9)(1_000L)), Right(()))
  }

  test("件数边界：10 件拒绝且回显实际件数 + 上限") {
    AttachContract.checkAttachmentCount(10) match
      case Left(err) =>
        assertEquals(err.code, AttachContract.Codes.AttachTooMany)
        assertEquals(err.actual, Some(10L))
        assertEquals(err.limit, Some(9L))
      case Right(_) => fail("10 attachments must be rejected")
  }

  test("组合闸：9 件里只要有一件超限，整条消息即拒（先报件数，再报大小）") {
    val nine = List.fill(8)(1_000L) :+ (MaxBytes + 1)
    AttachContract.checkMessage(nine) match
      case Left(err) =>
        assertEquals(err.code, AttachContract.Codes.AttachTooLarge)
        assertEquals(err.actual, Some(MaxBytes + 1))
      case Right(_) => fail("a message with one oversized attachment must be rejected")

    val ten = List.fill(10)(1_000L)
    AttachContract.checkMessage(ten) match
      case Left(err) => assertEquals(err.code, AttachContract.Codes.AttachTooMany)
      case Right(_) => fail("10 attachments must be rejected")
  }

  test("错误体是机器可解析的（code/actual/limit 三键齐备）") {
    val json = AttachContract.checkFileSize(MaxBytes + 1).left.toOption.get.toJson
    val hc = json.hcursor
    assertEquals(hc.downField("code").as[String].toOption, Some("ATTACH_TOO_LARGE"))
    assertEquals(hc.downField("actual").as[Long].toOption, Some(MaxBytes + 1))
    assertEquals(hc.downField("limit").as[Long].toOption, Some(MaxBytes))
  }

  test("分块计划：末块不补零长；恰整除时不产生空末块") {
    val chunk = 4
    assertEquals(
      AttachContract.plan(10, chunk),
      List(
        AttachContract.ChunkPlan(0, 0, 4),
        AttachContract.ChunkPlan(1, 4, 4),
        AttachContract.ChunkPlan(2, 8, 2)
      )
    )
    assertEquals(
      AttachContract.plan(8, chunk),
      List(AttachContract.ChunkPlan(0, 0, 4), AttachContract.ChunkPlan(1, 4, 4))
    )
    assertEquals(AttachContract.plan(0, chunk), Nil)
  }

  test("1,073,741,824 B 的块数（4 MiB 块）= 256 整除，无短末块") {
    val plan = AttachContract.plan(AttachContract.MaxFileBytes)
    assertEquals(plan.size, 256)
    assertEquals(plan.head.bytes, AttachContract.ChunkSize)
    assertEquals(plan.zip(plan.tail).map((a, b) => b.offset - a.offset).distinct, List(AttachContract.ChunkSize.toLong))
    assertEquals(plan.last.offset, 1_069_547_520L)
    assertEquals(plan.last.offset + plan.last.bytes, AttachContract.MaxFileBytes)
    assertEquals(plan.last.bytes, AttachContract.ChunkSize)
  }

end AttachLimitsSpec
