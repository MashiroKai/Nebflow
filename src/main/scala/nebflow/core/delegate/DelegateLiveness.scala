package nebflow.core.delegate

import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.{AgentRecord, AgentStatus}

/**
 * DelegateLiveness —— delegate 行的「真活」判定**唯一派生单点**（legacy-delegate-settle 批）。
 *
 * == 选定：单一持久字段 `DelegateRegistry.Record.restartInterruptedAt` ==
 * 盘上持久字段里只有它回答「这一行是不是被重启打断过」：
 *   - 写点单点 = 启动对账腿（`SubAgentStartupRecovery.markInterruptedRecords`，boot 恰一次、
 *     幂等；`DelegateRegistry.markRestartInterrupted` 是唯一搬手）；
 *   - 摘除单点 = 台账裁决（`DelegateLedgerReconcile`，只动这一个槽）；
 *   - 其余候选（`archivedAt` / `disposition` / 轮次 `settledAt`）回答的是**可见性**（该不该
 *     出现在哪一窗），不是活性（现在是不是还活着）——两者不可互相顶替。
 *
 * == 派生索引：[[verdictOf]] 纯函数 ==
 * 输入 = 持久记录 + **本实例** agentRegistry 快照（实例内在册面）；输出 = running | interrupted。
 * 「本实例真正收养/持有」判据 = 在册（快照含该 id）∧ 持引用（`ref != null`）∧ 处理中
 * （`status == Processing`）∧ 未被重启旗覆盖（旗缺席 ∨ 该 agent 的活动时刻晚于旗时点）。
 *
 * **零逐行 ActorSystem.resolve**：快照查表 O(1)/行，不做 ref 解析、不做消息往返。「旧引用
 * 保留在册而持有者已死」正是本判据要识别的**真实形态**——用 resolve 去探活既昂贵又回答不了
 * 「谁拥有它」；重启旗（持久字段）是唯一可离线、可复算的打断证据。
 *
 * 四条消费腿结构上共用**同一份读数、同一形状**（`live` 键是唯一载体）：
 *   - WS / REST 行投影（`ChainsReadFace.rowsJson` · `/api/chains`）；
 *   - delegate 列表/台账（store 臂 = 同一 rowsJson 的加宽面，不另造活性读点）；
 *   - flow 行（同一行体随行携带；flow 节点态是另一码事，走 `rowStatusOf`）；
 *   - 分节计数（前端 `data-bg-active-count` = 行集模型 `activeCount`，与行集同源派生）。
 *
 * == 不合并分头恢复 ==
 * 本件**零写盘、零摘旗、零 boot 改序**：SubAgentStartupRecovery（打旗）、DelegateLedgerReconcile
 * （摘旗）、SubAgentTaskStore（任务行终态）各守其职；本件只回答「这一行现在该显示什么」。
 *
 * @param aging
 *   老化腿（旗后活动时刻比较）。`false` = **关掉老化腿**，只供反向负控使用：关掉后同一条
 *   被打断行会被判 running，据此证明该腿是判据的承重件而不是装饰（见 ChainsReadFaceSpec）。
 */
object DelegateLiveness:

  /** 单行活性读数。 */
  final case class Verdict(running: Boolean):
    /** 行投影 `live` 键的**唯一形状**（WS / store / flow 行 / 分节计数四腿共用）。 */
    def json: Json = Json.obj("status" -> (if running then "running" else "interrupted").asJson)

  /** 统一非持有读数（盘上持久 Processing 但本实例不持有 ⇒ interrupted，不再是键缺席）。 */
  val Interrupted: Verdict = Verdict(running = false)

  /**
   * 判定单点：持久记录 × 本实例在册快照 → 真活读数。
   *
   * 键缺席在旧投影里等于「前端自己猜」（`rowFromRegistry` 兜底 running）——本批起读面
   * **总是**给出显式读数：持有 = running，否则 = interrupted。这是「盘上还有 Processing
   * 行就渲染成进行中」陈旧面板的根因收口。
   */
  def verdictOf(
    rec: DelegateRegistry.Record,
    agent: Option[AgentRecord],
    aging: Boolean = true
  ): Verdict =
    val held = agent.exists(a => a.ref != null && a.status == AgentStatus.Processing)
    // 老化腿：旗时点之后仍有本 agent 活动 ⇒ 旗让位（复活后确有新 turn 在跑）；否则旗覆盖。
    val superseded = aging && agent.exists(a => rec.restartInterruptedAt.exists(mark => a.lastActivityMs <= mark))
    Verdict(running = held && !superseded)

end DelegateLiveness
