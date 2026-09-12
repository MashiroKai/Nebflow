package nebflow.core.tools

import cats.effect.IO
import io.circe.Codec
import io.circe.Json
import io.circe.syntax.*
import io.circe.JsonObject
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.parser.decode

import java.time.Instant

import scala.collection.mutable

import nebflow.core.{AtomicJson, NebflowLogger, PathUtil}

/**
 * TaskList 任务工具（2026-09-06 作者 00:07 提议 + 00:11 首期无前端拍板）。
 *
 * 动机（设计文档 ~/.nebflow/memory/tasklist-design.md）：「Nebula 把记忆当任务
 * 工具用」根因 = 记忆是唯一持久外部存储。三边界：记忆 = T1 慢变事实（永久）；
 * 任务 = 快变状态（闭环即退役）；Flow Map = 单次派发执行。本工具是 Nebula 专属
 * 编排件（工具面隔离：仅 NebulaOrchestrationTools 携带 + NebulaExclusiveTools
 * 防声明逃逸剥离，dispatcher/general 面零出现），首期纯工具面、零 web/ 改动。
 *
 * 存储：`<dataRoot>/tasks.json`（~/.nebflow/tasks.json）——运行时数据层：
 * 不进 git、不进任何 system prompt 注入。落盘用 AtomicJson（tmp + ATOMIC_MOVE
 * rename，crash-safe）；进程内单锁串行化读-改-写；跨进程写入不在锁面内
 * （MemoryEditTool 同款取舍，description 已明示）。
 *
 * 无内存态：每次操作整读整写同一文件——重启后 tasks.json 自然持久（验收项）。
 *
 * 条目 schema（任务书口径）：id / title / status / project? / note? / blocks。
 * `blocks` = 本条目【依赖】的条目 id 列表（任务书原文「blocks(依赖条目 id 列表)」
 * ——即旧 TeamTask 的 blockedBy 方向；申报差异点）。
 *
 * 状态机（语义抄旧 TeamTask 四态机精神，按本 schema 落地四态）：
 *   open → in_progress → done；open/in_progress → blocked（记录等待）；
 *   blocked → open（回退待办）/ blocked → in_progress（恢复，受依赖闸）。
 *   - done 是终态：done → 任何状态拒绝（含 done→open，任务书明示必须拒绝）。
 *   - 进入 done 唯一路径 = close action（update 携带 status="done" 结构化拒绝，
 *     指向 close——终态迁移单通道）。
 *   - 同态迁移（如 in_progress→in_progress）= no-op 放行（TeamTask 同款）。
 *   - in_progress → open 不允许（TeamTask in_progress→pending 同款拒绝）。
 *
 * blocks 依赖闸（任务书第 3 条「被依赖条目未闭环时，受制条目迁移到
 * in_progress/done 的行为按设计文档语义落地」；设计文档 = 语义抄 TeamTask 四态
 * 机 + 本 schema 落地——落地为硬闸，边界情况见交付申报）：
 *   - 迁移到 in_progress 与 close（→done）要求 blocks 列表内全部条目均已 done；
 *     未闭环 → TASKLIST_BLOCKED 拒绝，错误列出未闭环依赖并给两条出路：
 *     先闭环依赖，或显式置 status=blocked 记录等待。
 *   - 迁移到 blocked 不设闸（记录等待本身是逃生通道）。
 *   - 依赖图环（含自依赖）→ TASKLIST_CYCLE 拒绝（DFS，TeamTask #3 同款）。
 *   - 依赖不存在 id → TASKLIST_BLOCK_UNKNOWN 拒绝；依赖已 done 的条目合法
 *     （闸恒过，记录「依赖已完成项」合法）。
 *
 * 损坏容错（任务书验收：坏 JSON 不崩、按可恢复策略处理并申报）：
 *   - 读路径（list / show / openSummaryLine）：损坏 → 空视图 + WARN 日志，绝不崩、
 *     绝无写副作用。
 *   - 写路径（create/update/close/log）：损坏 → 隔离 quarantaine（改名
 *     tasks.json.corrupt-<millis>，旧字节全保留供人工恢复）+ 全新空库继续本次
 *     写入。策略申报：隔离 + 空库续写，不静默覆盖。
 *
 * 闭环即删（T2 精神）：done 条目保留 note 作审计，create 时顺带清理 closedAt
 * 超 30 天的 done 条目（惰性清理，无定时器；解析失败的保守保留）。
 *
 * 注入语义：不全量进 system prompt；openSummaryLine 只在生命周期节点（重启/
 * 压缩，MemoryHygieneSignal 先例）随 Nebula 记忆块注入一行 open 摘要；全 done
 * 后该行为空（提醒消失）。按需明细一律走本工具 list / show。
 *
 * 升级批（2026-09-11，spec `20260911_135217_tasklist-upgrade-spec` + 方案 D1–D6）：
 *   - `action=log`：作者补记（只读史，不改写 `note`——`note` 降级为「当前态摘要」）；
 *   - `action=show`：单条全文（note 全文 / links / 依赖当前态 + 反查 / 父链 + 直接子 /
 *     变更史时间线）；对「主库已无、史文件仍有」的 id 走**降级渲染**（`[gone]`）；
 *   - 变更史独立落盘 `TaskListHistory`（append-only JSONL，不随任务 prune 消失）；
 *   - `links: List[String]`（自由字符串，**不校验可达性**）+ `parentId`（父子=包含，
 *     与 `blocks`=依赖正交共存；parent 边独立环检测 + 深度 ≤ 5）；
 *   - 写入侧容量：`note`/`log text` ≤ 2,000 字符且 ≤ 8 KiB 字节（仅写路径校验，
 *     读路径零校验 ⇒ 存量零影响）；
 *   - id 水位（`TaskListData.nextId`）：id **永不回收**（对齐 TaskBoard 侧形态）——
 *     否则 prune/隔离后的 id 复用会把两代任务的史混成一条时间线。
 */
/** TaskList 条目（TaskModel.scala 同款顶层 case class + companion givens——嵌套
  * object 内 ConfiguredCodec.derived 在 Scala 3.5 下触发镜像前向引用错误）。
  * withDefaults：缺字段回默认（零迁移）；未知键容忍（strictDeserialization 关）。
  *
  * 升级批新增两字段（旧 9 键文件零迁移：缺字段回默认 `links=Nil` / `parentId=None`）：
  *   - `links` = 自由关联锚（文档路径 / commit 短 hash / 任意字符串），**不校验可达性**
  *     （路径会随提交移动、短 hash 可能 rebase 后消失——硬校验会拒掉合法写入）；
  *   - `parentId` = 父子（包含）关系，与 `blocks`（依赖）语义正交、可共存；父链深度 ≤ 5。
  * 注意：旧二进制回写本文件会丢新字段（withDefaults + 未知键容忍）——史文件不受影响。
  */
case class TaskListEntry(
  id: String,
  title: String,
  status: String = TaskListStore.Status.Open,
  project: Option[String] = None,
  note: Option[String] = None,
  blocks: List[String] = Nil,
  createdAt: Option[String] = None,
  updatedAt: Option[String] = None,
  closedAt: Option[String] = None,
  links: List[String] = Nil,
  parentId: Option[String] = None
)
object TaskListEntry:
  given Configuration = Configuration.default.withDefaults
  given Codec[TaskListEntry] = ConfiguredCodec.derived

/** tasks.json 顶层结构：version + tasks + `nextId`（id 水位）。
  *
  * `nextId` = **单调 id 水位**（升级批 id 复用修正，形态对齐 TaskBoard 侧
  * `TaskBoardStore.TaskBoardData.nextId`）：create 取 `max(存量数字 id max, 水位) + 1`
  * 并回写水位 ⇒ 被 prune 掉或损坏隔离掉的 id **永不回收**。缺键回默认 0 ⇒ 旧库零迁移
  * 读入（首次 create 即自愈：水位推进到已用最大值+1 并落盘）。
  */
case class TaskListData(version: Int = 1, tasks: List[TaskListEntry] = Nil, nextId: Int = 0)
object TaskListData:
  given Configuration = Configuration.default.withDefaults
  given Codec[TaskListData] = ConfiguredCodec.derived

