package nebflow.gateway

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import munit.CatsEffectSuite

import java.nio.file.Files as JFiles
import java.nio.file.Paths

import scala.concurrent.duration.*

import io.circe.Json
import nebflow.shared.NebflowLogger

/**
 * explorer-rt backend spec (design card chain-n-1981ce87 §4 anchors R1-R5)
 * against a REAL WatchService — the mac polling implementation is the
 * production shape on the author's host (spike: flat ~2.0 s, batched), so
 * the timing budgets here assume 2 s poll cadence + 500 ms debounce with
 * generous headroom. Linux inotify (sub-second) only makes these faster.
 *
 * R1 订阅后建/改/删 → fsChanged dirs 含受影响目录（突发去抖合并 ≤2 帧；polling
 *   实现会对父目录合法共燃——子条目 mtime 变化即父 listing 变化——故含而非全等）
 * R2 未订阅连接零帧；unsubscribe 后零帧；连接关闭后订阅表清空（状态断言）
 * R3 rel-dir 逃逸拒绝（与 listDir canonical 判据同族的 fail-closed 守卫）
 * R4 合成 OVERFLOW → 帧 overflow:true 且 dirs 为空
 * R5 只注册可见展开目录：未注册目录（含新建子树内部）内的突发 ⇒ 零帧
 *   （新建子树本身是根 listing 的合法变化，根级帧不受此断言约束）
 */
