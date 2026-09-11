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
 *
 * 三者以 `ref`（= 队列 note id `q-…`）可对账：`MemoryHistory.discrepancies`
 * 给出「队列有 note 无 history 行 / 有 outcome 无 consume 行」的差集。
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
  val KindQueue   = "queue"
  val KindConsume = "consume"
  val KindChange  = "change"

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

  /** 追加一条事件。**不抛异常**：失败返回 Left（调用方按「不失败主操作 +
   * 结果行 NOTE + WARN」处置，绝不静默）。 */
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

  /** 对账（IMPL-1 验收口径：history ↔ note/outcome 三者可对账）：返回差集描述。
    * 空 = 三者一致。队列侧 refs 由调用方传入（避免 core 内两账本互相 import 成环）。 */
  def discrepancies(noteRefs: List[String], outcomeRefs: List[String]): List[String] =
    val events = readAll().events
    val queued = events.filter(_.kind == KindQueue).flatMap(_.ref).toSet
    val consumed = events.filter(_.kind == KindConsume).flatMap(_.ref).toSet
    val missingQueue = noteRefs.filterNot(queued.contains).map(r => s"note $r has no history:queue line")
    val missingConsume = outcomeRefs.filterNot(consumed.contains).map(r => s"outcome $r has no history:consume line")
    val orphanConsume = consumed.filterNot(noteRefs.contains).map(r => s"history:consume $r has no note").toList
    missingQueue ++ missingConsume ++ orphanConsume

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
