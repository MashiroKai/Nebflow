package nebflow.service

import cats.effect.IO
import nebflow.core.NebflowLogger

import java.nio.charset.StandardCharsets

/**
 * 记忆落盘单点闸（M4 —— 工具面收敛设计 §4 Q2-④ ④-b / M2 表 M4 行；作者 2026-09-13
 * 裁定「M4 闸下沉 = 独立小批立刻立项」）。
 *
 * 动机：预算闸与写前快照闸此前的**实现在调用方**（旧 MemoryEdit 路径内 / Dream hook 内），
 * 而落盘单点 [[MemoryStore.saveUserMemory]] / [[MemoryStore.saveAgentMemory]] 与
 * `ProjectMemory.save` 自身**零闸** ⇒ 不经调用方闸的写入没有任何闸。今天生产面上唯一的
 * 这类写入 = WS `saveMemory` 旁路（`WebSocketRoutes:3265/3271`），它直调 `MemoryStore.save*`。
 * 本对象把两道闸下沉到落盘单点，使「过闸」成为**落盘的必要条件**，而不是调用方的自觉。
 *
 * 边界（写死，越界即不合）：
 *   - **不覆盖** Write / Edit / Bash 直写路径（Bash 完备 ⇒ 无机械封堵；本对象**不声称**
 *     覆盖它们，也不为此加壳、不改工具面）；
 *   - **不退役** MemoryEdit（Q2 组已落「保留」）；
 *   - **不动**授能面 / 静态集 / 注册表（非本项范围）。
 *
 * ## 闸序 = 预算 → 快照 → 落盘（作者 2026-09-14 两处裁定；**取代** 09-13 任务书旧序）
 *
 *   - **① 预算闸前置**：超硬顶 ⇒ **结构化拒绝**，且**拒绝路径零文件写（含 `backups/` 面）**
 *     ——快照**不在**拒绝路径上发生。理由：契约一致性（「闸不过 ⇒ 拒绝落地、零文件写」的
 *     不变式不得降级为「只不写记忆面、备份面照写」）｜拒绝是高频路径（每次压缩可能触发
 *     ⇒ 备份面只增不减）｜留痕已有承载（`queue.jsonl` outcome `rejected`/`deferred`
 *     + 引擎日志）。
 *   - **② 快照的唯一触发点 = 「预算闸放行、即将落盘」**；快照失败 ⇒ 结构化拒绝 + **零写入**
 *     （fail-closed，与 hook 侧「跳过合并零写入」同形）。
 *   - 旧序（快照 → 预算，拒绝时也快照）是 09-13 任务书的正控口径，**已被 09-14 裁定取代**：
 *     拒绝路径的判据从「不快照」升级为「零文件写」。
 *
 * ## 纯收缩豁免（作者 2026-09-14 裁定 (b)，边界写死）
 *
 *   - 豁免判据 = **字节比**（新内容字节 ≤ 现文件字节；逐文件 POST-WRITE vs PRE-WRITE），
 *     🔴 **不按动作名豁免**；适格面 = 收缩通道（`replace_section`，工具契约原文
 *     「`replace_section` stays exempt — it is the shrinking channel」，`MemoryEditTool:92`）
 *     ⇒ 调用方以 `shrinkChannel = true` 声明**适格身份**，闸再用字节比拦「夹带净增」；
 *   - 适格 + 真收缩 ⇒ 只豁免**预算闸**：路径白名单 / 条目格式校验（工具层职责）、快照前置
 *     （按 ① 新序）、「闸不过 ⇒ 零写」不变式 **全部照旧**；
 *   - 适格 + 实际净增 ⇒ **照过闸**（判据是字节比，不是动作名）；
 *   - `shrinkChannel = false`（缺省 = 现全部生产调用方）⇒ **永不豁免**（WS `saveMemory`
 *     的整文件覆盖不是收缩通道：超限文件的自救路径是 `replace_section`）。
 *   - 🔴 现场读数：本闸**自身**的落盘调用面（[[MemoryStore.saveFile]] / `ProjectMemory.save`）
 *     仍是**零 `true` 调用方**——`MemoryEdit` 已零落盘（只入队）；WS `saveMemory` 的整文件
 *     覆盖**不是**收缩通道（本文件 :45-46），超限文件的自救路径必须是 `replace_section`。
 *     **队列消费侧的适格声明（2026-09-15 memshrinkgate 批）落在这道闸之外**：消费落地由
 *     整理会话经通用 `Edit`/`Write` 完成（本对象**不覆盖**直写路径，见边界 :19-21），故
 *     消费侧的前置闸 = [[nebflow.core.tools.MemoryQueue]] 的预算停点，由它传
 *     `shrinkChannel = true` 并用同一 [[shrinkExempt]] 独立裁决（判据单源，防两面漂移）。
 *
 * 判据来源（零新语义）：硬顶 / 软线一律取自 [[MemoryBudget]]（唯一常量源），本对象
 * 不复制数值、不新增阈值、不改判据函数。
 *
 * 可见出口（不引入任何新的静默路径）：
 *   - 超硬顶 ⇒ **结构化拒绝**（[[Rejected]] + [[Code.Budget]]，文本含硬顶 / 实际字节 /
 *     top-3 最大节 / 整理出路）——与工具侧的既有处置同形（结构化拒绝）；
 *   - 快照失败 ⇒ **结构化拒绝**（[[Code.Snapshot]]），**零写入**（fail-closed）——与
 *     hook 侧「快照失败 ⇒ 跳过合并零写入」同形；
 *   - 超软线未超硬顶 ⇒ **放行**，并落一条带 [[WarnMarker]] 的结构化 WARN（日志可判）。
 *
 * 调用方义务：[[Rejected]] 走 **IO 错误通道**（不 handle 即传播）——调用方不得把它吞成
 * 静默 no-op；需要面向用户的措辞时按 `code` 分派（见 `WebSocketRoutes` 的 `saveMemory`
 * 错误帧）。
 */
