package nebflow.core.project

import cats.effect.IO
import cats.syntax.all.*
import io.circe.parser.parse as jsonParse
import nebflow.core.PathUtil

/**
 * BootWakeInventory —— 宿主启动自动重入的「落盘事实清单」构建器（选项 A 档的输入面，
 * 采用方案件 B 档格式）。
 *
 * 设计正本（方案件，只读引用）：
 * session-resume-defect-plan 设计件（内部留档）
 *   - §2 A1：boot 唤醒腿携带的清单 = 选项 B 的清单（本对象即其判据实现）
 *   - §2 B：B1–B5 分档（本对象产出 B1–B4 条目；B5「需唤醒项目」= 条目非空，见
 *     [[BootDispatcherWake]]）
 *   - §4 表 B 判红：判据必须落**持久字段**（不得读内存态）；合法等待不得假阳；
 *     blocked 与 pendingSuccession 不得漏报；清单必须带「零自动行为」声明
 *     （声明在 `BootDispatcherWake.wakeText` 头部，本对象只产数据）
 *   - ★① 红线：终态节点永不入选（`n-639de2ee` 类已回收会话不得被当断点续跑）
 *
 * 三条硬约束（逐条落到代码）：
 *   1. **只读落盘**：唯一数据源 = `<workspace>/.nebflow/flow-map.json`（落盘 JSON 的
 *      状态/拓扑 + task/result **摘要与指针**）+ `tasks/<id>.md`、`results/<id>.md`
 *      （全文件，仅取大小）+ 会话 transcript 存在性（`<sessionsDir>/<sid>.json`）。
 *      **不读** `FlowMapStore` 内存 Ref、不读任何进程内表 ⇒「内存态不可用（或陈旧）
 *      仍可重建」（spec 反向对照：磁盘与内存不一致时**以磁盘为准**）。
 *   2. **判据与引擎同源**：B3 停滞判据 = `NodeEngine.mountStallReason` 同款（上游集 =
 *      `in ∪ deps ∪ pendingSuccession`；任一上游非终态 = 合法等待**不判**；上游引用
 *      悬空 = 保守**不判**；可触发点 t0 = 最晚上游 `completedAt`，无上游则 `createdAt`；
 *      60 s 档引用 `NodeEngine.MountStalledMs` 单点，不另立阈值）。
 *   3. **终态永不续跑**（方案 ★①/R4）：`resumable` 判据恒含「状态非终态」——已被回收
 *      的终态会话（transcript 文件可能仍在盘上，实测 `node-2da810ec.json` 先例）永不
 *      被标为可续，terminal 节点只出现在直方图与 B4（blocked，待裁决档）。
 *
 * 本对象**零写动作**：只读盘 + 产数据。一切写（事件/标记/唤醒）在 [[BootDispatcherWake]]。
 */
