package nebflow.core.project

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse as jsonParse
import io.circe.syntax.*
import nebflow.core.{AtomicJson, NebflowLogger, PathUtil}

import java.time.{Instant, OffsetDateTime, ZoneId}
import java.time.format.DateTimeFormatter
import scala.collection.mutable.ListBuffer

/**
 * 文档索引维护消费者（P3 引擎侧归档联动批 2026-09-10，spec
 * `20260910_process-doc-chain-attribution-spec.md` §6.2/§6.3/§9.2 项 9）。
 *
 * 职责边界（**只读文档、只写索引**）：
 * - 消费**引擎事件** `<workspace>/.nebflow/flow-map-events.jsonl` 的链族事件
 *   （`chain-archived` / `chain-restored`，见 [[FlowMapEventLog]]）→ 翻转
 *   `<域>/INDEX.md` 条目的 `state`（active ↔ archived）并把链块在
 *   「活跃链分区（active）」↔「已归档链分区（archived）」之间迁移。
 * - 消费**归档事实** `<workspace>/.nebflow/flow-map-archive/<batchId>.json` 的 batchId 全集
 *   ↔ 索引 `chain` 集 → 对账报表（missing-in-index / stale-in-index）。
 *
 * 硬纪律（spec §6.2 注 + §1.2）：
 * 1. **禁止实现归档资格判据**——资格（completed/failed 已上报/cancelled 放行/blocked
 *    永不过）是引擎推导单点 [[FlowMapStore.chainArchivable]] 的事，本消费者只认事件
 *    与归档区事实。前端 `flowMapArchive.js` 旧判据镜像（[[FlowMapStore]] :571-573）
 *    的双端漂移教训即此禁令的来由。
 * 2. **禁止链路派生**——链 id 只来自事件顶层 `chainId`（缺则 summary 的 `chain=`）、
 *    归档文件名/`batch` 字段、索引既有条目与标题。**本文件不引入任何
 *    `topologicalChains` / `chainIdOf` 调用**（同一条链在两个进程里各派生一次 = 必然
 *    漂移）。
 * 3. **文件本体零变化**——只改写名为 `INDEX.md` 的索引文件（[[IndexFileName]] 白名单
 *    校验），绝不创建/删除/改写任何被索引的文档，也绝不新建 INDEX.md。
 * 4. **无条目/无索引即跳过**——INDEX.md 不存在、或链在索引里查无条目 → 记日志跳过
 *    （不新建分区、不自创链标题、不猜链名）。
 *
 * 幂等：事件按文件顺序回放折叠（同 chainId 后写覆盖先写）→ 期望状态；变换是不动点
 * （第二次应用零 diff，不写盘）。
 *
 * **未接线接口点**（登记，勿当已落地）：
 * - `chain-restored`：链抽象 P2 `restoreChain` 尚未落地 → 本批只定义事件类型
 *   （[[FlowMapEventLog.ChainRestoredType]]）+ 消费侧回翻分支（[[transformIndex]]），
 *   无写入点（禁止虚构调用点）。P2 落地时在 restore 调用点追加对称事件即可激活。
 * - [[applyChainEvents]]：索引翻转入口，调用方 = 分发器/调度节点/离线任务（本批不
 *   自动接线——索引域 `<dataRoot>/docs` 不属项目工作区，无 roots 配置源）。
 *   已接线的是对账兜底 [[tick]]（挂 ProjectActor.TtlTick 30s 周期，内部 10min 节流）。
 */
