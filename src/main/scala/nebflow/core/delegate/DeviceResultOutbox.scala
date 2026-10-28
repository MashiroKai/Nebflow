package nebflow.core.delegate

import cats.effect.IO
import cats.syntax.all.*
import io.circe.*
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.core.AtomicJson
import nebflow.shared.{NebflowLogger, PathUtil}

/**
 * DeviceResultOutbox —— **跨设备完成件的持久待发区**（台账 #944 跨设备回报丢件腿）。
 *
 * == 病灶（2026-10-26 实证链，KAI 侧日志 + Mac 侧日志双侧对账）==
 *
 * 现象：Mac 父会话只收到 15:27 后报的 #939 一件，此前的件全丢。
 * 定因（不是 relay 断连窗口丢包——那条链是好的，KAI 的 `delegate-result sent to
 * host` 与 Mac 的 `result received` 逐件对得上）：
 *
 *   KAI 引擎 **14:39:04 进程重启**（stand.log「stand 2026.10.26-beta.2」boot 行）。
 *   重启把当时在飞的 6 个 device-delegate 会话连人带**回投代理 actor**
 *   （[[DeviceDelegate.resultProxyBehavior]]）一起杀掉——代理只终态事件才发结果，
 *   会话被 kill 就永远没有终态事件 ⇒ 这 6 件**从未离机**。boot 收殓腿
 *   （[[nebflow.agent.SubAgentStartupRecovery]]）本该兜底通知父会话，但它按
 *   `sessionStore.listSessions` 判「父会话在不在本机」——device-delegate 的父会话
 *   恒在**对端设备**上 ⇒ `exists=false` ⇒ 通知腿被当成「父会话已删」整条跳过
 *   （日志逐字 6 行 `parent=d7c3cb54-dd90-4f exists=false`）。**收殓写了「已通知
 *   父会话」的 lastError，实际零投递**。6/6 精确对应：`result_sent=0` 的 6 个
 *   spawnId 与 boot 收殓的 6 个孤儿 id 完全重合。
 *
 * == 本对象是什么 ==
 *
 * 完成件**先落盘再发**（write-ahead）：`delegate-result` 一旦产出就写进
 * `<dataRoot>/device-outbox/`，发送成功才删；发送失败/宿主暂不可达/进程死在中间，
 * 件都还在盘上，由 relay（重）连上时按序补投。语义 = **at-least-once（宁重勿丢）**，
 * 幂等由宿主侧 `DeliveredReceiptLedger`（键 = spawnId，见
 * [[DeviceDelegate.handleResult]]）承担——重投不会二次唤醒父会话。
 *
 * 与既有 F2 队列（[[nebflow.agent.CompactionQueueStore]]）的分工：F2 管**本机**
 * 会话的待注入队列（父会话在自己盘上）；本区管**跨设备出站**件（父会话在对端盘上，
 * 本机写不进它的队列）。
 *
 * == 设计红线 ==
 *
 *  - **零墙钟**：本区不设 TTL / 不按时间判失败，条目只在投递成功后被删（作者红线
 *    「设计零超时」）；
 *  - **落盘失败不阻断主路径**：写失败只 WARN（簿记面纪律，HeldReceiptStore 同款），
 *    但此时发送仍照常尝试——簿记挂掉不该让件也挂掉；
 *  - **有序**：文件名以 `enqueuedAt` 前缀排序，drain 严格按入队序补投（父会话侧
 *    按到达序渲染）。
 *
 * 存储：`<dataRoot>/device-outbox/<enqueuedAt>-<id>.json`，一条一件，AtomicJson 整写。
 */
