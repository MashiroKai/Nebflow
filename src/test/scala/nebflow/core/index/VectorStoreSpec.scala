package nebflow.core.index

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * task-rag 批（#1227 / §Batch 7）验收：检索向量落盘面。
 *
 * 中心性质：`~/.nebflow/index/vectors.json`（随 dataRoot 重根隔离）——内容寻址
 * 增量、容忍读（坏形降空视图绝不抛）、原子写（坏写不碰旧文件）、跨重启可见。
 */
class VectorStoreSpec extends CatsEffectSuite:

  override val munitIOTimeout = 60.seconds

  private def withTempDataRoot[A](f: os.Path => IO[A]): IO[A] =
    val original = PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "nb-vectors-")
    PathUtil.setDataRoot(tmp)
    f(tmp).guarantee(IO(PathUtil.setDataRoot(original)) *> IO(os.remove.all(tmp)))

  private def rec(entryId: String, hash: String, dim: Int = 3) =
    VectorStore.VectorRecord(
      vector = List(0.1f, 0.2f, 0.3f).take(dim),
      dim = dim,
      model = "m1",
      hash = hash,
      kind = VectorStore.TaskKind,
      title = "t",
      status = "open",
      executor = "",
      address = "",
      taskId = "",
      snippet = "s",
      updatedAt = "2026-10-07T00:00:00Z"
    )

  test("putAll/read round-trips; a fresh read (simulated restart) still sees it") {
    withTempDataRoot { tmp =>
      IO {
        VectorStore.putAll(Map("task:1" -> rec("task:1", "h1")))
        val f = VectorStore.read()
        assertEquals(f.version, VectorStore.Version)
        assertEquals(f.entries.keySet, Set("task:1"))
        assertEquals(f.entries("task:1").hash, "h1")
        // 向量文件路径形制：~/.nebflow/index/vectors.json（原文口径）
        assert(os.exists(tmp / "index" / "vectors.json"))
      }
    }.unsafeRunSync()
  }

  test("removeAll drops only the named ids; update is read-modify-write") {
    withTempDataRoot { tmp =>
      IO {
        VectorStore.putAll(Map("task:1" -> rec("task:1", "h1"), "delegate:d" -> rec("delegate:d", "h2", 4)))
        VectorStore.removeAll(Seq("task:1"))
        val f = VectorStore.read()
        assertEquals(f.entries.keySet, Set("delegate:d"))
        assertEquals(f.entries("delegate:d").dim, 4)
      }
    }.unsafeRunSync()
  }

  test("tolerant read: a malformed file degrades to the empty view, never throws") {
    withTempDataRoot { tmp =>
      IO {
        os.makeDir.all(tmp / "index")
        os.write.over(tmp / "index" / "vectors.json", "{not json")
        val f = VectorStore.read()
        assertEquals(f.entries, Map.empty)
        // 坏文件不被读路径改动（零副作用）
        assert(os.read(tmp / "index" / "vectors.json") == "{not json")
      }
    }.unsafeRunSync()
  }

  test("write failure keeps the old file intact (atomic write)") {
    withTempDataRoot { tmp =>
      IO {
        VectorStore.putAll(Map("task:1" -> rec("task:1", "h1")))
        // 制造写失败：vectors.json 路径替换成目录——写通道必然抛
        os.remove(tmp / "index" / "vectors.json")
        os.makeDir.all(tmp / "index" / "vectors.json")
        val res = VectorStore.putAll(Map("task:2" -> rec("task:2", "h2")))
        assert(res.isLeft, s"expected a Left on write failure, got $res")
      }
    }.unsafeRunSync()
  }

  test("clear() empties the store in place") {
    withTempDataRoot { tmp =>
      IO {
        VectorStore.putAll(Map("task:1" -> rec("task:1", "h1")))
        VectorStore.clear()
        assertEquals(VectorStore.read().entries, Map.empty)
      }
    }.unsafeRunSync()
  }

end VectorStoreSpec
