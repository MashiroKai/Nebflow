package nebflow.db

import io.circe.Json
import io.circe.parser.parse
import org.slf4j.LoggerFactory

import java.nio.channels.{FileChannel, FileLock, OverlappingFileLockException}
import java.nio.file.{Files, Path, StandardOpenOption}
import java.util.UUID

/**
 * 跨进程实例锁 + 世代感知（设计 §3.9 第 3 层，D-7 采纳）。
 *
 * - `INSTANCE.lock`：`FileChannel.tryLock` 独占；拿不到 ⇒ 拒启（第二实例/他进程持锁）。
 *   `AtomicJson:19-21` 自证「撕裂安全 ≠ 互斥」留下的换代双写窗在此收口。
 * - **世代感知**〔提案〕：锁档内容带 `generation`（= 盘上旧世代 + 1）。热重启换窗内
 *   旧实例锁失效 ⇒ 其写面翻只读（[[DbError.ReadOnly]]）；新实例持锁后开写——
 *   「旧实例只读、新实例持锁后开写」。
 * - 锁档内容经**持锁通道**直写 + force：rename 面在被锁文件上不可用（Windows 删不掉
 *   锁着的文件）；锁档是互斥的载体不是数据面，通道写即够（世代号仅 advisory）。
 */
final class InstanceLock private (
  val file: Path,
  private val channel: FileChannel,
  private val lock: FileLock,
  val generation: Long,
  val bootId: String,
  val pid: Long,
  val acquiredAt: Long
):

  /** 世代感知写闸：锁还在手上吗（写路径每次断言；失效 ⇒ 只读翻置）。 */
  def stillHeld: Boolean = channel.isOpen && lock.isValid

  /** 放手（优雅关闭 / 模拟换代失锁）：关通道即释放 OS 锁；世代号留在盘上给下一任。 */
  def release(): Unit =
    var failure: Option[Throwable] = None
    try if lock.isValid then lock.release()
    catch case scala.util.control.NonFatal(e) => failure = Some(e)
    try channel.close()
    catch case scala.util.control.NonFatal(e) =>
      failure match
        case Some(first) => first.addSuppressed(e)
        case None => failure = Some(e)
    failure.foreach(throw _)

end InstanceLock

object InstanceLock:

  private val log = LoggerFactory.getLogger("nebflow.db")

  val FileName: String = "INSTANCE.lock"

  def pathUnder(dbRoot: Path): Path = dbRoot.resolve(FileName)

  /**
   * 认领（设计 §3.11 步 1）。失败 = `Left(InstanceLockHeld)`（拒启，调用方不重试自旋——
   * 换窗等待语义由上层编排）。旧档不可读 ⇒ 世代从 1 重新计 + WARN（世代只用于换窗感知，
   * 不承载数据，重计无害）。
   */
  def claim(dbRoot: Path): Either[DbError.InstanceLockHeld, InstanceLock] =
    try
      Files.createDirectories(dbRoot)
      val f = pathUnder(dbRoot)
      val priorGen = readGeneration(f)
      val ch = FileChannel.open(f, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
      val fl: FileLock =
        try ch.tryLock()
        catch
          // 本 JVM 已持（同进程双开路径）或他进程持锁（Windows 实测形）⇒ 统一折算「拿不到」。
          case _: OverlappingFileLockException => null
          case _: java.io.IOException => null
      if fl == null then
        ch.close()
        Left(DbError.InstanceLockHeld(
          s"instance lock held at $f — second instance refused (design §3.9 layer 3)"))
      else
        val gen = priorGen.getOrElse(0L) + 1L
        val bootId = UUID.randomUUID().toString
        val now = System.currentTimeMillis()
        val content = Json.obj(
          "version" -> Json.fromInt(1),
          "generation" -> Json.fromLong(gen),
          "bootId" -> Json.fromString(bootId),
          "pid" -> Json.fromLong(ProcessHandle.current().pid()),
          "acquiredAt" -> Json.fromLong(now)
        ).noSpaces + "\n"
        ch.position(0)
        ch.truncate(0)
        val buf = java.nio.ByteBuffer.wrap(content.getBytes("UTF-8"))
        while buf.hasRemaining do ch.write(buf)
        ch.force(true)
        log.info("instance lock claimed: generation={} bootId={} pid={}", gen, bootId,
          ProcessHandle.current().pid())
        Right(new InstanceLock(f, ch, fl, gen, bootId, ProcessHandle.current().pid(), now))
    catch
      case e: Exception =>
        Left(DbError.InstanceLockHeld(s"instance lock claim failed: ${e.getMessage}"))

  private def readGeneration(f: Path): Option[Long] =
    if !Files.exists(f) then None
    else
      try
        val raw = Files.readString(f)
        parse(raw).flatMap(_.hcursor.downField("generation").as[Long]).toOption match
          case some @ Some(_) => some
          case None =>
            log.warn("prior lock file unreadable at {} — generation restarts from 1", f)
            None
      catch
        case _: Exception =>
          log.warn("prior lock file unreadable at {} — generation restarts from 1", f)
          None

end InstanceLock
