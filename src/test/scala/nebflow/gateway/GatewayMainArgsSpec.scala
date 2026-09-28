package nebflow.gateway

import cats.effect.ExitCode
import cats.effect.unsafe.implicits.global
import munit.FunSuite

/** P0 2026-09-06 (09:07 host-kill incident): GatewayMain never parsed argv —
  * `java nebflow.gateway.GatewayMain --port 8097` silently dropped the flag,
  * fell back to port 8080, and the startup port-clear hit the live host.
  * The arg gate must fail FAST (no boot, no port probing) and exit non-zero.
  */
class GatewayMainArgsSpec extends FunSuite:

  test("the incident command shape (--port 8097) fails fast with a non-zero exit"):
    val t0 = System.currentTimeMillis()
    val code = GatewayMain.run(List("--port", "8097")).unsafeRunSync()
    val elapsed = System.currentTimeMillis() - t0
    assertEquals(code, ExitCode.Error, "must exit non-zero")
    // A boot would take seconds-to-forever (and probe ports); the gate is instant.
    assert(elapsed < 10_000, s"must fail fast without booting (took ${elapsed}ms)")

  test("any argument shape is rejected — --home, start, -s, --server"):
    List(
      List("--home", "/tmp/x"),
      List("start"),
      List("-s"),
      List("--server"),
      List("--port", "8097", "--home", "/tmp/x")
    ).foreach { args =>
      assertEquals(GatewayMain.run(args).unsafeRunSync(), ExitCode.Error, s"args=$args")
    }

end GatewayMainArgsSpec
