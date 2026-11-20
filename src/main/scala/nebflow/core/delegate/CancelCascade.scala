package nebflow.core.delegate

import cats.effect.IO
import cats.syntax.all.*
import nebflow.actor.{AgentKind, AgentRecord}
import nebflow.shared.NebflowLogger

/**
 * CancelCascade —— 父子会话级联取消 + 孤儿子会话判定（引擎会话治理专项 · 缺陷七，
 * 作者 2026-11-03 15:09 报障逐字「你杀掉父会话，子会话也应该一并关闭，但是现在好像
 * 是只会清理父会话，子会话变成孤儿」）。
 *
 * 病灶：`AgentControlTool.doCancel` 只结算**目标记录本身**（墓碑 + Cancelled/Stop +
 * 注册表移除）。Dispatched 出去的子会话（SubTask / 嵌套 Delegate / 子批 / ephemeral
 * 执行体）在统一注册表里以 `parentSessionId == 目标 id` 存活，取消父之后它们**继续
 * 跑**：还在往同一批工作树/分支写东西，与后续接管的实例抢文件——「半死状态」。
 *
 * 本对象只提供**判定 + 编队**（纯函数 + 端口），执行仍由调用方注入：
 *   - 生产执行 = `AgentControlTool.doCancel`（pedigree：同一套墓碑/确认/兜底语义，
 *     级联腿只是把同一个原语递归作用到子记录上，`cascade = false` 防二次走查）；
 *   - 机械断言（`CancelCascadeSpec` = `SessionCascadeSpec`）注入假端口，不碰真 actor。
 *
 * 级联顺序 = **后序**（先孙后子，最后才是调用方自己的父结算）：子会话可能仍在
 * spawn 更深的孙会话，先斩叶再斩枝，父始终最后终态 ⇒ 不存在「父已结算、子仍在跑」
 * 的窗口被观察到（作者判据）。
 *
 * 孤儿判定（同族第二面）：父会话已终态（取消墓碑 / 归档在案）而子记录仍在注册表里
 * 活着 ⇒ 该子 = 孤儿。判定只用**在案事实**（墓碑/归档时间戳）而不用「父不在注册表」
 * ——后者对启动期、跨域根会话是误报源（会让正常子会话被误杀）。
 */
