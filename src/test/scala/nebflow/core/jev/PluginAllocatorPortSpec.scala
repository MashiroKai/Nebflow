package nebflow.core.jev

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * PluginAllocator 端口判读（jev-plugins 批 #1226；M2 注册器语义 = DelegateJudgePort
 * 同款）：未注册 = `Off`（分配动作未发生，eventless——绝不阻断调用方）；注册面内部
 * 缺陷 = `Failure` fail-open；install/clear 幂等。
 */
class PluginAllocatorPortSpec extends CatsEffectSuite:

  override val munitIOTimeout = 60.seconds

  private val catalog = List(CatalogEntry("p1", "fixture package"))

  // 🔴 夹具修复（2026-10-14 redfix 批，报告 #13）：「no face installed」前提原本
  // 只靠 afterEach 清场——但 face 是**进程级单例**，同 JVM 内任何先跑的套件只要
  // 构造过 `SharedResources`（生产 boot 装配行会 install 生产分配机 face）且未清，
  // 本套件首例看到的就是**别人的 face**（其执行体在夹具语境下 NPE ⇒ fail-open
  // `FellBack(Failure, allocator defect: NullPointerException)`——22 套件合跑实测）。
  // solo 跑无先行构造 ⇒ green——紅仅合跑形态出现。修法 = beforeEach 也清（前提
  // 「未注册」在任意 JVM 历史下成立），断言零改动；afterEach 清场保持（不向
  // 后续套件泄漏）。
  override def beforeEach(context: munit.BeforeEach): Unit = PluginAllocator.clear()

  override def afterEach(context: munit.AfterEach): Unit = PluginAllocator.clear()

  test("no face installed → Off fallback (allocation absent, dispatch never blocked)") {
    withTempDataRoot { _ =>
      PluginAllocator.allocatePlugins("s", "t", catalog).map { r =>
        assertEquals(r.via, None)
        r.allocation match
          case JevAllocation.FellBack(JevFallbackKind.Off, reason) =>
            assert(reason.contains("not installed"), reason)
          case other => fail(s"expected Off, got: $other")
      }
    }
  }

  test("installed face defect → Failure fail-open (the port belt-and-braces never rethrows)") {
    withTempDataRoot { _ =>
      PluginAllocator.install(
        new PluginAllocator.Face:
          def allocatePlugins(subject: String, taskText: String, catalog: List[CatalogEntry]) =
            IO.raiseError(new RuntimeException("boom"))
      )
      PluginAllocator.allocatePlugins("s", "t", catalog).map { r =>
        r.allocation match
          case JevAllocation.FellBack(JevFallbackKind.Failure, reason) =>
            assert(reason.contains("boom") || reason.contains("RuntimeException"), reason)
          case other => fail(s"expected fail-open, got: $other")
      }
    }
  }

  test("installed face result passes through with its via tag") {
    withTempDataRoot { _ =>
      PluginAllocator.install(
        new PluginAllocator.Face:
          def allocatePlugins(subject: String, taskText: String, catalog: List[CatalogEntry]) =
            IO.pure(
              PluginAllocator.PluginAllocationResult(
                JevAllocation.Allocated(List("p1")),
                Some(PluginAllocator.AllocationVia.Jev)
              )
            )
      )
      PluginAllocator.allocatePlugins("s", "t", catalog).map { r =>
        assertEquals(r.via, Some(PluginAllocator.AllocationVia.Jev))
        assertEquals(r.allocation, JevAllocation.Allocated(List("p1")))
      }
    }
  }

  private def withTempDataRoot[A](f: os.Path => IO[A]): IO[A] =
    val original = PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "nb-plugin-port-")
    PathUtil.setDataRoot(tmp)
    f(tmp).guarantee(IO(PathUtil.setDataRoot(original)) *> IO(os.remove.all(tmp)))

end PluginAllocatorPortSpec
