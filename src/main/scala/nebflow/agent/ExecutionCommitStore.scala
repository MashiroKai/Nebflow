package nebflow.agent

import cats.effect.kernel.Outcome
import cats.effect.std.Semaphore
import cats.effect.{Deferred, IO, Resource}
import cats.syntax.all.*
import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.ExecutionCommitPort
import nebflow.shared.*
import nebflow.shared.given

import java.nio.ByteBuffer
import java.nio.channels.{FileChannel, FileLock}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, LinkOption, Path, StandardCopyOption, StandardOpenOption}
import java.security.MessageDigest
import java.util.Base64

import scala.concurrent.duration.*

/** Serialized local commit authority and volatile attempt ownership. */
final class ExecutionCommitStore private (
  root: Path,
  channel: FileChannel,
  lock: FileLock,
  fault: String => Unit,
  admissionGate: Semaphore[IO]
) extends ExecutionCommitPort:
  import ExecutionCommitStore.*

  override val supportsProtectedExecution: Boolean = true

  private val monitor = new Object
  private var closed = false
  private var uncertain = Map.empty[(String, String), ExecutionAdmission]
  private var uncertainTransitions = Map.empty[(String, String), ExecutionTransition]
  private var uncertainTerminals = Map.empty[(String, String), (String, Json)]
  private var runners = Map.empty[(String, String), OwnedRunner]
  private var localStarts = Set.empty[(String, String, String, String)]
  private var authorizedResumes = Set.empty[(String, String, String, String)]

  private final class OwnedRunner(val runner: ExecutionRunner):
    var revoked = false
    var receipts = Map.empty[String, ExecutionRunner.Receipt]
    var modelReceipt: Option[(ExecutionRunner.Receipt, ExecutionCommitRef)] = None
    var pending = Set.empty[Deferred[IO, Unit]]

  /** Only this authority creates and registers the runner for an attempt. */
  def registerRunner(attempt: ExecutionRunner.Attempt): Resource[IO, Either[String, RunnerHandle]] =
    ExecutionRunner
      .resource(attempt)
      .evalMap { runner =>
        IO.blocking {
          monitor.synchronized {
            val key = (attempt.sessionId, attempt.executionId)
            if closed then Left("authority-closed")
            else if runners
                .get(key)
                .exists(owner =>
                  !owner.revoked || owner.pending.nonEmpty ||
                    owner.runner.attempt == attempt || !authorizedResumes
                      .contains((attempt.sessionId, attempt.executionId, attempt.attemptId, attempt.incarnationId))
                )
            then Left("runner-already-owned")
            else if !localStarts.contains(
                (attempt.sessionId, attempt.executionId, attempt.attemptId, attempt.incarnationId)
              )
            then Left("attempt-not-started-by-authority")
            else if uncertain.contains(key) || uncertainTransitions.contains(key) then Left("commit-unknown")
            else
              try
                readChain(attempt.sessionId, attempt.executionId)._1 match
                  case ExecutionChainObservation.Verified(state)
                      if state.attemptId == attempt.attemptId && state.incarnationId == attempt.incarnationId &&
                        Set("active", "model", "decided").contains(state.phase) =>
                    val owner = new OwnedRunner(runner)
                    runners += key -> owner
                    Right(new RunnerHandle(owner))
                  case _ => Left("attempt-unavailable")
              catch case e: Exception => Left(s"runner-registration-failed: ${e.getClass.getSimpleName}")
            end if
          }
        }
      }
      .flatMap {
        case Right(handle) => Resource.make(IO.pure[Either[String, RunnerHandle]](Right(handle)))(_ => handle.revoke)
        case left => Resource.pure[IO, Either[String, RunnerHandle]](left)
      }

  final class RunnerHandle private[ExecutionCommitStore] (private val owner: OwnedRunner):
    private def key = (owner.runner.attempt.sessionId, owner.runner.attempt.executionId)

    /** Commit a model request before registering its attempt-owned work. */
    def startModel(
      command: ExecutionTransition
    )(work: IO[Unit]): IO[Either[ExecutionCommitResult, (ExecutionCommitRef, ExecutionRunner.Receipt)]] =
      IO.uncancelable { _ =>
        (Deferred[IO, Unit], Deferred[IO, Unit]).tupled.flatMap { (pending, ready) =>
          IO.blocking {
            monitor.synchronized {
              if !runners.get(key).contains(owner) || owner.revoked then
                ExecutionCommitResult.Rejected("runner-revoked")
              else if !matchesOwner(command, owner) || !command.event.isInstanceOf[ExecutionEvent.ModelRequested] then
                ExecutionCommitResult.Rejected("invalid-runner-command")
              else
                val result = transitionSync(command, false)
                if result.isInstanceOf[ExecutionCommitResult.Committed] then owner.pending += pending
                result
            }
          }.flatMap {
            case ExecutionCommitResult.Committed(ref) =>
              owner.runner
                .start(command.commitId)(ready.get *> work)
                .flatMap {
                  case Right(receipt) =>
                    IO.blocking {
                      monitor.synchronized {
                        owner.modelReceipt = Some((receipt, ref))
                      }
                    } *> ready.complete(()).as(Right((ref, receipt)))
                  case Left(_) =>
                    IO.pure(Left(ExecutionCommitResult.Rejected("model-runner-unavailable-review-required")))
                }
                .guarantee(IO.blocking {
                  monitor.synchronized { owner.pending -= pending }
                } *> pending.complete(()).void)
            case other => IO.pure(Left(other))
          }
        }
      }

    def chargeModelRetry(command: ExecutionTransition): IO[ExecutionCommitResult] = IO.blocking {
      monitor.synchronized {
        if !runners.get(key).contains(owner) || owner.revoked || owner.modelReceipt.isEmpty || !matchesOwner(
            command,
            owner
          ) ||
          command.event != ExecutionEvent.ModelRetryCharged("classified-transient-provider")
        then ExecutionCommitResult.Rejected("runner-authority-required")
        else
          try
            val (observation, records) = readChain(command.sessionId, command.executionId)
            observation match
              case ExecutionChainObservation.Verified(state) if state.phase == "model" && owner.modelReceipt.exists {
                    case (_, requested) =>
                      records.filter(_._1.revision > requested.revision).forall { case (_, payload) =>
                        payload.as[ExecutionTransition].toOption.exists { transition =>
                          transition.event.isInstanceOf[ExecutionEvent.InputAccepted] ||
                          transition.event == ExecutionEvent.ModelRetryCharged("classified-transient-provider")
                        }
                      }
                  } =>
                transitionSync(command.copy(expected = state.ref), false)
              case _ => ExecutionCommitResult.Rejected("model-retry-unavailable")
          catch case e: Exception => ExecutionCommitResult.Rejected(s"retry-read-failed: ${e.getClass.getSimpleName}")
      }
    }

    /** Fence model results against revocation and the registered work receipt. */
    def modelDecision(command: ExecutionTransition, receipt: ExecutionRunner.Receipt): IO[ExecutionCommitResult] =
      receipt.await.flatMap {
        case Outcome.Succeeded(_) =>
          IO.blocking {
            monitor.synchronized {
              if !runners.get(key).contains(owner) || owner.revoked then
                ExecutionCommitResult.Rejected("runner-revoked")
              else if !matchesOwner(command, owner) || !owner.modelReceipt.contains((receipt, command.expected)) ||
                !command.event.isInstanceOf[ExecutionEvent.ModelDecided]
              then ExecutionCommitResult.Rejected("invalid-runner-command")
              else
                try
                  val (observation, records) = readChain(command.sessionId, command.executionId)
                  observation match
                    case ExecutionChainObservation.Verified(state) =>
                      val expectedExists = records.exists { case (record, _) =>
                        record.revision == command.expected.revision && record.recordHash == command.expected.recordHash &&
                        record.commitId == receipt.operationId
                      }
                      val inputOnly =
                        records.filter(_._1.revision > command.expected.revision).forall { case (_, payload) =>
                          payload.as[ExecutionTransition].toOption.exists { transition =>
                            transition.event.isInstanceOf[ExecutionEvent.InputAccepted] ||
                            transition.event == ExecutionEvent.ModelRetryCharged("classified-transient-provider")
                          }
                        }
                      if !expectedExists || !inputOnly then ExecutionCommitResult.Rejected("stale-revision")
                      else
                        val result = transitionSync(command.copy(expected = state.ref), false)
                        if result.isInstanceOf[ExecutionCommitResult.Committed] then owner.modelReceipt = None
                        result
                    case ExecutionChainObservation.ReviewRequired(_, reason) =>
                      ExecutionCommitResult.Rejected(s"review-required: $reason")
                  end match
                catch
                  case e: Exception => ExecutionCommitResult.Rejected(s"model-read-failed: ${e.getClass.getSimpleName}")
            }
          }
        case _ => IO.pure(ExecutionCommitResult.Rejected("model-unconfirmed-review-required"))
      }

    /** Called after permission; the Started commit and cancellation share this writer. */
    def dispatch(command: ExecutionTransition, intent: ExecutionToolIntent)(
      work: IO[Unit]
    ): IO[Either[ExecutionCommitResult, (ExecutionCommitRef, ExecutionRunner.Receipt)]] =
      IO.uncancelable { _ =>
        Deferred[IO, Unit].flatMap { pending =>
          IO.blocking {
            monitor.synchronized {
              if !runners.get(key).contains(owner) || owner.revoked then
                ExecutionCommitResult.Rejected("runner-revoked")
              else if !matchesOwner(command, owner) || !command.event.isInstanceOf[ExecutionEvent.ToolStarted] then
                ExecutionCommitResult.Rejected("invalid-runner-command")
              else
                try
                  val start = command.event.asInstanceOf[ExecutionEvent.ToolStarted]
                  val (observation, records) = readChain(command.sessionId, command.executionId)
                  observation match
                    case ExecutionChainObservation.Verified(state)
                        if start.operationId == intent.operationId && state.intents
                          .get(intent.operationId)
                          .contains(intent) &&
                          inputOnlySince(command.expected, records) =>
                      val result = transitionSync(command.copy(expected = state.ref), false)
                      if result.isInstanceOf[ExecutionCommitResult.Committed] then owner.pending += pending
                      result
                    case _ => ExecutionCommitResult.Rejected("intent-mismatch-or-unverified")
                catch
                  case e: Exception =>
                    ExecutionCommitResult.Rejected(s"intent-read-failed: ${e.getClass.getSimpleName}")
            }
          }.flatMap {
            case ExecutionCommitResult.Committed(ref) =>
              val id = command.event.asInstanceOf[ExecutionEvent.ToolStarted].operationId
              owner.runner
                .start(id)(work)
                .flatMap {
                  case Right(receipt) =>
                    IO.blocking {
                      monitor.synchronized { owner.receipts += id -> receipt }
                      Right((ref, receipt))
                    }
                  case Left(_) =>
                    IO.pure(Left(ExecutionCommitResult.Rejected("started-runner-unavailable-review-required")))
                }
                .guarantee(IO.blocking {
                  monitor.synchronized { owner.pending -= pending }
                } *> pending.complete(()).void)
            case other => IO.pure(Left(other))
          }
        }
      }

    def outcome(command: ExecutionTransition, receipt: ExecutionRunner.Receipt): IO[ExecutionCommitResult] =
      receipt.await.flatMap {
        case Outcome.Succeeded(_) =>
          IO.blocking {
            monitor.synchronized {
              if !runners.get(key).contains(owner) || owner.revoked then
                ExecutionCommitResult.Rejected("runner-revoked")
              else if !matchesOwner(command, owner) || !owner.receipts.get(receipt.operationId).contains(receipt) ||
                !command.event.isInstanceOf[ExecutionEvent.ToolOutcome] ||
                command.event.asInstanceOf[ExecutionEvent.ToolOutcome].operationId != receipt.operationId
              then ExecutionCommitResult.Rejected("invalid-runner-command")
              else
                val (observation, records) = readChain(command.sessionId, command.executionId)
                observation match
                  case ExecutionChainObservation.Verified(state) if inputOnlySince(command.expected, records) =>
                    transitionSync(command.copy(expected = state.ref), false)
                  case _ => ExecutionCommitResult.Rejected("stale-or-unverified-outcome")
            }
          }
        case _ => IO.pure(ExecutionCommitResult.Rejected("operation-unconfirmed-review-required"))
      }

    def installTerminal(command: ExecutionTransition, envelope: Json): IO[ExecutionCommitResult] =
      installTerminalOwned(owner, command, envelope)

    def projectTerminal(store: SubAgentTaskStore, resultRef: String): IO[Either[String, SubAgentTask]] = IO.blocking {
      monitor.synchronized {
        val attempt = owner.runner.attempt
        if !runners.get(key).contains(owner) || owner.revoked || uncertainTerminals.contains(key) then
          Left("runner-revoked-or-terminal-unknown")
        else
          readChain(attempt.sessionId, attempt.executionId) match
            case (ExecutionChainObservation.Verified(state), records)
                if state.phase == "terminal" && state.attemptId == attempt.attemptId &&
                  state.incarnationId == attempt.incarnationId && state.terminalResultRef.contains(resultRef) =>
              store.projectProtectedTerminalSync(
                records.head._1.ownerBinding,
                attempt.sessionId,
                attempt.executionId,
                resultRef
              )
            case _ => Left("terminal-unverified")
      }
    }

    def authorizeResume(
      command: ExecutionTransition,
      configBinding: String,
      timeout: FiniteDuration
    ): IO[ExecutionCommitResult] =
      awaitStopped(timeout).flatMap {
        case false => IO.pure(ExecutionCommitResult.Rejected("runner-stop-unconfirmed"))
        case true =>
          IO.blocking {
            monitor.synchronized {
              if !runners.get(key).contains(owner) || !owner.revoked || owner.pending.nonEmpty || !matchesOwner(
                  command,
                  owner
                ) ||
                !command.event.isInstanceOf[ExecutionEvent.ResumeAuthorized]
              then ExecutionCommitResult.Rejected("runner-authority-required")
              else
                try
                  val (observation, records) = readChain(command.sessionId, command.executionId)
                  observation match
                    case ExecutionChainObservation.Verified(_)
                        if records.head._1.modelAuditBinding == configBinding &&
                          records.exists { case (record, payload) =>
                            record.commitId == command.commitId && payload == command.asJson
                          } =>
                      val result = transitionSync(command, uncertainTransitions.get(key).contains(command))
                      if result.isInstanceOf[ExecutionCommitResult.Duplicate] then
                        val event = command.event.asInstanceOf[ExecutionEvent.ResumeAuthorized]
                        authorizedResumes += ((
                          command.sessionId,
                          command.executionId,
                          event.newAttemptId,
                          event.newIncarnationId
                        ))
                      result
                    case ExecutionChainObservation.Verified(state)
                        if state.ref == command.expected &&
                          state.phase == "suspended" && state.runnerStopped && state.operations.isEmpty &&
                          state.checkpoint.exists(value =>
                            ProtectedAgentExecution.restore(nebflow.actor.AgentState(), value).isRight
                          ) &&
                          records
                            .dropRight(2)
                            .reverse
                            .dropWhile { case (_, payload) =>
                              payload
                                .as[ExecutionTransition]
                                .toOption
                                .exists(_.event.isInstanceOf[ExecutionEvent.InputAccepted])
                            }
                            .headOption
                            .exists { case (_, payload) =>
                              payload
                                .as[ExecutionTransition]
                                .toOption
                                .exists(_.event.isInstanceOf[ExecutionEvent.RoundCommitted])
                            } && records.head._1.modelAuditBinding == configBinding =>
                      val result = transitionSync(command, false)
                      result match
                        case _: ExecutionCommitResult.Committed =>
                          val event = command.event.asInstanceOf[ExecutionEvent.ResumeAuthorized]
                          authorizedResumes += ((
                            command.sessionId,
                            command.executionId,
                            event.newAttemptId,
                            event.newIncarnationId
                          ))
                        case _ => ()
                      result
                    case _ => ExecutionCommitResult.Rejected("resume-preconditions-unverified")
                  end match
                catch
                  case e: Exception =>
                    ExecutionCommitResult.Rejected(s"resume-read-failed: ${e.getClass.getSimpleName}")
            }
          }
      }

    def cancel(command: ExecutionTransition): IO[ExecutionCommitResult] = IO.uncancelable { _ =>
      IO.blocking {
        monitor.synchronized {
          if !runners.get(key).contains(owner) || !matchesOwner(command, owner) ||
            !command.event.isInstanceOf[ExecutionEvent.CancelRequested]
          then ExecutionCommitResult.Rejected("invalid-runner-command")
          else
            val result = transitionSync(command, false)
            result match
              case _: ExecutionCommitResult.Committed | _: ExecutionCommitResult.Duplicate |
                  _: ExecutionCommitResult.CommitUnknown =>
                owner.revoked = true
              case _ => ()
            result
        }
      }.flatTap {
        case _: ExecutionCommitResult.Committed | _: ExecutionCommitResult.Duplicate |
            _: ExecutionCommitResult.CommitUnknown =>
          owner.runner.close
        case _ => IO.unit
      }
    }

    def revoke: IO[Unit] =
      IO.blocking { monitor.synchronized { owner.revoked = true } } *> owner.runner.close

    /** Confirm quiescence through the owned runner before installing its stop receipt. */
    def confirmStopped(command: ExecutionTransition, timeout: FiniteDuration): IO[ExecutionCommitResult] =
      if !matchesOwner(command, owner) || command.event != ExecutionEvent.RunnerStopped then
        IO.pure(ExecutionCommitResult.Rejected("invalid-runner-command"))
      else
        awaitStopped(timeout).flatMap {
          case false => IO.pure(ExecutionCommitResult.Rejected("runner-stop-unconfirmed"))
          case true =>
            IO.blocking {
              monitor.synchronized {
                if !runners.get(key).contains(owner) || !owner.revoked || owner.pending.nonEmpty then
                  ExecutionCommitResult.Rejected("runner-stop-unconfirmed")
                else transitionSync(command, false)
              }
            }
        }

    /** This observation alone cannot authorize resume. */
    def awaitStopped(timeout: FiniteDuration): IO[Boolean] =
      IO.blocking { monitor.synchronized { (owner.revoked, owner.pending.toList) } }.flatMap {
        case (false, _) => IO.pure(false)
        case (true, pending) =>
          (pending.traverse_(_.get) *> owner.runner.awaitStopped(timeout)).timeoutTo(timeout, IO.pure(false))
      }

  end RunnerHandle

  private def inputOnlySince(ref: ExecutionCommitRef, records: List[(ExecutionAdmissionRecord, Json)]): Boolean =
    records.exists { case (record, _) => record.revision == ref.revision && record.recordHash == ref.recordHash } &&
      records.filter(_._1.revision > ref.revision).forall { case (_, payload) =>
        payload.as[ExecutionTransition].toOption.exists(_.event.isInstanceOf[ExecutionEvent.InputAccepted])
      }

  private def matchesOwner(command: ExecutionTransition, owner: OwnedRunner): Boolean =
    val attempt = owner.runner.attempt
    command.sessionId == attempt.sessionId && command.executionId == attempt.executionId &&
    command.attemptId == attempt.attemptId && command.incarnationId == attempt.incarnationId

  override def withUnprotectedSession[A](sessionId: String)(effect: IO[A]): IO[A] =
    admissionGate.permit.use { _ =>
      protection(sessionId).flatMap {
        case ExecutionProtection.Unprotected => effect
        case _ => IO.raiseError(new IllegalStateException("protected-or-unknown-execution"))
      }
    }

  override def withClosedParentAdmission[A](sessionId: String)(effect: IO[A]): IO[A] =
    admissionGate.permit.use { _ =>
      IO.blocking {
        monitor.synchronized {
          if closed || !validId(sessionId) then throw new IllegalStateException("authority-closed-or-invalid-identity")
          val marker = parentClosurePath(root, sessionId)
          checkPath(root, marker)
          install(marker, canonical(Json.obj("version" -> 1.asJson, "parentSessionId" -> sessionId.asJson)), fault)
        }
      } *> effect
    }

  def admit(admission: ExecutionAdmission): IO[ExecutionCommitResult] = admissionGate.permit.use { _ =>
    IO.blocking {
      monitor.synchronized {
        val key = (admission.sessionId, admission.executionId)
        if closed then ExecutionCommitResult.Rejected("authority-closed")
        else if uncertain.contains(key) then ExecutionCommitResult.Rejected("commit-unknown")
        else if !validAdmission(admission) then ExecutionCommitResult.Rejected("invalid-admission")
        else
          var phase = "preparation"
          try
            val closure = parentClosurePath(root, admission.ownerBinding)
            checkPath(root, closure)
            if !Files.notExists(closure, LinkOption.NOFOLLOW_LINKS) then throw new ParentAdmissionClosed
            val dir = executionDir(root, admission.sessionId, admission.executionId)
            val payload =
              canonical(Json.obj("inputId" -> admission.initialInputId.asJson, "content" -> admission.initialInput))
            val payloadHash = hash(payload)
            val unsigned = ExecutionAdmissionRecord(
              2,
              admission.sessionId,
              admission.executionId,
              admission.commitId,
              1L,
              "",
              "Admission",
              0L,
              admission.requestId,
              admission.attemptId,
              admission.incarnationId,
              admission.ownerBinding,
              admission.businessBinding,
              admission.modelAuditBinding,
              payloadHash,
              payload.length.toLong,
              ""
            )
            val record = unsigned.copy(recordHash = hash(canonical(unsigned.asJson.mapObject(_.remove("recordHash")))))
            val commits = dir.resolve("commits")
            val payloads = dir.resolve("payloads")
            val executions = dir.getParent
            val prior =
              if !Files.exists(dir, LinkOption.NOFOLLOW_LINKS) && Files.exists(executions, LinkOption.NOFOLLOW_LINKS)
              then
                val identityCheck = ExecutionTransition(
                  admission.sessionId,
                  admission.executionId,
                  admission.commitId,
                  admission.requestId,
                  ExecutionCommitRef(0L, ""),
                  admission.attemptId,
                  admission.incarnationId,
                  ExecutionEvent.InputAccepted(admission.initialInputId, admission.initialInput)
                )
                inputInAnotherRun(identityCheck)
              else None
            prior match
              case Some(result) => result
              case None =>
                createDirectories(root, payloads)
                createDirectories(root, commits)
                val recordPath = commits.resolve("00000000000000000001.json")
                if Files.exists(recordPath, LinkOption.NOFOLLOW_LINKS) then
                  observeSync(admission.sessionId, admission.executionId) match
                    case ExecutionAdmissionObservation.Verified(existing, _)
                        if existing == record &&
                          readChain(admission.sessionId, admission.executionId)._1
                            .isInstanceOf[ExecutionChainObservation.Verified] =>
                      phase = "reconfirmation"
                      confirm(payloads.resolve(s"$payloadHash.json"))
                      confirm(recordPath)
                      ExecutionCommitResult.Duplicate(ExecutionCommitRef(1L, record.recordHash))
                    case _ => ExecutionCommitResult.Rejected("conflicting-or-corrupt-admission")
                else
                  phase = "payload-install"
                  install(payloads.resolve(s"$payloadHash.json"), payload, fault)
                  phase = "record-install"
                  install(recordPath, canonical(record.asJson), fault)
                  phase = "acknowledgement"
                  fault("before-acknowledgement")
                  ExecutionCommitResult.Committed(ExecutionCommitRef(1L, record.recordHash))
                end if
            end match
          catch
            case _: ParentAdmissionClosed => ExecutionCommitResult.Rejected("parent-admission-closed")
            case e: Exception =>
              uncertain += (key -> admission)
              ExecutionCommitResult.CommitUnknown(admission.commitId, s"$phase: ${e.getClass.getSimpleName}")
          end try
        end if
      }
    }
  }

  def reconcileAdmission(admission: ExecutionAdmission): IO[ExecutionCommitResult] = IO.blocking {
    monitor.synchronized {
      val key = (admission.sessionId, admission.executionId)
      if closed then ExecutionCommitResult.Rejected("authority-closed")
      else if !validAdmission(admission) then ExecutionCommitResult.Rejected("invalid-admission")
      else if !uncertain.get(key).contains(admission) then
        ExecutionCommitResult.Rejected("unknown-or-conflicting-request")
      else
        try
          observeSync(admission.sessionId, admission.executionId) match
            case ExecutionAdmissionObservation.Verified(record, input)
                if record.commitId == admission.commitId && record.requestId == admission.requestId &&
                  record.attemptId == admission.attemptId && record.incarnationId == admission.incarnationId &&
                  record.ownerBinding == admission.ownerBinding && record.businessBinding == admission.businessBinding &&
                  record.modelAuditBinding == admission.modelAuditBinding && input ==
                  Json.obj("inputId" -> admission.initialInputId.asJson, "content" -> admission.initialInput) =>
              val dir = executionDir(root, admission.sessionId, admission.executionId)
              confirm(dir.resolve("payloads").resolve(s"${record.payloadHash}.json"))
              confirm(dir.resolve("commits").resolve("00000000000000000001.json"))
              uncertain -= key
              ExecutionCommitResult.Duplicate(ExecutionCommitRef(1L, record.recordHash))
            case _ => ExecutionCommitResult.CommitUnknown(admission.commitId, "reconciliation-unconfirmed")
        catch
          case e: Exception =>
            ExecutionCommitResult.CommitUnknown(admission.commitId, s"reconfirmation: ${e.getClass.getSimpleName}")
      end if
    }
  }

  def observe(sessionId: String, executionId: String): IO[ExecutionAdmissionObservation] = IO.blocking {
    monitor.synchronized {
      if closed then ExecutionAdmissionObservation.ReviewRequired("authority-closed")
      else if !validId(sessionId) || !validId(executionId) then
        ExecutionAdmissionObservation.ReviewRequired("invalid-identity")
      else
        try observeSync(sessionId, executionId)
        catch
          case e: Exception => ExecutionAdmissionObservation.ReviewRequired(s"read-failed: ${e.getClass.getSimpleName}")
    }
  }

  private def observeSync(sessionId: String, executionId: String): ExecutionAdmissionObservation =
    val dir = executionDir(root, sessionId, executionId)
    val commits = dir.resolve("commits")
    val file = commits.resolve("00000000000000000001.json")
    checkPath(root, dir)
    if Files.notExists(dir, LinkOption.NOFOLLOW_LINKS) then ExecutionAdmissionObservation.Missing
    else if !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) then
      ExecutionAdmissionObservation.ReviewRequired("execution-directory-unavailable")
    else if !Files.isDirectory(commits, LinkOption.NOFOLLOW_LINKS) then
      ExecutionAdmissionObservation.ReviewRequired("missing-commits")
    else
      val entries = Files.list(commits)
      val names =
        try
          val iterator = entries.iterator()
          val builder = List.newBuilder[String]
          while iterator.hasNext do builder += iterator.next().getFileName.toString
          builder.result()
        finally entries.close()
      if !names.contains("00000000000000000001.json") then
        ExecutionAdmissionObservation.ReviewRequired("unexpected-commit-chain")
      else
        checkPath(root, file)
        val bytes = Files.readAllBytes(file)
        val decoded = parse(new String(bytes, UTF_8)).flatMap(_.as[ExecutionAdmissionRecord])
        decoded match
          case Left(_) => ExecutionAdmissionObservation.ReviewRequired("invalid-record")
          case Right(record) =>
            val expectedHash = hash(canonical(record.asJson.mapObject(_.remove("recordHash"))))
            if record.schemaVersion != 2 || record.revision != 1L || record.previousHash != "" ||
              record.event != "Admission" || record.roundIndex != 0L ||
              record.sessionId != sessionId || record.executionId != executionId ||
              !validRecordBindings(record) || record.payloadLength < 0L ||
              record.recordHash != expectedHash || !validHash(record.payloadHash) ||
              !java.util.Arrays.equals(bytes, canonical(record.asJson))
            then ExecutionAdmissionObservation.ReviewRequired("invalid-record-binding")
            else
              val payloadPath = dir.resolve("payloads").resolve(s"${record.payloadHash}.json")
              checkPath(root, payloadPath)
              val payload = Files.readAllBytes(payloadPath)
              if payload.length.toLong != record.payloadLength || hash(payload) != record.payloadHash then
                ExecutionAdmissionObservation.ReviewRequired("invalid-payload")
              else
                parse(new String(payload, UTF_8)) match
                  case Right(json) if java.util.Arrays.equals(payload, canonical(json)) =>
                    ExecutionAdmissionObservation.Verified(record, json)
                  case _ => ExecutionAdmissionObservation.ReviewRequired("invalid-payload-json")

            end if

        end match

      end if

    end if

  end observeSync

  override def protection(sessionId: String): IO[ExecutionProtection] = IO.blocking {
    monitor.synchronized { protectionSync(sessionId) }
  }

  private def protectionSync(sessionId: String): ExecutionProtection =
    if closed || !validId(sessionId) then ExecutionProtection.Unknown("authority-closed-or-invalid-identity")
    else if uncertain.keys.exists(_._1 == sessionId) || uncertainTransitions.keys.exists(_._1 == sessionId) ||
      uncertainTerminals.keys.exists(_._1 == sessionId)
    then ExecutionProtection.Unknown("commit-unknown")
    else
      try
        val session = root.resolve(sessionId)
        val executions = session.resolve("executions")
        checkPath(root, executions)
        if Files.notExists(executions, LinkOption.NOFOLLOW_LINKS) then ExecutionProtection.Unprotected
        else if !Files.isDirectory(executions, LinkOption.NOFOLLOW_LINKS) then
          ExecutionProtection.Unknown("invalid-executions-directory")
        else
          val stream = Files.list(executions)
          try
            val iterator = stream.iterator()
            val ids = List.newBuilder[String]
            while iterator.hasNext do
              val path = iterator.next()
              checkPath(root, path)
              val name = path.getFileName.toString
              if name == "administration" then throw new IllegalStateException("administration-requires-review")
              else
                if !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) then
                  throw new IllegalStateException("invalid-execution-entry")
                val id = new String(Base64.getUrlDecoder.decode(name), UTF_8)
                if !validId(id) || executionDir(root, sessionId, id) != path then
                  throw new IllegalStateException("invalid-execution-identity")
                readChain(sessionId, id)._1 match
                  case ExecutionChainObservation.Verified(_) => ids += id
                  case ExecutionChainObservation.ReviewRequired(_, _) =>
                    throw new IllegalStateException("candidate-requires-review")
            end while
            val result = ids.result().sorted
            if result.isEmpty then ExecutionProtection.Unknown("empty-executions-directory")
            else ExecutionProtection.Protected(result)
          finally stream.close()
          end try
        end if
      catch case e: Exception => ExecutionProtection.Unknown(s"discovery-failed: ${e.getClass.getSimpleName}")

  override def candidates: IO[Either[String, List[ExecutionCandidate]]] =
    IO.blocking {
      monitor.synchronized {
        if closed then Left("authority-closed")
        else
          try
            val stream = Files.list(root)
            try
              val iterator = stream.iterator()
              val names = List.newBuilder[String]
              while iterator.hasNext do
                val path = iterator.next()
                val name = path.getFileName.toString
                if Files.isSymbolicLink(path) then throw new IllegalStateException("symlink-in-sessions-root")
                if Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) then
                  if !validId(name) then throw new IllegalStateException("invalid-session-directory")
                  names += name
              val found = names.result().sorted.flatMap { sessionId =>
                protectionSync(sessionId) match
                  case ExecutionProtection.Unprotected => None
                  case other => Some(ExecutionCandidate(sessionId, other))
              }
              Right(found)
            finally stream.close()
            end try
          catch case e: Exception => Left(s"candidate-scan-failed: ${e.getClass.getSimpleName}")
      }
    }

  override def cancelUnownedSession(sessionId: String, reason: String): IO[ExecutionCommitResult] =
    admissionGate.permit.use { _ =>
      IO.blocking {
        monitor.synchronized {
          protectionSync(sessionId) match
            case ExecutionProtection.Protected(List(executionId)) if !runners.contains((sessionId, executionId)) =>
              try
                readChain(sessionId, executionId)._1 match
                  case ExecutionChainObservation.Verified(state) =>
                    val id = java.util.UUID.randomUUID().toString
                    transitionSync(
                      ExecutionTransition(
                        sessionId,
                        executionId,
                        id,
                        id,
                        state.ref,
                        state.attemptId,
                        state.incarnationId,
                        ExecutionEvent.CancelRequested(reason)
                      ),
                      false
                    )
                  case _ => ExecutionCommitResult.Rejected("review-required")
              catch
                case e: Exception => ExecutionCommitResult.Rejected(s"cancel-read-failed: ${e.getClass.getSimpleName}")
            case ExecutionProtection.Protected(_) => ExecutionCommitResult.Rejected("runner-owned-or-ambiguous")
            case _ => ExecutionCommitResult.Rejected("unprotected-or-unknown")
        }
      }
    }

  def observeChain(sessionId: String, executionId: String): IO[ExecutionChainObservation] = IO.blocking {
    monitor.synchronized {
      if closed || !validId(sessionId) || !validId(executionId) then
        ExecutionChainObservation.ReviewRequired(None, "authority-closed-or-invalid-identity")
      else
        try readChain(sessionId, executionId)._1
        catch
          case e: Exception =>
            ExecutionChainObservation.ReviewRequired(None, s"read-failed: ${e.getClass.getSimpleName}")
    }
  }

  def transition(command: ExecutionTransition): IO[ExecutionCommitResult] = IO.blocking {
    monitor.synchronized {
      command.event match
        case _: ExecutionEvent.ToolStarted | _: ExecutionEvent.ToolOutcome | ExecutionEvent.RunnerStopped |
            _: ExecutionEvent.TerminalInstallPrepared | _: ExecutionEvent.TerminalCommitted |
            _: ExecutionEvent.ResumeAuthorized =>
          ExecutionCommitResult.Rejected("runner-authority-required")
        case _: ExecutionEvent.ModelRequested | _: ExecutionEvent.ModelDecided | _: ExecutionEvent.ModelRetryCharged
            if runners.contains((command.sessionId, command.executionId)) =>
          ExecutionCommitResult.Rejected("runner-authority-required")
        case _: ExecutionEvent.CancelRequested if runners.contains((command.sessionId, command.executionId)) =>
          ExecutionCommitResult.Rejected("runner-authority-required")
        case _: ExecutionEvent.ToolPrepared
            if runners.get((command.sessionId, command.executionId)).exists(_.revoked) =>
          ExecutionCommitResult.Rejected("runner-revoked")
        case ExecutionEvent.AttemptStarted =>
          val result = transitionSync(command, false)
          if result.isInstanceOf[ExecutionCommitResult.Committed] then
            localStarts += ((command.sessionId, command.executionId, command.attemptId, command.incarnationId))
          result
        case _ => transitionSync(command, false)
    }
  }

  override def acceptInput(command: ExecutionTransition): IO[ExecutionCommitResult] = IO.blocking {
    monitor.synchronized {
      command.event match
        case _: ExecutionEvent.InputAccepted =>
          if closed || !validId(command.sessionId) || !validId(command.executionId) then
            ExecutionCommitResult.Rejected("authority-closed-or-invalid-identity")
          else
            try
              val (observation, records) = readChain(command.sessionId, command.executionId)
              observation match
                case ExecutionChainObservation.Verified(state) =>
                  records.find(_._1.commitId == command.commitId) match
                    case Some((_, payload)) =>
                      payload.as[ExecutionTransition] match
                        case Right(original) if original.copy(expected = command.expected) == command =>
                          transitionSync(original, false)
                        case _ => ExecutionCommitResult.Rejected("commit-id-conflict")
                    case None => transitionSync(command.copy(expected = state.ref), false)
                case ExecutionChainObservation.ReviewRequired(_, reason) =>
                  ExecutionCommitResult.Rejected(s"review-required: $reason")
            catch case e: Exception => ExecutionCommitResult.Rejected(s"input-read-failed: ${e.getClass.getSimpleName}")
        case _ => ExecutionCommitResult.Rejected("invalid-input-command")
    }
  }

  /**
   * Resume a process-reopened run from its verified durable checkpoint. This
   * method never invents quiescence for a live runner: an unrevoked owner with
   * outstanding work rejects the request. When no runner exists, the process
   * lifetime file lock proves the previous owner process is gone.
   */
  override def resumeInterrupted(
    request: ProtectedRecoveryRequest,
    configBinding: String
  ): IO[Either[String, ProtectedRecoveryResult]] = IO.blocking {
    monitor.synchronized {
      resumeInterruptedSync(request, configBinding)
    }
  }

  private def resumeInterruptedSync(
    request: ProtectedRecoveryRequest,
    configBinding: String
  ): Either[String, ProtectedRecoveryResult] =
    val key = (request.sessionId, request.executionId)
    val requestedAttempt = s"${request.requestId}-attempt"
    val requestedIncarnation = s"${request.requestId}-incarnation"
    if closed then Left("authority-closed")
    else if !validId(request.sessionId) || !validId(request.executionId) || !validId(request.requestId) ||
      request.principal.isEmpty
    then Left("invalid-recovery-request")
    else if uncertain.contains(key) || uncertainTransitions.contains(key) || uncertainTerminals.contains(key) then
      Left("commit-unknown")
    else
      try
        val (observation, records) = readChain(request.sessionId, request.executionId)
        observation match
          case ExecutionChainObservation.ReviewRequired(_, reason) => Left(s"review-required: $reason")
          case ExecutionChainObservation.Verified(state) =>
            val admission = records.head._1
            if admission.modelAuditBinding != configBinding then Left("config-binding-mismatch")
            else
              val existing = records.find(_._1.commitId == request.requestId)
              existing match
                case Some((record, payload)) =>
                  payload.as[ExecutionTransition] match
                    case Right(command)
                        if command.sessionId == request.sessionId &&
                          command.executionId == request.executionId &&
                          command.expected.recordHash == request.observed.recordHash &&
                          (command.event match
                            case ExecutionEvent.ResumeAuthorized(attempt, incarnation, principal) =>
                              attempt == requestedAttempt && incarnation == requestedIncarnation &&
                              principal == request.principal
                            case _ => false) =>
                      localStarts += ((request.sessionId, request.executionId, requestedAttempt, requestedIncarnation))
                      authorizedResumes += ((
                        request.sessionId,
                        request.executionId,
                        requestedAttempt,
                        requestedIncarnation
                      ))
                      Right(
                        ProtectedRecoveryResult(
                          request.sessionId,
                          request.executionId,
                          requestedAttempt,
                          requestedIncarnation,
                          state.ref,
                          duplicate = true
                        )
                      )
                    case _ => Left("idempotency-conflict")
                case None =>
                  val observedIndex = records.indexWhere { case (record, _) =>
                    record.recordHash == request.observed.recordHash
                  }
                  val ownIds = Set(
                    request.requestId,
                    s"${request.requestId}-suspend",
                    s"${request.requestId}-stopped",
                    s"${request.requestId}-start"
                  )
                  val intervening = if observedIndex >= 0 then records.drop(observedIndex + 1) else Nil
                  if observedIndex < 0 || intervening.exists { case (record, _) => !ownIds.contains(record.commitId) }
                  then Left("stale-observed-ref")
                  else if state.operations.values.exists(_ == "started") then
                    Left("unresolved-operation-review-required")
                  else if state.operations.nonEmpty then Left("pending-operation-review-required")
                  else if state.checkpoint.isEmpty then Left("checkpoint-unavailable")
                  else if state.terminalPayload.nonEmpty || state.terminalResultRef.nonEmpty then Left("terminal-run")
                  else if runners.get(key).exists(owner => !owner.revoked || owner.pending.nonEmpty) then
                    Left("runner-stop-unconfirmed")
                  else if !ProtectedAgentExecution.restore(nebflow.actor.AgentState(), state.checkpoint.get).isRight
                  then Left("checkpoint-unsupported")
                  else
                    var cursor = state
                    var failure: Option[String] = None
                    def step(
                      event: ExecutionEvent,
                      commitId: String,
                      phase: String,
                      stopped: Option[Boolean] = None
                    ): Unit =
                      if failure.isEmpty then
                        val command = ExecutionTransition(
                          request.sessionId,
                          request.executionId,
                          commitId,
                          commitId,
                          cursor.ref,
                          cursor.attemptId,
                          cursor.incarnationId,
                          event
                        )
                        transitionSync(command, false) match
                          case ExecutionCommitResult.Committed(ref) =>
                            cursor = cursor.copy(
                              ref = ref,
                              phase = phase,
                              runnerStopped = stopped.getOrElse(cursor.runnerStopped)
                            )
                          case ExecutionCommitResult.Duplicate(ref) =>
                            cursor = cursor.copy(
                              ref = ref,
                              phase = phase,
                              runnerStopped = stopped.getOrElse(cursor.runnerStopped)
                            )
                          case ExecutionCommitResult.Rejected(reason) => failure = Some(reason)
                          case ExecutionCommitResult.CommitUnknown(_, detail) =>
                            failure = Some(s"commit-unknown: $detail")
                          case other => failure = Some(s"unexpected-commit-result: $other")
                        end match
                        if failure.isEmpty then
                          event match
                            case ExecutionEvent.ResumeAuthorized(attempt, incarnation, _) =>
                              cursor = cursor.copy(attemptId = attempt, incarnationId = incarnation)
                            case _ => ()
                    if cursor.phase == "authorized" then ()
                    else if cursor.phase != "suspended" then
                      if Set("active", "model").contains(cursor.phase) then
                        step(ExecutionEvent.Suspended("process-reopen"), s"${request.requestId}-suspend", "suspended")
                      else failure = Some("resume-preconditions-unverified")
                    if failure.isEmpty && cursor.phase == "suspended" && !cursor.runnerStopped then
                      step(ExecutionEvent.RunnerStopped, s"${request.requestId}-stopped", "suspended", Some(true))
                    if failure.isEmpty && cursor.phase == "suspended" then
                      step(
                        ExecutionEvent.ResumeAuthorized(requestedAttempt, requestedIncarnation, request.principal),
                        request.requestId,
                        "authorized"
                      )
                    if failure.isEmpty && cursor.attemptId != requestedAttempt then
                      failure = Some("attempt-binding-mismatch")
                    if failure.isEmpty && cursor.incarnationId != requestedIncarnation then
                      failure = Some("incarnation-binding-mismatch")
                    if failure.isEmpty && cursor.phase == "authorized" then
                      step(ExecutionEvent.AttemptStarted, s"${request.requestId}-start", "active", Some(false))
                    failure match
                      case Some(reason) => Left(reason)
                      case None =>
                        localStarts += ((
                          request.sessionId,
                          request.executionId,
                          requestedAttempt,
                          requestedIncarnation
                        ))
                        authorizedResumes += ((
                          request.sessionId,
                          request.executionId,
                          requestedAttempt,
                          requestedIncarnation
                        ))
                        Right(
                          ProtectedRecoveryResult(
                            request.sessionId,
                            request.executionId,
                            requestedAttempt,
                            requestedIncarnation,
                            cursor.ref,
                            duplicate = false
                          )
                        )
                    end match
                  end if
              end match
            end if
        end match
      catch case e: Exception => Left(s"resume-read-failed: ${e.getClass.getSimpleName}")

    end if

  end resumeInterruptedSync

  override def commitRound(command: ExecutionTransition): IO[ExecutionCommitResult] = IO.blocking {
    monitor.synchronized {
      command.event match
        case _: ExecutionEvent.RoundCommitted =>
          if closed || !validId(command.sessionId) || !validId(command.executionId) then
            ExecutionCommitResult.Rejected("authority-closed-or-invalid-identity")
          else if runners.get((command.sessionId, command.executionId)).exists(_.revoked) then
            ExecutionCommitResult.Rejected("runner-revoked")
          else
            try
              val (observation, records) = readChain(command.sessionId, command.executionId)
              observation match
                case ExecutionChainObservation.Verified(state) =>
                  records.find(_._1.commitId == command.commitId) match
                    case Some((_, payload)) =>
                      payload.as[ExecutionTransition] match
                        case Right(original) if original.copy(expected = command.expected) == command =>
                          transitionSync(original, false)
                        case _ => ExecutionCommitResult.Rejected("commit-id-conflict")
                    case None =>
                      val inputOnly =
                        records.filter(_._1.revision > command.expected.revision).forall { case (_, payload) =>
                          payload
                            .as[ExecutionTransition]
                            .toOption
                            .exists(_.event.isInstanceOf[ExecutionEvent.InputAccepted])
                        }
                      val expectedExists = records.exists { case (record, _) =>
                        record.revision == command.expected.revision && record.recordHash == command.expected.recordHash
                      }
                      if !expectedExists || !inputOnly then ExecutionCommitResult.Rejected("stale-revision")
                      else transitionSync(command.copy(expected = state.ref), false)
                case ExecutionChainObservation.ReviewRequired(_, reason) =>
                  ExecutionCommitResult.Rejected(s"review-required: $reason")
              end match
            catch case e: Exception => ExecutionCommitResult.Rejected(s"round-read-failed: ${e.getClass.getSimpleName}")
        case _ => ExecutionCommitResult.Rejected("invalid-round-command")
    }
  }

  def repairTerminalProjection(
    sessionId: String,
    executionId: String,
    store: SubAgentTaskStore
  ): IO[Either[String, SubAgentTask]] = IO.blocking {
    monitor.synchronized {
      try
        val key = (sessionId, executionId)
        if closed || !validId(sessionId) || !validId(executionId) || uncertain.contains(key) ||
          uncertainTransitions.contains(key) || uncertainTerminals.contains(key)
        then Left("authority-unavailable")
        else
          readChain(sessionId, executionId) match
            case (ExecutionChainObservation.Verified(state), records) if state.phase == "terminal" =>
              (state.terminalPayload, state.terminalResultRef) match
                case (Some((payloadHash, payload)), Some(resultRef)) =>
                  val path = java.nio.file.Paths.get(resultRef)
                  checkPath(root, path)
                  val installed = parse(new String(Files.readAllBytes(path), UTF_8)).fold(throw _, identity)
                  if installed != payload || hash(canonical(installed)) != payloadHash then
                    Left("terminal-envelope-conflict")
                  else
                    confirm(path)
                    store.projectProtectedTerminalSync(records.head._1.ownerBinding, sessionId, executionId, resultRef)
                case _ => Left("terminal-reference-unavailable")
            case _ => Left("terminal-uncommitted")
        end if
      catch case e: Exception => Left(s"terminal-repair-failed: ${e.getClass.getSimpleName}")
    }
  }

  override def committedTerminal(sessionId: String, executionId: String): IO[Either[String, Json]] = IO.blocking {
    monitor.synchronized {
      try
        if closed || !validId(sessionId) || !validId(executionId) ||
          uncertain.contains((sessionId, executionId)) || uncertainTransitions.contains((sessionId, executionId)) ||
          uncertainTerminals.contains((sessionId, executionId))
        then throw new IllegalStateException("terminal-authority-unavailable")
        readChain(sessionId, executionId)._1 match
          case ExecutionChainObservation.Verified(state) if state.phase == "terminal" =>
            (state.terminalPayload, state.terminalResultRef) match
              case (Some((payloadHash, payload)), Some(resultRef)) =>
                val path = java.nio.file.Paths.get(resultRef)
                checkPath(root, path)
                val installed = parse(new String(Files.readAllBytes(path), UTF_8)).fold(throw _, identity)
                if installed != payload || hash(canonical(installed)) != payloadHash then
                  Left("terminal-envelope-conflict")
                else
                  confirm(path)
                  Right(payload)
              case _ => Left("terminal-reference-unavailable")
          case _ => Left("terminal-uncommitted")
      catch case e: Exception => Left(s"terminal-read-failed: ${e.getClass.getSimpleName}")
    }
  }

  private def installTerminalOwned(
    owner: OwnedRunner,
    command: ExecutionTransition,
    envelope: Json
  ): IO[ExecutionCommitResult] = IO.blocking {
    monitor.synchronized {
      val key = (command.sessionId, command.executionId)
      val payloadHash = hash(canonical(envelope))
      val proposed = command.copy(event = ExecutionEvent.TerminalInstallPrepared(payloadHash, envelope))
      val commitId = command.commitId + "-terminal"
      val requestId = command.requestId + "-terminal"
      val cursor = envelope.hcursor
      val validEnvelope = envelope.asObject.exists(
        _.keys.toSet == Set("source", "eventType", "payload", "metadata", "correlationId", "blocks")
      ) &&
        cursor.get[String]("source").isRight && cursor.get[String]("eventType").isRight &&
        cursor.get[String]("payload").isRight && cursor.get[JsonObject]("metadata").isRight &&
        cursor.get[Option[String]]("correlationId").isRight && cursor
          .get[Option[List[nebflow.shared.ContentBlock]]]("blocks")
          .isRight
      if !validEnvelope || !validId(commitId) || !validId(requestId) then
        ExecutionCommitResult.Rejected("invalid-terminal-payload")
      else if !runners.get(key).contains(owner) || owner.revoked || !matchesOwner(command, owner) then
        ExecutionCommitResult.Rejected("runner-authority-required")
      else if uncertainTerminals.get(key).exists(_ != ((command.commitId, envelope))) then
        ExecutionCommitResult.Rejected("commit-unknown")
      else
        try
          val (currentObservation, currentRecords) = readChain(command.sessionId, command.executionId)
          val original = currentRecords
            .find(_._1.commitId == command.commitId)
            .flatMap(_._2.as[ExecutionTransition].toOption)
          val prepared = original.filter(_.copy(expected = proposed.expected) == proposed).getOrElse(proposed)
          val currentAttempt = currentObservation match
            case ExecutionChainObservation.Verified(state) =>
              state.attemptId == command.attemptId && state.incarnationId == command.incarnationId
            case _ => false
          if !currentAttempt then throw new IllegalStateException("terminal-attempt-unverified")
          val pending = uncertainTransitions.get(key)
          val preparation =
            if pending.contains(prepared) then transitionSync(prepared, true)
            else
              pending match
                case Some(value)
                    if value.commitId == commitId && value.requestId == requestId &&
                      value.event == ExecutionEvent.TerminalCommitted(
                        payloadHash,
                        root
                          .resolve(command.sessionId)
                          .resolve(
                            s"terminal-${Base64.getUrlEncoder.withoutPadding().encodeToString(command.executionId.getBytes(UTF_8))}.json"
                          )
                          .toString
                      ) =>
                  val (observation, records) = readChain(command.sessionId, command.executionId)
                  if observation.isInstanceOf[ExecutionChainObservation.Verified] && records.exists {
                      case (record, payload) =>
                        record.revision == value.expected.revision && record.recordHash == value.expected.recordHash && payload == prepared.asJson
                    }
                  then ExecutionCommitResult.Duplicate(value.expected)
                  else ExecutionCommitResult.Rejected("terminal-preparation-unverified")
                case Some(_) => ExecutionCommitResult.Rejected("commit-unknown")
                case None => transitionSync(prepared, false)
          val result = preparation match
            case ExecutionCommitResult.Committed(ref) =>
              installPreparedTerminal(prepared, ref, envelope, payloadHash, commitId, requestId)
            case ExecutionCommitResult.Duplicate(ref) =>
              installPreparedTerminal(prepared, ref, envelope, payloadHash, commitId, requestId)
            case other => other
          result match
            case _: ExecutionCommitResult.Committed | _: ExecutionCommitResult.Duplicate => uncertainTerminals -= key
            case _: ExecutionCommitResult.CommitUnknown => uncertainTerminals += key -> ((command.commitId, envelope))
            case _ => ()
          result
        catch
          case e: Exception =>
            uncertainTerminals += key -> ((command.commitId, envelope))
            ExecutionCommitResult.CommitUnknown(command.commitId, s"terminal-install: ${e.getClass.getSimpleName}")
      end if
    }
  }

  private def installPreparedTerminal(
    prepared: ExecutionTransition,
    ref: ExecutionCommitRef,
    envelope: Json,
    payloadHash: String,
    commitId: String,
    requestId: String
  ): ExecutionCommitResult =
    val session = root.resolve(prepared.sessionId)
    val safeRun = Base64.getUrlEncoder.withoutPadding().encodeToString(prepared.executionId.getBytes(UTF_8))
    val path = session.resolve(s"terminal-$safeRun.json")
    checkPath(root, path)
    createDirectories(root, session)
    if Files.exists(path, LinkOption.NOFOLLOW_LINKS) then
      val existing = parse(new String(Files.readAllBytes(path), UTF_8)).fold(throw _, identity)
      if existing != envelope then return ExecutionCommitResult.Rejected("terminal-envelope-conflict")
      fault("before-terminal-confirm")
      confirm(path)
    else
      fault("before-terminal-install")
      install(path, envelope.noSpaces.getBytes(UTF_8), fault)
      fault("before-terminal-confirm")
      confirm(path)
    fault("after-terminal-confirm")
    val committed = prepared.copy(
      commitId = commitId,
      requestId = requestId,
      expected = ref,
      event = ExecutionEvent.TerminalCommitted(payloadHash, path.toString)
    )
    val result = transitionSync(
      committed,
      uncertainTransitions.get((prepared.sessionId, prepared.executionId)).contains(committed)
    )
    result match
      case _: ExecutionCommitResult.Committed => fault("before-terminal-acknowledgement")
      case _ => ()
    result

  end installPreparedTerminal

  def reconcileTransition(command: ExecutionTransition): IO[ExecutionCommitResult] = IO.blocking {
    monitor.synchronized {
      command.event match
        case ExecutionEvent.RunnerStopped | _: ExecutionEvent.TerminalInstallPrepared |
            _: ExecutionEvent.TerminalCommitted | _: ExecutionEvent.ResumeAuthorized |
            _: ExecutionEvent.ModelRetryCharged =>
          ExecutionCommitResult.Rejected("runner-authority-required")
        case _ => transitionSync(command, true)
    }
  }

  private def transitionSync(command: ExecutionTransition, reconcile: Boolean): ExecutionCommitResult =
    val key = (command.sessionId, command.executionId)
    if closed then ExecutionCommitResult.Rejected("authority-closed")
    else if !List(
        command.sessionId,
        command.executionId,
        command.commitId,
        command.requestId,
        command.attemptId,
        command.incarnationId
      ).forall(validId)
    then ExecutionCommitResult.Rejected("invalid-identity")
    else if uncertain.contains(key) || (uncertainTransitions.contains(key) &&
        (!reconcile || !uncertainTransitions.get(key).contains(command)))
    then ExecutionCommitResult.Rejected("commit-unknown")
    else
      var phase = "read"
      try
        val (observation, records) = readChain(command.sessionId, command.executionId)
        val existing = records.find(_._1.commitId == command.commitId)
        existing match
          case Some((record, payload)) =>
            if observation.isInstanceOf[ExecutionChainObservation.ReviewRequired] then
              ExecutionCommitResult.Rejected("review-required")
            else if payload != command.asJson then ExecutionCommitResult.Rejected("commit-id-conflict")
            else
              val dir = executionDir(root, command.sessionId, command.executionId)
              phase = "reconfirmation"
              confirm(dir.resolve("payloads").resolve(s"${record.payloadHash}.json"))
              confirm(dir.resolve("commits").resolve(f"${record.revision}%020d.json"))
              confirm(dir.resolve("intents").resolve(f"${record.revision}%020d.json"))
              uncertainTransitions -= key
              ExecutionCommitResult.Duplicate(ExecutionCommitRef(record.revision, record.recordHash))
          case None if reconcile => ExecutionCommitResult.CommitUnknown(command.commitId, "reconciliation-unconfirmed")
          case None =>
            observation match
              case ExecutionChainObservation.ReviewRequired(_, reason) =>
                ExecutionCommitResult.Rejected(s"review-required: $reason")
              case ExecutionChainObservation.Verified(state) =>
                val priorInput = command.event match
                  case ExecutionEvent.InputAccepted(id, content) =>
                    records.find { case (record, payload) =>
                      if record.revision == 1L then
                        payload.hcursor.get[String]("inputId").toOption.contains(id) &&
                        payload.hcursor.get[Json]("content").toOption.contains(content)
                      else
                        payload
                          .as[ExecutionTransition]
                          .toOption
                          .exists(_.event == ExecutionEvent.InputAccepted(id, content))
                    }
                  case _ => None
                val otherInput =
                  if priorInput.isEmpty && command.expected == state.ref then inputInAnotherRun(command) else None
                if command.attemptId != state.attemptId || command.incarnationId != state.incarnationId then
                  ExecutionCommitResult.Rejected("stale-attempt")
                else if records.exists(_._1.requestId == command.requestId) then
                  ExecutionCommitResult.Rejected("request-id-conflict")
                else if priorInput.nonEmpty then
                  val record = priorInput.get._1
                  val dir = executionDir(root, command.sessionId, command.executionId)
                  confirm(dir.resolve("payloads").resolve(s"${record.payloadHash}.json"))
                  confirm(dir.resolve("commits").resolve(f"${record.revision}%020d.json"))
                  if record.revision > 1L then confirm(dir.resolve("intents").resolve(f"${record.revision}%020d.json"))
                  ExecutionCommitResult.Duplicate(ExecutionCommitRef(record.revision, record.recordHash))
                else if command.expected != state.ref then ExecutionCommitResult.Rejected("stale-revision")
                else if otherInput.nonEmpty then otherInput.get
                else
                  nextState(state, command.event) match
                    case Left(reason) => ExecutionCommitResult.Rejected(reason)
                    case Right(next) =>
                      val dir = executionDir(root, command.sessionId, command.executionId)
                      val intents = dir.resolve("intents")
                      createDirectories(root, intents)
                      val revision = state.ref.revision + 1L
                      val payload = canonical(command.asJson)
                      val payloadHash = hash(payload)
                      val admission = records.head._1
                      val unsigned = ExecutionAdmissionRecord(
                        2,
                        command.sessionId,
                        command.executionId,
                        command.commitId,
                        revision,
                        state.ref.recordHash,
                        "Transition",
                        next.roundIndex,
                        command.requestId,
                        command.attemptId,
                        command.incarnationId,
                        admission.ownerBinding,
                        admission.businessBinding,
                        admission.modelAuditBinding,
                        payloadHash,
                        payload.length.toLong,
                        ""
                      )
                      val record =
                        unsigned.copy(recordHash = hash(canonical(unsigned.asJson.mapObject(_.remove("recordHash")))))
                      phase = "intent-install"
                      install(intents.resolve(f"$revision%020d.json"), payload, fault)
                      phase = "payload-install"
                      install(dir.resolve("payloads").resolve(s"$payloadHash.json"), payload, fault)
                      phase = "record-install"
                      install(dir.resolve("commits").resolve(f"$revision%020d.json"), canonical(record.asJson), fault)
                      phase = "acknowledgement"
                      fault("before-acknowledgement")
                      ExecutionCommitResult.Committed(ExecutionCommitRef(revision, record.recordHash))
                end if
        end match
      catch
        case e: Exception =>
          uncertainTransitions += (key -> command)
          ExecutionCommitResult.CommitUnknown(command.commitId, s"$phase: ${e.getClass.getSimpleName}")

      end try

    end if

  end transitionSync

  private def inputInAnotherRun(command: ExecutionTransition): Option[ExecutionCommitResult] = command.event match
    case ExecutionEvent.InputAccepted(id, content) =>
      val executions = root.resolve(command.sessionId).resolve("executions")
      checkPath(root, executions)
      if !Files.isDirectory(executions, LinkOption.NOFOLLOW_LINKS) then
        throw new IllegalStateException("execution directory unavailable")
      val stream = Files.list(executions)
      try
        val iterator = stream.iterator()
        var found: Option[ExecutionCommitResult] = None
        while iterator.hasNext do
          val path = iterator.next()
          if path != executionDir(
              root,
              command.sessionId,
              command.executionId
            ) && path.getFileName.toString != "administration"
          then
            checkPath(root, path)
            if !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) then
              throw new IllegalStateException("invalid execution entry")
            val name = path.getFileName.toString
            val bytes = Base64.getUrlDecoder.decode(name)
            val run = new String(bytes, UTF_8)
            if !validId(run) || executionDir(root, command.sessionId, run) != path then
              throw new IllegalStateException("invalid execution identity")
            val (observation, records) = readChain(command.sessionId, run)
            observation match
              case ExecutionChainObservation.Verified(state) =>
                state.inputs.get(id).foreach { existing =>
                  if found.nonEmpty then throw new IllegalStateException("duplicate session input identity")
                  if existing != content then found = Some(ExecutionCommitResult.Rejected("session-input-conflict"))
                  else
                    val record = records
                      .find { case (record, payload) =>
                        if record.revision == 1L then payload.hcursor.get[String]("inputId").toOption.contains(id)
                        else
                          payload
                            .as[ExecutionTransition]
                            .toOption
                            .exists(_.event == ExecutionEvent.InputAccepted(id, content))
                      }
                      .getOrElse(throw new IllegalStateException("input receipt unavailable"))
                      ._1
                    found = Some(
                      ExecutionCommitResult.DuplicateInput(run, ExecutionCommitRef(record.revision, record.recordHash))
                    )
                  end if
                }
              case ExecutionChainObservation.ReviewRequired(_, _) =>
                throw new IllegalStateException("session execution requires review")
            end match
          end if
        end while
        found
      finally stream.close()
      end try
    case _ => None

  private def readChain(
    sessionId: String,
    executionId: String
  ): (ExecutionChainObservation, List[(ExecutionAdmissionRecord, Json)]) =
    observeSync(sessionId, executionId) match
      case ExecutionAdmissionObservation.Verified(first, input) =>
        val dir = executionDir(root, sessionId, executionId)
        val initialId = input.hcursor.get[String]("inputId").fold(throw _, identity)
        val initialContent = input.hcursor.get[Json]("content").fold(throw _, identity)
        if !validId(initialId) then throw new IllegalStateException("invalid initial input identity")
        var state = ExecutionChainState(
          ExecutionCommitRef(1L, first.recordHash),
          first.attemptId,
          first.incarnationId,
          0L,
          "admitted",
          Map(initialId -> initialContent),
          List(initialId),
          Map.empty,
          Map.empty,
          0L,
          0,
          false
        )
        var records = List((first, input))
        def names(path: Path): List[String] =
          if !Files.exists(path, LinkOption.NOFOLLOW_LINKS) then Nil
          else
            checkPath(root, path)
            val stream = Files.list(path)
            try
              val iterator = stream.iterator()
              val builder = List.newBuilder[String]
              while iterator.hasNext do builder += iterator.next().getFileName.toString
              builder.result().sorted
            finally stream.close()
        val commitNames = names(dir.resolve("commits"))
        val intentNames = names(dir.resolve("intents"))
        var problem: Option[String] = None
        var revision = 2L
        while revision <= commitNames.size && problem.isEmpty do
          val name = f"$revision%020d.json"
          try
            if !commitNames.contains(name) || !intentNames.contains(name) then throw new IllegalStateException("gap")
            val path = dir.resolve("commits").resolve(name)
            checkPath(root, path)
            val bytes = Files.readAllBytes(path)
            val record = parse(new String(bytes, UTF_8)).flatMap(_.as[ExecutionAdmissionRecord]).fold(throw _, identity)
            if record.schemaVersion != 2 || record.revision != revision || record.previousHash != state.ref.recordHash ||
              record.sessionId != sessionId || record.executionId != executionId || record.event != "Transition" ||
              record.ownerBinding != first.ownerBinding || record.businessBinding != first.businessBinding ||
              record.modelAuditBinding != first.modelAuditBinding || !validRecordBindings(record) ||
              !validHash(record.payloadHash) || record.recordHash != hash(
                canonical(record.asJson.mapObject(_.remove("recordHash")))
              ) ||
              !java.util.Arrays.equals(bytes, canonical(record.asJson))
            then throw new IllegalStateException("invalid-record")
            val payloadPath = dir.resolve("payloads").resolve(s"${record.payloadHash}.json")
            val intentPath = dir.resolve("intents").resolve(name)
            checkPath(root, payloadPath)
            checkPath(root, intentPath)
            val payload = Files.readAllBytes(payloadPath)
            if payload.length.toLong != record.payloadLength || hash(payload) != record.payloadHash ||
              !java.util.Arrays.equals(payload, Files.readAllBytes(intentPath))
            then throw new IllegalStateException("invalid-payload")
            val json = parse(new String(payload, UTF_8)).fold(throw _, identity)
            val command = json.as[ExecutionTransition].fold(throw _, identity)
            if !java.util.Arrays.equals(payload, canonical(json)) || command.sessionId != sessionId ||
              command.executionId != executionId || command.commitId != record.commitId || command.requestId != record.requestId ||
              command.expected != state.ref || command.attemptId != state.attemptId || command.incarnationId != state.incarnationId ||
              record.attemptId != command.attemptId || record.incarnationId != command.incarnationId ||
              records.exists { case (r, _) => r.commitId == record.commitId || r.requestId == record.requestId }
            then throw new IllegalStateException("invalid-command")
            command.event match
              case ExecutionEvent.TerminalCommitted(_, resultRef) =>
                val safeRun = Base64.getUrlEncoder.withoutPadding().encodeToString(executionId.getBytes(UTF_8))
                if resultRef != root.resolve(sessionId).resolve(s"terminal-$safeRun.json").toString then
                  throw new IllegalStateException("invalid-terminal-reference")
              case _ => ()
            val next = nextState(state, command.event).fold(reason => throw new IllegalStateException(reason), identity)
            if next.roundIndex != record.roundIndex then throw new IllegalStateException("invalid-round")
            state = next.copy(ref = ExecutionCommitRef(revision, record.recordHash))
            records = records :+ (record -> json)
          catch case e: Exception => problem = Some(s"invalid-revision-$revision: ${e.getClass.getSimpleName}")
          end try
          revision += 1L
        end while
        if problem.isEmpty && (commitNames.size != state.ref.revision || intentNames.size != state.ref.revision - 1L)
        then problem = Some("unexpected-record-or-unresolved-intent")
        if problem.isEmpty then
          try
            records.foreach { case (record, _) =>
              fault("before-chain-reconfirmation")
              confirm(dir.resolve("payloads").resolve(s"${record.payloadHash}.json"))
              confirm(dir.resolve("commits").resolve(f"${record.revision}%020d.json"))
              if record.revision > 1L then confirm(dir.resolve("intents").resolve(f"${record.revision}%020d.json"))
            }
            var ancestor = dir
            while ancestor != root do
              forceDirectory(ancestor)
              ancestor = ancestor.getParent
            forceDirectory(root)
          catch case e: Exception => problem = Some(s"reconfirmation-failed: ${e.getClass.getSimpleName}")
        val observation = problem match
          case Some(reason) => ExecutionChainObservation.ReviewRequired(Some(state), reason)
          case None => ExecutionChainObservation.Verified(state)
        (observation, records)
      case ExecutionAdmissionObservation.Missing =>
        (ExecutionChainObservation.ReviewRequired(None, "missing-admission"), Nil)
      case ExecutionAdmissionObservation.ReviewRequired(reason) =>
        (ExecutionChainObservation.ReviewRequired(None, reason), Nil)

  private def nextState(state: ExecutionChainState, event: ExecutionEvent): Either[String, ExecutionChainState] =
    def reject = Left("illegal-transition")
    event match
      case ExecutionEvent.InputAccepted(id, content)
          if validId(id) && !state.inputs.contains(id) &&
            state.phase != "authorized" && state.phase != "cancelled" && state.phase != "terminal-prepared" && state.phase != "terminal" =>
        Right(state.copy(inputs = state.inputs.updated(id, content), pendingInputs = state.pendingInputs :+ id))
      case ExecutionEvent.AttemptStarted if state.phase == "admitted" || state.phase == "authorized" =>
        Right(state.copy(phase = "active", runnerStopped = false))
      case ExecutionEvent.ModelRequested(_, limit) if state.phase == "active" && limit >= 0 =>
        Right(state.copy(phase = "model", retryLimit = limit))
      case ExecutionEvent.ModelRetryCharged(category)
          if state.phase == "model" && category == "classified-transient-provider" &&
            state.retryCharges < state.retryLimit.toLong =>
        Right(state.copy(retryCharges = state.retryCharges + 1L))
      case ExecutionEvent.ModelRetryCharged(_) => Left("retry-classification-or-budget-invalid")
      case ExecutionEvent.ModelDecided(decision)
          if state.phase == "model" &&
            decision.calls.map(_.operationId).distinct.size == decision.calls.size &&
            decision.calls.map(_.modelCallId).distinct.size == decision.calls.size &&
            decision.calls
              .forall(call => validId(call.operationId) && validId(call.modelCallId) && call.tool.nonEmpty) =>
        Right(
          state.copy(
            phase = "decided",
            operations = decision.calls.map(_.operationId -> "declared").toMap,
            intents = decision.calls.map(call => call.operationId -> call).toMap,
            decision = Some(decision),
            outcomes = Map.empty
          )
        )
      case ExecutionEvent.ToolPrepared(id, intent)
          if state.phase == "decided" &&
            state.operations.get(id).contains("declared") && state.intents.get(id).exists(_.asJson == intent) =>
        Right(state.copy(operations = state.operations.updated(id, "prepared")))
      case ExecutionEvent.ToolStarted(id)
          if state.phase == "decided" && state.operations.get(id).contains("prepared") &&
            state.operations.values.forall(_ != "declared") =>
        Right(state.copy(operations = state.operations.updated(id, "started")))
      case ExecutionEvent.ToolOutcome(id, result)
          if state.phase == "decided" && state.operations.get(id).contains("started") =>
        Right(
          state.copy(
            operations = state.operations.updated(id, "outcome"),
            outcomes = state.outcomes.updated(id, result)
          )
        )
      case ExecutionEvent.RoundCommitted(ids, checkpoint)
          if state.phase == "decided" && state.roundIndex < Long.MaxValue &&
            ids.distinct == ids && ids
              .forall(state.pendingInputs.contains) && state.operations.values.forall(_ == "outcome") =>
        Right(
          state.copy(
            phase = "active",
            roundIndex = state.roundIndex + 1L,
            pendingInputs = state.pendingInputs.filterNot(ids.contains),
            operations = Map.empty,
            intents = Map.empty,
            decision = None,
            outcomes = Map.empty,
            checkpoint = Some(checkpoint)
          )
        )
      case ExecutionEvent.TerminalInstallPrepared(payloadHash, envelope)
          if state.phase == "active" && state.pendingInputs.isEmpty && validHash(payloadHash) &&
            hash(canonical(envelope)) == payloadHash =>
        Right(state.copy(phase = "terminal-prepared", terminalPayload = Some((payloadHash, envelope))))
      case ExecutionEvent.TerminalCommitted(payloadHash, resultRef)
          if state.phase == "terminal-prepared" && state.terminalPayload.exists(
            _._1 == payloadHash
          ) && resultRef.nonEmpty =>
        Right(state.copy(phase = "terminal", terminalResultRef = Some(resultRef)))
      case ExecutionEvent.Suspended(reason)
          if reason.nonEmpty && state.phase != "suspended" && state.phase != "cancelled" &&
            state.phase != "terminal-prepared" && state.phase != "terminal" =>
        Right(state.copy(phase = "suspended"))
      case ExecutionEvent.CancelRequested(reason)
          if reason.nonEmpty && state.phase != "cancelled" &&
            state.phase != "terminal-prepared" && state.phase != "terminal" =>
        Right(state.copy(phase = "cancelled"))
      case ExecutionEvent.RunnerStopped if state.phase == "suspended" => Right(state.copy(runnerStopped = true))
      case ExecutionEvent.ResumeAuthorized(attempt, incarnation, principal)
          if state.phase == "suspended" && state.runnerStopped && state.checkpoint.nonEmpty &&
            state.operations.isEmpty && validId(attempt) && validId(incarnation) &&
            attempt != state.attemptId && incarnation != state.incarnationId && principal.nonEmpty =>
        Right(
          state.copy(
            phase = "authorized",
            attemptId = attempt,
            incarnationId = incarnation,
            runnerStopped = false,
            operations = Map.empty,
            intents = Map.empty,
            decision = None,
            outcomes = Map.empty
          )
        )
      case _ => reject

    end match

  end nextState

  private[agent] def close(): IO[Unit] = admissionGate.permit.use { _ =>
    IO.uncancelable { poll =>
      IO.blocking {
        monitor.synchronized {
          if closed then None
          else
            closed = true
            runners.values.foreach(_.revoked = true)
            Some(runners.values.toList.map(owner => (owner.runner, owner.pending.toList)))
        }
      }.flatMap {
        case None => IO.unit
        case Some(owned) =>
          val release = owned.traverse_ { case (runner, pending) =>
            pending.traverse_(_.get) *> runner.awaitStoppedUnbounded.flatMap {
              case true => IO.unit
              case false => IO.raiseError(new IllegalStateException("runner-stop-unconfirmed"))
            }
          } *> IO.blocking {
            monitor.synchronized {
              if channel.isOpen then
                try lock.release()
                finally channel.close()
            }
          }
          owned.traverse_(_._1.close) *> release.start.flatMap { reaper =>
            poll(reaper.joinWithNever).timeoutTo(1.second, IO.unit)
          }
      }
    }
  }

