package nebflow.agent

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.core.NebflowLogger

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

  /** R10 审计面（作者裁定 U5=F-b）：ask / answer 两个**既有单点**各追加一行
    * `nebflow.audit`（先例 `AgentControlTool`：内核持全机权限 + 远端动作不可逆 ⇒
    * 事后可归因）。字段含 `sourceSession`——事后用 `delegate-kernel-` 前缀下过滤
    * 即可单独看内核提问链。**不新增事件类型、不改 `WatchdogEventLog`、零新存储。**
    * 注意作用域：hub 是全局单点，故审计行覆盖一切 ask 源（内核 + 项目节点）——
    * 按前缀过滤即内核面。 */
  private val audit = NebflowLogger.forName("nebflow.audit")

  /** 槽位条目。可见性 `private[agent]`（而非 `private`）：P0-1 验收 A1-5/A1-7 要求对
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

  def apply(): Behavior[InteractionHubCommand] =
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
              ctx.forkTurn(handleListPendingAsks(pending, rootSessionId, reply)) *> IO.pure(behavior)
            case InteractionHubCommand.ListAllPendingAsks(reply) =>
              // #250 第③项 (2026-09-13): global snapshot — the frontend's pending
              // mirror (bar/badge) rebuilds from ONE global read instead of
              // depending on which session happens to be (re)subscribed.
              ctx.forkTurn(handleListAllPendingAsks(pending, reply)) *> IO.pure(behavior)
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
              logger.warn(s"InteractionRequest render failed for requestId=${req.requestId}: ${e.getMessage}") *> IO.unit
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
              ws(render.deepMerge(Json.obj(
                "fallback" -> true.asJson,
                "fallbackRoot" -> req.rootSessionId.asJson
              ))).handleErrorWith { e =>
                logger.warn(
                  s"InteractionRequest fallback render failed for requestId=${req.requestId} root=$sid: ${e.getMessage}"
                ) *> IO.unit
              }
            } *>
              logger.warn(
                s"InteractionRequest root=${req.rootSessionId} unreachable — fanned out to ${others.size} " +
                  s"registered root(s) with fallback flag (requestId=${req.requestId}, #433 F4)"
              )
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

  /** Build the askUser question JSON, rendered at sessionId=rootSessionId.
    *
    * `agentName` 的来源：**payload 显式携带者优先**（内核会话的 U3 来源标注
    * `subagent · <任务摘要>` 由 `AgentActor` 写在 payload.agentName 上——它是
    * 前端 badge/待办条的回落标签来源），缺省才用 `sourceAgent`。两条既有路径
    * 逐字节不变：项目节点/分发器/Nebula 的 payload.agentName 恒等于 sourceAgent
    * （`buildAskUserJson` 首参），重放路径（`ListPendingAsks`）用的也是同一
    * 存储 payload ⇒ 渲染结果不变。 */
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

  /** Build the mcpPermission card JSON (P0-1 / P-M1, spec §2.4), rendered at
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
    rootSessionId: String,
    reply: ActorRef[List[Json]]
  ): IO[Unit] =
    pending.get
      .map(m => snapshotFrames(m, Some(rootSessionId)))
      .flatTap(list =>
        if list.nonEmpty then
          logger.info(s"ListPendingAsks root=$rootSessionId → ${list.size} pending ask(s) replayed")
        else IO.unit
      )
      .flatMap(list => (reply ! list).void)

  /** 多 AskUser 并发批（#250 第③项，2026-09-13 作者裁定「6 项全补」）：
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
    * ——后者保留原职责（把卡片渲染进对应会话的聊天流），不再承担镜像重建。 */
  private def handleListAllPendingAsks(
    pending: Ref[IO, Map[String, PendingRequest]],
    reply: ActorRef[List[Json]]
  ): IO[Unit] =
    pending.get
      .map(m => snapshotFrames(m, None))
      .flatTap(list =>
        logger.info(s"ListAllPendingAsks → global pending-ask snapshot: ${list.size} card(s)")
      )
      .flatMap(list => (reply ! list).void)

  /** 快照帧构造（按 root 过滤可选 = None 即全局）。只读：不触碰槽位。
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
              rootFilter.forall(_ == p.rootSessionId) => (rid, p)
      }
      .sortBy(_._2.createdAt)
      .map { case (rid, p) =>
        renderPending(p, rid).deepMerge(Json.obj("replayed" -> Json.fromBoolean(true)))
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
      case InteractionKind.AskUser       => renderAskUser(req)
      case InteractionKind.McpPermission => renderMcpPermission(req)
      case InteractionKind.Permission    => renderPermission(req)


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
              (
                m,
                rejectAnswer(
                  rootWsSend,
                  ans,
                  "shape-mismatch",
                  s"answer shape does not match kind=${p.kind} of requestId=${ans.requestId} — card RETAINED"
                )
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

  /** #12: does this answer payload have the shape required to complete `p`?
    * A PermissionReply needs `approved` (boolean); an AskUserReply needs
    * `answers` (list). Shape-mismatched answers are dropped without consuming
    * the card — they would otherwise complete nothing while deleting the slot.
    *
    * P0-1（spec §2.4，验收 A1-5）：`McpPermissionReply` **同款必需 `approved: Boolean`**
    * —— 缺 `approved`（或非布尔）的答复**不消费卡**，与既有 Permission 判据逐字同构。
    */
  private[agent] def answerCompletes(p: PendingRequest, ans: InteractionAnswered): Boolean =
    p.reply match
      case InteractionReply.PermissionReply(_) =>
        ans.payload.hcursor.downField("approved").as[Boolean].isRight
      case InteractionReply.McpPermissionReply(_) =>
        ans.payload.hcursor.downField("approved").as[Boolean].isRight
      case InteractionReply.AskUserReply(_) =>
        ans.payload.hcursor.downField("answers").as[List[String]].isRight

  /** 多 AskUser 并发批（#250 第⑥项，2026-09-13 作者裁定「6 项全补」）：
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
    * best-effort：广播失败只加一行 WARN，绝不影响槽位状态（丢弃语义不变）。 */
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
          logger.warn(s"answerRejected broadcast failed requestId=${ans.requestId} root=$sid: ${e.getMessage}"))
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
              s"CleanupForSession $sessionId: closing ${victims.size} pending ask(s) (source session died)")
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
                  logger.warn(s"askUserClosed broadcast failed requestId=$rid root=$sid: ${e.getMessage}") *> IO.unit)
              }
            }
          yield ()
        )
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
                  logger.warn(s"CloseRequest broadcast failed requestId=$requestId root=$sid: ${e.getMessage}") *> IO.unit)
              }
            yield ()
          )
    }.flatten

  /** R10/U5=F-b 审计行（answer 单点），`via` = card。best-effort。
    *
    * 输入框直通退役（2026-09-14 作者令「把 AskUserQuestion 通过输入框回答的功能
    * 关了」）：`via` 曾另有 `chat-input` 取值，随 `handleChatInputAnswer` 一并
    * 删除 ⇒ 现存唯一取值 = card（卡片入口作答）。 */
  private def auditAnswer(requestId: String, p: PendingRequest, answersJoined: String, via: String): IO[Unit] =
    val excerpt = if answersJoined.length > 200 then answersJoined.take(200) + "..." else answersJoined
    audit
      .info(
        s"event=answer requestId=$requestId sourceSession=${p.sourceSession} rootSessionId=${p.rootSessionId} " +
          s"kind=${p.kind} via=$via answersJoined=$excerpt"
      )
      .handleErrorWith(_ => IO.unit)

  private def complete(p: PendingRequest, ans: InteractionAnswered, via: String = "card"): IO[Unit] =
    val approved = ans.payload.hcursor.downField("approved").as[Boolean].toOption
    val answers = ans.payload.hcursor.downField("answers").as[List[String]].toOption
    // P0-1（spec §2.4/§2.5）：mcpPermission 的答复面比 permission 多 scope/upgradeMode。
    val mcpAnswer =
      if p.kind == InteractionKind.McpPermission then nebflow.core.McpPermissionAnswer.decode(ans.payload)
      else None
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
    for
      _ <- logger.info(
        s"InteractionAnswered requestId=${ans.requestId} kind=${p.kind} approved=$approved answers=${answers.map(_.size)}"
      )
      _ <- auditAnswer(
        ans.requestId,
        p,
        answers
          .map(_.mkString(" | "))
          .orElse(mcpAnswer.map(a =>
            s"approved=${a.approved} scope=${a.scope.getOrElse("once")} upgrade=${a.upgradeMode.getOrElse("-")}"))
          .orElse(approved.map(a => s"approved=$a"))
          .getOrElse("-"),
        via
      )
      _ <- IO.delay(rememberSessionApproval)
      _ <- p.reply match
        case InteractionReply.PermissionReply(deferred) =>
          approved.fold(IO.unit)(a => deferred.complete(a).void.handleErrorWith(_ => IO.unit))
        case InteractionReply.McpPermissionReply(deferred) =>
          mcpAnswer.fold(IO.unit)(a => deferred.complete(a).void.handleErrorWith(_ => IO.unit))
        case InteractionReply.AskUserReply(replyTo) =>
          replyTo.fold(IO.unit)(r => (r ! answers.getOrElse(Nil)))
    yield ()
