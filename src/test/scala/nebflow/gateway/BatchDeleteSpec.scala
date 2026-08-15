package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite

import java.nio.file.{Files => JFiles}

class BatchDeleteSpec extends FunSuite:

  private def withTempRoot(f: os.Path => Unit): Unit =
    val tmp = os.Path(JFiles.createTempDirectory("nb-batch-delete").toString)
    try f(tmp)
    finally os.remove.all(tmp)

  test("deletePathsSafely deletes files and directories under root") {
    withTempRoot { root =>
      os.write(root / "a.txt", "x")
      os.makeDir.all(root / "sub")
      os.write(root / "sub" / "b.txt", "y")
      val (deleted, failed) = WebSocketRoutes.deletePathsSafely(List("a.txt", "sub"), root).unsafeRunSync()
      assertEquals(deleted, List("a.txt", "sub"))
      assert(failed.isEmpty)
      assert(!os.exists(root / "a.txt"))
      assert(!os.exists(root / "sub"))
    }
  }

  test("deletePathsSafely rejects ../ traversal outside root") {
    withTempRoot { root =>
      val outside = root / os.up / "nb-outside-marker.txt"
      os.write.over(outside, "keep me")
      val (deleted, failed) = WebSocketRoutes
        .deletePathsSafely(List("../nb-outside-marker.txt", "ok.txt"), root)
        .unsafeRunSync()
      try
        // traversal rejected; the second (nonexistent) path still completes
        // as a no-op success — mirrors single deletePath semantics
        assertEquals(deleted, List("ok.txt"))
        assertEquals(failed.map(_._1), List("../nb-outside-marker.txt"))
        assert(failed.head._2.nonEmpty)
        // traversal rejection must keep the outside file intact
        assert(os.exists(outside))
      finally os.remove(outside)
    }
  }

  test("deletePathsSafely refuses to delete the project root itself") {
    withTempRoot { root =>
      val (deleted, failed) = WebSocketRoutes.deletePathsSafely(List(".", ""), root).unsafeRunSync()
      assert(deleted.isEmpty)
      assert(failed.nonEmpty)
      assert(os.exists(root))
    }
  }

  test("deletePathsSafely aggregates per-item failures without aborting the batch") {
    withTempRoot { root =>
      os.write(root / "keep.txt", "x")
      // first path already gone (vanished), second is fine
      val (deleted, failed) = WebSocketRoutes.deletePathsSafely(List("vanished.txt", "keep.txt"), root).unsafeRunSync()
      // vanished paths delete as no-op success (mirrors single deletePath semantics)
      assertEquals(deleted, List("vanished.txt", "keep.txt"))
      assert(failed.isEmpty)
      assert(!os.exists(root / "keep.txt"))
    }
  }

  test("resolveGuardedForDelete accepts nested paths and rejects symlinks resolving outside root") {
    withTempRoot { root =>
      os.makeDir.all(root / "d1" / "d2")
      os.write(root / "d1" / "d2" / "f.txt", "x")
      val nested = root / "d1" / "d2" / "f.txt"
      assertEquals(
        WebSocketRoutes.resolveGuardedForDelete("d1/d2/f.txt", root).map(_.toString),
        Right(nested.toString)
      )
      // craft a symlink pointing outside the root — canonical resolution must reject
      val outsideDir = os.Path(JFiles.createTempDirectory("nb-outside").toString)
      try
        val link = root / "link"
        os.symlink(link, outsideDir)
        val res = WebSocketRoutes.resolveGuardedForDelete("link/anything", root)
        assert(res.isLeft)
      finally os.remove.all(outsideDir)
    }
  }

  test("deletePathsSafely is safe on empty batch") {
    withTempRoot { root =>
      val (deleted, failed) = WebSocketRoutes.deletePathsSafely(Nil, root).unsafeRunSync()
      assert(deleted.isEmpty)
      assert(failed.isEmpty)
    }
  }

end BatchDeleteSpec
