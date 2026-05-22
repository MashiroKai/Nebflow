package nebflow.service

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import nebflow.shared.{MtimeCache, MtimeFileCache}

/**
 * Per-folder rules store backed by Markdown files.
 *
 * Storage: ~/.nebflow/folders/<folderId>.rules.md
 *
 * Each folder can have its own rules.md. Child folders automatically inherit
 * (accumulate) their parent's rules. The inheritance chain walks from the
 * current folder up to the top-level folder, concatenating all rules in order.
 */
object RulesStore:

  // --- Paths ---

  private val baseDir: os.Path = os.home / ".nebflow" / "folders"

  def rulesPath(folderId: String): os.Path = baseDir / s"$folderId.rules.md"

  // --- Mtime-cached file reads ---

  private def parseRules(content: String): Option[String] =
    val trimmed = content.trim
    if trimmed.nonEmpty then Some(trimmed) else None

  private val caches = scala.collection.mutable.Map[String, MtimeFileCache[Option[String]]]()

  private def getCache(folderId: String): MtimeFileCache[Option[String]] =
    caches.getOrElseUpdate(folderId, MtimeCache.file(rulesPath(folderId), parseRules))

  // --- Load single folder rules (mtime-cached) ---

  def loadFolderRules(folderId: String): Option[String] =
    getCache(folderId).get.unsafeRunSync().flatten

  // --- Save ---

  def saveFolderRules(folderId: String, content: String): IO[Unit] =
    IO.blocking(os.write.over(rulesPath(folderId), content, createFolders = true)) *>
      getCache(folderId).invalidate

  // --- Preview (first non-heading, non-empty line, max 80 chars) ---

  def preview(folderId: String): Option[String] =
    val path = rulesPath(folderId)
    if !os.exists(path) then None
    else
      val content = os.read(path).trim
      if content.isEmpty then None
      else
        Some(
          content.linesIterator
            .dropWhile(l => l.trim.isEmpty || l.trim.startsWith("#"))
            .find(_.trim.nonEmpty)
            .map(_.trim.take(80))
            .getOrElse("(empty)")
        )

  // --- Exists ---

  def exists(folderId: String): Boolean =
    val path = rulesPath(folderId)
    os.exists(path) && os.stat(path).size > 0

  /**
   * Resolve inherited rules for a folder: walks from current folder up to
   * top-level, concatenating all rules.md content in order (root → leaf).
   *
   * @param folderId     the folder to resolve rules for
   * @param findParent   function to look up a folder's parentId
   * @return             concatenated rules text, or None if no rules exist
   */
  def resolveInheritedRules(
    folderId: String,
    findParent: String => Option[Option[String]]  // folderId => Option[parentId]
  ): Option[String] =
    // Build chain from leaf to root
    def chain(id: String, acc: List[String]): List[String] =
      findParent(id) match
        case None => acc  // folder not found, stop
        case Some(Some(pid)) => chain(pid, pid :: acc)
        case Some(None) => id :: acc  // top-level reached

    val ordered = chain(folderId, List(folderId))
    val rulesList = ordered.flatMap(loadFolderRules)
    if rulesList.nonEmpty then Some(rulesList.mkString("\n\n")) else None

end RulesStore
