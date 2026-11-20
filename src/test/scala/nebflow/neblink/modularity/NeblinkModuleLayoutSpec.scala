package nebflow.neblink.modularity

import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/**
 * neblink 模块布局契约（模块化批 2026-11-06）。
 *
 * 这是一组**模块级单元测试**，只断言「结构」不触碰业务行为：
 *   - 目录 ↔ `package` 声明一一对应（搬文件不改包名 = 隐性漂移）；
 *   - 根包不再有源文件（模块化完成的定义：没有平铺文件）；
 *   - 子包集合 = 白名单（新增/删除模块必须改本表 = 显式决策）；
 *   - 每个子包至少一个源文件（防空目录假模块）；
 *   - 全仓不再有已失效的 `import nebflow.neblink.*`（根包已空）。
 *
 * 为什么单列而不并进行为 spec：行为 spec 会因环境/网络跳过，而结构漂移必须
 * 每次构建都被拦下。本 suite 零 IO、零网络、纯文件系统断言，永远可跑。
 */
class NeblinkModuleLayoutSpec extends FunSuite:

  /** 模块化后的合法子包集合（新增模块必须同步此表）。 */
  private val ExpectedPackages: Set[String] = Set(
    "attachment",
    "core",
    "identity",
    "logto",
    "misc",
    "portal",
    "relay",
    "social",
    "sync"
  )

  private val RootPackage = "nebflow.neblink"

  private def repoRoot: Path =
    val cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath
    Iterator
      .iterate(cwd)(_.getParent)
      .take(6)
      .find(p => Files.exists(p.resolve("build.sbt")))
      .getOrElse(cwd)

  private def neblinkDir: Path =
    repoRoot.resolve("src/main/scala/nebflow/neblink")

  private def scalaFiles(dir: Path): List[Path] =
    val stream = Files.walk(dir)
    try stream.iterator().asScala.filter(_.getFileName.toString.endsWith(".scala")).toList
    finally stream.close()

  private def relative(p: Path): String =
    neblinkDir.relativize(p).toString.replace('\\', '/')

  private def declaredPackage(p: Path): String =
    val text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8)
    val m = "(?m)^package\\s+([\\w.]+)".r.findFirstMatchIn(text)
    m.map(_.group(1)).getOrElse(fail(s"$p 没有 package 声明"))

  /** 目录 → 期望包名：`logto/X.scala` ⇒ `nebflow.neblink.logto`。 */
  private def expectedPackage(p: Path): String =
    val parent = Option(p.getParent).map(neblinkDir.relativize).map(_.toString.replace('\\', '/'))
    parent match
      case Some("") | None => RootPackage
      case Some(rel) => RootPackage + "." + rel.replace('/', '.')

  test("每个源文件的 package 与其目录一一对应") {
    val files = scalaFiles(neblinkDir)
    assert(files.nonEmpty, "neblink 目录下应至少有一个源文件")
    for p <- files do assertEquals(declaredPackage(p), expectedPackage(p), s"包名与目录不一致: ${relative(p)}")
  }

  test("根包不再有平铺源文件（模块化完成判据）") {
    val flat = scalaFiles(neblinkDir).filter(_.getParent == neblinkDir)
    assert(flat.isEmpty, s"neblink/ 根下仍有平铺源文件: ${flat.map(relative)}")
  }

  test("子包集合 = 白名单（新增/删除模块必须显式改本表）") {
    val stream = Files.list(neblinkDir)
    val actual =
      try
        stream
          .iterator()
          .asScala
          .filter(Files.isDirectory(_))
          .map(_.getFileName.toString)
          .toSet
      finally stream.close()
    assertEquals(actual, ExpectedPackages)
  }

  test("每个子包至少有一个源文件（防空目录假模块）") {
    for pkg <- ExpectedPackages do
      val dir = neblinkDir.resolve(pkg)
      assert(Files.isDirectory(dir), s"缺少子包目录: $pkg")
      val files = scalaFiles(dir)
      assert(files.nonEmpty, s"子包目录为空: $pkg")
  }

  test("全仓不再有失效的 `import nebflow.neblink.*`（根包已空）") {
    val pattern = "(?m)^\\s*import\\s+nebflow\\.neblink\\.\\*\\s*$".r
    val offenders =
      for
        base <- List("src/main/scala", "src/test/scala")
        file <- scalaFiles(repoRoot.resolve(base))
        text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
        if pattern.findFirstIn(text).isDefined
      yield repoRoot.relativize(file).toString.replace('\\', '/')
    assert(offenders.isEmpty, s"存在指向空根包的通配 import（应改为具体子包或删除）: $offenders")
  }

end NeblinkModuleLayoutSpec
