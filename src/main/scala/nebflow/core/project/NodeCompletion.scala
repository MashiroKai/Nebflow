/* 从 NodeEngine 迁出(行为保持重构,2026-09-25)。 */
package nebflow.core.project

import cats.effect.*
import cats.syntax.all.*
import nebflow.agent.*

private[project] trait NodeCompletion:
  self: NodeEngine =>

  // ── 终态化（§2.7）─────────────────────────────────────────
  //
  // 竞态根因修复（barrier 投递 bug 主因）：终态字段（status/result/completedAt/
  // ttlExpireAt）一律落在 mutate 事务内现读的 fresh 节点上，绝不在启动时捕获的
  // 陈旧快照上 copy 写回。运行期间 NodeTools.setOut 的接线改写（out/in 反向一致）
  // 与 deliverOut 的 deliveredTo 增量都在 fresh 上原样保留；投递沿 fresh.out。
  // 实证：n-95271231 两上游运行中被改接线 → 完成时陈旧副本回写 out=null →
  // deliverOut 沿空 out 悬空 → 下游永久 wiring；n-ab4a884f 同理回写覆盖成 Nebula。
  // 节点已不在活动区（被移除）→ 拒写拒投（陈旧写回会把它复活成垃圾行）。

  /**
   * blocked 结构化信号批（20260909 spec §5.3；同日作者裁定泛化 NodeReport
   * 统一三语义）：declared = 节点会话经 node_report 工具申报的结构化反馈
   * （NodeEngine 终态分流处 drain 登记表所得）；默认 None 向后兼容全部既有
   * 调用点。分流顺序【结构化信号优先，**按角色 × 类别分流**（nrloop 一期 2026-09-12
   * 语义轴分离：执行状态 vs 内容判定），全部锚定既有链零新链】：
   *
   *   - `finish`（执行节点显式完成）→ 既有 completed 语义链（回落 declared=None
   *     原路径：BlockedReader 降级面 → CompletionGate 闸门 → completedNode——闸门
   *     是完成链的产物完整性 owner，finish 申报不绕闸、零放宽）；
   *   - `pass`（verifier verdict）→ 记 `lastVerdict=pass` + completed 链（pass 边
   *     照常投递，verdict 感知的 deliverOut 只看 fail）；
   *   - `fail`（verifier verdict）→ **不调 `failNode`**（这正是 0911 secstore-audit
   *     误判 failed 的机制根因）：记 `lastVerdict=fail` + completed 链 + 沿
   *     `(fail)<目标>:loop` 控制边选通（`verifierFail`）；预算耗尽则熔断
   *     （`circuitBreakLoop`，verifier 终态化 failed + 计量数字）；无 fail 边
   *     （存量/畸形数据）⇒ 只记 verdict + 照常 completed，不误杀；
   *   - blocked（细分六类/泛值，两个角色共有，协议事实优先）→ 既有 blockedNode/
   *     FeedbackRouter 链（工具申报即节点对任务可完成性的正式判断，blocked 可重激活
   *     无损，completed 伪终态不可逆）。
   *
   * 纪律（设计 §3.1 三条，工具 description 同文）：**执行失败没有申报通道**——执行
   * 真的挂了仍由引擎 `failNode` 判（LLM 错误/会话死亡/LoopGuard L1），不由 agent 申报。
   */
  /**
   * 申报消费的原子/补偿对（engine-defects 批 #239①，2026-09-15）——面②「take-and-remove
   * + 先消费后写」的唯一修补点。
   *
   * **缝**（原文 `.nebflow/reports/20260915_engine-defects-impl.md:105`）：`drain` 先把申报
   * 取走并移除（[[NodeReportRegistry]] 头注「drain 即移除、重复消费不可能」），随后才写
   * 终态；而终态写族有多条**拒写**路径（节点已消失 / 状态已变＝R2 fresh-read 竞态纪律 /
   * 优雅关机期的失败写入抑制）⇒ 旧口径下**拒写即申报永久丢失、无补偿写回**——磁盘上、
   * 事件流里、内存里都没有副本。
   *
   * **修法（选 (B)，理由见报告 §2）**：终态写族回报「是否真的落地」（`completeNodeR` /
   * `blockedNodeR` / `failNodeR` / `completedNodeR` / `verifierFailR` / `circuitBreakLoopR`
   * 的 `IO[Boolean]`——它们内部**本来就**在 fresh-read 之后分「写成功 / 拒写」两支，本批只把
   * 这个既有判词向上回报，零新逻辑），消费点据此二分：
   *   · `landed = true` ⇒ 申报已由一次**真实终态写**消费（语义生效）——本函数零动作；
   *   · `landed = false` ⇒ **补偿写回**：申报全文（category / detail / suggestion）+ 拒写
   *     事实落 `FlowMapEventLog`（`node-report-unconsumed`，append-only、无 schema 变更、
   *     grep 可取回全文）+ WARN 日志。⇒「申报消失且全系统零痕迹」这一形态不再存在。
   *
   * **三条刻意不做**（每条的代价/理由）：
   *   · **不重试终态写**：拒写的判据本身就是「该节点已不是一个可写终态的 Running 实体」
   *     （或进程正在优雅关机）——重试 = 覆盖并发赢家的状态 ⇒ 违反 R2 fresh-read 纪律；
   *   · **不把申报放回登记表**：会话生命周期已尽（`cleanupRunTables` 按 sessionId 对称
   *     清理，放回只会成为永不再被消费的死槽）；Loop 腿更危险——回边**复用同一 sessionId**
   *     （`worker.sessionId` 跨轮不变），放回会让陈旧申报被**下一轮**重新消费（语义错位）；
   *   · **不改 `drain` 的 take-and-remove**（＝不选 (A) 的 peek-remove）：单次消费、无
   *     「peek 之后到 remove 之前」的重复消费窗口，登记表头注钉死的不变量原样保留。
   *
   * 边界（#239① 时的开口项，**#239② 已逐条处置**，见 `.nebflow/reports/20260915_v239b-impl.md`）：
   *   · (1) **非终态消费分支**：Loop verify 的 fail 支改挂 `setVerdictR` 落地判词 + 本补偿
   *     （未落地 ⇒ 补偿）；Loop worker 的 pass/`finish` 支改挂 [[noteNonTerminalConsumption]]
   *     审计行（无终态写可挂判词，故只做可见化）；
   *   · (2) **终态/判词写抛异常**：统一经 [[consumeReport]] 包装 ⇒ 异常路径同样补偿后重抛；
   *   · (3) `failNodeR` 的 mutate **无状态守卫**（节点存在即写 failed，含覆盖既有终态）——
   *     属**既有行为**、本批**显式列为已知边界**：它不会造成申报丢失（failed 终态是真写下了、
   *     `landed=true` 与事实一致），只影响「谁赢」的现场口径；收紧它 = 改 ~20 处 `failNode`
   *     调用点的语义，超出「最小加性」边界，另批另裁；
   *   · `setVerdictR` 之外的 `recordVerdict` / `reloopTo` 等循环控制写不在申报消费面上。
   */
  /**
   * 调用点（#239② 后）：全部 5 处申报消费点经 [[consumeReport]] 触发本函数——桥完成点、
   * Loop verify 的 pass/blocked 支、Loop worker 的 fail/blocked 支；Loop verify 的 fail 支
   * 与 Loop worker 的 forward 支按各自语义走落地判词 / 审计行。
   */
  private def compensateUnconsumedReport(
    nodeId: String,
    sessionId: String,
    declared: Option[BlockedFeedback],
    landed: Boolean
  ): IO[Unit] =
    declared match
      case Some(fb) if !landed =>
        logger.warn(
          s"Node '$nodeId' declared node_report(${fb.category}) but the terminal write REFUSED " +
            "(node vanished or left the Running state / shutdown suppression) — the declaration is compensated " +
            "into the audit log (node-report-unconsumed), not silently dropped"
        ) *>
          FlowMapEventLog.append(
            workspace,
            projectName,
            nodeId,
            NodeEngine.ReportUnconsumedEventType,
            s"node_report NOT consumed — terminal write refused (node vanished / status changed / shutdown " +
              s"suppression); session=$sessionId category=${fb.category} detail=${fb.detail} " +
              s"suggestion=${fb.suggestion}"
          )
      case _ => IO.unit

  /**
   * 申报消费的**异常安全**包装（engine-defects 批 #239② ⑤-(2)，2026-09-15）：终态写族
   * **抛异常**（≠ 拒写）时，旧口径的 `flatMap` 链当场断裂 ⇒ [[compensateUnconsumedReport]]
   * **不触发**（而 `drain` 已经把申报取走并移除，#239② 之后连盘上副本也随 consume 行消失）
   * ⇒ 「申报消失且全系统零痕迹」这一形态在**异常路径**上仍然成立——正是本批新持久化面
   * 若不自带修复就会继承的同一类静默缝。
   *
   * 两条非落地路径统一到同一补偿点：
   *   · `Right(landed)` ⇒ 既有判词口径（`true` 零动作 / `false` 补偿写回全文）；
   *   · `Left(t)` ⇒ **按「未落地」保守补偿**（全文 + 异常事实写回审计流）后**原样重抛**
   *     ——异常本身是引擎级失败事实，补偿不得把它吞掉（控制流零变化）。
   *
   * `write` 用 by-name：调用点照写 `completeNodeR(...)` 原样表达式，语义零改写。
   */
  private[project] def consumeReport(nodeId: String, sessionId: String, declared: Option[BlockedFeedback])(
    write: => IO[Boolean]
  ): IO[Boolean] =
    write.attempt.flatMap {
      case Right(landed) => compensateUnconsumedReport(nodeId, sessionId, declared, landed).as(landed)
      case Left(t) =>
        FlowMapEventLog
          .append(
            workspace,
            projectName,
            nodeId,
            NodeEngine.ReportUnconsumedEventType,
            s"kind=write-raised node_report NOT consumed — the terminal/verdict write RAISED " +
              s"(${t.getClass.getSimpleName}: ${Option(t.getMessage).getOrElse(t.toString)}); " +
              s"session=$sessionId declared=${declared.map(_.category).getOrElse("<none>")} " +
              s"detail=${declared.map(_.detail).getOrElse("")} suggestion=${declared.map(_.suggestion).getOrElse("")}"
          )
          .handleErrorWith(t2 =>
            logger.warn(
              s"could not append the write-raised compensation line for node '$nodeId' " +
                s"(${Option(t2.getMessage).getOrElse(t2.toString)})"
            )
          ) *>
          logger.warn(
            s"Node '$nodeId' declaration (${declared.map(_.category).getOrElse("<none>")}) was consumed " +
              s"but its write RAISED (${t.getClass.getSimpleName}) — compensated into node-report-unconsumed " +
              "(kind=write-raised); the exception is re-raised unchanged"
          ) *>
          IO.raiseError(t)
    }

  /**
   * 非终态消费的可见化（#239② ⑤-(1)，2026-09-15）：申报被 Loop 的**非终态**分支消费
   * （worker 的 `pass`/`finish` 申报 ⇒ 本轮产出照常进 verify 裁决；`drain` 已取走 + 日志
   * 已记 consume 行）时留一行审计——该分支**没有**终态写、也就没有落地判词可挂，但
   * 「申报被消费」这件事必须有机械痕迹，否则申报在内存与日志两处同时消失而全系统无一行
   * 说明它去了哪。`declared=None`（无申报）零动作；只有真有申报时才写（零噪音）。
   * 取证：`grep node-report-consumed <ws>/.nebflow/flow-map-events.jsonl`。
   */
  private[project] def noteNonTerminalConsumption(
    nodeId: String,
    sessionId: String,
    declared: Option[BlockedFeedback],
    branch: String
  ): IO[Unit] =
    declared match
      case Some(fb) =>
        FlowMapEventLog
          .append(
            workspace,
            projectName,
            nodeId,
            NodeEngine.ReportConsumedEventType,
            s"kind=nonterminal branch=$branch session=$sessionId category=${fb.category} " +
              s"detail=${fb.detail} suggestion=${fb.suggestion} — the declaration was consumed by a " +
              "non-terminal branch (no terminal write; the flow continues)"
          )
          .handleErrorWith(t =>
            logger.warn(
              s"could not append the non-terminal consumption line for node '$nodeId' " +
                s"(${Option(t.getMessage).getOrElse(t.toString)})"
            )
          )
      case None => IO.unit

  /**
   * 终态写「落地判词」版（#239①）：返回 `true` = 本次调用**真的**写下了终态
   * （fresh-read 守卫通过 + 落库可见），`false` = 走了拒写支（节点已消失 / 状态已变）。
   * 语义、分支、判据与修前逐字一致，只有返回值从 `Unit` 变为落地判词——
   * 消费点（[[compensateUnconsumedReport]]）据此决定是否需要补偿。
   */
  private[project] def completeNodeR(
    nodeId: String,
    resultText: String,
    declared: Option[BlockedFeedback] = None
  ): IO[Boolean] =
    declared match
      // finish 申报（执行节点显式完成）：与无申报同链走既有完成路径（降级面+闸门原样）
      case Some(fb) if nebflow.core.tools.NodeReportToolDef.isFinish(fb.category) =>
        completeNodeR(nodeId, resultText)
      // pass 申报（verifier verdict=pass）：记 lastVerdict 后走 completed 链（pass 边照投）
      case Some(fb) if nebflow.core.tools.NodeReportToolDef.isPass(fb.category) =>
        recordVerdict(nodeId, VerdictPass) *> completeNodeR(nodeId, resultText)
      // fail 申报（verifier verdict=fail）：**不再 failNode**——verdict ≠ 节点状态
      case Some(fb) if nebflow.core.tools.NodeReportToolDef.isFail(fb.category) =>
        verifierFailR(nodeId, fb, resultText)
      // blocked 申报（细分六类/泛值，协议事实优先）：工具申报即节点对任务可完成性
      // 的正式判断，blocked 可重激活无损，completed 伪终态不可逆（spec §6 语义裁定）。
      case Some(fb) => blockedNodeR(nodeId, fb, finalText = Some(resultText))
      case None =>
        // blocked 分流（设计 §1.3/§2.1）：最终输出以 BLOCKED 锚定 → blockedNode；
        // 非 BLOCKED 开头 → completeNode 原路径（产物完整性闸门 + completedNode）。
        BlockedReader.parse(resultText) match
          case Some(feedback) => blockedNodeR(nodeId, feedback)
          case None =>
            // 产物完整性闸门（audit 20260905 机制建议）：completed 出口三合法态
            // 校验（a 已提交+b commit-ready 申报+c 零改动；脏且未申报 → Reject）。
            // Reject → blockedNode 转 blocked（复用 BLOCKED 反馈协议：不走 out 投递、
            // 结果不丢弃，重入协议处置）——堵「节点自报 completed 但产物滞留」静默丢失。
            // fail-open 见 CompletionGate.check。
            store.getNode(nodeId).flatMap {
              case Some(fresh) if fresh.status == NodeLifecycle.Running =>
                CompletionGate.check(workspace, fresh.worktree, resultText, gateRunner).flatMap {
                  case CompletionGate.Pass(reason) =>
                    logger.debug(s"Node '${fresh.name}' completion gate pass: $reason")
                    completedNodeR(nodeId, resultText)
                  case CompletionGate.Reject(reason, diag) =>
                    logger.warn(s"Node '${fresh.name}' completion gate reject: $reason")
                    // U6/F 修复（2026-09-11）：闸门 Reject 转 blocked 时**必须带上原结论文本**
                    // ——旧口径只传 feedback ⇒ `blockedNode` 把 result 写成「闸门反馈」单段，
                    // 节点辛苦跑出来的结论文本（今日实测 24 分钟复核结论）被**整段替换且
                    // 不可恢复**（磁盘上再无副本）。修法 = 复用 `blockedNode` 既有的
                    // `finalText` 并列落盘能力（与 `node_report` 工具申报 BLOCKED 分支
                    // @:1970 同一机制、同一格式：`render(feedback) + "\n\n" + finalText`）：
                    // 闸门反馈在**前**（保住 `BlockedReader` 的裸 BLOCKED 锚定与重入 prompt
                    // 语义，零回归），原结论文本以空行分隔并列在**后**（一条 result 字段里
                    // 两段可各自取用，无 schema 变更、无前端改动、无新字段）。
                    // 取回原文的具体命令（U6/F 判据，<ws> = 项目工作区，<id> = 节点 id）：
                    //   python3 -c "import json;r=json.load(open('<ws>/.nebflow/flow-map.json'))\
                    //     ['nodes']['<id>']['result'];print(r.split('[original-conclusion]',1)[1])"
                    // → 打印闸门 Reject 前该节点会话产出的结论文本全文（未被闸门文本污染）。
                    blockedNodeR(
                      nodeId,
                      CompletionGate.feedback(diag),
                      finalText = Some(CompletionGate.withOriginalText(resultText))
                    )
                }
              case _ => completedNodeR(nodeId, resultText)
            }

  /**
   * completed 原路径：落库 completed + TTL → emitEvent nodeCompleted → deliverOut → settleDeps。
   * 返回值（#239①）= 落地判词：`true` = 落库可见并走完完成链；`false` = 节点已消失
   * （下方 `case None`，既有「result not persisted」WARN 支）——刻意**不吞**该支。
   */
  private def completedNodeR(nodeId: String, resultText: String): IO[Boolean] =
    for
      now <- IO(System.currentTimeMillis())
      s <- store.mutate { st =>
        st.nodes.get(nodeId) match
          case Some(fresh) =>
            st.copy(nodes =
              st.nodes.updated(
                nodeId,
                withoutReportPending(
                  fresh.copy(
                    status = NodeLifecycle.Completed,
                    result = Some(resultText),
                    completedAt = Some(now),
                    ttlExpireAt = Some(now + NodeEngine.TtlDisplayMs)
                  )
                )
              )
            )
          case None => st
      }
      landed <- s.nodes.get(nodeId) match
        case Some(completed) =>
          emitWithChain("nodeCompleted", nodeId, NodePayload.buildNodeJson(completed, now)) *>
            logger.info(s"Node '${completed.name}' completed (result ${resultText.length} chars)") *>
            deliverOut(completed, resultText) *>
            // deps 反向结算（deps 设计 §1.3）：与 deliverOut 同一完成 fiber 顺序推进
            // （现状 deliverOut 同款语义）。blocked 分流在 completeNode 入口已与
            // completed 分叉，deps 结算只挂 completed 分支尾部——blocked ∉ completed
            // 不触发；failed（failNode）/cancelled（cancelNode）不挂 settleDeps，
            // 下游保持 pending 可见（裁定③差异语义，NodeDepsSpec T5 锁定）。
            settleDeps(completed) *>
            // dispatch-notify（2026-09-05 批）：终态落库+投递+结算完成后，回流通知
            // 分发器（仅 notifyDispatcher 显式开启的节点；内部 best-effort 不上抛）。
            dispatchNotify.notifyTerminal(completed, NotifyReason.Completion) *>
            // #239① 落地判词：本条腿是 fresh-read 之后**写成功**支（落库可见 + 完成链走完）
            IO.pure(true)
        case None =>
          logger.warn(s"Node '$nodeId' vanished before completion — result not persisted") *>
            IO.pure(false)
    yield landed

  /**
   * deps 完成信号 → 触发依赖者（deps 设计 §1.3）：反向扫描活动区，对每个
   * deps 含本次完成节点、且自身非 running/终态的依赖者调 startNode——闸门在
   * startNode 内（deps 未全满足 / in 未归零都会静默返回）。满足判定是声明式
   * 状态查询（幂等、零记账）：同一上游既 in 又 deps 时，in 路径（deliverOut
   * barrier 归零）与 deps 路径（settleDeps）汇合同一个 startNode 入口，第二次
   * 调用被幂等跳过，冗余无害不重复 spawn。归档上游可触发——TTL 归档不影响
   * 「完成」事实（本函数由 completeNode 完成 fiber 调用时上游必在活动区；
   * 归档变体由 NodeEdit D1-deps 补触发路径覆盖，findNode 兜底）。
   */
  private def settleDeps(completed: NodeDef): IO[Unit] =
    // ③（chainmodel 批一）：`deps` 命中判据 = 字面 id 命中 **∪** `chain:<id>` 引用命中
    // （本次完成者在目标链成员集内）。链表按**合并集**派生一次（仅当确有链引用时才派生；
    // 零链引用 = 零派生成本，即时序与行为逐字等于改造前）。命中即触发，是否真起步仍由
    // `startNode` 的 `depsSatisfied`（全成员 completed）把关 ⇒ 逐成员完成均可触发、
    // 幂等无害。
    store.combinedNodes.flatMap { combined =>
      val chainsById =
        if !combined.valuesIterator.exists(_.deps.exists(FlowMapStore.isChainRef)) then Map.empty[String, ChainInfo]
        else FlowMapStore.topologicalChains(combined.values).map(c => c.id -> c).toMap
      def depsHit(d: NodeDef): Boolean =
        d.deps.exists { dep =>
          if FlowMapStore.isChainRef(dep) then
            chainsById.get(FlowMapStore.chainRefTarget(dep)).exists(_.memberIds.contains(completed.id))
          else dep == completed.id
        }
      store.snapshot.flatMap { s =>
        s.nodes.values
          .filter(d =>
            depsHit(d)
              && d.status != NodeLifecycle.Running
              && !NodeLifecycle.Terminal.contains(d.status)
          )
          .toList
          // fork 化（§6.1）：traverse_ 遍历体的 startNode 各自 fork——同上游 N 依赖
          // 者同时获得会话（案例 A 串行链根除），遍历 fiber 不被任何一个下游会话质押。
          .traverse_(d => forkStart(s"settle-deps -> ${d.name}(${d.id})")(startNode(d.id)))
      }
    }

  /**
   * 资格回扫（根因报告 §6.2，TtlTick 30s 驱动，ProjectActor 挂点）：对活动区
   * pending/wiring 节点做声明式启动资格重估，一次性关死「有资格但没人叫」的悬
   * 挂族（孤儿 barrier、D1 缺口、投递丢失、触发消费错位——案例 B 收口C 96min
   * 滞留的根因通道）。两步：
   *   1. 孤儿 barrier 自愈：in 中「completed+有 result+deliveredTo 未记」的上游
   *      逐个 deliverOutTo（自带 deliveredTo 去重幂等；语义 = NodeTools fix-b
   *      补投从「仅 edit 时」提升为周期性；barrier 随之归零者由其内部启动）；
   *      **判词面（原样保留）**：本腿**不做**判词感知——fail-verifier 的
   *      result 照旧进 `deliverOutTo`（既有 `deliveredTo` 记账语义逐字不变），"fail ⇒ 不
   *      拉起收口位" 由 **verdict 闸**承担（`mergeVerdictHolders` +
   *      `mergeVerdictHoldersOf`，落点② = 下方第 2 步 `qualified` 判定；出处
   *      perm-global-merge(n-9e9c385d)，`MergeVerdictGateSpec` V1–V10 覆盖）。🔴 本批
   *      曾在**本腿**加「fail-verifier 一律不补投」的挡投腿，实测**打红
   *      `MergeVerdictGateSpec.V1`**（其前提断言 = 本腿自愈确实跑了、`deliveredTo` 记全；
   *      该闸的设计口径亦明文「不改 deliveredTo 记账」）⇒ 判定为与既有闸重复且违约，
   *      **已撤除**（读数见 `.nebflow/evidence/20260914_stability-hotfix/`）。
   *      **#238 泛化（2026-09-15）后的缝合方式**：闸面（而非补投腿）扩到**全部收口位**
   *      ——本腿照旧补投、照旧把 `deliveredTo` 记全（记账契约零改动，见侦察 §4 红线：
   *      不得下沉 `deliverOutTo` 公共门），但被补投唤醒的下游若 `in ∪ deps` 含非 pass
   *      判词的 verifier ⇒ 在 `qualified`/`startNode` 收口被闸挡住 ⇒ 09-14 官网链
   *      `bpm-verify`(fail) → 非 merge 收口位 `bpm-report` 的**补投抢跑已关**；
   *   2. barrier/deps 均满足者 fork startNode（幂等；fork 化后不阻塞 tick）。
   * 资格口径与 startNode 闸门同源（非终态 + deps 全 completed + in 全归零 + 非
   * 零接线防御）——合格即应启动；同一节点连续 ≥StarvedRounds 轮合格却仍
   * pending/wiring（= fork 启动未生效，健康系统不应发生）→ trigger-starved 事
   * 件单发（附 nodeId+资格明细，防每 tick 刷屏）。
   * 留痕纪律：本轮实际补投/启动了哪些节点——INFO 一行 + FlowMapEventLog 每动作
   * 节点一条 settle-sweep 事件，禁止静默自愈。
   */
  def settleRunnableSweep(): IO[Unit] =
    for
      // crash-recovery 批：已认领待 rehydrate 的节点排除（bootRecoveryQueue 见字段注
      // 释）——资格回扫会以无 resume 的新鲜路径 fork startNode，与慢段续跑竞速会
      // 顶掉 transcript 续接语义（CAS 败者安静但恢复降级为全新重跑）。
      recovering <- bootRecoveryQueue.get
      s0 <- store.snapshot
      candidates = s0.nodes.values
        .filter(n =>
          (n.status == NodeLifecycle.Pending || n.status == NodeLifecycle.Wiring) && !recovering.contains(n.id)
        )
        .toList
      // 第 1 步：孤儿 barrier 自愈（deliverOutTo 自带 deliveredTo 去重，重复扫描幂等）
      healed <- candidates
        .traverse { n =>
          n.in
            .traverse { upId =>
              if n.deliveredTo.contains(upId) then IO.pure(None)
              else
                store.findNode(upId).flatMap {
                  case Some(up) if up.status == NodeLifecycle.Completed && up.result.exists(_.trim.nonEmpty) =>
                    deliverOutTo(up, n.id, up.result.get)
                      .as(
                        Some(n.id -> s"orphan barrier healed: redelivered completed upstream '${up.name}' (${up.id})")
                      )
                  case _ => IO.pure(None)
                }
            }
            .map(_.flatten)
        }
        .map(_.flatten)
      _ <- healed.traverse_((id, desc) => FlowMapEventLog.append(workspace, projectName, id, "settle-sweep", desc))
      // 第 2 步：重读后资格判定（补投可能已归零部分 barrier）+ fork 启动
      s1 <- store.snapshot
      actives = s1.nodes.values
        .filter(n => n.status == NodeLifecycle.Pending || n.status == NodeLifecycle.Wiring)
        .toList
      qualified <- actives.filterA { n =>
        val emptyWiring = n.status == NodeLifecycle.Wiring && n.task.isEmpty && n.in.isEmpty && n.deps.isEmpty
        // R4：带「待承接」标记的 barrier 不放行（摘除后 in 已 prune，若不设此闸，
        // barrier 会以缺轨输入正常启动并静默产出缺轨结论）。分发器承接后（NodeEdit
        // 实际变更）标记清空，此处自然放行。
        val barrierOk = !n.in.exists(up => !n.deliveredTo.contains(up)) && n.pendingSuccession.isEmpty
        if emptyWiring || !barrierOk then IO.pure(false)
        else
          depsSatisfied(n).flatMap {
            case false => IO.pure(false)
            // verdict 闸（merge-verdict-gate 批 2026-09-12，落点②/case (b)；**#238 泛化
            // 2026-09-15** 后判据对节点形态零分叉）：资格回扫不得把「in ∪ deps 上游
            // verifier 判词非 pass」的节点（merge sink 或非 merge 收口位同样）拉起。被挡者
            // 不进 qualified ⇒ 不 fork、不进 trigger-starved 记账（合法等待，不是启动失败）
            // ——节点保持 pending/wiring，verifier 重跑出 pass 后下一轮回扫自然放行
            // （每轮现读 lastVerdict，非一次性闩）。
            // mergefifo-engine 批 2026-09-13（落点②/互斥腿）：同键已有更高优先 merge 在跑
            // ⇒ 同样不进 qualified/不进 trigger-starved 记账（**合法等待 ≠ 启动失败**）；
            // 持有者进终态后本腿当轮放行 ⇒ 释放有界（TtlTick 30 s）。
            case true =>
              mergeVerdictHoldersOf(n).flatMap { vh =>
                if vh.nonEmpty then IO.pure(false)
                // 互斥闸停等留痕（本落点 = 排队节点的**周期心跳**：被挡者不进 qualified ⇒
                // 不 fork、不进 trigger-starved 记账，`merge-queue` 事件是它在事件流里
                // 唯一的持续可见面；持有者集合变化时才重写，单发防刷屏）。
                else
                  mergeMutexHoldersOf(n).flatMap { q =>
                    if q.nonEmpty then logMutexHold("settleSweep (qualified check)", n, q).as(false)
                    else IO.pure(true)
                  }
              }
          }
        end if
      }
      _ <- qualified.traverse_(n => forkStart(s"settle-sweep -> ${n.name}(${n.id})")(startNode(n.id)))
      _ <- qualified.traverse_(n =>
        FlowMapEventLog.append(
          workspace,
          projectName,
          n.id,
          "settle-sweep",
          s"qualified (deps+barrier settled, status=${n.status}) — startNode forked by settle sweep"
        )
      )
      // 饥饿记账：合格 → 计数 +1；已启动/终态/消失（不在本轮合格集）→ 移除（重新
      // 起算）；计数恰达阈值 → trigger-starved 单发（继续增长不再重复发）。
      starved <- starveRounds.modify { m =>
        val next = qualified.map(n => n.id -> (m.getOrElse(n.id, 0) + 1)).toMap
        (next, next.collect { case (id, c) if c == NodeEngine.StarvedRounds => id }.toSet)
      }
      _ <- qualified.filter(n => starved.contains(n.id)).traverse_ { n =>
        FlowMapEventLog.append(
          workspace,
          projectName,
          n.id,
          "trigger-starved",
          s"qualified but no session after ${NodeEngine.StarvedRounds} consecutive sweep rounds " +
            s"(status=${n.status}, deps=[${n.deps.mkString(",")}] all completed, " +
            s"in=[${n.in.mkString(",")}] all delivered, non-terminal) — startNode attempts not taking effect; " +
            "check detached trigger logs"
        )
      }
      // 第 3 步：挂载停滞兜底闸（mount-enforce 批 20260905，作者裁定「节点一旦挂载
      // 必须一定生效——不存在挂着但永不启动的合法形态」）：可触发点（入口=创建时 /
      // barrier=全部上游终态时）后 60s 仍 pending/wiring → mount-stalled 事件留痕
      // （含节点 id+等待原因）。补触发不另起机制——第 1/2 步既有骨架即接管（孤儿
      // barrier 自愈投递 + 资格回扫 fork 启动）；上游 failed/cancelled 卡 barrier 的
      // 形态补触发不可达，事件即其可见性载体（分发器巡检处置）。合法等待不误伤：
      // running/wiring 上游 = 可触发点未到，不判停滞（mountStallReason 单点口径）。
      // 判定与资格回扫同源快照（actives，fork 启动前）——本 tick 被补触发的节点同样
      // 先落停滞事件：事件记录的是回扫起点的停滞事实，repair 与留痕同 tick 完成，
      // 互不吞没（若用 fork 后快照，fork 翻转会赢得竞速吞掉事件）。
      nowMs <- IO(System.currentTimeMillis())
      stallChecks <- actives.traverse(n => mountStallReason(n, nowMs).map(reason => n.id -> reason))
      stalledNow = stallChecks.collect { case (id, Some(reason)) => id -> reason }
      prevStall <- stallNotified.get
      // R3 去重（取消静默死锁修复批）：本轮发射集排除「终态写点已即时告警」的节点
      // ——同一停滞期不得发两条（即时 barrier-blocked + 周期 mount-stalled）。
      prevAlerted <- barrierAlerted.get
      // 升级发射判定（engine-defects 批 #85）：①首条 = 本停滞期尚未发过（逐字保留
      // 今日行为）；②续发 = 本停滞期已发过且距上次发射 ≥ StallReNotifyMs（有界重复，
      // 把「静默死锁」变成持续可见）；③被即时 barrier 告警覆盖者不出（R3 原样）。
      stallEmits = stalledNow.flatMap { case (id, reason) =>
        if prevAlerted.contains(id) then None // R3 原样：即时 barrier 告警已覆盖本停滞期
        else
          prevStall.get(id) match
            case None => Some((id, reason, 1, nowMs)) // 首条（逐字保留今日行为）
            case Some((_, 0)) => Some((id, reason, 1, nowMs)) // 曾被 R3 抑制、现补发首条
            case Some((lastAt, n)) if nowMs - lastAt >= stallReNotifyWindowMs =>
              Some(
                (
                  id,
                  s"escalation=#${n + 1} ACTION REQUIRED — this node has now been stalled for " +
                    s"${(nowMs - lastAt) / 1000L}s since the previous alert; $reason",
                  n + 1,
                  nowMs
                )
              )
            case _ => None
      }
      // 记账**必须把「仍停滞但本轮未发射」的节点原样留在表内**（否则下一轮会把它当
      // 「首条」重发 ⇒ 每 tick 刷屏，单发纪律当场失效）；出表 = 该节点已脱离停滞集。
      stallNext = stalledNow.map { case (id, _) =>
        stallEmits.find(_._1 == id) match
          case Some((_, _, n, at)) => id -> (at, n)
          case None => id -> prevStall.getOrElse(id, (nowMs, 0))
      }.toMap
      _ <- stallNotified.set(stallNext)
      // 即时告警记账剪枝：仅保留「此刻仍被终态上游闸住」的下游（恢复即出集）。
      heldNow = actives.collect { case n if barrierHeldReason(s1.nodes, n).isDefined => n.id }.toSet
      _ <- barrierAlerted.set(prevAlerted.intersect(heldNow))
      _ <- stallEmits.traverse_ { case (id, reason, n, _) =>
        FlowMapEventLog.append(workspace, projectName, id, "mount-stalled", reason) *>
          logger.warn(s"[$projectName] node $id mount-stalled (alert #$n): $reason")
      }
      actions = healed.map(_._2) ++ qualified.map(n => s"start ${n.name}(${n.id})")
      _ <-
        if actions.nonEmpty then logger.info(s"[$projectName] settle sweep: ${actions.mkString("; ")}")
        else IO.unit
    yield ()

  /**
   * 挂载停滞判定（mount-enforce 批 §3）：Some(reason)=已过可触发点 60s 仍未触发。
   * 可触发点口径：入口节点（无 in 无 deps）自 createdAt 起；barrier 节点自全部上游
   * （in ∪ deps）到达终态起（四终态 completed/blocked/failed/cancelled 写点均落
   * completedAt——已核实）。barrier 感知（合法等待不判停滞）：
   *   - 任一上游 running/wiring = 被真实上游正确闸住（作者明示合法形态）；
   *   - 上游引用悬空（校验生效前遗留数据）= 保守不判。
   * 全部上游终态后仍 pending/wiring 超 60s = 停滞；reason 携带各上游终态明细 +
   * barrier 残缺清单，供事件流直接定位等待原因。
   */
  private def mountStallReason(n: NodeDef, now: Long): IO[Option[String]] =
    // R4：pendingSuccession（「待承接」槽位）并入上游集——被摘除的 cancelled 上游
    // 的 completedAt（= 取消时刻）因此参与可触发点 t0，使「承接等待」与其它停滞
    // 同源计时（60s 档），并让 barrier 残缺清单能点名它。
    //
    // ③（chainmodel 批一 2026-09-19）：`deps` 的 `chain:<id>` 引用按**合并集**展开为
    // 目标链成员集（判据单点；与下方 findNode 双区兜底同源）——链路满足时刻因此进入
    // t0。🔴 不可解析的链引用 = 永久不可满足 ⇒ **显式点名**（旧口径会被下方「悬空引用
    // 保守不判」吞掉 = 节点无声停等，正是「禁静默失败」要挡的形态）。
    store.combinedNodes.flatMap { combined =>
      val targets = FlowMapStore.resolveDepTargets(n.deps, combined)
      if targets.unknownChainRefs.nonEmpty then
        IO.pure(
          Some(
            s"deps carries unresolvable chain reference(s) [${targets.unknownChainRefs.map(c => s"chain:$c").mkString(",")}] — " +
              "a `chain:<id>` dependency is satisfied only by an EXISTING chain (ids come from the Flow Map `chains[]` " +
              "payload / NodeList); until the deps ref is fixed this node can never start (fail-closed, no silent settle)"
          )
        )
      else mountStallReasonOf(n, now, (n.in ++ targets.ids ++ n.pendingSuccession).distinct)
    }

  /**
   * 停滞判据体（上游 id 集已解析完毕；与 [[mountStallReason]] 同点拆出，仅为避免 ③ 的
   * 链引用解析把整段判据再缩进一层）。
   */
  private def mountStallReasonOf(n: NodeDef, now: Long, refIds: List[String]): IO[Option[String]] =
    refIds.traverse(upId => store.findNode(upId)).flatMap { ups =>
      if ups.exists(_.isEmpty) then IO.pure(None)
      else
        val us = ups.flatten
        val allTerminal = us.forall(u => NodeLifecycle.Terminal.contains(u.status))
        if !allTerminal then IO.pure(None)
        else
          // 入口节点（无上游）可触发点 = createdAt；barrier 节点 = 最晚上游终态时刻
          val t0 =
            if us.isEmpty then n.createdAt
            else us.flatMap(_.completedAt).maxOption.getOrElse(n.createdAt)
          val stalledSec = (now - t0) / 1000L
          if stalledSec <= NodeEngine.MountStalledMs / 1000L then IO.pure(None)
          else
            val upDesc =
              if us.isEmpty then "entry node (no upstreams; triggerable since creation)"
              else us.map(u => s"'${u.name}'(${u.id}):${u.status}").mkString(", ")
            val barrierDesc = n.in.filterNot(n.deliveredTo.contains) match
              case Nil => "in-barrier cleared"
              case missing => s"in-barrier undelivered=[${missing.mkString(",")}]"
            val successionDesc =
              if n.pendingSuccession.nonEmpty then
                s", awaiting handover (R4 pendingSuccession=[${n.pendingSuccession.mkString(",")}] — cancelled upstream detached; barrier held, dispatcher must hand over 承接 / rewire 改接 / abandon)"
              else ""
            // verdict 闸停等（merge-verdict-gate 批 2026-09-12；#238 泛化 2026-09-15 后
            // **节点形态中立**）：barrier 已清而节点仍 pending 的真实原因常见形态——in/deps
            // 上游 verifier 判词非 pass（其 status=completed，泛化文案会误指「terminal 上游堵
            // barrier」）。此处点名闸因与当下 verdict，免分发器把「机制挡住的合法等待」误判为
            // 引擎故障。零新事件类型（复用既有 mount-stalled 单发档位）。
            val gateDesc = mergeVerdictHolders(us) match
              case Nil => ""
              case held =>
                s", verdict gate held: in/deps upstream verifier(s) [${held.map(u => s"'${u.name}'(${u.id}):lastVerdict=${u.lastVerdict.getOrElse("none")}").mkString(", ")}]" +
                  " not pass — this node must not start until that verifier re-runs to pass (mechanism guarantee, not a " +
                  "stall; a non-pass verdict is never handed on as a positive result — the fail route is the " +
                  "'(fail)<target>:loop' control edge)"
            // merge 互斥闸停等（mergefifo-engine 批 2026-09-13）：同键（本项目 git 目录）
            // 已有更高优先 merge 在跑 ⇒ 本 merge 是**排队中的合法等待**，不是引擎故障。
            // 文案给持有者 id/status + FIFO 次序说明，免分发器误判（零新事件类型——复用
            // 既有 mount-stalled 单发档位；闸自身的 `merge-queue` 事件另在闸落点单发）。
            mergeMutexHoldersOf(n).map { queued =>
              // engine-defects 批 #2/#85（2026-09-15）：把「谁**在**临界区」与「谁只是
              // 排在前面」分开点名——旧文案对**开态排队者**也写「hold the critical
              // section … mechanism guarantee, not a stall」；真身 `flow-map-events.jsonl:5581`
              // 里两个持有者**都是 `wiring`**（无一在临界区），该断言当场为假，且那个
              // 队头正被 R4 `pendingSuccession` 永久 hold ⇒「机制的保证」不成立。
              val inSection = queued.filter(_.status == NodeLifecycle.Running)
              val queuedAhead = queued.filter(_.status != NodeLifecycle.Running)
              val queueDesc =
                if queued.isEmpty then ""
                else
                  val sectionPart =
                    if inSection.nonEmpty then
                      s"critical-section holder(s) [${inSection.map(h => s"'${h.name}'(${h.id}):${h.status}").mkString(", ")}]"
                    else s"NO holder is inside the critical section (every listed node is still open: none is running)"
                  val aheadPart =
                    if queuedAhead.isEmpty then ""
                    else
                      s"; queued ahead (not in the section) [${queuedAhead.map(h => s"'${h.name}'(${h.id}):${h.status}").mkString(", ")}]"
                  val guarantee =
                    if inSection.nonEmpty then "the running holder's terminal write releases it (mechanism guarantee)"
                    else
                      "no running holder exists to release it — the queue advances only when an open-state " +
                        "predecessor actually starts, which may require dispatcher intervention"
                  s", merge-queue held: $sectionPart$aheadPart — this node starts only in FIFO order " +
                    s"(rank = readyAt,createdAt,id); $guarantee"
              Some(
                s"mount stalled: ${stalledSec}s past triggerable point, still status=${n.status}, " +
                  s"$barrierDesc$successionDesc$gateDesc$queueDesc, no running/wiring upstream (upstreams: $upDesc) — settle sweep " +
                  "takeover attempted; if still stuck a terminal (failed/cancelled) upstream is blocking " +
                  "the barrier — dispatcher intervention required"
              )
            }
          end if
        end if
    }

  /**
   * blocked 终态化（设计 §2.1 四条动作序列，与 completeNode 同构）：
   * ① 事务内现读 fresh（R2 纪律）→ status=Blocked / result=渲染串 / blockedFeedback /
   *    blockCount+1 / completedAt=now / ttlExpireAt=None（永不过期=待办语义 §1.4）；
   *    节点已消失/状态已变（NodeEdit 重激活竞态）→ 拒写。
   * ② emitEvent nodeUpdated（复用现有 WS 类型 + NodePayload 同构载荷，不加新事件类型）。
   * ③ 不结算下游（out=节点不做 barrier 占位投递——传播停止是特性；下游 pending
   *    由重入分发器 NodeList 可见并处置）。
   * ④ 调 FeedbackRouter（重入 / 升级 / 频率保护决策）。
   *
   * finalText（blocked 结构化信号批 20260909 spec §5.2 #6）：工具申报通道传入
   * 节点最终全文——result 落库 = 渲染串头部 + 全文拼接；内存 Ref/投递链持有
   * 全文，落盘时 FlowMapStore persist 拆分（results/<nodeId>.md 全文 + JSON
   * ≤500 字符摘要，头部恰为渲染串）观测面信息不丢。文本锚定路径不传
   * （申报即全文，渲染串落库现状维持）。
   */
  /**
   * blocked 终态化「落地判词」版（#239①）：返回 `true` = 本次**真的**写下了 blocked
   * （fresh-read 守卫通过 + 落库可见）；`false` = 走了两条拒写支之一（节点已消失 /
   * 状态已变）。判据本身是既有代码（下方 mutate 的 `case _ => st` 守卫 + fresh-read
   * 复核支），本批只把既有判词向上回报，零新判据。
   */
  private[project] def blockedNodeR(
    nodeId: String,
    feedback: BlockedFeedback,
    finalText: Option[String] = None
  ): IO[Boolean] =
    for
      now <- IO(System.currentTimeMillis())
      s <- store.mutate { st =>
        st.nodes.get(nodeId) match
          case Some(fresh) if fresh.status == NodeLifecycle.Running =>
            st.copy(nodes =
              st.nodes.updated(
                nodeId,
                withoutReportPending(
                  fresh.copy(
                    status = NodeLifecycle.Blocked,
                    result = Some(BlockedReader.render(feedback) + finalText.fold("")("\n\n" + _)),
                    blockedFeedback = Some(feedback),
                    blockCount = fresh.blockCount + 1,
                    completedAt = Some(now),
                    ttlExpireAt = None
                  )
                )
              )
            )
          case _ => st // 节点已消失 / 状态已变 → 拒写（R2 竞态纪律）
      }
      landed <- s.nodes.get(nodeId) match
        case Some(bn) if bn.status == NodeLifecycle.Blocked =>
          val summary = s"round ${bn.blockCount}: [${feedback.category}] ${feedback.detail.take(160)}"
          emitWithChain("nodeUpdated", nodeId, NodePayload.buildNodeJson(bn, now)) *>
            logger.warn(s"Node '${bn.name}' blocked — $summary") *>
            FlowMapEventLog.append(workspace, projectName, nodeId, "blocked", summary) *>
            // ③ blocked 出口的销毁窗口登记（noderpt 批 B 段：与桥终态四出口**口径一致**
            // ——终态时刻只登记、到点由 `sweepDestroyWindows` 收殓；blocked 节点在窗口内
            // 仍可被 reactivate，届时 `withdrawDestroyWindow` / 翻转点清零撤销窗口）。
            scheduleDestroy(bn.id, destroyTargetSessions(bn), "blocked") *>
            feedbackRouter.route(bn, feedback) *>
            IO.pure(true)
        case Some(other) =>
          logger.info(
            s"Node '$nodeId' state changed to '${other.status}' before blocked finalize — refused (fresh-read discipline)"
          ) *>
            IO.pure(false)
        case None =>
          logger.warn(s"Node '$nodeId' vanished before blocked finalize — feedback not persisted") *>
            IO.pure(false)
    yield landed

  /**
   * `IO[Unit]` 门面（#239①）：文本锚定等**无申报消费**的既有调用点零改动。
   * 申报消费点一律直接用 [[blockedNodeR]] 的落地判词。
   */
  private[project] def blockedNode(
    nodeId: String,
    feedback: BlockedFeedback,
    finalText: Option[String] = None
  ): IO[Unit] =
    blockedNodeR(nodeId, feedback, finalText).void

  /**
   * `IO[Unit]` 门面（#239①）：`failNode` 的既有 ~20 处调用点（boot sweep / 启动失败 /
   * Loop 各腿 / 看门狗 / 取消链）零改动——它们的失败面与申报消费无关；
   * [[failNodeR]] 的落地判词只被申报消费点与 `circuitBreakLoopR` 取用。
   */
  private[project] def failNode(nodeId: String, err: String): IO[Unit] =
    failNodeR(nodeId, err).void

  /**
   * failed 终态化「落地判词」版（#239①）：返回 `true` = 本次真的写下了 failed；
   * `false` = 两条拒写支——① 优雅关机期 draining 抑制（节点保持 Running 交 boot
   * sweep）；② 节点已消失（既有「error not persisted」WARN 支）。
   */
  private[project] def failNodeR(nodeId: String, err: String): IO[Boolean] =
    // ── draining 守卫（中断恢复语义批 2026-09-13，spec §2.3-3）────────────────
    // 优雅关机窗口内，abort 钩子（GracefulInterruptHook 第 3 腿 / ShutdownAbort）
    // 会让每个在飞 agent turn 以「真实失败」形态回落本函数——那是「进程要死了」，
    // 不是「节点干砸了」。置位时**拒绝写 failed**（节点已被钩子翻成 interrupted，
    // 或停留 Running 交 boot sweep）+ **拒绝 deliverFailed**（零失败通知、零 D5
    // 结算、零分发器噪音），WARN + 事件留痕后返回。
    // 顺序保证：钩子先置 draining 再 abort ⇒ 不存在「失败链先落 failed」的窗口；
    // 竞态面：draining 置位前已自然失败并落 failed 的节点 → 钩子的 CAS fresh 守卫
    // 自动跳过（合法 failed 保留 D5 语义与通知，未及发出的通知由重启后
    // DispatchNotify.redeliver 补投）。回滚开关 false ⇒ draining 永不置位 ⇒ 本守卫
    // 结构性失效（现行为逐字节）。
    if ShutdownState.draining then
      FlowMapEventLog.append(
        workspace,
        projectName,
        nodeId,
        NodeEngine.InterruptedEventType,
        s"failed write suppressed while draining (graceful shutdown; the node is interrupted/awaits boot recovery): ${err.take(200)}"
      ) *>
        logger.warn(s"Node $nodeId failure suppressed while draining (graceful shutdown): ${err.take(200)}") *>
        // #239① 落地判词：draining 抑制 = **没有**写下 failed（节点保持 Running 交
        // boot sweep）⇒ false，申报消费点据此补偿（关机窗口内的申报不再静默蒸发）
        IO.pure(false)
    else
      for
        now <- IO(System.currentTimeMillis())
        s <- store.mutate { st =>
          st.nodes.get(nodeId) match
            case Some(fresh) =>
              st.copy(nodes =
                st.nodes.updated(
                  nodeId,
                  withoutReportPending(
                    fresh.copy(
                      status = NodeLifecycle.Failed,
                      result = Some(err),
                      completedAt = Some(now),
                      // 2026-09-07 作者裁定：failed 无 TTL 强制清——死亡现场保留待上层裁决。
                      ttlExpireAt = None
                    )
                  )
                )
              )
            case None => st
        }
        landed <- s.nodes.get(nodeId) match
          case Some(failed) =>
            emitWithChain("nodeUpdated", nodeId, NodePayload.buildNodeJson(failed, now)) *>
              logger.warn(s"Node '${failed.name}' failed: ${err.take(200)}") *>
              // 失败投递（§2.7 + D5 零结算）：out=Nebula → failed 消息；out=节点 →
              // 下游停等零结算（merge 例外转 blocked），停等等待者经尾部通知告知分发器。
              deliverFailed(failed, err) *>
              // R3（取消静默死锁修复批）：failed 侧**唯一**新增行为 = 终态写点同步的
              // barrier 即时告警（作者硬约束：failed 一栏只多 R3 的 barrier 检查，无摘除/
              // 无结算改动——deliverFailed 的 D5 零结算语义逐字不变）。
              checkBarriersNow(failed.id, cause = "failed") *>
              IO.pure(true)
          case None =>
            logger.warn(s"Node '$nodeId' vanished before failure finalize — error not persisted") *>
              IO.pure(false)
      yield landed

  /**
   * cancelled 终态化（**取消静默死锁修复批 2026-09-10 重写**——此前只写 status/
   * completedAt/ttlExpireAt，四条出口全截断：无 result / 无事件 / 不结算 / 不通知）：
   *
   *   ① **R2 原因落盘**：`result = Some("cancelled[source=…]: reason=…")`。此前
   *      reason 只作为内存字符串（桥 `FailOutcome`）存在、在桥后被 `contains("cancelled")`
   *      布尔嗅探，随后被丢弃——**取消原因在全系统零落盘**（设计 §1.2 差异点）。
   *   ② **R2 审计留痕**：`FlowMapEventLog` 新增 `cancelled` 事件（含 source + reason
   *      + 摘除目标）。此前唯一留痕是 `bg-harvest` 那行**无原因**。
   *   ③ **R4 自动摘除 + 「待承接」标记**（`detach = true` 默认）：把本节点 out 改接
   *      Nebula（= 今天人工 `NodeEdit(out=[Nebula])` 的自动化；D5 对 cancelled 的既有
   *      指引就是「从 barrier 摘除」——**不是**零结果结算）+ prune 下游 in 镜像 +
   *      给下游打 `pendingSuccession` 标（barrier 不被以缺轨输入自动触发成缺轨结论）。
   *      `detach = false` = **L3 硬恢复路径专用**（bridge Cancelled → 5s → resume）：
   *      该路径**永不摘除**——resume 成功后拓扑必须完整（否则下游 in 已被 prune、
   *      永远拿不到该节点结果）；resume 失败则 R5 方案 4 把节点改判 `failed`
   *      （[[settleFailedHardResume]]，**也不摘除**：R4 方案 3「failed 也自动摘」已
   *      被永久拒绝，见 [[detachCancelledUpstream]] 头注）。故 L3 路径的 out 边与
   *      下游 in 镜像自始至终原样保留，barrier 靠 D5 零结算停等而非摘除化解。
   *   ④ **R3 即时 barrier 告警**（终态写点同步，0 延迟；周期回扫降为兜底，见
   *      [[checkBarriersNow]] 的去重口径）。
   *   ⑤ **R1 回流分发器**：`notifyTerminal(_, Cancelled)`（不查 notifyDispatcher flag，
   *      与 failed 完全对称；账务与通知文本见 DispatchNotify）。
   *
   * 幂等：detach 幂等（out 已无节点目标即零写）、notify 由 notifySentAt 去重、
   * barrier 告警由即时/回扫共享单发记账去重——重复调用不产生重复副作用。
   * 三口复用（NodeCancel 桥 / dead-session reap / TaskStuckWatcher 取消）：
   * `reason`/`source` 由调用方按 [[CancelSource]] 口径给出（R7 两态）。
   *
   * **`notify = false`（R5 方案 4，2026-09-10 裁定）**：与 `detach = false` 同一
   * 调用点（L3 硬恢复路径）——该路径的 Cancelled 只是**中间态**（bridge Cancelled
   * → 5s → resume），终局由 resume 结果决定，故推迟**回流**而非「不发」：
   *   - 这里不发 Cancelled 通知，改以 [[DispatchNotify.holdTerminalNotify]] 占位
   *     （抑制 30s 补投扫描在 5s 窗口内把中间态当终态回流——见该方法注释）；
   *   - resume 成功 → 节点复活，占位由 `hardResumeNode` 的 CAS 归还，其真实终态
   *     照常回流；
   *   - resume 失败 → [[settleFailedHardResume]] 归还占位并改判 failed（failed
   *     语义回流一次）。
   *   ⇒ 对外可见面恰好一次、语义与终局一致（既不双份 cancelled+failed，也不零回流）。
   * 该参数**只**影响回流时点，不动 ①②③④ 任何行为，也不动 failed 侧（D5 零结算
   * 与 `deliverFailed` 本体逐字不变）。
   */
  private[project] def cancelNode(
    nodeId: String,
    reason: String,
    source: CancelSource,
    detach: Boolean = true,
    notify: Boolean = true,
    emitNotify: Boolean = true,
    suppressTargets: Set[String] = Set.empty
  ): IO[Unit] =
    val rendered = s"cancelled[source=${CancelSource.code(source)}]: reason=$reason"
    for
      now <- IO(System.currentTimeMillis())
      detached <- if detach then detachCancelledUpstream(nodeId, suppressTargets) else IO.pure(Nil)
      s <- store.mutate { st =>
        st.nodes.get(nodeId) match
          case Some(fresh) =>
            st.copy(nodes =
              st.nodes.updated(
                nodeId,
                withoutReportPending(
                  fresh.copy(
                    status = NodeLifecycle.Cancelled,
                    result = Some(rendered), // R2：取消原因落盘（此前 cancelled result 恒空）
                    completedAt = Some(now),
                    // 2026-09-07 作者裁定：cancelled 无 TTL 强制清——保留主图待上层处置。
                    ttlExpireAt = None
                  )
                )
              )
            )
          case None => st
      }
      _ <- s.nodes.get(nodeId) match
        case Some(cancelled) =>
          emitWithChain("nodeUpdated", nodeId, NodePayload.buildNodeJson(cancelled, now)) *>
            logger.info(
              s"Node '${cancelled.name}' cancelled [source=${CancelSource.code(source)}]: ${reason.take(200)}"
            ) *>
            FlowMapEventLog.append(
              workspace,
              projectName,
              nodeId,
              "cancelled",
              s"node cancelled [source=${CancelSource.code(source)}]: ${reason.take(220)}" +
                (if detached.nonEmpty then
                   s" — out detached to Nebula; successors awaiting handover: ${detached.mkString(",")}"
                 else "")
            ) *>
            // P2 G11（spec §3.4）：cancelled 级联清理该会话 pending asks——来源死亡
            // 即关闭（hub 移槽 + askUserClosed 广播），卡片不再僵尸常挂。
            cleanupPendingAsks(cancelled.sessionRef)
        case None =>
          logger.warn(s"Node '$nodeId' vanished before cancel finalize — skipped")
      _ <- checkBarriersNow(nodeId, cause = "cancelled") // R3 即时告警
      _ <-
        // R1 回流（notify=true）；L3 路径（notify=false）改以占位推迟——见方法头注
        // 「notify = false」段（中间态不是终局，终局腿负责真实回流）。
        // chaincancel 批（R2 §2.3）：`emitNotify=false` = **聚合通知腿**的成员（链级 /
        // 级联）——逐节点回流由链级腿单次收口（该腿先写全成员 `notifySentAt`，
        // 故逐节点腿即使跑到也结构性不发）。**只影响本通知分支**：状态写、摘除、
        // 审计事件、barrier 检查、L3 占位（notify=false 分支）全部逐字不变。
        if !notify then dispatchNotify.holdTerminalNotify(nodeId)
        else if emitNotify then
          s.nodes.get(nodeId).traverse_(n => dispatchNotify.notifyTerminal(n, NotifyReason.Cancelled))
        else IO.unit
    yield ()

    end for

  end cancelNode

  /**
   * R4 自动摘除（取消静默死锁修复批）：被取消节点的 out 改接 Nebula + 受影响下游的
   * in 镜像 prune + 下游登记 `pendingSuccession`（「待承接」）。
   *
   * 语义与 `NodeTools.setOut` 的 removed 侧 prune 同源（`in.filterNot(_ == fromId)`，
   * 设计 §5-Q1 实证的人工原语），但写点在引擎内部（工具层的 NodeEdit 是分发器动作，
   * 不经此路径）。**不产生任何结算/投递**——摘除 = 该边不存在，既非零结算也非占位
   * 投递，因此与 D5 的 failed 零结算纪律不冲突（R4 硬约束：failed 侧零改动）。
   *
   * 幂等：out 已无节点目标（重复调用 / 本就悬空）→ 返回 Nil 且零写。
   * 返回被 prune 的下游 id 集（供审计事件与 R1 通知文本使用）。
   *
   * **U5/E 修复（2026-09-11）**：目标集从「纯前向 out 遍历」扩为
   * `前向目标 ∪ 反向引用方`（[[reversePruneReferences]] 的判据，本方法内联同一事务
   * 计算）。原因：`out` 已被改接 Nebula 而下游 `in` 镜像仍引用本节点的**不一致拓扑**
   * 下，前向遍历恒空 ⇒ 旧口径 `targets.isEmpty` 静默零操作，下游 barrier 无人解救
   * （也不会有任何留痕）。反向引用集在活动区恒可算，恰好覆盖该形态。
   * **零行为漂移保证**：镜像边一致（常态）时反向集 ⊆ 前向集，`distinct` 后结果集与
   * 元素顺序均与旧口径**逐字相同**——本改动只在不一致拓扑下新增 prune。
   *
   * 可见性（R4 验收项「自动摘除 + 标记可见」）：prune 生效时对每个受影响下游补发
   * `nodeUpdated`（payload 走 NodePayload 条件字段 `pendingSuccession`，前端卡片/
   * 分发器 NodeList 同一序列化点）——与 NodeTools.emitWiringUpdates 同款：标记若只
   * 落盘不推帧，前端要等下一次全量快照才见。幂等（无 prune → 零帧）。
   *
   * `case None`（节点不在活动区）保持旧口径 `(s, Nil)`：调用方 [[cancelNode]] 的
   * `store.mutate` 同样查无该节点 ⇒ 整个取消是 no-op 且已有 `Node nodeId vanished
   * before cancel finalize` 响亮留痕（非静默）；离线节点的迟到摘除由 L3 失败腿的
   * [[lateDetachUnlocatableSession]]（含归档区解析）承担，不在本方法范围内。
   *
   * **`cancelloopfix` 批（2026-09-17，作者裁定 #675(a) / 跟踪卡 #697）**：**目标集排除
   * `:loop` 回边目标**——与方案 A / 判据 M9（[[referencesOf]] 头注：`:loop` 不作级联
   * 传导）一致化：**不连坐 ⇒ 便签也不该有**。排除面 = **前向扫描跳过
   * `OutEdge.isLoopEdge`**（`from.out` 侧），与 [[referencesOf]] 的传导排除面**同款形态、
   * 不同位点**：那里管**传导**（谁被级联取消），这里管**打标**（谁收「待承接」便签）。
   * 反向腿（`in` 镜像）**无需过滤**——回边**从不写 `in` 镜像**（三处写点同款过滤：
   * `NodeTools.setOut` 的 rewire 两侧、`NodeTools` create 的 `ins`/`out` 两支），
   * ⇒ 回边目标天然不出现在 `reverseOnly` 里。
   * 生效面：回边目标不再收 `pendingSuccession` 便签、不再收那 1 帧 `nodeUpdated`，
   * 也不进 `cancelled` 审计的「successors awaiting handover」栏；**连带面**（本批声明，
   * 见批报告 ⑥/⑨）＝ 被取消节点 out 若**只剩回边**（无其它节点目标）则 `targets` 为空
   * ⇒ 早退零写 ⇒ 其 `:loop` 声明边保留（不再改接 Nebula）。
   */
  private def detachCancelledUpstream(nodeId: String, suppressTargets: Set[String] = Set.empty): IO[List[String]] =
    // R2/R3（chaincancel 批 2026-09-17，**取代面**）：`suppressTargets` = 本次操作
    // **即将取消**的成员集（作者三答 1「取消即级联，不再登记后继位」）——对它们**不**
    // 追加 `pendingSuccession`（同一操作内「打标 + 紧接取消」自相矛盾，且会在终态节点
    // 上留噪标；判据 M3）。`cascadeCancelledIds`（见其定义）是**跨调用**的同一判据：
    // 级联腿发信号取消的 running 成员，其终态由**既有的桥腿**异步落盘，届时本方法
    // 由默认参数（suppressTargets = ∅）进入——若无该集，已取消下游会被补打噪标。
    // 🔴 默认值 ∅ 且该集为空时 ⇒ `NodeCancel` 工具 / 面板会话键等既有单节点腿
    // **逐字零改动**（Z4；判据 M11「保留面」）。
    cascadeCancelledIds.get.flatMap { suppressedByCascade =>
      store
        .mutateWithResult { s =>
          s.nodes.get(nodeId) match
            case Some(from) =>
              // #675(a)（cancelloopfix 批 2026-09-17）：回边**不作打标目标**——`:loop` 不作
              // 级联传导（M9）⇒ 不连坐 ⇒ 便签也不该有（现态「同一操作内打标 + 紧接取消」
              // 自相矛盾、在已取消节点上留噪标）。排除面 = 前向扫描跳过 `OutEdge.isLoopEdge`；
              // 反向腿**无需过滤**（回边从不写 `in` 镜像，见 NodeTools.setOut / create 三处写点）。
              // 🔴 与 [[referencesOf]] 的传导排除面同款形态、**不同位点**（那里管传导，这里管打标）。
              val forward = from.out
                .filterNot(OutEdge.isLoopEdge)
                .map(_.to)
                .filterNot(_ == OutEdge.NebulaTarget)
                .distinct
                .flatMap(OutEdge.resolveTargetId(s.nodes, _))
                .distinct
              // U5/E：反向引用方（谁还在 in 里引用我）——不一致拓扑下前向遍历恒空，反向恒可算。
              val reverseOnly =
                s.nodes.values.filter(n => n.id != nodeId && n.in.contains(nodeId)).map(_.id).toList.sorted
              val targets = (forward ++ reverseOnly).distinct
              if targets.isEmpty then (s, Nil)
              else
                val pruned = targets.foldLeft(s.nodes) { (acc, tid) =>
                  acc.get(tid) match
                    case Some(tn) =>
                      val alreadySuppressed = suppressTargets.contains(tid) || suppressedByCascade.contains(tid)
                      acc.updated(
                        tid,
                        tn.copy(
                          in = tn.in.filterNot(_ == nodeId),
                          pendingSuccession =
                            if alreadySuppressed then tn.pendingSuccession
                            else (tn.pendingSuccession :+ nodeId).distinct
                        )
                      )
                    case None => acc
                }
                (s.copy(nodes = pruned.updated(nodeId, from.copy(out = List(OutEdge.nebula)))), targets)
              end if
            case None => (s, Nil)
        }
        .flatMap { case (_, pruned) =>
          pruned
            .foldLeft(IO.unit) { (acc, tid) =>
              acc >> store.getNode(tid).flatMap {
                case Some(n) => emitUpdated(n)
                case None => IO.unit
              }
            }
            .as(pruned)
        }
    }

  /**
   * **级联传导引用并集单点**（R3，chaincancel 批 2026-09-17 —— 作者三答 3 逐字落地）。
   *
   * 判据 = **前向 ∪ 反向**（与 U5/E 修复逐字同源的口径）：
   *   - 前向：`from.out` 逐边 `OutEdge.resolveTargetId`（跳 `Nebula`）；
   *   - 反向：活动区里 `n.in ∋ id`（下游 in 镜像）/ `n.deps ∋ id`（依赖轨）/
   *     `n.pendingSuccession ∋ id`（R-3 已登记的「待承接」槽位——否则该槽位永闸死）/
   *     `n.out → id`（R-4「上游仍引用我」，含已 completed 上游的 pass 边粘住形态）。
   *
   * 🔴 **`:loop` 回边不作传导边**（作者三答 3，与设计 §3.1 R-4「含 `:loop`」不同）：
   *   - 为何排除：verifier 的 `(fail)<worker>:loop` 只是**返工信号**，不是拓扑依赖——
   *     取消 verify 时若不排除，级联会经回边**回烧 worker**（把独立的执行者一起判死）；
   *   - 排除面 = **两条方向的 out 扫描**都跳过 `OutEdge.isLoopEdge` 的边；
   *   - **不变的部分**：邻接/分量归属仍按 `FlowMapStore.topologicalChains:1069`
   *     **含回边**（弱连通分量本就是无向的，回边不破坏链归属）⇒ 「同一条链」
   *     的成员解析口径零变化，只有**传导方向**排除（判据 M9；变异 = 把 `:loop`
   *     加回传导 ⇒ M9 必红）。
   *
   * 🔴 **`retry.upstream` 显式排除**（设计 §3.1 R-5 不变量）：`retry.upstream` 恒为
   *   in/deps 邻居（`ProjectTypes` 的 `NODE_RETRY_NEIGHBOR` 创建期硬拒）⇒ 已被 R-1/R-2
   *   覆盖；**日后若 retry 语义放宽到可跨子图回跳，必须显式加入本并集并重开
   *   「跨分量禁行」判定**（设计 §3.2 的例外声明）。
   *
   * 纯函数（只读快照，无 IO、无写面）——调用方在同一事务/同一快照上取判据，
   * 防「派生两次必然漂移」。
   */
  private[project] def referencesOf(snapshot: FlowMapState, nodeId: String): Set[String] =
    val nodes = snapshot.nodes
    nodes.get(nodeId) match
      case None => Set.empty
      case Some(from) =>
        val forward = from.out.iterator
          .filterNot(OutEdge.isLoopEdge) // 三答 3：:loop 回边不作传导边
          .filterNot(_.to == OutEdge.NebulaTarget)
          .flatMap(e => OutEdge.resolveTargetId(nodes, e.to))
          .filter(_ != nodeId)
          .toSet
        // ③（chainmodel 批一 2026-09-19）：`chain:<id>` 引用的**反向传导**——目标链成员被
        // 取消/退役时，引用整链的下游同款入闭包（与字面 deps 引用同判据；否则「等整链」的
        // 下游在成员取消后只是静止停等，取消语义与字面引用不一致）。零链引用时零派生成本
        // （短路返回空表，逐字等于改造前行为）。
        // 🔴 **登记（口径变化，禁默认一致）**：链引用是**非图引用**——`deps` 自本批起不再是
        // 分量边、`chain:<id>` 更不是节点 id ⇒ `cascadeClosure` 头注「存在引用关系 ⇒ 必同
        // 分量」对链引用不成立（该头注已同步登记）。跨分量传导在此形态下是**有意保留**的：
        // 判据面 = 「与我取消集有依赖引用」，与分量归属解耦（归属面重构不应连带改取消面）。
        val chainsById =
          if !nodes.valuesIterator.exists(_.deps.exists(FlowMapStore.isChainRef)) then Map.empty[String, ChainInfo]
          else FlowMapStore.topologicalChains(nodes.values).map(c => c.id -> c).toMap
        def depsRefers(n: NodeDef, target: String): Boolean =
          n.deps.exists { dep =>
            if FlowMapStore.isChainRef(dep) then
              chainsById.get(FlowMapStore.chainRefTarget(dep)).exists(_.memberIds.contains(target))
            else dep == target
          }
        val reverse = nodes.values.iterator
          .filter(_.id != nodeId)
          .filter { n =>
            n.in.contains(nodeId) || depsRefers(n, nodeId) || n.pendingSuccession.contains(nodeId) ||
            n.out.exists(e => !OutEdge.isLoopEdge(e) && OutEdge.resolveTargetId(nodes, e.to).contains(nodeId))
          }
          .map(_.id)
          .toSet
        forward ++ reverse

    end match

  end referencesOf

  /**
   * **摘边残留判据（纯函数单点）**：该节点在**活动区**里是否还有任何挂线需要摘——
   * 自家 `in`/`deps` 非空，或自家 `out` 里还有一条**能解析成活动节点**的边（纯
   * `Nebula` 边与悬空名不算挂线，同 `topologicalChains` 的图成员判据），或活动区里
   * 任何别的节点还在 `in`/`out`/`deps` 里引用它。
   *
   * ③（chainmodel 批一 **登记**）：`chain:<id>` 引用**不**参与本判据的逐点匹配（它不是
   * 节点引用：既不能作为「引用我」命中，也不会被摘边腿 prune 掉——摘边腿按节点 id 过滤，
   * 链引用原样保留，语义 = 「该下游仍等这条链的其余成员」）。若某成员退役令目标链永不
   * 全 completed，该下游呈现为**永久停等**并由 `barrierHeldReason`（已按链引用展开）点名。
   * 零链引用时逐字等于改造前行为。
   *
   * 用途 = [[detachAbandonedNode]] 的**零写出口**（无残留 ⇒ 连一次 `mutateWithResult`
   * 都不进 ⇒ 真零写，不是「写了同样内容」）与 [[backfillAbandonedDetach]] 的候选集。
   * 两处共用本单点，禁二次派生（回填与工具路径判据漂移会让幂等证据失真）。
   */
  private def retireGap(snap: FlowMapState, nodeId: String): Boolean =
    val nodes = snap.nodes
    nodes.get(nodeId) match
      case None => false
      case Some(from) =>
        val selfGap = from.in.nonEmpty || from.deps.nonEmpty ||
          from.out.exists(e => e.to != OutEdge.NebulaTarget && OutEdge.resolveTargetId(nodes, e.to).isDefined)
        selfGap || nodes.values.exists { n =>
          n.id != nodeId && (n.in.contains(nodeId) || n.deps.contains(nodeId) ||
            n.out.exists(e => OutEdge.resolveTargetId(nodes, e.to).contains(nodeId)))
        }

  /**
   * **abandon 摘边**（cancelled 滞留主图修复批 · **案 A** 2026-09-14 作者 17:24 拍板）。
   *
   * 与 [[detachCancelledUpstream]]（NodeCancel 的 R4 摘除）**同族不同臂**：本方法把
   * 退役节点从拓扑上**摘干净**，使它自成**全终态分量** ⇒ 30s 链级 sweep
   * （`FlowMapStore.sweepCompletedChainsDetailed`）正常把它移出主图（**归档留底，
   * 非删除**；案 E 真删除已被作者显式排除）。NodeCancel 侧一行不动。
   *
   * == 机械动机（考古批 `20260914_165000_cancel-render-archaeo__chain-n-bbde88b1`）==
   * 两条取消入口结构性不对称：`cancelNode` 摘 out→Nebula，`abandon` **一条边都不摘**
   * ⇒ 退役节点被活链粘住。现场 11 件 cancelled 中机械根因样例 = 一条 `deps` 边
   * （`n-aa3382e0.deps=["n-8b21387d"]`，后者是另一条无关批的 wiring 节点）⇒ 所在分量
   * 32 成员 / 25 个非终态 ⇒ `chainArchivable=false` ⇒ 整分量永不出库；且 cancelled
   * **节点级 TTL 恒 None**（现场 11/11 实证）⇒ 没有第二条出图路径。
   *
   * == 语义定义（逐条；本批自决项，供复核）==
   * ① **自家挂线清空**：`in = Nil`、`deps = Nil`、`out = List(OutEdge.nebula)`。
   *    `out` 收束形态与 [[detachCancelledUpstream]] **逐字一致**（`OutEdge.nebula` =
   *    `{pass,failed}`/mode=result，NodeDef 字面构造先例）；退役节点永不投递（cancelled
   *    不可重激活），故收束不影响任何投递面。
   *    为什么要摘 `in`（`detachCancelledUpstream` 不摘）：`FlowMapStore.topologicalChains`
   *    的邻接是**无向**边集 `in ∪ out`（**chainmodel 批一 ① 2026-09-19 起 `deps` 不再是成员
   *    边**——旧口径 `in ∪ out ∪ deps` 作废；`deps` 仍进谱系边表、不再决定分量成员关系），
   *    且**两侧都建边**（上游 `out` 与下游 `in` 各自 `link` 一次）⇒ 只摘一侧摘不掉分量成员
   *    关系。要「自成全终态分量」必须把该节点**入射边**也清掉。
   *    🔴 口径变化登记：本条只改**理由**，不改**臂**——`R.deps ∋ id → prune` 臂仍保留（摘的
   *    是「下游还在等我」的依赖事实与后续停止等待语义，与成员边判据解耦），且 `deps` 边
   *    脱钩后「被 deps 粘住分量」的原始病根已由 ① 的定义层改动直接消除（本方法的摘边语义
   *    因此更宽松地达成目标、无新增禁用面）。
   * ② **反向引用三面全摘**（活动区全扫，「谁还引用我」是唯一可靠方向——前向遍历在
   *    「out 已收束但下游 in 仍引用」的不一致拓扑下恒空，见 [[reversePruneReferences]]）：
   *      - `R.in ∋ id`（下游镜像）→ prune；**未消费的轨**（`R.deliveredTo` 不含 id）
   *        追加 `pendingSuccession`（「待承接」标，R4 同款口径：barrier 不被以缺轨输入
   *        自动触发成缺轨结论）。已消费的轨只 prune 不打标 —— 打标会凭空闸死一个
   *        barrier 本已齐备的节点（abandon 接受 completed 节点，该形态实存）。
   *      - `R.out → id`（上游前向引用，含 `:loop` 控制边）→ 摘除该边。**为什么必须摘**：
   *        现场 11 件里 2 件（`n-060dee00` / `n-21131298`）的粘边正是**已 completed
   *        上游的 pass 边**（`n-85ce6fbe.out ∋ n-060dee00` 等）——只摘自家边摘不掉它们
   *        （只读模拟：仅摘 deps/out 时 9/11 出图，全摘后 11/11）。是否摘 `:loop` 回边：
   *        **摘**——`topologicalChains` 对 loop 边同样建邻接（仅 via 标注不同），留着即
   *        粘住；且回边目标一旦退役，其重跑路径本就断了（cancelled 不可重激活；
   *        `reloopTo` 对「目标已不在活动区」是**显式留痕 + 跳过**，不炸）。
   *      - `R.deps ∋ id`（下游依赖）→ prune；**退役前该节点未 completed** 时追加
   *        `pendingSuccession`（依赖轨从未被满足 ⇒ 留可见的「待承接」缺口，而不是让下游
   *        以缺轨自动开跑）；退役前**已 completed**（`priorStatus`）⇒ 只 prune 不打标
   *        （该依赖已被满足过，打标会凭空闸死下游）。
   * ③ **可见性**：被改写的每个 referrer 补发 `nodeUpdated`（与 [[detachCancelledUpstream]]
   *    / [[reversePruneReferences]] 逐字同款：标记只落盘不推帧则前端要等下一次全量快照）；
   *    退役节点自身由 `NodeEditTool.abandonNode` 的状态写点发帧，本方法不发。
   * ④ **幂等**：`RetireDetach.isEmpty` ⇒ 零写、零帧；重复调用第二次恒空（回填腿据此免副作用）。
   * ⑤ **零结算/零投递**：摘边 = 该边不存在，既非零结算也非占位投递（D5 failed 侧纪律
   *    零改动，本方法不碰 deliverFailed 任何分支）。
   * ⑥ `case None`（节点不在活动区）⇒ 空台账零写（调用方 `NodeEditTool.abandonNode` 同期
   *    查无该节点 ⇒ 整个 abandon 本就是 no-op）。
   *
   * @param priorStatus 该节点**在 abandon 写状态之前**的现值（回填路径给当时的
   *        `Cancelled` ⇒ 依赖轨按「未满足」处理，保守留「待承接」可见态）。
   */
  def detachAbandonedNode(
    nodeId: String,
    priorStatus: String,
    emitRouteLost: Boolean = true
  ): IO[NodeEngine.RetireDetach] =
    // 零写出口（幂等硬约束的机械承担点）：无残留 ⇒ 一次 store.snapshot、零 mutate、
    // 零帧、零事件 —— 不是「写了同样内容」。快照与事务之间的竞态由事务内重算兜住
    // （真无残留则返回空台账，仍零帧）。
    store.snapshot.flatMap { snap =>
      if !retireGap(snap, nodeId) then IO.pure(NodeEngine.RetireDetach())
      else
        store
          .mutateWithResult { s =>
            s.nodes.get(nodeId) match
              case None => (s, NodeEngine.RetireDetach())
              case Some(from) =>
                def resolvable(e: OutEdge): Boolean =
                  e.to != OutEdge.NebulaTarget && OutEdge.resolveTargetId(s.nodes, e.to).isDefined
                val selfHasGap = from.in.nonEmpty || from.deps.nonEmpty || from.out.exists(resolvable)
                val others = s.nodes.values.filter(_.id != nodeId).toList
                val inMirrors = others.filter(_.in.contains(nodeId)).map(_.id).sorted
                val outRefs =
                  others
                    .filter(_.out.exists(e => OutEdge.resolveTargetId(s.nodes, e.to).contains(nodeId)))
                    .map(_.id)
                    .sorted
                val depsRefs = others.filter(_.deps.contains(nodeId)).map(_.id).sorted
                if !selfHasGap && inMirrors.isEmpty && outRefs.isEmpty && depsRefs.isEmpty then
                  (s, NodeEngine.RetireDetach())
                else
                  val inSet = inMirrors.toSet
                  val outSet = outRefs.toSet
                  val depsSet = depsRefs.toSet
                  val depSatisfied = priorStatus == NodeLifecycle.Completed
                  val rewired: Map[String, NodeDef] = s.nodes.map { case (id, n) =>
                    if id == nodeId then id -> n.copy(in = Nil, deps = Nil, out = List(OutEdge.nebula))
                    else
                      val byIn =
                        if inSet.contains(id) then
                          n.copy(
                            in = n.in.filterNot(_ == nodeId),
                            pendingSuccession =
                              if n.deliveredTo.contains(nodeId) then n.pendingSuccession
                              else (n.pendingSuccession :+ nodeId).distinct
                          )
                        else n
                      val byOut =
                        if outSet.contains(id) then
                          byIn.copy(out =
                            byIn.out.filterNot(e => OutEdge.resolveTargetId(s.nodes, e.to).contains(nodeId))
                          )
                        else byIn
                      id -> (if depsSet.contains(id) then
                               byOut.copy(
                                 deps = byOut.deps.filterNot(_ == nodeId),
                                 pendingSuccession =
                                   if depSatisfied then byOut.pendingSuccession
                                   else (byOut.pendingSuccession :+ nodeId).distinct
                               )
                             else byOut)
                  }
                  // **拒绝态受害集**（failroute-guard 批 2026-09-21 · 案 A A1；卡 A3 的判据
                  // 在此**前后各算一次**）：被本次摘边摘掉 fail 选通边的上游 referrer 里，
                  // 「摘前合法 ∧ 摘后失路」的 verifier = 受害位。摘边语义**逐字不动**（本批
                  // 只动它的后果面：可见态 + 告警）——本判据**腿无关**（工具腿 abandon 与
                  // 引擎腿 NodeCancel→30s 回填腿到达同一处），故只在此落点即覆盖两条到达路径。
                  val routeLost: List[String] = outRefs.filter { id =>
                    (s.nodes.get(id), rewired.get(id)) match
                      case (Some(before), Some(after)) =>
                        !NodePayload.verifierRouteInvalid(before, s.nodes) &&
                        NodePayload.verifierRouteInvalid(after, rewired)
                      case _ => false
                  }
                  (
                    s.copy(nodes = rewired),
                    NodeEngine.RetireDetach(inMirrors, outRefs, depsRefs, selfHasGap, routeLost)
                  )
                end if
          }
          .flatMap { case (_, d) =>
            val refFrames = d.referrers.foldLeft(IO.unit) { (acc, tid) =>
              acc >> store.getNode(tid).flatMap {
                case Some(n) => emitUpdated(n)
                case None => IO.unit
              }
            }
            // 告警写点（卡 A2）：受害 verifier 的拒绝态在**同一帧**落盘 + WARN，主语 = 受害
            // verifier。**按批聚合**（裁定⑤）= 本位退役动作恰一行；回填腿（同批多退役）由
            // 调用方抑制本位发射、整批收口发一行（传 `emitRouteLost = false`）。
            refFrames *>
              (if emitRouteLost then emitVerifierRouteLost(d.routeLost.map(_ -> nodeId), "detach")
               else IO.unit).as(d)
          }
    }

  /**
   * **存量回填腿**（cancelled 滞留主图修复批 · 腿 2，2026-09-14）：对**已 cancelled**
   * 且仍有挂线的滞留节点补做 [[detachAbandonedNode]] 同语义摘边。
   *
   * 驱动 = `ProjectActor.TtlTick`（30s 节拍，**restart-effect**：宿主重启后自动继续），
   * 排在链级归档 sweep **之前**——同一 tick 内先摘边、再出库（被摘净的分量当帧即可
   * 归档，不等下一个 30s）。
   *
   * **为什么是引擎侧可复跑路径而非一次性改盘**：`flow-map.json` 的写点单点在
   * `FlowMapStore`（前端零拓扑写通道）⇒ 手工改数据文件既不幂等也逃过审计；本腿
   * 每次 tick 重算、幂等、留事件。
   *
   * **幂等（硬）**：候选集由 [[retireGap]] 判定（「仍有挂线」才入选）；摘净后该节点
   * 不再入选 ⇒ 第二次运行**连一次 store 写都不进**（零写、零帧、零事件，不是「写了
   * 同样内容」）。零候选 ⇒ 一次 `store.snapshot` + 直接返回。
   *
   * 只扫**活动区**（`store.snapshot`）；归档区成员恒终态且已出图，不在本腿范围。
   * 返回本轮真正被摘边的节点 id（升序，审计/对账用）。
   */
  def backfillAbandonedDetach(): IO[List[String]] =
    store.snapshot.flatMap { snap =>
      val candidates = snap.nodes.values.toList
        .filter(_.status == NodeLifecycle.Cancelled)
        .filter(n => retireGap(snap, n.id))
        .map(_.id)
        .sorted
      if candidates.isEmpty then IO.pure(Nil)
      else
        candidates
          // `emitRouteLost = false`：本条腿是**同批多退役**的集中来源，受害 verifier 的
          // 告警按批聚合（裁定⑤：防同批多退役逐位刷屏，与 cancelled 通知熔断同纪律）——
          // 整批收口发一行，见下方 `emitVerifierRouteLost`。
          .traverse(id => detachAbandonedNode(id, NodeLifecycle.Cancelled, emitRouteLost = false).map(d => (id, d)))
          .flatMap { pairs =>
            val detached = pairs.collect { case (id, d) if !d.isEmpty => id }
            // 受害集从**各退役位自己的台账**取（`RetireDetach.routeLost`）——此刻盘上引用
            // 已被摘净，事后重扫必然空，故必须用摘边当场算出的台账。
            val victims = pairs.flatMap { case (retired, d) => d.routeLost.map(v => v -> retired) }
            detached.traverse_ { id =>
              FlowMapEventLog.append(
                workspace,
                projectName,
                id,
                FlowMapEventLog.AbandonedDetachType,
                s"cancelled node's incident edges detached by the 30s backfill leg (in/out/deps severed on both " +
                  "sides ⇒ the node now forms its own terminal component; the chain sweep archives it)"
              )
            } *>
              // 本批（回填腿）的**整批**受害集收口（一条事件行 + 一条 WARN；零受害 ⇒ 零写）。
              emitVerifierRouteLost(victims, "30s backfill leg").as(detached)
          }
      end if
    }

  /**
   * **受害 verifier 拒绝态告警的收口写点**（failroute-guard 批 2026-09-21 · 案 A A2）：
   * 按批发**一条** `verifier-route-lost` 事件行 + 一条 WARN（零受害 ⇒ 零写）。
   *
   * 主语（`nodeId` 字段）= **首个受害 verifier 的 id**（不是退役节点）——与
   * `chain-cancelled` 以「代表节点」承载整批 nodeId 同族；summary 逐位载四项：受害
   * verifier 名/id、被摘的 fail 目标 id、保留的 pass 目标集、可行动恢复文案
   * （`NodeEdit out="(pass)<landing>, (fail)<worker>:loop"`）。
   *
   * `victims` = (受害 verifier id, 被摘的 fail 目标 id 或 "" 表示「无路由可摘」)；
   * 现读受害者状态以取名字与保留的 pass 面（判据不落持久字段 ⇒ 每次现算）。
   * 幂等：空集 ⇒ 零写零日志。
   */
  def emitVerifierRouteLost(victims: List[(String, String)], scope: String): IO[Unit] =
    val uniq = victims.filter(_._1.trim.nonEmpty).distinct.sortBy(_._1)
    if uniq.isEmpty then IO.unit
    else
      store.snapshot.flatMap { s =>
        val views = uniq.map { case (verId, lostTarget) =>
          val ver = s.nodes.get(verId)
          FlowMapEventLog.VerifierRouteLostView(
            verifierId = verId,
            verifierName = ver.map(_.name).getOrElse(verId),
            lostTargets = Option(lostTarget).filter(_.trim.nonEmpty).toList,
            keptPassTargets = ver.toList
              .flatMap(v =>
                OutEdge
                  .canonical(v.out)
                  .filter(e => e.on.contains(OutEdge.Pass) && !OutEdge.isLoopEdge(e))
                  .map(_.to)
                  .filterNot(_ == OutEdge.NebulaTarget)
                  .distinct
              )
              .sorted
          )
        }
        val summary = FlowMapEventLog.verifierRouteLostSummary(scope, views)
        FlowMapEventLog.append(
          workspace,
          projectName,
          views.head.verifierId,
          FlowMapEventLog.VerifierRouteLostType,
          summary
        ) *>
          logger.warn(
            s"[verifier-route-lost] $summary (project=$projectName; see NodeList payload key " +
              s"'verifierRoute' = ${NodePayload.VerifierRouteLost})"
          )
      }

    end if

  end emitVerifierRouteLost

  /**
   * P2 G11（spec §3.4）：节点 cancelled 级联清理其会话的 pending asks——hub 新增
   * CleanupForSession(sessionId)：移除该会话 pending 槽 + 向 root 广播
   * askUserClosed{requestId}（前端关卡摘卡归批 F2，本批验收到引擎广播为止）。
   * 三口级联：cancelNode（NodeCancel/bridge cancelled 分流）+ NodeEdit abandon +
   * dead-session reap（reapStaleRunning 内部走 cancelNode，自动覆盖）。
   * sessionRef=None（从未启动过，无会话即无问）或 hub 未挂（测试/早期 boot）→
   * 静默跳过。fire-and-forget（tell 语义）：清理失败不阻断 cancelled 终态化。
   */
  def cleanupPendingAsks(sessionId: Option[String]): IO[Unit] =
    sessionId.traverse_ { sid =>
      resources.interactionHubRef.get.flatMap {
        case Some(hub) => (hub ! InteractionHubCommand.CleanupForSession(sid)).void
        case None => IO.unit
      }
    }

end NodeCompletion
