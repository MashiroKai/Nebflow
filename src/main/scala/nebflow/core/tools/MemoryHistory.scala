package nebflow.core.tools

import io.circe.{Json, JsonObject}
import io.circe.parser.parse
import io.circe.syntax.*
import nebflow.core.{NebflowLogger, PathUtil}

import java.time.Instant

import scala.collection.mutable

/**
 * 记忆变更史 —— 独立 append-only JSONL 数据面（记忆改造批 2026-09-12，IMPL-1 必含项）。
 *
 * **代裁来源（非作者亲裁）**：作者 2026-09-12 00:19 授权代裁「`action=log` 补实现吧，
 * 或者你自己分析一下要不要补」⇒ 分发器代裁结论 = **补**；形态对齐既有先例
 * `TaskListHistory`（`TaskListTool.scala:952` 独立 append-only `${dataRoot}/
 * tasks-history.jsonl`、`:655`「note 零改写」、`:952`「Engine-derived `actor`；
 * you never write timestamps」）与 `TaskBoardHistory`（`<ws>/.nebflow/
 * task-history.jsonl`，「append-only, one history file per board」）。
 *
 * 落盘：`<dataRoot>/memory/history.jsonl`（生产 = `~/.nebflow/memory/history.jsonl`），
 * 归档单代 `history.1.jsonl`。运行时数据层：不进 git、不进任何注入。
 *
 * **为什么与队列分家（R2 理由 ②④）**：队列条目化后，记忆文件的实际写入者是
 * 「记忆整理 agent 的通用 `Edit`/`Write`」（`MemoryStore.scala:24-25` 自陈
 * 「Agents update them directly using Edit/Write」），该通道**无内建留痕**——
 * `MemoryChangeNotifier` 只推 `memoryChanged` WS 事件、不落任何文件，且其谓词
 * 不识别项目层。本文件就是那条缺失的落盘留痕：不读记忆正文即可重建
 * 「谁 / 何时 / 把哪条改成了什么」。
 *
 * 行 schema（注册式扩展：未知 kind / 未知键在读取侧照收不拒，同
 * `TaskListHistory` / `FlowMapEventLog` 口径）：
 *   - `{"kind":"queue","at","atMs","actor","ref","target","action","section?","match?",
 *      "content?","source":{"sessionId?","trigger"}}` —— 入队一条 note（引擎侧代写
 *      时间戳与 actor，不信客户端）；`content` **零改写**逐字入档。
 *   - `{"kind":"consume","at","atMs","actor","ref","target","action","content?",
 *      "result","by","detail"}` —— 一条 note 的消费结局（applied/modified/rejected/
 *      obsolete/deduped/timeout）；`action`/`content` 为 note 侧逐字回显（对账锚）。
 *   - `{"kind":"change","at","atMs","actor","path","target","trigger","refs":[…],
 *      "added":[…],"removed":[…]}` —— 记忆文件真身的变更（引擎在整理轨前后对
 *      文件取快照并做行级 diff）；`added`/`removed` 为**全文行、不截断**。
 *   - `{"kind":"preflight","at","atMs","actor","trigger","authorized","pending",
 *      "refs":[…],"targets":[{"label","path","sha256","bytes"}],
 *      "snapshot":{"dir","label"}}` —— **写前台账**（C 批 ①，2026-09-13）：整理轨
 *      spawn **之前**把本轮授权集落成**一条聚合行**（**禁**逐条 273 行 —— 避免把
 *      20000 行阈值烧穿）：授权 ref 计数 + 全量 ref + 每目标**起始 sha256/bytes**
 *      （+ 落地前快照目录）。超时后 ④ 的对账因此有可审计、可复算的基准，且**不依赖
 *      agent 自快照**。台账**不阻止重投**，它只让「谁该闭合」这件事事后可判。
 *
 * 三者以 `ref`（= 队列 note id `q-…`）对账，且**逐 occurrence 配对**：同一 ref 可被
 * 消费多次（重试 / 对账闭合）⇒ 同一 ref 可有多条 outcome 行与多条 consume 行，
 * 「集合差」口径对这种重复缺口恒报 0（同 ref 至少各有一条即视为一致）。
 * `MemoryHistory.reconciliation` 给出 occurrence 级 `Gaps`（三差集 + 计数），
 * `discrepancies` 返回其逐条描述（旧签名与三分类不变）。
 */
