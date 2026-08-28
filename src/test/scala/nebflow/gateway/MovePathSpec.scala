package nebflow.gateway

import munit.FunSuite

import java.nio.file.{Files => JFiles}

class MovePathSpec extends FunSuite:

  private def withTempRoot(f: os.Path => Unit): Unit =
    val tmp = os.Path(JFiles.createTempDirectory("nb-move-path").toString)
    try f(tmp)
    finally os.remove.all(tmp)

  // ---------- success paths ----------

  test("movePathSafely moves a file across directories and reports relative new path") {
    withTempRoot { root =>
      os.makeDir.all(root / "sub")
      os.write(root / "a.txt", "hello")
      assertEquals(WebSocketRoutes.movePathSafely("a.txt", "sub", root), Right("sub/a.txt"))
      assert(!os.exists(root / "a.txt"))
      assertEquals(os.read(root / "sub" / "a.txt"), "hello")
    }
  }

  test("movePathSafely moves a file into a nested target directory") {
    withTempRoot { root =>
      os.makeDir.all(root / "d1" / "d2")
      os.write(root / "notes.md", "x")
      assertEquals(WebSocketRoutes.movePathSafely("notes.md", "d1/d2", root), Right("d1/d2/notes.md"))
      assert(os.exists(root / "d1" / "d2" / "notes.md"))
    }
  }

  test("movePathSafely moves a whole directory preserving its contents") {
    withTempRoot { root =>
      os.makeDir.all(root / "src" / "utils")
      os.write(root / "src" / "utils" / "helper.scala", "code")
      os.write(root / "src" / "README.md", "r")
      os.makeDir.all(root / "archive")
      assertEquals(WebSocketRoutes.movePathSafely("src", "archive", root), Right("archive/src"))
      assert(!os.exists(root / "src"))
      assert(os.exists(root / "archive" / "src" / "utils" / "helper.scala"))
      assertEquals(os.read(root / "archive" / "src" / "README.md"), "r")
    }
  }

  // ---------- guard 1: both paths must stay inside the project root ----------

  test("movePathSafely rejects ../ traversal of the source path") {
    withTempRoot { root =>
      val outside = root / os.up / "nb-move-outside-marker.txt"
      os.write.over(outside, "keep me")
      try
        assertEquals(
          WebSocketRoutes.movePathSafely("../nb-move-outside-marker.txt", ".", root),
          Left("path outside project root")
        )
        // traversal rejection must keep the outside file intact
        assert(os.exists(outside))
      finally os.remove(outside)
    }
  }

  test("movePathSafely rejects ../ traversal of the target directory") {
    withTempRoot { root =>
      os.write(root / "a.txt", "x")
      assertEquals(
        WebSocketRoutes.movePathSafely("a.txt", "../nb-move-outside-dir", root),
        Left("target directory outside project root")
      )
      assert(os.exists(root / "a.txt"))
    }
  }

  test("movePathSafely rejects symlinks resolving outside the project root") {
    withTempRoot { root =>
      val outsideDir = os.Path(JFiles.createTempDirectory("nb-move-outside").toString)
      try
        os.makeDir.all(root / "dest")
        val link = root / "link"
        os.symlink(link, outsideDir)
        assertEquals(WebSocketRoutes.movePathSafely("link", "dest", root), Left("path outside project root"))
        assert(os.exists(outsideDir))
      finally os.remove.all(outsideDir)
    }
  }

  // ---------- guard 5: cannot move the project root itself ----------

  test("movePathSafely refuses to move the project root itself") {
    withTempRoot { root =>
      assertEquals(WebSocketRoutes.movePathSafely(".", "sub", root), Left("cannot move project root"))
      assertEquals(WebSocketRoutes.movePathSafely("", "sub", root), Left("cannot move project root"))
      assert(os.exists(root))
    }
  }

  // ---------- guard 2: target must exist and be a directory ----------

  test("movePathSafely rejects a missing target directory") {
    withTempRoot { root =>
      os.write(root / "a.txt", "x")
      assertEquals(WebSocketRoutes.movePathSafely("a.txt", "no-such-dir", root), Left("target directory not found"))
      assert(os.exists(root / "a.txt"))
    }
  }

  test("movePathSafely rejects a target that is a file, not a directory") {
    withTempRoot { root =>
      os.write(root / "a.txt", "x")
      os.write(root / "plain-file", "not a dir")
      assertEquals(WebSocketRoutes.movePathSafely("a.txt", "plain-file", root), Left("target directory not found"))
      assert(os.exists(root / "a.txt"))
    }
  }

  // ---------- guard 3: no cycles (target inside source) ----------

  test("movePathSafely rejects moving a directory into itself") {
    withTempRoot { root =>
      os.makeDir.all(root / "sub")
      assertEquals(WebSocketRoutes.movePathSafely("sub", "sub", root), Left("cannot move path into itself"))
      assert(os.exists(root / "sub"))
    }
  }

  test("movePathSafely rejects moving a directory into its own subtree") {
    withTempRoot { root =>
      os.makeDir.all(root / "sub" / "inner")
      assertEquals(
        WebSocketRoutes.movePathSafely("sub", "sub/inner", root),
        Left("cannot move path into itself")
      )
      assert(os.exists(root / "sub" / "inner"))
    }
  }

  // ---------- guard 4: destination must not already exist ----------

  test("movePathSafely never overwrites an existing destination") {
    withTempRoot { root =>
      os.makeDir.all(root / "dest")
      os.write(root / "a.txt", "moving")
      os.write(root / "dest" / "a.txt", "existing")
      assertEquals(WebSocketRoutes.movePathSafely("a.txt", "dest", root), Left("destination already exists"))
      // original untouched, destination untouched
      assertEquals(os.read(root / "a.txt"), "moving")
      assertEquals(os.read(root / "dest" / "a.txt"), "existing")
    }
  }

  test("movePathSafely rejects moving a path onto itself (same parent)") {
    withTempRoot { root =>
      os.write(root / "a.txt", "x")
      assertEquals(WebSocketRoutes.movePathSafely("a.txt", ".", root), Left("destination already exists"))
      assert(os.exists(root / "a.txt"))
    }
  }

  // ---------- source existence ----------

  test("movePathSafely reports a missing source") {
    withTempRoot { root =>
      os.makeDir.all(root / "dest")
      assertEquals(WebSocketRoutes.movePathSafely("vanished.txt", "dest", root), Left("source path not found"))
    }
  }

end MovePathSpec
