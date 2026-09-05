package nebflow.core.project

import cats.effect.IO
import cats.syntax.all.*
import nebflow.core.NebflowLogger
import nebflow.core.PathUtil

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.concurrent.duration.*

/** 产物完整性闸门（worktree 审计 20260905 机制建议落地）——节点 completed 出口的
  * worktree 三合法态校验，堵「节点自报 completed 但产物滞留（沙箱 EPERM 未 commit
  * 且未申报，或无人接手落地）」的静默丢失。
  *
  * 合法出口（作者规格，三选一）：
  *  - '''a. 正常已提交'''：分支领先 main ≥1 commit 且 worktree 干净（status
  *    --porcelain 零行）；
  *  - '''b. commit-ready 合法形态'''：worktree 有未提交改动，但节点结果文本显式
  *    申报 commit-ready。识别契约：结果文本命中正则 `commit[-\s_]?ready`（大小写
  *    不敏感）——本仓 EPERM 批结果一律用「commit-ready 申报」措辞，该词即闸门识别
  *    的契约标记；'''「宿主落地命令」字样不作为充分标记'''（已正常提交批的报告
  *    归档段也常含该词，仅凭它会误放行滞留节点）；
  *  - '''c. 零改动'''：领先 0 且 worktree 干净（纯调查/只读任务）。
  *
  * 三者皆不满足（典型：有脏文件且未申报；含 ahead≥1 但残留未申报脏文件）→
  * Reject——调用方（NodeEngine.completeNode）转 blocked（复用既有 BLOCKED 反馈
  * 协议），blockedFeedback 标注「产物滞留未申报」+ 诊断，不走 out 投递、结果不
  * 丢弃，由分发器/Nebula 按 blocked 重入协议处置。held → release 的 completed
  * 转移（releaseNode）同样接闸（release 不复用 completeNode，非单一咽喉）。
  *
  * 轻量与安全边界：
  *  - git 一律 `--no-optional-locks -C <dir>`（只读不建 index.lock）；默认 runner
  *    在 IO.blocking 执行，不占 actor/WS 线程；整体 15s 超时兜底；
  *  - 脏检测 = status --porcelain（含 untracked——未跟踪的新文件恰是最易丢的产物，
  *    必须算脏；.gitignore 内文件自然不可见）；
  *  - 分支领先数 = rev-list --count main..HEAD；分离 HEAD / rev-list 失败按 0
  *    （ahead 只用于 Pass 理由标注与 Reject 诊断，不构成独立放行条件——闸门硬
  *    条件只有「脏且未申报」）；
  *  - 无 worktree（直接作答型）→ 跳过；worktree 目录已不存在 → 跳过；worktree
  *    值经 PathUtil.resolveNodeProjectRoot 解析（与节点运行时 cwd 同源，参照系
  *    唯一）；解析回退为 workspace 本体（损坏存储值）→ 跳过（防误检主仓脏面）；
  *  - '''fail-open'''：git 命令失败 / 闸门自身异常或超时 → warn 日志 + 放行——
  *    闸门故障不得卡死正常完成链；
  *  - 生效边界：只对「未来的 completed 转移」生效，不追溯已完成/已归档节点；
  *    需宿主重启加载新 jar 后生效；
  *  - kill-switch：system prop `nebflow.completionGate.disabled=true`（默认关）；
  *    彻底回滚 = revert 单 commit。
  */
