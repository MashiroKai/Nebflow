package nebflow.social

import munit.FunSuite
import nebflow.core.CredentialFileAcl
import nebflow.social.SocialChannels.Failure

import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}

/**
 * keyaudit-quickfix ② 防回归：`SocialChannels.writeSecret` 在写任何明文之前对
 * `secrets/` 目录本身做一次 0700 收窄。
 *
 * 缺陷形态（改前）：`writeSecret` 只 `Files.createDirectories`（默认 umask ⇒ 实测
 * 0755）。文件位的 `rw-------` 挡得住「知道路径的读者」，挡不住「可枚举目录里
 * 有哪些凭据」的本地主体——即 SecretsStore.writeThrough / DaemonPanelStore 已有
 * 的「目录兜底」缺口在 SocialChannels 侧的同款。
 *
 * 钉的不变量：
 *   · 宽模式（0755）secrets/ 在下一次存密钥时收敛为 `rwx------`（幂等收窄，
 *     复用 [[CredentialFileAcl.restrictDirectory]]，无第二份 ACL）；
 *   · 收窄成功后，密钥文件照旧 `rw-------`、明文正确；
 *   · 目录已 0700 时保存幂等（再存一次仍 0700，不抛错）；
 *   · 失败 fail-closed：目录收窄抛异常 ⇒ 保存被拒（`Failure.Io` 路径），且不
 *     留半态——此时目标文件尚未创建（生产收窄在 createDirectories 之后、
 *     exists/create 分支之前）。
 *
 * 🔴 变异安全：只读写本 spec 自己的临时 home，不触碰真实 `~/.nebflow`。
 */
class SocialChannelsSecretDirSpec extends FunSuite:

  private def body(plain: String): io.circe.Json =
    io.circe.parser.parse(s"""{"enabled":true,"fields":{"bot_token":"$plain"}}""").toOption.get

  private def tmpRoot(): os.Path = os.temp.dir(prefix = "nb-social-dir-")

  private def secretsDir(root: os.Path): Path = (root / "secrets").toNIO

  private def target(root: os.Path): Path =
    (root / "secrets" / "social-telegram-bot-token").toNIO

  private def modeOf(p: Path): String =
    PosixFilePermissions.toString(Files.getPosixFilePermissions(p))

  private def posixOnly(): Unit =
    assume(
      !CredentialFileAcl.isWindows(CredentialFileAcl.currentOsName),
      "POSIX-only mode readback assertion"
    )

  test("QF2-R1 a wide-mode (0755) secrets/ converges to rwx------ on the next secret save") {
    posixOnly()
    val root = tmpRoot()
    Files.createDirectories(secretsDir(root))
    os.perms.set(root / "secrets", "rwxr-xr-x") // 缺陷形态：0755
    assertEquals(modeOf(secretsDir(root)), "rwxr-xr-x", "fixture is 0755")

    val result = SocialChannels.save(root, "telegram", body("tok-dir-1"))
    assertEquals(result.isRight, true, s"save must succeed, got $result")
    assertEquals(modeOf(secretsDir(root)), "rwx------", "the directory must be narrowed to 0700 by the save")
    assertEquals(modeOf(target(root)), "rw-------", "the credential file itself stays owner-only")
    assertEquals(Files.readString(target(root)), "tok-dir-1")
  }

  test("QF2-R2 create-on-the-fly namespace: save builds secrets/ directly owner-only") {
    posixOnly()
    val root = tmpRoot() // secrets/ 不存在 —— createDirectories + 立即收窄
    val result = SocialChannels.save(root, "telegram", body("tok-dir-2"))
    assertEquals(result.isRight, true, s"save must succeed, got $result")
    assertEquals(modeOf(secretsDir(root)), "rwx------")
    assertEquals(modeOf(target(root)), "rw-------")
  }

  test("QF2-R3 idempotent: an already-0700 secrets/ survives a second save unchanged") {
    posixOnly()
    val root = tmpRoot()
    val first = SocialChannels.save(root, "telegram", body("tok-dir-3a"))
    assertEquals(first.isRight, true, s"first save must succeed, got $first")
    val second = SocialChannels.save(root, "telegram", body("tok-dir-3b"))
    assertEquals(second.isRight, true, s"second save must succeed, got $second")
    assertEquals(modeOf(secretsDir(root)), "rwx------", "re-save must not widen or fail")
    assertEquals(modeOf(target(root)), "rw-------")
    assertEquals(Files.readString(target(root)), "tok-dir-3b")
  }

  test("QF2-R4 ordering: the directory is narrowed even when the later file step fails (fail-closed)") {
    posixOnly()
    val root = tmpRoot()
    Files.createDirectories(secretsDir(root))
    os.perms.set(root / "secrets", "rwxr-xr-x")
    // save 的 restrict 注入面只接管文件步收窄；让它必败 ⇒ 保存被拒。生产序里
    // 目录收窄位于文件步之前，因此被拒的保存也必须已经把目录收敛为 0700
    // （次序即不变量：任何明文可能存在之前，命名空间已 owner-only）。
    val failingRestrict: Path => Unit = _ => throw new java.io.IOException("injected: narrowing refused")
    val result = SocialChannels.save(root, "telegram", body("tok-dir-4"), failingRestrict)
    result match
      case Left(_: Failure.SecretMode) => () // 既有错误码族（fail-closed）
      case other => fail(s"expected a Failure (fail-closed), got $other")
    assertEquals(modeOf(secretsDir(root)), "rwx------", "dir narrowing precedes the file step and is not rolled back")
    assert(!Files.exists(target(root)), "no plaintext residue after a refused save")
  }

end SocialChannelsSecretDirSpec
