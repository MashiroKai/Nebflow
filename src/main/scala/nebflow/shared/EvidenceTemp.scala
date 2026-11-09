package nebflow.shared

import java.nio.file.{Files, LinkOption, Path, StandardCopyOption, StandardOpenOption}
import java.security.MessageDigest
import java.time.{Instant, LocalDate, ZoneOffset}
import java.time.format.DateTimeFormatter
import java.util.{HexFormat, UUID}
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Short-lived evidence isolated from the repository; never treats system temp as durable storage. */
object EvidenceTemp:
  private val Namespace = "stand-evidence"
  private val TtlMillis = 7L * 24 * 60 * 60 * 1000
  private val DateFormat = DateTimeFormatter.BASIC_ISO_DATE
  private val SafeSegment = "[A-Za-z0-9][A-Za-z0-9._-]{0,79}".r

  private def validSegment(value: String): Boolean = value != "." && value != ".." && SafeSegment.matches(value)
  private def tempRoot: Path = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath.normalize()
  private def namespacePath: Path = tempRoot.resolve(Namespace)

  /** Create a unique batch under the process platform temp directory. */
  def createBatch(project: String, task: String): Path =
    require(validSegment(project), "invalid evidence project segment")
    require(validSegment(task), "invalid evidence task segment")
    Files.createDirectories(tempRoot)
    val namespace = namespacePath
    Files.createDirectories(namespace)
    val namespaceReal = namespace.toRealPath()
    val projectRoot = namespaceReal.resolve(project)
    Files.createDirectories(projectRoot)
    val projectReal = projectRoot.toRealPath()
    require(projectReal.getParent == namespaceReal, "evidence project escaped namespace")
    val batch = s"$task-${UUID.randomUUID()}"
    val day = projectReal.resolve(batch).resolve(LocalDate.now(ZoneOffset.UTC).format(DateFormat))
    Files.createDirectories(day)
    val dayReal = day.toRealPath()
    require(dayReal.startsWith(projectReal) && dayReal != projectReal, "evidence batch escaped namespace")
    val manifest = Json.obj("createdAt" -> Instant.now().toString.asJson, "project" -> project.asJson, "batch" -> batch.asJson)
    Files.writeString(dayReal.resolve("manifest.json"), manifest.noSpaces + "\n", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    Files.writeString(dayReal.resolve(".active"), "active\n", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    dayReal

  /** Mark a completed batch inactive. A missing/invalid manifest is never eligible for sweep. */
  def finishBatch(batchDir: Path): Unit =
    val real = checkedBatch(batchDir)
    Files.deleteIfExists(real.resolve(".active"))

  /** Explicit, caller-authorized promotion; a sibling JSON records provenance and integrity. */
  def promote(batchDir: Path, relativeName: String, destination: Path, retentionReason: String): Path =
    require(retentionReason.trim.nonEmpty, "retention reason is required")
    val source = checkedFile(batchDir, relativeName)
    val batch = checkedBatch(batchDir)
    val target = destination.toAbsolutePath.normalize()
    require(!target.startsWith(namespacePath), "promotion destination must be durable and outside temp evidence")
    Option(target.getParent).foreach(p => Files.createDirectories(p))
    Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES)
    val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(target)))
    val metadata = Json.obj(
      "sourceBatch" -> batch.getParent.getFileName.toString.asJson,
      "sourceProject" -> batch.getParent.getParent.getFileName.toString.asJson,
      "sourceFile" -> source.getFileName.toString.asJson,
      "sha256" -> digest.asJson,
      "promotedAt" -> Instant.now().toString.asJson,
      "retentionReason" -> retentionReason.trim.asJson
    )
    Files.writeString(Path.of(target.toString + ".meta.json"), metadata.noSpaces + "\n", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    target

  private def checkedBatch(batchDir: Path): Path =
    val base = batchDir.toAbsolutePath.normalize().toRealPath()
    val root = namespacePath.toRealPath()
    require(base.startsWith(root) && base != root, "batch outside evidence namespace")
    require(Files.isRegularFile(base.resolve("manifest.json"), LinkOption.NOFOLLOW_LINKS), "batch manifest missing")
    base

  private def checkedFile(batchDir: Path, relativeName: String): Path =
    val rel = Path.of(relativeName)
    require(!rel.isAbsolute && !rel.iterator().asScala.exists(_.toString == ".."), "invalid evidence relative path")
    val base = checkedBatch(batchDir)
    val target = base.resolve(rel).normalize()
    require(target.startsWith(base) && target != base, "evidence path escaped batch")
    val real = target.toRealPath()
    require(real.startsWith(base) && Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS), "evidence target is not a contained regular file")
    real

  /** Best-effort, fail-closed cleanup of old, inactive batches with valid manifests. */
  def sweep(nowMillis: Long = System.currentTimeMillis()): Int =
    val namespace = namespacePath
    if !Files.isDirectory(namespace, LinkOption.NOFOLLOW_LINKS) then 0
    else
      val root = namespace.toRealPath()
      val stream = Files.list(root)
      try stream.iterator().asScala.filter(p => Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).map { project =>
        try
          val projectReal = project.toRealPath()
          if projectReal.getParent != root then 0
          else
            val batches = Files.list(projectReal)
            try batches.iterator().asScala.map { batch =>
              try
                if !Files.isDirectory(batch, LinkOption.NOFOLLOW_LINKS) then 0
                else
                  val batchReal = batch.toRealPath()
                  if batchReal.getParent != projectReal then 0
                  else
                    val dates = Files.list(batchReal)
                    try dates.iterator().asScala.map { date =>
                      try
                        if !Files.isDirectory(date, LinkOption.NOFOLLOW_LINKS) || Files.exists(date.resolve(".active"), LinkOption.NOFOLLOW_LINKS) then 0
                        else
                          val dateReal = date.toRealPath()
                          val manifest = dateReal.resolve("manifest.json")
                          if dateReal.getParent != batchReal || !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS) then 0
                          else
                            val created = parse(Files.readString(manifest)).toOption.flatMap(_.hcursor.get[String]("createdAt").toOption).flatMap(s => scala.util.Try(Instant.parse(s).toEpochMilli).toOption)
                            created.filter(t => t <= nowMillis - TtlMillis && t <= nowMillis).map { _ => deleteTree(dateReal); 1 }.getOrElse(0)
                      catch case NonFatal(_) => 0
                    }.sum
                    finally dates.close()
              catch case NonFatal(_) => 0
            }.sum
            finally batches.close()
        catch case NonFatal(_) => 0
      }.sum
      finally stream.close()

  private def deleteTree(root: Path): Unit =
    val walk = Files.walk(root)
    try walk.iterator().asScala.toList.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))
    finally walk.close()
end EvidenceTemp
