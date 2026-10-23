package nebflow.gateway

import munit.FunSuite

/**
 * WindowsProcs — the Windows legs of the identity/stop faces (新设备首用批
 * 2026-10-22). Everything here is a PURE function over captured output
 * shapes, pinned with REAL machine samples; the spawn faces
 * (`commandLineViaCim` / `netstatListeningRows`) are pinned as non-Windows
 * no-ops (None) — this spec runs on macOS CI where `isWindows` is false.
 *
 * Fixture provenance: `netstat -ano -p tcp` captured on the author's Windows
 * machine (DESKTOP-IJBBEOT, zh-CN locale Windows 11, 2026-10-22). Note what
 * the real sample teaches:
 *   - the header block is locale-localized (活动连接 / 协议 / 状态) — parsers
 *     must key on TCP rows with the always-English LISTENING state only;
 *   - pid 9112 is the author's live stand gateway: one LISTENING row on
 *     8080 plus several NON-listen rows (ESTABLISHED / CLOSE_WAIT) whose
 *     LOCAL column also contains :8080 — the pid→port parser must not
 *     count those, and the port→pid parser must not mistake the REMOTE
 *     column's :8080 for a listener.
 */
class WindowsProcsSpec extends FunSuite:

  /** Verbatim excerpt of the real capture (whitespace and headers intact). */
  private val RealNetstatSample: Vector[String] = Vector(
    "",
    "活动连接",
    "",
    "  协议  本地地址          外部地址        状态           PID",
    "  TCP    0.0.0.0:135            0.0.0.0:0              LISTENING       2040",
    "  TCP    0.0.0.0:445            0.0.0.0:0              LISTENING       4",
    "  TCP    0.0.0.0:2343           0.0.0.0:0              LISTENING       4832",
    "  TCP    0.0.0.0:5357           0.0.0.0:0              LISTENING       4",
    "  TCP    0.0.0.0:8080           0.0.0.0:0              LISTENING       9112",
    "  TCP    0.0.0.0:49878          0.0.0.0:0              LISTENING       17376",
    "  TCP    100.118.229.97:8080    100.127.149.106:57533  CLOSE_WAIT      9112",
    "  TCP    100.118.229.97:8080    100.127.149.106:62896  ESTABLISHED     9112",
    "  TCP    100.118.229.97:50314   100.127.149.106:8080   ESTABLISHED     9112",
    "  UDP    0.0.0.0:5353           *:*                                    4832"
  )

  // ── pid → ports (StopSupport.pidListeningPorts' Windows leg) ──────────

  test("real sample: the gateway pid listens on exactly 8080 (listen rows only)") {
    assertEquals(WindowsProcs.parseNetstatListenerPorts(RealNetstatSample, 9112L), List(8080))
  }

  test("real sample: second java pid (Xilinx installer) maps to its own port") {
    assertEquals(WindowsProcs.parseNetstatListenerPorts(RealNetstatSample, 17376L), List(49878))
  }

  test("real sample: pid with two listeners yields both, pid absent yields none") {
    assertEquals(WindowsProcs.parseNetstatListenerPorts(RealNetstatSample, 4L), List(445, 5357))
    assertEquals(WindowsProcs.parseNetstatListenerPorts(RealNetstatSample, 2040L), List(135))
    assertEquals(WindowsProcs.parseNetstatListenerPorts(RealNetstatSample, 999999L), List.empty)
  }

  test("non-LISTENING rows never count as listeners (CLOSE_WAIT/ESTABLISHED on :8080)") {
    // The same pid+port pair appears in non-listen rows — must stay List(8080) once.
    val ports = WindowsProcs.parseNetstatListenerPorts(RealNetstatSample, 9112L)
    assertEquals(ports, List(8080))
  }

  test("UDP and malformed rows are ignored") {
    val rows = Vector(
      "  UDP    0.0.0.0:5353           *:*                                    4832",
      "  TCP    0.0.0.0:8080", // truncated row (< 5 cols)
      "garbage line without columns",
      "  TCP    127.0.0.1:9000        0.0.0.0:0              LISTENING       not-a-pid",
      "  TCP    127.0.0.1:9001        0.0.0.0:0              LISTENING       4242"
    )
    assertEquals(WindowsProcs.parseNetstatListenerPorts(rows, 4242L), List(9001))
    assertEquals(WindowsProcs.parseNetstatListenerPorts(rows, 4832L), List.empty)
  }

  test("IPv6 listener shape ([::]:port) parses — documented column form") {
    // Shape pin: this capture host had no IPv6 rows; the form is the
    // documented netstat -ano layout for v6 listeners (state column stays
    // English). lastIndexOf(':') skips the [::] prefix correctly.
    val rows = Vector("  TCP    [::]:8080             [::]:0                 LISTENING       4242")
    assertEquals(WindowsProcs.parseNetstatListenerPorts(rows, 4242L), List(8080))
  }

  // ── port → pids (StaleProcessGuard.portListenerPids' Windows leg) ─────

  test("real sample: only the LISTENING row's pid answers for port 8080") {
    // The capture also holds CLOSE_WAIT/ESTABLISHED rows whose REMOTE column
    // is :8080 (and a foreign-machine remote) — none of those may surface.
    assertEquals(WindowsProcs.parseNetstatListenerPids(RealNetstatSample, 8080), List(9112L))
  }

  test("real sample: port 445 answers pid 4; unlistened port answers none") {
    assertEquals(WindowsProcs.parseNetstatListenerPids(RealNetstatSample, 445), List(4L))
    assertEquals(WindowsProcs.parseNetstatListenerPids(RealNetstatSample, 65500), List.empty)
  }

  test("port match keys on the LOCAL column only (remote :8080 is not a listener)") {
    val rows = Vector(
      "  TCP    10.0.0.2:51000        10.0.0.9:8080          ESTABLISHED     777"
    )
    assertEquals(WindowsProcs.parseNetstatListenerPids(rows, 8080), List.empty)
    assertEquals(WindowsProcs.parseNetstatListenerPids(rows, 51000), List.empty) // not LISTENING
  }

  // ── spawn faces: non-Windows no-op pins (this suite runs on macOS) ────

  test("non-Windows host: CIM and netstat legs are None (never spawn)") {
    if WindowsProcs.isWindows then ()
    else
      assertEquals(WindowsProcs.commandLineViaCim(1L), None)
      assertEquals(WindowsProcs.netstatListeningRows(), None)
      assertEquals(WindowsProcs.listenerPortsOf(1L), List.empty)
      assertEquals(WindowsProcs.listenerPidsOf(8080), List.empty)
  }

end WindowsProcsSpec
