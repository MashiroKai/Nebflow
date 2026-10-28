package nebflow.core.processor

import cats.effect.IO
import io.circe.*
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.actor.AgentCommand
import nebflow.core.AtomicJson
import nebflow.shared.given
import nebflow.shared.{Defaults, NebflowLogger, PathUtil}

import java.security.MessageDigest

/**
 * HeldReceiptStore —— 「待投递回执」持久台账（平台修复批：delegate 回执投递可靠性，
 * 作者令 2026-10-21「分析完成了没回报后的治本」第 1/2 件的数据面）。
 *
 * 机制链（本批要堵的丢失形态）：delegate 干完活 → 回执以 `ExternalEvent(source=
 * delegate/subtask)` 到达父会话 → 子代理批屏障（drainBarrier）在 outstanding>0 时
 * 把它 HOLD 在 `pendingEvents`（内存）→ 若屏障槽位成幻影（issue #31：成员 hang/
 * 停止失败，永不归还），held 回执**永不注入**，父会话看似 Idle 完成 → 回执烂在队列。
 *
 * 本台账是 held 回执的**簿记面**（不是载荷面——载荷仍由 `CompactionQueueStore` 持久、
 * 内存真源仍是 `ExecutionContext.pendingEvents`）：
 *   - 每条 held 回执一行：id（correlationId 优先，否则 payload 摘要）、producedAt、
 *     attempts、lastAttemptAt、exhausted（重试穷尽终态）。
 *   - 重试调度器（[[HeldDeliveryWatchdog]]）按 producedAt + 退避间隔判定到期，到期发
 *     `AgentCommand.DrainHeldReceipts` 催注；attempts 穷尽 → 置 exhausted（**终态落盘**
 *     ——回执本体已随 injection-queues 磁盘队列持久，本旗 = 「父会话 N 条未读回执」
 *     徽标的数据源），调度器停止催注，父会话任何后续事件的「先补注入未读回执」腿
 *     消费之。
 *   - 会话侧每次 pendingEvents 变动（hold / drain / flush）经 [[syncFromEvents]] 对账：
 *     已投递（不在 pendingEvents）的行删除，新 held 的行补记——producedAt/attempts
 *     跨对账保真。
 *
 * 存储：`<dataRoot>/sessions/<sessionId>/held-receipts.json`（AtomicJson 同款原子写）。
 * 写失败只 WARN 绝不阻断主路径（簿记面纪律，同 CompactionQueueStore）。
 */
