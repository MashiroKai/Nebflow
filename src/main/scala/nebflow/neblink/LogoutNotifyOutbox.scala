package nebflow.neblink

import cats.effect.IO
import cats.syntax.all.*
import io.circe.*
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.core.{AtomicJson, CredentialFileAcl}
import nebflow.shared.{NebflowLogger, PathUtil}

import java.util.concurrent.atomic.AtomicLong

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
 *   - 消费端：link-server `src/routes.rs:892-919`（`auth_logout` 面）+ 设计 §3.5.3
 *     的中继侧待决队列；
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
 *  - **永不抛**：排队写失败只 WARN，绝不阻断登出（logout must ALWAYS succeed
 *    locally —— 与 `performLocalLogout` 同一条纪律）；
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

  private val seq = new AtomicLong(0L)

  /** id 安全（作文件名尾）：非白名单字符一律替换，防路径穿越（死信区同款）。 */
  private def safeId(s: String): String =
    val cleaned = s.map(c => if c.isLetterOrDigit || c == '-' || c == '.' || c == '_' then c else '_')
    if cleaned.nonEmpty then cleaned.take(120) else "anon"

  private def fileOf(e: Entry): os.Path = dir / s"${e.enqueuedAt}-${safeId(e.id)}.json"

  /**
   * 记一件（同步核心，永不抛）。**永不抛** = 记账挂掉不拖垮登出路径 —— 写失败
   * 只给一条如实的 WARN 读数。accountSub 为空 ⇒ 没有账号域身份，直接 no-op。
   */
  def enqueueSync(accountSub: String, email: String, reason: String): Unit =
    if accountSub.trim.isEmpty then ()
    else
      val e = Entry(
        // seq 零填充 20 位：id/文件名字典序 == 数值序 == 入队序（死信区同一不变量）。
        id = f"${seq.incrementAndGet()}%020d-${safeId(accountSub)}",
        accountSub = accountSub,
        email = email,
        reason = reason,
        enqueuedAt = System.currentTimeMillis()
      )
      try
        if !os.exists(dir) then
          os.makeDir.all(dir)
          CredentialFileAcl.restrictDirectory(dir.toNIO)
        AtomicJson.writeSyncRestricted(fileOf(e), e.asJson.noSpaces)
        logger.warnSync(
          s"logout uplink queued: accountSub='$accountSub' reason=$reason file=${fileOf(e).last} — " +
            "live notify did not verifiably reach the relay; pending row kept for in-order replay " +
            "(transport face is cross-repo, link-server)"
        )
      catch
        case t: Throwable =>
          logger.warnSync(
            s"logout uplink queue WRITE FAILED (logout continues): accountSub='$accountSub' " +
              s"${Option(t.getMessage).getOrElse(t.getClass.getSimpleName)} — item could not be preserved"
          )
  end enqueueSync

  /** IO 面（`performLocalLogout` 第 11 步的调用形态；同样永不失败）。 */
  def enqueue(accountSub: String, email: String, reason: String): IO[Unit] =
    IO(enqueueSync(accountSub, email, reason))

  /** 全量待发件（按入队序；坏文件跳过并 WARN —— 簿记坏件不阻断其余补投）。 */
  def pending: IO[List[Entry]] = IO.blocking(pendingSync())

  private def pendingSync(): List[Entry] =
    try
      if !os.exists(dir) then Nil
      else
        os.list(dir)
          .filter(_.ext == "json")
          .toList
          .flatMap { p =>
            decode[Entry](os.read(p)) match
              case Right(e) => Some(e)
              case Left(err) =>
                logger.warnSync(s"LogoutNotifyOutbox: unreadable entry ${p.last} skipped (${err.getMessage})")
                None
          }
          .sortBy(e => (e.enqueuedAt, e.id))
    catch
      case t: Throwable =>
        logger.warnSync(s"LogoutNotifyOutbox: pending scan failed (${t.getMessage}) — treated as empty")
        Nil

  /** 补投成功 ⇒ 删除该件（幂等：不存在即 no-op）。 */
  def delivered(id: String): IO[Unit] =
    IO.blocking {
      pendingSync().filter(_.id == id).foreach(e => if os.exists(fileOf(e)) then os.remove(fileOf(e)))
    }.void.handleErrorWith(e =>
      logger.warn(s"LogoutNotifyOutbox.delivered failed for id=$id: ${e.getMessage}").void
    )

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
   * 就位后装配；spec 用纯桩驱动）。**任一成功即出队**；失败的留在盘上等下一次
   * （重连 / 下一次 boot）。`send` 抛异常按失败处理（一件炸不拖垮其余）。
   */
  def drain(send: Entry => IO[Unit]): IO[DrainReport] =
    pending.flatMap { entries =>
      entries.foldLeft(IO.pure(DrainReport())) { (acc, e) =>
        acc.flatMap { report =>
          send(e)
            .attempt
            .flatMap {
              case Right(_) =>
                delivered(e.id).as(report.copy(replayed = report.replayed :+ e.id))
              case Left(t) =>
                bumpAttempt(e).as(
                  report.copy(kept =
                    report.kept :+ (e.id, Option(t.getMessage).getOrElse(t.getClass.getSimpleName))
                  )
                )
            }
        }
      }
    }

  /** 清空（运维/spec 用；正常路径绝不调用 —— 零删除纪律）。 */
  def clear(): IO[Unit] =
    IO.blocking(if os.exists(dir) then os.remove.all(dir)).void
      .handleErrorWith(e => logger.warn(s"LogoutNotifyOutbox.clear failed: ${e.getMessage}").void)

end LogoutNotifyOutbox
