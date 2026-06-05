package nebflow

object Version:

  val string: String =
    // Try classpath first (VERSION is bundled in JAR), then filesystem fallback
    try
      Option(getClass.getClassLoader.getResourceAsStream("VERSION")) match
        case Some(is) =>
          try
            val content = scala.io.Source.fromInputStream(is, "UTF-8").mkString.trim
            if content.nonEmpty then content else sys.props.getOrElse("nebflow.version", "dev")
          finally is.close()
        case None =>
          val v = java.nio.file.Files.readString(java.nio.file.Paths.get("VERSION")).trim
          if v.nonEmpty then v else "dev"
    catch
      case _: Exception =>
        sys.props.getOrElse("nebflow.version", "dev")
end Version
