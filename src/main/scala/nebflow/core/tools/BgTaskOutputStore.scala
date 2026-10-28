package nebflow.core.tools

import cats.effect.{IO, Ref}
import cats.syntax.all.*

import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * 后台任务输出查看批（2026-09-09 作者需求「点进后台任务能看具体输出」）：
 * 每个后台 Bash 任务的 stdout/stderr 逐行累积缓冲 + 终态留存区。
 *
 * 分层（一处一个职责）：
 * - [[BgTaskOutputBuffer]] 纯同步线程安全缓冲——运行中逐行 append（stdout/stderr
 *   读取线程并发调用，非 IO 上下文，故是普通类而非 Ref 编排），字节游标切片
 *   读取；尾窗 cap ~256KB（丢头部留末尾），totalBytes/totalLines 全量计数供
 *   截断标注。
 * - [[BgTaskOutputStore]] 进程级注册表（cats-effect Ref，IO 侧读写）——jobId →
 *   缓冲 + 状态（running / completed / failed / cancelled）+ 终态留存。
 *   **独立于 ShellSession 生命周期**（会话销毁 / evictCompleted /
 *   getBackgroundResult 均不回收本区），终态条目 LRU cap 最近 [[MaxRetained]]
 *   条（选 cap 淘汰而非 TTL：回看集中在任务结束后数分钟内，cap 实现无扫描
 *   fiber、防堆积上界明确 ~20×256KB）。
 * - [[BgTaskOutputDisk]] 输出查看补全批（作者令 2026-10-25 需求④）落盘层——
 *   本地任务输出行同步落 `<dataRoot>/bg-task-logs/`（cap 32MB/件）+ 终态 meta
 *   json；留存区 miss（LRU 淘汰 / 引擎重启）时 [[read]] 兜底查盘，旧任务照样
 *   可回看。 [[enablePersistence]] 显式开启（BashTool / RemoteExecutor 首个
 *   任务面调用；测试不开启 = 纯内存，行为与批前一致）。
 *
 * 读取接口：REST `GET /api/bg-tasks/:jobId/output?offset=N`（RestApiRoutes）。
 * 游标 = 字节偏移（行粒度：返回所有起始偏移 ≥ offset 的行拼接），选 REST GET
 * 轮询而非 WS 帧——无状态、鉴权直接复用 withAuth 中间件、避免 256KB 级载荷
 * 经 WsHub 广播给所有客户端；先例 `/api/agents/:name/model`。
 *
 * 写入路径：BashTool（run_in_background）open（拿 buffer 句柄造 sink）→
 * ShellSession.executeBackground 把 sink 挂上 JobHealth → runProcess 的
 * stdout/stderr 行回调 append → 终态回调（makeNotifyCallback / WS
 * cancelBackgroundJob / reclaimSession）finalizeTask。kind=remote
 * （RemoteExecutor）本来无缓冲——输出查看补全批起，远程后台任务完成时本侧
 * fiber 把 HTTP 拿到的全量输出 [[fillRemote]] 灌进留存区（open + append +
 * finalize 同一时序，先落账再发 WS completed 帧 ⇒ 前端详情卡首拍即有终态
 * 输出）；运行中面板经 `/bg-tasks/:id/remote-output` 按需拉对端日志尾窗。
 */
