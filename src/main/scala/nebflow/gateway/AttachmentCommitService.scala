package nebflow.gateway

import cats.effect.IO

import java.io.InputStream
import java.nio.file.{Files, LinkOption, Path, StandardCopyOption}
import java.nio.file.attribute.PosixFilePermission
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Opaque, validated resource selector. It is a single filename, never a path. */
opaque type ResourceKey = String

object ResourceKey:
  def from(value: String): Either[String, ResourceKey] =
    if value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") && value != "." && value != ".." then Right(value)
    else Left("invalid resource key")

  extension (key: ResourceKey) private[gateway] def value: String = key

/** Minimal isolated attachment commit seam; it does not create or manage the root. */
final class AttachmentCommitService private (
  root: Path,
  ops: AttachmentCommitService.FileOps
):
  import AttachmentCommitService.*

  /**
   * Writes content to an exclusive same-directory temp, registers ownership, then
   * atomically publishes it. The caller supplies only an authenticated capability,
   * an opaque key, and a content stream. Existing files are never overwritten.
   */
  def commit(access: OwnerAccess, key: ResourceKey, content: InputStream): IO[Path] =
    IO.blocking {
      val base = root.toAbsolutePath.normalize()
      if Files.isSymbolicLink(base) then throw new SecurityException("attachment root is a symlink")
      Files.createDirectories(base)
      val realBase = base.toRealPath(LinkOption.NOFOLLOW_LINKS)
      if realBase != base.toRealPath() then throw new SecurityException("attachment root changed")
      val target = realBase.resolve(key.value).normalize()
      if target.getParent != realBase || Files.isSymbolicLink(target) || Files.exists(target, LinkOption.NOFOLLOW_LINKS) then
        throw new SecurityException("unsafe or existing attachment target")
      val temp = ops.createTemp(realBase).toAbsolutePath.normalize()
      try
        if temp.getParent != realBase || Files.isSymbolicLink(temp) || !Files.isRegularFile(temp, LinkOption.NOFOLLOW_LINKS) then
          throw new SecurityException("temporary file is not a private same-directory regular file")
        ops.copy(content, temp)
        ops.ownerOnly(temp)
        (temp, target)
      catch
        case NonFatal(error) =>
          ops.delete(temp)
          throw error
    }.flatMap { case (temp, target) =>
      access.createResource("attachment", key.value).flatMap {
        case false => IO.blocking(ops.delete(temp)) *> IO.raiseError[Path](new SecurityException("unknown or conflicting owner"))
        case true =>
          IO.blocking {
            if Files.isSymbolicLink(root) || Files.isSymbolicLink(target) || Files.exists(target, LinkOption.NOFOLLOW_LINKS) then
              throw new SecurityException("attachment target changed before publish")
            ops.atomicMove(temp, target)
            target
          }.handleErrorWith(error => IO.blocking(ops.delete(temp)).attempt *> IO.raiseError(error))
      }.handleErrorWith(error => IO.blocking(ops.delete(temp)).attempt *> IO.raiseError(error))
    }

object AttachmentCommitService:
  trait FileOps:
    def createTemp(parent: Path): Path
    def copy(input: InputStream, temp: Path): Unit
    def ownerOnly(path: Path): Unit
    def atomicMove(temp: Path, target: Path): Unit
    def delete(path: Path): Unit

  private object NioFileOps extends FileOps:
    def createTemp(parent: Path): Path = Files.createTempFile(parent, ".attachment-", ".tmp")
    def copy(input: InputStream, temp: Path): Unit =
      try Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING)
      finally input.close()
    def ownerOnly(path: Path): Unit =
      try Files.setPosixFilePermissions(path, Set(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE).asJava)
      catch case _: UnsupportedOperationException => ()
    def atomicMove(temp: Path, target: Path): Unit = Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
    def delete(path: Path): Unit = Files.deleteIfExists(path)

  def at(root: Path, ops: FileOps = NioFileOps): AttachmentCommitService =
    new AttachmentCommitService(root.toAbsolutePath.normalize(), ops)
