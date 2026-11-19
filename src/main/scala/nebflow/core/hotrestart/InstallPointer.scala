package nebflow.core.hotrestart

import cats.effect.IO
import cats.syntax.all.*
import nebflow.shared.Branding
import nebflow.shared.NebflowLogger

/**
 * 安装目录的**版本留存 + 指针**布局（hotupdate 批 2 G4 · 设计 §6②）。
 *
 * 盘上布局（本设计**唯一必须新增**的盘上机制）：
 * {{{
 *   <installDir>/current-version                 # 指针：当前版本（启动器以此为准）
 *   <installDir>/previous-version                # 指针：上一版（回滚目标）
 *   <installDir>/versions/<version>/<lowerName>-assembly-<version>.jar  # 每版一目录，不可变
 *   <installDir>/nebflow                         # 启动器（既有件，改为指针优先）
 * }}}
 *
 * **取舍（申报：指针 vs 移出扫描面）**：选**显式指针**，不作为「把坏版移出扫描面」
 * 的替代方案——理由：既有启动器用数值排序取**最新**（`release/install.sh` 的
 * WINSORT 块 + `_winsort_pick_newest`），坏版在场必然仍被选中；靠「把坏版搬走」只
 * 修一次实例，不修这一类（任何将来落回扫描面的散包会再次胜出）。指针把**选取判据**
 * 从「目录里谁最号大」改成「盘上声明谁在役」，坏版留在盘上（回滚基座 + 取证）却
 * 天然对选取面不可见。代价 = 启动器多一段指针解析（已按「无指针 ⇒ 逐字节回落到
 * 既有 WINSORT 扫描」实现，legacy 安装零回归）。
 *
 * 读侧与写侧都在这里：安装脚本写指针（shell 侧，`release/install.sh`），运行时读指针
 * （本对象）——**同一份文件**，不做第二套版本记录。
 */