object MemoryHistory:

  private val logger = NebflowLogger.forName("nebflow.memory.history")

  val FileName: String        = "history.jsonl"
  val ArchiveFileName: String = "history.1.jsonl"

  /** `def` 非 `val`：`PathUtil.dataRoot` 可被测试换根（同 TaskListHistory 陷阱）。 */
  def historyPath: os.Path = PathUtil.dataRoot / "memory" / FileName
  def archivePath: os.Path = PathUtil.dataRoot / "memory" / ArchiveFileName

  /** 轮转阈值（活动文件，先到为准）。5 MiB / 20,000 行 ⇒ 单代磁盘硬顶 ≈ 10 MiB。 */
  val MaxActiveBytes: Long = 5L * 1024 * 1024
  val MaxActiveLines: Int  = 20000

  /** 事件 kind 值域。 */
  val KindQueue     = "queue"
  val KindConsume   = "consume"
  val KindChange    = "change"
  val KindPreflight = "preflight"

  /** actor 值域（引擎侧派生，不信客户端参数）——`memory-consolidator` 等身份名
    * 亦经 `actorOf` 小写归一；未知来源记 `unknown`，绝不编造。 */
  val ActorSystem = "system"
  val ActorUnknown = "unknown"

  def actorOf(ctx: ToolContext): String =
    if ctx.isDispatcher then "dispatcher"
    else if ctx.flowNodeId.exists(_.trim.nonEmpty) then "node"
    else
      ctx.agentDef
        .map(_.name.trim.toLowerCase)
        .filter(_.nonEmpty)
        .getOrElse(ActorUnknown)

  /** 引擎侧时间戳（唯一时间源；调用方不写时间）。 */
  private def nowMs(): Long      = System.currentTimeMillis()
  private def isoOf(ms: Long): String = Instant.ofEpochMilli(ms).toString

  // ------------------------------------------------------------------
  // 写
  // ------------------------------------------------------------------

  /** Json 对象构造：`Json.Null` 值的键**整键省略**（可选字段不入档，行保持紧凑）。 */
  private def obj(fields: (String, Json)*): Json =
    Json.fromJsonObject(JsonObject.fromIterable(fields.toList.filterNot((_, v) => v.isNull)))

  private def optStr(v: Option[String]): Json = v.filter(_.nonEmpty).map(_.asJson).getOrElse(Json.Null)

  /** 入队留痕（`MemoryQueue.enqueue` 内调用）。 */
  def appendQueue(
    ref: String,
    atMs: Long,
    actor: String,
    target: String,
    action: String,
    section: Option[String],
    matchText: Option[String],
    content: Option[String],
    sessionId: Option[String],
    trigger: String
  ): Either[String, Unit] =
    append(obj(
      "kind"    -> KindQueue.asJson,
      "at"      -> isoOf(atMs).asJson,
      "atMs"    -> atMs.asJson,
      "actor"   -> actor.asJson,
      "ref"     -> ref.asJson,
      "target"  -> target.asJson,
      "action"  -> action.asJson,
      "section" -> optStr(section),
      "match"   -> optStr(matchText),
      "content" -> optStr(content),
      "source"  -> obj("sessionId" -> optStr(sessionId), "trigger" -> trigger.asJson)
    ))

  /** 消费结局留痕（`MemoryQueue.recordOutcome` 内调用）。 */
  def appendConsume(
    ref: String,
    atMs: Long,
    actor: String,
    target: String,
    action: String,
    content: Option[String],
    result: String,
    by: String,
    detail: String
  ): Either[String, Unit] =
    append(obj(
      "kind"    -> KindConsume.asJson,
      "at"      -> isoOf(atMs).asJson,
      "atMs"    -> atMs.asJson,
      "actor"   -> actor.asJson,
      "ref"     -> ref.asJson,
      "target"  -> target.asJson,
      "action"  -> action.asJson,
      "content" -> optStr(content),
      "result"  -> result.asJson,
      "by"      -> by.asJson,
      "detail"  -> detail.asJson
    ))

  /** 真身变更留痕（整理轨前后文件 diff；`added`/`removed` 逐行全文）。 */
  def appendChange(
    atMs: Long,
    actor: String,
    path: String,
    target: String,
    trigger: String,
    refs: List[String],
    added: List[String],
    removed: List[String]
  ): Either[String, Unit] =
    append(obj(
      "kind"    -> KindChange.asJson,
      "at"      -> isoOf(atMs).asJson,
      "atMs"    -> atMs.asJson,
      "actor"   -> actor.asJson,
      "path"    -> path.asJson,
      "target"  -> target.asJson,
      "trigger" -> trigger.asJson,
      "refs"    -> refs.asJson,
      "added"   -> added.asJson,
      "removed" -> removed.asJson
    ))

  /** 写前台账的目标行：某目标文件在**本轮起跑线**上的 sha256 与字节数（`label` =
    * 队列 target 名，`path` = 绝对路径）。 */
  final case class PreflightTarget(label: String, path: String, sha256: String, bytes: Long)

  /** 写前台账（C 批 ①，2026-09-13）：整轮**一条聚合行**（**禁**逐条 273 行/轮 —— 避免把
    * 20000 行阈值烧穿）。字段见类头注：授权 ref 全集 + 计数 + 每目标起始 sha256/bytes +
    * 落地前快照目录。写失败由调用方 WARN 处置（台账是审计面，不是落地屏障）。 */
  def appendPreflight(
    atMs: Long,
    actor: String,
    trigger: String,
    authorizedRefs: List[String],
    pendingTotal: Int,
    targets: List[PreflightTarget],
    snapshotDir: String,
    snapshotLabel: String
  ): Either[String, Unit] =
    append(obj(
      "kind"       -> KindPreflight.asJson,
      "at"         -> isoOf(atMs).asJson,
      "atMs"       -> atMs.asJson,
      "actor"      -> actor.asJson,
      "trigger"    -> trigger.asJson,
      "authorized" -> authorizedRefs.size.asJson,
      "pending"    -> pendingTotal.asJson,
      "refs"       -> authorizedRefs.asJson,
      "targets" -> targets.map(t =>
        obj(
          "label"  -> t.label.asJson,
          "path"   -> t.path.asJson,
          "sha256" -> t.sha256.asJson,
          "bytes"  -> t.bytes.asJson
        )).asJson,
      "snapshot" -> obj("dir" -> snapshotDir.asJson, "label" -> snapshotLabel.asJson)
    ))

  /** 追加一条事件。**不抛异常**：失败返回 Left（调用方按「不失败主操作 + 结果行 NOTE +
    * WARN」处置，绝不静默）。 */
  private def append(record: Json): Either[String, Unit] =
    JsonlLedger
      .appendLine(historyPath, archivePath, record.asJson.noSpaces, MaxActiveBytes, MaxActiveLines)
      .left
      .map { reason =>
        logger.warnSync(s"[memory-history] append failed: $reason")
        reason
      }

  // ------------------------------------------------------------------
  // 读
  // ------------------------------------------------------------------

  /** 解析后的事件视图（未知键照收；缺失键取默认）。 */
  final case class Event(
    kind: String,
    at: String,
    atMs: Long,
    actor: String,
    ref: Option[String] = None,
    target: Option[String] = None,
    action: Option[String] = None,
    section: Option[String] = None,
    matchText: Option[String] = None,
    content: Option[String] = None,
    result: Option[String] = None,
    by: Option[String] = None,
    detail: Option[String] = None,
    path: Option[String] = None,
    trigger: Option[String] = None,
    refs: List[String] = Nil,
    added: List[String] = Nil,
    removed: List[String] = Nil
  )

  final case class ReadResult(events: Vector[Event], unreadable: Int, ioError: Option[String])

  private def field(j: Json, k: String): Option[Json] = j.asObject.flatMap(_(k))

  private def str(j: Json, k: String): Option[String] = field(j, k).flatMap(_.asString)
  private def long(j: Json, k: String): Long = field(j, k).flatMap(_.asNumber).flatMap(_.toLong).getOrElse(0L)
  private def strs(j: Json, k: String): List[String] =
    field(j, k).flatMap(_.asArray).map(_.flatMap(_.asString).toList).getOrElse(Nil)

  private[tools] def decode(line: String): Option[Event] =
    parse(line).toOption.flatMap { j =>
      str(j, "kind").map { kind =>
        Event(
          kind = kind,
          at = str(j, "at").getOrElse(""),
          atMs = long(j, "atMs"),
          actor = str(j, "actor").getOrElse(ActorUnknown),
          ref = str(j, "ref"),
          target = str(j, "target"),
          action = str(j, "action"),
          section = str(j, "section"),
          matchText = str(j, "match"),
          content = str(j, "content"),
          result = str(j, "result"),
          by = str(j, "by"),
          detail = str(j, "detail"),
          path = str(j, "path"),
          trigger = str(j, "trigger"),
          refs = strs(j, "refs"),
          added = strs(j, "added"),
          removed = strs(j, "removed")
        )
      }
    }

  /** 全量读取（两代，升序）。 */
  def readAll(): ReadResult =
    val back = JsonlLedger.readLines(historyPath, archivePath)
    var unreadable = 0
    val out = Vector.newBuilder[Event]
    back.lines.foreach { l =>
      val t = l.trim
      if t.startsWith("{") && t.endsWith("}") then
        decode(t) match
          case Some(ev) => out += ev
          case None     => unreadable += 1
      else unreadable += 1
    }
    ReadResult(out.result(), unreadable, back.ioError)

  /** 按 kind 过滤。 */
  def ofKind(kind: String): Vector[Event] = readAll().events.filter(_.kind == kind)

  // ------------------------------------------------------------------
  // 对账（occurrence 级）
  // ------------------------------------------------------------------
  //
  // 配对语义（1:1，由写侧保证）：`MemoryQueue.enqueue` 落 1 条 `history:queue` 行/note；
  // `MemoryQueue.recordOutcome` 在**同一次**调用里落 1 条 queue `outcome` 行 + 1 条
  // `history:consume` 行（`MemoryQueue.scala:458-476`）⇒ 同一 ref 的第 i 次 outcome 与
  // 第 i 条 consume 行互为一对。**任何绕过 `recordOutcome` 直写 queue.jsonl 的通道
  // （如运维只读对账脚本）都会只增 outcome 不增 consume** —— 这正是集合口径看不见、
  // occurrence 口径必须报出的缺口形态。

  /** occurrence 级对账结果：三差集（人读描述）+ 计数（阈值/接线面）。
    * `missing*` 的每条 = **一个未配对的 occurrence**（同 ref 缺失 N 条就有 N 条）。 */
  final case class Gaps(
    missingQueue: Vector[String],
    missingConsume: Vector[String],
    orphanConsume: Vector[String]
  ):
    def total: Int       = missingQueue.size + missingConsume.size + orphanConsume.size
    def isEmpty: Boolean = total == 0

    /** 差集描述（旧 `discrepancies` 的返回面：顺序与分类与旧口径一致）。 */
    def messages: List[String] = (missingQueue ++ missingConsume ++ orphanConsume).toList

  object Gaps:
    val empty: Gaps = Gaps(Vector.empty, Vector.empty, Vector.empty)

  /** ref → occurrence 数（保**首次出现序**：结果确定性，不依赖哈希迭代序）。 */
  private def occurrenceCount(refs: Iterable[String]): Vector[(String, Int)] =
    val m = mutable.LinkedHashMap.empty[String, Int]
    refs.foreach(r => m.update(r, m.getOrElse(r, 0) + 1))
    m.toVector

  /** occurrence 级对账（纯函数；`events` 由调用方给，spec 可对任意账本直测）。 */
  def reconciliationOf(noteRefs: List[String], outcomeRefs: List[String], events: Vector[Event]): Gaps =
    val queuedOcc   = occurrenceCount(events.filter(_.kind == KindQueue).flatMap(_.ref))
    val consumedOcc = occurrenceCount(events.filter(_.kind == KindConsume).flatMap(_.ref))
    val noteOcc     = occurrenceCount(noteRefs)
    val outcomeOcc  = occurrenceCount(outcomeRefs)
    val queuedMap   = queuedOcc.toMap
    val consumedMap = consumedOcc.toMap
    val noteSet     = noteOcc.map(_._1).toSet
    val outcomeMap  = outcomeOcc.toMap

    // 队列有 note（第 i 次）无 history:queue（第 i 条）
    val missingQueue = noteOcc.flatMap { (r, n) =>
      val m = queuedMap.getOrElse(r, 0)
      if n <= m then Nil
      else (m + 1 to n).toList.map(i =>
        s"note $r occurrence $i of $n on the queue side has no history:queue line (history:queue has $m)")
    }

    // 队列有 outcome（第 i 次）无 history:consume（第 i 条）—— 重复消费缺口在此显形
    val missingConsume = outcomeOcc.flatMap { (r, n) =>
      val m = consumedMap.getOrElse(r, 0)
      if n <= m then Nil
      else (m + 1 to n).toList.map(i =>
        s"outcome $r occurrence $i of $n on the queue side has no history:consume line (history:consume has $m)")
    }

    // history 有 consume（第 i 条）无配对 outcome（第 i 次）；ref 侧无 note 时一并标注
    val orphanConsume = consumedOcc.flatMap { (r, n) =>
      val m      = outcomeMap.getOrElse(r, 0)
      val noNote = if noteSet.contains(r) then "" else " (no note on the queue side)"
      if n > m then
        (m + 1 to n).toList.map(i =>
          s"history:consume $r occurrence $i of $n in history has no paired outcome occurrence (outcomes on the queue side: $m)$noNote")
      else if !noteSet.contains(r) then List(s"history:consume $r has no note")
      else Nil
    }

    Gaps(missingQueue, missingConsume, orphanConsume)

  /** occurrence 级对账（读盘面）。 */
  def reconciliation(noteRefs: List[String], outcomeRefs: List[String]): Gaps =
    reconciliationOf(noteRefs, outcomeRefs, readAll().events)

  /** 对账（IMPL-1 验收口径：history ↔ note/outcome 三者可对账）：返回差集描述。
    * 空 = 三者一致。队列侧 refs 由调用方传入（避免 core 内两账本互相 import 成环）。
    *
    * **occurrence 级**：`noteRefs` / `outcomeRefs` 的**列表多重度即 occurrence 数**
    * （同 ref 出现 N 次 = N 个 occurrence），逐条与账本里的 `history:queue` /
    * `history:consume` 行配对；缺第 i 条即报一条。集合差口径对该形态恒 0。 */
  def discrepancies(noteRefs: List[String], outcomeRefs: List[String]): List[String] =
    reconciliation(noteRefs, outcomeRefs).messages

  /** 计数摘要（运维/诊断；不消费任何状态）。 */
  final case class Stats(queued: Int, consumed: Int, changed: Int, unreadable: Int)

  def stats(): Stats =
    val r = readAll()
    Stats(
      queued = r.events.count(_.kind == KindQueue),
      consumed = r.events.count(_.kind == KindConsume),
      changed = r.events.count(_.kind == KindChange),
      unreadable = r.unreadable
    )

  /** 行级 diff（纯函数，spec 直测面）：`removed` = 只在 before 中的行（保序，
    * 含重复计数），`added` = 只在 after 中的行。两侧空串 ⇒ 双空。 */
  def lineDiff(before: String, after: String): (List[String], List[String]) =
    def lines(s: String): Vector[String] =
      if s.isEmpty then Vector.empty else s.split("\n", -1).toVector
    val b = lines(before)
    val a = lines(after)
    val removed = mutable.ListBuffer[String]()
    val added   = mutable.ListBuffer[String]()
    val aCount  = mutable.Map.empty[String, Int].withDefaultValue(0)
    a.foreach(l => aCount(l) += 1)
    b.foreach { l =>
      val c = aCount(l)
      if c > 0 then aCount(l) = c - 1 else removed += l
    }
    val bCount = mutable.Map.empty[String, Int].withDefaultValue(0)
    b.foreach(l => bCount(l) += 1)
    a.foreach { l =>
      val c = bCount(l)
      if c > 0 then bCount(l) = c - 1 else added += l
    }
    (removed.toList, added.toList)

end MemoryHistory
