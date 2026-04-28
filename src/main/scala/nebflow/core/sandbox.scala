package nebflow.core

import java.nio.file.Path

object PathSandbox:
  def isAllowed(path: String, projectRoot: String): Boolean =
    try
      val resolved = Path.of(path).toAbsolutePath.normalize
      val root = Path.of(projectRoot).toAbsolutePath.normalize
      resolved.startsWith(root)
    catch case _: Exception => false
