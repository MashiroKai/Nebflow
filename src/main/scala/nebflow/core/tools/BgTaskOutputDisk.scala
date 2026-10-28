package nebflow.core.tools

import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}

import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths, StandardOpenOption}
import scala.util.Using

/**
 * 本地后台任务输出落盘层（作者令 2026-10-25 修复批需求④「若 job 框架本地侧未持久化
 * 输出文件，补上——本地任务输出落盘 + 记录路径」）。
 *
 * 形态（`<dataRoot>/bg-task-logs/` 下，每任务一对）：
 *   - `<jobId>.log`——行追加（BgTaskOutputStore 的 sink 同线程写入），单文件
 *     [[PersistCapBytes]] cap（超限停写，meta.persistTruncated 记账）；
 *   - `<jobId>.meta.json`——终态一次写入（状态/exit/行数字节/origin/截断注记），
 *     temp+move 原子落盘。
 *
 * 读回 = BgTaskOutputStore 留存区（LRU 20）miss 后的兜底：meta + log 切片拼出与
 * 内存读同构的 `BgTaskOutput`（留存被淘汰 / 引擎重启后的旧任务照样可回看）。
 *
 * 纯文件面（零进程状态）⇒ spec 直测（BgTaskOutputDiskSpec，temp dir 注入）。容量
 * 纪律：[[sweep]] 按对保留最近 [[KeepPairs]] 对，finalize 时顺带执行。
 */
