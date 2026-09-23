package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import nebflow.core.NebflowLogger
import nebflow.shared.{Defaults, PowerStateTracker, SleepWindow}

import scala.concurrent.duration.*

/**
 * 宿主睡眠/唤醒感知器（hostresume 批 2026-09-22，设计卡
 * `~/.nebflow/docs/Nebflow/20260921_235342_host-interrupt-resume-design__chain-n-494ba650.md`
 * §4 #2；作者「七项全照推荐」裁定 D-1 = A1 纯 JVM 双钟断流探测——零依赖、零子进程）。
 *
 * == 机制 ==
 * 15s 周期纤维（`Defaults.WakeSenseTickSec`）每拍成对采样 `(wallMs, nanoMs)`。macOS
 * 睡眠期 `System.nanoTime` 冻结而墙钟继续走（仓内既有平台知识，
 * `interface.scala:304-305` 注释自证），纤维本体也随进程冻结 ⇒ 唤醒后首拍：
 * Δwall ≫ Δnano，差额即冻结秒。判据（纯函数 [[judge]]）：
 *   `frozenMs = Δwall − Δnano > Defaults.WakeSenseSlopMs (45s)` ⇒ 睡眠窗
 *   `[wakeWall − frozenMs, wakeWall]`（尾置——对跨整窗活动跨度的总扣减量与真实冻结
 *   分布相同，见 judge 注）。45s = 3×15s 探测周期：吸收 DarkWake 突刺（取证实测 19s
 *   缝）与小幅 NTP 步进（Δwall≈Δnano ⇒ frozen≈0，卡 §6 口径 4/5）。
 *
 * == 命中后三件旁路物（卡 §2.3 唤醒面——零节点写、零重入、零重试，★②③④ 红线同款）==
 * ① `boot-wake.json` 台账 append：每在册项目两条（kind=sleep + kind=wake，恒
 *    blocking=false，[[powerEntries]] / `BootDispatcherWake.appendPowerMarker`）；
 * ② `PowerStateTracker` 窗集登记（`TaskStuckWatcher` 两轴 + 整流 no-progress 守卫的
 *    时间基修正消费，D-6 首批仅此两处）；
 * ③ `FlowMapEventLog` `host-wake` 审计事件（D-7：仅审计，不揽分发器）。
 *
 * 全部写点 fail-soft（单项目失败 WARN + 继续，不抛给纤维——纤维必须自愈，
 * `ProjectActor.ttlScanner` 同款 `IO.sleep *> work *> IO.defer(loop)` 形态）。
 * 开关 = `Defaults.WakeSenseEnabled`（launch 闸：false ⇒ 零新纤维零写点，逐字节现状）。
 */
