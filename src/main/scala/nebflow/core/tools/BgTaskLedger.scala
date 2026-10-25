package nebflow.core.tools

import cats.effect.IO
import io.circe.*
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.core.AtomicJson
import nebflow.shared.{NebflowLogger, PathUtil}

/**
 * 后台任务完成事件持久账本（修复批 2026-10-24，作者病历逐字「任务账目丢了，
 * wake 路径也没了，永久空等」）。
 *
 * 病历锚 = PPT 批 delegate 会话：起后台渲染任务 → 结束回合 → 任务完成 → 完成
 * 通知没有唤醒它 → 会话 Idle 悬挂，同时状态面报「本会话无后台任务」。三处缺陷
 * 本件收口两处（第三处在 BgNotifyDelivery/BashTool 的投递面）：
 *
 *   1. **完成事件先落账本再投递**（BashTool.makeNotifyCallback /
 *      RemoteExecutor.notifyRemoteBgResult 唯一写点）：投递之前事件与输出尾段
 *      先持久化到本账本；投递成功与否不再影响可追索性——目标会话 idle/无栅栏
 *      时事件挂起（pending），会话下一次被唤醒时由 agent 侧补投腿
 *      （AgentActor.flushPendingLedgerCompletions，挂在 idle 三个唤醒点）**
 *      先补投未消费的完成事件**——永不蒸发。
 *   2. **bg-wait cap 强制终判的孤儿登记**（BackoffSupervisor armBgWaitCap 到点
 *      腿）：等待期超限强制交付时仍有未结任务 ⇒ 逐任务写 orphan 条目（含当时
 *      输出尾段快照）——续会话（复活/重派）拾取有据，不再直接丢。
 *
 * 消费语义（consume-on-injection）：条目在**载荷真正进入会话转录**时才算消费
 * ——直投（Direct）到活会话的事件由唤醒轮的补投腿经「payloadHead 已在转录/
 * 在途事件」判重后消费；补投腿注入的条目注入即消费。双路径（直投+补投）靠
 * payloadHead 判重幂等，绝不双注。
 *
 * 存储：`<dataRoot>/sessions/<sessionId>/bg-task-ledger.json`（MailQueueStore /
 * CompactionQueueStore 同目录约定；atomic tmp+move）。损坏容忍：解码失败 =
 * 空账本 + WARN（账本故障绝不阻断主投递路径——所有写入点 fail-soft）。
 */
