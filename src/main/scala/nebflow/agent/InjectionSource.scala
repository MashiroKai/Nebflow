package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import nebflow.shared.MtimeCache

/**
 * Injection mode: controls when a source is re-evaluated.
 *
 *   - [[EveryTurn]] — mtime checked on every `pipeLlmCall`; edits take effect immediately.
 *   - [[Lifecycle]] — resolved once per session lifecycle; subsequent turns reuse value.
 */
enum InjectionMode derives CanEqual:
  case EveryTurn
  case Lifecycle

/**
 * A named, mtime-tracked source of prompt injection content.
 *
 * Each source can be independently cached and invalidated. File-backed sources
 * use [[MtimeFileCache]] so unchanged files cost only a stat() syscall.
 * In-memory / computed sources skip disk I/O entirely.
 *
 * == Lifecycle management ==
 * Sources in [[InjectionMode.EveryTurn]] are re-checked at every `refreshTurn()`.
 * Sources in [[InjectionMode.Lifecycle]] are resolved once and stored; call
 * [[resetLifecycle()]] on the container to force re-resolution (e.g. on session switch).
 */
trait InjectionSource:
  def name: String
  def mode: InjectionMode
  def get: IO[String]

// ============================================================
// Concrete implementations
// ============================================================

/**
 * File-backed source with mtime caching.
 * Only re-reads the file when its modification time changes.
 * If the file does not exist, returns [[fallback]] (empty string by default).
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

/**
 * In-memory constant source — value is fixed at construction time.
 */
class ConstInjectionSource(
  val name: String,
  val mode: InjectionMode,
  value: String
) extends InjectionSource:
  def get: IO[String] = IO.pure(value)

/**
 * Lazy-computed source — value is recomputed on every [[get]] call.
 * Suitable for purely dynamic content (e.g. env info) that has no file backing.
 */
class DynamicInjectionSource(
  val name: String,
  val mode: InjectionMode,
  compute: () => String
) extends InjectionSource:
  def get: IO[String] = IO(compute())

// ============================================================
// Registry / container
// ============================================================

/**
 * Holds all [[InjectionSource]] instances for the current session.
 *
 * Provides:
 *   - [[injections]] — the full list of registered sources
 *   - [[buildEveryTurnBlock]] — concatenates all [[EveryTurn]] sources into one text block
 *   - [[resetLifecycle]] — forces re-resolution of all [[Lifecycle]] sources on next call
 *
 * Currently all sources are registered as [[EveryTurn]]. Classification
 * into [[Lifecycle]] will happen in a follow-up step.
 */
class InjectionSources(sources: List[InjectionSource]):

  /** All registered sources. */
  def all: List[InjectionSource] = sources

  /** Filter by mode. */
  def byMode(m: InjectionMode): List[InjectionSource] = sources.filter(_.mode == m)

  /**
   * Concatenate all EveryTurn sources into a single text block.
   * Each source is separated by a blank line. Empty sources are skipped.
   */
  def buildEveryTurnBlock: IO[String] =
    sources
      .filter(_.mode == InjectionMode.EveryTurn)
      .traverse(_.get)
      .map(_.filter(_.nonEmpty).mkString("\n"))

  /** Force invalidate all Lifecycle sources (e.g. on session switch). */
  def resetLifecycle: IO[Unit] =
    sources
      .filter(_.mode == InjectionMode.Lifecycle)
      .collect { case f: FileInjectionSource => f.invalidate }
      .sequence_
      .void

end InjectionSources
