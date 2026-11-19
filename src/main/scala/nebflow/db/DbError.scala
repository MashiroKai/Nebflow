package nebflow.db

import java.nio.file.Path

/**
 * db 错误面（结构化错误码；IO 的 error channel 用 —— 调用方按码处置，不解析文案）。
 * 与三态读的分工：ReadResult.Corrupt 是**读面的可表示态**；这里是被拒动作的**错误**。
 */
object DbError:

  /** 实例锁被持（第二实例拒启；设计 §3.9 第 3 层 / §3.11 步 1）。 */
  final class InstanceLockHeld(message: String) extends RuntimeException(message)

  /** 拒启（计数器坏/缺但树有数据、或其他不可自愈的开工前置失败；设计 §3.6/§3.11 步 2）。 */
  final class RefuseStart(message: String) extends RuntimeException(message)

  /**
   * 写拒绝：流/档损坏在场（读失败不得参与下一次写，V3）——先隔离
   * （[[nebflow.db.Quarantine]]）再写。`evidence` = 损坏文件。
   */
  final class StreamCorrupt(message: String, val evidence: Path) extends RuntimeException(message)

  /** 只读态：实例锁已失（hot-restart 换代窗内旧实例禁写；设计 §3.9 世代感知）。 */
  final class ReadOnly(message: String) extends RuntimeException(message)

end DbError
