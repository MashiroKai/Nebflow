package nebflow.core.delegate

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * unified-delegate 批（2026-10-03）验收：持久地址簿的落盘/读取/列表、id 安全面、
 * 续聊地址行形态。
 *
 * 中心性质：地址簿是 `delegate:<id>` 唯一的持久数据源——find 对缺失/坏形/越权 id
 * 一律 None（绝不抛），list 对坏文件跳过（绝不炸），跨重启（重新读盘）可见。
 */
class DelegateRegistrySpec extends CatsEffectSuite:

  override val munitIOTimeout = 60.seconds

  private def withTempDataRoot[A](f: os.Path => IO[A]): IO[A] =
    val original = PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "nb-delegate-reg-")
    PathUtil.setDataRoot(tmp)
    f(tmp).guarantee(IO(PathUtil.setDataRoot(original)) *> IO(os.remove.all(tmp)))

  private def rec(id: String, createdAt: Long = 1L) =
    DelegateRegistry.Record(
      id = id,
      executor = "nebflow",
      cwd = "/tmp/ws",
      project = None,
      taskId = Some("12"),
      parentSessionId = "root-1",
      title = "t",
      createdAt = createdAt
    )

  test("put/find round-trips through disk; a fresh read (simulated restart) still sees it") {
    withTempDataRoot { tmp =>
      IO {
        DelegateRegistry.put(rec("delegate-kernel-abc12345"))
        // 模拟重启：新读（内部无缓存——单点仍是盘）
        val hit = DelegateRegistry.find("delegate-kernel-abc12345")
        assert(hit.isDefined)
        assertEquals(hit.get.cwd, "/tmp/ws")
        assertEquals(hit.get.taskId, Some("12"))
      }
    }.unsafeRunSync()
  }

  test("find: missing / malformed / path-traversal ids are None, never throw") {
    withTempDataRoot { tmp =>
      IO {
        assertEquals(DelegateRegistry.find("no-such-id"), None)
        assertEquals(DelegateRegistry.find(""), None)
        assertEquals(DelegateRegistry.find("../escape"), None)
        assertEquals(DelegateRegistry.find("a/b"), None)
      }
    }.unsafeRunSync()
  }

  test("find: a corrupted record file degrades to None") {
    withTempDataRoot { tmp =>
      IO {
        os.makeDir.all(tmp / "delegates")
        os.write(tmp / "delegates" / "delegate-kernel-bad.json", "not-json{")
        assertEquals(DelegateRegistry.find("delegate-kernel-bad"), None)
        // list 跳过坏文件不炸
        DelegateRegistry.put(rec("delegate-kernel-good", createdAt = 5L))
        assertEquals(DelegateRegistry.list().map(_.id), List("delegate-kernel-good"))
      }
    }.unsafeRunSync()
  }

  test("list: newest first") {
    withTempDataRoot { tmp =>
      IO {
        DelegateRegistry.put(rec("d-old", createdAt = 10L))
        DelegateRegistry.put(rec("d-new", createdAt = 20L))
        assertEquals(DelegateRegistry.list().map(_.id), List("d-new", "d-old"))
      }
    }.unsafeRunSync()
  }

  test("continuationLine: delegate: form only — the retired kernel: face must not resurface") {
    val line = DelegateRegistry.continuationLine("delegate-kernel-abc12345")
    assert(line.contains("\"delegate:delegate-kernel-abc12345\""), line)
    assert(line.contains("Mail"), line)
    assert(!line.contains("kernel:delegate-kernel"), line)
  }
