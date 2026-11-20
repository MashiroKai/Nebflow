package nebflow.core.mail

import cats.effect.IO
import io.circe.*
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.core.AtomicJson
import nebflow.shared.{NebflowLogger, PathUtil}

/**
 * InputMailbox —— 用户输入/注入输入的**持久邮箱**（P3 磁盘邮箱批 · 节点 2）。
 *
 * 作者裁定 2026-11-05 10:16：Entry 物理布局选 **A** ——
 * 同一邮箱根下另开 `pending-input/` + `taken-input/`，与 event 流(`pending/`+`taken/`)
 * **物理隔离**。理由：对既有 event drain(`MailboxDrain`) 爆炸半径为 0；输入之间的
 * 顺序独立性天然满足。否决案 B（单目录把 `event` 改 `Option`）因会触碰别批拥有的
 * `MailboxBootSweep`/`MailboxDrain`/`RedAnchor` 消费面；否决案 C（单目录 + 占位
 * event）因输入会被当外部事件注入 = 事故。
 *
 * == 三条不变量（作者 10:16 钉，逐条有断言）==
 *
 *   - **I-ISO 物理隔离**：输入条目落在 `pending-input/`/`taken-input/`，**永不**进入
 *     `SessionMailbox.scanPending/scanTaken`（event 面）的可见范围。结构保证：不同
 *     目录 + 本件不复用 event 的目录常量。断言：写输入后 event 两个扫描面均空。
 *   - **I-ORD 输入自身顺序**：`seq` 全局单调（`pending-input ∪ taken-input` 取 max+1，
 *     `taken-input` 也占序防重用），文件名为 `%012d-<safeIdentity>.json`，扫描按名字序
 *     ⇒ 保序。断言：连续 N 条输入 take 前后顺序一致。
 *   - **I-IDEM 重放不重复执行**：消费墓碑 `delivered-inputs.json`（与 event 墓碑
 *     **分文件**，键空间互不干扰）在 `taken-input` 侧仍可判「此 key 已执行过」。
 *     断言：同 key 二次 enqueue ⇒ `AlreadyPresent`；已执行 key 的重放尝试 ⇒ 跳过，
 *     执行计数 = 1。
 *
 * == 与 `SessionMailbox`（event 面）的关系 ==
 * **同一套机制、同一个根、两条流**：同名目录纪律（seq 前缀定序）、同一个原子写
 * `AtomicJson.writeSync`（tmp + ATOMIC_MOVE）、同一个 quarantine 策略、同一个
 * 「墓碑 = 已执行」物理事实。**不新造第三种落盘机制**（作者 09:37 明令）。
 * 刻意不复用 `SessionMailbox.Entry`：它的 `event: ExternalEvent` 装载不下输入，
 * 且改它会改 event 消费面的类型（正是 A 案要避免的爆炸）。
 *
 * == 崩溃窗口矩阵（与 event 面同构）==
 *   - 入队半写（tmp 完未 rename）：条目不可见，残片由 [[sweepTmpAll]] 清理；
 *     生产者以同 identity 重入队（幂等）。
 *   - 取走前崩：条目在 `pending-input/` ⇒ 下次扫箱原样扫出（零丢失零重复）。
 *   - 取走后注入前崩：条目在 `taken-input/` ⇒ [[scanTaken]] 在恢复点重投（≤1 重复）。
 *   - 注入后清前崩：墓碑已写 ⇒ 重放腿用 [[deliveredContains]] 跳过（**I-IDEM**）。
 *
 * 🔴 与节点 3 的接缝：本件的 `unlinkTaken` 是**独立语义单元**——调用方可以在
 * 「turn 真正提交之后」才调用它（D3），从而把「消费成功后才标记完成」做实；
 * 这与 event 面「注入前 unlink」的策略不同，**由调用点决定，本件不预设**。
 */
