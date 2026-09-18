package nebflow.neblink

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.PathUtil

import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** 缺陷 A 测试面共用件（round 1 起）：**用户可见串三重判据** + **WARN 采集器**。
  *
  * 三重判据（round 1 / 判词 D4 的落地）—— 本 spec 与两个路由 spec 共用**一份**
  * （禁各写一份，同「唯一文案源」纪律）。
  * 为什么单靠判别正则不够：`CredentialDiagnostics.ForbiddenInVisibleText` 的 `[A-Za-z]:\\`
  * 只覆盖 Windows 盘符 ⇒ **POSIX/macOS 绝对路径在当前正则下可静默通过**（判词实测：
  * 变异 A 下探针 P1 原文含 `/var/folders/…/device.json`，而正则命中 = **0**）。故在正则
  * 之外**追加**两条与平台无关的断言，并把三类读数逐条给出（[[violations]] 空 = 干净）：
  *   ① 判据正则零命中（Windows 盘符 / `java.` / `Exception` / `.nebflow`）；
  *   ② 不含**本次 data-root 路径**（调用点传入，不用硬编码常量）；
  *   ③ 不含凭据文件名 `device.json`。
  */
private[neblink] object LogdevTestSupport:

  private val Forbidden = CredentialDiagnostics.ForbiddenInVisibleText.r

  /** 逐类违规读数（空列表 = 三重判据全过）。 */
  def violations(s: String, dataRoot: os.Path): List[String] =
    val hits = Forbidden.findAllMatchIn(s).map(_.matched).toList
    List(
      Option.when(hits.nonEmpty)(s"判据正则命中=$hits"),
      Option.when(s.contains(dataRoot.toString))(s"data-root 路径泄漏=$dataRoot"),
      Option.when(s.contains("device.json"))("凭据文件名泄漏=device.json")
    ).flatten

  def isClean(s: String, dataRoot: os.Path): Boolean = violations(s, dataRoot).isEmpty

  /** 根 logger 上的 WARN 采集器：对「哪条腿打了哪条 WARN」做统一断言
    * （存储层与诊断层用的是两个 logger 名 ⇒ 只有挂根 logger 才看得全）。 */
  def withWarns[A](f: => A): (A, List[String]) =
    val lb = org.slf4j.LoggerFactory
      .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
      .asInstanceOf[ch.qos.logback.classic.Logger]
    val app = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    app.start()
    lb.addAppender(app)
    try
      val out = f
      (out, app.list.asScala.toList
        .filter(_.getLevel == ch.qos.logback.classic.Level.WARN)
        .map(_.getFormattedMessage))
    finally lb.detachAppender(app)

  /** 同上，但采集**一段 IO 执行期间**的 WARN（路由腿用：读点在被测 IO 内部，
    * 不能在 IO 外用同步 thunk 夹取）。 */
  def withWarnsIO[A](io: cats.effect.IO[A]): cats.effect.IO[(A, List[String])] =
    val lb = org.slf4j.LoggerFactory
      .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
      .asInstanceOf[ch.qos.logback.classic.Logger]
    val app = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    for
      out <- cats.effect.IO(app.start()) *> cats.effect.IO(lb.addAppender(app)) *> io
        .guarantee(cats.effect.IO(lb.detachAppender(app)))
      warns <- cats.effect.IO(app.list.asScala.toList
        .filter(_.getLevel == ch.qos.logback.classic.Level.WARN)
        .map(_.getFormattedMessage))
    yield (out, warns)

