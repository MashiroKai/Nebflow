package nebflow.gateway

class ProjectOpenAppsSpec extends munit.FunSuite:
  test("only a real registered absolute workspace is accepted") {
    val root = java.nio.file.Files.createTempDirectory("open-project-test")
    val other = java.nio.file.Files.createTempDirectory("open-project-other")
    try
      assertEquals(ProjectOpenApps.registeredPath(root.toString, root.toString), Right(root.toRealPath()))
      assert(ProjectOpenApps.registeredPath(other.toString, root.toString).isLeft)
      assert(ProjectOpenApps.registeredPath("relative", root.toString).isLeft)
      assert(ProjectOpenApps.registeredPath(root.resolve("missing").toString, root.toString).isLeft)
    finally
      java.nio.file.Files.delete(root)
      java.nio.file.Files.delete(other)
  }
  test("discovery never returns a fabricated application") {
    assert(!ProjectOpenApps.detect().exists(_.id == "nonexistent-app"))
  }