object BgTaskLedger:
  private val logger = NebflowLogger.forName("nebflow.bg-ledger")

  /** 转录判重键长度：payload 头部截断（直投注入件 = payload 原文进转录）。 */
  val PayloadHeadLen: Int = 160

  /** 单条账目。 */
  final case class Entry(
    jobId: String,
    description: String,
    /** "completion"（终态事件）| "orphan"（cap 强制终判时仍未结的孤儿）。 */
    kind: String,
    /** completed | failed；orphan 未结时为空串。 */
    eventType: String,
    /** 转录判重键 = payload.take(PayloadHeadLen)。 */
    payloadHead: String,
    /** 完整载荷（含输出尾段——完成通知即「事件+输出尾段」本体）。 */
    payload: String,
    /** orphan 登记时刻的输出尾段快照（BgTaskOutputStore 尾读）。 */
    tailSnapshot: Option[String],
    recordedAtMs: Long,
    completedAtMs: Option[Long],
    consumed: Boolean = false
  )

  /** 回合尾栅栏（修复批 spec ③）：会话结束回合时在飞等待型任务集的持久登记。 */
  final case class Barrier(taskIds: List[String], armedAtMs: Long)

  final case class Snapshot(entries: List[Entry], barrier: Option[Barrier])

  // ── Codecs（手写，字段少；消费方只读本件自己写出的条目）────────────────

  private given Encoder[Entry] = Encoder.instance { e =>
    Json.obj(
      "jobId" -> e.jobId.asJson,
      "description" -> e.description.asJson,
      "kind" -> e.kind.asJson,
      "eventType" -> e.eventType.asJson,
      "payloadHead" -> e.payloadHead.asJson,
      "payload" -> e.payload.asJson,
      "tailSnapshot" -> e.tailSnapshot.asJson,
      "recordedAtMs" -> e.recordedAtMs.asJson,
      "completedAtMs" -> e.completedAtMs.asJson,
      "consumed" -> e.consumed.asJson
    )
  }

  private given Decoder[Entry] = Decoder.instance { c =>
    for
      jobId <- c.downField("jobId").as[String]
      description <- c.downField("description").as[String]
      kind <- c.downField("kind").as[String]
      eventType <- c.downField("eventType").as[String]
      payloadHead <- c.downField("payloadHead").as[String]
      payload <- c.downField("payload").as[String]
      tailSnapshot <- c.downField("tailSnapshot").as[Option[String]]
      recordedAtMs <- c.downField("recordedAtMs").as[Long]
      completedAtMs <- c.downField("completedAtMs").as[Option[Long]]
      consumed <- c.downField("consumed").as[Option[Boolean]]
    yield Entry(
      jobId,
      description,
      kind,
      eventType,
      payloadHead,
      payload,
      tailSnapshot,
      recordedAtMs,
      completedAtMs,
      consumed.getOrElse(false)
    )
  }

  private given Encoder[Barrier] = Encoder.instance { b =>
    Json.obj("taskIds" -> b.taskIds.asJson, "armedAtMs" -> b.armedAtMs.asJson)
  }

  private given Decoder[Barrier] = Decoder.instance { c =>
    for
      taskIds <- c.downField("taskIds").as[List[String]]
      armedAtMs <- c.downField("armedAtMs").as[Long]
    yield Barrier(taskIds, armedAtMs)
  }

  private given Encoder[Snapshot] = Encoder.instance { s =>
    Json.obj("entries" -> s.entries.asJson, "barrier" -> s.barrier.asJson)
  }

  private given Decoder[Snapshot] = Decoder.instance { c =>
    for
      entries <- c.downField("entries").as[Option[List[Entry]]]
      barrier <- c.downField("barrier").as[Option[Barrier]]
    yield Snapshot(entries.getOrElse(Nil), barrier)
  }

  private def ledgerFile(sessionId: String): os.Path =
    PathUtil.dataRoot / "sessions" / sessionId / "bg-task-ledger.json"

  private def load(sessionId: String): IO[Snapshot] =
    if sessionId.trim.isEmpty then IO.pure(Snapshot(Nil, None))
    else
      val file = ledgerFile(sessionId)
      IO.blocking {
        if !os.exists(file) then Snapshot(Nil, None)
        else
          decode[Snapshot](os.read(file)) match
            case Right(s) => s
            case Left(e) =>
              logger.warn(s"BgTaskLedger.load: failed to decode for $sessionId: ${e.getMessage}")
              Snapshot(Nil, None)
      }.handleErrorWith(e =>
        logger.warn(s"BgTaskLedger.load failed for $sessionId: ${e.getMessage}").as(Snapshot(Nil, None))
      )

  private def save(sessionId: String, s: Snapshot): IO[Unit] =
    if sessionId.trim.isEmpty then IO.unit
    else
      val file = ledgerFile(sessionId)
      IO.blocking {
        if !os.exists(file / os.up) then os.makeDir.all(file / os.up)
        AtomicJson.writeSync(file, s.asJson.noSpaces)
      }.void
        .handleErrorWith(e => logger.warn(s"BgTaskLedger.save failed for $sessionId: ${e.getMessage}").void)

  /** 空账本删文件（与 CompactionQueueStore.clear 同语义：零条目不留壳）。 */
  private def clearFile(sessionId: String): IO[Unit] =
    if sessionId.trim.isEmpty then IO.unit
    else
      val file = ledgerFile(sessionId)
      IO.blocking {
        if os.exists(file) then os.remove(file)
      }.void
        .handleErrorWith(e => logger.warn(s"BgTaskLedger.clear failed for $sessionId: ${e.getMessage}").void)

  /**
   * 完成事件落账（**先落账本再投递**的唯一写点语义）：同 jobId 既有条目
   * （含 orphan 升级窗）原位替换、consumed 复位 false——重复完成（理论上不该
   * 有）以后到者为准。空 sessionId = no-op（无主任务无处落账）。
   */
  def recordCompletion(
    sessionId: String,
    jobId: String,
    description: String,
    eventType: String,
    payload: String
  ): IO[Unit] =
    if sessionId.trim.isEmpty then IO.unit
    else
      load(sessionId).flatMap { snap =>
        val now = System.currentTimeMillis()
        val entry = Entry(
          jobId = jobId,
          description = description,
          kind = "completion",
          eventType = eventType,
          payloadHead = payload.take(PayloadHeadLen),
          payload = payload,
          tailSnapshot = None,
          recordedAtMs = now,
          completedAtMs = Some(now)
        )
        val kept = snap.entries.filterNot(_.jobId == jobId)
        save(sessionId, snap.copy(entries = kept :+ entry))
      }

  /**
   * bg-wait cap 孤儿登记（BackoffSupervisor 到点腿唯一写点）：强制终判时仍未
   * 结的等待型任务逐条入账（当时输出尾段快照随行）。同 jobId 已有 completion
   * 条目（竞态：登记瞬间恰有完成写点）⇒ 保留 completion 不降级。
   */
  def markOrphaned(
    sessionId: String,
    orphans: List[(String, String, Option[String])]
  ): IO[Unit] =
    if sessionId.trim.isEmpty || orphans.isEmpty then IO.unit
    else
      load(sessionId).flatMap { snap =>
        val now = System.currentTimeMillis()
        val newEntries = orphans.map { case (jobId, description, tail) =>
          snap.entries.find(_.jobId == jobId) match
            case Some(existing) if existing.kind == "completion" => existing
            case _ =>
              Entry(
                jobId = jobId,
                description = description,
                kind = "orphan",
                eventType = "",
                payloadHead = "",
                payload = "",
                tailSnapshot = tail,
                recordedAtMs = now,
                completedAtMs = None
              )
        }
        val byId = snap.entries.map(e => e.jobId -> e).toMap ++
          newEntries.map(e => e.jobId -> e)
        save(sessionId, snap.copy(entries = byId.values.toList))
      }

  /** 未消费条目（补投腿的数据源；空 sessionId = Nil）。 */
  def pending(sessionId: String): IO[List[Entry]] =
    load(sessionId).map(_.entries.filterNot(_.consumed))

  /** 消费（注入即消费；jobId 集合内全部置 consumed）。 */
  def consume(sessionId: String, jobIds: List[String]): IO[Unit] =
    if sessionId.trim.isEmpty || jobIds.isEmpty then IO.unit
    else
      val ids = jobIds.toSet
      load(sessionId).flatMap { snap =>
        val ids = jobIds.toSet
        val next = snap.entries.map(e => if ids.contains(e.jobId) then e.copy(consumed = true) else e)
        // 消费完的 completion 条目直接出账（账本只留未消费 + 孤儿取证行——
        // orphan 即使被消费标记也保留：cap 孤儿是「会话终结时仍未结」的取证面）；
        // 全空且无栅栏 ⇒ 删文件（不留壳；有栅栏时保栅栏、只清条目）。
        val alive = next.filterNot(e => e.consumed && e.kind == "completion")
        if alive.isEmpty && snap.barrier.isEmpty then clearFile(sessionId)
        else save(sessionId, snap.copy(entries = alive))
      }

  /**
   * 回合尾栅栏登记（spec ③「有在飞任务结束回合必有栅栏」的载体）。返回
   * changed = 本次在飞任务集相对上次栅栏**发生了变化**（新增任务 = 新等待期
   * 开始）——回合尾警告只在新等待期注入一次（防逐轮重复刷转录）。
   * taskIds 为空 = 清栅栏（返回 false）。
   */
  def armBarrier(sessionId: String, taskIds: List[String]): IO[Boolean] =
    if sessionId.trim.isEmpty then IO.pure(false)
    else
      load(sessionId).flatMap { snap =>
        if taskIds.isEmpty then
          if snap.barrier.isEmpty then IO.pure(false)
          else save(sessionId, snap.copy(barrier = None)).as(false)
        else
          val now = System.currentTimeMillis()
          val changed = snap.barrier.forall(_.taskIds.toSet != taskIds.toSet)
          save(sessionId, snap.copy(barrier = Some(Barrier(taskIds, now)))).as(changed)
      }

  /** 栅栏读数（spec 断言面 / 取证）。 */
  def barrierOf(sessionId: String): IO[Option[Barrier]] =
    load(sessionId).map(_.barrier)

  /** 全量快照（状态面 / 取证 / spec 断言）。 */
  def snapshot(sessionId: String): IO[Snapshot] = load(sessionId)

  // ── 纯判定面（spec 直测，零 IO）───────────────────────────────────────

  /**
   * 转录判重拆分：entries 分成（需补投, 已在转录/在途=只消费）。判据 =
   * payloadHead 非空且（在途载荷头命中 ∨ 转录文本含 payloadHead）。inFlight
   * 传本回合正在注入的事件载荷（直投唤醒轮：事件本体由处理器自己注入，账本
   * 只消费不重注）。
   */
  def splitForRedelivery(
    entries: List[Entry],
    transcriptText: String,
    inFlightPayloads: List[String]
  ): (List[Entry], List[Entry]) =
    val inFlightHeads = inFlightPayloads.map(_.take(PayloadHeadLen)).filter(_.nonEmpty).toSet
    entries.partition { e =>
      val head = e.payloadHead
      head.nonEmpty && !inFlightHeads.contains(head) && !transcriptText.contains(head)
    }

  /**
   * 回合尾警告文案（纯函数，spec 判红锚）。逐任务 id+描述+起始（起止面：
   * 在飞任务带起始时刻与已运行时长，完成时刻由完成事件账目承载）。
   */
  def turnEndWarning(
    waiting: List[(String, String, Long)],
    nowMs: Long
  ): String =
    val lines = waiting.zipWithIndex
      .map { case ((jobId, description, startedAtMs), i) =>
        val started = java.time.Instant.ofEpochMilli(startedAtMs).toString
        val elapsedMin = math.max(0L, (nowMs - startedAtMs) / 60000L)
        s"${i + 1}. '$description' (jobId=$jobId, started=$started, running ${elapsedMin}m)"
      }
      .mkString("\n")
    s"""<system-reminder>
       |[Background-task accounting] You have ${waiting.size} background task(s) still in flight in this session:
       |$lines
       |Ending this turn now is your call: completion notifications wake this session automatically, and every completion is recorded in the persistent task ledger — if a wake is ever lost, the pending completions are re-delivered first on your next wake (never evaporate). If the results are required for your deliverable, do not end without either waiting for the wake or stating explicitly that the tasks are someone else's responsibility now.
       |</system-reminder>""".stripMargin
  end turnEndWarning