object InputMailbox:

  private val logger = NebflowLogger.forName("nebflow.core.mail.input-mailbox")

  /** 输入邮箱条目（信封 + 载荷）。`identity` = 入队幂等键（防写重，非判重键）。 */
  final case class InputEntry(
    seq: Long,
    identity: String,
    enqueuedAt: Long,
    producer: String,
    payload: InputPayload
  )

  object InputEntry:

    given Encoder[InputEntry] = Encoder.instance { e =>
      Json.obj(
        "seq" -> e.seq.asJson,
        "identity" -> e.identity.asJson,
        "enqueuedAt" -> e.enqueuedAt.asJson,
        "producer" -> e.producer.asJson,
        "payload" -> e.payload.asJson
      )
    }

    given Decoder[InputEntry] = Decoder.instance { c =>
      for
        seq <- c.get[Long]("seq")
        identity <- c.get[String]("identity")
        enqueuedAt <- c.get[Long]("enqueuedAt")
        producer <- c.get[Option[String]]("producer")
        payload <- c.get[InputPayload]("payload")
      yield InputEntry(seq, identity, enqueuedAt, producer.getOrElse(""), payload)
    }
  end InputEntry

  /** 入队结果（生产者职责止于 Enqueued；Failed = 显式失败，绝不静默）。 */
  sealed trait InputMailResult extends Product with Serializable

  object InputMailResult:
    final case class Enqueued(entry: InputEntry) extends InputMailResult

    /** 同 identity 已在 `pending-input/`（或已执行过）——幂等 no-op。 */
    case object AlreadyPresent extends InputMailResult
    final case class Failed(cause: String) extends InputMailResult

  // ── 存储路径（与 event 面同根、不同子目录）─────────────────────────────

  private def boxDir(sid: String): os.Path = PathUtil.dataRoot / "sessions" / sid / "mailbox"
  private def pendingDir(sid: String): os.Path = boxDir(sid) / "pending-input"
  private def takenDir(sid: String): os.Path = boxDir(sid) / "taken-input"

  /** 消费墓碑（**与 event 面 `delivered.json` 分文件**，键空间互不干扰）。 */
  private def deliveredFile(sid: String): os.Path = boxDir(sid) / "delivered-inputs.json"

  /** 文件名安全化：身份里可能带 `:`/`/`（如 `flow-notify:sid`），一律转 `_`。 */
  private[mail] def safeId(id: String): String =
    val cleaned = id.map(c => if c.isLetterOrDigit || c == '-' || c == '_' then c else '_')
    if cleaned.isEmpty then "unnamed" else cleaned.take(120)

  private def seqOf(e: InputEntry): String = f"${e.seq}%012d"

  private def pendingFileOf(sid: String, e: InputEntry): os.Path =
    pendingDir(sid) / s"${seqOf(e)}-${safeId(e.identity)}.json"

  private def takenFileOf(sid: String, e: InputEntry): os.Path =
    takenDir(sid) / s"${seqOf(e)}-${safeId(e.identity)}.json"

  private val lock = new Object

  // ── 入队（I-IDEM：同 identity 幂等）───────────────────────────────────

  /**
   * 幂等入队：同 identity 已在 `pending-input/` 或已出现在墓碑 ⇒ `AlreadyPresent`，
   * 零写盘。`identity` 由**调用点**按设计 §2.2（K1 原生事件 id / K2 `clientMessageId` /
   * K3 调用点铸造 ULID）提供，本件不生成——否则重试腿每轮铸新键会让幂等当场失效。
   */
  def enqueue(
    sid: String,
    payload: InputPayload,
    identity: String,
    producer: String = ""
  ): IO[InputMailResult] =
    if sid == null || sid.trim.isEmpty then IO.pure(InputMailResult.Failed("empty-session-id"))
    else if identity == null || identity.trim.isEmpty then IO.pure(InputMailResult.Failed("empty-identity"))
    else
      IO.blocking {
        lock.synchronized {
          try
            val alreadyPending = scanDir(pendingDir(sid)).exists(_.identity == identity)
            // 🔴 也要查 taken-input：条目在 turn 执行期间驻留该目录（D3：turn 成功后才
            // unlink）。若只查 pending + 墓碑，则「turn 在飞时客户端重发同 cid」会造出
            // 第二份副本 → 双跑（本批验收「同 cid 重发 ⇒ 执行计数=1」正是挡这个）。
            // 重放腿直接读 taken-input（不经本函数），故本判定不妨碍崩溃重投。
            val alreadyTaken = scanDir(takenDir(sid)).exists(_.identity == identity)
            if alreadyPending || alreadyTaken then InputMailResult.AlreadyPresent
            else if deliveredContainsSync(sid, Set(identity)).nonEmpty then
              // 已执行过：再投同 key 不得产生第二次执行（I-IDEM）
              InputMailResult.AlreadyPresent
            else
              val e = InputEntry(nextSeq(sid), identity, System.currentTimeMillis(), producer, payload)
              os.makeDir.all(pendingDir(sid))
              AtomicJson.writeSync(pendingFileOf(sid, e), e.asJson.noSpaces)
              InputMailResult.Enqueued(e)
          catch
            case t: Throwable =>
              InputMailResult.Failed(Option(t.getMessage).getOrElse(t.getClass.getName))
        }
      }.handleErrorWith(t =>
        logger
          .warn(s"InputMailbox.enqueue failed for '$sid' identity='$identity': ${t.getMessage}")
          .as(InputMailResult.Failed(Option(t.getMessage).getOrElse("unknown")))
      )

  /** On a fresh actor, restore producer-owned intake left pending or taken by its predecessor. */
  def replayProducerInputs(sid: String): IO[List[InputEntry]] = IO.blocking {
    lock.synchronized {
      val pending = scanDir(pendingDir(sid))
      val taken = scanDir(takenDir(sid)).filter(_.producer == ProducerInput.Producer)
      val delivered = deliveredContainsSync(sid, taken.map(_.identity).toSet)
      taken.filterNot(e => delivered.contains(e.identity)).foreach { e =>
        if !pending.exists(_.identity == e.identity) then
          os.makeDir.all(pendingDir(sid))
          os.move(takenFileOf(sid, e), pendingFileOf(sid, e))
      }
      scanDir(pendingDir(sid)).filter(_.producer == ProducerInput.Producer)
    }
  }

  /** 下一个 seq：`pending-input ∪ taken-input` 名字序 max+1（taken 占序防重用）。 */
  private def nextSeq(sid: String): Long =
    val pend = listNames(pendingDir(sid)).flatMap(parseSeq)
    val taken = listNames(takenDir(sid)).flatMap(parseSeq)
    (pend ++ taken).foldLeft(0L)(math.max) + 1

  private def parseSeq(name: String): Option[Long] =
    val idx = name.indexOf('-')
    if idx <= 0 then None
    else name.take(idx).toLongOption

  private def listNames(dir: os.Path): List[String] =
    if !os.exists(dir) then Nil else os.list(dir).toList.map(_.last).sorted

  // ── 扫描（按 seq 名字序 = 严格 FIFO）─────────────────────────────────

  /** `pending-input/` 全量（FIFO = seq 名字序）。坏形件移 quarantine，不阻断 FIFO。 */
  def scanPending(sid: String): IO[List[InputEntry]] =
    IO.blocking(scanPendingSync(sid))
      .handleErrorWith(e => logger.warn(s"InputMailbox.scanPending failed for '$sid': ${e.getMessage}").as(Nil))

  def scanPendingSync(sid: String): List[InputEntry] = scanDir(pendingDir(sid))

  /** `taken-input/` 全量（重放面：取走后未执行 / 已执行未清 的残差）。 */
  def scanTaken(sid: String): IO[List[InputEntry]] =
    IO.blocking(scanTakenSync(sid))
      .handleErrorWith(e => logger.warn(s"InputMailbox.scanTaken failed for '$sid': ${e.getMessage}").as(Nil))

  def scanTakenSync(sid: String): List[InputEntry] = scanDir(takenDir(sid))

  private def scanDir(dir: os.Path): List[InputEntry] =
    lock.synchronized {
      if !os.exists(dir) then Nil
      else
        os.list(dir)
          .toList
          .filter(p => p.ext == "json" && parseSeq(p.last).isDefined)
          .sortBy(p => (parseSeq(p.last).getOrElse(0L), p.last))
          .flatMap { p =>
            decode[InputEntry](os.read(p)) match
              case Right(e) => Some(e)
              case Left(_) =>
                // 坏形件隔离（不阻断 FIFO；与 event 面同策略）
                try
                  val q = dir / os.up / "quarantine-input"
                  os.makeDir.all(q)
                  os.move(p, q / p.last, replaceExisting = true)
                  logger.warnSync(s"InputMailbox: malformed entry quarantined: ${p.last}")
                catch case _: Throwable => ()
                None
          }
    }

  /** 徽标面：pending-input 深度。 */
  def depth(sid: String): IO[Int] = IO.blocking(scanPendingSync(sid).size)

  // ── 取走（I-ORD：唯一路径，逐条原子 rename 保序）─────────────────────

  /** 取走一批：逐条 `rename` 进 `taken-input/`；单条失败 = 留 pending（安全侧）。 */
  def take(sid: String, entries: List[InputEntry]): IO[List[InputEntry]] =
    IO.blocking(takeSync(sid, entries))
      .handleErrorWith(e => logger.warn(s"InputMailbox.take failed for '$sid': ${e.getMessage}").as(Nil))

  def takeSync(sid: String, entries: List[InputEntry]): List[InputEntry] =
    lock.synchronized {
      try
        os.makeDir.all(takenDir(sid))
        entries.flatMap { e =>
          val src = pendingFileOf(sid, e)
          if !os.exists(src) then Nil // 已被并发取走/清走：单消费口，跳过
          else
            os.move(src, takenFileOf(sid, e))
            List(e)
        }
      catch
        case e: Throwable =>
          logger.warnSync(s"InputMailbox.take failed for '$sid': ${e.getMessage} — entries stay pending")
          Nil
    }

  // ── 消费完成（I-IDEM 的承重面：删文件 + 写墓碑，两者同事务邻域）───────

  /**
   * 消费完成：按身份清除 `taken-input/` 条目 + 落**消费墓碑**。
   *
   * 🔴 调用时机由调用点决定（D3）：用户输入要求「turn **提交后**才调用」，
   * 从而 `taken-input/` 在 turn 执行期间仍在盘上；此时若崩溃，恢复点扫到该条目
   * 但墓碑已在 ⇒ [[deliveredContains]] 判定「已执行过」⇒ 跳过，不产生第二次执行。
   */
  def unlinkTaken(sid: String, identities: List[String]): IO[Unit] =
    IO.blocking(unlinkTakenSync(sid, identities))
      .handleErrorWith(e => logger.warn(s"InputMailbox.unlinkTaken failed for '$sid': ${e.getMessage}"))

  def unlinkTakenSync(sid: String, identities: List[String]): Unit =
    val clean = identities.filter(_.nonEmpty).toSet
    if clean.nonEmpty then
      // 🔴 文件名里的身份是 `safeId(identity)`（`:`/`/` 等一律转 `_`），**不是**原始
      // identity。故比对必须同样走 safeId，否则含 `:` 的键（如 `flow-notify:sid`）
      // 会永远匹配不上、条目永不清除（本批自查修）。墓碑仍写**原始** identity。
      lock.synchronized {
        markDeliveredSync(sid, clean.toList)
        scanDir(takenDir(sid)).filter(e => clean.contains(e.identity)).foreach { e =>
          val path = takenFileOf(sid, e)
          if os.exists(path) then os.remove(path)
        }
      }

  // ── 消费墓碑（I-IDEM：与 event 墓碑分文件）───────────────────────────

  /** 墓碑容量（drop-oldest）。输入量级远低于事件流，1024 覆盖远超任何恢复窗口。 */
  val MaxDeliveredEntries: Int = 1024

  final case class DeliveredInputs(v: Int = 1, ids: List[String] = Nil)

  object DeliveredInputs:
    given Encoder[DeliveredInputs] = Encoder.instance(d => Json.obj("v" -> d.v.asJson, "ids" -> d.ids.asJson))

    given Decoder[DeliveredInputs] = Decoder.instance { c =>
      for
        v <- c.get[Option[Int]]("v")
        ids <- c.get[Option[List[String]]]("ids")
      yield DeliveredInputs(v.getOrElse(1), ids.getOrElse(Nil))
    }

  private def markDeliveredSync(sid: String, ids: List[String]): Unit =
    val clean = ids.filter(_.nonEmpty).distinct
    if clean.nonEmpty then
      val f = deliveredFile(sid)
      val existing =
        if !os.exists(f) then DeliveredInputs()
        else decode[DeliveredInputs](os.read(f)).getOrElse(DeliveredInputs())
      val merged = existing.ids.filterNot(clean.contains) ::: clean
      val capped = merged.takeRight(MaxDeliveredEntries)
      if capped != existing.ids then
        os.makeDir.all(boxDir(sid))
        AtomicJson.writeSync(f, DeliveredInputs(existing.v, capped).asJson.noSpaces)

  /** 墓碑查询：返回 `identities` 中**已执行过**的那些。 */
  def deliveredContains(sid: String, identities: Set[String]): IO[Set[String]] =
    if identities.isEmpty then IO.pure(Set.empty)
    else
      IO.blocking(deliveredContainsSync(sid, identities))
        .handleErrorWith(_ => IO.pure(Set.empty[String]))

  private def deliveredContainsSync(sid: String, identities: Set[String]): Set[String] =
    val f = deliveredFile(sid)
    if !os.exists(f) then Set.empty[String]
    else
      decode[DeliveredInputs](os.read(f)) match
        case Right(d) => d.ids.toSet.intersect(identities)
        case Left(_) => Set.empty[String]

  // ── tmp 残片清理（boot）──────────────────────────────────────────────

  /**
   * 清理输入面两目录的 tmp 残片。
   *
   * 🔴 判据用 `contains(".tmp.")` 而非 `endsWith(".tmp")`：
   * `AtomicJson.writeSync` 的临时名是 `<name>.json.tmp.<UUID>`（**以 UUID 结尾**），
   * 既有 `SessionMailbox.sweepTmpAll` 用 `endsWith(".tmp")` 因此**永不命中**
   * （本批点名缺陷 §4.2，另案修 event 面；本件不重犯）。
   */
  def sweepTmpAll(): IO[List[String]] =
    IO.blocking {
      val sessionsDir = PathUtil.dataRoot / "sessions"
      if !os.exists(sessionsDir) then Nil
      else
        os.list(sessionsDir).toList.flatMap { s =>
          List(s / "mailbox" / "pending-input", s / "mailbox" / "taken-input").flatMap { d =>
            if !os.exists(d) then Nil
            else
              val tmps = os.list(d).toList.filter(_.last.contains(".tmp."))
              tmps.foreach(t =>
                try os.remove(t)
                catch case _: Throwable => ()
              )
              tmps.map(_.last)
          }
        }
    }

end InputMailbox
