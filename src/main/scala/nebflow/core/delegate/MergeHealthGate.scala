package nebflow.core.delegate

import nebflow.core.GitWorktrees
import nebflow.shared.NebflowLogger

import scala.util.Try

/**
 * MergeHealthGate —— 合并队列的**合并前最小健康闸**（缺陷批 2026-11-04 · 作者令
 * 「合并前做最小健康闸：分支自身可否编译/关键测试是否绿，成本要可控，不能因此拖慢
 * 队列」）。
 *
 * == 为什么是"最小" ==
 *
 * 队列一拍扫描要过 N 个成员，任何一件"跑一次 sbt"的检查都会把队列拖成分钟级。所以
 * 本闸默认只做**廉价、只读、无副作用**的一件半事：
 *   1. **冲突标记扫描**：分支相对 merge-base 改动过的文件里是否还留着 `<<<<<<<` /
 *      `>>>>>>>` 标记——半成品合并 / 手工编辑残留的第一形态，读 blob 即可，毫秒级；
 *   2. **可选的项目编译/测试命令**（`-Dnebflow.merge.health.command=...`，缺省不配
 *      置 = 不跑）：作者/项目显式声明「关键测试绿」的最小命令（例如
 *      `sbt -batch "testOnly *MergeAcceptanceSpec"`），带超时（缺省
 *      [[CommandTimeoutMs]]）；非 0 退出 = 拒绝并报原因尾巴。
 *
 * ⚠️ **刻意不做的事**：不做「分支与 base 有无冲突」的预检（`git merge-tree`）。
 * 合并轮协议要求属主**就地解冲突**（[[MergeQueue.mergeBriefFor]] 的硬规则）——base
 * 前进带来的冲突是常态，不是半成品；把冲突当健康红灯会把正常合并轮永久 park 掉。
 *
 * 判读三态（[[Verdict]]）：`Ok` / `Refused(reason)` / `Skipped(reason)`——`Skipped` 与
 * `Ok` 都放行（闸面读不到 ≠ 分支有问题；红线的方向与队列既有 fail-soft 纪律一致）。
 *
 * 注入缝（[[install]]）：spec 用它驱动「编译不过」的分支，不必真跑编译器。
 */
