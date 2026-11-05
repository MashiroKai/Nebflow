package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.AgentRuntimePort
import nebflow.core.delegate.{DelegateFreeze, DelegateRegistry, FreezeNotice}
import nebflow.shared.{Defaults, HealthState, NebflowLogger}

/**
 * FreezeAlertSweep —— 冻结的**面板面 + 引擎自愈面 + 聚合告警面**（冻结通知策略批
 * 2026-11-05，作者令两连：10:23「然后frozen这种可恢复的，不需要通知，」＋ 10:36
 * 「…由于provider 故障，都不需要向上通知，冻结等待恢复就行。然后在面板显示，这个
 * 是需要用户去处理的，我们引擎要做的就行检测恢复了能接着工作。所以不需要向上汇报。
 * 因为provider 故障上级也基本死了，汇报给上级没用。」）。
 *
 * 挂在 `FreezeScheduler` 的既有 30s 拍上（**零新增定时器**），三腿：
 *
 *   ① [[digestTick]] —— **面板显形**：读 registry 冻结记录 → `delegateFreezeDigest`
 *      帧（「N 条已冻结（可唤醒）」+ 原因码 + 自动续跑次数）。签名不变不重发。
 *   ② [[autoResumeTick]] —— **引擎自己检测恢复了能接着工作**：provider 故障族的冻结，
 *      按 `ProviderHealthPort` 的既有健康读数（后台探针链的结果，**零真实调用**，
 *      不靠反复真调用烧额度）判定「供应商又服务了」⇒ 走 [[DelegateWakeLeg]] 续跑
 *      同一会话（续在检查点、已完成的不重做）。次数上限
 *      `Defaults.DelegateFreezeAutoResumeMax`、间隔按
 *      `Defaults.DelegateFreezeAutoResumeBackoffMs` 递增（防「恢复一点→又冻→又续」
 *      抖动）。**不发任何通知**，只更新面板。
 *   ③ [[alertTick]] —— **只在需要人时发一条**：确定性缺陷（tool/engine）或 provider
 *      故障族的自动续跑次数**用尽** ⇒ 按父会话聚合成**一条**告警（去重键 = 父会话 +
 *      分类 + 冻结 id 集）。provider 故障族的正常冻结**零投递**。
 *
 * 纪律：全链 fail-soft（扫描器必须活着）；唤醒腿不消耗 supervisor 重启预算
 * （走 `UserInput` / `wakeFrozen`，不发 `RestartAgent`）。
 */
