package nebflow.core.tools

import cats.effect.{Deferred, IO}
import cats.syntax.all.*
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import nebflow.actor.{AgentCommand, RootAgentIdentity}
import nebflow.shared.{NebflowLogger, PathUtil}

import java.util.UUID
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** AskHost's first end-to-end runtime slice: an engine-generated correlation, an
  * ExternalEvent to the actual root actor, and a root-only correlated reply.
  * Ledger rows are append-only JSONL so each transition survives process restart.
  */
object AskHostLedger:
  private def file = PathUtil.dataRoot / "askhost" / "ledger.jsonl"
  private val waiters = TrieMap.empty[String, Deferred[IO, String]]
  private val answered = TrieMap.empty[String, String]
  private val journalLock = new Object

  /** Neutral outcome recorded when a wait window elapses without a decision
    * (author ruling 2026-11-19: "超时释放写入中性 outcome").
    * The release must never push a substantive default option onto the worker — only
    * the fact that no decision was made, so the worker can continue on its brief.
    */
  val TimeoutOutcome: String = "TIMEOUT/NO-DECISION"
  /** Journal status for a wait released by its window elapsing (distinct from
    * [[cancel]]'s `unavailable`, which means the request was never answerable).
    * A timed-out request keeps its identity so a late root answer can still be
    * delivered ([[AskHostReplyTool]] late-reply leg).
    */
  val StatusTimeout: String = "timeout"
  /** Journal status for a root answer injected AFTER its wait window was released. */
  val StatusLateReply: String = "late-reply"
  /** Journal status for a late answer that conflicts with one already delivered. */
  val StatusLateReplyConflict: String = "late-reply-conflict"

  final case class Pending(correlationId: String, workerSession: String, rootSession: String, sourceAgent: String, toolCallId: String = "")
  final case class Recovered(pending: Pending, question: String, answer: Option[String] = None)

  /** Read the append-only journal as state; later transitions supersede earlier ones. */
  def recover: IO[List[Recovered]] = IO.blocking(journalLock.synchronized(recoverSync()))

  private def recoverSync(): List[Recovered] =
    if !os.exists(file) then Nil
    else
      val requests = scala.collection.mutable.LinkedHashMap.empty[String, Recovered]
      os.read.lines(file).foreach { line =>
        io.circe.parser.parse(line).toOption.flatMap(_.asObject).foreach { row =>
          def str(key: String): String = row(key).flatMap(_.asString).getOrElse("")
          val id = str("correlationId")
          if id.nonEmpty then str("status") match
            case "pending" =>
              requests.update(id, Recovered(Pending(id, str("workerSession"), str("rootSession"), str("sourceAgent"), str("toolCallId")), str("question")))
            case "answered" => requests.get(id).foreach(r => requests.update(id, r.copy(answer = Some(str("decision")))))
            case "delivered" | "unavailable" | "timeout" => requests.remove(id)
            case _ => ()
        }
      }
      requests.values.toList

  /** Recreate in-process waiters and satisfy any request whose decision was journaled before a crash. */
  def restoreWaiter(id: String): IO[Deferred[IO, String]] =
    Deferred[IO, String].flatMap { candidate =>
      IO(waiters.putIfAbsent(id, candidate)).flatMap { existing =>
        val actual = existing.getOrElse(candidate)
        recover.map(_.find(_.pending.correlationId == id).flatMap(_.answer).orElse(answered.get(id)))
          .flatMap(_.traverse_(actual.complete)).as(actual)
      }
    }

  def clearForTests(): Unit = { waiters.clear(); answered.clear() }

  /** Terminal classification of one AskHost correlation (three-state distinction,
    * author ruling 2026-11-19): the reply leg must be able to tell "unknown id",
    * "released by timeout (neutral outcome, late reply still deliverable)" and
    * "explicitly answered with a decision" apart.
    */
  enum Terminal:
    /** Released when the wait window elapsed — neutral outcome, no decision made. */
    case TimedOut(outcome: String, reason: String)
    /** Never answerable (delivery failed / worker cancelled / legacy): nothing to answer. */
    case Unavailable(reason: String)
    /** Explicitly answered by the root. */
    case Answered(decision: String)
    /** Answered and its tool result already committed to the worker transcript. */
    case Delivered(decision: Option[String])

  /** Full durable lifecycle reading for one correlation id (raw journal scan — unlike
    * [[recover]] this keeps timed-out rows so their worker binding stays resolvable).
    */
  final case class RequestState(
    pending: Pending,
    question: String,
    terminal: Option[Terminal],
    /** Decisions already delivered as late replies (idempotency + conflict detection). */
    lateReplies: List[String]
  )

  def requestState(id: String): IO[Option[RequestState]] = IO.blocking {
    journalLock.synchronized(requestStateSync(id))
  }

  private def requestStateSync(id: String): Option[RequestState] =
    if !os.exists(file) then None
    else
      var pending: Option[Pending] = None
      var question = ""
      var decision: Option[String] = None
      var timeout: Option[(String, String)] = None
      var unavailable: Option[String] = None
      var delivered = false
      val late = scala.collection.mutable.ListBuffer.empty[String]
      os.read.lines(file).foreach { line =>
        io.circe.parser.parse(line).toOption.flatMap(_.asObject).foreach { row =>
          def str(key: String): String = row(key).flatMap(_.asString).getOrElse("")
          if str("correlationId") == id then str("status") match
            case "pending" =>
              pending = Some(Pending(id, str("workerSession"), str("rootSession"), str("sourceAgent"), str("toolCallId")))
              question = str("question")
            case "answered" => decision = Some(str("decision"))
            case "timeout" => timeout = Some((str("outcome"), str("reason")))
            case "unavailable" => unavailable = Some(str("reason"))
            case "delivered" => delivered = true
            case "late-reply" => late += str("decision")
            case _ => ()
        }
      }
      pending.map { p =>
        val terminal =
          if timeout.isDefined then Some(Terminal.TimedOut(timeout.get._1, timeout.get._2))
          else if unavailable.isDefined then Some(Terminal.Unavailable(unavailable.get))
          else if delivered then Some(Terminal.Delivered(decision))
          else decision.map(Terminal.Answered.apply)
        RequestState(p, question, terminal, late.toList)
      }

  def begin(workerSession: String, rootSession: String, sourceAgent: String, question: String): IO[(Pending, Deferred[IO, String])] =
    for
      id <- IO(UUID.randomUUID().toString)
      reply <- Deferred[IO, String]
      row = Pending(id, workerSession, rootSession, sourceAgent)
      _ <- append(Json.obj("correlationId" -> id.asJson, "workerSession" -> workerSession.asJson,
        "rootSession" -> rootSession.asJson, "sourceAgent" -> sourceAgent.asJson,
        "question" -> question.asJson, "status" -> "pending".asJson,
        "at" -> System.currentTimeMillis().asJson))
      _ <- IO(waiters.put(id, reply))
    yield (row, reply)

  def beginForCall(workerSession: String, rootSession: String, sourceAgent: String, question: String, toolCallId: String): IO[(Pending, Deferred[IO, String])] =
    require(toolCallId.nonEmpty, "AskHost requires a tool call id")
    IO.blocking {
      journalLock.synchronized {
        recoverSync().find(r => r.pending.workerSession == workerSession && r.pending.toolCallId == toolCallId) match
          case Some(row) =>
            require(row.pending.rootSession == rootSession && row.pending.sourceAgent == sourceAgent && row.question == question,
              "AskHost tool call identity conflicts with its persisted request")
            row.pending
          case None =>
            val row = Pending(UUID.randomUUID().toString, workerSession, rootSession, sourceAgent, toolCallId)
            appendSync(Json.obj("correlationId" -> row.correlationId.asJson, "workerSession" -> workerSession.asJson,
              "rootSession" -> rootSession.asJson, "sourceAgent" -> sourceAgent.asJson,
              "toolCallId" -> toolCallId.asJson, "question" -> question.asJson, "status" -> "pending".asJson,
              "at" -> System.currentTimeMillis().asJson))
            row
      }
    }.flatMap(row => restoreWaiter(row.correlationId).tupleLeft(row))

  /** Explicit cancellation competes with answer under the same journal lock.
    * A crash is not cancellation: its outstanding request remains recoverable.
    */
  def cancel(id: String, reason: String): IO[Boolean] = IO.blocking {
    journalLock.synchronized {
      recoverSync().find(_.pending.correlationId == id) match
        case Some(row) if row.answer.isEmpty =>
          appendSync(Json.obj("correlationId" -> id.asJson, "status" -> "unavailable".asJson,
            "reason" -> reason.asJson, "at" -> System.currentTimeMillis().asJson))
          waiters.remove(id)
          answered.remove(id)
          true
        case _ => false
    }
  }

  def markUnavailable(id: String, reason: String): IO[Unit] = cancel(id, reason).void

  /** Release a still-unanswered wait because its window elapsed. Writes the NEUTRAL
    * [[TimeoutOutcome]] (never a substantive default) under the same journal lock that
    * [[answer]] uses — so an answer that lands concurrently still wins, and this returns
    * `false` in that case (the caller re-reads the answer).
    *
    * The request identity and worker binding are preserved in the journal, so a late
    * root answer can still be routed to the worker ([[AskHostReplyTool]] late-reply leg).
    * This is deliberately NOT [[cancel]] (`unavailable`): a timed-out request was
    * answerable, just late.
    */
  def releaseOnTimeout(id: String, reason: String): IO[Boolean] = IO.blocking {
    journalLock.synchronized {
      recoverSync().find(_.pending.correlationId == id) match
        case Some(row) if row.answer.isEmpty =>
          appendSync(Json.obj("correlationId" -> id.asJson, "status" -> StatusTimeout.asJson,
            "outcome" -> TimeoutOutcome.asJson, "reason" -> reason.asJson,
            "at" -> System.currentTimeMillis().asJson))
          waiters.remove(id)
          answered.remove(id)
          true
        case _ => false
    }
  }

  /** Reject a late root answer that conflicts with one already delivered, and record the
    * conflict so it stays auditable (author ruling 2026-11-19: "真冲突必须留痕可查").
    */
  def recordLateReplyConflict(id: String, existing: String, attempted: String): IO[Unit] =
    append(Json.obj("correlationId" -> id.asJson, "status" -> StatusLateReplyConflict.asJson,
      "existingDecision" -> existing.asJson, "attemptedDecision" -> attempted.asJson,
      "at" -> System.currentTimeMillis().asJson))

  /** Audit a late root answer actually delivered to the worker (injected / continuation). */
  def recordLateReply(id: String, decision: String, delivery: String): IO[Unit] =
    append(Json.obj("correlationId" -> id.asJson, "status" -> StatusLateReply.asJson,
      "decision" -> decision.asJson, "delivery" -> delivery.asJson,
      "at" -> System.currentTimeMillis().asJson))

  def cancelWorker(worker: String, reason: String): IO[Unit] =
    recover.flatMap(_.filter(_.pending.workerSession == worker).traverse_(r => cancel(r.pending.correlationId, reason).void))

  /** Legacy unanswered rows have no original tool identity; never guess by text. */
  def expireLegacy(): IO[Unit] =
    recover.flatMap(_.filter(r => r.pending.toolCallId.isEmpty && r.answer.isEmpty)
      .traverse_(r => cancel(r.pending.correlationId, "legacy request has no recoverable tool call identity").void))

  /** Boot replay: durable mailbox identity makes repeated runs safe. */
  def recoverPending(resources: nebflow.core.AgentRuntimePort): IO[Int] =
    expireLegacy() *> recover.flatMap(_.traverse { row =>
      resources.agentRegistry.get.flatMap { registry =>
        val event = AgentCommand.ExternalEvent("askhost", "request",
          s"[AskHost request ${row.pending.correlationId} from ${row.pending.sourceAgent} / ${row.pending.workerSession}]\n${row.question}",
          JsonObject("correlationId" -> row.pending.correlationId.asJson,
            "workerSession" -> row.pending.workerSession.asJson, "rootSession" -> row.pending.rootSession.asJson,
            "sourceAgent" -> row.pending.sourceAgent.asJson, "engineOwned" -> true.asJson), Some(row.pending.correlationId))
        row.answer.traverse_(answer => IO(answered.put(row.pending.correlationId, answer))) *>
            restoreWaiter(row.pending.correlationId).void *>
            (if row.answer.nonEmpty then IO.unit
             else nebflow.core.mail.SessionMailbox.mailOnce(row.pending.rootSession, event, "askhost-request",
               row.pending.correlationId, coalesce = false, producer = "askhost-recovery", deduplicateReceipts = true).flatMap {
                 case nebflow.core.mail.SessionMailbox.MailResult.Enqueued(_) =>
                   registry.get(row.pending.rootSession).traverse_(r => r.ref ! AgentCommand.MailboxArrived)
                 case nebflow.core.mail.SessionMailbox.MailResult.AlreadyPresent =>
                   registry.get(row.pending.rootSession).traverse_(r => r.ref ! AgentCommand.MailboxArrived)
                 case nebflow.core.mail.SessionMailbox.MailResult.Coalesced(_) => IO.unit
                 case other => IO.raiseError(new IllegalStateException(s"AskHost replay failed: $other"))
               }) *> IO.pure(true)
      }
    }).map(_.count(identity))

  def answer(correlationId: String, answer: String): IO[Boolean] =
    IO.blocking {
      journalLock.synchronized {
        val rows = recoverSync()
        rows.find(_.pending.correlationId == correlationId) match
          case Some(row) if row.answer.isEmpty =>
            appendSync(Json.obj("correlationId" -> correlationId.asJson, "decision" -> answer.asJson,
              "status" -> "answered".asJson, "at" -> System.currentTimeMillis().asJson))
            answered.put(correlationId, answer)
            true
          case _ => false
      }
    }.flatTap(accepted => if accepted then waiters.get(correlationId).traverse_(_.complete(answer).void) else IO.unit)

  /** Close only after the transcript containing the original tool result is durable.
    * Legacy rows without tool IDs stay unresolved rather than being matched by text.
    * Cancellation must be distinguished from a crash before invalidating a request.
    */
  def closeCommittedResults(worker: String, messages: List[nebflow.shared.Message]): IO[Unit] =
    val results = messages.collect { case nebflow.shared.Message(nebflow.shared.MessageRole.User, Right(blocks), _, _, _, _) =>
      blocks.collect { case r: nebflow.shared.ContentBlock.ToolResult => r }
    }.flatten
    recover.flatMap(_.filter(r => r.pending.workerSession == worker && r.pending.toolCallId.nonEmpty &&
      r.answer.exists(answer => results.exists(t => t.toolUseId == r.pending.toolCallId &&
        t.content == answer && !t.isError.contains(true)))).traverse_(r => markDelivered(r.pending.correlationId)))

  def markDelivered(correlationId: String): IO[Unit] =
    append(Json.obj("correlationId" -> correlationId.asJson, "status" -> "delivered".asJson,
      "at" -> System.currentTimeMillis().asJson)) *> IO { waiters.remove(correlationId); answered.remove(correlationId); () }

  private def append(json: Json): IO[Unit] = IO.blocking(journalLock.synchronized(appendSync(json)))

  private def appendSync(json: Json): Unit =
    os.makeDir.all(file / os.up)
    val line = json.noSpaces + "\n"
    val out = new java.io.FileOutputStream(file.toIO, true)
    try
      out.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      out.getFD.sync()
    finally out.close()