object HeldReceiptStore:

  private val logger = NebflowLogger.forName("nebflow.core.processor.held-receipts")

  /** 一条 held 回执的簿记行（纯元数据；载荷在 pendingEvents / injection-queues.json）。 */
  final case class HeldReceipt(
    /** 回执身份：correlationId（delegate/subtask 终态 = 子会话 id）优先，否则 payload 摘要。 */
    id: String,
    /** 事件源（"delegate" | "subtask" | …）——徽标与日志展示用。 */
    source: String,
    /** 事件类型（completed / failed / cancelled / …）。 */
    eventType: String,
    /** 载荷头部（≤120 字符）——徽标 tooltip / 台账展示，绝不承载全文。 */
    summary: String,
    /** 回执产出（被 HOLD）时刻，epoch ms——退避计时基点。 */
    producedAt: Long,
    /** 已执行的催注投递尝试次数（0 = 尚未催注过）。 */
    attempts: Int = 0,
    /** 上次催注时刻（0 = 未催注过）。 */
    lastAttemptAt: Long = 0L,
    /** 重试穷尽终态：true = 调度器停止催注，等父会话下一事件的「先补注入」腿消费。 */
    exhausted: Boolean = false
  )

  // ── 编解码（手工小面，向后兼容缺键容忍） ──

  given Encoder[HeldReceipt] = Encoder.instance { r =>
    Json.obj(
      "id" -> r.id.asJson,
      "source" -> r.source.asJson,
      "eventType" -> r.eventType.asJson,
      "summary" -> r.summary.asJson,
      "producedAt" -> r.producedAt.asJson,
      "attempts" -> r.attempts.asJson,
      "lastAttemptAt" -> r.lastAttemptAt.asJson,
      "exhausted" -> r.exhausted.asJson
    )
  }

  given Decoder[HeldReceipt] = Decoder.instance { c =>
    for
      id <- c.get[String]("id")
      source <- c.get[String]("source")
      eventType <- c.get[String]("eventType")
      summary <- c.get[String]("summary")
      producedAt <- c.get[Long]("producedAt")
      attempts <- c.get[Option[Int]]("attempts")
      lastAttemptAt <- c.get[Option[Long]]("lastAttemptAt")
      exhausted <- c.get[Option[Boolean]]("exhausted")
    yield HeldReceipt(
      id,
      source,
      eventType,
      summary,
      producedAt,
      attempts.getOrElse(0),
      lastAttemptAt.getOrElse(0L),
      exhausted.getOrElse(false)
    )
  }

  private def fileOf(sessionId: String): os.Path =
    PathUtil.dataRoot / "sessions" / sessionId / "held-receipts.json"

  /** 读全量台账（缺失/损坏 = Nil，绝不抛）。 */
  def load(sessionId: String): IO[List[HeldReceipt]] =
    if sessionId.isEmpty then IO.pure(Nil)
    else
      IO.blocking {
        val f = fileOf(sessionId)
        if !os.exists(f) then Nil
        else decode[List[HeldReceipt]](os.read(f)).getOrElse {
          logger.warn(s"HeldReceiptStore.load: corrupt ledger for $sessionId — treated as empty")
          Nil
        }
      }

  private def save(sessionId: String, entries: List[HeldReceipt]): IO[Unit] =
    if sessionId.isEmpty then IO.unit
    else if entries.isEmpty then clear(sessionId)
    else
      IO.blocking {
        val f = fileOf(sessionId)
        if !os.exists(f / os.up) then os.makeDir.all(f / os.up)
        AtomicJson.writeSync(f, entries.asJson.noSpaces)
      }.void
        .handleErrorWith(e => logger.warn(s"HeldReceiptStore.save failed for $sessionId: ${e.getMessage}").void)

  /** 删除台账文件（全部回执已投递）。 */
  def clear(sessionId: String): IO[Unit] =
    if sessionId.isEmpty then IO.unit
    else
      IO.blocking {
        val f = fileOf(sessionId)
        if os.exists(f) then os.remove(f)
      }.void
        .handleErrorWith(e => logger.warn(s"HeldReceiptStore.clear failed for $sessionId: ${e.getMessage}").void)

  /**
   * 对账单点（actor 侧每次 pendingEvents 变动后调用）：以**当前 held 集合**为准——
   *   - 新出现的回执 → 补行（producedAt = now）；
   *   - 仍在 held 的行 → 簿记原样保真（producedAt/attempts 不重置）；
   *   - 已不在 held（已投递/已 flush）的行 → 删除（exhausted 行同样随投递消亡——
   *     「未读」是投递态谓词，投递即已读）。
   * 返回对账后的行集（供调用方发徽标帧），写失败不影响返回。
   */
  def syncFromEvents(
    sessionId: String,
    held: List[AgentCommand.ExternalEvent],
    now: Long = System.currentTimeMillis()
  ): IO[List[HeldReceipt]] =
    if sessionId.isEmpty then IO.pure(Nil)
    else
      load(sessionId).flatMap { existing =>
        val byId = existing.map(e => e.id -> e).toMap
        val reconciled = held.map { ev =>
          byId.getOrElse(
            HeldDeliveryPolicy.receiptId(ev),
            HeldReceipt(
              id = HeldDeliveryPolicy.receiptId(ev),
              source = ev.source,
              eventType = ev.eventType,
              summary = ev.payload.take(120),
              producedAt = now
            )
          )
        }
        // 幂等短路：集合身份与簿记均无变化时不重写（防高频 hold 点刷盘）。
        val changed =
          reconciled.map(_.id) != existing.map(_.id) ||
            reconciled.exists(r => byId.get(r.id).exists(b => b.producedAt != r.producedAt))
        val writeIO = if changed then save(sessionId, reconciled) else IO.unit
        writeIO.as(reconciled)
      }

  /** 催注记账：attempts+1（幂等键 = id；未知 id 忽略——对账竞态的宽容面）。 */
  def markAttempted(sessionId: String, ids: List[String], now: Long): IO[Unit] =
    mutate(sessionId) { entries =>
      entries.map { e =>
        if ids.contains(e.id) then e.copy(attempts = e.attempts + 1, lastAttemptAt = now) else e
      }
    }

  /** 穷尽终态置旗（终态落盘：旗持久在台账，徽标据此计「未读」）。 */
  def markExhausted(sessionId: String, ids: List[String]): IO[Unit] =
    mutate(sessionId)(entries =>
      entries.map(e => if ids.contains(e.id) && !e.exhausted then e.copy(exhausted = true) else e)
    )

  /** exhausted（未读）行。 */
  def unread(sessionId: String): IO[List[HeldReceipt]] =
    load(sessionId).map(_.filter(_.exhausted))

  private def mutate(sessionId: String)(f: List[HeldReceipt] => List[HeldReceipt]): IO[Unit] =
    if sessionId.isEmpty then IO.unit
    else
      load(sessionId).flatMap { entries =>
        val next = f(entries)
        if next == entries then IO.unit else save(sessionId, next)
      }

