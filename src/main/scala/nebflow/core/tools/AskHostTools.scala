package nebflow.core.tools

import cats.effect.{Deferred, IO}
import cats.syntax.all.*
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import nebflow.actor.{AgentCommand, RootAgentIdentity}
import nebflow.shared.PathUtil

import java.util.UUID
import scala.collection.concurrent.TrieMap
import scala.util.control.NonFatal

/** AskHost's first end-to-end runtime slice: an engine-generated correlation, an
  * ExternalEvent to the actual root actor, and a root-only correlated reply.
  * Ledger rows are append-only JSONL so each transition survives process restart.
  */
object AskHostLedger:
  private def file = PathUtil.dataRoot / "askhost" / "ledger.jsonl"
  private val waiters = TrieMap.empty[String, Deferred[IO, String]]
  private val answered = TrieMap.empty[String, String]

  final case class Pending(correlationId: String, workerSession: String, rootSession: String, sourceAgent: String)
  final case class Recovered(pending: Pending, question: String, answer: Option[String] = None)

  /** Read the append-only journal as state; later transitions supersede earlier ones. */
  def recover: IO[List[Recovered]] = IO.blocking {
    if !os.exists(file) then Nil
    else
      val requests = scala.collection.mutable.LinkedHashMap.empty[String, Recovered]
      os.read.lines(file).foreach { line =>
        io.circe.parser.parse(line).toOption.flatMap(_.asObject).foreach { row =>
          def str(key: String): String = row(key).flatMap(_.asString).getOrElse("")
          val id = str("correlationId")
          if id.nonEmpty then str("status") match
            case "pending" =>
              requests.update(id, Recovered(Pending(id, str("workerSession"), str("rootSession"), str("sourceAgent")), str("question")))
            case "answered" => requests.get(id).foreach(r => requests.update(id, r.copy(answer = Some(str("decision")))))
            case "delivered" | "unavailable" => requests.remove(id)
            case _ => ()
        }
      }
      requests.values.toList
  }

  /** Recreate in-process waiters and satisfy any request whose decision was journaled before a crash. */
  def restoreWaiter(id: String): IO[Deferred[IO, String]] =
    Deferred[IO, String].flatMap { candidate =>
      IO(waiters.putIfAbsent(id, candidate)).flatMap { existing =>
        val actual = existing.getOrElse(candidate)
        answered.get(id).traverse_(actual.complete).as(actual)
      }
    }

  def clearForTests(): Unit = { waiters.clear(); answered.clear() }

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

  def markUnavailable(id: String, reason: String): IO[Unit] =
    append(Json.obj("correlationId" -> id.asJson, "status" -> "unavailable".asJson,
      "reason" -> reason.asJson, "at" -> System.currentTimeMillis().asJson)) *>
      IO { waiters.remove(id); answered.remove(id); () }

  /** Boot replay: durable mailbox identity makes repeated runs safe. */
  def recoverPending(resources: nebflow.core.AgentRuntimePort): IO[Int] =
    recover.flatMap(_.traverse { row =>
      resources.agentRegistry.get.flatMap { registry =>
        val event = AgentCommand.ExternalEvent("askhost", "request",
          s"[AskHost request ${row.pending.correlationId} from ${row.pending.sourceAgent} / ${row.pending.workerSession}]\n${row.question}",
          JsonObject("correlationId" -> row.pending.correlationId.asJson,
            "workerSession" -> row.pending.workerSession.asJson, "rootSession" -> row.pending.rootSession.asJson,
            "sourceAgent" -> row.pending.sourceAgent.asJson, "engineOwned" -> true.asJson), Some(row.pending.correlationId))
        val rootAlive = registry.contains(row.pending.rootSession)
        val workerLive = registry.contains(row.pending.workerSession)
        if !workerLive then markUnavailable(row.pending.correlationId, "worker session unavailable on boot; original waiter cannot be resumed").as(false)
        else
          row.answer.traverse_(answer => IO(answered.put(row.pending.correlationId, answer))) *>
            restoreWaiter(row.pending.correlationId).void *>
            (if !rootAlive && row.answer.isEmpty then markUnavailable(row.pending.correlationId, "root session unavailable on boot")
             else if row.answer.nonEmpty then IO.unit
             else nebflow.core.mail.SessionMailbox.mailOnce(row.pending.rootSession, event, "askhost-request",
               row.pending.correlationId, coalesce = false, producer = "askhost-recovery").flatMap {
                 case nebflow.core.mail.SessionMailbox.MailResult.Enqueued(_) => IO.unit
                 case nebflow.core.mail.SessionMailbox.MailResult.AlreadyPresent => IO.unit
                 case nebflow.core.mail.SessionMailbox.MailResult.Coalesced(_) => IO.unit
                 case other => markUnavailable(row.pending.correlationId, s"replay failed: $other")
               }) *> IO.pure(true)
      }
    }).map(_.count(identity))

  def answer(correlationId: String, answer: String): IO[Boolean] =
    waiters.get(correlationId) match
      case None => IO.pure(false)
      case Some(reply) =>
        IO(answered.putIfAbsent(correlationId, answer)).flatMap {
          case Some(existing) => IO.pure(existing == answer)
          case None =>
            append(Json.obj("correlationId" -> correlationId.asJson, "decision" -> answer.asJson,
              "status" -> "answered".asJson, "at" -> System.currentTimeMillis().asJson)) *>
              reply.complete(answer)
        }

  def markDelivered(correlationId: String): IO[Unit] =
    append(Json.obj("correlationId" -> correlationId.asJson, "status" -> "delivered".asJson,
      "at" -> System.currentTimeMillis().asJson)) *> IO { waiters.remove(correlationId); answered.remove(correlationId); () }

  private def append(json: Json): IO[Unit] = IO.blocking {
    os.makeDir.all(file / os.up)
    val line = json.noSpaces + "\n"
    val out = new java.io.FileOutputStream(file.toIO, true)
    try
      out.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      out.getFD.sync()
    finally out.close()
  }
