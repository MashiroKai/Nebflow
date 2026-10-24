package nebflow.core.project

import scala.util.Try

/**
 * 挂载预检（跨设备项目挂载批 2026-10-25 · 现场事故必修）。
 *
 * 两条铁律（作者令 2026-10-25 00:30 逐字）：
 *   1. **workspace 必须在声明设备上真实存在才准挂**；
 *   2. 不存在 ⇒ 结构化报错（**指明设备与路径**），绝不静默造骨架。
 *
 * 事故形态（2026-10-24 现场验证）：用设备 KAI 上的真实路径 `C:\Users\Kai\stand`
 * 在 Mac 引擎上走本机挂载腿——挂载面不校验存在性，`os.Path` 把 Windows 风格路径
 * 当**相对名**解析进 Mac 数据根，`ensureScaffold` 在 `~/.stand/C:\Users\Kai\stand/`
 * 静默造出垃圾骨架并「假挂载成功」。本对象是这类缺口的**唯一闸门**：
 *
 *   - [[checkLocalWorkspace]] = 本机腿预检（同步、**零写入**——纯 stat，不建目录）；
 *     调用点必须在任何磁盘写之前（含 project.json 定义本身）。
 *   - 设备腿的 stat 走设备通道（`RemoteExecutor.transferListScoped`，编排点在
 *     [[DeviceProjectMount]]），但报错文案的单点拼装也在这里（[[deviceRefusal]]），
 *     保证「指明设备与路径」的口径两侧一致。
 *
 * 纯函数 + 真实 stat 的最小面：spec 以临时 dataRoot 直测（零网络、零引擎）。
 */
object ProjectMountPreflight:

  /** Windows 风格绝对路径判据（`C:\...` / `C:/...` / `\\server\...` UNC）。
    * 这类路径出现在本机腿 = 「声明设备」与「实际走腿」错位的强信号（事故原形）。 */
  def windowsStylePath(p: String): Boolean =
    p.matches("""(?i)^[a-z]:[\\/].*""") || p.startsWith("""\\""")

  /**
   * 本机腿预检。返回 None = 通过（存在且是目录，准挂）；Some(错误文案) = 结构化
   * 拒绝——文案含：原始声明路径、**本机解析结果**（让路径错觉可见）、补建指引
   * （先建目录再挂 / 设备路径应带 device 参数走设备腿）。
   *
   * 🔴 本函数绝不创建任何文件/目录（os.exists/os.isDir 只读）——「绝不静默造
   * 骨架」的闸门语义由调用点把本检查放在一切写盘之前来兑现。
   */
  def checkLocalWorkspace(workspace: String, base: os.Path): Option[String] =
    val resolved = Try(os.Path(workspace, base)).toOption
    val existsIsDir = resolved.exists(p => os.exists(p) && os.isDir(p))
    if existsIsDir then None
    else Some(refusalMessage(workspace, resolved))

  /** 结构化拒绝文案（本机腿）。指明：声明路径、解析落点、两条出路。 */
  def refusalMessage(workspace: String, resolved: Option[os.Path]): String =
    val resolvedLine = resolved.fold("path could not be resolved")(p => s"it resolves to '$p' on the engine host")
    val deviceHint =
      if windowsStylePath(workspace) then
        s" The path looks like another OS's absolute path — if it lives on a paired device, mount with the device declared (device=<name>) so the existence check runs on THAT device via the device channel."
      else ""
    s"Mount refused: workspace '$workspace' does not exist as a directory on the engine host " +
      s"($resolvedLine). Mounting never creates a workspace — create the directory first " +
      "(e.g. mkdir, or the picker's new-folder action), then retry.$deviceHint"

  /**
   * 设备腿结构化拒绝文案（单点拼装，与 [[checkLocalWorkspace]] 同口径）：
   * 指明**设备与路径**（铁律 2 的字面要求）。`detail` = 设备通道的原错误
   * （不在线 / 不可达 / 非目录），原样带上保证可判读。
   */
  def deviceRefusal(device: String, path: String, detail: String): String =
    s"Mount refused: workspace '$path' could not be verified on device '$device' — $detail. " +
      "The workspace must really exist on the declared device before it can be mounted " +
      "(mounting never creates it, locally or remotely); create the directory on that device, " +
      "or mount a different path/device."

end ProjectMountPreflight