object CompletionGate:

  private val logger = NebflowLogger.forName("nebflow.completion.gate")

  /** commit-ready 申报识别契约（大小写不敏感；`commit-ready` / `commit ready` /
    * `commit_ready` / `commitready` 均命中）。 */
  val MarkerRegex = "(?i)commit[-\\s_]?ready".r

  /** Reject 落 blockedFeedback 的 category（语义化新值）：前端 flowViewers.js 对
    * fb.category 直接 esc() 透传渲染、无封闭枚举 label 映射（实读核实），新值
    * 安全；BlockedReader.Categories 只约束 agent 自报 BLOCKED JSON 的解析归一，
    * 闸门自构 feedback 不经该归一。 */
  val CategoryArtifactResidue = "artifact-residue"

  /** status 样本截取行数（诊断里最多带前 10 行，防 feedback 串膨胀）。 */
  val StatusSampleLines = 10

  /** gate 单次执行整体超时（含两条 git 命令）；超时按 fail-open 放行。 */
  private val GateTimeout: FiniteDuration = 15.seconds

  /** git 只读执行器：(cwd, git 子命令参数) → stdout（exit 0）或错误摘要（非 0）。
    * 可注入（spec stub）；默认实现 ProcessBuilder + IO.blocking，命令恒带
    * `--no-optional-locks -C <cwd>` 前缀（只读不建 index.lock）。 */
  type GitRunner = (String, List[String]) => IO[Either[String, String]]

  val defaultRunner: GitRunner = (cwd, args) => IO.blocking {
    val pb = new java.lang.ProcessBuilder(("git" :: "--no-optional-locks" :: "-C" :: cwd :: args)*)
    pb.redirectErrorStream(true)
    pb.redirectInput(java.lang.ProcessBuilder.Redirect.INHERIT)
    val p = pb.start()
    val out = new String(p.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    val code = p.waitFor()
    if code == 0 then Right(out.trim)
    else Left(s"git ${args.mkString(" ")} exit=$code: ${out.trim.take(300)}")
  }

  /** 闸门裁决：Pass 放行（reason 进 debug/诊断日志）；Reject 拒绝进 completed。 */
  sealed trait Verdict
  final case class Pass(reason: String) extends Verdict
  final case class Reject(reason: String, diagnostic: Diagnostic) extends Verdict

  /** Reject 诊断集（进 blockedFeedback）：ahead 数 / 脏文件数 / status 前 10 行样本 /
    * 标记检测结果。 */
  final case class Diagnostic(
    worktree: String,
    dir: String,
    aheadCount: Int,
    aheadFailed: Boolean,
    dirtyCount: Int,
    statusSample: List[String],
    markerHit: Boolean
  )

  /** kill-switch（默认关）：`nebflow.completionGate.disabled=true` → 闸门整体旁路
    * （恒 Pass）。每次调用现读 system prop（测试可即时翻转）。 */
  def disabled: Boolean =
    "true".equalsIgnoreCase(java.lang.System.getProperty("nebflow.completionGate.disabled", "false"))

  /** 主入口（纯决策 + 注入式 git 执行器）。worktree 传 NodeDef.worktree 原值
    * （裸名，Option）；workspace 传项目工作区绝对路径。 */
  def check(
    workspace: String,
    worktree: Option[String],
    resultText: String,
    runner: GitRunner = defaultRunner
  ): IO[Verdict] =
    val guarded: IO[Verdict] =
      if disabled then IO.pure(Pass("kill-switch (nebflow.completionGate.disabled=true)"))
      else
        worktree match
          case None => IO.pure(Pass("no-worktree (直接作答型节点，跳过)"))
          case Some(wt) =>
            // 参照系唯一：与节点运行时 cwd 同源解析（裸名 → worktrees/<名> 权威位置
            // 优先 / .nebflow/<名> 顶层 fallback / 缺失旧公式兜底）。解析回退为
            // workspace 本体 = 归一化拒绝（损坏存储值）→ 跳过，防误检主仓脏面。
            val root = PathUtil.resolveNodeProjectRoot(workspace, Some(wt))
            if root == workspace then
              IO.pure(Pass(s"worktree '$wt' unresolvable → workspace fallback, skip"))
            else if !Files.exists(Paths.get(root)) then
              IO.pure(Pass(s"worktree dir missing: $root, skip"))
            else
              dirtyFiles(root, runner).flatMap {
                case Left(err) => failOpen(s"git status failed: $err")
                case Right(dirty) if dirty.isEmpty =>
                  aheadOf(root, runner).map { a =>
                    if !a.failed && a.count > 0 then
                      Pass(s"committed: ahead main..HEAD=${a.count}, worktree clean")
                    else
                      Pass(s"zero-change: ahead=${a.count}${if a.failed then " (rev-list failed → 按 0)" else ""}, worktree clean")
                  }
                case Right(dirty) =>
                  val markerHit = MarkerRegex.findFirstIn(resultText).isDefined
                  if markerHit then
                    IO.pure(Pass(s"commit-ready declared (marker contract hit; ${dirty.size} dirty files accepted)"))
                  else
                    aheadOf(root, runner).map { a =>
                      Reject(
                        s"dirty worktree without commit-ready declaration (${dirty.size} files)",
                        Diagnostic(wt, root, a.count, a.failed, dirty.size, dirty.take(StatusSampleLines), markerHit))
                    }
              }
    guarded.timeout(GateTimeout).handleErrorWith(t =>
      failOpen(s"gate error: ${Option(t.getMessage).getOrElse(t.toString)}"))

  /** Reject → BlockedFeedback（blockedNode 落盘 + FeedbackRouter 重入 prompt 复用
    * 同一结构）。detail 必含「产物滞留未申报」+ 诊断（ahead 数/脏文件数/status
    * 前 10 行样本/标记检测结果）。 */
  def feedback(d: Diagnostic): BlockedFeedback =
    val sample = if d.statusSample.isEmpty then "(空)" else d.statusSample.mkString(" | ")
    BlockedFeedback(
      category = CategoryArtifactResidue,
      detail = s"产物滞留未申报：worktree '${d.worktree}'（${d.dir}）有 ${d.dirtyCount} 个未提交改动" +
        s"（领先 main ${d.aheadCount} commit${if d.aheadFailed then "，rev-list 失败按 0 计" else ""}），" +
        s"结果文本未命中 commit-ready 申报标记（markerHit=${d.markerHit}）。status 样本（前 ${d.statusSample.size} 行）：$sample",
      suggestion = "提交改动（git add + git commit）后按 blocked 重入协议重入；或在节点结果文本申报 commit-ready（字面标记，闸门识别契约）后重入")

  // ── 内部 ─────────────────────────────────────────────────────────

  private final case class Ahead(count: Int, failed: Boolean)

  /** 脏文件行（status --porcelain 非空行，含 untracked `??` 行）。 */
  private def dirtyFiles(dir: String, runner: GitRunner): IO[Either[String, List[String]]] =
    runner(dir, List("status", "--porcelain")).map {
      case Right(out) => Right(out.linesIterator.map(_.trim).filter(_.nonEmpty).toList)
      case Left(err)  => Left(err)
    }

  /** 分支领先数（worktree 内 HEAD = 其检出分支）；分离 HEAD / 无 main / 失败 →
    * (0, failed=true)（按 0 处理，作者规格）。 */
  private def aheadOf(dir: String, runner: GitRunner): IO[Ahead] =
    runner(dir, List("rev-list", "--count", "main..HEAD")).map {
      case Right(out) => out.trim.toIntOption match
        case Some(n) => Ahead(n, failed = false)
        case None    => Ahead(0, failed = true)
      case Left(_) => Ahead(0, failed = true)
    }

  /** fail-open（作者规格）：warn 日志 + 放行——闸门故障不得卡死正常完成链。 */
  private def failOpen(reason: String): IO[Verdict] =
    logger.warn(s"Completion gate fail-open: $reason") *>
      IO.pure(Pass(s"fail-open: $reason"))

end CompletionGate
