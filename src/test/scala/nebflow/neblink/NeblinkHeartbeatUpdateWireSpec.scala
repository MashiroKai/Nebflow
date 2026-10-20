package nebflow.neblink

import io.circe.parser.*
import munit.FunSuite

import NeblinkCodecs.{given, *}

/**
 * 统一更新系统 M3 ③ 线格式定向验收：心跳响应 `update.target` 解码 + 心跳请求
 * `build`/`channel` 字段名对齐（与 link-server M2 收侧逐字对齐，防漂移）。
 *
 * 对齐锚 = link-server `model.rs`：`HeartbeatRequest{build,channel}`（serde
 * camelCase，单词不变形）/ `HeartbeatResponse{update:{target:{version,build,
 * channel,manifestUrl,publishedAt}}}`（`UpdateTargetView` 逐字）。
 */
class NeblinkHeartbeatUpdateWireSpec extends FunSuite:

  test("心跳响应 update.target 全字段解码（serde camelCase 键名逐字）") {
    val body = """{"peers": [],
      "update": {"target": {
        "version": "2026.10.20", "build": "71452cd2", "channel": "stable",
        "manifestUrl": "https://mystand.space/updates/manifest.json",
        "publishedAt": "2026-10-20T12:00:00Z"
      }}}"""
    val decoded = decode[HeartbeatResponse](body)
    assert(decoded.isRight, decoded)
    val hb = decoded.toOption.get
    val target = hb.update
    assert(target.isDefined, "update.target must decode")
    val t = target.get
    assertEquals(t.version, "2026.10.20")
    assertEquals(t.build, Some("71452cd2"))
    assertEquals(t.channel, "stable")
    assertEquals(t.manifestUrl, Some("https://mystand.space/updates/manifest.json"))
    assertEquals(t.publishedAt, Some("2026-10-20T12:00:00Z"))
  }

  test("已收敛设备（无 update 键）⇒ None —— wire 对 insync 多数保持字节稳定") {
    val body = """{"peers": [{"deviceId": "d1", "deviceName": "A", "platform": "mac",
      "endpoints": [], "online": true}]}"""
    val decoded = decode[HeartbeatResponse](body)
    assert(decoded.isRight, decoded)
    assertEquals(decoded.toOption.get.update, None)
    assertEquals(decoded.toOption.get.peers.map(_.deviceId), List("d1"))
  }

  test("update 键在但 target 缺失 ⇒ None（宽容解码，不炸心跳）") {
    val body = """{"peers": [], "update": {}}"""
    val decoded = decode[HeartbeatResponse](body)
    assert(decoded.isRight, decoded)
    assertEquals(decoded.toOption.get.update, None)
  }

  test("target build 可缺（旧 server 记账形态）⇒ None 字段，version 恒在") {
    val body = """{"peers": [], "update": {"target": {"version": "2026.10.20", "channel": "beta"}}}"""
    val decoded = decode[HeartbeatResponse](body)
    assert(decoded.isRight, decoded)
    val t = decoded.toOption.get.update
    assert(t.isDefined)
    assertEquals(t.get.build, None)
    assertEquals(t.get.channel, "beta")
  }

  test("target version 缺失（服务端记账违约）⇒ 整个 target 不可用（不猜）") {
    val body = """{"peers": [], "update": {"target": {"channel": "stable"}}}"""
    val decoded = decode[HeartbeatResponse](body)
    assert(decoded.isRight, decoded)
    assertEquals(decoded.toOption.get.update, None)
  }
end NeblinkHeartbeatUpdateWireSpec
