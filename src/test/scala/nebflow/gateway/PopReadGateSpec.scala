package nebflow.gateway

import munit.FunSuite
import nebflow.shared.AttachContract

/**
 * P2 批（阅读器体验设计件 §5.1）`pop.readFile` 打开闸拆分 —— 两门各归各位：
 *
 *   - 文本腿维持 `MaxPopReadFileBytes`（100MB，2026-09-20 作者令值，**不动**）；
 *   - 二进制查看腿（nf-file 族）解除到 `AttachContract.MaxFileBytes`
 *     （1 GiB）——`MaxViewerFileBytes` 必须与附件总闸**同值同源**（禁第二把尺）。
 *
 * 验收表 A8 的判据（100MB+1 的 PDF 可打开 / 100MB+1 的文本件仍被文本闸拒）在此
 * 以纯函数面钉死；handler 接线（`WsFileOpsHandlers.handlePopReadFile` 在闸前先做
 * `FileTypeRegistry.detect` 二值判定）由本 spec 用**真实注册表**的 binary 位
 * 穿透验证 —— 扩展名→binary 的映射漂移会在此显式 red。
 */
class PopReadGateSpec extends FunSuite:

  test("MaxViewerFileBytes 与 AttachContract.MaxFileBytes 同值（1 GiB 单一数值权威）"):
    assertEquals(TextStreamSearch.MaxViewerFileBytes, AttachContract.MaxFileBytes)
    assertEquals(TextStreamSearch.MaxViewerFileBytes, 1_073_741_824L)

  test("文本腿闸维持 100MB 不动"):
    assertEquals(TextStreamSearch.MaxPopReadFileBytes, 100L * 1024 * 1024)
    assertEquals(TextStreamSearch.popReadGateLimit(isBinary = false), TextStreamSearch.MaxPopReadFileBytes)

  test("二进制腿闸 = 1 GiB（100MB+1 放行 —— A8 的 PDF 面）"):
    assertEquals(TextStreamSearch.popReadGateLimit(isBinary = true), TextStreamSearch.MaxViewerFileBytes)
    // 100MB+1：改前被文本闸误杀的那个尺寸，现在二进制腿放行。
    assert(TextStreamSearch.MaxPopReadFileBytes + 1 <= TextStreamSearch.popReadGateLimit(isBinary = true))

  test("二进制 1GiB+1 仍被拒（解除不是无上限）"):
    assert(AttachContract.MaxFileBytes + 1 > TextStreamSearch.popReadGateLimit(isBinary = true))

  test("真实注册表 binary 位穿透：pdf 走 1 GiB 闸，txt/log 走 100MB 闸"):
    val pdfBinary = nebflow.core.workspace.FileTypeRegistry.detect("pdf").binary
    val txtBinary = nebflow.core.workspace.FileTypeRegistry.detect("txt").binary
    assert(pdfBinary, "registry drift: .pdf must stay binary (the whole P2 premise)")
    assert(!txtBinary, "registry drift: .txt must stay text")
    assertEquals(
      TextStreamSearch.popReadGateLimit(pdfBinary),
      AttachContract.MaxFileBytes,
      ".pdf gate must be the 1 GiB viewer gate"
    )
    assertEquals(
      TextStreamSearch.popReadGateLimit(txtBinary),
      TextStreamSearch.MaxPopReadFileBytes,
      ".txt gate must stay the 100MB text gate"
    )
    // 同一尺寸两门不同判（A8 的二值核心）：
    val over100mb = TextStreamSearch.MaxPopReadFileBytes + 1
    assert(over100mb > TextStreamSearch.popReadGateLimit(txtBinary), "100MB+1 text must still be refused")
    assert(over100mb <= TextStreamSearch.popReadGateLimit(pdfBinary), "100MB+1 pdf must pass the gate")

end PopReadGateSpec