end HeldReceiptStore

/**
 * 投递重试策略（纯函数面，spec 判红锚）：退避节奏与穷尽判定的唯一定义处。
 *
 * 节奏（Defaults 单源）：首催 = producedAt + HeldDeliveryFirstRetryMs（默认 10min——
 * 「回合结束即有已产出回执但 N 分钟未投递 → 判投递失败触发补投」的 N）；此后 ×2
 * 封顶 HeldDeliveryMaxBackoffMs（30min）。催注次数 HeldDeliveryMaxAttempts（5）穷尽
 * → exhausted 终态（约 10+20+30+30+30 ≈ 2h 后落旗）。
 */
object HeldDeliveryPolicy:

  /** 回执身份单点：correlationId 优先（delegate/subtask 终态恒带子会话 id），否则 payload sha256 头。 */
  def receiptId(e: AgentCommand.ExternalEvent): String =
    e.correlationId.filter(_.nonEmpty).getOrElse {
      val md = MessageDigest.getInstance("SHA-256")
      md.digest(e.payload.getBytes("UTF-8")).take(8).map("%02x" format _).mkString
    }

  /** 第 attempt 次催注（attempt 从 1 计）应距 producedAt 的毫秒数：10min → 20 → 30 封顶。 */
  def delayMs(attempt: Int): Long =
    val first = Defaults.HeldDeliveryFirstRetryMs
    val capped = BigDecimal(first).*(math.pow(2, math.max(0, attempt - 1))).toLong
    math.min(capped, Defaults.HeldDeliveryMaxBackoffMs)

  /** 到期判定：now ≥ producedAt + delay(已催注次数 + 1)。 */
  def isDue(rec: HeldReceiptStore.HeldReceipt, now: Long): Boolean =
    now >= rec.producedAt + delayMs(rec.attempts + 1)

  /** 穷尽判定：下一次催注将超过 HeldDeliveryMaxAttempts ⇒ 置终态、不再催注。 */
  def shouldExhaust(rec: HeldReceiptStore.HeldReceipt): Boolean =
    rec.attempts + 1 > Defaults.HeldDeliveryMaxAttempts

  /**
   * 未读回执的「先补注入」reminder 文案（fix 1 的第三腿：父会话任何后续事件先补
   * 注入未读回执再处理事件）。空集 = None（既有路径零影响）。
   */
  def unreadReminder(events: List[AgentCommand.ExternalEvent]): Option[String] =
    if events.isEmpty then None
    else
      val lines = events.zipWithIndex.map((e, i) => s"${i + 1}. ${e.payload}")
      Some(
        s"<system-reminder>\n${events.size} held delivery receipt(s) could not be injected earlier " +
          s"(sub-agent barrier wedged — issue #31 class); they are delivered now, BEFORE this input:\n\n" +
          lines.mkString("\n\n") + "\n</system-reminder>"
      )

end HeldDeliveryPolicy
