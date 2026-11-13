package nebflow.core.delegate

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.circe.syntax.*
import nebflow.core.AgentRuntimePort
import nebflow.shared.{Message, MessageRole, NebflowLogger, PathUtil, SubAgentTask}
import java.nio.channels.{FileChannel, OverlappingFileLockException}
import java.nio.file.StandardOpenOption

/** Durable creation intent plus a process-independent session-start lease. Never starts an actor. */
object MergerInitialization:
  final class SessionBusy extends IllegalStateException("Session initialization or recovery is already in progress")

  private val logger = NebflowLogger.forName("nebflow.merger-initialization")

  def exclusive[A](id: String)(action: IO[A]): IO[A] =
    Resource
      .make(IO.blocking {
        require(id.matches("[A-Za-z0-9._-]+"), "Invalid session identity")
        val directory = PathUtil.dataRoot / "workspaces"
        os.makeDir.all(directory)
        val channel =
          FileChannel.open((directory / s"start-$id.lock").toNIO, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        try
          val lock = channel.tryLock()
          if lock == null then throw new SessionBusy
          (channel, lock)
        catch
          case error: OverlappingFileLockException =>
            channel.close()
            throw new SessionBusy
          case error: Throwable => channel.close(); throw error
      }) { case (channel, lock) =>
        IO.blocking {
          try lock.release()
          finally channel.close()
        }
      }
      .use(_ => action)

  /**
   * Start-lease occupancy probe (read-only, never blocks): true when the session's
   * start lock is currently held by anyone (this JVM included — Java file locks are
   * JVM-wide, so a same-process holder shows up as an overlapping-lock refusal).
   * Consumers use it as a "session start/revival is in flight" signal (delegate
   * completion-gate defer; busy checks). Probe failure (IO error) reads as held =
   * conservative side.
   */
  def isHeld(id: String): Boolean =
    try
      val directory = PathUtil.dataRoot / "workspaces"
      os.makeDir.all(directory)
      val channel =
        FileChannel.open((directory / s"start-$id.lock").toNIO, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
      try
        val lock = channel.tryLock()
        if lock == null then true
        else
          lock.release()
          false
      catch
        case _: OverlappingFileLockException => true
        case _: Throwable => true
      finally channel.close()
    catch case _: Throwable => true

  /** Caller holds exclusive(id). Restores only missing metadata, never overwrites a transcript. */
  def prepareLocked(resources: AgentRuntimePort, id: String): IO[Unit] =
    IO.blocking(WorkspaceLedger.default.snapshot.mergerBootstraps.get(id)).flatMap {
      case None => IO.unit
      case Some(seed) =>
        for
          _ <- IO(require(seed.record.id == id && seed.record.mode.contains("merger"), "Invalid merger bootstrap"))
          transcript <- resources.sessionStore.loadMessagesForSession(id)
          _ <-
            if transcript.nonEmpty then IO.unit
            else if seed.everStarted then
              IO.raiseError(new IllegalStateException("Saved merger history is missing; manual inspection is required"))
            else resources.sessionStore.durableSwapMessages(id, List(Message(MessageRole.User, Left(seed.prompt))))
          _ <- IO.blocking {
            if DelegateRegistry.find(id).isEmpty then
              DelegateRegistry.put(
                seed.record.copy(
                  frozenSince = Some(System.currentTimeMillis()),
                  frozenReason = Some(DelegateFreeze.wireValue(DelegateFreeze.Reason.EngineFault)),
                  frozenDetail = Some("Merger initialization needs explicit manual recovery")
                )
              )
          }
          row <- resources.subAgentTaskStore.findByTaskId(id)
          _ <-
            if row.nonEmpty then IO.unit
            else
              resources.subAgentTaskStore.recordTask(
                SubAgentTask(
                  taskId = id,
                  parentSessionId = seed.record.parentSessionId,
                  agentName = seed.record.executor,
                  prompt = seed.prompt,
                  description = seed.record.title,
                  status = "frozen",
                  retryCount = 0,
                  spawnedAt = seed.record.createdAt,
                  completedAt = None,
                  lastError = seed.error,
                  source = "delegate",
                  mode = Some("merger")
                )
              )
        yield ()
    }

  def failed(resources: AgentRuntimePort, id: String, detail: String): IO[Unit] =
    val error = DelegateFreeze.compactDetail(detail)
    IO.blocking(WorkspaceLedger.default.snapshot.mergerBootstraps.get(id)).flatMap {
      case Some(seed) =>
        IO.blocking {
          WorkspaceLedger.default.markMergerInitialization(id, "failed", Some(error))
          DelegateRegistry.parkFrozen(id, DelegateFreeze.wireValue(DelegateFreeze.Reason.EngineFault), error)
        } *> resources.subAgentTaskStore.updateStatus(
          seed.record.parentSessionId,
          id,
          "frozen",
          lastError = Some(error)
        )
      case None => IO.unit
    }
  end failed

  def ready(id: String): IO[Unit] = IO.blocking(WorkspaceLedger.default.markMergerInitialization(id, "ready", None))

  /** Recovery of execution metadata only; execution itself still requires an explicit wake. */
  def repairLocked(resources: AgentRuntimePort, id: String): IO[Unit] =
    resources.agentRegistry.get.flatMap { live =>
      if live.contains(id) then IO.unit
      else
        // Read-only startup reconciliation of this merger's in-flight requests:
        // the structured reading is logged for the actor and the panel; nothing
        // auto-continues (recovery stays manual). Failures never block recovery.
        val reconcile = IO
          .blocking(RequestReconcile.forMerger(id))
          .flatMap { readings =>
            readings.traverse_ { reading =>
              IO.whenA(reading.items.nonEmpty)(
                IO(logger.info(s"Merger $id startup reconcile (read-only): ${reading.asJson.noSpaces}"))
              )
            }
          }
          .handleErrorWith(error =>
            IO(logger.warn(s"Merger $id startup reconcile could not read requests: ${error.getMessage}"))
          )
        reconcile *> IO.blocking(WorkspaceLedger.default.snapshot.mergerBootstraps.get(id)).flatMap {
          case Some(seed) if seed.status != "ready" || DelegateRegistry.find(id).isEmpty =>
            val needsFreeze = seed.status == "initializing" || !DelegateRegistry.isFrozen(id)
            prepareLocked(
              resources,
              id
            ) *> (if !needsFreeze then IO.unit
                  else
                    failed(
                      resources,
                      id,
                      seed.error
                        .getOrElse("Merger initialization was interrupted; use explicit manual recovery to continue")
                    ))
          case _ => IO.unit
        }
    }

  def repair(resources: AgentRuntimePort, id: String): IO[Unit] = exclusive(id)(repairLocked(resources, id))

  def repairAll(resources: AgentRuntimePort): IO[Unit] =
    IO.blocking(WorkspaceLedger.default.snapshot.mergerBootstraps.keys.toList)
      .flatMap(_.traverse_ { id =>
        repair(resources, id)
          .handleErrorWith(error => logger.warn(s"Merger $id metadata recovery deferred: ${error.getMessage}"))
      })
end MergerInitialization