end AskHostLedger

object AskHostTool extends Tool:
  val name = "AskHost"
  val description = "Ask the trusted Nebula root for a decision. The root can answer or reject; use AskUserQuestion there if clarification is needed. This blocks until its correlated reply arrives."
  val inputSchema = JsonObject(
    "type" -> "object".asJson,
    "properties" -> Json.obj("question" -> Json.obj("type" -> "string".asJson)),
    "required" -> Json.arr("question".asJson)
  )
  def summarize(input: JsonObject): String = s"AskHost(${input("question").flatMap(_.asString).getOrElse("").take(60)})"
  def summarizeResult(input: JsonObject, result: String): String = result.take(200)
  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val question = input("question").flatMap(_.asString).map(_.trim).getOrElse("")
    if question.isEmpty then IO.pure(Left(ToolError("AskHost: question must be non-empty")))
    else (ctx.sessionId, ctx.rootSessionId, ctx.agentDef, ctx.sharedResources) match
      case (Some(worker), Some(root), Some(agent), Some(resources)) if worker != root =>
        resources.agentRegistry.get.flatMap(_.get(root) match
          case None => IO.pure(Left(ToolError("AskHost: root session unavailable")))
          case Some(rootRecord) =>
            AskHostLedger.begin(worker, root, agent.name, question).flatMap { case (request, _) =>
              val payload = s"[AskHost request ${request.correlationId} from ${request.sourceAgent} / ${request.workerSession}]\n$question"
              val metadata = JsonObject("correlationId" -> request.correlationId.asJson,
                "workerSession" -> worker.asJson, "rootSession" -> root.asJson,
                "sourceAgent" -> agent.name.asJson, "engineOwned" -> true.asJson)
              val event = AgentCommand.ExternalEvent("askhost", "request", payload, metadata, Some(request.correlationId))
              val durable = nebflow.core.mail.SessionMailbox.mailOnce(root, event, "askhost-request", request.correlationId,
                coalesce = false, producer = "askhost")
              durable.flatMap {
                case nebflow.core.mail.SessionMailbox.MailResult.Enqueued(_) | nebflow.core.mail.SessionMailbox.MailResult.AlreadyPresent =>
                  (rootRecord.ref ! AgentCommand.MailboxArrived) *> AskHostLedger.restoreWaiter(request.correlationId).flatMap(_.get.flatTap(_ => AskHostLedger.markDelivered(request.correlationId)).map(Right(_)))
                case other => AskHostLedger.markUnavailable(request.correlationId, s"root request delivery failed: $other")
                  .as(Left(ToolError(s"AskHost root delivery unavailable: $other")))
              }
            }
        )
      case _ => IO.pure(Left(ToolError("AskHost is only available to a worker with an engine-bound root session")))
end AskHostTool

/** Root-side clarification uses the established user-card lifecycle; its answer bridge injects
  * the user's answer into this same root session, where the model can then call AskHostReply.
  */
