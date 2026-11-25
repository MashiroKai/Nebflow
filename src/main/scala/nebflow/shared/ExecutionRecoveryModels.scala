package nebflow.shared

import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder, Json}

/** The first durable record binds a pre-existing task run to its initial input. */
final case class ExecutionAdmission(
  sessionId: String,
  executionId: String,
  commitId: String,
  requestId: String,
  attemptId: String,
  incarnationId: String,
  ownerBinding: String,
  businessBinding: String,
  modelAuditBinding: String,
  initialInput: Json,
  initialInputId: String
)

object ExecutionAdmission:
  given Encoder[ExecutionAdmission] = deriveEncoder
  given Decoder[ExecutionAdmission] = deriveDecoder

final case class ExecutionCommitRef(revision: Long, recordHash: String)

object ExecutionCommitRef:
  given Encoder[ExecutionCommitRef] = deriveEncoder
  given Decoder[ExecutionCommitRef] = deriveDecoder

final case class ExecutionAdmissionRecord(
  schemaVersion: Int,
  sessionId: String,
  executionId: String,
  commitId: String,
  revision: Long,
  previousHash: String,
  event: String,
  roundIndex: Long,
  requestId: String,
  attemptId: String,
  incarnationId: String,
  ownerBinding: String,
  businessBinding: String,
  modelAuditBinding: String,
  payloadHash: String,
  payloadLength: Long,
  recordHash: String
)

object ExecutionAdmissionRecord:
  given Encoder[ExecutionAdmissionRecord] = deriveEncoder
  given Decoder[ExecutionAdmissionRecord] = deriveDecoder

final case class ExecutionToolIntent(operationId: String, modelCallId: String, tool: String, arguments: Json)

object ExecutionToolIntent:
  given Encoder[ExecutionToolIntent] = deriveEncoder
  given Decoder[ExecutionToolIntent] = deriveDecoder

final case class ExecutionModelDecision(message: Json, calls: List[ExecutionToolIntent])

object ExecutionModelDecision:
  given Encoder[ExecutionModelDecision] = deriveEncoder
  given Decoder[ExecutionModelDecision] = deriveDecoder

enum ExecutionEvent:
  case InputAccepted(inputId: String, content: Json)
  case AttemptStarted
  case ModelRequested(request: Json, retryLimit: Int)
  case ModelRetryCharged(category: String)
  case ModelDecided(decision: ExecutionModelDecision)
  case ToolPrepared(operationId: String, intent: Json)
  case ToolStarted(operationId: String)
  case ToolOutcome(operationId: String, result: Json)
  case RoundCommitted(inputIds: List[String], checkpoint: Json)
  case TerminalInstallPrepared(payloadHash: String, envelope: Json)
  case TerminalCommitted(payloadHash: String, resultRef: String)
  case Suspended(reason: String)
  case CancelRequested(reason: String)
  case RunnerStopped
  case ResumeAuthorized(newAttemptId: String, newIncarnationId: String, principal: String)

end ExecutionEvent

object ExecutionEvent:
  given Encoder[ExecutionEvent] = deriveEncoder
  given Decoder[ExecutionEvent] = deriveDecoder

final case class ExecutionTransition(
  sessionId: String,
  executionId: String,
  commitId: String,
  requestId: String,
  expected: ExecutionCommitRef,
  attemptId: String,
  incarnationId: String,
  event: ExecutionEvent
)

object ExecutionTransition:
  given Encoder[ExecutionTransition] = deriveEncoder
  given Decoder[ExecutionTransition] = deriveDecoder

final case class ExecutionChainState(
  ref: ExecutionCommitRef,
  attemptId: String,
  incarnationId: String,
  roundIndex: Long,
  phase: String,
  inputs: Map[String, Json],
  pendingInputs: List[String],
  operations: Map[String, String],
  intents: Map[String, ExecutionToolIntent],
  retryCharges: Long,
  retryLimit: Int,
  runnerStopped: Boolean,
  decision: Option[ExecutionModelDecision] = None,
  outcomes: Map[String, Json] = Map.empty,
  checkpoint: Option[Json] = None,
  terminalPayload: Option[(String, Json)] = None,
  terminalResultRef: Option[String] = None,
  suspensionReason: Option[String] = None
)

sealed trait ExecutionChainObservation

object ExecutionChainObservation:
  final case class Verified(state: ExecutionChainState) extends ExecutionChainObservation
  final case class ReviewRequired(prefix: Option[ExecutionChainState], reason: String) extends ExecutionChainObservation

sealed trait ExecutionCommitResult

object ExecutionCommitResult:
  final case class Committed(ref: ExecutionCommitRef) extends ExecutionCommitResult
  final case class Duplicate(ref: ExecutionCommitRef) extends ExecutionCommitResult
  final case class DuplicateInput(executionId: String, ref: ExecutionCommitRef) extends ExecutionCommitResult
  final case class Rejected(reason: String) extends ExecutionCommitResult
  final case class CommitUnknown(commitId: String, phase: String) extends ExecutionCommitResult

final case class ExecutionCandidate(sessionId: String, protection: ExecutionProtection)

/** An explicit manual recovery request for one protected run. */
final case class ProtectedRecoveryRequest(
  sessionId: String,
  executionId: String,
  observed: ExecutionCommitRef,
  requestId: String,
  principal: String,
  input: Option[String] = None
)

object ProtectedRecoveryRequest:
  given Encoder[ProtectedRecoveryRequest] = deriveEncoder
  given Decoder[ProtectedRecoveryRequest] = deriveDecoder

/** The exact attempt installed by a recovery request; duplicate repeats the prior result. */
final case class ProtectedRecoveryResult(
  sessionId: String,
  executionId: String,
  attemptId: String,
  incarnationId: String,
  ref: ExecutionCommitRef,
  duplicate: Boolean
)

object ProtectedRecoveryResult:
  given Encoder[ProtectedRecoveryResult] = deriveEncoder
  given Decoder[ProtectedRecoveryResult] = deriveDecoder

sealed trait ExecutionProtection

object ExecutionProtection:
  case object Unprotected extends ExecutionProtection
  final case class Protected(executionIds: List[String]) extends ExecutionProtection
  final case class Unknown(reason: String) extends ExecutionProtection

sealed trait ExecutionAdmissionObservation

object ExecutionAdmissionObservation:
  case object Missing extends ExecutionAdmissionObservation
  final case class Verified(record: ExecutionAdmissionRecord, initialInput: Json) extends ExecutionAdmissionObservation
  final case class ReviewRequired(reason: String) extends ExecutionAdmissionObservation
