package nebflow.core.project

import cats.effect.IO
import cats.effect.Ref
import cats.syntax.all.*
import io.circe.Codec
import io.circe.parser.parse as jsonParse
import io.circe.syntax.*
import io.circe.derivation.{Configuration, ConfiguredCodec}
import nebflow.core.{AtomicJson, NebflowLogger, PathUtil}
import nebflow.shared.Defaults

/**
 * BootDispatcherWake —— 宿主启动「自动重入」腿（方案件选项 A 档 A1「控制面唤醒腿」）。
 *
 * 设计正本（只读引用）：
 * `~/.nebflow/docs/Nebflow/20260913_075859_session-resume-defect-plan__chain-n-18f8a200.md`
 *   - §1.4(d)：缺陷正体 = 宿主重启后**没有任何东西主动叫醒控制面**（分发器会话不是
 *     节点、不在任何恢复扫描面；boot 期唯一唤醒源被 `if actions == 0` 闸住）⇒ 停摆窗
 *     无上界（本次实测 89.34 s / 150.93 s，且由一封无关 Mail 偶然结束）。
 *   - §2 A1：新增**独立于 crash-recovery** 的 boot 扫描腿——判据非空则经**既有通道**
 *     `DispatchNotify.defaultTrigger` 发一条 `TriggerDispatcher`（文本 = B 档清单）。
 *     零新调度器、零节点写、挂载顺序不变。
 *   - §4 表 A1 判红：≤60 s 无 dispatcher spawn 且 flow-map 有非终态节点 = 红；唤醒后
 *     首条输入不含清单 = 红；零动作 boot 后 5 min 无 spawn = 红（本腿即其修复面）。
 *   - ★② ③ ④ 红线：**不自动重激活 blocked / 不自动清 `pendingSuccession` / 不重投任何边**
 *     ⇒ 本腿**零节点写**、零投递面改动（唤醒本身不产生副作用；决定权仍在分发器，
 *     动作仍是既有 `NodeEdit`）。
 *
 * == 触发面（硬判据：仅在「宿主实例启动」这一事件上触发）==
 * 唯一生产调用点 = `GatewayMain` boot 链（`... *> projectCrashSweep *> projectBootWake
 * *> projectTtlScanner *> ...`），**在 server listen 之前**、每进程恰一次；本对象无
 * 任何被会话层（AgentActor/spawn 路径）/工具层（tools/）/定时扫描（`TtlTick` 挂载腿）
 * 引用的入口（`grep -rn "BootDispatcherWake.wakeAll" src/main` 恰一行）。
 * 「重启」与「普通会话启动」的区分信号 = **boot 实例 id**（[[instanceId]]，JVM
 * startTime+pid，每进程现铸）+ **调用点唯一在 boot 链**：普通会话启动没有一个参数/
 * 通道能落到本腿（对比：`settleRunnableSweep` / `sweepDestroyWindows` 等扫描腿挂在
 * `ProjectActor.TtlTick` 上——本腿刻意**不**挂任何 tick）。
 *
 * == 幂等与防重（硬判据：同一目标会话不得被重入两次）==
 * - **幂等键** = `"<bootId>|<project>"`（一次 boot 对一个项目至多一次唤醒）。
 * - **落盘标记** = `<workspace>/.nebflow/boot-wake.json`（append-only 滚动，保留最近
 *   [[MarkerKeepEntries]] 条）——记录每次唤醒的 `bootId / at / result / reason /
 *   blocking / 清单条目`；`blocking=true` 的条目使**同一 bootId 的第二次调用静默跳过**
 *   （跨调用路径/跨再入的权威去重面；进程内 Ref 只是快路径）。
 * - **重启风暴/连续重启（含看门狗拉起）**：每次 boot 都是新 bootId ⇒ **每 boot 恰一条**
 *   （线性，非累积、非循环）；重入循环在结构上不可能——本腿不是任何事件的监听者，
 *   也没有任何定时器，且唤醒产生的分发器动作不回调本腿。
 * - 判红：同一 boot 内同一项目 ≥2 次唤醒；或反复调用使唤醒数增长；或非 boot 链路径
 *   产生唤醒（spec 逐一钉死）。
 *
 * == 重入内容来源（硬判据：落盘事实，禁靠内存态）==
 * 清单由 [[BootWakeInventory.fromDisk]] 直读 `<workspace>/.nebflow/flow-map.json` +
 * `tasks/<id>.md` + `results/<id>.md` + transcript 存在性重建（**不经** store 内存 Ref、
 * 不经任何进程内表）⇒「内存态不可用（或陈旧）仍可重建」（spec 反向对照：磁盘与内存
 * 不一致时以磁盘为准）。
 *
 * == 失败降级（硬判据：显式记录 + 跳过，禁静默成功、禁重试成风暴）==
 * 逐条落 `ResultSkipped`/`ResultFailed` + 原因码 + 事件 + 日志，**不抛不吞**：
 *   1. `project-not-mounted`：项目在册但未挂载（mount fail-soft / 挂载失败）⇒ 跳过；
 *   2. 目标会话已终态（`sessionState=transcript-missing`，如 `n-639de2ee` 的
 *      `node-2da810ec` 形态）⇒ 清单内显式标 `resumable=false` + `terminalTargets` 计数，
 *      **不续跑**（★① 红线）；
 *   3. 上游失败/缺轨（`upstreamGap=true`）⇒ 显式标 `改接|承接|放弃` + 计数，
 *      **禁自动启动**（★④ 红线）。
 * 触发链失败（无 actorRef / 通道异常）= `ResultFailed(notify-failed)`：**一次尝试、
 * 零重试**（无定时器、无循环）⇒ 重试风暴在结构上不可达；上限判据 = 单 boot 每项目
 * 至多 1 次、单 boot 项目数上限 [[MaxProjectsPerBoot]]（超出记 `cap-exceeded`）。
 *
 * == 可观测 + 可关 ==
 * - 日志（`nebflow.log`）：`[boot-wake] project '<p>' auto-reentry: result=… reason=…
 *   nodes=… b1=… b2=… b3=… b4=… items=… at=…`（谁 / 何时 / 结果）＋ boot 汇总行
 *   （含零命中可读）。
 * - 事件流（`<workspace>/.nebflow/flow-map-events.jsonl`，type=`dispatcher-wake`）：
 *   与日志同源的 `k=v` 结构化摘要（`boot=` / `at=` / `result=` / `reason=` / 各档计数）。
 * - 面板：唤醒产生的**真实分发器会话**照既有链路出现在 subagent 面板（spawn 日志带
 *   session id），其首条输入即清单正文（蓝气泡 source 标注沿用既有 `dispatch` 定名）。
 * - 开关：`Defaults.BootDispatcherWakeEnabled`（system prop `nebflow.boot.dispatcherWake`，
 *   默认 **true**——方案 §2 A ⑤ 建议名 + 默认值，且与作者 09-13 裁定「重启后唤醒源 =
 *   直接上 A（自动重入）」同向）。**关掉 = 现状**：`GatewayMain` 不挂本腿，`wakeAll`
 *   自身也不写事件/标记、不触发（零残留、零半恢复态；回滚 = 置 false + 重启宿主）。
 */
