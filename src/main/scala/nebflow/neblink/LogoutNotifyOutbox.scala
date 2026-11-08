package nebflow.neblink

import cats.effect.IO
import cats.syntax.all.*
import io.circe.*
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.core.{AtomicJson, CredentialFileAcl}
import nebflow.shared.{NebflowLogger, PathUtil}

import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.UUID
import cats.effect.std.Semaphore
import cats.effect.unsafe.implicits.global

/**
 * 「我登出了」上行通知 · stand 侧**发送接口 + 排队点**（登出收尾批 logout-fin；
 * 设计 multi-tenant-dataroot §3.5.2 ⑤「上行『我登出了』通知中继（离线也记账，
 * §3.5.3）」）。
 *
 * == 为什么本仓只有接口 + 排队点（诚实登记）==
 * 通知的**传输面在中继仓**（link-server）：消费端点、离线待决队列与上线对账都
 * 是跨仓面（设计 §8.3(e)【link-server】行）；隧道帧面走 portal-wire 契约
 * （v1.3 冻结的 14 帧，`PortalWireCodec.FrameInventory`）—— 新增帧类型必须先
 * 契约重冻结，本批不动契约。因此本仓（stand）落的是：
 *   - **发送接口** = [[enqueue]]（账号域传输尚未接入，已识别账号登出均记一件）
 *     + [[drain]]（传输面就位后 at-least-once 补投，
 *     `send` 注入 —— spec 用纯桩驱动，生产装配点留给跨仓对接腿）；以及
 *   - **排队点** = 机器层 `<machineRoot>/[[PendingDirName]]/`，一条一件。
 *
 * 对接点（跨仓，本批**未做**）：
 *   - 消费端尚待定义：现有 relay auth_logout 有账号级设备断连等副作用，不能
 *     作为纯 cookie 清理或账号通知端点直接复用；须新建受权、幂等的专用契约；
 *   - 补投装配：stand 侧未来的 drain 调用 = 隧道重连 / 启动对账
 *     （`PortalConnection.armLifecycle` 对 `PortalUplinkDeadLetter.drain` 的
 *     既有同位装配，`PortalUplinkBackpressure.scala` 头注）。
 *
 * == 为什么记在**机器层**（不是 dataRoot）==
 * 登出清指针后有效根降档（账号根 → 未登录层），`PathUtil.dataRoot` 的落点会在
 * 降档后变得不可达 —— 待发记录必须活过降档，故钉机器层（与 `active-account`
 * 指针同一解析链 `PathUtil.machineRoot`，`NeblinkEnrollment` 登录腿同注：不引入
 * 第二套 home 解析）。目录 0700 / 文件 0600（账号域落盘的权限口径，§2.1）。
 *
 * == 记账语义（与 [[PortalUplinkDeadLetter]] 同族纪律）==
 *  - **零墙钟**：无 TTL、不按时间判失败；条目只在补投成功后删除；
 *  - **失败可见**：排队写失败返回错误；登出仍执行本地撤销，保留指针供重试；
 *  - **有序**：id 以 20 位零填充序号开头 ⇒ 字典序 = 入队序（补投按序）；
 *  - **at-least-once**：drain 成功才出队，失败留盘 + `attempts` 计数（纯读数，
 *    不参与判死）；
 *  - **只记账号域未送达**：设备通知成功不能证明账号域通知送达；账号传输尚未接入，
 *    所有已识别账号均须排队。`accountSub` 为空 ⇒ 无账号域身份 ⇒ no-op。
 *
 * 设备域与账号域的边界：`performLocalLogout` 第 1 步的活通知
 * （DELETE /api/device/logout）只覆盖**设备会话面**；排队记录携带的是**账号域**
 * 身份（`accountSub` / `email`）—— 等未来的账号域传输面消费（按账号清中继侧
 * 控制台会话与路由）。
 */
