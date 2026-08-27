package nebflow.gateway

import munit.FunSuite

/**
 * P1 2026-08-27 paste-attachment-loss regression guard.
 *
 * 事故：用户粘贴大段文本 → 前端转附件（pasted-text-*.txt）→ WS 发送 →
 * handleMessage 在 receivePipe evalMap 内处理：附件本地搜索（walkFileTree
 * 全目录 + mdfind）对内存 Blob 必然 miss（5-30s），期间用户看不到任何响应
 * 刷新页面 → 连接断开 → receivePipe cancel → 处理链在「uploads 文件已保存、
 * 历史/dispatch 未执行」处被取消 → 消息静默丢失（无 input_history 条目、
 * 无 ui.json 消息、无 LLM 轮次）。
 *
 * 修复两件（源码契约由本 spec 钉死，行为由 E2E /private/tmp 隔离实例覆盖）：
 * 1. case _ 用户消息处理链包 IO.uncancelable —— 连接断开不再丢消息；
 *    重发幂等由 AgentActor checkDuplicate(clientMessageId) 保证。
 * 2. pasted-text-* 前缀 + data 非空（前端内存 Blob 特征）跳过本地文件
 *    搜索，直接走 data 保存分支 —— 消除 5-30s 零反馈窗口。
 */
class PasteAttachmentGuardSpec extends FunSuite:

  private val src =
    os.read(os.pwd / "src" / "main" / "scala" / "nebflow" / "gateway" / "WebSocketRoutes.scala")

  test("user-message dispatch chain is wrapped in IO.uncancelable") {
    val idxUncancelable = src.indexOf("IO.uncancelable(_ =>")
    assert(idxUncancelable >= 0, "IO.uncancelable wrapper removed — user messages can be lost on WS disconnect again")

    // The wrapper must precede the user-message chain it guards (projectRoot
    // resolution is the first step of the case _ dispatch body).
    val idxResolve =
      src.indexOf("Resolve projectRoot for local file search before processing attachments")
    assert(idxResolve > idxUncancelable, "IO.uncancelable no longer wraps the case _ user-message chain")

    // The receive pipe still runs handlers on the connection fiber (evalMap) —
    // that alone is fine only because the user-message tail is uncancelable.
    val idxEvalMap = src.indexOf("_.evalMap {")
    assert(idxEvalMap >= 0)
  }

  test("frontend in-memory blob (pasted-text-*) skips local file search") {
    assert(
      src.contains("val isFrontendBlob = name.startsWith(\"pasted-text-\") && data.nonEmpty"),
      "pasted-text fast-path removed — 5-30s guaranteed-miss search window is back"
    )
    // Both search entry points must be gated by the flag.
    val idxFlag = src.indexOf("val isFrontendBlob")
    val idxWalk = src.indexOf("if !isFrontendBlob && hash.nonEmpty && fileSize > 0 then findLocalFile")
    val idxSpot = src.indexOf("case None if !isFrontendBlob && hash.nonEmpty && fileSize > 0 => spotlightSearch")
    assert(idxWalk > idxFlag, "findLocalFile no longer gated by isFrontendBlob")
    assert(idxSpot > idxFlag, "spotlightSearch no longer gated by isFrontendBlob")
  }

end PasteAttachmentGuardSpec
