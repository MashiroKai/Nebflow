package nebflow.core.delegate

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * external-executors 批（#1225）验收 · DelegateRegistry.Record.cliSessionId。
 *
 * 中心性质：外部执行器的 CLI 会话锚点（claude session_id / codex thread_id /
 * zcode session_id）持久落盘、resume 续聊跨重启可达；**旧盘面文件（无此字段）
 * 照常解码**（缺省 None——向后兼容面，不迁移）。
 */
class DelegateRegistryCliSessionSpec extends CatsEffectSuite:

  override val munitIOTimeout = 60.seconds

  private def withTempDataRoot[A](f: os.Path => IO[A]): IO[A] =
    val original = PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "nb-delegate-cli-")
    PathUtil.setDataRoot(tmp)
    f(tmp).guarantee(IO(PathUtil.setDataRoot(original)) *> IO(os.remove.all(tmp)))

  private def rec(id: String, cli: Option[String] = None) =
    DelegateRegistry.Record(
      id = id,
      executor = "claude-code",
      cwd = "/tmp/ws",
      project = None,
      taskId = Some("7"),
      parentSessionId = "root-1",
      title = "external task",
      createdAt = 1L,
      cliSessionId = cli
    )

  test("cliSessionId round-trips through disk; first-write-wins update keeps prior value") {
    withTempDataRoot { tmp =>
      IO {
        DelegateRegistry.put(rec("external-claude-code-abc12345"))
        val hit = DelegateRegistry.find("external-claude-code-abc12345")
        assertEquals(hit.flatMap(_.cliSessionId), None)

        // 流面首帧回写。
        DelegateRegistry.put(hit.get.copy(cliSessionId = Some("sess-9f13")))
        assertEquals(DelegateRegistry.find("external-claude-code-abc12345").flatMap(_.cliSessionId), Some("sess-9f13"))

        // 迟到帧不覆盖（updateRecordCliSession 的 orElse 口径在 registry 层同形）。
        DelegateRegistry.find("external-claude-code-abc12345").foreach { r =>
          DelegateRegistry.put(r.copy(cliSessionId = r.cliSessionId.orElse(Some("sess-late"))))
        }
        assertEquals(DelegateRegistry.find("external-claude-code-abc12345").flatMap(_.cliSessionId), Some("sess-9f13"))
      }
    }.unsafeRunSync()
  }

  test("old on-disk records (no cliSessionId field) decode as None — no migration needed") {
    withTempDataRoot { tmp =>
      IO {
        val dir = PathUtil.dataRoot / "delegates"
        os.makeDir.all(dir)
        // unified-delegate 批（2026-10-03）的原字段形态：无 cliSessionId。
        os.write(
          dir / "delegate-kernel-old1234.json",
          """{"id":"delegate-kernel-old1234","executor":"nebflow","cwd":"/tmp/ws","project":null,"taskId":null,"parentSessionId":"root-1","title":"legacy","createdAt":5}"""
        )
        val hit = DelegateRegistry.find("delegate-kernel-old1234")
        assert(hit.isDefined)
        assertEquals(hit.get.cliSessionId, None)
        assertEquals(hit.get.executor, "nebflow")
      }
    }.unsafeRunSync()
  }
end DelegateRegistryCliSessionSpec
