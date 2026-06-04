package nebflow

import java.nio.file.{Files, Paths}

import scala.util.Using

object Version:

  val string: String =
    try
      Using(Files.newBufferedReader(Paths.get("VERSION"))) { reader =>
        reader.readLine().trim
      }.getOrElse("dev")
    catch case _: Exception => "dev"
end Version