object CancelCascade:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.cancel-cascade")

  /** 级联深度上限（父子链的环路/病态自嵌套保险）。 */
  val MaxDepth: Int = 8

  /** 处置结论字符串（报告与审计面共用；面板/日志可直接 grep）。 */
  object Outcome:
    val Killed: String = "killed"
    val AlreadyTerminal: String = "already-terminal"
    val Refused: String = "refused"

  /** 一条子会话的处置结果（`killed` / `already-terminal` / `refused`）。 */
  final case class Step(
    sessionId: String,
    kind: String,
    statusAtCancel: String,
    outcome: String,
    detail: String = ""
  )

  /** 一条孤儿记录（子会话 + 判死的父证据）。 */
  final case class Orphan(child: AgentRecord, parentId: String, cause: String)

  /**
   * 执行端口（生产 = AgentControlTool.doCancel 自身；测试 = 假端口）。core 不 import
   * agent 域，端口只认 actor 层的 [[AgentRecord]]。
   */
  trait Port:
    def registry: IO[Map[String, AgentRecord]]

    /** 已在案的取消墓碑 id 集（`DelegateRegistry.cancelRequestedAt` 非空）。 */
    def tombstoned: IO[Set[String]]
    def cancelChild(rec: AgentRecord, why: String): IO[Either[String, Unit]]

  /** 直接子会话（按 `parentSessionId` 反查；排序保决定性报告顺序）。 */
  def children(reg: Map[String, AgentRecord], parentId: String): List[AgentRecord] =
    reg.values.filter(r => r.parentSessionId == parentId && r.sessionId != parentId).toList.sortBy(_.sessionId)

  /**
   * 后序级联：对 `parentId` 的每个子会话，先递归处理其子孙，再处置该子本身。
   * 返回**全部**被处置会话的逐条结果（含孙层——报告要逐条，不是只有第一层）。
   */
  def cascade(port: Port, parentId: String, extraSeeds: List[String] = Nil): IO[List[Step]] =
    def walk(id: String, depth: Int): IO[List[Step]] =
      if depth > MaxDepth then
        logger
          .warn(s"cancel-cascade: depth limit $MaxDepth reached below '$parentId' — deeper levels not walked")
          .as(Nil)
      else
        port.registry.flatMap { reg =>
          // 顶层额外种子（项目域：dispatcher/node 会话在注册表里没有 parentSessionId
          // 边——节点 ↔ 属主的边活在 flow map 的 `sessionRef`；由调用方解析后作为
          // 种子注入，本层起照常按 parentSessionId 递归）。
          val seeds = if id == parentId then extraSeeds.flatMap(reg.get) else Nil
          (children(reg, id) ++ seeds).distinctBy(_.sessionId).flatTraverse { kid =>
            walk(kid.sessionId, depth + 1).flatMap { descendants =>
              port.tombstoned.flatMap { ts =>
                if ts.contains(kid.sessionId) then
                  IO.pure(
                    descendants :+
                      Step(
                        kid.sessionId,
                        kid.kind.toString,
                        kid.status.toString,
                        Outcome.AlreadyTerminal,
                        "cancel tombstone already on record"
                      )
                  )
                else
                  port
                    .cancelChild(kid, s"parent session $parentId cancelled")
                    .attempt
                    .map {
                      case Right(Right(_)) =>
                        Step(kid.sessionId, kid.kind.toString, kid.status.toString, Outcome.Killed)
                      case Right(Left(err)) =>
                        Step(kid.sessionId, kid.kind.toString, kid.status.toString, Outcome.Refused, err.take(200))
                      case Left(e) =>
                        Step(
                          kid.sessionId,
                          kid.kind.toString,
                          kid.status.toString,
                          Outcome.Refused,
                          s"cancel raised: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}".take(200)
                        )
                    }
                    .map(step => descendants :+ step)
              }
            }
          }
        }
    walk(parentId, 0)

  end cascade

  /** 报告行（追加到 cancel 成功文案尾部；零子会话 = 空串，旧文案逐字不变）。 */
  def report(steps: List[Step]): String =
    if steps.isEmpty then ""
    else
      val killed = steps.count(_.outcome == Outcome.Killed)
      val already = steps.count(_.outcome == Outcome.AlreadyTerminal)
      val refused = steps.count(_.outcome == Outcome.Refused)
      val lines = steps.map { s =>
        s"  - ${s.sessionId} (${s.kind}/${s.statusAtCancel}): ${s.outcome}" +
          (if s.detail.nonEmpty then s" — ${s.detail}" else "")
      }
      s"\n\nCancel cascade over child sessions — $killed killed, $already already-terminal, $refused refused:\n" +
        lines.mkString("\n")

  // ── 孤儿判定（缺陷七 ②；与缺陷六「孤儿链」同族：父子账本不一致） ──────────

  /**
   * 已明确取消的父会话 id 集。普通归档仅是保留/查询状态，不构成取消后代的授权；
   * 只有显式 cancel tombstone 表达了递归终止意图。
   */
  def terminalParents(delegates: List[DelegateRegistry.Record]): Set[String] =
    delegates.filter(_.cancelRequestedAt.isDefined).map(_.id).toSet

  /**
   * 孤儿 = 父有明确取消墓碑、在册仍活的子会话记录。普通完成后的归档不授权杀子。
   * 只看齐 `parentSessionId`（自引用除外）。
   */
  def detectOrphans(reg: Map[String, AgentRecord], terminalParentIds: Set[String]): List[Orphan] =
    reg.values.toList
      .filter(r => r.parentSessionId.nonEmpty && r.parentSessionId != r.sessionId)
      // 裁定4：外部腿（`AgentKind.External`，ref 恒 null）**不在孤儿处置面**——
      // 它没有 actor 可 Stop，取消走 ExternalSessionRunner（杀进程树），终态由
      // settle 摘除登记行。放进本表只会得到一条「无法处置」的假孤儿。
      .filter(_.kind != AgentKind.External)
      .filter(r => terminalParentIds.contains(r.parentSessionId))
      .sortBy(_.sessionId)
      .map(r =>
        Orphan(
          r,
          r.parentSessionId,
          "parent session is terminal (cancel tombstone / archived) but this child is still registered"
        )
      )

end CancelCascade
