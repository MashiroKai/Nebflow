package nebflow.agent

import cats.effect.{Deferred, IO}
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.Json
import nebflow.actor.{ActorRef, InteractionHubCommand, InteractionKind, InteractionReply, InteractionRequest}
import nebflow.db.format.PendingRecord
import nebflow.db.{DbHandle, DbHost, PendingStream}
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
class PendingAskLedger(
  path: os.Path = nebflow.shared.PathUtil.dataRoot / "pending-asks.json",
  /**
   * db 接线第一阶（D-8 待审单落库）：显式注入缝（测试/隔离实例传临时根句柄）。
   * 缺省 + 默认路径构造（= 生产装配，GatewayMain 无参 new）⇒ 自开共享句柄
   * （DbHost，best-effort——失败回落纯旧档行为）；测试传临时路径 ⇒ 不自开。
   */
  db: Option[DbHandle] = None
):

  import PendingAskLedger.*

  private val logger = NebflowLogger.forName("nebflow.agent.interaction")
  private val audit  = NebflowLogger.forName("nebflow.audit")

  /** db 待审单面句柄（构造期定值；None = 未接线/自开失败 ⇒ 全部腿 no-op）。 */
  private val dbHandle: Option[DbHandle] = db.orElse(
    Option.when(path == nebflow.shared.PathUtil.dataRoot / "pending-asks.json")(
      DbHost.openBestEffort(nebflow.shared.PathUtil.dataRoot.toNIO)
    ).flatten
  )

  /**
   * hub 重装槽（D-8 permission 重装腿）：InteractionHub 装配时（Behaviors.setup）
   * 注册 ctx.self——GatewayMain 装配序上 hub spawn（:737）先于 bootReconcile
   * （:838）⇒ boot 对账时槽必已就位。volatile：装配线程写、boot 线程读。
   */
  @volatile private var reinstallSink: Option[ActorRef[InteractionHubCommand]] = None

  def attachReinstallSink(ref: ActorRef[InteractionHubCommand]): Unit = reinstallSink = Some(ref)

  // ── 槽位记账（hub 写面；best-effort，绝不影响交互链）────────────────────

  /** hub `Request`（槽位注册）随行记账：旧档面 + db 开单（两腿各自 best-effort）。 */
  def record(entry: Entry): IO[Unit] =
    IO.blocking {
      synchronized {
        val current = readEntries()
        writeEntries(current :+ entry)
      }
    }
      .handleErrorWith(e => logger.warn(s"PendingAskLedger.record failed requestId=${entry.requestId}: ${e.getMessage}"))
      .*>(openInDb(entry))

  /** 任一收口路径（answered / cleanup / close / cancel）随行销账。幂等。 */
  def remove(requestId: String): IO[Unit] =
    IO.blocking {
      synchronized {
        val current = readEntries()
        if current.exists(_.requestId == requestId) then writeEntries(current.filterNot(_.requestId == requestId))
        else ()
      }
    }
      .handleErrorWith(e => logger.warn(s"PendingAskLedger.remove failed requestId=$requestId: ${e.getMessage}"))
      .*>(closeInDb(requestId))

  // ── db 待审面（第一阶：写穿 + D-8 重水合）───────────────────────────────

  /** db 开单（PendingCard：payload 入摘要——db 记录不落全文，digest 对拍可证）。 */
  private def openInDb(entry: Entry): IO[Unit] =
    dbHandle match
      case Some(h) =>
        h.openPending(PendingRecord.PendingCard(
          requestId = entry.requestId,
          kind = entry.kind,
          rootSessionId = entry.rootSessionId,
          sourceSession = entry.sourceSession,
          sourceAgent = entry.sourceAgent,
          firstQuestion = entry.firstQuestion,
          payload = entry.payload
        )).void.handleErrorWith(e =>
          logger.warn(s"PendingAskLedger db openPending failed requestId=${entry.requestId}: ${e.getMessage}"))
      case None => IO.unit

  /** db 闭单（追加决议事件行；两流都试——remove 不携带 kind，幂等命中即闭）。 */
  private def closeInDb(requestId: String): IO[Unit] =
    dbHandle match
      case Some(h) =>
        val now = System.currentTimeMillis()
        (h.closePending(PendingStream.Permission, requestId, closedBy = "hub",
          closeReason = "slot-closed", now = now) *>
          h.closePending(PendingStream.Ask, requestId, closedBy = "hub",
            closeReason = "slot-closed", now = now))
          .void.handleErrorWith(e =>
            logger.warn(s"PendingAskLedger db closePending failed requestId=$requestId: ${e.getMessage}"))
      case None => IO.unit

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
    appendReceipt: (String, UiMessage) => IO[Unit],
    retireAskTodo: String => IO[Unit] = _ => IO.unit
  ): IO[Report] =
    for
      // db 面先行（D-8 重水合）：permission 重装 + ask 过期关闭（终态行由库侧
      // recoverPendingD8 追加——V6 孤儿 ask == 0）；广播/回执/销账/审计随行。
      dbPlan <- recoverDbPendingD8(broadcast, appendReceipt, retireAskTodo)
      entries <- load().handleErrorWith(_ => IO.pure(Nil))
      now = System.currentTimeMillis()
      // D-11 双读去重：已被 db 面处置的单（ask 已闭 / permission 已重装）不再走
      // legacy 全量过期腿（同一 requestId 双播/双回执结构性消除）；未入 db 的
      // 存量条目（接线前旧数据）维持 legacy 语义（全量过期关闭）。
      legacy = entries.filterNot(e => dbPlan.handledIds.contains(e.requestId))
      _ <- legacy.traverse_ { e =>
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
          // 「最后一条真实消息」）。best-effort——落盘失败不拦广播与清账。
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
          // ②b 待办分家批：AskUser 镜像条目随行销账（幂等；AskUser kind 以外
          // 未入过待办面 ⇒ retire 天然 no-op，无需 kind 闸）。
          retireAskTodo(e.requestId).handleErrorWith(err =>
            logger.warn(s"ask-expired todo retire failed requestId=${e.requestId}: ${err.getMessage}")
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
        if entries.nonEmpty || dbPlan.reinstalled > 0 || dbPlan.askExpired > 0 then
          logger.info(
            s"Pending-ask boot reconcile: expired-closed ${legacy.size} legacy ask(s) " +
              s"(ages ${legacy.map(e => "%.1f".format((now - e.createdAt) / 3600000.0)).mkString(", ")}h); " +
              s"db face (D-8): reinstalled=${dbPlan.reinstalled} permission card(s), ask-expired=${dbPlan.askExpired}"
          )
        else logger.info("Pending-ask boot reconcile: 0 leftover(s)")
    yield Report(inspected = entries.size, closed = legacy.size,
      dbReinstalled = dbPlan.reinstalled, dbAskExpired = dbPlan.askExpired)

  // ── D-8 db 重水合执行腿 ─────────────────────────────────────────────────

  /** db 面一次对账的动作单（handledIds = 已处置的 requestId 集——legacy 腿去重用）。 */
  private final case class DbBootPlan(handledIds: Set[String], reinstalled: Int, askExpired: Int)

  /**
   * D-8 工作默认执行腿：permission（Permission/McpPermission）未闭单**重装回 hub**
   * （数据面恢复：卡可答、答则闭单——不复活会话身份，等待方随旧进程亡故）；
   * ask（AskUser）未闭单**过期关闭**（db 终态行由 [[nebflow.db.DbHandle.recoverPendingD8]]
   * 追加，V6 孤儿 ask == 0）+ 广播/回执/待办销账/审计（与 legacy 对账同族语义）。
   * best-effort：db 面失败 ⇒ WARN + 空 plan（legacy 腿全量兜底，行为回落接线前）。
   */
  private def recoverDbPendingD8(
    broadcast: Json => IO[Unit],
    appendReceipt: (String, UiMessage) => IO[Unit],
    retireAskTodo: String => IO[Unit]
  ): IO[DbBootPlan] =
    dbHandle match
      case None => IO.pure(DbBootPlan(Set.empty, 0, 0))
      case Some(h) =>
        h.recoverPendingD8().attempt.flatMap {
          case Left(e) =>
            logger
              .warn(s"db pending recovery (D-8) failed — pending 面不可再生，单留库待核" +
                s"（若已隔离见 .corrupt-* 旁档）: ${e.getMessage}")
              .as(DbBootPlan(Set.empty, 0, 0))
          case Right(plan) =>
            val askExpired = plan.expiredAsks.traverse_ { row =>
              val ageH = "%.1f".format((System.currentTimeMillis() - row.createdAt) / 3600000.0)
              broadcast(
                Json.obj(
                  "type"          -> "askUserClosed".asJson,
                  "sessionId"     -> row.rootSessionId.asJson,
                  "requestId"     -> row.requestId.asJson,
                  "sourceSession" -> row.sourceSession.asJson,
                  "reason"        -> "expired-orphan".asJson
                )
              ) *>
                appendReceipt(
                  row.rootSessionId,
                  UiMessage.System(
                    s"问题已过期关闭（引擎重启孤儿对账）：提问方已随上次引擎重启结束，回答通道失效 — " +
                      s"requestId=${row.requestId}，来源 ${row.sourceAgent}/${row.sourceSession}，挂起 ${ageH}h",
                    None,
                    Some(Json.obj("requestId" -> row.requestId.asJson, "expiredKind" -> "restart-orphan".asJson))
                  )
                ).handleErrorWith(err =>
                  logger.warn(s"ask-expired receipt append failed requestId=${row.requestId}: ${err.getMessage}")
                ) *>
                retireAskTodo(row.requestId).handleErrorWith(err =>
                  logger.warn(s"ask-expired todo retire failed requestId=${row.requestId}: ${err.getMessage}")
                ) *>
                audit
                  .info(
                    s"event=ask-expired requestId=${row.requestId} kind=${row.kind} rootSessionId=${row.rootSessionId} " +
                      s"sourceSession=${row.sourceSession} sourceAgent=${row.sourceAgent} ageHours=$ageH " +
                      s"reason=expired-orphan source=db-pending"
                  )
                  .handleErrorWith(_ => IO.unit)
            }
            val reinstall = plan.reinstall.traverse_ { row =>
              reinstallSink match
                case Some(sink) =>
                  (sink ! InteractionHubCommand.Request(rebuiltRequest(row))).void
                    .handleErrorWith(err =>
                      logger.warn(s"db pending reinstall tell failed requestId=${row.requestId}: ${err.getMessage}"))
                case None =>
                  logger.warn(
                    s"db pending reinstall skipped (hub sink 未注册——InteractionHub 未装配): 单留库不丢 requestId=${row.requestId}"
                  )
            }
            (askExpired *> reinstall) *>
              logger
                .info(s"db pending recovery (D-8): reinstalled=${plan.reinstall.size} permission card(s), " +
                  s"ask-expired-closed=${plan.expiredAsks.size}")
                .as(DbBootPlan(
                  plan.reinstall.map(_.requestId).toSet ++ plan.expiredAsks.map(_.requestId).toSet,
                  plan.reinstall.size, plan.expiredAsks.size
                ))
        }

  /**
   * 重装卡请求（降级数据面）：payload 不可再生（db 只存摘要）⇒ 以 firstQuestion
   * 重建最小可答卡——Permission/McpPermission 的答复判据只看 answer 形状
   * （`answerCompletes`：`approved: Boolean`），不依赖原 payload ⇒ 用户可答、
   * 答则闭单（hub 槽位 + db 决议行 + 待办销账全链收口）。
   */
  private def rebuiltRequest(row: PendingRecord): InteractionRequest =
    val kind = row.kind match
      case PendingRecord.KindMcpPermission => InteractionKind.McpPermission
      case _                               => InteractionKind.Permission
    InteractionRequest(
      requestId = row.requestId,
      kind = kind,
      payload = Json.obj(
        "reinstalledFromRestart" -> Json.fromBoolean(true),
        "items" -> Json.arr(Json.obj("question" -> Json.fromString(row.firstQuestion)))
      ),
      reply = kind match
        case InteractionKind.McpPermission =>
          InteractionReply.McpPermissionReply(Deferred.unsafe[IO, nebflow.shared.McpPermissionAnswer])
        case _ => InteractionReply.PermissionReply(Deferred.unsafe[IO, Boolean]),
      rootSessionId = row.rootSessionId,
      sourceAgent = row.sourceAgent,
      sourceSession = row.sourceSession
    )

end PendingAskLedger

object PendingAskLedger:

  /**
   * 台账条目（hub 槽位的持久投影；payload 不落旧档——卡片渲染面由会话历史承担）。
   * `payload` 属 db 接线第一阶新增：**只进内存与 db 开单摘要**（旧档 7 字段面
   * 零变化——writeEntries 显式字段，fromJson 只解 7 字段）。
   */
  final case class Entry(
    requestId: String,
    kind: String,
    rootSessionId: String,
    sourceSession: String,
    sourceAgent: String,
    firstQuestion: String,
    createdAt: Long,
    payload: Json = Json.obj()
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
        createdAt = System.currentTimeMillis(),
        payload = req.payload
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

  final case class Report(
    inspected: Int,
    closed: Int,
    /** db 面 D-8：重装回 hub 的 permission 卡数（0 = db 未接线/无残留）。 */
    dbReinstalled: Int = 0,
    /** db 面 D-8：过期关闭的 ask 数（终态行已入库，V6 孤儿 ask==0）。 */
    dbAskExpired: Int = 0
  ):
    def summary: String =
      s"inspected=$inspected expiredClosed=$closed dbReinstalled=$dbReinstalled dbAskExpired=$dbAskExpired"
    /**
     * 相等语义按**旧两字段面**比较（db 面读数经 `summary` 暴露）：既有机械
     * `assertEquals(report, Report(inspected=2, closed=2))` 在 db 腿产出增量时
     * 不得变红（零回归纪律——形状兼容性优先于全字段 case-class 相等）。
     */
    override def equals(other: Any): Boolean = other match
      case r: Report => inspected == r.inspected && closed == r.closed
      case _         => false
    override def hashCode(): Int = (inspected, closed).hashCode()

  /** 文件形状解析（纯函数，spec 可直接消费）。 */
  def fromJsonList(j: Json): List[Entry] =
    j.hcursor.downField("asks").as[List[Json]].toOption.getOrElse(Nil).flatMap(Entry.fromJson)

end PendingAskLedger
