package nebflow.agent

import java.util.concurrent.atomic.AtomicReference

/**
 * 记忆轨一次性待整理信号（记忆改造批 2026-09-12，IMPL-3 / spec §5 R4 谓词的第三支
 * 「未消费信号」）——形态对齐 [[MemoryHygieneSignal]]（一次性、进程内、不落盘）。
 *
 * **为什么需要它**：降级路径（失败 / 超时）会给本轮待办逐条写 `outcome
 * (rejected|timeout)`——按队列折叠谓词（有 note 无 outcome）它们**不再是 pending**
 * （这是对的：结局已被如实记录，不是静默丢）。但「未落地」这件事必须能在**下一次
 * 压缩**被重新提起，否则失败一次就永久搁置。本信号就是那根重试引线：降级时置位、
 * 记忆轨起跑线消费（`take`）。
 *
 * 与队列/内存的边界：本对象**不持队列状态**（pending 计数由 `MemoryQueue` 现算）；
 * 只持一个「有未消费工作」的布尔 + 一句原因（供日志与注入诊断）。
 */
object MemoryTrackSignal:

  private val reason = new AtomicReference[Option[String]](None)

  /** 置位（降级/审计侧调用）。重复置位覆盖原因——后到者的原因更新。 */
  def mark(why: String): Unit = reason.set(Some(why))

  /** 取走并清零（记忆轨起跑线；一次性语义：只影响紧随其后的一次 run）。 */
  def take(): Option[String] = reason.getAndSet(None)

  /** 观测不消费（运维/诊断）。 */
  def peek(): Option[String] = reason.get()

  /** 测试钩子（生产代码禁用；`private[nebflow]` = spec 隔离面）。 */
  private[nebflow] def resetForTest(): Unit = reason.set(None)
