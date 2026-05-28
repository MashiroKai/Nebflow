package nebflow.agent

import cats.effect.IO
import nebflow.shared.MtimeCache

/**
 * Injection mode: controls when a source is re-evaluated.
 *
 *   - [[EveryTurn]] — re-resolved every turn; edits take effect immediately.
 *   - [[Lifecycle]] — resolved once per session lifecycle; cached until reset.
 */
enum InjectionMode derives CanEqual:
  case EveryTurn
  case Lifecycle

/**
 * A named, mtime-tracked source of prompt injection content.
 *
 * File-backed sources use [[MtimeFileCache]] so unchanged files cost
 * only a stat() syscall. Lifecycle sources are cached in [[LifecycleContext]]
 * and only re-read on lifecycle reset (/clear, compact, model switch).
 */
trait InjectionSource:
  def name: String
  def mode: InjectionMode
  def get: IO[String]

/**
 * File-backed source with mtime caching.
 * Only re-reads when its modification time changes.
 * Returns [[fallback]] when the file does not exist.
 */
class FileInjectionSource(
  val name: String,
  val mode: InjectionMode,
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
