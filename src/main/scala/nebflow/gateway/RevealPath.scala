/* canvas-reveal-in-finder 批（2026-11-19；作者令逐字 19:39「电脑端的canvas标签
 * 页右键，多一个选项，在访达/文件浏览器打开。」）
 *
 * 本对象 = 「在系统文件管理器里定位这个文件」的**唯一实现点**：平台判定 + 打开器
 * 解析 + argv 构造 + 启动 + 路径判据。同族先例 = [[ProjectOpenApps]]（本仓既有的
 * 「本地动作」通道：shell-free 的 `ProcessBuilder(argv*)` + realpath 判据 + 结构化
 * 拒绝），结构照抄、不新造协议族。
 *
 * 纪律（逐条对应任务书）：
 *   ① 平台由**引擎**判定（`os.name`）——前端不猜 `navigator.platform`；
 *   ② **全 argv，零 shell 拼接**：文件路径永远是单独一个参数，不进任何命令行
 *      字符串，故路径里的空格/引号/`;`/`$(…)` 都只是文件名字符；
 *   ③ 判据在 **realpath 之后**：macOS `/var` 与 `/private/var` 是同一文件（本仓
 *      已踩过），所以执行与回报一律用 `toRealPath` 的结果（`nfFileVerdict` 阶梯内
 *      已完成，本文件**不做任何词法字符串比较**，也就不可能比错前缀）；
 *   ④ 必须是本地**真实存在的常规文件**：目录、悬空链接、设备/管道一律拒绝。
 *
 * 判据来源 = `/api/nf-file` 的**同一份**阶梯（`NfFilePolicy.nfFileVerdict`：词法
 * 归一 → 存在 ∧ 常规文件 → toRealPath → R2 凭据 inode → 凭据身份）。本仓既有裁定
 * 「判据的形状只有一份」——新增第二套判据就会与取字节面分叉。
 */
package nebflow.gateway

import cats.effect.IO
import java.nio.file.{Files, Path, Paths}
import org.http4s.Status

private[gateway] object RevealPath:

  import NfFilePolicy.{NfPathPolicy, NfVerdict}

  /**
   * 平台 → (引擎侧 id, 前端挑文案的 `target`)。
   * `target` 是**菜单归属**而不是可执行件名：前端只按它选 i18n 键（finder /
   * explorer / file-manager），打开器本身仍由本对象解析。
   */
  enum Platform(val id: String, val target: String):
    case MacOS extends Platform("macos", "finder")
    case Windows extends Platform("windows", "explorer")
    case Linux extends Platform("linux", "file-manager")

  /**
   * `os.name` → 平台。mac/win 之外的 POSIX（linux/bsd/其他）一律按 Linux 族走
   *  `xdg-open`；连打开器都没有时 [[opener]] 返回 None ⇒ 能力面 `supported:false`
   *  （前端于是不渲染该项，绝不乱执行）。
   */
  def platformFrom(osName: String): Option[Platform] =
    val os = Option(osName).getOrElse("").toLowerCase(java.util.Locale.ROOT)
    if os.contains("mac") || os.contains("darwin") then Some(Platform.MacOS)
    else if os.contains("win") then Some(Platform.Windows)
    else if os.isEmpty then None
    else Some(Platform.Linux)

  def platform(): Option[Platform] = platformFrom(sys.props.getOrElse("os.name", ""))

  /** PATH 上第一个可执行件（同 [[ProjectOpenApps]] 的解析口径）。 */
  private def onPath(name: String): Option[String] =
    sys.env
      .getOrElse("PATH", "")
      .split(java.io.File.pathSeparator)
      .iterator
      .filter(_.nonEmpty)
      .map(p => Paths.get(p, name).toString)
      .find(p => Files.isExecutable(Paths.get(p)))

  /** 打开器可执行件（唯一一处解析；None = 本机没有这个平台的打开器）。 */
  def opener(p: Platform): Option[String] =
    p match
      case Platform.MacOS =>
        Option
          .when(Files.isExecutable(Paths.get("/usr/bin/open")))("/usr/bin/open")
          .orElse(onPath("open"))
      // Windows：`explorer.exe` 由 ProcessBuilder 经 PATH / System32 解析（与
      // GatewayMain 的 `rundll32` 先例同法：裸名，不硬编码盘符路径）。
      case Platform.Windows => Some("explorer.exe")
      case Platform.Linux => onPath("xdg-open")

  /**
   * argv 构造（**路径 = 单独 token**，零 shell）：
   *
   *  - macOS   `open -R <file>` —— `-R` = 在访达中**定位并选中**该文件；
   *  - Windows `explorer.exe /select, <file>` —— 两个 token。**为什么不写成单个
   *    `/select,"<path>"`**：ProcessBuilder 见到空格会把**整个** token 再包一层
   *    引号 ⇒ `"/select,"C:\a b\f.txt""`，explorer 解析错位；拆成 `/select,` +
   *    路径两参数是 Java 侧既定解法，路径含空格也安全（任务书给的是 cmd 形态，
   *    这里给的是 argv 形态，语义相同：选中该文件）；
   *  - Linux   `xdg-open <父目录>` —— xdg-open 没有「选中」语义，故打开其父目录
   *    （任务书 Phase 明确「打开父目录」）。
   */
  def argvFor(p: Platform, real: Path): Option[List[String]] =
    opener(p).flatMap(bin => argvWith(bin, p, real))

  /**
   * [[argvFor]] 的纯半边（打开器已解析）：spec 直接喂合成打开器验三档 argv 形态，
   *  生产侧仍只经 [[opener]] 取真件 —— 一个判据、一个接缝，不复制标量表。
   */
  def argvWith(bin: String, p: Platform, real: Path): Option[List[String]] =
    p match
      case Platform.MacOS => Some(List(bin, "-R", real.toString))
      case Platform.Windows => Some(List(bin, "/select,", real.toString))
      case Platform.Linux => Option(real.getParent).map(parent => List(bin, parent.toString))

  /** 结构化拒绝：`code` = 机器读面（稳定），`message` = 人读面（可带原判据原文）。 */
  final case class Refusal(status: Status, code: String, message: String)

  private def mapDenied(d: NfVerdict.Denied): Refusal =
    d.reason match
      // 凭据两族对外只露一个 code（前端只区分「不存在 / 不许 / 不是文件」三档），
      // 原始判据原文原样带进 message，诊断面不丢信息。
      case "credential-path" | "credential-hardlink" => Refusal(d.status, "path-refused", d.message)
      case other => Refusal(d.status, other, d.message)

  /**
   * 路径判据 = `/api/nf-file` 的**同一份阶梯**（零复制、零旁路，见文件头③）。
   * 阶梯之后补最后一步：**realpath 上**再确认一次常规文件 —— 词法检查与
   * `toRealPath` 之间可能被替换，而「链接指向目录」这类形态也只在这一步可见。
   */
  def resolvePath(rawPath: String): IO[Either[Refusal, Path]] =
    NfFilePolicy.nfFileVerdict(rawPath, NfPathPolicy.current()).map {
      case NfVerdict.Allowed(real, _) =>
        if Files.isRegularFile(real) then Right(real)
        else Left(Refusal(Status.Conflict, "not-a-file", s"not a regular file: $real"))
      case denied: NfVerdict.Denied => Left(mapDenied(denied))
    }

  /** 执行（argv、无 shell、无 stdin）。返回打开器退出码。 */
  def launch(argv: List[String]): IO[Int] =
    IO.blocking {
      val proc = new ProcessBuilder(argv*)
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .start()
      proc.waitFor()
    }

end RevealPath
