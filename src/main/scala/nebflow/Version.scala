package nebflow

import scala.util.Using
import java.nio.file.Files
import java.nio.file.Paths

object Version:
  val string: String =
    try
      Using(Files.newBufferedReader(Paths.get("VERSION"))) { reader =>
        reader.readLine().trim
      }.getOrElse("dev")
    catch
      case _: Exception => "dev"
end Version
