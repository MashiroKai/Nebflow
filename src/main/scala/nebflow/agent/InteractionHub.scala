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
 * and the requesting agents fall back to their 5-minute permission timeout
 * (AskUser replyTo is dropped — bounded by the actor system restart).
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
          // card in a graveyard no one watches — invisible = unanswerable =
          // guaranteed 5-minute timeout denial. Fan the card out to ALL other
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
              // would strand the pending deferred on its 5-minute timeout with
              // the card gone (user cannot re-answer what they cannot see).
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
