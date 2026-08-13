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

end PathUtilSpec
