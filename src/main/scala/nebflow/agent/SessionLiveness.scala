package nebflow.agent

import cats.effect.{IO, Ref}
import nebflow.actor.AgentRecord
import nebflow.core.{AgentRuntimePort, SessionStore}
import nebflow.shared.NebflowLogger

/**
 * SessionLiveness —— 会话**存活判据单点**（OWNER-GONE 误判修复批 2026-11-06 · 作者
 * 报障「活着的 delegate 被判父会话已消失，合并槽被关掉」）。
 *
 * == 病灶（读到本文件即别再自造判据）==
 *
 * 2026-11-06 08:21 / 08:26 两批 delegate（`41cd2d19` / `20056caf`）在 AgentControl 里
 * 明明是 `Idle`、`lastActivity 0s`、root 会话健在，合并队列却把合并轮通知判成
 * `parent-session-gone` 并关账 OWNER-GONE。判据实现在
 * `MailboxProducer.deliver`：`sessionStore.listSessions.exists(_.id == parentSessionId)`。
 * 而 `listSessions` 只读**会话索引** `_index.json`——**delegate 会话从不进索引**
 * （`SessionStore.listSessionsIncludeUnindexed` 与 `shared.SessionScope` 头注写死了
 * 这条事实：后台 agent 会话只把 `<id>.json` / `<id>.ui.json` 落盘）。于是该谓词对
 * **任何** delegate 收件方恒假 ⇒ 投递必被判死 ⇒
 * `MergeQueue.grant ⑥` → `markOwnerGone`；同一谓词还被
 * `SharedResources.OwnerDeliveryPort.present` 用于 careTick 的「持窗属主还在吗」，
 * 于是持窗也会被同一误判断掉。
 *
 * == 判据（三态合一，绝不凭派生信号判死）==
 *
 *   1. **活体 actor**（`agentRegistry` 有行 = AgentControl 同源的「这个会话此刻在
 *      跑/挂着」）⇒ 存活。这一个信号就够：actor 在 = 它的邮箱/门铃可达。
 *   2. 否则看**会话记录**：索引行 ∪ 盘上记录（`SessionStore.sessionRecordExists`）
 *      ⇒ 存活。delegate 会话正是落在这一格：索引没有它，但转录与 UI 件都在盘上，
 *      落盘邮箱写进去照样会在它下次 spawn / resume 时被消费（P1 身份化的本意）。
 *   3. 两者都读不到 ⇒ **真 gone**（会话被删干净 / 从未存在）。真 OWNER-GONE 的
 *      关账语义逐字保留——本判据只去掉「索引里没有 = 已死」这个**假否**。
 *
 * 读数故障（存储面异常）⇒ **fail-open**（当真 = 允许投递）：一份写不进读的邮箱件
 * 由 boot 对账兜底，而误判 OWNER-GONE 会把一条活分支的合并槽直接关掉——两害相权，
 * 绝不在读不到的时候判死（与 MergeQueue 既有的「读数不可得 = 不判」同纪律）。
 */
private[agent] object SessionLiveness:

  private val logger = NebflowLogger.forName("nebflow.agent.session-liveness")

  /**
   * 判据本体（数据面两源显式传入——spec 可直接装配，不必造整个 SharedResources）。
   *
   * @param registry `sessionId → AgentRecord` 注册表（`AgentRegistryPort.agentRegistry`）
   */
  def alive(store: SessionStore, registry: Ref[IO, Map[String, AgentRecord]], sessionId: String): IO[Boolean] =
    val sid = Option(sessionId).map(_.trim).getOrElse("")
    if sid.isEmpty then IO.pure(false)
    else
      registry.get.attempt.flatMap {
        case Right(rows) if rows.contains(sid) =>
          IO.pure(true) // ① 活体 actor（AgentControl 同源）
        case _ =>
          store.sessionRecordExists(sid).attempt.map {
            case Right(found) => found // ② 索引 ∪ 盘上记录
            case Left(e) =>
              logger.warn(
                s"[session-liveness] record read failed for '$sid': ${Option(e.getMessage).getOrElse(e.toString).take(160)} — " +
                  "treating as ALIVE (fail-open: never close a merge slot on an unreadable signal)"
              )
              true
          }
      }

  /** 生产便捷面（`resources` = SharedResources / 任何 AgentRuntimePort 视图）。 */
  def alive(resources: AgentRuntimePort, sessionId: String): IO[Boolean] =
    alive(resources.sessionStore, resources.agentRegistry, sessionId)

  /**
   * **只读记录面**（注册表拿不到的调用点：boot 对账、复活资格判读等 legacy 装配）。
   *
   * 判据 = 索引 ∪ 盘上会话记录；读数故障 = fail-open（true）。比 [[alive]] 少一条
   * 「活体 actor」信号——所以它只会把**更多**会话判成活，绝不会误判死（本批的方向
   * 就是消灭假否）。
   */
  def recordAlive(store: SessionStore, sessionId: String): IO[Boolean] =
    store.sessionRecordExists(sessionId).handleErrorWith { e =>
      logger.warn(
        s"[session-liveness] record read failed for '${Option(sessionId).getOrElse("")}': " +
          s"${Option(e.getMessage).getOrElse(e.toString).take(160)} — treating as ALIVE (fail-open)"
      ).as(true)
    }

  /**
   * 判死理由（写日志/审计行用；两源各说一句，避免「父会话已消失」这种不指明读数
   * 的笼统判词——今早那条误判正是靠这句话才被追到的）。
   */
  def goneReason(sessionId: String): String =
    s"no live agent record and no session record (index ∪ disk) for '$sessionId'"

end SessionLiveness
