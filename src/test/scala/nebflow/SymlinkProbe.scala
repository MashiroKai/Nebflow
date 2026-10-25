package nebflow

import java.nio.file.{Files, Path}

/**
 * symlink 脚手架能力探针（Windows 测试批 2026-10-25）。
 *
 * WHY：Windows 上 `Files.createSymbolicLink` 需要 SeCreateSymbolicLinkPrivilege
 * （开发者模式/管理员），无特权时抛 `FileSystemException: …客户端没有所需的特权`
 * （ERROR_1314）。依赖 symlink 脚手架的测试在无特权 Windows 主机上**全部红**——
 * 失败面是主机能力，不是引擎行为。Mac/有特权 Windows 上探针恒真，零行为变化。
 *
 * 用法：symlink 脚手架 helper（或用例体首行）调 [[assumeSymlinks]]——
 * munit `assume` 失败 ⇒ 该用例记 **ignored**（非 failed），与仓内
 * `posixOnly()`（SecretsStoreSpec）同一平台门惯例。
 */
object SymlinkProbe:

  /** 进程级缓存（Test / parallelExecution := false，顺序执行，无竞态）。 */
  private var cached: Option[Boolean] = None

  def canCreate(): Boolean =
    cached.getOrElse:
      val probe: Path = Files.createTempFile("nb-symlink-probe", ".txt")
      val link: Path = probe.resolveSibling("nb-symlink-probe-link")
      val ok =
        try
          Files.deleteIfExists(link)
          Files.createSymbolicLink(link, probe)
          true
        catch case _: Throwable => false
        finally
          Files.deleteIfExists(link)
          Files.deleteIfExists(probe)
      cached = Some(ok)
      ok

  /** 依赖 symlink 脚手架的用例首行调用；无特权 ⇒ ignore 该用例。 */
  def assumeSymlinks(): Unit =
    assume(
      canCreate(),
      "symlink scaffolding needs symlink privilege (enable Windows Developer Mode to run this leg)"
    )
end SymlinkProbe