object TaskListStore:

  private val logger = NebflowLogger.forName("nebflow.tasklist")

  // ---- 状态常量（wire 格式唯一来源）----
  object Status:
    val Open       = "open"
    val InProgress = "in_progress"
    val Done       = "done"
    val Blocked    = "blocked"
    val all: Set[String] = Set(Open, InProgress, Done, Blocked)

  /** done 条目保留期（create 时惰性清理，「闭环即删」T2 精神）。 */
  val DoneTtlDays: Long = 30L

  // ---- 写入侧容量上限（升级批 D3/R5；仅写路径校验，读路径零校验 ⇒ 存量零影响）----
  /** `note` / `log text` 单条上限：先到为准（2,000 汉字 ≈ 6.1 KB < 8 KiB）。 */
  val NoteMaxChars: Int = 2000
  val TextMaxBytes: Int = 8 * 1024

  // ---- `show` 窗口上限（升级批 D4/R5：全部为「展示预算」，非删除）----
  /** 主区（note/log 内容变更 —— 作者 17:27 口径的史主体）预算。 */
  val TimelineShowMaxEvents: Int = 50
  val TimelineShowMaxChars: Int  = 20000
  val TimelineShowMaxBytes: Int  = 32 * 1024
  /** 次要区（状态/结构类事件）预算：独立且更小——退居次要，不得淹没 note 主线。 */
  val StateShowMaxEvents: Int   = 50
  val StateShowMaxChars: Int    = 4000
  val StateShowMaxBytes: Int    = 6 * 1024
  /** 单次读取窗口（按 id 保留最近 ≤ N 条；计数在整文件范围统计 ⇒ 未显示数可对账）。 */
  val TimelineReadWindow: Int = 2000
  val ReverseDepsShowMax: Int    = 20
  val ChildrenShowMax: Int       = 20
  /** 父链深度上限（root = 1 层）。 */
  val ParentMaxDepth: Int = 5
  val LinksMax: Int       = 20
  val LinkMaxChars: Int   = 300
  /** `show` 最终硬截断（结果硬顶 50_000 之下的自保线）。 */
  val ShowHardCapChars: Int    = 48000
  val GlobalEventsShowMax: Int = 3

  type Entry = TaskListEntry
  val Entry = TaskListEntry

  type Store = TaskListData
  val Store = TaskListData

  /** `def` 非 `val`：PathUtil.dataRoot 可被测试换根（FileTaskStore 同款陷阱）。 */
  private def file: os.Path = PathUtil.dataRoot / "tasks.json"

  /** 进程内单锁：整段读-改-写串行化（单文件，无需 per-path 锁）。跨进程不在面内。 */
  private val fileLock = new Object

  // ------------------------------------------------------------------
  // 读 / 写原语
  // ------------------------------------------------------------------

  private def readSync(): Either[String, Store] =
    if !os.exists(file) then Right(Store())
    else
      // 读取本身抛异常（权限/目录顶替等）与解码失败同归损坏路径——不崩
      scala.util.Try(decode[Store](os.read(file))) match
        case scala.util.Success(Right(store)) => Right(store)
        case scala.util.Success(Left(err))    => Left(err.getMessage)
        case scala.util.Failure(e)            => Left(e.getMessage)

  /** 读路径视图：损坏 → 空视图 + WARN（零副作用）。 */
  private def readViewSync(): Store =
    readSync() match
      case Right(store) => store
      case Left(reason) =>
        logger.warnSync(s"[tasklist] tasks.json corrupted, read path degrades to empty view: $reason")
        Store()

  /** 写路径读：损坏 → 隔离（改名保旧字节）+ 全新空库 + 1 条 `quarantine` 史行。
    * 返回 (库, 隔离文件名?)。 */
  private def readForWriteSync(hist: HistoryLog): (Store, Option[String]) =
    readSync() match
      case Right(store) => (store, None)
      case Left(reason) =>
        val quarantine = os.Path(s"${file}.corrupt-${System.currentTimeMillis()}")
        val renamed =
          try
            os.move(file, quarantine)
            Some(quarantine.last)
          catch case _: Exception => None
        logger.warnSync(
          s"[tasklist] tasks.json corrupted ($reason) — quarantined to ${renamed.getOrElse("(rename failed, proceeding on empty store)")}, starting fresh store")
        hist.append(TaskListEvent(
          at = nowStr,
          actor = TaskListActor.System,
          kind = TaskListEventKind.Quarantine,
          detail = Some(s"${renamed.getOrElse("(rename failed)")} reason=$reason")))
        (Store(), renamed)

  private def writeSync(store: Store): Unit =
    AtomicJson.writeSync(file, store.asJson.noSpaces)

  // ------------------------------------------------------------------
  // 校验器
  // ------------------------------------------------------------------

  /** 状态迁移矩阵（TeamTask 四态机精神落地）。同态 = no-op 放行。 */
  private[tools] def isValidTransition(from: String, to: String): Boolean =
    (from, to) match
      case (f, t) if f == t                  => true // no-op（含 done→done 幂等）
      case (Status.Open, Status.InProgress)  => true
      case (Status.Open, Status.Blocked)     => true
      case (Status.InProgress, Status.Blocked) => true
      case (Status.Blocked, Status.Open)     => true
      case (Status.Blocked, Status.InProgress) => true
      case _                                 => false // done→任何（终态）、in_progress→open 等

  /** 依赖图环检测（id → blocks 边；含自依赖）。TeamTask #3 DFS 同款。 */
  private[tools] def hasCycle(tasks: List[Entry]): Boolean =
    val adj = tasks.map(t => t.id -> t.blocks.filter(_.nonEmpty)).toMap
    val visited = mutable.Set[String]()
    val recStack = mutable.Set[String]()

    def dfs(id: String): Boolean =
      visited += id
      recStack += id
      val found = adj.getOrElse(id, Nil).exists { n =>
        if !visited.contains(n) then dfs(n)
        else if recStack.contains(n) then true
        else false
      }
      recStack -= id
      found

    adj.keys.exists(id => !visited.contains(id) && dfs(id))
  end hasCycle

  /** blocks 列表规整：去空白、去重。 */
  private def normalizeBlocks(raw: List[String]): List[String] =
    raw.map(_.trim).filter(_.nonEmpty).distinct

  /** 依赖 id 存在性校验：未知 id → Left(错误)。 */
  private def checkBlockIds(store: Store, blocks: List[String]): Option[ToolError] =
    val known = store.tasks.map(_.id).toSet
    blocks.find(!known.contains(_)).map { unknown =>
      val knownList = if known.isEmpty then "(none — store is empty)" else known.map("#" + _).mkString(", ")
      ToolError(
        s"TaskList: dependency id '#$unknown' does not exist. Known ids: $knownList. " +
          s"Fix the id or drop it. (TASKLIST_BLOCK_UNKNOWN)")
    }

  /** 依赖闸（TeamTask 精神落地）：blocks 内全部条目须 done 才可 in_progress/done。 */
  private def checkDepsClosed(store: Store, blocks: List[String], action: String, id: String): Option[ToolError] =
    val open = blocks.flatMap(dep => store.tasks.find(_.id == dep)).filterNot(_.status == Status.Done)
    if open.isEmpty then None
    else
      val listing = open.map(e => s"#${e.id}[${e.status}] ${truncate(e.title, 40)}").mkString(", ")
      Some(ToolError(
        s"TaskList: cannot $action #$id — dependency(ies) not closed yet: $listing. " +
          s"Close them first, or set status=\"blocked\" on #$id to record the wait. (TASKLIST_BLOCKED)"))

  private def truncate(s: String, max: Int): String =
    if s.length <= max then s else s.take(max) + "…"

  private def nowStr: String = Instant.now().toString

  // ---- 升级批校验器：文本上限 / links / 父子图 ----

  private def utf8Len(s: String): Int = s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length

  /** 文本参数写入侧上限（D3）：≤ maxChars 字符且 ≤ maxBytes 字节（先到为准）。
    * 超限 → `TASKLIST_PARAM` + 修法（错误文案必须给出路）。读路径零校验。 */
  private def checkTextLimit(field: String, value: String, hint: String): Option[ToolError] =
    val bytes = utf8Len(value)
    if value.length <= NoteMaxChars && bytes <= TextMaxBytes then None
    else
      Some(ToolError(
        s"TaskList: `$field` is ${value.length} char(s) / $bytes byte(s) — over the per-entry limit " +
          s"($NoteMaxChars chars / $TextMaxBytes bytes). $hint (TASKLIST_PARAM)"))

  /** links 规整：去空白、去空串、去重（与 normalizeBlocks 同形）。 */
  private def normalizeLinks(raw: List[String]): List[String] =
    raw.map(_.trim).filter(_.nonEmpty).distinct

  /** links 限额：≤ 20 条、单条 ≤ 300 字符。**不做可达性校验**（见 case class 注释）。 */
  private def checkLinks(links: List[String]): Option[ToolError] =
    if links.size > LinksMax then
      Some(ToolError(
        s"TaskList: too many `links` (${links.size}) — max $LinksMax. Keep the most relevant ones, " +
          s"or put the rest in `note` / an action=log entry. (TASKLIST_PARAM)"))
    else
      links.find(_.length > LinkMaxChars).map { long =>
        ToolError(
          s"TaskList: a `links` entry is ${long.length} chars — max $LinkMaxChars per link. " +
            s"Shorten it (e.g. a repo-relative path or a short commit hash). (TASKLIST_PARAM)")
      }

  /** 自 id 向上取祖先链（不含自身，近→远）；悬空父一并收入（渲染为 `[gone]`）。
    * 防御性环保护：存量损坏数据存在回指时不死循环。 */
  private[tools] def ancestorsOf(tasks: List[Entry], id: String): List[String] =
    val byId = tasks.map(t => t.id -> t).toMap
    val out = mutable.ListBuffer[String]()
    val seen = mutable.Set[String](id)
    var cur = id
    var go = true
    while go do
      byId.get(cur).flatMap(_.parentId) match
        case Some(p) if !seen.contains(p) =>
          seen += p
          out += p
          cur = p
        case _ => go = false
    out.toList

  /** parent 链深度（root = 1 层）；含环保护与悬空父（计入一层后停）。 */
  private[tools] def parentDepthOf(tasks: List[Entry], id: String): Int = 1 + ancestorsOf(tasks, id).size

  /** parent 校验（create / update 共用）：存在性 + 自环/回指 + 深度 ≤ ParentMaxDepth。
    * `selfId` = update 时为被改条目 id（create 为 None）。 */
  private def checkParent(store: Store, selfId: Option[String], parentId: String): Option[ToolError] =
    val known = store.tasks.map(_.id)
    if selfId.contains(parentId) then
      Some(ToolError(
        s"TaskList: #$parentId cannot be its own parent. (TASKLIST_PARENT_CYCLE)"))
    else if !known.contains(parentId) then
      val knownList = if known.isEmpty then "(none — store is empty)" else known.map("#" + _).mkString(", ")
      Some(ToolError(
        s"TaskList: parent id '#$parentId' does not exist. Known ids: $knownList. " +
          s"Fix the id or drop it. (TASKLIST_PARENT_UNKNOWN)"))
    else if selfId.exists(sid => ancestorsOf(store.tasks, parentId).contains(sid)) then
      val self = selfId.getOrElse("?")
      Some(ToolError(
        s"TaskList: parentId=$parentId would create a parent cycle (#$self is an ancestor of #$parentId). (TASKLIST_PARENT_CYCLE)"))
    else
      val depth = parentDepthOf(store.tasks, parentId) + 1
      if depth > ParentMaxDepth then
        Some(ToolError(
          s"TaskList: parent chain would become $depth level(s) deep — max $ParentMaxDepth. " +
            s"Flatten the tree (attach to a shallower parent) or keep the remaining work as a dependency (blocks). (TASKLIST_PARENT_DEPTH)"))
      else None

  /** 后代（子/孙…，含环保护）；用于 `show` 的计数行与 `⚠children-open` 标记。 */
  private[tools] def descendantsOf(tasks: List[Entry], id: String): List[Entry] =
    val out = mutable.ListBuffer[Entry]()
    val seen = mutable.Set[String](id)
    val queue = mutable.Queue[String](id)
    while queue.nonEmpty do
      val cur = queue.dequeue()
      tasks.filter(_.parentId.contains(cur)).foreach { child =>
        if !seen.contains(child.id) then
          seen += child.id
          out += child
          queue.enqueue(child.id)
      }
    out.toList

  // ---- 变更史事件构造（D1：逐字段一行）----

  private def jsonOf(o: Option[String]): Json = o.map(Json.fromString).getOrElse(Json.Null)

  private def jsonList(l: List[String]): Json = Json.arr(l.map(Json.fromString)*)

  /** 逐字段 diff → `update` 史行（单次调用最多 7 条：title/status/note/project/blocks/links/parentId）。
    * **note 变更的 `from`/`to` 是两侧全文**（作者 17:27 口径：覆盖动作必须可还原）。 */
  private def updateEvents(old: Entry, next: Entry, actor: String, at: String): List[TaskListEvent] =
    val buf = mutable.ListBuffer[TaskListEvent]()
    def ev(field: String, from: Json, to: Json): Unit =
      if from != to then
        buf += TaskListEvent(at = at, actor = actor, kind = TaskListEventKind.Update,
          id = Some(next.id), field = Some(field), from = Some(from), to = Some(to))
    ev("title", Json.fromString(old.title), Json.fromString(next.title))
    ev("status", Json.fromString(old.status), Json.fromString(next.status))
    ev("note", jsonOf(old.note), jsonOf(next.note))
    ev("project", jsonOf(old.project), jsonOf(next.project))
    ev("blocks", jsonList(old.blocks), jsonList(next.blocks))
    ev("links", jsonList(old.links), jsonList(next.links))
    ev("parentId", jsonOf(old.parentId), jsonOf(next.parentId))
    buf.toList

  /** note 内容变更行（`create` 的初始 note / `close` 的 `[done] outcome` 追加走这里）：
    * `field="note"` + `from`/`to` 两侧全文 ⇒ 「每一次补充的内容」都可还原。 */
  private def noteEvent(id: String, old: Option[String], next: Option[String], actor: String, at: String): Option[TaskListEvent] =
    if old == next then None
    else Some(TaskListEvent(at = at, actor = actor, kind = TaskListEventKind.Update,
      id = Some(id), field = Some("note"), from = Some(jsonOf(old)), to = Some(jsonOf(next))))

  /** `TASKLIST_NO_ID` 文案（update/close/show/log 四处同源：说错在哪 + 给 open 清单出路）。 */
  private def noIdError(store: Store, id: String): ToolError =
    val open = store.tasks.filterNot(_.status == Status.Done)
    val hint = if open.isEmpty then "(no open entries)" else open.map(e => s"#${e.id}[${e.status}] ${truncate(e.title, 40)}").mkString(", ")
    ToolError(s"""TaskList: no entry #$id. Open entries: $hint — pick an id from action=list. (TASKLIST_NO_ID)""")

  /** 引用渲染（`#id[status] title`；未知 id 用既有形态 `[?]` / `[gone]`）。 */
  private def refOf(store: Store, otherId: String, missingMark: String): String =
    store.tasks.find(_.id == otherId) match
      case Some(t) => s"#$otherId[${t.status}] ${truncate(t.title, 40)}"
      case None    => s"#$otherId[$missingMark]"

  /** done 条目惰性清理（closedAt 超 30d；解析失败保守保留）。返回 (库, 被清条目)。
    * 每条被清条目追加 1 条 `prune` 史行（`actor=system`）——「任务条目消亡」本身有留痕，
    * 且史文件**不参与** prune（「任务没了、史还在」，事后仍可按 id 查回）。 */
  private def pruneDone(store: Store, hist: HistoryLog): (Store, List[Entry]) =
    val cutoff = Instant.now().minusSeconds(DoneTtlDays * 24 * 3600)
    val pruned = mutable.ListBuffer[Entry]()
    val kept = store.tasks.filter { t =>
      val expired = t.status == Status.Done &&
        t.closedAt.flatMap(s => scala.util.Try(Instant.parse(s)).toOption).exists(_.isBefore(cutoff))
      if expired then pruned += t
      !expired
    }
    pruned.foreach { t =>
      hist.append(TaskListEvent(
        at = nowStr,
        actor = TaskListActor.System,
        kind = TaskListEventKind.Prune,
        id = Some(t.id),
        detail = Some(s"closedAt=${t.closedAt.getOrElse("(unknown)")} ttl=${DoneTtlDays}d title=${truncate(t.title, 80)}")))
    }
    (store.copy(tasks = kept), pruned.toList)

  /**
   * id 分配（升级批 id 复用修正，形态对齐 TaskBoard 侧 `nextNumId`）：
   * `max(存量数字 id max, 水位 nextId) + 1`。create 取用后**回写水位** ⇒ 被 prune /
   * 损坏隔离掉的 id 永不回收（否则新任务会拿到旧任务的 id，`show <id>` 把两代任务的
   * 史混成一条时间线、`[gone]` 标记失效——与「按任务还原 note 变更」正确性前提冲突）。
   * 旧库缺 `nextId` 键 ⇒ 默认 0 ⇒ 首次 create 用 `max+1` 并落盘水位（零迁移自愈）。
   */
  private[tools] def nextNumId(store: Store): Int =
    math.max(store.tasks.flatMap(_.id.toIntOption).maxOption.getOrElse(0), store.nextId) + 1

  // ------------------------------------------------------------------
  // 六 action 同步实现（工具在 IO.blocking 内调用）
  // ------------------------------------------------------------------

  def createSync(
    title: String,
    project: Option[String],
    note: Option[String],
    blocksRaw: Option[List[String]],
    linksRaw: Option[List[String]] = None,
    parentId: Option[String] = None,
    actor: String = TaskListActor.Unknown
  ): Either[ToolError, String] = fileLock.synchronized {
    val hist = new HistoryLog
    if title.trim.isEmpty then
      Left(ToolError("TaskList: create requires a non-empty `title`. (TASKLIST_PARAM)"))
    else
      val (base, quarantined) = readForWriteSync(hist)
      val (store, pruned) = pruneDone(base, hist)
      val blocks = blocksRaw.map(normalizeBlocks).getOrElse(Nil)
      val links = linksRaw.map(normalizeLinks).getOrElse(Nil)
      val parent = parentId.map(_.trim).filter(_.nonEmpty)
      for
        _ <- checkBlockIds(store, blocks).toLeft(())
        _ <- checkLinks(links).toLeft(())
        _ <- note.filter(_.trim.nonEmpty).flatMap(n => checkTextLimit("note", n.trim,
          "Keep `note` as the current-state summary and append long content with action=log.")).toLeft(())
        _ <- parent.flatMap(p => checkParent(store, None, p)).toLeft(())
        nextId = nextNumId(store)
        now = nowStr
        entry = Entry(
          id = nextId.toString,
          title = title.trim,
          status = Status.Open,
          project = project.map(_.trim).filter(_.nonEmpty),
          note = note.map(_.trim).filter(_.nonEmpty),
          blocks = blocks,
          createdAt = Some(now),
          updatedAt = Some(now),
          links = links,
          parentId = parent
        )
        _ <- {
          // 水位随取用回写（id 不回收）
          writeSync(store.copy(tasks = store.tasks :+ entry, nextId = nextId))
          Right(())
        }
      yield
        hist.append(TaskListEvent(at = now, actor = actor, kind = TaskListEventKind.Create,
          id = Some(entry.id), detail = Some(entry.title)))
        // 初始 note 也是一版「内容」（内容线起点），单独落一条 note 内容行
        noteEvent(entry.id, None, entry.note, actor, now).foreach(hist.append)
        val depsNote = if blocks.nonEmpty then s" (deps: ${blocks.map("#" + _).mkString(", ")})" else ""
        val linksNote = if links.nonEmpty then s" (links: ${links.size})" else ""
        val parentNote = parent.map(p => s" (parent: #$p)").getOrElse("")
        val pruneNote = (pruned.size, quarantined) match
          case (0, None)       => ""
          case (n, None)       => s" Pruned $n done entr${if n == 1 then "y" else "ies"} older than ${DoneTtlDays}d."
          case (0, Some(q))    => s" NOTE: previous tasks.json was corrupted — quarantined as $q; fresh store started."
          case (n, Some(q))    => s" NOTE: previous tasks.json was corrupted — quarantined as $q. Pruned $n stale done entries."
        s"""[OK] TaskList created #$nextId [open] ${truncate(entry.title, 60)}$depsNote$linksNote$parentNote$pruneNote${hist.note}
           |Start it with action=update status=in_progress (blocked until all deps are done); close it with action=close.""".stripMargin
  }

  def updateSync(
    id: String,
    title: Option[String],
    note: Option[String],
    project: Option[String],
    blocksRaw: Option[List[String]],
    status: Option[String],
    linksRaw: Option[List[String]] = None,
    parentId: Option[String] = None,
    actor: String = TaskListActor.Unknown
  ): Either[ToolError, String] = fileLock.synchronized {
    val hist = new HistoryLog
    val (store, quarantined) = readForWriteSync(hist)
    store.tasks.find(_.id == id) match
      case None =>
        Left(noIdError(store, id))
      case Some(existing) =>
        // 终态单通道：update 不得进 done（close 专属），错误给可行动出路
        if status.contains(Status.Done) && existing.status != Status.Done then
          Left(ToolError(
            s"TaskList: status=\"done\" is not settable via update — use action=close (the single path into the terminal state). (TASKLIST_DONE_VIA_CLOSE)"))
        else
          val target = status.getOrElse(existing.status)
          if !Status.all.contains(target) then
            Left(ToolError(
              s"TaskList: unknown status '$target' — one of open | in_progress | blocked (done is reached via action=close). (TASKLIST_STATUS)"))
          else if !isValidTransition(existing.status, target) then
            Left(ToolError(
              s"TaskList: illegal transition ${existing.status} → $target for #$id. " +
                "Legal: open→in_progress, open→blocked, in_progress→blocked, blocked→open, blocked→in_progress; done is terminal (no transitions out). (TASKLIST_STATUS)"))
          else
            val newBlocks = blocksRaw.map(normalizeBlocks).getOrElse(existing.blocks)
            val newLinks = linksRaw.map(normalizeLinks).getOrElse(existing.links)
            // parentId：空串 = 清除；缺省 = 保留
            val newParent = parentId match
              case Some(p) => Some(p.trim).filter(_.nonEmpty)
              case None    => existing.parentId
            val parentChanged = newParent != existing.parentId
            for
              _ <- checkBlockIds(store, newBlocks).toLeft(())
              _ <- checkLinks(newLinks).toLeft(())
              _ <- note.filter(_.trim.nonEmpty).flatMap(n => checkTextLimit("note", n.trim,
                "Keep `note` as the current-state summary and append long content with action=log.")).toLeft(())
              _ <- (if parentChanged then newParent.flatMap(p => checkParent(store, Some(id), p)) else None).toLeft(())
              // 环检测：以「改动后」的边集跑 DFS（含自依赖）
              _ <-
                val candidate = Entry(id = existing.id, title = existing.title, status = target,
                  blocks = newBlocks) // 只取边集，其余字段无关环检测
                val edgeSet = store.tasks.map(t => if t.id == existing.id then candidate else t)
                if hasCycle(edgeSet) then
                  Left(ToolError(
                    s"TaskList: blocks update for #$id would create a dependency cycle (self-reference or loop). (TASKLIST_CYCLE)"))
                else Right(())
              // 依赖闸：目标 in_progress 时 blocks 全部须闭环
              _ <-
                if target == Status.InProgress then
                  checkDepsClosed(store, newBlocks, "start", id).toLeft(())
                else Right(())
              now = nowStr
              updated = existing.copy(
                title = title.map(_.trim).filter(_.nonEmpty).getOrElse(existing.title),
                note = note.map(_.trim).filter(_.nonEmpty).orElse(existing.note),
                project = project match
                  case Some(p) => Some(p.trim).filter(_.nonEmpty) // 空串 = 清除
                  case None    => existing.project,
                blocks = newBlocks,
                links = newLinks,
                parentId = newParent,
                status = target,
                updatedAt = Some(now)
              )
              _ <- {
                writeSync(store.copy(tasks = store.tasks.map(t => if t.id == id then updated else t)))
                Right(())
              }
            yield
              updateEvents(existing, updated, actor, now).foreach(hist.append)
              val statusNote = if target != existing.status then s" ${existing.status}→$target" else " (no status change)"
              val fieldNote =
                val changed = List(
                  Option.when(updated.title != existing.title)("title"),
                  Option.when(updated.note != existing.note)("note"),
                  Option.when(updated.project != existing.project)("project"),
                  Option.when(updated.blocks != existing.blocks)("blocks"),
                  Option.when(updated.links != existing.links)("links"),
                  Option.when(updated.parentId != existing.parentId)("parentId")).flatten
                if changed.isEmpty then "" else s" (changed: ${changed.mkString(", ")})"
              val qNote = quarantined.map(q => s" NOTE: previous tasks.json was corrupted — quarantined as $q.").getOrElse("")
              s"[OK] TaskList updated #$id$statusNote$fieldNote$qNote${hist.note}"
  }

  def closeSync(id: String, note: Option[String], actor: String = TaskListActor.Unknown): Either[ToolError, String] =
    fileLock.synchronized {
      val hist = new HistoryLog
      val (store, quarantined) = readForWriteSync(hist)
      store.tasks.find(_.id == id) match
        case None =>
          Left(noIdError(store, id))
        case Some(existing) if existing.status == Status.Done =>
          // 幂等：重复 close = no-op 成功（TeamTask complete 双发竞态同款）
          Right(s"[OK] TaskList #$id already closed at ${existing.closedAt.getOrElse("(unknown time)")} — no-op.")
        case Some(existing) =>
          for
            _ <- checkDepsClosed(store, existing.blocks, "close", id).toLeft(())
            _ <- note.filter(_.trim.nonEmpty).flatMap(n => checkTextLimit("note", n.trim,
              "Append long content with action=log instead of a giant close note.")).toLeft(())
            now = nowStr
            // close 的 note = 结果备注，追加保留工作 note（update 语义是替换，close 语义是追加）
            newNote = note.map(_.trim).filter(_.nonEmpty) match
              case Some(outcome) =>
                Some(existing.note.filter(_.nonEmpty).map(n => s"$n\n[done] $outcome").getOrElse(s"[done] $outcome"))
              case None => existing.note
            updated = existing.copy(
              status = Status.Done,
              note = newNote,
              closedAt = Some(now),
              updatedAt = Some(now)
            )
            _ <- {
              writeSync(store.copy(tasks = store.tasks.map(t => if t.id == id then updated else t)))
              Right(())
            }
          yield
            hist.append(TaskListEvent(at = now, actor = actor, kind = TaskListEventKind.Close,
              id = Some(id),
              from = Some(Json.fromString(existing.status)),
              to = Some(Json.fromString(Status.Done)),
              detail = note.map(_.trim).filter(_.nonEmpty)))
            // close 的 `[done] outcome` 追加 = 一次 note 内容变更（可还原新旧两侧）
            noteEvent(id, existing.note, newNote, actor, now).foreach(hist.append)
            val qNote = quarantined.map(q => s" NOTE: previous tasks.json was corrupted — quarantined as $q.").getOrElse("")
            // 9.5A：无级联——存在 open 子不阻断 close，但结果行给提示（父状态不派生）
            val openKids = store.tasks.filter(t => t.parentId.contains(id) && t.status != Status.Done)
            val kidsNote =
              if openKids.isEmpty then ""
              else
                val shown = openKids.take(ChildrenShowMax).map(k => s"#${k.id}[${k.status}] ${truncate(k.title, 40)}").mkString(", ")
                val more = if openKids.size > ChildrenShowMax then s" (+${openKids.size - ChildrenShowMax} more)" else ""
                s" NOTE: ${openKids.size} open child(ren) remain — not cascaded (close children explicitly): $shown$more."
            s"[OK] TaskList closed #$id ${existing.status}→done$qNote$kidsNote${hist.note}"
    }

  /**
   * `action=log`（R1）：给任务追加一条作者补记（结构化、工具写 `at`/`actor`）。
   * **`note` 零改写**（note 已降级为「当前态摘要」）；条目只读史不可编辑——需修正
   * 时再追加一条（`kind=log` 语义）。
   */
  def logSync(
    id: String,
    text: String,
    linksRaw: Option[List[String]] = None,
    actor: String = TaskListActor.Unknown
  ): Either[ToolError, String] = fileLock.synchronized {
    val hist = new HistoryLog
    // 只读视图（log 不改任务体）：损坏 → 空视图 + WARN，不触发隔离写
    val store = readViewSync()
    store.tasks.find(_.id == id) match
      case None => Left(noIdError(store, id))
      case Some(existing) =>
        val body = text.trim
        val links = linksRaw.map(normalizeLinks).getOrElse(Nil)
        for
          _ <-
            if body.isEmpty then Left(ToolError("TaskList: log requires a non-empty `text`. (TASKLIST_PARAM)"))
            else Right(())
          _ <- checkTextLimit("text", body, "Split it into several action=log calls.").toLeft(())
          _ <- checkLinks(links).toLeft(())
        yield
          hist.append(TaskListEvent(at = nowStr, actor = actor, kind = TaskListEventKind.Log,
            id = Some(id), text = Some(body), links = links))
          val linksNote = if links.nonEmpty then s" links: ${links.size}." else ""
          s"[OK] TaskList logged ${body.length} char(s) to #$id (note untouched).$linksNote${hist.note}"
  }

  /**
   * `action=show`（R3/R8/R9）：单条全文视图 —— note 全文 + links + 依赖当前态与
   * 反查 + 父链/直接子/后代计数 + 变更史时间线（note 主线在前、状态类退居次要）。
   *
   * 两条出口：
   *   - 主库有该条目 → 全量渲染；
   *   - 主库已无但史文件有该 id 的事件（prune 后）→ **降级渲染**：标 `#<id>[gone]`，
   *     主库字段如实显示「已清理，不可得」（禁编造/禁回填），仍给史时间线。
   * 两条出口共享同一预算（note 主线 ≤50 条 / ≤20,000 字符 / ≤32 KiB；状态类 ≤50 条 /
   * ≤4,000 字符 / ≤6 KiB，未显示数明示）与 ≤48,000 字符最终硬截断。id 在主库与史
   * 文件**均无**命中 → `TASKLIST_NO_ID` + open 清单。
   */
  def showSync(id: String): Either[ToolError, String] =
    val store = readViewSync()
    val hist = TaskListHistory.readFor(id, TimelineReadWindow, GlobalEventsShowMax)
    store.tasks.find(_.id == id) match
      case Some(e) => Right(renderShow(store, e, hist))
      case None =>
        if hist.matched > 0 then Right(renderGone(id, hist))
        else Left(noIdError(store, id))

  /** 状态/结构类事件行（次要区：极简一行）。 */
  private def renderEvent(ev: TaskListEvent): String =
    val body = ev.kind match
      case TaskListEventKind.Update =>
        val f = ev.field.getOrElse("?")
        s"$f: ${brief(ev.from)} → ${brief(ev.to)}"
      case TaskListEventKind.Log =>
        val links = if ev.links.nonEmpty then s"  [links: ${ev.links.mkString(", ")}]" else ""
        ev.text.getOrElse("") + links
      case TaskListEventKind.Close =>
        s"status ${brief(ev.from)} → ${brief(ev.to)}" + ev.detail.map(d => s" — $d").getOrElse("")
      case _ => ev.detail.getOrElse("")
    val kind = if body.isEmpty then ev.kind else s"${ev.kind}: $body"
    s"- ${ev.at} ${ev.actor} $kind"

  private def brief(j: Option[Json]): String =
    val s = j match
      case None                => "(none)"
      case Some(v) if v.isNull => "(none)"
      case Some(v)             => v.asString.getOrElse(v.noSpaces)
    truncate(s.replace("\n", " ⏎ "), 60)

  /** 内容行单段渲染（多行内容逐行缩进；本段被截断时**明示**截断与原文长度）。 */
  private def contentBlock(label: String, content: String, budget: Int): String =
    val body =
      if content.isEmpty then "(empty)"
      else if content.length <= budget then content
      else content.take(math.max(0, budget)) + s"… (truncated: ${content.length} chars total)"
    body.split("\n", -1).toList match
      case head :: tail => (s"    $label| $head" :: tail.map(l => s"    $label| $l")).mkString("\n")
      case Nil          => s"    $label| (empty)"

  /** 一条 note/log 内容变更的可见块（内容可见 = 可还原；块内截断必须明示）。 */
  private def noteBlock(ev: TaskListEvent, budget: Int): String =
    val head = s"- ${ev.at} ${ev.actor} "
    val inner = math.max(64, budget - head.length - 40)
    val body = ev.kind match
      case TaskListEventKind.Log =>
        s"log${if ev.links.nonEmpty then s" [links: ${ev.links.mkString(", ")}]" else ""}\n" +
          contentBlock("", ev.text.getOrElse(""), inner)
      case TaskListEventKind.Update =>
        val old = ev.from.flatMap(v => if v.isNull then None else v.asString)
        val nw  = ev.to.flatMap(v => if v.isNull then None else v.asString)
        s"note (${old.map(_.length).getOrElse(0)} → ${nw.map(_.length).getOrElse(0)} chars)\n" +
          contentBlock("old", old.getOrElse("(none)"), inner / 2) + "\n" +
          contentBlock("new", nw.getOrElse("(none)"), inner / 2)
      case _ =>
        s"${ev.kind}\n" + contentBlock("", ev.detail.getOrElse(""), inner)
    head + body

  /** 主区：note/log 内容变更（作者 17:27 口径的史主体）——时间序、内容可见。 */
  private def renderNoteTimeline(sb: StringBuilder, hist: TaskListHistory.ReadResult): Unit =
    val noteEvents = hist.events.filter(TaskListHistory.isNoteEvent)
    val kept = mutable.ListBuffer[String]()
    var chars = 0
    var bytes = 0
    var stop = false
    noteEvents.reverseIterator.foreach { ev =>
      if !stop && kept.size < TimelineShowMaxEvents then
        val blk = noteBlock(ev, TimelineShowMaxChars - chars)
        val b = utf8Len(blk)
        if chars + blk.length <= TimelineShowMaxChars && bytes + b <= TimelineShowMaxBytes then
          kept.prepend(blk)
          chars += blk.length
          bytes += b
        else stop = true
    }
    // 预算被单条超大内容吃光：仍须给出最新一条（显式截断），不得静默空白
    if kept.isEmpty && noteEvents.nonEmpty then kept += noteBlock(noteEvents.last, TimelineShowMaxChars - 120)
    sb.append(s"## Note timeline (${hist.matchedNote} note/log change(s) recorded)\n")
    if kept.isEmpty then sb.append("(none)\n")
    else
      kept.foreach(b => sb.append(b + "\n"))
      val notShown = hist.matchedNote - kept.size
      if notShown > 0 then sb.append(s"(+$notShown older change(s) not shown)\n")

  /** 次要区：状态/结构类事件（极简行、独立小节 —— 不与 note 主线平铺混杂）。 */
  private def renderStateTimeline(sb: StringBuilder, hist: TaskListHistory.ReadResult): Unit =
    val stateEvents = hist.events.filterNot(TaskListHistory.isNoteEvent)
    val kept = mutable.ListBuffer[String]()
    var chars = 0
    var bytes = 0
    var stop = false
    stateEvents.reverseIterator.foreach { ev =>
      if !stop && kept.size < StateShowMaxEvents then
        val line = renderEvent(ev)
        val b = utf8Len(line)
        if chars + line.length <= StateShowMaxChars && bytes + b <= StateShowMaxBytes then
          kept.prepend(line)
          chars += line.length
          bytes += b
        else stop = true
    }
    sb.append(s"## Other events (state/structural, ${hist.matchedState} recorded)\n")
    if kept.isEmpty then sb.append("(none)\n")
    else
      kept.foreach(l => sb.append(l + "\n"))
      val notShown = hist.matchedState - kept.size
      if notShown > 0 then sb.append(s"(+$notShown older event(s) not shown)\n")

  /** 主库有该条目：全量渲染。 */
  private def renderShow(store: Store, e: Entry, hist: TaskListHistory.ReadResult): String =
    val sb = new StringBuilder
    sb.append(s"TaskList #${e.id} [${e.status}] ${e.title}\n")
    sb.append(s"project: ${e.project.getOrElse("-")}\n")
    sb.append(s"created: ${e.createdAt.getOrElse("-")}  updated: ${e.updatedAt.getOrElse("-")}  closed: ${e.closedAt.getOrElse("-")}\n")
    e.note.filter(_.nonEmpty) match
      case Some(n) => sb.append(s"note (${n.length} chars):\n$n\n")
      case None    => sb.append("note: (none)\n")
    if e.links.nonEmpty then sb.append(s"links (${e.links.size}): ${e.links.mkString(", ")}\n")
    // 父链（根在前）+ 直接子 + 后代计数（D4：不展开整棵子树）
    val chain = ancestorsOf(store.tasks, e.id)
    if chain.nonEmpty then
      sb.append(s"parent chain (root first): ${chain.reverse.map(p => refOf(store, p, "gone")).mkString(" → ")}\n")
    val children = store.tasks.filter(_.parentId.contains(e.id)).sortBy(_.id.toIntOption.getOrElse(Int.MaxValue))
    if children.nonEmpty then
      val openKids = children.count(_.status != Status.Done)
      val shown = children.take(ChildrenShowMax).map(c => refOf(store, c.id, "gone"))
      val more = if children.size > ChildrenShowMax then s" (+${children.size - ChildrenShowMax} more)" else ""
      sb.append(s"children (${children.size}, $openKids open): ${shown.mkString(" | ")}$more\n")
      val desc = descendantsOf(store.tasks, e.id)
      sb.append(s"subtree: ${desc.size} descendant(s) / ${desc.count(_.status != Status.Done)} open\n")
    // 依赖当前态 + 反查（谁依赖我 —— 单向 blocks 的另一半）
    if e.blocks.nonEmpty then
      val openDeps = e.blocks.flatMap(d => store.tasks.find(_.id == d)).exists(_.status != Status.Done)
      val warn = if openDeps && e.status != Status.Done then " ⚠deps-open" else ""
      sb.append(s"deps (${e.blocks.size}): ${e.blocks.map(d => refOf(store, d, "?")).mkString(", ")}$warn\n")
    val dependents = store.tasks.filter(t => t.id != e.id && t.blocks.contains(e.id))
    val depMore = if dependents.size > ReverseDepsShowMax then s" (+${dependents.size - ReverseDepsShowMax} more)" else ""
    val depShown = if dependents.isEmpty then "none" else dependents.take(ReverseDepsShowMax).map(d => refOf(store, d.id, "gone")).mkString(", ")
    val depWarn = if dependents.exists(_.status != Status.Done) then " ⚠dependents-open" else ""
    sb.append(s"dependents (${dependents.size}): $depShown$depMore$depWarn\n")
    renderNoteTimeline(sb, hist)
    renderStateTimeline(sb, hist)
    renderGlobals(sb, hist)
    if hist.unreadable > 0 then sb.append(s"history: ${hist.unreadable} unreadable line(s) skipped\n")
    hardCap(sb.toString)

  /** 主库已无该 id、史文件仍有事件：降级渲染（[gone] + 主库字段如实不可得，禁回填）。 */
  private def renderGone(id: String, hist: TaskListHistory.ReadResult): String =
    val sb = new StringBuilder
    sb.append(s"TaskList #$id [gone] (pruned — no longer in tasks.json)\n")
    sb.append("main store fields: (cleared, unavailable — entry was pruned; history below is all that remains)\n")
    sb.append("note: (cleared, unavailable)\n")
    renderNoteTimeline(sb, hist)
    renderStateTimeline(sb, hist)
    renderGlobals(sb, hist)
    if hist.unreadable > 0 then sb.append(s"history: ${hist.unreadable} unreadable line(s) skipped\n")
    hardCap(sb.toString)

  private def renderGlobals(sb: StringBuilder, hist: TaskListHistory.ReadResult): Unit =
    if hist.globalRecent.nonEmpty then
      sb.append(s"## System events (global, last ${hist.globalRecent.size})\n")
      hist.globalRecent.foreach(g => sb.append(renderEvent(g) + "\n"))

  private def hardCap(s: String): String =
    if s.length <= ShowHardCapChars then s
    else s.take(ShowHardCapChars) + s"\n… (truncated at $ShowHardCapChars chars)"

  /** list 渲染（可选 project 过滤，精确匹配）。 */
  def listSync(project: Option[String]): Either[ToolError, String] =
    val store = readViewSync()
    val filtered = project.map(_.trim).filter(_.nonEmpty) match
      case Some(p) => store.tasks.filter(_.project.contains(p))
      case None    => store.tasks
    val open = filtered.filterNot(_.status == Status.Done)
    if filtered.isEmpty then
      val scopeNote = project.map(p => s" for project '$p'").getOrElse("")
      Right(s"TaskList — empty$scopeNote (no entries). Create one with action=create.")
    else
      val lines = filtered.map { t =>
        val proj = t.project.map(p => s" ($p)").getOrElse("")
        val deps =
          if t.blocks.isEmpty then ""
          else
            val parts = t.blocks.map { depId =>
              store.tasks.find(_.id == depId) match
                case Some(d) => s"#$depId[${d.status}]"
                case None    => s"#$depId[?]"
            }
            val openDeps = t.blocks.flatMap(dep => store.tasks.find(_.id == dep)).exists(_.status != Status.Done)
            val warn = if openDeps && t.status != Status.Done then " ⚠deps-open" else ""
            s" deps: ${parts.mkString(", ")}$warn"
        val note = t.note.filter(_.nonEmpty).map(n => s" | note: ${truncate(n.replace("\n", " ⏎ "), 60)}").getOrElse("")
        // 9.4B：父状态不派生，但有 open 子时打提示标记（收口需显式 close）
        val kidsOpen = store.tasks.exists(k => k.parentId.contains(t.id) && k.status != Status.Done)
        val kidWarn = if kidsOpen && t.status != Status.Done then " ⚠children-open" else ""
        s"#${t.id} [${t.status}] ${truncate(t.title, 60)}$proj$deps$kidWarn$note"
      }
      Right(
        s"""TaskList — ${filtered.size} entr${if filtered.size == 1 then "y" else "ies"} (${open.size} open)${project.map(p => s" for project '$p'").getOrElse("")}
           |${lines.mkString("\n")}""".stripMargin)

  /**
   * 生命周期注入摘要行（ContextRefresher.buildMemoryBlock 消费）：open 条目存在
   * 时返回一行摘要；全 done / 空 / 文件损坏 → 空串（提醒消失）。
   * 摘要行 = 一行、首 5 条、标题截 40、总长封顶 600。
   */
  def openSummaryLine(): String =
    val store = readViewSync()
    val open = store.tasks.filterNot(_.status == Status.Done)
    if open.isEmpty then ""
    else
      val shown = open.take(5).map { t =>
        val proj = t.project.map(p => s"@$p").getOrElse("")
        s"#${t.id}[${t.status}] ${truncate(t.title, 40)}$proj"
      }
      val more = if open.size > 5 then s" (+${open.size - 5} more)" else ""
      val line = s"[TaskList] ${open.size} open task(s): ${shown.mkString(" | ")}$more — details: TaskList(action=list|show)"
      truncate(line, 600)
  end openSummaryLine
