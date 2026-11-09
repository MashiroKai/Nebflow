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

  final case class Pending(correlationId: String, workerSession: String, rootSession: String, sourceAgent: String)

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

  def clearForTests(): Unit = waiters.clear()

  def answer(correlationId: String, answer: String): IO[Boolean] =
    waiters.get(correlationId) match
      case None => IO.pure(false)
      case Some(reply) => reply.complete(answer).flatTap(done =>
        if done then append(Json.obj("correlationId" -> correlationId.asJson, "decision" -> answer.asJson,
          "status" -> "delivered".asJson, "at" -> System.currentTimeMillis().asJson))
        else IO.unit
      )

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
            AskHostLedger.begin(worker, root, agent.name, question).flatMap { case (request, reply) =>
              val payload = s"[AskHost request ${request.correlationId} from ${request.sourceAgent} / ${request.workerSession}]\n$question"
              val metadata = JsonObject("correlationId" -> request.correlationId.asJson,
                "workerSession" -> worker.asJson, "rootSession" -> root.asJson,
                "sourceAgent" -> agent.name.asJson, "engineOwned" -> true.asJson)
              (rootRecord.ref ! AgentCommand.ExternalEvent("askhost", "request", payload, metadata,
                Some(request.correlationId))) *> reply.get.map(Right(_))
            }
        )
      case _ => IO.pure(Left(ToolError("AskHost is only available to a worker with an engine-bound root session")))
end AskHostTool

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
    else AskHostLedger.answer(id, decision).map {
      case true => Right(s"AskHost reply delivered for $id")
      case false => Left(ToolError(s"AskHost request unavailable or already answered: $id"))
    }
end AskHostReplyTool
