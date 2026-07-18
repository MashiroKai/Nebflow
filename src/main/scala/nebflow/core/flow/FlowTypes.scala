package nebflow.core.flow

import cats.effect.{Deferred, IO, Ref}
import nebflow.core.NebflowLogger

// ============================================================
// VerifyResult — used by FlowVerifyRegistry + FlowVerifyTool
// ============================================================

case class VerifyResult(pass: Boolean, summary: String)

// ============================================================
// StepStatus — pipeline step lifecycle states
// ============================================================

enum StepStatus:
  case Pending, Running, Done, Failed

// ============================================================
// FlowVerifyRegistry — bridges FlowVerifyTool ↔ pipeline verify
// ============================================================

/**
 * Global registry mapping verify agent paths to their pending Deferred.
 * The pipeline orchestrator registers a Deferred before spawning a verify agent.
 * FlowVerifyTool completes it when the verify agent calls the tool.
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
    pending.update(_ - agentPath)

end FlowVerifyRegistry