end TaskListStore

/**
 * TaskList 工具（Nebula 专属编排件，2026-09-06 作者提议 + 首期无前端拍板）。
 *
 * 工具面隔离（硬约束）：仅 AgentCore.NebulaOrchestrationTools 携带（Nebula 固定面
 * 成员，TaskList 批 +1；件数以 AgentCore.NebulaOrchestrationToolsExpectedSize 为单点，
 * R2 2026-09-12 后 16 件 ⇒ 此处不再写死数字）；
 * NebulaExclusiveTools 防声明逃逸——dispatcher（DispatcherFixedTools）
 * / general（BaseTools+Pop）与任何非 Nebula 身份声明（含 "*"）均剥离。
 */
object TaskListTool extends Tool:

  val name = "TaskList"

  // `def` + s-interpolation（home 硬编码 → 运行时动态化批 2026-09-11）：存储路径
  // 全部走 PathUtil.dataRootRenderValue —— 默认 home ⇒ `~/.nebflow/...`（与旧字面
  // 逐字节一致），隔离实例 ⇒ 该实例 home 绝对路径。`def` on purpose：dataRoot 可在
  // 对象初始化后被换根（--home / 测试 setDataRoot）。
  def description =
    s"""Persistent task tracker — your personal orchestration backlog (Nebula-exclusive). Fast-changing work state lives here, NOT in memory (memory = slow-changing facts; closed tasks retire). Storage: ${PathUtil.dataRootRenderValue}/tasks.json (runtime data — never in git, never injected wholesale into prompts; a ONE-LINE open summary rides your memory block at lifecycle events (restart/compaction) only).
## Actions
- create: new entry (status=open). Required: `title`. Optional: `project`, `note`, `blocks` (ids of entries THIS one depends on), `links` (free-form references), `parentId` (this entry is a sub-task of that entry).
- update: edit entry fields. Required: `id`. Optional: `status` ("open" | "in_progress" | "blocked" — NEVER "done", use close), `title`, `note` (replaces the current-state summary), `project` (empty string clears), `blocks` (FULL list replacement; [] clears), `links` ([] clears), `parentId` (empty string clears).
- list: render all entries with status + dependencies. Optional: `project` (exact filter). On-demand detail queries — do not wait for the lifecycle reminder.
- close: finish an entry (status→done, terminal). Required: `id`. Optional: `note` appended as a "[done] outcome" line (working note is preserved). Closing a parent does NOT cascade — open children are reported back, not modified.
- log: append a note/remark to an entry's change history. Required: `id`, `text` (≤2,000 chars). Optional: `links`. The entry `note` is NEVER touched by log (history is append-only, read-only).
- show: full detail for one entry — every field, the FULL note, links, dependency state + reverse lookup (who depends on me), parent chain / direct children / descendant count, and the change-history timeline (ascending). Required: `id`. Use it instead of reading tasks.json by hand.
## Sub-tasks (parentId) vs dependencies (blocks)
`parentId` = containment (this is a sub-task of …; chains ≤5 levels, cycles rejected: TASKLIST_PARENT_CYCLE / TASKLIST_PARENT_UNKNOWN / TASKLIST_PARENT_DEPTH). `blocks` = ordering (this DEPENDS ON …). They are orthogonal and may coexist (A parent of B while B blocks A is legal). Parent status is NEVER derived: all children done does NOT auto-close the parent, and closing a parent never rewrites children (close it explicitly). `links` are free-form strings (paths/commit hashes) — their reachability is NOT validated and never will be.
## Status machine (TeamTask four-state heritage)
open → in_progress → done (via close only); open/in_progress → blocked (record a wait); blocked → open (back to backlog) or blocked → in_progress (resume). Same-state update = no-op. done is TERMINAL: no transitions out (done→open is rejected). in_progress→open is rejected (reopen via blocked→open if truly needed — or create anew).
## Dependency guard (blocks semantics)
`blocks` = ids this entry DEPENDS ON. Starting (→in_progress) or closing (→done) is REJECTED while any dependency is not done (TASKLIST_BLOCKED lists the open deps): close them first, or set this entry to blocked to record the wait. Dependency cycles (incl. self-reference) are rejected (TASKLIST_CYCLE); unknown dep ids rejected (TASKLIST_BLOCK_UNKNOWN); depending on an already-done entry is legal.
## Change history (separate file, append-only) — the Note Timeline
The history's PRIMARY subject is the entry's `note` content: every change is appended with BOTH sides of the content (the pre-overwrite version stays readable, so you can reconstruct "in the Nth revision I changed the approach from A to B"). Written automatically by the tool for: `log` (the appended text), `update` with a `note` (old AND new content, full), `create` with a `note` (initial content) and `close` with an outcome (the `[done] …` line). Engine-derived `actor`; you never write timestamps. Storage: its OWN append-only file ${PathUtil.dataRootRenderValue}/tasks-history.jsonl (rotated at ~5 MiB / 20,000 lines into tasks-history.1.jsonl; disk capped at ~10 MiB, oldest generation dropped) and INDEPENDENT of the entry: pruning an entry does not delete its history — `show <id>` still returns that id's note timeline afterwards, marked `[gone]` (main-store fields then read "cleared, unavailable"; nothing is invented). Manual cleanup if ever needed: `mv ${PathUtil.dataRootRenderValue}/tasks-history.jsonl ${PathUtil.dataRootRenderValue}/tasks-history.archive-<date>` (zero impact on tasks.json). Read it with `show` — never by reading the file by hand.
In `show`, note/log changes are rendered in a `## Note timeline` section (time order, content visible); state/structural events (create, status changes, prune …) are demoted to a separate `## Other events` section so they never bury the note line.
## Notes
- Errors carry codes (TASKLIST_*) — they are actionable, read them.
- close is idempotent (closing a done entry = no-op success).
- Ids are NEVER reused (a monotonic watermark lives in tasks.json) — a pruned entry's id is not recycled, so `show <id>` never mixes two generations. Residual: after a corruption quarantine the store restarts empty and the watermark restarts at 0 (the quarantine itself is recorded as a system event in the history).
- `note` is the current-state summary and is size-limited: ≤2,000 chars AND ≤8 KiB bytes per write (write path only — existing entries are never re-validated). Longer content belongs in `action=log` (split into several calls). `show` renders the most recent ≤50 note/log changes within a ≤20,000-char / ≤32 KiB budget and the most recent ≤50 state events in a separate section (≤4,000 chars); anything dropped is reported as "(+N older … not shown)" — never silently.
- Done entries auto-prune after 30 days on the next create (their change history survives the prune; outcome notes are the entry-level audit trail until then).
- Concurrency: reads tolerate a corrupted tasks.json (empty view, never crash); the first write after corruption quarantines the file (tasks.json.corrupt-<ts>) and starts fresh — nothing is silently overwritten (new fields links/parentId are discarded together with the rest of a quarantined file, and the quarantine itself is recorded in the history as a system event). Cross-process writers are not locked."""

  val inputSchema: JsonObject = JsonObject(
    "type" -> "object".asJson,
    "properties" -> Json.obj(
      "action" -> Json.obj(
        "type"        -> "string".asJson,
        "enum"        -> Json.arr("create".asJson, "update".asJson, "list".asJson, "close".asJson, "log".asJson, "show".asJson),
        "description" -> "create / update / list / close / log / show (see description).".asJson
      ),
      "title" -> Json.obj(
        "type"        -> "string".asJson,
        "description" -> "Entry title. Required for create; optional replacement for update.".asJson
      ),
      "id" -> Json.obj(
        "type"        -> "string".asJson,
        "description" -> "Entry id (from list). Required for update/close/log/show.".asJson
      ),
      "text" -> Json.obj(
        "type"        -> "string".asJson,
        "description" -> "log only: the remark to append to the change history (≤2,000 chars / ≤8 KiB). Never modifies `note`.".asJson
      ),
      "status" -> Json.obj(
        "type"        -> "string".asJson,
        "enum"        -> Json.arr("open".asJson, "in_progress".asJson, "blocked".asJson),
        "description" -> "New status for update. \"done\" is reached via action=close only.".asJson
      ),
      "project" -> Json.obj(
        "type"        -> "string".asJson,
        "description" -> "Project tag. create: optional; update: empty string clears; list: exact-match filter.".asJson
      ),
      "note" -> Json.obj(
        "type"        -> "string".asJson,
        "description" -> "create: initial note; update: REPLACES the note (≤2,000 chars; use log for long content); close: appended as a [done] outcome line.".asJson
      ),
      "blocks" -> Json.obj(
        "type"        -> "array".asJson,
        "items"       -> Json.obj("type" -> "string".asJson),
        "description" -> "Ids of entries this one depends on. create: initial list; update: FULL replacement ([] clears).".asJson
      ),
      "links" -> Json.obj(
        "type"        -> "array".asJson,
        "items"       -> Json.obj("type" -> "string".asJson),
        "description" -> "Free-form references (paths / commit hashes), max 20. create: initial list; update: FULL replacement ([] clears); log: optional, attached to that history entry. Reachability is NOT validated.".asJson
      ),
      "parentId" -> Json.obj(
        "type"        -> "string".asJson,
        "description" -> "Parent entry id (sub-task relation). create: optional; update: empty string clears; depth ≤5, cycles and unknown ids rejected.".asJson
      )
    ),
    "required" -> Json.arr("action".asJson)
  )

  def summarize(input: JsonObject): String =
    val a = input("action").flatMap(_.asString).getOrElse("?")
    val id = input("id").flatMap(_.asString).map(i => s"#$i").getOrElse("")
    s"TaskList($a$id)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 200 then result.take(197) + "..." else result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    IO.blocking {
      val action   = input("action").flatMap(_.asString).getOrElse("")
      val title    = input("title").flatMap(_.asString)
      val id       = input("id").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
      val status   = input("status").flatMap(_.asString)
      val project  = input("project").flatMap(_.asString)
      val note     = input("note").flatMap(_.asString)
      val blocks   = input("blocks").flatMap(_.as[List[String]].toOption)
      val links    = input("links").flatMap(_.as[List[String]].toOption)
      val text     = input("text").flatMap(_.asString)
      // parentId 空串 = 清除（原样传 None 表示「保留」）——两者语义在 updateSync 内区分
      val parentId = input("parentId").flatMap(_.asString)
      // 身份引擎侧派生（D5）：客户端传 actor/history 一律忽略（不进任何分支）
      val actor    = TaskListHistory.actorOf(ctx)

      action match
        case "create" =>
          TaskListStore.createSync(
            title = title.getOrElse(""),
            project = project,
            note = note,
            blocksRaw = blocks,
            linksRaw = links,
            parentId = parentId,
            actor = actor)
        case "update" =>
          id match
            case None =>
              Left(ToolError("TaskList: update requires `id` (entry id from action=list). (TASKLIST_PARAM)"))
            case Some(i) =>
              TaskListStore.updateSync(i, title, note, project, blocks, status,
                linksRaw = links, parentId = parentId, actor = actor)
        case "list" =>
          TaskListStore.listSync(project)
        case "close" =>
          id match
            case None =>
              Left(ToolError("TaskList: close requires `id` (entry id from action=list). (TASKLIST_PARAM)"))
            case Some(i) =>
              TaskListStore.closeSync(i, note, actor)
        case "log" =>
          id match
            case None =>
              Left(ToolError("TaskList: log requires `id` (entry id from action=list). (TASKLIST_PARAM)"))
            case Some(i) =>
              TaskListStore.logSync(i, text.getOrElse(""), linksRaw = links, actor = actor)
        case "show" =>
          id match
            case None =>
              Left(ToolError("TaskList: show requires `id` (entry id from action=list). (TASKLIST_PARAM)"))
            case Some(i) =>
              TaskListStore.showSync(i)
        case other =>
          Left(ToolError(
            s"TaskList: unknown action '$other' — one of create/update/list/close/log/show. (TASKLIST_ACTION)"))
    }
end TaskListTool
