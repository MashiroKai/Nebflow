package nebflow.agent

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.shared.NebflowLogger

/**
 * InteractionHub — the single interaction center for the whole gateway (P2).
 *
 * Every agent (root or sub-agent) sends its permission requests and AskUser
 * questions here as [[InteractionRequest]]; the hub renders the card/question
 * into the Nebula (root) window via the root session's recording wsSend and
 * routes the user's answer back by `requestId`.
 *
 * Why a dedicated actor instead of the root agent:
 *  - D3: pending deferreds/replyTo live here, NOT in any AgentActor — they
 *    survive the requesting agent's turn lifecycle (turn-end cleanup, compaction).
 *  - D4: `pending` is a `Map[requestId → reply]` — naturally multi-slot; a
 *    second concurrent request is queued, never silently rejected.
 *  - D2: answers are addressed by requestId and never touch the agent registry
 *    (or routeToAgent), so a ghost agent can never be created by an answer.
 *
 * The hub has no external state dependencies (two in-memory Refs). If it
 * crashes, the actor system restarts it; in-flight pending requests are lost
 * and the requesting agents keep waiting (R1, wait-timeout-fix: there is no
 * permission timeout anymore — same exposure as AskUser's unbounded wait).
 * The stuck turn remains user-cancellable via Interrupt, which is the
 * guaranteed exit; the restarted hub serves FUTURE requests.
 */
