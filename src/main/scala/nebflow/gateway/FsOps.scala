/* 从 WebSocketRoutes 迁出(行为保持重构,2026-09-24)。 */
package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.PathUtil

object FsOps:

  /**
   * 伴生对象侧 logger（#159/#176 wtsurv 批，2026-09-14）：`deletePathsSafely` 是
   * **纯核**（companion 成员，spec 直接静态调用），其**成功分支的审计留痕**必须
   * 在本层落笔 ⇒ 需要本层自己的 logger（与类侧 `nebflow.ws` 同名，日志面同源）。
   */
  private val logger = nebflow.shared.NebflowLogger.forName("nebflow.ws")

  /**
   * UI 文件浏览器**删除成功分支**的审计行（#159/#176 ④「补盲区」）。
   *
   * **单一出处**：单删（WS `deletePath`）与批量删（`deletePathsSafely`）两处共用同一
   * 生成函数——措辞与字段集只定义一次，防两处漂移（`deletePath` / `deletePaths` 的
   * 排除理由在取证件 §1.2 机制 D-2 里是同一段代码）。字段 = **被删路径**（canonical，
   * 与包含校验同一参照系）+ **来源会话** + 形态（目录递归 / 单文件）+ 通道名。
   *
   * 语义边界（硬）：只证「本通道删过 X」，**不指认**任何历史事件的责任人（取证件
   * §1.4「直接删除者未证」）。
   *
   * `channel` 取 `"deletePath"` / `"deletePaths"`（与 WS case 名同字，便于按通道 grep）。
   */
  private[gateway] def deleteAuditLine(
    channel: String,
    canonicalPath: String,
    sessionId: String,
    isDir: Boolean
  ): String =
    s"$channel: removed '$canonicalPath' (session=$sessionId, " +
      s"kind=${if isDir then "dir-recursive" else "file"}, channel=ui-file-explorer)"

  /**
   * F1 batch delete — pure, testable core for the `deletePaths` WS case.
   * Per-path guards are IDENTICAL to the single `deletePath` case:
   * resolve under root, canonical-path containment check, root itself
   * protected. Each path is attempted independently; failures (guard
   * rejection OR io error) land in `failed` without aborting the batch.
   * Returns (deletedPaths, failedPairs).
   */
  def deletePathsSafely(
    paths: List[String],
    root: os.Path,
    /**
     * 来源会话（审计用；#159/#176 wtsurv 批）。默认空串 ⇒ 既有调用点（含
     * `BatchDeleteSpec` 四例）零改动。
     */
    sessionId: String = ""
  ): IO[(List[String], List[(String, String)])] =
    paths.foldLeftM((List.empty[String], List.empty[(String, String)])) { (acc, p) =>
      resolveGuardedForDelete(p, root) match
        case Left(err) => IO.pure((acc._1, acc._2 :+ (p -> err)))
        case Right(basePath) =>
          // remove.all: batch delete from the file explorer explicitly covers
          // non-empty directories (F1 acceptance), unlike the single-file
          // deletePath case.
          for
            wasDir <- IO.blocking { os.isDir(basePath) }
            existed <- IO.blocking { os.exists(basePath) }
            res <- IO.blocking { if existed then os.remove.all(basePath) }.attempt
            out <- res match
              // ── 补盲区（同 `deletePath`）：批量删除的**成功分支**留痕 ──────────
              // 同一 `os.remove.all` 语义、同一「目录消失 + 注册残留 = prunable」签名
              // （取证件 §1.2 机制 D-2 / §6.1 第 2 条）。失败分支沿用原「进 failed」
              // 语义，**不误报**成功（本行只在 `Right` 分支执行）；**且**只在路径
              // **实存**时写——不存在的路径是「no-op 成功」（既有语义，见
              // `BatchDeleteSpec`），写 removed 会误报。
              case Right(_) =>
                val canonical =
                  try basePath.toIO.getCanonicalPath
                  catch case _: Throwable => basePath.toString
                val audit =
                  if existed then logger.info(deleteAuditLine("deletePaths", canonical, sessionId, wasDir))
                  else IO.unit
                audit.as((acc._1 :+ p, acc._2))
              case Left(e) => IO.pure((acc._1, acc._2 :+ (p -> Option(e.getMessage).getOrElse(e.toString))))
          yield out
    }

  /** Resolve + guard one delete candidate (mirror of deletePath's checks). */
  private[gateway] def resolveGuardedForDelete(path: String, root: os.Path): Either[String, os.Path] =
    try
      val basePath = PathUtil.resolvePath(path, root)
      val canonicalBase = basePath.toIO.getCanonicalPath
      val canonicalRoot = root.toIO.getCanonicalPath
      if !canonicalBase.startsWith(canonicalRoot) then Left("path outside project root")
      else if canonicalBase == canonicalRoot then Left("cannot delete project root")
      else Right(basePath)
    catch case e: Exception => Left(Option(e.getMessage).getOrElse(e.toString))

  /**
   * Explorer listDir — pure, testable listing core with per-entry fault
   * isolation (2026-09-02). The bare os.isDir/os.size calls used to throw out
   * of the whole listing when a directory contained a dangling symlink (e.g.
   * project .nebflow/flowmap-anim → worktrees/flowmap-anim whose target is
   * gone): one bad entry produced dirListing{error} and the frontend rendered
   * the directory as un-openable. Now each entry stats independently — a
   * stat failure degrades THAT entry to type=file, size=0, broken=true
   * (readFile on it already fails gracefully via fileContent{error}; the
   * extra `broken` field is optional and today's frontend simply ignores it).
   * os.isDir keeps its followLinks semantics; dirs sort first, then name —
   * ordering identical to the pre-fix implementation.
   */
  private[gateway] def listDirEntries(basePath: os.Path): Seq[Json] =
    if os.exists(basePath) && os.isDir(basePath) then
      os.list(basePath)
        .map { p =>
          val stat = scala.util.Try {
            val isDir = os.isDir(p)
            if isDir then (isDir, 0L) else (isDir, os.size(p))
          }.toOption
          stat match
            case Some((isDir, size)) => (p.last, isDir, size, false)
            case None => (p.last, false, 0L, true)
        }
        .sortBy { case (name, isDir, _, _) => (if isDir then 0 else 1, name.toLowerCase) }
        .map { case (name, isDir, size, broken) =>
          io.circe.Json.obj(
            "name" -> name.asJson,
            "type" -> (if isDir then "dir" else "file").asJson,
            "size" -> size.asJson,
            "broken" -> broken.asJson
          )
        }
    else Nil

  /**
   * Resolve + guard + perform one move (mirror of movePath's checks).
   * Returns Right(relative new path) on success; Left(error message) on any
   * guard failure or move failure. Never overwrites an existing destination.
   */
  private[gateway] def movePathSafely(path: String, targetDir: String, root: os.Path): Either[String, String] =
    try
      val basePath = PathUtil.resolvePath(path, root)
      // Empty targetDir = move to the project root itself (resolvePath("")
      // semantics are os-lib-version-sensitive — be explicit).
      val targetBase = if targetDir.isEmpty then root else PathUtil.resolvePath(targetDir, root)
      val canonicalBase = basePath.toIO.getCanonicalPath
      val canonicalTarget = targetBase.toIO.getCanonicalPath
      val canonicalRoot = root.toIO.getCanonicalPath
      // Source and target must both stay inside the project root.
      if !canonicalBase.startsWith(canonicalRoot) then Left("path outside project root")
      else if !canonicalTarget.startsWith(canonicalRoot) then Left("target directory outside project root")
      // Cannot move the project root itself.
      else if canonicalBase == canonicalRoot then Left("cannot move project root")
      // Source must exist; target must be an existing directory.
      else if !os.exists(basePath) then Left("source path not found")
      else if !(os.exists(targetBase) && os.isDir(targetBase)) then Left("target directory not found")
      // Prevent cycles: target must not be the source itself or inside it.
      else if canonicalTarget == canonicalBase || canonicalTarget.startsWith(canonicalBase + java.io.File.separator)
      then Left("cannot move path into itself")
      else
        val newBase = targetBase / basePath.last
        // Never overwrite an existing destination (os.move has no overwrite).
        if os.exists(newBase) then Left("destination already exists")
        else
          os.move(basePath, newBase)
          Right(newBase.relativeTo(root).toString.replace('\\', '/'))
      end if
    catch case e: Exception => Left(Option(e.getMessage).getOrElse(e.toString))

end FsOps
