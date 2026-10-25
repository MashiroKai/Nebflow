package nebflow.gateway

import munit.FunSuite

/**
 * attach-split 批（2026-10-24 作者现场报障）源码契约钉：
 *
 * 事故：网页控制台 22 张图 + 文字一条 WS 帧 ≈11MB > 旧 10MB 上限 ⇒
 * `handleMessage` 超限支**整帧静默丢弃**（含文字），前端已渲乐观气泡 = 假成功；
 * 随后纯文本正常到达（「纯文本通、带附件整体吞」）。
 *
 * 修复两件（行为由 tests/attach-split.spec.mjs 真链覆盖，本 spec 钉源码契约）：
 * 1. MaxMessageSize 10MB → 64MB：栈内无更低帧级 cap（http4s-core 0.23.30
 *    FrameTranscoder 无 size 限制）⇒ 22 图级体积（前端 1920px/q0.8 压缩后
 *    ~30-40MB 上限）单帧可达。
 * 2. 超限支从「只留一行 logger.warn」改为**有声拒绝**：尽力解析
 *    sessionId/clientMessageId 回发 `messageTooLarge` 错误帧（客户端据此给出
 *    用户可见错误并收幻影 busy —— 超限有声，禁假成功）。
 * 3. 前端拆分阈（8MB）刻意低于**旧** 10MB 上限 —— 拆分后的附件帧（content 空 ∧
 *    attachments 非空）依赖 typeless 准入谓词 `content.nonEmpty || attachments.
 *    nonEmpty` 受理：该谓词钉死不得放宽/收窄（fwdguard 批作者裁定同面）。
 */
class OversizeFrameLoudRejectSpec extends FunSuite:

  private val src =
    os.read(os.pwd / "src" / "main" / "scala" / "nebflow" / "gateway" / "WebSocketRoutes.scala")

  test("frame size cap raised to 64MB (22-image scale fits one frame)") {
    assert(
      src.contains("MaxMessageSize = 64 * 1024 * 1024"),
      "MaxMessageSize is not 64MB — the 22-image incident frame (~11MB) fits the old 10MB cap again"
    )
  }

  test("oversized frame gets a loud messageTooLarge receipt, not a silent warn-only drop") {
    val idxWarn = src.indexOf("Dropping oversized WebSocket message")
    assert(idxWarn >= 0, "oversized-drop warn removed — the size gate itself is gone")
    assert(
      src.contains("\"messageTooLarge\""),
      "oversized branch no longer sends the messageTooLarge receipt —超限又变回静默丢"
    )
    // 回执必须带客户端对账键（sessionId + clientMessageId），幻影 busy 才有得收。
    val idxReceipt = src.indexOf("\"messageTooLarge\"")
    assert(
      src.indexOf("overSid") < idxReceipt && src.indexOf("overCmid") < idxReceipt,
      "messageTooLarge receipt lost its sessionId/clientMessageId fields"
    )
    assert(idxReceipt > idxWarn || src.indexOf("messageTooLarge receipt sent") >= 0)
  }

  test("typeless gate predicate intact (attachment-only chunk frames must stay admissible)") {
    assert(
      src.contains("content.nonEmpty || attachments.nonEmpty"),
      "typeless admission predicate changed — frontend attachment-chunk frames (empty content) " +
        "would be dropped again (fwdguard-impl pinned this face: 禁放宽也禁误伤)"
    )
  }

  test("per-attachment failure still cannot连坐 text (fallback blocks intact)") {
    // 附件级失败兜底块：单附件保存失败只降级为该附件的占位块，不动正文块。
    assert(src.contains("Failed to save image"), "image save-failure fallback block removed")
    assert(src.contains("Failed to save attachment"), "file save-failure fallback block removed")
  }

end OversizeFrameLoudRejectSpec
