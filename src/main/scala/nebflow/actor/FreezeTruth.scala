package nebflow.actor

import io.circe.Json
import io.circe.syntax.*

/**
 * FreezeTruth —— 一个会话**冻结状态的唯一真相**（缺陷批 2026-11-05「冻结状态两套真相」）。
 *
 * == 报障（作者 2026-11-05 10:42 逐字）==
 * 「'delegate-nebflow-f7c6cd3a' is not in the delegate-error frozen state (frozenReason=none)
 * — the wake action only applies to frozen delegates，现在前端显示有矛盾，显示需要我唤醒，
 * 我点了唤醒又弹这个」。
 *
 * == 两套真相（本件要收掉的东西）==
 * 冻结态在系统里有**两个写点**，各自被一条消费链读着：
 *   ① `AgentRecord.status/Frozen` + `AgentRecord.frozenReason`（统一注册表，内存态）——
 *      快照行 `frozenKind/needsHuman/freezeReasonWire`（`activeAgentEntryJson`）读它；
 *   ② `DelegateRegistry.Record.frozenSince/frozenReason`（落盘槽，跨重启存活）——
 *      快照行 `frozenSince/frozenReason/wakeAddress` 与 `delegateWake` 的准入闸读它。
 * 两条链**各算一套**：Mail 唤醒腿（`DelegateFreeze.onWoken`）同时清两处，但**不发帧**；
 * 面板行的冻结标记是**单调累积**的（快照缺席就保留本地值），于是清掉之后面板照旧挂
 * 「需唤醒」按钮，而唤醒闸看到 ① 或 ② 已空 ⇒ 回一句 `frozenReason=none`。面板说能点、
 * 后端说不能点 = 同一时刻两个答案。
 *
 * == 本件的裁决 ==
 * **冻结与否 = 两处写点的并集**（任一处说冻结就是冻结，任一处说清就是清）——
 * 因为两处的清空由同一个动作（唤醒 / 自动恢复）触发，并集不会制造新的分歧，而交集
 * 会把「一方漏清」的脏态藏起来。分类、是否给按钮、是否需人**都从这一份读数派生**：
 *   - 面板可操作性（显示唤醒按钮与否）= [[wakeable]]
 *   - `delegateWake` 的准入判定 = 同一个 [[wakeable]]
 *   - 拒绝文案里「当前实际状态 + 为什么不需要」= [[status]] / [[source]] / [[labelZh]]
 * 快照（`activeAgentEntryJson` 的 `freeze` 块）、唤醒回帧、恢复/唤醒帧三面共用本件
 * 的 [[json]]——**禁止各面各写一份**（本批的根因就是各写一份）。
 */
case class FreezeTruth(
  frozen: Boolean,
  kindWire: String,
  needsHuman: Boolean,
  autoRecoverable: Boolean,
  /** 面板可操作性 = 冻结 **且** 需人 ⇒ 唯一该给「唤醒」按钮的形态。 */
  wakeable: Boolean,
  /** 冻结原因线值（`delegate-frozen:*` / `loop` / `schedule` / …；非冻结 = 空串）。 */
  reasonWire: String,
  since: Option[Long],
  /** 实际状态（`AgentStatus.toString`；无活体 = `"unknown"`）。 */
  status: String,
  /** 哪一处写点说冻结：`both` / `agent` / `delegate` / `none`（拒绝文案的「为什么」）。 */
  source: String,
  asOf: Long
):
  /** 呈现分类（后端判定表的唯一入口；非冻结 = 空串）。 */
  def kindOf: String = kindWire

  /** 面向用户的一句话（与 `FreezeDisplay` 同词；非冻结 = 「运行中」）。 */
  def labelZh: String =
    if !frozen then "运行中"
    else FreezeDisplay.ofWire(reasonWire, since, asOf).labelZh

  def labelEn: String =
    if !frozen then "running"
    else FreezeDisplay.ofWire(reasonWire, since, asOf).labelEn

  /** 冻结快照块（WS 帧 / 快照行共用；字段面是**单一契约**，前端按 asOf 定序合并）。 */
  def json: Json =
    Json.obj(
      "frozen" -> frozen.asJson,
      "kind" -> kindWire.asJson,
      "needsHuman" -> needsHuman.asJson,
      "autoRecoverable" -> autoRecoverable.asJson,
      "wakeable" -> wakeable.asJson,
      "reasonWire" -> reasonWire.asJson,
      "since" -> since.asJson,
      "status" -> status.asJson,
      "source" -> source.asJson,
      "asOf" -> asOf.asJson,
      "labelZh" -> labelZh.asJson,
      "labelEn" -> labelEn.asJson
    )

  /**
   * 唤醒被拒时的**可对照**说明（任务书「失败要说清楚」）：面板显示什么、服务端看到
   * 什么、为什么不需要这个动作——一句里三个事实都在，不许只回一个 `frozenReason=none`。
   */
  def notWakableWhy: String =
    if frozen then
      s"it is frozen but needs no human action (${labelEn}, reason=${if reasonWire.isEmpty then "unknown" else reasonWire}) — " +
        "the engine resumes it on its own at the scheduled window/backoff"
    else
      s"it is NOT frozen any more (status=$status, frozenReason=${if reasonWire.isEmpty then "none" else reasonWire}) — " +
        "something already resumed it (a Mail wake, the panel wake action, an automatic recovery, or a fresh turn). " +
        "The console row was showing a stale freeze badge: this reply carries the live freeze state so the panel can drop it."

