/* 从 WebSocketRoutes 迁出(行为保持重构,2026-09-24)。 */
package nebflow.gateway

import cats.effect.std.{Dispatcher, Queue}
import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import fs2.{Pipe, Stream}
import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.ActorSystem as NebulaActorSystem
import nebflow.agent.*
import nebflow.core.entity.EntityLoader
import nebflow.core.flow.{FlowTreeActor, FlowTreeRegistry, TeamSessionRegistry}
import nebflow.core.mcp.McpManager
import nebflow.core.project.{CancelSource as ChainCancelSource, *}
import nebflow.core.schedule.FreezeSchedule.given
import nebflow.core.skill.SkillService
import nebflow.core.tools.{ToolContext, ToolRegistry}
import nebflow.core.{PathUtil, *}
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
        val answerText = answers.mkString("\n")
        val requestId = hc.downField("requestId").as[String].toOption.getOrElse("")
        sessionStore.appendUiMessages(
          askSessionId,
          // 案 B（双开缺陷批 2026-09-21，chain-askuserdup）：作答行经**单一构造
          // 点**落盘，带显式来源标记 answerOf = 被作答的 requestId ⇒ 历史恢复
          // 取值由**数据**决定，不再靠「askUser 条目后面第一条 user 行」的邻接
          // 启发式（该启发式在非阻塞提问/作答后继续打字的形态下会取偏 ⇒ 取样
          // null ⇒ 历史卡被渲染成「已作答」⇒ 重放去重判据被击穿 ⇒ 同 id 双卡）。
          List(UiMessage.askUserAnswer(answerText, requestId, System.currentTimeMillis()))
        ) *>
          forwardInteractionAnswer(requestId, askSessionId, io.circe.Json.obj("answers" -> answers.asJson))
      case _ => IO.unit
    end match
  end handleAskUserAnswer

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
      case "rollback" => nebflow.agent.RestartLevel.Rollback
      case "prune" => nebflow.agent.RestartLevel.Prune
      case "full" => nebflow.agent.RestartLevel.Full
      case _ => nebflow.agent.RestartLevel.Soft
    if rSessionId.nonEmpty then
      logger.info(s"Restart agent (level=$rLevel) for session $rSessionId") *>
        ensureAgent(rSessionId)(ref => ref ! nebflow.agent.AgentCommand.RestartAgent(rLevel))
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
            logger.info(s"chainCancel (panel) for chain $chChainId in project '${one.project.name}'") *>
              one.engine.cancelChain(chChainId, ChainCancelSource.User, reason).flatMap {
                case Left(err) => chainCancelReply(ok = false, None, Some(err))
                case Right(rep) => chainCancelReply(ok = true, Some(rep), None)
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
                if rec.status != nebflow.agent.AgentStatus.Frozen ||
                  !rec.frozenReason.exists(_ != "schedule") =>
              val notFrozenErr =
                s"Session '$prSessionId' is not in error-frozen state (status=${rec.status}, " +
                  s"frozenReason=${rec.frozenReason.getOrElse("none")}) — parentRestart only applies to frozen-error agents"
              prReply(ok = false, "error" -> notFrozenErr.asJson)
            case Some(rec) if rec.kind == nebflow.agent.AgentKind.Root =>
              // Root agent：现有 restartAgent soft 语义（冻结中 RestartAgent
              // 镜像 processing——cancelCurrentTurn + restartStateFor + 续跑）
              (rec.ref ! nebflow.agent.AgentCommand.RestartAgent(nebflow.agent.RestartLevel.Soft)) *>
                prReply(
                  ok = true,
                  "message" -> "Root restart (soft) sent; frozen turn will resume from checkpoint".asJson
                )
            case Some(rec) if rec.kind == nebflow.agent.AgentKind.Flow =>
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
              (rec.ref ! nebflow.agent.AgentCommand.Stop(s"parent-restart")) *>
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
      handleUserText(immSessionId, immContent, source = "immediateInput", fromUser = true)
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
      case "clear" =>
        val clearSessionId =
          inboundEnvelope(text).sessionId
        logger.info("Session cleared") *>
          sessionStore.saveMessagesForSession(clearSessionId, Nil) *>
          sessionStore.appendUiMessages(
            clearSessionId,
            List(UiMessage.System("Context cleared. LLM memory reset.", Some("slash.clearDone")))
          ) *>
          sharedResources.taskStore.deleteAll(clearSessionId) *>
          wsSend(
            io.circe.Json.obj(
              "type" -> "taskListUpdate".asJson,
              "tasks" -> io.circe.Json.arr(),
              "sessionId" -> clearSessionId.asJson
            )
          ) *>
          ensureAgent(clearSessionId)(ref => ref ! AgentCommand.ResetSession)
      case "compact" =>
        val compactSessionId =
          inboundEnvelope(text).sessionId
        val instruction =
          parse(text).flatMap(_.hcursor.downField("instruction").as[String]).toOption.filter(_.nonEmpty)
        logger.info("Manual compaction triggered") *>
          ensureAgent(compactSessionId)(ref =>
            ref ! AgentCommand.TriggerCompaction("full", postCompactInstruction = instruction)
          )
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
            val rulesFolderIds = folders.filter(f => nebflow.service.RulesStore.exists(f.id)).map(_.id)
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
            sessionService.sendSessionList(wsSend, "Nebula")
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
          val agentName = metaOpt.flatMap(_.agentName).getOrElse("Nebula")
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
          val agentName = metaOpt.flatMap(_.agentName).getOrElse("Nebula")
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
        active
          .traverse { rec =>
            // retryCount 来自 taskStore（2026-08-22 缺口 2：快照自带三
            // 字段之一；Ephemeral/无任务记录 → 0）
            sharedResources.subAgentTaskStore.findByTaskId(rec.sessionId).flatMap { taskOpt =>
              sessionStore.getSessionMeta(rec.sessionId).map { meta =>
                WebSocketRoutes.activeAgentEntryJson(rec, meta, taskOpt.map(_.retryCount))
              }
            }
          }
          .flatMap { agents =>
            wsSend(
              io.circe.Json.obj(
                "type" -> "activeAgents".asJson,
                "agents" -> agents.asJson
              )
            )
          }
      }
    }
  end handleGetActiveAgents

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
                "hasMore" -> hasMore.asJson
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
                "hasMore" -> false.asJson
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
    sessionService.sendSessionList(wsSend, "Nebula")

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
