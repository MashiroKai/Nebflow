package nebflow.core.delegate

import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.{AgentCommand, FreezeDisplay, FreezeKind}
import nebflow.shared.{Defaults, NebflowLogger}

import java.util.concurrent.ConcurrentHashMap

/**
 * FreezeNotice —— 冻结的**通知策略 / 静默统计 / 自动续跑预算**单点。
 *
 * 作者令（2026-11-05 10:23 逐字「然后frozen这种可恢复的，不需要通知，」；同日 10:36
 * 细化逐字「不论是节点还是delegate，由于provider 故障，都不需要向上通知，冻结等待
 * 恢复就行。然后在面板显示，这个是需要用户去处理的，我们引擎要做的就行检测恢复了能
 * 接着工作。所以不需要向上汇报。因为provider 故障上级也基本死了，汇报给上级没用。」）。
 *
 * == 本件不造第二套判定 ==
 * 「这条冻结该怎么呈现」的单点是 `nebflow.actor.FreezeDisplay`（冻结态误报批已合入
 * 的单点，`FreezeKind × needsHuman × autoRecoverable × v 用户面措辞`）。本件只消费
 * 它，回答**另外三个问题**：
 *   ① **要不要向上发消息**（本件 `silent` / `escalate`）——provider 故障族一律"不发"；
 *   ② **一波怎么读**（[[Digest]]，面板统计行的数据源）；
 *   ③ **自动续跑预算用了几次、下次什么时候能再试**（[[AutoResume]] 台账）。
 *
 * == 政策（provider 故障 = 基础设施故障，不是任务失败）==
 *   - `delegate-frozen:llm-failure` / `cancelled-by-quota`（provider 故障族，
 *     `FreezeDisplay.isInfraReasonCode`）⇒ **零向上通知**：不投递父会话冻结回执、不进
 *     升级链、不扣任何重试/重启预算；引擎自己探测 provider 可用性，恢复即用既有唤醒腿
 *     续跑（同一会话、续在检查点），最多 [[Defaults.DelegateFreezeAutoResumeMax]] 次、
 *     间隔递增；**次数用尽**才升级成一条聚合的「需用户处理」告警。
 *   - `delegate-frozen:tool-failure` / `engine-fault`（确定性缺陷）⇒ 一条聚合告警
 *     （同波合并，绝不 N 条）。
 *   - actor 级 `loop` ⇒ 一条聚合告警；`schedule`/`llm-transient`/`network`/
 *     `provider-down`/`restart-recovery`/`waiting-network` ⇒ 静默（引擎自己退避/探测）。
 *
 * == 分层 ==
 * 只读 `DelegateRegistry` 记录、只用 `Defaults` / `FreezeDisplay`（actor 层）与
 * `AgentCommand`，**不 import agent 层**；投递与唤醒在 `nebflow.agent.FreezeAlertSweep`。
 */
