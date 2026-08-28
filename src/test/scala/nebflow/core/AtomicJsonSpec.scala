package nebflow.core

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * AtomicJson 契约：tmp + rename(2)——写后内容完整、旧内容不被破坏、目录无
 * *.tmp.* 残留（成功路径）、IO 形态与 Sync 形态行为一致。
 */
class AtomicJsonSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 30.seconds

  test("write creates the file with exact content (nested dirs auto-created)") {
    for
      dir <- IO(os.temp.dir())
      f = dir / "deep" / "nested" / "state.json"
      _ <- AtomicJson.write(f, """{"a":1}""")
      content <- IO(os.read(f))
    yield assertEquals(content, """{"a":1}""")
  }

  test("write replaces existing content atomically — old or new, never partial") {
    for
      dir <- IO(os.temp.dir())
      f = dir / "state.json"
      _ <- AtomicJson.write(f, """{"version":1}""")
      _ <- AtomicJson.write(f, """{"version":2,"x":"yyyy"}""")
      content <- IO(os.read(f))
      residue <- IO(os.list(dir).filter(_.last.contains(".tmp.")))
    yield
      assertEquals(content, """{"version":2,"x":"yyyy"}""")
      assertEquals(residue.toList, List.empty[os.Path], s"no tmp residue expected: $residue")
  }

  test("writeSync behaves identically to write") {
    for
      dir <- IO(os.temp.dir())
      f = dir / "sync.json"
      _ <- IO(AtomicJson.writeSync(f, "[1,2,3]"))
      c1 <- IO(os.read(f))
      _ <- IO(AtomicJson.writeSync(f, "[4]"))
      c2 <- IO(os.read(f))
      residue <- IO(os.list(dir).filter(_.last.contains(".tmp.")))
    yield
      assertEquals(c1, "[1,2,3]")
      assertEquals(c2, "[4]")
      assertEquals(residue.toList, List.empty[os.Path])
  }

  test("failed move leaves the old file intact and cleans up the tmp file") {
    // 构造必失败场景：把目标路径的父目录换成文件 → tmp 写入必败。
    // 断言：异常抛出（不吞）、旧文件内容原样、目录无 tmp 残留。
    IO(os.temp.dir()).flatMap { dir =>
      val blocker = dir / "blocker"
      os.write.over(blocker, "i am a file")
      val f = blocker / "sub" / "state.json" // 父目录是文件 → tmp 写不进去 → 必败
      IO(AtomicJson.writeSync(f, "{}")).attempt.flatMap {
        case Left(_)  => IO(assert(true, "failure is raised, not swallowed"))
        case Right(_) => IO(fail("expected the write to fail"))
      } *> IO {
        assertEquals(os.read(blocker), "i am a file", "blocker file untouched")
      }
    }
  }

end AtomicJsonSpec
