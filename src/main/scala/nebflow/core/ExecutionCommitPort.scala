package nebflow.core

import cats.effect.IO
import nebflow.shared.{
  ExecutionAdmission,
  ExecutionAdmissionObservation,
  ExecutionCandidate,
  ExecutionChainObservation,
  ExecutionCommitRef,
  ExecutionCommitResult,
  ExecutionProtection,
  ExecutionTransition,
  ProtectedRecoveryRequest,
  ProtectedRecoveryResult
}
import io.circe.syntax.*

/** Admission-only authority surface. No execution permit is available yet. */
trait ExecutionCommitPort:
  def admit(admission: ExecutionAdmission): IO[ExecutionCommitResult]
  def observe(sessionId: String, executionId: String): IO[ExecutionAdmissionObservation]
  def reconcileAdmission(admission: ExecutionAdmission): IO[ExecutionCommitResult]
  def transition(command: ExecutionTransition): IO[ExecutionCommitResult]

  def acceptInput(command: ExecutionTransition): IO[ExecutionCommitResult] =
    IO.pure(ExecutionCommitResult.Rejected("unsupported"))
  def reconcileTransition(command: ExecutionTransition): IO[ExecutionCommitResult]

  def commitRound(command: ExecutionTransition): IO[ExecutionCommitResult] =
    IO.pure(ExecutionCommitResult.Rejected("unsupported"))
  def observeChain(sessionId: String, executionId: String): IO[ExecutionChainObservation]

  /**
   * Resume a process-reopened protected run without a live runner handle.
   * The authority alone decides whether the observed commit and checkpoint are safe.
   */
  def resumeInterrupted(
    request: ProtectedRecoveryRequest,
    configBinding: String
  ): IO[Either[String, ProtectedRecoveryResult]] =
    IO.pure(Left("unsupported"))

  def committedTerminal(sessionId: String, executionId: String): IO[Either[String, io.circe.Json]] =
    IO.pure(Left("unsupported"))

  /**
   * True only for a port that can honestly serve protected execution. A default
   * or fake port answers false so the Delegate entry fails closed instead of
   * silently running an unprotected loop under a B1 opt-in.
   */
  def supportsProtectedExecution: Boolean = false

  /**
   * Legacy callers have no protected record. A port that is absent or a fake
   * therefore reports Unprotected rather than blocking an existing spawn.
   */
  def protection(sessionId: String): IO[ExecutionProtection] =
    IO.pure(ExecutionProtection.Unprotected)
  def candidates: IO[Either[String, List[ExecutionCandidate]]] = IO.pure(Left("unsupported"))

  def cancelUnownedSession(sessionId: String, reason: String): IO[ExecutionCommitResult] =
    IO.pure(ExecutionCommitResult.Rejected("unsupported"))

  /** The absence of a protected record is not a reason to reject a legacy action. */
  def withUnprotectedSession[A](sessionId: String)(effect: IO[A]): IO[A] = effect
  def withClosedParentAdmission[A](sessionId: String)(effect: IO[A]): IO[A] = effect

end ExecutionCommitPort

final case class ProtectedExecutionHandle private[core] (
  admission: ExecutionAdmission,
  ref: ExecutionCommitRef,
  authority: ExecutionCommitPort
)

object ExecutionCommitPort:

  /** Bind only a confirmed admission and its exact durable initial input. */
  def admitHandle(
    port: ExecutionCommitPort,
    admission: ExecutionAdmission
  ): IO[Either[String, ProtectedExecutionHandle]] =
    port.admit(admission).flatMap {
      case ExecutionCommitResult.Committed(ref) => bindHandle(port, admission, ref)
      case ExecutionCommitResult.Duplicate(ref) => bindHandle(port, admission, ref)
      case ExecutionCommitResult.CommitUnknown(_, _) => IO.pure(Left("commit-unknown"))
      case ExecutionCommitResult.Rejected(reason) => IO.pure(Left(reason))
      case ExecutionCommitResult.DuplicateInput(_, _) => IO.pure(Left("input-already-admitted"))
    }

  /**
   * Bind a resumed attempt. Unlike [[admitHandle]] the durable record still carries
   * the previous attempt; only the stable session/run/business bindings and the
   * original input are compared.
   */
  def bindRecovered(
    port: ExecutionCommitPort,
    admission: ExecutionAdmission,
    ref: ExecutionCommitRef
  ): IO[Either[String, ProtectedExecutionHandle]] =
    port.observe(admission.sessionId, admission.executionId).map {
      case ExecutionAdmissionObservation.Verified(record, input)
          if record.sessionId == admission.sessionId && record.executionId == admission.executionId &&
            record.ownerBinding == admission.ownerBinding && record.businessBinding == admission.businessBinding &&
            record.modelAuditBinding == admission.modelAuditBinding &&
            input == io.circe.Json.obj(
              "inputId" -> admission.initialInputId.asJson,
              "content" -> admission.initialInput
            ) =>
        Right(ProtectedExecutionHandle(admission, ref, port))
      case _ => Left("recovery-admission-unconfirmed")
    }

  private def bindHandle(
    port: ExecutionCommitPort,
    admission: ExecutionAdmission,
    ref: ExecutionCommitRef
  ): IO[Either[String, ProtectedExecutionHandle]] =
    port.observe(admission.sessionId, admission.executionId).map {
      case ExecutionAdmissionObservation.Verified(record, input)
          if ref.revision == record.revision && ref.recordHash == record.recordHash &&
            record.sessionId == admission.sessionId && record.executionId == admission.executionId &&
            record.commitId == admission.commitId && record.requestId == admission.requestId &&
            record.attemptId == admission.attemptId && record.incarnationId == admission.incarnationId &&
            record.ownerBinding == admission.ownerBinding && record.businessBinding == admission.businessBinding &&
            record.modelAuditBinding == admission.modelAuditBinding &&
            input == io.circe.Json.obj(
              "inputId" -> admission.initialInputId.asJson,
              "content" -> admission.initialInput
            ) =>
        Right(ProtectedExecutionHandle(admission, ref, port))
      case _ => Left("admission-unconfirmed")
    }

  val unsupported: ExecutionCommitPort = new ExecutionCommitPort:
    def admit(admission: ExecutionAdmission): IO[ExecutionCommitResult] =
      IO.pure(ExecutionCommitResult.Rejected("unsupported"))

    def observe(sessionId: String, executionId: String): IO[ExecutionAdmissionObservation] =
      IO.pure(ExecutionAdmissionObservation.ReviewRequired("unsupported"))

    def reconcileAdmission(admission: ExecutionAdmission): IO[ExecutionCommitResult] =
      IO.pure(ExecutionCommitResult.Rejected("unsupported"))

    def transition(command: ExecutionTransition): IO[ExecutionCommitResult] =
      IO.pure(ExecutionCommitResult.Rejected("unsupported"))

    def reconcileTransition(command: ExecutionTransition): IO[ExecutionCommitResult] =
      IO.pure(ExecutionCommitResult.Rejected("unsupported"))

    def observeChain(sessionId: String, executionId: String): IO[ExecutionChainObservation] =
      IO.pure(ExecutionChainObservation.ReviewRequired(None, "unsupported"))

    override def resumeInterrupted(
      request: ProtectedRecoveryRequest,
      configBinding: String
    ): IO[Either[String, ProtectedRecoveryResult]] =
      IO.pure(Left("unsupported"))
end ExecutionCommitPort
