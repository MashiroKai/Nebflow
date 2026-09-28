package nebflow.agent

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 生命周期记忆整理信号（memory-management-plan §2.2-2 / §6.2-2.5，2026-09-05 批次二机制五）。
 *
 * 两个一次性触发源，由 ContextRefresher.buildMemoryBlock 在 Nebula 记忆注入时消费：
 *   - restarted：进程启动即置位 —— 宿主重启后首会话注入「整理提醒」（重启使重启包/
 *     待重启清单类 T2 批量闭环，重启后首会话是天然审计时点）。
 *   - compacted：Nebula 压缩前置 hook（NebulaMemoryHook，CompactionProfile.Root
 *     唯一 hook）完成时置位 —— 压缩完成后注入「T2/T3 清扫提示」。只加提示不改
 *     hook 清扫逻辑（先提示后机制，plan §2.2-2 原文）。
 *
 * 一次性语义：takePending 原子取走并清零 —— 每个事件只影响紧随其后的第一个
 * Nebula lifecycle 注入，不重复骚扰。纯进程内状态（不落盘）：重启本身即 restarted
 * 的重新置位，无持久化需求。
 */
object MemoryHygieneSignal:
  private val restarted = new AtomicBoolean(true) // 进程启动 = 重启事件待消费
  private val compacted = new AtomicBoolean(false)

  /** NebulaMemoryHook 压缩抽取完成后调用（fire-and-forget，一行置位）。 */
  def markCompacted(): Unit = compacted.set(true)

  /** (restartPending, compactPending) —— 取走即清零。 */
  def takePending(): (Boolean, Boolean) =
    (restarted.getAndSet(false), compacted.getAndSet(false))

  /** 观测不消费（运维/诊断用）。 */
  def peek(): (Boolean, Boolean) = (restarted.get(), compacted.get())

  /** 测试钩子（spec 直接构造信号状态；生产代码禁用）。 */
  private[agent] def resetForTest(restartedV: Boolean, compactedV: Boolean): Unit =
    restarted.set(restartedV)
    compacted.set(compactedV)

end MemoryHygieneSignal