object FreezeTruth:

  /** 非冻结读数（无活体、无落盘槽时的基线）。 */
  def running(status: String, now: Long): FreezeTruth =
    FreezeTruth(
      frozen = false,
      kindWire = "",
      needsHuman = false,
      autoRecoverable = false,
      wakeable = false,
      reasonWire = "",
      since = None,
      status = status,
      source = "none",
      asOf = now
    )

  /**
   * 单一派生点：统一注册表记录（内存态）+ delegate 落盘冻结槽 ⇒ 一份自洽读数。
   *
   * @param live       统一注册表记录（`None` = 无活体/引擎重启后——落盘槽仍可判冻结）
   * @param slotReason delegate 落盘冻结槽线值（`DelegateRegistry.Record.frozenReason`）
   * @param slotSince  delegate 落盘冻结时刻（同槽 `frozenSince`）
   */
  def of(
    live: Option[AgentRecord],
    slotReason: Option[String] = None,
    slotSince: Option[Long] = None,
    now: Long = System.currentTimeMillis()
  ): FreezeTruth =
    val agentFrozen = live.exists(_.status == AgentStatus.Frozen)
    val agentWire = live.flatMap(_.frozenReason).map(_.trim).filter(_.nonEmpty)
    val slotWire = slotReason.map(_.trim).filter(_.nonEmpty)
    val slotFrozen = slotWire.isDefined || slotSince.isDefined
    val frozen = agentFrozen || slotFrozen
    val status = live.map(_.status.toString).getOrElse("unknown")
    if !frozen then running(status, now)
    else
      // 原因取先写者：内存态优先（它承载 `loop`/`schedule`/错误退避与 delegate 线值全族），
      // 落盘槽兜底（无活体时唯一来源）。两者都在场但不同 ⇒ 内存态更接近当前真相。
      val wire = agentWire.orElse(slotWire).getOrElse("")
      val fd = FreezeDisplay.ofWire(wire, slotSince, now)
      FreezeTruth(
        frozen = true,
        kindWire = fd.kind.wire,
        needsHuman = fd.needsHuman,
        autoRecoverable = fd.autoRecoverable,
        wakeable = fd.needsHuman,
        reasonWire = fd.reasonWire,
        since = slotSince,
        status = status,
        source =
          if agentFrozen && slotFrozen then "both"
          else if agentFrozen then "agent"
          else "delegate",
        asOf = now
      )

  /** 帧/快照块 → 回读（前端帧回传面与单测用；坏数据 = 非冻结，绝不假装冻结）。 */
  def fromJson(json: Json, now: Long = System.currentTimeMillis()): FreezeTruth =
    val c = json.hcursor
    def b(k: String): Boolean = c.downField(k).as[Boolean].getOrElse(false)
    def s(k: String): String = c.downField(k).as[String].getOrElse("")
    val frozen = b("frozen")
    FreezeTruth(
      frozen = frozen,
      kindWire = s("kind"),
      needsHuman = b("needsHuman"),
      autoRecoverable = b("autoRecoverable"),
      wakeable = b("wakeable"),
      reasonWire = s("reasonWire"),
      since = c.downField("since").as[Long].toOption,
      status = s("status"),
      source = s("source"),
      asOf = c.downField("asOf").as[Long].getOrElse(now)
    )

end FreezeTruth
