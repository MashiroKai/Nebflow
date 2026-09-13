package nebflow.core.tools

import io.circe.{Json, JsonObject}
import io.circe.parser.parse
import io.circe.syntax.*
import nebflow.core.{NebflowLogger, PathUtil}
import nebflow.service.MemoryBudget

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
 *     obsolete / deduped / timeout / notrun / blocked；值域见 [[TerminalResults]] /
 *     [[RetryableResults]]）。
 *   - `drop`：容量兜底记录（pending 超顶时按 `atMs` 保留最近 N，被弃者逐 id 入档
 *     ——**禁静默丢**，spec §5 R1「代价与必做」）。
 *   - 崩溃留下的半行：读取侧计入 `unreadable` 并跳过，**不影响折叠**（append-only
 *     的崩溃安全语义）。
 *
 * **折叠谓词**（2026-09-13 缺失自愈批修订，恢复 spec §5 R3 档 1 语义）：pending =
 * 有 `note` 且**未**被 `drop` 记录引用，且**末条 outcome 不是终态**——即
 * 「无 outcome ∨ 末条结局 ∈ {notrun, timeout, rejected, blocked}」。终态
 * （applied / modified / obsolete / deduped）= 消费者**确实跑过**并判定已了结。
 *
 * 旧实现「有 outcome 即永不 pending」是「重试引线是死的」这条缺陷的一半：spec §5
 * R3 档 1 的档 1 逐字要求「队列条目保留（未消费条目不动）⇒ 下次压缩重试」，而
 * 引擎侧 infra 失败代笔写下的结局把那句话变成了空头承诺（取证件 §0-3）。未知结局
 * 值同样按「未闭合」处理（宁保留勿静默丢）。ids 唯一（`q-<atMs>-<n>`），故折叠
 * 无需顺序假设。
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

  /** 引擎侧**超时对账**结局（C 批 ④）：硬超时截断后，引擎用**跑后**文件内容逐条判
    * 「效果是否已在盘上」，确凿者由**引擎**标终态（[[MemoryQueue.reconcile]]）。
    *
    * **独立字样是作者硬约束**：审计必须一眼分清「谁判的」——`applied` / `modified` /
    * `obsolete` / `deduped` 的裁决者是消费者（确实跑过并判定），本字样只可能由引擎在
    * 超时对账路径写下（`by=memory-consolidator` + `detail` 前缀 [[ReconcileDetailPrefix]]
    * 双保险）。判据只取高精度两支（逐字行命中 / 定位键消失），**宁漏不误**：判不准的
    * 照旧写 [[ResultTimeout]] 留 pending 重试。 */
  val ResultAppliedByReconcile = "applied-by-reconcile"

  /** 引擎侧 infra 结局（2026-09-13 缺失自愈批 / 方案 D）：消费链**根本没跑**——
    * 定义缺失 / 前置闸拒绝 / spawn 失败 / 引擎前置不满足。这**不是**消费者的裁决，
    * 故一律不用 `rejected` 冒充（作者令：`rejected` 只许用于「消费者确实跑过并判定
    * 不可落」）。语义 = 「本轮未消费，条目留 pending，下轮重试」。 */
  val ResultNotRun = "notrun"

  /** infra 连续未跑达上限后的静默档（[[MaxInfraOutcomesPerRef]]）：条目**仍 pending**
    * （重试引线在），只是引擎不再逐轮追加 infra 结局行——把「不再烧积压」同时兑现为
    * 「不再烧队列容量」（修复前每轮 191 行）。 */
  val ResultBlocked = "blocked"

  /** 终态结局 = 唯一能闭合一条 note 的结局（消费者确实跑过并判定已了结）。
    *
    * C 批 ④（2026-09-13）：[[ResultAppliedByReconcile]]（引擎超时对账判的）加入本集合。
    * **向后安全（作者硬约束 iii）**：本集合之外的值（含本字样下旧 jar 不认识的未知值）
    * 一律走「**未闭合**」分支（见 [[State.consumed]]：`TerminalResults.contains` 为假 ⇒
    * 条目仍 pending）⇒ 升级/回滚两个方向都不会丢记账、不会误闭合。 */
  val TerminalResults: Set[String] =
    Set(ResultApplied, ResultModified, ResultObsolete, ResultDeduped, ResultAppliedByReconcile)

  /** 可重试结局（spec §5 R3 档 1「队列条目保留 ⇒ 下次压缩重试」）。末条结局落在本集合
    * 内的 note **仍是 pending**。
    *
    * `rejected` 为何在此：消费侧 `system.md:39` 定义 `rejected` =「本次定位失败但含义
    * 仍在（节改名 / 条目被改写）」——**本就是暂态、本该重试**；把它当终态的正是同一
    * 缺陷的另一半（降级路径用 rejected 代笔 infra 失败 + 折叠谓词把 rejected 当终态）。
    * 「含义已消失 / 已实现于别处」走 `obsolete`（终态），两者分工不变。 */
  val RetryableResults: Set[String] =
    Set(ResultNotRun, ResultTimeout, ResultRejected, ResultBlocked)

  /** 单条 note 的 infra 结局写入上限（`notrun`/`timeout`/`blocked` 计数）——达上限后
    * 只补一条 `blocked` 然后闭嘴，条目仍 pending。防「修复前每轮 191 行」的膨胀。 */
  val MaxInfraOutcomesPerRef: Int = 3

  /** 引擎 infra 未跑档（写入计数用；`rejected` 是**消费者裁决**，不计入本集合）。 */
  val EngineInfraResults: Set[String] = Set(ResultNotRun, ResultTimeout, ResultBlocked)

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
    /** ref → 末条结局（`outcomes` 保 append 序 ⇒ 后写者胜）。 */
    lazy val lastOutcomeByRef: Map[String, Outcome] =
      outcomes.foldLeft(Map.empty[String, Outcome])((m, o) => m.updated(o.ref, o))

    /** 已闭合 = 末条结局是终态（消费者确实跑过并判定已了结）。无结局 / 末条属可重试类 /
      * 未知结局值 ⇒ 未闭合（宁保留勿静默丢）。 */
    def consumed(n: Note): Boolean =
      lastOutcomeByRef.get(n.id).exists(o => TerminalResults.contains(o.result))

    /** 折叠谓词（见类头注）：未闭合且未被 drop 引用。 */
    def pending: Vector[Note] =
      notes.filterNot(n => droppedRefs.contains(n.id) || consumed(n))

    def pendingCount: Int = pending.size

    /** 末条结局为 infra 未跑档（`notrun`/`blocked`）的 pending 条数——注入行与告警的
      * 判据（「消费链没跑」而不是「消费者裁定不可落」）。 */
    def notRunPendingCount: Int =
      pending.count(n => lastOutcomeByRef.get(n.id).exists(o =>
        o.result == ResultNotRun || o.result == ResultBlocked))

    /** 最老 pending 条目的 atMs（无 pending ⇒ None）——注入行报「堆积了多久」。 */
    def oldestPendingAtMs: Option[Long] =
      val p = pending
      if p.isEmpty then None else Some(p.map(_.atMs).min)

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
    parseState(JsonlLedger.readLines(queuePath, archivePath).lines)

  /** 同 [[readState]]，但行来自调用方（纯函数；dry-run 与 spec 对任意文本直测用）。
    * `readState` 只负责取行，折叠语义单点在此。 */
  def parseState(lines: Vector[String]): State =
    var unreadable = 0
    val notes = Vector.newBuilder[Note]
    val outcomes = Vector.newBuilder[Outcome]
    var droppedRefs = Set.empty[String]
    var droppedTotal = 0
    lines.foreach { l =>
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

  // ── 引擎侧只读 dry-run（方案 E/D1，2026-09-13 缺失自愈批）──────────
  //
  // 定位语义**单源**：本段是引擎侧权威定位（首个含 `match` 的 `- ` 条目；给 `section`
  // 时限定该节；节名 `## ` 前缀可选），消费侧提示词 `agents/memory-consolidator/system.md`
  // 引用同一口径、不另起一套。D0（只读脚本）与本实现互为独立复算面，分桶须逐条一致。

  /** 分桶（方案 §5 D1 的三段输出）。`WouldDefer` = 预算 fail-closed 截断
    * （超硬顶即停、剩余留 pending）。 */
  enum Bucket:
    case WouldApply, WouldObsolete, WouldDefer

  object Bucket:
    def label(b: Bucket): String = b match
      case WouldApply    => "would-apply"
      case WouldObsolete => "would-obsolete"
      case WouldDefer    => "would-defer"

  /** dry-run 的输入面：某目标**当前磁盘内容**的只读快照（`path` 仅供渲染）。 */
  final case class TargetFile(path: String, content: String)

  final case class PlanItem(
      ref: String,
      target: String,
      action: String,
      atMs: Long,
      bucket: Bucket,
      detail: String,
      line: Option[Int],
      deltaBytes: Long
  )

  final case class TargetProjection(
      target: String,
      path: String,
      beforeBytes: Long,
      projectedBytes: Long,
      fullProjectedBytes: Long,
      softCap: Long,
      hardCap: Long
  ):
    def delta: Long          = projectedBytes - beforeBytes
    def fullDelta: Long      = fullProjectedBytes - beforeBytes
    def overHard: Boolean    = projectedBytes > hardCap
    def fullOverHard: Boolean = fullProjectedBytes > hardCap

  /** dry-run 计划：逐条分桶 + 逐文件投影 + 前置闸结论。
    *
    * `authorized` = 本落轮**允许消费**的 ref（would-apply + would-obsolete；后者零文件
    * 写、只回写结局），`deferred` = 预算截断的 ref（**不进简报、保持 pending**），
    * `refusal` 非空 = 前置闸 fail-closed 拒绝本轮落地（authorized 为空而仍有 pending）。 */
  final case class Plan(
      items: Vector[PlanItem],
      projections: Vector[TargetProjection],
      authorized: Vector[String],
      deferred: Vector[String],
      refusal: Option[String]
  ):
    def refsOf(b: Bucket): Vector[String] = items.filter(_.bucket == b).map(_.ref)
    def countOf(b: Bucket): Int           = items.count(_.bucket == b)

    /** 三段结构化文本（进日志；dry-run 模式下同时是实测输出）。 */
    def render(maxPerBucket: Int = 400): String =
      val sb = new StringBuilder
      sb ++= s"memory queue plan (read-only): pending=${items.size} "
      sb ++= s"${Bucket.label(Bucket.WouldApply)}=${countOf(Bucket.WouldApply)} "
      sb ++= s"${Bucket.label(Bucket.WouldObsolete)}=${countOf(Bucket.WouldObsolete)} "
      sb ++= s"${Bucket.label(Bucket.WouldDefer)}=${countOf(Bucket.WouldDefer)}\n"
      sb ++= s"GATE: ${refusal.getOrElse("landable (no item deferred by the budget cap)")}\n"
      List(
        Bucket.WouldApply -> "will be landed (authorized)",
        Bucket.WouldObsolete -> "NOT landed — verdict only (obsolete/rejected are the consumer's call)",
        Bucket.WouldDefer -> "NOT authorized this round — stays pending (budget fail-closed)"
      ).foreach { (b, why) =>
        val rows = items.filter(_.bucket == b)
        sb ++= s"-- ${Bucket.label(b)} (${rows.size}) — $why\n"
        rows.take(maxPerBucket).foreach { i =>
          val loc = i.line.map(l => s" line=$l").getOrElse("")
          val d   = if i.deltaBytes == 0L then "" else s" Δ${i.deltaBytes}B"
          sb ++= s"   ${i.ref} ${i.target} ${i.action}$loc$d — ${i.detail}\n"
        }
        if rows.size > maxPerBucket then sb ++= s"   …(${rows.size - maxPerBucket} more)\n"
      }
      sb ++= "-- projected bytes (per file; \"authorized\" = the capped landing prefix, \"full set\" = if every would-apply item landed)\n"
      projections.foreach { p =>
        val warn = if p.overHard then "  ** OVER HARD CAP **" else if p.projectedBytes > p.softCap then "  (over soft line)" else ""
        val fullWarn =
          if p.fullOverHard then s"  ** FULL SET OVER HARD by ${p.fullProjectedBytes - p.hardCap}B **"
          else if p.fullProjectedBytes > p.softCap then "  (full set over soft line)"
          else ""
        sb ++= f"   ${p.target}%-22s ${p.path}%s  ${p.beforeBytes}%,d → ${p.projectedBytes}%,d (auth ${p.delta}%+,d) | full ${p.fullProjectedBytes}%,d (${p.fullDelta}%+,d) | hard=${p.hardCap}%,d$warn$fullWarn\n"
      }
      sb.result()

  /** 预算常量按 target 维度（与 [[nebflow.service.MemoryBudget]] 同源，数值不复制）。 */
  private def capsOf(target: String): (Long, Long) =
    if target == "user" then (MemoryBudget.UserSoftBytes, MemoryBudget.UserHardBytes)
    else if target == "agent" then (MemoryBudget.AgentSoftBytes, MemoryBudget.AgentHardBytes)
    else (MemoryBudget.ProjectSoftBytes, MemoryBudget.ProjectHardBytes)

  private def utf8Bytes(s: String): Long =
    s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length.toLong

  private def splitLines(s: String): Vector[String] = s.split("\n", -1).toVector

  /** 节区间 `[start, end)`（`start` = 节标题行；`## ` 前缀在参数里可选）。
    * 节不存在 ⇒ None。 */
  private[tools] def sectionBounds(lines: Vector[String], section: String): Option[(Int, Int)] =
    val sec = section.trim.stripPrefix("## ").trim
    val hdr = s"## $sec"
    val start = lines.indexWhere(_.trim == hdr)
    if start < 0 then None
    else Some((start, (start + 1 until lines.length).find(i => lines(i).startsWith("## ")).getOrElse(lines.length)))

  /** 定位：首个含 `match` 的 `- ` 条目行（给 `section` 时限定该节）。返回 (行号 1-based, 原文)。 */
  private[tools] def locate(
      lines: Vector[String],
      section: Option[String],
      matchText: String
  ): Option[(Int, String)] =
    val bounds = section match
      case None    => Some((0, lines.length))
      case Some(s) => sectionBounds(lines, s)
    bounds.flatMap { (s, e) =>
      (s until e).find(i => lines(i).startsWith("- ") && lines(i).contains(matchText)).map(i => (i + 1, lines(i)))
    }

  /** 「逐字行已在文件」判据（**单源**：`plan` 的 `already-present` 桶与超时对账 ④ 共用）。
    * 判据 = **整行 trim 后逐字相等**（精确行匹配：误杀率 0、漏检率非 0 ⇒ 宁漏不误；
    * 前缀匹配会误杀，故不用）。 */
  private[tools] def alreadyPresentLine(lines: Vector[String], content: String): Boolean =
    val t = content.trim
    lines.exists(_.trim == t)

  /** 单条操作应用到内容（纯函数）。定位不到 / 节不存在 ⇒ None（不臆测、不硬改）。 */
  private def applyOp(
      content: String,
      action: String,
      section: Option[String],
      matchText: Option[String],
      newText: Option[String]
  ): Option[String] =
    val lines = splitLines(content)
    action match
      case "append" =>
        newText match
          case None => None
          case Some(t) =>
            section match
              case None =>
                val body = if content.endsWith("\n") then content.dropRight(1) else content
                Some(if body.isEmpty then s"$t\n" else s"$body\n$t\n")
              case Some(s) => sectionBounds(lines, s).map((_, e) => lines.patch(e, Vector(t), 0).mkString("\n"))
      case "update" =>
        for
          t <- newText
          m <- matchText
          (ln, _) <- locate(lines, section, m)
        yield lines.updated(ln - 1, t).mkString("\n")
      case "remove" =>
        matchText.flatMap(m => locate(lines, section, m)).map((ln, _) => lines.patch(ln - 1, Nil, 1).mkString("\n"))
      case "replace_section" =>
        (section, newText) match
          case (Some(s), Some(t)) =>
            // 节标题保留，节体（start+1 .. end）整段替换（null-ary 前缀可选⇒同规）
            sectionBounds(lines, s).map((start, e) => lines.patch(start + 1, splitLines(t), e - start - 1).mkString("\n"))
          case _ => None
      case _ => None

  /** 落地顺序偏序（atMs, id）——取代检测的「后到者」判据。 */
  private def after(later: Note, earlier: Note): Boolean =
    later.atMs > earlier.atMs || (later.atMs == earlier.atMs && later.id > earlier.id)

  /** 引擎侧只读 dry-run：对 `state.pending` 逐条定位 + 分桶 + 逐文件投影，产出落地前置闸。
    *
    * **零写入**：本方法不落任何文件、不改队列、不写结局。fail-closed 语义 = 逐条按
    * 落地顺序（§8.2 硬顺序：收缩 → update → append）累计投影，一旦某条会把该文件推过
    * **硬顶** ⇒ 该条起（含）该文件后续全部条目转 `would-defer`（**超硬顶即停，剩余留
    * pending**）；authorized 为空而仍有 pending ⇒ `refusal` 非空（本轮拒绝落地）。
    *
    * 取代检测（与 D0 机械近似同规）：同一 (target, located line) 上多条 update/remove
    * 只有**末条**有效、其余 `would-obsolete`；append 的 content 被同目标后续 remove 的
    * match 命中 ⇒ 同样 `would-obsolete`。 */
  def plan(state: State, files: Map[String, TargetFile]): Plan =
    planWith(state, files, enforceBudgetCap = true)

  /** 无闸全量投影（对照面）：同一套定位/分桶逻辑、只关掉预算停点——「若不加闸一次全量
    * 落地会到多少字节」的机械复算（方案件 §3.2 同口径），与带闸的 authorized 投影并列
    * 展示，供互证与爆炸半径复核。 */
  private def planWith(state: State, files: Map[String, TargetFile], enforceBudgetCap: Boolean): Plan =
    val notes = state.pending
    def phase(n: Note): Int = n.action match
      case "remove" | "replace_section" => 0
      case "update"                     => 1
      case "append"                     => 2
      case _                            => 3
    val ordered = notes.sortBy(n => (phase(n), n.atMs, n.id))

    // ── 静态定位（原盘内容；取代检测的行号基准）──
    val staticLocate: Map[String, Option[(Int, String)]] = ordered.map { n =>
      val lines = splitLines(files.get(n.target).map(_.content).getOrElse(""))
      n.id -> (n.action match
        case "update" | "remove" => locate(lines, n.section, n.matchText.getOrElse("\u0000"))
        case _                   => None)
    }.toMap
    val byLine: Map[(String, Int), Vector[String]] =
      ordered
        .filter(n => n.action == "update" || n.action == "remove")
        .flatMap(n => staticLocate.getOrElse(n.id, None).map((ln, _) => (n.target, ln) -> n.id))
        .groupBy(_._1)
        .view
        .mapValues(_.map(_._2).toVector)
        .toMap
    val supersededByLine: Set[String] =
      byLine.values.filter(_.sizeIs > 1).flatMap(ids => ids.dropRight(1)).toSet
    val supersededByRemove: Set[String] =
      ordered.filter(_.action == "append").flatMap { a =>
        val body = a.content.getOrElse("")
        val hit = ordered.exists(b =>
          b.action == "remove" && b.target == a.target && after(b, a) &&
            b.matchText.exists(m => m.nonEmpty && body.contains(m)))
        if hit then Some(a.id) else None
      }.toSet

    // ── 逐条分桶 + 落地顺序预算闸（模拟；不落盘）──
    val sim      = scala.collection.mutable.Map.from(files.view.mapValues(_.content))
    val stopped  = scala.collection.mutable.Set.empty[String]
    val items    = Vector.newBuilder[PlanItem]

    ordered.foreach { n =>
      val caps        = capsOf(n.target)
      val fileOpt     = files.get(n.target)
      val targetLabel = fileOpt.map(_.path).getOrElse(n.target)
      def bucketOf(supersededDetail: String): PlanItem =
        PlanItem(n.id, n.target, n.action, n.atMs, Bucket.WouldObsolete, supersededDetail, None, 0L)
      val item: PlanItem =
        if fileOpt.isEmpty then
          PlanItem(n.id, n.target, n.action, n.atMs, Bucket.WouldObsolete, s"target-missing: no memory file for '$targetLabel'", None, 0L)
        else if supersededByLine.contains(n.id) then bucketOf("superseded-by-later: same located line, a later note wins")
        else if supersededByRemove.contains(n.id) then bucketOf("superseded-by-later: a later remove matches this append")
        else if stopped.contains(n.target) then
          PlanItem(n.id, n.target, n.action, n.atMs, Bucket.WouldDefer, s"budget: stopped earlier — projected bytes already over the hard cap (${caps._2} B)", None, 0L)
        else
          val cur    = sim.getOrElse(n.target, "")
          val lines  = splitLines(cur)
          val loc    = if n.action == "update" || n.action == "remove" then locate(lines, n.section, n.matchText.getOrElse("")) else None
          val secOk  = n.section.forall(s => sectionBounds(lines, s).isDefined)
          val dupApp = n.action == "append" && n.content.exists(c => alreadyPresentLine(lines, c))
          if (n.action == "update" || n.action == "remove") && loc.isEmpty then
            PlanItem(n.id, n.target, n.action, n.atMs, Bucket.WouldObsolete,
              if n.section.isDefined && !secOk then "locate-miss: section not found" else "locate-miss: no matching '- ' entry",
              None, 0L)
          else if n.action == "replace_section" && !secOk then
            PlanItem(n.id, n.target, n.action, n.atMs, Bucket.WouldObsolete, "locate-miss: section not found", None, 0L)
          else if n.action == "append" && !secOk then
            PlanItem(n.id, n.target, n.action, n.atMs, Bucket.WouldObsolete, "locate-miss: section not found", None, 0L)
          else if dupApp then
            PlanItem(n.id, n.target, n.action, n.atMs, Bucket.WouldObsolete, "already-present: an identical entry is already in the file (consumer verdict: deduped)", None, 0L)
          else
            applyOp(cur, n.action, n.section, n.matchText, n.content) match
              case None =>
                PlanItem(n.id, n.target, n.action, n.atMs, Bucket.WouldObsolete, "apply-miss: locator no longer resolves on the simulated content", loc.map(_._1), 0L)
              case Some(next) =>
                val projected = utf8Bytes(next)
                if enforceBudgetCap && projected > caps._2 then
                  stopped += n.target
                  PlanItem(n.id, n.target, n.action, n.atMs, Bucket.WouldDefer,
                    s"budget: applying would reach $projected B > hard cap ${caps._2} B — stopping here (remaining notes stay pending)",
                    loc.map(_._1), 0L)
                else
                  val delta = projected - utf8Bytes(cur)
                  sim.update(n.target, next)
                  PlanItem(n.id, n.target, n.action, n.atMs, Bucket.WouldApply, "located" + loc.map((ln, _) => s" at line $ln").getOrElse(""), loc.map(_._1), delta)
      items += item
    }

    val all       = items.result()
    val applyIds  = all.filter(_.bucket == Bucket.WouldApply).map(_.ref)
    val obsIds    = all.filter(_.bucket == Bucket.WouldObsolete).map(_.ref)
    val deferIds  = all.filter(_.bucket == Bucket.WouldDefer).map(_.ref)
    val authorized = applyIds ++ obsIds
    // 无闸对照面：同一逻辑关掉预算停点重跑一次，只取逐文件投影。
    val fullByTarget =
      if enforceBudgetCap then
        planWith(state, files, enforceBudgetCap = false).projections.map(p => p.target -> p.projectedBytes).toMap
      else Map.empty[String, Long]
    val projections = files.toVector.sortBy(_._1).map { (label, tf) =>
      val (soft, hard) = capsOf(label)
      val projected    = if sim.contains(label) then utf8Bytes(sim(label)) else utf8Bytes(tf.content)
      val full         = fullByTarget.getOrElse(label, projected)
      TargetProjection(label, tf.path, utf8Bytes(tf.content), projected, full, soft, hard)
    }
    val refusal =
      if notes.nonEmpty && authorized.isEmpty then
        val stop = all.find(_.bucket == Bucket.WouldDefer)
          .map(i => s"${i.ref} ${i.target}/${i.action}: ${i.detail}")
          .getOrElse("no landable item")
        Some(s"REFUSED (fail-closed): ${notes.size} pending note(s), none authorized — " +
          s"${deferIds.size} deferred by the budget cap (stopped at ${stop}) " +
          s"and ${all.count(_.bucket == Bucket.WouldObsolete)} not landable. Nothing was written; every note stays pending until the cap is relieved (shrink via remove/replace_section first).")
      else None
    Plan(all, projections, authorized, deferIds, refusal)

  // ── 引擎侧超时对账（C 批 ④，2026-09-13）───────────────────────────
  //
  // 背景（取证 plan §0-2 / §3.2）：硬超时截断后，消费者「尾部一次性回写 outcome」的
  // step 5 一步都没跑 ⇒ 273 条全留 pending，而其中 151 条的效果**已在文件里**。下一轮
  // 整批重投 ⇒ 已落 append 造重复行（append 不幂等）、未落条目无限空转。本段是那条缺陷
  // 的机械闭合面：**超时后先对账、再降级**。

  /** 对账 detail 前缀（审计锚之一：与 `by` 一起把「引擎判的」与「消费者判的」分开）。 */
  val ReconcileDetailPrefix: String = "reconcile:"

  /** 单条对账结论（纯数据）。 */
  final case class ReconcileVerdict(ref: String, target: String, action: String, result: String, detail: String)

  /** 对账报告（纯数据）：`closed` = 超时后用跑后文件判为**已了结**的条目。 */
  final case class ReconcileReport(closed: Vector[ReconcileVerdict]):
    def byRef: Map[String, ReconcileVerdict] = closed.map(v => v.ref -> v).toMap
    def refs: Set[String]                   = closed.map(_.ref).toSet
    def count: Int                          = closed.size
    /** 结论直方图（日志/事件渲染用）。 */
    def resultCounts: Map[String, Int] = closed.groupBy(_.result).view.mapValues(_.size).toMap
    /** 单行渲染（日志用）。 */
    def render: String =
      if closed.isEmpty then "none"
      else resultCounts.toVector.sortBy(_._1).map((r, n) => s"$r×$n").mkString(", ")

  object ReconcileReport:
    val empty: ReconcileReport = ReconcileReport(Vector.empty)

  /** **超时对账（纯函数、零写入、可单测）**：对每条 pending note 用**跑后**文件内容判
    * 「效果是否已在盘上」，只取作者硬约束里的**两支高精度判据**：
    *
    *   - `append ∧ content 行已在目标文件`（逐字行命中）⇒ 终态 `deduped`
    *     —— 与 [[plan]] 的 `already-present` 桶**同判据单源**（[[alreadyPresentLine]]）：
    *     再落一次只会造重复行；
    *   - `update ∧ content 行已在目标文件`（逐字行命中）⇒ 终态 `applied-by-reconcile`
    *     —— 目标效果已在盘上（旧文本被压缩式改写、新文本已落）。
    *     `match` 已消失而 `content` **不在**盘的形态**不判**（那是「落空」，交回 timeout 重试）；
    *   - `remove ∧ 定位键已消失`⇒ 终态 `applied-by-reconcile`（节点确已不在文件里）。
    *
    * **其余一律不判**：`replace_section`（整段替换无法用行命中判）、`content`/`match` 为空、
    * 目标层不在 `postFiles`（本轮没点名它 / 该层无记忆文件）、目标文件跑后为空串（无凭据 ⇒
    * 不下判），照旧写 `timeout` 留 pending 重试。判据**宁漏不误**：漏判的代价 = 下轮再重试
    * （与修复前同），误判的代价 = 把没落地的条目记成落地（记账失真）。
    *
    * 调用面：[[nebflow.agent.MemoryTrack]] 的 `finish` 在 Timeout 分支用它（跑后文件内容已
    * 在 `readAll` 手里，本函数不读盘）。 */
  def reconcile(state: State, postFiles: Map[String, TargetFile]): ReconcileReport =
    val closed = state.pending.flatMap { n =>
      postFiles.get(n.target).filter(_.content.trim.nonEmpty).flatMap { tf =>
        val lines = splitLines(tf.content)
        n.action match
          case "append" =>
            n.content
              .filter(_.trim.nonEmpty)
              .filter(c => alreadyPresentLine(lines, c))
              .map(_ =>
                ReconcileVerdict(
                  n.id,
                  n.target,
                  n.action,
                  ResultDeduped,
                  s"${ReconcileDetailPrefix} append: an identical line is already present in ${tf.path} — landing it again would only duplicate it"
                ))
          case "update" =>
            n.content
              .filter(_.trim.nonEmpty)
              .filter(c => alreadyPresentLine(lines, c))
              .map(_ =>
                ReconcileVerdict(
                  n.id,
                  n.target,
                  n.action,
                  ResultAppliedByReconcile,
                  s"${ReconcileDetailPrefix} update: the target line is already present in ${tf.path} — judged by the engine against the post-timeout file, not by the consumer"
                ))
          case "remove" =>
            n.matchText
              .filter(_.trim.nonEmpty)
              .filter(m => locate(lines, n.section, m).isEmpty)
              .map(_ =>
                ReconcileVerdict(
                  n.id,
                  n.target,
                  n.action,
                  ResultAppliedByReconcile,
                  s"${ReconcileDetailPrefix} remove: the located entry is already gone from ${tf.path} — judged by the engine against the post-timeout file"
                ))
          case _ => None
      }
    }
    ReconcileReport(closed)

  // ── 注入摘要行 ──────────────────────────────────────────────────

  /** 生命周期注入一行（`ContextRefresher.buildMemoryBlock` 消费，MemoryHygieneSignal
    * 先例）：pending 为 0 且无弃置记录 ⇒ 空串（不留常驻噪声行）。
    * 不新增只读回看工具（spec §5 R2「Nebula 能否读回队列」）。
    *
    * 2026-09-13 缺失自愈批（方案 D「响亮失败」）：注入行由「无条件承诺」改为**条件承诺**
    * ——后端照旧报 pending 数，另加两段可观测信息：① 最老 pending 的年龄（堆积了多久）；
    * ② 一旦存在 `notrun`/`blocked` 结局（= 消费链**根本没跑**，不是消费者裁定不可落）
    * 就在同一行挂 ALERT 段。没有这两段时行长与旧版逐字一致（不引入常驻噪声）。 */
  def summaryLine(): String =
    try
      val s = readState()
      val n = s.pendingCount
      val drops = if s.droppedTotal > 0 then s" (${s.droppedTotal} dropped by the $MaxPending-pending cap)" else ""
      val last = s.outcomes.lastOption.map(o => s"last outcome: ${o.result} (${o.ref})")
      if n == 0 && s.droppedTotal == 0 then ""
      else
        val age = s.oldestPendingAtMs.map(ms => s" (oldest ${ageLabel(System.currentTimeMillis() - ms)})").getOrElse("")
        val head = s"Memory queue: $n pending note(s)$age awaiting consolidation — applied at the next compaction, not at write time$drops."
        val alert =
          val notRun = s.notRunPendingCount
          if notRun == 0 then None
          else
            val causes = s.outcomes
              .filter(o => o.result == ResultNotRun || o.result == ResultBlocked)
              .groupBy(_.result)
              .view
              .map((r, os) => s"$r×${os.size}")
              .toVector
              .sorted
              .mkString(", ")
            Some(
              s"""ALERT: the memory-consolidation track is NOT consuming the queue — $notRun pending note(s) carry a
                 |not-run/blocked outcome ($causes), i.e. the consumer never ran (missing agent definition or an
                 |engine precondition), NOT a judgement that the entries are unlandable. Nothing is being applied and
                 |the queue only grows. Fix the consumption chain (see the gateway startup log) — the entries stay
                 |pending and will be retried on the next compaction.""".stripMargin.replace("\n", " "))
        List(Some(head), alert, last).flatten.mkString(" ")
    catch case e: Exception => ""

  /** 年龄渲染（注入行用；粗粒度即可，不引入时钟依赖）。 */
  private[tools] def ageLabel(ms: Long): String =
    val m = ms / 60000L
    if m < 60 then s"${m}m"
    else if m < 60 * 24 then s"${m / 60}h${m % 60}m"
    else s"${m / (60 * 24)}d${(m % (60 * 24)) / 60}h"

end MemoryQueue
