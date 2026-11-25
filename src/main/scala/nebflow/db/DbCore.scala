package nebflow.db

import cats.effect.IO
import cats.effect.kernel.Deferred
import io.circe.syntax.*
import io.circe.{Json, parser}
import nebflow.db.format.{EventEnvelope, GuardDocs, PendingRecord, ScheduleRecord, SessionEnvelope}
import org.slf4j.LoggerFactory

import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{ConcurrentHashMap, Semaphore}

import scala.jdk.CollectionConverters.*

/**
 * db 门面（设计 §3.1/§3.2，D-2/D-3 采纳）：**进程内嵌入式库**——一个 Scala 包 + 数据根
 * 下文件族；对外 API 全走 trait（[[DbWriteFace]]/[[DbReadFace]]/[[DbRecovery]]）+ 数据根
 * 路径注入，日后移进独立进程时实现层换传输、调用方零改动（A-3 对冲）。
 *
 * 三层锁（D-7，[[Locking]] 头注）：流级单写者（per-stream 信号量）→ 进程内全局写序
 * （[[DbHandle]] 单写闸）→ 跨进程实例锁 + 世代感知。
 *
 * 本轮**零接线**：不触碰任何现有 store；消费方向单向（core/agent/scheduler → db，
 * 接线期建立）。
 */
object Db:

  /** 数据根下的 db 树目录名（设计 §3.2(b)）。 */
  val TreeName: String = "db"

  /** avoid 档固定 source 口径（与 writeAvoidGuard 单档路径对齐；宿主写穿共用）。 */
  val AvoidGuardSource: String = "provider-avoid"

  def open(cfg: DbConfig): IO[DbHandle] = IO.blocking {
    val dbRoot = cfg.dataRoot.resolve(TreeName)
    Files.createDirectories(dbRoot)
    // §3.11 步 1：实例锁认领（世代检查）——拿不到 ⇒ 拒启。
    val lock = InstanceLock.claim(dbRoot) match
      case Right(l) => l
      case Left(e) => throw e
    try
      // V1 补课：启动清扫 orphan tmp（进程崩溃残留 *.tmp-*）。
      val swept = WriteFace.sweepOrphanTmp(dbRoot)
      if swept.nonEmpty then DbCore.log.warn("orphan tmp swept at open: {} file(s) — {}", swept.size, swept)
      // §3.11 步 2：计数器（缺+树有数据 / 坏 ⇒ 拒启——不得从 0 重号）。
      val counter = SeqCounter.open(
        dbRoot.resolve("seq").resolve("counter.json"),
        treeHasDataUnder(dbRoot),
        cfg.seqInterval
      ) match
        case Right(c) => c
        case Left(e) => throw e
      val handle = new DbHandle(cfg, dbRoot, lock, counter, swept.size)
      // §3.11 步 4：events.idx 校验/重建（派生面③：坏 ⇒ 删了重建）。
      handle.checkEventIndexAtOpen()
      // §3.11 步 2 附：计数器 × 各流尾 seq 交叉校验（以大者为准 + WARN）。
      handle.reconcileCounterAtOpen()
      // 步 3/5/6（pending 重放、guard 恢复、会话 lazy 水合）为显式恢复腿/惰性读——不预读。
      handle
    catch
      case e: Throwable =>
        lock.release()
        throw e
    end try
  }

  /** db 树是否已承载数据（排除锁档与 seq/ 自身——「只有锁没有计数器」= 全新根）。 */
  private[db] def treeHasDataUnder(dbRoot: Path): Boolean =
    val skip = List(dbRoot.resolve(InstanceLock.FileName), dbRoot.resolve("seq"))
    if !Files.exists(dbRoot) then false
    else
      val walk = Files.walk(dbRoot)
      try
        walk.iterator().asScala.exists { p =>
          Files.isRegularFile(p) && !skip.exists(k => p == k || p.startsWith(k))
        }
      finally walk.close()

end Db

private[db] object DbCore:
  val log = LoggerFactory.getLogger("nebflow.db")

/**
 * 配置（参数面；缺省 = 设计工作默认：分片 64 MiB〔提案〕、seq 区间 64〔D-6〕、
 * 事实面每条 fsync〔D-4〕、ui 渲染面批量〔D-4〕）。
 *
 * @param dataRoot      数据根（db 树 = `<dataRoot>/db/`，与现状载体平级）
 * @param shardMaxBytes 分片字节上限（单行超限例外放行独占新片）
 * @param seqInterval   计数器区间分配步长（一次领 N 个号；重启至多弃用未用号——只跳号不重号）
 * @param uiFsyncTier   ui 渲染面的 fsync 档（默认 Batched；事实面恒 PerRecord，不设参数）
 */
final case class DbConfig(
  dataRoot: Path,
  shardMaxBytes: Long = 64L * 1024 * 1024,
  seqInterval: Long = 64L,
  uiFsyncTier: WriteFace.FsyncTier = WriteFace.FsyncTier.Batched
)

/** 待审单文件面（permission / ask 两流，design §3.2(b) `pending/`）。 */
enum PendingStream(val fileName: String):
  case Permission extends PendingStream("permission")
  case Ask extends PendingStream("ask")

/** 会话流的惰性水合索引（§3.11 步 6：boot 只载索引，帧面扫描不解码）。 */
final case class SessionIndex(
  sessionId: String,
  stream: String,
  shards: List[SessionIndex.ShardIndex],
  tornTails: Int
):
  def totalLines: Long = shards.map(_.lines).sum
  def totalBytes: Long = shards.map(_.bytes).sum

object SessionIndex:
  final case class ShardIndex(no: Int, lines: Long, bytes: Long)

/** 全树核验报告（V4/V6 支持面；`ok=false` 的每一项都在清单里有落点）。 */
final case class VerifyReport(
  counterNext: Long,
  maxSeqSeen: Long,
  seqCount: Long,
  dupSeq: List[Long],
  nonMonotonic: List[Path],
  orphanTmp: Int,
  corrupt: List[String],
  idxFresh: Boolean
):

  def ok: Boolean =
    dupSeq.isEmpty && nonMonotonic.isEmpty && orphanTmp == 0 && corrupt.isEmpty &&
      idxFresh && counterNext > maxSeqSeen

/** 运行计数面（Corrupt 必有计数——V2「WARN + 计数」的计数半边）。 */
final case class DbStats(
  tornTailsRepaired: Long,
  tornBytesDropped: Long,
  quarantined: Long,
  tmpSwept: Long,
  appends: Long,
  seqHighWatermark: Long
)

