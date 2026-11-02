package nebflow.core.delegate

import cats.effect.IO
import nebflow.core.project.{DelegateFlowHosts, NodeLifecycle}

import scala.util.Try

/**
 * DelegateInflightGate —— `delegate_report(finish)` 的**在飞闸**（P3 批 2026-11-02 ·
 * 作者裁定逐字：「finish 要是任务完成了 而不是汇报轮次完成 有在飞节点是不能接受
 * 完成的 需要用 system reminder 提醒 要么取消 要么 stand by 等节点完成 任务确定
 * 完成后再使用 finish」）。
 *
 * == 语义订正（本件的存在理由）==
 * 改前 `finish` 只表示「我这一轮汇报完了」：delegate 一次性 `ChainWrite` 八节点链后
 * **开工即报 finish**（#1367，09:41:15），链上节点全部照常跑；`land` 在 18:28:41 撞
 * loop-detected 失败、`gate`/`docs` 永久 pending，而 delegate 的状态槽早已写着
 * finish —— 判词序一路把它当「任务完成」。本件把 finish 拉回它应有的含义：**任务
 * 确定完成**（在飞节点为零）才可申报。拒绝是硬拒绝（工具返回 `Left`，调用失败），
 * 不是提示、不是警告、不是软性劝告。
 *
 * == 在飞口径（判据单点，附代码依据）==
 * **「在飞」= 本 delegate 自己作用域图里仍有非终态节点**。三层依据：
 *   1. **归属面**：一 delegate 一图（[[DelegateFlowHosts]] 的对象注「作用域键 =
 *      delegate 会话 id」，`DelegateFlowHosts.scala:50-62`），图里出现的每一个节点都是
 *      本 delegate 派出去的（登记面 `ChainWrite` / `AgentFlow` / `NodeEdit` 全部经
 *      `NodeTools.resolveRuntime` 的作用域闸只落自己的图）。故「作用域图非终态节点数」
 *      **就是**「我派出的工作里还没结束的部分」，无需第二判据。
 *   2. **不更宽（不含它派出的其它 delegate）**：delegate 会话结构上派不出 delegate
 *      —— `Delegate` 属 `AgentCore.RootExclusiveTools`（`AgentCore.scala:198-203`），
 *      且执行侧 `buildAllowedToolSet` 的 `nebulaFiltered` 对一切非 Nebula 身份剥离该名
 *      （`AgentSessionExecution.scala` 的 `rootFiltered` 段），挂载面再按 `delegateSession`
 *      尾段追加的只有 Node 四件 / Mail / delegate_report。`delegate_report` 本身不描述
 *      子会话的生命周期，把它塞进 finish 闸只会让闸在「我早就处置完、子委托还在跑」
 *      的合法形态下误拒。
 *   3. **终态定义复用单点**：非终态 = `!NodeLifecycle.Terminal.contains(status)`
 *      （`ProjectTypes.scala:44`：`Terminal = {completed, failed, cancelled, blocked}`）
 *      ——与状态矩阵批已经在用的同一判据（`DelegateTerminal.scala:158` 的
 *      `inFlightChains` 成员终态判定）逐字同源，禁第二套口径。
 *      `blocked` 归**终态**（不是「在飞」）：它是等人工裁定的停止态、不会自己再跑，
 *      判为在飞会让 finish 在「delegate 无权推进该节点」的形态下被无限拒。
 *
 * **与既有 `inFlightChains` 读数的差别（有意，非回归）**：既读数只统计**带链归属**的
 * 节点（`FlowMapStore.chainIdView` 分量，`DelegateTerminal.scala:154-160`），无链的孤立
 * 节点数不到。本闸取**整张作用域图**（严格超集）——finish 是真终态判断，漏一个孤立
 * 的 running/pending 节点就等于把 #1367 的形态原样放回去。既有读数与其消费方（未申报
 * 提醒腿的文案锚）零改动。
 *
 * == 防锁死（作者问「会不会把 delegate 锁死」）==
 * 不会，且不靠「在飞节点一定会自己走完」这种不可证命题：
 *   - **合法逃逸口 = 取消**，而取消是 delegate 工具面里**真实存在且对该 delegate 全部
 *     节点状态都生效**的动作：`NodeCancel`（节点级 running → 取消信号 / stale running
 *     → 直接收殓；链级 `chain:<id>` → 把全部分量非终态成员翻 cancelled，
 *     `NodeCanceller.scala:95-115, 296-315`）与 `NodeEdit(abandon=true)`（终态 /
 *     wiring / pending / 死会话 running → cancelled 摘边，
 *     `NodeTools.scala` 的 `abandonNode`）。两者都在
 *     `NodeTools.DelegateNodeToolsFace`（`NodeTools.scala:68`）并随 delegate 会话挂载。
 *   - **读面 fail-soft**：态势读不出来（宿主缺失 / 快照异常）⇒ **放行**——闸不得因
 *     自身故障把 delegate 锁在「不能 finish」。这条与「宁可少拦不可误锁」同律。
 *   - kernel 形态 delegate（极简内核）在挂载面就没有 Node 四件（连 `ChainWrite` 也没
 *     有）⇒ 它结构上派不出任何节点 ⇒ 本闸对它恒放行，零新增死锁面。
 *   - 提醒文案因此必须把「取消」写成**具体可执行的动作**（工具名 + 参数），否则那句
 *     「要么取消」是空话（作者令原文要求的就是这两条路都要真能走）。
 *
 * 消费点单点 = [[nebflow.core.tools.DelegateReportTool]] 的 `finish` 分支（申报落槽
 * 之前）。`merge` / `standby` 不受本闸约束（前者是入队等合并、后者是显式未完成）。
 */