object AskHostClarifyTool extends Tool:
  val name = "AskHostClarify"
  val description = "Ask the user a blocking clarification for a pending AskHost correlation. The answer returns as a normal user message to this root session; then call AskHostReply with the same correlationId. This never sends the user card to the worker."
  val inputSchema = JsonObject("type" -> "object".asJson,
    "properties" -> Json.obj("correlationId" -> Json.obj("type" -> "string".asJson),
      "questions" -> AskUserQuestionTool.inputSchema("properties").flatMap(_.hcursor.downField("questions").focus).getOrElse(Json.arr())),
    "required" -> Json.arr("correlationId".asJson, "questions".asJson))
  def summarize(input: JsonObject): String = "AskHostClarify"
  def summarizeResult(input: JsonObject, result: String): String = result
  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val id = input("correlationId").flatMap(_.asString).getOrElse("").trim
    if !ctx.isRootAgent then IO.pure(Left(ToolError("AskHostClarify: root identity required")))
    else if id.isEmpty then IO.pure(Left(ToolError("AskHostClarify: correlationId is required")))
    else AskHostLedger.recover.map(_.exists(_.pending.correlationId == id)).flatMap {
      case false => IO.pure(Left(ToolError(s"AskHostClarify: pending correlation unavailable: $id")))
      case true => AskUserQuestionTool.call(JsonObject.fromIterable(input.toList.filterNot(_._1 == "correlationId")), ctx)
    }
end AskHostClarifyTool

object AskHostReplyTool extends Tool:
  val name = "AskHostReply"
  val description = "Answer or reject an AskHost request by its correlationId. Root session only."
  val inputSchema = JsonObject("type" -> "object".asJson,
    "properties" -> Json.obj("correlationId" -> Json.obj("type" -> "string".asJson),
      "decision" -> Json.obj("type" -> "string".asJson)),
    "required" -> Json.arr("correlationId".asJson, "decision".asJson))
  def summarize(input: JsonObject): String = "AskHostReply"
  def summarizeResult(input: JsonObject, result: String): String = result
  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val id = input("correlationId").flatMap(_.asString).getOrElse("").trim
    val decision = input("decision").flatMap(_.asString).getOrElse("").trim
    if !ctx.isRootAgent then IO.pure(Left(ToolError("AskHostReply: root identity required")))
    else if id.isEmpty || decision.isEmpty then IO.pure(Left(ToolError("AskHostReply: correlationId and decision are required")))
    else ctx.sharedResources match
      case None => IO.pure(Left(ToolError("AskHostReply: runtime resources unavailable")))
      case Some(resources) =>
        AskHostLedger.recover.flatMap(_.find(_.pending.correlationId == id) match
          case None => IO.pure(Left(ToolError(s"AskHost request unavailable or already answered: $id")))
          case Some(row) if row.pending.rootSession != ctx.sessionId.getOrElse("") =>
            IO.pure(Left(ToolError("AskHostReply: request belongs to another root session")))
          case Some(row) =>
            val event = AgentCommand.ExternalEvent("askhost", "reply",
              s"[AskHost reply ${row.pending.correlationId}] $decision",
              JsonObject("correlationId" -> id.asJson, "workerSession" -> row.pending.workerSession.asJson,
                "rootSession" -> row.pending.rootSession.asJson, "decision" -> decision.asJson,
                "engineOwned" -> true.asJson), Some(id))
            nebflow.core.mail.SessionMailbox.mailOnce(row.pending.workerSession, event, "askhost-reply", id,
              coalesce = false, producer = "askhost-reply").flatMap {
              case nebflow.core.mail.SessionMailbox.MailResult.Enqueued(_) | nebflow.core.mail.SessionMailbox.MailResult.AlreadyPresent =>
                AskHostLedger.answer(id, decision) *> resources.agentRegistry.get.flatMap(_.get(row.pending.workerSession) match
                  case Some(worker) => (worker.ref ! AgentCommand.MailboxArrived).as(Right(s"AskHost reply durably queued for $id"))
                  case None =>
                    val deferred = AskHostLedger.restoreWaiter(id)
                    deferred.flatMap(_.get).flatTap(_ => AskHostLedger.markDelivered(id)).map(answer => Right(s"AskHost reply delivered after worker resumed for $id: $answer"))
                )
              case other => AskHostLedger.markUnavailable(id, s"worker reply delivery failed: $other")
                .as(Left(ToolError(s"AskHost worker delivery unavailable: $other")))
            }
        )
end AskHostReplyTool
