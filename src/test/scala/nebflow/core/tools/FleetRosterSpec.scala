package nebflow.core.tools

import munit.FunSuite
import nebflow.core.device.DeviceEntry
import nebflow.shared.PeerInfo

/**
 * FleetRoster 单测（台账 #913 ①，2026-10-26）——`# Devices` 花名册渲染纯函数面：
 *   - 本机行：名称/id短码/platform/版本/能力面（键名级）+ 描述；
 *   - 在线对端行：online 标 + 画像摘要（DeviceProfile 既有渲染面）+ caps 键名；
 *   - 离线已知行：登记表/画像在册但 presence 不在 ⇒ offline 标（诚实显示未知）；
 *   - 去重：presence 在场设备绝不重复出现在离线段；
 *   - 截断：行数超上限截断 + 权威面指针（上下文预算纪律）；
 *   - 提示行二态：enforce=false 既有文案逐字 / enforce=true 强制措辞。
 */
class FleetRosterSpec extends FunSuite:

  private val now = 1_700_000_000_000L

  private val local = FleetRoster.LocalDevice(
    deviceId = "40742cd4-851a-4ec4-a49d-6db2509bf03d",
    deviceName = "KAI",
    userDescription = "主力机",
    platform = "windows",
    capabilities = Map("node" -> "C:\\x\\node.exe", "git" -> "C:\\x\\git.exe"),
    version = "2026.10.26-beta.1"
  )

  private val mac = PeerInfo(
    deviceId = "dd8cfd22-c180-43a5-817c-a2ceae4da09f",
    deviceName = "Mac",
    platform = "macos",
    address = "http://127.0.0.1:7",
    capabilities = Map("java" -> "/usr/bin/java")
  )

  private def profileFor(id: String, cwd: String) = DeviceProfileEntry(
    deviceId = id,
    deviceName = "X",
    platform = "",
    addressKey = "",
    probedAt = now,
    probeVersion = DeviceProfile.SchemaVersion,
    available = true,
    fields = Map("cwd" -> ProfileField(cwd, now))
  )

  test("render: local row carries name/id-short/platform/version/caps keys/description"):
    val out = FleetRoster.render(Some(local), Nil, Nil, Map.empty, now, enforce = false)
    assert(out.contains("KAI (40742cd4) [local · v2026.10.26-beta.1 · windows]"), out)
    assert(out.contains("- 主力机"), out)
    assert(out.contains("caps: git,node"), "caps listed as sorted KEYS (never values)")
    assert(!out.contains("node.exe") && !out.contains("git.exe"), "capability VALUES (absolute paths) must not leak into the roster")

  test("render: online peer row carries online tag, platform and caps keys"):
    val out = FleetRoster.render(Some(local), List(mac), Nil, Map.empty, now, enforce = false)
    assert(out.contains("Mac (dd8cfd22) [online · macos]"), out)
    assert(out.contains("caps: java"), out)

  test("render: offline-known rows appear with offline tag; presence-present ids never duplicated"):
    val out = FleetRoster.render(
      Some(local),
      List(mac),
      List(
        FleetRoster.KnownOffline("dd8cfd22-c180-43a5-817c-a2ceae4da09f", "Mac", "macos"), // 在线，须去重
        FleetRoster.KnownOffline("aaaabbbb-0000-0000-0000-000000000000", "OldBox", "linux")
      ),
      Map.empty,
      now,
      enforce = false
    )
    assertEquals(out.linesIterator.count(_.contains("Mac (")), 1, "presence-present device must not repeat as offline row")
    assert(out.contains("OldBox (aaaabbbb) [offline · linux]"), out)

  test("render: profile summary still rides the peer row (existing DeviceProfile face unchanged)"):
    val out = FleetRoster.render(
      Some(local),
      List(mac),
      Nil,
      Map(mac.deviceId -> profileFor(mac.deviceId, "/Users/kaiyu/proj")),
      now,
      enforce = false
    )
    assert(out.contains("cwd=/Users/kaiyu/proj"), out)

  test("render: truncation caps rows and points at the authoritative full-roster face"):
    val peers = (1 to 30).map { n =>
      PeerInfo(deviceId = f"dev-$n%04d", deviceName = f"DEV$n%02d", platform = "linux", address = "x")
    }.toList
    val out = FleetRoster.render(Some(local), peers, Nil, Map.empty, now, enforce = false)
    val deviceRows = out.linesIterator.count(l => l.contains(" [online · ") || l.contains(" [local"))
    assert(deviceRows <= FleetRoster.MaxRows, s"rows capped at ${FleetRoster.MaxRows}, got $deviceRows")
    assert(out.contains("more device(s) not shown"), out)
    assert(out.contains("GET /api/neblink/status"), out)

  test("render: hint line OFF-state is the existing verbatim copy"):
    val out = FleetRoster.render(Some(local), List(mac), Nil, Map.empty, now, enforce = false)
    assert(
      out.contains("\nRead/Write/Edit/Glob/Grep/Bash accept a `device` parameter to run on another machine; every other tool always runs locally."),
      out
    )

  test("render: hint line ON-state carries the REQUIRED wording (engine-refusal same source)"):
    val out = FleetRoster.render(Some(local), List(mac), Nil, Map.empty, now, enforce = true)
    assert(out.contains("fleet.enforceExplicitDevice is ON"), out)
    assert(out.contains("REQUIRE an explicit `device` parameter"), out)
    assert(!out.contains("Defaults to local device if omitted"), "default-local wording must not survive enforcement")

  test("render: empty roster (no local, no peers, no known) renders empty — section not injected"):
    assertEquals(FleetRoster.render(None, Nil, Nil, Map.empty, now, enforce = false), "")

  test("knownOfflineFromRegistry: registry entries map to offline rows; empty name falls back to id"):
    val rows = FleetRoster.knownOfflineFromRegistry(
      List(DeviceEntry("id-1", "PairOne", "secret", createdAt = 1L), DeviceEntry("id-2", "", "secret", createdAt = 2L))
    )
    assertEquals(rows.map(_.deviceName), List("PairOne", "id-2"))

end FleetRosterSpec
