package nebflow.core.delegate

import cats.effect.IO
import nebflow.core.project.{DelegateFlowHosts, NodeLifecycle}

/**
 * DelegateInflightGate —— delegate 作用域图的**在飞态势读数单点**（终态申报工具
 * 通道退役 · T2 批后本件只剩读面；原 finish 申报的在飞闸（P3 批
 * 2026-11-02 · 作者裁定：「finish 要是任务完成了 而不是汇报轮次完成 有在飞节点
 * 是不能接受完成的」）随申报通道整体退役——完成判定 = 回合结束 + 末条文本单通道，
 * 引擎侧不再有可被在飞节点拒付的申报动作面）。
 *
 * == 在飞口径（判据单点，附代码依据）==
 * **「在飞」= 本 delegate 自己作用域图里仍有非终态节点**。三层依据：
 *   1. **归属面**：一 delegate 一图（[[DelegateFlowHosts]] 的对象注「作用域键 =
 *      delegate 会话 id」），图里出现的每一个节点都是本 delegate 派出去的
 *      （登记面 `ChainWrite` / `NodeEdit` 全部经 `NodeTools.resolveRuntime` 的
 *      作用域闸只落自己的图）。故「作用域图非终态节点数」**就是**「我派出的工作里
 *      还没结束的部分」，无需第二判据。
 *   2. **不更宽（不含它派出的其它 delegate）**：delegate 会话结构上派不出 delegate
 *      —— `Delegate` 属 `AgentCore.RootExclusiveTools`，且执行侧 `buildAllowedToolSet`
 *      的 `nebulaFiltered` 对一切非 Nebula 身份剥离该名。子委托的生命周期不属于
 *      本读数。
 *   3. **终态定义复用单点**：非终态 = `!NodeLifecycle.Terminal.contains(status)`
 *      （`ProjectTypes.scala`：`Terminal = {completed, failed, cancelled, blocked}`）
 *      ——与 [[DelegateTerminal]] 的 `inFlightChains` 成员终态判定同源，禁第二套
 *      口径。`blocked` 归**终态**（不是「在飞」）：它是等人工裁定的停止态、不会
 *      自己再跑。
 *
 * == 消费面 ==
 *   - [[nebflow.core.delegate.BaseDriftWatch]]：冲突通知的「对端还在干活」判据；
 *   - [[nebflow.core.delegate.WorkspaceOccupancy]]：合并请求占用闸的 busy 判据
 *     （owner 有非终态作用域节点 = busy，合并请求整批拒绝）。
 *
 * == 读面纪律 ==
 * **fail-soft**：态势读不出来（宿主缺失 / 快照异常）⇒ `Reading(Nil, readable = false)`
 * ——绝不抛；消费方依 `readable` 旗标自决（占用闸 fail-closed、通知面放行，各自
 * 语义归各自消费点，本件不越权）。
 */
object DelegateInflightGate:

  /** 一个在飞节点的读数（id / name / status —— 态势呈现的三个必列字段）。 */
  final case class Inflight(id: String, name: String, status: String)

  /**
   * 态势读数：`nodes` = 非终态节点（稳定序，便于 spec 与人工复核）；
   * `readable = false` = 读面不可用（宿主缺失 / 快照异常）——放行/拦截语义由调用方
   * 依本旗标决定，本件不越权。
   */
  final case class Reading(nodes: List[Inflight], readable: Boolean):
    def inFlight: Boolean = nodes.nonEmpty

  /**
   * 读本次 delegate 作用域图的非终态节点（fail-soft：任何读面故障 ⇒
   * `Reading(Nil, readable = false)`，绝不抛）。
   *
   * 无宿主（delegate 没派任何链 / 宿主未挂载 / 已卸载）= `readable = true, nodes = Nil`
   * ——**空图是确定事实，不是读面故障**，这条区分正是「不误伤普通场景」的判据。
   */
  def read(delegateId: String): IO[Reading] =
    DelegateFlowHosts
      .get(delegateId)
      .flatMap {
        case None => IO.pure(Reading(Nil, readable = true))
        case Some(h) =>
          h.store.snapshot
            .map { s =>
              val live = s.nodes.valuesIterator
                .filterNot(n => NodeLifecycle.Terminal.contains(n.status))
                .toList
                .sortBy(n => (n.createdAt, n.id))
                .map(n => Inflight(n.id, n.name, n.status))
              Reading(live, readable = true)
            }
      }
      .handleErrorWith(_ => IO.pure(Reading(Nil, readable = false)))

end DelegateInflightGate
