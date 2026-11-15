/* 严格DAG第⑥步第二批裁定(2026-09-27):本文件自 nebflow/neblink/RemoteUpdateAction.scala 整文件落 core/hotupdate(原样迁移,package 改 nebflow.core.hotupdate;InstallLayout 引用自此为同包系上行引用),斩断 core→neblink 边;neblink 侧(NeblinkRelayTunnel)与 gateway 侧(NeblinkRoutes)改经下行 import/FQN 引用本处。 */
package nebflow.core.hotupdate

import cats.effect.IO
import cats.syntax.all.*
import nebflow.shared.NebflowLogger

/**
 * Runs the remote update install script and returns the result.
 *
 * Shared logic used by:
 *   - RestApiRoutes POST /neblink/update (P2P endpoint)
 *   - NeblinkRelayTunnel handleRelayRequest for "RemoteUpdate" action (relay)
 *   - UpdateOrchestrator.defaultInstall (统一更新七相位的「更新」相位安装动作)
 *
 * The caller is responsible for scheduling the actual JVM restart after a
 * successful install — see RestartHelper.spawnRestart().
 *
 * msi guard (Team #11 ③, 2026-08-28): on an msi install the update script
 * would silently re-install as a plain-jar layout (form drift), so it is
 * refused with a pointer to the download page instead. The msi form is
 * identified by the marker file packaging/build-msi.sh stages into the
 * app payload (nebflow.core.InstallLayout.isMsiInstall).
 *
 * **校验拒装腿（统一更新系统 M3 ③，DESIGN §5「装前校验，不符 ⇒ Aborted，绝不入
 * staging swap」）**：安装动作本体仍是既有 install.sh（禁双实现——staging swap、
 * 指针写入、wrapper 生成全部在脚本单一处），本件只在其前面加一段 **manifest 时代
 * 的取件校验**：
 *   1. manifest 可读且带 `artifacts.jar` 校验字段 ⇒ 引擎先拉 jar 到临时文件，
 *      按 manifest 的 `sha256` + `sizeBytes` 逐字节校验；**任一不符 ⇒ Left 拒装**
 *      （install.sh 根本不起跑，在役包与 staging 区零接触）；校验通过 ⇒ 以
 *      `NEBFLOW_STAGED_JAR` / `NEBFLOW_EXPECTED_JAR_SHA256` 环境变量把已校验文件
 *      交接给 install.sh（脚本侧对交接件**再验一次哈希**才采用——防交接窗内文件被换，
 *      双向都不信任未验哈希的字节）；另带 `minCompatibleEngine` 防呆（低于 ⇒ 拒装 +
 *      「需先手动跨版」可见解释）。
 *   2. manifest 不可读（读序回落到裸 txt 的过渡期常态）⇒ 无校验面 ⇒ **既有路径
 *      逐字节照旧**（install.sh 自身的 checksums.txt 警示级校验不受影响）。
 * 失败语义：拒装/脚本失败都折成 `Left` ⇒ 编排器既有 Aborted 相位（旧实例续服），
 * 不新增失败类别。
 */
