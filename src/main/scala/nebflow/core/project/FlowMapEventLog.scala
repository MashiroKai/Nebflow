package nebflow.core.project

import cats.effect.IO
import io.circe.syntax.*
import io.circe.Json
import nebflow.core.PathUtil

/**
 * Flow Map 调整事件审计日志（blocked 反馈重入设计 §4.4）。
 *
 * `<workspace>/.nebflow/flow-map-events.jsonl` 追加式 JSONL，每行一事件：
 * `{ts, type, project, nodeId, summary}`，
 * type ∈ blocked / reentry-triggered / reactivated / abandoned / escalated /
 *   cooldown-on / reaped / merge-blocked /
 *   settle-sweep / trigger-starved / start-aborted（trigger-chain-fix 批）/
 *   bg-wait / bg-wait-timeout / bg-released（bgtask-completion-gate 批）/
 *   mount-stalled（mount-enforce 批：可触发点后 60s 仍未触发的挂载停滞留痕，
 *   summary 含等待原因——上游终态明细 + barrier 残缺清单）/
 *   node-ask（D6 批 F1 2026-09-08：项目节点 AskUser 提问留痕——方案 A 直达作者
 *   的监督补齐件，summary 含节点名/requestId/问题摘要；写入点 AgentActor AskUser
 *   处理链，分发器经事件流审计可见）/
 *   boot-recovery（crash-recovery 批 2026-09-07：boot sweep 每个认领动作——
 *   rehydrate 认领 / (c) 类 failNode，summary 含三分类与 transcript 指针——
 *   「禁止静默自愈」纪律，settle-sweep 先例同款）/
 *   chain-archived（P3 引擎侧归档联动批 2026-09-10：整链出库（sweepCompletedChains）
 *   时追加——nodeId = 分量内 createdAt 最早节点（与链 id 派生同源），summary =
 *   `chain=<id> archivedAt=<ms> members=<n>`，顶层 chainId 同值；索引维护消费者
 *   DocIndexConsumer 据此翻 INDEX.md 条目 state）/ chain-restored（同批定义的对称
 *   事件类型——链抽象 P2 restoreChain 拉回时索引回翻；**接口点，本批无写入点**）/
 *   hard-recovery（hard-recovery 批 2026-09-07 起由 NodeEngine.hardResumeNode 写
 *   「resumed from stuck」；取消静默死锁修复批 R5 补写 resume **失败**腿——
 *   `L3 resume FAILED … node left cancelled; dispatcher notified (R1) + out detached (R4)`）/
 *   cancelled（**取消静默死锁修复批 R2** 2026-09-10：cancelled 终态化留痕——
 *   `node cancelled [source=engine|user]: <reason>`（+ R4 摘除目标清单）。此前
 *   cancelled 唯一留痕是 bg-harvest 那行**无原因**文本，取消原因全系统零落盘）/
 *   barrier-blocked（**取消静默死锁修复批 R3**：终态写点（cancelled/failed）
 *   同步做下游 barrier 检查，已被终态上游永久闸死 → **即时**告警（0 延迟，不设
 *   60s 档）。周期回扫的 mount-stalled 保留为兜底，两者由 NodeEngine 的
 *   stallNotified + barrierAlerted 单发记账去重——同一停滞不发两条）/
 *   dispatcher-idle-expired（**令 3 分发器生命周期** 2026-09-12：分发器会话空闲
 *   超过 `Defaults.DispatcherIdleWindowMs` 被 30 s 扫描腿拆除时留痕——
 *   summary = `session=<dispatcher-xxxxxxxx> idleSecs=<n> windowMs=<n>`，
 *   nodeId 字段承载会话 id；「活着但空闲」与「已销毁」的事后对齐面）/
 *   dispatcher-wake（**宿主启动自动重入批** 2026-09-13，方案件 A 档 A1：boot 链
 *   `projectBootWake` 腿对每个在册项目做一次唤醒判定，写点 = `BootDispatcherWake.record`
 *   ——「谁 / 何时 / 结果」的正向留痕，取代此前「零动作 boot 零日志行、只能靠缺失行
 *   推断」的取证面；summary 见 [[dispatcherWakeSummary]]，nodeId 字段承载项目名）/
 *   merge-queue（**mergefifo-engine 批** 2026-09-13：合并窗 FIFO 互斥闸的停等留痕
 *   （`kind=hold`）+ **O-1 已知缺口告警**（`kind=same-git-dir-multi-project`：两项目
 *   共用同一 git 目录 ⇒ 引擎侧漏互斥，本批只检测告警不实现 claim；写点 =
 *    `NodeEngine.logMutexHold` / `NodeEngine.alarmSameGitDirProjects`，summary 见
 *   [[mergeQueueHoldSummary]] / [[mergeQueueSameGitDirSummary]]）/
 *   abandoned-detach（**cancelled 滞留主图修复批 · 案 A** 2026-09-14：存量回填腿
 *   对被 retired 却仍挂在活链上的 cancelled 节点补做摘边时的留痕，写点 =
 *   `NodeEngine.backfillAbandonedDetach`；见 [[AbandonedDetachType]]）。
 * 注册式扩展：append API 无 schema 变更，新事件类型 = 本清单加一词 + 写入点调用；
 * chainId 为顶层**可选**字段（2026-09-10 加，spec §9.2 项 9）：旧行无该键照常解析
 * （零迁移、append-only），新行仅在链族事件带上。
 *
 * 0 schema 迁移（独立文件不碰 flow-map.json 契约）、append-only、重启保留、grep 友好。
 * 写入点：NodeEngine.blockedNode（blocked）/ mergeBlockedByUpstream
 * Failure（merge-blocked）/ runWithAgent 翻转异常中止（start-aborted）/ settleRunnable
 * Sweep（settle-sweep、trigger-starved、mount-stalled）/ reapStaleRunning（reaped）、
 * FeedbackRouter（reentry-triggered / escalated / cooldown-on）、NodeEditTool 重激活与
 * abandon 两分支（reactivated / abandoned）。
 */