object DocIndexConsumer:

  /** 索引文件名（唯一允许被本消费者改写的文件名；白名单校验见 [[applyToIndex]]）。 */
  val IndexFileName = "INDEX.md"

  /** 对账报表文件名（落 `<workspace>/.nebflow/tmp/`，过程件域，非文档域）。 */
  val ReportFileName = "doc-index-reconcile.json"

  /** 索引扫描最大深度（`<root>/<域>/INDEX.md` = 2 层；留一层余量）。 */
  val MaxIndexScanDepth = 3

  /** 对账兜底节流间隔（挂 30s TtlTick 周期，内部按此节流；JVM 内全局单点）。 */
  val DefaultReconcileIntervalMs: Long = 10 * 60 * 1000L

  /** 条目 state 值域（spec §5.2：`active` | `archived`，索引自治可变）。 */
  val StateActive = "active"
  val StateArchived = "archived"

  private val logger = NebflowLogger.forName("nebflow.docindex")

  // ── 事件侧（只解析，不派生） ─────────────────────────────────

  /** 链族事件类型（消费白名单）。 */
  val ChainEventTypes: Set[String] =
    Set(FlowMapEventLog.ChainArchivedType, FlowMapEventLog.ChainRestoredType)

  /** 事件行模型：`chainId` 优先取顶层可选字段，缺则回退 summary 的 `chain=`（旧行/他
    * 写点零迁移兼容）；`fields` = 结构化 summary 的 `k=v` 解析结果。 */
  final case class ChainEvent(
    ts: Long,
    typ: String,
    project: String,
    nodeId: String,
    chainId: Option[String],
    summary: String,
    fields: Map[String, String]
  ):
    /** 事件时刻（归档事件 = archivedAt，拉回事件 = restoredAt；缺则回退行 ts）。 */
    def atMs: Long =
      fields.get("archivedAt").orElse(fields.get("restoredAt")).flatMap(_.toLongOption).getOrElse(ts)

  /** 解析一行事件（无效 JSON / 缺 type → None，调用方计入 parseErrors）。 */
  def parseEventLine(line: String): Option[ChainEvent] =
    val trimmed = line.trim
    if trimmed.isEmpty then None
    else
      jsonParse(trimmed).toOption.flatMap(_.asObject).map { o =>
        val summary = o("summary").flatMap(_.asString).getOrElse("")
        val fields = FlowMapEventLog.parseChainSummary(summary)
        ChainEvent(
          ts = o("ts").flatMap(_.asNumber).flatMap(_.toLong).getOrElse(0L),
          typ = o("type").flatMap(_.asString).getOrElse(""),
          project = o("project").flatMap(_.asString).getOrElse(""),
          nodeId = o("nodeId").flatMap(_.asString).getOrElse(""),
          chainId = o("chainId").flatMap(_.asString).map(_.trim).filter(_.nonEmpty).orElse(fields.get("chain")),
          summary = summary,
          fields = fields
        )
      }

  /** 链的期望状态（事件回放折叠结果）。members 仅留痕（事件载荷事实），不参与判定。 */
  final case class DesiredState(chainId: String, archived: Boolean, atMs: Long, members: Option[Int])

  /** 事件回放折叠：append-only 文件顺序 = 时序，同 chainId **后写覆盖先写**
    * （archived → restored → archived 三事件链 = 末态 archived）。无 chainId 的链族
    * 事件无法定位链 → 丢弃（消费者无从派生链 id）。 */
  def desiredStates(events: List[ChainEvent]): Map[String, DesiredState] =
    events.foldLeft(Map.empty[String, DesiredState]) { (acc, e) =>
      e.chainId match
        case Some(cid) if e.typ == FlowMapEventLog.ChainArchivedType =>
          acc.updated(cid, DesiredState(cid, archived = true, e.atMs, e.fields.get("members").flatMap(_.toIntOption)))
        case Some(cid) if e.typ == FlowMapEventLog.ChainRestoredType =>
          acc.updated(cid, DesiredState(cid, archived = false, e.atMs, e.fields.get("members").flatMap(_.toIntOption)))
        case _ => acc
    }

  // ── 索引文档模型（纯解析 / 纯变换） ───────────────────────────

  private enum SectionKind:
    case Active, Archived, Unattributed, Live, Other
  import SectionKind.*

  private val SectionHeadingRe = """^##(?!#)\s*(.*?)\s*$""".r
  private val SectionKindRe = """[（(]\s*(active|archived|unattributed|live)\s*[)）]""".r
  private val BlockHeadingRe = """^###\s+.*$""".r
  private val ChainIdTokenRe = """(chain-[^\s·|,，、（）()\[\]]+)""".r

  private def kindOf(heading: String): SectionKind =
    SectionKindRe.findFirstMatchIn(heading).map(_.group(1).toLowerCase) match
      case Some("active")       => Active
      case Some("archived")     => Archived
      case Some("unattributed") => Unattributed
      case Some("live")         => Live
      case _                    => Other

  /** 链块：`lines` 含标题行（若存在）与后续所有行（含尾部空行，逐字保留 → 未改动
    * 区块渲染逐字节同原文）。 */
  private final case class Block(lines: List[String]):
    def headingLine: Option[String] = lines.headOption.filter(l => BlockHeadingRe.matches(l))
    def chainId: Option[String] = headingLine.flatMap(h => ChainIdTokenRe.findFirstMatchIn(h).map(_.group(1)))
    def withHeading(nl: String): Block = copy(lines = nl :: lines.drop(1))

  private final case class Section(kind: SectionKind, heading: String, prelude: List[String], blocks: List[Block])

  private final case class TableSchema(stateIdx: Option[Int], chainIdxs: List[Int], docIdx: Option[Int])

  private final case class RowInfo(idx: Int, chains: List[String], stateIdx: Option[Int],
                                   state: Option[String], docKey: Option[String])

  private final case class StaleEntry(chainId: String, doc: Option[String], indexPath: String)

  private def splitLines(content: String): List[String] = content.split("\n", -1).toList

  private def parseIndex(content: String): (List[String], List[Section]) =
    val preamble = ListBuffer.empty[String]
    val raw = ListBuffer.empty[(String, ListBuffer[String])]
    splitLines(content).foreach { l =>
      if SectionHeadingRe.matches(l) then raw += ((l, ListBuffer.empty[String]))
      else
        raw.lastOption match
          case Some((_, body)) => body += l
          case None            => preamble += l
    }
    val sections = raw.toList.map { case (heading, body) => buildSection(heading, body.toList) }
    (preamble.toList, sections)

  private def buildSection(heading: String, body: List[String]): Section =
    val pre = ListBuffer.empty[String]
    val raw = ListBuffer.empty[ListBuffer[String]]
    body.foreach { l =>
      if BlockHeadingRe.matches(l) then raw += ListBuffer(l)
      else
        raw.lastOption match
          case Some(buf) => buf += l
          case None      => pre += l
    }
    Section(kindOf(heading), heading, pre.toList, raw.toList.map(b => Block(b.toList)))

  private def renderIndex(preamble: List[String], sections: List[Section]): String =
    (preamble ++ sections.flatMap(s => s.heading :: (s.prelude ++ s.blocks.flatMap(_.lines)))).mkString("\n")

  // ── 表格行解析 ────────────────────────────────────────────

  private def isTableRow(l: String): Boolean = l.trim.startsWith("|")

  private def cellsOf(l: String): List[String] =
    val t = l.trim
    if !t.startsWith("|") then Nil
    else
      val body = t.stripPrefix("|")
      val body2 = if body.endsWith("|") then body.dropRight(1) else body
      body2.split("\\|", -1).toList.map(_.trim)

  private def isSeparatorCells(cells: List[String]): Boolean =
    cells.nonEmpty && cells.forall(c => c.nonEmpty && c.forall(ch => ch == '-' || ch == ':' || ch == ' '))

  private def isHeaderCells(cells: List[String]): Boolean =
    cells.exists(c => Set("doc", "文档", "file", "path", "time", "状态", "state").contains(c.toLowerCase))

  private def isSeparatorRow(l: String): Boolean = isTableRow(l) && isSeparatorCells(cellsOf(l))
  private def isHeaderRow(l: String): Boolean = isTableRow(l) && !isSeparatorRow(l) && isHeaderCells(cellsOf(l))

  private def schemaOf(header: List[String]): TableSchema =
    def idx(names: Set[String]): Option[Int] =
      header.indexWhere(c => names.contains(c.toLowerCase.trim)) match
        case -1 => None
        case i  => Some(i)
    val chainIdx = idx(Set("chain", "链"))
    val chainsIdx = idx(Set("chains", "多链", "链集"))
    TableSchema(
      stateIdx = idx(Set("state", "status", "状态", "归档状态")),
      chainIdxs = List(chainIdx, chainsIdx).flatten,
      docIdx = idx(Set("doc", "文档", "file", "path", "文件"))
    )

  /** 单元格内的链 id 列表（`[chain-a, chain-b]` / `chain-a chain-b` / `chain-a`）。 */
  private def chainsInCell(v: String): List[String] =
    v.replace("[", " ").replace("]", " ")
      .split("[\\s,，;；、|`'\"]+")
      .toList
      .map(_.trim)
      .filter(_.startsWith("chain-"))
      .distinct

  /** 表内数据行（表头驱动列定位；无表头的表 → 解析不出行，调用方按「不可解析」记日志）。 */
  private def rowsOf(lines: List[String]): List[RowInfo] =
    var schema: Option[TableSchema] = None
    val out = ListBuffer.empty[RowInfo]
    var i = 0
    while i < lines.size do
      val l = lines(i)
      if isTableRow(l) then
        val cells = cellsOf(l)
        if isHeaderCells(cells) && !isSeparatorCells(cells) then
          schema = Some(schemaOf(cells))
        else if !isSeparatorCells(cells) then
          schema match
            case Some(sc) =>
              val chains = sc.chainIdxs.flatMap(j => cells.lift(j).toList.flatMap(chainsInCell)).distinct
              val stateIdx = sc.stateIdx.filter(j => cells.lift(j).isDefined)
              val state = stateIdx.map(cells).map(_.trim).filter(_.nonEmpty)
              val docKey = sc.docIdx.flatMap(j => cells.lift(j)).map(_.trim).filter(_.nonEmpty)
              out += RowInfo(i, chains, stateIdx, state, docKey)
            case None => ()
      i += 1
    out.toList

  private def setStateCell(line: String, stateIdx: Int, value: String): Option[String] =
    val cells = cellsOf(line)
    if stateIdx >= cells.size || cells(stateIdx).equalsIgnoreCase(value) then None
    else
      val indent = line.takeWhile(_.isWhitespace)
      Some(indent + cells.updated(stateIdx, value).mkString("| ", " | ", " |"))

  // ── 纯变换（幂等不动点） ─────────────────────────────────────

  /** 变换结果：`changed=false` 时 [[content]] 与输入逐字节相同（调用方据此免写盘）。 */
  final case class TransformResult(content: String, changed: Boolean, stateFlips: Int,
                                   blocksMoved: Int, notes: List[String])

  private val IsoSecFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

  /** 秒级 ISO-8601 + 时区偏移（spec §5.1 归档分区标题形态）。 */
  def isoSeconds(ms: Long): String =
    IsoSecFmt.format(OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()))

  private def withArchivedStamp(heading: String, iso: String): String =
    if heading.toLowerCase.contains("· archived") || heading.toLowerCase.contains(" archived ") then heading
    else s"$heading · archived $iso"

  private def withoutArchivedStamp(heading: String): String =
    heading.replaceAll("""\s*·\s*archived\s+\S+\s*$""", "")

  private def appendLines(body: List[String], extra: List[String]): List[String] =
    if extra.isEmpty then body
    else if body.isEmpty then extra
    else if body.last.trim.isEmpty then body ++ extra
    else body ++ ("" :: extra)

  /** 行级 state 翻转：`force` = 块整体迁移时的强制口径（None = 按行链集判定——
    * 行链集全归档 → archived；行链集命中拉回链 → active；无链/未定态 → 不动）。
    * 返 (块, 翻转行数, 无法翻转的行数(缺 state 列))。 */
  private def flipRows(b: Block, archivedSet: Set[String], restoredSet: Set[String],
                       force: Option[Boolean]): (Block, Int, Int) =
    val rows = rowsOf(b.lines)
    if rows.isEmpty then (b, 0, 0)
    else
      val buf = b.lines.toArray
      var flips = 0
      var noStateCol = 0
      rows.foreach { r =>
        val target = force.orElse {
          if r.chains.nonEmpty && r.chains.forall(archivedSet.contains) then Some(true)
          else if r.chains.exists(restoredSet.contains) then Some(false)
          else None
        }
        target.foreach { toArchived =>
          r.stateIdx match
            case Some(si) =>
              setStateCell(buf(r.idx), si, if toArchived then StateArchived else StateActive) match
                case Some(nl) => buf(r.idx) = nl; flips += 1
                case None     => ()
            case None => noStateCol += 1
        }
      }
      (b.copy(lines = buf.toList), flips, noStateCol)

  /** 目标分区落位：已有同链块 → 行合并（按 doc 键去重，保原块标题与位置）；无 → 追加
    * （归档目标补 `· archived <iso>` 时间戳，拉回目标剥除该后缀）。 */
  private def appendOrMerge(blocks: List[Block], incoming: Block, archived: Boolean, iso: String,
                           notes: ListBuffer[String]): (List[Block], Boolean) =
    val existingIdx = incoming.chainId match
      case Some(cid) => blocks.indexWhere(_.chainId.contains(cid))
      case None      => -1
    if existingIdx >= 0 then
      val existing = blocks(existingIdx)
      val existingDocKeys = rowsOf(existing.lines).flatMap(_.docKey).toSet
      val incomingRows = rowsOf(incoming.lines)
      val newRows = incomingRows.filterNot(r => r.docKey.exists(existingDocKeys.contains))
      if newRows.isEmpty then (blocks, false)
      else
        val hasTable = existing.lines.exists(isTableRow)
        val headerLines =
          if hasTable then Nil
          else incoming.lines.filter(l => isTableRow(l) && (isHeaderRow(l) || isSeparatorRow(l)))
        val merged = appendLines(existing.lines, headerLines ++ newRows.map(r => incoming.lines(r.idx)))
        if newRows.size < incomingRows.size then
          notes += s"merge-dedup: ${incoming.chainId.getOrElse("(no-chain)")} (${incomingRows.size - newRows.size} 条重复 doc 行丢弃)"
        (blocks.updated(existingIdx, existing.copy(lines = merged)), true)
    else
      val withHeading = incoming.headingLine match
        case Some(h) => incoming.withHeading(if archived then withArchivedStamp(h, iso) else withoutArchivedStamp(h))
        case None =>
          notes += s"block-without-heading: ${incoming.chainId.getOrElse("(no-chain)")} (行级迁移，未生成标题——不自创链标题)"
          incoming
      (blocks :+ withHeading, true)

  /** 索引变换（纯函数、幂等）：
    * - 链块从非归档分区迁入「已归档链分区（archived）」（标题补归档时间戳），块内行
    *   state → archived；反向（链块从归档分区迁回「活跃链分区（active）」）剥时间戳、
    *   行 state → active。**块级口径**：块内条目随块整体翻（spec §6.3「同一链分区下的
    *   文档条目在同一时间随链归档事件翻状态」一次事件批量翻，含块内多链行）。
    * - 未被迁移的行（无链标题的松散表、块内不随块的其他情形）按**行链集**判定：行链集
    *   全部归档 → archived；行链集命中拉回链 → active；两者都不满足（含无链的行）→
    *   不动。多链行部分归档时不离场（spec 未覆盖多链行，取保守口径）。
    * - 目标分区缺失 / 无四分区结构 / 查无该链条目 → 结构零改写，记 note（不新建分区、
    *   不自创链标题、不猜条目）。 */
  def transformIndex(content: String, desired: Map[String, DesiredState]): TransformResult =
    val (preamble, sections) = parseIndex(content)
    if desired.isEmpty || sections.isEmpty then
      TransformResult(content, changed = false, 0, 0,
        if sections.isEmpty then List("index-has-no-sections") else Nil)
    else
      val notes = ListBuffer.empty[String]
      val archById = desired.values.filter(_.archived).map(d => d.chainId -> d).toMap
      val restById = desired.values.filterNot(_.archived).map(d => d.chainId -> d).toMap
      val archSet = archById.keySet
      val restSet = restById.keySet
      val hasArchivedSection = sections.exists(_.kind == Archived)
      val hasActiveSection = sections.exists(_.kind == Active)
      var flips = 0
      var moved = 0
      val toArchive = ListBuffer.empty[(Block, String)]
      val toActive = ListBuffer.empty[Block]
      val kept = ListBuffer.empty[Section]
      sections.foreach { s =>
        // 分区前导行（无 `### ` 链标题的松散表，如 unattributed/live 的条目表）：
        // 无链标题 → 不迁移，只按行链集判定翻 state（条目级口径，同 kept 块）
        val (prelude2, pf, pmiss) = flipRows(Block(s.prelude), archSet, restSet, force = None)
        flips += pf
        if pmiss > 0 then notes += s"no-state-column: ${s.heading.trim} 前导表 ($pmiss 行未翻 state)"
        val keep = ListBuffer.empty[Block]
        s.blocks.foreach { b =>
          val target: Option[Boolean] = s.kind match
            case Archived => b.chainId.filter(restSet.contains).map(_ => false)
            case Other    => None
            case _        => b.chainId.filter(archSet.contains).map(_ => true)
          val movable =
            target match
              case Some(true)  => hasArchivedSection
              case Some(false) => hasActiveSection
              case None        => false
          if target.isDefined && !movable then
            notes += s"target-section-missing: ${b.chainId.getOrElse("(no-chain)")} (结构未动，仅按行翻 state)"
          if movable then
            val (nb, f, miss) = flipRows(b, archSet, restSet, force = target)
            flips += f
            moved += 1
            if miss > 0 then notes += s"no-state-column: ${b.chainId.getOrElse("(no-chain)")} ($miss 行未翻 state)"
            if nb.lines.exists(isTableRow) && rowsOf(nb.lines).isEmpty then
              notes += s"no-table-header: ${b.chainId.getOrElse("(no-chain)")} (state 未翻转，块整体迁移)"
            target match
              case Some(true) =>
                val iso = isoSeconds(b.chainId.flatMap(archById.get).map(_.atMs).getOrElse(System.currentTimeMillis()))
                toArchive += ((nb, iso))
              case _ => toActive += nb
          else
            val (nb, f, miss) = flipRows(b, archSet, restSet, force = None)
            flips += f
            if miss > 0 then notes += s"no-state-column: ${b.chainId.getOrElse("(no-chain)")} ($miss 行未翻 state)"
            keep += nb
        }
        kept += s.copy(prelude = prelude2.lines, blocks = keep.toList)
      }
      if toArchive.nonEmpty || toActive.nonEmpty || flips > 0 then
        val rebuilt = kept.toList.map { s =>
          s.kind match
            case Archived if toArchive.nonEmpty =>
              var blocks = s.blocks
              toArchive.foreach { case (b, iso) => blocks = appendOrMerge(blocks, b, archived = true, iso, notes)._1 }
              s.copy(blocks = blocks)
            case Active if toActive.nonEmpty =>
              var blocks = s.blocks
              toActive.foreach { b => blocks = appendOrMerge(blocks, b, archived = false, "", notes)._1 }
              s.copy(blocks = blocks)
            case _ => s
        }
        val out = renderIndex(preamble, rebuilt)
        TransformResult(out, changed = out != content, flips, moved, notes.toList)
      else TransformResult(content, changed = false, 0, 0, notes.toList)

  /** 索引内出现过的链 id 集（块标题 + 行 chain/chains 单元格）——对账与「条目缺失」判定
    * 共用，纯读取、零派生。 */
  def chainIdsIn(content: String): Set[String] =
    val (_, sections) = parseIndex(content)
    sections.flatMap(s => s.blocks.flatMap(b => b.chainId.toList ++ rowsOf(b.lines).flatMap(_.chains))).toSet

  private def staleEntries(path: String, content: String, archiveChains: Set[String]): List[StaleEntry] =
    val (_, sections) = parseIndex(content)
    sections.flatMap(_.blocks).flatMap { b =>
      rowsOf(b.lines).flatMap { r =>
        val hit = r.chains.filter(archiveChains.contains)
        if hit.nonEmpty && r.state.exists(_.equalsIgnoreCase(StateActive)) then
          List(StaleEntry(hit.head, r.docKey, path))
        else Nil
      }
    }

  // ── IO 入口 ───────────────────────────────────────────────

  /** 单索引文件应用结果。 */
  final case class IndexApplyResult(indexPath: String, changed: Boolean, stateFlips: Int,
                                    blocksMoved: Int, notes: List[String])

  /** 索引维护批次报告。 */
  final case class ApplyReport(
    workspace: String,
    eventLines: Int,
    chainEvents: Int,
    desiredStates: Map[String, Boolean],
    indexFiles: List[String],
    results: List[IndexApplyResult],
    /** 事件有、索引查无条目的链（跳过 + 已记日志；不新建分区/不猜条目）。 */
    skippedChains: List[String],
    parseErrors: Int,
    dryRun: Boolean
  )

  /** 对账报表：missing-in-index = 归档区有事实、索引 chain 集无；stale-in-index =
    * 索引条目 state=active 但链已在归档区。**只报表，不改文档本体。** */
  final case class ReconcileReport(
    workspace: String,
    generatedAt: Long,
    archiveChains: List[String],
    indexChains: List[String],
    missingInIndex: List[String],
    staleInIndex: List[String],
    indexFiles: List[String],
    reportPath: String
  ):
    def isClean: Boolean = missingInIndex.isEmpty && staleInIndex.isEmpty

  private def eventsPath(workspace: String): os.Path =
    os.Path(workspace, PathUtil.dataRoot) / ".nebflow" / FlowMapEventLog.FileName

  private def archiveDir(workspace: String): os.Path =
    os.Path(workspace, PathUtil.dataRoot) / ".nebflow" / FlowMapStore.ArchiveDirName

  private def reportPath(workspace: String): os.Path =
    os.Path(workspace, PathUtil.dataRoot) / ".nebflow" / "tmp" / ReportFileName

  private def readEvents(workspace: String): IO[(List[ChainEvent], Int)] =
    val p = eventsPath(workspace)
    IO.blocking(if os.exists(p) && os.isFile(p) then os.read.lines(p).toList else Nil).map { lines =>
      val parsed = lines.map(parseEventLine)
      (parsed.flatten, parsed.count(_.isEmpty))
    }

  /** 归档事实：`flow-map-archive/<batchId>.json` 的 batchId 全集（文件名 = batchId，
    * 单一事实源，spec §6.1 分文件布局）。 */
  def archiveChainIds(workspace: String): IO[Set[String]] =
    val dir = archiveDir(workspace)
    IO.blocking {
      if os.exists(dir) && os.isDir(dir) then
        os.list(dir).filter(p => os.isFile(p) && p.last.endsWith(".json")).map(_.last.stripSuffix(".json")).toSet
      else Set.empty[String]
    }

  /** 扫描索引根下的 INDEX.md（存在才算；**绝不创建**）。 */
  def scanIndexFiles(roots: List[String]): IO[List[os.Path]] =
    IO.blocking {
      roots.filter(r => os.exists(os.Path(r))).flatMap { r =>
        os.walk(os.Path(r), maxDepth = MaxIndexScanDepth)
          .filter(p => p.last == IndexFileName && os.isFile(p))
          .toList
      }.distinct.sortBy(_.toString)
    }

  /** 索引维护入口（消费者主函数）：读事件 → 折叠期望状态 → 翻转/迁移所有 INDEX.md。
    * 未接线（调用方 = 分发器/调度节点/离线任务；本批只在 [[tick]] 接了只读对账）。 */
  def applyChainEvents(workspace: String, indexRoots: List[String], dryRun: Boolean = false): IO[ApplyReport] =
    for
      ev <- readEvents(workspace)
      (events, parseErrors) = ev
      chainEvents = events.filter(e => ChainEventTypes.contains(e.typ))
      desired = desiredStates(chainEvents)
      files <- scanIndexFiles(indexRoots)
      _ <-
        if files.isEmpty then
          logger.info(s"doc-index: no $IndexFileName under [${indexRoots.mkString(", ")}] — skip（索引未落地或路径不含索引）")
        else IO.unit
      pairs <- files.traverse(f => IO.blocking(os.read(f)).map(c => (f, c)))
      results <- pairs.traverse { case (f, c) => applyToIndex(f, c, desired, dryRun) }
      known = pairs.flatMap { case (_, c) => chainIdsIn(c) }.toSet
      skipped = desired.keys.filterNot(known.contains).toList.sorted
      _ <-
        if skipped.nonEmpty then
          logger.info(s"doc-index: chain(s) with events but no index entry — skipped: ${skipped.mkString(", ")}")
        else IO.unit
      _ <- if !dryRun && results.exists(_.changed) then
        IO(logger.infoSync(s"doc-index: ${results.count(_.changed)} index file(s) updated (flips=${results.map(_.stateFlips).sum}, blocksMoved=${results.map(_.blocksMoved).sum})"))
      else IO.unit
    yield ApplyReport(
      workspace = workspace,
      eventLines = events.size,
      chainEvents = chainEvents.size,
      desiredStates = desired.view.mapValues(_.archived).toMap,
      indexFiles = files.map(_.toString),
      results = results,
      skippedChains = skipped,
      parseErrors = parseErrors,
      dryRun = dryRun
    )

  private def applyToIndex(path: os.Path, content: String, desired: Map[String, DesiredState],
                           dryRun: Boolean): IO[IndexApplyResult] =
    if path.last != IndexFileName then
      // 防呆白名单：本消费者只允许改写 INDEX.md（文件本体零变化纪律的机械保证）
      logger.info(s"doc-index: refuse to rewrite non-index file $path").as(
        IndexApplyResult(path.toString, changed = false, 0, 0, List("not-an-index-file")))
    else
      val r = transformIndex(content, desired)
      val write = if r.changed && !dryRun then IO.blocking(os.write.over(path, r.content)) else IO.unit
      write.as(IndexApplyResult(path.toString, r.changed, r.stateFlips, r.blocksMoved, r.notes))

  /** 对账兜底（显式入口，总是落报表）。 */
  def reconcile(workspace: String, indexRoots: List[String]): IO[ReconcileReport] =
    scanIndexFiles(indexRoots).flatMap { files =>
      buildReport(workspace, files).flatTap(writeReport)
    }

  private def buildReport(workspace: String, files: List[os.Path]): IO[ReconcileReport] =
    for
      archiveChains <- archiveChainIds(workspace)
      contents <- files.traverse(f => IO.blocking(os.read(f)).map(c => (f.toString, c)))
      now <- IO(System.currentTimeMillis())
    yield
      val indexChains = contents.flatMap { case (_, c) => chainIdsIn(c) }.toSet
      val stale = contents.flatMap { case (f, c) => staleEntries(f, c, archiveChains) }
      ReconcileReport(
        workspace = workspace,
        generatedAt = now,
        archiveChains = archiveChains.toList.sorted,
        indexChains = indexChains.toList.sorted,
        missingInIndex = archiveChains.diff(indexChains).toList.sorted,
        staleInIndex = stale.map(e => s"chain=${e.chainId} doc=${e.doc.getOrElse("(no-doc-cell)")} index=${e.indexPath}").sorted,
        indexFiles = files.map(_.toString),
        reportPath = reportPath(workspace).toString
      )

  private def writeReport(r: ReconcileReport): IO[Unit] =
    IO.blocking(AtomicJson.writeSync(os.Path(r.reportPath), reportJson(r).noSpaces))
      .handleErrorWith(e => logger.warn(s"doc-index: reconcile report write failed (${r.reportPath}): ${e.getMessage}"))

  /** 报表 JSON（stable 字段序；对账只读口径写进 note）。 */
  def reportJson(r: ReconcileReport): Json =
    Json.obj(
      "workspace" -> r.workspace.asJson,
      "generatedAt" -> r.generatedAt.asJson,
      "archiveChains" -> r.archiveChains.asJson,
      "indexChains" -> r.indexChains.asJson,
      "missingInIndex" -> r.missingInIndex.asJson,
      "staleInIndex" -> r.staleInIndex.asJson,
      "indexFiles" -> r.indexFiles.asJson,
      "note" -> "只读对账（文档本体零改动）：missing-in-index = 归档区有事实、索引 chain 集无；stale-in-index = 索引条目 state=active 但链已在归档区".asJson
    )

  /** 默认索引根：`<dataRoot>/docs`（文档规范 §1 域目录的父目录），不存在 → 空集。 */
  def defaultIndexRoots: IO[List[String]] =
    IO.blocking {
      val root = PathUtil.dataRoot / "docs"
      if os.exists(root) && os.isDir(root) then List(root.toString) else Nil
    }

  private val lastTickMs = new java.util.concurrent.atomic.AtomicLong(0L)

  /** 定时对账兜底（挂 ProjectActor.TtlTick 30s 周期；JVM 内按 [[minIntervalMs]] 节流，
    * 多项目 tick 不叠加扫描）。`indexRoots = None` → [[defaultIndexRoots]]（`<dataRoot>/docs`，
    * 显式传 Some 供测试/自定义域根）。索引区无 INDEX.md → 零开销 no-op（不写盘不打
    * 日志）；报表干净（无 missing/stale）→ 不写报表文件。**只写 tmp 报表，不改文档本体。** */
  def tick(workspace: String, minIntervalMs: Long = DefaultReconcileIntervalMs,
           indexRoots: Option[List[String]] = None): IO[Option[ReconcileReport]] =
    IO.blocking {
      val now = System.currentTimeMillis()
      val prev = lastTickMs.get()
      val due = prev == 0L || now - prev >= minIntervalMs
      if due then lastTickMs.set(now)
      due
    }.flatMap {
      case false => IO.pure(None)
      case true =>
        val roots = indexRoots.fold(defaultIndexRoots)(rs => IO.pure(rs))
        roots.flatMap(scanIndexFiles).flatMap {
          case Nil => IO.pure(None)
          case files =>
            buildReport(workspace, files).flatMap { r =>
              if r.isClean then IO.pure(Some(r)) else writeReport(r).as(Some(r))
            }
        }
    }
