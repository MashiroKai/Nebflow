package nebflow.core.delegate

import cats.effect.{Deferred, IO}
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.shared.PathUtil

class SessionStartLeaseSpec extends CatsEffectSuite:
  private val previous = PathUtil.dataRoot
  private var root = os.pwd

  override def beforeEach(context: BeforeEach): Unit =
    root = os.temp.dir(prefix = "merger-start-lease-")
    PathUtil.setDataRoot(root)

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(previous)
    os.remove.all(root)

  test("concurrent occupancy probes cannot reject a legitimate local starter") {
    val id = "delegate-probe-race"
    for
      _ <- (1 to 200).toList.traverse_ { _ =>
        (
          IO.blocking((1 to 25).foreach(_ => SessionStartLease.isHeld(id))),
          SessionStartLease.exclusive(id)(IO(assert(SessionStartLease.isHeld(id))))
        ).parTupled.void
      }
      _ <- IO(assert(!SessionStartLease.isHeld(id)))
    yield ()
  }

  test("the session-start lease excludes an independent process and cancellation releases it") {
    val id = "delegate-process-lock"
    val probe = root / "LockProbe.java"
    os.write(
      probe,
      """import java.nio.channels.FileChannel;
      |import java.nio.file.*;
      |public class LockProbe {
      |  public static void main(String[] args) throws Exception {
      |    try (var channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
      |      var lock = channel.tryLock();
      |      if (lock == null) System.exit(7);
      |      lock.release();
      |    }
      |  }
      |}
      |""".stripMargin
    )
    def external = IO.blocking {
      os.proc(
        (os.Path(System.getProperty("java.home")) / "bin" / "java").toString,
        probe.toString,
        (root / "workspaces" / s"start-$id.lock").toString
      ).call(check = false, stdout = os.Pipe, stderr = os.Pipe)
        .exitCode
    }
    for
      started <- Deferred[IO, Unit]
      holder <- SessionStartLease.exclusive(id)(started.complete(()) *> IO.never[Unit]).start
      _ <- (started.get *> external.flatMap(rc => IO(assertEquals(rc, 7)))).guarantee(holder.cancel)
      released <- external
      _ = assertEquals(released, 0)
      error <- SessionStartLease
        .exclusive(id)(IO.raiseError[Unit](new IllegalStateException("simulated failure")))
        .attempt
      _ = assert(error.isLeft)
      retry <- SessionStartLease.exclusive(id)(IO.pure("recovered"))
      _ = assertEquals(retry, "recovered")
    yield ()
  }
end SessionStartLeaseSpec