/** 写面 trait（D-3 稳定面之一）。 */
trait DbWriteFace:
  /** 事件流追加（事实面：PerRecord fsync）。返回全局 seq。 */
  def appendEvent(event: Json, dedupKey: Option[String]): IO[Long]

  /** 会话消息追加（stream ∈ llm|ui；ui = Batched 档、llm = PerRecord 档）。 */
  def appendMessage(sessionId: String, stream: String, payload: Json): IO[Long]

  /** 压缩点行（进 llm 流；payload = beforeRef/afterRef/tokens，快照本体另由 writeSnapshot 落）。 */
  def appendCompactPoint(sessionId: String, beforeRef: String, afterRef: String, tokens: Long): IO[Long]

  /** 压缩前后快照整档写（五步写；返回分配的快照号 n → `snapshots/<n>.json`）。 */
  def writeSnapshot(sessionId: String, content: String): IO[Int]

  /** 待审单开单（payload 只入摘要；返回 seq）。 */
  def openPending(card: PendingRecord.PendingCard): IO[Long]

  /** 待审单闭单（追加决议事件行，不改写原行；目标不在/已闭 ⇒ false，幂等）。 */
  def closePending(
    stream: PendingStream,
    requestId: String,
    closedBy: String,
    closeReason: String,
    now: Long
  ): IO[Boolean]

  /** 调度任务行追加（taskDef 须携带 id；复活 = 重新 append 同 id 完整行——天然 upsert）。 */
  def appendScheduleTask(sessionId: String, taskDef: Json, now: Long): IO[Long]

  /** 调度任务逻辑删除（追加 tombstone 行；无该活任务 ⇒ false）。 */
  def tombstoneScheduleTask(sessionId: String, taskId: String, now: Long): IO[Boolean]

  /** notify-guard 小档写（锁内五步整档；坏档在场 ⇒ 写拒绝〔V3〕——先隔离）。 */
  def writeNotifyGuard(doc: GuardDocs.NotifyGuardDoc): IO[Unit]

  /** provider-avoid 小档写（同上）。 */
  def writeAvoidGuard(doc: GuardDocs.AvoidGuardDoc): IO[Unit]

  /** Batched 档收口 flush（rotate/close 之外的手动落盘点）。 */
  def flush(): IO[Unit]

end DbWriteFace

/** 读面 trait（D-3 稳定面之二；全部三态）。 */
trait DbReadFace:
  /** 事件流读（logical=true 过滤已 tombstone 单位；撕裂尾显式计数；中间坏帧 ⇒ Corrupt）。 */
  def readEvents(fromSeq: Long, limit: Int, logical: Boolean): IO[ReadResult[StreamRead[EventEnvelope]]]

  /** 会话流读（坏帧 ⇒ Corrupt；撕裂尾计数）。 */
  def readSession(sessionId: String, stream: String): IO[ReadResult[StreamRead[SessionEnvelope]]]

  /** 快照整档读（三态；Present = 原文文本）。 */
  def readSnapshot(sessionId: String, n: Int): IO[ReadResult[String]]

  /** 待审单读（两文件面之一；不可再生面——Corrupt ⇒ 显式拒绝处置）。 */
  def readPending(stream: PendingStream): IO[ReadResult[StreamRead[PendingRecord]]]

  /** 调度原始行读。 */
  def readSchedule(sessionId: String): IO[ReadResult[StreamRead[ScheduleRecord]]]

  /** 调度逻辑视图（同 id 最后一行定生死；tombstone 行不出现）。 */
  def liveScheduleTasks(sessionId: String): IO[ReadResult[List[ScheduleRecord]]]

  /** notify-guard 读（三态）。 */
  def readNotifyGuard(source: String): IO[ReadResult[GuardDocs.NotifyGuardDoc]]

  /** provider-avoid 读（三态）。 */
  def readAvoidGuard(source: String): IO[ReadResult[GuardDocs.AvoidGuardDoc]]

  /** 会话惰性水合索引（帧面扫描，不解码——boot 只载索引的同形）。 */
  def sessionIndex(sessionId: String, stream: String): IO[SessionIndex]

  /** 事件分片索引（派生面③）。 */
  def eventIndex(): IO[List[Shards.IdxRow]]

end DbReadFace

/** 恢复面 trait（D-3 稳定面之三；§3.11 表的执行腿）。 */
trait DbRecovery:
  /** 步 3（D-8 工作默认）：permission 重装 + ask 过期关闭（本腿为其追加终态行）。 */
  def recoverPendingD8(): IO[Recovery.PendingPlan]

  /** 不变式探针（V6）：ask 面未闭合行数——恢复腿跑后 == 0。 */
  def orphanOpenAsks(): IO[Int]

  /** 步 5（D-10）：guard 读三态折保守向（坏 ⇒ 隔离 + 保守值「全部仍在冷却」）。 */
  def recoverNotifyGuardConservative(source: String, now: Long): IO[GuardDocs.NotifyGuardDoc]

  /** 步 4：idx 全量重建（派生面③）。 */
  def rebuildEventIndex(): IO[List[Shards.IdxRow]]

  /** 隔离动作族（.corrupt-<ts> 就地改名，全字节保留）。 */
  def quarantineEventShard(shardNo: Int, now: Long): IO[Option[Path]]
  def quarantinePending(stream: PendingStream, now: Long): IO[Option[Path]]
  def quarantineGuardDoc(source: String, now: Long): IO[Option[Path]]

  /** 全树核验（V4：重号/回退探针 + 计数器交叉 + idx 新鲜度 + Corrupt 清单）。 */
  def verify(): IO[VerifyReport]

end DbRecovery

/**
 * 库句柄（一个数据根一个；生命周期 = open…close）。写路径统一穿三层闸
 * （流级 → 全局写序 → 世代断言），任何一层失败 = 结构化 [[DbError]]。
 * 闸纪律：持闸临界区内**禁止**再取任何闸（嵌套取闸 = 死锁源）。
 */