class ExplorerWatchSessionSpec extends CatsEffectSuite:

  private val log = NebflowLogger.forName("explorerrt-spec")

  private def withTempRoot(f: os.Path => IO[Any]): IO[Any] =
    IO.delay {
      val canon = Paths.get(JFiles.createTempDirectory("explorerrt-spec").toString).toFile.getCanonicalPath
      os.Path(canon)
    }.bracket(tmp => f(tmp).void)(tmp => IO(os.remove.all(tmp)).void)

  /**
   * Canonicalized child dir (matches what the production call site feeds
   * subscribe — the route canonicalizes before calling).
   */
  private def canonRoot(p: os.Path): os.Path = os.Path(p.toIO.getCanonicalPath)

  private def capture(): IO[Ref[IO, Vector[Json]]] = Ref.of[IO, Vector[Json]](Vector.empty)

  private def isFsChanged(j: Json): Boolean =
    j.hcursor.get[String]("type").toOption.contains("fsChanged")

  private def isFileOpError(j: Json): Boolean =
    j.hcursor.get[String]("type").toOption.contains("fileOpError")

  /** Poll `check` every 100 ms until Some or `timeout` elapses. */
  private def await[A](timeout: FiniteDuration)(check: IO[Option[A]]): IO[A] =
    IO.monotonic.flatMap { start =>
      def elapsed: IO[FiniteDuration] = IO.monotonic.map(_ - start)
      def loop: IO[A] = check.flatMap {
        case Some(a) => IO.pure(a)
        case None =>
          elapsed.flatMap { d =>
            if d > timeout then IO.raiseError(new RuntimeException("await: timeout"))
            else IO.sleep(100.millis) *> loop
          }
      }
      loop
    }

  private def fsFrames(ref: Ref[IO, Vector[Json]]): IO[Vector[Json]] =
    ref.get.map(_.filter(isFsChanged))

  private def sessionFor(ref: Ref[IO, Vector[Json]]): ExplorerWatchSession =
    new ExplorerWatchSession(j => ref.update(_ :+ j), log)

  private def dirsOf(frame: Json): List[String] =
    frame.hcursor.downField("dirs").as[List[String]].getOrElse(List.empty)

  // ============================================================
  // R1 — event delivery + debounce merge
  // ============================================================

  test("R1a: subscribed create/modify/delete deliver fsChanged with the affected dir") {
    withTempRoot { tmp =>
      for
        root <- IO.pure(canonRoot(tmp))
        _ <- IO(os.makeDir.all(root / "src"))
        ref <- capture()
        ws = sessionFor(ref)
        _ <- ws.subscribe(root, "", Some(List("src")))
        _ <- IO(os.write(root / "src" / "a.txt", "one"))
        // Polling watchers legitimately co-fire the PARENT dir too (the child
        // entry's mtime changed in the root listing) — assert "src" is in the
        // merged dirs, not strict equality.
        f1 <- await(20.seconds)(fsFrames(ref).map(_.find(f => dirsOf(f).contains("src"))))
        _ <- IO(os.write.over(root / "src" / "a.txt", "two"))
        _ <- IO(os.write(root / "src" / "b.txt", "three"))
        _ <- IO(os.remove(root / "src" / "b.txt"))
        // Discriminate the new delivery by count (frames with identical dirs
        // are Json-equal — content alone cannot prove freshness).
        n1 <- fsFrames(ref).map(_.size)
        f2 <- await(20.seconds)(fsFrames(ref).map { fs =>
          Option.when(fs.size > n1 && dirsOf(fs.last).contains("src"))(fs.last)
        })
        _ <- ws.close()
      yield
        assertEquals(f1.hcursor.get[Boolean]("overflow").toOption, Some(false))
        assertEquals(f1.hcursor.get[String]("rootPath").toOption, Some(""))
        assert(dirsOf(f2).contains("src"), s"post-modify frame dirs: ${dirsOf(f2)}")
    }
  }

  test("R1b: a 10-file burst collapses to at most 2 fsChanged frames (debounce merge)") {
    withTempRoot { tmp =>
      for
        root <- IO.pure(canonRoot(tmp))
        _ <- IO(os.makeDir.all(root / "src"))
        ref <- capture()
        ws = sessionFor(ref)
        _ <- ws.subscribe(root, "", Some(List("src")))
        _ <- IO((1 to 10).foreach(i => os.write(root / "src" / s"burst$i.txt", "x")))
        _ <- await(20.seconds)(fsFrames(ref).map(v => Option.when(v.nonEmpty)(())))
        // let any second flush land (poll tick + debounce + margin)
        _ <- IO.sleep(4.seconds)
        n <- fsFrames(ref).map(_.size)
        _ <- ws.close()
      yield assert(n <= 2, s"burst produced $n fsChanged frames (card R1: ≤2)")
    }
  }

  test("R1c: root-level events report the empty relative dir") {
    withTempRoot { tmp =>
      for
        root <- IO.pure(canonRoot(tmp))
        ref <- capture()
        ws = sessionFor(ref)
        _ <- ws.subscribe(root, "", Some(Nil))
        _ <- IO(os.write(root / "rootfile.txt", "x"))
        f <- await(20.seconds)(fsFrames(ref).map(_.find(dirsOf(_) == List(""))))
        _ <- ws.close()
      yield assertEquals(f.hcursor.get[Boolean]("overflow").toOption, Some(false))
    }
  }

  // ============================================================
  // R2 — isolation, unsubscribe, close-clears-table
  // ============================================================

  test("R2: unsubscribed session gets zero frames; unsubscribe stops delivery; close empties the table") {
    withTempRoot { tmp =>
      for
        root <- IO.pure(canonRoot(tmp))
        _ <- IO(os.makeDir.all(root / "src"))
        subRef <- capture()
        other <- capture()
        wsSub = sessionFor(subRef)
        wsOther = sessionFor(other)
        _ <- wsSub.subscribe(root, "", Some(List("src")))
        _ <- IO(os.write(root / "src" / "r2a.txt", "x"))
        _ <- await(20.seconds)(fsFrames(subRef).map(v => Option.when(v.nonEmpty)(())))
        _ <- IO.sleep(500.millis)
        nOther <- fsFrames(other).map(_.size)
        _ <- wsSub.unsubscribe("")
        _ <- IO.sleep(200.millis)
        _ <- IO(os.write(root / "src" / "r2b.txt", "x"))
        _ <- IO.sleep(6.seconds) // > 2 poll cycles + debounce — negative window
        nAfter <- fsFrames(subRef).map(_.size)
        _ <- wsSub.close()
        _ <- wsOther.close()
        snap <- IO(wsSub.tableSnapshot)
      yield
        assertEquals(nOther, 0, "unsubscribed connection must receive zero fsChanged frames")
        assertEquals(nAfter, 1, "unsubscribe must stop further delivery")
        assertEquals(snap, Nil, "close() must empty the subscription table")
    }
  }

  // ============================================================
  // R3 — rel-dir escape guard (fail-closed, listDir canonical-guard family)
  // ============================================================

  test("R3: traversal / absolute / non-live subscribe rejected, nothing registered") {
    withTempRoot { tmp =>
      for
        root <- IO.pure(canonRoot(tmp))
        ref <- capture()
        ws = sessionFor(ref)
        e1 <- ws.subscribe(root, "", Some(List("../outside"))).attempt
        e2 <- ws.subscribe(root, "", Some(List("/etc"))).attempt
        e3 <- ws.subscribe(root, "", Some(List("a/../../b"))).attempt
        dead = new ExplorerWatchSession(j => ref.update(_ :+ j), log, live = false)
        e4 <- dead.subscribe(root, "", Some(List("src"))).attempt
        _ <- IO(os.write(root / "sneaky.txt", "x"))
        _ <- IO.sleep(5.seconds)
        n <- fsFrames(ref).map(_.size)
        snap <- IO(ws.tableSnapshot)
        _ <- ws.close()
      yield
        assert(e1.isLeft, s"traversal dir must be rejected: $e1")
        assert(e2.isLeft, s"absolute dir must be rejected: $e2")
        assert(e3.isLeft, s"embedded traversal must be rejected: $e3")
        assert(e4.isLeft, s"non-live (REST facade) session must reject subscribe: $e4")
        assertEquals(n, 0, "rejected subscribes must not register anything")
        assertEquals(snap, Nil, "failed subscribes must not leave table entries")
    }
  }

  test("R3b: nonexistent watch root rejected") {
    withTempRoot { tmp =>
      for
        root <- IO.pure(canonRoot(tmp))
        ref <- capture()
        ws = sessionFor(ref)
        e1 <- ws.subscribe(root / "does-not-exist", "", Some(Nil)).attempt
        _ <- ws.close()
      yield assert(e1.isLeft, s"nonexistent root must be rejected: $e1")
    }
  }

  test("R3c: declared dirs containing the root coordinate '' subscribe cleanly (round-1 R12 seam contract)") {
    withTempRoot { tmp =>
      for
        root <- IO.pure(canonRoot(tmp))
        _ <- IO(os.makeDir.all(root / "projA"))
        _ <- IO(os.makeDir.all(root / "projA" / "src"))
        ref <- capture()
        ws = sessionFor(ref)
        // The REAL frontend frame always leads dirs with '' (explorer.js
        // visibleWatchDirs). Verify round-1 R12 caught the backend rejecting
        // the WHOLE subscribe with "empty path" over it — this pins the
        // seam: '' in dirs is the root coordinate, not an invalid path.
        _ <- ws.subscribe(root, "", Some(List("", "projA", "projA/src")))
        _ <- IO(os.write(root / "projA" / "src" / "seam.txt", "x"))
        f1 <- await(20.seconds)(fsFrames(ref).map(_.find(f => dirsOf(f).contains("projA/src"))))
        _ <- IO(os.write(root / "rootmark.txt", "x"))
        f2 <- await(20.seconds)(fsFrames(ref).map(_.find(f => dirsOf(f).contains(""))))
        snap <- IO(ws.tableSnapshot)
        _ <- ws.close()
      yield
        assertEquals(f1.hcursor.get[Boolean]("overflow").toOption, Some(false))
        assert(dirsOf(f1).contains("projA/src"), s"declared-subdir event must deliver: ${dirsOf(f1)}")
        assert(dirsOf(f2).contains(""), s"root-level event must still deliver: ${dirsOf(f2)}")
        // '' declared must NOT double-register the root (registerDir dedup):
        // visible set = root("") + projA + projA/src = exactly 3.
        assertEquals(snap, List(("", 3)), s"registration set must be root+projA+projA/src exactly: $snap")
    }
  }

  // ============================================================
  // R4 — synthetic OVERFLOW degrades to overflow:true with empty dirs
  // ============================================================

  test("R4: overflow flag flushes an overflow frame with empty dirs, then delivery resumes") {
    withTempRoot { tmp =>
      for
        root <- IO.pure(canonRoot(tmp))
        _ <- IO(os.makeDir.all(root / "src"))
        ref <- capture()
        ws = sessionFor(ref)
        _ <- ws.subscribe(root, "", Some(List("src")))
        _ <- ws.simulateOverflow("")
        f1 <- await(5.seconds)(
          fsFrames(ref).map(_.find(f => f.hcursor.get[Boolean]("overflow").toOption.contains(true)))
        )
        _ <- IO(os.write(root / "src" / "after-overflow.txt", "x"))
        f2 <- await(20.seconds)(
          fsFrames(ref).map(
            _.find(f => dirsOf(f).contains("src") && !f.hcursor.get[Boolean]("overflow").toOption.contains(true))
          )
        )
        _ <- ws.close()
      yield
        assertEquals(dirsOf(f1), Nil, "overflow frame must carry an empty dirs list")
        assertEquals(f1.hcursor.get[String]("rootPath").toOption, Some(""))
        assertEquals(dirsOf(f2).contains("src"), true, s"delivery must resume after an overflow, dirs=${dirsOf(f2)}")
    }
  }

  // ============================================================
  // R5 — only visible dirs are registered (HIDDEN_DIRS anchor)
  // ============================================================

  test("R5: bursts in unregistered dirs (hidden analogue + brand-new subtree) produce zero frames") {
    withTempRoot { tmp =>
      for
        root <- IO.pure(canonRoot(tmp))
        _ <- IO(os.makeDir.all(root / "src"))
        _ <- IO(os.makeDir.all(root / "target"))
        ref <- capture()
        ws = sessionFor(ref)
        _ <- ws.subscribe(root, "", Some(List("src"))) // visible set: src only
        _ <- IO(os.write(root / "target" / "churn.class", "x"))
        _ <- IO(os.write(root / "target" / "churn2.class", "x"))
        // Creating newdir itself is a ROOT-listing change (the root is a
        // visible, registered dir) — that legitimately fires a root-level
        // event. What must stay silent: anything INSIDE target / newdir.
        _ <- IO(os.makeDir.all(root / "newdir"))
        _ <- IO(os.write(root / "newdir" / "x.txt", "x"))
        _ <- IO.sleep(6.seconds) // > 2 poll cycles + debounce — negative window
        negFrames <- fsFrames(ref)
        // liveness control: the SAME subscription still sees registered dirs
        _ <- IO(os.write(root / "src" / "control.txt", "x"))
        _ <- await(20.seconds)(fsFrames(ref).map(v => Option.when(v.size > negFrames.size)(())))
        _ <- ws.close()
      yield assert(
        negFrames.flatMap(dirsOf).forall(d => d == ""),
        s"unregistered dirs must not be reported, got: ${negFrames.map(_.noSpaces)}"
      )
    }
  }
end ExplorerWatchSessionSpec