object LogoutNotifyOutbox:

  private val logger = NebflowLogger.forName("nebflow.neblink.logout-uplink")

  /** 排队目录名（机器层下）。单源 = 本常量（route 级 spec 按契约字面读盘 + 本件交叉钉）。 */
  val PendingDirName: String = "logout-notify-pending"

  /** 入队原因；设备域确认不代表账号域通知送达。 */
  val ReasonLiveNotifyFailed: String = "live-notify-failed"
  val ReasonNoLiveClient: String = "no-live-client"
  val ReasonAccountTransportPending: String = "account-transport-pending"

  /** 一件待发记录。`id` = 幂等键（20 位序号 + accountSub 尾）。 */
  final case class Entry(
    id: String,
    accountSub: String,
    email: String,
    reason: String,
    enqueuedAt: Long,
    attempts: Int = 0
  )

  object Entry:
    given Encoder[Entry] = Encoder.instance { e =>
      Json.obj(
        "id" -> e.id.asJson,
        "accountSub" -> e.accountSub.asJson,
        "email" -> e.email.asJson,
        "reason" -> e.reason.asJson,
        "enqueuedAt" -> e.enqueuedAt.asJson,
        "attempts" -> e.attempts.asJson
      )
    }
    given Decoder[Entry] = Decoder.instance { c =>
      for
        id         <- c.downField("id").as[String]
        accountSub <- c.downField("accountSub").as[String]
        email      <- c.downField("email").as[String]
        reason     <- c.downField("reason").as[String]
        enqueuedAt <- c.downField("enqueuedAt").as[Long]
        attempts   <- c.downField("attempts").as[Option[Int]]
      yield Entry(id, accountSub, email, reason, enqueuedAt, attempts.getOrElse(0))
    }
  end Entry

  /** 排队点（机器层；登出清指针后有效根降档 ⇒ 机器层是唯一活过降档的落点）。 */
  def dir: os.Path = PathUtil.machineRoot / PendingDirName

  private val enqueueLock = new Object
  private val drainLock = Semaphore[IO](1).unsafeRunSync()

  // The OS lock serializes producers in different processes. The sequence is
  // persisted independently of queue entries, so deletion and restart cannot
  // reset ordering; gaps after a crash are harmless.
  private def withSequence[A](f: Long => A): A = enqueueLock.synchronized {
    os.makeDir.all(dir)
    CredentialFileAcl.restrictDirectory(dir.toNIO)
    val channel = FileChannel.open((dir / ".lock").toNIO, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    try
      val lock = channel.lock()
      try
        val sequenceFile = dir / ".sequence"
        val previous = if os.exists(sequenceFile) then os.read(sequenceFile).trim.toLong else 0L
        val next = Math.addExact(previous, 1L)
        AtomicJson.writeSyncRestricted(sequenceFile, next.toString)
        f(next)
      finally lock.release()
    finally channel.close()
  }

  /** id 安全（作文件名尾）：非白名单字符一律替换，防路径穿越（死信区同款）。 */
  private def safeId(s: String): String =
    val cleaned = s.map(c => if c.isLetterOrDigit || c == '-' || c == '.' || c == '_' then c else '_')
    if cleaned.nonEmpty then cleaned.take(120) else "anon"

  private def fileOf(e: Entry): os.Path = dir / s"${e.enqueuedAt}-${safeId(e.id)}.json"

  /** 记一件；写失败向调用者报告。空账号不产生记录。 */
  def enqueueSync(accountSub: String, email: String, reason: String): Unit =
    if accountSub.trim.isEmpty then ()
    else
      try
        withSequence { sequence =>
          val e = Entry(
            id = f"$sequence%020d-${UUID.randomUUID()}-${safeId(accountSub)}",
            accountSub = accountSub,
            email = email,
            reason = reason,
            enqueuedAt = System.currentTimeMillis()
          )
          AtomicJson.writeSyncRestricted(fileOf(e), e.asJson.noSpaces)
          logger.warnSync(s"logout uplink queued: accountSub='$accountSub' reason=$reason file=${fileOf(e).last}")
        }
      catch
        case scala.util.control.NonFatal(t) =>
          logger.warnSync(s"logout uplink queue WRITE FAILED: accountSub='$accountSub' ${t.getClass.getSimpleName}")
          throw t
  end enqueueSync

  /** IO 入队面，失败传给调用者。 */
  def enqueue(accountSub: String, email: String, reason: String): IO[Unit] =
    IO(enqueueSync(accountSub, email, reason))

  /** 全量待发件；坏件或扫描失败向调用者报告，不伪报空队列。 */
  def pending: IO[List[Entry]] = IO.blocking(pendingSync())

  private def pendingSync(): List[Entry] =
    if !os.exists(dir) then Nil
    else
      os.list(dir).filter(_.ext == "json").toList.map { p =>
        val entry = decode[Entry](os.read(p)).fold(throw _, e => e)
        if fileOf(entry) != p then throw new IllegalStateException(s"outbox entry filename mismatch: ${p.last}")
        entry
      }.sortBy(e => (if e.id.matches("[0-9]{20}-[0-9a-f-]{36}-.*") then 1 else 0,
        if e.id.matches("[0-9]{20}-[0-9a-f-]{36}-.*") then e.id else f"${e.enqueuedAt}%020d-${e.id}"))

  /** 补投成功 ⇒ 删除该件（幂等：不存在即 no-op）。 */
  def delivered(id: String): IO[Unit] =
    IO.blocking {
      val matches = pendingSync().filter(_.id == id)
      if matches.size != 1 then throw new IllegalStateException(s"missing or duplicate outbox id: $id")
      os.remove(fileOf(matches.head))
    }.void

  /** 记一次补投尝试（纯读数：attempts 升序可见，不参与任何判死逻辑）。 */
  private def bumpAttempt(e: Entry): IO[Unit] =
    IO.blocking {
      val updated = e.copy(attempts = e.attempts + 1)
      AtomicJson.writeSyncRestricted(fileOf(updated), updated.asJson.noSpaces)
    }.void.handleErrorWith(err =>
      logger.warn(s"LogoutNotifyOutbox: attempt counter write failed for ${e.id}: ${err.getMessage}").void
    )

  /** 补投结果读数。 */
  final case class DrainReport(replayed: List[String] = Nil, kept: List[(String, String)] = Nil)

  /**
   * 按入队序补投全量待发件。`send` = **未来的传输面**注入的上行腿（跨仓对接点
   * 就位后装配；spec 用纯桩驱动）。成功并删除后才报告 replayed；
   * 发送或删除失败时停止，保留后续记录以避免越序。
   */
  def drain(send: Entry => IO[Unit]): IO[DrainReport] = drainLock.permit.use { _ =>
    val processLock = cats.effect.Resource.make(IO.blocking {
      os.makeDir.all(dir)
      CredentialFileAcl.restrictDirectory(dir.toNIO)
      val channel = FileChannel.open((dir / ".drain-lock").toNIO, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
      try
        val lock = channel.tryLock()
        if lock == null then throw new IllegalStateException("outbox drain already running in another process")
        (channel, lock)
      catch
        case scala.util.control.NonFatal(e) => channel.close(); throw e
    }) { case (channel, lock) => IO.blocking { try lock.release() finally channel.close() } }
    processLock.use { _ =>
      def replay(entries: List[Entry], report: DrainReport): IO[DrainReport] = entries match
        case Nil => IO.pure(report)
        case e :: rest =>
          (send(e) *> delivered(e.id)).attempt.flatMap {
            case Right(_) => replay(rest, report.copy(replayed = report.replayed :+ e.id))
            case Left(t) =>
              bumpAttempt(e).as(report.copy(kept = report.kept :+
                (e.id, Option(t.getMessage).getOrElse(t.getClass.getSimpleName))))
          }
      pending.flatMap(entries => replay(entries, DrainReport()))
    }
  }

  /** 清空（运维/spec 用；正常路径绝不调用 —— 零删除纪律）。 */
  def clear(): IO[Unit] =
    IO.blocking(if os.exists(dir) then os.remove.all(dir)).void
      .handleErrorWith(e => logger.warn(s"LogoutNotifyOutbox.clear failed: ${e.getMessage}").void)

end LogoutNotifyOutbox
