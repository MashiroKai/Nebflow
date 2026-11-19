package nebflow.core.project

import cats.effect.IO
import cats.syntax.all.*
import nebflow.shared.NebflowLogger

/**
 * OrphanChainPolicy —— **孤儿链判定与处置**（引擎会话治理专项 · 缺陷六，作者令
 * 2026-11-03 14:52：「重启恢复链时必须判定父 dispatcher 存活，父不在 ⇒ 链标
 * 『孤儿』（面板/账本不得显示运行中）」）。
 *
 * 病灶（改前）：`ProjectCrashRecovery.recoverDelegateScopes` 对 flow-maps/ 下每个
 * 作用域**无条件**重挂宿主，并对 `Running` / `Interrupted` 节点 `bootRecoveryClaim`
 * + `bootRecoveryStart` 续跑——**不看属主 dispatcher 会话是否还活着、不标孤儿、零
 * 通知**。父已死的链因此在面板上继续「运行中」，节点无限推进，产出无处投递。
 *
 * 处置语义（**选 a：暂停推进**，报告说明理由）：
 *   - 不选 b（「跑完归档为无收口产出」）：父死的链没有收口对象（产出无人读），继续
 *     LLM 烧钱且与后来接管的实例抢同一批工作树/分支（11-03 实证）；归档一张
 *     「无主完成」的图还会污染结果面。
 *   - 选 a：链台账落 `paused`（`chainGateOf` 闸死全部派发 ⇒ 节点**绝不**被
 *     claim/rehydrate）+ `chain-orphaned` 审计事件（链号 / 父会话 / 剩余节点数）+
 *     根会话通知（活投 / 持久收件箱补投）。根会话可经 `NodeCancel` 的 resume 原语
 *     显式接管，或重新派发——决策权留在人/根，引擎不自作主张。
 *
 * 判定只用在案事实：属主 id **不在 delegate 注册表**（且有作用域图残留）= 父已死。
 * 判定与动作分离（[[Port]]），机械断言用假端口驱动。
 */
object OrphanChainPolicy:

  private val logger = NebflowLogger.forName("nebflow.project.orphan-chain")

  /** 审计事件类型（FlowMapEventLog 行）。 */
  val EventType: String = "chain-orphaned"

  /** 配置：`pause`（默认，= 本批语义）| `resume`（回滚到改前的无脑续跑）。 */
  def action: String =
    sys.props.getOrElse("nebflow.boot.orphanChain.action", "pause").trim.toLowerCase match
      case "resume" => "resume"
      case _        => "pause"

  /** 是否按暂停语义处置（false = 回滚开关，走改前续跑腿）。 */
  def pauseEnabled: Boolean = action == "pause"

  /** 孤儿判定：属主不在 delegate 注册表（作用域没有活着的主人）。 */
  def isOrphan(ownerRecorded: Boolean): Boolean = !ownerRecorded

  /** 端口（生产 = [[ProjectCrashRecovery]] 从已挂载宿主装配；测试 = 假端口）。 */
  trait Port:
    /** 作用域内的链号（去重）+ 剩余非终态节点数。 */
    def scopeState: IO[(List[String], Int)]
    /** 落链控 `paused`（暂停推进）。 */
    def pauseChain(chainId: String): IO[Unit]
    /** 审计事件（chain-orphaned）。 */
    def event(chainId: String, remaining: Int): IO[Unit]
    /** 根会话通知（含链号 / 父会话 id / 剩余节点数）。 */
    def notifyRoot(scope: String, chains: List[String], remaining: Int): IO[Unit] = IO.unit

  /** 单作用域处置：暂停每条链 → 留痕 → 通知根。返回被暂停的链数。 */
  def handle(port: Port, scope: String): IO[Int] =
    if !pauseEnabled then
      logger.info(s"[orphan-chain] scope '$scope': 处置语义 = resume（回滚配置）— 走改前续跑腿").as(0)
    else
      port.scopeState.flatMap { case (chains, remaining) =>
        chains.traverse { cid =>
          port
            .pauseChain(cid)
            .handleErrorWith(e => logger.warn(s"[orphan-chain] pause failed for chain $cid: ${e.getMessage}"))
            *> port
              .event(cid, remaining)
              .handleErrorWith(e => logger.warn(s"[orphan-chain] event write failed for chain $cid: ${e.getMessage}"))
        } *> port
          .notifyRoot(scope, chains, remaining)
          .handleErrorWith(e => logger.warn(s"[orphan-chain] root notify failed: ${e.getMessage}"))
          .as(chains.size)
      }

end OrphanChainPolicy