end AskHostLedger

object AskHostTool extends Tool:
  val name = "AskHost"
  val description = "Ask the trusted Nebula root for a decision. The root can answer or reject; use AskUserQuestion there if clarification is needed. This blocks until its correlated reply arrives."

  /** Wait window before the blocking hold is released neutrally (author ruling 2026-11-19 ②).
    *
    * Recommended 30 min: a root turn is a full reasoning+tool round and can legitimately
    * exceed the old 10-minute value (forensics 2026-11-19: two real requests timed out at
    * 600s while the root's answers arrived 10-26 min later). This is an upper bound, never
    * an infinite wait — the release is neutral ([[AskHostLedger.TimeoutOutcome]]) and a late
    * answer is still delivered ([[AskHostReplyTool]] late-reply leg).
    *
    * Configurable via `-D nebflow.askhost.waitWindowMs=<ms>` (>0; non-numeric/<=0 falls back
    * to the default), same `sys.props` precedent as `SessionMailbox.maxPending`.
    */
  val DefaultWaitWindowMs: Long = 30L * 60L * 1000L
  def waitWindowMs: Long =
    sys.props.get("nebflow.askhost.waitWindowMs").map(_.trim).flatMap(_.toLongOption)
      .filter(_ > 0).getOrElse(DefaultWaitWindowMs)
  def waitWindow: FiniteDuration = waitWindowMs.millis

  /** Neutral, actionable notice returned when the window elapses with no decision. */
  private[nebflow] def timeoutReleaseNotice(id: String): String =
    s"""AskHost request $id got NO decision within the wait window; the blocking hold was released neutrally (outcome ${AskHostLedger.TimeoutOutcome}).
       |No option was chosen on your behalf. Continue per your brief's stated default and label it truthfully in your result (e.g. "no decision received from the root; proceeding by the brief's default"), or escalate explicitly if the brief names no default.
       |If the root answers after this point, it will arrive as an injected message tagged [ASKHOST-LATE-REPLY] and is authoritative then.""".stripMargin
  val inputSchema = JsonObject(
    "type" -> "object".asJson,
    "properties" -> Json.obj("question" -> Json.obj("type" -> "string".asJson)),
    "required" -> Json.arr("question".asJson)
  )
  def summarize(input: JsonObject): String = s"AskHost(${input("question").flatMap(_.asString).getOrElse("").take(60)})"
  def summarizeResult(input: JsonObject, result: String): String = result.take(200)
  private[nebflow] def resolveRoot(bound: String, registry: Map[String, nebflow.actor.AgentRecord]): Either[String, String] =
    def loop(id: String, seen: Set[String]): Either[String, String] =
      if seen(id) then Left(s"session ancestry cycle at $id")
      else registry.get(id) match
        case None => Left(s"session $id unavailable while resolving root")
        case Some(record) if record.kind == nebflow.actor.AgentKind.Root && record.rootSessionId == id => Right(id)
        case Some(record) =>
          val next = Option(record.rootSessionId).filter(s => s.nonEmpty && s != id)
            .orElse(Option(record.parentSessionId).filter(s => s.nonEmpty && s != id))
          next.fold[Either[String, String]](Left(s"no root ancestry for session $id"))(loop(_, seen + id))
    loop(bound, Set.empty)

  private[nebflow] def awaitReply(id: String, waiter: Deferred[IO, String], timeout: FiniteDuration): IO[Either[ToolError, String]] =
    waiter.get.map(Right(_)).timeoutTo(timeout,
      AskHostLedger.releaseOnTimeout(id, "root reply timed out").flatMap {
        // Released by timeout: unblock the worker with the NEUTRAL outcome (no substantive
        // default) — the request identity survives so a late answer can still be delivered.
        case true => IO.pure(Right(timeoutReleaseNotice(id)))
        // The answer won the race against the release — return it as the tool result.
        case false => AskHostLedger.recover.map(_.find(_.pending.correlationId == id).flatMap(_.answer) match
          case Some(answer) => Right(answer)
          case None => Left(ToolError(s"AskHost request $id is no longer available")))
      })

  private[nebflow] def trustedFallback(resources: nebflow.core.AgentRuntimePort, registry: Map[String, nebflow.actor.AgentRecord]): IO[Option[String]] =
    registry.values.toList.filter(r => r.kind == nebflow.actor.AgentKind.Root && r.rootSessionId == r.sessionId)
      .traverse(r => resources.sessionStore.getSessionMeta(r.sessionId).map(_.filter(m =>
        m.agentName.getOrElse(RootAgentIdentity.Name) == RootAgentIdentity.Name).map(_ => r.sessionId)))
      .map(_.flatten match
        case single :: Nil => Some(single)
        case _ => None)

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val question = input("question").flatMap(_.asString).map(_.trim).getOrElse("")
    if question.isEmpty then IO.pure(Left(ToolError("AskHost: question must be non-empty")))
    else (ctx.sessionId, ctx.rootSessionId, ctx.agentDef, ctx.sharedResources) match
      case (Some(worker), Some(boundRoot), Some(agent), Some(resources)) if worker != boundRoot =>
        resources.agentRegistry.get.flatMap { registry =>
          val resolved = resolveRoot(boundRoot, registry)
          val destination = resolved match
            case Right(root) => IO.pure(Some(root))
            case Left(_) => trustedFallback(resources, registry)
          destination.flatMap {
          case None => IO.pure(Left(ToolError(s"AskHost: root session unavailable: ${resolved.swap.toOption.getOrElse("ambiguous trusted root")}")))
          case Some(root) =>
            val rootRecord = registry(root)
            AskHostLedger.beginForCall(worker, root, agent.name, question, ctx.toolCallId).flatMap { case (request, _) =>
              val payload = s"[AskHost request ${request.correlationId} from ${request.sourceAgent} / ${request.workerSession}]\n$question"
              val metadata = JsonObject("correlationId" -> request.correlationId.asJson,
                "workerSession" -> worker.asJson, "rootSession" -> root.asJson,
                "sourceAgent" -> agent.name.asJson, "engineOwned" -> true.asJson)
              val event = AgentCommand.ExternalEvent("askhost", "request", payload, metadata, Some(request.correlationId))
              val durable = nebflow.core.mail.SessionMailbox.mailOnce(root, event, "askhost-request", request.correlationId,
                coalesce = false, producer = "askhost", deduplicateReceipts = true)
              durable.flatMap {
                case nebflow.core.mail.SessionMailbox.MailResult.Enqueued(_) | nebflow.core.mail.SessionMailbox.MailResult.AlreadyPresent =>
                  (rootRecord.ref ! AgentCommand.MailboxArrived) *> AskHostLedger.restoreWaiter(request.correlationId).flatMap { waiter =>
                    awaitReply(request.correlationId, waiter, waitWindow)
                  }
                case other => AskHostLedger.markUnavailable(request.correlationId, s"delivery failed: $other") *>
                  IO.pure(Left(ToolError(s"AskHost root delivery failed for request ${request.correlationId}: $other")))
              }.handleErrorWith { error =>
                AskHostLedger.markUnavailable(request.correlationId, s"delivery failed: ${error.getMessage}") *>
                  IO.pure(Left(ToolError(s"AskHost request ${request.correlationId} failed: ${error.getMessage}")))
              }
            }
          }
        }
      case _ => IO.pure(Left(ToolError("AskHost is only available to a worker with an engine-bound root session")))
