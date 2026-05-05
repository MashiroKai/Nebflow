package nebflow.core.tools

import cats.effect.*
import cats.effect.std.Mutex
import cats.syntax.all.*

import java.io.{BufferedReader, File, InputStreamReader}
import java.nio.charset.StandardCharsets

import scala.concurrent.TimeoutException
import scala.concurrent.duration.*
import scala.util.Using

/** Background job managed by cats-effect Fiber + Deferred */
private case class BackgroundJob(
  fiber: Fiber[IO, Throwable, Unit],
  deferred: Deferred[IO, Either[Throwable, ProcessResult]],
  command: String
):
  def isComplete: IO[Boolean] = deferred.tryGet.map(_.isDefined)

/** Per-session shell executor using pure cats-effect IO */
final class ShellSession private (
  val sessionId: String,
  currentDir: Ref[IO, String],
  backgroundJobs: Ref[IO, Map[String, BackgroundJob]],
  private val cleanupFiber: Fiber[IO, Throwable, Unit],
  private val lastAccessed: Ref[IO, Long],
  private val isAlive: Ref[IO, Boolean],
  private val lifecycleMutex: Mutex[IO]
):

  private val SessionTTL = 30.minutes.toMillis

  def getCurrentDir: IO[String] = currentDir.get

  private[tools] def touch: IO[Unit] =
    Clock[IO].realTime.map(_.toMillis).flatMap(lastAccessed.set)

  private[tools] def isStale: IO[Boolean] =
    for
      now <- Clock[IO].realTime.map(_.toMillis)
      last <- lastAccessed.get
    yield now - last > SessionTTL

  private def checkAlive: IO[Unit] =
    isAlive.get.flatMap {
      case true => IO.unit
      case false => IO.raiseError(new IllegalStateException("Session has been destroyed"))
    }

  /**
   * Execute a command synchronously, updating cwd afterwards via pwd.
   *  If pwd fails (e.g. old cwd was deleted), currentDir is left unchanged.
   */
  def execute(command: String, timeout: FiniteDuration): IO[ProcessResult] =
    for
      _ <- checkAlive *> touch
      cwd <- currentDir.get
      result <- runProcess(command, cwd, timeout)
      newCwd <- runProcess("pwd", cwd, 5.seconds).attempt.map {
        case Right(r) => r.stdout.trim
        case Left(_) => cwd // keep old cwd if pwd fails
      }
      _ <- currentDir.set(newCwd)
    yield result.copy(cwd = newCwd)

  /** Start a background job and return its job ID */
  def executeBackground(command: String, timeout: FiniteDuration): IO[String] =
    lifecycleMutex.lock.surround {
      for
        _ <- checkAlive *> touch
        jobId <- IO.randomUUID.map(_.toString.take(8))
        deferred <- Deferred[IO, Either[Throwable, ProcessResult]]
        fiber <- backgroundExecute(command, timeout, deferred).start
        job = BackgroundJob(fiber, deferred, command)
        _ <- backgroundJobs.update(_ + (jobId -> job))
      yield jobId
    }

  /** Query a background job. If complete, remove it and return the result. */
  def getBackgroundResult(jobId: String): IO[Option[Either[Throwable, ProcessResult]]] =
    lifecycleMutex.lock.surround {
      for
        _ <- checkAlive *> touch
        res <- backgroundJobs.get.map(_.get(jobId)).flatMap {
          case None => IO.pure(None)
          case Some(job) =>
            job.deferred.tryGet.flatMap {
              case None => IO.pure(None)
              case Some(result) =>
                backgroundJobs.update(_ - jobId).as(Some(result))
            }
        }
      yield res
    }

  /** List background jobs with completion status */
  def listBackgroundJobs(): IO[List[(String, Boolean, String)]] =
    lifecycleMutex.lock.surround {
      for
        _ <- checkAlive *> touch
        jobs <- backgroundJobs.get
        res <- jobs.toList.traverse { case (id, job) =>
          job.isComplete.map((id, _, job.command))
        }
      yield res
    }

  /**
   * Cancel a background job by its ID. Returns false if the job is not found
   *  or has already completed (result should be retrieved via getBackgroundResult).
   */
  def cancelBackgroundJob(jobId: String): IO[Boolean] =
    lifecycleMutex.lock.surround {
      for
        _ <- checkAlive *> touch
        res <- backgroundJobs.get.map(_.get(jobId)).flatMap {
          case None => IO.pure(false)
          case Some(job) =>
            job.isComplete.flatMap {
              case true => IO.pure(false)
              case false =>
                job.deferred.complete(Left(new InterruptedException("Cancelled"))).attempt.void *>
                  job.fiber.cancel *>
                  backgroundJobs.update(_ - jobId).as(true)
            }
        }
      yield res
    }

  /**
   * Kill this session: cancel all background jobs and cleanup fiber.
   *  Serialised with lifecycleMutex to prevent executeBackground from adding
   *  jobs after we read the map.
   */
  def kill(): IO[Unit] =
    lifecycleMutex.lock.surround {
      for
        _ <- isAlive.set(false)
        jobs <- backgroundJobs.getAndSet(Map.empty)
        _ <- jobs.values.toList.traverse_(job =>
          job.deferred.complete(Left(new InterruptedException("Session killed"))).attempt.void *>
            job.fiber.cancel *>
            job.fiber.join.void.timeout(5.seconds).attempt.void
        )
        _ <- cleanupFiber.cancel *> cleanupFiber.join.void.timeout(5.seconds).attempt.void
      yield ()
    }

  // ------------------------------------------------------------------
  // Internals
  // ------------------------------------------------------------------

  private val MaxOutputSize = 10 * 1024 * 1024 // 10MB

  private def runProcess(command: String, cwd: String, timeout: FiniteDuration): IO[ProcessResult] =
    IO.blocking {
      val pb = new ProcessBuilder("bash", "-c", command)
      pb.directory(new File(cwd))
      pb.redirectInput(new File("/dev/null"))
      pb.redirectErrorStream(false) // stdout/stderr separated
      pb.start()
    }.bracket { proc =>
      val stdoutIO = IO.blocking(readStream(proc.getInputStream))
      val stderrIO = IO.blocking(readStream(proc.getErrorStream))
      val waitIO = IO.blocking {
        proc.waitFor()
        proc.exitValue()
      }

      (stdoutIO, stderrIO, waitIO)
        .parMapN { (out, err, code) =>
          ProcessResult(out, err, code, cwd)
        }
        .timeout(timeout)
    } { proc =>
      IO.blocking {
        proc.destroyForcibly()
        proc.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
        ()
      }
    }

  private def backgroundExecute(
    command: String,
    timeout: FiniteDuration,
    deferred: Deferred[IO, Either[Throwable, ProcessResult]]
  ): IO[Unit] =
    execute(command, timeout).attempt.flatMap(deferred.complete(_).void)

  private def readStream(is: java.io.InputStream): String =
    Using.resource(new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) { reader =>
      val sb = new StringBuilder
      var line: String = null
      val truncationMarker = "\n[Output truncated due to size limit]\n"

      try
        while {
          line = reader.readLine()
          line != null
        } do
          sb.append(line).append("\n")
          if sb.length > MaxOutputSize then
            val trimTo = math.max(0, MaxOutputSize - truncationMarker.length)
            sb.setLength(trimTo)
            sb.append(truncationMarker)
            while { line = reader.readLine(); line != null } do ()
      catch
        case _: java.io.IOException => () // expected when proc.destroyForcibly() closes the stream on timeout/cancel

      val s = sb.toString()
      if s.trim.isEmpty then "" else s
    }