object DelegateInflightGate:

  /** 结构化错误码（工具面 `Left` 文案前缀，机读锚）。 */
  val ErrorCode: String = "DELEGATE_REPORT_INFLIGHT"

  /** 提醒里最多逐个列出的在飞节点数（防文案淹没；余量给汇总行）。 */
  val MaxListed: Int = 20

  /** 一个在飞节点的读数（id / name / status —— 提醒文案的三个必列字段）。 */
  final case class Inflight(id: String, name: String, status: String)

  /**
   * 态势读数：`nodes` = 非终态节点（稳定序，便于 spec 与人工复核）；
   * `readable = false` = 读面不可用（宿主缺失 / 快照异常）——**放行**语义由调用方
   * 依本旗标决定，本件不越权（fail-soft 判据在 [[check]]）。
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

  /**
   * 闸本体（判据单点）：`None` = 放行；`Some(text)` = **拒绝文案全文**（结构化错误
   * 前缀 + `<system-reminder>` 块）。
   *
   * 放行条件（三条任一）：读面不可用 / 图内无非终态节点 / 无宿主。拒绝时不写任何
   * 状态槽——拒绝本身不是一次申报（调用方在 `Left` 分支零写）。
   */
  def check(delegateId: String): IO[Option[String]] =
    read(delegateId).map { r =>
      if !r.readable then None else if !r.inFlight then None else Some(refusalText(r.nodes))
    }

  /**
   * 拒绝文案（模型可见；纯函数，spec 判红锚）。三件事必须说清（作者令逐条）：
   *   ① 现在有几个节点在飞，**逐个列出 id / name / status**；
   *   ② 两条合法出路——**取消**它们（给出具体工具与参数，delegate 真能执行），或
   *      **standby** 等它们跑完；
   *   ③ **任务确定全部完成后**才能用 finish（finish ≠ 本轮汇报完毕）。
   */
  def refusalText(nodes: List[Inflight]): String =
    val listed = nodes.take(MaxListed)
    val more = nodes.size - listed.size
    val bullets = listed.map(n => s"  - ${n.id}（${n.name}，status=${n.status}）").mkString("\n") +
      (if more > 0 then s"\n  - …另有 $more 个未列出（NodeList 看全量）" else "")
    s"""delegate_report: finish REFUSED — ${nodes.size} 个节点仍在飞，任务未确定完成。$ErrorCode
       |
       |<system-reminder>
       |【finish 语义】finish = **任务确定完成**，不是「我这一轮汇报完了」。你的作用域图里还有 ${nodes.size} 个非终态节点：
       |$bullets
       |
       |你现在有两个选择（二选一，别硬报 finish）：
       |1) **取消**它们——你已经持有的工具就能干：
       |   - `NodeCancel(chain="<链号>")` 取消整条链的全部非终态成员（级联，不可逆）；
       |   - `NodeCancel(node-id="<节点 id 或 name>")` 取消单个 running 节点；
       |   - `NodeEdit(nodename="<节点 name>", abandon=true)` 放弃一个 wiring/pending 节点。
       |   放弃/取消是显式动作，本轮就要落，不要留给「以后再说」。
       |2) **stand by** 等它们跑完：`delegate_report(category="standby", detail="在等哪几个节点")`
       |   ——回合收尾不终判、不投父，节点跑完后 Mail/通知唤醒你续跑。
       |
       |**任务确定全部完成后**（图上再无非终态节点，或你把它们全部取消/放弃后）才能用
       |`delegate_report(category="finish", detail=…)`。本条拒绝没有写任何状态槽，你这一轮的
       |正文仍会作为普通输出留在会话里。
       |</system-reminder>""".stripMargin

  /** 提醒里「可执行动作」清单（文案与工具面同源校验用；spec 判红锚同源）。 */
  def offeredExits: List[String] = List("NodeCancel", "NodeEdit", "standby")

  /** 本 delegate 会话的工具面是否真带着两条取消出口（fail-soft：异常 ⇒ false）。 */
  def cancelExitsMounted: Boolean =
    Try {
      val face = nebflow.core.tools.NodeTools.DelegateNodeToolsFace
      face.contains("NodeCancel") && face.contains("NodeEdit")
    }.getOrElse(false)

end DelegateInflightGate
