package nebflow.core.tools

import cats.effect.IO

/**
 * 后台任务活性判定（后台任务生命周 P2 · death-watch 批 · 设计稿 20261030 §4.2）。
 *
 * == 判据（零墙钟——全部读内核进程事实/既有在册事实，无任何到点判死）==
 *
 *  - **local**：`ShellSession.peekSession`（非创建式）⇒
 *      - 属主会话不在册/已死 ⇒ `Dead("owner session gone")`（会话收殓时已杀其
 *        全部进程树——kill()/killSessionProcesses 的既有语义，行内任务不可能
 *        再有终态腿；这正是 10-30 合并闸卡死 3 小时的残留形态：进程已死 +
 *        行残留 + 无完成事件）；
 *      - 在册 ⇒ `probeJobLiveness`：任务不在其任务图 ⇒ `Dead("not owned by
 *        session")`（行指向的会话不持有该任务——终态腿结构性绝路）；持有 ⇒
 *        进程可验证存活 `Alive`，进程已死 `Dead("process dead")`（deferred
 *        完成与否不构成豁免——完成但回调已死/被取消的行同样是绝路，收割后
 *        first-terminal-wins 由行取走判据承担，迟到真完成只补输出留存区）。
 *  - **remote**：等待 fiber 句柄图（RemoteExecutor.bgWaitFibers）⇒ 句柄在图
 *      `Alive`（终态腿在飞）；句柄缺席而行仍在 `Dead("remote wait leg gone")`
 *      （终态事件已绝路）。RemoteExecutor 未初始化（spec harness）⇒ `Unknown`。
 *
 * `Unknown` 的语义 = 「本环境无从判定」——调用方一律**跳过**（宁可漏收一轮，
 * 绝不误收活任务；健康轨 escalated 承接可见性）。
 */
object BgTaskLiveness:

  sealed trait Verdict extends Product with Serializable
  case object Alive extends Verdict
  final case class Dead(reason: String) extends Verdict
  case object Unknown extends Verdict

  /** 单行活性探针（默认判据面；spec 经 BgTaskReaper.reapWith 注桩绕行本面）。 */
  def probe(row: BgTaskRegistry.ActiveTask): IO[Verdict] =
    if row.kind == "remote" then probeRemote(row.jobId)
    else probeLocal(row.sessionId, row.jobId)

  private[tools] def probeRemote(jobId: String): IO[Verdict] =
    RemoteExecutor.current match
      case None       => IO.pure(Unknown)
      case Some(exec) => exec.bgWaitAlive(jobId).map(if _ then Alive else Dead("remote wait leg gone"))

  private[tools] def probeLocal(sessionId: String, jobId: String): IO[Verdict] =
    ShellSession.peekSession(sessionId).flatMap {
      case None => IO.pure(Dead("owner session gone"))
      case Some(shell) =>
        shell.probeJobLiveness(jobId).map {
          case None => Dead("not owned by session")
          // 句柄未落（spawn 在飞）不是死证——executeBackground 先注册任务图/行、
          // 后落 processRef，毫秒级窗口内判 Dead 会收割刚起的活任务（A-4 实测：
          // cap 用例 40ms 窗口内进程句柄未落被误杀）。
          case Some(p) if !p.handlePresent => Alive
          case Some(p) if !p.alive         => Dead("process dead")
          case _                           => Alive
        }
    }
