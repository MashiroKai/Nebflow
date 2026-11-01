package nebflow.core.mail

import cats.effect.IO
import cats.syntax.all.*
import io.circe.*
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.actor.AgentCommand
import nebflow.core.AtomicJson
import nebflow.shared.given
import nebflow.shared.{ContentBlock, NebflowLogger, PathUtil}

/**
 * SessionMailbox —— **收件方会话的持久邮箱**（终态通知 actor 邮箱化批 P1 底座，
 * 作者 2026-11-01 08:53 裁定；设计
 * `docs/design/20261101_090600_delegate-terminal-mailbox-design__delegate-nebflow-4a58b3db.zh-CN.md`）。
 *
 * == 语义反转（本件的全部存在理由）==
 *
 * 旧 push 面：actor 消息是投递、磁盘是兜底——四层补丁（投递标记/同键判重/watchdog
 * 重投/held 台账）真源互不同步，10-31 实证断链（标记已投/父未消费/重投被压制）。
 * 本件把语义反转：**磁盘邮箱是唯一真源，actor 消息只是门铃**。
 *
 *   - **入队** = `mailOnce`：每事件一个原子落盘文件（tmp+rename，写成功=必达）。
 *     同 identity 条目已在 pending ⇒ no-op（幂等键防写重，不防读重）。
 *   - **消费** = 唤醒点 drain（见 [[nebflow.agent.MailboxDrain]]）：取走 = 原子
 *     rename 进 `taken/`，注入回合后 unlink——「已投递」= 条目已删除这一物理事实，
 *     不再是任何标记。
 *   - **门铃** = `ref ! AgentCommand.MailboxArrived`，尽力而为：投给死会话蒸发也
 *     无所谓，信已在盘上，会话任何唤醒点都会扫箱。
 *
 * == 五不变式（设计 §6，P1 逐条落地）==
 *
 *   - I1 必达：`mailOnce` 返回 Enqueued ⇔ 条目在盘 ⇔ 任何唤醒点必被扫出。
 *   - I2 恰一次（进程正常期）：一事件一拷贝一消费口；离开 pending 的唯一路径是
 *     drain 的原子 rename。
 *   - I3 崩溃残差：任意点 kill -9 ⇒ 至多一次重复、零丢失、永不放大（重达件身份
 *     不变——「重投改写 correlationId」时代终结）。
 *   - I4 顺序：同箱严格 FIFO（seq 名字序；seq 全局单调，pending∪taken 取 max+1，
 *     taken 也占序防重用）；批门禁是调度不是重排；quarantine 件不占 FIFO。
 *   - I5 无 ack：生产者职责止于入队；「已投递未确认」状态类物理不存在。
 *
 * == 崩溃窗口与恢复规则（设计 §6 表，kill -9 四窗矩阵的恢复面）==
 *
 *   - 入队半写（tmp 完未 rename）：条目不可见；tmp 残片由 [[sweepTmpAll]]（boot）
 *     清理；生产者重试同 identity 重入队（幂等）。
 *   - 取走前崩：条目在 pending → 下个唤醒点照常扫出（零损失零重复）。
 *   - 取走后注入前崩：条目在 taken → `scanTaken` 于恢复点重注（≤1 重复，零丢失）。
 *   - 注入后清前崩：同上（重注一次 = 恰一次重复上限；红线：宁一重复不丢一件）。
 *   - 磁盘故障：mailOnce 返回 Failed——生产者腿显式失败（响亮 WARN），绝不静默；
 *     boot 对账（[[MailboxBootSweep]]）以账面合成补投。
 *
 * == 存储（开放决策①采纳推荐案：per-entry 文件）==
 *
 * 前例：[[nebflow.core.delegate.DeviceResultOutbox]]（一条一件 + 序前缀有序 +
 * 零墙钟纪律）。单 JSONL 备选案否决理由见设计 §3.2（取走即清需整文件重写，
 * 临界区与崩溃窗口双劣化）。积压零墙钟：无 TTL、无自动丢弃（开放决策⑤）；
 * 可见性 = `depth`（徽标面）。
 *
 * 分层纪律：本件 ∈ core.mail（core 层，零 agent 依赖）；agent 层消费经
 * [[nebflow.agent.MailboxDrain]]；ExternalEvent codec 本地手写（与
 * `CompactionQueueStore` 同形同键，迁移期两 surface 字节兼容）。
 */
