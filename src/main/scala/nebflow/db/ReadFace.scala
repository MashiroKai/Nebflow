package nebflow.db

import java.nio.file.Path

/**
 * 三态读面（设计 §3.7，D-5 采纳）：Absent / Corrupt / Present —— 「坏 ≠ 不存在」。
 *
 * 这是全仓 9 处静默空视图（设计 v1 §1.3：`getOrElse`-空值族把 Corrupt 折叠成 Nil，
 * 下一次写以空为基线整档覆盖）的根治位。禁令（V7 机械判据）：db 管辖树的 load 路径
 * 不得出现「坏档折空表」的 getOrElse 形——Corrupt 永不折叠。
 *
 * Corrupt 的标准动作 = [[Quarantine]] 隔离 + WARN + 由调用方按可再生性选处置
 * （三标签：①显式拒绝〔不可再生：待审单/计数器〕②隔离留档+按缺席处理〔取证面〕
 * ③记账降级〔可重建派生面：分片索引〕）。
 */
sealed trait ReadResult[+A]

object ReadResult:

  /** 文件缺 = 正常（新流/新档），不是错误，不触发隔离。 */
  final case class Absent() extends ReadResult[Nothing]

  /**
   * 存在但不可用。`kind` 携带机械分类（unreadable / empty / corrupt /
   * bad-frame:<reason> 等，供日志与计数）；`evidence` = 被判损坏的文件路径
   * （隔离动作的对象；全字节保留于 `.corrupt-<ts>` 副本，原值可查）。
   */
  final case class Corrupt(kind: String, evidence: Path) extends ReadResult[Nothing]

  /** 成功读得值。 */
  final case class Present[A](value: A) extends ReadResult[A]

/**
 * JSONL 流读结果的载荷面：完整记录 + 撕裂尾计数。
 *
 * 撕裂尾（尾部无终止换行的残段）是崩溃窗工件：弃之并**显式计数**——与「坏==无」
 * 的区别恰在这里：弃尾是报告出来的，不是静默归零（对齐 [[nebflow.db]] 头注与
 * AtomicJson journal 残尾纪律；中间坏帧则升级为 [[ReadResult.Corrupt]]）。
 */
final case class StreamRead[A](records: List[A], tornTailDropped: Int):
  def mapRecords[B](f: A => B): StreamRead[B] = copy(records = records.map(f))
end StreamRead
