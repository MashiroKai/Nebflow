package nebflow.db

import org.slf4j.LoggerFactory

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{
  AccessDeniedException,
  AtomicMoveNotSupportedException,
  Files,
  Path,
  StandardCopyOption,
  StandardOpenOption
}
import java.util.UUID
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*

/**
 * 写面（设计 §3.5，D-4 采纳）：五步写序 + 追加腿 + 撕裂尾前置检 + orphan tmp 启动清扫。
 * 自包含 durable 原语（零 nebflow 内部依赖；供体 triple `archive.rs:994-1034` write_atomic
 * 五步序 + 本仓 `AtomicJson.writeSyncDurable/appendSyncDurable` 既有形收敛）。
 *
 * 五步序（每步都是崩溃安全判据的一部分，禁删步）：
 *  ① mkdir 父目录（幂等）
 *  ② tmp 名 = `<name>.tmp-<pid>-<uuid8>`（唯一性 = 进程 + 随机；cap 固定名互踩的反面，R29）
 *  ③ 写 tmp → `FileChannel.force(true)`（rename 前 fsync；tmp 字节先落盘）
 *  ④ `Files.move(ATOMIC_MOVE)`（Windows AccessDenied 退避重试，AtomicJson:37-54 形保留）
 *  ⑤ 父目录 `force(true)`（目录 fsync 使 rename 本身耐久；best-effort——非 POSIX 文件系统
 *     跳过 = 窄化保证非丢写，R32 保持）
 *  失败路径：清 tmp；**绝不回落「先删后写」**（供体纪律 archive.rs:1026-1030 同句）。
 *
 * 追加腿（JSONL 不走 rename）：per-record `force(true)`（PerRecord 档）+ 建档时目录 fsync
 * 一次；写失败必报错（禁吞，R28）。
 */