/** 缺陷 A（2026-09-18 换号登录失败批）· **失败分类断言钉** + 文案负控
  * （上游 §8.2 第 9 项 / §10.2 判据 G2·G3·G4）。
  *
  * 缺陷形态（本批修前）：凭据文件的读/写/删三条腿**没有分类** —— 底层异常（Windows 上
  * 常常**只有路径**）原样透到登录框；解码失败**完全静默**（连日志都没有）；坏件既无修复
  * 路径也无自愈路径 ⇒「一次坏了就永久坏」。本 spec 钉四件事：
  *
  *  R1 **分类可二值判读**：读失败 / 解码失败 / 写失败 / 删失败四条腿各自产出**稳定码**
  *     （`credential-unreadable` / `credential-undecodable` / `credential-write-denied` /
  *     `credential-delete-denied`），且 `load` / `clear` **永不抛**（判据 G4①②：读失败与
  *     坏件都不许把 `/status` 打成 500）。
  *     🔴 **round 1 补正（判词 D2）**：三条腿的夹具改为**平台中立**形态（[[occupyCredPathWithDir]]：
  *     凭据落点放一个非空目录）⇒ 修前那 3 处 `assume(!CredentialFileAcl.isWindows(…))` 平台守卫
  *     **结构性去除**，本 spec 现在**零 `assume`**；判据一字未放宽，且新增「兼容面 `load` 也走
  *     真实读失败腿」与「删失败时第①级改名留档必成」两条读数。
  *  R2 **文案负控**（判据 G2/G3）：全分类表 × **最脏 detail**（Windows 路径 + `java.nio`
  *     异常类名）⇒ 用户可见串过**三重**判据（[[LogdevTestSupport]]：判据正则 + 不含 data-root
  *     路径 + 不含 `device.json`；round 1 判词 D4 追加后两条 —— 判据正则的 `[A-Za-z]:\\` 只覆盖
  *     Windows 盘符，POSIX 绝对路径在它下面会静默通过）；并带**正控**（脏串必须能命中、
  *     POSIX 泄漏必须被追加的两条拦住 —— 否则负控是空断言）。
  *  R3 **坏件自愈**（判据 G4③④）：坏件 ⇒ 盘上出现**一次性**备份件 + 恰好**一条**带分类码的
  *     WARN；第二次读不再改名/不再刷日志，但分类读数仍在。
  *  R4 **镜像漂移**：`neblink.js` 的 `LOCAL_FILE_CODES` 与后端 `CredentialFailure` 的本地
  *     文件类码**逐字同源**；每个 code 在 zh-CN / en 两份 locale 里都有 `reason`+`action`
  *     键（禁死键 / 禁漏键，两向都钉）。
  */
