package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

class SessionStoreFolderSpec extends CatsEffectSuite:

  private def withStore(test: SessionStore => IO[Unit]): Unit =
    val tmp = Files.createTempDirectory("nebflow-folder-test")
    val sessionsDir = os.Path(tmp.resolve("sessions"))
    val tasksDir = os.Path(tmp.resolve("tasks"))
    try
      val store = SessionStore(sessionsDir, tasksDir)
      store.load.unsafeRunSync()
      test(store).unsafeRunSync()
    finally
      if Files.exists(tmp) then
        Files.walk(tmp).sorted(java.util.Comparator.reverseOrder()).iterator().asScala.foreach(Files.deleteIfExists)

  test("set projectRoot on a subfolder succeeds") {
    withStore { store =>
      for
        parent <- store.createFolder("parent")
        child <- store.createFolder("child", parentId = Some(parent.id))
        result <- store.setFolderProjectRoot(child.id, Some("/child/path"))
        resolved <- store.resolveProjectRoot(Some(child.id))
      yield
        assert(result.isRight, "Setting projectRoot on a subfolder should succeed")
        assertEquals(resolved, Some("/child/path"))
    }
  }

  test("subfolder projectRoot overrides parent's") {
    withStore { store =>
      for
        top <- store.createFolder("top")
        mid <- store.createFolder("mid", parentId = Some(top.id))
        leaf <- store.createFolder("leaf", parentId = Some(mid.id))
        _ <- store.setFolderProjectRoot(top.id, Some("/top/path"))
        _ <- store.setFolderProjectRoot(mid.id, Some("/mid/path"))
        resolved <- store.resolveProjectRoot(Some(leaf.id))
      yield assertEquals(resolved, Some("/mid/path"))
    }
  }

  test("subfolder without projectRoot inherits nearest ancestor's") {
    withStore { store =>
      for
        top <- store.createFolder("top")
        mid <- store.createFolder("mid", parentId = Some(top.id))
        leaf <- store.createFolder("leaf", parentId = Some(mid.id))
        _ <- store.setFolderProjectRoot(top.id, Some("/top/path"))
        resolved <- store.resolveProjectRoot(Some(leaf.id))
      yield assertEquals(resolved, Some("/top/path"))
    }
  }

  test("no projectRoot on any ancestor returns None") {
    withStore { store =>
      for
        top <- store.createFolder("top")
        child <- store.createFolder("child", parentId = Some(top.id))
        resolved <- store.resolveProjectRoot(Some(child.id))
      yield assertEquals(resolved, None)
    }
  }

  test("resolveProjectRoot(None) returns None") {
    withStore { store =>
      for resolved <- store.resolveProjectRoot(None)
      yield assertEquals(resolved, None)
    }
  }

  test("clearing subfolder projectRoot falls back to parent's") {
    withStore { store =>
      for
        parent <- store.createFolder("parent")
        child <- store.createFolder("child", parentId = Some(parent.id))
        _ <- store.setFolderProjectRoot(parent.id, Some("/parent/path"))
        _ <- store.setFolderProjectRoot(child.id, Some("/child/path"))
        resolvedBefore <- store.resolveProjectRoot(Some(child.id))
        _ <- store.setFolderProjectRoot(child.id, None)
        resolvedAfter <- store.resolveProjectRoot(Some(child.id))
      yield
        assertEquals(resolvedBefore, Some("/child/path"))
        assertEquals(resolvedAfter, Some("/parent/path"))
    }
  }

  test("set projectRoot on non-existent folder returns Left") {
    withStore { store =>
      for result <- store.setFolderProjectRoot("non-existent-id", Some("/some/path"))
      yield assert(result.isLeft)
    }
  }

  test("multi-level inheritance: leaf inherits grandparent when parent has none") {
    withStore { store =>
      for
        a <- store.createFolder("A")
        b <- store.createFolder("B", parentId = Some(a.id))
        c <- store.createFolder("C", parentId = Some(b.id))
        d <- store.createFolder("D", parentId = Some(c.id))
        _ <- store.setFolderProjectRoot(a.id, Some("/a/path"))
        resolved <- store.resolveProjectRoot(Some(d.id))
      yield assertEquals(resolved, Some("/a/path"))
    }
  }

  test("top-level folder projectRoot still works") {
    withStore { store =>
      for
        top <- store.createFolder("top")
        _ <- store.setFolderProjectRoot(top.id, Some("/top/path"))
        resolved <- store.resolveProjectRoot(Some(top.id))
      yield assertEquals(resolved, Some("/top/path"))
    }
  }

end SessionStoreFolderSpec