object InteractionHub:
  private val logger = NebflowLogger.forName("nebflow.agent.interaction")

  /**
   * R10 审计面（作者裁定 U5=F-b）：ask / answer 两个**既有单点**各追加一行
   * `nebflow.audit`（先例 `AgentControlTool`：内核持全机权限 + 远端动作不可逆 ⇒
   * 事后可归因）。字段含 `sourceSession`——事后用 `delegate-kernel-` 前缀下过滤
   * 即可单独看内核提问链。**不新增事件类型、不改 `WatchdogEventLog`、零新存储。**
   * 注意作用域：hub 是全局单点，故审计行覆盖一切 ask 源（内核 + 项目节点）——
   * 按前缀过滤即内核面。
   */
  private val audit = NebflowLogger.forName("nebflow.audit")

  /**
   * 槽位条目。可见性 `private[agent]`（而非 `private`）：P0-1 验收 A1-5/A1-7 要求对
   * **生产代码本体**（`answerCompletes` / `snapshotFrames`）取值，而非对测试自造的等价物
   * —— 故 spec 需在包内构造它。零行为影响（同包可见性放宽，不新增任何写面）。
   */
  private[agent] case class PendingRequest(
    reply: InteractionReply,
    rootSessionId: String,
    sourceAgent: String,
    sourceSession: String,
    kind: InteractionKind,
    payload: Json,
    createdAt: Long
  )

  /**
   * @param liveness askerAlive 读时投影探针（answer-landing 批 2026-10-21，开放
   *   问题 #3 推荐默认采纳）：`sourceSession → 注册表在册?`。`None`（缺省）=
   *   未接线 ⇒ 快照帧逐字节不改（既有测试构造零改动）；GatewayMain 接线
   *   `sharedResources.agentRegistry` 读数。
   */
  def apply(liveness: Option[String => IO[Boolean]] = None): Behavior[InteractionHubCommand] =
    Behaviors.setup { ctx =>
      val pending: Ref[IO, Map[String, PendingRequest]] = Ref.unsafe(Map.empty)
      val rootWsSend: Ref[IO, Map[String, Json => IO[Unit]]] = Ref.unsafe(Map.empty)
      // Hub behavior never changes state internally — the two Refs hold all
      // mutable state — so the same behavior object is returned for every msg.
      lazy val behavior: Behavior[InteractionHubCommand] =
        Behaviors.receiveMessage { msg =>
          msg match
            case InteractionHubCommand.RegisterRoot(rootSessionId, wsSend) =>
              rootWsSend.update(_ + (rootSessionId -> wsSend)) *> IO.pure(behavior)
            case InteractionHubCommand.UnregisterRoot(rootSessionId) =>
              rootWsSend.update(_ - rootSessionId) *> IO.pure(behavior)
            case InteractionHubCommand.Request(req) =>
              ctx.forkTurn(handleRequest(pending, rootWsSend, req)) *> IO.pure(behavior)
            case InteractionHubCommand.Answered(ans) =>
              ctx.forkTurn(handleAnswered(pending, rootWsSend, ans)) *> IO.pure(behavior)
            case InteractionHubCommand.ListPendingAsks(rootSessionId, reply) =>
              // 刷新存活 (2026-09-03): read-only snapshot for reconnect replay.
              ctx.forkTurn(handleListPendingAsks(pending, liveness, rootSessionId, reply)) *> IO.pure(behavior)
            case InteractionHubCommand.ListAllPendingAsks(reply) =>
              // #250 第③项 (2026-09-13): global snapshot — the frontend's pending
              // mirror (bar/badge) rebuilds from ONE global read instead of
              // depending on which session happens to be (re)subscribed.
              ctx.forkTurn(handleListAllPendingAsks(pending, liveness, reply)) *> IO.pure(behavior)
            case InteractionHubCommand.RootReachable(rootSessionId, reply) =>
              // 工具面按角色分化批 B6 (2026-09-13): read-only reachability probe —
              // the non-blocking AskUserQuestion preflights with this before it
              // registers a slot (nobody waits in non-blocking mode ⇒ a card
              // rendered into an unreachable root = a silently lost answer).
              ctx.forkTurn(handleRootReachable(rootWsSend, rootSessionId, reply)) *> IO.pure(behavior)
            case InteractionHubCommand.CleanupForSession(sessionId, reason) =>
              // P2 G11 (20260908 spec §3.4): source-death cleanup — node cancelled
              // while its AskUser card is pending → close the card (engine cascade:
              // cancelNode / abandon / dead-session reap).
              // #250 第②项：同一命令新增 `reason`（缺省 ""）——turn 被用户中断时
              // root 会话没有清理入口，是同一「等待方已死、槽位仍在」面（见
              // handleCleanupForSession 注释）。
              ctx.forkTurn(handleCleanupForSession(pending, rootWsSend, sessionId, reason)) *> IO.pure(behavior)
            case InteractionHubCommand.CloseRequest(requestId) =>
              // 调用侧撤回（#147 接线段 2026-09-12）：请求方自己不等了（确认超时）
              // ⇒ 精确回收**这一个**槽位并关掉卡片。与 CleanupForSession 的区别是
              // 粒度：那条按 sourceSession 批量回收（源会话死亡），这条按 requestId
              // 精确回收 —— 同一会话同时挂着别的 ask 时不得被误伤（超时撤回若走
              // CleanupForSession 就会误杀同会话的其它 pending 卡）。
              ctx.forkTurn(handleCloseRequest(pending, rootWsSend, requestId)) *> IO.pure(behavior)
            case InteractionHubCommand.CancelRequest(requestId, reason, by, reply) =>
              // askucancel 批（2026-10-08）：用户发起的取消——按 requestId 精确终结
              // 一个 AskUser 槽位。与上一条（调用侧撤回，#147）的差别 = kind 闸 +
              // 发起方终结通知 + 显式裁决（见 InteractionHubCommand.CancelRequest 注）。
              ctx.forkTurn(handleCancelRequest(pending, rootWsSend, requestId, reason, by, reply)) *> IO.pure(behavior)
            case InteractionHubCommand.SecretSlotsOf(requestId, reply) =>
              // askuser-secret 批（2026-10-08）：只读 secret 槽位查询（网关作答闸的
              // 判据来源）——见 InteractionHubCommand.SecretSlotsOf 注。
              ctx.forkTurn(handleSecretSlotsOf(pending, requestId, reply)) *> IO.pure(behavior)
            case InteractionHubCommand.AttachGateOf(requestId, reply) =>
              // askuser-dragdrop 实施批（2026-10-19，批 #551）：只读附件闸查询
              // （上传端点前置闸的判据来源）——见 InteractionHubCommand.AttachGateOf 注。
              ctx.forkTurn(handleAttachGateOf(pending, requestId, reply)) *> IO.pure(behavior)
        }
      IO.pure(behavior)
    }

  // ============================================================
  // Request: store reply target, render card/question to root window
  // ============================================================

  private def handleRequest(
    pending: Ref[IO, Map[String, PendingRequest]],
    rootWsSend: Ref[IO, Map[String, Json => IO[Unit]]],
    req: InteractionRequest
  ): IO[Unit] =
    val render = req.kind match
      case InteractionKind.Permission => renderPermission(req)
      case InteractionKind.AskUser => renderAskUser(req)
      // P0-1 / P-M1（spec §2.4）：第三种 kind 的分支。与 askPermission / askUser
      // 同款「加 kind 分支」形态，零渲染层重构（payload 走自由 Json 先例）。
      case InteractionKind.McpPermission => renderMcpPermission(req)
    for
      _ <- logger.info(
        s"InteractionRequest kind=${req.kind} requestId=${req.requestId} root=${req.rootSessionId} " +
          s"sourceAgent=${req.sourceAgent}"
      )
      // R10/U5=F-b 审计行（ask 单点）——best-effort，绝不影响交互链。
      _ <- audit
        .info(
          s"event=ask kind=${req.kind} requestId=${req.requestId} sourceSession=${req.sourceSession} " +
            s"rootSessionId=${req.rootSessionId} sourceAgent=${req.sourceAgent} " +
            s"firstQuestion=${firstQuestionExcerpt(req.payload)}"
        )
        .handleErrorWith(_ => IO.unit)
      // Register BEFORE rendering so a fast answer can never miss the slot.
      _ <- pending.update(
        _ + (req.requestId ->
          PendingRequest(
            req.reply,
            req.rootSessionId,
            req.sourceAgent,
            req.sourceSession,
            req.kind,
            req.payload,
            System.currentTimeMillis()
          ))
      )
      send <- rootWsSend.get
      _ <- send.get(req.rootSessionId) match
        case Some(ws) =>
          // roundComplete first: flush any in-flight text buffer into the root
          // session's ui.json before the interactive card (matches P1 root behavior).
          (ws(Json.obj("type" -> "roundComplete".asJson, "sessionId" -> req.rootSessionId.asJson)).handleErrorWith(_ =>
            IO.unit
          ) *>
            ws(render).handleErrorWith { e =>
              logger.warn(
                s"InteractionRequest render failed for requestId=${req.requestId}: ${e.getMessage}"
              ) *> IO.unit
            }).void
        case None =>
          // F4 (#433): the target root session is unreachable (deleted /
          // zombie / never had a client). Rendering into it would park the
          // card in a graveyard no one watches — invisible = unanswerable
          // (and since R1, wait-timeout-fix, there is no timeout that would
          // eventually deny it). Fan the card out to ALL other
          // registered roots instead, flagged `fallback: true` so the frontend
          // shows a global actionable toast rather than routing the card into
          // a session it cannot open. Answers still match by requestId, so a
          // user answering from any window completes the pending deferred.
          val others = send.toList.filterNot(_._1 == req.rootSessionId)
          if others.isEmpty then
            logger.warn(
              s"InteractionRequest dropped: no root wsSend registered for rootSessionId=${req.rootSessionId}"
            )
          else
            others.traverse_ { case (sid, ws) =>
              ws(
                render.deepMerge(
                  Json.obj(
                    "fallback" -> true.asJson,
                    "fallbackRoot" -> req.rootSessionId.asJson
                  )
                )
              ).handleErrorWith { e =>
                logger.warn(
                  s"InteractionRequest fallback render failed for requestId=${req.requestId} root=$sid: ${e.getMessage}"
                ) *> IO.unit
              }
            } *>
              logger.warn(
                s"InteractionRequest root=${req.rootSessionId} unreachable — fanned out to ${others.size} " +
                  s"registered root(s) with fallback flag (requestId=${req.requestId}, #433 F4)"
              )
          end if
    yield ()

    end for

  end handleRequest

  /** 审计用：问题首条 ≤200 字符（无内容时给 "-"；绝不抛）。 */
  private def firstQuestionExcerpt(payload: Json): String =
    val q = payload.hcursor.downField("items").downArray.downField("question").as[String].toOption
    q.map(s => if s.length > 200 then s.take(200) + "..." else s).getOrElse("-")

  /** Build the askPermission card JSON, rendered at sessionId=rootSessionId. */
  private def renderPermission(req: InteractionRequest): Json =
    val base = req.payload.asObject.getOrElse(JsonObject.empty)
    Json.fromJsonObject(
      base
        .add("type", "askPermission".asJson)
        .add("sessionId", req.rootSessionId.asJson)
        .add("requestId", req.requestId.asJson)
        .add("sourceAgent", req.sourceAgent.asJson)
        .add("sourceSession", req.sourceSession.asJson)
    )

  /**
   * Build the askUser question JSON, rendered at sessionId=rootSessionId.
   *
   * `agentName` 的来源：**payload 显式携带者优先**（内核会话的 U3 来源标注
   * `subagent · <任务摘要>` 由 `AgentActor` 写在 payload.agentName 上——它是
   * 前端 badge/待办条的回落标签来源），缺省才用 `sourceAgent`。两条既有路径
   * 逐字节不变：项目节点/分发器/Nebula 的 payload.agentName 恒等于 sourceAgent
   * （`buildAskUserJson` 首参），重放路径（`ListPendingAsks`）用的也是同一
   * 存储 payload ⇒ 渲染结果不变。
   */
  private def renderAskUser(req: InteractionRequest): Json =
    val base = req.payload.asObject.getOrElse(JsonObject.empty)
    val withAgentName =
      if base.contains("agentName") then base
      else base.add("agentName", req.sourceAgent.asJson)
    Json.fromJsonObject(
      withAgentName
        .add("type", "askUser".asJson)
        .add("sessionId", req.rootSessionId.asJson)
        .add("requestId", req.requestId.asJson)
        .add("sourceAgent", req.sourceAgent.asJson)
        .add("sourceSession", req.sourceSession.asJson)
    )

  /**
   * Build the mcpPermission card JSON (P0-1 / P-M1, spec §2.4), rendered at
   * sessionId=rootSessionId.
   *
   * Payload 由 `McpToolGate.cardPayload` 产出（serverId/plugin/tool/凭据 redact 后的
   * 参数摘要/riskTier/declared/hostBanner/allowUpgrade…），本处只补三个既有归属字段
   * ——与 `renderPermission` / `renderAskUser` 逐字同款（单一补齐点，不各自拼帧）。
   * `type` 由调用侧 payload 自带（`mcpPermission`），此处不再改写，避免两处定名。
   */
  private def renderMcpPermission(req: InteractionRequest): Json =
    val base = req.payload.asObject.getOrElse(JsonObject.empty)
    val withType = if base.contains("type") then base else base.add("type", "mcpPermission".asJson)
    Json.fromJsonObject(
      withType
        .add("sessionId", req.rootSessionId.asJson)
        .add("requestId", req.requestId.asJson)
        .add("sourceAgent", req.sourceAgent.asJson)
        .add("sourceSession", req.sourceSession.asJson)
    )

  // ============================================================
  // 刷新存活 (2026-09-03): reconnect replay — read-only pending snapshot.
  //
  // A pending AskUser already survives a page refresh through the history
  // restore chain (#43), but that chain loses the requestId binding
  // (UiMessage.AskUser persists only {type, items}) and disappears entirely
  // when the askUser entry falls outside the first history page. The gateway
  // calls ListPendingAsks when a client (re)subscribes to a session (initial
  // history load) and re-sends the cards — the hub stays the single authority:
  // an ask answered between snapshot and render is simply not in the snapshot.
  //
  // Read-only: the pending map is untouched. The replayed frame is state
  // re-delivery, NOT a re-ask — answering a card that was replayed twice
  // consumes the slot exactly once (second answer hits "unknown requestId",
  // card already locked locally).
  // ============================================================
  private def handleListPendingAsks(
    pending: Ref[IO, Map[String, PendingRequest]],
    liveness: Option[String => IO[Boolean]],
    rootSessionId: String,
    reply: ActorRef[List[Json]]
  ): IO[Unit] =
    pending.get
      .map(m => snapshotFrames(m, Some(rootSessionId)))
      .flatMap(list => annotateAskerAlive(list, liveness))
      .flatTap(list =>
        if list.nonEmpty then logger.info(s"ListPendingAsks root=$rootSessionId → ${list.size} pending ask(s) replayed")
        else IO.unit
      )
      .flatMap(list => (reply ! list).void)

  /**
   * 多 AskUser 并发批（#250 第③项，2026-09-13 作者裁定「6 项全补」）：
   * **全局** pending-AskUser 快照（跨 root，按 createdAt 升序）。
   *
   * 口径不一致（改前）：前端重连做的是**全局**清空（`resetPendingAsks`，askPending.js），
   * 而后端重建只在「某会话 getHistory 首帧」触发（`WebSocketRoutes` 的
   * `replayPendingAsks`，按会话订阅）⇒ 重连时活动会话 ≠ 承载卡片的 root 会话时，
   * 镜像清空后**永不重建**：待办条/badge 显示 0，而卡片其实还挂着
   * （hub 是唯一权威、等待无超时）＝ 待办信号静默丢失。
   *
   * 统一口径：清空与重建都走**全局快照**这一条路（前端 `pendingAsksSnapshot`
   * 帧 = 一次性全局对账：丢权威已无的、补权威仍有的），与「按会话订阅重放」解耦
   * ——后者保留原职责（把卡片渲染进对应会话的聊天流），不再承担镜像重建。
   */
  private def handleListAllPendingAsks(
    pending: Ref[IO, Map[String, PendingRequest]],
    liveness: Option[String => IO[Boolean]],
    reply: ActorRef[List[Json]]
  ): IO[Unit] =
    pending.get
      .map(m => snapshotFrames(m, None))
      .flatMap(list => annotateAskerAlive(list, liveness))
      .flatTap(list => logger.info(s"ListAllPendingAsks → global pending-ask snapshot: ${list.size} card(s)"))
      .flatMap(list => (reply ! list).void)

  /**
   * 僵尸 pending 的**发起方活性标注**（answer-landing 实施批 2026-10-21；开放问题
   * #3 推荐默认采纳「非破坏性标注」——只读不杀，零超时纪律不破）：每帧按
   * `sourceSession` 现查统一注册表并落 `askerAlive` 布尔键——发起会话已亡的卡
   * （blocked/终态节点 30min 销毁窗收殓后）在健康面/待办条可见「发起会话已结束，
   * 答复将走重入面」。**读时投影**（hub-projection placement）：零生命周期钩子、
   * 零槽位写面、读数恒新鲜（每次快照重算）；探针缺席（未接线）⇒ 逐字节不改帧。
   * best-effort：单帧标注失败不拖垮快照。
   */
  private def annotateAskerAlive(frames: List[Json], liveness: Option[String => IO[Boolean]]): IO[List[Json]] =
    liveness match
      case None => IO.pure(frames)
      case Some(alive) =>
        frames.traverse { f =>
          val src = f.hcursor.downField("sourceSession").as[String].toOption.map(_.trim).filter(_.nonEmpty)
          src match
            case None => IO.pure(f)
            case Some(sid) =>
              alive(sid)
                .map(a => f.deepMerge(Json.obj("askerAlive" -> Json.fromBoolean(a))))
                .handleErrorWith(e =>
                  logger.warn(s"askerAlive annotate failed (sourceSession=$sid): ${e.getMessage}").as(f)
                )
        }

  /**
   * 快照帧构造（按 root 过滤可选 = None 即全局）。只读：不触碰槽位。
   *
   * 进快照的 kind：`AskUser`（既有口径，逐字不变）+ **`McpPermission`（P0-1 扩展，
   * 验收 A1-7）** —— MCP 审批卡必须跨浏览器刷新 / WS 重连存活（spec §2.4「重连重放」、
   * §6 #7 建议「纳入重放」、roadmap §2.2 A14「三章口径 = 纳入重放」）。
   * 内置 `Permission` 卡**仍不进快照**（既有口径原文不变：权限卡走各自会话的历史/渲染链）。
   */
  private[agent] def snapshotFrames(
    m: Map[String, PendingRequest],
    rootFilter: Option[String]
  ): List[Json] =
    m.toList
      .collect {
        case (rid, p)
            if (p.kind == InteractionKind.AskUser || p.kind == InteractionKind.McpPermission) &&
              rootFilter.forall(_ == p.rootSessionId) =>
          (rid, p)
      }
      .sortBy(_._2.createdAt)
      .map { case (rid, p) =>
        // 案 C（双开缺陷批 2026-09-21，chain-askuserdup）：重放帧带**替代语义标**
        // `replaces:true`（= 本帧替代本会话内同 requestId 的既有卡，不是一张新提问）。
        // `replayed:true` 是既有的「快照重发」标，两者同帧：前者面向「这张卡与既有卡
        // 的关系」，后者面向「本帧的来源」，前端按 `msg.replayed || msg.replaces` 走
        // 替代腿（`main.js`）。🔴 本标不可单独闭环（历史卡仍须靠案 B 的 requestId 才
        // 能被寻址），故与案 B 同批落地、只作附属帧。
        renderPending(p, rid).deepMerge(
          Json.obj("replayed" -> Json.fromBoolean(true), "replaces" -> Json.fromBoolean(true))
        )
      }

  /** 重放帧按 kind 选渲染器（单一分派点，避免快照侧复制粘贴两份拼帧逻辑）。 */
  private def renderPending(p: PendingRequest, rid: String): Json =
    val req = InteractionRequest(
      requestId = rid,
      kind = p.kind,
      payload = p.payload,
      reply = p.reply,
      rootSessionId = p.rootSessionId,
      sourceAgent = p.sourceAgent,
      sourceSession = p.sourceSession
    )
    p.kind match
      case InteractionKind.AskUser => renderAskUser(req)
      case InteractionKind.McpPermission => renderMcpPermission(req)
      case InteractionKind.Permission => renderPermission(req)

  // ============================================================
  // askuser-secret 批（2026-10-08）：secret 槽位判据（本文件内单一实现，守卫/
  // complete/审计三处消费点一律委托）。
  // ============================================================

  /** pending 载荷 items 里的 secret 型题位次（0-based，按 items 序）。 */
  private[agent] def secretSlotsOf(p: PendingRequest): List[Int] =
    p.payload.hcursor.downField("items").as[List[Json]].toOption.getOrElse(Nil).zipWithIndex.collect {
      case (item, i) if item.hcursor.downField("secret").as[Boolean].toOption.getOrElse(false) => i
    }

  /** 单题的一次性参数（审计/回执用；非 ephemeral 题恒 None）。 */
  private def ephemeralTtlOf(p: PendingRequest, idx: Int): Option[Int] =
    p.payload.hcursor
      .downField("items")
      .as[List[Json]]
      .toOption
      .getOrElse(Nil)
      .lift(idx)
      .flatMap(_.hcursor.downField("ttlSec").as[Int].toOption)

  private def isEphemeralSlot(p: PendingRequest, idx: Int): Boolean =
    p.payload.hcursor
      .downField("items")
      .as[List[Json]]
      .toOption
      .getOrElse(Nil)
      .lift(idx)
      .exists(_.hcursor.downField("ephemeral").as[Boolean].toOption.getOrElse(false))

  /**
   * 只读查询（SecretSlotsOf）：槽位在册且 kind=AskUser ⇒ 回 secret 槽参数；否则
   * None（网关按旧路径透传，hub 守卫兜底）。
   */
  private def handleSecretSlotsOf(
    pending: Ref[IO, Map[String, PendingRequest]],
    requestId: String,
    reply: ActorRef[Option[List[SecretSlotSpec]]]
  ): IO[Unit] =
    pending.get
      .map(m =>
        m.get(requestId).filter(_.kind == InteractionKind.AskUser).map { p =>
          p.payload.hcursor.downField("items").as[List[Json]].toOption.getOrElse(Nil).zipWithIndex.collect {
            case (item, i) if item.hcursor.downField("secret").as[Boolean].toOption.getOrElse(false) =>
              SecretSlotSpec(
                idx = i,
                secretName = item.hcursor.downField("secretName").as[String].toOption.map(_.trim).filter(_.nonEmpty),
                ephemeral = item.hcursor.downField("ephemeral").as[Boolean].toOption.getOrElse(false),
                ttlSec = item.hcursor.downField("ttlSec").as[Int].toOption.filter(_ > 0)
              )
          }
        }
      )
      .flatMap(specs => (reply ! specs).void)

  /**
   * 只读查询（AttachGateOf，askuser-dragdrop 实施批 2026-10-19）：槽位在册且
   * kind=AskUser ⇒ 回附件闸（root 会话 + 是否 secret 卡）；否则 None（端点按
   * 卡不在册处理 → 410）。
   */
  private def handleAttachGateOf(
    pending: Ref[IO, Map[String, PendingRequest]],
    requestId: String,
    reply: ActorRef[Option[AttachGate]]
  ): IO[Unit] =
    pending.get
      .map(m =>
        m.get(requestId).filter(_.kind == InteractionKind.AskUser).map { p =>
          AttachGate(rootSessionId = p.rootSessionId, hasSecret = secretSlotsOf(p).nonEmpty)
        }
      )
      .flatMap(gate => (reply ! gate).void)

  // ============================================================
  // 工具面按角色分化批 B6 (2026-09-13): read-only reachability probe.
  //
  // Returns whether a root session currently has a client window registered
  // (`RegisterRoot`). Used by AskUserQuestion's **non-blocking** preflight:
  // in non-blocking mode nobody waits for the answer, so a card rendered into
  // an unreachable root (hub only warns, `handleRequest` None branch) would
  // lose the answer **silently** — the one "必修" item of spec §5.4. The
  // preflight makes that path an explicit refusal with ZERO slot registered.
  //
  // Read-only: never touches `pending` (no slot create/remove) and never
  // renders. Pure query ⇒ safe to call before any side effect.
  // ============================================================
  private def handleRootReachable(
    rootWsSend: Ref[IO, Map[String, Json => IO[Unit]]],
    rootSessionId: String,
    reply: ActorRef[Boolean]
  ): IO[Unit] =
    rootWsSend.get.flatMap { m =>
      val reachable = rootSessionId.nonEmpty && m.contains(rootSessionId)
      val note =
        if reachable then IO.unit
        else
          logger.info(
            s"RootReachable check: no wsSend registered for rootSessionId=$rootSessionId " +
              s"(${m.size} registered) — non-blocking ask will be declined fail-closed (B6)"
          )
      note *> (reply ! reachable).void
    }

  // ============================================================
  // Answer: complete the reply target (multi-slot by requestId)
  // ============================================================

  private def handleAnswered(
    pending: Ref[IO, Map[String, PendingRequest]],
    rootWsSend: Ref[IO, Map[String, Json => IO[Unit]]],
    ans: InteractionAnswered
  ): IO[Unit] =
    pending.modify { m =>
      if ans.requestId.nonEmpty then
        m.get(ans.requestId) match
          case Some(p) =>
            if answerCompletes(p, ans) then (m - ans.requestId, complete(p, ans))
            else
              // #12: a malformed answer must NOT consume the card — eating it
              // would strand the pending deferred forever with
              // the card gone (user cannot re-answer what they cannot see;
              // and since R1, wait-timeout-fix, no timeout would ever release it).
              val (reason, why) =
                if p.kind == InteractionKind.AskUser &&
                  ans.payload.hcursor
                    .downField("answers")
                    .as[List[String]]
                    .toOption
                    .exists(secretValueInPlainAnswers(p, _))
                then
                  // askuser-secret 批（规格 §5.1）：秘密槽明文回流（旧前端不识别
                  // secret 键）——专用 reason 让前端 toast 给出「刷新页面」指引，
                  // 卡片保留、值就地丢弃（不落任何存储）。
                  (
                    "secret-slot-mismatch",
                    s"secret question slot(s) received a plain-text answer — the value was DISCARDED unstored; " +
                      s"refresh the web panel (frontend must send secret values on the secretSlots key) — " +
                      s"card RETAINED for requestId=${ans.requestId}"
                  )
                else
                  (
                    "shape-mismatch",
                    s"answer shape does not match kind=${p.kind} of requestId=${ans.requestId} — card RETAINED"
                  )
              (
                m,
                rejectAnswer(rootWsSend, ans, reason, why)
              )
          case None => (m, rejectAnswer(rootWsSend, ans, "unknown-request-id", s"unknown requestId=${ans.requestId}"))
      else
        // Old-frontend fallback: no requestId — among this root session's
        // pending cards, take the OLDEST one this answer can complete.
        // #12: kind-matched so a permission answer is never wired into an
        // askUser card and vice versa; multi-card warn because the answer
        // may not be for the matched card at all.
        val candidates = m.toList
          .filter(_._2.rootSessionId == ans.rootSessionId)
          .sortBy(_._2.createdAt)
        val multiWarn =
          if candidates.size > 1 then
            logger.warn(
              s"InteractionAnswered without requestId: ${candidates.size} pending cards for " +
                s"rootSessionId=${ans.rootSessionId} — matched the oldest kind-compatible one; " +
                "possible cross-wiring, frontend should send requestId (#12)"
            )
          else IO.unit
        candidates.find { case (_, p) => answerCompletes(p, ans) } match
          case Some((rid, p)) => (m - rid, multiWarn *> complete(p, ans))
          case None =>
            (
              m,
              multiWarn *> rejectAnswer(
                rootWsSend,
                ans,
                "no-kind-compatible",
                s"no kind-compatible pending request for rootSessionId=${ans.rootSessionId}"
              )
            )
    }.flatten

  /**
   * #12: does this answer payload have the shape required to complete `p`?
   * A PermissionReply needs `approved` (boolean); an AskUserReply needs
   * `answers` (list). Shape-mismatched answers are dropped without consuming
   * the card — they would otherwise complete nothing while deleting the slot.
   *
   * P0-1（spec §2.4，验收 A1-5）：`McpPermissionReply` **同款必需 `approved: Boolean`**
   * —— 缺 `approved`（或非布尔）的答复**不消费卡**，与既有 Permission 判据逐字同构。
   *
   * askuser-secret 批（规格 §5.1 最高风险项）：AskUserReply 在此之上加**秘密槽
   * fail-closed 守卫**——识别不了 `secret` 键的旧前端把密码题当普通自由输入渲染，
   * 值会打在普通 `answers` 槽里回流（全链明文）。判据：任一 secret 题（
   * [[secretSlotsOf]]，pending 载荷 items 是唯一判据源）的 `answers` 槽**非空**
   * ⇒ 整条答复 shape-mismatch 拒收、**卡保留**（#12 语义）+ 既有
   * `interactionAnswerRejected` 广播 → 前端 toast 提示刷新。绝不静默降级。
   * 新前端的线协议：秘密槽的 `answers` 位**恒空串**（值走 `secretSlots` 专用键，
   * 网关落库后以 `stored` 回执数组随载荷上行，`complete` 时才占位化）。
   */
  private[agent] def answerCompletes(p: PendingRequest, ans: InteractionAnswered): Boolean =
    p.reply match
      case InteractionReply.PermissionReply(_) =>
        ans.payload.hcursor.downField("approved").as[Boolean].isRight
      case InteractionReply.McpPermissionReply(_) =>
        ans.payload.hcursor.downField("approved").as[Boolean].isRight
      case InteractionReply.AskUserReply(_) =>
        ans.payload.hcursor.downField("answers").as[List[String]].toOption match
          case None => false
          case Some(answers) => !secretValueInPlainAnswers(p, answers)

  /**
   * 秘密槽明文回流判据（守卫单一实现）：secret 题 answers 槽非空 = 旧前端/非法
   * 客户端把值打进了明文通道。回执 JSON 占位**不算**泄漏（那是 complete 自己
   * 写入下游的形态，不会出现在上行载荷里；防御性放行）。
   */
  private[agent] def secretValueInPlainAnswers(p: PendingRequest, answers: List[String]): Boolean =
    secretSlotsOf(p).exists { i =>
      answers.lift(i).exists(a => a.nonEmpty && !a.startsWith("(secret"))
    }

  /**
   * 多 AskUser 并发批（#250 第⑥项，2026-09-13 作者裁定「6 项全补」）：
   * 被丢弃的回答**必须用户可见**。
   *
   * 改前现象（代码判据）：形态不符 / requestId 未知 / 无 kind 兼容槽三条丢弃路径
   * 只留一行 `WARN` 日志（`logMissing`），用户侧零提示——点下按钮后界面毫无反应，
   * 而卡片按 #12 语义**保留**（不得消费），用户唯一的反馈是「什么都没发生」=
   * 「错误路径折成静默成功」缺陷族的标准形态（本项要修的正是「静默」）。
   *
   * 现口径：保留原 WARN 行（可归因），并**广播**一条
   * `interactionAnswerRejected{requestId, rootSessionId, reason, detail}` 到所有已注册
   * root 窗口（与 `askUserClosed` 同族：卡片可能因 #433 F4 fallback 渲染在别的窗口，
   * 反馈必须到达每一个可能显示该卡片的窗口）。前端消费点 = `main.js`
   * `onMessage('interactionAnswerRejected')` → 既有 `__showToast`。reason 是稳定码
   * （shape-mismatch | unknown-request-id | no-kind-compatible），文案在前端 i18n。
   *
   * best-effort：广播失败只加一行 WARN，绝不影响槽位状态（丢弃语义不变）。
   */
  private def rejectAnswer(
    rootWsSend: Ref[IO, Map[String, Json => IO[Unit]]],
    ans: InteractionAnswered,
    reason: String,
    why: String
  ): IO[Unit] =
    for
      _ <- logger.warn(
        s"InteractionAnswered dropped: $why (requestId=${ans.requestId}, root=${ans.rootSessionId})"
      )
      sends <- rootWsSend.get
      _ <- sends.toList.traverse_ { case (sid, ws) =>
        ws(
          Json.obj(
            "type" -> "interactionAnswerRejected".asJson,
            "sessionId" -> sid.asJson,
            "rootSessionId" -> ans.rootSessionId.asJson,
            "requestId" -> ans.requestId.asJson,
            "reason" -> reason.asJson,
            "detail" -> why.asJson
          )
        ).handleErrorWith(e =>
          logger.warn(s"answerRejected broadcast failed requestId=${ans.requestId} root=$sid: ${e.getMessage}")
        )
      }
    yield ()

  // ============================================================
  // P2 G11 (20260908 spec §3.4): source-death cleanup.
  //
  // A node cancelled while its AskUser card is pending leaves a zombie card:
  // nobody can ever answer it (the requesting session is dead) and the
  // deferred never completes — a permanently misleading blocker. The engine
  // cascades CleanupForSession on all three death exits (cancelNode /
  // NodeEdit abandon / dead-session reap): remove ALL pending slots whose
  // sourceSession matches, then broadcast askUserClosed{requestId} so the
  // frontend can retire the card + pending-bar entry (frontend consumption
  // lands in batch F2; acceptance here stops at the engine broadcast).
  // Broadcast goes to ALL registered roots — a card fanned out under
  // fallback:true (#433) renders in windows other than the owning root, and
  // a close signal must reach every window that may still show it.
  // ============================================================
  private def handleCleanupForSession(
    pending: Ref[IO, Map[String, PendingRequest]],
    rootWsSend: Ref[IO, Map[String, Json => IO[Unit]]],
    sessionId: String,
    reason: String = ""
  ): IO[Unit] =
    pending.modify { m =>
      val victims = m.toList.collect { case (rid, p) if p.sourceSession == sessionId => rid -> p }
      if victims.isEmpty then (m, IO.unit)
      else
        (
          m -- victims.map(_._1),
          for
            _ <- logger.info(
              s"CleanupForSession $sessionId: closing ${victims.size} pending ask(s) (source session died)"
            )
            sends <- rootWsSend.get
            _ <- victims.traverse_ { case (rid, p) =>
              sends.toList.traverse_ { case (sid, ws) =>
                // 帧形状逐字节兼容（reason 缺省时零新增键，旧前端忽略未知键）；非空
                // reason 让前端把卡片文案从「来源已关闭」改成更贴合的中断文案。
                val base = Json.obj(
                  "type" -> "askUserClosed".asJson,
                  "sessionId" -> sid.asJson,
                  "requestId" -> rid.asJson,
                  "sourceSession" -> sessionId.asJson
                )
                ws(if reason.nonEmpty then base.deepMerge(Json.obj("reason" -> reason.asJson)) else base)
                  .handleErrorWith(e =>
                    logger.warn(s"askUserClosed broadcast failed requestId=$rid root=$sid: ${e.getMessage}") *> IO.unit
                  )
              }
            }
          yield ()
        )
      end if
    }.flatten

  // ============================================================
  // 调用侧撤回（#147 接线段，2026-09-12）：按 requestId **精确**回收单个槽位。
  //
  // WHY：确认链（`SendConfirm`，SendMessage ask 档）有 60s 等待上限；超时后请求方
  // 已经带判据失败了，此时若卡片还挂在 hub 里，用户稍后点它 = 点了个「没人在听」
  // 的动作 —— 本仓「错误路径折成静默成功」缺陷族的标准形态。CleanupForSession
  // 不能用来干这件事：它按 sourceSession 批量回收（源会话死亡语义），会连带杀掉
  // 同会话**其它**仍有效的 pending 卡（例如同一 turn 里 Nebula 自己还挂着一张
  // AskUserQuestion）。
  //
  // 广播口径与 CleanupForSession 逐字同族（askUserClosed 走 ALL registered roots，
  // 前端既有消费点 = `main.js onMessage('askUserClosed')` → closeAskUserCard +
  // removePendingAsk ⇒ 零前端改动）。多一个 `reason` 字段（旧前端忽略未知键）。
  // ============================================================
  private def handleCloseRequest(
    pending: Ref[IO, Map[String, PendingRequest]],
    rootWsSend: Ref[IO, Map[String, Json => IO[Unit]]],
    requestId: String
  ): IO[Unit] =
    pending.modify { m =>
      m.get(requestId) match
        case None =>
          // 幂等：已答复/已回收/从未存在 —— no-op（不报错、不广播）。
          (m, logger.debug(s"CloseRequest for unknown requestId=$requestId (already answered or closed)"))
        case Some(p) =>
          (
            m - requestId,
            for
              _ <- logger.info(
                s"CloseRequest $requestId (kind=${p.kind}) — caller withdrew; closing the card"
              )
              sends <- rootWsSend.get
              _ <- sends.toList.traverse_ { case (sid, ws) =>
                ws(
                  Json.obj(
                    "type" -> "askUserClosed".asJson,
                    "sessionId" -> sid.asJson,
                    "requestId" -> requestId.asJson,
                    "sourceSession" -> p.sourceSession.asJson,
                    "reason" -> "caller-withdrew".asJson
                  )
                ).handleErrorWith(e =>
                  logger.warn(
                    s"CloseRequest broadcast failed requestId=$requestId root=$sid: ${e.getMessage}"
                  ) *> IO.unit
                )
              }
            yield ()
          )
    }.flatten

  // ============================================================
  // askucancel 批（2026-10-08）：用户发起的取消（CancelRequest）。
  //
  // 三段语义（与 handleCloseRequest 的差别各占一段）：
  //   ① kind 闸：只终结 InteractionKind.AskUser 槽位；permission/mcpPermission 卡
  //      各有自身生命周期（approve/deny 即终态），误触取消回 NotAskUser，不静默。
  //   ② 发起方终结通知：撤回信号（nebflow.shared.AskUserCancelled.signal）经槽位
  //      既有答复管道（AskUserReply.replyTo）投达请求方——同一条管道平时送答案，
  //      取消时送「不会有答案了」的明确结论：阻塞面板腿（ProjectTool.pathPanel）
  //      据此判 Shelved 解除挂起 + 恢复预算；非阻塞桥腿（AskUserAnswerBridge）
  //      注入取消通告给发起的代理/节点。
  //   ③ 显式裁决：Cancelled（携带 root/sourceSession，调用方据此追加取消落盘行）/
  //      UnknownRequestId（幂等：重复取消、取消已答问题、取消从未存在的 id 同一终态）/
  //      NotAskUser。裁决在广播**之后**回——调用方拿到 Cancelled 时前端关卡帧已在途。
  // 广播面与 handleCloseRequest 逐字同族（askUserClosed 走 ALL registered roots；
  // 两条既有消费点零改动：main.js onMessage('askUserClosed')、两桥的 askUserClosed
  // 分支各自清本地路由缓存/台账）。reason 缺省补 "user-cancelled"。
  // ============================================================
  private def handleCancelRequest(
    pending: Ref[IO, Map[String, PendingRequest]],
    rootWsSend: Ref[IO, Map[String, Json => IO[Unit]]],
    requestId: String,
    reason: String,
    by: String,
    reply: Option[ActorRef[AskCancelOutcome]]
  ): IO[Unit] =
    val effectiveReason = if reason.nonEmpty then reason else "user-cancelled"
    def respond(outcome: AskCancelOutcome): IO[Unit] =
      reply.fold(IO.unit)(r => (r ! outcome).void)
    pending.modify { m =>
      m.get(requestId) match
        case None =>
          // 幂等：已答复/已回收/从未存在 —— 同一终态，不报错、不广播。
          (
            m,
            respond(AskCancelOutcome.UnknownRequestId) *>
              logger.debug(
                s"CancelRequest for unknown requestId=$requestId (already answered, closed, or never existed)"
              )
          )
        case Some(p) if p.kind != InteractionKind.AskUser =>
          (
            m,
            respond(AskCancelOutcome.NotAskUser(p.kind)) *>
              logger.info(
                s"CancelRequest refused for requestId=$requestId (kind=${p.kind} is not AskUser — " +
                  "only AskUser questions are cancellable; permission cards resolve by approve/deny)"
              )
          )
        case Some(p) =>
          (
            m - requestId,
            for
              _ <- logger.info(
                s"CancelRequest $requestId (kind=AskUser) — cancelled by=$by reason=$effectiveReason; closing the card"
              )
              // R10/U5=F-b 审计行（cancel 单点）——best-effort。
              _ <- audit
                .info(
                  s"event=cancel requestId=$requestId sourceSession=${p.sourceSession} " +
                    s"rootSessionId=${p.rootSessionId} kind=${p.kind} by=$by reason=$effectiveReason"
                )
                .handleErrorWith(_ => IO.unit)
              // ② 发起方终结通知（先于广播：会话侧先拿到结论，前端关卡帧随后在途）。
              _ <- p.reply match
                case InteractionReply.AskUserReply(replyToOpt) =>
                  replyToOpt.fold(IO.unit)(r =>
                    (r ! nebflow.shared.AskUserCancelled.signal(requestId, effectiveReason, by)).void
                  )
                case _ => IO.unit
              sends <- rootWsSend.get
              _ <- sends.toList.traverse_ { case (sid, ws) =>
                ws(
                  Json.obj(
                    "type" -> "askUserClosed".asJson,
                    "sessionId" -> sid.asJson,
                    "requestId" -> requestId.asJson,
                    "sourceSession" -> p.sourceSession.asJson,
                    "reason" -> effectiveReason.asJson
                  )
                ).handleErrorWith(e =>
                  logger.warn(
                    s"CancelRequest broadcast failed requestId=$requestId root=$sid: ${e.getMessage}"
                  ) *> IO.unit
                )
              }
              _ <- respond(AskCancelOutcome.Cancelled(requestId, p.kind, p.rootSessionId, p.sourceSession))
            yield ()
          )
    }.flatten

  end handleCancelRequest

  /**
   * R10/U5=F-b 审计行（answer 单点），`via` = card。best-effort。
   *
   * 输入框直通退役（2026-09-14 作者令「把 AskUserQuestion 通过输入框回答的功能
   * 关了」）：`via` 曾另有 `chat-input` 取值，随 `handleChatInputAnswer` 一并
   * 删除 ⇒ 现存唯一取值 = card（卡片入口作答）。
   */
  private def auditAnswer(requestId: String, p: PendingRequest, answersJoined: String, via: String, attachNote: String = ""): IO[Unit] =
    val excerpt = if answersJoined.length > 200 then answersJoined.take(200) + "..." else answersJoined
    audit
      .info(
        s"event=answer requestId=$requestId sourceSession=${p.sourceSession} rootSessionId=${p.rootSessionId} " +
          s"kind=${p.kind} via=$via$attachNote answersJoined=$excerpt"
      )
      .handleErrorWith(_ => IO.unit)

  // 可见性 `private[agent]`（同 `PendingRequest` 先例 · McpPermissionReplaySpec 形态）：
  // askuser-secret 批验收「回执 JSON 的 answers 秘密槽位被占位化」需对**生产本体**取值，
  // spec 在包内直接捕获 delivered 载荷。零行为影响（同包可见性放宽）。
  private[agent] def complete(p: PendingRequest, ans: InteractionAnswered, via: String = "card"): IO[Unit] =
    val approved = ans.payload.hcursor.downField("approved").as[Boolean].toOption
    val answers = ans.payload.hcursor.downField("answers").as[List[String]].toOption
    // P0-1（spec §2.4/§2.5）：mcpPermission 的答复面比 permission 多 scope/upgradeMode。
    val mcpAnswer =
      if p.kind == InteractionKind.McpPermission then nebflow.shared.McpPermissionAnswer.decode(ans.payload)
      else None
    // askuser-secret 批（规格 §2）：秘密槽占位化 + 审计脱敏（hub 侧单一实现）。
    val secretIdxs = if p.kind == InteractionKind.AskUser then secretSlotsOf(p) else Nil
    // 占位化：网关随载荷上行的 `stored` 回执（AskUserSecretSink.Receipt 的 Json 形）
    // 折进 answers 的秘密槽位——回执 = 指针（keyRef/location/variableName），零值。
    // 无回执的秘密槽（渠道只答非 secret 题 / 落库失败缺席）保持空串（下游渲染
    // 「未提供」）。非 secret 卡 ⇒ 恒等（字节不变）。
    val storedReceipts: List[Json] =
      if secretIdxs.isEmpty then Nil
      else ans.payload.hcursor.downField("stored").as[List[Json]].toOption.getOrElse(Nil)
    def answersForDelivery: List[String] =
      if secretIdxs.isEmpty then answers.getOrElse(Nil)
      else
        val base = Array.fill(itemsCountOf(p))("")
        answers.getOrElse(Nil).zipWithIndex.foreach { case (a, i) => if i < base.length then base(i) = a }
        storedReceipts.foreach { r =>
          val c = r.hcursor
          val idx = c.downField("idx").as[Int].toOption
          val ok = c.downField("stored").as[Boolean].toOption.getOrElse(false)
          idx.filter(secretIdxs.contains).foreach { i =>
            if ok then
              base(i) = Json
                .obj(
                  "stored" -> Json.fromBoolean(true),
                  "keyRef" -> Json.fromString(c.downField("keyRef").as[String].getOrElse("")),
                  "location" -> Json.fromString(c.downField("location").as[String].getOrElse("")),
                  "variableName" -> Json.fromString(c.downField("variableName").as[String].getOrElse("")),
                  "ephemeral" -> Json.fromBoolean(isEphemeralSlot(p, i))
                )
                .noSpaces
            else
              base(i) = Json
                .obj(
                  "stored" -> Json.fromBoolean(false),
                  "reason" -> Json.fromString(c.downField("reason").as[String].getOrElse("unstorable"))
                )
                .noSpaces
          }
        }
        base.toList
    // R10 审计脱敏（规格 §2「auditAnswer 只记 slot 数与 keyRef」）：有秘密槽的卡
    // 不再把答案拼接进审计行——只记总槽数、秘密槽数与 keyRef 名单。
    val secretAudit: Option[String] =
      if secretIdxs.isEmpty then None
      else
        val keyRefs = storedReceipts.flatMap(r => r.hcursor.downField("keyRef").as[String].toOption)
        Some(
          s"answers=${answersForDelivery.size} slots, secret=${secretIdxs.size} " +
            s"keyRefs=[${keyRefs.mkString(",")}] (secret values withheld)"
        )
    // P0-2（spec §2.5 + §6 #2 建议口径「启用（L0-L2；不落盘）」）：卡答 scope=session 且
    // approved ⇒ 记 (serverId, tool) 会话内免审。**只内存**（SessionApprovals 无落盘面）；
    // **只对 L0-L2**（L3 恒审红线在判定侧强制，见 McpToolGate.decide）。
    val rememberSessionApproval: Unit =
      mcpAnswer match
        case Some(a) if a.approved && a.wantsSessionScope =>
          val sid = p.payload.hcursor.downField("serverId").as[String].toOption.getOrElse("")
          val tool = p.payload.hcursor.downField("tool").as[String].toOption.getOrElse("")
          val tier = p.payload.hcursor.downField("riskTier").as[String].toOption.getOrElse("")
          if sid.nonEmpty && tool.nonEmpty && tier != "L3" then
            nebflow.core.SessionApprovals.remember(p.sourceSession, sid, tool)
            logger.infoSync(s"mcpPermission scope=session: session=${p.sourceSession} server=$sid tool=$tool")
          else
            logger.warnSync(
              s"mcpPermission scope=session IGNORED (server='$sid' tool='$tool' tier='$tier') — " +
                "L3 is always-ask (host red line); no session approval recorded"
            )
        case _ => ()
    // askuser-dragdrop 实施批（2026-10-19，批 #551）：随卡回传的**答复附件清单**
    // （网关 askUserAnswer 帧的 `attachments` 键，已由网关终验）。只对 AskUser 槽位
    // 生效：非空 ⇒ 以尾随清单槽（[[AskUserAnswerAttachments.manifestSlot]]）与答复
    // 同投 —— 桥腿（AskUserAnswerBridge）剥下后注入模型上下文；其它消费面
    // （formatAnswer / parsePanelAnswer / SendConfirm）对尾随多余槽天然无感。
    val answerAttachments: List[Json] =
      if p.kind == InteractionKind.AskUser then
        ans.payload.hcursor.downField("attachments").as[List[Json]].toOption.getOrElse(Nil)
      else Nil
    val attachAuditNote =
      if answerAttachments.isEmpty then ""
      else
        s" attach=${answerAttachments.size}(" +
          answerAttachments
            .map(a =>
              val c = a.hcursor
              s"${c.downField("name").as[String].getOrElse("?")},${c.downField("size").as[Long].toOption.getOrElse(0L)}B"
            )
            .mkString(";") + ")"
    for
      _ <- logger.info(
        s"InteractionAnswered requestId=${ans.requestId} kind=${p.kind} approved=$approved answers=${answers.map(_.size)}" +
          (if answerAttachments.isEmpty then "" else s" attachments=${answerAttachments.size}")
      )
      _ <- auditAnswer(
        ans.requestId,
        p,
        secretAudit.getOrElse(
          answers
            .map(_.mkString(" | "))
            .orElse(
              mcpAnswer.map(a =>
                s"approved=${a.approved} scope=${a.scope.getOrElse("once")} upgrade=${a.upgradeMode.getOrElse("-")}"
              )
            )
            .orElse(approved.map(a => s"approved=$a"))
            .getOrElse("-")
        ),
        via,
        attachAuditNote
      )
      _ <- IO.delay(rememberSessionApproval)
      _ <- p.reply match
        case InteractionReply.PermissionReply(deferred) =>
          approved.fold(IO.unit)(a => deferred.complete(a).void.handleErrorWith(_ => IO.unit))
        case InteractionReply.McpPermissionReply(deferred) =>
          mcpAnswer.fold(IO.unit)(a => deferred.complete(a).void.handleErrorWith(_ => IO.unit))
        case InteractionReply.AskUserReply(replyTo) =>
          replyTo.fold(IO.unit)(r =>
            (r ! (answersForDelivery ++
              nebflow.shared.AskUserAnswerAttachments.manifestSlot(answerAttachments)))
          )
    yield ()
    end for
  end complete

  /**
   * pending 载荷 items 的题数（answersForDelivery 的补齐基准——渠道腿恒发全长
   * 数组，此处兜底防越界）。
   */
  private def itemsCountOf(p: PendingRequest): Int =
    p.payload.hcursor.downField("items").as[List[Json]].toOption.map(_.size).getOrElse(0)
end InteractionHub

// 严格DAG第⑥步第三批A裁定(dwfq-5c7a31ea-1,R-B=B1):InteractionHubCommand 命令 ADT 已整体
// 下沉 nebflow.actor(新文件 actor/InteractionHubCommand.scala,成员与注释逐字迁移);
// 本文件经上方 import 引用,实现原地不搬。