object RemoteUpdateAction:

  private val logger = NebflowLogger.forName("nebflow.hotupdate")

  /** 交接环境变量名（install.sh download_jar 消费，两侧同字面，禁漂移）。 */
  val StagedJarEnv = "NEBFLOW_STAGED_JAR"
  val ExpectedShaEnv = "NEBFLOW_EXPECTED_JAR_SHA256"

  /** 已校验交接件。 */
  final case class StagedJar(path: os.Path, sha256: String, sizeBytes: Long)

  /**
   * The exact install command this action would run for the given channel (single
   * source: [[runInstallScript]] executes what this returns, the hot-update batch-1
   * verification reads it without executing).
   */
  def installCommand(beta: Boolean): String =
    val isWindows = sys.props.getOrElse("os.name", "").toLowerCase.contains("win")
    if beta then
      if isWindows then
        """powershell -Command "$env:CHANNEL='beta'; iwr """ + nebflow.shared.Branding.installPs1Url + """ | iex" """
      else "curl -fsSL " + nebflow.shared.Branding.installUrl + " | sh -s -- --beta"
    else if isWindows then
      """powershell -Command "& { iwr """ + nebflow.shared.Branding.installPs1Url + """ | iex }" """
    else "curl -fsSL " + nebflow.shared.Branding.installUrl + " | sh"

  /** Run the install script. Returns Right(msg) on success, Left(error) on failure. */
  def runInstallScript(beta: Boolean): IO[Either[String, String]] =
    if nebflow.core.InstallLayout.isMsiInstall then
      IO.pure(
        Left(
          "This copy was installed with the Windows installer (msi); the in-app update " +
            "script would replace it with a plain-jar layout, so it was skipped. " +
            "Please download the latest msi from the download page and install it over this copy."
        )
      )
    else
      // M3 ③：manifest 时代先取件校验；校验不过 ⇒ 拒装（Aborted 相位，在役包不动）
      verifiedStagedJar(beta).flatMap {
        case Left(verifyErr) =>
          IO.pure(
            Left(
              s"manifest artifact verification failed — install REFUSED, the in-service " +
                s"package is untouched: $verifyErr"
            )
          )
        case Right(staged) =>
          runScript(beta, staged).guarantee(
            staged.traverse_(s => IO.blocking(os.remove(s.path)).handleErrorWith(_ => IO.unit))
          )
      }

  /**
   * 取件校验（读序与语义见文件头）。manifest 不可读 ⇒ `Right(None)`（legacy 路径）；
   * 校验字段不齐 ⇒ `Right(None)` + WARN（发布侧 schema 违约的防御性降级，M1 校验器
   * 本应拦住）；校验失败 ⇒ `Left`（拒装）。
   */
  private[hotupdate] def verifiedStagedJar(beta: Boolean): IO[Either[String, Option[StagedJar]]] =
    VersionCheck.fetchManifest(beta).flatMap {
      case None => IO.pure(Right(None)) // txt 回落时代：无校验面，legacy 照旧
      case Some(m) =>
        val current = nebflow.Version.string
        // minCompatibleEngine 防呆（DESIGN §5）：低于 ⇒ 不自动装，可见解释
        m.minCompatibleEngine match
          case Some(minimum) if VersionCheck.compareVersions(current, minimum.stripPrefix("v")) < 0 =>
            IO.pure(
              Left(
                s"current version $current is below minCompatibleEngine ${minimum.stripPrefix("v")} — " +
                  "automatic update refused; a manual cross-version update is required first"
              )
            )
          case _ =>
            (m.jarUrl, m.jarSha256, m.jarSizeBytes) match
              case (Some(url), Some(sha), Some(size)) =>
                pullAndVerify(url, sha, size).map(_.map(Some(_)))
              case _ =>
                logger.warn(
                  s"[hotupdate] manifest ${m.version} reachable but artifacts.jar integrity fields " +
                    "missing — falling back to the legacy install path (no staged-jar verification)"
                )
                IO.pure(Right(None))
    }

  /** 拉 jar 到临时文件 + sizeBytes/sha256 逐项校验；任一不过 ⇒ Left（临时件即删）。 */
  private def pullAndVerify(url: String, expectedSha: String, expectedSize: Long): IO[Either[String, StagedJar]] =
    IO.blocking {
      val target = os.temp(prefix = "stand-staged-jar-", suffix = ".jar")
      try
        val conn = java.net.URI.create(url).toURL.openConnection()
        conn.setConnectTimeout(5000)
        conn.setReadTimeout(5000)
        val in = conn.getInputStream
        try
          java.nio.file.Files.copy(
            in,
            target.toNIO,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
          )
        finally in.close()
        val actualSize = os.size(target)
        if actualSize != expectedSize then
          os.remove(target)
          Left(s"size mismatch for $url: got $actualSize bytes, manifest declares $expectedSize")
        else
          val actualSha = sha256Of(target)
          if actualSha != expectedSha then
            os.remove(target)
            Left(s"sha256 mismatch for $url: got $actualSha, manifest declares $expectedSha")
          else Right(StagedJar(target, actualSha, actualSize))
      catch
        case t: Throwable =>
          try os.remove(target)
          catch case _: Throwable => ()
          Left(s"artifact fetch failed for $url: ${Option(t.getMessage).getOrElse(t.toString)}")
    }

  /** 流式 sha256（64MB 级 jar 不进内存）。 */
  private def sha256Of(path: os.Path): String =
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val in = java.nio.file.Files.newInputStream(path.toNIO)
    try
      val buf = new Array[Byte](64 * 1024)
      var n = 0
      while { n = in.read(buf); n != -1 } do digest.update(buf, 0, n)
    finally in.close()
    digest.digest().map("%02x" format _).mkString

  /**
   * 跑既有安装脚本（安装动作本体单一处）。`staged` 在场 ⇒ 以环境变量交接已校验件
   * （install.sh 侧再验哈希后才采用；脚本不认识这些变量时 = 旧安装器，自然忽略并
   * 自行下载——零破坏）。验证注入面（`nebflow.hotupdate.installCommand`）沿用既有
   * 语义：注入命令替换真实脚本，交接变量照传（良性验证链可断言交接发生了）。
   */
  private def runScript(beta: Boolean, staged: Option[StagedJar]): IO[Either[String, String]] =
    val overrideCmd =
      sys.props
        .get("nebflow.hotupdate.installCommand")
        .map(_.trim)
        .filter(_.nonEmpty)
        .orElse(sys.env.get("NEBFLOW_HOTUPDATE_INSTALL_COMMAND").map(_.trim).filter(_.nonEmpty))
    val extraEnv: Seq[(String, String)] = staged match
      case Some(s) => Seq(StagedJarEnv -> s.path.toString, ExpectedShaEnv -> s.sha256)
      case None => Seq.empty
    overrideCmd match
      case Some(cmd) =>
        logger.warn(
          s"[hotupdate] install command OVERRIDDEN by verification injection: $cmd — the real install script is NOT run"
        )
        IO.blocking {
          import sys.process.*
          Process(Seq("/bin/sh", "-c", cmd), None, extraEnv*).!
        }.map {
          case 0 => Right("verification install command succeeded")
          case code => Left(s"verification install command failed (exit code: $code)")
        }.handleErrorWith(e =>
          IO.pure(Left(s"verification install command error: ${Option(e.getMessage).getOrElse(e.toString)}"))
        )
      case None =>
        IO.blocking {
          import sys.process.*
          Process(Seq("/bin/sh", "-c", installCommand(beta)), None, extraEnv*).!
        }.flatMap {
          case 0 => IO.pure(Right("Update installed, restarting..."))
          case code => IO.pure(Left(s"Install script failed (exit code: $code)"))
        }.handleErrorWith(e => IO.pure(Left(s"Install error: ${e.getMessage}")))

end RemoteUpdateAction
