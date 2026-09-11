package nebflow.neblink

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.{CredentialFileAcl, PathUtil}

import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}
import scala.collection.mutable.ArrayBuffer

/** T3 (2026-09-11, Q2 裁定) — device.json 凭据文件 ACL 收窄的验收钉。
  *
  * 缺陷形态：`Files.setPosixFilePermissions` 在 Windows 上是静默 no-op（provider
  * 无 POSIX 视图，抛出的异常被 `catch case _: Exception => ()` 吞掉）——device.json
  * 因此保留 profile 目录继承来的 DACL，当前用户以外的主体可读 live device token。
  *
  * 本 spec 钉三件事（Q7 口径：Windows 机制面不实跑，只钉分支选择）：
  *  R1/R3 分支选择：os.name 含 win ⇒ Windows ACL 分支；否则 POSIX 分支。用记录型
  *     Port 双倍断言「命中分支 + 另一分支零调用」——把 Windows 分支改回
  *     PosixFilePermissions（= 缺陷复现）⇒ R1b/R3 必红。
  *  R2 POSIX 实测：真实 save 后读回 0600（macOS 实测读数，打印留证）。
  *  R4 Windows 分支拿不到 ACL 视图时显式失败，禁止静默回退 POSIX。
  */
class DeviceCredentialAclSpec extends FunSuite:

  /** 记录型 Port：只记录哪条分支被调用，不碰真实文件系统。 */
  private final class RecordingPort extends CredentialFileAcl.Port:
    val calls = ArrayBuffer.empty[String]
    def ownerOnlyPosix(path: Path): Unit = calls += "posix"
    def ownerOnlyWindows(path: Path): Unit = calls += "windows"

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-device-acl-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  private def credPath: Path = tmpDir.resolve("neblink").resolve("device.json")
  private def cred =
    DeviceCredential("https://neblink.example", "net-1", "dev-1", "tok-secret")

  test("T3-R1a os.name 含 win ⇒ 只走 Windows ACL 分支（POSIX 分支零调用）") {
    val port = new RecordingPort
    CredentialFileAcl.restrict(credPath, "Windows 11", port)
    assertEquals(port.calls.toList, List("windows"))
  }

  test("T3-R1b os.name 非 win ⇒ 只走 POSIX 分支（Windows 分支零调用）") {
    val port = new RecordingPort
    CredentialFileAcl.restrict(credPath, "Mac OS X", port)
    assertEquals(port.calls.toList, List("posix"))
  }

  test("T3-R1c 分支判定表：win 大小写不敏感，mac/linux/空值归 POSIX") {
    assert(CredentialFileAcl.isWindows("Windows 11"))
    assert(CredentialFileAcl.isWindows("windows server 2019"))
    assert(!CredentialFileAcl.isWindows("Mac OS X"))
    assert(!CredentialFileAcl.isWindows("Linux"))
    assert(!CredentialFileAcl.isWindows(""))
  }

  test("T3-R3 端到端：Windows 口径 save 只碰 Windows 分支，且写入/读回不受影响") {
    val port = new RecordingPort
    DeviceCredential.save(cred, port, "Windows 11").unsafeRunSync()
    // 缺陷复现点：save 若直接调 Files.setPosixFilePermissions，这里会红
    // （calls 为空 = 分支未被走；calls 含 posix = 走了静默 no-op 分支）。
    assertEquals(port.calls.toList, List("windows"))
    assertEquals(DeviceCredential.load.unsafeRunSync().map(_.deviceToken), Some("tok-secret"))
  }

  test("T3-R2 POSIX 实测：save 后 device.json 落在 0600（macOS 读数留证）") {
    assume(
      !CredentialFileAcl.isWindows(CredentialFileAcl.currentOsName),
      "POSIX-only readback assertion"
    )
    DeviceCredential.save(cred, CredentialFileAcl.systemPort, CredentialFileAcl.currentOsName)
      .unsafeRunSync()
    val mode = PosixFilePermissions.toString(Files.getPosixFilePermissions(credPath))
    println(s"[T3-R2] posix readback: device.json mode = $mode (os=${CredentialFileAcl.currentOsName})")
    assertEquals(mode, "rw-------")
  }

  test("T3-R4 Windows 分支拿不到 ACL 视图 ⇒ 显式失败，禁止静默回退 POSIX") {
    assume(
      !CredentialFileAcl.isWindows(CredentialFileAcl.currentOsName),
      "non-Windows host: the ACL view is absent, which is what this test probes"
    )
    Files.createDirectories(credPath.getParent)
    Files.writeString(credPath, "{}")
    val modeBefore = PosixFilePermissions.toString(Files.getPosixFilePermissions(credPath))
    val thrown = intercept[Exception] {
      CredentialFileAcl.restrict(credPath, "Windows 11", CredentialFileAcl.systemPort)
    }
    // 关键：不是静默 return（那就是缺陷原形），而是抛出可诊断的失败。
    val msg = Option(thrown.getMessage).getOrElse("").toLowerCase
    assert(
      msg.contains("acl"),
      s"expected a diagnosable ACL failure, got: ${thrown.getClass.getName}: ${thrown.getMessage}"
    )
    // 失败路径不允许留下任何「假装收窄成功」的痕迹（POSIX 模式原封不动）。
    assertEquals(
      PosixFilePermissions.toString(Files.getPosixFilePermissions(credPath)),
      modeBefore,
      "POSIX mode must be untouched when the Windows branch bails out"
    )
  }

  test("T3-R5 ACL 收窄失败不阻断写入（凭证已落盘），但也绝不静默") {
    val failing = new CredentialFileAcl.Port:
      def ownerOnlyPosix(path: Path): Unit = throw new java.io.IOException("posix nope")
      def ownerOnlyWindows(path: Path): Unit = throw new java.io.IOException("acl nope")
    // 不抛（save 失败时凭证已在盘上，中断写入损失更大）
    DeviceCredential.save(cred, failing, "Windows 11").unsafeRunSync()
    // 文件确实已落盘且可解码 —— 告警路径覆盖的是「盘上有凭证但 ACL 未收窄」这一状态
    assertEquals(DeviceCredential.load.unsafeRunSync().map(_.deviceToken), Some("tok-secret"))
  }