object BgTaskOutputDisk:

  /** 单 log 文件写上限（超限停写；展示面是尾窗，全量无界写没必要）。 */
  val PersistCapBytes: Long = 32L * 1024 * 1024

  /** sweep 保留的最近任务对数（log+meta 成对）。 */
  val KeepPairs: Int = 128

  /** meta json 载荷（终态一次写；字段全部可选宽容——旧文件缺字段可读）。 */
  final case class Meta(
    jobId: String,
    status: String,
    exitCode: Option[Int] = None,
    errorHint: Option[String] = None,
    startedAtMs: Option[Long] = None,
    finishedAtMs: Option[Long] = None,
    description: Option[String] = None,
    totalBytes: Long = 0L,
    totalLines: Long = 0L,
    headDropped: Boolean = false,
    persistTruncated: Boolean = false,
    kind: Option[String] = None,
    deviceName: Option[String] = None,
    outputPath: Option[String] = None
  )

  given Encoder[Meta] = deriveEncoder
  given Decoder[Meta] = deriveDecoder

  /** 目录单点：`<dataRoot>/bg-task-logs`。 */
  def dir(dataRoot: os.Path): os.Path = dataRoot / "bg-task-logs"

  def logPath(dir: os.Path, jobId: String): os.Path = dir / s"$jobId.log"
  def metaPath(dir: os.Path, jobId: String): os.Path = dir / s"$jobId.meta.json"

  /**
   * 追加写句柄（sink 线程直连，BufferedWriter 缓冲、finalize 时 flush+close——
   * 留存区在内存里兜着运行中读面，盘上文件只服务终态回看，无需逐行 flush）。
   * 非 IO 上下文可安全持有（BgTaskOutputBuffer sink 同约束）。
   */
  final class LogWriter(val path: os.Path, capBytes: Long = PersistCapBytes):
    private val lock: Object = new Object()
    private var writer: Option[BufferedWriter] = None
    private var written: Long = 0L
    private var truncated: Boolean = false

    /**
     * 追加一行（带换行）。cap 超限静默停写（truncated 记账，读面如实标注）。
     * 首写惰性建文件（含父目录——open 早于 dir 首次落盘）。
     */
    def writeLine(line: String): Unit = lock.synchronized {
      if !truncated then
        try
          val w = writer.getOrElse {
            Files.createDirectories(path.toNIO.getParent)
            val w = Files.newBufferedWriter(
              Paths.get(path.toString),
              StandardCharsets.UTF_8,
              StandardOpenOption.CREATE,
              StandardOpenOption.APPEND
            )
            writer = Some(w)
            w
          }
          w.write(line)
          w.write("\n")
          written += line.getBytes(StandardCharsets.UTF_8).length.toLong + 1L
          if written >= capBytes then
            truncated = true
            flushClose()
        catch case _: Throwable => truncated = true // 盘故障静默降级（内存缓冲不受影响）
    }

    def persistTruncated: Boolean = truncated
    def bytesWritten: Long = written

    def flushClose(): Unit = lock.synchronized {
      writer.foreach { w =>
        try
          w.flush(); w.close()
        catch case _: Throwable => ()
      }
      writer = None
    }
  end LogWriter

  /** meta 原子写入（temp + move；失败静默——回看兜底是尽力面，不反噬主流程）。 */
  def writeMeta(dir: os.Path, meta: Meta): Unit =
    try
      os.makeDir.all(dir)
      val tmp = dir / s".${meta.jobId}.meta.json.tmp"
      os.write.over(tmp, meta.asJson.noSpaces, createFolders = true)
      os.move(tmp, metaPath(dir, meta.jobId), replaceExisting = true)
    catch case _: Throwable => ()

  /** meta 读取（缺文件 / 损坏 = None）。 */
  def readMeta(dir: os.Path, jobId: String): Option[Meta] =
    try
      val p = metaPath(dir, jobId)
      if os.exists(p) then io.circe.parser.decode[Meta](os.read(p)).toOption else None
    catch case _: Throwable => None

  /**
   * log 切片读（与内存读同协议：offset = 字节游标，行粒度——从第一个起始位置
   * ≥ offset 的换行之后切片，最多 maxWindow 字节）。返回 (文本, nextOffset,
   * 文件是否已到尾)。
   */
  def readSlice(
    dir: os.Path,
    jobId: String,
    offset: Long,
    maxWindow: Long = BgTaskOutputStore.CapBytes.toLong
  ): Option[(String, Long, Boolean)] =
    try
      val p = logPath(dir, jobId)
      if !os.exists(p) then None
      else
        val size = os.size(p)
        if offset >= size then Some(("", offset, true))
        else
          val start =
            // 行对齐：offset 落在行中间时跳到下一个换行之后
            val is = Files.newInputStream(Paths.get(p.toString))
            try
              var k = offset
              val buf = new Array[Byte](1)
              if offset > 0 then
                is.skip(offset)
                // 向前扫一个换行（首字符即换行 ⇒ 切片起点 offset+1）
                while k < size && is.read(buf) == 1 && buf(0) != '\n' do k += 1
                k += 1 // 换行之后
              math.min(k, size)
            finally is.close()
          if start >= size then Some(("", size, true))
          else
            val len = math.min(maxWindow, size - start).toInt
            val bytes = new Array[Byte](len)
            val is = Files.newInputStream(Paths.get(p.toString))
            try
              is.skip(start)
              var read = 0
              while read < len do
                val r = is.read(bytes, read, len - read)
                if r < 0 then
                  read = len; ()
                else read += r
            finally is.close()
            val text = new String(bytes, 0, len, StandardCharsets.UTF_8)
            Some((text, start + len, start + len >= size))
          end if
        end if
      end if
    catch case _: Throwable => None

  /**
   * 容量纪律：按对保留最近 KeepPairs 对（log+meta 成对删）。finalize 时顺带执行；
   * 失败静默。按 meta mtime 判新旧（log 与 meta 同刻落）。
   */
  def sweep(dir: os.Path, keep: Int = KeepPairs): Unit =
    try
      if os.exists(dir) then
        val metas = os.list(dir).filter(_.last.endsWith(".meta.json")).sortBy(p => -os.mtime(p))
        metas.drop(keep).foreach { m =>
          val jobId = m.last.stripSuffix(".meta.json")
          try os.remove.all(m)
          catch case _: Throwable => ()
          try os.remove.all(logPath(dir, jobId))
          catch case _: Throwable => ()
        }
    catch case _: Throwable => ()

  /** 目录里现存任务对数（观测面 / 测试断言）。 */
  def pairCount(dir: os.Path): Int =
    try if os.exists(dir) then os.list(dir).count(_.last.endsWith(".meta.json")) else 0
    catch case _: Throwable => 0

end BgTaskOutputDisk
