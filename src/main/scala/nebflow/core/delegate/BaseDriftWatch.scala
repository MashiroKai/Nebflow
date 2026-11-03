package nebflow.core.delegate

import cats.effect.IO
import cats.syntax.all.*
import nebflow.core.AgentRuntimePort
import nebflow.core.GitWorktrees
import nebflow.shared.{NebflowLogger, PathUtil}

/**
 * BaseDriftWatch —— **main 漂移通知**（引擎会话治理专项 · 缺陷四，作者令 2026-11-03）：
 * 在飞 delegate 的基分支（`baseBranch`，通常 main）被别人推进后，**向在飞 delegate
 * 会话注入一条 `[base-moved] old→new` 提示（含涉及文件列表）**，让它知道「脚下的
 * 基线动了」；同一 tip 只通知一次（节流 = 状态文件里的 last-notified tip）。
 *
 * 病灶：改前引擎对 base tip 的漂移**零感知**——delegate 在自己的 worktree 里埋头干，
 * 基线被别人合并了几轮也不吭声，直到它自己合并时才发现冲突/被覆盖。
 *
 * 通道：复用既有会话注入面 [[DelegateNotify.inject]]（ImmediateInput → idle 分支转
 * UserInput，零新机制）；目标会话不 live（已收尾/死亡）= 零动作（不是本通道的活）。
 *
 * 调度：搭既有扫描拍（[[nebflow.core.processor.TaskStuckWatcher.scan]] 拍尾，零新增
 * 定时器纪律）；状态落 `<dataRoot>/base-drift.json`（id → tip），重启后节流照旧。
 */
object BaseDriftWatch:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.base-drift")

  /** 单条通知里最多列出的文件数（防提示词膨胀）。 */
  val MaxFiles: Int = 20

  private def statePath: os.Path = PathUtil.dataRoot / "base-drift.json"

  /** 端口（生产 = [[fromResources]]；测试 = 假端口注入全部 IO 面）。 */
  trait Port:
    def delegates: IO[List[DelegateRegistry.Record]]
    def liveSessionIds: IO[Set[String]]
    def headTip(workspace: String, branch: String): Option[String]
    def changedFiles(workspace: String, from: String, to: String): List[String]
    def notify(sessionId: String, text: String): IO[Boolean]
    def loadState: IO[Map[String, String]] = IO.blocking(BaseDriftWatch.readState())
    def saveState(state: Map[String, String]): IO[Unit] = IO.blocking(BaseDriftWatch.writeState(state))

  /** 生产端口（AgentRuntimePort 装配；git 读数走 GitWorktrees）。 */
  def fromResources(resources: AgentRuntimePort): Port = new Port:
    def delegates = IO.blocking(DelegateRegistry.list()).handleErrorWith(_ => IO.pure(Nil))
    def liveSessionIds = resources.agentRegistry.get.map(_.keySet)
    def headTip(workspace: String, branch: String): Option[String] =
      scala.util.Try(os.Path(workspace)).toOption.flatMap(ws => GitWorktrees.headShaOfBranch(ws, branch))
    def changedFiles(workspace: String, from: String, to: String): List[String] =
      scala.util.Try(os.Path(workspace)).toOption
        .map(ws => GitWorktrees.changedFilesBetween(ws, from, to).take(MaxFiles))
        .getOrElse(Nil)
    def notify(sessionId: String, text: String): IO[Boolean] =
      DelegateNotify.inject(resources.agentRegistry, sessionId, text)

  /** 通知文案（模型可见；`[base-moved] old→new` 前缀 = 作者令的 grep 锚）。 */
  def message(delegateId: String, baseBranch: String, oldTip: String, newTip: String, files: List[String]): String =
    val filePart =
      if files.isEmpty then "(no file list available)"
      else files.mkString("\n  - ", "\n  - ", "")
    s"""[base-moved] $baseBranch advanced $oldTip → $newTip while this task is in flight.
       |
       |Files touched by the move:$filePart
       |
       |What this means: the base branch you were branched from has moved. Before you finish, re-check that your work still applies cleanly onto the new tip (rebase or merge `$baseBranch` into your branch); if the move conflicts with your plan, say so in your report instead of merging over it.""".stripMargin

  /**
   * 单拍：对每个在飞 delegate 比对 base tip 与上次记账。
   *   - 首次见（无记账）⇒ 只记账（baseline，不通知——不知道「从哪来」）；
   *   - tip 变了 ∧ 会话 live ⇒ 注入一次 + 记账到新 tip（同一 tip 只通知一次）；
   *   - 会话不 live ⇒ 记账（避免会话被唤醒后再补发一条过期漂移）。
   */
  def scan(port: Port): IO[List[String]] =
    for
      recs <- port.delegates
      live <- port.liveSessionIds
      prev <- port.loadState
      tracked = recs.filter(r =>
        r.archivedAt.isEmpty && r.cancelRequestedAt.isEmpty &&
          r.branch.isDefined && r.baseBranch.isDefined && live.contains(r.id)
      )
      pairs = tracked.flatMap { r =>
        for
          branch <- r.baseBranch
          ws <- r.projectWorkspace.orElse(Some(r.cwd))
          tip <- port.headTip(ws, branch)
        yield (r, ws, branch, tip)
      }
      drifted = pairs.collect {
        case (r, ws, branch, tip) if prev.contains(r.id) && prev(r.id) != tip =>
          (r, ws, branch, prev(r.id), tip)
      }
      notices <- drifted.traverse { case (r, ws, branch, oldTip, newTip) =>
        val files = port.changedFiles(ws, oldTip, newTip)
        port
          .notify(r.id, message(r.id, branch, oldTip, newTip, files))
          .map(ok => if ok then Some(s"${r.id}: $branch $oldTip→$newTip (${files.size} file(s))") else None)
          .handleErrorWith(e => logger.warn(s"[base-drift] notify failed for ${r.id}: ${e.getMessage}").as(None))
      }
      // 记账：所有跟踪项写到当前 tip（首次见 = baseline；已通知 = 节流锚）。
      next = pairs.foldLeft(prev) { case (m, (r, _, _, tip)) => m.updated(r.id, tip) }
      _ <- port.saveState(next)
    yield notices.flatten

  // ── 状态文件（简单文本：`<delegateId> <tip>` 逐行）────────────────────────

  private def readState(): Map[String, String] =
    val p = statePath
    if !os.exists(p) then Map.empty
    else
      os.read
        .lines(p)
        .iterator
        .map(_.trim)
        .filter(_.nonEmpty)
        .flatMap { line =>
          line.split("\\s+", 2) match
            case Array(k, v) if k.nonEmpty && v.nonEmpty => Some(k -> v)
            case _                                        => None
        }
        .toMap

  private def writeState(state: Map[String, String]): Unit =
    try
      os.makeDir.all(statePath / os.up)
      os.write.over(statePath, state.toList.sortBy(_._1).map((k, v) => s"$k $v").mkString("", "\n", "\n"))
    catch case e: Throwable => logger.warn(s"[base-drift] state write failed: ${e.getMessage}")

end BaseDriftWatch