object WriteFace:

  private val log = LoggerFactory.getLogger("nebflow.db")

  /** D-4 fsync 档位（参数化；工作默认 = 事实面每条 fsync，渲染/ui 面可批量）。 */
  enum FsyncTier:
    /** 事实面（事件流 / llm 消息 / 待审单 / 调度）：每条 force(true) —— 「已 ack 即已落盘」。 */
    case PerRecord

    /** 渲染/ui 面：批量（rotate / close / 显式 flush 时 force）——ack 先于落盘，崩窗丢批可接受。 */
    case Batched

  object FsyncTier:

    /** D-4 工作默认的分派单点：`ui` 渲染面批量，其余（事实面）每条。 */
    def defaultFor(stream: String): FsyncTier = stream match
      case "ui" => Batched
      case _ => PerRecord

  // ── 测试专用崩溃注入（V1：rename 前后各相位边界的撕裂窗注入）────────────────
  /** 五步序的相位边界。hook 在该点抛 [[SimulatedCrash]] = 进程死在该相位（tmp 不清——真崩溃也清不了）。 */
  private[db] enum CrashPoint:
    case AfterTmpWrite, AfterTmpForce, AfterRename, AfterDirForce

  /** 模拟 kill：抛出点 = 崩溃点；与真异常的区别 = 失败路径不清 tmp（清扫归启动扫）。 */
  private[db] final class SimulatedCrash(val at: CrashPoint) extends RuntimeException(s"simulated crash at $at")

  private[db] type CrashHook = CrashPoint => Unit
  private[db] val NoHook: CrashHook = _ => ()

  val TmpInfix: String = ".tmp-"

  def isTmpName(name: String): Boolean = name.contains(TmpInfix)

  def tmpNameFor(fileName: String): String =
    s"$fileName$TmpInfix${ProcessHandle.current().pid()}-${UUID.randomUUID().toString.take(8)}"

  // ── 五步写（R20：db 唯一写面；R28：失败必报错；R29：tmp 名唯一；R32：零平台专用）──

  /** 五步写（字节面）。线程安全；调用方负责串行（[[nebflow.db.DbHandle]] 的写闸）。 */
  def writeFiveStep(target: Path, bytes: Array[Byte], hook: CrashHook = NoHook): Unit =
    val parent = Option(target.getParent).getOrElse(Path.of("."))
    Files.createDirectories(parent) // ① 幂等建目录
    val tmp = parent.resolve(tmpNameFor(target.getFileName.toString))
    try
      val ch = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
      try
        val buf = ByteBuffer.wrap(bytes)
        while buf.hasRemaining do ch.write(buf)
        hook(CrashPoint.AfterTmpWrite)
        ch.force(true) // ③ rename 前 fsync
        hook(CrashPoint.AfterTmpForce)
      finally ch.close()
      moveTo(target, tmp) // ④ ATOMIC_MOVE（AccessDenied 退避；无原子改名支持 ⇒ 降级为替换 move）
      hook(CrashPoint.AfterRename)
      forceDirBestEffort(parent) // ⑤ 目录 fsync（best-effort）
      hook(CrashPoint.AfterDirForce)
    catch
      case crash: SimulatedCrash => throw crash // 崩溃模拟：不清 tmp（启动清扫的管辖）
      case e: Throwable =>
        // 失败路径：目标文件不动（tmp+rename 的意义所在），清 tmp 残留 best-effort。
        try if Files.exists(tmp) then Files.delete(tmp)
        catch case _: Exception => ()
        throw e
    end try
  end writeFiveStep

  /** 五步写（文本面，UTF-8）。 */
  def writeFiveStepText(target: Path, content: String, hook: CrashHook = NoHook): Unit =
    writeFiveStep(target, content.getBytes("UTF-8"), hook)

  /** ④ 步单点：ATOMIC_MOVE + Windows 句柄竞态退避（`AtomicJson.writeSync` 37-54 形逐字保留）。 */
  private def moveTo(target: Path, tmp: Path): Unit =
    @tailrec def attempt(n: Int, backoffMs: Long): Unit =
      try Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      catch
        case _: AccessDeniedException if n > 0 =>
          Thread.sleep(backoffMs)
          attempt(n - 1, backoffMs * 3)
    try attempt(2, 50L)
    catch
      case _: AtomicMoveNotSupportedException =>
        // 无原子改名支持的文件系统：降级为替换 move（不丢写；AtomicJson.writeSyncDurable 先例）。
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
  end moveTo

  // ── 追加腿（R28：per-record fsync / 批量档 + 建档目录 fsync 一次）────────────

  /**
   * 追加一条完整记录行（单行，无内嵌换行）。PerRecord 档：行落盘后才返回（ack 即耐久，
   * V9 契约）；Batched 档：只 write 不 force（rotate/close/flush 时收口）。
   * 建档时目录 fsync 一次（`AtomicJson.appendSyncDurable:348-355` 形：force 文件本身
   * 不使目录项耐久）。
   */
  def appendDurable(target: Path, line: String, tier: FsyncTier): Unit =
    require(!line.contains('\n'), "append record must be a single line")
    val parent = Option(target.getParent).getOrElse(Path.of("."))
    Files.createDirectories(parent)
    val existed = Files.exists(target)
    val ch = FileChannel.open(
      target,
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE,
      StandardOpenOption.APPEND
    )
    try
      val buf = ByteBuffer.wrap((line + "\n").getBytes("UTF-8"))
      while buf.hasRemaining do ch.write(buf)
      if tier == FsyncTier.PerRecord then ch.force(true)
    finally ch.close()
    if !existed then forceDirBestEffort(target)
  end appendDurable

  /** Batched 档的收口 flush（rotate / close / 显式 flush 调用点）。 */
  def forceFile(target: Path): Unit =
    if Files.exists(target) then
      val ch = FileChannel.open(target, StandardOpenOption.WRITE)
      try ch.force(true)
      finally ch.close()

  /**
   * 目录 fsync（⑤ 步单点；rename 进目录后使目录项耐久）。best-effort：拒开目录读句柄的
   * 文件系统（部分非 POSIX / Windows）跳过——文件自身 force 已发生，降级 = 「窄化保证」
   * 非「丢写」（R32：`AtomicJson.forceParentDir:399-407` 形）。
   */
  def forceDirBestEffort(path: Path): Unit =
    val dir = if Files.isDirectory(path) then path else Option(path.getParent).getOrElse(Path.of("."))
    try
      val ch = FileChannel.open(dir, StandardOpenOption.READ)
      try ch.force(true)
      finally ch.close()
    catch case _: Exception => ()

  /**
   * 启动清扫 orphan tmp（V1 补课项：进程崩溃残留 tmp 的启动扫描——全仓现状 NOT FOUND，
   * 本库的正面补法）。清扫 = 删 `*.tmp-*` 残留（它们永不可能是有效数据：五步序里 tmp
   * 要么被 rename 成正名、要么随失败清理；残留即崩溃工件）。返回清除清单（计数上报）。
   */
  def sweepOrphanTmp(root: Path): List[Path] =
    if !Files.exists(root) then Nil
    else
      val out = List.newBuilder[Path]
      val walk = Files.walk(root)
      try
        walk.iterator().asScala.foreach { p =>
          if Files.isRegularFile(p) && isTmpName(p.getFileName.toString) then
            try
              Files.delete(p)
              out += p
            catch
              case e: Exception =>
                log.warn("orphan tmp sweep failed at {}: {}", p, e.getMessage)
        }
      finally walk.close()
      out.result()
  end sweepOrphanTmp

  // ── 追加前置检：撕裂尾修复 + 尾帧校验（V1「无半行入库」/ V3 的机械位）────────

  /** 追加前置检的三态结果。 */
  sealed trait TailState

  object TailState:
    /** 干净（含：文件缺 / 0 长——从未写或崩溃在建档与首写之间，皆无残可修）。 */
    case object Clean extends TailState

    /** 撕裂尾已修复：崩溃窗残段（从未 ack 的字节）已截断回最后一个换行；bytes = 截掉的字节数。 */
    final case class RepairedTornTail(bytes: Long) extends TailState

    /** 尾帧（最后一条完整行）校验失败 ⇒ 追加拒绝（读失败不参与下一次写，V3）——先隔离再写。 */
    final case class BadTail(reason: String) extends TailState

  /**
   * 追加前置检（「无半行入库」的机械保证）：
   *  ① 尾部无终止换行的残段 = kill -9 撕裂窗工件（从未 ack）⇒ **截断**回最后一个换行
   *     并计数（这是 WAL 恢复语义，不是删除数据——残段永不可能成为合法记录）；
   *  ② 最后一条完整帧经 `validateLastLine` 校验（解码 + 事件面校验和）⇒ 失败 =
   *     [[TailState.BadTail]]，追加拒绝。
   *  中间坏帧不在此面（追加不消费历史；全量坏帧由读面报 Corrupt → 隔离）。
   */
  def prepareAppend(target: Path, validateLastLine: String => Either[String, Unit]): TailState =
    if !Files.exists(target) then return TailState.Clean
    val size = Files.size(target)
    if size == 0L then return TailState.Clean
    val raf = new RandomAccessFile(target.toFile, "rw")
    try
      val lastNl = lastNewlineBefore(raf, size) // -1 = 整文件无换行（全是残段）
      val tornBytes = size - (lastNl + 1)
      if tornBytes > 0 then
        raf.setLength(lastNl + 1)
        log.warn("torn tail repaired at {}: {} unacked byte(s) truncated (crash-window artifact)", target, tornBytes)
      if lastNl <= 0 then
        // 无完整帧（整文件皆残段）或唯一帧为空行：无尾帧可校。
        return if tornBytes > 0 then TailState.RepairedTornTail(tornBytes) else TailState.Clean
      val frameStart = lastNewlineBefore(raf, lastNl) + 1
      val frame = readRange(raf, frameStart, lastNl)
      validateLastLine(frame) match
        case Right(()) =>
          if tornBytes > 0 then TailState.RepairedTornTail(tornBytes) else TailState.Clean
        case Left(why) =>
          TailState.BadTail(why)
    finally raf.close()
    end try
  end prepareAppend

  /** 反向扫描 `[0, limit)` 内最后一个 `\n` 的位置；无则 -1（分块回扫，避免整读大分片）。 */
  private def lastNewlineBefore(raf: RandomAccessFile, limit: Long): Long =
    val chunk = new Array[Byte](8192)
    var remaining = limit
    while remaining > 0 do
      val len = math.min(8192L, remaining).toInt
      val at = remaining - len
      raf.seek(at)
      raf.readFully(chunk, 0, len)
      var i = len - 1
      while i >= 0 do
        if chunk(i) == '\n'.toByte then return at + i
        i -= 1
      remaining = at
    -1L

  private def readRange(raf: RandomAccessFile, start: Long, endExclusive: Long): String =
    val len = (endExclusive - start).toInt
    val buf = new Array[Byte](len)
    raf.seek(start)
    raf.readFully(buf)
    new String(buf, "UTF-8")

end WriteFace