class CredentialDiagnosticsSpec extends FunSuite:

  import CredentialDiagnostics as CD
  import CredentialFailure as CF

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-cred-diag-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))
    // 跨 suite 共享 JVM：闩必须复位，否则第二个观测点看不到 WARN/备份（既有先例：
    // `DeviceCredential.resetLegacyTokenWarnForTest`）。
    DeviceCredential.resetSelfHealForTest()
    DeviceCredential.resetLegacyTokenWarnForTest()

  override def afterEach(context: AfterEach): Unit =
    // 权限实验留下的只读目录会让清理失败 ⇒ 先复位权限再删。
    val dir = neblinkDir
    if Files.exists(dir.toNIO) then
      try Files.setPosixFilePermissions(dir.toNIO, PosixFilePermissions.fromString("rwx------"))
      catch case _: Exception => ()
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  // ── 夹具 ───────────────────────────────────────────────

  private def root: os.Path = os.Path(tmpDir, os.pwd)
  private def neblinkDir: os.Path = root / "neblink"
  private def credFile: os.Path = neblinkDir / "device.json"

  private def backupNames: List[String] =
    if !Files.exists(neblinkDir.toNIO) then Nil
    else os.list(neblinkDir).toList.map(_.last).filter(_.startsWith("device.json.corrupt-"))

  /** WARN 采集器（共用件，见 [[LogdevTestSupport.withWarns]]）。 */
  private def withWarns[A](f: => A): (A, List[String]) = LogdevTestSupport.withWarns(f)

  private def writeCredentialJson(json: String): Unit =
    Files.createDirectories(neblinkDir.toNIO)
    Files.writeString(credFile.toNIO, json)

  /** 平台中立的「本机凭据打不开 / 删不掉」夹具（round 1 / 判词 D2 的落地）。
    *
    * 形态：在 `device.json` 的**位置**上放一个**非空目录**。
    *  - 读它会抛 `IOException`（POSIX `IsADirectoryException` / Windows `AccessDeniedException`）；
    *  - 删它会失败（POSIX `DirectoryNotEmptyException` / Windows `AccessDeniedException`）；
    *  - 两者都是 `IOException` ⇒ 与修前的 `chmod 000` 夹具走**同一条分类边**
    *    （`CredentialDiagnostics.classify` 只看**操作面**，不看异常类型）。
    *
    * 为什么换掉 `chmod 000`（判词 D2）：`Files.setPosixFilePermissions` 只在 POSIX 上存在，
    * 那三处夹具因此要靠 `assume(!isWindows)` 平台守卫，等于在 Windows 上**整条腿不判**。
    * 本夹具在任何平台都能构造 ⇒ 三处平台守卫**结构性去除**（本 spec 现在零 `assume`），
    * 而判据（读失败 ⇒ `credential-unreadable` / 写失败 ⇒ `credential-write-denied` /
    * 删失败 ⇒ `credential-delete-denied`）一字未放宽。 */
  private def occupyCredPathWithDir(): Unit =
    Files.createDirectories(credFile.toNIO)
    Files.writeString(credFile.toNIO.resolve("occupied-by-a-directory.txt"), "not a credential file")

  // ── R1 分类可二值判读 ───────────────────────────────────

  test("R1a 读失败（凭据落点不是可读文件）⇒ LoadDiagnosed 分类 credential-unreadable；load 永不抛") {
    // 两条腿**各自**走一次真实读失败：第一次 `loadDiagnosed` 会顺手自愈（改名留档），
    // 故复位闩 + 重布夹具，让兼容面 `load` 也真的落在「读失败」腿上（不是落在自愈后的空态）。
    occupyCredPathWithDir()
    val (diagnosed, warnsDiag) = withWarns(DeviceCredential.loadDiagnosed.unsafeRunSync())
    DeviceCredential.resetSelfHealForTest()
    occupyCredPathWithDir()
    val (plain, warnsPlain) = withWarns(DeviceCredential.load.unsafeRunSync())
    assertEquals(diagnosed.left.toOption.map(_.code), Some(CF.CredentialUnreadable.code))
    assertEquals(plain, None, "load 是兼容面：读失败 ⇒ None（**不抛**：判据 G4①②）")
    assert(
      warnsDiag.exists(_.contains(CF.CredentialUnreadable.code)),
      s"读失败必须留一条带分类码的 WARN（判据 G4④），实际: $warnsDiag"
    )
    assert(
      warnsPlain.exists(_.contains(CF.CredentialUnreadable.code)),
      s"兼容面 load 的读失败同样不得静默（判据 G4④），实际: $warnsPlain"
    )
  }

  test("R1b 解码失败 ⇒ 分类 credential-undecodable + 备份改名 + 带码 WARN（判据 G4③④）") {
    writeCredentialJson("{ this is not json")
    val (outcome, warns) = withWarns(DeviceCredential.loadDiagnosed.unsafeRunSync())
    assertEquals(outcome.left.toOption.map(_.code), Some(CF.CredentialUndecodable.code))
    assertEquals(backupNames.length, 1, "坏件必须改名留档（一次性备份件）")
    assert(warns.exists(_.contains(CF.CredentialUndecodable.code)), s"缺带分类码的 WARN: $warns")
  }

  test("R1c 写失败（落点被非空目录占用）⇒ save 抛 CredentialStoreError（分类 credential-write-denied），WARN 带码") {
    occupyCredPathWithDir() // 原子写的 rename 落不到目标上（目标是非空目录）
    val cred = DeviceCredential("https://neblink.example", "n-1", "d-1", "tok")
    val (result, warns) = withWarns(
      DeviceCredential.save(cred, nebflow.core.CredentialFileAcl.systemPort,
        nebflow.core.CredentialFileAcl.currentOsName).attempt.unsafeRunSync()
    )
    val failure = result.left.toOption.collect {
      case e: CD.CredentialStoreError => e.diagnostic.code
    }
    assertEquals(failure, Some(CF.CredentialWriteDenied.code),
      "写失败必须带上分类（不是裸 IOException —— 裸异常正是缺陷原形）")
    assert(warns.exists(_.contains(CF.CredentialWriteDenied.code)), s"缺带分类码的 WARN: $warns")
    // 分类 → 用户可见文案：走 Left 通道的那份文本必须干净且带码。
    assert(!CD.classifyFailure(result.left.toOption.getOrElse(new Exception("x"))).message
      .contains("java."), "用户可见文案不得含异常类名")
  }

  test("R1d 删失败（落点被非空目录占用）⇒ clear 永不抛（登出腿必须完成本地拆除）+ 带码 WARN") {
    occupyCredPathWithDir() // 非空目录 ⇒ `os.remove` 失败（不是「文件不在了」的空态）
    val (_, warns) = withWarns(DeviceCredential.clear.unsafeRunSync())
    assert(warns.exists(_.contains(CF.CredentialDeleteDenied.code)), s"缺带分类码的 WARN: $warns")
    // 两级处置的第①级：改名留档仍应成功（改名只需父目录权限）⇒ 坏件不挡「清理并重登」。
    assert(
      !os.exists(credFile) && backupNames.nonEmpty,
      s"删不掉时必须尝试改名留档（第①级处置）: backups=$backupNames"
    )
  }

  test("R1e 分类码 → 可见文案的反查是恒等映射（Left 通道的自由串靠它回到结构化）") {
    CD.all.foreach { f =>
      val message = CD.diagnosticOf(f).message
      assertEquals(CD.byVisibleMessage(message), Some(f), s"反查必须命中 $f（码 ${f.code}）")
    }
  }

  test("R1f 本地文件类码集合 = credential-* 六条（前端「清理并重登」的门控面）") {
    assertEquals(
      CD.localFileCodes.sorted,
      List(
        CF.CredentialAclNotApplied,
        CF.CredentialDeleteDenied,
        CF.CredentialMissing,
        CF.CredentialUndecodable,
        CF.CredentialUnreadable,
        CF.CredentialWriteDenied
      ).map(_.code).sorted
    )
    assert(CD.isLocalFileCode(CF.CredentialUnreadable.code))
    assert(!CD.isLocalFileCode(CF.ServerNoDeviceToken.code), "服务端类故障不给本地清理入口")
  }

  // ── R2 文案负控（判据 G2/G3）────────────────────────────

  test("R2a 全表负控：最脏 detail 下，用户可见串对判据正则零命中（并追加 data-root / device.json 二重断言）") {
    // 正控（防空断言）：脏串必须真的能命中判据正则。
    assert(!CD.isCleanVisibleText("java.nio.file.AccessDeniedException: C:\\x\\.nebflow\\neblink\\device.json"))
    assert(!CD.isCleanVisibleText("Failed to read /.nebflow/neblink/device.json"))
    assert(CD.isCleanVisibleText("本机凭据文件打不开（权限或占用）"))
    // D4 二重断言的正控：POSIX 绝对路径**不命中**判据正则（这正是要补这两条的理由）
    // —— 故只靠正则时它是「干净」的，必须由 data-root / 文件名两条断言拦住。
    val posixLeak = s"不能读取 $root/neblink/device.json"
    assert(CD.isCleanVisibleText(posixLeak), "判据正则对 POSIX 绝对路径零命中（判词 D4 实测形态）")
    assert(LogdevTestSupport.violations(posixLeak, root).length == 2,
      s"data-root 与 device.json 两条断言必须各自命中: ${LogdevTestSupport.violations(posixLeak, root)}")

    val dirty = "java.nio.file.AccessDeniedException: C:\\Users\\kaiyu\\.nebflow\\neblink\\device.json"
    CD.all.foreach { f =>
      val d = CD.diagnosticOf(f, dirty)
      List(
        s"$f.message" -> d.message,
        s"$f.reason" -> d.reason,
        s"$f.action" -> d.action
      ).foreach { case (tag, s) =>
        assertEquals(LogdevTestSupport.violations(s, root), Nil, s"$tag 三条判据必须全过: $s")
      }
      // 三段式形态 + 稳定码（判据 G2：错误面必须能机械判读）。
      assert(d.message.contains(s"诊断码：${f.code}"), s"$f 的 message 缺诊断码")
      assert(d.message.startsWith("登录失败："), s"$f 的 message 缺三段式首句")
      assert(d.message.contains("下一步："), s"$f 的 message 缺动作句")
      // detail 只在日志面 —— 含护栏拒绝分类在内**无条件**成立（`diagnosticOf` 的可见面
      // 闸门：例外分支也只在 detail 本身干净时透出 ⇒ 不变量不靠调用点自律）。
      assert(!d.message.contains("AccessDeniedException"), s"$f 把 detail 泄进了可见文案")
    }
  }

  test("R2b 护栏拒绝的可见文案保留案 C 语义（真因照实透出，且文本本身干净）") {
    val guardReason =
      "refused: this instance runs on an isolated data root, so it must not auto-register " +
        "with the production NebLink server (https://nebflow.space). Set NEBFLOW_ALLOW_PROD_ENROLL=1 to allow " +
        "it explicitly (same account as another instance ⇒ one live session, the older one is kicked)."
    val d = CD.diagnosticOf(CF.EnrollRefusedIsolatedHome, guardReason)
    assert(d.reason.contains("isolated data root"), "案 C：护栏原文必须照实出现在可见原因里")
    assertEquals(LogdevTestSupport.violations(d.message, root), Nil, "护栏文案本身必须干净（判据 G3）")
    // 反控（可见面闸门）：脏 detail 不得借「案 C 例外」通道进可见面，但必须原样留在日志面。
    val dirty = "java.nio.file.AccessDeniedException: C:\\Users\\kaiyu\\.nebflow\\neblink\\device.json"
    val g = CD.diagnosticOf(CF.EnrollRefusedIsolatedHome, dirty)
    assertEquals(LogdevTestSupport.violations(g.message, root), Nil, "例外分支也必须过可见面闸门（不变量无条件）")
    assert(g.detail == dirty, "原文仍须进日志面（可归因，不许连日志一起吞）")
  }

  test("R2c 分类异常 → 诊断的收敛是单点（CredentialStoreError 原样取回其分类）") {
    val diag = CD.diagnosticOf(CF.CredentialWriteDenied, "boom")
    val e: Throwable = new CD.CredentialStoreError(diag)
    assertEquals(CD.classifyFailure(e, CF.Unclassified).code, CF.CredentialWriteDenied.code)
    assertEquals(
      CD.classifyFailure(new RuntimeException("whatever"), CF.Unclassified).code,
      CF.Unclassified.code
    )
  }

  // ── R3 自愈（判据 G4③④）────────────────────────────────

  test("R3a 自愈是一次性的：第二次读不再改名、不再刷日志，但分类读数仍在") {
    writeCredentialJson("{ broken")
    val (first, warnsFirst) = withWarns(DeviceCredential.loadDiagnosed.unsafeRunSync())
    assertEquals(first.left.toOption.map(_.code), Some(CF.CredentialUndecodable.code))
    assertEquals(backupNames.length, 1)
    assertEquals(warnsFirst.count(_.contains(CF.CredentialUndecodable.code)), 1, "恰好一条 WARN")
    // 第二次：文件已被改名 ⇒ 干净空态（Right(None)），零新增备份、零新增 WARN。
    val (second, warnsSecond) = withWarns(DeviceCredential.loadDiagnosed.unsafeRunSync())
    assertEquals(second, Right(None), "自愈后 = 当作无凭据继续（不是永久坏）")
    assertEquals(backupNames.length, 1, "备份件只能有一个（一次性）")
    assertEquals(warnsSecond, Nil, "第二次读不得再刷日志")
    assertEquals(DeviceCredential.selfHealAttemptsForTest, 1)
  }

  test("R3b 坏件态下 save 仍可重建 (自愈后按空盘继续) —— 坏 ⟶ 不永久坏") {
    writeCredentialJson("{ broken")
    val _ = DeviceCredential.load.unsafeRunSync() // 触发自愈
    DeviceCredential.save(DeviceCredential("https://neblink.example", "n-1", "d-1", "tok")).unsafeRunSync()
    val back = DeviceCredential.loadDiagnosed.unsafeRunSync()
    assertEquals(back.map(_.map(_.deviceId)), Right(Some("d-1")))
  }

  test("R3c 原子写落地（复用 AtomicJson）：save 后目录里不留 *.tmp.* 残渣") {
    DeviceCredential.save(DeviceCredential("https://neblink.example", "n-1", "d-1", "tok")).unsafeRunSync()
    val leftovers = os.list(neblinkDir).toList.map(_.last).filter(_.contains(".tmp."))
    assertEquals(leftovers, Nil)
    assertEquals(DeviceCredential.load.unsafeRunSync().map(_.deviceId), Some("d-1"))
  }

  // ── R4 镜像漂移（前端 / i18n）───────────────────────────

  private def repoFile(rel: String): String =
    // 模块根定位：sbt test 的 cwd 通常是模块根，但闸/包装器可能从别处起 ⇒ 向上最多探 3 级，
    // 找不到就如实失败（不会静默空读）。
    val candidates = (0 to 3).map(n => Path.of(("../" * n) + rel)).toList
    candidates.find(Files.exists(_)) match
      case Some(p) => Files.readString(p)
      case None    => fail(s"repo file not found from cwd=${Path.of(".").toAbsolutePath}: $rel")

  test("R4a neblink.js 的 LOCAL_FILE_CODES 与后端本地文件类码逐字同源") {
    val js = repoFile("src/main/resources/web/js/neblink.js")
    val block = js.split("export const LOCAL_FILE_CODES")(1).split("\\]")(0)
    val codes = "'([^']+)'".r.findAllMatchIn(block).map(_.group(1)).toList
    assertEquals(codes.sorted, CD.localFileCodes.sorted, "前端门控表与后端分类表必须逐字同源")
  }

  test("R4b zh-CN / en 两份 locale 都有每个 code 的 reason + action 键（禁死键/禁漏键）") {
    val locales = List(
      "src/main/resources/web/js/locales/zh-CN.js",
      "src/main/resources/web/js/locales/en.js"
    ).map(f => f -> repoFile(f))
    locales.foreach { (file, body) =>
      CD.all.foreach { f =>
        assert(body.contains(s"'login.reason.${f.code}':"), s"$file 缺 login.reason.${f.code}")
        assert(body.contains(s"'login.action.${f.code}':"), s"$file 缺 login.action.${f.code}")
      }
      assert(body.contains("'login.failureLine':"), s"$file 缺三段式模板键")
      assert(body.contains("'login.diagnosticCode':"), s"$file 缺诊断码行键")
      assert(body.contains("'neblink.cleanupRelogin':"), s"$file 缺清理并重登键")
      assert(body.contains("'neblink.statusDegraded':"), s"$file 缺状态降级键")
    }
  }

  test("R4c activityBar.js 的模态失败面按 data.code 渲染 + 清理键只在本地文件类出现") {
    val js = repoFile("src/main/resources/web/js/activityBar.js")
    assert(js.contains("data-code=\"${escapeHtml(failureCode)}\""), "失败面板必须暴露分类码（二值断言契约）")
    assert(js.contains("LOCAL_FILE_CODES.includes(failureCode)"), "清理键必须按分类门控")
    // 既有两键档位与主/次键视觉逐字保留（§13：禁造新轮子）。
    assert(js.contains("id=\"login-retry\""), "重试键必须保留")
    assert(js.contains("id=\"login-switch-account\""), "切换账号键必须保留")
    assert(js.contains("cfg-btn-primary"), "主键档位必须保留")
    assert(js.contains("login-modal-btn-secondary"), "次键档位必须保留")
  }

  test("R4d neblink.js 状态面不再静默（判据 G6）+ 失败文案走 i18n 单点") {
    val js = repoFile("src/main/resources/web/js/neblink.js")
    // 断言必须打在**代码**面：修前原形在新代码的注释里被引用（讲清改了什么），若拿整份文件
    // 做 `contains` 就会把「注释里提到旧写法」判成回归 —— 故先剥掉整行注释再断言。
    val code = js.linesIterator.filterNot(_.trim.startsWith("//")).mkString("\n")
    assert(!code.contains("if (!resp.ok) return;"), "状态面静默吞掉（修前原形）必须消失")
    assert(!code.contains("if (!resp.ok) return\n"), "状态面静默吞掉（无分号变体）必须消失")
    assert(js.contains("statusDegraded"), "状态面降级读数必须存在")
    assert(js.contains("export function loginFailureText"), "三段式组装点必须存在")
    assert(js.contains("export function openEndSessionHandoff"), "清理并重登必须复用既有 end-session 链")
  }

  test("R4e PkceLoginSession.statusJson 的分类错误态带 status/error/code/reason/action 五键") {
    val session = PkceLoginSession.unsafe
    val json = (for
      _ <- session.failClassified(CF.CredentialWriteDenied, "raw detail goes to the log only")
      st <- session.statusJson
    yield st).unsafeRunSync()
    assertEquals(json.hcursor.downField("status").as[String], Right("error"))
    assertEquals(json.hcursor.downField("code").as[String], Right(CF.CredentialWriteDenied.code))
    val err = json.hcursor.downField("error").as[String].toOption.getOrElse("")
    assert(err.contains(s"诊断码：${CF.CredentialWriteDenied.code}"), s"error 必须承载三段式: $err")
    assertEquals(LogdevTestSupport.violations(err, root), Nil, s"error 串三条判据必须全过: $err")
    assertEquals(
      json.hcursor.downField("reason").as[String],
      Right(CD.diagnosticOf(CF.CredentialWriteDenied).reason)
    )
    assert(json.hcursor.downField("action").as[String].toOption.exists(_.nonEmpty))
  }

end CredentialDiagnosticsSpec
