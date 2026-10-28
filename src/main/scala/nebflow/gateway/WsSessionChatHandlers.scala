/* 从 WebSocketRoutes 迁出(行为保持重构,2026-09-24)。 */
package nebflow.gateway

import cats.effect.std.{Dispatcher, Queue}
import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import fs2.{Pipe, Stream}
import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.{ActorSystem as RootActorSystem, *}
import nebflow.agent.*
import nebflow.core.*
import nebflow.core.entity.EntityLoader
import nebflow.core.flow.{FlowTreeActor, FlowTreeRegistry, TeamSessionRegistry}
import nebflow.core.mcp.McpManager
import nebflow.core.project.{CancelSource as ChainCancelSource, *}
import nebflow.core.schedule.FreezeSchedule.given
import nebflow.core.skill.SkillService
import nebflow.core.tools.{ToolContext, ToolRegistry}
import nebflow.gateway.NfFilePolicy.*
import nebflow.gateway.WsDispatch.{inboundEnvelope, parsedJson}
import nebflow.llm.*
import nebflow.service.*
import nebflow.shared.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame

import scala.concurrent.duration.*
import scala.io.Source

/** 会话与聊天域(sessions/chat):会话 CRUD、输入/命令、智能体回合控制与历史。 */
private[gateway] object WsSessionChatHandlers:

  private[gateway] val handlers: Map[String, WsDispatch.WsHandler] = Map(
    "askUserAnswer" -> handleAskUserAnswer,
    "askUserCancel" -> handleAskUserCancel,
    "permissionAnswer" -> handlePermissionAnswer,
    "interrupt" -> handleInterrupt,
    "restartAgent" -> handleRestartAgent,
    "cancelAgent" -> handleCancelAgent,
    "chainCancel" -> handleChainCancel,
    "parentRestart" -> handleParentRestart,
    "immediateInput" -> handleImmediateInput,
    "userMessage" -> handleUserMessage,
    "command" -> handleCommand,
    "recallMessage" -> handleRecallMessage,
    "switchSession" -> handleSwitchSession,
    "createSession" -> handleCreateSession,
    "deleteSession" -> handleDeleteSession,
    "batchDeleteSessions" -> handleBatchDeleteSessions,
    "renameSession" -> handleRenameSession,
    "getActiveAgents" -> handleGetActiveAgents,
    "getSessionBusy" -> handleGetSessionBusy,
    "getHistory" -> handleGetHistory,
    "listAgents" -> handleListAgents,
    "listAgentSessions" -> handleListAgentSessions,
    "listSessions" -> handleListSessions,
    "createAgentSession" -> handleCreateAgentSession
  )

  private def handleAskUserAnswer(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val json = parsedJson(text)
    val hc = json.hcursor
    // 未走信封助手:sessionId 与 answers 组成 Either 元组匹配(分支语义逐处保持),非单值空串回退同形,保持原样(2026-09-24)
    (hc.downField("answers").as[List[String]], hc.downField("sessionId").as[String]) match
      case (Right(answers), Right(askSessionId)) =>
        val requestId = hc.downField("requestId").as[String].toOption.getOrElse("")
        // askuser-dragdrop 实施批（2026-10-19，批 #551）：随作答回传的**已落盘附件**
        // （上传端点 `/api/askuser/attachments` 的响应记录，逐键解析向后兼容——缺键
        // 即旧行为逐字节不变）。
        val attachmentsJson = hc.downField("attachments").as[List[Json]].toOption.getOrElse(Nil)
        // askuser-secret 批（2026-10-08，规格 §2「唯一新闸口」）：先问 hub 该卡是否
        // 携 secret 型题——无（旧卡 / 无 requestId / hub 未装配 / 查询失败）⇒ 与
        // 改前**逐字节同路径**；有 ⇒ 秘密槽分流（值落 sink、.ui.json 落脱敏行、
        // hub 载荷带 stored 回执），见 [[handleSecretAwareAnswer]]。
        secretSlotsFor(sharedResources, requestId).flatMap {
          case specs if specs.nonEmpty && attachmentsJson.nonEmpty =>
            // 修正② fail-closed（纵深防御）：secret 卡的作答**永不**接受附件——
            // 前端对 secret 卡本就无附件入口，此闸只服务伪造/越权客户端。整帧
            // 拒收：不落盘、不进 hub（卡保留，可刷新后重新作答）。
            rejectAttachFrame(wsSend, askSessionId, requestId, "ASKUSER_ATTACH_SECRET_CARD",
              "secret questions never accept attachments")
          case Nil =>
            validateAnswerAttachments(askSessionId, requestId, attachmentsJson).flatMap {
              case Some((code, detail)) => rejectAttachFrame(wsSend, askSessionId, requestId, code, detail)
              case None =>
                // 案 B（双开缺陷批 2026-09-21，chain-askuserdup）作答行单一构造点逐字
                // 保留：秘密题缺席的卡与改前同形（answers-only 载荷、原样行文本）；
                // 附件在场 ⇒ 行带附件清单（本批），载荷带 attachments 键。
                val answerText = answers.mkString("\n")
                sessionStore.appendUiMessages(
                  askSessionId,
                  List(UiMessage.askUserAnswer(answerText, requestId, System.currentTimeMillis(), attachmentsJson))
                ) *>
                  markAnswered(askSessionId, requestId, attachmentsJson) *>
                  forwardInteractionAnswer(
                    requestId,
                    askSessionId,
                    io.circe.Json.obj("answers" -> answers.asJson) deepMerge
                      (if attachmentsJson.isEmpty then io.circe.Json.obj()
                       else io.circe.Json.obj("attachments" -> attachmentsJson.asJson))
                  )
            }
          case specs => handleSecretAwareAnswer(ctx, answers, hc, requestId, askSessionId, specs)
        }
      case _ => IO.unit
    end match
  end handleAskUserAnswer

  /**
   * askuser-dragdrop 实施批（2026-10-19，批 #551）：答案侧**提交时终验**（设计稿
   * §3.3，对齐出向面 `validateAttachments` 的反向）。逐条 fail-closed：
   *   ① 计数 ≤ `AttachContract.MaxAttachmentsPerMessage`；
   *   ② 每条 path 必须真实存在、是普通文件；
   *   ③ realpath 必须落在 `<dataRoot>/uploads/<askSessionId>/ask-<requestId>/` 前缀内
   *      （路径逃逸 = 拒收；端点是唯一落盘闸）；
   *   ④ 单件 ≤ `AskUserQuestionTool.MaxAttachmentBytes`（真实文件尺寸，非声明值）；
   *   ⑤ sha 与服务端受理台账一致（台账在 ⇒ 必须一致；进程重启后台账缺失 ⇒ 降级为
   *      ①–④ 把关，不作安全依赖）。
   * 返回 `None` = 全部通过；`Some((code, detail))` = 拒收（调用方整帧丢弃）。
   */
  private[gateway] def validateAnswerAttachments(
    askSessionId: String,
    requestId: String,
    attachments: List[Json]
  ): IO[Option[(String, String)]] =
    if attachments.isEmpty then IO.pure(None)
    else if requestId.trim.isEmpty then
      IO.pure(Some(("ASKUSER_BAD_REQUEST", "attachments require a requestId (card-scoped uploads)")))
    else if attachments.size > AttachContract.MaxAttachmentsPerMessage then
      IO.pure(
        Some(
          (
            "ASKUSER_ATTACH_TOO_MANY",
            s"${attachments.size} attachments — at most ${AttachContract.MaxAttachmentsPerMessage} per card"
          )
        )
      )
    else
      val baseDir = AskUserAttachmentRoutes.Store.dirOf(askSessionId, requestId).toString
      attachments.foldLeft[IO[Option[(String, String)]]](IO.pure(None)) { (acc, att) =>
        acc.flatMap {
          case some @ Some(_) => IO.pure(some)
          case None =>
            val c = att.hcursor
            val path = c.downField("path").as[String].toOption.getOrElse("").trim
            val claimedSha = c.downField("sha256").as[String].toOption.getOrElse("")
            if path.isEmpty then IO.pure(Some(("ASKUSER_BAD_REQUEST", "attachment entry without a path")))
            else
              IO.blocking {
                val p = os.Path(path, os.pwd)
                if !os.isFile(p) then Left(Some(("ASKUSER_ATTACH_NOT_FOUND", s"attachment file missing: $path")))
                else
                  val real = java.nio.file.Paths.get(p.toString).toRealPath().toString
                  val baseReal =
                    try java.nio.file.Paths.get(baseDir).toRealPath().toString
                    catch case _: Throwable => baseDir
                  if !real.startsWith(baseReal + java.io.File.separator) then
                    Left(Some(("ASKUSER_ATTACH_OUTSIDE_CARD", s"attachment path outside the card upload dir: $path")))
                  else
                    val size = os.size(p)
                    if size > nebflow.core.tools.AskUserQuestionTool.MaxAttachmentBytes then
                      Left(
                        Some(
                          (
                            "ASKUSER_ATTACH_TOO_LARGE",
                            s"$path is $size bytes — over the ${nebflow.core.tools.AskUserQuestionTool.MaxAttachmentBytes / (1024 * 1024)} MB per-file limit"
                          )
                        )
                      )
                    else Right(()) // 基础闸过；sha 比对在 blocking 块外（需查台账 IO）
              }.flatMap {
                case Left(reject) => IO.pure(reject)
                case Right(_) =>
                  // 台账 sha 比对（存在才比——重启后缺失，降级不拒）。
                  shaRecordFor(path).map {
                    case Some(recorded) if claimedSha.nonEmpty && recorded != claimedSha =>
                      Some(("ASKUSER_ATTACH_SHA_MISMATCH", s"attachment sha mismatch: $path"))
                    case _ => None
                  }
              }.handleErrorWith(e => IO.pure(Some(("ASKUSER_ATTACH_UNREADABLE", s"attachment not readable: ${e.getMessage}"))))
        }
      }

  /** 服务端受理台账里该 path 的 sha（按 path 线性查——每卡 ≤9 条，代价可忽略）。 */
  private def shaRecordFor(path: String): IO[Option[String]] =
    AskUserAttachmentRoutes.Store.registry.get.map { m =>
      m.values.flatMap(_.files.values).find(_.path == path).map(_.sha256)
    }

  /** 拒收帧（前端 toast 面；与 hub `rejectAnswer` 广播同族同形状）。 */
  private def rejectAttachFrame(
    wsSend: io.circe.Json => IO[Unit],
    askSessionId: String,
    requestId: String,
    code: String,
    detail: String
  ): IO[Unit] =
    nebflow.shared.NebflowLogger
      .forName("nebflow.ws")
      .warn(s"askUserAnswer with attachments REJECTED requestId=$requestId root=$askSessionId code=$code detail=$detail") *>
      wsSend(
        io.circe.Json.obj(
          "type" -> "interactionAnswerRejected".asJson,
          "sessionId" -> askSessionId.asJson,
          "rootSessionId" -> askSessionId.asJson,
          "requestId" -> requestId.asJson,
          "reason" -> "attach-rejected".asJson,
          "detail" -> s"$code: $detail".asJson
        )
      )

  /** 已作答卡的 `.answered` 标记（孤儿清扫的保留判据；只对带附件的作答落——
    * 无附件作答卡即便曾传过孤儿件也照扫）。best-effort。 */
  private def markAnswered(askSessionId: String, requestId: String, attachments: List[Json]): IO[Unit] =
    if attachments.isEmpty then IO.unit
    else AskUserAttachmentRoutes.markAnswered(askSessionId, requestId)

  /**
   * askuser-secret 批（规格 §2 值通道图的本落点）：
   *
   *   ① 值分流：帧的 `secretSlots` 专用键（`[{idx, value}]`）逐槽调
   *      `AskUserSecretSink.store`（0600 F1 写序；ephemeral 题进 `.otp/`）。值
   *      **不进** `answers`、不进任何载荷字段、不进日志。
   *   ② `.ui.json` 脱敏行：秘密槽落「已安全存储 <keyRef>（<location>）」/
   *      「未存储：<reason>」；旧前端把值打在明文 `answers` 槽 ⇒ 落「拒收」行
   *      （值不落盘），随后 hub 守卫拒收整卡并保留（secret-slot-mismatch）。
   *   ③ hub 载荷：`{answers: <原样>, stored: [回执]}`——新前端线协议下秘密槽
   *      answers 位恒空串；回执由 hub `complete` 占位化进答复槽，auditAnswer 由
   *      hub 侧脱敏（只记 slot 数与 keyRef）。
   *
   * 失败面（sink 拒存）⇒ `stored:false` 回执（SecretsStore.Failure 词表，含类别
   * 与路径、无值）随载荷上行：卡照常完成、Agent 收到 `{stored:false, reason}`，
   * 可重问。ephemeral 题落库后顺手清扫一次过期验证码（惰性 + 活动触发，无独立
   * 守护进程）。
   */
  private def handleSecretAwareAnswer(
    ctx: WsDispatchCtx,
    answers: List[String],
    hc: io.circe.HCursor,
    requestId: String,
    askSessionId: String,
    specs: List[SecretSlotSpec]
  ): IO[Unit] =
    import ctx.*
    val slotValues: Map[Int, String] =
      hc.downField("secretSlots")
        .as[List[Json]]
        .toOption
        .getOrElse(Nil)
        .flatMap { s =>
          val idx = s.hcursor.downField("idx").as[Int].toOption
          val value = s.hcursor.downField("value").as[String].toOption.getOrElse("")
          idx.filter(i => !value.isBlank).map(i => i -> value)
        }
        .toMap
    val anyEphemeral = specs.exists(_.ephemeral)
    def storeOne(spec: SecretSlotSpec): IO[Option[Json]] =
      slotValues.get(spec.idx) match
        case None => IO.pure(None) // 未作答的秘密槽：无回执，占位由 hub 留空
        case Some(value) =>
          nebflow.core.secrets.AskUserSecretSink
            .store(
              nebflow.shared.PathUtil.dataRoot,
              nebflow.core.secrets.AskUserSecretSink.StoreRequest(
                requestId = requestId,
                questionIndex = spec.idx,
                value = value,
                nameHint = spec.secretName,
                ephemeral = spec.ephemeral,
                ttlSec = spec.ttlSec
              )
            )
            .map {
              case Right(r) =>
                Some(
                  Json.obj(
                    "idx" -> spec.idx.asJson,
                    "stored" -> true.asJson,
                    "keyRef" -> r.keyRef.asJson,
                    "location" -> r.location.asJson,
                    "variableName" -> r.variableName.asJson
                  )
                )
              case Left(f) =>
                Some(Json.obj("idx" -> spec.idx.asJson, "stored" -> false.asJson, "reason" -> failWord(f).asJson))
            }
    def failWord(f: nebflow.core.secrets.SecretsStore.Failure): String = f match
      case nebflow.core.secrets.SecretsStore.Failure.InvalidName(n, r) => s"invalid_name: $n ($r)"
      case nebflow.core.secrets.SecretsStore.Failure.InvalidValue(n, r) => s"invalid_value: $n ($r)"
      case nebflow.core.secrets.SecretsStore.Failure.SecretMode(n, r) => s"secret_mode: $n ($r)"
      case nebflow.core.secrets.SecretsStore.Failure.UnknownSecret(n) => s"unknown_secret: $n"
      case nebflow.core.secrets.SecretsStore.Failure.ManagedNamespace(n) => s"managed_namespace: $n"
      case nebflow.core.secrets.SecretsStore.Failure.AlreadyExists(n) => s"already_exists: $n"
      case nebflow.core.secrets.SecretsStore.Failure.Referenced(n, refs) => s"referenced: $n (${refs.mkString(",")})"
      case nebflow.core.secrets.SecretsStore.Failure.InvalidVariableName(n, v) => s"invalid_variable_name: $n ($v)"
      case nebflow.core.secrets.SecretsStore.Failure.Io(r) => s"io: $r"
    val secretIdxs = specs.map(_.idx).toSet
    // .ui.json 作答行（槽对齐，逐槽替换秘密槽；值永不落行）：秘密槽先置空，落库
    // 回执产出后回填 keyRef/location（下方 finalRow）。
    val rowLines: List[String] =
      answers.indices.map { i =>
        if !secretIdxs.contains(i) then answers.lift(i).getOrElse("")
        else if answers.lift(i).exists(!_.isBlank) then // 旧前端把值打进了明文槽
          "该题为安全输入题：明文作答未接收——请刷新页面后在桌面面板作答"
        else "" // 新前端：值在 secretSlots，行文本由回执回填
      }.toList
    for
      receipts <- specs.foldLeft(IO.pure(List.empty[Option[Json]])) { (acc, spec) =>
        acc.flatMap(rs => storeOne(spec).map(rs :+ _))
      }
      _ <-
        if anyEphemeral then
          IO.blocking(nebflow.core.secrets.AskUserSecretSink.sweepExpired(nebflow.shared.PathUtil.dataRoot))
            .handleErrorWith(_ => IO.unit)
        else IO.unit
      // 脱敏行内嵌 keyRef/location（storeOne 已产出回执——回填行文本）。
      finalRow =
        val byIdx = receipts.flatten
          .map(r => r.hcursor.downField("idx").as[Int].toOption -> r)
          .collect { case (Some(i), r) =>
            i -> r
          }
          .toMap
        rowLines.zipWithIndex
          .map { (line, i) =>
            byIdx.get(i) match
              case Some(r) if r.hcursor.downField("stored").as[Boolean].toOption.getOrElse(false) =>
                s"已安全存储 ${r.hcursor.downField("keyRef").as[String].getOrElse("?")}（${r.hcursor.downField("location").as[String].getOrElse("?")}）"
              case Some(r) =>
                s"未存储：${r.hcursor.downField("reason").as[String].getOrElse("unstorable")}"
              case None => line
          }
          .mkString("\n")
      _ <- logger.info(
        s"askUserAnswer (secret-aware) requestId=$requestId root=$askSessionId slots=${specs.size} " +
          s"stored=${receipts.flatten.count(r => r.hcursor.downField("stored").as[Boolean].toOption.getOrElse(false))} " +
          s"plainLeakRefused=${secretIdxs.count(i => answers.lift(i).exists(!_.isBlank))}"
      )
      _ <- sessionStore.appendUiMessages(
        askSessionId,
        // 案 B 单一构造点不变（见 legacy 腿注）；秘密槽的行文本 = 脱敏行（本批）。
        List(UiMessage.askUserAnswer(finalRow, requestId, System.currentTimeMillis()))
      )
      payload = Json.obj(
        "answers" -> answers.asJson, // 原样上行：秘密槽明文值由 hub 守卫拒收（fail-closed）
        "stored" -> receipts.flatten.asJson
      )
      _ <- forwardInteractionAnswer(requestId, askSessionId, payload)
    yield ()
    end for
  end handleSecretAwareAnswer

  /**
   * hub 只读查询：该 requestId 的卡携带哪些 secret 槽（空 = 无秘密题 / 槽位不在 /
   * hub 未装配 / 查询失败——一律回落旧路径，hub 侧守卫兜底拒收明文回流）。
   */
  private def secretSlotsFor(sharedResources: SharedResources, requestId: String): IO[List[SecretSlotSpec]] =
    if requestId.trim.isEmpty then IO.pure(Nil)
    else
      sharedResources.interactionHubRef.get.flatMap {
        case None => IO.pure(Nil)
        case Some(hub) =>
          hub
            .?[Option[List[SecretSlotSpec]]](reply => InteractionHubCommand.SecretSlotsOf(requestId, reply))
            .map(_.getOrElse(Nil))
            .handleErrorWith { e =>
              nebflow.shared.NebflowLogger
                .forName("nebflow.ws")
                .warn(
                  s"secret-slot lookup failed for requestId=$requestId — proceeding on the legacy path: ${e.getMessage}"
                )
              IO.pure(Nil)
            }
      }

  /**
   * askucancel 批（2026-10-08）：**取消一个仍在等待作答的 AskUser 问题**。
   * 帧 = `{type:"askUserCancel", requestId, reason?, by?}`（requestId 必填——
   * askUser 帧 / 工具 ack 携带的 `asknb-…`/`panel-…` id；hub 是唯一权威，无需
   * sessionId）。执行走共享执行器 `AskUserCancelRoutes.cancelOnce`（hub
   * `CancelRequest`：kind 闸 + 发起方终结通知 + 幂等裁决；Cancelled 时在卡片
   * 所属会话追加 `__cancelled__` 落盘行）。结果帧（回本连接）：
   * `{type:"askUserCancelResult", ok, requestId, state, error?}`，state ∈
   * cancelled / unknown / not-ask-user / no-hub——重复取消与取消已答问题回
   * `unknown`（幂等终态，不是错误面）。前端「已取消」态的实时面由 hub 广播的
   * `askUserClosed{reason:"user-cancelled"}` 承载（既有消费点零改动；reason 文案
   * 映射是前端位扩展点）。
   */
  private def handleAskUserCancel(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val json = parsedJson(text)
    val hc = json.hcursor
    val requestId = hc.downField("requestId").as[String].toOption.map(_.trim).filter(_.nonEmpty)
    val reason = hc.downField("reason").as[String].toOption.map(_.trim).filter(_.nonEmpty).getOrElse("user-cancelled")
    val by = hc.downField("by").as[String].toOption.map(_.trim).filter(_.nonEmpty).getOrElse("web")
    def resultFrame(ok: Boolean, v: AskUserCancelRoutes.Verdict): IO[Unit] =
      wsSend(
        v.json(requestId.getOrElse(""))
          .deepMerge(
            Json.obj("type" -> "askUserCancelResult".asJson, "ok" -> ok.asJson)
          )
      )
    requestId match
      case None =>
        wsSend(
          Json.obj(
            "type" -> "askUserCancelResult".asJson,
            "ok" -> false.asJson,
            "requestId" -> "".asJson,
            "state" -> "bad-request".asJson,
            "error" -> "askUserCancel requires requestId".asJson
          )
        )
      case Some(rid) =>
        AskUserCancelRoutes.cancelOnce(sharedResources, sessionStore, rid, reason, by).flatMap { v =>
          logger.info(s"askUserCancel requestId=$rid by=$by → ${v.state}") *>
            resultFrame(v.ok, v)
        }
    end match
  end handleAskUserCancel

  private def handlePermissionAnswer(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val json = parsedJson(text)
    val permSessionId = json.hcursor.downField("sessionId").as[String].toOption.getOrElse("")
    val requestId = json.hcursor.downField("requestId").as[String].toOption.getOrElse("")
    val upgradeRaw = json.hcursor.downField("upgradeMode").as[String].toOption
    // #12: NEVER synthesize a deny from a malformed reply — the old
    // getOrElse(false) turned any missing/non-boolean `approved` into
    // a user-attributed denial the user never clicked.
    json.hcursor.downField("approved").as[Boolean] match
      case Right(approved) =>
        // 递进式放行链 (2026-08-30): an invalid upgrade request never
        // transforms the answer (rule #12 spirit) — log and proceed as
        // the plain allow/deny the user actually clicked.
        nebflow.core.PermissionUpgrade.parse(approved, upgradeRaw) match
          case Left(why) =>
            logger.warn(
              s"Permission answer upgradeMode IGNORED ($why) — proceeding as plain ${
                  if approved then "allow" else "deny"
                } (requestId=$requestId)"
            ) *>
              forwardInteractionAnswer(
                requestId,
                permSessionId,
                io.circe.Json.obj("approved" -> approved.asJson)
              )
          case Right(upgrade) =>
            val answerPayload = io.circe.Json.fromJsonObject(
              io.circe.JsonObject.fromIterable(
                Seq("approved" -> approved.asJson) ++
                  upgrade.map(m => "upgradeMode" -> io.circe.Json.fromString(nebflow.core.SafetyMode.toString(m)))
              )
            )
            val upgradeIo = upgrade.fold(IO.unit)(m =>
              applyPermissionUpgrade(permSessionId, m).handleErrorWith { e =>
                logger.warn(s"Permission upgrade FAILED (proceeding as plain allow): ${e.getMessage}")
              }
            )
            logger.info(
              s"Permission answer: ${if approved then "approved" else "denied"}${upgrade.map(m => s" + upgrade→${nebflow.core.SafetyMode.toString(m)}").getOrElse("")}"
            ) *> upgradeIo *> forwardInteractionAnswer(requestId, permSessionId, answerPayload)
      case Left(_) =>
        logger.warn(
          s"Permission answer DROPPED: 'approved' missing or not a boolean (requestId=$requestId, session=$permSessionId) — a malformed reply must not become a deny (#12)"
        )
    end match
  end handlePermissionAnswer

  private def handleInterrupt(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val intSessionId = inboundEnvelope(text).sessionId
    // Hard-recovery P4: an interrupt aimed at a wedged turn cannot be
    // consumed (P3 keeps the actor alive, but the parked read survives
    // fiber cancellation) — kick the transport so the turn actually dies.
    maybeSessionKick(intSessionId, "interrupt") *>
      logger.info("User interrupted") *> ensureAgent(intSessionId)(ref => ref ! AgentCommand.Interrupt())

  private def handleRestartAgent(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val rJson = parsedJson(text)
    val rSessionId = rJson.hcursor.downField("sessionId").as[String].toOption.getOrElse("")
    val rLevel = rJson.hcursor.downField("level").as[String].toOption.getOrElse("soft") match
      case "rollback" => nebflow.actor.RestartLevel.Rollback
      case "prune" => nebflow.actor.RestartLevel.Prune
      case "full" => nebflow.actor.RestartLevel.Full
      case _ => nebflow.actor.RestartLevel.Soft
    if rSessionId.nonEmpty then
      logger.info(s"Restart agent (level=$rLevel) for session $rSessionId") *>
        ensureAgent(rSessionId)(ref => ref ! nebflow.actor.AgentCommand.RestartAgent(rLevel))
    else IO.unit
  end handleRestartAgent

  private def handleCancelAgent(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // 子 agent 管理面板（2026-08-22 缺口 1）：终止任务终态——复用
    // AgentControlTool.doCancel 同链路（supervisor Cancelled → barrier
    // 释放 + taskStore cancelled + Stop；无 supervisor 走降级兜底）。
    // 区别于 interrupt（只停当前 turn，任务可继续）。用户面板路径无
    // caller 概念（工具层的自杀/同桶守卫不适用），仅保留 kind 白名单。
    val cJson = parsedJson(text)
    val cSessionId = cJson.hcursor.downField("sessionId").as[String].toOption.getOrElse("")
    val cReason = cJson.hcursor.downField("reason").as[String].toOption.getOrElse("")
    def cancelReply(ok: Boolean, extra: (String, Json)*): IO[Unit] =
      wsSend(
        io.circe.Json.obj(
          Seq(
            ("type", "cancelAgentResult".asJson),
            ("ok", ok.asJson),
            ("sessionId", cSessionId.asJson)
          ) ++ extra*
        )
      )
    if cSessionId.isEmpty then cancelReply(ok = false, "error" -> "cancelAgent requires sessionId".asJson)
    else if cSessionId.startsWith(nebflow.core.executor.ExternalSessionRunner.SessionPrefix) then
      // 外部腿（external-executors 批 #1225）：外部 delegate 无 agentRegistry
      // 行——取消直达进程树 + 补发 agentDone 面板终态帧（行即时清理，与
      // node-* 会话取消同款刷新语义）。面板路径无 caller 概念（本 handler
      // 既有语义）：外部行管理面随面板放行（工具路径另有 root 桶守卫）。
      logger.info(s"cancelAgent (panel) for external delegate $cSessionId") *>
        nebflow.core.executor.ExternalSessionRunner
          .cancel(cSessionId, if cReason.nonEmpty then cReason else "cancelled from panel", by = "the user panel")
          .flatMap {
            case Left(err) => cancelReply(ok = false, "error" -> err.asJson)
            case Right(msg) =>
              nebflow.core.delegate.DelegateRegistry.find(cSessionId) match
                case None => cancelReply(ok = true, "message" -> msg.asJson)
                case Some(rec) =>
                  nebflow.core.node.NodeRunner
                    .emitSubagentPanelDone(wsSend, cSessionId, rec.parentSessionId)
                    .handleErrorWith(_ => IO.unit) *> cancelReply(ok = true, "message" -> msg.asJson)
          }
    else
      logger.info(s"cancelAgent (panel) for session $cSessionId") *>
        sharedResources.agentRegistry.get.flatMap { registry =>
          registry.get(cSessionId) match
            case None =>
              cancelReply(
                ok = false,
                "error" ->
                  s"No live agent with sessionId='$cSessionId' (registry is in-memory; stale ids vanish after restart)".asJson
              )
            case Some(rec) if !nebflow.core.tools.AgentControlTool.cancelable(rec) =>
              cancelReply(
                ok = false,
                "error" -> s"Kind ${rec.kind} is read-only (cancelable: Delegate / SubTask / Ephemeral / Project node-dispatcher sessions)".asJson
              )
            case Some(rec) =>
              val reason = if cReason.nonEmpty then cReason else "cancelled from panel"
              // notifyWs = 本连接 wsSend：node-* 会话取消补发 agentDone
              // 面板帧（Sub-Agents 面板取消实时刷新修复；dispatcher-*
              // 由观察桥拆除点补发，不在此重发）。
              nebflow.core.tools.AgentControlTool
                .doCancel(sharedResources, rec, reason, notifyWs = Some(wsSend))
                .flatMap {
                  case Right(msg) => cancelReply(ok = true, "message" -> msg.asJson)
                  case Left(err) => cancelReply(ok = false, "error" -> err.message.asJson)
                }
        }
    end if
  end handleCancelAgent

  private def handleChainCancel(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // **链级取消（R2 面板入口）**，chaincancel 批 2026-09-17。
    // 帧 = `{type:'chainCancel', chainId, reason?}`——🔴 **前端只发 chainId**：
    // 成员集合 / 状态判定 / 级联闭包**全在后端**（"后端下发、零派生"硬纪律；
    // 链解析唯一单点 = `FlowMapStore.chainMembersOf`）。项目由注册表反查：
    // chainId 只作**查找键**（同一 chainId 同时在两个项目里 = 节点 id 碰撞，
    // 概率可忽略但**不静默**——报可行动歧义错误）。
    val chJson = parsedJson(text)
    val chChainId = chJson.hcursor.downField("chainId").as[String].toOption.getOrElse("").trim
    val chReason = chJson.hcursor.downField("reason").as[String].toOption.getOrElse("")
    def entryJson(e: ChainCancelEntry): Json =
      Json.obj(
        "id" -> e.nodeId.asJson,
        "name" -> e.name.asJson,
        "status" -> e.status.asJson,
        "why" -> e.why.asJson,
        "signalled" -> e.signalled.asJson
      )
    def chainCancelReply(ok: Boolean, report: Option[ChainCancelReport], err: Option[String]): IO[Unit] =
      val base = Seq(
        ("type", "chainCancelResult".asJson),
        ("ok", ok.asJson),
        ("chainId", chChainId.asJson),
        ("error", err.map(_.asJson).getOrElse(Json.Null))
      )
      val payload = report match
        case None => base
        case Some(r) =>
          base ++ Seq(
            ("chainTitle", r.chainTitle.asJson),
            ("cancelled", r.cancelled.map(entryJson).asJson),
            ("preserved", r.preserved.map(entryJson).asJson),
            ("skipped", r.skipped.map(entryJson).asJson),
            ("prunedReferrers", r.prunedReferrers.asJson),
            ("injected", r.injected.asJson),
            ("notified", r.notified.asJson)
          )
      wsSend(Json.obj(payload*))
    end chainCancelReply
    if chChainId.isEmpty then chainCancelReply(ok = false, None, Some("chainCancel requires chainId"))
    else
      def resolved(rt: ProjectRuntime): IO[Boolean] = rt.store.chainMembersOf(chChainId).map(_.isDefined)
      ProjectRuntimeRegistry.all.flatMap(_.traverse(rt => resolved(rt).map(rt -> _))).flatMap { pairs =>
        val hits = pairs.collect { case (rt, true) => rt }
        hits match
          case Nil =>
            chainCancelReply(
              ok = false,
              None,
              Some(
                s"CHAIN_NOT_FOUND: no mounted project owns chain '$chChainId' " +
                  "(chain ids come from the Flow Map chains[] payload)"
              )
            )
          case one :: Nil =>
            val reason = if chReason.nonEmpty then chReason else "cancelled from Flow Map panel (chain-level)"
            // 2026-10-01 (chainview r3, review finding R1): this leg used to call the plain
            // `engine.cancelChain`, so the cascade ran but the chain-control ledger was never
            // written -> `chainControlOf` / the REST payload kept reporting the chain as
            // `active` (or `paused`), and `resumeChain` answered the semantics-mismatched
            // CHAIN_SINGLE_MEMBER instead of the contract's CHAIN_CANCELLED (§5.2).
            //
            // `cancelChainAndRecord` is the same cascade plus the single ledger write; it keeps
            // the ledger (`ChainLedger`) as the one authoritative state projection regardless of
            // which live entry point issued the cancel. Both live entries (this WS leg and the
            // REST leg in ProjectsRoutes) therefore funnel into the exact same primitive.
            logger.info(s"chainCancel (panel) for chain $chChainId in project '${one.project.name}'") *>
              one.engine.cancelChainAndRecord(chChainId, ChainCancelSource.User, reason).flatMap {
                case Left(err) => chainCancelReply(ok = false, None, Some(err))
                case Right((rep, _)) => chainCancelReply(ok = true, Some(rep), None)
              }
          case many =>
            chainCancelReply(
              ok = false,
              None,
              Some(
                s"AMBIGUOUS_CHAIN_ID: chain '$chChainId' exists in ${many.size} mounted projects " +
                  s"(${many.map(_.project.name).mkString(", ")}) — refusing to guess which one to cancel"
              )
            )
        end match
      }
    end if
  end handleChainCancel

  private def handleParentRestart(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // v2 冻结式错误恢复升级链（§5.3.3）：用户/父干预卡片——重启冻结中的
    // 子 agent（断点续跑）。幂等守卫：sessionId 存在 + 处于错误族冻结
    // （status==Frozen && frozenReason != schedule——时间表冻结是正常调度，
    // 不可父重启）。按 kind 路由：有 supervisor（Delegate/SubTask）→ Stop →
    // BackoffSupervisor respawn；Team 成员 → Stop + Mail 激活（history 重建）；
    // Root → restartAgent soft（现有重启语义）。
    val prJson = parsedJson(text)
    val prSessionId = prJson.hcursor.downField("sessionId").as[String].toOption.getOrElse("")
    def prReply(ok: Boolean, extra: (String, Json)*): IO[Unit] =
      wsSend(
        io.circe.Json.obj(
          Seq(
            ("type", "parentRestartResult".asJson),
            ("ok", ok.asJson),
            ("sessionId", prSessionId.asJson)
          ) ++ extra*
        )
      )
    if prSessionId.isEmpty then prReply(ok = false, "error" -> "parentRestart requires sessionId".asJson)
    else
      logger.info(s"parentRestart (error-recovery) for session $prSessionId") *>
        sharedResources.agentRegistry.get.flatMap { registry =>
          registry.get(prSessionId) match
            case None =>
              prReply(
                ok = false,
                "error" ->
                  s"No live agent with sessionId='$prSessionId' (registry is in-memory; stale ids vanish after restart)".asJson
              )
            case Some(rec)
                if rec.status != nebflow.actor.AgentStatus.Frozen ||
                  !rec.frozenReason.exists(_ != "schedule") =>
              val notFrozenErr =
                s"Session '$prSessionId' is not in error-frozen state (status=${rec.status}, " +
                  s"frozenReason=${rec.frozenReason.getOrElse("none")}) — parentRestart only applies to frozen-error agents"
              prReply(ok = false, "error" -> notFrozenErr.asJson)
            case Some(rec) if rec.kind == nebflow.actor.AgentKind.Root =>
              // Root agent：现有 restartAgent soft 语义（冻结中 RestartAgent
              // 镜像 processing——cancelCurrentTurn + restartStateFor + 续跑）
              (rec.ref ! nebflow.actor.AgentCommand.RestartAgent(nebflow.actor.RestartLevel.Soft)) *>
                prReply(
                  ok = true,
                  "message" -> "Root restart (soft) sent; frozen turn will resume from checkpoint".asJson
                )
            case Some(rec) if rec.kind == nebflow.actor.AgentKind.Flow =>
              // Project flow 会话（node-/dispatcher-）：单次会话，supervisor
              // 是观察桥（不 respawn）——restart 语义不成立，且 raw Stop 会
              // 杀 agent 而不发终态事件（node engine fiber 挂死）。拒绝并指路。
              prReply(
                ok = false,
                "error" ->
                  "Project node/dispatcher sessions are single-shot — restart not supported. Cancel it and re-trigger the work.".asJson
              )
            case Some(rec) if rec.supervisorRef.isDefined =>
              // Delegate/SubTask：Stop → BackoffSupervisor respawn（断点续跑）
              (rec.ref ! nebflow.actor.AgentCommand.Stop(s"parent-restart")) *>
                prReply(
                  ok = true,
                  "message" ->
                    "Restart sent: the supervisor will respawn from the last persisted checkpoint after a short backoff".asJson
                )
            case Some(rec) =>
              // Team 成员：Stop + Mail 激活（history 重建）——复用 AgentControl 工具链路
              val toolCtx = ToolContext(
                projectRoot = "",
                sessionId = None,
                sharedResources = Some(sharedResources),
                actorSystem = Some(sharedResources.actorSystem)
              )
              nebflow.core.tools.AgentControlTool
                .doRestart(sharedResources, toolCtx, rec, "parent-restart (WS)")
                .flatMap {
                  case Right(msg) => prReply(ok = true, "message" -> msg.asJson)
                  case Left(err) => prReply(ok = false, "error" -> err.message.asJson)
                }
        }
    end if
  end handleParentRestart

  private def handleImmediateInput(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val immJson = parsedJson(text)
    val immSessionId = immJson.hcursor.downField("sessionId").as[String].getOrElse("")
    val immContent = immJson.hcursor.downField("content").as[String].getOrElse("")
    admitWorkOrRefuse(wsSend)(
      // 真人：用户在客户端点发送（队列条「立即发送」/输入框直投）
      // device-provenance 批：帧 `device` 键 → 来源设备读数（缺席 ⇒ None ⇒
      // 下方 handleUserText 全 face 逐字节现状）。
      deviceProvenance(immJson).flatMap(device =>
        handleUserText(immSessionId, immContent, source = "immediateInput", fromUser = true, device)
      )
    )

  private def handleUserMessage(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // Protocol alias for immediateInput, sent by the CLI (ChatSend).
    // Before this case existed the payload fell through to the silent
    // `case _ => IO.unit` below — the CLI received "status: ok" while
    // nothing was dispatched (the headless one-shot chain was broken).
    // NOTE: ScheduledTaskActor also emits a "userMessage" broadcast, but
    // that is a server-to-client display event (task routing goes
    // through routeToAgent directly) — it never enters handleMessage
    // and is unaffected by this case.
    val umJson = parsedJson(text)
    val umSessionId = umJson.hcursor.downField("sessionId").as[String].getOrElse("")
    val umContent = umJson.hcursor.downField("content").as[String].getOrElse("")
    admitWorkOrRefuse(wsSend)(
      // 真人：CLI (ChatSend) 是人在终端里敲的字——实测本帧确实走
      // handleUserText → dispatchUserText（与 immediateInput 同一腿）。
      handleUserText(umSessionId, umContent, source = "userMessage", fromUser = true)
    )
  end handleUserMessage

  private def handleCommand(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val command = parse(text).flatMap(_.hcursor.downField("command").as[String]).getOrElse("")
    command match
      // "clear" / "compact" removed (user instruction 2026-10-09 23:12): the
      // /clear and /compact slash commands left the UI command face, so their
      // gateway handlers are gone too. Unknown names (these included) fall
      // through to `case _` — a silent no-op. Auto-compaction
      // (AgentCommand.TriggerCompaction) is engine-internal and unaffected.
      case "fork" =>
        val forkSessionId =
          inboundEnvelope(text).sessionId
        for
          sourceMetaOpt <- sessionStore.getSessionMeta(forkSessionId)
          sourceName = sourceMetaOpt.map(_.name).getOrElse("Session")
          _ <- logger.info(s"Forking session $forkSessionId ($sourceName)")
          newMeta <- sessionStore.forkSession(forkSessionId, s"Fork of $sourceName")
          // 出口 overlay（设计 §13 #9）：fork 帧同样输出有效档位。
          _ <- (sessionStore.listSessions, sessionStore.listAllFolders).flatMapN { (sessions, folders) =>
            val rulesFolderIds = folders.filter(f => nebflow.shared.RulesStore.exists(f.id)).map(_.id)
            sharedResources.overlaySessionList(sessions).flatMap { sessionsJson =>
              wsSend(
                io.circe.Json.obj(
                  "type" -> "sessionList".asJson,
                  "sessions" -> sessionsJson,
                  "folders" -> folders.asJson,
                  "activeId" -> forkSessionId.asJson,
                  "foldersWithRules" -> rulesFolderIds.asJson
                )
              )
            }
          }
          _ <- wsSend(
            io.circe.Json.obj(
              "type" -> "forkComplete".asJson,
              "sessionId" -> newMeta.id.asJson,
              "name" -> newMeta.name.asJson
            )
          )
        yield ()
        end for
      case _ => IO.unit
    end match
  end handleCommand

  private def handleRecallMessage(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val recallSessionId =
      inboundEnvelope(text).sessionId
    if recallSessionId.nonEmpty then
      for
        deleted <- sessionStore.deleteLastUserMessage(recallSessionId)
        _ <- wsSend(
          io.circe.Json.obj(
            "type" -> "messageRecalled".asJson,
            "sessionId" -> recallSessionId.asJson,
            "success" -> deleted.asJson
          )
        )
      yield ()
    else IO.unit
  end handleRecallMessage

  private def handleSwitchSession(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val sessionId = inboundEnvelope(text).sessionId
    if sessionId.nonEmpty then
      sessionService
        .switchSession(sessionId)
        .flatMap { _ =>
          sendAgentSessionList(wsSend, sessionId) *>
            sendMemoryStatus(wsSend, sessionId)
        }
        .handleErrorWith { e =>
          wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
        }
    else IO.unit
    end if
  end handleSwitchSession

  private def handleCreateSession(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val json = parsedJson(text)
    val name = json.hcursor.downField("name").as[String].getOrElse("New Session")
    val agentName = json.hcursor.downField("agentName").as[Option[String]].getOrElse(None)
    val folderId = json.hcursor.downField("folderId").as[Option[String]].getOrElse(None)
    sessionService
      .createSession(name, agentName = agentName, folderId = folderId)
      .flatMap { meta =>
        // Send unified session list (all agents)
        val sendList = agentName match
          case Some(an) =>
            sendAgentSessionListByName(wsSend, an)
          case None =>
            sessionService.sendSessionList(wsSend, RootAgentIdentity.Name)
        sendList
      }
      .handleErrorWith { e =>
        wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
      }
  end handleCreateSession

  private def handleDeleteSession(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val sessionId = inboundEnvelope(text).sessionId
    if sessionId.nonEmpty then
      // Get agent name before deleting so we can send filtered list
      sessionStore
        .getSessionMeta(sessionId)
        .flatMap { metaOpt =>
          val agentName = metaOpt.flatMap(_.agentName).getOrElse(RootAgentIdentity.Name)
          // Clean up text buffers for deleted session to prevent memory leak
          sessionTextBuffers.update(_ - sessionId) *>
            sessionThinkingBuffers.update(_ - sessionId) *>
            sessionTurnStarts.update(_ - sessionId) *>
            stopTeamSessionActors(sessionId) *> stopChildDelegateActors(sessionId) *> removeRootAgent(
              sessionId
            ) *> sessionService
              .deleteSession(sessionId)
              .flatMap { _ =>
                sendAgentSessionListByName(wsSend, agentName)
              }
        }
        .handleErrorWith { e =>
          wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
        }
    else IO.unit
    end if
  end handleDeleteSession

  private def handleBatchDeleteSessions(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val sessionIds = parse(text).flatMap(_.hcursor.downField("sessionIds").as[List[String]]).getOrElse(Nil)
    if sessionIds.nonEmpty then
      sessionStore
        .getSessionMeta(sessionIds.head)
        .flatMap { metaOpt =>
          val agentName = metaOpt.flatMap(_.agentName).getOrElse(RootAgentIdentity.Name)
          sessionIds
            .traverse_ { sid =>
              sessionTextBuffers.update(_ - sid) *>
                sessionThinkingBuffers.update(_ - sid) *>
                sessionTurnStarts.update(_ - sid) *>
                stopTeamSessionActors(sid) *>
                stopChildDelegateActors(sid) *>
                removeRootAgent(sid) *>
                sessionService.deleteSession(sid)
            }
            .flatMap { _ =>
              sendAgentSessionListByName(wsSend, agentName)
            }
        }
        .handleErrorWith { e =>
          wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
        }
    else IO.unit
    end if
  end handleBatchDeleteSessions

  private def handleRenameSession(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val json = parsedJson(text)
    val sessionId = json.hcursor.downField("sessionId").as[String].getOrElse("")
    val newName = json.hcursor.downField("name").as[String].getOrElse("")
    if sessionId.nonEmpty && newName.nonEmpty then
      sessionService
        .renameSession(sessionId, newName)
        .flatMap { _ =>
          sendAgentSessionList(wsSend, sessionId)
        }
        .handleErrorWith { e =>
          wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
        }
    else IO.unit
  end handleRenameSession

  private def handleGetActiveAgents(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // Snapshot restore for the bg-agent indicator: the frontend builds
    // sessionBgAgents incrementally from realtime agentStart/agentDone
    // events, which are not replayed after a browser refresh. Read the
    // unified AgentRegistry (sessionId -> AgentRecord) and filter to
    // active sub-agents (root agents excluded). Team agents are
    // included only while BUSY — they are long-lived in the registry
    // (see filterActiveAgents), so registry presence alone would
    // report idle team agents as running ghosts.
    // agentId == sessionId (nodeSessionId) so restored entries match
    // subsequent realtime events (agentToolStart/agentDone key on it)
    // — pinned by ActiveAgentsEntrySpec, see activeAgentEntryJson.
    sharedResources.agentRegistry.get.flatMap { registry =>
      WebSocketRoutes.filterActiveAgents(registry).flatMap { active =>
        // taskbadge batch 2026-09-27: ledger titles read **once** (an
        // id->title map -- a single ledger-file read regardless of row
        // count); a read failure degrades to an empty map -- the snapshot
        // always frames, the badge degrades to bare #id (empty-state
        // contract; the snapshot must never fail because a title could
        // not be read).
        IO.blocking {
          val ids = active.flatMap(_.taskId).distinct
          ids.map(id => id -> nebflow.core.project.TaskLedgerStore.titleOfSync(Some(id)).getOrElse("")).toMap
        }.handleErrorWith(_ => IO.pure(Map.empty[String, String]))
          .flatMap { titles =>
            // #752 空闲细分：会话自有等待型后台任务一次全表扫描（waitingBySession
            // 分组 Map），逐行查表取自有集——快照「在等什么」字段单源。
            nebflow.core.tools.BgTaskRegistry.waitingBySession.flatMap { waitingBySession =>
              active
                .traverse { rec =>
                  // retryCount 来自 taskStore（2026-08-22 缺口 2：快照自带三
                  // 字段之一；Ephemeral/无任务记录 → 0）
                  sharedResources.subAgentTaskStore.findByTaskId(rec.sessionId).flatMap { taskOpt =>
                    // 显示名契约批（2026-10-22 · 子智能体面板行显示名修复）：快照面
                    // 的 delegate/SubTask 行名**不再回落 sessionId**——权威主源 =
                    // DelegateRegistry.Record.title（spawn 落盘、跨重启存活）；任务
                    // 记录 description 为回填源（`delegateDisplayTitle` 单一解析缝，
                    // 纯函数可单测）。不命中 = None（既有三档链逐字不动）。
                    val delegateTitleIO: IO[Option[String]] =
                      IO
                        .blocking {
                          val registryTitle =
                            if rec.kind == AgentKind.Delegate then
                              nebflow.core.delegate.DelegateRegistry
                                .find(rec.sessionId)
                                .map(_.title)
                                .filter(_.trim.nonEmpty)
                            else None
                          WebSocketRoutes.delegateDisplayTitle(
                            rec.kind,
                            registryTitle,
                            taskOpt.map(_.description).filter(_.trim.nonEmpty)
                          )
                        }
                        .handleErrorWith(_ => IO.pure(None))
                    delegateTitleIO.flatMap { delegateTitle =>
                      sessionStore.getSessionMeta(rec.sessionId).map { meta =>
                        WebSocketRoutes.activeAgentEntryJson(
                          rec,
                          meta,
                          taskOpt.map(_.retryCount),
                          taskTitle = rec.taskId.flatMap(titles.get),
                          delegateTitle = delegateTitle,
                          bgWaiting = waitingBySession
                            .getOrElse(rec.sessionId, Nil)
                            .map(t => (t.jobId, t.description, t.startedAtMs))
                        )
                      }
                    }
                  }
                }
            }.flatMap { agents =>
              wsSend(
                io.circe.Json.obj(
                  "type" -> "activeAgents".asJson,
                  "agents" -> agents.asJson
                )
              ) *>
                // 台账 #929（后台子智能体跨设备透明）：远端投影行搭本连接快照回复
                // 同拍直发（刷新/重连恢复路径——前端零新请求型；投影仓空 = 零帧）。
                nebflow.neblink.AgentSessionSync.pushRemoteRowsTo(wsSend)
            }
          }
      }
    }
  end handleGetActiveAgents

  /**
   * 重连后的主会话 busy 权威快照（composer-resync，2026-10-28）。
   *
   * `done` / `sessionBusy(false)` 是瞬时事件，WS 断连窗内不会重放；activeAgents
   * 又只列在飞子智能体，活跃主会话因此可能永远保留断线前的 busy 镜像。此查询按
   * 客户端请求会话逐个回 AgentRegistry 当前相位，未知会话也显式回 false：
   * 前端因此可以收敛（不是「查不到就继续猜忙」）。
   *
   * Processing 才是回复中/工具执行中的 composer busy。Idle（含后台任务等待期）与
   * WaitingForUser 均不占用停止钮；在飞后台 job 的可见语义由现有任务/子代理面板承担。
   */
  private def handleGetSessionBusy(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val json = parsedJson(text)
    val sessionIds = json.hcursor.downField("sessionIds").as[List[String]].getOrElse(Nil).distinct
    if sessionIds.isEmpty then IO.unit
    else
      sharedResources.agentRegistry.get.flatMap { registry =>
        val states = WebSocketRoutes.sessionBusyStates(registry, sessionIds)
        wsSend(
          io.circe.Json.obj(
            "type" -> "sessionBusyStates".asJson,
            "states" -> states.asJson
          )
        )
      }
  end handleGetSessionBusy

  private def handleGetHistory(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val json = parsedJson(text)
    val sessionId = json.hcursor.downField("sessionId").as[String].getOrElse("")
    val limit = json.hcursor.downField("limit").as[Int].getOrElse(50)
    val beforeIndex = json.hcursor.downField("beforeIndex").as[Option[Int]].getOrElse(None)
    if sessionId.nonEmpty then
      (sharedResources.sessionStore
        .getHistoryPage(sessionId, limit, beforeIndex)
        .attempt
        .flatMap {
          case Right((msgs, total, offset, hasMore)) =>
            wsSend(
              io.circe.Json.obj(
                "type" -> "historyPage".asJson,
                "sessionId" -> sessionId.asJson,
                "messages" -> msgs.asJson,
                "total" -> total.asJson,
                "offset" -> offset.asJson,
                "hasMore" -> hasMore.asJson,
                // M2 缓存语义小刀（v2 §3.1 ① · 历史页行）：页指纹（append-only 历史
                // 的对账键；语义与「候作者裁」边界见 HistoryPageFingerprint 头注）。
                "pageFingerprint" -> HistoryPageFingerprint.of(msgs, total, offset)
              )
            )
          case Left(_) =>
            // Send empty historyPage so the frontend doesn't get stuck
            wsSend(
              io.circe.Json.obj(
                "type" -> "historyPage".asJson,
                "sessionId" -> sessionId.asJson,
                "messages" -> List.empty[io.circe.Json].asJson,
                "total" -> 0.asJson,
                "offset" -> 0.asJson,
                "hasMore" -> false.asJson,
                "pageFingerprint" -> HistoryPageFingerprint.of(List.empty, 0L, 0L)
              )
            )
        })
        // 刷新存活 (2026-09-03): after the initial history page of a
        // session (re)subscribe — browser refresh, WS reconnect
        // (frontend onReconnect re-fetches history) or session switch —
        // re-send the hub's still-pending AskUser cards. Ordered AFTER
        // historyPage on the same outbound queue so the replayed frames
        // are never wiped by the initial-load DOM clear. Pagination
        // fetches (beforeIndex set) prepend older messages and must not
        // trigger a replay. The hub is the single authority: an ask
        // answered before this point is not in the snapshot. The frames
        // carry the live requestId — the frontend dedups against the
        // history-restored (requestId-less) card and rebinds the answer
        // channel (#12 precise routing).
        .flatMap { _ =>
          if beforeIndex.isEmpty then replayPendingAsks(sessionId, wsSend)
          else IO.unit
        }
        .handleErrorWith { e =>
          wsSend(
            io.circe.Json
              .obj("type" -> "error".asJson, "message" -> s"getHistory failed: ${e.getMessage}".asJson)
          )
        }
    else IO.unit
    end if
  end handleGetHistory

  private def handleListAgents(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    agentService.listAgents.flatMap { agents =>
      val agentsJson = agents.map { a =>
        io.circe.Json.obj(
          "name" -> a.name.asJson,
          "description" -> a.description.asJson,
          "displayName" -> a.displayName.getOrElse(a.name).asJson,
          "avatar" -> a.avatar.asJson,
          "tools" -> a.tools.asJson
        )
      }
      wsSend(
        io.circe.Json.obj(
          "type" -> "agentList".asJson,
          "agents" -> agentsJson.asJson
        )
      )
    }
  end handleListAgents

  private def handleListAgentSessions(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val agentName = parse(text).flatMap(_.hcursor.downField("name").as[String]).getOrElse("")
    if agentName.nonEmpty then sendAgentSessionListByName(wsSend, agentName)
    else IO.unit
    end if

  private def handleListSessions(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // Manual trigger for re-fetching the unified session list on WS
    // reconnect (sessions may have been created/removed while the
    // frontend was disconnected). Same payload as the initial push.
    sessionService.sendSessionList(wsSend, RootAgentIdentity.Name)

  private def handleCreateAgentSession(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val agentName = parse(text).flatMap(_.hcursor.downField("name").as[String]).getOrElse("")
    if agentName.nonEmpty then
      (for
        defnOpt <- sharedResources.agentLibrary.get(agentName)
        defn <- IO.fromOption(defnOpt)(new RuntimeException(s"Agent not found: $agentName"))
        meta <- sessionService.createSession(
          s"Agent: ${defn.displayName.getOrElse(defn.name)}",
          agentName = Some(agentName)
        )
        _ <- sessionService.switchSession(meta.id)
        _ <- sessionService.sendSessionList(wsSend, agentName)
      yield ()).handleErrorWith { e =>
        wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
      }
    else IO.unit
  end handleCreateAgentSession

end WsSessionChatHandlers
