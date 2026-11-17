// Isolated fixture checkout only; no gateway startup or sbt run task.
Compile / unmanagedSourceDirectories += baseDirectory.value / "src" / "e2e" / "scala"

lazy val e2eClasspath = taskKey[File]("Export the compiled fixture runtime classpath")
e2eClasspath := {
  val entries = (Compile / fullClasspath).value.files
  val output = target.value / "e2e-classpath.txt"
  IO.write(output, entries.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator))
  output
}