object MemoryWriteGate:

  private val logger = NebflowLogger.forName("nebflow.memory.writegate")

  /** 稳定码（结构化拒绝的机器可判标识）。 */
  object Code:
    /** 超硬顶：拒绝。 */
    val Budget: String = "MEMORYSTORE_BUDGET"
    /** 写前快照失败：拒绝（fail-closed，零写入）。 */
    val Snapshot: String = "MEMORYSTORE_SNAPSHOT"

  /** 软线放行的日志标记（可 grep；与结构化拒绝同族的可判出口）。 */
  val WarnMarker: String = "MEMORYSTORE_BUDGET_WARN"

  /** 落盘单点闸的结构化拒绝。`code` 面向机器（可判），`detail` 面向人（可行动）。 */
  final class Rejected(val code: String, val detail: String)
      extends RuntimeException(s"MemoryWriteGate: rejected [$code] — $detail")

  /** **收缩豁免判据（单源）**：`shrinkChannel` 只声明「本调用方走收缩通道」这一**身份**，
    * 是否真豁免一律由**字节比**独立裁决（POST-WRITE ≤ PRE-WRITE）——🔴 **不按动作名**，
    * 适格 + 净增**照过闸**。
    *
    * 两个消费方，同一个判据（作者 2026-09-15 裁定 A 的机械形态 = 「满格时放行删除/替换类
    * 条目落盘，append 类仍拒至回到预算内」，定性 = 把闸对齐其已文档化设计初衷）：
    *   - 本闸的预算分支（`exempt = shrinkExempt(pre, bytes, shrinkChannel)`）；
    *   - [[nebflow.core.tools.MemoryQueue]] 的预算停点 + 停点闩（消费侧前置计划）——
    *     目标超硬顶时，缩容方向（真收缩）条目照旧放行、净增条目照旧被截断。
    *
    * 抽成单源的理由：两处若各写一份「收缩」判据，迟早出现「计划面放行、落盘闸拒绝」
    * （或反之）的判据漂移——那正是「超限文件的自救路径被自己的前置闸掐死」的死锁成因。
    *
    * 纯函数、零 IO（供 [[nebflow.core.tools.MemoryQueue]] 的只读计划面共用）。 */
  def shrinkExempt(preBytes: Long, newBytes: Long, shrinkChannel: Boolean): Boolean =
    shrinkChannel && newBytes <= preBytes

  /** 现文件字节 = 豁免判据的 PRE-WRITE 腿。目标不存在 ⇒ 0（首次写入 ⇒ 任何内容都是净增，
    * 不豁免）；读元数据失败 ⇒ 也按 0（保守：不豁免；后续预算闸与快照照走，无静默分支）。
    * 只读元数据，从不写盘。 */
  private def preSizeBytes(path: os.Path): Long =
    try if os.exists(path) then os.size(path).toLong else 0L
    catch case _: Exception => 0L

  /** **纯收缩豁免判据（单源）**——本闸与消费侧前置计划（[[nebflow.core.tools.MemoryQueue]]
    * 的预算停点）共用同一个字节比口径，见 [[shrinkExempt]]。
    *
    * 过闸判定（fail-closed）。`Right` = 允许落盘；`Left` = 调用方**必须中止**（零写入）。
    *
    * `shrinkChannel` = 调用方声明自己走收缩通道（`replace_section`）；是否真豁免由本方法用
    * **字节比**独立判定（见头注「纯收缩豁免」）——适格 + 净增照过闸。
    *
    * 副作用三处（便于 spec 直测）：软线 WARN 日志、PRE-WRITE 字节读（只读元数据）、
    * 放行路径上的写前快照。**拒绝路径零文件写**：不落目标文件、不落快照。 */
  def decide(target: String, path: os.Path, newContent: String, shrinkChannel: Boolean = false): Either[Rejected, Unit] =
    val bytes = newContent.getBytes(StandardCharsets.UTF_8).length.toLong
    val exempt = shrinkExempt(preSizeBytes(path), bytes, shrinkChannel)
    val budget: Either[Rejected, Unit] =
      if exempt then Right(())
      else
        MemoryBudget.verdict(target, bytes) match
          case MemoryBudget.Exceeded(_, hard) =>
            // 闸 1（前置）：超硬顶 = 结构化拒绝。🔴 此处**不得有任何文件写**（含备份面）——
            // 快照在预算放行之后才发生（作者 09-14 裁定 (a)）。
            Left(Rejected(Code.Budget, budgetDetail(target, path, bytes, hard, newContent)))
          case MemoryBudget.Warn(_, soft, hard) =>
            logger.warnSync(warnDetail(target, path, bytes, soft, hard))
            Right(())
          case MemoryBudget.Within =>
            Right(())
    budget.flatMap { _ =>
      // 闸 2（唯一触发点 = 「预算闸放行、即将落盘」）：无快照不落笔（fail-closed）。
      MemorySnapshot.snapshotBeforeWrite(path) match
        case Left(reason) => Left(Rejected(Code.Snapshot, snapshotDetail(target, path, reason)))
        case Right(_)     => Right(())
    }

  /** IO 形态：`Left` ⇒ `raiseError`（错误沿 IO 通道向上；调用方无法在不知不觉中吞掉，
    * 与旧路径 `Either[ToolError, …]` 的「结构化拒绝」同形）。 */
  def guard(target: String, path: os.Path, newContent: String, shrinkChannel: Boolean = false): IO[Unit] =
    IO.blocking(decide(target, path, newContent, shrinkChannel)).flatMap {
      case Right(()) => IO.unit
      case Left(err) => IO.raiseError(err)
    }

  // ---------------------------------------------------------------
  // 文案（调用方按 code 分派；正文自含「是什么 / 期望是什么 / 怎么修」）
  // ---------------------------------------------------------------

  private def snapshotDetail(target: String, path: os.Path, reason: String): String =
    s"""the pre-write snapshot failed ($reason), so nothing was written — $path is untouched.
       |The copy would have gone to ${MemorySnapshot.backupRoot} (target='$target').
       |Check space/permissions on that directory, then retry. (${Code.Snapshot})""".stripMargin

  private def budgetDetail(target: String, path: os.Path, bytes: Long, hard: Long, newContent: String): String =
    val pct = if hard > 0 then f"${bytes * 100.0 / hard}%.0f%%" else "?"
    val label = target match
      case "user"  => "~/.nebflow/User.md"
      case "agent" => "~/.nebflow/agents/Nebula/memory.md"
      case other   => path.toString
    s"""$path would reach $bytes bytes ($pct of the $hard-byte hard budget for target='$target'), so the write was refused; $label is untouched.
       |Budget is enforced on the WRITE side (injection is never truncated — an over-budget memory taxes every future session instead).
       |Consolidate first, then write. Largest sections:
       |${MemoryBudget.topSections(newContent)}
       |Trim stale/duplicate entries, or demote detail into ~/.nebflow/memory/<id>.md files.
       |If this write only shrinks the file, use the shrinking channel (MemoryEdit replace_section) — it is exempt from the hard cap. (${Code.Budget})""".stripMargin

  private def warnDetail(target: String, path: os.Path, bytes: Long, soft: Long, hard: Long): String =
    s"$WarnMarker target='$target' $path is now $bytes bytes (over the 80% soft line of $soft bytes; hard budget $hard) — " +
      "the write was allowed. Schedule a consolidation pass this turn or at the next lifecycle node."

end MemoryWriteGate
