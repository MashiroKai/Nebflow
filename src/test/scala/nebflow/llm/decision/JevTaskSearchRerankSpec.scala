package nebflow.llm.decision

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.core.index.TaskSearchRerank
import nebflow.core.jev.{JevAllocation, JevAllocatorPort, JevFallbackKind}

import scala.concurrent.duration.*

/**
 * task-rag 批（#1227 / §Batch 7）验收：Jev 重排执行体（可选 Jev 重排）。
 *
 * 中心性质：Jev 未配 ⇒ 恒 `None`（跳过重排照常返回——原文「可选」的缺省路径）；
 * 配置在册 ⇒ 机械映射到 `JevAllocatorPort.allocate`（判据入选者前置，全集排列
 * 绝不删命中）；`FellBack` / 异常 ⇒ `None`。🔴 零真实 provider：判定面以 stub
 * `JevAllocatorPort` 驱动；`fromConfig` 路径以未配置 dataRoot 驱动（无 jev 块）。
 */
class JevTaskSearchRerankSpec extends CatsEffectSuite:

  override val munitIOTimeout = 60.seconds

  private def candidate(id: String) = TaskSearchRerank.Candidate(id, s"text of $id")

  test("sanitizeName: entryId stripped to question-id-safe chars") {
    assertEquals(JevTaskSearchRerank.sanitizeName("task:12"), "task_12")
    assertEquals(JevTaskSearchRerank.sanitizeName("delegate:delegate-kernel-ab_1.2"), "delegate_delegate-kernel-ab_1.2")
    assertEquals(JevTaskSearchRerank.sanitizeName("already/fine-1.0"), "already_fine-1.0")
  }

  test("skipFace: always None (the off shape registered when Jev is unconfigured)") {
    JevTaskSearchRerank.skipFace.rerank("q", List(candidate("task:1"))).unsafeRunSync() match
      case None => ()
      case other => fail(s"expected None, got $other")
  }

  test("face with an allocator: selected candidates lead (original order kept), rest follow; no hits dropped") {
    val stub = new JevAllocatorPort:
      def allocate(taskText: String, catalog: List[nebflow.core.jev.CatalogEntry]): IO[JevAllocation] =
        // 判据入选 = 名字含 "rel" 的候选（fixture 判定，不涉真实 provider）
        IO.pure(JevAllocation.Allocated(catalog.map(_.name).filter(_.contains("rel"))))

    val face = JevTaskSearchRerank.faceFor(Some(stub))
    val candidates = List(
      candidate("task:1"),
      TaskSearchRerank.Candidate("delegate:rel-a", "relevant one"),
      TaskSearchRerank.Candidate("delegate:rel-b", "relevant two"),
      candidate("task:9")
    )
    val out = face.rerank("find relevant items", candidates).unsafeRunSync()
    val order = out match
      case Some(o) => o
      case None => fail("expected Some(ordering), got None")
    // 入选者前置（保持候选原序 rel-a → rel-b），未入选续后（原序 1 → 9）；全集排列
    assertEquals(order, List("delegate:rel-a", "delegate:rel-b", "task:1", "task:9"))
    assertEquals(order.size, candidates.size, "重排不增删命中")
  }

  test("face with an allocator: FellBack and exceptions yield None (skip, keep incoming order)") {
    val fallingBack = new JevAllocatorPort:
      def allocate(taskText: String, catalog: List[nebflow.core.jev.CatalogEntry]): IO[JevAllocation] =
        IO.pure(JevAllocation.FellBack(JevFallbackKind.Timeout, "fixture timeout"))
    JevTaskSearchRerank
      .faceFor(Some(fallingBack))
      .rerank("q", List(candidate("task:1")))
      .unsafeRunSync() match
      case None => ()
      case other => fail(s"FellBack ⇒ skip (None), got $other")

    val throwing = new JevAllocatorPort:
      def allocate(taskText: String, catalog: List[nebflow.core.jev.CatalogEntry]): IO[JevAllocation] =
        IO.raiseError(new RuntimeException("fixture provider crash"))
    JevTaskSearchRerank
      .faceFor(Some(throwing))
      .rerank("q", List(candidate("task:1")))
      .unsafeRunSync() match
      case None => ()
      case other => fail(s"exception ⇒ skip (None), got $other")
  }

  test("face from live config on an unconfigured dataRoot: skip shape (Jev 未配)") {
    // 本 JVM 的 dataRoot 在无 jev 块的临时根上（未配置）⇒ face = 恒 None 形态
    val original = nebflow.shared.PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "nb-jev-rerank-")
    nebflow.shared.PathUtil.setDataRoot(tmp)
    try
      val face = JevTaskSearchRerank.face()
      face.rerank("q", List(candidate("task:1"))).unsafeRunSync() match
        case None => ()
        case other => fail(s"unconfigured Jev ⇒ skip (None), got $other")
    finally
      nebflow.shared.PathUtil.setDataRoot(original)
      os.remove.all(tmp)
  }

end JevTaskSearchRerankSpec
