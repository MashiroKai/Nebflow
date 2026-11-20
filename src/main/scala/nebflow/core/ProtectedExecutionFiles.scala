package nebflow.core

import java.nio.file.{Files, LinkOption, Path}

/**
 * Narrow execution-only write exclusion for protected engine state.
 *
 * The cooperative authority lock protects authority commits; it does not stop a
 * JVM file tool or shell process from rewriting authority/task/transcript bytes.
 * Protected tool contexts therefore carry the engine roots and reject writes
 * into a root or into an ancestor that a recursive operation could remove.
 * Multiply linked regular files are rejected conservatively because another
 * name may sit outside every root while sharing the protected inode.
 * This is a data-integrity boundary, not a general sandbox.
 */
final class ProtectedExecutionFiles private (roots: List[Path]):
  import ProtectedExecutionFiles.*

  def checkWrite(raw: Path): Either[String, Path] =
    val resolved = canonicalTarget(raw)
    val multiplyLinked = Files.exists(raw.toAbsolutePath.normalize(), LinkOption.NOFOLLOW_LINKS) &&
      !Files.isSymbolicLink(raw.toAbsolutePath.normalize()) &&
      Files.getAttribute(raw.toAbsolutePath.normalize(), "unix:nlink").asInstanceOf[Integer].intValue() > 1
    if multiplyLinked then Left(s"engine-state-write-denied: $resolved has multiple hard links")
    else
      roots.find(root => resolved == root || resolved.startsWith(root) || root.startsWith(resolved)) match
        case Some(root) => Left(s"engine-state-write-denied: $resolved (engine root $root)")
        case None => Right(resolved)

object ProtectedExecutionFiles:
  val disabled: ProtectedExecutionFiles = new ProtectedExecutionFiles(Nil)

  def apply(roots: Iterable[Path]): ProtectedExecutionFiles =
    new ProtectedExecutionFiles(roots.toList.map(canonicalRoot).distinct)

  /**
   * Engine roots must land in the same canonical form as canonicalTarget's
   * resolution (macOS /var → /private/var), or the prefix comparison misses.
   */
  private def canonicalRoot(root: Path): Path =
    val absolute = root.toAbsolutePath.normalize()
    if Files.exists(absolute, LinkOption.NOFOLLOW_LINKS) then absolute.toRealPath() else absolute

  /** Resolve existing symlink components before comparing against engine roots. */
  private def canonicalTarget(raw: Path): Path =
    val absolute = raw.toAbsolutePath.normalize()
    var probe = absolute
    while probe != null && !Files.exists(probe, LinkOption.NOFOLLOW_LINKS) do probe = probe.getParent
    if probe == null then absolute
    else
      val real = probe.toRealPath()
      if probe == absolute then real
      else real.resolve(probe.relativize(absolute)).normalize()
end ProtectedExecutionFiles
