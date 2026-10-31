package nebflow.core.tools

import cats.effect.IO
import nebflow.shared.Defaults

/**
 * 后台任务活性判定（后台任务生命周 P2 · death-watch 批 · 设计稿 20261030 §4.2
 * + 2026-10-31 误报修复批（作者令「修」），根因 docs/20261031_owner-lost.md）。
 *
 * == 判据（以内核进程事实/既有在册事实为主，墙钟只用于**豁免窗**——
 *   只拦误杀、不制造到点判死）==
 *
 *  - **local**：`ShellSession.peekSession`（非创建式）⇒
 *      - 属主会话不在册/已死 ⇒ `Dead("owner session gone")`（会话收殓时已杀其
 *        全部进程树——kill()/killSessionProcesses 的既有语义；**幼龄豁免不适用
 *        本分支**——会话已殁是结构性死证，10-31 修法清单②）；
 *      - 在册 ⇒ **幼龄行豁免**（修法②）：行龄 < [[Defaults.BgDeathWatchYoungRowExemptMs]]
 *        （默认 10s）⇒ 直接 `Alive` 跳过本轮——spawn/句柄/任务图落位的毫秒~秒级
 *        窗口内一切判死依据都尚未稳定（瞬起瞬崩合法 case 不误伤，实证 5ae83e33）；
 *      - 行龄过窗 ⇒ `probeJobLiveness`：任务不在其任务图 ⇒
 *        `Dead("not owned by session")`（行指向的会话不持有该任务——#1220 行↔图
 *        发散形态；落地收割前还要过 reaper 的输增长二次确认，修法⑤）；
 *      - 持有 ⇒ **deferred 已落终态 ⇒ `Alive`（修法①，done 者永不判死）**——
 *        终态腿在飞（完成回调尚未注销行），下一拍行自注销；进程死活不再是判据
 *        （消灭 758ms 窗口 `process dead` 假证，实证 5ae83e33）；
 *      - 句柄未落（spawn 在飞）⇒ **期限豁免**（修法⑥）：行龄 <
 *        [[Defaults.BgDeathWatchHandleGraceMs]]（默认 5min）⇒ `Alive`；逾期
 *        ⇒ `Dead("handle never landed")` 降级回普通判死路径（堵重启恢复/收编的
 *        句柄永不再落 ⇒ 永久 Alive 的僵尸免疫反向缺陷，46a86d3f 型的另一半）；
 *      - 句柄在且进程可验证存活 `Alive`；已死 `Dead("process dead")`
 *        （落地收割前同样过输增长二次确认，修法⑤）。
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

  /**
   * 单行活性探针（默认判据面；spec 经 BgTaskReaper.reapWith 注桩绕行本面）。
   * `nowMs` 默认取当前墙钟——只用于豁免窗的**行龄**计算（向后兼容：旧单参
   * 调用方逐字不受影响）。
   */
  def probe(row: BgTaskRegistry.ActiveTask, nowMs: Long = System.currentTimeMillis()): IO[Verdict] =
    if row.kind == "remote" then probeRemote(row.jobId)
    else probeLocal(row, nowMs)

  private[tools] def probeRemote(jobId: String): IO[Verdict] =
    RemoteExecutor.current match
      case None       => IO.pure(Unknown)
      case Some(exec) => exec.bgWaitAlive(jobId).map(if _ then Alive else Dead("remote wait leg gone"))

  private[tools] def probeLocal(row: BgTaskRegistry.ActiveTask, nowMs: Long): IO[Verdict] =
    val ageMs = nowMs - row.startedAtMs
    ShellSession.peekSession(row.sessionId).flatMap {
      // 会话不在册/已死：结构性死证——幼龄豁免不适用（修法②的唯一例外分支）。
      case None => IO.pure(Dead("owner session gone"))
      case Some(shell) =>
        // 修法② 幼龄行豁免：行龄 < 基线窗 ⇒ 跳过本轮（ageMs < 0 = 时钟回拨/未来
        // 戳，按正常路径判——不豁免）。spawn/句柄/图落位窗口内一切判死依据未稳。
        if ageMs >= 0L && ageMs < Defaults.BgDeathWatchYoungRowExemptMs then IO.pure(Alive)
        else
          shell.probeJobLiveness(row.jobId).map {
            case None => Dead("not owned by session")
            // 修法①：deferred 已落终态 ⇒ 永不判死——终态腿在飞，完成回调下一拍
            // 自注销行；进程已死不构成死证（758ms 窗口假证的根治，实证 5ae83e33）。
            case Some(p) if p.done => Alive
            // 句柄未落（spawn 在飞）= 期限豁免（修法⑥）：行龄超宽限仍未落 ⇒
            // 降级普通判死路径（堵僵尸永久 Alive 反向缺陷）。
            case Some(p) if !p.handlePresent =>
              if ageMs >= 0L && ageMs < Defaults.BgDeathWatchHandleGraceMs then Alive
              else Dead("handle never landed")
            case Some(p) if !p.alive => Dead("process dead")
            case _                   => Alive
          }
    }
