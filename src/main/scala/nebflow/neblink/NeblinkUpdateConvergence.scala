package nebflow.neblink

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.hotupdate.{UpdateChannel, UpdateRequest, UpdateSource, UpdateOrchestrator, VersionCheck}
import nebflow.shared.NebflowLogger

/**
 * 统一更新收敛闭环 · 设备侧领账与采纳闸（M3 ③，DESIGN §7.2）。
 *
 * 职责单一：心跳响应带来 `update.target` 挂账（[[onHeartbeatTarget]]）→ **采纳闸**
 * → 过闸即**全复用**既有 `UpdateOrchestrator` 七相位执行链（本件零相位逻辑、零安装
 * 逻辑——执行链一个字节不用改，DESIGN §9 M3）。设备**不新增状态机**：target 永远在
 * link-server 那里，本件只留「最近一次见账 + 最近一次决策」两块内存读数（可见性面），
 * 任何时刻掉电都能从下一拍心跳重演完整状态。
 *
 * **采纳语义（自动档，与服务端口径冻结一致；开放问题 #4/#6 采推荐默认）**：
 *   1. **跨通道拒采**：target.channel ≠ 本机通道 ⇒ 不装（beta 不拖 stable）；
 *   2. **前向-only**：仅 target 版本**严格新于**当前版本才装（[[VersionCheck.compareVersions]]
 *      日历版序，与 link-server `cmp_versions` 同构；等值/更旧 = 已收敛或回拨 ⇒ 不装，
 *      防「装完没变版本号」死循环重装）；
 *   3. 过闸 = 自动受理（`confirm = true`）：排他/幂等/排空全交既有编排器——幂等键
 *      `heartbeat:<channel>:<version>` 使同一 target 的重复心跳自然合并；"自动"的含义
 *      是「准入闸通过即装」，不是「无条件装」（忙/在飞 ⇒ 既有 Aborted 腿，旧实例续服）。
 *
 * **零新增定时器（零超时纪律）**：本件只在心跳腿被调用（挂账天然 ≤30s 一拍），无任何
 * wall-clock 调度；状态可见 = `updateState` 帧广播（变更即推）+ serverConfig 快照 +
 * /api/health 健康面，面板纯订阅。
 */
