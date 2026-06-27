package nebflow.mesh

import cats.effect.unsafe.implicits.global
import io.circe.syntax.*
import munit.CatsEffectSuite

/**
 * Tests for the cross-device latency optimization:
 *   - MeshError classification (group D)
 *   - RelayStore relay submit/poll/result lifecycle (group B backing)
 *   - MeshConfig cloudUrl preservation on login (group C bug fix)
 */
class RelayOptimizationSpec extends CatsEffectSuite:

  // ===== MeshError classification (group D) =====

  test("MeshError: 'Cloud URL not configured' → CloudUrlNotConfigured") {
    val err = MeshError.fromThrowable(new RuntimeException("Cloud URL not configured"))
    assertEquals(err, MeshError.CloudUrlNotConfigured)
    assertEquals(err.code, "cloud_url_missing")
  }

  test("MeshError: bad credentials → AuthFailed") {
    // callCloud wraps server errors as "Cloud error 500: Invalid username or password"
    val err = MeshError.fromThrowable(
      new RuntimeException("Cloud error 500: Invalid username or password")
    )
    assert(err.isInstanceOf[MeshError.AuthFailed], s"expected AuthFailed, got $err")
    assertEquals(err.code, "auth_failed")
    // Message should preserve the inner server message
    assert(err.message.contains("Invalid username or password"), s"got: ${err.message}")
  }

  test("MeshError: connection refused → NetworkError") {
    val err = MeshError.fromThrowable(new RuntimeException("Connection refused"))
    assert(err.isInstanceOf[MeshError.NetworkError], s"expected NetworkError, got $err")
    assertEquals(err.code, "network_error")
  }

  test("MeshError: Cloud API 503 → CloudError with inner message extracted") {
    val err = MeshError.fromThrowable(new RuntimeException("Cloud API 503: Service Unavailable"))
    assert(err.isInstanceOf[MeshError.CloudError], s"expected CloudError, got $err")
    assertEquals(err.code, "cloud_error")
    // extractInner strips the "Cloud API 503: " prefix
    assertEquals(err.message, "Service Unavailable")
  }

  test("MeshError: unknown error falls back to NetworkError") {
    val err = MeshError.fromThrowable(new RuntimeException("something weird"))
    assert(err.isInstanceOf[MeshError.NetworkError])
  }

  test("MeshError: null message handled gracefully") {
    val err = MeshError.fromThrowable(new RuntimeException())
    // null message → uses class simple name
    assert(err.message.nonEmpty)
  }

  // ===== MeshConfig cloudUrl preservation (group C bug fix) =====

  test("MeshConfig: round-trip preserves cloudUrl (was wiped by old startDiscovery)") {
    val cfg = MeshConfig(enabled = true, cloudUrl = Some("http://localhost:9090/api/mesh"))
    val json = cfg.asJson.spaces2
    val decoded = io.circe.parser.decode[MeshConfig](json).toOption
    assertEquals(decoded, Some(cfg), "cloudUrl must survive round-trip")
    assertEquals(decoded.flatMap(_.cloudUrl), Some("http://localhost:9090/api/mesh"))
  }

  test("MeshConfig: syncIntervalSec is configurable and persists") {
    // The group-C fix made the sync actor read this instead of a hardcoded 5min.
    val cfg = MeshConfig(enabled = true, syncIntervalSec = 30)
    val json = cfg.asJson.spaces2
    val decoded = io.circe.parser.decode[MeshConfig](json).toOption
    assertEquals(decoded.map(_.syncIntervalSec), Some(30))
  }

  // ===== RelayStore lifecycle (backing for group B) =====

  test("RelayStore: submit → poll → result → fetch-result full cycle") {
    // Use a real RelayStore against a temp dir — verifies the relay queue that
    // RelayService.submitAndWait + the background poller depend on.
    val tmpDir = os.pwd / "target" / "test-relay-store"
    os.makeDir.all(tmpDir)
    (for
      store <- nebflow.server.RelayStore.create(tmpDir)
      // register two devices under one user
      _ <- store.registerDevice("user1", "devA", "DeviceA", "macos", "http://a", Map.empty, "", "secretA")
      _ <- store.registerDevice("user1", "devB", "DeviceB", "linux", "http://b", Map.empty, "", "secretB")
      // devA submits a command to devB
      submit <- store.relaySubmit("user1", "devA", "devB", "Bash", io.circe.Json.obj("command" -> "echo hi".asJson))
      relayId = submit.hcursor.downField("relayId").as[String].toOption
      _ = assert(relayId.isDefined, "submit must return a relayId")
      // devB polls — should receive the pending command
      polled <- store.relayPoll("user1", "devB")
      commands = polled.hcursor.downField("commands").as[List[io.circe.Json]].getOrElse(Nil)
      _ = assert(commands.length == 1, s"devB should poll 1 command, got ${commands.length}")
      _ = assert(
        commands.head.hcursor.downField("action").as[String].contains("Bash"),
        "polled command action should be Bash"
      )
      // devB posts the result
      rid = relayId.get
      _ <- store.relayResult(rid, "hi\n", "")
      // devA fetches the result
      fetched <- store.relayFetchResult(rid)
      status = fetched.hcursor.downField("status").as[String].getOrElse("")
      _ = assert(status == "done", s"result status should be done, got $status")
      _ = assert(
        fetched.hcursor.downField("result").as[String].contains("hi\n"),
        "fetched result should contain 'hi'"
      )
    yield ()).unsafeRunSync()

    os.remove.all(tmpDir)
  }

  test("RelayStore: lookupDevices returns same-account peers with deviceSecret (group A data)") {
    val tmpDir = os.pwd / "target" / "test-relay-lookup"
    os.makeDir.all(tmpDir)
    (for
      store <- nebflow.server.RelayStore.create(tmpDir)
      _ <- store.registerDevice("user1", "devA", "DeviceA", "macos", "http://a", Map.empty, "", "secretA")
      _ <- store.registerDevice(
        "user1",
        "devB",
        "DeviceB",
        "linux",
        "http://b",
        Map("python" -> "/usr/bin/python3"),
        "build box",
        "secretB"
      )
      // devA looks up its peers (excluding itself)
      r <- store.lookupDevices("user1", excludeDeviceId = Some("devA"))
      peers = r.hcursor.downField("peers").as[List[io.circe.Json]].getOrElse(Nil)
      _ = assert(peers.length == 1, s"devA should see 1 peer, got ${peers.length}")
      peer = peers.head
      _ = assert(peer.hcursor.downField("deviceId").as[String].contains("devB"))
      _ = assert(
        peer.hcursor.downField("deviceSecret").as[String].contains("secretB"),
        "peer must include deviceSecret so P2P auth (verifyPeerToken) works"
      )
      _ = assert(peer.hcursor.downField("userDescription").as[String].contains("build box"))
    yield ()).unsafeRunSync()

    os.remove.all(tmpDir)
  }

end RelayOptimizationSpec