object SessionMailbox:

  private val logger = NebflowLogger.forName("nebflow.core.mail.session-mailbox")

  /** 邮箱条目（信封 + 载荷）。`identity` = 入队幂等键（防写重，非判重键）。 */
  final case class Entry(
    seq: Long,
    identity: String,
    /** 条目族：delegate-terminal | escalation | …（观测面；drain 谓词不读它）。 */
    kind: String,
    /** true = 同 identity 入队为**原位合条**（更新载荷+压制计数，序不变）——风暴闸换轴面。 */
    coalesce: Boolean,
    enqueuedAt: Long,
    producer: String,
    /** 风暴窗内被本条目合条吸收的同因事件数（合条政策承载位）。 */
    suppressedCount: Int = 0,
    event: AgentCommand.ExternalEvent
  )

  object Entry:
    given Encoder[Entry] = Encoder.instance { e =>
      Json.obj(
        "seq" -> e.seq.asJson,
        "identity" -> e.identity.asJson,
        "kind" -> e.kind.asJson,
        "coalesce" -> e.coalesce.asJson,
        "enqueuedAt" -> e.enqueuedAt.asJson,
        "producer" -> e.producer.asJson,
        "suppressedCount" -> e.suppressedCount.asJson,
        "event" -> e.event.asJson
      )
    }
    given Decoder[Entry] = Decoder.instance { c =>
      for
        seq <- c.downField("seq").as[Long]
        identity <- c.downField("identity").as[String]
        kind <- c.downField("kind").as[String]
        coalesce <- c.downField("coalesce").as[Option[Boolean]]
        enqueuedAt <- c.downField("enqueuedAt").as[Long]
        producer <- c.downField("producer").as[Option[String]]
        suppressedCount <- c.downField("suppressedCount").as[Option[Int]]
        event <- c.downField("event").as[AgentCommand.ExternalEvent]
      yield Entry(
        seq,
        identity,
        kind,
        coalesce.getOrElse(false),
        enqueuedAt,
        producer.getOrElse(""),
        suppressedCount.getOrElse(0),
        event
      )
    }
  end Entry

  /** mailOnce 结果（生产者职责止于 Enqueued/Coalesced；Failed = 显式失败）。 */
  sealed trait MailResult extends Product with Serializable
  object MailResult:
    /** 条目已落盘（写成功=必达）。 */
    final case class Enqueued(entry: Entry) extends MailResult
    /** 同 identity 条目已在 pending（非合条模式）——幂等 no-op。 */
    case object AlreadyPresent extends MailResult
    /** 合条模式：载荷已原位并入既有条目（suppressedCount+1，序不变）。 */
    final case class Coalesced(entry: Entry) extends MailResult
    /** 落盘失败（磁盘面）——生产者必须显式处置（重试/响亮 WARN），绝不静默。 */
    final case class Failed(cause: String) extends MailResult
  end MailResult

  // ── 存储路径 ──────────────────────────────────────────────────────────

  private def boxDir(sid: String): os.Path = PathUtil.dataRoot / "sessions" / sid / "mailbox"
  private def pendingDir(sid: String): os.Path = boxDir(sid) / "pending"
  private def takenDir(sid: String): os.Path = boxDir(sid) / "taken"
  private def quarantineDir(sid: String): os.Path = boxDir(sid) / "quarantine"

  /** id 安全（作文件名段）：非白名单字符替换，防路径穿越（DeviceResultOutbox 同款）。 */
  def safeId(id: String): String =
    val cleaned = id.map(c => if c.isLetterOrDigit || c == '-' || c == '.' || c == '_' then c else '_')
    if cleaned.nonEmpty then cleaned.take(120) else "anon"

  private def pendingFileOf(sid: String, e: Entry): os.Path =
    pendingDir(sid) / s"${seqOf(e)}-${safeId(e.identity)}.json"
  private def takenFileOf(sid: String, e: Entry): os.Path =
    takenDir(sid) / s"${seqOf(e)}-${safeId(e.identity)}.json"
  private def seqOf(e: Entry): String = f"${e.seq}%012d"

  private def parseSeq(name: String): Option[Long] =
    name.take(12).takeWhile(_.isDigit) match
      case d if d.length == 12 => scala.util.Try(d.toLong).toOption
      case _ => None

  /** 全程程序级互斥（seq 分配 + 入队/合条读-改-写原子性；通知频率下无争用）。 */
  private val lock = new Object

  // ── 写入口：mailOnce（唯一入队口）────────────────────────────────────

  /**
   * 幂等入队（I1/I2）。同 identity 已在 pending：
   *   - `coalesce=false` ⇒ AlreadyPresent（防写重——delegate 终态类每终态独立事件）；
   *   - `coalesce=true`  ⇒ 原位合条（更新载荷 + suppressedCount+1，seq/FIFO 位不变
   *     ——风暴闸换轴：同类升级压条不丢弃，设计 §4.5）。
   * 落盘 = tmp + 原子 rename（AtomicJson）。返回 Failed 时条目**不在盘上**（I1
   * 不成立），调用方必须显式处置。
   */
  def mailOnce(
    recipientSid: String,
    event: AgentCommand.ExternalEvent,
    kind: String,
    identity: String,
    coalesce: Boolean = false,
    producer: String = ""
  ): IO[MailResult] =
    if recipientSid.trim.isEmpty then IO.pure(MailResult.Failed("recipient session id is empty"))
    else if identity.trim.isEmpty then IO.pure(MailResult.Failed("entry identity is empty"))
    else
      IO.blocking {
        lock.synchronized {
          val now = System.currentTimeMillis()
          existingPending(recipientSid, identity) match
            case Some(e) if !coalesce => MailResult.AlreadyPresent
            case Some(e) =>
              // 原位合条：同文件名（seq 不变）重写载荷；压制计数累加。放行条目
              // 携带「已合条 N 条」摘要（设计 §2.5/§4.5：风暴的代价被合条吸收，
              // 一条不丢且风暴规模父侧可感知）。
              val mergedCount = e.suppressedCount + 1
              val annotated = event.copy(
                payload = event.payload +
                  s"\n[escalation-notify: $mergedCount same-cause notice(s) coalesced into this entry — nothing dropped]"
              )
              val merged = e.copy(event = annotated, enqueuedAt = now, suppressedCount = mergedCount)
              AtomicJson.writeSync(pendingFileOf(recipientSid, merged), merged.asJson.noSpaces)
              MailResult.Coalesced(merged)
            case None =>
              val seq = nextSeq(recipientSid)
              val e = Entry(seq, identity, kind, coalesce, now, producer, 0, event)
              os.makeDir.all(pendingDir(recipientSid))
              AtomicJson.writeSync(pendingFileOf(recipientSid, e), e.asJson.noSpaces)
              MailResult.Enqueued(e)
        }
      }.handleErrorWith(e => IO.pure(MailResult.Failed(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))))

  /** pending 里同 identity 条目（幂等键查重；坏形件不参与）。 */
  private def existingPending(sid: String, identity: String): Option[Entry] =
    scanDir(sid, pendingDir(sid)).collectFirst { case (e, _) if e.identity == identity => e }

  /**
   * 下一个 seq：pending∪taken 名字序 max+1（taken 占序防重用；空箱 = 1）。
   * 占序的保证域 = taken 文件尚存期间（unlink 清空后整箱已消费完毕，seq 从 1
   * 重新起算不构成乱序——I4 的 FIFO 保证只在共存条目之间）。
   */
  private def nextSeq(sid: String): Long =
    val pend = listNames(pendingDir(sid)).flatMap(parseSeq)
    val taken = listNames(takenDir(sid)).flatMap(parseSeq)
    (pend ++ taken).foldLeft(0L)(math.max) + 1

  private def listNames(dir: os.Path): List[String] =
    if !os.exists(dir) then Nil
    else
      try os.list(dir).map(_.last).toList.sorted
      catch case _: Throwable => Nil

  // ── 读口 ────────────────────────────────────────────────────────────

  /**
   * pending 全量（FIFO = seq 名字序）。坏形条目（解码失败）→ 移 quarantine +
   * WARN，**不阻塞后件**（I4）；tmp 残片忽略。
   */
  def scanPending(sid: String): IO[List[Entry]] =
    IO.blocking {
      val (entries, quarantined) = scanDirWithQuarantine(sid, pendingDir(sid))
      quarantined.foreach(name =>
        logger.warn(s"SessionMailbox: malformed pending entry quarantined (does not block FIFO): $name")
      )
      entries
    }.handleErrorWith(e =>
      logger.warn(s"SessionMailbox: pending scan failed for '$sid' (${e.getMessage}) — treated as empty").as(Nil)
    )

  /** [[scanPending]] 的同步面（行为体 val 位消费；fail-soft 空集）。 */
  def scanPendingSync(sid: String): List[Entry] =
    try
      val (entries, quarantined) = scanDirWithQuarantine(sid, pendingDir(sid))
      quarantined.foreach(name =>
        logger.warnSync(s"SessionMailbox: malformed pending entry quarantined (does not block FIFO): $name")
      )
      entries
    catch
      case e: Throwable =>
        logger.warnSync(s"SessionMailbox: pending scan failed for '$sid' (${e.getMessage}) — treated as empty")
        Nil

  /**
   * taken 全量（恢复点重注面：取走后注入前崩 / 注入后清前崩的残差）。条目**留在
   * taken**——重注成功后由注入点的 unlinkTaken 物理清除（≤1 重复，零丢失）。
   */
  def scanTaken(sid: String): IO[List[Entry]] =
    IO.blocking {
      val (entries, quarantined) = scanDirWithQuarantine(sid, takenDir(sid))
      quarantined.foreach(name => logger.warn(s"SessionMailbox: malformed taken entry quarantined: $name"))
      entries
    }.handleErrorWith(e =>
      logger.warn(s"SessionMailbox: taken scan failed for '$sid' (${e.getMessage}) — treated as empty").as(Nil)
    )

  /** [[scanTaken]] 的同步面（行为体 val 位消费；fail-soft 空集）。 */
  def scanTakenSync(sid: String): List[Entry] =
    try
      val (entries, quarantined) = scanDirWithQuarantine(sid, takenDir(sid))
      quarantined.foreach(name => logger.warnSync(s"SessionMailbox: malformed taken entry quarantined: $name"))
      entries
    catch
      case e: Throwable =>
        logger.warnSync(s"SessionMailbox: taken scan failed for '$sid' (${e.getMessage}) — treated as empty")
        Nil

  /** 徽标面（设计 §2.3：heldReceipts 徽标换数据源 = 邮箱 pending 深度）。 */
  def depth(sid: String): IO[Int] =
    IO.blocking(listNames(pendingDir(sid)).count(n => n.endsWith(".json") && parseSeq(n).isDefined))
      .handleErrorWith(_ => IO.pure(0))

  // ── 消费原语（drain 的物理面）────────────────────────────────────────

  /**
   * 取走一批（I2：离开 pending 的唯一路径）。逐条原子 rename 进 taken/；单条
   * rename 失败 = 该条留在 pending（安全侧——下个唤醒点重扫），其余照常。
   * 返回成功取走的条目（名字序）。
   */
  def take(sid: String, entries: List[Entry]): IO[List[Entry]] =
    if entries.isEmpty then IO.pure(Nil)
    else IO.blocking(takeSync(sid, entries)).handleErrorWith { e =>
      logger.warn(s"SessionMailbox: take failed for '$sid' (${e.getMessage}) — entries stay pending").as(Nil)
    }

  /** [[take]] 的同步面（行为体 val 位消费；本地小目录，DeviceResultOutbox.pendingSync 同款先例）。 */
  def takeSync(sid: String, entries: List[Entry]): List[Entry] =
    if entries.isEmpty then Nil
    else
      lock.synchronized {
        try
          os.makeDir.all(takenDir(sid))
          entries.flatMap { e =>
            val src = pendingFileOf(sid, e)
            if !os.exists(src) then Nil // 已被并发取走/清走：I2 单消费口，跳过
            else
              os.move(src, takenFileOf(sid, e))
              List(e)
          }
        catch
          case e: Throwable =>
            logger.warnSync(s"SessionMailbox: take failed for '$sid' (${e.getMessage}) — entries stay pending")
            Nil
      }

  /**
   * 消费完成：按身份清除 taken 条目（「已投递」= 条目已删除的物理事实）+ 落
   * **消费墓碑**（[[markDelivered]]——boot 对账区分「已消费」与「从未入箱」的
   * 唯一依据，防合成腿把已交付终态再造一遍）。幂等；失败只 WARN（残差留给
   * 恢复点 scanTaken 重注——宁一重复不丢一件）。
   */
  def unlinkTaken(sid: String, identities: List[String]): IO[Unit] =
    val clean = identities.filter(_.nonEmpty).toSet
    if clean.isEmpty then IO.unit
    else
      IO.blocking {
        lock.synchronized {
          scanDir(sid, takenDir(sid)).foreach { (e, path) =>
            if clean.contains(e.identity) && os.exists(path) then os.remove(path)
          }
          markDeliveredSync(sid, clean.toList)
        }
      }.void.handleErrorWith(e =>
        logger.warn(s"SessionMailbox: unlinkTaken failed for '$sid': ${e.getMessage}").void
      )

  // ── 消费墓碑（boot 对账真源；零墙钟不设 TTL——开放决策⑤）────────────────

  /** 墓碑容量（drop-oldest）：只服务「对账不重合成」，老条目出窗后的残余风险见 MailboxBootSweep 头注。 */
  val MaxDeliveredEntries: Int = 1024

  final case class Delivered(v: Int = 1, ids: List[String] = Nil)
  object Delivered:
    given Encoder[Delivered] = Encoder.instance(d => Json.obj("v" -> d.v.asJson, "ids" -> d.ids.asJson))
    given Decoder[Delivered] = Decoder.instance { c =>
      for v <- c.get[Option[Int]]("v"); ids <- c.get[Option[List[String]]]("ids")
      yield Delivered(v.getOrElse(1), ids.getOrElse(Nil))
    }
  end Delivered

  private def deliveredFile(sid: String): os.Path = boxDir(sid) / "delivered.json"

  /** 消费墓碑落账（unlinkTaken 消费点同事务邻域；幂等追加 drop-oldest）。 */
  private def markDeliveredSync(sid: String, ids: List[String]): Unit =
    val clean = ids.filter(_.nonEmpty).distinct
    if clean.nonEmpty then
      val f = deliveredFile(sid)
      val existing =
        if !os.exists(f) then Delivered()
        else decode[Delivered](os.read(f)).getOrElse(Delivered())
      val merged = existing.ids.filterNot(clean.contains) ::: clean
      val capped = merged.takeRight(MaxDeliveredEntries)
      if capped != existing.ids then
        os.makeDir.all(boxDir(sid))
        AtomicJson.writeSync(f, Delivered(existing.v, capped).asJson.noSpaces)

  /** 墓碑查询（boot 对账面；坏形文件 = 空集）。 */
  def deliveredContains(sid: String, identities: Set[String]): IO[Set[String]] =
    if identities.isEmpty then IO.pure(Set.empty)
    else
      IO.blocking {
        val f = deliveredFile(sid)
        if !os.exists(f) then Set.empty[String]
        else
          decode[Delivered](os.read(f)) match
            case Right(d) => d.ids.toSet.intersect(identities)
            case Left(_) => Set.empty[String]
      }.handleErrorWith(_ => IO.pure(Set.empty[String]))

  // ── 内部扫描（同步面）────────────────────────────────────────────────

  /** (好形条目按名字序, 被隔离的坏形件名)。 */
  private def scanDirWithQuarantine(sid: String, dir: os.Path): (List[Entry], List[String]) =
    val quarantined = scala.collection.mutable.ListBuffer[String]()
    val entries =
      if !os.exists(dir) then Nil
      else
        os.list(dir)
          .toList
          .filter(p => p.ext == "json" && parseSeq(p.last).isDefined)
          .sortBy(p => (parseSeq(p.last).getOrElse(0L), p.last))
          .flatMap { p =>
            decode[Entry](os.read(p)) match
              case Right(e) => Some(e)
              case Left(_) =>
                quarantine(sid, p)
                quarantined += p.last
                None
          }
    (entries, quarantined.toList)

  /** (好形条目按名字序, 所在路径)——unlink 类精确清除用。 */
  private def scanDir(sid: String, dir: os.Path): List[(Entry, os.Path)] =
    if !os.exists(dir) then Nil
    else
      os.list(dir)
        .toList
        .filter(p => p.ext == "json" && parseSeq(p.last).isDefined)
        .sortBy(p => (parseSeq(p.last).getOrElse(0L), p.last))
        .flatMap(p => decode[Entry](os.read(p)).toOption.map(e => e -> p))

  private def quarantine(sid: String, p: os.Path): Unit =
    try
      os.makeDir.all(quarantineDir(sid))
      os.move(p, quarantineDir(sid) / s"${p.last}.bad", createFolders = true)
    catch case _: Throwable => () // 隔离失败绝不阻塞扫描（人工处置面）

  // ── boot 面 ─────────────────────────────────────────────────────────

  /**
   * 清理入队半写残片（`*.tmp`——AtomicJson 崩溃窗口的不可见垃圾，设计 §6 窗口1
   * 恢复规则）。幂等；对全部会话箱执行（boot sweep 消费点）。
   */
  def sweepTmpAll(): IO[List[String]] =
    IO.blocking {
      val sessionsDir = PathUtil.dataRoot / "sessions"
      if !os.exists(sessionsDir) then Nil
      else
        os.list(sessionsDir).toList.flatMap { s =>
          val pend = s / "mailbox" / "pending"
          if !os.exists(pend) then Nil
          else
            val tmps = os.list(pend).toList.filter(_.last.endsWith(".tmp"))
            tmps.foreach(t => try os.remove(t) catch case _: Throwable => ())
            tmps.map(_.last)
        }
    }.handleErrorWith(e =>
      logger.warn(s"SessionMailbox: tmp sweep failed (${e.getMessage})").as(Nil)
    )

  /** 测试/运维清空（正常路径绝不调用——零删除纪律）。 */
  def clearAll(sid: String): IO[Unit] =
    IO.blocking(if os.exists(boxDir(sid)) then os.remove.all(boxDir(sid))).void

  // ── ExternalEvent codec（与 CompactionQueueStore 同形同键；本地手写防层向依赖）──

  given Encoder[AgentCommand.ExternalEvent] = Encoder.instance { e =>
    val base = Json.obj(
      "source" -> e.source.asJson,
      "eventType" -> e.eventType.asJson,
      "payload" -> e.payload.asJson,
      "metadata" -> Json.fromJsonObject(e.metadata),
      "correlationId" -> e.correlationId.asJson
    )
    e.blocks.fold(base)(b => base.deepMerge(Json.obj("blocks" -> b.asJson)))
  }

  given Decoder[AgentCommand.ExternalEvent] = Decoder.instance { c =>
    for
      source <- c.downField("source").as[String]
      eventType <- c.downField("eventType").as[String]
      payload <- c.downField("payload").as[String]
      metadata <- c.downField("metadata").as[Option[JsonObject]]
      correlationId <- c.downField("correlationId").as[Option[String]]
      blocks <- c.downField("blocks").as[Option[List[ContentBlock]]]
    yield AgentCommand
      .ExternalEvent(source, eventType, payload, metadata.getOrElse(JsonObject.empty), correlationId)
      .copy(blocks = blocks)
  }

  // ── 身份约定（入队幂等键的构造单点）──────────────────────────────────

  /** P2 迁移面判据：delegate 终态（source=delegate × completed/failed/cancelled）。 */
  def migratedTerminalEvent(e: AgentCommand.ExternalEvent): Boolean =
    e.source == "delegate" && Set("completed", "failed", "cancelled").contains(e.eventType)

  /** delegate 终态条目身份 = 子会话 id（每终态独立事件，永不合条）。 */
  def delegateTerminalIdentity(correlationId: Option[String]): Option[String] =
    correlationId.map(_.trim).filter(_.nonEmpty)

  /** 升级条目身份 = 同因升级键（failed 会话）——风暴窗内原位合条。 */
  def escalationIdentity(failedSessionId: String): String = s"escalation-$failedSessionId"
end SessionMailbox
