package nebflow.cli

import cats.effect.IO
import nebflow.core.hotrestart.InstallPointer
import nebflow.shared.Branding

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import scala.util.control.NonFatal

/**
 * **two-phase update — Phase1：download-verify-stage**（台账 #916，2026-10-26
 * 作者令）。把「取包 → 校验 → 落 staging」与「换装+重启（Phase2 = 指针晋升 +
 * detached apply）」拆成两个全或无的阶段：
 *
 *   - Phase1 **不动现役 jar**（版本目录不可变语义：同名包 sha 不同即拒绝，
 *     sha 相同即幂等命中、零写盘）、**不碰指针**、**不碰在跑引擎**——引擎忙
 *     时照常 staging；
 *   - 全就绪才进 Phase2（[[UpdateCommand]] 的 apply 面：pointer 布局 =
 *     [[InstallPointer.promote]] 原子切 + OS 托管 detached restart；winsort
 *     扁平布局 = 新包落位即选定 + detached restart）。
 *
 * 中断安全与既有锚一致：包文件只经「同卷 .part → ATOMIC_MOVE 落位」一条路
 * 进选取面；任何时刻断电/被杀，选取面上出现的都是完整包（或旧态）。.part
 * 残留只在下一次同名 staging 时被覆写，不进选取面。
 */
object UpdateStage:

  private val logger = nebflow.shared.NebflowLogger.forName("nebflow.cli.update")

  final case class Staged(
    version: String,
    sha256: String,
    jarName: String,
    stagedPath: String,
    layout: DetachedApply.Layout,
    /** 目录里已有同版本同内容包 → 幂等命中（未重写盘上一字节）。 */
    idempotentHit: Boolean
  )

  /** 包名 → 版本（`<lowerName>-assembly-<v>.jar`；legacy nebflow 前缀兼容）。 */
  def versionOfJar(fileName: String): Option[String] =
    s"^(?:${Branding.lowerName}|nebflow)-assembly-(.+)\\.jar$$".r
      .findFirstMatchIn(fileName)
      .map(_.group(1))
      .filter(v => InstallPointer.safeVersionLiteral(v))

  /**
   * winsort 扁平布局的**选取面合同**：launcher 的数值排序只认
   * `date[.seq][-beta.N]`（packaging/app-version.sh:36 同一正则）；不合规形状
   * （如 `-dev.<sha>`）在指针布局里合法（指针直名），在 winsort 布局里是
   * **永不可选的死包**——staging 面拒绝，不让它静静地躺在选取面里。
   */
  def winsortParseable(version: String): Boolean =
    """^\d{4}\.\d{1,2}\.\d{1,2}(\.\d+)?(-beta\.\d+)?$""".r.matches(version)

  /** sha256（流式；Phase1 校验的锚，注册进 apply 证据链）。 */
  def sha256Of(path: os.Path): IO[Either[String, String]] =
    IO.blocking(sha256Sync(path)).attempt.map {
      case Right(h) => Right(h)
      case Left(NonFatal(e)) => Left(s"sha256 failed: ${Option(e.getMessage).getOrElse(e.toString)}")
      case Left(e) => Left(e.toString)
    }

  private def sha256Sync(path: os.Path): String =
    val md = MessageDigest.getInstance("SHA-256")
    val in = Files.newInputStream(path.toNIO)
    try
      val buf = new Array[Byte](64 * 1024)
      var n = 0
      while { n = in.read(buf); n != -1 } do md.update(buf, 0, n)
    finally in.close()
    md.digest().map(b => f"$b%02x").mkString

  /**
   * Phase1 主入口：校验 → 落位（幂等）→ 返回 Staged。全程不写指针、不触引擎。
   * `installDir` = launcher 所在目录（Mac: ~/.stand/bin；KAI: %LOCALAPPDATA%\stand）。
   */
  def stage(jarPath: os.Path, installDir: os.Path, layout: DetachedApply.Layout): IO[Either[String, Staged]] =
    if !os.exists(jarPath) then IO.pure(Left(s"jar not found: $jarPath"))
    else if !os.isFile(jarPath) then IO.pure(Left(s"not a file: $jarPath"))
    else
      versionOfJar(jarPath.last) match
        case None =>
          IO.pure(
            Left(s"cannot parse version from '${jarPath.last}' (expected ${Branding.lowerName}-assembly-<version>.jar)")
          )
        case Some(v) if layout == DetachedApply.Layout.WinsortFlat && !winsortParseable(v) =>
          IO.pure(
            Left(
              s"version '$v' is not winsort-selectable (date[.seq][-beta.N] contract) — this launcher layout picks the newest winsort-parseable jar; a pointer-layout install can take this shape"
            )
          )
        case Some(v) =>
          val finalPath = layout match
            case DetachedApply.Layout.Pointer     => InstallPointer.versionDir(installDir, v) / jarPath.last
            case DetachedApply.Layout.WinsortFlat => installDir / jarPath.last
          IO
            .blocking {
              if os.exists(finalPath) then
                // 同名包在场：sha 相同 = 幂等命中（零写盘）；不同 = 拒绝（版本目录不可变）
                val existing = sha256Sync(finalPath)
                if existing == sha256Sync(jarPath) then
                  Right(Staged(v, existing, jarPath.last, finalPath.toString, layout, idempotentHit = true))
                else
                  Left(
                    s"version $v already staged with DIFFERENT content (staged $existing != source ${sha256Sync(jarPath)}) — version dirs are immutable; pick a new version literal"
                  )
              else
                os.makeDir.all(finalPath / os.up)
                val part = finalPath / os.up / s"${finalPath.last}.part"
                try
                  os.copy.over(jarPath, part, createFolders = true)
                  val copied = sha256Sync(part)
                  val src = sha256Sync(jarPath)
                  if copied != src then
                    Left(s"staged copy sha mismatch ($copied != $src) — source changed mid-copy; nothing promoted")
                  else
                    Files.move(
                      part.toNIO,
                      finalPath.toNIO,
                      StandardCopyOption.REPLACE_EXISTING,
                      StandardCopyOption.ATOMIC_MOVE
                    )
                    Right(Staged(v, copied, jarPath.last, finalPath.toString, layout, idempotentHit = false))
                catch case NonFatal(e) => Left(s"stage copy failed: ${Option(e.getMessage).getOrElse(e.toString)}")
                finally
                  try if os.exists(part) then os.remove(part)
                  catch case _: Exception => ()
            }
            .attempt
            .map {
              case Right(r) => r
              case Left(NonFatal(e)) => Left(s"stage failed: ${Option(e.getMessage).getOrElse(e.toString)}")
              case Left(e) => Left(e.toString)
            }
end UpdateStage
