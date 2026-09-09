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

  private case class PendingRequest(
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
              ctx.forkTurn(handleAnswered(pending, ans)) *> IO.pure(behavior)
            case InteractionHubCommand.AnswerViaChatInput(rootSessionId, text, reply) =>
              // 第六件 (2026-08-30): chat-input passthrough — the gateway asks
              // on behalf of the input box; single atomic modify = query + slot
              // removal (P1 lesson: reply-slot lifecycle lives in ONE place).
              ctx.forkTurn(handleChatInputAnswer(pending, rootWsSend, rootSessionId, text, reply)) *>
                IO.pure(behavior)
            case InteractionHubCommand.ListPendingAsks(rootSessionId, reply) =>
              // 刷新存活 (2026-09-03): read-only snapshot for reconnect replay.
              ctx.forkTurn(handleListPendingAsks(pending, rootSessionId, reply)) *> IO.pure(behavior)
            case InteractionHubCommand.CleanupForSession(sessionId) =>
              // P2 G11 (20260908 spec §3.4): source-death cleanup — node cancelled
              // while its AskUser card is pending → close the card (engine cascade:
              // cancelNode / abandon / dead-session reap).
              ctx.forkTurn(handleCleanupForSession(pending, rootWsSend, sessionId)) *> IO.pure(behavior)
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
    for
      _ <- logger.info(
        s"InteractionRequest kind=${req.kind} requestId=${req.requestId} root=${req.rootSessionId} " +
          s"sourceAgent=${req.sourceAgent}"
      )
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

  /** Build the askUser question JSON, rendered at sessionId=rootSessionId. */
  private def renderAskUser(req: InteractionRequest): Json =
    val base = req.payload.asObject.getOrElse(JsonObject.empty)
    Json.fromJsonObject(
      base
        .add("type", "askUser".asJson)
        .add("sessionId", req.rootSessionId.asJson)
        .add("requestId", req.requestId.asJson)
        .add("agentName", req.sourceAgent.asJson)
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
    pending.get.map { m =>
      m.toList
        .collect {
          case (rid, p) if p.rootSessionId == rootSessionId && p.kind == InteractionKind.AskUser => (rid, p)
        }
        .sortBy(_._2.createdAt)
        .map { case (rid, p) =>
          renderAskUser(
            InteractionRequest(
              requestId = rid,
              kind = p.kind,
              payload = p.payload,
              reply = p.reply,
              rootSessionId = p.rootSessionId,
              sourceAgent = p.sourceAgent,
              sourceSession = p.sourceSession
            )
          ).deepMerge(Json.obj("replayed" -> Json.fromBoolean(true)))
        }
    }.flatTap(list =>
      if list.nonEmpty then
        logger.info(s"ListPendingAsks root=$rootSessionId → ${list.size} pending ask(s) replayed")
      else IO.unit
    ).flatMap(list => (reply ! list).void)


  // ============================================================
  // Answer: complete the reply target (multi-slot by requestId)
  // ============================================================

  private def handleAnswered(
    pending: Ref[IO, Map[String, PendingRequest]],
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
                logMissing(ans, s"answer shape does not match kind=${p.kind} of requestId=${ans.requestId} — card RETAINED")
              )
          case None => (m, logMissing(ans, s"unknown requestId=${ans.requestId}"))
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
            (m, multiWarn *> logMissing(ans, s"no kind-compatible pending request for rootSessionId=${ans.rootSessionId}"))
    }.flatten

  /** #12: does this answer payload have the shape required to complete `p`?
    * A PermissionReply needs `approved` (boolean); an AskUserReply needs
    * `answers` (list). Shape-mismatched answers are dropped without consuming
    * the card — they would otherwise complete nothing while deleting the slot.
    */
  private def answerCompletes(p: PendingRequest, ans: InteractionAnswered): Boolean =
    p.reply match
      case InteractionReply.PermissionReply(_) =>
        ans.payload.hcursor.downField("approved").as[Boolean].isRight
      case InteractionReply.AskUserReply(_) =>
        ans.payload.hcursor.downField("answers").as[List[String]].isRight

  private def logMissing(ans: InteractionAnswered, why: String): IO[Unit] =
    logger.warn(s"InteractionAnswered dropped: $why (requestId=${ans.requestId}, root=${ans.rootSessionId})") *> IO.unit

  // ============================================================
  // 第六件 (2026-08-30): chat-input passthrough for pending AskUser cards.
  //
  // One atomic modify does query + slot removal — the P1 lesson (reply-slot
  // lifecycle) says slot state must change in exactly one place; splitting
  // "peek" from "consume" would race a concurrent card answer. Only the
  // OLDEST pending AskUser of this root session is consumed; permission
  // cards are never touched (kind filter). The gateway learns the outcome
  // via `reply`: true = card consumed (deliver as tool result), false = no
  // pending card (fall back to normal dispatch).
  // ============================================================
  private def handleChatInputAnswer(
      pending: Ref[IO, Map[String, PendingRequest]],
      rootWsSend: Ref[IO, Map[String, Json => IO[Unit]]],
      rootSessionId: String,
      text: String,
      reply: cats.effect.Deferred[IO, Boolean]
  ): IO[Unit] =
    pending.modify { m =>
      val candidates = m.toList
        .collect { case (rid, p) if p.rootSessionId == rootSessionId && p.kind == InteractionKind.AskUser => (rid, p) }
        .sortBy(_._2.createdAt)
      // QC (2026-08-30): parity with handleAnswered's multiWarn — with several
      // pending AskUser cards the passthrough answers the OLDEST, which may
      // not be the card the user was looking at. Never silent about it.
      val multiWarn =
        if candidates.size > 1 then
          logger.warn(
            s"Chat-input passthrough: ${candidates.size} pending AskUser cards for rootSessionId=$rootSessionId — " +
              "answering the OLDEST; the user should answer a specific card on the card itself (#12 parity)"
          )
        else IO.unit
      candidates.headOption match
        case Some((rid, p)) =>
          (
            m - rid,
            for
              _ <- multiWarn
              _ <- logger.info(
                s"Chat-input passthrough: answering pending AskUser requestId=$rid root=$rootSessionId (${text.length} chars)"
              )
              _ <- p.reply match
                case InteractionReply.AskUserReply(Some(r)) =>
                  (r ! List(text)).void.handleErrorWith(_ => IO.unit)
                case _ => IO.unit
              // Close the card on the frontend: the input box went through this
              // path (not askUserAnswer), so the frontend needs the explicit
              // signal to mark the card answered.
              send <- rootWsSend.get
              _ <- send.get(rootSessionId).traverse_ { ws =>
                ws(
                  Json.obj(
                    "type" -> "askUserAnswered".asJson,
                    "sessionId" -> rootSessionId.asJson,
                    "requestId" -> rid.asJson,
                    "via" -> "chat-input".asJson
                  )
                ).handleErrorWith(_ => IO.unit)
              }
              _ <- reply.complete(true)
            yield ()
          )
        case None => (m, reply.complete(false).void)
    }.flatten

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
    sessionId: String
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
                ws(
                  Json.obj(
                    "type" -> "askUserClosed".asJson,
                    "sessionId" -> sid.asJson,
                    "requestId" -> rid.asJson,
                    "sourceSession" -> sessionId.asJson
                  )
                ).handleErrorWith(e =>
                  logger.warn(s"askUserClosed broadcast failed requestId=$rid root=$sid: ${e.getMessage}") *> IO.unit)
              }
            }
          yield ()
        )
    }.flatten

  private def complete(p: PendingRequest, ans: InteractionAnswered): IO[Unit] =
    val approved = ans.payload.hcursor.downField("approved").as[Boolean].toOption
    val answers = ans.payload.hcursor.downField("answers").as[List[String]].toOption
    for
      _ <- logger.info(
        s"InteractionAnswered requestId=${ans.requestId} kind=${p.kind} approved=$approved answers=${answers.map(_.size)}"
      )
      _ <- p.reply match
        case InteractionReply.PermissionReply(deferred) =>
          approved.fold(IO.unit)(a => deferred.complete(a).void.handleErrorWith(_ => IO.unit))
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

  /** 第六件 (2026-08-30): chat-input passthrough. The gateway detected a text
    * message sent from the input box while an AskUser card is pending for
    * `rootSessionId` — deliver `text` as the tool result (free-text answer).
    * `reply` resolves true when a pending card was consumed, false when the
    * gateway must fall back to the normal message-dispatch path. Queued
    * messages/external events are NEVER touched (they live in the agent's own
    * injection queue, not here) — only the newest user text flows through.
    */
  final case class AnswerViaChatInput(
      rootSessionId: String,
      text: String,
      reply: cats.effect.Deferred[IO, Boolean]
  ) extends InteractionHubCommand

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
    * its asker. */
  final case class CleanupForSession(sessionId: String) extends InteractionHubCommand
