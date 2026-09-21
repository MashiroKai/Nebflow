package nebflow.core.tools

import munit.FunSuite

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * RgHelper 20260909 Glob 修复批回归：
 *  - readWithLimit 超限从「抛异常即死」改为「截断 + truncated 标志」（defect ② 地基）；
 *  - runRg 新增 processCwd（Glob 以搜索根为 rg cwd 搜 "." —— gitignore 锚定
 *    语义以搜索根为基准；单段通配对绝对路径永零命中的根因修复）；
 *  - runRg 新增 truncateOnOverflow：false（Grep）保持硬 ToolError 行为零回归，
 *    true（Glob）截断返回。
 *
 * rg 依赖：与 GlobToolSpec 同先例（工具面真跑 rg；PATH 或 ~/.nebflow/bin）。
 */
class RgHelperSpec extends FunSuite:

  private val utf8 = StandardCharsets.UTF_8

  /** launchd 最小 PATH（2026-09-21 事发读数，.nebflow/evidence/20260921_env-rg-restore/）。 */
  private val launchdMinimalPath = "/usr/bin:/bin:/usr/sbin:/sbin"

  /** 平台真实的 rg 可执行文件名（RgHelper 的 rgBinName 为 private，测试本地镜像）。 */
  private def rgName: String =
    if sys.props.getOrElse("os.name", "").toLowerCase.contains("win") then "rg.exe" else "rg"

  /** 造一个假的 rg 可执行文件（内容无关——解析链只判 isFile，不执行）。 */
  private def fakeRg(dir: os.Path): String =
    os.write(dir / rgName, "#!/bin/sh\n", createFolders = true)
    (dir / rgName).toString

  // ---- envfix 批 2026-09-21：策展前缀腿（作者裁定①，只增解析位不改优先序）--

  test("minimal PATH resolves rg via the curated probe leg (launchd context)") {
    val tmp = os.Path(java.nio.file.Files.createTempDirectory("nb-rg-probe"))
    try
      val probeDir = tmp / "opt-homebrew-bin"
      val seeded = fakeRg(probeDir)
      val r = RgHelper.resolveRgPathFrom(
        pathEnv = launchdMinimalPath, // dirs exist but carry no rg
        probeDirs = List(probeDir.toString),
        localPath = (tmp / "no-home" / ".nebflow" / "bin" / rgName).toString, // nonexistent
        winInstall = None
      )
      assertEquals(r, Some(seeded), "curated probe dir must supply the hit when PATH is minimal")
    finally os.remove.all(tmp)
  }

  test("PATH leg outranks the curated probe leg (existing priority unchanged)") {
    val tmp = os.Path(java.nio.file.Files.createTempDirectory("nb-rg-order"))
    try
      val pathDir = tmp / "on-path"
      val probeDir = tmp / "probed"
      val onPath = fakeRg(pathDir)
      fakeRg(probeDir)
      val r = RgHelper.resolveRgPathFrom(
        pathEnv = pathDir.toString,
        probeDirs = List(probeDir.toString),
        localPath = (tmp / "no-home" / ".nebflow" / "bin" / rgName).toString,
        winInstall = None
      )
      assertEquals(r, Some(onPath), "PATH hit must keep priority over the curated probe leg")
    finally os.remove.all(tmp)
  }

  test("curated probe leg outranks the local ~/.nebflow/bin leg (PATH-adjacent insertion)") {
    val tmp = os.Path(java.nio.file.Files.createTempDirectory("nb-rg-local"))
    try
      val probeDir = tmp / "probed"
      val probed = fakeRg(probeDir)
      val localDir = tmp / "local-bin"
      val local = fakeRg(localDir)
      val r = RgHelper.resolveRgPathFrom(
        pathEnv = launchdMinimalPath,
        probeDirs = List(probeDir.toString),
        localPath = local,
        winInstall = None
      )
      assertEquals(r, Some(probed), "probe leg sits with the PATH leg, before the local bin leg")
    finally os.remove.all(tmp)
  }

  test("pre-fix shape (no probe dirs, minimal PATH, no local) still resolves to None") {
    val tmp = os.Path(java.nio.file.Files.createTempDirectory("nb-rg-none"))
    try
      val r = RgHelper.resolveRgPathFrom(
        pathEnv = launchdMinimalPath,
        probeDirs = Nil, // pre-fix shape: the leg did not exist
        localPath = (tmp / "no-home" / ".nebflow" / "bin" / rgName).toString,
        winInstall = None
      )
      assertEquals(r, None, "nothing anywhere must stay None (drives the not-found error path)")
    finally os.remove.all(tmp)
  }

  test("real host, launchd-minimal PATH: resolution lands on /opt/homebrew/bin/rg when present") {
    val probeDirs = List("/opt/homebrew/bin", "/usr/local/bin", "/snap/bin")
    val expected = probeDirs.map(d => java.nio.file.Path.of(d, rgName)).find(java.nio.file.Files.isRegularFile(_))
    assume(expected.isDefined, s"host has no package-manager rg under ${probeDirs.mkString(", ")} - drill not applicable here")
    val tmp = os.Path(java.nio.file.Files.createTempDirectory("nb-rg-host"))
    try
      val r = RgHelper.resolveRgPathFrom(
        pathEnv = launchdMinimalPath,
        probeDirs = probeDirs,
        localPath = (tmp / "no-home" / ".nebflow" / "bin" / rgName).toString, // nonexistent: isolate the probe leg
        winInstall = None
      )
      assertEquals(r, Some(expected.get.toAbsolutePath.toString))
    finally os.remove.all(tmp)
  }

  test("readWithLimit passes small streams through untruncated") {
    val (out, truncated) = RgHelper.readWithLimit(new ByteArrayInputStream("a\nb\nc".getBytes(utf8)))
    assertEquals(out, "a\nb\nc")
    assert(!truncated, "small stream must not be flagged truncated")
  }

  test("readWithLimit truncates at the 500KB cap instead of throwing") {
    val big = ("x" * 199 + "\n") * 5000 // ~1MB of 200B lines
    val (out, truncated) = RgHelper.readWithLimit(new ByteArrayInputStream(big.getBytes(utf8)))
    assert(truncated, "over-cap stream must report truncation")
    assert(out.getBytes(utf8).length <= 500 * 1024, "content stays within the cap")
    assert(out.linesIterator.length >= 2000, "a large prefix of lines is kept")
  }

  /** ~675KB of rg output: 1500 files under a 200-char dir, ~450B path lines each. */
  private def bigFixture(prefix: String): os.Path =
    val tmp = os.Path(java.nio.file.Files.createTempDirectory(prefix))
    val dir = tmp / ("d" * 200)
    (1 to 1500).foreach { i =>
      val pad = "f" * (250 - s"$i".length - 4)
      os.write(dir / s"${pad}$i.txt", "x", createFolders = true)
    }
    tmp

  test("runRg honors processCwd for relative '.' search") {
    val tmp = os.Path(java.nio.file.Files.createTempDirectory("nb-rghelper"))
    try
      os.write(tmp / "one.txt", "1", createFolders = true)
      os.write(tmp / "sub" / "two.txt", "2", createFolders = true)
      val r = RgHelper.runRg(
        List("--files", "--no-ignore", "--no-messages", "."),
        os.pwd.toString,
        processCwd = Some(tmp.toString)
      )
      r match
        case Right((stdout, _, code, truncated)) =>
          assertEquals(code, 0)
          assert(!truncated)
          val lines = stdout.trim.split("\\n").toList.map(_.stripPrefix("./")).sorted
          assertEquals(lines, List("one.txt", "sub/two.txt"), "relative search must list cwd-relative paths")
        case Left(err) => fail(s"unexpected ToolError: ${err.message}")
    finally os.remove.all(tmp)
  }

  test("runRg truncateOnOverflow=false keeps the hard 500KB ToolError (Grep behavior)") {
    val tmp = bigFixture("nb-rghelper-big")
    try
      val r = RgHelper.runRg(
        List("--files", "--glob", "/**", "--sort=modified", "--color=never", "--no-ignore", "--no-messages", "."),
        os.pwd.toString,
        processCwd = Some(tmp.toString),
        truncateOnOverflow = false
      )
      assert(r.isLeft, "Grep-mode overflow stays a hard error")
      assert(r.swap.toOption.get.message.contains("500KB"), s"${r.swap.toOption.get.message}")
    finally os.remove.all(tmp)
  }

  test("runRg truncateOnOverflow=true returns truncated stdout with the flag (Glob behavior)") {
    val tmp = bigFixture("nb-rghelper-trunc")
    try
      val r = RgHelper.runRg(
        List("--files", "--glob", "/**", "--sort=modified", "--color=never", "--no-ignore", "--no-messages", "."),
        os.pwd.toString,
        processCwd = Some(tmp.toString),
        truncateOnOverflow = true
      )
      r match
        case Right((stdout, _, code, truncated)) =>
          assert(truncated, "over-cap output must be flagged")
          assert(stdout.getBytes(utf8).length <= 500 * 1024 + 300, "stdout stays within cap (line-boundary tolerance)")
          assert(stdout.trim.split("\\n").length > 500, "a large prefix of sorted lines survives")
        case Left(err) => fail(s"unexpected ToolError: ${err.message}")
    finally os.remove.all(tmp)
  }

end RgHelperSpec
