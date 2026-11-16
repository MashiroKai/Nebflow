package nebflow.core

import munit.CatsEffectSuite
import java.nio.file.Files
import scala.sys.process.*

class ManifestGateSpec extends CatsEffectSuite:
  private def fixture(checker: Boolean, manifest: Boolean): String =
    val dir = Files.createTempDirectory("manifest-gate-spec").toFile
    def run(args: String*): Unit = assertEquals(Process(args, dir).!, 0)
    run("git", "init", "-q")
    run("git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "--allow-empty", "-qm", "fixture")
    val wt = dir.toPath.resolve("linked")
    run("git", "worktree", "add", "-q", "-b", "test", wt.toString)
    if checker then
      Files.createDirectories(wt.resolve("scripts"))
      Files.writeString(wt.resolve("scripts/check-delivery.py"),
        if manifest then "print('valid')\n" else "import sys; print('missing MANIFEST.md'); sys.exit(1)\n")
    wt.toString

  for path <- List("node", "delegate", "recovery") do
    test(s"$path compliant manifest passes") {
      ManifestGate.check(fixture(true, true), None).map(r => assert(r.isRight, r.toString))
    }
    test(s"$path missing manifest is rejected") {
      ManifestGate.check(fixture(true, false), None).map(r => assert(r.isLeft, r.toString))
    }
  test("remote channel unavailable fails closed") {
    ManifestGate.check("/remote/workspace", Some("unavailable-device")).map(r => assert(r.isLeft))
  }
  test("missing checker fails closed") {
    ManifestGate.check(fixture(false, false), None).map(r => assert(r.isLeft))
  }
  test("unknown checkout fails closed") {
    ManifestGate.check("/definitely/missing/checkout", None).map(r => assert(r.isLeft))
  }
