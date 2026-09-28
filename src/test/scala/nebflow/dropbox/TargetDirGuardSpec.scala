package nebflow.dropbox

import munit.FunSuite
import nebflow.shared.AttachContract

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * 钉 C（spec §⑦ 钉 C）—— `TargetDirGuard` 判定链 + §3.3 的 **18 条样例**逐条读数
 * （纯判定；触盘仅**只读**探查，判定本身零副作用）。
 *
 * **修正前（红）**：`TargetDirGuard` / 四个 `TARGET_DIR_*` 码面**不存在** ⇒ 本文件编译失败（红）。
 * **修正后（绿）**：逐条命中期望码；#1/#2/#18 走允许分支。
 *
 * 允许根 = **隔离临时树**（spec §3.3 的样例串以 `/Users/kaiyu/Downloads` 为允许根，
 * 本钉用等构的临时树替换，逐条判据/期望码不变 —— 避免测试触碰真实用户主目录）。
 * `TargetDirGuard.defaultAllowRoots` 的缺省口径另有断言钉住。
 */
class TargetDirGuardSpec extends FunSuite:

  private var parent: os.Path = null // 两个允许根候选的父亲
  private var root: os.Path = null // 唯一允许根
  private var outside: os.Path = null // 允许根外（存在）
  private var roDir: os.Path = null // 允许根内、存在、只读
  private var blocker: os.Path = null // 允许根内的普通**文件**（用作中间段）

  override def beforeAll(): Unit =
    parent = os.temp.dir(prefix = "nb-tdguard-")
    root = parent / "allowed"
    os.makeDir.all(root)
    outside = parent / "outside"
    os.makeDir.all(outside)
    roDir = root / "ro-dir"
    os.makeDir.all(roDir)
    Files.setPosixFilePermissions(roDir.toNIO, PosixFilePermissions.fromString("r-xr-xr-x"))
    blocker = root / "blocker.txt"
    os.write.over(blocker, "x")
    os.symlink(root / "link-to-etc", os.Path("/etc"))

  override def afterAll(): Unit =
    try Files.setPosixFilePermissions(roDir.toNIO, PosixFilePermissions.fromString("rwxr-xr-x"))
    catch case _: Exception => ()
    os.remove.all(parent)

  // ===== 期望码名（字符串面，逐字） =====

  test("码面：四个 TARGET_DIR_* 码名逐字（SCREAMING_SNAKE，对齐既有码面风格）"):
    assertEquals(AttachContract.Codes.TargetDirInvalid, "TARGET_DIR_INVALID")
    assertEquals(AttachContract.Codes.TargetDirNotAllowed, "TARGET_DIR_NOT_ALLOWED")
    assertEquals(AttachContract.Codes.TargetDirNotFound, "TARGET_DIR_NOT_FOUND")
    assertEquals(AttachContract.Codes.TargetDirNotWritable, "TARGET_DIR_NOT_WRITABLE")
    assert(
      Set(
        AttachContract.Codes.TargetDirInvalid,
        AttachContract.Codes.TargetDirNotAllowed,
        AttachContract.Codes.TargetDirNotFound,
        AttachContract.Codes.TargetDirNotWritable
      ).size == 4 && !AttachContract.Codes.TargetDirInvalid.equals(AttachContract.Codes.InvalidArgument),
      "四码必须与既有 INVALID_ARGUMENT 区分（是细化，不是别名）"
    )

  test("量纲/版本常量：MaxTargetDirBytes = 1024 字节；等级阶梯 0 < 1 < 2"):
    assertEquals(AttachContract.MaxTargetDirBytes, 1024)
    assertEquals(AttachContract.ProtoLegacy, 0)
    assertEquals(AttachContract.ProtoChunked, 1)
    assertEquals(AttachContract.ProtoAssignDir, 2)
    assertEquals(AttachContract.negotiate(2, 1), 1, "min 协商：与 level-1 对端落到 1")
    assertEquals(AttachContract.negotiate(2, 2), 2)
    assertEquals(AttachContract.negotiate(2, 0), 0)

  test("缺省允许根 = [接收端 downloadsDir]（机制主体，只存在于接收端）"):
    assertEquals(TargetDirGuard.defaultAllowRoots, List(DropboxUtil.downloadsDir))

  // ===== §3.3 样例清单 =====

  /**
   * 允许根的 canonical 形态（macOS 上 `/var/folders/...` 的 realpath 是
   * `/private/var/folders/...` —— 两侧都走 canonicalize，故断言也用 canonical 面）。
   */
  private def rootCanonical: String = root.toNIO.toRealPath().toString

  private def resolve(s: String): Either[AttachContract.AttachError, os.Path] =
    TargetDirGuard.resolve(s, List(root), Set.empty)

  private def codeOf(s: String): String =
    resolve(s).swap.toOption.map(_.code).getOrElse("ALLOWED")

  test("样例 #1：允许根自身（绝对、存在、可写）⇒ 允许"):
    val r = resolve(root.toString)
    assert(r.isRight, s"期望允许，实际 ${r.swap.toOption.map(_.code).getOrElse("")}")
    assertEquals(r.toOption.get.toString, root.toNIO.toRealPath().toString)

  test("样例 #2：允许根内**不存在**的子目录 ⇒ 允许（允许根内可新建）"):
    val r = resolve((root / "子目录").toString)
    assert(r.isRight, s"期望允许，实际 ${r.swap.toOption.map(_.code).getOrElse("")}")

  test("样例 #3：`../../etc` ⇒ TARGET_DIR_INVALID（非绝对 + 含 `..`）"):
    assertEquals(codeOf("../../etc"), AttachContract.Codes.TargetDirInvalid)

  test("样例 #4：绝对路径含 `..` 段 ⇒ TARGET_DIR_INVALID（禁折叠）"):
    assertEquals(codeOf(s"$root/../../etc"), AttachContract.Codes.TargetDirInvalid)

  test("样例 #5：`..` 段指向允许根内隐藏目录 ⇒ TARGET_DIR_INVALID"):
    assertEquals(codeOf(s"$root/../.ssh"), AttachContract.Codes.TargetDirInvalid)

  test("样例 #6：`.` 段 ⇒ TARGET_DIR_INVALID"):
    assertEquals(codeOf(s"$root/."), AttachContract.Codes.TargetDirInvalid)

  test("样例 #7：`~/Downloads` ⇒ TARGET_DIR_INVALID（禁 `~` 展开 —— 那是接收端 home）"):
    assertEquals(codeOf("~/Downloads"), AttachContract.Codes.TargetDirInvalid)

  test("样例 #8：相对路径 ⇒ TARGET_DIR_INVALID"):
    assertEquals(codeOf("Users/kaiyu/Downloads"), AttachContract.Codes.TargetDirInvalid)

  test("样例 #9：Windows 盘符形态 ⇒ TARGET_DIR_INVALID"):
    assertEquals(codeOf("""C:\Users\kaiyu\Downloads"""), AttachContract.Codes.TargetDirInvalid)

  test("样例 #10：UNC 形态 ⇒ TARGET_DIR_INVALID"):
    assertEquals(codeOf("""\\server\share\x"""), AttachContract.Codes.TargetDirInvalid)

  test("样例 #11：含 `\\u0000` ⇒ TARGET_DIR_INVALID（结构化码，异常不逃逸）"):
    assertEquals(codeOf(s"$root\u0000/x"), AttachContract.Codes.TargetDirInvalid)

  test("样例 #12：UTF-8 字节数 > 1024 ⇒ TARGET_DIR_INVALID（量纲 = 字节，不是字符数）"):
    val long = root.toString + "/" + ("a" * (AttachContract.MaxTargetDirBytes + 1))
    assert(long.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024, "样例本身必须超限")
    assertEquals(codeOf(long), AttachContract.Codes.TargetDirInvalid)

  test("样例 #13：`/etc`（绝对、存在、允许根外）⇒ TARGET_DIR_NOT_ALLOWED"):
    assertEquals(codeOf("/etc"), AttachContract.Codes.TargetDirNotAllowed)

  test("样例 #14：允许根外的兄弟目录（存在）⇒ TARGET_DIR_NOT_ALLOWED"):
    val r = resolve(outside.toString)
    assertEquals(r.swap.toOption.map(_.code), Some(AttachContract.Codes.TargetDirNotAllowed))
    assertEquals(
      r.swap.toOption.get.expected.map(_.contains(rootCanonical)),
      Some(true),
      "拒体的 expected 必须回带允许根清单（canonical 形态）"
    )

  test("样例 #15：允许根内指向 `/etc` 的符号链接 ⇒ TARGET_DIR_NOT_ALLOWED（realpath 后逃逸）"):
    assertEquals(codeOf((root / "link-to-etc").toString), AttachContract.Codes.TargetDirNotAllowed)

  test("样例 #16：最深存在祖先是一条**文件** ⇒ TARGET_DIR_NOT_FOUND"):
    assertEquals(codeOf((blocker / "a" / "b").toString), AttachContract.Codes.TargetDirNotFound)

  test("样例 #17：允许根内、存在、只读 ⇒ TARGET_DIR_NOT_WRITABLE"):
    val r = resolve(roDir.toString)
    assertEquals(r.swap.toOption.map(_.code), Some(AttachContract.Codes.TargetDirNotWritable))

  test("样例 #18：NFD 形态 ⇒ NFC 归一化后判定（同目录不得两套码位表示）"):
    val nfd = s"$root/cafe\u0301" // e + COMBINING ACUTE
    val nfc = s"$root/caf\u00e9" // precomposed é
    assert(nfd != nfc, "样例本身必须是两种码位表示")
    assertEquals(TargetDirGuard.normalize(nfd), nfc, "归一化必须落 NFC 形态")
    val r = resolve(nfd)
    assert(r.isRight, s"NFD 形态归一化后应允许，实际 ${r.swap.toOption.map(_.code).getOrElse("")}")
    assertEquals(r.toOption.get.toString, TargetDirGuard.normalize(r.toOption.get.toString), "落盘/回显一律用 NFC 形态")
    assertEquals(resolve(nfc).map(_.toString), resolve(nfd).map(_.toString), "两种码位表示必须落到同一路径")

  // ===== 边界（spec 内部口径歧义点，已登记上报；此处钉住本实现的选择） =====

  test("边界：目标**存在**但是文件 ⇒ TARGET_DIR_NOT_WRITABLE（取 §2.3 码表枚举；非 NOT_FOUND）"):
    assertEquals(codeOf(blocker.toString), AttachContract.Codes.TargetDirNotWritable)

  test("边界：空串 / 仅空白 / null ⇒ TARGET_DIR_INVALID"):
    assertEquals(codeOf(""), AttachContract.Codes.TargetDirInvalid)
    assertEquals(codeOf("   "), AttachContract.Codes.TargetDirInvalid)
    assertEquals(
      TargetDirGuard.resolve(null, List(root), Set.empty).swap.toOption.map(_.code),
      Some(AttachContract.Codes.TargetDirInvalid)
    )

  test("边界：允许根清单为空 ⇒ fail-closed（全拒），不放过"):
    assertEquals(
      TargetDirGuard.resolve(root.toString, Nil, Set.empty).swap.toOption.map(_.code),
      Some(AttachContract.Codes.TargetDirNotAllowed)
    )

  test("边界：`locallyConfirmed` 只作为接收端本地的额外允许根生效"):
    val r = TargetDirGuard.resolve(outside.toString, List(root), Set(outside))
    assertEquals(r.toOption.map(_.toString), Some(outside.toNIO.toRealPath().toString))

  test("边界：符号链接指向允许根内真目录 ⇒ 允许（带分隔符边界的包含判定，非前缀串匹配）"):
    val insideTarget = root / "real-inside"
    os.makeDir.all(insideTarget)
    os.symlink(root / "link-in", insideTarget)
    val r = resolve((root / "link-in").toString)
    assert(r.isRight, s"根内链不应被误拒，实际 ${r.swap.toOption.map(_.code).getOrElse("")}")
    assertEquals(r.toOption.get.toString, insideTarget.toNIO.toRealPath().toString)

  test("边界：`/foo/bar-baz` 不伪匹配 `/foo/bar`（逐段包含判定）"):
    val bar = root / "bar"
    val barBaz = parent / "bar-baz"
    os.makeDir.all(bar)
    os.makeDir.all(barBaz)
    assertEquals(
      resolve(barBaz.toString).swap.toOption.map(_.code),
      Some(AttachContract.Codes.TargetDirNotAllowed)
    )

  test("边界：接收端判定的产物即 canonical 落点（判定通过 ⇒ 会话固化的是真名，不是原串）"):
    val r = resolve((root / "a" / "b").toString)
    assertEquals(r.toOption.map(_.toString), Some(s"$rootCanonical/a/b"))

end TargetDirGuardSpec
