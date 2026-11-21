package nebflow.core.tests

import cats.effect.IO
import munit.CatsEffectSuite

import java.nio.file.{Files, Path as NioPath}
import scala.concurrent.duration.*

/**
 * M2 契约（实施文档 M2 行，作者令 2026-11-19）：
 *
 *   ① 同一任务两次执行拿到的源快照摘要一致；
 *   ② 源树变更后摘要变化（且不是静默复用旧快照）；
 *   ③ 篡改快照内一个字节 ⇒ 校验失败并**报出是哪个文件**（负控在用例内）；
 *   ④ 并发提交第二个任务 ⇒ 被明确拒绝（退出码 3 的那条路径），持有者读数可读。
 *
 * 另外锁住两条实现取舍：路径与溯源取自 Git 索引、字节取自工作区（已暂存后又编辑、
 * 未暂存修改、删除、改名都要在清单里如实体现），以及纳入路径的安全校验（不许逃出工作区）。
 */
class SourceSnapshotSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private def git(cwd: os.Path, args: String*): Unit =
    val out = os.proc("git", args).call(cwd = cwd, stderr = os.Pipe, check = false)
    assert(out.exitCode == 0, s"git ${args.mkString(" ")} → ${out.exitCode}: ${out.err.text()}")

  /** 一个带初始提交的临时 Git 工作树；`.git` 之外只放 spec 用得到的文件。 */
  private def gitTree(): IO[(os.Path, os.Path)] =
    for
      root <- IO(os.temp.dir(prefix = "snapshot-src-"))
      store <- IO(os.temp.dir(prefix = "snapshot-store-"))
      _ <- IO {
        git(root, "init", "-q", "-b", "main")
        git(root, "config", "user.email", "spec@example.invalid")
        git(root, "config", "user.name", "Spec")
        os.write.over(root / "a.txt", "alpha\n")
        os.write.over(root / "b.txt", "beta\n")
        os.makeDir.all(root / "nested")
        os.write.over(root / "nested" / "c.txt", "gamma\n")
        os.write.over(root / "bin.sh", "#!/bin/sh\necho hi\n")
        java.nio.file.Files.setPosixFilePermissions(
          root.toNIO.resolve("bin.sh"),
          java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x")
        )
        git(root, "add", "-A")
        git(root, "commit", "-qm", "seed")
      }
    yield (root, store)

  // ── ① 同一内容两次捕获 ⇒ 同一个摘要 ──────────────────────────────────────

  test("① 同一工作区两次捕获得到同一个快照摘要（第二次不再重写对象）") {
    for
      (root, store) <- gitTree()
      first <- SourceSnapshot.capture(root, snapshotRoot = store)
      second <- SourceSnapshot.capture(root, snapshotRoot = store)
    yield
      assert(first.isRight, s"first capture failed: $first")
      assert(second.isRight, s"second capture failed: $second")
      assertEquals(first.toOption.get.id, second.toOption.get.id)
      assertEquals(first.toOption.get.reused, false)
      assertEquals(second.toOption.get.reused, true)
  }

  // ── ② 源树变更 ⇒ 摘要变化（不是静默复用） ──────────────────────────────

  test("② 改一个字节、删一个文件、改名、改可执行位后摘要全部变化") {
    for
      (root, store) <- gitTree()
      base <- SourceSnapshot.capture(root, snapshotRoot = store)
      afterEdit <- IO { os.write.over(root / "a.txt", "alpha-changed\n") }.flatMap(_ =>
        SourceSnapshot.capture(root, snapshotRoot = store)
      )
      _ <- IO(git(root, "checkout", "--", "a.txt"))
      afterDelete <- IO(os.remove(root / "b.txt")).flatMap(_ => SourceSnapshot.capture(root, snapshotRoot = store))
      _ <- IO(git(root, "checkout", "--", "b.txt"))
      afterRename <- IO { git(root, "mv", "nested/c.txt", "nested/renamed.txt") }.flatMap(_ =>
        SourceSnapshot.capture(root, snapshotRoot = store)
      )
      _ <- IO(git(root, "mv", "nested/renamed.txt", "nested/c.txt"))
      afterMode <- IO(
        java.nio.file.Files.setPosixFilePermissions(
          root.toNIO.resolve("bin.sh"),
          java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--")
        )
      ).flatMap(_ => SourceSnapshot.capture(root, snapshotRoot = store))
    yield
      val ids = List(base, afterEdit, afterDelete, afterRename, afterMode).map(_.toOption.get.id)
      assertEquals(ids.distinct.size, 5, s"每个变更都应产出不同摘要：$ids")
      // 两次「同一内容」的中间态仍然稳定：改回去再捕获应与第一次相同
      val restored = SourceSnapshot.capture(root, snapshotRoot = store).unsafeRunSync()
      assertEquals(restored.toOption.get.id, ids.last)
  }

  test("②b 已暂存后又编辑、未暂存修改、删除在清单里如实体现（字节取工作区而非索引）") {
    for
      (root, store) <- gitTree()
      _ <- IO {
        os.write.over(root / "a.txt", "staged\n")
        git(root, "add", "a.txt")
        os.write.over(root / "a.txt", "staged-then-edited\n") // 索引里的字节 = "staged"
        os.write.over(root / "b.txt", "unstaged\n")
        os.remove(root / "nested" / "c.txt")
      }
      ref <- SourceSnapshot.capture(root, snapshotRoot = store)
      manifest = ref.toOption.get
      entries <- IO(SourceSnapshot.readManifest(manifest.manifest / os.up).toOption.get)
      byPath = entries.entries.map(e => e.path -> e).toMap
      _ <- IO {
        val a = byPath("a.txt")
        val expected = java.security.MessageDigest
          .getInstance("SHA-256")
          .digest("staged-then-edited\n".getBytes("UTF-8"))
          .map(b => f"$b%02x")
          .mkString
        assertEquals(a.sha256, Some(expected), "清单里 a.txt 必须是工作区字节（已暂存后又编辑）")
        assertEquals(byPath("nested/c.txt").kind, "absent", "追踪但已删除的路径必须是 absent 项")
        assert(byPath.contains("b.txt"))
        assertEquals(byPath("bin.sh").mode, "100755", "可执行位必须进清单")
      }
    yield ()
  }

  // ── ③ 篡改一个字节 ⇒ 校验失败并点名文件（负控） ────────────────────────

  test("③ 篡改快照内一个字节 ⇒ verify 失败且报出是哪个文件；恢复后必绿") {
    for
      (root, store) <- gitTree()
      ref <- SourceSnapshot.capture(root, snapshotRoot = store)
      snapshot = ref.toOption.get
      manifest <- IO(SourceSnapshot.readManifest(snapshot.manifest / os.up).toOption.get)
      victim = manifest.entries.find(e => e.path == "nested/c.txt").get
      objectPath = snapshot.manifest / os.up / "objects" / victim.sha256.get
      clean <- SourceSnapshot.verify(store, snapshot.id)
      _ <- IO {
        val bytes = os.read.bytes(objectPath)
        bytes(0) = (bytes(0) ^ 0x01).toByte // 单字节翻转
        os.write.over(objectPath, bytes)
      }
      tampered <- SourceSnapshot.verify(store, snapshot.id)
      message = tampered.swap.toOption.map(_.message).getOrElse("")
      _ <- IO {
        assertEquals(clean.isRight, true, s"未篡改时必须绿：$clean")
        assert(tampered.isLeft, "篡改后必须红")
        assert(message.contains("nested/c.txt"), s"必须点名文件，实际：$message")
        assert(message.contains("快照校验失败"), message)
      }
      _ <- IO(os.write.over(objectPath, os.read.bytes(root / "nested" / "c.txt")))
      restored <- SourceSnapshot.verify(store, snapshot.id)
    yield assertEquals(restored.isRight, true, s"恢复字节后必须重新绿：$restored")
  }

  test("③b 清单本身被改动（目录名与摘要不符）也判红") {
    for
      (root, store) <- gitTree()
      ref <- SourceSnapshot.capture(root, snapshotRoot = store)
      snapshot = ref.toOption.get
      _ <- IO {
        val manifestPath = snapshot.manifest
        os.write.over(manifestPath, os.read(manifestPath).replace("\"size\" : ", "\"size\" : 1"))
      }
      result <- SourceSnapshot.verify(store, snapshot.id)
    yield
      assert(result.isLeft, "篡改清单必须红")
      assert(result.swap.toOption.get.message.contains("清单"), result.swap.toOption.get.message)
  }

  // ── ④ 独占执行槽 ────────────────────────────────────────────────────────

  test("④ 并发提交：第一个拿到槽，第二个被明确拒绝并读到持有者") {
    val slot = os.temp.dir(prefix = "slot-")
    val run = for
      first <- SourceSnapshot.ExecutionSlot.acquire(slot, "task-one")
      second <- SourceSnapshot.ExecutionSlot.acquire(slot, "task-two")
      status <- IO(SourceSnapshot.ExecutionSlot.status(slot))
      released <- SourceSnapshot.ExecutionSlot.release(slot)
      after <- IO(SourceSnapshot.ExecutionSlot.status(slot))
      third <- SourceSnapshot.ExecutionSlot.acquire(slot, "task-three")
    yield (first, second, status, released, after, third)
    run.map { case (first, second, status, released, after, third) =>
      assert(first.toOption.get.isInstanceOf[SourceSnapshot.ExecutionSlot.Outcome.Acquired], first.toString)
      val busy = second.toOption.get
      assert(busy.isInstanceOf[SourceSnapshot.ExecutionSlot.Outcome.Busy], s"第二个必须被拒绝：$busy")
      val holder = busy.asInstanceOf[SourceSnapshot.ExecutionSlot.Outcome.Busy].holder
      assertEquals(holder.map(_.task), Some("task-one"))
      assertEquals(status.map(_.task), Some("task-one"))
      assertEquals(released, true)
      assertEquals(after, None)
      assert(third.toOption.get.isInstanceOf[SourceSnapshot.ExecutionSlot.Outcome.Acquired])
    }
  }

  test("④b 陈旧锁（持有者进程已不在）可被接管；活着的持有者不被抢走") {
    val slot = os.temp.dir(prefix = "slot-stale-")
    val run = for
      _ <- IO(
        os.write.over(
          SourceSnapshot.ExecutionSlot.lockPath(slot),
          """{"pid":999999,"task":"dead","since":"2026-11-19T00:00:00Z"}""",
          createFolders = true
        )
      )
      takenOver <- SourceSnapshot.ExecutionSlot.acquire(slot, "fresh", alive = _ => false)
      _ <- IO(
        os.write.over(
          SourceSnapshot.ExecutionSlot.lockPath(slot),
          """{"pid":999999,"task":"alive","since":"2026-11-19T00:00:00Z"}"""
        )
      )
      blocked <- SourceSnapshot.ExecutionSlot.acquire(slot, "should-lose", alive = _ => true)
    yield (takenOver, blocked)
    run.map { case (takenOver, blocked) =>
      assert(takenOver.toOption.get.isInstanceOf[SourceSnapshot.ExecutionSlot.Outcome.Acquired], s"陈旧锁必须可接管：$takenOver")
      assert(blocked.toOption.get.isInstanceOf[SourceSnapshot.ExecutionSlot.Outcome.Busy], s"活着的持有者不许被抢：$blocked")
    }
  }

  // ── 路径安全与排除面 ────────────────────────────────────────────────────

  test("纳入路径不许逃出工作区；构建缓存与凭据面不许进清单") {
    val root = os.temp.dir(prefix = "snapshot-safe-")
    val attempts =
      List("../etc/passwd", "/etc/passwd", "a/../../b", "node_modules/x.js", "credentials.json", ".env", ".git/config")
    val results = attempts.map(p => SourceSnapshot.safeRelative(root, p))
    assert(results.forall(_.isLeft), s"全部必须拒绝：$results")
    val ok = SourceSnapshot.safeRelative(root, "docs/plan.md")
    assertEquals(ok.isRight, true, ok.toString)
  }