object BgTaskOutputStore:

  /** 尾窗容量：保留末尾 ~256KB（对齐任务规格），早期内容丢弃但计数保留。 */
  val CapBytes: Int = 256 * 1024

  /** 终态留存条数上限：最近 20 条（LRU 淘汰最旧终态条目，运行中条目不受限）。 */
  val MaxRetained: Int = 20

  /**
   * 任务来源元数据（输出查看补全批）：详情卡 subtitle / remote-output 路由 /
   * 落盘 meta 共用。local 任务 = BashTool startBg 时构造；remote 任务 =
   * RemoteExecutor executeRemoteBackground 时构造（outputPath 可能为空——命令
   * 无可解析的重定向 ⇒ 面板走「输出不可用 + 原因」降级）。
   */
  final case class Origin(
    kind: String, // "local" | "remote"
    deviceName: String = "",
    outputPath: String = "",
    description: String = "",
    startedAtMs: Long = 0L
  )

  /** 单任务输出的只读快照（REST 响应载荷）。 */
  final case class BgTaskOutput(
    taskId: String,
    status: String,
    totalBytes: Long,
    totalLines: Long,
    /** 头部内容已被尾窗 cap 丢弃（totalBytes > 窗口内字节数）。 */
    truncated: Boolean,
    /** 切片输出：起始偏移 ≥ 请求 offset 的行按行序拼接（'\n' 分隔）。 */
    output: String,
    /** 下一次轮询应携带的游标（= 当前 totalBytes）。 */
    nextOffset: Long,
    finishedAtMs: Option[Long] = None,
    exitCode: Option[Int] = None,
    errorHint: Option[String] = None,
    // ── 输出查看补全批（2026-10-25）：来源/落盘面（缺省缺席，前端 falsy 不渲染）──
    kind: Option[String] = None,
    deviceName: Option[String] = None,
    /** 本地 = 引擎侧落盘 log 绝对路径；remote = 对端日志路径（拉尾目标）。 */
    outputPath: Option[String] = None,
    startedAtMs: Option[Long] = None,
    description: Option[String] = None
  )

  private final case class Entry(
    buffer: BgTaskOutputBuffer,
    status: String,
    finishedAtMs: Option[Long],
    exitCode: Option[Int],
    errorHint: Option[String],
    /** 终态留存淘汰序（单调递增；finalize 时取号，越大越新）。 */
    seq: Long,
    /** 来源元数据（输出查看补全批；旧路径缺省 None）。 */
    origin: Option[Origin] = None
  )

  private val entries: Ref[IO, Map[String, Entry]] = Ref.unsafe(Map.empty)

  // ── 落盘面（输出查看补全批）：sink 线程直连的 writer 表 + 惰性开启的目录 ──
  @volatile private var persistDir: Option[os.Path] = None
  private val writers = new ConcurrentHashMap[String, BgTaskOutputDisk.LogWriter]()

  /**
   * 开启落盘（进程生命周期内幂等）。BashTool / RemoteExecutor 任务面在首个任务
   * 开启时调用（传 [[nebflow.shared.PathUtil.dataRoot]]）；测试不调用 = 纯内存。
   */
  def enablePersistence(dataRoot: os.Path): Unit =
    if persistDir.isEmpty then persistDir = Some(BgTaskOutputDisk.dir(dataRoot))

  /** 落盘目录（None = 未开启——spec 观测面）。 */
  def persistenceDir: Option[os.Path] = persistDir

  /**
   * 任务开启时建缓冲（BashTool 发起后台任务时调用）。幂等：已存在的 jobId
   * 返回原缓冲（防重复 open 撕出两个缓冲）。返回句柄供调用方造 append sink
   * （sink 捕获 buffer 直连，append 无需查表）；条目被留存淘汰后 sink 若因
   * 竞态迟到 append，写入的是孤儿缓冲——无副作用，GC 兜底。
   *
   * 落盘（输出查看补全批）：persistDir 已开启时为该任务建 LogWriter（行写惰性
   * 落盘），[[lineSink]] 消费。
   */
  def open(jobId: String, origin: Option[Origin] = None): IO[BgTaskOutputBuffer] =
    IO {
      if persistDir.isDefined && writers.get(jobId) == null then
        persistDir.foreach(dir =>
          writers.putIfAbsent(jobId, new BgTaskOutputDisk.LogWriter(BgTaskOutputDisk.logPath(dir, jobId)))
        )
      ()
    } *> entries.modify { m =>
      m.get(jobId) match
        case Some(e) => (m, e.buffer)
        case None =>
          val buf = new BgTaskOutputBuffer(CapBytes)
          (m + (jobId -> Entry(buf, "running", None, None, None, 0L, origin)), buf)
    }

  /**
   * sink 同线程复合口：内存缓冲 append + 落盘行写（persistDir 未开启 = 纯内存）。
   * BashTool 的 outputSink 单点消费——读取线程每次回调 O(1)（ConcurrentHashMap get）。
   */
  def lineSink(jobId: String, buffer: BgTaskOutputBuffer): String => Unit = line =>
    buffer.append(line)
    val w = writers.get(jobId)
    if w != null then w.writeLine(line)

  /**
   * 远程后台任务终态输出灌账（输出查看补全批）：open（幂等）→ 全量行 append
   * （单 IO 块直写缓冲 + writer——万行级输出不再逐行撕 IO）→ finalize，同一
   * 时序内完成——RemoteExecutor 在 unregister + WS completed 帧**之前**调用，
   * 前端详情卡首拍 /output 即命中终态留存。空输出也照样落账（终态空输出 =
   * 卡内「暂无输出」，好过 404「不可用」）。
   */
  def fillRemote(
    jobId: String,
    output: String,
    status: String,
    exitCode: Option[Int],
    errorHint: Option[String],
    origin: Origin
  ): IO[Unit] =
    open(jobId, Some(origin)).flatMap { buf =>
      IO {
        if output.nonEmpty then
          val w = writers.get(jobId)
          output.split("\n", -1).foreach { line =>
            buf.append(line)
            if w != null then w.writeLine(line)
          }
      }
    } *> finalizeTask(jobId, status, exitCode, errorHint)

  /**
   * 读取切片：offset = 字节游标（行粒度，见模块注释）。任务未知 → 落盘兜底
   * （输出查看补全批：留存被淘汰 / 引擎重启后，meta+log 仍可回看）；兜底也
   * 无 → None（REST 404：前端详情卡按「不可用」降级）。
   */
  def read(jobId: String, offset: Long): IO[Option[BgTaskOutput]] =
    entries.get.map(_.get(jobId)).flatMap {
      case Some(e) =>
        IO.pure {
          Some {
            val snap = e.buffer.snapshot(offset)
            BgTaskOutput(
              taskId = jobId,
              status = e.status,
              totalBytes = snap.totalBytes,
              totalLines = snap.totalLines,
              truncated = snap.truncated,
              output = snap.output,
              nextOffset = snap.totalBytes,
              finishedAtMs = e.finishedAtMs,
              exitCode = e.exitCode,
              errorHint = e.errorHint,
              kind = e.origin.map(_.kind),
              deviceName = e.origin.map(_.deviceName).filter(_.nonEmpty),
              outputPath = e.origin.flatMap(o => outputPathOf(jobId, o)),
              startedAtMs = e.origin.map(_.startedAtMs).filter(_ > 0L),
              description = e.origin.map(_.description).filter(_.nonEmpty)
            )
          }
        }
      case None => readFromDisk(jobId, offset)
    }

  /**
   * 读面 outputPath 单点：local = 引擎侧落盘 log 绝对路径（记录落盘位置）；
   * remote = 对端日志路径（origin 携带，拉尾目标）。均缺席 = None。
   */
  private def outputPathOf(jobId: String, o: Origin): Option[String] =
    if o.kind == "local" then persistDir.map(dir => BgTaskOutputDisk.logPath(dir, jobId).toString)
    else if o.outputPath.nonEmpty then Some(o.outputPath)
    else None

  /** 落盘兜底读（留存 miss 时）：meta 必需，log 缺席 = 空输出（终态无输出合法）。 */
  private def readFromDisk(jobId: String, offset: Long): IO[Option[BgTaskOutput]] =
    persistDir match
      case None => IO.pure(None)
      case Some(dir) =>
        IO.blocking {
          BgTaskOutputDisk.readMeta(dir, jobId).map { meta =>
            val slice = BgTaskOutputDisk.readSlice(dir, jobId, offset)
            BgTaskOutput(
              taskId = jobId,
              status = meta.status,
              totalBytes = meta.totalBytes,
              totalLines = meta.totalLines,
              truncated = meta.headDropped || meta.persistTruncated,
              output = slice.map(_._1).getOrElse(""),
              nextOffset = slice.map(_._2).getOrElse(meta.totalBytes),
              finishedAtMs = meta.finishedAtMs,
              exitCode = meta.exitCode,
              errorHint = meta.errorHint,
              kind = meta.kind,
              deviceName = meta.deviceName,
              outputPath = meta.outputPath,
              startedAtMs = meta.startedAtMs,
              description = meta.description
            )
          }
        }

  /**
   * 终态化（completed / failed / cancelled）：状态翻转 + 留存淘汰 + 落盘收口
   * （输出查看补全批：meta json 原子写 + writer flush/close + 目录 sweep）。
   * 幂等——已终态条目二次 finalize no-op（首个终态语义胜出，防 WS cancel 与
   * 完成回调竞态双写），落盘面同样只在首个终态写一次。运行中条目永不淘汰。
   */
  def finalizeTask(
    jobId: String,
    status: String,
    exitCode: Option[Int] = None,
    errorHint: Option[String] = None
  ): IO[Unit] =
    entries
      .modify { m =>
        m.get(jobId) match
          case Some(e) if e.status == "running" =>
            val seq = 1L + m.values.map(_.seq).foldLeft(0L)(math.max)
            val updated = e.copy(
              status = status,
              finishedAtMs = Some(System.currentTimeMillis()),
              exitCode = exitCode,
              errorHint = errorHint.map(_.take(200)),
              seq = seq
            )
            // 留存淘汰：本条入册后终态条目超 MaxRetained → 淘汰 seq 最小（最旧）。
            val next0 = m + (jobId -> updated)
            val terminal = next0.collect { case (id, ent) if ent.status != "running" => id -> ent.seq }
            val next =
              if terminal.size > MaxRetained then next0 - terminal.minBy(_._2)._1
              else next0
            (next, Some(updated))
          case _ => (m, None)
      }
      .flatMap {
        case None => IO.unit
        case Some(e) =>
          IO {
            val w = writers.remove(jobId)
            if w != null then w.flushClose()
            val snap = e.buffer.snapshot(0L)
            persistDir.foreach { dir =>
              BgTaskOutputDisk.writeMeta(
                dir,
                BgTaskOutputDisk.Meta(
                  jobId = jobId,
                  status = e.status,
                  exitCode = e.exitCode,
                  errorHint = e.errorHint,
                  startedAtMs = e.origin.map(_.startedAtMs).filter(_ > 0L),
                  finishedAtMs = e.finishedAtMs,
                  description = e.origin.map(_.description).filter(_.nonEmpty),
                  totalBytes = snap.totalBytes,
                  totalLines = snap.totalLines,
                  headDropped = snap.truncated,
                  persistTruncated = (w != null && w.persistTruncated),
                  kind = e.origin.map(_.kind),
                  deviceName = e.origin.map(_.deviceName).filter(_.nonEmpty),
                  outputPath = e.origin.map(_.outputPath).filter(_.nonEmpty)
                )
              )
              BgTaskOutputDisk.sweep(dir)
            }
          }
      }

  /**
   * #753 快照行数面（批量）：jobId → 当前 totalLines——activeBgTasks 快照
   * （BgTaskRegistry.activeTasksJson）单次调用携带全部在册任务的已产出行数，
   * 页面刷新后前端行数 chip 不再依赖心跳帧的迟到。无缓冲（remote / 已淘汰）
   * 的任务不入 Map（消费方 getOrElse 0）。
   */
  def lineCounts: IO[Map[String, Long]] =
    entries.get.map { m =>
      m.view.map { case (id, e) => id -> e.buffer.snapshot(0L).totalLines }.toMap
    }