end InteractionHub

/** Commands accepted by the InteractionHub actor. */
sealed trait InteractionHubCommand

object InteractionHubCommand:
  /** Gateway → hub: register a root session's recording wsSend (render target). */
  final case class RegisterRoot(rootSessionId: String, wsSend: Json => IO[Unit]) extends InteractionHubCommand

  /** Gateway → hub: root session closed — remove its render target. */
  final case class UnregisterRoot(rootSessionId: String) extends InteractionHubCommand

  /** Agent → hub: a permission / AskUser request from any agent in the tree. */
  final case class Request(req: InteractionRequest) extends InteractionHubCommand

  /** Gateway → hub: user answered (translated from permissionAnswer/askUserAnswer). */
  final case class Answered(ans: InteractionAnswered) extends InteractionHubCommand

  /** 刷新存活 (2026-09-03): gateway → hub — snapshot the still-pending AskUser
    * cards for `rootSessionId` (oldest first), each rendered exactly like the
    * first send plus `replayed: true`. The gateway re-sends them when a client
    * (re)subscribes to the session (initial history load after browser refresh
    * / WS reconnect / session switch) so the card and its requestId binding
    * survive regardless of history pagination. Read-only: never touches the
    * pending map or the reply slots.
    */
  final case class ListPendingAsks(rootSessionId: String, reply: ActorRef[List[Json]])
      extends InteractionHubCommand

  /** P2 G11 (20260908 spec §3.4): engine → hub — the node owning `sessionId`
    * (sourceSession of its asks) reached cancelled (cancelNode / abandon /
    * dead-session reap cascade). Remove every pending slot sourced from that
    * session and broadcast askUserClosed{requestId} so no zombie card outlives
    * its asker.
    *
    * #250 第②项（2026-09-13 作者裁定「6 项全补」）：同一语义也覆盖**用户中断
    * turn**（root 会话此前没有清理入口）。`reason` 是可选来源标注
    * （"turn-interrupted"），缺省 "" 时广播帧逐字节不变（旧前端忽略未知键）。 */
  final case class CleanupForSession(sessionId: String, reason: String = "") extends InteractionHubCommand

  /** 多 AskUser 并发批（#250 第③项，2026-09-13 作者裁定「6 项全补」）：
    * gateway → hub — **全局** pending-AskUser 快照（跨 root，按 createdAt 升序），
    * 每帧与 `ListPendingAsks` 逐字节同构（`replayed: true`，`sessionId` = 各自 root）。
    * 前端重连时用它把待办条/badge 的本地镜像与 hub 权威一次性对齐，不再依赖
    * 「哪个会话恰好被（重新）订阅」。只读：不触碰 pending map 或 reply 槽位。 */
  final case class ListAllPendingAsks(reply: ActorRef[List[Json]]) extends InteractionHubCommand

  /** #147 接线段（2026-09-12）：requester → hub — the caller itself stopped
    * waiting for `requestId` (SendMessage ask-档 confirm timed out) and returns
    * a judged failure. Remove exactly that slot and broadcast
    * askUserClosed{requestId, reason:"caller-withdrew"} so the user is never
    * left with a clickable card whose answer goes nowhere. Precise by
    * requestId — never the session-wide sweep of CleanupForSession. Idempotent:
    * an unknown/already-answered requestId is a no-op. */
  final case class CloseRequest(requestId: String) extends InteractionHubCommand

  /** 工具面按角色分化批 B6（2026-09-13）：**只读**可达性查询 ——
    * `rootSessionId` 当前是否有已注册的客户端窗口（`RegisterRoot` 的 wsSend）。
    * 非阻塞 AskUserQuestion 在注册槽位**之前**用它做前置预检：非阻塞下没人等待
    * ⇒ 卡渲染进不可达 root（`handleRequest` 的 None 分支只 warn）= 答案静默丢失。
    * 预检把这条路径变成**显式拒绝 + 零槽位**（fail-closed）。
    *
    * 读侧纪律：不碰 `pending`（不建/不删槽位）、不渲染、不广播 —— 纯查询，故可安全
    * 放在任何副作用之前。阻塞模式**不用**它（阻塞路径 root 不可达时的扇出回落
    * `fallback:true` 语义保持不变）。 */
  final case class RootReachable(rootSessionId: String, reply: ActorRef[Boolean]) extends InteractionHubCommand
