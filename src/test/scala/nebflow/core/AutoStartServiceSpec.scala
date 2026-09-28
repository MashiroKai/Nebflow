package nebflow.core

import munit.FunSuite

class AutoStartServiceSpec extends FunSuite:

  test("isBundledApp detects jpackage app-bundle jar layout") {
    assert(AutoStartService.isBundledApp("/Applications/Nebflow.app/Contents/app/Nebflow.jar"))
    assert(AutoStartService.isBundledApp("/Users/x/Applications/Nebflow.app/Contents/app/Nebflow.jar"))
    assert(!AutoStartService.isBundledApp("/opt/nebflow/Nebflow.jar"))
    assert(!AutoStartService.isBundledApp("~/Downloads/nebflow.jar"))
  }

  test("bundleExecutable derives Contents/MacOS path from .app root") {
    assertEquals(
      AutoStartService.bundleExecutable("/Applications/Nebflow.app/Contents/app/Nebflow.jar"),
      Some("/Applications/Nebflow.app/Contents/MacOS/Nebflow")
    )
    assertEquals(
      AutoStartService.bundleExecutable("/Users/x/Apps/My Flow.app/Contents/app/My Flow.jar"),
      Some("/Users/x/Apps/My Flow.app/Contents/MacOS/My Flow")
    )
    assertEquals(AutoStartService.bundleExecutable("/opt/nebflow/Nebflow.jar"), None)
  }

  test("programArguments uses bundle executable without java -jar wrapper for bundled apps") {
    val args = AutoStartService.programArguments("/usr/bin/java", "/Applications/Nebflow.app/Contents/app/Nebflow.jar")
    assertEquals(args, List("/Applications/Nebflow.app/Contents/MacOS/Nebflow", "start", "--no-browser"))
  }

  test("programArguments keeps classic java -jar template for plain jar form") {
    val args = AutoStartService.programArguments("/usr/bin/java", "/opt/nebflow/Nebflow.jar")
    assertEquals(
      args,
      List(
        "/usr/bin/java",
        "--add-opens",
        "java.base/java.lang=ALL-UNNAMED",
        "-jar",
        "/opt/nebflow/Nebflow.jar",
        "start",
        "--no-browser"
      )
    )
  }

  test("renderPlist embeds label, all program arguments and paths") {
    val args = List("/usr/bin/java", "-jar", "/opt/neb flow/Nebflow.jar")
    val plist = AutoStartService.renderPlist("com.nebflow.gateway", args, "/tmp/auto.log", "/Users/x")
    assert(plist.contains("<string>com.nebflow.gateway</string>"))
    assert(plist.contains("<string>/usr/bin/java</string>"))
    // path with a space is embedded verbatim inside one <string> element
    assert(plist.contains("<string>/opt/neb flow/Nebflow.jar</string>"))
    assert(plist.contains("<string>/tmp/auto.log</string>"))
    assert(plist.contains("<string>/Users/x</string>"))
    assert(plist.contains("<key>RunAtLoad</key>"))
  }

  test("renderPlist escapes XML special characters") {
    val plist = AutoStartService.renderPlist("a&b<c>", List("\"quoted\""), "/tmp/x", "/tmp")
    assert(plist.contains("<string>a&amp;b&lt;c&gt;</string>"))
    assert(plist.contains("<string>&quot;quoted&quot;</string>"))
  }

  test("renderDesktopEntry quotes java and jar paths in Exec") {
    val entry = AutoStartService.renderDesktopEntry("/usr/bin/java", "/opt/neb flow/Nebflow.jar")
    assert(entry.contains("Exec='/usr/bin/java' --add-opens java.base/java.lang=ALL-UNNAMED -jar '/opt/neb flow/Nebflow.jar' start --no-browser"))
    assert(entry.contains("X-GNOME-Autostart-enabled=true"))
  }

end AutoStartServiceSpec
