package nebflow.neblink

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.{CredentialFileAcl, PathUtil}

import java.nio.file.attribute.{AclEntryPermission, PosixFilePermissions}
import java.nio.file.{Files, Path}
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

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
    // kaiauth 修法批 ②（2026-09-16）：`deviceToken` 已**停写**（写侧单源化 ⇒
    // `config.json` 是唯一权威来源，见 `DeviceCredential` 的 DEPRECATED 注记）
    // ⇒ 「读回不受影响」改钉**身份面**完整读回 + 盘上**不含**那份被停写的副本。
    // 旧断言（`load.map(_.deviceToken) == Some("tok-secret")`）钉的是本批要移除的
    // 写面本身，故迁移；覆盖面不缩反扩（多了「盘上无该键」这一机械判据）。
    val back = DeviceCredential.load.unsafeRunSync()
    assertEquals(
      back.map(c => (c.serverUrl, c.networkId, c.deviceId)),
      Some(("https://neblink.example", "net-1", "dev-1"))
    )
    assert(
      !Files.readString(credPath).contains("\"deviceToken\""),
      "the retired deviceToken copy must not be written into neblink/device.json"
    )
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
    // （kaiauth 修法批 ②：读回断言改钉身份面 + 盘上不含被停写的 deviceToken 副本，
    // 理由同 T3-R3 处注释）。
    assertEquals(DeviceCredential.load.unsafeRunSync().map(_.deviceId), Some("dev-1"))
    assert(
      !Files.readString(credPath).contains("\"deviceToken\""),
      "the retired deviceToken copy must not be written into neblink/device.json"
    )
  }

  // T3-R6（适配 kaiauth② 停写语义，2026-09-17）—— 原探针钉「收窄后可读回 deviceToken」，
  // kaiauth② 该字段已**停写** ⇒ 探针改钉**身份面**（serverUrl/networkId/deviceId），并补一条
  // 「盘上不含该键」的机械判据；被验证的行为本身不变：**属主对已收窄文件仍可读回并重写**。
  //
  // 缺陷形态（missing-EA，2026-09-16 实测）：Windows 分支的 owner-only ACE 若只带数据/属性位、
  // 缺 READ_NAMED_ATTRS/WRITE_NAMED_ATTRS（乃至 READ_ACL/WRITE_ACL），则 `java.nio.file` 的
  // GENERIC_READ/GENERIC_WRITE 打开（连带请求 FILE_READ_EA/FILE_WRITE_EA）被 Windows 直接拒
  // —— 属主被自己刚写的 ACE 锁在门外：device.json 既读不回也重写不了（AccessDeniedException）。
  // 本钉在真实 Windows 主机上实跑 systemPort（Q7「Windows 机制面不实跑」正是本钉要合的残差），
  // 故 host 非 Windows 时 assume 跳过。
  test("T3-R6 Windows 实测：属主对已收窄 device.json 仍可读回并重写（EA 位回归钉）") {
    assume(
      CredentialFileAcl.isWindows(CredentialFileAcl.currentOsName),
      "Windows-only probe: the ACL branch (and the missing-EA defect) is Windows-specific"
    )
    // 第一跳：真 systemPort 收窄（Windows 分支 = 单条非继承 owner-only ACE）。
    DeviceCredential.save(cred, CredentialFileAcl.systemPort, CredentialFileAcl.currentOsName)
      .unsafeRunSync()
    // 🔴 核心验证点 1：收窄后属主仍可读回 —— 缺 READ_NAMED_ATTRS 时此处 AccessDeniedException。
    val back1 = DeviceCredential.load.unsafeRunSync()
    assertEquals(
      back1.map(c => (c.serverUrl, c.networkId, c.deviceId)),
      Some(("https://neblink.example", "net-1", "dev-1"))
    )
    // 第二跳：rotation 式写回 —— 轮换后的 credential 覆盖同一已收窄文件。
    // 🔴 核心验证点 2：属主仍可重写 —— 缺 WRITE_NAMED_ATTRS/WRITE_ACL 时此处抛。
    val rotated = cred.copy(deviceToken = "tok-rotated-2")
    DeviceCredential.save(rotated, CredentialFileAcl.systemPort, CredentialFileAcl.currentOsName)
      .unsafeRunSync()
    val back2 = DeviceCredential.load.unsafeRunSync()
    // 第二次断言与「旋转后的 token」无关：token 已停写、不在盘上，身份面才是可比对的
    // （刻意**不**用 `load.map(_.deviceToken) == Some(...)` 这一已停写字段的形态）。
    assertEquals(
      back2.map(c => (c.serverUrl, c.networkId, c.deviceId)),
      Some(("https://neblink.example", "net-1", "dev-1"))
    )
    assert(
      !Files.readString(credPath).contains("\"deviceToken\""),
      "the retired deviceToken copy must not be written into neblink/device.json"
    )
  }

  // ═════════════ T4（credaacl 批，2026-09-19）：自锁残留缺陷的三段梯判据 ═════════════
  //
  // 缺陷形态（本批对象，上游 kaifla-fix 实机取证）：`setAcl` 之后**没有任何可用性
  // 自检** —— owner-only ACE 一旦少任何一位，落盘即自锁（属主自己的 ACE 把 java.nio
  // 的 GENERIC_READ/GENERIC_WRITE 打开请求拒掉）且**无自愈路径**；KAI 之所以必须外部
  // `icacls` 才能救，正是因为没有「自检 + 重建 + 显式报错」这条梯。
  //   · 自锁形（p19 BEFORE）：SDDL `D:P(A;;0x100187;;;<owner>)` / icacls `(S,RD,WD,AD,RA,WA)`
  //   · 修复形（p19 AFTER / p20 收敛）：`A;;0x16019f` / `(S,RD,WD,AD,RA,WA,REA,WEA,RC,WDAC)`
  //
  // 🔴 C-3 申报：`.nebflow/evidence/20260919_kailogin-r2/` 现读**不存在**（S-1 补充件
  // 亦预期此结果）⇒ 红钉素材 = **值级锚**（上面两个掩码）+ 本仓在册索引
  // `.nebflow/evidence/20260919_kaifla-fix/`（p16/p19/p20/p21）；本 spec 不引用任何
  // 不存在路径。
  //
  // T4 判据全部用**注入式替身**驱动 ⇒ 宿主无 Windows、无 ACL 视图也要可跑（真机位只在
  // T3-R6；🔴 不把「未实跑」写成「已验证」）。

  /** 自锁形态的实机 mask 读数（KAI 2026-09-18 现取，kaifla-fix p19）。 */
  private val LockedMask = 0x100187L

  /** 修复形态的实机 mask 读数（icacls 最小修复后；与上游 `d113ebbca` 逐位相同）。 */
  private val RepairedMask = 0x16019fL

  /** T4 替身：探针 / 结构读数 / 重建三腿全部可编，且**不碰真实文件系统**。 */
  private final class FakeWindowsAcl extends CredentialFileAcl.WindowsAcl:
    val probeCalls = new java.util.concurrent.atomic.AtomicInteger(0)
    val repairCalls = new java.util.concurrent.atomic.AtomicInteger(0)
    val readCalls = new java.util.concurrent.atomic.AtomicInteger(0)
    /** 前 N 次探针（真实打开）失败；0 = 永不失败。 */
    var probeFails: Int = 0
    /** 结构读数（重建之前）。 */
    var reading: Option[CredentialFileAcl.AclMask] = Some(CredentialFileAcl.AclMask.of(RepairedMask))
    /** 结构读数（重建之后）—— 默认已是正确形态。 */
    var readingAfterRepair: Option[CredentialFileAcl.AclMask] =
      Some(CredentialFileAcl.AclMask.of(RepairedMask))
    var repairOutcome: CredentialFileAcl.RepairOutcome = CredentialFileAcl.RepairOutcome.Repaired
    /** `Some(msg)` ⇒ 重建本身抛（模拟自锁态下连重建也做不动）。 */
    var repairThrows: Option[String] = None

    def proveUsable(path: Path): Unit =
      if probeCalls.incrementAndGet() <= probeFails then
        throw new java.nio.file.AccessDeniedException(path.toString)

    def readAcl(path: Path): Option[CredentialFileAcl.AclMask] =
      readCalls.incrementAndGet()
      if repairCalls.get() > 0 then readingAfterRepair else reading

    def repair(path: Path): CredentialFileAcl.RepairOutcome =
      repairCalls.incrementAndGet()
      repairThrows.foreach(m => throw new java.io.IOException(m))
      repairOutcome

  private def ladderOf(acl: CredentialFileAcl.WindowsAcl): CredentialFileAcl.WindowsLadder =
    new CredentialFileAcl.WindowsLadder(acl, CredentialFileAcl.expectedBits)

  private def hex(mask: Long): String = java.lang.Long.toHexString(mask)

  // ── 内存 ACL 后端（C-1/C-2/C-4「再保存幂等」判据的核心夹具）──────────────
  //
  // DACL 掩码是唯一状态：写侧装哪个掩码 ⇒ 探针能不能打开 —— 与实机缺陷**同构**
  // （0x100187 缺位 ⇒ 打开被拒；0x16019f ⇒ 打开通过）。

  /** `installMaskAt(n)` 决定第 n 次安装写入哪个掩码；默认全写正确形态。传
    * `n => if n == 2 then LockedMask else RepairedMask` 即「首写修好、再写抹回」。 */
  private final class MemoryAclBackend(installMaskAt: Int => Long):
    private val expectedMask = CredentialFileAcl.maskOf(CredentialFileAcl.expectedBits)
    var mask: Long = RepairedMask
    val installs = ArrayBuffer.empty[Long]
    val probes = ArrayBuffer.empty[Long]
    var repairs = 0
    var present = true

    def usable: Boolean = (expectedMask & mask) == expectedMask

    def install(): Unit =
      val next = installMaskAt(installs.size + 1)
      installs += next
      mask = next

    def render: String = CredentialFileAcl.AclMask.of(mask).render

  /** 写侧端口：只走「安装 ACL」这一腿（= 生产 systemPort 的 Windows 分支形态）。 */
  private final class BackendPort(backend: MemoryAclBackend) extends CredentialFileAcl.Port:
    def ownerOnlyPosix(path: Path): Unit = ()
    def ownerOnlyWindows(path: Path): Unit = backend.install()

  /** 探针/维修端口：可用性由后端掩码算出 —— 缺位即真打开被拒（缺陷形态同构）。 */
  private final class BackendWindowsAcl(backend: MemoryAclBackend)
      extends CredentialFileAcl.WindowsAcl:
    def proveUsable(path: Path): Unit =
      backend.probes += backend.mask
      if !backend.usable then throw new java.nio.file.AccessDeniedException(path.toString)

    def readAcl(path: Path): Option[CredentialFileAcl.AclMask] =
      if backend.present then Some(CredentialFileAcl.AclMask.of(backend.mask)) else None

    def repair(path: Path): CredentialFileAcl.RepairOutcome =
      backend.repairs += 1
      backend.mask = RepairedMask
      CredentialFileAcl.RepairOutcome.Repaired

  test("T4-R1 期望位集合逐位钉 + mask 读数：== 实机修复形 0x16019f（去四位 == 实机自锁形 0x100187）") {
    val perms = CredentialFileAcl.ownerPermissions.asScala.toSet
    val reading = CredentialFileAcl.AclMask.of(CredentialFileAcl.maskOf(perms))
    println(s"[T4-R1] expected bits=[${CredentialFileAcl.AclMask.names(perms)}] size=${perms.size}")
    println(s"[T4-R1] expected mask reading = ${reading.render}")
    // ① 逐位：四个 LOAD-BEARING 位必须在（缺任一 ⇒ 属主被自己刚写的 ACE 锁在门外）
    val loadBearing = List(
      AclEntryPermission.READ_NAMED_ATTRS, // FILE_READ_EA
      AclEntryPermission.WRITE_NAMED_ATTRS, // FILE_WRITE_EA
      AclEntryPermission.READ_ACL, // READ_CONTROL
      AclEntryPermission.WRITE_ACL // WRITE_DAC
    )
    loadBearing.foreach(p => assert(perms.contains(p), s"期望位集合缺 $p —— 读数 ${reading.render}"))
    // ② 读数与实机逐位相同（两个字面量互钉：代码与机器证据不能各自漂移）
    assertEquals(perms.size, 10, s"读数 ${reading.render}")
    assertEquals(CredentialFileAcl.maskOf(perms), RepairedMask, s"读数 ${reading.render}")
    // ③ 去掉这四位 = 实机自锁形 0x100187（变异 B① 打的就是这两个字面量）
    val defectForm = perms -- loadBearing.toSet
    assertEquals(
      CredentialFileAcl.maskOf(defectForm),
      LockedMask,
      s"缺陷形读数 = 0x${hex(CredentialFileAcl.maskOf(defectForm))}"
    )
  }

  test("T4-R1b 自锁形读数 0x100187 ⇒ missing 逐位 == 实机缺的四位（DELETE 只报不判）") {
    val locked = CredentialFileAcl.AclMask.of(LockedMask)
    println(s"[T4-R1b] locked-form reading = ${locked.render}")
    assertEquals(
      locked.missing(CredentialFileAcl.expectedBits).map(_.name),
      Set("READ_ACL", "READ_NAMED_ATTRS", "WRITE_ACL", "WRITE_NAMED_ATTRS")
    )
    // DELETE 刻意不在期望集合内（与实机修复形逐位相同）⇒ 只出读数、不作判据
    assert(locked.render.contains("delete=ABSENT"), s"读数缺 DELETE 观测位: ${locked.render}")
    assert(
      !CredentialFileAcl.expectedBits.map(_.name).contains("DELETE"),
      "DELETE 不得进入期望位集合（会与实机修复形 0x16019f 漂移）"
    )
  }

  test("T4-R2 自检成功 ⇒ 重建梯零调用（restrict 正常返回）") {
    val port = new RecordingPort
    val acl = new FakeWindowsAcl
    CredentialFileAcl.restrict(credPath, "Windows 11", port, ladderOf(acl))
    assertEquals(port.calls.toList, List("windows"))
    assertEquals(acl.probeCalls.get(), 1)
    assertEquals(acl.repairCalls.get(), 0, "自检通过时禁止触发重建")
    println(s"[T4-R2] probes=${acl.probeCalls.get()} repairs=${acl.repairCalls.get()}")
  }

  test("T4-R3 自检失败 ⇒ 触发重建一次；重建后自检通过 ⇒ restrict 正常返回") {
    val acl = new FakeWindowsAcl
    acl.probeFails = 1
    acl.reading = Some(CredentialFileAcl.AclMask.of(LockedMask, entries = 1))
    // 不抛即通过（重建后自检必须再跑一次并放行）
    CredentialFileAcl.restrict(credPath, "Windows 11", new RecordingPort, ladderOf(acl))
    assertEquals(acl.repairCalls.get(), 1, "自检失败必须触发重建")
    assertEquals(acl.probeCalls.get(), 2, "重建后必须再自检一次（不能假设重建成功）")
    println(s"[T4-R3] probes=${acl.probeCalls.get()} repairs=${acl.repairCalls.get()}")
  }

  test("T4-R4 重建仍败 ⇒ 显式异常含 file:line 锚点 + mask 读数（0x100187 + 缺位集合）") {
    val acl = new FakeWindowsAcl
    acl.probeFails = Int.MaxValue
    acl.reading = Some(CredentialFileAcl.AclMask.of(LockedMask, entries = 1))
    acl.readingAfterRepair = Some(CredentialFileAcl.AclMask.of(LockedMask, entries = 1))
    acl.repairOutcome = CredentialFileAcl.RepairOutcome.Salvaged(
      CredentialFileAcl.Salvage(
        s"${credPath}.aclrebuild-20260919-000000-000",
        "AccessDeniedException: swap refused (target lacks DELETE)"
      )
    )
    val e = intercept[CredentialFileAcl.AclSelfLockedException] {
      CredentialFileAcl.restrict(credPath, "Windows 11", new RecordingPort, ladderOf(acl))
    }
    val msg = e.getMessage
    println(s"[T4-R4] sample = $msg")
    assert(msg.matches("(?s).*CredentialFileAcl\\.scala:\\d+.*"), s"缺 file:line 锚点: $msg")
    assert(e.site.matches("CredentialFileAcl\\.scala:\\d+"), s"site 读数不合形: ${e.site}")
    assert(msg.contains("mask=0x100187"), s"缺 mask 读数: $msg")
    assert(
      msg.contains("missing=[READ_ACL,READ_NAMED_ATTRS,WRITE_ACL,WRITE_NAMED_ATTRS]"),
      s"缺与期望位的差集: $msg"
    )
    assert(msg.contains("bits=["), s"缺 ACE 位集合: $msg")
    assert(msg.contains("salvaged(copy="), s"缺降级读数（重建形态 + 不可行原因）: $msg")
    assert(msg.contains(credPath.toString), s"缺路径读数: $msg")
    // 凭据协议（红线）：异常文本零凭据内容
    assert(!msg.contains("tok-secret"), s"凭据值泄漏: $msg")
    assert(!msg.contains("deviceToken"), s"凭据字段名外的内容泄漏: $msg")
    assertEquals(e.reading.map(_.mask), Some(LockedMask))
  }

  test("T4-R5 结构读数缺位（打开看不见的 WRITE_ACL）⇒ 同样触发重建；补齐后正常返回") {
    val acl = new FakeWindowsAcl
    acl.probeFails = 0 // 打开成功：缺 WRITE_DAC 时真机上打开仍可能成功
    acl.reading = Some(CredentialFileAcl.AclMask.of(RepairedMask & ~0x40000L)) // 去掉 WRITE_ACL
    CredentialFileAcl.restrict(credPath, "Windows 11", new RecordingPort, ladderOf(acl))
    assertEquals(acl.repairCalls.get(), 1, s"结构缺位（读数 ${acl.reading.get.render}）必须触发重建")
    println(s"[T4-R5] deficient reading = ${acl.reading.get.render} repairs=${acl.repairCalls.get()}")
  }

  test("T4-R6 缺失路径：无 DACL 可自锁 ⇒ 文档化 no-op（不重建、不抛、不建件）") {
    val missing = tmpDir.resolve("neblink").resolve("missing.json")
    // 真 systemLadder：真打开探针 ⇒ NoSuchFileException = 「无可自锁」的显式判据
    CredentialFileAcl.restrict(missing, "Windows 11", new RecordingPort, CredentialFileAcl.systemLadder)
    assert(!Files.exists(missing), "探针不得创建文件（打开请求不带 CREATE 标志）")
    println("[T4-R6] missing path ⇒ no-op (no repair, no throw, no file created)")
  }

  test("T4-R7 调用面：save 在自锁形态下不静默 —— WARN 带 mask 读数 + site 锚点，零凭据内容") {
    val acl = new FakeWindowsAcl
    acl.probeFails = Int.MaxValue
    acl.reading = Some(CredentialFileAcl.AclMask.of(LockedMask, entries = 1))
    acl.readingAfterRepair = Some(CredentialFileAcl.AclMask.of(LockedMask, entries = 1))
    acl.repairOutcome = CredentialFileAcl.RepairOutcome.Salvaged(
      CredentialFileAcl.Salvage(s"${credPath}.aclrebuild-x", "IOException: swap refused")
    )
    val (_, warns) = LogdevTestSupport
      .withWarnsIO(DeviceCredential.save(cred, new RecordingPort, "Windows 11", ladderOf(acl)))
      .unsafeRunSync()
    val aclWarn = warns
      .find(_.contains("owner-only ACL not applied"))
      .getOrElse(fail(s"自锁形态下 save 必须留一条可诊断 WARN，实得 warns=${warns.mkString(" | ")}"))
    println(s"[T4-R7] WARN sample = $aclWarn")
    assert(aclWarn.contains("mask=0x100187"), s"WARN 缺 mask 读数: $aclWarn")
    assert(aclWarn.contains("CredentialFileAcl.scala:"), s"WARN 缺 file:line 锚点: $aclWarn")
    assert(!aclWarn.contains("tok-secret"), s"WARN 泄漏凭据值: $aclWarn")
  }

  test("T4-R8 读腿：device.json 不可读 ⇒ load 返回 None 且有分类 WARN（不再静默）") {
    assume(
      !CredentialFileAcl.isWindows(CredentialFileAcl.currentOsName),
      "POSIX-only fixture: an owner-unreadable file is produced with chmod 000"
    )
    Files.createDirectories(credPath.getParent)
    Files.writeString(credPath, "{}")
    Files.setPosixFilePermissions(credPath, PosixFilePermissions.fromString("---------"))
    DeviceCredential.resetSelfHealForTest()
    val (out, warns) = LogdevTestSupport.withWarnsIO(DeviceCredential.load).unsafeRunSync()
    println(s"[T4-R8] load=$out warns=${warns.mkString(" | ")}")
    assertEquals(out, None, "锁死形态下 load 只允许「本机此刻无凭据」，不允许抛")
    assert(warns.nonEmpty, "锁死形态必须有分类 WARN（修前完全静默）")
    assert(!warns.exists(_.contains("tok-secret")), s"WARN 泄漏凭据值: $warns")
  }

  // ── C-1/C-2/C-4：**再保存幂等性**（同一凭据 save → load → save → load）────────
  //
  // 二轮取证实锤（S-1 补充件）：**`save` 路径写入即把 DACL 抹回坏掩码 0x100187 ⇒
  // 读腿复断** —— 即「修好一次、再存一次又锁」。故判据不能只钉首次 enroll：
  // 自检必须在**每一次**写凭据件的路径上执行（本实现的落点 = `restrict` 内 ⇒
  // `DeviceCredential.save` / enroll 落盘 / refresh 轮换全部自动共用同一条梯）。

  test("T4-R9 再保存幂等（C-1/C-2）：save→load→save→load，两次写后有效 ACL 均为可用集") {
    val backend = new MemoryAclBackend(_ => RepairedMask)
    val port = new BackendPort(backend)
    val ladder = ladderOf(new BackendWindowsAcl(backend))
    DeviceCredential.save(cred, port, "Windows 11", ladder).unsafeRunSync()
    val after1 = backend.mask
    val load1 = DeviceCredential.load.unsafeRunSync().map(_.deviceId)
    DeviceCredential.save(cred.copy(logto = LogtoRefresh.of(Some("rt-1"), None)), port, "Windows 11", ladder)
      .unsafeRunSync()
    val after2 = backend.mask
    val load2 = DeviceCredential.load.unsafeRunSync().map(_.deviceId)
    println(
      s"[T4-R9] after1=0x${hex(after1)} after2=0x${hex(after2)} load1=$load1 load2=$load2 " +
        s"installs=${backend.installs.size} probes=${backend.probes.size} repairs=${backend.repairs}"
    )
    assertEquals(load1, Some("dev-1"))
    assertEquals(load2, Some("dev-1"))
    assertEquals(after1, RepairedMask, s"第一次写后读数 ${backend.render}")
    assertEquals(after2, RepairedMask, "第二次写后不得回到坏掩码")
    assertEquals(backend.installs.size, 2, "两次写都必须走 ACL 写入")
    assertEquals(backend.probes.size, 2, "🔴 自检不得只在首次写执行（C-1）")
    assertEquals(backend.repairs, 0, "形态始终正确时不应触发重建")
  }

  test("T4-R9b 再写抹回坏掩码 0x100187 ⇒ 梯当场修回 0x16019f（禁「首写可用、再写回坏」）") {
    val backend = new MemoryAclBackend(n => if n == 2 then LockedMask else RepairedMask)
    val port = new BackendPort(backend)
    val ladder = ladderOf(new BackendWindowsAcl(backend))
    DeviceCredential.save(cred, port, "Windows 11", ladder).unsafeRunSync()
    val after1 = backend.mask
    DeviceCredential.save(cred, port, "Windows 11", ladder).unsafeRunSync()
    val after2 = backend.mask
    val load2 = DeviceCredential.load.unsafeRunSync().map(_.deviceId)
    println(
      s"[T4-R9b] after1=0x${hex(after1)} installed2=0x${hex(backend.installs(1))} after2=0x${hex(after2)} " +
        s"load2=$load2 repairs=${backend.repairs} probes=${backend.probes.mkString("->")}"
    )
    assertEquals(backend.installs(1), LockedMask, "夹具确实在第二次写装了坏掩码")
    assert(backend.usable, s"第二次写后有效 ACL 必须仍可用，实得 ${backend.render}")
    assertEquals(after2, RepairedMask, s"梯必须把第二次写的坏掩码修回，实得 ${backend.render}")
    assertEquals(backend.repairs, 1, "再写抹回坏形态必须由重建梯当场修回一次")
    assertEquals(load2, Some("dev-1"), "读腿在第二次写后必须仍可读")
  }

  test("T4-R10 POSIX 分支不触碰自检/重建梯（零漂移）") {
    val port = new RecordingPort
    val acl = new FakeWindowsAcl
    CredentialFileAcl.restrict(credPath, "Mac OS X", port, ladderOf(acl))
    assertEquals(port.calls.toList, List("posix"))
    assertEquals(
      (acl.probeCalls.get(), acl.repairCalls.get(), acl.readCalls.get()),
      (0, 0, 0),
      "自检/重建梯必须只在 Windows 分支生效"
    )
  }