object BootWakeInventory:

  /** 活动区落盘文件名（与 `FlowMapStore.open` 的 statePath 同名同目录；常量在此
    * 显式声明以免读方与写方口径漂移）。
    */
  val FileName: String = "flow-map.json"

  /** 单次清单条目上限（超出部分进 `truncated` 计数并由渲染层标 `... N more`——
    * 不静默丢弃，工具结果风格先例）。 */
  val MaxItems: Int = 20

  /** 逐条字段的字符上限（详情行长控制：清单要进分发器首条输入 = LLM 上下文）。 */
  val DetailCap: Int = 220

  /** B3 停滞 60 s 档（单点引用引擎常量，两处口径恒同源）。 */
  def stallMs: Long = NodeEngine.MountStalledMs

  // ── 档位（方案 §2 B 表逐档同码）────────────────────────────────────────
  val BucketLooseRunning: String = "B1"      // 落单 running（无活会话/transcript 缺失）
  val BucketAwaitingHandover: String = "B2"  // 待承接（pendingSuccession 非空）
  val BucketDeadBarrier: String = "B3"       // 死 barrier（上游全终态 ∧ 超 60 s 未触发）
  val BucketBlocked: String = "B4"           // 待裁决 blocked

  /** 会话（节点 transcript）现状三分——落盘存在性判据，不含任何进程内状态。 */
  val SessionPresent: String = "transcript-present"
  val SessionMissing: String = "transcript-missing"
  val SessionAbsentRef: String = "no-session-ref"

  /** 单条清单项（全部字段可核对：来源 = 落盘字段或落盘文件）。 */
  final case class Item(
    nodeId: String,
    name: String,
    status: String,
    buckets: List[String],
    /** 判据短码（人可读，含命中细节，如 `blocked(blockCount=1)`）。 */
    reasons: List[String],
    upstream: String,
    /** 上游含 failed/blocked/cancelled（缺轨：**禁自动启动**，需承接/改接/放弃）。 */
    upstreamGap: Boolean,
    sessionRef: String,
    sessionState: String,
    /** 目标会话可续跑（= transcript 在盘 ∧ 状态非终态）；终态恒 false（★① 红线）。 */
    resumable: Boolean,
    destroyAt: String,
    taskFile: String,
    resultFile: String,
    recommend: String,
    detail: String
  ):
    def bucketLabel: String = buckets.mkString("+")

  /** 清单（项目级）。`histogram` 覆盖全部七态（缺失态计 0——判红判据「不得漏报」的可核面）。 */
  final case class Inventory(
    project: String,
    atMs: Long,
    histogram: Map[String, Int],
    nodes: Int,
    items: List[Item],
    truncated: Int
  ):
    def needWake: Boolean = items.nonEmpty
    def inBucket(b: String): List[Item] = items.filter(_.buckets.contains(b))
    /** 需人工/分发器裁决的目标会话中，已不可续（会话已终态/已回收）的条数。 */
    def terminalTargets: Int = items.count(!_.resumable)
    def upstreamGaps: Int = items.count(_.upstreamGap)

  /** 读盘构建清单（唯一生产入口）。workspace 下的 `.nebflow` 目录由调用方给出
    * （与 `FlowMapStore.open` / `ProjectMemory.path` 同规解析）。
    *
    * 返回 `Left(reason)` = 落盘事实不可用（缺失/损坏）——调用方**显式记录并跳过**，
    * 禁止当作「无工作」静默成功（方案 §4 判红「漏报」）。
    */
  def fromDisk(
    nebflowDir: os.Path,
    project: String,
    nowMs: Long,
    maxItems: Int = MaxItems,
    sessionsDir: os.Path = PathUtil.dataRoot / "sessions"
  ): IO[Either[String, Inventory]] =
    IO.blocking {
      val f = nebflowDir / FileName
      if !os.exists(f) then Left("flow-map-missing")
      else
        val raw = os.read(f)
        jsonParse(raw) match
          case Left(e) =>
            Left(s"flow-map-unreadable (invalid JSON: ${Option(e.getMessage).getOrElse(e.toString)})")
          case Right(j) =>
            j.as[FlowMapState] match
              case Left(e) =>
                Left(s"flow-map-unreadable (decode failed: ${Option(e.getMessage).getOrElse(e.toString)})")
              case Right(st) => Right(build(st, nebflowDir, project, nowMs, maxItems, sessionsDir))
    }.handleErrorWith(e =>
      // 读盘异常（权限/IO）= 落盘事实不可用，同样走显式降级面（不抛给 boot 链）。
      IO.pure(Left(s"flow-map-unreadable (read failed: ${Option(e.getMessage).getOrElse(e.toString)})")))

  /** 纯重建入口（spec 接缝 + 「内存态不可用仍可重建」的直接证据）：给一段
    * flow-map.json **原文**即可产出整张清单——不需要 store / engine / registry /
    * 任何进程内状态。 */
  def fromJson(
    raw: String,
    nebflowDir: os.Path,
    project: String,
    nowMs: Long,
    maxItems: Int = MaxItems,
    sessionsDir: os.Path = PathUtil.dataRoot / "sessions"
  ): Either[String, Inventory] =
    jsonParse(raw)
      .leftMap(e => s"flow-map-unreadable (invalid JSON: ${Option(e.getMessage).getOrElse(e.toString)})")
      .flatMap(
        _.as[FlowMapState]
          .leftMap(e => s"flow-map-unreadable (decode failed: ${Option(e.getMessage).getOrElse(e.toString)})")
          .map(st => build(st, nebflowDir, project, nowMs, maxItems, sessionsDir)))

  /** 清单构建（纯）。 */
  def build(
    st: FlowMapState,
    nebflowDir: os.Path,
    project: String,
    nowMs: Long,
    maxItems: Int = MaxItems,
    sessionsDir: os.Path = PathUtil.dataRoot / "sessions"
  ): Inventory =
    val all = st.nodes
    val histogram = NodeLifecycle.All.toList.map(s => s -> all.values.count(_.status == s)).toMap
    val items = all.values.toList
      .sortBy(n => (n.createdAt, n.id))
      .flatMap(n => itemFor(n, all, nebflowDir, sessionsDir, nowMs))
    Inventory(
      project = project,
      atMs = nowMs,
      histogram = histogram,
      nodes = all.size,
      items = items.take(maxItems),
      truncated = math.max(0, items.size - maxItems)
    )

  // ── 逐节点分档（判据逐条对齐方案 §2 B1–B4）────────────────────────────

  private[project] def itemFor(
    n: NodeDef,
    all: Map[String, NodeDef],
    dir: os.Path,
    sessionsDir: os.Path,
    nowMs: Long
  ): Option[Item] =
    val (sid, sessState, resumeOk) = sessionState(n, sessionsDir)
    val buckets = scala.collection.mutable.ListBuffer.empty[String]
    val reasons = scala.collection.mutable.ListBuffer.empty[String]

    // B1 落单 running：状态 = running 而目标会话已无活 transcript（崩溃残留形态；
    // 有 transcript 的 running 归 crash-recovery 认领，不是本档的活）。
    if n.status == NodeLifecycle.Running && sessState != SessionPresent then
      buckets += BucketLooseRunning
      reasons += (if sid.isEmpty then "running-without-session-ref"
                  else "running-without-transcript")

    // B2 待承接：pendingSuccession（被摘除上游的显式「缺失」标记，引擎三闸据此不放行）。
    if n.pendingSuccession.nonEmpty then
      buckets += BucketAwaitingHandover
      reasons += s"awaiting-handover(from=${n.pendingSuccession.mkString(",")})"

    // B3 死 barrier：与引擎 mountStallReason 同款判据（含合法等待/悬空豁免）。
    stallReason(n, all, nowMs).foreach { r =>
      buckets += BucketDeadBarrier
      reasons += r
    }

    // B4 待裁决 blocked（终态但永不过期 = 待办语义；只登记，绝不自动重激活）。
    if n.status == NodeLifecycle.Blocked then
      buckets += BucketBlocked
      reasons += s"blocked(blockCount=${n.blockCount})"

    if buckets.isEmpty then None
    else
      val upIds = (n.in ++ n.deps ++ n.pendingSuccession).distinct
      val upstreamDesc =
        if upIds.isEmpty then "none (entry node)"
        else
          upIds
            .map { id =>
              all.get(id) match
                case Some(u) => s"'${u.name}'($id):${u.status}"
                case None    => s"$id:missing(dangling)"
            }
            .mkString(", ")
      val upGap = upIds.exists(id =>
        all.get(id).exists(u => NodeLifecycle.Terminal.contains(u.status) && u.status != NodeLifecycle.Completed))
      val blockedFeedback = n.blockedFeedback
        .map(f => s" feedback=${oneLine(f.category + ": " + f.detail, 80)}")
        .getOrElse("")
      val taskInfo = fileInfo(dir, FlowMapStore.TasksDirName, n.id, n.task)
      val resultInfo = fileInfo(dir, FlowMapStore.ResultsDirName, n.id, n.result)
      Some(
        Item(
          nodeId = n.id,
          name = n.name,
          status = n.status,
          buckets = buckets.toList,
          reasons = reasons.toList,
          upstream = upstreamDesc,
          upstreamGap = upGap,
          sessionRef = sid,
          sessionState = sessState,
          resumable = resumeOk,
          destroyAt = n.destroyAt.map(d => s"${(nowMs - d) / 1000L}s-ago(+${(d - nowMs) / 1000L}s)").getOrElse("-"),
          taskFile = taskInfo,
          resultFile = resultInfo,
          recommend = recommend(n, buckets.toList, upGap, resumeOk),
          detail = oneLine(
            s"${n.task.orElse(n.description).getOrElse("-")}$blockedFeedback", DetailCap)
        ))
  end itemFor

  /** B3 停滞判据（与 `NodeEngine.mountStallReason` 逐条同款，只读落盘字段）。
    * 返回 None = 不判（非 pending/wiring / 有合法等待 / 悬空引用 / 未到 60 s 档）。 */
  private[project] def stallReason(n: NodeDef, all: Map[String, NodeDef], nowMs: Long): Option[String] =
    if n.status != NodeLifecycle.Pending && n.status != NodeLifecycle.Wiring then None
    else
      val ups = (n.in ++ n.deps ++ n.pendingSuccession).distinct.map(all.get)
      if ups.exists(_.isEmpty) then None // 悬空引用：保守不判（引擎同款）
      else
        val us = ups.flatten
        if !us.forall(u => NodeLifecycle.Terminal.contains(u.status)) then None // 合法等待（有 running/wiring 上游）
        else
          val t0 =
            if us.isEmpty then n.createdAt
            else us.flatMap(_.completedAt).maxOption.getOrElse(n.createdAt)
          val stalledMs = nowMs - t0
          if stalledMs <= stallMs then None
          else
            val gapNote =
              if us.exists(u => u.status != NodeLifecycle.Completed) then
                s"; upstream gap present (${us.filter(u => u.status != NodeLifecycle.Completed).map(u => s"'${u.name}':${u.status}").mkString(",")}) — barrier can never satisfy, handover/rewire needed"
              else ""
            Some(s"stalled(${stalledMs / 1000L}s past triggerable point, all upstreams terminal)$gapNote")

  /** 会话现状（落盘判据）：`sessionRef` + `<sessionsDir>/<sid>.json` 存在性。
    * `resumable` = transcript 在盘 ∧ 状态非终态（★① 红线：终态节点恒 false）。 */
  private[project] def sessionState(n: NodeDef, sessionsDir: os.Path): (String, String, Boolean) =
    n.sessionRef match
      case None => ("", SessionAbsentRef, false)
      case Some(sid) =>
        val f = sessionsDir / s"$sid.json"
        val present = os.exists(f) && os.size(f) > 0
        val state = if present then SessionPresent else SessionMissing
        val resumable = present && !NodeLifecycle.Terminal.contains(n.status)
        (sid, state, resumable)

  /** 建议动作（决策仍归分发器/人；本字段是提示不是动作——零自动行为）。
    * 取值口径对齐方案 §2 B 表输出形态：承接 | 改接 | 重激活 | 放弃 | 忽略。 */
  private def recommend(n: NodeDef, buckets: List[String], upGap: Boolean, resumable: Boolean): String =
    if buckets.contains(BucketBlocked) then
      "重激活|承接|改接|放弃 (blocked=需裁决, 永不自动重激活)"
    else if upGap then "改接|承接|放弃 (上游缺轨, 禁自动启动)"
    else if buckets.contains(BucketAwaitingHandover) then "承接(把新上游 append 进 in)|改接|放弃"
    else if buckets.contains(BucketDeadBarrier) then
      if n.status == NodeLifecycle.Pending || n.status == NodeLifecycle.Wiring then
        "承接|改接 (barrier 已清仍停滞, 需人工介入)"
      else "改接|放弃"
    else if buckets.contains(BucketLooseRunning) then
      if resumable then "重激活|承接 (会话可续)" else "重激活|承接|放弃 (目标会话已终态, 不可续跑)"
    else "忽略"

  /** 文件事实（相对路径 + 字节数；缺失文件标 `-`）——「任务书 / 结果件」两面的
    * 可核读数（方案 B 表：`sessionRef 与 transcript 现状` 同条规定）。 */
  private def fileInfo(dir: os.Path, sub: String, id: String, summary: Option[String]): String =
    val rel = s"$sub/$id.md"
    val f = dir / sub / s"$id.md"
    val size = if os.exists(f) then fmtBytes(os.size(f)) else "-"
    val head = summary.filter(_.trim.nonEmpty).map(s => s" '${oneLine(s, 40)}'").getOrElse("")
    s"$rel($size)$head"

  private def fmtBytes(n: Long): String =
    if n >= 1024L * 1024L then f"${n / (1024.0 * 1024.0)}%.1fMB"
    else if n >= 1024L then f"${n / 1024.0}%.1fKB"
    else s"${n}B"

  /** 单行化 + 截断（清单进 LLM 上下文与 `k=v` 事件摘要，禁裸换行）。 */
  private def oneLine(s: String, cap: Int): String =
    val t = Option(s).getOrElse("").replaceAll("\\s+", " ").trim
    if t.length > cap then t.take(cap) + "…" else t
