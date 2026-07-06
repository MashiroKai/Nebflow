package nebflow.dropbox

import cats.effect.IO
import fs2.Stream
import nebflow.core.NebflowLogger

import java.security.MessageDigest
import java.time.format.DateTimeFormatter
import java.time.ZonedDateTime

/** Pure utility functions for Dropbox file handling. Extracted for testability. */
object DropboxUtil:
  private val logger = NebflowLogger.forName("nebflow.dropbox")

  /** Stream bytes to a file while computing SHA-256. Returns the hex hash. */
  def streamToFileWithHash(stream: Stream[IO, Byte], path: os.Path): IO[String] =
    IO.blocking {
      if os.exists(path) then os.remove(path)
      ()
    } *> {
      val digest = MessageDigest.getInstance("SHA-256")
      stream.chunks.evalTap { chunk =>
        IO.blocking {
          val bytes = chunk.toArray
          digest.update(bytes)
          os.write.append(path, bytes)
        }
      }.compile.drain *> IO {
        digest.digest().map(b => f"$b%02x").mkString
      }
    }

  /** Compute SHA-256 of an existing file. Returns the hex hash. */
  def hashFile(path: os.Path): IO[String] =
    IO.blocking {
      val digest = MessageDigest.getInstance("SHA-256")
      val bytes = os.read.bytes(path)
      digest.update(bytes)
      digest.digest().map(b => f"$b%02x").mkString
    }

  /**
   * Resolve the final save path, appending a timestamp suffix if the name already exists.
   * e.g. "report.pdf" → "report_20250115_143022.pdf"
   */
  def resolveFinalPath(dir: os.Path, fileName: String): os.Path =
    val target = dir / fileName
    if !os.exists(target) then target
    else
      val dot = fileName.lastIndexOf('.')
      val (base, ext) = if dot > 0 then (fileName.substring(0, dot), fileName.substring(dot)) else (fileName, "")
      val ts = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(ZonedDateTime.now())
      dir / s"${base}_$ts$ext"

  /** Platform-aware Downloads directory. */
  def downloadsDir: os.Path =
    val home = os.Path(System.getProperty("user.home"), os.pwd)
    val osName = System.getProperty("os.name", "").toLowerCase
    osName match
      case s if s.contains("win") => home / "Downloads"
      case s if s.contains("mac") => home / "Downloads"
      case _ =>
        Option(System.getenv("XDG_DOWNLOAD_DIR")) match
          case Some(p) => os.Path(p, os.pwd)
          case None    => home / "Downloads"

end DropboxUtil