object FreezeAlertSweep:

  private val logger = NebflowLogger.forName("nebflow.agent.freeze-alert-sweep")

  /** 上一次广播的统计签名（不变则不重发，避免 30s 拍刷屏）。 */
  @volatile private var lastDigestSig: String = ""

  /** 冻结记录读数（blocking 读盘；失败 = 空表 + WARN）。 */
  private def records(): IO[List[DelegateRegistry.Record]] =
    IO.blocking(DelegateRegistry.list()).handleErrorWith { e =>
      logger.warn(s"[freeze-alert-sweep] registry read failed: ${e.getMessage}").as(Nil)
    }

  /** 统计签名（用于「只在变化时广播」）。 */
  private def signature(d: FreezeNotice.Digest): String =
    d.items
      .map(i => s"${i.delegateId}:${i.reason}:${i.tier}:${i.frozenSince}:${i.autoResumes}")
      .sorted
      .mkString("|")

  // ── ① 面板显形 ──────────────────────────────────────────────────────────

  /** 算一波 digest，变化则推 `delegateFreezeDigest` 帧（冻结发生点也调本方法）。 */
  def digestTick(resources: AgentRuntimePort, wsSend: Option[Json => IO[Unit]]): IO[Unit] =
    if !Defaults.DelegateFreezeEnabled then IO.unit
    else
      records().flatMap { recs =>
        val d = FreezeNotice.digestOf(recs, System.currentTimeMillis())
        val sig = signature(d)
        if sig == lastDigestSig then IO.unit
        else
          IO { lastDigestSig = sig } *>
            wsSend.fold(IO.unit) { send =>
              send(FreezeNotice.digestJson(d)).handleErrorWith(e =>
                logger.warn(s"[freeze-alert-sweep] digest frame failed: ${e.getMessage}")
              )
            }
      }

  // ── ② 引擎自愈：探测到 provider 恢复 ⇒ 自动续跑 ─────────────────────────

  /**
   * provider 是否**又服务了**（轻量读健康快照——后台探针链每 `ProbeIntervalSec` 探测
   * Down 候选并 markUp，本判据零真实调用、零额度消耗）。
   *
   * 判据（provider 故障降噪批 2026-11-05 修正，**这就是 fallback 那条旧缺陷的根因**）：
   * **只要链上还有一个候选不是 Down，就算「有 provider 在服务」**——空表（引擎从未
   * 判过谁死）= 可用；表里**存在**至少一个非 Down 项 = 可用。旧判据是「表里一条
   * Down 都不许有」，于是一个**无关模型**的 Down（例如 kimi 403 无订阅）会把
   * **全部**冻结批永久压住：cmdcode 明明可用，被冻结的批却永远等不到自动续跑 ——
   * 作者面看到的正是「fallback 队列里明明有可用模型，却报所有模型不可用并冻结」。
   *
   * 语义边界：不保证 D**这个 delegate 自己那条链**的首选模型可用——唤醒后若首选仍不
   * 服务，`Fallback` 会自己换链（这是既有能力），真全灭时它会再冻一次，被
   * [[FreezeNotice.episodeOf]] 归进**同一轮**冻结（退避继续、时限继续累计），不会
   * 变成无限快速重试。
   */
  def providerServing(resources: AgentRuntimePort): IO[Boolean] =
    resources.healthMonitor.getStates
      .map { states =>
        states.isEmpty || states.values.exists {
          case _: HealthState.Down => false
          case _ => true
        }
      }
      .handleErrorWith { e =>
        logger
          .warn(s"[freeze-alert-sweep] health read failed, assuming serving: ${e.getMessage}")
          .as(true)
      }

  /** 健康读数摘要（日志用：down / up 各几，方便复盘「为什么续跑了/为什么没续」）。 */
  private def healthSummary(resources: AgentRuntimePort): IO[String] =
    resources.healthMonitor.getStates
      .map { states =>
        val down = states.count { case (_, _: HealthState.Down) => true; case _ => false }
        s"down=$down up=${states.size - down}"
      }
      .handleErrorWith(_ => IO.pure("down=? up=?"))

  /**
   * 「这一拍该不该为这条冻结去尝试续跑」（纯函数，判据单点）：健康表说服务了 ⇒ 可以；
   * 健康表说还没恢复但**本轮冻结已超过健康表容忍窗**（
   * [[Defaults.DelegateFreezeAutoResumeForceAfterMs]]，缺省 10 分钟）⇒ **也可以**
   * ——健康表只许延迟续跑，不许无限期压住（见该参数的动因注释：健康表根本不知道
   * 「从未被探过的健康候选」，旧判据因此把有可用模型的冻结批永久压死）。
   */
  def shouldAttemptResume(serving: Boolean, episodeAgeMs: Long): Boolean =
    serving || episodeAgeMs >= Defaults.DelegateFreezeAutoResumeForceAfterMs

  /**
   * 自动续跑一拍：provider 故障族 + 已过龄阈 + 间隔已到 + （provider 已服务 **或**
   * 已过健康表容忍窗）⇒ 用既有唤醒腿续跑一次（同一会话、续在检查点）。**成功/失败
   * 都不发通知**。
   *
   * 本批（provider 故障降噪 2026-11-05）三处语义修正：
   *   ① **不再按「次数用尽」停手**：次数只作面板读数，续跑一直持续到恢复或本轮冻结
   *      超过升级时限（30 分钟）——冻结不是终点；
   *   ② 间隔走**指数退避 + 封顶**（[[FreezeNotice.autoResumeDue]]），并跨「冻→续→
   *      又冻」按 [[FreezeNotice.episodeOf]] 累计，不会因为一冻一续把退避打回起点；
   *   ③ 健康表**只延迟不否决**（[[shouldAttemptResume]]）——修 fallback 那条旧缺陷。
   */
  def autoResumeTick(resources: AgentRuntimePort, wsSend: Option[Json => IO[Unit]]): IO[Unit] =
    if !Defaults.DelegateFreezeEnabled || !Defaults.DelegateFreezeAutoResume then IO.unit
    else
      providerServing(resources).flatMap { serving =>
        records().flatMap { recs =>
          val now = System.currentTimeMillis()
          FreezeNotice
            .itemsOf(recs, now, windowMs = 0L)
            .filter(_.infra)
            .filter(i => FreezeNotice.autoResumeDue(i.frozenSince, now, i.autoResumes))
            .filter { i =>
              val age = FreezeNotice.episodeAgeMs(i.delegateId, i.frozenSince, now)
              shouldAttemptResume(serving, age) || {
                logger.debug(
                  s"[freeze-alert-sweep] auto-resume deferred for ${i.delegateId}: " +
                    s"health table still has a Down entry and the episode is only ${age / 1000}s old"
                )
                false
              }
            }
            .traverse_ { item =>
              val attempt = FreezeNotice.recordAutoResume(item.delegateId, item.frozenSince, now)
              healthSummary(resources).flatMap { h =>
                logger.info(
                  s"[freeze-alert-sweep] auto-resume #$attempt for ${item.delegateId} " +
                    s"(reason=${item.reason}, frozen ${item.ageMs / 1000}s, episode " +
                    s"${FreezeNotice.episodeAgeMs(item.delegateId, item.frozenSince, now) / 1000}s, $h) " +
                    "— no notification, panel only"
                )
              } *>
                DelegateWakeLeg
                  .wake(resources, wsSend, item.delegateId, "", via = "self-heal")
                  .flatMap { res =>
                    wsSend.fold(IO.unit) { send =>
                      send(
                        Json.obj(
                          "type" -> "delegateWakeResult".asJson,
                          "ok" -> res.isRight.asJson,
                          "sessionId" -> item.delegateId.asJson,
                          "mode" -> "self-heal".asJson,
                          "autoResumeAttempt" -> attempt.asJson,
                          "message" -> res
                            .fold(
                              r => s"auto-resume attempt $attempt did not land: $r (record + transcript kept)",
                              m => s"auto-resume attempt $attempt: resumed ($m)"
                            )
                            .asJson
                        )
                      ).handleErrorWith(_ => IO.unit)
                    } *>
                      res.fold(
                        r =>
                          logger.warn(
                            s"[freeze-alert-sweep] auto-resume #$attempt failed for ${item.delegateId}: $r" +
                              (if FreezeNotice.escalateDue(item.delegateId, item.frozenSince, now) then
                                 " — past the escalation horizon, one aggregated alert will follow"
                               else " — still inside the horizon, retrying at the capped gap")
                          ),
                        m => logger.info(s"[freeze-alert-sweep] auto-resume #$attempt woke ${item.delegateId} ($m)")
                      )
                  }
                  .handleErrorWith(e =>
                    logger.warn(s"[freeze-alert-sweep] auto-resume error for ${item.delegateId}: ${e.getMessage}")
                  )
            }
        }
      }

  // ── ③ 聚合告警（只在需要人时）────────────────────────────────────────────

  /**
   * 告警一拍：确定性缺陷立即进候选、provider 故障族只在自动续跑次数用尽后进候选；
   * 按父会话 + 分类 + id 集去重 ⇒ 同一波**一条**（不是 N 条）。
   * 静默总闸关（回滚）时本腿旁路。
   */
  def alertTick(resources: AgentRuntimePort): IO[Unit] =
    if !Defaults.DelegateFreezeEnabled || !Defaults.DelegateFreezeSilence then IO.unit
    else
      records().flatMap { recs =>
        val now = System.currentTimeMillis()
        val alerts = FreezeNotice.planAlerts(FreezeNotice.digestOf(recs, now))
        alerts.traverse_ { alert =>
          if !FreezeNotice.claimAlert(alert.key, now) then IO.unit
          else
            MailboxProducer
              .deliver(
                resources,
                alert.parentSessionId,
                FreezeNotice.alertEvent(alert),
                identity = s"freeze-alert-${alert.ids.mkString("-")}",
                kind = "delegate-freeze-alert",
                coalesce = false,
                producer = "freeze-alert-sweep"
              )
              .flatMap { r =>
                logger.info(
                  s"[freeze-alert-sweep] ONE aggregated alert for ${alert.items.size} frozen delegate(s) " +
                    s"→ parent ${alert.parentSessionId} (${alert.ids.mkString(", ")}) result=$r"
                )
              }
              .handleErrorWith(e =>
                logger.warn(s"[freeze-alert-sweep] alert delivery failed for ${alert.parentSessionId}: ${e.getMessage}")
              )
        }
      }

  /** 一拍三腿（挂 `FreezeScheduler` 的既有 30s 拍）。 */
  def run(resources: AgentRuntimePort, wsSend: Option[Json => IO[Unit]] = None): IO[Unit] =
    episodeTick(resources)
      .handleErrorWith(e => logger.warn(s"[freeze-alert-sweep] episode tick failed: ${e.getMessage}"))
      .flatMap(_ => digestTick(resources, wsSend))
      .handleErrorWith(e => logger.warn(s"[freeze-alert-sweep] digest tick failed: ${e.getMessage}"))
      .flatMap(_ => autoResumeTick(resources, wsSend))
      .handleErrorWith(e => logger.warn(s"[freeze-alert-sweep] auto-resume tick failed: ${e.getMessage}"))
      .flatMap(_ => alertTick(resources))
      .handleErrorWith(e => logger.warn(s"[freeze-alert-sweep] alert tick failed: ${e.getMessage}"))

  /**
   * 冻结轮次收口：退出冻结态的 delegate 从轮次台账清账（provider 故障降噪批）。
   * 只在**连续冻结**期间累计退避次数与升级时限；唤醒成功后又因同一波故障再冻 =
   * 同一轮（时限继续），真恢复了才是新的一轮。
   */
  def episodeTick(resources: AgentRuntimePort): IO[Unit] =
    if !Defaults.DelegateFreezeEnabled then IO.unit
    else
      records().map { recs =>
        val active = recs.filter(_.frozenSince.isDefined).map(_.id).toSet
        FreezeNotice.endEpisodes(active)
      }

  /** 测试复位（进程内签名）。 */
  def resetForTest(): Unit =
    lastDigestSig = ""

end FreezeAlertSweep
