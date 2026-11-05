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
   *   | `llm-failure`        | 待你处理 · 可自动恢复 | **否**（续跑次数用尽 ⇒ 一条告警）|
   *   | `cancelled-by-quota` | 待你处理 · 可自动恢复 | **否**（同上）|
   *   | `tool-failure`       | 需唤醒                | 是（一条聚合告警）|
   *   | `engine-fault`       | 需唤醒                | 是（一条聚合告警）|
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
    else
      FreezeDisplay.isInfraReasonCode(w.stripPrefix(DelegateFreeze.WirePrefix).stripPrefix(":"))

  /** 是否静默（provider 故障族 = 零向上通知）。 */
  def silent(line: String, detail: String = ""): Boolean = tierOfLine(line, detail) == Tier.Silent

  // ── provider / 基础设施故障标记（通知面与 flow-notify 面共用同一把尺）──────

  /**
   * 基础设施故障标记词表：命中即认定这次失败**不是任务失败**，而是供应商/网络/额度
   * 这类外部世界的故障——不该向上汇报，也不该扣任务的重试预算。
   */
  private val InfraMarkers: List[String] = List(
    "provider",
    "all candidates",
    "candidates exhausted",
    "no available",
    "llm",
    "429",
    "500",
    "502",
    "503",
    "504",
    "529",
    "overloaded",
    "rate limit",
    "ratelimit",
    "timeout",
    "timed out",
    "econnreset",
    "connection reset",
    "socket hang up",
    "network",
    "unavailable",
    "401",
    "403",
    "unauthorized",
    "invalid api key",
    "invalid_api_key",
    "authentication",
    "no subscription",
    "subscription",
    "forbidden",
    "insufficient_quota",
    "quota",
    "billing",
    "payment required",
    "balance",
    "budget exhausted",
    "模型层",
    "供应商",
    "余额",
    "欠费",
    "额度",
    "无订阅",
    "鉴权"
  )

  /**
   * 这段失败正文是否属**基础设施故障**（provider / 网络 / 额度 / 鉴权）。
   * flow-notify 闸（`DispatchNotify`）与冻结面共用本判据——单一尺子。
   */
  def infraFault(detail: String): Boolean =
    val d = Option(detail).getOrElse("").toLowerCase
    d.nonEmpty && InfraMarkers.exists(m => d.contains(m))

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

  /** 一个冻结点的自动续跑台账（键 = `delegateId@frozenSince`：再冻一次 = 新预算）。 */
  final case class AutoResume(attempts: Int, lastAt: Long)

  private val autoResumes = new ConcurrentHashMap[String, AutoResume]()

  private def autoKey(delegateId: String, frozenSince: Long): String = s"$delegateId@$frozenSince"

  def autoResumesOf(delegateId: String, frozenSince: Long): Int =
    Option(autoResumes.get(autoKey(delegateId, frozenSince))).map(_.attempts).getOrElse(0)

  /**
   * 次数是否用尽（缺省 [[Defaults.DelegateFreezeAutoResumeMax]] = 3）：用尽 ⇒ 该批
   * 不再自动续跑，升级为一条「需用户处理」聚合告警。
   */
  def autoResumesExhausted(delegateId: String, frozenSince: Long): Boolean =
    val max = Defaults.DelegateFreezeAutoResumeMax
    max > 0 && autoResumesOf(delegateId, frozenSince) >= max

  /**
   * 自动续跑是否到期（纯函数）：已试 `attempts` 次后，下一次尝试的最早时刻 =
   * `frozenSince + afterMs + base × 2^(attempts-1)`（attempts=0 ⇒ 只等 afterMs；
   * 之后每多一次尝试间隔翻倍：60s → 120s → 240s …）。间隔从 `frozenSince` 起算
   * （不是从上次尝试起算）——同一份台账即可判定，不需要额外存上次时刻。
   */
  def autoResumeDue(frozenSince: Long, now: Long, attempts: Int): Boolean =
    val after = Defaults.DelegateFreezeAutoResumeAfterMs
    val base = Defaults.DelegateFreezeAutoResumeBackoffMs
    val gap = if attempts <= 0 then after else after + base * (1L << math.min(attempts - 1, 16))
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
   *   - `tool-failure` / `engine-fault`（Tier.Escalate）⇒ 立即进告警；
   *   - provider 故障族（Tier.Silent）⇒ **只有**自动续跑次数用尽才进告警
   *     （作者令「连续失败到上限才升级成一条需用户处理的告警」）。
   * 按父会话分组 ⇒ 同一波**一条**。
   */
  def planAlerts(d: Digest): List[Alert] =
    d.items
      .filter(i => i.tier == Tier.Escalate || i.autoResumesExhausted)
      .groupBy(_.parentSessionId)
      .toList
      .sortBy(_._1)
      .map { case (parent, items) => Alert(parent, items) }

  /** 聚合告警正文（**一条**讲清这一波：几条 / 什么原因 / 谁 / 怎么动）。 */
  def alertText(alert: Alert): String =
    val n = alert.items.size
    val defects = alert.items.filter(_.tier == Tier.Escalate)
    val exhausted = alert.items.filter(_.autoResumesExhausted)
    val causeLine = alert.items
      .groupBy(i => (i.reason, i.tier))
      .toList
      .sortBy { case ((r, t), _) => (tierWire(t), r) }
      .map { case ((reason, tier), items) =>
        val ids = items.map(_.delegateId).mkString(", ")
        val sample = items.headOption.map(_.detail).getOrElse("")
        s"- ${tierWire(tier)} `${reason}` ×${items.size} ($ids)${if sample.nonEmpty then s": ${DelegateFreeze.compactDetail(sample, 160)}" else ""}"
      }
      .mkString("\n")
    val head =
      if defects.nonEmpty && exhausted.nonEmpty then
        s"$n frozen delegate(s) need you: ${defects.size} on a deterministic defect, ${exhausted.size} whose automatic resume budget is spent"
      else if defects.nonEmpty then s"$defects.size frozen delegate(s) stopped on a deterministic defect"
      else
        s"${exhausted.size} frozen delegate(s) used up their automatic resume budget (${Defaults.DelegateFreezeAutoResumeMax} tries) " +
          "— the provider is still not serving, so this needs you (top-up / switch model / cancel)"
    val wake = alert.items.take(6).map(i => s"`delegate:${i.delegateId}`").mkString(" / ")
    val more = if n > 6 then s" (+${n - 6} more on the panel)" else ""
    s"""[freeze-alert] $head
       |$causeLine
       |
       |Wake one: $wake$more — the 唤醒 button on the background-agents panel, or mail the address (it continues in the SAME session; finished work is not redone, and waking never spends the supervisor restart budget).
       |Quiet by design: provider-failure freezes no longer mail you anything — the engine probes availability and resumes them by itself; you only see this single aggregated alert when the budget is spent. Reason codes: `${DelegateFreeze.WirePrefix}:<llm-failure|cancelled-by-quota|tool-failure|engine-fault>`.
       |
       |【冻结聚合告警】$n 条 delegate 冻结：${defects.size} 条是确定性缺陷，${exhausted.size} 条已用完自动续跑次数（供应商仍未恢复，需要你：充值/换模型/取消）。原因：$causeLine。唤醒：面板「唤醒」按钮或给上列地址发一条 Mail（在原会话上下文上继续，已完成的不重做，且不消耗重启预算）。provider 故障冻结不再逐条通知——只此一条聚合告警 + 面板徽标。""".stripMargin

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

  /** 测试/复盘复位（进程内两份台账）。 */
  def resetLedger(): Unit =
    alertLedger.clear()
    autoResumes.clear()

end FreezeNotice