object FreezeNotice:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.freeze-notice")

  // ── ① 分类：单一判据来自 FreezeDisplay（不另造表）────────────────────────

  /** 通知面分类：会不会惊动作者（呈现面分类另见 `FreezeDisplay`）。 */
  enum Tier:
    /** 静默：零向上消息（面板可见；引擎自己探测恢复后续跑）。 */
    case Silent

    /** 升级：同一波**一条**聚合告警（需人处置的确定性缺陷，或自动续跑次数用尽）。 */
    case Escalate

  def tierWire(t: Tier): String = t match
    case Tier.Silent => "silent"
    case Tier.Escalate => "escalate"

  /** 面板/回执用中文短名。 */
  def tierLabelZh(t: Tier): String = t match
    case Tier.Silent => "静默（面板可见）"
    case Tier.Escalate => "需你处理"

  /**
   * delegate 冻结原因 → 通知面分类（**枚举 × 分类 × 是否通知**表）：
   *
   *   | 原因码 | FreezeDisplay | 是否向上通知 |
   *   |---|---|---|
   *   | `llm-failure`        | 待你处理 · 可自动恢复 | **否**（本轮冻结超过 30 分钟时限 ⇒ 一条摘要告警）|
   *   | `cancelled-by-quota` | 待你处理 · 可自动恢复 | **否**（同上）|
   *   | `tool-failure`       | 需唤醒                | 是（一条聚合告警，立即）|
   *   | `engine-fault`       | 需唤醒                | 是（一条聚合告警，立即）|
   *
   * 注：`llm-failure` 现在是 provider 侧可恢复故障的**收容码**（鉴权/订阅 403、
   * 配额余额、上游 5xx 含 550、连接失败/超时、格式层拒绝含 `Input too long` 全归
   * 这里；判据表见 [[ProviderFault]]）——所以这一族的静默覆盖面正是作者报障的那
   * 一屏。
   */
  def tierOfReason(r: DelegateFreeze.Reason, detail: String = ""): Tier =
    val fd = FreezeDisplay.ofWire(s"${DelegateFreeze.WirePrefix}:${DelegateFreeze.wire(r)}")
    if fd.autoRecoverable then Tier.Silent else Tier.Escalate

  /** 线值 → 通知面分类（全量词表，actor 级族同表判读）。 */
  def tierOfLine(line: String, detail: String = ""): Tier =
    val fd = FreezeDisplay.ofWire(Option(line).getOrElse(""))
    if fd.autoRecoverable then Tier.Silent else Tier.Escalate

  /** 呈现面判定（面板/审计统一读这里，杜绝两套表）。 */
  def displayOf(line: String): FreezeDisplay = FreezeDisplay.ofWire(Option(line).getOrElse(""))

  /** 该冻结是否属 provider 故障族（基础设施故障，不是任务失败）。 */
  def infraFamily(line: String): Boolean =
    val w = Option(line).getOrElse("")
    if !w.startsWith(DelegateFreeze.WirePrefix) then false
    else FreezeDisplay.isInfraReasonCode(w.stripPrefix(DelegateFreeze.WirePrefix).stripPrefix(":"))

  /** 是否静默（provider 故障族 = 零向上通知）。 */
  def silent(line: String, detail: String = ""): Boolean = tierOfLine(line, detail) == Tier.Silent

  // ── provider / 基础设施故障标记（通知面与 flow-notify 面共用同一把尺）──────

  /**
   * 这段失败正文是否属**基础设施故障**（provider / 网络 / 额度 / 鉴权 / 格式退回）。
   * flow-notify 闸（`DispatchNotify`）与冻结面共用本判据——单一尺子。
   *
   * provider 故障降噪批（2026-11-05 17:18 作者令）后，词表真源搬到
   * [[ProviderFault.Table]]（可测的判据表，逐族带裁定），本方法只做投影：**命中
   * 可恢复族且不属确定性缺陷**即「不是任务失败」。旧的本类私有词表一并删除——
   * 两套词表就是两套真相的发源地（旧表漏了 `550` 与 `Input too long`，正是
   * 作者报障那一屏里被当失败逐条通知的两类）。
   */
  def infraFault(detail: String): Boolean = ProviderFault.silent(detail)

  // ── ② 一波统计（静默面：面板就地看，零消息）──────────────────────────────

  /** 一条冻结记录的分类读数（digest/告警/自动续跑三腿共用）。 */
  final case class Item(
    delegateId: String,
    parentSessionId: String,
    title: String,
    reason: String,
    detail: String,
    tier: Tier,
    infra: Boolean,
    frozenSince: Long,
    ageMs: Long,
    workspace: String,
    autoResumes: Int,
    autoResumesExhausted: Boolean
  ):
    /** 面板统计行用的「已冻结（可唤醒）」判据。 */
    def wakeable: Boolean = true

  end Item

  /** 一波冻结的静默统计（面板统计行数据源）。 */
  final case class Digest(now: Long, windowMs: Long, items: List[Item]):
    def total: Int = items.size
    def silentCount: Int = items.count(_.tier == Tier.Silent)
    def escalateCount: Int = items.count(_.tier == Tier.Escalate)
    def infraCount: Int = items.count(_.infra)
    def oldestAgeMs: Long = if items.isEmpty then 0L else items.map(_.ageMs).max

  /**
   * 记录 → 一波统计项（纯函数）：只收**在册未归档**且带 delegate 冻结线值的记录；
   * 窗口 = `now - windowMs` 之后冻结的算同一波（`windowMs <= 0` = 全部）。
   */
  def itemsOf(records: List[DelegateRegistry.Record], now: Long, windowMs: Long): List[Item] =
    records
      .filter(r => r.frozenSince.isDefined && r.archivedAt.isEmpty)
      .filter(r => DelegateFreeze.isFreezeWire(r.frozenReason.getOrElse("")))
      .filter { r =>
        windowMs <= 0L || now - r.frozenSince.getOrElse(now) <= windowMs
      }
      .flatMap { r =>
        DelegateFreeze.parse(r.frozenReason.getOrElse("")).map { reason =>
          val detail = r.frozenDetail.getOrElse("")
          val since = r.frozenSince.getOrElse(now)
          val wire = DelegateFreeze.wire(reason)
          Item(
            delegateId = r.id,
            parentSessionId = r.parentSessionId,
            title = Option(r.title).getOrElse(""),
            reason = wire,
            detail = detail,
            tier = tierOfReason(reason, detail),
            infra = FreezeDisplay.isInfraReasonCode(wire) || infraFault(detail),
            frozenSince = since,
            ageMs = math.max(0L, now - since),
            workspace = Option(r.worktree.getOrElse(r.cwd)).getOrElse(""),
            autoResumes = autoResumesOf(r.id, since),
            autoResumesExhausted = autoResumesExhausted(r.id, since)
          )
        }
      }
      .sortBy(i => -i.frozenSince)

  /** 一波统计（缺省窗口 = [[Defaults.DelegateFreezeDigestWindowMs]]）。 */
  def digestOf(records: List[DelegateRegistry.Record], now: Long): Digest =
    Digest(
      now,
      Defaults.DelegateFreezeDigestWindowMs,
      itemsOf(records, now, Defaults.DelegateFreezeDigestWindowMs)
    )

  /** 面板统计行载荷（WS `delegateFreezeDigest` 帧；快照同形态）。 */
  def digestJson(d: Digest): Json =
    Json.obj(
      "type" -> "delegateFreezeDigest".asJson,
      "total" -> d.total.asJson,
      "recoverable" -> d.infraCount.asJson,
      "needsHuman" -> (d.total - d.infraCount).asJson,
      "silent" -> d.silentCount.asJson,
      "escalate" -> d.escalateCount.asJson,
      "oldestAgeMs" -> d.oldestAgeMs.asJson,
      "windowMs" -> d.windowMs.asJson,
      "items" -> d.items.map { i =>
        Json.obj(
          "sessionId" -> i.delegateId.asJson,
          "parentSessionId" -> i.parentSessionId.asJson,
          "reason" -> i.reason.asJson,
          "tier" -> tierWire(i.tier).asJson,
          "tierLabel" -> tierLabelZh(i.tier).asJson,
          "infra" -> i.infra.asJson,
          "frozenSince" -> i.frozenSince.asJson,
          "ageMs" -> i.ageMs.asJson,
          "autoResumes" -> i.autoResumes.asJson,
          "autoResumesExhausted" -> i.autoResumesExhausted.asJson,
          "wakeAddress" -> s"delegate:${i.delegateId}".asJson
        )
      }.asJson
    )

  // ── ③ 自动续跑预算（provider 故障族的引擎自愈台账）──────────────────────

  /** 一个冻结点的自动续跑台账（键 = `delegateId#episodeStart`：同一轮冻结 = 同一预算）。 */
  final case class AutoResume(attempts: Int, lastAt: Long)

  private val autoResumes = new ConcurrentHashMap[String, AutoResume]()

  /**
   * 冻结**轮次（episode）台账**：`delegateId → 本轮冻结起点`（provider 故障降噪批
   * 2026-11-05）。
   *
   * 为什么不是 `frozenSince`：自动续跑会唤醒会话，若 provider 其实还没好，子会话会
   * 很快再冻一次 ⇒ `frozenSince` 刷新 ⇒ 计数与时限**双双归零**，「30 分钟才升级」
   * 永远到不了、退避也永远从 60s 重新开始（这正是「无限重试」形状）。轮次台账把
   * 一串「冻→续→又冻」当**同一轮**：起点只认第一次，直到该 delegate 不再处于冻结
   * 态才清账（[[endEpisodes]]，由扫描拍按当前冻结集收口）。
   */
  private val episodes = new ConcurrentHashMap[String, java.lang.Long]()

  /** 本轮冻结起点（首次见到该 delegate 冻结的时刻；幂等）。 */
  def episodeOf(delegateId: String, frozenSince: Long): Long =
    episodes.computeIfAbsent(delegateId, _ => java.lang.Long.valueOf(frozenSince)).longValue()

  /** 本轮冻结已持续多久（ms）——「长时间仍不可恢复」的判据材料。 */
  def episodeAgeMs(delegateId: String, frozenSince: Long, now: Long): Long =
    math.max(0L, now - episodeOf(delegateId, frozenSince))

  /**
   * 轮次收口：不再处于冻结态的 delegate 清账（恢复/唤醒后下一次冻结算新的轮次）。
   * 由 `FreezeAlertSweep` 每拍调用，fail-soft（台账只增不减会误伤后来的冻结）。
   */
  def endEpisodes(activeIds: Set[String]): Unit =
    episodes.keySet().removeIf(id => !activeIds.contains(id))

  private def autoKey(delegateId: String, frozenSince: Long): String =
    s"$delegateId#${episodeOf(delegateId, frozenSince)}"

  def autoResumesOf(delegateId: String, frozenSince: Long): Int =
    Option(autoResumes.get(autoKey(delegateId, frozenSince))).map(_.attempts).getOrElse(0)

  /**
   * 次数是否达到「面板读数」意义上的上限（缺省 [[Defaults.DelegateFreezeAutoResumeMax]]）：
   * **只作面板读数与告警文案**，不再是续跑闸——续跑一直按封顶间隔进行到恢复或升级
   * 时限（provider 故障降噪批：把「3 次即停」改成「时间到了才升级」）。
   */
  def autoResumesExhausted(delegateId: String, frozenSince: Long): Boolean =
    val max = Defaults.DelegateFreezeAutoResumeMax
    max > 0 && autoResumesOf(delegateId, frozenSince) >= max

  /**
   * 是否到「升级」时限（纯函数）：本轮冻结（[[episodeAgeMs]]，跨「冻→续→又冻」累计）
   * 超过 [[Defaults.DelegateFreezeEscalateAfterMs]]（缺省 30 分钟）⇒ 才允许把这一族
   * 升级成一条摘要通知。作者令：「如果长时间（给出你选的上限，例如 30 分钟）仍不可
   * 恢复，才升级为一条摘要式通知」。
   */
  def escalateDue(delegateId: String, frozenSince: Long, now: Long): Boolean =
    val horizon = Defaults.DelegateFreezeEscalateAfterMs
    horizon <= 0L || episodeAgeMs(delegateId, frozenSince, now) >= horizon

  /**
   * 自动续跑是否到期（纯函数）：已试 `attempts` 次后，下一次尝试的最早时刻 =
   * `frozenSince + afterMs + min(base × 2^(attempts-1), backoffMax)`（attempts=0 ⇒
   * 只等 afterMs；之后每次翻倍，**到 [[Defaults.DelegateFreezeAutoResumeBackoffMaxMs]]
   * 封顶**，此后恒按封顶间隔）。间隔从冻结起点起算（不是从上次尝试起算）——同一份
   * 台账即可判定，不需要额外存上次时刻。
   *
   * 退避曲线（缺省 after=60s、base=60s、cap=300s）的实际尝试时刻：
   * `+60s → +120s → +180s → +300s → +360s → +420s → …`（每次 +5min 封顶）——
   * 指数起步、迅速封顶，**不是固定间隔死循环**（固定间隔会让 provider 恢复瞬间
   * 撞上一串同时唤醒；指数退避把压力摊平）。
   */
  def autoResumeDue(frozenSince: Long, now: Long, attempts: Int): Boolean =
    val after = Defaults.DelegateFreezeAutoResumeAfterMs
    val base = Defaults.DelegateFreezeAutoResumeBackoffMs
    val cap = Defaults.DelegateFreezeAutoResumeBackoffMaxMs
    val grow = if attempts <= 0 then 0L else base * (1L << math.min(attempts - 1, 16))
    val gap = after + (if cap > 0L then math.min(grow, cap) else grow)
    now - frozenSince >= gap

  /** 记一次自动续跑尝试（返回累计次数）。 */
  def recordAutoResume(delegateId: String, frozenSince: Long, now: Long): Int =
    val key = autoKey(delegateId, frozenSince)
    autoResumes
      .merge(key, AutoResume(1, now), (a, b) => AutoResume(a.attempts + b.attempts, now))
      .attempts

  /** 台账复位（测试/复盘）。 */
  def resetAutoResumes(): Unit = autoResumes.clear()

  // ── ④ 聚合告警（需人处理面）──────────────────────────────────────────────

  /** 一条聚合告警：一个父会话的一波冻结项。 */
  final case class Alert(parentSessionId: String, items: List[Item]):
    def ids: List[String] = items.map(_.delegateId).sorted

    /** 去重键（父会话 + 冻结 id 集 + 分类）：集合变了才是新一波。 */
    def key: String =
      s"alert:${parentSessionId}:${items.map(i => tierWire(i.tier)).distinct.sorted.mkString("+")}:${ids.mkString(",")}"

  /**
   * 告警计划（纯函数）：一波里
   *   - `tool-failure` / `engine-fault`（Tier.Escalate，确定性缺陷）⇒ **立即**进告警；
   *   - provider 故障族（Tier.Silent）⇒ **只有**本轮冻结超过升级时限
   *     （[[escalateDue]]，缺省 30 分钟）才进告警——作者令「长时间仍不可恢复，才升级
   *     为一条摘要式通知」。此前的判据是「自动续跑次数用尽」（3 次 ≈ 8 分钟），
   *     等于把一次正常的上游抖动直接变成作者的事，本批按作者令改为时间判据。
   * 按父会话分组 ⇒ 同一波**一条**。
   */
  def planAlerts(d: Digest): List[Alert] =
    d.items
      .filter { i =>
        if i.tier == Tier.Escalate then true
        else escalateDue(i.delegateId, i.frozenSince, d.now)
      }
      .groupBy(_.parentSessionId)
      .toList
      .sortBy(_._1)
      .map { case (parent, items) => Alert(parent, items) }

  /** 聚合告警正文（**一条**讲清这一波：几条 / 什么原因 / 谁 / 怎么动）。 */
  def alertText(alert: Alert): String =
    val n = alert.items.size
    val defects = alert.items.filter(_.tier == Tier.Escalate)
    val exhausted = alert.items.filter(_.autoResumesExhausted)
    val horizonMin = Defaults.DelegateFreezeEscalateAfterMs / 60000L
    val causeLine = alert.items
      .groupBy(i => (i.reason, i.tier))
      .toList
      .sortBy { case ((r, t), _) => (tierWire(t), r) }
      .map { case ((reason, tier), items) =>
        val ids = items.map(_.delegateId).mkString(", ")
        val sample = items.headOption.map(_.detail).getOrElse("")
        val tries = items.headOption.map(i => i.autoResumes).getOrElse(0)
        s"- ${tierWire(tier)} `${reason}` ×${items.size} ($ids, 自动续跑 $tries 次)" +
          s"${if sample.nonEmpty then s": ${DelegateFreeze.compactDetail(sample, 160)}" else ""}"
      }
      .mkString("\n")
    val head =
      if defects.nonEmpty && exhausted.nonEmpty then
        s"$n frozen delegate(s) need you: ${defects.size} on a deterministic defect, ${exhausted.size} whose automatic resume budget is spent"
      else if defects.nonEmpty then s"$defects.size frozen delegate(s) stopped on a deterministic defect"
      else
        s"${exhausted.size} frozen delegate(s) are still frozen after the ${horizonMin}-minute auto-resume horizon " +
          "— the provider is still not serving, so this needs you (top-up / switch model / cancel)"
    val wake = alert.items.take(6).map(i => s"`delegate:${i.delegateId}`").mkString(" / ")
    val more = if n > 6 then s" (+${n - 6} more on the panel)" else ""
    s"""[freeze-alert] $head
       |$causeLine
       |
       |Wake one: $wake$more — the 唤醒 button on the background-agents panel, or mail the address (it continues in the SAME session; finished work is not redone, and waking never spends the supervisor restart budget).
       |Quiet by design: provider-failure freezes mail you nothing while the engine is retrying them by itself (exponential backoff, capped gap, resuming the SAME session). This single aggregated alert is the only message — it appears once a freeze has stayed unrecoverable past the ${Defaults.DelegateFreezeEscalateAfterMs / 60000L}-minute horizon, or immediately for a deterministic defect. Reason codes: `${DelegateFreeze.WirePrefix}:<llm-failure|cancelled-by-quota|tool-failure|engine-fault>`.
       |
       |【冻结聚合告警】$n 条 delegate 冻结：${defects.size} 条是确定性缺陷，${exhausted.size} 条在自动续跑时限（$horizonMin 分钟）后仍未恢复（供应商仍未服务，需要你：充值/换模型/取消）。原因：$causeLine。唤醒：面板「唤醒」按钮或给上列地址发一条 Mail（在原会话上下文上继续，已完成的不重做，且不消耗重启预算）。provider 故障冻结在时限内不逐条通知——只此一条聚合告警 + 面板徽标。""".stripMargin

  end alertText

  /** 聚合告警事件（入父会话持久邮箱；`eventType=freeze-alert`，不是终态）。 */
  def alertEvent(alert: Alert): AgentCommand.ExternalEvent =
    AgentCommand.ExternalEvent(
      source = "delegate",
      eventType = "freeze-alert",
      payload = alertText(alert),
      metadata = io.circe.JsonObject(
        "freezeAlert" -> true.asJson,
        "count" -> alert.items.size.asJson,
        "deterministicDefects" -> alert.items.count(_.tier == Tier.Escalate).asJson,
        "autoResumeExhausted" -> alert.items.count(_.autoResumesExhausted).asJson,
        "delegateIds" -> alert.ids.asJson,
        "reasons" -> alert.items.map(_.reason).distinct.asJson,
        "wakeAddresses" -> alert.ids.map(id => s"delegate:$id").asJson
      ),
      correlationId = Some(s"freeze-alert-${alert.ids.mkString("-")}")
    )

  // ── ⑤ 聚合告警去重（进程内台账；重启后重发一次 = 无害）──────────────────

  private val alertLedger = new ConcurrentHashMap[String, java.lang.Long]()

  /**
   * 认领一次告警发送权（无 IO 副作用）：同键在冷却窗
   * [[Defaults.DelegateFreezeAlertReArmMs]] 内只放行一次——30s 扫描拍不会把同一波
   * 重复推成 N 条。
   */
  def claimAlert(key: String, now: Long): Boolean =
    val reArm = Defaults.DelegateFreezeAlertReArmMs
    val prev = alertLedger.get(key)
    if prev != null && now - prev < reArm then false
    else
      alertLedger.put(key, now)
      true

  /** 测试/复盘复位（进程内三份台账）。 */
  def resetLedger(): Unit =
    alertLedger.clear()
    autoResumes.clear()
    episodes.clear()

end FreezeNotice
