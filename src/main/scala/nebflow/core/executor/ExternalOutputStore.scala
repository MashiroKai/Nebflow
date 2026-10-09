package nebflow.core.executor

import cats.effect.IO
import cats.effect.Ref
import cats.syntax.all.*

import java.lang.Process

import scala.collection.immutable.Queue

import nebflow.core.tools.BgTaskOutputBuffer

/**
 * ExternalOutputStore —— 外部执行器进度缓冲 ＋ 活体注册表 ＋ mailbox
 * （external-executors 批 #1225，2026-10-06）。
 *
 * 分层（[[nebflow.core.tools.BgTaskOutputStore]] 同款三段式，REST 输出面仿制）：
 *   - 缓冲 = 直接复用 [[BgTaskOutputBuffer]]（尾窗 cap ~256KB，行粒度字节游标）；
 *   - 注册表 = cats-effect Ref 进程级表——id → 缓冲 ＋ 进程句柄 ＋ 状态 ＋
 *     CLI 会话 id ＋ mailbox 队列；**独立于进程生命周期**（终态留存，LRU cap
 *     最近条目，同 BgTaskOutputStore 口径）。
 *   - mailbox（增补任务书 3.5：live 外部实例的 `delegate:<id>` 来信**排队**）：
 *     外部 CLI 无法 mid-run 注入——运行中的来信入队，本 run 终态后由 runner
 *     以一条 resume 续聊消化（终态 resume 续聊，S15 同通道）。
 *
 * 读取接口：REST `GET /api/delegates/:id/output?offset=N`（PresenceRoutes，
 * 响应形态与 bg-tasks 输出面同构）。取消：`kill` = ProcessTree.killProcessTree。
 */
