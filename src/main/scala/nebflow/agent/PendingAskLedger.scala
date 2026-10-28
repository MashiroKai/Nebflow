package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.Json
import nebflow.actor.InteractionRequest
import nebflow.shared.{NebflowLogger, UiMessage}

import java.nio.file.{Files, StandardCopyOption}
import scala.util.Try

/**
 * PendingAskLedger — 重启孤儿对账批（2026-10-22，作者「Header 待答徽标僵尸态 ·
 * 诊断修复批」修复①）。
 *
 * WHY（缺陷链，实证见 reports/20261022_*_ask-restart-orphan-reconcile）：
 * InteractionHub 的 pending map 是**进程内存**（两条 Ref）。引擎重启杀死全部
 * 槽位时**不经过任何收口路径**（CleanupForSession / askUserClosed 广播都不会
 * 跑）——10-20 answer-landing 批（2382b64e）的三个机制（答案重入三臂、
 * askerAlive 只读标注、AskAnswerReceipt 反向回执通道）全部以「hub 槽位还活着」
 * 为前提，重启后无槽可标注、无槽可回执 ⇒ 缺口原样保留：
 *   - 前端镜像（待办条 / Header 徽标）继续计数一个槽位已死的提问（「有数无卡」，
 *     实测 asknb-6554022b93bb4aa7 的计数在作者标签页里存活 46h+ 跨三次重启）；
 *   - 会话历史里未作答的持久化卡可在历史重载时复活成可作答卡，作答必撞
 *     "unknown requestId"。
 *
 * 本面把 pending 状态变成**跨重启可对账**的：
 *   - 槽位创建时记一行（hub `Request`）；四条收口路径（answered / cleanup /
 *     close / cancel）各删一行 —— 台账在稳态下恒等于 hub 内存面；
 *   - 引擎启动时 [[bootReconcile]] 清点台账：重启后每条残留的提问其等待方
 *     （deferred / 发起会话 actor）都已随旧进程消亡 ⇒ **全部过期关闭**：
 *       ① 广播 `askUserClosed{requestId, sessionId=root, reason:"expired-orphan"}`
 *         （热重启窗内已连接的客户端即时收口；冷启动无连接者由②的持久腿兜住）；
 *       ② 往 root 会话历史追加一条 System 回执行 —— 历史重载的 pending 判据
 *         （`findLastRealMessage`：askUser 行之后出现任何非注入行 = 已推进）从
 *         结构上失效，僵尸卡不再以「可作答」形态复活；
 *       ③ 审计 `event=ask-expired`（含年龄）+ 汇总 INFO，清空台账。
 *
 * 与既有裁定的边界：**只对重启孤儿生效**（boot 单次）。运行中槽位仍遵守
 * answer-landing 批的「askerAlive 只读不杀、零超时纪律不破」——本面不引入任何
 * 运行时超时。前端镜像另有 24h 寿限兜底（askPending.js，同一批）。
 *
 * 并发口径：hub 侧调用已被 hub actor 的 turn 串行化；bootReconcile 在 listen
 * 之前的 boot 链上跑。文件读写统一走 `synchronized` 读-改-写 + 原子 tmp+rename
 * （AskUserSecretSink.atomicWriteJson 先例），频率 = 提问量级（低），不做缓存。
 */