object DeviceResultOutbox:

  private val logger = NebflowLogger.forName("nebflow.device-delegate.outbox")

  /** 一件待发件。`id` = 幂等键（device-delegate 腿恒 = spawnId）。 */
  final case class Entry(
    id: String,
    targetDeviceId: String,
    channel: String,
    payload: Json,
    enqueuedAt: Long,
    attempts: Int = 0
  )

  object Entry:
    given Encoder[Entry] = Encoder.instance { e =>
      Json.obj(
        "id" -> e.id.asJson,
        "targetDeviceId" -> e.targetDeviceId.asJson,
        "channel" -> e.channel.asJson,
        "payload" -> e.payload,
        "enqueuedAt" -> e.enqueuedAt.asJson,
        "attempts" -> e.attempts.asJson
      )
    }
    given Decoder[Entry] = Decoder.instance { c =>
      for
        id <- c.downField("id").as[String]
        targetDeviceId <- c.downField("targetDeviceId").as[String]
        channel <- c.downField("channel").as[String]
        payload <- c.downField("payload").as[Json]
        enqueuedAt <- c.downField("enqueuedAt").as[Long]
        attempts <- c.downField("attempts").as[Option[Int]]
      yield Entry(id, targetDeviceId, channel, payload, enqueuedAt, attempts.getOrElse(0))
    }
  end Entry

  private def dir: os.Path = PathUtil.dataRoot / "device-outbox"

  /** id 安全（作文件名尾）：非白名单字符一律替换，防路径穿越。 */
  private def safeId(id: String): String =
    val cleaned = id.map(c => if c.isLetterOrDigit || c == '-' || c == '.' || c == '_' then c else '_')
    if cleaned.nonEmpty then cleaned.take(120) else "anon"

  private def fileOf(e: Entry): os.Path = dir / s"${e.enqueuedAt}-${safeId(e.id)}.json"

  /**
   * 落盘一件（**同一 id 幂等**：已有同 id 件则不改写——保留原入队序与 attempts）。
   * 写失败只 WARN，绝不抛（调用方的发送照常继续）。
   */
  def enqueue(targetDeviceId: String, channel: String, payload: Json, id: String): IO[Entry] =
    if id.isEmpty || targetDeviceId.isEmpty then
      IO.pure(Entry(id, targetDeviceId, channel, payload, System.currentTimeMillis()))
    else
      IO.blocking {
        val existing = pendingSync().find(_.id == id)
        existing match
          case Some(e) => e // 已入队：幂等零重写（重投走同一条）
          case None =>
            val e = Entry(id, targetDeviceId, channel, payload, System.currentTimeMillis())
            if !os.exists(dir) then os.makeDir.all(dir)
            AtomicJson.writeSync(fileOf(e), e.asJson.noSpaces)
            e
      }.handleErrorWith { err =>
        logger
          .warn(s"DeviceResultOutbox.enqueue failed for id=$id (sending anyway): ${err.getMessage}")
          .as(Entry(id, targetDeviceId, channel, payload, System.currentTimeMillis()))
      }

  /** 全量待发件（按入队序；坏文件跳过并 WARN——簿记坏件不阻断其余补投）。 */
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
                logger.warnSync(s"DeviceResultOutbox: unreadable entry ${p.last} skipped (${err.getMessage})")
                None
          }
          .sortBy(e => (e.enqueuedAt, e.id))
    catch
      case e: Throwable =>
        logger.warnSync(s"DeviceResultOutbox: pending scan failed (${e.getMessage}) — treated as empty")
        Nil

  /** 投递成功 ⇒ 删除该件（幂等：不存在即 no-op）。 */
  def delivered(id: String): IO[Unit] =
    if id.isEmpty then IO.unit
    else
      IO.blocking {
        pendingSync().filter(_.id == id).foreach(e => if os.exists(fileOf(e)) then os.remove(fileOf(e)))
      }.void.handleErrorWith(e =>
        logger.warn(s"DeviceResultOutbox.delivered failed for id=$id: ${e.getMessage}").void
      )

  /** 记一次投递尝试（纯读数：`attempts` 升序可见，不参与任何判失败逻辑）。 */
  private def bumpAttempt(e: Entry): IO[Unit] =
    IO.blocking {
      // 尝试数改写走独立文件名尾（enqueuedAt 不变 ⇒ 入队序不变）。
      val updated = e.copy(attempts = e.attempts + 1)
      AtomicJson.writeSync(fileOf(updated), updated.asJson.noSpaces)
    }.void.handleErrorWith(err =>
      logger.warn(s"DeviceResultOutbox: attempt counter write failed for ${e.id}: ${err.getMessage}").void
    )

  /** 补投结果读数（drain 汇总面 / spec 断言面）。 */
  final case class DrainReport(delivered: List[String] = Nil, failed: List[(String, String)] = Nil):
    def attempted: Int = delivered.size + failed.size

  /**
   * 按入队序补投全量待发件。`send` = 实际发送腿（DeviceDelegate.sendToDevice 注入，
   * 便于 spec 用纯桩驱动）。**任一成功即出队**；失败的留在盘上等下一次（relay 重连 /
   * 下一次 boot）。`send` 抛异常按失败处理（一条炸不拖垮其余——继续补后面的件）。
   *
   * 幂等与顺序：入队序来自磁盘文件名，多轮 drain 不会重排；件只在成功后消失，
   * 因此「宁重勿丢」——最坏情况同一件投两次，宿主侧经 spawnId 判重。
   */
  def drain(send: (String, String, Json) => IO[Either[String, Json]]): IO[DrainReport] =
    pending.flatMap { entries =>
      entries.foldLeft(IO.pure(DrainReport())) { (acc, e) =>
        acc.flatMap { report =>
          send(e.targetDeviceId, e.channel, e.payload)
            .attempt
            .flatMap {
              case Right(Right(_)) =>
                delivered(e.id).as(
                  report.copy(delivered = report.delivered :+ e.id)
                )
              case Right(Left(err)) =>
                bumpAttempt(e).as(
                  report.copy(failed = report.failed :+ (e.id, err))
                )
              case Left(t) =>
                bumpAttempt(e).as(
                  report.copy(failed = report.failed :+ (e.id, Option(t.getMessage).getOrElse(t.getClass.getSimpleName)))
                )
            }
        }
      }
    }

  /** 清空（运维/spec 用；正常路径绝不调用——零删除纪律）。 */
  def clear(): IO[Unit] =
    IO.blocking(if os.exists(dir) then os.remove.all(dir)).void
      .handleErrorWith(e => logger.warn(s"DeviceResultOutbox.clear failed: ${e.getMessage}").void)

end DeviceResultOutbox