end ExecutionCommitStore

object ExecutionCommitStore:
  private final class ParentAdmissionClosed extends Exception

  def resource(sessionsDir: Path): Resource[IO, ExecutionCommitStore] =
    resourceWithFault(sessionsDir, _ => ())

  private[agent] def resourceWithFault(sessionsDir: Path, fault: String => Unit): Resource[IO, ExecutionCommitStore] =
    Resource.eval(Semaphore[IO](1)).flatMap { admissionGate =>
      Resource.make(IO.blocking {
        // OS 级祖先符号链接（macOS /var → /private/var）是机器拓扑不是篡改面：
        // 先把根归一到真身，逐组件反符号链接检查在规范形上照常执行。
        val normalized = sessionsDir.toAbsolutePath.normalize()
        val root =
          if Files.exists(normalized, LinkOption.NOFOLLOW_LINKS) then normalized.toRealPath() else normalized
        if !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root) then
          throw new IllegalArgumentException("sessions directory must already exist and not be a symlink")
        checkAbsolutePath(root)
        val lockPath = root.resolve("execution-authority.lock")
        if Files.isSymbolicLink(lockPath) then throw new IllegalArgumentException("symlink lock")
        val channel =
          FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        try
          val lock = channel.tryLock()
          if lock == null then throw new IllegalStateException("authority already owned")
          try
            channel.force(true)
            forceDirectory(root)
            new ExecutionCommitStore(root, channel, lock, fault, admissionGate)
          catch
            case e: Exception =>
              lock.release()
              throw e
        catch
          case e: Exception =>
            channel.close()
            throw e
        end try
      })(_.close())
    }

  private def validId(value: String): Boolean =
    value.nonEmpty && value.length <= 200 && value.forall(c => c.isLetterOrDigit || c == '-' || c == '_')

  private def validHash(value: String): Boolean = value.matches("[0-9a-f]{64}")

  private def validAdmission(a: ExecutionAdmission): Boolean =
    List(a.sessionId, a.executionId, a.commitId, a.requestId, a.attemptId, a.incarnationId, a.initialInputId).forall(
      validId
    ) &&
      validBindings(a.ownerBinding, a.businessBinding, a.modelAuditBinding)

  private def validRecordBindings(record: ExecutionAdmissionRecord): Boolean =
    List(
      record.sessionId,
      record.executionId,
      record.commitId,
      record.requestId,
      record.attemptId,
      record.incarnationId
    )
      .forall(validId) && validBindings(record.ownerBinding, record.businessBinding, record.modelAuditBinding)

  private def validBindings(owner: String, business: String, audit: String): Boolean =
    List(owner, business, audit).forall(value => value.nonEmpty && value.length <= 4096)

  private def parentClosurePath(root: Path, parentId: String): Path =
    val encoded = hash(parentId.getBytes(UTF_8))
    root.resolve(s"execution-closed-$encoded.json")

  private def executionDir(root: Path, sessionId: String, executionId: String): Path =
    val run = Base64.getUrlEncoder.withoutPadding().encodeToString(executionId.getBytes(UTF_8))
    root.resolve(sessionId).resolve("executions").resolve(run)

  private def checkAbsolutePath(path: Path): Unit =
    var current = path.getRoot
    val parts = path.iterator()
    while parts.hasNext do
      current = current.resolve(parts.next())
      if Files.isSymbolicLink(current) then throw new IllegalArgumentException("symlink path")

  private def checkPath(root: Path, path: Path): Unit =
    if !path.normalize().startsWith(root) then throw new IllegalArgumentException("path escape")
    var current = root
    val parts = root.relativize(path).iterator()
    while parts.hasNext do
      current = current.resolve(parts.next())
      if Files.isSymbolicLink(current) then throw new IllegalArgumentException("symlink path")

  private def createDirectories(root: Path, path: Path): Unit =
    checkPath(root, path)
    var current = root
    val parts = root.relativize(path).iterator()
    while parts.hasNext do
      val parent = current
      current = current.resolve(parts.next())
      if !Files.exists(current, LinkOption.NOFOLLOW_LINKS) then
        Files.createDirectory(current)
        forceDirectory(parent)
      if !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) then
        throw new IllegalArgumentException("not a directory")

  /**
   * Directory fsync — a POSIX capability: forcing the directory entry makes a
   * create/rename durable. Windows cannot open a DIRECTORY as a `FileChannel`
   * at all (`AccessDeniedException: …\sessions`), so on Windows this leg is a
   * no-op rather than a failure.
   *
   * Best-effort by contract, never a correctness gate. Two independent reasons:
   *   1. the same call sites (`AtomicJson.forceParentDir`,
   *      `SubAgentTaskStore.writeTasksSync`) already treat it as best-effort,
   *      and durability there still holds — the file bytes are forced, only the
   *      directory entry is advisory;
   *   2. as an exception source it exceeded its station: a bare throw here
   *      aborted the whole first-run config path on Windows
   *      (`MainBoot.ensureFirstRunConfig` → `shell.scala:1665` fatal), i.e. the
   *      engine could not boot at all on that platform (2026-11-21 E2E, defect
   *      #3), and in the reconfirmation face it would permanently mark every
   *      chain `review-required` on a platform that can never satisfy it.
   * Strict legs are untouched: `confirm` (file open + force) still throws.
   */
  private[agent] def forceDirectory(path: Path): Unit =
    if directoryForceSupported(sys.props.getOrElse("os.name", "")) then
      try
        val channel = FileChannel.open(path, StandardOpenOption.READ)
        try channel.force(true)
        finally channel.close()
      catch case _: Exception => ()

  /** Pure capability predicate (spec-drivable, no filesystem): only POSIX can open a directory. */
  private[agent] def directoryForceSupported(osName: String): Boolean =
    !Option(osName).getOrElse("").toLowerCase.contains("win")

  private def confirm(path: Path): Unit =
    val channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
    try channel.force(true)
    finally channel.close()
    forceDirectory(path.getParent)

  private def install(path: Path, bytes: Array[Byte], fault: String => Unit): Unit =
    if Files.exists(path, LinkOption.NOFOLLOW_LINKS) then
      if Files.isSymbolicLink(path) || !java.util.Arrays.equals(Files.readAllBytes(path), bytes) then
        throw new IllegalStateException("immutable collision")
      confirm(path)
    else
      val temp = Files.createTempFile(path.getParent, ".execution-", ".tmp")
      val channel = FileChannel.open(temp, StandardOpenOption.WRITE)
      try
        val buffer = ByteBuffer.wrap(bytes)
        while buffer.hasRemaining do channel.write(buffer)
        channel.force(true)
        fault("after-file-force")
      finally channel.close()
      Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE)
      fault("after-install")
      forceDirectory(path.getParent)
      fault("after-directory-force")

  private def hash(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  private def canonical(json: Json): Array[Byte] =
    def sorted(value: Json): Json = value.arrayOrObject(
      value,
      values => Json.fromValues(values.map(sorted)),
      obj => Json.fromJsonObject(JsonObject.fromIterable(obj.toList.sortBy(_._1).map { case (k, v) => k -> sorted(v) }))
    )
    sorted(json).noSpaces.getBytes(UTF_8)
end ExecutionCommitStore