final class NeblinkUpdateConvergence(
  /** 既有七相位编排器（执行链唯一入口；None = 本实例未装配 ⇒ 领账只记不装）。 */
  orchestrator: Option[UpdateOrchestrator],
  /** 既有 WS 广播通道（与编排器进度帧同一通道，复用点 #11）。 */
  broadcast: Json => IO[Unit],
  /** 当前版本（唯一来源 `nebflow.Version.string`）。 */
  currentVersion: () => String = () => nebflow.Version.string,
  /** 本机通道（`nebflow.Version.channel`：install-manifest 记录 → 版本串启发）。 */
  selfChannel: () => String = () => nebflow.Version.channel
):

  private val logger = NebflowLogger.forName("nebflow.neblink.update")

  /** 最近一次见账（含见账时刻；staleness 可见性的取数点）。 */
  final case class SeenTarget(target: NeblinkUpdateTarget, seenAtMs: Long)

  /** 最近一次采纳闸决策（outcome 机器 token：adopted / refused / insync / orchestrator-missing）。 */
  final case class Decision(outcome: String, reason: Option[String], atMs: Long)

  private val seenRef: Ref[IO, Option[SeenTarget]] = Ref.unsafe(None)
  private val decisionRef: Ref[IO, Option[Decision]] = Ref.unsafe(None)

  /**
   * 心跳领账入口（NeblinkDiscovery 心跳腿调用；本件唯一被调度面）。任何内部失败都
   * 吞进日志——**收敛闭环的失败绝不允许打断心跳/名册同步**。
   */
  def onHeartbeatTarget(target: NeblinkUpdateTarget): IO[Unit] =
    evaluate(target)
      .handleErrorWith(e =>
        logger.warn(s"[update-convergence] hint evaluation failed (ignored): ${msg(e)}")
      )

  private def evaluate(target: NeblinkUpdateTarget): IO[Unit] =
    for
      now <- IO(System.currentTimeMillis())
      prev <- seenRef.get
      _ <- seenRef.set(Some(SeenTarget(target, now)))
      decision <- gate(target, now)
      _ <- decisionRef.set(Some(decision))
      // 变更即推（相等不重推——心跳每拍都来，重复广播是噪音）
      _ <- broadcastStateIfChanged(prev, Some(SeenTarget(target, now)))
    yield ()

  /** 采纳闸（语义冻结见类注释；决策只记不抛——每个分支都收敛到可见状态）。 */
  private def gate(target: NeblinkUpdateTarget, now: Long): IO[Decision] =
    val current = currentVersion()
    val mine = selfChannel()
    val targetVersion = target.version.stripPrefix("v")
    if target.channel.isBlank then
      IO.pure(Decision("refused", Some("target channel missing"), now))
    else if target.channel != mine then
      // 跨通道拒采：beta 不拖 stable（反向同理）——面板可见「有挂账但不属于本通道」
      IO.pure(Decision("refused", Some(s"channel mismatch (target=${target.channel}, self=$mine)"), now))
    else if VersionCheck.compareVersions(current, targetVersion) >= 0 then
      // 前向-only：等值（已收敛）或更旧（服务端回拨过 target）⇒ 不装
      IO.pure(Decision("insync", Some(s"target $targetVersion not newer than current $current"), now))
    else
      orchestrator match
        case None =>
          IO.pure(Decision("orchestrator-missing", Some("no UpdateOrchestrator wired in this instance"), now))
        case Some(orch) =>
          val channel = if target.channel == UpdateChannel.Beta.wire then UpdateChannel.Beta else UpdateChannel.Stable
          // mode 用契约默认（WaitIdle(awaitIdleMs)）；幂等键钉 target 身份——同一
          // target 的重复心跳自然合并为「已在途/窗口内同键」，绝不装第二次。
          val req = UpdateRequest(
            source = UpdateSource.Heartbeat,
            confirm = true, // 自动档 = 准入闸通过即装；忙/在飞 ⇒ 既有 Aborted 腿
            channel = channel,
            idempotencyKey = Some(s"heartbeat:${target.channel}:$targetVersion")
          )
          orch
            .request(req)
            .flatMap(admission =>
              logger.info(
                s"[update-convergence] target $targetVersion (${target.channel}) adopted -> orchestrator: $admission"
              ) *> IO.pure(Decision("adopted", Some(admission.toString), now))
            )
            .handleErrorWith(e =>
              logger.warn(s"[update-convergence] orchestrator request failed: ${msg(e)}") *>
                IO.pure(Decision("adopted", Some(s"orchestrator request error: ${msg(e)}"), now))
            )

  // ===== 可见面（面板 serverConfig 快照 / updateState 帧 / /api/health 健康面）=====

  /** 完整状态读数（serverConfig 快照用：自报版本/构建/通道 + 最近挂账与决策）。 */
  def stateJson: IO[Option[Json]] =
    for
      seen <- seenRef.get
      decision <- decisionRef.get
    yield Some(
      Json.obj(
        "currentVersion" -> currentVersion().asJson,
        "build" -> nebflow.Version.build.asJson,
        "channel" -> selfChannel().asJson,
        "target" -> seen.map(s => targetJson(s.target, s.seenAtMs)).getOrElse(Json.Null),
        "decision" -> decision
          .map(d => Json.obj("outcome" -> d.outcome.asJson, "reason" -> d.reason.asJson, "at" -> d.atMs.asJson))
          .getOrElse(Json.Null)
      )
    )

  /**
   * 健康面读数（/api/health 附加 `update` 键；**仅当有挂账或决策时非空**——默认载荷
   * 逐字节不变，探针/自检契约零回归）。
   */
  def healthJson: IO[Option[Json]] =
    for
      seen <- seenRef.get
      decision <- decisionRef.get
    yield
      if seen.isEmpty && decision.isEmpty then None
      else
        Some(
          Json.obj(
            "target" -> seen.map(s => targetJson(s.target, s.seenAtMs)).getOrElse(Json.Null),
            "decision" -> decision
              .map(d => Json.obj("outcome" -> d.outcome.asJson, "reason" -> d.reason.asJson, "at" -> d.atMs.asJson))
              .getOrElse(Json.Null)
          )
        )

  private def targetJson(t: NeblinkUpdateTarget, seenAtMs: Long): Json =
    Json.obj(
      "version" -> t.version.asJson,
      "build" -> t.build.asJson,
      "channel" -> t.channel.asJson,
      "manifestUrl" -> t.manifestUrl.asJson,
      "publishedAt" -> t.publishedAt.asJson,
      "seenAt" -> seenAtMs.asJson
    )

  private def broadcastStateIfChanged(prev: Option[SeenTarget], cur: Option[SeenTarget]): IO[Unit] =
    val changed = (prev, cur) match
      case (None, None) => false
      case (None, Some(_)) => true
      case (Some(_), None) => true
      case (Some(p), Some(c)) => p.target != c.target // seenAt 每拍都变，不参与变更判定
    if changed then
      stateJson.flatMap {
        case Some(state) =>
          broadcast(Json.obj("type" -> "updateState".asJson) deepMerge state).handleErrorWith(e =>
            logger.warn(s"[update-convergence] updateState broadcast failed: ${msg(e)}")
          )
        case None => IO.unit
      }
    else IO.unit

  private def msg(e: Throwable): String = Option(e.getMessage).getOrElse(e.toString)

end NeblinkUpdateConvergence
