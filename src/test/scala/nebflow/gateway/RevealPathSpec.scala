/* canvas-reveal-in-finder 批（2026-11-19）：[[RevealPath]] 的判据/argv 单测。
 *
 * 为什么这几条必须有：该端点会**在这台机器上起一个进程**，所以「平台怎么判」
 * 「argv 由谁拼」和「哪条路径允许被定位」三件事都不能只靠浏览器侧的门禁兜。
 * 三条判据：
 *   ① 平台来自 os.name（三档 target），不认识的操作系统 = 没有动作面；
 *   ② argv 是 token 表：文件名里的空格/引号/分号/`$()` 一律只是文件名字符
 *      （路径永远是**单独一个**参数，全仓禁 shell 拼接）；
 *   ③ 路径判据 = nf-file 同一份阶梯，且**执行用的是 realpath**（macOS
 *      `/var` 与 `/private/var` 是同一文件 —— 本仓踩过的坑）。
 */
package nebflow.gateway

import cats.effect.unsafe.implicits.global
import java.nio.file.{Files, Path}

class RevealPathSpec extends munit.FunSuite:

  private def withTempFile[A](name: String)(f: Path => A): A =
    val dir = Files.createTempDirectory("reveal-spec")
    val file = dir.resolve(name)
    Files.writeString(file, "# fixture\n")
    try f(file)
    finally
      Files.deleteIfExists(file)
      Files.deleteIfExists(dir)

  test("platformFrom: mac/win/linux 三档 target，未知宿主没有动作面") {
    assertEquals(RevealPath.platformFrom("Mac OS X").map(_.target), Some("finder"))
    assertEquals(RevealPath.platformFrom("Windows 11").map(_.target), Some("explorer"))
    assertEquals(RevealPath.platformFrom("Linux").map(_.target), Some("file-manager"))
    assertEquals(RevealPath.platformFrom("FreeBSD").map(_.target), Some("file-manager"))
    assertEquals(RevealPath.platformFrom(""), None)
  }

  test("argv: 路径永远是单独一个 token（文件名里的 shell 元字符只是字符）") {
    val evil = Path.of("/tmp/a b;$(rm -rf x)`id`\"q\".md")
    assertEquals(
      RevealPath.argvWith("/usr/bin/open", RevealPath.Platform.MacOS, evil),
      Some(List("/usr/bin/open", "-R", "/tmp/a b;$(rm -rf x)`id`\"q\".md"))
    )
    // Windows：`/select,` 与路径分两个 token（单个 `/select,"<path>"` 会被
    // ProcessBuilder 二次加引号 ⇒ explorer 解析错位）。
    assertEquals(
      RevealPath.argvWith("explorer.exe", RevealPath.Platform.Windows, evil),
      Some(List("explorer.exe", "/select,", "/tmp/a b;$(rm -rf x)`id`\"q\".md"))
    )
    // Linux：xdg-open 没有「选中」语义 ⇒ 打开父目录。
    assertEquals(
      RevealPath.argvWith("/usr/bin/xdg-open", RevealPath.Platform.Linux, evil),
      Some(List("/usr/bin/xdg-open", "/tmp"))
    )
    assertEquals(RevealPath.argvWith("/usr/bin/xdg-open", RevealPath.Platform.Linux, Path.of("/")), None)
  }

  test("resolvePath: 真常规文件通过，且用的是 realpath（/var ⇄ /private/var 同一文件）") {
    withTempFile("把 报告 (终版).md") { file =>
      val verdict = RevealPath.resolvePath(file.toString).unsafeRunSync()
      verdict match
        case Left(refusal) => fail(s"unexpected refusal: ${refusal.code} ${refusal.message}")
        case Right(real) =>
          assertEquals(real, file.toRealPath())
          // macOS 上 tempdir 在 /var/… ⇒ realpath 必然是 /private/var/…：这条同时
          // 证明「执行/回报都用 realpath」，即作者踩过的那个坑不会复发。
          if file.toString.startsWith("/var/") then assert(real.toString.startsWith("/private/var/"))
    }
  }

  test("resolvePath: 目录 / 不存在的文件 / 空路径一律结构化拒绝，绝不执行") {
    val dir = Files.createTempDirectory("reveal-spec-dir")
    try
      RevealPath.resolvePath(dir.toString).unsafeRunSync() match
        case Left(r) => assertEquals(r.code, "not-found") // 阶梯的「非常规文件」与「不存在」同一出口
        case Right(p) => fail(s"a directory must not be revealable: $p")
      RevealPath.resolvePath(dir.resolve("gone.md").toString).unsafeRunSync() match
        case Left(r) => assertEquals(r.code, "not-found")
        case Right(p) => fail(s"a missing file must not be revealable: $p")
      RevealPath.resolvePath("   ").unsafeRunSync() match
        case Left(r) => assertEquals(r.code, "missing-path")
        case Right(p) => fail(s"an empty path must not be revealable: $p")
    finally Files.deleteIfExists(dir)
  }

  test("opener: 三档都有打开器解析口（Windows 恒 Some，其余按本机可执行件）") {
    assert(RevealPath.opener(RevealPath.Platform.Windows).isDefined)
    // mac/linux 两档取决于本机是否装了对应打开器 —— 只断言「不抛、且形态是裸名/绝对名」。
    for p <- List(RevealPath.Platform.MacOS, RevealPath.Platform.Linux) do
      RevealPath.opener(p).foreach(bin => assert(bin.nonEmpty && !bin.contains("\u0000")))
  }
end RevealPathSpec