end AskHostTool

/** Root-side clarification uses the established user-card lifecycle; its answer bridge injects
  * the user's answer into this same root session, where the model can then call AskHostReply.
  */
object AskHostClarifyTool extends Tool:
  val name = "AskHostClarify"
  val description = "Ask the user a blocking clarification for a pending AskHost correlation. The answer returns as a normal user message to this root session; then call AskHostReply with the same correlationId. This never sends the user card to the worker. A correlation that already timed out is still clarifiable — its answer becomes a late reply."
  val inputSchema = JsonObject("type" -> "object".asJson,
    "properties" -> Json.obj("correlationId" -> Json.obj("type" -> "string".asJson),
      "questions" -> AskUserQuestionTool.inputSchema("properties").flatMap(_.hcursor.downField("questions").focus).getOrElse(Json.arr())),
    "required" -> Json.arr("correlationId".asJson, "questions".asJson))
  def summarize(input: JsonObject): String = "AskHostClarify"
  def summarizeResult(input: JsonObject, result: String): String = result

  /** Admission judgement (single point, pure — same [[AskHostLedger.requestState]] evidence
    * as [[AskHostReplyTool]]). Returns an optional late-reply note to append to the ack.
    *
    * The defect (2026-11-19): the old judgement keyed off [[AskHostLedger.recover]], which
    * drops timed-out rows — so the root could no longer clarify exactly the requests it most
    * needed to (one that had just been neutrally released). A released request is still
    * answerable, so it stays clarifiable, with the late-reply contract spelled out.
    */
  private[nebflow] def admission(state: AskHostLedger.RequestState): Either[ToolError, Option[String]] =
    state.terminal match
      case None => Right(None)
      case Some(AskHostLedger.Terminal.TimedOut(outcome, _)) =>
        Right(Some(
          s"[ASKHOST-LATE-REPLY] AskHost request ${state.pending.correlationId} was already released when its wait window elapsed (neutral outcome $outcome) — the worker is no longer blocked and chose nothing on your behalf. Your clarification still reaches it: answer with AskHostReply on this same correlationId and it is delivered as a late reply."))
      case Some(AskHostLedger.Terminal.Answered(_)) | Some(AskHostLedger.Terminal.Delivered(_)) =>
        Left(ToolError(
          s"AskHostClarify: request ${state.pending.correlationId} was already answered — no clarification is needed. (ASKHOST_CLARIFY_ALREADY_ANSWERED)"))
      case Some(AskHostLedger.Terminal.Unavailable(reason)) =>
        Left(ToolError(
          s"AskHostClarify: request ${state.pending.correlationId} was released WITHOUT ever holding an answer ($reason) — there is nothing to clarify. (ASKHOST_CLARIFY_RELEASED_UNANSWERABLE)"))

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val id = input("correlationId").flatMap(_.asString).getOrElse("").trim
    if !ctx.isRootAgent then IO.pure(Left(ToolError("AskHostClarify: root identity required")))
    else if id.isEmpty then IO.pure(Left(ToolError("AskHostClarify: correlationId is required")))
    else AskHostLedger.requestState(id).flatMap {
      case None =>
        IO.pure(Left(ToolError(
          s"AskHostClarify: unknown correlationId '$id' — no AskHost request with this id exists. (ASKHOST_CLARIFY_UNKNOWN_ID)")))
      case Some(state) =>
        admission(state) match
          case Left(err) => IO.pure(Left(err))
          case Right(note) =>
            AskUserQuestionTool.call(JsonObject.fromIterable(input.toList.filterNot(_._1 == "correlationId")), ctx)
              .map(_.map(text => note.fold(text)(n => s"$text\n\n$n")))
    }