end ShellSession

object ShellSession:

  private val sessions: Ref[IO, Map[String, ShellSession]] =
    Ref.unsafe[IO, Map[String, ShellSession]](Map.empty)
  // Guards concurrent get-or-create to prevent duplicate ShellSession + cleanupFiber leaks
  private val createMutex: IO[Mutex[IO]] = Mutex[IO].memoize.flatten

  // Best-effort cleanup of all sessions on JVM exit
  sys.addShutdownHook {
    import cats.effect.unsafe.implicits.global
    sessions.get.flatMap(s => s.values.toList.traverse_(_.kill())).unsafeRunAndForget()
  }

  def forSession(sessionId: String): IO[ShellSession] =
    createMutex.flatMap(_.lock.surround(doGetOrCreate(sessionId)))

  private def doGetOrCreate(sessionId: String): IO[ShellSession] =
    sessions.get.flatMap { m =>
      m.get(sessionId) match
        case Some(s) =>
          s.isStale.flatMap {
            case true =>
              s.kill() *> sessions.update(_ - sessionId) *>
                ShellSession.create(sessionId).flatMap { newS =>
                  sessions.update(_ + (sessionId -> newS)).as(newS)
                }
            case false => s.touch.as(s)
          }
        case None =>
          ShellSession.create(sessionId).flatMap { newS =>
            sessions.update(_ + (sessionId -> newS)).as(newS)
          }
    }

  def destroySession(sessionId: String): IO[Unit] =
    sessions.modify { m =>
      m.get(sessionId) match
        case Some(s) => (m - sessionId, s.kill())
        case None => (m, IO.unit)
    }.flatten

  private[tools] def create(sessionId: String): IO[ShellSession] =
    for
      dirRef <- Ref.of[IO, String](System.getProperty("user.dir"))
      jobsRef <- Ref.of[IO, Map[String, BackgroundJob]](Map.empty)
      fiber <- startCleanupFiber(jobsRef)
      accessRef <- Clock[IO].realTime.map(_.toMillis).flatMap(Ref.of[IO, Long])
      aliveRef <- Ref.of[IO, Boolean](true)
      mutex <- Mutex[IO]
    yield new ShellSession(sessionId, dirRef, jobsRef, fiber, accessRef, aliveRef, mutex)

  private def startCleanupFiber(jobsRef: Ref[IO, Map[String, BackgroundJob]]): IO[Fiber[IO, Throwable, Unit]] =
    def loop: IO[Unit] =
      IO.sleep(5.minutes) *> evictCompleted(jobsRef).handleErrorWith { e =>
        IO.println(s"[ShellSession] Cleanup error: ${e.getMessage}")
      } *> IO.defer(loop)
    loop.start

  private def evictCompleted(jobsRef: Ref[IO, Map[String, BackgroundJob]]): IO[Unit] =
    jobsRef.get.flatMap {
      case jobs if jobs.isEmpty => IO.unit
      case jobs =>
        jobs.toList
          .traverse { case (id, job) =>
            job.isComplete.map(if _ then Some(id) else None)
          }
          .map(_.flatten.toSet)
          .flatMap { completed =>
            jobsRef.update(_ -- completed)
          }
    }
end ShellSession
