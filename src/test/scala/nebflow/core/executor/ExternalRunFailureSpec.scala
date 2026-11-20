package nebflow.core.executor

import cats.effect.{IO, Resource}
import munit.CatsEffectSuite
import nebflow.shared.{Message, MessageRole}

/** Isolated regressions for run ownership, raw evidence and chunk reconstruction. */
class ExternalRunFailureSpec extends CatsEffectSuite:

  private def temporaryDirectory: Resource[IO, os.Path] =
    Resource.make(IO.blocking(os.temp.dir(prefix = "external-run-failure-")))(path => IO.blocking(os.remove.all(path)))

  private def message(id: String, text: String, run: String = "run"): Message =
    Message(MessageRole.Assistant, Left(text), messageId = Some(id), runId = Some(run))

  test("stream chunks reconstruct without inserted whitespace and exclude other runs") {
    IO {
      val messages = List(
        message("run/protocol/6:stream/block/0", "Hello "),
        message("run/protocol/6:stream/block/1", "world"),
        message("run/protocol/4:next/block/0", "Next message"),
        message("other/protocol/3:old/block/0", "Not this run", "other")
      )
      assertEquals(ExternalSessionRunner.reconstructRunText(messages, "run"), "Hello world\n\nNext message")
    }
  }

  test("raw evidence is capped while later writes continue successfully") {
    temporaryDirectory.use { root =>
      val file = root / "session" / "external-run.log"
      for
        _ <- ExternalSessionRunner.appendRawFile(file, "x" * (ExternalSessionRunner.RawLimitBytes + 100))
        _ <- ExternalSessionRunner.appendRawFile(file, "must not extend the log")
        _ <- IO.blocking {
          assertEquals(os.size(file), ExternalSessionRunner.RawLimitBytes.toLong)
          assert(os.read(file).endsWith("[Raw output truncated at 8 MiB; assistant history is retained separately.]\n"))
          if java.nio.file.Files.getFileStore(file.toNIO).supportsFileAttributeView("posix") then
            assertEquals(
              java.nio.file.attribute.PosixFilePermissions
                .toString(java.nio.file.Files.getPosixFilePermissions(file.toNIO)),
              "rw-------"
            )
        }
      yield ()
    }
  }

  test("raw evidence refuses a symlink instead of writing through it") {
    temporaryDirectory.use { root =>
      val target = root / "target"
      val link = root / "session" / "external-run.log"
      for
        _ <- IO.blocking {
          assume(!ExecutorRegistry.isWindows, "Symbolic-link fixture requires POSIX")
          os.write(target, "untouched")
          os.makeDir.all(link / os.up)
          java.nio.file.Files.createSymbolicLink(link.toNIO, target.toNIO)
        }
        result <- ExternalSessionRunner.appendRawFile(link, "sensitive").attempt
        _ <- IO.blocking {
          assert(result.isLeft)
          assertEquals(os.read(target), "untouched")
        }
      yield ()
    }
  }

  test("owned process cleanup escalates using only its supplied handle") {
    val destroys = new java.util.concurrent.atomic.AtomicInteger(0)
    val forces = new java.util.concurrent.atomic.AtomicInteger(0)
    val alive = new java.util.concurrent.atomic.AtomicBoolean(true)
    val process = new Process:
      override def pid(): Long = ProcessHandle.current().pid() + 1000000L
      override def getOutputStream(): java.io.OutputStream = java.io.OutputStream.nullOutputStream()
      override def getInputStream(): java.io.InputStream = java.io.InputStream.nullInputStream()
      override def getErrorStream(): java.io.InputStream = java.io.InputStream.nullInputStream()
      override def waitFor(): Int = 0
      override def waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean = !alive.get()
      override def exitValue(): Int = if alive.get() then throw new IllegalThreadStateException() else 0
      override def isAlive(): Boolean = alive.get()
      override def destroy(): Unit =
        destroys.incrementAndGet(); ()
      override def destroyForcibly(): Process =
        forces.incrementAndGet()
        alive.set(false)
        this
    ExternalOutputStore.stopOwnedProcess(process).map { _ =>
      assertEquals(destroys.get(), 1)
      assertEquals(forces.get(), 1)
      assert(!alive.get())
    }
  }

  test("a stale run cannot append or terminalize its successor") {
    val id = "external-run-failure-" + java.util.UUID.randomUUID().toString
    for
      _ <- ExternalOutputStore.open(id)
      _ <- ExternalOutputStore.claimRun(id, "old")
      _ <- ExternalOutputStore.finalizeRun(id, "old", "completed", Some(0), None)
      reset <- ExternalOutputStore.resetForNewRun(id)
      _ <- IO(assert(reset))
      _ <- ExternalOutputStore.claimRun(id, "new")
      _ <- ExternalOutputStore.appendForRun(id, "old", "stale output")
      _ <- ExternalOutputStore.finalizeRun(id, "old", "failed", Some(1), Some("stale"))
      current <- ExternalOutputStore.read(id, 0L)
      _ <- IO {
        assertEquals(current.map(_.status), Some("running"))
        assertEquals(current.map(_.output), Some(""))
      }
      _ <- ExternalOutputStore.finalizeRun(id, "new", "cancelled", None, Some("first"))
      _ <- ExternalOutputStore.finalizeRun(id, "new", "completed", Some(0), None)
      terminal <- ExternalOutputStore.read(id, 0L)
      _ <- IO(assertEquals(terminal.map(_.status), Some("cancelled")))
    yield ()
    end for
  }
end ExternalRunFailureSpec
