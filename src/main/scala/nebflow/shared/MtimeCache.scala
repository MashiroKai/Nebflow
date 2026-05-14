package nebflow.shared

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref, Sync}

/**
 * File content cache keyed by modification time.
 *
 * On every access, checks the file's mtime via os.stat (cheap syscall, no content read).
 * If mtime matches the cached value, returns the cached content.
 * Otherwise, re-reads and caches the new content.
 *
 * This provides a unified "edit file → next access sees changes" mechanism
 * across all configuration files (agent defs, memory files, preferences).
 */
object MtimeCache:

  case class Cached[T](value: T, mtimeMs: Long)

  /** Get file mtime as epoch millis. */
  def mtimeOf(path: os.Path): Long = os.stat(path).mtime.toMillis

  /**
   * Create a new mtime-aware cache for a single file.
   *
   * @param path   the file to cache
   * @param parse  pure function: file content string → parsed value
   * @return       an MtimeFileCache instance
   */
  def file[T](path: os.Path, parse: String => T): MtimeFileCache[T] =
    new MtimeFileCache[T](path, parse)

  /**
   * Create a new mtime-aware cache for a directory of files (e.g. agent dirs).
   * Each subdirectory is cached individually by its own mtime.
   *
   * @param listEntries  lists (key, directory path) pairs
   * @param parseEntry   reads and parses a single directory into a value
   * @return             an MtimeDirCache instance
   */
  def directory[K, V](
    listEntries: () => List[(K, os.Path)],
    parseEntry: (K, os.Path) => V
  ): MtimeDirCache[K, V] =
    new MtimeDirCache[K, V](listEntries, parseEntry)

end MtimeCache

/**
 * Mtime cache for a single file.
 * Thread-safe via Ref.
 */
final class MtimeFileCache[T] private[shared] (
  path: os.Path,
  parse: String => T
):
  private val cache: Ref[IO, Option[MtimeCache.Cached[T]]] = Ref.unsafe(None)

  /** Get the parsed value, re-reading only if the file changed. Returns None if file doesn't exist. */
  def get: IO[Option[T]] =
    if !os.exists(path) then cache.set(None) *> IO.pure(None)
    else
      val currentMs = MtimeCache.mtimeOf(path)
      cache.get.flatMap {
        case Some(c) if c.mtimeMs == currentMs => IO.pure(Some(c.value))
        case _ =>
          IO.blocking {
            val content = os.read(path)
            val value = parse(content)
            MtimeCache.Cached(value, currentMs)
          }.flatTap(c => cache.set(Some(c)))
            .map(_.value)
            .map(Some(_))
      }

  /** Force clear the cache. */
  def invalidate: IO[Unit] = cache.set(None)

end MtimeFileCache

/**
 * Mtime cache for a directory of entries (e.g. agent definitions).
 * Each entry is tracked by the mtime of its directory (any file change in the dir bumps mtime).
 */
final class MtimeDirCache[K, V] private[shared] (
  listEntries: () => List[(K, os.Path)],
  parseEntry: (K, os.Path) => V
):
  private val cache: Ref[IO, Map[K, MtimeCache.Cached[V]]] = Ref.unsafe(Map.empty)

  private case class Entry(key: K, dirPath: os.Path, mtimeMs: Long)

  /**
   * Get the latest mtime of any file in the directory (not the dir itself).
   * macOS does not update directory mtime when files inside are edited,
   * only when files are added/removed.
   */
  private def latestFileMtime(dirPath: os.Path): Long =
    os.walk(dirPath)
      .filter(os.isFile)
      .map(p => MtimeCache.mtimeOf(p))
      .maxOption
      .getOrElse(0L)

  /** Load all entries, re-reading only changed ones. */
  def loadAll: IO[Map[K, V]] =
    IO.blocking {
      listEntries().flatMap { (key, dirPath) =>
        if !os.exists(dirPath) then None
        else Some(Entry(key, dirPath, latestFileMtime(dirPath)))
      }
    }.flatMap { entries =>
      cache.get.flatMap { cached =>
        IO.blocking {
          entries.map { e =>
            cached.get(e.key) match
              case Some(c) if c.mtimeMs == e.mtimeMs => e.key -> c.value
              case _ => e.key -> parseEntry(e.key, e.dirPath)
          }.toMap
        }.flatTap { results =>
          cache.set(entries.map(e => e.key -> MtimeCache.Cached(results(e.key), e.mtimeMs)).toMap)
        }
      }
    }

  /** Force clear the cache. */
  def invalidate: IO[Unit] = cache.set(Map.empty)

end MtimeDirCache
