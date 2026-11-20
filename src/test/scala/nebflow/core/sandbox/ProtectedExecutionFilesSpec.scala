package nebflow.core.sandbox

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.ProtectedExecutionFiles
import nebflow.core.tools.{ToolContext, WriteTool}

import java.nio.file.Files

/** Protected file tools must not write authority/task state behind the authority. */
class ProtectedExecutionFilesSpec extends CatsEffectSuite:

  test("protected file and shell operations cannot mutate engine state through aliases or ancestors") {
    for
      root <- IO.blocking(os.temp.dir(prefix = "protected-engine-files-"))
      _ <- (IO.blocking {
        os.makeDir.all(root / "engine" / "authority")
        os.makeDir.all(root / "engine" / "tasks")
        os.makeDir.all(root / "workspace")
        os.write(root / "engine" / "authority" / "commit.json", "{}")
      } *> IO.blocking {
        val engine = root / "engine"
        val authority = engine / "authority"
        val tasks = engine / "tasks"
        val workspace = root / "workspace"
        val guard = ProtectedExecutionFiles(List(engine.toNIO, tasks.toNIO))

        assert(guard.checkWrite((authority / "commit.json").toNIO).isLeft, "authority bytes are engine state")
        assert(guard.checkWrite((tasks / "tasks.json").toNIO).isLeft, "task projection files are engine state")
        assert(guard.checkWrite(engine.toNIO).isLeft, "an engine root itself is protected")
        assert(guard.checkWrite(root.toNIO).isLeft, "an ancestor whose removal destroys the root is protected")

        os.write(workspace / "result.txt", "ok")
        assert(guard.checkWrite((workspace / "result.txt").toNIO).isRight)
        assert(guard.checkWrite((workspace / "new.txt").toNIO).isRight)

        os.symlink(workspace / "engine-link", engine)
        assert(
          guard.checkWrite((workspace / "engine-link" / "commit.json").toNIO).isLeft,
          "symlink components resolve before the engine-root comparison"
        )

        os.write(workspace / "plain.txt", "plain")
        Files.createLink((workspace / "plain-link.txt").toNIO, (workspace / "plain.txt").toNIO)
        assert(
          guard.checkWrite((workspace / "plain-link.txt").toNIO).isLeft,
          "a multiply linked file is rejected conservatively"
        )

        val context =
          ToolContext(projectRoot = workspace.toString, protectedExecutionRoots = List(engine.toNIO, tasks.toNIO))
        assert(
          WriteTool
            .call(
              JsonObject(
                "file_path" -> (authority / "commit.json").toString.asJson,
                "content" -> "{\"changed\":true}".asJson
              ),
              context
            )
            .unsafeRunSync()
            .isLeft,
          "WriteTool must reject engine state even with the legacy sandbox off"
        )
        assert(
          WriteTool
            .call(
              JsonObject("file_path" -> (workspace / "written.txt").toString.asJson, "content" -> "business".asJson),
              context
            )
            .unsafeRunSync()
            .isRight,
          "WriteTool must keep ordinary workspace writes available"
        )
        assertEquals(os.read(authority / "commit.json"), "{}", "engine bytes stayed unchanged")
      }).guarantee(IO.blocking(os.remove.all(root)))
    yield ()
  }
end ProtectedExecutionFilesSpec
