package nebflow.core.dataroot

import java.nio.file.Files

import nebflow.core.AutoStartService

/**
 * 三层数据根批 · B1 第二腿 —— **验收 V6（autostart `--home`）的红证据 spec**。
 *
 * == 编号口径（勿混）==
 * 本文件用**派单验收编号 V6** = 「launchd plist 与 schtasks 命令行、desktop entry 三者渲染文本
 * 都含 `--home <账号根>`；平台不可用侧用纯渲染函数断言并注明」。契约里的「V3 迁移与 autostart」
 * 是**实现腿分组**，不是验收编号。
 *
 * == 断言原文（派单）==
 * 「V6（launchd plist 与 schtasks 命令行、desktop entry 三者渲染文本都含 `--home <账号根>`；
 * 平台不可用侧用纯渲染函数断言并注明）」
 *
 * == 契约权威（design-api.md §8.2 + §10 A12）==
 * - `programArguments(javaBin, jarPath, accountHome: Option[os.Path])`：有账号根时**尾部追加**
 *   `"--home", home.toString`；`None` ⇒ **逐字节等于今日输出**（老 plist / 未登录 autostart 不变，A12 后半）；
 * - `renderPlist` / `renderDesktopEntry` / `enableWindows` 的 `schtasks /tr` cmd 同步追加；
 *   `renderDesktopEntry` 的 `Exec=` 行同样是含 `--home` 的形态；
 * - 「**不加新旗标**」：`--home` 同时接受机器根与账号根，由阶梯第 0 档（`profile.json` 存在）判别（§8.2）——
 *   所以这里断言的就是作者裁定的「autostart 追加 `--home <账号根>`」逐字成立。
 *
 * == 两处实现腿需要照此落名的点（契约未冻结签名，写在这里而不是含糊过去）==
 * 1. `renderDesktopEntry`：契约 §8.2 要求它的 `Exec=` 行含 `--home`，但没给参数表。本 spec 按
 *    `programArguments` 的同款加参模式调用 `renderDesktopEntry(javaBin, jarPath, accountHome)`；
 *    实现腿若选别的形态（例如接 args），**回改本 spec 并同步 §8.2**，不要留两套口径。
 * 2. `renderWindowsTaskCmd`：契约只写了「`enableWindows` 的 `schtasks /tr` cmd 同步追加」，而今日
 *    该 cmd 是私有分支里的内联字符串 —— 派单要求「平台不可用侧用纯渲染函数断言」，故本 spec 按
 *    `renderPlist`/`renderDesktopEntry` 同款命名法断言一个**纯渲染**函数
 *    `renderWindowsTaskCmd(javaBin, jarPath, accountHome)`（`enableWindows` 改为调用它，行为不变）。
 *
 * == 平台不可用侧的注明 ==
 * 本机 = macOS：Windows 的 `schtasks` 与 Linux 的 `.desktop` 都**不可执行**，因此两侧只用**纯渲染函数**
 * 断言文本（不起进程、不碰注册表 / XDG 目录）；launchd 侧同样**只渲染不落盘**（不写
 * `~/Library/LaunchAgents`，不跑 `launchctl`）—— 本 spec 全程零副作用，不碰 `~/.stand`。
 */
