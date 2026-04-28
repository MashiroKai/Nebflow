package nebflow.core

import java.nio.file.Path

object PathSandbox:
  def isAllowed(path: String, projectRoot: String): Boolean =
    try
      if path == null || path.isEmpty then return false
      val pathObj = Path.of(path).toAbsolutePath.normalize
      val root = Path.of(projectRoot).toAbsolutePath.normalize
      // Resolve symlinks: for existing files resolve directly,
      // for new files resolve the parent directory
      val resolved =
        if java.nio.file.Files.exists(pathObj) then pathObj.toRealPath()
        else
          val parent = pathObj.getParent
          if parent != null && java.nio.file.Files.exists(parent) then
            parent.toRealPath().resolve(pathObj.getFileName)
          else pathObj
      val realRoot = root.toRealPath()
      resolved.startsWith(realRoot)
    catch case _: Exception => false
