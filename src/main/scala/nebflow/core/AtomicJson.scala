package nebflow.core

import cats.effect.IO

/**
 * Crash-safe file writes for JSON (or any small text) state files.
 *
 * Writes go to a unique temp file in the target's directory, then
 * `java.nio.file.Files.move` with `ATOMIC_MOVE` — which maps to rename(2),
 * a true single-step atomic replace on POSIX. Readers therefore see either
 * the complete old file or the complete new file, never a truncated/partial
 * write left behind by a mid-write crash (issue #23).
 *
 * Why not `os.move.over(replaceExisting = true)`: os-lib implements that as
 * delete-then-rename, which is NOT atomic (and throws NoSuchFileException if
 * the target disappears between the two steps). The old tmp+move.over
 * precedents only narrowed the corruption window — this closes it.
 *
 * Concurrency: the temp name carries a random UUID, so concurrent writers
 * never share a temp file (the shared `_index.json.tmp` race — see
 * SessionStore.saveIndex history).
 */
object AtomicJson:

  /** Effectful write — runs on the blocking thread pool. */
  def write(path: os.Path, content: String): IO[Unit] =
    IO.blocking(writeSync(path, content))

  /**
   * Direct blocking write for call sites already inside `IO.blocking`, or
   * synchronous code (ModelRegistry-style). Same semantics as [[write]].
   */
  def writeSync(path: os.Path, content: String): Unit =
    val tmp = path / os.up / s"${path.last}.tmp.${java.util.UUID.randomUUID()}"
    try
      os.write.over(tmp, content, createFolders = true)
      java.nio.file.Files.move(
        tmp.toNIO,
        path.toNIO,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        java.nio.file.StandardCopyOption.ATOMIC_MOVE
      )
    catch
      case e: Throwable =>
        // On failure the target file is untouched — that is the point of the
        // tmp+rename dance. Best-effort cleanup so failed writes don't litter
        // the directory with *.tmp.* residue.
        try if os.exists(tmp) then os.remove(tmp)
        catch case _: Exception => ()
        throw e
    end try
  end writeSync

end AtomicJson
