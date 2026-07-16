package nebflow.agent

import cats.effect.IO
import nebflow.shared.MtimeCache

/**
 * A named, mtime-tracked source of prompt injection content.
 *
 * File-backed sources use [[MtimeFileCache]] so unchanged files cost
 * only a stat() syscall. All sources are re-evaluated every turn;
 * edits take effect immediately on the next LLM call.
 */
trait InjectionSource:
  def name: String
  def get: IO[String]

/**
 * File-backed source with mtime caching.
 * Only re-reads when its modification time changes.
 * Returns [[fallback]] when the file does not exist.
 */
class FileInjectionSource(
  val name: String,
  path: os.Path,
  fallback: => String = ""
) extends InjectionSource:

  private val cache = MtimeCache.file(path, identity[String])

  def get: IO[String] =
    cache.get.map(_.getOrElse(fallback)).map { content =>
      val trimmed = content.trim
      if trimmed.nonEmpty then trimmed + "\n\n" else ""
    }

  def invalidate: IO[Unit] = cache.invalidate
end FileInjectionSource
