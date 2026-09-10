package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.neblink.{FriendSummary, PeerInfo}

/**
 * TransferFile 工具面收敛批①（剔 `local` + 目标前缀化）单测。
 *
 * 钉死四类硬报错（无前缀 / `local:` / 裸绝对路径 / 未知目标）+ 两个成功路径
 * （`device:` 设备→设备路由 / `friend:` 好友路由到显式 unsupported）+ 三要素报文
 * （原因回显 + 正确用法 + 可用目标候选）+ `resolveDevice` 歧义候选（旧行为是静默
 * 首命中）+ schema/required 契约。
 *
 * 纯逻辑为主（前缀解析 / 候选 / 报文 / 路由规划），全部可注入 peers/friends 列表，
 * 不起实例、不碰网络。**唯一不在本 spec 覆盖**的是真正搬字节的设备↔设备链路
 * （P2P HTTP + relay 回退），其 E2E 归批② `scripts/e2e-neblink-device.mjs` harness。
 */
class TransferFileToolSpec extends CatsEffectSuite:

  // ── fixtures ─────────────────────────────────────────────

  private val macBook = PeerInfo(deviceId = "3f9c1a77-aaaa", deviceName = "MacBook-Air", platform = "macos", address = "100.64.0.1")
  private val macMini = PeerInfo(deviceId = "9b22ee41-bbbb", deviceName = "MacMini", platform = "macos", address = "100.64.0.2")
  private val kaiPc = PeerInfo(deviceId = "c07d5a10-cccc", deviceName = "kai-pc", platform = "windows", address = "100.64.0.3")
  private val peers = List(macBook, macMini, kaiPc)

  private val friends = List(
    FriendSummary(userId = "u1", username = "lin@example.com", displayName = "林小满"),
    FriendSummary(userId = "u2", username = "wangxuan", displayName = "王选")
  )

  private def ctxNoNeblink = ToolContext(projectRoot = "/tmp/nb-transferfile-spec")

  private def input(fields: (String, String)*): JsonObject =
    JsonObject.fromIterable(fields.map { case (k, v) => k -> v.asJson }.toList)

  private def errOf(r: Either[ToolError, String]): String =
    r.left.toOption.map(_.message).getOrElse(fail(s"expected Left, got Right(${r.toOption.get})"))

  // ── 前缀解析：合法前缀 ────────────────────────────────────

  test("parseTarget: device: prefix (name) resolves to Device ref") {
    assertEquals(TransferFileTool.parseTarget("device:MacBook-Air"), Right(TransferFileTool.TargetRef.Device("MacBook-Air")))
  }

  test("parseTarget: device: prefix (deviceId) trims surrounding whitespace") {
    assertEquals(TransferFileTool.parseTarget("  device: 3f9c1a77-aaaa "), Right(TransferFileTool.TargetRef.Device("3f9c1a77-aaaa")))
  }

  test("parseTarget: friend: prefix resolves to Friend ref") {
    assertEquals(TransferFileTool.parseTarget("friend:wangxuan"), Right(TransferFileTool.TargetRef.Friend("wangxuan")))
  }

  // ── 报错类 ① 无前缀（裸名） ───────────────────────────────

  test("error class 1: bare device name (no prefix) is a hard error echoing the input") {
    val e = TransferFileTool.parseTarget("Kai-Laptop").left.toOption.getOrElse(fail("expected Left"))
    assert(e.contains("'Kai-Laptop' has no prefix"), e)
    assert(e.contains("`device:`"), e)
    assert(e.contains("`friend:`"), e)
  }

  test("error class 1: no prefix is never accepted as a device query even when one peer exists") {
    // 单设备名册 + 裸名 ⇒ 仍必须报错（不得「猜前缀」/不得回落默认设备）
    assert(TransferFileTool.parseTarget("MacMini").isLeft)
  }

  // ── 报错类 ② `local:` ────────────────────────────────────

  test("error class 2: `local:` prefix is a hard error (prefix removed)") {
    val e = TransferFileTool.parseTarget("local:/tmp/a.txt").left.toOption.getOrElse(fail("expected Left"))
    assert(e.contains("uses the removed `local` prefix"), e)
    assert(e.contains("project node"), e)
  }

  test("error class 2: `LOCAL:` is rejected case-insensitively") {
    assert(TransferFileTool.parseTarget("LOCAL:MacMini").left.toOption.exists(_.contains("removed `local` prefix")))
  }

  // ── 报错类 ③ 裸绝对路径 ───────────────────────────────────

  test("error class 3: bare absolute path is a hard error with an accurate reason") {
    val e = TransferFileTool.parseTarget("/tmp/a.txt").left.toOption.getOrElse(fail("expected Left"))
    assert(e.contains("looks like a local filesystem path"), e)
    assert(e.contains("project node"), e)
  }

  test("error class 3: ~ / ./ / ../ paths are rejected as local paths") {
    List("~/docs/x.md", "./x.md", "../x.md").foreach { p =>
      assert(
        TransferFileTool.parseTarget(p).left.toOption.exists(_.contains("looks like a local filesystem path")),
        s"expected local-path rejection for $p"
      )
    }
  }

  test("error class 3: Windows drive path is rejected as a local path") {
    val e = TransferFileTool.parseTarget("C:\\Users\\kai\\x.txt").left.toOption.getOrElse(fail("expected Left"))
    assert(e.contains("Windows drive path"), e)
  }

  // ── 前缀形状的其余硬报错 ─────────────────────────────────

  test("unknown prefix and empty payloads are hard errors") {
    assert(TransferFileTool.parseTarget("nas:backup").left.toOption.exists(_.contains("unsupported prefix `nas:`")))
    assert(TransferFileTool.parseTarget("device:").left.toOption.exists(_.contains("missing the device name/id")))
    assert(TransferFileTool.parseTarget("friend:").left.toOption.exists(_.contains("missing the username/displayName")))
    assert(TransferFileTool.parseTarget("   ").left.toOption.exists(_.contains("value is empty")))
  }

  // ── 报错类 ④ 未知目标 + resolveDevice 既有 not-found 文案 ──

  test("error class 4: unknown device target keeps the legacy not-found wording") {
    val e = TransferFileTool.resolveDevice("ghost", peers).left.toOption.getOrElse(fail("expected Left"))
    assertEquals(e.message, "Device 'ghost' not found among 3 peer(s). Available: MacBook-Air, MacMini, kai-pc")
  }

  test("error class 4: empty roster keeps the legacy discovery hint") {
    val e = TransferFileTool.resolveDevice("ghost", Nil).left.toOption.getOrElse(fail("expected Left"))
    assert(e.message.startsWith("No peer devices discovered."), e.message)
  }

  test("error class 4: unknown friend target lists the available friends") {
    val e = TransferFileTool.resolveFriend("ghost", friends).left.toOption.getOrElse(fail("expected Left"))
    assert(e.message.contains("Friend 'ghost' not found"), e.message)
    assert(e.message.contains("Available friends: 林小满 [username lin@example.com], 王选 [username wangxuan]"), e.message)
  }

  // ── resolveDevice：歧义候选列表（批① B8，旧行为=静默首命中） ──

  test("resolveDevice: two fuzzy matches ⇒ error listing both candidates with their match reason") {
    val e = TransferFileTool.resolveDevice("mac", peers).left.toOption.getOrElse(fail("expected Left"))
    assert(e.message.contains("Device 'mac' is ambiguous (2 matches)"), e.message)
    assert(e.message.contains("MacBook-Air [deviceId 3f9c1a77-aaaa] — matched by deviceName prefix 'mac'"), e.message)
    assert(e.message.contains("MacMini [deviceId 9b22ee41-bbbb] — matched by deviceName prefix 'mac'"), e.message)
    assert(e.message.contains("Use the exact deviceId"), e.message)
    assertEquals(TransferFileTool.deviceMatches("mac", peers).size, 2)
  }

  test("resolveDevice: unique fuzzy candidate still resolves") {
    assertEquals(TransferFileTool.resolveDevice("ka", peers).map(_.deviceName), Right("kai-pc"))
  }

  test("resolveDevice: exact deviceName / deviceId resolve to the intended peer") {
    assertEquals(TransferFileTool.resolveDevice("MacMini", peers).map(_.deviceId), Right("9b22ee41-bbbb"))
    assertEquals(TransferFileTool.resolveDevice("c07d5a10-cccc", peers).map(_.deviceName), Right("kai-pc"))
  }

  // ── 三要素报文（原因 + 用法 + 候选） ─────────────────────

  test("errorBody: carries usage + device and friend candidates") {
    val body = TransferFileTool.errorBody("Invalid sourceDevice: 'Kai' has no prefix.", peers, friends)
    assert(body.contains("Correct usage"), body)
    assert(body.contains("Available devices: MacBook-Air [deviceId 3f9c1a77-aaaa]"), body)
    assert(body.contains("Available friends: 林小满 [username lin@example.com]"), body)
  }

  test("errorBody: empty rosters say so explicitly") {
    val body = TransferFileTool.errorBody("boom", Nil, Nil)
    assert(body.contains("Available devices: none"), body)
    assert(body.contains("Available friends: none"), body)
  }

  // ── 成功路径 1：`device:` → `device:` ────────────────────

  test("planTransfer: device: → device: resolves both peers (the mode that moves bytes)") {
    val plan = TransferFileTool.planTransfer(
      TransferFileTool.TargetRef.Device("MacBook-Air"),
      TransferFileTool.TargetRef.Device("kai-pc"),
      peers,
      friends
    )
    assertEquals(plan, Right(TransferFileTool.TransferPlan.DeviceToDevice(macBook, kaiPc)))
  }

  test("planTransfer: deviceId addressing works on both sides") {
    val plan = TransferFileTool.planTransfer(
      TransferFileTool.TargetRef.Device("c07d5a10-cccc"),
      TransferFileTool.TargetRef.Device("9b22ee41-bbbb"),
      peers,
      friends
    )
    assertEquals(plan, Right(TransferFileTool.TransferPlan.DeviceToDevice(kaiPc, macMini)))
  }

  test("planTransfer: unresolved target names the failing parameter and lists candidates") {
    val e = TransferFileTool
      .planTransfer(TransferFileTool.TargetRef.Device("MacBook-Air"), TransferFileTool.TargetRef.Device("ghost"), peers, friends)
      .left
      .toOption
      .getOrElse(fail("expected Left"))
    assert(e.message.contains("Invalid targetDevice 'device:ghost'"), e.message)
    assert(e.message.contains("Available devices: MacBook-Air"), e.message)
  }

  // ── 成功路径 2：`friend:` 路由 → 显式 unsupported ────────

  test("planTransfer: friend: source routes to the explicit 'not supported' error") {
    val e = TransferFileTool
      .planTransfer(TransferFileTool.TargetRef.Friend("wangxuan"), TransferFileTool.TargetRef.Device("kai-pc"), peers, friends)
      .left
      .toOption
      .getOrElse(fail("expected Left"))
    assert(e.message.contains("File transfer to/from a NebLink friend is not supported"), e.message)
    assert(e.message.contains("'friend:wangxuan' → 'device:kai-pc'"), e.message)
    assert(e.message.contains("pending batch"), e.message)
    assert(e.message.contains("Correct usage"), e.message)
    assert(e.message.contains("王选 [username wangxuan]"), e.message)
  }

  test("planTransfer: friend: target also routes to the explicit 'not supported' error") {
    val e = TransferFileTool
      .planTransfer(TransferFileTool.TargetRef.Device("MacBook-Air"), TransferFileTool.TargetRef.Friend("王选"), peers, friends)
      .left
      .toOption
      .getOrElse(fail("expected Left"))
    assert(e.message.contains("'device:MacBook-Air' → 'friend:王选'"), e.message)
    assert(e.message.contains("not supported"), e.message)
  }

  test("planTransfer: unknown friend is reported before the unsupported leg") {
    val e = TransferFileTool
      .planTransfer(TransferFileTool.TargetRef.Friend("ghost"), TransferFileTool.TargetRef.Device("kai-pc"), peers, friends)
      .left
      .toOption
      .getOrElse(fail("expected Left"))
    assert(e.message.contains("Invalid sourceDevice 'friend:ghost'"), e.message)
    assert(e.message.contains("not found"), e.message)
  }

  // ── call 入口：护栏真的在（无 NebLink 服务也能报对错） ─────

  test("call: bare local name target is rejected before any NebLink lookup") {
    TransferFileTool
      .call(input("sourceDevice" -> "device:MacBook-Air", "sourcePath" -> "a.txt", "targetDevice" -> "local"), ctxNoNeblink)
      .map { r =>
        val msg = errOf(r)
        assert(msg.contains("has no prefix"), msg)
        assert(msg.contains("Correct usage"), msg)
        assert(msg.contains("Available devices: none"), msg)
      }
  }

  test("call: `local:` source is rejected as a removed prefix") {
    TransferFileTool
      .call(input("sourceDevice" -> "local:/tmp/a.txt", "sourcePath" -> "a.txt", "targetDevice" -> "device:kai-pc"), ctxNoNeblink)
      .map { r =>
        val msg = errOf(r)
        assert(msg.contains("uses the removed `local` prefix"), msg)
      }
  }

  test("call: missing sourceDevice is a hard error — there is no `local` default any more") {
    TransferFileTool
      .call(input("sourcePath" -> "a.txt", "targetDevice" -> "device:kai-pc"), ctxNoNeblink)
      .map { r =>
        val msg = errOf(r)
        assert(msg.contains("sourceDevice is required"), msg)
        assert(!msg.contains("Copied"), msg)
      }
  }

  test("call: bare absolute path target is rejected with the local-path reason") {
    TransferFileTool
      .call(input("sourceDevice" -> "device:MacBook-Air", "sourcePath" -> "a.txt", "targetDevice" -> "/tmp/b.txt"), ctxNoNeblink)
      .map(r => assert(errOf(r).contains("looks like a local filesystem path")))
  }

  test("call: valid device: pair without NebLink keeps the legacy availability error") {
    TransferFileTool
      .call(input("sourceDevice" -> "device:MacBook-Air", "sourcePath" -> "a.txt", "targetDevice" -> "device:kai-pc"), ctxNoNeblink)
      .map(r => assertEquals(errOf(r), "NebLink not available — start nebflow on both devices and ensure they are connected"))
  }

  test("call: sourcePath is still required") {
    TransferFileTool
      .call(input("sourceDevice" -> "device:a", "targetDevice" -> "device:b"), ctxNoNeblink)
      .map(r => assertEquals(errOf(r), "sourcePath is required"))
  }

  // ── schema / 契约（registry 名与参数名不变） ──────────────

  test("contract: name, required list and schema wording") {
    assertEquals(TransferFileTool.name, "TransferFile")
    assertEquals(
      TransferFileTool.inputSchema("required"),
      Some(io.circe.Json.arr("sourceDevice".asJson, "sourcePath".asJson, "targetDevice".asJson))
    )
    val sourceDoc = TransferFileTool.inputSchema("properties")
      .flatMap(_.asObject)
      .flatMap(_("sourceDevice"))
      .flatMap(_.asObject)
      .flatMap(_("description"))
      .flatMap(_.asString)
      .getOrElse("")
    assert(sourceDoc.contains("device:<deviceName|deviceId>"), sourceDoc)
    assert(sourceDoc.contains("friend:<username|displayName>"), sourceDoc)
    assert(!sourceDoc.contains("Omit"), sourceDoc)
    assert(TransferFileTool.description.contains("`local` is NOT supported"), TransferFileTool.description)
  }

  test("summarize: no `local` default — missing values render as '?'") {
    assertEquals(
      TransferFileTool.summarize(input("sourcePath" -> "a.txt")),
      "TransferFile(?:a.txt → ?:a.txt)"
    )
  }
