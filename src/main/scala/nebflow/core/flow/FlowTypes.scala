package nebflow.core.flow

import cats.effect.{Deferred, IO, Ref}
import cats.implicits.*
import nebflow.core.NebflowLogger

// ============================================================
// VerifyResult — used by FlowVerifyRegistry + MailTool
// ============================================================

case class VerifyResult(pass: Boolean, summary: String)

// ============================================================
// StepStatus — pipeline step lifecycle states
// ============================================================

enum StepStatus:
  case Pending, Running, Done, Failed, Canceled

// ============================================================
// FlowVerifyRegistry — bridges MailTool(type=verify) ↔ pipeline
// ============================================================

/**
 * Global registry mapping verify agent paths to their pending Deferred.
 * PipelineActor registers a Deferred before spawning a verify agent.
 * MailTool completes it when the verify agent calls Mail(type=verify).
 */
object FlowVerifyRegistry:
  private val logger = NebflowLogger(getClass)
  private val pending = Ref.unsafe[IO, Map[String, Deferred[IO, VerifyResult]]](Map.empty)

  def register(agentPath: String, d: Deferred[IO, VerifyResult]): IO[Unit] =
    pending.update(_ + (agentPath -> d))

  def tryGet(agentPath: String): IO[Option[Deferred[IO, VerifyResult]]] =
    pending.get.map(_.get(agentPath))

  def complete(agentPath: String, result: VerifyResult): IO[Boolean] =
    pending.get.flatMap { m =>
      m.get(agentPath) match
        case Some(d) => d.complete(result).as(true)
        case None => IO.pure(false)
    }

  def remove(agentPath: String): IO[Unit] =
    pending.update(_ - (agentPath))

  /** Test-only: complete ALL pending verify Deferreds with the given result.
   *  Used by the test FakeLlm to simulate a verify agent calling
   *  Mail(type=verify) — since the fake LLM can't call tools and doesn't know
   *  the spawned agent's actor path. Production never calls this. */
  def completeAllForTest(result: VerifyResult): IO[Int] =
    pending.get.flatMap { m =>
      m.values.toList.traverse_(_.complete(result)).as(m.size) <*
        pending.set(Map.empty)
    }

end FlowVerifyRegistry
