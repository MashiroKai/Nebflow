package nebflow.core.tools

import io.circe.{Json, JsonObject}
import io.circe.parser.parse
import io.circe.syntax.*
import nebflow.core.{NebflowLogger, PathUtil}
import nebflow.core.project.{ProjectDef, ProjectMemory, ProjectStore}
import nebflow.service.{MemoryBudget, MemoryStore, MemoryWriteGate}

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

  val FileName: String = "queue.jsonl"
  val ArchiveFileName: String = "queue.1.jsonl"

  /** `def` 非 `val`：`PathUtil.dataRoot` 可被测试换根（同 TaskListHistory 陷阱）。 */
  def queuePath: os.Path = PathUtil.dataRoot / "memory" / FileName
  def archivePath: os.Path = PathUtil.dataRoot / "memory" / ArchiveFileName

  /** pending 容量上限（spec §5 R1 建议值：500）。 */
  val MaxPending: Int = 500

  /** 轮转阈值（活动文件，先到为准）。 */
  val MaxActiveBytes: Long = 5L * 1024 * 1024
  val MaxActiveLines: Int = 20000

  // ── 值域（消费方按常量引用，不写裸字面量） ──────────────────────

  val KindNote = "note"
  val KindOutcome = "outcome"
  val KindDrop = "drop"

  val ResultApplied = "applied"
  val ResultModified = "modified"
  val ResultRejected = "rejected"
  val ResultObsolete = "obsolete"
  val ResultDeduped = "deduped"
  val ResultTimeout = "timeout"

  /**
   * 引擎侧**超时对账**结局（C 批 ④）：硬超时截断后，引擎用**跑后**文件内容逐条判
   * 「效果是否已在盘上」，确凿者由**引擎**标终态（[[MemoryQueue.reconcile]]）。
   *
   * **独立字样是作者硬约束**：审计必须一眼分清「谁判的」——`applied` / `modified` /
   * `obsolete` / `deduped` 的裁决者是消费者（确实跑过并判定），本字样只可能由引擎在
   * 超时对账路径写下（`by=memory-consolidator` + `detail` 前缀 [[ReconcileDetailPrefix]]
   * 双保险）。判据只取高精度两支（逐字行命中 / 定位键消失），**宁漏不误**：判不准的
   * 照旧写 [[ResultTimeout]] 留 pending 重试。
   */
  val ResultAppliedByReconcile = "applied-by-reconcile"

  /**
   * 引擎侧 infra 结局（2026-09-13 缺失自愈批 / 方案 D）：消费链**根本没跑**——
   * 定义缺失 / 前置闸拒绝 / spawn 失败 / 引擎前置不满足。这**不是**消费者的裁决，
   * 故一律不用 `rejected` 冒充（作者令：`rejected` 只许用于「消费者确实跑过并判定
   * 不可落」）。语义 = 「本轮未消费，条目留 pending，下轮重试」。
   */
  val ResultNotRun = "notrun"

  /**
   * infra 连续未跑达上限后的静默档（[[MaxInfraOutcomesPerRef]]）：条目**仍 pending**
   * （重试引线在），只是引擎不再逐轮追加 infra 结局行——把「不再烧积压」同时兑现为
   * 「不再烧队列容量」（修复前每轮 191 行）。
   */
  val ResultBlocked = "blocked"

  /**
   * 终态结局 = 唯一能闭合一条 note 的结局（消费者确实跑过并判定已了结）。
   *
   * C 批 ④（2026-09-13）：[[ResultAppliedByReconcile]]（引擎超时对账判的）加入本集合。
   * **向后安全（作者硬约束 iii）**：本集合之外的值（含本字样下旧 jar 不认识的未知值）
   * 一律走「**未闭合**」分支（见 [[State.consumed]]：`TerminalResults.contains` 为假 ⇒
   * 条目仍 pending）⇒ 升级/回滚两个方向都不会丢记账、不会误闭合。
   */
  val TerminalResults: Set[String] =
    Set(ResultApplied, ResultModified, ResultObsolete, ResultDeduped, ResultAppliedByReconcile)

  /**
   * 可重试结局（spec §5 R3 档 1「队列条目保留 ⇒ 下次压缩重试」）。末条结局落在本集合
   * 内的 note **仍是 pending**。
   *
   * `rejected` 为何在此：消费侧 `system.md:46`（step 4，行内原话「Location failed but the
   * meaning still exists」）定义 `rejected` =「本次定位失败但含义仍在（节改名 / 条目被
   * 改写）」——**本就是暂态、本该重试**；把它当终态的正是同一缺陷的另一半（降级路径用
   * rejected 代笔 infra 失败 + 折叠谓词把 rejected 当终态）。
   * 「含义已消失 / 已实现于别处」走 `obsolete`（终态），两者分工不变。
   */
  val RetryableResults: Set[String] =
    Set(ResultNotRun, ResultTimeout, ResultRejected, ResultBlocked)

  /**
   * 单条 note 的 infra 结局写入上限（`notrun`/`timeout`/`blocked` 计数）——达上限后
   * 只补一条 `blocked` 然后闭嘴，条目仍 pending。防「修复前每轮 191 行」的膨胀。
   */
  val MaxInfraOutcomesPerRef: Int = 3

  /** 引擎 infra 未跑档（写入计数用；`rejected` 是**消费者裁决**，不计入本集合）。 */
  val EngineInfraResults: Set[String] = Set(ResultNotRun, ResultTimeout, ResultBlocked)

  val TriggerCompaction = "compaction"
  val TriggerManual = "manual"
  val TriggerReview = "review"
  val TriggerDream = "dream"

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

    /**
     * 已闭合 = 末条结局是终态（消费者确实跑过并判定已了结）。无结局 / 末条属可重试类 /
     * 未知结局值 ⇒ 未闭合（宁保留勿静默丢）。
     */
    def consumed(n: Note): Boolean =
      lastOutcomeByRef.get(n.id).exists(o => TerminalResults.contains(o.result))

    /** 折叠谓词（见类头注）：未闭合且未被 drop 引用。 */
    def pending: Vector[Note] =
      notes.filterNot(n => droppedRefs.contains(n.id) || consumed(n))

    def pendingCount: Int = pending.size

    /**
     * 末条结局为 infra 未跑档（`notrun`/`blocked`）的 pending 条数——注入行与告警的
     * 判据（「消费链没跑」而不是「消费者裁定不可落」）。
     */
    def notRunPendingCount: Int =
      pending.count(n => lastOutcomeByRef.get(n.id).exists(o => o.result == ResultNotRun || o.result == ResultBlocked))

    /** 最老 pending 条目的 atMs（无 pending ⇒ None）——注入行报「堆积了多久」。 */
    def oldestPendingAtMs: Option[Long] =
      val p = pending
      if p.isEmpty then None else Some(p.map(_.atMs).min)

    def outcomeRefs: List[String] = outcomes.map(_.ref).toList

  end State

  object State:
    val empty: State = State(Vector.empty, Vector.empty, Set.empty, 0, 0)

  /**
   * 入队结果：`deduped` = 命中 pending 幂等键（未新增行）；`dropped` = 本次因容量
   * 上限被弃的条目数；`historyNote` 非空 = 变更史写失败（主操作已成功）。
   */
  final case class EnqueueResult(
    id: String,
    deduped: Boolean,
    dropped: Int,
    pending: Int,
    historyNote: String = ""
  )

  // ── 幂等键 ──────────────────────────────────────────────────────

  /**
   * `hash(target+action+section+match+content)`（spec §5 R1 原文口径）。空字段
   * 归一为 `-`，各段以 `\u0001` 分隔（防拼接歧义）。
   */
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
  end hashOf

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
      "kind" -> KindNote.asJson,
      "id" -> n.id.asJson,
      "atMs" -> n.atMs.asJson,
      "at" -> n.at.asJson,
      "target" -> n.target.asJson,
      "action" -> n.action.asJson,
      "section" -> optStr(n.section),
      "match" -> optStr(n.matchText),
      "content" -> optStr(n.content),
      "source" -> obj("sessionId" -> optStr(n.sessionId), "trigger" -> n.trigger.asJson)
    )

  private[tools] def encodeOutcome(o: Outcome): Json =
    obj(
      "kind" -> KindOutcome.asJson,
      "ref" -> o.ref.asJson,
      "atMs" -> o.atMs.asJson,
      "at" -> o.at.asJson,
      "result" -> o.result.asJson,
      "by" -> o.by.asJson,
      "detail" -> o.detail.asJson
    )

  private[tools] def encodeDrop(atMs: Long, refs: List[String]): Json =
    obj(
      "kind" -> KindDrop.asJson,
      "atMs" -> atMs.asJson,
      "at" -> isoOf(atMs).asJson,
      "dropped" -> refs.size.asJson,
      "refs" -> refs.asJson,
      "detail" -> s"pending cap $MaxPending exceeded — oldest ${refs.size} note(s) dropped by atMs (never silent)".asJson
    )

  private def field(j: Json, k: String): Option[Json] = j.asObject.flatMap(_(k))
  private def str(j: Json, k: String): Option[String] = field(j, k).flatMap(_.asString)
  private def long(j: Json, k: String): Long = field(j, k).flatMap(_.asNumber).flatMap(_.toLong).getOrElse(0L)

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

  /**
   * 同 [[readState]]，但行来自调用方（纯函数；dry-run 与 spec 对任意文本直测用）。
   * `readState` 只负责取行，折叠语义单点在此。
   */
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
                  case None => unreadable += 1
              case Some(KindOutcome) =>
                decodeOutcome(Json.fromJsonObject(o)) match
                  case Some(out) => outcomes += out
                  case None => unreadable += 1
              case Some(KindDrop) =>
                val refs = strs(Json.fromJsonObject(o), "refs")
                droppedRefs = droppedRefs ++ refs
                droppedTotal += field(Json.fromJsonObject(o), "dropped")
                  .flatMap(_.asNumber)
                  .flatMap(_.toInt)
                  .getOrElse(refs.size)
              case Some(_) => () // 未知 kind：注册式扩展，照收不拒（TaskListHistory 口径）
              case None => unreadable += 1
          case None => unreadable += 1
      end if
    }
    State(notes.result(), outcomes.result(), droppedRefs, droppedTotal, unreadable)
  end parseState

  def pendingNotes(): Vector[Note] = readState().pending

  def pendingCount(): Int = readState().pendingCount

  // ── 写 ──────────────────────────────────────────────────────────

  private val lock = new Object

  /**
   * 入队（幂等 + 容量兜底 + 变更史）。**不抛异常**：写失败返回 Left，调用方
   * 结构化报错（`MEMORYEDIT` 域），绝不静默成功。
   */
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
      val key = hashOf(target, action, section, matchText, content)
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
          JsonlLedger.appendLine(
            queuePath,
            archivePath,
            encodeNote(note).asJson.noSpaces,
            MaxActiveBytes,
            MaxActiveLines
          ) match
            case Left(reason) =>
              logger.warnSync(s"[memory-queue] enqueue failed: $reason")
              Left(reason)
            case Right(_) =>
              val histErr = MemoryHistory
                .appendQueue(
                  note.id,
                  atMs,
                  actor,
                  note.target,
                  note.action,
                  note.section,
                  note.matchText,
                  note.content,
                  note.sessionId,
                  note.trigger
                )
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
              val notes = List(
                histErr.map(e => s"history append failed ($e)"),
                dropErr.map(e => s"drop record append failed ($e)")
              ).flatten
              Right(
                EnqueueResult(
                  id = note.id,
                  deduped = false,
                  dropped = excess.size,
                  pending = pendingAfter.size - excess.size,
                  historyNote = notes.mkString("; ")
                )
              )
          end match
      end match
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
      JsonlLedger.appendLine(
        queuePath,
        archivePath,
        encodeOutcome(outcome).asJson.noSpaces,
        MaxActiveBytes,
        MaxActiveLines
      ) match
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
      end match
    }

  /** 批量回写同一结局（整理轨失败/超时的降级路径：队列条目保留 + 逐条留痕）。 */
  def recordOutcomes(refs: List[String], result: String, by: String, detail: String): List[Either[String, Outcome]] =
    refs.map(r => recordOutcome(r, result, by, detail, by))

  // ── 引擎侧只读 dry-run（方案 E/D1，2026-09-13 缺失自愈批）──────────
  //
  // 定位语义**单源**：本段是引擎侧权威定位（首个含 `match` 的 `- ` 条目；给 `section`
  // 时限定该节；节名 `## ` 前缀可选），消费侧提示词 `agents/memory-consolidator/system.md`
  // 引用同一口径、不另起一套。D0（只读脚本）与本实现互为独立复算面，分桶须逐条一致。

  /**
   * 分桶（方案 §5 D1 的三段输出 + 2026-09-13 r3 批的第四段）。
   *
   * 分档判据 = **该条的内容是否还能落**：
   *   - `WouldObsolete`（**终态族**）：内容已被**后来的记账**取代（`superseded-by-later`）
   *     或**逐字已在文件里**（`already-present`）——这类条目的意图已了结，文件再变也不会
   *     让它可落 ⇒ 终态词成立。
   *   - `WouldRetry`（**可重试族**，2026-09-13 r3 批新增）：**目标定位不到**——目标文件
   *     不存在（`target-missing`）/ 目标节不存在（`locate-miss: section not found`）/
   *     该节内定位不到条目（`locate-miss: no matching '- ' entry`）/ 落笔时定位器失效
   *     （`apply-miss`）。**内容本身没有作废**，只是**这一时点找不到落点**；文件/节出现
   *     或被改写后即可落 ⇒ **不得打终态词**（终态 ⇒ `State.consumed` 为真 ⇒ 条目永不再
   *     pending ⇒ 内容永久丢失 = 「失败被伪装成成功」）。
   *   - `WouldDefer`：预算 fail-closed 截断（超硬顶即停、剩余留 pending）。
   *
   * `WouldRetry` **仍进 `authorized`**（与 `WouldObsolete` 同为「零文件写、只回写结局」的
   * 裁决面）——不授权的活锁后果：消费者永远看不到、条目永不闭合、pending 只增不减。
   */
  enum Bucket:
    case WouldApply, WouldObsolete, WouldRetry, WouldDefer

  object Bucket:

    def label(b: Bucket): String = b match
      case WouldApply => "would-apply"
      case WouldObsolete => "would-obsolete"
      case WouldRetry => "would-retry"
      case WouldDefer => "would-defer"

  /**
   * dry-run 的输入面：某目标**当前磁盘内容**的只读快照（`path` 仅供渲染）。
   *
   * `exists`（2026-09-13 r3 批新增，缺省 `true` 保持向后兼容）：**目标文件在不在**。
   * 改动前 `readAll` 把「文件不存在」与「空文件」都归成 `""` ⇒ 对不存在的项目记忆文件
   * 的 append 被当「空文件追加」⇒ 判 `would-apply`，投影 `0 → N B`，消费侧据此**新建了
   * 文件**（生产实证 2026-09-13 18:25，`project:neblink-server`，引擎投影 1,032 B vs
   * 实盘 1,104 B）——缺陷①。存在性必须作为**独立输入位**传进来，`content == ""` 不足以
   * 区分。
   */
  final case class TargetFile(path: String, content: String, exists: Boolean = true)

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
    def delta: Long = projectedBytes - beforeBytes
    def fullDelta: Long = fullProjectedBytes - beforeBytes
    def overHard: Boolean = projectedBytes > hardCap
    def fullOverHard: Boolean = fullProjectedBytes > hardCap

  /**
   * dry-run 计划：逐条分桶 + 逐文件投影 + 前置闸结论。
   *
   * `authorized` = 本落轮**允许消费**的 ref（would-apply + would-obsolete + would-retry；
   * 后两者零文件写、只回写结局），`deferred` = 预算截断的 ref（**不进简报、保持 pending**），
   * `retryable` = 目标缺失族的 ref（**可重试口径的响亮面**：进简报、零新建、条目保持
   * pending），`refusal` 非空 = 前置闸 fail-closed 拒绝本轮落地（authorized 为空而仍有 pending）。
   */
  final case class Plan(
    items: Vector[PlanItem],
    projections: Vector[TargetProjection],
    authorized: Vector[String],
    deferred: Vector[String],
    retryable: Vector[String],
    refusal: Option[String]
  ):
    def refsOf(b: Bucket): Vector[String] = items.filter(_.bucket == b).map(_.ref)
    def countOf(b: Bucket): Int = items.count(_.bucket == b)

    /** 四段结构化文本（进日志；dry-run 模式下同时是实测输出）。 */
    def render(maxPerBucket: Int = 400): String =
      val sb = new StringBuilder
      sb ++= s"memory queue plan (read-only): pending=${items.size} "
      sb ++= s"${Bucket.label(Bucket.WouldApply)}=${countOf(Bucket.WouldApply)} "
      sb ++= s"${Bucket.label(Bucket.WouldObsolete)}=${countOf(Bucket.WouldObsolete)} "
      sb ++= s"${Bucket.label(Bucket.WouldRetry)}=${countOf(Bucket.WouldRetry)} "
      sb ++= s"${Bucket.label(Bucket.WouldDefer)}=${countOf(Bucket.WouldDefer)}\n"
      sb ++= s"GATE: ${refusal.getOrElse("landable (no item deferred by the budget cap)")}\n"
      List(
        Bucket.WouldApply -> "will be landed (authorized)",
        Bucket.WouldObsolete -> "NOT landed — terminal verdict only (superseded / already present; obsolete is the consumer's call)",
        Bucket.WouldRetry -> "NOT landed — RETRYABLE missing target (file / section / entry); never a terminal word, stays pending, the target file must NOT be created by the consumer",
        Bucket.WouldDefer -> "NOT authorized this round — stays pending (budget fail-closed: a net-growth note would push an over-cap file further over; net-shrinking notes are exempt)"
      ).foreach { (b, why) =>
        val rows = items.filter(_.bucket == b)
        sb ++= s"-- ${Bucket.label(b)} (${rows.size}) — $why\n"
        rows.take(maxPerBucket).foreach { i =>
          val loc = i.line.map(l => s" line=$l").getOrElse("")
          val d = if i.deltaBytes == 0L then "" else s" Δ${i.deltaBytes}B"
          sb ++= s"   ${i.ref} ${i.target} ${i.action}$loc$d — ${i.detail}\n"
        }
        if rows.size > maxPerBucket then sb ++= s"   …(${rows.size - maxPerBucket} more)\n"
      }
      sb ++= "-- projected bytes (per file; \"authorized\" = the capped landing prefix, \"full set\" = if every would-apply item landed)\n"
      projections.foreach { p =>
        val warn =
          if p.overHard then "  ** OVER HARD CAP **"
          else if p.projectedBytes > p.softCap then "  (over soft line)"
          else ""
        val fullWarn =
          if p.fullOverHard then s"  ** FULL SET OVER HARD by ${p.fullProjectedBytes - p.hardCap}B **"
          else if p.fullProjectedBytes > p.softCap then "  (full set over soft line)"
          else ""
        sb ++= f"   ${p.target}%-22s ${p.path}%s  ${p.beforeBytes}%,d → ${p.projectedBytes}%,d (auth ${p.delta}%+,d) | full ${p.fullProjectedBytes}%,d (${p.fullDelta}%+,d) | hard=${p.hardCap}%,d$warn$fullWarn\n"
      }
      sb.result()

    end render

  end Plan

  /** 预算常量按 target 维度（与 [[nebflow.service.MemoryBudget]] 同源，数值不复制）。 */
  private def capsOf(target: String): (Long, Long) =
    if target == "user" then (MemoryBudget.UserSoftBytes, MemoryBudget.UserHardBytes)
    else if target == "agent" then (MemoryBudget.AgentSoftBytes, MemoryBudget.AgentHardBytes)
    else (MemoryBudget.ProjectSoftBytes, MemoryBudget.ProjectHardBytes)

  private def utf8Bytes(s: String): Long =
    s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length.toLong

  private def splitLines(s: String): Vector[String] = s.split("\n", -1).toVector

  /**
   * 节区间 `[start, end)`（`start` = 节标题行；`## ` 前缀在参数里可选）。
   * 节不存在 ⇒ None。
   */
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
      case None => Some((0, lines.length))
      case Some(s) => sectionBounds(lines, s)
    bounds.flatMap { (s, e) =>
      (s until e).find(i => lines(i).startsWith("- ") && lines(i).contains(matchText)).map(i => (i + 1, lines(i)))
    }

  /**
   * 「逐字行已在文件」判据（**单源**：`plan` 的 `already-present` 桶与超时对账 ④ 共用）。
   * 判据 = **整行 trim 后逐字相等**（精确行匹配：误杀率 0、漏检率非 0 ⇒ 宁漏不误；
   * 前缀匹配会误杀，故不用）。
   */
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
            sectionBounds(lines, s).map((start, e) =>
              lines.patch(start + 1, splitLines(t), e - start - 1).mkString("\n")
            )
          case _ => None
      case _ => None

    end match

  end applyOp

  /** 落地顺序偏序（atMs, id）——取代检测的「后到者」判据。 */
  private def after(later: Note, earlier: Note): Boolean =
    later.atMs > earlier.atMs || (later.atMs == earlier.atMs && later.id > earlier.id)

  /**
   * 引擎侧只读 dry-run：对 `state.pending` 逐条定位 + 分桶 + 逐文件投影，产出落地前置闸。
   *
   * **零写入**：本方法不落任何文件、不改队列、不写结局。fail-closed 语义 = 逐条按
   * 落地顺序（§8.2 硬顺序：收缩 → update → append）累计投影，一旦某条会把该文件推过
   * **硬顶** ⇒ 该条起（含）该文件后续全部条目转 `would-defer`（**超硬顶即停，剩余留
   * pending**）；authorized 为空而仍有 pending ⇒ `refusal` 非空（本轮拒绝落地）。
   *
   * **收缩豁免（作者 2026-09-15 裁定 A；判据单源 = [[MemoryWriteGate.shrinkExempt]]）**：
   * 「推过硬顶」只对**净增**条目成立——本条落笔后字节 **≤** 落笔前字节（真收缩：`remove` /
   * `replace_section` / 净缩的 `update`）**既不受硬顶停点约束、也不被停点闩吞掉**；净增条目
   * （含 `append`、净增的 `replace_section`）**照旧**被截断。裁定逐字 = 「满格时放行删除/
   * 替换类条目落盘，**append 类仍拒至回到预算内**」；理由是超限文件的自救路径（`remove` /
   * `replace_section`）不得被自己的前置闸掐死（`MemoryWriteGate` 的纯收缩豁免同旨，
   * `seed/agents/memory-consolidator/system.md:68` 记同一设计初衷）。
   *
   * 取代检测（与 D0 机械近似同规）：同一 (target, located line) 上多条 update/remove
   * 只有**末条**有效、其余 `would-obsolete`；append 的 content 被同目标后续 remove 的
   * match 命中 ⇒ 同样 `would-obsolete`。**「末条」按真时序 `(atMs, id)` 判**（与
   * [[after]] 同规，2026-09-13 r3 批缺陷②），不是按相位序的落地位置。
   *
   * **目标缺失族**（目标文件不存在 / 目标节不存在 / 该节内定位不到条目）落
   * `would-retry`——**非终态、条目保持 pending、零新建**（2026-09-13 r3 批缺陷① A′）。
   */
  def plan(state: State, files: Map[String, TargetFile]): Plan =
    planWith(state, files, enforceBudgetCap = true)

  /**
   * 无闸全量投影（对照面）：同一套定位/分桶逻辑、只关掉预算停点——「若不加闸一次全量
   * 落地会到多少字节」的机械复算（方案件 §3.2 同口径），与带闸的 authorized 投影并列
   * 展示，供互证与爆炸半径复核。
   */
  private def planWith(state: State, files: Map[String, TargetFile], enforceBudgetCap: Boolean): Plan =
    val notes = state.pending
    def phase(n: Note): Int = n.action match
      case "remove" | "replace_section" => 0
      case "update" => 1
      case "append" => 2
      case _ => 3
    val ordered = notes.sortBy(n => (phase(n), n.atMs, n.id))

    // ── 静态定位（原盘内容；取代检测的行号基准）──
    val staticLocate: Map[String, Option[(Int, String)]] = ordered.map { n =>
      val lines = splitLines(files.get(n.target).map(_.content).getOrElse(""))
      n.id -> (n.action match
        case "update" | "remove" => locate(lines, n.section, n.matchText.getOrElse("\u0000"))
        case _ => None)
    }.toMap
    val byLine: Map[(String, Int), Vector[Note]] =
      ordered
        .filter(n => n.action == "update" || n.action == "remove")
        .flatMap(n => staticLocate.getOrElse(n.id, None).map((ln, _) => (n.target, ln) -> n))
        .groupBy(_._1)
        .view
        .mapValues(_.map(_._2).toVector)
        .toMap
    // 2026-09-13 r3 批（缺陷②）：同一 (target, 定位行) 上「谁取代谁」按**真时序**判，
    // 与 `after()` 的声明同规（改动前用 `ordered` 的**位置**= 相位序：`phase` 为主键 ⇒
    // 时间更晚的 remove（phase 0）会输给时间更早的 update（phase 1）；生产实测 8 条分歧、
    // 最大早 16.3 h）。**只改本行的迭代序**：`ordered` 仍按相位序累计预算闸（§8.2 硬序：
    // 收缩先落）——落地调度 ≠ 取代裁决，两层基准不同是设计，不是同一个量。
    val supersededByLine: Set[String] =
      byLine.values
        .filter(_.sizeIs > 1)
        .flatMap(ns => ns.sortWith((a, b) => after(b, a)).dropRight(1).map(_.id))
        .toSet
    val supersededByRemove: Set[String] =
      ordered
        .filter(_.action == "append")
        .flatMap { a =>
          val body = a.content.getOrElse("")
          val hit = ordered.exists(b =>
            b.action == "remove" && b.target == a.target && after(b, a) &&
              b.matchText.exists(m => m.nonEmpty && body.contains(m))
          )
          if hit then Some(a.id) else None
        }
        .toSet

    // ── 逐条分桶 + 落地顺序预算闸（模拟；不落盘）──
    val sim = scala.collection.mutable.Map.from(files.view.mapValues(_.content))
    val stopped = scala.collection.mutable.Set.empty[String]
    val items = Vector.newBuilder[PlanItem]

    /**
     * **收缩豁免（消费侧适格声明）**：本轨就是队列的消费前置闸，落盘面的适格身份在此声明
     * ——`shrinkChannel = true`，是否真放行仍由闸的**字节比**独立裁决
     * （[[MemoryWriteGate.shrinkExempt]]，判据**单源**，🔴 **不按动作名**）。
     *
     * 适格面 = 「本条落笔后该目标字节 **≤** 落笔前字节」——`remove` / `replace_section` /
     * 净缩的 `update` 一视同仁；净增的 `replace_section`、`append` **照旧**被硬顶截断。
     * `cur` = 该条**落地时刻**的模拟内容（不是原盘内容：前序条目已生效）。
     *
     * 为什么需要（作者 2026-09-15 裁定 A）：改动前停点判据（`projected > 硬顶`）与停点闩
     * （`stopped`）**不区分方向** ⇒ 目标一旦超硬顶，**连会把它拉回硬顶的收缩条目也被一并
     * 扣发** ⇒ 授权集为空 ⇒ `refusal` 非空 ⇒ 零 spawn ⇒ 文件永远超顶、该目标全部条目
     * 永远 pending（「超限文件的自救路径被自己的前置闸掐死」）。裁定把它对齐到闸已文档化的
     * 设计初衷（`seed/agents/memory-consolidator/system.md:68`：over the cap ⇒ consolidate
     * first（`remove` / `replace_section`）and then write—never land over-cap）。
     */
    def shrinksFile(cur: String, n: Note): Boolean =
      applyOp(cur, n.action, n.section, n.matchText, n.content)
        .exists(next => MemoryWriteGate.shrinkExempt(utf8Bytes(cur), utf8Bytes(next), shrinkChannel = true))

    ordered.foreach { n =>
      val caps = capsOf(n.target)
      val fileOpt = files.get(n.target)
      val targetLabel = fileOpt.map(_.path).getOrElse(n.target)
      def bucketOf(supersededDetail: String): PlanItem =
        PlanItem(n.id, n.target, n.action, n.atMs, Bucket.WouldObsolete, supersededDetail, None, 0L)
      // ── 缺陷①（2026-09-13 r3 批，A′）：目标**文件不存在** ≠ **空文件** ──
      // 改动前「label 在面里但文件不存在」与「文件是空的」在输入面不可分 ⇒ append 走
      // 「空文件追加」⇒ 判 would-apply ⇒ 消费侧据此**新建**项目记忆文件（生产实证）。
      // A′：缺文件 ⇒ **零新建**（本判词不进 authorized） + 条目**留 pending 可重试** +
      // 响亮告警（`Plan.retryable` 进简报）。判据 = 显式存在位，非 `content.isEmpty`。
      // 口径是**通则**（任一层的目标文件缺失一律照此办），不是项目层专例。
      val targetMissing = fileOpt.exists(f => !f.exists)
      def retryOf(detail: String): PlanItem =
        PlanItem(n.id, n.target, n.action, n.atMs, Bucket.WouldRetry, detail, None, 0L)
      val item: PlanItem =
        if targetMissing then
          retryOf(s"target-missing: target file does not exist ('$targetLabel') — do NOT create it; retryable")
        else if fileOpt.isEmpty then retryOf(s"target-missing: no memory file for '$targetLabel' — retryable")
        else if supersededByLine.contains(n.id) then
          bucketOf("superseded-by-later: same located line, a later note wins")
        else if supersededByRemove.contains(n.id) then
          bucketOf("superseded-by-later: a later remove matches this append")
        else if stopped.contains(n.target) && !shrinksFile(sim.getOrElse(n.target, ""), n) then
          PlanItem(
            n.id,
            n.target,
            n.action,
            n.atMs,
            Bucket.WouldDefer,
            s"budget: stopped earlier — projected bytes already over the hard cap (${caps._2} B)",
            None,
            0L
          )
        else
          val cur = sim.getOrElse(n.target, "")
          val lines = splitLines(cur)
          val loc =
            if n.action == "update" || n.action == "remove" then locate(lines, n.section, n.matchText.getOrElse(""))
            else None
          val secOk = n.section.forall(s => sectionBounds(lines, s).isDefined)
          val dupApp = n.action == "append" && n.content.exists(c => alreadyPresentLine(lines, c))
          if (n.action == "update" || n.action == "remove") && loc.isEmpty then
            retryOf(
              if n.section.isDefined && !secOk then "locate-miss: section not found — retryable"
              else "locate-miss: no matching '- ' entry — retryable"
            )
          else if n.action == "replace_section" && !secOk then retryOf("locate-miss: section not found — retryable")
          else if n.action == "append" && !secOk then retryOf("locate-miss: section not found — retryable")
          else if dupApp then
            PlanItem(
              n.id,
              n.target,
              n.action,
              n.atMs,
              Bucket.WouldObsolete,
              "already-present: an identical entry is already in the file (consumer verdict: deduped)",
              None,
              0L
            )
          else
            applyOp(cur, n.action, n.section, n.matchText, n.content) match
              case None =>
                retryOf("apply-miss: locator no longer resolves on the simulated content — retryable")
              case Some(next) =>
                val projected = utf8Bytes(next)
                // 收缩豁免：真收缩（POST ≤ PRE）不受硬顶停点约束——判据由闸单源裁决，
                // 与 [[MemoryWriteGate.decide]] 的 `exempt` 逐字同值（防两面判据漂移）。
                val exempt = MemoryWriteGate.shrinkExempt(utf8Bytes(cur), projected, shrinkChannel = true)
                if enforceBudgetCap && !exempt && projected > caps._2 then
                  stopped += n.target
                  PlanItem(
                    n.id,
                    n.target,
                    n.action,
                    n.atMs,
                    Bucket.WouldDefer,
                    s"budget: applying would reach $projected B > hard cap ${caps._2} B — stopping here (remaining notes stay pending)",
                    loc.map(_._1),
                    0L
                  )
                else
                  val delta = projected - utf8Bytes(cur)
                  sim.update(n.target, next)
                  PlanItem(
                    n.id,
                    n.target,
                    n.action,
                    n.atMs,
                    Bucket.WouldApply,
                    "located" + loc.map((ln, _) => s" at line $ln").getOrElse(""),
                    loc.map(_._1),
                    delta
                  )
                end if
          end if
      items += item
    }

    val all = items.result()
    val applyIds = all.filter(_.bucket == Bucket.WouldApply).map(_.ref)
    val obsIds = all.filter(_.bucket == Bucket.WouldObsolete).map(_.ref)
    val retryIds = all.filter(_.bucket == Bucket.WouldRetry).map(_.ref)
    val deferIds = all.filter(_.bucket == Bucket.WouldDefer).map(_.ref)
    // would-retry 与 would-obsolete 同为「零文件写、只回写结局」的裁决面 ⇒ 一并进授权集。
    // 不进的理由只能是「本轮不能给消费者任何落笔授权」，而这两族都不落笔。
    val authorized = applyIds ++ obsIds ++ retryIds
    // 无闸对照面：同一逻辑关掉预算停点重跑一次，只取逐文件投影。
    val fullByTarget =
      if enforceBudgetCap then
        planWith(state, files, enforceBudgetCap = false).projections.map(p => p.target -> p.projectedBytes).toMap
      else Map.empty[String, Long]
    val projections = files.toVector.sortBy(_._1).map { (label, tf) =>
      val (soft, hard) = capsOf(label)
      val projected = if sim.contains(label) then utf8Bytes(sim(label)) else utf8Bytes(tf.content)
      val full = fullByTarget.getOrElse(label, projected)
      TargetProjection(label, tf.path, utf8Bytes(tf.content), projected, full, soft, hard)
    }
    val refusal =
      if notes.nonEmpty && authorized.isEmpty then
        val stop = all
          .find(_.bucket == Bucket.WouldDefer)
          .map(i => s"${i.ref} ${i.target}/${i.action}: ${i.detail}")
          .getOrElse("no landable item")
        Some(
          s"REFUSED (fail-closed): ${notes.size} pending note(s), none landable — " +
            s"${deferIds.size} deferred by the budget cap (stopped at $stop). " +
            s"Nothing was written; every note stays pending until the cap is relieved " +
            s"(shrink via remove/replace_section first)."
        )
      else None
    Plan(all, projections, authorized, deferIds, retryIds, refusal)
  end planWith

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
    def refs: Set[String] = closed.map(_.ref).toSet
    def count: Int = closed.size

    /** 结论直方图（日志/事件渲染用）。 */
    def resultCounts: Map[String, Int] = closed.groupBy(_.result).view.mapValues(_.size).toMap

    /** 单行渲染（日志用）。 */
    def render: String =
      if closed.isEmpty then "none"
      else resultCounts.toVector.sortBy(_._1).map((r, n) => s"$r×$n").mkString(", ")

  object ReconcileReport:
    val empty: ReconcileReport = ReconcileReport(Vector.empty)

  /**
   * **超时对账（纯函数、零写入、可单测）**：对每条 pending note 用**跑后**文件内容判
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
   * 在 `readAll` 手里，本函数不读盘）。
   */
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
                )
              )
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
                )
              )
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
                )
              )
          case _ => None
        end match
      }
    }
    ReconcileReport(closed)
  end reconcile

  // ── 注入摘要行 ──────────────────────────────────────────────────

  /**
   * 生命周期注入一行（`ContextRefresher.buildMemoryBlock` 消费，MemoryHygieneSignal
   * 先例）：pending 为 0 且无弃置记录 ⇒ 空串（不留常驻噪声行）。
   * 不新增只读回看工具（spec §5 R2「Nebula 能否读回队列」）。
   *
   * 2026-09-13 缺失自愈批（方案 D「响亮失败」）：注入行由「无条件承诺」改为**条件承诺**
   * ——后端照旧报 pending 数，另加两段可观测信息：① 最老 pending 的年龄（堆积了多久）；
   * ② 一旦存在 `notrun`/`blocked` 结局（= 消费链**根本没跑**，不是消费者裁定不可落）
   * 就在同一行挂 ALERT 段。没有这两段时行长与旧版逐字一致（不引入常驻噪声）。
   *
   * 缺文件族批（A′ 三件之三）：再挂第三段 ALERT —— pending 里存在**目标层没有记忆文件**
   * 的条目（[[targetLayerHasFile]] 判据，与 [[nebflow.agent.MemoryTrack]] 的计划输入面
   * 同源）时，该族判 `target-missing` ⇒ 可重试族：**不落笔、不新建、不打终态词、条目留
   * pending**，靠整理轨那一行的 WARN 与简报里的段只在「有会话在跑整理」时可见 ⇒ 注入面
   * 必须自己挂响一项，否则缺层条目静默堆着。分层去重后逐层只探一次盘（pending 可达数百）。
   *
   * 三段段位互不遮蔽（`List(...).flatten` 逐个拼接）：同一条件可同时成立。
   */
  def summaryLine(): String =
    try
      val s = readState()
      val pending = s.pending
      val n = pending.size
      val drops = if s.droppedTotal > 0 then s" (${s.droppedTotal} dropped by the $MaxPending-pending cap)" else ""
      val last = s.outcomes.lastOption.map(o => s"last outcome: ${o.result} (${o.ref})")
      if n == 0 && s.droppedTotal == 0 then ""
      else
        val age = s.oldestPendingAtMs.map(ms => s" (oldest ${ageLabel(System.currentTimeMillis() - ms)})").getOrElse("")
        val head =
          s"Memory queue: $n pending note(s)$age awaiting consolidation — applied at the next compaction, not at write time$drops."
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
            Some(s"""ALERT: the memory-consolidation track is NOT consuming the queue — $notRun pending note(s) carry a
                 |not-run/blocked outcome ($causes), i.e. the consumer never ran (missing agent definition or an
                 |engine precondition), NOT a judgement that the entries are unlandable. Nothing is being applied and
                 |the queue only grows. Fix the consumption chain (see the gateway startup log) — the entries stay
                 |pending and will be retried on the next compaction.""".stripMargin.replace("\n", " "))
          end if
        end alert
        val strandedLayers =
          pending.map(_.target).distinct.filterNot(targetLayerHasFile).sorted
        val missingAlert =
          if strandedLayers.isEmpty then None
          else
            val strandedSet = strandedLayers.toSet
            val stranded = pending.count(note => strandedSet.contains(note.target))
            val shown = strandedLayers.take(6).mkString(", ") + (if strandedLayers.size > 6 then ", …" else "")
            Some(
              s"""ALERT: $stranded pending note(s) name a target layer that has no memory file ($shown) — no file and no
                 |section is created for them, and no terminal word is written for them (a terminal verdict on a note that
                 |cannot land would throw its content away); they keep their retryable status and stay pending. Nothing
                 |lands until that layer exists: register the project / create the layer's memory file outside the
                 |consolidation track, and the next compaction applies them.""".stripMargin.replace("\n", " ")
            )
        List(Some(head), alert, missingAlert, last).flatten.mkString(" ")
      end if
    catch case e: Exception => ""

  /**
   * 注入行的缺文件族判据（A′ 三件之三「响亮告警」）：该 target 层**是否存在记忆文件**。
   *
   * 与计划输入面 [[nebflow.agent.MemoryTrack.memoryFilesOf]] **同源**（判据不另起一套）：
   * `user` / `agent` 按 [[nebflow.service.MemoryStore]] 的路径取；`project:<name>` 取注册表
   * `{{dataRoot}}/projects/<name>/project.json` 的 `workspace`（[[ProjectStore.projectJsonPath]]
   * + [[ProjectDef]] 的 decoder + [[ProjectMemory.path]]）。
   *
   * **不调 [[ProjectStore.load]]**：它在加载时跑旧位迁移（写工作区根），注入面（每次上下文
   * 构建都过这里）不产出副作用 ⇒ 只做只读解析。未注册 / 名称非法 / 未识别的 target 一律判
   * 「无文件」（与计划面 `fileOpt.isEmpty ⇒ target-missing` 同判）；判据自身抛异常按「有文件」
   * 处理（不因判据出错而误报，宁漏不误）。
   */
  private[tools] def targetLayerHasFile(target: String): Boolean =
    try
      target match
        case "user" => os.exists(MemoryStore.userMemoryPath)
        case "agent" => os.exists(MemoryStore.agentMemoryPath("Nebula"))
        case p if p.startsWith("project:") =>
          val name = p.stripPrefix("project:")
          val named =
            name.nonEmpty && !name.contains("/") && !name.contains("\\") && name != "." && name != ".."
          if !named then false
          else
            val pj = ProjectStore.projectJsonPath(name)
            os.exists(pj) && parse(os.read(pj))
              .flatMap(_.as[ProjectDef])
              .toOption
              .exists(pd => os.exists(ProjectMemory.path(pd.workspace)))
        case _ => false
    catch case _: Exception => true

  /** 年龄渲染（注入行用；粗粒度即可，不引入时钟依赖）。 */
  private[tools] def ageLabel(ms: Long): String =
    val m = ms / 60000L
    if m < 60 then s"${m}m"
    else if m < 60 * 24 then s"${m / 60}h${m % 60}m"
    else s"${m / (60 * 24)}d${(m % (60 * 24)) / 60}h"

end MemoryQueue
