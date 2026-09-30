package nebflow.core.compact

import cats.effect.IO
import nebflow.shared.*

/**
 * Pre-compaction hook for the Root agent (Nebula, depth 0).
 *
 * Replaces DreamMode's idle timer: instead of a 5-minute polling cycle,
 * durable facts were extracted from the conversation right before it was
 * compacted.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 记忆族退役批（memory-family-retirement，2026-09-29）——本件处置与保留理由
 * ═══════════════════════════════════════════════════════════════════════
 *
 * **决定 = 保留本件**（**不**整件退役）+ **逐段删除记忆专属段**。
 * 依据（三类证据，逐条可复核）：
 *   ① **本件有独立于记忆的职责**（非「纯为记忆 enqueue 而生」）：`run(…)` 的
 *      置位信号腿（`[[nebflow.shared.MemoryHygieneSignal.markCompacted]]`）与
 *      队列无关——消费方 `ContextRefresher.buildMemoryBlock` 在压缩后的首个
 *      Nebula 注入里读该信号（T2/T3 清扫提示）。该腿在 `run` 内是**唯一**动作。
 *   ② **记忆专属段早已停产**：`enqueueFacts` 及其分流辅助在生产链路上
 *      **零调用方**（抽取轮随 DreamMode 停用批一并停）；退役批删其本体。
 *   ③ **本件不是六族声明件**：六族符号在本件内**只作引用**（注释 + 入队调用）
 *      ⇒ 删除引用面后本件即「零六族引用」，属清引用而非退役。
 *
 * **删面（逐段）**：队列族 import · `Actor` 常量 · `UserFace` / `AgentFace`
 * 常量 · 分流机读码对象 · 分流裁决枚举 · `decideRoute` / `faceRoom` /
 * `pendingBytesByFace` / `refOf` / `utf8Bytes` / `enqueueFacts`（含其唯一消费的
 * `DreamMode.parseFact` 调用与预算读数）。逐条证据见 retire 报告 §两特殊件处置；
 * 本注释刻意**不复写已退役的符号字面**（引用图闭合判据按 whole-tree `-w` 从严，
 * 注释命中同样计入 —— 批特定口径）。
 *
 * **留面（逐段）**：`run(…)` 全签名 + 置位信号一行 —— 这是本件在生产链路上的
 * 全部现存职责（调用点 `PreCompactionHooks.forProfile` → `CompactionProfile.Root`，
 * 由 `AgentSessionExecution` 的 hook 装配处起用）。
 *
 * **如实登记的行为后果**：队列文件不再有任何生产者/消费者（`enqueueFacts` 是
 * 本件最后一枚写入口，其调用方早已为零）⇒ 存量队列文件成为惰性残留，不再被
 * 引擎读写。这是退役批的**既定意图**，非缺陷。
 */
object RootMemoryHook extends PreCompactionHook:

  // ── 压缩后置位（本件在生产链路上的唯一现存动作）──────────────────────────

  // Phase 5 D 步:删除未消费的 `resources: SharedResources` 形参(抽取轮停用后
  // 本 hook 仅剩置位信号,不读定位器);签名与 PreCompactionHook 同步。
  def run(
    messages: List[Message],
    agentName: String,
    sessionId: Option[String],
    teamName: Option[String]
  ): IO[Unit] =
    if messages.size < 20 then IO.unit
    else
      // 抽取轮已停（见类头注）：本 hook 不再发 LLM 请求、不再入队。
      // 生命周期触发（§6.2-2.5）保留：压缩完成后置位整理提醒信号 —— 消费方
      // ContextRefresher.buildMemoryBlock 在压缩后的首个 Nebula 注入里带
      // 「T2/T3 清扫提示」。仅置位一行。
      // 严格DAG第⑥步第三批A裁定(dwfq-5c7a31ea-1,M4):信号 object 已下沉 nebflow.shared。
      IO(MemoryHygieneSignal.markCompacted())

end RootMemoryHook
