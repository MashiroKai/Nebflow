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
 *   「禁止静默自愈」纪律，settle-sweep 先例同款）。
 * 注册式扩展：append API 无 schema 变更，新事件类型 = 本清单加一词 + 写入点调用。
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

  /** 追加一条审计事件。workspace 为项目工作区绝对路径；IO.blocking 隔离磁盘写。
    * dispatch-notify 批（2026-09-05）：新增事件 type `dispatch-notify`（节点终态
    * 回流分发器通知——triggered / budget-exhausted 两形态，写点在 DispatchNotify，
    * 追加式注册同 bg-wait/trigger-starved 先例）。 */
  def append(workspace: String, project: String, nodeId: String, typ: String, summary: String): IO[Unit] =
    val line = Json
      .obj(
        "ts" -> System.currentTimeMillis().asJson,
        "type" -> typ.asJson,
        "project" -> project.asJson,
        "nodeId" -> nodeId.asJson,
        "summary" -> summary.asJson
      )
      .noSpaces
    IO.blocking(os.write.append(os.Path(workspace, PathUtil.dataRoot) / ".nebflow" / FileName, line + "\n")).void