end BgTaskOutputStore

/**
 * 见 [[BgTaskOutputStore]]。行粒度字节游标缓冲：每行记账 (起始字节偏移, 文本)，
 * cap 超限时从头丢行（保末尾窗），totalBytes/totalLines 永远全量。
 */
final class BgTaskOutputBuffer(capBytes: Int):

  private val lock: Object = new Object()
  private val lines: ArrayDeque[(Long, String)] = new ArrayDeque[(Long, String)]()
  private var endOffset: Long = 0L // 下一行的起始偏移 = 已写入总字节
  private var totalLines: Long = 0L
  private var droppedHead: Boolean = false

  /**
   * 追加一行（读取线程并发调用，锁内 O(1)；超限从头丢行，至少保一行——单行
   * 超限也保它，尾窗不空）。行字节记 UTF-8 长度 + 1（换行）——offset /
   * totalBytes / 切片三处同口径，自洽。
   */
  def append(line: String): Unit = lock.synchronized {
    lines.addLast((endOffset, line))
    endOffset += line.getBytes(StandardCharsets.UTF_8).length.toLong + 1L
    totalLines += 1L
    while lines.size() > 1 && endOffset - lines.peekFirst()._1 > capBytes.toLong do
      lines.removeFirst()
      droppedHead = true
  }

  /** 切片快照（独立 case class——项目 Scala 版本未开 named tuples）。 */
  final case class Snapshot(output: String, totalBytes: Long, totalLines: Long, truncated: Boolean)

  /**
   * 切片快照：返回窗口内起始偏移 ≥ offset 的行按行序拼接。
   * truncated = 头部曾被丢弃（缓冲级属性，与请求 offset 无关——客户端首拍
   * offset=0 即能据此标注「仅尾窗」）。
   */
  def snapshot(offset: Long): Snapshot =
    lock.synchronized {
      val sb = new StringBuilder
      val it = lines.iterator()
      while it.hasNext do
        val (startOff, text) = it.next()
        if startOff >= offset then
          if sb.nonEmpty then sb.append('\n')
          sb.append(text)
      Snapshot(sb.toString, endOffset, totalLines, droppedHead)
    }
end BgTaskOutputBuffer
