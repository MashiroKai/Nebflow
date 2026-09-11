package nebflow.core.tools

import io.circe.{Json, JsonObject}
import io.circe.parser.parse
import io.circe.syntax.*
import nebflow.core.{NebflowLogger, PathUtil}

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

/**
 * 记忆队列 —— append-only JSONL 记账面（记忆改造批 2026-09-12，IMPL-1 / spec §5 R1 O-A）。
 *
 * **落点**：`<dataRoot>/memory/queue.jsonl`（生产 = `~/.nebflow/memory/queue.jsonl`），
 * 归档单代 `queue.1.jsonl`；运行时数据层（不进 git、不进任何注入）。形态复用
 * `JsonlLedger`（= `TaskListHistory` 先例的读写/轮转基元）。
 *
 * **两类记录 + 三类被动记录**：
 *   - `note`：记账一条待执行的记忆变更（幂等键 = target+action+section+match+content
 *     的 sha256；`atMs` 必带）。
 *   - `outcome`：消费者回写一条 note 的结局（applied / modified / rejected /
 *     obsolete / deduped / timeout）。
 *   - `drop`：容量兜底记录（pending 超顶时按 `atMs` 保留最近 N，被弃者逐 id 入档
 *     ——**禁静默丢**，spec §5 R1「代价与必做」）。
 *   - 崩溃留下的半行：读取侧计入 `unreadable` 并跳过，**不影响折叠**（append-only
 *     的崩溃安全语义）。
 *
 * **折叠谓词**：pending = 有 `note` 且**无**对应 `outcome` 且**未**被 `drop` 记录
 * 引用的条目。ids 唯一（`q-<atMs>-<n>`），故折叠无需顺序假设。
 *
 * **容量上限**：pending ≤ [[MaxPending]]（500）。超限由写入侧（同临界区内）按
 * `atMs` 保留最近 N，其余写一条 `drop` 记录（含被弃 id 全量）。
 *
 * **幂等**：同 hash 已在 pending ⇒ **不新增**，返回既有 `q-id`（spec §5 R1/R8
 * 双层去重的第一层）。
 *
 * **变更史**：每次入队/回写 outcome 同时落一行 [[MemoryHistory]]（引擎代写 at/actor，
 * 与 note/outcome 以 `ref` 对账）。history 写失败**不失败主操作**，但返回的
 * `historyNote` 非空（调用方把它带进结果文本，绝不静默）。
 *
 * 并行：进程内单锁（`synchronized`）串行化「读折叠 → 追加 → 容量兜底」整段；
 * 跨进程写者不在锁面内（记忆文件本身亦无跨进程锁，spec §3.6 已裁定接受该风险）。
 */