final class DbHandle private[db] (
  cfg: DbConfig,
  val dbRoot: Path,
  instanceLock: InstanceLock,
  private[db] val counter: SeqCounter,
  initialTmpSwept: Int = 0
) extends DbWriteFace,
      DbReadFace,
      DbRecovery,
      AutoCloseable:

  import DbCore.log

  // ── 三层锁的库内侧（D-7 层①②；层③ = instanceLock 断言）───────────────────
  private val writeGate = new Semaphore(1) // 层②：进程内全局写序——一切落盘过同一点
  private val streamGates = new ConcurrentHashMap[String, Semaphore] // 层①：流级单写者
  @volatile private var lostGeneration = false

  // ── 计数面（V2 的「计数」半边）────────────────────────────────────────────
  private val tornTails = new AtomicLong(0)
  private val tornBytes = new AtomicLong(0)
  private val quarantinedN = new AtomicLong(0)
  private val tmpSweptN = new AtomicLong(initialTmpSwept.toLong)
  private val appendsN = new AtomicLong(0)

  def stats: DbStats = DbStats(
    tornTailsRepaired = tornTails.get(),
    tornBytesDropped = tornBytes.get(),
    quarantined = quarantinedN.get(),
    tmpSwept = tmpSweptN.get(),
    appends = appendsN.get(),
    seqHighWatermark = counter.persistedNext - 1L
  )

  // Batched 档脏面（flush/close 收口）。
  private val dirtyBatched = new ConcurrentHashMap[Path, java.lang.Boolean]()

  /**
   * §3.11 步 3 完成门（遗忘刀避让窗基线，乙件 S16→S17 同语义：清扫不得在 boot
   * 恢复完成前跑）——[[recoverPendingD8]] 成功即开；[[RetentionSweeper]] 先等此门。
   * 门永不开 ⇒ sweep 永不跑（fail-safe：未恢复就绝不遗忘）。
   */
  private val recoveryGate: Deferred[IO, Unit] = Deferred.unsafe[IO, Unit]

  private[db] def awaitRecoveryComplete: IO[Unit] = recoveryGate.get

  private def assertWritable(): Unit =
    if lostGeneration || !instanceLock.stillHeld then
      lostGeneration = true
      throw DbError.ReadOnly(
        s"instance lock lost (generation ${instanceLock.generation} superseded) — this handle " +
          "is read-only now (design §3.9 hot-restart window: 旧实例只读、新实例持锁后开写)"
      )

  private def withGates[A](streamKey: String)(body: => A): A =
    val g = streamGates.computeIfAbsent(streamKey, _ => new Semaphore(1))
    g.acquire()
    try
      writeGate.acquire()
      try
        assertWritable()
        body
      finally writeGate.release()
    finally g.release()

  // ── 测试面钩子（private[db]；V5/V6/V8/V9 的 kill-9 模拟）───────────────────
  /** 模拟 kill -9：OS 锁先行消失、不做优雅 flush/close（Batched 档允许丢批）。 */
  private[db] def simulateCrash(): Unit = instanceLock.release()
  private[db] def instanceLockGeneration: Long = instanceLock.generation
  private[db] def counterPersistedNext: Long = counter.persistedNext

  // ── 路径面（设计 §3.2(b) 数据树）──────────────────────────────────────────

  private def eventsDir: Path = dbRoot.resolve("events")
  private def sessionsDir: Path = dbRoot.resolve("sessions")
  private def pendingDir: Path = dbRoot.resolve("pending")
  private def scheduleDir: Path = dbRoot.resolve("schedule")
  private def guardDir: Path = dbRoot.resolve("guard")

  private def sessionDir(sessionId: String): Path = sessionsDir.resolve(safeSeg(sessionId))
  private def snapshotsDir(sessionId: String): Path = sessionDir(sessionId).resolve("snapshots")
  private def pendingFile(stream: PendingStream): Path = pendingDir.resolve(s"${stream.fileName}.jsonl")
  private def scheduleFile(sessionId: String): Path = scheduleDir.resolve(s"${safeSeg(sessionId)}.jsonl")
  private def tombstonesFile: Path = eventsDir.resolve("events.tombstones.jsonl")

  private def safeSeg(id: String): String =
    val cleaned = id.replaceAll("[^A-Za-z0-9._-]", "_")
    require(
      cleaned.nonEmpty && cleaned != "." && cleaned != ".." && !cleaned.startsWith("."),
      s"unsafe db path segment: $id"
    )
    cleaned

  private def guardSlug(source: String): String = source.replaceAll("[^A-Za-z0-9._-]", "-")

  private def notifyGuardPath(source: String): Path =
    guardDir.resolve(s"notify-${guardSlug(source)}.json")
  private val avoidGuardPath: Path = guardDir.resolve("provider-avoid.json")

  // ── 解码/校验单点 ──────────────────────────────────────────────────────────

  private def decodeEventLine(line: String): Either[String, EventEnvelope] =
    parser.parse(line).flatMap(_.as[EventEnvelope]) match
      case Right(env) if EventEnvelope.verify(env) => Right(env)
      case Right(env) => Left(s"checksum mismatch at seq=${env.seq}")
      case Left(err) => Left(s"decode: ${err.getMessage}")

  private def validatorFor[A](using io.circe.Decoder[A]): String => Either[String, Unit] =
    line => parser.parse(line).flatMap(_.as[A]).map(_ => ()).left.map(e => s"decode: ${e.getMessage}")

  private def decodeAs[A](using io.circe.Decoder[A])(line: String): Either[String, A] =
    parser.parse(line).flatMap(_.as[A]).left.map(e => s"decode: ${e.getMessage}")

  private def decodeLines[A](lines: List[String], decode: String => Either[String, A]): Either[String, List[A]] =
    val out = List.newBuilder[A]
    var bad: Option[String] = None
    lines.foreach { l =>
      if bad.isEmpty then
        decode(l) match
          case Right(a) => out += a
          case Left(why) => bad = Some(why)
    }
    bad.toLeft(out.result())

  private def readWholeDoc[A](file: Path, decode: String => Either[String, A]): ReadResult[A] =
    if !Files.exists(file) then ReadResult.Absent()
    else
      try
        val text = Files.readString(file)
        if text.trim.isEmpty then ReadResult.Corrupt("empty", file)
        else
          decode(text) match
            case Right(a) => ReadResult.Present(a)
            case Left(why) => ReadResult.Corrupt(s"corrupt: $why", file)
      catch case e: Exception => ReadResult.Corrupt(s"unreadable: ${e.getMessage}", file)

  // ── JSONL 追加单点（分片族 / 单文件两形；V1「无半行入库」+ V3 拒写在此）────

  private def noteTail(state: WriteFace.TailState): Unit = state match
    case WriteFace.TailState.RepairedTornTail(n) =>
      tornTails.incrementAndGet()
      tornBytes.addAndGet(n)
    case _ => ()

  private def prepareOrRefuse(file: Path, validate: String => Either[String, Unit]): Unit =
    WriteFace.prepareAppend(file, validate) match
      case WriteFace.TailState.BadTail(why) =>
        throw DbError.StreamCorrupt(
          s"append refused: tail frame of ${file.getFileName} invalid ($why) — quarantine " +
            "first (design §3.7: 读失败不得参与下一次写)",
          file
        )
      case other => noteTail(other)

  private def appendSharded(
    dir: Path,
    base: String,
    streamKey: String,
    tier: WriteFace.FsyncTier,
    validate: String => Either[String, Unit]
  )(lineForSeq: Long => String): Long =
    withGates(streamKey) {
      val cur = Shards.currentShardNo(dir, base)
      if cur >= 1 then prepareOrRefuse(Shards.shardPath(dir, base, cur), validate)
      val seq = counter.next()
      val line = lineForSeq(seq)
      val out = Shards.appendLine(dir, base, line, tier, cfg.shardMaxBytes)
      if out.oversized then
        log.warn(
          "oversized single line ({} B > cap {} B) admitted to its own shard {}.{}",
          line.getBytes("UTF-8").length.toLong,
          cfg.shardMaxBytes,
          base,
          out.shardNo
        )
      if tier == WriteFace.FsyncTier.Batched then
        dirtyBatched.put(Shards.shardPath(dir, base, out.shardNo), java.lang.Boolean.TRUE)
      appendsN.incrementAndGet()
      seq
    }

  private def appendSingle(
    file: Path,
    streamKey: String,
    tier: WriteFace.FsyncTier,
    validate: String => Either[String, Unit]
  )(lineForSeq: Long => String): Long =
    withGates(streamKey) {
      if Files.exists(file) then prepareOrRefuse(file, validate)
      val seq = counter.next()
      WriteFace.appendDurable(file, lineForSeq(seq), tier)
      appendsN.incrementAndGet()
      seq
    }

  /** 已持闸追加 N 行（恢复腿/闭单用；闸由调用方持有）。 */
  private def appendRowsUnderGate(file: Path, validate: String => Either[String, Unit], lines: List[String]): Unit =
    if lines.isEmpty then ()
    else
      if Files.exists(file) then prepareOrRefuse(file, validate)
      lines.foreach(l => WriteFace.appendDurable(file, l, WriteFace.FsyncTier.PerRecord))
      appendsN.addAndGet(lines.size.toLong)

  // ── 事件流（案 B 类②；D-12：外层存储 envelope，内核 event 原样）────────────

  def appendEvent(event: Json, dedupKey: Option[String]): IO[Long] = IO.blocking {
    appendSharded(
      eventsDir,
      "events",
      "events",
      WriteFace.FsyncTier.PerRecord,
      line => decodeEventLine(line).map(_ => ())
    )(seq => // 尾帧校验含 checksum（与读面同判据）
      EventEnvelope.wrap(seq, event, dedupKey).asJson.noSpaces
    )
  }

  def readEvents(fromSeq: Long, limit: Int, logical: Boolean): IO[ReadResult[StreamRead[EventEnvelope]]] =
    IO.blocking(readEventsSync(fromSeq, limit, logical))

  private def readEventsSync(fromSeq: Long, limit: Int, logical: Boolean): ReadResult[StreamRead[EventEnvelope]] =
    val nos = Shards.listShardNos(eventsDir, "events")
    if nos.isEmpty then ReadResult.Absent()
    else
      var corrupt: Option[ReadResult.Corrupt] = None
      var torn = 0
      val all = List.newBuilder[EventEnvelope]
      nos.foreach { n =>
        if corrupt.isEmpty then
          Shards.scanShard(Shards.shardPath(eventsDir, "events", n)).foreach { fr =>
            torn += fr.tornTailDropped
            decodeLines(fr.records, decodeEventLine) match
              case Right(envs) => all ++= envs
              case Left(why) =>
                corrupt = Some(ReadResult.Corrupt(s"bad-frame:$why", Shards.shardPath(eventsDir, "events", n)))
          }
      }
      corrupt match
        case Some(c) => c
        case None =>
          // logical 视图：tombstone 面坏 ⇒ 不折叠成空集（那会放大遗忘）——显式 Corrupt。
          def liveUnitsOrCorrupt: Either[ReadResult.Corrupt, Set[String]] =
            if !logical then Right(Set.empty[String])
            else
              readTombstoneRows() match
                case ReadResult.Present(sr) => Right(Retention.forgottenUnits(sr.records))
                case ReadResult.Absent() => Right(Set.empty[String])
                case ReadResult.Corrupt(_, ev) =>
                  Left(ReadResult.Corrupt("tombstones-corrupt", ev))
          liveUnitsOrCorrupt match
            case Left(c) => c
            case Right(forgotten) =>
              val filtered =
                if logical then all.result().filter(env => !forgotten.contains(unitKeyOf(env)))
                else all.result()
              val paged = filtered
                .filter(_.seq >= fromSeq)
                .sortBy(_.seq)
                .take(math.max(0, limit))
              ReadResult.Present(StreamRead(paged, torn))

      end match

    end if

  end readEventsSync

  /** 事件单位键（判定单位按 subject+type 分域；内核字段 best-effort 抽取——D-12 对拍位）。 */
  def unitKeyOf(env: EventEnvelope): String =
    val t = env.event.hcursor.downField("type").as[String].toOption
    val s = env.event.hcursor.downField("subject").as[String].toOption
    (t, s) match
      case (Some(typ), Some(sub)) => s"$sub|$typ"
      case (Some(typ), None) => env.dedupKey.getOrElse(s"type|$typ")
      case _ => env.dedupKey.getOrElse(s"seq|${env.seq}")

  private def readTombstoneRows(): ReadResult[StreamRead[Retention.TombstoneRow]] =
    Shards.scanShard(tombstonesFile) match
      case None => ReadResult.Absent()
      case Some(fr) =>
        decodeLines(fr.records, decodeAs[Retention.TombstoneRow]) match
          case Right(rows) => ReadResult.Present(StreamRead(rows, fr.tornTailDropped))
          case Left(why) => ReadResult.Corrupt(s"bad-frame:$why", tombstonesFile)

  /** 逻辑标记轨（D-9）：标记遗忘（可回滚）／复活。返回追加行数。 */
  def markForgotten(unitIds: List[String], now: Long): IO[Int] = IO.blocking {
    appendTombstoneRows(unitIds.map(u => Retention.TombstoneRow(u, now, revived = false)))
  }

  def reviveUnits(unitIds: List[String], now: Long): IO[Int] = IO.blocking {
    appendTombstoneRows(unitIds.map(u => Retention.TombstoneRow(u, now, revived = true)))
  }

  private def appendTombstoneRows(rows: List[Retention.TombstoneRow]): Int =
    if rows.isEmpty then 0
    else
      withGates("events-tombstones") {
        appendRowsUnderGate(tombstonesFile, validatorFor[Retention.TombstoneRow], rows.map(_.asJson.noSpaces))
      }
      rows.size

  // ── 会话消息流（案 B 类①）─────────────────────────────────────────────────

  private def sessionBase(stream: String): String =
    stream match
      case SessionEnvelope.StreamLlm => "messages"
      case SessionEnvelope.StreamUi => "ui"
      case other => throw new IllegalArgumentException(s"unknown session stream: $other")

  private def sessionTier(stream: String): WriteFace.FsyncTier =
    if stream == SessionEnvelope.StreamUi then cfg.uiFsyncTier else WriteFace.FsyncTier.PerRecord

  def appendMessage(sessionId: String, stream: String, payload: Json): IO[Long] = IO.blocking {
    appendSharded(
      sessionDir(sessionId),
      sessionBase(stream),
      s"session:$sessionId:$stream",
      sessionTier(stream),
      validatorFor[SessionEnvelope]
    ) { seq =>
      SessionEnvelope(
        seq,
        System.currentTimeMillis(),
        sessionId,
        stream,
        SessionEnvelope.KindMessageAppend,
        payload
      ).asJson.noSpaces
    }
  }

  def appendCompactPoint(sessionId: String, beforeRef: String, afterRef: String, tokens: Long): IO[Long] = IO.blocking {
    appendSharded(
      sessionDir(sessionId),
      sessionBase(SessionEnvelope.StreamLlm),
      s"session:$sessionId:llm",
      WriteFace.FsyncTier.PerRecord,
      validatorFor[SessionEnvelope]
    ) { seq =>
      SessionEnvelope(
        seq,
        System.currentTimeMillis(),
        sessionId,
        SessionEnvelope.StreamLlm,
        SessionEnvelope.KindCompactPoint,
        SessionEnvelope.CompactPoint(beforeRef, afterRef, tokens).asJson
      ).asJson.noSpaces
    }
  }

  def writeSnapshot(sessionId: String, content: String): IO[Int] = IO.blocking {
    require(parser.parse(content).isRight, "snapshot content must be valid JSON")
    withGates(s"snapshot:$sessionId") {
      val dir = snapshotsDir(sessionId)
      val next =
        if Files.exists(dir) then
          val s = Files.list(dir)
          try
            s.iterator()
              .asScala
              .toList
              .flatMap(p => p.getFileName.toString.stripSuffix(".json").toIntOption)
              .maxOption
              .getOrElse(0)
          finally s.close()
        else 0
      val n = next + 1
      WriteFace.writeFiveStepText(dir.resolve(s"$n.json"), content)
      n
    }
  }

  def readSnapshot(sessionId: String, n: Int): IO[ReadResult[String]] = IO.blocking {
    readWholeDoc(
      snapshotsDir(sessionId).resolve(s"$n.json"),
      text => if parser.parse(text).isRight then Right(text) else Left("not JSON")
    )
  }

  def readSession(sessionId: String, stream: String): IO[ReadResult[StreamRead[SessionEnvelope]]] =
    IO.blocking {
      val dir = sessionDir(sessionId)
      val base = sessionBase(stream)
      val nos = Shards.listShardNos(dir, base)
      if nos.isEmpty then ReadResult.Absent()
      else
        var corrupt: Option[ReadResult.Corrupt] = None
        var torn = 0
        val all = List.newBuilder[SessionEnvelope]
        nos.foreach { n =>
          if corrupt.isEmpty then
            Shards.scanShard(Shards.shardPath(dir, base, n)).foreach { fr =>
              torn += fr.tornTailDropped
              decodeLines(fr.records, decodeAs[SessionEnvelope]) match
                case Right(envs) => all ++= envs
                case Left(why) =>
                  corrupt = Some(ReadResult.Corrupt(s"bad-frame:$why", Shards.shardPath(dir, base, n)))
            }
        }
        corrupt match
          case Some(c) => c
          case None => ReadResult.Present(StreamRead(all.result().sortBy(_.seq), torn))
      end if
    }

  def sessionIndex(sessionId: String, stream: String): IO[SessionIndex] = IO.blocking {
    val dir = sessionDir(sessionId)
    val base = sessionBase(stream)
    var torn = 0
    val shards = Shards.listShardNos(dir, base).map { n =>
      val f = Shards.shardPath(dir, base, n)
      val framed = Shards.scanShard(f)
      framed.foreach(fr => torn += fr.tornTailDropped)
      SessionIndex.ShardIndex(
        n,
        framed.map(_.records.size.toLong).getOrElse(0L),
        if Files.exists(f) then Files.size(f) else 0L
      )
    }
    SessionIndex(sessionId, stream, shards, torn)
  }

  // ── 待审单（案 B 类③；不可再生面——Corrupt ⇒ 显式拒绝）────────────────────

  def openPending(card: PendingRecord.PendingCard): IO[Long] = IO.blocking {
    val stream = card.kind match
      case PendingRecord.KindPermission | PendingRecord.KindMcpPermission =>
        PendingStream.Permission
      case PendingRecord.KindAskUser => PendingStream.Ask
      case k => throw new IllegalArgumentException(s"unknown pending kind: $k")
    appendSingle(
      pendingFile(stream),
      s"pending:${stream.fileName}",
      WriteFace.FsyncTier.PerRecord,
      validatorFor[PendingRecord]
    ) { seq =>
      PendingRecord.openRow(seq, card, System.currentTimeMillis()).asJson.noSpaces
    }
  }

  def closePending(
    stream: PendingStream,
    requestId: String,
    closedBy: String,
    closeReason: String,
    now: Long
  ): IO[Boolean] = IO.blocking {
    withGates(s"pending:${stream.fileName}") {
      readLatestPending(stream) match
        case ReadResult.Corrupt(kind, ev) =>
          throw DbError.StreamCorrupt(
            s"close refused: $kind at ${ev.getFileName} — pending 面不可再生，" +
              "quarantine 后由调用方裁定",
            ev
          )
        case ReadResult.Absent() => false
        case ReadResult.Present(rows) =>
          rows.find(_.requestId == requestId) match
            case Some(row) if row.state == PendingRecord.StateOpen =>
              val seq = counter.next()
              appendRowsUnderGate(
                pendingFile(stream),
                validatorFor[PendingRecord],
                List(PendingRecord.closeRow(row, seq, now, closedBy, closeReason).asJson.noSpaces)
              )
              true
            case _ => false // 已闭（幂等）或不存在
    }
  }

  def readPending(stream: PendingStream): IO[ReadResult[StreamRead[PendingRecord]]] =
    IO.blocking(readPendingSync(stream))

  private def readPendingSync(stream: PendingStream): ReadResult[StreamRead[PendingRecord]] =
    val f = pendingFile(stream)
    Shards.scanShard(f) match
      case None => ReadResult.Absent()
      case Some(fr) =>
        decodeLines(fr.records, decodeAs[PendingRecord]) match
          case Right(rows) => ReadResult.Present(StreamRead(rows, fr.tornTailDropped))
          case Left(why) => ReadResult.Corrupt(s"bad-frame:$why", f)

  /** 同 requestId 折叠取最新行（持闸读面；闸由调用方持有或不持——纯读无闸）。 */
  private def readLatestPending(stream: PendingStream): ReadResult[List[PendingRecord]] =
    val f = pendingFile(stream)
    Shards.scanShard(f) match
      case None => ReadResult.Absent()
      case Some(fr) =>
        decodeLines(fr.records, decodeAs[PendingRecord]) match
          case Right(rows) => ReadResult.Present(Recovery.foldPending(rows))
          case Left(why) => ReadResult.Corrupt(s"bad-frame:$why", f)

  // ── 调度任务（案 B 类④）────────────────────────────────────────────────────

  def appendScheduleTask(sessionId: String, taskDef: Json, now: Long): IO[Long] = IO.blocking {
    require(taskDef.hcursor.downField("id").as[String].isRight, "taskDef must carry an id field")
    appendSingle(
      scheduleFile(sessionId),
      s"schedule:$sessionId",
      WriteFace.FsyncTier.PerRecord,
      validatorFor[ScheduleRecord]
    ) { seq =>
      ScheduleRecord(seq, sessionId, taskDef, now, None).asJson.noSpaces
    }
  }

  def tombstoneScheduleTask(sessionId: String, taskId: String, now: Long): IO[Boolean] =
    IO.blocking {
      withGates(s"schedule:$sessionId") {
        readScheduleRows(sessionId) match
          case ReadResult.Corrupt(kind, ev) =>
            throw DbError.StreamCorrupt(s"tombstone refused: $kind", ev)
          case ReadResult.Absent() => false
          case ReadResult.Present(rows) =>
            val (live, _) = ScheduleRecord.foldLive(rows)
            if live.exists(ScheduleRecord.taskIdOf(_).contains(taskId)) then
              val seq = counter.next()
              appendRowsUnderGate(
                scheduleFile(sessionId),
                validatorFor[ScheduleRecord],
                List(ScheduleRecord.tombstoneRow(seq, sessionId, taskId, now).asJson.noSpaces)
              )
              true
            else false
      }
    }

  def readSchedule(sessionId: String): IO[ReadResult[StreamRead[ScheduleRecord]]] =
    IO.blocking {
      val f = scheduleFile(sessionId)
      Shards.scanShard(f) match
        case None => ReadResult.Absent()
        case Some(fr) =>
          decodeLines(fr.records, decodeAs[ScheduleRecord]) match
            case Right(rows) => ReadResult.Present(StreamRead(rows, fr.tornTailDropped))
            case Left(why) => ReadResult.Corrupt(s"bad-frame:$why", f)
    }

  def liveScheduleTasks(sessionId: String): IO[ReadResult[List[ScheduleRecord]]] =
    readSchedule(sessionId).map {
      case ReadResult.Absent() => ReadResult.Absent()
      case ReadResult.Corrupt(k, e) => ReadResult.Corrupt(k, e)
      case ReadResult.Present(sr) => ReadResult.Present(ScheduleRecord.foldLive(sr.records)._1)
    }

  private def readScheduleRows(sessionId: String): ReadResult[List[ScheduleRecord]] =
    val f = scheduleFile(sessionId)
    Shards.scanShard(f) match
      case None => ReadResult.Absent()
      case Some(fr) =>
        decodeLines(fr.records, decodeAs[ScheduleRecord]) match
          case Right(rows) => ReadResult.Present(rows)
          case Left(why) => ReadResult.Corrupt(s"bad-frame:$why", f)

  // ── guard 小档（案 B 类⑥；锁内 RMW 整档五步写；V3 拒写在此）───────────────

  def writeNotifyGuard(doc: GuardDocs.NotifyGuardDoc): IO[Unit] = IO.blocking {
    writeGuardDoc(notifyGuardPath(doc.source), doc.asJson.noSpaces + "\n", guardDecoder[GuardDocs.NotifyGuardDoc])
  }

  def writeAvoidGuard(doc: GuardDocs.AvoidGuardDoc): IO[Unit] = IO.blocking {
    writeGuardDoc(avoidGuardPath, doc.asJson.noSpaces + "\n", guardDecoder[GuardDocs.AvoidGuardDoc])
  }

  private def guardDecoder[A](text: String)(using io.circe.Decoder[A]): Either[String, A] =
    parser.parse(text).flatMap(_.as[A]).left.map(e => s"decode: ${e.getMessage}")

  private def writeGuardDoc[A](file: Path, content: String, validate: String => Either[String, A]): Unit =
    withGates(s"guard:${file.getFileName}") {
      // V3：坏档在场 ⇒ 写拒绝——先隔离（quarantineGuardDoc）再写。
      readWholeDoc(file, validate) match
        case ReadResult.Corrupt(kind, ev) =>
          throw DbError.StreamCorrupt(
            s"guard write refused: $kind at $ev — quarantine first " +
              "(design §3.7: 读失败不得参与下一次写)",
            ev
          )
        case _ => ()
      WriteFace.writeFiveStepText(file, content)
    }

  def readNotifyGuard(source: String): IO[ReadResult[GuardDocs.NotifyGuardDoc]] = IO.blocking {
    readWholeDoc(notifyGuardPath(source), guardDecoder[GuardDocs.NotifyGuardDoc])
  }

  def recoverNotifyGuardConservative(source: String, now: Long): IO[GuardDocs.NotifyGuardDoc] =
    IO.blocking {
      readWholeDoc(notifyGuardPath(source), guardDecoder[GuardDocs.NotifyGuardDoc]) match
        case ReadResult.Present(doc) => doc // D-10：重启不归零
        case ReadResult.Absent() =>
          GuardDocs.NotifyGuardDoc(1, source, Nil, 0L, now) // 真·全新：无窗口态
        case ReadResult.Corrupt(kind, ev) =>
          // 坏 ⇒ 保守向（宁可少发不可风暴）+ 隔离留档。
          log.warn("guard recovery: {} corrupt ({}) — conservative all-cooling + quarantined", source, kind)
          Quarantine.quarantine(ev, now).foreach(_ => quarantinedN.incrementAndGet())
          Recovery.conservativeNotifyGuard(source, now)
    }

  def readAvoidGuard(source: String): IO[ReadResult[GuardDocs.AvoidGuardDoc]] = IO.blocking {
    // source 参数保持 API 对称；avoid 面 = 单档 provider-avoid.json。
    readWholeDoc(avoidGuardPath, guardDecoder[GuardDocs.AvoidGuardDoc])
  }

  /**
   * 步 5（D-10，avoid 面）：三态折保守向。坏 ⇒ 隔离 + **有界**保守窗——
   * `conservativeKeys` 全部软避至 `now + conservativeWindowMs`（自愈：窗到自然放回，
   * 探测/真请求照常恢复）。**不取** notify 的 `Long.MaxValue` 形：avoid 若永久全避 ⇒
   * filterCandidates 恒空 ⇒ LLM 全链阻塞（宁可少用不可全灭）。down 面保守向不适用
   * （未知键集无从保守；有界 avoid 已覆盖「不锤坏 provider」的窗口语义）。
   */
  def recoverAvoidGuardConservative(
    now: Long,
    conservativeKeys: List[String],
    conservativeWindowMs: Long
  ): IO[GuardDocs.AvoidGuardDoc] = IO.blocking {
    readWholeDoc(avoidGuardPath, guardDecoder[GuardDocs.AvoidGuardDoc]) match
      case ReadResult.Present(doc) => doc // D-10：重启不归零
      case ReadResult.Absent() =>
        GuardDocs.AvoidGuardDoc(1, Db.AvoidGuardSource, Map.empty, now, Map.empty) // 真·全新
      case ReadResult.Corrupt(kind, ev) =>
        log.warn(
          "guard recovery: provider-avoid corrupt ({}) — conservative bounded avoid " +
            "({} key(s), {}ms) + quarantined",
          kind,
          conservativeKeys.size,
          conservativeWindowMs
        )
        Quarantine.quarantine(ev, now).foreach(_ => quarantinedN.incrementAndGet())
        GuardDocs.AvoidGuardDoc(
          version = 1,
          source = Db.AvoidGuardSource,
          avoidUntil = conservativeKeys.map(k => k -> (now + math.max(0L, conservativeWindowMs))).toMap,
          updatedAt = now
        )
  }

  // ── 恢复腿（§3.11 执行面）─────────────────────────────────────────────────

  def recoverPendingD8(): IO[Recovery.PendingPlan] =
    IO.blocking {
      val now = System.currentTimeMillis()
      // 不可再生面（三标签①）：坏 ⇒ 隔离 + 显式拒绝（由调用方裁定，绝不静默空起步）。
      List(PendingStream.Permission, PendingStream.Ask).foreach { stream =>
        readPendingSync(stream) match
          case ReadResult.Corrupt(kind, ev) =>
            Quarantine.quarantine(ev, now).foreach(_ => quarantinedN.incrementAndGet())
            throw DbError.StreamCorrupt(
              s"pending recovery refused: ${stream.fileName} $kind — 已隔离 $ev；" +
                "pending 面不可再生，处置（重放/人工核档）由调用方裁定后重试",
              ev
            )
          case _ => ()
      }
      val permLatest = readLatestPending(PendingStream.Permission) match
        case ReadResult.Present(rows) => rows
        case _ => Nil
      val askLatest = readLatestPending(PendingStream.Ask) match
        case ReadResult.Present(rows) => rows
        case _ => Nil
      val plan = Recovery.planPendingRecovery(permLatest ++ askLatest)
      // ask 过期关闭：本腿为其追加终态行（V6 不变式：孤儿 ask == 0）。
      withGates(s"pending:${PendingStream.Ask.fileName}") {
        appendRowsUnderGate(
          pendingFile(PendingStream.Ask),
          validatorFor[PendingRecord],
          plan.expiredAsks.map { row =>
            val seq = counter.next()
            PendingRecord.closeRow(row, seq, now, Recovery.ExpiredCloseBy, Recovery.ExpiredCloseReason).asJson.noSpaces
          }
        )
      }
      if plan.reinstall.nonEmpty then
        log.warn(
          "pending recovery (D-8): {} permission card(s) to reinstall: {}",
          plan.reinstall.size,
          plan.reinstall.map(_.requestId).mkString(",")
        )
      plan
    }.flatMap { plan =>
      // 避让门开锁（幂等）：恢复腿成功 ⇒ 遗忘刀调度可起步（S16→S17）。
      recoveryGate.complete(()).attempt.as(plan)
    }

  def orphanOpenAsks(): IO[Int] = IO.blocking {
    readLatestPending(PendingStream.Ask) match
      case ReadResult.Present(rows) =>
        rows.count(r => r.state == PendingRecord.StateOpen && r.kind == PendingRecord.KindAskUser)
      case _ => 0
  }

  def rebuildEventIndex(): IO[List[Shards.IdxRow]] = IO.blocking(rebuildEventIndexSync())

  /**
   * idx 读面（派生面③）：**读即重算**——idx 是纯派生件，任何读点都从分片现算
   * （库规模下开销可忽略；接线期若需缓存再加）。坏档跳片 + WARN，由 shard-set
   * 不一致暴露（verify 可见）。
   */
  def eventIndex(): IO[List[Shards.IdxRow]] = IO.blocking(rebuildEventIndexSync())

  private def rebuildEventIndexSync(): List[Shards.IdxRow] =
    withGates("events-idx") {
      val rows = Shards.listShardNos(eventsDir, "events").flatMap { n =>
        val f = Shards.shardPath(eventsDir, "events", n)
        Shards.scanShard(f) match
          case Some(fr) =>
            decodeLines(fr.records, decodeEventLine) match
              case Right(envs) =>
                List(
                  Shards.IdxRow(
                    n,
                    envs.headOption.map(_.seq).getOrElse(0L),
                    envs.lastOption.map(_.seq).getOrElse(0L),
                    Files.size(f),
                    envs.size.toLong
                  )
                )
              case Left(why) =>
                log.warn("events.idx rebuild: shard {} undecodable ({}) — skipped", n, why)
                Nil
          case None => Nil
        end match
      }
      Shards.writeIdx(eventsDir, rows)
      rows
    }

  private[db] def checkEventIndexAtOpen(): Unit =
    Shards.readIdx(eventsDir) match
      case Right(rows) =>
        val actual = Shards.listShardNos(eventsDir, "events")
        if rows.map(_.shard).distinct != actual then rebuildEventIndexSync()
      case Left(why) =>
        log.warn("events.idx corrupt ({}) — derived face: deleted and rebuilt (三标签③)", why)
        val idx = eventsDir.resolve(Shards.IdxFileName)
        try if Files.exists(idx) then Files.delete(idx)
        catch case _: Exception => ()
        rebuildEventIndexSync()

  private[db] def reconcileCounterAtOpen(): Unit =
    // 交叉校验面（§3.6）：事件分片尾 seq + 待审单/调度行 seq（会话流 lazy——全量在 verify()）。
    var maxSeen = 0L
    Shards.listShardNos(eventsDir, "events").foreach { n =>
      Shards.scanShard(Shards.shardPath(eventsDir, "events", n)).foreach { fr =>
        decodeLines(fr.records, decodeEventLine) match
          case Right(envs) => envs.lastOption.foreach(e => maxSeen = math.max(maxSeen, e.seq))
          case Left(_) => () // 坏片由读面报 Corrupt；交叉校验不猜
      }
    }
    List(PendingStream.Permission, PendingStream.Ask).foreach { s =>
      Shards.scanShard(pendingFile(s)).foreach { fr =>
        decodeLines(fr.records, decodeAs[PendingRecord]) match
          case Right(rows) => rows.lastOption.foreach(r => maxSeen = math.max(maxSeen, r.seq))
          case Left(_) => ()
      }
    }
    listScheduleFiles().foreach { f =>
      Shards.scanShard(f).foreach { fr =>
        decodeLines(fr.records, decodeAs[ScheduleRecord]) match
          case Right(rows) => rows.lastOption.foreach(r => maxSeen = math.max(maxSeen, r.seq))
          case Left(_) => ()
      }
    }
    if counter.reconcileWith(maxSeen) then
      log.warn("seq counter reconciled to >= {} (counter lagged streams — 交叉校验以大者为准)", maxSeen + 1L)

  end reconcileCounterAtOpen

  private def listScheduleFiles(): List[Path] =
    if !Files.exists(scheduleDir) then Nil
    else
      val s = Files.list(scheduleDir)
      try s.iterator().asScala.filter(Files.isRegularFile(_)).toList
      finally s.close()

  // ── 隔离动作族（§3.10；Corrupt 标准动作的执行面）──────────────────────────

  def quarantineEventShard(shardNo: Int, now: Long): IO[Option[Path]] = IO.blocking {
    val out = Quarantine.quarantine(Shards.shardPath(eventsDir, "events", shardNo), now)
    out.foreach(_ => quarantinedN.incrementAndGet())
    rebuildEventIndexSync()
    out
  }

  def quarantinePending(stream: PendingStream, now: Long): IO[Option[Path]] = IO.blocking {
    val out = Quarantine.quarantine(pendingFile(stream), now)
    out.foreach(_ => quarantinedN.incrementAndGet())
    out
  }

  def quarantineGuardDoc(source: String, now: Long): IO[Option[Path]] = IO.blocking {
    val out = Quarantine
      .quarantine(notifyGuardPath(source), now)
      .orElse(Quarantine.quarantine(avoidGuardPath, now))
    out.foreach(_ => quarantinedN.incrementAndGet())
    out
  }

  // ── 遗忘 sweep（D-9 双轨：逻辑轨 + 物化轨一等显式动词）─────────────────────

  /**
   * 一轮遗忘 sweep（逻辑轨）：对事件面跑两刀规划，DropBy* ⇒ 追加 tombstone 标记
   * （可回滚）；pending/快照/隔离档 = 整域豁免（计数进 keepDebt）。
   * 物理回收 = [[purgeTombstonedEvents]]（一等显式动词，默认永不自动跑）。
   */
  def retentionSweep(retentionCfg: Retention.Config, now: Long): IO[Retention.SweepReport] =
    IO.blocking {
      val envs = readEventsSync(0L, Int.MaxValue, logical = false) match
        case ReadResult.Present(sr) => sr.records
        case _ => Nil
      val candidates = envs.map { env =>
        Retention.Candidate(unitKeyOf(env), tsOf(env), Retention.Domain.EventStream, kernelTypeOf(env))
      }
      val decisions = Retention.plan(candidates, retentionCfg, now)
      val toForget = candidates
        .zip(decisions)
        .collect {
          case (c, Retention.Decision.DropByAge) => c.unitId
          case (c, Retention.Decision.DropBySampling) => c.unitId
        }
        .distinct
      val appended = appendTombstoneRows(toForget.map(u => Retention.TombstoneRow(u, now, revived = false)))
      val exempt = countPendingRows() + countSnapshotFiles() + countAllQuarantined()
      def cnt(d: Retention.Decision): Long = decisions.count(_ == d)
      Retention.SweepReport(
        scanned = candidates.size.toLong + exempt,
        keepDebt = cnt(Retention.Decision.KeepDebt) + exempt,
        keepSampled = cnt(Retention.Decision.KeepSampled),
        dropByAge = cnt(Retention.Decision.DropByAge),
        dropBySampling = cnt(Retention.Decision.DropBySampling),
        tombstonesAppended = appended.toLong
      )
    }

  /** 内核事件 type 抽取（D-12 对拍位：字段名以 pi-logic 定稿为准，此处 best-effort）。 */
  private def kernelTypeOf(env: EventEnvelope): String =
    env.event.hcursor.downField("type").as[String].getOrElse("")

  private def tsOf(env: EventEnvelope): Long =
    env.event.hcursor
      .downField("occurredAt")
      .as[Long]
      .orElse(env.event.hcursor.downField("ts").as[Long])
      .getOrElse(0L)

  private def countPendingRows(): Long =
    List(PendingStream.Permission, PendingStream.Ask).map { s =>
      Shards.scanShard(pendingFile(s)).map(_.records.size.toLong).getOrElse(0L)
    }.sum

  private def countSnapshotFiles(): Long =
    if !Files.exists(sessionsDir) then 0L
    else
      val walk = Files.walk(sessionsDir)
      try
        walk
          .iterator()
          .asScala
          .count(p =>
            Files.isRegularFile(p) && p.getParent != null &&
              p.getParent.getFileName.toString == "snapshots"
          )
      finally walk.close()

  private def countAllQuarantined(): Long =
    if !Files.exists(dbRoot) then 0L
    else
      val walk = Files.walk(dbRoot)
      try
        walk
          .iterator()
          .asScala
          .count(p => Files.isRegularFile(p) && Quarantine.isQuarantinedName(p.getFileName.toString))
      finally walk.close()

  /**
   * 物化回收（**一等显式高危动词**，对齐 triple D-16b：期限到期 ⇒ 逻辑先消失 ⇒ 物理
   * 回收另立动词——默认永不自动跑）。重写事件分片族：剔除「已 tombstone 且非欠债」的
   * 行；欠债行（audit/failed 族）与活性行全保留；重建 idx。返回物理删除的行数。
   */
  def purgeTombstonedEvents(): IO[Long] = IO.blocking {
    val forgotten = readTombstoneRows() match
      case ReadResult.Present(sr) => Retention.forgottenUnits(sr.records)
      case _ => Set.empty[String]
    val dropped = withGates("events") {
      var n = 0L
      Shards.listShardNos(eventsDir, "events").foreach { shardNo =>
        val f = Shards.shardPath(eventsDir, "events", shardNo)
        Shards.scanShard(f).foreach { fr =>
          val kept = List.newBuilder[String]
          fr.records.foreach { line =>
            decodeEventLine(line) match
              case Right(env) =>
                // 保留 = 未被逻辑遗忘 ∨ 欠债豁免（失败样本不可丢——遗忘刀永不追杀欠债档）。
                val keep = !forgotten.contains(unitKeyOf(env)) ||
                  Retention.hasDebt(
                    Retention.Candidate(unitKeyOf(env), tsOf(env), Retention.Domain.EventStream, kernelTypeOf(env))
                  )
                if keep then kept += line else n += 1L
              case Left(_) =>
                kept += line // 坏行原样保留（purge 不处置损坏——隔离面管辖）
          }
          val survivors = kept.result()
          if survivors.isEmpty then Files.deleteIfExists(f)
          else WriteFace.writeFiveStepText(f, survivors.mkString("", "\n", "\n"))
        }
      }
      n
    }
    rebuildEventIndexSync()
    dropped
  }

  // ── verify（V4 探针面）────────────────────────────────────────────────────

  def verify(): IO[VerifyReport] = IO.blocking {
    val seqs = scala.collection.mutable.ListBuffer.empty[Long]
    val nonMono = List.newBuilder[Path]
    val corrupt = List.newBuilder[String]
    def sweepFile(f: Path, decode: String => Either[String, Long]): Unit =
      Shards.scanShard(f).foreach { fr =>
        decodeLines(fr.records, decode) match
          case Right(ss) =>
            seqs ++= ss
            if ss.zip(ss.drop(1)).exists((a, b) => b < a) then nonMono += f
          case Left(why) => corrupt += s"$f: $why"
      }
    Shards.listShardNos(eventsDir, "events").foreach { n =>
      sweepFile(Shards.shardPath(eventsDir, "events", n), l => decodeEventLine(l).map(_.seq))
    }
    // 会话流（lazy 面的全量核验落点）。
    if Files.exists(sessionsDir) then
      val walk = Files.walk(sessionsDir)
      try
        walk.iterator().asScala.filter(Files.isRegularFile(_)).foreach { f =>
          val name = f.getFileName.toString
          if name.endsWith(".jsonl") && !Quarantine.isQuarantinedName(name) then
            sweepFile(f, decodeAs[SessionEnvelope](_).map(_.seq))
        }
      finally walk.close()
    List(PendingStream.Permission, PendingStream.Ask).foreach { s =>
      sweepFile(pendingFile(s), decodeAs[PendingRecord](_).map(_.seq))
    }
    listScheduleFiles().foreach { f =>
      sweepFile(f, decodeAs[ScheduleRecord](_).map(_.seq))
    }
    val dup = seqs.groupBy(identity).collect { case (s, xs) if xs.size > 1 => s }.toList.sorted
    val orphan = WriteFace.sweepOrphanTmp(dbRoot) // 复扫（应 0——open 已扫）
    // 派生面先行刷新（verify 是可维护探针）：idx 从分片现算，坏片跳片 ⇒ shard-set 不一致可见。
    rebuildEventIndexSync()
    val idxFresh = Shards.readIdx(eventsDir) match
      case Right(rows) => rows.map(_.shard).distinct == Shards.listShardNos(eventsDir, "events")
      case Left(_) => false
    VerifyReport(
      counterNext = counter.persistedNext,
      maxSeqSeen = if seqs.isEmpty then 0L else seqs.max,
      seqCount = seqs.size.toLong,
      dupSeq = dup,
      nonMonotonic = nonMono.result(),
      orphanTmp = orphan.size,
      corrupt = corrupt.result(),
      idxFresh = idxFresh
    )
  }

  // ── flush / close ─────────────────────────────────────────────────────────

  def flush(): IO[Unit] = IO.blocking(flushSync())

  private def flushSync(): Unit =
    dirtyBatched.keySet().asScala.foreach(WriteFace.forceFile)
    dirtyBatched.clear()

  /** 优雅关闭（AutoCloseable）：收口 Batched 档、释放实例锁（世代号留盘给下一任）。 */
  override def close(): Unit =
    var failure: Option[Throwable] = None
    try flushSync()
    catch case scala.util.control.NonFatal(e) => failure = Some(e)
    try instanceLock.release()
    catch
      case scala.util.control.NonFatal(e) =>
        failure match
          case Some(first) => first.addSuppressed(e)
          case None => failure = Some(e)
    failure.foreach(throw _)

end DbHandle