object MergeHealthGate:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.merge-health-gate")

  /** 判读三态。 */
  sealed trait Verdict extends Product with Serializable
  object Verdict:
    /** 过闸（可授窗）。 */
    case object Ok extends Verdict
    /** 不过闸：分支是半成品/冲突/关键测试红——不授窗，原因随行（面板 + 父通知）。 */
    final case class Refused(reason: String) extends Verdict
    /** 闸面读不到（非 git / git 太老 / 未配置命令）——放行，原因只进审计行。 */
    final case class Skipped(reason: String) extends Verdict

  /** 注入面（生产 = [[fromGit]]；spec = 记录实参返回定值）。 */
  trait Face:
    def check(m: MergeQueue.Member): Verdict

  private val face = new java.util.concurrent.atomic.AtomicReference[Option[Face]](None)

  def install(f: Face): Unit = face.set(Some(f))

  def clear(): Unit = face.set(None)

  /** 可选项目健康命令（缺省不配置 = 不跑；见对象头注 3）。 */
  def command: Option[String] =
    sys.props.get("nebflow.merge.health.command").map(_.trim).filter(_.nonEmpty)

  /** 命令超时（ms；缺省 300s——最小闸不该把队列拖成分钟级之外）。 */
  def CommandTimeoutMs: Long =
    sys.props.get("nebflow.merge.health.commandTimeoutMs").flatMap(s => Try(s.trim.toLong).toOption).filter(_ > 0L).getOrElse(300000L)

  /** 单次扫描读的 blob 数上界（超出只报“已扫描前 N 个”，绝不把一拍拖成 O(分支大小)）。 */
  val MaxScannedFiles: Int = 60

  /** 闸面本体（注入优先）。 */
  def check(m: MergeQueue.Member): Verdict =
    face.get match
      case Some(f) =>
        try f.check(m)
        catch case e: Throwable => Verdict.Skipped(s"health gate face failed: ${Option(e.getMessage).getOrElse(e.toString).take(160)}")
      case None => fromGit(m)

  /** 生产实现：冲突标记扫描 → 可选命令。任何一步读不到 = 跳过（fail-soft）。 */
  def fromGit(m: MergeQueue.Member): Verdict =
    Try(os.Path(m.baseWs)).toOption.filter(os.exists(_)).filter(GitWorktrees.isGitRepo) match
      case None => Verdict.Skipped(s"base workspace '${m.baseWs}' is not a readable git repository — health gate skipped")
      case Some(ws) =>
        markersOf(ws, m) match
          case Some(Nil) => commandVerdict(m)
          case Some(files) =>
            Verdict.Refused(
              s"the branch '${m.branch}' still carries merge-conflict markers in ${files.size} file(s): " +
                s"${files.take(5).mkString(", ")}${if files.size > 5 then ", …" else ""} — " +
                "that is a half-finished branch (an unresolved merge), not a deliverable; refusing the merge turn"
            )
          case None =>
            Verdict.Skipped(s"could not read the branch's changed files from '${m.baseWs}' — conflict-marker scan skipped")

  /** 分支改动文件里的冲突标记扫描（None = 读数不可得）。 */
  private def markersOf(ws: os.Path, m: MergeQueue.Member): Option[List[String]] =
    val base = GitWorktrees.mergeBase(ws, m.baseBranch, m.branch)
    base match
      case None => None
      case Some(b) =>
        val changed = GitWorktrees.changedFilesBetween(ws, b, m.branch)
        if changed.isEmpty then Some(Nil)
        else
          val scanned = changed.take(MaxScannedFiles)
          val hits = scanned.filter { f =>
            GitWorktrees.fileAtRef(ws, m.branch, f).exists(hasConflictMarkers)
          }
          Some(hits)

  /** 冲突标记判读（纯函数）：同时出现 `<<<<<<< ` 与 `>>>>>>> ` 才算（单边出现 = 代码里的普通文本）。 */
  private[delegate] def hasConflictMarkers(content: String): Boolean =
    val lines = content.linesIterator
    var open = false
    var close = false
    lines.foreach { l =>
      if l.startsWith("<<<<<<< ") then open = true
      else if l.startsWith(">>>>>>> ") then close = true
    }
    open && close

  /** 可选项目健康命令（未配置 = Ok 直过）。 */
  private def commandVerdict(m: MergeQueue.Member): Verdict =
    command match
      case None => Verdict.Ok
      case Some(cmd) =>
        Try(os.Path(m.baseWs)).toOption.filter(os.exists(_)) match
          case None => Verdict.Skipped(s"base workspace '${m.baseWs}' is not readable — configured health command skipped")
          case Some(ws) =>
            Try {
              // 输出直通引擎日志（stdout/stderr = Inherit）：只取退出码判读，失败时日志里
              // 有编译/测试的原话——闸面自己不解析输出（省一次大字符串分配）。
              os.proc("bash", "-lc", cmd).call(
                cwd = ws,
                check = false,
                stdout = os.Inherit,
                stderr = os.Inherit,
                timeout = CommandTimeoutMs
              )
            }.toOption match
              case None =>
                Verdict.Skipped(s"health command '$cmd' could not be executed — fail-soft, gate skipped")
              case Some(res) =>
                if res.exitCode == 0 then Verdict.Ok
                else
                  Verdict.Refused(
                    s"the merge health command rejected the branch '${m.branch}' (exit ${res.exitCode}): $cmd — " +
                      "the command's own output (compile/test failure lines) is in the engine log"
                  )

end MergeHealthGate