object MemoryQueue:

  private val logger = NebflowLogger.forName("nebflow.memory.queue")

  val FileName: String        = "queue.jsonl"
  val ArchiveFileName: String = "queue.1.jsonl"

  /** `def` 非 `val`：`PathUtil.dataRoot` 可被测试换根（同 TaskListHistory 陷阱）。 */
  def queuePath: os.Path   = PathUtil.dataRoot / "memory" / FileName
  def archivePath: os.Path = PathUtil.dataRoot / "memory" / ArchiveFileName

  /** pending 容量上限（spec §5 R1 建议值：500）。 */
  val MaxPending: Int = 500

  /** 轮转阈值（活动文件，先到为准）。 */
  val MaxActiveBytes: Long = 5L * 1024 * 1024
  val MaxActiveLines: Int  = 20000

  // ── 值域（消费方按常量引用，不写裸字面量） ──────────────────────

  val KindNote    = "note"
  val KindOutcome = "outcome"
  val KindDrop    = "drop"

  val ResultApplied  = "applied"
  val ResultModified = "modified"
  val ResultRejected = "rejected"
  val ResultObsolete = "obsolete"
  val ResultDeduped  = "deduped"
  val ResultTimeout  = "timeout"

  val TriggerCompaction = "compaction"
  val TriggerManual     = "manual"
  val TriggerReview     = "review"
  val TriggerDream      = "dream"

  // ── 记录模型 ────────────────────────────────────────────────────

  final case class Note(
    id: String,
    atMs: Long,
    at: String,
    target: String,
    action: String,
    section: Option[String],
    matchText: Option[String],
    content: Option[String],
    sessionId: Option[String],
    trigger: String
  ):
    /** 幂等键（同规于 `enqueue` 的入参面）。 */
    def hash: String = MemoryQueue.hashOf(target, action, section, matchText, content)

  final case class Outcome(
    ref: String,
    atMs: Long,
    at: String,
    result: String,
    by: String,
    detail: String
  )

  /** 队列状态（折叠的输入面，纯数据）。 */
  final case class State(
    notes: Vector[Note],
    outcomes: Vector[Outcome],
    droppedRefs: Set[String],
    droppedTotal: Int,
    unreadable: Int
  ):
    def pending: Vector[Note] =
      notes.filterNot(n => outcomes.exists(_.ref == n.id) || droppedRefs.contains(n.id))
    def pendingCount: Int = pending.size
    def outcomeRefs: List[String] = outcomes.map(_.ref).toList

  object State:
    val empty: State = State(Vector.empty, Vector.empty, Set.empty, 0, 0)

  /** 入队结果：`deduped` = 命中 pending 幂等键（未新增行）；`dropped` = 本次因容量
    * 上限被弃的条目数；`historyNote` 非空 = 变更史写失败（主操作已成功）。 */
  final case class EnqueueResult(
    id: String,
    deduped: Boolean,
    dropped: Int,
    pending: Int,
    historyNote: String = ""
  )

  // ── 幂等键 ──────────────────────────────────────────────────────

  /** `hash(target+action+section+match+content)`（spec §5 R1 原文口径）。空字段
    * 归一为 `-`，各段以 `\u0001` 分隔（防拼接歧义）。 */
  def hashOf(
    target: String,
    action: String,
    section: Option[String],
    matchText: Option[String],
    content: Option[String]
  ): String =
    val raw = List(target, action, section.getOrElse(""), matchText.getOrElse(""), content.getOrElse(""))
      .map(_.trim)
      .mkString("\u0001")
    MessageDigest
      .getInstance("SHA-256")
      .digest(raw.getBytes(StandardCharsets.UTF_8))
      .map("%02x".format(_))
      .mkString

  // ── 时间 / id ───────────────────────────────────────────────────

  private def nowMs(): Long = System.currentTimeMillis()
  private def isoOf(ms: Long): String = Instant.ofEpochMilli(ms).toString

  /** id = `q-<atMs>-<n>`（同毫秒内序数，从既有行现算 ⇒ 无跨进程计数器）。 */
  private def nextId(atMs: Long, notes: Vector[Note]): String =
    val prefix = s"q-$atMs-"
    val maxN = notes
      .map(_.id)
      .filter(_.startsWith(prefix))
      .flatMap(_.stripPrefix(prefix).toIntOption)
      .maxOption
      .getOrElse(0)
    s"$prefix${maxN + 1}"

  // ── 编解码 ──────────────────────────────────────────────────────

  private def obj(fields: (String, Json)*): Json =
    Json.fromJsonObject(JsonObject.fromIterable(fields.toList.filterNot((_, v) => v.isNull)))
  private def optStr(v: Option[String]): Json = v.filter(_.nonEmpty).map(_.asJson).getOrElse(Json.Null)

  private[tools] def encodeNote(n: Note): Json =
    obj(
      "kind"    -> KindNote.asJson,
      "id"      -> n.id.asJson,
      "atMs"    -> n.atMs.asJson,
      "at"      -> n.at.asJson,
      "target"  -> n.target.asJson,
      "action"  -> n.action.asJson,
      "section" -> optStr(n.section),
      "match"   -> optStr(n.matchText),
      "content" -> optStr(n.content),
      "source"  -> obj("sessionId" -> optStr(n.sessionId), "trigger" -> n.trigger.asJson)
    )

  private[tools] def encodeOutcome(o: Outcome): Json =
    obj(
      "kind"   -> KindOutcome.asJson,
      "ref"    -> o.ref.asJson,
      "atMs"   -> o.atMs.asJson,
      "at"     -> o.at.asJson,
      "result" -> o.result.asJson,
      "by"     -> o.by.asJson,
      "detail" -> o.detail.asJson
    )

  private[tools] def encodeDrop(atMs: Long, refs: List[String]): Json =
    obj(
      "kind"    -> KindDrop.asJson,
      "atMs"    -> atMs.asJson,
      "at"      -> isoOf(atMs).asJson,
      "dropped" -> refs.size.asJson,
      "refs"    -> refs.asJson,
      "detail"  -> s"pending cap $MaxPending exceeded — oldest ${refs.size} note(s) dropped by atMs (never silent)".asJson
    )

  private def field(j: Json, k: String): Option[Json] = j.asObject.flatMap(_(k))
  private def str(j: Json, k: String): Option[String]  = field(j, k).flatMap(_.asString)
  private def long(j: Json, k: String): Long           = field(j, k).flatMap(_.asNumber).flatMap(_.toLong).getOrElse(0L)
  private def strs(j: Json, k: String): List[String] =
    field(j, k).flatMap(_.asArray).map(_.flatMap(_.asString).toList).getOrElse(Nil)

  private[tools] def decodeNote(j: Json): Option[Note] =
    for
      id <- str(j, "id")
      target <- str(j, "target")
      action <- str(j, "action")
    yield Note(
      id = id,
      atMs = long(j, "atMs"),
      at = str(j, "at").getOrElse(""),
      target = target,
      action = action,
      section = str(j, "section"),
      matchText = str(j, "match"),
      content = str(j, "content"),
      sessionId = field(j, "source").flatMap(s => str(s, "sessionId")),
      trigger = field(j, "source").flatMap(s => str(s, "trigger")).getOrElse(TriggerManual)
    )

  private[tools] def decodeOutcome(j: Json): Option[Outcome] =
    for
      ref <- str(j, "ref")
      result <- str(j, "result")
    yield Outcome(
      ref = ref,
      atMs = long(j, "atMs"),
      at = str(j, "at").getOrElse(""),
      result = result,
      by = str(j, "by").getOrElse(""),
      detail = str(j, "detail").getOrElse("")
    )

  // ── 读 + 折叠 ───────────────────────────────────────────────────

  /** 全量读取 + 折叠输入面（两代文件，升序）。坏行计入 `unreadable`，**不臆测**。 */
  def readState(): State =
    val back = JsonlLedger.readLines(queuePath, archivePath)
    var unreadable = 0
    val notes = Vector.newBuilder[Note]
    val outcomes = Vector.newBuilder[Outcome]
    var droppedRefs = Set.empty[String]
    var droppedTotal = 0
    back.lines.foreach { l =>
      val t = l.trim
      if !(t.startsWith("{") && t.endsWith("}")) then unreadable += 1
      else
        parse(t).toOption.flatMap(_.asObject) match
          case Some(o) =>
            str(Json.fromJsonObject(o), "kind") match
              case Some(KindNote) =>
                decodeNote(Json.fromJsonObject(o)) match
                  case Some(n) => notes += n
                  case None    => unreadable += 1
              case Some(KindOutcome) =>
                decodeOutcome(Json.fromJsonObject(o)) match
                  case Some(out) => outcomes += out
                  case None      => unreadable += 1
              case Some(KindDrop) =>
                val refs = strs(Json.fromJsonObject(o), "refs")
                droppedRefs = droppedRefs ++ refs
                droppedTotal += field(Json.fromJsonObject(o), "dropped").flatMap(_.asNumber).flatMap(_.toInt).getOrElse(refs.size)
              case Some(_) => () // 未知 kind：注册式扩展，照收不拒（TaskListHistory 口径）
              case None    => unreadable += 1
          case None => unreadable += 1
    }
    State(notes.result(), outcomes.result(), droppedRefs, droppedTotal, unreadable)

  def pendingNotes(): Vector[Note] = readState().pending

  def pendingCount(): Int = readState().pendingCount

  // ── 写 ──────────────────────────────────────────────────────────

  private val lock = new Object

  /** 入队（幂等 + 容量兜底 + 变更史）。**不抛异常**：写失败返回 Left，调用方
    * 结构化报错（`MEMORYEDIT` 域），绝不静默成功。 */
  def enqueue(
    target: String,
    action: String,
    section: Option[String],
    matchText: Option[String],
    content: Option[String],
    sessionId: Option[String],
    trigger: String,
    actor: String
  ): Either[String, EnqueueResult] =
    lock.synchronized {
      val state = readState()
      val key   = hashOf(target, action, section, matchText, content)
      state.pending.find(_.hash == key) match
        case Some(existing) =>
          Right(EnqueueResult(existing.id, deduped = true, dropped = 0, pending = state.pendingCount))
        case None =>
          val atMs = nowMs()
          val note = Note(
            id = nextId(atMs, state.notes),
            atMs = atMs,
            at = isoOf(atMs),
            target = target,
            action = action,
            section = section.filter(_.nonEmpty),
            matchText = matchText.filter(_.nonEmpty),
            content = content,
            sessionId = sessionId.filter(_.nonEmpty),
            trigger = trigger
          )
          JsonlLedger.appendLine(queuePath, archivePath, encodeNote(note).asJson.noSpaces, MaxActiveBytes, MaxActiveLines) match
            case Left(reason) =>
              logger.warnSync(s"[memory-queue] enqueue failed: $reason")
              Left(reason)
            case Right(_) =>
              val histErr = MemoryHistory
                .appendQueue(note.id, atMs, actor, note.target, note.action, note.section, note.matchText, note.content, note.sessionId, note.trigger)
                .left
                .toOption
              // 容量兜底：pending 超顶 ⇒ 按 atMs 保留最近 N，被弃者逐 id 入 drop 记录
              val pendingAfter = state.pending :+ note
              val excess =
                if pendingAfter.size > MaxPending then
                  pendingAfter.sortBy(n => (n.atMs, n.id)).take(pendingAfter.size - MaxPending).toList
                else Nil
              var dropErr: Option[String] = None
              if excess.nonEmpty then
                JsonlLedger.appendLine(
                  queuePath,
                  archivePath,
                  encodeDrop(nowMs(), excess.map(_.id)).asJson.noSpaces,
                  MaxActiveBytes,
                  MaxActiveLines
                ) match
                  case Left(reason) =>
                    dropErr = Some(reason)
                    logger.warnSync(s"[memory-queue] drop record append failed: $reason")
                  case Right(_) => ()
              val notes = List(histErr.map(e => s"history append failed ($e)"), dropErr.map(e => s"drop record append failed ($e)")).flatten
              Right(
                EnqueueResult(
                  id = note.id,
                  deduped = false,
                  dropped = excess.size,
                  pending = pendingAfter.size - excess.size,
                  historyNote = notes.mkString("; ")
                )
              )
    }

  /** 回写一条 outcome（消费结局）。**不抛异常**：失败返回 Left。 */
  def recordOutcome(
    ref: String,
    result: String,
    by: String,
    detail: String,
    actor: String = MemoryHistory.ActorUnknown
  ): Either[String, Outcome] =
    lock.synchronized {
      val note = readState().notes.find(_.id == ref)
      val atMs = nowMs()
      val outcome = Outcome(ref = ref, atMs = atMs, at = isoOf(atMs), result = result, by = by, detail = detail)
      JsonlLedger.appendLine(queuePath, archivePath, encodeOutcome(outcome).asJson.noSpaces, MaxActiveBytes, MaxActiveLines) match
        case Left(reason) =>
          logger.warnSync(s"[memory-queue] outcome append failed ($ref): $reason")
          Left(reason)
        case Right(_) =>
          MemoryHistory
            .appendConsume(
              ref = ref,
              atMs = atMs,
              actor = actor,
              target = note.map(_.target).getOrElse(""),
              action = note.map(_.action).getOrElse(""),
              content = note.flatMap(_.content),
              result = result,
              by = by,
              detail = detail
            )
            .left
            .foreach(e => logger.warnSync(s"[memory-queue] history consume append failed ($ref): $e"))
          Right(outcome)
    }

  /** 批量回写同一结局（整理轨失败/超时的降级路径：队列条目保留 + 逐条留痕）。 */
  def recordOutcomes(refs: List[String], result: String, by: String, detail: String): List[Either[String, Outcome]] =
    refs.map(r => recordOutcome(r, result, by, detail, by))

  // ── 注入摘要行 ──────────────────────────────────────────────────

  /** 生命周期注入一行（`ContextRefresher.buildMemoryBlock` 消费，MemoryHygieneSignal
    * 先例）：pending 为 0 且无弃置记录 ⇒ 空串（不留常驻噪声行）。
    * 不新增只读回看工具（spec §5 R2「Nebula 能否读回队列」）。 */
  def summaryLine(): String =
    try
      val s = readState()
      val n = s.pendingCount
      val drops = if s.droppedTotal > 0 then s" (${s.droppedTotal} dropped by the $MaxPending-pending cap)" else ""
      val last = s.outcomes.lastOption.map(o => s"last outcome: ${o.result} (${o.ref})")
      if n == 0 && s.droppedTotal == 0 then ""
      else
        val head = s"Memory queue: $n pending note(s) awaiting consolidation — applied at the next compaction, not at write time$drops."
        List(Some(head), last).flatten.mkString(" ")
    catch case e: Exception => ""

end MemoryQueue
