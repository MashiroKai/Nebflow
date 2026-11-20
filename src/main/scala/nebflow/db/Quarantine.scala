package nebflow.db

import org.slf4j.LoggerFactory

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * 损坏隔离（设计 §3.10）：`.corrupt-<ts>` 就地改名，全字节保留，**绝不删除**。
 *
 * 收编本仓 3 处隔离先例（SessionStore `.corrupted.<ts>`、TaskLedgerStore `corrupt-<millis>`、
 * UsageRecordStore `.corrupt-<ts>`）为一个全库共用 helper；命名取 TaskLedgerStore 形
 * （`.corrupt-<epoch-ms>`）。同目录改名后不被消费的可行性已被 TaskLedgerStore 生产验证
 * （消费面只认正名）。R31 的正面位：cap 线 `.corrupt-<ts>` NOT FOUND 的反面。
 *
 * 隔离档属于遗忘豁免域③（失败取证）：永不进入两刀判定（[[nebflow.db.Retention]]）。
 */
object Quarantine:

  private val log = LoggerFactory.getLogger("nebflow.db")

  val Suffix: String = ".corrupt-"

  def quarantinedName(fileName: String, nowMs: Long): String = s"$fileName$Suffix$nowMs"

  def isQuarantinedName(name: String): Boolean = name.contains(Suffix)

  /**
   * 就地改名隔离：`<name>` → `<name>.corrupt-<ts>`（同目录，rename 原子；同 ts 已存在
   * ⇒ 追加 `-<n>` 序号）。返回隔离副本路径（原值全字节可查）；文件缺 ⇒ None。
   * 计数/WARN 由调用方（[[nebflow.db.DbHandle]] 的 stats 面）统一上报。
   */
  def quarantine(file: Path, nowMs: Long): Option[Path] =
    if !Files.exists(file) then None
    else
      val dir = Option(file.getParent).getOrElse(Path.of("."))
      val base = quarantinedName(file.getFileName.toString, nowMs)
      var target = dir.resolve(base)
      var n = 1
      while Files.exists(target) do
        target = dir.resolve(s"$base-$n")
        n += 1
      Files.move(file, target)
      log.warn("QUARANTINED: {} -> {} (bytes preserved, never deleted)", file, target)
      Some(target)

  /** 目录下的全部隔离档（豁免域③清单；遗忘刀永不触碰）。 */
  def listQuarantined(dir: Path): List[Path] =
    if !Files.exists(dir) then Nil
    else
      val s = Files.list(dir)
      try
        s.iterator()
          .asScala
          .filter(p => Files.isRegularFile(p) && isQuarantinedName(p.getFileName.toString))
          .toList
      finally s.close()

end Quarantine