object FlowMapEventLog:
  val FileName = "flow-map-events.jsonl"

  /** 链归档事件类型（写点：ProjectActor TtlTick → sweep 出库后追加）。 */
  val ChainArchivedType = "chain-archived"

  /** **abandon 回填摘边事件类型**（cancelled 滞留主图修复批 · 案 A 腿 2，2026-09-14
    * 作者 17:24 拍板）。写点 = [[NodeEngine.backfillAbandonedDetach]]（30s `TtlTick`
    * 扫描腿，排在链级归档 sweep 之前）——对**已 cancelled 且仍有挂线**的滞留节点补做
    * 摘边（`NodeEngine.detachAbandonedNode`）时逐件留痕。
    *
    * 与同批的 `abandoned`（工具路径 `NodeEditTool.abandonNode` 的写点）**分开记账**：
    * 本条回答的是「我没动过这个节点，它的拓扑为什么变了」——回填是引擎自主动作，
    * 与被退役时刻的 `abandoned` 行不是同一事实。幂等：`RetireDetach.isEmpty` 时零写。 */
  val AbandonedDetachType = "abandoned-detach"

  /** 链拉回事件类型（对称口径，spec §6.2/§9.3：链抽象 P2 `restoreChain` 落地后由
    * 其调用点写入；**本批只定义类型 + 消费者回翻分支，无写入点**——禁止虚构调用点）。 */
  val ChainRestoredType = "chain-restored"

  /** **判词闸回退告警**事件类型（engine-defects 批 #238：2026-09-15 `8a3ac535e` 定义，
    * 同日泛化笔 `v238-impl` 语义反转为**回退检测器**——类型串保持不变，消费面零迁移）。
    *
    * 写点 = [[NodeEngine.startNode]] 的 verdict 闸收口（`NodeEngine.logVerdictGateBreach`）。
    *   - **泛化前**（`8a3ac535e`）：判词闸是 merge-only ⇒「非 merge 收口位带非 pass 判词
    *     上游仍被拉起」属**常态**，本行 = 「人肉口径 → 机械口径」的可见化；
    *   - **泛化后**（同批第二笔）：闸覆盖全部收口位（判据对节点形态零分叉）⇒ 该形态
    *     **结构性不可能再发生**（本告警与闸共用 `staleVerdictUps` 单点，闸持有时走不到写点）
    *     ⇒ 本行语义 = **不变式告警**：出现即表示闸被绕过 / 被改弱（或新增了绕开
    *     `startNode` 收口的启动腿）。
    * nodeId = 被启动的下游；同一 (下游, 持有者清单) 只发一次（单发记账防刷屏）。
    * 取证：`grep 'verdict-gate-gap' <ws>/.nebflow/flow-map-events.jsonl`。 */
  val VerdictGateGapType = "verdict-gate-gap"

  /** 分发器会话空闲到期销毁事件类型（**令 3 分发器生命周期** 2026-09-12 批，设计
    * §3.2/§4 R4-(a)）：写点 = `ProjectActor.expireIdleDispatcher`（30 s `TtlTick`
    * 扫描腿到点拆除时）。语义 = 「保活期结束 ⇒ 会话已销毁」，使「活着但空闲」与
    * 「已销毁」在事后可对齐（面板/registry 在 turn 末即无行，空闲期无第二观察面）。
    * `nodeId` 字段承载**会话 id**（`dispatcher-<uuid8>`）——分发器不是 Flow 节点、
    * 无 NodeDef.id（`ProjectActor` spawn 处 `flowChainId = None` 同口径）；不写
    * `chainId`（分发器不属任何链）。 */
  val DispatcherIdleExpiredType = "dispatcher-idle-expired"

  /** 空闲到期事件结构化 summary（`k=v` 单空格分隔，值不含空白；`session` 值形如
    * `dispatcher-<8hex>`，天然无空白）。 */
  def dispatcherIdleSummary(sessionId: String, idleSecs: Long, windowMs: Long): String =
    s"session=$sessionId idleSecs=$idleSecs windowMs=$windowMs"

  /** 宿主启动自动重入事件类型（boot-wake 批 2026-09-13，方案件 A 档 A1「控制面唤醒腿」）。
    *
    * 写点 = `BootDispatcherWake.record`（GatewayMain boot 链 `projectBootWake` 腿）：
    * 每 boot 每在册项目**恰一条**——含未唤醒形态（`skipped` + reason），使「零唤醒 boot」
    * 在事件流里可审计（方案 §1.4(c)/R9：此前唯一正证据只有 `mount-stalled`，其余全是
    * 「缺失的日志行」）。
    *
    * `nodeId` 字段承载**项目名**（分发器不是 Flow 节点、无 `NodeDef.id`；同
    * [[DispatcherIdleExpiredType]] 以会话 id 承载该字段的先例：此字段承载发起者标识）。
    * 不写 `chainId`（唤醒是项目级动作，不属任何链）。清单正文只进分发器首条输入与
    * `boot-wake.json`（事件行必须保持单行 `k=v`）。 */
  val DispatcherWakeType: String = "dispatcher-wake"

  /** 唤醒事件结构化 summary（`k=v` 单空格分隔，**值不含空白**——reason 内的空白
    * 归一为 `_` 并截断，防 k=v 解析被破坏）。`counts` = (nodes, B1, B2, B3, B4)。 */
  def dispatcherWakeSummary(
    bootId: String,
    atMs: Long,
    result: String,
    reason: String,
    counts: (Int, Int, Int, Int, Int),
    items: Int,
    truncated: Int
  ): String =
    val (nodes, b1, b2, b3, b4) = counts
    val r = if reason.isEmpty then "-" else reason.replaceAll("\\s+", "_").take(80)
    s"boot=${bootId.replaceAll("\\s+", "_")} at=$atMs result=$result reason=$r" +
      s" nodes=$nodes b1=$b1 b2=$b2 b3=$b3 b4=$b4 items=$items truncated=$truncated"

  /** 合并窗 FIFO 互斥闸事件类型（**mergefifo-engine 批** 2026-09-13，作者 A-4 裁决）。
    *
    * 写点 = `NodeEngine` 的 merge 互斥闸判定位（[[logMutexHold]] 与
    * `alarmSameGitDirProjects`）。两种 summary（`k=v` 单行）：
    *   - `kind=hold`：本 merge 被同键更高优先者挡住（闸停等留痕，单发=持有者集合变化时
    *     才写，禁每轮刷屏）；
    *   - `kind=same-git-dir-multi-project`：**O-1 已知缺口告警**——检测到另一在册项目
    *     与本项目**同键**（`realpath(git-common-dir)` 相等）⇒ 引擎侧持有者派生自本项目
    *     store，此形态**漏互斥**（本批不实现 claim/抢占，作者令）；只做「发生即告警」。
    * 设计件 §7.2 规划的第三种 summary（等待超预算）**引擎侧不写**：等待超预算的上报按
    * SEM-3 由 sink 自身承担（引擎不自动上报，与既有「合法等待」口径一致）。
    * `nodeId` 字段对 hold 形态承载节点 id；对相同 git 目录形态承载**项目名**（与
    * [[DispatcherWakeType]] 同款先例：该字段承载发起者标识）。不写 `chainId`。 */
  val MergeQueueType: String = "merge-queue"

  /** k=v 值归一（空白 → `_`，与 [[dispatcherWakeSummary]] 同款；防 `k=v` 解析被注释
    * 或路径中的空白破坏——真实键是绝对路径，本仓工作区含空格）。 */
  def noWs(s: String): String = s.replaceAll("\\s+", "_")

  /** `merge-queue` / hold 形态 summary：本 merge 被同键更高优先者挡住。 */
  def mergeQueueHoldSummary(where: String, holders: List[String]): String =
    s"kind=hold at=${noWs(where)} holders=${holders.mkString(",")}"

  /** `merge-queue` / 同键多项目形态 summary（O-1 告警；键按 k=v 纪律归一，原始键
    * 全文在同期 WARN 日志行里，取证走日志面）。`foreignRunning` = 他项目中此刻处于
    * running 的 merge 节点数（>0 = 真实并发争用，而非静态配置问题）。 */
  def mergeQueueSameGitDirSummary(
    key: String,
    foreign: List[String],
    foreignRunning: Int,
    mineRunning: Int
  ): String =
    s"kind=same-git-dir-multi-project key=${noWs(key)} foreign=${foreign.mkString(",")} " +
      s"foreignRunning=$foreignRunning mineRunning=$mineRunning"

  /** 归档事件结构化 summary（`k=v` 单空格分隔，值不含空白；消费者侧解析单点
    * [[parseChainSummary]] 与本函数同源，防写读口径漂移）。 */
  def chainArchivedSummary(chainId: String, archivedAt: Long, members: Int): String =
    s"chain=$chainId archivedAt=$archivedAt members=$members"

  /** 拉回事件结构化 summary（对称口径；restoredAt 与 archivedAt 同键位语义）。 */
  def chainRestoredSummary(chainId: String, restoredAt: Long, members: Int): String =
    s"chain=$chainId restoredAt=$restoredAt members=$members"

  /** 结构化 summary 解析：按空白切分取 `k=v` 对（非 `k=v` 词条丢弃）。消费者只取
    * `chain` / `archivedAt` / `restoredAt` / `members`，未知键照收不拒（向前兼容）。 */
  def parseChainSummary(summary: String): Map[String, String] =
    summary.split("\\s+").iterator
      .filter(t => t.indexOf('=') > 0)
      .map { t =>
        val i = t.indexOf('=')
        t.substring(0, i) -> t.substring(i + 1)
      }
      .toMap

  /** 追加一条审计事件。workspace 为项目工作区绝对路径；IO.blocking 隔离磁盘写。
    * dispatch-notify 批（2026-09-05）：新增事件 type `dispatch-notify`（节点终态
    * 回流分发器通知——triggered / budget-exhausted 两形态，写点在 DispatchNotify，
    * 追加式注册同 bg-wait/trigger-starved 先例）。
    * P3 归档联动批（2026-09-10）：新增可选顶层 `chainId`（默认 None = 不写该键，
    * 既有调用点零改动、旧行零迁移）；链族事件（[[ChainArchivedType]] /
    * [[ChainRestoredType]]）写入时带上，消费者免从 summary 反解析取链 id。 */
  def append(workspace: String, project: String, nodeId: String, typ: String, summary: String,
             chainId: Option[String] = None): IO[Unit] =
    // ts 求值时点修复（noderpt 批 B 段 2026-09-11，实测 `n-0931699e`）：此前
    // `System.currentTimeMillis()` 在 **IO 构造期**求值（在 `IO.blocking` 之外），
    // 事件行的 ts 因此是「构造该 IO 的时刻」而不是「真正落盘的时刻」——对
    // **延迟触发**的写入点（`bg-wait` 武装时构造、cap 到点才执行的 `bg-wait-timeout`）
    // 偏差可达整个等待窗口（实测 harvest − timeout = 7,199,951ms ≈ 1 个 cap，而
    // bg-wait 与 bg-wait-timeout 两行 ts 只差 5ms ⇒ 全部按「构造时刻」写）。任何按 ts
    // 反推「节点挂了多久」的取证都会得出错误结论。修法 = 把 ts 求值移进同一
    // `IO.blocking`（执行时刻求值，与落盘同一时刻），构造与执行分离时 ts = 真实触发
    // 时刻（≥ 执行开始时刻）。JSON 组装一并移入（零额外开销、字段集与顺序逐字不变）。
    IO.blocking {
      val ts = System.currentTimeMillis()
      val base = List(
        "ts" -> ts.asJson,
        "type" -> typ.asJson,
        "project" -> project.asJson,
        "nodeId" -> nodeId.asJson,
        "summary" -> summary.asJson
      )
      val fields = chainId.filter(_.trim.nonEmpty).map(id => base :+ ("chainId" -> id.asJson)).getOrElse(base)
      val line = Json.obj(fields*).noSpaces
      // createFolders：`.nebflow/` 缺席（未挂载工作区/测试新目录）时自建——审计写永不
      // 因目录缺失整条丢失（既有写点均在已挂载项目内，本参数对其零行为变化）。
      os.write.append(os.Path(workspace, PathUtil.dataRoot) / ".nebflow" / FileName, line + "\n", createFolders = true)
    }.void