class AutoStartHomeSpec extends munit.FunSuite:

  private val JavaBin = "/usr/bin/java"
  private val Jar = "/opt/stand/stand-assembly-2026.10.jar"
  private val BundleJar = "/Applications/stand.app/Contents/app/stand.jar"

  /** 今日形态（改造前）的程序参数 —— A12「None 分支逐字节等于今日」的基准。 */
  private val TodayJarArgs = List(
    JavaBin,
    "--add-opens",
    "java.base/java.lang=ALL-UNNAMED",
    "-jar",
    Jar,
    "start",
    "--no-browser"
  )

  private val TodayBundleArgs = List(
    "/Applications/stand.app/Contents/MacOS/stand",
    "start",
    "--no-browser"
  )

  /** 今日形态（改造前）的 `schtasks /tr` 命令串 —— `AutoStartService.enableWindows` 的内联形态。 */
  private val TodayWindowsCmd =
    s""""$JavaBin" --add-opens java.base/java.lang=ALL-UNNAMED -jar "$Jar" start --no-browser"""

  /** 今日形态（改造前）`.desktop` 的 Exec 行。 */
  private val TodayDesktopExec =
    s"Exec='$JavaBin' --add-opens java.base/java.lang=ALL-UNNAMED -jar '$Jar' start --no-browser"

  /** 样本账号根：派单指定的 /tmp/b1-red-*，用完即删（本 spec 只用其字符串形态，不往里写任何东西）。 */
  private def withAccountHome[T](f: os.Path => T): T =
    val root = os.Path(Files.createTempDirectory("b1-red-"))
    try f(root / "accounts" / "acct-a")
    finally os.remove.all(root)

  test("V6: programArguments 尾部追加 --home <账号根>（jar 形态与 bundle 形态），None 逐字节等于今日（A12）"):
    withAccountHome { accountHome =>
      // None ⇒ 逐字节今日形态：老 plist / 未登录 autostart 一个字节都不许变
      assertEquals(
        AutoStartService.programArguments(JavaBin, Jar, None),
        TodayJarArgs,
        "None 分支必须逐字节等于今日 jar 形态（A12 后半：AutoStartServiceSpec 的钉死断言不许改）"
      )
      assertEquals(
        AutoStartService.programArguments(JavaBin, BundleJar, None),
        TodayBundleArgs,
        "None 分支在 jpackage 捆绑形态下同样逐字节不变"
      )

      // Some ⇒ 尾部追加 ["--home", 账号根]
      val jarArgs = AutoStartService.programArguments(JavaBin, Jar, Some(accountHome))
      assertEquals(jarArgs.dropRight(2), TodayJarArgs, "有账号根时只在**尾部**追加，其它参数次序不动")
      assertEquals(jarArgs.takeRight(2), List("--home", accountHome.toString), "追加形态逐字：--home <账号根>")

      val bundleArgs = AutoStartService.programArguments(JavaBin, BundleJar, Some(accountHome))
      assertEquals(bundleArgs.dropRight(2), TodayBundleArgs, "bundle 分支同样只尾部追加")
      assertEquals(bundleArgs.takeRight(2), List("--home", accountHome.toString), "bundle 分支也带 --home（不得漏）")
    }

  test("V6: launchd plist 渲染文本含 --home <账号根>（只渲染不落盘、不跑 launchctl）"):
    withAccountHome { accountHome =>
      val args = AutoStartService.programArguments(JavaBin, Jar, Some(accountHome))
      val plist = AutoStartService.renderPlist(
        "com.stand.gateway",
        args,
        "/tmp/b1-red-autostart.log",
        "/tmp"
      )
      assert(plist.contains("<string>--home</string>"), s"plist 的 ProgramArguments 必须含 --home：$plist")
      assert(
        plist.contains(s"<string>$accountHome</string>"),
        s"plist 必须含账号根字面（可点路径）：$plist"
      )
      assert(plist.contains("<string>start</string>"), "程序参数其余部分仍完整")
      assert(plist.contains("<key>RunAtLoad</key>"), "plist 结构不得被这次改动破坏")

      // 负控：None 分支的 plist 不得出现 --home
      val nonePlist = AutoStartService.renderPlist(
        "com.stand.gateway",
        AutoStartService.programArguments(JavaBin, Jar, None),
        "/tmp/b1-red-autostart.log",
        "/tmp"
      )
      assert(!nonePlist.contains("--home"), s"None 分支的 plist 不得含 --home：$nonePlist")
    }

  test("V6: desktop entry 的 Exec= 行含 --home <账号根>（Linux 面纯渲染断言，本机不落盘）"):
    withAccountHome { accountHome =>
      val entry = AutoStartService.renderDesktopEntry(JavaBin, Jar, Some(accountHome))
      assert(entry.contains("--home"), s"Exec= 行必须含 --home（§8.2）：$entry")
      assert(entry.contains(accountHome.toString), s"Exec= 行必须含账号根字面：$entry")
      assert(entry.contains("X-GNOME-Autostart-enabled=true"), "desktop entry 结构不得被破坏")

      val noneEntry = AutoStartService.renderDesktopEntry(JavaBin, Jar, None)
      assert(
        noneEntry.contains(TodayDesktopExec),
        s"None 分支的 Exec 行必须逐字节等于今日：$noneEntry"
      )
      assert(!noneEntry.contains("--home"), s"None 分支不得含 --home：$noneEntry")
    }

  test("V6: schtasks 命令行含 --home <账号根>（Windows 面纯渲染断言；本机 = macOS，不可执行侧）"):
    withAccountHome { accountHome =>
      // 本机 macOS：schtasks/注册表不可达 ⇒ 只对纯渲染函数断言（派单「平台不可用侧用纯渲染函数断言」）
      assertEquals(
        AutoStartService.renderWindowsTaskCmd(JavaBin, Jar, None),
        TodayWindowsCmd,
        "None 分支的 /tr 命令串必须逐字节等于今日（A12 后半）"
      )
      val cmd = AutoStartService.renderWindowsTaskCmd(JavaBin, Jar, Some(accountHome))
      assert(cmd.contains("--home"), s"schtasks /tr 命令行必须含 --home：$cmd")
      assert(cmd.contains(accountHome.toString), s"schtasks /tr 命令行必须含账号根字面：$cmd")
      assert(cmd.contains("start"), "命令串主体不得被这次追加破坏")
    }

  test("缺陷④: Windows 计划任务走 XML 逐字存参——含空格的 java 路径不被 /tr 剥引号切开"):
    // 2026-11-21 跨机 E2E 实测（DESKTOP-V7EUCHT）：schtasks /create /tr 会把内层引号剥掉，
    // 于是 %ProgramFiles%\Eclipse Adoptium\…\java.exe 按空白切开 ⇒ 任务建不出来
    // （exit -2147467259）。断言 XML 面把整条命令行逐字面保留，且 Command 是全路径。
    val spacey = "C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.12.101-hotspot\\bin\\java.exe"
    val cmd = AutoStartService.renderWindowsTaskCmd(spacey, "C:\\stand-e2e\\bin\\stand-assembly-x.jar", None)
    val xml = AutoStartService.renderWindowsTaskXml(cmd)

    assert(xml.contains("<DisallowStartIfOnBatteries>false"), "电池闸必须显式关断（否则 /run 假成功不执行）")
    assert(xml.contains("<LogonTrigger>"), "autostart 语义 = 登录触发")
    assert(xml.contains("\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"), "Command 必须是全路径 PS")
    // 逐字面保参：路径与 jar 都在，且没有被切分成两个 XML 属性/节点
    assert(xml.contains(spacey), s"含空格的 java 路径必须逐字面保留：\n$xml")
    assert(xml.contains("stand-assembly-x.jar"), "jar 路径必须在命令行里")
    assert(!xml.contains("&lt;"), "命令串里没有需要转义的尖括号（负控：转义过度即可疑）")

  test("缺陷④: XML 参数的 XML 元字符被转义（引号/尖括号不得破标签）"):
    val cmd = AutoStartService.renderWindowsTaskCmd("/usr/bin/java", "/opt/a&b/<c>.jar", None)
    val xml = AutoStartService.renderWindowsTaskXml(cmd)
    assert(xml.contains("&amp;"), "& 必须转义")
    assert(!xml.contains("/<c>.jar"), "裸尖括号不得出现在文本节点里")
    assert(xml.contains("&quot;"), "命令行里的引号必须转成 &quot;")

  test("缺陷⑤: 自启命令必须钉死 --home 与 --port（隔离实例绝不能被默默指回默认根）"):
    // 2026-11-21 跨机 E2E 实测：隔离实例执行 `stand autostart enable` 时，
    // accountHomeIfAny 在平铺/未登录档返回 None ⇒ 计划任务命令行**不带 --home**，
    // 登录自启于是静默去起默认数据根（author 的机器上恰好没有在跑实例，否则就是
    // 互踢）。端口同理：不带 --port 就会被评成品牌缺省 6138 = 生产口。
    // 现约束：自启一律钉 machineRoot 与解析出的端口；本 spec 断言纯渲染面确实把
    // 这两个事实写进了命令行（三个平台腿同一条纪律）。
    val home = os.Path("/tmp/e2e-isolated-home")
    val winCmd = AutoStartService.renderWindowsTaskCmd("/usr/bin/java", "/opt/a.jar", Some(home), Some(6139))
    assert(winCmd.contains("--home \"/tmp/e2e-isolated-home\""), s"Windows 自启必须带 --home：$winCmd")
    assert(winCmd.contains("--port 6139"), s"Windows 自启必须带 --port：$winCmd")

    val args = AutoStartService.programArguments("/usr/bin/java", "/opt/a.jar", Some(home), Some(6139))
    assert(args.contains("/tmp/e2e-isolated-home"), s"macOS plist 参数必须含 home：$args")
    assert(args.sliding(2).exists(w => w == List("--port", "6139")), s"macOS plist 参数必须含 --port 6139：$args")

    val desktop = AutoStartService.renderDesktopEntry("/usr/bin/java", "/opt/a.jar", Some(home), Some(6139))
    assert(desktop.contains("--home '/tmp/e2e-isolated-home'"), s"Linux Exec 行必须带 --home：$desktop")
    assert(desktop.contains("--port 6139"), s"Linux Exec 行必须带 --port：$desktop")

    // 负控：默认根（与今日字节相同）仍不带 --port 覆盖时不得凭空多出该旗标
    assert(!AutoStartService.renderDesktopEntry("/usr/bin/java", "/opt/a.jar", None).contains("--port"))
    assert(!AutoStartService.renderWindowsTaskCmd("/usr/bin/java", "/opt/a.jar", None).contains("--port"))

end AutoStartHomeSpec