end BgTaskLedger

/**
 * 回合尾栅栏门（修复批 spec ③ 的 IO 接线单点）：结束回合时对照在飞账本，
 * 非空 ⇒ 持久栅栏（armBarrier）+ 新等待期首拍返回警告文案。纯装配，无状态。
 */
object BgTurnEndGate:

  final case class Outcome(warning: Option[String], changed: Boolean, waitingCount: Int)

  /**
   * @param waiting 本会话**自有**在飞等待型任务（sessionId == 会话 id；子代理
   *                挂靠任务由子代理自己的回合尾门承载，不灌水父会话）。
   */
  def onTurnEnd(sessionId: String, waiting: List[BgTaskRegistry.ActiveTask]): IO[Outcome] =
    if sessionId.trim.isEmpty then IO.pure(Outcome(None, changed = false, 0))
    else
      BgTaskLedger.armBarrier(sessionId, waiting.map(_.jobId)).map { changed =>
        Outcome(
          warning =
            if changed && waiting.nonEmpty then
              Some(
                BgTaskLedger
                  .turnEndWarning(waiting.map(t => (t.jobId, t.description, t.startedAtMs)), System.currentTimeMillis())
              )
            else None,
          changed = changed,
          waitingCount = waiting.size
        )
      }
end BgTurnEndGate
