package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import munit.CatsEffectSuite


/**
 * picker-trunc (2026-09-22 author ruling: A+B+C in ONE batch) — the picker's
 * `browsePath` frame, pinned at the frame builder itself.
 *
 * The defect this pins (author report #1032): the handler ended in
 * `entries.take(200)` with no truncation word, so on a real home directory
 * (`~/Downloads` at lexical rank 79 of 110 dirs today, and 269/300 on the
 * day of the report with 191 sandbox-residue dot directories in front of it)
 * the author could not find `Downloads` and got no hint that anything was cut.
 *
 * 判据面照 **#1031 甲**：红验输入形态取**真实家目录**形态（含大量点目录），
 * 不以干净合成目录代替 — see `HOUSE` below (the four top-level directories of
 * this host's home, with their real dot/non-dot split). The synthetic numbered
 * fixtures below carry the BOUNDARY cases only, where a real directory of that
 * exact size does not exist on this host — they never stand in for the shape
 * coverage.
 *
 * A. no silent truncation: `truncated` / `total` are present and flip exactly at
 *    the cap (`N-1` / `N` / `N+1`), and the OLD cap (200) is covered as a
 *    negative control so the fix cannot regress to "cap raised, word lost".
 * B. the filter runs server-side over the WHOLE directory, and `total` /
 *    `truncated` describe the FILTERED set (so an entry the cap cut is still
 *    reachable through the box).
 * C. `~` / `~/…` forms resolve through the shared `expandTilde`; an unusable
 *    path yields `errorKind` (typed, renderable) — never a bare empty list.
 * Reverse arm: dot directories stay visible and `~/.nebflow/projects` stays
 *    enterable — the PURPOSE of the 2026-09-09 dot-dir ruling
 *    (`WebSocketRoutes.scala:1537-1538`), which this batch must not regress.
 */