object InstallPointer:

  private val logger = NebflowLogger.forName("nebflow.hotrestart")

  val CurrentFileName = "current-version"
  val PreviousFileName = "previous-version"
  val VersionsDirName = "versions"

  /**
   * 生产安装目录（台账 #916 品牌修正 2026-10-26：`$HOME/<homeDirName>/bin`，
   * 当前品牌 = `~/.stand/bin`——与部署面 launcher（`ops/bin/stand`、stand-jar-pull、
   * release/install.sh `INSTALL_DIR:-${STAND_BASE}/bin`）同一真值来源；
   * `INSTALL_DIR` env 覆写保留。此前硬编码 `.nebflow/bin` 是 rebrand 前旧账：
   * stand 品牌安装上，健康自检「期望版本」读到空指针、G4 指针回滚切的是
   * launcher 永远不读的一份孤儿指针文件——面子上全绿，盘上无效。
   */
  def defaultInstallDir(
    env: Map[String, String] = sys.env,
    home: String = sys.props.getOrElse("user.home", "")
  ): os.Path =
    val fromEnv = env.get("INSTALL_DIR").map(_.trim).filter(_.nonEmpty)
    os.Path(fromEnv.getOrElse(s"$home/${Branding.homeDirName}/bin"))

  def currentPath(dir: os.Path): os.Path = dir / CurrentFileName
  def previousPath(dir: os.Path): os.Path = dir / PreviousFileName
  def versionsDir(dir: os.Path): os.Path = dir / VersionsDirName
  def versionDir(dir: os.Path, version: String): os.Path = versionsDir(dir) / version

  /**
   * 安装包名（与 `release/install.sh:download_jar` 的 `JAR_NAME` 契约逐字同构：
   * `${LOWER_NAME}-assembly-${VERSION}.jar`，两侧同源自 brand.conf `lowerName`）。
   * 重启窗热修批 2026-10-18：此前裸拼 `nebflow-assembly-`，stand 品牌产物
   * `stand-assembly-*.jar` 下回滚直取恒 miss。
   */
  def jarName(version: String): String = s"${Branding.lowerName}-assembly-$version.jar"

  /** 现行品牌拼装的包名前缀（读侧 glob 用；与 [[jarName]] 同源）。 */
  private def jarPrefix: String = s"${Branding.lowerName}-assembly-"

  /** 上一代品牌拼装的包名前缀（品牌迁移前落盘的存量留存包；legacy 字面 = 兼容层）。 */
  private val LegacyJarPrefix = "nebflow-assembly-"

  private def readTrimmed(path: os.Path): IO[Option[String]] =
    IO.blocking(if os.exists(path) then Some(os.read(path).trim) else None)
      .map(_.filter(_.nonEmpty))
      .handleErrorWith(e => IO(logger.warn(s"[hotupdate] pointer read failed ${path.toString}: ${msg(e)}")).as(None))

  /** 现读指针（None = 无指针 / 空指针 ⇒ 调用方走 legacy 回退或报 `unverified`）。 */
  def readCurrent(dir: os.Path): IO[Option[String]] = readTrimmed(currentPath(dir))
  def readPrevious(dir: os.Path): IO[Option[String]] = readTrimmed(previousPath(dir))

  /** 现读当前版本（第三/四档「期望版本」的**真值来源**；缺失 ⇒ None，绝不猜）。 */
  def expectedVersion(dir: os.Path): IO[Option[String]] = readCurrent(dir)

  private def writeTrimmed(path: os.Path, value: String): IO[Unit] =
    IO.blocking {
      os.makeDir.all(path / os.up)
      // 临时文件 + rename（安装脚本与运行时进程可能同时在场 ⇒ 读侧永不见半行指针）
      val tmp = path / os.up / s".${path.last}.tmp"
      os.write.over(tmp, value.trim + "\n", createFolders = true)
      os.move(tmp, path, replaceExisting = true)
    }

  /** 指针切换（**回滚动作的承重件**：切回上一版即回滚；切完即「坏版出选取面」）。 */
  def flip(dir: os.Path): IO[Either[String, (String, String)]] =
    readCurrent(dir)
      .flatMap { cur =>
        readPrevious(dir).flatMap { prev =>
          (cur, prev) match
            case (Some(c), Some(p)) =>
              writeTrimmed(currentPath(dir), p) *>
                writeTrimmed(previousPath(dir), c) *>
                IO.pure(Right((p, c)))
            case (None, _) =>
              IO.pure(Left(s"no current-version pointer at ${currentPath(dir).toString} — nothing to roll back from"))
            case (_, None) =>
              IO.pure(
                Left(s"no previous-version pointer at ${previousPath(dir).toString} — no rollback target on disk")
              )
        }
      }
      .handleErrorWith(e => IO.pure(Left(s"pointer flip failed: ${msg(e)}")))

  /**
   * 指针晋升（**two-phase update Phase2 的换装动作**，台账 #916）：current ←
   * version、previous ← 旧 current，双文件各自原子写（[[writeTrimmed]] tmp+
   * rename）。前置：version 的包必须在盘（versions/<v>/ 内可解析出 jar）——
   * 晋升一个无包版本等于把下一次开机指向空气，Phase1 staging 是本动作的合同
   * 前置，不满足即 Left 且**双指针零改动**。首个版本（无 current 指针）也合法：
   * previous 写 None 语义 = 清掉旧指针文件。
   */
  def promote(dir: os.Path, version: String): IO[Either[String, (String, String)]] =
    if !safeVersionLiteral(version) then
      IO.pure(Left(s"invalid version literal: $version"))
    else
      jarIn(dir, version).flatMap {
        case Left(err) => IO.pure(Left(s"refusing to promote: $err"))
        case Right(_) =>
          readCurrent(dir).flatMap { cur =>
            writeTrimmed(currentPath(dir), version) *>
              (cur match
                case Some(c) => writeTrimmed(previousPath(dir), c)
                case None =>
                  IO.blocking(if os.exists(previousPath(dir)) then os.remove(previousPath(dir)))
              ) *>
              IO.pure(Right((version, cur.getOrElse(""))))
          }.handleErrorWith(e => IO.pure(Left(s"pointer promote failed: ${msg(e)}")))
      }

  /** 指针回写（更新注册失败时的复原腿：把 promote 前的双指针原样放回）。 */
  def writePointers(dir: os.Path, current: String, previous: Option[String]): IO[Unit] =
    writeTrimmed(currentPath(dir), current) *>
      (previous match
        case Some(p) => writeTrimmed(previousPath(dir), p)
        case None    => IO.blocking(if os.exists(previousPath(dir)) then os.remove(previousPath(dir)))
      )

  /** 注入脚本/指针的版本字面字符闸（与 launcher WINSORT 的可解析形状一致）。 */
  def safeVersionLiteral(v: String): Boolean = safeToken(v)

  private def safeToken(s: String): Boolean = s.nonEmpty && s.matches("[A-Za-z0-9._\\-]+")

  /** 上一版安装目录里的包路径（包在本目录内的 glob；找不到 ⇒ Left，不猜、不回落）。 */
  def jarIn(dir: os.Path, version: String): IO[Either[String, String]] =
    IO.blocking {
      val vd = versionDir(dir, version)
      val direct = vd / jarName(version)
      if os.exists(direct) then Some(direct.toString)
      else if os.exists(vd) then
        // 双拼 glob（与 release/install.sh 的 archive_legacy_jars 同判据）：现行品牌
        // 前缀优先，品牌迁移前落盘的 legacy `nebflow-assembly-*` 留存包仍可回滚。
        val jars = os.list(vd).filter(_.last.endsWith(".jar"))
        jars
          .find(_.last.startsWith(jarPrefix))
          .orElse(jars.find(_.last.startsWith(LegacyJarPrefix)))
          .map(_.toString)
      else None
    }.map {
      case Some(p) => Right(p)
      case None =>
        Left(
          s"no package for version '$version' under ${versionDir(dir, version).toString} (version retention missing?)"
        )
    }.handleErrorWith(e => IO.pure(Left(s"previous package lookup failed: ${msg(e)}")))

  /** 诊断读数（人工/诊断命令用；只读，零写面）。 */
  final case class Layout(installDir: String, current: Option[String], previous: Option[String], versions: List[String])

  def layout(dir: os.Path = InstallPointer.defaultInstallDir()): IO[Layout] =
    (
      readCurrent(dir),
      readPrevious(dir),
      IO.blocking(if os.exists(versionsDir(dir)) then os.list(versionsDir(dir)).map(_.last).toList.sorted else Nil)
        .handleErrorWith(_ => IO.pure(Nil))
    )
      .mapN(Layout(dir.toString, _, _, _))

  private def msg(e: Throwable): String = Option(e.getMessage).getOrElse(e.toString)

end InstallPointer
