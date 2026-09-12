package nebflow.dropbox

import munit.CatsEffectSuite

/**
 * 附件闸位（作者数三条）逐条判定 —— 硬判据 ①。
 *
 * 边界值一律**实测**，禁静态论证：
 *   - 单文件：`100,000,000 B` **通过** / `100,000,001 B` **拒绝**（十进制量纲，非 MiB）；
 *   - 件数：`9` 通过 / `10` 拒绝；
 *   - 超限错误体**回显实际值**（`actual`）与上限（`limit`）。
 */
class AttachLimitsSpec extends CatsEffectSuite:

  private val MaxBytes = 100_000_000L

  test("量纲冻结：上限 = 100 MB 十进制 = 100,000,000 B（≠ 100 MiB = 104,857,600 B）") {
    assertEquals(AttachContract.MaxFileBytes, 100_000_000L)
    assertEquals(AttachContract.MaxAttachmentsPerMessage, 9)
    assert(AttachContract.MaxFileBytes != 100L * 1024 * 1024, "must not be the binary MiB value")
  }

  test("单文件边界：100,000,000 B 通过（正控，防量纲写错）") {
    assertEquals(AttachContract.checkFileSize(MaxBytes), Right(()))
  }

  test("单文件边界：100,000,001 B 拒绝（+1 B）且回显实际值 + 上限") {
    AttachContract.checkFileSize(MaxBytes + 1) match
      case Left(err) =>
        assertEquals(err.code, AttachContract.Codes.AttachTooLarge)
        assertEquals(err.actual, Some(MaxBytes + 1))
        assertEquals(err.limit, Some(MaxBytes))
        assert(err.message.contains("100000001"), s"message must echo the actual byte count: ${err.message}")
        assert(err.message.contains("100,000,000"), s"message must echo the limit: ${err.message}")
      case Right(_) => fail("100,000,001 B must be rejected")
  }

  test("单文件边界：100 MiB（104,857,600 B）必须被拒 —— 量纲混用会被这一条抓住") {
    AttachContract.checkFileSize(104_857_600L) match
      case Left(err) => assertEquals(err.code, AttachContract.Codes.AttachTooLarge)
      case Right(_)  => fail("100 MiB must be rejected (the limit is 100 MB decimal)")
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
      case Right(_)  => fail("10 attachments must be rejected")
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

  test("100,000,000 B 的块数（4 MiB 块）= 24，末块 = 100,000,000 − 23×4,194,304 = 3,531,008") {
    val plan = AttachContract.plan(AttachContract.MaxFileBytes)
    assertEquals(plan.size, 24)
    assertEquals(plan.head.bytes, AttachContract.ChunkSize)
    assertEquals(plan.zip(plan.tail).map((a, b) => b.offset - a.offset).distinct, List(AttachContract.ChunkSize.toLong))
    assertEquals(plan.last.offset, 96_468_992L)
    assertEquals(plan.last.offset + plan.last.bytes, AttachContract.MaxFileBytes)
    assertEquals(plan.last.bytes, 3_531_008)
  }

end AttachLimitsSpec
