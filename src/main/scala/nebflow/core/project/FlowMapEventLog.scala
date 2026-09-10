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
 *   事件类型——链抽象 P2 restoreChain 拉回时索引回翻；**接口点，本批无写入点**）。
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

  /** 链拉回事件类型（对称口径，spec §6.2/§9.3：链抽象 P2 `restoreChain` 落地后由
    * 其调用点写入；**本批只定义类型 + 消费者回翻分支，无写入点**——禁止虚构调用点）。 */
  val ChainRestoredType = "chain-restored"

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
    val base = List(
      "ts" -> System.currentTimeMillis().asJson,
      "type" -> typ.asJson,
      "project" -> project.asJson,
      "nodeId" -> nodeId.asJson,
      "summary" -> summary.asJson
    )
    val fields = chainId.filter(_.trim.nonEmpty).map(id => base :+ ("chainId" -> id.asJson)).getOrElse(base)
    val line = Json.obj(fields*).noSpaces
    // createFolders：`.nebflow/` 缺席（未挂载工作区/测试新目录）时自建——审计写永不
    // 因目录缺失整条丢失（既有写点均在已挂载项目内，本参数对其零行为变化）。
    IO.blocking(os.write.append(os.Path(workspace, PathUtil.dataRoot) / ".nebflow" / FileName, line + "\n", createFolders = true)).void