object WakeSensor:

  private val logger = NebflowLogger.forName("nebflow.project.wake-sense")

  /** 单拍样本（墙钟 + 单调钟，同刻成对采集）。 */
  final case class Sample(wallMs: Long, nanoMs: Long)

  /**
   * 双钟断流判定（纯函数，卡 §4 #1「纯函数可独立单测」精神的探测半边）。
   *
   * `frozenMs = Δwall − Δnano`；`frozenMs > slop` ⇒ `Some(窗 [wakeWall − frozenMs, wakeWall])`。
   * 窗**尾置**（紧贴本拍 wakeAt）的口径：真实冻结分布是「区间内某段（含 DarkWake 突刺
   * 扣除）」，但消费点 `PowerStateMath.effectiveElapsed` 只做交集总时长减法——对任一
   * 跨越整个检测区间的活动跨度，尾置窗与真实分布的**总扣减量相同**（DarkWake 只减
   * 总量、不移判据）；对区间内起始的跨度，误差上界为检测区间内的清醒残段（≤ 探测
   * 周期级，方向保守——只影响本拍内新起跨度的扣减量，不影响既有在飞跨度的判据）。
   */
  private[project] def judge(prev: Sample, cur: Sample, slopMs: Long): Option[SleepWindow] =
    val wallDelta = cur.wallMs - prev.wallMs
    val nanoDeltaMs = (cur.nanoMs - prev.nanoMs) / 1000000L
    val frozenMs = wallDelta - nanoDeltaMs
    if frozenMs > slopMs then Some(SleepWindow(cur.wallMs - frozenMs, cur.wallMs))
    else None

  /**
   * boot 链挂载点（`GatewayMain` `projectTtlScanner` 邻位、server listen 前就绪；卡 §4 #3）。
   * 开关关闭 ⇒ `IO.unit`（零新纤维、零写点）。开启 ⇒ 后台纤维 `.start.void`
   * （ttlScanner 同款挂载形态），单拍失败 WARN 自愈不外抛。
   */
  def launch: IO[Unit] =
    if !Defaults.WakeSenseEnabled then IO.unit
    else
      val state = Ref.unsafe[IO, Option[Sample]](None)
      def loop: IO[Unit] =
        IO.sleep(Defaults.WakeSenseTickSec.seconds) *>
          tick(state).handleErrorWith(e =>
            logger.warn(s"[wake-sense] tick failed (fiber self-heals): ${Option(e.getMessage).getOrElse(e.toString)}")
          ) *>
          IO.defer(loop)
      loop.start.void

  /**
   * 单拍：采样 → 与上一拍判定 → 命中则窗集登记 + 台账 + 事件。先换基线再判定失败
   * 也不拖垮下一拍。生产由 launch 纤维驱动；测试经自建 state Ref 逐步驱动。
   */
  private[project] def tick(state: Ref[IO, Option[Sample]]): IO[Unit] =
    IO(Sample(System.currentTimeMillis(), System.nanoTime())).flatMap { cur =>
      state.get.flatMap {
        case None => state.set(Some(cur))
        case Some(prev) =>
          state.set(Some(cur)) *>
            judge(prev, cur, Defaults.WakeSenseSlopMs).traverse_ { w =>
              // 窗集登记是同步 AtomicReference 面（PowerStateTracker 设计如此——纯函数
              // 消费、无 cats-effect 依赖越层），在 IO 语境显式包裹。
              IO(PowerStateTracker.registerSleepWindow(w.sleepAtMs, w.wakeAtMs)) *>
                announce(w, prev, cur) *>
                logger.info(
                  s"[wake-sense] host sleep window detected: sleepAt=${w.sleepAtMs} wakeAt=${w.wakeAtMs} " +
                    s"frozenMs=${w.durationMs} (wallDelta=${cur.wallMs - prev.wallMs}ms, " +
                    s"nanoDelta=${(cur.nanoMs - prev.nanoMs) / 1000000L}ms, slop=${Defaults.WakeSenseSlopMs}ms)"
                )
            }
      }
    }

  /**
   * 命中后旁路物①③：每在册项目台账对（kind=sleep + kind=wake，恒 blocking=false）
   * + 一条 `host-wake` 审计事件。任一面失败 fail-soft（WARN + 继续），不抛给纤维。
   */
  private def announce(w: SleepWindow, prev: Sample, cur: Sample): IO[Unit] =
    val bootId = BootDispatcherWake.instanceId
    val wallDeltaMs = cur.wallMs - prev.wallMs
    val nanoDeltaMs = (cur.nanoMs - prev.nanoMs) / 1000000L
    ProjectStore
      .list()
      .flatMap { pds =>
        pds.traverse_ { pd =>
          powerEntries(w, bootId, pd.name)
            .traverse_(e => BootDispatcherWake.appendPowerMarker(BootDispatcherWake.markerPath(pd), pd.name, e)) *>
            FlowMapEventLog
              .append(
                pd.workspace,
                pd.name,
                bootId,
                FlowMapEventLog.HostWakeType,
                FlowMapEventLog.hostWakeSummary(
                  bootId,
                  w.sleepAtMs,
                  w.wakeAtMs,
                  w.durationMs,
                  wallDeltaMs,
                  nanoDeltaMs,
                  Defaults.WakeSenseSlopMs
                )
              )
              .handleErrorWith(e =>
                logger.warn(s"[wake-sense] event append failed for project '${pd.name}': ${e.getMessage}")
              )
        }
      }
      .handleErrorWith(e =>
        logger.warn(s"[wake-sense] announce failed (fail-soft): ${Option(e.getMessage).getOrElse(e.toString)}")
      )

  end announce

  /**
   * 台账两条目（纯函数可单测，卡 §6 口径 3 的形态半边）：kind 标注 + `blocking=false`
   * 恒 + sleepAt/wakeAt 载荷。append 走 `BootDispatcherWake.appendPowerMarker`
   * （append-only、不做 (bootId, project) 替换——替换语义若跨 kind 会让电源条目顶掉
   * 同 boot 的 blocking boot 条目、破坏 duplicate-boot 幂等）。
   */
  private[project] def powerEntries(
    w: SleepWindow,
    bootId: String,
    project: String
  ): List[BootDispatcherWake.MarkerEntry] =
    List(
      BootDispatcherWake.MarkerEntry(
        bootId = bootId,
        project = project,
        at = w.sleepAtMs,
        result = "noted",
        reason = s"host sleep began (inferred at wake; frozenMs=${w.durationMs})",
        blocking = false,
        nodes = 0,
        items = Nil,
        kind = BootDispatcherWake.KindSleep,
        sleepAt = Some(w.sleepAtMs),
        wakeAt = None
      ),
      BootDispatcherWake.MarkerEntry(
        bootId = bootId,
        project = project,
        at = w.wakeAtMs,
        result = "noted",
        reason = s"host wake (frozenMs=${w.durationMs})",
        blocking = false,
        nodes = 0,
        items = Nil,
        kind = BootDispatcherWake.KindWake,
        sleepAt = Some(w.sleepAtMs),
        wakeAt = Some(w.wakeAtMs)
      )
    )
end WakeSensor
