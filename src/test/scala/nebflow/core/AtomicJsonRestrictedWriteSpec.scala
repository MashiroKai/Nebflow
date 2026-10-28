package nebflow.core

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import scala.jdk.CollectionConverters.*

/**
 * keyaudit-quickfix ① 防回归：受限写形态（[[AtomicJson.writeSyncRestricted]] /
 * [[AtomicJson.writeRestricted]]）。
 *
 * 缺陷形态（改前 nebflow.json / stt-config.json 走 [[AtomicJson.writeSync]]）：
 * tmp 文件继承进程 umask（实测 0644），rename(2) 把该模式原样带 onto 目标 ——
 * 明文 provider apiKey 落成 group/other **可读**。
 *
 * 本 spec 钉的不变量：
 *   · 新文件从第一个可观测字节起就是 `rw-------`（模式来自创建属性 + 移动前
 *     [[CredentialFileAcl.restrict]] 重申——两者共享同一个模式常量，不造第二份 ACL）；
 *   · 既有宽模式旧文件（0644 遗留安装）在**下一次保存即收敛**为 0600——无迁移
 *     脚本，这正是任务口径「写入时顺手收权」；
 *   · 失败语义与 [[AtomicJson.writeSync]] 同族：旧文件内容与模式原样、无 tmp 残留。
 *
 * Windows 分支（DACL 阶梯）不在本 spec 的读回断言范围（POSIX 位无义），由
 * CredentialFileAcl 自身的 DeviceCredentialAclSpec 在真实 Windows 宿主钉死。
 */
class AtomicJsonRestrictedWriteSpec extends CatsEffectSuite:

  private def posixOnly(): Unit =
    assume(
      !CredentialFileAcl.isWindows(CredentialFileAcl.currentOsName),
      "POSIX-only mode readback assertion"
    )

  private def perms(p: os.Path): Set[PosixFilePermission] =
    Files.getPosixFilePermissions(p.toNIO).asScala.toSet

  private val ownerRW = Set(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

  test("writeSyncRestricted lands the file rw------- from its first observable state") {
    posixOnly()
    for
      dir <- IO(os.temp.dir())
      f = dir / "nebflow.json"
      _ <- IO(AtomicJson.writeSyncRestricted(f, """{"llm":{"providers":{}}}"""))
      mode <- IO(perms(f))
      content <- IO(os.read(f))
      residue <- IO(os.list(dir).filter(_.last.contains(".tmp.")))
    yield
      assertEquals(mode, ownerRW, "file must be owner-only (0600), not the umask default 0644")
      assertEquals(content, """{"llm":{"providers":{}}}""")
      assertEquals(residue.toList, List.empty[os.Path], s"no tmp residue: $residue")
  }

  test("restricted write converges a legacy wide-mode (0644) file on the very next save") {
    posixOnly()
    for
      dir <- IO(os.temp.dir())
      f = dir / "nebflow.json"
      // 模拟遗留安装：明文文件以 0644 在盘上
      _ <- IO { os.write.over(f, """{"apiKey":"sk-old"}"""); os.perms.set(f, "rw-r--r--") }
      before <- IO(perms(f))
      _ <- IO(AtomicJson.writeSyncRestricted(f, """{"apiKey":"sk-new"}"""))
      after <- IO(perms(f))
      content <- IO(os.read(f))
    yield
      assertEquals(
        before,
        ownerRW + PosixFilePermission.GROUP_READ + PosixFilePermission.OTHERS_READ,
        "fixture is 0644"
      )
      assertEquals(after, ownerRW, "one save must converge the legacy file to 0600")
      assertEquals(content, """{"apiKey":"sk-new"}""")
    end for
  }

  test("already-0600 target stays 0600 and the IO form behaves like the Sync form") {
    posixOnly()
    for
      dir <- IO(os.temp.dir())
      f1 = dir / "a.json"
      f2 = dir / "b.json"
      _ <- IO(AtomicJson.writeSyncRestricted(f1, """{"v":1}"""))
      _ <- IO(AtomicJson.writeSyncRestricted(f1, """{"v":2}"""))
      m1 <- IO(perms(f1))
      c1 <- IO(os.read(f1))
      _ <- AtomicJson.writeRestricted(f2, """{"w":1}""")
      m2 <- IO(perms(f2))
      c2 <- IO(os.read(f2))
    yield
      assertEquals(m1, ownerRW, "re-save of an already-0600 file stays 0600 (idempotent)")
      assertEquals(c1, """{"v":2}""")
      assertEquals(m2, ownerRW, "IO form == Sync form on mode")
      assertEquals(c2, """{"w":1}""")
    end for
  }

  test("failed write leaves the old file's content AND mode untouched (fail-same-as-writeSync)") {
    posixOnly()
    IO(os.temp.dir()).flatMap { dir =>
      val blocker = dir / "blocker"
      os.write.over(blocker, "i am a file")
      val f = blocker / "sub" / "state.json" // 父目录是文件 → tmp 建不进去 → 必败
      IO(AtomicJson.writeSyncRestricted(f, "{}")).attempt.flatMap {
        case Left(_) => IO(assert(true, "failure is raised, not swallowed"))
        case Right(_) => IO(fail("expected the write to fail"))
      } *> IO {
        assertEquals(os.read(blocker), "i am a file", "blocker file untouched")
      }
    }
  }

  test("a tmp-creation failure inside a live directory leaves the existing target byte-identical") {
    posixOnly()
    IO {
      val dir = os.temp.dir()
      val f = dir / "nebflow.json"
      os.write.over(f, """{"apiKey":"sk-keep"}""")
      os.perms.set(f, "rw-------")
      // 目录置为不可写 → tmp 的 createFile 必败（AccessDenied），走 catch 清理
      os.perms.set(dir, "r-xr-xr-x")
      val attemptCls =
        try
          AtomicJson.writeSyncRestricted(f, """{"apiKey":"sk-new"}"""); None
        catch
          case e: Exception => Some(e.getClass.getSimpleName)
      // 恢复目录可写（清场），再断言
      os.perms.set(dir, "rwxr-xr-x")
      val content = os.read(f)
      val mode = perms(f)
      val residue = os.list(dir).filter(_.last.contains(".tmp.")).toList
      assertEquals(attemptCls.isDefined, true, "the failure must surface, not be swallowed")
      assertEquals(content, """{"apiKey":"sk-keep"}""", "old plaintext untouched")
      assertEquals(mode, ownerRW, "old mode untouched")
      assertEquals(residue, List.empty[os.Path], "no tmp residue after the failed attempt")
    }
  }

end AtomicJsonRestrictedWriteSpec
