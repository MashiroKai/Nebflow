package nebflow.db

import munit.FunSuite

import java.nio.file.{Files, Path}

/** 接线第一阶 C1：宿主共享句柄注册表——同根单句柄（INSTANCE.lock 归一）、best-effort、释放幂等。 */
class DbHostSpec extends FunSuite:

  private def tempRoot(): Path = Files.createTempDirectory(s"dbhost-${System.nanoTime()}")

  test("C1: same data root yields the same memoized handle; different roots distinct"):
    val root = tempRoot()
    try
      val h1 = DbHost.openBestEffort(root)
      val h2 = DbHost.openBestEffort(root)
      assert(h1.isDefined && h2.isDefined, "open should succeed on a fresh temp root")
      assert(h1.get eq h2.get, "same root must memoize to the SAME handle (INSTANCE.lock 归一)")
      val other = tempRoot()
      try
        val h3 = DbHost.openBestEffort(other)
        assert(h3.isDefined && !(h3.get eq h1.get), "different roots get distinct handles")
      finally DbHost.release(other)
    finally DbHost.release(root)

  test("C1: release closes the handle and a fresh open re-claims (generation advances)"):
    val root = tempRoot()
    try
      val h1 = DbHost.openBestEffort(root)
      assert(h1.isDefined)
      val gen1 = h1.get.instanceLockGeneration
      DbHost.release(root)
      assert(!DbHost.isOpen(root), "release must deregister")
      val h2 = DbHost.openBestEffort(root)
      assert(h2.isDefined)
      assert(h2.get.instanceLockGeneration > gen1,
        "re-open after release must see an advanced generation (世代感知)")
    finally DbHost.release(root)

  test("C1: best-effort — a root that is a regular file fails open without memoizing"):
    val notADir = Files.createTempFile("dbhost-notadir", ".tmp")
    try
      val r1 = DbHost.openBestEffort(notADir)
      assert(r1.isEmpty, "open on a regular-file path must degrade to None (best-effort)")
      val r2 = DbHost.openBestEffort(notADir)
      assert(r2.isEmpty, "failure must not memoize; still None on retry")
      assert(!DbHost.isOpen(notADir), "no handle registered for the failed root")
    finally Files.deleteIfExists(notADir)

  test("C1: opened handle is writable through a real guard-doc roundtrip"):
    val root = tempRoot()
    try
      DbHost.openBestEffort(root).foreach { h =>
        import cats.effect.unsafe.implicits.global
        import nebflow.db.format.GuardDocs
        val doc = GuardDocs.NotifyGuardDoc(1, "dbhost-spec:failed", List(1L, 2L), 99L, 5L)
        h.writeNotifyGuard(doc).unsafeRunSync()
        h.readNotifyGuard("dbhost-spec:failed").unsafeRunSync() match
          case ReadResult.Present(got) => assertEquals(got, doc)
          case other => fail(s"expected Present, got $other")
      }
    finally DbHost.release(root)

end DbHostSpec
