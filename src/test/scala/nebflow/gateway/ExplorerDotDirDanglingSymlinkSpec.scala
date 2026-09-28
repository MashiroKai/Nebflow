package nebflow.gateway

import munit.FunSuite

import java.nio.file.{Files => JFiles}
import nebflow.core.PathUtil

/**
 * Explorer dot-dir + dangling-symlink regression spec (2026-09-02).
 *
 * Symptom: opening a project's `.nebflow/` folder in the file browser showed
 * an error instead of the listing. Root cause: the listDir handler stat'ed
 * every entry with bare os.isDir/os.size calls; a dangling symlink inside the
 * directory (real case: .nebflow/flowmap-anim → worktrees/flowmap-anim with
 * an emptied worktrees/ dir) threw NoSuchFile and poisoned the WHOLE listing
 * (dirListing{error}). Fix: WebSocketRoutes.listDirEntries stats each entry
 * inside a Try — a failing entry degrades to type=file, size=0, broken=true
 * and never aborts the listing.
 *
 * Note the deliberate design this spec also pins down: dot-directories are
 * NOT hidden by the frontend (HIDDEN_DIRS only contains build artifacts and
 * VCS dirs), so `.nebflow/` must list and expand like any other directory.
 */
class ExplorerDotDirDanglingSymlinkSpec extends FunSuite:

  private def withTempRoot(f: os.Path => Unit): Unit =
    val tmp = os.Path(JFiles.createTempDirectory("nb-explorer-dotdir").toString)
    try f(tmp)
    finally os.remove.all(tmp)

  /** Replica of the real project workspace that triggered the bug. */
  private def scaffoldNebflowDir(root: os.Path): Unit =
    os.write(root / "AGENTS.md", "# AGENTS stub\n")
    os.makeDir.all(root / ".nebflow" / "worktrees")
    os.write(root / ".nebflow" / "flow-map.json", "{\"flow\":\"map-stub\"}")
    os.write(root / ".nebflow" / "flow-map-archive.json", "{\"archive\":true}")
    // Valid symlink (relative-looking target, absolute here — same stat
    // semantics): .nebflow/Agent.md -> ../AGENTS.md
    os.symlink(root / ".nebflow" / "Agent.md", root / "AGENTS.md")
    // Dangling symlink: .nebflow/flowmap-anim -> worktrees/flowmap-anim
    // (target deliberately NOT created — this is the reproduction asset)
    os.symlink(root / ".nebflow" / "flowmap-anim", root / ".nebflow" / "worktrees" / "flowmap-anim")

  private def entryOf(entries: Seq[io.circe.Json], name: String): io.circe.Json =
    entries
      .find(_.hcursor.get[String]("name").toOption.contains(name))
      .getOrElse(fail(s"entry '$name' not found in listing"))

  // ---------- the bug: dangling symlink must not poison the listing ----------

  test("listing a .nebflow dir containing a dangling symlink succeeds with all entries") {
    withTempRoot { root =>
      scaffoldNebflowDir(root)
      val entries = WebSocketRoutes.listDirEntries(root / ".nebflow")
      val names = entries.flatMap(_.hcursor.get[String]("name").toOption).toSet
      assertEquals(
        names,
        Set("Agent.md", "flow-map.json", "flow-map-archive.json", "flowmap-anim", "worktrees")
      )
    }
  }

  test("dangling symlink degrades to a size-0 broken file entry, not an error") {
    withTempRoot { root =>
      scaffoldNebflowDir(root)
      val entries = WebSocketRoutes.listDirEntries(root / ".nebflow")
      val e = entryOf(entries, "flowmap-anim")
      assertEquals(e.hcursor.get[String]("type").toOption, Some("file"))
      assertEquals(e.hcursor.get[Long]("size").toOption, Some(0L))
      assertEquals(e.hcursor.get[Boolean]("broken").toOption, Some(true))
    }
  }

  // ---------- normal entries behave exactly as before ----------

  test("normal files and a valid symlink list with real types, sizes and broken=false") {
    withTempRoot { root =>
      scaffoldNebflowDir(root)
      val entries = WebSocketRoutes.listDirEntries(root / ".nebflow")

      val flowMap = entryOf(entries, "flow-map.json")
      assertEquals(flowMap.hcursor.get[String]("type").toOption, Some("file"))
      assertEquals(flowMap.hcursor.get[Long]("size").toOption, Some(19L))
      assertEquals(flowMap.hcursor.get[Boolean]("broken").toOption, Some(false))

      // Valid symlink keeps followLinks semantics: stat goes through to target
      val agentMd = entryOf(entries, "Agent.md")
      assertEquals(agentMd.hcursor.get[String]("type").toOption, Some("file"))
      assertEquals(agentMd.hcursor.get[Long]("size").toOption, Some(14L))
      assertEquals(agentMd.hcursor.get[Boolean]("broken").toOption, Some(false))

      val worktrees = entryOf(entries, "worktrees")
      assertEquals(worktrees.hcursor.get[String]("type").toOption, Some("dir"))
      assertEquals(worktrees.hcursor.get[Long]("size").toOption, Some(0L))
      assertEquals(worktrees.hcursor.get[Boolean]("broken").toOption, Some(false))
    }
  }

  test("dir entries sort before files, then case-insensitive by name") {
    withTempRoot { root =>
      scaffoldNebflowDir(root)
      // Make the case-ordering observable: uppercase file name sorts with
      // lowercase peers (same tie-break the pre-fix implementation used).
      os.write(root / ".nebflow" / "Zebra.txt", "z")
      val listing = WebSocketRoutes.listDirEntries(root / ".nebflow")
        .map(e => (e.hcursor.get[String]("type").toOption.getOrElse("?"), e.hcursor.get[String]("name").toOption.getOrElse("?")))
      val dirs = listing.takeWhile(_._1 == "dir").map(_._2)
      val files = listing.dropWhile(_._1 == "dir").map(_._2)
      assertEquals(dirs, Seq("worktrees"))
      assertEquals(files.map(_.toLowerCase), files.map(_.toLowerCase).sorted)
    }
  }

  test("empty directory and non-directory paths return empty listings") {
    withTempRoot { root =>
      scaffoldNebflowDir(root)
      assertEquals(WebSocketRoutes.listDirEntries(root / ".nebflow" / "worktrees"), Nil)
      assertEquals(WebSocketRoutes.listDirEntries(root / ".nebflow" / "flow-map.json"), Nil)
      assertEquals(WebSocketRoutes.listDirEntries(root / "no-such-dir"), Nil)
    }
  }

  // ---------- canonical containment guard regression (listDir handler) ----------

  test("../ traversal canonicalizes outside the project root (guard rejects)") {
    withTempRoot { root =>
      val outside = PathUtil.resolvePath("../escape.txt", root)
      assert(!outside.toIO.getCanonicalPath.startsWith(root.toIO.getCanonicalPath))
    }
  }

  test("symlink resolving outside the project root canonicalizes outside (guard rejects)") {
    withTempRoot { root =>
      val outsideDir = os.Path(JFiles.createTempDirectory("nb-explorer-outside").toString)
      try
        os.symlink(root / "escape-link", outsideDir)
        val resolved = PathUtil.resolvePath("escape-link", root)
        assert(!resolved.toIO.getCanonicalPath.startsWith(root.toIO.getCanonicalPath))
      finally os.remove.all(outsideDir)
    }
  }

  test("in-root symlink target canonicalizes inside the root (guard allows, readFile path)") {
    withTempRoot { root =>
      scaffoldNebflowDir(root)
      // .nebflow/Agent.md -> AGENTS.md stays inside the root after canonicalization
      val resolved = PathUtil.resolvePath(".nebflow/Agent.md", root)
      assert(resolved.toIO.getCanonicalPath.startsWith(root.toIO.getCanonicalPath))
    }
  }

end ExplorerDotDirDanglingSymlinkSpec
