package nebflow.core

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import nebflow.shared.{Defaults, NebflowLogger, PathUtil}

import java.nio.file.Files
import java.util.concurrent.TimeUnit

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * 记忆检索库自动入库守护（memory-recall 批 2026-10-22）。
 *
 * 作者令：「这个进库 应该要是我们使用stand自动的能力」——主 agent 会话消息落盘后
 * 自动增量进 `<dataRoot>/memory-db/`，无需人工跑 `tools/stand-recall --update`。
 *
 * == 挂接点选择（最小面裁定）==
 *
 * **独立驻留守护**（本对象，进程内单纤维），而非持久化路径埋点：会话落盘的调用面
 * 极广（`saveMessagesForSession` 十余处散在 AgentProcessing / AgentFinishTurn /
 * ExternalSessionRunner 等热路径），逐点埋事件 = 广侵入 + 高耦合；守护只在
 * GatewayMain 装配点起一个纤维，持久化路径**零改动**。触发形态 = 定时脏探测
 * （消息落盘事件总会在 30s 拍内的目录 mtime 上现形），不造新框架。
 *
 * == 单拍语义 ==
 *
 *   1. kill-switch（`nebflow.memoryRecall.enabled`，默认 true）关 ⇒ 零动作；
 *   2. 节流窗（默认 ≥5 分钟，按**尝试**计——失败也占窗，下窗才重试 ⇒ 无重试风暴）；
 *   3. 脏探测：sessions/ 目录 stat 指纹（_index.json + 顶层 *.json + 各会话
 *      compaction 快照 json 的 mtime+size 摘要）与上次成功入库指纹相同 ⇒ 跳过
 *      （稳态每拍成本 = 数百次 stat，毫秒级；子进程零开销）；
 *   4. 执行：`python3 <stand-recall> --update --source auto --data-root <数据根>`
 *      （幂等 upsert，实测 ~3s / 10140 条库；超时 300s 杀进程按失败计）。
 *
 * == 数据根与排除面 ==
 *
 * 数据根 = `PathUtil.dataRoot`（CLI --home / STAND_HOME / NEBFLOW_HOME / 默认
 * ~/.stand，[[resolveToolPath]] 与 --data-root 均逐拍现读 ⇒ 隔离实例只进自己的
 * 根）；排除面（外部执行器 / team 子代理会话不入库）由 stand-recall 既有分类
 * 规则单点承载，守护不复制第二份口径。
 *
 * == 失败纪律（静默重试不吵日志）==
 *
 * 首败 WARN 一行（带原因 + 输出尾），连败降 debug；成功复位。stand-recall 侧
 * 幂等 ⇒ 任何一步失败的最坏后果都是「入库略陈旧」，下窗自愈，绝不污染主链路。
 */
