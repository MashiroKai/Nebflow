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
   * 判据：健康表里**没有一条 Down**（空表 = 引擎从未判过谁死 ⇒ 视为可用；有 Down =
   * 供应商仍不可用 ⇒ 不抢跑）。
   */
  def providerServing(resources: AgentRuntimePort): IO[Boolean] =
    resources.healthMonitor.getStates
      .map(states => !states.values.exists { case _: HealthState.Down => true; case _ => false })
      .handleErrorWith { e =>
        logger
          .warn(s"[freeze-alert-sweep] health read failed, assuming serving: ${e.getMessage}")
          .as(true)
      }

  /**
   * 自动续跑一拍：provider 故障族 + 已过龄阈 + 次数未用尽 + 间隔已到 + provider 已服务
   * ⇒ 用既有唤醒腿续跑一次（同一会话、续在检查点）。**成功/失败都不发通知**。
   */
  def autoResumeTick(resources: AgentRuntimePort, wsSend: Option[Json => IO[Unit]]): IO[Unit] =
    if !Defaults.DelegateFreezeEnabled || !Defaults.DelegateFreezeAutoResume then IO.unit
    else
      providerServing(resources).flatMap { serving =>
        if !serving then
          logger.debug("[freeze-alert-sweep] auto-resume skipped: no provider serving yet")
        else
          records().flatMap { recs =>
            val now = System.currentTimeMillis()
            FreezeNotice
              .itemsOf(recs, now, windowMs = 0L)
              .filter(i => i.infra && !i.autoResumesExhausted)
              .filter(i => FreezeNotice.autoResumeDue(i.frozenSince, now, i.autoResumes))
              .traverse_ { item =>
                val attempt = FreezeNotice.recordAutoResume(item.delegateId, item.frozenSince, now)
                logger.info(
                  s"[freeze-alert-sweep] auto-resume #$attempt for ${item.delegateId} " +
                    s"(reason=${item.reason}, frozen ${item.ageMs / 1000}s, provider serving) — no notification, panel only"
                ) *>
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
                          r => {
                            val exhausted = FreezeNotice.autoResumesExhausted(item.delegateId, item.frozenSince)
                            logger.warn(
                              s"[freeze-alert-sweep] auto-resume #$attempt failed for ${item.delegateId}: $r" +
                                (if exhausted then " — budget now spent, one aggregated alert will follow" else "")
                            )
                          },
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
    digestTick(resources, wsSend)
      .handleErrorWith(e => logger.warn(s"[freeze-alert-sweep] digest tick failed: ${e.getMessage}"))
      .flatMap(_ => autoResumeTick(resources, wsSend))
      .handleErrorWith(e => logger.warn(s"[freeze-alert-sweep] auto-resume tick failed: ${e.getMessage}"))
      .flatMap(_ => alertTick(resources))
      .handleErrorWith(e => logger.warn(s"[freeze-alert-sweep] alert tick failed: ${e.getMessage}"))

  /** 测试复位（进程内签名）。 */
  def resetForTest(): Unit =
    lastDigestSig = ""

end FreezeAlertSweep
