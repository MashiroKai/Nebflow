package nebflow.core.project

/** 合并节点策略（merge-node 批 20260905，方案 .nebflow/Spec/merge-node-plan.md）。
  *
  * 背景：任务节点各自在 worktree/分支产出，completed 后产物滞留——落地全靠宿主
  * 手工 merge。机制：分发器把每批任务节点的 out 多对一接到同一「合并节点」
  * （merge=true，不配 worktree），由它收口落地（--no-ff 合并 + worktree remove +
  * branch -d + 零残留复核）。
  *
  * 单一语义 home（与 RUNNING 批的冲突纪律）：本批对 NodeEngine 只留 deliverFailed
  * 一处最小挂接，全部判定/反馈构造收进本 object（纯函数，便于单测）。
  *
  * 触发语义（§3 spec）：
  *   - 全部上游 completed → 触发（既有 in-barrier 语义零改动，completed 正常结算）
  *   - 上游 blocked   → 不结算不触发（blockedNode 本就不投递）；走既有 blocked
  *                      重入协议处置该上游，合并节点原地 wiring/pending 等待
  *   - 上游 failed    → 本 object.haltsOnFailure 命中 → NodeEngine 把合并节点转
  *                      blocked 可见终态（**不做 collect 占位结算**——占位会让合
  *                      并在不完整输入上启动；也不悬挂 pending）
  *   - 上游 cancelled → 无投递（cancelNode 本就不结算）；合并节点保持
  *                      wiring/pending 可见，由分发器 NodeList 巡检处置（改接/abandon）
  */
object MergeNodePolicy:

  /** 判定：该节点是否合并节点（NodeEdit create `merge=true` 显式标记）。 */
  def isMerge(n: NodeDef): Boolean = n.merge

  /** 上游失败时该合并节点是否应转 blocked（而非 collect 占位结算）：
    * 仅合并节点且尚未启动（wiring/pending）——running/终态一律不动
    * （running 不会被失败上游触达：其启动前提是全部上游已完成投递）。 */
  def haltsOnFailure(n: NodeDef): Boolean =
    n.merge && (n.status == NodeLifecycle.Wiring || n.status == NodeLifecycle.Pending)

  /** 上游失败 → 合并节点 blockedFeedback（spec §3②）。category 沿 BlockedReader
    * 既有枚举 upstream-incomplete（前端零改动透传渲染）；blockCount **不增**——
    * 这不是节点自报的 blocked 轮次（FeedbackRouter 重入主责在失败上游侧），
    * 引擎代转的可见终态，重入由分发器对失败上游执行。 */
  def upstreamFailureFeedback(failed: NodeDef, err: String): BlockedFeedback =
    BlockedFeedback(
      category = "upstream-incomplete",
      detail = s"上游节点 '${failed.name}' (${failed.id}) failed，合并未执行。失败原因: ${err.take(300)}",
      suggestion = s"处置失败上游（修复后 NodeEdit 重激活它），然后 NodeEdit 重激活本合并节点" +
        s"（改动 task/agent 任一即触发重激活）——已完成上游结果自动重投，无需重建拓扑"
    )

  /** 合并节点 blocked 落库渲染串（与 BlockedReader.render 同构，NodeList 摘要共用）。 */
  def renderBlocked(f: BlockedFeedback): String = BlockedReader.render(f)
end MergeNodePolicy
