package nebflow.db

import munit.FunSuite

import java.nio.file.{Files, Path}

/**
 * V4 —— seq 连续性：计数器损毁 ⇒ 拒启（不得从 0 重号）；区间分配只跳号不重号；
 * 交叉校验以大者为准。计数器绝不数行（cap `agentd:4502-4506` 数行 ⇒ 重号的反面）。
 */
class SeqCounterSpec extends FunSuite:

  private def newRoot: Path = Files.createTempDirectory("db-seq-spec")

  test("fresh root initializes counter at next=1; first claim persists interval bound"):
    val root = newRoot
    val f = root.resolve("counter.json")
    val c = SeqCounter.open(f, treeHasData = false, interval = 8).toTry.get
    assertEquals(c.next(), 1L)
    assertEquals(c.next(), 2L)
    // 区间 [1,9) 在首次发号前已领并持久化（persistedNext = 9）。
    assertEquals(c.persistedNext, 9L)
    (0 until 6).foreach(_ => c.next()) // 3..8：耗尽第一个区间
    assertEquals(c.persistedNext, 9L, "区间未耗尽不重写计数器")
    assertEquals(c.next(), 9L, "第 9 号触发新区间 [9,17) 的持久化")
    assertEquals(c.persistedNext, 17L)

  test("corrupt counter refuses to open (never renumber from zero)"):
    val root = newRoot
    val f = root.resolve("counter.json")
    Files.writeString(f, """{"version":1,"next":"NOT-A-NUMBER"}""")
    SeqCounter.open(f, treeHasData = true, interval = 4) match
      case Left(e: DbError.RefuseStart) => assert(e.getMessage.contains("refusing to start"))
      case other => fail(s"expected RefuseStart, got $other")
    Files.writeString(f, "") // 空档同罪
    assert(SeqCounter.open(f, treeHasData = true, interval = 4).isLeft)

  test("missing counter with data-bearing tree refuses to open"):
    val root = newRoot
    val f = root.resolve("counter.json")
    SeqCounter.open(f, treeHasData = true, interval = 4) match
      case Left(e: DbError.RefuseStart) => assert(e.getMessage.contains("MISSING"))
      case other => fail(s"expected RefuseStart, got $other")

  test("missing counter on fresh root is fine (first open initializes)"):
    val root = newRoot
    val c = SeqCounter.open(root.resolve("counter.json"), treeHasData = false, interval = 4).toTry.get
    assertEquals(c.next(), 1L)

  test("interval allocation across reopen only skips, never duplicates"):
    val root = newRoot
    val f = root.resolve("counter.json")
    val c1 = SeqCounter.open(f, treeHasData = false, interval = 8).toTry.get
    val seqs1 = (1 to 5).map(_ => c1.next()) // 用 1..5；弃 6..8（模拟崩溃弃号）
    assertEquals(seqs1.toList, List(1L, 2L, 3L, 4L, 5L))
    val c2 = SeqCounter.open(f, treeHasData = true, interval = 8).toTry.get
    val seqs2 = (1 to 3).map(_ => c2.next())
    // 新区间 [9,17)：与旧号无交集（不重号），且高于已见最大号（不回退）。
    assert(seqs2.min > seqs1.max, s"seq regression: $seqs2 vs $seqs1")
    assert(seqs2.distinct.size == seqs2.size)

  test("reconcileWith bumps a lagging counter to the larger truth and persists it"):
    val root = newRoot
    val f = root.resolve("counter.json")
    val c = SeqCounter.open(f, treeHasData = false, interval = 4).toTry.get
    c.next() // 1
    assert(c.reconcileWith(100L), "counter (next=5) lagging stream tail (100) must bump")
    assertEquals(c.persistedNext, 101L)
    assertEquals(c.next(), 101L, "发号从抬升后继续")
    val reloaded = SeqCounter.open(f, treeHasData = true, interval = 4).toTry.get
    assertEquals(reloaded.persistedNext, 105L, "抬升已持久化并随新区间推进")
