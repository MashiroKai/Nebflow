package nebflow.core.flow

import nebflow.actor.{AgentKind, AgentRecord, AgentStatus}
import nebflow.core.flow.RunningFlowRegistry.RunningFlow

/**
 * #407 (2026-08-25 用户裁定 19:53) + 2026-08-28 01:00 统一裁定：queue 模式
 * Mail 的投递时机 gate。
 *
 * 用户裁定：
 *  - queue Mail 投递时机 = **目标 agent 自身**连同其子树（子 agent/flow）全空闲
 *    才投递——**无论发送者是谁**（08-28 统一裁定废除 08-25 的按发送者区分：
 *    「Nebula→Manager 等全 team 停」不再成立，Manager 忙不因兄弟成员在飞而被
 *    阻塞；全 team 停的旧语义让无关成员的忙碌无限期扣住投递）；
 *  - Q3 保留：RunningFlow.sessionId 关联（flow 在飞检测完整）；
 *  - 不做 MailQueueDrainer / force 兜底（子 agent 卡死归 TaskStuckWatcher 既有机制）。
 *
 * 纯函数（可单测）。AgentActor 负责组装运行时数据（registry 快照 / running flows）
 * 后调用。
 */
object MailIdleGate:

  /**
   * kind 白名单：只计任务型子 agent。**必须排除 Team / Root**——MailTool.activateAgent
   * 注册 Mail 激活的 team 成员时 `parentRef = 发送者 ref`（发送者视角），若不排除
   * Team kind，gate 检查发送者会误判「有下属在飞」→ 永不投递（方案 R4）。
   */
  private[flow] val taskKinds: Set[AgentKind] =
    Set(AgentKind.Delegate, AgentKind.SubTask, AgentKind.Flow, AgentKind.Ephemeral, AgentKind.Plan)

  /**
   * 规则②：目标 agent 自身子树完全空闲（team 内 queue 语义）。
   *
   * checkStatus：自身 turn 状态是否计入。默认 true——「自身正在 turn」算忙。
   * finishTurnCont 的 turn-end drain 传入 false：turn 已结束，registry 的
   * status 还是 Processing 残留（touchRegistryActivity(Idle) 在回 idle 分支
   * 才执行），此时自身不算忙，只查子树与 flow。
   *
   * 判定（§1.2 实证）：子记录存在（parentRef == self 且 kind 任务型）= 子树未收尾——
   * BackoffSupervisor.notifyParentAndStop 终态时 registry 移除与结果通知原子同步，
   * 不存在「子 agent 已完成但记录残留」的正常窗口。存在性检查即足够，无需递归展开
   * 整棵子树（MaxDepth=5 有界）。
   */
  def isAgentTreeIdle(
    sid: String,
    registry: Map[String, AgentRecord],
    runningFlows: List[RunningFlow] = Nil,
    checkStatus: Boolean = true
  ): Boolean =
    registry.get(sid).exists { rec =>
      // 显式等待停靠态（平台修复批 fix 4，2026-10-21）视同可投递：Waiting =
      // 引擎停靠（等合并轮提醒）——停靠会话的已产出报告照样走投递链，合并轮
      // Mail/续聊可达；「自身 busy」语义只对真实 turn（Processing）成立。
      //
      // 错误冻结批（2026-11-01）：`Frozen` 同样视同可投递——冻结是**停住的等待
      // 态**（监督者已停/已让窗，本会话零员工），投给它的 Mail 正是唤醒腿的载体；
      // 若把 Frozen 计成忙，冻结会话的出箱/续聊永远投不进去（冻结即失联）。
      (!checkStatus || rec.status == AgentStatus.Idle || rec.status == AgentStatus.Waiting ||
        rec.status == AgentStatus.Frozen) &&
      // 在飞子代理数：冻结监督者已自停，但记录里 outstanding 可能残留（冻结拍未清）
      // ——冻结态不看该计数（冻结 = 零在飞：child 已停），否则冻结父永远不可投递。
      (rec.status == AgentStatus.Frozen || rec.outstandingSubagents == 0) &&
      // 无在飞任务型子 agent（一阶；递归由存在性覆盖）
      !registry.values.exists(c => c.parentRef.exists(_ == rec.ref) && taskKinds.contains(c.kind)) &&
      // 无关联 running flow（Q3：RunningFlow.sessionId 关联触发者，补节点间隙窗口）
      !runningFlows.exists(f => f.status == NodeStatus.Running && f.sessionId.contains(sid))
    }
end MailIdleGate