object ExternalOutputStore:

  /** 终态留存条数上限（同 BgTaskOutputStore.MaxRetained 口径）。 */
  val MaxRetained: Int = 20

  /** 单任务输出的只读快照（REST 响应载荷；与 bg-tasks 输出面同构）。 */
  final case class ExternalOutput(
    taskId: String,
    status: String,
    totalBytes: Long,
    totalLines: Long,
    truncated: Boolean,
    output: String,
    nextOffset: Long,
    startedAtMs: Long,
    finishedAtMs: Option[Long],
    exitCode: Option[Int],
    errorHint: Option[String],
    cliSessionId: Option[String],
    mailboxDepth: Int
  )

  private final case class Entry(
    buffer: BgTaskOutputBuffer,
    process: Option[Process],
    status: String, // running | completed | failed | blocked | cancelled
    startedAtMs: Long,
    finishedAtMs: Option[Long],
    exitCode: Option[Int],
    errorHint: Option[String],
    cliSessionId: Option[String],
    mailbox: Queue[String],
    seq: Long
  )

  private val entries: Ref[IO, Map[String, Entry]] = Ref.unsafe(Map.empty)

  /** 任务开启时建条目（幂等；重复 open 保原缓冲防双读面）。 */
  def open(id: String): IO[Unit] =
    entries.update { m =>
      m.get(id) match
        case Some(_) => m
        case None =>
          m + (id -> Entry(
            buffer = new BgTaskOutputBuffer(nebflow.core.tools.BgTaskOutputStore.CapBytes),
            process = None,
            status = "running",
            startedAtMs = System.currentTimeMillis(),
            finishedAtMs = None,
            exitCode = None,
            errorHint = None,
            cliSessionId = None,
            mailbox = Queue.empty,
            seq = 0L
          ))
    }

  /**
   * 同 delegate 地址的新一轮（resume 续起前调）：仅当旧条目已终态才重置为
   * 新 running 条目（fresh 缓冲、清退出码）；**cliSessionId 与 mailbox 保留**
   * ——续聊锚点与 live 期间来信跨轮有效。running 条目拒绝重置（防撕双缓冲）。
   */
  def resetForNewRun(id: String): IO[Boolean] =
    entries.modify { m =>
      m.get(id) match
        case Some(e) if e.status != "running" =>
          val reset = e.copy(
            buffer = new BgTaskOutputBuffer(nebflow.core.tools.BgTaskOutputStore.CapBytes),
            process = None,
            status = "running",
            startedAtMs = System.currentTimeMillis(),
            finishedAtMs = None,
            exitCode = None,
            errorHint = None,
            seq = 0L
          )
          (m + (id -> reset), true)
        case _ => (m, false)
    }

  /** attach 进程句柄（cancel 杀进程树的靶点）。 */
  def attachProcess(id: String, p: Process): IO[Unit] =
    entries.update(m => m.updatedWith(id)(_.map(_.copy(process = Some(p)))))

  /** 流面取到 CLI 会话 id 时回写（续聊锚点；首个胜出，不覆盖）。 */
  def setCliSession(id: String, cliSessionId: String): IO[Unit] =
    if cliSessionId.nonEmpty then
      entries.update(m =>
        m.updatedWith(id)(_.map(e => e.copy(cliSessionId = e.cliSessionId.orElse(Some(cliSessionId)))))
      )
    else IO.unit

  def cliSessionIdOf(id: String): IO[Option[String]] =
    entries.get.map(_.get(id).flatMap(_.cliSessionId))

  /** 缓冲 append（读取线程逐行；运行/终态均可补写——终态错误面留痕）。 */
  def append(id: String, line: String): IO[Unit] =
    entries.update(m =>
      m.updatedWith(id)(_.map(e =>
        e.buffer.append(line); e
      ))
    )

  /** 读取切片（同 bg-tasks 输出面形态）。任务未知 → None（REST 404）。 */
  def read(id: String, offset: Long): IO[Option[ExternalOutput]] =
    entries.get.map { m =>
      m.get(id).map { e =>
        val snap = e.buffer.snapshot(offset)
        ExternalOutput(
          taskId = id,
          status = e.status,
          totalBytes = snap.totalBytes,
          totalLines = snap.totalLines,
          truncated = snap.truncated,
          output = snap.output,
          nextOffset = snap.totalBytes,
          startedAtMs = e.startedAtMs,
          finishedAtMs = e.finishedAtMs,
          exitCode = e.exitCode,
          errorHint = e.errorHint,
          cliSessionId = e.cliSessionId,
          mailboxDepth = e.mailbox.size
        )
      }
    }

  /** 终态化（completed/failed/blocked/cancelled）。幂等——首个终态胜出。 */
  def finalizeTask(
    id: String,
    status: String,
    exitCode: Option[Int] = None,
    errorHint: Option[String] = None
  ): IO[Unit] =
    entries.update { m =>
      m.get(id) match
        case Some(e) if e.status == "running" =>
          val seq = 1L + m.values.map(_.seq).foldLeft(0L)(math.max)
          val updated = e.copy(
            status = status,
            finishedAtMs = Some(System.currentTimeMillis()),
            exitCode = exitCode,
            errorHint = errorHint.map(_.take(200)),
            seq = seq
          )
          val next0 = m + (id -> updated)
          val terminal = next0.collect { case (k, ent) if ent.status != "running" => k -> ent.seq }
          if terminal.size > MaxRetained then next0 - terminal.minBy(_._2)._1
          else next0
        case _ => m
    }

  /** live 判定（有条目且 running）。 */
  def isLive(id: String): IO[Boolean] =
    entries.get.map(_.get(id).exists(_.status == "running"))

  /** mailbox 入队（live 外部实例的来信排队；返回当前队深）。 */
  def enqueue(id: String, message: String): IO[Int] =
    entries.modify {
      case m if m.contains(id) =>
        val e = m(id)
        (m + (id -> e.copy(mailbox = e.mailbox :+ message)), e.mailbox.size + 1)
      case m => (m, 0)
    }

  def mailboxDepth(id: String): IO[Int] =
    entries.get.map(_.get(id).map(_.mailbox.size).getOrElse(0))

  /** 全量出队（run 终态后的 resume 续聊消费）。 */
  def drainMailbox(id: String): IO[Queue[String]] =
    entries.modify { m =>
      m.get(id) match
        case Some(e) if e.mailbox.nonEmpty => (m + (id -> e.copy(mailbox = Queue.empty)), e.mailbox)
        case _ => (m, Queue.empty[String])
    }

  /**
   * 取消：杀进程树（[[nebflow.core.util.ProcessTree]]）。返回是否存在**真实
   * 被杀的进程**（无条目 / 无进程句柄 / 已终态 → false——不假报成功，调用方
   * 回显式错误）。
   */
  def kill(id: String): IO[Boolean] =
    entries.get.flatMap { m =>
      m.get(id) match
        case Some(e) if e.status == "running" =>
          e.process match
            case Some(p) => nebflow.core.util.ProcessTree.killProcessTree(p).as(true)
            case None => IO.pure(false)
        case _ => IO.pure(false)
    }

  /** 进程句柄快照（终态 settle 后 reaper 兜底用）。 */
  def processOf(id: String): IO[Option[Process]] =
    entries.get.map(_.get(id).flatMap(_.process))

end ExternalOutputStore
