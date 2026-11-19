package nebflow.core

import munit.FunSuite

import java.io.File

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
    assert(
      entry.contains(
        "Exec='/usr/bin/java' --add-opens java.base/java.lang=ALL-UNNAMED -jar '/opt/neb flow/Nebflow.jar' start --no-browser"
      )
    )
    assert(entry.contains("X-GNOME-Autostart-enabled=true"))
  }

  // ── 打包态判定（重启窗热修批 2026-10-18：jar 名品牌派生）─────────────

  test("isDistributionJar accepts current-brand and legacy spellings, rejects foreign jars") {
    // 现行品牌产物（stand-assembly-<v>.jar）——热修前 contains("nebflow") 恒 miss
    assert(AutoStartService.isDistributionJar("/opt/stand/stand-assembly-2026.9.20.jar"))
    assert(AutoStartService.isDistributionJar("/opt/stand/STAND-ASSEMBLY-2026.9.20.JAR")) // case-insensitive
    // 上一代拼写的存量 checkout 仍然合法（installer dual-glob 同判据）
    assert(AutoStartService.isDistributionJar("/opt/nebflow/nebflow-assembly-1.4.1.jar"))
    // jpackage app bundle 内的 jar 恒可（布局判据，与名字无关）
    assert(AutoStartService.isDistributionJar("/Applications/stand.app/Contents/app/stand.jar"))
    // 非 distribution jar（sbt run 的 scala-library 等）必须被滤掉
    assert(!AutoStartService.isDistributionJar("/repo/target/scala-3.5.2/scala-library.jar"))
    assert(!AutoStartService.isDistributionJar("/tmp/random-thing.jar"))
  }

  test("RestartHelper.resolveJarPathFrom prefers the brand jar over a generic classpath jar") {
    // 现行品牌 fat jar 在场 ⇒ 选它（而非首个 .jar）
    assertEquals(
      RestartHelper.resolveJarPathFrom(
        s"/repo/lib/scala-library.jar${File.pathSeparator}/opt/stand/stand-assembly-2026.9.20.jar"
      ),
      Some("/opt/stand/stand-assembly-2026.9.20.jar")
    )
    // legacy 拼写同样被识别（双读）
    assertEquals(
      RestartHelper.resolveJarPathFrom(
        s"/repo/lib/scala-library.jar${File.pathSeparator}/opt/nebflow/nebflow-assembly-1.4.1.jar"
      ),
      Some("/opt/nebflow/nebflow-assembly-1.4.1.jar")
    )
    // 无品牌 jar ⇒ generic 回落（首个 .jar）——resolveRunJar 的过滤层兜底
    assertEquals(
      RestartHelper.resolveJarPathFrom("/repo/lib/scala-library.jar"),
      Some("/repo/lib/scala-library.jar")
    )
    // 空 classpath ⇒ None
    assertEquals(RestartHelper.resolveJarPathFrom(""), None)
  }

end AutoStartServiceSpec