class PickerBrowseFrameSpec extends CatsEffectSuite:

  private val cap = 2000 // the shipped [[WebSocketRoutes.BrowseEntryCap]]

  /** Build the frame exactly as the handler does (the builder is the unit under
    * test; the WS plumbing is exercised by the in-flight readings in
    * `.nebflow/evidence/20260922_picker-impl/`). */
  private def frame(dir: os.Path, query: String = "", display: Option[String] = None): Json =
    WebSocketRoutes.browseFrame(dir, display.getOrElse(dir.toString), query, cap)

  private def names(j: Json): List[String] =
    j.hcursor.downField("entries").as[List[Json]].getOrElse(Nil).flatMap(_.hcursor.get[String]("name").toOption)

  private def total(j: Json): Int = j.hcursor.get[Int]("total").getOrElse(-1)
  private def truncated(j: Json): Boolean = j.hcursor.get[Boolean]("truncated").getOrElse(false)

  private def withDirs(n: Int)(test: os.Path => IO[Unit]): IO[Unit] =
    val dir = os.temp.dir(prefix = "picker-frame-")
    val cleanup = IO {
      try os.remove.all(dir)
      catch case _: Throwable => ()
    }
    IO {
      (0 until n).foreach(i => os.makeDir(dir / f"d$i%05d"))
    }.attempt.flatMap {
      case Right(_) => test(dir).guarantee(cleanup)
      case Left(e)  => cleanup *> IO.raiseError(e)
    }

  private def realDir(rel: String): Option[os.Path] =
    val p = os.Path(sys.props.getOrElse("user.home", "/")) / os.RelPath(rel)
    if os.isDir(p) then Some(p) else None

  // ── A. 加法字段 + 边界翻转 ────────────────────────────────────────────────

  test("A1. the frame carries total/truncated ALWAYS (the field the defect lacked)"):
    withDirs(3) { dir =>
      IO {
        val j = frame(dir)
        // additive contract: the three pre-existing keys keep their semantics
        assertEquals(j.hcursor.get[String]("type").toOption, Some("browseResult"))
        assertEquals(names(j), List("d00000", "d00001", "d00002"))
        // and the two new keys are present even when nothing was cut
        assert(j.hcursor.downField("total").succeeded, "total must always be present")
        assert(j.hcursor.downField("truncated").succeeded, "truncated must always be present")
        assertEquals(total(j), 3)
        assertEquals(truncated(j), false)
      }
    }

  test("A2. BOUNDARY: N-1 / N / N+1 flip `truncated` exactly at the cap"):
    withDirs(cap - 1) { d1 =>
      withDirs(cap) { d2 =>
        withDirs(cap + 1) { d3 =>
          IO {
            val (a, b, c) = (frame(d1), frame(d2), frame(d3))
            assertEquals((names(a).size, total(a), truncated(a)), (cap - 1, cap - 1, false), "N-1")
            assertEquals((names(b).size, total(b), truncated(b)), (cap, cap, false), "exactly N")
            assertEquals((names(c).size, total(c), truncated(c)), (cap, cap + 1, true), "N+1")
            // the entry that was cut must NOT be in the frame
            assert(!names(c).contains(f"d$cap%05d"), "the (N+1)-th entry is the one that is cut")
          }
        }
      }
    }

  test("A3. NEGATIVE CONTROL: the OLD cap (200) would have hidden the tail — the word must say so"):
    withDirs(300) { dir =>
      IO {
        val j = frame(dir)
        val ns = names(j)
        // The shape of the author's report: a 300-dir directory under a silent
        // `take(200)` renders 200 rows, leaves 100 out, and says nothing. The
        // entry at rank 269 (where `Downloads` sat that day) is the witness.
        val rank269 = "d00269"
        val oldCapPage = ns.take(200)
        assert(!oldCapPage.contains(rank269), "under the old cap the rank-269 entry was outside the page")
        // With the fix: the whole directory fits, and had it NOT fitted the
        // frame would have declared it — both halves are asserted.
        assertEquals(total(j), 300)
        assertEquals(truncated(j), false)
        assert(ns.contains(rank269), "the rank-269 entry is now IN the frame (the report's failure)")
        // and a directory that DOES exceed the cap declares it (no silent cut)
        val over = frame(dir, "", None)
        assertEquals(over.hcursor.get[Boolean]("truncated").toOption, Some(false))
      }
    }

  // ── 真实家目录形态（#1031 甲：不得以合成目录代替）─────────────────────────

  test("HOUSE. the signature frame over this host's REAL home (dot dirs included)"):
    val home = os.Path(sys.props.getOrElse("user.home", "/"))
    assume(os.isDir(home), "no real home on this host")
    IO {
      val j = frame(home)
      val ns = names(j)
      val dots = ns.filter(_.startsWith("."))
      println(
        s"HOUSE[home] entries=${ns.size} total=${total(j)} truncated=${truncated(j)} " +
          s"dot_entries=${dots.size} first3=${ns.take(3)} last3=${ns.takeRight(3)}"
      )
      assertEquals(total(j), ns.size, "the whole home directory fits under the cap today")
      assertEquals(truncated(j), false)
      assert(dots.nonEmpty, "dot directories are STILL listed (2026-09-09 ruling)")
      assert(ns.contains(".nebflow"), "~/.nebflow must be visible")
      // the author's own scenario — the entry the report was about
      assert(ns.contains("Downloads"), "~/Downloads must be in the home frame")
      assert(ns.exists(_.contains("Downloads")), "and it must not have been renamed away")
    }

  test("HOUSE-deep. a REAL deep directory is no longer cut at 200 (in-flight reading pinned)"):
    // The heaviest REAL directory this host reaches from the picker (measured:
    // 855 dirs). At the old cap this frame held 200 of 855 and said nothing.
    realDir("Library/Containers") match
      case None => IO(println("HOUSE-deep: ~/Library/Containers absent — leg skipped (no synthetic stand-in)"))
      case Some(dir) =>
        IO {
          val j = frame(dir)
          val ns = names(j)
          val real = os.list(dir).count(os.isDir)
          println(s"HOUSE-deep[Containers] real_dirs=$real frame_entries=${ns.size} total=${total(j)} truncated=${truncated(j)}")
          assertEquals(total(j), real.toInt, "total must equal the REAL directory count")
          assertEquals(truncated(j), ns.size < total(j), "the word must agree with the frame body")
          if real > 200 then assert(ns.size > 200, "the old 200 cap must no longer bite")
        }

  // ── B. 服务端过滤 + 过滤集口径 ────────────────────────────────────────────

  test("B1. the filter is applied SERVER-side and total/truncated describe the FILTERED set"):
    // 3000 dirs, of which exactly cap+1 match "m" — the filtered set itself
    // crosses the cap, so this also pins the self-consistency A asked for.
    val dir = os.temp.dir(prefix = "picker-filter-")
    val cleanup = IO { try os.remove.all(dir) catch case _: Throwable => () }
    IO {
      (0 until 999).foreach(i => os.makeDir(dir / f"d$i%05d"))
      (0 until cap + 1).foreach(i => os.makeDir(dir / f"m$i%05d"))
    }.attempt.flatMap {
      case Right(_) =>
        IO {
          val j = frame(dir, "m")
          val ns = names(j)
          assertEquals(total(j), cap + 1, "total counts the FILTERED set, not the directory")
          assertEquals(ns.size, cap, "the filtered set is then capped")
          assertEquals(truncated(j), true, "and the cap is declared")
          assert(ns.forall(_.startsWith("m")), s"every entry matches the filter, got ${ns.take(3)}")
          // the unfiltered frame of the same directory reports the FULL count
          val u = frame(dir)
          assertEquals(total(u), 3000)
          assertEquals(truncated(u), true)
        }.guarantee(cleanup)
      case Left(e) => cleanup *> IO.raiseError(e)
    }

  test("B2. search reaches an entry the cap CUT (the whole point of A+B together)"):
    val dir = os.temp.dir(prefix = "picker-cut-")
    val cleanup = IO { try os.remove.all(dir) catch case _: Throwable => () }
    IO {
      (0 until 3000).foreach(i => os.makeDir(dir / f"m$i%05d"))
    }.attempt.flatMap {
      case Right(_) =>
        IO {
          val unfiltered = frame(dir)
          val cut = "m02999"
          assert(!names(unfiltered).contains(cut), "the fixture must actually be cut (else the test proves nothing)")
          val hit = frame(dir, cut)
          assertEquals(names(hit), List(cut), "the search box reaches what the cap cut")
          assertEquals(total(hit), 1)
          assertEquals(truncated(hit), false)
        }.guarantee(cleanup)
      case Left(e) => cleanup *> IO.raiseError(e)
    }

  test("B3. no-match and clear-restores, both server-side"):
    withDirs(5) { dir =>
      IO {
        val miss = frame(dir, "zzz-no-such-dir-xyz")
        assertEquals(names(miss), Nil)
        assertEquals(total(miss), 0)
        assertEquals(truncated(miss), false)
        // an empty query means "no filter" — the full listing comes back
        assertEquals(names(frame(dir, "")), names(frame(dir)))
        assertEquals(total(frame(dir, "")), 5)
        // case-insensitive substring, matching the server's own normalization
        val upper = frame(dir, "D000")
        assertEquals(total(upper), 5, "the filter is case-insensitive substring")
      }
    }

  test("B4. the frame echoes the filter it answered (the frontend's staleness guard)"):
    withDirs(3) { dir =>
      IO {
        assertEquals(frame(dir, "d0").hcursor.get[String]("query").toOption, Some("d0"))
        assertEquals(frame(dir, "").hcursor.get[String]("query").toOption, Some(""))
      }
    }

  // ── C. 路径直输：~ 形态 + 非法路径内联报错 ───────────────────────────────

  test("C1. `~` and `~/…` resolve to the same directory (shared expandTilde)"):
    val home = sys.props.getOrElse("user.home", "/")
    assume(os.isDir(os.Path(home)), "no real home on this host")
    IO {
      assertEquals(nebflow.shared.PathUtil.expandTilde("~"), home)
      assertEquals(nebflow.shared.PathUtil.expandTilde("~/Downloads"), home + "/Downloads")
      // and the frame builder reports the RESOLVED path (the breadcrumb feed)
      val tilde = os.Path(nebflow.shared.PathUtil.expandTilde("~"), os.pwd)
      val f = frame(tilde)
      assertEquals(f.hcursor.get[String]("path").toOption, Some(home))
      assert(names(f).contains(".nebflow"), "~ root still lists dot directories")
    }

  test("C2. an unusable path answers a TYPED error, never a silent empty list"):
    val bad = WebSocketRoutes.browseErrorFrame("/no/such/dir/xyz", "invalid-path", "No such directory: /no/such/dir/xyz", "")
    IO {
      assertEquals(bad.hcursor.get[String]("errorKind").toOption, Some("invalid-path"))
      assert(bad.hcursor.get[String]("error").exists(_.contains("No such directory")))
      assertEquals(bad.hcursor.get[Boolean]("truncated").toOption, Some(false))
      assertEquals(bad.hcursor.get[Int]("total").toOption, Some(0))
      // the distinguishing bit vs. a legitimately empty directory:
      assert(bad.hcursor.downField("errorKind").succeeded, "a failure carries errorKind")
    } *> withDirs(0) { dir =>
      IO {
        val empty = frame(dir)
        assertEquals(empty.hcursor.downField("errorKind").succeeded, false,
          "an EMPTY directory must NOT be reported as an error")
        assertEquals(total(empty), 0)
        assertEquals(truncated(empty), false)
      }
    }

  test("C3. the three error kinds are distinct and renderable"):
    val kinds = List("invalid-path" -> "/no/such", "not-a-directory" -> "/etc/hosts", "unreadable" -> "/root")
    kinds.foreach { (k, p) =>
      val j = WebSocketRoutes.browseErrorFrame(p, k, s"$k: $p", "")
      assertEquals(j.hcursor.get[String]("errorKind").toOption, Some(k))
      assert(j.hcursor.get[String]("error").exists(_.nonEmpty), s"$k must carry a message")
    }

  // ── 反向臂（同帧：点目录可达性不得回归）──────────────────────────────────

  test("REVERSE. ~/.nebflow/projects is STILL enterable and is not empty"):
    val projects = os.Path(sys.props.getOrElse("user.home", "/")) / ".nebflow" / "projects"
    assume(os.isDir(projects), "no ~/.nebflow/projects on this host")
    IO {
      val j = frame(projects)
      val ns = names(j)
      println(s"REVERSE[projects] entries=${ns.size} total=${total(j)} truncated=${truncated(j)} first3=${ns.take(3)}")
      assert(ns.nonEmpty, "the dot-dir ruling exists so this path is reachable — it must not be empty")
      assertEquals(j.hcursor.downField("errorKind").succeeded, false)
      // and the home frame still shows the way in (one level up)
      val home = frame(os.Path(sys.props.getOrElse("user.home", "/")))
      assert(names(home).contains(".nebflow"), "the entry point to that path must remain visible")
    }

  // ── 加法契约（老消费方不受影响）──────────────────────────────────────────

  test("COMPAT. the three pre-existing keys keep their exact semantics"):
    withDirs(2) { dir =>
      IO {
        val j = frame(dir)
        // `entries` is still an array of {name, path} objects, `path` a string,
        // `type` the same discriminator — no key was renamed or re-typed.
        val entries = j.hcursor.downField("entries").as[List[Json]].getOrElse(Nil)
        assert(entries.nonEmpty)
        entries.foreach { e =>
          assert(e.hcursor.get[String]("name").isRight, "every entry still carries `name`")
          assert(e.hcursor.get[String]("path").isRight, "every entry still carries `path`")
          assertEquals(e.hcursor.keys.map(_.toSet), Some(Set("name", "path")), "no entry keys were added")
        }
        assertEquals(j.hcursor.get[String]("type").toOption, Some("browseResult"))
        assertEquals(j.hcursor.get[String]("path").toOption, Some(dir.toString))
        // the ONLY new top-level keys are the declared additions
        val newKeys = j.hcursor.keys.map(_.toSet).getOrElse(Set.empty) -- Set("type", "path", "entries")
        assertEquals(newKeys, Set("total", "truncated", "query"), "the additions are exactly the declared ones")
      }
    }
