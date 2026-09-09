package nebflow.core

import io.circe.JsonObject
import io.circe.syntax.*
import munit.FunSuite

class PathUtilSpec extends FunSuite:

  private val home = sys.props.getOrElse("user.home", "~")

  // ===== expandTilde =====

  test("expandTilde: ~/x expands to home + /x") {
    assertEquals(PathUtil.expandTilde("~/Desktop/test.txt"), s"$home/Desktop/test.txt")
  }

  test("expandTilde: bare ~ expands to home") {
    assertEquals(PathUtil.expandTilde("~"), home)
  }

  test("expandTilde: absolute unix path unchanged") {
    assertEquals(PathUtil.expandTilde("/usr/local/bin"), "/usr/local/bin")
  }

  test("expandTilde: windows drive path unchanged") {
    assertEquals(PathUtil.expandTilde("C:\\Users\\kai\\Desktop"), "C:\\Users\\kai\\Desktop")
  }

  test("expandTilde: ~ inside path (not prefix) unchanged") {
    // only a leading ~ should expand
    assertEquals(PathUtil.expandTilde("/tmp/~backup"), "/tmp/~backup")
  }

  // ===== expandPathParams =====

  test("expandPathParams: expands file_path with ~") {
    val params = JsonObject("file_path" -> "~/Desktop/test.txt".asJson, "content" -> "hello".asJson)
    val out = PathUtil.expandPathParams(params)
    assertEquals(out("file_path").flatMap(_.asString).getOrElse(""), s"$home/Desktop/test.txt")
    assertEquals(out("content").flatMap(_.asString).getOrElse(""), "hello") // untouched
  }

  test("expandPathParams: expands path key with ~") {
    val params = JsonObject("path" -> "~/projects".asJson, "pattern" -> "**/*.scala".asJson)
    val out = PathUtil.expandPathParams(params)
    assertEquals(out("path").flatMap(_.asString).getOrElse(""), s"$home/projects")
    assertEquals(out("pattern").flatMap(_.asString).getOrElse(""), "**/*.scala") // untouched
  }

  test("expandPathParams: absolute file_path unchanged") {
    val params = JsonObject("file_path" -> "/etc/hosts".asJson)
    val out = PathUtil.expandPathParams(params)
    assertEquals(out("file_path").flatMap(_.asString).getOrElse(""), "/etc/hosts")
  }

  test("expandPathParams: windows path with ~ expands to local home") {
    // On a Windows receiver, user.home is C:\Users\...; the expansion uses the
    // receiving JVM's home regardless of the path style.
    val params = JsonObject("file_path" -> "~/Desktop/win.txt".asJson)
    val out = PathUtil.expandPathParams(params)
    assertEquals(out("file_path").flatMap(_.asString).getOrElse(""), s"$home/Desktop/win.txt")
  }

  test("expandPathParams: does not touch command (Bash owns ~ expansion)") {
    val params = JsonObject("command" -> "echo ~/foo".asJson)
    val out = PathUtil.expandPathParams(params)
    assertEquals(out("command").flatMap(_.asString).getOrElse(""), "echo ~/foo")
  }

  test("expandPathParams: empty params returns empty") {
    assertEquals(PathUtil.expandPathParams(JsonObject.empty), JsonObject.empty)
  }

  // ===== isAbsolute (regression: unchanged by this fix) =====

  test("isAbsolute still rejects ~ (receiver must expand first)") {
    assertEquals(PathUtil.isAbsolute("~/Desktop/x"), false)
    assertEquals(PathUtil.isAbsolute("/abs/path"), true)
    assertEquals(PathUtil.isAbsolute("C:\\Users\\x"), true)
  }

  // ===== isAbsolute: dual-platform semantics matrix =====
  // Regression gate for the KAI file-browser fix (diag-win-paths): pop.readFile
  // used naive `startsWith("/")`, rejecting every Windows path form. These are
  // pure string checks — identical results on any host OS.

  test("isAbsolute: POSIX absolute paths accepted") {
    assertEquals(PathUtil.isAbsolute("/Users/kai/file.txt"), true)
    assertEquals(PathUtil.isAbsolute("/tmp/output.svg"), true)
    assertEquals(PathUtil.isAbsolute("/"), true)
  }

  test("isAbsolute: Windows drive-letter paths accepted (both separators)") {
    assertEquals(PathUtil.isAbsolute("C:\\Users\\Kai\\doc.txt"), true)
    assertEquals(PathUtil.isAbsolute("C:/Users/Kai/doc.txt"), true)
    assertEquals(PathUtil.isAbsolute("D:\\data"), true)
    assertEquals(PathUtil.isAbsolute("Z:"), true) // bare drive root
  }

  test("isAbsolute: UNC paths accepted") {
    assertEquals(PathUtil.isAbsolute("\\\\server\\share\\file.txt"), true)
    assertEquals(PathUtil.isAbsolute("\\\\192.168.1.10\\public"), true)
  }

  test("isAbsolute: mixed-separator drive paths accepted") {
    assertEquals(PathUtil.isAbsolute("C:\\Users\\Kai/Desktop/x"), true)
  }

  test("isAbsolute: relative paths rejected") {
    assertEquals(PathUtil.isAbsolute("foo/bar.txt"), false)
    assertEquals(PathUtil.isAbsolute("./x"), false)
    assertEquals(PathUtil.isAbsolute("..\\x"), false)
    assertEquals(PathUtil.isAbsolute(""), false)
  }

  test("isAbsolute: drive-like edge cases") {
    assertEquals(PathUtil.isAbsolute("C"), false)  // single char, no colon
    assertEquals(PathUtil.isAbsolute("CX"), false) // colon check is position 1 only
    assertEquals(PathUtil.isAbsolute(":C"), false)
  }

  test("isAbsolute: tilde forms are not absolute (must expand first)") {
    assertEquals(PathUtil.isAbsolute("~"), false)
    assertEquals(PathUtil.isAbsolute("~/x"), false)
    assertEquals(PathUtil.isAbsolute("~\\x"), false)
  }

  // ===== expandTilde: Windows backslash form (~\) =====

  test("expandTilde: ~\\x expands to home + suffix (kept verbatim)") {
    // On a Windows JVM home is C:\Users\name so this yields the native path;
    // on POSIX the backslash stays in the segment and the path simply won't
    // exist — same failure class as before, never a wrong-file read.
    assertEquals(PathUtil.expandTilde("~\\Desktop\\x.txt"), s"$home\\Desktop\\x.txt")
  }

  test("expandTilde: ~\\ not at position 0 unchanged") {
    assertEquals(PathUtil.expandTilde("C:\\Users\\~\\x"), "C:\\Users\\~\\x")
  }

  // ===== resolvePath: POSIX anchor (host-OS independent branch) =====

  test("resolvePath: POSIX absolute string resolves to itself, pwd-independent") {
    assertEquals(PathUtil.resolvePath("/tmp/nebflow-pathtest-anchor").toString, "/tmp/nebflow-pathtest-anchor")
  }

end PathUtilSpec