object MemoryRecallDaemon:

  private val logger = NebflowLogger.forName("nebflow.memory-recall")

  /** 子进程输出里的计数行（tools/stand-recall --update 的固定首行形态）。 */
  private val IndexedLine = """indexed: \+(\d+) msgs \((\d+) total\)""".r

  // ── 状态与单拍结果（spec 可观测面）─────────────────────────────────────

  /**
   * 守护簿记。`lastAttemptMs` 只在**真实尝试**（发起子进程）时推进；NoTool /
   * NoData / Throttled / CleanSkip 不占窗（前两者是可自愈的部署/数据条件，
   * 下拍即可恢复探测）。
   */
  final case class State(
    lastAttemptMs: Long,
    lastSuccessFingerprint: Option[String],
    warnedNoTool: Boolean = false,
    consecutiveFailures: Int = 0
  )

  object State:
    val empty: State = State(0L, None)

  /** Spec 用：新建独立簿记 Ref（与生产纤维互不共享）。 */
  private[core] def unsafeState: Ref[IO, State] = Ref.unsafe[IO, State](State.empty)

  /** 单拍结果——spec 断言面 + 可观测语义（每档一义）。 */
  enum TickOutcome:
    /** kill-switch 关（`nebflow.memoryRecall.enabled=false`）。 */
    case Disabled

    /** stand-recall 脚本不可解析（三处候选全落空；每进程首见 WARN 一行）。 */
    case NoTool

    /** sessions/_index.json 不存在（全新数据根尚无会话）——无可入库面。 */
    case NoData

    /** 节流窗内（距上次尝试 < minIntervalMs）——跳过。 */
    case Throttled

    /** 脏探测指纹无变化——跳过（零子进程）。 */
    case CleanSkip

    /** 入库成功：本窗新增消息数 / 库内总消息数 / 耗时 ms。 */
    case Updated(newMsgs: Long, totalMsgs: Long, durationMs: Long)

    /** 入库失败（已占节流窗，下窗静默重试）：`reason` 含退出码/超时 + 输出尾。 */
    case Failed(reason: String)
  end TickOutcome

  // ── 工具脚本解析（三处候选，优先级降序）────────────────────────────────

  /**
   * stand-recall 脚本定位：
   *   1. system prop `nebflow.memoryRecall.toolPath`（显式覆盖——打包安装 /
   *      spec 注入假脚本）；
   *   2. `<dataRoot>/tools/stand-recall`（数据根 tools 惯例，~/.stand/bin/rg
   *      同款：隔离实例的根下只落自己的工具）；
   *   3. `<user.dir>/tools/stand-recall`（仓内开发形态：sbt run / 测试的进程
   *      cwd = 仓根，与生产实例实测形态一致）。
   */
  def resolveToolPath(dataRoot: os.Path): Option[os.Path] =
    val candidates =
      sys.props.get("nebflow.memoryRecall.toolPath").map(os.Path(_, os.pwd)).toList :::
        List(dataRoot / "tools" / "stand-recall") :::
        List(os.Path(sys.props.getOrElse("user.dir", "."), os.pwd) / "tools" / "stand-recall")
    candidates.find(os.exists)

  // ── 脏探测指纹（sessions/ 目录 stat 摘要）───────────────────────────────

  /**
   * sessions/ 增量面指纹：`_index.json` + 顶层 `*.json` + 各会话 compaction
   * 目录下 `*-before.json` 快照的 `name:mtime:size` 摘要串。消息落盘 / 会话新建 /
   * 压缩快照落盘任一发生 ⇒ 指纹必变（append-only 面 mtime 单调）。**目录缺
   * _index.json = None**（无会话面，无可入库）。遍历异常 → 返回「必然不同」的
   * 哨兵串（宁可多跑一次幂等 --update，不让探测故障静默卡死入库）。
   */
  def sessionsFingerprint(root: os.Path): Option[String] =
    val sessions = root / "sessions"
    val idx = sessions / "_index.json"
    if !os.exists(idx) then None
    else
      try
        val parts = collection.mutable.ArrayBuffer.newBuilder[String]
        parts += statPart(idx)
        for p <- os.list(sessions).toList.sortBy(_.last) do
          if p.last.endsWith(".json") then parts += statPart(p)
          else if os.isDir(p) then
            val comp = p / "compaction"
            if os.exists(comp) then
              for c <- os.list(comp).toList.sortBy(_.last) if c.last.endsWith("-before.json") do parts += statPart(c)
        Some(parts.result().mkString("|"))
      catch case _: Throwable => Some("<fs-unstable>")

  end sessionsFingerprint

  private def statPart(p: os.Path): String =
    s"${p.last}:${os.mtime(p)}:${os.size(p)}"

  // ── 子进程执行 ──────────────────────────────────────────────────────────

  /**
   * 跑一次 `--update --source auto`。输出重定向到临时文件（子进程秒级、输出
   * ~3 行——文件中转杜绝管道满阻塞，超时杀进程无悬挂读线程）。成功解析
   * `indexed:` 计数行回填 [[TickOutcome.Updated]]；非零退出 / 超时 = 左值原因
   * （输出尾随行一并带上，诊断不靠猜）。
   */
  private[core] def runUpdate(tool: os.Path, dataRoot: os.Path): IO[Either[String, (Long, Long)]] =
    IO.blocking {
      val out = Files.createTempFile("stand-recall-update", ".log")
      try
        val pb = new ProcessBuilder(
          "python3",
          tool.toString,
          "--update",
          "--source",
          "auto",
          "--data-root",
          dataRoot.toString
        )
        pb.redirectErrorStream(true)
        pb.redirectOutput(out.toFile)
        pb.environment().put("STAND_DATA_ROOT", dataRoot.toString)
        val proc = pb.start()
        val finished = proc.waitFor(Defaults.MemoryRecallTimeoutSec.toLong, TimeUnit.SECONDS)
        if !finished then
          proc.destroyForcibly()
          Left(s"timeout after ${Defaults.MemoryRecallTimeoutSec}s")
        else
          val text =
            try Files.readAllLines(out).asScala.mkString("\n")
            catch case _: Throwable => ""
          val code = proc.exitValue()
          if code == 0 then
            IndexedLine.findFirstMatchIn(text) match
              case Some(m) => Right((m.group(1).toLong, m.group(2).toLong))
              case None =>
                // 退出码 0 但计数行缺失（脚本未来改版）：入库本身成功，计数面退化
                Right((-1L, -1L))
          else Left(s"exit $code: ${text.linesIterator.toList.takeRight(2).mkString(" | ")}")
        end if
      finally Files.deleteIfExists(out)
      end try
    }

  // ── 单拍（launch 纤维驱动；spec 可逐步直驱）────────────────────────────

  /**
   * 单拍：门序 = kill-switch → 节流 → 工具解析 → 脏探测 → 执行。`dataRoot`
   * 逐拍现读（spec 换根即时生效）。本方法**绝不抛出**（内部全失败封左）；
   * 上层 launch 纤维另有 handleError 兜底（纤维自愈，WakeSensor 同款）。
   */
  def tickRun(state: Ref[IO, State], minIntervalMs: Long): IO[TickOutcome] =
    for
      now <- IO(System.currentTimeMillis())
      st <- state.get
      outcome <-
        if !Defaults.MemoryRecallEnabled then IO.pure(TickOutcome.Disabled)
        else if now - st.lastAttemptMs < minIntervalMs then IO.pure(TickOutcome.Throttled)
        else
          val dataRoot = PathUtil.dataRoot
          IO(resolveToolPath(dataRoot)).flatMap {
            case None =>
              // 每进程首见 WARN 一行（部署面缺陷值得看见），连见降 debug——不吵。
              val first = !st.warnedNoTool
              val logLine =
                if first then
                  logger.warn(
                    s"[memory-recall] stand-recall script not found (tried prop " +
                      "nebflow.memoryRecall.toolPath / <dataRoot>/tools/ / <cwd>/tools/) — " +
                      "auto ingestion dormant until it appears"
                  )
                else logger.debug("[memory-recall] stand-recall script still not found — dormant")
              logLine *> state.set(st.copy(warnedNoTool = true)).as(TickOutcome.NoTool)
            case Some(tool) =>
              IO.blocking(sessionsFingerprint(dataRoot)).flatMap {
                case None => IO.pure(TickOutcome.NoData)
                case Some(fp) if st.lastSuccessFingerprint.contains(fp) => IO.pure(TickOutcome.CleanSkip)
                case Some(fp) =>
                  // 尝试即占窗（先记后跑）：失败也等下一窗——静默重试不风暴。
                  state.set(st.copy(lastAttemptMs = now)) *>
                    runUpdate(tool, dataRoot).timed.flatMap { case (dur, res) =>
                      res match
                        case Right((n, total)) =>
                          (if n >= 0 then
                             logger.debug(
                               s"[memory-recall] auto --update: +$n msgs ($total total) in ${dur.toMillis}ms"
                             )
                           else
                             logger.debug(s"[memory-recall] auto --update ok (${dur.toMillis}ms, counts unavailable)")
                          ) *>
                            state
                              .set(
                                State(now, Some(fp), warnedNoTool = st.warnedNoTool, consecutiveFailures = 0)
                              )
                              .as(TickOutcome.Updated(n, total, dur.toMillis))
                        case Left(reason) =>
                          val fails = st.consecutiveFailures + 1
                          val line =
                            s"[memory-recall] auto --update failed (attempt $fails): $reason"
                          (if fails == 1 then logger.warn(line)
                           else logger.debug(line)) *>
                            state
                              .set(
                                State(now, st.lastSuccessFingerprint, st.warnedNoTool, fails)
                              )
                              .as(TickOutcome.Failed(reason))
                    }
              }
          }
    yield outcome

  // ── 驻留纤维（GatewayMain 装配点入口）──────────────────────────────────

  /**
   * 启动守护纤维（返回即后台运行；JVM 生命周期即纤维生命周期，WakeSensor 同款
   * 形态）。kill-switch 关 = 零纤维（装配点空转）。测试经 `(tick, minIntervalMs)`
   * 注入短拍短窗；生产取 [[Defaults.MemoryRecallTickSec]] /
   * [[Defaults.MemoryRecallMinIntervalMs]]。
   */
  def launch(
    tick: Duration = Defaults.MemoryRecallTickSec.seconds,
    minIntervalMs: Long = Defaults.MemoryRecallMinIntervalMs
  ): IO[Unit] =
    if !Defaults.MemoryRecallEnabled then IO.unit else spawn(tick, minIntervalMs).void

  /**
   * [[launch]] 的可观测形态：返回纤维句柄（spec 取消用；生产无人持 fiber）。
   * `onTick` = 每拍结果探针（spec 用——生产传 None 零开销）。
   */
  private[core] def spawn(
    tick: Duration = Defaults.MemoryRecallTickSec.seconds,
    minIntervalMs: Long = Defaults.MemoryRecallMinIntervalMs,
    onTick: TickOutcome => Unit = _ => ()
  ): IO[cats.effect.Fiber[IO, Throwable, Unit]] =
    val state = Ref.unsafe[IO, State](State.empty)
    // 首拍即 boot 补扫（state 初值 lastAttempt=0 ⇒ 不受节流）：停机窗内落盘的
    // 消息在启动后 30s 内补齐——与 taskIndexBackfill「启动补扫」同语义。
    def loop: IO[Unit] =
      IO.sleep(tick) *>
        tickRun(state, minIntervalMs)
          .flatTap(out => IO(onTick(out)))
          .handleErrorWith(e =>
            logger.warn(
              s"[memory-recall] tick failed (fiber self-heals): ${Option(e.getMessage).getOrElse(e.toString)}"
            )
          ) *>
        IO.defer(loop)
    logger.debug("[memory-recall] daemon started") *> loop.start
  end spawn
end MemoryRecallDaemon
