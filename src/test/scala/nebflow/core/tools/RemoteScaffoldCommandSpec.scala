package nebflow.core.tools

class RemoteScaffoldCommandSpec extends munit.FunSuite:

  test("missing python fails closed before executing payload") {
    val tmp = java.nio.file.Files.createTempDirectory("scaffold-command-")
    val output = tmp.resolve("payload-ran")
    val script =
      s"open(${output.toString.replace("\\", "\\\\").replace("'", "\\'").prepended('\'').appended('\'')},'w').write('bad')"
    val encoded = java.util.Base64.getEncoder.encodeToString(script.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    val command = RemoteScaffoldCommand.build(encoded, tmp.toString, "test-device")
    val process = new ProcessBuilder("/bin/sh", "-c", command)
    process.environment().put("PATH", tmp.toString)
    val running = process.redirectErrorStream(true).start()
    val result = new String(running.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    assertEquals(running.waitFor(), 0)
    assert(result.contains("REMOTE_SCAFFOLD_PYTHON_UNAVAILABLE"), result)
    assert(!java.nio.file.Files.exists(output))
    println(s"PREFLIGHT: ${result.trim}; payload-ran=false")
    java.nio.file.Files.delete(tmp)
  }
end RemoteScaffoldCommandSpec
