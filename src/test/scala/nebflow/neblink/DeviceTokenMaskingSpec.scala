package nebflow.neblink

import nebflow.neblink.relay.RelayTunnelDiagnostics

import munit.FunSuite

/**
 * 隧道诊断腿的凭据键覆盖（2026-11-04 两小件批 · 件一）。
 *
 * `RelayTunnelDiagnostics.redact` 是唯一会被写进**网关日志**的隧道失败体滤网
 * （体检报告 §3 的头注口径：token 形态一律不许进日志）。它的旧键表是三字面量
 * （`token|sessiontoken|devicetoken|refresh_token|access_token`）——缺
 * `device_token` 下划线变体、`clientSecret`、`password`、`apiKey`、`authorization`
 * 形；本 spec 把八个键全族钉死，同时钉住「服务端鉴权拒绝体逐字保留」这条
 * 有意的窄口径（那头是证据，不是凭据）。
 */
class DeviceTokenMaskingSpec extends FunSuite:

  private val FakeValue = "4f9a2c7e1b8d3056aa47e2c9f60b1d38"

  private val AllKeys =
    List("deviceToken", "accessToken", "refreshToken", "token", "clientSecret", "apiKey", "password", "authorization")

  test("tunnel leg (RelayTunnelDiagnostics.redact): every credential key is masked") {
    val leaks = AllKeys.filter { k =>
      RelayTunnelDiagnostics.redact(s"""{"$k":"$FakeValue"}""").contains(FakeValue)
    }
    assertEquals(leaks, Nil, s"这些键的值被原样回显（未掩码）：$leaks")
  }

  test("tunnel leg: underscore / hyphen / case variants are masked too") {
    val variants = List("device_token", "device-token", "DEVICE_TOKEN", "x-api-key", "client_secret")
    val leaks = variants.filter { k =>
      RelayTunnelDiagnostics.redact(s"""{"$k":"$FakeValue"}""").contains(FakeValue)
    }
    assertEquals(leaks, Nil, s"变体键未掩码：$leaks")
  }

  test("no over-mask: the auth-rejection body survives verbatim") {
    val authBody = """{"error":"Missing or invalid token"}"""
    assertEquals(RelayTunnelDiagnostics.redact(authBody), authBody)
  }

end DeviceTokenMaskingSpec