end AskHostClarifyTool

object AskHostReplyTool extends Tool:
  val name = "AskHostReply"
  val description = "Answer or reject an AskHost request by its correlationId. Root session only. If the request already timed out, the answer is still delivered to the worker as a late reply tagged [ASKHOST-LATE-REPLY]."
  val inputSchema = JsonObject("type" -> "object".asJson,
    "properties" -> Json.obj("correlationId" -> Json.obj("type" -> "string".asJson),
      "decision" -> Json.obj("type" -> "string".asJson)),
    "required" -> Json.arr("correlationId".asJson, "decision".asJson))
  def summarize(input: JsonObject): String = "AskHostReply"
  def summarizeResult(input: JsonObject, result: String): String = result

  /** Explicit-conflict refusal (the ONLY case that legitimately rejects a reply). */
  private def conflict(id: String, existing: String, attempted: String): ToolError =
    ToolError(
      s"AskHostReply: request $id was ALREADY answered with a DIFFERENT decision — refusing to overwrite (ASKHOST_REPLY_CONFLICT). " +
        s"Existing: \"${existing.take(200)}\"; attempted: \"${attempted.take(200)}\". The existing decision stands; the conflict is audited.")

  private def lateReplyPayload(p: AskHostLedger.Pending, id: String, outcome: String, decision: String): String =
    s"""[ASKHOST-LATE-REPLY] AskHost request $id (from ${p.sourceAgent} / ${p.workerSession}) was released when its wait window elapsed; the outcome recorded at release was the neutral $outcome — no option had been chosen for you.
       |The trusted root has now answered AFTER the window. This decision is authoritative: apply it now and label the result truthfully (late reply; the earlier neutral release is superseded).""".stripMargin +
      s"\n\n$decision"

  /**
   * Late-reply delivery (author ruling 2026-11-19 ②): a root answer for a request whose
   * wait window already elapsed is not rejected — it is injected into the worker with the
   * SAME Mail semantics as the rest of Stand:
   *
   *   - live worker ⇒ durable mailbox enqueue + doorbell (injected at its next turn boundary);
   *   - finished delegate worker ⇒ in-place continuation (same session id, new round) —
   *     the Mail "finished ⇒ new round" rule, reusing the single point
   *     [[nebflow.core.delegate.SessionResumption.resumeInPlace]];
   *   - manual-gated / external / no-record worker ⇒ durable mailbox enqueue (never lost;
   *     read when the session next runs a round).
   *
   * Idempotent: the mailbox identity is stable (`askhost-late-reply-<id>`), so a repeated
   * send cannot duplicate the injection ([[nebflow.core.mail.SessionMailbox]] dedup).
   */
  private def deliverLateReply(
    resources: nebflow.core.AgentRuntimePort,
    ctx: ToolContext,
    p: AskHostLedger.Pending,
    id: String,
    outcome: String,
    decision: String
  ): IO[Either[ToolError, String]] =
    val worker = p.workerSession
    val payload = lateReplyPayload(p, id, outcome, decision)
    val identity = s"askhost-late-reply-$id"
    val metadata = JsonObject(
      "correlationId" -> id.asJson,
      "workerSession" -> worker.asJson,
      "rootSession" -> p.rootSession.asJson,
      "sourceAgent" -> p.sourceAgent.asJson,
      "originalOutcome" -> outcome.asJson,
      "lateReply" -> true.asJson,
      "engineOwned" -> true.asJson
    )
    val event = AgentCommand.ExternalEvent("askhost", "late-reply", payload, metadata, Some(id))
    def enqueue: IO[nebflow.core.mail.SessionMailbox.MailResult] =
      nebflow.core.mail.SessionMailbox.mailOnce(worker, event, "askhost-late-reply", identity,
        coalesce = false, producer = "askhost-late-reply", deduplicateReceipts = true)
    resources.agentRegistry.get.flatMap { registry =>
      val live = registry.get(worker)
      val doorbell: IO[Unit] = live.traverse_(rec => (rec.ref ! AgentCommand.MailboxArrived))
      def persist(text: String, delivery: String): IO[Either[ToolError, String]] =
        enqueue.flatMap {
          case nebflow.core.mail.SessionMailbox.MailResult.Failed(cause) =>
            IO.pure(Left(ToolError(
              s"AskHost late reply for $id could NOT be persisted ($cause) — nothing was delivered; retry. (ASKHOST_LATE_REPLY_NOT_PERSISTED)")))
          case _ => AskHostLedger.recordLateReply(id, decision, delivery) *> doorbell.as(Right(text))
        }
      live match
        case Some(_) =>
          persist(
            s"[ASKHOST-LATE-REPLY] Late reply for $id queued in the worker mailbox ($worker, live) and the session woken; it is injected at its next turn boundary.",
            "mailbox-live")
        case None =>
          IO.blocking(nebflow.core.delegate.DelegateRegistry.find(worker)).flatMap {
            case Some(record) if record.executor == nebflow.core.executor.ExecutorRegistry.DefaultId =>
              resources.subAgentTaskStore.findByTaskId(worker).flatMap { task =>
                if nebflow.core.delegate.SessionResumption.requiresManual(Some(record), task, None) then
                  persist(
                    s"[ASKHOST-LATE-REPLY] Late reply for $id queued in the worker mailbox; execution of '$worker' is held behind a manual recovery gate (MANUAL_RECOVERY_REQUIRED) — it is read when execution resumes.",
                    "mailbox-held")
                else
                  nebflow.core.delegate.SessionResumption
                    .resumeInPlace(
                      nebflow.core.delegate.SessionResumption.Deps(resources, resources.actorSystem, ctx.wsSend),
                      record,
                      payload)
                    .flatMap {
                      case Right(_) =>
                        AskHostLedger.recordLateReply(id, decision, "continuation").as(Right(
                          s"[ASKHOST-LATE-REPLY] Late reply for $id delivered by resuming the finished worker '$worker' in place (same session id, new round)."))
                      case Left(err) =>
                        persist(
                          s"[ASKHOST-LATE-REPLY] Late reply for $id queued in the worker mailbox (in-place continuation declined: $err) — it is read when '$worker' next runs a round.",
                          "mailbox-continuation-declined")
                    }
              }
            case _ =>
              persist(
                s"[ASKHOST-LATE-REPLY] Late reply for $id queued in the worker mailbox ($worker); the session is not live, so it is read when '$worker' next runs a round.",
                "mailbox-queued")
          }
    }

  /** Classify a known request and act: answer while pending, distinguish the terminal
    * cases (neutral timeout release vs explicit decision conflict). */
  private def classify(
    resources: nebflow.core.AgentRuntimePort,
    ctx: ToolContext,
    id: String,
    decision: String,
    state: AskHostLedger.RequestState
  ): IO[Either[ToolError, String]] =
    state.terminal match
      case None =>
        AskHostLedger.answer(id, decision).flatMap {
          case true => IO.pure(Right(s"AskHost reply durably recorded for $id"))
          case false =>
            // Raced with a concurrent terminal transition: re-read and classify once more.
            AskHostLedger.requestState(id).flatMap {
              case Some(next) if next.terminal.isDefined => classify(resources, ctx, id, decision, next)
              case _ => IO.pure(Left(ToolError(
                s"AskHostReply: request $id could not be answered and is no longer pending — retry to re-read its state (ASKHOST_REPLY_RACED)")))
            }
        }
      case Some(AskHostLedger.Terminal.Answered(existing)) =>
        if existing == decision then IO.pure(Right(s"AskHost reply already accepted for $id"))
        else IO.pure(Left(conflict(id, existing, decision)))
      case Some(AskHostLedger.Terminal.Delivered(existing)) =>
        if existing.contains(decision) then IO.pure(Right(s"AskHost reply already accepted and delivered for $id"))
        else IO.pure(Left(conflict(id, existing.getOrElse("(unknown)"), decision)))
      case Some(AskHostLedger.Terminal.TimedOut(outcome, _)) =>
        if state.lateReplies.contains(decision) then
          IO.pure(Right(s"[ASKHOST-LATE-REPLY] Late reply for $id was already delivered (idempotent re-send); nothing changed."))
        else if state.lateReplies.nonEmpty then
          AskHostLedger.recordLateReplyConflict(id, state.lateReplies.last, decision) *>
            IO.pure(Left(conflict(id, state.lateReplies.last, decision)))
        else deliverLateReply(resources, ctx, state.pending, id, outcome, decision)
      case Some(AskHostLedger.Terminal.Unavailable(reason)) =>
        IO.pure(Left(ToolError(
          s"AskHostReply: request $id was released WITHOUT ever holding an answer (${reason}); the worker did not wait for a decision, so there is nothing to answer. (ASKHOST_REPLY_RELEASED_UNANSWERABLE)")))

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val id = input("correlationId").flatMap(_.asString).getOrElse("").trim
    val decision = input("decision").flatMap(_.asString).getOrElse("").trim
    if !ctx.isRootAgent then IO.pure(Left(ToolError("AskHostReply: root identity required")))
    else if id.isEmpty || decision.isEmpty then IO.pure(Left(ToolError("AskHostReply: correlationId and decision are required")))
    else ctx.sharedResources match
      case None => IO.pure(Left(ToolError("AskHostReply: runtime resources unavailable")))
      case Some(resources) =>
        val root = ctx.sessionId.getOrElse("")
        AskHostLedger.requestState(id).flatMap {
          case None =>
            IO.pure(Left(ToolError(
              s"AskHostReply: unknown correlationId '$id' — no AskHost request with this id exists (never created, or mistyped). Nothing was recorded. (ASKHOST_REPLY_UNKNOWN_ID)")))
          case Some(state) if state.pending.rootSession != root =>
            IO.pure(Left(ToolError("AskHostReply: request belongs to another root session")))
          case Some(state) =>
            classify(resources, ctx, id, decision, state)
        }
end AskHostReplyTool
