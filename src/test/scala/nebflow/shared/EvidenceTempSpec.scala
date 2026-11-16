package nebflow.shared

import java.nio.file.{Files, Path}
import java.time.Instant
import scala.jdk.CollectionConverters.*
import munit.FunSuite

class EvidenceTempSpec extends FunSuite:
  private var originalTemp: String = ""
  private var isolatedTemp: Path = Path.of(".")

  override def beforeEach(context: BeforeEach): Unit =
    originalTemp = System.getProperty("java.io.tmpdir")
    isolatedTemp = Files.createTempDirectory("evidence-isolated-test-")
    System.setProperty("java.io.tmpdir", isolatedTemp.toString)

  override def afterEach(context: AfterEach): Unit =
    System.setProperty("java.io.tmpdir", originalTemp)
    removeTree(isolatedTemp)

  test("shared legacy namespace is never swept") {
    val foreign = isolatedTemp.resolve("stand-evidence").resolve("foreign").resolve("batch").resolve("20200101")
    Files.createDirectories(foreign)
    Files.writeString(foreign.resolve("manifest.json"),
      s"""{"createdAt":"${Instant.EPOCH}","project":"foreign","batch":"batch"}""")
    Files.writeString(foreign.resolve("payload"), "other process")
    assertEquals(EvidenceTemp.sweep(System.currentTimeMillis()), 0)
    assertEquals(Files.readString(foreign.resolve("payload")), "other process")
  }

  test("sweep refuses manifest belonging to another project or process") {
    val a = EvidenceTemp.createBatch("owner", "wrong-project")
    val b = EvidenceTemp.createBatch("owner", "wrong-process")
    EvidenceTemp.finishBatch(a)
    EvidenceTemp.finishBatch(b)
    val manifestA = io.circe.parser.parse(Files.readString(a.resolve("manifest.json"))).toOption.get
    val manifestB = io.circe.parser.parse(Files.readString(b.resolve("manifest.json"))).toOption.get
    Files.writeString(a.resolve("manifest.json"), manifestA.mapObject(_.add("project", io.circe.Json.fromString("other"))).noSpaces)
    Files.writeString(b.resolve("manifest.json"), manifestB.mapObject(_.add("owner", io.circe.Json.fromString("other"))).noSpaces)
    assertEquals(EvidenceTemp.sweep(System.currentTimeMillis() + 8L * 24 * 60 * 60 * 1000), 0)
    assert(Files.exists(a) && Files.exists(b))
  }

  private def removeTree(path: Path): Unit =
    if Files.exists(path) then
      val walk = Files.walk(path)
      try walk.iterator().asScala.toList.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))
      finally walk.close()

  test("sweep removes a symlink without traversing its external target") {
    val batch = EvidenceTemp.createBatch("symlink-test", "sweep")
    val outside = Files.createTempDirectory(isolatedTemp, "outside-")
    Files.writeString(outside.resolve("keep"), "safe")
    Files.createSymbolicLink(batch.resolve("link"), outside)
    EvidenceTemp.finishBatch(batch)
    assertEquals(EvidenceTemp.sweep(System.currentTimeMillis() + 8L * 24 * 60 * 60 * 1000), 1)
    assertEquals(Files.readString(outside.resolve("keep")), "safe")
  }

  test("create batch and explicitly promote with provenance and checksum metadata") {
    val batch = EvidenceTemp.createBatch("unit-test", "promote")
    val source = batch.resolve("proof.txt")
    Files.writeString(source, "evidence-payload")
    val destination = Files.createTempDirectory("evidence-promote-test-").resolve("kept.txt")
    try
      val result = EvidenceTemp.promote(batch, "proof.txt", destination, "retain for regression review")
      assertEquals(Files.readString(result), "evidence-payload")
      val meta = io.circe.parser.parse(Files.readString(Path.of(result.toString + ".meta.json"))).toOption.get
      val c = meta.hcursor
      assert(c.get[String]("sourceBatch").toOption.exists(_.startsWith("promote-")))
      assertEquals(c.get[String]("sourceProject").toOption, Some("unit-test"))
      assertEquals(c.get[String]("sha256").toOption.map(_.length), Some(64))
      assert(c.get[String]("promotedAt").toOption.exists(s => scala.util.Try(Instant.parse(s)).isSuccess))
      assertEquals(c.get[String]("retentionReason").toOption, Some("retain for regression review"))
    finally
      removeTree(batch.getParent.getParent)
      removeTree(destination.getParent)
  }

  test("sweep deletes only expired, inactive batches with valid manifests") {
    val batch = EvidenceTemp.createBatch("unit-sweep", "expired")
    Files.writeString(batch.resolve("payload.txt"), "x")
    EvidenceTemp.finishBatch(batch)
    assertEquals(EvidenceTemp.sweep(System.currentTimeMillis() + 8L * 24 * 60 * 60 * 1000), 1)
    assert(!Files.exists(batch))
  }

  test("sweep is fail-closed: an expired, inactive batch WITHOUT a manifest is never reclaimed") {
    // A missing manifest is never eligible even inside the owned root.
    val name = s"unit-failclosed-${java.util.UUID.randomUUID()}"
    val orphan = EvidenceTemp.createBatch(name, "no-manifest")
    val reclaimable = EvidenceTemp.createBatch(name, "with-manifest")
    val project = orphan.getParent.getParent
    try
      EvidenceTemp.finishBatch(orphan)
      EvidenceTemp.finishBatch(reclaimable)
      Files.delete(orphan.resolve("manifest.json"))
      Files.writeString(orphan.resolve("payload.txt"), "orphan")
      Files.writeString(reclaimable.resolve("payload.txt"), "reclaimable")
      EvidenceTemp.sweep(System.currentTimeMillis() + 8L * 24 * 60 * 60 * 1000)
      assert(
        Files.exists(orphan.resolve("payload.txt")),
        "manifest-less namespace dirs must be skipped (fail-closed), never deleted"
      )
      assert(!Files.exists(reclaimable), "contrast: the sibling with a manifest IS reclaimed past TTL")
    finally removeTree(project)
    end try
  }

  test("rejects path traversal and missing retention reason") {
    val batch = EvidenceTemp.createBatch("unit-security", "reject")
    try
      intercept[IllegalArgumentException](
        EvidenceTemp.promote(batch, "../manifest.json", Path.of("/tmp/evidence-invalid"), "reason")
      )
      intercept[IllegalArgumentException](
        EvidenceTemp.promote(batch, "manifest.json", Path.of("/tmp/evidence-invalid"), "  ")
      )
    finally removeTree(batch.getParent.getParent)
  }

  test("producer lifecycle: active batches survive sweeps, finished ones age out, promotions persist") {
    val project = s"unit-lifecycle-${java.util.UUID.randomUUID()}"
    val active = EvidenceTemp.createBatch(project, "still-active")
    Files.writeString(active.resolve("draft.txt"), "in flight")
    val finished = EvidenceTemp.createBatch(project, "retired")
    EvidenceTemp.finishBatch(finished)
    val promoted = EvidenceTemp.createBatch(project, "promoted")
    Files.writeString(promoted.resolve("shot.png"), "png-bytes")
    EvidenceTemp.finishBatch(promoted)
    val destination = Files.createTempDirectory("evidence-lifecycle-").resolve("kept.png")
    try
      EvidenceTemp.promote(promoted, "shot.png", destination, "retained for incident review")
      val removed = EvidenceTemp.sweep(System.currentTimeMillis() + 8L * 24 * 60 * 60 * 1000)
      // An active batch (no finishBatch) is never swept — fail-closed even past TTL.
      assert(Files.exists(active.resolve("draft.txt")))
      // A finished batch past TTL is removed.
      assert(!Files.exists(finished))
      // A promotion outside the temp namespace survives its source batch's expiry.
      assertEquals(Files.readString(destination), "png-bytes")
      assert(Files.exists(Path.of(destination.toString + ".meta.json")))
      assert(removed >= 2)
    finally
      removeTree(active.getParent.getParent)
      removeTree(destination.getParent)
  }
end EvidenceTempSpec