class PendingAskLedger(path: os.Path = nebflow.shared.PathUtil.dataRoot / "pending-asks.json"):

  import PendingAskLedger.*

  private val logger = NebflowLogger.forName("nebflow.agent.interaction")
  private val audit  = NebflowLogger.forName("nebflow.audit")

  // ── 槽位记账（hub 写面；best-effort，绝不影响交互链）────────────────────

  /** hub `Request`（槽位注册）随行记账。 */
  def record(entry: Entry): IO[Unit] = IO.blocking {
    synchronized {
      val current = readEntries()
      writeEntries(current :+ entry)
    }
  }.handleErrorWith(e => logger.warn(s"PendingAskLedger.record failed requestId=${entry.requestId}: ${e.getMessage}"))

  /** 任一收口路径（answered / cleanup / close / cancel）随行销账。幂等。 */
  def remove(requestId: String): IO[Unit] = IO.blocking {
    synchronized {
      val current = readEntries()
      if current.exists(_.requestId == requestId) then writeEntries(current.filterNot(_.requestId == requestId))
      else ()
    }
  }.handleErrorWith(e => logger.warn(s"PendingAskLedger.remove failed requestId=$requestId: ${e.getMessage}"))

  /** 只读清点（boot 对账 / 诊断）。 */
  def load(): IO[List[Entry]] = IO.blocking(synchronized(readEntries()))

  // ── 文件面（原子 tmp+rename，AskUserSecretSink.atomicWriteJson 先例）────────
  // 类内私有：path 是实例字段，读写必须随实例走（单例账本、测试临时根各一实例）。

  private def readEntries(): List[Entry] =
    Try(os.read(path))
      .map(raw => io.circe.parser.parse(raw).map(PendingAskLedger.fromJsonList).getOrElse(Nil))
      .getOrElse(Nil)

  private def writeEntries(entries: List[Entry]): Unit =
    val payload = Json.obj(
      "version"   -> Json.fromInt(1),
      "updatedAt" -> Json.fromLong(System.currentTimeMillis()),
      "asks"      -> entries
        .map { e =>
          Json.obj(
            "requestId"     -> e.requestId.asJson,
            "kind"          -> e.kind.asJson,
            "rootSessionId" -> e.rootSessionId.asJson,
            "sourceSession" -> e.sourceSession.asJson,
            "sourceAgent"   -> e.sourceAgent.asJson,
            "firstQuestion" -> e.firstQuestion.asJson,
            "createdAt"     -> e.createdAt.asJson
          )
        }
        .asJson
    )
    val tmp = path / os.up / s".${path.last}.tmp-${java.util.UUID.randomUUID().toString.take(8)}"
    try
      os.write.over(tmp, payload.spaces2 + "\n", createFolders = true)
      Files.move(tmp.toNIO, path.toNIO, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    finally
      try Files.deleteIfExists(tmp.toNIO)
      catch case _: Throwable => ()

  // ── boot 对账（GatewayMain boot 链，listen 前恰一次）────────────────────

  /**
   * @param broadcast     全量 WS 广播（生产 = wsHub.broadcast）
   * @param appendReceipt (rootSessionId, System 行) —— 往会话历史追加过期回执行
   */
  def bootReconcile(
    broadcast: Json => IO[Unit],
    appendReceipt: (String, UiMessage) => IO[Unit]
  ): IO[Report] =
    for
      entries <- load().handleErrorWith(_ => IO.pure(Nil))
      now = System.currentTimeMillis()
      _ <- entries.traverse_ { e =>
        val ageH = "%.1f".format((now - e.createdAt) / 3600000.0)
        // ① 广播关卡帧（reason="expired-orphan" —— 前端 main.js 映射专用过期文案；
        //    旧前端忽略未知 reason 键，回落来源关闭文案，兼容矩阵不破）。
        broadcast(
          Json.obj(
            "type"          -> "askUserClosed".asJson,
            "sessionId"     -> e.rootSessionId.asJson,
            "requestId"     -> e.requestId.asJson,
            "sourceSession" -> e.sourceSession.asJson,
            "reason"        -> "expired-orphan".asJson
          )
        ) *>
          // ② 持久回执行：历史重载的 pending 判据结构性失效（askUser 行不再是
          //    「最后一条真实消息」）。best-effort——落盘失败不拦广播与清账。
          appendReceipt(
            e.rootSessionId,
            UiMessage.System(
              s"问题已过期关闭（引擎重启孤儿对账）：提问方已随上次引擎重启结束，回答通道失效 — " +
                s"requestId=${e.requestId}，来源 ${e.sourceAgent}/${e.sourceSession}，挂起 ${ageH}h",
              None,
              Some(Json.obj("requestId" -> e.requestId.asJson, "expiredKind" -> "restart-orphan".asJson))
            )
          ).handleErrorWith(err =>
            logger.warn(s"ask-expired receipt append failed requestId=${e.requestId}: ${err.getMessage}")
          ) *>
          // ③ 审计（清点面：谁、多久、怎么处置）。
          audit
            .info(
              s"event=ask-expired requestId=${e.requestId} kind=${e.kind} rootSessionId=${e.rootSessionId} " +
                s"sourceSession=${e.sourceSession} sourceAgent=${e.sourceAgent} ageHours=$ageH reason=expired-orphan"
            )
            .handleErrorWith(_ => IO.unit)
      }
      _ <- IO.blocking(synchronized(writeEntries(Nil))) // 清账（对账即终态）
      _ <-
        if entries.nonEmpty then
          logger.info(
            s"Pending-ask boot reconcile: expired-closed ${entries.size} restart-orphan ask(s) " +
              s"(ages ${entries.map(e => "%.1f".format((now - e.createdAt) / 3600000.0)).mkString(", ")}h)"
          )
        else logger.info("Pending-ask boot reconcile: 0 leftover(s)")
    yield Report(inspected = entries.size, closed = entries.size)

end PendingAskLedger

object PendingAskLedger:

  /** 台账条目（hub 槽位的持久投影；payload 不落盘——卡片渲染面由会话历史承担）。 */
  final case class Entry(
    requestId: String,
    kind: String,
    rootSessionId: String,
    sourceSession: String,
    sourceAgent: String,
    firstQuestion: String,
    createdAt: Long
  )

  object Entry:
    /** hub `Request` 处的单一构造点（excerpt 由调用方给——InteractionHub 已有
     *  `firstQuestionExcerpt` 单点）。 */
    def of(req: InteractionRequest, firstQuestion: String): Entry =
      Entry(
        requestId = req.requestId,
        kind = req.kind.toString,
        rootSessionId = req.rootSessionId,
        sourceSession = req.sourceSession,
        sourceAgent = req.sourceAgent,
        firstQuestion = firstQuestion,
        createdAt = System.currentTimeMillis()
      )

    def fromJson(j: Json): Option[Entry] =
      val c = j.hcursor
      for
        rid <- c.downField("requestId").as[String].toOption
        kind = c.downField("kind").as[String].toOption.getOrElse("")
        root = c.downField("rootSessionId").as[String].toOption.getOrElse("")
        src  = c.downField("sourceSession").as[String].toOption.getOrElse("")
        agent    = c.downField("sourceAgent").as[String].toOption.getOrElse("")
        question = c.downField("firstQuestion").as[String].toOption.getOrElse("")
        createdAt = c.downField("createdAt").as[Long].toOption.getOrElse(0L)
      yield Entry(rid, kind, root, src, agent, question, createdAt)

  final case class Report(inspected: Int, closed: Int):
    def summary: String = s"inspected=$inspected expiredClosed=$closed"

  /** 文件形状解析（纯函数，spec 可直接消费）。 */
  def fromJsonList(j: Json): List[Entry] =
    j.hcursor.downField("asks").as[List[Json]].toOption.getOrElse(Nil).flatMap(Entry.fromJson)

end PendingAskLedger
