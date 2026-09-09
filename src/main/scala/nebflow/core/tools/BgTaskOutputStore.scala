package nebflow.core.tools

import cats.effect.IO
import cats.effect.Ref
import cats.syntax.all.*
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque

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
 * （RemoteExecutor）不经 BashTool，无缓冲——前端详情卡对 remote 任务降级
 * 注明「暂不支持查看」，REST 404 同语义。
 */
object BgTaskOutputStore:

  /** 尾窗容量：保留末尾 ~256KB（对齐任务规格），早期内容丢弃但计数保留。 */
  val CapBytes: Int = 256 * 1024

  /** 终态留存条数上限：最近 20 条（LRU 淘汰最旧终态条目，运行中条目不受限）。 */
  val MaxRetained: Int = 20

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
    errorHint: Option[String] = None
  )

  private final case class Entry(
    buffer: BgTaskOutputBuffer,
    status: String,
    finishedAtMs: Option[Long],
    exitCode: Option[Int],
    errorHint: Option[String],
    /** 终态留存淘汰序（单调递增；finalize 时取号，越大越新）。 */
    seq: Long
  )

  private val entries: Ref[IO, Map[String, Entry]] = Ref.unsafe(Map.empty)

  /** 任务开启时建缓冲（BashTool 发起后台任务时调用）。幂等：已存在的 jobId
    * 返回原缓冲（防重复 open 撕出两个缓冲）。返回句柄供调用方造 append sink
    * （sink 捕获 buffer 直连，append 无需查表）；条目被留存淘汰后 sink 若因
    * 竞态迟到 append，写入的是孤儿缓冲——无副作用，GC 兜底。 */
  def open(jobId: String): IO[BgTaskOutputBuffer] =
    entries.modify { m =>
      m.get(jobId) match
        case Some(e) => (m, e.buffer)
        case None =>
          val buf = new BgTaskOutputBuffer(CapBytes)
          (m + (jobId -> Entry(buf, "running", None, None, None, 0L)), buf)
    }

  /** 读取切片：offset = 字节游标（行粒度，见模块注释）。任务未知 → None
    * （REST 404：前端详情卡按「不可用」降级）。 */
  def read(jobId: String, offset: Long): IO[Option[BgTaskOutput]] =
    entries.get.map { m =>
      m.get(jobId).map { e =>
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
          errorHint = e.errorHint
        )
      }
    }

  /** 终态化（completed / failed / cancelled）：状态翻转 + 留存淘汰。幂等——
    * 已终态条目二次 finalize no-op（首个终态语义胜出，防 WS cancel 与完成
    * 回调竞态双写）。运行中条目永不淘汰。 */
  def finalizeTask(
    jobId: String,
    status: String,
    exitCode: Option[Int] = None,
    errorHint: Option[String] = None
  ): IO[Unit] =
    entries.update { m =>
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
          if terminal.size > MaxRetained then next0 - terminal.minBy(_._2)._1
          else next0
        case _ => m
    }
end BgTaskOutputStore

/** 见 [[BgTaskOutputStore]]。行粒度字节游标缓冲：每行记账 (起始字节偏移, 文本)，
  * cap 超限时从头丢行（保末尾窗），totalBytes/totalLines 永远全量。 */
final class BgTaskOutputBuffer(capBytes: Int):

  private val lock: Object = new Object()
  private val lines: ArrayDeque[(Long, String)] = new ArrayDeque[(Long, String)]()
  private var endOffset: Long = 0L // 下一行的起始偏移 = 已写入总字节
  private var totalLines: Long = 0L
  private var droppedHead: Boolean = false

  /** 追加一行（读取线程并发调用，锁内 O(1)；超限从头丢行，至少保一行——单行
    * 超限也保它，尾窗不空）。行字节记 UTF-8 长度 + 1（换行）——offset /
    * totalBytes / 切片三处同口径，自洽。 */
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

  /** 切片快照：返回窗口内起始偏移 ≥ offset 的行按行序拼接。
    * truncated = 头部曾被丢弃（缓冲级属性，与请求 offset 无关——客户端首拍
    * offset=0 即能据此标注「仅尾窗」）。 */
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