object BootDispatcherWake:
  private val logger = NebflowLogger.forName("nebflow.project.boot-wake")

  /** 落盘标记 + 清单快照文件名（`<workspace>/.nebflow/boot-wake.json`）。 */
  val MarkerFileName: String = "boot-wake.json"

  /** 单 boot 项目数上限（结构上限，非配置项）：超出记 `cap-exceeded` 跳过——防「项目数
    * 异常膨胀导致一次 boot 唤醒风暴」。 */
  val MaxProjectsPerBoot: Int = 64

  /** 标记文件保留的历史条目数（滚动；只留审计窗，不做归档）。 */
  val MarkerKeepEntries: Int = 8

  /** 本 JVM 启动实例 id（**幂等键前缀**）：进程内稳定、跨 boot 必然不同。
    * 与 `GatewayMain` 的 PID 写入同源（`ProcessHandle`），补 JVM startTime 使
    * 「同 pid 复用」窗口下仍区分（pid 回绕/容器化场景）。 */
  lazy val instanceId: String =
    val rt = java.lang.management.ManagementFactory.getRuntimeMXBean
    s"${rt.getStartTime}-${ProcessHandle.current.pid}"

  // ── 结果与原因码（日志 / 事件 / 标记三面同码）──────────────────────────
  val ResultWoken: String = "woken"
  val ResultSkipped: String = "skipped"
  val ResultFailed: String = "failed"

  val ReasonDuplicate: String = "duplicate-boot"
  val ReasonNoReentry: String = "no-reentry-required"
  val ReasonNotMounted: String = "project-not-mounted"
  val ReasonCapExceeded: String = "cap-exceeded"
  val ReasonNotifyFailed: String = "notify-failed"
  val ReasonInternalError: String = "internal-error"

  /** 进程内快路径去重（权威去重面是落盘标记；本 Ref 只挡同 bootId 的重复调用）。 */
  private val wokenKeys: Ref[IO, Set[String]] = Ref.unsafe[IO, Set[String]](Set.empty)

  final case class Outcome(project: String, result: String, reason: String, items: Int, buckets: List[String])

  final case class WakeReport(bootId: String, projects: Int, outcomes: List[Outcome]):
    def woken: Int = outcomes.count(_.result == ResultWoken)
    def skipped: Int = outcomes.count(_.result == ResultSkipped)
    def failed: Int = outcomes.count(_.result == ResultFailed)
    /** 一行汇总（boot 级审计：含零命中形态）。 */
    def summary: String =
      val detail = outcomes
        .map(o => s"${o.project}=${o.result}${if o.reason.isEmpty then "" else s"(${o.reason})"}")
        .mkString(", ")
      s"boot=$bootId projects=$projects woken=$woken skipped=$skipped failed=$failed" +
        (if detail.isEmpty then "" else s" [$detail]")

  // ── 落盘标记（幂等锚 + 清单审计面）─────────────────────────────────────

  final case class MarkerItem(
    node: String,
    name: String,
    status: String,
    buckets: List[String],
    reason: String,
    session: String,
    sessionState: String,
    resumable: Boolean,
    task: String,
    result: String,
    recommend: String
  )
  object MarkerItem:
    given Configuration = Configuration.default.withDefaults
    given Codec[MarkerItem] = ConfiguredCodec.derived
    def of(i: BootWakeInventory.Item): MarkerItem =
      MarkerItem(
        node = i.nodeId,
        name = i.name,
        status = i.status,
        buckets = i.buckets,
        reason = i.reasons.mkString("; "),
        session = i.sessionRef,
        sessionState = i.sessionState,
        resumable = i.resumable,
        task = i.taskFile,
        result = i.resultFile,
        recommend = i.recommend
      )

  final case class MarkerEntry(
    bootId: String,
    project: String,
    at: Long,
    result: String,
    reason: String,
    /** true = 本条目对同一 bootId 的后续调用构成幂等跳过（终态结果）；
      * false = 仅审计留痕（如 `no-reentry-required`——同 boot 内状态可能再变）。 */
    blocking: Boolean,
    nodes: Int,
    items: List[MarkerItem]
  )
  object MarkerEntry:
    given Configuration = Configuration.default.withDefaults
    given Codec[MarkerEntry] = ConfiguredCodec.derived

  final case class MarkerFile(
    v: Int = 1,
    project: String = "",
    lastBootId: String = "",
    entries: List[MarkerEntry] = Nil
  )
  object MarkerFile:
    given Configuration = Configuration.default.withDefaults
    given Codec[MarkerFile] = ConfiguredCodec.derived

  /** 项目 `.nebflow` 目录（与 `FlowMapStore.open` / `ProjectMemory.path` 同规解析）。 */
  def nebflowDir(pd: ProjectDef): os.Path =
    os.Path(pd.workspace, PathUtil.dataRoot) / ".nebflow"

  def markerPath(pd: ProjectDef): os.Path = nebflowDir(pd) / MarkerFileName

  private def readMarker(p: os.Path): IO[MarkerFile] =
    IO.blocking {
      if !os.exists(p) then MarkerFile()
      else
        jsonParse(os.read(p)).flatMap(_.as[MarkerFile]) match
          case Right(m) => m
          case Left(_)  => MarkerFile()
    }.handleErrorWith(e =>
      logger.warn(s"boot-wake marker read failed at $p: ${e.getMessage}").as(MarkerFile()))

  /** 幂等判据：该 bootId 是否已有**终结型**条目（woken/failed/跳过型）。
    * 落盘面权威——即使进程内 Ref 因再入/重建而空，也不会二次唤醒。 */
  private[project] def hasBlockingEntry(marker: os.Path, bootId: String, project: String): IO[Boolean] =
    readMarker(marker).map(_.entries.exists(e => e.bootId == bootId && e.project == project && e.blocking))

  private def appendMarker(p: os.Path, project: String, e: MarkerEntry): IO[Unit] =
    readMarker(p).flatMap { f =>
      // 键 = (bootId, project)：即使两个项目共享同一标记文件（路径解析退化）也不会
      // 互相顶掉对方的幂等条目。
      val kept =
        (f.entries.filterNot(x => x.bootId == e.bootId && x.project == e.project) :+ e)
          .takeRight(MarkerKeepEntries)
      AtomicJson
        .write(p, MarkerFile(project = project, lastBootId = e.bootId, entries = kept).asJson.noSpaces)
        .handleErrorWith(err =>
          // fail-soft：标记写失败不阻断唤醒（幂等的第二道防线退化为进程内 Ref），但必须留痕。
          logger.warn(s"[boot-wake] marker write failed at $p: ${err.getMessage}"))
    }

  // ── 主入口 ────────────────────────────────────────────────────────────

  /** boot 链调用点（生产唯一）。参数全为测试/装配接缝，默认值即生产形态。
    *
    * @param trigger      None = 既有通道 `DispatchNotify.defaultTrigger`（TriggerDispatcher
    *                     → spawn/注入分发器会话）；Some = spec 捕获文本用接缝。
    * @param bootId       boot 实例 id（幂等键前缀），默认 [[instanceId]]。
    * @param enabled      开关（`nebflow.boot.dispatcherWake` 热读）——false 时**零动作**。
    * @param projects     None = `ProjectStore.list()`（磁盘在册项目，含未挂载者——
    *                     未挂载走 `project-not-mounted` 显式降级面）。
    */
  def wakeAll(
    trigger: Option[String => IO[Unit]] = None,
    bootId: String = instanceId,
    enabled: Boolean = Defaults.BootDispatcherWakeEnabled,
    nowMs: () => Long = () => System.currentTimeMillis,
    maxItems: Int = BootWakeInventory.MaxItems,
    sessionsDir: os.Path = PathUtil.dataRoot / "sessions",
    projects: Option[List[ProjectDef]] = None
  ): IO[WakeReport] =
    if !enabled then
      // 「关掉 = 现状」：零事件、零标记、零触发、零日志噪音（不写任何东西）。
      IO.pure(WakeReport(bootId, 0, Nil))
    else
      projects.fold(ProjectStore.list())(pds => IO.pure(pds)).flatMap { pds =>
        val ordered = pds.sortBy(_.name)
        ordered.zipWithIndex
          .traverse { case (pd, idx) =>
            val at = nowMs()
            if idx >= MaxProjectsPerBoot then
              record(pd, bootId, ResultSkipped, ReasonCapExceeded, None, blocking = false, atMs = at)
            else
              wakeProject(pd, bootId, trigger, nowMs, maxItems, sessionsDir, at)
                .handleErrorWith(e =>
                  record(
                    pd,
                    bootId,
                    ResultFailed,
                    ReasonInternalError,
                    None,
                    blocking = false,
                    atMs = at,
                    err = Some(e)
                  ))
          }
          .map(outs => WakeReport(bootId, ordered.size, outs))
      }

  private def wakeProject(
    pd: ProjectDef,
    bootId: String,
    trigger: Option[String => IO[Unit]],
    nowMs: () => Long,
    maxItems: Int,
    sessionsDir: os.Path,
    atMs: Long
  ): IO[Outcome] =
    val dir = nebflowDir(pd)
    val marker = dir / MarkerFileName
    val key = s"$bootId|${pd.name}"
    for
      dup <- hasBlockingEntry(marker, bootId, pd.name)
      memo <- wokenKeys.get.map(_.contains(key))
      out <-
        if dup || memo then
          logger
            .info(s"[boot-wake] project '${pd.name}' auto-reentry: result=$ResultSkipped reason=$ReasonDuplicate " +
              s"(boot=$bootId already woke this project — idempotent skip, no second wake)")
            .as(Outcome(pd.name, ResultSkipped, ReasonDuplicate, 0, Nil))
        else
          BootWakeInventory.fromDisk(dir, pd.name, nowMs(), maxItems, sessionsDir).flatMap {
            case Left(reason) =>
              // 落盘事实不可读（缺失/损坏）：显式记录 + 跳过（禁当「无工作」静默成功）。
              record(pd, bootId, ResultSkipped, reason, None, blocking = true, atMs = atMs)
            case Right(inv) if !inv.needWake =>
              record(pd, bootId, ResultSkipped, ReasonNoReentry, Some(inv), blocking = false, atMs = atMs)
            case Right(inv) =>
              ProjectRuntimeRegistry.get(pd.name).flatMap {
                case None =>
                  record(pd, bootId, ResultSkipped, ReasonNotMounted, Some(inv), blocking = true, atMs = atMs)
                case Some(rt) =>
                  val fire = trigger.getOrElse(
                    DispatchNotify.defaultTrigger(pd.name, rt.engine.rootSessionId))
                  wokenKeys.update(_ + key) *>
                    fire(wakeText(inv, bootId)).attempt.flatMap {
                      case Right(_) =>
                        record(pd, bootId, ResultWoken, "", Some(inv), blocking = true, atMs = atMs)
                      case Left(e) =>
                        // 触发链失败 = 显式失败 + **零重试**（无定时器、无循环 ⇒ 风暴不可达）。
                        record(pd, bootId, ResultFailed, ReasonNotifyFailed, Some(inv), blocking = true,
                          atMs = atMs, err = Some(e))
                    }
              }
          }
    yield out

  /** 落盘（标记）+ 事件 + 日志三面同源记录；任一面失败均 fail-soft（不阻断其余面、
    * 不抛给 boot 链——boot 永不因本腿失败）。 */
  private def record(
    pd: ProjectDef,
    bootId: String,
    result: String,
    reason: String,
    inv: Option[BootWakeInventory.Inventory],
    blocking: Boolean,
    atMs: Long,
    err: Option[Throwable] = None
  ): IO[Outcome] =
    val counts = inv.map { i =>
      (i.nodes, i.inBucket(BootWakeInventory.BucketLooseRunning).size,
        i.inBucket(BootWakeInventory.BucketAwaitingHandover).size,
        i.inBucket(BootWakeInventory.BucketDeadBarrier).size,
        i.inBucket(BootWakeInventory.BucketBlocked).size)
    }.getOrElse((0, 0, 0, 0, 0))
    val items = inv.map(_.items.map(MarkerItem.of)).getOrElse(Nil)
    val entry = MarkerEntry(bootId, pd.name, atMs, result, reason, blocking, counts._1, items)
    val logLine =
      s"[boot-wake] project '${pd.name}' auto-reentry: result=$result" +
        (if reason.isEmpty then "" else s" reason=$reason") +
        s" boot=$bootId nodes=${counts._1} b1=${counts._2} b2=${counts._3} b3=${counts._4} b4=${counts._5}" +
        inv.map(i => s" items=${i.items.size}${if i.truncated > 0 then s"(+${i.truncated} more)" else ""}" +
          s" terminalTargets=${i.terminalTargets} upstreamGaps=${i.upstreamGaps}").getOrElse("") +
        s" at=$atMs dispatcher=(spawn/inject via existing TriggerDispatcher channel)" +
        err.map(e => s" error=${Option(e.getMessage).getOrElse(e.toString)}").getOrElse("")
    for
      _ <- appendMarker(markerPath(pd), pd.name, entry)
      _ <- FlowMapEventLog
        .append(pd.workspace, pd.name, pd.name, FlowMapEventLog.DispatcherWakeType,
          FlowMapEventLog.dispatcherWakeSummary(bootId, atMs, result, reason,
            (counts._1, counts._2, counts._3, counts._4, counts._5),
            inv.map(_.items.size).getOrElse(0), inv.map(_.truncated).getOrElse(0)))
        .handleErrorWith(e =>
          logger.warn(s"[boot-wake] event append failed for project '${pd.name}': ${e.getMessage}"))
      _ <- if result == ResultFailed then logger.warn(logLine) else logger.info(logLine)
    yield Outcome(pd.name, result, reason, inv.map(_.items.size).getOrElse(0),
      inv.map(_.items.flatMap(_.buckets).distinct).getOrElse(Nil))

  // ── 清单正文（= 分发器会话首条输入；方案 §2 B 输出形态）──────────────────

  /** 唤醒文本：B 档格式清单 + **零自动行为声明** + 处置指引（方案判红：清单不得
    * 缺该声明，否则会被误当授权）。 */
  def wakeText(inv: BootWakeInventory.Inventory, bootId: String): String =
    val hist = NodeLifecycle.All.toList
      .map(s => s"$s=${inv.histogram.getOrElse(s, 0)}")
      .mkString(" ")
    def itemsOf(b: String): String =
      val xs = inv.inBucket(b)
      if xs.isEmpty then "（无）"
      else xs.map(renderItem).mkString("\n")
    s"""[boot-wake] 宿主重启自动重入 —— 项目「${inv.project}」需重入清单（${inv.items.size} 项${if inv.truncated > 0 then s" + 另外 ${inv.truncated} 项已省略" else ""}；boot=$bootId）
       |
       |本清单**仅供处置，不产生任何自动行为**：引擎未改动任何节点状态、未自动承接、未自动重激活、未重投任何边；无自动重试。
       |成因：宿主重启后分发器会话不续存（方案 §1.4(d)），而 boot 期唯一唤醒源被「零崩溃残留」闸住 ⇒ 「零 running 但有未完成工作」的项目此前无人唤醒，停摆窗无上界。
       |
       |判据来源 = **落盘事实（读盘重建，非内存态）**：.nebflow/flow-map.json（状态/拓扑/摘要）+ .nebflow/tasks/<id>.md（任务书）+ .nebflow/results/<id>.md（结果件）+ 会话 transcript 存在性（sessions/<sid>.json）。
       |
       |B0 五态：$hist（活动区节点总数 ${inv.nodes}）
       |B1 落单 running（目标会话已终态，不可续跑）：${inv.inBucket(BootWakeInventory.BucketLooseRunning).size} 项
       |B2 待承接（pendingSuccession 非空）：${inv.inBucket(BootWakeInventory.BucketAwaitingHandover).size} 项
       |B3 死 barrier（上游全终态 ∧ 超 ${BootWakeInventory.stallMs / 1000L}s 未触发）：${inv.inBucket(BootWakeInventory.BucketDeadBarrier).size} 项
       |B4 待裁决 blocked：${inv.inBucket(BootWakeInventory.BucketBlocked).size} 项
       |
       |逐条：
       |B1：
       |${itemsOf(BootWakeInventory.BucketLooseRunning)}
       |B2：
       |${itemsOf(BootWakeInventory.BucketAwaitingHandover)}
       |B3：
       |${itemsOf(BootWakeInventory.BucketDeadBarrier)}
       |B4：
       |${itemsOf(BootWakeInventory.BucketBlocked)}
       |
       |处置指引：先 NodeList(project="${inv.project}") 读现状，再按条目「建议」在**本地**决定并走既有 NodeEdit 语义——
       |承接（把新上游 append 进 in）/ 改接（替换 in 来源）/ 重激活（blocked 重跑）/ 放弃（abandon）。
       |上游缺轨（failed/blocked/cancelled）的条目**不得**直接启动下游：barrier 会以缺轨输入产出错误结论（pendingSuccession 就是为此留痕的闸）。
       |merge 节点收口仍须走既有 MergeNodePolicy 与 .nebflow/locks/main-merge.lock 纪律。
       |无需回报——拓扑与状态已落 Flow Map。""".stripMargin

  private def renderItem(i: BootWakeInventory.Item): String =
    s"""- [${i.bucketLabel}] ${i.nodeId} '${i.name}' status=${i.status}
       |    判据: ${i.reasons.mkString("; ")}
       |    上游: ${i.upstream}${if i.upstreamGap then "  ← 缺轨(禁自动启动)" else ""}
       |    目标会话: ${if i.sessionRef.isEmpty then "-" else i.sessionRef} sessionState=${i.sessionState} resumable=${i.resumable}${if !i.resumable then " (已终态/不可续跑 — 不自动续)" else ""}
       |    destroyAt: ${i.destroyAt}
       |    任务书: ${i.taskFile}
       |    结果件: ${i.resultFile}
       |    建议: ${i.recommend}
       |    详情: ${i.detail}""".stripMargin
